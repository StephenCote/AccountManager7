/**
 * Feature Configuration E2E tests — admin panel loads, toggle UI works, save button.
 *
 * NO ADMIN USER. These previously called `login(page)`, whose default user is `admin`
 * (e2e/helpers/auth.js:13) — a standing rule violation (.claude/rules/llm-conduct.md rule 3). They now
 * run as a dedicated TEST user granted the AccountAdministrators role via
 * ensureAdminRoleTestUser(), which is what WEB-INF/resource/roleMap.json maps the JAAS role "admin"
 * onto, so it can reach the admin-only page and its PUT. Admin is used only inside the api.js helper
 * to provision that user.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureAdminRoleTestUser } from './helpers/api.js';

let featAdmin = null;

/**
 * Navigate to the Feature Config page via the aside menu button.
 */
async function goToFeatureConfig(page) {
    // Wait for a button whose text includes 'Features' (aside menu item: icon span + label span)
    await page.waitForFunction(() => {
        let buttons = Array.from(document.querySelectorAll('button'));
        return buttons.some(b => b.textContent.trim().includes('Features'));
    }, { timeout: 10000 });

    await page.evaluate(() => {
        let buttons = Array.from(document.querySelectorAll('button'));
        // textContent includes the icon glyph + label (e.g. "tuneFeatures"), use includes()
        let btn = buttons.find(b => b.textContent.trim().includes('Features'));
        if (btn) btn.click();
    });
    await page.waitForTimeout(1500);
}

/** Log in as the admin-ROLE test user (never `admin`) and open the Feature Config page. */
async function openAsAdminRoleUser(page) {
    await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
    await goToFeatureConfig(page);
}

test.describe('Feature Configuration admin panel', () => {

    test.beforeAll(async ({ request }) => {
        featAdmin = await ensureAdminRoleTestUser(request);
        expect(featAdmin.user && featAdmin.user.objectId, 'admin-role test user was not created').toBeTruthy();
        expect(featAdmin.roleAssigned, 'AccountAdministrators could not be assigned to the test user').toBe(true);
    });

    test('feature config page loads after login', async ({ page }) => {
        await openAsAdminRoleUser(page);

        await expect(page.locator('h2').filter({ hasText: 'Feature Configuration' })).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'feature-config-page');
    });

    test('feature toggle switches are visible', async ({ page }) => {
        await openAsAdminRoleUser(page);

        // Should see feature labels
        await expect(page.locator('text=Core').first()).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=LLM Chat').first()).toBeVisible({ timeout: 5000 });

        // Core should have Required badge
        await expect(page.locator('text=Required').first()).toBeVisible({ timeout: 5000 });

        // `media` is the id that rotted out of the old server-side list, so it must be renderable here.
        await expect(page.locator('text=Media Processing').first()).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'feature-config-toggles');
    });

    test('quick profile buttons are visible', async ({ page }) => {
        await openAsAdminRoleUser(page);

        await expect(page.locator('text=Quick Profiles')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Minimal")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Full")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Enterprise")')).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'feature-config-profiles');
    });

    test('clicking Minimal profile shows unsaved changes', async ({ page }) => {
        await openAsAdminRoleUser(page);

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
        await openAsAdminRoleUser(page);

        // Card Game depends on core and chat — should show "Depends on:" text
        await expect(page.locator('text=Depends on:').first()).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'feature-config-deps');
    });

    test('the manifest error banner is absent (no client/server drift)', async ({ page }) => {
        await openAsAdminRoleUser(page);

        // D2: a wiring id with no manifest entry is surfaced here as a hard error, never a silent skip.
        await expect(page.locator('text=Feature manifest error')).toHaveCount(0);
        // "N of M features enabled" — M is availableFeatures.length, i.e. what the server served.
        await expect(page.locator('text=/of 13 features enabled/')).toBeVisible({ timeout: 5000 });
    });
});
