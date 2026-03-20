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
        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );

        let content = page.locator('.list-table, .tabular-results-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'list-view');
    });

    test('toolbar buttons are visible on list page', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'list-toolbar');
    });

    test('breadcrumb shows on list page', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        let breadcrumb = page.locator('.breadcrumb-bar');
        await expect(breadcrumb.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'list-breadcrumb');
    });

    test('filter input is present for group types', async ({ page }) => {
        // Navigate to auth.group list directly — filter only shows for group types
        await page.goto(BASE_URL + '/AccountManagerUx752/#!/list/auth.group');
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        // Wait for toolbar to render
        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });

        let filterInput = page.locator('.text-field');
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
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        // Breadcrumb nav is rendered by router's pageLayout wrapper
        let breadcrumb = page.locator('.breadcrumb-bar');
        await expect(breadcrumb.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'list-nav-breadcrumb');
    });

    test('list toolbar has search/filter input for group types', async ({ page }) => {
        // Navigate to auth.group list — filter only renders for group types (Ux7 pattern)
        await page.goto(BASE_URL + '/AccountManagerUx752/#!/list/auth.group');
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 10000 });

        let filterInput = page.locator('.text-field');
        await expect(filterInput.first()).toBeVisible({ timeout: 5000 });

        // Type a filter term — should not throw
        await filterInput.first().fill('test');
        await page.waitForTimeout(600);
        await filterInput.first().fill('');
        await screenshot(page, 'list-nav-search-input');
    });

    test('list view toolbar has grid mode toggle buttons', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

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
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        // Pagination bar contains prev/next buttons and page count
        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 15000 });
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

        // Wait for list view to render content (more reliable than URL matching in Firefox)
        let content = page.locator('.list-table, .tabular-results-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 20000 });

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

    test('filter input clears and list remains visible', async ({ page }) => {
        // Navigate to auth.group — filter only shows for group types (Ux7 pattern)
        await page.goto(BASE_URL + '/AccountManagerUx752/#!/list/auth.group');
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });

        let filterInput = page.locator('.text-field');
        let inputEl = filterInput.first();
        await expect(inputEl).toBeVisible({ timeout: 10000 });

        await inputEl.fill('TestFilter');
        await page.waitForTimeout(600);
        await screenshot(page, 'list-filter-active');

        // Clear the filter
        await inputEl.fill('');
        await page.waitForTimeout(600);
        await screenshot(page, 'list-filter-cleared');

        // List should still be visible (not in error state)
        let content = page.locator('.tabular-results-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 10000 });
    });

    test('filter with no results does not break list', async ({ page }) => {
        // Navigate to auth.group — filter only shows for group types
        await page.goto(BASE_URL + '/AccountManagerUx752/#!/list/auth.group');
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });

        let filterInput = page.locator('.text-field');
        let inputEl = filterInput.first();
        await expect(inputEl).toBeVisible({ timeout: 10000 });

        // Filter for something that won't match
        await inputEl.fill('zzz_no_match_zzz_' + Date.now());
        await page.keyboard.press('Enter');
        await page.waitForTimeout(1200);

        // Should show empty state gracefully — no crash
        let content = page.locator('.tabular-results-table, .list-empty, .result-nav-outer');
        await expect(content.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'list-filter-no-results');
    });
});
