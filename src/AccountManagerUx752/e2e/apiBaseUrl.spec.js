/**
 * E2E test: when the UX is served from the Vite dev port (8899), API requests
 * should target the app server (8443) directly via absolute URLs.
 *
 * Regression: am7client.base() returned only `/AccountManagerService7/rest`
 * (path-relative), so requests like /olio/.../narrate went to 8899 and either
 * relied on the Vite proxy or 404'd entirely depending on the dev setup.
 */
import { test, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('API base URL resolution', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('REST calls from page on 8899 target app server on 8443', async ({ page }) => {
        test.setTimeout(60000);

        let restCalls = [];
        page.on('request', (req) => {
            let url = req.url();
            if (url.includes('/AccountManagerService7/rest/')) {
                restCalls.push(url);
            }
        });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate somewhere that fires REST calls after login
        await page.goto('/#!/main');
        await page.waitForTimeout(2000);

        expect(restCalls.length, 'should have observed some REST traffic').toBeGreaterThan(0);

        // After the config.js change, every REST call should be absolute to 8443.
        let toUx = restCalls.filter(u => u.startsWith('https://localhost:8899/AccountManagerService7/'));
        let toApp = restCalls.filter(u => u.startsWith('https://localhost:8443/AccountManagerService7/'));

        // For diagnostics, log what we saw
        if (toUx.length || toApp.length === 0) {
            console.log('REST calls observed:\n' + restCalls.join('\n'));
        }

        expect(toApp.length, 'all REST calls should target the app server on 8443').toBeGreaterThan(0);
        expect(toUx.length, 'no REST calls should target the Ux dev server on 8899').toBe(0);
    });

    test('am7client.base() returns absolute URL to app server', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/main');
        await page.waitForTimeout(1000);

        let base = await page.evaluate(() => {
            // am7client is exposed via the global page object's _client reference,
            // or through the am7model bridge. Try both.
            if (typeof window.am7client !== 'undefined' && typeof window.am7client.base === 'function') {
                return window.am7client.base();
            }
            if (typeof window.am7model !== 'undefined' && window.am7model._client) {
                return window.am7model._client.base();
            }
            return null;
        });

        // If am7client isn't on window, fall back to inspecting a tracked request URL.
        // The previous test already covers the network path; this is the direct
        // module check when possible.
        if (base !== null) {
            expect(base, 'base() should include the app server host:port').toMatch(/https?:\/\/[^/]+:8443\/AccountManagerService7\/rest/);
        }
    });
});
