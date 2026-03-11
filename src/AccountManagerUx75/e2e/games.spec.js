import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Games features', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    // --- Games menu ---

    test('games menu shows available games', async ({ page }) => {
        await page.goto('/#!/game');
        await page.waitForURL(/.*#!\/game$/, { timeout: 10000 });
        let heading = page.locator('text=Mini Games');
        await expect(heading).toBeVisible({ timeout: 10000 });
        // Verify game buttons are present
        await expect(page.locator('text=Tetris')).toBeVisible();
        await expect(page.locator('text=Word Battle')).toBeVisible();
        await screenshot(page, 'games-menu');
    });

    // --- Tetris ---

    test('tetris loads and shows start button', async ({ page }) => {
        await page.goto('/#!/game/tetris');
        await page.waitForURL(/.*#!\/game\/tetris/, { timeout: 10000 });
        // Tetris scorecard has a start button
        let startBtn = page.locator('button >> span.material-symbols-outlined:has-text("start")');
        await expect(startBtn).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'tetris-loaded');
    });

    test('tetris game grid renders', async ({ page }) => {
        await page.goto('/#!/game/tetris');
        await page.waitForURL(/.*#!\/game\/tetris/, { timeout: 10000 });
        // Grid should render as a flex container with cube divs
        let grid = page.locator('div[style*="display:flex"][style*="flex-wrap:wrap"]').first();
        await expect(grid).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'tetris-grid');
    });

    // --- Word Game ---

    test('word game loads and shows start button', async ({ page }) => {
        await page.goto('/#!/game/wordGame');
        await page.waitForURL(/.*#!\/game\/wordGame/, { timeout: 10000 });
        // Word Battle shows start screen with "Word Battle" heading and Start Game button
        let heading = page.locator('text=Word Battle');
        await expect(heading).toBeVisible({ timeout: 10000 });
        let startBtn = page.locator('button:has-text("Start Game")');
        await expect(startBtn).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'wordgame-loaded');
    });

    // --- Tetris interaction ---

    test('tetris active piece appears in grid after clicking start', async ({ page }) => {
        await page.goto('/#!/game/tetris');
        await page.waitForURL(/.*#!\/game\/tetris/, { timeout: 10000 });
        let startBtn = page.locator('button >> span.material-symbols-outlined:has-text("start")');
        await expect(startBtn).toBeVisible({ timeout: 10000 });
        await startBtn.click();
        // After start, an active piece appears in the main grid (width: 250px = 10 cols * 25px).
        // Active cells have shadow-lg; the preview div (100px wide) is separate.
        // Checking direct children of the main grid div ensures we find piece cells, not preview cells.
        let activePieceCell = page.locator('div[style*="width: 250px"] > div[class*="shadow-lg"]').first();
        await expect(activePieceCell).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'tetris-active-piece');
    });

    // --- Word Game interaction ---

    test('word game board renders with player panels after start', async ({ page }) => {
        await page.goto('/#!/game/wordGame');
        await page.waitForURL(/.*#!\/game\/wordGame/, { timeout: 10000 });
        let startBtn = page.locator('button:has-text("Start Game")');
        await expect(startBtn).toBeVisible({ timeout: 10000 });
        await startBtn.click();
        // After start, player panels appear with h3 headings
        await expect(page.locator('h3:has-text("Player 1")')).toBeVisible({ timeout: 8000 });
        await expect(page.locator('h3:has-text("Player 2")')).toBeVisible();
        await screenshot(page, 'wordgame-board');
    });

    // --- Card Game ---

    test('card game loads and shows deck list', async ({ page }) => {
        await page.goto('/#!/cardGame');
        await page.waitForURL(/.*#!\/cardGame/, { timeout: 10000 });
        // CardGameApp starts on deckList screen — shows "Card Game" in header
        let heading = page.locator('text=Card Game');
        await expect(heading).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'cardgame-loaded');
    });

    test('card game shows builder or deck list content after loading', async ({ page }) => {
        await page.goto('/#!/cardGame');
        await page.waitForURL(/.*#!\/cardGame/, { timeout: 10000 });
        await expect(page.locator('text=Card Game')).toBeVisible({ timeout: 15000 });
        // After backend call completes: with no saved decks → builder step tabs appear;
        // with saved decks → deck list with "New Deck" button appears.
        let builderTab = page.locator('span:has-text("1. Theme")');
        let deckListBtn = page.locator('button:has-text("New Deck"), .cg2-deck-grid');
        await expect(builderTab.or(deckListBtn).first()).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'cardgame-content');
    });

    // --- No uncaught JS errors across all game routes ---

    test('no uncaught JS errors during game route loads', async ({ page }) => {
        let errors = [];
        page.on('pageerror', err => errors.push(err.message));

        const routes = [
            { url: '/#!/game',          pattern: /.*#!\/game$/ },
            { url: '/#!/game/tetris',   pattern: /.*#!\/game\/tetris/ },
            { url: '/#!/game/wordGame', pattern: /.*#!\/game\/wordGame/ },
            { url: '/#!/cardGame',      pattern: /.*#!\/cardGame/ },
        ];
        for (let { url, pattern } of routes) {
            await page.goto(url);
            await page.waitForURL(pattern, { timeout: 10000 });
            await page.waitForTimeout(2500); // let lazy chunks and async init settle
        }

        expect(errors, 'Uncaught JS errors:\n' + errors.join('\n')).toHaveLength(0);
        await screenshot(page, 'games-no-js-errors');
    });
});
