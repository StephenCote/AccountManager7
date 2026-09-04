/**
 * ChapBook Issue 2 — chat-config DEFAULT + user SELECTION, proven WITHOUT a live LLM.
 *
 * The bug: ChapBook analysis used to pick the FIRST/RANDOM chat config in the user's ~/Chat or a
 * system path (e.g. "coding" / "Open Chat") and immediately connect to the LLM. The fix requires:
 *   (1) default to the NAMED system-default chatConfig `contentAnalysis` (ChatUtil
 *       .DEFAULT_ANALYSIS_CHAT_CONFIG_NAME), NOT a random one;
 *   (2) the user can SELECT/override the chat config BEFORE the app connects to the LLM;
 *   (3) during edit (the reader view) there is a Re-analyze control whose chat config can be re-picked.
 *
 * How this proves it with NO live LLM (Docker cannot reach the LAN LLM at 192.168.1.42, and the whole
 * point of (2) is that selection happens BEFORE any LLM connection): we intercept the analyze POST with
 * a page.route glob on the ChapBook "analyze" endpoint and (a) CAPTURE the request body to assert it
 * carries the chosen chatConfig NAME, and (b) fulfill it with a canned 200 so no real LLM is contacted.
 * Because the request is intercepted before it leaves the browser, asserting on its body proves the
 * chosen config is what the frontend SENDS before connecting — the strongest LLM-free proof.
 *
 * The reader's Re-analyze control (chapBook.js: reanalyzeChatConfigRef / ensureReanalyzeChatConfigDefault
 * / the header picker with libraryType:'chatConfig' / analyzeReaderPoems → analyzePoem(pid, name)) is the
 * exact Issue-2c surface, and its default resolution (resolveSystemChatConfig → contentAnalysis) is the
 * same code path create/render use, so this one reader flow exercises all three requirements.
 *
 * Run against the ALREADY-RUNNING Docker stack (host 9443, 127.0.0.1 required — localhost resolves to
 * IPv6 ::1 which Docker does not map):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookChatConfigPicker.spec.js \
 *     --workers=1 --project=chromium --reporter=list
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const RESULTS = path.resolve(SPEC_DIR, '../test-results');
const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

// The named system-default the fix must resolve to (ChatUtil.DEFAULT_ANALYSIS_CHAT_CONFIG_NAME).
const SYSTEM_DEFAULT = 'contentAnalysis';
// A DISTINCT, user-owned chatConfig the test SELECTS in the picker to override the default. The
// '000-' prefix sorts it onto page 1 of the name-ascending picker list (>25-row cap, no pagination),
// guaranteeing the row is reachable. It never needs a working LLM — /analyze is intercepted.
const ALT_CONFIG_NAME = '000-cbpicker-altcfg';

const POEM_TEXT = `A lantern in the fog,\nits small deliberate flame\nrefusing the dark.`;

function b64(s) { return Buffer.from(s).toString('base64'); }
function encPath(p) { return 'B64-' + b64(p).replace(/=/g, '%3D'); }

// Shared state seeded in beforeAll.
let orgId = null;
let poemsGroupId = null;
let chatGroupId = null;
let chatGroupPath = null;
let poemOid = null;
let bookOid = null;

async function restLogin(request, name, password) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name, credential: b64(password), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'REST login failed for ' + name).toBe(true);
}

// WebSocket stub — Docker's nginx strips the session cookie on the WS upgrade, so Tomcat closes it,
// which triggers forceLogin() → redirect to #!/sig. Stub it BEFORE goto (addInitScript runs on every
// navigation, including reload). Copied verbatim from chapBookDeleteRegression.spec.js.
function wsStub() {
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
}

async function loginAndLoad(page, name, password) {
    await page.context().clearCookies();
    await restLogin(page.request, name, password);
    await page.addInitScript(wsStub);
    await page.goto('about:blank');
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

// Open the reader for `bookOid` with readerPoemIds SEEDED into localStorage (the exact durable key the
// reader's oninit re-derives from — D5), so the Re-analyze picker + Analyze controls render. Setting a
// localStorage key from the test is legitimate fixture setup, not production instrumentation.
async function openReaderWithPoems(page) {
    await page.evaluate(({ oid, pid }) => {
        localStorage.setItem('cb-poemids-' + oid, JSON.stringify([pid]));
        window.location.hash = '!/chap-book/read/' + oid;
    }, { oid: bookOid, pid: poemOid });
}

// Locators for the reader's Re-analyze controls (title strings are exact, from chapBook.js).
function configBtn(page) { return page.locator('button[title="Choose the chat config used to re-analyze poem themes"]'); }
function analyzeBtn(page) { return page.locator('button[title="Re-analyze poem themes with the selected chat config"]'); }

// Register an /analyze interceptor that captures the POST body and fulfills a canned 200 (NO LLM
// contacted). Returns a getter for the captured body. Only /analyze is intercepted — the picker's
// /rest/model/search calls are untouched.
async function interceptAnalyze(page) {
    let captured = [];
    await page.route('**/rest/olio/chap-book/analyze/**', async (route) => {
        const req = route.request();
        let body = null;
        try { body = req.postDataJSON(); } catch (_) { try { body = JSON.parse(req.postData() || '{}'); } catch (_) {} }
        captured.push({ url: req.url(), method: req.method(), body: body || {} });
        await route.fulfill({
            status: 200, contentType: 'application/json',
            body: JSON.stringify({ theme: 'intercepted', mood: 'calm', analyzed: true })
        });
    });
    return () => captured;
}

test.describe('ChapBook — Issue 2 chat-config default + selection (LLM-free, route-intercepted)', () => {
    test.describe.configure({ timeout: 120000, mode: 'serial' });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request, 'e2etest_shared', 'password');

        const poemsDir = await request.get(REST + '/path/make/auth.group/data/' + encPath('~/Poems'));
        const poemsBody = await poemsDir.json();
        expect(poemsBody && poemsBody.id, 'could not ensure ~/Poems').toBeTruthy();
        poemsGroupId = poemsBody.id;
        orgId = poemsBody.organizationId;

        const chatDir = await request.get(REST + '/path/make/auth.group/data/' + encPath('~/Chat'));
        const chatBody = await chatDir.json();
        expect(chatBody && chatBody.id, 'could not ensure ~/Chat').toBeTruthy();
        chatGroupId = chatBody.id;
        chatGroupPath = chatBody.path;

        // Seed one poem (digit-prefixed name for determinism); its objectId feeds readerPoemIds.
        const poemName = '000-cbpicker-poem-' + Date.now().toString(36);
        const poemResp = await request.post(REST + '/model', {
            data: { schema: 'olio.cb.poem', name: poemName, title: 'Picker Poem', author: 'E2E', groupId: poemsGroupId, text: POEM_TEXT }
        });
        const poemRec = await poemResp.json().catch(() => null);
        poemOid = poemRec && poemRec.objectId;
        expect(poemOid, 'poem not seeded').toBeTruthy();

        // Create a ChapBook so the reader route has a valid objectId.
        const slug = 'cbpicker-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Picker ChapBook', poemObjectIds: [poemOid], maxLinesPerPage: 4 }
        });
        expect(createResp.ok(), 'create book failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        bookOid = created && (created.objectId || created.bookObjectId);
        expect(bookOid, 'no bookObjectId').toBeTruthy();

        // Provision a DISTINCT, shared-user-owned chatConfig in ~/Chat to SELECT in the picker (Issue-2
        // override). It only needs to exist + be listable; /analyze is intercepted so no LLM is used.
        const cfgSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.llm.chatConfig',
                fields: [
                    { name: 'name', comparator: 'equals', value: ALT_CONFIG_NAME },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const cfgBody = await cfgSearch.json().catch(() => null);
        if (!(cfgBody && cfgBody.results && cfgBody.results.length)) {
            const mk = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.llm.chatConfig', name: ALT_CONFIG_NAME,
                    groupId: chatGroupId, groupPath: chatGroupPath,
                    serviceType: 'ollama', model: 'qwen3:8b'
                }
            });
            expect(mk.ok(), 'alt chatConfig create failed: ' + mk.status() + ' ' + await mk.text()).toBe(true);
        }

        await request.get(REST + '/logout');
        fs.mkdirSync(RESULTS, { recursive: true });
        console.log('[setup] orgId=' + orgId + ' poemsGroupId=' + poemsGroupId + ' chatGroupId=' + chatGroupId
            + ' poemOid=' + poemOid + ' bookOid=' + bookOid);
    });

    // ── Requirement 1: the default resolves to the NAMED system default `contentAnalysis` (not random) ──
    test('req1: reader Re-analyze control auto-resolves the default to contentAnalysis (not a random config)', async ({ page }) => {
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await openReaderWithPoems(page);

        const cfg = configBtn(page);
        await expect(cfg, 'Re-analyze chat-config control missing (readerPoemIds not seeded?)').toBeVisible({ timeout: 20000 });
        // The label is 'Resolving…' then the resolved name. It must settle on the NAMED system default.
        await expect(cfg, 'default did not resolve to the named system default contentAnalysis').toContainText(SYSTEM_DEFAULT, { timeout: 20000 });
        // And it is NOT one of the random configs the old code could have grabbed.
        const label = (await cfg.innerText()).trim();
        expect(label, 'default label unexpectedly a random config: ' + label).not.toContain('Open Chat');
        expect(label, 'default label unexpectedly a random config: ' + label).not.toContain('coding');
        await page.screenshot({ path: path.join(RESULTS, 'cbpicker-req1-default-contentAnalysis.png'), fullPage: true });
        console.log('[req1] Re-analyze default resolved to "' + label + '"');
    });

    // ── Requirement 1 (sent): the resolved default is what the frontend SENDS to /analyze, before any LLM ──
    test('req1-sent: clicking Analyze with the default sends chatConfig=contentAnalysis in the intercepted body', async ({ page }) => {
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await openReaderWithPoems(page);

        const cfg = configBtn(page);
        await expect(cfg).toBeVisible({ timeout: 20000 });
        await expect(cfg, 'default not resolved before Analyze').toContainText(SYSTEM_DEFAULT, { timeout: 20000 });

        const getCaptured = await interceptAnalyze(page);
        await analyzeBtn(page).click();

        // The intercepted analyze POST must carry the default name — proving it is chosen BEFORE the LLM
        // call (we never let the request leave the browser).
        await expect.poll(() => getCaptured().length, { timeout: 20000, message: '/analyze was never called' }).toBeGreaterThan(0);
        const calls = getCaptured();
        expect(calls[0].url, 'intercepted a non-analyze URL').toContain('/rest/olio/chap-book/analyze/');
        expect(calls[0].method).toBe('POST');
        expect(calls[0].body.chatConfig, 'analyze body did not carry the system default').toBe(SYSTEM_DEFAULT);
        console.log('[req1-sent] intercepted /analyze body.chatConfig=' + calls[0].body.chatConfig);
    });

    // ── Requirements 2 + 3: user picks a DIFFERENT config in the reader Re-analyze picker → that name is ──
    //    what the frontend sends to /analyze (selection wins, before the LLM, via the edit-view picker).
    test('req2+3: picking a different config in the Re-analyze picker sends THAT config to the intercepted /analyze', async ({ page }) => {
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await openReaderWithPoems(page);

        const cfg = configBtn(page);
        await expect(cfg).toBeVisible({ timeout: 20000 });
        await expect(cfg, 'default must resolve first so the override is a genuine change').toContainText(SYSTEM_DEFAULT, { timeout: 20000 });

        // Open the reader's Re-analyze library picker (libraryType:'chatConfig') and choose the DISTINCT
        // shared-user config. NOTE: a double-click navigates INTO the row (olio.llm.chatConfig carries a
        // groupId, so decorator.js treats it as a container to descend). The real single-select gesture
        // is: single-click the row to check it, then click the picker's confirm (check) button — which
        // fires pickerHandler(getSelected()) → onSelect(item) + close.
        await cfg.click();
        const overlay = page.locator('.am7-picker-overlay');
        await expect(overlay, 'chat-config picker overlay did not open').toBeVisible({ timeout: 15000 });
        const altRow = overlay.locator('tr.tabular-row', { hasText: ALT_CONFIG_NAME });
        await expect(altRow.first(), 'alt config row not visible in picker (page-1 placement?)').toBeVisible({ timeout: 20000 });
        await altRow.first().click();
        await expect(altRow.first(), 'row did not register as selected').toHaveClass(/tabular-row-active/, { timeout: 5000 });
        const confirmBtn = overlay.locator('button:has(span.material-symbols-outlined:text-is("check"))').first();
        await expect(confirmBtn, 'picker confirm (check) button missing').toBeVisible({ timeout: 5000 });
        await confirmBtn.click();
        await expect(overlay, 'picker did not close after confirm').toBeHidden({ timeout: 10000 });

        // The control now reflects the USER'S choice, not the default.
        await expect(cfg, 'Re-analyze control did not update to the selected config').toContainText(ALT_CONFIG_NAME, { timeout: 10000 });
        await expect(cfg).not.toContainText(SYSTEM_DEFAULT);
        await page.screenshot({ path: path.join(RESULTS, 'cbpicker-req2-selected-altconfig.png'), fullPage: true });

        // Analyze → the intercepted body must carry the SELECTED config, proving user selection wins and
        // happens before the LLM is contacted (through the edit/reader Re-analyze control — Issue 2c).
        const getCaptured = await interceptAnalyze(page);
        await analyzeBtn(page).click();
        await expect.poll(() => getCaptured().length, { timeout: 20000, message: '/analyze was never called after selection' }).toBeGreaterThan(0);
        const calls = getCaptured();
        expect(calls[0].body.chatConfig, 'analyze body did not carry the SELECTED config').toBe(ALT_CONFIG_NAME);
        expect(calls[0].body.chatConfig, 'selected config must override the default').not.toBe(SYSTEM_DEFAULT);
        console.log('[req2+3] intercepted /analyze body.chatConfig=' + calls[0].body.chatConfig + ' (override wins)');
    });
});
