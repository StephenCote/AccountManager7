import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Magic 8 (Biometrics) feature', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('magic 8 button navigates to session config', async ({ page }) => {
        let bioBtn = page.locator('button[title="Magic 8"]');
        await expect(bioBtn).toBeVisible({ timeout: 5000 });
        await bioBtn.click();
        await page.waitForURL(/.*#!\/magic8/, { timeout: 10000 });
        // SessionConfigEditor shows "Magic8 Session" heading or loading spinner
        let heading = page.locator('text=Magic8 Session');
        let loading = page.locator('text=Loading configuration options');
        await expect(heading.or(loading).first()).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'magic8-config');
    });

    test('session config shows Start Session button', async ({ page }) => {
        await page.locator('button[title="Magic 8"]').click();
        await page.waitForURL(/.*#!\/magic8/, { timeout: 10000 });
        let startBtn = page.locator('button:has-text("Start Session")');
        await expect(startBtn).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'magic8-start-button');
    });

    test('session config shows configuration description', async ({ page }) => {
        await page.locator('button[title="Magic 8"]').click();
        await page.waitForURL(/.*#!\/magic8/, { timeout: 10000 });
        await expect(page.locator('text=Magic8 Session')).toBeVisible({ timeout: 15000 });
        await expect(page.locator('text=Configure your immersive experience')).toBeVisible();
        await screenshot(page, 'magic8-config-detail');
    });

});
