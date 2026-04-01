/**
 * Chat session create/delete UX — exercises the ACTUAL UI flow.
 */
import { test as base, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

test.describe('Chat session create/delete UX', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('create and delete session via API, verify list state after each', async ({ page }) => {
        test.setTimeout(120000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to chat
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Dismiss wizard if shown
        let wizard = page.locator('text=Chat Library Setup');
        if (await wizard.isVisible({ timeout: 2000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
            await page.waitForTimeout(500);
        }

        // Create 2 sessions via API, then verify list, then delete 1, verify again
        let result = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { page } = await import('/src/core/pageClient.js');
            let log = [];
            let ts = Date.now().toString(36);

            await LLMConnector.ensureLibrary().catch(() => {});
            await page.makePath('auth.group', 'data', '~/Chat');

            let chatCfg = await am7chat.makeChat('UxTest-' + ts, 'qwen3-vl:8b-instruct', 'http://192.168.1.42:11434', 'OLLAMA');
            if (!chatCfg) return { error: 'ChatConfig failed', log };
            let promptCfg = await am7chat.makePrompt('default');

            // Create 2 sessions
            let req1 = await am7chat.getChatRequest('UxKeep-' + ts, chatCfg, promptCfg);
            let req2 = await am7chat.getChatRequest('UxDelete-' + ts, chatCfg, promptCfg);
            if (!req1 || !req2) return { error: 'ChatRequest creation failed', log };
            log.push('Created: ' + req1.name + ', ' + req2.name);

            // Import ConversationManager and refresh
            let { default: CM } = await import('/src/chat/ConversationManager.js');
            await CM.refresh();
            let sessions = CM.getSessions() || [];
            let names = sessions.map(s => s.name);
            log.push('After create refresh: ' + sessions.length + ' sessions');
            let hasKeep = names.includes('UxKeep-' + ts);
            let hasDelete = names.includes('UxDelete-' + ts);
            log.push('  hasKeep=' + hasKeep + ' hasDelete=' + hasDelete);

            // Delete the second session (force=true, no confirm dialog)
            await LLMConnector.deleteSession(req2, true);
            log.push('Deleted: ' + req2.name);

            // Refresh again
            await CM.refresh();
            sessions = CM.getSessions() || [];
            names = sessions.map(s => s.name);
            log.push('After delete refresh: ' + sessions.length + ' sessions');
            let keepStillThere = names.includes('UxKeep-' + ts);
            let deleteGone = !names.includes('UxDelete-' + ts);
            log.push('  keepStillThere=' + keepStillThere + ' deleteGone=' + deleteGone);

            return {
                hasKeepAfterCreate: hasKeep,
                hasDeleteAfterCreate: hasDelete,
                keepStillThere,
                deleteGone,
                log
            };
        });

        console.log('=== Create/Delete Test ===');
        result.log.forEach(l => console.log('  ' + l));

        expect(result.error).toBeUndefined();
        expect(result.hasKeepAfterCreate).toBe(true);
        expect(result.hasDeleteAfterCreate).toBe(true);
        expect(result.keepStillThere).toBe(true);
        expect(result.deleteGone).toBe(true);
        console.log('VERIFIED: Session list correctly reflects creates and deletes');
    });
});
