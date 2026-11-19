/**
 * Admin panel (/admin/system) — thumbnail cleanup and audit list/search/view/trim/purge over /rest/admin.
 *
 * Runs as the admin-ROLE test user (`e2etest_featadmin`, an AccountAdministrators member — never `admin`);
 * the authorization checks use the shared (non-admin) test user. An AccountAdministrators member is an
 * administrator but NOT a SystemAdministrators member, so the all-organizations scope must be refused to
 * it both in the UI (disabled radio) and by the server (403) — that refusal is the cross-tenant boundary
 * this spec pins.
 *
 * Destructive commands exercised here are confined to the test stack's /Development organization:
 * the thumbnail wipe (thumbnails regenerate on demand) and an audit TRIM to the last 3650 days (deletes
 * only records older than ten years - nothing on a fresh stack - but drives the confirm → DELETE → journal
 * → recount path end to end). Purge is deliberately NOT run here; it is covered by TestAdminService /
 * TestAdminMaintenanceUtil against a dedicated organization.
 *
 * Run against the Docker stack:  PLAYWRIGHT_BASE_URL=https://localhost:9443 npx playwright test e2e/systemAdmin.spec.js --workers=1 --project=chromium
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import {
    ensureAdminRoleTestUser, ensureSharedTestUser, apiLogin, getOrgFeatures, setOrgFeatures
} from './helpers/api.js';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://127.0.0.1:9443';
const REST = BASE + '/AccountManagerService7/rest';
const ADMIN = REST + '/admin';

let featAdmin, shared;
let featuresBefore = null;

async function openAdmin(page) {
    await page.goto('/#!/admin/system');
    await page.waitForFunction(() => window.location.hash.includes('/admin/system'), { timeout: 15000 });
    await expect(page.locator('h1', { hasText: 'Admin' })).toBeVisible({ timeout: 30000 });
    // The scope control appears once GET /rest/admin/scope has answered.
    await expect(page.locator('#systemAdminScopeOrg')).toBeVisible({ timeout: 30000 });
}

/** Click Search and wait for the POST /audit/search round trip plus the redraw it triggers. */
async function runSearch(page) {
    let answered = page.waitForResponse(r => r.url().includes('/rest/admin/audit/search'), { timeout: 60000 });
    await page.locator('#systemAdminAuditSearch').click();
    let resp = await answered;
    await page.waitForTimeout(500); // let Mithril redraw the table from the response
    return resp;
}

async function confirmDialog(page, label) {
    let dialog = page.locator('.am7-dialog');
    await expect(dialog).toBeVisible({ timeout: 10000 });
    await dialog.locator('button', { hasText: label }).click();
}

test.describe('Admin panel — thumbnails and audit', () => {

    test.beforeAll(async ({ request }) => {
        featAdmin = await ensureAdminRoleTestUser(request);
        shared = await ensureSharedTestUser(request);
        expect(featAdmin.roleAssigned, 'the AccountAdministrators role could not be assigned').toBe(true);

        // The systemAdmin feature is optional; make sure the org has it enabled for the UI tests.
        let seen = await getOrgFeatures(request, { userName: featAdmin.testUserName, password: featAdmin.testPassword });
        if (Array.isArray(seen.features) && !seen.features.includes('systemAdmin')) {
            featuresBefore = seen.features;
            let stored = await setOrgFeatures(request, featuresBefore.concat(['systemAdmin']));
            expect(stored, 'could not enable the systemAdmin feature').toContain('systemAdmin');
        }
    });

    test.afterAll(async ({ request }) => {
        if (featuresBefore) {
            await setOrgFeatures(request, featuresBefore);
        }
    });

    test('panel renders for the admin-role user; all-organizations scope is withheld from a non-system administrator', async ({ page }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await openAdmin(page);

        await expect(page.locator('label', { hasText: 'This organization (/Development)' })).toBeVisible();
        await expect(page.locator('#systemAdminScopeOrg')).toBeChecked();
        // AccountAdministrators ≠ SystemAdministrators: the all-orgs option is present but disabled.
        await expect(page.locator('#systemAdminScopeAll')).toBeDisabled();

        await page.locator('#systemAdminThumbCount').click();
        await expect(page.locator('#systemAdminThumbTotal')).toContainText(/\d+ thumbnails? in \/Development/, { timeout: 30000 });

        await page.locator('#systemAdminAuditCount').click();
        await expect(page.locator('#systemAdminAuditTotal')).toContainText(/\d+ audit records? in \/Development/, { timeout: 30000 });
        await screenshot(page, 'system-admin-panel');
    });

    test('a non-admin user sees the role notice, not the commands', async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await page.goto('/#!/admin/system');
        await page.waitForFunction(() => window.location.hash.includes('/admin/system'), { timeout: 15000 });
        await expect(page.getByText('Admin requires an administrator role.')).toBeVisible({ timeout: 30000 });
        await expect(page.locator('#systemAdminThumbWipe')).toHaveCount(0);
    });

    test('audit search lists records newest first, filters by response, and opens a detail dialog', async ({ page }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await openAdmin(page);

        let first = await runSearch(page);
        expect(first.status()).toBe(200);
        let rows = page.locator('#systemAdminAuditTable tbody tr');
        await expect(rows.first()).toBeVisible({ timeout: 60000 });
        await expect(page.locator('#systemAdminAuditRange')).toContainText(/Showing 1–\d+ of \d+/);
        // The login that opened this session was itself audited, so the newest row is recent.
        let count = await rows.count();
        expect(count).toBeGreaterThan(0);
        expect(count).toBeLessThanOrEqual(25);

        // Filter: PERMIT only - every chip in the page must read PERMIT.
        await page.locator('#systemAdminAuditResponse').selectOption('PERMIT');
        await runSearch(page);
        await expect(rows.first()).toBeVisible({ timeout: 60000 });
        let chips = await rows.locator('td:nth-child(2) span').allTextContents();
        expect(chips.length).toBeGreaterThan(0);
        for (let c of chips) expect(c.trim()).toBe('PERMIT');

        // Detail dialog for the first row.
        let objectId = await rows.first().getAttribute('data-object-id');
        expect(objectId).toBeTruthy();
        await rows.first().click();
        let detail = page.locator('#systemAdminAuditDetail');
        await expect(detail).toBeVisible({ timeout: 30000 });
        await expect(detail).toContainText(objectId);
        await expect(detail).toContainText('Organization: /Development');
        await expect(detail).toContainText('Context user:');
        await screenshot(page, 'system-admin-audit-detail');
        await page.locator('.am7-dialog .am7-dialog-footer button', { hasText: 'Close' }).click();
        await expect(detail).toHaveCount(0);

        // A text filter that cannot match yields the empty state, not an error.
        await page.locator('#systemAdminAuditResponse').selectOption('');
        await page.locator('#systemAdminAuditText').fill('no-such-audit-text-' + Date.now());
        await runSearch(page);
        await expect(page.locator('#systemAdminAuditEmpty')).toBeVisible({ timeout: 60000 });
    });

    test('thumbnail wipe and audit trim run through confirm → DELETE → recount, and are journaled', async ({ page }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await openAdmin(page);

        // Wipe thumbnails (this org). Cancel first: nothing may be sent.
        let deletes = [];
        page.on('request', r => { if (r.method() === 'DELETE' && r.url().includes('/rest/admin/')) deletes.push(r.url()); });
        await page.locator('#systemAdminThumbWipe').click();
        await confirmDialog(page, 'Cancel');
        expect(deletes).toEqual([]);

        await page.locator('#systemAdminThumbWipe').click();
        await confirmDialog(page, 'Wipe thumbnails');
        await expect(page.getByText(/Wiped \d+ thumbnails?\./)).toBeVisible({ timeout: 60000 });
        expect(deletes.some(u => /\/rest\/admin\/thumbnails\?allOrganizations=false$/.test(u))).toBe(true);
        await expect(page.locator('#systemAdminThumbTotal')).toContainText('0 thumbnails in /Development');

        // Trim audit to the last 3650 days.
        await page.locator('#systemAdminAuditTrimDays').fill('3650');
        await page.locator('#systemAdminAuditTrim').click();
        await confirmDialog(page, 'Trim');
        await expect(page.getByText(/Trimmed \d+ audit records? older than 3650 days\./)).toBeVisible({ timeout: 60000 });
        expect(deletes.some(u => /\/rest\/admin\/audit\?allOrganizations=false&days=3650$/.test(u))).toBe(true);
        // Purge was never sent.
        expect(deletes.some(u => /purge=true/.test(u))).toBe(false);

        // Both commands were journaled as the caller. The server's BatchQueue flushes every 5 s, so
        // re-run the search until both entries have landed.
        await page.locator('#systemAdminAuditText').fill('Admin maintenance:');
        let rows = page.locator('#systemAdminAuditTable tbody tr');
        await expect.poll(async () => {
            await runSearch(page);
            let summaries = await rows.locator('td:nth-child(5)').allTextContents();
            return summaries.some(s => /Wiped \d+ thumbnail/.test(s))
                && summaries.some(s => /Trimmed \d+ audit record\(s\) older than 3650 day/.test(s));
        }, { timeout: 60000, intervals: [2000] }).toBe(true);
        await screenshot(page, 'system-admin-after-maintenance');
    });

    test('REST: non-admins get 401/403, account administrators are refused the all-organizations scope, and audit DELETE validates its flags', async ({ page, request }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });

        let scope = await page.request.get(ADMIN + '/scope');
        expect(scope.status()).toBe(200);
        let s = await scope.json();
        expect(s.organizationPath).toBe('/Development');
        expect(s.systemAdministrator).toBe(false);
        expect(s.organizations).toEqual([]);

        for (let url of [ADMIN + '/thumbnails/count?allOrganizations=true', ADMIN + '/audit/count?allOrganizations=true']) {
            let r = await page.request.get(url);
            expect(r.status(), url).toBe(403);
            expect((await r.json()).error).toContain('SystemAdministrators');
        }
        expect((await page.request.delete(ADMIN + '/thumbnails?allOrganizations=true')).status()).toBe(403);
        expect((await page.request.delete(ADMIN + '/audit?allOrganizations=true&purge=true')).status()).toBe(403);
        let allSearch = await page.request.post(ADMIN + '/audit/search', { data: { allOrganizations: true } });
        expect(allSearch.status()).toBe(403);

        let neither = await page.request.delete(ADMIN + '/audit');
        expect(neither.status()).toBe(400);
        expect((await neither.json()).error).toContain('days');
        expect((await page.request.delete(ADMIN + '/audit?days=30&purge=true')).status()).toBe(400);
        expect((await page.request.delete(ADMIN + '/audit?days=0')).status()).toBe(400);
        expect((await page.request.post(ADMIN + '/audit/search', { data: { action: 'bogus' } })).status()).toBe(400);
        expect((await page.request.get(ADMIN + '/audit/00000000-0000-0000-0000-000000000000')).status()).toBe(404);

        // The shared (non-admin) user is refused by the container role gate.
        await apiLogin(request, { user: shared.testUserName, password: shared.testPassword });
        for (let url of [ADMIN + '/scope', ADMIN + '/thumbnails/count', ADMIN + '/audit/count']) {
            let r = await request.get(url);
            expect([401, 403], url).toContain(r.status());
        }
        expect([401, 403]).toContain((await request.delete(ADMIN + '/thumbnails')).status());
        expect([401, 403]).toContain((await request.delete(ADMIN + '/audit?purge=true')).status());
        expect([401, 403]).toContain((await request.post(ADMIN + '/audit/search', { data: {} })).status());
    });
});
