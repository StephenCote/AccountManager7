import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';

test.describe('Test Harness feature', () => {

    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('test button in top menu navigates to test page', async ({ page }) => {
        // Tests button is devOnly — should be visible in dev mode
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

    test('home button returns from test page to dashboard', async ({ page }) => {
        await page.locator('button[title="Tests"]').click();
        await page.waitForURL(/.*#!\/test/, { timeout: 10000 });
        await page.waitForTimeout(1000);
        await page.locator('button[title="Home"]').click();
        await page.waitForURL(/.*#!\/main/, { timeout: 10000 });
        await expect(page.locator('.panel-container').first()).toBeVisible();
        await screenshot(page, 'test-home-return');
    });

});
