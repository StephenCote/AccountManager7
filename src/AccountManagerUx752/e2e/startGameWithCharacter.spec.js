/**
 * startGameWithCharacter — the "Start Game" toolbar command on the charPerson editor (live).
 *
 * views/object.js objectPage.startGameWithCharacter was a "not yet implemented" toast stub until
 * 2026-10-07; it is now the Ux7 port in workflows/startGameWithCharacter.js (dialog.js:1805 origin).
 * Both branches are driven as the non-admin shared user:
 *   1. a character whose groupPath is outside the Olio world → "Character Not in World" prompt; Cancel
 *      leaves the editor where it was and writes no hand-off;
 *   2. a character whose groupPath matches the Ux7 world heuristic (/World Building|Worlds|Population/)
 *      → the hand-off key is written to sessionStorage and the app routes to /cardGame?character=<id>.
 *
 * Runs against the Vite dev server (:8899); uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, createObject, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const KEY = 'olio_selected_character';
// Scoped to the content region: the main nav toolbar's "Games" button uses the same sports_esports icon.
const START_BTN = '[role="main"] button:has(span.material-symbols-outlined:text-is("sports_esports"))';

test.describe('charPerson editor Start Game command — startGameWithCharacter (Ux7 port)', () => {
    let testInfo = {};
    let outside = null;
    let inWorld = null;
    const runTag = Date.now().toString(36);

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            let plain = await ensurePath(ctx, 'auth.group', 'data', '~/KI7StartGameChars');
            let pop = await ensurePath(ctx, 'auth.group', 'data', '~/KI7StartGame/Population');
            expect(plain && plain.id && pop && pop.id, 'test directories').toBeTruthy();
            outside = await createObject(ctx, 'olio.charPerson', {
                name: 'ki7_outside_' + runTag, firstName: 'Loose', lastName: 'Cannon',
                gender: 'male', alignment: 'neutral', groupId: plain.id, groupPath: plain.path
            });
            inWorld = await createObject(ctx, 'olio.charPerson', {
                name: 'ki7_inworld_' + runTag, firstName: 'Rhea', lastName: 'World',
                gender: 'female', alignment: 'neutral', groupId: pop.id, groupPath: pop.path
            });
            expect(outside && outside.objectId && inWorld && inWorld.objectId, 'characters created').toBeTruthy();
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            if (outside && outside.objectId) await deleteObject(ctx, 'olio.charPerson', outside.objectId);
            if (inWorld && inWorld.objectId) await deleteObject(ctx, 'olio.charPerson', inWorld.objectId);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('outside the world: "Character Not in World" prompt; Cancel stays put and writes no hand-off', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/view/olio.charPerson/' + outside.objectId);
        await expect(page.locator('input[name="name"]')).toHaveValue(outside.name || 'ki7_outside_' + runTag, { timeout: 30000 });

        let btn = page.locator(START_BTN).first();
        await expect(btn).toBeVisible({ timeout: 15000 });
        await expect(btn).not.toHaveClass(/opacity-30/);
        await btn.click();

        await expect(page.getByText('Character Not in World')).toBeVisible({ timeout: 10000 });
        await expect(page.getByText('This character is not part of the Olio world', { exact: false })).toBeVisible();
        let adoptBtn = page.getByRole('button', { name: /Adopt Character/ });
        await expect(adoptBtn).toBeVisible();
        await page.getByRole('button', { name: /Cancel/ }).first().click();
        await expect(page.getByText('Character Not in World')).toBeHidden({ timeout: 10000 });

        expect(page.url()).toContain('/view/olio.charPerson/' + outside.objectId);
        expect(await page.evaluate((k) => sessionStorage.getItem(k), KEY)).toBeNull();
    });

    test('inside the world (Population path): hand-off key written and routed to /cardGame?character=<id>', async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/view/olio.charPerson/' + inWorld.objectId);
        await expect(page.locator('input[name="name"]')).toHaveValue('ki7_inworld_' + runTag, { timeout: 30000 });

        let btn = page.locator(START_BTN).first();
        await expect(btn).toBeVisible({ timeout: 15000 });
        await btn.click();

        await page.waitForFunction(() => window.location.hash.startsWith('#!/cardGame'), { timeout: 15000 });
        expect(page.url()).toContain('character=' + inWorld.objectId);
        expect(await page.evaluate((k) => sessionStorage.getItem(k), KEY)).toBe(inWorld.objectId);
        await expect(page.getByText('Character Not in World')).toHaveCount(0);
    });
});
