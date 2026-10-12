/**
 * KI-19 — the generic "uri"/object-link field (system.primaryKey.uri, rendered by
 * formFieldRenderers.js's "object-link" renderer) had two bugs and one layout problem:
 *   a) the built URI duplicated "/rest" (client.base() already includes it) -> ".../rest/rest/model/...".
 *   b) the URI pointed at the default minimal-field stub instead of "/full" (fully populated record).
 *   c) data.groupExport had no formDef.js/modelDef.js entry at all, so uri (and every other field)
 *      rendered on a single unstructured tab instead of the Info sub-tab every other model uses.
 * This test builds a real group export via the live REST endpoint, then verifies the fix live in
 * the browser: the Info tab exists and holds the link, and the link's href is well-formed.
 *
 * Current intended layout (formDef.js forms.groupExport, e225b6b7 2026-07-24): the default "Export"
 * tab shows sourceGroup + archive as read-only `foreign-summary` links (name + `link` icon, href to
 * the REFERENCED record's /full) plus itemCount/generatedDate; the container's own uri object-link
 * lives on the "Info" sub-tab (forms.groupdateinfo). The KI-19 regression this guards against is a
 * link on the Export tab that points back at the container itself.
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, createObject } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
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
    let shared, groupExportObjectId, sourceGroup, archive;

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

        // The view page loads this same /full payload; capture the two foreign records so the
        // Export-tab assertions below are against known identities, not the test's own guess.
        let full = await (await ctx.get(REST + '/model/data.groupExport/' + groupExportObjectId + '/full')).json();
        sourceGroup = full && full.sourceGroup;
        archive = full && full.archive;
        expect(sourceGroup && sourceGroup.objectId, 'groupExport.sourceGroup must be populated by /full').toBeTruthy();
        expect(sourceGroup.objectId).toBe(dir.objectId);
        expect(archive && archive.objectId && archive.name, 'groupExport.archive must be populated by /full').toBeTruthy();

        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test.beforeEach(async ({ page }) => {
        await attachSharedSession(page, shared);
    });

    async function openView(page) {
        await page.goto('/#!/view/data.groupExport/' + groupExportObjectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/data.groupExport/'), { timeout: 15000 });
        let main = page.getByRole('main');
        // Scoped with ":visible" — this app mounts a second, offscreen/hidden copy of the DOM (a
        // pre-existing quirk, also noted in e2e/groupExport.spec.js), which plain locators match too.
        await expect(main.locator('button:visible:has-text("Export")')).toBeVisible({ timeout: 15000 });
        return main;
    }

    test('groupExport view page puts uri link in the Info tab with a well-formed href', async ({ page }) => {
        test.setTimeout(30000);
        let main = await openView(page);

        // Export tab (default): the two foreign-summary links are present and labelled with the
        // referenced records' names ...
        let exportLinks = main.locator('a:visible:has(span:text("link"))');
        await expect(exportLinks).toHaveCount(2);
        await expect(exportLinks.filter({ hasText: sourceGroup.name })).toHaveCount(1);
        await expect(exportLinks.filter({ hasText: archive.name })).toHaveCount(1);
        // ... and none of them is the container's own uri (the KI-19 "object-link on a foreign field
        // points back at the container" regression). The uri link belongs on the Info tab only.
        await expect(main.locator('a:visible[href*="/model/data.groupExport/"]')).toHaveCount(0);
        await expect(exportLinks.filter({ hasText: /^\s*link\s*export-container-/ })).toHaveCount(0);

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
        expect(href).toContain('/model/data.groupExport/' + groupExportObjectId + '/full');
    });

    test('Export tab foreign-summary links resolve to the referenced records (/full), not about:blank', async ({ page }) => {
        test.setTimeout(30000);
        let main = await openView(page);

        // formFieldRenderers.js renderers["foreign-summary"]: href = client.base() + "/model/" +
        // <model> + "/" + fk.objectId + "/full" — i.e. the referenced auth.group / data.data records.
        let sourceLink = main.locator('a:visible:has(span:text("link"))').filter({ hasText: sourceGroup.name });
        let archiveLink = main.locator('a:visible:has(span:text("link"))').filter({ hasText: archive.name });
        await expect(sourceLink).toHaveCount(1);
        await expect(archiveLink).toHaveCount(1);

        // NOTE 2026-10-12: red on the live stack. Both hrefs render as "about:blank" because the
        // renderer reads the model name from fk[am7model.jsonModelKey] ("schema"), and nested records
        // in GET /rest/model/data.groupExport/{id}/full carry NO schema key (RecordSerializer condenses
        // model declarations below the top level). The renderer has the field's declared foreign type
        // (ctx.field.baseModel) available as a fallback and does not use it. Product defect in
        // src/components/formFieldRenderers.js:703-708; left red deliberately.
        await expect(sourceLink).toHaveAttribute('href', new RegExp('/rest/model/auth\\.group/' + sourceGroup.objectId + '/full$'));
        await expect(archiveLink).toHaveAttribute('href', new RegExp('/rest/model/data\\.data/' + archive.objectId + '/full$'));
    });
});
