/**
 * Breadcrumb navigation test — auth.group hierarchy
 * Create BcTest/Sub1/Sub2 via REST API, navigate via breadcrumb dropdowns,
 * verify breadcrumb updates at each hop.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, ensurePath, findPath } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

function b64(str) { return Buffer.from(str).toString('base64'); }

test.describe('Breadcrumb Olio full navigation', () => {
    let testInfo = {};
    let bcTestId = null;
    let sub1Id = null;
    let sub2Id = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);

        // Login as test user and create the group hierarchy
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
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

            // Create BcTest/Sub1/Sub2 hierarchy
            const homePath = '/home/' + testInfo.testUserName;
            let bcTest = await makeGroup(ctx, homePath + '/BcTest');
            let sub1 = await makeGroup(ctx, homePath + '/BcTest/Sub1');
            let sub2 = await makeGroup(ctx, homePath + '/BcTest/Sub1/Sub2');

            bcTestId = bcTest && bcTest.objectId;
            sub1Id = sub1 && sub1.objectId;
            sub2Id = sub2 && sub2.objectId;
            console.log('[beforeAll] BcTest:', bcTestId, 'Sub1:', sub1Id, 'Sub2:', sub2Id);

            await ctx.get(REST + '/logout');
        } finally {
            await ctx.dispose();
        }
    });

    test.beforeEach(async ({ page }) => {
        page.on('console', msg => {
            let txt = msg.text();
            if (txt.includes('[breadcrumb') || txt.includes('[listByType]') || txt.includes('[route.set]') || msg.type() === 'error') {
                console.log('[browser ' + msg.type() + ']', txt);
            }
        });
        console.log('[beforeEach] login start t=' + Date.now());
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        console.log('[beforeEach] login done t=' + Date.now());
    });

    test('navigate through group hierarchy via breadcrumb dropdowns', async ({ page }) => {
        test.setTimeout(240000);

        if (!bcTestId) {
            test.skip('BcTest group was not created in beforeAll');
        }

        // Navigate directly to BcTest via Mithril route (no full page reload)
        console.log('[test] Navigating to BcTest:', bcTestId);
        await page.evaluate((id) => {
            if (window.am7client && window.am7client.clearCache) window.am7client.clearCache('auth.group', true);
            window.m.route.set('/list/data.data/' + id);
        }, bcTestId);
        console.log('[test] route.set done, hash:', await page.evaluate(() => window.location.hash));

        // Wait for list to load
        await page.waitForFunction(() => window.location.hash.includes('/list/'), null, { timeout: 15000 }).catch(() => {});

        // Wait for breadcrumb to show BcTest (fetch has 10s AbortSignal so should resolve quickly)
        await page.waitForFunction(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            return bc && bc.textContent.includes('BcTest');
        }, null, { timeout: 15000 }).catch(() => {});

        let bc = await getBreadcrumbInfo(page);
        console.log('[1-BcTest] Breadcrumb:', JSON.stringify(bc), 't=' + Date.now());
        await screenshot(page, 'bc-1-root');
        console.log('[1-BcTest] screenshot done t=' + Date.now());

        // Breadcrumb should show BcTest path
        expect(bc.text).toContain('BcTest');
        console.log('[1-BcTest] expect passed t=' + Date.now());

        // --- Navigate to Sub1 via dropdown ---
        console.log('[2-pre] opening dropdown t=' + Date.now());
        let dropdown = await openLastDropdown(page);
        console.log('[2-pre] Dropdown items:', JSON.stringify(dropdown), 't=' + Date.now());
        let hashBeforeClick = await page.evaluate(() => window.location.hash);
        console.log('[2-pre] hash before click:', hashBeforeClick);

        console.log('[2-pre] clicking Sub1 t=' + Date.now());
        await clickDropdownItem(page, 'Sub1');
        console.log('[2-pre] Sub1 clicked t=' + Date.now());
        await page.waitForTimeout(500);
        let hashAfterClick = await page.evaluate(() => window.location.hash);
        console.log('[2-pre] hash after click:', hashAfterClick);
        // Wait for hash to include Sub1's objectId
        await page.waitForFunction((sid) => window.location.hash.includes(sid), sub1Id, { timeout: 8000 }).catch(() => {});
        let hashAfterNav = await page.evaluate(() => window.location.hash);
        console.log('[2-Sub1] hash after nav:', hashAfterNav);

        // Wait for breadcrumb to update
        await page.waitForFunction(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            return bc && bc.textContent.includes('Sub1');
        }, null, { timeout: 12000 }).catch(() => {});
        let route = await page.evaluate(() => window.location.hash);
        console.log('[2-Sub1] Route:', route);
        expect(route).toContain('/list/');
        expect(route).not.toContain('/main');

        bc = await getBreadcrumbInfo(page);
        console.log('[2-Sub1] Breadcrumb:', JSON.stringify(bc));
        await screenshot(page, 'bc-2-sub1');

        // Breadcrumb should show Sub1, NOT home fallback
        expect(bc.text).toContain('Sub1');

        // --- Navigate to Sub2 via dropdown ---
        dropdown = await openLastDropdown(page);
        console.log('[3-pre] Dropdown items:', JSON.stringify(dropdown));

        await clickDropdownItem(page, 'Sub2');
        await page.waitForTimeout(2000);

        route = await page.evaluate(() => window.location.hash);
        console.log('[3-Sub2] Route:', route);
        expect(route).toContain('/list/');
        expect(route).not.toContain('/main');

        await page.waitForFunction(() => {
            let bc = document.querySelector('nav.breadcrumb-bar');
            return bc && bc.textContent.includes('Sub2');
        }, null, { timeout: 12000 }).catch(() => {});

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

// --- REST helper ---

async function makeGroup(ctx, path) {
    // Try find first
    let enc = 'B64-' + Buffer.from(path).toString('base64').replace(/=/g, '%3D');
    let findResp = await ctx.get(REST + '/path/find/auth.group/data/' + enc);
    if (findResp.ok()) {
        let text = await findResp.text();
        if (text && !text.startsWith('<') && !text.startsWith('null')) {
            try { return JSON.parse(text); } catch {}
        }
    }
    // Make path
    let makeResp = await ctx.get(REST + '/path/make/auth.group/data/' + enc);
    if (!makeResp.ok()) return null;
    let text = await makeResp.text();
    if (!text || text.startsWith('<')) return null;
    try { return JSON.parse(text); } catch { return null; }
}

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
            // Filter out material icon names, separator chars, and loading text
            if (txt && txt !== '/' && !txt.match(/^(expand_more|Loading|folder|folder_off|data_object|[a-z_]+)$/)) {
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
    await page.waitForTimeout(1000);
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
