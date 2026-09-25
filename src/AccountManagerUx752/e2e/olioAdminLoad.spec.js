/**
 * Olio Admin — location corpus load.
 *
 * Issue: "Load location data" appeared to do nothing. The bool WAS sent; the default universe had an
 * empty `features` list so GeoParser never ran. The server now derives the country list from the
 * staged `<datagen.path>/location/XX.txt` files, or from an explicit `features` array (ISO alpha-2
 * codes) that the Olio Admin panel sends from its new "Countries" input.
 *
 * Runs as the admin-ROLE test user (`e2etest_featadmin`, never `admin`); the authorization check uses
 * the shared test user. The real load (OLIO_LOAD_TESTS=1) writes into the org and can take a long
 * time on the first run — even `AS` (126 KB) first stages countryInfo/admin1/admin2 and scans the
 * 650 MB alternateNamesV2.txt — so run it alone with --workers=1.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import {
    ensureAdminRoleTestUser, ensureSharedTestUser, apiLogin, getOrgFeatures, setOrgFeatures
} from './helpers/api.js';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://127.0.0.1:9443';
const REST = BASE + '/AccountManagerService7/rest';
const LOAD_URL = REST + '/olio/loadData';
const TOGGLE = 'label:has-text("Include location data (large)") input[type="checkbox"]';
const CODES = '#olioAdminLocationCodes';
const LOAD_BTN = 'button:has-text("Load Olio data into this org")';

let featAdmin, shared;
let featuresBefore = null;

async function openOlioAdmin(page) {
    await page.goto('/#!/admin/olio');
    await page.waitForFunction(() => window.location.hash.includes('/admin/olio'), { timeout: 15000 });
    await expect(page.locator('h1', { hasText: 'Olio Admin' })).toBeVisible({ timeout: 30000 });
}

test.describe('Olio Admin — location load', () => {

    test.beforeAll(async ({ request }) => {
        featAdmin = await ensureAdminRoleTestUser(request);
        shared = await ensureSharedTestUser(request);
        expect(featAdmin.roleAssigned, 'the AccountAdministrators role could not be assigned').toBe(true);

        // The olioAdmin feature is optional; make sure the org has it enabled for the UI tests.
        let seen = await getOrgFeatures(request, { userName: featAdmin.testUserName, password: featAdmin.testPassword });
        if (Array.isArray(seen.features) && !seen.features.includes('olioAdmin')) {
            featuresBefore = seen.features;
            let stored = await setOrgFeatures(request, featuresBefore.concat(['olioAdmin']));
            expect(stored, 'could not enable the olioAdmin feature').toContain('olioAdmin');
        }
    });

    test.afterAll(async ({ request }) => {
        if (featuresBefore) {
            await setOrgFeatures(request, featuresBefore);
        }
    });

    test('panel renders for the admin-role user and reveals the country input', async ({ page }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await openOlioAdmin(page);

        await expect(page.locator(CODES)).toHaveCount(0);
        await page.locator(TOGGLE).check();
        await expect(page.locator(CODES)).toBeVisible();
        await expect(page.locator(CODES)).toHaveAttribute('placeholder', 'e.g. AS, IE, GB');
        await screenshot(page, 'olio-admin-location-input');
    });

    test('invalid country codes are rejected client-side without a request', async ({ page }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await openOlioAdmin(page);

        let loadRequests = [];
        page.on('request', r => { if (r.url().includes('/olio/loadData')) loadRequests.push(r.url()); });

        await page.locator(TOGGLE).check();
        await page.locator(CODES).fill('bad1, ie');
        await page.locator(LOAD_BTN).click();

        await expect(page.getByText('Country codes must be two-letter ISO codes: BAD1')).toBeVisible({ timeout: 10000 });
        expect(loadRequests, 'no loadData request may be sent for invalid codes').toEqual([]);
    });

    test('POST /olio/loadData rejects invalid codes with 400 and non-admins with 401/403', async ({ page, request }) => {
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        let bad = await page.request.post(LOAD_URL, { data: { includeLocations: true, features: ['bad1'] } });
        expect(bad.status()).toBe(400);
        let body = await bad.json();
        expect(body.error).toContain('ISO 3166-1 alpha-2');

        await apiLogin(request, { user: shared.testUserName, password: shared.testPassword });
        let denied = await request.post(LOAD_URL, { data: { includeLocations: true, features: ['AS'] } });
        expect([401, 403]).toContain(denied.status());
    });

    test('loading AS location data through the panel reports locations > 0', async ({ page }) => {
        if (!process.env.OLIO_LOAD_TESTS) {
            test.skip(true, 'set OLIO_LOAD_TESTS=1 to run the real (slow) Olio corpus load');
        }
        // First load in an org stages the world-wide admin1/admin2/alternateNames tables before AS.
        test.setTimeout(55 * 60 * 1000);

        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await openOlioAdmin(page);

        await page.locator(TOGGLE).check();
        await page.locator(CODES).fill('AS');

        let responsePromise = page.waitForResponse(r => r.url().includes('/olio/loadData'), { timeout: 50 * 60 * 1000 });
        await page.locator(LOAD_BTN).click();
        await expect(page.locator('button:has-text("Loading Olio data…")')).toBeVisible({ timeout: 10000 });

        let resp = await responsePromise;
        let text = await resp.text();
        expect(resp.status(), 'loadData failed: ' + text).toBe(200);
        let counts = JSON.parse(text);
        expect(typeof counts.locations, 'response must carry a locations count').toBe('number');
        expect(counts.locations).toBeGreaterThan(0);

        await expect(page.getByText('Olio corpus loaded into this organization.')).toBeVisible({ timeout: 30000 });
        let card = page.locator('.grid > div', { has: page.locator('div.text-sm', { hasText: /^locations$/ }) });
        await expect(card).toHaveCount(1);
        await expect(card.locator('div.text-2xl')).toHaveText(String(counts.locations));
        await screenshot(page, 'olio-admin-location-loaded');
    });
});
