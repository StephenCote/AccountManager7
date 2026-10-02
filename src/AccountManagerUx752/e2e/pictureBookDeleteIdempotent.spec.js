/**
 * FIX 1 — PictureBook idempotent delete (route #!/picture-book, src/features/pictureBook.js).
 *
 * The reported bug: a stale list row for a book already gone server-side, when deleted, showed a red
 * "Failed to delete book" error and the row lingered. The fix routes every list delete through
 * performPbDelete: reset:true → success toast "Picture book deleted"; a 404 / "Book not found" is
 * treated as an idempotent success (info toast "Already removed"); anything else → a red error toast.
 * The cache is always cleared and the selector lists reloaded afterward, "so a stale row can never
 * persist."
 *
 * These tests drive the PB2 "Workflow Books" rows (deletePb2BookFromList). The PB1 "Legacy Books" list
 * they originally drove was removed on 2026-10-02: its source, the .pictureBookMeta note, is also every
 * PB2 book's scene store, so each book showed twice. An earlier note here claimed the PB2 list was
 * "unconditionally empty" because of olio-principal ownership; that is no longer the case — the list
 * is populated for the shared user, as every other PB2 selector spec relies on.
 *
 * The stale-row case is manufactured for real: the page lists a book, the book is then deleted through
 * the REST API behind the page's back, and the still-visible row's delete button is clicked.
 *
 * LLM/SD-free (POST /chapter creates an empty book), so these run in the default suite.
 *
 * Run (Windows / Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookDeleteIdempotent.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';

// REST login on an arbitrary Playwright request/page.request context.
async function restLogin(ctx) {
    const resp = await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

// Canonical WS-stub + login pattern copied verbatim from chapBook.spec.js — Docker's nginx strips the
// session cookie on the WS upgrade, so without this stub Tomcat closes the socket, forceLogin() fires,
// and the app redirects to #!/sig.
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

// Best-effort cleanup registry (books created during the run), swept in afterAll.
const toCleanup = [];

async function createBook(request, slug, title) {
    const resp = await request.post(PB + '/chapter', { data: { slug, title } });
    expect(resp.ok(), 'POST /chapter ' + slug + ' failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const b = await resp.json();
    expect(b && b.bookObjectId, 'POST /chapter ' + slug + ' returned no bookObjectId').toBeTruthy();
    toCleanup.push(b.bookObjectId);
    return b.bookObjectId;
}

async function openSelector(page) {
    await loginAsSharedUser(page);
    await page.evaluate(() => { window.location.hash = '!/picture-book'; });
    await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: 30000 });
}

async function clickRowDelete(page, row) {
    await row.locator('button[title="Delete picture book"]').click();
    await page.locator('.am7-dialog-footer button.am7-dialog-btn-destructive').click();
}

test.describe('PictureBook — idempotent delete (FIX 1)', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request);
    });

    test.afterAll(async ({ request }) => {
        try {
            await restLogin(request);
            for (const oid of toCleanup) {
                try { await request.delete(PB + '/' + oid + '/reset'); } catch (_) {}
            }
        } catch (_) {}
    });

    // ── (a) NORMAL PATH: deleting a live book shows the green success toast, no red error, and the row
    //        disappears on the reload. ──────────────────────────────────────────────────────────────
    test('a: deleting a live book shows success and removes the row', async ({ page, request }) => {
        await restLogin(request);
        const tag = 'live-' + Date.now().toString(36);
        const oid = await createBook(request, 'e2e-pbdel-' + tag, 'PBDelLive ' + tag);

        await openSelector(page);
        const row = page.locator('[data-pb2-book-list] [data-pb2-book="' + oid + '"]');
        await expect(row, 'created PB2 book row not visible in selector').toBeVisible({ timeout: 15000 });

        await clickRowDelete(page, row);

        // Green success toast — NOT the red error the bug produced.
        const successToast = page.locator('.toast-box').filter({ hasText: 'Picture book deleted' });
        await expect(successToast, 'no "Picture book deleted" success toast').toBeVisible({ timeout: 10000 });
        await expect(successToast).toHaveClass(/bg-green/);
        await expect(page.locator('.toast-box').filter({ hasText: 'Failed to delete' }),
            'a red "Failed to delete" toast appeared on a normal delete').toHaveCount(0);

        await expect(row, 'row did not disappear after a live delete').toHaveCount(0, { timeout: 15000 });

        // The server agrees the book is gone.
        const again = await request.delete(PB + '/' + oid + '/reset');
        expect(again.status(), 'a second reset of the deleted book should 404').toBe(404);
    });

    // ── (b) IDEMPOTENCY CORE: a stale row whose book is already gone. The page lists the book, the
    //        book is deleted via REST behind the page's back, then the still-visible row is deleted.
    //        reset() returns HTTP 404 {"error":"Book not found"}; the fix must show the benign info
    //        toast "Already removed", NOT the red "Failed to delete book", and the reload must drop the
    //        row for good — THIS is the reported bug. ─────────────────────────────────────────────
    test('b: deleting an already-gone (stale) book row shows "Already removed" and the row does not come back', async ({ page, request }) => {
        await restLogin(request);
        const tag = 'stale-' + Date.now().toString(36);
        const oid = await createBook(request, 'e2e-pbdel-' + tag, 'PBDelStale ' + tag);

        await openSelector(page);
        const row = page.locator('[data-pb2-book-list] [data-pb2-book="' + oid + '"]');
        await expect(row, 'created PB2 book row not visible in selector').toBeVisible({ timeout: 15000 });

        // Make the row stale: the book goes away server-side while the page still shows it.
        const gone = await request.delete(PB + '/' + oid + '/reset');
        expect(gone.ok(), 'server-side reset failed: ' + gone.status()).toBe(true);
        await expect(row, 'the page must still show the now-stale row').toBeVisible();

        await clickRowDelete(page, row);

        // Benign info toast (info style carries bg-white), NOT a red error toast.
        const infoToast = page.locator('.toast-box').filter({ hasText: 'Already removed' });
        await expect(infoToast, 'no "Already removed" info toast for an already-gone book').toBeVisible({ timeout: 10000 });
        await expect(infoToast).toHaveClass(/bg-white/);
        await expect(page.locator('.toast-box').filter({ hasText: 'Failed to delete' }),
            'the bug is UNFIXED — a red "Failed to delete book" toast appeared for an already-gone book').toHaveCount(0);

        // The reload drops the stale row, and it stays gone.
        await expect(row, 'stale row still shown after the idempotent delete').toHaveCount(0, { timeout: 15000 });
        await page.waitForTimeout(2000);
        await expect(row, 'stale row reappeared after the reload').toHaveCount(0);
    });
});
