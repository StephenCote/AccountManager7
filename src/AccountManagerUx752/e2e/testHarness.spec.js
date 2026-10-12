/**
 * devOnly test harness (#!/test) — the "Tests" button in the top menu and the harness view.
 *
 * The harness is gated on page.devMode = !!import.meta.env.DEV (core/pageClient.js:887), which is
 * false in the production dist that the Docker nginx serves. It exists only on a Vite dev server, so
 * this spec is listed in VITE_SOURCE_SPECS (playwright.config.js) and runs under the `chromium-vite`
 * project. If it is ever pointed at a production build, the guard below skips with the reason
 * rather than failing on a button the build does not contain.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Test Harness feature', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        // window.am7page is exposed by main.js:85; devMode is the single definition of "dev build".
        const devMode = await page.evaluate(() => !!(window.am7page && window.am7page.devMode));
        console.log('[testHarness] page.devMode=' + devMode + ' at ' + page.url());
        test.skip(!devMode, 'devOnly test harness is not built into the production dist');
    });

    test('test button in top menu navigates to test page', async ({ page }) => {
        // Tests button is devOnly — visible in dev mode (guarded in beforeEach)
        let testBtn = page.locator('button[title="Tests"]');
        await expect(testBtn).toBeVisible({ timeout: 5000 });
        await testBtn.click();
        await page.waitForURL(/.*#!\/test/, { timeout: 10000 });
        await screenshot(page, 'test-via-menu');
    });

    test('test framework renders UI components', async ({ page }) => {
        await page.locator('button[title="Tests"]').click();
        await page.waitForURL(/.*#!\/test/, { timeout: 10000 });
        // Wait for the test view to render its components
        await page.waitForTimeout(2000);
        // The test view should render content (not the dashboard)
        await expect(page.locator('.panel-container')).not.toBeVisible({ timeout: 3000 });
        await screenshot(page, 'test-framework-ui');
    });

    test('test framework shows run button and categories', async ({ page }) => {
        await page.locator('button[title="Tests"]').click();
        await page.waitForURL(/.*#!\/test/, { timeout: 10000 });
        await page.waitForTimeout(2000);
        // Run Tests button should be visible
        await expect(page.locator('text=Run Tests')).toBeVisible({ timeout: 5000 });
        // Category toggle buttons should be present
        await expect(page.locator('text=All')).toBeVisible();
        await screenshot(page, 'test-run-button');
    });

});
