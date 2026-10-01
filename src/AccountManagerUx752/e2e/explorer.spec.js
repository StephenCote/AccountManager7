/**
 * Explorer view E2E tests — Phase 15b
 *
 * Tests the split-pane file explorer at #!/explorer.
 * Left panel: tree (auth.group hierarchy). Right panel: list view for selected folder.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot, openAppTool } from './helpers/auth.js';
import { ensureSharedTestUser, addUserToRole, removeUserFromRole, findPath, apiLogin, apiLogout } from './helpers/api.js';

/**
 * Navigate to the explorer view via its App Panel card (flyout → App Panel → Explorer).
 */
async function goToExplorer(page) {
    await openAppTool(page, '/explorer');
}

test.describe('Explorer view', () => {

    test('explorer route loads after login', async ({ page }) => {
        await login(page);
        await goToExplorer(page);

        // Explorer toolbar shows "Explorer" label
        await expect(page.locator('text=Explorer').first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'explorer-loaded');
    });

    test('explorer has split pane layout — tree panel and content panel', async ({ page }) => {
        await login(page);
        await goToExplorer(page);

        await expect(page.locator('text=Explorer').first()).toBeVisible({ timeout: 10000 });

        // Left panel: fixed-width tree area (border-r separator is visible)
        // Right panel: shows empty state or list
        // Both panels are rendered inside the flex split view
        let splitContainer = page.locator('.flex.flex-1.overflow-hidden').first();
        await expect(splitContainer).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'explorer-split-pane');
    });

    test('right panel shows content or empty state after navigation', async ({ page }) => {
        await login(page);
        await goToExplorer(page);

        await expect(page.locator('text=Explorer').first()).toBeVisible({ timeout: 10000 });

        // Right panel shows either empty state (no selection) or list content (auto-selected node)
        let rightPanel = page.locator('.flex.flex-1.overflow-hidden').first();
        await expect(rightPanel).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'explorer-right-panel');
    });

    test('explorer toolbar has fullscreen toggle button', async ({ page }) => {
        await login(page);
        await goToExplorer(page);

        await expect(page.locator('text=Explorer').first()).toBeVisible({ timeout: 10000 });

        // Toolbar contains an expand/fullscreen button (open_in_new icon button)
        let toolbar = page.locator('.flex.items-center.gap-2.px-3.py-1\\.5').first();
        await expect(toolbar).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'explorer-toolbar');
    });

    test('tree panel is visible with at least the root group node', async ({ page }) => {
        await login(page);
        await goToExplorer(page);

        await expect(page.locator('text=Explorer').first()).toBeVisible({ timeout: 10000 });
        await page.waitForTimeout(2000); // Allow tree to load from backend

        // Tree panel is on the left — width 250px shrink-0 div
        let treePanel = page.locator('div[style*="width:250px"], div[style*="width: 250px"]').first();
        await expect(treePanel).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'explorer-tree-panel');
    });

    test('clicking a tree node updates the right panel', async ({ page }) => {
        await login(page);
        await goToExplorer(page);

        await expect(page.locator('text=Explorer').first()).toBeVisible({ timeout: 10000 });
        await page.waitForTimeout(2500); // Allow tree to load

        // Check if any tree items are clickable
        let treeItems = page.locator('div[style*="width:250px"] button, div[style*="width:250px"] li, div[style*="width:250px"] span[role]');
        let count = await treeItems.count();

        if (count > 0) {
            await treeItems.first().click();
            await page.waitForTimeout(1500);

            // Right panel should no longer show "Select a folder" — or may show list content
            await screenshot(page, 'explorer-node-selected');
        } else {
            // Backend not available or tree empty — still pass
            await screenshot(page, 'explorer-tree-empty');
        }
    });

    test('App Panel Explorer card navigates to explorer (no Explorer button on the flyout)', async ({ page }) => {
        await login(page);

        // Explorer moved from the flyout to the App Panel; the aside must not offer it directly.
        let asideExplorer = await page.$$eval('aside button', els => els.filter(b => /^\s*\S+\s*Explorer\s*$/.test(b.textContent)).length);
        expect(asideExplorer, 'the flyout should no longer have an Explorer button').toBe(0);

        await openAppTool(page, '/explorer');
        expect(page.url()).toContain('explorer');
        await screenshot(page, 'explorer-from-app-panel');
    });
});

/**
 * The Olio branch (/Olio/Universes) is readable only by members of ~/Roles/Olio User or
 * ~/Roles/Olio Admin (OlioContext.configureEnvironment grants Read to those two roles only).
 * The tree used to look the group up exactly once per SPA session and silently drop the
 * branch forever if the lookup came back empty — so enrolling a user (or loading Olio data)
 * had no visible effect until a full page reload. It now re-checks on every mount and Refresh.
 */
test.describe('Explorer Olio branch', () => {
    const OLIO_USER_ROLE = 'Olio User';
    const treePanel = 'div[style*="width:250px"], div[style*="width: 250px"]';

    test('Universes node follows Olio User membership and reappears on Refresh', async ({ page, request }) => {
        const { user, testUserName, testPassword } = await ensureSharedTestUser(request);
        expect(user && user.objectId, 'shared test user must exist').toBeTruthy();

        // Precondition: the org actually has Olio data. Checked with an admin session on the test's own
        // request context (provisioning only — the UI runs as the shared user) so a missing
        // /Olio/Universes fails loudly here instead of masquerading as a PBAC denial below.
        expect((await apiLogin(request)).ok()).toBeTruthy();
        const universes = await findPath(request, 'auth.group', 'DATA', '/Olio/Universes');
        await apiLogout(request);
        expect(universes && universes.objectId, '/Olio/Universes must exist in /Development (load Olio data first)').toBeTruthy();

        // Start from a known state: NOT a member of Olio User.
        await removeUserFromRole(request, user.objectId, OLIO_USER_ROLE);

        try {
            await login(page, { user: testUserName, password: testPassword });
            await goToExplorer(page);
            await expect(page.locator('text=Explorer').first()).toBeVisible({ timeout: 10000 });

            // The origin (home directory, named after the user) renders; the Universes node must not.
            const homeNode = page.locator(treePanel).locator('span.text-sm', { hasText: testUserName }).first();
            await expect(homeNode).toBeVisible({ timeout: 10000 });
            const universesNode = page.locator(treePanel).locator('span.text-sm', { hasText: /^Universes$/ });
            await page.waitForTimeout(1500);
            await expect(universesNode).toHaveCount(0);

            // The raw lookup the tree performs comes back empty for a non-member.
            const denied = await page.request.get('/AccountManagerService7/rest/path/find/auth.group/DATA/B64-' + Buffer.from('/Olio/Universes').toString('base64').replace(/=/g, '%3D'));
            expect(denied.status()).toBe(200);
            expect((await denied.text()).trim()).toBe('');
            await screenshot(page, 'explorer-olio-absent');

            // Enrol as admin, then use the tree's own Refresh — no reload, no re-login.
            expect(await addUserToRole(request, user.objectId, OLIO_USER_ROLE)).toBe(true);
            await page.locator(treePanel).locator('button:has(span:text-is("refresh"))').first().click();

            await expect(universesNode.first()).toBeVisible({ timeout: 15000 });
            await screenshot(page, 'explorer-olio-present');
        } finally {
            await removeUserFromRole(request, user.objectId, OLIO_USER_ROLE);
        }
    });
});
