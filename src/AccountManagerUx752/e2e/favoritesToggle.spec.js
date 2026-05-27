/**
 * E2E test: clicking the favorite (heart) icon in a list view actually
 * persists the favorite to the user's Favorites group, and the icon
 * reflects the favorite state after a reload.
 *
 * Repro:
 *   1. Create a data.data record in the user's Documents directory
 *   2. Navigate to /list/data.data/<docs.objectId>
 *   3. Click the heart icon in that row
 *   4. Assert the icon picks up the "filled" / red-styled class
 *   5. Reload the page
 *   6. Assert the icon is STILL filled (favorite persisted)
 *
 * Prior bug: pageClient.toggleFavorite passed the literal string 'null'
 * instead of null as participantModel to am7client.member, so the
 * participation creation failed silently.
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(s) { return Buffer.from(s).toString('base64'); }
function uniq() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }

async function asUser(user, password, fn) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let resp = await ctx.post(REST + '/login', {
            data: {
                schema: 'auth.credential', organizationPath: '/Development',
                name: user, credential: b64(password), type: 'hashed_password'
            }
        });
        if (!resp.ok()) throw new Error('login failed');
        return await fn(ctx);
    } finally {
        await ctx.get(REST + '/logout').catch(() => {});
        await ctx.dispose();
    }
}

async function safeJson(resp) {
    if (!resp.ok()) return null;
    let t = await resp.text();
    if (!t) return null;
    try { return JSON.parse(t); } catch(e) { return null; }
}

test.describe('Favorites toggle persists', () => {
    let testInfo = {};
    let docsDir = null;
    let dataObject = null;
    let dataName = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);

        await asUser(testInfo.testUserName, testInfo.testPassword, async (ctx) => {
            /// Make a Documents directory under the user's home
            let dir = await ctx.get(REST + '/path/make/auth.group/data/B64-' +
                b64('/home/' + testInfo.testUserName + '/Documents').replace(/=/g, '%3D'));
            docsDir = await safeJson(dir);

            if (docsDir && docsDir.id) {
                dataName = 'FavTest_' + uniq();
                /// Use schema model endpoint to create a data.data record
                let r = await ctx.post(REST + '/model', {
                    data: {
                        schema: 'data.data',
                        name: dataName,
                        contentType: 'text/plain',
                        groupId: docsDir.id,
                        groupPath: docsDir.path
                    }
                });
                dataObject = await safeJson(r);
            }
        });

        expect(docsDir, 'Documents directory created').toBeTruthy();
        expect(docsDir.objectId, 'Documents directory has objectId').toBeTruthy();
        expect(dataObject, 'test data record created').toBeTruthy();
    });

    test('clicking favorite icon persists across reload', async ({ page }) => {
        test.setTimeout(90000);

        /// Capture browser console + page errors — we need to know if a
        /// JS error is short-circuiting page.toggleFavorite.
        page.on('console', (msg) => {
            console.log('[browser:' + msg.type() + '] ' + msg.text());
        });
        page.on('pageerror', (err) => {
            console.log('[browser:pageerror] ' + (err.message || err));
        });
        page.on('requestfailed', (req) => {
            console.log('[browser:requestfailed] ' + req.method() + ' ' + req.url() + ' - ' + req.failure().errorText);
        });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        /// Navigate to the list view for data.data scoped to the Documents dir.
        /// Bump recordCount so accumulated test items from prior runs don't
        /// push our new row off the first page (default is 10).
        let listUrl = '/#!/list/data.data/' + docsDir.objectId + '?startRecord=0&recordCount=200';
        await page.goto(listUrl);
        await page.waitForTimeout(2500);

        await screenshot(page, 'fav-list-loaded');

        /// Re-query the icon inline on every check — Mithril redraws may
        /// replace the DOM node and a saved locator handle can read stale
        /// attributes from a detached node.
        let iconForRow = () => page.locator('tr', { hasText: dataName })
            .first()
            .locator('span.cursor-pointer.material-symbols-outlined')
            .filter({ hasText: /^favorite$/ })
            .first();

        let row = page.locator('tr', { hasText: dataName }).first();
        await expect(row, 'test data row should be visible').toBeVisible({ timeout: 10000 });
        await expect(iconForRow(), 'favorite icon should be visible').toBeVisible({ timeout: 5000 });

        /// Initial: should NOT be filled (fresh data record)
        let initialClass = (await iconForRow().getAttribute('class')) || '';
        console.log('[diag] favorite class before click: ' + initialClass);
        expect(initialClass).not.toContain('filled');

        /// Probe the actual DOM state by reading the favorite cell directly
        let domProbeBefore = await page.evaluate((name) => {
            let rows = Array.from(document.querySelectorAll('tr'));
            let matchRow = rows.find(r => r.textContent && r.textContent.includes(name));
            if (!matchRow) return { err: 'row not found' };
            let icons = Array.from(matchRow.querySelectorAll('span.material-symbols-outlined'));
            let favIcons = icons.filter(i => i.textContent && i.textContent.trim() === 'favorite');
            return {
                rowCount: rows.length,
                rowFound: true,
                iconCount: favIcons.length,
                iconClass: favIcons[0] ? favIcons[0].className : null,
                iconOuter: favIcons[0] ? favIcons[0].outerHTML.slice(0, 200) : null
            };
        }, dataName);
        console.log('[dom probe before click] ' + JSON.stringify(domProbeBefore));

        /// Click — triggers page.toggleFavorite (async member call + redraw)
        await iconForRow().click();
        await page.waitForTimeout(4000);

        /// Inspect the DOM directly after the click settles
        let domProbeAfter = await page.evaluate((name) => {
            let rows = Array.from(document.querySelectorAll('tr'));
            let matchRow = rows.find(r => r.textContent && r.textContent.includes(name));
            if (!matchRow) return { err: 'row not found' };
            let icons = Array.from(matchRow.querySelectorAll('span.material-symbols-outlined'));
            let favIcons = icons.filter(i => i.textContent && i.textContent.trim() === 'favorite');
            return {
                rowCount: rows.length,
                iconCount: favIcons.length,
                iconClass: favIcons[0] ? favIcons[0].className : null,
                iconOuter: favIcons[0] ? favIcons[0].outerHTML.slice(0, 200) : null,
                allRows: rows.slice(0, 10).map(r => (r.textContent || '').slice(0, 80))
            };
        }, dataName);
        console.log('[dom probe after click] ' + JSON.stringify(domProbeAfter));

        /// Poll up to 15s for the icon class to pick up "filled". The chain
        /// is: POST member → clearCache → GET members → update
        /// contextModel.favorites → m.redraw → rAF → paint. On
        /// a slow machine this can take a few seconds — bumping the budget
        /// avoids flaky failures while still catching a real regression.
        await expect.poll(async () => {
            let c = (await iconForRow().getAttribute('class')) || '';
            return c.includes('filled');
        }, {
            message: 'icon class should pick up "filled" within 15s of toggle',
            timeout: 15000,
            intervals: [500, 750, 1000, 1500]
        }).toBe(true);

        await screenshot(page, 'fav-after-click');

        let afterClass = (await iconForRow().getAttribute('class')) || '';
        console.log('[diag] favorite class after click (settled): ' + afterClass);
        expect(afterClass, 'icon should be filled after toggle').toContain('filled');

        /// Persist check — reload and the icon must still be filled
        /// page.reload() drops query-string overrides; re-goto with our wide
        /// recordCount so the row is still visible after the reload.
        await page.goto(listUrl);
        await page.waitForTimeout(2500);
        await screenshot(page, 'fav-after-reload');

        await expect(iconForRow(), 'favorite icon should be visible after reload').toBeVisible({ timeout: 10000 });
        let reloadedClass = (await iconForRow().getAttribute('class')) || '';
        console.log('[diag] favorite class after reload: ' + reloadedClass);
        expect(reloadedClass, 'favorite must persist across reload').toContain('filled');

        /// NOTE: Un-favorite (click again to remove) is intentionally NOT
        /// asserted here. The optimistic remove works briefly but the
        /// authoritative reconcile fetch re-adds the favorite, suggesting
        /// the server-side participation removal isn't taking effect on
        /// the GET /authz/.../false path. That's a separate bug from the
        /// original "favorites isn't working" complaint and warrants its
        /// own investigation.
    });
});
