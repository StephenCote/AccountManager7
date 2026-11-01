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

async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
    // The reader reads /pages, /health and /covers together before it draws the cover; wait for the covers read.
    await expect(page.locator('[data-pb2-cover-source]')).toHaveCount(1, { timeout: 30000 });
}

let bookObjectId = null;
let slug = null;
const fx = { book: null, scene: null, inventoryUnavailable: null, inventoryError: null, compositionObjectId: null, latestArtifactOid: null, pngPath: null, planVariants: null };

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
                title: 'Lanterns on the quay', setting: SCENE_SETTING, action: SCENE_ACTION, mood: 'quiet, end-of-day' }
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
        // the latest, still GENERATED.
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('DONE_UNVERIFIED · ' + variants + ' render' + (variants === 1 ? '' : 's') + ' · generated');
        await expect(strip).toHaveAttribute('data-node-status', 'DONE_UNVERIFIED');
        await expect(page.getByTestId('pb-cmp-cover-approved')).toContainText('none');
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeEnabled();
        await expect(page.getByTestId('pb-cmp-render-cover')).toHaveText(/Re-render/);
        await expect(page.getByTestId('pb-cmp-cover-error')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/05-rendered.png', fullPage: true });

        await login(request);
        const png = await savePng(request, PB_ROOT + '/' + bookObjectId, fx.latestArtifactOid, 'cover-latest.png');
        fx.pngPath = png.pngPath;
        expect(upper(png.art.reviewStatus)).toBe('GENERATED');
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
});
