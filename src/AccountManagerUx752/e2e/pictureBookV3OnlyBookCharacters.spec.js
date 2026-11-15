/**
 * §24 UAT-6 — a V3-only picture book answers its legacy read routes and the Manage Characters dialog
 * never hangs on it.
 *
 * Found by §24 U-6's solo re-run of pictureBookNavigation.spec.js (107-u6-pbnav-rerun3.log:18-19): the
 * PB1 read routes GET /olio/picture-book/{id}/scenes and /{id}/characters resolved the book through its
 * PB1 scene data.group only (PictureBookUtil.resolveBookGroupEither), so a V3-only olio.pb.book — every
 * POST /chapter fixture, and any product-created chapter whose extraction has not run — answered 404
 * "Book not found" although the book exists and the caller owns it. The list row's Characters button
 * (src/features/pictureBook.js renderPb2CharactersButton → openCharacterManager) took the 404 at face
 * value: pictureBookCharacters.js refreshList had no catch, so the dialog stayed on "Loading characters…"
 * and the page logged an unhandled rejection ("List characters failed: 404").
 *
 * Now (PictureBookUtil.isReadableV3OnlyBook; pictureBookCharacters.js listError): a readable V3-only book
 * answers with what it has — no PB1 scenes, its world-population characters or none — the dialog shows the
 * empty state, and any failed list (stubbed here as a 500) renders an inline error plus an error toast
 * instead of an unhandled rejection. An absent id still 404s (the server half of that contract, including
 * another user's book, is pinned by Objects7 TestPbV3OnlyBookLegacyReads against the live DB).
 *
 * LLM/SD-free (POST /chapter creates an empty book). Uses ensureSharedTestUser — never admin. The
 * ./helpers/fixtures.js `test` asserts no unexpected console errors or pageerrors after every test, which
 * is exactly the unhandled rejection this item is about.
 *
 * Run (Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookV3OnlyBookCharacters.spec.js \
 *       --workers=1 --project=chromium
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';

// REST login on an arbitrary Playwright request/page.request context (the shared non-admin user).
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
    const body = (await resp.text().catch(() => '')).trim();
    expect(body, 'login returned ' + resp.status() + ' but body was "' + body + '" (credential rejected)').not.toBe('false');
}

// Canonical WS-stub + login pattern (chapBook.spec.js / pictureBookDeleteIdempotent.spec.js) — Docker's
// nginx strips the session cookie on the WS upgrade, so without this stub Tomcat closes the socket,
// forceLogin() fires, and the app redirects to #!/sig.
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

async function openSelector(page) {
    await loginAsSharedUser(page);
    await page.evaluate(() => { window.location.hash = '!/picture-book'; });
    await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: 30000 });
}

function managerDialog(page) {
    return page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Manage Characters' }) }).last();
}

async function closeManager(page, dlg) {
    await dlg.locator('.am7-dialog-footer button').filter({ hasText: 'Close' }).first().click();
    await expect(dlg).toHaveCount(0, { timeout: 10000 });
}

// ── Fixture: ONE V3-only standalone book, created once, deleted in afterAll ───────────────────────
let bookOid = null;
let bookSlug = null;

test.describe('PictureBook — V3-only book: legacy reads + Manage Characters (§24 UAT-6)', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request);
        bookSlug = 'e2e-uat6-' + Date.now().toString(36);
        const resp = await request.post(PB + '/chapter', { data: { slug: bookSlug, title: 'UAT-6 V3-only ' + bookSlug } });
        expect(resp.ok(), 'POST /chapter ' + bookSlug + ' failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
        const b = await resp.json();
        expect(b && b.bookObjectId, 'POST /chapter returned no bookObjectId').toBeTruthy();
        bookOid = b.bookObjectId;
        console.log('[uat6] fixture V3-only book ' + bookOid + ' (' + bookSlug + ')');
    });

    test.afterAll(async ({ request }) => {
        if (!bookOid) return;
        try {
            await restLogin(request);
            const d = await request.delete(PB + '/' + bookOid + '/reset');
            console.log('[uat6] afterAll DELETE /' + bookOid + '/reset -> ' + d.status());
        } catch (e) {
            console.log('[uat6] afterAll cleanup failed: ' + (e && e.message));
        }
    });

    // 1. The server half, through the real REST routes as the owner.
    test('GET /{id}/characters and /{id}/scenes answer 200 for a V3-only book; an absent id still 404s', async ({ request }) => {
        await restLogin(request);

        const chars = await request.get(PB + '/' + bookOid + '/characters');
        expect(chars.status(), 'GET /characters on a V3-only book: ' + (await chars.text().catch(() => ''))).toBe(200);
        const cj = await chars.json();
        expect(Array.isArray(cj), '/characters body is not an array').toBe(true);
        // A bare POST /chapter book has no world population yet.
        expect(cj.length).toBe(0);

        const scenes = await request.get(PB + '/' + bookOid + '/scenes');
        expect(scenes.status(), 'GET /scenes on a V3-only book: ' + (await scenes.text().catch(() => ''))).toBe(200);
        const sj = await scenes.json();
        expect(Array.isArray(sj) && sj.length === 0, '/scenes on a V3-only book must be an empty list, got ' + JSON.stringify(sj).slice(0, 200)).toBe(true);

        // The fix must not turn "not found" into "found": an id that is nobody's book is still a 404.
        const absent = '00000000-0000-4000-8000-' + Date.now().toString(16).padStart(12, '0').slice(-12);
        expect((await request.get(PB + '/' + absent + '/characters')).status()).toBe(404);
        expect((await request.get(PB + '/' + absent + '/scenes')).status()).toBe(404);
        console.log('[uat6] REST characters=' + cj.length + ' scenes=' + sj.length + ' absent=404/404');
    });

    // 2. The product entry point: list row → Characters → the dialog settles on the empty state, with no
    //    unhandled rejection (asserted by the fixtures.js console/pageerror capture after the test).
    test('list row Characters on a V3-only book shows the empty state, not a hung "Loading characters…"', async ({ page }) => {
        await openSelector(page);
        const row = page.locator('[data-pb2-book-list] [data-pb2-book="' + bookOid + '"]');
        await expect(row, 'fixture book row not visible in the PB2 list').toBeVisible({ timeout: 15000 });

        const charsResp = page.waitForResponse((r) => r.url().includes('/picture-book/' + bookOid + '/characters'), { timeout: 30000 });
        await row.locator('[data-pb2-characters="' + bookOid + '"]').click();
        const resp = await charsResp;
        expect(resp.status(), 'the dialog\'s GET /characters answered ' + resp.status()).toBe(200);

        const dlg = managerDialog(page);
        await expect(dlg).toBeVisible({ timeout: 30000 });
        await expect(dlg.locator('[data-testid="pb-chars-empty"]')).toBeVisible({ timeout: 30000 });
        await expect(dlg.locator('[data-testid="pb-chars-empty"]')).toHaveText('No characters extracted yet.');
        await expect(dlg.locator('[data-testid="pb-chars-error"]')).toHaveCount(0);
        await expect(dlg.locator('text=Loading characters…')).toHaveCount(0);
        await closeManager(page, dlg);
    });

    // 3. A failed list is reported, not thrown: the dialog renders the inline error and an error toast.
    test('a failed /characters list renders an inline error and a toast instead of an unhandled rejection', async ({ page }) => {
        await openSelector(page);
        const row = page.locator('[data-pb2-book-list] [data-pb2-book="' + bookOid + '"]');
        await expect(row).toBeVisible({ timeout: 15000 });

        const pattern = '**/picture-book/' + bookOid + '/characters';
        await page.route(pattern, (route) => route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"uat6 stubbed failure"}' }));
        try {
            await row.locator('[data-pb2-characters="' + bookOid + '"]').click();
            const dlg = managerDialog(page);
            await expect(dlg).toBeVisible({ timeout: 30000 });
            const err = dlg.locator('[data-testid="pb-chars-error"]');
            await expect(err).toBeVisible({ timeout: 30000 });
            await expect(err).toContainText('Could not load characters: List characters failed: 500');
            await expect(dlg.locator('[data-testid="pb-chars-empty"]')).toHaveCount(0);
            await expect(dlg.locator('text=Loading characters…')).toHaveCount(0);
            const toast = page.locator('.toast-box[data-toast-type="error"]').filter({ hasText: 'Could not load characters' });
            await expect(toast, 'no error toast for the failed character list').toBeVisible({ timeout: 10000 });
            await closeManager(page, dlg);
        } finally {
            await page.unroute(pattern).catch(() => {});
        }
    });
});
