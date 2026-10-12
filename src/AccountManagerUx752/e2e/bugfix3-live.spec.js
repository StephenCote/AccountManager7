/**
 * Bug Fix Sprint #3 — end-to-end tests against the live backend (chromium-vite project: the dynamic
 * `/src/...` imports and `window.am7imageTokens` / `window.am7audioTokens` need the Vite dev server).
 *
 * Principal: the shared test user (ensureSharedTestUser — NEVER admin). `login()` stubs the WebSocket
 * before page.goto() (Docker nginx drops the session cookie on the WS upgrade), so nothing here reaches
 * the LLM: the "pending until stream text" test drives the chat stream with synthetic server chirps
 * injected into the stub socket (the exact `{chirps:[...]}` frames WebSocketService.java:618-633 emits).
 *
 * What touches the live backend:
 *   - image token resolve: a real data.data record created in ~/Gallery/Bugfix3E2E and fetched back
 *     through am7client.getFull + the ThumbnailServlet (/thumbnail/*, web.xml:374-401).
 *   - audio: POST /rest/voice/{referenceId} (VoiceService.java:193-244) synthesizes through the TTS
 *     server configured on the stack (VOICE_TTS_SERVER) and stores "Voice - <ref>.mp3" in ~/Data/Synthesis.
 *   - edit-mode save: PATCH /rest/model on the session, then /rest/chat/history; persistence is
 *     re-read with a fresh cache:false search.
 *   - scene generation: the dialog's save-then-generate persists this chat's olio.sd.config
 *     (sdcfg-chat-<chatConfigObjectId> in ~/Data/.preferences); the generateScene POST itself is
 *     intercepted (it renders on the SD server) and only the client body contract is asserted.
 *
 * SD-triggering (NOT run in the default suite, gated behind E2E_SD_LIVE and excluded with
 * --grep-invert "generates image via /reimage"):
 *   - am7imageTokens.generateForTags -> POST /olio/olio.charPerson/{id}/reimage.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, ensurePath, createObject, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

// PLAYWRIGHT_BASE_URL is mandatory for every run; no local-dev fallback.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL;
const SD_LIVE = !!process.env.E2E_SD_LIVE;
const HERE = path.dirname(fileURLToPath(import.meta.url));
const RUN_TS = Date.now().toString(36);

// Message rows (features/chat.js:573-574 edit mode, :627 display mode). The pending indicator
// (chat.js:739) is also a `div.flex.justify-start.mb-3`, so tests that send a message use the
// narrower selectors below.
const MSG_ROWS = 'div.flex.mb-3:is(.justify-end, .justify-start)';
const USER_ROW = 'div.flex.mb-3.justify-end';
const THINKING = 'div.flex.justify-start.mb-3:has(span.animate-spin):has-text("Thinking...")';
const STREAM_ROW = 'div.flex.mb-3.justify-start:has(span.inline-block.w-2.h-4.animate-pulse)';
const CHAT_INPUT = 'input[name="chatmessage"]';
const SEND_BTN = 'form button[type="submit"]:has-text("Send")';

let _testUser = {};
let _fixtureImage = null;   // data.data created in beforeAll (identity fields + name)
let _fixtureDir = null;

test.beforeAll(async ({ request }) => {
    _testUser = await ensureSharedTestUser(request);

    // A PNG the shared user owns, so resolve-by-UUID has a known, readable target instead of "any
    // image in the org" (which PBAC may hide from this user, and which may not exist on a fresh stack).
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let lr = await apiLogin(ctx, { user: _testUser.testUserName, password: _testUser.testPassword });
        if (!(lr.ok() || lr.status() === 204)) throw new Error('login as ' + _testUser.testUserName + ' failed: HTTP ' + lr.status());
        _fixtureDir = await ensurePath(ctx, 'auth.group', 'data', '~/Gallery/Bugfix3E2E');
        if (!_fixtureDir || !_fixtureDir.id) throw new Error('could not make ~/Gallery/Bugfix3E2E');
        let imgPath = path.resolve(HERE, '..', 'public', 'media', 'femaleSilhouette.png');
        let imgB64 = fs.readFileSync(imgPath).toString('base64');
        let name = 'bugfix3-e2e-' + RUN_TS + '.png';
        _fixtureImage = await createObject(ctx, 'data.data', {
            groupId: _fixtureDir.id, groupPath: _fixtureDir.path, name,
            contentType: 'image/png', dataBytesStore: imgB64
        });
        if (!_fixtureImage || !_fixtureImage.objectId) throw new Error('could not create fixture image ' + name + ': ' + JSON.stringify(_fixtureImage));
        _fixtureImage.name = name;
        _fixtureImage.groupPath = _fixtureDir.path;
        console.log('[bugfix3] fixture image ' + _fixtureDir.path + '/' + name + ' = ' + _fixtureImage.objectId);
        await apiLogout(ctx);
    } finally {
        await ctx.dispose();
    }
});

function testLogin(page) {
    return login(page, { user: _testUser.testUserName, password: _testUser.testPassword });
}

/// Chat feature route; the one-time "Chat Library Setup" wizard is dismissed if it is showing.
async function openChat(page) {
    await page.locator('button[title="Chat"]').click();
    await page.waitForURL(/.*#!\/chat/, { timeout: 15000 });
    await expect(page.locator('button[title="Sessions"]')).toBeVisible({ timeout: 20000 });
    let wizardTitle = page.locator('text=Chat Library Setup');
    if (await wizardTitle.isVisible()) {
        await page.locator('button:has-text("Cancel")').click();
        await expect(wizardTitle).not.toBeVisible({ timeout: 5000 });
    }
}

/// Create a chat config + prompt + session through the client API (no LLM call: makeChat/makePrompt/
/// getChatRequest only create records and POST /rest/chat/new), optionally seeding messages onto the
/// session record, and drop the client+server chatRequest caches so ConversationManager lists it.
async function seedSession(page, prefix, messages) {
    let setup = await page.evaluate(async ({ prefix, messages }) => {
        let { am7client } = await import('/src/core/am7client.js');
        let { am7model } = await import('/src/core/model.js');
        let { am7chat } = await import('/src/chat/chatUtil.js');
        let { am7view } = await import('/src/core/view.js');
        let { LLMConnector } = await import('/src/chat/LLMConnector.js');
        let { page: pg } = await import('/src/core/pageClient.js');
        let ts = Date.now().toString(36);
        let name = prefix + '-' + ts;
        await LLMConnector.ensureLibrary().catch(() => {});
        await pg.makePath('auth.group', 'data', '~/Chat');
        let chatCfg = await am7chat.makeChat(name, 'qwen3-vl:8b-instruct', 'http://192.168.1.42:11434', 'OLLAMA');
        let promptCfg = await am7chat.makePrompt('default');
        let req = await am7chat.getChatRequest(name, chatCfg, promptCfg);
        if (!req) return { error: 'ChatRequest failed' };

        let q0 = am7client.newQuery('olio.llm.chatRequest');
        q0.field('objectId', req.objectId);
        q0.entity.request.push('id', 'objectId', 'name', 'session', 'sessionType', 'chatConfig');
        let qr0 = await pg.search(q0);
        let fullReq = qr0 && qr0.results && qr0.results.length ? qr0.results[0] : null;
        if (!fullReq || !fullReq.session) return { error: 'No session on chatRequest ' + req.objectId };

        let sessType = fullReq.sessionType;
        let patched = null;
        if (messages && messages.length) {
            let q = am7view.viewQuery(am7model.newInstance(sessType));
            q.field('id', fullReq.session.id);
            q.cache(false);
            q.entity.request.push('messages');
            let qr = await pg.search(q);
            let sess = qr.results[0];
            if (!sess.messages) sess.messages = [];
            messages.forEach(mm => sess.messages.push(mm));
            if (!sess[am7model.jsonModelKey]) sess[am7model.jsonModelKey] = sessType;
            patched = await pg.patchObject(sess);
            am7client.clearCache(sessType);
        }
        await Promise.resolve(am7client.clearCache('olio.llm.chatRequest', false));
        return {
            name, objectId: req.objectId, sessionId: fullReq.session.id, sessionType: sessType,
            chatConfigObjectId: chatCfg ? chatCfg.objectId : null, patched
        };
    }, { prefix, messages: messages || [] });
    expect(setup.error, 'session setup: ' + JSON.stringify(setup)).toBeUndefined();
    console.log('[bugfix3] seeded session ' + JSON.stringify(setup));
    return setup;
}

/// Sessions panel -> filter -> row click; resolves once the session's history has been fetched
/// (the click triggers POST /rest/chat/history) and the header shows the session name.
async function selectSession(page, name) {
    await page.locator('button[title="Sessions"]').click();
    await page.locator('input[placeholder="Filter sessions..."]').fill(name);
    // Rows: chat/ConversationManager.js:118-155 — `div.cursor-pointer` with the delete_outline span.
    let row = page.locator('div.cursor-pointer:has(span[title="Delete session"])', { hasText: name });
    await expect(row, 'the seeded session must be listed').toHaveCount(1, { timeout: 15000 });
    await Promise.all([
        page.waitForResponse(r => r.url().includes('/rest/chat/history') && r.request().method() === 'POST', { timeout: 30000 }),
        row.click()
    ]);
    await expect(page.locator('h2', { hasText: name }), 'chat header must show the selected session').toBeVisible({ timeout: 15000 });
}

test.describe('Image token pipeline (live backend)', () => {

    test.beforeEach(async ({ page }) => {
        await testLogin(page);
        await openChat(page);
    });

    test('am7imageTokens.resolve() fetches an existing image by UUID and its thumbnail URL serves the image', async ({ page }) => {
        test.setTimeout(120000);

        let resolveResult = await page.evaluate(async (objId) => {
            let imgTokens = window.am7imageTokens;
            if (!imgTokens || !imgTokens.resolve) return { error: 'am7imageTokens not loaded' };
            let token = { id: objId, key: objId, tags: [] };
            let result = await imgTokens.resolve(token, null, {});
            if (!result) return { resolved: false };
            // imageTokens.js:326-337 -> am7client.getFull + buildThumbnailUrl (needs groupPath + name)
            let cachedById = imgTokens.cache[objId] === result;
            let r = await fetch(result.url, { credentials: 'include' });
            let buf = await r.arrayBuffer();
            return {
                resolved: true, url: result.url, imageObjectId: result.image && result.image.objectId,
                imageName: result.image && result.image.name, cachedById,
                fetchOk: r.ok, fetchStatus: r.status, contentType: r.headers.get('content-type') || '', bytes: buf.byteLength
            };
        }, _fixtureImage.objectId);
        console.log('[bugfix3] resolve by UUID: ' + JSON.stringify(resolveResult));

        expect(resolveResult.error).toBeUndefined();
        expect(resolveResult.resolved, 'resolve must find the fixture image by objectId').toBe(true);
        expect(resolveResult.imageObjectId).toBe(_fixtureImage.objectId);
        expect(resolveResult.imageName).toBe(_fixtureImage.name);
        expect(resolveResult.url).toContain('/thumbnail/');
        expect(resolveResult.url).toContain('/data.data' + _fixtureImage.groupPath + '/' + _fixtureImage.name + '/256x256');
        expect(resolveResult.cachedById, 'resolve must cache the entry by token id for processContent').toBe(true);
        // The URL must actually serve an image through the ThumbnailServlet.
        expect(resolveResult.fetchOk, 'thumbnail URL must return 2xx, got ' + resolveResult.fetchStatus).toBe(true);
        expect(resolveResult.contentType).toMatch(/^image\//);
        expect(resolveResult.bytes).toBeGreaterThan(0);

        await screenshot(page, 'bugfix3-live-image-uuid-resolve');
    });

    // SD-TRIGGERING: generateForTags POSTs /olio/olio.charPerson/{id}/reimage, which renders on the SwarmUI
    // server (30-120 s). Run only in the serial SD slot:
    //   E2E_SD_LIVE=1 PLAYWRIGHT_BASE_URL=... npx playwright test e2e/bugfix3-live.spec.js -g "generates image via /reimage" --workers=1 --project=chromium-vite
    test('am7imageTokens.resolve() generates image via /reimage for character', async ({ page }) => {
        test.skip(!SD_LIVE, 'set E2E_SD_LIVE=1 to run the SD-rendering reimage test (serial slot only)');
        // Generous ceiling for a live render; never a pass/fail threshold.
        test.setTimeout(600000);

        // A charPerson the shared user owns, in its own group (not "any charPerson in the org").
        let charData = await page.evaluate(async (ts) => {
            let { page: pg } = await import('/src/core/pageClient.js');
            let { am7model } = await import('/src/core/model.js');
            let dir = await pg.makePath('auth.group', 'data', '~/Bugfix3E2E');
            if (!dir || !dir.id) return { error: 'could not make ~/Bugfix3E2E' };
            let obj = {
                name: 'Bugfix3E2E_' + ts, firstName: 'Bugfix', lastName: 'Three', gender: 'female',
                alignment: 'neutralgood', groupId: dir.id, groupPath: dir.path
            };
            obj[am7model.jsonModelKey] = 'olio.charPerson';
            let created = await pg.createObject(obj);
            if (!created || !created.objectId) return { error: 'createObject failed: ' + JSON.stringify(created) };
            return { objectId: created.objectId, name: obj.name };
        }, RUN_TS);
        expect(charData.error, 'charPerson fixture: ' + JSON.stringify(charData)).toBeUndefined();

        let genResult = await page.evaluate(async (char) => {
            let imgTokens = window.am7imageTokens;
            if (!imgTokens || !imgTokens.generateForTags) return { error: 'am7imageTokens not loaded' };
            let character = { objectId: char.objectId, name: char.name, schema: 'olio.charPerson' };
            let t0 = Date.now();
            let image = await imgTokens.generateForTags(character, ['casual'], {});
            return { generated: !!image, imageObjectId: image ? image.objectId : null, imageName: image ? image.name : null, ms: Date.now() - t0 };
        }, charData);
        console.log('[bugfix3] reimage: ' + JSON.stringify(genResult));
        expect(genResult.error).toBeUndefined();
        expect(genResult.generated, 'reimage must return a data.data image').toBe(true);
        expect(genResult.imageObjectId).toBeTruthy();
        await screenshot(page, 'bugfix3-live-image-generate');
    });

    test('processContent replaces image tokens with img tags or placeholders', async ({ page }) => {
        let result = await page.evaluate((objId) => {
            let imgTokens = window.am7imageTokens;
            if (!imgTokens) return { error: 'not loaded' };
            // Unresolved token -> loading placeholder (imageTokens.js:377)
            let html = imgTokens.processContent('Hello ${image.casual} world');
            // Resolved (cached) token -> <img> with the cached thumbnail URL (imageTokens.js:374)
            imgTokens.cache['e2e-cached'] = { image: { objectId: objId }, url: '/e2e/thumb/' + objId };
            let tokens = imgTokens.parse('Hello ${image.casual} world');
            return {
                placeholder: html.indexOf('animate-pulse') > -1,
                tokenRemoved: html.indexOf('${image.') === -1,
                html,
                parsedKeys: tokens.map(t => t.key)
            };
        }, _fixtureImage.objectId);
        console.log('[bugfix3] processContent: ' + JSON.stringify(result));
        expect(result.error).toBeUndefined();
        expect(result.tokenRemoved).toBe(true);
        expect(result.placeholder, 'an unresolved token renders the animate-pulse placeholder').toBe(true);
        expect(result.html).toContain('Hello ');
        expect(result.html).toContain(' world');
        expect(result.parsedKeys.length).toBe(1);
    });
});

test.describe('Audio token pipeline (live backend)', () => {

    test.beforeEach(async ({ page }) => {
        await testLogin(page);
        await openChat(page);
    });

    test('POST /rest/voice/{referenceId} synthesizes speech and returns the stored audio record', async ({ page }) => {
        // Synthesis on the stack's TTS server; give it ample room, never a threshold.
        test.setTimeout(180000);
        // VoiceService.java:200-243 — a referenceId already in its in-memory `synthesized` set with no stored
        // record 400s, so every run uses a fresh one; the body mirrors audioTokens.js:117-122.
        let refId = 'bugfix3-e2e-' + RUN_TS;
        let synthResult = await page.evaluate(async (refId) => {
            let { applicationPath } = await import('/src/core/config.js').catch(() => ({ applicationPath: null }));
            let base = (applicationPath || '/AccountManagerService7') + '/rest/voice/' + encodeURIComponent(refId);
            let t0 = Date.now();
            let resp = await fetch(base, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ text: 'Hello, this is a test.', speed: 1.2, engine: 'piper', speaker: 'en_GB-alba-medium' })
            });
            let ms = Date.now() - t0;
            let text = await resp.text();
            let data = null;
            try { data = JSON.parse(text); } catch (e) { /* non-JSON body */ }
            return {
                url: base, status: resp.status, ok: resp.ok, ms,
                contentType: resp.headers.get('content-type') || '',
                schema: data && data.schema, name: data && data.name, dataContentType: data && data.contentType,
                audioBytes: data && data.dataBytesStore ? atob(data.dataBytesStore).length : 0,
                bodyHead: data ? null : text.substring(0, 200)
            };
        }, refId);
        console.log('[bugfix3] voice synth: ' + JSON.stringify(synthResult));

        expect(synthResult.status, 'synthesis must succeed (500 = TTS server unreachable from the stack); body: ' + synthResult.bodyHead).toBe(200);
        expect(synthResult.schema).toBe('data.data');
        expect(synthResult.name).toBe('Voice - ' + refId + '.mp3');
        expect(synthResult.dataContentType).toMatch(/audio/);
        expect(synthResult.audioBytes, 'the stored record must carry real audio bytes').toBeGreaterThan(0);
        await screenshot(page, 'bugfix3-live-audio-synth');
    });

    test('audio button renders with data-audio-id and data-audio-text attributes', async ({ page }) => {
        // Token form is `${audio.TEXT}` — TOKEN_REGEX (audioTokens.js:8-9) captures everything after
        // `audio.` up to `}` as the spoken text. The file's header comment (:2) still documents the older
        // `${audio.text "..."}` form; with that input the captured text is `text "hello world"`, not
        // `hello world`.
        let result = await page.evaluate(() => {
            let audioTokens = window.am7audioTokens;
            if (!audioTokens) return { error: 'not loaded' };
            let html = audioTokens.processContent('Say ${audio.hello world} now');
            return {
                hasDataAudioId: html.indexOf('data-audio-id=') > -1,
                hasDataAudioText: html.indexOf('data-audio-text="hello world"') > -1,
                hasPlayIcon: html.indexOf('play_arrow') > -1,
                tokenRemoved: html.indexOf('${audio.') === -1,
                html
            };
        });
        console.log('[bugfix3] audio processContent: ' + JSON.stringify(result));
        expect(result.error).toBeUndefined();
        expect(result.hasDataAudioId).toBe(true);
        expect(result.hasDataAudioText).toBe(true);
        expect(result.hasPlayIcon).toBe(true);
        expect(result.tokenRemoved).toBe(true);
        expect(result.html).toContain('Say ');
        expect(result.html).toContain(' now');
    });

    test('clicking an audio button posts the button text to /rest/voice/{id}', async ({ page }) => {
        test.setTimeout(120000);
        // The document-level click delegation (audioTokens.js:210-219) handles any [data-audio-id] button.
        await page.evaluate(() => {
            let div = document.createElement('div');
            div.id = 'test-audio-container';
            div.innerHTML = '<button data-audio-id="test-btn" data-audio-text="test audio click"><span class="material-symbols-outlined" style="font-size:14px">play_arrow</span><span>test</span></button>';
            document.body.appendChild(div);
        });

        // play() -> fetch(applicationPath + "/rest/voice/" + btnId) (audioTokens.js:129-134); there is no
        // /audio/synthesize route (the previous version of this test waited for one that never existed).
        let synthRequest = page.waitForRequest(
            req => req.method() === 'POST' && /\/rest\/voice\/test-btn$/.test(req.url()),
            { timeout: 15000 }
        );
        await page.locator('#test-audio-container button[data-audio-id="test-btn"]').click();
        let req = await synthRequest;
        let body = req.postDataJSON();
        console.log('[bugfix3] audio click -> ' + req.method() + ' ' + req.url() + ' ' + JSON.stringify(body));
        expect(body.text).toBe('test audio click');
        expect(body.engine).toBe('piper');
        expect(body.speaker).toBe('en_GB-alba-medium');
        let resp = await req.response();
        // "test-btn" is reused across runs: the first run synthesizes it, later runs get the stored
        // "Voice - test-btn.mp3" back (VoiceService.java:202-206). Either way the server must answer 200.
        expect(resp.status(), 'voice route must return the stored/synthesized record').toBe(200);

        await page.evaluate(() => { let el = document.getElementById('test-audio-container'); if (el) el.remove(); });
        await screenshot(page, 'bugfix3-live-audio-click');
    });
});

test.describe('Edit mode save (live backend)', () => {

    test.beforeEach(async ({ page }) => {
        await testLogin(page);
    });

    test('editing a message in edit mode and saving persists the edit to the session', async ({ page }) => {
        test.setTimeout(120000);
        let marker = '[E2E-EDIT-' + RUN_TS + ']';
        let setup = await seedSession(page, 'EditSave', [
            { role: 'user', content: 'Edit me' },
            { role: 'assistant', content: 'Original reply' }
        ]);
        await openChat(page);
        await selectSession(page, setup.name);

        let rows = page.locator(MSG_ROWS);
        await expect(rows, 'both seeded messages must render').toHaveCount(2, { timeout: 30000 });
        await expect(rows.nth(1)).toContainText('Original reply');

        let networkCalls = [];
        page.on('request', req => {
            let url = req.url();
            if (url.includes('/rest/') && ['POST', 'PATCH', 'PUT'].includes(req.method())) {
                networkCalls.push(req.method() + ' ' + url.replace(/.*\/rest\//, '/rest/'));
            }
        });

        await page.locator('button[title="Edit messages"]').click();
        // Edit mode renders one textarea#editMessage-<displayIndex> per message (chat.js:480-483).
        let ta = page.locator('textarea#editMessage-1');
        await expect(ta, 'assistant message textarea must render in edit mode').toBeVisible({ timeout: 5000 });
        await expect(ta).toHaveValue('Original reply');
        await ta.fill('Original reply ' + marker);

        await page.locator('button[title="Save edits"]').click();
        // saveEdits (chat.js:441-507): search session -> PATCH /rest/model -> re-peek via /rest/chat/history.
        const historyAfterPatch = () => {
            let p = networkCalls.findIndex(c => c.startsWith('PATCH /rest/model'));
            return p >= 0 && networkCalls.slice(p + 1).some(c => c.startsWith('POST /rest/chat/history'));
        };
        await expect.poll(historyAfterPatch, {
            message: 'save must PATCH the session and then re-fetch history; calls so far: ' + JSON.stringify(networkCalls),
            timeout: 30000
        }).toBe(true);
        expect(networkCalls.some(c => c.includes('/rest/chat/clear')), 'the edit flow must never hit /rest/chat/clear').toBe(false);

        // The re-fetched history shows the edit, edit mode is exited.
        await expect(rows, 'both messages survive the edit').toHaveCount(2, { timeout: 30000 });
        await expect(rows.nth(1)).toContainText(marker);
        await expect(rows.nth(0)).toContainText('Edit me');
        await expect(page.locator('textarea#editMessage-1')).toHaveCount(0);
        await expect(page.locator('button[title="Edit messages"]')).toBeVisible();

        // Persistence: a fresh, uncached read of the session record carries the edited content.
        let persisted = await page.evaluate(async ({ sessionId, sessionType }) => {
            let { am7model } = await import('/src/core/model.js');
            let { am7view } = await import('/src/core/view.js');
            let { page: pg } = await import('/src/core/pageClient.js');
            let q = am7view.viewQuery(am7model.newInstance(sessionType));
            q.field('id', sessionId);
            q.cache(false);
            q.entity.request.push('messages');
            let qr = await pg.search(q);
            let sess = qr && qr.results && qr.results[0];
            let msgs = (sess && sess.messages) || [];
            return { count: msgs.length, last: msgs.length ? msgs[msgs.length - 1].content : null, roles: msgs.map(mm => mm.role) };
        }, setup);
        console.log('[bugfix3] persisted session after save: ' + JSON.stringify(persisted));
        expect(persisted.last, 'the server session must hold the edited assistant message').toContain(marker);
        await screenshot(page, 'bugfix3-live-edit-save');
    });
});

test.describe('Scene generation (live backend)', () => {

    test.beforeEach(async ({ page }) => {
        await testLogin(page);
    });

    test('Scene Gallery -> Generate Scene saves this chat\'s olio.sd.config and POSTs it to /rest/chat/{id}/generateScene', async ({ page }) => {
        test.setTimeout(120000);
        let setup = await seedSession(page, 'SceneGen', []);
        await openChat(page);
        await selectSession(page, setup.name);

        // The POST renders on the SD server (SceneGenerator.js:253-258). Intercept it and answer 204 so the
        // client resolves null -> "Scene generation failed: no result" toast (no console.error, which the
        // fixtures would fail on). Only the client-side body contract is asserted here; the server side of
        // generateScene is covered by chatChainSceneReimageRoutes.spec.js under E2E_SD_LIVE.
        let captured = null;
        await page.route(/\/rest\/chat\/[^/]+\/generateScene$/, route => {
            captured = { url: route.request().url(), body: route.request().postDataJSON() };
            route.fulfill({ status: 204, body: '' });
        });

        // Toolbar "Scene Gallery" (chat.js:931-937); with no characters on the chat openSceneGallery
        // goes straight to SceneGenerator.show (SceneGenerator.js:404-410).
        await page.locator('button[title="Scene Gallery"]').click();
        let dialog = page.locator('[role="dialog"]', { hasText: 'Scene Generation' });
        await expect(dialog, 'Scene Generation dialog must open').toBeVisible({ timeout: 15000 });
        // The panel renders a loading state until /olio/randomImageConfig resolves (SceneGenerator.js:342-344).
        await expect(dialog.locator('text=Loading image configuration...')).toHaveCount(0, { timeout: 30000 });
        await expect(dialog.locator('text=Save as Shared Config')).toBeVisible();

        let generateBtn = dialog.locator('.am7-dialog-footer button.am7-dialog-btn-primary', { hasText: 'Generate Scene' });
        await expect(generateBtn).toBeEnabled();
        await generateBtn.click();

        await expect.poll(() => captured !== null, { message: 'Generate Scene must POST to /rest/chat/{id}/generateScene', timeout: 30000 }).toBe(true);
        await expect(dialog).toHaveCount(0);
        console.log('[bugfix3] generateScene ' + captured.url + ' body keys: ' + Object.keys(captured.body || {}).sort().join(','));

        expect(captured.url).toContain('/rest/chat/' + setup.objectId + '/generateScene');
        let body = captured.body;
        expect(body, 'body must be a JSON olio.sd.config').toBeTruthy();
        expect(body.schema).toBe('olio.sd.config');
        // Legacy field name must be gone; the declared names are the model's own.
        expect(body).not.toHaveProperty('cfgScale');
        expect(body).not.toHaveProperty('samplerName');
        // Authority for "declared" is the SERVER's schema (GET /rest/schema), inherited fields included: the body
        // is what /olio/randomImageConfig serialized (toFullString), and on the server olio.sd.config inherits
        // data.directory -> ... -> common.base -> dev.debug / common.populate, so the ephemeral `debug` and
        // `populated` are legitimately present. The client mirror (core/modelDef.js `olio.sd.config`, no
        // `inherits`) is hand-edited and omits every inherited field, so it is not usable as the authority.
        let declared = await page.evaluate(async () => {
            let { applicationPath } = await import('/src/core/config.js');
            let r = await fetch(applicationPath + '/rest/schema', { credentials: 'include' });
            if (!r.ok) return { error: 'GET /rest/schema HTTP ' + r.status };
            let schema = await r.json();
            let byName = {};
            (schema.models || []).forEach(mm => { byName[mm.name] = mm; });
            let seen = new Set();
            let names = [];
            let walk = (n) => {
                let mm = byName[n];
                if (!mm || seen.has(n)) return;
                seen.add(n);
                (mm.fields || []).forEach(f => names.push(f.name));
                (mm.inherits || []).forEach(walk);
            };
            walk('olio.sd.config');
            return { names, inherits: (byName['olio.sd.config'] || {}).inherits };
        });
        expect(declared.error, 'served schema must be readable').toBeUndefined();
        expect(declared.inherits, 'server olio.sd.config inherits data.directory').toContain('data.directory');
        let unknown = Object.keys(body).filter(k => k !== 'schema' && !declared.names.includes(k));
        expect(unknown, 'every posted key must be a field the server declares on olio.sd.config (inherited included)').toEqual([]);
        // No identity: the server treats the body as a config template, not a record to update.
        ['id', 'objectId', 'urn', 'groupId', 'ownerId'].forEach(k => expect(body[k], k + ' must not be posted').toBeFalsy());
        expect(body.sampler, 'sampler must be set').toBeTruthy();
        expect(body.scheduler, 'scheduler must be set').toBeTruthy();
        expect(typeof body.style).toBe('string');
        // KI-65 / SceneGenerator.js:88-91: /olio/randomImageConfig does not serialize steps/cfg/width/height,
        // so their presence depends on a previously saved per-chat config. Observed, not asserted.
        console.log('[bugfix3] numeric fields present: ' + ['steps', 'cfg', 'width', 'height', 'hires', 'compositeMode']
            .map(k => k + '=' + JSON.stringify(body[k])).join(' '));

        // Save-then-generate (SceneGenerator.js:239-241): this chat's config record now exists server-side.
        let saved = await page.evaluate(async (chatConfigObjectId) => {
            let { am7client } = await import('/src/core/am7client.js');
            let { page: pg } = await import('/src/core/pageClient.js');
            let grp = await pg.makePath('auth.group', 'DATA', '~/Data/.preferences');
            let q = am7client.newQuery('olio.sd.config');
            q.field('name', 'sdcfg-chat-' + chatConfigObjectId);
            q.field('groupId', grp.id);
            q.cache(false);
            let qr = await pg.search(q);
            let hit = qr && qr.results && qr.results[0];
            if (!hit) return { found: false };
            let full = await am7client.getFull('olio.sd.config', hit.objectId);
            return { found: true, objectId: hit.objectId, sampler: full && full.sampler, scheduler: full && full.scheduler };
        }, setup.chatConfigObjectId);
        console.log('[bugfix3] saved per-chat config: ' + JSON.stringify(saved));
        expect(saved.found, 'Generate must persist sdcfg-chat-<chatConfigObjectId> in ~/Data/.preferences').toBe(true);
        expect(saved.sampler).toBe(body.sampler);
        expect(saved.scheduler).toBe(body.scheduler);
        await screenshot(page, 'bugfix3-live-scene-generation');
    });
});

test.describe('Pending indicator during a streamed reply', () => {

    test('Thinking... shows until the first chatUpdate token, then the stream renders and chatComplete re-enables input', async ({ page }) => {
        test.setTimeout(120000);
        // Capture the stub WebSocket instance login() installs (helpers/auth.js:39-53) so the server-side
        // chirps can be injected. Registered BEFORE login() so its init script wraps the stub's assignment.
        await page.addInitScript(() => {
            let current = window.WebSocket;
            window.__e2eWsInstances = [];
            Object.defineProperty(window, 'WebSocket', {
                configurable: true,
                get() { return current; },
                set(v) {
                    current = class extends v {
                        constructor(...a) { super(...a); window.__e2eWsInstances.push(this); }
                    };
                }
            });
        });
        await testLogin(page);
        let setup = await seedSession(page, 'PendLive', []);
        await openChat(page);
        await selectSession(page, setup.name);

        // Wrap the outgoing side so the frame doSend emits is asserted too (pageClient.js:333-372).
        await page.evaluate(() => {
            let pg = window.am7page;
            window.__e2eWsSends = [];
            let orig = pg.wss.send;
            pg.wss.send = function (name, data, ...rest) { window.__e2eWsSends.push({ name, data }); return orig.call(this, name, data, ...rest); };
        });

        let input = page.locator(CHAT_INPUT);
        await expect(input).toBeEnabled({ timeout: 10000 });
        await input.fill('Pending indicator check ' + RUN_TS);
        await page.locator(SEND_BTN).click();

        // doSend (chat.js:367-375): pending=true, user bubble pushed, chat frame sent; nothing has answered yet.
        await expect(page.locator(THINKING), 'Thinking... must show while pending').toBeVisible({ timeout: 10000 });
        await expect(page.locator(USER_ROW, { hasText: 'Pending indicator check ' + RUN_TS })).toBeVisible();
        await expect(input).toBeDisabled();
        await expect(page.locator(SEND_BTN)).toBeDisabled();
        let sends = await page.evaluate(() => window.__e2eWsSends);
        expect(sends.length, 'exactly one chat frame must be sent').toBe(1);
        expect(sends[0].name).toBe('chat');
        let frame = JSON.parse(sends[0].data);
        expect(frame.schema).toBe('olio.llm.chatRequest');
        expect(frame.objectId).toBe(setup.objectId);
        expect(frame.message).toBe('Pending indicator check ' + RUN_TS);

        // Inject the server's chirps (WebSocketService.java:618-633 -> pageClient.routeMessage:385-390).
        const chirp = (chirps) => page.evaluate((c) => {
            let list = window.__e2eWsInstances;
            let ws = list[list.length - 1];
            if (!ws || !ws.onmessage) throw new Error('no stub WebSocket with onmessage (' + (list ? list.length : 'none') + ' instances)');
            ws.onmessage({ data: JSON.stringify({ chirps: c }) });
        }, chirps);

        // chatStart: streaming=true but streamText empty -> the indicator stays (chat.js:737-738).
        await chirp(['chatStart', setup.objectId, '{}']);
        await expect(page.locator(THINKING), 'chatStart alone must not hide the indicator').toBeVisible();
        await expect(page.locator(STREAM_ROW)).toHaveCount(0);

        // First token: indicator gives way to the streaming bubble with the caret (chat.js:679-698).
        await chirp(['chatUpdate', setup.objectId, 'Hello from ']);
        await expect(page.locator(THINKING), 'first chatUpdate must hide the indicator').toHaveCount(0, { timeout: 10000 });
        await expect(page.locator(STREAM_ROW)).toBeVisible({ timeout: 10000 });
        await expect(page.locator(STREAM_ROW)).toContainText('Hello from');
        await chirp(['chatUpdate', setup.objectId, 'the stream.']);
        await expect(page.locator(STREAM_ROW)).toContainText('Hello from the stream.', { timeout: 10000 });
        // Mid-stream the input is deliberately ENABLED again: renderInput (chat.js:985-986) disables only while
        // `pending && !streaming`, so that typing during a stream interrupts and resends (doSend, chat.js:357).
        await expect(input, 'input must re-enable once streaming has started (interrupt affordance)').toBeEnabled();
        // ...and the submit button is swapped for a red "Stop" (LLMConnector.cancelStream) while streaming
        // (chat.js:1013-1020); "Send" only comes back after chatComplete.
        await expect(page.locator(SEND_BTN)).toHaveCount(0);
        await expect(page.locator('form button[type="button"]:has-text("Stop")'), 'Stop button must replace Send mid-stream').toBeVisible();

        // chatComplete: streaming/pending cleared, caret gone, controls re-enabled (chat.js:286-306).
        await chirp(['chatComplete', setup.objectId]);
        await expect(page.locator(STREAM_ROW)).toHaveCount(0, { timeout: 10000 });
        await expect(page.locator(THINKING)).toHaveCount(0);
        await expect(input).toBeEnabled({ timeout: 10000 });
        await expect(page.locator(SEND_BTN)).toBeEnabled();
        // onchatcomplete then replaces the local history with the server's (chat.js:297-304). The stub socket
        // never delivered the message to the server, so the server copy is the seeded (empty) history and the
        // local user/assistant bubbles are dropped — a property of the stub, not asserted either way.
        console.log('[bugfix3] rows after chatComplete: ' + await page.locator(MSG_ROWS).count());
        await screenshot(page, 'bugfix3-live-pending-stream');
    });
});
