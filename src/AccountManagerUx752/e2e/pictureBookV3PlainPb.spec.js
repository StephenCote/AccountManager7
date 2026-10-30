/**
 * PictureBook V3 PF-9 — the plain-PB (bookType STORY) REST-level proof on the Docker stack (§6.C 140/141).
 *
 * PF-9 (PbNodeExecutor, all four executors): a node that completes must have its inputHash stamped so
 * the next un-forced render treats it as CURRENT. Before the fix, executeLandscape (the plain-PB path —
 * dispatch sends a LANDSCAPE node of a non-CHAPBOOK book here) left inputHash null, so an un-forced
 * POST /scene/{id}/render on an already-rendered scene re-executed the node instead of refusing with
 * 409 "Nothing to render: every node of this scene is current (1 reused, 0 pinned)".
 * pictureBookV3Ux.spec.js proves the CHAPBOOK path (executeChapBookPage); this spec proves executeLandscape.
 *
 * Fixture (all over REST as e2etest_shared, no LLM): POST /picture-book/chapter {slug,title} creates a
 * STORY book with its own world and Book/Workflow/Artifacts groups but — unlike a ChapBook — no workflow
 * and no olio.pb.scene rows (a STORY book gets those only from the LLM+SD PB1 generate path). So the
 * spec seeds them directly over /rest/model, the same way the V3 spec seeds its nodes: one
 * olio.pb.workflow in the Workflow group, one olio.pb.scene (hand-written sdPrompt) in the Book group,
 * one LANDSCAPE olio.pb.node (scope scene) that the scene's sceneNode points at.
 *
 * Then: un-forced render → 202 → completes DONE_UNVERIFIED with an inputHash + artifact revision 1;
 * un-forced render again → 409 "(1 reused, 0 pinned)" [the PF-9 proof]; pinned + forced → 409
 * "Node … is pinned" (the facade refuses before planning, P7-5); unpinned + ?force=true → 202 →
 * revision 2 supersedes revision 1 and is the page's image.
 *
 * Live SD on .39 (two renders) — gated behind PB_SD_TESTS=1 and run serially:
 *
 *   cd src/AccountManagerUx752
 *   PB_SD_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3PlainPb.spec.js --workers=1 --project=chromium --retries=0
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) — never admin. Timings are logged, never
 * asserted. Every generated PNG is fetched from the media servlet, byte-checked and written to
 * test-results/pf9-plain/ so it can be opened and looked at.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/pf9-plain';

function b64(str) { return Buffer.from(str).toString('base64'); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }
function shortRef(oid) { return String(oid).replace(/-/g, '').slice(0, 8); }

// The only credential source: what ensureSharedTestUser() returns (set in beforeAll), never a literal.
let shared = null;

function sharedCredential() {
    if (!shared) throw new Error('login before ensureSharedTestUser() — the beforeAll must set the shared user first');
    return {
        schema: 'auth.credential',
        organizationPath: '/Development',
        name: shared.testUserName,
        credential: b64(shared.testPassword),
        type: 'hashed_password'
    };
}

async function login(request) {
    const resp = await request.post(REST + '/login', { data: sharedCredential() });
    expect(resp.ok() || resp.status() === 204, 'login as ' + shared.testUserName + ': HTTP ' + resp.status()).toBe(true);
}

// Canonical page login (chapBook.spec.js): API login on the page's request context, WebSocket stub
// BEFORE page.goto (Docker nginx drops the cookie on the WS upgrade → forceLogin → #!/sig).
async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', { data: sharedCredential() });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login as ' + shared.testUserName + ' failed: HTTP ' + resp.status());
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0;
                this.onopen = null; this.onclose = null;
                this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => {
                    this.readyState = 1;
                    if (this.onopen) this.onopen({ type: 'open', target: this });
                }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0;
        window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2;
        window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function search(request, type, fields, requestFields) {
    const resp = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, fields, request: requestFields, recordCount: 1, cache: false }
    });
    expect(resp.ok(), 'search ' + type + ': HTTP ' + resp.status()).toBe(true);
    const body = await resp.json();
    return body && body.results && body.results[0];
}

/** Fetch an artifact's PNG bytes through data.data → the media servlet; byte-check; write to OUT_DIR. */
async function savePng(request, PB, artifactObjectId, fileName) {
    const artResp = await request.get(PB + '/artifact/' + artifactObjectId);
    expect(artResp.ok(), 'GET artifact ' + artifactObjectId + ': HTTP ' + artResp.status()).toBe(true);
    const art = await artResp.json();
    expect(art.dataObjectId, 'artifact.dataObjectId').toBeTruthy();
    const dataResp = await request.get(REST + '/model/data.data/' + art.dataObjectId);
    expect(dataResp.ok(), 'GET data.data: HTTP ' + dataResp.status()).toBe(true);
    const dataRec = await dataResp.json();
    expect(dataRec && dataRec.groupPath && dataRec.name, 'data.data carries groupPath + name').toBeTruthy();
    const mediaUrl = BASE_URL + '/AccountManagerService7/media/Development/data.data' + dataRec.groupPath + '/' + encodeURIComponent(dataRec.name);
    const media = await request.get(mediaUrl);
    expect(media.ok(), 'GET media ' + mediaUrl + ' HTTP ' + media.status()).toBe(true);
    let raw = await media.body();
    if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
    expect(raw.subarray(0, 8).toString('hex'), fileName + ' bytes are a PNG').toBe('89504e470d0a1a0a');
    const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
    expect(dims[0] > 0 && dims[1] > 0, 'PNG has dimensions').toBe(true);
    mkdirSync(OUT_DIR, { recursive: true });
    const pngPath = OUT_DIR + '/' + fileName;
    writeFileSync(pngPath, raw);
    await test.info().attach(fileName, { path: pngPath, contentType: 'image/png' });
    console.log('[pf9-png] ' + fileName + ' ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) role=' + art.role
        + ' revision=' + art.revision + ' selected=' + art.selected + ' supersedes=' + art.supersedesObjectId
        + ' backend=' + art.backend + ' seed=' + art.seed + ' durationMs=' + art.durationMs + ' reviewStatus=' + art.reviewStatus
        + ' -> ' + pngPath);
    return { art, dims, pngPath };
}

/** POST /scene/{id}/render (optionally forced); returns {status, text}. */
async function postRender(request, PB, sceneObjectId, force) {
    const resp = await request.post(PB + '/scene/' + sceneObjectId + '/render' + (force ? '?force=true' : ''));
    const text = await resp.text();
    return { status: resp.status(), text };
}

/** Poll GET /rest/job/{id} every 3 s until terminal; logs every phase change; returns the terminal job. */
async function watchJob(request, jobId, tag, submittedAt) {
    let job = null;
    let lastPhase = null;
    const deadline = Date.now() + 600000;
    while (Date.now() < deadline) {
        const r = await request.get(REST + '/job/' + jobId);
        expect(r.ok(), 'GET /job/{id}: HTTP ' + r.status()).toBe(true);
        job = await r.json();
        const phase = job.status + '/' + job.phase + '/' + job.current + '/' + job.total;
        if (phase !== lastPhase) { console.log('[' + tag + '-job] +' + (Date.now() - submittedAt) + 'ms ' + phase); lastPhase = phase; }
        if (job.terminal) break;
        await sleep(3000);
    }
    console.log('[' + tag + '-job] terminal after ' + (Date.now() - submittedAt) + 'ms: ' + JSON.stringify({ status: job.status, cancelled: job.cancelled, error: job.error, result: job.result }));
    expect(job.terminal, 'job reached a terminal state: ' + JSON.stringify(job)).toBe(true);
    expect(job.status, 'job status: ' + JSON.stringify(job)).toBe('completed');
    expect(job.cancelled).toBe(false);
    expect(upper(job.result && job.result.runStatus), 'result.runStatus').toBe('COMPLETED');
    expect(job.result.executedNodeCount, 'one node executed').toBe(1);
    expect(job.result.failedNodeCount).toBe(0);
    return job;
}

async function nodeRow(request, n) {
    return search(request, 'olio.pb.node', [{ name: 'objectId', comparator: 'equals', value: n.objectId }],
        ['id', 'objectId', 'handle', 'nodeStatus', 'inputHash', 'pinned']);
}

/** Reader helpers. */
async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
}
async function openFirstPage(page) {
    await page.locator('button', { hasText: 'Begin' }).click();
    await expect(page.getByText(/^Page 1 of \d+$/).first()).toBeVisible();
}

// Seeded in beforeAll. The fixture rows are left in place as the record of the run (the run rows and
// artifacts reference the nodes by FK); afterAll records what was created.
let orgId = null;
let bookObjectId = null;
let slug = null;
const fx = { book: null, workflow: null, scene: null, nodeL: null, handleL: null };
const r1 = { jobId: null, runObjectId: null, artifactObjectId: null };
const r2 = { jobId: null, runObjectId: null, artifactObjectId: null };

test.describe('PictureBook V3 PF-9 — plain-PB (STORY) render path over REST', () => {
    // Serial: every test needs the state the previous one produced. 15 minutes per test: one .39 render
    // plus REST round trips. Never a threshold.
    test.describe.configure({ mode: 'serial', timeout: 900000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        if (!SD_ENABLED) return;
        shared = await ensureSharedTestUser(request);
        await login(request);
        const ts = Date.now().toString(36);

        // 1. A fresh STORY book: /chapter → PbServiceFacade.createChapter → PbBookUtil.writeBookRow (bookType STORY),
        //    its own world and the Book/Workflow/Artifacts groups under /Olio/Universes/Books/Worlds/{slug}.
        slug = 'pf9-plain-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'PF-9 Plain PB ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(createText);
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded STORY book objectId: ' + createText.slice(0, 300)).toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;

        const bookFull = await (await request.get(REST + '/model/olio.pb.book/' + bookObjectId + '/full')).json();
        expect(upper(bookFull.bookType), 'seeded book is STORY (dispatch → executeLandscape, not executeChapBookPage): ' + JSON.stringify(bookFull).slice(0, 300)).toBe('STORY');
        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId']);
        expect(book && book.id && book.groupId && book.groupPath, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        orgId = book.organizationId;
        expect(orgId, 'book organizationId').toBeTruthy();
        expect(book.groupPath, 'book row lives in the Book group').toMatch(/\/Book$/);
        fx.book = { id: book.id, objectId: book.objectId, name: book.name, groupId: book.groupId, groupPath: book.groupPath };
        const bookRef = { schema: 'olio.pb.book', id: book.id, objectId: book.objectId };

        // 2. No workflow and no scenes yet: a STORY book gets those only from the PB1 generate path.
        const wfMissing = await request.get(PB + '/workflow');
        const wfMissingText = await wfMissing.text();
        console.log('[pf9-seed] GET /workflow before seeding: HTTP ' + wfMissing.status() + ' ' + wfMissingText.slice(0, 200));
        expect(wfMissing.status(), 'a fresh STORY book has no workflow').toBe(404);
        const pagesBefore = await (await request.get(PB + '/pages')).json();
        expect(Array.isArray(pagesBefore) && pagesBefore.length === 0, 'a fresh STORY book has no pages: ' + JSON.stringify(pagesBefore).slice(0, 200)).toBe(true);

        // 3. The Workflow group is the Book group's sibling under the book's container group.
        const bookGroup = await search(request, 'auth.group', [
            { name: 'id', comparator: 'equals', value: book.groupId },
            { name: 'organizationId', comparator: 'equals', value: orgId }
        ], ['id', 'objectId', 'name', 'parentId', 'path']);
        expect(bookGroup && bookGroup.parentId, 'Book group: ' + JSON.stringify(bookGroup)).toBeTruthy();
        const wfGroup = await search(request, 'auth.group', [
            { name: 'name', comparator: 'equals', value: 'Workflow' },
            { name: 'parentId', comparator: 'equals', value: bookGroup.parentId },
            { name: 'organizationId', comparator: 'equals', value: orgId }
        ], ['id', 'objectId', 'name', 'parentId', 'path']);
        expect(wfGroup && wfGroup.id && wfGroup.path, 'Workflow group: ' + JSON.stringify(wfGroup)).toBeTruthy();
        expect(wfGroup.path).toBe(book.groupPath.replace(/\/Book$/, '/Workflow'));

        // 4. The workflow row, as PbGraphUtil.getCreateWorkflow would write it (name "Workflow <book.name>").
        const wfResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.workflow', name: 'Workflow ' + book.name, groupId: wfGroup.id, groupPath: wfGroup.path,
                book: bookRef, graphVersion: 1, graphStatus: 'dirty', nodeCount: 0 }
        });
        const wfText = await wfResp.text();
        expect(wfResp.ok(), 'create olio.pb.workflow: HTTP ' + wfResp.status() + ' ' + wfText.slice(0, 300)).toBe(true);
        const wf = JSON.parse(wfText);
        expect(wf && wf.id && wf.objectId, 'workflow identity').toBeTruthy();
        fx.workflow = { id: wf.id, objectId: wf.objectId, groupId: wfGroup.id, groupPath: wfGroup.path };
        const wfRef = { schema: 'olio.pb.workflow', id: wf.id, objectId: wf.objectId };
        const wfv = await (await request.get(PB + '/workflow')).json();
        expect(wfv && wfv.workflowObjectId, 'GET /workflow resolves the seeded workflow: ' + JSON.stringify(wfv).slice(0, 300)).toBe(wf.objectId);

        // 5. One scene in the Book group with a hand-written landscape prompt (executeLandscape uses scene.sdPrompt; no LLM).
        const sceneResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.scene', name: 'Scene ' + slug + ' 0', groupId: book.groupId, groupPath: book.groupPath,
                book: bookRef, sceneIndex: 0, title: 'The lake at dawn', mood: 'calm',
                sdPrompt: 'a quiet misty lake at dawn, pine forest on the far shore, a single rowing boat, painterly, soft light, wide natural vista' }
        });
        const sceneText = await sceneResp.text();
        expect(sceneResp.ok(), 'create olio.pb.scene: HTTP ' + sceneResp.status() + ' ' + sceneText.slice(0, 300)).toBe(true);
        const scene = JSON.parse(sceneText);
        expect(scene && scene.id && scene.objectId, 'scene identity').toBeTruthy();
        fx.scene = { id: scene.id, objectId: scene.objectId };
        const pagesSeeded = await (await request.get(PB + '/pages')).json();
        const pg0 = pagesSeeded.find(p => p.objectId === scene.objectId);
        expect(pg0, 'GET /pages lists the seeded scene: ' + JSON.stringify(pagesSeeded).slice(0, 300)).toBeTruthy();
        expect(pg0.sceneIndex).toBe(0);
        expect(pg0.sceneNodeObjectId == null && pg0.artifactObjectId == null, 'no node, no image yet: ' + JSON.stringify(pg0)).toBe(true);

        // 6. L (landscape, scene scope) — the scene's sceneNode.
        fx.handleL = 'landscape@' + shortRef(scene.objectId);
        const lResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.node', name: fx.handleL, groupId: wfGroup.id, groupPath: wfGroup.path, workflow: wfRef,
                handle: fx.handleL, nodeType: 'landscape', nodeStatus: 'pending', ordinal: 0, scope: 'scene', scopeRef: scene.objectId, sceneIndex: 0 }
        });
        const lText = await lResp.text();
        expect(lResp.ok(), 'create node L: HTTP ' + lResp.status() + ' ' + lText.slice(0, 300)).toBe(true);
        const L = JSON.parse(lText);
        fx.nodeL = { id: L.id, objectId: L.objectId };
        const sceneNodeResp = await request.patch(REST + '/model', {
            data: { schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId, sceneNode: { schema: 'olio.pb.node', id: L.id, objectId: L.objectId } }
        });
        expect(sceneNodeResp.ok(), 'PATCH scene.sceneNode: HTTP ' + sceneNodeResp.status()).toBe(true);

        const pagesAfter = await (await request.get(PB + '/pages')).json();
        const pg = pagesAfter.find(p => p.objectId === scene.objectId);
        expect(pg && pg.sceneNodeObjectId, 'page carries sceneNodeObjectId: ' + JSON.stringify(pg)).toBe(L.objectId);
        expect(upper(pg.nodeStatus)).toBe('PENDING');
        expect(pg.artifactObjectId == null && pg.dataObjectId == null, 'page has no image before the render: ' + JSON.stringify(pg)).toBe(true);
        const lSeeded = await nodeRow(request, fx.nodeL);
        console.log('[pf9-seed-node] ' + JSON.stringify(lSeeded));
        expect(upper(lSeeded.nodeStatus)).toBe('PENDING');
        expect(lSeeded.inputHash == null || lSeeded.inputHash === '', 'no inputHash before the first render').toBe(true);

        console.log('[pf9-seed] ' + JSON.stringify({ book: bookObjectId, slug, orgId, ...fx }));
        await request.get(REST + '/logout');
    });

    test.afterAll(async ({ request }) => {
        if (!SD_ENABLED || !bookObjectId) return;
        console.log('[pf9-cleanup] left in place (run rows + artifacts reference them): ' + JSON.stringify({ book: bookObjectId, slug, ...fx, r1, r2 }));
    });

    test('[SD] render 1: an un-forced POST /scene/{id}/render on a fresh STORY scene plans L, completes DONE_UNVERIFIED with an inputHash and artifact revision 1', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD renders');
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const submittedAt = Date.now();
        const sub = await postRender(request, PB, fx.scene.objectId, false);
        console.log('[pf9-r1-submit] HTTP ' + sub.status + ' ' + sub.text.slice(0, 600));
        expect(sub.status, 'POST /scene/{id}/render: ' + sub.text.slice(0, 400)).toBe(202);
        const dto = JSON.parse(sub.text);
        expect(dto.sceneObjectId).toBe(fx.scene.objectId);
        expect(dto.sceneIndex).toBe(0);
        expect(dto.jobId, 'jobId').toBeTruthy();
        expect(dto.runObjectId, 'runObjectId').toBeTruthy();
        expect(dto.kind).toBe('pb.render');
        expect(dto.planned, 'L alone is planned').toEqual([fx.handleL]);
        expect(dto.plannedNodeCount).toBe(1);
        expect(dto.reusedNodeCount).toBe(0);
        expect(dto.pinnedNodeCount).toBe(0);
        r1.jobId = dto.jobId;
        r1.runObjectId = dto.runObjectId;

        const job = await watchJob(request, dto.jobId, 'pf9-r1', submittedAt);
        const oL = (job.result.nodes || []).find(n => n.nodeObjectId === fx.nodeL.objectId);
        expect(oL && oL.artifactObjectId, 'L outcome: ' + JSON.stringify(job.result.nodes)).toBeTruthy();
        // executeLandscape completes the LANDSCAPE node DONE_UNVERIFIED (no composite/verify stage).
        expect(upper(oL.status), 'landscape outcome').toBe('DONE_UNVERIFIED');
        r1.artifactObjectId = oL.artifactObjectId;

        // Durable state: the node row carries the inputHash PF-9 stamps (what makes render 2 a 409).
        const lAfter = await nodeRow(request, fx.nodeL);
        console.log('[pf9-r1-node] ' + JSON.stringify(lAfter));
        expect(upper(lAfter.nodeStatus)).toBe('DONE_UNVERIFIED');
        expect(lAfter.inputHash, 'PF-9: executeLandscape stamps inputHash on completion').toBeTruthy();
        expect(!!lAfter.pinned).toBe(false);
        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        console.log('[pf9-r1-page] ' + JSON.stringify(pg));
        expect(pg.artifactObjectId, 'page.artifactObjectId').toBe(oL.artifactObjectId);
        expect(pg.dataObjectId && pg.imageGroupPath && pg.imageName, 'page carries the image reference').toBeTruthy();
        expect(pg.imageStale, 'page.imageStale').toBe(false);
        expect(upper(pg.nodeStatus)).toBe('DONE_UNVERIFIED');
        const run = await (await request.get(PB + '/run/' + dto.runObjectId)).json();
        expect(upper(run.runStatus)).toBe('COMPLETED');
        expect(run.jobId == null, 'run no longer holds a job once terminal: ' + run.jobId).toBe(true);

        const land = await savePng(request, PB, oL.artifactObjectId, 'r1-landscape.png');
        expect(upper(land.art.role)).toBe('LANDSCAPE');
        expect(land.art.revision, 'first revision').toBe(1);
        expect(land.art.selected).toBe(true);
        expect(land.art.supersedesObjectId == null, 'revision 1 supersedes nothing: ' + land.art.supersedesObjectId).toBe(true);
        console.log('[pf9-r1-timing] submit->terminal ' + (Date.now() - submittedAt) + 'ms; job.result.durationMs=' + job.result.durationMs + ' (logged, never asserted)');
        await request.get(REST + '/logout');
    });

    test('PF-9 proof: an un-forced render of the now-current scene is refused 409 "(1 reused, 0 pinned)"; pinned, even a forced render is refused 409 "Node … is pinned"', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the render from test 1');
        expect(r1.artifactObjectId, 'render 1 produced an artifact').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const lBefore = await nodeRow(request, fx.nodeL);
        console.log('[pf9-409-before] ' + JSON.stringify(lBefore));
        expect(upper(lBefore.nodeStatus)).toBe('DONE_UNVERIFIED');
        expect(lBefore.inputHash, 'L inputHash').toBeTruthy();
        expect(!!lBefore.pinned).toBe(false);

        // The PF-9 proof: before the fix executeLandscape left inputHash null, L was never CURRENT and this
        // un-forced POST was accepted (202) and re-rendered the scene. Now it is refused.
        const unforced = await postRender(request, PB, fx.scene.objectId, false);
        console.log('[pf9-409-unforced] HTTP ' + unforced.status + ' ' + unforced.text.slice(0, 300));
        expect(unforced.status, 'un-forced render of a current STORY scene is refused: ' + unforced.text).toBe(409);
        expect(JSON.parse(unforced.text).error).toMatch(/^Nothing to render: every node of this scene is current \(1 reused, 0 pinned\)$/);

        // No new run, no new artifact: the node row is untouched.
        const lAfter = await nodeRow(request, fx.nodeL);
        expect(lAfter.inputHash).toBe(lBefore.inputHash);
        expect(upper(lAfter.nodeStatus)).toBe('DONE_UNVERIFIED');
        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        expect(pg.artifactObjectId, 'the page still shows revision 1').toBe(r1.artifactObjectId);

        // Pinned: the pin wins over force. Since P7-5 (Scenario D) the refusal is the facade's own "Node … is
        // pinned" (the regenerate / ChapBook page text), not the planner's "(0 reused, 1 pinned)": the facade
        // refuses before planning so a pinned scene with a STALE upstream is refused too.
        const pinResp = await request.post(PB + '/node/' + fx.nodeL.objectId + '/pin', { data: { pinned: true } });
        const pinText = await pinResp.text();
        expect(pinResp.status(), 'pin L: ' + pinText).toBe(200);
        expect(JSON.parse(pinText).pinned).toBe(true);
        const pinnedForced = await postRender(request, PB, fx.scene.objectId, true);
        console.log('[pf9-409-pinned] HTTP ' + pinnedForced.status + ' ' + pinnedForced.text.slice(0, 300));
        expect(pinnedForced.status, 'forced render of a pinned scene is refused: ' + pinnedForced.text).toBe(409);
        expect(JSON.parse(pinnedForced.text).error).toBe('Node ' + fx.handleL + ' is pinned. Unpin it first - pinning means do not replace this artifact.');
        const pgPinned = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        expect(pgPinned.pinned, 'page reports the pin').toBe(true);

        // Unpin for test 3.
        const unpinResp = await request.post(PB + '/node/' + fx.nodeL.objectId + '/pin', { data: { pinned: false } });
        const unpinText = await unpinResp.text();
        expect(unpinResp.status(), 'unpin L: ' + unpinText).toBe(200);
        expect(JSON.parse(unpinText).pinned).toBe(false);
        expect(!!(await nodeRow(request, fx.nodeL)).pinned).toBe(false);
        await request.get(REST + '/logout');
    });

    test('[SD] render 2: ?force=true on the current scene re-rolls L; revision 2 supersedes revision 1 and is the page\'s image', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the render from test 1');
        expect(r1.artifactObjectId, 'render 1 produced an artifact').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const submittedAt = Date.now();
        const sub = await postRender(request, PB, fx.scene.objectId, true);
        console.log('[pf9-r2-submit] HTTP ' + sub.status + ' ' + sub.text.slice(0, 600));
        expect(sub.status, 'forced render of a current scene is accepted: ' + sub.text.slice(0, 400)).toBe(202);
        const dto = JSON.parse(sub.text);
        expect(dto.runObjectId, 'a new run, not render 1\'s').not.toBe(r1.runObjectId);
        expect(dto.planned, 'the forced terminal is planned').toEqual([fx.handleL]);
        expect(dto.plannedNodeCount).toBe(1);
        expect(dto.reusedNodeCount).toBe(0);
        expect(dto.pinnedNodeCount).toBe(0);
        r2.jobId = dto.jobId;
        r2.runObjectId = dto.runObjectId;

        const job = await watchJob(request, dto.jobId, 'pf9-r2', submittedAt);
        const oL = (job.result.nodes || []).find(n => n.nodeObjectId === fx.nodeL.objectId);
        expect(oL && oL.artifactObjectId, 'L outcome: ' + JSON.stringify(job.result.nodes)).toBeTruthy();
        expect(upper(oL.status)).toBe('DONE_UNVERIFIED');
        expect(oL.artifactObjectId, 'a new artifact revision, not render 1\'s').not.toBe(r1.artifactObjectId);
        r2.artifactObjectId = oL.artifactObjectId;

        const lAfter = await nodeRow(request, fx.nodeL);
        console.log('[pf9-r2-node] ' + JSON.stringify(lAfter));
        expect(upper(lAfter.nodeStatus)).toBe('DONE_UNVERIFIED');
        expect(lAfter.inputHash, 'inputHash re-stamped').toBeTruthy();
        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        console.log('[pf9-r2-page] ' + JSON.stringify(pg));
        expect(pg.artifactObjectId, 'the page now shows the forced render').toBe(oL.artifactObjectId);
        expect(pg.imageStale).toBe(false);
        expect(!!pg.pinned).toBe(false);
        const run = await (await request.get(PB + '/run/' + dto.runObjectId)).json();
        expect(upper(run.runStatus)).toBe('COMPLETED');

        const forced = await savePng(request, PB, oL.artifactObjectId, 'r2-landscape-forced.png');
        expect(upper(forced.art.role)).toBe('LANDSCAPE');
        const prior = await (await request.get(PB + '/artifact/' + r1.artifactObjectId)).json();
        expect(prior.objectId, 'revision 1 is kept in the history').toBe(r1.artifactObjectId);
        console.log('[pf9-r2-revision] prior=' + prior.revision + ' selected=' + prior.selected + ' reviewStatus=' + prior.reviewStatus
            + ' -> forced=' + forced.art.revision + ' selected=' + forced.art.selected + ' supersedes=' + forced.art.supersedesObjectId);
        expect(forced.art.revision, 'the forced render is revision 2').toBe(2);
        expect(forced.art.supersedesObjectId, 'the forced render supersedes revision 1').toBe(prior.objectId);
        expect(forced.art.selected, 'the forced render is selected (revision 1 was GENERATED, not APPROVED)').toBe(true);
        expect(prior.selected, 'revision 1 is kept but no longer selected').toBe(false);

        // And once more un-forced: current again → 409.
        const again = await postRender(request, PB, fx.scene.objectId, false);
        console.log('[pf9-r2-unforced-again] HTTP ' + again.status + ' ' + again.text.slice(0, 300));
        expect(again.status).toBe(409);
        expect(JSON.parse(again.text).error).toMatch(/^Nothing to render: every node of this scene is current \(1 reused, 0 pinned\)$/);
        console.log('[pf9-r2-timing] submit->terminal ' + (Date.now() - submittedAt) + 'ms; job.result.durationMs=' + job.result.durationMs + ' (logged, never asserted)');
        await request.get(REST + '/logout');
    });

    test('reader: the PB2 reader shows the forced image for the STORY scene (chip Review, no stale badge, button Re-render)', async ({ page }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the renders from tests 1 and 3');
        expect(r2.artifactObjectId, 'render 2 produced an artifact').toBeTruthy();
        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await expect(page.locator('[data-pb2-cover-count]')).toHaveText(/^1 rendered of 1 scene/);
        await openFirstPage(page);
        const img = page.getByTestId('pb2-scene-image');
        await expect(img).toBeVisible();
        await expect.poll(async () => img.evaluate(el => el.complete && el.naturalWidth > 0), { timeout: 60000, message: 'scene image decoded in the browser' }).toBe(true);
        const natural = await img.evaluate(el => [el.naturalWidth, el.naturalHeight]);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');
        await expect(page.getByTestId('pb2-stale-badge')).toHaveCount(0);
        await expect(page.getByTestId('pb2-scene-render')).toHaveText(/Re-render$/);
        await expect(page.getByTestId('pb2-scene-render')).toBeEnabled();
        await expect(page.getByTestId('pb2-run-progress')).toHaveCount(0);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/reader-forced.png', fullPage: true });
        console.log('[pf9-reader] reader shows the scene image ' + natural[0] + 'x' + natural[1] + ' -> ' + OUT_DIR + '/reader-forced.png');
    });
});
