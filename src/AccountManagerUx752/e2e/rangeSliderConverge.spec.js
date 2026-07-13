/**
 * KI-16 Finding C — behavioral (live) verification that the converged range-slider widget works
 * end to end in the two dialog-based workflows that were previously bespoke, schema-blind, single-
 * input-with-no-spinner sliders: workflows/reimage.js (olio.charPerson "Reimage" command) and
 * workflows/reimageApparel.js (olio.apparel "Reimage" command). Both now render their Steps/Refiner
 * Steps/CFG/Refiner CFG/Denoising fields via the same `formFieldRenderers.renderRange` plain-contract
 * helper that backs the canonical schema-driven `renderers.range` (components/formFieldRenderers.js).
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

const BASE_URL = 'https://localhost:8899';

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
        let stepsLabel = dialog.locator('.field-label', { hasText: 'Steps:' }).first();
        await expect(stepsLabel).toBeVisible({ timeout: 10000 });
        let stepsRow = stepsLabel.locator('xpath=..');
        let stepsRange = stepsRow.locator('input[type="range"]');
        let stepsSpinner = stepsRow.locator('input[type="number"]');
        await expect(stepsRange).toHaveAttribute('min', '1');
        await expect(stepsRange).toHaveAttribute('max', '100');

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

    test('apparel Reimage dialog: CFG and Denoising sliders both update live via the same widget', async ({ page }) => {
        test.skip(!apparel || !apparel.objectId, 'Could not create test apparel');

        await page.goto('/#!/view/olio.apparel/' + apparel.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.apparel/'), { timeout: 15000 });

        let reimageBtn = page.locator('button:has(span.material-symbols-outlined:text("auto_awesome"))').first();
        await expect(reimageBtn).toBeVisible({ timeout: 20000 });
        await reimageBtn.click();

        let dialog = page.locator('.am7-dialog-backdrop .am7-dialog').first();
        await expect(dialog).toBeVisible({ timeout: 15000 });

        let cfgLabel = dialog.locator('.field-label', { hasText: 'CFG:' }).first();
        await expect(cfgLabel).toBeVisible({ timeout: 10000 });
        let cfgRange = cfgLabel.locator('xpath=..').locator('input[type="range"]');
        await cfgRange.fill('12.5');
        await page.waitForTimeout(300);
        expect(await cfgRange.inputValue()).toBe('12.5');
        await expect(cfgLabel).toHaveText('CFG: 12.5');

        let denoiseLabel = dialog.locator('.field-label', { hasText: 'Denoising:' }).first();
        await expect(denoiseLabel).toBeVisible({ timeout: 10000 });
        let denoiseRange = denoiseLabel.locator('xpath=..').locator('input[type="range"]');
        await expect(denoiseRange).toHaveAttribute('min', '0');
        await expect(denoiseRange).toHaveAttribute('max', '1');
        await denoiseRange.fill('0.4');
        await page.waitForTimeout(300);
        expect(await denoiseRange.inputValue()).toBe('0.4');
        await expect(denoiseLabel).toHaveText('Denoising: 0.4');
    });
});
