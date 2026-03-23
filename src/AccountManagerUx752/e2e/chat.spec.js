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

test.describe('Chat config picker selection', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    /**
     * Helper: navigate to chat, dismiss wizard, open sidebar, click New, wait for dialog.
     * Returns the picker modal locator helper.
     */
    async function openNewSessionDialog(page) {
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

        // Open sidebar sessions panel, then click New
        let sessionsBtn = page.locator('button[title="Sessions"]');
        await expect(sessionsBtn).toBeVisible({ timeout: 5000 });
        await sessionsBtn.click();
        await page.waitForTimeout(500);

        let newBtn = page.locator('button:has-text("New Session")').or(page.locator('button:has-text("New")'));
        await expect(newBtn.first()).toBeVisible({ timeout: 10000 });
        await newBtn.first().click();

        let dialog = page.locator('text=New Chat Session');
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await page.waitForTimeout(2000);
    }

    /**
     * Helper: open picker, navigate to system library (where configs live), select first item.
     * Returns the selected item name.
     */
    async function pickFirstFromLibrary(page, findBtnIndex) {
        let findBtns = page.locator('button[title="Find"]');
        let findBtn = findBtns.nth(findBtnIndex);
        await expect(findBtn).toBeVisible({ timeout: 5000 });
        await findBtn.click();

        let pickerModal = page.locator('div.fixed.inset-0').filter({ has: page.locator('h3:has-text("Select")') });
        await expect(pickerModal).toBeVisible({ timeout: 15000 });

        // Click system library button to navigate to shared library (configs live here)
        let sysLibBtn = pickerModal.locator('button[aria-label="System library"]');
        let hasSysLib = await sysLibBtn.isVisible({ timeout: 3000 }).catch(() => false);
        if (hasSysLib) {
            await sysLibBtn.click();
            await page.waitForTimeout(1000);
        }

        // Wait for list items (table rows or grid items)
        let listRow = pickerModal.locator('tr.tabular-row').first();
        await expect(listRow).toBeVisible({ timeout: 15000 });

        // If the first row is a group directory, double-click to navigate into it
        let rowText = await listRow.textContent();

        // Click to select the first item
        await listRow.click();
        await page.waitForTimeout(300);

        // Confirm (check button)
        let confirmBtn = pickerModal.locator('button:has(span:text("check"))');
        await expect(confirmBtn).toBeVisible({ timeout: 5000 });
        await confirmBtn.click();

        // Picker should close
        await expect(pickerModal).not.toBeVisible({ timeout: 5000 });
        return rowText;
    }

    test('selecting a chatConfig from picker updates the dialog field', async ({ page }) => {
        test.setTimeout(90000);
        await openNewSessionDialog(page);

        // Clear the default chatConfig selection so we can test manual pick
        let chatConfigClear = page.locator('button[title="Clear"]').first();
        let hasClear = await chatConfigClear.isVisible({ timeout: 3000 }).catch(() => false);
        if (hasClear) {
            await chatConfigClear.click();
            await page.waitForTimeout(500);
        }

        // Pick first chatConfig from library (Find button index 0 = Chat Config)
        await pickFirstFromLibrary(page, 0);

        // The Chat Config field should now show the selected config name (not "(none)")
        let chatConfigLabel = page.locator('label:has-text("Chat Config")').locator('..').locator('span.truncate');
        let configName = await chatConfigLabel.textContent({ timeout: 5000 });
        expect(configName).not.toBe('(none)');
        expect(configName.length).toBeGreaterThan(0);

        await screenshot(page, 'chat-config-picker-selected');
    });

    test('selecting a promptConfig from picker updates the dialog field', async ({ page }) => {
        test.setTimeout(90000);
        await openNewSessionDialog(page);

        // Pick first promptConfig from library (Find button index 1 = Prompt Config)
        await pickFirstFromLibrary(page, 1);

        // Prompt Config field should show a name, not "(none)"
        let promptLabel = page.locator('label:has-text("Prompt Config")').locator('..').locator('span.truncate');
        let promptName = await promptLabel.textContent({ timeout: 5000 });
        expect(promptName).not.toBe('(none)');
        expect(promptName.length).toBeGreaterThan(0);

        await screenshot(page, 'prompt-config-picker-selected');
    });

    test('no console errors during config picker selection', async ({ page }) => {
        test.setTimeout(90000);
        let consoleErrors = [];
        page.on('pageerror', (err) => { consoleErrors.push(err.message); });

        await openNewSessionDialog(page);

        // Pick first chatConfig from library
        await pickFirstFromLibrary(page, 0);

        // Wait for any async callbacks (updateNewSessionDefaultName)
        await page.waitForTimeout(2000);

        // Filter out known non-critical errors
        let critical = consoleErrors.filter(e => !e.includes('ResizeObserver') && !e.includes('WebSocket'));
        expect(critical).toEqual([]);

        await screenshot(page, 'chat-config-no-errors');
    });
});
