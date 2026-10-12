/**
 * List navigation: create 25 notes, switch views, navigate, verify pagination.
 * Uses shared test user (NEVER admin).
 *
 * Current list design (src/views/list.js + src/components/imageViewer.js):
 *   - toolbar buttons are `page.iconButton` => `button > span.material-symbols-outlined` inside
 *     `.list-results-container .result-nav.tab-container`; the `apps` button carries class `active`
 *     while gridMode > 0 (list.js:1156) and toggles table <-> GridPreview (toggleGrid, list.js:832).
 *   - `file_open` (list.js:1054) -> openSelected -> openItem sets gridMode=1 + gridFullView=true, i.e. the
 *     carousel is the FullCanvasViewer portal (imageViewer.js:198-229: `div[tabindex=0]` on body with a
 *     `button[title="Close (Esc)"]` and an "N / M" counter). Escape runs onClose which only clears
 *     gridFullView (list.js:1228), so one Escape lands in GridPreview (`.grid-preview-container`,
 *     thumbs `[data-grid-thumb]`), NOT the table. Getting back to the table is a click on `apps`.
 *   - table rows are `tr.tabular-row[draggable]` (decorator.js:357); grid thumbs are NOT draggable.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, createNote } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

// PLAYWRIGHT_BASE_URL is mandatory for every run; no local-dev fallback.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL;
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }

const TOOLBAR = '.list-results-container .result-nav.tab-container';
const APPS_BTN = TOOLBAR + ' button:has(span.material-symbols-outlined:text-is("apps"))';
const OPEN_BTN = TOOLBAR + ' button:has(span.material-symbols-outlined:text-is("file_open"))';
const TABLE_ROWS = 'table tbody tr';
const GRID = '.grid-preview-container';
const THUMBS = GRID + ' [data-grid-thumb]';
// FullCanvasViewer portal root (imageViewer.js:198-213)
const VIEWER = 'div[tabindex="0"]:has(> button[title="Close (Esc)"])';

function counterOf(page) {
    // "N / M" index counter at the bottom of the viewer (imageViewer.js:220-222)
    return page.locator(VIEWER + ' > div').filter({ hasText: /^\d+ \/ \d+/ }).first();
}

async function readCounter(page) {
    let txt = (await counterOf(page).textContent()) || '';
    let mt = txt.match(/^(\d+) \/ (\d+)/);
    return mt ? { idx: parseInt(mt[1]), total: parseInt(mt[2]) } : null;
}

// Stub WebSocket before any navigation — Docker's nginx drops the cookie on the WS upgrade, so the
// real socket closes at once and pageClient.reconnect() -> forceLogin() redirects to #!/sig.
async function stubWebSocket(page) {
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 1;
                setTimeout(() => { if (this.onopen) this.onopen({}); }, 0);
            }
            send() {}
            close() {}
            addEventListener() {}
            removeEventListener() {}
        };
    });
}

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
        if (!(lr.ok() || lr.status() === 204)) throw new Error('Login as ' + shared.testUserName + ' failed: HTTP ' + lr.status());

        // Ensure ~/NavTest directory
        let dirResp = await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/NavTest').replace(/=/g, '%3D'));
        if (!dirResp.ok()) { await ctx.dispose(); throw new Error('Path make ~/NavTest failed: HTTP ' + dirResp.status()); }
        let dir = await dirResp.json();
        notesDirOid = dir.objectId;
        console.log('NavTest dir:', notesDirOid);

        // Create notes — use existing ones if already created
        let checkResp = await ctx.post(REST + '/model/search', {
            data: { schema: 'io.query', type: 'data.note',
                fields: [{ name: 'groupId', comparator: 'equals', value: dir.id }],
                request: ['id'], recordCount: 1, cache: false }
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

    // Log in over REST, copy the session cookie into the browser context, open the NavTest note list.
    async function openNoteList(page) {
        let ctx = await newCtx();
        let lr = await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        if (!(lr.ok() || lr.status() === 204)) throw new Error('Login as ' + shared.testUserName + ' failed: HTTP ' + lr.status());
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();

        await stubWebSocket(page);
        await page.goto('/');
        // Let the SPA finish its own initial routing to #!/main first — a hash change issued before that
        // completes is overridden by the app's own m.route.set('/main').
        await page.waitForFunction(
            () => window.location.hash.includes('/main') && !!document.querySelector('[role="main"]'),
            null, { timeout: 30000 }
        );

        await page.goto('#!/list/data.note/' + notesDirOid);
        await expect(page.locator(TABLE_ROWS).first(), 'note list must render table rows').toBeVisible({ timeout: 30000 });
        // Exactly one list toolbar / one apps toggle on the page (the old loose selector hit two buttons).
        await expect(page.locator(APPS_BTN)).toHaveCount(1);
    }

    test('Table view: paginate forward and back', async ({ page }) => {
        await openNoteList(page);

        let rows = await page.locator(TABLE_ROWS).count();
        console.log('Table rows on page 1:', rows);
        expect(rows).toBeGreaterThan(0);
        let firstName = await page.locator(TABLE_ROWS).first().textContent();
        await page.screenshot({ path: 'e2e/screenshots/listnav-01-table-p1.png' });

        // 25 notes at page size 10 => the pager must offer a next page.
        let nextBtn = page.locator('nav.result-nav button:has(span.material-symbols-outlined:text-is("chevron_right"))').first();
        await expect(nextBtn, 'next-page button must be present for a multi-page list').toBeVisible({ timeout: 5000 });
        await nextBtn.click();
        // Page 2 shows different rows than page 1.
        await expect(page.locator(TABLE_ROWS).first()).not.toHaveText(firstName || '', { timeout: 15000 });
        let rows2 = await page.locator(TABLE_ROWS).count();
        console.log('Table rows on page 2:', rows2);
        expect(rows2).toBeGreaterThan(0);
        await page.screenshot({ path: 'e2e/screenshots/listnav-02-table-p2.png' });

        // Navigate back
        let prevBtn = page.locator('nav.result-nav button:has(span.material-symbols-outlined:text-is("chevron_left"))').first();
        await expect(prevBtn).toBeVisible({ timeout: 5000 });
        await prevBtn.click();
        await expect(page.locator(TABLE_ROWS).first()).toHaveText(firstName || '', { timeout: 15000 });
        let rows1b = await page.locator(TABLE_ROWS).count();
        console.log('Table rows back on page 1:', rows1b);
        expect(rows1b).toBe(rows);
    });

    test('Icon view: switch from table, items visible, switch back', async ({ page }) => {
        await openNoteList(page);

        let tableRows = await page.locator(TABLE_ROWS).count();
        console.log('Table rows before switch:', tableRows);
        expect(tableRows).toBeGreaterThan(0);

        // Switch to icon view
        let gridBtn = page.locator(APPS_BTN);
        await expect(gridBtn).not.toHaveClass(/active/);
        await gridBtn.click();
        await expect(page.locator(GRID), 'GridPreview must replace the table').toBeVisible({ timeout: 10000 });
        await expect(gridBtn).toHaveClass(/active/);
        await expect(page.locator(TABLE_ROWS)).toHaveCount(0);
        await page.screenshot({ path: 'e2e/screenshots/listnav-03-icon.png' });

        // Verify icon items are visible — one thumb per loaded result
        await expect(page.locator(THUMBS).first()).toBeVisible({ timeout: 10000 });
        let iconItems = await page.locator(THUMBS).count();
        console.log('Icon items:', iconItems);
        expect(iconItems).toBeGreaterThanOrEqual(tableRows);

        // Switch back to table
        await gridBtn.click();
        await expect(page.locator(TABLE_ROWS).first()).toBeVisible({ timeout: 10000 });
        await expect(gridBtn).not.toHaveClass(/active/);
        await expect(page.locator(GRID)).toHaveCount(0);
        await page.screenshot({ path: 'e2e/screenshots/listnav-04-table-back.png' });

        let tableRowsAfter = await page.locator(TABLE_ROWS).count();
        console.log('Table rows after switch back:', tableRowsAfter);
        expect(tableRowsAfter).toBeGreaterThan(0);
    });

    test('Carousel: open item, navigate past page boundary, close, list intact', async ({ page }) => {
        await openNoteList(page);

        let initialRows = await page.locator(TABLE_ROWS).count();
        console.log('Initial table rows:', initialRows);
        expect(initialRows).toBeGreaterThan(0);

        // Select first row (decorator.js:355 onclick -> ctl.select) then open via file_open
        let firstRow = page.locator(TABLE_ROWS).first();
        await firstRow.click();
        await expect(firstRow).toHaveClass(/tabular-row-active/, { timeout: 5000 });

        let openBtn = page.locator(OPEN_BTN);
        await expect(openBtn, 'file_open toolbar button must be present').toBeVisible({ timeout: 5000 });
        await openBtn.click();

        // The carousel is the FullCanvasViewer portal on body
        let viewer = page.locator(VIEWER);
        await expect(viewer, 'FullCanvasViewer must open from file_open').toBeVisible({ timeout: 10000 });
        let start = await readCounter(page);
        console.log('Carousel counter on open:', JSON.stringify(start));
        expect(start, 'viewer shows an "N / M" counter').not.toBeNull();
        expect(start.idx).toBe(1);
        expect(start.total).toBe(initialRows);
        await page.screenshot({ path: 'e2e/screenshots/listnav-05-carousel.png' });

        // Navigate right past the end of page 1 — nav() past the last loaded item calls onLoadMore
        // (imageViewer.js:188-190 -> pagination.loadNextPage) so the total must grow.
        for (let i = 0; i < initialRows + 2; i++) {
            await page.keyboard.press('ArrowRight');
            await page.waitForTimeout(300);
        }
        await expect.poll(async () => (await readCounter(page) || {}).total, {
            message: 'crossing the page boundary must load the next page into the carousel',
            timeout: 30000
        }).toBeGreaterThan(initialRows);
        let after = await readCounter(page);
        console.log('Carousel counter after navigation:', JSON.stringify(after));
        expect(after.idx).toBeGreaterThan(1);
        await page.screenshot({ path: 'e2e/screenshots/listnav-06-carousel-navigated.png' });

        // Escape closes the carousel (onClose -> gridFullView=false, list.js:1228) …
        await page.keyboard.press('Escape');
        await expect(viewer).toHaveCount(0, { timeout: 10000 });
        // … and by current design lands in GridPreview (gridMode stays 1), not the table.
        // DESIGN QUESTION (not a regression): should closing the carousel restore the view it was opened from?
        await expect(page.locator(GRID), 'after Escape the list is in grid-preview mode').toBeVisible({ timeout: 10000 });
        await expect(page.locator(APPS_BTN)).toHaveClass(/active/);
        expect(await page.locator(THUMBS).count()).toBeGreaterThanOrEqual(initialRows);
        await page.screenshot({ path: 'e2e/screenshots/listnav-07-after-carousel.png' });

        // Toggling grid off returns to the table with its rows intact
        await page.locator(APPS_BTN).click();
        await expect(page.locator(TABLE_ROWS).first()).toBeVisible({ timeout: 10000 });
        let afterRows = await page.locator(TABLE_ROWS).count();
        console.log('Table rows after carousel:', afterRows);
        expect(afterRows).toBeGreaterThan(0);
    });

    test('Icon full view: open, navigate, close, list intact', async ({ page }) => {
        await openNoteList(page);

        // Switch to icon view
        let gridBtn = page.locator(APPS_BTN);
        await gridBtn.click();
        await expect(page.locator(GRID)).toBeVisible({ timeout: 10000 });
        await expect(page.locator(THUMBS).first()).toBeVisible({ timeout: 10000 });
        let thumbsBefore = await page.locator(THUMBS).count();
        console.log('Icon items before full view:', thumbsBefore);
        expect(thumbsBefore).toBeGreaterThan(1);

        // Open full view from the preview pane's "View" button (imageViewer.js:372-375 -> onFullView)
        let viewBtn = page.locator(GRID + ' button.iv-btn:has-text("View")');
        await expect(viewBtn).toBeVisible({ timeout: 5000 });
        await viewBtn.click();
        let viewer = page.locator(VIEWER);
        await expect(viewer, 'FullCanvasViewer must open from the grid View button').toBeVisible({ timeout: 10000 });
        await page.screenshot({ path: 'e2e/screenshots/listnav-08-fullview.png' });

        // Navigate one to the right; the viewer's onIndexChange moves the list's gridSelectedIdx
        await page.keyboard.press('ArrowRight');
        await expect(counterOf(page)).toHaveText(/^2 \/ \d+/, { timeout: 10000 });

        // Close with Escape — back to GridPreview with the second thumb now selected
        await page.keyboard.press('Escape');
        await expect(viewer).toHaveCount(0, { timeout: 10000 });
        await expect(page.locator(GRID)).toBeVisible({ timeout: 10000 });
        let iconItems = await page.locator(THUMBS).count();
        console.log('Icon items after full view close:', iconItems);
        expect(iconItems).toBeGreaterThan(0);
        await expect(page.locator(THUMBS).nth(1), 'selection made in the viewer survives closing it').toHaveClass(/border-blue-500/);

        // Switch back to table
        await gridBtn.click();
        await expect(page.locator(TABLE_ROWS).first()).toBeVisible({ timeout: 10000 });
        let tableRows = await page.locator(TABLE_ROWS).count();
        console.log('Table rows after icon->fullview->table:', tableRows);
        expect(tableRows).toBeGreaterThan(0);
    });
});
