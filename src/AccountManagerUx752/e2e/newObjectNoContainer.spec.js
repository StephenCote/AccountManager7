/**
 * Issue: "new" object forms for container-less models (system.user etc.) did not render.
 * list.js built the route as '/new/system.user/' + undefined and object.js treated the
 * literal "undefined" as a parent objectId, leaving the view stuck on "Loading...".
 *
 * Runs as the admin-ROLE test user (never `admin`) because listing system.user needs it;
 * the data.note / auth.role regressions run as the shared test user.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import {
    ensureAdminRoleTestUser, ensureSharedTestUser, ensurePath, searchByField, deleteObject
} from './helpers/api.js';

const NAME_INPUT = 'input[name="name"]';
const LOAD_ERROR = '.object-load-error';

async function expectFormRendered(page) {
    await expect(page.locator(NAME_INPUT).first()).toBeVisible({ timeout: 30000 });
    await expect(page.locator(LOAD_ERROR)).toHaveCount(0);
    await expect(page.getByText('Loading...', { exact: true })).toHaveCount(0);
    await expect(page.getByText(/^Error: /)).toHaveCount(0);
}

test.describe('New object without a container', () => {
    let adminRole;

    test.beforeAll(async ({ request }) => {
        adminRole = await ensureAdminRoleTestUser(request);
        expect(adminRole.user && adminRole.user.objectId, 'admin-role test user must exist').toBeTruthy();
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: adminRole.testUserName, password: adminRole.testPassword });
    });

    test('+ on the system.user list opens a rendering form at /new/system.user', async ({ page }) => {
        await page.goto('/#!/list/system.user');
        await page.waitForFunction(() => window.location.hash.includes('/list/system.user'), { timeout: 15000 });

        let addBtn = page.locator('button:has(span:text("add"))').first();
        await expect(addBtn).toBeVisible({ timeout: 15000 });
        await addBtn.click();

        await page.waitForFunction(() => window.location.hash.includes('/new/system.user'), { timeout: 10000 });
        let hash = await page.evaluate(() => window.location.hash);
        expect(hash.split('?')[0]).toBe('#!/new/system.user');
        expect(hash).not.toContain('undefined');

        await expectFormRendered(page);
        await screenshot(page, 'new-system-user-form');
    });

    test('direct /new/system.user renders the form', async ({ page }) => {
        await page.goto('/#!/new/system.user');
        await page.waitForFunction(() => window.location.hash.includes('/new/system.user'), { timeout: 15000 });
        await expectFormRendered(page);
    });

    test('legacy /new/system.user/undefined still renders instead of hanging', async ({ page }) => {
        await page.goto('/#!/new/system.user/undefined');
        await page.waitForFunction(() => window.location.hash.includes('/new/system.user'), { timeout: 15000 });
        await expectFormRendered(page);
    });

    test('unresolvable parent objectId shows a load error instead of Loading...', async ({ page }) => {
        await page.goto('/#!/new/data.note/00000000-0000-0000-0000-000000000000');
        await expect(page.locator(LOAD_ERROR)).toBeVisible({ timeout: 30000 });
        await expect(page.locator(LOAD_ERROR)).toContainText('Could not resolve the parent container');
        await expect(page.getByText('Loading...', { exact: true })).toHaveCount(0);
    });
});

test.describe('New object with a container (regression)', () => {
    let shared;

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        expect(shared.user && shared.user.objectId, 'shared test user must exist').toBeTruthy();
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });
    });

    test('/new/data.note/<groupObjectId> pre-fills groupId and saves into that group', async ({ page }) => {
        let group = await ensurePath(page.request, 'auth.group', 'data', '~/E2E New Object');
        expect(group && group.objectId && group.id, 'group must be created').toBeTruthy();

        await page.goto('/#!/new/data.note/' + group.objectId);
        await expectFormRendered(page);

        let name = 'e2e-new-note-' + Date.now().toString(36);
        await page.locator(NAME_INPUT).first().fill(name);
        await page.locator('button:has(span:text("save"))').first().click();
        await page.waitForFunction(() => window.location.hash.includes('/view/data.note/'), { timeout: 30000 });

        let note = await searchByField(page.request, 'data.note', 'name', name, ['id', 'objectId', 'name', 'groupId']);
        try {
            expect(note, 'note must have been created').toBeTruthy();
            expect(note.groupId).toBe(group.id);
        } finally {
            if (note && note.objectId) await deleteObject(page.request, 'data.note', note.objectId);
        }
    });

});

test.describe('New object under a parent (/pnew)', () => {
    let adminRole;

    test.beforeAll(async ({ request }) => {
        adminRole = await ensureAdminRoleTestUser(request);
    });

    test('/pnew/auth.role/<parentObjectId> renders a form for a parent-scoped new object', async ({ page }) => {
        await login(page, { user: adminRole.testUserName, password: adminRole.testPassword });
        let parent = await searchByField(page.request, 'auth.role', 'name', 'AccountUsers', ['id', 'objectId', 'name']);
        expect(parent && parent.objectId, 'AccountUsers role must be readable').toBeTruthy();

        await page.goto('/#!/pnew/auth.role/' + parent.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/pnew/auth.role/'), { timeout: 15000 });
        await expectFormRendered(page);
    });
});
