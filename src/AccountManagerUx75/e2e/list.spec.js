import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser } from './helpers/api.js';

test.describe('List view', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'list', noteCount: 3 });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('navigating from panel shows list or empty state', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let content = page.locator('.list-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'list-view');
    });

    test('toolbar buttons are visible on list page', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'list-toolbar');
    });

    test('breadcrumb shows on list page', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let breadcrumb = page.locator('.breadcrumb-bar, nav.breadcrumb-bar');
        await expect(breadcrumb.first()).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'list-breadcrumb');
    });

    test('filter input is present', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let filterInput = page.locator('.text-field, input[placeholder*="Filter"]');
        await expect(filterInput.first()).toBeVisible({ timeout: 10000 });
    });

});
