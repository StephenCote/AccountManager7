/**
 * KI-19 — the generic "uri"/object-link field (system.primaryKey.uri, rendered by
 * formFieldRenderers.js's "object-link" renderer) had two bugs and one layout problem:
 *   a) the built URI duplicated "/rest" (client.base() already includes it) -> ".../rest/rest/model/...".
 *   b) the URI pointed at the default minimal-field stub instead of "/full" (fully populated record).
 *   c) data.groupExport had no formDef.js/modelDef.js entry at all, so uri (and every other field)
 *      rendered on a single unstructured tab instead of the Info sub-tab every other model uses.
 * This test builds a real group export via the live REST endpoint, then verifies the fix live in
 * the browser: the Info tab exists and holds the link, and the link's href is well-formed.
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, createObject } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }

async function loginCtx(ctx, shared) {
    await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password'
        }
    });
}

async function attachSharedSession(page, shared) {
    let ctx = await newCtx();
    await loginCtx(ctx, shared);
    let cookies = (await ctx.storageState()).cookies;
    await page.context().addCookies(cookies);
    await ctx.dispose();
}

test.describe('object-link field fix (KI-19)', () => {
    let shared, groupExportObjectId;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);
        let ctx = await newCtx();
        await loginCtx(ctx, shared);

        let dir = await ensurePath(ctx, 'auth.group', 'data', '~/ObjectLinkFixE2E');
        expect(dir && dir.id).toBeTruthy();

        let doc = await createObject(ctx, 'data.data', {
            groupId: dir.id, groupPath: dir.path,
            name: 'objectlinkfix-' + Date.now() + '.txt',
            contentType: 'text/plain',
            dataBytesStore: b64('e2e object-link fixture content')
        });
        expect(doc && doc.objectId).toBeTruthy();

        let resp = await ctx.post(REST + '/groupExport/data.data/' + dir.objectId);
        let container = await resp.json();
        expect(container && container.objectId).toBeTruthy();
        groupExportObjectId = container.objectId;

        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test.beforeEach(async ({ page }) => {
        await attachSharedSession(page, shared);
    });

    test('groupExport view page puts uri link in the Info tab with a well-formed href', async ({ page }) => {
        test.setTimeout(30000);

        await page.goto('/#!/view/data.groupExport/' + groupExportObjectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/data.groupExport/'), { timeout: 15000 });
        await page.waitForTimeout(2000);

        let main = page.getByRole('main');

        // The main/default tab should NOT carry the uri link directly. Scoped with the ":visible"
        // pseudo-class, not just role=main — this app mounts a second, offscreen/hidden copy of the
        // DOM (a pre-existing quirk, also noted in e2e/groupExport.spec.js), which plain locators
        // match too.
        let mainLink = main.locator('a:visible:has(span:text("link"))');
        expect(await mainLink.count()).toBe(0);

        // An "Info" tab should exist and, once selected, hold the link.
        let infoTab = main.locator('button:visible:has-text("Info")');
        await expect(infoTab).toBeVisible({ timeout: 10000 });
        await infoTab.click();

        let link = main.locator('a:visible:has(span:text("link"))').first();
        await expect(link).toBeVisible({ timeout: 10000 });

        let href = await link.getAttribute('href');
        expect(href).toBeTruthy();
        expect(href).not.toContain('/rest/rest');
        expect(href).toMatch(/\/rest\/model\/data\.groupExport\/.+\/full$/);
    });
});
