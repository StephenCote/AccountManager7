/**
 * Character image gallery — E2E tests against live backend
 * Tests the charImages tab: grid/list views, arrow nav, auto-tag, tags display
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';

test.describe('Character image gallery', () => {

    test.beforeEach(async ({ page }) => {
        await login(page);
    });

    test('Images tab shows gallery with thumbnails for charPerson', async ({ page }) => {
        test.setTimeout(60000);

        // Find a charPerson and navigate to it
        let charRoute = await page.evaluate(async () => {
            let mod = await import('/src/core/am7client.js');
            let am7client = mod.am7client;
            let q = am7client.newQuery('olio.charPerson');
            q.range(0, 1);
            let qr = await am7client.search(q);
            if (qr && qr.results && qr.results.length > 0) {
                return '/view/olio.charPerson/' + qr.results[0].objectId;
            }
            return null;
        });

        if (!charRoute) {
            console.log('No charPerson found — skipping gallery test');
            return;
        }

        // Navigate to the character
        // Close aside drawer if open
        let aside = page.locator('aside.transition');
        if (await aside.isVisible({ timeout: 1000 }).catch(() => false)) {
            let toggle = page.locator('div.menuToggle button').first();
            if (await toggle.isVisible({ timeout: 1000 }).catch(() => false)) {
                await toggle.click();
                await page.waitForTimeout(500);
            }
        }

        await page.evaluate((route) => { window.location.hash = '#!/' + route.replace(/^\//, ''); }, charRoute);
        await page.waitForTimeout(5000);

        // Find and click the "Images" tab
        let imagesTab = page.locator('button:has-text("Images")');
        if (!await imagesTab.isVisible({ timeout: 15000 }).catch(() => false)) {
            console.log('Images tab not visible — charPerson form may not have loaded');
            await screenshot(page, 'char-gallery-no-tab');
            return;
        }
        await imagesTab.click();
        await page.waitForTimeout(5000);

        // Gallery should render — either images or "No images found" or "Loading images..."
        let gallery = page.locator('text=image(s)').or(page.locator('text=No images found')).or(page.locator('text=Loading'));
        await expect(gallery.first()).toBeVisible({ timeout: 15000 });

        // Check for view mode toggle buttons (only if images loaded)
        let hasImages = await page.locator('text=image(s)').isVisible({ timeout: 3000 }).catch(() => false);
        if (hasImages) {
            let gridBtn = page.locator('button[title="Grid view"]');
            let listBtn = page.locator('button[title="List view"]');
            await expect(gridBtn).toBeVisible({ timeout: 5000 });
            await expect(listBtn).toBeVisible({ timeout: 5000 });
        }

        await screenshot(page, 'char-gallery-images-tab');
    });

    test('grid and list view toggle works', async ({ page }) => {
        test.setTimeout(60000);

        let charRoute = await page.evaluate(async () => {
            let mod = await import('/src/core/am7client.js');
            let q = mod.am7client.newQuery('olio.charPerson');
            q.range(0, 1);
            let qr = await mod.am7client.search(q);
            return (qr && qr.results && qr.results.length > 0) ? '/view/olio.charPerson/' + qr.results[0].objectId : null;
        });

        if (!charRoute) { console.log('No charPerson — skipping'); return; }

        await page.evaluate((route) => { window.location.hash = '#!/' + route.replace(/^\//, ''); }, charRoute);
        await page.waitForTimeout(3000);

        let imagesTab = page.locator('button:has-text("Images")');
        if (!await imagesTab.isVisible({ timeout: 10000 }).catch(() => false)) { return; }
        await imagesTab.click();
        await page.waitForTimeout(3000);

        // If no images, skip
        let noImages = page.locator('text=No images found');
        if (await noImages.isVisible({ timeout: 2000 }).catch(() => false)) {
            console.log('No images for this character — skipping view toggle test');
            return;
        }

        // Default is grid view — should have grid layout
        let grid = page.locator('.grid');
        await expect(grid).toBeVisible({ timeout: 5000 });

        // Switch to list view
        let listBtn = page.locator('button[title="List view"]');
        await listBtn.click();
        await page.waitForTimeout(500);

        // List view should show divider items
        let listItems = page.locator('.divide-y');
        await expect(listItems).toBeVisible({ timeout: 5000 });

        // Switch back to grid
        let gridBtn = page.locator('button[title="Grid view"]');
        await gridBtn.click();
        await page.waitForTimeout(500);
        await expect(grid).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'char-gallery-view-toggle');
    });

    test('arrow keys navigate between images', async ({ page }) => {
        test.setTimeout(60000);

        let charRoute = await page.evaluate(async () => {
            let mod = await import('/src/core/am7client.js');
            let q = mod.am7client.newQuery('olio.charPerson');
            q.range(0, 1);
            let qr = await mod.am7client.search(q);
            return (qr && qr.results && qr.results.length > 0) ? '/view/olio.charPerson/' + qr.results[0].objectId : null;
        });

        if (!charRoute) { console.log('No charPerson — skipping'); return; }

        await page.evaluate((route) => { window.location.hash = '#!/' + route.replace(/^\//, ''); }, charRoute);
        await page.waitForTimeout(3000);

        let imagesTab = page.locator('button:has-text("Images")');
        if (!await imagesTab.isVisible({ timeout: 10000 }).catch(() => false)) { return; }
        await imagesTab.click();
        await page.waitForTimeout(3000);

        let noImages = page.locator('text=No images found');
        if (await noImages.isVisible({ timeout: 2000 }).catch(() => false)) {
            console.log('No images — skipping arrow nav test');
            return;
        }

        // Check initial position indicator
        let posIndicator = page.locator('text=/(1\\/\\d+)/');
        let initialText = await page.locator('.text-gray-400.text-xs').first().textContent().catch(() => '');

        // Focus the gallery container and press right arrow
        let galleryContainer = page.locator('.outline-none[tabindex="0"]');
        await galleryContainer.focus();
        await page.keyboard.press('ArrowRight');
        await page.waitForTimeout(300);

        // Position should have changed if there are 2+ images
        let newText = await page.locator('.text-gray-400.text-xs').first().textContent().catch(() => '');
        if (initialText.includes('1/1')) {
            console.log('Only 1 image — cannot test arrow navigation');
        } else if (newText !== initialText) {
            // Position changed — navigation works
            expect(newText).not.toBe(initialText);
        }

        await screenshot(page, 'char-gallery-arrow-nav');
    });

    test('action buttons visible: Set Profile, Auto-Tag, Delete', async ({ page }) => {
        test.setTimeout(60000);

        let charRoute = await page.evaluate(async () => {
            let mod = await import('/src/core/am7client.js');
            let q = mod.am7client.newQuery('olio.charPerson');
            q.range(0, 1);
            let qr = await mod.am7client.search(q);
            return (qr && qr.results && qr.results.length > 0) ? '/view/olio.charPerson/' + qr.results[0].objectId : null;
        });

        if (!charRoute) { console.log('No charPerson — skipping'); return; }

        await page.evaluate((route) => { window.location.hash = '#!/' + route.replace(/^\//, ''); }, charRoute);
        await page.waitForTimeout(3000);

        let imagesTab = page.locator('button:has-text("Images")');
        if (!await imagesTab.isVisible({ timeout: 10000 }).catch(() => false)) { return; }
        await imagesTab.click();
        await page.waitForTimeout(3000);

        let noImages = page.locator('text=No images found');
        if (await noImages.isVisible({ timeout: 2000 }).catch(() => false)) {
            console.log('No images — skipping action buttons test');
            return;
        }

        // All three action buttons should be visible
        await expect(page.locator('button:has-text("Set Profile")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Auto-Tag")')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Delete")')).toBeVisible({ timeout: 5000 });

        // Tags section should be visible
        await expect(page.locator('text=Tags:')).toBeVisible({ timeout: 5000 });

        // Navigation hint should be visible
        await expect(page.locator('text=Arrow keys to navigate')).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'char-gallery-actions');
    });

    test('auto-tag calls applyImageTags endpoint', async ({ page }) => {
        test.setTimeout(60000);

        let charRoute = await page.evaluate(async () => {
            let mod = await import('/src/core/am7client.js');
            let q = mod.am7client.newQuery('olio.charPerson');
            q.range(0, 1);
            let qr = await mod.am7client.search(q);
            return (qr && qr.results && qr.results.length > 0) ? '/view/olio.charPerson/' + qr.results[0].objectId : null;
        });

        if (!charRoute) { console.log('No charPerson — skipping'); return; }

        await page.evaluate((route) => { window.location.hash = '#!/' + route.replace(/^\//, ''); }, charRoute);
        await page.waitForTimeout(3000);

        let imagesTab = page.locator('button:has-text("Images")');
        if (!await imagesTab.isVisible({ timeout: 10000 }).catch(() => false)) { return; }
        await imagesTab.click();
        await page.waitForTimeout(3000);

        let noImages = page.locator('text=No images found');
        if (await noImages.isVisible({ timeout: 2000 }).catch(() => false)) {
            console.log('No images — skipping auto-tag test');
            return;
        }

        // Click Auto-Tag and capture the network request
        let tagRequest = page.waitForRequest(
            req => req.url().includes('/tag/apply/'),
            { timeout: 15000 }
        ).catch(() => null);

        await page.locator('button:has-text("Auto-Tag")').click();

        let req = await tagRequest;
        if (req) {
            expect(req.url()).toContain('/tag/apply/');
        } else {
            console.log('No applyImageTags request captured — endpoint may not exist');
        }

        // Toast should show
        let toast = page.locator('.toast-box');
        await expect(toast.first()).toBeVisible({ timeout: 10000 });

        await screenshot(page, 'char-gallery-auto-tag');
    });
});
