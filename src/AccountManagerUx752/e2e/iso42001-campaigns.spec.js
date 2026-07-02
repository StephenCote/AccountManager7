/**
 * ISO 42001 Campaigns (Phase 1) — live UI tests against the running stack (Vite + Service7/Tomcat).
 *
 * Logs in as the provisioned non-admin ISO-role user (ensureIso42001TestUser — never admin) which holds
 * Testers/Reporters/Certifiers/Administrators, so create/edit/delete/report controls render and the server
 * authorizes them.
 *
 * Two describes:
 *  1. Campaign CRUD — fully deterministic, no LLM. Proves create → list → edit → delete against the live DB.
 *  2. Launch + Generate Report — hits the LLM at 192.168.1.42 (DGX Spark). GATED behind ISO_LLM_E2E=1 and
 *     run.serial so the default 4-worker suite never fires parallel runs at the single-thread DGX. Run it
 *     explicitly and single-threaded:
 *        ISO_LLM_E2E=1 npx playwright test e2e/iso42001-campaigns.spec.js -g "launch" --workers=1 --project=chromium
 *
 * Uses generalChat as the endpoint (its connection points at http://192.168.1.42:11434, model qwen3-vl:8b-instruct).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureIso42001TestUser, deleteObject, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const REST = 'https://localhost:8899/AccountManagerService7/rest';
// The chatConfig (endpoint) name the launch test runs against — must resolve to a chatConfig pointing at the
// DGX Spark (192.168.1.42) with model qwen3:8b. Override with ISO_LLM_ENDPOINT to match your test-org config.
const LLM_ENDPOINT = process.env.ISO_LLM_ENDPOINT || 'generalChat';

async function goto(page, route) {
    await page.goto('/?features=full#!' + route);
}

test.describe.serial('ISO 42001 Campaigns (live)', () => {
    let iso = {};
    const campaignName = 'e2e-campaign-' + Date.now().toString(36);
    const renamedDesc = 'edited-by-e2e';

    test.beforeAll(async ({ request }) => {
        // ensureIso42001TestUser is idempotent; note its rolesAssigned is only the *newly* added roles, so it
        // is empty on re-runs when membership already exists. Don't gate on it — the create step proves the role.
        iso = await ensureIso42001TestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: iso.testUserName, password: iso.testPassword });
    });

    test('create a campaign via the UI, then see it in the list', async ({ page }) => {
        await goto(page, '/iso42001/campaigns');
        await expect(page.locator('h2', { hasText: 'Test Campaigns' })).toBeVisible({ timeout: 15000 });

        await page.locator('button:has-text("New Campaign")').click();
        await expect(page.locator('h3', { hasText: 'New Campaign' })).toBeVisible();

        await page.locator('input[name="campaign_name"]').fill(campaignName);
        await page.locator('input[name="campaign_endpointName"]').fill(LLM_ENDPOINT);
        await page.locator('input[name="campaign_samplesPerGroup"]').fill('1');

        await page.locator('button:has-text("Create Campaign")').click();

        // On success the view routes to the detail page for the new campaign.
        await expect(page.locator('h1', { hasText: campaignName })).toBeVisible({ timeout: 15000 });
        await expect(page.getByText(LLM_ENDPOINT, { exact: false })).toBeVisible();
        await screenshot(page, 'iso42001-campaign-detail');

        // And it appears in the list.
        await goto(page, '/iso42001/campaigns');
        await expect(page.locator('td', { hasText: campaignName })).toBeVisible({ timeout: 15000 });
    });

    test('edit the campaign (description) and see the change persist', async ({ page }) => {
        await goto(page, '/iso42001/campaigns');
        await page.locator('td', { hasText: campaignName }).click();
        await expect(page.locator('h1', { hasText: campaignName })).toBeVisible({ timeout: 15000 });

        await page.locator('button:has-text("Edit")').first().click();
        await expect(page.locator('h3', { hasText: 'Edit Campaign' })).toBeVisible();
        await page.locator('input[name="campaign_description"]').fill(renamedDesc);
        await page.locator('button:has-text("Save Changes")').click();

        // Detail re-renders with the new description text.
        await expect(page.getByText(renamedDesc)).toBeVisible({ timeout: 15000 });
    });

    test('delete the campaign and see it removed from the list', async ({ page }) => {
        page.on('dialog', d => d.accept()); // confirm() → OK
        await goto(page, '/iso42001/campaigns');
        await page.locator('td', { hasText: campaignName }).click();
        await expect(page.locator('h1', { hasText: campaignName })).toBeVisible({ timeout: 15000 });

        await page.locator('button:has-text("Delete")').click();

        // Routes back to the list; the row is gone.
        await expect(page.locator('h2', { hasText: 'Test Campaigns' })).toBeVisible({ timeout: 15000 });
        await expect(page.locator('td', { hasText: campaignName })).toHaveCount(0);
    });
});

test.describe.serial('ISO 42001 launch + report (live LLM)', () => {
    let iso = {};
    const campaignName = 'e2e-llm-' + Date.now().toString(36);
    let runObjectId = null;

    test.skip(process.env.ISO_LLM_E2E !== '1',
        'LLM launch test is gated behind ISO_LLM_E2E=1 (single-thread DGX Spark; run with --workers=1)');

    test.beforeAll(async ({ request }) => {
        iso = await ensureIso42001TestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: iso.testUserName, password: iso.testPassword });
    });

    test('launch a run against a campaign, wait for completion, then generate a report', async ({ page }) => {
        test.setTimeout(600000); // a real LLM run is minutes; single-thread DGX
        // Generate Report uses window.prompt for the report name; accept it with a name (Playwright otherwise
        // auto-dismisses dialogs → prompt returns null → no report).
        page.on('dialog', d => d.accept('e2e report ' + Date.now().toString(36)));

        // Create a minimal campaign (1 sample/group, tier 1, a single BIAS test) to keep LLM calls low.
        await goto(page, '/iso42001/campaigns');
        await page.locator('button:has-text("New Campaign")').click();
        await page.locator('input[name="campaign_name"]').fill(campaignName);
        await page.locator('input[name="campaign_endpointName"]').fill(LLM_ENDPOINT);
        await page.locator('input[name="campaign_samplesPerGroup"]').fill('1');
        await page.locator('input[name="campaign_testIds"]').fill('BIAS-ATTR-002');
        await page.locator('button:has-text("Create Campaign")').click();
        await expect(page.locator('h1', { hasText: campaignName })).toBeVisible({ timeout: 15000 });

        // Launch — the view routes to the results page for the new run once startRun returns.
        await page.locator('button:has-text("Launch Run")').click();
        await page.waitForFunction(() => /\/iso42001\/results\//.test(window.location.hash), { timeout: 600000 });
        runObjectId = await page.evaluate(() => window.location.hash.split('/iso42001/results/')[1]);
        expect(runObjectId, 'run objectId from route').toBeTruthy();

        // startRun is synchronous server-side, so by the time we land on results the run is COMPLETED (or FAILED).
        // Reload results and require the result row for the configured test (verdict may be PASS/FLAG/FAIL/ERROR
        // — with samplesPerGroup=1 it is typically ERROR, which is still a rendered result, not a failure).
        await goto(page, '/iso42001/results/' + runObjectId);
        await expect(page.getByText('BIAS-ATTR-002').first()).toBeVisible({ timeout: 60000 });

        // Generate a report from this completed run (Reporter role) → routes to the report detail.
        await page.locator('button:has-text("Generate Report")').click();
        await page.waitForFunction(() => /\/iso42001\/report\//.test(window.location.hash), { timeout: 60000 });
        await expect(page.getByText('Certification')).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'iso42001-generated-report');
    });
});
