/**
 * Picker E2E tests — validates object field pickers (Find/View/Clear buttons)
 *
 * Tests that picker fields render with action buttons and that the ObjectPicker
 * modal opens/closes correctly. Uses charPerson (eyeColor/hairColor pickers, formDef.js:4087-4115)
 * which requires setupWorkflowTestData.
 *
 * Current rendering (components/formFieldRenderers.js:796-851): a picker field is a bordered row with
 * the value label and ICON buttons titled "Find" (always), "View" and "Clear" (only when the field holds
 * a value). There is no button text, so `button:has-text("Find")` matches nothing and
 * `button:has-text("Clear")` matches the top-menu "Clear Cache" button instead. Find -> doFieldPicker ->
 * ObjectPicker.open renders `.am7-picker-overlay` (components/picker.js:419) with an h3 title, the
 * embedded list control, and a header close button; there is no "Search..." input.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupWorkflowTestData, cleanupTestUser, apiLogin, apiLogout, ensurePath, createObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

// PLAYWRIGHT_BASE_URL is mandatory for every run; no local-dev fallback.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL;
const REST = BASE_URL + '/AccountManagerService7/rest';

const FIND = 'button[title="Find"]';
const OVERLAY = '.am7-picker-overlay';

async function openCharPerson(page, objectId) {
    await page.goto('/#!/view/olio.charPerson/' + objectId);
    await page.waitForFunction(
        () => window.location.hash.includes('/view/olio.charPerson/'),
        null, { timeout: 15000 }
    );
    // Wait for form to render
    let inputs = page.locator('input, select, textarea');
    await expect(inputs.first()).toBeVisible({ timeout: 30000 });
}

/**
 * Create a data.color in the test user's own ~/Colors (owned, group-scoped) as that user.
 * ~/Colors is where the data.color picker opens (picker.js:251,265 -> resolveUserContainer ->
 * page.makePath of am7view.pathForType, modelDef "group":"Colors"), so a fresh user sees an empty
 * picker unless something is provisioned here.
 */
async function createUserColor(ctx, testInfo, suffix, hex) {
    let colorDir = await ensurePath(ctx, 'auth.group', 'data', '~/Colors');
    if (!colorDir || !colorDir.id) throw new Error('could not ensure ~/Colors for ' + testInfo.testUserName);
    // Create returns identity fields only (model-api.md "Create response pattern") — keep the name.
    const colorName = testInfo.testUserName + suffix;
    let color = await createObject(ctx, 'data.color', {
        name: colorName,
        hex: hex,
        groupId: colorDir.id,
        groupPath: colorDir.path
    });
    if (!color || !color.objectId) throw new Error('could not create data.color ' + colorName + ' in ~/Colors');
    return Object.assign({}, color, { name: colorName });
}

async function provisionColor(testInfo, suffix, hex) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let lr = await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
        if (!(lr.ok() || lr.status() === 204)) throw new Error('login as ' + testInfo.testUserName + ' failed: HTTP ' + lr.status());
        let color = await createUserColor(ctx, testInfo, suffix, hex);
        await apiLogout(ctx);
        return color;
    } finally {
        await ctx.dispose();
    }
}

/**
 * Give the per-run charPerson an eyeColor so the View/Clear buttons render. The color lives in the
 * test user's own ~/Colors (owned, group-scoped) and is attached with a PATCH carrying only schema +
 * identity + the changed foreign field (model-api.md PATCH rules).
 */
async function provisionEyeColor(testInfo) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let lr = await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
        if (!(lr.ok() || lr.status() === 204)) throw new Error('login as ' + testInfo.testUserName + ' failed: HTTP ' + lr.status());

        let color = await createUserColor(ctx, testInfo, '_eye', '#3366cc');

        let patch = await ctx.patch(REST + '/model', {
            headers: { 'Content-Type': 'application/json' },
            data: {
                schema: 'olio.charPerson',
                id: testInfo.charPerson.id,
                objectId: testInfo.charPerson.objectId,
                eyeColor: { schema: 'data.color', id: color.id, objectId: color.objectId }
            }
        });
        let patchText = await patch.text();
        console.log('[picker] PATCH charPerson.eyeColor -> HTTP ' + patch.status() + ' ' + patchText.slice(0, 120));
        if (!patch.ok() || patchText !== 'true') throw new Error('PATCH charPerson.eyeColor failed: HTTP ' + patch.status() + ' ' + patchText.slice(0, 300));

        // Confirm the server has it before driving the UI (server search cache is keyed per query).
        let full = await ctx.get(REST + '/model/olio.charPerson/' + testInfo.charPerson.objectId + '/full');
        let rec = await full.json();
        let eye = rec && rec.eyeColor;
        if (!eye || !eye.objectId) throw new Error('charPerson /full does not carry eyeColor after PATCH: ' + JSON.stringify(rec && rec.eyeColor));
        await apiLogout(ctx);
        return color;
    } finally {
        await ctx.dispose();
    }
}

test.describe('Object field pickers', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await setupWorkflowTestData(request, { suffix: 'pk' + Date.now().toString(36) });
        if (!testInfo.charPerson || !testInfo.charPerson.objectId) {
            throw new Error('setupWorkflowTestData did not create a charPerson for ' + testInfo.testUserName);
        }
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('picker fields show Find/View/Clear buttons on object form', async ({ page }) => {
        await openCharPerson(page, testInfo.charPerson.objectId);

        // charPerson has eyeColor and hairColor pickers -> at least two Find icon buttons.
        let findBtns = page.locator(FIND);
        await expect(findBtns.first()).toBeVisible({ timeout: 10000 });
        let findCount = await findBtns.count();
        console.log('[picker] Find buttons: ' + findCount + ', View: ' + await page.locator('button[title="View"]').count()
            + ', Clear: ' + await page.locator('button[title="Clear"]').count());
        expect(findCount, 'eyeColor + hairColor pickers render a Find button each').toBeGreaterThanOrEqual(2);
        // A fresh charPerson has no eye/hair color, so a Find row shows the "(none)" placeholder label.
        await expect(page.locator('span[title="(none)"]').first()).toBeVisible();
        await screenshot(page, 'picker-buttons-visible');
    });

    test('Find button opens picker modal', async ({ page }) => {
        await openCharPerson(page, testInfo.charPerson.objectId);

        await page.locator(FIND).first().click();
        let modal = page.locator(OVERLAY);
        await expect(modal).toBeVisible({ timeout: 10000 });
        // Header title (picker.js:430) and embedded list toolbar
        await expect(modal.locator('h3').first()).toBeVisible({ timeout: 5000 });
        await expect(modal.locator('.result-nav-outer').first()).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'picker-modal-open');

        // Close the modal via the header X button (picker.js:438-441)
        await modal.locator('button:has(span:text-is("close"))').first().click();
        await expect(modal).not.toBeVisible({ timeout: 5000 });
    });

    test('picker modal shows items and allows selection', async ({ page }) => {
        // The picker opens on the user's own ~/Colors, which is empty for the per-run user — provision a
        // color there so a row exists (the old `tr.list-tr` branch could never match: openForPicker forces
        // table mode, list.js:1538, and rows render as `tr.tabular-row`, decorator.js:333).
        let color = await provisionColor(testInfo, '_pick', '#cc3366');
        await openCharPerson(page, testInfo.charPerson.objectId);

        // eyeColor is the first picker field on the charPerson form (formDef.js:4087-4100).
        await page.locator(FIND).first().click();
        let modal = page.locator(OVERLAY);
        await expect(modal).toBeVisible({ timeout: 10000 });
        await expect(modal.locator('h3').first()).toBeVisible({ timeout: 5000 });
        await modal.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });

        let rows = modal.locator('tr.tabular-row');
        let row = rows.filter({ hasText: color.name });
        await expect(row, 'provisioned ~/Colors entry must be listed in the picker').toHaveCount(1, { timeout: 15000 });
        console.log('[picker] rows in picker list: ' + await rows.count());
        await screenshot(page, 'picker-items-listed');

        // Single click toggles the row's checked state (decorator.js:355 -> ctl.select); the toolbar check
        // button (list.js:972) confirms the selection -> pickerHandler -> ObjectPicker.close + onSelect.
        await row.click();
        await expect(row).toHaveClass(/tabular-row-active/, { timeout: 5000 });
        await modal.locator('button:has(span:text-is("check"))').first().click();
        await expect(modal).not.toBeVisible({ timeout: 5000 });

        // The selection lands on the eyeColor field: its label now shows the color name and the
        // View/Clear buttons appear (formFieldRenderers.js:829-850).
        await expect(page.locator('span[title="' + color.name + '"]').first(), 'eyeColor label shows the picked color').toBeVisible({ timeout: 10000 });
        await expect(page.locator('button[title="Clear"]').first()).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'picker-item-selected');
    });

    test('Clear button removes picker field value without errors', async ({ page }) => {
        // View/Clear only render when the field has a value (formFieldRenderers.js:841-850) — provision one.
        let color = await provisionEyeColor(testInfo);
        let consoleErrors = [];
        page.on('pageerror', e => consoleErrors.push(e.message));

        await openCharPerson(page, testInfo.charPerson.objectId);

        // The eyeColor row shows the color's name as its label with View + Clear next to it.
        let valueLabel = page.locator('span[title="' + color.name + '"], span:text-is("' + color.name + '")').first();
        await expect(valueLabel, 'eyeColor label must show the provisioned color name').toBeVisible({ timeout: 15000 });
        let clearBtn = page.locator('button[title="Clear"]');
        await expect(clearBtn.first()).toBeVisible({ timeout: 5000 });
        let viewBtn = page.locator('button[title="View"]');
        expect(await viewBtn.count(), 'View button renders alongside Clear for a valued picker').toBeGreaterThan(0);
        let clearsBefore = await clearBtn.count();
        await screenshot(page, 'picker-before-clear');

        // Click clear — doFieldClear (object.js:564) nulls the in-memory value and redraws.
        await clearBtn.first().click();
        await expect(page.locator('span[title="' + color.name + '"]')).toHaveCount(0, { timeout: 5000 });
        await expect(clearBtn).toHaveCount(clearsBefore - 1, { timeout: 5000 });
        expect(consoleErrors, 'no page errors on Clear:\n' + consoleErrors.join('\n')).toHaveLength(0);
        await screenshot(page, 'picker-cleared');
    });

    test('picker shows Home, Favorites, and Library buttons', async ({ page }) => {
        await openCharPerson(page, testInfo.charPerson.objectId);

        await page.locator(FIND).first().click();
        let modal = page.locator(OVERLAY);
        await expect(modal).toBeVisible({ timeout: 10000 });

        // Wait for list content or empty state to load
        await modal.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });

        // Source buttons are plain icon buttons (list.js:969-1015 via pagination.button -> page.iconButton,
        // pageClient.js:482 — no aria-label, no text): `home` = user path (~/Colors), `admin_panel_settings`
        // = shared library (/Library/Colors), `star` = favorites, `cottage` = home root. The active one
        // carries the `active` class (list.js:988,997,1006).
        let srcBtn = (ico) => modal.locator('.result-nav-outer button:has(span:text-is("' + ico + '"))').first();
        let homeBtn = srcBtn('home');
        let favBtn = srcBtn('star');
        let libraryBtn = srcBtn('admin_panel_settings');
        let pathLabel = modal.locator('p.text-xs').first();

        // Home should be present (user path for data.color = ~/Colors)
        await expect(homeBtn).toBeVisible({ timeout: 5000 });
        // Favorites should be present
        await expect(favBtn).toBeVisible({ timeout: 5000 });
        // Library should be present (data.color has /Library/Colors — model.js:14)
        await expect(libraryBtn).toBeVisible({ timeout: 5000 });

        // Home should be active initially (picker.js:265 starts at the user path; list.js:1536 -> 'home')
        await expect(homeBtn).toHaveClass(/active/);
        await expect(pathLabel).toHaveText(new RegExp('/home/' + testInfo.testUserName + '/Colors$'));

        // Click Library — should switch to shared library view
        await libraryBtn.click();
        await expect(libraryBtn).toHaveClass(/active/, { timeout: 5000 });
        await expect(homeBtn).not.toHaveClass(/active/);
        await expect(pathLabel).toHaveText(/\/Library\/Colors$/, { timeout: 10000 });
        await screenshot(page, 'picker-library-view');

        // Click Favorites
        await favBtn.click();
        await expect(favBtn).toHaveClass(/active/, { timeout: 5000 });
        await expect(libraryBtn).not.toHaveClass(/active/);
        await screenshot(page, 'picker-favorites-view');

        // Click Home — back to user path
        await homeBtn.click();
        await expect(homeBtn).toHaveClass(/active/, { timeout: 5000 });
        await expect(pathLabel).toHaveText(new RegExp('/home/' + testInfo.testUserName + '/Colors$'), { timeout: 10000 });
        await screenshot(page, 'picker-my-items-view');

        // Close modal
        await modal.locator('button:has(span:text-is("close"))').first().click();
        await expect(modal).not.toBeVisible({ timeout: 5000 });
    });

    test('picker modal closes on backdrop click', async ({ page }) => {
        await openCharPerson(page, testInfo.charPerson.objectId);

        await page.locator(FIND).first().click();
        let modal = page.locator(OVERLAY);
        await expect(modal).toBeVisible({ timeout: 10000 });

        // Click the backdrop (picker.js:421 — the absolute inset-0 div behind the dialog)
        let backdrop = modal.locator('div.absolute.inset-0').first();
        await backdrop.click({ position: { x: 5, y: 5 } });
        await expect(modal).not.toBeVisible({ timeout: 5000 });
        await screenshot(page, 'picker-backdrop-close');
    });
});
