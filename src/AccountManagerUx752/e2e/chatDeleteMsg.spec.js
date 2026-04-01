/**
 * Chat message delete test — exercises edit mode delete via API
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Chat message delete', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('saveEdits deletes marked messages from server session', async ({ page }) => {
        test.setTimeout(120000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to chat
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Create a test session with messages via API, then test delete
        let result = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { am7view } = await import('/src/core/view.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { page: pg } = await import('/src/core/pageClient.js');
            let log = [];
            let ts = Date.now().toString(36);

            // Ensure library
            await LLMConnector.ensureLibrary().catch(() => {});
            await pg.makePath('auth.group', 'data', '~/Chat');

            // Create config and session
            let chatCfg = await am7chat.makeChat('DelTest-' + ts, 'qwen3-vl:8b-instruct', 'http://192.168.1.42:11434', 'OLLAMA');
            if (!chatCfg) return { error: 'ChatConfig failed' };
            let promptCfg = await am7chat.makePrompt('default');
            let req = await am7chat.getChatRequest('DelTest-' + ts, chatCfg, promptCfg);
            if (!req) return { error: 'ChatRequest failed' };
            log.push('Created session: ' + req.name);

            // Use search to get session and sessionType (getFull may 404 for chatRequest)
            let q0 = am7client.newQuery('olio.llm.chatRequest');
            q0.field('objectId', req.objectId);
            q0.entity.request.push('id', 'objectId', 'name', 'session', 'sessionType');
            let qr0 = await pg.search(q0);
            let fullReq = qr0 && qr0.results && qr0.results.length ? qr0.results[0] : null;
            if (!fullReq) return { error: 'ChatRequest search failed', log };
            log.push('session: ' + JSON.stringify(fullReq.session));
            log.push('sessionType: ' + fullReq.sessionType);

            if (!fullReq.session || !fullReq.session.id) {
                return { error: 'No session on chatRequest', log };
            }

            // Add test messages directly to the session
            let sessType = fullReq.sessionType;
            let sessId = fullReq.session.id;

            // Fetch session record
            let q = am7view.viewQuery(am7model.newInstance(sessType));
            q.field('id', sessId);
            q.cache(false);
            q.entity.request.push('messages');
            let qr = await pg.search(q);
            if (!qr || !qr.results || !qr.results.length) {
                return { error: 'Session record not found', log };
            }
            let sess = qr.results[0];
            log.push('Session schema: ' + sess[am7model.jsonModelKey]);
            log.push('Messages before: ' + (sess.messages ? sess.messages.length : 0));

            // Add 3 test messages
            if (!sess.messages) sess.messages = [];
            sess.messages.push({ role: 'user', content: 'Test message 1 - ' + ts });
            sess.messages.push({ role: 'assistant', content: 'Test reply 1 - ' + ts });
            sess.messages.push({ role: 'user', content: 'Test message 2 - ' + ts });

            // Ensure schema is set for patch
            if (!sess[am7model.jsonModelKey]) {
                sess[am7model.jsonModelKey] = sessType;
            }

            try {
                await pg.patchObject(sess);
                log.push('Patch succeeded');
            } catch(e) {
                log.push('Patch failed: ' + (e.message || e));
                return { error: 'Patch failed', log };
            }

            // Clear cache and re-fetch to verify
            am7client.clearCache(sessType);
            let q2 = am7view.viewQuery(am7model.newInstance(sessType));
            q2.field('id', sessId);
            q2.cache(false);
            q2.entity.request.push('messages');
            let qr2 = await pg.search(q2);
            let sess2 = qr2 && qr2.results && qr2.results.length ? qr2.results[0] : null;
            let messagesAfterAdd = sess2 ? sess2.messages.length : 0;
            log.push('Messages after add: ' + messagesAfterAdd);

            // Now simulate deleting the middle message (index 1)
            if (sess2 && sess2.messages && sess2.messages.length >= 3) {
                sess2.messages.splice(1, 1); // Remove index 1
                if (!sess2[am7model.jsonModelKey]) sess2[am7model.jsonModelKey] = sessType;
                try {
                    await pg.patchObject(sess2);
                    log.push('Delete patch succeeded');
                } catch(e) {
                    log.push('Delete patch failed: ' + (e.message || e));
                    return { error: 'Delete patch failed', log };
                }

                // Re-fetch to verify
                am7client.clearCache(sessType);
                let q3 = am7view.viewQuery(am7model.newInstance(sessType));
                q3.field('id', sessId);
                q3.cache(false);
                q3.entity.request.push('messages');
                let qr3 = await pg.search(q3);
                let sess3 = qr3 && qr3.results && qr3.results.length ? qr3.results[0] : null;
                let messagesAfterDelete = sess3 ? sess3.messages.length : 0;
                log.push('Messages after delete: ' + messagesAfterDelete);

                return {
                    messagesAfterAdd,
                    messagesAfterDelete,
                    deletedCorrectly: messagesAfterDelete === messagesAfterAdd - 1,
                    log
                };
            }

            return { error: 'Not enough messages to test delete', log };
        });

        console.log('Delete message result:', JSON.stringify(result, null, 2));
        expect(result.error).toBeUndefined();
        expect(result.deletedCorrectly).toBe(true);
    });
});
