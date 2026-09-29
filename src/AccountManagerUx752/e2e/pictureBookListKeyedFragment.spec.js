/**
 * PictureBook selector — PB2 "Workflow Books" list must render a MIX of series cards and standalone
 * books (route #!/picture-book, src/features/pictureBook.js renderPb2BookList).
 *
 * Reported 2026-09-28 (console, live stack):
 *   mount-redraw.js:13 TypeError: In fragments, vnodes must either all have keys or none have keys.
 *     at normalizeChildren ... at Object.view (pictureBook.js:323)   <- the [data-pb2-book-list] div
 *   ... and again from Dialog.open on the delete-confirm redraw (deleteBookFromList -> Dialog.confirm).
 *
 * Cause: renderPb2BookList() mapped the '__standalone__' group to a NESTED ARRAY of keyed rows while
 * every series group mapped to ONE keyed vnode. Mithril treats a nested array as an unkeyed fragment,
 * so the list's children were keyed vnodes + an unkeyed fragment and every redraw threw — which only
 * happens once a user owns at least one series-linked book AND at least one standalone book. The fix
 * flattens the standalone rows into the same list so every child is a keyed vnode.
 *
 * This test manufactures exactly that state through the REST API (POST /series, POST /chapter with and
 * without seriesObjectId), opens the selector, then drives the delete-confirm on the standalone row
 * (the redraw in the second reported trace) and finally deletes it. Any page error or console error
 * carrying the Mithril keyed-fragment message fails the test. LLM/SD-free.
 *
 * Run (Windows / Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookListKeyedFragment.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { apiLogin, ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const KEYED_FRAGMENT_RE = /vnodes must either all have keys or none have keys/i;

// Credentials come from ensureSharedTestUser() in beforeAll; nothing here logs in as admin.
let shared = null;

async function restLogin(ctx) {
    expect(shared, 'ensureSharedTestUser() must run before any login').toBeTruthy();
    const resp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

// WS stub + login, canonical pattern (Docker's nginx drops the session cookie on the WS upgrade; without
// the stub the app's reconnect path forces a redirect to #!/sig).
async function loginAsSharedUser(page) {
    await restLogin(page.request);
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

async function createBook(request, body) {
    const resp = await request.post(PB + '/chapter', { data: body });
    expect(resp.ok(), 'POST /chapter ' + body.slug + ' failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const b = await resp.json();
    expect(b && b.bookObjectId, 'POST /chapter ' + body.slug + ' returned no bookObjectId').toBeTruthy();
    return b;
}

async function resetBook(request, bookObjectId) {
    try { await request.delete(PB + '/' + bookObjectId + '/reset'); } catch (_) {}
}

test.describe('PictureBook selector — series + standalone PB2 books render together', () => {
    test.describe.configure({ timeout: 180000 });

    const tag = Date.now().toString(36);
    let seriesOid = null;
    let chapterOid = null;
    let standaloneOid = null;
    const standaloneSlug = 'e2e-keyed-standalone-' + tag;
    const standaloneTitle = 'E2E Keyed Standalone ' + tag;

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await restLogin(request);
        const series = await createSeries(request, 'e2e-keyed-series-' + tag, 'E2E Keyed Series ' + tag);
        seriesOid = series.seriesObjectId;
        const ch = await createBook(request, {
            seriesObjectId: seriesOid, slug: 'e2e-keyed-series-' + tag + '-ch1', title: 'E2E Keyed Chapter 1 ' + tag, chapter: 1
        });
        chapterOid = ch.bookObjectId;
        const sa = await createBook(request, { slug: standaloneSlug, title: standaloneTitle });
        standaloneOid = sa.bookObjectId;

        // The selector's own DTO must expose the mix, else the grouping under test cannot occur.
        const listResp = await request.get(PB + '/books');
        expect(listResp.ok(), 'GET /books failed: ' + listResp.status()).toBe(true);
        const books = await listResp.json();
        const chRow = books.find((b) => b.objectId === chapterOid);
        const saRow = books.find((b) => b.objectId === standaloneOid);
        expect(chRow && chRow.seriesObjectId, 'chapter book must list with its seriesObjectId').toBe(seriesOid);
        expect(saRow, 'standalone book must be listed').toBeTruthy();
        expect(saRow.seriesObjectId == null, 'standalone book must have no seriesObjectId').toBe(true);
    });

    test.afterAll(async ({ request }) => {
        try {
            await restLogin(request);
            if (standaloneOid) await resetBook(request, standaloneOid);
            if (chapterOid) await resetBook(request, chapterOid);
        } catch (_) {}
    });

    test('list renders both, delete-confirm redraws cleanly, standalone deletes', async ({ page }) => {
        const pageErrors = [];
        const consoleErrors = [];
        page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));
        page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/picture-book'; });

        const noKeyedError = () => {
            const hits = pageErrors.concat(consoleErrors).filter((s) => KEYED_FRAGMENT_RE.test(s));
            expect(hits, 'Mithril keyed-fragment error fired: ' + hits.join(' | ')).toEqual([]);
        };

        const list = page.locator('[data-pb2-book-list]');
        // When the view throws, nothing renders — so wait for EITHER the list or an error, then let the
        // captured error (not a bare visibility timeout) name the failure.
        await expect.poll(async () => (await list.count()) > 0 || pageErrors.length > 0 || consoleErrors.some((s) => KEYED_FRAGMENT_RE.test(s)),
            { timeout: 30000, message: 'neither the PB2 book list nor a page error appeared' }).toBe(true);
        noKeyedError();
        await expect(list, 'PB2 book list did not render').toBeVisible({ timeout: 30000 });

        // Both shapes present, as DIRECT children of the list (flat, keyed siblings).
        const seriesCard = list.locator(':scope > [data-pb2-series="' + seriesOid + '"]');
        const standaloneRow = list.locator(':scope > [data-pb2-book="' + standaloneOid + '"]');
        await expect(seriesCard, 'series card missing from the list').toBeVisible({ timeout: 15000 });
        await expect(standaloneRow, 'standalone book row missing from the list').toBeVisible({ timeout: 15000 });
        // Rows render name + slug (the DTO's "title" is the description, shown only in the canvas header).
        await expect(standaloneRow).toContainText(standaloneSlug);
        // The chapter is bundled under its series card, not listed as an individual top-level book.
        await expect(list.locator(':scope > [data-pb2-book="' + chapterOid + '"]')).toHaveCount(0);
        noKeyedError();

        // Expand the series so its chapter rows render inside the card (another redraw of the same list).
        await seriesCard.locator('[data-pb2-series-header]').click();
        await expect(seriesCard.locator('[data-pb2-series-chapters] [data-pb2-book="' + chapterOid + '"]')).toBeVisible({ timeout: 10000 });
        noKeyedError();

        // The reported path: delete on a list row -> Dialog.confirm -> redraw with the dialog open.
        await standaloneRow.locator('button[title="Delete picture book"]').click();
        const destructive = page.locator('.am7-dialog-footer button.am7-dialog-btn-destructive');
        await expect(destructive, 'delete confirm dialog did not open').toBeVisible({ timeout: 10000 });
        noKeyedError();

        await destructive.click();
        const successToast = page.locator('.toast-box').filter({ hasText: 'Picture book deleted' });
        await expect(successToast, 'no "Picture book deleted" success toast').toBeVisible({ timeout: 30000 });
        await expect(standaloneRow, 'standalone row still present after delete').toHaveCount(0, { timeout: 15000 });
        // Series card survives the reload; the list is still rendering.
        await expect(list.locator(':scope > [data-pb2-series="' + seriesOid + '"]')).toBeVisible({ timeout: 15000 });
        noKeyedError();
        expect(pageErrors, 'uncaught page errors during the flow: ' + pageErrors.join(' | ')).toEqual([]);
    });
});
