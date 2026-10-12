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
 * Runs as the shared test user (never admin). Topology comes from PLAYWRIGHT_BASE_URL (mandatory):
 * the Docker stack at https://127.0.0.1:9443 (project `chromium`) or the Vite dev server at
 * https://127.0.0.1:8898 proxying to it (project `chromium-vite`; this spec is in VITE_SOURCE_SPECS
 * because it imports /src modules). In both, config.js defaultServer() yields '' (the port is not
 * 8899), so every REST call is same-origin and no request rewriting is needed.
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/worldGroupPicker.e2e.spec.js \
 *     --workers=1 --project=chromium-vite
 *
 * Data: needs at least one olio.apparel in a book world (groupPath …/Worlds/<slug>/Apparel) that the
 * shared user can read. The test fails loudly (does not skip) when none exists.
 */
import { test as base, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import { login } from './helpers/auth.js';

const test = base;

test.describe('KI-35 world-group picker (real backend)', () => {
    let info = {};

    test.beforeAll(async ({ request }) => {
        info = await ensureSharedTestUser(request);
    });

    test('Apparel.wearables picker lands on the world Wearables group, not the user path', async ({ page }) => {
        test.setTimeout(120000);
        page.on('pageerror', err => console.log('  [pageerror] ' + err.message));

        // login() establishes the session via the REST API on the page's own request context, stubs the
        // WebSocket BEFORE page.goto (nginx drops the session cookie on the WS upgrade, which would
        // otherwise forceLogin() -> #!/sig after ~1s), and waits for #!/main.
        await login(page, { user: info.testUserName, password: info.testPassword });
        await expect(page.locator('[role="main"]')).toBeVisible({ timeout: 15000 });

        const result = await page.evaluate(async () => {
            const log = [];
            // Use the LIVE singletons main.js publishes on window (window.am7page / am7model / am7client).
            // A bare `import('/src/core/pageClient.js')` is NOT the app's instance once Vite HMR has
            // stamped main.js's imports with `?t=<timestamp>` (it does so after any product-file edit
            // while the dev server is up): the bare URL instantiates a second module whose page.user and
            // page.components.picker are null — measured here before this was switched.
            const am7client = window.am7client;
            const am7model = window.am7model;
            const page = window.am7page;
            if (!am7client || !am7model || !page) {
                return { noPrincipal: true, log: ['window.am7page/am7model/am7client not published by main.js'] };
            }
            // picker.js's module-level helpers are not on the ObjectPicker object main.js wires into
            // page.components.picker, so import the module for those two pure functions. They only use
            // page.findObject (REST + session cookie), so a second instance behaves identically.
            const { resolveWorldContainer, isWorldGroupPicker } = await import('/src/components/picker.js');

            // Identity of the acting principal (router.js refreshApplication() sets page.user from
            // am7client.principal()).
            const princ = page.user;
            if (!princ || !princ.id || !princ.objectId) {
                return { noPrincipal: true, log: log.concat(['page.user unresolved: ' + JSON.stringify(princ)]) };
            }
            const orgId = princ.organizationId;
            const userId = princ.id;
            log.push('acting user id=' + userId + ' name=' + princ.name + ' org=' + orgId);

            // 1) Discover a REAL book-world apparel the shared user can READ.
            //    Book worlds and everything in them are owned by the Olio principal; the acting user's
            //    read grants come from having created the book. And AccessPoint.list is NOT a per-record
            //    authorization boundary (model-api.md): an org-wide olio.apparel list happily returns
            //    thousands of rows from OTHER users' book worlds whose by-id read is AUDIT DENY. So walk
            //    the user's own books (olio.pb.book.createdByObjectId) -> world (/full carries the
            //    per-world `apparel`/`wearables` groups) -> olio.apparel in that world's Apparel group,
            //    and confirm readability with the same GET .../full views/object.js performs.
            const bq = am7client.newQuery('olio.pb.book');
            bq.field('organizationId', orgId);
            bq.field('createdByObjectId', princ.objectId);
            bq.entity.request = ['id', 'objectId', 'name', 'slug', 'world'];
            bq.sort('id');
            bq.order('descending');
            bq.cache(false);
            bq.range(0, 50);
            const bqr = await page.search(bq);
            const books = (bqr && bqr.results) ? bqr.results.filter(b => b.world && b.world.objectId) : [];
            log.push('olio.pb.book created by the acting user (with a world): ' + books.length);

            let apparelLite = null;
            let apparel = null;
            let worldRec = null;
            const probes = [];
            const seenWorlds = {};
            for (const b of books) {
                if (seenWorlds[b.world.objectId]) continue;
                seenWorlds[b.world.objectId] = true;
                const w = await am7client.getFull('olio.world', b.world.objectId);
                if (!w || !w.apparel || !w.apparel.id) { probes.push(b.slug + ':world-unreadable'); continue; }
                const aq = am7client.newQuery('olio.apparel');
                aq.field('organizationId', orgId);
                aq.field('groupId', w.apparel.id);
                aq.entity.request = ['id', 'objectId', 'name', 'groupId', 'groupPath', 'ownerId'];
                aq.cache(false);
                aq.range(0, 5);
                const aqr = await page.search(aq);
                const rows = (aqr && aqr.results) ? aqr.results : [];
                if (!rows.length) { probes.push(b.slug + ':0 apparel'); continue; }
                const r = rows[0];
                const resp = await fetch('/AccountManagerService7/rest/model/olio.apparel/' + r.objectId + '/full',
                    { credentials: 'include', headers: { 'Accept': 'application/json' } });
                probes.push(b.slug + ':' + rows.length + ' apparel, GET /full=' + resp.status);
                if (resp.status === 200) {
                    apparel = await resp.json();
                    apparelLite = r;
                    worldRec = w;
                    break;
                }
            }
            log.push('book-world probes: ' + (probes.length ? probes.join(' | ') : '(none)'));
            if (!apparelLite || !apparel) return { noData: true, log, bookCount: books.length, probes };
            log.push('chosen apparel ' + apparelLite.objectId + ' groupPath=' + apparelLite.groupPath + ' ownerId=' + apparelLite.ownerId
                + '; world.wearables=' + worldRec.wearables.objectId + ' (' + worldRec.wearables.path + ')');

            // 2) The full record is exactly what views/object.js hands to preparePicker.
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
                worldRecordWearablesOid: worldRec.wearables.objectId,
                expectedWearablesOid: wgrp ? wgrp.objectId : null,
                expectedWearablesPath: wgrp ? wgrp.path : null,
                oldCount,
                userOid,
                pickerOpen: page.components.picker.isOpen()
            };
        });

        result.log.forEach(l => console.log('  ' + l));

        if (result.noPrincipal) {
            throw new Error('Could not resolve the acting principal after login: ' + result.log.join('; '));
        }
        if (result.noData) {
            throw new Error('No book-world apparel readable by the shared user was found (books created by the '
                + 'user with a world=' + result.bookCount + '; probes=' + JSON.stringify(result.probes) + '). '
                + 'Cannot verify the fix without a book world holding apparel. Create a book via the '
                + 'PictureBook/ChapBook flow as the shared user (with scenes/cast so apparel is generated), then re-run.');
        }

        // THE FIX: resolveWorldContainer resolves to a real world Wearables group, not null — and it is
        // the very group the olio.world record itself points at as `wearables` (independent of the
        // path-derivation the fix uses).
        expect(result.worldOid, 'resolveWorldContainer returned null (the bug)').toBeTruthy();
        expect(result.expectedWearablesOid, 'world Wearables group not resolvable via path/find').toBeTruthy();
        expect(result.worldOid).toBe(result.expectedWearablesOid);
        expect(result.worldOid).toBe(result.worldRecordWearablesOid);
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
