/**
 * Infinite scroll test — verifies that icon/grid view uses infinite scroll
 * pagination, and that arrow-key navigation works across page boundaries.
 *
 * Creates 500 notes in the test user's ~/Notes directory, opens the list
 * in icon view, and verifies:
 *   1. Scrolling/auto-load loads more items (infinite scroll)
 *   2. Arrow-right navigates through items, loading more pages as needed
 *   3. Multiple pages load via repeated scrolling
 *   4. Page buttons hidden in grid mode
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUserFull } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const NOTE_COUNT = 500;

function b64(str) { return Buffer.from(str).toString('base64'); }

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

async function loginCtx(ctx, opts = {}) {
    return ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: opts.org || '/Development',
            name: opts.user || 'admin',
            credential: b64(opts.password || 'password'),
            type: 'hashed_password'
        }
    });
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

// Helper: navigate to notes list and enter grid view, wait for thumbnails
async function navigateToGridView(page, notesGroupOid) {
    await page.evaluate((oid) => { window.location.hash = '#!/list/data.note/' + oid; }, notesGroupOid);
    await page.waitForTimeout(3000);

    let toolbar = page.locator('.result-nav-outer');
    await expect(toolbar.first()).toBeVisible({ timeout: 15000 });

    // Click grid toggle button (icon: 'apps')
    await page.locator('.result-nav button:has(span.material-symbols-outlined:text("apps"))').first().click();

    // Wait for thumbnails to load (async pagination + auto-load may fire)
    await page.waitForFunction(
        () => document.querySelectorAll('[data-grid-thumb]').length > 0,
        { timeout: 20000 }
    );
}

test.describe('Infinite scroll — icon view with 500 items', () => {
    let testInfo = {};
    let notesGroupOid = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await setupTestUser(request, {
            suffix: 'inf' + Date.now().toString(36),
            noteCount: 0
        });

        // Bulk-create 500 notes as the test user
        let ctx = await newApiContext();
        try {
            await loginCtx(ctx, {
                user: testInfo.testUserName,
                password: testInfo.testPassword
            });

            let resp = await ctx.get(REST + '/path/make/auth.group/data/' + encodePath('~/Notes'));
            let notesDir = await safeJson(resp);
            if (notesDir) notesGroupOid = notesDir.objectId;

            for (let batch = 0; batch < NOTE_COUNT; batch += 50) {
                let promises = [];
                let end = Math.min(batch + 50, NOTE_COUNT);
                for (let i = batch; i < end; i++) {
                    let padded = String(i).padStart(4, '0');
                    promises.push(
                        ctx.post(REST + '/model', {
                            data: {
                                schema: 'data.note',
                                groupId: notesDir.id,
                                groupPath: notesDir.path,
                                name: 'InfNote_' + padded,
                                text: 'Infinite scroll test note ' + padded
                            }
                        })
                    );
                }
                await Promise.all(promises);
            }

            await ctx.get(REST + '/logout');
        } finally {
            await ctx.dispose();
        }
    }, 120000);

    test.afterAll(async ({ request }) => {
        // Cleanup can take a while with 500 items — give it plenty of time
        await cleanupTestUserFull(request, testInfo.user?.objectId, {
            userName: testInfo.testUserName
        }).catch(() => {});
    }, 300000);

    test.beforeEach(async ({ page }) => {
        await login(page, {
            user: testInfo.testUserName,
            password: testInfo.testPassword
        });
    });

    test('icon view loads more items via infinite scroll', async ({ page }) => {
        await navigateToGridView(page, notesGroupOid);

        let thumbs = page.locator('[data-grid-thumb]');
        let initialCount = await thumbs.count();
        expect(initialCount).toBeGreaterThan(0);

        // Auto-load or scroll should have loaded extra items
        // (the onupdate handler auto-loads when content fits on screen)
        // If initial auto-load already happened, count may be > 40
        if (initialCount <= 40) {
            // Trigger scroll to load more
            let scrollArea = page.locator('.grid-preview-container .overflow-y-auto');
            await scrollArea.evaluate(el => { el.scrollTop = el.scrollHeight; });
            await page.waitForFunction(
                (initial) => document.querySelectorAll('[data-grid-thumb]').length > initial,
                initialCount,
                { timeout: 15000 }
            );
        }

        let afterCount = await thumbs.count();
        expect(afterCount).toBeGreaterThan(40);

        await screenshot(page, 'infinite-scroll-loaded');
    });

    test('arrow-right navigates across page boundaries', async ({ page }) => {
        await navigateToGridView(page, notesGroupOid);

        let gridContainer = page.locator('.grid-preview-container');
        await gridContainer.focus();

        // Navigate past 45 items (page size = 40)
        for (let i = 0; i < 45; i++) {
            await page.keyboard.press('ArrowRight');
            await page.waitForTimeout(30);
        }

        // Should have loaded beyond first page
        await page.waitForFunction(
            () => document.querySelectorAll('[data-grid-thumb]').length > 40,
            { timeout: 15000 }
        );

        let thumbCount = await page.locator('[data-grid-thumb]').count();
        expect(thumbCount).toBeGreaterThan(40);

        await screenshot(page, 'infinite-scroll-arrow-nav');
    });

    test('scroll down repeatedly loads multiple pages', async ({ page }) => {
        await navigateToGridView(page, notesGroupOid);

        let scrollArea = page.locator('.grid-preview-container .overflow-y-auto');
        await expect(scrollArea).toBeVisible({ timeout: 5000 });

        let lastCount = 0;
        let pagesLoaded = 0;
        for (let attempt = 0; attempt < 20; attempt++) {
            await scrollArea.evaluate(el => { el.scrollTop = el.scrollHeight; });
            await page.waitForTimeout(800);

            let currentCount = await page.locator('[data-grid-thumb]').count();
            if (currentCount > lastCount) {
                pagesLoaded++;
                lastCount = currentCount;
            }

            if (currentCount >= 120) break;
        }

        expect(pagesLoaded).toBeGreaterThanOrEqual(2);
        expect(lastCount).toBeGreaterThanOrEqual(80);

        await screenshot(page, 'infinite-scroll-multi-page');
    });

    test('page buttons hidden in grid mode', async ({ page }) => {
        await page.evaluate((oid) => { window.location.hash = '#!/list/data.note/' + oid; }, notesGroupOid);
        await page.waitForTimeout(3000);

        let toolbar = page.locator('.result-nav-outer');
        await expect(toolbar.first()).toBeVisible({ timeout: 15000 });

        // In table mode, page nav should be visible
        let pageNav = page.locator('.result-nav-inner nav.result-nav');
        await expect(pageNav).toBeVisible({ timeout: 5000 });

        // Enter grid view
        await page.locator('.result-nav button:has(span.material-symbols-outlined:text("apps"))').first().click();

        await page.waitForFunction(
            () => document.querySelectorAll('[data-grid-thumb]').length > 0,
            { timeout: 20000 }
        );

        // In grid mode, page nav should NOT be visible
        let pageNavInGrid = page.locator('.result-nav-inner nav.result-nav');
        await expect(pageNavInGrid).toHaveCount(0, { timeout: 5000 });
    });
});
