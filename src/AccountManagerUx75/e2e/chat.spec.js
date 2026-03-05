import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Chat feature', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('chat button in top menu navigates to chat', async ({ page }) => {
        let chatBtn = page.locator('button[title="Chat"]');
        await expect(chatBtn).toBeVisible({ timeout: 5000 });
        await chatBtn.click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await screenshot(page, 'chat-via-menu');
    });

    test('chat page renders content', async ({ page }) => {
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        // Either the empty state message or a session list should be visible
        let emptyState = page.locator('text=Select a conversation');
        let sessionList = page.locator('text=New Session');
        let either = emptyState.or(sessionList);
        await expect(either.first()).toBeVisible({ timeout: 15000 });
        await screenshot(page, 'chat-state');
    });

    test('library status check does not crash', async ({ page }) => {
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        // Wait for page to settle — library check runs on oninit
        await page.waitForTimeout(3000);
        // Page should still be on chat route (no redirect to error)
        expect(page.url()).toMatch(/.*#!\/chat/);
        await screenshot(page, 'chat-library-check');
    });

});
