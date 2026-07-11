/**
 * Gallery/Group export to ZIP (KI-17) — character portrait gallery surface.
 * Verifies the Export/Download affordance also appears inside page.imageGallery(), the pop-in
 * dialog opened from a charPerson view (not just the views/list.js grid/list toolbar).
 * Uses the shared persistent test user (ensureSharedTestUser — NEVER admin).
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, createObject } from './helpers/api.js';
import fs from 'fs';
import path from 'path';

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

test.describe('Character gallery export (KI-17)', () => {
    let shared, charPerson;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);
        let ctx = await newCtx();
        await loginCtx(ctx, shared);

        let charDir = await ensurePath(ctx, 'auth.group', 'data', '~/CharGalleryExportE2E');
        expect(charDir && charDir.id).toBeTruthy();

        // No portrait is set on this character, so page.imageGallery() falls back to the
        // shared ~/Gallery directory — matches the code path exercised by portraitsAndGallery.spec.js.
        charPerson = await createObject(ctx, 'olio.charPerson', {
            name: 'CharGalleryExportE2E_' + Date.now(),
            firstName: 'Gallery', lastName: 'ExportTest', gender: 'female', alignment: 'neutralgood',
            groupId: charDir.id, groupPath: charDir.path
        });
        expect(charPerson && charPerson.objectId).toBeTruthy();

        let galleryDir = await ensurePath(ctx, 'auth.group', 'data', '~/Gallery');
        expect(galleryDir && galleryDir.id).toBeTruthy();

        let imgPath = path.resolve('public', 'media', 'femaleSilhouette.png');
        let imgB64 = fs.existsSync(imgPath) ? fs.readFileSync(imgPath).toString('base64') : null;
        expect(imgB64).toBeTruthy();

        let img = await createObject(ctx, 'data.data', {
            groupId: galleryDir.id,
            groupPath: galleryDir.path,
            name: 'chargallery-export-' + Date.now() + '.png',
            contentType: 'image/png',
            dataBytesStore: imgB64
        });
        expect(img && img.objectId).toBeTruthy();

        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test.beforeEach(async ({ page }) => {
        await attachSharedSession(page, shared);
    });

    test('character view gallery pop-in shows Export button, exporting shows a Download button, and downloading returns a real ZIP', async ({ page }) => {
        test.setTimeout(60000);

        await page.goto('/#!/view/olio.charPerson/' + charPerson.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.charPerson/'), { timeout: 15000 });
        await page.waitForTimeout(3000);

        let main = page.getByRole('main');

        let galleryBtn = main.locator('button:has(span:text("photo_library"))');
        await expect(galleryBtn).toBeVisible({ timeout: 15000 });
        await galleryBtn.click();

        // The dialog mounts as a sibling of <main> (outside it), not nested inside — confirmed via
        // the Playwright accessibility snapshot, so these locators are scoped to the dialog role
        // rather than to <main> like the list.js (KI-17) test.
        let dialog = page.getByRole('dialog');
        await expect(dialog.locator('text=/Gallery \\(/')).toBeVisible({ timeout: 10000 });

        let exportBtn = dialog.locator('.am7-dialog-footer button:has-text("Export")').first();
        await expect(exportBtn).toBeVisible({ timeout: 10000 });
        await exportBtn.click();

        await expect(page.locator('text=/Export complete/')).toBeVisible({ timeout: 20000 });

        let downloadBtn = dialog.locator('.am7-dialog-footer button:has-text("Download")').first();
        await expect(downloadBtn).toBeVisible({ timeout: 10000 });

        let [resp] = await Promise.all([
            page.context().waitForEvent('response', r => r.url().includes('/groupExport/') && r.url().includes('/download')),
            downloadBtn.click()
        ]);
        expect(resp.status()).toBe(200);
        expect(resp.headers()['content-type']).toContain('zip');
        expect(resp.headers()['content-disposition']).toContain('attachment');
    });
});
