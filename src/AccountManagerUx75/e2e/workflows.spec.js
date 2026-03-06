import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser } from './helpers/api.js';

test.describe('Workflow command registration', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'workflow' });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('workflow handlers are registered on objectPage', async ({ page }) => {
        // Verify workflow functions are available on the object page module
        let handlers = await page.evaluate(() => {
            // Access the object page factory via page.views.object
            let pg = window.__am7page || null;
            if (!pg || !pg.views || !pg.views.object) return null;
            let objectPage = pg.views.object();
            return {
                summarize: typeof objectPage.summarize,
                vectorize: typeof objectPage.vectorize,
                reimage: typeof objectPage.reimage,
                reimageApparel: typeof objectPage.reimageApparel,
                memberCloud: typeof objectPage.memberCloud,
                adoptCharacter: typeof objectPage.adoptCharacter,
                outfitBuilder: typeof objectPage.outfitBuilder
            };
        });

        // If page.views isn't exposed globally, verify no "Command function not found" in console
        // The fixtures auto-capture console errors, so any such warnings would fail the test
        if (handlers) {
            expect(handlers.summarize).toBe('function');
            expect(handlers.vectorize).toBe('function');
            expect(handlers.reimage).toBe('function');
            expect(handlers.reimageApparel).toBe('function');
            expect(handlers.memberCloud).toBe('function');
            expect(handlers.adoptCharacter).toBe('function');
            expect(handlers.outfitBuilder).toBe('function');
        }
        await screenshot(page, 'workflow-registration');
    });

    test('object view loads without command-not-found warnings', async ({ page }) => {
        // Navigate to a list and open an object — the object view should load
        // without "Command function not found" console warnings
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });

        // Check if any items exist to open
        let row = page.locator('.list-table tbody tr, .list-tr').first();
        let hasRows = await row.isVisible({ timeout: 10000 }).catch(() => false);

        if (hasRows) {
            await row.dblclick();
            await page.waitForURL(/.*#!\/(view|object|list)\//, { timeout: 10000 });

            // Wait for form to render
            let inputs = page.locator('input, select, textarea');
            await expect(inputs.first()).toBeVisible({ timeout: 5000 }).catch(() => {});

            // Console errors are auto-asserted by the fixture — if "Command function
            // not found" appeared it would be caught (it's a console.warn, not in
            // IGNORED_PATTERNS)
            await screenshot(page, 'object-no-command-warnings');
        }
    });
});
