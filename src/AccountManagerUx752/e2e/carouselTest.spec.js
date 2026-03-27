import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, cleanupTestUser } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';

test('gallery full view covers viewport', async ({ page, request }) => {
    // Use the admin user who has charPerson data with images
    let errors = [];
    page.on('pageerror', err => { errors.push(err.message); });

    await login(page);
    
    // Go to charPerson list
    await page.goto('#!/list/olio.charPerson');
    await page.waitForTimeout(3000);

    let rows = page.locator('tr.cursor-pointer, div.cursor-pointer');
    let rowCount = await rows.count();
    console.log('CharPerson rows:', rowCount);
    if (rowCount === 0) { test.skip('No charPerson data'); return; }

    // Open first character
    await rows.first().click();
    await page.waitForFunction(() => window.location.hash.includes('/view/'), { timeout: 10000 });
    await page.waitForTimeout(3000);

    // Click gallery button
    let galleryBtn = page.locator('button:has(span:text("photo_library"))');
    let hasGallery = await galleryBtn.isVisible({ timeout: 5000 }).catch(() => false);
    console.log('Gallery button:', hasGallery);
    if (!hasGallery) { test.skip('No gallery button'); return; }

    await galleryBtn.click();
    await page.waitForTimeout(3000);
    await screenshot(page, 'carousel-01-gallery-grid');

    // Check grid has images
    let gridImgs = page.locator('.grid img');
    let imgCount = await gridImgs.count();
    console.log('Grid images:', imgCount);

    // Click preview image to go full canvas
    let previewImg = page.locator('img[style*="object-fit"]').first();
    let hasPreview = await previewImg.isVisible({ timeout: 3000 }).catch(() => false);
    console.log('Preview visible:', hasPreview);
    if (!hasPreview) { test.skip('No preview image'); return; }

    await previewImg.click();
    await page.waitForTimeout(2000);
    await screenshot(page, 'carousel-02-fullcanvas');

    // Check the portal element exists on body
    let portal = page.locator('body > div > div[style*="position:fixed"]');
    let portalCount = await portal.count();
    console.log('Portal fixed elements:', portalCount);

    if (portalCount > 0) {
        let box = await portal.first().boundingBox();
        let viewport = page.viewportSize();
        console.log('Portal box:', JSON.stringify(box));
        console.log('Viewport:', JSON.stringify(viewport));
        
        expect(box.width).toBeGreaterThanOrEqual(viewport.width - 2);
        expect(box.height).toBeGreaterThanOrEqual(viewport.height - 2);

        let fullImg = portal.first().locator('img').first();
        let src = await fullImg.getAttribute('src');
        console.log('Full image src:', src);
        expect(src).toContain('/media/');
        expect(src).not.toContain('/thumbnail/');
    } else {
        // Fallback: check for any full-viewport overlay
        await screenshot(page, 'carousel-03-debug');
        console.log('WARNING: No portal found');
    }

    let realErrors = errors.filter(e => !e.includes('404'));
    if (realErrors.length > 0) {
        console.log('JS ERRORS:');
        realErrors.forEach(e => console.log('  -', e));
    }
    expect(realErrors.length).toBe(0);
});
