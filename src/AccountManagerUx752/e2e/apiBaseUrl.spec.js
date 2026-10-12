/**
 * E2E test: the SPA's REST base resolves to the right origin for the topology it is served from.
 *
 * core/config.js defaultServer(): when the page is served from the Vite dev port (8899) the API base
 * is an absolute URL to the app server on 8443; from any other origin (the Docker nginx on 9443, the
 * second Vite on 8898 that proxies to it) it is '' and REST calls are same-origin. This spec derives
 * the expected API origin from the project's baseURL and asserts every observed REST request targets
 * it, so it is correct under both Playwright projects without naming any port.
 *
 * Regression guarded: am7client.base() returned only `/AccountManagerService7/rest` (path-relative)
 * in the split dev pairing, so requests like /olio/.../narrate bounced off the Ux server.
 */
import { test, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

// Mirror of config.js defaultServer(): same-origin unless served from the Vite dev port 8899.
function expectedApiOrigin(baseURL) {
    const u = new URL(baseURL);
    if (u.port === '8899') return u.protocol + '//' + u.hostname + ':8443';
    return u.origin;
}

test.describe('API base URL resolution', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('REST calls from the page target the API origin derived from baseURL', async ({ page, baseURL }) => {
        test.setTimeout(60000);
        const apiOrigin = expectedApiOrigin(baseURL);
        console.log('[apiBaseUrl] baseURL=' + baseURL + ' expected API origin=' + apiOrigin);

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

        const onApi = restCalls.filter(u => new URL(u).origin === apiOrigin);
        const offApi = restCalls.filter(u => new URL(u).origin !== apiOrigin);

        // For diagnostics, log what we saw
        if (offApi.length || onApi.length === 0) {
            console.log('REST calls observed:\n' + restCalls.join('\n'));
        }

        expect(onApi.length, 'REST calls should target ' + apiOrigin).toBeGreaterThan(0);
        expect(offApi, 'no REST call may target any other origin than ' + apiOrigin).toEqual([]);
    });

    test('am7client.base() resolves to the API origin derived from baseURL', async ({ page, baseURL }) => {
        test.setTimeout(30000);
        const apiOrigin = expectedApiOrigin(baseURL);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/main');
        await page.waitForTimeout(1000);

        // main.js:80-88 exposes window.am7client; am7model._client is the older bridge.
        let base = await page.evaluate(() => {
            if (typeof window.am7client !== 'undefined' && typeof window.am7client.base === 'function') {
                return window.am7client.base();
            }
            if (typeof window.am7model !== 'undefined' && window.am7model._client) {
                return window.am7model._client.base();
            }
            return null;
        });
        console.log('[apiBaseUrl] am7client.base()=' + base);
        expect(base, 'am7client must be reachable on window (main.js exposes it)').not.toBeNull();

        // base() is absolute in the split pairing and path-relative when same-origin; resolve it
        // against the page's own location the way the browser does and compare origins.
        const resolved = new URL(base, page.url());
        expect(resolved.origin, 'base() must resolve to the API origin').toBe(apiOrigin);
        expect(resolved.pathname).toBe('/AccountManagerService7/rest');
    });
});
