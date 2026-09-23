/**
 * KI-35 — Apparel.wearables picker must DEFAULT its landing container to the parent apparel's
 * WORLD-relative Wearables group, not the acting user's ~/Wearables path.
 *
 * WHY A REAL E2E TEST: the existing Vitest unit test (`src/test/worldGroupPicker.test.js`) gave FALSE
 * CONFIDENCE. It mocks `page.search` to synthesize a WORLD_GROUP record whenever the query's numeric
 * `id` matches a literal, so it "passed" against the OLD implementation even though that implementation
 * used POST /rest/model/search on `auth.group`, which returns an EMPTY body for a non-owning user
 * against Olio-principal-owned world groups — so in production it ALWAYS resolved null and the picker
 * silently fell back to the user path (the reported bug). No stub can catch that; only a live PBAC call
 * can. This test drives the REAL `resolveWorldContainer` / `ObjectPicker.open` against the live backend
 * with a REAL book-world apparel loaded via getFull — the exact object the Add-button onclick in
 * views/object.js hands to preparePicker.
 *
 * Runs as the shared test user (never admin). Requires the Docker Service7 stack (:9443) and the Vite
 * dev server (:8899, proxying to it) both live:
 *   PLAYWRIGHT_BASE_URL=https://localhost:8899 npx playwright test e2e/worldGroupPicker.e2e.spec.js \
 *     --workers=1 --project=chromium
 */
import { test as base, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

// NOTE: do NOT force --host-resolver-rules=MAP localhost 127.0.0.1 here. That mapping is a DOCKER-only
// workaround (the Docker publish is IPv4-only). In the LOCAL dev pairing this test targets — Vite dev
// server :8899 + local Tomcat :8443 — `config.js` defaultServer() makes am7client talk DIRECTLY to
// https://localhost:8443 (not through the Vite proxy). Both the Vite server and the local backend
// listen on IPv6 ::1, so forcing localhost->127.0.0.1 made every browser XHR to :8443 fail with
// ERR_CONNECTION_REFUSED (nothing on 127.0.0.1:8443) and the SPA bounced to #!/sig. Natural resolution
// (localhost -> ::1) reaches both. The document Origin stays https://localhost:8899, which the :8443
// backend allows for cross-origin XHR in the standard dev setup.

test.describe('KI-35 world-group picker (real backend)', () => {
    let info = {};

    test.beforeAll(async ({ request }) => {
        info = await ensureSharedTestUser(request);
    });

    test('Apparel.wearables picker lands on the world Wearables group, not the user path', async ({ page }) => {
        test.setTimeout(120000);

        // Stub the WebSocket BEFORE any navigation — the Vite(:8899)->backend proxy drops the session
        // cookie on the WS upgrade, so Tomcat closes it and pageClient.reconnect() would forceLogin() ->
        // #!/sig after ~1s. The stub fires onopen and never onclose.
        await page.addInitScript(() => {
            window.WebSocket = class StubWS {
                constructor(url) {
                    this.url = url; this.readyState = 0;
                    this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                    this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                    setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
                }
                send() {} close() { this.readyState = 3; }
                addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
            };
            window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
            window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
        });

        // First load: the app boots unauthenticated and routes to #!/sig — that's expected. We only need
        // the ES modules loaded so we can drive a BROWSER-NATIVE login. Logging in from inside the page
        // (rather than page.request.post) guarantees the session cookie is set for the browser's exact
        // origin/resolution, which is what the SPA's am7client.principal() reads on the next load. The
        // cross-context cookie from page.request.post is not reliably seen through the Vite->backend proxy.
        page.on('pageerror', err => console.log('  [pageerror] ' + err.message));

        // config.js defaultServer() hardcodes the API base to https://localhost:8443 whenever the SPA is
        // served from :8899. In this environment nothing listens on :8443 — the only live backend is the
        // one the Vite dev-server proxy reaches. Rewrite the browser's direct :8443 REST calls onto the
        // working :8899 proxy origin. Cookies are host-based (port-agnostic), so a JSESSIONID set on
        // localhost via :8899 is still sent to localhost:8443 requests, and same-origin :8899 avoids CORS.
        await page.route('**', async (route) => {
            const u = route.request().url();
            if (u.indexOf('://localhost:8443/') !== -1) {
                await route.continue({ url: u.replace('://localhost:8443/', '://localhost:8899/') });
            } else {
                await route.continue();
            }
        });

        // BROWSER-NATIVE login: log in from inside the page (not page.request.post) so the session cookie
        // is set for the browser's exact origin, which is what the SPA's am7client.principal() reads on
        // the next load. The first goto boots unauthenticated (routes to #!/sig) — that only loads the ES
        // modules we then drive.
        await page.goto('/', { timeout: 30000 });
        const principalName = await page.evaluate(async (creds) => {
            const { am7client } = await import('/src/core/am7client.js');
            await am7client.loginWithPassword(creds.org, creds.user, creds.pass);
            const usr = await am7client.principal();
            return usr ? (usr.name || true) : null;
        }, { org: '/Development', user: info.testUserName, pass: info.testPassword });
        expect(principalName, 'browser-native login did not establish a session').toBeTruthy();

        // Reload so the SPA bootstraps as the authenticated user and routes to #!/main.
        await page.goto('/', { timeout: 30000 });
        await page.waitForFunction(
            () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
            { timeout: 30000 }
        );
        await page.waitForTimeout(1500);

        const result = await page.evaluate(async () => {
            const log = [];
            const { am7client } = await import('/src/core/am7client.js');
            const { am7model } = await import('/src/core/model.js');
            const { page } = await import('/src/core/pageClient.js');
            const picker = await import('/src/components/picker.js');
            const { resolveWorldContainer, isWorldGroupPicker } = picker;

            const orgId = (page.user && page.user.organizationId) || 2;
            const userId = page.user ? page.user.id : null;
            log.push('acting user id=' + userId + ' name=' + (page.user ? page.user.name : '?'));

            // 1) Discover a REAL book-world apparel readable by the shared user.
            //    NOTE: `groupPath` is a VIRTUAL provider-computed field (PathProvider walks the parent
            //    chain) — it is projectable but NOT a SQL column, so a `groupPath LIKE …` condition
            //    silently matches nothing. Query org-scoped and filter on the PROJECTED groupPath
            //    client-side instead.
            const q = am7client.newQuery('olio.apparel');
            q.field('organizationId', orgId);
            q.entity.request = ['id', 'objectId', 'name', 'groupId', 'groupPath', 'ownerId'];
            q.cache(false);
            q.range(0, 100);
            const qr = await page.search(q);
            const rows = (qr && qr.results) ? qr.results : [];
            const worldRows = rows.filter(r => r.groupPath && /\/Worlds\/[^/]+\/Apparel$/.test(r.groupPath));
            log.push('olio.apparel readable=' + rows.length + ', in a book world=' + worldRows.length);
            const apparelLite = worldRows[0];
            if (!apparelLite) return { noData: true, log, rowCount: rows.length };
            log.push('chosen apparel groupPath=' + apparelLite.groupPath + ' ownerId=' + apparelLite.ownerId);

            // 2) Load it fully (exactly as views/object.js does before opening the picker).
            const apparel = await am7client.getFull('olio.apparel', apparelLite.objectId);
            const parentModel = apparel[am7model.jsonModelKey] || 'olio.apparel';
            log.push('parentModel=' + parentModel + ' groupId=' + apparel.groupId + ' groupPath=' + apparel.groupPath);
            log.push('isWorldGroupPicker=' + isWorldGroupPicker(parentModel, 'wearables'));

            // 3) THE FIX — call the REAL resolveWorldContainer against the live backend.
            const worldOid = await resolveWorldContainer(parentModel, 'wearables', apparel);
            log.push('resolveWorldContainer -> ' + worldOid);

            // 4) Independently resolve the expected world Wearables group via path/find.
            const worldRoot = apparel.groupPath.replace(/\/Apparel$/, '');
            const expectedPath = worldRoot + '/Wearables';
            const wgrp = await page.findObject('auth.group', 'DATA', expectedPath);
            log.push('expected Wearables path=' + expectedPath + ' -> ' + (wgrp ? wgrp.objectId + ' (' + wgrp.path + ')' : 'null'));

            // 5) ROOT CAUSE — replicate the OLD mechanism (model/search on auth.group by numeric id).
            //    For a non-owning user against an Olio-principal-owned group this returns EMPTY, which is
            //    why the old resolveWorldContainer returned null and the picker fell to the user path.
            const oq = am7client.newQuery('auth.group');
            oq.field('id', apparel.groupId);
            oq.field('organizationId', orgId);
            oq.entity.request = ['id', 'objectId', 'name', 'groupPath'];
            oq.cache(false);
            const oqr = await page.search(oq);
            const oldCount = (oqr && oqr.results) ? oqr.results.length : 0;
            log.push('OLD model/search auth.group by id=' + apparel.groupId + ' -> ' + oldCount + ' rows');

            // 6) The WRONG default the bug produced — the user's own ~/Wearables group.
            const userGrp = await page.makePath('auth.group', 'data', '~/Wearables');
            const userOid = userGrp ? userGrp.objectId : null;
            log.push('user ~/Wearables -> ' + userOid + ' (' + (userGrp ? userGrp.path : '') + ')');

            // 7) Drive the FULL onclick -> preparePicker -> open path via page.components.picker (what
            //    views/object.js actually calls), so the mounted PickerView renders into the live DOM.
            await page.components.picker.open({
                type: 'olio.wearable',
                parentModel: parentModel,
                parentField: 'wearables',
                parentEntity: apparel
            });
            log.push('picker.isOpen=' + page.components.picker.isOpen());

            return {
                noData: false,
                log,
                userId,
                apparelOwnerId: apparelLite.ownerId,
                worldGroupOwnerId: wgrp ? wgrp.ownerId : null,
                worldOid,
                expectedWearablesOid: wgrp ? wgrp.objectId : null,
                expectedWearablesPath: wgrp ? wgrp.path : null,
                oldCount,
                userOid,
                pickerOpen: page.components.picker.isOpen()
            };
        });

        result.log.forEach(l => console.log('  ' + l));

        if (result.noData) {
            throw new Error('No book-world apparel (groupPath …/Worlds/<slug>/Apparel) readable by the shared '
                + 'user was found (rows=' + result.rowCount + '). Cannot verify the fix without book-world data. '
                + 'Create a book via the PictureBook/ChapBook flow, then re-run.');
        }

        // THE FIX: resolveWorldContainer resolves to a real world Wearables group, not null.
        expect(result.worldOid, 'resolveWorldContainer returned null (the bug)').toBeTruthy();
        expect(result.expectedWearablesOid, 'world Wearables group not resolvable via path/find').toBeTruthy();
        expect(result.worldOid).toBe(result.expectedWearablesOid);
        expect(result.expectedWearablesPath).toMatch(/\/Worlds\/[^/]+\/Wearables$/);

        // ROOT CAUSE: the acting user is a genuine NON-OWNER of the world apparel/group (owned by the
        // Olio principal), and the OLD model/search-by-id mechanism returns EMPTY for such a user —
        // which is exactly why the previous implementation resolved null and dropped to the user path.
        expect(result.userId, 'acting user should be a non-owner of the world group')
            .not.toBe(result.apparelOwnerId);
        expect(result.oldCount, 'OLD model/search unexpectedly returned rows — root-cause premise wrong').toBe(0);

        // NO LONGER the user path: the world group is distinct from the user's ~/Wearables group.
        expect(result.userOid).toBeTruthy();
        expect(result.worldOid).not.toBe(result.userOid);

        // FULL PATH: the picker actually opened.
        expect(result.pickerOpen).toBe(true);

        // END-TO-END DOM: the mounted picker overlay orients on the world Wearables path (the container
        // open() landed on), NOT the user's ~/Wearables path.
        const overlay = page.locator('.am7-picker-overlay');
        await expect(overlay).toBeVisible({ timeout: 10000 });
        const pathText = overlay.locator('p[title]').first();
        await expect(pathText).toHaveAttribute('title', /\/Worlds\/[^/]+\/Wearables$/, { timeout: 10000 });
        const shown = await pathText.getAttribute('title');
        expect(shown).not.toMatch(/\/home\//);
    });
});
