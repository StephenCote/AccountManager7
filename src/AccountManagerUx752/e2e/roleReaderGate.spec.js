/**
 * KI-4 — "View system roles" toggle is gated on RoleReaders (not Admin).
 *
 * views/list.js getAdminButtons() shows the `admin_panel_settings` system-list toggle on /list/auth.role
 * for `page.context().roles.roleReader` (router.js setContextRoles: hasRole(userRoles,'rolereaders')),
 * and on /list/auth.permission for permissionReader; AccountAdministrators get it everywhere.
 *
 * Fresh users are auto-enrolled in AccountUsers only, so the RoleReaders-only state has to be built
 * explicitly: a persistent non-admin user `e2etest_rolereader` is added to RoleReaders by the admin
 * helper (addUserToRole), then the UI runs as that user. The negative case runs as a second persistent
 * non-admin user `e2etest_plainuser` (AccountUsers only) that no other spec or lane script grants roles
 * to; both cases assert the live role context in the browser rather than assuming it.
 *
 * Why not the shared user for the negative case (2026-10-07): Service7 `PrincipalService.profiles`
 * (PrincipalService.java:59) caches the whole application profile per user URN in a static map that
 * nothing clears — `GET /rest/cache/clearAll` (CacheService.clearCaches) does not call
 * `PrincipalService.clearCache()`, and neither does the membership endpoint. So `userRoles` is pinned
 * from the first profile fetch after a Tomcat start until the next restart; a later role grant OR
 * removal is invisible to the UI. The shared e2e user is granted RoleReaders by other lanes'
 * scripts (e2e/_core_ws_tmp.mjs), so removing it here cannot be observed and the precondition fails.
 * A user only this spec touches makes the precondition real. The cache itself is a backend defect
 * (KnownIssues.md KI-4 note) — out of scope for this spec.
 *
 * Both UI sessions are non-admin. Admin is used only for provisioning (api.js helpers).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, addUserToRole, removeUserFromRole, apiLogin, apiLogout, ensurePath } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

const SYSTEM_TOGGLE = 'button:has(span.material-symbols-outlined:text-is("admin_panel_settings"))';

async function clearServerCaches() {
    // Drops the participation/search caches so the FIRST application-profile fetch for a user sees a
    // just-granted membership. It does NOT drop an already-cached profile (see header). Admin-only
    // provisioning step.
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        await apiLogin(ctx, { user: 'admin', password: 'password' });
        await ctx.get(REST + '/cache/clearAll');
        await apiLogout(ctx);
    } finally {
        await ctx.dispose();
    }
}

async function ctxRoles(page) {
    return page.evaluate(() => (window.am7dbg && window.am7dbg.roles) ? window.am7dbg.roles() : null);
}

async function gotoList(page, type) {
    await page.goto('/#!/list/' + type);
    await page.waitForFunction((t) => window.location.hash.includes('/list/' + t), type, { timeout: 15000 });
    // The list toolbar (add button) is rendered once the list control is up.
    await expect(page.locator('button:has(span.material-symbols-outlined:text-is("add"))').first()).toBeVisible({ timeout: 30000 });
}

test.describe('System roles toggle gating — RoleReaders vs plain user (KI-4)', () => {
    let reader = {};
    let plain = {};

    async function ensureFavorites(u) {
        // First-login artifact: pageClient.favorites() logs a console error for a user that has no
        // ~/Favorites bucket yet (it then creates it). Pre-create it as that user so the fixture's
        // console-error guard checks the gating, not first-login bootstrap. Non-admin.
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: u.testUserName, password: u.testPassword });
            let fav = await ensurePath(ctx, 'auth.group', 'bucket', '~/Favorites');
            expect(fav && fav.objectId, u.testUserName + ' ~/Favorites bucket').toBeTruthy();
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    }

    test.beforeAll(async ({ request }) => {
        reader = await ensureSharedTestUser(request, { name: 'e2etest_rolereader' });
        plain = await ensureSharedTestUser(request, { name: 'e2etest_plainuser' });
        expect(reader.user && reader.user.objectId, 'rolereader user').toBeTruthy();
        expect(plain.user && plain.user.objectId, 'plain user').toBeTruthy();
        expect(await addUserToRole(request, reader.user.objectId, 'RoleReaders'), 'RoleReaders grant').toBe(true);
        // Belt and braces for the negative precondition (a no-op unless something granted it).
        await removeUserFromRole(request, plain.user.objectId, 'RoleReaders');
        await clearServerCaches();
        await ensureFavorites(reader);
        await ensureFavorites(plain);
    });

    test('RoleReaders-only user sees the system-roles toggle on auth.role and can list the system roles', async ({ page }) => {
        await login(page, { user: reader.testUserName, password: reader.testPassword });
        let roles = await ctxRoles(page);
        expect(roles, 'am7dbg.roles()').toBeTruthy();
        expect(roles.roleReader, 'context roleReader').toBe(true);
        expect(roles.accountAdmin, 'must not be an account admin').toBe(false);
        expect(roles.admin, 'must not be admin').toBe(false);

        await gotoList(page, 'auth.role');
        let toggle = page.locator(SYSTEM_TOGGLE).first();
        await expect(toggle, 'system-roles toggle visible for RoleReaders').toBeVisible({ timeout: 15000 });
        await toggle.click();
        // System roles come from the application profile (pagination.js listSystem branch, sorted by
        // name and paginated). Page 1 of the org's well-known roles must now be listed; these two are
        // alphabetically first and are never user-creatable, so seeing them proves the system list.
        await expect(page.getByText('AccountAdministrators', { exact: true }).first()).toBeVisible({ timeout: 15000 });
        await expect(page.getByText('AccountUsers', { exact: true }).first()).toBeVisible({ timeout: 15000 });
        // The toggle is reflected in the live role context, not a stale admin flag.
        await expect(page.locator(SYSTEM_TOGGLE).first()).toBeVisible();

        // Not a PermissionReader: the toggle must NOT appear on auth.permission for this user.
        if (!roles.permissionReader) {
            await gotoList(page, 'auth.permission');
            await expect(page.locator(SYSTEM_TOGGLE)).toHaveCount(0);
        }
    });

    test('plain AccountUsers user (no RoleReaders) does not see the toggle on auth.role', async ({ page }) => {
        await login(page, { user: plain.testUserName, password: plain.testPassword });
        let roles = await ctxRoles(page);
        expect(roles, 'am7dbg.roles()').toBeTruthy();
        expect(roles.user, 'precondition: plain user is an AccountUser').toBe(true);
        expect(roles.roleReader, 'precondition: plain user is not a RoleReader').toBe(false);
        expect(roles.accountAdmin).toBe(false);

        await gotoList(page, 'auth.role');
        await expect(page.locator(SYSTEM_TOGGLE)).toHaveCount(0);
    });
});
