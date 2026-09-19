/**
 * Beauty composite + selector — E2E against the live stack.
 *
 * Two things are verified here that no unit test can reach:
 *
 *  1. The deployed server actually computes the new composite: olio.statistics.beauty is SAVG
 *     (StatCompositeProvider), read back over REST from a real record. There is deliberately no
 *     'beauty' field on olio.charPerson - the score lives on the statistics and the narrative wording
 *     on olio.narrative.beautyDescription - so this also asserts charPerson does not carry one.
 *
 *  2. The Beauty selector renders beside Body Shape in the character form and rewrites the statistics
 *     the composite reads, so picking a band actually moves the character into it — judged by the
 *     server after a save, not by the client's own copy of the formula.
 *
 * Run against the docker-compose.test.yml stack. Use 127.0.0.1, not localhost: localhost resolves to
 * IPv6 ::1 and Docker only publishes on IPv4, so the browser gets a TLS reset and page.goto times out
 * (.claude/rules/troubleshooting.md).
 *
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/beautySelector.spec.js \
 *     --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

/// Base statistics the point budget is spread across (StatisticsUtil.getBaseStatisticNames).
const BASE_STATS = ['physicalStrength', 'physicalEndurance', 'manualDexterity', 'agility', 'speed',
    'mentalStrength', 'mentalEndurance', 'intelligence', 'wisdom', 'charisma', 'creativity',
    'spirituality', 'luck', 'perception'];

/// The statistics the beauty selector is allowed to move (formDef.js beautyInputStats).
const BEAUTY_INPUTS = ['charisma', 'intelligence', 'creativity', 'spirituality', 'perception', 'manualDexterity'];

/// A projection has to carry the intermediate composites too. ComputeProvider skips a computed field
/// whose inputs are not on the record, so requesting 'beauty' without willpower/maximumHealth/
/// physicalAppearance/mentalHealth silently returns it unset rather than computed.
const STAT_REQUEST = ['id', 'objectId'].concat(BASE_STATS,
    ['willpower', 'maximumHealth', 'wit', 'charm', 'physicalAppearance', 'mentalHealth', 'beauty']);

let _testUser = {};
let _charDir = null;
let _created = [];

function b64(s) { return Buffer.from(s).toString('base64'); }

async function newCtx() {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    let resp = await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: _testUser.testUserName,
            credential: b64(_testUser.testPassword),
            type: 'hashed_password'
        }
    });
    if (!resp.ok()) throw new Error('login failed: HTTP ' + resp.status());
    return ctx;
}

test.beforeAll(async ({ request }) => {
    _testUser = await ensureSharedTestUser(request);
    let ctx = await newCtx();
    try {
        let path = 'B64-' + b64('/home/' + _testUser.testUserName + '/Characters').replace(/=/g, '%3D');
        let resp = await ctx.get(REST + '/path/make/auth.group/data/' + path);
        expect(resp.ok(), 'could not make the Characters group').toBeTruthy();
        _charDir = await resp.json();
    } finally {
        await ctx.dispose();
    }
});

test.afterAll(async () => {
    let ctx = await newCtx();
    try {
        for (const oid of _created) {
            await ctx.delete(REST + '/model/olio.charPerson/' + oid).catch(() => {});
        }
    } finally {
        await ctx.dispose();
    }
});

/// Create a charPerson, then set its base statistics. The statistics are patched on their own record:
/// AccessPoint.update does not cascade into a nested foreign model, so folding them into a charPerson
/// patch would update the FK and silently drop the values (.claude/rules/model-api.md).
async function createCharacter(ctx, name, stats) {
    let resp = await ctx.post(REST + '/model', {
        data: {
            schema: 'olio.charPerson',
            name: name,
            firstName: name,
            gender: 'female',
            age: 27,
            groupId: _charDir.id
        }
    });
    expect(resp.ok(), 'charPerson create failed: HTTP ' + resp.status()).toBeTruthy();
    let created = await resp.json();
    expect(created.objectId, 'charPerson create returned no objectId').toBeTruthy();
    _created.push(created.objectId);

    let full = await (await ctx.get(REST + '/model/olio.charPerson/' + created.objectId + '/full')).json();
    expect(full.statistics, 'charPerson has no statistics record').toBeTruthy();

    let patch = { schema: 'olio.statistics', id: full.statistics.id, objectId: full.statistics.objectId };
    BASE_STATS.forEach(f => { patch[f] = stats[f]; });
    let presp = await ctx.patch(REST + '/model', { data: patch });
    expect(presp.ok(), 'statistics patch failed: HTTP ' + presp.status()).toBeTruthy();

    /// The charPerson was cached by the read above, and CacheDBSearch.clearCache does not invalidate a
    /// parent when a nested record changes, so its derived beauty would otherwise read pre-patch.
    await ctx.get(REST + '/cache/clearAll');

    return { objectId: created.objectId, statisticsObjectId: full.statistics.objectId };
}

async function readBeauty(ctx, ref) {
    await ctx.get(REST + '/cache/clearAll');

    let sresp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'olio.statistics',
            cache: false,
            fields: [{ name: 'objectId', comparator: 'equals', value: ref.statisticsObjectId }],
            request: STAT_REQUEST
        }
    });
    expect(sresp.ok(), 'statistics search failed: HTTP ' + sresp.status()).toBeTruthy();
    let results = (await sresp.json()).results || [];
    expect(results.length, 'statistics record not found').toBe(1);

    let presp = await ctx.get(REST + '/model/olio.charPerson/' + ref.objectId + '/full');
    expect(presp.ok(), 'charPerson read failed: HTTP ' + presp.status()).toBeTruthy();
    let person = await presp.json();

    /// The duplicate-named field is gone: the score is on the statistics, the wording on the narrative.
    expect(person.beauty, 'olio.charPerson should not carry a beauty field').toBeUndefined();

    return { stats: results[0], person: person };
}

/// Mirrors NarrativeUtil.getLooksPrettyUgly. Asserted independently against the real band function by
/// TestBodyStats#TestNarrativeGetLooksPrettyUgly and #TestBeautyLabelsAllReachableFromStat.
function bandFor(beauty) {
    if (beauty <= 3) return 'hideous';
    if (beauty <= 5) return 'homely';
    if (beauty <= 7) return 'bland';
    if (beauty <= 10) return 'comely';
    if (beauty <= 12) return 'pretty';
    if (beauty <= 14) return 'beautiful';
    return 'gorgeous';
}

test.describe('beauty composite over REST', () => {

    /// The flat stat block is the diagonal case: the spread transform centres on the character's own
    /// mean base statistic, so a character whose statistics are all v must come back with beauty
    /// exactly v. Before the fix this composite could not exceed 14 of its declared 0-20 range at any
    /// allocation, and four of the seven bands were unreachable.
    test('the server computes the composite and its narrative band', async () => {
        let ctx = await newCtx();
        try {
            const cases = [
                { v: 2,  label: 'hideous' },
                { v: 5,  label: 'homely' },
                { v: 7,  label: 'bland' },
                { v: 10, label: 'comely' },
                { v: 12, label: 'pretty' },
                { v: 14, label: 'beautiful' },
                { v: 18, label: 'gorgeous' }
            ];

            for (const c of cases) {
                let stats = {};
                BASE_STATS.forEach(f => { stats[f] = c.v; });
                let ref = await createCharacter(ctx, 'e2e-beauty-flat-' + c.v + '-' + Date.now(), stats);
                let got = await readBeauty(ctx, ref);

                expect(got.stats.beauty,
                    'all base statistics at ' + c.v + ' should give beauty ' + c.v).toBe(c.v);
                expect(bandFor(got.stats.beauty),
                    'beauty ' + c.v + ' should band as ' + c.label).toBe(c.label);
            }
        } finally {
            await ctx.dispose();
        }
    });

    /// The top of the range has to be reachable from a lopsided allocation, not only a flat block.
    test('a beauty-weighted allocation reaches the flattering bands', async () => {
        let ctx = await newCtx();
        try {
            let stats = {};
            BASE_STATS.forEach(f => { stats[f] = 10; });
            BEAUTY_INPUTS.forEach(f => { stats[f] = 17; });

            let ref = await createCharacter(ctx, 'e2e-beauty-weighted-' + Date.now(), stats);
            let got = await readBeauty(ctx, ref);

            expect(got.stats.beauty,
                'a beauty-weighted allocation should climb well above the midpoint').toBeGreaterThanOrEqual(15);
            expect(['beautiful', 'gorgeous']).toContain(bandFor(got.stats.beauty));
        } finally {
            await ctx.dispose();
        }
    });
});

test.describe('beauty selector in the character form', () => {

    test('renders beside Body Shape and moves the statistics into the chosen band', async ({ page }) => {
        let ctx = await newCtx();
        try {
            let stats = {};
            BASE_STATS.forEach(f => { stats[f] = 10; });
            let ref = await createCharacter(ctx, 'e2e-beauty-ux-' + Date.now(), stats);

            let before = await readBeauty(ctx, ref);
            expect(before.stats.beauty, 'baseline should be the flat-block identity').toBe(10);
            expect(bandFor(before.stats.beauty)).toBe('comely');

            await login(page, { user: _testUser.testUserName, password: _testUser.testPassword });
            await page.evaluate((oid) => { window.location.hash = '#!/view/olio.charPerson/' + oid; }, ref.objectId);

            /// Both selectors live in the same form section. formFieldRenderers.select lowercases
            /// option values while displaying the raw label, so match on the lowercased value.
            let shapeSelect = page.locator('select').filter({ has: page.locator('option[value="hourglass"]') }).first();
            await expect(shapeSelect, 'Body Shape selector did not render').toBeVisible({ timeout: 30000 });

            let beautySelect = page.locator('select').filter({ has: page.locator('option[value="gorgeous"]') }).first();
            await expect(beautySelect, 'Beauty selector did not render beside Body Shape').toBeVisible({ timeout: 30000 });

            /// Every band must be offered
            for (const band of ['hideous', 'homely', 'bland', 'comely', 'pretty', 'beautiful', 'gorgeous']) {
                await expect(beautySelect.locator('option[value="' + band + '"]')).toHaveCount(1);
            }

            /// It shows the band derived from the character's current statistics - not a stored field
            await expect(beautySelect, 'selector should show the derived band').toHaveValue('comely');

            await screenshot(page, 'beauty-selector-rendered');

            /// Selecting a band rewrites the statistics the composite reads, and the selector reports
            /// back the band it reached.
            await beautySelect.selectOption('gorgeous');
            await expect(beautySelect, 'selector should report the band it reached').toHaveValue('gorgeous');

            /// The object view's save is an icon button (object.js page.iconButton(..., 'save', ...)),
            /// so its accessible name is the material ligature, not a title attribute.
            let saveBtn = page.getByRole('button', { name: 'save', exact: true }).first();
            await expect(saveBtn, 'Save control did not render').toBeVisible({ timeout: 10000 });
            await saveBtn.click();
            await expect(page.locator('.toast-box:has-text("Saved")')).toBeVisible({ timeout: 30000 });

            await screenshot(page, 'beauty-selector-saved');

            /// The assertion that matters: run the written statistics back through the real provider
            /// chain rather than trusting the client-side copy of it.
            let after = await readBeauty(ctx, ref);
            expect(after.stats.beauty,
                'saved statistics should compute into the gorgeous band (15+)').toBeGreaterThanOrEqual(15);
            expect(bandFor(after.stats.beauty), 'server should band the saved statistics as gorgeous').toBe('gorgeous');

            /// ...and it must have got there by moving only the beauty inputs
            BEAUTY_INPUTS.forEach(f => {
                expect(after.stats[f], f + ' should have been raised by the selector').toBeGreaterThan(10);
            });
            ['physicalStrength', 'physicalEndurance', 'agility', 'speed', 'mentalStrength',
                'mentalEndurance', 'wisdom', 'luck'].forEach(f => {
                expect(after.stats[f], f + ' is not a beauty input and must not be touched').toBe(10);
            });
        } finally {
            await ctx.dispose();
        }
    });
});
