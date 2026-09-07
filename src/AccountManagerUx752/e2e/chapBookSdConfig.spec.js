/**
 * ChapBook Review Redesign — PER-SCENE SD CONFIG OVERRIDE (Gap 8 / D2)
 *
 * The redesign renders the per-scene "Image config overrides" via the shared SdConfigPanel and folds
 * its save into the single per-card Save (a SPARSE delta persisted to the scene's configOverride).
 * This drives the real UI and verifies the backend:
 *   1. Expand the override panel, set a config field (Composition/bodyStyle), Save → the scene's
 *      configOverride persists as a JSON string carrying ONLY the changed field (+ schema).
 *   2. Clear removes the override → configOverride is null again.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookSdConfig.spec.js \
 *       --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, bookPages, sceneByObjectId,
    POEM_1, POEM_2
} from './helpers/chapbook.js';

const OVERRIDE_COMPOSITION = 'e2e-override-composition-' + Date.now();

async function seedFreshBook(request, slugPrefix) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    return await createChapBook(request, slugPrefix + '-' + Date.now(), 'E2E SdConfig Book', [p1, p2], 4);
}

test.describe('ChapBook Review — per-scene SD config override', () => {

    test('override sets a sparse delta, persists it, and Clear removes it', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-sdcfg');
        const before = await bookPages(request, bookOid);
        expect(before.length).toBeGreaterThanOrEqual(1);
        const oid = before[0].objectId;

        // Precondition: no override yet.
        const s0 = await sceneByObjectId(request, oid);
        expect(s0.configOverride == null || s0.configOverride === '', 'no override at seed').toBe(true);

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });

        const card = page.locator('div.rounded-lg.border')
            .filter({ has: page.locator('textarea[rows="12"]') }).first();
        await expect(card).toBeVisible({ timeout: 20000 });

        // Expand the collapsible override panel.
        await card.locator('button:has-text("Image config overrides")').click();

        // Composition (bodyStyle) is the first text field in the shared SdConfigPanel; it is a tracked
        // field in forms.sdConfigOverrides, so editing it lands in the sparse delta.
        const composition = card.locator('label:text-is("Composition") + input');
        await expect(composition).toBeVisible({ timeout: 10000 });
        await composition.fill(OVERRIDE_COMPOSITION);

        // Editing the override marks the card dirty → the single per-card Save enables.
        const saveBtn = card.locator('button[title="Save unsaved changes on this page"]');
        await expect(saveBtn).toBeEnabled({ timeout: 10000 });
        await saveBtn.click();

        // Backend: configOverride persisted as a JSON string carrying the changed field + schema, and
        // NOT a fully-materialized entity (sparse).
        let parsed = null;
        await expect.poll(async () => {
            const s = await sceneByObjectId(request, oid);
            if (!s || !s.configOverride) return null;
            try { parsed = JSON.parse(s.configOverride); } catch (_) { return 'unparseable'; }
            return parsed.bodyStyle;
        }, { timeout: 20000, message: 'configOverride persisted with bodyStyle' }).toBe(OVERRIDE_COMPOSITION);

        expect(parsed.schema, 'override carries the sd.config schema').toBe('olio.sd.config');
        // Sparse: it must NOT have materialized dozens of defaulted fields.
        expect(Object.keys(parsed).length, 'override is sparse').toBeLessThan(8);

        // Clear the override.
        await card.getByTitle("Clear this page's overrides (revert to the book config)").click();

        await expect.poll(async () => {
            const s = await sceneByObjectId(request, oid);
            return (s && s.configOverride) ? s.configOverride : null;
        }, { timeout: 20000, message: 'override cleared' }).toBeNull();
    });
});
