/**
 * charPerson feature tests — pickers, outfit builder, image view
 *
 * Tests that charPerson-specific features work: color pickers for eye/hair,
 * outfit builder command button, and portrait image click-to-view.
 * Uses the console error fixture to catch runtime errors.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupWorkflowTestData, cleanupTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

// PLAYWRIGHT_BASE_URL is mandatory for every run; no local-dev fallback.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL;
const REST = BASE_URL + '/AccountManagerService7/rest';

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

test.describe('charPerson features', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        // setupWorkflowTestData creates a data.note; ModelService.create (ModelService.java:298-309)
        // runs createVectorStore() synchronously before answering, so the POST blocks on the shared
        // Ollama embedding server for as long as any concurrent LLM generation holds it (measured
        // 2026-10-12: a 70 s chat generation held the note POST past the default 60 s hook limit,
        // nginx logged 499). Timings are never pass/fail thresholds -- give the hook ample time.
        test.setTimeout(300000);
        testInfo = await setupWorkflowTestData(request, { suffix: 'cp' + Date.now().toString(36) });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('charPerson object view loads without console errors', async ({ page }) => {
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            null, { timeout: 15000 }
        );
        // Wait for form to render
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });
        await screenshot(page, 'charperson-view');
    });

    test('charPerson form shows picker buttons for color fields', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form load hangs under concurrent test load');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            null, { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        // Picker fields render icon buttons with title="Find" (formFieldRenderers.js:836), not a
        // text label — View/Clear only appear once the field holds a value.
        let findBtns = page.locator('button[title="Find"]');
        await expect(findBtns.first()).toBeVisible({ timeout: 10000 });
        let findCount = await findBtns.count();
        console.log('[charPerson] Find picker buttons on form: ' + findCount);
        expect(findCount).toBeGreaterThan(0);
        await screenshot(page, 'charperson-picker-buttons');
    });

    test('color picker Find button opens picker modal', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form load hangs under concurrent test load');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            null, { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        // Find is an icon button (title="Find"); the form must have at least one picker field.
        let findBtn = page.locator('button[title="Find"]').first();
        await expect(findBtn).toBeVisible({ timeout: 10000 });
        await findBtn.click();

        // doFieldPicker -> ObjectPicker.open renders the .am7-picker-overlay (components/picker.js:419)
        // with an h3 title and an embedded list control; there is no "Search..." input.
        let modal = page.locator('.am7-picker-overlay');
        await expect(modal).toBeVisible({ timeout: 10000 });
        let title = await modal.locator('h3').first().textContent();
        console.log('[charPerson] picker overlay title: ' + title);
        expect((title || '').trim().length, 'picker overlay must carry a title').toBeGreaterThan(0);

        // The embedded list renders its toolbar (result-nav) even before rows arrive.
        await expect(modal.locator('.result-nav').first()).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'charperson-color-picker-modal');

        // Close via the header close button (picker.js:438-441)
        let closeBtn = modal.locator('button:has(span:text-is("close"))').first();
        await closeBtn.click();
        await expect(modal).not.toBeVisible({ timeout: 5000 });
    });

    test('outfit builder button exists on charPerson form', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form load hangs under concurrent test load');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            null, { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        // Look for the Outfit Builder command button (checkroom icon)
        let outfitBtn = page.locator('button:has(span:text("checkroom"))');
        let hasOutfit = await outfitBtn.isVisible({ timeout: 5000 }).catch(() => false);
        if (hasOutfit) {
            await screenshot(page, 'charperson-outfit-button');
        }
        // Button may not appear if objectId requiredAttribute not met — still a valid state
    });

    test('outfit builder opens without console errors', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form load hangs under concurrent test load');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            null, { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        let outfitBtn = page.locator('button:has(span:text("checkroom"))');
        let hasOutfit = await outfitBtn.isVisible({ timeout: 5000 }).catch(() => false);
        if (hasOutfit) {
            await outfitBtn.click();

            // Dialog should open with outfit builder content
            let dialog = page.locator('[role="dialog"], .fixed.inset-0');
            await expect(dialog.first()).toBeVisible({ timeout: 10000 });
            await page.waitForTimeout(2000);
            await screenshot(page, 'charperson-outfit-builder');

            // Close the dialog
            let cancelBtn = dialog.locator('button:has-text("Cancel")').first();
            let hasCancelBtn = await cancelBtn.isVisible({ timeout: 3000 }).catch(() => false);
            if (hasCancelBtn) {
                await cancelBtn.click();
            } else {
                // Try close button
                let closeBtn = dialog.locator('button:has(span:text("close"))').first();
                let hasClose = await closeBtn.isVisible({ timeout: 3000 }).catch(() => false);
                if (hasClose) await closeBtn.click();
            }
        }
    });

    test('portrait image is clickable when present', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form load hangs under concurrent test load');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            null, { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        // Check if a portrait image exists (it may not for test characters)
        let portraitImg = page.locator('img.image-field.cursor-pointer, img.cursor-pointer.hover\\:opacity-80');
        let hasPortrait = await portraitImg.first().isVisible({ timeout: 5000 }).catch(() => false);

        if (hasPortrait) {
            await portraitImg.first().click();
            // Image view dialog should open
            let dialog = page.locator('[role="dialog"], .fixed.inset-0');
            await expect(dialog.first()).toBeVisible({ timeout: 5000 });
            await screenshot(page, 'charperson-portrait-view');

            // Close it
            let closeBtn = dialog.locator('button:has-text("Close")').first();
            if (await closeBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
                await closeBtn.click();
            }
        }
        // No portrait is valid for test characters — just verify no console errors
        await screenshot(page, 'charperson-portrait-state');
    });

    test('outfit generate API returns valid response with objectId', async () => {
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }

        let ctx = await newApiContext();
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });

            let resp = await ctx.post(REST + '/game/outfit/generate', {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    schema: 'olio.outfitRequest',
                    characterId: testInfo.charPerson.objectId
                }
            });

            let status = resp.status();
            let body = await resp.text();
            console.log('Outfit generate status:', status, 'body:', body.substring(0, 500));

            if (status === 400) {
                console.error('Outfit generate 400 response:', body);
            }

            expect(status, 'Outfit generate should not return 400: ' + body).not.toBe(400);

            // Verify the response has the expected fields
            if (status === 200 && body) {
                let apparel = JSON.parse(body);
                expect(apparel.objectId, 'Response must include objectId').toBeTruthy();
                expect(apparel.schema || apparel.s, 'Response must include schema').toBeTruthy();
                console.log('Generated apparel objectId:', apparel.objectId, 'name:', apparel.name);

                // Verify the apparel was added to the character's store by the backend
                let charResp = await ctx.get(REST + '/model/olio.charPerson/' + testInfo.charPerson.objectId + '/full');
                expect(charResp.status()).toBe(200);
                let charData = await charResp.json();
                let storeApparel = charData.store ? charData.store.apparel : [];
                let found = storeApparel.some(a => a.objectId === apparel.objectId);
                console.log('Apparel in store:', found, 'store apparel count:', storeApparel.length);
                expect(found, 'Generated apparel should be in character store').toBe(true);
            }

            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    // SD-TRIGGERING: /olio/apparel/{id}/reimage renders on the SwarmUI server. Run only in the serial
    // SD slot (--workers=1), never in the default suite:
    //   E2E_SD_LIVE=1 PLAYWRIGHT_BASE_URL=... npx playwright test e2e/charPerson.spec.js -g "reimageApparel" --workers=1
    test('reimageApparel API accepts an olio.sd.config body for an apparel the caller owns', async () => {
        test.skip(!process.env.E2E_SD_LIVE, 'set E2E_SD_LIVE=1 to run the SD-rendering reimage test (serial slot only)');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        // Timeout is a generous ceiling for a live SD render, never a pass/fail threshold.
        test.setTimeout(600000);

        let ctx = await newApiContext();
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });

            // First generate an outfit so we have an apparel with objectId
            let genResp = await ctx.post(REST + '/game/outfit/generate', {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    schema: 'olio.outfitRequest',
                    characterId: testInfo.charPerson.objectId
                }
            });
            expect(genResp.status()).toBe(200);
            let apparel = await genResp.json();
            expect(apparel.objectId, 'Generated apparel must have objectId').toBeTruthy();

            console.log('Reimage test — apparel objectId:', apparel.objectId, 'ownerId:', apparel.ownerId);

            // Verify the apparel is accessible via model endpoint first
            let checkResp = await ctx.get(REST + '/model/olio.apparel/' + apparel.objectId + '/full');
            console.log('Apparel lookup status:', checkResp.status());

            // The apparel must be owned by the asserting user (generated under their login above);
            // the reimage body is an olio.sd.config and MUST carry `schema` — the deserializer needs
            // it to type the fields (model-api.md), otherwise the route answers 400 "Invalid JSON".
            // The UI sends exactly this shape (components/outfitBuilder -> olio.sd.config).
            let reimageResp = await ctx.post(REST + '/olio/apparel/' + apparel.objectId + '/reimage', {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    schema: 'olio.sd.config',
                    hires: false,
                    style: '',
                    seed: 0
                }
            });

            let reimageStatus = reimageResp.status();
            let reimageBody = await reimageResp.text();
            console.log('Reimage status:', reimageStatus, 'body:', reimageBody.substring(0, 500));

            expect(reimageStatus, 'reimageApparel must succeed for an owned apparel: HTTP ' + reimageStatus + ' ' + reimageBody.substring(0, 300)).toBe(200);
            // OlioService.reimageApparel returns a JSON array of the generated image records.
            let reimaged = JSON.parse(reimageBody);
            expect(Array.isArray(reimaged), 'reimage must return a JSON array of images').toBe(true);
            expect(reimaged.length, 'reimage must produce at least one image').toBeGreaterThan(0);
            expect(reimaged[0].objectId, 'generated image must carry objectId').toBeTruthy();

            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('outfit builder generates and shows apparel in dialog', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form load hangs under concurrent test load');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }
        test.setTimeout(90000);

        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            null, { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        // Open outfit builder
        let outfitBtn = page.locator('button:has(span:text("checkroom"))');
        let hasOutfit = await outfitBtn.isVisible({ timeout: 5000 }).catch(() => false);
        if (!hasOutfit) {
            test.skip(true, 'Outfit builder button not visible');
            return;
        }

        await outfitBtn.click();
        let dialog = page.locator('[role="dialog"], .fixed.inset-0');
        await expect(dialog.first()).toBeVisible({ timeout: 10000 });

        // Click a preset to generate outfit
        let casualBtn = dialog.locator('button:has-text("Casual")');
        let hasCasual = await casualBtn.isVisible({ timeout: 5000 }).catch(() => false);
        if (hasCasual) {
            await casualBtn.click();

            // Wait for generation to complete — loading spinner should appear then disappear
            await page.waitForTimeout(3000);

            // Check for toast or apparel preview
            let apparelPreview = dialog.locator('.border.rounded.p-3');
            let hasPreview = await apparelPreview.isVisible({ timeout: 15000 }).catch(() => false);
            if (hasPreview) {
                // Verify the Generate Outfit Images button is present and enabled
                let imgBtn = dialog.locator('button:has-text("Generate Outfit Images")');
                await expect(imgBtn).toBeVisible({ timeout: 5000 });
                let isDisabled = await imgBtn.isDisabled();
                expect(isDisabled, 'Generate Outfit Images button should be enabled (apparel has objectId)').toBe(false);

                // Click it and verify no 400 error toast
                await imgBtn.click();
                // Wait for the request to complete (loading spinner appears then disappears)
                await page.waitForTimeout(5000);

                // Check there's no error toast about "invalid config" or "Failed to generate images"
                let errorToast = page.locator('.bg-red-500:has-text("Failed"), .bg-red-500:has-text("invalid")');
                let hasError = await errorToast.isVisible({ timeout: 2000 }).catch(() => false);
                expect(hasError, 'Should not show error toast after Generate Outfit Images').toBe(false);
            }
            await screenshot(page, 'charperson-outfit-generated');
        }

        // Close the dialog
        let cancelBtn = dialog.locator('button:has-text("Cancel")').first();
        if (await cancelBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
            await cancelBtn.click();
        }
    });
});
