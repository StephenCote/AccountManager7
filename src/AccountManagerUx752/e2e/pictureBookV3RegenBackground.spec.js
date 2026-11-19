/**
 * PictureBook V3 — §24 I-8 (design §22, G-29 / 219 e): background-only regenerate of a scene, through the
 * reader's "Regenerate background" button and the route behind it
 * (POST /olio/picture-book/{book}/scene/{scene}/regenerate-background → 202 async job).
 *
 * The slice is built on ONE node kind — the scene's own RENDER_PLAN node (no new node type): the job re-renders
 * the node's selected `base` final with a fresh background seed, one variant, always forced, and each
 * ACTOR_REGION pass reuses the seed it had so the actors come back as they were. A plain text-only scene has no
 * actor passes, so the product degrades the run as BACKGROUND_REGEN_NO_REGIONS (which vetoes auto-approve) and
 * says so in the job outcome. That is the path this spec drives: it is the one reachable without a cast and a
 * composition, and it proves the route, the job, the artifact chain and the reader control end to end. The
 * actor-seed reuse itself is pinned offline (TestPbRenderStagesOffline) and live (TestPbVariantsLive scenario H).
 *
 * Fixture (the render-plan spec's shape, NOT the plain-PB spec's): a fresh STORY book via POST /chapter and two
 * seeded olio.pb.scene rows — scene 0 with setting/action/mood prose, scene 1 with no text at all. No sceneNode
 * is seeded: a seeded LANDSCAPE sceneNode would be refused by the route ("not a RENDER_PLAN node"), and the
 * synchronous POST /scene/{id}/render-plan creates the RENDER_PLAN node + first base. That first render DOES use
 * the LLM: a scene with no composition is composed by the render itself (design §9.1 lazy compose,
 * PbComposeUtil.ensureComposition, COMPOSED_OUT_OF_ORDER degradation) through the org's default chat config, and
 * the stack's visual-QA judge (VLM) then grades the image — a WARN flags it IN_REVIEW, which this spec accepts
 * (see UNAPPROVED). Only the prompt compile is TEXT_ONLY (no cast, no regions).
 *
 * Tests (serial):
 *   1. [REST + reader] an unrendered scene: the route answers 409 "render it first" (its RENDER_PLAN node is
 *      materialized on that first use, with no selected base); the reader draws no Regenerate-background button
 *      on that page.
 *   2. [SD, browser] scene 0 rendered once over REST (one attempt; the effective preset's `variants` .39 renders —
 *      2 under STANDARD); the reader shows the image and the
 *      button; clicking it POSTs regenerate-background → 202 {jobId, runObjectId, kind pb.regenerateBackground,
 *      planned [render_plan@…], regenerate 'background'}; the run progress is watched to terminal in the reader
 *      (1 live .39 render, variants forced to 1); toast "Render complete: 1 rendered"; GET /job/{id} carries the regenerate keys on the
 *      node outcome (regenerate, regeneratedFromArtifactObjectId = the first base, variantsPlanned/Rendered 1,
 *      BACKGROUND_REGEN_NO_REGIONS degradation, not auto-approved); the page view now shows the new base
 *      (selected, unapproved, supersedes the first); the base chain = the first attempt's variants then exactly
 *      one regenerated base; both PNGs fetched and written.
 *   3. [REST + reader] approve the new base (pins the node): the route answers 409 "is pinned"; in the reader the
 *      button shows the "Scene is pinned" confirm whose primary is "Unpin and regenerate"; Cancel sends no pin
 *      call and the node stays pinned.
 *
 * Gate: PB_SD_TESTS=1 (two live .39 attempts, 3 renders under STANDARD), --workers=1. A skip is a skip, never a pass. Timings are logged,
 * never asserted. Run:
 *   PB_SD_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookV3RegenBackground.spec.js --workers=1 --project=chromium --retries=0
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { ensureBookStyleDescription, openPageInEdit, openActivityDock } from './helpers/pbReader.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/i8-regen-background';
const TAG = '[i8-regen]';
const SETTING = 'a lone traveller in a hooded cloak on a windswept basalt headland at dusk, the sea grey below';
const ACTION = 'the traveller plants a walking staff and looks out over the water';
const MOOD = 'quiet, resolute';
const NO_PROGRESS_MS = 600000;

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }
/**
 * A rendered-but-never-approved artifact is GENERATED, or IN_REVIEW when the visual-QA hook (220 g) flagged it —
 * on the live stack the VLM judge runs and a WARN/FAIL flags the image IN_REVIEW (PbArtifactUtil.flagForReview);
 * offline no judge runs and it stays GENERATED. The claim this spec makes is "never APPROVED, never auto-approved",
 * so both are accepted; APPROVED / REJECTED / ARCHIVED are not.
 */
const UNAPPROVED = ['GENERATED', 'IN_REVIEW'];
function shortRef(oid) { return String(oid).replace(/-/g, '').slice(0, 8); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// The only credential source: what ensureSharedTestUser() returns (set in beforeAll), never a literal.
let shared = null;

function credentialFor(who) {
    if (!who) throw new Error('login before ensureSharedTestUser() — the beforeAll must set the shared user first');
    return { schema: 'auth.credential', organizationPath: '/Development', name: who.testUserName, credential: b64(who.testPassword), type: 'hashed_password' };
}

async function login(request) {
    const resp = await request.post(REST + '/login', { data: credentialFor(shared) });
    expect(resp.ok() || resp.status() === 204, 'login as ' + shared.testUserName + ': HTTP ' + resp.status()).toBe(true);
}

/** Browser login as the shared user; the WebSocket is stubbed because the Docker nginx proxy drops the session
 *  cookie on the WS upgrade and pageClient would force a re-login. */
async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', { data: credentialFor(shared) });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login as ' + shared.testUserName + ' failed: HTTP ' + resp.status());
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1; window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(() => window.location.hash.includes('/main') && document.querySelector('[role="main"]'), { timeout: 30000 });
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
    console.log(TAG + '-png ' + fileName + ' ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) role=' + art.role
        + ' revision=' + art.revision + ' selected=' + art.selected + ' supersedes=' + art.supersedesObjectId
        + ' seed=' + art.seed + ' durationMs=' + art.durationMs + ' reviewStatus=' + art.reviewStatus + ' -> ' + pngPath);
    return { art, dims, pngPath, raw };
}

/** POST /scene/{id}/regenerate-background → {status, text, dto|null}. */
async function postRegen(request, bookOid, sceneOid) {
    const resp = await request.post(PB_ROOT + '/' + bookOid + '/scene/' + sceneOid + '/regenerate-background', { data: {}, timeout: 120000 });
    const text = await resp.text();
    let dto = null;
    try { dto = JSON.parse(text); } catch (e) { /* non-JSON body is asserted by the caller */ }
    return { status: resp.status(), text, dto };
}

function errorOf(text) {
    try { const j = JSON.parse(text); return j.error || j.message || text; } catch (e) { return text; }
}

/** GET /workflow/node/{id}: nodeSummary + per-role ascending revision chains (`artifacts`). */
async function nodeView(request, PB, nodeOid) {
    const resp = await request.get(PB + '/workflow/node/' + nodeOid);
    const text = await resp.text();
    expect(resp.ok(), 'GET /workflow/node/' + nodeOid + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

async function pageOf(request, PB, sceneOid) {
    const pages = await (await request.get(PB + '/pages')).json();
    const pg = pages.find(p => p.objectId === sceneOid);
    expect(pg, 'GET /pages lists scene ' + sceneOid + ': ' + JSON.stringify(pages).slice(0, 300)).toBeTruthy();
    return pg;
}

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
    return job;
}

async function seedScene(request, book, bookRef, slug, index, extra) {
    const resp = await request.post(REST + '/model', {
        data: Object.assign({ schema: 'olio.pb.scene', name: 'Scene ' + slug + ' ' + index, groupId: book.groupId, groupPath: book.groupPath,
            book: bookRef, sceneIndex: index }, extra)
    });
    const text = await resp.text();
    expect(resp.ok(), 'create olio.pb.scene ' + index + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    const scene = JSON.parse(text);
    expect(scene && scene.id && scene.objectId, 'scene identity').toBeTruthy();
    return { id: scene.id, objectId: scene.objectId, index };
}

/** Reader helpers (the testids the reader renders — pictureBook.js renderPb2V3SceneControls). */
async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
}
async function openFirstPage(page) {
    await page.locator('button', { hasText: 'Begin' }).click();
    await expect(page.getByText(/^Page 1 of \d+$/).first()).toBeVisible();
}
function nextPageButton(page) {
    return page.locator('button:has(> span.material-symbols-outlined:text-is("chevron_right"))').first();
}

/** Toasts expire after 5 s (pageClient.js); record every `.toast-text` as it appears (idempotent per page). */
async function captureToasts(page) {
    await page.evaluate(() => {
        if (window.__i8Toasts) return;
        window.__i8Toasts = [];
        const grab = () => document.querySelectorAll('.toast-text').forEach(el => {
            const t = (el.textContent || '').trim();
            if (t && !window.__i8Toasts.includes(t)) window.__i8Toasts.push(t);
        });
        new MutationObserver(grab).observe(document.body, { childList: true, subtree: true, characterData: true });
        grab();
    });
}
function capturedToasts(page) {
    return page.evaluate(() => window.__i8Toasts || []);
}

// Seeded in beforeAll. The fixture rows are left in place as the record of the run (nodes/artifacts reference
// them by FK); afterAll records what was created.
let bookObjectId = null;
let slug = null;
const fx = { book: null, scene: null, emptyScene: null, handle: null, emptyHandle: null };
const first = { nodeObjectId: null, artifactObjectId: null, runObjectId: null, seed: null };
const regen = { jobId: null, runObjectId: null, artifactObjectId: null, seed: null };

test.describe('PictureBook V3 §24 I-8 — background-only regenerate (reader button + route)', () => {
    // Serial: every test needs the state the previous one produced. 15 minutes per test: at most one .39 render
    // each plus REST round trips. Never a threshold.
    test.describe.configure({ mode: 'serial', timeout: 900000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        if (!SD_ENABLED) return;
        shared = await ensureSharedTestUser(request);
        await login(request);
        const ts = Date.now().toString(36);

        slug = 'i8-regen-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'I-8 Regenerate Background ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(createText);
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded STORY book objectId: ' + createText.slice(0, 300)).toBeTruthy();
        // Scene renders require a described book style (409 otherwise, since 2026-11-16).
        await ensureBookStyleDescription(request, PB_ROOT, bookObjectId);

        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId', 'bookType']);
        expect(book && book.id && book.groupId && book.groupPath, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        expect(upper(book.bookType), 'seeded book is STORY').toBe('STORY');
        fx.book = { id: book.id, objectId: book.objectId, name: book.name, slug: book.slug, groupId: book.groupId, groupPath: book.groupPath };
        const bookRef = { schema: 'olio.pb.book', id: book.id, objectId: book.objectId };

        // Scene 0: setting/action/mood prose, no sdPrompt — the TEXT_ONLY compile joins the three (the lazy compose still runs the LLM once).
        fx.scene = await seedScene(request, fx.book, bookRef, slug, 0, { title: 'The headland at dusk', setting: SETTING, action: ACTION, mood: MOOD });
        // Scene 1: never rendered — test 1's 409 and the page with no button.
        fx.emptyScene = await seedScene(request, fx.book, bookRef, slug, 1, { title: 'Never rendered' });
        fx.handle = 'render_plan@' + shortRef(fx.scene.objectId);
        fx.emptyHandle = 'render_plan@' + shortRef(fx.emptyScene.objectId);

        const pg0 = await pageOf(request, PB_ROOT + '/' + bookObjectId, fx.scene.objectId);
        expect(pg0.sceneNodeObjectId == null && pg0.artifactObjectId == null, 'no node, no image yet: ' + JSON.stringify(pg0)).toBe(true);
        console.log(TAG + '-seed ' + JSON.stringify({ book: bookObjectId, slug, ...fx }));
        await request.get(REST + '/logout');
    });

    test.afterAll(async ({ request }) => {
        if (!SD_ENABLED || !bookObjectId) return;
        console.log(TAG + '-cleanup left in place (nodes + artifacts reference them): ' + JSON.stringify({ book: bookObjectId, slug, ...fx, first, regen }));
    });

    test('[SD] 1: an unrendered scene — the route answers 409 "render it first" (its RENDER_PLAN node is materialized, no selected base), and the reader draws no Regenerate-background button on that page', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const r = await postRegen(request, bookObjectId, fx.emptyScene.objectId);
        console.log(TAG + '-1 regenerate on the unrendered scene: HTTP ' + r.status + ' ' + r.text.slice(0, 400));
        expect(r.status, 'no selected base to regenerate from: ' + r.text.slice(0, 300)).toBe(409);
        expect(errorOf(r.text)).toBe('Scene has no selected render to regenerate the background of; render it first');

        // The refusal came AFTER the scene's RENDER_PLAN node was materialized on first use (sceneTerminal), so the
        // page now names a node but still no artifact; and the same request repeats the same 409 (no job, no run).
        const pg1 = await pageOf(request, PB, fx.emptyScene.objectId);
        expect(pg1.sceneNodeObjectId, 'the RENDER_PLAN node exists after the refused request: ' + JSON.stringify(pg1)).toBeTruthy();
        expect(pg1.artifactObjectId == null, 'still no image: ' + JSON.stringify(pg1)).toBe(true);
        const view1 = await nodeView(request, PB, pg1.sceneNodeObjectId);
        expect(upper(view1.nodeType), 'scene terminal node type').toBe('RENDER_PLAN');
        expect(view1.handle).toBe(fx.emptyHandle);
        const again = await postRegen(request, bookObjectId, fx.emptyScene.objectId);
        expect(again.status, 'repeat is the same 409: ' + again.text.slice(0, 300)).toBe(409);
        await request.get(REST + '/logout');

        // Reader: page 2 (the unrendered scene) has no image; its Edit › Scenes › Render tab (where the per-page
        // controls moved, design §5.6) has Render but no Regenerate background.
        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        await nextPageButton(page).click();
        await expect(page.getByText(/^Page 2 of \d+$/).first()).toBeVisible();
        await expect(page.getByTestId('pb2-scene-image')).toHaveCount(0);
        await openPageInEdit(page, 'render');
        await expect(page.getByTestId('pb2-scene-render')).toBeVisible();
        await expect(page.getByTestId('pb-render-tab-image')).toHaveCount(0);
        await expect(page.getByTestId('pb2-scene-regen-background')).toHaveCount(0);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/1-unrendered-page-no-button.png', fullPage: true });
    });

    test('[SD] 2: scene 0 rendered once; the reader button regenerates the background → 202 job watched to terminal; the outcome names what it regenerated from; the page shows the new base (selected, superseding the first); both PNGs fetched', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        // First render (synchronous route; one .39 render; the §9.1 lazy compose runs the LLM first, then the VLM QA judge).
        const t0 = Date.now();
        const rp = await request.post(PB + '/scene/' + fx.scene.objectId + '/render-plan', { timeout: 900000 });
        const rpText = await rp.text();
        console.log(TAG + '-2-first HTTP ' + rp.status() + ' in ' + (Date.now() - t0) + 'ms (logged, never asserted): ' + rpText.slice(0, 800));
        expect(rp.status(), 'POST /scene/{id}/render-plan: ' + rpText.slice(0, 600)).toBe(200);
        const firstDto = JSON.parse(rpText);
        expect(firstDto.executed).toBe(true);
        expect(firstDto.handle).toBe(fx.handle);
        expect(firstDto.strategy, 'a text-only scene: no actor passes').toBe('TEXT_ONLY');
        expect(firstDto.artifactObjectId, 'first base').toBeTruthy();
        expect(firstDto.nodeObjectId).toBeTruthy();
        expect(firstDto.regenerate, 'a plain render carries no regenerate key').toBeUndefined();
        first.nodeObjectId = firstDto.nodeObjectId;
        first.artifactObjectId = firstDto.artifactObjectId;
        first.runObjectId = firstDto.runObjectId;
        const firstPng = await savePng(request, PB, first.artifactObjectId, '2-first-' + slug + '.png');
        first.seed = firstPng.art.seed;
        expect(firstPng.art.selected, 'the first base is the selection').toBe(true);
        const pgFirst = await pageOf(request, PB, fx.scene.objectId);
        expect(pgFirst.artifactObjectId).toBe(first.artifactObjectId);
        expect(pgFirst.sceneNodeObjectId).toBe(first.nodeObjectId);
        expect(pgFirst.pinned).toBe(false);
        await request.get(REST + '/logout');

        // The reader: page 1 shows the image, chip Review, and the Regenerate-background button.
        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        await captureToasts(page);
        const img = page.getByTestId('pb2-scene-image');
        await expect(img).toBeVisible();
        await expect.poll(async () => img.evaluate(el => el.complete && el.naturalWidth > 0), { timeout: 60000, message: 'first scene image decoded' }).toBe(true);
        const readFirstSrc = await img.getAttribute('src');
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');
        // The Regenerate-background button moved to Edit › Scenes › this scene › Render (design §5.6); that tab shows
        // the same scene image.
        await openPageInEdit(page, 'render');
        const editImg = page.getByTestId('pb-render-tab-image');
        await expect(editImg).toBeVisible();
        const firstSrc = await editImg.getAttribute('src');
        const regenBtn = page.getByTestId('pb2-scene-regen-background');
        await expect(regenBtn).toBeVisible();
        await expect(regenBtn).toBeEnabled();
        await expect(regenBtn).toHaveText(/Regenerate background$/);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/2-reader-before-regen.png', fullPage: true });

        // Click: POST …/scene/{id}/regenerate-background → 202 submit DTO.
        const isRegen = r => r.request().method() === 'POST' && r.url().includes('/scene/' + fx.scene.objectId + '/regenerate-background');
        const t1 = Date.now();
        const submitted = page.waitForResponse(isRegen, { timeout: 120000 });
        await regenBtn.click();
        const submitResp = await submitted;
        const submitText = await submitResp.text();
        console.log(TAG + '-2-submit ' + submitResp.url() + ' HTTP ' + submitResp.status() + ' ' + submitText.slice(0, 800));
        expect(submitResp.status(), 'regenerate-background accepted: ' + submitText.slice(0, 400)).toBe(202);
        const sub = JSON.parse(submitText);
        expect(sub.sceneObjectId).toBe(fx.scene.objectId);
        expect(sub.sceneIndex).toBe(0);
        expect(sub.jobId, 'jobId').toBeTruthy();
        expect(sub.runObjectId, 'runObjectId').toBeTruthy();
        expect(sub.runObjectId, 'a new run, not the first render\'s').not.toBe(first.runObjectId);
        expect(sub.kind).toBe('pb.regenerateBackground');
        expect(sub.regenerate).toBe('background');
        expect(sub.planned, 'exactly the scene\'s RENDER_PLAN node is planned').toEqual([fx.handle]);
        expect(sub.plannedNodeCount).toBe(1);
        regen.jobId = sub.jobId;
        regen.runObjectId = sub.runObjectId;

        // Watched to terminal (one .39 render) in the workspace's activity dock (§4.5); the summary toast; the page
        // refreshes with the new image.
        await openActivityDock(page);
        const progress = page.getByTestId('pb2-run-progress');
        await expect(progress).toBeVisible();
        await expect(page.getByTestId('pb2-run-stage')).toHaveCount(1);
        await expect(page.getByTestId('pb2-run-stage')).toHaveText(new RegExp(fx.handle));
        await expect(regenBtn).toBeDisabled();
        await expect(progress).toHaveAttribute('data-run-terminal', '1', { timeout: 600000 });
        console.log(TAG + '-2-terminal in the reader after ' + (Date.now() - t1) + 'ms: ' + await page.getByTestId('pb2-run-counter').textContent());
        await expect(page.getByTestId('pb2-run-stage')).toHaveAttribute('data-stage-state', 'done');
        await expect(page.getByTestId('pb2-run-cancel')).toHaveCount(0);
        await expect(page.getByTestId('pb2-run-dismiss')).toBeVisible();
        await expect.poll(() => capturedToasts(page), { timeout: 30000, message: 'the run summary toast' }).toContain('Render complete: 1 rendered');
        await expect(editImg).toBeVisible();
        await expect.poll(async () => editImg.getAttribute('src'), { timeout: 60000, message: 'the page shows a different image after the regenerate' }).not.toBe(firstSrc);
        await expect(regenBtn).toBeEnabled();
        // …and Read shows it on page 1 (Edit → Read keeps the page, §4.3).
        await page.getByTestId('book-mode-read').click();
        await expect(img).toBeVisible({ timeout: 30000 });
        await expect.poll(async () => img.getAttribute('src'), { timeout: 60000, message: 'Read shows the regenerated image' }).not.toBe(readFirstSrc);
        await expect.poll(async () => img.evaluate(el => el.complete && el.naturalWidth > 0), { timeout: 60000, message: 'regenerated scene image decoded' }).toBe(true);
        await expect(page.getByTestId('pb2-scene-chip')).toHaveAttribute('data-chip', 'review');
        await expect(page.getByTestId('pb2-stale-badge')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/2-reader-after-regen.png', fullPage: true });
        console.log(TAG + '-2-toasts ' + JSON.stringify(await capturedToasts(page)));

        // The job's node outcome carries the regenerate keys.
        await login(request);
        const job = await pollJob(request, regen.jobId, 'regenerate');
        expect(job.status, 'job: ' + (job.error || JSON.stringify(job).slice(0, 300))).toBe('completed');
        const result = job.result;
        expect(result && upper(result.runStatus), 'run status').toBe('COMPLETED');
        expect(result.executedNodeCount).toBe(1);
        expect(result.failedNodeCount).toBe(0);
        expect(result.runObjectId).toBe(regen.runObjectId);
        expect(Array.isArray(result.nodes) && result.nodes.length === 1, 'one node outcome: ' + JSON.stringify(result.nodes)).toBe(true);
        const outcome = result.nodes[0];
        expect(outcome.nodeObjectId).toBe(first.nodeObjectId);
        expect(outcome.handle).toBe(fx.handle);
        expect(outcome.regenerate, 'outcome.regenerate').toBe('background');
        expect(outcome.regeneratedFromArtifactObjectId, 'regenerated from the first base').toBe(first.artifactObjectId);
        expect(outcome.variantsPlanned, 'a regenerate is always one variant').toBe(1);
        expect(outcome.variantsRendered).toBe(1);
        expect(outcome.artifactObjectId, 'the regenerated base').toBeTruthy();
        expect(outcome.artifactObjectId).not.toBe(first.artifactObjectId);
        expect(outcome.autoApproved, 'never auto-approved: the degradation vetoes it').toBeFalsy();
        const degradations = outcome.degradations || [];
        expect(degradations.some(d => String(d).startsWith('BACKGROUND_REGEN_NO_REGIONS: ')), 'a text-only scene has no ACTOR_REGION pass to keep: ' + JSON.stringify(degradations)).toBe(true);
        // JSONUtil.exportObject is Include.NON_EMPTY: the empty actor-seed lists are absent on the wire (absent-or-empty).
        expect(outcome.actorSeedsReused || [], 'no actors on a text-only scene').toEqual([]);
        expect(outcome.actorSeedsNew || []).toEqual([]);
        regen.artifactObjectId = outcome.artifactObjectId;

        // Durable state: the page follows the new selection; the base chain is the first attempt's variants (the
        // effective preset decides how many — 2 under STANDARD; variant 0 is `first`, its siblings sit unselected
        // behind it, §23) followed by exactly ONE regenerated base (the regenerate forces variants=1); the new base
        // supersedes the first; the first is deselected (never approved, so rule 1 lets the regenerate take the
        // selection); node not pinned.
        const pg = await pageOf(request, PB, fx.scene.objectId);
        expect(pg.artifactObjectId, 'page view names the regenerated base').toBe(regen.artifactObjectId);
        expect(UNAPPROVED, 'the regenerated base is not approved (GENERATED, or IN_REVIEW after a QA flag): ' + pg.reviewStatus).toContain(upper(pg.reviewStatus));
        expect(pg.pinned).toBe(false);
        const view = await nodeView(request, PB, first.nodeObjectId);
        expect(Boolean(view.pinned)).toBe(false);
        const baseEntries = (view.artifacts && view.artifacts.base) || [];
        const baseChain = baseEntries.map(a => a.objectId);
        console.log(TAG + '-2-base-chain ' + JSON.stringify(baseEntries.map(a => ({ objectId: a.objectId, revision: a.revision, attempt: a.attempt, variantIndex: a.variantIndex, selected: a.selected, reviewStatus: a.reviewStatus }))));
        expect(baseChain.length, 'at least the first base and the regenerated one').toBeGreaterThanOrEqual(2);
        expect(baseChain[0], 'the chain starts at the first base (variant 0 of the first attempt)').toBe(first.artifactObjectId);
        expect(baseChain[baseChain.length - 1], 'the chain ends at the regenerated base').toBe(regen.artifactObjectId);
        const firstAttempt = baseEntries[0].attempt;
        const regenEntry = baseEntries[baseEntries.length - 1];
        expect(regenEntry.attempt, 'the regenerate is its own attempt').not.toBe(firstAttempt);
        expect(baseEntries.filter(a => a.attempt === regenEntry.attempt).length, 'the regenerate rendered exactly one base (variants forced to 1)').toBe(1);
        for (const sib of baseEntries.slice(1, -1)) {
            expect(sib.attempt, 'every base between them is a sibling variant of the first attempt').toBe(firstAttempt);
            expect(sib.variantIndex).toBeGreaterThan(0);
            expect(sib.selected, 'sibling variants never hold the selection').toBeFalsy();
        }
        const regenPng = await savePng(request, PB, regen.artifactObjectId, '2-regen-' + slug + '.png');
        regen.seed = regenPng.art.seed;
        expect(regenPng.art.selected).toBe(true);
        expect(regenPng.art.supersedesObjectId, 'supersedes the first base').toBe(first.artifactObjectId);
        expect(UNAPPROVED, 'artifact record not approved: ' + regenPng.art.reviewStatus).toContain(upper(regenPng.art.reviewStatus));
        expect(regenPng.art.seed, 'a fresh background seed').not.toBe(first.seed);
        expect(regenPng.raw.equals(firstPng.raw), 'different bytes').toBe(false);
        const firstAfter = await (await request.get(PB + '/artifact/' + first.artifactObjectId)).json();
        expect(firstAfter.selected, 'the first base is no longer the selection').toBe(false);
        expect(UNAPPROVED, 'the first base stays unapproved (never approved, never auto-approved): ' + firstAfter.reviewStatus).toContain(upper(firstAfter.reviewStatus));
        await request.get(REST + '/logout');
    });

    test('[SD] 3: approving the regenerated base pins the node — the route answers 409 "is pinned"; the reader offers "Unpin and regenerate" and Cancel sends no pin call', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — a skip, not a pass');
        expect(regen.artifactObjectId, 'test 2 produced the regenerated base').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const ap = await request.put(PB + '/artifact/' + regen.artifactObjectId + '/approve', { data: {} });
        const apText = await ap.text();
        console.log(TAG + '-3-approve HTTP ' + ap.status() + ' ' + apText.slice(0, 300));
        expect(ap.ok(), 'approve: ' + apText.slice(0, 300)).toBe(true);
        const pgPinned = await pageOf(request, PB, fx.scene.objectId);
        expect(pgPinned.pinned, 'approve pins the node').toBe(true);
        expect(upper(pgPinned.reviewStatus)).toBe('APPROVED');

        const r = await postRegen(request, bookObjectId, fx.scene.objectId);
        console.log(TAG + '-3 regenerate on the pinned scene: HTTP ' + r.status + ' ' + r.text.slice(0, 300));
        expect(r.status, 'pinned scene refused: ' + r.text.slice(0, 300)).toBe(409);
        const pinnedMsg = errorOf(r.text);
        expect(pinnedMsg).toBe('Node ' + fx.handle + ' is pinned. Unpin it first - pinning means do not replace this artifact.');
        await request.get(REST + '/logout');

        // Reader: the 409 is shown verbatim in the "Scene is pinned" confirm; Cancel never calls the pin route.
        await loginAsSharedUser(page);
        const pinCalls = [];
        page.on('request', req => { if (req.method() === 'POST' && /\/node\/[^/]+\/pin$/.test(req.url())) pinCalls.push(req.url()); });
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        await openPageInEdit(page, 'render');
        const regenBtn = page.getByTestId('pb2-scene-regen-background');
        await expect(regenBtn).toBeVisible();
        await expect(regenBtn).toBeEnabled();
        const isRegen = res => res.request().method() === 'POST' && res.url().includes('/scene/' + fx.scene.objectId + '/regenerate-background');
        const refused = page.waitForResponse(isRegen, { timeout: 60000 });
        await regenBtn.click();
        const refusedResp = await refused;
        const refusedText = await refusedResp.text();
        console.log(TAG + '-3-reader ' + refusedResp.url() + ' HTTP ' + refusedResp.status() + ' ' + refusedText.slice(0, 300));
        expect(refusedResp.status()).toBe(409);
        const dialog = page.locator('.am7-dialog');
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog.locator('.am7-dialog-title')).toHaveText('Scene is pinned');
        await expect(dialog.locator('.am7-dialog-body')).toContainText(pinnedMsg);
        await expect(dialog.locator('.am7-dialog-body')).toContainText('Unpin this scene\'s node and regenerate its background?');
        await expect(dialog.locator('.am7-dialog-footer button.am7-dialog-btn-primary')).toHaveText(/Unpin and regenerate$/);
        mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: OUT_DIR + '/3-reader-pinned-confirm.png', fullPage: true });
        await dialog.locator('.am7-dialog-footer button.am7-dialog-btn', { hasText: 'Cancel' }).click();
        await expect(page.locator('.am7-dialog')).toHaveCount(0);
        await expect(regenBtn).toBeEnabled({ timeout: 10000 });
        expect(pinCalls, 'declining must not call the pin route').toEqual([]);

        await login(request);
        const pgAfter = await pageOf(request, PB, fx.scene.objectId);
        expect(pgAfter.pinned, 'still pinned after decline').toBe(true);
        expect(pgAfter.artifactObjectId, 'the approved image is untouched').toBe(regen.artifactObjectId);
        await request.get(REST + '/logout');
    });
});
