/**
 * ISO 42001 Ux views (Phase 8) — UI render checks against the running stack (Vite + Service7/Tomcat).
 * Logs in as the provisioned non-admin ISO-role user (ensureIso42001TestUser — never admin) so the role-gated
 * controls render. Each navigation also passes through fixtures.js' console-error guard, so a render regression
 * in any of the 5 views (or in the shared router/features/modelDef changes) fails the test.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureIso42001TestUser } from './helpers/api.js';

test.describe('ISO 42001 Ux views (live)', () => {
    let iso = {};

    test.beforeAll(async ({ request }) => {
        iso = await ensureIso42001TestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: iso.testUserName, password: iso.testPassword });
    });

    async function goto(page, route) {
        await page.goto('/?features=full#!' + route);
    }

    test('dashboard (/compliance) renders', async ({ page }) => {
        await goto(page, '/compliance');
        await expect(page.locator('h1', { hasText: 'ISO 42001 Compliance Dashboard' })).toBeVisible({ timeout: 15000 });
        await expect(page.getByText('System Status')).toBeVisible();
        await expect(page.getByText('Training Bias Overcorrection Active')).toBeVisible();
        await screenshot(page, 'iso42001-dashboard');
    });

    test('test runner (/iso42001/run) renders', async ({ page }) => {
        await goto(page, '/iso42001/run');
        await expect(page.locator('h2', { hasText: 'Test Runs' })).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'iso42001-testrunner');
    });

    test('results browser (/iso42001/results/:runId) renders gracefully for an unknown run', async ({ page }) => {
        await goto(page, '/iso42001/results/none-such-run');
        // The view renders its header + a graceful empty state (no crash) for an unknown run.
        await expect(page.getByRole('heading', { name: 'Results' })).toBeVisible({ timeout: 15000 });
        await expect(page.getByText('No results for this run.')).toBeVisible();
        await screenshot(page, 'iso42001-results');
    });

    test('report viewer (/iso42001/report) renders', async ({ page }) => {
        await goto(page, '/iso42001/report');
        await expect(page.locator('h2', { hasText: 'Compliance Reports' })).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'iso42001-reports');
    });

    test('certification queue (/iso42001/cert) renders', async ({ page }) => {
        await goto(page, '/iso42001/cert');
        await expect(page.locator('h2', { hasText: 'Certification Queue' })).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'iso42001-cert');
    });
});
