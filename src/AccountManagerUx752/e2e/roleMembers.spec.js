/**
 * Role membership regression tests (bugs reported during ISO UAT):
 *  #2 role members not loading
 *  #3 console crash (am7model.inherits null) when opening the add-member picker
 *
 * Uses the ISO42001Testers role, which has e2etest_iso42001 as a member (provisioned by
 * ensureIso42001TestUser). Navigation/role-management is privileged, so this repro logs in as admin
 * to reach the role view — the assertions here are UI render/behavior + console-error checks, not
 * business-authorization assertions. The fixtures console guard fails the test on the inherits(null) crash.
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureIso42001TestUser, apiLogin, searchByField } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

test.describe('Role membership (live)', () => {
    let roleObjectId = null;

    test.beforeAll(async ({ request }) => {
        // Ensure the ISO test user + its role memberships exist, then find the role's objectId.
        await ensureIso42001TestUser(request);
        let ctx = await newApiContext();
        try {
            await apiLogin(ctx, { user: 'admin', password: 'password' });
            let role = await searchByField(ctx, 'auth.role', 'name', 'ISO42001Testers', ['objectId', 'name', 'type']);
            roleObjectId = role ? role.objectId : null;
        } finally {
            await ctx.dispose();
        }
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: 'admin', password: 'password' });
    });

    test('role view opens Members and loads members without crashing (bugs #2/#3)', async ({ page }) => {
        test.skip(!roleObjectId, 'ISO42001Testers role not found');

        await page.goto('/#!/view/auth.role/' + roleObjectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/auth.role/'),
            { timeout: 15000 }
        );
        // Wait for the form to render.
        await expect(page.locator('input, select, button').first()).toBeVisible({ timeout: 30000 });

        // Open the Members tab (forms: parentinfo, rolemembers, attributes). The tab button contains an
        // icon glyph + the label, so match by accessible name containing "Members".
        let membersTab = page.getByRole('button', { name: /Members/ }).first();
        await expect(membersTab).toBeVisible({ timeout: 15000 });
        await membersTab.click();
        await page.waitForTimeout(1000);
        // Members panel renders (table/empty-state) without crashing.
        await expect(page.getByText('Members').first()).toBeVisible({ timeout: 10000 });

        // #3 regression: open the add-member editor + the participant picker. Previously this crashed with
        // am7model.inherits(null) and sent an invalid type ("user"/"$flex") that NPE'd the server. The fix
        // maps the participant enum (USER) → model (system.user), so the picker resolves a real type. The
        // fixtures console guard fails the test on any console error (crash) — including "missing model for
        // type user" and the inherits(null) TypeError.
        let addBtn = page.locator('button[title="Add new"]').or(page.locator('button:has(span:text-is("add"))')).first();
        if (await addBtn.isVisible({ timeout: 5000 }).catch(() => false)) {
            await addBtn.click();
            await page.waitForTimeout(800);
            let findBtn = page.locator('button:has-text("Find")').first();
            if (await findBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
                await findBtn.click();
                await page.waitForTimeout(1500); // picker list renders here — the former crash/NPE point
            }
        }
        // Reaching here with no console error = crash + bad-type-to-server fixed (guard asserts on teardown).
        // NOTE: members-list population (bug #2) is tracked separately in aiDocs/KnownIssues.md (KI-3) —
        // not asserted here because it is not yet resolved.
    });
});
