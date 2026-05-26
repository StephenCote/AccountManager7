/**
 * E2E test for the height feet+inches input renderer.
 *
 * Regression: the statistics form's `height` field rendered as a plain
 * number input. The stored encoding is feet+inches/100 (5.10 = 5'10"), but
 * the browser stepper used step=1, so one click bumped 5.10 → 6.10 which
 * the server reads as 6'10" / 82" — adding ~50–100 lbs to the computed
 * weight per stray click.
 *
 * The fix splits the input into two fields (feet, inches) that write back
 * with proper carry. This test verifies both inputs are visible on a real
 * character's statistics tab and that they hold the decoded values.
 *
 * Uses the SHARED TEST USER per project rules.
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(s) { return Buffer.from(s).toString('base64'); }

async function asUser(user, password, fn) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let resp = await ctx.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: user,
                credential: b64(password),
                type: 'hashed_password'
            }
        });
        if (!resp.ok()) throw new Error('login failed (status ' + resp.status() + ')');
        return await fn(ctx);
    } finally {
        await ctx.get(REST + '/logout').catch(() => {});
        await ctx.dispose();
    }
}

async function findCharacterWithHeight(user, password) {
    return await asUser(user, password, async (ctx) => {
        let resp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.charPerson',
                fields: [],
                request: ['objectId', 'name', 'statistics'],
                recordCount: 5
            }
        });
        if (!resp.ok()) return null;
        let text = await resp.text();
        if (!text) return null;
        let body;
        try { body = JSON.parse(text); } catch (e) { return null; }
        let results = (body && body.results) || [];
        // Pick the first character with a usable height value
        for (let r of results) {
            if (r.statistics && typeof r.statistics.height === 'number' && r.statistics.height > 0) {
                return r;
            }
        }
        return results[0] || null;
    });
}

test.describe('Height field feet+inches renderer', () => {
    let testInfo = {};
    let target = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        target = await findCharacterWithHeight(testInfo.testUserName, testInfo.testPassword);
        // If the shared test user has no characters yet, fall back to looking
        // across organization characters via admin context — but per project
        // rules we don't use admin. So just skip in that case.
        test.skip(!target, 'no charPerson available for shared test user');
    });

    test('Statistics tab renders feet + inches inputs with decoded values', async ({ page }) => {
        test.setTimeout(60000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Open the character editor
        await page.goto('/#!/view/olio.charPerson/' + target.objectId);
        await page.waitForTimeout(2500);

        // Click the Statistics tab — its label comes from forms.statisticsRef.label
        let statsTab = page.locator('button:has-text("Statistics")').first();
        await expect(statsTab).toBeVisible({ timeout: 10000 });
        await statsTab.click();
        await page.waitForTimeout(1500);

        // The new renderer creates inputs named `${useName}_feet` and `${useName}_inches`
        let feetInput = page.locator('input[name="height_feet"]');
        let inchesInput = page.locator('input[name="height_inches"]');

        await expect(feetInput).toBeVisible({ timeout: 10000 });
        await expect(inchesInput).toBeVisible({ timeout: 10000 });

        let storedHeight = (target.statistics && target.statistics.height) || 0;
        let expectedFeet = Math.floor(storedHeight);
        let expectedInches = Math.round((storedHeight - expectedFeet) * 100);
        // Normalize carry the same way the renderer does
        if (expectedInches >= 12) {
            expectedFeet += Math.floor(expectedInches / 12);
            expectedInches = expectedInches % 12;
        }

        let feetVal = await feetInput.inputValue();
        let inchesVal = await inchesInput.inputValue();

        expect(Number(feetVal), 'feet input reflects stored height').toBe(expectedFeet);
        expect(Number(inchesVal), 'inches input reflects stored height').toBe(expectedInches);

        await screenshot(page, 'height-feet-inches');
    });

    test('Bumping inches by 1 does not bump feet field', async ({ page }) => {
        test.setTimeout(60000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.goto('/#!/view/olio.charPerson/' + target.objectId);
        await page.waitForTimeout(2500);

        let statsTab = page.locator('button:has-text("Statistics")').first();
        await expect(statsTab).toBeVisible({ timeout: 10000 });
        await statsTab.click();
        await page.waitForTimeout(1500);

        let feetInput = page.locator('input[name="height_feet"]');
        let inchesInput = page.locator('input[name="height_inches"]');
        await expect(inchesInput).toBeVisible({ timeout: 10000 });

        let feetBefore = Number(await feetInput.inputValue());
        let inchesBefore = Number(await inchesInput.inputValue());

        // Pick a target inches value that won't cause a carry (stay below 12)
        let targetInches = inchesBefore < 11 ? inchesBefore + 1 : inchesBefore - 1;

        await inchesInput.fill(String(targetInches));
        await inchesInput.blur();
        await page.waitForTimeout(500);

        let feetAfter = Number(await feetInput.inputValue());
        let inchesAfter = Number(await inchesInput.inputValue());

        expect(feetAfter, 'feet field should not change when only inches changed').toBe(feetBefore);
        expect(inchesAfter, 'inches reflects the user edit').toBe(targetInches);
    });
});
