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
 * Fixture: the SHARED TEST USER creates its own olio.charPerson (with a nested
 * olio.statistics carrying a deliberately non-default height) in ~/HeightFeetInchesE2E
 * and views that record. An unfiltered org-wide charPerson search used to pick records
 * owned by other users, whose by-id view the shared user is (correctly) denied, so the
 * editor never rendered. Self-cleaning: the charPerson is deleted in afterAll.
 *
 * Uses the SHARED TEST USER per project rules (never admin).
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

// 5'7" — not the olio.statistics schema default (5.10), so a form that fell back to a fresh
// default statistics record instead of the persisted one cannot pass.
const HEIGHT = 5.07;
const EXPECTED_FEET = 5;
const EXPECTED_INCHES = 7;

async function asUser(user, password, fn) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let resp = await apiLogin(ctx, { user, password });
        if (!resp.ok() && resp.status() !== 204) throw new Error('login failed (status ' + resp.status() + ')');
        return await fn(ctx);
    } finally {
        await apiLogout(ctx).catch(() => {});
        await ctx.dispose();
    }
}

async function findOwnCharacter(ctx, objectId) {
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'olio.charPerson',
            fields: [{ name: 'objectId', comparator: 'equals', value: objectId }],
            request: ['id', 'objectId', 'name', 'ownerId', 'statistics'],
            recordCount: 1,
            cache: false
        }
    });
    if (!resp.ok()) return null;
    let body = await resp.json().catch(() => null);
    return (body && body.results && body.results.length) ? body.results[0] : null;
}

test.describe('Height field feet+inches renderer', () => {
    let testInfo = {};
    let target = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        expect(testInfo && testInfo.testUserName).toBe('e2etest_shared');

        target = await asUser(testInfo.testUserName, testInfo.testPassword, async (ctx) => {
            let dir = await ensurePath(ctx, 'auth.group', 'data', '~/HeightFeetInchesE2E');
            expect(dir && dir.id, 'ensurePath(~/HeightFeetInchesE2E) must return the group').toBeTruthy();

            let name = testInfo.testUserName + '_heightfi_' + Date.now().toString(36);
            let resp = await ctx.post(REST + '/model', {
                data: {
                    schema: 'olio.charPerson',
                    name,
                    firstName: 'Height', middleName: 'FeetInches', lastName: 'Test',
                    gender: 'female', alignment: 'neutralgood',
                    groupId: dir.id, groupPath: dir.path,
                    statistics: { schema: 'olio.statistics', groupId: dir.id, height: HEIGHT }
                }
            });
            expect(resp.ok(), 'POST /rest/model olio.charPerson must succeed (HTTP ' + resp.status() + ')').toBe(true);
            let created = await resp.json();
            expect(created && created.objectId, 'create must return the charPerson identity').toBeTruthy();

            // Create returns identity only; prove the nested statistics.height persisted so a UI
            // mismatch below is a rendering failure, not a data-never-arrived failure.
            let rec = await findOwnCharacter(ctx, created.objectId);
            expect(rec && rec.objectId, 'created charPerson must be readable by the shared user').toBeTruthy();
            expect(rec.statistics && typeof rec.statistics.height === 'number', 'statistics must round-trip').toBe(true);
            expect(rec.statistics.height).toBeCloseTo(HEIGHT, 5);
            return rec;
        });
    });

    test.afterAll(async () => {
        if (!target || !target.objectId) return;
        await asUser(testInfo.testUserName, testInfo.testPassword, async (ctx) => {
            await deleteObject(ctx, 'olio.charPerson', target.objectId);
            let gone = await findOwnCharacter(ctx, target.objectId);
            expect(gone, 'cleanup: charPerson ' + target.name + ' must be deleted').toBeNull();
        });
    });

    async function openStatisticsTab(page) {
        await page.goto('/#!/view/olio.charPerson/' + target.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.charPerson/'), { timeout: 15000 });

        // Click the Statistics tab — its label comes from forms.statisticsRef.label
        let statsTab = page.locator('button:has-text("Statistics")').first();
        await expect(statsTab).toBeVisible({ timeout: 15000 });
        await statsTab.click();

        // The renderer creates inputs named `${useName}_feet` and `${useName}_inches`
        let feetInput = page.locator('input[name="height_feet"]');
        let inchesInput = page.locator('input[name="height_inches"]');
        await expect(feetInput).toBeVisible({ timeout: 10000 });
        await expect(inchesInput).toBeVisible({ timeout: 10000 });
        return { feetInput, inchesInput };
    }

    test('Statistics tab renders feet + inches inputs with decoded values', async ({ page }) => {
        test.setTimeout(60000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let { feetInput, inchesInput } = await openStatisticsTab(page);

        // Decoded from the persisted 5.07 (feet + inches/100), not from the schema default 5.10.
        await expect(feetInput, 'feet input reflects stored height').toHaveValue(String(EXPECTED_FEET));
        await expect(inchesInput, 'inches input reflects stored height').toHaveValue(String(EXPECTED_INCHES));

        await screenshot(page, 'height-feet-inches');
    });

    test('Bumping inches by 1 does not bump feet field', async ({ page }) => {
        test.setTimeout(60000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let { feetInput, inchesInput } = await openStatisticsTab(page);
        await expect(feetInput).toHaveValue(String(EXPECTED_FEET));
        await expect(inchesInput).toHaveValue(String(EXPECTED_INCHES));

        // 7 -> 8 inches: no carry, so the feet field must stay exactly where it was. (This is the
        // regression: the old single number input stepped 5.07 -> 6.07.)
        let targetInches = EXPECTED_INCHES + 1;
        await inchesInput.fill(String(targetInches));
        await inchesInput.blur();

        await expect(inchesInput, 'inches reflects the user edit').toHaveValue(String(targetInches));
        await expect(feetInput, 'feet field should not change when only inches changed').toHaveValue(String(EXPECTED_FEET));
    });
});
