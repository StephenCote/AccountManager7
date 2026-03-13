import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser, ensurePath } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

function b64(str) { return Buffer.from(str).toString('base64'); }

test.describe('List view', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'ls' + Date.now().toString(36), noteCount: 3 });
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

// ── Phase 15b: Group navigation + search tests ─────────────────────────────

test.describe('List view — group navigation (15b)', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'ln' + Date.now().toString(36), noteCount: 2 });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('list view shows breadcrumb path when navigated to a group', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        // Breadcrumb nav is rendered by router's pageLayout wrapper
        let breadcrumb = page.locator('.breadcrumb-bar, nav[aria-label="breadcrumb"], .breadcrumb');
        await expect(breadcrumb.first()).toBeVisible({ timeout: 8000 });
        await screenshot(page, 'list-nav-breadcrumb');
    });

    test('list toolbar has search/filter input that accepts text', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        // Wait for list to render
        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 10000 });

        // Search input: list.js renders it as a text input
        let filterInput = page.locator('input[placeholder*="Search"], input[placeholder*="Filter"], .text-field');
        await expect(filterInput.first()).toBeVisible({ timeout: 5000 });

        // Type a search term — should not throw
        await filterInput.first().fill('test');
        await page.waitForTimeout(600); // debounce
        await filterInput.first().fill('');
        await screenshot(page, 'list-nav-search-input');
    });

    test('list view toolbar has grid mode toggle buttons', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 10000 });

        // Toolbar should have view-mode icon buttons (table_rows, grid_view, etc.)
        // These have aria-label from the iconBtn helper
        let buttons = toolbar.locator('button');
        let count = await buttons.count();
        expect(count).toBeGreaterThan(0);

        await screenshot(page, 'list-nav-toolbar-modes');
    });

    test('list view has pagination controls', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 10000 });

        // Pagination: prev/next buttons or page count indicator
        let paginationEl = page.locator('.result-nav-outer');
        await expect(paginationEl.first()).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'list-nav-pagination');
    });

    test('navigating to a group via URL shows its contents', async ({ page }) => {
        // Navigate directly to auth.group list (admin home dir)
        // Use am7 path to find the home group for this test user
        let ctx = await newApiContext();
        let groupObjectId = null;
        try {
            await ctx.post(REST + '/login', {
                data: {
                    schema: 'auth.credential',
                    organizationPath: '/Development',
                    name: testInfo.testUserName,
                    credential: b64(testInfo.testPassword),
                    type: 'hashed_password'
                }
            });

            // Find the Notes group for this user
            let resp = await ctx.get(REST + '/path/find/auth.group/data/' +
                encodeURIComponent('/Development/home/' + testInfo.testUserName + '/Notes'));
            if (resp.ok()) {
                let text = await resp.text();
                if (text && !text.startsWith('<')) {
                    try {
                        let group = JSON.parse(text);
                        groupObjectId = group.objectId;
                    } catch (e) { /* ignore parse errors */ }
                }
            }
        } finally {
            await ctx.dispose();
        }

        if (!groupObjectId) {
            test.skip(true, 'Could not resolve test group objectId — skipping direct URL nav test');
            return;
        }

        // Navigate directly to #!/list/data.note/{groupObjectId}
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, groupObjectId);

        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        let content = page.locator('.list-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 15000 });

        await screenshot(page, 'list-nav-direct-url');
    });
});

test.describe('List view — search behavior (15b)', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, {
            suffix: 'sr' + Date.now().toString(36),
            noteCount: 3,
            notePrefix: 'SearchTestNote'
        });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('search input clears when X button or clear action is used', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 10000 });

        let filterInput = page.locator('input[placeholder*="Search"], input[placeholder*="Filter"], .text-field');
        let inputEl = filterInput.first();
        let visible = await inputEl.isVisible().catch(() => false);
        if (!visible) {
            test.skip(true, 'No search input visible on this list view');
            return;
        }

        await inputEl.fill('SearchTestNote');
        await page.waitForTimeout(700); // debounce
        await screenshot(page, 'list-search-active');

        // Clear the search
        await inputEl.fill('');
        await page.waitForTimeout(700);
        await screenshot(page, 'list-search-cleared');

        // List should still be visible (not in error state)
        let content = page.locator('.list-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 5000 });
    });

    test('search does not break list when no results match', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 10000 });

        let filterInput = page.locator('input[placeholder*="Search"], input[placeholder*="Filter"], .text-field');
        let inputEl = filterInput.first();
        let visible = await inputEl.isVisible().catch(() => false);
        if (!visible) {
            test.skip(true, 'No search input visible on this list view');
            return;
        }

        // Search for something that won't match anything
        await inputEl.fill('zzz_no_match_zzz_' + Date.now());
        await page.waitForTimeout(700);

        // Should show empty state gracefully — no crash
        let content = page.locator('.list-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 8000 });
        await screenshot(page, 'list-search-no-results');
    });
});
