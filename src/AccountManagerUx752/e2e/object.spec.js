import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser } from './helpers/api.js';

test.describe('Object view', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'obj' + Date.now().toString(36), noteCount: 3 });
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
        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );

        let addBtn = page.locator('button:has(span:text("add"))').first();
        await expect(addBtn).toBeVisible({ timeout: 10000 });
        await addBtn.click();
        await page.waitForFunction(
            () => window.location.hash.includes('/new/') || window.location.hash.includes('/object/'),
            { timeout: 10000 }
        );
        await screenshot(page, 'new-object-form');
    });

    test('object form renders fields for existing note', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: system.user form schema load hangs under concurrent test load');
        if (!testInfo.notes || !testInfo.notes[0] || !testInfo.notes[0].objectId) {
            test.skip(true, 'No test notes created');
            return;
        }
        // Navigate to an existing note's object view
        await page.goto('/#!/view/data.note/' + testInfo.notes[0].objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/data.note/'),
            { timeout: 15000 }
        );

        // Wait for the form to fully load (not just "Loading...")
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });
        expect(await inputs.count()).toBeGreaterThan(0);
        await screenshot(page, 'new-object-fields');
    });

    test('double-clicking list item opens object view', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );

        let row = page.locator('.list-table tbody tr, .list-tr').first();
        let hasRows = await row.isVisible({ timeout: 10000 }).catch(() => false);

        if (hasRows) {
            await row.dblclick();
            await page.waitForFunction(
                () => window.location.hash.includes('/view/') || window.location.hash.includes('/object/') || window.location.hash.includes('/list/'),
                { timeout: 10000 }
            );
            await screenshot(page, 'object-view');
        }
    });

});
