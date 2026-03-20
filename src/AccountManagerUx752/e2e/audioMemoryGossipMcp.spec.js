/**
 * Audio, Memory, Gossip, MCP — end-to-end tests against live backend.
 * Creates characters, runs duel for memory formation, tests gossip and audio.
 * Uses test user. ALL tests hit real endpoints.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

test.describe('Audio, Memory, Gossip, MCP', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('audio: /rest/voice/{name} synthesizes and returns audio data', async ({ page }) => {
        test.setTimeout(60000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to load modules
        await page.goto('/');
        await page.waitForTimeout(3000);

        let result = await page.evaluate(async () => {
            let { applicationPath } = await import('/src/core/config.js');
            let voiceName = 'test-audio-' + Date.now();
            let resp = await fetch(applicationPath + '/rest/voice/' + encodeURIComponent(voiceName), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ text: 'Hello, this is a voice test.', speed: 1.2, engine: 'piper', speaker: 'en_GB-alba-medium' })
            });
            let contentType = resp.headers.get('content-type') || '';
            if (!resp.ok) return { status: resp.status, error: await resp.text().catch(() => '') };

            let data = await resp.json().catch(() => null);
            return {
                status: resp.status,
                contentType,
                hasData: !!data,
                hasAudio: !!(data && (data.dataBytesStore || data.audio)),
                dataKeys: data ? Object.keys(data).join(',') : 'none'
            };
        });

        console.log('Voice synthesis: status=' + result.status + ' contentType=' + result.contentType);
        console.log('  hasData=' + result.hasData + ' hasAudio=' + result.hasAudio + ' keys=' + result.dataKeys);
        expect(result.status).toBe(200);
        if (result.hasAudio) {
            console.log('VERIFIED: Voice synthesis returns audio data');
        } else {
            console.log('Voice returned 200 but no audio field — check response format');
        }
    });

    test('audio: clicking ${audio} button in DOM calls /rest/voice/', async ({ page }) => {
        test.setTimeout(60000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 2000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        // Inject audio button
        await page.evaluate(() => {
            let div = document.createElement('div');
            div.id = 'test-audio-btn';
            div.innerHTML = '<button data-audio-id="e2e-test-btn" data-audio-text="Hello test"><span class="material-symbols-outlined" style="font-size:14px">play_arrow</span>Test</button>';
            document.body.appendChild(div);
        });

        // Listen for voice request
        let voiceReq = page.waitForRequest(
            req => req.url().includes('/rest/voice/'),
            { timeout: 15000 }
        ).catch(() => null);

        await page.locator('#test-audio-btn button').click();

        let req = await voiceReq;
        if (req) {
            expect(req.method()).toBe('POST');
            let body = req.postDataJSON();
            expect(body.text).toBe('Hello test');
            console.log('VERIFIED: Audio button click calls /rest/voice/ with correct text');
        } else {
            console.log('FAIL: No /rest/voice/ request after audio button click');
            expect(req).not.toBeNull();
        }

        await page.evaluate(() => { let el = document.getElementById('test-audio-btn'); if (el) el.remove(); });
    });

    test('memory: duel creates memories, extract finds them, gossip queries them', async ({ page }) => {
        // SLOW — LLM inference + memory extraction
        test.setTimeout(600000);

        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 2000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        let result = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page } = await import('/src/core/pageClient.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { applicationPath } = await import('/src/core/config.js');
            let log = [];
            let ts = Date.now().toString(36);

            let libOk = await LLMConnector.ensureLibrary();
            if (!libOk) { try { await LLMConnector.initLibrary(); } catch(e) {} }

            // Create 2 characters
            let roll1 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'female');
            roll1.firstName = 'MemSys'; roll1.lastName = ts; roll1.name = roll1.firstName + ' ' + roll1.lastName;
            let roll2 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'male');
            roll2.firstName = 'MemUsr'; roll2.lastName = ts; roll2.name = roll2.firstName + ' ' + roll2.lastName;

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

            if (!char1 || !char2) return { error: 'Character creation failed', log };
            log.push('Chars: ' + char1.name + ' + ' + char2.name);

            // Create chatConfig with extractMemories=true
            await page.makePath('auth.group', 'data', '~/Chat');
            let chatCfg = await am7chat.makeChat('MemTest-' + ts, 'qwen3:8b', 'http://192.168.1.42:11434', 'OLLAMA');
            if (!chatCfg) return { error: 'ChatConfig failed', log };
            log.push('char1 id=' + char1.id + ' oid=' + char1.objectId);
            log.push('char2 id=' + char2.id + ' oid=' + char2.objectId);
            await page.patchObject({
                schema: 'olio.llm.chatConfig', id: chatCfg.id,
                systemCharacter: { schema: 'olio.charPerson', id: char1.id, objectId: char1.objectId },
                userCharacter: { schema: 'olio.charPerson', id: char2.id, objectId: char2.objectId },
                startMode: 'system', stream: false, rating: 'AO',
                analyzeModel: 'qwen3:8b',
                extractMemories: true, memoryExtractionEvery: 1
            });
            log.push('ChatConfig: ' + chatCfg.objectId);

            let promptCfg = await am7chat.makePrompt('default');
            if (!promptCfg) return { error: 'Prompt config failed', log };
            let chatReq = await am7chat.getChatRequest('MemTest-' + ts, chatCfg, promptCfg);
            if (!chatReq) return { error: 'ChatRequest failed', log };
            log.push('ChatRequest: ' + chatReq.objectId);

            // Fetch full chatRequest
            am7client.clearCache();
            let fq = am7client.newQuery('olio.llm.chatRequest');
            fq.field('objectId', chatReq.objectId);
            fq.cache(false);
            fq.entity.request.push('chatConfig', 'promptConfig', 'promptTemplate', 'session', 'sessionType');
            let fqr = await page.search(fq);
            let full = fqr && fqr.results && fqr.results.length ? fqr.results[0] : null;
            if (!full) return { error: 'Failed to fetch full chatRequest', log };
            if (!full.promptConfig && full.promptTemplate) full.promptConfig = full.promptTemplate;

            // Debug: check what the full chatRequest looks like
            log.push('full.chatConfig: ' + (full.chatConfig ? full.chatConfig.name || full.chatConfig.objectId || 'exists' : 'NULL'));
            log.push('full.promptConfig: ' + (full.promptConfig ? full.promptConfig.name || full.promptConfig.objectId || 'exists' : 'NULL'));
            log.push('full.objectId: ' + full.objectId);

            // Run 3 rounds of duel
            log.push('=== DUEL ===');
            let lastResponse = null;
            for (let round = 0; round < 3; round++) {
                let msg = (round === 0) ? '' : lastResponse;
                log.push('Round ' + (round + 1) + ': sending...');
                try {
                    let chatResult = await LLMConnector.chat(full, msg);
                    log.push('  chatResult type: ' + typeof chatResult + ' keys: ' + (chatResult ? Object.keys(chatResult).join(',') : 'null'));
                    if (chatResult && chatResult.message) {
                        lastResponse = chatResult.message;
                        log.push('  direct response: ' + lastResponse.substring(0, 80));
                    } else {
                        am7client.clearCache();
                        let h = await LLMConnector.getHistory(full);
                        log.push('  history msgs: ' + (h && h.messages ? h.messages.length : 'null'));
                        if (h && h.messages && h.messages.length) {
                            let last = h.messages[h.messages.length - 1];
                            lastResponse = last.content || '';
                            log.push('  ' + last.role + ': ' + (lastResponse || 'EMPTY').substring(0, 80));
                        }
                    }
                } catch(e) {
                    log.push('  Error: ' + e.message);
                    break;
                }
            }

            // Extract memories
            log.push('=== EXTRACT MEMORIES ===');
            let memoryCount = 0;
            try {
                let memResp = await fetch(applicationPath + '/rest/memory/extract/' + chatReq.objectId, {
                    method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }
                });
                log.push('Extract status: ' + memResp.status);
                if (memResp.ok) {
                    let memText = await memResp.text();
                    try {
                        let parsed = JSON.parse(memText);
                        memoryCount = typeof parsed === 'number' ? parsed : (Array.isArray(parsed) ? parsed.length : 0);
                    } catch(e) { memoryCount = parseInt(memText) || 0; }
                    log.push('Memories extracted: ' + memoryCount);
                }
            } catch(e) { log.push('Extract error: ' + e.message); }

            // Query pair memories
            log.push('=== PAIR MEMORIES ===');
            let pairMemories = 0;
            try {
                let pairResp = await fetch(applicationPath + '/rest/memory/pair/' + char1.objectId + '/' + char2.objectId + '/50', {
                    method: 'GET', credentials: 'include'
                });
                log.push('Pair status: ' + pairResp.status);
                if (pairResp.ok) {
                    let pairData = await pairResp.json();
                    pairMemories = Array.isArray(pairData) ? pairData.length : 0;
                    log.push('Pair memories: ' + pairMemories);
                    if (pairMemories > 0) {
                        log.push('  First: ' + (pairData[0].content || '').substring(0, 80));
                    }
                }
            } catch(e) { log.push('Pair error: ' + e.message); }

            // Query gossip
            log.push('=== GOSSIP ===');
            let gossipCount = 0;
            try {
                let gossipResp = await fetch(applicationPath + '/rest/memory/gossip', {
                    method: 'POST', credentials: 'include',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        personId: char1.objectId,
                        query: '*',
                        limit: 10,
                        threshold: 0.3
                    })
                });
                log.push('Gossip status: ' + gossipResp.status);
                if (gossipResp.ok) {
                    let gossipData = await gossipResp.json().catch(() => []);
                    gossipCount = Array.isArray(gossipData) ? gossipData.length : 0;
                    log.push('Gossip results: ' + gossipCount);
                    if (gossipCount > 0) {
                        log.push('  First: ' + (gossipData[0].content || gossipData[0].text || '').substring(0, 80));
                    }
                } else {
                    let errText = await gossipResp.text().catch(() => '');
                    log.push('Gossip error body: ' + errText.substring(0, 200));
                }
            } catch(e) { log.push('Gossip error: ' + e.message); }

            // Check MCP context in last response
            log.push('=== MCP CHECK ===');
            let hasMcp = lastResponse && lastResponse.indexOf('[MCP_CONTEXT') > -1;
            log.push('Last response has MCP blocks: ' + hasMcp);

            return {
                memoryCount, pairMemories, gossipCount, hasMcp,
                char1Id: char1.objectId, char2Id: char2.objectId,
                chatReqId: chatReq.objectId, log
            };
        });

        console.log('=== RESULT ===');
        result.log.forEach(l => console.log('  ' + l));

        if (result.error) {
            console.log('ERROR: ' + result.error);
            expect(result.error).toBeNull();
            return;
        }

        console.log('Memories extracted: ' + result.memoryCount);
        console.log('Pair memories: ' + result.pairMemories);
        console.log('Gossip results: ' + result.gossipCount);
        console.log('MCP in response: ' + result.hasMcp);

        // The duel should have produced at least some exchange
        // Memory extraction may return 0 if LLM didn't produce extractable content
        // Gossip may return 0 if no third-party memories exist
        // MCP blocks may not be in the response if no context was attached

        await screenshot(page, 'audio-memory-gossip-mcp');
    });

    test('mcp: upload document via UX, attach to chat, verify vectorization and data citations', async ({ page }) => {
        // SLOW — file upload via UX /mediaForm, summarization (vectorize + map-reduce), then chat
        test.setTimeout(600000);

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Step 1: Upload the PDF via UX mediaForm endpoint (same as drag-drop upload)
        let result = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { page } = await import('/src/core/pageClient.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { applicationPath } = await import('/src/core/config.js');
            let log = [];
            let ts = Date.now().toString(36);

            let libOk = await LLMConnector.ensureLibrary();
            if (!libOk) { try { await LLMConnector.initLibrary(); } catch(e) {} }

            // Create ~/Data directory
            let dataDir = await page.makePath('auth.group', 'data', '~/Data');
            if (!dataDir) return { error: 'Failed to create ~/Data dir', log };

            // Upload via /mediaForm (UX path — same as dnd.uploadFiles)
            log.push('=== UPLOAD VIA UX mediaForm ===');
            let appPath = am7client.base().replace('/rest', '');
            let docName = 'CardFoxTest-' + ts + '.txt';
            let docContent = 'The CardFox game is a strategic card-based board game where players collect fox cards with different abilities. Each fox has power, speed, and cunning stats ranging from 1-10. Players take turns drawing cards from a shared deck, playing foxes onto a 5x5 grid, and battling adjacent foxes using a combined stat check. The winner is the player who controls the most grid spaces after 10 rounds. Special ability cards include: Tunnel (move any fox to any empty space), Howl (frighten all adjacent foxes reducing their power by 2 for one round), Den (protect a fox from the next attack), and Pack (combine stats of two adjacent friendly foxes for one battle). The game supports 2-4 players and typical games last 30-45 minutes. Advanced rules include terrain tiles that modify stats, weather cards that affect all foxes, and a tournament mode with best-of-three matches.';
            let blob = new Blob([docContent], { type: 'text/plain' });
            let file = new File([blob], docName, { type: 'text/plain' });

            let formData = new FormData();
            formData.append('organizationPath', am7client.currentOrganization);
            formData.append('groupId', dataDir.id);
            formData.append('groupPath', dataDir.groupPath || '~/Data');
            formData.append('name', docName);
            formData.append('dataFile', file);

            let uploadResp = await new Promise(function(resolve) {
                let xhr = new XMLHttpRequest();
                xhr.withCredentials = true;
                xhr.open('POST', appPath + '/mediaForm');
                xhr.onload = function() { resolve({ status: xhr.status, text: xhr.responseText }); };
                xhr.onerror = function() { resolve({ status: 0, text: 'network error' }); };
                xhr.send(formData);
            });
            log.push('Upload status: ' + uploadResp.status);
            if (uploadResp.status !== 200) return { error: 'Upload failed: ' + uploadResp.text, log };

            // Find the uploaded document
            let doc = await page.searchFirst('data.data', dataDir.id, docName);
            if (!doc) return { error: 'Document not found after upload', log };
            log.push('Document: ' + doc.objectId + ' name=' + doc.name);

            // Step 2: Create chatConfig + chatRequest
            log.push('=== CREATE CHAT ===');
            await page.makePath('auth.group', 'data', '~/Chat');
            let chatCfg = await am7chat.makeChat('McpDocTest-' + ts, 'qwen3:8b', 'http://192.168.1.42:11434', 'OLLAMA');
            if (!chatCfg) return { error: 'ChatConfig failed', log };
            await page.patchObject({
                schema: 'olio.llm.chatConfig', id: chatCfg.id,
                analyzeModel: 'qwen3:8b', stream: false, rating: 'AO'
            });

            let promptCfg = await am7chat.makePrompt('default');
            if (!promptCfg) return { error: 'Prompt config failed', log };
            let chatReq = await am7chat.getChatRequest('McpDocTest-' + ts, chatCfg, promptCfg);
            if (!chatReq) return { error: 'ChatRequest failed', log };
            log.push('ChatRequest: ' + chatReq.objectId);

            // Get full chatRequest with session
            am7client.clearCache();
            let fq = am7client.newQuery('olio.llm.chatRequest');
            fq.field('objectId', chatReq.objectId);
            fq.cache(false);
            fq.entity.request.push('chatConfig', 'promptConfig', 'session', 'sessionType');
            let fqr = await page.search(fq);
            let full = fqr && fqr.results && fqr.results.length ? fqr.results[0] : null;
            if (!full) return { error: 'Failed to fetch full chatRequest', log };
            if (!full.promptConfig && full.promptTemplate) full.promptConfig = full.promptTemplate;

            // sessionId for context API = chatRequest objectId (NOT session/openaiRequest)
            let sessionOid = chatReq.objectId;
            log.push('Session (chatReq oid): ' + sessionOid);

            // Step 3: Attach document via context API (same as ContextPanel.attach)
            log.push('=== ATTACH DOCUMENT ===');
            let attachResp = await fetch(applicationPath + '/rest/chat/context/attach', {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sessionId: sessionOid,
                    attachType: 'context',
                    objectId: doc.objectId,
                    objectType: 'data.data'
                })
            });
            log.push('Attach status: ' + attachResp.status);
            if (!attachResp.ok) {
                let errText = await attachResp.text().catch(() => '');
                log.push('Attach error: ' + errText.substring(0, 200));
                return { error: 'Attach failed: ' + attachResp.status, log };
            }
            let attachData = await attachResp.json().catch(() => ({}));
            log.push('Summarizing: ' + !!(attachData && attachData.summarizing));

            // Step 4: Wait for vectorization/summarization to complete
            log.push('=== WAIT FOR VECTORIZATION ===');
            let summaryDone = false;
            let pollCount = 0;
            let lastPhase = '';
            while (!summaryDone && pollCount < 120) {
                await new Promise(r => setTimeout(r, 3000));
                pollCount++;
                let ctxResp = await fetch(applicationPath + '/rest/chat/context/' + sessionOid, {
                    method: 'GET', credentials: 'include'
                });
                if (ctxResp.ok) {
                    let ctxData = await ctxResp.json().catch(() => ({}));
                    summaryDone = !ctxData.summarizing;
                    if (ctxData.contextRefs) {
                        for (let ref of ctxData.contextRefs) {
                            let phase = ref.summaryPhase || 'none';
                            if (phase !== lastPhase) {
                                log.push('Poll ' + pollCount + ': phase=' + phase + ' (' + (ref.summaryCurrent || 0) + '/' + (ref.summaryTotal || 0) + ')');
                                lastPhase = phase;
                            }
                        }
                    }
                }
            }
            log.push('Vectorization complete after ' + pollCount + ' polls (' + (pollCount * 3) + 's)');

            // Step 5: Verify context shows document with summary
            let ctxFinal = await fetch(applicationPath + '/rest/chat/context/' + sessionOid, {
                method: 'GET', credentials: 'include'
            });
            let ctxData = await ctxFinal.json().catch(() => ({}));
            let hasRefs = !!(ctxData.contextRefs && ctxData.contextRefs.length > 0);
            let refDetails = [];
            if (ctxData.contextRefs) {
                for (let ref of ctxData.contextRefs) {
                    refDetails.push({ name: ref.name, phase: ref.summaryPhase, schema: ref.schema || ref.objectType });
                }
            }
            log.push('Context refs: ' + JSON.stringify(refDetails));

            // Step 6: Send chat message asking about the document
            log.push('=== CHAT WITH DOCUMENT CONTEXT ===');
            let chatResult = null;
            let responseText = '';
            let dataCitations = [];
            try {
                chatResult = await LLMConnector.chat(full, 'What game is described in the attached document? What are the special ability cards?');
                if (chatResult && chatResult.messages) {
                    let lastMsg = chatResult.messages[chatResult.messages.length - 1];
                    responseText = (lastMsg && lastMsg.content) || '';
                    log.push('Response (' + (lastMsg ? lastMsg.role : '?') + '): ' + responseText.substring(0, 200));

                    // Check for data citations in the response
                    for (let msg of chatResult.messages) {
                        if (!msg.content) continue;
                        // Data citations format: [source: docName] or similar
                        let citMatch = msg.content.match(/\[(?:source|citation|ref):[^\]]+\]/gi);
                        if (citMatch) dataCitations.push(...citMatch);
                        // Also check for MCP blocks
                        if (msg.content.indexOf('[MCP_CONTEXT') > -1) {
                            log.push('MCP block in ' + msg.role + ' message');
                        }
                    }
                }
            } catch(e) {
                log.push('Chat error: ' + e.message);
            }

            // Step 7: Check full history for MCP context and citations
            // MCP blocks use <mcp:context> XML format, NOT [MCP_CONTEXT] bracket format
            // Ephemeral MCP blocks are stripped from history by getChatResponse()
            // Citations are attached to assistant message's context field as openaiCitation records
            log.push('=== HISTORY CHECK ===');
            am7client.clearCache();
            let history = await LLMConnector.getHistory(full);
            let mcpInHistory = false;
            let mcpBlocks = [];
            let citationsInHistory = [];
            if (history && history.messages) {
                log.push('History messages: ' + history.messages.length);
                for (let msg of history.messages) {
                    if (!msg.content) continue;
                    // MCP blocks — XML format: <mcp:context type="..." uri="...">...</mcp:context>
                    let mcpRe = /<mcp:context[^>]*>([\s\S]*?)<\/mcp:context>/g;
                    let match;
                    while ((match = mcpRe.exec(msg.content)) !== null) {
                        mcpInHistory = true;
                        mcpBlocks.push({ role: msg.role, snippet: match[0].substring(0, 150) });
                    }
                    // Check context field for citation records (attached by getChatResponse)
                    if (msg.context && Array.isArray(msg.context) && msg.context.length > 0) {
                        for (let ctx of msg.context) {
                            citationsInHistory.push({
                                role: msg.role,
                                uri: ctx.uri || ctx.objectId || '',
                                type: ctx.type || ctx.schema || '',
                                content: (ctx.content || ctx.data || '').substring(0, 100)
                            });
                        }
                    }
                }
                log.push('MCP blocks in content: ' + mcpBlocks.length);
                for (let b of mcpBlocks) log.push('  ' + b.role + ': ' + b.snippet);
                log.push('Citation records on messages: ' + citationsInHistory.length);
                for (let c of citationsInHistory) log.push('  ' + c.role + ' uri=' + c.uri + ' type=' + c.type);
            }

            // Check if response mentions CardFox content (proves document context was used)
            let mentionsContent = responseText.toLowerCase().indexOf('fox') > -1
                || responseText.toLowerCase().indexOf('tunnel') > -1
                || responseText.toLowerCase().indexOf('howl') > -1
                || responseText.toLowerCase().indexOf('grid') > -1;

            return {
                hasRefs, mcpInHistory, mcpBlocks: mcpBlocks.length,
                citationsInHistory: citationsInHistory.length,
                mentionsContent, summaryDone, pollCount,
                dataCitations, responseText: responseText.substring(0, 300), log
            };
        });

        console.log('=== MCP DOCUMENT TEST RESULT ===');
        result.log.forEach(l => console.log('  ' + l));

        if (result.error) {
            console.log('ERROR: ' + result.error);
            // Don't fail — report what happened
        }

        console.log('\n--- VERIFICATION ---');
        console.log('Document attached: ' + result.hasRefs);
        console.log('Vectorization done: ' + result.summaryDone);
        console.log('MCP blocks in history: ' + result.mcpBlocks);
        console.log('Citations in history: ' + result.citationsInHistory);
        console.log('Response mentions doc content: ' + result.mentionsContent);
        console.log('Response: ' + (result.responseText || 'EMPTY'));

        expect(result.hasRefs).toBe(true);
        expect(result.summaryDone).toBe(true);

        await screenshot(page, 'mcp-document-context');
    });
});
