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

test.describe('Chat session creation (admin)', () => {
    test.beforeEach(async ({ page }) => {
        await login(page, { user: 'admin', password: 'password' });
    });

    test('create session via new session dialog against live backend', async ({ page }) => {
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Dismiss ChatSetupWizard if it appears
        let wizardTitle = page.locator('text=Chat Library Setup');
        let hasWizard = await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false);
        if (hasWizard) {
            await page.locator('button:has-text("Cancel")').click();
            await expect(wizardTitle).not.toBeVisible({ timeout: 5000 });
        }

        // Click "New Session" button
        let newBtn = page.locator('button:has-text("New Session")').or(page.locator('button:has-text("New")'));
        await expect(newBtn.first()).toBeVisible({ timeout: 15000 });
        await newBtn.first().click();

        // New session dialog should appear
        let dialog = page.locator('text=New Chat Session');
        await expect(dialog).toBeVisible({ timeout: 10000 });

        // Wait for defaults to load (chat config, prompt config/template)
        await page.waitForTimeout(3000);

        // Session name should be auto-populated
        let nameInput = page.locator('input[placeholder="Session name"]');
        await expect(nameInput).toBeVisible({ timeout: 5000 });
        let nameVal = await nameInput.inputValue();
        expect(nameVal.length).toBeGreaterThan(0);

        // Overwrite with a unique test name
        let testSessionName = 'E2E Test Session ' + Date.now();
        await nameInput.fill(testSessionName);

        // Create button should be enabled (defaults loaded)
        let createBtn = page.locator('button:has-text("Create")');
        await expect(createBtn).toBeEnabled({ timeout: 5000 });
        await createBtn.click();

        // Dialog should close
        await expect(dialog).not.toBeVisible({ timeout: 10000 });

        // Wait for session to load
        await page.waitForTimeout(2000);

        // Open sidebar conversations panel to verify session appears
        let sessionsBtn = page.locator('button[title="Sessions"]');
        await expect(sessionsBtn).toBeVisible({ timeout: 5000 });
        await sessionsBtn.click();

        // Session should appear in the sidebar list
        let sessionItem = page.locator('text=' + testSessionName);
        await expect(sessionItem.first()).toBeVisible({ timeout: 10000 });

        // Toolbar should show the session name
        let toolbar = page.locator('h2');
        await expect(toolbar.filter({ hasText: testSessionName })).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'chat-session-created');
    });
});

test.describe('Chat picker (admin)', () => {
    test.beforeEach(async ({ page }) => {
        // Use admin which has initialized chat library
        await login(page, { user: 'admin', password: 'password' });
    });

    test('new session picker shows system library, favorites, and home buttons', async ({ page }) => {
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });

        // Wait for chat page to settle
        await page.waitForTimeout(3000);

        // Dismiss ChatSetupWizard if it appears
        let wizardTitle = page.locator('text=Chat Library Setup');
        let hasWizard = await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false);
        if (hasWizard) {
            await page.locator('button:has-text("Cancel")').click();
            await expect(wizardTitle).not.toBeVisible({ timeout: 5000 });
        }

        // Click "New Session" button (in empty state or sidebar)
        let newBtn = page.locator('button:has-text("New Session")').or(page.locator('button:has-text("New")'));
        await expect(newBtn.first()).toBeVisible({ timeout: 15000 });
        await newBtn.first().click();

        // New session dialog should appear
        let dialog = page.locator('text=New Chat Session');
        await expect(dialog).toBeVisible({ timeout: 10000 });

        // Wait for defaults to load, then click search on Chat Config picker field
        await page.waitForTimeout(2000);
        let searchBtn = page.locator('button[title="Find"]').first();
        await expect(searchBtn).toBeVisible({ timeout: 5000 });
        await searchBtn.click();

        // Picker modal should open — use data attribute or broader selector
        // The picker uses z-[60] which is a Tailwind arbitrary value class
        let pickerModal = page.locator('div.fixed.inset-0').filter({ has: page.locator('h3:has-text("Select")') });
        await expect(pickerModal).toBeVisible({ timeout: 15000 });

        // Wait for picker toolbar to render
        let toolbar = pickerModal.locator('.result-nav-outer');
        await expect(toolbar).toBeVisible({ timeout: 10000 });

        // System library button (admin_panel_settings icon) should be present
        let sysLibBtn = pickerModal.locator('button[aria-label="System library"]');
        await expect(sysLibBtn).toBeVisible({ timeout: 5000 });

        // Favorites button should be present
        let favBtn = pickerModal.locator('button[aria-label="Favorites"]');
        await expect(favBtn).toBeVisible({ timeout: 5000 });

        // Home button should be present (user's ~/Chat path)
        let homeBtn = pickerModal.locator('button[aria-label="My items"]');
        await expect(homeBtn).toBeVisible({ timeout: 5000 });

        await screenshot(page, 'chat-picker-toolbar-buttons');

        // Close picker
        let closeBtn = pickerModal.locator('button:has(span:text("close"))');
        await closeBtn.click();
        await expect(pickerModal).not.toBeVisible({ timeout: 5000 });
    });
});
