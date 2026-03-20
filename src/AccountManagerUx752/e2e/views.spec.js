/**
 * View Console Error Smoke Tests
 *
 * Navigates to each major view and relies on the fixtures' automatic
 * console error capture to fail if any unexpected errors or warnings
 * are emitted. This catches runtime crashes, missing imports, bad API
 * responses, and other issues that only surface when the view renders.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser } from './helpers/api.js';

test.describe('View console errors', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'vw' + Date.now().toString(36), noteCount: 2 });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('dashboard (/main) renders without console errors', async ({ page }) => {
        // Already on /main after login — wait for dashboard content
        await expect(page.locator('.panel-grid, .panel-container').first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'views-dashboard');
    });

    test('list view renders without console errors', async ({ page }) => {
        // Click first dashboard card to navigate to a list view
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 10000 });
        await firstItem.click();
        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );
        // Wait for list content to render (table or empty state)
        await page.waitForTimeout(2000);
        await screenshot(page, 'views-list');
    });

    test('list view with group navigation renders without console errors', async ({ page }) => {
        // Navigate to data.note list — a type that has group navigation with child groups
        await page.goto('/#!/list/data.note');
        await page.waitForFunction(
            () => window.location.hash.includes('/list/data.note'),
            { timeout: 15000 }
        );
        // Wait for group loading and list render
        await page.waitForTimeout(3000);
        await screenshot(page, 'views-list-notes');
    });

    test('object view renders without console errors', async ({ page }) => {
        // Navigate to a list, then open the first item
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 10000 });
        await firstItem.click();
        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );

        // Try to double-click first row to open object view
        let row = page.locator('.list-table tbody tr, .list-tr').first();
        let hasRows = await row.isVisible({ timeout: 10000 }).catch(() => false);
        if (hasRows) {
            await row.dblclick();
            await page.waitForFunction(
                () => window.location.hash.includes('/view/') || window.location.hash.includes('/object/'),
                { timeout: 10000 }
            );
            await page.waitForTimeout(2000);
            await screenshot(page, 'views-object');
        }
    });

    test('object form renders fields without console errors', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form schema load hangs under concurrent test load');
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

        // Wait for form fields to load
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });
        await screenshot(page, 'views-new-object');
    });

    test('navigator view renders without console errors', async ({ page }) => {
        await page.goto('/#!/nav');
        await page.waitForFunction(
            () => window.location.hash.includes('/nav'),
            { timeout: 15000 }
        );
        await page.waitForTimeout(2000);
        await screenshot(page, 'views-navigator');
    });

    test('explorer view renders without console errors', async ({ page }) => {
        await page.goto('/#!/explorer');
        await page.waitForFunction(
            () => window.location.hash.includes('/explorer'),
            { timeout: 15000 }
        );
        await page.waitForTimeout(2000);
        await screenshot(page, 'views-explorer');
    });

    test('multiple list types render without console errors', async ({ page }) => {
        // Navigate through several common object types
        let types = ['data.note', 'data.tag', 'system.user'];

        for (let type of types) {
            await page.goto('/#!/list/' + type);
            await page.waitForFunction(
                (t) => window.location.hash.includes('/list/' + t),
                type,
                { timeout: 15000 }
            );
            // Allow list to load and any child group queries to complete
            await page.waitForTimeout(2000);
        }
        await screenshot(page, 'views-multi-list');
    });
});
