/**
 * KI-8 — list body overflow/scroll at a constrained viewport height (e.g. devtools console open).
 *
 * Investigation (like the KI-18 "investigate first" pattern): reproduced this at a range of short
 * viewport heights (150-400px) against the live dev server, in both table and gallery/grid list
 * modes, using direct DOM measurement (scrollHeight/clientHeight), a programmatic scrollTop set, and
 * a real mouse-wheel scroll. In every case above ~180px of available body height, the list body
 * (`.list-results > div.flex-1`, `views/list.js` ~line 1004) DOES scroll correctly — its
 * `flex-1 min-h-0 overflow-auto` classes are present in both the dev build and the production
 * Tailwind build (`dist/assets/*.css` contains `.min-h-0{min-height:0px}` and
 * `.overflow-auto{overflow:auto}`, ruling out the "Tailwind class purged from the build" hypothesis
 * the tracker floated), and a row scrolled below the fold is reachable both programmatically and via
 * a real wheel event.
 *
 * git history shows `overflow-auto` was added to this exact div in commit bd5806af ("Patch",
 * 2026-06-26) — one day after KI-8 was filed (2026-06-25) — which most likely already fixed the
 * reported symptom; the tracker entry was just never updated to reflect it. No further layout change
 * was made here since the bug does not reproduce on current `main` and this is a shared, sensitive
 * layout chain (KI-13's cross-view-consistency umbrella flags exactly this class of file for
 * "don't change without a reproduced bug").
 *
 * This test is therefore a REGRESSION GUARD (proving current-good behavior), not a bug-fix
 * confirmation — logged explicitly as such per the "investigate first, don't assume a fix is needed"
 * instruction. Uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, ensurePath, createNote, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';

test.describe('List body scroll at constrained viewport height (KI-8)', () => {
    let testInfo = {};
    let notesGroup = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);

        // Seed enough notes that the list body genuinely overflows a short viewport. The shared user
        // is persistent (fixtures accumulate across runs, same pattern as ensureIso42001TestUser's role
        // assignments) so this is idempotent-ish: createNote() no-ops if a note with that name exists.
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            notesGroup = await ensurePath(ctx, 'auth.group', 'data', '~/KI8OverflowNotes');
            expect(notesGroup && notesGroup.objectId, 'KI8OverflowNotes group').toBeTruthy();
            for (let i = 0; i < 20; i++) {
                await createNote(ctx, '~/KI8OverflowNotes', 'KI8Note' + i, 'KI-8 overflow scroll fixture');
            }
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('table view: list body scrolls (not clipped) at a short viewport height', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve KI8OverflowNotes group');
        await page.setViewportSize({ width: 1280, height: 400 });

        await page.goto('/#!/list/data.note/' + notesGroup.objectId);
        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        let rows = table.locator('tbody .tabular-row');
        await expect(async () => {
            expect(await rows.count()).toBeGreaterThan(5);
        }).toPass({ timeout: 15000 });

        // The scrollable body wrapper must actually overflow (proves it isn't clipped/hidden) and be
        // reachable via a real wheel scroll (not just a programmatic scrollTop set).
        let scrollBox = page.locator('.list-results > div.flex-1');
        await expect(scrollBox).toBeVisible();

        let before = await scrollBox.evaluate(el => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, scrollTop: el.scrollTop }));
        expect(before.scrollHeight, 'content taller than the visible pane (genuine overflow)').toBeGreaterThan(before.clientHeight);
        expect(before.scrollTop).toBe(0);

        let box = await scrollBox.boundingBox();
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        await page.mouse.wheel(0, 400);
        await page.waitForTimeout(400);

        let after = await scrollBox.evaluate(el => el.scrollTop);
        expect(after, 'wheel scroll actually moved the list body (not clipped/stuck)').toBeGreaterThan(0);

        // The last row must be reachable within the (now-scrolled) viewport bounds.
        let lastRow = rows.last();
        let lastBox = await lastRow.boundingBox();
        let scrollBoxBox = await scrollBox.boundingBox();
        expect(lastBox.y + lastBox.height).toBeLessThanOrEqual(scrollBoxBox.y + scrollBoxBox.height + 2);
    });

    test('gallery/grid view: preview body scrolls at a short viewport height', async ({ page }) => {
        test.skip(!notesGroup, 'Could not resolve KI8OverflowNotes group');
        await page.setViewportSize({ width: 900, height: 400 });

        await page.goto('/#!/list/data.note/' + notesGroup.objectId);
        await page.locator('.tabular-results-table').waitFor({ state: 'visible', timeout: 20000 });

        let gridBtn = page.locator('button:has(span:text("apps")), button:has(span:text("grid_view"))').first();
        await expect(gridBtn).toBeVisible({ timeout: 5000 });
        await gridBtn.click();

        let scrollBox = page.locator('.grid-preview-container .flex-1.overflow-y-auto');
        await expect(scrollBox).toBeVisible({ timeout: 15000 });

        await expect(async () => {
            let dims = await scrollBox.evaluate(el => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
            expect(dims.scrollHeight).toBeGreaterThan(dims.clientHeight);
        }).toPass({ timeout: 15000 });
    });
});
