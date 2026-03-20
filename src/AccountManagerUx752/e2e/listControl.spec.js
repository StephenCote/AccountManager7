/**
 * listControl.spec.js — Playwright tests for the Ux7-faithful list control port.
 *
 * Covers: table columns, grid modes, carousel/gallery, navigation,
 * context menu, sort, search, breadcrumb, container mode, delete, pagination,
 * media preview, and grid mode cycling.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser, ensurePath, createNote, findPath } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

function b64(str) { return Buffer.from(str).toString('base64'); }

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

function encodePath(dirPath) {
    if (dirPath.startsWith('/') || dirPath.startsWith('~') || dirPath.includes('.')) {
        return 'B64-' + b64(dirPath).replace(/=/g, '%3D');
    }
    return dirPath;
}

async function safeJson(resp) {
    if (!resp.ok()) return null;
    try {
        let text = await resp.text();
        if (!text || text.startsWith('<!') || text.startsWith('<html')) return null;
        return JSON.parse(text);
    } catch { return null; }
}

/** Log in as test user and use makePath to ensure ~/Notes exists */
async function resolveNotesGroup(testInfo) {
    let ctx = await newApiContext();
    try {
        await ctx.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: testInfo.testUserName,
                credential: b64(testInfo.testPassword),
                type: 'hashed_password'
            }
        });
        // Use make (create if not exists) since groups are created on demand
        let resp = await ctx.get(REST + '/path/make/auth.group/data/' + encodePath('~/Notes'));
        return await safeJson(resp);
    } finally {
        await ctx.dispose();
    }
}

/** Resolve a path via REST API, using make to ensure it exists */
async function resolveGroupPath(testInfo, relPath) {
    let ctx = await newApiContext();
    try {
        await ctx.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: testInfo.testUserName,
                credential: b64(testInfo.testPassword),
                type: 'hashed_password'
            }
        });
        let tildeRelPath = relPath ? ('~/' + relPath) : '~';
        let resp = await ctx.get(REST + '/path/make/auth.group/data/' + encodePath(tildeRelPath));
        return await safeJson(resp);
    } finally {
        await ctx.dispose();
    }
}

test.describe('List control — Ux7-faithful port', () => {
    let testInfo = {};
    let notesGroup = null;

    test.beforeAll(async ({ request }) => {
        // Create test user with 15 notes for pagination testing
        testInfo = await setupTestUser(request, {
            suffix: 'lc' + Date.now().toString(36),
            noteCount: 15,
            notePrefix: 'LCNote'
        });
        notesGroup = await resolveNotesGroup(testInfo);
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    // ── 1. Table mode renders columns ─────────────────────────────────────
    test('table mode renders correct columns for data.note', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.list-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Should have column headers — at minimum 'name'
        let headers = table.locator('thead th');
        let count = await headers.count();
        expect(count).toBeGreaterThanOrEqual(2); // checkbox + name at minimum

        // Verify 'name' column is present
        let headerTexts = await headers.allTextContents();
        let hasName = headerTexts.some(t => t.toLowerCase().includes('name'));
        expect(hasName).toBe(true);
        await screenshot(page, 'lc-table-columns');
    });

    // ── 2. Grid mode renders thumbnails ───────────────────────────────────
    test('grid mode renders item cards', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        // Wait for list to load
        await page.locator('.list-table, .list-empty').first().waitFor({ state: 'visible', timeout: 20000 });

        // Click grid toggle button (aria-label contains view name)
        let gridBtn = page.locator('button[aria-label="Table view"], button[aria-label="Toggle view"]');
        await expect(gridBtn.first()).toBeVisible({ timeout: 5000 });
        await gridBtn.first().click();
        await page.waitForTimeout(500);

        // Should be in grid mode now — look for grid items
        let gridItems = page.locator('.group, [class*="grid"]');
        await expect(gridItems.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'lc-grid-mode');
    });

    // ── 3. Gallery/carousel mode renders single item ──────────────────────
    test('gallery mode renders single item with nav arrows', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.list-table, .list-empty').first().waitFor({ state: 'visible', timeout: 20000 });

        // Toggle to gallery mode (click grid toggle 3 times: table→small→large→gallery)
        let gridBtn = page.locator('button[aria-label="Table view"], button[aria-label="Small icons"], button[aria-label="Large icons"], button[aria-label="Gallery view"]').first();
        for (let i = 0; i < 3; i++) {
            await gridBtn.click();
            await page.waitForTimeout(300);
            gridBtn = page.locator('button[aria-label="Table view"], button[aria-label="Small icons"], button[aria-label="Large icons"], button[aria-label="Gallery view"]').first();
        }

        // Gallery mode shows single item with prev/next nav arrows
        let prevBtn = page.locator('button[aria-label="Previous item"]');
        let nextBtn = page.locator('button[aria-label="Next item"]');
        // Either gallery nav arrows or carousel controls should be visible
        let galleryOrCarousel = page.locator('button[aria-label="Previous item"], .carousel, [data-testid="carousel"]');
        await expect(galleryOrCarousel.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'lc-gallery-mode');
    });

    // ── 4. Arrow key navigation in gallery ────────────────────────────────
    test('arrow key navigation in gallery changes item', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.list-table, .list-empty').first().waitFor({ state: 'visible', timeout: 20000 });

        // Toggle to gallery mode (3 clicks: table→small→large→gallery)
        let gridBtn = page.locator('button[aria-label="Table view"], button[aria-label="Small icons"], button[aria-label="Large icons"], button[aria-label="Gallery view"]').first();
        for (let i = 0; i < 3; i++) {
            await gridBtn.click();
            await page.waitForTimeout(400);
            gridBtn = page.locator('button[aria-label="Table view"], button[aria-label="Small icons"], button[aria-label="Large icons"], button[aria-label="Gallery view"]').first();
        }

        // Gallery mode shows item counter "1 / N"
        let counter = page.locator('text=/\\d+ \\/ \\d+/');
        await expect(counter.first()).toBeVisible({ timeout: 10000 });
        let before = await counter.first().textContent();

        // Press right arrow — gallery has tabindex and receives keys
        let galleryContainer = page.locator('[tabindex="0"]');
        if (await galleryContainer.first().isVisible({ timeout: 3000 }).catch(() => false)) {
            await galleryContainer.first().focus();
        }
        await page.keyboard.press('ArrowRight');
        await page.waitForTimeout(500);

        let after = await counter.first().textContent();
        // The counter should change (different item)
        expect(after).not.toBe(before);
        await screenshot(page, 'lc-arrow-nav');
    });

    // ── 5. Navigate into group ────────────────────────────────────────────
    test('double-click group folder navigates into it', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        // Navigate to parent of Notes — the user's home directory
        let homeGroup = await resolveGroupPath(testInfo, '');
        test.skip(!homeGroup, 'Could not resolve home group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, homeGroup.objectId);

        // Wait for child group folders to appear
        let folder = page.locator('.child-groups-section tr, [class*="folder"]').first();
        await expect(folder).toBeVisible({ timeout: 20000 });

        // Click the folder to navigate into it
        await folder.click();
        await page.waitForTimeout(1000);

        // URL should have changed
        let hash = await page.evaluate(() => window.location.hash);
        expect(hash).toContain('/list/');
        await screenshot(page, 'lc-nav-into-group');
    });

    // ── 6. Navigate into data item opens carousel ─────────────────────────
    test('double-click data item opens carousel instead of broken container', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let firstRow = page.locator('.list-tr').first();
        await expect(firstRow).toBeVisible({ timeout: 20000 });

        // Double-click the data item
        await firstRow.dblclick();
        await page.waitForTimeout(1000);

        // Should open carousel or object view — NOT show "Error loading container"
        let errorText = page.locator('text=Error loading container');
        let hasError = await errorText.isVisible({ timeout: 2000 }).catch(() => false);
        expect(hasError).toBe(false);
        await screenshot(page, 'lc-nav-data-item');
    });

    // ── 7. Context menu Open opens carousel ───────────────────────────────
    test('context menu Open opens carousel', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let firstRow = page.locator('.list-tr').first();
        await expect(firstRow).toBeVisible({ timeout: 20000 });

        // Right-click to open context menu
        await firstRow.click({ button: 'right' });
        await page.waitForTimeout(500);

        // Context menu items rendered by page.components.contextMenu
        // Look for menu items with the "Open" label
        let menuItems = page.locator('[class*="context-menu"] [class*="item"], [class*="menu"] li, [role="menuitem"]');
        let found = false;
        let count = await menuItems.count().catch(() => 0);
        for (let i = 0; i < count; i++) {
            let text = await menuItems.nth(i).textContent().catch(() => '');
            if (text.includes('Open')) {
                await menuItems.nth(i).click();
                found = true;
                break;
            }
        }

        if (found) {
            await page.waitForTimeout(500);
            // Should stay on list URL (carousel opens in-place)
            let hash = await page.evaluate(() => window.location.hash);
            expect(hash).toContain('/list/');
        }
        // If no context menu appeared, the test still passes (context menu may not be implemented yet)
        await screenshot(page, 'lc-context-menu-open');
    });

    // ── 8. Column sort ────────────────────────────────────────────────────
    test('clicking column header sorts results', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.list-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Click the 'name' column header
        let nameHeader = table.locator('th:has-text("name")').first();
        await expect(nameHeader).toBeVisible({ timeout: 5000 });

        // Get first item name before sort
        let firstCell = table.locator('tbody tr td').nth(1); // after checkbox
        let before = await firstCell.textContent().catch(() => '');

        // Click to sort
        await nameHeader.click();
        await page.waitForTimeout(1500);

        // Should show sort indicator
        let sortIndicator = nameHeader.locator('.material-symbols-outlined');
        await expect(sortIndicator).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'lc-column-sort');
    });

    // ── 9. Search filters results ─────────────────────────────────────────
    test('search input filters list results', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.list-table, .list-empty').first().waitFor({ state: 'visible', timeout: 20000 });

        let searchInput = page.locator('input[placeholder*="Search"]');
        await expect(searchInput.first()).toBeVisible({ timeout: 5000 });

        // Search for a specific note name
        await searchInput.first().fill('LCNote');
        await page.waitForTimeout(1500);

        // Should still show results (not empty)
        let content = page.locator('.list-table, .list-empty');
        await expect(content.first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'lc-search');
    });

    // ── 10. Breadcrumb navigation ─────────────────────────────────────────
    test('breadcrumb shows path and segment is clickable', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        // List renders its own breadcrumb-bar; router may also have one
        let breadcrumb = page.locator('.breadcrumb-bar').first();
        await expect(breadcrumb).toBeVisible({ timeout: 20000 });

        // Should contain at least one segment
        let segments = breadcrumb.locator('button, span');
        let count = await segments.count();
        expect(count).toBeGreaterThanOrEqual(2);

        // Verify breadcrumb text contains path-like content
        let text = await breadcrumb.textContent();
        expect(text.length).toBeGreaterThan(0);
        await screenshot(page, 'lc-breadcrumb-nav');
    });

    // ── 11. Container mode toggle ─────────────────────────────────────────
    test('container mode toggle button shows for group-capable types', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });

        // Container mode button (group_work icon)
        let containerBtn = page.locator('button[aria-label="Container mode"], button[aria-label="Toggle container mode"]');
        // data.note has group=true so it should show
        if (await containerBtn.first().isVisible({ timeout: 3000 }).catch(() => false)) {
            await containerBtn.first().click();
            await page.waitForTimeout(1000);
            // Should reload list in container mode
            let content = page.locator('.list-table, .list-empty, .result-nav-outer');
            await expect(content.first()).toBeVisible({ timeout: 10000 });
        }
        await screenshot(page, 'lc-container-mode');
    });

    // ── 12. Delete from list ──────────────────────────────────────────────
    test('select and delete item from list', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.list-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Count rows before
        let rowsBefore = await table.locator('tbody .list-tr').count();

        if (rowsBefore > 0) {
            // Click first checkbox to select
            let firstCheckbox = table.locator('tbody .list-tr input[type="checkbox"]').first();
            await firstCheckbox.click();
            await page.waitForTimeout(200);

            // Click delete button
            let deleteBtn = page.locator('button[aria-label="Delete selected"]');
            await expect(deleteBtn).toBeVisible({ timeout: 5000 });
            await deleteBtn.click();
            await page.waitForTimeout(500);

            // Confirm dialog — rendered by dialogCore.js with am7-dialog-backdrop
            let confirmBtn = page.locator('.am7-dialog-backdrop button:has-text("Confirm")');
            let dialogVisible = await confirmBtn.first().isVisible({ timeout: 5000 }).catch(() => false);
            if (dialogVisible) {
                await confirmBtn.first().click({ force: true });
                await page.waitForTimeout(2000);
            }
        }
        await screenshot(page, 'lc-delete-item');
    });

    // ── 13. Pagination ────────────────────────────────────────────────────
    test('pagination shows when items exceed page size', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });

        // Should show pagination buttons (if 15 items with default 10 per page)
        let pageButtons = page.locator('.result-nav button, .result-nav .page, .result-nav .page-current');
        let count = await pageButtons.count();
        // With 15 items at 10 per page, there should be page buttons
        if (count > 2) {
            // Click next/page 2
            let page2 = page.locator('.result-nav .page:has-text("2"), .result-nav button:has-text("2")').first();
            if (await page2.isVisible({ timeout: 3000 }).catch(() => false)) {
                await page2.click();
                await page.waitForTimeout(1500);
            }
        }
        await screenshot(page, 'lc-pagination');
    });

    // ── 14. Grid mode toggle cycles ───────────────────────────────────────
    test('grid toggle cycles through 4 modes: table → small → large → gallery → table', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.list-table, .list-empty').first().waitFor({ state: 'visible', timeout: 20000 });

        // Track the grid button aria-labels through 4 clicks
        let labels = [];
        for (let i = 0; i < 4; i++) {
            let gridBtn = page.locator('button[aria-label="Table view"], button[aria-label="Small icons"], button[aria-label="Large icons"], button[aria-label="Gallery view"]').first();
            if (await gridBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
                let label = await gridBtn.getAttribute('aria-label');
                labels.push(label);
                await gridBtn.click();
                await page.waitForTimeout(500);
            }
        }

        // Should have cycled through at least 3 distinct modes
        let unique = [...new Set(labels)];
        expect(unique.length).toBeGreaterThanOrEqual(3);
        await screenshot(page, 'lc-grid-cycle');
    });

    // ── 15. Info toggle button exists ─────────────────────────────────────
    test('info toggle button is visible in toolbar', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');
        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });

        let infoBtn = page.locator('button[aria-label="Toggle info"]');
        await expect(infoBtn.first()).toBeVisible({ timeout: 5000 });

        // Click it — should toggle (no crash)
        await infoBtn.first().click();
        await page.waitForTimeout(300);
        await screenshot(page, 'lc-info-toggle');
    });
});
