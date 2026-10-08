/**
 * KI-1 — auth.group member picker + list for PERSON / ACCOUNT / USER (live).
 *
 * The group object view now has a "Members" tab (formDef.js forms.groupmembers) with one member table
 * per participant type (Users / Accounts / Persons), each backed by a virtual list field on auth.group
 * whose baseModel pins the participant model. This spec drives the Persons table end to end as the
 * non-admin shared user: add a member through the picker, see it in the list, confirm the membership
 * on the server (GET /rest/authorization/auth.group/{id}/identity.person/0/100), remove it, confirm
 * it is gone both in the UI and on the server.
 *
 * Uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, createObject, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

async function listGroupMembers(ctx, groupObjectId, actorType) {
    let resp = await ctx.get(REST + '/authorization/auth.group/' + groupObjectId + '/' + actorType + '/0/100');
    if (!resp.ok()) return null;
    return await resp.json();
}

test.describe('auth.group Members tab — add / list / remove a PERSON member (KI-1)', () => {
    let testInfo = {};
    let group = null;
    let person = null;
    const runTag = Date.now().toString(36);
    // createObject() returns identity fields only (model-api.md: create response), so keep the name here.
    const personName = 'ki1_person_' + runTag;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            // A USER-typed group owned by the shared user (its members are managed from the object view).
            group = await ensurePath(ctx, 'auth.group', 'user', '~/KI1Grp_' + runTag);
            expect(group && group.objectId, 'test group created').toBeTruthy();
            // A person under the picker's default landing path for identity.person (~/Persons).
            let persons = await ensurePath(ctx, 'auth.group', 'data', '~/Persons');
            expect(persons && persons.id, '~/Persons').toBeTruthy();
            person = await createObject(ctx, 'identity.person', {
                name: personName,
                groupId: persons.id, groupPath: persons.path
            });
            expect(person && person.objectId, 'test person created').toBeTruthy();
            // Precondition: not a member yet.
            let before = await listGroupMembers(ctx, group.objectId, 'identity.person');
            expect(Array.isArray(before)).toBe(true);
            expect(before.some(p => p.objectId === person.objectId)).toBe(false);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            if (person && person.objectId) await deleteObject(ctx, 'identity.person', person.objectId);
            if (group && group.objectId) await deleteObject(ctx, 'auth.group', group.objectId);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('Members tab lists Users/Accounts/Persons; add a person via the picker, see it, remove it', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/view/auth.group/' + group.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/auth.group/'), { timeout: 15000 });

        // Tab buttons are `button > span.material-icons-outlined(tab|tab_unselected) + label` (object.js
        // buttonTab); scope to those so a breadcrumb/menu button containing "Members" can never match.
        let membersTab = page.locator('button:has(span.material-icons-outlined)', { hasText: /^\s*tab(_unselected)?\s*Members\s*$/ }).first();
        await expect(membersTab).toBeVisible({ timeout: 30000 });
        await membersTab.click();

        // Three per-type member tables render (each field container carries its label).
        for (let label of ['Users', 'Accounts', 'Persons']) {
            await expect(page.getByText(label, { exact: true }).first()).toBeVisible({ timeout: 15000 });
        }

        // The Persons table: its field wrapper is the `div.field-grid-item-*` that object.js
        // modelFieldContainer renders around `label.field-label[for=personMembers]` + the member list.
        // Anchor on that wrapper, NOT on "nearest ancestor with a New button": after an add/remove the
        // member list re-queries and renders "Loading..." with no toolbar for a moment, during which the
        // nearest such ancestor is the whole tab (all three tables) and `0 items` matches twice (strict
        // mode violation — it throws instead of retrying). Seen 2026-10-07 in a serial batch run.
        let personsBox = page.locator('div[class^="field-grid-item"]:has(> label.field-label[for="personMembers"])').first();
        await expect(personsBox).toBeVisible();
        await expect(personsBox.getByText('Persons', { exact: true })).toBeVisible();
        await expect(personsBox.getByText(/0 items/)).toBeVisible({ timeout: 15000 });

        // Add → picker (identity.person, lands on ~/Persons) → pick our person → confirm.
        await personsBox.locator('button[title="New"]').click();
        let overlay = page.locator('.am7-picker-overlay');
        await expect(overlay).toBeVisible({ timeout: 15000 });
        let row = overlay.locator('tr.tabular-row', { hasText: personName });
        await expect(row.first()).toBeVisible({ timeout: 20000 });
        await row.first().click();
        await overlay.locator('button:has(span.material-symbols-outlined:text-is("check"))').click();
        await expect(overlay).toBeHidden({ timeout: 15000 });

        // The member shows in the Persons table ...
        await expect(personsBox.getByText(personName).first()).toBeVisible({ timeout: 15000 });
        await expect(personsBox.getByText(/1 item\b/)).toBeVisible({ timeout: 15000 });

        // ... and the server agrees.
        let afterAdd = await listGroupMembers(page.request, group.objectId, 'identity.person');
        expect(afterAdd && afterAdd.some(p => p.objectId === person.objectId), 'server lists the person as a group member').toBe(true);

        // Remove: select the row, click Delete, list is empty again.
        await personsBox.getByText(personName).first().click();
        let removeBtn = personsBox.locator('button[title="Delete"]');
        await expect(removeBtn).toBeVisible();
        await removeBtn.click();
        await expect(personsBox.getByText(/0 items/)).toBeVisible({ timeout: 15000 });
        await expect(personsBox.getByText(personName)).toHaveCount(0);

        let afterRemove = await listGroupMembers(page.request, group.objectId, 'identity.person');
        expect(afterRemove && afterRemove.some(p => p.objectId === person.objectId), 'server no longer lists the person').toBe(false);
    });
});
