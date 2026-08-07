/**
 * Live end-to-end tests for the feature-flag work: D1 (org-scoped config propagates to a DIFFERENT
 * user), D3 (apply without a page reload), D5 (core is feature-free — no Olio/AI on the dashboard or
 * sidebar under `minimal`), §3.6 (a disabled deep link says so instead of bouncing), and §3.7 (the
 * ?features= override is dev-gated).
 *
 * NO ADMIN USER. Two non-admin test users:
 *   - ensureAdminRoleTestUser()  — a test user granted the AccountAdministrators role, which is what
 *     WEB-INF/resource/roleMap.json maps the JAAS role "admin" onto, so it can call the
 *     @RolesAllowed({"admin"}) PUT. Admin is used only inside the api.js helper to provision it.
 *   - ensureSharedTestUser()     — a plain user, the subject of every propagation assertion.
 *
 * SERIAL. Every test mutates one organization-wide record, so these cannot run in parallel with each
 * other. Run with --workers=1.
 *
 * The suite restores the full feature set in afterAll — leaving the organization on `minimal` would
 * strand the admin UI itself (featureConfig is a feature).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import {
    ensureSharedTestUser,
    ensureAdminRoleTestUser,
    setOrgFeatures,
    getOrgFeatures,
    getAvailableFeatures,
    putOrgFeaturesStatus
} from './helpers/api.js';

test.describe.configure({ mode: 'serial' });

let shared = null;
let featAdmin = null;
let allIds = [];

/** Wait for the authenticated dashboard to be mounted. */
async function waitForMain(page) {
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 20000 }
    );
    // The dashboard cards render from am7model.categories after the app payload resolves.
    await page.waitForFunction(() => document.querySelectorAll('.panel-card').length > 0, { timeout: 20000 });
}

/**
 * Visible /main dashboard card titles.
 *
 * The label is a bare text node child of `p.card-title`, next to an icon <span> whose text content is
 * the ligature name (e.g. "account_tree"). Read only the text nodes so the returned strings are the
 * labels themselves — otherwise "Identity" arrives as "account_treeIdentity" and any exact comparison
 * (in either direction) is meaningless. See components/panel.js:186-192.
 */
async function cardTitles(page) {
    return await page.$$eval('.panel-card .card-title', els => els.map(e =>
        Array.from(e.childNodes)
            .filter(n => n.nodeType === 3)
            .map(n => n.textContent)
            .join('')
            .trim()
    ).filter(t => t.length));
}

/**
 * Visible sidebar category button labels. asideMenu.js:100-104 renders the icon in a
 * .material-symbols-outlined span and the label in a plain span, so select the plain one.
 */
async function sidebarLabels(page) {
    return await page.$$eval('aside button span:not([class])', els => els.map(e => e.textContent.trim())
        .filter(t => t.length));
}

/** aria-labels of the top toolbar buttons (feature menu items render with aria-label = label). */
async function topMenuLabels(page) {
    return await page.$$eval('[role="toolbar"] button', els => els.map(e => e.getAttribute('aria-label')));
}

test.describe('Feature flags — live', () => {

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        featAdmin = await ensureAdminRoleTestUser(request);
        expect(featAdmin.user && featAdmin.user.objectId, 'admin-role test user was not created').toBeTruthy();
        expect(featAdmin.roleAssigned, 'the AccountAdministrators role could not be assigned').toBe(true);

        let { status, manifest } = await getAvailableFeatures(request);
        expect(status, 'the manifest endpoint must be reachable before these tests mean anything').toBe(200);
        allIds = manifest.map(f => f.id);
        // Freshness: the PRE-change service served 12 ids and no `media`.
        expect(allIds, 'stale deployment — rebuild the image').toContain('media');
    });

    test.afterAll(async ({ request }) => {
        if (allIds.length) {
            await setOrgFeatures(request, allIds);
        }
    });

    // ── D1 ──────────────────────────────────────────────────────────────

    test('D1: an admin-role user PUT is visible to a different user over REST', async ({ request }) => {
        let reduced = ['core', 'media', 'chat'];
        let stored = await setOrgFeatures(request, reduced);
        expect(stored, 'PUT /rest/config/features failed for the AccountAdministrators test user').not.toBeNull();
        expect(stored.slice().sort()).toEqual(reduced.slice().sort());

        // The reduced set MUST differ from the default, or a fallback would satisfy this assertion.
        expect(reduced.length, 'the reduced set must be smaller than the manifest').toBeLessThan(allIds.length);

        let seen = await getOrgFeatures(request, { userName: shared.testUserName, password: shared.testPassword });
        expect(seen.status).toBe(200);
        expect(seen.features.slice().sort(),
            'the plain test user must see the set the admin-role user saved — before D1 this returned the full default'
        ).toEqual(reduced.slice().sort());
        expect(seen.profile).toBe('custom');
    });

    test('D1: a plain user cannot PUT the feature config', async ({ request }) => {
        let before = await getOrgFeatures(request, { userName: shared.testUserName, password: shared.testPassword });
        let status = await putOrgFeaturesStatus(request, allIds, {
            userName: shared.testUserName, password: shared.testPassword
        });
        expect([401, 403], 'a plain user PUT must be refused by @RolesAllowed({"admin"}), got ' + status)
            .toContain(status);

        let after = await getOrgFeatures(request, { userName: shared.testUserName, password: shared.testPassword });
        expect(after.features.slice().sort(), 'a refused PUT must not change anything')
            .toEqual(before.features.slice().sort());
    });

    test('D1: the reduced set is what the plain user\'s browser session actually renders', async ({ page, request }) => {
        await setOrgFeatures(request, ['core', 'media', 'chat', 'featureConfig']);

        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await waitForMain(page);

        let top = await topMenuLabels(page);
        expect(top, 'chat is enabled, so its top menu item must be present').toContain('Chat');
        expect(top, 'cardGame is disabled, so its top menu item must be gone').not.toContain('Card Game');
        expect(top, 'games is disabled, so its top menu item must be gone').not.toContain('Games');
        await screenshot(page, 'featureflags-d1-reduced-topmenu');
    });

    // ── §3.6 ────────────────────────────────────────────────────────────

    test('§3.6: a deep link into a disabled feature says so instead of silently bouncing', async ({ page, request }) => {
        await setOrgFeatures(request, ['core', 'media', 'chat', 'featureConfig']);

        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await waitForMain(page);

        // Deep link, not an in-app click.
        await page.goto('/#!/cardGame');
        await expect(page.getByText('This feature is not enabled.')).toBeVisible({ timeout: 15000 });
        expect(page.url(), 'the URL must stay on the requested path, not bounce to /main')
            .toContain('/cardGame');

        // The message names the feature so it is diagnosable.
        await expect(page.getByText('Card Game', { exact: false })).toBeVisible();
        await screenshot(page, 'featureflags-3-6-disabled-deeplink');
    });

    test('§3.6: an enabled route still resolves normally', async ({ page, request }) => {
        await setOrgFeatures(request, ['core', 'media', 'chat', 'featureConfig']);

        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await waitForMain(page);

        await page.goto('/#!/chat');
        await page.waitForTimeout(2500);
        await expect(page.getByText('This feature is not enabled.')).toHaveCount(0);
        expect(page.url()).toContain('/chat');
    });

    // ── D5 ──────────────────────────────────────────────────────────────

    test('D5: the minimal profile removes Olio and AI from the dashboard and the sidebar', async ({ page, request }) => {
        let stored = await setOrgFeatures(request, ['core']);
        expect(stored, 'PUT failed').not.toBeNull();
        expect(stored, 'core is force-included, so ["core"] is the smallest legal set').toEqual(['core']);

        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await waitForMain(page);

        let titles = await cardTitles(page);
        expect(titles.length, 'the dashboard must still render the core categories').toBeGreaterThan(0);
        expect(titles, 'the Olio category is owned by cardGame and must not be on the landing page')
            .not.toContain('Olio');
        expect(titles, 'the AI category is owned by chat and must not be on the landing page')
            .not.toContain('AI');
        // Untagged categories are core and must survive.
        for (let core of ['Identity', 'Assets', 'Process', 'Policy']) {
            expect(titles, core + ' is an untagged core category and must still render').toContain(core);
        }

        let side = await sidebarLabels(page);
        expect(side, 'the sidebar must not offer Olio under minimal').not.toContain('Olio');
        expect(side, 'the sidebar must not offer AI under minimal').not.toContain('AI');
        expect(side, 'the sidebar must still offer Identity').toContain('Identity');

        let top = await topMenuLabels(page);
        for (let gone of ['Chat', 'Card Game', 'Games']) {
            expect(top, gone + ' must be gone under minimal').not.toContain(gone);
        }
        await screenshot(page, 'featureflags-d5-minimal-dashboard');
    });

    test('D5: the full profile brings Olio and AI back', async ({ page, request }) => {
        await setOrgFeatures(request, allIds);

        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await waitForMain(page);

        let titles = await cardTitles(page);
        expect(titles, 'Olio must be on the dashboard when cardGame is enabled').toContain('Olio');
        expect(titles, 'AI must be on the dashboard when chat is enabled').toContain('AI');
        await screenshot(page, 'featureflags-d5-full-dashboard');
    });

    // ── D3 ──────────────────────────────────────────────────────────────

    test('D3: disabling a feature applies without a page reload — menu AND route', async ({ page, request }) => {
        await setOrgFeatures(request, allIds);

        // The admin-role TEST user, not admin: it is the one that can see/use the Features page.
        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await waitForMain(page);

        expect(await topMenuLabels(page), 'Games must be present before the change').toContain('Games');

        // Open the Feature Configuration page from the aside menu.
        await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
            .some(b => b.textContent.trim().includes('Features')), { timeout: 15000 });
        await page.evaluate(() => {
            let btn = Array.from(document.querySelectorAll('button'))
                .find(b => b.textContent.trim().includes('Features'));
            if (btn) btn.click();
        });
        await expect(page.locator('h2').filter({ hasText: 'Feature Configuration' })).toBeVisible({ timeout: 15000 });

        // Record the navigation count so we can prove no reload happened.
        await page.evaluate(() => { window.__am7NavCount = (window.__am7NavCount || 0) + 1; });

        // Toggle `games` off: its card is the one containing the mono id text "games".
        let gamesCard = page.locator('div.border.rounded-lg').filter({ hasText: 'Mini Games' }).first();
        await expect(gamesCard).toBeVisible({ timeout: 10000 });
        await gamesCard.locator('button').first().click();
        await expect(page.getByText('Unsaved changes')).toBeVisible({ timeout: 10000 });

        await page.locator('button:has-text("Save")').click();
        await expect(page.getByText('Feature configuration saved.')).toBeVisible({ timeout: 20000 });

        // NO page.reload() anywhere below this line.
        expect(await page.evaluate(() => window.__am7NavCount),
            'the page must not have reloaded — a reload would clear this marker').toBe(1);

        // 1. The menu item is gone.
        await expect.poll(async () => await topMenuLabels(page), { timeout: 20000 })
            .not.toContain('Games');

        // 2. The route no longer renders — it now hits the disabled-feature catch-all.
        await page.evaluate(() => window.m.route.set('/game'));
        await expect(page.getByText('This feature is not enabled.')).toBeVisible({ timeout: 15000 });
        expect(await page.evaluate(() => window.__am7NavCount),
            'still no reload after navigating to the disabled route').toBe(1);
        await screenshot(page, 'featureflags-d3-disabled-without-reload');

        // Re-enabling from the same page restores the route, again with no reload.
        await page.evaluate(() => window.m.route.set('/admin/features'));
        await expect(page.locator('h2').filter({ hasText: 'Feature Configuration' })).toBeVisible({ timeout: 15000 });
        let gamesCard2 = page.locator('div.border.rounded-lg').filter({ hasText: 'Mini Games' }).first();
        await gamesCard2.locator('button').first().click();
        await page.locator('button:has-text("Save")').click();
        await expect(page.getByText('Feature configuration saved.')).toBeVisible({ timeout: 20000 });
        await expect.poll(async () => await topMenuLabels(page), { timeout: 20000 }).toContain('Games');
        expect(await page.evaluate(() => window.__am7NavCount), 'no reload for the whole test').toBe(1);
    });

    // ── §3.7 ────────────────────────────────────────────────────────────

    /**
     * The ?features= override is deliberately gated behind page.devMode (= import.meta.env.DEV,
     * router.js:229-238). The deployment under test is a production `vite build`, so devMode is FALSE
     * and the override must be inert — the server's org config must win. That negative is what is
     * asserted here.
     *
     * The POSITIVE case (dev build: ?features= beats the server config) cannot be exercised against a
     * production artifact and is NOT covered by this file. It is covered by
     * src/test/featureFlags.test.js "§3.7 — profile precedence" ("the ?features= override is gated
     * behind devMode", "router resolves ?features= -> server config -> __FEATURE_PROFILE__ -> standard").
     */
    test('§3.7: in a production build ?features= is ignored and the server config wins', async ({ page, request }) => {
        await setOrgFeatures(request, allIds);

        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await waitForMain(page);
        expect(await cardTitles(page), 'baseline: the full set renders Olio').toContain('Olio');

        await page.goto('/?features=minimal#!/main');
        await waitForMain(page);

        let titles = await cardTitles(page);
        expect(titles,
            '?features=minimal must NOT take effect in a production build (devMode is false) — the server org config wins'
        ).toContain('Olio');
        expect(titles).toContain('AI');

        // And the server config genuinely is what is in force: reduce it and the same URL follows it.
        await setOrgFeatures(request, ['core', 'featureConfig']);
        await page.goto('/?features=full#!/main');
        await waitForMain(page);
        expect(await cardTitles(page),
            '?features=full must not resurrect Olio when the org config excludes cardGame'
        ).not.toContain('Olio');
    });
});
