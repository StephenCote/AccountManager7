/**
 * Chat edit mode: deleting a single message and saving must remove exactly that message.
 *
 * Originally a log-only diagnostic. Its message "delete" selector was `button:has-text("delete")`, which is a
 * case-insensitive substring match and resolves FIRST to the toolbar "Clear history" button (icon text
 * `delete_sweep`, features/chat.js:968-972) — that click fired `POST /rest/chat/clear`, wiped the session,
 * and the diagnostic then reported "Messages visible in UI after save: 0". The per-message delete button is the
 * `button` with exact text "delete" inside an edit-mode message row (chat.js:580-587); rows are
 * `div.flex.mb-3.justify-end|justify-start` (chat.js:573-574 edit mode, :627 display mode).
 *
 * No LLM call is made: makeChat/makePrompt/getChatRequest only create the config records and the session,
 * and the UI edit/save path goes through /rest/model/search, PATCH /rest/model and /rest/chat/history.
 * Runs under the chromium-vite project (dynamic `/src/...` imports need the Vite dev server).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

const MSG_ROWS = 'div.flex.mb-3:is(.justify-end, .justify-start)';
const ROW_DELETE = MSG_ROWS + ' button:text-is("delete")';

test.describe('Chat delete message', () => {
    let testInfo = {};
    test.beforeAll(async ({ request }) => { testInfo = await ensureSharedTestUser(request); });

    test('deleting one message in edit mode and saving leaves the other message', async ({ page }) => {
        test.setTimeout(120000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Create a session carrying two messages (user + assistant) directly through the client API.
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
            let chatCfg = await am7chat.makeChat('DbgDel-' + ts, 'qwen3-vl:8b-instruct', 'http://192.168.1.42:11434', 'OLLAMA');
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
            let ok = await pg.patchObject(sess);
            am7client.clearCache(sessType);
            // ConversationManager.loadSessions (ConversationManager.js:22-63) reads the chatRequest list through
            // the client + server caches; drop both so the just-created session is listed.
            await Promise.resolve(am7client.clearCache('olio.llm.chatRequest', false));
            return { name: req.name, patched: ok };
        });
        expect(setup.error, 'session setup: ' + JSON.stringify(setup)).toBeUndefined();
        console.log('[chatDelete] session ' + setup.name + ' patched=' + JSON.stringify(setup.patched));

        // Navigate to chat and select the session
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });

        let sessionsBtn = page.locator('button[title="Sessions"]');
        await expect(sessionsBtn).toBeVisible({ timeout: 15000 });
        await sessionsBtn.click();

        // Session rows (chat/ConversationManager.js:118-155): `div.cursor-pointer` holding the delete_outline
        // span and a `span.text-sm` with the name. A bare `div` + hasText resolves to the outermost page
        // wrapper and clicking it selects nothing (the chat then stays on the auto-selected first session).
        await page.locator('input[placeholder="Filter sessions..."]').fill(setup.name);
        let sessionItem = page.locator('div.cursor-pointer:has(span[title="Delete session"])', { hasText: setup.name });
        await expect(sessionItem, 'the seeded session must be listed').toHaveCount(1, { timeout: 15000 });
        await sessionItem.click();
        await expect(page.locator('h2', { hasText: setup.name }), 'chat header must show the selected session').toBeVisible({ timeout: 15000 });

        // Both messages render before editing (history comes back through /rest/chat/history).
        let rows = page.locator(MSG_ROWS);
        await expect(rows, 'both seeded messages must render').toHaveCount(2, { timeout: 30000 });
        await expect(rows.nth(0)).toContainText('Msg to delete');
        await expect(rows.nth(1)).toContainText('Reply to delete');

        // Collect console errors and write-type network requests during the edit flow
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

        // Enter edit mode (chat.js:903-907)
        let editBtn = page.locator('button[title="Edit messages"]');
        await expect(editBtn, 'Edit messages button must render for a selected session').toBeVisible({ timeout: 5000 });
        await editBtn.click();

        // Per-message delete buttons (chat.js:580-587) — NOT the toolbar "Clear history" (delete_sweep).
        let deleteBtns = page.locator(ROW_DELETE);
        await expect(deleteBtns, 'one delete button per message row in edit mode').toHaveCount(2, { timeout: 5000 });
        await deleteBtns.first().click();
        // The marked row flips its button to "restore" and is struck through (chat.js:574, :587)
        await expect(rows.first()).toHaveClass(/line-through/);
        await expect(rows.first().locator('button:text-is("restore")')).toBeVisible();

        let saveBtn = page.locator('button[title="Save edits"]');
        await expect(saveBtn, 'Save edits button must render in edit mode').toBeVisible({ timeout: 3000 });
        await saveBtn.click();

        // saveEdits (chat.js:441-507): search session -> PATCH -> re-peek via /rest/chat/history. A history call
        // can also be in flight from the session selection, so require one issued AFTER the PATCH.
        const patchIdx = () => networkCalls.findIndex(c => c.startsWith('PATCH /rest/model'));
        const historyAfterPatch = () => {
            let p = patchIdx();
            return p >= 0 && networkCalls.slice(p + 1).some(c => c.startsWith('POST /rest/chat/history'));
        };
        await expect.poll(historyAfterPatch, {
            message: 'save must PATCH the session and then re-fetch history; calls so far: ' + JSON.stringify(networkCalls),
            timeout: 30000
        }).toBe(true);
        console.log('[chatDelete] console errors/warnings:', JSON.stringify(errors));
        console.log('[chatDelete] write calls during edit:', JSON.stringify(networkCalls));

        expect(networkCalls.some(c => c.includes('/rest/chat/clear')), 'the edit flow must never hit /rest/chat/clear').toBe(false);

        // Exactly the surviving message remains, and it is the one that was not deleted.
        await expect(rows, 'one of the two messages must remain after delete + save').toHaveCount(1, { timeout: 30000 });
        await expect(rows.first()).toContainText('Reply to delete');
        await expect(page.locator(MSG_ROWS, { hasText: 'Msg to delete' })).toHaveCount(0);
        // Edit mode has been exited: no textareas, no per-row delete buttons.
        await expect(page.locator(ROW_DELETE)).toHaveCount(0);
        await expect(page.locator('button[title="Edit messages"]')).toBeVisible();

        let msgCount = await rows.count();
        console.log('[chatDelete] messages visible in UI after save:', msgCount);
        expect(errors.filter(e => e.startsWith('error:')), 'no console errors during edit flow:\n' + errors.join('\n')).toHaveLength(0);
    });
});
