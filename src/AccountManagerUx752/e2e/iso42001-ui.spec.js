/**
 * ISO 42001 Ux views (Phase 8) — UI render checks against the running stack (Vite + Service7/Tomcat).
 * Non-admin shared test user (never admin). The dashboard renders for any authenticated user; role-gated
 * controls (Test Runner button, pending-cert section) are hidden without the ISO roles — the positive
 * role-gated flows are exercised separately once an ISO-role user is provisioned.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('ISO 42001 Ux (live)', () => {
    let shared = {};

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
    });

    test('compliance dashboard renders for a non-admin user', async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });
        // full profile (default) includes the iso42001 feature → /compliance route is loaded
        await page.goto('/?features=full#!/compliance');
        await expect(
            page.locator('h1', { hasText: 'ISO 42001 Compliance Dashboard' })
        ).toBeVisible({ timeout: 15000 });
        // Stub-preserved sections still present
        await expect(page.getByText('System Status')).toBeVisible();
        await expect(page.getByText('Training Bias Overcorrection Active')).toBeVisible();
        await screenshot(page, 'iso42001-dashboard');
    });
});
// Note: feature/menu gating by profile is covered deterministically in src/test/iso42001.test.js
// (Vitest) — feature resolution here is server-driven and boots once, so a ?features= URL override is
// not a reliable e2e signal.
