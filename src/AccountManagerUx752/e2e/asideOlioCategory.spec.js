/**
 * Flyout (aside) nav — the "Olio" category button (src/components/asideMenu.js navigateToCategory).
 *
 * Bug: the hand-curated `olio` category in core/modelDef.js has no `group`, and navigateToCategory
 * fell back to `~/<cat.name>`, so clicking "Olio" created an empty `~/olio` group for the user and
 * listed it — a list that shows nothing. Fix: resolve the category's first model through
 * am7view.pathForType(type) (the model's own default folder: `~/Characters` for olio.charPerson),
 * exactly as panel.clickPanelItem does. The `policy` (~/policy vs ~/Policies) and `ai` (~/ai vs
 * ~/Memories) categories had the same fallback.
 *
 * Proves, in a real browser as the shared (non-admin) user:
 *   - clicking "Olio" in the aside lands on #!/list/olio.charPerson/<objectId of ~/Characters>;
 *   - the list view renders for that group;
 *   - no `~/olio` group exists afterwards (a stale one from the old behavior is removed first so the
 *     "not created" assertion is not vacuous).
 * The category is feature-tagged `cardGame`; the suite asserts the org config enables it rather than
 * mutating the organization's feature set.
 *
 * Run (Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/asideOlioCategory.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { apiLogin, ensureSharedTestUser, findPath, deleteObject, getOrgFeatures } from './helpers/api.js';

let shared = null;

async function restLogin(ctx) {
    const resp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(resp.ok() || resp.status() === 204, 'shared user login failed: ' + resp.status()).toBe(true);
}

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

test.describe('Aside nav — Olio category opens the Characters list, not a stray ~/olio group', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        const feats = await getOrgFeatures(request, { userName: shared.testUserName, password: shared.testPassword });
        expect(feats.status, 'GET /rest/config/features failed').toBe(200);
        expect(feats.features, 'the olio category is feature-tagged cardGame; the org config must enable it for this test to mean anything')
            .toContain('cardGame');

        await restLogin(request);
        const stale = await findPath(request, 'auth.group', 'data', '~/olio');
        if (stale && stale.objectId) {
            // Left over from the old fallback; remove it so "not created" below is a real assertion.
            await deleteObject(request, 'auth.group', stale.objectId);
            expect(await findPath(request, 'auth.group', 'data', '~/olio'), 'stale ~/olio could not be removed').toBeNull();
        }
    });

    test('clicking Olio routes to /list/olio.charPerson/<~/Characters> and creates no ~/olio', async ({ page }) => {
        const pageErrors = [];
        page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));

        await loginAsSharedUser(page);

        const menuToggle = page.locator('div.menuToggle button').first();
        await expect(menuToggle, 'drawer toggle not visible').toBeVisible({ timeout: 10000 });
        await menuToggle.click();
        const aside = page.locator('aside.transition');
        await expect(aside).toHaveClass(/transition-full/, { timeout: 5000 });

        const olioBtn = aside.locator('button', { has: page.locator('span:not([class])', { hasText: /^Olio$/ }) });
        await expect(olioBtn, 'no "Olio" category button in the aside').toHaveCount(1);
        await olioBtn.click();

        await page.waitForFunction(() => /#!\/list\/olio\.charPerson\/[0-9A-Za-z-]+/.test(window.location.hash), { timeout: 20000 });
        const hash = await page.evaluate(() => window.location.hash);
        const routedOid = hash.replace(/^#!\/list\/olio\.charPerson\//, '');

        const characters = await findPath(page.request, 'auth.group', 'data', '~/Characters');
        expect(characters && characters.objectId, '~/Characters must resolve for the shared user').toBeTruthy();
        expect(routedOid, 'Olio must list the model\'s default folder ~/Characters').toBe(characters.objectId);

        await expect(page.locator('.list-results-container'), 'list view did not render').toBeVisible({ timeout: 20000 });
        await expect(aside, 'drawer should close after navigating').toHaveClass(/transition-0/, { timeout: 5000 });

        expect(await findPath(page.request, 'auth.group', 'data', '~/olio'), 'a stray ~/olio group was created').toBeNull();
        expect(pageErrors, 'uncaught page errors: ' + pageErrors.join(' | ')).toEqual([]);
    });
});
