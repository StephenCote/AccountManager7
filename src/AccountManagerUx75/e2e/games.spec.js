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

    // --- Card Game ---

    test('card game loads and shows deck list', async ({ page }) => {
        await page.goto('/#!/cardGame');
        await page.waitForURL(/.*#!\/cardGame/, { timeout: 10000 });
        // CardGameApp starts on deckList screen — shows "Card Game" in header
        let heading = page.locator('text=Card Game');
        await expect(heading).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'cardgame-loaded');
    });
});
