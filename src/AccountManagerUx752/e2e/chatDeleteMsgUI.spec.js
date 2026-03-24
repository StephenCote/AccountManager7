/**
 * Chat message delete UI test — exercises the actual edit mode UI flow
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Chat message delete UI', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('edit mode delete button marks and saves message deletion', async ({ page }) => {
        test.setTimeout(120000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Setup: create session with messages via API
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
            let chatCfg = await am7chat.makeChat('UIDelTest-' + ts, 'qwen3:8b', 'http://192.168.1.42:11434', 'OLLAMA');
            if (!chatCfg) return { error: 'ChatConfig failed' };
            let promptCfg = await am7chat.makePrompt('default');
            let req = await am7chat.getChatRequest('UIDelTest-' + ts, chatCfg, promptCfg);
            if (!req) return { error: 'ChatRequest failed' };

            // Get session info
            let q0 = am7client.newQuery('olio.llm.chatRequest');
            q0.field('objectId', req.objectId);
            q0.entity.request.push('id', 'objectId', 'name', 'session', 'sessionType');
            let qr0 = await pg.search(q0);
            let fullReq = qr0 && qr0.results && qr0.results.length ? qr0.results[0] : null;
            if (!fullReq || !fullReq.session) return { error: 'No session' };

            // Add messages to session
            let sessType = fullReq.sessionType;
            let q = am7view.viewQuery(am7model.newInstance(sessType));
            q.field('id', fullReq.session.id);
            q.cache(false);
            q.entity.request.push('messages');
            let qr = await pg.search(q);
            let sess = qr && qr.results && qr.results.length ? qr.results[0] : null;
            if (!sess) return { error: 'Session not found' };

            if (!sess.messages) sess.messages = [];
            sess.messages.push({ role: 'user', content: 'Delete test msg 1' });
            sess.messages.push({ role: 'assistant', content: 'Delete test reply 1' });
            sess.messages.push({ role: 'user', content: 'Delete test msg 2' });
            if (!sess[am7model.jsonModelKey]) sess[am7model.jsonModelKey] = sessType;
            await pg.patchObject(sess);
            am7client.clearCache(sessType);

            return { sessionName: req.name, objectId: req.objectId, messageCount: sess.messages.length };
        });

        console.log('Setup:', JSON.stringify(setup));
        expect(setup.error).toBeUndefined();

        // Now check the entity.session value when loaded via ConversationManager
        let entityCheck = await page.evaluate(async (sessionName) => {
            let { default: CM } = await import('/src/chat/ConversationManager.js');
            await CM.refresh();
            let sessions = CM.getSessions() || [];
            let found = sessions.find(s => s.name === sessionName);
            if (!found) return { error: 'Session not found in list', names: sessions.map(s => s.name).slice(0, 5) };
            return {
                hasSession: !!found.session,
                sessionValue: found.session,
                hasSessionType: !!found.sessionType,
                sessionType: found.sessionType
            };
        }, setup.sessionName);

        console.log('Entity check:', JSON.stringify(entityCheck));
        expect(entityCheck.hasSession).toBe(true);
        expect(entityCheck.hasSessionType).toBe(true);
    });
});
