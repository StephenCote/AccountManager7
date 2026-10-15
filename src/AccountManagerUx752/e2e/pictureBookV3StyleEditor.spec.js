/**
 * PictureBook V3 P2-9 — the book style editor (#!/picture-book/{book}/style) on the Docker stack (design §26).
 *
 * The editor is a thin client over GET/PUT /{book}/style + POST /{book}/style/lock (P2-4): it shows the
 * server's effective values and chain, sends a SPARSE PUT of only the fields the user touched, and shows
 * server messages verbatim. Render settings (sdConfig) are an olio.sd.config record the editor creates and
 * patches through the generic model routes and links through PUT {sdConfig:{objectId}}.
 *
 * What this spec proves on the LIVE stack (UI as e2etest_shared, REST only to seed and to verify; no LLM;
 * no SD render — the .39 inventory may be up or down and the assertions follow whichever it is):
 *   1. a fresh STORY book opens the editor from the reader header; nothing of its own yet (chain empty, lock
 *      disabled with the reason, Save disabled, "No render settings"), six task rows whose availability column
 *      matches GET /rest/olio/illustration/models for the live inventory, and — when the inventory is
 *      unavailable — exactly ONE probe is sent (the message is applied to all six rows).
 *   2. editing description / palette / quality preset / page format CUSTOM + width/height / portrait task
 *      model shows dirty marks, Save sends one sparse PUT, the own style appears (<slug>-style, BOOK, hop 1
 *      "(this book)"), the values survive a reload, and GET /style carries exactly what was typed; clearing a
 *      field sends null and the key disappears from own.fields.
 *   3. the server's 400s the UI cannot produce (invalid pageFormat / unknown field) are proven verbatim over
 *      REST so the messages the editor surfaces are real.
 *   4. Lock: the badge "Locked by e2etest_shared", the banner, every input/clear/SD button and Save disabled;
 *      PUT while locked → 409 verbatim; Unlock restores editing.
 *   5. Render settings: "Create render settings" creates an olio.sd.config (<slug>-style-sd-…) and links it
 *      (own.fields.sdConfig, effective.sdConfigName); editing the seed in the SdConfigPanel enables "Save
 *      render settings", the PATCH persists (GET /rest/model/olio.sd.config/{id}) and POST /scene/{s}/plan
 *      echoes config.seed + the DRAFT preset saved in 2; Unlink clears sdConfig (sparse PUT {sdConfig:null}).
 *   6. Inheritance: a second STORY book's style (palette, exclusions, a location task model, the sdConfig from 5) is
 *      made this book's parent over REST (PUT {parent:{objectId}} — the editor has no parent control); the editor
 *      shows both hops (own first, "(this book)"; the parent without it), "Inherited from <parent>-style" only on the
 *      parent-sourced fields, the inherited render settings read-only with "Create book override" (copies the
 *      record into this book's group, seed included, then PUT {sdConfig:{objectId}}); a lock on the PARENT makes the
 *      server refuse any edit here (409 naming the parent) and the editor surfaces that 409 verbatim on Save while
 *      staying editable (its badge is for the own lock only); unlock + PUT {parent:null} detaches; a lock on the OWN
 *      style disables the own render-settings panel (fieldset), Save render settings and Unlink, and Unlock restores
 *      them; the back arrow returns to the reader.
 *
 * Run:
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3StyleEditor.spec.js --workers=1 --project=chromium --retries=0
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) — never admin. Timings are logged, never asserted.
 * Screenshots: test-results/p29-style/.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { mkdirSync } from 'fs';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/p29-style';
const TASKS = ['portrait', 'location', 'sceneDirect', 'inpaint', 'harmonize', 'upscale'];
const SETTING = 'a weathered stone lighthouse on a rocky headland at dusk, waves breaking below';
const ACTION = 'the lamp has just been lit and its beam sweeps across the darkening water';
const MOOD = 'windswept, solitary';

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }

let shared = null;

function sharedCredential() {
    if (!shared) throw new Error('login before ensureSharedTestUser() — the beforeAll must set the shared user first');
    return { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' };
}

async function login(request) {
    const resp = await request.post(REST + '/login', { data: sharedCredential() });
    expect(resp.ok() || resp.status() === 204, 'login as ' + shared.testUserName + ': HTTP ' + resp.status()).toBe(true);
}

// Canonical page login (chapBook.spec.js): API login on the page's request context, WebSocket stub
// BEFORE page.goto (Docker nginx drops the cookie on the WS upgrade → forceLogin → #!/sig).
async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', { data: sharedCredential() });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login as ' + shared.testUserName + ' failed: HTTP ' + resp.status());
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1; window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(() => window.location.hash.includes('/main') && document.querySelector('[role="main"]'), { timeout: 30000 });
}

async function search(request, type, fields, requestFields) {
    const resp = await request.post(REST + '/model/search', { data: { schema: 'io.query', type, fields, request: requestFields, recordCount: 1, cache: false } });
    expect(resp.ok(), 'search ' + type + ': HTTP ' + resp.status()).toBe(true);
    const body = await resp.json();
    return body && body.results && body.results[0];
}

/** GET /{book}/style as the shared user → the DTO. */
async function getStyle(request, bookOid) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/style');
    const text = await resp.text();
    expect(resp.ok(), 'GET /style: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

/** PUT /{book}/style → {status, text, json}. */
async function putStyle(request, bookOid, body) {
    const resp = await request.put(PB_ROOT + '/' + bookOid + '/style', { data: body });
    const text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch (e) { /* not JSON */ }
    return { status: resp.status(), text, json };
}

function errorText(text) {
    try { const j = JSON.parse(text); return (typeof j.error === 'string' && j.error) || j.message || text; } catch (e) { return text; }
}

/** The live model inventory for one task (what the editor's availability column is built from). */
async function modelsForTask(request, task) {
    const resp = await request.get(REST + '/olio/illustration/models?task=' + encodeURIComponent(task), { timeout: 300000 });
    const text = await resp.text();
    expect(resp.ok(), 'GET /illustration/models?task=' + task + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

async function openStyleEditor(page, bookOid) {
    await page.goto('/#!/picture-book/' + bookOid + '/style', { timeout: 30000 });
    await expect(page.getByTestId('pb2-style')).toBeVisible({ timeout: 30000 });
    await expect(page.getByTestId('pb2-style-chain')).toBeVisible({ timeout: 30000 });
}

async function waitForToast(page, text) {
    await expect(page.locator('.toast-text', { hasText: text })).toBeVisible({ timeout: 15000 });
}

/** The SdConfigPanel has no testids: its inputs sit beside a <label> with the field's caption. */
function sdField(page, caption) {
    return page.getByTestId('pb2-style-sd').locator('div:has(> label:text-is("' + caption + '"))');
}

let bookObjectId = null;
let slug = null;
const fx = { book: null, scene: null, sdConfigObjectId: null, inventory: null };

test.describe('PictureBook V3 P2-9 — book style editor', () => {
    // Budgets are generous on purpose: while the SD server is unreachable the server's inventory read blocks
    // ~42 s per attempt and POST /plan re-reads it several times (212 s measured, C:/tmp/p29-plan-time.log).
    test.describe.configure({ mode: 'serial', timeout: 1200000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await login(request);
        const ts = Date.now().toString(36);
        slug = 'p29-style-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'P2-9 Style ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(createText);
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded STORY book objectId: ' + createText.slice(0, 300)).toBeTruthy();

        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId', 'bookType']);
        expect(book && book.id && book.groupId && book.groupPath, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        fx.book = { id: book.id, objectId: book.objectId, name: book.name, slug: book.slug, groupId: book.groupId, groupPath: book.groupPath };

        const sceneResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.scene', name: 'Scene ' + slug + ' 0', groupId: book.groupId, groupPath: book.groupPath,
                book: { schema: 'olio.pb.book', id: book.id, objectId: book.objectId }, sceneIndex: 0,
                title: 'The lighthouse at dusk', setting: SETTING, action: ACTION, mood: MOOD }
        });
        const sceneText = await sceneResp.text();
        expect(sceneResp.ok(), 'create olio.pb.scene: HTTP ' + sceneResp.status() + ' ' + sceneText.slice(0, 300)).toBe(true);
        const scene = JSON.parse(sceneText);
        expect(scene && scene.objectId, 'scene identity').toBeTruthy();
        fx.scene = { id: scene.id, objectId: scene.objectId };

        const fresh = await getStyle(request, bookObjectId);
        expect(fresh.own, 'a fresh book has no style of its own').toBeUndefined();
        expect(upper(fresh.effective && fresh.effective.qualityPreset)).toBe('STANDARD');
        fx.inventory = await modelsForTask(request, 'portrait');
        console.log('[p29-style-seed] ' + JSON.stringify({ book: bookObjectId, slug, scene: fx.scene, inventory: fx.inventory.inventory, deploymentDefault: fx.inventory.deploymentDefault }));
        mkdirSync(OUT_DIR, { recursive: true });
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        if (!bookObjectId) return;
        console.log('[p29-style-cleanup] left in place as the record of the run: ' + JSON.stringify({ book: bookObjectId, slug, ...fx }));
    });

    test('1. a fresh book: reached from the reader header, nothing of its own, six task rows built from the live inventory with one probe when it is unavailable', async ({ page }) => {
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        await loginAsSharedUser(page);
        const probes = [];
        page.on('request', req => { if (req.url().indexOf('/rest/olio/illustration/models') >= 0) probes.push(req.url().replace(/^.*\/rest\//, '/rest/')); });

        await page.goto('/#!/picture-book/v2/' + bookObjectId, { timeout: 30000 });
        await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
        const link = page.getByTestId('pb2-style-link');
        await expect(link).toBeVisible();
        await link.click();
        await expect(page).toHaveURL(new RegExp('#!/picture-book/' + bookObjectId + '/style$'));
        await expect(page.getByTestId('pb2-style')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb2-style-chain')).toBeVisible({ timeout: 30000 });

        await expect(page.getByTestId('pb2-style-own-name')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-hop')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-chain')).toContainText('No style of its own yet');
        await expect(page.getByTestId('pb2-style-locked')).toHaveCount(0);
        const lock = page.getByTestId('pb2-style-lock');
        await expect(lock).toBeDisabled();
        await expect(lock).toHaveAttribute('title', 'The book has no style of its own to lock; save a field first');
        await expect(page.getByTestId('pb2-style-save')).toBeDisabled();
        await expect(page.getByTestId('pb2-style-qualityPreset')).toHaveValue('STANDARD');
        await expect(page.getByTestId('pb2-style-pageFormat')).toHaveValue('SQUARE');
        await expect(page.getByTestId('pb2-style-customWidth')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-description')).toHaveValue('');
        await expect(page.getByTestId('pb2-style-sd-none')).toBeVisible();
        await expect(page.getByTestId('pb2-style-sd-create')).toBeEnabled();
        await expect(page.getByTestId('pb2-style-reference-images')).toContainText('Phase 4');

        for (const t of TASKS) {
            await expect(page.getByTestId('pb2-style-taskModel-' + t)).toHaveValue('');
            await expect(page.getByTestId('pb2-style-model-missing-' + t)).toHaveCount(0);
        }
        // The availability column is built from the live inventory — assert against what the server says now.
        const inv = fx.inventory;
        const unavailable = !!(inv.inventory && inv.inventory.error);
        // While the SD server is unreachable the server's inventory read blocks for its connect timeout (~42 s
        // measured) before it answers; the wait is generous and the elapsed time is logged, never asserted.
        const tProbe = Date.now();
        await expect(page.getByTestId('pb2-style-task-availability-portrait')).not.toContainText('checking', { timeout: 300000 });
        console.log('[p29-style-1] portrait availability answered after ' + (Date.now() - tProbe) + ' ms');
        for (const t of TASKS) {
            const cell = page.getByTestId('pb2-style-task-availability-' + t);
            await expect(cell).toHaveAttribute('data-unavailable', unavailable ? '1' : '0');
            if (unavailable) {
                await expect(cell).toContainText(inv.inventory.error);
                if (inv.deploymentDefault && inv.deploymentDefault.model) await expect(cell).toContainText('deployment default: ' + inv.deploymentDefault.model);
            } else {
                await expect(cell).toContainText(String(inv.inventory.count) + ' installed');
            }
        }
        console.log('[p29-style-1] inventory ' + (unavailable ? 'UNAVAILABLE: ' + inv.inventory.error : 'available, count=' + inv.inventory.count) + '; probes sent: ' + JSON.stringify(probes));
        if (unavailable) {
            expect(probes, 'one probe when the inventory is unavailable (§6.C 168 a)').toEqual(['/rest/olio/illustration/models?task=portrait']);
        } else {
            expect(probes.length, 'one probe per task when the inventory answers').toBe(TASKS.length);
        }
        await page.screenshot({ path: OUT_DIR + '/01-fresh.png', fullPage: true });
    });

    test('2. editing + Save sends one sparse PUT; the own style appears; values survive a reload; clear sends null', async ({ page, request }) => {
        await loginAsSharedUser(page);
        await openStyleEditor(page, bookObjectId);
        const puts = [];
        page.on('request', req => { if (req.method() === 'PUT' && /\/style$/.test(req.url())) puts.push(JSON.parse(req.postData() || 'null')); });

        await page.getByTestId('pb2-style-description').fill('ink wash, muted palette');
        await page.getByTestId('pb2-style-palette').fill('sepia and slate');
        await page.getByTestId('pb2-style-qualityPreset').selectOption('DRAFT');
        await page.getByTestId('pb2-style-pageFormat').selectOption('CUSTOM');
        await expect(page.getByTestId('pb2-style-customWidth')).toBeVisible();
        await page.getByTestId('pb2-style-customWidth').fill('1024');
        await page.getByTestId('pb2-style-customHeight').fill('1024');
        await page.getByTestId('pb2-style-taskModel-portrait').fill('p29-not-installed.safetensors');
        for (const k of ['description', 'palette', 'qualityPreset', 'pageFormat', 'customWidth', 'customHeight']) await expect(page.getByTestId('pb2-style-dirty-' + k)).toBeVisible();
        await expect(page.getByTestId('pb2-style-dirty-taskModels-portrait')).toBeVisible();
        // "Not in the installed-model list" needs a list to check against: only when the live probe returned models[].
        const listed = Array.isArray(fx.inventory.models) ? fx.inventory.models.length : 0;
        console.log('[p29-style-2] live inventory models listed: ' + listed + (listed ? '' : ' — the model-missing hint is Vitest-only on this run'));
        if (listed > 0) await expect(page.getByTestId('pb2-style-model-missing-portrait')).toHaveText('Not in the installed-model list');
        else await expect(page.getByTestId('pb2-style-model-missing-portrait')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/02-dirty.png', fullPage: true });

        await expect(page.getByTestId('pb2-style-save')).toBeEnabled();
        await page.getByTestId('pb2-style-save').click();
        await waitForToast(page, 'Style saved');
        expect(puts.length, 'exactly one PUT').toBe(1);
        expect(puts[0]).toEqual({ description: 'ink wash, muted palette', palette: 'sepia and slate', qualityPreset: 'DRAFT', pageFormat: 'CUSTOM', customWidth: 1024, customHeight: 1024, taskModels: { portrait: 'p29-not-installed.safetensors' } });

        await expect(page.getByTestId('pb2-style-own-name')).toHaveText(slug + '-style');
        await expect(page.getByTestId('pb2-style-hop')).toHaveCount(1);
        await expect(page.getByTestId('pb2-style-hop')).toHaveAttribute('data-own', '1');
        await expect(page.getByTestId('pb2-style-hop')).toContainText('(this book)');
        await expect(page.getByTestId('pb2-style-hop')).toContainText(/BOOK/i);
        await expect(page.getByTestId('pb2-style-lock')).toBeEnabled();
        await expect(page.getByTestId('pb2-style-save')).toBeDisabled();
        await expect(page.getByTestId('pb2-style-dirty-description')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-inherited-description')).toHaveCount(0);

        await login(request);
        const dto = await getStyle(request, bookObjectId);
        expect(dto.own && dto.own.name).toBe(slug + '-style');
        expect(upper(dto.own.scope)).toBe('BOOK');
        expect(dto.own.locked).toBe(false);
        expect(dto.own.fields.description).toBe('ink wash, muted palette');
        expect(dto.own.fields.palette).toBe('sepia and slate');
        expect(upper(dto.own.fields.qualityPreset)).toBe('DRAFT');
        expect(upper(dto.own.fields.pageFormat)).toBe('CUSTOM');
        expect(dto.own.fields.customWidth).toBe(1024);
        expect(dto.own.fields.customHeight).toBe(1024);
        expect(dto.own.fields.taskModels).toEqual({ portrait: 'p29-not-installed.safetensors' });
        expect(dto.own.fields.sdConfig, 'sdConfig untouched').toBeUndefined();
        expect(upper(dto.effective.qualityPreset)).toBe('DRAFT');
        expect(upper(dto.effective.pageFormat)).toBe('CUSTOM');
        expect(dto.effective.taskModels).toEqual({ portrait: 'p29-not-installed.safetensors' });
        expect(dto.chain.map(h => h.objectId)).toEqual([dto.own.objectId]);
        fx.ownObjectId = dto.own.objectId;

        await page.reload();
        await expect(page.getByTestId('pb2-style-chain')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb2-style-own-name')).toHaveText(slug + '-style');
        await expect(page.getByTestId('pb2-style-description')).toHaveValue('ink wash, muted palette');
        await expect(page.getByTestId('pb2-style-palette')).toHaveValue('sepia and slate');
        await expect(page.getByTestId('pb2-style-qualityPreset')).toHaveValue('DRAFT');
        await expect(page.getByTestId('pb2-style-pageFormat')).toHaveValue('CUSTOM');
        await expect(page.getByTestId('pb2-style-customWidth')).toHaveValue('1024');
        await expect(page.getByTestId('pb2-style-taskModel-portrait')).toHaveValue('p29-not-installed.safetensors');

        // Clear palette → PUT {palette:null}; the key leaves own.fields.
        puts.length = 0;
        await page.getByTestId('pb2-style-clear-palette').click();
        await expect(page.getByTestId('pb2-style-palette')).toHaveValue('');
        await expect(page.getByTestId('pb2-style-dirty-palette')).toBeVisible();
        await page.getByTestId('pb2-style-save').click();
        await waitForToast(page, 'Style saved');
        expect(puts).toEqual([{ palette: null }]);
        const after = await getStyle(request, bookObjectId);
        expect(after.own.fields.palette, 'palette cleared').toBeUndefined();
        expect(after.own.fields.description, 'untouched fields kept').toBe('ink wash, muted palette');
        expect(after.effective.fields && after.effective.fields.palette).toBeUndefined();
        await page.screenshot({ path: OUT_DIR + '/02-saved.png', fullPage: true });
        await request.get(REST + '/logout');
    });

    test('3. the 400s the editor cannot produce are real: invalid pageFormat and an unknown field, verbatim', async ({ request }) => {
        await login(request);
        const bad = await putStyle(request, bookObjectId, { pageFormat: 'circle' });
        expect(bad.status, bad.text).toBe(400);
        expect(errorText(bad.text)).toBe('pageFormat must be one of PORTRAIT_PAGE, LANDSCAPE_PAGE, SQUARE, CUSTOM');
        const unknown = await putStyle(request, bookObjectId, { name: 'x' });
        expect(unknown.status, unknown.text).toBe(400);
        expect(errorText(unknown.text)).toMatch(/^Unknown style field 'name'; accepted: /);
        const badTask = await putStyle(request, bookObjectId, { taskModels: { banner: 'x' } });
        expect(badTask.status, badTask.text).toBe(400);
        console.log('[p29-style-3] ' + JSON.stringify({ pageFormat: errorText(bad.text), unknown: errorText(unknown.text), badTask: errorText(badTask.text) }));
        const dto = await getStyle(request, bookObjectId);
        expect(upper(dto.own.fields.pageFormat), 'nothing changed').toBe('CUSTOM');
        await request.get(REST + '/logout');
    });

    test('4. Lock disables editing and names the locking hop; PUT while locked → 409 verbatim; Unlock restores', async ({ page, request }) => {
        await loginAsSharedUser(page);
        await openStyleEditor(page, bookObjectId);
        await expect(page.getByTestId('pb2-style-own-name')).toHaveText(slug + '-style');
        await page.getByTestId('pb2-style-lock').click();
        await waitForToast(page, 'Style locked');
        // lockedBy on the wire is the locking style's objectId (PbServiceFacade.styleDto); the badge shows that hop's name.
        await expect(page.getByTestId('pb2-style-locked')).toHaveText('Locked by ' + slug + '-style');
        await expect(page.getByTestId('pb2-style-locked-banner')).toBeVisible();
        await expect(page.getByTestId('pb2-style-lock')).toHaveText(/Unlock$/);
        for (const k of ['description', 'exclusions', 'palette', 'qualityPreset', 'pageFormat', 'customWidth', 'photoreal', 'taskModel-portrait', 'clear-description', 'sd-create', 'save']) {
            await expect(page.getByTestId('pb2-style-' + k), k + ' disabled while locked').toBeDisabled();
        }
        await page.screenshot({ path: OUT_DIR + '/04-locked.png', fullPage: true });

        await login(request);
        const locked = await getStyle(request, bookObjectId);
        expect(locked.own.locked).toBe(true);
        expect(locked.lockedBy).toBe(locked.own.objectId);
        const r = await putStyle(request, bookObjectId, { description: 'should not apply' });
        expect(r.status, r.text).toBe(409);
        expect(errorText(r.text)).toBe('Style \'' + slug + '-style\' (BOOK, ' + locked.own.objectId + ') is locked - unlock it before editing (POST /{book}/style/lock {"locked":false})');

        await page.getByTestId('pb2-style-lock').click();
        await waitForToast(page, 'Style unlocked');
        await expect(page.getByTestId('pb2-style-locked')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-locked-banner')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-description')).toBeEnabled();
        await expect(page.getByTestId('pb2-style-sd-create')).toBeEnabled();
        const unlocked = await getStyle(request, bookObjectId);
        expect(unlocked.own.locked).toBe(false);
        expect(unlocked.own.fields.description, 'the locked PUT did not apply').toBe('ink wash, muted palette');
        await request.get(REST + '/logout');
    });

    test('5. Render settings: create links an olio.sd.config, a seed edit PATCHes it and /plan echoes it with the DRAFT preset; Unlink clears sdConfig', async ({ page, request }) => {
        await loginAsSharedUser(page);
        await openStyleEditor(page, bookObjectId);
        await expect(page.getByTestId('pb2-style-sd-none')).toBeVisible();
        const puts = [];
        const patches = [];
        page.on('request', req => {
            if (req.method() === 'PUT' && /\/style$/.test(req.url())) puts.push(JSON.parse(req.postData() || 'null'));
            if (req.method() === 'PATCH' && /\/rest\/model$/.test(req.url())) patches.push(JSON.parse(req.postData() || 'null'));
        });

        await page.getByTestId('pb2-style-sd-create').click();
        await waitForToast(page, 'Render settings created');
        await expect(page.getByTestId('pb2-style-sd-own')).toBeVisible();
        // Named <book slug or name>-style-sd-<stamp>: the book /full carries name and may or may not carry slug.
        const nameRe = new RegExp('^(' + [slug, fx.book.name].map(s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')-style-sd-[0-9a-z]+$');
        await expect(page.getByTestId('pb2-style-sd-own')).toContainText('-style-sd-');
        expect(puts.length, 'one PUT linking the new config').toBe(1);
        expect(puts[0].sdConfig && puts[0].sdConfig.objectId, 'PUT {sdConfig:{objectId}}').toBeTruthy();
        fx.sdConfigObjectId = puts[0].sdConfig.objectId;
        await expect(page.getByTestId('pb2-style-sd-save')).toBeDisabled();
        await expect(page.getByTestId('pb2-style-sd-unlink')).toBeEnabled();
        // Steps are hidden (the quality preset owns them); the panel itself rendered (Refiner Steps is there).
        await expect(page.getByTestId('pb2-style-sd').locator('label', { hasText: /^Steps: / })).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-sd').locator('label', { hasText: /^Refiner Steps: / })).toHaveCount(1);

        await login(request);
        const linked = await getStyle(request, bookObjectId);
        expect(linked.own.fields.sdConfig).toBe(fx.sdConfigObjectId);
        expect(linked.effective.sdConfigName).toMatch(nameRe);
        expect(linked.effective.sources && linked.effective.sources.sdConfig).toBe(linked.own.objectId);
        const recResp = await request.get(REST + '/model/olio.sd.config/' + fx.sdConfigObjectId + '/full');
        expect(recResp.ok(), 'GET olio.sd.config/full: HTTP ' + recResp.status()).toBe(true);
        const rec = await recResp.json();
        expect(rec.groupId, 'created in the book\'s group').toBe(fx.book.groupId);
        expect(rec.name).toBe(linked.effective.sdConfigName);
        console.log('[p29-style-5] created olio.sd.config ' + fx.sdConfigObjectId + ' ' + rec.name + ' seed=' + rec.seed + ' steps=' + rec.steps + ' model=' + rec.model);

        // Edit the seed in the SdConfigPanel → "Save render settings" enables → PATCH {schema,id,objectId,seed}.
        const seedInput = sdField(page, 'Seed').locator('input[type="number"]');
        await expect(seedInput).toBeVisible();
        await seedInput.fill('4242');
        await expect(page.getByTestId('pb2-style-sd-save')).toBeEnabled();
        await page.getByTestId('pb2-style-sd-save').click();
        await waitForToast(page, 'Render settings saved');
        expect(patches.length, 'one PATCH').toBe(1);
        expect(patches[0].schema).toBe('olio.sd.config');
        expect(patches[0].objectId).toBe(fx.sdConfigObjectId);
        expect(patches[0].seed).toBe(4242);
        expect(patches[0].name, 'sparse patch carries no name').toBeUndefined();
        await expect(page.getByTestId('pb2-style-sd-save')).toBeDisabled();
        const patched = await (await request.get(REST + '/model/olio.sd.config/' + fx.sdConfigObjectId + '/full')).json();
        expect(patched.seed).toBe(4242);
        expect(patched.name, 'the patch did not touch the name').toBe(rec.name);

        const tPlan = Date.now();
        const planResp = await request.post(PB_ROOT + '/' + bookObjectId + '/scene/' + fx.scene.objectId + '/plan', { data: {}, timeout: 900000 });
        console.log('[p29-style-5] POST /plan answered in ' + (Date.now() - tPlan) + ' ms');
        const planText = await planResp.text();
        expect(planResp.ok(), 'POST /plan: HTTP ' + planResp.status() + ' ' + planText.slice(0, 300)).toBe(true);
        const plan = JSON.parse(planText);
        expect(plan.config && plan.config.seed, 'the plan is built on the style\'s sdConfig').toBe(4242);
        expect(upper(plan.preset && plan.preset.preset), 'the plan uses the saved quality preset').toBe('DRAFT');
        expect(plan.style && plan.style.styleObjectId).toBe(linked.own.objectId);
        expect(plan.style.description).toBe('ink wash, muted palette');
        console.log('[p29-style-5] /plan canvas=' + JSON.stringify(plan.canvas) + ' preset=' + JSON.stringify(plan.preset) + ' estimate=' + JSON.stringify(plan.estimate) + ' config.model=' + plan.config.model);
        await page.screenshot({ path: OUT_DIR + '/05-sd-own.png', fullPage: true });

        puts.length = 0;
        await page.getByTestId('pb2-style-sd-unlink').click();
        await waitForToast(page, 'Render settings unlinked');
        await expect(page.getByTestId('pb2-style-sd-none')).toBeVisible();
        expect(puts).toEqual([{ sdConfig: null }]);
        const unlinked = await getStyle(request, bookObjectId);
        expect(unlinked.own.fields.sdConfig).toBeUndefined();
        expect(unlinked.effective.sdConfigName).toBeUndefined();
        expect(unlinked.own.fields.description, 'other fields untouched').toBe('ink wash, muted palette');
        const still = await request.get(REST + '/model/olio.sd.config/' + fx.sdConfigObjectId);
        expect(still.ok(), 'the olio.sd.config record itself is left in place').toBe(true);
        await request.get(REST + '/logout');
    });

    test('6. a parent style: both hops shown, "Inherited from" only on parent-sourced fields, inherited render settings read-only with a book override; a lock on the parent refuses Save verbatim; detach; back arrow', async ({ page, request }) => {
        expect(fx.ownObjectId, 'own style from test 2').toBeTruthy();
        expect(fx.sdConfigObjectId, 'the olio.sd.config created in test 5').toBeTruthy();
        await login(request);

        // Seed the parent: a second STORY book whose own style sets what this book does not (palette, exclusions,
        // the location task model) and the render settings test 5 created (unlinked there, so this book has none).
        const ts = Date.now().toString(36);
        const parentSlug = 'p29-parent-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug: parentSlug, title: 'P2-9 Parent ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter (parent): HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const parentChapter = JSON.parse(createText);
        const parentBookOid = parentChapter.bookObjectId || parentChapter.objectId;
        expect(parentBookOid, 'parent STORY book objectId: ' + createText.slice(0, 300)).toBeTruthy();
        const seeded = await putStyle(request, parentBookOid, {
            palette: 'house sepia', exclusions: 'no text, no borders', sdConfig: { objectId: fx.sdConfigObjectId }, taskModels: { location: 'p29-parent-location.safetensors' }
        });
        expect(seeded.status, 'seed the parent style: ' + seeded.text.slice(0, 300)).toBe(200);
        const parentOwnOid = seeded.json.own.objectId;
        expect(seeded.json.own.name).toBe(parentSlug + '-style');
        expect(seeded.json.own.fields.sdConfig).toBe(fx.sdConfigObjectId);
        fx.parent = { book: parentBookOid, slug: parentSlug, ownObjectId: parentOwnOid };

        // Re-parent this book's style and read what the editor will be shown.
        const linked = await putStyle(request, bookObjectId, { parent: { objectId: parentOwnOid } });
        expect(linked.status, 'PUT {parent}: ' + linked.text.slice(0, 300)).toBe(200);
        const dto = linked.json;
        expect(dto.own.parent).toBe(parentOwnOid);
        expect(dto.chain.map(h => h.objectId), 'own hop first, then the parent').toEqual([fx.ownObjectId, parentOwnOid]);
        expect(dto.chain[1].name).toBe(parentSlug + '-style');
        expect(dto.effective.fields.palette).toBe('house sepia');
        expect(dto.effective.sources.palette).toBe(parentOwnOid);
        expect(dto.effective.fields.exclusions).toBe('no text, no borders');
        expect(dto.effective.fields.description, 'the own value wins').toBe('ink wash, muted palette');
        expect(dto.effective.sources.description).toBe(fx.ownObjectId);
        expect(upper(dto.effective.qualityPreset), 'own DRAFT wins over the parent default').toBe('DRAFT');
        expect(dto.effective.fields.sdConfig).toBe(fx.sdConfigObjectId);
        expect(dto.effective.sources.sdConfig).toBe(parentOwnOid);
        expect(dto.effective.taskModels, 'task models merge per key').toEqual({ portrait: 'p29-not-installed.safetensors', location: 'p29-parent-location.safetensors' });
        expect(dto.effective.taskModelSources.location).toBe(parentOwnOid);
        expect(dto.effective.taskModelSources.portrait).toBe(fx.ownObjectId);
        expect(dto.lockedBy == null, 'nothing locked (lockedBy absent/null)').toBe(true);
        console.log('[p29-style-6] parent ' + JSON.stringify(fx.parent) + ' chain=' + JSON.stringify(dto.chain) + ' sdConfigName=' + dto.effective.sdConfigName);

        await loginAsSharedUser(page);
        await openStyleEditor(page, bookObjectId);
        const hops = page.getByTestId('pb2-style-hop');
        await expect(hops).toHaveCount(2);
        await expect(hops.nth(0)).toHaveAttribute('data-own', '1');
        await expect(hops.nth(0)).toContainText(slug + '-style');
        await expect(hops.nth(0)).toContainText('(this book)');
        await expect(hops.nth(1)).toHaveAttribute('data-own', '0');
        await expect(hops.nth(1)).toContainText(parentSlug + '-style');
        await expect(hops.nth(1)).toContainText(/BOOK/i);
        await expect(hops.nth(1)).not.toContainText('(this book)');
        await expect(page.getByTestId('pb2-style-own-name')).toHaveText(slug + '-style');
        await expect(page.getByTestId('pb2-style-cycle')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-truncated')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-incomplete')).toHaveCount(0);

        const from = 'Inherited from ' + parentSlug + '-style';
        await expect(page.getByTestId('pb2-style-inherited-palette')).toHaveText(from);
        await expect(page.getByTestId('pb2-style-palette')).toHaveValue('house sepia');
        await expect(page.getByTestId('pb2-style-inherited-exclusions')).toHaveText(from);
        await expect(page.getByTestId('pb2-style-exclusions')).toHaveValue('no text, no borders');
        await expect(page.getByTestId('pb2-style-inherited-description')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-description')).toHaveValue('ink wash, muted palette');
        await expect(page.getByTestId('pb2-style-inherited-qualityPreset')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-qualityPreset')).toHaveValue('DRAFT');
        await expect(page.getByTestId('pb2-style-inherited-taskModels-location')).toHaveText(from);
        await expect(page.getByTestId('pb2-style-taskModel-location')).toHaveValue('p29-parent-location.safetensors');
        await expect(page.getByTestId('pb2-style-inherited-taskModels-portrait')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-taskModel-portrait')).toHaveValue('p29-not-installed.safetensors');
        await expect(page.getByTestId('pb2-style-save')).toBeDisabled();

        // Render settings come from the parent: read-only, named, tagged, with "Create book override" and no panel.
        await expect(page.getByTestId('pb2-style-sd-inherited')).toBeVisible();
        await expect(page.getByTestId('pb2-style-sd-inherited')).toContainText(dto.effective.sdConfigName);
        await expect(page.getByTestId('pb2-style-sd-inherited')).toContainText('read-only here');
        await expect(page.getByTestId('pb2-style-inherited-sdConfig')).toHaveText(from);
        await expect(page.getByTestId('pb2-style-sd-override')).toBeEnabled();
        await expect(page.getByTestId('pb2-style-sd-save')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-sd-unlink')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-sd-create')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/06-inherited.png', fullPage: true });

        // "Create book override": POST /rest/model copies the parent's record (seed 4242 from test 5) into THIS
        // book's group under <slug>-style-override-<stamp>, then one PUT {sdConfig:{objectId}} points the own style at it.
        const puts = [];
        const posts = [];
        page.on('request', req => {
            if (req.method() === 'PUT' && /\/style$/.test(req.url())) puts.push(JSON.parse(req.postData() || 'null'));
            if (req.method() === 'POST' && /\/rest\/model$/.test(req.url())) posts.push(JSON.parse(req.postData() || 'null'));
        });
        await page.getByTestId('pb2-style-sd-override').click();
        await waitForToast(page, 'Book override created');
        await expect(page.getByTestId('pb2-style-sd-own')).toBeVisible();
        await expect(page.getByTestId('pb2-style-sd-own')).toContainText('-style-override-');
        await expect(page.getByTestId('pb2-style-sd-inherited')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-inherited-sdConfig')).toHaveCount(0);
        expect(posts.length, 'one POST /rest/model').toBe(1);
        expect(posts[0].schema).toBe('olio.sd.config');
        expect(posts[0].name).toMatch(/-style-override-[0-9a-z]+$/);
        expect(posts[0].groupId, 'created in this book\'s group').toBe(fx.book.groupId);
        expect(posts[0].seed, 'copied from the inherited record').toBe(4242);
        expect(posts[0].objectId, 'a copy, not the source').toBeUndefined();
        expect(posts[0].id).toBeUndefined();
        expect(puts.length, 'one PUT linking the override').toBe(1);
        expect(puts[0].sdConfig && puts[0].sdConfig.objectId).toBeTruthy();
        expect(puts[0].sdConfig.objectId).not.toBe(fx.sdConfigObjectId);
        fx.overrideSdConfigObjectId = puts[0].sdConfig.objectId;
        await expect(page.getByTestId('pb2-style-sd-unlink')).toBeEnabled();
        await expect(page.getByTestId('pb2-style-sd').locator('label', { hasText: /^Refiner Steps: / })).toHaveCount(1);

        const overridden = await getStyle(request, bookObjectId);
        expect(overridden.own.fields.sdConfig).toBe(fx.overrideSdConfigObjectId);
        expect(overridden.effective.fields.sdConfig).toBe(fx.overrideSdConfigObjectId);
        expect(overridden.effective.sources.sdConfig, 'now set here').toBe(fx.ownObjectId);
        expect(overridden.effective.fields.palette, 'the other inherited values are untouched').toBe('house sepia');
        expect(overridden.effective.sources.palette).toBe(parentOwnOid);
        const ovResp = await request.get(REST + '/model/olio.sd.config/' + fx.overrideSdConfigObjectId + '/full');
        expect(ovResp.ok(), 'GET override olio.sd.config/full: HTTP ' + ovResp.status()).toBe(true);
        const ov = await ovResp.json();
        expect(ov.seed).toBe(4242);
        expect(ov.groupId).toBe(fx.book.groupId);
        expect(ov.name).toBe(overridden.effective.sdConfigName);
        const parentStill = await getStyle(request, parentBookOid);
        expect(parentStill.own.fields.sdConfig, 'the parent still points at its own record').toBe(fx.sdConfigObjectId);
        console.log('[p29-style-6] override olio.sd.config ' + fx.overrideSdConfigObjectId + ' ' + ov.name + ' seed=' + ov.seed + ' model=' + ov.model);

        // A lock on the PARENT: the server refuses every edit of this book's style, naming the parent (409).
        const lockResp = await request.post(PB_ROOT + '/' + parentBookOid + '/style/lock', { data: { locked: true } });
        expect(lockResp.ok(), 'lock the parent: HTTP ' + lockResp.status()).toBe(true);
        const underParentLock = await getStyle(request, bookObjectId);
        expect(underParentLock.lockedBy).toBe(parentOwnOid);
        expect(underParentLock.own.locked).toBe(false);
        expect(underParentLock.chain[1].locked).toBe(true);
        const refused = await putStyle(request, bookObjectId, { palette: 'should not apply' });
        expect(refused.status, refused.text).toBe(409);
        // No "(POST /{book}/style/lock …)" hint here: PbServiceFacade.putStyle appends it only when the locking hop
        // is the book's OWN style (test 4); an inherited lock is released on the parent's book, not this one.
        const refusal = 'Style \'' + parentSlug + '-style\' (BOOK, ' + parentOwnOid + ') is locked - unlock it before editing';
        expect(errorText(refused.text)).toBe(refusal);

        // The editor: the parent hop carries the lock glyph, the own-lock badge is absent (own.locked is false), the
        // inputs stay enabled, and Save surfaces the server's 409 verbatim; the draft is kept and nothing persisted.
        await page.reload();
        await expect(page.getByTestId('pb2-style-chain')).toBeVisible({ timeout: 30000 });
        await expect(hops).toHaveCount(2);
        await expect(hops.nth(1).locator('[title="locked"]')).toHaveCount(1);
        await expect(hops.nth(0).locator('[title="locked"]')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-locked')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-locked-banner')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-palette')).toBeEnabled();
        await page.getByTestId('pb2-style-palette').fill('child palette');
        await expect(page.getByTestId('pb2-style-dirty-palette')).toBeVisible();
        await page.getByTestId('pb2-style-save').click();
        await waitForToast(page, '(409) ' + refusal);
        await expect(page.getByTestId('pb2-style-dirty-palette'), 'the draft survives a refused save').toBeVisible();
        await expect(page.getByTestId('pb2-style-palette')).toHaveValue('child palette');
        await page.screenshot({ path: OUT_DIR + '/06-parent-locked.png', fullPage: true });
        const unchanged = await getStyle(request, bookObjectId);
        expect(unchanged.own.fields.palette, 'nothing persisted').toBeUndefined();
        expect(unchanged.effective.fields.palette).toBe('house sepia');

        // Unlock the parent; detach with PUT {parent:null}: one hop again, the parent's values gone, the override kept.
        const unlockResp = await request.post(PB_ROOT + '/' + parentBookOid + '/style/lock', { data: { locked: false } });
        expect(unlockResp.ok(), 'unlock the parent: HTTP ' + unlockResp.status()).toBe(true);
        const detached = await putStyle(request, bookObjectId, { parent: null });
        expect(detached.status, 'PUT {parent:null}: ' + detached.text.slice(0, 300)).toBe(200);
        expect(detached.json.own.parent == null, 'parent cleared').toBe(true);
        expect(detached.json.chain.map(h => h.objectId)).toEqual([fx.ownObjectId]);
        expect(detached.json.effective.fields.palette).toBeUndefined();
        expect(detached.json.effective.fields.exclusions).toBeUndefined();
        expect(detached.json.effective.taskModels).toEqual({ portrait: 'p29-not-installed.safetensors' });
        expect(detached.json.own.fields.sdConfig).toBe(fx.overrideSdConfigObjectId);
        expect(detached.json.lockedBy == null, 'no lock after detaching').toBe(true);

        await page.reload();
        await expect(page.getByTestId('pb2-style-chain')).toBeVisible({ timeout: 30000 });
        await expect(hops).toHaveCount(1);
        await expect(page.getByTestId('pb2-style-inherited-palette')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-palette')).toHaveValue('');
        await expect(page.getByTestId('pb2-style-sd-own')).toContainText('-style-override-');

        // A lock on this book's OWN style also freezes its own render settings: the panel's fieldset, Save render
        // settings and Unlink are disabled (the style lock guards PUT /style only; the olio.sd.config record would
        // otherwise still be editable through PATCH /rest/model). Unlock restores the panel.
        const seedInput = sdField(page, 'Seed').locator('input[type="number"]');
        await expect(seedInput).toBeEnabled();
        await page.getByTestId('pb2-style-lock').click();
        await waitForToast(page, 'Style locked');
        await expect(page.getByTestId('pb2-style-locked')).toHaveText('Locked by ' + slug + '-style');
        await expect(page.getByTestId('pb2-style-sd-fields')).toHaveAttribute('disabled', '');
        await expect(seedInput, 'SD panel input disabled while the own style is locked').toBeDisabled();
        await expect(page.getByTestId('pb2-style-sd-save')).toBeDisabled();
        await expect(page.getByTestId('pb2-style-sd-unlink')).toBeDisabled();
        await page.screenshot({ path: OUT_DIR + '/06-own-locked-sd.png', fullPage: true });
        await page.getByTestId('pb2-style-lock').click();
        await waitForToast(page, 'Style unlocked');
        await expect(page.getByTestId('pb2-style-sd-fields')).not.toHaveAttribute('disabled', '');
        await expect(seedInput).toBeEnabled();
        await expect(page.getByTestId('pb2-style-sd-unlink')).toBeEnabled();
        const relocked = await getStyle(request, bookObjectId);
        expect(relocked.own.locked, 'unlocked again').toBe(false);

        // The back arrow returns to the reader of this book.
        await page.getByTestId('pb2-style-back').click();
        await expect(page).toHaveURL(new RegExp('#!/picture-book/v2/' + bookObjectId + '$'));
        await expect(page.getByTestId('pb2-style-link')).toBeVisible({ timeout: 30000 });
        await request.get(REST + '/logout');
    });
});
