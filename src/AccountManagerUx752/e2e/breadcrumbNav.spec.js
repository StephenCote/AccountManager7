/**
 * Breadcrumb navigation — test that selecting a group from dropdown
 * navigates to it and stays there (doesn't reset to home).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';

test.describe('Breadcrumb dropdown navigation', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, {
            suffix: 'bn' + Date.now().toString(36),
            noteCount: 2
        });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('clicking breadcrumb segment navigates and stays on that group', async ({ page }) => {
        // Navigate to data.data via Assets
        let assetsBtn = page.locator('.card-item button:has-text("Data")').first();
        let count = await assetsBtn.count();
        if (count === 0) {
            await page.locator('button:has-text("Assets")').first().click();
        } else {
            await assetsBtn.click();
        }
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });
        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(2000);

        // Get the initial route
        let initialRoute = await page.evaluate(() => window.location.hash);
        console.log('[nav] Initial route:', initialRoute);

        // Find a clickable breadcrumb segment (multi-button, not disabled)
        let navBtn = page.locator('nav.breadcrumb-bar .multi-button.rounded-l').first();
        let navBtnCount = await navBtn.count();
        console.log('[nav] Clickable breadcrumb segments:', navBtnCount);

        if (navBtnCount > 0) {
            let segName = await navBtn.textContent();
            console.log('[nav] Clicking segment:', segName);

            await navBtn.click();
            await page.waitForTimeout(3000);

            let newRoute = await page.evaluate(() => window.location.hash);
            console.log('[nav] Route after click:', newRoute);

            // Wait a bit more for any resets
            await page.waitForTimeout(2000);

            let finalRoute = await page.evaluate(() => window.location.hash);
            console.log('[nav] Route after settle:', finalRoute);

            // Route should NOT be /main
            expect(finalRoute).not.toContain('/main');
            // Route should contain /list/
            expect(finalRoute).toContain('/list/');
        }

        await screenshot(page, 'breadcrumb-nav-segment');
    });

    test('clicking expand_more dropdown item navigates to child group', async ({ page }) => {
        // Navigate to data.data
        let assetsBtn = page.locator('.card-item button:has-text("Data")').first();
        let count = await assetsBtn.count();
        if (count === 0) {
            await page.locator('button:has-text("Assets")').first().click();
        } else {
            await assetsBtn.click();
        }
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });
        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(2000);

        let initialRoute = await page.evaluate(() => window.location.hash);
        console.log('[nav] Initial route:', initialRoute);

        // Find the expand_more button on a non-disabled segment
        let expandBtns = page.locator('nav.breadcrumb-bar .multi-button.rounded-r');
        let expandCount = await expandBtns.count();
        console.log('[nav] Expand buttons:', expandCount);

        if (expandCount > 0) {
            // Click the first expand_more button
            await expandBtns.first().click();
            await page.waitForTimeout(2000);

            // Check for dropdown menu items
            let menuItems = page.locator('.context-menu-48 button.context-menu-item');
            let menuCount = await menuItems.count();
            console.log('[nav] Menu items:', menuCount);

            // Get menu item names
            let itemNames = [];
            for (let i = 0; i < menuCount; i++) {
                itemNames.push(await menuItems.nth(i).textContent());
            }
            console.log('[nav] Menu item names:', itemNames);

            if (menuCount > 0) {
                // Click the first real menu item (not "Loading")
                let firstReal = null;
                for (let i = 0; i < menuCount; i++) {
                    let txt = await menuItems.nth(i).textContent();
                    if (!txt.includes('Loading')) {
                        firstReal = menuItems.nth(i);
                        console.log('[nav] Clicking menu item:', txt);
                        break;
                    }
                }

                if (firstReal) {
                    await firstReal.click();
                    await page.waitForTimeout(3000);

                    let newRoute = await page.evaluate(() => window.location.hash);
                    console.log('[nav] Route after menu click:', newRoute);

                    // Wait for any resets
                    await page.waitForTimeout(2000);

                    let finalRoute = await page.evaluate(() => window.location.hash);
                    console.log('[nav] Final route:', finalRoute);

                    // Should NOT reset to main
                    expect(finalRoute).not.toContain('/main');
                    expect(finalRoute).toContain('/list/');
                }
            }
        }

        await screenshot(page, 'breadcrumb-dropdown-nav');
    });
});
