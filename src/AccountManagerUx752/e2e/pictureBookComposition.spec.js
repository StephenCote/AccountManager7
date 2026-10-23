/**
 * PictureBook V3 Phase 4 — the composition board + wardrobe UI (P4-9, design §26), live on the Docker stack as
 * the shared non-admin user, in a real browser. Its own book (POST /chapter), two olio.pb.scene rows, and —
 * in the default tier — a hand-seeded SCENE composition (generic POST /rest/model olio.pb.composition) with one
 * text actor and one element added through the P4-8 row routes, so the board has rows to show without an LLM.
 *
 * What this spec proves (every edit on the board is a row-level PATCH against the P4-8 routes; the board
 * re-reads composition + outfits + /plan after each write and the PbPlanInspector shows the compiled prompt):
 *   default            — scene 2 (no composition) shows the empty state; scene 1 renders status chips, camera
 *                        fields, the actor row with its outfit chip, the element row, the canvas boxes and the plan
 *                        side; Save on a changed camera field is one PATCH {changed keys only} → 'Composition saved',
 *                        rev bumped, `render stale` chip, user-edited chip; Save on an unchanged row is no request;
 *                        prompt override + lock; actor row Save PATCHes only the changed keys; Add element dialog →
 *                        POST element → row appears; Promote to item → item chip; delete element (confirm) → gone;
 *                        the reader's Compose button deep-links to the board; the wardrobe panel on a character
 *                        (none on this book → the character list is empty; the panel's own list is covered by the
 *                        Vitest). No LLM, no SD.
 *   PB_LLM_TESTS=1     — the header's "Chat Config" control picks PICKER_CONFIG_NAME through the real ObjectPicker;
 *                        scene 2's empty-state "Compose this scene" POSTs {chatConfigName} and runs the synchronous
 *                        single-scene compose against the live LLM tier → the board fills with an `llm` composition;
 *                        "Re-compose scene" on the user-edited scene 1 asks first (dialog), then POSTs
 *                        {force:true, chatConfigName}. A `migrated` (no-LLM fallback) result is red.
 *   PB_SD_TESTS=1      — "Compose book…" with Quick start + the picked config POSTs {quickStart, force,
 *                        chatConfigName} → the job runs to terminal on the page → the scene list shows one
 *                        `composed` row per scene; the draft bytes are a real image (fetched from the artifact).
 *
 * Run (Windows / Docker stack — 127.0.0.1, never localhost):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 [PB_LLM_TESTS=1] [PB_SD_TESTS=1] \
 *     npx playwright test e2e/pictureBookComposition.spec.js --workers=1 --project=chromium --retries=0
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results');
const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const TAG = '[pictureBookComposition]';
const ORG_ID = 2;
// The LLM/SD tiers' chatConfig (ensureChatConfig → resolveChatRoute(): LiteLLM-in-stack first). The '000-' prefix
// is the device chapBookChatConfigPicker.spec.js and pictureBookChapteredManuscriptUx.spec.js use: the picker
// shows ~/Chat name-ascending, 25 rows a page, and the shared user's ~/Chat holds dozens of leftover e2e configs
// (7 pages on 2026-10-22), so `e2e-chapbook-llm` is unreachable without paging while a name that sorts first is
// on page 1. Same shared connection as the default config (one scene compose fits its 300 s budget).
const PICKER_CONFIG_NAME = '000-p49-board-llm';
// Verbatim from features/pictureBookCompose.js — the Re-compose confirm on a hand-edited composition.
const USER_EDITED_WARNING = 'This composition was edited by hand. Re-composing overwrites every actor, element and camera field with what the LLM proposes; pinned outfits and the prompt override are kept only if the server keeps them. Continue?';

const SCENES = [
    [1, 'Dawn on the Quay', 'The harbour road at dawn, gulls over the fishing boats',
        'A fishwife hauls a basket of herring up the slick stone steps while a boy coils rope', 'brisk'],
    [2, 'The Copyist', 'A cramped scriptorium in the abbey, candles guttering',
        'An old monk bends over a half-finished page, a quill in one hand and a knife in the other', 'hushed']
];

test.describe.configure({ mode: 'serial' });

let api;
let shared;
let bookObjectId;
let bookSlug;
let bookId;
let bookGroupId;
let bookGroupPath;
let bookReset = false;
let sceneIds = [];         // objectId in story order
let compositionObjectId;   // scene 1's hand-seeded composition
let actorObjectId;         // the seeded text actor
let actorName;             // its server-assigned name (actor.<comp>.<n>) — the row heading for an unnamed actor
let elementObjectId;       // the seeded element
let addedElementObjectId;  // the element added through the dialog
let revision;              // scene 1's composition revision as last read from the board GET; every write bumps it by one

function lc(v) { return String(v == null ? '' : v).toLowerCase(); }

async function expectStatus(resp, status, what) {
    const body = await resp.text();
    expect(resp.status(), what + ': expected ' + status + ' got ' + resp.status() + ' ' + body.slice(0, 400)).toBe(status);
    try { return JSON.parse(body); } catch (e) { return body; }
}

async function search(type, fields, request, extra) {
    const r = await api.post(REST + '/model/search', { data: Object.assign({ schema: 'io.query', type, fields, request, recordCount: 50, cache: false }, extra || {}) });
    const res = await expectStatus(r, 200, type + ' search');
    return (res && res.results) || [];
}

async function getBoard(sceneOid) {
    const r = await api.get(PB + '/' + bookObjectId + '/scene/' + sceneOid + '/composition');
    return { status: r.status(), body: await (async () => { const t = await r.text(); try { return JSON.parse(t); } catch (e) { return t; } })() };
}

async function fetchImage(dataObjectId, fileName) {
    const dataRec = await expectStatus(await api.get(REST + '/model/data.data/' + dataObjectId + '/full'), 200, 'data.data ' + dataObjectId);
    expect(dataRec.groupPath && dataRec.name, 'data.data missing groupPath/name: ' + JSON.stringify(dataRec).slice(0, 300)).toBeTruthy();
    expect((dataRec.contentType || '').startsWith('image/'), 'contentType ' + dataRec.contentType).toBe(true);
    const mediaUrl = '/AccountManagerService7/media/Development/data.data' + encodeURI(dataRec.groupPath) + '/' + encodeURIComponent(dataRec.name);
    const img = await api.get(mediaUrl, { timeout: 60000 });
    expect(img.ok(), 'media fetch ' + img.status() + ' ' + mediaUrl).toBe(true);
    const buf = Buffer.from(await img.body());
    const isPng = buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47;
    const isJpeg = buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
    expect(isPng || isJpeg, 'bytes are neither PNG nor JPEG: ' + buf.slice(0, 4).toString('hex')).toBe(true);
    expect(buf.length, 'image is not a stub').toBeGreaterThan(1024);
    const out = path.join(OUT_DIR, fileName + (isPng ? '.png' : '.jpg'));
    fs.writeFileSync(out, buf);
    console.log(TAG + ' wrote ' + buf.length + ' bytes to ' + out + ' ' + dataRec.contentType);
    return out;
}

async function resetBook() {
    if (bookReset || !bookObjectId) return;
    const r = await api.delete(PB + '/' + bookObjectId + '/reset', { timeout: 120000 });
    console.log(TAG + ' DELETE /' + bookObjectId + '/reset -> ' + r.status() + ' ' + (await r.text()).slice(0, 300));
    bookReset = true;
}

// Browser login (canonical pattern, helpers/chapbook.js): the Docker nginx drops the session cookie on the WS
// upgrade so Tomcat closes it and pageClient would forceLogin(); stub WebSocket before goto.
async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: Buffer.from(shared.testPassword).toString('base64'), type: 'hashed_password' }
    });
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

/** Record every write the board makes against the picture-book routes: [{method, path, body}]. */
function recordWrites(page) {
    const writes = [];
    page.on('request', req => {
        const u = req.url();
        const i = u.indexOf('/olio/picture-book/');
        if (i < 0 || req.method() === 'GET') return;
        let body = null;
        try { body = req.postData() ? JSON.parse(req.postData()) : null; } catch (e) { body = req.postData(); }
        writes.push({ method: req.method(), path: u.slice(i + '/olio/picture-book/'.length), body });
    });
    return writes;
}

async function openBoard(page, sceneOid) {
    await page.goto('/#!/picture-book/' + bookObjectId + '/compose/' + sceneOid, { timeout: 30000 });
    await expect(page.getByTestId('pb-cmp')).toBeVisible({ timeout: 30000 });
}

// The board compiles /plan on open and after every write (act → refetch → loadBoard → loadPlan). Register the
// waiter BEFORE the action: right after a save toast the button still reads 'Recompile' from the previous plan,
// so waiting on the text alone would pass on the stale plan. Planning resolves models against the SD inventory;
// while that server is unreachable each read blocks for its connect timeout, so the waits are generous and the
// elapsed time is logged, never asserted.
function planWaiter(page) {
    return page.waitForResponse(r => r.request().method() === 'POST' && /\/scene\/[^/]+\/plan$/.test(r.url()), { timeout: 900000 });
}

async function waitForPlan(page, label, pending) {
    const t0 = Date.now();
    const resp = await pending;
    expect(resp.status(), label + ': POST /plan status').toBe(200);
    await expect(page.getByTestId('pb-cmp-plan-refresh')).toHaveText('Recompile', { timeout: 900000 });
    const prompt = page.getByTestId('pb-cmp-plan-prompt');
    const err = page.getByTestId('pb-cmp-plan-error');
    const planned = (await prompt.count()) > 0;
    const errored = (await err.count()) > 0;
    // A compiled plan whose stages all have blank prompts shows neither element; that is a plan, not a failure.
    console.log(TAG + ' ' + label + ': POST /plan ' + resp.status() + ' → ' + (planned ? 'prompt shown' : errored ? 'ERROR ' + (await err.textContent()) : 'no headline prompt') + ' in ' + (Date.now() - t0) + ' ms');
    return planned;
}

/** A Dialog.confirm footer button by label; the accessible name also carries the icon ligature, and the board has its own 'Re-compose scene' button. */
function dialogButton(page, label) {
    return page.locator('.am7-dialog-footer button', { hasText: label });
}

async function readRevision() {
    const b = await getBoard(sceneIds[0]);
    expect(b.status, 'board GET for revision').toBe(200);
    return b.body.composition.revision;
}

/**
 * Pick the compose chat config through the real ObjectPicker on the board header's "Chat Config" control
 * (pattern: pictureBookChapteredManuscriptUx.spec.js pickChatConfig). The board resolves a library default
 * (contentAnalysis → generalChat) on open; wait for that to settle so it cannot overwrite the pick, then pick
 * the shared user's ~/Chat/<configName> — the one ensureChatConfig() routed to the live LLM tier (a '000-' name,
 * see PICKER_CONFIG_NAME: the row must be on the picker's first page). A compose without a key-bearing config
 * falls to the organization's generalChat, keyless on this stack → "LLM unavailable".
 */
async function pickChatConfig(page, configName) {
    const ctl = page.getByTestId('pb-cmp-chatconfig');
    await expect(ctl).toBeVisible({ timeout: 30000 });
    await expect(ctl).not.toContainText('Resolving default…', { timeout: 30000 });
    console.log(TAG + ' board chat config before pick: "' + ((await ctl.textContent()) || '').replace(/\s+/g, ' ').trim() + '"');
    await ctl.click();
    const overlay = page.locator('.am7-picker-overlay');
    await expect(overlay).toBeVisible({ timeout: 15000 });
    await expect(overlay.locator('h3')).toHaveText('Select Chat Config');
    const row = overlay.locator('tr.tabular-row', { hasText: configName });
    await expect(row, 'picker lists the shared user\'s ~/Chat/' + configName).toBeVisible({ timeout: 20000 });
    await row.click();
    await expect(row).toHaveClass(/tabular-row-active/);
    await overlay.locator('button:has(span:text-is("check"))').first().click();
    await expect(overlay).toHaveCount(0, { timeout: 10000 });
    await expect(ctl).toContainText(configName);
}

async function shot(page, name) {
    await page.screenshot({ path: path.join(OUT_DIR, name + '.png'), fullPage: true });
}

test.beforeAll(async ({ request }) => {
    test.setTimeout(5 * 60 * 1000);
    fs.mkdirSync(OUT_DIR, { recursive: true });
    const baseURL = test.info().project.use.baseURL;
    shared = await ensureSharedTestUser(request);
    api = await pwRequest.newContext({ baseURL, ignoreHTTPSErrors: true });
    await api.get(REST + '/logout');
    const login = await api.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: Buffer.from(shared.testPassword).toString('base64'), type: 'hashed_password' }
    });
    expect(login.ok() || login.status() === 204, 'shared-user login failed: ' + login.status()).toBe(true);

    const rp = await expectStatus(await api.get(REST + '/config/render-path'), 200, 'GET /config/render-path');
    expect(String(rp.picturebook).toLowerCase(), 'the board is V3-only; the test stack boots picturebook on v3').toBe('v3');

    bookSlug = 'p49brd' + Date.now().toString(36);
    const cb = await expectStatus(await api.post(PB + '/chapter', { data: { slug: bookSlug, title: 'P4-9 Board ' + bookSlug }, timeout: 180000 }), 200, 'POST /chapter');
    bookObjectId = cb.bookObjectId;
    expect(bookObjectId, 'POST /chapter returned no bookObjectId: ' + JSON.stringify(cb)).toBeTruthy();

    const book = (await search('olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }], ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath'], { recordCount: 1 }))[0];
    expect(book && book.id, 'created olio.pb.book not found by objectId').toBeTruthy();
    bookId = book.id; bookGroupId = book.groupId; bookGroupPath = book.groupPath;

    for (const [idx, title, setting, action, mood] of SCENES) {
        const created = await expectStatus(await api.post(REST + '/model', {
            data: {
                schema: 'olio.pb.scene', name: 'Scene ' + bookSlug + ' ' + idx, groupId: bookGroupId, groupPath: bookGroupPath,
                book: { schema: 'olio.pb.book', id: bookId, objectId: bookObjectId }, sceneIndex: idx, title, setting, action, mood,
                description: title + ': ' + setting + '. ' + action + '.'
            }
        }), 200, 'POST olio.pb.scene ' + idx);
        expect(created && created.objectId, 'scene ' + idx + ' objectId').toBeTruthy();
        sceneIds.push(created.objectId);
    }
    const pages = await expectStatus(await api.get(PB + '/' + bookObjectId + '/pages'), 200, 'GET /pages');
    expect(Array.isArray(pages) && pages.length, 'GET /pages').toBe(SCENES.length);
    console.log(TAG + ' book ' + bookSlug + ' objectId=' + bookObjectId + ' groupId=' + bookGroupId + ' scenes=' + JSON.stringify(sceneIds));
});

test.afterAll(async () => {
    if (!api) return;
    try { await resetBook(); } finally { await api.dispose(); }
});

// ────────────────────────────── default tier: no LLM, no SD ──────────────────────────────
test.describe('composition board (default tier)', () => {

    test('fixture: a hand-seeded SCENE composition on scene 1 is what the board GET resolves; one text actor and one element through the P4-8 routes', async () => {
        const [, title, setting, action, mood] = SCENES[0];
        const sceneRow = (await search('olio.pb.scene', [{ name: 'objectId', comparator: 'equals', value: sceneIds[0] }], ['id', 'objectId', 'name'], { recordCount: 1 }))[0];
        expect(sceneRow && sceneRow.id, 'scene 1 row').toBeTruthy();
        const created = await expectStatus(await api.post(REST + '/model', {
            data: {
                schema: 'olio.pb.composition', name: 'composition ' + bookSlug + ' 1', groupId: bookGroupId, groupPath: bookGroupPath,
                book: { schema: 'olio.pb.book', id: bookId, objectId: bookObjectId },
                scene: { schema: 'olio.pb.scene', id: sceneRow.id, objectId: sceneIds[0] },
                purpose: 'scene', source: 'user', settingText: setting, action, mood, shotType: 'medium', viewpoint: 'eye_level',
                cameraText: 'from the foot of the steps', props: 'herring basket, coiled rope', revision: 1
            }
        }), 200, 'POST olio.pb.composition');
        expect(created && created.objectId, 'composition objectId: ' + JSON.stringify(created)).toBeTruthy();
        compositionObjectId = created.objectId;

        const b = await getBoard(sceneIds[0]);
        expect(b.status, 'board GET: ' + JSON.stringify(b.body).slice(0, 300)).toBe(200);
        expect(b.body.compositionObjectId).toBe(compositionObjectId);
        expect(lc(b.body.composition && b.body.composition.purpose)).toBe('scene');
        expect(b.body.composition.settingText).toBe(setting);
        expect(b.body.composition.mood).toBe(mood);
        console.log(TAG + ' board DTO keys: ' + Object.keys(b.body).join(','));
        expect(b.body.actors || []).toEqual([]);
        expect(b.body.elements || []).toEqual([]);

        const a = await expectStatus(await api.post(PB + '/' + bookObjectId + '/composition/' + compositionObjectId + '/actor', { data: { description: 'a fishwife hauling a herring basket', role: 'hero', x: 0.1, y: 0.2, w: 0.3, h: 0.6 } }), 200, 'POST actor');
        expect(a.created, JSON.stringify(a).slice(0, 300)).toBe(true);
        actorObjectId = a.actorObjectId;
        actorName = a.actor && a.actor.name;
        expect(actorName, 'POST actor returns the row with its assigned name: ' + JSON.stringify(a).slice(0, 300)).toBeTruthy();
        const e = await expectStatus(await api.post(PB + '/' + bookObjectId + '/composition/' + compositionObjectId + '/element', { data: { elementType: 'prop', description: 'a basket of herring', importance: 'high', x: 0.5, y: 0.6, w: 0.2, h: 0.2 } }), 200, 'POST element');
        expect(e.created, JSON.stringify(e).slice(0, 300)).toBe(true);
        elementObjectId = e.elementObjectId;
        const b2 = await getBoard(sceneIds[0]);
        expect(b2.body.actors.map(x => x.objectId)).toEqual([actorObjectId]);
        expect(b2.body.elements.map(x => x.objectId)).toEqual([elementObjectId]);
        expect(b2.body.composition.revision, 'two row writes bumped the seeded revision by exactly two').toBe((b.body.composition.revision || 0) + 2);
        revision = b2.body.composition.revision;
        const n = await getBoard(sceneIds[1]);
        expect(n.status, 'scene 2 has no composition yet').toBe(404);
        expect(String(n.body && (n.body.message || n.body.error))).toMatch(/has no composition/i);
        console.log(TAG + ' seeded composition=' + compositionObjectId + ' actor=' + actorObjectId + ' (' + actorName + ') element=' + elementObjectId + ' revision=' + revision + ' title=' + title);
    });

    test('scene 2 opens to the empty state; the header navigates between scenes; the reader deep-links to the board', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        await openBoard(page, sceneIds[1]);
        await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-empty-message')).toContainText('has no composition');
        await expect(page.getByTestId('pb-cmp-compose-scene')).toBeEnabled();
        await expect(page.getByTestId('pb-cmp-camera')).toHaveCount(0);
        const sel = page.getByTestId('pb-cmp-scene-select');
        await expect(sel).toHaveValue(sceneIds[1]);
        await expect(sel.locator('option')).toHaveText(['1 · Dawn on the Quay', '2 · The Copyist']);
        await expect(page.getByTestId('pb-cmp-scene-next')).toBeDisabled();
        await shot(page, 'p49-board-empty');

        await page.getByTestId('pb-cmp-scene-prev').click();
        await expect(page).toHaveURL(new RegExp('/picture-book/' + bookObjectId + '/compose/' + sceneIds[0] + '$'));
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
        await expect(sel).toHaveValue(sceneIds[0]);

        // Back → the reader; the reader's Compose button on page 1 deep-links to this scene's board.
        await page.getByTestId('pb-cmp-back').click();
        await expect(page).toHaveURL(new RegExp('/picture-book/v2/' + bookObjectId + '$'));
        await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
        await page.locator('button', { hasText: 'Begin' }).click();
        await expect(page.getByText(/^Page 1 of \d+$/).first()).toBeVisible({ timeout: 30000 });
        const btn = page.getByTestId('pb2-scene-compose');
        await expect(btn).toBeVisible();
        await expect(btn).toHaveAttribute('title', 'Open the composition board for this scene (camera, actors, elements, wardrobe)');
        await btn.click();
        await expect(page).toHaveURL(new RegExp('/picture-book/' + bookObjectId + '/compose/' + sceneIds[0] + '$'));
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
    });

    test('scene 1 renders the seeded rows: status chips, camera fields, the actor row + outfit chip, the element row, canvas boxes and the compiled plan', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-source')).toHaveText('user');
        await expect(page.getByTestId('pb-cmp-purpose')).toHaveText('scene');
        await expect(page.getByTestId('pb-cmp-revision')).toHaveText('rev ' + revision);
        // The fixture's P4-8 actor/element POSTs each end in touchEdited (userEdited, source=USER, revision+1), so the chip is already here.
        await expect(page.getByTestId('pb-cmp-user-edited')).toHaveCount(1);
        await expect(page.getByTestId('pb-cmp-locked')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-stale')).toHaveCount(0);

        await expect(page.getByTestId('pb-cmp-shot')).toHaveValue('medium');
        await expect(page.getByTestId('pb-cmp-viewpoint')).toHaveValue('eye_level');
        await expect(page.getByTestId('pb-cmp-camera-text')).toHaveValue('from the foot of the steps');
        await expect(page.getByTestId('pb-cmp-mood')).toHaveValue('brisk');
        await expect(page.getByTestId('pb-cmp-setting')).toHaveValue(SCENES[0][2]);
        await expect(page.getByTestId('pb-cmp-action')).toHaveValue(SCENES[0][3]);
        await expect(page.getByTestId('pb-cmp-props')).toHaveValue('herring basket, coiled rope');
        const shotOptions = await page.getByTestId('pb-cmp-shot').locator('option').allTextContents();
        expect(shotOptions, 'enum vocabulary, UNKNOWN hidden').toEqual(['(unset)', 'close_up', 'medium', 'full_body', 'wide', 'establishing']);

        const actor = page.getByTestId('pb-cmp-actor-' + actorObjectId);
        await expect(actor).toBeVisible();
        // An unnamed (text) actor is headed by its server-assigned name; its description is the editable field.
        await expect(actor).toContainText(actorName);
        await expect(actor.getByTestId('pb-cmp-actor-description')).toHaveValue('a fishwife hauling a herring basket');
        await expect(actor).toContainText('unnamed');
        await expect(actor.getByTestId('pb-cmp-actor-role')).toHaveValue('hero');
        await expect(actor.getByTestId('pb-cmp-actor-x')).toHaveValue('0.1');
        await expect(actor.getByTestId('pb-cmp-actor-h')).toHaveValue('0.6');
        const chip = actor.getByTestId('pb-cmp-actor-outfit-chip');
        await expect(chip).toHaveAttribute('data-source', 'none');
        await expect(actor.getByTestId('pb-cmp-actor-outfit-pick')).toBeDisabled();
        await expect(actor.getByTestId('pb-cmp-actor-outfit-pick')).toHaveAttribute('title', 'Unnamed actors have no wardrobe');

        const el = page.getByTestId('pb-cmp-element-' + elementObjectId);
        await expect(el).toBeVisible();
        await expect(el).toContainText('a basket of herring');
        await expect(el.getByTestId('pb-cmp-element-type')).toHaveValue('prop');
        await expect(el.getByTestId('pb-cmp-element-importance')).toHaveValue('high');
        await expect(el.getByTestId('pb-cmp-element-item')).toHaveCount(0);
        await expect(el.getByTestId('pb-cmp-element-promote')).toBeEnabled();

        const abox = page.getByTestId('pb-cmp-box-actor-' + actorObjectId);
        await expect(abox).toBeVisible();
        await expect(abox).toHaveCSS('left', /px$/);
        const style = await abox.getAttribute('style');
        expect(style, 'actor box placed from its normalized x/y/w/h: ' + style).toMatch(/left:\s*10(\.0+)?%/);
        expect(style).toMatch(/top:\s*20(\.0+)?%/);
        expect(style).toMatch(/width:\s*30(\.0+)?%/);
        expect(style).toMatch(/height:\s*60(\.0+)?%/);
        await expect(page.getByTestId('pb-cmp-box-element-' + elementObjectId)).toBeVisible();

        const planned = await waitForPlan(page, 'scene 1 open', planOpen);
        // The seeded scene carries a setting, an actor and an element, so the compiled plan always has a non-blank headline prompt.
        expect(planned, 'the seeded scene compiles a headline prompt in the browser').toBe(true);
        const direct = await expectStatus(await api.post(PB + '/' + bookObjectId + '/scene/' + sceneIds[0] + '/plan', { data: {}, timeout: 900000 }), 200, 'POST /plan');
        // The board's headline prompt is the first compiled stage with a non-blank prompt; the inspector shows the same plan.
        const stage = await page.getByTestId('pb-cmp-plan-prompt').getAttribute('data-stage');
        expect(direct.compiled && direct.compiled[stage] && direct.compiled[stage].prompt, 'stage ' + stage + ' in the direct plan').toBeTruthy();
        await expect(page.getByTestId('pb-cmp-plan-prompt')).toHaveText(direct.compiled[stage].prompt);
        await expect(page.getByTestId('pb2-plan-inspector')).toBeVisible();
        await expect(page.getByTestId('pb2-plan-strategy')).toHaveText(direct.strategy.name);
        const degs = Array.isArray(direct.degradations) ? direct.degradations : [];
        if (degs.length) await expect(page.getByTestId('pb-cmp-degradations')).toHaveAttribute('data-count', String(degs.length));
        else await expect(page.getByTestId('pb-cmp-degradations')).toHaveCount(0);
        console.log(TAG + ' plan: stage=' + stage + ' strategy=' + direct.strategy.name + ' degradations=' + JSON.stringify(degs.map(d => d.code)));
        await shot(page, 'p49-board-scene1');
    });

    test('Save on a changed camera field is one PATCH with only the changed keys; an unchanged Save makes no request', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before camera save', planOpen);

        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.getByText('Nothing changed')).toBeVisible({ timeout: 10000 });
        expect(writes.filter(w => w.method === 'PATCH'), 'no PATCH for an unchanged row').toEqual([]);

        await page.getByTestId('pb-cmp-shot').selectOption('wide');
        await page.getByTestId('pb-cmp-mood').fill('brisk, salt wind');
        const planAfter = planWaiter(page);
        const patchResp = page.waitForResponse(r => r.request().method() === 'PATCH' && r.url().includes('/scene/' + sceneIds[0] + '/composition'), { timeout: 60000 });
        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.getByText('Composition saved')).toBeVisible({ timeout: 60000 });
        const patches = writes.filter(w => w.method === 'PATCH');
        expect(patches.length, JSON.stringify(writes)).toBe(1);
        expect(patches[0].path).toBe(bookObjectId + '/scene/' + sceneIds[0] + '/composition');
        expect(patches[0].body).toEqual({ shotType: 'wide', mood: 'brisk, salt wind' });
        const edit = await (await patchResp).json();
        expect(edit.updated, JSON.stringify(edit).slice(0, 300)).toBe(true);
        expect(edit.revision).toBe(revision + 1);

        await expect(page.getByTestId('pb-cmp-revision')).toHaveText('rev ' + (revision + 1));
        await expect(page.getByTestId('pb-cmp-user-edited')).toBeVisible();
        // `render stale` is shown only when the server marked a render node stale; a never-rendered scene has no
        // node, so EditResult.nodeStale is false and the chip is legitimately absent (PbComposeUtil.EditResult).
        console.log(TAG + ' camera PATCH response: nodeStale=' + edit.nodeStale + ' nodeStatus=' + edit.nodeStatus);
        if (edit.nodeStale) await expect(page.getByTestId('pb-cmp-stale')).toBeVisible();
        else await expect(page.getByTestId('pb-cmp-stale')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-shot')).toHaveValue('wide');
        await expect(page.getByTestId('pb-cmp-mood')).toHaveValue('brisk, salt wind');
        await waitForPlan(page, 'after camera save', planAfter);

        const b = await getBoard(sceneIds[0]);
        expect(lc(b.body.composition.shotType)).toBe('wide');
        expect(b.body.composition.mood).toBe('brisk, salt wind');
        expect(b.body.composition.userEdited).toBe(true);
        expect(b.body.composition.revision).toBe(revision + 1);
        expect(b.body.composition.cameraText, 'untouched field kept').toBe('from the foot of the steps');
        revision = b.body.composition.revision;
        await shot(page, 'p49-board-camera-saved');
    });

    test('(unset) on an enum that had a value is sent as "unknown", reads back as (unset), and can be set again', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-shot')).toHaveValue('wide', { timeout: 60000 });
        await waitForPlan(page, 'before unset', planOpen);

        // PbComposeUtil.enumField matches case-insensitively and every composition enum has UNKNOWN; a bare '' answers 400.
        await page.getByTestId('pb-cmp-shot').selectOption('');
        let planAfter = planWaiter(page);
        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.getByText('Composition saved')).toBeVisible({ timeout: 60000 });
        let patches = writes.filter(w => w.method === 'PATCH');
        expect(patches.length, JSON.stringify(writes)).toBe(1);
        expect(patches[0].body).toEqual({ shotType: 'unknown' });
        await expect(page.getByTestId('pb-cmp-shot')).toHaveValue('');
        await expect(page.getByTestId('pb-cmp-revision')).toHaveText('rev ' + (revision + 1));
        await waitForPlan(page, 'after unset', planAfter);
        let b = await getBoard(sceneIds[0]);
        expect(lc(b.body.composition.shotType)).toBe('unknown');
        expect(b.body.composition.revision).toBe(revision + 1);

        // An unchanged (unset) is not re-sent.
        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.getByText('Nothing changed')).toBeVisible({ timeout: 10000 });
        expect(writes.filter(w => w.method === 'PATCH').length, 'no PATCH for an unchanged (unset)').toBe(1);

        await page.getByTestId('pb-cmp-shot').selectOption('wide');
        planAfter = planWaiter(page);
        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.getByText('Composition saved')).toBeVisible({ timeout: 60000 });
        patches = writes.filter(w => w.method === 'PATCH');
        expect(patches.length).toBe(2);
        expect(patches[1].body).toEqual({ shotType: 'wide' });
        await expect(page.getByTestId('pb-cmp-shot')).toHaveValue('wide');
        await waitForPlan(page, 'after re-set', planAfter);
        b = await getBoard(sceneIds[0]);
        expect(lc(b.body.composition.shotType)).toBe('wide');
        expect(b.body.composition.revision).toBe(revision + 2);
        revision = b.body.composition.revision;
        await shot(page, 'p49-board-unset-roundtrip');
    });

    test('prompt override + lock is one PATCH {promptOverride, promptLocked}; the locked chip appears', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-prompt')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before prompt save', planOpen);
        await page.getByTestId('pb-cmp-prompt-override').fill('a fishwife on wet stone steps at dawn, gulls, painterly');
        await page.getByTestId('pb-cmp-prompt-locked').check();
        const planAfter = planWaiter(page);
        await page.getByTestId('pb-cmp-save-prompt').click();
        await expect(page.getByText('Prompt saved')).toBeVisible({ timeout: 60000 });
        const patches = writes.filter(w => w.method === 'PATCH');
        expect(patches.length, JSON.stringify(writes)).toBe(1);
        expect(patches[0].body).toEqual({ promptOverride: 'a fishwife on wet stone steps at dawn, gulls, painterly', promptLocked: true });
        await expect(page.getByTestId('pb-cmp-locked')).toBeVisible();
        await expect(page.getByTestId('pb-cmp-revision')).toHaveText('rev ' + (revision + 1));
        await waitForPlan(page, 'after prompt save', planAfter);
        const b = await getBoard(sceneIds[0]);
        expect(b.body.composition.promptLocked).toBe(true);
        expect(b.body.composition.promptOverride).toBe('a fishwife on wet stone steps at dawn, gulls, painterly');
        expect(b.body.composition.revision).toBe(revision + 1);
        revision = b.body.composition.revision;
    });

    test('actor row Save PATCHes only the changed keys of that actor', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        const actor = page.getByTestId('pb-cmp-actor-' + actorObjectId);
        await expect(actor).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before actor save', planOpen);
        await actor.getByTestId('pb-cmp-actor-pose').fill('bent under the basket');
        await actor.getByTestId('pb-cmp-actor-facing').selectOption('left');
        await actor.getByTestId('pb-cmp-actor-x').fill('0.25');
        // depth is the model's int layer order (0 = front-most); the board rounds before sending so the server's intField never truncates silently.
        await expect(actor.getByTestId('pb-cmp-actor-depth')).toHaveAttribute('step', '1');
        await actor.getByTestId('pb-cmp-actor-depth').fill('2.6');
        const planAfter = planWaiter(page);
        await actor.getByTestId('pb-cmp-actor-save').click();
        await expect(page.getByText('Actor saved')).toBeVisible({ timeout: 60000 });
        const patches = writes.filter(w => w.method === 'PATCH');
        expect(patches.length, JSON.stringify(writes)).toBe(1);
        expect(patches[0].path).toBe(bookObjectId + '/composition/' + compositionObjectId + '/actor/' + actorObjectId);
        expect(patches[0].body).toEqual({ pose: 'bent under the basket', facing: 'left', x: 0.25, depth: 3 });
        await expect(page.getByTestId('pb-cmp-revision')).toHaveText('rev ' + (revision + 1));
        await expect(actor.getByTestId('pb-cmp-actor-pose')).toHaveValue('bent under the basket');
        await expect(actor.getByTestId('pb-cmp-actor-depth')).toHaveValue('3');
        await expect(page.getByTestId('pb-cmp-box-actor-' + actorObjectId)).toHaveAttribute('style', /left:\s*25(\.0+)?%/);
        await expect(page.getByTestId('pb-cmp-box-actor-' + actorObjectId)).toHaveAttribute('style', /z-index:\s*997\b/);
        await waitForPlan(page, 'after actor save', planAfter);
        const b = await getBoard(sceneIds[0]);
        const a = b.body.actors.find(x => x.objectId === actorObjectId);
        expect(a.pose).toBe('bent under the basket');
        expect(lc(a.facing)).toBe('left');
        expect(a.x).toBe(0.25);
        expect(a.depth, 'int depth stored as sent').toBe(3);
        expect(a.y, 'untouched box field kept').toBe(0.2);
        expect(b.body.composition.revision).toBe(revision + 1);
        revision = b.body.composition.revision;
    });

    test('Add element dialog POSTs the row; Promote to item links it; delete asks first and removes it', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-elements')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before add element', planOpen);

        await page.getByTestId('pb-cmp-element-add').click();
        await expect(page.getByTestId('pb-cmp-element-add-dialog')).toBeVisible();
        await page.getByTestId('pb-cmp-add-element-type').selectOption('furniture');
        await page.getByTestId('pb-cmp-add-element-description').fill('an upturned rowing boat');
        await page.getByTestId('pb-cmp-add-element-importance').selectOption('low');
        const planAdd = planWaiter(page);
        await page.getByTestId('pb-cmp-add-element-submit').click();
        await expect(page.getByText('Element added')).toBeVisible({ timeout: 60000 });
        const posts = writes.filter(w => w.method === 'POST' && /\/element$/.test(w.path));
        expect(posts.length, JSON.stringify(writes)).toBe(1);
        expect(posts[0].body).toEqual({ elementType: 'furniture', description: 'an upturned rowing boat', importance: 'low' });
        await expect(page.getByTestId('pb-cmp-elements')).toContainText('Elements (2)');
        const b = await getBoard(sceneIds[0]);
        const added = b.body.elements.find(x => x.description === 'an upturned rowing boat');
        expect(added, JSON.stringify(b.body.elements)).toBeTruthy();
        addedElementObjectId = added.objectId;
        const row = page.getByTestId('pb-cmp-element-' + addedElementObjectId);
        await expect(row).toBeVisible();
        await expect(row.getByTestId('pb-cmp-element-type')).toHaveValue('furniture');
        await waitForPlan(page, 'after add element', planAdd);

        // Promote: no body. On success the toast names the item and the row shows the item chip with Promote disabled;
        // a refusal (this composition has no location, so there is no location to hold an item) is the server's
        // message on an error toast and the row is unchanged. Either way the request is made and the board re-reads.
        const planPromote = planWaiter(page);
        const promoteResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/promote$/.test(r.url()), { timeout: 120000 });
        await row.getByTestId('pb-cmp-element-promote').click();
        const pr = await promoteResp;
        const prText = await pr.text();
        let prBody = null; try { prBody = JSON.parse(prText); } catch (e) { prBody = null; }
        console.log(TAG + ' POST promote → ' + pr.status() + ' ' + prText.slice(0, 300));
        const promotes = writes.filter(w => w.method === 'POST' && /\/promote$/.test(w.path));
        expect(promotes.length, JSON.stringify(writes)).toBe(1);
        expect(promotes[0].path).toBe(bookObjectId + '/composition/' + compositionObjectId + '/element/' + addedElementObjectId + '/promote');
        if (pr.ok()) {
            await expect(page.getByText(/^(Created item|Linked existing item|Element was not promoted)/).first()).toBeVisible({ timeout: 60000 });
        } else {
            const msg = String((prBody && (prBody.message || prBody.error)) || '').trim();
            expect(msg, 'a refusal carries a message').toBeTruthy();
            await expect(page.getByText(msg, { exact: false }).first()).toBeVisible({ timeout: 60000 });
        }
        await waitForPlan(page, 'after promote', planPromote);
        const b2 = await getBoard(sceneIds[0]);
        const promoted = b2.body.elements.find(x => x.objectId === addedElementObjectId);
        console.log(TAG + ' promote: ok=' + pr.ok() + ' promoted=' + (prBody && prBody.promoted) + ' itemObjectId=' + (promoted && promoted.itemObjectId) + ' revision=' + b2.body.composition.revision);
        if (promoted && promoted.itemObjectId) {
            await expect(row.getByTestId('pb-cmp-element-item')).toBeVisible({ timeout: 60000 });
            await expect(row.getByTestId('pb-cmp-element-promote')).toBeDisabled();
        } else {
            await expect(row.getByTestId('pb-cmp-element-item')).toHaveCount(0);
            await expect(row.getByTestId('pb-cmp-element-promote')).toBeEnabled();
        }
        await shot(page, 'p49-board-element-promoted');

        // Delete asks first; declining makes no request.
        await row.getByTestId('pb-cmp-element-delete').click();
        await expect(page.getByText('Remove "an upturned rowing boat" from the composition?')).toBeVisible();
        await dialogButton(page, 'Cancel').click();
        await expect(page.locator('.am7-dialog-footer')).toHaveCount(0);
        expect(writes.filter(w => w.method === 'DELETE')).toEqual([]);
        const planDelete = planWaiter(page);
        await row.getByTestId('pb-cmp-element-delete').click();
        await expect(page.getByText('Remove "an upturned rowing boat" from the composition?')).toBeVisible();
        await dialogButton(page, 'Remove').click();
        await expect(page.getByText('Element removed')).toBeVisible({ timeout: 60000 });
        const dels = writes.filter(w => w.method === 'DELETE');
        expect(dels.length).toBe(1);
        expect(dels[0].path).toBe(bookObjectId + '/composition/' + compositionObjectId + '/element/' + addedElementObjectId);
        await expect(row).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-elements')).toContainText('Elements (1)');
        await waitForPlan(page, 'after delete element', planDelete);
        const b3 = await getBoard(sceneIds[0]);
        expect(b3.body.elements.map(x => x.objectId)).toEqual([elementObjectId]);
        revision = b3.body.composition.revision;
    });

    test('a bad enum on Save is the server\'s 400 verbatim; the draft stays dirty and nothing is written', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before bad patch', planOpen);
        const before = await readRevision();
        expect(before).toBe(revision);
        // The select only offers the vocabulary, so the refusal is reached through the direct route: prove it refuses
        // with the server's message and writes nothing.
        const r = await api.patch(PB + '/' + bookObjectId + '/scene/' + sceneIds[0] + '/composition', { data: { shotType: 'BOGUS' } });
        const body = await expectStatus(r, 400, 'PATCH bad enum');
        expect(String(body.message || body.error)).toMatch(/shotType must be one of/);
        expect(await readRevision(), 'nothing written').toBe(before);

        // Through the UI: the number input lets an out-of-range box coordinate through and the server refuses it
        // (unitField: 0..1, never clamped). The refusal is the server's message on an error toast, the board re-reads,
        // and the typed value is still in the field — a refused write saved nothing, so there is nothing to discard.
        const elRow = page.getByTestId('pb-cmp-element-' + elementObjectId);
        await expect(elRow).toBeVisible();
        const xField = elRow.getByTestId('pb-cmp-element-x');
        const xBefore = await xField.inputValue();
        const planRefused = planWaiter(page);
        const refusedResp = page.waitForResponse(r2 => r2.request().method() === 'PATCH' && /\/element\/[^/]+$/.test(r2.url()), { timeout: 120000 });
        await xField.fill('5');
        await elRow.getByTestId('pb-cmp-element-save').click();
        const refused = await refusedResp;
        console.log(TAG + ' PATCH element x=5 → ' + refused.status() + ' ' + (await refused.text()).slice(0, 200));
        expect(refused.status(), 'x=5 is refused').toBe(400);
        await expect(page.getByText(/x must be between 0 and 1/).first()).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'after refused element patch', planRefused);
        await expect(xField, 'the draft survives the refusal and the re-read').toHaveValue('5');
        expect(await readRevision(), 'nothing written by the refused element patch').toBe(before);
        await shot(page, 'p49-board-refused-draft-kept');
        // put the draft back so the row is clean for the rest of the test
        await xField.fill(xBefore);
        const planAfter = planWaiter(page);
        await page.getByTestId('pb-cmp-camera-text').fill('');
        await page.getByTestId('pb-cmp-save-camera').click();
        await expect(page.getByText('Composition saved')).toBeVisible({ timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-camera-text')).toHaveValue('');
        await waitForPlan(page, 'after blank cameraText', planAfter);
        const b = await getBoard(sceneIds[0]);
        expect(b.body.composition.cameraText || '', 'blanking a free-text field is a PATCH {cameraText:\'\'}').toBe('');
        expect(b.body.composition.revision).toBe(before + 1);
        revision = b.body.composition.revision;
    });

    test('Image config override: an untouched panel Save sends nothing; a changed step count is one PATCH of the sparse olio.sd.config delta; Clear empties it', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-config')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before config override', planOpen);
        let before = await readRevision();
        expect(before).toBe(revision);
        const b0 = await getBoard(sceneIds[0]);
        if (b0.body.composition.configOverride) {
            // normalize the fixture: start from no override (one PATCH through the direct route, as the test user)
            const r0 = await api.patch(PB + '/' + bookObjectId + '/scene/' + sceneIds[0] + '/composition', { data: { configOverride: '' } });
            await expectStatus(r0, 200, 'clear configOverride');
            before = await readRevision();
            const planReopen = planWaiter(page);
            await openBoard(page, sceneIds[0]);
            await expect(page.getByTestId('pb-cmp-config')).toBeVisible({ timeout: 60000 });
            await waitForPlan(page, 'after fixture normalize', planReopen);
        }
        await expect(page.getByTestId('pb-cmp-config-set')).toHaveCount(0);

        await page.getByTestId('pb-cmp-config-override').click();
        const save = page.getByTestId('pb-cmp-config-save');
        await expect(save).toBeEnabled({ timeout: 60000 });
        // Untouched: nothing is sent (a PATCH {configOverride:""} would bump the revision and mark the composition user-edited).
        await save.click();
        await expect(page.getByText('Nothing changed').first()).toBeVisible({ timeout: 60000 });
        expect(writes.filter(w => w.method === 'PATCH')).toEqual([]);
        expect(await readRevision(), 'untouched Save wrote nothing').toBe(before);

        // Change the step count through the panel's number spinner (the sr-only label names the field).
        const steps = page.getByTestId('pb-cmp-config').locator('div:has(> label:text-is("steps")) input[type=number]');
        await expect(steps).toBeVisible();
        const planSave = planWaiter(page);
        await steps.fill('17');
        await save.click();
        await expect(page.getByText('Image config override saved').first()).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'after config override save', planSave);
        const patches = writes.filter(w => w.method === 'PATCH');
        expect(patches.length, JSON.stringify(writes)).toBe(1);
        expect(patches[0].path).toBe(bookObjectId + '/scene/' + sceneIds[0] + '/composition');
        expect(Object.keys(patches[0].body)).toEqual(['configOverride']);
        expect(JSON.parse(patches[0].body.configOverride)).toEqual({ steps: 17, schema: 'olio.sd.config' });
        const b = await getBoard(sceneIds[0]);
        expect(JSON.parse(b.body.composition.configOverride)).toEqual({ steps: 17, schema: 'olio.sd.config' });
        expect(b.body.composition.revision).toBe(before + 1);
        await expect(page.getByTestId('pb-cmp-config-set')).toBeVisible({ timeout: 60000 });
        await shot(page, 'p49-board-config-override-set');

        // Clear: one PATCH {configOverride:""}; the chip goes away.
        const planClear = planWaiter(page);
        await page.getByTestId('pb-cmp-config-clear').click();
        await expect(page.getByText('Image config override cleared').first()).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'after config override clear', planClear);
        const clears = writes.filter(w => w.method === 'PATCH');
        expect(clears.length).toBe(2);
        expect(clears[1].body).toEqual({ configOverride: '' });
        const b2 = await getBoard(sceneIds[0]);
        expect(b2.body.composition.configOverride || '').toBe('');
        expect(b2.body.composition.revision).toBe(before + 2);
        await expect(page.getByTestId('pb-cmp-config-set')).toHaveCount(0);
        revision = b2.body.composition.revision;
    });

    test('Add actor dialog: the book has no characters, so an unnamed actor is added by description and shows the wardrobe-less chip', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-actors')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before add actor', planOpen);
        await page.getByTestId('pb-cmp-actor-add').click();
        const dlg = page.getByTestId('pb-cmp-actor-add-dialog');
        await expect(dlg).toBeVisible();
        await expect(page.getByTestId('pb-cmp-add-actor-character').locator('option')).toHaveText(['(unnamed — describe below)'], { timeout: 30000 });
        await page.getByTestId('pb-cmp-add-actor-submit').click();
        await expect(dlg).toContainText('Pick a character or describe the actor');
        await page.getByTestId('pb-cmp-add-actor-description').fill('a boy coiling rope');
        await page.getByTestId('pb-cmp-add-actor-role').selectOption('supporting');
        const planAdd = planWaiter(page);
        await page.getByTestId('pb-cmp-add-actor-submit').click();
        await expect(page.getByText('Actor added')).toBeVisible({ timeout: 60000 });
        const posts = writes.filter(w => w.method === 'POST' && /\/actor$/.test(w.path));
        expect(posts.length, JSON.stringify(writes)).toBe(1);
        expect(posts[0].body).toEqual({ description: 'a boy coiling rope', role: 'supporting' });
        await expect(page.getByTestId('pb-cmp-actors')).toContainText('Actors (2)');
        const b = await getBoard(sceneIds[0]);
        const boy = b.body.actors.find(x => x.description === 'a boy coiling rope');
        expect(boy, JSON.stringify(b.body.actors)).toBeTruthy();
        expect(boy.name, 'the server names the row').toBeTruthy();
        const row = page.getByTestId('pb-cmp-actor-' + boy.objectId);
        await expect(row).toContainText(boy.name);
        await expect(row.getByTestId('pb-cmp-actor-outfit-chip')).toHaveAttribute('data-source', 'none');
        await waitForPlan(page, 'after add actor', planAdd);

        // The confirm names an unnamed actor by its server-assigned name (no character name to show).
        const planDelete = planWaiter(page);
        await row.getByTestId('pb-cmp-actor-delete').click();
        await expect(page.getByText('Remove ' + boy.name + ' from the composition?')).toBeVisible();
        await dialogButton(page, 'Remove').click();
        await expect(page.getByText(/^Actor removed/)).toBeVisible({ timeout: 60000 });
        await expect(row).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-actors')).toContainText('Actors (1)');
        await waitForPlan(page, 'after delete actor', planDelete);
        const b2 = await getBoard(sceneIds[0]);
        expect(b2.body.actors.map(x => x.objectId)).toEqual([actorObjectId]);
        revision = b2.body.composition.revision;
        await shot(page, 'p49-board-actor-deleted');
    });
});

// ────────────────────────────── LLM tier ──────────────────────────────
// A MIGRATED composition is the server's no-LLM fallback (keyless config → "LLM unavailable"); under this flag
// the board must send the picked chat config and the result must be an LLM composition — anything else is red.
test.describe('empty-state compose and Re-compose against the live LLM (PB_LLM_TESTS=1)', () => {
    test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 not set — live LLM compose from the board skipped (a skip, not a pass)');

    let chatConfigName;

    test.beforeAll(async ({ request }) => {
        test.setTimeout(5 * 60 * 1000);
        chatConfigName = await ensureChatConfig(request, null, { configName: PICKER_CONFIG_NAME });
        expect(chatConfigName, 'chatConfig provisioned via resolveChatRoute()').toBe(PICKER_CONFIG_NAME);
        console.log(TAG + ' LLM tier chat config: ' + chatConfigName);
    });

    test('scene 2: "Compose this scene" with the picked chat config runs the synchronous single-scene compose against the LLM and fills the board', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[1]);
        await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'scene 2 empty open', planOpen);
        await pickChatConfig(page, chatConfigName);
        const planAfter = planWaiter(page);
        const t0 = Date.now();
        await page.getByTestId('pb-cmp-compose-scene').click();
        await expect(page.getByText(/^Scene (composed|was not composed)/)).toBeVisible({ timeout: 15 * 60 * 1000 });
        console.log(TAG + ' board compose of scene 2 in ' + (Date.now() - t0) + ' ms: ' + (await page.getByText(/^Scene (composed|was not composed)/).textContent()));
        const posts = writes.filter(w => w.method === 'POST' && /\/compose$/.test(w.path));
        expect(posts.length, JSON.stringify(writes)).toBe(1);
        expect(posts[0].path).toBe(bookObjectId + '/scene/' + sceneIds[1] + '/compose');
        expect(posts[0].body).toEqual({ chatConfigName });
        await expect(page.getByText(/^Scene composed/)).toBeVisible();
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-source')).toHaveText('llm');
        await waitForPlan(page, 'scene 2 after compose', planAfter);
        const b = await getBoard(sceneIds[1]);
        expect(b.status, JSON.stringify(b.body).slice(0, 300)).toBe(200);
        expect(lc(b.body.composition.source)).toBe('llm');
        expect(b.body.composition.userEdited).toBe(false);
        await expect(page.getByTestId('pb-cmp-revision')).toHaveText('rev ' + b.body.composition.revision);
        console.log(TAG + ' MEASURED scene 2 compose source=' + b.body.composition.source + ' actors=' + (b.body.actors || []).length + ' elements=' + (b.body.elements || []).length + ' revision=' + b.body.composition.revision);
        expect((b.body.actors || []).length + (b.body.elements || []).length, 'an LLM composition of a scene with a fishwife, a boy, boats and a basket has rows').toBeGreaterThan(0);
        for (const a of (b.body.actors || [])) await expect(page.getByTestId('pb-cmp-actor-' + a.objectId)).toBeVisible();
        for (const e of (b.body.elements || [])) await expect(page.getByTestId('pb-cmp-element-' + e.objectId)).toBeVisible();
        await shot(page, 'p49-board-scene2-composed');
    });

    test('scene 1: "Re-compose scene" on a hand-edited composition asks first, then POSTs {force:true, chatConfigName} and keeps the row', async ({ page }) => {
        test.setTimeout(20 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-user-edited')).toBeVisible({ timeout: 60000 });
        await expect(page.getByTestId('pb-cmp-recompose-warning')).toHaveText('Hand-edited: re-composing overwrites it');
        await waitForPlan(page, 'before re-compose', planOpen);
        await pickChatConfig(page, chatConfigName);
        const before = await readRevision();
        await page.getByTestId('pb-cmp-recompose').click();
        await expect(page.getByText(USER_EDITED_WARNING)).toBeVisible();
        await dialogButton(page, 'Cancel').click();
        await expect(page.locator('.am7-dialog-footer')).toHaveCount(0);
        expect(writes.filter(w => /\/compose$/.test(w.path))).toEqual([]);
        const planAfter = planWaiter(page);
        const t0 = Date.now();
        await page.getByTestId('pb-cmp-recompose').click();
        await expect(page.getByText(USER_EDITED_WARNING)).toBeVisible();
        await dialogButton(page, 'Re-compose').click();
        await expect(page.getByText(/^Scene (re-composed|was not re-composed)/)).toBeVisible({ timeout: 15 * 60 * 1000 });
        console.log(TAG + ' board re-compose of scene 1 in ' + (Date.now() - t0) + ' ms: ' + (await page.getByText(/^Scene (re-composed|was not re-composed)/).textContent()));
        const posts = writes.filter(w => w.method === 'POST' && /\/compose$/.test(w.path));
        expect(posts.length, JSON.stringify(writes)).toBe(1);
        expect(posts[0].body).toEqual({ force: true, chatConfigName });
        await expect(page.getByText(/^Scene re-composed/)).toBeVisible();
        await waitForPlan(page, 'after re-compose', planAfter);
        const b = await getBoard(sceneIds[0]);
        expect(b.body.compositionObjectId, 'the composition row is kept').toBe(compositionObjectId);
        expect(b.body.composition.revision).toBeGreaterThan(before);
        expect(b.body.composition.userEdited).toBe(false);
        expect(lc(b.body.composition.source)).toBe('llm');
        await expect(page.getByTestId('pb-cmp-user-edited')).toHaveCount(0);
        await expect(page.getByTestId('pb-cmp-source')).toHaveText('llm');
        console.log(TAG + ' MEASURED scene 1 re-compose source=' + b.body.composition.source + ' actors=' + (b.body.actors || []).length + ' elements=' + (b.body.elements || []).length + ' revision=' + b.body.composition.revision);
        await shot(page, 'p49-board-scene1-recomposed');
    });
});

// ────────────────────────────── SD tier ──────────────────────────────
test.describe('Compose book… with Quick start: the job runs on the page to a DRAFT per scene (PB_SD_TESTS=1)', () => {
    test.skip(!SD_ENABLED, 'PB_SD_TESTS=1 not set — live SD Quick start from the board skipped (a skip, not a pass)');
    test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 not set — Quick start needs the LLM tier (a skip, not a pass)');

    let chatConfigName;

    test.beforeAll(async ({ request }) => {
        test.setTimeout(5 * 60 * 1000);
        chatConfigName = await ensureChatConfig(request, null, { configName: PICKER_CONFIG_NAME });
        expect(chatConfigName, 'chatConfig provisioned via resolveChatRoute()').toBe(PICKER_CONFIG_NAME);
    });

    test('the compose dialog with Quick start + Re-compose in order posts {quickStart, force, chatConfigName}; the run is followed to terminal; every scene gets a draft', async ({ page }) => {
        test.setTimeout(60 * 60 * 1000);
        await loginAsSharedUser(page);
        const writes = recordWrites(page);
        const planOpen = planWaiter(page);
        await openBoard(page, sceneIds[0]);
        await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
        await waitForPlan(page, 'before compose book', planOpen);
        await pickChatConfig(page, chatConfigName);
        await page.getByTestId('pb-cmp-compose-book').click();
        const dlg = page.getByTestId('pb-cmp-compose-dialog');
        await expect(dlg).toBeVisible();
        await expect(page.getByTestId('pb-cmp-compose-chatconfig')).toHaveText('Chat config: ' + chatConfigName);
        await page.getByTestId('pb-cmp-quickstart').check();
        await expect(page.getByTestId('pb-cmp-force-warning')).toHaveCount(0);
        await page.getByTestId('pb-cmp-force').check();
        await expect(page.getByTestId('pb-cmp-force-warning')).toBeVisible();
        const t0 = Date.now();
        // The run panel does not expose the job id; the 202 {jobId} of the compose POST is the way to the job result.
        const composeResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/olio\/picture-book\/[^/]+\/compose$/.test(r.url()), { timeout: 60000 });
        await page.getByTestId('pb-cmp-compose-start').click();
        await expect.poll(() => writes.filter(w => w.method === 'POST' && /\/compose$/.test(w.path)).length, { timeout: 30000 }).toBe(1);
        expect(writes.filter(w => w.method === 'POST' && /\/compose$/.test(w.path))[0].body).toEqual({ quickStart: true, force: true, chatConfigName });
        const submitResp = await composeResp;
        expect(submitResp.status(), 'compose submit status').toBe(202);
        const submit = await submitResp.json();
        console.log(TAG + ' quick start submit: ' + JSON.stringify(submit));
        expect(submit.jobId, 'compose submit carries a jobId').toBeTruthy();
        const run = page.getByTestId('pb-cmp-compose-run');
        await expect(run).toBeVisible({ timeout: 60000 });
        await expect(page.getByText(/^Compose job started|^Joined the running compose job/)).toBeVisible({ timeout: 60000 });
        await expect(run).toHaveAttribute('data-run-terminal', 'true', { timeout: 58 * 60 * 1000 });
        console.log(TAG + ' quick start run terminal in ' + (Date.now() - t0) + ' ms: ' + (await page.getByTestId('pb-cmp-compose-counter').textContent()));
        // The counter AND the toast both carry the text — getByText would be a strict-mode violation (run p49-pw-sd).
        await expect(page.getByTestId('pb-cmp-compose-counter')).toHaveText(/^Done: composed/, { timeout: 60000 });
        for (const idx of [1, 2]) {
            const row = page.getByTestId('pb-cmp-compose-scene-' + idx);
            await expect(row).toBeVisible();
            console.log(TAG + ' scene ' + idx + ' row: ' + (await row.getAttribute('data-status')) + ' ' + (await row.textContent()));
            // Forced, with a key-bearing config: every scene is an LLM composition. A `migrated` row is the
            // server's no-LLM fallback and means the config did not reach the LLM — red, not a pass.
            expect(await row.getAttribute('data-status')).toBe('composed');
        }
        await shot(page, 'p49-board-quickstart-done');

        // The drafts are real images. A DRAFT is a render-node artifact, not the scene's approved image, so GET /pages
        // does not carry it (its `imageObjectId` stayed undefined in run p49-pw-sd2); the job result names each scene's
        // artifact — the device pictureBookCompose.spec.js uses. The job is terminal already, so one GET is enough.
        const job = await expectStatus(await api.get(REST + '/job/' + submit.jobId), 200, 'GET /job/' + submit.jobId);
        expect(job.terminal, 'job terminal: ' + JSON.stringify(job).slice(0, 400)).toBe(true);
        expect(job.status, 'job ended ' + job.status + ': ' + (job.error || '')).toBe('completed');
        const res = job.result;
        console.log(TAG + ' quick start job result: ' + JSON.stringify(res).slice(0, 2000));
        expect(res.quickStart).toBe(true);
        expect(res.hardFailures, JSON.stringify(res.scenes)).toBe(0);
        expect(res.migrated, 'forced with a key-bearing config: no MIGRATED fallback').toBe(0);
        expect(res.composed).toBe(2);
        expect(res.quickStartFailed, 'draft render failures: ' + JSON.stringify(res.scenes.map(s => s.draftRenderError))).toBe(0);
        expect(res.quickStartRendered, 'one DRAFT per composed scene').toBe(2);
        let n = 0;
        for (const s of res.scenes) {
            expect(s.draftRender && s.draftRender.artifactObjectId, 'scene ' + s.sceneIndex + ' draftRender: ' + JSON.stringify(s)).toBeTruthy();
            const art = await expectStatus(await api.get(PB + '/' + bookObjectId + '/artifact/' + s.draftRender.artifactObjectId), 200, 'GET artifact');
            expect(art.dataObjectId, 'artifact has bytes: ' + JSON.stringify(art)).toBeTruthy();
            expect(String(art.artifactType).toUpperCase()).toBe('IMAGE');
            await fetchImage(art.dataObjectId, 'p49-board-quickstart-scene' + s.sceneIndex);
            n++;
        }
        expect(n).toBe(2);
        await page.getByTestId('pb-cmp-compose-dismiss').click();
        await expect(run).toHaveCount(0);
    });
});
