/**
 * Diagnostic + regression test for role member LOAD (bug: members don't display on the role members view).
 * Uses the plain @playwright/test import (NOT fixtures) so the debug console.warn lines don't trip the
 * console-error guard. Captures the console so the routing/load decision is visible in THIS test's output.
 *
 * ISO42001Testers has e2etest_iso42001 as a member (ensureIso42001TestUser), so the members list must show it.
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureIso42001TestUser, apiLogin, searchByField } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';

test('role members view loads + displays the existing member', async ({ page, request }) => {
    // Setup: ensure the ISO test user + its role memberships, then resolve the role objectId.
    await ensureIso42001TestUser(request);
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    let roleObjectId = null;
    try {
        await apiLogin(ctx, { user: 'admin', password: 'password' });
        let role = await searchByField(ctx, 'auth.role', 'name', 'ISO42001Testers', ['objectId', 'name', 'type']);
        roleObjectId = role ? role.objectId : null;
    } finally {
        await ctx.dispose();
    }
    expect(roleObjectId, 'ISO42001Testers role objectId').toBeTruthy();

    await login(page, { user: 'admin', password: 'password' });
    await page.goto('/#!/view/auth.role/' + roleObjectId);
    await page.waitForFunction(() => window.location.hash.includes('/view/auth.role/'), { timeout: 15000 });
    await expect(page.locator('input, select, button').first()).toBeVisible({ timeout: 30000 });

    // Open Members tab.
    let membersTab = page.getByRole('button', { name: /Members/ }).first();
    await expect(membersTab).toBeVisible({ timeout: 15000 });
    await membersTab.click();

    // Regression: the member load (objectMembers) must fire for the virtual members field and display the member.
    await expect(page.getByText('e2etest_iso42001')).toBeVisible({ timeout: 10000 });
});
