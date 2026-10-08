/**
 * makeFact — the "Fact" toolbar command on the object editor (live).
 *
 * views/object.js objectPage.makeFact was a "not yet implemented" toast stub until 2026-10-07; it is now
 * the Ux7 port in workflows/makeFact.js. This spec drives it end to end as the non-admin shared user:
 * open a saved auth.group, click the Fact button, land on /new/policy.fact/<~/Facts group> with the fact
 * pre-filled from the group (name, sourceUrn, modelType), save it, and confirm on the server (search with
 * cache:false) that a policy.fact pointing at the group now exists in ~/Facts.
 *
 * Runs against the Vite dev server (:8899); uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, findPath, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

async function findFact(ctx, factsGroupId, name) {
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'policy.fact',
            cache: false,
            fields: [
                { name: 'groupId', comparator: 'equals', value: factsGroupId },
                { name: 'name', comparator: 'equals', value: name }
            ],
            request: ['id', 'objectId', 'name', 'type', 'modelType', 'sourceUrn', 'sourceUrl', 'sourceType', 'description', 'groupId'],
            recordCount: 1
        }
    });
    if (!resp.ok()) return null;
    let result = await resp.json();
    return (result && result.results && result.results.length) ? result.results[0] : null;
}

test.describe('object editor Fact command — makeFact (Ux7 port)', () => {
    let testInfo = {};
    let group = null;
    let fact = null;
    const runTag = Date.now().toString(36);
    const groupName = 'KI7Fact_' + runTag;
    const factName = groupName + ' Fact';

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            group = await ensurePath(ctx, 'auth.group', 'data', '~/' + groupName);
            expect(group && group.objectId && group.urn, 'source group created (with urn)').toBeTruthy();
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            if (fact && fact.objectId) await deleteObject(ctx, 'policy.fact', fact.objectId);
            if (group && group.objectId) await deleteObject(ctx, 'auth.group', group.objectId);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('Fact button pre-fills a policy.fact for the group; Save persists it under ~/Facts', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/view/auth.group/' + group.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/auth.group/'), { timeout: 15000 });

        // The record must be loaded (name input shows the group name) before the toolbar is live:
        // the button is rendered opacity-30/inert until entity.objectId is present.
        await expect(page.locator('input[name="name"]')).toHaveValue(groupName, { timeout: 30000 });

        let factBtn = page.locator('[role="main"] button:has(span.material-symbols-outlined:text-is("fact_check"))').first();
        await expect(factBtn).toBeVisible({ timeout: 15000 });
        await expect(factBtn).not.toHaveClass(/opacity-30/);
        await factBtn.click();

        // Routed to the generic editor for a new policy.fact under the (created-if-missing) ~/Facts group.
        await page.waitForFunction(() => /#!\/new\/policy\.fact\/[0-9a-f-]+/i.test(window.location.hash), { timeout: 20000 });
        let factsGroup = await findPath(page.request, 'auth.group', 'data', '~/Facts');
        expect(factsGroup && factsGroup.objectId && factsGroup.id, '~/Facts exists after the command').toBeTruthy();
        expect(page.url()).toContain('/new/policy.fact/' + factsGroup.objectId);

        // Pre-filled from the source record (workflows/makeFact.js buildFact).
        await expect(page.locator('input[name="name"]')).toHaveValue(factName, { timeout: 15000 });
        await expect(page.locator('input[name="sourceUrn"]')).toHaveValue(group.urn);
        await expect(page.locator('input[name="modelType"]')).toHaveValue('auth.group');
        await expect(page.locator('input[name="sourceUrl"]')).toHaveValue(group.path);

        // Save without touching a field: the folded pendingEntity keys count as changes (object.js setInst).
        await page.locator('button:has(span.material-symbols-outlined:text-is("save"))').first().click();
        await page.waitForFunction(() => /#!\/view\/policy\.fact\/[0-9a-f-]+/i.test(window.location.hash), { timeout: 20000 });

        // Server agrees (fresh read, no search cache).
        fact = await findFact(page.request, factsGroup.id, factName);
        expect(fact && fact.objectId, 'policy.fact persisted in ~/Facts').toBeTruthy();
        expect(fact.sourceUrn).toBe(group.urn);
        expect(fact.modelType).toBe('auth.group');
        expect(String(fact.type).toLowerCase()).toBe('factory');
        expect(String(fact.sourceType).toLowerCase()).toBe('data');
        expect(fact.sourceUrl).toBe(group.path);
        expect(fact.description).toContain(group.urn);
        expect(page.url()).toContain('/view/policy.fact/' + fact.objectId);
    });
});
