/**
 * PictureBook V3 P1-10 — the Phase 1 exit criteria, exercised through the Ux752 reader against the
 * Docker stack (design §30 row 1):
 *
 *   1. Leave the page → the render continues. A scene render is started from the PB2 reader
 *      (#!/picture-book/v2/{book}); the reader is left while the run is in flight; the job is watched
 *      from outside the page until it is terminal; the reader is reopened and the scene shows its image.
 *   1b. (§6.C 109) The reader's Re-render on a CURRENT scene is an explicit re-roll: the click POSTs
 *      /scene/{id}/render?force=true and the planner re-executes the scene node (the current upstream
 *      portrait is reused), whereas the same route without force is a 409 "(N reused, 0 pinned)". A pin
 *      still wins over force: on a pinned scene the click is a 409 "(1 reused, 1 pinned)" shown verbatim in
 *      the "Scene is pinned" confirm; Cancel never calls the pin route; "Unpin and re-render" sends
 *      POST /node/{n}/pin {pinned:false} and the forced render once more.
 *   3. A charPerson edit shows the affected scenes. In the standalone character manager (reader header
 *      "Manage characters"), editing the name of the character the rendered node is bound to shows
 *      "Affected scenes: 1 (…)" with a "1 stale" chip plus Review / Regenerate, and the reader's scene
 *      chip reads "Stale" with the stale badge over the (still visible) image.
 *
 *   (Criterion 2 — restart → Resume — bounces the container and lives in pictureBookRestartRecovery.spec.js.)
 *
 * The fixture is a fresh ChapBook (bookType CHAPBOOK) whose first page gets a two-node graph seeded over
 * REST as e2etest_shared: a PORTRAIT node P (scope character → the test charPerson, whose narrative carries
 * a hand-written sdPrompt so no LLM stage runs) bound as the `portrait` source of a LANDSCAPE node L
 * (scope scene, the page's sceneNode, hand-written scene sdPrompt). POST /scene/{id}/render plans P then
 * L (upstreamOf), so one click is two live SD renders and both nodes complete with an inputHash — which
 * is what makes the character edit in criterion 3 drift P and propagate STALE to L (and so to the page).
 *
 * Live SD on .39 (four renders: two in criterion 1, two in 1b) — gated behind PB_SD_TESTS=1 and run serially:
 *
 *   cd src/AccountManagerUx752
 *   PB_SD_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3Ux.spec.js --workers=1 --project=chromium
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) — never admin. Timings are logged, never
 * asserted. Every generated PNG is fetched from the media servlet, byte-checked and written to
 * test-results/p110/ so it can be opened and looked at.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';
const OUT_DIR = 'test-results/p110';

function b64(str) { return Buffer.from(str).toString('base64'); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }
function shortRef(oid) { return String(oid).replace(/-/g, '').slice(0, 8); }

// The only credential source: what ensureSharedTestUser() returns (set in each beforeAll), never a literal.
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

async function makePath(request, path) {
    const resp = await request.get(REST + '/path/make/auth.group/data/B64-' + b64(path).replace(/=/g, '%3D'));
    expect(resp.ok(), 'path/make ' + path + ': HTTP ' + resp.status()).toBe(true);
    const g = await resp.json();
    expect(g && g.id, path + ' group id').toBeTruthy();
    return g;
}

const POEM = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.
A precarious branch
Mourning its creased skein's blanch.`;

async function findOrCreatePoem(request, orgId, groupId, name, title, text) {
    const existing = await search(request, 'olio.cb.poem', [
        { name: 'name', comparator: 'equals', value: name },
        { name: 'organizationId', comparator: 'equals', value: orgId }
    ], ['id', 'objectId']);
    if (existing && existing.objectId) return existing.objectId;
    const resp = await request.post(REST + '/model', {
        data: { schema: 'olio.cb.poem', name, title, author: 'P110 Test', groupId, text }
    });
    expect(resp.ok(), 'create poem ' + name + ': HTTP ' + resp.status()).toBe(true);
    const created = await resp.json();
    expect(created && created.objectId, 'poem ' + name + ' objectId').toBeTruthy();
    return created.objectId;
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
    console.log('[p110-png] ' + fileName + ' ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) role=' + art.role
        + ' backend=' + art.backend + ' seed=' + art.seed + ' durationMs=' + art.durationMs + ' reviewStatus=' + art.reviewStatus
        + ' -> ' + pngPath);
    return { art, dims, pngPath };
}

/** Reader helpers. */
async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
}
async function openFirstPage(page) {
    await page.locator('button', { hasText: 'Begin' }).click();
    // "Page 1 of N" is in both the header label and the page footer.
    await expect(page.getByText(/^Page 1 of \d+$/).first()).toBeVisible();
}

// Seeded in beforeAll. The fixture rows are left in place as the record of the run (the run rows and
// artifacts reference the nodes by FK); afterAll records what was created.
let orgId = null;
let bookObjectId = null;
let slug = null;
const fx = { narrative: null, charPerson: null, charName: null, scene: null, sceneIndex: null, nodeP: null, nodeL: null, handleP: null, handleL: null, bindingObjectId: null };
let c1 = { jobId: null, runObjectId: null, outcomes: null };

test.describe('PictureBook V3 P1-10 — Phase 1 exit criteria through the reader UI', () => {
    // Serial: criterion 3 needs the node rendered by criterion 1 (an inputHash is what makes a node
    // stale-scan eligible). 15 minutes per test: two .39 renders plus page loads. Never a threshold.
    test.describe.configure({ mode: 'serial', timeout: 900000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        if (!SD_ENABLED) return;
        shared = await ensureSharedTestUser(request);
        await login(request);

        const poemsGroup = await makePath(request, '~/Poems');
        orgId = poemsGroup.organizationId;
        expect(orgId, '~/Poems organizationId').toBeTruthy();
        const poemOid = await findOrCreatePoem(request, orgId, poemsGroup.id, 'p110-ux-poem-alpha', 'Falling Leaves (P110)', POEM);

        // 1. A fresh ChapBook (ChapBookUtil.createChapBook patches bookType=CHAPBOOK right after createBook).
        slug = 'p110-ux-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'P1-10 Reader UX ChapBook', poemObjectIds: [poemOid], maxLinesPerPage: 20 }
        });
        const createText = await createResp.text();
        expect(createResp.ok(), 'ChapBook create: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        bookObjectId = JSON.parse(createText).objectId;
        expect(bookObjectId, 'seeded ChapBook objectId').toBeTruthy();
        const PB = REST + '/olio/picture-book/' + bookObjectId;

        const bookFull = await (await request.get(REST + '/model/olio.pb.book/' + bookObjectId + '/full')).json();
        expect(upper(bookFull.bookType), 'seeded book is a CHAPBOOK (the LANDSCAPE page path that stamps inputHash)').toBe('CHAPBOOK');
        expect(bookFull.world && bookFull.world.objectId, 'book.world').toBeTruthy();

        // 2. GET /{book}/characters resolves the book through ~/Data/PictureBooks/{slug}; a ChapBook-created
        //    book has no such group, so the reader's Characters button would 404 without this.
        await makePath(request, '~/Data/PictureBooks/' + slug);

        // 3. The character lives in the world's population group so /characters lists it.
        const world = await (await request.get(REST + '/model/olio.world/' + bookFull.world.objectId + '/full')).json();
        const pop = world && world.population;
        expect(pop && pop.id && pop.path, 'world.population group: ' + JSON.stringify(pop)).toBeTruthy();

        const ts = Date.now().toString(36);
        const narrativeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.narrative',
                name: 'p110-ux-narrative-' + ts,
                groupId: pop.id, groupPath: pop.path,
                sdPrompt: 'a woman in her thirties with braided auburn hair and freckles, wearing a dark green wool cloak over a linen blouse, head and shoulders portrait, soft overcast daylight, painterly',
                physicalDescription: 'braided auburn hair, freckles',
                outfitDescription: 'dark green wool cloak over a linen blouse'
            }
        });
        expect(narrativeResp.ok(), 'create olio.narrative: HTTP ' + narrativeResp.status()).toBe(true);
        const narrative = await narrativeResp.json();
        fx.narrative = narrative.objectId;

        fx.charName = 'p110-ux-char-' + ts;
        const cpResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.charPerson',
                name: fx.charName,
                firstName: 'Wren', middleName: 'Ash', lastName: 'Hollis',
                gender: 'female', alignment: 'neutralgood',
                groupId: pop.id, groupPath: pop.path
            }
        });
        expect(cpResp.ok(), 'create olio.charPerson: HTTP ' + cpResp.status()).toBe(true);
        const cp = await cpResp.json();
        expect(cp && cp.objectId && cp.id, 'charPerson identity').toBeTruthy();
        fx.charPerson = cp.objectId;
        const linkResp = await request.patch(REST + '/model', {
            data: { schema: 'olio.charPerson', id: cp.id, objectId: cp.objectId, narrative: { schema: 'olio.narrative', id: narrative.id, objectId: narrative.objectId } }
        });
        expect(linkResp.ok(), 'PATCH charPerson.narrative: HTTP ' + linkResp.status()).toBe(true);

        // 4. The first page's scene: hand-written landscape prompt (executeChapBookPage requires one; no LLM).
        const pages = await (await request.get(PB + '/pages')).json();
        expect(Array.isArray(pages) && pages.length > 0, 'seeded ChapBook has pages').toBe(true);
        const scene = await search(request, 'olio.pb.scene',
            [{ name: 'objectId', comparator: 'equals', value: pages[0].objectId }], ['id', 'objectId', 'sceneIndex', 'sceneNode', 'sdPrompt']);
        expect(scene && scene.id, 'scene record: ' + JSON.stringify(scene)).toBeTruthy();
        fx.scene = { id: scene.id, objectId: scene.objectId };
        // The search projection drops a default-valued (0) sceneIndex; /pages always carries it.
        fx.sceneIndex = (scene.sceneIndex != null ? scene.sceneIndex : pages[0].sceneIndex);
        expect(typeof fx.sceneIndex, 'sceneIndex of the first page: ' + JSON.stringify(pages[0])).toBe('number');
        const promptResp = await request.patch(REST + '/model', {
            data: { schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId, sdPrompt: 'a quiet misty lake at dawn, pine forest on the far shore, a single rowing boat, painterly, soft light, wide natural vista' }
        });
        expect(promptResp.ok(), 'PATCH scene.sdPrompt: HTTP ' + promptResp.status()).toBe(true);

        // 5. P (portrait, character scope) → L (landscape, scene scope, the page's sceneNode).
        const wfv = await (await request.get(PB + '/workflow')).json();
        expect(wfv && wfv.workflowObjectId, 'workflow view').toBeTruthy();
        const wf = await search(request, 'olio.pb.workflow',
            [{ name: 'objectId', comparator: 'equals', value: wfv.workflowObjectId }], ['id', 'objectId', 'groupId', 'groupPath']);
        expect(wf && wf.id && wf.groupId, 'workflow record').toBeTruthy();
        const wfRef = { schema: 'olio.pb.workflow', id: wf.id, objectId: wf.objectId };

        fx.handleP = 'portrait@' + shortRef(cp.objectId);
        const pResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.node', name: fx.handleP, groupId: wf.groupId, groupPath: wf.groupPath, workflow: wfRef,
                handle: fx.handleP, nodeType: 'portrait', nodeStatus: 'pending', ordinal: 0, scope: 'character', scopeRef: cp.objectId }
        });
        expect(pResp.ok(), 'create node P: HTTP ' + pResp.status()).toBe(true);
        const P = await pResp.json();
        fx.nodeP = { id: P.id, objectId: P.objectId };

        fx.handleL = 'landscape@' + shortRef(scene.objectId);
        const lResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.node', name: fx.handleL, groupId: wf.groupId, groupPath: wf.groupPath, workflow: wfRef,
                handle: fx.handleL, nodeType: 'landscape', nodeStatus: 'pending', ordinal: fx.sceneIndex, scope: 'scene', scopeRef: scene.objectId, sceneIndex: fx.sceneIndex }
        });
        expect(lResp.ok(), 'create node L: HTTP ' + lResp.status()).toBe(true);
        const L = await lResp.json();
        fx.nodeL = { id: L.id, objectId: L.objectId };

        const bindResp = await request.post(PB + '/node/' + L.objectId + '/bind', { data: { role: 'portrait', sourceNodeObjectId: P.objectId } });
        const bindText = await bindResp.text();
        expect(bindResp.status(), 'bind P→L: HTTP ' + bindResp.status() + ' ' + bindText.slice(0, 300)).toBe(200);
        fx.bindingObjectId = JSON.parse(bindText).bindingObjectId;

        const sceneNodeResp = await request.patch(REST + '/model', {
            data: { schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId, sceneNode: { schema: 'olio.pb.node', id: L.id, objectId: L.objectId } }
        });
        expect(sceneNodeResp.ok(), 'PATCH scene.sceneNode: HTTP ' + sceneNodeResp.status()).toBe(true);

        const pagesAfter = await (await request.get(PB + '/pages')).json();
        const pg = pagesAfter.find(p => p.objectId === scene.objectId);
        expect(pg && pg.sceneNodeObjectId, 'page carries sceneNodeObjectId: ' + JSON.stringify(pg)).toBe(L.objectId);
        expect(pg.artifactObjectId == null && pg.dataObjectId == null, 'page has no image before the render: ' + JSON.stringify(pg)).toBe(true);

        const chars = await (await request.get(PB + '/characters')).json();
        expect(Array.isArray(chars) && chars.some(c => c.objectId === cp.objectId), '/characters lists the fixture character: ' + JSON.stringify(chars).slice(0, 300)).toBe(true);

        console.log('[p110-seed] ' + JSON.stringify({ book: bookObjectId, slug, orgId, ...fx }));
        await request.get(REST + '/logout');
    });

    test.afterAll(async ({ request }) => {
        if (!SD_ENABLED || !bookObjectId) return;
        console.log('[p110-cleanup] left in place (run rows + artifacts reference them): ' + JSON.stringify({ book: bookObjectId, slug, ...fx, c1 }));
    });

    test('[SD] criterion 1: a scene render started in the reader keeps running after the reader is left, and the scene shows its image on return', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD renders');
        expect(bookObjectId, 'ChapBook seeded in beforeAll').toBeTruthy();
        const PB = REST + '/olio/picture-book/' + bookObjectId;

        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await expect(page.locator('[data-pb2-cover-count]')).toHaveText(/^0 rendered of \d+ scene/);
        await openFirstPage(page);

        // Before: no image, no chip (node PENDING), the button says "Render".
        await expect(page.getByTestId('pb2-scene-image')).toHaveCount(0);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveCount(0);
        const renderBtn = page.getByTestId('pb2-scene-render');
        await expect(renderBtn).toBeVisible();
        await expect(renderBtn).toHaveText(/Render$/);
        await expect(renderBtn).toBeEnabled();

        // 1. Click Render; the 202 body is the job the reader watches.
        const submittedAt = Date.now();
        const respPromise = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/scene/' + fx.scene.objectId + '/render'), { timeout: 60000 });
        await renderBtn.click();
        const submitResp = await respPromise;
        const submitText = await submitResp.text();
        console.log('[p110-c1-submit] HTTP ' + submitResp.status() + ' ' + submitText.slice(0, 600));
        expect(submitResp.status(), 'POST /scene/{id}/render: ' + submitText.slice(0, 400)).toBe(202);
        const dto = JSON.parse(submitText);
        expect(dto.jobId, 'jobId').toBeTruthy();
        expect(dto.runObjectId, 'runObjectId').toBeTruthy();
        expect(dto.kind).toBe('pb.render');
        expect(dto.plannedNodeCount, 'P (upstream) + L planned').toBe(2);
        expect(dto.planned).toEqual([fx.handleP, fx.handleL]);
        c1.jobId = dto.jobId;
        c1.runObjectId = dto.runObjectId;

        // The reader is watching: progress panel non-terminal, both stages listed, Render disabled.
        const progress = page.getByTestId('pb2-run-progress');
        await expect(progress).toBeVisible();
        await expect(progress).toHaveAttribute('data-run-terminal', '0');
        await expect(page.getByTestId('pb2-run-stage')).toHaveCount(2);
        await expect(renderBtn).toBeDisabled();
        await expect(page.getByTestId('pb2-run-cancel')).toBeVisible();

        // 2. Leave the reader (header back arrow → the book list). The progress panel is gone.
        await page.locator('button[title="Back to book list"]').click();
        await expect(page).toHaveURL(/#!\/picture-book$/);
        await expect(progress).toHaveCount(0);
        const leftAt = Date.now();
        console.log('[p110-c1] left the reader ' + (leftAt - submittedAt) + 'ms after submit');

        // 3. Outside the page: the job is still there and still running; watch it to terminal.
        await login(request);
        const first = await request.get(REST + '/job/' + dto.jobId);
        expect(first.status(), 'GET /rest/job/{id} after leaving: HTTP ' + first.status()).toBe(200);
        const firstJob = await first.json();
        console.log('[p110-c1-job] first poll after leaving: ' + JSON.stringify({ status: firstJob.status, phase: firstJob.phase, current: firstJob.current, total: firstJob.total, terminal: firstJob.terminal }));
        expect(firstJob.terminal, 'the run was still in flight when the reader was left (two SD renders cannot have finished in ' + (Date.now() - submittedAt) + 'ms): ' + JSON.stringify(firstJob)).toBe(false);

        let job = firstJob;
        let lastPhase = null;
        const deadline = Date.now() + 600000;
        while (Date.now() < deadline) {
            const r = await request.get(REST + '/job/' + dto.jobId);
            expect(r.ok(), 'GET /job/{id}: HTTP ' + r.status()).toBe(true);
            job = await r.json();
            const phase = job.status + '/' + job.phase + '/' + job.current + '/' + job.total;
            if (phase !== lastPhase) { console.log('[p110-c1-job] +' + (Date.now() - submittedAt) + 'ms ' + phase); lastPhase = phase; }
            if (job.terminal) break;
            await sleep(3000);
        }
        const terminalAt = Date.now();
        console.log('[p110-c1-job] terminal after ' + (terminalAt - submittedAt) + 'ms: ' + JSON.stringify({ status: job.status, cancelled: job.cancelled, error: job.error, result: job.result }));
        expect(job.terminal, 'job reached a terminal state while the reader was closed: ' + JSON.stringify(job)).toBe(true);
        expect(job.status, 'job status: ' + JSON.stringify(job)).toBe('completed');
        expect(job.cancelled).toBe(false);
        expect(upper(job.result && job.result.runStatus), 'result.runStatus').toBe('COMPLETED');
        expect(job.result.executedNodeCount).toBe(2);
        expect(job.result.failedNodeCount).toBe(0);
        expect(typeof job.result.durationMs).toBe('number');
        const oP = (job.result.nodes || []).find(n => n.nodeObjectId === fx.nodeP.objectId);
        const oL = (job.result.nodes || []).find(n => n.nodeObjectId === fx.nodeL.objectId);
        expect(oP && oL, 'outcomes for P and L: ' + JSON.stringify(job.result.nodes)).toBeTruthy();
        expect(upper(oP.status), 'portrait outcome').toBe('DONE');
        // executeChapBookPage completes the LANDSCAPE node DONE_UNVERIFIED (no composite/verify stage).
        expect(upper(oL.status), 'landscape outcome').toBe('DONE_UNVERIFIED');
        expect(oP.artifactObjectId, 'P artifact').toBeTruthy();
        expect(oL.artifactObjectId, 'L artifact').toBeTruthy();
        c1.outcomes = { P: oP, L: oL };

        // 4. Back to the reader: the page shows the landscape, the chip says Review (auto-approve off).
        await openReader(page, bookObjectId);
        await expect(page.locator('[data-pb2-cover-count]')).toHaveText(/^1 rendered of \d+ scene/);
        await openFirstPage(page);
        const img = page.getByTestId('pb2-scene-image');
        await expect(img).toBeVisible();
        await expect.poll(async () => img.evaluate(el => el.complete && el.naturalWidth > 0), { timeout: 60000, message: 'scene image decoded in the browser' }).toBe(true);
        const natural = await img.evaluate(el => [el.naturalWidth, el.naturalHeight]);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');
        await expect(page.getByTestId('pb2-scene-chip')).toHaveText('Review');
        await expect(page.getByTestId('pb2-stale-badge')).toHaveCount(0);
        await expect(page.getByTestId('pb2-scene-render')).toHaveText(/Re-render$/);
        await expect(page.getByTestId('pb2-scene-render')).toBeEnabled();
        await expect(page.getByTestId('pb2-run-progress')).toHaveCount(0);
        await expect(page.getByTestId('pb2-resume-banner')).toHaveCount(0);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/c1-reader-scene.png', fullPage: true });
        console.log('[p110-c1] reader shows the scene image ' + natural[0] + 'x' + natural[1] + ' -> ' + OUT_DIR + '/c1-reader-scene.png');

        // 5. Durable state behind what the reader showed.
        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        console.log('[p110-c1-page] ' + JSON.stringify(pg));
        expect(pg.artifactObjectId, 'page.artifactObjectId').toBe(oL.artifactObjectId);
        expect(pg.dataObjectId && pg.imageGroupPath && pg.imageName, 'page carries the image reference').toBeTruthy();
        expect(pg.imageStale, 'page.imageStale').toBe(false);
        const run = await (await request.get(PB + '/run/' + dto.runObjectId)).json();
        expect(upper(run.runStatus)).toBe('COMPLETED');
        expect(run.jobId == null, 'run no longer holds a job once terminal (nothing to re-attach): ' + run.jobId).toBe(true);
        for (const n of [fx.nodeP, fx.nodeL]) {
            const row = await search(request, 'olio.pb.node', [{ name: 'objectId', comparator: 'equals', value: n.objectId }], ['id', 'objectId', 'handle', 'nodeStatus', 'inputHash']);
            console.log('[p110-c1-node] ' + JSON.stringify(row));
            expect(['DONE', 'DONE_UNVERIFIED']).toContain(upper(row.nodeStatus));
            expect(row.inputHash, row.handle + ' inputHash stamped (stale-scan eligibility for criterion 3)').toBeTruthy();
        }

        // 6. The pictures themselves.
        const land = await savePng(request, PB, oL.artifactObjectId, 'c1-landscape.png');
        expect(upper(land.art.role)).toBe('LANDSCAPE');
        const port = await savePng(request, PB, oP.artifactObjectId, 'c1-portrait.png');
        expect(upper(port.art.role)).toBe('PORTRAIT');
        console.log('[p110-c1-timing] submit->left ' + (leftAt - submittedAt) + 'ms; submit->terminal ' + (terminalAt - submittedAt)
            + 'ms; job.result.durationMs=' + job.result.durationMs + ' (logged, never asserted)');
        await request.get(REST + '/logout');
    });

    test('[SD] criterion 1b: Re-render on a current scene is forced (?force=true re-rolls the node); on a pinned scene the pin wins and only "Unpin and re-render" unpins', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the render from criterion 1');
        expect(c1.outcomes && c1.outcomes.L && c1.outcomes.L.artifactObjectId, 'criterion 1 rendered P and L').toBeTruthy();
        const PB = REST + '/olio/picture-book/' + bookObjectId;
        const isRender = r => r.request().method() === 'POST' && r.url().includes('/scene/' + fx.scene.objectId + '/render');
        const pinCalls = [];
        page.on('request', req => {
            if (req.method() === 'POST' && /\/node\/[^/]+\/pin$/.test(req.url())) pinCalls.push(req.url());
        });
        const nodeRow = async (n) => search(request, 'olio.pb.node', [{ name: 'objectId', comparator: 'equals', value: n.objectId }], ['id', 'objectId', 'handle', 'nodeStatus', 'inputHash', 'pinned']);

        // Precondition from criterion 1: L is current (DONE_UNVERIFIED with an inputHash) and not pinned, and
        // the same route WITHOUT force refuses it — nothing is stale, so there is nothing to render.
        await login(request);
        const lBefore = await nodeRow(fx.nodeL);
        console.log('[p110-c1b-before] ' + JSON.stringify(lBefore));
        expect(upper(lBefore.nodeStatus), 'L current after criterion 1').toBe('DONE_UNVERIFIED');
        expect(lBefore.inputHash, 'L inputHash').toBeTruthy();
        expect(!!lBefore.pinned, 'L not pinned').toBe(false);
        const unforced = await request.post(PB + '/scene/' + fx.scene.objectId + '/render');
        const unforcedText = await unforced.text();
        console.log('[p110-c1b-unforced] HTTP ' + unforced.status() + ' ' + unforcedText.slice(0, 300));
        expect(unforced.status(), 'un-forced render of a current scene is refused: ' + unforcedText).toBe(409);
        const unforcedMsg = JSON.parse(unforcedText).error;
        // P (upstream, current) and L (the terminal, current) are both reused; nothing is pinned.
        expect(unforcedMsg).toMatch(/^Nothing to render: every node of this scene is current \(2 reused, 0 pinned\)$/);
        await request.get(REST + '/logout');

        // 1. The reader: the scene shows criterion 1's image, chip Review, button "Re-render".
        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        const renderBtn = page.getByTestId('pb2-scene-render');
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');
        await expect(page.getByTestId('pb2-scene-image')).toBeVisible();
        await expect(renderBtn).toHaveText(/Re-render$/);
        await expect(renderBtn).toBeEnabled();

        // 2. Click Re-render: the request carries ?force=true and the planner re-executes L only (P reused).
        const t0 = Date.now();
        const forcedPromise = page.waitForResponse(isRender, { timeout: 60000 });
        await renderBtn.click();
        const forcedResp = await forcedPromise;
        const forcedText = await forcedResp.text();
        console.log('[p110-c1b-forced] ' + forcedResp.url() + ' HTTP ' + forcedResp.status() + ' ' + forcedText.slice(0, 600));
        expect(new URL(forcedResp.url()).searchParams.get('force'), 'the reader\'s per-scene render passes force').toBe('true');
        expect(forcedResp.request().postData(), 'force travels as the query flag, never a body').toBeNull();
        expect(forcedResp.status(), 'forced render of a current scene is accepted: ' + forcedText.slice(0, 400)).toBe(202);
        const dto = JSON.parse(forcedText);
        expect(dto.sceneObjectId).toBe(fx.scene.objectId);
        expect(dto.sceneIndex).toBe(fx.sceneIndex);
        expect(dto.jobId, 'jobId').toBeTruthy();
        expect(dto.runObjectId, 'runObjectId').toBeTruthy();
        expect(dto.runObjectId, 'a new run, not criterion 1\'s').not.toBe(c1.runObjectId);
        expect(dto.kind).toBe('pb.render');
        expect(dto.planned, 'only the forced terminal is planned').toEqual([fx.handleL]);
        expect(dto.plannedNodeCount).toBe(1);
        expect(dto.reusedNodeCount, 'the current upstream portrait is reused').toBe(1);
        expect(dto.pinnedNodeCount).toBe(0);

        const progress = page.getByTestId('pb2-run-progress');
        await expect(progress).toBeVisible();
        await expect(progress).toHaveAttribute('data-run-terminal', '0');
        await expect(page.getByTestId('pb2-run-stage')).toHaveCount(1);
        await expect(page.getByTestId('pb2-run-stage')).toHaveText(new RegExp(fx.handleL));
        await expect(renderBtn).toBeDisabled();

        // 3. Watched to terminal in the reader; the page refreshes with the new image.
        await expect(progress).toHaveAttribute('data-run-terminal', '1', { timeout: 600000 });
        const forcedMs = Date.now() - t0;
        console.log('[p110-c1b-forced] terminal in the reader after ' + forcedMs + 'ms: ' + await page.getByTestId('pb2-run-counter').textContent());
        await expect(page.getByTestId('pb2-run-stage')).toHaveAttribute('data-stage-state', 'done');
        await expect(page.getByTestId('pb2-run-cancel')).toHaveCount(0);
        await expect(page.getByTestId('pb2-run-dismiss')).toBeVisible();
        const img = page.getByTestId('pb2-scene-image');
        await expect(img).toBeVisible();
        await expect.poll(async () => img.evaluate(el => el.complete && el.naturalWidth > 0), { timeout: 60000, message: 'forced scene image decoded' }).toBe(true);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');
        await expect(page.getByTestId('pb2-stale-badge')).toHaveCount(0);
        await expect(renderBtn).toHaveText(/Re-render$/);
        await expect(renderBtn).toBeEnabled();
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/c1b-reader-forced.png', fullPage: true });

        await login(request);
        const job1 = await (await request.get(REST + '/job/' + dto.jobId)).json();
        console.log('[p110-c1b-job] ' + JSON.stringify({ status: job1.status, terminal: job1.terminal, result: job1.result }));
        expect(job1.terminal).toBe(true);
        expect(job1.status).toBe('completed');
        expect(upper(job1.result && job1.result.runStatus)).toBe('COMPLETED');
        expect(job1.result.executedNodeCount, 'one node executed').toBe(1);
        expect(job1.result.failedNodeCount).toBe(0);
        const o2 = (job1.result.nodes || []).find(n => n.nodeObjectId === fx.nodeL.objectId);
        expect(o2 && o2.artifactObjectId, 'L outcome: ' + JSON.stringify(job1.result.nodes)).toBeTruthy();
        expect(upper(o2.status)).toBe('DONE_UNVERIFIED');
        expect(o2.artifactObjectId, 'a new artifact revision, not criterion 1\'s').not.toBe(c1.outcomes.L.artifactObjectId);
        const pg2 = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        console.log('[p110-c1b-page] ' + JSON.stringify(pg2));
        expect(pg2.artifactObjectId, 'the page now shows the forced render').toBe(o2.artifactObjectId);
        expect(pg2.imageStale).toBe(false);
        expect(!!pg2.pinned).toBe(false);
        const lAfter = await nodeRow(fx.nodeL);
        console.log('[p110-c1b-node] ' + JSON.stringify(lAfter));
        expect(upper(lAfter.nodeStatus)).toBe('DONE_UNVERIFIED');
        expect(lAfter.inputHash, 'inputHash re-stamped').toBeTruthy();
        const pAfter = await nodeRow(fx.nodeP);
        expect(upper(pAfter.nodeStatus), 'P untouched by the forced scene render').toBe('DONE');
        const forced = await savePng(request, PB, o2.artifactObjectId, 'c1b-landscape-forced.png');
        expect(upper(forced.art.role)).toBe('LANDSCAPE');
        const prior = await (await request.get(PB + '/artifact/' + c1.outcomes.L.artifactObjectId)).json();
        expect(prior.objectId, 'criterion 1\'s artifact is kept in the history').toBe(c1.outcomes.L.artifactObjectId);
        // The run outcome names the artifact only; revision + supersedes come from the artifact route.
        console.log('[p110-c1b-revision] prior=' + prior.revision + ' selected=' + prior.selected + ' reviewStatus=' + prior.reviewStatus
            + ' -> forced=' + forced.art.revision + ' selected=' + forced.art.selected + ' supersedes=' + forced.art.supersedesObjectId);
        expect(forced.art.revision, 'the forced render is the next revision after criterion 1\'s ' + prior.revision).toBe(prior.revision + 1);
        expect(forced.art.supersedesObjectId, 'the forced render supersedes criterion 1\'s artifact').toBe(prior.objectId);
        expect(forced.art.selected, 'the forced render is selected (the superseded one was GENERATED, not APPROVED)').toBe(true);
        expect(prior.selected, 'criterion 1\'s artifact is kept but no longer selected').toBe(false);

        // 4. Pin L over REST. The reader's Re-render is now refused even though it is forced: the pin wins.
        const pinResp = await request.post(PB + '/node/' + fx.nodeL.objectId + '/pin', { data: { pinned: true } });
        const pinText = await pinResp.text();
        expect(pinResp.status(), 'pin L: ' + pinText).toBe(200);
        expect(JSON.parse(pinText).pinned).toBe(true);
        await request.get(REST + '/logout');

        await page.reload({ timeout: 30000 });
        await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
        await openFirstPage(page);
        await expect(page.getByTestId('pb2-scene-image')).toBeVisible();
        await expect(renderBtn).toBeEnabled();

        //    4a. Decline: the 409 is shown verbatim; Cancel sends no pin call and L stays pinned.
        const refused1 = page.waitForResponse(isRender, { timeout: 60000 });
        await renderBtn.click();
        const r1 = await refused1;
        const r1Text = await r1.text();
        console.log('[p110-c1b-pinned] ' + r1.url() + ' HTTP ' + r1.status() + ' ' + r1Text.slice(0, 300));
        expect(new URL(r1.url()).searchParams.get('force')).toBe('true');
        expect(r1.status(), 'forced render of a pinned scene is refused: ' + r1Text).toBe(409);
        const pinnedMsg = JSON.parse(r1Text).error;
        expect(pinnedMsg).toMatch(/^Nothing to render: every node of this scene is current \(1 reused, 1 pinned\)$/);
        const dialog = page.locator('.am7-dialog');
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog.locator('.am7-dialog-title')).toHaveText('Scene is pinned');
        await expect(dialog.locator('.am7-dialog-body')).toContainText(pinnedMsg);
        await expect(dialog.locator('.am7-dialog-body')).toContainText('Unpin this scene\'s node and re-render it?');
        const confirmBtn = dialog.locator('.am7-dialog-footer button.am7-dialog-btn-primary');
        await expect(confirmBtn).toHaveText(/Unpin and re-render$/);
        await page.screenshot({ path: OUT_DIR + '/c1b-reader-pinned-confirm.png', fullPage: true });
        await dialog.locator('.am7-dialog-footer button.am7-dialog-btn', { hasText: 'Cancel' }).click();
        await expect(page.locator('.am7-dialog')).toHaveCount(0);
        await expect(renderBtn).toBeEnabled({ timeout: 10000 });
        expect(pinCalls, 'declining must not call the pin route').toEqual([]);
        await login(request);
        const lDeclined = await nodeRow(fx.nodeL);
        expect(lDeclined.pinned, 'L still pinned after decline: ' + JSON.stringify(lDeclined)).toBe(true);
        const pgDeclined = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        expect(pgDeclined.artifactObjectId, 'declined: the pinned image is untouched').toBe(o2.artifactObjectId);
        await request.get(REST + '/logout');

        //    4b. Accept: the same 409, then "Unpin and re-render" → POST pin {pinned:false} → the forced render runs.
        const refused2 = page.waitForResponse(isRender, { timeout: 60000 });
        await renderBtn.click();
        const r2 = await refused2;
        expect(r2.status(), 'second click is refused the same way').toBe(409);
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog.locator('.am7-dialog-title')).toHaveText('Scene is pinned');
        const t1 = Date.now();
        const unpinDone = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/node/' + fx.nodeL.objectId + '/pin'), { timeout: 30000 });
        const resubmit = page.waitForResponse(isRender, { timeout: 60000 });
        await confirmBtn.click();
        await expect(page.locator('.am7-dialog')).toHaveCount(0);
        const unpinResp = await unpinDone;
        const unpinText = await unpinResp.text();
        console.log('[p110-c1b-unpin] HTTP ' + unpinResp.status() + ' ' + unpinText.slice(0, 300) + ' (' + JSON.stringify(unpinResp.request().postDataJSON()) + ')');
        expect(unpinResp.status(), 'unpin: ' + unpinText).toBe(200);
        expect(unpinResp.request().postDataJSON(), 'unpin body').toEqual({ pinned: false });
        expect(JSON.parse(unpinText).pinned).toBe(false);
        expect(pinCalls.length, 'exactly one pin call, after the confirm').toBe(1);
        const r3 = await resubmit;
        const r3Text = await r3.text();
        console.log('[p110-c1b-resubmit] ' + r3.url() + ' HTTP ' + r3.status() + ' ' + r3Text.slice(0, 600));
        expect(new URL(r3.url()).searchParams.get('force')).toBe('true');
        expect(r3.status(), 'render after unpin: ' + r3Text.slice(0, 400)).toBe(202);
        const dto3 = JSON.parse(r3Text);
        expect(dto3.planned).toEqual([fx.handleL]);
        expect(dto3.reusedNodeCount).toBe(1);
        expect(dto3.pinnedNodeCount).toBe(0);
        expect(dto3.runObjectId).not.toBe(dto.runObjectId);

        await expect(progress).toBeVisible();
        await expect(progress).toHaveAttribute('data-run-terminal', '1', { timeout: 600000 });
        const unpinnedMs = Date.now() - t1;
        console.log('[p110-c1b-resubmit] terminal in the reader after ' + unpinnedMs + 'ms: ' + await page.getByTestId('pb2-run-counter').textContent());
        await expect(page.getByTestId('pb2-run-stage')).toHaveAttribute('data-stage-state', 'done');
        await expect(img).toBeVisible();
        await expect.poll(async () => img.evaluate(el => el.complete && el.naturalWidth > 0), { timeout: 60000, message: 'unpinned scene image decoded' }).toBe(true);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');
        await expect(page.getByTestId('pb2-stale-badge')).toHaveCount(0);
        await expect(renderBtn).toBeEnabled();
        await page.screenshot({ path: OUT_DIR + '/c1b-reader-unpinned.png', fullPage: true });

        // 5. Durable state: unpinned, a third revision selected, the pinned revision kept, P untouched.
        await login(request);
        const job3 = await (await request.get(REST + '/job/' + dto3.jobId)).json();
        expect(job3.terminal && job3.status === 'completed', JSON.stringify(job3)).toBe(true);
        expect(job3.result.executedNodeCount).toBe(1);
        const o3 = (job3.result.nodes || []).find(n => n.nodeObjectId === fx.nodeL.objectId);
        expect(o3 && o3.artifactObjectId, 'L outcome: ' + JSON.stringify(job3.result.nodes)).toBeTruthy();
        expect(upper(o3.status)).toBe('DONE_UNVERIFIED');
        expect(o3.artifactObjectId, 'a third artifact, not the pinned one').not.toBe(o2.artifactObjectId);
        const pg3 = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        console.log('[p110-c1b-page] ' + JSON.stringify(pg3));
        expect(pg3.artifactObjectId).toBe(o3.artifactObjectId);
        expect(pg3.imageStale).toBe(false);
        expect(!!pg3.pinned, 'page unpinned').toBe(false);
        const lFinal = await nodeRow(fx.nodeL);
        console.log('[p110-c1b-node] ' + JSON.stringify(lFinal));
        expect(upper(lFinal.nodeStatus)).toBe('DONE_UNVERIFIED');
        expect(!!lFinal.pinned, 'node unpinned').toBe(false);
        expect(lFinal.inputHash, 'inputHash stamped (criterion 3 stale-scan eligibility)').toBeTruthy();
        expect(upper((await nodeRow(fx.nodeP)).nodeStatus), 'P untouched').toBe('DONE');
        const kept = await (await request.get(PB + '/artifact/' + o2.artifactObjectId)).json();
        expect(kept.objectId, 'the previously pinned revision stays in the history').toBe(o2.artifactObjectId);
        const unpinned = await savePng(request, PB, o3.artifactObjectId, 'c1b-landscape-unpinned.png');
        expect(upper(unpinned.art.role)).toBe('LANDSCAPE');
        console.log('[p110-c1b-revision] kept=' + kept.revision + ' selected=' + kept.selected
            + ' -> unpinned=' + unpinned.art.revision + ' selected=' + unpinned.art.selected + ' supersedes=' + unpinned.art.supersedesObjectId);
        expect(unpinned.art.revision, 'the post-unpin render is the next revision after ' + kept.revision).toBe(kept.revision + 1);
        expect(unpinned.art.supersedesObjectId, 'it supersedes the formerly pinned revision').toBe(o2.artifactObjectId);
        expect(unpinned.art.selected).toBe(true);
        expect(kept.selected, 'the formerly pinned revision is kept but no longer selected').toBe(false);
        console.log('[p110-c1b-timing] forced click->terminal ' + forcedMs + 'ms; confirm->terminal ' + unpinnedMs
            + 'ms; durationMs=' + job1.result.durationMs + '/' + job3.result.durationMs + ' (logged, never asserted)');
        // Criterion 3 checks the page against the latest L artifact.
        c1.outcomes.L = o3;
        await request.get(REST + '/logout');
    });

    test('[SD] criterion 3: editing the bound character shows "Affected scenes: 1" + "1 stale" in the character manager, and the reader chip turns Stale', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the render from criterion 1');
        expect(c1.outcomes && c1.outcomes.L && c1.outcomes.L.artifactObjectId, 'criterion 1 rendered P and L').toBeTruthy();
        const PB = REST + '/olio/picture-book/' + bookObjectId;

        // Precondition from criterion 1: nothing is stale yet, and the character's one bound node reaches the page.
        await login(request);
        const before = await (await request.get(PB + '/affected-by/olio.charPerson/' + fx.charPerson)).json();
        console.log('[p110-c3-before] ' + JSON.stringify(before));
        expect(before.boundNodes, 'P bound to the character by the portrait render').toBe(1);
        expect(before.drifted).toBe(false);
        expect(before.affectedSceneIndexes).toEqual([fx.sceneIndex]);
        // The DTO omits empty lists (staleSceneIndexes / driftedHandles) rather than sending [].
        expect(before.staleSceneIndexes || []).toEqual([]);

        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');

        // 1. Reader header → standalone character manager → the fixture character.
        await page.locator('[data-pb2-characters="' + bookObjectId + '"]').click();
        await expect(page.locator('.am7-dialog-title')).toHaveText('Manage Characters');
        const item = page.locator('[data-char-item="' + fx.charPerson + '"]');
        await expect(item).toBeVisible({ timeout: 30000 });
        await expect(item).toContainText(fx.charName);
        await item.click();
        await expect(page.locator('[data-char-name]')).toHaveText(fx.charName, { timeout: 30000 });

        const count = page.getByTestId('pbchar-affected-count');
        await expect(count).toBeVisible({ timeout: 30000 });
        await expect(count).toHaveAttribute('data-count', '1');
        await expect(count).toHaveText('Affected scenes: 1 (' + fx.sceneIndex + ')');
        await expect(page.getByTestId('pbchar-affected-drifted')).toHaveCount(0);
        await expect(page.getByTestId('pbchar-affected-error')).toHaveCount(0);
        await expect(page.getByTestId('pbchar-affected-regenerate')).toBeEnabled();

        // 2. Edit the name (a watched field) and save.
        const newName = fx.charName + '-edited';
        await page.locator('[data-char-edit]').click();
        await expect(page.locator('[data-char-identity-editor]')).toBeVisible();
        const nameInput = page.locator('[data-char-name-input]');
        await expect(nameInput).toHaveValue(fx.charName);
        await nameInput.fill(newName);
        const affectedPromise = page.waitForResponse(r => r.request().method() === 'GET' && r.url().includes('/affected-by/olio.charPerson/' + fx.charPerson), { timeout: 60000 });
        await page.locator('[data-char-save]').click();
        const affectedResp = await affectedPromise;
        const affectedText = await affectedResp.text();
        console.log('[p110-c3-after] HTTP ' + affectedResp.status() + ' ' + affectedText.slice(0, 600));
        expect(affectedResp.status(), 'post-save affected-by: ' + affectedText.slice(0, 300)).toBe(200);
        const after = JSON.parse(affectedText);
        expect(after.boundNodes).toBe(1);
        expect(after.drifted, 'the name edit drifted the bound portrait node').toBe(true);
        expect(after.driftedHandles).toContain(fx.handleP);
        expect(after.affectedSceneIndexes).toEqual([fx.sceneIndex]);
        expect(after.staleSceneIndexes, 'STALE propagated P → L → the page').toEqual([fx.sceneIndex]);
        expect(after.markPersisted, 'the scan persisted STALE as e2etest_shared').toBe(true);

        // 3. The panel: "Affected scenes: 1 (idx)" + "1 stale" + Review / Regenerate, no caveat.
        await expect(page.locator('[data-char-name]')).toHaveText(newName, { timeout: 30000 });
        await expect(page.locator('[data-char-identity-editor]')).toHaveCount(0);
        await expect(count).toHaveAttribute('data-count', '1');
        await expect(count).toHaveText('Affected scenes: 1 (' + fx.sceneIndex + ')');
        const drifted = page.getByTestId('pbchar-affected-drifted');
        await expect(drifted).toBeVisible();
        await expect(drifted).toHaveText('1 stale');
        await expect(drifted).toHaveAttribute('title', new RegExp('^Nodes: .*' + fx.handleP));
        await expect(page.getByTestId('pbchar-affected-review')).toBeVisible();
        await expect(page.getByTestId('pbchar-affected-review')).toBeEnabled();
        await expect(page.getByTestId('pbchar-affected-regenerate')).toBeVisible();
        await expect(page.getByTestId('pbchar-affected-regenerate')).toBeEnabled();
        await expect(page.getByTestId('pbchar-affected-caveat')).toHaveCount(0);
        await expect(item).toContainText(newName);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/c3-character-manager.png', fullPage: true });

        // 4. Close the manager; reload the reader: the scene chip is Stale, the badge overlays the image.
        await page.locator('button[aria-label="Close dialog"]').click();
        await expect(page.locator('.am7-dialog-title')).toHaveCount(0);
        await page.reload({ timeout: 30000 });
        await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
        await openFirstPage(page);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'stale');
        await expect(page.getByTestId('pb2-scene-chip')).toHaveText('Stale');
        await expect(page.getByTestId('pb2-stale-badge')).toHaveText(/Image is stale/);
        await expect(page.getByTestId('pb2-scene-image')).toBeVisible();
        await expect(page.getByTestId('pb2-scene-render')).toHaveText(/Re-render$/);
        await expect(page.getByTestId('pb2-scene-render')).toHaveAttribute('title', 'Re-render this scene');
        await page.screenshot({ path: OUT_DIR + '/c3-reader-stale.png', fullPage: true });

        // 5. Durable state behind the chip.
        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        console.log('[p110-c3-page] ' + JSON.stringify(pg));
        expect(upper(pg.nodeStatus), 'page.nodeStatus').toBe('STALE');
        expect(pg.artifactObjectId, 'the stale page keeps its image').toBe(c1.outcomes.L.artifactObjectId);
        for (const n of [fx.nodeP, fx.nodeL]) {
            const row = await search(request, 'olio.pb.node', [{ name: 'objectId', comparator: 'equals', value: n.objectId }], ['id', 'objectId', 'handle', 'nodeStatus', 'inputHash']);
            console.log('[p110-c3-node] ' + JSON.stringify(row));
            expect(upper(row.nodeStatus), row.handle + ' stored STALE').toBe('STALE');
        }
        const cpRow = await search(request, 'olio.charPerson', [{ name: 'objectId', comparator: 'equals', value: fx.charPerson }], ['id', 'objectId', 'name']);
        expect(cpRow && cpRow.name, 'charPerson name persisted').toBe(newName);
        await request.get(REST + '/logout');
    });
});

/**
 * Criterion 4 (optional, §30 row 1): the ChapBook review page's per-page "Re-render this page" on a PINNED
 * page. The facade refuses the render with a 409 ("Node … is pinned. Unpin it first …"); the page shows
 * that message in a Dialog.confirm whose only affirmative is "Unpin and re-render". Declining leaves the
 * pin alone (no pin call, "Page N left pinned" toast); accepting sends POST …/node/{n}/pin {pinned:false}
 * and runs the same render once more, which puts the image on the card.
 *
 * Own fixture: a second fresh ChapBook whose first page gets a hand-written prompt and a LANDSCAPE node
 * that is pinned over REST before the browser opens the review page. One live SD render (.39).
 */
const c4 = { book: null, slug: null, scene: null, sceneIndex: null, node: null, handle: null, prompt: null };

test.describe('PictureBook V3 P1-10 — criterion 4: ChapBook per-page Re-render on a pinned page asks before unpinning', () => {
    test.describe.configure({ mode: 'serial', timeout: 900000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        if (!SD_ENABLED) return;
        shared = await ensureSharedTestUser(request);
        await login(request);

        const poemsGroup = await makePath(request, '~/Poems');
        const poemOid = await findOrCreatePoem(request, poemsGroup.organizationId, poemsGroup.id, 'p110-ux-poem-alpha', 'Falling Leaves (P110)', POEM);

        c4.slug = 'p110-cb-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug: c4.slug, title: 'P1-10 Pinned Page ChapBook', poemObjectIds: [poemOid], maxLinesPerPage: 20 }
        });
        const createText = await createResp.text();
        expect(createResp.ok(), 'ChapBook create: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        c4.book = JSON.parse(createText).objectId;
        expect(c4.book, 'seeded ChapBook objectId').toBeTruthy();
        const PB = REST + '/olio/picture-book/' + c4.book;

        const bookFull = await (await request.get(REST + '/model/olio.pb.book/' + c4.book + '/full')).json();
        expect(upper(bookFull.bookType), 'seeded book is a CHAPBOOK').toBe('CHAPBOOK');

        const pages = await (await request.get(PB + '/pages')).json();
        expect(Array.isArray(pages) && pages.length > 0, 'seeded ChapBook has pages').toBe(true);
        const scene = await search(request, 'olio.pb.scene',
            [{ name: 'objectId', comparator: 'equals', value: pages[0].objectId }], ['id', 'objectId', 'sceneIndex', 'sceneNode', 'sdPrompt']);
        expect(scene && scene.id, 'scene record: ' + JSON.stringify(scene)).toBeTruthy();
        c4.scene = { id: scene.id, objectId: scene.objectId };
        c4.sceneIndex = (scene.sceneIndex != null ? scene.sceneIndex : pages[0].sceneIndex);
        expect(typeof c4.sceneIndex, 'sceneIndex of the first page: ' + JSON.stringify(pages[0])).toBe('number');
        c4.prompt = 'a white lighthouse on a rocky headland at golden hour, long shadows, gulls over the surf, painterly, wide natural vista';
        const promptResp = await request.patch(REST + '/model', {
            data: { schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId, sdPrompt: c4.prompt }
        });
        expect(promptResp.ok(), 'PATCH scene.sdPrompt: HTTP ' + promptResp.status()).toBe(true);

        // The page's LANDSCAPE node, linked as scene.sceneNode, then pinned over REST.
        const wfv = await (await request.get(PB + '/workflow')).json();
        expect(wfv && wfv.workflowObjectId, 'workflow view').toBeTruthy();
        const wf = await search(request, 'olio.pb.workflow',
            [{ name: 'objectId', comparator: 'equals', value: wfv.workflowObjectId }], ['id', 'objectId', 'groupId', 'groupPath']);
        expect(wf && wf.id && wf.groupId, 'workflow record').toBeTruthy();
        c4.handle = 'landscape@' + shortRef(scene.objectId);
        const lResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.node', name: c4.handle, groupId: wf.groupId, groupPath: wf.groupPath,
                workflow: { schema: 'olio.pb.workflow', id: wf.id, objectId: wf.objectId },
                handle: c4.handle, nodeType: 'landscape', nodeStatus: 'pending', ordinal: c4.sceneIndex, scope: 'scene', scopeRef: scene.objectId, sceneIndex: c4.sceneIndex }
        });
        expect(lResp.ok(), 'create node L: HTTP ' + lResp.status()).toBe(true);
        const L = await lResp.json();
        c4.node = { id: L.id, objectId: L.objectId };
        const sceneNodeResp = await request.patch(REST + '/model', {
            data: { schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId, sceneNode: { schema: 'olio.pb.node', id: L.id, objectId: L.objectId } }
        });
        expect(sceneNodeResp.ok(), 'PATCH scene.sceneNode: HTTP ' + sceneNodeResp.status()).toBe(true);

        const pinResp = await request.post(PB + '/node/' + L.objectId + '/pin', { data: { pinned: true } });
        const pinText = await pinResp.text();
        expect(pinResp.status(), 'pin node: HTTP ' + pinResp.status() + ' ' + pinText.slice(0, 300)).toBe(200);
        expect(JSON.parse(pinText).pinned, 'pin response').toBe(true);

        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === scene.objectId);
        expect(pg && pg.sceneNodeObjectId, 'page carries sceneNodeObjectId: ' + JSON.stringify(pg)).toBe(L.objectId);
        expect(pg.pinned, 'page reports pinned before the browser opens it').toBe(true);
        expect(pg.artifactObjectId == null && pg.dataObjectId == null, 'page has no image before the render').toBe(true);

        console.log('[p110-c4-seed] ' + JSON.stringify(c4));
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        if (!SD_ENABLED || !c4.book) return;
        console.log('[p110-c4-cleanup] left in place (run rows + artifact reference the node): ' + JSON.stringify(c4));
    });

    test('[SD] criterion 4: "Re-render this page" on a pinned page shows the 409 in an "Unpin and re-render" confirm; declining leaves it pinned, accepting unpins and renders', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'Set PB_SD_TESTS=1 to run the live .39 render');
        const PB = REST + '/olio/picture-book/' + c4.book;
        const isGenerate = r => r.request().method() === 'POST' && r.url().includes('/chap-book/scene/' + c4.scene.objectId + '/generate');
        const pinCalls = [];
        page.on('request', req => {
            if (req.method() === 'POST' && /\/node\/[^/]+\/pin$/.test(req.url())) pinCalls.push(req.url());
        });

        // 1. The review page: the first card's Re-render button is marked pinned, the prompt is prefilled.
        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + c4.book, { timeout: 30000 });
        const btn = page.locator('[data-testid="cb-page-rerender"][data-scene-oid="' + c4.scene.objectId + '"]');
        await expect(btn).toBeVisible({ timeout: 30000 });
        await expect(btn).toHaveAttribute('data-pinned', '1');
        await expect(btn).toBeEnabled();
        await expect(page.locator('textarea.cb-scene-prompt[data-scene-oid="' + c4.scene.objectId + '"]')).toHaveValue(c4.prompt, { timeout: 15000 });
        await expect(page.locator('img.cb-scene-image[data-scene-oid="' + c4.scene.objectId + '"]')).toHaveCount(0);
        const label = 'Page ' + c4.sceneIndex;

        // 2. Decline: the server's 409 is shown verbatim; Cancel sends no pin call and the page stays pinned.
        const gen1 = page.waitForResponse(isGenerate, { timeout: 120000 });
        await btn.click();
        const r1 = await gen1;
        const r1Text = await r1.text();
        console.log('[p110-c4-decline] generate HTTP ' + r1.status() + ' ' + r1Text.slice(0, 300));
        expect(r1.status(), 'pinned page render is refused: ' + r1Text).toBe(409);
        const serverMsg = JSON.parse(r1Text).error;
        expect(serverMsg, '409 body names the pinned node').toMatch(new RegExp('^Node ' + c4.handle + ' is pinned\\. Unpin it first'));

        const dialog = page.locator('.am7-dialog');
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog.locator('.am7-dialog-title')).toHaveText(label + ' is pinned');
        await expect(dialog.locator('.am7-dialog-body')).toContainText(serverMsg);
        await expect(dialog.locator('.am7-dialog-body')).toContainText('Unpin this page\'s node and re-render it?');
        const confirmBtn = dialog.locator('.am7-dialog-footer button.am7-dialog-btn-destructive');
        await expect(confirmBtn).toHaveText(/Unpin and re-render$/);
        await page.screenshot({ path: OUT_DIR + '/c4-review-pinned-confirm.png', fullPage: true });
        await dialog.locator('.am7-dialog-footer button.am7-dialog-btn', { hasText: 'Cancel' }).click();
        await expect(page.locator('.am7-dialog')).toHaveCount(0);
        await expect(page.locator('.toast-text', { hasText: label + ' left pinned' })).toBeVisible({ timeout: 10000 });
        await expect(btn).toHaveAttribute('data-pinned', '1');
        await expect(btn).toBeEnabled({ timeout: 10000 });
        expect(pinCalls, 'declining must not call the pin route').toEqual([]);

        await login(request);
        const pgDeclined = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === c4.scene.objectId);
        expect(pgDeclined.pinned, 'page still pinned after decline: ' + JSON.stringify(pgDeclined)).toBe(true);
        expect(pgDeclined.artifactObjectId == null && pgDeclined.dataObjectId == null, 'declined page has no image').toBe(true);
        const nodeDeclined = await search(request, 'olio.pb.node', [{ name: 'objectId', comparator: 'equals', value: c4.node.objectId }], ['id', 'objectId', 'handle', 'nodeStatus', 'pinned']);
        expect(nodeDeclined.pinned, 'node still pinned after decline: ' + JSON.stringify(nodeDeclined)).toBe(true);

        // 3. Accept: the same 409, then "Unpin and re-render" → POST pin {pinned:false} → the render runs
        //    (the per-scene generate call waits for the page job) and the card shows the image.
        const gen2 = page.waitForResponse(isGenerate, { timeout: 120000 });
        await btn.click();
        const r2 = await gen2;
        expect(r2.status(), 'second click is refused the same way').toBe(409);
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog.locator('.am7-dialog-title')).toHaveText(label + ' is pinned');

        const t0 = Date.now();
        const pinDone = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/node/' + c4.node.objectId + '/pin'), { timeout: 30000 });
        const gen3 = page.waitForResponse(isGenerate, { timeout: 840000 });
        await confirmBtn.click();
        await expect(page.locator('.am7-dialog')).toHaveCount(0);
        const pinResp = await pinDone;
        const pinText = await pinResp.text();
        console.log('[p110-c4-unpin] HTTP ' + pinResp.status() + ' ' + pinText.slice(0, 300) + ' (' + JSON.stringify(pinResp.request().postDataJSON()) + ')');
        expect(pinResp.status(), 'unpin: ' + pinText).toBe(200);
        expect(pinResp.request().postDataJSON(), 'unpin body').toEqual({ pinned: false });
        expect(JSON.parse(pinText).pinned, 'unpin response').toBe(false);
        expect(pinCalls.length, 'exactly one pin call, after the confirm').toBe(1);

        const r3 = await gen3;
        const r3Text = await r3.text();
        const renderMs = Date.now() - t0;
        console.log('[p110-c4-render] generate HTTP ' + r3.status() + ' after ' + renderMs + 'ms: ' + r3Text.slice(0, 600));
        expect([200, 202].includes(r3.status()), 'render after unpin: HTTP ' + r3.status() + ' ' + r3Text).toBe(true);
        const r3Body = JSON.parse(r3Text);
        expect(r3Body.renderPath, 'v3 render path').toBe('v3');
        expect(r3Body.nodeObjectId, 'the page node').toBe(c4.node.objectId);
        if (r3.status() === 200) {
            expect(r3Body.rendered, 'rendered: ' + r3Text).toBe(true);
            expect(r3Body.imageObjectId, 'imageObjectId').toBeTruthy();
            expect(upper(r3Body.nodeStatus), 'nodeStatus').toBe('DONE_UNVERIFIED');
        }

        const img = page.locator('img.cb-scene-image[data-scene-oid="' + c4.scene.objectId + '"]');
        await expect(img).toBeVisible({ timeout: 600000 });
        await expect.poll(() => img.evaluate(el => el.complete && el.naturalWidth > 0), { timeout: 60000, message: 'card image decoded' }).toBe(true);
        await expect(page.locator('.toast-text', { hasText: 'Page re-rendered' })).toBeVisible({ timeout: 10000 });
        await expect(btn).toHaveAttribute('data-pinned', '0');
        await expect(btn).toBeEnabled({ timeout: 10000 });
        await page.screenshot({ path: OUT_DIR + '/c4-review-rendered.png', fullPage: true });
        console.log('[p110-c4-timing] confirm->generate response ' + renderMs + 'ms (logged, never asserted)');

        // 4. Durable state: unpinned, rendered, the artifact's PNG is real.
        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === c4.scene.objectId);
        console.log('[p110-c4-page] ' + JSON.stringify(pg));
        expect(pg.pinned, 'page unpinned').toBe(false);
        expect(upper(pg.nodeStatus), 'page.nodeStatus').toBe('DONE_UNVERIFIED');
        expect(pg.artifactObjectId && pg.dataObjectId, 'page has an artifact + image').toBeTruthy();
        if (r3Body.imageObjectId) expect(pg.dataObjectId, 'the card image is the page image').toBe(r3Body.imageObjectId);
        const node = await search(request, 'olio.pb.node', [{ name: 'objectId', comparator: 'equals', value: c4.node.objectId }], ['id', 'objectId', 'handle', 'nodeStatus', 'pinned', 'inputHash']);
        console.log('[p110-c4-node] ' + JSON.stringify(node));
        // The search projection drops a default-valued (false) boolean, so "not pinned" is absent-or-false.
        expect(node.pinned === true, 'node unpinned: ' + JSON.stringify(node)).toBe(false);
        expect(upper(node.nodeStatus), 'node status').toBe('DONE_UNVERIFIED');
        expect(node.inputHash, 'node carries an inputHash').toBeTruthy();
        const saved = await savePng(request, PB, pg.artifactObjectId, 'c4-landscape.png');
        expect(upper(saved.art.role), 'artifact role').toBe('LANDSCAPE');
        expect(saved.art.dataObjectId, 'artifact data is the page image').toBe(pg.dataObjectId);
        await request.get(REST + '/logout');
    });
});
