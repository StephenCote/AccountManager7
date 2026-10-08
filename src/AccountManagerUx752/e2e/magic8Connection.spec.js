/**
 * Magic8 / chat connection migration — olio.llm.chatConfig no longer carries serverUrl/apiKey/
 * requestTimeout; the endpoint is a system.connection referenced by chatConfig.connection.
 *
 * Proves, as the shared NON-admin user, that:
 *  (a) LLMConnector.ensureConfig() persists a `connection` FK on the user's chatConfig (fresh create
 *      AND backfill of a pre-migration config), and the ref resolves to a connection with a serverUrl;
 *  (b) SessionDirector.runDiagnostics() resolves the "Open Chat" template's connection (no
 *      "missing serverUrl"/"missing connection" failure) and reports the resolved server;
 *  (c) am7chat.makeChat() creates a chatConfig that carries `connection`.
 *
 * Runs through the Vite dev server (:8899, proxied to the backend) because the tests dynamically
 * import /src/ modules, which the Docker-served dist bundle does not expose.
 */
import { test as base, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { captureConsole, assertNoConsoleErrors } from './helpers/console.js';
import { ensureSharedTestUser, ensureChatConfig, resolveChatRoute } from './helpers/api.js';

const test = base;
const E2E_CONN_NAME = 'e2e-chapbook-conn';   // the ~/Chat system.connection ensureChatConfig reconciles

test.describe('Chat connection migration (Magic8 / makeChat)', () => {
    let testInfo = {};
    let llmRoute = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        // Reconciles ~/Chat/e2e-chapbook-conn (serverUrl + dialect + apiKey) to the resolved LLM route
        // so the round-trip test below has a connection that actually authenticates.
        await ensureChatConfig(request, undefined);
        llmRoute = await resolveChatRoute();
    });

    test('ensureConfig persists a resolvable system.connection ref (create + backfill)', async ({ page }) => {
        test.setTimeout(180000);
        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let result = await page.evaluate(async () => {
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page } = await import('/src/core/pageClient.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let log = [];
            let ts = Date.now();
            let created = [];

            let reread = async (group, name) => {
                let q = am7view.viewQuery(am7model.newInstance('olio.llm.chatConfig'));
                q.field('groupId', group.id);
                q.field('name', name);
                q.cache(false);
                let qr = await page.search(q);
                return (qr && qr.results && qr.results.length) ? qr.results[0] : null;
            };
            let hasRef = (c) => !!(c && c.connection && (c.connection.id || c.connection.objectId));

            try {
                let libOk = await LLMConnector.ensureLibrary();
                log.push('Library status: ' + libOk);
                if (!libOk) return { error: 'Chat library not initialized on this stack', log };

                let chatDir = await LLMConnector.findChatDir();
                if (!chatDir) return { error: '~/Chat not found', log };

                let tpl = await LLMConnector.getOpenChatTemplate(chatDir);
                if (!tpl) return { error: 'Open Chat template not found', log };
                log.push('Template: ' + tpl.objectId + ' model=' + tpl.model + ' service=' + tpl.serviceType
                    + ' connection=' + (hasRef(tpl) ? (tpl.connection.objectId || tpl.connection.id) : 'none'));

                // (a1) fresh create via ensureConfig
                let name1 = 'E2E Conn Create ' + ts;
                let cfg1 = await LLMConnector.ensureConfig(name1, tpl, { messageTrim: 4 }, chatDir);
                if (!cfg1) return { error: 'ensureConfig returned null', log };
                created.push(cfg1.objectId);
                let re1 = await reread(chatDir, name1);
                log.push('Created ' + name1 + ' -> connection=' + JSON.stringify(re1 && re1.connection ? { id: re1.connection.id, objectId: re1.connection.objectId, name: re1.connection.name } : null));
                log.push('Created keys: ' + (re1 ? Object.keys(re1).join(',') : 'null'));
                let createHasRef = hasRef(re1);
                let createHasServerUrlField = !!(re1 && Object.prototype.hasOwnProperty.call(re1, 'serverUrl'));

                let conn1 = createHasRef ? await LLMConnector.resolveConnection(re1.connection, true) : null;
                log.push('Resolved connection: ' + JSON.stringify(conn1 ? { id: conn1.id, name: conn1.name, serverUrl: conn1.serverUrl, requestTimeout: conn1.requestTimeout } : null));

                // (a2) backfill: a pre-migration config (no connection) gets one on ensureConfig
                let name2 = 'E2E Conn Stale ' + ts;
                let stale = {
                    schema: 'olio.llm.chatConfig',
                    groupId: chatDir.id,
                    groupPath: chatDir.path,
                    name: name2,
                    model: tpl.model || 'stale-model',
                    serviceType: tpl.serviceType || 'OLLAMA',
                    messageTrim: 6
                };
                let cres = await page.createObject(stale);
                log.push('Stale create result: ' + JSON.stringify(cres));
                let staleBefore = await reread(chatDir, name2);
                if (!staleBefore) return { error: 'stale config not created', log, created };
                created.push(staleBefore.objectId);
                let staleHadRefBefore = hasRef(staleBefore);
                log.push('Stale before ensureConfig: connection=' + staleHadRefBefore);

                let cfg2 = await LLMConnector.ensureConfig(name2, tpl, null, chatDir);
                let re2 = await reread(chatDir, name2);
                let backfillHasRef = hasRef(re2);
                log.push('Stale after ensureConfig: connection=' + JSON.stringify(re2 && re2.connection ? { id: re2.connection.id, objectId: re2.connection.objectId } : null)
                    + ' (ensureConfig returned ' + (cfg2 ? cfg2.objectId : 'null') + ')');
                let conn2 = backfillHasRef ? await LLMConnector.resolveConnection(re2.connection, true) : null;

                return {
                    log, created,
                    createHasRef, createHasServerUrlField,
                    conn1ServerUrl: conn1 ? conn1.serverUrl : null,
                    conn1Timeout: conn1 ? conn1.requestTimeout : null,
                    staleHadRefBefore, backfillHasRef,
                    conn2ServerUrl: conn2 ? conn2.serverUrl : null
                };
            } catch (e) {
                return { error: 'Exception: ' + (e && e.message ? e.message : String(e)), log, created };
            }
        });

        console.log('ensureConfig result:');
        result.log.forEach(l => console.log('  ' + l));

        // Cleanup regardless of outcome
        if (result.created && result.created.length) {
            await page.evaluate(async (oids) => {
                let { page } = await import('/src/core/pageClient.js');
                for (let oid of oids) {
                    if (oid) await page.deleteObject('olio.llm.chatConfig', oid);
                }
            }, result.created);
            console.log('  cleaned up ' + result.created.length + ' chatConfig(s)');
        }

        expect(result.error, result.error || '').toBeUndefined();
        expect(result.createHasRef, 'ensureConfig-created chatConfig must carry a connection ref').toBe(true);
        expect(result.createHasServerUrlField, 'chatConfig must not carry serverUrl any more').toBe(false);
        expect(typeof result.conn1ServerUrl).toBe('string');
        expect(result.conn1ServerUrl.length, 'resolved connection serverUrl must be non-empty').toBeGreaterThan(0);
        expect(typeof result.conn1Timeout).toBe('number');
        expect(result.staleHadRefBefore, 'test precondition: stale config created without a connection').toBe(false);
        expect(result.backfillHasRef, 'ensureConfig must backfill connection on a pre-migration config').toBe(true);
        expect(result.conn2ServerUrl && result.conn2ServerUrl.length > 0).toBe(true);
        assertNoConsoleErrors(capture);
    });

    test('SessionDirector.runDiagnostics resolves the Open Chat connection (no missing serverUrl)', async ({ page }) => {
        test.setTimeout(300000);
        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Kick off diagnostics un-awaited (the 5 behavioral passes call the LLM); poll the log.
        await page.evaluate(async () => {
            let { SessionDirector } = await import('/src/magic8/ai/SessionDirector.js');
            window.__m8diag = { entries: [], done: false, results: null, error: null };
            let director = new SessionDirector();
            director.runDiagnostics('Diagnostic test session', null, (entry) => {
                window.__m8diag.entries.push({ name: entry.name, pass: entry.pass, detail: entry.detail });
            }).then((r) => {
                window.__m8diag.results = r;
                window.__m8diag.done = true;
            }).catch((e) => {
                window.__m8diag.error = e && e.message ? e.message : String(e);
                window.__m8diag.done = true;
            });
        });

        // Infrastructure checks must complete quickly (no LLM involved up to 'Diagnostic chat request').
        await page.waitForFunction(() => {
            let d = window.__m8diag;
            if (!d) return false;
            if (d.done) return true;
            return d.entries.some(e => e.name === 'Diagnostic chat request')
                || d.entries.some(e => e.pass === false);
        }, null, { timeout: 90000 });

        let infra = await page.evaluate(() => JSON.parse(JSON.stringify(window.__m8diag)));
        console.log('Diagnostics (infrastructure):');
        infra.entries.forEach(e => console.log('  ' + (e.pass ? 'PASS' : 'FAIL') + ': ' + e.name + (e.detail ? ' - ' + e.detail : '')));
        if (infra.error) console.log('  runDiagnostics threw: ' + infra.error);

        let byName = (n) => infra.entries.find(e => e.name === n);
        let tplEntry = byName('Find "Open Chat" template');
        let detailsEntry = byName('Load template details');
        let cfgEntry = byName('Diagnostic chat config');

        expect(infra.error).toBeNull();
        expect(tplEntry, 'Find "Open Chat" template entry missing').toBeTruthy();
        expect(tplEntry.pass, 'template step failed: ' + (tplEntry && tplEntry.detail)).toBe(true);
        expect(tplEntry.detail).not.toMatch(/missing serverUrl/i);
        expect(tplEntry.detail).not.toMatch(/missing connection/i);
        expect(detailsEntry, 'Load template details entry missing').toBeTruthy();
        expect(detailsEntry.detail).toMatch(/connection=/);
        expect(detailsEntry.detail).toMatch(/server=https?:\/\//);
        expect(detailsEntry.detail).not.toMatch(/server=unresolved/);
        expect(cfgEntry, 'Diagnostic chat config entry missing').toBeTruthy();
        expect(cfgEntry.pass, 'diagnostic chat config failed: ' + (cfgEntry && cfgEntry.detail)).toBe(true);
        expect(byName('Diagnostic chat request') && byName('Diagnostic chat request').pass).toBe(true);
        for (let e of infra.entries) {
            expect(e.detail || '').not.toMatch(/missing serverUrl/i);
        }

        // The "Magic8 Diagnostics" config ensureConfig just wrote must carry the connection on re-read.
        let diagCfg = await page.evaluate(async () => {
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page } = await import('/src/core/pageClient.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let chatDir = await LLMConnector.findChatDir();
            let q = am7view.viewQuery(am7model.newInstance('olio.llm.chatConfig'));
            q.field('groupId', chatDir.id);
            q.field('name', 'Magic8 Diagnostics');
            q.cache(false);
            let qr = await page.search(q);
            let c = (qr && qr.results && qr.results.length) ? qr.results[0] : null;
            return c ? { objectId: c.objectId, connection: c.connection ? { id: c.connection.id, objectId: c.connection.objectId } : null } : null;
        });
        console.log('  Magic8 Diagnostics config: ' + JSON.stringify(diagCfg));
        expect(diagCfg).toBeTruthy();
        expect(diagCfg.connection && (diagCfg.connection.id || diagCfg.connection.objectId)).toBeTruthy();

        // Behavioral passes (live LLM) — bounded wait; reported, not required for the migration check.
        let llmWaitMs = 150000;
        let behavioral = null;
        try {
            await page.waitForFunction(() => window.__m8diag && window.__m8diag.done, null, { timeout: llmWaitMs });
            behavioral = await page.evaluate(() => JSON.parse(JSON.stringify(window.__m8diag)));
        } catch (e) {
            behavioral = await page.evaluate(() => JSON.parse(JSON.stringify(window.__m8diag)));
            console.log('  behavioral passes did not finish within ' + llmWaitMs + 'ms');
        }
        let passes = behavioral.entries.filter(e => /^Pass \d/.test(e.name));
        console.log('Diagnostics (behavioral, live LLM): ' + passes.length + ' entries');
        passes.forEach(e => console.log('  ' + (e.pass ? 'PASS' : 'FAIL') + ': ' + e.name + (e.detail ? ' - ' + e.detail.substring(0, 160) : '')));

        assertNoConsoleErrors(capture, { ignore: [/SessionDirector \[TEST\] FAIL: Pass/] });
    });

    test('makeChat-created chatConfig carries a connection', async ({ page }) => {
        test.setTimeout(120000);
        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let result = await page.evaluate(async () => {
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page } = await import('/src/core/pageClient.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let log = [];
            let name = 'E2E MakeChat ' + Date.now();
            let oid = null;
            try {
                let chatDir = await LLMConnector.findChatDir();
                let tpl = await LLMConnector.getOpenChatTemplate(chatDir);
                let model = (tpl && tpl.model) || 'herm-local';
                // Legacy positional shape: (name, model, serverUrl[ignored], serviceType)
                let cfg = await am7chat.makeChat(name, model, null, 'OLLAMA');
                if (!cfg) return { error: 'makeChat returned null', log };
                oid = cfg.objectId;
                log.push('makeChat returned ' + cfg.objectId);

                let q = am7view.viewQuery(am7model.newInstance('olio.llm.chatConfig'));
                q.field('groupId', chatDir.id);
                q.field('name', name);
                q.cache(false);
                let qr = await page.search(q);
                let re = (qr && qr.results && qr.results.length) ? qr.results[0] : null;
                let hasRef = !!(re && re.connection && (re.connection.id || re.connection.objectId));
                log.push('Re-read connection=' + JSON.stringify(re && re.connection ? { id: re.connection.id, objectId: re.connection.objectId } : null));
                let conn = hasRef ? await LLMConnector.resolveConnection(re.connection, true) : null;
                log.push('Resolved serverUrl=' + (conn ? conn.serverUrl : null));
                return { log, oid, hasRef, serverUrl: conn ? conn.serverUrl : null, hasServerUrlField: !!(re && Object.prototype.hasOwnProperty.call(re, 'serverUrl')) };
            } catch (e) {
                return { error: 'Exception: ' + (e && e.message ? e.message : String(e)), log, oid };
            }
        });

        console.log('makeChat result:');
        result.log.forEach(l => console.log('  ' + l));
        if (result.oid) {
            await page.evaluate(async (oid) => {
                let { page } = await import('/src/core/pageClient.js');
                await page.deleteObject('olio.llm.chatConfig', oid);
            }, result.oid);
            console.log('  cleaned up ' + result.oid);
        }

        expect(result.error, result.error || '').toBeUndefined();
        expect(result.hasRef, 'makeChat config must carry a connection ref').toBe(true);
        expect(result.hasServerUrlField).toBe(false);
        expect(typeof result.serverUrl === 'string' && result.serverUrl.length > 0).toBe(true);
        assertNoConsoleErrors(capture);
    });

    test('live LLM round trip through a makeChat config with an explicit connection', async ({ page }) => {
        test.setTimeout(300000);
        let capture = captureConsole(page, { verbose: true });
        console.log('[e2e-llm] route=' + llmRoute.route + ' server=' + llmRoute.serverUrl
            + ' analysis=' + llmRoute.analysisModel + ' dialect=' + llmRoute.dialect);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let result = await page.evaluate(async (args) => {
            let { am7client } = await import('/src/core/am7client.js');
            let { page } = await import('/src/core/pageClient.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let log = [];
            let ts = Date.now();
            let cleanup = { chatConfig: null, promptConfig: null, session: null };
            try {
                let chatDir = await LLMConnector.findChatDir();
                let q = am7client.newQuery('system.connection');
                q.entity.request = ['id', 'objectId', 'name', 'serverUrl', 'dialect', 'upstream', 'requestTimeout'];
                q.field('groupId', chatDir.id);
                q.field('name', args.connName);
                q.cache(false);
                let qr = await page.search(q);
                let conn = (qr && qr.results && qr.results.length) ? qr.results[0] : null;
                if (!conn) return { error: 'connection ' + args.connName + ' not found in ~/Chat', log, cleanup };
                log.push('Using connection ' + conn.name + ' id=' + conn.id + ' serverUrl=' + conn.serverUrl + ' dialect=' + conn.dialect);

                let cfg = await am7chat.makeChat('E2E RoundTrip ' + ts, args.model, null, args.dialect, conn);
                if (!cfg) return { error: 'makeChat returned null', log, cleanup };
                cleanup.chatConfig = cfg.objectId;
                let usedConn = cfg.connection && (cfg.connection.id || cfg.connection.objectId);
                log.push('chatConfig ' + cfg.objectId + ' connection=' + JSON.stringify(cfg.connection ? { id: cfg.connection.id, objectId: cfg.connection.objectId } : null));

                let prompt = await am7chat.makePrompt('E2E RoundTrip Prompt ' + ts,
                    ['You are a terse assistant. Reply with a single word and nothing else.']);
                if (!prompt) return { error: 'makePrompt returned null', log, cleanup };
                cleanup.promptConfig = prompt.objectId;

                let session = await am7chat.getChatRequest('E2E RoundTrip ' + ts, cfg, prompt);
                if (!session) return { error: 'getChatRequest returned null', log, cleanup };
                cleanup.session = { objectId: session.objectId, sessionType: session.sessionType, sessionId: session.session && session.session.id };
                log.push('chatRequest ' + session.objectId);

                let resp = await am7chat.chat(session, 'Reply with exactly the word PONG.');
                let msgs = (resp && resp.messages) ? resp.messages : [];
                let last = msgs.length ? msgs[msgs.length - 1] : null;
                log.push('response messages=' + msgs.length + ' lastRole=' + (last && last.role) + ' content=' + JSON.stringify(last && last.content ? last.content.substring(0, 120) : null));
                return {
                    log, cleanup, usedConnId: usedConn, connId: conn.id,
                    assistantContent: (last && last.role === 'assistant') ? last.content : null
                };
            } catch (e) {
                return { error: 'Exception: ' + (e && e.message ? e.message : String(e)), log, cleanup };
            }
        }, { connName: E2E_CONN_NAME, model: llmRoute.analysisModel, dialect: llmRoute.dialect });

        console.log('round trip result:');
        result.log.forEach(l => console.log('  ' + l));

        if (result.cleanup) {
            await page.evaluate(async (c) => {
                let { page } = await import('/src/core/pageClient.js');
                let { am7client } = await import('/src/core/am7client.js');
                try {
                    if (c.session && c.session.sessionType && c.session.sessionId) {
                        let q = am7client.newQuery(c.session.sessionType);
                        q.field('id', c.session.sessionId);
                        q.cache(false);
                        let qr = await page.search(q);
                        if (qr && qr.results && qr.results.length) await page.deleteObject(c.session.sessionType, qr.results[0].objectId);
                    }
                    if (c.session && c.session.objectId) await page.deleteObject('olio.llm.chatRequest', c.session.objectId);
                } catch (e) { console.warn('cleanup session failed', e); }
                if (c.chatConfig) await page.deleteObject('olio.llm.chatConfig', c.chatConfig);
                if (c.promptConfig) await page.deleteObject('olio.llm.promptConfig', c.promptConfig);
            }, result.cleanup);
            console.log('  cleaned up round-trip records');
        }

        expect(result.error, result.error || '').toBeUndefined();
        expect(result.usedConnId, 'makeChat must persist the explicit connection').toBeTruthy();
        expect(typeof result.assistantContent === 'string' && result.assistantContent.trim().length > 0,
            'expected a non-empty assistant reply through the connection endpoint').toBe(true);
        assertNoConsoleErrors(capture);
    });
});
