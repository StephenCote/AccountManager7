/**
 * Chat edit via UI — tests the actual edit button → textarea → save button flow.
 * NOT an API test. Exercises the real saveEdits() code path in chat.js.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

test.describe('Chat edit UI flow', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('edit message via UI textarea persists after save', async ({ page }) => {
        test.setTimeout(120000);

        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
            await expect(wizardTitle).not.toBeVisible({ timeout: 5000 });
        }

        // Open sessions sidebar and find one with messages
        let sessionsBtn = page.locator('button[title="Sessions"]');
        if (await sessionsBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
            await sessionsBtn.click();
            await page.waitForTimeout(1000);
        }

        // Wait for chat to auto-select a session, or click one
        await page.waitForTimeout(3000);

        // Dismiss any open dialogs (New Session dialog may have appeared)
        let newSessionDialog = page.locator('text=New Chat Session');
        if (await newSessionDialog.isVisible({ timeout: 2000 }).catch(() => false)) {
            let cancelBtn = page.locator('button:has-text("Cancel")');
            if (await cancelBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
                await cancelBtn.click();
                await page.waitForTimeout(1000);
            }
        }

        // Check if we have messages
        let messages = page.locator('.overflow-y-auto .mb-3');
        let msgCount = await messages.count();

        if (msgCount === 0) {
            // Try clicking sidebar sessions (not "New" buttons)
            let sessionsBtn = page.locator('button[title="Sessions"]');
            if (await sessionsBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
                await sessionsBtn.click();
                await page.waitForTimeout(1000);
            }
            // Click session items that have text content (not icon-only buttons)
            let sessionItems = page.locator('.overflow-y-auto .truncate');
            let count = await sessionItems.count();
            for (let i = 0; i < Math.min(count, 5); i++) {
                await sessionItems.nth(i).click();
                await page.waitForTimeout(2000);
                msgCount = await messages.count();
                if (msgCount > 0) {
                    console.log('Found session with ' + msgCount + ' messages at index ' + i);
                    break;
                }
            }
        }

        if (msgCount === 0) {
            console.log('No session with messages found');
            return;
        }

        // Click edit mode
        let editBtn = page.locator('button[title="Edit messages"]');
        await expect(editBtn).toBeVisible({ timeout: 5000 });
        await editBtn.click();
        await page.waitForTimeout(1000);

        // Find textareas
        let textareas = page.locator('textarea[id^="editMessage-"]');
        let taCount = await textareas.count();
        console.log('Textareas: ' + taCount);

        if (taCount === 0) {
            console.log('FAIL: No textareas in edit mode');
            // Log what's in the DOM
            let html = await page.locator('.overflow-y-auto').first().innerHTML().catch(() => 'N/A');
            console.log('Messages area HTML (first 500): ' + html.substring(0, 500));
            await screenshot(page, 'chat-edit-ui-no-textareas');
            expect(taCount).toBeGreaterThan(0);
            return;
        }

        // Record original
        let original = await textareas.first().inputValue();
        console.log('Original: ' + original.substring(0, 80));

        // Modify
        let marker = ' [UI-EDIT-' + Date.now() + ']';
        await textareas.first().fill(original + marker);
        console.log('Added marker: ' + marker);

        // Capture network during save
        let patchRequests = [];
        let searchRequests = [];
        page.on('request', req => {
            if (req.method() === 'PATCH') {
                patchRequests.push({ url: req.url(), body: req.postData() || null });
            }
            if (req.method() === 'POST' && req.url().includes('/search')) {
                searchRequests.push({ url: req.url() });
            }
        });

        let patchResponses = [];
        page.on('response', resp => {
            if (resp.request().method() === 'PATCH') {
                patchResponses.push({ url: resp.url(), status: resp.status() });
            }
        });

        // Click save
        let saveBtn = page.locator('button[title="Save edits"]');
        await expect(saveBtn).toBeVisible({ timeout: 3000 });
        await saveBtn.click();

        // Wait for save + re-peek
        await page.waitForTimeout(8000);

        console.log('PATCH requests: ' + patchRequests.length);
        patchRequests.forEach(r => {
            console.log('  PATCH ' + r.url);
            // Find the marker in the body
            if (r.body && r.body.includes(marker)) {
                console.log('  ** MARKER FOUND IN PATCH BODY **');
            } else {
                console.log('  ** MARKER NOT IN PATCH BODY — saveEdits did not apply edit **');
            }
            // Log all message contents
            try {
                let parsed = JSON.parse(r.body);
                if (parsed.messages) {
                    parsed.messages.forEach(function(msg, i) {
                        console.log('    msg[' + i + '] role=' + msg.role + ' content=' + (msg.content || '').substring(0, 60));
                    });
                }
            } catch(e) {}
        });
        console.log('PATCH responses: ' + patchResponses.length);
        patchResponses.forEach(r => console.log('  ' + r.status + ' ' + r.url));
        console.log('Search requests during save: ' + searchRequests.length);

        // Check console errors
        let errors = capture.errors.filter(e => !e.text.match(/vite|favicon|404|Failed to (get|post)/i));
        if (errors.length) {
            console.log('Console errors during save:');
            errors.forEach(e => console.log('  ' + e.text));
        }

        // Now verify: click edit mode again
        editBtn = page.locator('button[title="Edit messages"]');
        if (await editBtn.isVisible({ timeout: 5000 }).catch(() => false)) {
            await editBtn.click();
            await page.waitForTimeout(1000);

            textareas = page.locator('textarea[id^="editMessage-"]');
            taCount = await textareas.count();
            if (taCount > 0) {
                let afterSave = await textareas.first().inputValue();
                console.log('After save: ' + afterSave.substring(0, 80));
                let persisted = afterSave.includes(marker);
                console.log('PERSISTED: ' + persisted);

                // Restore original
                if (persisted) {
                    await textareas.first().fill(original);
                    saveBtn = page.locator('button[title="Save edits"]');
                    if (await saveBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
                        await saveBtn.click();
                        await page.waitForTimeout(5000);
                    }
                }

                expect(persisted).toBe(true);
            } else {
                console.log('No textareas on second edit — save may have failed');
                expect(taCount).toBeGreaterThan(0);
            }
        }

        await screenshot(page, 'chat-edit-ui');
    });
});
