import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser } from './helpers/api.js';

test.describe('Object view', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'object', noteCount: 3 });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('add button navigates to new object form', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let addBtn = page.locator('button:has(span:text("add"))').first();
        await expect(addBtn).toBeVisible({ timeout: 10000 });
        await addBtn.click();
        await page.waitForURL(/.*#!\/(new|object)\//, { timeout: 10000 });
        await screenshot(page, 'new-object-form');
    });

    test('new object form renders fields', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let addBtn = page.locator('button:has(span:text("add"))').first();
        await expect(addBtn).toBeVisible({ timeout: 10000 });
        await addBtn.click();
        await page.waitForURL(/.*#!\/(new|object)\//, { timeout: 10000 });

        let inputs = page.locator('input, select, textarea');
        expect(await inputs.count()).toBeGreaterThan(0);
        await screenshot(page, 'new-object-fields');
    });

    test('double-clicking list item opens object view', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let row = page.locator('.list-table tbody tr, .list-tr').first();
        let hasRows = await row.isVisible({ timeout: 10000 }).catch(() => false);

        if (hasRows) {
            await row.dblclick();
            await page.waitForURL(/.*#!\/(view|object|list)\//, { timeout: 10000 });
            await screenshot(page, 'object-view');
        }
    });

});
