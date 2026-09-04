/**
 * FIX 1 — PictureBook idempotent delete (route #!/picture-book, src/features/pictureBook.js).
 *
 * The reported bug: a stale list row for a book already gone server-side, when deleted, showed a red
 * "Failed to delete book" error and the row lingered. The fix routes EVERY delete (PB2 and PB1 rows)
 * through performPbDelete: reset:true → success toast "Picture book deleted"; a 404 / "Book not found"
 * is treated as an idempotent success (info toast "Already removed"); anything else → a red error
 * toast. The cache is always cleared and BOTH selector lists reloaded afterward, "so a stale row can
 * never persist."
 *
 * ── Why these tests drive the PB1 "Legacy Books" list, not the PB2 list ──────────────────────────
 * Investigation (curl, live stack) established that the PB2 "Workflow Books" selector — the natural
 * home of a freshly-created book and the surface of deletePb2BookFromList — is UNCONDITIONALLY EMPTY:
 * GET /rest/olio/picture-book/books (PbServiceFacade.listBooks) filters ownerId = user.id, but every
 * olio.pb.book is owned by the OLIO PRINCIPAL (PbBookUtil.writeBookRow, "uniform olioUser ownership"),
 * so the list returns [ ] no matter how many books exist. A ChapBook create confirmed this: the book
 * appears in GET /olio/chap-book/books (no ownerId filter) but never in /picture-book/books.
 * ⇒ deletePb2BookFromList is not reachable through the UI. The ONLY reachable performPbDelete surface
 *   is the PB1 "Legacy Books" list, populated by .pictureBookMeta data.note records (deleteBookFromList).
 *   Both handlers call the identical performPbDelete, so this exercises the exact fixed code.
 *
 * These tests are LLM/SD-free (no extraction / no image generation), so they run in the default suite.
 *
 * Run (Windows / Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookDeleteIdempotent.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';

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

// Best-effort cleanup registry (records seeded during the run), swept in afterAll.
const toCleanup = [];

// GET /path/make → find-or-create a data.group at an absolute (~/...) path. Returns { id, objectId }.
async function makeGroup(request, path) {
    const enc = Buffer.from(path).toString('base64').replace(/=/g, '%3D');
    const resp = await request.get(REST + '/path/make/auth.group/data/B64-' + enc);
    expect(resp.ok(), 'makeGroup(' + path + ') failed: ' + resp.status()).toBe(true);
    const b = await resp.json();
    expect(b && b.id, 'no group id for ' + path).toBeTruthy();
    return { id: b.id, objectId: b.objectId };
}

// Seed a .pictureBookMeta data.note inside a group. loadExistingBooks() (name filter, org-wide) renders
// each one as a PB1 "Legacy Books" row whose delete button routes through performPbDelete(bookObjectId).
async function seedMetaNote(request, groupId, bookObjectId, workName, sceneCount) {
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'data.note',
            name: '.pictureBookMeta',
            groupId,
            text: JSON.stringify({ bookObjectId, workName, sceneCount })
        }
    });
    expect(resp.ok(), 'seed .pictureBookMeta failed: ' + resp.status()).toBe(true);
    const b = await resp.json();
    expect(b && b.objectId, 'no objectId for seeded meta note').toBeTruthy();
    return b.objectId;
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
            for (const r of toCleanup) {
                try { await request.delete(REST + '/model/' + r.type + '/' + r.objectId); } catch (_) {}
            }
        } catch (_) {}
    });

    // ── (a) NORMAL PATH: a live PB1 book delete shows the green success toast, no red error, and the
    //        row disappears. Fixture is a genuine data.group at ~/Data/PictureBooks/{tag} with a
    //        .pictureBookMeta note inside pointing at the group objectId. reset(groupObjectId) deletes
    //        the meta note + group (verified via curl: reset:true and the note is gone), so the reload
    //        removes the row. ──────────────────────────────────────────────────────────────────────
    test('a: deleting a live book shows success and removes the row', async ({ page, request }) => {
        await restLogin(request);
        const tag = 'live-' + Date.now().toString(36);
        const grp = await makeGroup(request, '~/Data/PictureBooks/' + tag);
        const workName = 'PBDelLive ' + tag;
        const noteId = await seedMetaNote(request, grp.id, grp.objectId, workName, 3);
        toCleanup.push({ type: 'data.note', objectId: noteId }, { type: 'auth.group', objectId: grp.objectId });

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/picture-book'; });

        const row = page.locator('div.cursor-pointer').filter({ hasText: workName });
        await expect(row, 'seeded PB1 book row not visible in selector').toBeVisible({ timeout: 15000 });

        await row.locator('button[title="Delete picture book"]').click();
        await page.locator('.am7-dialog-footer button.am7-dialog-btn-destructive').click();

        // Green success toast — NOT the red error the bug produced.
        const successToast = page.locator('.toast-box').filter({ hasText: 'Picture book deleted' });
        await expect(successToast, 'no "Picture book deleted" success toast').toBeVisible({ timeout: 10000 });
        await expect(successToast).toHaveClass(/bg-green/);
        await expect(page.locator('.toast-box').filter({ hasText: 'Failed to delete' }),
            'a red "Failed to delete" toast appeared on a normal delete').toHaveCount(0);

        // reset() deleted the meta note, so the reload drops the row.
        await expect(row, 'row did not disappear after a live delete').toHaveCount(0, { timeout: 15000 });
    });

    // ── (b) IDEMPOTENCY CORE: a stale row whose book is already gone. The .pictureBookMeta note points
    //        at an objectId that is not a book (never existed / already deleted), so reset() returns
    //        HTTP 404 {"error":"Book not found"} (verified via curl). The fix must show the benign info
    //        toast "Already removed", NOT the red "Failed to delete book". THIS is the reported bug. ──
    test('b: deleting an already-gone (stale) book row shows "Already removed", not an error', async ({ page, request }) => {
        await restLogin(request);
        const tag = 'stale-' + Date.now().toString(36);
        const grp = await makeGroup(request, '~/Data/PictureBooks/' + tag);
        const workName = 'PBDelStale ' + tag;
        // A well-formed but never-existent book objectId — reset() 404s on it.
        const goneBookId = '00000000-dead-4000-8000-' + Date.now().toString(16).padStart(12, '0').slice(-12);
        const noteId = await seedMetaNote(request, grp.id, goneBookId, workName, 2);
        toCleanup.push({ type: 'data.note', objectId: noteId }, { type: 'auth.group', objectId: grp.objectId });

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/picture-book'; });

        const row = page.locator('div.cursor-pointer').filter({ hasText: workName });
        await expect(row, 'seeded stale PB1 row not visible in selector').toBeVisible({ timeout: 15000 });

        await row.locator('button[title="Delete picture book"]').click();
        await page.locator('.am7-dialog-footer button.am7-dialog-btn-destructive').click();

        // Benign info toast (info style carries bg-white), NOT a red error toast.
        const infoToast = page.locator('.toast-box').filter({ hasText: 'Already removed' });
        await expect(infoToast, 'no "Already removed" info toast for an already-gone book').toBeVisible({ timeout: 10000 });
        await expect(infoToast).toHaveClass(/bg-white/);
        await expect(page.locator('.toast-box').filter({ hasText: 'Failed to delete' }),
            'the bug is UNFIXED — a red "Failed to delete book" toast appeared for an already-gone book').toHaveCount(0);
    });

    // ── (c) TASK ASSERTION (b) part 3: "the stale row does not reappear." The fix's own comment claims
    //        the always-reload makes it "so a stale row can never persist." Curl proof: after a 404
    //        reset, the orphaned .pictureBookMeta note SURVIVES (reset() 404s before it can locate/delete
    //        the note — it needs the now-gone book group's path). So loadExistingBooks re-finds the note
    //        and the row REAPPEARS. This test encodes the task's expectation (row gone) and is EXPECTED
    //        TO FAIL, surfacing the residual gap for the specialist. It is NOT weakened. ──────────────
    test('c: [expected FAIL / finding] the lingering stale row must not reappear after the idempotent delete', async ({ page, request }) => {
        await restLogin(request);
        const tag = 'stalerow-' + Date.now().toString(36);
        const grp = await makeGroup(request, '~/Data/PictureBooks/' + tag);
        const workName = 'PBDelStaleRow ' + tag;
        const goneBookId = '00000000-beef-4000-8000-' + Date.now().toString(16).padStart(12, '0').slice(-12);
        const noteId = await seedMetaNote(request, grp.id, goneBookId, workName, 2);
        toCleanup.push({ type: 'data.note', objectId: noteId }, { type: 'auth.group', objectId: grp.objectId });

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/picture-book'; });

        const row = page.locator('div.cursor-pointer').filter({ hasText: workName });
        await expect(row, 'seeded stale PB1 row not visible in selector').toBeVisible({ timeout: 15000 });

        await row.locator('button[title="Delete picture book"]').click();
        await page.locator('.am7-dialog-footer button.am7-dialog-btn-destructive').click();

        // Confirm the delete was processed (info toast) before checking the row.
        await expect(page.locator('.toast-box').filter({ hasText: 'Already removed' })).toBeVisible({ timeout: 10000 });
        // Let clearCache + reloadSelectorLists complete.
        await page.waitForTimeout(2000);

        // TASK EXPECTATION: the stale row must be gone. (Currently fails — the orphaned meta note is
        // never deleted on the 404 path, so the row is rebuilt by the reload.)
        await expect(row,
            'RESIDUAL GAP: the lingering .pictureBookMeta row reappears after the idempotent delete — ' +
            'reset() 404 never removes the orphaned meta note, so the reload re-shows the stale row.'
        ).toHaveCount(0, { timeout: 8000 });
    });
});
