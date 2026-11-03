/**
 * PictureBook V3 P9-4 — cover art: the cover board (/picture-book/{book}/cover), the title-safe band, the cover
 * routes and the reader's cover (design §40), live on the Docker stack as e2etest_shared.
 *
 * What this spec proves:
 *   1. [no SD needed] a fresh book: GET /{book}/covers lists every cover purpose as null; the cover board opens
 *      in its empty state, "Create cover…" opens the Create dialog, Manual creates an empty USER-source composition
 *      (strip "Not rendered yet", purpose tab marked as existing); GET /{book}/covers/cover agrees.
 *   2. [no SD needed] the camera section saves setting / action / mood and the title-safe band (PATCH
 *      /{book}/covers/cover accepts titleSafeBand); the strip shows "band bottom"; a direct POST
 *      /{book}/covers/cover/plan reports the reserved bottom-quarter band {x:0,y:0.75,w:1,h:0.25}, a direct PATCH
 *      to `none` clears it, and back to `bottom` restores it; the scene board links to the covers; the reader
 *      shows no cover yet (nothing approved).
 *   3. [SD] (PB_SD_TESTS=1 and the renderer inventory available) Render → the job is followed to terminal →
 *      the latest render appears in the strip (its PNG bytes are fetched through the media servlet and written
 *      to OUT_DIR); Approve latest → the approved tile; GET /covers carries it; the reader shows the approved
 *      cover ([data-pb2-cover-source="cover"]) with its title block in the BOTTOM band.
 *   4. [SD] (same gate) the From-scene creation mode and a second purpose, live: on the TITLE_PAGE tab the Create
 *      dialog's "From a scene" picks scene 0 and creates a CLONE-source composition (PbCoverUtil.cloneFromScene:
 *      setting / action / mood / camera copied from the scene's SCENE composition, revision 0, no band) that is a
 *      separate composition from the COVER one and from the scene's own; the repeat POST is idempotent
 *      (existingReturned); Render → job to terminal → Approve latest → GET /covers shows TITLE_PAGE approved
 *      (approved == selected, APPROVED) while the COVER entry is byte-for-byte what it was before; the PNG is saved.
 *      Fixture note: the seed creates a raw olio.pb.scene only, so the test first GETs the scene's composition and,
 *      on 404, seeds the SCENE composition through POST /rest/model as the shared user (the same shape
 *      pictureBookComposition.spec.js uses) — the cover create itself goes through the UI.
 *
 * Run:
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 PB_SD_TESTS=1 \
 *     npx playwright test e2e/pictureBookV3Cover.spec.js --workers=1 --project=chromium --retries=0
 *
 * ensureSharedTestUser only (never admin). Timings are logged, never asserted. Output: test-results/p94-cover/.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/p94-cover';
const SCENE_SETTING = 'a narrow cobbled harbour street at dusk, lanterns being lit along the quay';
const SCENE_ACTION = 'a fisherman coils rope beside an upturned boat while gulls settle on the wall';
const COVER_SETTING = 'a lighthouse on a black headland under a wide violet evening sky, the sea calm below';
const COVER_ACTION = 'the lamp has just been lit and a single small boat rows toward the shore';
const COVER_MOOD = 'hushed, expectant';
const SCENE_MOOD = 'quiet, end-of-day';
const SCENE_CAMERA = 'from the end of the quay, looking back along the lantern line';
const SCENE_PROPS = 'coiled rope, upturned boat, lit lanterns';
const COVER_PURPOSES = ['COVER', 'TITLE_PAGE', 'CHAPTER_OPENER', 'SERIES_ART'];

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }

let shared = null;

function sharedCredential() {
    if (!shared) throw new Error('login before ensureSharedTestUser() — the beforeAll must set the shared user first');
    return { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' };
}

async function login(request) {
    const resp = await request.post(REST + '/login', { data: sharedCredential() });
    expect(resp.ok() || resp.status() === 204, 'login as ' + shared.testUserName + ': HTTP ' + resp.status()).toBe(true);
}

async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', { data: sharedCredential() });
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
    const resp = await request.post(REST + '/model/search', { data: { schema: 'io.query', type, fields, request: requestFields, recordCount: 1, cache: false } });
    expect(resp.ok(), 'search ' + type + ': HTTP ' + resp.status()).toBe(true);
    const body = await resp.json();
    return body && body.results && body.results[0];
}

async function modelsForTask(request, task) {
    const resp = await request.get(REST + '/olio/illustration/models?task=' + encodeURIComponent(task), { timeout: 300000 });
    const text = await resp.text();
    expect(resp.ok(), 'GET /illustration/models?task=' + task + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

/** GET /{book}/covers → the cover view DTO. */
async function getCovers(request, bookOid) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/covers');
    const text = await resp.text();
    expect(resp.ok(), 'GET /covers: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

/** GET /{book}/covers/{purpose} → {status, body}. */
async function getCover(request, bookOid, purpose) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/covers/' + purpose);
    const text = await resp.text();
    return { status: resp.status(), text, body: (resp.ok() ? JSON.parse(text) : null) };
}

/** PATCH /{book}/covers/{purpose} with a field map → the edit result. */
async function patchCover(request, bookOid, purpose, body) {
    const resp = await request.patch(PB_ROOT + '/' + bookOid + '/covers/' + purpose, { data: body });
    const text = await resp.text();
    expect(resp.ok(), 'PATCH /covers/' + purpose + ' ' + JSON.stringify(body) + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

/** POST /{book}/covers/{purpose}/plan → the RenderPlan DTO (a read; the plan is never persisted). */
async function planCover(request, bookOid, purpose, body) {
    const t0 = Date.now();
    const resp = await request.post(PB_ROOT + '/' + bookOid + '/covers/' + purpose + '/plan', { data: body || {}, timeout: 900000 });
    const text = await resp.text();
    expect(resp.ok(), 'POST /covers/' + purpose + '/plan: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    console.log('[p94-cover] POST /covers/' + purpose + '/plan in ' + (Date.now() - t0) + ' ms');
    return JSON.parse(text);
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
    console.log('[p94-png] ' + fileName + ' ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) role=' + art.role
        + ' revision=' + art.revision + ' backend=' + art.backend + ' seed=' + art.seed + ' durationMs=' + art.durationMs
        + ' reviewStatus=' + art.reviewStatus + ' -> ' + pngPath);
    return { art, dims, pngPath, raw };
}

async function openCoverBoard(page, bookOid) {
    await page.goto('/#!/picture-book/' + bookOid + '/cover', { timeout: 30000 });
    await expect(page.getByTestId('pb-cmp-title')).toHaveText('Cover art', { timeout: 30000 });
    await expect(page.getByTestId('pb-cmp-purpose-tabs')).toBeVisible();
}

/**
 * Wait for a toast matching `re`; on timeout the error names every toast text seen meanwhile (toasts expire after
 * 5 s — pageClient.js — so a failed action's error toast would otherwise vanish before the assertion reports).
 */
async function waitForToast(page, re, timeout) {
    const seen = [];
    const deadline = Date.now() + (timeout || 60000);
    while (Date.now() < deadline) {
        const texts = await page.locator('.toast-text').allTextContents();
        for (const t of texts) {
            if (!seen.includes(t)) seen.push(t);
            if (re.test(t)) return t;
        }
        await page.waitForTimeout(250);
    }
    throw new Error('toast ' + re + ' did not appear within ' + (timeout || 60000) + ' ms; toasts seen: ' + JSON.stringify(seen));
}

/** GET /{book}/scene/{scene}/composition → {status, text, body} (404 "Scene N has no composition; compose it first" when none). */
async function getSceneComposition(request, bookOid, sceneOid) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/scene/' + sceneOid + '/composition');
    const text = await resp.text();
    return { status: resp.status(), text, body: (resp.ok() ? JSON.parse(text) : null) };
}

/**
 * The scene's SCENE-purpose composition, seeding one when the scene has none. The beforeAll creates a raw
 * olio.pb.scene only and no read path lazily composes it, so From-scene would 404 "Scene 0 has no composition to
 * copy" (PbCoverUtil.cloneFromScene). Seeded as the shared user through the generic model route — the proven shape
 * from pictureBookComposition.spec.js — never through the cover routes: the cover create is the UI's job.
 */
async function ensureSceneComposition(request, bookOid, sceneRef, bookRef) {
    let got = await getSceneComposition(request, bookOid, sceneRef.objectId);
    if (got.status === 404) {
        console.log('[p94-cover-4] scene ' + sceneRef.sceneIndex + ' has no composition (' + got.text.slice(0, 160) + ') — seeding a SCENE composition');
        const resp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.pb.composition', name: 'composition ' + bookRef.slug + ' 0', groupId: bookRef.groupId, groupPath: bookRef.groupPath,
                book: { schema: 'olio.pb.book', id: bookRef.id, objectId: bookRef.objectId },
                scene: { schema: 'olio.pb.scene', id: sceneRef.id, objectId: sceneRef.objectId },
                purpose: 'scene', source: 'user', settingText: SCENE_SETTING, action: SCENE_ACTION, mood: SCENE_MOOD,
                shotType: 'wide', viewpoint: 'eye_level', cameraText: SCENE_CAMERA, props: SCENE_PROPS, revision: 1
            }
        });
        const text = await resp.text();
        expect(resp.ok(), 'POST /model olio.pb.composition (scene seed): HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
        got = await getSceneComposition(request, bookOid, sceneRef.objectId);
    }
    expect(got.status, 'GET /scene/' + sceneRef.objectId + '/composition: ' + got.text.slice(0, 300)).toBe(200);
    expect(got.body.compositionObjectId, 'scene composition objectId').toBeTruthy();
    expect(upper(got.body.composition.purpose)).toBe('SCENE');
    return got.body;
}

async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
    // The reader reads /pages, /health and /covers together before it draws the cover; wait for the covers read.
    await expect(page.locator('[data-pb2-cover-source]')).toHaveCount(1, { timeout: 30000 });
}

let bookObjectId = null;
let slug = null;
const fx = { book: null, scene: null, inventoryUnavailable: null, inventoryError: null, compositionObjectId: null, latestArtifactOid: null, pngPath: null, planVariants: null,
    // test 4: the scene's SCENE composition (seeded or found), the TITLE_PAGE clone and its approved artifact / PNG
    titlePage: { sceneCompositionObjectId: null, sceneCompositionSeeded: null, compositionObjectId: null, planVariants: null, latestArtifactOid: null, autoApproved: null, pngPath: null } };

test.describe('PictureBook V3 P9-4 — cover board, title-safe band, cover routes, reader cover', () => {
    // Generous budgets on purpose: POST /plan resolves models against the renderer inventory (slow while that server
    // is unreachable) and test 3 waits for a real SD render. Timings are logged, never asserted.
    test.describe.configure({ mode: 'serial', timeout: 2400000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await login(request);
        const ts = Date.now().toString(36);
        slug = 'p94-cover-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'P9-4 Cover ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(createText);
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded STORY book objectId: ' + createText.slice(0, 300)).toBeTruthy();

        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId', 'bookType']);
        expect(book && book.id && book.groupId && book.groupPath, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        fx.book = { id: book.id, objectId: book.objectId, name: book.name, slug: book.slug, groupId: book.groupId, groupPath: book.groupPath };

        const sceneResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.scene', name: 'Scene ' + slug + ' 0', groupId: book.groupId, groupPath: book.groupPath,
                book: { schema: 'olio.pb.book', id: book.id, objectId: book.objectId }, sceneIndex: 0,
                title: 'Lanterns on the quay', setting: SCENE_SETTING, action: SCENE_ACTION, mood: SCENE_MOOD }
        });
        const sceneText = await sceneResp.text();
        expect(sceneResp.ok(), 'create olio.pb.scene: HTTP ' + sceneResp.status() + ' ' + sceneText.slice(0, 300)).toBe(true);
        const scene = JSON.parse(sceneText);
        expect(scene && scene.objectId, 'scene identity').toBeTruthy();
        fx.scene = { id: scene.id, objectId: scene.objectId, sceneIndex: 0 };

        const inv = await modelsForTask(request, 'sceneDirect');
        fx.inventoryUnavailable = !!(inv.inventory && inv.inventory.error);
        fx.inventoryError = inv.inventory ? inv.inventory.error : null;
        console.log('[p94-cover-seed] ' + JSON.stringify({ book: bookObjectId, slug, scene: fx.scene, inventory: inv.inventory, deploymentDefault: inv.deploymentDefault, SD_ENABLED }));
        mkdirSync(OUT_DIR, { recursive: true });
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        if (!bookObjectId) return;
        console.log('[p94-cover-cleanup] left in place as the record of the run: ' + JSON.stringify({ book: bookObjectId, slug, ...fx }));
    });

    test('1. a fresh book has no covers; the board\'s empty state creates a Manual-mode (USER-source) cover through the Create dialog', async ({ page, request }) => {
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        await login(request);
        const fresh = await getCovers(request, bookObjectId);
        console.log('[p94-cover-1] fresh GET /covers: ' + JSON.stringify(fresh));
        expect(fresh.bookObjectId).toBe(bookObjectId);
        expect(fresh.workflowObjectId == null, 'a never-rendered book has no workflow').toBe(true);
        // The wire form is NON_EMPTY (JSONUtil): a null workflowObjectId and an all-null covers map are omitted
        // entirely, so a fresh book answers {bookObjectId} alone; normalizeCoverView reads that as every purpose null.
        const freshCovers = fresh.covers || {};
        for (const p of COVER_PURPOSES) expect(freshCovers[p] == null, 'covers.' + p + ' is null on a fresh book').toBe(true);
        const missing = await getCover(request, bookObjectId, 'cover');
        expect(missing.status, 'GET /covers/cover before creation: ' + missing.text.slice(0, 200)).toBe(404);
        expect(missing.text).toMatch(/has no COVER composition/i);
        await request.get(REST + '/logout');

        await loginAsSharedUser(page);
        await openCoverBoard(page, bookObjectId);
        const tab = page.getByTestId('pb-cmp-purpose-cover');
        await expect(tab).toHaveAttribute('data-active', 'true');
        await expect(tab).toHaveAttribute('data-exists', 'false');
        await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-empty-message')).toContainText(/no cover composition/i);
        await expect(page.getByTestId('pb-cmp-cover')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/01-empty.png', fullPage: true });

        await page.getByTestId('pb-cmp-create-cover-open').click();
        const dialog = page.getByTestId('pb-cmp-create-cover');
        await expect(dialog).toBeVisible();
        await expect(page.getByTestId('pb-cmp-create-auto')).toBeChecked();
        // The book has one page, so From a scene is offered; pick it to see the scene select, then settle on Manual.
        await expect(page.getByTestId('pb-cmp-create-from-scene')).toBeEnabled();
        await page.getByTestId('pb-cmp-create-from-scene').check();
        await expect(page.getByTestId('pb-cmp-create-scene')).toBeVisible();
        await expect(page.getByTestId('pb-cmp-create-scene').locator('option')).toHaveCount(1);
        await page.getByTestId('pb-cmp-create-manual').check();
        await expect(page.getByTestId('pb-cmp-create-scene')).toHaveCount(0);
        const t0 = Date.now();
        await page.getByTestId('pb-cmp-create-submit').click();
        await expect(page.locator('.toast-text', { hasText: /^Cover created/ })).toBeVisible({ timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-cover')).toBeVisible({ timeout: 30000 });
        console.log('[p94-cover-1] manual cover created through the dialog in ' + (Date.now() - t0) + ' ms');
        await expect(page.getByTestId('pb-cmp-empty')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('Not rendered yet');
        await expect(page.getByTestId('pb-cmp-cover-band')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-cover-approved')).toContainText('none');
        await expect(page.getByTestId('pb-cmp-cover-latest')).toContainText('none');
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
        await expect(page.getByTestId('pb-cmp-render-cover')).toBeEnabled();
        // Manual mode creates a USER-source composition (PbCoverUtil.createCover(..., USER, ...)); the
        // PbCompositionSourceEnumType has no MANUAL value, so the chip reads 'user'.
        await expect(page.getByTestId('pb-cmp-source')).toHaveText(/user/i);
        await expect(page.getByTestId('pb-cmp-purpose')).toHaveText(/cover/i);
        await expect(tab).toHaveAttribute('data-exists', 'true');
        await expect(page.getByTestId('pb-cmp-purpose-title-page')).toHaveAttribute('data-exists', 'false');
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible();
        await expect(page.getByTestId('pb-cmp-title-band')).toBeVisible();
        await page.screenshot({ path: OUT_DIR + '/02-created.png', fullPage: true });

        await login(request);
        const board = await getCover(request, bookObjectId, 'cover');
        expect(board.status, 'GET /covers/cover after creation: ' + board.text.slice(0, 300)).toBe(200);
        console.log('[p94-cover-1] GET /covers/cover: ' + JSON.stringify(board.body).slice(0, 600));
        expect(upper(board.body.purpose)).toBe('COVER');
        expect(upper(board.body.composition.source)).toBe('USER');
        expect(board.body.composition.objectId).toBe(board.body.compositionObjectId);
        expect(board.body.cover && board.body.cover.nodeObjectId == null, 'no render node yet').toBe(true);
        // NON_EMPTY wire form again: empty actor / element lists are omitted, not sent as [].
        expect(board.body.actors || []).toEqual([]);
        expect(board.body.elements || []).toEqual([]);
        fx.compositionObjectId = board.body.compositionObjectId;
        const view = await getCovers(request, bookObjectId);
        expect(view.covers.COVER && view.covers.COVER.compositionObjectId).toBe(fx.compositionObjectId);
        expect(view.covers.TITLE_PAGE == null).toBe(true);
        // Idempotent create: a second Manual request returns the same composition, never a 409.
        const again = await request.post(PB_ROOT + '/' + bookObjectId + '/covers/cover', { data: { mode: 'manual' } });
        const againText = await again.text();
        expect(again.ok(), 'second POST /covers/cover: HTTP ' + again.status() + ' ' + againText.slice(0, 300)).toBe(true);
        const againBody = JSON.parse(againText);
        expect(againBody.existingReturned).toBe(true);
        expect(againBody.created).toBe(false);
        expect(againBody.compositionObjectId).toBe(fx.compositionObjectId);
        await request.get(REST + '/logout');
    });

    test('2. the camera section saves the title-safe band; the plan reserves the band; the scene board links to the covers; the reader shows no cover yet', async ({ page, request }) => {
        expect(fx.compositionObjectId, 'cover composition from test 1').toBeTruthy();
        await loginAsSharedUser(page);
        const patches = [];
        page.on('request', req => { if (req.method() === 'PATCH' && /\/covers\/cover$/.test(req.url())) patches.push(JSON.parse(req.postData() || 'null')); });
        await openCoverBoard(page, bookObjectId);
        await expect(page.getByTestId('pb-cmp-cover')).toBeVisible({ timeout: 30000 });
        await page.getByTestId('pb-cmp-setting').fill(COVER_SETTING);
        await page.getByTestId('pb-cmp-action').fill(COVER_ACTION);
        await page.getByTestId('pb-cmp-mood').fill(COVER_MOOD);
        await page.getByTestId('pb-cmp-title-band').selectOption('bottom');
        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.locator('.toast-text', { hasText: 'Composition saved' })).toBeVisible({ timeout: 30000 });
        await expect.poll(() => patches.length, { timeout: 10000 }).toBe(1);
        expect(patches[0]).toEqual({ settingText: COVER_SETTING, action: COVER_ACTION, mood: COVER_MOOD, titleSafeBand: 'bottom' });
        await expect(page.getByTestId('pb-cmp-cover-band')).toHaveText('band bottom', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-setting')).toHaveValue(COVER_SETTING);
        await expect(page.getByTestId('pb-cmp-title-band')).toHaveValue('bottom');
        await expect(page.getByTestId('pb-cmp-user-edited')).toBeVisible();
        await page.screenshot({ path: OUT_DIR + '/03-band-saved.png', fullPage: true });

        // The scene board carries the Covers link; it lands on the cover board.
        await page.goto('/#!/picture-book/' + bookObjectId + '/compose', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-title')).toHaveText('Composition board', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-cover-link')).toBeVisible();
        await page.getByTestId('pb-cmp-cover-link').click();
        await expect(page.getByTestId('pb-cmp-title')).toHaveText('Cover art', { timeout: 30000 });
        await expect(page).toHaveURL(/\/picture-book\/[^/]+\/cover$/);
        await expect(page.getByTestId('pb-cmp-cover-band')).toHaveText('band bottom', { timeout: 30000 });

        // The reader: nothing is approved, so the cover view falls back (no page image yet → 'none') and no band.
        await openReader(page, bookObjectId);
        const coverRoot = page.locator('[data-pb2-cover-source]');
        await expect(coverRoot).toHaveAttribute('data-pb2-cover-source', 'none');
        await expect(coverRoot).toHaveAttribute('data-pb2-cover-band', '');
        await expect(page.locator('[data-pb2-cover-image]')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/04-reader-no-cover.png', fullPage: true });

        await login(request);
        const board = await getCover(request, bookObjectId, 'cover');
        expect(board.status).toBe(200);
        expect(board.body.composition.settingText).toBe(COVER_SETTING);
        expect(board.body.composition.action).toBe(COVER_ACTION);
        expect(board.body.composition.mood).toBe(COVER_MOOD);
        expect(upper(board.body.composition.titleSafeBand)).toBe('BOTTOM');
        expect(upper(board.body.cover.titleSafeBand)).toBe('BOTTOM');
        expect(board.body.composition.userEdited).toBe(true);
        const view = await getCovers(request, bookObjectId);
        expect(upper(view.covers.COVER.titleSafeBand)).toBe('BOTTOM');

        const plan = await planCover(request, bookObjectId, 'cover', {});
        console.log('[p94-cover-2] plan: purpose=' + plan.purpose + ' reserved=' + JSON.stringify(plan.reserved) + ' canvas=' + JSON.stringify(plan.canvas)
            + ' strategy=' + JSON.stringify(plan.strategy) + ' estimate=' + JSON.stringify(plan.estimate) + ' degradations=' + JSON.stringify(plan.degradations)
            + ' BASE=' + JSON.stringify(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt));
        expect(upper(plan.purpose)).toBe('COVER');
        expect(plan.compositionObjectId).toBe(fx.compositionObjectId);
        expect(plan.reserved).toEqual({ x: 0, y: 0.75, w: 1, h: 0.25 });
        expect(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt, 'the cover plan carries a compiled BASE prompt').toBeTruthy();
        expect(plan.compiled.BASE.prompt).toContain('lighthouse');
        expect(JSON.stringify(plan)).not.toMatch(/https?:\/\//);
        // One artifact per planned variant (the P9-2 live test's -v0 / -v1 finals): test 3 expects that many.
        expect(plan.estimate && plan.estimate.variants, 'the plan estimate states the variant count').toBeGreaterThan(0);
        fx.planVariants = plan.estimate.variants;

        // Direct PATCH: `none` clears the reserved band; `top` reserves the top quarter; back to `bottom`.
        const none = await patchCover(request, bookObjectId, 'cover', { titleSafeBand: 'none' });
        expect(none.updated).toBe(true);
        expect(upper(none.purpose)).toBe('COVER');
        expect(upper(none.composition.titleSafeBand)).toBe('NONE');
        expect((await planCover(request, bookObjectId, 'cover', {})).reserved == null, 'NONE reserves nothing').toBe(true);
        const top = await patchCover(request, bookObjectId, 'cover', { titleSafeBand: 'top' });
        expect(upper(top.composition.titleSafeBand)).toBe('TOP');
        expect((await planCover(request, bookObjectId, 'cover', {})).reserved).toEqual({ x: 0, y: 0, w: 1, h: 0.25 });
        const bottom = await patchCover(request, bookObjectId, 'cover', { titleSafeBand: 'bottom' });
        expect(upper(bottom.composition.titleSafeBand)).toBe('BOTTOM');
        // A scene PATCH 400s the key; the cover PATCH refuses an unknown key the same way.
        const bad = await request.patch(PB_ROOT + '/' + bookObjectId + '/covers/cover', { data: { notAField: 1 } });
        expect(bad.status(), 'unknown key → 400: ' + (await bad.text()).slice(0, 200)).toBe(400);
        await request.get(REST + '/logout');
    });

    test('3. [SD] Render follows the job to a cover image; Approve latest makes it the reader\'s cover in the BOTTOM band', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD render');
        test.skip(fx.inventoryUnavailable, 'the renderer inventory is unavailable (' + JSON.stringify(fx.inventoryError) + ') — nothing can render');
        expect(fx.compositionObjectId, 'cover composition from test 1').toBeTruthy();
        await loginAsSharedUser(page);
        await openCoverBoard(page, bookObjectId);
        const strip = page.getByTestId('pb-cmp-cover');
        await expect(strip).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('Not rendered yet');

        const t0 = Date.now();
        await page.getByTestId('pb-cmp-render-cover').click();
        await expect(page.locator('.toast-text', { hasText: /^Cover render started/ })).toBeVisible({ timeout: 60000 });
        const run = page.getByTestId('pb-cmp-render-run');
        await expect(run).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-render-cover')).toBeDisabled();
        // A real SD render on the LAN GPU; ample time, never a threshold.
        await expect(run).toHaveAttribute('data-run-terminal', 'true', { timeout: 1800000 });
        const counter = await page.getByTestId('pb-cmp-render-counter').textContent();
        console.log('[p94-cover-3] render job terminal in ' + (Date.now() - t0) + ' ms: ' + counter);
        expect(counter, 'the job finished without an error').toBe('Rendered');
        await expect(page.locator('.toast-text', { hasText: 'Cover: Rendered' })).toBeVisible({ timeout: 30000 });

        const latest = page.getByTestId('pb-cmp-cover-latest');
        await expect(latest).toBeVisible({ timeout: 60000 });
        await expect.poll(async () => latest.getAttribute('data-artifact'), { timeout: 60000 }).toMatch(/^[0-9a-f-]{36}$/);
        fx.latestArtifactOid = await latest.getAttribute('data-artifact');
        // The render writes one BASE artifact per planned variant (rev 1..N); the Latest tile shows the highest
        // revision and the state line counts the chain — N = plan.estimate.variants from test 2 (2 on the stack).
        const variants = fx.planVariants;
        await expect(latest).toContainText('rev ' + variants);
        await expect(latest.locator('img')).toHaveCount(1);
        await expect.poll(() => latest.locator('img').evaluate(img => img.complete && img.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
        // A RENDER_PLAN node closes DONE_UNVERIFIED (verification is the review step); the selected artifact is
        // the latest, not yet approved. The strip ends in its reviewStatus: `generated` when the stack's QA has not
        // run yet, `in_review` once it has — the same "rendered, not approved" moment either way (test 4's shape,
        // ledger §6.C 243 b).
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText(new RegExp('^DONE_UNVERIFIED · ' + variants + ' render' + (variants === 1 ? '' : 's') + ' · (generated|in_review)$'));
        await expect(strip).toHaveAttribute('data-node-status', 'DONE_UNVERIFIED');
        await expect(page.getByTestId('pb-cmp-cover-approved')).toContainText('none');
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeEnabled();
        await expect(page.getByTestId('pb-cmp-render-cover')).toHaveText(/Re-render/);
        await expect(page.getByTestId('pb-cmp-cover-error')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/05-rendered.png', fullPage: true });

        await login(request);
        const png = await savePng(request, PB_ROOT + '/' + bookObjectId, fx.latestArtifactOid, 'cover-latest.png');
        fx.pngPath = png.pngPath;
        expect(upper(png.art.reviewStatus), 'rendered, not approved: GENERATED before QA, IN_REVIEW after').toMatch(/^(GENERATED|IN_REVIEW)$/);
        const beforeApprove = await getCovers(request, bookObjectId);
        expect(beforeApprove.covers.COVER.latest && beforeApprove.covers.COVER.latest.artifactObjectId).toBe(fx.latestArtifactOid);
        expect(beforeApprove.covers.COVER.latest.revision, 'latest = the highest variant revision').toBe(fx.planVariants);
        expect(beforeApprove.covers.COVER.chainLength, 'one BASE artifact per planned variant').toBe(fx.planVariants);
        expect(beforeApprove.covers.COVER.approved == null, 'nothing approved before the click').toBe(true);
        expect(beforeApprove.workflowObjectId, 'the render created the workflow').toBeTruthy();
        await request.get(REST + '/logout');

        await page.getByTestId('pb-cmp-approve-cover').click();
        await expect(page.locator('.toast-text', { hasText: 'Cover approved' })).toBeVisible({ timeout: 60000 });
        const approved = page.getByTestId('pb-cmp-cover-approved');
        await expect(approved).toHaveAttribute('data-artifact', fx.latestArtifactOid, { timeout: 60000 });
        await expect(approved).toContainText('approved');
        await expect(approved.locator('img')).toHaveCount(1);
        await expect(page.getByTestId('pb-cmp-cover-latest')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
        await expect(page.getByTestId('pb-cmp-cover-state')).toContainText('approved');
        await page.screenshot({ path: OUT_DIR + '/06-approved.png', fullPage: true });

        await login(request);
        const afterApprove = await getCovers(request, bookObjectId);
        console.log('[p94-cover-3] GET /covers after approve: ' + JSON.stringify(afterApprove.covers.COVER));
        expect(afterApprove.covers.COVER.approved && afterApprove.covers.COVER.approved.artifactObjectId).toBe(fx.latestArtifactOid);
        expect(upper(afterApprove.covers.COVER.approved.reviewStatus)).toBe('APPROVED');
        expect(upper(afterApprove.covers.COVER.titleSafeBand)).toBe('BOTTOM');
        expect(afterApprove.covers.COVER.approved.imageName, 'the approved ref carries the media name').toBeTruthy();
        await request.get(REST + '/logout');

        await openReader(page, bookObjectId);
        const coverRoot = page.locator('[data-pb2-cover-source]');
        await expect(coverRoot).toHaveAttribute('data-pb2-cover-source', 'cover');
        await expect(coverRoot).toHaveAttribute('data-pb2-cover-band', 'BOTTOM');
        await expect(coverRoot).toHaveClass(/justify-end/);
        const img = page.locator('[data-pb2-cover-image]');
        await expect(img).toHaveCount(1);
        await expect(img).toHaveClass(/opacity-100/);
        // pb2ImageUrl builds the MediaServlet URL from the ref's imageGroupPath + imageName as-is.
        expect(await img.getAttribute('src')).toContain(afterApprove.covers.COVER.approved.imageGroupPath + '/' + afterApprove.covers.COVER.approved.imageName);
        await expect.poll(() => img.evaluate(el => el.complete && el.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
        await page.screenshot({ path: OUT_DIR + '/07-reader-cover.png', fullPage: true });
        console.log('[p94-cover-3] reader shows the approved cover: ' + (await img.getAttribute('src')));
    });

    test('4. [SD] the TITLE_PAGE tab\'s Create "From a scene" clones scene 0 as a separate CLONE-source composition; Render → Approve makes it the approved title page while COVER is untouched', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD render');
        test.skip(fx.inventoryUnavailable, 'the renderer inventory is unavailable (' + JSON.stringify(fx.inventoryError) + ') — nothing can render');
        expect(fx.compositionObjectId, 'cover composition from test 1').toBeTruthy();
        expect(fx.scene && fx.scene.id && fx.scene.objectId, 'scene 0 from the seed: ' + JSON.stringify(fx.scene)).toBeTruthy();
        const tp = fx.titlePage;

        // ── REST pre-state, as the shared user: the scene's SCENE composition (seeded if the scene has none — see
        // ensureSceneComposition), no TITLE_PAGE yet, and a snapshot of the COVER entry to compare against at the end.
        await login(request);
        const sceneBefore = await getSceneComposition(request, bookObjectId, fx.scene.objectId);
        tp.sceneCompositionSeeded = sceneBefore.status === 404;
        const sceneComp = await ensureSceneComposition(request, bookObjectId, fx.scene, fx.book);
        tp.sceneCompositionObjectId = sceneComp.compositionObjectId;
        expect(sceneComp.composition.settingText, 'the scene composition carries a setting to clone').toBeTruthy();
        console.log('[p94-cover-4] scene 0 composition ' + tp.sceneCompositionObjectId + (tp.sceneCompositionSeeded ? ' (seeded by this test)' : ' (pre-existing)')
            + ': ' + JSON.stringify(sceneComp.composition).slice(0, 500));
        const missing = await getCover(request, bookObjectId, 'title_page');
        expect(missing.status, 'GET /covers/title_page before creation: ' + missing.text.slice(0, 200)).toBe(404);
        expect(missing.text).toMatch(/has no TITLE_PAGE composition/i);
        const viewBefore = await getCovers(request, bookObjectId);
        expect(viewBefore.covers && viewBefore.covers.COVER && viewBefore.covers.COVER.compositionObjectId, 'the COVER entry tests 1-3 left').toBe(fx.compositionObjectId);
        expect(viewBefore.covers.TITLE_PAGE == null, 'no TITLE_PAGE entry yet').toBe(true);
        const coverBefore = JSON.parse(JSON.stringify(viewBefore.covers.COVER));
        console.log('[p94-cover-4] COVER entry before: ' + JSON.stringify(coverBefore));
        await request.get(REST + '/logout');

        // ── UI: the TITLE_PAGE tab → the empty state → Create dialog → From a scene → scene 0 → Create.
        await loginAsSharedUser(page);
        const posts = [];
        page.on('request', req => { if (req.method() === 'POST' && /\/covers\/title[-_]page$/.test(req.url())) posts.push(JSON.parse(req.postData() || 'null')); });
        await openCoverBoard(page, bookObjectId);
        const tab = page.getByTestId('pb-cmp-purpose-title-page');
        await expect(tab).toBeVisible();
        await expect(tab).toHaveAttribute('data-active', 'false');
        await expect(tab).toHaveAttribute('data-exists', 'false', { timeout: 30000 });
        await tab.click();
        await expect(page).toHaveURL(/\/picture-book\/[^/]+\/cover\/title-page$/);
        await expect(tab).toHaveAttribute('data-active', 'true');
        await expect(page.getByTestId('pb-cmp-purpose-cover')).toHaveAttribute('data-active', 'false');
        await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-empty-message')).toContainText(/no title[_ ]page composition/i);
        await expect(page.getByTestId('pb-cmp-cover')).toHaveCount(0);
        await page.getByTestId('pb-cmp-create-cover-open').click();
        const dialog = page.getByTestId('pb-cmp-create-cover');
        await expect(dialog).toBeVisible();
        await expect(page.getByTestId('pb-cmp-create-auto')).toBeChecked();
        await expect(page.getByTestId('pb-cmp-create-from-scene')).toBeEnabled();
        await page.getByTestId('pb-cmp-create-from-scene').check();
        await expect(page.getByTestId('pb-cmp-create-from-scene')).toBeChecked();
        const sceneSelect = page.getByTestId('pb-cmp-create-scene');
        await expect(sceneSelect).toBeVisible();
        await expect(sceneSelect.locator('option')).toHaveCount(1);
        // sceneLabel(/pages DTO): '<sceneIndex> · <title>'.
        await expect(sceneSelect.locator('option')).toHaveText('0 · Lanterns on the quay');
        await sceneSelect.selectOption(fx.scene.objectId);
        await expect(sceneSelect).toHaveValue(fx.scene.objectId);
        await page.screenshot({ path: OUT_DIR + '/08-title-page-dialog.png', fullPage: true });
        const t0 = Date.now();
        await page.getByTestId('pb-cmp-create-submit').click();
        // createCoverText: '<label> created from the scene' for mode FROM_SCENE; an error toast is reported by name.
        const createToast = await waitForToast(page, /^Title page created from the scene/, 60000);
        console.log('[p94-cover-4] From-scene create through the dialog in ' + (Date.now() - t0) + ' ms: ' + createToast);
        await expect(dialog).toHaveCount(0);
        expect(posts.length, 'exactly one POST /covers/title-page from the dialog').toBe(1);
        expect(posts[0]).toEqual({ mode: 'from_scene', sceneObjectId: fx.scene.objectId });
        const strip = page.getByTestId('pb-cmp-cover');
        await expect(strip).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-empty')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('Not rendered yet');
        // A clone leaves titleSafeBand UNKNOWN (cloneFromScene), so the strip shows no band chip.
        await expect(page.getByTestId('pb-cmp-cover-band')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-source')).toHaveText(/clone/i);
        await expect(page.getByTestId('pb-cmp-purpose')).toHaveText(/title[_ ]?page/i);
        await expect(page.getByTestId('pb-cmp-revision')).toHaveText('rev 0');
        await expect(page.getByTestId('pb-cmp-setting')).toHaveValue(sceneComp.composition.settingText);
        await expect(page.getByTestId('pb-cmp-action')).toHaveValue(sceneComp.composition.action || '');
        await expect(page.getByTestId('pb-cmp-mood')).toHaveValue(sceneComp.composition.mood || '');
        await expect(page.getByTestId('pb-cmp-camera-text')).toHaveValue(sceneComp.composition.cameraText || '');
        await expect(tab).toHaveAttribute('data-exists', 'true', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-purpose-cover')).toHaveAttribute('data-exists', 'true');
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
        await expect(page.getByTestId('pb-cmp-render-cover')).toBeEnabled();
        await page.screenshot({ path: OUT_DIR + '/09-title-page-created.png', fullPage: true });

        // ── REST: a separate CLONE-source composition carrying the scene's text; the repeat POST is idempotent;
        // the COVER entry and the scene's own composition are untouched; the plan compiles the cloned setting.
        await login(request);
        const board = await getCover(request, bookObjectId, 'title_page');
        expect(board.status, 'GET /covers/title_page after creation: ' + board.text.slice(0, 300)).toBe(200);
        console.log('[p94-cover-4] GET /covers/title_page: ' + JSON.stringify(board.body).slice(0, 700));
        expect(upper(board.body.purpose)).toBe('TITLE_PAGE');
        // PbCoverUtil.cloneFromScene → createCover(..., PbCompositionSourceEnumType.CLONE, 0, null, src): the From-scene
        // source word is CLONE (PbCompositionSourceEnumType: UNKNOWN, LLM, USER, MIGRATED, CLONE).
        expect(upper(board.body.composition.source)).toBe('CLONE');
        expect(upper(board.body.cover.source)).toBe('CLONE');
        expect(upper(board.body.composition.purpose)).toBe('TITLE_PAGE');
        expect(board.body.composition.revision || 0, 'a clone starts at revision 0').toBe(0);
        tp.compositionObjectId = board.body.compositionObjectId;
        expect(tp.compositionObjectId).toMatch(/^[0-9a-f-]{36}$/);
        expect(board.body.composition.objectId).toBe(tp.compositionObjectId);
        expect(tp.compositionObjectId, 'a separate composition from the COVER one').not.toBe(fx.compositionObjectId);
        expect(tp.compositionObjectId, 'a separate composition from the scene\'s own').not.toBe(tp.sceneCompositionObjectId);
        // CLONED_COMPOSITION_FIELDS: setting / action / mood / shot / viewpoint / camera / props come across.
        expect(board.body.composition.settingText, 'cloned setting is non-empty').toBeTruthy();
        expect(board.body.composition.settingText).toBe(sceneComp.composition.settingText);
        expect(board.body.composition.action || null).toBe(sceneComp.composition.action || null);
        expect(board.body.composition.mood || null).toBe(sceneComp.composition.mood || null);
        expect(board.body.composition.cameraText || null).toBe(sceneComp.composition.cameraText || null);
        expect(board.body.composition.props || null).toBe(sceneComp.composition.props || null);
        expect(upper(board.body.composition.shotType)).toBe(upper(sceneComp.composition.shotType));
        expect(upper(board.body.composition.viewpoint)).toBe(upper(sceneComp.composition.viewpoint));
        expect(board.body.composition.titleSafeBand == null || upper(board.body.composition.titleSafeBand) === 'UNKNOWN', 'band left UNKNOWN: ' + board.body.composition.titleSafeBand).toBe(true);
        expect(board.body.cover.nodeObjectId == null, 'no render node yet').toBe(true);
        // Idempotent (ledger 237 d): a repeat From-scene POST returns the same composition, never a 409 or a second clone.
        const again = await request.post(PB_ROOT + '/' + bookObjectId + '/covers/title_page', { data: { mode: 'from_scene', sceneObjectId: fx.scene.objectId } });
        const againText = await again.text();
        expect(again.ok(), 'second POST /covers/title_page: HTTP ' + again.status() + ' ' + againText.slice(0, 300)).toBe(true);
        const againBody = JSON.parse(againText);
        expect(againBody.existingReturned).toBe(true);
        expect(againBody.created).toBe(false);
        expect(againBody.compositionObjectId).toBe(tp.compositionObjectId);
        expect(upper(againBody.purpose)).toBe('TITLE_PAGE');
        const viewCreated = await getCovers(request, bookObjectId);
        expect(viewCreated.covers.TITLE_PAGE && viewCreated.covers.TITLE_PAGE.compositionObjectId).toBe(tp.compositionObjectId);
        expect(upper(viewCreated.covers.TITLE_PAGE.source)).toBe('CLONE');
        expect(viewCreated.covers.TITLE_PAGE.nodeObjectId == null, 'no render node before Render').toBe(true);
        expect(viewCreated.covers.COVER, 'creating the title page leaves the COVER entry as it was').toEqual(coverBefore);
        const sceneAfterClone = await getSceneComposition(request, bookObjectId, fx.scene.objectId);
        expect(sceneAfterClone.status, 'the scene still has its own composition: ' + sceneAfterClone.text.slice(0, 200)).toBe(200);
        expect(sceneAfterClone.body.compositionObjectId).toBe(tp.sceneCompositionObjectId);
        expect(upper(sceneAfterClone.body.composition.purpose)).toBe('SCENE');
        // The plan compiles the cloned setting (not the COVER's lighthouse); an UNKNOWN band reserves nothing
        // (PbLayoutUtil.titleSafeBand → null) and is no render blocker.
        const plan = await planCover(request, bookObjectId, 'title_page', {});
        console.log('[p94-cover-4] title_page plan: purpose=' + plan.purpose + ' reserved=' + JSON.stringify(plan.reserved) + ' canvas=' + JSON.stringify(plan.canvas)
            + ' estimate=' + JSON.stringify(plan.estimate) + ' degradations=' + JSON.stringify(plan.degradations)
            + ' BASE=' + JSON.stringify(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt));
        expect(upper(plan.purpose)).toBe('TITLE_PAGE');
        expect(plan.compositionObjectId).toBe(tp.compositionObjectId);
        expect(plan.reserved == null, 'an UNKNOWN band reserves nothing').toBe(true);
        expect(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt, 'the title-page plan carries a compiled BASE prompt').toBeTruthy();
        expect(plan.compiled.BASE.prompt).toMatch(/harbour|lantern|quay/i);
        expect(plan.compiled.BASE.prompt).not.toContain('lighthouse');
        expect(JSON.stringify(plan)).not.toMatch(/https?:\/\//);
        expect(plan.estimate && plan.estimate.variants, 'the plan estimate states the variant count').toBeGreaterThan(0);
        tp.planVariants = plan.estimate.variants;
        await request.get(REST + '/logout');

        // ── UI: Render → the job to terminal → Approve latest, test 3's path on the title-page tab.
        await expect(page).toHaveURL(/\/cover\/title-page$/);
        await expect(tab).toHaveAttribute('data-active', 'true');
        const t1 = Date.now();
        await page.getByTestId('pb-cmp-render-cover').click();
        await waitForToast(page, /^Title page render started/, 60000);
        const run = page.getByTestId('pb-cmp-render-run');
        await expect(run).toBeVisible({ timeout: 30000 });
        await expect(run).toContainText('Render title page');
        await expect(page.getByTestId('pb-cmp-render-cover')).toBeDisabled();
        // A real SD render on the LAN GPU; ample time, never a threshold.
        await expect(run).toHaveAttribute('data-run-terminal', 'true', { timeout: 1800000 });
        const counter = await page.getByTestId('pb-cmp-render-counter').textContent();
        console.log('[p94-cover-4] title-page render job terminal in ' + (Date.now() - t1) + ' ms: ' + counter);
        expect(counter, 'the job finished without an error').toBe('Rendered');
        await expect(page.locator('.toast-text', { hasText: 'Title page: Rendered' })).toBeVisible({ timeout: 30000 });
        // watchRender re-reads the board after the toast; wait for the strip to leave 'Not rendered yet'.
        await expect(page.getByTestId('pb-cmp-cover-state')).not.toHaveText('Not rendered yet', { timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-cover-error')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-cover-band')).toHaveCount(0);
        console.log('[p94-cover-4] strip after render: node-status=' + (await strip.getAttribute('data-node-status')) + ' state=' + (await page.getByTestId('pb-cmp-cover-state').textContent()));

        const latest = page.getByTestId('pb-cmp-cover-latest');
        const approvedTile = page.getByTestId('pb-cmp-cover-approved');
        let approvedOid = null;
        // renderAndApprove's shape (pictureBookV3Usability step 15): Approve when a Latest tile is offered; when the
        // strip already shows the render as approved (no Latest tile), accept the auto-approval and say so.
        if (await latest.count()) {
            // A RENDER_PLAN node closes DONE_UNVERIFIED (verification is the review step) — test 3's observed state.
            await expect(strip).toHaveAttribute('data-node-status', 'DONE_UNVERIFIED');
            await expect.poll(async () => latest.getAttribute('data-artifact'), { timeout: 60000 }).toMatch(/^[0-9a-f-]{36}$/);
            tp.latestArtifactOid = await latest.getAttribute('data-artifact');
            await expect(latest).toContainText('rev ' + tp.planVariants);
            await expect(latest.locator('img')).toHaveCount(1);
            await expect.poll(() => latest.locator('img').evaluate(img => img.complete && img.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
            // The strip ends in the selected artifact's reviewStatus (coverStateText): `generated` when QA has not
            // run yet, `in_review` once it has — run 1 (scratch/p9-9/pw-cover-run1.log) saw `in_review`.
            await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText(new RegExp('^DONE_UNVERIFIED · ' + tp.planVariants + ' render' + (tp.planVariants === 1 ? '' : 's') + ' · (generated|in_review)$'));
            await expect(approvedTile).toContainText('none');
            await expect(page.getByTestId('pb-cmp-approve-cover')).toBeEnabled();
            await expect(page.getByTestId('pb-cmp-render-cover')).toHaveText(/Re-render/);
            await page.screenshot({ path: OUT_DIR + '/10-title-page-rendered.png', fullPage: true });

            await login(request);
            const beforeApprove = await getCovers(request, bookObjectId);
            const tpBefore = beforeApprove.covers.TITLE_PAGE;
            expect(tpBefore && tpBefore.latest && tpBefore.latest.artifactObjectId).toBe(tp.latestArtifactOid);
            expect(tpBefore.latest.revision, 'latest = the highest variant revision').toBe(tp.planVariants);
            expect(tpBefore.chainLength, 'one BASE artifact per planned variant').toBe(tp.planVariants);
            expect(tpBefore.approved == null, 'nothing approved before the click').toBe(true);
            expect(tpBefore.nodeObjectId, 'the render created the title-page node').toBeTruthy();
            expect(beforeApprove.covers.COVER, 'rendering the title page leaves the COVER entry as it was').toEqual(coverBefore);
            await request.get(REST + '/logout');

            await page.getByTestId('pb-cmp-approve-cover').click();
            await waitForToast(page, /^Title page approved/, 60000);
            tp.autoApproved = false;
            approvedOid = tp.latestArtifactOid;
        } else {
            tp.autoApproved = true;
            await expect.poll(async () => approvedTile.getAttribute('data-artifact'), { timeout: 60000 }).toMatch(/^[0-9a-f-]{36}$/);
            approvedOid = await approvedTile.getAttribute('data-artifact');
            tp.latestArtifactOid = approvedOid;
            console.log('[p94-cover-4] no Latest tile after the render — the stack approved the render itself (' + approvedOid + '); accepted as renderAndApprove does');
        }

        await expect(approvedTile).toHaveAttribute('data-artifact', approvedOid, { timeout: 60000 });
        await expect(approvedTile).toContainText('approved');
        await expect(approvedTile.locator('img')).toHaveCount(1);
        await expect.poll(() => approvedTile.locator('img').evaluate(img => img.complete && img.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
        await expect(page.getByTestId('pb-cmp-cover-latest')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
        await expect(page.getByTestId('pb-cmp-cover-state')).toContainText('approved');
        await page.screenshot({ path: OUT_DIR + '/11-title-page-approved.png', fullPage: true });

        // ── REST after: TITLE_PAGE approved == selected and APPROVED; COVER is byte-for-byte what it was; the PNG.
        await login(request);
        const after = await getCovers(request, bookObjectId);
        const tpEntry = after.covers.TITLE_PAGE;
        console.log('[p94-cover-4] GET /covers after approve: TITLE_PAGE=' + JSON.stringify(tpEntry) + ' COVER=' + JSON.stringify(after.covers.COVER));
        expect(tpEntry && tpEntry.compositionObjectId).toBe(tp.compositionObjectId);
        expect(upper(tpEntry.purpose)).toBe('TITLE_PAGE');
        expect(upper(tpEntry.source)).toBe('CLONE');
        expect(tpEntry.nodeObjectId, 'the render created the title-page node').toBeTruthy();
        expect(tpEntry.approved && tpEntry.approved.artifactObjectId).toBe(approvedOid);
        expect(tpEntry.selectedArtifactObjectId, 'approved == selected').toBe(approvedOid);
        expect(upper(tpEntry.reviewStatus)).toBe('APPROVED');
        expect(upper(tpEntry.approved.reviewStatus)).toBe('APPROVED');
        expect(tpEntry.approved.imageName, 'the approved ref carries the media name').toBeTruthy();
        expect(tpEntry.chainLength, 'one BASE artifact per planned variant').toBe(tp.planVariants);
        expect(after.workflowObjectId, 'the book has its workflow').toBeTruthy();
        // COVER untouched: the entry tests 1-3 left, test 3's approved artifact (when it ran), never the title-page artifact or node.
        expect(after.covers.COVER, 'the COVER entry is unchanged by the title-page create / render / approve').toEqual(coverBefore);
        if (fx.latestArtifactOid) {
            expect(after.covers.COVER.approved && after.covers.COVER.approved.artifactObjectId, 'test 3\'s approved cover is still the approved cover').toBe(fx.latestArtifactOid);
        }
        expect(after.covers.COVER.approved == null || after.covers.COVER.approved.artifactObjectId !== approvedOid, 'the title-page artifact is not the cover\'s').toBe(true);
        expect(after.covers.COVER.selectedArtifactObjectId == null || after.covers.COVER.selectedArtifactObjectId !== approvedOid).toBe(true);
        expect(after.covers.COVER.nodeObjectId == null || after.covers.COVER.nodeObjectId !== tpEntry.nodeObjectId, 'each purpose has its own render node').toBe(true);
        const sceneAfter = await getSceneComposition(request, bookObjectId, fx.scene.objectId);
        expect(sceneAfter.status).toBe(200);
        expect(sceneAfter.body.compositionObjectId, 'the scene\'s own composition is still its own').toBe(tp.sceneCompositionObjectId);
        const png = await savePng(request, PB_ROOT + '/' + bookObjectId, approvedOid, 'title-page-latest.png');
        tp.pngPath = png.pngPath;
        expect(upper(png.art.reviewStatus)).toBe('APPROVED');
        await request.get(REST + '/logout');

        // The reader's cover is the COVER purpose: approving a TITLE_PAGE must not change what the reader shows.
        if (fx.latestArtifactOid) {
            await openReader(page, bookObjectId);
            const coverRoot = page.locator('[data-pb2-cover-source]');
            await expect(coverRoot).toHaveAttribute('data-pb2-cover-source', 'cover');
            const img = page.locator('[data-pb2-cover-image]');
            await expect(img).toHaveCount(1);
            const src = await img.getAttribute('src');
            expect(src).toContain(after.covers.COVER.approved.imageGroupPath + '/' + after.covers.COVER.approved.imageName);
            expect(src).not.toContain(tpEntry.approved.imageName);
            await page.screenshot({ path: OUT_DIR + '/12-reader-still-cover.png', fullPage: true });
            console.log('[p94-cover-4] reader still shows the COVER artifact, not the title page: ' + src);
        }
    });
});
