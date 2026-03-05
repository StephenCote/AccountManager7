import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Biometrics feature', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('biometrics button navigates and shows start screen', async ({ page }) => {
        let bioBtn = page.locator('button[title="Biometrics"]');
        await expect(bioBtn).toBeVisible({ timeout: 5000 });
        await bioBtn.click();
        await page.waitForURL(/.*#!\/hyp/, { timeout: 10000 });
        await expect(page.locator('text=Biometric Feedback Session')).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'biometrics-start');
    });

    test('begin button starts experience with canvas', async ({ page }) => {
        await page.locator('button[title="Biometrics"]').click();
        await page.waitForURL(/.*#!\/hyp/, { timeout: 10000 });
        let beginBtn = page.locator('button:has-text("Begin")');
        await expect(beginBtn).toBeVisible({ timeout: 10000 });
        await beginBtn.click();
        // Canvas should exist in DOM after starting (full-screen overlay)
        let canvas = page.locator('canvas#visual-canvas');
        await expect(canvas).toBeAttached({ timeout: 5000 });
        // Core message text should appear
        await page.waitForTimeout(1000);
        await screenshot(page, 'biometrics-experience');
    });

    test('start screen shows begin button and description', async ({ page }) => {
        await page.locator('button[title="Biometrics"]').click();
        await page.waitForURL(/.*#!\/hyp/, { timeout: 10000 });
        await expect(page.locator('text=Biometric Feedback Session')).toBeVisible({ timeout: 10000 });
        await expect(page.locator('button:has-text("Begin")')).toBeVisible();
        await expect(page.locator('text=This experience adapts to you')).toBeVisible();
        await screenshot(page, 'biometrics-start-detail');
    });

});
