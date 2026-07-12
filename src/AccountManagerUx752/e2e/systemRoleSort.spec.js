/**
 * KI-7 (remaining OPEN half) — system-role list sort has no effect.
 *
 * When pages.listSystem is true for auth.role/auth.permission, pagination.js served the list from a
 * pre-loaded client-side array (page.application.systemRoles.slice(...)) instead of the search API, so
 * clicking the sort header did nothing to it — the slice was never re-sorted by pages.sort/order.
 * Fixed in components/pagination.js (updatePage()'s pages.listSystem branch) by sorting the array
 * client-side before slicing.
 *
 * Uses ensureSharedTestUser() (never admin) for the actual UI-driven test; an admin API context is used
 * only for the one-time RoleReaders role-assignment provisioning step (mirrors ensureIso42001TestUser's
 * existing pattern), same as every other role-membership e2e test in this suite.
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, addUserToRole } from './helpers/api.js';

async function getColumnTexts(table, columnLabel) {
    return await table.evaluate((tableEl, label) => {
        let headers = Array.from(tableEl.querySelectorAll('thead th'));
        let idx = headers.findIndex(h => h.textContent.trim().toLowerCase() === label);
        if (idx < 0) return [];
        let rows = Array.from(tableEl.querySelectorAll('tbody tr'));
        return rows.map(r => {
            let cells = r.querySelectorAll('td');
            return cells[idx] ? cells[idx].textContent.trim() : '';
        });
    }, columnLabel);
}

test.describe('System role list — sort (KI-7)', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        expect(testInfo.user && testInfo.user.objectId, 'shared test user objectId').toBeTruthy();
        let assigned = await addUserToRole(request, testInfo.user.objectId, 'RoleReaders');
        expect(assigned, 'RoleReaders role assignment').toBe(true);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('sorting the system role list actually re-sorts the slice (KI-7)', async ({ page }) => {
        // Wide recordCount so the entire system-role slice fits on one page — otherwise a page boundary
        // would make "descending order != reverse of ascending order" for reasons unrelated to the bug.
        await page.goto('/#!/list/auth.role?startRecord=0&recordCount=200');
        await page.waitForFunction(() => window.location.hash.includes('/list/auth.role'), { timeout: 15000 });

        // Enable the system-role listing (admin_panel_settings toggle, gated on RoleReaders for auth.role).
        let systemBtn = page.locator('button:has(span:text("admin_panel_settings"))').first();
        await expect(systemBtn).toBeVisible({ timeout: 15000 });
        await systemBtn.click();

        let table = page.locator('.tabular-results-table');
        await expect(table).toBeVisible({ timeout: 15000 });

        let rows = table.locator('tbody tr');
        await expect(async () => {
            expect(await rows.count()).toBeGreaterThan(1);
        }).toPass({ timeout: 15000 });

        // Default sort is name/ascending — capture it.
        let ascNames = await getColumnTexts(table, 'name');
        expect(ascNames.length).toBeGreaterThan(1);

        // Click the name column's sort icon — pages.sort already == "name", so this flips to descending.
        let nameHeader = table.locator('thead th:has-text("name")').first();
        await expect(nameHeader).toBeVisible({ timeout: 5000 });
        let sortIcon = nameHeader.locator('.material-symbols-outlined').first();
        await sortIcon.click();
        await page.waitForTimeout(1500);

        // Indicator should now show descending.
        let sortIconText = (await nameHeader.locator('.material-symbols-outlined').first().textContent()).trim();
        expect(sortIconText).toBe('arrow_downward');

        let descNames = await getColumnTexts(table, 'name');
        expect(descNames.length).toBe(ascNames.length);

        // THE REGRESSION: before the fix, the slice was never re-sorted, so descNames === ascNames
        // even though the order indicator flipped. Assert the actual rendered order changed...
        expect(descNames).not.toEqual(ascNames);

        // ...and that it is genuinely sorted descending (not just "different", but correctly ordered).
        for (let i = 0; i < descNames.length - 1; i++) {
            expect(descNames[i].localeCompare(descNames[i + 1])).toBeGreaterThanOrEqual(0);
        }
        // Sanity: the ascending capture was genuinely sorted ascending too.
        for (let i = 0; i < ascNames.length - 1; i++) {
            expect(ascNames[i].localeCompare(ascNames[i + 1])).toBeLessThanOrEqual(0);
        }
    });
});
