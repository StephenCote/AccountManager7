/**
 * Card Game overlays — P1-1 (confirm buttons did nothing) + P1-2 (toasts/dialogs rendered twice).
 *
 * P1-2: features/cardGame.js rendered loadToast()/loadDialogs() in its own layout AND CardGameApp.js
 *       rendered them again, so every toast and dialog on /cardGame appeared twice (the second copy
 *       inert — the toast animation targets the first #toast-box-<id>). Fixed by rendering overlays
 *       once via the router's shared layout()/OverlayGuard.
 * P1-1: "Exit game" and "Delete theme" used the legacy string form of dialog.confirm whose callback
 *       receives NO argument, so `if (ok)` never ran. Both now use the Promise form.
 *
 * Uses ensureSharedTestUser() — NEVER admin. login() (helpers/auth.js) installs the WebSocket stub
 * before page.goto (Docker nginx drops the session cookie on the WS upgrade). Run against Docker with
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/cardGameOverlays.spec.js --workers=1 --project=chromium
 * (127.0.0.1 is mandatory — localhost resolves to IPv6 ::1 which Docker does not map.)
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

async function goToCardGame(page) {
    await page.goto('/#!/cardGame');
    await page.waitForFunction(() => window.location.hash.includes('/cardGame'), { timeout: 15000 });
    await page.waitForFunction(() => window.__cardGameCtx !== undefined && window.__cardGameCtx !== null, { timeout: 25000 });
    await expect(page.locator('span.font-bold:has-text("Card Game")')).toBeVisible({ timeout: 15000 });
}

// Minimal deck: two character cards so createGameState() can pick a player and an opponent.
function probeDeck(tag) {
    let stats = { STR: 10, END: 12, AGI: 10, INT: 10, MAG: 12, CHA: 10 };
    let equipped = { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null };
    return {
        deckName: 'e2e-overlay-deck-' + tag,
        storageName: 'e2e-overlay-deck-' + tag,
        themeId: 'high-fantasy',
        cards: [
            { type: 'character', name: 'Probe Knight', race: 'HUMAN', alignment: 'NEUTRAL', level: 1, stats, needs: { hp: 20, energy: 12, morale: 20 }, equipped, activeSkills: [null, null, null, null], portraitUrl: null },
            { type: 'character', name: 'Probe Rogue', race: 'HUMAN', alignment: 'NEUTRAL', level: 1, stats, needs: { hp: 20, energy: 12, morale: 20 }, equipped, activeSkills: [null, null, null, null], portraitUrl: null }
        ]
    };
}

test.describe('Card Game — overlays render once and confirms actually confirm', () => {
    let testInfo = {};
    let themeId = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await goToCardGame(page);
    });

    test.afterEach(async ({ page }) => {
        // Best-effort cleanup of any custom theme this test persisted under ~/CardGame/themes.
        if (themeId) {
            let id = themeId;
            themeId = null;
            await page.evaluate(async (tid) => {
                try { await window.__cardGameNS.Themes.themeStorage.remove(tid); } catch (e) { /* ignore */ }
            }, id).catch(() => {});
        }
    });

    // ── P1-2 ─────────────────────────────────────────────────────────────

    test('P1-2: a toast on /cardGame renders exactly once and animates in', async ({ page }) => {
        // -1 expiry = sticky toast, so the count can't drop while we assert.
        await page.evaluate(() => window.am7page.toast('info', 'overlay-probe-toast', -1));
        await expect(page.locator('.toast-container')).toHaveCount(1);
        let box = page.locator('.toast-box:has-text("overlay-probe-toast")');
        await expect(box).toHaveCount(1);
        // The duplicate copy never got the transition-100 class (querySelector hit the first only);
        // with a single host the one box must animate in.
        await expect(box).toHaveClass(/transition-100/);
        await screenshot(page, 'cardGame-single-toast');
        await page.evaluate(() => window.am7page.clearToast && window.am7page.clearToast());
    });

    test('P1-2: a dialog on /cardGame renders exactly one backdrop', async ({ page }) => {
        await page.evaluate(() => window.Dialog.open({ title: 'overlay-probe-dialog', content: 'probe', actions: [] }));
        await expect(page.locator('.am7-dialog-backdrop[role="dialog"]')).toHaveCount(1);
        await expect(page.locator('.am7-dialog-title:has-text("overlay-probe-dialog")')).toHaveCount(1);
        await page.evaluate(() => window.Dialog.close());
        await expect(page.locator('.am7-dialog-backdrop')).toHaveCount(0);
    });

    // ── P1-1: Delete theme ───────────────────────────────────────────────

    test('P1-1: Delete theme — Confirm actually deletes the persisted custom theme', async ({ page }) => {
        themeId = 'e2e-overlay-theme-' + Date.now().toString(36);
        let themeName = themeId.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase());

        // Persist a real custom theme (data.data under ~/CardGame/themes) through the app's own storage.
        let saved = await page.evaluate(async (tid) => {
            let r = await window.__cardGameNS.Themes.themeStorage.save(tid, { themeId: tid, name: tid, cardPool: [] });
            return !!r;
        }, themeId);
        expect(saved, 'themeStorage.save must persist the probe theme').toBe(true);

        // Theme editor: oninit → loadThemeList() lists builtins + custom.
        await page.evaluate(() => window.__cardGameSetScreen('themeEditor'));
        let row = page.locator('.cg2-theme-list-item', { hasText: themeName });
        await expect(row).toBeVisible({ timeout: 20000 });
        let deleteBtn = row.locator('button.cg2-btn-danger', { hasText: 'Delete' });
        await expect(deleteBtn).toBeVisible();

        // Cancel path first: nothing may be deleted.
        await deleteBtn.click();
        let backdrop = page.locator('.am7-dialog-backdrop[role="dialog"]');
        await expect(backdrop).toHaveCount(1);
        await expect(page.locator('.am7-dialog-title')).toHaveText('Delete Theme');
        await page.locator('.am7-dialog-footer button', { hasText: 'Cancel' }).click();
        await expect(backdrop).toHaveCount(0);
        await expect(row).toBeVisible();

        // Confirm path: the destructive button must resolve true and the record must be removed.
        await deleteBtn.click();
        await expect(backdrop).toHaveCount(1);
        let confirmBtn = page.locator('.am7-dialog-btn-destructive', { hasText: 'Delete' });
        await expect(confirmBtn).toBeVisible();
        await confirmBtn.click();
        await expect(backdrop).toHaveCount(0);

        // Row disappears once loadThemeList() refreshes after the delete.
        await expect(row).toHaveCount(0, { timeout: 20000 });
        // And the backing data.data is really gone (fresh read, client cache cleared).
        await expect.poll(async () => page.evaluate(async (tid) => {
            // clearCache(type) with no local-only flag also clears the server-side
            // /rest/model/search cache for the type, so load() is a true fresh read.
            await window.am7client.clearCache('data.data');
            let rec = await window.__cardGameNS.Themes.themeStorage.load(tid);
            return rec == null;
        }, themeId), { timeout: 20000 }).toBe(true);
        themeId = null; // already removed — skip afterEach cleanup
        await screenshot(page, 'cardGame-theme-deleted');
    });

    // ── P1-1: Exit game ──────────────────────────────────────────────────

    test('P1-1: Exit game — Cancel keeps the game, Exit leaves it', async ({ page }) => {
        // Build a real game state through the app's own factory (no LLM: initializeLLMComponents is
        // not invoked on this path) and enter the game screen.
        let entered = await page.evaluate(async (deck) => {
            let ctx = window.__cardGameCtx;
            let gs = await window.__cardGameNS.GameState.createGameState(deck, deck.cards[0]);
            if (!gs) return false;
            ctx.viewingDeck = deck;
            ctx.gameState = gs;
            ctx.screen = 'game';
            window.m.redraw();
            return true;
        }, probeDeck(Date.now().toString(36)));
        expect(entered, 'createGameState must produce a game state for the probe deck').toBe(true);

        let exitBtn = page.locator('.cg2-game-header button', { hasText: 'Exit' });
        await expect(exitBtn).toBeVisible({ timeout: 15000 });

        // Cancel: still in the game.
        await exitBtn.click();
        let backdrop = page.locator('.am7-dialog-backdrop[role="dialog"]');
        await expect(backdrop).toHaveCount(1);
        await expect(page.locator('.am7-dialog-title')).toHaveText('Exit Game');
        await page.locator('.am7-dialog-footer button', { hasText: 'Cancel' }).click();
        await expect(backdrop).toHaveCount(0);
        expect(await page.evaluate(() => window.__cardGameCtx.screen)).toBe('game');
        expect(await page.evaluate(() => window.__cardGameCtx.gameState != null)).toBe(true);

        // Exit: the confirm must resolve true and the handler body must run.
        await exitBtn.click();
        await expect(backdrop).toHaveCount(1);
        await page.locator('.am7-dialog-btn-destructive', { hasText: 'Exit' }).click();
        await expect(backdrop).toHaveCount(0);
        await expect.poll(() => page.evaluate(() => window.__cardGameCtx.screen)).toBe('deckView');
        expect(await page.evaluate(() => window.__cardGameCtx.gameState)).toBeNull();
        await screenshot(page, 'cardGame-exit-confirmed');
    });
});
