/**
 * KI-16 — displayed range-slider value == stored value, across edit + save + full reload, on the
 * canonical schema-driven slider (components/formFieldRenderers.js `renderers.range`) as rendered by
 * the generic editor (views/object.js) for an olio.charPerson's Statistics sub-form
 * (formDef.js forms.statistics → olio.statistics int fields, minValue 0 / maxValue 20).
 *
 * This is the "edit + reload" verification the KI-16 entry still lacked: the earlier spec
 * (rangeSliderConverge.spec.js) checks live drag behaviour inside dialogs; this one checks that the
 * value the slider SHOWS after a hard reload is the value the backend actually STORED (read back via
 * REST, cache:false), i.e. that no display-vs-storage drift (the original KI-16 symptom) survives.
 *
 * Uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, createObject, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const INITIAL = 7;
const EDITED = 13;

async function readStatistics(ctx, statsObjectId) {
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'olio.statistics',
            cache: false,
            request: ['id', 'objectId', 'physicalStrength', 'agility'],
            fields: [{ name: 'objectId', comparator: 'EQUALS', value: statsObjectId }]
        }
    });
    if (!resp.ok()) return null;
    let j = await resp.json();
    return (j && j.results && j.results.length) ? j.results[0] : null;
}

test.describe('Range slider — displayed value equals stored value after edit + reload (KI-16)', () => {
    let testInfo = {};
    let charPerson = null;
    let statsObjectId = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            let dir = await ensurePath(ctx, 'auth.group', 'data', '~/KI16PersistChars');
            expect(dir && dir.id, 'test directory ~/KI16PersistChars').toBeTruthy();
            // Unique name: createObject() returns any existing same-named record, which might not carry
            // the nested statistics this test depends on.
            let name = testInfo.testUserName + '_ki16persist_' + Date.now().toString(36);
            charPerson = await createObject(ctx, 'olio.charPerson', {
                name,
                firstName: 'Persist', middleName: 'KI16', lastName: 'Slider',
                gender: 'female', alignment: 'neutral',
                groupId: dir.id, groupPath: dir.path,
                statistics: {
                    schema: 'olio.statistics',
                    groupId: dir.id,
                    physicalStrength: INITIAL,
                    agility: 5
                }
            });
            expect(charPerson && charPerson.objectId, 'charPerson created').toBeTruthy();

            // Resolve the nested statistics record's objectId so the test can read the STORED value back.
            let full = await ctx.get(REST + '/model/olio.charPerson/' + charPerson.objectId + '/full');
            expect(full.ok(), 'GET charPerson/full').toBeTruthy();
            let fj = await full.json();
            statsObjectId = fj && fj.statistics ? fj.statistics.objectId : null;
            expect(statsObjectId, 'nested olio.statistics was created with the charPerson').toBeTruthy();
            let stored = await readStatistics(ctx, statsObjectId);
            expect(stored && stored.physicalStrength).toBe(INITIAL);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        if (!charPerson || !charPerson.objectId) return;
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            await deleteObject(ctx, 'olio.charPerson', charPerson.objectId);
            if (statsObjectId) await deleteObject(ctx, 'olio.statistics', statsObjectId);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    async function openStatisticsTab(page) {
        await page.goto('/#!/view/olio.charPerson/' + charPerson.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.charPerson/'), { timeout: 15000 });
        let statsTab = page.getByRole('button', { name: /Statistics/ }).first();
        await expect(statsTab).toBeVisible({ timeout: 30000 });
        await statsTab.click();
        let range = page.locator('input[type="range"][name="physicalStrength"]');
        await expect(range).toBeVisible({ timeout: 15000 });
        return range;
    }

    test('physicalStrength: shows stored value, edit + save persists, reload shows the new stored value', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // 1. Displayed value == stored value before any edit.
        let range = await openStatisticsTab(page);
        let spinner = page.locator('input[type="number"][name="physicalStrength_num"]');
        await expect(range).toHaveAttribute('min', '0');
        await expect(range).toHaveAttribute('max', '20');
        await expect(range).toHaveValue(String(INITIAL));
        await expect(spinner).toHaveValue(String(INITIAL));

        // 2. Edit via the slider; the companion spinner tracks it live (same oninput handler).
        await range.fill(String(EDITED));
        await expect(spinner).toHaveValue(String(EDITED));

        // 3. Save (toolbar save icon button) and wait for the success toast.
        let saveBtn = page.locator('button:has(span.material-symbols-outlined:text-is("save"))').first();
        await expect(saveBtn).toBeVisible();
        await saveBtn.click();
        await expect(page.getByText('Saved!').first()).toBeVisible({ timeout: 15000 });

        // 4. The backend actually stored the edited value (fresh read, cache:false).
        let stored = await readStatistics(page.request, statsObjectId);
        expect(stored, 'statistics readable after save').toBeTruthy();
        expect(stored.physicalStrength).toBe(EDITED);

        // 5. Hard reload + re-open the tab: the displayed value is the stored value.
        await page.reload();
        await page.waitForSelector('[role="main"]', { timeout: 30000 });
        range = await openStatisticsTab(page);
        spinner = page.locator('input[type="number"][name="physicalStrength_num"]');
        await expect(range).toHaveValue(String(EDITED));
        await expect(spinner).toHaveValue(String(EDITED));
    });
});
