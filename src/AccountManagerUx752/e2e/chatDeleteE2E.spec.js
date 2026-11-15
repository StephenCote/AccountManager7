/**
 * Chat message delete E2E — all on one page load.
 * Creates session via New dialog, injects messages, forces history reload,
 * then exercises edit mode delete.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Chat message delete E2E', () => {
    let testInfo = {};
    test.beforeAll(async ({ request }) => { testInfo = await ensureSharedTestUser(request); });

    test('delete a message via edit mode UI', async ({ page }) => {
        test.setTimeout(120000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let consoleLog = [];
        page.on('console', msg => consoleLog.push(msg.type() + ': ' + msg.text()));

        // Navigate to chat
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Dismiss wizard
        let wizard = page.locator('text=Chat Library Setup');
        if (await wizard.isVisible({ timeout: 2000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
            await page.waitForTimeout(500);
        }

        // Open sidebar, create new session via UI
        await page.locator('button[title="Sessions"]').click();
        await page.waitForTimeout(500);
        let newBtn = page.locator('button:has-text("New Session")').or(page.locator('button:has-text("New")'));
        await expect(newBtn.first()).toBeVisible({ timeout: 10000 });
        await newBtn.first().click();
        let dialog = page.locator('text=New Chat Session');
        await expect(dialog).toBeVisible({ timeout: 10000 });
        // The dialog opens empty and then runs loadNewSessionDefaults() + updateNewSessionDefaultName()
        // (features/chat.js:1239-1248 / :1092-1128): up to six REST calls, after which it assigns the
        // auto-generated default name unconditionally. A name typed before that chain settles is
        // overwritten (KI-98) — under the 2-worker gate it took longer than the fixed 2 s wait that
        // used to be here and POST /rest/chat/new created the default name instead of DelE2E-<ts>
        // (§24 U-6 full gate RUN 3, 107-u6-verify3.phases.log:2271; retry ok :2300). Wait for the
        // default name to appear — what a user sees before typing — then replace it.
        let nameInput = page.locator('input[placeholder="Session name"]');
        await expect(nameInput, 'default session name populated').not.toHaveValue('', { timeout: 30000 });
        let ts = Date.now().toString(36);
        let sessionName = 'DelE2E-' + ts;
        await nameInput.fill(sessionName);
        await expect(nameInput).toHaveValue(sessionName);
        await page.locator('button:has-text("Create")').click();
        await page.waitForTimeout(3000);

        // Inject fake messages and force history reload — all in one evaluate
        let result = await page.evaluate(async (sName) => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { page: pg } = await import('/src/core/pageClient.js');
            let { default: CM } = await import('/src/chat/ConversationManager.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { applicationPath } = await import('/src/core/config.js');

            // Find our session
            await CM.refresh();
            let sessions = CM.getSessions() || [];
            let active = sessions.find(s => s.name === sName);
            if (!active) return { error: 'Not found', names: sessions.slice(0,3).map(s=>s.name) };
            if (!active.session) return { error: 'No session FK', keys: Object.keys(active) };

            // Fetch openaiRequest and inject messages
            let q = am7client.newQuery(active.sessionType);
            q.field('id', active.session.id);
            q.cache(false);
            q.entity.request.push('id', 'objectId', 'messages');
            let qr = await pg.search(q);
            let sess = qr?.results?.[0];
            if (!sess) return { error: 'openaiRequest not found' };

            if (!sess.messages) sess.messages = [];
            sess.messages.push({ role: 'user', content: 'KEEP-MSG-1' });
            sess.messages.push({ role: 'assistant', content: 'DELETE-ME' });
            sess.messages.push({ role: 'user', content: 'KEEP-MSG-2' });
            if (!sess[am7model.jsonModelKey]) sess[am7model.jsonModelKey] = active.sessionType;
            await pg.patchObject(sess);

            // Clear chat cache for this session
            await fetch(applicationPath + '/rest/chat/clear', {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ schema: 'olio.llm.chatRequest', objectId: active.objectId, uid: pg.uid() })
            }).catch(() => {});

            // Fetch history to verify
            am7client.clearCache(null, true);
            let history = await LLMConnector.getHistory(active);
            return { ok: true, historyMsgs: history?.messages?.length || 0, activeOid: active.objectId };
        }, sessionName);

        console.log('Inject result:', JSON.stringify(result));
        expect(result.error).toBeUndefined();
        expect(result.historyMsgs).toBeGreaterThan(0);

        // Now click our session in the sidebar to trigger pickSession → doPeek
        // The sidebar should still be open from before
        let filterInput = page.locator('input[placeholder*="Filter"]');
        if (await filterInput.isVisible({ timeout: 2000 }).catch(() => false)) {
            await filterInput.fill(sessionName);
            await page.waitForTimeout(500);
        }
        // Click the session item - the sidebar row (ConversationManager.sessionItemView: div.cursor-pointer.group,
        // onclick selectSession), not the last div containing the name, which is the main pane's title container
        // once the new session is active: clicking that issues no request, so the injected history is never
        // reloaded (archive §4.6 run-2 triage, §6.C 145 (c)(1); applied §24 U-6).
        let sessionItem = page.locator('div.cursor-pointer.group').filter({ hasText: sessionName }).first();
        await expect(sessionItem).toBeVisible({ timeout: 5000 });
        // Double-click to ensure selection (single click may not trigger if already selected)
        await sessionItem.click();
        await page.waitForTimeout(1000);
        await sessionItem.click();
        await page.waitForTimeout(5000);

        // Check messages
        let keepVisible = await page.locator('text=KEEP-MSG-1').first().isVisible({ timeout: 10000 }).catch(() => false);
        console.log('KEEP-MSG-1 visible:', keepVisible);

        if (!keepVisible) {
            await screenshot(page, 'chat-delete-no-msgs');
            let saveLogs = consoleLog.filter(l => l.includes('saveEdits') || l.includes('error') || l.includes('peek') || l.includes('history'));
            console.log('Relevant logs:', JSON.stringify(saveLogs.slice(-10)));
        }

        expect(keepVisible).toBe(true);

        // === EDIT MODE ===
        await page.locator('button[title="Edit messages"]').click();
        await page.waitForTimeout(500);

        // Click the in-message "delete" button for DELETE-ME
        // These are small text buttons inside message bubbles, not toolbar icon buttons
        // The text is exactly "delete" with class containing "text-red"
        let msgDeleteBtns = page.locator('button.text-xs:has-text("delete")');
        let count = await msgDeleteBtns.count();
        console.log('Message delete buttons:', count);
        // Click the second one (index 1 = DELETE-ME assistant reply)
        let targetIdx = Math.min(1, count - 1);
        await msgDeleteBtns.nth(targetIdx).click();
        console.log('Clicked message delete button', targetIdx);
        await page.waitForTimeout(300);

        // Save
        consoleLog = [];
        await page.locator('button[title="Save edits"]').click();
        await page.waitForTimeout(3000);

        let saveLogs = consoleLog.filter(l => l.includes('saveEdits'));
        console.log('Save logs:', JSON.stringify(saveLogs));
        await screenshot(page, 'chat-delete-result');

        let deleteGone = !(await page.locator('text=DELETE-ME').first().isVisible().catch(() => false));
        let keepStays = await page.locator('text=KEEP-MSG-1').first().isVisible().catch(() => false);
        console.log('DELETE-ME gone:', deleteGone, 'KEEP-MSG-1 stays:', keepStays);

        expect(deleteGone).toBe(true);
        expect(keepStays).toBe(true);
    });
});
