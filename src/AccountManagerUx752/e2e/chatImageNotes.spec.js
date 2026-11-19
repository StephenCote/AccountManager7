/**
 * Image action notes (aiDocs/ChatWardrobeTokensDesign.md §3.4b), against the live backend.
 *
 * When a chat ${image...} token is resolved, the client appends a system-written note right after it:
 *   ${image.<oid>.selfie,casual}<private>[image] Bob shares a picture of himself (selfie, casual): <caption>.</private>
 * The caption is the auto-tagger's (data.data.description, written by GET /tag/apply/{id}). The note is
 * persisted on the session record (so it reaches the LLM in later turns), hidden in the chat view, never
 * stacked, and lands only in the message that carried the token (user vs assistant intros share tokens).
 *
 * Principal: the shared test user (ensureSharedTestUser — NEVER admin). No LLM call: the session is
 * created with POST /rest/chat/new and its messages are seeded by PATCH. The tagger (tag.server) IS
 * called for real — test 1 fails if it cannot caption the fixture.
 *
 * Vite-source spec (dynamic /src imports): run with the Docker stack plus a Vite on 8898, e.g.
 *   AM7_BACKEND=https://127.0.0.1:9443 npx vite --port 8898
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chatImageNotes.spec.js --workers=1 --project=chromium-vite
 */
import { test, expect } from './helpers/fixtures.js';
import { openChat } from './helpers/nav.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, ensurePath, createObject, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const RUN_TS = Date.now().toString(36);

let _user = {};
let _img = null;        // fixture data.data (identity + name)
let _bob = null;        // olio.charPerson, male — system character
let _sally = null;      // olio.charPerson, female — user character
let _cfg = null;        // olio.llm.chatConfig
let _prompt = null;     // olio.llm.promptConfig

test.beforeAll(async ({ request }) => {
    _user = await ensureSharedTestUser(request);
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let lr = await apiLogin(ctx, { user: _user.testUserName, password: _user.testPassword });
        if (!(lr.ok() || lr.status() === 204)) throw new Error('login failed: HTTP ' + lr.status());

        let gal = await ensurePath(ctx, 'auth.group', 'data', '~/Gallery/ImageNotesE2E');
        let imgB64 = fs.readFileSync(path.resolve(HERE, '..', 'public', 'media', 'femaleSilhouette.png')).toString('base64');
        let name = 'image-notes-' + RUN_TS + '.png';
        _img = await createObject(ctx, 'data.data', { groupId: gal.id, groupPath: gal.path, name, contentType: 'image/png', dataBytesStore: imgB64 });
        if (!_img || !_img.objectId) throw new Error('fixture image create failed: ' + JSON.stringify(_img));

        let chars = await ensurePath(ctx, 'auth.group', 'data', '~/Characters');
        _bob = await createObject(ctx, 'olio.charPerson', { name: 'Bob Notes' + RUN_TS, firstName: 'Bob', lastName: 'Notes' + RUN_TS, gender: 'male', age: 30, race: ['E'], groupId: chars.id, groupPath: chars.path });
        _sally = await createObject(ctx, 'olio.charPerson', { name: 'Sally Notes' + RUN_TS, firstName: 'Sally', lastName: 'Notes' + RUN_TS, gender: 'female', age: 29, race: ['E'], groupId: chars.id, groupPath: chars.path });
        if (!_bob || !_bob.objectId || !_sally || !_sally.objectId) throw new Error('character create failed');

        let chat = await ensurePath(ctx, 'auth.group', 'data', '~/Chat');
        _prompt = await createObject(ctx, 'olio.llm.promptConfig', { name: 'ImageNotesPrompt-' + RUN_TS, groupId: chat.id, groupPath: chat.path, system: ['You are brief.'] });
        _cfg = await createObject(ctx, 'olio.llm.chatConfig', {
            name: 'ImageNotesCfg-' + RUN_TS, groupId: chat.id, groupPath: chat.path,
            model: 'qwen3:8b', serviceType: 'ollama', stream: false, prune: true, messageTrim: 20,
            systemCharacter: { schema: 'olio.charPerson', objectId: _bob.objectId, id: _bob.id },
            userCharacter: { schema: 'olio.charPerson', objectId: _sally.objectId, id: _sally.id },
            promptConfig: { schema: 'olio.llm.promptConfig', objectId: _prompt.objectId, id: _prompt.id }
        });
        if (!_cfg || !_cfg.objectId || !_prompt || !_prompt.objectId) throw new Error('config create failed');
        await ensurePath(ctx, 'auth.group', 'data', '~/ChatRequests');
        console.log('[image-notes] fixture=' + _img.objectId + ' bob=' + _bob.objectId + ' sally=' + _sally.objectId + ' cfg=' + _cfg.objectId);
        await apiLogout(ctx);
    } finally {
        await ctx.dispose();
    }
});

async function openChat(page) {
    await openChat(page);
    await page.waitForURL(/.*#!\/chat/, { timeout: 15000 });
    await expect(page.locator('button[title="Sessions"]')).toBeVisible({ timeout: 20000 });
    let wizardTitle = page.locator('text=Chat Library Setup');
    if (await wizardTitle.isVisible()) {
        await page.locator('button:has-text("Cancel")').click();
        await expect(wizardTitle).not.toBeVisible({ timeout: 5000 });
    }
}

/// Sessions panel -> filter -> row click, waiting for the history fetch and the header (bugfix3-live pattern).
async function selectSession(page, name) {
    await page.locator('button[title="Sessions"]').click();
    await page.locator('input[placeholder="Filter sessions..."]').fill(name);
    let row = page.locator('div.cursor-pointer:has(span[title="Delete session"])', { hasText: name });
    await expect(row, 'the seeded session must be listed').toHaveCount(1, { timeout: 15000 });
    /// The select callback is wired inside the async initChatView (chat.js ConversationManager.onSelect);
    /// a click that lands before it only highlights the row. Retry until the history request fires.
    let fetched = false;
    for (let attempt = 0; attempt < 5 && !fetched; attempt++) {
        let wait = page.waitForResponse(r => r.url().includes('/rest/chat/history'), { timeout: 8000 }).then(() => true, () => false);
        await row.click();
        fetched = await wait;
    }
    expect(fetched, 'selecting the session must fetch its history').toBe(true);
    await expect(page.locator('h2', { hasText: name }), 'chat header must show the selected session').toBeVisible({ timeout: 15000 });
}

/// Read the session's stored messages straight from the server (cache:false).
async function readSession(page, sessionType, sessionId) {
    return page.evaluate(async ({ sessionType, sessionId }) => {
        let { am7client } = await import('/src/core/am7client.js');
        let { page: pg } = await import('/src/core/pageClient.js');
        let q = am7client.newQuery(sessionType);
        q.field('id', sessionId);
        q.cache(false);
        q.entity.request.push('id', 'messages');
        let qr = await pg.search(q);
        return qr && qr.results && qr.results.length ? qr.results[0].messages : null;
    }, { sessionType, sessionId });
}

test.describe('Chat image action notes (live backend)', () => {

    test.beforeEach(async ({ page }) => {
        await login(page, { user: _user.testUserName, password: _user.testPassword });
        await openChat(page);
    });

    test('note helpers: build, detect, idempotent replace, and the real tagger caption', async ({ page }) => {
        test.setTimeout(180000);
        let r = await page.evaluate(async (oid) => {
            let { am7imageTokens: t } = await import('/src/chat/imageTokens.js');
            let bob = { firstName: 'Bob', gender: 'male' };
            let sally = { firstName: 'Sally', gender: 'female' };
            let tok = '${image.' + oid + '.selfie,casual}';
            let note = t.buildImageNote(bob, ['selfie', 'casual'], 'a man smiling on a porch');
            let noCap = t.buildImageNote(sally, ['casual'], null);
            let nullCap = t.buildImageNote(sally, ['casual'], 'null');
            let once = t.replaceUnannotated('hi ' + tok + ' bye', tok, tok + note);
            let twice = t.replaceUnannotated(once, tok, tok + note);
            let two = t.replaceUnannotated(once + ' ' + tok, tok, tok + note);
            let caption = await t.describeImage(oid);
            return {
                note, noCap, nullCap, once, twice, two,
                hasOnce: t.hasImageNote(once, tok), needsOnce: t.needsImageNote(once, tok),
                needsTwo: t.needsImageNote(two, tok), caption
            };
        }, _img.objectId);
        console.log('[image-notes] helpers: ' + JSON.stringify(r));

        expect(r.note).toBe('<private>[image] Bob shares a picture of himself (selfie, casual): a man smiling on a porch.</private>');
        expect(r.noCap).toBe('<private>[image] Sally shares a picture of herself (casual), posing for the camera.</private>');
        expect(r.nullCap, 'a literal "null" caption is treated as absent').toBe(r.noCap);
        expect(r.hasOnce).toBe(true);
        expect(r.needsOnce).toBe(false);
        expect(r.twice, 'second replace must not stack a second note').toBe(r.once);
        expect(r.needsTwo, 'a second, un-annotated occurrence still needs its note').toBe(false);
        expect((r.two.match(/\[image\]/g) || []).length, 'each occurrence annotated exactly once').toBe(2);
        expect(r.caption, 'the auto-tagger must caption the fixture (GET /tag/apply)').toBeTruthy();
    });

    test('resolving a chat persists one note per token, in the right message, hidden in the view', async ({ page }) => {
        test.setTimeout(240000);
        page.on('console', mm => { if (mm.type() === 'error' || mm.type() === 'warning' || /image|resolve|patch/i.test(mm.text())) console.log('[browser ' + mm.type() + '] ' + mm.text().substring(0, 300)); });
        page.on('pageerror', e => console.log('[pageerror] ' + e.message));
        let name = 'ImageNotes-' + RUN_TS + '-' + Date.now().toString(36);
        let tok = '${image.' + _img.objectId + '.selfie,casual}';
        let utok = '${image.' + _img.objectId + '.casual}';

        let setup = await page.evaluate(async ({ name, cfgOid, promptOid, tok, utok }) => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { am7view } = await import('/src/core/view.js');
            let { page: pg } = await import('/src/core/pageClient.js');
            let req = await am7chat.getChatRequest(name, { objectId: cfgOid }, { objectId: promptOid });
            if (!req) return { error: 'chat/new failed' };
            let q0 = am7client.newQuery('olio.llm.chatRequest');
            q0.field('objectId', req.objectId);
            q0.cache(false);
            q0.entity.request.push('id', 'objectId', 'session', 'sessionType');
            let qr0 = await pg.search(q0);
            let full = qr0 && qr0.results && qr0.results.length ? qr0.results[0] : null;
            if (!full || !full.session) return { error: 'no session' };
            let q = am7view.viewQuery(am7model.newInstance(full.sessionType));
            q.field('id', full.session.id);
            q.cache(false);
            q.entity.request.push('messages');
            let sess = (await pg.search(q)).results[0];
            if (!sess.messages) sess.messages = [];
            sess.messages.push({ schema: 'olio.llm.openai.openaiMessage', role: 'assistant', content: 'Here you go ' + tok });
            sess.messages.push({ schema: 'olio.llm.openai.openaiMessage', role: 'user', content: 'Me too ' + utok });
            if (!sess[am7model.jsonModelKey]) sess[am7model.jsonModelKey] = full.sessionType;
            let patched = await pg.patchObject(sess);
            am7client.clearCache(full.sessionType);
            await Promise.resolve(am7client.clearCache('olio.llm.chatRequest', false));
            return { sessionId: full.session.id, sessionType: full.sessionType, patched, count: sess.messages.length };
        }, { name, cfgOid: _cfg.objectId, promptOid: _prompt.objectId, tok, utok });
        expect(setup.error, JSON.stringify(setup)).toBeUndefined();
        console.log('[image-notes] seeded ' + JSON.stringify(setup));

        // Select the session: rendering triggers resolveChatImages for both tokens.
        await selectSession(page, name);

        // Poll the stored session until both notes land.
        let msgs = null;
        await expect.poll(async () => {
            msgs = await readSession(page, setup.sessionType, setup.sessionId);
            if (!msgs) return 0;
            return msgs.filter(mm => mm.content && mm.content.indexOf('<private>[image]') !== -1).length;
        }, { timeout: 150000, intervals: [2000] }).toBe(2);

        let a = msgs.find(mm => mm.role === 'assistant' && mm.content.indexOf(tok) !== -1);
        let u = msgs.find(mm => mm.role === 'user' && mm.content.indexOf(utok) !== -1);
        console.log('[image-notes] assistant: ' + a.content);
        console.log('[image-notes] user: ' + u.content);
        expect(a.content.indexOf(tok + '<private>[image] Bob shares a picture of himself (selfie, casual)'), 'assistant note follows the token, names Bob').toBe('Here you go '.length);
        expect(u.content.indexOf(utok + '<private>[image] Sally shares a picture of herself (casual)'), 'user note follows the token, names Sally').toBe('Me too '.length);
        expect((a.content.match(/\[image\]/g) || []).length).toBe(1);
        expect((u.content.match(/\[image\]/g) || []).length).toBe(1);

        // Hidden in the chat view, while the image itself still renders.
        await expect(page.locator('text=shares a picture of')).toHaveCount(0);
        await expect(page.locator('img[data-token-key^="' + _img.objectId + '"]').first()).toBeVisible({ timeout: 30000 });

        // Idempotent across a fresh page load (empty in-memory cache re-triggers resolution).
        await page.reload();
        await openChat(page);
        await selectSession(page, name);
        await expect(page.locator('img[data-token-key^="' + _img.objectId + '"]').first()).toBeVisible({ timeout: 60000 });
        await page.waitForTimeout(8000);
        let after = await readSession(page, setup.sessionType, setup.sessionId);
        let total = after.map(mm => (mm.content || '').match(/\[image\]/g) || []).reduce((n, x) => n + x.length, 0);
        expect(total, 'no notes stacked after a reload').toBe(2);
    });
});
