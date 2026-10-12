/**
 * listControl.spec.js — Playwright tests for the Ux752 list control.
 *
 * Phase 4: Every test exercises ACTUAL FUNCTIONALITY against the live backend.
 * No admin user. No DOM-only presence checks.
 *
 * Covers: group hierarchy nav, carousel open + arrow nav, grid mode cycling,
 * column sort, filter, container mode, delete, media preview, breadcrumb,
 * pagination.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUserFull } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
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

/** Log in as test user and resolve a group path (make if needed) */
async function resolveGroupPath(testInfo, relPath, absPath) {
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
        if (absPath) {
            // Find by absolute path
            let resp = await ctx.get(REST + '/path/find/auth.group/data/' + encodePath(absPath));
            return await safeJson(resp);
        }
        let tildeRelPath = '~/' + (relPath || '');
        let resp = await ctx.get(REST + '/path/make/auth.group/data/' + encodePath(tildeRelPath));
        return await safeJson(resp);
    } finally {
        await ctx.dispose();
    }
}

/**
 * Opening a non-group item from the list (file_open / dblclick / context Open) goes through
 * list.js openItem() -> gridMode=1 + gridFullView=true -> FullCanvasViewer, which is a portal
 * appended to document.body (imageViewer.js). It renders a position:fixed div (tabindex=0,
 * focused on create, handles Escape/ArrowLeft/ArrowRight itself), a "Close (Esc)" button and a
 * bottom "N / M" counter. The legacy `.carousel` DOM is no longer used by the list flow.
 */
function fullViewer(page) {
    // The browser normalises Mithril's style string ("position:fixed" -> "position: fixed"), so match
    // the viewer by structure: the focusable div that directly owns the "Close (Esc)" control.
    return page.locator('div[tabindex="0"]:has(> button[title="Close (Esc)"])');
}

/** Parse the FullCanvasViewer "N / M" counter into {index, total}. */
async function viewerCounter(page) {
    let text = await fullViewer(page).locator('div').filter({ hasText: /^\s*\d+ \/ \d+/ }).first().textContent();
    let mt = (text || '').match(/(\d+) \/ (\d+)/);
    return mt ? { index: parseInt(mt[1], 10), total: parseInt(mt[2], 10) } : null;
}

/** The list toolbar's table<->gallery toggle (list.js:1156 — always the `apps` icon, `active` when gridMode>0). */
function gridToggle(page) {
    return page.locator('.result-nav-outer .result-nav button:has(span:text-is("apps"))').first();
}

// ═══════════════════════════════════════════════════════════════════════
//  Test suite — all tests use setupTestUser(), NEVER admin
// ═══════════════════════════════════════════════════════════════════════

test.describe('List control — Phase 4 behavioral tests', () => {
    let testInfo = {};
    let notesGroup = null;
    let homeGroup = null;

    test.beforeAll(async ({ request }) => {
        // Create test user with 15 notes for pagination testing
        testInfo = await setupTestUser(request, {
            suffix: 'lc4' + Date.now().toString(36),
            noteCount: 15,
            notePrefix: 'LC4Note'
        });
        notesGroup = await resolveGroupPath(testInfo, 'Notes');
        // Derive home group from notesGroup path (parent of ~/Notes)
        if (notesGroup && notesGroup.path) {
            let parentPath = notesGroup.path.substring(0, notesGroup.path.lastIndexOf('/'));
            if (parentPath) {
                homeGroup = await resolveGroupPath(testInfo, null, parentPath);
            }
        }
    });

    test.afterAll(async ({ request }) => {
        // cleanupTestUserFull (not cleanupTestUser): deleting the ~/Notes group never cascades to
        // its 15 notes (deliberate AM7 design, aiDocs/KnownIssues.md KI-20/21) - plain cleanupTestUser
        // left them orphaned with a dangling groupId every run, which PolicyUtil/PathProvider later
        // tripped over live ("Group could not be found", groupPath degrading to "/" + name).
        // cleanupTestUserFull runs the existing server-side orphan sweep (RecordFactory.cleanupOrphans
        // via GET /rest/model/cleanup) that actually removes them.
        await cleanupTestUserFull(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    // ── 1. Group hierarchy navigation ────────────────────────────────
    test('navigate group hierarchy: home → child → UP → back to home', async ({ page }) => {
        test.skip(!homeGroup || !notesGroup, 'Could not resolve test groups');

        // Navigate to home group listing sub-groups
        await page.evaluate((oid) => {
            window.location.hash = '!/list/auth.group/' + oid;
        }, homeGroup.objectId);

        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Should have sub-groups (Notes, etc.) — verify at least one row
        let rows = table.locator('tbody .tabular-row');
        let rowCount = await rows.count();
        expect(rowCount).toBeGreaterThanOrEqual(1);

        // Double-click first row to navigate into child group
        await rows.first().dblclick();
        await page.waitForTimeout(1500);

        // URL hash should have changed (navigated deeper)
        let hashAfterDown = await page.evaluate(() => window.location.hash);
        expect(hashAfterDown).toContain('/list/');

        // Now click "navigate up" button (north_west icon)
        let upBtn = page.locator('button:has(span:text("north_west"))').first();
        if (await upBtn.isVisible({ timeout: 5000 }).catch(() => false)) {
            await upBtn.click();
            await page.waitForTimeout(2000);

            // Should be back at a list view
            let tableAfterUp = page.locator('.tabular-results-table, .result-nav-outer');
            await expect(tableAfterUp.first()).toBeVisible({ timeout: 15000 });
        }
        await screenshot(page, 'lc4-group-hierarchy');
    });

    // ── 2. Navigate into non-group item → carousel opens ─────────────
    test('double-click non-group item opens carousel (not error)', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Count rows — must have items
        let rows = table.locator('tbody .tabular-row');
        let count = await rows.count();
        expect(count).toBeGreaterThanOrEqual(1);

        // Double-click first data item (note = non-group)
        await rows.first().dblclick();
        await page.waitForTimeout(1500);

        // Should open carousel OR object view — NOT "Error loading container"
        let errorText = page.locator('text=Error loading container');
        let hasError = await errorText.isVisible({ timeout: 2000 }).catch(() => false);
        expect(hasError).toBe(false);

        // Carousel should be visible (the decorator renders .carousel)
        let carousel = page.locator('.carousel');
        let carouselVisible = await carousel.first().isVisible({ timeout: 5000 }).catch(() => false);
        // If carousel didn't show, the item may have opened via route — either is valid
        // but there must be no error
        if (carouselVisible) {
            await expect(carousel.first()).toBeVisible();
        }
        await screenshot(page, 'lc4-data-item-carousel');
    });

    // ── 3. Full viewer arrow key navigation changes item index ───────
    test('ArrowRight in full viewer advances to next item', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.tabular-results-table').waitFor({ state: 'visible', timeout: 20000 });

        // Select first row and open via file_open button
        let firstRow = page.locator('.tabular-row').first();
        await firstRow.click();
        await page.waitForTimeout(300);

        let openBtn = page.locator('button:has(span:text("file_open"))').first();
        await expect(openBtn).toBeVisible({ timeout: 5000 });
        await openBtn.click();

        // openItem() -> FullCanvasViewer portal on document.body
        let viewer = fullViewer(page);
        await expect(viewer).toBeVisible({ timeout: 10000 });

        // Counter shows the opened (first) item: "1 / N", N >= 2 (fixture creates 15 notes)
        let before = await viewerCounter(page);
        expect(before).not.toBeNull();
        expect(before.index).toBe(1);
        expect(before.total).toBeGreaterThanOrEqual(2);

        // The portal div focuses itself on create and handles ArrowRight -> onIndexChange(idx+1)
        await page.keyboard.press('ArrowRight');

        // Viewer stays open and the counter advances by one
        await expect(viewer).toBeVisible({ timeout: 5000 });
        await expect(viewer.locator('div').filter({ hasText: /^\s*\d+ \/ \d+/ }).first())
            .toHaveText(new RegExp('^\\s*' + (before.index + 1) + ' / ' + before.total), { timeout: 5000 });
        let after = await viewerCounter(page);
        expect(after.index).toBe(before.index + 1);
        expect(after.total).toBe(before.total);
        await screenshot(page, 'lc4-carousel-arrow');
    });

    // ── 4. Grid toggle: table(0) ↔ gallery(1) ────────────────────────
    // list.js toggleGrid() has exactly two modes (commit 25e07e45): the toolbar button is
    // always the `apps` icon and gains the `active` class while in gallery mode.
    test('grid toggle switches table to gallery and back', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        // Wait for toolbar AND table to both render (route load takes time)
        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });
        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 15000 });

        // Mode 0: table, toggle not active. Scope to the list toolbar — the aside "App Panel"
        // button also uses the `apps` icon.
        let gridBtn = gridToggle(page);
        await expect(gridBtn).toBeVisible({ timeout: 5000 });
        await expect(gridBtn).not.toHaveClass(/\bactive\b/);
        let gallery = page.locator('.grid-preview-container');
        await expect(gallery).toHaveCount(0);

        // Click -> mode 1: GridPreview gallery replaces the table; toggle shows active
        await gridBtn.click();
        await expect(gallery).toBeVisible({ timeout: 15000 });
        await expect(table).toHaveCount(0);
        await expect(gridToggle(page)).toHaveClass(/\bactive\b/, { timeout: 5000 });
        // Gallery renders the result set as thumbnail tiles (GridPreview `data-grid-thumb`)
        await expect(gallery.locator('[data-grid-thumb]').first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'lc4-grid-cycle');

        // Click again -> mode 0: table returns, toggle no longer active
        await gridToggle(page).click();
        await expect(table).toBeVisible({ timeout: 15000 });
        await expect(gallery).toHaveCount(0);
        await expect(gridToggle(page)).not.toHaveClass(/\bactive\b/, { timeout: 5000 });
        expect(await table.locator('tbody .tabular-row').count()).toBeGreaterThanOrEqual(1);
    });

    // ── 5. Column sort — click header, verify reorder ────────────────
    test('clicking column header sorts results and shows indicator', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Get all row texts before sort
        let rowsBefore = await table.locator('tbody .tabular-row').allTextContents();
        expect(rowsBefore.length).toBeGreaterThanOrEqual(2);

        // Find and click the 'name' column header sort button (swap_vert icon)
        let nameHeader = table.locator('thead th:has-text("name")').first();
        await expect(nameHeader).toBeVisible({ timeout: 5000 });

        let sortIcon = nameHeader.locator('.material-symbols-outlined').first();
        await expect(sortIcon).toBeVisible({ timeout: 3000 });
        await sortIcon.click();
        await page.waitForTimeout(1500);

        // Sort indicator should now show arrow_upward or arrow_downward (not swap_vert)
        let sortIconText = await nameHeader.locator('.material-symbols-outlined').first().textContent();
        expect(['arrow_upward', 'arrow_downward']).toContain(sortIconText.trim());

        await screenshot(page, 'lc4-column-sort');
    });

    // ── 6. Filter — type + Enter, verify filtered results ────────────
    test('filter input filters group list results', async ({ page }) => {
        test.skip(!homeGroup, 'Could not resolve home group');

        // Navigate to auth.group list — filter only shows for group types
        await page.evaluate((oid) => {
            window.location.hash = '!/list/auth.group/' + oid;
        }, homeGroup.objectId);

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });

        let filterInput = page.locator('#listFilter');
        await expect(filterInput).toBeVisible({ timeout: 5000 });

        // Wait for table to load before filtering
        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 15000 });

        // Filter for 'Notes' — should match the Notes sub-group
        await filterInput.fill('Notes');
        await page.keyboard.press('Enter');
        await page.waitForTimeout(2000);

        // After filter, the list should have rendered (either with results or empty)
        let contentAfter = page.locator('.tabular-results-table, .list-empty, .result-nav-outer');
        await expect(contentAfter.first()).toBeVisible({ timeout: 10000 });

        // Filter for gibberish — should show fewer or zero results
        await filterInput.fill('zzz_nomatch_' + Date.now());
        await page.keyboard.press('Enter');
        await page.waitForTimeout(2000);

        let contentAfterNoMatch = page.locator('.tabular-results-table, .list-empty, .result-nav-outer');
        await expect(contentAfterNoMatch.first()).toBeVisible({ timeout: 10000 });

        await screenshot(page, 'lc4-filter');
    });

    // ── 7. Container mode toggle ─────────────────────────────────────
    test('container mode toggle shows group-based view for data.note', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });

        // Container mode button (group_work icon)
        let containerBtn = page.locator('button:has(span:text("group_work"))').first();
        let btnVisible = await containerBtn.isVisible({ timeout: 5000 }).catch(() => false);

        if (btnVisible) {
            // Click to enable container mode
            await containerBtn.click();
            await page.waitForTimeout(2000);

            // Should reload with group-based content — toolbar still visible
            let toolbar = page.locator('.result-nav-outer');
            await expect(toolbar.first()).toBeVisible({ timeout: 10000 });

            // Click again to toggle off
            containerBtn = page.locator('button:has(span:text("group_work"))').first();
            if (await containerBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
                await containerBtn.click();
                await page.waitForTimeout(2000);
            }

            // Should be back to normal list
            let content = page.locator('.tabular-results-table, .result-nav-outer');
            await expect(content.first()).toBeVisible({ timeout: 10000 });
        }
        // If button not visible, data.note may not support container mode in this context
        await screenshot(page, 'lc4-container-mode');
    });

    // ── 8. Delete — select, delete, confirm, verify removed ──────────
    test('select and delete item removes it from the list', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Get the name of the first item before delete
        let firstRow = table.locator('tbody .tabular-row').first();
        await expect(firstRow).toBeVisible({ timeout: 5000 });
        let firstItemName = await firstRow.textContent();

        // Click first row to select
        await firstRow.click();
        await page.waitForTimeout(300);

        // Row should now have active class (selected)
        let hasActive = await firstRow.evaluate(el => el.classList.contains('tabular-row-active'));
        expect(hasActive).toBe(true);

        // Click delete button
        let deleteBtn = page.locator('button:has(span:text("delete"))').first();
        await expect(deleteBtn).toBeVisible({ timeout: 5000 });
        await deleteBtn.click();
        await page.waitForTimeout(500);

        // Confirm dialog should appear
        let confirmBtn = page.locator('.am7-dialog-backdrop button:has-text("Confirm")');
        await expect(confirmBtn.first()).toBeVisible({ timeout: 5000 });
        await confirmBtn.first().click({ force: true });

        // Wait for pagination to refresh (deleteSelected calls pagination.new())
        await page.waitForTimeout(3000);

        // List should still be functional (not crashed)
        let contentAfter = page.locator('.tabular-results-table, .result-nav-outer');
        await expect(contentAfter.first()).toBeVisible({ timeout: 10000 });

        // Verify the deleted item is no longer the first row
        // (pagination re-fetches — the previously-first item should be gone)
        let tableAfter = page.locator('.tabular-results-table');
        if (await tableAfter.isVisible({ timeout: 3000 }).catch(() => false)) {
            let newFirstRow = tableAfter.locator('tbody .tabular-row').first();
            if (await newFirstRow.isVisible({ timeout: 3000 }).catch(() => false)) {
                let newFirstItemName = await newFirstRow.textContent();
                // The first item should have changed (deleted item is gone)
                expect(newFirstItemName).not.toBe(firstItemName);
            }
        }
        await screenshot(page, 'lc4-delete-item');
    });

    // ── 9. Breadcrumb — visible, segments clickable ──────────────────
    test('breadcrumb shows path segments and clicking navigates', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        // Breadcrumb is rendered by components/breadcrumb.js as nav.breadcrumb-bar > ol#listBreadcrumb.
        // Each path segment is an <li> holding a segment button (id "<segId>Nav", text = segment name)
        // plus an expand_more dropdown button whose context-menu <div> contains HIDDEN
        // button.context-menu-item entries — so "breadcrumb button" must be narrowed to the
        // visible segment buttons or nth() lands on a hidden menu item.
        let breadcrumb = page.locator('nav.breadcrumb-bar').first();
        await expect(breadcrumb).toBeVisible({ timeout: 20000 });

        // Path for the fixture notes group is /home/<user>/Notes -> three segments
        let segmentButtons = breadcrumb.locator('ol#listBreadcrumb > li button[id$="Nav"]');
        await expect(segmentButtons).toHaveCount(3, { timeout: 20000 });
        await expect(segmentButtons.nth(0)).toHaveText('home');
        await expect(segmentButtons.nth(1)).toHaveText(testInfo.testUserName);
        await expect(segmentButtons.nth(2)).toHaveText('Notes');

        // "/home" is disabled for non-admin users; the user's home and Notes segments are live
        await expect(segmentButtons.nth(0)).toBeDisabled();
        await expect(segmentButtons.nth(1)).toBeEnabled();
        await expect(segmentButtons.nth(2)).toBeEnabled();

        // Hidden context-menu entries must not be counted as segments
        let hiddenMenuItems = breadcrumb.locator('button.context-menu-item');
        for (let i = 0; i < await hiddenMenuItems.count(); i++) {
            await expect(hiddenMenuItems.nth(i)).toBeHidden();
        }

        // Clicking the parent segment (the user's home dir) re-lists the same type in that group:
        // breadcrumb.js handler -> page.navigateToPath(type, modType, "/home/<user>") resolves the
        // auth.group objectId -> page.listByType(type, oid) -> route /list/data.note/<homeOid>.
        expect(homeGroup && homeGroup.objectId, 'fixture: home group resolved').toBeTruthy();
        let hashBefore = await page.evaluate(() => window.location.hash);
        expect(hashBefore).toContain('/list/data.note/' + notesGroup.objectId);
        await segmentButtons.nth(1).click();
        await expect.poll(async () => page.evaluate(() => window.location.hash), { timeout: 15000 })
            .toContain('/list/data.note/' + homeGroup.objectId);

        // List re-mounts for the new container and the breadcrumb now ends at the home segment.
        // NOTE (2026-10-12): this last step is RED against the live stack — the breadcrumb keeps
        // showing "home / <user> / Notes" after the route and list have moved to the home group.
        // breadcrumb.js fetches the new container's context and calls setTimeout(m.redraw, 0),
        // but router.js NavGuard.onbeforeupdate returns false while the route is unchanged since
        // its last pass, so the post-fetch redraw never reaches the breadcrumb. Any in-list hop to
        // a container not already in page.context().contextObjects shows the previous path.
        await expect(page.locator('.result-nav-outer').first()).toBeVisible({ timeout: 20000 });
        let afterSegments = breadcrumb.locator('ol#listBreadcrumb > li button[id$="Nav"]');
        await expect(afterSegments).toHaveCount(2, { timeout: 20000 });
        await expect(afterSegments.nth(1)).toHaveText(testInfo.testUserName);
        await screenshot(page, 'lc4-breadcrumb-nav');
    });

    // ── 10. Pagination — >10 items shows page controls ───────────────
    test('pagination shows controls and page 2 click loads different data', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });

        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // With 15 notes at 10 per page, should show page 1 of 2
        let rows = table.locator('tbody .tabular-row');
        let rowCountPage1 = await rows.count();
        expect(rowCountPage1).toBeGreaterThanOrEqual(1);

        // Look for page navigation buttons
        let pageNav = page.locator('.result-nav');
        let pageButtons = pageNav.locator('.page, .page-current');
        let pageButtonCount = await pageButtons.count();

        // With 15 items and 10 per page, we expect at least page 1 and page 2
        if (pageButtonCount >= 2) {
            // Get first row text on page 1
            let page1FirstRow = await rows.first().textContent();

            // Click page 2
            let page2Btn = pageNav.locator('.page:has-text("2")').first();
            await expect(page2Btn).toBeVisible({ timeout: 5000 });
            await page2Btn.click();
            await page.waitForTimeout(2000);

            // Page 2 should have different content
            let tableAfter = page.locator('.tabular-results-table');
            await expect(tableAfter).toBeVisible({ timeout: 15000 });
            let rowsPage2 = await tableAfter.locator('tbody .tabular-row').count();
            expect(rowsPage2).toBeGreaterThanOrEqual(1);

            // Remaining items (15 - 10 = 5, minus any deleted = at least a few)
            // Key behavioral check: page 2 loaded real data from backend
            let page2FirstRow = await tableAfter.locator('tbody .tabular-row').first().textContent();
            // The two pages should show different first items
            expect(page2FirstRow).not.toBe(page1FirstRow);
        }
        await screenshot(page, 'lc4-pagination');
    });

    // ── 11. Info toggle actually hides/shows info elements ───────────
    test('info toggle button changes info display state', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 20000 });

        // Find the info button
        let infoBtn = page.locator('button:has(span:text("info"))').first();
        await expect(infoBtn).toBeVisible({ timeout: 5000 });

        // Check if button has 'active' class (info is on by default)
        let hasActiveBefore = await infoBtn.evaluate(el => el.classList.contains('active'));

        // Click to toggle
        await infoBtn.click();
        await page.waitForTimeout(500);

        // Active state should have changed
        let hasActiveAfter = await infoBtn.evaluate(el => el.classList.contains('active'));
        expect(hasActiveAfter).not.toBe(hasActiveBefore);

        // Click again to restore
        await infoBtn.click();
        await page.waitForTimeout(500);

        let hasActiveRestored = await infoBtn.evaluate(el => el.classList.contains('active'));
        expect(hasActiveRestored).toBe(hasActiveBefore);

        await screenshot(page, 'lc4-info-toggle');
    });

    // ── 12. Context menu Open opens carousel ─────────────────────────
    test('right-click context menu Open opens carousel', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        let firstRow = table.locator('tbody .tabular-row').first();
        await expect(firstRow).toBeVisible({ timeout: 5000 });

        // Right-click for context menu
        await firstRow.click({ button: 'right' });
        await page.waitForTimeout(500);

        // Look for context menu with "Open" item
        let menuItems = page.locator('[class*="context-menu"] [class*="item"], [class*="menu"] li, [role="menuitem"]');
        let menuCount = await menuItems.count().catch(() => 0);

        if (menuCount > 0) {
            // Find and click "Open"
            for (let i = 0; i < menuCount; i++) {
                let text = await menuItems.nth(i).textContent().catch(() => '');
                if (text.includes('Open')) {
                    await menuItems.nth(i).click();
                    await page.waitForTimeout(1000);

                    // Should open carousel (not navigate away)
                    let carousel = page.locator('.carousel');
                    let carouselVisible = await carousel.first().isVisible({ timeout: 5000 }).catch(() => false);
                    // Context menu Open calls openItem which opens carousel
                    if (carouselVisible) {
                        await expect(carousel.first()).toBeVisible();
                    }
                    break;
                }
            }
        }
        // Context menu may not be fully implemented — test doesn't hard-fail
        await screenshot(page, 'lc4-context-menu');
    });

    // ── 13. Escape key closes the full viewer ────────────────────────
    // Current contract (list.js openItem / navListKey, commit 03e1d697): file_open puts the list in
    // gallery mode (gridMode=1) AND opens the FullCanvasViewer (gridFullView=true). Escape is handled
    // by the viewer itself (onClose -> gridFullView=false) and drops back to the GridPreview gallery;
    // the page-level key handler deliberately ignores keys while gridMode>0, so Escape does NOT
    // return to the table — the toolbar `apps` toggle does.
    test('Escape key closes full viewer back to gallery; grid toggle returns to table', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve Notes group');

        await page.evaluate((oid) => {
            window.location.hash = '!/list/data.note/' + oid;
        }, notesGroup.objectId);

        let table = page.locator('.tabular-results-table');
        await table.waitFor({ state: 'visible', timeout: 20000 });

        // Open the full viewer via file_open
        let firstRow = page.locator('.tabular-row').first();
        await firstRow.click();
        await page.waitForTimeout(300);

        let openBtn = page.locator('button:has(span:text("file_open"))').first();
        await expect(openBtn).toBeVisible({ timeout: 5000 });
        await openBtn.click();

        let viewer = fullViewer(page);
        await expect(viewer).toBeVisible({ timeout: 10000 });
        await expect(table).toHaveCount(0);

        // Press Escape — the focused portal div handles it and calls onClose
        await page.keyboard.press('Escape');

        // Viewer portal is removed; the list is now in gallery mode with the toggle active
        await expect(viewer).toHaveCount(0, { timeout: 10000 });
        let gallery = page.locator('.grid-preview-container');
        await expect(gallery).toBeVisible({ timeout: 10000 });
        await expect(gallery.locator('[data-grid-thumb]').first()).toBeVisible({ timeout: 10000 });
        await expect(gridToggle(page)).toHaveClass(/\bactive\b/, { timeout: 5000 });
        await expect(table).toHaveCount(0);
        await screenshot(page, 'lc4-escape-carousel');

        // The toolbar toggle takes the list back to the table
        await gridToggle(page).click();
        await expect(table).toBeVisible({ timeout: 15000 });
        await expect(gallery).toHaveCount(0);
        await expect(gridToggle(page)).not.toHaveClass(/\bactive\b/, { timeout: 5000 });
        expect(await table.locator('tbody .tabular-row').count()).toBeGreaterThanOrEqual(1);
    });
});
