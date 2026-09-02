/**
 * ChapBook UX verification — proves three already-implemented features against the live Docker stack
 * with REAL browser behavior (not grep, not bare "dialog opens"). Each test asserts the SPECIFIC
 * behavioral condition:
 *
 *   #4  Render SD-config dialog loads the user's SAVED defaults (seeded olio.sd.config `sdcfg-default`),
 *       and the model control is a REAL populated <select> — this Docker stack reaches the live SD
 *       server (192.168.1.39), so /rest/olio/sdModels returns the true model catalog (13 models here).
 *       The seeded default (a real model from that catalog, distinct from the schema default) must be
 *       the <select>'s selected value: proves "loaded my saved default", not blank/random/schema.
 *   #5  Analyze control survives a FRESH page reload (new JS VM) of the reader route — restored from
 *       localStorage persistence, not module memory. Negative control: clearing the key removes it.
 *   #6  A user WITHOUT the AccountUsers role gets create/import/render buttons that are actually DOM
 *       disabled (not merely a warning banner). Contrast: the shared user (has the role) can click them.
 *
 * Run against the Docker stack (host 9443, 127.0.0.1 required — localhost resolves to IPv6 ::1 which
 * Docker does not map):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookVerify.spec.js \
 *     --workers=1 --project=chromium
 *
 * #4 reads the live SD catalog (Docker CAN reach the SD host); #5/#6 make no LLM/SD calls.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensureUserWithoutUserRole } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const RESULTS = path.resolve(SPEC_DIR, '../test-results');
const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

// A REAL model from this stack's live SD catalog (GET /rest/olio/sdModels), chosen because it is NOT
// the schema/random default — so a match on it proves the saved sdcfg-default was loaded.
const SEEDED_MODEL = 'ponyRealism_V22.safetensors';

const POEM_A = `Memory, do not fail me;\nA majestic oak's leaves\nTumbling and falling.`;
const POEM_B = `Outside, all is pristine,\nFrom cobalt skies of charcoal unity\nDescending upon snow canvassed green.`;

function b64(s) { return Buffer.from(s).toString('base64'); }
function encPath(p) { return 'B64-' + b64(p).replace(/=/g, '%3D'); }

// Shared state seeded in beforeAll.
let orgId = null;
let poemsGroupId = null;
let prefsGroupId = null;
let prefsGroupPath = null;
let poemAId = null;
let poemBId = null;
let bookOid = null;
let noRoleUser = null;

async function restLogin(request, name, password) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name, credential: b64(password), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'REST login failed for ' + name).toBe(true);
}

async function seedPoem(request, name, title, text) {
    const search = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.cb.poem',
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
        }
    });
    const body = await search.json().catch(() => null);
    if (body && body.results && body.results.length) return body.results[0].objectId;
    const resp = await request.post(REST + '/model', {
        data: { schema: 'olio.cb.poem', name, title, author: 'E2E Verify', groupId: poemsGroupId, text }
    });
    const created = await resp.json().catch(() => null);
    return created && created.objectId;
}

// WebSocket stub — Docker's nginx strips the session cookie on the WS upgrade, so Tomcat closes it,
// which triggers forceLogin() → redirect to #!/sig. Stub it BEFORE goto (addInitScript runs on every
// navigation, including reload).
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
    // A SECOND login in one browser context (#6: role-less user → shared user) otherwise inherits the
    // first user's session cookie, so the fresh app boot fetches the WRONG principal/application and
    // page.context().roles reflects the previous user (user=false). Every other test's login works only
    // because its page starts cookie-free — so clear cookies first to give this the same clean slate.
    await page.context().clearCookies();
    await restLogin(page.request, name, password);
    await page.addInitScript(wsStub);
    // Force a full cross-document load. When called a second time in one test, the page already sits on a
    // '#!/chap-book/read/<oid>' hash route; a bare goto('/') from '/#!/x' to '/' differs only in the
    // fragment, a same-document navigation that would NOT reboot the SPA. about:blank first guarantees
    // goto('/') is cross-document and re-runs refreshApplication()/setContextRoles() for the new session.
    await page.goto('about:blank');
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

test.describe('ChapBook — UX verification', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        noRoleUser = await ensureUserWithoutUserRole(request);

        await restLogin(request, 'e2etest_shared', 'password');

        const poemsDir = await request.get(REST + '/path/make/auth.group/data/' + encPath('~/Poems'));
        const poemsBody = await poemsDir.json();
        expect(poemsBody && poemsBody.id, 'could not ensure ~/Poems').toBeTruthy();
        poemsGroupId = poemsBody.id;
        orgId = poemsBody.organizationId;

        const prefsDir = await request.get(REST + '/path/make/auth.group/data/' + encPath('~/Data/.preferences'));
        const prefsBody = await prefsDir.json();
        expect(prefsBody && prefsBody.id, 'could not ensure ~/Data/.preferences').toBeTruthy();
        prefsGroupId = prefsBody.id;
        prefsGroupPath = prefsBody.path;

        // The poem library (GET /poems) caps at 25 records sorted by NAME ascending (byte-order
        // collation: digits < uppercase < lowercase) with no client pagination. On the shared stack's
        // accumulated >25 poems, a 'cbverify-...' name (lowercase 'c') sorts past the cap and the seeded
        // rows never load — so #5's UI selection can't reach them. Digit-prefixed names sort ahead of
        // every existing poem (the smallest current name is 'AAAQDEL-b'), guaranteeing first-page
        // placement. Titles stay 'Verify Poem A/B' (what the UI filter matches). This is deterministic
        // test setup, not a product change; the 25-cap/no-pagination itself is noted as out-of-scope.
        poemAId = await seedPoem(request, '000-cbverify-poemA', 'Verify Poem A', POEM_A);
        poemBId = await seedPoem(request, '000-cbverify-poemB', 'Verify Poem B', POEM_B);
        expect(poemAId, 'poem A not seeded').toBeTruthy();
        expect(poemBId, 'poem B not seeded').toBeTruthy();

        // Seed the user's saved SD defaults (olio.sd.config named 'sdcfg-default') with a DISTINCTIVE
        // model value so #4 can distinguish "loaded my saved default" from the schema default / random.
        let cfg = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.sd.config',
                fields: [
                    { name: 'name', comparator: 'equals', value: 'sdcfg-default' },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name', 'model'], recordCount: 1, cache: false
            }
        });
        let cfgBody = await cfg.json().catch(() => null);
        let existing = cfgBody && cfgBody.results && cfgBody.results[0];
        if (existing && existing.objectId) {
            // Force the model onto the known seeded value (PATCH identity + name + changed field).
            await request.fetch(REST + '/model', {
                method: 'PATCH',
                data: { schema: 'olio.sd.config', id: existing.id, objectId: existing.objectId, name: 'sdcfg-default', model: SEEDED_MODEL }
            });
        } else {
            await request.post(REST + '/model', {
                data: {
                    schema: 'olio.sd.config', name: 'sdcfg-default',
                    groupId: prefsGroupId, groupPath: prefsGroupPath,
                    model: SEEDED_MODEL, steps: 24, cfg: 7, width: 1024, height: 1024, style: 'photograph'
                }
            });
        }

        // Create a book (2 seeded poems) so #4/#6 can open the reader route with a valid objectId.
        const slug = 'cbverify-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Verify ChapBook', poemObjectIds: [poemAId, poemBId], maxLinesPerPage: 4 }
        });
        expect(createResp.ok(), 'create book failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        bookOid = created && (created.objectId || created.bookObjectId);
        expect(bookOid, 'no bookObjectId').toBeTruthy();

        await request.get(REST + '/logout');
        fs.mkdirSync(RESULTS, { recursive: true });
    });

    // ── #4 raw-path fact: the live SD model list is REAL and non-empty (Docker reaches the SD host) ───
    test('4-env: /olio/sdModels returns the live SD catalog and includes the seeded default', async ({ request }) => {
        await restLogin(request, 'e2etest_shared', 'password');
        const resp = await request.get(REST + '/olio/sdModels');
        const status = resp.status();
        const text = await resp.text();
        let arr = null; try { arr = JSON.parse(text); } catch { /* leave null */ }
        console.log('[4-env] GET /olio/sdModels -> HTTP ' + status + ' count=' + (Array.isArray(arr) ? arr.length : 'n/a'));
        // Docker CAN reach the LAN SD host, so the live catalog is populated with real model names.
        expect(resp.ok(), 'GET /olio/sdModels failed: ' + status).toBe(true);
        expect(Array.isArray(arr) && arr.length > 0, 'expected a non-empty live SD catalog, got: ' + text.slice(0, 200)).toBe(true);
        // The value we seeded must exist in the live catalog — otherwise the <select> can't select it.
        expect(arr, 'seeded model ' + SEEDED_MODEL + ' not present in live SD catalog').toContain(SEEDED_MODEL);

        // And the saved default we seeded persisted — prove the field via a raw projected search (the
        // loadConfig path the UI uses).
        const cfg = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.sd.config',
                fields: [
                    { name: 'name', comparator: 'equals', value: 'sdcfg-default' },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name', 'model'], recordCount: 1, cache: false
            }
        });
        const cfgBody = await cfg.json();
        expect(cfgBody.results && cfgBody.results[0] && cfgBody.results[0].model,
            'seeded sdcfg-default.model not persisted').toBe(SEEDED_MODEL);
        await request.get(REST + '/logout');
    });

    // ── #4: render dialog loads saved defaults; Model is a populated <select> with saved value selected
    test('4: render dialog Model control is a populated <select> whose value is the saved default', async ({ page }) => {
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);

        // Open the pre-render SD config dialog via the reader's Render button.
        const renderBtn = page.locator('button:has-text("Render")').first();
        await expect(renderBtn).toBeVisible({ timeout: 15000 });
        await expect(renderBtn).toBeEnabled();
        await renderBtn.click();

        // Dialog + config panel must actually finish loading the saved config (not stay on the spinner).
        await expect(page.locator('text=Render Settings')).toBeVisible({ timeout: 10000 });

        // The live catalog is non-empty, so the Model control is a <select> (not the text fallback).
        const modelSelect = page.locator('xpath=//label[normalize-space()="Model"]/following-sibling::select[1]');
        await expect(modelSelect, 'expected a <select> for Model (live catalog is populated)').toBeVisible({ timeout: 15000 });
        // Real options present (placeholder + the live catalog).
        const optionCount = await modelSelect.locator('option').count();
        expect(optionCount, 'model <select> not populated with real options').toBeGreaterThanOrEqual(3);
        // The SELECTED value is the user's SAVED default — NOT blank, NOT random, NOT the schema default.
        await expect(modelSelect).toHaveValue(SEEDED_MODEL);
        const optionTexts = await modelSelect.locator('option').allTextContents();
        expect(optionTexts, 'saved default not among the <select> options').toContain(SEEDED_MODEL);

        await page.screenshot({ path: path.join(RESULTS, 'verify-4-select-saved-default.png'), fullPage: true });
    });

    // ── #A-env (FIX A raw fact): the SYSTEM library chat config the auto-default resolves to EXISTS ───
    // The old code returned results[0].name from an org-wide olio.llm.chatConfig search — a random
    // USER-owned config. The fix resolves a SYSTEM library config (contentAnalysis → generalChat) via
    // GET /rest/chat/library/chat/<name>. Prove that endpoint returns the named system config.
    test('A-env: /chat/library/chat/contentAnalysis resolves the system default config by name', async ({ request }) => {
        await restLogin(request, 'e2etest_shared', 'password');
        const resp = await request.get(REST + '/chat/library/chat/contentAnalysis');
        expect(resp.ok(), 'GET /chat/library/chat/contentAnalysis failed: ' + resp.status()).toBe(true);
        const rec = await resp.json();
        expect(rec && rec.schema, 'not a chatConfig record').toBe('olio.llm.chatConfig');
        expect(rec.name, 'system default config name mismatch').toBe('contentAnalysis');
        console.log('[A-env] contentAnalysis resolved: name=' + rec.name + ' owner=' + rec.ownerId);
        await request.get(REST + '/logout');
    });

    // ── #A (FIX A): the render dialog exposes a Chat Config control resolved to the SYSTEM default ─────
    // Proves the render dialog now has a visible Chat Config picker and its auto-default is the SYSTEM
    // library config (contentAnalysis) — NOT a silent random arr[0]. The user can override via the picker.
    test('A: render dialog Chat Config control auto-resolves to the system default (contentAnalysis)', async ({ page }) => {
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);

        const renderBtn = page.locator('button:has-text("Render")').first();
        await expect(renderBtn).toBeVisible({ timeout: 15000 });
        await expect(renderBtn).toBeEnabled();
        await renderBtn.click();

        await expect(page.locator('text=Render Settings')).toBeVisible({ timeout: 10000 });

        // The Chat Config control is the clickable div immediately after the "Chat Config" label.
        const chatCfgControl = page.locator('xpath=//label[normalize-space()="Chat Config"]/following-sibling::div[1]');
        await expect(chatCfgControl, 'Chat Config control missing from render dialog').toBeVisible({ timeout: 10000 });
        // The auto-default must resolve to the SYSTEM library config, not a placeholder or random user config.
        await expect(chatCfgControl, 'Chat Config did not auto-resolve to the system default').toContainText('contentAnalysis', { timeout: 15000 });

        await page.screenshot({ path: path.join(RESULTS, 'verify-A-chatconfig-system-default.png'), fullPage: true });
    });

    // ── #5: Analyze control restored from persistence across a FRESH page reload ─────────────────────
    test('5: reader Analyze control survives a full page reload (restored from localStorage, not memory)', async ({ page }) => {
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });

        // Select the two seeded poems through the real UI (filter → check), then Create via the dialog,
        // so doCreateChapBook → persistReaderPoemIds writes localStorage exactly as production does.
        const filter = page.getByPlaceholder('Filter by theme or title...');
        await expect(filter).toBeVisible({ timeout: 15000 });
        for (const title of ['Verify Poem A', 'Verify Poem B']) {
            await filter.fill(title);
            const row = page.locator('tr', { hasText: title }).first();
            await expect(row, 'seeded poem row not visible: ' + title).toBeVisible({ timeout: 10000 });
            await row.locator('input[type="checkbox"]').check();
        }
        await filter.fill('');

        const createBtn = page.locator('button:has-text("Create ChapBook")').first();
        await expect(createBtn).toBeVisible({ timeout: 10000 });
        await createBtn.click();

        // Fill the create dialog and submit.
        const titleInput = page.locator('xpath=//label[normalize-space()="Title"]/following-sibling::input[1]');
        await expect(titleInput).toBeVisible({ timeout: 10000 });
        const uiTitle = 'Reload Persist ' + Date.now().toString(36);
        await titleInput.fill(uiTitle);
        await page.locator('div.fixed button:has-text("Create")').last().click();

        // Navigation to the reader route.
        await page.waitForFunction(() => /#!\/chap-book\/read\//.test(window.location.hash), { timeout: 20000 });
        const readerHash = await page.evaluate(() => window.location.hash);
        const readerOid = readerHash.split('/chap-book/read/')[1];
        expect(readerOid, 'could not capture reader objectId from hash').toBeTruthy();

        // In-memory state right after creation: Analyze present.
        await expect(page.locator('button:has-text("Analyze")').first(), 'Analyze absent right after creation').toBeVisible({ timeout: 15000 });

        // The REAL persist path wrote localStorage (this is what a reload will restore from).
        const persisted = await page.evaluate((oid) => localStorage.getItem('cb-poemids-' + oid), readerOid);
        expect(persisted, 'localStorage cb-poemids-<oid> was not written by doCreateChapBook').toBeTruthy();
        const persistedIds = JSON.parse(persisted);
        expect(Array.isArray(persistedIds) && persistedIds.length).toBe(2);

        // FULL PAGE RELOAD → brand-new JS VM; all module-level state (readerPoemIds, etc.) is wiped.
        await page.reload({ timeout: 30000 });
        await page.waitForFunction(() => /#!\/chap-book\/read\//.test(window.location.hash), { timeout: 20000 });

        // Analyze STILL present — it can only be here if oninit re-derived readerPoemIds from localStorage.
        await expect(
            page.locator('button:has-text("Analyze")').first(),
            'Analyze NOT restored after full reload — persistence broken'
        ).toBeVisible({ timeout: 20000 });
        await page.screenshot({ path: path.join(RESULTS, 'verify-5-analyze-after-reload.png'), fullPage: true });

        // Negative control: remove the persisted key, reload → Analyze must disappear (proves the button
        // is driven by that localStorage entry, not by some other always-on condition).
        await page.evaluate((oid) => localStorage.removeItem('cb-poemids-' + oid), readerOid);
        await page.reload({ timeout: 30000 });
        await page.waitForFunction(() => /#!\/chap-book\/read\//.test(window.location.hash), { timeout: 20000 });
        // Give the reader time to settle, then assert Analyze is gone.
        await expect(page.locator('text=ChapBook').first()).toBeVisible({ timeout: 15000 });
        await expect(
            page.locator('button:has-text("Analyze")'),
            'Analyze STILL present after clearing persistence — not actually persistence-driven'
        ).toHaveCount(0, { timeout: 15000 });
    });

    // ── #C (FIX C): Analyze available from SERVER-derived poems, with NO localStorage ────────────────
    // The old reader sourced its poem ids SOLELY from localStorage (cb-poemids-<oid>), so a book opened
    // in a fresh browser / after cleared storage / by another user had no way to re-analyze. FIX C unions
    // server-derived ids (GET /poems?bookObjectId — poems carrying this book's `book` FK) into readerPoemIds.
    // Seed a book-scoped poem via a direct `book` FK PATCH (localStorage-independent), then prove Analyze
    // appears in a browser that has NO cb-poemids entry for that book.
    test('C: reader Analyze appears from server-derived poems when localStorage is empty (FIX C)', async ({ page, request }) => {
        await restLogin(request, 'e2etest_shared', 'password');

        // Fresh book (distinct from bookOid) so this browser holds NO create-flow localStorage for it.
        const slug = 'cbverify-srv-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Server Derive Book', poemObjectIds: [poemAId], maxLinesPerPage: 4 }
        });
        expect(createResp.ok(), 'create book (C) failed: ' + createResp.status()).toBe(true);
        const createdBook = await createResp.json();
        const bookOidC = createdBook && (createdBook.objectId || createdBook.bookObjectId);
        expect(bookOidC, 'no bookObjectId (C)').toBeTruthy();

        // Resolve the book's numeric id (the FK ref value the poem PATCH needs).
        const bookSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.book',
                fields: [{ name: 'objectId', comparator: 'equals', value: bookOidC }],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const bookBody = await bookSearch.json();
        const bookRec = bookBody.results && bookBody.results[0];
        expect(bookRec && bookRec.id, 'could not resolve book id (C)').toBeTruthy();

        // Create a NEW poem, then PATCH its `book` FK so it is SERVER-scoped to this book.
        const poemName = 'cbverify-srvpoem-' + Date.now().toString(36);
        const poemResp = await request.post(REST + '/model', {
            data: { schema: 'olio.cb.poem', name: poemName, title: 'Server Poem', author: 'E2E Verify', groupId: poemsGroupId, text: POEM_B }
        });
        const poemRec = await poemResp.json();
        const srvPoemOid = poemRec && poemRec.objectId;
        expect(srvPoemOid && poemRec.id, 'server poem not created (C)').toBeTruthy();

        const patchResp = await request.fetch(REST + '/model', {
            method: 'PATCH',
            data: {
                schema: 'olio.cb.poem', id: poemRec.id, objectId: srvPoemOid, name: poemName,
                book: { schema: 'olio.pb.book', id: bookRec.id, objectId: bookOidC }
            }
        });
        expect(patchResp.ok(), 'PATCH poem book FK failed (C): ' + patchResp.status()).toBe(true);

        // Raw-path proof: the server returns the book-scoped poem independent of any client state.
        const poemsResp = await request.get(CB_REST + '/poems?bookObjectId=' + encodeURIComponent(bookOidC));
        expect(poemsResp.ok(), 'GET /poems?bookObjectId failed (C)').toBe(true);
        const scopedPoems = await poemsResp.json();
        const scopedIds = (Array.isArray(scopedPoems) ? scopedPoems : []).map(p => p && p.objectId).filter(Boolean);
        console.log('[C] server-scoped poems for book=' + bookOidC + ' -> ' + JSON.stringify(scopedIds));
        expect(scopedIds, 'server did not return the book-scoped poem').toContain(srvPoemOid);
        await request.get(REST + '/logout');

        // Browser: open the reader with NO localStorage for this book — Analyze must appear from server derivation.
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await page.evaluate((oid) => { localStorage.removeItem('cb-poemids-' + oid); }, bookOidC);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOidC);

        // Confirm there is NO local poem-id state for this book (so the button can only be server-derived).
        const localState = await page.evaluate((oid) => localStorage.getItem('cb-poemids-' + oid), bookOidC);
        expect(localState, 'unexpected localStorage for the fresh book — test would not prove server derivation').toBeFalsy();

        await expect(
            page.locator('button:has-text("Analyze")').first(),
            'Analyze NOT shown from server-derived poems (FIX C broken)'
        ).toBeVisible({ timeout: 20000 });
        await page.screenshot({ path: path.join(RESULTS, 'verify-C-analyze-server-derived.png'), fullPage: true });
    });

    // ── #6: no-AccountUsers user → create/import/render buttons are DOM-disabled ─────────────────────
    test('6: user WITHOUT AccountUsers has disabled create/import/render buttons; role user has them enabled', async ({ page }) => {
        // 6a — the role-less user.
        await loginAndLoad(page, noRoleUser.testUserName, noRoleUser.testPassword);

        // Direct role-state proof (not just the banner): ctx.roles.user is false.
        const noRoleFlag = await page.evaluate(() => window.am7dbg && window.am7dbg.roles() && window.am7dbg.roles().user);
        expect(noRoleFlag, 'expected ctx.roles.user=false for the role-less user').toBeFalsy();

        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        // Warning banner shows AND the mutating buttons are actually disabled.
        await expect(page.locator('text=You need the AccountUsers role').first()).toBeVisible({ timeout: 15000 });
        await expect(page.locator('button:has-text("Add from Note")').first(), 'import(note) not disabled').toBeDisabled();
        await expect(page.locator('button:has-text("Add from Data")').first(), 'import(data) not disabled').toBeDisabled();
        await expect(page.locator('button:has-text("New Poem")').first(), 'create(new poem) not disabled').toBeDisabled();
        await page.screenshot({ path: path.join(RESULTS, 'verify-6a-norole-disabled.png'), fullPage: true });

        // Reader Render button (render action) is also disabled for the role-less user.
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);
        await expect(page.locator('button:has-text("Render")').first(), 'render not disabled for role-less user').toBeDisabled({ timeout: 15000 });

        // 6b — contrast: the shared user (HAS AccountUsers) can click the same buttons.
        await loginAndLoad(page, 'e2etest_shared', 'password');
        const roleFlag = await page.evaluate(() => window.am7dbg && window.am7dbg.roles() && window.am7dbg.roles().user);
        expect(roleFlag, 'expected ctx.roles.user=true for the shared user').toBeTruthy();

        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        await expect(page.locator('button:has-text("New Poem")').first()).toBeVisible({ timeout: 15000 });
        await expect(page.locator('text=You need the AccountUsers role')).toHaveCount(0);
        await expect(page.locator('button:has-text("Add from Note")').first(), 'import(note) should be enabled').toBeEnabled();
        await expect(page.locator('button:has-text("Add from Data")').first(), 'import(data) should be enabled').toBeEnabled();
        await expect(page.locator('button:has-text("New Poem")').first(), 'create(new poem) should be enabled').toBeEnabled();

        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);
        await expect(page.locator('button:has-text("Render")').first(), 'render should be enabled for role user').toBeEnabled({ timeout: 15000 });
        await page.screenshot({ path: path.join(RESULTS, 'verify-6b-roleuser-enabled.png'), fullPage: true });
    });
});
