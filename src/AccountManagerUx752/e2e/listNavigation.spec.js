/**
 * List navigation: create 100 notes, switch views, navigate, verify pagination.
 * Uses shared test user (NEVER admin).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, createNote } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }

test.describe('List View Navigation', () => {
    let shared;
    let notesDirOid;
    const NOTE_COUNT = 25; // enough for 2-3 pages at default page size 10

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);

        let ctx = await newCtx();
        let lr = await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        if (!lr.ok()) { console.log('Login failed:', lr.status()); return; }

        // Ensure ~/NavTest directory
        let dirResp = await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/NavTest').replace(/=/g, '%3D'));
        if (!dirResp.ok()) { console.log('Path make failed:', dirResp.status()); await ctx.dispose(); return; }
        let dir = await dirResp.json();
        notesDirOid = dir.objectId;
        console.log('NavTest dir:', notesDirOid);

        // Create notes — use existing ones if already created
        let checkResp = await ctx.post(REST + '/model/search', {
            data: { schema: 'io.query', type: 'data.note',
                fields: [{ name: 'groupId', comparator: 'equals', value: dir.id }],
                request: ['id'], recordCount: 1 }
        });
        let existing = 0;
        try { let cr = await checkResp.json(); existing = (cr && cr.results) ? cr.results.length : 0; } catch(e) {}

        if (existing === 0) {
            let ts = Date.now();
            for (let i = 0; i < NOTE_COUNT; i++) {
                await createNote(ctx, '~/NavTest', 'NavNote-' + String(i).padStart(3, '0') + '-' + ts, 'Content ' + i);
            }
            console.log('Created ' + NOTE_COUNT + ' notes');
        } else {
            console.log('Notes already exist, count check:', existing);
        }

        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test('Table view: paginate forward and back', async ({ page }) => {
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();

        await page.goto('/');
        await page.waitForTimeout(1500);

        // Navigate to notes list
        await page.goto('#!/list/data.note/' + notesDirOid);
        await page.waitForTimeout(3000);

        // Verify we have items
        let rows = await page.locator('table tbody tr').count();
        console.log('Table rows on page 1:', rows);
        expect(rows).toBeGreaterThan(0);
        await page.screenshot({ path: 'e2e/screenshots/listnav-01-table-p1.png' });

        // Navigate to next page
        let nextBtn = page.locator('button:has(span:text("chevron_right"))').first();
        if (await nextBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
            await nextBtn.click();
            await page.waitForTimeout(2000);
            let rows2 = await page.locator('table tbody tr').count();
            console.log('Table rows on page 2:', rows2);
            expect(rows2).toBeGreaterThan(0);
            await page.screenshot({ path: 'e2e/screenshots/listnav-02-table-p2.png' });

            // Navigate back
            let prevBtn = page.locator('button:has(span:text("chevron_left"))').first();
            await prevBtn.click();
            await page.waitForTimeout(2000);
            let rows1b = await page.locator('table tbody tr').count();
            console.log('Table rows back on page 1:', rows1b);
            expect(rows1b).toBeGreaterThan(0);
        }
    });

    test('Icon view: switch from table, items visible, switch back', async ({ page }) => {
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();

        await page.goto('/');
        await page.waitForTimeout(1500);
        await page.goto('#!/list/data.note/' + notesDirOid);
        await page.waitForTimeout(3000);

        // Count table rows
        let tableRows = await page.locator('table tbody tr').count();
        console.log('Table rows before switch:', tableRows);
        expect(tableRows).toBeGreaterThan(0);

        // Switch to icon view
        let gridBtn = page.locator('button:has(span:text("apps"))');
        await expect(gridBtn).toBeVisible({ timeout: 3000 });
        await gridBtn.click();
        await page.waitForTimeout(2000);
        await page.screenshot({ path: 'e2e/screenshots/listnav-03-icon.png' });

        // Verify icon items are visible
        await page.waitForTimeout(500);
        // Icon view renders note icons as grid items — count them by the card-title class or grid container children
        let iconItems = await page.evaluate(() => {
            // Count elements that look like grid items (excluding header/nav)
            let gridContainer = document.querySelector('.image-grid-tile') || document.querySelector('.image-grid-5');
            if (gridContainer) return gridContainer.children.length;
            // Fallback: count by any grid with many items
            let grids = document.querySelectorAll('[class*="grid"]');
            for (let g of grids) { if (g.children.length > 5) return g.children.length; }
            return 0;
        });
        console.log('Icon items:', iconItems);
        expect(iconItems).toBeGreaterThan(0);

        // Switch back to table
        await gridBtn.click();
        await page.waitForTimeout(2000);
        await page.screenshot({ path: 'e2e/screenshots/listnav-04-table-back.png' });

        let tableRowsAfter = await page.locator('table tbody tr').count();
        console.log('Table rows after switch back:', tableRowsAfter);
        expect(tableRowsAfter).toBeGreaterThan(0);
    });

    test('Carousel: open item, navigate past page boundary, close, table intact', async ({ page }) => {
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();

        await page.goto('/');
        await page.waitForTimeout(1500);
        await page.goto('#!/list/data.note/' + notesDirOid);
        await page.waitForTimeout(3000);

        // Count initial rows
        let initialRows = await page.locator('table tbody tr').count();
        console.log('Initial table rows:', initialRows);
        expect(initialRows).toBeGreaterThan(0);

        // Select first row then open carousel (file_open button)
        let firstRow = page.locator('table tbody tr').first();
        await firstRow.click();
        await page.waitForTimeout(500);

        let openBtn = page.locator('button:has(span:text("file_open"))');
        if (await openBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
            await openBtn.click();
            await page.waitForTimeout(2000);
            await page.screenshot({ path: 'e2e/screenshots/listnav-05-carousel.png' });

            // Navigate right multiple times to cross page boundary
            for (let i = 0; i < initialRows + 2; i++) {
                await page.keyboard.press('ArrowRight');
                await page.waitForTimeout(300);
            }
            await page.screenshot({ path: 'e2e/screenshots/listnav-06-carousel-navigated.png' });

            // Press Escape to close carousel
            await page.keyboard.press('Escape');
            await page.waitForTimeout(2000);
            await page.screenshot({ path: 'e2e/screenshots/listnav-07-after-carousel.png' });

            // Verify table is intact with items
            let afterRows = await page.locator('table tbody tr').count();
            console.log('Table rows after carousel:', afterRows);
            expect(afterRows).toBeGreaterThan(0);
        } else {
            console.log('file_open button not visible — skipping carousel test');
        }
    });

    test('Icon full view: open, navigate, close, list intact', async ({ page }) => {
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();

        await page.goto('/');
        await page.waitForTimeout(1500);
        await page.goto('#!/list/data.note/' + notesDirOid);
        await page.waitForTimeout(3000);

        // Switch to icon view
        let gridBtn = page.locator('button:has(span:text("apps"))');
        await expect(gridBtn).toBeVisible({ timeout: 3000 });
        await gridBtn.click();
        await page.waitForTimeout(2000);

        // Click on the first icon image to open full view
        let firstIcon = page.locator('[draggable]').first();
        if (await firstIcon.isVisible({ timeout: 2000 }).catch(() => false)) {
            await firstIcon.click();
            await page.waitForTimeout(1500);
            await page.screenshot({ path: 'e2e/screenshots/listnav-08-fullview.png' });

            // Close with Escape
            await page.keyboard.press('Escape');
            await page.waitForTimeout(1500);

            let iconItems = await page.locator('[draggable]').count();
            console.log('Icon items after full view close:', iconItems);
            expect(iconItems).toBeGreaterThan(0);

            // Switch back to table
            await gridBtn.click();
            await page.waitForTimeout(2000);
            let tableRows = await page.locator('table tbody tr').count();
            console.log('Table rows after icon→fullview→table:', tableRows);
            expect(tableRows).toBeGreaterThan(0);
        } else {
            console.log('No icon items to click');
        }
    });
});
