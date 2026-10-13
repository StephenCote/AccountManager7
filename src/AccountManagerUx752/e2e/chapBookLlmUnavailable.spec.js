/**
 * ChapBook — LLM-unavailable signal E2E (client half of the backend llmUnavailable/llmDegraded fix).
 *
 * The LLM IS reachable from the Docker stack (Ollama on 192.168.1.42 and the in-stack LiteLLM both
 * answer), so a test that merely clicks Regenerate / Render would exercise the SUCCESS path. Each UI
 * test therefore CREATES its own degraded condition — a chatConfig whose system.connection points at
 * an address that can never answer — and the backend's LLM landscape-prompt STEP hard-fails on it.
 * For a freshly created ChapBook scene (which carries a no-LLM fallback prompt) the backend then
 * renders on that STORED prompt and returns the DEGRADED shape:
 *   { rendered:true, skipped:false, llmUnavailable:true, llmDegraded:true }.
 * (The skip-branch client messaging is proven by chapBookRender.test.js SHAPE 2; it is not naturally
 * reachable against this backend because a fallback prompt always exists.)
 *
 * How each test gets its chat config to the unreachable host:
 *   - bulk (Reader → Render, as e2etest_shared): a THROW-AWAY chatConfig '000-e2e-llm-unreachable'
 *     (connection → http://127.0.0.1:9, never a real host) is created in the shared user's ~/Chat,
 *     SELECTED in the Render Settings dialog's "Chat Config" picker, sent as `chatConfig` on every
 *     per-scene generate POST, and deleted again in `finally`.
 *   - per-scene (Review → Regenerate): doRegenerateScene (chapBook.js) resolves the chat config BY NAME
 *     ('contentAnalysis' via fetchDefaultChatConfigName) and ignores every picker, so the only way to
 *     select an unreachable config on that path is to be a user whose OWN ~/Chat holds a
 *     'contentAnalysis' that points at one: ChatUtil.resolveConfig consults the caller's own ~/Chat
 *     (owner + name) before the shared library. The test runs as the dedicated persistent non-admin
 *     e2etest_pbdown (ensureUnreachableChatConfig → http://192.0.2.1:11434, RFC 5737 TEST-NET-1),
 *     the same fixture pictureBookUnreachableLlmUx.spec.js owns; it is NOT deleted afterwards.
 *     e2etest_shared's own 'contentAnalysis' (another spec's fixture) points at the REAL LLM and is
 *     never touched.
 *
 * These tests prove the CLIENT surfaces a DISTINCT, user-visible signal for that hard LLM/config
 * fault — clearly different from a benign "run Analyze" skip and from plain success:
 *   - per-scene (Review → Regenerate): a distinct WARNING toast ("Rendered using the stored prompt …")
 *   - bulk (Reader → Render):          a distinct ERROR summary toast ("… scene(s) affected by an
 *                                       unavailable LLM/chat config")
 *
 * Run against the Docker stack (never admin — spec-owned non-admin users only):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookLlmUnavailable.spec.js \
 *     --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, ensureChatConfig, ensureUnreachableChatConfig, deleteObject,
    UNREACHABLE_LLM_USER, UNREACHABLE_LLM_PASSWORD, UNREACHABLE_LLM_URL
} from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results');

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';

// Bulk-test throw-away fixture (shared user's ~/Chat). The '000-' prefix puts the row on page 1 of the
// picker (25-row cap, name-ascending). 127.0.0.1:9 inside the Tomcat container: nothing listens there,
// so the LLM step is refused immediately — never a real host.
const THROWAWAY_CFG = '000-e2e-llm-unreachable';
const THROWAWAY_CONN = '000-e2e-llm-unreachable-conn';
const THROWAWAY_URL = 'http://127.0.0.1:9';

// Exact wording the client raises for each distinct signal (kept in sync with chapBook.js constants).
const DEGRADED_MSG = 'Rendered using the stored prompt — the LLM prompt step was unavailable (no usable chat config or the LLM is unreachable).';
const BULK_ERROR_CLAUSE = 'scene(s) affected by an unavailable LLM/chat config';
// Benign wordings the distinct signals must NOT be confused with.
const BENIGN_REGEN_SKIP = 'Still no usable prompt — Analyze the poem or edit the stanza, then regenerate';
const BENIGN_REGEN_SUCCESS = 'Scene regenerated';

// A single-stanza poem → one page/scene, so each render is a single per-scene call.
const POEM = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.`;

function b64(s) { return Buffer.from(s).toString('base64'); }
function encodePath(p) { return 'B64-' + b64(p).replace(/=/g, '%3D'); }

async function restLoginAs(request, name, password) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: name, credential: b64(password), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, name + ' login failed: ' + resp.status()).toBe(true);
}

// The caller's own ~/Poems group ({ id, organizationId }) — created on first use.
async function ensurePoemsGroup(request) {
    const resp = await request.get(REST + '/path/make/auth.group/data/' + encodePath('~/Poems'));
    const body = await resp.json().catch(() => null);
    expect(body && body.id && typeof body.organizationId === 'number', 'could not ensure ~/Poems group: ' + JSON.stringify(body)).toBe(true);
    return { id: body.id, organizationId: body.organizationId };
}

// Find a record by name in the caller's ~/Chat (cache:false so a just-created/deleted record is seen).
async function findInChat(request, type, name, orgId, chatGroupId) {
    const resp = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: type, cache: false, recordCount: 1,
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId },
                { name: 'groupId', comparator: 'equals', value: Number(chatGroupId) }
            ],
            request: ['id', 'objectId', 'name']
        }
    });
    const body = await resp.json().catch(() => null);
    return (body && body.results && body.results.length) ? body.results[0] : null;
}

// Seed a poem (idempotent by name) and create a FRESH ChapBook from it; returns its bookObjectId.
async function createFreshBook(request, poemsGroupId, orgId, tag) {
    const poemName = 'cb-llmunavail-' + tag;
    const search = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.cb.poem',
            fields: [
                { name: 'name', comparator: 'equals', value: poemName },
                { name: 'organizationId', comparator: 'equals', value: orgId },
                { name: 'groupId', comparator: 'equals', value: Number(poemsGroupId) }
            ],
            request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
        }
    });
    const sBody = await search.json().catch(() => null);
    let poemOid = (sBody && sBody.results && sBody.results[0]) ? sBody.results[0].objectId : null;
    if (!poemOid) {
        const cResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.cb.poem', name: poemName, title: 'LLM-Unavailable Probe',
                author: 'Stephen W. Cote', groupId: poemsGroupId, text: POEM
            }
        });
        const created = await cResp.json().catch(() => null);
        poemOid = created && created.objectId;
    }
    expect(poemOid, 'failed to seed poem').toBeTruthy();

    const slug = 'cb-llmunavail-' + tag + '-' + Date.now().toString(36);
    const createResp = await request.post(CB_REST + '/create', {
        data: { slug, title: 'LLM-Unavailable ' + tag, poemObjectIds: [poemOid], maxLinesPerPage: 20 }
    });
    expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
    const created = await createResp.json();
    const bookOid = created && (created.bookObjectId || created.objectId);
    expect(bookOid, 'no bookObjectId in create response').toBeTruthy();
    return bookOid;
}

// Log in via REST (page.request shares the browser context's cookies) + stub the WebSocket (Docker
// nginx strips the WS cookie) + install a toast-capturing poller BEFORE navigation so a 5s
// auto-dismissed toast can't be missed by a slow assert.
async function loginAsUser(page, name, password) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: name, credential: b64(password), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'browser-session login as ' + name + ' failed: ' + resp.status()).toBe(true);
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {} close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;

        // Capture every toast body text as it appears. Toasts auto-dismiss (~5s), so scrape the DOM on
        // a short interval and record CUMULATIVELY. A setInterval poller (not a MutationObserver) is
        // used deliberately: it self-arms even if document.documentElement isn't ready at init time and
        // cannot be silently dropped, so a fast render's brief toast is never missed. Guard the array
        // and the timer so a re-run of this init script (fires on every navigation) never wipes history.
        window.__toasts = window.__toasts || [];
        if (!window.__toastPoll) {
            window.__toastPoll = setInterval(() => {
                document.querySelectorAll('.toast-text').forEach((el) => {
                    const t = (el.textContent || '').trim();
                    if (t && window.__toasts.indexOf(t) === -1) window.__toasts.push(t);
                });
            }, 150);
        }
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null,
        { timeout: 30000 }
    );
}

const capturedToasts = (page) => page.evaluate(() => window.__toasts || []);

// Record (without intercepting) every per-scene generate POST body the page sends, so the test can
// prove WHICH chatConfig name went to the backend.
function captureGeneratePosts(page) {
    const posts = [];
    page.on('request', (req) => {
        if (req.method() === 'POST' && /\/olio\/chap-book\/scene\/[^/]+\/generate$/.test(req.url())) {
            let body = null;
            try { body = JSON.parse(req.postData() || 'null'); } catch { body = null; }
            posts.push({ url: req.url(), body });
        }
    });
    return () => posts;
}

test.describe('ChapBook — LLM-unavailable client signal', () => {
    test.describe.configure({ timeout: 180000 });

    let orgId = null;
    let poemsGroupId = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLoginAs(request, SHARED_USER, SHARED_PASSWORD);
        const poems = await ensurePoemsGroup(request);
        poemsGroupId = poems.id;
        orgId = poems.organizationId;
        await request.get(REST + '/logout');
    });

    // ── Layer-isolation gate: prove the DEPLOYED backend returns the new fields ──────────────────
    test('REST: per-scene generate returns llmUnavailable on the Docker backend (LLM unreachable)', async ({ request }) => {
        await restLoginAs(request, SHARED_USER, SHARED_PASSWORD);
        const bookOid = await createFreshBook(request, poemsGroupId, orgId, 'rest');
        const pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        expect(pagesResp.ok(), 'pages fetch failed: ' + pagesResp.status()).toBe(true);
        const pages = await pagesResp.json();
        expect(Array.isArray(pages) && pages.length, 'book has no scenes').toBeTruthy();
        const sceneOid = pages[0].objectId;

        const gResp = await request.post(CB_REST + '/scene/' + sceneOid + '/generate', {
            data: { schema: 'olio.pictureBookRequest' }
        });
        expect(gResp.ok(), 'generate failed: ' + gResp.status()).toBe(true);
        const body = await gResp.json();
        console.log('[llmUnavail] REST generate response:', JSON.stringify(body));
        // The whole point of the fix: the field exists on the wire and is TRUE when the LLM step
        // could not run (here: LLM unreachable from Docker). A normal soft refusal would NOT set it.
        expect(body.llmUnavailable, 'backend did not report llmUnavailable — is the fix deployed?').toBe(true);
    });

    // ── Per-scene distinct signal: Review → Regenerate raises the DEGRADED warning toast ─────────
    test('UI per-scene: Review Regenerate raises the distinct degraded WARNING toast (not the benign skip/success)', async ({ page, request }) => {
        // Connect to TEST-NET-1 can take the full connect timeout (the backend tries twice) before the
        // degraded SD render even starts; timings are never the assertion, so give it ample room.
        test.setTimeout(300000);

        // Degraded condition: the dedicated non-admin whose OWN 'contentAnalysis' can never answer.
        const fixture = await ensureUnreachableChatConfig(request);
        expect(fixture.serverUrl, 'unreachable fixture must point at TEST-NET-1').toBe(UNREACHABLE_LLM_URL);
        console.log('[llmUnavail] per-scene fixture: user=' + UNREACHABLE_LLM_USER + ' chatConfig id=' + fixture.configId
            + ' connection id=' + fixture.connectionId + ' -> ' + fixture.serverUrl);

        await restLoginAs(request, UNREACHABLE_LLM_USER, UNREACHABLE_LLM_PASSWORD);
        const poems = await ensurePoemsGroup(request);
        const bookOid = await createFreshBook(request, poems.id, poems.organizationId, 'review');
        await request.get(REST + '/logout');

        await loginAsUser(page, UNREACHABLE_LLM_USER, UNREACHABLE_LLM_PASSWORD);

        // Premise, proven from the browser's own session: the by-name resolution Regenerate relies on
        // (ChatUtil.resolveConfig, the same call the render endpoint makes) lands on the UNREACHABLE
        // user-owned record, not the shared library's.
        const resolvedResp = await page.request.get(REST + '/chat/library/chat/contentAnalysis');
        expect(resolvedResp.ok(), 'contentAnalysis did not resolve for ' + UNREACHABLE_LLM_USER + ': ' + resolvedResp.status()).toBe(true);
        const resolved = await resolvedResp.json();
        expect(resolved.id, 'contentAnalysis resolved to a record other than the unreachable fixture').toBe(fixture.configId);

        const getPosts = captureGeneratePosts(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/review/' + oid; }, bookOid);

        // A fresh (un-prompted) scene card exposes the "Regenerate" affordance.
        const regenBtn = page.locator('button[data-testid="cb-page-regenerate"]').first();
        await expect(regenBtn, 'Regenerate button not visible on a fresh un-prompted scene').toBeVisible({ timeout: 30000 });
        await expect(regenBtn, 'Regenerate button is disabled (role warning or scene saving)').toBeEnabled({ timeout: 10000 });
        await regenBtn.click();

        // The distinct degraded WARNING toast must appear — and NOT the benign skip / plain-success text.
        await expect.poll(async () => await capturedToasts(page), {
            message: 'degraded WARNING toast never appeared',
            timeout: 240000, intervals: [1000]
        }).toContain(DEGRADED_MSG);

        const toasts = await capturedToasts(page);
        console.log('[llmUnavail] per-scene toasts:', JSON.stringify(toasts));
        expect(toasts, 'must not show the benign "run Analyze" skip text').not.toContain(BENIGN_REGEN_SKIP);
        expect(toasts, 'must not show the plain-success text').not.toContain(BENIGN_REGEN_SUCCESS);

        // The page sent the by-name default — the name whose user-owned record is the unreachable one.
        const posts = getPosts();
        expect(posts.length, 'no per-scene generate POST was sent').toBeGreaterThan(0);
        expect(posts[0].body && posts[0].body.chatConfig, 'Regenerate did not send the by-name default chatConfig').toBe('contentAnalysis');
        console.log('[llmUnavail] per-scene generate body:', JSON.stringify(posts[0].body));

        fs.mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(OUT_DIR, 'llmUnavail-perscene-degraded-warning.png'), fullPage: true });
    });

    // ── PF-6: per-scene Regenerate honours the Review tab's "Default chat config" pick ───────────
    // Before the fix doRegenerateScene always resolved the config by name ('contentAnalysis') and
    // ignored the Review tab's picker, so a user whose default pointed at a live LLM could never
    // regenerate a scene with a different (here: unreachable) config. Proven as e2etest_shared, whose
    // 'contentAnalysis' points at the REAL LLM: the only way the throw-away config can reach the wire
    // is through the picker.
    test('UI per-scene: Regenerate sends the chat config picked on the Review tab, not the by-name default', async ({ page, request }) => {
        test.setTimeout(300000);

        await restLoginAs(request, SHARED_USER, SHARED_PASSWORD);
        const chatDirResp = await request.get(REST + '/path/make/auth.group/data/' + encodePath('~/Chat'));
        const chatDir = await chatDirResp.json().catch(() => null);
        expect(chatDir && chatDir.id, 'could not ensure ~/Chat for ' + SHARED_USER).toBeTruthy();
        const chatGroupId = chatDir.id;

        let cleanupError = null;
        try {
            const cfgName = await ensureChatConfig(request, orgId, {
                configName: THROWAWAY_CFG, connectionName: THROWAWAY_CONN,
                serverUrl: THROWAWAY_URL, dialect: 'ollama', upstream: 'ollama', requestTimeout: 30
            });
            expect(cfgName, 'throw-away unreachable chatConfig was not created').toBe(THROWAWAY_CFG);
            const cfgRec = await findInChat(request, 'olio.llm.chatConfig', THROWAWAY_CFG, orgId, chatGroupId);
            expect(cfgRec && cfgRec.objectId, 'throw-away chatConfig not found in ~/Chat after create').toBeTruthy();
            console.log('[llmUnavail] pick fixture: chatConfig ' + THROWAWAY_CFG + ' id=' + cfgRec.id + ' -> ' + THROWAWAY_URL);

            const bookOid = await createFreshBook(request, poemsGroupId, orgId, 'pick');
            await request.get(REST + '/logout');

            await loginAsUser(page, SHARED_USER, SHARED_PASSWORD);
            const getPosts = captureGeneratePosts(page);
            await page.evaluate((oid) => { window.location.hash = '!/chap-book/review/' + oid; }, bookOid);

            const regenBtn = page.locator('button[data-testid="cb-page-regenerate"]').first();
            await expect(regenBtn, 'Regenerate button not visible on a fresh un-prompted scene').toBeVisible({ timeout: 30000 });

            // Pick the throw-away config in the Review tab's "Default chat config" control: single-click
            // the row (a double-click descends into it), then the picker's confirm (check) button.
            const cfgControl = page.locator('div:has(> label:text-is("Default chat config")) > div.cursor-pointer').first();
            await expect(cfgControl, 'Review tab "Default chat config" control missing').toBeVisible({ timeout: 10000 });
            await cfgControl.click();
            const overlay = page.locator('.am7-picker-overlay');
            await expect(overlay, 'chat-config picker overlay did not open').toBeVisible({ timeout: 15000 });
            const row = overlay.locator('tr.tabular-row', { hasText: THROWAWAY_CFG });
            await expect(row.first(), 'throw-away config row not visible in picker (page-1 placement?)').toBeVisible({ timeout: 20000 });
            await row.first().click();
            await expect(row.first(), 'row did not register as selected').toHaveClass(/tabular-row-active/, { timeout: 5000 });
            const confirmBtn = overlay.locator('button:has(span.material-symbols-outlined:text-is("check"))').first();
            await expect(confirmBtn, 'picker confirm (check) button missing').toBeVisible({ timeout: 5000 });
            await confirmBtn.click();
            await expect(overlay, 'picker did not close after confirm').toBeHidden({ timeout: 10000 });
            await expect(cfgControl, 'control did not show the selected throw-away config').toContainText(THROWAWAY_CFG, { timeout: 10000 });

            await expect(regenBtn, 'Regenerate button is disabled (role warning or scene saving)').toBeEnabled({ timeout: 10000 });
            await regenBtn.click();

            // The picked config can never answer, so the scene renders degraded on its stored prompt.
            await expect.poll(async () => await capturedToasts(page), {
                message: 'degraded WARNING toast never appeared',
                timeout: 240000, intervals: [1000]
            }).toContain(DEGRADED_MSG);

            const posts = getPosts();
            expect(posts.length, 'no per-scene generate POST was sent').toBeGreaterThan(0);
            expect(posts[0].body && posts[0].body.chatConfig,
                'Regenerate ignored the Review tab pick: ' + JSON.stringify(posts[0].body)).toBe(THROWAWAY_CFG);
            console.log('[llmUnavail] pick generate body:', JSON.stringify(posts[0].body));

            fs.mkdirSync(OUT_DIR, { recursive: true });
            await page.screenshot({ path: path.join(OUT_DIR, 'llmUnavail-perscene-picked-config.png'), fullPage: true });
        } finally {
            try {
                await restLoginAs(request, SHARED_USER, SHARED_PASSWORD);
                for (const [type, name] of [['olio.llm.chatConfig', THROWAWAY_CFG], ['system.connection', THROWAWAY_CONN]]) {
                    const rec = await findInChat(request, type, name, orgId, chatGroupId);
                    if (rec && rec.objectId) {
                        await deleteObject(request, type, rec.objectId);
                        const after = await findInChat(request, type, name, orgId, chatGroupId);
                        if (after) throw new Error(type + ' ' + name + ' (id=' + rec.id + ') still present after DELETE');
                        console.log('[llmUnavail] cleanup: deleted ' + type + ' ' + name + ' id=' + rec.id);
                    } else {
                        console.log('[llmUnavail] cleanup: ' + type + ' ' + name + ' not present (nothing to delete)');
                    }
                }
                await request.get(REST + '/logout');
            } catch (e) {
                cleanupError = e;
            }
        }
        expect(cleanupError, 'throw-away fixture cleanup failed: ' + (cleanupError && cleanupError.message)).toBeNull();
    });

    // ── Bulk distinct signal: Reader → Render raises the ERROR summary toast ─────────────────────
    test('UI bulk: Reader Render raises the distinct ERROR summary toast (affected by unavailable LLM/chat config)', async ({ page, request }) => {
        test.setTimeout(420000);

        await restLoginAs(request, SHARED_USER, SHARED_PASSWORD);
        const chatDirResp = await request.get(REST + '/path/make/auth.group/data/' + encodePath('~/Chat'));
        const chatDir = await chatDirResp.json().catch(() => null);
        expect(chatDir && chatDir.id, 'could not ensure ~/Chat for ' + SHARED_USER).toBeTruthy();
        const chatGroupId = chatDir.id;

        let cleanupError = null;
        try {
            // Degraded condition: a THROW-AWAY chatConfig in the shared user's ~/Chat whose connection
            // points at a port nothing listens on. Created in the shared user's own session (never admin).
            const cfgName = await ensureChatConfig(request, orgId, {
                configName: THROWAWAY_CFG, connectionName: THROWAWAY_CONN,
                serverUrl: THROWAWAY_URL, dialect: 'ollama', upstream: 'ollama', requestTimeout: 30
            });
            expect(cfgName, 'throw-away unreachable chatConfig was not created').toBe(THROWAWAY_CFG);
            const cfgRec = await findInChat(request, 'olio.llm.chatConfig', THROWAWAY_CFG, orgId, chatGroupId);
            expect(cfgRec && cfgRec.objectId, 'throw-away chatConfig not found in ~/Chat after create').toBeTruthy();
            console.log('[llmUnavail] bulk fixture: chatConfig ' + THROWAWAY_CFG + ' id=' + cfgRec.id + ' -> ' + THROWAWAY_URL);

            const bookOid = await createFreshBook(request, poemsGroupId, orgId, 'reader');
            // Confirm the reader will have at least one scene to render.
            const pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
            const pages = await pagesResp.json();
            expect(Array.isArray(pages) && pages.length, 'reader book has no scenes').toBeTruthy();
            await request.get(REST + '/logout');

            await loginAsUser(page, SHARED_USER, SHARED_PASSWORD);
            const getPosts = captureGeneratePosts(page);
            await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);

            // The reader opens on its Cover; Begin turns the first stanza page.
            const begin = page.locator('button:has-text("Begin")');
            await expect(begin, 'reader Cover "Begin" button not visible').toBeVisible({ timeout: 30000 });
            await begin.click();
            await expect(page.locator('text=/Page \\d+ of \\d+/').first(), 'reader did not load book pages')
                .toBeVisible({ timeout: 30000 });

            // Reader header Render (the only orange button) → the "Render Settings" dialog.
            const headerRender = page.locator('button.bg-orange-600').filter({ hasText: 'Render' }).first();
            await expect(headerRender, 'reader Render button not visible/enabled').toBeVisible({ timeout: 10000 });
            await headerRender.click();

            const renderDialog = page.locator('div.fixed.inset-0.z-50').filter({ hasText: 'Render Settings' });
            await expect(renderDialog, 'Render Settings dialog did not open').toBeVisible({ timeout: 10000 });

            // Select the throw-away config in the dialog's "Chat Config" picker: single-click the row
            // (a double-click descends into it), then the picker's confirm (check) button.
            const chatCfgControl = renderDialog.locator('div.mb-4:has(label.field-label:text-is("Chat Config")) div.text-field-full');
            await expect(chatCfgControl, 'Render dialog Chat Config control missing').toBeVisible({ timeout: 10000 });
            await chatCfgControl.click();
            const overlay = page.locator('.am7-picker-overlay');
            await expect(overlay, 'chat-config picker overlay did not open').toBeVisible({ timeout: 15000 });
            const row = overlay.locator('tr.tabular-row', { hasText: THROWAWAY_CFG });
            await expect(row.first(), 'throw-away config row not visible in picker (page-1 placement?)').toBeVisible({ timeout: 20000 });
            await row.first().click();
            await expect(row.first(), 'row did not register as selected').toHaveClass(/tabular-row-active/, { timeout: 5000 });
            const confirmBtn = overlay.locator('button:has(span.material-symbols-outlined:text-is("check"))').first();
            await expect(confirmBtn, 'picker confirm (check) button missing').toBeVisible({ timeout: 5000 });
            await confirmBtn.click();
            await expect(overlay, 'picker did not close after confirm').toBeHidden({ timeout: 10000 });
            await expect(chatCfgControl, 'Chat Config control did not show the selected throw-away config').toContainText(THROWAWAY_CFG, { timeout: 10000 });

            await page.waitForTimeout(2000); // let the SD config load so a real sdConfig is sent
            const dialogRender = renderDialog.locator('button.bg-orange-600');
            await expect(dialogRender, 'dialog Render button not visible').toBeVisible({ timeout: 10000 });
            await dialogRender.click();

            // The bulk summary toast must carry the distinct LLM-unavailable clause; it must NOT be the
            // plain all-clear success wording.
            await expect.poll(async () => await capturedToasts(page), {
                message: 'bulk ERROR summary toast never appeared',
                timeout: 240000, intervals: [2000]
            }).toEqual(expect.arrayContaining([expect.stringContaining(BULK_ERROR_CLAUSE)]));

            const toasts = await capturedToasts(page);
            console.log('[llmUnavail] bulk toasts:', JSON.stringify(toasts));
            expect(
                toasts.some((t) => /^Render complete: \d+ scene\(s\) generated$/.test(t)),
                'must not show the plain all-clear success wording'
            ).toBe(false);

            // Every per-scene generate POST the bulk loop sent carried the SELECTED throw-away config —
            // the degraded outcome came from the picker choice, not from some other resolution.
            const posts = getPosts();
            expect(posts.length, 'no per-scene generate POST was sent by the bulk render').toBeGreaterThan(0);
            posts.forEach((p) => {
                expect(p.body && p.body.chatConfig, 'bulk generate POST did not carry the selected chatConfig: ' + JSON.stringify(p.body)).toBe(THROWAWAY_CFG);
            });
            console.log('[llmUnavail] bulk generate POSTs: ' + posts.length + ', chatConfig=' + posts[0].body.chatConfig);

            fs.mkdirSync(OUT_DIR, { recursive: true });
            await page.screenshot({ path: path.join(OUT_DIR, 'llmUnavail-bulk-error-summary.png'), fullPage: true });
        } finally {
            // Remove the throw-away fixture (config first — it holds the FK — then its connection), as
            // the shared user, and prove both are gone.
            try {
                await restLoginAs(request, SHARED_USER, SHARED_PASSWORD);
                for (const [type, name] of [['olio.llm.chatConfig', THROWAWAY_CFG], ['system.connection', THROWAWAY_CONN]]) {
                    const rec = await findInChat(request, type, name, orgId, chatGroupId);
                    if (rec && rec.objectId) {
                        await deleteObject(request, type, rec.objectId);
                        const after = await findInChat(request, type, name, orgId, chatGroupId);
                        if (after) throw new Error(type + ' ' + name + ' (id=' + rec.id + ') still present after DELETE');
                        console.log('[llmUnavail] cleanup: deleted ' + type + ' ' + name + ' id=' + rec.id);
                    } else {
                        console.log('[llmUnavail] cleanup: ' + type + ' ' + name + ' not present (nothing to delete)');
                    }
                }
                await request.get(REST + '/logout');
            } catch (e) {
                cleanupError = e;
            }
        }
        expect(cleanupError, 'throw-away fixture cleanup failed: ' + (cleanupError && cleanupError.message)).toBeNull();
    });
});
