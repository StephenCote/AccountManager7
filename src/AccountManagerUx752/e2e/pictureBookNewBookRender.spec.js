/**
 * PictureBook V3 — P10-B: a NEW book is renderable end to end, as a user does it (no REST short-cuts on
 * the path under test).
 *
 * Stephen, UAT 2026-11-03/04: "PB is still not testable end to end when I try it on new books … Ux is
 * confusing, inconsistent, and blocks user on workflow not created for new books post scene and character
 * extract (ie: can't render scenes)" and "The error in Ux on a new book I see is: (404) This book has no
 * workflow yet - generate a scene list - (it has a scene list though)". The fix (PbServiceFacade:
 * createFromScenesAndCompose builds the scene graph at create; the render routes self-heal a missing
 * workflow after their gates; the canvas empty state offers "Open in reader to render" + "Build graph") is
 * proved here the way he hit it: upload a manuscript, run the wizard, land in the reader, press Render on
 * page 1, press Render all, open the workflow canvas — and never see that 404.
 *
 * ONE serial walk, ONE browser context, the shared test user (never admin), one real book from Stephen's
 * content (media/Kiss of the Sun Queen.docx — single-range manuscript, so the wizard takes the single-document
 * path). The book is LEFT IN PLACE in the resettable stack DB. A second, scene-less book (POST /chapter —
 * the only way to get a book with no scenes and no workflow, which the wizard can no longer produce) is
 * used ONLY to exercise the canvas empty state's two buttons; that one REST short-cut is in the step note.
 *
 * Every step: (a) asserts a visible outcome in the DOM, (b) reads the same fact back over the PB REST surface,
 * (c) saves a full-page screenshot to test-results/newbook/NN-<step>.png for a human to look at, (d) records
 * its elapsed time as an OBSERVATION — never a threshold. Ceilings exist so a dead watcher fails the step
 * instead of hanging the run.
 *
 * Run (Docker stack, dist current via `npx vite build` + `docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/`):
 *   PB_SD_TESTS=1 PB_LLM_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookNewBookRender.spec.js --workers=1 --project=chromium --retries=0
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, resolveChatRoute, ensureChatConfig, ensurePath } from './helpers/api.js';
import { REST, PB_REST, restLoginShared, loginAsSharedUser } from './helpers/chapbook.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TAG = '[p10b-newbook]';
const OUT_DIR = path.resolve(__dirname, '../test-results/newbook');
const MEDIA_DIR = path.resolve(__dirname, '../../AccountManagerObjects7/media');
const SOURCE_DOC = 'Kiss of the Sun Queen.docx';
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const CONFIG_NAME = '000-p90-gate-llm';       // '000-' sorts onto the ObjectPicker's first page
const CONN_NAME = '000-p90-gate-conn';

// Ceilings only (a dead watcher must fail, not hang). Render all renders every scene of the book serially on
// the SD server, so its ceiling is the per-scene ceiling times a generous scene count.
const T = { ui: 30000, extract: 2 * 900000 + 120000, characters: 1800000, render: 1500000, renderAll: 3 * 3600000 };

const stamp = Date.now().toString(36);
const results = [];
const consoleErrors = [];
const toasts = [];           // every toast text seen, for the "(404) no workflow yet" regression assertion
const fx = { user: null, route: null, chatConfig: null, doc: null, book: null, scenes: [], chapterBook: null };

function slugify(s) { return String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''); }
function b64path(p) { return 'B64-' + Buffer.from(p).toString('base64').replace(/=/g, '%3D'); }
async function shot(page, name) {
    fs.mkdirSync(OUT_DIR, { recursive: true });
    const file = path.join(OUT_DIR, name + '.png');
    await page.screenshot({ path: file, fullPage: true });
    return file;
}
async function jsonOf(resp) { const t = await resp.text(); try { return JSON.parse(t); } catch (e) { return { _raw: t.slice(0, 600) }; } }
async function pbGet(request, pathSuffix) {
    const r = await request.get(PB_REST + pathSuffix, { timeout: 300000 });
    return { status: r.status(), body: await jsonOf(r) };
}
async function waitForToast(page, re, timeout) {
    const t = page.locator('.toast-text', { hasText: re });
    await expect(t.first()).toBeVisible({ timeout: timeout || 15000 });
    return (await t.first().textContent()) || '';
}
/** Pick a chat config through the real ObjectPicker (pattern: pictureBookV3Usability.spec.js). */
async function pickFromOverlay(page, configName) {
    const overlay = page.locator('.am7-picker-overlay');
    await expect(overlay).toBeVisible({ timeout: 15000 });
    await expect(overlay.locator('h3')).toHaveText('Select Chat Config');
    const row = overlay.locator('tr.tabular-row', { hasText: configName });
    await expect(row, 'picker lists ~/Chat/' + configName).toBeVisible({ timeout: 20000 });
    if (!/tabular-row-active/.test((await row.getAttribute('class')) || '')) await row.click();
    await overlay.locator('button:has(span:text-is("check"))').first().click();
    await expect(overlay).toHaveCount(0, { timeout: 10000 });
}
async function readerState(page) {
    const label = ((await page.locator('[data-pb2-book-name] + span').textContent({ timeout: 2000 }).catch(() => '(no header)')) || '').trim();
    const begin = await page.locator('button', { hasText: 'Begin' }).count();
    const loading = await page.getByText('Loading scenes...').count();
    return label + ' begin=' + begin + ' loading=' + loading;
}
// The reader keeps its current page across same-route visits; if it is already open, stay on it (usability spec runs 11/12).
async function openReader(page, bookOid) {
    const target = '/#!/picture-book/v2/' + bookOid;
    if (!page.url().endsWith(target)) await page.goto(target, { timeout: T.ui });
    await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: T.ui });
    await expect(page.locator('button', { hasText: 'Begin' }).or(page.getByText(/^Page \d+ of \d+$/).first())).toBeVisible({ timeout: T.ui });
}
// Land on page 1: Begin from the cover, otherwise chevron_left back; re-decide every turn (usability spec run 13).
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
    catch (e) { throw new Error('openFirstPage: "Page 1 of N" never shown; now [' + (await readerState(page)) + ']; trail ' + trail.join(' ; ')); }
}
/** Page forward once with the header's chevron_right; returns the new "Page n of N" label. */
async function nextPage(page) {
    await page.locator('button:has(span:text-is("chevron_right"))').first().click({ timeout: 5000 });
    await page.waitForTimeout(300);
    return ((await page.getByText(/^Page \d+ of \d+$/).first().textContent({ timeout: 5000 }).catch(() => '')) || '').trim();
}

/** The step wrapper (usability spec): a throw marks the step red via expect.soft, the walk continues, the screenshot is always taken. */
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
        if (ok && test.info().errors.length > softBefore) {
            ok = false;
            const soft = test.info().errors.slice(softBefore).map(e => String(e.message || e).split('\n').filter(Boolean).slice(0, 3).join(' | ').slice(0, 300));
            note = 'FAILED (soft, step completed): ' + soft.join(' || ') + (note ? ' — ' + note : '');
        }
        try { await shot(page, file); } catch (e) { note += ' [screenshot failed: ' + (e && e.message) + ']'; }
        if (!ok) {
            for (let i = 0; i < 2 && (await page.locator('.am7-picker-overlay').count()); i++) {
                await page.locator('.am7-picker-overlay button:has(span:text-is("close"))').first().click({ timeout: 5000 }).catch(() => {});
                await page.waitForTimeout(500);
            }
            for (let i = 0; i < 3 && (await page.locator('.am7-dialog, [role="dialog"]').count()); i++) {
                const close = page.locator('.am7-dialog button:has-text("Close"), [role="dialog"] button:has-text("Close"), .am7-dialog button:has(span:text-is("close"))').last();
                if (await close.count()) await close.click({ timeout: 5000 }).catch(() => {}); else await page.keyboard.press('Escape').catch(() => {});
                await page.waitForTimeout(500);
            }
        }
    });
    const ms = Date.now() - t0;
    results.push({ n, name, ok, ms, note, file: file + '.png' });
    console.log(TAG + ' step ' + n + ' ' + (ok ? 'OK  ' : 'FAIL') + ' ' + ms + ' ms — ' + name + (note ? ' — ' + note : ''));
    return ok;
}

test.describe('PictureBook V3 P10-B — a new book renders end to end, as a user', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });
    test.use({ viewport: { width: 1600, height: 900 }, actionTimeout: T.ui });

    test.beforeAll(async ({ request }) => {
        test.setTimeout(10 * 60 * 1000);
        fs.mkdirSync(OUT_DIR, { recursive: true });
        const shared = await ensureSharedTestUser(request);
        expect(shared && shared.testUserName, 'shared test user').toBeTruthy();
        fx.route = resolveChatRoute();   // prints the authoritative `[e2e-llm] route=…` line itself
        fx.chatConfig = await ensureChatConfig(request, null, { configName: CONFIG_NAME, connectionName: CONN_NAME, requestTimeout: 900 });
        expect(fx.chatConfig, 'chat config ' + CONFIG_NAME + ' provisioned on the resolved LLM tier').toBe(CONFIG_NAME);
        await restLoginShared(request);
        const chat = await (await request.get(REST + '/path/make/auth.group/data/' + b64path('~/Chat'))).json();
        fx.user = { name: shared.testUserName, organizationId: chat.organizationId };
        expect(fx.user.organizationId, 'organizationId from ~/Chat').toBeTruthy();
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        console.log('\n' + TAG + ' ===== per-step table (timings are observations) =====');
        for (const r of results) console.log(TAG + ' | ' + String(r.n).padStart(2) + ' | ' + (r.ok ? 'OK  ' : 'FAIL') + ' | ' + String(r.ms).padStart(8) + ' ms | ' + r.name + ' | ' + r.file + ' | ' + r.note);
        console.log(TAG + ' left in place: ' + JSON.stringify({ book: fx.book, chapterBook: fx.chapterBook, doc: fx.doc, chatConfig: fx.chatConfig }));
        console.log(TAG + ' toasts seen (' + toasts.length + '): ' + JSON.stringify(toasts.slice(0, 40)));
        console.log(TAG + ' browser console errors (observation): ' + consoleErrors.length + (consoleErrors.length ? '\n  ' + consoleErrors.slice(0, 40).join('\n  ') : ''));
        try { fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify({ results, fx, toasts, consoleErrors }, null, 2)); } catch (e) { /* report only */ }
    });

    test('new book: wizard → reader Render → Render all → canvas', async ({ page, request }) => {
        test.setTimeout(5 * 60 * 60 * 1000);
        test.skip(!SD_ENABLED || !LLM_ENABLED, 'PB_SD_TESTS=1 and PB_LLM_TESTS=1 are both required: the walk renders on the SD server and extracts on the LLM');
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text().slice(0, 300)); });
        page.on('pageerror', err => consoleErrors.push('pageerror: ' + String(err && err.message).slice(0, 300)));
        // "Failed to load resource: 404" in the console carries no URL; run 1 saw ten of them. Record which
        // requests 4xx/5xx so the observation names them (the chapter book's GET /workflow 404 is expected).
        page.on('response', resp => {
            const st = resp.status();
            if (st >= 400) consoleErrors.push('HTTP ' + st + ' ' + resp.request().method() + ' ' + resp.url().replace(/^https?:\/\/[^/]+/, '').slice(0, 200));
        });
        await loginAsSharedUser(page);
        await restLoginShared(request);
        // Every toast the reader/canvas shows, captured as it appears (toasts auto-dismiss, so a later locator
        // check would miss the one Stephen saw). Every navigation below is a hash change inside the one SPA
        // document loginAsSharedUser opened, so an init script would never run again: install the observer
        // into the live document (idempotent) and re-arm it at each step in case of a full reload.
        await page.exposeFunction('__p10bToast', t => { toasts.push(String(t).slice(0, 200)); });
        const toastSpy = () => {
            if (window.__p10bSpyArmed) return;
            window.__p10bSpyArmed = true;
            const seen = new Set();
            const scan = () => document.querySelectorAll('.toast-text').forEach(el => {
                if (!seen.has(el)) { seen.add(el); if (window.__p10bToast) window.__p10bToast(el.textContent || ''); }
            });
            new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
        };
        await page.addInitScript(toastSpy);
        const armToastSpy = () => page.evaluate(toastSpy).catch(() => {});
        await armToastSpy();
        const noWorkflowToast = () => toasts.filter(t => /no workflow yet/i.test(t));

        // ── 1. Wizard from the manuscript → reader; the workflow graph exists at create ───────────────
        await step(page, 1, 'Wizard from the manuscript', async () => {
            const docPath = path.join(MEDIA_DIR, SOURCE_DOC);
            expect(fs.existsSync(docPath), 'source document ' + docPath).toBe(true);
            const dir = await ensurePath(request, 'auth.group', 'data', '~/Manuscripts');
            const docName = 'p10b-' + stamp + '-' + SOURCE_DOC;
            const doc = await jsonOf(await request.post(REST + '/model', { data: { schema: 'data.data', name: docName, groupId: dir.id, groupPath: dir.path, contentType: DOCX_MIME, dataBytesStore: fs.readFileSync(docPath).toString('base64') } }));
            expect(doc && doc.objectId, 'uploaded manuscript').toBeTruthy();
            fx.doc = { objectId: doc.objectId, name: docName };

            await page.goto('/#!/picture-book/' + doc.objectId, { timeout: T.ui });
            const generateBtn = page.locator('button:has-text("Generate Picture Book")');
            await expect(generateBtn).toBeVisible({ timeout: T.ui });
            await generateBtn.click();
            await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: T.ui });
            const dialog = page.locator('[role="dialog"]').first();
            await expect(dialog).toContainText('Source: ' + docName, { timeout: T.ui });
            const cfgField = dialog.locator('label.field-label:has-text("Chat Config") + div');
            await expect(cfgField).not.toContainText('Loading default...', { timeout: T.ui });
            await cfgField.click();
            await pickFromOverlay(page, CONFIG_NAME);
            await expect(cfgField).toContainText(CONFIG_NAME);
            await shot(page, '01a-wizard-source');

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
            // A scene with no description is not a scene (usability spec runs 7/15): Re-extract once, as a user would.
            const blurbInputs = dialog.locator('textarea[placeholder="Scene description/blurb"]');
            const describedScenes = async () => (await blurbInputs.evaluateAll(els => els.map(e => e.value.trim()))).filter(Boolean);
            let blurbs = await describedScenes();
            let reExtractNote = '';
            if (!blurbs.length) {
                await shot(page, '01b0-wizard-scenes-degenerate');
                const tRe = Date.now();
                const reResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/extract-scenes-only(\?|$)/.test(r.url()), { timeout: 120000 });
                await dialog.locator('button:has-text("Re-extract")').click();
                const reStatus = (await reResp).status();
                await expect(dialog.locator('button:has-text("Extracting...")')).toHaveCount(0, { timeout: T.extract });
                if (await extractErr.count()) throw new Error('re-extraction reported: ' + (await extractErr.first().textContent()));
                heading = await sceneList.first().textContent();
                sceneCount = parseInt((heading.match(/\((\d+)\)/) || [])[1] || '0', 10);
                blurbs = await describedScenes();
                reExtractNote = '; first extraction yielded no described scene → Re-extract (POST ' + reStatus + ') ' + Math.round((Date.now() - tRe) / 1000) + ' s → ' + sceneCount + ' scene(s)';
                extractMs = Date.now() - tExtract;
            }
            await shot(page, '01b-wizard-scenes');
            expect(blurbs.length, 'scenes with a description (of ' + sceneCount + ')' + reExtractNote).toBeGreaterThan(0);

            // Continue → POST /{work}/create-from-scenes (characters + scenes + graph + compose job) → Continue to Images → reader.
            const cfsResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/create-from-scenes$/.test(r.url()), { timeout: T.characters }).then(jsonOf).catch(() => null);
            await page.locator('button:has-text("Continue")').first().click();
            const tChars = Date.now();
            const dlg = page.getByRole('dialog');
            const empty = dlg.locator('text=No characters extracted yet.');
            const card = dlg.locator('div.font-medium.text-sm').first();
            await Promise.race([empty.waitFor({ state: 'visible', timeout: T.characters }), card.waitFor({ state: 'visible', timeout: T.characters })]).catch(() => {});
            const charCount = await dlg.locator('div.font-medium.text-sm').count();
            const charsMs = Date.now() - tChars;
            await shot(page, '01c-wizard-characters');
            expect(charCount, 'characters created (empty state: ' + (await empty.count()) + ')').toBeGreaterThan(0);

            await page.locator('button:has-text("Continue to Images")').click();
            await expect.poll(() => page.evaluate(() => location.hash), { timeout: 60000 }).toMatch(/^#!\/picture-book\/v2\/[^/]+$/);
            const bookOid = (await page.evaluate(() => location.hash)).replace(/^#!\/picture-book\/v2\//, '');
            await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: T.ui });
            await expect(page.locator('text=Picture Book —')).toHaveCount(0);
            fx.book = { objectId: bookOid, name: ((await page.locator('[data-pb2-book-name]').textContent()) || '').trim() };

            // Read-backs. THE fix under test: a wizard-made book has its workflow + one node per scene at
            // create (createFromScenesAndCompose → buildSceneGraph), so GET /workflow is 200 now, not the
            // documented 404 the usability gate still tolerates at :456-458.
            const cfs = await cfsResp;
            const pages = await pbGet(request, '/' + bookOid + '/pages');
            expect(pages.status, 'GET /pages').toBe(200);
            fx.scenes = (Array.isArray(pages.body) ? pages.body : []).map(p => ({ objectId: p.objectId, title: p.title, sceneIndex: p.sceneIndex }));
            expect(fx.scenes.length, 'pages read back = scenes extracted').toBe(sceneCount);
            const wf = await pbGet(request, '/' + bookOid + '/workflow');
            expect(wf.status, 'GET /workflow on the fresh wizard book (graph is built at create): ' + JSON.stringify(wf.body).slice(0, 200)).toBe(200);
            const nodes = (wf.body && wf.body.nodes) || [];
            expect(nodes.length, 'one workflow node per scene at create').toBe(sceneCount);
            const graph = cfs && cfs.graph;
            return 'extract ' + Math.round(extractMs / 1000) + ' s → ' + sceneCount + ' scene(s)' + reExtractNote + '; characters ' + charCount + ' in ' + Math.round(charsMs / 1000) + ' s; book ' + bookOid + ' "' + fx.book.name + '"'
                + '; create-from-scenes graph=' + (graph ? JSON.stringify(graph) : (cfs && cfs.graphError ? 'error ' + JSON.stringify(cfs.graphError) : 'absent')) + '; GET /workflow 200 nodes=' + nodes.length;
        });

        // ── 2. Reader: Render on page 1 → 202 → progress → image ─────────────────────────────────────
        await step(page, 2, 'Render page 1 in the reader', async () => {
            expect(fx.book && fx.book.objectId, 'book from step 1').toBeTruthy();
            await openReader(page, fx.book.objectId);
            await openFirstPage(page);
            await armToastSpy();
            const renderBtn = page.getByTestId('pb2-scene-render');
            await expect(renderBtn).toBeVisible({ timeout: T.ui });
            const labelBefore = ((await renderBtn.textContent()) || '').replace(/brush/, '').trim();
            const imgBefore = await page.getByTestId('pb2-scene-image').count();
            await shot(page, '02a-reader-page1-before');
            // renderScene() posts …/render?force=true — match with or without the query string.
            const renderResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/scene\/[^/]+\/render(\?|$)/.test(r.url()), { timeout: 120000 });
            const t0 = Date.now();
            await renderBtn.click();
            const rr = await renderResp;
            const rrBody = await jsonOf(rr);
            expect(rr.status(), 'POST /scene/{id}/render on the fresh book (never the "no workflow yet" 404): ' + JSON.stringify(rrBody).slice(0, 300)).toBe(202);
            expect(noWorkflowToast(), 'no "(404) This book has no workflow yet" toast').toEqual([]);
            const progress = page.getByTestId('pb2-run-progress');
            await expect(progress).toBeVisible({ timeout: 60000 });
            await shot(page, '02b-reader-render-running');
            await expect(progress).toHaveAttribute('data-run-terminal', '1', { timeout: T.render });
            const renderMs = Date.now() - t0;
            const counter = ((await page.getByTestId('pb2-run-counter').textContent().catch(() => '')) || '').trim();
            const summary = toasts.slice(-1)[0] || '';
            await page.getByTestId('pb2-run-dismiss').click().catch(() => {});
            // The page the user is looking at now shows the image, and the button offers a re-render.
            const img = page.getByTestId('pb2-scene-image');
            await expect(img, 'page 1 shows its rendered image after the run').toBeVisible({ timeout: 60000 });
            const natural = await img.evaluate(el => ({ w: el.naturalWidth, h: el.naturalHeight, complete: el.complete }));
            await expect.poll(async () => ((await renderBtn.textContent()) || '').replace(/brush/, '').trim(), { timeout: 30000 }).toBe('Re-render');
            await shot(page, '02c-reader-page1-rendered');
            expect(natural.w, 'the image element actually loaded bytes (naturalWidth)').toBeGreaterThan(0);
            // Read-back: /pages carries the image reference for scene 0.
            const pages = await pbGet(request, '/' + fx.book.objectId + '/pages');
            const p0 = (Array.isArray(pages.body) ? pages.body : []).find(p => p.objectId === fx.scenes[0].objectId) || {};
            expect(p0.imageName, 'GET /pages: scene 0 has an image').toBeTruthy();
            return 'button "' + labelBefore + '" (image before=' + imgBefore + ') → POST 202 ' + JSON.stringify({ jobId: rrBody.jobId, kind: rrBody.kind, planned: rrBody.plannedNodeCount, reused: rrBody.reusedNodeCount }) + '; terminal in ' + Math.round(renderMs / 1000) + ' s "' + counter + '"; toast "' + summary + '"; image ' + natural.w + 'x' + natural.h + ' ' + p0.imageName;
        });

        // ── 3. Render all → 202 (nodes reused, none missing) → every page has an image ───────────────
        await step(page, 3, 'Render all from the reader', async () => {
            await openReader(page, fx.book.objectId);
            await armToastSpy();
            await page.getByTestId('pb2-render-all').click();
            await expect(page.getByTestId('pb2-render-dialog')).toBeVisible({ timeout: T.ui });
            await shot(page, '03a-render-all-dialog');
            const raResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/render-all$/.test(r.url()), { timeout: 120000 });
            const t0 = Date.now();
            await page.getByTestId('pb2-render-all-start').click();
            const ra = await raResp;
            const raBody = await jsonOf(ra);
            expect(ra.status(), 'POST /render-all on the fresh book: ' + JSON.stringify(raBody).slice(0, 300)).toBe(202);
            expect(noWorkflowToast(), 'no "(404) This book has no workflow yet" toast').toEqual([]);
            // requestRenderAll: every scene already has its node from create (nodesMaterialized 0, scenesWithoutNode
            // empty); the plan reuses what step 2 rendered (page 1's node and its producers) instead of re-rendering it.
            expect(raBody.nodesMaterialized, 'no node is created at Render all — they were built at create').toBe(0);
            expect(raBody.sceneCount, 'Render all plans every scene').toBe(fx.scenes.length);
            expect(raBody.scenesWithoutNode || [], 'no scene is left without a node').toEqual([]);
            expect(raBody.reusedNodeCount, 'page 1 (rendered in step 2) is reused, not re-rendered').toBeGreaterThanOrEqual(1);
            const progress = page.getByTestId('pb2-run-progress');
            await expect(progress).toBeVisible({ timeout: 60000 });
            await shot(page, '03b-render-all-running');
            await expect(progress).toHaveAttribute('data-run-terminal', '1', { timeout: T.renderAll });
            const renderMs = Date.now() - t0;
            const counter = ((await page.getByTestId('pb2-run-counter').textContent().catch(() => '')) || '').trim();
            const summary = toasts.slice(-1)[0] || '';
            await page.getByTestId('pb2-run-dismiss').click().catch(() => {});
            // Page through the whole book as a reader would: every page shows an image.
            await openFirstPage(page);
            const withImage = [], without = [];
            for (let i = 0; i < fx.scenes.length; i++) {
                const label = ((await page.getByText(/^Page \d+ of \d+$/).first().textContent().catch(() => '')) || '').trim();
                const has = await page.getByTestId('pb2-scene-image').isVisible().catch(() => false);
                (has ? withImage : without).push(label || ('#' + (i + 1)));
                if (i === 1) await shot(page, '03c-reader-page2');
                if (i < fx.scenes.length - 1) await nextPage(page);
            }
            await shot(page, '03d-reader-last-page');
            // Read-back: every /pages entry carries an image reference.
            const pages = await pbGet(request, '/' + fx.book.objectId + '/pages');
            const list = Array.isArray(pages.body) ? pages.body : [];
            const missing = list.filter(p => !p.imageName).map(p => p.sceneIndex);
            expect(without, 'pages without an image in the reader after Render all').toEqual([]);
            expect(missing, 'GET /pages entries without an image after Render all').toEqual([]);
            return 'POST 202 ' + JSON.stringify({ jobId: raBody.jobId, sceneCount: raBody.sceneCount, nodesMaterialized: raBody.nodesMaterialized, planned: raBody.plannedNodeCount, reused: raBody.reusedNodeCount, pinned: raBody.pinnedNodeCount }) + '; terminal in ' + Math.round(renderMs / 1000) + ' s "' + counter + '"; toast "' + summary + '"; pages with image ' + withImage.length + '/' + fx.scenes.length;
        });

        // ── 4. Workflow canvas of the wizard book: nodes, no empty state ─────────────────────────────
        await step(page, 4, 'Workflow canvas shows the graph', async () => {
            await page.goto('/#!/picture-book/' + fx.book.objectId + '/workflow', { timeout: T.ui });
            await armToastSpy();
            const nodes = page.locator('[data-node-id]');
            await expect(nodes.first()).toBeVisible({ timeout: 60000 });
            const nodeCount = await nodes.count();
            await shot(page, '04-canvas-graph');
            expect(await page.locator('.pb-wf-empty').count(), 'no empty state on a book with a graph').toBe(0);
            expect(nodeCount, 'canvas nodes >= scenes').toBeGreaterThanOrEqual(fx.scenes.length);
            const wf = await pbGet(request, '/' + fx.book.objectId + '/workflow');
            return 'canvas nodes ' + nodeCount + '; GET /workflow nodes ' + (((wf.body && wf.body.nodes) || []).length);
        });

        // ── 5. Canvas empty state on a scene-less book: the two buttons ──────────────────────────────
        await step(page, 5, 'Canvas empty state offers reader and Build graph', async () => {
            // REST short-cut, recorded: the wizard can no longer make a book without a graph, so the empty
            // state is reached through POST /chapter (a standalone, scene-less book).
            const slug = 'p10b-empty-' + stamp;
            const created = await request.post(PB_REST + '/chapter', { data: { slug, title: 'P10-B empty ' + stamp }, timeout: 180000 });
            const cb = await jsonOf(created);
            expect(created.status(), 'POST /chapter: ' + JSON.stringify(cb).slice(0, 200)).toBe(200);
            fx.chapterBook = { objectId: cb.bookObjectId, slug };
            const wf0 = await pbGet(request, '/' + cb.bookObjectId + '/workflow');
            await page.goto('/#!/picture-book/' + cb.bookObjectId + '/workflow', { timeout: T.ui });
            await armToastSpy();
            const empty = page.locator('.pb-wf-empty');
            await expect(empty).toBeVisible({ timeout: 60000 });
            // renderEmptyState: [icon span, title div, body div, scenes?, actions] — the title is the first div child.
            const title0 = ((await empty.locator(':scope > div').first().textContent().catch(() => '')) || '').trim();
            const openBtn = empty.locator('[data-wf-open-wizard]');
            const buildBtn = empty.locator('[data-wf-build-graph]');
            await expect(openBtn, 'Open in reader button').toBeVisible({ timeout: T.ui });
            await expect(buildBtn, 'Build graph button').toBeVisible({ timeout: T.ui });
            await expect(openBtn).toHaveAttribute('data-wf-open-target', 'reader');
            await expect(openBtn).toHaveText(/Open in reader to render/);
            await expect(buildBtn).toHaveText(/Build graph/);
            expect(await empty.locator('button').count(), 'exactly the two actions').toBe(2);
            await shot(page, '05a-canvas-empty');
            // Build graph on a scene-less book: POST /graph/build answers, the toast says there is nothing to build.
            const bgResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/graph\/build$/.test(r.url()), { timeout: 120000 });
            await buildBtn.click();
            const bg = await bgResp;
            const bgBody = await jsonOf(bg);
            const bgToast = await waitForToast(page, /Workflow graph|No extracted scenes|Build graph failed/, 60000);
            await shot(page, '05b-canvas-build-graph');
            expect(bg.status(), 'POST /graph/build: ' + JSON.stringify(bgBody).slice(0, 200)).toBe(200);
            expect(bgToast, 'toast on a scene-less book').toMatch(/No extracted scenes/);
            const wf1 = await pbGet(request, '/' + cb.bookObjectId + '/workflow');
            // Open in reader → the V3 reader route for this book.
            const empty2 = page.locator('.pb-wf-empty');
            await expect(empty2).toBeVisible({ timeout: 60000 });
            const title1 = ((await empty2.locator(':scope > div').first().textContent().catch(() => '')) || '').trim();
            await empty2.locator('[data-wf-open-wizard]').click();
            await expect.poll(() => page.evaluate(() => location.hash), { timeout: T.ui }).toBe('#!/picture-book/v2/' + cb.bookObjectId);
            await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: T.ui });
            await shot(page, '05c-reader-from-canvas');
            return 'chapter book ' + cb.bookObjectId + '; GET /workflow before ' + wf0.status + ' → after Build graph ' + wf1.status + ' nodes=' + (((wf1.body && wf1.body.nodes) || []).length) + '; empty state "' + title0 + '" → "' + title1 + '"; build DTO ' + JSON.stringify(bgBody).slice(0, 120) + '; toast "' + bgToast.trim() + '"';
        });

        expect(noWorkflowToast(), 'the "(404) This book has no workflow yet" toast never appeared during the walk').toEqual([]);
    });
});
