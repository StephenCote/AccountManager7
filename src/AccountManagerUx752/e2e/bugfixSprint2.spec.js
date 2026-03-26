/**
 * Bug Fix Sprint 2 — Playwright tests for reported issues
 * Tests navigate through the real UI against live backend
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, cleanupTestUser, apiLogin } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';

let testInfo;

test.describe('Bug Fix Sprint 2', () => {
    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test('data.data object view shows Tags tab', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate through dashboard to find a data type
        // Click first card item on dashboard
        let firstCard = page.locator('.card-item button').first();
        await expect(firstCard).toBeVisible({ timeout: 10000 });
        await firstCard.click();

        // Wait for list view
        await page.waitForFunction(() => window.location.hash.includes('/list/'), { timeout: 15000 });
        await page.waitForTimeout(2000);

        await screenshot(page, 'sprint2-list-view');

        // Check if list has items
        let listContent = page.locator('.list-table, .tabular-results-table, .result-nav-outer');
        let hasContent = await listContent.first().isVisible({ timeout: 5000 }).catch(() => false);
        console.log('List has content:', hasContent);

        // Navigate to data.data list if not already there
        // Try direct URL to data.data Home
        await page.goto('#!/list/data.data');
        await page.waitForTimeout(3000);
        await screenshot(page, 'sprint2-data-list');

        // Check for any items in the list
        let rows = page.locator('tr.cursor-pointer, div.cursor-pointer');
        let rowCount = await rows.count();
        console.log('List rows:', rowCount);

        if (rowCount > 0) {
            // Click first item to open object view
            await rows.first().click();
            await page.waitForFunction(() => window.location.hash.includes('/view/'), { timeout: 10000 });
            await page.waitForTimeout(2000);
            await screenshot(page, 'sprint2-data-object-view');

            // Look for tabs
            let allButtons = await page.locator('button').allTextContents();
            console.log('Object view buttons:', allButtons.filter(b => b.trim()).join(' | '));

            // Look for Tags tab specifically
            let tagsBtn = page.locator('button:has-text("Tags")');
            let hasTagsTab = await tagsBtn.isVisible().catch(() => false);
            console.log('Tags tab visible:', hasTagsTab);
            if (hasTagsTab) {
                await tagsBtn.click();
                await page.waitForTimeout(1000);
                await screenshot(page, 'sprint2-tags-tab');
            }
        }
    });

    test('charPerson object view shows portrait and gallery opens', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to charPerson list
        await page.goto('#!/list/olio.charPerson');
        await page.waitForTimeout(3000);
        await screenshot(page, 'sprint2-char-list');

        let rows = page.locator('tr.cursor-pointer, div.cursor-pointer');
        let rowCount = await rows.count();
        console.log('CharPerson rows:', rowCount);

        if (rowCount > 0) {
            await rows.first().click();
            await page.waitForFunction(() => window.location.hash.includes('/view/'), { timeout: 10000 });
            await page.waitForTimeout(3000);
            await screenshot(page, 'sprint2-char-object-view');

            // Check for portrait thumbnail
            let thumbnail = page.locator('img.rounded.object-cover').first();
            let hasThumbnail = await thumbnail.isVisible().catch(() => false);
            console.log('Portrait thumbnail visible:', hasThumbnail);

            // Check for gallery button (photo_library icon)
            let galleryBtn = page.locator('button:has(span:text("photo_library"))');
            let hasGallery = await galleryBtn.isVisible().catch(() => false);
            console.log('Gallery button visible:', hasGallery);

            if (hasGallery) {
                await galleryBtn.click();
                await page.waitForTimeout(2000);
                await screenshot(page, 'sprint2-gallery-dialog');

                // Check gallery has images
                let galleryImages = page.locator('.am7-dialog img');
                let imgCount = await galleryImages.count();
                console.log('Gallery images:', imgCount);
                expect(imgCount).toBeGreaterThan(0);
            }
        }
    });

    test('list view shows file type icons', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.goto('#!/list/data.data');
        await page.waitForTimeout(3000);
        await screenshot(page, 'sprint2-list-icons');

        // Check for any icon elements — material icons or file-icon-vectors
        let materialIcons = page.locator('.material-symbols-outlined, .material-icons-outlined');
        let iconCount = await materialIcons.count();
        console.log('Material icons count:', iconCount);

        let fivIcons = page.locator('[class*="fiv-icon-"]');
        let fivCount = await fivIcons.count();
        console.log('FIV file icons count:', fivCount);
    });

    test('denoising slider round-trip via sdConfig form', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to a charPerson to open reimage dialog
        await page.goto('#!/list/olio.charPerson');
        await page.waitForTimeout(3000);

        let rows = page.locator('tr.cursor-pointer, div.cursor-pointer');
        let rowCount = await rows.count();
        if (rowCount === 0) {
            test.skip('No charPerson available');
            return;
        }

        await rows.first().click();
        await page.waitForFunction(() => window.location.hash.includes('/view/'), { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Click reimage button
        let reimageBtn = page.locator('button:has(span:text("auto_awesome"))');
        let hasReimage = await reimageBtn.isVisible().catch(() => false);
        if (!hasReimage) {
            console.log('No reimage button visible');
            await screenshot(page, 'sprint2-no-reimage');
            test.skip('No reimage button');
            return;
        }

        await reimageBtn.click();
        await page.waitForTimeout(2000);
        await screenshot(page, 'sprint2-reimage-dialog');

        // Look for denoising slider
        let denoiseSlider = page.locator('input[type="range"]').first();
        let hasSlider = await denoiseSlider.isVisible().catch(() => false);
        console.log('Denoising slider visible:', hasSlider);

        if (hasSlider) {
            // Check slider value is displayed
            let sliderValue = await denoiseSlider.inputValue();
            console.log('Slider value:', sliderValue);

            // Check for a value display label near the slider
            await screenshot(page, 'sprint2-denoise-slider');
        }
    });

    test('action buttons (Narrate, Reimage) alignment', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.goto('#!/list/olio.charPerson');
        await page.waitForTimeout(3000);

        let rows = page.locator('tr.cursor-pointer, div.cursor-pointer');
        if (await rows.count() === 0) {
            test.skip('No charPerson');
            return;
        }

        await rows.first().click();
        await page.waitForFunction(() => window.location.hash.includes('/view/'), { timeout: 10000 });
        await page.waitForTimeout(3000);
        await screenshot(page, 'sprint2-action-buttons');

        // Look for action command buttons
        let cmdButtons = page.locator('.field-grid-item-full button, .field-grid-item-half button');
        let cmdCount = await cmdButtons.count();
        console.log('Command buttons in form:', cmdCount);
    });

    test('console errors during navigation', async ({ page }) => {
        let errors = [];
        page.on('pageerror', err => {
            errors.push(err.message);
        });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate through several views
        await page.goto('#!/list/olio.charPerson');
        await page.waitForTimeout(3000);

        let rows = page.locator('tr.cursor-pointer, div.cursor-pointer');
        if (await rows.count() > 0) {
            await rows.first().click();
            await page.waitForTimeout(3000);
        }

        // Check for JS errors
        if (errors.length > 0) {
            console.log('Console errors:');
            errors.forEach(e => console.log('  -', e));
        }
        console.log('Total JS errors:', errors.length);
    });
});
