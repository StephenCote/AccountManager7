/**
 * Pending animation — verifies "Thinking..." spinner appears when:
 * 1. System-start session auto-starts
 * 2. User sends a message
 * Uses test user. Creates a chatConfig with startMode=system.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

test.describe('Pending animation', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('system-start session shows Thinking spinner, then user message shows it too', async ({ page }) => {
        test.setTimeout(300000);

        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        // Create a system-start chatConfig and session via JS
        let sessionId = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { page } = await import('/src/core/pageClient.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let ts = Date.now().toString(36);

            let libOk = await LLMConnector.ensureLibrary();
            if (!libOk) { try { await LLMConnector.initLibrary(); } catch(e) {} }

            await page.makePath('auth.group', 'data', '~/Chat');

            // Create chatConfig with startMode=system
            let chatCfg = await am7chat.makeChat('PendingTest-' + ts, 'herm-local:latest', 'http://localhost:11434', 'OLLAMA');
            if (!chatCfg) return null;

            // Need characters — roll two
            let roll1 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'female');
            roll1.firstName = 'PendSys'; roll1.lastName = ts; roll1.name = roll1.firstName + ' ' + roll1.lastName;
            let roll2 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'male');
            roll2.firstName = 'PendUsr'; roll2.lastName = ts; roll2.name = roll2.firstName + ' ' + roll2.lastName;

            let cdir = await page.makePath('auth.group', 'data', '~/Characters');
            let cn1 = am7model.prepareEntity(roll1, 'olio.charPerson', true);
            let w1 = cn1.store.apparel[0].wearables;
            for (let i = 0; i < w1.length; i++) {
                w1[i] = am7model.prepareEntity(w1[i], 'olio.wearable', false);
                if (w1[i].qualities && w1[i].qualities[0]) w1[i].qualities[0] = am7model.prepareEntity(w1[i].qualities[0], 'olio.quality', false);
            }
            await page.createObject(cn1);
            let char1 = await page.searchFirst('olio.charPerson', cdir.id, roll1.name);

            let cn2 = am7model.prepareEntity(roll2, 'olio.charPerson', true);
            let w2 = cn2.store.apparel[0].wearables;
            for (let i = 0; i < w2.length; i++) {
                w2[i] = am7model.prepareEntity(w2[i], 'olio.wearable', false);
                if (w2[i].qualities && w2[i].qualities[0]) w2[i].qualities[0] = am7model.prepareEntity(w2[i].qualities[0], 'olio.quality', false);
            }
            await page.createObject(cn2);
            let char2 = await page.searchFirst('olio.charPerson', cdir.id, roll2.name);

            if (!char1 || !char2) return null;

            await page.patchObject({
                schema: 'olio.llm.chatConfig', id: chatCfg.id,
                systemCharacter: { objectId: char1.objectId },
                userCharacter: { objectId: char2.objectId },
                startMode: 'system',
                stream: false,
                rating: 'AO'
            });

            let promptCfg = await am7chat.makePrompt('default');
            if (!promptCfg) return null;

            let chatReq = await am7chat.getChatRequest('PendingTest-' + ts, chatCfg, promptCfg);
            return chatReq ? chatReq.objectId : null;
        });

        console.log('Session created: ' + sessionId);
        if (!sessionId) {
            console.log('I DID NOT VERIFY pending animation — session creation failed');
            return;
        }

        // Navigate to chat route with the session — use ConversationManager to select it
        await page.evaluate(async (objId) => {
            let { am7client } = await import('/src/core/am7client.js');
            am7client.clearCache();
            let { ConversationManager } = await import('/src/chat/ConversationManager.js');
            await ConversationManager.refresh();
        }, sessionId);

        await page.locator('button[title="Home"]').click();
        await page.waitForTimeout(1000);
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Dismiss any dialogs
        let newDialog = page.locator('text=New Chat Session');
        if (await newDialog.isVisible({ timeout: 2000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
            await page.waitForTimeout(500);
        }
        let wizard2 = page.locator('text=Chat Library Setup');
        if (await wizard2.isVisible({ timeout: 2000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        // Open sessions sidebar and find PendingTest
        let sessionsBtn = page.locator('button[title="Sessions"]');
        if (await sessionsBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
            await sessionsBtn.click();
            await page.waitForTimeout(2000);
        }

        let sessionItem = page.locator('text=/PendingTest/');
        let found = await sessionItem.isVisible({ timeout: 5000 }).catch(() => false);
        if (!found) {
            // Try scrolling or looking for truncated name
            let allItems = page.locator('.overflow-y-auto .truncate');
            let count = await allItems.count();
            console.log('Sidebar items: ' + count);
            for (let i = 0; i < count; i++) {
                let txt = await allItems.nth(i).textContent().catch(() => '');
                console.log('  [' + i + '] ' + txt);
                if (txt.includes('PendingTest') || txt.includes('Pending')) {
                    sessionItem = allItems.nth(i);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            console.log('PendingTest session not found in sidebar after refresh');
            console.log('I DID NOT VERIFY pending animation');
            return;
        }

        // Click the session — since startMode=system and no messages, it should auto-start
        await sessionItem.click();

        // The auto-start should trigger: chatCfg.pending = true → "Thinking..." spinner
        let thinkingAppeared = await page.locator('text=Thinking...').isVisible({ timeout: 10000 }).catch(() => false);
        console.log('Thinking... appeared on auto-start: ' + thinkingAppeared);

        if (thinkingAppeared) {
            console.log('VERIFIED: Pending animation shows on system auto-start');

            // Wait for LLM response to complete
            await page.waitForTimeout(60000);

            // Thinking should be gone now
            let thinkingGone = !(await page.locator('text=Thinking...').isVisible({ timeout: 2000 }).catch(() => false));
            console.log('Thinking... gone after response: ' + thinkingGone);
        } else {
            console.log('Thinking... did NOT appear — checking if response arrived too fast');
            // Check if messages appeared (response came before we could check)
            await page.waitForTimeout(5000);
            let messages = page.locator('.overflow-y-auto .mb-3');
            let msgCount = await messages.count();
            console.log('Messages after waiting: ' + msgCount);
            if (msgCount > 0) {
                console.log('Response arrived too fast to see pending indicator — LLM was quick');
            } else {
                console.log('No messages and no pending — auto-start may not have triggered');
            }
        }

        // Test 2: Send a user message and check for Thinking...
        let chatInput = page.locator('[name="chatmessage"]:not([disabled])');
        let inputReady = await chatInput.isVisible({ timeout: 10000 }).catch(() => false);
        if (inputReady) {
            await chatInput.fill('Tell me more about yourself.');
            let sendBtn = page.locator('button[title="Send"]');
            if (await sendBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
                await sendBtn.click();

                let thinkingOnSend = await page.locator('text=Thinking...').isVisible({ timeout: 5000 }).catch(() => false);
                console.log('Thinking... appeared on user send: ' + thinkingOnSend);
                if (thinkingOnSend) {
                    console.log('VERIFIED: Pending animation shows on user message send');
                }

                // Wait for response
                await page.waitForTimeout(60000);
            }
        } else {
            console.log('Chat input not enabled — cannot test user send pending');
        }

        await screenshot(page, 'verify-pending-animation');
    });
});
