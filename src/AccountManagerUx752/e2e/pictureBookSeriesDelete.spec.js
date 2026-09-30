/**
 * PictureBook selector — delete a whole chaptered book from the series card
 * (route #!/picture-book, src/features/pictureBook.js renderPb2SeriesCard -> deletePb2SeriesFromList,
 *  DELETE /rest/olio/picture-book/series/{seriesObjectId} -> PbSeriesUtil.deleteSeries).
 *
 * Until now chapters had to be deleted one at a time; the series card now carries its own delete that
 * removes every chapter, the ONE shared series world and the series record in a single call, while the
 * per-chapter delete on each chapter row is kept.
 *
 * Manufactures a series with two chapters through the REST API as the shared (non-admin) user, then:
 *   1. a different non-admin user (the ISO test user, not entitled to this series) gets 403 from the new
 *      route and nothing is removed;
 *   2. the owner drives the UI: series card -> "Delete series" -> destructive confirm -> success toast ->
 *      card gone from the list, while the per-chapter delete button is still present on chapter rows;
 *   3. REST post-conditions: GET /series/{id}/books is 404, GET /books lists neither chapter, and a
 *      repeat DELETE is 404.
 * LLM/SD-free. Never runs as admin.
 *
 * Run (Windows / Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookSeriesDelete.spec.js --workers=1 --project=chromium
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { apiLogin, ensureSharedTestUser, ensureIso42001TestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';

let shared = null;
let stranger = null;

async function restLogin(ctx, who) {
    expect(who, 'test users must be provisioned before any login').toBeTruthy();
    const resp = await apiLogin(ctx, { user: who.testUserName, password: who.testPassword });
    expect(resp.ok() || resp.status() === 204, who.testUserName + ' login failed: ' + resp.status()).toBe(true);
}

// WS stub + login, canonical pattern (Docker's nginx drops the session cookie on the WS upgrade; without
// the stub the app's reconnect path forces a redirect to #!/sig).
async function loginAsSharedUser(page) {
    await restLogin(page.request, shared);
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {} close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function createSeries(request, seriesSlug, title) {
    const resp = await request.post(PB + '/series', { data: { seriesSlug, title } });
    expect(resp.ok(), 'POST /series failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const b = await resp.json();
    expect(b && b.seriesObjectId, 'createSeries returned no seriesObjectId').toBeTruthy();
    return b;
}

async function createChapter(request, body) {
    const resp = await request.post(PB + '/chapter', { data: body });
    expect(resp.ok(), 'POST /chapter ' + body.slug + ' failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const b = await resp.json();
    expect(b && b.bookObjectId, 'POST /chapter ' + body.slug + ' returned no bookObjectId').toBeTruthy();
    return b;
}

async function listSeriesBooks(request, seriesOid) {
    return request.get(PB + '/series/' + seriesOid + '/books');
}

test.describe('PictureBook — delete a chaptered book from its series card', () => {
    test.describe.configure({ timeout: 240000 });

    const tag = Date.now().toString(36);
    const seriesSlug = 'e2e-serdel-' + tag;
    let seriesOid = null;
    let ch1Oid = null;
    let ch2Oid = null;

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        stranger = await ensureIso42001TestUser(request);
        expect(stranger.testUserName, 'stranger must be a different user').not.toBe(shared.testUserName);

        await restLogin(request, shared);
        const series = await createSeries(request, seriesSlug, 'E2E Series Delete ' + tag);
        seriesOid = series.seriesObjectId;
        ch1Oid = (await createChapter(request, { seriesObjectId: seriesOid, slug: seriesSlug + '-ch1', title: 'E2E SerDel Ch1 ' + tag, chapter: 1 })).bookObjectId;
        ch2Oid = (await createChapter(request, { seriesObjectId: seriesOid, slug: seriesSlug + '-ch2', title: 'E2E SerDel Ch2 ' + tag, chapter: 2 })).bookObjectId;

        const booksResp = await listSeriesBooks(request, seriesOid);
        expect(booksResp.ok(), 'GET /series/{id}/books failed: ' + booksResp.status()).toBe(true);
        const books = await booksResp.json();
        expect(books.map((b) => b.objectId).sort(), 'series must list exactly the two chapters').toEqual([ch1Oid, ch2Oid].sort());
    });

    test.afterAll(async ({ request }) => {
        // Belt and braces: if the UI step failed, remove what was created so the shared user's list stays clean.
        try {
            await restLogin(request, shared);
            if (seriesOid) await request.delete(PB + '/series/' + seriesOid);
        } catch (_) {}
    });

    test('a non-entitled user is refused and nothing is removed', async () => {
        const ctx = await pwRequest.newContext({ ignoreHTTPSErrors: true, baseURL: test.info().project.use.baseURL });
        try {
            await restLogin(ctx, stranger);
            const denied = await ctx.delete(PB + '/series/' + seriesOid);
            expect(denied.status(), 'stranger DELETE /series/{id} must be 403, body=' + (await denied.text())).toBe(403);
        } finally {
            await ctx.dispose();
        }
        const owner = await pwRequest.newContext({ ignoreHTTPSErrors: true, baseURL: test.info().project.use.baseURL });
        try {
            await restLogin(owner, shared);
            const still = await listSeriesBooks(owner, seriesOid);
            expect(still.ok(), 'series must still resolve after the refused delete: ' + still.status()).toBe(true);
            expect((await still.json()).length, 'both chapters must survive the refused delete').toBe(2);
        } finally {
            await owner.dispose();
        }
    });

    test('owner deletes the whole series from the series card; per-chapter delete remains', async ({ page }) => {
        const pageErrors = [];
        page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/picture-book'; });

        const list = page.locator('[data-pb2-book-list]');
        await expect(list, 'PB2 book list did not render').toBeVisible({ timeout: 30000 });
        const seriesCard = list.locator(':scope > [data-pb2-series="' + seriesOid + '"]');
        await expect(seriesCard, 'series card missing from the list').toBeVisible({ timeout: 15000 });

        // Both options are present: the card's series delete AND, once expanded, each chapter's own delete.
        const seriesDelete = seriesCard.locator('button[data-pb2-series-delete="' + seriesOid + '"]');
        await expect(seriesDelete, 'series card has no "Delete series" button').toBeVisible();
        await seriesCard.locator('[data-pb2-series-header]').click();
        const chapterRows = seriesCard.locator('[data-pb2-series-chapters] [data-pb2-book]');
        await expect(chapterRows).toHaveCount(2, { timeout: 10000 });
        await expect(seriesCard.locator('[data-pb2-series-chapters] button[title="Delete chapter"]'),
            'per-chapter delete buttons must still be offered').toHaveCount(2);

        // Series delete -> destructive confirm -> success toast -> card gone.
        await seriesDelete.click();
        const destructive = page.locator('.am7-dialog-footer button.am7-dialog-btn-destructive');
        await expect(destructive, 'series delete confirm dialog did not open').toBeVisible({ timeout: 10000 });
        await expect(page.locator('.am7-dialog-footer')).toContainText(/Delete series/i);
        await destructive.click();

        const successToast = page.locator('.toast-box').filter({ hasText: 'Series deleted' });
        await expect(successToast, 'no "Series deleted" success toast').toBeVisible({ timeout: 120000 });
        await expect(seriesCard, 'series card still present after delete').toHaveCount(0, { timeout: 15000 });
        await expect(list.locator(':scope > [data-pb2-book="' + ch1Oid + '"]'), 'chapter 1 must not resurface as a standalone row').toHaveCount(0);
        await expect(list.locator(':scope > [data-pb2-book="' + ch2Oid + '"]'), 'chapter 2 must not resurface as a standalone row').toHaveCount(0);
        expect(pageErrors, 'uncaught page errors during the flow: ' + pageErrors.join(' | ')).toEqual([]);

        // REST post-conditions as the same (browser) session.
        const gone = await listSeriesBooks(page.request, seriesOid);
        expect(gone.status(), 'GET /series/{id}/books after delete must be 404').toBe(404);
        const all = await page.request.get(PB + '/books');
        expect(all.ok(), 'GET /books failed: ' + all.status()).toBe(true);
        const ids = (await all.json()).map((b) => b.objectId);
        expect(ids, 'chapter 1 must be gone from /books').not.toContain(ch1Oid);
        expect(ids, 'chapter 2 must be gone from /books').not.toContain(ch2Oid);
        const again = await page.request.delete(PB + '/series/' + seriesOid);
        expect(again.status(), 'repeat DELETE /series/{id} must be 404').toBe(404);
    });
});
