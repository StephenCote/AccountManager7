/**
 * Feature Configuration E2E tests — Tests admin panel loads, toggle UI works, save button.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';

/**
 * Navigate to the Feature Config page via the aside menu button.
 */
async function goToFeatureConfig(page) {
    await page.waitForFunction(() => {
        let buttons = Array.from(document.querySelectorAll('button'));
        return buttons.some(b => b.textContent.trim().includes('Features'));
    }, { timeout: 10000 });

    await page.evaluate(() => {
        let buttons = Array.from(document.querySelectorAll('button'));
        let btn = buttons.find(b => b.textContent.trim() === 'Features');
        if (btn) btn.click();
    });
    await page.waitForTimeout(1500);
}

test.describe('Feature Configuration admin panel', () => {

    test('feature config page loads after login', async ({ page }) => {
        await login(page);
        await goToFeatureConfig(page);

        await expect(page.locator('text=Feature Configuration')).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'feature-config-page');
    });

    test('feature toggle switches are visible', async ({ page }) => {
        await login(page);
        await goToFeatureConfig(page);

        // Should see feature labels
        await expect(page.locator('text=Core')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=LLM Chat')).toBeVisible({ timeout: 5000 });

        // Core should have Required badge
        await expect(page.locator('text=Required')).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'feature-config-toggles');
    });

    test('quick profile buttons are visible', async ({ page }) => {
        await login(page);
        await goToFeatureConfig(page);

        await expect(page.locator('text=Quick Profiles')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Minimal")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Full")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Enterprise")')).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'feature-config-profiles');
    });

    test('clicking Minimal profile shows unsaved changes', async ({ page }) => {
        await login(page);
        await goToFeatureConfig(page);

        // Click Minimal profile button
        await page.locator('button:has-text("Minimal")').click();
        await page.waitForTimeout(500);

        // Should show "Unsaved changes" indicator
        await expect(page.locator('text=Unsaved changes')).toBeVisible({ timeout: 5000 });

        // Save button should be enabled
        let saveBtn = page.locator('button:has-text("Save")');
        await expect(saveBtn).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'feature-config-unsaved');
    });

    test('dependency info is shown for features with deps', async ({ page }) => {
        await login(page);
        await goToFeatureConfig(page);

        // Card Game depends on core and chat — should show "Depends on:" text
        await expect(page.locator('text=Depends on:')).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'feature-config-deps');
    });
});
