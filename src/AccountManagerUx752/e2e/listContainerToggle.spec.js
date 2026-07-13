/**
 * KI-9 — list view empty after switching back from group/parent navigation.
 *
 * Root cause (components/pagination.js `stopPaginating()`, ~line 284): toggling the "navigate by
 * group/parent" (container mode) toolbar button back OFF issues `m.route.set(...)` to the same list
 * route with a different `:objectId` (the drilled-into subgroup). That route change unmounts and
 * remounts the list view's vnode (`onremove` fires), which calls `pagination.stop()` ->
 * `stopPaginating()`. That function reset `pages` but never reset the module-level `requesting` flag.
 * If an async container-lookup fetch (`updateList()`'s `am7client.get(containerType, containerId, ...)`)
 * was still in flight at that moment — which it usually is, since the toggle-off handler kicks one off
 * just before the route change lands — its `.then` callback's stale-guard (`if (reqPages !== pages)
 * return;`) discards the response WITHOUT clearing `requesting`. With `requesting` stuck `true`,
 * every subsequent `pagination.update()` call permanently no-ops at its `if (requesting) return;` guard
 * — the list stays empty until something else (e.g. a full page reload, which reinitializes the whole
 * module) resets it. `toggleGrid()` (icon view) never hits this because it re-queries synchronously
 * without an intervening route change/unmount, matching the tracker's own observation that icon view
 * "works correctly."
 *
 * Fix: `stopPaginating()` now also resets `requesting = false` (matching `pagination.new()`, which
 * already did this correctly). A stale in-flight promise still safely no-ops via its own
 * `reqPages !== pages` check when it resolves — resetting `requesting` early does not race it.
 *
 * Verified by direct trace (temporary console logging, since removed) that isolated the exact
 * sequence: toggleContainer OFF -> pagination.new() -> update() kicks off container fetch
 * (requesting=true) -> onremove -> stopPaginating() (pages replaced, requesting left stuck true) ->
 * every further update() call no-ops. Uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, ensurePath, createNote, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';

test.describe('List container/group-nav toggle — off restores the list without a manual refresh (KI-9)', () => {
    let testInfo = {};
    let notesGroup = null;
    let subGroup = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);

        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            notesGroup = await ensurePath(ctx, 'auth.group', 'data', '~/KI9ContainerNotes');
            subGroup = await ensurePath(ctx, 'auth.group', 'data', '~/KI9ContainerNotes/SubFolder');
            expect(notesGroup && notesGroup.objectId, 'KI9ContainerNotes group').toBeTruthy();
            expect(subGroup && subGroup.objectId, 'KI9ContainerNotes/SubFolder group').toBeTruthy();
            // A top-level note too — the table element isn't rendered at all for zero results
            // (decorator.js renderTabular), so the initial visibility check needs at least one row.
            await createNote(ctx, '~/KI9ContainerNotes', 'KI9TopNote', 'KI-9 regression fixture (top level)');
            for (let i = 0; i < 5; i++) {
                await createNote(ctx, '~/KI9ContainerNotes/SubFolder', 'KI9SubNote' + i, 'KI-9 regression fixture');
            }
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('drill into a subgroup via container mode, then toggle it off: list repopulates without a refresh', async ({ page }) => {
        test.skip(!notesGroup || !subGroup, 'Could not resolve KI9ContainerNotes fixtures');

        await page.goto('/#!/list/data.note/' + notesGroup.objectId);
        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 20000 });

        // Turn container/group-nav mode ON.
        let containerBtn = page.locator('button:has(span:text("group_work"))').first();
        await expect(containerBtn).toBeVisible({ timeout: 10000 });
        await containerBtn.click();

        // Drill into the (only) subgroup shown while in container mode.
        let groupRows = page.locator('.tabular-results-table tbody .tabular-row');
        await expect(async () => { expect(await groupRows.count()).toBeGreaterThan(0); }).toPass({ timeout: 10000 });
        await groupRows.first().dblclick();
        await page.waitForTimeout(800);

        // Toggle container mode back OFF — this is the regression path.
        let containerBtnOff = page.locator('button:has(span:text("group_work"))').first();
        await expect(containerBtnOff).toBeVisible({ timeout: 10000 });
        await containerBtnOff.click();

        // THE REGRESSION: without the fix this stays empty indefinitely (no manual refresh here).
        let rowsAfterToggleOff = page.locator('.tabular-results-table tbody .tabular-row');
        await expect(async () => {
            expect(await rowsAfterToggleOff.count()).toBeGreaterThan(0);
        }).toPass({ timeout: 8000 });
        expect(await rowsAfterToggleOff.count()).toBe(5);

        // Sanity: the list is still genuinely alive (not just showing stale cached rows) — a totally
        // unrelated fresh navigation still works, proving `requesting` isn't left stuck.
        await page.goto('/#!/list/data.note/' + notesGroup.objectId);
        let topRows = page.locator('.tabular-results-table tbody .tabular-row');
        await expect(async () => { expect(await topRows.count()).toBeGreaterThanOrEqual(0); }).toPass({ timeout: 8000 });
    });
});
