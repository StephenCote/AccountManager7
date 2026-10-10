/**
 * ChapBook BOOK-LEVEL SD CONFIG — V3 Phase 0 P0-10c (REST) / P0-10d (UI)
 *
 * Part 1 (REST): GET / PUT /rest/olio/chap-book/{bookObjectId}/settings against the live Docker stack as
 * the shared (non-admin) test user. The Objects7 contract (sparse merge, identity discard, CHAPBOOK
 * guard) is proven in TestChapBookBookSettings; this pins the transport end to end: status codes, the
 * stored row coming back through toFullString(), and the next GET seeing the PUT.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookBookSettings.spec.js \
 *       --project=chromium --workers=1
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, CB_REST,
    POEM_1, POEM_2
} from './helpers/chapbook.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const OTHER_USER = 'e2etest_cbother';

async function seedFreshBook(request, slugPrefix) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    return await createChapBook(request, slugPrefix + '-' + Date.now(), 'E2E Book Settings Book', [p1, p2], 4);
}

async function getSettings(request, bookOid) {
    return await request.get(CB_REST + '/' + bookOid + '/settings');
}

async function putSettings(request, bookOid, sdConfig) {
    return await request.put(CB_REST + '/' + bookOid + '/settings', {
        data: sdConfig === undefined ? {} : { sdConfig }
    });
}

test.describe('ChapBook book-level SD config — REST /settings', () => {

    test('fresh book has no stored config; PUT persists a sparse config; GET reads it back', async ({ request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-settings');

        // 1. A fresh ChapBook has no book-sdConfig row: 200 with an empty object (not 404, not null).
        const g0 = await getSettings(request, bookOid);
        expect(g0.status(), 'GET settings on fresh book: ' + await g0.text()).toBe(200);
        const b0 = await g0.json();
        expect(b0, 'fresh book settings body').toEqual({});

        // 2. PUT a sparse olio.sd.config — the body the Ux752 book panel will send.
        const p1 = await putSettings(request, bookOid, {
            schema: 'olio.sd.config', steps: 33, cfg: 5, style: 'art', artStyle: 'watercolor', negativePrompt: 'blurry'
        });
        expect(p1.status(), 'PUT settings: ' + await p1.text()).toBe(200);
        const s1 = await p1.json();
        expect(s1.schema).toBe('olio.sd.config');
        expect(s1.objectId, 'stored row has an objectId').toBeTruthy();
        expect(s1.name, 'stored row is the book-level row').toBe('book-sdConfig');
        expect(s1.steps).toBe(33);
        expect(s1.cfg).toBe(5);
        expect(s1.style.toLowerCase()).toBe('art');
        expect(s1.artStyle.toLowerCase()).toBe('watercolor');
        expect(s1.negativePrompt).toBe('blurry');

        // 3. GET sees the PUT (fresh read, same row).
        const g1 = await getSettings(request, bookOid);
        expect(g1.status()).toBe(200);
        const b1 = await g1.json();
        expect(b1.objectId).toBe(s1.objectId);
        expect(b1.steps).toBe(33);
        expect(b1.cfg).toBe(5);
        expect(b1.negativePrompt).toBe('blurry');

        // 4. A second sparse PUT merges: only steps changes, cfg/negativePrompt survive, same row.
        const p2 = await putSettings(request, bookOid, { schema: 'olio.sd.config', steps: 21 });
        expect(p2.status(), 'second PUT: ' + await p2.text()).toBe(200);
        const s2 = await p2.json();
        expect(s2.objectId, 'second PUT updates the same row').toBe(s1.objectId);
        expect(s2.steps).toBe(21);
        expect(s2.cfg, 'unsent field survives a sparse PUT').toBe(5);
        expect(s2.negativePrompt, 'unsent field survives a sparse PUT').toBe('blurry');

        // 5. Foreign identity in the body is discarded (SD_OVERRIDE_SKIP): the row keeps its own
        //    objectId/name and the value field still lands.
        const p3 = await putSettings(request, bookOid, {
            schema: 'olio.sd.config', objectId: 'not-this-books-row', groupId: 999999, name: 'not-the-book-config', steps: 44
        });
        expect(p3.status(), 'identity-carrying PUT: ' + await p3.text()).toBe(200);
        const s3 = await p3.json();
        expect(s3.objectId, 'foreign objectId must not replace the stored row').toBe(s1.objectId);
        expect(s3.name, 'foreign name must not replace the stored row name').toBe('book-sdConfig');
        expect(s3.steps).toBe(44);
        const g3 = await getSettings(request, bookOid);
        expect((await g3.json()).objectId).toBe(s1.objectId);
    });

    test('PUT without sdConfig is 400; unknown book is 404 on GET and PUT', async ({ request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-settings-err');

        const noCfg = await putSettings(request, bookOid, undefined);
        expect(noCfg.status(), 'PUT with no sdConfig: ' + await noCfg.text()).toBe(400);

        const missingGet = await getSettings(request, 'no-such-book-' + Date.now());
        expect(missingGet.status(), 'GET unknown book: ' + await missingGet.text()).toBe(404);

        const missingPut = await putSettings(request, 'no-such-book-' + Date.now(), { schema: 'olio.sd.config', steps: 9 });
        expect(missingPut.status(), 'PUT unknown book: ' + await missingPut.text()).toBe(404);
    });

    test('another non-admin user cannot read or write this book\'s settings', async ({ request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-settings-authz');
        const seeded = await putSettings(request, bookOid, { schema: 'olio.sd.config', steps: 12 });
        expect(seeded.status()).toBe(200);

        // Second persistent non-admin user with its own session (own cookie jar).
        const { testUserName, testPassword } = await ensureSharedTestUser(request, { name: OTHER_USER });
        const other = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            const login = await other.post('/AccountManagerService7/rest/login', {
                data: {
                    schema: 'auth.credential', organizationPath: '/Development', name: testUserName,
                    credential: Buffer.from(testPassword).toString('base64'), type: 'hashed_password'
                }
            });
            expect(login.ok() || login.status() === 204, 'other-user login: ' + login.status()).toBe(true);

            // The book is not readable by this user, so the util resolves no book → 404 (not a leak of
            // the stored config, and not a 500).
            const g = await getSettings(other, bookOid);
            expect(g.status(), 'other user GET: ' + await g.text()).toBe(404);

            const p = await putSettings(other, bookOid, { schema: 'olio.sd.config', steps: 99 });
            expect(p.status(), 'other user PUT: ' + await p.text()).toBe(404);
        } finally {
            await other.dispose();
        }

        // The owner's stored value is untouched by the rejected write.
        const g = await getSettings(request, bookOid);
        expect((await g.json()).steps).toBe(12);
    });
});

/**
 * Part 2 (UI, P0-10d): the ChapBook review toolbar's collapsible "Book image settings" panel drives ONE
 * olio.sd.config instance per book (features/chapBook.js ensureRenderSdConfig / computeBookSdDelta /
 * saveBookSdConfig). These tests drive the real page against the live Docker stack and verify the
 * backend row through the same REST endpoint Part 1 pinned. No render is triggered (no "Render" /
 * "Regenerate" clicks) — the render path is covered elsewhere.
 *
 * Field map (components/SdConfigPanel.js:261-263): "Composition" → bodyStyle, "Action" → imageAction,
 * "Setting" → imageSetting. Server-side '' values are ignored on merge and sdcfg-default is shared
 * across e2e users, so every value asserted here is a unique non-empty string minted by the test.
 */
const SAVE_BOOK_SD = '[data-testid="cb-save-book-sd"]';

// The sticky review toolbar that hosts the "Book image settings" collapsible. Scoping every field
// locator to it keeps the per-scene "Image config overrides" panels (same SdConfigPanel) out of play.
function bookSdToolbar(page) {
    return page.locator('div.sticky').filter({ has: page.locator('button:has-text("Book image settings")') });
}

// Navigate a logged-in page to the review view, expand the book SD panel and wait for the real panel
// (the Composition input), not the "Loading SD configuration…" spinner. Returns the toolbar locator.
async function openBookSdPanel(page, bookOid) {
    await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
    const toolbar = bookSdToolbar(page);
    // The toolbar only renders once the scene pages have loaded.
    await expect(toolbar).toBeVisible({ timeout: 30000 });
    await toolbar.locator('button:has-text("Book image settings")').click();
    await expect(toolbar.locator('label:text-is("Composition") + input')).toBeVisible({ timeout: 30000 });
    await expect(toolbar.getByText('Loading SD configuration…')).toHaveCount(0);
    return toolbar;
}

function compositionInput(toolbar) { return toolbar.locator('label:text-is("Composition") + input'); }
function actionInput(toolbar) { return toolbar.locator('label:text-is("Action") + input'); }

test.describe('ChapBook book settings — UI (P0-10d)', () => {

    test('Save in the toolbar panel persists the book row (first save of an unstored book)', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-ui-save');
        expect(await (await getSettings(request, bookOid)).json(), 'fresh book has no row').toEqual({});

        const value = 'e2e-comp-' + Date.now();
        await loginAsSharedUser(page);
        const toolbar = await openBookSdPanel(page, bookOid);

        await compositionInput(toolbar).fill(value);
        await expect(compositionInput(toolbar)).toHaveValue(value);
        const save = toolbar.locator(SAVE_BOOK_SD);
        await expect(save).toBeEnabled({ timeout: 10000 });
        await save.click();

        // UI evidence: the success toast from saveBookSdConfig.
        await expect(page.locator('.toast-text', { hasText: 'Book image settings saved' }))
            .toBeVisible({ timeout: 20000 });

        // Authoritative evidence: the stored row through the REST endpoint.
        let row = null;
        await expect.poll(async () => {
            const r = await getSettings(request, bookOid);
            if (r.status() !== 200) return 'HTTP ' + r.status();
            row = await r.json();
            return row.bodyStyle;
        }, { timeout: 30000, message: 'book row bodyStyle after UI save' }).toBe(value);
        expect(row.schema).toBe('olio.sd.config');
        expect(row.name, 'stored row is the book-level row').toBe('book-sdConfig');
        expect(row.objectId, 'stored row has an objectId').toBeTruthy();

        // After the save the instance is marked stored + changes reset → nothing left to save.
        await expect(save).toBeDisabled({ timeout: 10000 });
    });

    test('a new page shows the STORED value and Save is disabled (stored-row load, not the seed path)', async ({ page, context, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-ui-reload');
        const value = 'e2e-comp-reload-' + Date.now();
        const seeded = await putSettings(request, bookOid, { schema: 'olio.sd.config', bodyStyle: value });
        expect(seeded.status(), 'REST seed PUT: ' + await seeded.text()).toBe(200);

        // First page: the value is there.
        await loginAsSharedUser(page);
        const toolbar1 = await openBookSdPanel(page, bookOid);
        await expect(compositionInput(toolbar1)).toHaveValue(value);

        // Brand-new page (own module state): the panel must load from the stored row again.
        const page2 = await context.newPage();
        try {
            await loginAsSharedUser(page2);
            const toolbar2 = await openBookSdPanel(page2, bookOid);
            await expect(compositionInput(toolbar2)).toHaveValue(value);
            // Stored row + no edits ⇒ nothing to save. (An unstored book would have the whole seeded
            // entity pending and the button ENABLED, so this distinguishes the two load paths.)
            await expect(toolbar2.locator(SAVE_BOOK_SD)).toBeDisabled();
        } finally {
            await page2.close();
        }
    });

    test('no cross-book leakage: book B does not show book A\'s value and opening B writes no row', async ({ page, context, request }) => {
        const bookA = await seedFreshBook(request, 'e2e-cb-ui-leak-a');
        const bookB = await seedFreshBook(request, 'e2e-cb-ui-leak-b');
        const valueA = 'e2e-comp-A-' + Date.now();
        const seeded = await putSettings(request, bookA, { schema: 'olio.sd.config', bodyStyle: valueA });
        expect(seeded.status(), 'REST seed PUT for A: ' + await seeded.text()).toBe(200);
        expect(await (await getSettings(request, bookB)).json(), 'B starts with no row').toEqual({});

        await loginAsSharedUser(page);
        const toolbarA = await openBookSdPanel(page, bookA);
        await expect(compositionInput(toolbarA)).toHaveValue(valueA);

        const page2 = await context.newPage();
        try {
            await loginAsSharedUser(page2);
            const toolbarB = await openBookSdPanel(page2, bookB);
            const shownB = await compositionInput(toolbarB).inputValue();
            expect(shownB, 'book B must not show book A\'s Composition').not.toBe(valueA);
            // B has no stored row, so its panel is on the seed path: the whole seeded entity is pending
            // and Save is enabled (contrast with the stored-row test above).
            await expect(toolbarB.locator(SAVE_BOOK_SD)).toBeEnabled();
        } finally {
            await page2.close();
        }

        // Read paths must not write: opening B's review page created no book-sdConfig row.
        const gB = await getSettings(request, bookB);
        expect(gB.status()).toBe(200);
        expect(await gB.json(), 'B still has no row after opening its review page').toEqual({});
        // And A's row is untouched by B's page.
        expect((await (await getSettings(request, bookA)).json()).bodyStyle).toBe(valueA);
    });

    test('once a row exists, Save sends only the changed field (sparse PUT) and unsent fields survive', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-ui-sparse');
        const ts = Date.now();
        const seedComp = 'e2e-c-' + ts;
        const seedAction = 'e2e-act-' + ts;
        const seeded = await putSettings(request, bookOid, {
            schema: 'olio.sd.config', bodyStyle: seedComp, imageAction: seedAction
        });
        expect(seeded.status(), 'REST seed PUT: ' + await seeded.text()).toBe(200);
        const seededRow = await seeded.json();
        expect(seededRow.imageAction).toBe(seedAction);

        await loginAsSharedUser(page);
        const toolbar = await openBookSdPanel(page, bookOid);
        // Both seeded values are shown → the stored row (not the seed) backs the panel.
        await expect(compositionInput(toolbar)).toHaveValue(seedComp);
        await expect(actionInput(toolbar)).toHaveValue(seedAction);
        const save = toolbar.locator(SAVE_BOOK_SD);
        await expect(save).toBeDisabled();

        const newComp = 'e2e-c-new-' + ts;
        await compositionInput(toolbar).fill(newComp);
        await expect(save).toBeEnabled({ timeout: 10000 });

        // Capture the exact PUT the UI sends.
        const putPromise = page.waitForRequest(
            r => r.method() === 'PUT' && r.url().endsWith('/' + bookOid + '/settings'),
            { timeout: 30000 });
        await save.click();
        const putReq = await putPromise;
        const body = putReq.postDataJSON();
        expect(body && body.sdConfig, 'PUT body carries sdConfig').toBeTruthy();
        const sent = body.sdConfig;
        expect(sent.bodyStyle).toBe(newComp);
        expect(sent.schema).toBe('olio.sd.config');
        expect(Object.prototype.hasOwnProperty.call(sent, 'imageAction'), 'unchanged imageAction not sent').toBe(false);
        expect(Object.prototype.hasOwnProperty.call(sent, 'objectId'), 'identity not sent').toBe(false);
        expect(Object.prototype.hasOwnProperty.call(sent, 'name'), 'row name not sent').toBe(false);
        expect(Object.keys(sent).sort(), 'PUT body is exactly the changed field + schema').toEqual(['bodyStyle', 'schema']);

        // Server state: the changed field landed and the unsent field survived the sparse merge.
        let row = null;
        await expect.poll(async () => {
            const r = await getSettings(request, bookOid);
            if (r.status() !== 200) return 'HTTP ' + r.status();
            row = await r.json();
            return row.bodyStyle;
        }, { timeout: 30000, message: 'bodyStyle updated by sparse UI save' }).toBe(newComp);
        expect(row.imageAction, 'unsent imageAction survives').toBe(seedAction);
        expect(row.objectId, 'same stored row').toBe(seededRow.objectId);
        expect(row.name).toBe('book-sdConfig');

        await expect(save).toBeDisabled({ timeout: 10000 });
    });

    // P0-10d verifier Medium: a FAILED GET .../settings must not be treated as "no row". Before the fix the
    // panel seeded from sdcfg-default and the next Save/Render PUT the whole seed over the real stored row.
    // The stack is live; only the browser's first GET of this book's settings is failed (page.route → 503)
    // so the failure path is reachable. Retry goes to the real endpoint.
    test('a failed settings GET shows an error + Retry (no seed, no Save, no PUT); Retry loads the real stored row', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-ui-getfail');
        const value = 'e2e-comp-getfail-' + Date.now();
        const seeded = await putSettings(request, bookOid, { schema: 'olio.sd.config', bodyStyle: value });
        expect(seeded.status(), 'REST seed PUT: ' + await seeded.text()).toBe(200);
        const seededRow = await seeded.json();

        let failed = 0;
        const puts = [];
        await page.route('**/chap-book/' + bookOid + '/settings', async route => {
            const method = route.request().method();
            if (method === 'PUT') puts.push(route.request().postData());
            if (method === 'GET' && failed === 0) {
                failed++;
                await route.fulfill({ status: 503, contentType: 'text/plain', body: 'e2e forced failure' });
                return;
            }
            await route.continue();
        });

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
        const toolbar = bookSdToolbar(page);
        await expect(toolbar).toBeVisible({ timeout: 30000 });
        await toolbar.locator('button:has-text("Book image settings")').click();

        // Error state, not the seed: no Composition input, no Save button, no stuck spinner.
        const err = toolbar.locator('[data-testid="cb-book-sd-load-error"]');
        await expect(err).toBeVisible({ timeout: 30000 });
        await expect(err).toContainText('503');
        await expect(toolbar.locator('label:text-is("Composition") + input')).toHaveCount(0);
        await expect(toolbar.locator(SAVE_BOOK_SD)).toHaveCount(0);
        await expect(toolbar.getByText('Loading SD configuration…')).toHaveCount(0);
        expect(failed, 'exactly one GET was failed').toBe(1);

        // Retry hits the live endpoint: the STORED value appears and there is nothing pending to save.
        await toolbar.locator('[data-testid="cb-book-sd-retry"]').click();
        await expect(compositionInput(toolbar)).toHaveValue(value, { timeout: 30000 });
        await expect(err).toHaveCount(0);
        await expect(toolbar.locator(SAVE_BOOK_SD)).toBeDisabled();

        // The server row is untouched: same objectId, same value, and the page sent no PUT at all.
        const g = await getSettings(request, bookOid);
        expect(g.status()).toBe(200);
        const row = await g.json();
        expect(row.objectId).toBe(seededRow.objectId);
        expect(row.bodyStyle).toBe(value);
        expect(puts, 'no PUT from the page').toEqual([]);
    });
});
