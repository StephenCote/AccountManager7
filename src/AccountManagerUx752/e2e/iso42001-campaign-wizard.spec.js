/**
 * ISO 42001 "Quick Start" campaign wizard (campaignWizard.js) — live UI tests against the running stack.
 *
 * Logs in as the provisioned non-admin ISO-role user (ensureIso42001TestUser — NEVER admin), which holds the
 * Tester role the wizard's Quick Start CTA is gated on. An ISO-user-OWNED olio.llm.chatConfig is provisioned so
 * (a) the wizard's Endpoint step shows a selectable endpoint (org-scoped GET /rest/iso42001/endpoints), and
 * (b) launch's server-side resolveChatConfig (AccessPoint.find as the acting user) can actually read it.
 *
 * Two describes:
 *  1. Wizard create (deterministic, no LLM, MUST run): open the wizard, prove Next is gated on endpoint
 *     selection, walk Endpoint → Scope → Depth → Name & Review, click "Create campaign", assert the campaign
 *     is created and the route lands on its detail page + it appears in the list. Pure DB op.
 *  2. Wizard create-and-launch (LLM-touching): after create, "Create & launch" triggers a synchronous ISO run
 *     against the ISO user's chatConfig endpoint (Ollama on the DGX Spark, 192.168.1.42). GATED behind
 *     ISO_LLM_E2E=1 and run.serial so the default 4-worker suite never fires parallel runs at the single-thread
 *     DGX. Run it explicitly and single-threaded:
 *        ISO_LLM_E2E=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *          npx playwright test e2e/iso42001-campaign-wizard.spec.js -g "launch" --workers=1 --project=chromium
 *
 * Windows/Docker: run with PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 (localhost → IPv6 ::1 which Docker
 * doesn't map → 30s timeout). The login helper stubs the WebSocket before goto (nginx drops the WS session
 * cookie → Tomcat closes → forceLogin → #!/sig).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureIso42001TestUser, ensureChatConfig } from './helpers/api.js';

async function goto(page, route) {
    await page.goto('/?features=full#!' + route);
}

async function openWizard(page) {
    await goto(page, '/iso42001/campaigns');
    await expect(page.locator('h2', { hasText: 'Test Campaigns' })).toBeVisible({ timeout: 15000 });
    await page.locator('button:has-text("Quick Start")').click();
    await expect(page.locator('h3', { hasText: 'Quick Start — New Campaign' })).toBeVisible({ timeout: 10000 });
}

test.describe.serial('ISO 42001 campaign wizard — create (live, deterministic)', () => {
    let iso = {};
    let endpointName = null;
    const campaignName = 'e2e-wizard-' + Date.now().toString(36);

    test.beforeAll(async ({ request }) => {
        iso = await ensureIso42001TestUser(request);
        // Provision an endpoint (olio.llm.chatConfig) OWNED BY the ISO user so it's both listed and readable.
        endpointName = await ensureChatConfig(request, null, {
            user: iso.testUserName, password: iso.testPassword
        });
        expect(endpointName, 'an ISO-user-owned chatConfig endpoint must be provisioned').toBeTruthy();
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: iso.testUserName, password: iso.testPassword });
    });

    test('Endpoint step gates Next until an endpoint is selected', async ({ page }) => {
        await openWizard(page);
        await expect(page.locator('h4', { hasText: 'LLM Endpoint' })).toBeVisible();

        // The provisioned endpoint renders as a selectable button.
        let epBtn = page.locator('button', { hasText: endpointName }).first();
        await expect(epBtn).toBeVisible({ timeout: 10000 });

        // Before selecting: Next is disabled (blockNext).
        let next = page.locator('button:has-text("Next")');
        await expect(next).toBeDisabled();

        // After selecting: Next is enabled.
        await epBtn.click();
        await expect(next).toBeEnabled();
    });

    test('walk the wizard and create a campaign, landing on its detail page', async ({ page }) => {
        await openWizard(page);

        // Step 1 — Endpoint
        await page.locator('button', { hasText: endpointName }).first().click();
        await page.locator('button:has-text("Next")').click();

        // Step 2 — Scope (keep BIAS module; single test to keep the config tight)
        await expect(page.locator('h4', { hasText: 'Scope' })).toBeVisible();
        await page.getByPlaceholder('e.g. GENDER-01').fill('BIAS-ATTR-002');
        await page.locator('button:has-text("Next")').click();

        // Step 3 — Depth (tier options are exactly 1 and 2 — assert tier 0 is absent)
        await expect(page.locator('h4', { hasText: 'Depth' })).toBeVisible();
        await expect(page.locator('button', { hasText: 'Tier 1' })).toBeVisible();
        await expect(page.locator('button', { hasText: 'Tier 2' })).toBeVisible();
        await expect(page.locator('button', { hasText: 'Tier 0' })).toHaveCount(0);
        await page.locator('input[type="number"]').first().fill('1'); // samplesPerGroup
        await page.locator('button:has-text("Next")').click();

        // Step 4 — Name & Review
        await expect(page.locator('h4', { hasText: 'Name & Review' })).toBeVisible();
        await page.getByPlaceholder('e.g. Gender bias').fill(campaignName);
        // Review summary reflects the chosen endpoint.
        await expect(page.getByText(endpointName, { exact: false }).first()).toBeVisible();

        await page.locator('button:has-text("Create campaign")').click();

        // finish() routes to the new campaign's detail page (h1 = name).
        await expect(page.locator('h1', { hasText: campaignName })).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'iso42001-wizard-campaign-detail');

        // And it appears in the list.
        await goto(page, '/iso42001/campaigns');
        await expect(page.locator('td', { hasText: campaignName })).toBeVisible({ timeout: 15000 });
    });
});

test.describe.serial('ISO 42001 campaign wizard — create & launch (live LLM)', () => {
    let iso = {};
    let endpointName = null;
    const campaignName = 'e2e-wizard-llm-' + Date.now().toString(36);

    test.skip(process.env.ISO_LLM_E2E !== '1',
        'LLM launch test is gated behind ISO_LLM_E2E=1 (single-thread DGX Spark; run with --workers=1)');

    test.beforeAll(async ({ request }) => {
        iso = await ensureIso42001TestUser(request);
        endpointName = await ensureChatConfig(request, null, {
            user: iso.testUserName, password: iso.testPassword
        });
        expect(endpointName, 'an ISO-user-owned chatConfig endpoint must be provisioned').toBeTruthy();
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: iso.testUserName, password: iso.testPassword });
    });

    test('Create & launch runs a real ISO bias run against the LLM endpoint, then routes to the campaign', async ({ page }) => {
        test.setTimeout(600000); // a real LLM run is minutes; single-thread DGX

        await openWizard(page);

        // Endpoint
        await page.locator('button', { hasText: endpointName }).first().click();
        await page.locator('button:has-text("Next")').click();
        // Scope — single test to minimise LLM calls
        await page.getByPlaceholder('e.g. GENDER-01').fill('BIAS-ATTR-002');
        await page.locator('button:has-text("Next")').click();
        // Depth — 1 sample/group, tier 1 (default)
        await page.locator('input[type="number"]').first().fill('1');
        await page.locator('button:has-text("Next")').click();
        // Name & Review
        await page.getByPlaceholder('e.g. Gender bias').fill(campaignName);

        // "Create & launch": createCampaignFromForm → startRun (synchronous server-side), then route to campaign.
        await page.locator('button:has-text("Create & launch")').click();

        // On success the wizard closes and routes to the campaign detail page.
        await expect(page.locator('h1', { hasText: campaignName })).toBeVisible({ timeout: 600000 });

        // A run exists for this campaign — the detail view lists it under "Runs" (not the "No runs" empty state).
        await goto(page, '/iso42001/campaigns');
        await page.locator('td', { hasText: campaignName }).click();
        await expect(page.locator('h1', { hasText: campaignName })).toBeVisible({ timeout: 15000 });
        await expect(page.getByText('No runs for this campaign yet')).toHaveCount(0, { timeout: 30000 });
        await screenshot(page, 'iso42001-wizard-launched-run');
    });
});
