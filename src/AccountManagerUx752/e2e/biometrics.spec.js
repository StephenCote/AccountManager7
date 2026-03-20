/**
 * Biometrics / Magic 8 E2E tests.
 *
 * Tests the Magic8 session config UI and face analysis endpoint.
 * The face analysis test uses a static test image (media/faceTest.jpg)
 * since automated browsers cannot access the webcam.
 */
import fs from 'fs';
import path from 'path';
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, analyzeFace, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

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
        await expect(startBtn).toBeVisible({ timeout: 20000 });
        await screenshot(page, 'magic8-start-button');
    });

    test('session config shows configuration description', async ({ page }) => {
        await page.locator('button[title="Magic 8"]').click();
        await page.waitForURL(/.*#!\/magic8/, { timeout: 10000 });
        await expect(page.locator('text=Magic8 Session')).toBeVisible({ timeout: 20000 });
        await expect(page.locator('text=Configure your immersive experience')).toBeVisible();
        await screenshot(page, 'magic8-config-detail');
    });

    test('session config shows biometric adaptation and recording sections', async ({ page }) => {
        await page.locator('button[title="Magic 8"]').click();
        await page.waitForURL(/.*#!\/magic8/, { timeout: 10000 });
        await expect(page.locator('text=Magic8 Session')).toBeVisible({ timeout: 20000 });
        // Biometric Adaptation section with its enable checkbox
        await expect(page.locator('h3:has-text("Biometric Adaptation")')).toBeVisible({ timeout: 5000 });
        // Session Recording section with its enable checkbox
        await expect(page.locator('h3:has-text("Session Recording")')).toBeVisible();
        // Session Name input should be present
        await expect(page.locator('input[type="text"]').first()).toBeVisible();
        await screenshot(page, 'magic8-config-sections');
    });

    test('no uncaught JS errors during magic8 load', async ({ page }) => {
        let errors = [];
        page.on('pageerror', err => errors.push(err.message));

        await page.locator('button[title="Magic 8"]').click();
        await page.waitForURL(/.*#!\/magic8/, { timeout: 15000 });
        await expect(page.locator('text=Magic8 Session').or(page.locator('text=Loading configuration options')).first()).toBeVisible({ timeout: 20000 });
        await page.waitForTimeout(2000); // let async subsystem init settle

        expect(errors, 'Uncaught JS errors:\n' + errors.join('\n')).toHaveLength(0);
        await screenshot(page, 'magic8-no-js-errors');
    });

    test('face analysis endpoint accepts image and returns result', async () => {
        // Load the static test image and convert to data URL
        let imgPath = path.resolve('media/faceTest.jpg');
        let imgBuffer = fs.readFileSync(imgPath);
        let imageDataUrl = 'data:image/jpeg;base64,' + imgBuffer.toString('base64');

        // POST to /rest/face/analyze via API context
        let ctx = await newApiContext();
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });

            let result = await analyzeFace(ctx, imageDataUrl);
            // The endpoint should return some result (not null/error)
            expect(result).not.toBeNull();

            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });
});
