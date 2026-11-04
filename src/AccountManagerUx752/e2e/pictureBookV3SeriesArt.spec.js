/**
 * PictureBook V3 P10-A 7 — SERIES_ART end to end: one series, two chapters, the series art created and rendered
 * from chapter A, shared by chapter B (design §40 purpose SERIES_ART; ledger §19 (7), §6.C 237 f / 237 j), live on
 * the Docker stack as e2etest_shared.
 *
 * What this spec proves:
 *   1. [no SD needed] a standalone book's covers view carries no seriesObjectId, its cover board never offers the
 *      Series art tab, POST /{standalone}/covers/series-art is the 409 "is not a chapter of a series" and the board
 *      GET is the read path's 404 "has no SERIES_ART composition" (a read never says why). A
 *      chapter's view carries the series FK; its board offers the tab; the tab's empty state says the art is shared
 *      and "Create series art…" (Manual) creates the row from chapter A; chapter B's board and GET /{B}/covers then
 *      show the SAME composition (one row, hosted by chapter A — no row is created in B's group: a repeat create from
 *      B is existingReturned). Every entry names its host (hostBookObjectId = A from both chapters); a render
 *      addressed from B is the 409 "is rendered from its host chapter" and materializes no workflow in B (§6.C 257);
 *      B's board shows the "host chapter" chip with data-host-book = A, A's board no chip. A stranger (the ISO test
 *      user, no grants on either chapter) gets 404 from both chapters' /covers and /covers/series-art.
 *   2. [SD] (PB_SD_TESTS=1 and the renderer inventory available) on chapter A: the camera section saves the setting
 *      and the TOP band; Render → job to terminal → the latest render (PNG saved to OUT_DIR — look at it); Approve
 *      latest; GET /{A}/covers and GET /{B}/covers both carry the approved artifact, the same node / status / chain
 *      (resolved through the host from B, which still has no workflow; a render from B is still the 409); chapter B's
 *      reader shows the series art as its cover page ([data-pb2-cover-source="series_art"], title block in the TOP
 *      band, the SAME bytes as chapter A's PNG through the media servlet); chapter A's reader shows the same; chapter
 *      B's board shows the approved tile with the same artifact and the host chip. The stranger is still refused.
 *
 * Not proven here (Vitest-only, src/test/pictureBookCover.test.js): the two-front-page reader walk (approved
 * SERIES_ART page BEFORE an approved COVER page) — it would need a second SD render; and the entitled-sibling 237 j
 * case (a reader of B with nothing on A sees no SERIES_ART) — no REST route lets a plain user grant a stranger read
 * on a chapter's group, so that case is the Service7 H2 contract (TestPictureBookCoverRoutes, user pbcovrtsibling).
 *
 * Run:
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 PB_SD_TESTS=1 \
 *     npx playwright test e2e/pictureBookV3SeriesArt.spec.js --workers=1 --project=chromium --retries=0
 *
 * ensureSharedTestUser / ensureIso42001TestUser only (never admin). Timings are logged, never asserted.
 * Output: test-results/p10a7-series-art/.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, ensureIso42001TestUser } from './helpers/api.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/p10a7-series-art';
const ART_SETTING = 'eight women in salt-stained coats on a stone quay at first light, a tall ship\'s masts behind them';
const ART_ACTION = 'they stand shoulder to shoulder looking out to sea while a bell buoy rocks in the swell';
const ART_MOOD = 'defiant, windswept';

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }

let shared = null;
let stranger = null;

function credentialOf(who) {
    if (!who) throw new Error('login before the beforeAll provisioned the test users');
    return { schema: 'auth.credential', organizationPath: '/Development', name: who.testUserName, credential: b64(who.testPassword), type: 'hashed_password' };
}

async function loginAs(request, who) {
    const resp = await request.post(REST + '/login', { data: credentialOf(who) });
    expect(resp.ok() || resp.status() === 204, 'login as ' + who.testUserName + ': HTTP ' + resp.status()).toBe(true);
}
async function login(request) { return loginAs(request, shared); }
async function logout(request) { await request.get(REST + '/logout'); }

async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', { data: credentialOf(shared) });
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

async function modelsForTask(request, task) {
    const resp = await request.get(REST + '/olio/illustration/models?task=' + encodeURIComponent(task), { timeout: 300000 });
    const text = await resp.text();
    expect(resp.ok(), 'GET /illustration/models?task=' + task + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

/** POST /series → {seriesObjectId, …}; the first series on a stack loads the Olio corpus, so the budget is wide. */
async function createSeries(request, seriesSlug, title) {
    const t0 = Date.now();
    const resp = await request.post(PB_ROOT + '/series', { data: { seriesSlug, title }, timeout: 900000 });
    const text = await resp.text();
    expect(resp.ok(), 'POST /series: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    const b = JSON.parse(text);
    expect(b && b.seriesObjectId, 'POST /series returned no seriesObjectId: ' + text.slice(0, 300)).toBeTruthy();
    console.log('[p10a7-seed] POST /series ' + seriesSlug + ' in ' + (Date.now() - t0) + ' ms: ' + text.slice(0, 300));
    return b;
}

/** POST /chapter → {bookObjectId, …}. */
async function createChapter(request, body) {
    const t0 = Date.now();
    const resp = await request.post(PB_ROOT + '/chapter', { data: body, timeout: 300000 });
    const text = await resp.text();
    expect(resp.ok(), 'POST /chapter ' + body.slug + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    const b = JSON.parse(text);
    expect(b && b.bookObjectId, 'POST /chapter ' + body.slug + ' returned no bookObjectId: ' + text.slice(0, 300)).toBeTruthy();
    console.log('[p10a7-seed] POST /chapter ' + body.slug + ' in ' + (Date.now() - t0) + ' ms: ' + text.slice(0, 300));
    return b;
}

/** GET /{book}/covers → {status, text, body}. */
async function getCoversRaw(request, bookOid) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/covers');
    const text = await resp.text();
    return { status: resp.status(), text, body: (resp.ok() ? JSON.parse(text) : null) };
}
/** GET /{book}/covers → the cover view DTO (asserting 200). */
async function getCovers(request, bookOid) {
    const r = await getCoversRaw(request, bookOid);
    expect(r.status, 'GET /' + bookOid + '/covers: HTTP ' + r.status + ' ' + r.text.slice(0, 300)).toBe(200);
    return r.body;
}
/** GET /{book}/covers/{purpose} → {status, text, body}. */
async function getCover(request, bookOid, purpose) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/covers/' + purpose);
    const text = await resp.text();
    return { status: resp.status(), text, body: (resp.ok() ? JSON.parse(text) : null) };
}
/** POST /{book}/covers/{purpose} → {status, text, body}. */
async function createCover(request, bookOid, purpose, body) {
    const resp = await request.post(PB_ROOT + '/' + bookOid + '/covers/' + purpose, { data: body || {} });
    const text = await resp.text();
    return { status: resp.status(), text, body: (resp.ok() ? JSON.parse(text) : null) };
}

/** Fetch an artifact's PNG bytes through data.data → the media servlet; byte-check; write to OUT_DIR. */
async function savePng(request, bookOid, artifactObjectId, fileName) {
    const artResp = await request.get(PB_ROOT + '/' + bookOid + '/artifact/' + artifactObjectId);
    expect(artResp.ok(), 'GET artifact ' + artifactObjectId + ': HTTP ' + artResp.status()).toBe(true);
    const art = await artResp.json();
    expect(art.dataObjectId, 'artifact.dataObjectId').toBeTruthy();
    const dataResp = await request.get(REST + '/model/data.data/' + art.dataObjectId);
    expect(dataResp.ok(), 'GET data.data: HTTP ' + dataResp.status()).toBe(true);
    const dataRec = await dataResp.json();
    expect(dataRec && dataRec.groupPath && dataRec.name, 'data.data carries groupPath + name').toBeTruthy();
    const mediaUrl = BASE_URL + '/AccountManagerService7/media/Development/data.data' + dataRec.groupPath + '/' + encodeURIComponent(dataRec.name);
    const raw = await mediaBytes(request, mediaUrl, fileName);
    mkdirSync(OUT_DIR, { recursive: true });
    const pngPath = OUT_DIR + '/' + fileName;
    writeFileSync(pngPath, raw);
    await test.info().attach(fileName, { path: pngPath, contentType: 'image/png' });
    const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
    console.log('[p10a7-png] ' + fileName + ' ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) role=' + art.role
        + ' revision=' + art.revision + ' backend=' + art.backend + ' seed=' + art.seed + ' durationMs=' + art.durationMs
        + ' reviewStatus=' + art.reviewStatus + ' -> ' + pngPath);
    return { art, dims, pngPath, raw, mediaUrl };
}

/** GET a media URL as the logged-in request user → PNG bytes (gunzipped when the servlet gzips). */
async function mediaBytes(request, url, what) {
    const media = await request.get(url);
    expect(media.ok(), 'GET media ' + url + ' HTTP ' + media.status()).toBe(true);
    let raw = await media.body();
    if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
    expect(raw.subarray(0, 8).toString('hex'), what + ' bytes are a PNG').toBe('89504e470d0a1a0a');
    expect(raw.readUInt32BE(16) > 0 && raw.readUInt32BE(20) > 0, what + ' PNG has dimensions').toBe(true);
    return raw;
}

async function openCoverBoard(page, bookOid, word) {
    await page.goto('/#!/picture-book/' + bookOid + '/cover' + (word ? '/' + word : ''), { timeout: 30000 });
    await expect(page.getByTestId('pb-cmp-title')).toHaveText('Cover art', { timeout: 30000 });
    await expect(page.getByTestId('pb-cmp-purpose-tabs')).toBeVisible();
}

async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    // The reader reads /pages, /health and /covers together before it draws the cover; wait for the covers read.
    await expect(page.locator('[data-pb2-cover-source]')).toHaveCount(1, { timeout: 30000 });
}

/** Wait for a toast matching `re`; on timeout the error names every toast seen meanwhile (toasts expire after 5 s). */
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

/** The stranger's view of a chapter: the book itself is refused (PbBookUtil.requireBook → 404), so nothing leaks. */
async function assertStrangerRefused(request, bookOid, label) {
    await loginAs(request, stranger);
    const view = await getCoversRaw(request, bookOid);
    const art = await getCover(request, bookOid, 'series-art');
    console.log('[p10a7-stranger] ' + label + ': GET /covers → ' + view.status + ' ' + view.text.slice(0, 120) + ' | GET /covers/series-art → ' + art.status + ' ' + art.text.slice(0, 120));
    expect(view.status, 'stranger GET /' + label + '/covers: ' + view.text.slice(0, 200)).toBe(404);
    expect(view.text).not.toMatch(/series_art|SERIES_ART|compositionObjectId/);
    expect(art.status, 'stranger GET /' + label + '/covers/series-art: ' + art.text.slice(0, 200)).toBe(404);
    expect(art.text).not.toMatch(/compositionObjectId|settingText/);
    await logout(request);
}

let seriesOid = null;
let seriesSlug = null;
const fx = { a: null, b: null, standalone: null, inventoryUnavailable: null, inventoryError: null, compositionObjectId: null, planVariants: null,
    latestArtifactOid: null, approvedOid: null, pngPath: null, approvedRef: null };

test.describe('PictureBook V3 P10-A 7 — SERIES_ART: created from chapter A, shared by chapter B, refused to a stranger', () => {
    // Generous budgets on purpose: POST /series may load the Olio corpus, POST /plan resolves models against the
    // renderer inventory, and test 2 waits for a real SD render. Timings are logged, never asserted.
    test.describe.configure({ mode: 'serial', timeout: 2400000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        stranger = await ensureIso42001TestUser(request);
        expect(stranger.testUserName, 'the stranger must be a different user').not.toBe(shared.testUserName);
        await login(request);
        const ts = Date.now().toString(36);
        seriesSlug = 'p10a7-series-' + ts;
        const series = await createSeries(request, seriesSlug, 'P10-A 7 Series ' + ts);
        seriesOid = series.seriesObjectId;
        const a = await createChapter(request, { seriesObjectId: seriesOid, slug: seriesSlug + '-ch1', title: 'P10-A 7 Chapter One ' + ts, chapter: 1 });
        const b = await createChapter(request, { seriesObjectId: seriesOid, slug: seriesSlug + '-ch2', title: 'P10-A 7 Chapter Two ' + ts, chapter: 2 });
        const s = await createChapter(request, { slug: 'p10a7-standalone-' + ts, title: 'P10-A 7 Standalone ' + ts });
        fx.a = { bookObjectId: a.bookObjectId, slug: seriesSlug + '-ch1', title: 'P10-A 7 Chapter One ' + ts };
        fx.b = { bookObjectId: b.bookObjectId, slug: seriesSlug + '-ch2', title: 'P10-A 7 Chapter Two ' + ts };
        fx.standalone = { bookObjectId: s.bookObjectId, slug: 'p10a7-standalone-' + ts };
        expect(fx.a.bookObjectId).not.toBe(fx.b.bookObjectId);
        const booksResp = await request.get(PB_ROOT + '/series/' + seriesOid + '/books');
        expect(booksResp.ok(), 'GET /series/{id}/books: HTTP ' + booksResp.status()).toBe(true);
        const books = await booksResp.json();
        expect(books.map(x => x.objectId).sort(), 'the series lists exactly the two chapters').toEqual([fx.a.bookObjectId, fx.b.bookObjectId].sort());

        const inv = await modelsForTask(request, 'sceneDirect');
        fx.inventoryUnavailable = !!(inv.inventory && inv.inventory.error);
        fx.inventoryError = inv.inventory ? inv.inventory.error : null;
        console.log('[p10a7-seed] ' + JSON.stringify({ series: seriesOid, seriesSlug, a: fx.a, b: fx.b, standalone: fx.standalone, inventory: inv.inventory, deploymentDefault: inv.deploymentDefault, SD_ENABLED }));
        mkdirSync(OUT_DIR, { recursive: true });
        await logout(request);
    });

    test.afterAll(async () => {
        if (!seriesOid) return;
        console.log('[p10a7-cleanup] left in place as the record of the run: ' + JSON.stringify({ series: seriesOid, seriesSlug, ...fx }));
    });

    test('1. a standalone never offers Series art (409 over REST); a chapter does — created from A through the dialog, the same row is B\'s; a stranger gets 404', async ({ page, request }) => {
        expect(seriesOid && fx.a && fx.b && fx.standalone, 'series + chapters seeded in beforeAll').toBeTruthy();
        const A = fx.a.bookObjectId, B = fx.b.bookObjectId, S = fx.standalone.bookObjectId;

        // ── REST pre-state as the shared user.
        await login(request);
        const freshA = await getCovers(request, A);
        console.log('[p10a7-1] fresh GET /' + A + '/covers: ' + JSON.stringify(freshA));
        expect(freshA.bookObjectId).toBe(A);
        expect(freshA.seriesObjectId, 'a chapter\'s view carries its series FK').toBe(seriesOid);
        expect((freshA.covers || {}).SERIES_ART == null, 'no SERIES_ART yet').toBe(true);
        const missingA = await getCover(request, A, 'series-art');
        expect(missingA.status, 'GET /' + A + '/covers/series-art before creation: ' + missingA.text.slice(0, 200)).toBe(404);
        expect(missingA.text).toMatch(/has no SERIES_ART composition/i);
        const freshB = await getCovers(request, B);
        expect(freshB.seriesObjectId).toBe(seriesOid);
        expect((freshB.covers || {}).SERIES_ART == null).toBe(true);
        const freshS = await getCovers(request, S);
        console.log('[p10a7-1] fresh GET /' + S + '/covers (standalone): ' + JSON.stringify(freshS));
        // NON_EMPTY wire form: a null seriesObjectId is omitted; normalizeCoverView reads absent as null.
        expect(freshS.seriesObjectId == null, 'a standalone\'s view carries no seriesObjectId').toBe(true);
        // The board GET is the read path: find-only, so a non-chapter is the same 404 as "no composition yet"
        // (PbServiceFacade.requireCover) - a read never says more than that. Only the create says WHY (409,
        // PbCoverUtil.requireSeries); TestPictureBookCoverRoutes asserts the same split. (Run 1 asserted the
        // create's 409 on the read - my error, corrected 2026-11-03, §6.C 257.)
        const notChapter = await getCover(request, S, 'series-art');
        expect(notChapter.status, 'GET /' + S + '/covers/series-art: ' + notChapter.text.slice(0, 200)).toBe(404);
        expect(notChapter.text).toMatch(/has no SERIES_ART composition/i);
        const notChapterCreate = await createCover(request, S, 'series-art', { mode: 'manual' });
        expect(notChapterCreate.status, 'POST /' + S + '/covers/series-art: ' + notChapterCreate.text.slice(0, 200)).toBe(409);
        expect(notChapterCreate.text).toMatch(/is not a chapter of a series; SERIES_ART needs one/i);
        const afterS = await getCovers(request, S);
        expect(afterS.covers == null || afterS.covers.SERIES_ART == null, 'the 409 wrote nothing').toBe(true);
        await logout(request);

        // ── UI: the standalone's board never shows the tab …
        await loginAsSharedUser(page);
        await openCoverBoard(page, S);
        await expect(page.getByTestId('pb-cmp-purpose-cover')).toHaveAttribute('data-active', 'true');
        await expect(page.getByTestId('pb-cmp-purpose-tabs').locator('button')).toHaveCount(3);
        await expect(page.getByTestId('pb-cmp-purpose-series-art')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: 30000 });
        await page.screenshot({ path: OUT_DIR + '/01-standalone-three-tabs.png', fullPage: true });
        // … and a direct route to it on the standalone is the read path's 404 as the empty state that says "not a
        // chapter" and offers no Create (the create would be the 409), still without the tab. (Run 2 asserted the
        // 409 in pb-cmp-error here - the board GET is never a 409; corrected 2026-11-03, §6.C 257.)
        await page.goto('/#!/picture-book/' + S + '/cover/series-art', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-title')).toHaveText('Cover art', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-empty-message')).toContainText(/has no SERIES_ART composition/i, { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-empty-not-chapter')).toContainText(/is not a chapter of a series/i, { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-error')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-purpose-series-art')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-create-cover-open')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/02-standalone-series-art-not-chapter.png', fullPage: true });

        // ── UI: chapter A's board offers the tab; its empty state names the sharing; Create → Manual.
        const posts = [];
        page.on('request', req => { if (req.method() === 'POST' && /\/covers\/series[-_]art$/.test(req.url())) posts.push({ url: req.url(), body: JSON.parse(req.postData() || 'null') }); });
        await openCoverBoard(page, A);
        await expect(page.getByTestId('pb-cmp-purpose-tabs').locator('button')).toHaveCount(4, { timeout: 30000 });
        const tab = page.getByTestId('pb-cmp-purpose-series-art');
        await expect(tab).toHaveText(/Series art/);
        await expect(tab).toHaveAttribute('data-active', 'false');
        await expect(tab).toHaveAttribute('data-exists', 'false');
        await tab.click();
        await expect(page).toHaveURL(/\/picture-book\/[^/]+\/cover\/series-art$/);
        await expect(tab).toHaveAttribute('data-active', 'true');
        await expect(page.getByTestId('pb-cmp-purpose-cover')).toHaveAttribute('data-active', 'false');
        await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-empty-message')).toContainText(/no SERIES_ART composition/i);
        await expect(page.getByTestId('pb-cmp-empty-series-note')).toContainText(/shared by every chapter/i);
        await expect(page.getByTestId('pb-cmp-cover')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-error')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/03-chapter-a-empty.png', fullPage: true });
        await page.getByTestId('pb-cmp-create-cover-open').click();
        const dialog = page.getByTestId('pb-cmp-create-cover');
        await expect(dialog).toBeVisible();
        await expect(page.getByTestId('pb-cmp-create-auto')).toBeChecked();
        // A fresh chapter has no pages, so From a scene has nothing to offer; Manual it is (no LLM, no SD here).
        await page.getByTestId('pb-cmp-create-manual').check();
        const t0 = Date.now();
        await page.getByTestId('pb-cmp-create-submit').click();
        const createToast = await waitForToast(page, /^Series art created/, 60000);
        console.log('[p10a7-1] manual series art created through the dialog in ' + (Date.now() - t0) + ' ms: ' + createToast);
        await expect(dialog).toHaveCount(0);
        expect(posts.length, 'exactly one POST /covers/series-art from the dialog').toBe(1);
        expect(posts[0].body).toEqual({ mode: 'manual' });
        expect(posts[0].url).toContain('/' + A + '/covers/series-art');
        const strip = page.getByTestId('pb-cmp-cover');
        await expect(strip).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-empty')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('Not rendered yet');
        await expect(page.getByTestId('pb-cmp-source')).toHaveText(/user/i);
        await expect(page.getByTestId('pb-cmp-purpose')).toHaveText(/series[_ ]?art/i);
        await expect(tab).toHaveAttribute('data-exists', 'true', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-purpose-cover')).toHaveAttribute('data-exists', 'false');
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
        await expect(page.getByTestId('pb-cmp-render-cover')).toBeEnabled();
        await page.screenshot({ path: OUT_DIR + '/04-chapter-a-created.png', fullPage: true });

        // ── REST: one row, A's; B reads the same composition; a repeat create from B returns it (237 f: no relocation, no second row).
        await login(request);
        const boardA = await getCover(request, A, 'series-art');
        expect(boardA.status, 'GET /' + A + '/covers/series-art after creation: ' + boardA.text.slice(0, 300)).toBe(200);
        console.log('[p10a7-1] GET /' + A + '/covers/series-art: ' + JSON.stringify(boardA.body).slice(0, 600));
        expect(upper(boardA.body.purpose)).toBe('SERIES_ART');
        expect(upper(boardA.body.composition.source)).toBe('USER');
        expect(boardA.body.composition.objectId).toBe(boardA.body.compositionObjectId);
        fx.compositionObjectId = boardA.body.compositionObjectId;
        expect(fx.compositionObjectId).toMatch(/^[0-9a-f-]{36}$/);
        const viewA = await getCovers(request, A);
        expect(viewA.covers.SERIES_ART && viewA.covers.SERIES_ART.compositionObjectId).toBe(fx.compositionObjectId);
        expect(viewA.covers.SERIES_ART.seriesObjectId, 'the entry names the series').toBe(seriesOid);
        expect(viewA.covers.SERIES_ART.hostBookObjectId, 'the row is hosted by chapter A (§6.C 257)').toBe(A);
        expect(viewA.covers.COVER == null, 'no COVER row was created').toBe(true);
        const viewB = await getCovers(request, B);
        console.log('[p10a7-1] GET /' + B + '/covers: ' + JSON.stringify(viewB));
        expect(viewB.seriesObjectId).toBe(seriesOid);
        expect(viewB.covers && viewB.covers.SERIES_ART && viewB.covers.SERIES_ART.compositionObjectId, 'chapter B lists chapter A\'s SERIES_ART row').toBe(fx.compositionObjectId);
        expect(viewB.covers.SERIES_ART.hostBookObjectId, 'seen from B, the host is still A').toBe(A);
        expect(viewB.covers.COVER == null, 'chapter B has no cover of its own').toBe(true);
        const boardB = await getCover(request, B, 'series-art');
        expect(boardB.status, 'GET /' + B + '/covers/series-art: ' + boardB.text.slice(0, 300)).toBe(200);
        expect(boardB.body.compositionObjectId, 'the same composition from B').toBe(fx.compositionObjectId);
        expect(boardB.body.cover && boardB.body.cover.hostBookObjectId, 'B\'s board entry names the host').toBe(A);
        // The render pipeline is the host's: a render addressed from B is the 409 naming A, and B gets no workflow.
        const renderFromB = await request.post(PB_ROOT + '/' + B + '/covers/series-art/render', { data: {} });
        const renderFromBText = await renderFromB.text();
        console.log('[p10a7-1] POST /' + B + '/covers/series-art/render → ' + renderFromB.status() + ' ' + renderFromBText.slice(0, 200));
        expect(renderFromB.status(), 'render from the sibling chapter: ' + renderFromBText.slice(0, 200)).toBe(409);
        expect(renderFromBText).toMatch(/is rendered from its host chapter/i);
        expect(renderFromBText).toContain(A);
        expect((await getCovers(request, B)).workflowObjectId == null, 'the 409 materialized no workflow in B').toBe(true);
        const againB = await createCover(request, B, 'series-art', { mode: 'manual' });
        expect(againB.status, 'repeat POST /' + B + '/covers/series-art: ' + againB.text.slice(0, 300)).toBe(200);
        expect(againB.body.existingReturned).toBe(true);
        expect(againB.body.created).toBe(false);
        expect(againB.body.compositionObjectId).toBe(fx.compositionObjectId);
        const againA = await createCover(request, A, 'series-art', { mode: 'manual' });
        expect(againA.status).toBe(200);
        expect(againA.body.existingReturned).toBe(true);
        expect(againA.body.compositionObjectId).toBe(fx.compositionObjectId);
        await logout(request);

        // ── UI: chapter B's board shows the same row on its own Series art tab.
        await openCoverBoard(page, B, 'series-art');
        const tabB = page.getByTestId('pb-cmp-purpose-series-art');
        await expect(tabB).toHaveAttribute('data-active', 'true');
        await expect(tabB).toHaveAttribute('data-exists', 'true', { timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-cover')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('Not rendered yet');
        await expect(page.getByTestId('pb-cmp-purpose')).toHaveText(/series[_ ]?art/i);
        await expect(page.getByTestId('pb-cmp-purpose-cover')).toHaveAttribute('data-exists', 'false');
        await expect(page.getByTestId('pb-cmp-empty')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-error')).toHaveCount(0);
        // B's board knows the row is hosted by A: the chip, and data-host-book = A (the render / approve target).
        await expect(page.getByTestId('pb-cmp-cover')).toHaveAttribute('data-host-book', A);
        await expect(page.getByTestId('pb-cmp-cover-host')).toHaveText('host chapter');
        await page.screenshot({ path: OUT_DIR + '/05-chapter-b-same-row.png', fullPage: true });
        // … while A's own board shows no chip (the host is the book itself).
        await openCoverBoard(page, A, 'series-art');
        await expect(page.getByTestId('pb-cmp-cover')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-cover')).toHaveAttribute('data-host-book', A);
        await expect(page.getByTestId('pb-cmp-cover-host')).toHaveCount(0);

        // ── The stranger: refused on both chapters, nothing of the row in the body.
        await assertStrangerRefused(request, A, 'A');
        await assertStrangerRefused(request, B, 'B');
    });

    test('2. [SD] Render from chapter A → Approve; chapter B\'s reader shows the series art as its cover page with the same bytes; B\'s board shows the approved tile', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD render');
        test.skip(fx.inventoryUnavailable, 'the renderer inventory is unavailable (' + JSON.stringify(fx.inventoryError) + ') — nothing can render');
        expect(fx.compositionObjectId, 'series art composition from test 1').toBeTruthy();
        const A = fx.a.bookObjectId, B = fx.b.bookObjectId;

        // ── UI on chapter A: the camera section saves the setting and the TOP band (a cover-keyed PATCH).
        await loginAsSharedUser(page);
        const patches = [];
        page.on('request', req => { if (req.method() === 'PATCH' && /\/covers\/series[-_]art$/.test(req.url())) patches.push(JSON.parse(req.postData() || 'null')); });
        await openCoverBoard(page, A, 'series-art');
        const strip = page.getByTestId('pb-cmp-cover');
        await expect(strip).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('Not rendered yet');
        await page.getByTestId('pb-cmp-setting').fill(ART_SETTING);
        await page.getByTestId('pb-cmp-action').fill(ART_ACTION);
        await page.getByTestId('pb-cmp-mood').fill(ART_MOOD);
        await page.getByTestId('pb-cmp-title-band').selectOption('top');
        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.locator('.toast-text', { hasText: 'Composition saved' })).toBeVisible({ timeout: 30000 });
        await expect.poll(() => patches.length, { timeout: 10000 }).toBe(1);
        expect(patches[0]).toEqual({ settingText: ART_SETTING, action: ART_ACTION, mood: ART_MOOD, titleSafeBand: 'top' });
        await expect(page.getByTestId('pb-cmp-cover-band')).toHaveText('band top', { timeout: 30000 });

        await login(request);
        const t0p = Date.now();
        const planResp = await request.post(PB_ROOT + '/' + A + '/covers/series-art/plan', { data: {}, timeout: 900000 });
        const planText = await planResp.text();
        expect(planResp.ok(), 'POST /covers/series-art/plan: HTTP ' + planResp.status() + ' ' + planText.slice(0, 300)).toBe(true);
        const plan = JSON.parse(planText);
        console.log('[p10a7-2] plan in ' + (Date.now() - t0p) + ' ms: purpose=' + plan.purpose + ' reserved=' + JSON.stringify(plan.reserved) + ' canvas=' + JSON.stringify(plan.canvas)
            + ' strategy=' + JSON.stringify(plan.strategy) + ' estimate=' + JSON.stringify(plan.estimate) + ' BASE=' + JSON.stringify(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt));
        expect(upper(plan.purpose)).toBe('SERIES_ART');
        expect(plan.compositionObjectId).toBe(fx.compositionObjectId);
        expect(plan.reserved, 'TOP reserves the top quarter').toEqual({ x: 0, y: 0, w: 1, h: 0.25 });
        expect(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt).toMatch(/quay/i);
        expect(plan.estimate && plan.estimate.variants, 'the plan estimate states the variant count').toBeGreaterThan(0);
        fx.planVariants = plan.estimate.variants;
        // The plan read from chapter B compiles the same composition (the plan route is not host-gated: a preview
        // from B compiles with B's context - the Ux addresses it to the host; noted as Yours in §6.C 257).
        const planB = await request.post(PB_ROOT + '/' + B + '/covers/series-art/plan', { data: {}, timeout: 900000 });
        expect(planB.ok(), 'POST /' + B + '/covers/series-art/plan: HTTP ' + planB.status()).toBe(true);
        expect((await planB.json()).compositionObjectId).toBe(fx.compositionObjectId);
        await logout(request);

        // ── UI: Render → the job to terminal → the latest tile.
        const t0 = Date.now();
        await page.getByTestId('pb-cmp-render-cover').click();
        await waitForToast(page, /^Series art render started/, 60000);
        const run = page.getByTestId('pb-cmp-render-run');
        await expect(run).toBeVisible({ timeout: 30000 });
        await expect(run).toContainText('Render series art');
        await expect(page.getByTestId('pb-cmp-render-cover')).toBeDisabled();
        // A real SD render on the LAN GPU; ample time, never a threshold.
        await expect(run).toHaveAttribute('data-run-terminal', 'true', { timeout: 1800000 });
        const counter = await page.getByTestId('pb-cmp-render-counter').textContent();
        console.log('[p10a7-2] series art render job terminal in ' + (Date.now() - t0) + ' ms: ' + counter);
        expect(counter, 'the job finished without an error').toBe('Rendered');
        await expect(page.locator('.toast-text', { hasText: 'Series art: Rendered' })).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-cmp-cover-state')).not.toHaveText('Not rendered yet', { timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-cover-error')).toHaveCount(0);
        console.log('[p10a7-2] strip after render: node-status=' + (await strip.getAttribute('data-node-status')) + ' state=' + (await page.getByTestId('pb-cmp-cover-state').textContent()));

        const latest = page.getByTestId('pb-cmp-cover-latest');
        const approvedTile = page.getByTestId('pb-cmp-cover-approved');
        // renderAndApprove's shape (pictureBookV3Cover test 4): Approve when a Latest tile is offered; when the strip
        // already shows the render as approved (no Latest tile), accept the auto-approval and say so.
        if (await latest.count()) {
            await expect(strip).toHaveAttribute('data-node-status', 'DONE_UNVERIFIED');
            await expect.poll(async () => latest.getAttribute('data-artifact'), { timeout: 60000 }).toMatch(/^[0-9a-f-]{36}$/);
            fx.latestArtifactOid = await latest.getAttribute('data-artifact');
            await expect(latest).toContainText('rev ' + fx.planVariants);
            await expect(latest.locator('img')).toHaveCount(1);
            await expect.poll(() => latest.locator('img').evaluate(img => img.complete && img.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
            await expect(approvedTile).toContainText('none');
            await expect(page.getByTestId('pb-cmp-approve-cover')).toBeEnabled();
            await page.screenshot({ path: OUT_DIR + '/06-chapter-a-rendered.png', fullPage: true });

            await login(request);
            const beforeApprove = await getCovers(request, A);
            const sa = beforeApprove.covers.SERIES_ART;
            expect(sa && sa.latest && sa.latest.artifactObjectId).toBe(fx.latestArtifactOid);
            expect(sa.approved == null, 'nothing approved before the click').toBe(true);
            expect(sa.chainLength, 'one BASE artifact per planned variant').toBe(fx.planVariants);
            const bBefore = (await getCovers(request, B)).covers.SERIES_ART;
            expect(bBefore && bBefore.latest && bBefore.latest.artifactObjectId, 'chapter B sees the same latest render').toBe(fx.latestArtifactOid);
            expect(bBefore.approved == null).toBe(true);
            await logout(request);

            await page.getByTestId('pb-cmp-approve-cover').click();
            await waitForToast(page, /^Series art approved/, 60000);
            fx.approvedOid = fx.latestArtifactOid;
        } else {
            await expect.poll(async () => approvedTile.getAttribute('data-artifact'), { timeout: 60000 }).toMatch(/^[0-9a-f-]{36}$/);
            fx.approvedOid = await approvedTile.getAttribute('data-artifact');
            fx.latestArtifactOid = fx.approvedOid;
            console.log('[p10a7-2] no Latest tile after the render — the stack approved the render itself (' + fx.approvedOid + '); accepted as renderAndApprove does');
        }
        await expect(approvedTile).toHaveAttribute('data-artifact', fx.approvedOid, { timeout: 60000 });
        await expect(approvedTile).toContainText('approved');
        await expect(approvedTile.locator('img')).toHaveCount(1);
        await expect.poll(() => approvedTile.locator('img').evaluate(img => img.complete && img.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
        await expect(page.getByTestId('pb-cmp-cover-latest')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
        await expect(page.getByTestId('pb-cmp-cover-state')).toContainText('approved');
        await page.screenshot({ path: OUT_DIR + '/07-chapter-a-approved.png', fullPage: true });

        // ── REST after: both chapters carry the approved artifact; the PNG is real (saved — look at it).
        await login(request);
        const afterA = await getCovers(request, A);
        const saA = afterA.covers.SERIES_ART;
        console.log('[p10a7-2] GET /' + A + '/covers after approve: ' + JSON.stringify(saA));
        expect(saA.compositionObjectId).toBe(fx.compositionObjectId);
        expect(saA.approved && saA.approved.artifactObjectId).toBe(fx.approvedOid);
        expect(saA.selectedArtifactObjectId, 'approved == selected').toBe(fx.approvedOid);
        expect(upper(saA.reviewStatus)).toBe('APPROVED');
        expect(upper(saA.approved.reviewStatus)).toBe('APPROVED');
        expect(upper(saA.titleSafeBand)).toBe('TOP');
        expect(saA.approved.imageName && saA.approved.imageGroupPath, 'the approved ref carries the media path').toBeTruthy();
        expect(saA.nodeObjectId, 'the render created the series-art node').toBeTruthy();
        expect(saA.hostBookObjectId, 'hosted by A').toBe(A);
        expect(afterA.workflowObjectId, 'the render created chapter A\'s workflow').toBeTruthy();
        expect(afterA.covers.COVER == null, 'still no COVER on A').toBe(true);
        fx.approvedRef = saA.approved;
        const afterB = await getCovers(request, B);
        const saB = afterB.covers.SERIES_ART;
        console.log('[p10a7-2] GET /' + B + '/covers after approve: ' + JSON.stringify(saB) + ' workflowObjectId=' + afterB.workflowObjectId);
        expect(saB, 'chapter B lists the series art').toBeTruthy();
        expect(saB.compositionObjectId).toBe(fx.compositionObjectId);
        expect(saB.approved && saB.approved.artifactObjectId, 'chapter B carries the SAME approved artifact').toBe(fx.approvedOid);
        expect(saB.approved.imageGroupPath + '/' + saB.approved.imageName).toBe(saA.approved.imageGroupPath + '/' + saA.approved.imageName);
        // The node, its status and chain are resolved through the host from B (§6.C 257 p): B has no workflow of its own.
        expect(saB.nodeObjectId, 'the node is the row\'s, seen from B too').toBe(saA.nodeObjectId);
        expect(saB.hostBookObjectId, 'seen from B, the host is A').toBe(A);
        expect(upper(saB.nodeStatus)).toBe(upper(saA.nodeStatus));
        expect(saB.chainLength).toBe(saA.chainLength);
        expect(afterB.workflowObjectId == null, 'chapter B has no workflow: the render ran in A\'s').toBe(true);
        expect(afterB.covers.COVER == null, 'no COVER on B').toBe(true);
        // A render addressed from B is still the 409 naming the host, after the render as before it.
        const renderFromB = await request.post(PB_ROOT + '/' + B + '/covers/series-art/render?force=true', { data: {} });
        const renderFromBText = await renderFromB.text();
        expect(renderFromB.status(), 'render from B after the host render: ' + renderFromBText.slice(0, 200)).toBe(409);
        expect(renderFromBText).toMatch(/is rendered from its host chapter/i);
        const png = await savePng(request, A, fx.approvedOid, 'series-art-approved.png');
        fx.pngPath = png.pngPath;
        expect(upper(png.art.reviewStatus)).toBe('APPROVED');
        await logout(request);

        // ── Chapter B's reader: no COVER of its own, so the series art IS its cover page, in the TOP band.
        await openReader(page, B);
        const coverRootB = page.locator('[data-pb2-cover-source]');
        await expect(coverRootB).toHaveAttribute('data-pb2-cover-source', 'series_art');
        await expect(coverRootB).toHaveAttribute('data-pb2-cover-band', 'TOP');
        await expect(coverRootB).toHaveClass(/justify-start/);
        await expect(page.locator('[data-pb2-series-art]')).toHaveCount(0);
        // The reader's title is the book's name ("Book <slug>", PbBookUtil.bookName), not the request title.
        await expect(coverRootB).toContainText('Book ' + fx.b.slug);
        const imgB = page.locator('[data-pb2-cover-image]');
        await expect(imgB).toHaveCount(1);
        await expect(imgB).toHaveClass(/opacity-100/);
        const srcB = await imgB.getAttribute('src');
        expect(srcB).toContain(saA.approved.imageGroupPath + '/' + saA.approved.imageName);
        await expect.poll(() => imgB.evaluate(el => el.complete && el.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
        await page.screenshot({ path: OUT_DIR + '/08-chapter-b-reader.png', fullPage: true });
        console.log('[p10a7-2] chapter B\'s reader shows the series art as its cover: ' + srcB);
        // The same bytes: what B's reader loads is byte-for-byte chapter A's approved PNG.
        await login(request);
        const bytesB = await mediaBytes(request, srcB.startsWith('http') ? srcB : BASE_URL + srcB, 'chapter B reader image');
        expect(bytesB.length, 'same length as chapter A\'s PNG').toBe(png.raw.length);
        expect(bytesB.equals(png.raw), 'chapter B\'s reader image is byte-for-byte chapter A\'s approved render').toBe(true);
        await logout(request);

        // ── Chapter A's reader: the same page (no COVER approved on A either).
        await openReader(page, A);
        const coverRootA = page.locator('[data-pb2-cover-source]');
        await expect(coverRootA).toHaveAttribute('data-pb2-cover-source', 'series_art');
        await expect(coverRootA).toHaveAttribute('data-pb2-cover-band', 'TOP');
        const srcA = await page.locator('[data-pb2-cover-image]').getAttribute('src');
        expect(srcA).toBe(srcB);
        await page.screenshot({ path: OUT_DIR + '/09-chapter-a-reader.png', fullPage: true });

        // ── Chapter B's board: the approved tile is the same artifact; nothing to approve, Re-render offered.
        await openCoverBoard(page, B, 'series-art');
        const approvedB = page.getByTestId('pb-cmp-cover-approved');
        await expect(approvedB).toHaveAttribute('data-artifact', fx.approvedOid, { timeout: 60000 });
        await expect(approvedB.locator('img')).toHaveCount(1);
        await expect.poll(() => approvedB.locator('img').evaluate(img => img.complete && img.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
        await expect(page.getByTestId('pb-cmp-cover-band')).toHaveText('band top');
        await expect(page.getByTestId('pb-cmp-cover-host')).toHaveText('host chapter');
        await expect(page.getByTestId('pb-cmp-cover')).toHaveAttribute('data-host-book', A);
        await expect(page.getByTestId('pb-cmp-cover-state')).toContainText('approved');
        await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
        await expect(page.getByTestId('pb-cmp-render-cover')).toHaveText(/Re-render/);
        await expect(page.getByTestId('pb-cmp-setting')).toHaveValue(ART_SETTING);
        await page.screenshot({ path: OUT_DIR + '/10-chapter-b-board-approved.png', fullPage: true });

        // ── The stranger is still refused now that there is something worth leaking.
        await assertStrangerRefused(request, A, 'A');
        await assertStrangerRefused(request, B, 'B');
    });
});
