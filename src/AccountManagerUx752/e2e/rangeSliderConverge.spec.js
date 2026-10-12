/**
 * KI-16 Finding C — behavioral (live) verification that the converged range-slider widget works
 * end to end in the two dialog-based workflows that were previously bespoke, schema-blind, single-
 * input-with-no-spinner sliders: workflows/reimage.js (olio.charPerson "Reimage" command) and
 * workflows/reimageApparel.js (olio.apparel "Reimage" command). Both now render their Steps/Refiner
 * Steps/CFG/Refiner CFG/Denoising (charPerson, via components/SdConfigPanel.js) and Steps/Refiner
 * Steps/CFG/Mannequin Creativity (apparel, KI-43) fields via the same `formFieldRenderers.renderRange`
 * plain-contract helper that backs the canonical schema-driven `renderers.range`
 * (components/formFieldRenderers.js).
 *
 * workflows/pictureBook.js's scene-image SD panel and components/SdConfigPanel.js's own `rangeInput()`
 * helper were also ported onto the identical shared function (verified directly by code inspection —
 * `SdConfigPanel.js`'s `rangeInput()` now just calls `formFieldRenderers.renderRange(...)`, and
 * `pictureBook.js`'s `renderSdConfig()` calls it inline at each of its 4 slider call sites) and are
 * covered functionally by src/test/rangeSliderConverge.test.js (Vitest, pure widget-contract checks);
 * live e2e coverage here is scoped to the two dialog workflows reachable with lightweight fixtures
 * (a bare charPerson / apparel record), not full picture-book scene generation, to keep this test
 * proportionate — the actual slider MARKUP+BEHAVIOR under test is identical code in all four call
 * sites, not a per-file reimplementation.
 *
 * Uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, ensurePath, createObject, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';

test.describe('Converged range-slider widget — live dialog verification (KI-16 Finding C)', () => {
    let testInfo = {};
    let charPerson = null;
    let apparel = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);

        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });

            let charDir = await ensurePath(ctx, 'auth.group', 'data', '~/KI16SliderChars');
            if (charDir && charDir.id) {
                charPerson = await createObject(ctx, 'olio.charPerson', {
                    name: testInfo.testUserName + '_ki16char',
                    firstName: 'Slider', middleName: 'KI16', lastName: 'Test',
                    gender: 'female', alignment: 'neutralgood',
                    groupId: charDir.id, groupPath: charDir.path
                });
            }

            let apparelDir = await ensurePath(ctx, 'auth.group', 'data', '~/KI16SliderApparel');
            if (apparelDir && apparelDir.id) {
                apparel = await createObject(ctx, 'olio.apparel', {
                    name: testInfo.testUserName + '_ki16apparel',
                    groupId: apparelDir.id, groupPath: apparelDir.path
                });
            }

            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('charPerson Reimage dialog: Steps slider drag updates the live value + spinner, min/max enforced', async ({ page }) => {
        test.skip(!charPerson || !charPerson.objectId, 'Could not create test charPerson');

        await page.goto('/#!/view/olio.charPerson/' + charPerson.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.charPerson/'), { timeout: 15000 });

        let reimageBtn = page.locator('button:has(span.material-symbols-outlined:text("auto_awesome"))').first();
        await expect(reimageBtn).toBeVisible({ timeout: 20000 });
        await reimageBtn.click();

        let dialog = page.locator('.am7-dialog-backdrop .am7-dialog').first();
        await expect(dialog).toBeVisible({ timeout: 15000 });

        // Steps: label shows "Steps: <value>" live; the range input + its companion spinner both carry
        // the same value and the same min/max/step bounds (formFieldRenderers.renderRange contract).
        // reimage.js now renders the SD fields through components/SdConfigPanel.js, whose stacked
        // `field()` helper emits a plain <label> (Tailwind classes, no `field-label`) above the
        // renderRange slider+spinner pair; Steps is `rangeInput(config, "steps", 1, 100, 1, ...)`.
        // Anchor the match so "Refiner Steps: N" (same panel) cannot be picked up.
        let stepsLabel = dialog.locator('label', { hasText: /^Steps: \d+$/ }).first();
        await expect(stepsLabel).toBeVisible({ timeout: 10000 });
        let stepsRow = stepsLabel.locator('xpath=..');
        let stepsRange = stepsRow.locator('input[type="range"]');
        let stepsSpinner = stepsRow.locator('input[type="number"]');
        await expect(stepsRange).toHaveAttribute('min', '1');
        await expect(stepsRange).toHaveAttribute('max', '100');
        await expect(stepsRange).toHaveAttribute('step', '1');
        // Slider and spinner start in agreement with the label
        let initial = await stepsRange.inputValue();
        await expect(stepsSpinner).toHaveValue(initial);
        await expect(stepsLabel).toHaveText('Steps: ' + initial);

        let before = await stepsRange.inputValue();

        // Drag the slider (fill sets the value and fires the input event, exercising the same
        // `oninput` handler a real drag would).
        await stepsRange.fill('77');
        await page.waitForTimeout(300);

        let after = await stepsRange.inputValue();
        expect(after).toBe('77');
        expect(after).not.toBe(before);

        // The companion spinner reflects the same live value (both inputs share the one onInput handler
        // and re-render from the same `cinst.api.steps()` getter).
        await expect(stepsSpinner).toHaveValue('77');

        // The visible field-name label re-rendered with the new value too (unchanged surrounding markup).
        await expect(stepsLabel).toHaveText('Steps: 77');

        // Min/max are still enforced by the browser (a real HTML5 range input, not just visual styling):
        // even setting the DOM value directly and dispatching input clamps to `max`. (Playwright's own
        // `.fill()` pre-validates against min/max and would refuse an out-of-range value outright — that
        // refusal is itself confirmation the bounds are wired up, but we assert the actual clamped value
        // here too by going through the DOM directly.)
        let clamped = await stepsRange.evaluate((el) => {
            el.value = '9999';
            el.dispatchEvent(new Event('input', { bubbles: true }));
            return el.value;
        });
        expect(Number(clamped)).toBeLessThanOrEqual(100);
    });

    test('apparel Reimage dialog: CFG and Mannequin Creativity sliders both update live via the same widget', async ({ page }) => {
        test.skip(!apparel || !apparel.objectId, 'Could not create test apparel');

        await page.goto('/#!/view/olio.apparel/' + apparel.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.apparel/'), { timeout: 15000 });

        let reimageBtn = page.locator('button:has(span.material-symbols-outlined:text("auto_awesome"))').first();
        await expect(reimageBtn).toBeVisible({ timeout: 20000 });
        await reimageBtn.click();

        let dialog = page.locator('.am7-dialog-backdrop .am7-dialog').first();
        await expect(dialog).toBeVisible({ timeout: 15000 });

        // reimageApparel.js: "CFG: <v>" (min 1, max 30, step 0.5). Anchor so "Refiner CFG:" is excluded.
        let cfgLabel = dialog.locator('label.field-label', { hasText: /^CFG: / }).first();
        await expect(cfgLabel).toBeVisible({ timeout: 10000 });
        let cfgRow = cfgLabel.locator('xpath=..');
        let cfgRange = cfgRow.locator('input[type="range"]');
        let cfgSpinner = cfgRow.locator('input[type="number"]');
        await expect(cfgRange).toHaveAttribute('min', '1');
        await expect(cfgRange).toHaveAttribute('max', '30');
        await expect(cfgRange).toHaveAttribute('step', '0.5');
        await cfgRange.fill('12.5');
        await page.waitForTimeout(300);
        expect(await cfgRange.inputValue()).toBe('12.5');
        await expect(cfgSpinner).toHaveValue('12.5');
        await expect(cfgLabel).toHaveText('CFG: 12.5');

        // KI-43: the former "Denoising" slider (denoisingStrength, which the mannequin pass never read)
        // is now "Mannequin Creativity" — a 0-100 UI slider (step 5) that writes mannequinCreativity
        // (0-1 on the wire) through cinst.api so the forms' range decorator does the ×100/÷100
        // conversion; the label re-renders from the entity value, so it proves the round trip.
        let creativityLabel = dialog.locator('label.field-label', { hasText: /^Mannequin Creativity: / }).first();
        await expect(creativityLabel).toBeVisible({ timeout: 10000 });
        let creativityRow = creativityLabel.locator('xpath=..');
        let creativityRange = creativityRow.locator('input[type="range"]');
        let creativitySpinner = creativityRow.locator('input[type="number"]');
        await expect(creativityRange).toHaveAttribute('min', '0');
        await expect(creativityRange).toHaveAttribute('max', '100');
        await expect(creativityRange).toHaveAttribute('step', '5');
        // Untouched: shows the server default (0.85 -> 85) rather than a literal 0
        await expect(creativityLabel).toHaveText('Mannequin Creativity: 85');
        await expect(creativityRange).toHaveValue('85');

        await creativityRange.fill('40');
        await page.waitForTimeout(300);
        expect(await creativityRange.inputValue()).toBe('40');
        await expect(creativitySpinner).toHaveValue('40');
        await expect(creativityLabel).toHaveText('Mannequin Creativity: 40');

        // No stale "Denoising" slider remains in the apparel dialog
        await expect(dialog.locator('label.field-label', { hasText: /^Denoising/ })).toHaveCount(0);
    });
});
