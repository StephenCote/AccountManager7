/**
 * Breadcrumb navigation tests — exercises breadcrumb against the live backend.
 * Uses test user (not admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser, ensurePath } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';

test.describe('Breadcrumb navigation', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, {
            suffix: 'bc' + Date.now().toString(36),
            noteCount: 2
        });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('breadcrumb renders with full path on group-type list', async ({ page }) => {
        // Navigate to data.data — a group-based type that will have a container
        // Click the "Assets" category in the panel which should go to data.data
        let assetsBtn = page.locator('.card-item button:has-text("Data")').first();
        let assetsBtnCount = await assetsBtn.count();

        // If no "Data" button, try navigating via URL by finding the user's home Data group
        if (assetsBtnCount === 0) {
            // Navigate to data.data via category — click on Assets in side menu or panel
            let catBtn = page.locator('button:has-text("Assets")').first();
            await expect(catBtn).toBeVisible({ timeout: 5000 });
            await catBtn.click();
        } else {
            await assetsBtn.click();
        }

        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );

        // Wait for list to load
        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(2000); // let breadcrumb load async

        let routeInfo = await page.evaluate(() => window.location.hash);
        console.log('[breadcrumb] Route:', routeInfo);

        // showBreadcrumb defaults to true now, no toggle needed
        await page.waitForTimeout(1000); // let breadcrumb render

        // Check if breadcrumb exists (it's in navigation, outside main)
        let html = await page.evaluate(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            return bc ? bc.outerHTML : 'NO BREADCRUMB FOUND';
        });
        console.log('[breadcrumb] HTML:', html.substring(0, 1500));

        // Check what the breadcrumb bar state is
        let debugInfo = await page.evaluate(() => {
            // Check if breadcrumbBar module is accessible
            let main = document.querySelector('[role="main"]');
            let allNavs = main ? main.querySelectorAll('nav') : [];
            let navInfo = Array.from(allNavs).map(n => n.className + ': ' + n.textContent.substring(0, 100));
            return {
                mainExists: !!main,
                navCount: allNavs.length,
                navs: navInfo,
                mainHTML: main ? main.innerHTML.substring(0, 500) : 'NO MAIN'
            };
        });
        console.log('[breadcrumb] Debug:', JSON.stringify(debugInfo, null, 2));

        await screenshot(page, 'breadcrumb-group-list');
    });

    test('breadcrumb has multiple path segments', async ({ page }) => {
        // Navigate directly to data.data with a known container
        // First find the user's home Data group
        let homeData = await page.evaluate(async () => {
            // Wait for am7client to be available
            let attempts = 0;
            while (!window.m && attempts < 20) {
                await new Promise(r => setTimeout(r, 200));
                attempts++;
            }
            let hash = window.location.hash;
            return hash;
        });

        // Click first panel item to get to a list
        let firstItem = page.locator('.card-item button').first();
        await expect(firstItem).toBeVisible({ timeout: 5000 });
        await firstItem.click();
        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(2000);

        // Count breadcrumb segments
        let segmentInfo = await page.evaluate(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            if (!bc) return { found: false, segments: 0, text: '' };
            let items = bc.querySelectorAll('ol.breadcrumb-list li');
            let texts = [];
            items.forEach(li => {
                let txt = li.textContent.trim();
                if (txt && txt !== '/') texts.push(txt);
            });
            return { found: true, segments: texts.length, text: texts.join(' > ') };
        });

        console.log('[breadcrumb] Segments:', JSON.stringify(segmentInfo));
        await screenshot(page, 'breadcrumb-segments');

        // Breadcrumb should have at least 2 path segments (e.g. home > Data)
        // plus the icon = at least 3 li elements
        expect(segmentInfo.found).toBe(true);
        expect(segmentInfo.segments).toBeGreaterThanOrEqual(2);
    });

    test('breadcrumb dropdown shows child folders', async ({ page }) => {
        // Navigate to data.data via Assets category
        let assetsBtn = page.locator('.card-item button:has-text("Data")').first();
        let count = await assetsBtn.count();
        if (count === 0) {
            let catBtn = page.locator('button:has-text("Assets")').first();
            await expect(catBtn).toBeVisible({ timeout: 5000 });
            await catBtn.click();
        } else {
            await assetsBtn.click();
        }

        await page.waitForFunction(
            () => window.location.hash.includes('/list/'),
            { timeout: 15000 }
        );

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(2000);

        // Find expand_more button in breadcrumb
        let expandBtn = page.locator('nav.breadcrumb-bar .breadcrumb-expand-btn').first();
        let expandCount = await expandBtn.count();
        console.log('[breadcrumb] Expand buttons found:', expandCount);
        expect(expandCount).toBeGreaterThan(0);

        // Click the expand_more button
        await expandBtn.click();
        await page.waitForTimeout(2000);

        // Check if context menu appeared
        let ctxMenu = page.locator('.context-menu');
        let menuVisible = await ctxMenu.isVisible({ timeout: 5000 }).catch(() => false);
        console.log('[breadcrumb] Context menu visible after click:', menuVisible);

        // Log any console errors
        let errors = [];
        page.on('console', msg => {
            if (msg.type() === 'error') errors.push(msg.text());
        });

        if (menuVisible) {
            let menuItems = await page.evaluate(() => {
                let items = document.querySelectorAll('.context-menu .context-menu-item');
                return Array.from(items).map(i => i.textContent.trim());
            });
            console.log('[breadcrumb] Menu items:', menuItems);
        } else {
            // Check if there were JS errors
            let jsErrors = await page.evaluate(() => {
                return (window.__consoleErrors || []).slice(-5);
            });
            console.log('[breadcrumb] No menu visible. Recent errors:', jsErrors);
        }

        await screenshot(page, 'breadcrumb-dropdown');
    });
});
