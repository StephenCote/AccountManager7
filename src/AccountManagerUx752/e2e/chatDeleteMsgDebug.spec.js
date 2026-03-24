/**
 * Debug: check what saveEdits sees when called from the UI
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Chat delete message debug', () => {
    let testInfo = {};
    test.beforeAll(async ({ request }) => { testInfo = await ensureSharedTestUser(request); });

    test('diagnose saveEdits guard failures', async ({ page }) => {
        test.setTimeout(120000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Create session with messages
        let setup = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { am7view } = await import('/src/core/view.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { page: pg } = await import('/src/core/pageClient.js');
            let ts = Date.now().toString(36);
            await LLMConnector.ensureLibrary().catch(() => {});
            await pg.makePath('auth.group', 'data', '~/Chat');
            let chatCfg = await am7chat.makeChat('DbgDel-' + ts, 'qwen3:8b', 'http://192.168.1.42:11434', 'OLLAMA');
            let promptCfg = await am7chat.makePrompt('default');
            let req = await am7chat.getChatRequest('DbgDel-' + ts, chatCfg, promptCfg);
            if (!req) return { error: 'ChatRequest failed' };

            let q0 = am7client.newQuery('olio.llm.chatRequest');
            q0.field('objectId', req.objectId);
            q0.entity.request.push('id', 'objectId', 'name', 'session', 'sessionType');
            let qr0 = await pg.search(q0);
            let fullReq = qr0 && qr0.results && qr0.results.length ? qr0.results[0] : null;
            if (!fullReq || !fullReq.session) return { error: 'No session' };

            let sessType = fullReq.sessionType;
            let q = am7view.viewQuery(am7model.newInstance(sessType));
            q.field('id', fullReq.session.id);
            q.cache(false);
            q.entity.request.push('messages');
            let qr = await pg.search(q);
            let sess = qr.results[0];
            if (!sess.messages) sess.messages = [];
            sess.messages.push({ role: 'user', content: 'Msg to delete' });
            sess.messages.push({ role: 'assistant', content: 'Reply to delete' });
            if (!sess[am7model.jsonModelKey]) sess[am7model.jsonModelKey] = sessType;
            await pg.patchObject(sess);
            am7client.clearCache(sessType);
            return { name: req.name };
        });
        expect(setup.error).toBeUndefined();

        // Navigate to chat and select the session
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Open sessions sidebar
        let sessionsBtn = page.locator('button[title="Sessions"]');
        await expect(sessionsBtn).toBeVisible({ timeout: 5000 });
        await sessionsBtn.click();
        await page.waitForTimeout(1000);

        // Click on our test session
        let sessionItem = page.locator('div', { hasText: setup.name }).first();
        await expect(sessionItem).toBeVisible({ timeout: 10000 });
        await sessionItem.click();
        await page.waitForTimeout(3000);

        // Now check inst.entity from the running chat module
        let entityState = await page.evaluate(async () => {
            // Access the chat module's internal state via the module
            let chatModule = await import('/src/features/chat.js');
            // Can't access internal `inst` directly — use window inspection
            // Instead, check if the entity has session info by examining the DOM
            return { note: 'Cannot access internal state directly' };
        });

        // Collect console errors and network requests during edit flow
        let errors = [];
        let networkCalls = [];
        page.on('console', msg => {
            if (msg.type() === 'error' || msg.type() === 'warn') {
                errors.push(msg.type() + ': ' + msg.text());
            }
        });
        page.on('request', req => {
            let url = req.url();
            if (url.includes('/rest/') && (req.method() === 'POST' || req.method() === 'PATCH' || req.method() === 'PUT')) {
                networkCalls.push(req.method() + ' ' + url.replace(/.*\/rest\//, '/rest/'));
            }
        });

        // Click edit mode button
        let editBtn = page.locator('button[title="Edit messages"]');
        let hasEditBtn = await editBtn.isVisible({ timeout: 5000 }).catch(() => false);
        if (!hasEditBtn) {
            console.log('Edit button not found');
            expect(hasEditBtn).toBe(true);
            return;
        }
        await editBtn.click();
        await page.waitForTimeout(500);

        // Look for delete buttons on messages
        let deleteBtn = page.locator('button:has-text("delete")').first();
        let hasDeleteBtn = await deleteBtn.isVisible({ timeout: 5000 }).catch(() => false);
        console.log('Delete button visible:', hasDeleteBtn);

        if (hasDeleteBtn) {
            await deleteBtn.click();
            await page.waitForTimeout(300);

            // Click save edits
            let saveBtn = page.locator('button[title="Save edits"]');
            let hasSaveBtn = await saveBtn.isVisible({ timeout: 3000 }).catch(() => false);
            console.log('Save button visible:', hasSaveBtn);

            if (hasSaveBtn) {
                await saveBtn.click();
                await page.waitForTimeout(3000);
            }
        }

        // Report console errors and network calls
        console.log('Console errors/warnings:', JSON.stringify(errors));
        console.log('Network calls after edit:', JSON.stringify(networkCalls));

        // Check message count in UI after save
        await page.waitForTimeout(2000);
        let msgCount = await page.evaluate(() => {
            let msgs = document.querySelectorAll('[class*="justify-end"], [class*="justify-start"]');
            return msgs.length;
        });
        console.log('Messages visible in UI after save:', msgCount);
    });
});
