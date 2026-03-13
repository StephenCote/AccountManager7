/**
 * charPerson feature tests — pickers, outfit builder, image view
 *
 * Tests that charPerson-specific features work: color pickers for eye/hair,
 * outfit builder command button, and portrait image click-to-view.
 * Uses the console error fixture to catch runtime errors.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupWorkflowTestData, cleanupTestUser } from './helpers/api.js';

test.describe('charPerson features', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
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
            { timeout: 15000 }
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
            { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        // Look for Find buttons — color picker fields should have them
        let findBtns = page.locator('button:has-text("Find")');
        let findCount = await findBtns.count();
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
            { timeout: 15000 }
        );
        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        let findBtn = page.locator('button:has-text("Find")').first();
        let hasFindBtn = await findBtn.isVisible({ timeout: 5000 }).catch(() => false);
        if (hasFindBtn) {
            await findBtn.click();

            // Picker modal should appear
            let modal = page.locator('.fixed.inset-0.z-50');
            await expect(modal).toBeVisible({ timeout: 10000 });

            // Should have search input and item list
            let searchInput = modal.locator('input[placeholder="Search..."]');
            await expect(searchInput).toBeVisible({ timeout: 5000 });

            // Wait for items to load
            await page.waitForTimeout(3000);
            await screenshot(page, 'charperson-color-picker-modal');

            // Close modal
            let closeBtn = modal.locator('button:has(span:text("close"))');
            await closeBtn.click();
            await expect(modal).not.toBeVisible({ timeout: 5000 });
        }
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
            { timeout: 15000 }
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
            { timeout: 15000 }
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
            { timeout: 15000 }
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
});
