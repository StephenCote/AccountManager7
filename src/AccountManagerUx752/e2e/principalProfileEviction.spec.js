/**
 * KI-77 — the Service7 application-profile cache must be evicted on a role membership change.
 *
 * PrincipalService.getApplicationProfile memoizes the whole profile (including the user's direct role
 * participations) per URN in a static map. Until 2026-10-07 nothing ever removed an entry, so a role
 * granted or revoked after a user's first profile fetch stayed invisible to the UI's role gating until
 * Tomcat restarted — and GET /rest/cache/clearAll did not help because it never reached that map.
 *
 * Live, API-only, against the Docker stack. The subject is a spec-private non-admin user; the admin
 * context is used only to provision the grant/revoke, exactly as the shared helpers do. No clearAll is
 * called anywhere in the positive path — that is the point.
 */
import { request as pwRequest } from '@playwright/test';
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, addUserToRole, removeUserFromRole, apiLogin, apiLogout } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const ROLE = 'RoleReaders';
const SUBJECT = 'e2etest_profilecache';

async function userRoleNames(u) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let login = await apiLogin(ctx, { user: u.testUserName, password: u.testPassword });
        expect(login.status(), 'login as ' + u.testUserName).toBe(200);
        let resp = await ctx.get(REST + '/principal/application');
        expect(resp.status(), 'GET /principal/application').toBe(200);
        let app = await resp.json();
        expect(app && app.organizationPath, 'profile organizationPath').toBe('/Development');
        await apiLogout(ctx);
        return (app.userRoles || []).map(r => r.name);
    } finally {
        await ctx.dispose();
    }
}

test.describe('Application profile cache eviction on role change (KI-77)', () => {
    let subject;

    test.beforeAll(async ({ request }) => {
        subject = await ensureSharedTestUser(request, { name: SUBJECT });
        expect(subject.user && subject.user.objectId, 'subject user').toBeTruthy();
        expect(await removeUserFromRole(request, subject.user.objectId, ROLE), 'baseline revoke').toBe(true);
    });

    test('a role granted after the first profile fetch is visible on the next fetch without clearAll', async ({ request }) => {
        // Prime the cache as the subject.
        let before = await userRoleNames(subject);
        expect(before, 'baseline must not already carry ' + ROLE).not.toContain(ROLE);

        expect(await addUserToRole(request, subject.user.objectId, ROLE), 'grant ' + ROLE).toBe(true);
        let afterGrant = await userRoleNames(subject);
        expect(afterGrant, 'profile must reflect the grant immediately (cache evicted by the member route)').toContain(ROLE);

        expect(await removeUserFromRole(request, subject.user.objectId, ROLE), 'revoke ' + ROLE).toBe(true);
        let afterRevoke = await userRoleNames(subject);
        expect(afterRevoke, 'profile must reflect the revoke immediately').not.toContain(ROLE);
    });

    test.afterAll(async ({ request }) => {
        if (subject && subject.user && subject.user.objectId) {
            await removeUserFromRole(request, subject.user.objectId, ROLE);
        }
    });
});
