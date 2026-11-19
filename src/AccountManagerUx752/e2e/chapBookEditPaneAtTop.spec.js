/**
 * ChapBook — P10-C 2: after create (analysis), the reader / review pane must be IN VIEW, not below the fold.
 *
 * Stephen (2026-11-04): "in chapbook create, after analysis, the edit view is on the bottom so looks like a
 * css issue". Reproduced as the shared user: the shared ChapBookConfig panel renders the FULL poem queue
 * (every library poem — 200+ rows for e2etest_shared) expanded at the top of every view, so the reader
 * the create navigates to, and the review view's scene cards, start thousands of px below the viewport.
 *
 * This spec drives the REAL UI create (dialog → job → LLM analysis on the live chat route → navigate to
 * /chap-book/edit/:id?guided=1 — design §7.2 since the Read | Edit split) as the shared user (never admin), then
 * measures bounding boxes against the viewport:
 *   - edit view right after create: the first page card action (cb-page-analyze) is inside the viewport at scroll 0
 *   - read view:   the reader's bookHeader back control ("Back to ChapBooks") is inside the viewport at scroll 0
 *   - edit › pages view (by route): the first scene card action is inside the viewport at scroll 0
 *   - create view: the "My ChapBooks" heading is inside the viewport at scroll 0 (same queue, same fold)
 * Timings are observations, never thresholds — the create waits generously for the live LLM.
 *
 * Run (single-threaded, live LLM):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookEditPaneAtTop.spec.js \
 *     --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, REST, POEM_1, POEM_2
} from './helpers/chapbook.js';

const PANEL = '[data-testid="cb-config-panel"]';
const OUT_DIR = path.resolve('test-results', 'p10-c-2');

function shot(page, name) {
    fs.mkdirSync(OUT_DIR, { recursive: true });
    return page.screenshot({ path: path.join(OUT_DIR, name), fullPage: false });
}

// Where an element sits relative to the viewport at the CURRENT scroll position.
async function placement(page, locator) {
    const box = await locator.first().boundingBox();
    const vp = page.viewportSize();
    const scrollY = await page.evaluate(() => window.scrollY);
    // The poem-queue panel is library-only since the Read | Edit split (§7.2): absent on Edit / Read, so measure it
    // only where it exists (boundingBox() on a missing element waits out the whole test timeout).
    const panelBox = (await page.locator(PANEL).count()) ? await page.locator(PANEL).boundingBox() : null;
    const queueRows = await page.locator(PANEL + ' tbody tr').count();
    return {
        top: box ? Math.round(box.y) : null, bottom: box ? Math.round(box.y + box.height) : null,
        viewportHeight: vp.height, scrollY, panelHeight: panelBox ? Math.round(panelBox.height) : null, queueRows
    };
}

function expectInView(p, what) {
    expect(p.top, what + ' is rendered').not.toBeNull();
    expect(p.bottom, what + ' bottom edge inside the viewport (top=' + p.top + ' bottom=' + p.bottom
        + ' viewport=' + p.viewportHeight + ' panel=' + p.panelHeight + 'px queueRows=' + p.queueRows + ')')
        .toBeLessThanOrEqual(p.viewportHeight);
}

async function seedPoems(request) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    return { p1, p2, groupId, organizationId };
}

// The queue's row checkbox for the poem whose Title cell is `title` (stopPropagation keeps the row click off).
function rowCheckbox(page, title) {
    return page.locator(PANEL + ' tbody tr').filter({ has: page.locator('td', { hasText: new RegExp('^' + title + '$') }) })
        .first().locator('input[type="checkbox"]');
}

test.describe('ChapBook create → the edit / reader pane is in view (P10-C 2)', () => {

    test('UI create as the shared user lands with the edit pane in view; read + edit + create views too', async ({ page, request }) => {
        // One live-LLM create (two poems) plus three measured views.
        test.setTimeout(900000);
        await seedPoems(request);

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book', { timeout: 30000 });
        await expect(page.locator(PANEL)).toBeVisible({ timeout: 20000 });
        // The queue pages through every library poem; wait for the two seeded rows.
        await expect(rowCheckbox(page, 'Memory')).toBeAttached({ timeout: 60000 });
        await expect(rowCheckbox(page, 'Winter')).toBeAttached({ timeout: 60000 });

        // CREATE view: "My ChapBooks" sits under the same panel + queue.
        const createView = await placement(page, page.locator('h3', { hasText: 'My ChapBooks' }));
        await shot(page, '01-create-view.png');
        console.log('[p10c2] create view: ' + JSON.stringify(createView));

        await rowCheckbox(page, 'Memory').check();
        await rowCheckbox(page, 'Winter').check();
        await page.locator(PANEL).getByRole('button', { name: /Create ChapBook \(2\)/ }).click();
        const dialog = page.locator('div.fixed', { has: page.locator('h3', { hasText: 'Create ChapBook' }) });
        await expect(dialog).toBeVisible();
        const stamp = Date.now().toString(36);
        await dialog.locator('input[type="text"]').first().fill('P10C2 edit pane ' + stamp);
        await shot(page, '02-create-dialog.png');
        const t0 = Date.now();
        await dialog.getByRole('button', { name: 'Create', exact: true }).click();

        // The create runs the per-poem analysis on the live chat route, then opens the book in Edit (guided, §7.2).
        await page.waitForURL(/#!\/chap-book\/edit\//, { timeout: 840000 });
        const bookOid = decodeURIComponent(page.url()).split('/chap-book/edit/')[1].split(/[/?#]/)[0];
        console.log('[p10c2] created ' + bookOid + ' after ' + Math.round((Date.now() - t0) / 1000) + 's (observation) → ' + decodeURIComponent(new URL(page.url()).hash));
        await expect(page.getByTestId('book-workspace')).toBeVisible({ timeout: 60000 });
        await expect(page.locator(PANEL), 'the poem queue is library-only (§7.2)').toHaveCount(0);
        // The guided entry resolves to the next incomplete section (in practice Pages); measure the page card there.
        await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'pages', { timeout: 60000 });
        await expect(page.locator('[data-testid="cb-page-analyze"]').first()).toBeAttached({ timeout: 60000 });
        const afterCreateView = await placement(page, page.locator('[data-testid="cb-page-analyze"]'));
        await shot(page, '03-after-create-edit.png');
        console.log('[p10c2] edit view after create: ' + JSON.stringify(afterCreateView));

        // READ view: the bookHeader back control.
        await page.goto('/#!/chap-book/read/' + bookOid, { timeout: 30000 });
        const back = page.getByTestId('book-back');
        await expect(back).toHaveAttribute('title', 'Back to ChapBooks', { timeout: 60000 });
        await expect(page.locator(PANEL)).toHaveCount(0);
        const readView = await placement(page, back);
        await shot(page, '03b-read-view.png');
        console.log('[p10c2] read view: ' + JSON.stringify(readView));

        // Edit › Pages by route: the scene cards with the prompt editors.
        await page.goto('/#!/chap-book/edit/' + bookOid + '/pages', { timeout: 30000 });
        await expect(page.locator('[data-testid="cb-page-analyze"]').first()).toBeAttached({ timeout: 60000 });
        await expect(page.locator(PANEL)).toHaveCount(0);
        const reviewView = await placement(page, page.locator('[data-testid="cb-page-analyze"]'));
        await shot(page, '04-review-view.png');
        console.log('[p10c2] review view: ' + JSON.stringify(reviewView));

        fs.mkdirSync(OUT_DIR, { recursive: true });
        fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify({ bookOid, createView, afterCreateView, readView, reviewView }, null, 2));

        expectInView(afterCreateView, 'first page card action (cb-page-analyze) in Edit right after the UI create');
        expectInView(readView, 'reader header (Back to ChapBooks) on the read view');
        expectInView(reviewView, 'first scene card action (cb-page-analyze) on the review view');
        expectInView(createView, '"My ChapBooks" heading on the create view');
    });
});
