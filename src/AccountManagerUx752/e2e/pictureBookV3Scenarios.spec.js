/**
 * PictureBook V3 P7-5 — Scenarios A–G (archive/PictureBookV3Notes.md §70) on the Docker stack.
 *
 * One STORY book, one approved tavern (olio.pb.location), four characters with approved flux2 portraits
 * (Alice, Bob, Cara, Dev), and six scenes at the tavern: 1 (A: Alice+Bob), 2 (B: all four), 3/7/12 (C:
 * same tavern, never rendered - plan only), 4 (D: Alice alone). The compositions, actor rows (with
 * boxes), the RENDER_PLAN nodes (handle render_plan@<shortRef(scene)>, the handle ensureRenderPlanNode
 * finds), the portrait->scene node bindings and scene.sceneNode are hand-seeded over REST - no LLM.
 * The book style pins every task model to flux2 (`PB_FLUX2_MODEL`, default flux2Klein_9b on .39): the
 * deployment default checkpoint is SDXL, whose capabilities carry no reference support, so every
 * reference-bearing scenario would otherwise degrade to TEXT_ONLY / ACTOR_TEXT_ONLY.
 *
 *   A  two characters: both referenced in the BASE stage (DIRECT_REFERENCE), the approved tavern render
 *      rides the plan, the composition boxes are the actors' boxes, the pinned flux2 model is the one used.
 *   B  four characters: all four on the plan, two primary references in BASE, two regional actors
 *      (ACTOR_REGION, SEQUENTIAL_INPAINT); none silently dropped (no ACTOR_TEXT_ONLY).
 *   C  scenes 3, 7, 12: every plan names the same location, the same approved render, the same
 *      revisionHash as scene 1's - no LOCATION_TEXT_ONLY.
 *   D  Alice's hair changes (hairStyle, a watched field): the bound portrait node drifts, scenes 1 and 4
 *      report stale, the approved scene 1 render stays approved and pinned; the character manager's
 *      Regenerate re-renders scene 4 (portrait first) and leaves pinned scene 1 alone when the
 *      "Scene 1 is pinned" confirm is declined; approving the new portrait and re-rendering scene 4
 *      puts the new portrait revision on the plan.
 *   E  the tavern is wrong: unpin + force-render the location, approve the new revision - the three
 *      rendered scenes report stale through affected-by/olio.pb.location, scene plans carry the new
 *      render. Character regions are NOT preserved (no background-only regenerate exists): recorded,
 *      not asserted.
 *   F  variant selection - P6 TestPbVariantsLive (Objects7, re-run only; not in this file).
 *   G  Render all from the reader: leave the reader mid-run, the job keeps running, return - the
 *      progress panel re-attaches and reaches terminal; the review page lists the re-rendered nodes.
 *
 * Live SD on .39 — gated behind PB_SD_TESTS=1 and run serially. A skip is NOT a pass.
 *
 *   cd src/AccountManagerUx752
 *   PB_SD_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3Scenarios.spec.js --workers=1 --project=chromium --retries=0
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) - never admin. Timings are logged, never
 * asserted; the only time-based failure is "no job progress for 30 minutes". Every PNG is fetched from
 * the media servlet, byte-checked and written to test-results/p75-scenarios/ so it can be looked at.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/p75-scenarios';
const FLUX2 = process.env.PB_FLUX2_MODEL || 'flux2Klein_9b';
const NO_PROGRESS_MS = 30 * 60 * 1000;
const TAG = '[p75]';

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }
function shortRef(oid) { return String(oid).replace(/-/g, '').slice(0, 8); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

let shared = null;

function sharedCredential() {
    if (!shared) throw new Error('login before ensureSharedTestUser() — the beforeAll must set the shared user first');
    return { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' };
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

async function postModel(request, body, label) {
    const resp = await request.post(REST + '/model', { data: body });
    const text = await resp.text();
    expect(resp.ok(), 'POST /model ' + label + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    const rec = JSON.parse(text);
    expect(rec && rec.id && rec.objectId, label + ' identity: ' + text.slice(0, 200)).toBeTruthy();
    return rec;
}

async function patchModel(request, body, label) {
    const resp = await request.patch(REST + '/model', { data: body });
    const text = await resp.text();
    expect(resp.ok(), 'PATCH /model ' + label + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
}

async function okJson(resp, label) {
    const text = await resp.text();
    expect(resp.ok(), label + ': HTTP ' + resp.status() + ' ' + text.slice(0, 400)).toBe(true);
    return JSON.parse(text);
}

/** Fetch an artifact's PNG bytes through data.data → the media servlet; byte-check; write to OUT_DIR. */
async function savePng(request, PB, artifactObjectId, fileName) {
    const art = await okJson(await request.get(PB + '/artifact/' + artifactObjectId), 'GET artifact ' + artifactObjectId);
    expect(art.dataObjectId, 'artifact.dataObjectId').toBeTruthy();
    const dataRec = await okJson(await request.get(REST + '/model/data.data/' + art.dataObjectId), 'GET data.data');
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
    console.log(TAG + '-png ' + fileName + ' ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) role=' + art.role
        + ' revision=' + art.revision + ' selected=' + art.selected + ' backend=' + art.backend + ' seed=' + art.seed
        + ' durationMs=' + art.durationMs + ' reviewStatus=' + art.reviewStatus + ' -> ' + pngPath);
    return { art, dims, pngPath };
}

/** POST /scene/{id}/render-plan (synchronous) → {status, text, dto, ms}. */
async function renderPlan(request, PB, sceneOid, label) {
    const started = Date.now();
    const resp = await request.post(PB + '/scene/' + sceneOid + '/render-plan', { timeout: 3600000 });
    const text = await resp.text();
    const ms = Date.now() - started;
    console.log(TAG + ' render-plan ' + label + ': HTTP ' + resp.status() + ' ' + ms + 'ms ' + text.slice(0, 900));
    expect(resp.status(), 'render-plan ' + label + ': ' + text.slice(0, 500)).toBe(200);
    const dto = JSON.parse(text);
    expect(dto.executed, label + ' executed').toBe(true);
    expect(dto.renderPath).toBe('v3');
    expect(dto.artifactObjectId && dto.planArtifactObjectId && dto.runObjectId && dto.nodeObjectId, label + ' ids: ' + text.slice(0, 300)).toBeTruthy();
    expect(upper(dto.nodeStatus), label + ' nodeStatus').toBe('DONE_UNVERIFIED');
    return { status: resp.status(), text, dto, ms };
}

/** POST /scene/{id}/plan (read-only inspector) → the RenderPlan map. */
async function planOf(request, PB, sceneOid, label) {
    const plan = await okJson(await request.post(PB + '/scene/' + sceneOid + '/plan', { data: {}, timeout: 120000 }), 'POST /plan ' + label);
    console.log(TAG + ' plan ' + label + ': location=' + JSON.stringify(plan.location) + ' strategy=' + JSON.stringify(plan.strategy)
        + ' degradations=' + JSON.stringify(plan.degradations || []) + ' actors=' + JSON.stringify((plan.actors || []).map(a => [a.name, a.referenced, a.referenceStage, a.policy])));
    return plan;
}

/** The persisted plan artifact of a render-plan result, parsed. */
async function planArtifact(request, PB, planArtifactObjectId, label) {
    const art = await okJson(await request.get(PB + '/artifact/' + planArtifactObjectId), 'GET plan artifact ' + label);
    expect(upper(art.role), label + ' plan artifact role').toBe('RENDERPLAN');
    expect(art.artifactText, label + ' plan artifactText').toBeTruthy();
    const plan = JSON.parse(art.artifactText);
    console.log(TAG + ' plan artifact ' + label + ': strategy=' + JSON.stringify(plan.strategy) + ' location=' + JSON.stringify(plan.location)
        + ' models=' + JSON.stringify(plan.models) + ' degradations=' + JSON.stringify(plan.degradations || [])
        + ' actors=' + JSON.stringify((plan.actors || []).map(a => ({ name: a.name, role: a.role, priority: a.priority, box: a.box, referenced: a.referenced, referenceStage: a.referenceStage, policy: a.policy, portrait: a.identity && a.identity.portraitArtifactId }))));
    return plan;
}

function codesOf(plan) { return (plan.degradations || []).map(d => d.code); }
function actorNamed(plan, name) { return (plan.actors || []).find(a => a.name === name); }

/** GET /workflow → {status, view}. */
async function workflow(request, PB) {
    const resp = await request.get(PB + '/workflow');
    const text = await resp.text();
    return { status: resp.status(), text, view: (resp.ok() ? JSON.parse(text) : null) };
}

async function nodeView(request, PB, nodeOid) {
    return okJson(await request.get(PB + '/workflow/node/' + nodeOid), 'GET /workflow/node/' + nodeOid);
}

/** A node view's artifact chain for a role, matched case-insensitively (keys are lowerCamel: base, portrait, renderPlan...). */
function chainOf(view, role) {
    const arts = view.artifacts || {};
    const key = Object.keys(arts).find(k => k.toLowerCase() === role.toLowerCase());
    return key ? arts[key] : [];
}

async function artifact(request, PB, oid) {
    return okJson(await request.get(PB + '/artifact/' + oid), 'GET /artifact/' + oid);
}

async function approve(request, PB, artifactOid, label) {
    const dto = await okJson(await request.put(PB + '/artifact/' + artifactOid + '/approve'), 'approve ' + label);
    expect(upper(dto.reviewStatus), 'approve ' + label).toBe('APPROVED');
    return dto;
}

async function pin(request, PB, nodeOid, pinned) {
    return okJson(await request.post(PB + '/node/' + nodeOid + '/pin', { data: { pinned } }), 'pin ' + nodeOid + ' ' + pinned);
}

async function affectedBy(request, PB, refModel, oid, label) {
    const dto = await okJson(await request.get(PB + '/affected-by/' + refModel + '/' + oid), 'affected-by ' + label);
    console.log(TAG + ' affected-by ' + label + ': ' + JSON.stringify(dto));
    return dto;
}

async function getLocation(request, PB, oid) {
    return okJson(await request.get(PB + '/location/' + oid), 'GET /location/' + oid);
}

async function pages(request, PB) {
    return okJson(await request.get(PB + '/pages'), 'GET /pages');
}

async function nodeRow(request, nodeOid) {
    return search(request, 'olio.pb.node', [{ name: 'objectId', comparator: 'equals', value: nodeOid }], ['id', 'objectId', 'handle', 'nodeStatus', 'inputHash', 'pinned']);
}

/**
 * Poll GET /rest/job/{id} to terminal. Logs every phase change. Fails only on a job error or on no
 * progress for NO_PROGRESS_MS - never on how long the render takes.
 */
async function pollJob(request, jobId, label) {
    const started = Date.now();
    let last = null, lastChangeAt = Date.now(), job = null;
    for (;;) {
        const r = await request.get(REST + '/job/' + jobId);
        expect(r.ok(), 'GET /job/' + jobId + ' (' + label + '): HTTP ' + r.status()).toBe(true);
        job = await r.json();
        const phase = job.status + '/' + job.phase + '/' + job.current + '/' + job.total;
        if (phase !== last) { console.log(TAG + '-job ' + label + ' +' + (Date.now() - started) + 'ms ' + phase); last = phase; lastChangeAt = Date.now(); }
        if (job.terminal) break;
        expect(Date.now() - lastChangeAt < NO_PROGRESS_MS, label + ': no job progress for ' + NO_PROGRESS_MS + 'ms: ' + JSON.stringify(job)).toBe(true);
        await sleep(3000);
    }
    console.log(TAG + '-job ' + label + ' terminal after ' + (Date.now() - started) + 'ms: ' + JSON.stringify({ status: job.status, cancelled: job.cancelled, error: job.error, result: job.result }).slice(0, 1500));
    expect(job.status, label + ' job: ' + (job.error || JSON.stringify(job).slice(0, 300))).toBe('completed');
    expect(job.result && job.result.failedNodeCount, label + ' failedNodeCount').toBe(0);
    return job;
}

/** Submit a location render (202 job), wait, and return the location DTO afterwards. */
async function renderLocation(request, PB, locOid, force, label) {
    const resp = await request.post(PB + '/location/' + locOid + '/render' + (force ? '?force=true' : ''), { data: {} });
    const text = await resp.text();
    expect(resp.status(), 'POST /location/' + locOid + '/render (' + label + '): ' + text.slice(0, 300)).toBe(202);
    const sub = JSON.parse(text);
    expect(sub.jobId && sub.runObjectId && sub.nodeObjectId, label + ' submit: ' + text.slice(0, 300)).toBeTruthy();
    await pollJob(request, sub.jobId, label);
    return { sub, loc: await getLocation(request, PB, locOid) };
}

async function createNode(request, wf, wfRef, handle, nodeType, scope, scopeRef, ordinal, sceneIndex) {
    const body = { schema: 'olio.pb.node', name: handle, groupId: wf.groupId, groupPath: wf.groupPath, workflow: wfRef,
        handle, nodeType, nodeStatus: 'pending', ordinal, scope, scopeRef };
    if (sceneIndex != null) body.sceneIndex = sceneIndex;
    const n = await postModel(request, body, 'node ' + handle);
    return { id: n.id, objectId: n.objectId, handle };
}

async function bind(request, PB, targetNodeOid, role, sourceNodeOid, label) {
    const resp = await request.post(PB + '/node/' + targetNodeOid + '/bind', { data: { role, sourceNodeObjectId: sourceNodeOid } });
    const text = await resp.text();
    expect(resp.status(), 'bind ' + label + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(200);
    return JSON.parse(text).bindingObjectId;
}

/** Reader helpers. */
async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
}

/** Toasts expire after 5 s (pageClient.js); record every `.toast-text` as it appears (idempotent per page). */
async function captureToasts(page) {
    await page.evaluate(() => {
        if (window.__p75Toasts) return;
        window.__p75Toasts = [];
        const grab = () => document.querySelectorAll('.toast-text').forEach(el => {
            const t = (el.textContent || '').trim();
            if (t && !window.__p75Toasts.includes(t)) window.__p75Toasts.push(t);
        });
        new MutationObserver(grab).observe(document.body, { childList: true, subtree: true, characterData: true });
        grab();
    });
}
function capturedToasts(page) {
    return page.evaluate(() => window.__p75Toasts || []);
}

// ----- fixture -----
const CHARS = [
    { key: 'alice', first: 'Alice', last: 'Marrow', gender: 'female', hairStyle: 'long copper braid',
      prompt: 'a woman in her late twenties with a long copper-red braid over one shoulder, green eyes, freckles, a dark blue sailor\'s coat, head and shoulders portrait, warm lamplight, painterly',
      physical: 'long copper-red braid, green eyes, freckles', outfit: 'dark blue sailor\'s coat' },
    { key: 'bob', first: 'Bob', last: 'Teague', gender: 'male', hairStyle: 'short grey, close beard',
      prompt: 'a broad man in his fifties with short grey hair and a close grey beard, weathered face, a brown leather apron over a cream shirt, head and shoulders portrait, warm lamplight, painterly',
      physical: 'short grey hair, close grey beard, weathered face', outfit: 'brown leather apron over a cream shirt' },
    { key: 'cara', first: 'Cara', last: 'Voss', gender: 'female', hairStyle: 'close-cropped black',
      prompt: 'a slim woman in her thirties with close-cropped black hair, dark eyes, a thin scar on her chin, a grey hooded travelling cloak, head and shoulders portrait, warm lamplight, painterly',
      physical: 'close-cropped black hair, dark eyes, thin scar on the chin', outfit: 'grey hooded travelling cloak' },
    { key: 'dev', first: 'Dev', last: 'Okonkwo', gender: 'male', hairStyle: 'shaved head',
      prompt: 'a tall young man with a shaved head, brown skin, a gold ear stud, a red quilted jacket, head and shoulders portrait, warm lamplight, painterly',
      physical: 'shaved head, brown skin, gold ear stud', outfit: 'red quilted jacket' }
];
const TAVERN = { name: 'The Drowned Anchor', kind: 'interior',
    description: 'a low-beamed harbour tavern, smoke-stained timber, a long oak bar with brass taps, lanterns on chains, fishing nets and a ship\'s wheel on the wall, rain against small leaded windows' };
const SCENES = {
    1: { title: 'Two at the bar', setting: 'the long oak bar of the Drowned Anchor, late evening, lanterns lit', action: 'Alice leans on the bar telling a story while Bob polishes a tankard and listens', mood: 'warm, conspiratorial' },
    2: { title: 'The crew gathers', setting: 'the corner table of the Drowned Anchor under the ship\'s wheel', action: 'Alice, Bob, Cara and Dev crowd round a chart spread on the table, Alice pointing at it', mood: 'tense, expectant' },
    3: { title: 'Rain at the window', setting: 'the window seat of the Drowned Anchor, rain streaking the leaded glass', action: 'an empty table with two tankards and a guttering candle', mood: 'quiet' },
    4: { title: 'Alice alone', setting: 'the empty bar of the Drowned Anchor after closing, one lantern still lit', action: 'Alice sits alone on a stool, turning a coin over in her fingers', mood: 'pensive' },
    7: { title: 'Morning at the Anchor', setting: 'the Drowned Anchor at first light, chairs up on the tables', action: 'sunlight through the small windows catches dust in the air', mood: 'still' },
    12: { title: 'The last night', setting: 'the Drowned Anchor packed and loud, every lantern burning', action: 'a fiddler plays by the hearth and the crowd stamps along', mood: 'raucous' }
};
// P5 box layout for two actors; four narrower boxes for scene 2.
function boxes(n) {
    return n === 2
        ? [{ x: 0.04, y: 0.15, w: 0.28, h: 0.80 }, { x: 0.36, y: 0.15, w: 0.28, h: 0.80 }]
        : [0, 1, 2, 3].map(i => ({ x: 0.02 + i * 0.245, y: 0.15, w: 0.22, h: 0.80 }));
}

let bookObjectId = null;
let slug = null;
let PB = null;
const fx = { book: null, world: null, tavern: null, tavernNode: null, tavernArtifact: null, chars: {}, scenes: {}, nodes: {}, compositions: {} };
const res = { A: null, B: null, C: null, D: null, E: null, G: null };

test.describe('PictureBook V3 P7-5 — Scenarios A–G on the Docker stack', () => {
    // Serial: every scenario needs the state the previous ones produced. One hour per test / hook:
    // several flux2 renders on .39 plus REST round trips. Never a threshold.
    test.describe.configure({ mode: 'serial', timeout: 3600000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        if (!SD_ENABLED) return;
        /// describe.configure's timeout does not reach hooks (the hook ran into the 60 s config default on
        /// its first flux2 render); setTimeout inside a beforeAll sets the hook's own timeout.
        test.setTimeout(3600000);
        shared = await ensureSharedTestUser(request);
        await login(request);
        const ts = Date.now().toString(36);
        slug = 'p75-scen-' + ts;

        // 1. A fresh STORY book (own world; Book/Workflow/Artifacts groups; no workflow, no scenes).
        const chapter = await okJson(await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'P7-5 Scenarios ' + ts }, timeout: 180000 }), 'POST /chapter');
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded STORY book objectId: ' + JSON.stringify(chapter).slice(0, 300)).toBeTruthy();
        PB = PB_ROOT + '/' + bookObjectId;
        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId', 'bookType']);
        expect(book && book.id && book.groupId && book.groupPath, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        expect(upper(book.bookType), 'seeded book is STORY').toBe('STORY');
        fx.book = { id: book.id, objectId: book.objectId, slug: book.slug, groupId: book.groupId, groupPath: book.groupPath };
        const bookRef = { schema: 'olio.pb.book', id: book.id, objectId: book.objectId };
        // GET /characters (and the reader's Characters button) resolve the book through ~/Data/PictureBooks/{slug}.
        await makePath(request, '~/Data/PictureBooks/' + slug);

        // 2. Pin every task model to flux2: the deployment default (SDXL) has no reference support.
        const styleBody = { taskModels: { portrait: FLUX2, location: FLUX2, sceneDirect: FLUX2, inpaint: FLUX2 } };
        const styleResp = await request.put(PB + '/style', { data: styleBody });
        const styleText = await styleResp.text();
        console.log(TAG + ' PUT /style ' + JSON.stringify(styleBody) + ' -> HTTP ' + styleResp.status() + ' ' + styleText.slice(0, 400));
        expect(styleResp.status(), 'PUT /style: ' + styleText.slice(0, 300)).toBe(200);

        const bookFull = await okJson(await request.get(REST + '/model/olio.pb.book/' + bookObjectId + '/full'), 'GET book /full');
        expect(bookFull.world && bookFull.world.objectId, 'book.world').toBeTruthy();
        const world = await okJson(await request.get(REST + '/model/olio.world/' + bookFull.world.objectId + '/full'), 'GET world /full');
        const pop = world && world.population;
        expect(pop && pop.id && pop.path, 'world.population group: ' + JSON.stringify(pop)).toBeTruthy();
        fx.world = { objectId: bookFull.world.objectId, populationId: pop.id, populationPath: pop.path };

        // 3. The tavern: create, render (the first location render creates the book's workflow), approve.
        const tavern = await okJson(await request.post(PB + '/locations', { data: TAVERN }), 'POST /locations');
        expect(tavern.objectId, 'tavern: ' + JSON.stringify(tavern)).toBeTruthy();
        const tavernRec = await okJson(await request.get(REST + '/model/olio.pb.location/' + tavern.objectId), 'GET olio.pb.location');
        fx.tavern = { id: tavernRec.id, objectId: tavern.objectId, name: TAVERN.name };
        const tr = await renderLocation(request, PB, tavern.objectId, false, 'tavern render 1');
        expect(tr.loc.node && tr.loc.node.chain && tr.loc.node.chain.length, 'tavern chain: ' + JSON.stringify(tr.loc).slice(0, 500)).toBe(1);
        fx.tavernNode = tr.loc.node.objectId;
        await approve(request, PB, tr.loc.node.chain[0].objectId, 'tavern render 1');
        const tavernAfter = await getLocation(request, PB, tavern.objectId);
        expect(tavernAfter.approvedRenderObjectId, 'approvedRender').toBe(tr.loc.node.chain[0].objectId);
        expect(tavernAfter.node.pinned, 'approve pins the LOCATION node').toBe(true);
        fx.tavernArtifact = tavernAfter.approvedRenderObjectId;
        await savePng(request, PB, fx.tavernArtifact, 'fixture-tavern-v1.png');

        const wfv = (await workflow(request, PB)).view;
        expect(wfv && wfv.workflowObjectId, 'workflow after the location render').toBeTruthy();
        const wf = await search(request, 'olio.pb.workflow', [{ name: 'objectId', comparator: 'equals', value: wfv.workflowObjectId }], ['id', 'objectId', 'groupId', 'groupPath']);
        expect(wf && wf.id && wf.groupId, 'workflow record').toBeTruthy();
        const wfRef = { schema: 'olio.pb.workflow', id: wf.id, objectId: wf.objectId };
        const locRef = { schema: 'olio.pb.location', id: fx.tavern.id, objectId: fx.tavern.objectId };

        // 4. Four characters in the world's population group, each with a PORTRAIT node rendered (sync
        //    regenerate) and approved. Approving pins the node; the pin is released so that Scenario D's
        //    propagation (portrait re-render before the scene) is not pre-blocked by the fixture.
        for (const c of CHARS) {
            const narrative = await postModel(request, { schema: 'olio.narrative', name: 'p75-' + c.key + '-' + ts, groupId: pop.id, groupPath: pop.path,
                sdPrompt: c.prompt, physicalDescription: c.physical, outfitDescription: c.outfit }, 'narrative ' + c.key);
            const name = c.first + ' ' + c.last;
            const cp = await postModel(request, { schema: 'olio.charPerson', name, firstName: c.first, lastName: c.last, gender: c.gender,
                alignment: 'neutralgood', hairStyle: c.hairStyle, groupId: pop.id, groupPath: pop.path }, 'charPerson ' + c.key);
            await patchModel(request, { schema: 'olio.charPerson', id: cp.id, objectId: cp.objectId, narrative: { schema: 'olio.narrative', id: narrative.id, objectId: narrative.objectId } }, 'charPerson.narrative ' + c.key);
            const handle = 'portrait@' + shortRef(cp.objectId);
            const node = await createNode(request, wf, wfRef, handle, 'portrait', 'character', cp.objectId, 0, null);
            const started = Date.now();
            const regen = await okJson(await request.post(PB + '/node/' + node.objectId + '/regenerate', { timeout: 3600000 }), 'regenerate ' + handle);
            console.log(TAG + ' portrait ' + c.key + ' regenerate ' + (Date.now() - started) + 'ms: ' + JSON.stringify(regen).slice(0, 500));
            expect(regen.executed && regen.artifactObjectId, 'portrait ' + c.key + ' rendered: ' + JSON.stringify(regen).slice(0, 300)).toBeTruthy();
            await approve(request, PB, regen.artifactObjectId, 'portrait ' + c.key);
            const pinned = await pin(request, PB, node.objectId, false);
            expect(pinned.pinned, 'portrait node ' + handle + ' unpinned after approve').toBe(false);
            await savePng(request, PB, regen.artifactObjectId, 'fixture-portrait-' + c.key + '.png');
            fx.chars[c.key] = { id: cp.id, objectId: cp.objectId, name, node, portraitArtifact: regen.artifactObjectId };
        }

        // 5. Six scenes at the tavern; compositions + actor rows (with boxes) for 1, 2, 4; RENDER_PLAN nodes
        //    by the handle ensureRenderPlanNode finds; portrait→scene node bindings (1: Alice+Bob, 4: Alice;
        //    scene 2 deliberately unbound so D reaches exactly 1 and 4); scene.sceneNode linked.
        const cast = { 1: ['alice', 'bob'], 2: ['alice', 'bob', 'cara', 'dev'], 4: ['alice'] };
        const bound = { 1: ['alice', 'bob'], 2: [], 4: ['alice'] };
        for (const idx of [1, 2, 3, 4, 7, 12]) {
            const s = SCENES[idx];
            const scene = await postModel(request, { schema: 'olio.pb.scene', name: 'Scene ' + slug + ' ' + idx, groupId: book.groupId, groupPath: book.groupPath,
                book: bookRef, sceneIndex: idx, title: s.title, setting: s.setting, action: s.action, mood: s.mood, location: locRef }, 'scene ' + idx);
            fx.scenes[idx] = { id: scene.id, objectId: scene.objectId, index: idx };
            if (!cast[idx]) continue;
            const comp = await postModel(request, { schema: 'olio.pb.composition', name: 'Composition ' + slug + ' ' + idx, groupId: book.groupId, groupPath: book.groupPath,
                book: bookRef, scene: { schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId }, purpose: 'scene', source: 'user',
                settingText: s.setting, action: s.action, mood: s.mood, shotType: 'medium', viewpoint: 'eye_level', revision: 1 }, 'composition ' + idx);
            const bx = boxes(cast[idx].length);
            const actors = [];
            for (let i = 0; i < cast[idx].length; i++) {
                const c = fx.chars[cast[idx][i]];
                const body = { characterObjectId: c.objectId, role: (i < 2 ? 'hero' : 'supporting'), priority: i + 1, x: bx[i].x, y: bx[i].y, w: bx[i].w, h: bx[i].h };
                const a = await okJson(await request.post(PB + '/composition/' + comp.objectId + '/actor', { data: body }), 'actor ' + cast[idx][i] + ' scene ' + idx);
                expect(a.created && a.actorObjectId, 'actor row: ' + JSON.stringify(a).slice(0, 300)).toBeTruthy();
                actors.push({ key: cast[idx][i], actorObjectId: a.actorObjectId, box: bx[i] });
            }
            fx.compositions[idx] = { objectId: comp.objectId, actors };
            const handle = 'render_plan@' + shortRef(scene.objectId);
            const node = await createNode(request, wf, wfRef, handle, 'render_plan', 'scene', scene.objectId, idx, idx);
            for (const key of bound[idx]) await bind(request, PB, node.objectId, 'portrait', fx.chars[key].node.objectId, key + '→scene ' + idx);
            await patchModel(request, { schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId, sceneNode: { schema: 'olio.pb.node', id: node.id, objectId: node.objectId } }, 'scene ' + idx + '.sceneNode');
            fx.nodes[idx] = node;
        }
        const pg = await pages(request, PB);
        for (const idx of [1, 2, 4]) {
            const p = pg.find(x => x.objectId === fx.scenes[idx].objectId);
            expect(p && p.sceneNodeObjectId, 'page ' + idx + ' carries sceneNodeObjectId: ' + JSON.stringify(p)).toBe(fx.nodes[idx].objectId);
        }
        const chars = await okJson(await request.get(PB + '/characters'), 'GET /characters');
        expect(CHARS.every(c => chars.some(x => x.objectId === fx.chars[c.key].objectId)), '/characters lists the four: ' + JSON.stringify(chars).slice(0, 300)).toBe(true);
        console.log(TAG + '-seed ' + JSON.stringify({ book: bookObjectId, slug, ...fx }));
        await request.get(REST + '/logout');
    });

    test.afterAll(async ({ request }) => {
        if (!SD_ENABLED || !bookObjectId) return;
        console.log(TAG + '-cleanup left in place (nodes + artifacts reference them): ' + JSON.stringify({ book: bookObjectId, slug, res }));
    });

    test('[SD] Scenario A — Alice and Bob in the tavern: both referenced in BASE, approved tavern reused, composition boxes respected, pinned model used', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass: the live .39 renders are the test');
        expect(bookObjectId, 'fixture seeded').toBeTruthy();
        await login(request);
        const sc = fx.scenes[1];
        const r = await renderPlan(request, PB, sc.objectId, 'A scene 1');
        expect(r.dto.nodeObjectId, 'the pre-created RENDER_PLAN node is the one executed').toBe(fx.nodes[1].objectId);
        expect(upper(r.dto.strategy), 'two referenced actors with slots to spare').toBe('DIRECT_REFERENCE');
        expect(r.dto.stagesRendered, 'BASE rendered').toContain('BASE');
        expect(r.dto.stagesRendered).not.toContain('ACTOR_REGION');
        expect((r.dto.degradations || []).filter(d => /ACTOR_TEXT_ONLY|LOCATION_TEXT_ONLY/.test(d)), 'no identity/location degradation: ' + JSON.stringify(r.dto.degradations)).toEqual([]);

        const plan = await planArtifact(request, PB, r.dto.planArtifactObjectId, 'A');
        expect(plan.location && plan.location.locationId, 'location on the plan').toBe(fx.tavern.objectId);
        expect(plan.location.renderArtifactId, 'the approved tavern render rides the plan').toBe(fx.tavernArtifact);
        expect(codesOf(plan)).not.toContain('LOCATION_TEXT_ONLY');
        expect(codesOf(plan)).not.toContain('ACTOR_TEXT_ONLY');
        expect((plan.actors || []).map(a => a.name).sort(), 'both actors on the plan').toEqual([fx.chars.alice.name, fx.chars.bob.name].sort());
        for (const key of ['alice', 'bob']) {
            const a = actorNamed(plan, fx.chars[key].name);
            expect(a.referenced, key + ' referenced').toBe(true);
            expect(upper(a.referenceStage), key + ' reference stage').toBe('BASE');
            expect(a.identity && a.identity.portraitArtifactId, key + ' identity is the approved portrait').toBe(fx.chars[key].portraitArtifact);
            const seeded = fx.compositions[1].actors.find(x => x.key === key).box;
            expect(a.box, key + ' box is the composition box').toEqual(expect.objectContaining(seeded));
        }
        expect(plan.models && plan.models.sceneDirect && String(plan.models.sceneDirect.model), 'sceneDirect model is the pinned flux2').toMatch(/flux2/i);
        expect(plan.models.sceneDirect.degraded, 'not degraded').toBeFalsy();
        const png = await savePng(request, PB, r.dto.artifactObjectId, 'a-scene1.png');
        expect(upper(png.art.role)).toBe('BASE');
        res.A = { nodeObjectId: r.dto.nodeObjectId, artifactObjectId: r.dto.artifactObjectId, planArtifactObjectId: r.dto.planArtifactObjectId, runObjectId: r.dto.runObjectId, revisionHash: plan.location.revisionHash, ms: r.ms };
        await request.get(REST + '/logout');
    });

    test('[SD] Scenario B — four characters: all four on the plan, two BASE references, two regional actors, none dropped', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(res.A, 'Scenario A ran').toBeTruthy();
        await login(request);
        const r = await renderPlan(request, PB, fx.scenes[2].objectId, 'B scene 2');
        expect(r.dto.nodeObjectId).toBe(fx.nodes[2].objectId);
        expect(upper(r.dto.strategy), 'overflow actors with boxes → regional pass').toBe('SEQUENTIAL_INPAINT');
        expect((r.dto.degradations || []).filter(d => /ACTOR_TEXT_ONLY/.test(d)), 'no actor silently dropped: ' + JSON.stringify(r.dto.degradations)).toEqual([]);

        const plan = await planArtifact(request, PB, r.dto.planArtifactObjectId, 'B');
        const names = CHARS.map(c => fx.chars[c.key].name).sort();
        expect((plan.actors || []).map(a => a.name).sort(), 'all four represented').toEqual(names);
        expect(plan.actors.every(a => a.referenced === true), 'every actor referenced: ' + JSON.stringify(plan.actors.map(a => [a.name, a.referenced, a.policy]))).toBe(true);
        const base = plan.actors.filter(a => upper(a.referenceStage) === 'BASE').map(a => a.name);
        const regionActors = plan.actors.filter(a => upper(a.referenceStage) === 'ACTOR_REGION');
        const region = regionActors.map(a => a.name);
        console.log(TAG + ' B references: BASE=' + JSON.stringify(base) + ' ACTOR_REGION=' + JSON.stringify(region) + ' stagesRendered=' + JSON.stringify(r.dto.stagesRendered));
        expect(base.length, 'primary references selected automatically (maxReferences 3 − location)').toBe(2);
        expect(region.length, 'the other two generated regionally').toBe(2);
        // Rendered stages are keyed per actor pass (RenderPlan.actorStageKey, P5-2): BASE once, then one
        // ACTOR_REGION#<actorId> for each regional actor — proof that neither regional pass was skipped.
        expect(r.dto.stagesRendered, 'the BASE stage rendered').toContain('BASE');
        for (const a of regionActors) {
            expect(r.dto.stagesRendered, 'a regional pass rendered for ' + a.name).toContain('ACTOR_REGION#' + a.actorId);
        }
        expect(r.dto.stagesRendered.filter(s => /^ACTOR_REGION#/.test(s)).length, 'exactly one regional pass per regional actor').toBe(regionActors.length);
        expect(codesOf(plan)).not.toContain('ACTOR_TEXT_ONLY');
        expect(plan.location.renderArtifactId).toBe(fx.tavernArtifact);
        const view = await nodeView(request, PB, fx.nodes[2].objectId);
        console.log(TAG + ' B node chains: ' + JSON.stringify(Object.fromEntries(Object.entries(view.artifacts || {}).map(([k, v]) => [k, v.map(a => a.objectId)]))));
        const png = await savePng(request, PB, r.dto.artifactObjectId, 'b-scene2.png');
        console.log(TAG + ' B final artifact role=' + png.art.role);
        res.B = { nodeObjectId: r.dto.nodeObjectId, artifactObjectId: r.dto.artifactObjectId, planArtifactObjectId: r.dto.planArtifactObjectId, runObjectId: r.dto.runObjectId, base, region, ms: r.ms };
        await request.get(REST + '/logout');
    });

    test('Scenario C — scenes 3, 7, 12 in the same tavern reference one Location entity with one approved visual identity', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(res.A, 'Scenario A ran').toBeTruthy();
        await login(request);
        const seen = [];
        for (const idx of [3, 7, 12]) {
            const plan = await planOf(request, PB, fx.scenes[idx].objectId, 'C scene ' + idx);
            expect(plan.location && plan.location.locationId, 'scene ' + idx + ' names the tavern').toBe(fx.tavern.objectId);
            expect(plan.location.renderArtifactId, 'scene ' + idx + ' carries the approved render').toBe(fx.tavernArtifact);
            expect(plan.location.revisionHash, 'scene ' + idx + ' same revisionHash as scene 1').toBe(res.A.revisionHash);
            expect(plan.inputs && plan.inputs.location, 'scene ' + idx + ' inputs.location').toBe(res.A.revisionHash);
            expect(codesOf(plan), 'scene ' + idx + ' no LOCATION_TEXT_ONLY').not.toContain('LOCATION_TEXT_ONLY');
            seen.push({ idx, locationId: plan.location.locationId, renderArtifactId: plan.location.renderArtifactId, revisionHash: plan.location.revisionHash });
        }
        res.C = seen;
        await request.get(REST + '/logout');
    });

    test('[SD] Scenario D — Alice\'s hair changes: scenes 1 and 4 go stale, approved scene 1 stays, Regenerate re-renders scene 4 and skips pinned scene 1; the approved new portrait reaches the plan', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(res.A, 'Scenario A ran').toBeTruthy();
        const alice = fx.chars.alice;
        await login(request);

        // 1. Scene 4 rendered once (Alice alone); scene 1's render approved → its node is pinned.
        const d1 = await renderPlan(request, PB, fx.scenes[4].objectId, 'D scene 4 v1');
        await savePng(request, PB, d1.dto.artifactObjectId, 'd-scene4-v1.png');
        await approve(request, PB, res.A.artifactObjectId, 'scene 1 render');
        expect((await nodeRow(request, fx.nodes[1].objectId)).pinned, 'approving scene 1 pinned its node').toBe(true);
        // A RENDER_PLAN node in a run renders the preset's variants per attempt (STANDARD = 2, P6-2), so the
        // chain is captured here and compared whole after the regenerate - never assumed to be one artifact.
        const c1Before = chainOf(await nodeView(request, PB, fx.nodes[1].objectId), 'base');
        expect(c1Before.filter(a => a.selected).map(a => a.objectId), 'scene 1 shows the approved render').toEqual([res.A.artifactObjectId]);
        console.log(TAG + ' D scene 1 chain before: ' + JSON.stringify(c1Before.map(a => ({ oid: a.objectId.slice(0, 8), rev: a.revision, v: a.variantIndex, sel: a.selected }))));
        const before = await affectedBy(request, PB, 'olio.charPerson', alice.objectId, 'Alice before');
        expect(before.boundNodes, 'P(Alice) bound to the character by its portrait render').toBe(1);
        expect(before.drifted).toBe(false);
        expect(before.affectedSceneIndexes, 'scenes 1 and 4 bind Alice\'s portrait').toEqual([1, 4]);
        expect(before.staleSceneIndexes || []).toEqual([]);

        // 2. The hair change (hairStyle is in the watched set; hairColor is a data.color foreign record).
        const newHair = 'cropped short, silver-white';
        await patchModel(request, { schema: 'olio.charPerson', id: alice.id, objectId: alice.objectId, hairStyle: newHair }, 'Alice.hairStyle');
        const after = await affectedBy(request, PB, 'olio.charPerson', alice.objectId, 'Alice after');
        expect(after.boundNodes).toBe(1);
        expect(after.drifted, 'the bound portrait node drifted (Alice version moved)').toBe(true);
        expect(after.driftedHandles).toContain(alice.node.handle);
        expect(after.affectedSceneIndexes).toEqual([1, 4]);
        expect(after.staleSceneIndexes, 'STALE propagated P(Alice) → scenes 1 and 4').toEqual([1, 4]);
        expect(after.markPersisted).toBe(true);
        const a1 = await artifact(request, PB, res.A.artifactObjectId);
        expect(upper(a1.reviewStatus), 'the approved scene 1 render stays approved').toBe('APPROVED');
        for (const idx of [1, 4]) expect(upper((await nodeRow(request, fx.nodes[idx].objectId)).nodeStatus), 'node ' + idx + ' stored STALE').toBe('STALE');

        // 3. The character manager shows it: "Affected scenes: 2 (1, 4)" + "2 stale".
        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await page.locator('[data-pb2-characters="' + bookObjectId + '"]').click();
        await expect(page.locator('.am7-dialog-title', { hasText: 'Manage Characters' })).toBeVisible({ timeout: 30000 });
        const item = page.locator('[data-char-item="' + alice.objectId + '"]');
        await expect(item).toBeVisible({ timeout: 30000 });
        await item.click();
        await expect(page.locator('[data-char-name]')).toHaveText(alice.name, { timeout: 30000 });
        const count = page.getByTestId('pbchar-affected-count');
        await expect(count).toBeVisible({ timeout: 30000 });
        await expect(count).toHaveAttribute('data-count', '2');
        await expect(count).toHaveText('Affected scenes: 2 (1, 4)');
        const drifted = page.getByTestId('pbchar-affected-drifted');
        await expect(drifted).toHaveText('2 stale');
        expect(await drifted.getAttribute('title'), 'the drifted node is P(Alice)').toContain(alice.node.handle);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/d-character-manager.png', fullPage: true });

        // 4. Regenerate: scene 1 is pinned → "Scene 1 is pinned" confirm → Cancel (skipped); scene 4 renders
        //    (P(Alice) first, then the scene). Toasts expire after 5 s (pageClient.js), so they are captured
        //    as they appear rather than asserted after the poll.
        const regenBtn = page.getByTestId('pbchar-affected-regenerate');
        await expect(regenBtn).toBeEnabled();
        await captureToasts(page);
        const scene4Submit = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/scene/' + fx.scenes[4].objectId + '/render'), { timeout: 600000 });
        await regenBtn.click();
        const confirm = page.locator('.am7-dialog', { has: page.locator('.am7-dialog-title', { hasText: 'Scene 1 is pinned' }) });
        await expect(confirm).toBeVisible({ timeout: 60000 });
        await expect(confirm.locator('.am7-dialog-body')).toContainText('is pinned');
        await expect(confirm.locator('.am7-dialog-footer button.am7-dialog-btn-primary')).toHaveText(/Unpin and re-render$/);
        await confirm.locator('.am7-dialog-footer button.am7-dialog-btn', { hasText: 'Cancel' }).click();
        await expect(confirm).toHaveCount(0);
        const submitResp = await scene4Submit;
        const submitText = await submitResp.text();
        console.log(TAG + ' D regenerate scene 4 submit: HTTP ' + submitResp.status() + ' ' + submitText.slice(0, 600));
        expect(submitResp.status(), 'POST /scene/4/render?force=true: ' + submitText.slice(0, 300)).toBe(202);
        const sub = JSON.parse(submitText);
        expect([...sub.planned].sort(), 'P(Alice) (stale, unpinned) and scene 4 planned').toEqual([alice.node.handle, fx.nodes[4].handle].sort());
        await expect(regenBtn).toHaveText(/^Regenerating/);
        const job = await pollJob(request, sub.jobId, 'D scene 4 regenerate');
        expect(job.result.executedNodeCount, 'portrait + scene executed').toBe(2);
        const oP = job.result.nodes.find(n => n.nodeObjectId === alice.node.objectId);
        const o4 = job.result.nodes.find(n => n.nodeObjectId === fx.nodes[4].objectId);
        expect(oP && oP.artifactObjectId && o4 && o4.artifactObjectId, 'outcomes: ' + JSON.stringify(job.result.nodes)).toBeTruthy();
        await expect(regenBtn).toHaveText('Regenerate', { timeout: 120000 });
        await expect.poll(() => capturedToasts(page), { timeout: 30000, message: 'final toast' }).toContain('Regenerated 1 of 2 scenes, 1 skipped (pinned)');
        console.log(TAG + ' D toasts: ' + JSON.stringify(await capturedToasts(page)));
        await page.screenshot({ path: OUT_DIR + '/d-after-regenerate.png', fullPage: true });
        await page.locator('button[aria-label="Close dialog"]').first().click();

        // 5. Durable state: scene 1 untouched (pinned, the same chain as before the regenerate, still the
        //    approved render selected); scene 4 gained one attempt (variant 0 of each attempt is the one
        //    the DTOs name; only variant 0 is ever auto-selected); P(Alice) revision 2 is NOT approved - the
        //    scene re-render referenced the still-approved revision 1.
        const n1 = await nodeView(request, PB, fx.nodes[1].objectId);
        expect(chainOf(n1, 'base').map(a => a.objectId), 'scene 1 chain unchanged').toEqual(c1Before.map(a => a.objectId));
        expect(chainOf(n1, 'base').filter(a => a.selected).map(a => a.objectId), 'scene 1 still shows the approved render').toEqual([res.A.artifactObjectId]);
        expect((await nodeRow(request, fx.nodes[1].objectId)).pinned).toBe(true);
        const n4 = await nodeView(request, PB, fx.nodes[4].objectId);
        const c4 = chainOf(n4, 'base');
        console.log(TAG + ' D scene 4 chain: ' + JSON.stringify(c4.map(a => ({ oid: a.objectId.slice(0, 8), rev: a.revision, attempt: String(a.attempt || '').slice(0, 8), v: a.variantIndex, sel: a.selected }))));
        expect([...new Set(c4.map(a => a.attempt))], 'scene 4: the v1 attempt then the regenerate').toEqual([d1.dto.runObjectId, sub.runObjectId]);
        expect(c4.filter(a => a.variantIndex === 0).map(a => a.objectId), 'scene 4 chain: v1 then the regenerate (variant 0 of each attempt)').toEqual([d1.dto.artifactObjectId, o4.artifactObjectId]);
        expect(c4.filter(a => a.selected).map(a => a.objectId), 'the regenerate\'s variant 0 is the selected final').toEqual([o4.artifactObjectId]);
        expect(upper((await nodeRow(request, fx.nodes[4].objectId)).nodeStatus), 'scene 4 stored status after the regenerate').toBe('DONE_UNVERIFIED');
        const pv = await nodeView(request, PB, alice.node.objectId);
        const pChain = chainOf(pv, 'portrait').map(a => a.objectId);
        expect(pChain, 'P(Alice) chain: approved v1 then the regenerate').toEqual([alice.portraitArtifact, oP.artifactObjectId]);
        expect(upper((await artifact(request, PB, alice.portraitArtifact)).reviewStatus)).toBe('APPROVED');
        const p2 = await artifact(request, PB, oP.artifactObjectId);
        expect(upper(p2.reviewStatus), 'new portrait waits for review').not.toBe('APPROVED');
        const plan4b = await planArtifact(request, PB, (chainOf(n4, 'renderPlan').slice(-1)[0] || {}).objectId, 'D scene 4 v2');
        expect(actorNamed(plan4b, alice.name).identity.portraitArtifactId, 'the regenerate referenced the still-approved portrait v1').toBe(alice.portraitArtifact);
        await savePng(request, PB, oP.artifactObjectId, 'd-alice-portrait-v2.png');
        await savePng(request, PB, o4.artifactObjectId, 'd-scene4-v2.png');

        // 6. Approve the new portrait (pins P(Alice)); re-render scene 4: the plan now carries portrait v2.
        await approve(request, PB, oP.artifactObjectId, 'Alice portrait v2');
        expect(upper((await artifact(request, PB, alice.portraitArtifact)).reviewStatus), 'v1 demoted').not.toBe('APPROVED');
        const d3 = await renderPlan(request, PB, fx.scenes[4].objectId, 'D scene 4 v3');
        const plan4c = await planArtifact(request, PB, d3.dto.planArtifactObjectId, 'D scene 4 v3');
        expect(actorNamed(plan4c, alice.name).identity.portraitArtifactId, 'the new hair reaches the scene plan').toBe(oP.artifactObjectId);
        expect(actorNamed(plan4c, alice.name).referenced).toBe(true);
        await savePng(request, PB, d3.dto.artifactObjectId, 'd-scene4-v3.png');
        res.D = { newHair, scene4: [d1.dto.artifactObjectId, o4.artifactObjectId, d3.dto.artifactObjectId], portrait: pChain, regenerateJob: sub.jobId, regenerateMs: job.result.durationMs };
        await request.get(REST + '/logout');
    });

    test('[SD] Scenario E — the tavern is wrong: unpin + force-render the location, approve it; rendered scenes report stale and plans carry the new render', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(res.D, 'Scenario D ran').toBeTruthy();
        await login(request);
        // The LOCATION node binds its own record after rendering (PbNodeExecutor.executeLocation), with
        // approvedRender in the watched set - so the fixture approve already drifted the location node
        // itself. The three rendered scene nodes bound the record AFTER that approve and are current.
        const sceneHandles = [1, 2, 4].map(i => fx.nodes[i].handle);
        const before = await affectedBy(request, PB, 'olio.pb.location', fx.tavern.objectId, 'tavern before');
        expect(before.boundNodes, 'location node + scenes 1, 2, 4 bind the tavern').toBe(4);
        expect(before.affectedSceneIndexes).toEqual([1, 2, 4]);
        expect((before.driftedHandles || []).filter(h => sceneHandles.includes(h)), 'no scene node drifted yet').toEqual([]);
        expect(before.staleSceneIndexes || []).toEqual([]);
        console.log(TAG + ' E before: drifted=' + before.drifted + ' handles=' + JSON.stringify(before.driftedHandles || []) + ' (the location node self-drifts on approve)');

        // Unpin (approve pinned it) → forced re-render → approve the new revision.
        const unp = await pin(request, PB, fx.tavernNode, false);
        expect(unp.pinned).toBe(false);
        const tr = await renderLocation(request, PB, fx.tavern.objectId, true, 'tavern render 2 (forced)');
        const chain = tr.loc.node.chain;
        expect(chain.length, 'second location revision: ' + JSON.stringify(chain)).toBe(2);
        expect(tr.loc.approvedRenderObjectId, 'still the old approval until approved').toBe(fx.tavernArtifact);
        await approve(request, PB, chain[1].objectId, 'tavern render 2');
        const loc = await getLocation(request, PB, fx.tavern.objectId);
        expect(loc.approvedRenderObjectId, 'the new render is the approved one').toBe(chain[1].objectId);
        expect(loc.node.pinned).toBe(true);
        await savePng(request, PB, chain[1].objectId, 'e-tavern-v2.png');

        const after = await affectedBy(request, PB, 'olio.pb.location', fx.tavern.objectId, 'tavern after');
        expect(after.boundNodes).toBe(4);
        expect(after.drifted, 'approvedRender is a watched field of olio.pb.location').toBe(true);
        for (const h of sceneHandles) expect(after.driftedHandles, 'the three rendered scene nodes drifted').toContain(h);
        expect(after.staleSceneIndexes).toEqual([1, 2, 4]);
        expect(after.markPersisted).toBe(true);
        for (const idx of [2, 4]) {
            const plan = await planOf(request, PB, fx.scenes[idx].objectId, 'E scene ' + idx);
            expect(plan.location.renderArtifactId, 'scene ' + idx + ' plan carries the corrected tavern').toBe(chain[1].objectId);
            expect(plan.location.revisionHash).not.toBe(res.A.revisionHash);
            const alice = actorNamed(plan, fx.chars.alice.name);
            expect(alice && alice.referenced, 'scene ' + idx + ': characters still referenced').toBe(true);
        }
        const pg = await pages(request, PB);
        for (const idx of [1, 2, 4]) {
            const p = pg.find(x => x.objectId === fx.scenes[idx].objectId);
            expect(upper(p.nodeStatus), 'page ' + idx + ' nodeStatus').toBe('STALE');
        }
        console.log(TAG + ' E: "character regions remain unchanged" is NOT met - there is no background-only regenerate; G re-renders the full scene.');
        res.E = { tavernV2: chain[1].objectId, after };
        await request.get(REST + '/logout');
    });

    test('[SD] Scenario G — Render all from the reader: leave mid-run, the job continues, return and the progress panel re-attaches to terminal; review lists the nodes', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(res.E, 'Scenario E ran').toBeTruthy();
        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        const cover = await page.locator('[data-pb2-cover-count]').textContent();
        console.log(TAG + ' G cover before: ' + cover);

        // 1. Render all → dialog → Start; the 202 names the two stale, unpinned scene nodes.
        await page.getByTestId('pb2-render-all').click();
        await expect(page.getByTestId('pb2-render-dialog')).toBeVisible();
        await captureToasts(page);
        const submitPromise = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/' + bookObjectId + '/render-all'), { timeout: 120000 });
        const submittedAt = Date.now();
        await page.getByTestId('pb2-render-all-start').click();
        const submitResp = await submitPromise;
        const submitText = await submitResp.text();
        console.log(TAG + ' G render-all submit: HTTP ' + submitResp.status() + ' ' + submitText.slice(0, 800));
        expect(submitResp.status(), 'POST /render-all: ' + submitText.slice(0, 400)).toBe(202);
        const dto = JSON.parse(submitText);
        expect(dto.jobId && dto.runObjectId).toBeTruthy();
        expect(dto.planned.sort(), 'scenes 2 and 4 (stale) planned; scene 1 pinned').toEqual([fx.nodes[2].handle, fx.nodes[4].handle].sort());
        expect(dto.pinnedNodeCount, 'scene 1 (and P(Alice)) pinned, not replaced').toBeGreaterThanOrEqual(1);
        expect((dto.scenesWithoutNode || []).length, 'scenes 3, 7, 12 have no node').toBe(3);
        await expect.poll(() => capturedToasts(page), { timeout: 30000, message: 'no-node warning toast' }).toContain('3 scenes have no graph node yet and will not render');
        const progress = page.getByTestId('pb2-run-progress');
        await expect(progress).toBeVisible();
        await expect(progress).toHaveAttribute('data-run-terminal', '0');
        await expect(page.getByTestId('pb2-run-stage')).toHaveCount(2);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/g-running.png', fullPage: true });

        // 2. Leave the reader; the job is still there and still running.
        await page.locator('button[title="Back to book list"]').click();
        await expect(page).toHaveURL(/#!\/picture-book$/);
        await expect(progress).toHaveCount(0);
        await login(request);
        const first = await okJson(await request.get(REST + '/job/' + dto.jobId), 'GET /job after leaving');
        console.log(TAG + ' G first poll after leaving (+' + (Date.now() - submittedAt) + 'ms): ' + JSON.stringify({ status: first.status, phase: first.phase, current: first.current, total: first.total, terminal: first.terminal }));
        expect(first.terminal, 'the run was still in flight when the reader was left: ' + JSON.stringify(first)).toBe(false);

        // 3. Return: the reader re-attaches to the running job (progress panel, non-terminal) when it is
        //    still running; otherwise the run is simply complete. Both are logged; the job itself is then
        //    watched to terminal over REST.
        await openReader(page, bookObjectId);
        const onReturn = await okJson(await request.get(REST + '/job/' + dto.jobId), 'GET /job on return');
        if (!onReturn.terminal) {
            await expect(progress).toBeVisible({ timeout: 30000 });
            await expect(progress).toHaveAttribute('data-run-terminal', '0');
            console.log(TAG + ' G reattached: ' + await page.getByTestId('pb2-run-counter').textContent());
            await page.screenshot({ path: OUT_DIR + '/g-reattached.png', fullPage: true });
        } else {
            console.log(TAG + ' G the run finished before the return (' + (Date.now() - submittedAt) + 'ms)');
        }
        const job = await pollJob(request, dto.jobId, 'G render-all');
        expect(job.result.executedNodeCount).toBe(2);
        const o2 = job.result.nodes.find(n => n.nodeObjectId === fx.nodes[2].objectId);
        const o4 = job.result.nodes.find(n => n.nodeObjectId === fx.nodes[4].objectId);
        expect(o2 && o2.artifactObjectId && o4 && o4.artifactObjectId, 'outcomes: ' + JSON.stringify(job.result.nodes)).toBeTruthy();
        expect(upper(o2.status)).toBe('DONE_UNVERIFIED');
        expect(upper(o4.status)).toBe('DONE_UNVERIFIED');
        if (!onReturn.terminal) {
            await expect(progress).toHaveAttribute('data-run-terminal', '1', { timeout: 120000 });
            console.log(TAG + ' G terminal panel: ' + await page.getByTestId('pb2-run-counter').textContent());
            await expect(page.locator('[data-testid="pb2-run-stage"][data-stage-state="done"]')).toHaveCount(2);
            await page.screenshot({ path: OUT_DIR + '/g-terminal.png', fullPage: true });
        }
        const runDto = await okJson(await request.get(PB + '/run/' + dto.runObjectId), 'GET /run');
        expect(upper(runDto.runStatus)).toBe('COMPLETED');

        // 4. Inspect: the review page lists the nodes; the pictures.
        await page.goto('/#!/picture-book/' + bookObjectId + '/review', { timeout: 30000 });
        await expect(page.getByTestId('pb2-review')).toBeVisible({ timeout: 30000 });
        for (const idx of [1, 2, 4]) await expect(page.locator('[data-testid="pb2-review-node"][data-node-id="' + fx.nodes[idx].objectId + '"]')).toBeVisible({ timeout: 30000 });
        await page.screenshot({ path: OUT_DIR + '/g-review.png', fullPage: true });
        await savePng(request, PB, o2.artifactObjectId, 'g-scene2-v2.png');
        await savePng(request, PB, o4.artifactObjectId, 'g-scene4-v4.png');
        const n1 = await nodeRow(request, fx.nodes[1].objectId);
        expect(n1.pinned, 'scene 1 still pinned').toBe(true);
        console.log(TAG + ' G cover after: ' + (await (async () => { await openReader(page, bookObjectId); return page.locator('[data-pb2-cover-count]').textContent(); })()));
        res.G = { jobId: dto.jobId, runObjectId: dto.runObjectId, leftAfterMs: first.terminal ? null : 'in flight', durationMs: job.result.durationMs, scene2: o2.artifactObjectId, scene4: o4.artifactObjectId };
        await request.get(REST + '/logout');
    });
});
