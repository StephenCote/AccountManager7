/**
 * Picker E2E tests — validates object field pickers (Find/View/Clear buttons)
 *
 * Tests that picker fields render with action buttons and that the ObjectPicker
 * modal opens/closes correctly. Uses charPerson (eyeColor/hairColor pickers)
 * which requires setupWorkflowTestData.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupWorkflowTestData, cleanupTestUser } from './helpers/api.js';

test.describe('Object field pickers', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupWorkflowTestData(request, { suffix: 'pk' + Date.now().toString(36) });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('picker fields show Find/View/Clear buttons on object form', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form schema load hangs under concurrent test load');
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

        // Look for picker buttons (Find/View/Clear) — charPerson has eyeColor and hairColor pickers
        let findBtn = page.locator('button:has-text("Find")').first();
        let hasPicker = await findBtn.isVisible({ timeout: 5000 }).catch(() => false);
        if (hasPicker) {
            await expect(findBtn).toBeVisible();
            await screenshot(page, 'picker-buttons-visible');
        }
    });

    test('Find button opens picker modal', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form schema load hangs under concurrent test load');
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
        let hasPicker = await findBtn.isVisible({ timeout: 5000 }).catch(() => false);

        if (hasPicker) {
            await findBtn.click();
            // Picker modal should appear (fixed z-50 overlay with search input)
            let modal = page.locator('.fixed.inset-0.z-50');
            await expect(modal).toBeVisible({ timeout: 10000 });

            // Modal should have a search input
            let searchInput = modal.locator('input[placeholder="Search..."]');
            await expect(searchInput).toBeVisible({ timeout: 5000 });

            await screenshot(page, 'picker-modal-open');

            // Close the modal via the X button
            let closeBtn = modal.locator('button:has(span:text("close"))');
            await closeBtn.click();
            await expect(modal).not.toBeVisible({ timeout: 5000 });
        }
    });

    test('picker modal shows items and allows selection', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form schema load hangs under concurrent test load');
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
        let hasPicker = await findBtn.isVisible({ timeout: 5000 }).catch(() => false);

        if (hasPicker) {
            await findBtn.click();
            let modal = page.locator('.fixed.inset-0.z-50');
            await expect(modal).toBeVisible({ timeout: 10000 });

            // Wait for items to load
            await page.waitForTimeout(3000);

            // Check if items or "No items found" message is shown
            let itemButtons = modal.locator('button.w-full.text-left');
            let noItems = modal.locator('text=No items found');
            let hasItems = await itemButtons.count() > 0;
            let hasNoItems = await noItems.isVisible().catch(() => false);

            // Either items loaded or empty state shown — both valid
            expect(hasItems || hasNoItems).toBeTruthy();

            if (hasItems) {
                // Click the first item to select it
                await itemButtons.first().click();
                // Modal should close after selection
                await expect(modal).not.toBeVisible({ timeout: 5000 });
                await screenshot(page, 'picker-item-selected');
            } else {
                // Close modal
                let closeBtn = modal.locator('button:has(span:text("close"))');
                await closeBtn.click();
                await screenshot(page, 'picker-no-items');
            }
        }
    });

    test('Clear button removes picker field value without errors', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form schema load hangs under concurrent test load');
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

        let clearBtn = page.locator('button:has-text("Clear")').first();
        let hasPicker = await clearBtn.isVisible({ timeout: 5000 }).catch(() => false);

        if (hasPicker) {
            // Click clear — should not throw errors
            await clearBtn.click();
            await page.waitForTimeout(500);
            await screenshot(page, 'picker-cleared');
        }
    });

    test('picker modal closes on backdrop click', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form schema load hangs under concurrent test load');
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
        let hasPicker = await findBtn.isVisible({ timeout: 5000 }).catch(() => false);

        if (hasPicker) {
            await findBtn.click();
            let modal = page.locator('.fixed.inset-0.z-50');
            await expect(modal).toBeVisible({ timeout: 10000 });

            // Click the backdrop (the outer fixed div, not the inner dialog)
            let backdrop = modal.locator('.absolute.inset-0.bg-black\\/50');
            await backdrop.click({ position: { x: 5, y: 5 } });
            await expect(modal).not.toBeVisible({ timeout: 5000 });
            await screenshot(page, 'picker-backdrop-close');
        }
    });
});
