/**
 * listGalleryOpen.spec.js — opening a selected table row in the gallery viewer, navigating past the
 * table's page boundary, closing back to the grid, and the video loop control.
 *
 * Regression coverage for (2026-11-26):
 *   1. Selecting row N in the table and pressing Open (file_open) opened the FIRST item instead:
 *      openItem() set gridMode=1, and the next onupdate → update() rebuilt pagination with the icon
 *      record count (40 ≠ 10), emptying the results so the selected index clamped to 0.
 *   2. Navigating in the opened gallery stopped at the end of the table page: after each
 *      loadNextPage, update() recomputed currentPage from the route's startRecord and snapped back.
 *   3. Closing the gallery into the grid then looped: GridPreview's "load more when it fits"
 *      re-triggered loadNextPage, update() snapped back again, forever.
 *   4. Video loop toggle (FullCanvasViewer + GridPreview), persisted in localStorage.
 *
 * Runs as the shared non-admin test user (ensureSharedTestUser), in its own uniquely named group.
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, createObject, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const NOTE_COUNT = 25;          // 3 table pages of 10
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const VIDEO_FIXTURE = path.resolve(__dirname, '../../AccountManagerObjects7/media/YoureFired.mp4');

function fullViewer(page) {
    return page.locator('div[tabindex="0"]:has(> button[title="Close (Esc)"])');
}
function viewerCounterLoc(page) {
    return fullViewer(page).locator('div').filter({ hasText: /^\s*\d+ \/ \d+/ }).first();
}
async function viewerCounter(page) {
    let text = await viewerCounterLoc(page).textContent();
    let mt = (text || '').match(/(\d+) \/ (\d+)/);
    return mt ? { index: parseInt(mt[1], 10), total: parseInt(mt[2], 10) } : null;
}
/** The viewer's top-centre title — the opened row's name. */
function viewerTitle(page, name) {
    return fullViewer(page).locator('div', { hasText: new RegExp('^' + name + '$') }).first();
}
/**
 * Press ArrowRight until the counter reads `target` (1-based), never overshooting. At the end of the
 * loaded items a press only requests the next page (FullCanvasViewer nav → onLoadMore) and the index
 * stays put, so a second press is sent only after the counter has demonstrably not moved.
 */
async function stepTo(page, target) {
    for (let attempt = 0; attempt < 3; attempt++) {
        let before = (await viewerCounter(page)).index;
        if (before === target) return;
        expect(before, 'viewer overshot').toBeLessThan(target);
        await page.keyboard.press('ArrowRight');
        try {
            await expect.poll(async () => (await viewerCounter(page)).index, { timeout: 3000 }).toBe(before + 1);
        } catch (e) {
            // unchanged → that press loaded the next page; loop and press again
            if ((await viewerCounter(page)).index !== before) throw e;
        }
    }
    expect((await viewerCounter(page)).index).toBe(target);
}
function noteName(i) { return 'GvNote_' + String(i).padStart(2, '0'); }

async function selectRowAndOpen(page, name) {
    let row = page.locator('tbody .tabular-row', { hasText: name }).first();
    await expect(row).toBeVisible({ timeout: 20000 });
    await row.click();
    let openBtn = page.locator('.result-nav-outer button:has(span:text-is("file_open"))').first();
    await expect(openBtn).not.toHaveClass(/inactive/, { timeout: 5000 });
    await openBtn.click();
    await expect(fullViewer(page)).toBeVisible({ timeout: 10000 });
}

test.describe('List → gallery open / navigate / close', () => {
    let creds = {};
    let notesDir = null;
    let videoDir = null;
    let created = [];   // [{type, objectId}]
    const stamp = Date.now().toString(36);

    test.beforeAll(async ({ request }) => {
        creds = await ensureSharedTestUser(request);
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: creds.testUserName, password: creds.testPassword });
            notesDir = await ensurePath(ctx, 'auth.group', 'data', '~/GalleryNavE2E-' + stamp);
            videoDir = await ensurePath(ctx, 'auth.group', 'data', '~/GalleryVideoE2E-' + stamp);
            expect(notesDir && notesDir.id, 'notes group').toBeTruthy();
            expect(videoDir && videoDir.id, 'video group').toBeTruthy();
            for (let i = 0; i < NOTE_COUNT; i++) {
                let n = await createObject(ctx, 'data.note', {
                    groupId: notesDir.id, groupPath: notesDir.path, name: noteName(i), text: 'gallery nav ' + i
                });
                expect(n && n.objectId, 'note ' + i).toBeTruthy();
                created.push({ type: 'data.note', objectId: n.objectId });
            }
            let vid = await createObject(ctx, 'data.data', {
                groupId: videoDir.id, groupPath: videoDir.path, name: 'gallery-loop-' + stamp + '.mp4',
                contentType: 'video/mp4', dataBytesStore: fs.readFileSync(VIDEO_FIXTURE).toString('base64')
            });
            expect(vid && vid.objectId, 'video').toBeTruthy();
            created.push({ type: 'data.data', objectId: vid.objectId });
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    }, 120000);

    test.afterAll(async () => {
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: creds.testUserName, password: creds.testPassword });
            for (const c of created) await deleteObject(ctx, c.type, c.objectId).catch(() => {});
            for (const g of [notesDir, videoDir]) if (g) await deleteObject(ctx, 'auth.group', g.objectId).catch(() => {});
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    }, 120000);

    test.beforeEach(async ({ page }) => {
        await login(page, { user: creds.testUserName, password: creds.testPassword });
    });

    test('Open on a selected row opens THAT row, and navigation runs past the table page', async ({ page }) => {
        await page.evaluate((oid) => { window.location.hash = '!/list/data.note/' + oid; }, notesDir.objectId);
        await page.locator('.tabular-results-table').waitFor({ state: 'visible', timeout: 20000 });

        // Bug 1: select the 4th row (not the first) and open it.
        await selectRowAndOpen(page, noteName(3));
        await expect(viewerTitle(page, noteName(3))).toBeVisible({ timeout: 5000 });
        await expect(viewerCounterLoc(page)).toHaveText(/^\s*4 \/ \d+/, { timeout: 5000 });
        // Let the post-open redraw/onupdate cycle run — the old bug reset the index during it.
        await page.waitForTimeout(1500);
        expect((await viewerCounter(page)).index).toBe(4);
        await expect(viewerTitle(page, noteName(3))).toBeVisible();

        // Bug 2: ArrowRight past the 10-row table page boundary into page 2 and on to item 13.
        for (let i = 5; i <= 13; i++) await stepTo(page, i);
        await expect(viewerTitle(page, noteName(12))).toBeVisible();
        expect((await viewerCounter(page)).total).toBeGreaterThanOrEqual(13);

        // Bug 3: close into the grid; it must settle with every item loaded and stay settled.
        await page.keyboard.press('Escape');
        await expect(fullViewer(page)).toHaveCount(0, { timeout: 5000 });
        let thumbs = page.locator('[data-grid-thumb]');
        await expect(thumbs).toHaveCount(NOTE_COUNT, { timeout: 15000 });
        let samples = [];
        for (let s = 0; s < 15; s++) { samples.push(await thumbs.count()); await page.waitForTimeout(200); }
        expect(samples.every(n => n === NOTE_COUNT), 'grid thumb count stable: ' + samples.join(',')).toBe(true);

        // Item 6: the table selection must not linger as an active Open/Edit/Delete in the grid.
        for (const ico of ['file_open', 'edit', 'delete']) {
            await expect(page.locator('.result-nav-outer button:has(span:text-is("' + ico + '"))').first())
                .toHaveClass(/inactive/, { timeout: 5000 });
        }
    });

    test('Open on a selected row from table page 2 opens that row', async ({ page }) => {
        await page.evaluate((oid) => { window.location.hash = '!/list/data.note/' + oid; }, notesDir.objectId);
        await page.locator('.tabular-results-table').waitFor({ state: 'visible', timeout: 20000 });
        await page.locator('.result-nav-outer button:has(span:text-is("chevron_right"))').first().click();
        await expect(page.locator('tbody .tabular-row', { hasText: noteName(12) })).toBeVisible({ timeout: 15000 });

        await selectRowAndOpen(page, noteName(12));
        await page.waitForTimeout(1500);
        await expect(viewerTitle(page, noteName(12))).toBeVisible();

        // Moving forward lands on the next record, and keeps going onto page 3.
        let start = (await viewerCounter(page)).index;
        for (let step = 1; step <= 9; step++) {
            await stepTo(page, start + step);
            await expect(viewerTitle(page, noteName(12 + step))).toBeVisible({ timeout: 5000 });
        }
    });

    test('Video loop toggle sets loop on the element and persists', async ({ page }) => {
        await page.evaluate(() => localStorage.removeItem('am7.video.loop'));
        await page.evaluate((oid) => { window.location.hash = '!/list/data.data/' + oid; }, videoDir.objectId);
        await page.locator('.tabular-results-table').waitFor({ state: 'visible', timeout: 20000 });
        await selectRowAndOpen(page, 'gallery-loop-' + stamp);

        let video = fullViewer(page).locator('video');
        await expect(video).toHaveCount(1, { timeout: 10000 });
        expect(await video.evaluate(v => v.loop)).toBe(false);

        let loopBtn = fullViewer(page).locator('button[title^="Loop"]');
        await expect(loopBtn).toHaveAttribute('title', 'Loop: off');
        await loopBtn.click();
        await expect(loopBtn).toHaveAttribute('title', 'Loop: on');
        expect(await video.evaluate(v => v.loop)).toBe(true);
        expect(await page.evaluate(() => localStorage.getItem('am7.video.loop'))).toBe('true');

        // Grid preview pane honours the same preference and can turn it back off.
        await page.keyboard.press('Escape');
        await expect(fullViewer(page)).toHaveCount(0, { timeout: 5000 });
        let gpVideo = page.locator('.grid-preview-container video');
        await expect(gpVideo).toHaveCount(1, { timeout: 10000 });
        expect(await gpVideo.evaluate(v => v.loop)).toBe(true);
        let gpLoop = page.locator('.grid-preview-container button[title^="Loop"]');
        await gpLoop.click();
        await expect(gpLoop).toHaveAttribute('title', 'Loop: off');
        expect(await gpVideo.evaluate(v => v.loop)).toBe(false);
        expect(await page.evaluate(() => localStorage.getItem('am7.video.loop'))).toBe('false');
    });
});
