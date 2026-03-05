import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser } from './helpers/api.js';

test.describe('Panel dashboard', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, { suffix: 'panel', noteCount: 1 });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('dashboard shows category cards', async ({ page }) => {
        await expect(page.locator('.panel-grid')).toBeVisible({ timeout: 10000 });
        let cards = page.locator('.panel-card, .card-contents');
        expect(await cards.count()).toBeGreaterThan(0);
        await screenshot(page, 'panel-categories');
    });

    test('clicking a category item navigates to list', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });
        await screenshot(page, 'panel-to-list');
    });

    test('home button returns to dashboard', async ({ page }) => {
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForURL(/.*#!\/list\//, { timeout: 15000 });
        await page.locator('button[title="Home"]').click();
        await page.waitForURL(/.*#!\/main/, { timeout: 10000 });
        await expect(page.locator('.panel-container').first()).toBeVisible();
        await screenshot(page, 'home-return');
    });

    test('dark mode toggle works', async ({ page }) => {
        await page.locator('button[title="Toggle dark mode"]').click();
        let html = page.locator('html');
        await expect(html).toHaveClass(/dark/);
        await screenshot(page, 'panel-dark-mode');
        await page.locator('button[title="Toggle dark mode"]').click();
        await expect(html).not.toHaveClass(/dark/);
    });

    test('feature buttons appear in top menu', async ({ page }) => {
        // Wait for at least one menu button to be visible (home, dark_mode, logout + features)
        await expect(page.locator('.menu-button').first()).toBeVisible({ timeout: 5000 });
        let menuButtons = page.locator('.menu-button');
        expect(await menuButtons.count()).toBeGreaterThan(2);
        await screenshot(page, 'feature-buttons');
    });

});
