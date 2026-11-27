/**
 * Common Admin panel — Admin (/admin/system), Olio Admin (/admin/olio) and Service Status (/admin/status)
 * are tabs of one panel reached from the single "Admin" aside entry (features/systemAdmin/adminTabs.js).
 *
 * Runs as the admin-ROLE test user (`e2etest_featadmin`, never `admin`); the non-admin check uses the
 * shared test user. olioAdmin and serviceStatus are optional feature ids, so the org's stored feature set
 * is extended for the run and restored afterwards (same approach as olioAdminLoad.spec.js). Read-only:
 * no command on any tab is executed.
 *
 * Run against the Docker stack:  PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/adminPanelTabs.spec.js --workers=1 --project=chromium
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureAdminRoleTestUser, ensureSharedTestUser, getOrgFeatures, setOrgFeatures } from './helpers/api.js';

let featAdmin, shared;
let featuresBefore = null;

function asideLabels(page) {
    return page.evaluate(() => Array.from(document.querySelectorAll('button')).map(b => b.textContent.trim()));
}

test.describe('Admin panel — combined tabs', () => {

    test.beforeAll(async ({ request }) => {
        featAdmin = await ensureAdminRoleTestUser(request);
        shared = await ensureSharedTestUser(request);
        expect(featAdmin.roleAssigned, 'the AccountAdministrators role could not be assigned').toBe(true);
        let seen = await getOrgFeatures(request, { userName: featAdmin.testUserName, password: featAdmin.testPassword });
        let want = ['olioAdmin', 'serviceStatus'];
        if (Array.isArray(seen.features) && want.some(f => !seen.features.includes(f))) {
            featuresBefore = seen.features;
            let stored = await setOrgFeatures(request, featuresBefore.concat(want.filter(f => !featuresBefore.includes(f))));
            for (let f of want) expect(stored, 'could not enable ' + f).toContain(f);
        }
    });

    test.afterAll(async ({ request }) => {
        if (featuresBefore) await setOrgFeatures(request, featuresBefore);
    });

    test('one Admin aside entry opens the panel; System / Olio / Service Status tabs switch routes', async ({ page }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await page.goto('/#!/main');
        await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
            .some(b => b.textContent.trim() === 'admin_panel_settingsAdmin'), { timeout: 30000 });

        // The separate Olio Admin and Service Status entries are gone from the menu.
        let labels = await asideLabels(page);
        expect(labels).not.toContain('travel_exploreOlio Admin');
        expect(labels).not.toContain('dnsService Status');

        await page.evaluate(() => {
            Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === 'admin_panel_settingsAdmin').click();
        });
        await page.waitForFunction(() => window.location.hash.includes('/admin/system'), { timeout: 15000 });
        let tabs = page.locator('[data-testid="admin-tabs"]');
        await expect(tabs).toBeVisible({ timeout: 30000 });
        await expect(tabs.locator('[role="tab"]')).toHaveCount(3);
        await expect(page.locator('[data-testid="admin-tab-system"]')).toHaveAttribute('aria-selected', 'true');
        await expect(page.locator('#systemAdminScopeOrg')).toBeVisible({ timeout: 30000 });

        await page.locator('[data-testid="admin-tab-olio"]').click();
        await page.waitForFunction(() => window.location.hash.includes('/admin/olio'), { timeout: 15000 });
        await expect(page.locator('h1', { hasText: 'Olio Admin' })).toBeVisible({ timeout: 30000 });
        await expect(page.locator('[data-testid="admin-tab-olio"]')).toHaveAttribute('aria-selected', 'true');

        await page.locator('[data-testid="admin-tab-status"]').click();
        await page.waitForFunction(() => window.location.hash.includes('/admin/status'), { timeout: 15000 });
        await expect(page.locator('h1', { hasText: 'Service Status' })).toBeVisible({ timeout: 30000 });
        await expect(page.locator('#serviceStatusPanel')).toBeVisible();
        await expect(page.locator('[data-testid="admin-tab-status"]')).toHaveAttribute('aria-selected', 'true');

        await page.locator('[data-testid="admin-tab-system"]').click();
        await page.waitForFunction(() => window.location.hash.includes('/admin/system'), { timeout: 15000 });
        await expect(page.locator('#systemAdminScopeOrg')).toBeVisible({ timeout: 30000 });
    });

    test('a deep link to a tab route still opens that tab inside the panel', async ({ page }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await page.goto('/#!/admin/status');
        await page.waitForFunction(() => window.location.hash.includes('/admin/status'), { timeout: 15000 });
        await expect(page.locator('[data-testid="admin-tab-status"]')).toHaveAttribute('aria-selected', 'true', { timeout: 30000 });
        await expect(page.locator('h1', { hasText: 'Service Status' })).toBeVisible();
    });

    test('a non-admin user gets no Admin entry and no tab bar', async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await page.goto('/#!/main');
        await page.waitForTimeout(2000);
        expect(await asideLabels(page)).not.toContain('admin_panel_settingsAdmin');
        await page.goto('/#!/admin/system');
        await page.waitForFunction(() => window.location.hash.includes('/admin/system'), { timeout: 15000 });
        await page.waitForTimeout(1500);
        await expect(page.locator('[data-testid="admin-tabs"]')).toHaveCount(0);
    });
});
