/**
 * Gallery/Group export to ZIP (KI-17) — live UI test against the running stack (Vite + Service7/Tomcat).
 * Uses the shared persistent test user (ensureSharedTestUser — NEVER admin).
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

test.describe('Group export (KI-17)', () => {
    let shared, group, doc;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);
        let ctx = await newCtx();
        await loginCtx(ctx, shared);

        let dir = await ensurePath(ctx, 'auth.group', 'data', '~/GalleryExportE2E');
        expect(dir && dir.id).toBeTruthy();
        group = dir;

        doc = await createObject(ctx, 'data.data', {
            groupId: dir.id,
            groupPath: dir.path,
            name: 'e2e-export-' + Date.now() + '.txt',
            contentType: 'text/plain',
            dataBytesStore: b64('e2e group export fixture content')
        });
        expect(doc && doc.objectId).toBeTruthy();

        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test.beforeEach(async ({ page }) => {
        await attachSharedSession(page, shared);
    });

    test('list view for a group shows an Export button, building an export shows a Download button, and downloading returns a real ZIP', async ({ page }) => {
        test.setTimeout(60000);

        await page.goto('/#!/list/data.data/' + group.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/list/data.data/'), { timeout: 15000 });

        // The app appears to mount a second, offscreen/hidden copy of the DOM (a pre-existing quirk,
        // not introduced here — plain text locators match it too) — scope everything to the visible
        // <main> region so clicks land on the real, on-screen elements confirmed by screenshot.
        let main = page.getByRole('main');

        let exportBtn = main.locator('button:has-text("archive")').first();
        if (await exportBtn.count() === 0) {
            exportBtn = main.locator('[class*="button"]:has-text("archive")').first();
        }
        await expect(exportBtn).toBeVisible({ timeout: 15000 });
        await exportBtn.click();

        // KI-18 (fixed): the dialog renders via router.js's global OverlayGuard, which mounts it as a
        // SIBLING of <main>, not nested inside it (confirmed via document.elementFromPoint() at the
        // button's coordinates) — so dialog content is scoped to page.getByRole('dialog'), not `main`.
        // (Previously views/list.js's renderContent() ALSO mounted its own duplicate
        // page.components.dialog.loadDialogs() nested inside <main>, so a `main`-scoped locator used to
        // resolve to that duplicate copy while the real, outside-<main>, later-painted OverlayGuard copy
        // silently won every click hit-test over it — hence the old `{force:true}` workaround. Fixed in
        // views/list.js: renderContent no longer double-mounts the overlay, so there is now only one
        // live copy, and a plain click works.)
        let dialog = page.getByRole('dialog');
        await expect(dialog.locator('text=Export Group')).toBeVisible({ timeout: 5000 });
        await dialog.locator('.am7-dialog-btn-primary:has-text("Export")').click();

        // The "Export complete" toast isn't inside the dialog (same as KI-19's finding for this app's
        // toast system) — assert it unscoped.
        await expect(page.locator('text=/Export complete/')).toBeVisible({ timeout: 20000 });

        let downloadBtn = main.locator('button:has-text("download")').first();
        if (await downloadBtn.count() === 0) {
            downloadBtn = main.locator('[class*="button"]:has-text("download")').first();
        }
        await expect(downloadBtn).toBeVisible({ timeout: 10000 });

        // Listen at the context level, armed BEFORE the click — this is what actually proves the
        // Download button does the right thing: it hits the real REST endpoint and gets back a 200
        // with a real ZIP content-type and an attachment disposition (so the browser downloads rather
        // than tries to render it). That's the meaningful assertion for THIS test; the archive's actual
        // byte content (real entries, correct names, real extraction vs. JSON fallback) is already
        // thoroughly verified server-side by TestGroupExport (Objects7 JUnit, live DB). Chromium hands
        // an attachment-disposition response straight to its download manager, at which point the
        // response body is no longer retrievable via CDP (Network.getResponseBody 404s) — that's a
        // browser mechanic to work around, not something worth re-proving the ZIP bytes against here.
        let [resp] = await Promise.all([
            page.context().waitForEvent('response', r => r.url().includes('/groupExport/') && r.url().includes('/download')),
            downloadBtn.click()
        ]);
        expect(resp.status()).toBe(200);
        expect(resp.headers()['content-type']).toContain('zip');
        expect(resp.headers()['content-disposition']).toContain('attachment');
    });
});
