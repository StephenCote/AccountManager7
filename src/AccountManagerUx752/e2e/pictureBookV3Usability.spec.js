/**
 * PictureBook V3 — P9-0 end-to-end usability gate (PictureBookV3Implementation.md §16).
 *
 * ONE serial walk, in ONE browser context, as the shared test user (never admin): one real book from
 * Stephen's content (media/Kiss of the Sun Queen.docx — a single-range manuscript, so the wizard takes the
 * single-document path rather than the hours-long per-chapter fan-out) is created by the walk itself and
 * LEFT IN PLACE in the resettable stack DB for inspection. No fixture short-cuts between steps: the book
 * the wizard made is the book the reader shows, the style editor locks, the board composes, the canvas
 * drags, the queue approves. What still runs over REST is fixture seeding the UI has no surface for (the
 * legacy PB1 book of step 1, the .docx uploads of steps 2 and 13), the read-backs, and step 6's Propose
 * fallback when the UI Propose yields nothing — each recorded in the step note so it lands in §6.C. The
 * step-13 ChapBook is made through the UI (Add from Data → picker → Set poem order → Import → Create
 * ChapBook → reader; P10-D 6). Step 15 (P9-6, §16 "Cover & Art") rides the P9-4 cover board and reader
 * hooks: Auto cover on the walk's book (LLM + the picked chat config) and a Manual cover with a saved camera on
 * the step-13 ChapBook, each rendered, approved, read back from GET /{book}/covers and shown by its reader;
 * the ChapBook export's cover slot carries the approved render.
 *
 * Every step is a test.step that (a) asserts a visible outcome in the DOM, (b) reads the same fact back
 * over REST (/rest/olio/picture-book/{book}/… or POST /rest/model/search with cache:false), (c) saves a
 * full-page screenshot to test-results/usability/NN-<step>.png for a human to look at, and (d) records its
 * elapsed time as an OBSERVATION — never a threshold. A step that cannot complete fails the gate (soft
 * assertion, so the walk continues and the later steps still report) and becomes a §6.C sub-item.
 *
 * Run (Docker stack, dist current via `npx vite build` + `docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/`):
 *   PB_SD_TESTS=1 PB_LLM_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3Usability.spec.js --workers=1 --project=chromium --retries=0
 *
 * The LLM tier is whatever resolveChatRoute() prints (`[e2e-llm] route=…`); the SD server is whatever the
 * stack's illustration connection points at (named in the step-4 note from GET /olio/illustration/models).
 */
import fs from 'fs';
import path from 'path';
import { gunzipSync } from 'zlib';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, resolveChatRoute, ensureChatConfig, ensurePath, createObject } from './helpers/api.js';
import { REST, PB_REST, restLoginShared, loginAsSharedUser } from './helpers/chapbook.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TAG = '[p90-gate]';
const OUT_DIR = path.resolve(__dirname, '../test-results/usability');
const MEDIA_DIR = path.resolve(__dirname, '../../AccountManagerObjects7/media');
const SOURCE_DOC = 'Kiss of the Sun Queen.docx';
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const CONFIG_NAME = '000-p90-gate-llm';       // '000-' sorts onto the ObjectPicker's first page
const CONN_NAME = '000-p90-gate-conn';
const FLUX2 = process.env.PB_FLUX2_MODEL || 'flux2Klein_9b';

// Ceilings exist so a dead watcher fails the step instead of hanging the gate; they are never pass/fail timings.
const T = {
    ui: 30000, extract: 2 * 900000 + 120000, characters: 1800000, plan: 900000, render: 1500000,
    compose: 900000, propose: 900000, locationRender: 1800000, chapRender: 1500000, job: 1800000
};

const stamp = Date.now().toString(36);
const results = [];          // {n, name, ok, ms, note}
const consoleErrors = [];    // observation only
const fx = {                 // what the walk made, for the report and for later steps
    user: null, route: null, chatConfig: null, legacy: null, importedBook: null, doc: null,
    book: null, scenes: [], scene0: null, cast: [], planNode: null, baseArtifacts: [], chapBook: null, sdUrl: null, sdInventory: null
};

function upper(v) { return v == null ? null : String(v).toUpperCase(); }
function slugify(s) { return String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''); }
function b64path(p) { return 'B64-' + Buffer.from(p).toString('base64').replace(/=/g, '%3D'); }
async function shot(page, name) {
    fs.mkdirSync(OUT_DIR, { recursive: true });
    const file = path.join(OUT_DIR, name + '.png');
    await page.screenshot({ path: file, fullPage: true });
    return file;
}
async function jsonOf(resp) { const t = await resp.text(); try { return JSON.parse(t); } catch (e) { return { _raw: t.slice(0, 600) }; } }

/** POST /rest/model/search, uncached, org-scoped. */
async function search(request, type, fields, projection, count) {
    const r = await request.post(REST + '/model/search', { data: {
        schema: 'io.query', type, cache: false, recordCount: count || 50,
        fields: fields.concat([{ name: 'organizationId', comparator: 'equals', value: fx.user.organizationId }]),
        request: projection } });
    const j = await jsonOf(r);
    return (j && j.results) || [];
}
async function pbGet(request, pathSuffix) {
    const r = await request.get(PB_REST + pathSuffix, { timeout: 300000 });
    return { status: r.status(), body: await jsonOf(r) };
}
async function pollJob(request, jobId, label, maxMs) {
    const t0 = Date.now(); let last = '';
    for (;;) {
        const r = await request.get(REST + '/job/' + jobId);
        const j = await jsonOf(r);
        const line = j.status + ' phase=' + j.phase + ' ' + j.current + '/' + j.total + ' elapsed=' + j.elapsed + 's';
        if (line !== last) { console.log(TAG + ' ' + label + ' job ' + jobId + ': ' + line); last = line; }
        if (j.terminal) return j;
        if (Date.now() - t0 > maxMs) throw new Error(label + ' job ' + jobId + ' not terminal after ' + maxMs + ' ms: ' + line);
        await new Promise(res => setTimeout(res, 5000));
    }
}
async function waitForToast(page, re, timeout) {
    const t = page.locator('.toast-text', { hasText: re });
    await expect(t).toBeVisible({ timeout: timeout || 15000 });
    return (await t.first().textContent()) || '';
}
/** Pick a chat config through the real ObjectPicker (pattern: pictureBookChapteredManuscriptUx.spec.js). */
async function pickFromOverlay(page, configName) {
    const overlay = page.locator('.am7-picker-overlay');
    await expect(overlay).toBeVisible({ timeout: 15000 });
    await expect(overlay.locator('h3')).toHaveText('Select Chat Config');
    const row = overlay.locator('tr.tabular-row', { hasText: configName });
    await expect(row, 'picker lists ~/Chat/' + configName).toBeVisible({ timeout: 20000 });
    // The picker's list control is shared and keeps its selection between opens; a row click TOGGLES
    // `checked` (views/list.js selectResult), so a second pick of the same config would unselect it (run 6 step 7).
    if (!/tabular-row-active/.test((await row.getAttribute('class')) || '')) await row.click();
    // Run 13 step 7: on the SECOND open of the picker in one session the clicked row never shows
    // tabular-row-active, although the selection state toggled and confirm applies the pick (probe
    // scratch/p9-0/probe-picker-memo.mjs: decorator.js TabularMemo keeps its memo key in a module-global
    // `_tabState`, so a fresh table whose first post-click key equals the previous instance's skips the
    // re-render). Soft: the step stays red on it, but the pick — and the compose it gates — still run.
    await expect.soft(row, 'picked row highlighted (decorator.js TabularMemo module-global _tabState)').toHaveClass(/tabular-row-active/, { timeout: 5000 });
    await overlay.locator('button:has(span:text-is("check"))').first().click();
    await expect(overlay).toHaveCount(0, { timeout: 10000 });
}
/** The reader header's page label ("Cover" / "Page n of N") plus what the cover/loading state shows right now. */
async function readerState(page) {
    const label = ((await page.locator('[data-pb2-book-name] + span').textContent({ timeout: 2000 }).catch(() => '(no header)')) || '').trim();
    const begin = await page.locator('button', { hasText: 'Begin' }).count();
    const loading = await page.getByText('Loading scenes...').count();
    const prevDisabled = await page.locator('button:has(span:text-is("chevron_left"))').first().isDisabled({ timeout: 2000 }).catch(() => 'n/a');
    return label + ' begin=' + begin + ' loading=' + loading + ' prevDisabled=' + prevDisabled;
}
// The reader keeps its current page (module state) across same-route visits, so "Begin" (the cover) is only
// there on the first visit — run 4 killed steps 4–8 by assuming it always is. Wait for the header, not the cover.
// Runs 11 + 12: a goto to the URL the reader is ALREADY on (step 6 ends on page 6 of the reader) is a same-document
// navigation — Playwright returns at once on the old DOM and Mithril re-initialises the reader onto the cover a beat
// later, undoing whatever openFirstPage decided on the old DOM (run 11: clicked a disabled chevron; run 12: five
// good clicks to page 1, then the cover). If the reader is already open, stay on it, as a user would.
async function openReader(page, bookOid) {
    const target = '/#!/picture-book/v2/' + bookOid;
    if (!page.url().endsWith(target)) await page.goto(target, { timeout: T.ui });
    await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: T.ui });
    await expect(page.locator('button', { hasText: 'Begin' }).or(page.getByText(/^Page \d+ of \d+$/).first())).toBeVisible({ timeout: T.ui });
}
// Land on page 1 from wherever the reader is: Begin from the cover, otherwise step back with the header's chevron_left.
// Run 11 step 7: a same-URL goto re-inits the reader a beat AFTER openReader's header check passed on the previous
// visit's "Page 6 of 10" — the reader flipped to Loading → cover (chevron_left disabled) and the one unconditional
// click timed out. Re-decide every turn; a click that cannot land in 5 s is not an error, just a stale decision.
// Run 13 step 6: sixteen Begin clicks inside one second on a fully loaded cover and "Page 1 of N" never
// appeared, while the same click in step 7 (30 s later, no reload between) worked at once; the probe
// (scratch/p9-0/probe-reader-begin.mjs) could not reproduce it. So: a user's pace (let a pending load clear,
// one click, a beat for Mithril), and a per-turn trail of what the reader showed, carried in the failure.
async function openFirstPage(page) {
    const page1 = page.getByText(/^Page 1 of \d+$/);
    const begin = page.locator('button', { hasText: 'Begin' });
    const prev = page.locator('button:has(span:text-is("chevron_left"))').first();
    const trail = [];
    const clickResult = (what, p) => p.then(() => what, e => what + ' ✗ ' + String((e && e.message) || e).split('\n')[0].slice(0, 90));
    for (let i = 0; i < 16 && !(await page1.count()); i++) {
        await expect(page.getByText('Loading scenes...')).toHaveCount(0, { timeout: 15000 }).catch(() => {});
        const before = await readerState(page);
        const did = (await begin.count())
            ? await clickResult('Begin', begin.first().click({ timeout: 5000 }))
            : await clickResult('chevron_left', prev.click({ timeout: 5000 }));
        await page.waitForTimeout(400);
        trail.push('#' + i + ' [' + before + '] ' + did + ' → [' + (await readerState(page)) + ']');
    }
    try { await expect(page1.first()).toBeVisible({ timeout: T.ui }); }
    catch (e) { throw new Error('openFirstPage: "Page 1 of N" never shown; now [' + (await readerState(page)) + ']; trail ' + trail.join(' ; ') + ' — ' + String((e && e.message) || e).split('\n')[0]); }
    return trail;
}

/**
 * The step wrapper: a throw inside fn marks the step red via expect.soft (the gate ends red) but the walk
 * continues; the step's screenshot is always taken; fn's return value is the step note for the report.
 */
async function step(page, n, name, fn) {
    const t0 = Date.now();
    let ok = true, note = '';
    const file = String(n).padStart(2, '0') + '-' + slugify(name);
    await test.step(n + '. ' + name, async () => {
        const softBefore = test.info().errors.length;
        try { note = (await fn()) || ''; }
        catch (e) {
            ok = false;
            note = 'FAILED: ' + String((e && e.message) || e).split('\n').filter(Boolean).slice(0, 6).join(' | ').slice(0, 900);
            expect.soft(false, 'step ' + n + ' ' + name + ' — ' + note).toBe(true);
        }
        // An expect.soft inside fn (pickFromOverlay's row highlight) does not throw: the step keeps collecting
        // its evidence, but it is still a red step — never reported OK.
        if (ok && test.info().errors.length > softBefore) {
            ok = false;
            const soft = test.info().errors.slice(softBefore).map(e => String(e.message || e).split('\n').filter(Boolean).slice(0, 3).join(' | ').slice(0, 300));
            note = 'FAILED (soft, step completed): ' + soft.join(' || ') + (note ? ' — ' + note : '');
        }
        try { await shot(page, file); } catch (e) { note += ' [screenshot failed: ' + (e && e.message) + ']'; }
        if (!ok) {
            // A step that died inside a dialog must not leave it over the page: run 5 lost steps 6–14 to one open
            // "Manage Characters" dialog (every later click resolved its button and then timed out behind the overlay).
            // Same for the object picker overlay (z-60, inset-0): run 6 step 7 left it open and steps 8–12 clicked into it.
            for (let i = 0; i < 2 && (await page.locator('.am7-picker-overlay').count()); i++) {
                await page.locator('.am7-picker-overlay button:has(span:text-is("close"))').first().click({ timeout: 5000 }).catch(() => {});
                await page.waitForTimeout(500);
            }
            for (let i = 0; i < 3 && (await page.locator('.am7-dialog, [role="dialog"]').count()); i++) {
                const close = page.locator('.am7-dialog button:has-text("Close"), [role="dialog"] button:has-text("Close"), .am7-dialog button:has(span:text-is("close"))').last();
                if (await close.count()) await close.click({ timeout: 5000 }).catch(() => {}); else await page.keyboard.press('Escape').catch(() => {});
                await page.waitForTimeout(500);
            }
            // The ChapBook's own "Set poem order" / "Create ChapBook" overlays (step 13) are plain fixed divs, not .am7-dialog.
            const cbDialog = page.locator('div.fixed.inset-0', { has: page.locator('h3:text-is("Create ChapBook"), h3:has-text("Set poem order")') });
            for (let i = 0; i < 2 && (await cbDialog.count()); i++) {
                await cbDialog.last().locator('button:has-text("Cancel"), button:has-text("Close"), button:has(span:text-is("close"))').last().click({ timeout: 5000 }).catch(() => {});
                await page.waitForTimeout(500);
            }
            if (await page.locator('.am7-dialog, [role="dialog"]').count()) note += ' [dialog still open after cleanup]';
        }
    });
    const ms = Date.now() - t0;
    results.push({ n, name, ok, ms, note, file: file + '.png' });
    console.log(TAG + ' step ' + n + ' ' + (ok ? 'OK  ' : 'FAIL') + ' ' + ms + ' ms — ' + name + (note ? ' — ' + note : ''));
    return ok;
}

test.describe('PictureBook V3 P9-0 — end-to-end usability gate (§16)', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });
    // actionTimeout: playwright.config.js sets none, so a locator action (click / fill / textContent / getAttribute) on an
    // element that never appears waits FOREVER — run 3 hung 75 min on `pb2-scene-chip`.getAttribute() because the chip is
    // null on a never-rendered scene. Every action is now bounded; the long waits all go through explicit expect()/waitFor()
    // timeouts (T.*), which this does not touch.
    test.use({ viewport: { width: 1600, height: 900 }, actionTimeout: T.ui });

    test.beforeAll(async ({ request }) => {
        test.setTimeout(10 * 60 * 1000);
        fs.mkdirSync(OUT_DIR, { recursive: true });
        const shared = await ensureSharedTestUser(request);
        expect(shared && shared.testUserName, 'shared test user').toBeTruthy();
        // resolveChatRoute() prints the authoritative `[e2e-llm] route=…` line itself (its return value is not
        // a plain object — JSON.stringify gives `{}` — so it is not echoed here; quote the printed line).
        fx.route = resolveChatRoute();
        // One chat config on the live tier, requestTimeout 900 (single-shot extraction of a whole manuscript).
        fx.chatConfig = await ensureChatConfig(request, null, { configName: CONFIG_NAME, connectionName: CONN_NAME, requestTimeout: 900 });
        expect(fx.chatConfig, 'chat config ' + CONFIG_NAME + ' provisioned on the resolved LLM tier').toBe(CONFIG_NAME);
        await restLoginShared(request);
        const chat = await (await request.get(REST + '/path/make/auth.group/data/' + b64path('~/Chat'))).json();
        fx.user = { name: shared.testUserName, organizationId: chat.organizationId };
        expect(fx.user.organizationId, 'organizationId from ~/Chat').toBeTruthy();
        // The SD server this stack renders on is named in the report from the container's web.xml `sd.server`
        // (the inventory DTO carries no URL); the gate only confirms the inventory answers.
        fx.sdUrl = process.env.SD_SERVER || '(container web.xml sd.server — see report)';
        const inv = await request.get(REST + '/olio/illustration/models?task=sceneDirect', { timeout: 300000 });
        const invBody = await jsonOf(inv);
        const invSummary = { status: inv.status(), count: invBody.inventory && invBody.inventory.count, error: invBody.inventory && invBody.inventory.error, resolution: invBody.resolution, deploymentDefault: invBody.deploymentDefault };
        fx.sdInventory = invSummary;
        console.log(TAG + ' SD inventory (sceneDirect): ' + JSON.stringify(invSummary).slice(0, 600));
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        console.log('\n' + TAG + ' ===== per-step table (timings are observations) =====');
        for (const r of results) console.log(TAG + ' | ' + String(r.n).padStart(2) + ' | ' + (r.ok ? 'OK  ' : 'FAIL') + ' | ' + String(r.ms).padStart(8) + ' ms | ' + r.name + ' | ' + r.file + ' | ' + r.note);
        console.log(TAG + ' left in place: ' + JSON.stringify({ book: fx.book, importedBook: fx.importedBook, legacy: fx.legacy, legacy2: fx.legacy2, chapBook: fx.chapBook, doc: fx.doc, chatConfig: fx.chatConfig }));
        console.log(TAG + ' browser console errors (observation): ' + consoleErrors.length + (consoleErrors.length ? '\n  ' + consoleErrors.slice(0, 40).join('\n  ') : ''));
        try { fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify({ results, fx, consoleErrors, route: fx.route }, null, 2)); } catch (e) { /* report only */ }
    });

    test('one user journey, 15 steps', async ({ page, request }) => {
        test.setTimeout(6 * 60 * 60 * 1000);
        test.skip(!SD_ENABLED || !LLM_ENABLED, 'PB_SD_TESTS=1 and PB_LLM_TESTS=1 are both required: the gate renders on the SD server and extracts on the LLM');
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text().slice(0, 300)); });
        page.on('pageerror', err => consoleErrors.push('pageerror: ' + String(err && err.message).slice(0, 300)));
        await loginAsSharedUser(page);
        await restLoginShared(request);

        // ── 1. Book list + pre-V3 import ─────────────────────────────────────────────────────────────
        // A pure PB1 fixture (the shape PictureBookUtil wrote): group + Scenes/Images + one scene note + meta note.
        const makeLegacyBook = async (legacyName) => {
            const legacyGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName);
            const scenesGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName + '/Scenes');
            const imagesGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName + '/Images');
            expect(legacyGroup && legacyGroup.objectId && scenesGroup.id && imagesGroup.id, 'legacy groups').toBeTruthy();
            const imgPath = path.resolve(__dirname, '../public/media/femaleSilhouette.png');
            const img = await createObject(request, 'data.data', { groupId: imagesGroup.id, groupPath: imagesGroup.path, name: 'scene-1-' + stamp + '.png', contentType: 'image/png', dataBytesStore: fs.readFileSync(imgPath).toString('base64') });
            expect(img && img.objectId, 'fixture PNG').toBeTruthy();
            const postNote = async (dir, name, text) => jsonOf(await request.post(REST + '/model', { data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name, text } }));
            const sceneNote = await postNote(scenesGroup, 'Scene 1', JSON.stringify({ index: 0, title: 'The Keeper Rows Out', blurb: 'The keeper pushes the skiff off the shingle.', setting: 'shingle beach', action: 'launching a skiff', mood: 'resolute', characters: [], imageObjectId: img.objectId, landscapeObjectId: img.objectId }));
            expect(sceneNote && sceneNote.objectId, 'scene note').toBeTruthy();
            const metaNote = await postNote(legacyGroup, '.pictureBookMeta', JSON.stringify({ schema: 'olio.pictureBookMeta', bookObjectId: legacyGroup.objectId, workName: legacyName, sceneCount: 1,
                scenes: [{ schema: 'olio.pictureBookScene', objectId: sceneNote.objectId, index: 0, title: 'The Keeper Rows Out', description: 'The keeper pushes the skiff off the shingle.', setting: 'shingle beach', action: 'launching a skiff', mood: 'resolute', characters: [] }] }));
            expect(metaNote && metaNote.objectId, 'meta note').toBeTruthy();
            return { name: legacyName, group: legacyGroup.objectId };
        };

        await step(page, 1, 'Book list and PB1 import', async () => {
            const legacyName = 'p90-legacy-' + stamp;
            fx.legacy = await makeLegacyBook(legacyName);
            const legacyGroup = { objectId: fx.legacy.group };
            const pre = await request.get(PB_REST + '/' + legacyGroup.objectId + '/pb2');
            expect(pre.status(), 'no V3 book for the legacy group before import').toBe(404);

            // The list page. A user with no story book yet has no list section (pictureBook.js renders
            // [data-pb2-book-list] only when pb2Books.length > 0): the landing page is Health + "Select a
            // document". Runs 1–20 always found leftovers from earlier runs; the 263 c cleanup removed them,
            // so the fresh-user shape is the one to expect — the list is asserted after the import below.
            await page.goto('/#!/picture-book', { timeout: T.ui });
            await expect(page.locator('[data-pb-health-check]')).toBeVisible({ timeout: T.ui });
            // The list loads after the page paints ("Loading PB2 books..." while pb2Loading); count only
            // once that is gone, or a leftover book from the previous run is reported as "absent" (run 22).
            await expect(page.getByText('Loading PB2 books...')).toHaveCount(0, { timeout: T.ui });
            const listBefore = await page.locator('[data-pb2-book-list]').count();
            const rowsBefore = await page.locator('[data-pb2-book]').count();
            await page.locator('[data-pb-health-check]').click();
            await expect(page.locator('[data-pb-health-clean], [data-pb-health-findings]').first()).toBeVisible({ timeout: 120000 });
            const healthClean = await page.locator('[data-pb-health-clean]').count();
            const findingCodes = await page.locator('[data-pb-health-finding]').evaluateAll(els => els.map(e => e.getAttribute('data-pb-health-finding') + ':' + e.getAttribute('data-pb-health-severity')));
            await shot(page, '01a-book-list');

            // The pre-V3 book: legacy notice → Import into V3 → result dialog → V3 reader.
            await page.evaluate(h => { location.hash = h; }, '!/picture-book/' + legacyGroup.objectId);
            const notice = page.locator('[data-pb1-legacy-notice]');
            await expect(notice).toBeVisible({ timeout: T.ui });
            await expect(notice).toContainText('replaced by V3');
            const migrate = page.waitForResponse(r => r.request().method() === 'POST' && /\/olio\/picture-book\/migrate-v1$/.test(r.url()), { timeout: 120000 });
            await notice.locator('[data-pb1-import]').click();
            const migrateResp = await migrate;
            const migrateBody = await jsonOf(migrateResp);
            expect(migrateResp.status(), 'POST /migrate-v1: ' + JSON.stringify(migrateBody).slice(0, 300)).toBe(200);
            fx.importedBook = migrateBody.bookObjectId;
            await expect.poll(() => page.evaluate(() => location.hash), { timeout: T.ui }).toBe('#!/picture-book/v2/' + fx.importedBook);
            const dlg = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Imported into V3' }) });
            await expect(dlg).toBeVisible({ timeout: T.ui });
            await expect(dlg.locator('[data-pb1-import-result]')).toContainText('1 scene imported');
            await shot(page, '01b-import-result');
            await dlg.locator('[data-pb1-import-close]').click();
            await expect(page.locator('.am7-dialog')).toHaveCount(0);
            await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: T.ui });
            await expect(page.locator('[data-pb2-cover-count]')).toContainText('of 1 scene', { timeout: T.ui });
            const bannerCount = await page.locator('[data-pb2-health-banner]').count();

            // Read-backs: the imported book's health, and the list shows the new row.
            const health = await pbGet(request, '/' + fx.importedBook + '/health');
            expect(health.status, 'GET /{imported}/health').toBe(200);
            await page.goto('/#!/picture-book', { timeout: T.ui });
            await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: T.ui });
            await expect(page.locator('[data-pb2-book="' + fx.importedBook + '"]')).toBeVisible({ timeout: T.ui });
            const rowsAfter = await page.locator('[data-pb2-book]').count();
            return 'list section before import ' + (listBefore ? 'shown' : 'absent (no story book yet)') + '; rows before/after import ' + rowsBefore + '/' + rowsAfter + '; org health ' + (healthClean ? 'clean' : 'findings ' + JSON.stringify(findingCodes)) + '; imported ' + fx.importedBook + ' slug=' + migrateBody.slug
                + ' scenesImported=' + migrateBody.scenesImported + ' landscapes=' + migrateBody.landscapesImported + ' warnings=' + (migrateBody.warnings || []).length
                + '; reader health banner count ' + bannerCount + '; GET /health errors=' + (health.body.errors != null ? health.body.errors : JSON.stringify(health.body).slice(0, 120));
        });

        // ── 2. Wizard: Source → Scenes → Characters → reader ─────────────────────────────────────────
        await step(page, 2, 'Wizard from the manuscript', async () => {
            const docPath = path.join(MEDIA_DIR, SOURCE_DOC);
            expect(fs.existsSync(docPath), 'source document ' + docPath).toBe(true);
            const dir = await ensurePath(request, 'auth.group', 'data', '~/Manuscripts');
            const docName = 'p90-' + stamp + '-' + SOURCE_DOC;
            const doc = await jsonOf(await request.post(REST + '/model', { data: { schema: 'data.data', name: docName, groupId: dir.id, groupPath: dir.path, contentType: DOCX_MIME, dataBytesStore: fs.readFileSync(docPath).toString('base64') } }));
            expect(doc && doc.objectId, 'uploaded manuscript').toBeTruthy();
            fx.doc = { objectId: doc.objectId, name: docName };
            const jobPolls = [];
            const onReq = req => { if (/\/rest\/job\//.test(req.url())) jobPolls.push(req.url().replace(/^.*\/rest\/job\//, '')); };
            page.on('request', onReq);

            await page.goto('/#!/picture-book/' + doc.objectId, { timeout: T.ui });
            const generateBtn = page.locator('button:has-text("Generate Picture Book")');
            await expect(generateBtn).toBeVisible({ timeout: T.ui });
            await generateBtn.click();
            await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: T.ui });
            const dialog = page.locator('[role="dialog"]').first();
            await expect(dialog).toContainText('Source: ' + docName, { timeout: T.ui });
            // Chat config: the stack's default resolves to a library config; pick the gate's own on the live tier.
            const cfgField = dialog.locator('label.field-label:has-text("Chat Config") + div');
            await expect(cfgField).not.toContainText('Loading default...', { timeout: T.ui });
            await cfgField.click();
            await pickFromOverlay(page, CONFIG_NAME);
            await expect(cfgField).toContainText(CONFIG_NAME);
            await shot(page, '02a-wizard-source');

            const extractBtn = page.locator('button:has-text("Extract")').first();
            await expect(extractBtn).toBeEnabled({ timeout: 10000 });
            const tExtract = Date.now();
            await extractBtn.click();
            const sceneList = page.locator('text=/Scene List \\(\\d+\\)/');
            const extractErr = page.locator('text=/Extraction stopped at chapter|No chapters were saved|Extraction was cancelled|No scenes were extracted from any chapter|Extraction failed/');
            await Promise.race([sceneList.first().waitFor({ state: 'visible', timeout: T.extract }), extractErr.first().waitFor({ state: 'visible', timeout: T.extract })]);
            if (await extractErr.count()) throw new Error('extraction reported: ' + (await extractErr.first().textContent()));
            let heading = await sceneList.first().textContent();
            let sceneCount = parseInt((heading.match(/\((\d+)\)/) || [])[1] || '0', 10);
            let extractMs = Date.now() - tExtract;
            expect(sceneCount, 'scenes extracted').toBeGreaterThan(0);
            // A scene with no description is not a scene. Run 7: the model dropped the outer [ ] of its reply,
            // parseLlmJsonArray latched onto scene 0's nested "characters":[…] and the wizard showed one
            // blank "scene" (name/role of Biju Rao) — the spec continued and the book was built from it.
            // Run 15: the same latch (the reply opened with `{` where the `[` belonged → "Scene List (1)",
            // blank), then the Re-extract came back as ten real scenes (blurb, setting, action, mood,
            // characters, diffusionPrompt) every one with "title":"" — the server's NON_EMPTY mapper drops
            // the empty string, so the wizard showed ten untitled scenes and the spec's "titled = real"
            // test failed a legitimate list. An untitled scene IS a scene: the title field is there to be
            // typed into, and create-from-scenes names a missing title "Scene N" (PictureBookUtil :7134).
            // Do what a user does: Re-extract once when there is no scene with a description, then insist
            // on described scenes; the titled count is reported, not asserted.
            const titleInputs = dialog.locator('input[placeholder="Scene title"]');
            const blurbInputs = dialog.locator('textarea[placeholder="Scene description/blurb"]');
            const titledScenes = async () => (await titleInputs.evaluateAll(els => els.map(e => e.value.trim()))).filter(Boolean);
            const describedScenes = async () => (await blurbInputs.evaluateAll(els => els.map(e => e.value.trim()))).filter(Boolean);
            let titles = await titledScenes();
            let blurbs = await describedScenes();
            let reExtractNote = '';
            if (!blurbs.length) {
                await shot(page, '02b0-wizard-scenes-degenerate');
                const tRe = Date.now();
                // Run 10: "Extracting..." only appears on Mithril's next redraw, so a bare toHaveCount(0) passed at once
                // and the step read the stale list while the second job (56 s on .42) was still running. Wait for the
                // POST the button sends (…/extract-scenes-only?async=true → 202), then for the label to clear.
                const reResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/extract-scenes-only(\?|$)/.test(r.url()), { timeout: 120000 });
                await dialog.locator('button:has-text("Re-extract")').click();
                const reStatus = (await reResp).status();
                await expect(dialog.locator('button:has-text("Extracting...")')).toHaveCount(0, { timeout: T.extract });
                reExtractNote = ' (POST ' + reStatus + ')';
                if (await extractErr.count()) throw new Error('re-extraction reported: ' + (await extractErr.first().textContent()));
                heading = await sceneList.first().textContent();
                sceneCount = parseInt((heading.match(/\((\d+)\)/) || [])[1] || '0', 10);
                titles = await titledScenes();
                blurbs = await describedScenes();
                reExtractNote = '; first extraction yielded no described scene → Re-extract' + reExtractNote + ' ' + Math.round((Date.now() - tRe) / 1000) + ' s → ' + sceneCount + ' scene(s)';
                extractMs = Date.now() - tExtract;
            }
            await shot(page, '02b-wizard-scenes');
            expect(blurbs.length, 'scenes with a description (of ' + sceneCount + ')' + reExtractNote).toBeGreaterThan(0);

            // Continue → POST /{work}/create-from-scenes: characters, scenes, then a whole-book pb.compose job
            // (PbServiceFacade.createFromScenesAndCompose → composeJob {jobId…}). That job keeps the book's
            // compose claim for minutes after the wizard returns and 409s /locations/propose (run 8 step 6).
            const cfsResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/create-from-scenes$/.test(r.url()), { timeout: T.characters }).then(jsonOf).catch(() => null);
            await page.locator('button:has-text("Continue")').first().click();
            const tChars = Date.now();
            const dlg = page.getByRole('dialog');
            const empty = dlg.locator('text=No characters extracted yet.');
            const card = dlg.locator('div.font-medium.text-sm').first();
            await Promise.race([empty.waitFor({ state: 'visible', timeout: T.characters }), card.waitFor({ state: 'visible', timeout: T.characters })]).catch(() => {});
            const charCount = await dlg.locator('div.font-medium.text-sm').count();
            const charsMs = Date.now() - tChars;
            await shot(page, '02c-wizard-characters');
            expect(charCount, 'characters created (empty state: ' + (await empty.count()) + ')').toBeGreaterThan(0);

            await page.locator('button:has-text("Continue to Images")').click();
            await expect.poll(() => page.evaluate(() => location.hash), { timeout: 60000 }).toMatch(/^#!\/picture-book\/v2\/[^/]+$/);
            const bookOid = (await page.evaluate(() => location.hash)).replace(/^#!\/picture-book\/v2\//, '');
            await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: T.ui });
            await expect(page.locator('text=Picture Book —')).toHaveCount(0);
            page.off('request', onReq);

            // Read-backs through the PB REST surface. A PB2 book is NOT addressable via the generic
            // /rest/model/search for the shared user (PictureBookService "REST addressability" comment;
            // chapBookReader.spec.js:49-52) — run 1 of this gate went red here on exactly that. And
            // GET /{book}/workflow 404s ("This book has no workflow yet - generate a scene first",
            // PbServiceFacade.requireWorkflow) until the first generation — run 2 went red on THAT.
            // So the book facts come from the workflow-independent routes: /health (bookObjectId + slug),
            // the reader header for the name, /pages for the scenes, /characters for the cast.
            const health = await pbGet(request, '/' + bookOid + '/health');
            expect(health.status, 'GET /health for ' + bookOid).toBe(200);
            expect(health.body && health.body.bookObjectId, 'health report names the book').toBe(bookOid);
            const headerName = ((await page.locator('[data-pb2-book-name]').textContent()) || '').trim();
            fx.book = { objectId: bookOid, name: headerName, slug: health.body.slug, healthScope: health.body.scope, healthFindings: (health.body.findings || []).length };
            const cfs = await cfsResp;
            const cj = cfs && cfs.composeJob;
            fx.composeJob = cj && cj.jobId ? { jobId: cj.jobId, status: cj.jobStatus, scenes: cj.scenesToCompose } : null;
            const composeNote = fx.composeJob ? 'compose job ' + fx.composeJob.jobId + ' ' + fx.composeJob.status + ' (' + fx.composeJob.scenes + ' scenes)'
                : 'compose job ' + (cfs ? (cfs.composeJobSkipped ? 'skipped: ' + cfs.composeJobSkipped : cfs.composeJobError ? 'error: ' + JSON.stringify(cfs.composeJobError) : 'absent from the response') : 'response not captured');
            const pages = await pbGet(request, '/' + bookOid + '/pages');
            expect(pages.status, 'GET /pages').toBe(200);
            fx.scenes = (Array.isArray(pages.body) ? pages.body : []).map(p => ({ objectId: p.objectId, title: p.title, sceneIndex: p.sceneIndex, setting: p.setting }));
            fx.scene0 = fx.scenes[0] || null;
            expect(fx.scenes.length, 'pages read back = scenes extracted').toBe(sceneCount);
            // P10-B (2026-11-04): create-from-scenes builds the scene graph, so a fresh wizard book has its
            // workflow — one node per scene — before anything is rendered. The earlier "200 or the documented
            // 404" tolerance here hid the UAT blocker ("(404) This book has no workflow yet" on Render).
            const wf = await pbGet(request, '/' + bookOid + '/workflow');
            expect(wf.status, 'GET /workflow on a fresh wizard book (graph built at create): ' + JSON.stringify(wf.body).slice(0, 160)).toBe(200);
            const wfNodes = (wf.body && wf.body.nodes) || [];
            expect(wfNodes.length, 'one workflow node per extracted scene at create').toBe(sceneCount);
            fx.book.workflowAtCreate = wf.status + ' nodes=' + wfNodes.length;
            const chars = await pbGet(request, '/' + bookOid + '/characters');
            expect(chars.status, 'GET /characters').toBe(200);
            const cast = Array.isArray(chars.body) ? chars.body : [];
            fx.cast = cast.map(c => ({ objectId: c.objectId, name: c.name }));
            return 'doc=' + doc.objectId + '; extraction ' + Math.round(extractMs / 1000) + ' s → ' + sceneCount + ' scenes (' + blurbs.length + ' described, ' + titles.length + ' titled: ' + titles.join(' | ').slice(0, 160) + ')' + reExtractNote + '; characters ' + Math.round(charsMs / 1000) + ' s → ' + charCount + ' shown / ' + cast.length + ' via /characters (' + cast.map(c => c.name).join(', ').slice(0, 200) + ')'
                + '; job polls ' + jobPolls.length + '; book ' + bookOid + ' "' + fx.book.name + '" slug=' + fx.book.slug + ' health scope=' + fx.book.healthScope + ' findings=' + fx.book.healthFindings + '; ' + composeNote + '; GET /workflow at create → ' + fx.book.workflowAtCreate;
        });
        if (!fx.book) {
            // Nothing downstream can run without the book: record every remaining step as unreachable, keep the report shape.
            for (const [n, name] of [[3, 'Reader shell'], [4, 'Style'], [5, 'Characters'], [6, 'Locations'], [7, 'Composition board'], [8, 'Workflow canvas'], [9, 'Variant strip and retention'], [10, 'QA'], [11, 'Review queue'], [12, 'Reader after approval'], [13, 'ChapBook'], [14, 'Export'], [15, 'Cover and Art']]) {
                await step(page, n, name, async () => { throw new Error('unreachable: the wizard made no book'); });
            }
            return;
        }

        // ── 3. Reader shell ──────────────────────────────────────────────────────────────────────────
        await step(page, 3, 'Reader shell', async () => {
            await openReader(page, fx.book.objectId);
            const nameEl = page.locator('[data-pb2-book-name]');
            await expect(nameEl).toBeVisible({ timeout: T.ui });
            const headerName = (await nameEl.textContent()) || '';
            const cover = page.locator('[data-pb2-cover-count]');
            await expect(cover).toContainText('of ' + fx.scenes.length + ' scene');
            const coverText = (await cover.textContent()) || '';
            const chapterSelect = await page.locator('[data-pb2-chapter-select]').count();
            for (const id of ['pb2-style-link', 'pb2-locations-link', 'pb2-compose-link', 'pb2-review-link', 'pb2-render-all']) await expect(page.getByTestId(id), id).toBeVisible();
            await expect(page.locator('button[title="Open Workflow Canvas"]')).toBeVisible();
            await expect(page.locator('[data-pb2-characters="' + fx.book.objectId + '"]')).toBeVisible();
            await openFirstPage(page);
            await expect(page.locator('h2', { hasText: fx.scene0.title || 'Scene' }).first()).toBeVisible({ timeout: T.ui });
            // 213 e / 219 f: no scene has an image yet; the reader shows the placeholder, not pb2-scene-image.
            const imgCount = await page.getByTestId('pb2-scene-image').count();
            // renderPb2SceneChip returns null on a scene with nothing to say — count first, never wait on it (run-3 hang).
            const chip = (await page.getByTestId('pb2-scene-chip').count()) ? await page.getByTestId('pb2-scene-chip').first().getAttribute('data-chip') : '(no chip)';
            await page.locator('button:has(span:text-is("chevron_right"))').first().click();
            if (fx.scenes.length > 1) await expect(page.getByText(/^Page 2 of \d+$/).first()).toBeVisible({ timeout: T.ui });
            await page.locator('button:has(span:text-is("chevron_left"))').first().click();
            await expect(page.getByText(/^Page 1 of \d+$/).first()).toBeVisible({ timeout: T.ui });
            // Still pre-generation, but the graph exists since create (P10-B): /workflow is 200 with the scene nodes.
            const wf = await pbGet(request, '/' + fx.book.objectId + '/workflow');
            expect(wf.status, 'GET /workflow before any generation (graph built at create)').toBe(200);
            const wfNodes = (wf.body && wf.body.nodes) || [];
            return 'header "' + headerName.trim() + '" (slug ' + fx.book.slug + '); cover "' + coverText.trim() + '"; chapter select count ' + chapterSelect + '; page 1 scene chip=' + chip + ' pb2-scene-image count ' + imgCount
                + ' (213 e: RENDER_PLAN base is never the page image today); GET /workflow ' + wf.status + ' nodes ' + wfNodes.length + ' types ' + JSON.stringify([...new Set(wfNodes.map(n => upper(n.nodeType)))]);
        });

        // ── 4. Style ─────────────────────────────────────────────────────────────────────────────────
        await step(page, 4, 'Style', async () => {
            await openReader(page, fx.book.objectId);
            await page.getByTestId('pb2-style-link').click();
            await expect(page).toHaveURL(new RegExp('#!/picture-book/' + fx.book.objectId + '/style$'));
            await expect(page.getByTestId('pb2-style')).toBeVisible({ timeout: T.ui });
            await expect(page.getByTestId('pb2-style-chain')).toBeVisible({ timeout: T.ui });
            const t0 = Date.now();
            await expect(page.getByTestId('pb2-style-task-availability-sceneDirect')).not.toContainText('checking', { timeout: 300000 });
            const availability = ((await page.getByTestId('pb2-style-task-availability-sceneDirect').textContent()) || '').trim();
            const invMs = Date.now() - t0;
            const hadOwn = await page.getByTestId('pb2-style-own-name').count();
            const photoreal = page.getByTestId('pb2-style-photoreal');
            const photorealBefore = await photoreal.isChecked();
            const cycle = await page.getByTestId('pb2-style-cycle').count();
            await page.getByTestId('pb2-style-description').fill('storybook watercolour, warm light, P9-0 gate ' + stamp);
            await page.getByTestId('pb2-style-qualityPreset').selectOption('STANDARD');
            for (const task of ['sceneDirect', 'location', 'portrait']) await page.getByTestId('pb2-style-taskModel-' + task).fill(FLUX2);
            await expect(page.getByTestId('pb2-style-save')).toBeEnabled();
            await page.getByTestId('pb2-style-save').click();
            await waitForToast(page, 'Style saved');
            await expect(page.getByTestId('pb2-style-own-name')).toBeVisible({ timeout: T.ui });
            const ownName = (await page.getByTestId('pb2-style-own-name').textContent()) || '';
            // Own render settings: create → seed edit → save.
            await page.getByTestId('pb2-style-sd-create').click();
            await waitForToast(page, 'Render settings created');
            await expect(page.getByTestId('pb2-style-sd-own')).toBeVisible({ timeout: T.ui });
            const seed = page.getByTestId('pb2-style-sd').locator('div:has(> label:text-is("Seed"))').locator('input[type="number"]');
            await seed.fill('9090');
            await expect(page.getByTestId('pb2-style-sd-save')).toBeEnabled();
            await page.getByTestId('pb2-style-sd-save').click();
            await waitForToast(page, 'Render settings saved');
            await shot(page, '04a-style-saved');
            // Lock → banner → read-back → unlock (the walk keeps editing later).
            await page.getByTestId('pb2-style-lock').click();
            await waitForToast(page, 'Style locked');
            await expect(page.getByTestId('pb2-style-locked-banner')).toBeVisible();
            await expect(page.getByTestId('pb2-style-description')).toBeDisabled();
            await shot(page, '04b-style-locked');
            const locked = await pbGet(request, '/' + fx.book.objectId + '/style');
            expect(locked.status, 'GET /style').toBe(200);
            expect(locked.body.own && locked.body.own.locked, 'own.locked on read-back').toBe(true);
            expect(upper(locked.body.effective.qualityPreset)).toBe('STANDARD');
            expect(locked.body.effective.taskModels && locked.body.effective.taskModels.sceneDirect).toBe(FLUX2);
            const sdOid = locked.body.own.fields.sdConfig;
            const sd = await search(request, 'olio.sd.config', [{ name: 'objectId', comparator: 'equals', value: sdOid }], ['id', 'objectId', 'name', 'seed', 'model', 'steps']);
            await page.getByTestId('pb2-style-lock').click();
            await waitForToast(page, 'Style unlocked');
            await expect(page.getByTestId('pb2-style-locked-banner')).toHaveCount(0);
            const unlocked = await pbGet(request, '/' + fx.book.objectId + '/style');
            expect(unlocked.body.own.locked).toBe(false);
            return 'inventory answered in ' + invMs + ' ms: "' + availability + '"; own style before=' + hadOwn + ' → ' + ownName.trim() + '; photoreal checkbox ' + photorealBefore + '; cycle hint count ' + cycle
                + '; own sdConfig ' + sdOid + ' seed=' + (sd[0] && sd[0].seed) + ' model=' + (sd[0] && sd[0].model) + '; locked→' + locked.body.own.locked + ' unlocked→' + unlocked.body.own.locked + '; effective.sceneDirect=' + locked.body.effective.taskModels.sceneDirect;
        });

        // ── 5. Characters ────────────────────────────────────────────────────────────────────────────
        await step(page, 5, 'Characters', async () => {
            await openReader(page, fx.book.objectId);
            await page.locator('[data-pb2-characters="' + fx.book.objectId + '"]').click();
            await expect(page.locator('.am7-dialog-title', { hasText: 'Manage Characters' })).toBeVisible({ timeout: T.ui });
            const items = page.locator('[data-char-item]');
            await expect(items.first()).toBeVisible({ timeout: 60000 });
            const itemCount = await items.count();
            const firstOid = await items.first().getAttribute('data-char-item');
            await items.first().click();
            await expect(page.locator('[data-char-name]')).toBeVisible({ timeout: T.ui });
            const charName = ((await page.locator('[data-char-name]').textContent()) || '').trim();
            // Affected-by panel: on a never-rendered book the manager calls /workflow and shows the raw 404
            // ("This book has no workflow yet - generate a scene first", run 5) instead of a count — record whichever appears.
            const affected = page.getByTestId('pbchar-affected-count');
            const affectedErr = page.getByTestId('pbchar-affected-error');
            await Promise.race([affected.waitFor({ state: 'visible', timeout: 120000 }), affectedErr.waitFor({ state: 'visible', timeout: 120000 })]);
            const affectedCount = (await affected.count()) ? await affected.getAttribute('data-count') : null;
            const affectedError = (await affectedErr.count()) ? ((await affectedErr.textContent()) || '').trim() : null;
            const regen = page.getByTestId('pbchar-affected-regenerate');
            const regenDisabled = (await regen.count()) ? await regen.isDisabled() : '(absent)';
            const regenTitle = (await regen.count()) ? await regen.getAttribute('title') : '(absent)';
            const portraitImgBefore = await page.locator('.am7-dialog img').count();
            await shot(page, '05a-character-detail');
            // The one portrait Ux element the manager has today is the legacy "Regenerate Portrait" → Reimage dialog →
            // Generate → POST /olio/olio.charPerson/{oid}/reimage on .39; the first image becomes profile.portrait.
            const legacyPortraitBtn = page.locator('.am7-dialog button', { hasText: 'Regenerate Portrait' });
            await expect(legacyPortraitBtn, 'legacy Regenerate Portrait button').toBeVisible();
            await legacyPortraitBtn.click();
            await expect(page.locator('.am7-dialog-title', { hasText: 'Reimage ' })).toBeVisible({ timeout: 60000 });
            await shot(page, '05b-reimage-dialog');
            const tPortrait = Date.now();
            const reimageResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/olio\/olio\.charPerson\/[^/]+\/reimage$/.test(r.url()), { timeout: T.render });
            await page.locator('.am7-dialog button', { hasText: 'Generate' }).last().click();
            const portraitToast = await waitForToast(page, /Created \d+ image\(s\)|Reimage error|Reimage failed|Cannot generate images|Failed to create image/, T.render);
            const ri = await reimageResp;
            const riBody = await jsonOf(ri);
            const portraitMs = Date.now() - tPortrait;
            await shot(page, '05c-portrait-result');
            expect(portraitToast, 'portrait render toast').toMatch(/^Created \d+ image\(s\)/);
            // The success path opens the image gallery over the manager — close it, re-select the character, see the portrait.
            while ((await page.locator('.am7-dialog').count()) > 1) {
                await page.locator('button[aria-label="Close dialog"]').last().click({ timeout: 5000 }).catch(() => page.keyboard.press('Escape'));
                await page.waitForTimeout(300);
            }
            await items.first().click();
            await expect(page.locator('[data-char-name]')).toBeVisible({ timeout: T.ui });
            await expect(page.locator('.am7-dialog img').first()).toBeVisible({ timeout: 60000 });
            const portraitImg = await page.locator('.am7-dialog img').count();
            await shot(page, '05d-character-with-portrait');
            // P10-D 3 (§6.C 267 i): the manager re-reads /characters from reimage's onImages callback, after the
            // profile patch, so the row's `no portrait` badge is gone before any other write (run 24 still read
            // "Alekhyano portrait" here and after the description save).
            await expect(items.first(), 'list row after portrait render').not.toContainText('no portrait', { timeout: T.ui });
            const charsAfter = await pbGet(request, '/' + fx.book.objectId + '/characters');
            const me = (Array.isArray(charsAfter.body) ? charsAfter.body : []).find(c => c.objectId === firstOid) || {};
            expect(me.hasPortrait, '/characters hasPortrait for ' + firstOid).toBe(true);
            fx.portrait = { charOid: firstOid, imageOid: riBody && riBody.objectId, ms: portraitMs };
            // Edit the description → Save → the manager re-reads the affected-by panel.
            await page.locator('[data-char-edit]').click();
            await expect(page.locator('[data-char-identity-editor]')).toBeVisible();
            const descInput = page.locator('[data-char-description-input]');
            const descBefore = await descInput.inputValue();
            const marker = ' [P9-0 gate ' + stamp + ']';
            await descInput.fill((descBefore || '').slice(0, 400) + marker);
            await page.locator('[data-char-save]').click();
            await waitForToast(page, 'Character saved');
            await expect(page.locator('[data-char-identity-editor]')).toHaveCount(0, { timeout: T.ui });
            // Same by-id route the manager itself reads through (am7client.getFull → /rest/model/{type}/{oid}/full).
            const personResp = await request.get(REST + '/model/olio.charPerson/' + firstOid + '/full');
            const person = await jsonOf(personResp);
            expect(personResp.status(), 'GET /model/olio.charPerson/{oid}/full').toBe(200);
            expect(person && person.description && person.description.indexOf(marker) >= 0, 'description persisted: ' + JSON.stringify({ name: person && person.name, description: person && person.description }).slice(0, 300)).toBe(true);
            // /affected-by rides requireWorkflow too: 404 until the first scene generation (run 6) — record, don't assert 200.
            const ab = await pbGet(request, '/' + fx.book.objectId + '/affected-by/olio.charPerson/' + firstOid);
            expect([200, 404], 'GET /affected-by: ' + JSON.stringify(ab.body).slice(0, 200)).toContain(ab.status);
            const listPortraitLabel = ((await items.first().textContent()) || '').replace(/\s+/g, ' ').trim();
            await page.locator('button[aria-label="Close dialog"]').last().click();
            return itemCount + ' characters; first "' + charName + '" (' + firstOid + ') affected panel: ' + (affectedError ? 'error "' + affectedError + '"' : 'count ' + affectedCount)
                + ' (REST /affected-by ' + ab.status + (ab.status === 200 ? ' boundNodes=' + ((ab.body.boundNodes || []).length) + ' affectedSceneIndexes=' + JSON.stringify(ab.body.affectedSceneIndexes || []) : ' ' + JSON.stringify(ab.body).slice(0, 80)) + ')'
                + '; Regenerate disabled=' + regenDisabled + ' title="' + regenTitle + '"; legacy Regenerate Portrait → "' + portraitToast.trim() + '" in ' + Math.round(portraitMs / 1000) + ' s, POST /reimage ' + ri.status() + ' image ' + (riBody && riBody.objectId) + ', dialog <img> ' + portraitImgBefore + ' → ' + portraitImg + ', /characters hasPortrait=' + me.hasPortrait + ', list row after render "' + listPortraitLabel + '"'
                + ' — no V3 portrait prompt→render→variant→approve chain in the manager today (§16 step 5 gap); description edit read back OK';
        });

        // ── 6. Locations ─────────────────────────────────────────────────────────────────────────────
        await step(page, 6, 'Locations', async () => {
            // The wizard's pb.compose job holds the book's compose claim; /locations/propose is refused
            // (409 "Your pb.compose job … is still running for this book") until it is terminal. A user sees
            // only that toast — the Locations page has no "compose still running" affordance. Wait it out
            // first (observation, never a threshold) and keep the job's own result.
            let composeNote = 'no compose job captured at create';
            if (fx.composeJob) {
                const tc = Date.now();
                const cjob = await pollJob(request, fx.composeJob.jobId, 'wizard compose', T.job);
                const cr = cjob.result || {};
                composeNote = 'wizard compose job ' + cjob.status + ' after a further ' + Math.round((Date.now() - tc) / 1000) + ' s (' + cjob.elapsed + ' s since submit): composed ' + cr.composed + ' current ' + cr.current + ' failed ' + cr.failed
                    + ' locationPass ' + JSON.stringify(cr.locationPass || null).slice(0, 120);
            }
            await openReader(page, fx.book.objectId);
            await page.getByTestId('pb2-locations-link').click();
            await expect(page.getByTestId('pb-loc-count')).toBeVisible({ timeout: T.ui });
            const countBefore = ((await page.getByTestId('pb-loc-count').textContent()) || '').trim();
            // The real button first, with whatever the header's "Chat Config" resolved to on its own (267: the
            // library default, contentAnalysis first, sent by NAME — the server resolves the name per user). Until
            // 267 the button sent no name and rode the server's generalChat, which on this stack is unreachable.
            // Outcome = the POST body + status + whichever toast propose() emitted (success / failed / cancelled / thrown).
            const cfgCtl = page.getByTestId('pb-loc-chatconfig');
            await expect(cfgCtl).toBeVisible({ timeout: T.ui });
            await expect(cfgCtl).not.toContainText('Resolving default', { timeout: T.ui });
            const cfgShown = ((await cfgCtl.textContent()) || '').replace(/\s*search\s*$/, '').trim();
            const t0 = Date.now();
            const proposeBtn = page.getByTestId('pb-loc-propose');
            const proposeResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/locations\/propose$/.test(r.url()), { timeout: 120000 });
            await proposeBtn.click();
            const pr = await proposeResp;
            const prBody = await jsonOf(pr);
            const prSent = pr.request().postData() || '{}';
            // The button carries an icon ligature + label ("auto_awesome Propose from scenes" / "progress_activity Proposing…"):
            // exact toHaveText never matches (run 6 burned its 900 s ceiling on that) — wait for the busy label to clear.
            await expect(proposeBtn).not.toContainText('Proposing', { timeout: T.propose });
            await expect(proposeBtn).toBeEnabled({ timeout: T.ui });
            const toastText = (await page.locator('.toast-text').allTextContents()).join(' || ') || '(toast already dismissed)';
            const uiProposeMs = Date.now() - t0;
            await shot(page, '06a-locations-propose-ui');
            let note = 'UI Propose (header chat config "' + cfgShown + '", sent ' + prSent.slice(0, 80) + ') → POST ' + pr.status() + ' ' + JSON.stringify(prBody).slice(0, 120) + ' → "' + toastText.trim().slice(0, 240) + '" in ' + Math.round(uiProposeMs / 1000) + ' s';
            let proposals = await page.getByTestId('pb-loc-proposal').count();
            if (!proposals) {
                // Fallback (recorded as a partial short-cut): the same route with the gate's chat config on the live tier.
                note += '; UI Propose produced no proposal — REST short-cut taken';
                const r = await request.post(PB_REST + '/' + fx.book.objectId + '/locations/propose', { data: { chatConfigName: CONFIG_NAME } });
                const sub = await jsonOf(r);
                expect(r.status(), 'POST /locations/propose {chatConfigName}: ' + JSON.stringify(sub).slice(0, 300)).toBe(202);
                const job = await pollJob(request, sub.jobId, 'propose', T.propose);
                expect(job.status, 'propose job: ' + JSON.stringify(job).slice(0, 400)).toBe('completed');
                note += '; REST propose with ' + CONFIG_NAME + ' → proposed=' + job.result.proposed + ' scenesConsidered=' + job.result.scenesConsidered + ' hardFailure=' + job.result.hardFailure + ' in ' + job.elapsed + ' s';
                await page.getByTestId('pb-loc-refresh').click();
                await page.getByTestId('pb-loc-proposal-filter').selectOption('PROPOSED').catch(() => {});
                proposals = await page.getByTestId('pb-loc-proposal').count();
                if (!proposals) { await page.reload(); await expect(page.getByTestId('pb-loc-count')).toBeVisible({ timeout: T.ui }); await page.getByTestId('pb-loc-proposal-filter').selectOption('PROPOSED').catch(() => {}); proposals = await page.getByTestId('pb-loc-proposal').count(); }
            }
            expect(proposals, 'at least one proposal to accept').toBeGreaterThan(0);
            const first = page.getByTestId('pb-loc-proposal').first();
            const propName = ((await first.getByTestId('pb-loc-proposal-name').textContent()) || '').trim();
            await first.getByTestId('pb-loc-proposal-accept').click();
            const acceptToast = await waitForToast(page, /^Accepted /, 60000);
            const card = page.getByTestId('pb-loc-card').filter({ has: page.getByTestId('pb-loc-name').filter({ hasText: propName }) }).first();
            await expect(card).toBeVisible({ timeout: T.ui });
            const usedBy = ((await card.getByTestId('pb-loc-used-by').textContent().catch(() => '')) || '').trim();
            // A variant (dusk) → render it on the SD server → approve the artifact.
            await card.getByTestId('pb-loc-add-variant').click();
            await expect(page.getByTestId('pb-loc-variant-form')).toBeVisible({ timeout: T.ui });
            await page.getByTestId('pb-loc-variant-name').fill('dusk');
            await page.getByTestId('pb-loc-variant-timeOfDay').fill('dusk');
            await page.getByTestId('pb-loc-variant-create').click();
            await waitForToast(page, /^Added variant/);
            const variant = card.getByTestId('pb-loc-variant').first();
            await expect(variant).toBeVisible({ timeout: T.ui });
            const variantId = await variant.getAttribute('data-variant-id');
            const t1 = Date.now();
            await variant.getByTestId('pb-loc-variant-render').click();
            const run = variant.getByTestId('pb-loc-run');        // renderRun(v.objectId) renders INSIDE the variant element
            await expect(run).toBeVisible({ timeout: 60000 });
            await expect(run).toHaveAttribute('data-run-terminal', '1', { timeout: T.locationRender });
            const runSummary = ((await run.getByTestId('pb-loc-run-counter').textContent()) || '').trim();
            const renderMs = Date.now() - t1;
            await shot(page, '06b-location-variant-rendered');
            expect(runSummary, 'variant render run').toMatch(/^Render complete/);
            // artifactActions() offers Approve only while the artifact is not APPROVED — a server auto-approve
            // (approvedBy=AUTO) leaves no button, only the 'auto-approved' mark; record which one the walk saw.
            const approveBtn = variant.getByTestId('pb-loc-approve').first();
            let approveNote;
            if (await approveBtn.count()) {
                await approveBtn.click();
                approveNote = 'approved via pb-loc-approve → "' + (await waitForToast(page, 'Approved')).trim() + '"';
            } else {
                await expect(variant.getByText('auto-approved'), 'no Approve button and no auto-approved mark on the variant chain').toBeVisible({ timeout: T.ui });
                approveNote = 'artifact arrived auto-approved (no Approve button offered)';
            }
            // Read-backs.
            const locs = await pbGet(request, '/' + fx.book.objectId + '/locations');
            expect(locs.status, 'GET /locations').toBe(200);
            const list = Array.isArray(locs.body) ? locs.body : (locs.body.locations || []);
            const loc = list.find(l => l.name === propName);
            expect(loc, 'accepted location in GET /locations: ' + JSON.stringify(list).slice(0, 300)).toBeTruthy();
            const detail = await pbGet(request, '/' + fx.book.objectId + '/location/' + loc.objectId);
            const variants = (detail.body.variants || []);
            const v = variants.find(x => x.objectId === variantId) || variants[0];
            const chain = (v && v.node && v.node.chain) || [];
            const approved = chain.filter(a => upper(a.reviewStatus) === 'APPROVED');
            // The reader shows the location chip on a page that points at it.
            await openReader(page, fx.book.objectId);
            await openFirstPage(page);
            let chipPages = 0;
            for (let i = 0; i < Math.min(fx.scenes.length, 6); i++) {
                if (await page.getByTestId('pb2-page-location').count()) chipPages++;
                if (i < fx.scenes.length - 1) await page.locator('button:has(span:text-is("chevron_right"))').first().click();
            }
            return composeNote + '; ' + note + '; count before "' + countBefore + '"; accepted "' + propName + '" → "' + acceptToast.trim().slice(0, 160) + '"; used-by "' + usedBy + '"; variant ' + variantId + ' rendered in ' + Math.round(renderMs / 1000) + ' s ("' + runSummary + '"); ' + approveNote + '; chain ' + chain.length + ' approved ' + approved.length
                + '; pb2-page-location chips on ' + chipPages + ' of the first ' + Math.min(fx.scenes.length, 6) + ' pages';
        });

        // ── 7. Composition board ─────────────────────────────────────────────────────────────────────
        await step(page, 7, 'Composition board', async () => {
            await openReader(page, fx.book.objectId);
            await openFirstPage(page);
            // renderPb2SceneChip returns null on a scene with nothing to say — count first, never wait on it (run-3 hang).
            const chip = (await page.getByTestId('pb2-scene-chip').count()) ? await page.getByTestId('pb2-scene-chip').first().getAttribute('data-chip') : '(no chip)';
            const degr = page.getByTestId('pb2-scene-degradations');
            const degrCount = (await degr.count()) ? await degr.first().getAttribute('data-count') : 'absent';
            await page.getByTestId('pb2-scene-compose').click();
            await expect(page.getByTestId('pb-cmp')).toBeVisible({ timeout: 60000 });
            const emptyBefore = await page.getByTestId('pb-cmp-empty').count();
            const ctl = page.getByTestId('pb-cmp-chatconfig');
            await expect(ctl).toBeVisible({ timeout: T.ui });
            await expect(ctl).not.toContainText('Resolving default…', { timeout: T.ui });
            const defaultCfg = ((await ctl.textContent()) || '').replace(/\s+/g, ' ').trim();
            await ctl.click();
            await pickFromOverlay(page, CONFIG_NAME);
            await expect(ctl).toContainText(CONFIG_NAME);
            // The wizard's pb.compose job has usually composed this scene already (run 8: "rev 2", no empty
            // state), so the board offers pb-cmp-recompose, not the empty-state pb-cmp-compose-scene. Use
            // whichever is there; either way the LLM composes the scene now with the gate's chat config.
            const revBeforeCompose = (await page.getByTestId('pb-cmp-revision').count()) ? ((await page.getByTestId('pb-cmp-revision').textContent()) || '').trim() : '(none)';
            const composeCtl = emptyBefore ? 'pb-cmp-compose-scene' : 'pb-cmp-recompose';
            const t0 = Date.now();
            await page.getByTestId(composeCtl).click();
            const outcome = page.locator('.toast-text', { hasText: /^Scene (was not )?(re-)?composed/ });
            await expect(outcome.first()).toBeVisible({ timeout: T.compose });
            const outcomeText = ((await outcome.first().textContent()) || '').trim();
            const composeMs = Date.now() - t0;
            expect(outcomeText, 'compose outcome via ' + composeCtl).toMatch(/^Scene (re-)?composed/);
            await expect(page.getByTestId('pb-cmp-camera')).toBeVisible({ timeout: 60000 });
            const actors = await page.locator('li[data-actor-objectid]').count();
            const boxes = await page.locator('[data-testid^="pb-cmp-box-actor-"]').count();
            // P10-D 1 (§6.C 267 i): no placement box or actor row is labelled by the raw actor.<comp>.<n> record name.
            const boxLabels = await page.locator('[data-testid^="pb-cmp-box-actor-"]').allTextContents();
            const rowHeads = await page.locator('li[data-actor-objectid] span.font-medium').allTextContents();
            for (const t of boxLabels.concat(rowHeads)) {
                expect(t.trim(), 'board label is never the system record name: ' + JSON.stringify(boxLabels) + ' ' + JSON.stringify(rowHeads)).not.toMatch(/^actor\.[0-9a-f-]+\.\d+$/);
            }
            await shot(page, '07a-board-composed');
            // Camera / mood edit → save → revision bump.
            const revBefore = ((await page.getByTestId('pb-cmp-revision').textContent()) || '').trim();
            await page.getByTestId('pb-cmp-mood').fill('wistful, late light (P9-0)');
            await page.getByTestId('pb-cmp-camera-text').fill('low three-quarter view, soft rim light');
            await page.getByTestId('pb-cmp-save-camera').click();
            await waitForToast(page, 'Composition saved');
            const revAfter = ((await page.getByTestId('pb-cmp-revision').textContent()) || '').trim();
            const dto = await pbGet(request, '/' + fx.book.objectId + '/scene/' + fx.scene0.objectId + '/composition');
            expect(dto.status, 'GET /composition').toBe(200);
            expect(upper(dto.body.composition.purpose)).toBe('SCENE');
            expect(dto.body.composition.userEdited).toBe(true);
            expect(dto.body.composition.mood).toBe('wistful, late light (P9-0)');
            // Back on the reader: render this scene directly (the §16 step-7 control) and observe what the server says.
            await openReader(page, fx.book.objectId);
            await openFirstPage(page);
            // renderScene() (workflows/pictureBookWorkflow.js:413-417) posts …/render?force=true — match the path with or
            // without a query string (run 9: a `$`-anchored regex never matched and the 409 answered at 21:59:28 went unseen).
            const renderResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/scene\/[^/]+\/render(\?|$)/.test(r.url()), { timeout: 120000 });
            await page.getByTestId('pb2-scene-render').click();
            const rr = await renderResp;
            const rrBody = await jsonOf(rr);
            let renderNote = 'pb2-scene-render → HTTP ' + rr.status() + ' ' + JSON.stringify(rrBody).slice(0, 200);
            // P10-B: Render on a wizard-made book is accepted (202) — never the "no workflow yet" 404 (UAT 2026-11-03).
            expect(rr.status(), 'POST /scene/{id}/render on the wizard book: ' + renderNote).toBe(202);
            if (rr.status() === 202 || rr.status() === 200) {
                const progress = page.getByTestId('pb2-run-progress');
                await expect(progress).toBeVisible({ timeout: 60000 });
                const t1 = Date.now();
                // The watcher survives navigate-away/back (re-attached from server state).
                await page.goto('/#!/picture-book', { timeout: T.ui });
                await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: T.ui });
                await openReader(page, fx.book.objectId);
                const reattached = await page.getByTestId('pb2-run-progress').isVisible().catch(() => false);
                const resumeBanner = await page.getByTestId('pb2-resume-banner').count();
                await expect(page.getByTestId('pb2-run-progress')).toHaveAttribute('data-run-terminal', '1', { timeout: T.render });
                const counter = ((await page.getByTestId('pb2-run-counter').textContent()) || '').trim();
                renderNote += '; run re-attached after navigate away/back=' + reattached + ' resume banner=' + resumeBanner + '; terminal in ' + Math.round((Date.now() - t1) / 1000) + ' s: "' + counter + '"';
                await page.getByTestId('pb2-run-dismiss').click().catch(() => {});
            } else {
                const toast = await page.locator('.toast-text').last().textContent().catch(() => '');
                renderNote += '; toast "' + String(toast).trim().slice(0, 200) + '"';
            }
            return 'page-1 chip=' + chip + ' degradations=' + degrCount + '; board empty before=' + emptyBefore + ' (' + revBeforeCompose + ') default cfg "' + defaultCfg + '"; ' + composeCtl + ' → "' + outcomeText + '" in ' + Math.round(composeMs / 1000) + ' s; actors ' + actors + ' boxes ' + boxes
                + '; ' + revBefore + ' → ' + revAfter + ' (REST revision ' + dto.body.composition.revision + ' source=' + dto.body.composition.source + ' userEdited=' + dto.body.composition.userEdited + '); ' + renderNote;
        });

        // ── 8. Workflow canvas (Render all, Plan & generate, node card, plan inspector, drag = 212 p) ──
        await step(page, 8, 'Workflow canvas', async () => {
            await openReader(page, fx.book.objectId);
            // Render all → dialog → Start: observe the server's answer on this wizard-made book.
            await page.getByTestId('pb2-render-all').click();
            await expect(page.getByTestId('pb2-render-dialog')).toBeVisible({ timeout: T.ui });
            await shot(page, '08a-render-all-dialog');
            const raResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/render-all$/.test(r.url()), { timeout: 120000 });
            await page.getByTestId('pb2-render-all-start').click();
            const ra = await raResp;
            const raBody = await jsonOf(ra);
            let raNote = 'Render all → HTTP ' + ra.status() + ' ' + JSON.stringify(raBody).slice(0, 220);
            // P10-B: Render all on a wizard-made book is accepted (202) and reuses the nodes built at create.
            expect(ra.status(), 'POST /render-all on the wizard book: ' + raNote).toBe(202);
            if (ra.status() === 202 || ra.status() === 200) {
                await expect(page.getByTestId('pb2-run-progress')).toBeVisible({ timeout: 60000 });
                const stages = await page.getByTestId('pb2-run-stage').count();
                await page.getByTestId('pb2-run-cancel').click();
                const cancelToast = await waitForToast(page, /Cancel requested|Cancel failed/);
                await expect(page.getByTestId('pb2-run-progress')).toHaveAttribute('data-run-terminal', '1', { timeout: T.render });
                raNote += '; stages ' + stages + '; "' + cancelToast.trim() + '"; terminal "' + ((await page.getByTestId('pb2-run-counter').textContent()) || '').trim() + '"';
                await page.getByTestId('pb2-run-dismiss').click().catch(() => {});
            } else {
                raNote += '; toast "' + String(await page.locator('.toast-text').last().textContent().catch(() => '')).trim().slice(0, 200) + '"';
                await page.getByTestId('pb2-render-dialog-cancel').click().catch(() => {});
                await page.keyboard.press('Escape').catch(() => {});
            }
            // Plan & generate on page 1 twice (STANDARD = 3 variants + QA per attempt) — the RENDER_PLAN node and its base variants.
            await openReader(page, fx.book.objectId);
            await openFirstPage(page);
            const gens = [];
            for (let attempt = 1; attempt <= 2; attempt++) {
                await expect(page.getByTestId('pb2-plan-render')).toBeVisible({ timeout: T.ui });
                const t0 = Date.now();
                await page.getByTestId('pb2-plan-render').click();
                await expect(page.getByTestId('pb2-plan-dialog')).toBeVisible({ timeout: T.ui });
                await expect(page.getByTestId('pb2-plan-loading')).toHaveCount(0, { timeout: T.plan });
                const planMs = Date.now() - t0;
                if (await page.getByTestId('pb2-plan-error').count()) throw new Error('plan error: ' + (await page.getByTestId('pb2-plan-error').textContent()));
                const strategy = ((await page.getByTestId('pb2-plan-dialog-strategy').textContent()) || '').trim();
                const estimate = ((await page.getByTestId('pb2-plan-dialog-estimate').textContent()) || '').trim();
                if (attempt === 1) {
                    await page.getByTestId('pb2-plan-inspect-toggle').click();
                    await expect(page.getByTestId('pb2-plan-inspector')).toBeVisible({ timeout: T.ui });
                    await shot(page, '08b-plan-dialog-inspector');
                }
                const t1 = Date.now();
                await page.getByTestId('pb2-plan-generate').click();
                await expect(page.getByTestId('pb2-plan-rendering')).toBeVisible({ timeout: T.ui });
                // Success closes the dialog, then toasts; failure keeps the dialog open and shows pb2-plan-error
                // (no toast). Race the dialog going away against the error — not the toast: attempt 1's
                // "Scene rendered" toast is still on screen when attempt 2 starts (run 8 went red on that).
                const planDialog = page.getByTestId('pb2-plan-dialog');
                const planErr = page.getByTestId('pb2-plan-error');
                await Promise.race([planDialog.waitFor({ state: 'detached', timeout: T.render }), planErr.waitFor({ state: 'visible', timeout: T.render })]);
                const errText = (await planErr.count()) ? ((await planErr.textContent()) || '').trim() : '';
                const okToast = page.locator('.toast-text', { hasText: /^Scene rendered/ });
                if (!errText) await expect(okToast.last()).toBeVisible({ timeout: T.ui });
                const toastText = errText ? 'pb2-plan-error: ' + errText : ((await okToast.last().textContent()) || '').trim();
                gens.push({ attempt, planMs, renderMs: Date.now() - t1, strategy, estimate, toast: toastText });
                expect(toastText, 'generate attempt ' + attempt).toMatch(/^Scene rendered/);
                await expect(planDialog).toHaveCount(0, { timeout: T.ui });
            }
            const imgAfter = await page.getByTestId('pb2-scene-image').count();
            const coverAfter = ((await page.locator('[data-pb2-cover-count]').textContent().catch(() => '')) || '').trim();
            await shot(page, '08c-reader-after-generate');
            const wf = await pbGet(request, '/' + fx.book.objectId + '/workflow');
            expect(wf.status, 'GET /workflow after the first generation (the workflow now exists)').toBe(200);
            const node = (wf.body.nodes || []).find(n => upper(n.nodeType) === 'RENDER_PLAN' && n.scopeRef === fx.scene0.objectId);
            expect(node, 'RENDER_PLAN node for scene 1: ' + JSON.stringify((wf.body.nodes || []).map(n => [n.nodeType, n.scopeRef])).slice(0, 300)).toBeTruthy();
            fx.planNode = { objectId: node.objectId, canvasX: node.canvasX, canvasY: node.canvasY };
            const nd = await pbGet(request, '/' + fx.book.objectId + '/workflow/node/' + node.objectId);
            const arts = nd.body.artifacts || {};
            fx.baseArtifacts = (arts.base || []).map(a => ({ objectId: a.objectId, attempt: a.attempt, variantIndex: a.variantIndex, seed: a.seed, reviewStatus: a.reviewStatus, selected: a.selected, qa: a.qa ? a.qa.result : null }));

            // The canvas: node card → detail → plan inspector → DRAG (212 p: no "Save position failed", position reads back).
            await page.goto('/#!/picture-book/' + fx.book.objectId + '/workflow', { timeout: T.ui });
            const card = page.locator('[data-node-id="' + node.objectId + '"]');
            await expect(card).toBeVisible({ timeout: 60000 });
            await card.click({ position: { x: 14, y: 12 } });
            const toggle = page.getByTestId('pbwf-node-plan-toggle');
            await expect(toggle).toBeVisible({ timeout: T.ui });
            await toggle.click();
            await expect(toggle).toHaveText('Hide plan', { timeout: T.ui });
            await expect(page.getByTestId('pbwf-node-plan').getByTestId('pb2-plan-inspector')).toBeVisible();
            const stageCount = await page.locator('[data-testid="pb2-plan-stage"]').count();
            const qaCount = await page.getByTestId('pb2-plan-qa').first().getAttribute('data-count').catch(() => 'absent');
            await shot(page, '08d-canvas-node-plan');
            const box = await card.boundingBox();
            const savePos = page.waitForResponse(r => r.request().method() === 'PUT' && /\/canvas$/.test(r.url()), { timeout: 60000 });
            await page.mouse.move(box.x + 14, box.y + 12);
            await page.mouse.down();
            await page.mouse.move(box.x + 14 + 60, box.y + 12 + 40, { steps: 6 });
            await page.mouse.move(box.x + 14 + 140, box.y + 12 + 90, { steps: 8 });
            await page.mouse.up();
            const sp = await savePos;
            const spBody = await jsonOf(sp);
            const sentBody = JSON.parse(sp.request().postData() || '{}');
            expect(sp.status(), 'PUT /canvas: ' + JSON.stringify(spBody).slice(0, 200)).toBe(200);
            await page.waitForTimeout(1500);
            const failToast = await page.locator('.toast-text', { hasText: 'Save position failed' }).count();
            expect(failToast, 'no "Save position failed" toast (212 p)').toBe(0);
            const wf2 = await pbGet(request, '/' + fx.book.objectId + '/workflow');
            const node2 = (wf2.body.nodes || []).find(n => n.objectId === node.objectId);
            expect(node2.canvasX, 'canvasX read back = sent').toBe(sentBody.x);
            expect(node2.canvasY, 'canvasY read back = sent').toBe(sentBody.y);
            return raNote + '; plan/generate ' + JSON.stringify(gens.map(g => ({ a: g.attempt, planS: Math.round(g.planMs / 1000), renderS: Math.round(g.renderMs / 1000), strategy: g.strategy.slice(0, 60), est: g.estimate.slice(0, 60) })))
                + '; reader after generate: pb2-scene-image ' + imgAfter + ' cover "' + coverAfter + '" (213 e gap, recorded once); node ' + node.objectId + ' base artifacts ' + fx.baseArtifacts.length + ' qa ' + fx.baseArtifacts.filter(a => a.qa).length
                + '; inspector stages ' + stageCount + ' qa data-count ' + qaCount + '; drag: sent {x:' + sentBody.x + ',y:' + sentBody.y + ',w:' + sentBody.w + ',h:' + sentBody.h + '} read back {' + node2.canvasX + ',' + node2.canvasY + '} from {' + node.canvasX + ',' + node.canvasY + '}, fail toasts ' + failToast;
        });

        // ── 9. Variant strip + compare + retention ───────────────────────────────────────────────────
        await step(page, 9, 'Variant strip and retention', async () => {
            expect(fx.planNode, 'RENDER_PLAN node from step 8').toBeTruthy();
            await page.goto('/#!/picture-book/' + fx.book.objectId + '/workflow', { timeout: T.ui });
            const card = page.locator('[data-node-id="' + fx.planNode.objectId + '"]');
            await expect(card).toBeVisible({ timeout: 60000 });
            await card.click({ position: { x: 14, y: 12 } });
            const history = page.getByTestId('pbwf-art-history');
            await expect(history).toBeVisible({ timeout: T.ui });
            const roleBase = history.locator('[data-testid="pbwf-art-role"][data-role="base"]');
            await expect(roleBase).toBeVisible({ timeout: T.ui });
            const attempts = await roleBase.getByTestId('pbwf-art-attempt').count();
            const tiles = roleBase.getByTestId('pbwf-art-tile');
            const tileCount = await tiles.count();
            const variantBadges = await roleBase.getByTestId('pbwf-art-variant-badge').count();
            const seedBadges = await roleBase.getByTestId('pbwf-art-seed-badge').count();
            const qaBadges = await roleBase.getByTestId('pbwf-art-qa-badge').count();
            expect(tileCount, 'base tiles').toBeGreaterThan(0);
            await tiles.nth(0).click();
            if (tileCount > 1) await tiles.nth(1).click();
            await expect(page.getByTestId('pbwf-art-compare')).toBeVisible({ timeout: T.ui });
            const compareItems = await page.getByTestId('pbwf-art-compare-item').count();
            const compareQa = await page.getByTestId('pbwf-art-compare-qa').count();
            await shot(page, '09a-variant-strip-compare');
            await page.getByTestId('pbwf-art-compare-clear').click();
            await expect(page.getByTestId('pbwf-art-compare')).toHaveCount(0);
            // Run 9: the node detail panel is position:fixed z-index:200 (pictureBookWorkflow.js:1044-1046) and the
            // modal backdrop is z-index 40 (dialogCore.js getZIndex), so with a node selected the panel covers the
            // Retention dialog's footer — Scan/Apply sit under it and the click waits 30 s. Same for a user: close the
            // inspector (its × has no test id) before opening Retention; recorded as a product finding.
            await page.locator('button', { hasText: /^×$/ }).first().click();
            await expect(history).toHaveCount(0, { timeout: T.ui });
            // Retention K=1 archives the older attempt's unselected, unapproved variants.
            await page.getByTestId('pbwf-retention-open').click();
            await expect(page.getByTestId('pbwf-retention-dialog')).toBeVisible({ timeout: T.ui });
            await page.getByTestId('pbwf-retention-keep').fill('1');
            await page.getByTestId('pbwf-retention-days').fill('0');
            const dialogBox = await page.locator('.am7-dialog').first().boundingBox();
            await page.getByTestId('pbwf-retention-scan').click();
            await expect(page.getByTestId('pbwf-retention-summary')).toBeVisible({ timeout: 120000 });
            const summary = ((await page.getByTestId('pbwf-retention-summary').textContent()) || '').replace(/\s+/g, ' ').trim().slice(0, 200);
            const items = await page.getByTestId('pbwf-retention-item').count();
            const reclaimable = await page.getByTestId('pbwf-retention-reclaimable').count();
            await shot(page, '09b-retention-dry-run');
            let applied = 'apply disabled (nothing actionable)';
            if (await page.getByTestId('pbwf-retention-apply').isEnabled()) {
                await page.getByTestId('pbwf-retention-apply').click();
                await expect(page.getByTestId('pbwf-retention-result')).toBeVisible({ timeout: 120000 });
                applied = ((await page.getByTestId('pbwf-retention-result').textContent()) || '').replace(/\s+/g, ' ').trim().slice(0, 200);
            }
            await shot(page, '09c-retention-applied');
            await page.locator('.am7-dialog button', { hasText: 'Close' }).first().click().catch(() => {});
            await expect(page.locator('.am7-dialog')).toHaveCount(0, { timeout: T.ui });
            // Re-open the inspector for the archived toggle (the panel was closed to reach the dialog footer).
            await card.click({ position: { x: 14, y: 12 } });
            await expect(roleBase).toBeVisible({ timeout: T.ui });
            const archivedToggle = await roleBase.getByTestId('pbwf-art-archived-toggle').count();
            const nd = await pbGet(request, '/' + fx.book.objectId + '/workflow/node/' + fx.planNode.objectId);
            const base = (nd.body.artifacts && nd.body.artifacts.base) || [];
            const statuses = base.map(a => 'a' + a.attempt + 'v' + a.variantIndex + ':' + String(a.reviewStatus).toLowerCase() + (a.selected ? '*' : ''));
            fx.baseArtifacts = base.map(a => ({ objectId: a.objectId, attempt: a.attempt, variantIndex: a.variantIndex, seed: a.seed, reviewStatus: a.reviewStatus, selected: a.selected, qa: a.qa ? a.qa.result : null }));
            return 'base: attempts ' + attempts + ' tiles ' + tileCount + ' variant badges ' + variantBadges + ' seed badges ' + seedBadges + ' qa badges ' + qaBadges + '; compare items ' + compareItems + ' qa ' + compareQa
                + '; dry run "' + summary + '" items ' + items + ' reclaimable ' + reclaimable + '; apply → "' + applied + '"; archived toggle ' + archivedToggle + '; REST statuses ' + statuses.join(' ')
                + '; dialog box ' + (dialogBox ? Math.round(dialogBox.width) + '×' + Math.round(dialogBox.height) + ' at x=' + Math.round(dialogBox.x) : '?') + ' (size lg should cap at max-w-2xl = 672 px — run 9 showed it full-width)';
        });

        // ── 10. QA ───────────────────────────────────────────────────────────────────────────────────
        await step(page, 10, 'QA', async () => {
            expect(fx.planNode, 'unreachable: no RENDER_PLAN node from step 8').toBeTruthy();
            await page.goto('/#!/picture-book/' + fx.book.objectId + '/review', { timeout: T.ui });
            await expect(page.getByTestId('pb2-review-counts')).toBeVisible({ timeout: 60000 });
            await expect(page.getByTestId('pb2-review-counts')).not.toHaveText('', { timeout: 60000 });
            const qaButtons = page.getByTestId('pb2-review-qa');
            const qaCount = await qaButtons.count();
            let report = 'no QA badge on any queued artifact';
            if (qaCount) {
                await qaButtons.first().click();
                await expect(qaButtons.first()).toHaveAttribute('data-open', '1');
                await expect(page.getByTestId('pb2-review-qa-report').first()).toBeVisible({ timeout: T.ui });
                const checks = await page.getByTestId('pb2-review-qa-check').evaluateAll(els => els.map(e => e.getAttribute('data-kind') + '=' + e.getAttribute('data-result')));
                report = 'first badge ' + (await qaButtons.first().getAttribute('data-qa-result')) + ' report result ' + (await page.getByTestId('pb2-review-qa-report').first().getAttribute('data-result')) + ' checks ' + JSON.stringify(checks);
            }
            // Read-back: the node's qa summaries live on the base images, never on the qa/renderPlan rows, and the queue never lists the qa role.
            const nd = await pbGet(request, '/' + fx.book.objectId + '/workflow/node/' + fx.planNode.objectId);
            const arts = nd.body.artifacts || {};
            const judged = (arts.base || []).filter(a => a.qa).map(a => 'a' + a.attempt + 'v' + a.variantIndex + '=' + a.qa.result + (a.qa.provider ? '(' + a.qa.provider + ')' : ''));
            const qaRows = (arts.qa || []).length;
            const bookkeepingWithQa = ['qa', 'renderPlan'].reduce((n, r) => n + (arts[r] || []).filter(a => a.qa).length, 0);
            const rq = await pbGet(request, '/' + fx.book.objectId + '/review-queue?filter=all');
            const queued = [];
            for (const n of (rq.body.nodes || [])) for (const a of (n.artifacts || [])) queued.push(a.role || a.artifactType || '?');
            expect(bookkeepingWithQa, 'qa/renderPlan summaries never carry qa').toBe(0);
            return 'review-page QA badges ' + qaCount + '; ' + report + '; REST judged base variants ' + judged.length + ' ' + JSON.stringify(judged).slice(0, 200) + '; qa artifacts ' + qaRows + '; queue artifact roles ' + JSON.stringify([...new Set(queued)]);
        });

        // ── 11. Review queue ─────────────────────────────────────────────────────────────────────────
        await step(page, 11, 'Review queue', async () => {
            expect(fx.planNode, 'unreachable: no RENDER_PLAN node from step 8').toBeTruthy();
            await page.goto('/#!/picture-book/' + fx.book.objectId + '/review', { timeout: T.ui });
            await expect(page.getByTestId('pb2-review-counts')).toBeVisible({ timeout: 60000 });
            const counts = ((await page.getByTestId('pb2-review-counts').textContent()) || '').trim();
            const autoApprove = await page.getByTestId('pb2-review-auto-approve').getAttribute('data-on');
            const nodeRow = page.locator('[data-testid="pb2-review-node"][data-node-id="' + fx.planNode.objectId + '"]');
            await expect(nodeRow).toBeVisible({ timeout: 60000 });
            const rowsAll = await page.getByTestId('pb2-review-artifact').count();
            // A fresh variant's reviewStatus is GENERATED (reviewStatusOf() in pictureBookReview.js:69-71 defaults to it, and
            // the header's "N pending" counts it); IN_REVIEW is a second pending state. Run 9 waited on IN_REVIEW alone → 0.
            const PENDING = '[data-testid="pb2-review-artifact"]:is([data-review-status="GENERATED"],[data-review-status="IN_REVIEW"])';
            const inReview = nodeRow.locator(PENDING);
            const inReviewCount = await inReview.count();
            // 221 a: bookkeeping (renderPlan / renderGraph JSON) rows appear in the queue today — count them, record once.
            const jsonRows = await page.getByTestId('pb2-review-artifact').filter({ has: page.locator('.material-symbols-outlined', { hasText: 'image' }) }).count();
            await page.getByTestId('pb2-review-filter-auto').click();
            const autoRows = await page.getByTestId('pb2-review-artifact').count();
            const autoEmpty = await page.getByTestId('pb2-review-empty').count();
            await page.getByTestId('pb2-review-filter-all').click();
            await expect(nodeRow).toBeVisible({ timeout: 60000 });
            const planToggle = nodeRow.getByTestId('pb2-review-plan-toggle');
            await planToggle.click();
            await expect(planToggle).toHaveText('Hide plan', { timeout: T.ui });
            await expect(nodeRow.getByTestId('pb2-review-plan').getByTestId('pb2-plan-inspector')).toBeVisible();
            await shot(page, '11a-review-queue');
            // Approve one pending base image (the newest attempt's first variant with a thumbnail) → APPROVED on read-back, row leaves the pending set.
            const candidates = nodeRow.locator(PENDING).filter({ has: page.locator('img') });
            expect(await candidates.count(), 'a pending (GENERATED/IN_REVIEW) image artifact to approve').toBeGreaterThan(0);
            const target = candidates.first();
            const targetOid = await target.getAttribute('data-artifact-id');
            // Use/Approve/Reject are always rendered and disabled per artifactActions(); a click on a disabled button waits forever.
            const useBtn = target.getByTestId('pb2-review-use');
            let useNote = 'Use disabled (already selected or node pinned)';
            if (await useBtn.isEnabled()) {
                await useBtn.click();
                useNote = 'Use → "' + (await waitForToast(page, /is now current|failed/i, 60000).catch(() => '(no toast)')).trim().slice(0, 120) + '"';
            }
            const approveBtn = page.locator('[data-testid="pb2-review-artifact"][data-artifact-id="' + targetOid + '"]').getByTestId('pb2-review-approve');
            await expect(approveBtn).toBeEnabled({ timeout: T.ui });
            await approveBtn.click();
            await waitForToast(page, 'Approved');
            await page.waitForTimeout(1000);
            const rowAfter = page.locator('[data-testid="pb2-review-artifact"][data-artifact-id="' + targetOid + '"]');
            const rowStatusAfter = (await rowAfter.count()) ? await rowAfter.getAttribute('data-review-status') : 'row gone';
            const countsAfter = ((await page.getByTestId('pb2-review-counts').textContent()) || '').trim();
            const nd = await pbGet(request, '/' + fx.book.objectId + '/workflow/node/' + fx.planNode.objectId);
            const base = (nd.body.artifacts && nd.body.artifacts.base) || [];
            const approved = base.find(a => a.objectId === targetOid);
            expect(approved && upper(approved.reviewStatus), 'approved artifact read-back').toBe('APPROVED');
            fx.approvedArtifact = targetOid;
            return 'counts "' + counts + '" → "' + countsAfter + '"; auto-approve ' + autoApprove + '; rows all ' + rowsAll + ' (image-less rows ' + jsonRows + ', 221 a), pending (GENERATED/IN_REVIEW) on the node ' + inReviewCount + '; auto filter rows ' + autoRows + ' empty ' + autoEmpty
                + '; ' + useNote + '; approved ' + targetOid + ' → row ' + rowStatusAfter + ', REST ' + approved.reviewStatus + ' selected=' + approved.selected;
        });

        // ── 12. Reader after approval ────────────────────────────────────────────────────────────────
        await step(page, 12, 'Reader after approval', async () => {
            await openReader(page, fx.book.objectId);
            // The cover count lives on the cover page only; the reader is on whatever page it was left on.
            const cover = (await page.locator('[data-pb2-cover-count]').count()) ? ((await page.locator('[data-pb2-cover-count]').textContent()) || '').trim() : '(not on the cover)';
            await openFirstPage(page);
            const img = page.getByTestId('pb2-scene-image');
            const imgCount = await img.count();
            // renderPb2SceneChip returns null on a scene with nothing to say — count first, never wait on it (run-3 hang).
            const chip = (await page.getByTestId('pb2-scene-chip').count()) ? await page.getByTestId('pb2-scene-chip').first().getAttribute('data-chip') : '(no chip)';
            const stale = await page.getByTestId('pb2-stale-badge').count();
            const pages = await pbGet(request, '/' + fx.book.objectId + '/pages');
            const p0 = (pages.body || [])[0] || {};
            const node = (p0.sceneNode && p0.sceneNode.objectId) || null;
            return 'cover "' + cover + '"; page 1 pb2-scene-image ' + imgCount + ' chip=' + chip + ' stale badge ' + stale + '; /pages[0] imageObjectId=' + (p0.imageObjectId || null) + ' sceneNode=' + node
                + ' — ' + (imgCount ? 'the approved base is the page image' : '213 e / 219 f: an approved RENDER_PLAN base is still not the page image (recorded in step 8, re-asked in §17 P9-4)');
        });

        // ── 13. ChapBook ─────────────────────────────────────────────────────────────────────────────
        await step(page, 13, 'ChapBook', async () => {
            // P10-D 6 (§6.C 267 i): the ChapBook is made through the UI — Add from Data → the ObjectPicker in its default
            // container (~/Data, name filter) → "Set poem order" → Import → "Create ChapBook (1)" → the Create dialog (title,
            // slug, max lines, the dialog's own default chat config) → Create (async job) → the reader. Only the .docx upload
            // stays a REST fixture: the generic data.data upload form is not a ChapBook surface, and every ChapBook spec seeds
            // its sources that way (chapBookDataPickerNav.spec.js is the picker pattern).
            const docPath = path.join(MEDIA_DIR, SOURCE_DOC);
            const dataDir = await ensurePath(request, 'auth.group', 'data', '~/Data');        // the picker's default container for data.data
            const dataName = 'p90-cb-' + stamp + '-' + SOURCE_DOC;                              // no '/', '~' or '..': the picker filter reads those as a path
            const data = await jsonOf(await request.post(REST + '/model', { data: { schema: 'data.data', name: dataName, groupId: dataDir.id, groupPath: dataDir.path, contentType: DOCX_MIME, dataBytesStore: fs.readFileSync(docPath).toString('base64') } }));
            expect(data && data.objectId, 'ChapBook source upload').toBeTruthy();
            await page.goto('/#!/chap-book', { timeout: T.ui });
            const addFromData = page.locator('button', { hasText: 'Add from Data' });
            if (!(await addFromData.isVisible().catch(() => false))) await page.locator('[data-testid="cb-config-toggle"]').click();
            await expect(addFromData).toBeVisible({ timeout: T.ui });
            await addFromData.click();
            const overlay = page.locator('.am7-picker-overlay');
            await expect(overlay).toBeVisible({ timeout: 15000 });
            const filter = overlay.locator('#listFilter');
            await filter.waitFor({ state: 'visible', timeout: 15000 });
            await filter.fill(dataName);
            await Promise.all([
                page.waitForResponse(r => /\/rest\/model\/search$/.test(r.url()) && r.request().method() === 'POST', { timeout: 15000 }).catch(() => null),
                filter.press('Enter')
            ]);
            const row = overlay.locator('tr.tabular-row', { hasText: dataName });
            await expect(row, 'picker lists the uploaded .docx in ~/Data under its name filter').toBeVisible({ timeout: 20000 });
            await row.first().click();
            await overlay.locator('button:has(span.material-symbols-outlined:text-is("check"))').click();
            const orderH3 = page.locator('h3', { hasText: 'Set poem order' });
            await expect(orderH3).toBeVisible({ timeout: 15000 });
            const orderTitle = ((await orderH3.textContent()) || '').trim();
            await expect(page.locator('li[class*="rounded"] span[title="' + dataName + '"]'), 'the picked .docx is the one note to import').toBeVisible({ timeout: 10000 });
            await shot(page, '13c-chapbook-poem-order');
            const t0i = Date.now();
            await page.locator('button', { hasText: 'Import 1 poem(s) in this order' }).click();
            const importToast = await waitForToast(page, /Imported \d+ of \d+ poem|No poems were imported|Import failed/i, 120000);
            const importMs = Date.now() - t0i;
            expect(importToast, 'poem import (Tika, no LLM)').toMatch(/Imported 1 of 1/);
            const createBtn = page.locator('button', { hasText: 'Create ChapBook (1)' });
            await expect(createBtn, 'the imported poem is auto-selected').toBeVisible({ timeout: T.ui });
            await createBtn.click();
            const dialog = page.locator('div.fixed.inset-0', { has: page.locator('h3:text-is("Create ChapBook")') }).last();
            await expect(dialog).toBeVisible({ timeout: 15000 });
            const cbSlug = 'p90-chap-' + stamp;
            const cbTitle = 'P9-0 ChapBook ' + stamp;
            const textInputs = dialog.locator('input[type="text"]');
            await textInputs.nth(0).fill(cbTitle);                       // Title (the dialog slugifies it into Slug)
            const autoSlug = await textInputs.nth(1).inputValue();
            await textInputs.nth(1).fill(cbSlug);                        // Slug, kept at the gate's p90-chap-<stamp> shape
            await dialog.locator('input[type="number"]').fill('20');     // Max Lines per Page (dialog default 8; the REST walk sent 20)
            // The dialog's own default chat config (contentAnalysis, then generalChat — P10-D 4) is left as resolved; observation only.
            await expect(dialog.locator('span', { hasText: 'Resolving default' })).toHaveCount(0, { timeout: T.ui }).catch(() => {});
            const dialogChatConfig = ((await dialog.locator('label:has-text("Chat Config") + div > span').first().textContent().catch(() => '(unread)')) || '').trim();
            await shot(page, '13d-chapbook-create-dialog');
            const create = dialog.getByRole('button', { name: 'Create', exact: true });
            await expect(create).toBeEnabled({ timeout: 5000 });
            const t0c = Date.now();
            await create.click();
            await page.waitForURL(/#!\/chap-book\/read\//, { timeout: T.plan });
            const createMs = Date.now() - t0c;
            const cbOid = page.url().split('/chap-book/read/')[1].split(/[?#]/)[0];
            expect(cbOid, 'reader route after Create').toBeTruthy();
            const createdToast = await page.locator('.toast-text', { hasText: /ChapBook created/ }).count();
            fx.chapBook = { objectId: cbOid, slug: cbSlug, organizationId: fx.user.organizationId };
            // Read-back: the book the dialog made carries the slug typed into it.
            const bookRows = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: cbOid }], ['id', 'objectId', 'name', 'slug']);
            const bookSlug = (bookRows[0] || {}).slug || '(book row not readable via model/search)';
            expect(bookSlug, 'slug persisted from the Create dialog').toBe(cbSlug);
            const pages0 = await pbGet(request, '/' + cbOid + '/pages');
            const cbPages = Array.isArray(pages0.body) ? pages0.body : [];
            expect(cbPages.length, 'ChapBook pages').toBeGreaterThan(0);
            const p0 = cbPages[0];
            // Review screen: location chip, prompt edit → Save prompt (locks it) → Re-render this page on the SD server.
            await page.evaluate(h => { location.hash = h; }, '!/chap-book/review/' + cbOid);
            const ta = page.locator('textarea.cb-scene-prompt[data-scene-oid="' + p0.objectId + '"]');
            await expect(ta).toBeVisible({ timeout: 60000 });
            const chipCount = await page.locator('[data-testid="cb-page-location"][data-scene-oid="' + p0.objectId + '"]').count();
            const chipLoc = chipCount ? await page.locator('[data-testid="cb-page-location"][data-scene-oid="' + p0.objectId + '"]').getAttribute('data-location-id') : 'absent';
            const promptBefore = await ta.inputValue();
            const lockedPrompt = 'A sun-drenched palace terrace at golden hour, a queen in a gown of woven light, storybook watercolour, warm palette (P9-0 ' + stamp + ')';
            await ta.fill(lockedPrompt);
            await page.locator('[data-testid="cb-page-save"][data-scene-oid="' + p0.objectId + '"]').click();
            const saveToast = await waitForToast(page, /saved|Saved|failed/i);
            const t0 = Date.now();
            await page.locator('[data-testid="cb-page-rerender"][data-scene-oid="' + p0.objectId + '"]').click();
            const rerenderToast = await waitForToast(page, /Page re-rendered|re-rendered|Re-render failed|no usable prompt|degraded|unavailable/i, T.chapRender);
            const renderMs = Date.now() - t0;
            await shot(page, '13a-chapbook-review-rendered');
            expect(rerenderToast, 'ChapBook page render').toMatch(/re-rendered/i);
            await expect(page.locator('img.cb-scene-image[data-scene-oid="' + p0.objectId + '"]')).toBeVisible({ timeout: 60000 });
            // Read-backs: the page carries the image and the locked prompt verbatim.
            const pages1 = await pbGet(request, '/' + cbOid + '/pages');
            const q0 = (pages1.body || []).find(p => p.objectId === p0.objectId) || {};
            // The page composition is the authority for the prompt (V3 §24; promptOverride + promptLocked);
            // the scene row is its mirror (sdPrompt) — read both, and the composition is what must match.
            const comp = await pbGet(request, '/' + cbOid + '/scene/' + p0.objectId + '/composition');
            expect(comp.status, 'GET /composition for the ChapBook page').toBe(200);
            const c0 = (comp.body && comp.body.composition) || {};
            const sceneRows = await search(request, 'olio.pb.scene', [{ name: 'objectId', comparator: 'equals', value: p0.objectId }], ['id', 'objectId', 'sdPrompt', 'promptLocked', 'imageObjectId']);
            const s0 = sceneRows[0] || {};
            expect(c0.promptOverride, 'locked prompt persisted verbatim on the page composition').toBe(lockedPrompt);
            expect(s0.sdPrompt || '(scene row not readable via model/search)', 'scene mirror sdPrompt').toBe(sceneRows.length ? lockedPrompt : '(scene row not readable via model/search)');
            expect(q0.dataObjectId || s0.imageObjectId, 'page image persisted (pages.dataObjectId or scene.imageObjectId)').toBeTruthy();
            // Reader: Begin → the page image loads (the cover is the first page image today).
            await page.evaluate(h => { location.hash = h; }, '!/chap-book/read/' + cbOid);
            await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 60000 });
            const coverImgs = await page.locator('img').count();
            await page.locator('button', { hasText: 'Begin' }).click();
            const pageImg = page.locator('img.object-cover').first();
            await expect(pageImg).toBeVisible({ timeout: 60000 });
            const loaded = await pageImg.evaluate(el => el.complete && el.naturalWidth > 0);
            await shot(page, '13b-chapbook-reader');
            return 'UI: picker row "' + dataName + '" → "' + orderTitle + '" → "' + importToast.trim() + '" in ' + Math.round(importMs / 1000) + ' s → Create dialog (auto slug "' + autoSlug + '", chat config "' + dialogChatConfig + '") → reader in ' + Math.round(createMs / 1000) + ' s (created toast count ' + createdToast + '); REST book slug "' + bookSlug + '"'
                + '; ChapBook ' + cbOid + ' slug=' + cbSlug + ' pages ' + cbPages.length + '; location chip count ' + chipCount + ' (location-id "' + chipLoc + '"); prompt before ' + (promptBefore ? promptBefore.length + ' chars' : 'empty') + '; save → "' + saveToast.trim() + '"; re-render → "' + rerenderToast.trim() + '" in ' + Math.round(renderMs / 1000) + ' s'
                + '; REST composition promptLocked=' + c0.promptLocked + ' revision=' + c0.revision + ' (scene mirror rows ' + sceneRows.length + ' promptLocked=' + s0.promptLocked + ') image=' + (q0.dataObjectId || s0.imageObjectId) + '; reader cover <img> ' + coverImgs + ', page image loaded=' + loaded + '; no pre-V3 lock notice element exists (lock is the saved-prompt state, recorded)';
        });

        // ── 14. Export / print ───────────────────────────────────────────────────────────────────────
        await step(page, 14, 'Export', async () => {
            const parse = html => ({
                cover: /<div class="cover">[\s\S]*?<\/div>\s*<\/div>/.exec(html) ? true : false,
                coverImg: /<div class="cover">\s*<img /.test(html),
                scenes: (html.match(/<div class="scene">/g) || []).length,
                sceneImgs: (html.match(/<div class="scene">\s*<img /g) || []).length,
                title: (/<title>([^<]*)<\/title>/.exec(html) || [])[1] || ''
            });
            // ChapBook reader (one rendered page → cover slot carries it). ReaderShell disables Export until the
            // pages have loaded (readerShell.js:220) — a fresh route load (page.goto, never a same-hash no-op) and
            // wait for the cover before clicking.
            // The shell reopens on the page the walk left it on (run 8: "Page 1 of 6", no cover, no "Begin"
            // — the Begin button exists only on the cover slot), so never wait for Begin; record where it opened.
            await page.goto('/#!/chap-book/read/' + fx.chapBook.objectId, { timeout: T.ui });
            await expect(page.locator('button[title="Export as HTML"]')).toBeEnabled({ timeout: 60000 });
            const openedOn = (await page.locator('button', { hasText: 'Begin' }).count()) ? 'cover' : (((await page.getByText(/^Page \d+ of \d+$/).first().textContent().catch(() => '')) || '').trim() || '(no page label)');
            const dl1 = page.waitForEvent('download', { timeout: 120000 });
            await page.locator('button[title="Export as HTML"]').click();
            const d1 = await dl1;
            const cbHtml = fs.readFileSync(await d1.path(), 'utf8');
            const cb = parse(cbHtml);
            const cbPages = await pbGet(request, '/' + fx.chapBook.objectId + '/pages');
            expect(cb.scenes, 'ChapBook export page count = pages').toBe((cbPages.body || []).length);
            expect(cb.coverImg, 'ChapBook export cover slot carries the first page image').toBe(true);
            fs.writeFileSync(path.join(OUT_DIR, '14-export-chapbook.html'), cbHtml);
            // The PB2 reader (/picture-book/v2/…, renderPb2Header pictureBook.js:2196-2298) has NO Export control — §16
            // step 14 names one that only ReaderShell has, i.e. the PB1 viewer (/picture-book/{group}) and the ChapBook
            // reader. Record that. The step-1 legacy group was imported, so its route now bridges to the V3 reader
            // (run 9: health banner, no legacy notice, no Export) — export a second, un-imported PB1 book through the viewer.
            await openReader(page, fx.book.objectId);
            const pb2Export = await page.locator('button[title="Export as HTML"]').count();
            await page.goto('/#!/picture-book/' + fx.legacy.group, { timeout: T.ui });
            await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: 60000 });
            const importedRoute = await page.evaluate(() => location.hash);
            const importedBanner = ((await page.locator('[data-pb2-health-banner]').first().textContent().catch(() => '')) || '').replace(/\s+/g, ' ').trim().slice(0, 160);
            const importedExport = await page.locator('button[title="Export as HTML"]').count();
            fx.legacy2 = await makeLegacyBook('p90-legacy2-' + stamp);
            await page.goto('/#!/picture-book/' + fx.legacy2.group, { timeout: T.ui });
            await expect(page.locator('[data-pb1-legacy-notice]')).toBeVisible({ timeout: 60000 });
            await expect(page.locator('button[title="Export as HTML"]')).toBeEnabled({ timeout: 60000 });
            const dl2 = page.waitForEvent('download', { timeout: 120000 });
            await page.locator('button[title="Export as HTML"]').click();
            const d2 = await dl2;
            const pbHtml = fs.readFileSync(await d2.path(), 'utf8');
            const pb = parse(pbHtml);
            expect(pb.scenes, 'PB1 viewer export page count = its scenes').toBeGreaterThan(0);
            fs.writeFileSync(path.join(OUT_DIR, '14-export-picturebook.html'), pbHtml);
            await shot(page, '14a-pb1-viewer-export');
            return 'ChapBook reader opened on ' + openedOn + '; export "' + d1.suggestedFilename() + '": cover ' + cb.cover + ' coverImg ' + cb.coverImg + ' scenes ' + cb.scenes + ' with images ' + cb.sceneImgs + ' (nav total = cover + ' + cb.scenes + ')'
                + '; PB2 reader Export controls ' + pb2Export + ' (none exists — §16 hook gap); imported legacy group route → ' + importedRoute + ' Export controls ' + importedExport + ' banner "' + importedBanner + '"'
                + '; un-imported PB1 viewer ' + fx.legacy2.group + ' "' + d2.suggestedFilename() + '": cover ' + pb.cover + ' coverImg ' + pb.coverImg + ' scenes ' + pb.scenes + ' with images ' + pb.sceneImgs;
        });

        // ── 15. Cover & Art (P9-6; V3 §40, the P9-4 board + reader hooks) ────────────────────────────
        await step(page, 15, 'Cover and Art', async () => {
            const notes = [];
            // Cover board (cover mode of the composition board, pictureBookCompose.js) on the book the wizard made.
            const openCoverBoard = async (bookOid) => {
                await page.goto('/#!/picture-book/' + bookOid + '/cover', { timeout: T.ui });
                await expect(page.getByTestId('pb-cmp-title')).toHaveText('Cover art', { timeout: T.ui });
                await expect(page.getByTestId('pb-cmp-purpose-tabs')).toBeVisible();
            };
            // Render → the job strip goes terminal → Approve latest. Either the approve click or the book's
            // auto-approve (a QA verdict on the stack) may be what approves; both end with the approved tile set.
            const renderAndApprove = async (label) => {
                const strip = page.getByTestId('pb-cmp-cover');
                await expect(strip).toBeVisible({ timeout: T.ui });
                const t0 = Date.now();
                await page.getByTestId('pb-cmp-render-cover').click();
                await waitForToast(page, /^Cover render started/, 60000);
                const run = page.getByTestId('pb-cmp-render-run');
                await expect(run).toBeVisible({ timeout: T.ui });
                await expect(run).toHaveAttribute('data-run-terminal', 'true', { timeout: T.render });
                const counter = ((await page.getByTestId('pb-cmp-render-counter').textContent()) || '').trim();
                const renderMs = Date.now() - t0;
                expect(counter, label + ' cover render job finished without an error').toBe('Rendered');
                await waitForToast(page, /Cover: Rendered/, 60000);
                // The board re-reads the covers after the job; wait for a tile to carry an artifact before deciding
                // whether the render's QA verdict already approved it (no Latest tile) or Approve latest is ours to click.
                const latestTile = page.getByTestId('pb-cmp-cover-latest');
                const approvedTile = page.getByTestId('pb-cmp-cover-approved');
                await expect(page.getByTestId('pb-cmp-cover-state')).not.toHaveText('Not rendered yet', { timeout: 60000 });
                await expect.poll(async () => (await latestTile.count()) ? latestTile.getAttribute('data-artifact') : approvedTile.getAttribute('data-artifact'), { timeout: 60000 }).toMatch(/^[0-9a-f-]{36}$/);
                const state = ((await page.getByTestId('pb-cmp-cover-state').textContent()) || '').trim();
                const nodeStatus = await strip.getAttribute('data-node-status');
                let latestOid = null, autoApproved = false;
                if (await latestTile.count()) {
                    latestOid = await latestTile.getAttribute('data-artifact');
                    await expect(page.getByTestId('pb-cmp-approve-cover')).toBeEnabled({ timeout: 60000 });
                    await page.getByTestId('pb-cmp-approve-cover').click();
                    await waitForToast(page, /Cover approved/, 60000);
                    await expect(approvedTile).toHaveAttribute('data-artifact', latestOid, { timeout: 60000 });
                } else {
                    autoApproved = true;   // the render's QA verdict approved it before the click could
                }
                const approvedOid = await approvedTile.getAttribute('data-artifact');
                expect(approvedOid, label + ' approved tile carries the artifact').toMatch(/^[0-9a-f-]{36}$/);
                await expect(approvedTile).toContainText('approved');
                await expect(approvedTile.locator('img')).toHaveCount(1);
                await expect.poll(() => approvedTile.locator('img').evaluate(img => img.complete && img.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
                await expect(page.getByTestId('pb-cmp-approve-cover')).toBeDisabled();
                await expect(page.getByTestId('pb-cmp-cover-state')).toContainText('approved');
                return { renderMs, counter, state, nodeStatus, latestOid, approvedOid, autoApproved };
            };
            // The approved entry, read back the way the readers read it (GET /{book}/covers → covers.COVER).
            const approvedEntry = async (bookOid, label) => {
                const v = await pbGet(request, '/' + bookOid + '/covers');
                expect(v.status, label + ' GET /covers').toBe(200);
                const c = (v.body.covers || {}).COVER;
                expect(c && c.approved && c.approved.artifactObjectId, label + ' covers.COVER.approved: ' + JSON.stringify(c).slice(0, 400)).toBeTruthy();
                expect(upper(c.approved.reviewStatus), label + ' approved.reviewStatus').toBe('APPROVED');
                expect(c.selectedArtifactObjectId, label + ' selected = approved').toBe(c.approved.artifactObjectId);
                expect(c.approved.imageGroupPath && c.approved.imageName, label + ' approved ref carries the media path').toBeTruthy();
                expect(v.body.workflowObjectId, label + ' the render created the workflow').toBeTruthy();
                return c;
            };

            // 15a — the book: Auto (the LLM stages it from the book's cast + premise, chat config CONFIG_NAME).
            await openCoverBoard(fx.book.objectId);
            const existsBefore = await page.getByTestId('pb-cmp-purpose-cover').getAttribute('data-exists');
            expect(existsBefore, 'the walk made no cover yet').toBe('false');
            await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: T.ui });
            await expect(page.getByTestId('pb-cmp-empty-message')).toContainText(/no cover composition/i);
            // The board's chat-config pick is module state keyed to the book: step 7 picked CONFIG_NAME on this book's
            // scene board, so it usually still shows here; pick again only when it does not.
            const cfgShown = ((await page.getByTestId('pb-cmp-chatconfig').textContent()) || '').trim();
            if (cfgShown.indexOf(CONFIG_NAME) < 0) {
                await page.getByTestId('pb-cmp-chatconfig').click();
                await pickFromOverlay(page, CONFIG_NAME);
                await expect(page.getByTestId('pb-cmp-chatconfig')).toContainText(CONFIG_NAME, { timeout: T.ui });
                notes.push('chat config picked');
            } else notes.push('chat config carried from step 7');
            await page.getByTestId('pb-cmp-create-cover-open').click();
            await expect(page.getByTestId('pb-cmp-create-cover')).toBeVisible();
            await expect(page.getByTestId('pb-cmp-create-auto')).toBeChecked();
            await expect(page.getByTestId('pb-cmp-create-from-scene')).toBeEnabled();
            await shot(page, '15a-book-create-dialog');
            const tAuto = Date.now();
            await page.getByTestId('pb-cmp-create-submit').click();
            const createdToast = (await waitForToast(page, /^Cover (created|was not created|already exists)/, T.compose)).trim();
            const autoMs = Date.now() - tAuto;
            expect(createdToast, 'Auto cover created through the dialog').toMatch(/^Cover created/);
            await expect(page.getByTestId('pb-cmp-cover')).toBeVisible({ timeout: T.ui });
            await expect(page.getByTestId('pb-cmp-source')).toHaveText(/llm/i);
            await expect(page.getByTestId('pb-cmp-purpose')).toHaveText(/cover/i);
            await expect(page.getByTestId('pb-cmp-purpose-cover')).toHaveAttribute('data-exists', 'true');
            await expect(page.getByTestId('pb-cmp-cover-state')).toHaveText('Not rendered yet');
            const bandChip = (await page.getByTestId('pb-cmp-cover-band').count()) ? ((await page.getByTestId('pb-cmp-cover-band').textContent()) || '').trim() : '(no band chip — band UNKNOWN/NONE)';
            const setting = await page.getByTestId('pb-cmp-setting').inputValue();
            const actorCards = await page.locator('li[data-actor-objectid]').count();
            await shot(page, '15b-book-cover-created');
            const board0 = await pbGet(request, '/' + fx.book.objectId + '/covers/cover');
            expect(board0.status, 'GET /covers/cover after Auto').toBe(200);
            const comp0 = board0.body.composition || {};
            expect(upper(comp0.source), 'Auto = LLM-source composition').toBe('LLM');
            expect(upper(comp0.purpose)).toBe('COVER');
            const actors0 = board0.body.actors || [];
            const heroes = actors0.map(a => a.characterName || a.description || a.objectId).join(', ');
            const bookRender = await renderAndApprove('book');
            await shot(page, '15c-book-cover-approved');
            const bookCover = await approvedEntry(fx.book.objectId, 'book');
            expect(bookCover.approved.artifactObjectId, 'the approved tile is the approved entry').toBe(bookRender.approvedOid);
            // The V3 reader's cover page: the approved render, undimmed, title block in the band.
            await openReader(page, fx.book.objectId);
            await page.keyboard.press('Home');   // the reader keeps its page across visits (pictureBook.js pb2OnKeyDown Home → cover)
            const coverRoot = page.locator('[data-pb2-cover-source]');
            await expect(coverRoot).toHaveCount(1, { timeout: T.ui });
            await expect(coverRoot).toHaveAttribute('data-pb2-cover-source', 'cover', { timeout: T.ui });
            const readerBand = await coverRoot.getAttribute('data-pb2-cover-band');
            const readerImg = page.locator('[data-pb2-cover-image]');
            await expect(readerImg).toHaveCount(1);
            await expect(readerImg).toHaveClass(/opacity-100/);
            expect(await readerImg.getAttribute('src'), 'reader cover src = approved imageGroupPath/imageName').toContain(bookCover.approved.imageGroupPath + '/' + bookCover.approved.imageName);
            await expect.poll(() => readerImg.evaluate(el => el.complete && el.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
            await shot(page, '15d-book-reader-cover');
            notes.push('book: Auto in ' + Math.round(autoMs / 1000) + ' s → "' + createdToast + '" source ' + comp0.source + ' band "' + bandChip + '" (' + upper(comp0.titleSafeBand) + ') actors ' + actors0.length + ' [' + heroes + '] (cards ' + actorCards + ') setting ' + (setting ? setting.length + ' chars' : 'empty')
                + '; render ' + Math.round(bookRender.renderMs / 1000) + ' s → "' + bookRender.counter + '" node ' + bookRender.nodeStatus + ' state "' + bookRender.state + '"' + (bookRender.autoApproved ? ' auto-approved by the render' : ' approved by the click on latest ' + bookRender.latestOid)
                + '; GET /covers approved ' + bookCover.approved.artifactObjectId + ' rev ' + bookCover.approved.revision + ' chain ' + bookCover.chainLength + ' band ' + upper(bookCover.titleSafeBand) + '; reader source=cover band="' + readerBand + '" image loaded');

            // 15e — the ChapBook: the same board on the olio.pb.book the step-13 ChapBook is; Manual + camera (setting,
            // action, mood, band) saved through the board, then render → approve → the ChapBook reader + export.
            // Auto on a ChapBook (no cast → NONE tier) is the Objects7 P9-5 live test's t02, not repeated here.
            const cbOid = fx.chapBook.objectId;
            await openCoverBoard(cbOid);
            expect(await page.getByTestId('pb-cmp-purpose-cover').getAttribute('data-exists'), 'the ChapBook has no cover yet').toBe('false');
            await expect(page.getByTestId('pb-cmp-empty')).toBeVisible({ timeout: T.ui });
            await page.getByTestId('pb-cmp-create-cover-open').click();
            await expect(page.getByTestId('pb-cmp-create-cover')).toBeVisible();
            const fromSceneEnabled = await page.getByTestId('pb-cmp-create-from-scene').isEnabled();
            await page.getByTestId('pb-cmp-create-manual').check();
            await page.getByTestId('pb-cmp-create-submit').click();
            const cbCreated = (await waitForToast(page, /^Cover (created|was not created|already exists)/, 60000)).trim();
            expect(cbCreated, 'Manual cover created on the ChapBook').toMatch(/^Cover created/);
            await expect(page.getByTestId('pb-cmp-cover')).toBeVisible({ timeout: T.ui });
            await expect(page.getByTestId('pb-cmp-source')).toHaveText(/user/i);
            const cbSetting = 'A sun-drenched palace terrace at golden hour, long shadows across pale stone, a queen in a gown of woven light seen from behind, storybook watercolour (P9-0 ' + stamp + ')';
            const cbAction = 'she rests one hand on the balustrade and looks out over the gardens';
            const cbMood = 'warm, hushed, golden';
            await page.getByTestId('pb-cmp-setting').fill(cbSetting);
            await page.getByTestId('pb-cmp-action').fill(cbAction);
            await page.getByTestId('pb-cmp-mood').fill(cbMood);
            await page.getByTestId('pb-cmp-title-band').selectOption('bottom');
            await page.getByTestId('pb-cmp-save-camera').click();
            await waitForToast(page, /Composition saved/, T.ui);
            await expect(page.getByTestId('pb-cmp-cover-band')).toHaveText('band bottom', { timeout: T.ui });
            await shot(page, '15e-chapbook-cover-created');
            const cbBoard = await pbGet(request, '/' + cbOid + '/covers/cover');
            expect(cbBoard.status, 'GET /covers/cover on the ChapBook').toBe(200);
            expect((cbBoard.body.composition || {}).settingText, 'ChapBook cover setting persisted').toBe(cbSetting);
            expect(upper((cbBoard.body.composition || {}).titleSafeBand)).toBe('BOTTOM');
            const cbRender = await renderAndApprove('ChapBook');
            await shot(page, '15f-chapbook-cover-approved');
            const cbCover = await approvedEntry(cbOid, 'ChapBook');
            expect(upper(cbCover.titleSafeBand)).toBe('BOTTOM');
            // ChapBook reader (ReaderShell): Home → the cover slot shows the approved render in the BOTTOM band.
            await page.goto('/#!/chap-book/read/' + cbOid, { timeout: T.ui });
            await expect(page.locator('button[title="Export as HTML"]')).toBeEnabled({ timeout: 60000 });
            await page.keyboard.press('Home');
            const cbRoot = page.locator('[data-cb-cover-source]');
            await expect(cbRoot).toHaveCount(1, { timeout: T.ui });
            await expect(cbRoot).toHaveAttribute('data-cb-cover-source', 'cover', { timeout: T.ui });
            await expect(cbRoot).toHaveAttribute('data-cb-cover-band', 'BOTTOM');
            await expect(cbRoot).toHaveClass(/justify-end/);
            const cbImg = page.locator('[data-cb-cover-image]');
            await expect(cbImg).toHaveCount(1);
            await expect(cbImg).toHaveClass(/opacity-100/);
            const cbReaderSrc = await cbImg.getAttribute('src');
            expect(cbReaderSrc, 'ChapBook reader cover src = approved imageGroupPath/imageName').toContain(cbCover.approved.imageGroupPath + '/' + cbCover.approved.imageName);
            await expect.poll(() => cbImg.evaluate(el => el.complete && el.naturalWidth), { timeout: 60000 }).toBeGreaterThan(0);
            // The ChapBook reader sits below the config panel + poem queue in an inner scroller, so a full-page shot taken
            // here framed only the queue (runs 16 / 17); scroll the cover into view first so the shot shows what was asserted.
            await cbRoot.scrollIntoViewIfNeeded();
            await shot(page, '15g-chapbook-reader-cover');
            // Export carries it: the cover slot's <img> is the approved render, not page 1's image (step 14's shape).
            // The export inlines the cover as a data URI (readerShell.js fetchImageAsBase64 → readAsDataURL), so the
            // imageName never appears in the HTML — run 16 asserted the name substring and failed a correct export.
            // Decode the data URI and compare its bytes with the approved artifact's media, fetched as the same user
            // from the URL the reader showed (the media route may gzip, as pictureBookV3Cover.spec.js found).
            const dl = page.waitForEvent('download', { timeout: 120000 });
            await page.locator('button[title="Export as HTML"]').click();
            const html = fs.readFileSync(await (await dl).path(), 'utf8');
            const coverSlot = /<div class="cover">\s*<img [^>]*src="([^"]*)"/.exec(html);
            expect(coverSlot && coverSlot[1], 'ChapBook export cover slot carries an image').toBeTruthy();
            const DATA_PNG = 'data:image/png;base64,';
            expect(coverSlot[1].slice(0, DATA_PNG.length), 'export cover is an inlined PNG data URI').toBe(DATA_PNG);
            const exportBytes = Buffer.from(coverSlot[1].slice(DATA_PNG.length), 'base64');
            expect(exportBytes.subarray(0, 8).toString('hex'), 'export cover bytes are a PNG').toBe('89504e470d0a1a0a');
            const mediaResp = await request.get(cbReaderSrc);
            expect(mediaResp.ok(), 'GET approved cover media ' + cbReaderSrc + ' HTTP ' + mediaResp.status()).toBe(true);
            let mediaBytes = await mediaResp.body();
            if (mediaBytes[0] === 0x1f && mediaBytes[1] === 0x8b) mediaBytes = gunzipSync(mediaBytes);
            expect(exportBytes.length, 'export cover byte length = approved media byte length').toBe(mediaBytes.length);
            expect(exportBytes.equals(mediaBytes), 'export cover bytes = the approved render\'s media bytes').toBe(true);
            fs.writeFileSync(path.join(OUT_DIR, '15-export-chapbook-cover.html'), html);
            notes.push('ChapBook: board opened on the CHAPBOOK book (from-scene offered ' + fromSceneEnabled + '); Manual → "' + cbCreated + '" camera saved band bottom'
                + '; render ' + Math.round(cbRender.renderMs / 1000) + ' s → "' + cbRender.counter + '" node ' + cbRender.nodeStatus + (cbRender.autoApproved ? ' auto-approved by the render' : ' approved by the click on latest ' + cbRender.latestOid)
                + '; GET /covers approved ' + cbCover.approved.artifactObjectId + ' rev ' + cbCover.approved.revision + ' chain ' + cbCover.chainLength + '; reader source=cover band BOTTOM image loaded; export cover <img> = data URI, ' + exportBytes.length + ' bytes = approved media ' + cbCover.approved.imageName);
            return notes.join(' || ');
        });
    });
});
