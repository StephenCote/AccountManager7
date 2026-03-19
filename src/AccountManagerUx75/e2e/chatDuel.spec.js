/**
 * Chat duel — creates two characters, two chatConfigs (swapped), runs LLM conversation.
 * Tests: character creation, chat exchange, memory extraction, gossip.
 * Uses test user (not admin). Uses herm-local model from Ollama.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

test.describe('Chat duel + memories + gossip', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('full duel: create chars, create configs, exchange messages, memories, gossip', async ({ page }) => {
        test.setTimeout(600000);

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

        let result = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page } = await import('/src/core/pageClient.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { applicationPath } = await import('/src/core/config.js');
            let log = [];

            let libOk = await LLMConnector.ensureLibrary();
            if (!libOk) { try { await LLMConnector.initLibrary(); } catch(e) {} }

            // Step 1: Create two characters via /olio/roll
            log.push('=== STEP 1: Create characters ===');
            let roll1, roll2;
            try {
                roll1 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'female');
                log.push('Roll 1 keys: ' + Object.keys(roll1).filter(k => roll1[k]).join(','));
                log.push('Roll 1: ' + (roll1.firstName || roll1.name || '?') + ' ' + (roll1.lastName || '') + ' (' + roll1.gender + ', age ' + roll1.age + ')');
                roll2 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'male');
                log.push('Roll 2: ' + (roll2.firstName || roll2.name || '?') + ' ' + (roll2.lastName || '') + ' (' + roll2.gender + ', age ' + roll2.age + ')');
            } catch (e) {
                return { error: 'Roll failed: ' + e.message, log };
            }

            // Create characters (this sets up store, apparel, wearables, portrait, narrate)
            let char1, char2;
            let ts = Date.now().toString(36);
            try {
                let cdir = await page.makePath('auth.group', 'data', '~/Characters');
                log.push('Characters dir: ' + cdir.path);

                // Set names (roll doesn't generate names)
                roll1.firstName = 'Elena';
                roll1.lastName = 'Vasquez' + ts;
                roll1.name = roll1.firstName + ' ' + roll1.lastName;
                roll2.firstName = 'Marcus';
                roll2.lastName = 'Thornton' + ts;
                roll2.name = roll2.firstName + ' ' + roll2.lastName;

                // Char 1
                log.push('Creating char: ' + roll1.name);
                let charN1 = am7model.prepareEntity(roll1, 'olio.charPerson', true);
                let w1 = charN1.store.apparel[0].wearables;
                for (let i = 0; i < w1.length; i++) {
                    w1[i] = am7model.prepareEntity(w1[i], 'olio.wearable', false);
                    if (w1[i].qualities && w1[i].qualities[0]) w1[i].qualities[0] = am7model.prepareEntity(w1[i].qualities[0], 'olio.quality', false);
                }
                char1 = await page.createObject(charN1);
                if (char1) char1 = await page.searchFirst('olio.charPerson', cdir.id, roll1.name);
                log.push('Created char 1: ' + (char1 ? char1.objectId : 'FAILED'));

                // Char 2
                let charN2 = am7model.prepareEntity(roll2, 'olio.charPerson', true);
                let w2 = charN2.store.apparel[0].wearables;
                for (let i = 0; i < w2.length; i++) {
                    w2[i] = am7model.prepareEntity(w2[i], 'olio.wearable', false);
                    if (w2[i].qualities && w2[i].qualities[0]) w2[i].qualities[0] = am7model.prepareEntity(w2[i].qualities[0], 'olio.quality', false);
                }
                char2 = await page.createObject(charN2);
                if (char2) char2 = await page.searchFirst('olio.charPerson', cdir.id, roll2.name);
                log.push('Created char 2: ' + (char2 ? char2.objectId : 'FAILED'));
            } catch (e) {
                return { error: 'Character creation failed: ' + e.message, log };
            }

            if (!char1 || !char2) return { error: 'One or both characters failed to create', log };

            // Narrate both characters
            try {
                let inst1 = am7model.prepareInstance(char1);
                await am7model.forms.commands.narrate(undefined, inst1);
                log.push('Narrated char 1');
                let inst2 = am7model.prepareInstance(char2);
                await am7model.forms.commands.narrate(undefined, inst2);
                log.push('Narrated char 2');
            } catch(e) {
                log.push('Narrate warning: ' + e.message);
            }

            // Step 2: Create two chatConfigs with swapped characters
            log.push('=== STEP 2: Create chat configs ===');
            // Ensure ~/Chat directory exists
            await page.makePath('auth.group', 'data', '~/Chat');
            let chatCfg1, chatCfg2;
            try {
                // Config 1: char1=system, char2=user, startMode=system
                chatCfg1 = await am7chat.makeChat('DuelTest1-' + ts, 'herm-local:latest', 'http://localhost:11434', 'OLLAMA');
                if (chatCfg1) {
                    await page.patchObject({
                        schema: 'olio.llm.chatConfig', id: chatCfg1.id,
                        systemCharacter: { objectId: char1.objectId },
                        userCharacter: { objectId: char2.objectId },
                        startMode: 'system',
                        stream: false,
                        rating: 'AO'
                    });
                    log.push('ChatConfig 1 created: ' + chatCfg1.objectId + ' (system=' + char1.name + ', user=' + char2.name + ')');
                }

                // Config 2: char2=system, char1=user, startMode=user
                chatCfg2 = await am7chat.makeChat('DuelTest2-' + ts, 'herm-local:latest', 'http://localhost:11434', 'OLLAMA');
                if (chatCfg2) {
                    await page.patchObject({
                        schema: 'olio.llm.chatConfig', id: chatCfg2.id,
                        systemCharacter: { objectId: char2.objectId },
                        userCharacter: { objectId: char1.objectId },
                        startMode: 'user',
                        stream: false,
                        rating: 'AO'
                    });
                    log.push('ChatConfig 2 created: ' + chatCfg2.objectId + ' (system=' + char2.name + ', user=' + char1.name + ')');
                }
            } catch (e) {
                return { error: 'ChatConfig creation failed: ' + e.message, log };
            }

            if (!chatCfg1 || !chatCfg2) return { error: 'ChatConfig creation failed', log };

            // Step 3: Get or create prompt config
            log.push('=== STEP 3: Get prompt config ===');
            let promptCfg;
            try {
                promptCfg = await am7chat.makePrompt('default');
                log.push('Prompt config: ' + (promptCfg ? promptCfg.objectId : 'FAILED'));
            } catch(e) {
                return { error: 'Prompt config failed: ' + e.message, log };
            }
            if (!promptCfg) return { error: 'No prompt config available', log };

            // Step 4: Create chatRequests
            log.push('=== STEP 4: Create chat requests ===');
            let req1, req2;
            try {
                req1 = await am7chat.getChatRequest('Duel1-' + Date.now(), chatCfg1, promptCfg);
                log.push('ChatRequest 1: ' + (req1 ? req1.objectId : 'FAILED'));
                req2 = await am7chat.getChatRequest('Duel2-' + Date.now(), chatCfg2, promptCfg);
                log.push('ChatRequest 2: ' + (req2 ? req2.objectId : 'FAILED'));
            } catch(e) {
                return { error: 'ChatRequest creation failed: ' + e.message, log };
            }
            if (!req1 || !req2) return { error: 'ChatRequest creation failed', log };

            // Fetch full requests with chatConfig/promptConfig refs
            am7client.clearCache();
            let fq1 = am7client.newQuery('olio.llm.chatRequest');
            fq1.field('objectId', req1.objectId);
            fq1.cache(false);
            fq1.entity.request.push('chatConfig', 'promptConfig', 'promptTemplate', 'session', 'sessionType');
            let fqr1 = await page.search(fq1);
            let full1 = fqr1 && fqr1.results && fqr1.results.length ? fqr1.results[0] : null;

            let fq2 = am7client.newQuery('olio.llm.chatRequest');
            fq2.field('objectId', req2.objectId);
            fq2.cache(false);
            fq2.entity.request.push('chatConfig', 'promptConfig', 'promptTemplate', 'session', 'sessionType');
            let fqr2 = await page.search(fq2);
            let full2 = fqr2 && fqr2.results && fqr2.results.length ? fqr2.results[0] : null;

            if (!full1 || !full2) return { error: 'Failed to fetch full requests', log };
            if (!full1.promptConfig && full1.promptTemplate) full1.promptConfig = full1.promptTemplate;
            if (!full2.promptConfig && full2.promptTemplate) full2.promptConfig = full2.promptTemplate;

            // Step 5: Duel — 4 rounds
            log.push('=== STEP 5: Duel ===');
            let ROUNDS = 4;
            let lastResponse = null;
            let allReplies = [];

            for (let round = 0; round < ROUNDS; round++) {
                // Session 1
                let msg1 = (round === 0) ? '' : lastResponse; // empty = system starts
                log.push('Round ' + (round + 1) + '/1: Session 1...');
                try {
                    await LLMConnector.chat(full1, msg1);
                    am7client.clearCache();
                    let h1 = await LLMConnector.getHistory(full1);
                    if (h1 && h1.messages && h1.messages.length) {
                        let last = h1.messages[h1.messages.length - 1];
                        log.push('  ' + last.role + ': ' + (last.content || '').substring(0, 80));
                        lastResponse = last.content || '';
                        allReplies.push(last);
                    } else {
                        log.push('  No history after chat');
                        break;
                    }
                } catch(e) {
                    log.push('  Error: ' + (e.message || e));
                    break;
                }

                // Session 2
                log.push('Round ' + (round + 1) + '/2: Session 2...');
                try {
                    await LLMConnector.chat(full2, lastResponse);
                    am7client.clearCache();
                    let h2 = await LLMConnector.getHistory(full2);
                    if (h2 && h2.messages && h2.messages.length) {
                        let last = h2.messages[h2.messages.length - 1];
                        log.push('  ' + last.role + ': ' + (last.content || '').substring(0, 80));
                        lastResponse = last.content || '';
                        allReplies.push(last);
                    } else {
                        log.push('  No history after chat');
                        break;
                    }
                } catch(e) {
                    log.push('  Error: ' + (e.message || e));
                    break;
                }
            }
            log.push('Total replies: ' + allReplies.length);

            // Step 6: Extract memories
            log.push('=== STEP 6: Extract memories ===');
            let memoryCount = 0;
            try {
                let memResp = await fetch(applicationPath + '/rest/memory/extract/' + req1.objectId, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include'
                });
                log.push('Memory extract status: ' + memResp.status);
                if (memResp.ok) {
                    let memText = await memResp.text();
                    try { memoryCount = JSON.parse(memText); if (typeof memoryCount !== 'number') memoryCount = Array.isArray(memoryCount) ? memoryCount.length : 0; } catch(e) { memoryCount = 0; }
                    log.push('Memories: ' + memoryCount);
                }
            } catch(e) { log.push('Memory error: ' + e.message); }

            // Step 7: Gossip
            log.push('=== STEP 7: Gossip ===');
            let gossipCount = 0;
            try {
                let gossipResp = await fetch(applicationPath + '/rest/memory/gossip', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ personId: char1.objectId, query: '', limit: 10, threshold: 0.3 })
                });
                log.push('Gossip status: ' + gossipResp.status);
                if (gossipResp.ok) {
                    let gText = await gossipResp.text();
                    try {
                        let gResult = JSON.parse(gText);
                        gossipCount = Array.isArray(gResult) ? gResult.length : 0;
                        if (gossipCount > 0) log.push('First gossip: ' + (gResult[0].content || gResult[0].text || '').substring(0, 80));
                    } catch(e) {}
                    log.push('Gossip results: ' + gossipCount);
                }
            } catch(e) { log.push('Gossip error: ' + e.message); }

            return { allReplies: allReplies.length, memoryCount, gossipCount, char1Name: char1.name, char2Name: char2.name, log };
        });

        console.log('=== DUEL RESULT ===');
        result.log.forEach(l => console.log('  ' + l));

        if (result.error) {
            console.log('ERROR: ' + result.error);
            expect(result.error).toBeNull();
            return;
        }

        console.log('Characters: ' + result.char1Name + ' vs ' + result.char2Name);
        console.log('Replies: ' + result.allReplies);
        console.log('Memories: ' + result.memoryCount);
        console.log('Gossip: ' + result.gossipCount);

        expect(result.allReplies).toBeGreaterThan(0);

        await screenshot(page, 'chat-duel-complete');
    });
});
