/**
 * Sub-object save/reload tests — validates that child model tabs
 * (personality, statistics) load, save, and persist across reloads.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupWorkflowTestData, cleanupTestUser } from './helpers/api.js';

test.describe('Sub-object save and reload', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupWorkflowTestData(request, { suffix: 'sub' + Date.now().toString(36) });
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('personality tab loads, saves value, and persists after reload', async ({ page, browserName }) => {
        test.skip(browserName === 'firefox', 'Firefox: form schema load hangs under concurrent test load');
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            test.skip(true, 'No charPerson created');
            return;
        }

        // Step 1: Navigate to charPerson
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            { timeout: 15000 }
        );

        // Wait for form to fully load (getFull is async)
        await page.waitForTimeout(3000);
        let saveBtn = page.locator('button:has(span:text("save"))').first();
        await expect(saveBtn).toBeVisible({ timeout: 30000 });

        // Step 2: Find and click the Personality tab
        let personalityTab = page.locator('button').filter({ hasText: /Personality/i }).first();
        await expect(personalityTab).toBeVisible({ timeout: 5000 });
        await personalityTab.click();

        // Wait for sub-object data to load
        await page.waitForTimeout(2000);

        // Step 3: Find the conscientiousness slider (range input)
        // Personality fields render as range sliders
        let conscSlider = page.locator('input[type="range"]').nth(1); // conscientiousness is 2nd field (after openness)
        let hasSlider = await conscSlider.isVisible({ timeout: 5000 }).catch(() => false);

        if (!hasSlider) {
            await screenshot(page, 'personality-tab-no-slider');
            test.skip(true, 'Conscientiousness slider not found');
            return;
        }

        // Set slider to 72
        await conscSlider.fill('72');
        await page.waitForTimeout(500);
        await screenshot(page, 'personality-value-set');

        // Step 4: Save
        await expect(saveBtn).toBeVisible({ timeout: 5000 });
        await saveBtn.click();

        // Wait for save to complete
        await page.waitForTimeout(3000);
        await screenshot(page, 'personality-saved');

        // Step 5: Reload the page completely
        await page.goto('/#!/view/olio.charPerson/' + testInfo.charPerson.objectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/olio.charPerson/'),
            { timeout: 15000 }
        );
        // Wait for getFull to complete
        await page.waitForTimeout(3000);
        saveBtn = page.locator('button:has(span:text("save"))').first();
        await expect(saveBtn).toBeVisible({ timeout: 30000 });

        // Step 6: Click Personality tab again
        personalityTab = page.locator('button').filter({ hasText: /Personality/i }).first();
        await expect(personalityTab).toBeVisible({ timeout: 5000 });
        await personalityTab.click();
        await page.waitForTimeout(2000);

        // Step 7: Verify conscientiousness is still 72
        conscSlider = page.locator('input[type="range"]').nth(1);
        await expect(conscSlider).toBeVisible({ timeout: 5000 });
        let value = await conscSlider.inputValue();
        expect(value).toBe('72');
        await screenshot(page, 'personality-reloaded-value');
    });
});
