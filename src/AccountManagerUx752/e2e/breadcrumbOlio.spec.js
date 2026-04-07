/**
 * Breadcrumb Olio navigation — full depth test:
 * Navigate /Olio/Universes → My Grid Universe → Worlds → My Grid World
 * Verify breadcrumb shows correct path at EACH step (not home fallback)
 * Verify route doesn't bounce
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';

test.describe('Breadcrumb Olio full navigation', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        page.on('console', msg => {
            let t = msg.type();
            let txt = msg.text();
            if (t === 'error' || txt.includes('[breadcrumb]') || txt.includes('[listByType]') || txt.includes('[route.set]')) {
                console.log('[browser ' + t + ']', txt);
            }
        });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('navigate through group hierarchy via breadcrumb dropdowns', async ({ page }) => {
        // Wait for app to fully initialize (WebSocket reconnect, etc.)
        await page.waitForTimeout(5000);

        // Navigate to data list via panel to get into the app
        let dataBtn = page.locator('.card-item button').first();
        await expect(dataBtn).toBeVisible({ timeout: 5000 });
        await dataBtn.click();
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });
        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(2000);

        // Create test group hierarchy and navigate to it
        await page.evaluate(async () => {
            // Access page object through the module system
            let usr = await new Promise(r => window.am7client.principal(r));
            if (!usr) return;
            let homePath = usr.homeDirectory.path;
            await new Promise(resolve => {
                window.am7client.make('auth.group', 'data', homePath + '/BcTest/Sub1/Sub2', resolve);
            });
            window.am7client.clearCache('auth.group', true);
            let result = await new Promise(resolve => {
                window.am7client.find('auth.group', 'data', homePath + '/BcTest', resolve);
            });
            if (result && result.objectId) {
                window.m.route.set('/list/data.data/' + result.objectId);
            }
        });
        await page.waitForTimeout(3000);

        let route = await page.evaluate(() => window.location.hash);
        console.log('[1-BcTest] Route:', route);
        expect(route).toContain('/list/');

        // Wait for breadcrumb context to load
        await page.waitForFunction(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            return bc && bc.textContent.includes('BcTest');
        }, { timeout: 10000 }).catch(() => {});

        let bc = await getBreadcrumbInfo(page);
        console.log('[1-BcTest] Breadcrumb:', JSON.stringify(bc));
        await screenshot(page, 'bc-1-root');

        // Breadcrumb should show BcTest path
        expect(bc.text).toContain('BcTest');

        // --- Navigate to Sub1 via dropdown ---
        let dropdown = await openLastDropdown(page);
        console.log('[2-pre] Dropdown items:', JSON.stringify(dropdown));

        await clickDropdownItem(page, 'Sub1');
        await page.waitForTimeout(3000);

        route = await page.evaluate(() => window.location.hash);
        console.log('[2-Sub1] Route:', route);
        expect(route).toContain('/list/');
        expect(route).not.toContain('/main');

        // Wait for breadcrumb to update
        await page.waitForFunction(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            return bc && bc.textContent.includes('Sub1');
        }, { timeout: 10000 }).catch(() => {});

        bc = await getBreadcrumbInfo(page);
        console.log('[2-Sub1] Breadcrumb:', JSON.stringify(bc));
        await screenshot(page, 'bc-2-sub1');

        // Breadcrumb should show Sub1, NOT home fallback
        expect(bc.text).toContain('Sub1');

        // --- Navigate to Sub2 via dropdown ---
        dropdown = await openLastDropdown(page);
        console.log('[3-pre] Dropdown items:', JSON.stringify(dropdown));

        await clickDropdownItem(page, 'Sub2');
        await page.waitForTimeout(3000);

        route = await page.evaluate(() => window.location.hash);
        console.log('[3-Sub2] Route:', route);
        expect(route).toContain('/list/');
        expect(route).not.toContain('/main');

        await page.waitForFunction(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            return bc && bc.textContent.includes('Sub2');
        }, { timeout: 10000 }).catch(() => {});

        bc = await getBreadcrumbInfo(page);
        console.log('[3-Sub2] Breadcrumb:', JSON.stringify(bc));
        await screenshot(page, 'bc-3-sub2');

        expect(bc.text).toContain('Sub2');

        // Final route should still be on Sub2, not bounced to home
        let finalRoute = await page.evaluate(() => window.location.hash);
        console.log('[final] Route:', finalRoute);
        expect(finalRoute).toContain('/list/');
    });
});

// --- Helpers ---

async function getBreadcrumbInfo(page) {
    return page.evaluate(() => {
        let bc = document.querySelector('nav.breadcrumb-bar');
        if (!bc) return { visible: false, text: '', segments: [] };
        let items = bc.querySelectorAll('ol.breadcrumb-list li');
        let segments = [];
        items.forEach(li => {
            let btn = li.querySelector('button.multi-button.rounded-l');
            let span = li.querySelector('span.font-semibold, span:not(.material-symbols-outlined)');
            let txt = (btn ? btn.textContent : (span ? span.textContent : li.textContent)).trim();
            if (txt && txt !== '/' && !txt.match(/^(expand_more|Loading|folder)$/)) {
                segments.push(txt);
            }
        });
        return {
            visible: bc.offsetHeight > 0,
            text: segments.join(' > '),
            segments: segments
        };
    });
}

async function openLastDropdown(page) {
    let expandBtns = page.locator('nav.breadcrumb-bar button.multi-button.rounded-r');
    let count = await expandBtns.count();
    if (count === 0) return [];
    await expandBtns.last().click();
    await page.waitForTimeout(2000);
    return page.evaluate(() => {
        let menus = document.querySelectorAll('.context-menu-48');
        let visibleMenu = null;
        menus.forEach(m => {
            if (!m.classList.contains('transition-0') && m.offsetHeight > 0) visibleMenu = m;
        });
        if (!visibleMenu) return [];
        let items = visibleMenu.querySelectorAll('button.context-menu-item');
        return Array.from(items).map(b => b.textContent.trim()).filter(t => !t.includes('Loading'));
    });
}

async function clickDropdownItem(page, name) {
    let items = page.locator('.context-menu-48.transition-full button.context-menu-item');
    let count = await items.count();
    for (let i = 0; i < count; i++) {
        let txt = await items.nth(i).textContent();
        if (txt.includes(name)) {
            await items.nth(i).click();
            return;
        }
    }
    throw new Error('Dropdown item "' + name + '" not found');
}
