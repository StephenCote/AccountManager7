/**
 * P10-C 1 — "ChapBook pages are random landscapes, nothing based on the poem text" (Stephen, 2026-11-04).
 *
 * Repro + proof, as the shared test user, with NO explicit sdPrompt anywhere: the book is created from the
 * real e2e/fixtures/winter_1.docx exactly as a user would (binary upload → POST /poems import → POST /create),
 * one page is rendered with a bare body, and then every link of the prompt chain is read back in order:
 *
 *   poem.text (Tika extraction) → scene.poemStanza → scene.sdPrompt (createChapBookScene) → CHAPBOOK_PAGE
 *   composition promptOverride → the render's artifact generatorRequest prompt → the image itself.
 *
 * The "landscape, <title>, <mood> atmosphere, painterly, soft light, wide natural vista" shape is the
 * stanza-FREE fallback createChapBookScene stores when generateLandscapePrompt returns nothing. A page rendered
 * on that shape IS the reported symptom (a generic vista with no poem content), so the assertion is: no stored
 * prompt, no composition prompt and no rendered prompt starts with "landscape, ", and the rendered prompt carries
 * at least one concrete image from the stanza.
 *
 * Two creates, because which olio.llm.chatConfig the create resolves is the whole difference on this stack:
 *   DEFAULT  — no chatConfig in the body → ChapBookUtil.resolveDefaultChatConfig → the shared-library
 *              contentAnalysis (whatever the deployment's library config points at).
 *   NAMED    — chatConfig:'contentAnalysis' (what the Ux create dialog sends) → ChatUtil.resolveConfig →
 *              the user's own ~/Chat/contentAnalysis first, the library one second.
 * Both are read and reported; the fix must make the user-visible outcome honest and the UI walk below green.
 *
 * LIVE (LLM + SD): run with
 *   PB_SD_TESTS=1 PB_LLM_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/chapBookPromptNoOverride.spec.js --project=chromium --workers=1
 * Timings are logged, never asserted.
 */
import { test, expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { ensureSharedTestUser, resolveChatRoute, ensureChatConfig, deleteObject } from './helpers/api.js';
import {
    restLoginShared, loginAsSharedUser, ensurePoemsGroup, bookPages, sceneByObjectId,
    REST, CB_REST, PB_REST
} from './helpers/chapbook.js';

const LIVE = !!process.env.PB_SD_TESTS && !!process.env.PB_LLM_TESTS;
const TEST_TIMEOUT_MS = 45 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 60 * 60 * 1000;

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const DOCX_FIXTURE = path.resolve(SPEC_DIR, 'fixtures/winter_1.docx');
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results/p10-c-1');
const REAL_DOCX_CT = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

// The stanza-free fallback shape (ChapBookUtil.createChapBookScene) — the discriminator the recovery path uses too.
const FALLBACK_RE = /^landscape, /i;
// Concrete imagery from winter_1's STANZAS (POEM_2 in helpers/chapbook.js): a real LLM prompt built from a stanza
// carries at least one of these. Deliberately NO word from the book title / poem title / mood ("Winter",
// "Sorceror's Reflection", "poetic"), because the fallback is built from exactly those — an earlier draft matched
// "winter" and so scored the fallback as stanza-based.
const STANZA_IMAGERY = /pristine|cobalt|charcoal|canvassed|silver vein|icy|sheen|sorcer|spell|hearth|ochre|ember|ebon|cinder|crone|tome|snow/i;
// Throw-away fixture for the deterministic Re-render repro: a chatConfig in the shared user's ~/Chat whose
// connection points at 127.0.0.1:9 inside the Tomcat container — nothing listens there, so the landscape-prompt
// LLM step is refused immediately and createChapBookScene stores the fallback on every page, on ANY stack.
const DEAD_CFG = '000-p10c1-dead';
const DEAD_CONN = '000-p10c1-dead-conn';
const DEAD_URL = 'http://127.0.0.1:9';
// Client toast wordings (chapBook.js) the Re-render walk discriminates between.
const DEGRADED_MSG = 'Rendered using the stored prompt — the LLM prompt step was unavailable (no usable chat config or the LLM is unreachable).';
const RERENDER_SUCCESS = 'Page re-rendered';

async function jsonOf(r, what) {
    const text = await r.text();
    expect(r.ok(), what + ' failed: HTTP ' + r.status() + ' ' + text).toBe(true);
    return JSON.parse(text);
}

async function uploadWinterDocx(request, orgId) {
    const dataDir = await jsonOf(await request.get(REST + '/path/make/auth.group/data/B64-'
        + Buffer.from('~/Data').toString('base64').replace(/=/g, '%3D')), 'ensure ~/Data');
    const docxBytes = fs.readFileSync(DOCX_FIXTURE);
    const dataName = 'p10c1-winter-' + Date.now().toString(36) + '.docx';
    const rec = await jsonOf(await request.post(REST + '/model', {
        data: { schema: 'data.data', name: dataName, groupId: dataDir.id, contentType: REAL_DOCX_CT,
            dataBytesStore: docxBytes.toString('base64') }
    }), 'data.data create');
    expect(rec.objectId, 'uploaded data.data objectId').toBeTruthy();
    const poemTitle = 'P10C1 Winter ' + Date.now().toString(36);
    const imp = await jsonOf(await request.post(CB_REST + '/poems', {
        data: { sources: [{ type: 'data.data', objectId: rec.objectId, title: poemTitle }] }
    }), 'POST /poems');
    expect(!imp.errors || imp.errors.length === 0, 'import errors: ' + JSON.stringify(imp.errors)).toBe(true);
    expect(imp.poems && imp.poems.length === 1, 'one imported poem: ' + JSON.stringify(imp)).toBe(true);
    const poemOid = imp.poems[0].objectId;
    // olio.cb.poem defaults omit `text` — project it (memory: feedback-cb-poem-text-projection).
    const q = await jsonOf(await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type: 'olio.cb.poem', cache: false,
            request: ['id', 'objectId', 'name', 'title', 'text', 'theme', 'mood', 'keywords'],
            fields: [{ name: 'objectId', comparator: 'equals', value: poemOid },
                { name: 'organizationId', comparator: 'equals', value: orgId }], recordCount: 1 }
    }), 'poem read');
    const poem = q.results && q.results[0];
    expect(poem, 'imported poem readable').toBeTruthy();
    return { poemOid, poemTitle, text: poem.text || '', poem };
}

async function createBook(request, slug, title, poemOid, chatConfigName) {
    const data = { slug, title, poemObjectIds: [poemOid], maxLinesPerPage: 12 };
    if (chatConfigName) data.chatConfig = chatConfigName;
    const started = Date.now();
    const b = await jsonOf(await request.post(CB_REST + '/create', { data, timeout: REQUEST_TIMEOUT_MS }), 'POST /create ' + slug);
    const oid = b.bookObjectId || b.objectId;
    console.log('[p10c1 create ' + (chatConfigName ? 'NAMED' : 'DEFAULT') + '] ' + (Date.now() - started) + 'ms book=' + oid);
    expect(oid, 'bookObjectId').toBeTruthy();
    return oid;
}

// The page's CHAPBOOK_PAGE composition (PbServiceFacade.getSceneComposition nests the row under `composition`);
// null while the page has none yet — a fresh create writes no composition (404 "set its prompt first").
async function composition(request, bookOid, sceneOid) {
    const r = await request.get(PB_REST + '/' + bookOid + '/scene/' + sceneOid + '/composition');
    if (r.status() === 404) return null;
    const body = await jsonOf(r, 'GET composition');
    return body.composition || body;
}

async function readChain(request, bookOid, label) {
    const pages = await bookPages(request, bookOid);
    expect(pages.length, label + ': book has pages').toBeGreaterThanOrEqual(1);
    const out = [];
    for (const p of pages) {
        const scene = await sceneByObjectId(request, p.objectId);
        const comp = await composition(request, bookOid, p.objectId);
        const row = {
            sceneOid: p.objectId, sceneIndex: p.sceneIndex,
            stanzaLen: (scene.poemStanza || '').length,
            sdPrompt: scene.sdPrompt || '', promptLocked: !!scene.promptLocked,
            compPrompt: comp ? (comp.promptOverride || '') : null, compLocked: comp ? !!comp.promptLocked : null,
            compSource: comp ? (comp.source || null) : null, compUserEdited: comp ? !!comp.userEdited : null
        };
        console.log('[p10c1 ' + label + ' page ' + p.sceneIndex + '] stanzaLen=' + row.stanzaLen
            + ' promptLocked=' + row.promptLocked + ' sdPrompt=' + JSON.stringify(row.sdPrompt.slice(0, 160))
            + ' comp=' + (comp ? ('{locked=' + row.compLocked + ' source=' + row.compSource + ' userEdited=' + row.compUserEdited
                + ' prompt=' + JSON.stringify((row.compPrompt || '').slice(0, 160)) + '}') : 'none'));
        out.push(row);
    }
    return { pages, rows: out };
}

// Browser session as the shared user (helpers/chapbook.js loginAsSharedUser: REST login + WS stub) plus a
// cumulative toast capture installed BEFORE navigation, so a 5 s auto-dismissed toast cannot be missed.
async function loginSharedWithToasts(page) {
    await page.addInitScript(() => {
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
    await loginAsSharedUser(page);
}
const capturedToasts = (page) => page.evaluate(() => window.__toasts || []);

// Record every per-scene generate POST body the page sends — the proof of WHAT the review card asked for.
function captureGeneratePosts(page) {
    const posts = [];
    page.on('request', (req) => {
        if (req.method() === 'POST' && /\/olio\/chap-book\/scene\/[^/]+\/generate$/.test(req.url())) {
            let body = null;
            try { body = JSON.parse(req.postData() || 'null'); } catch (e) { body = null; }
            posts.push({ url: req.url(), body });
        }
    });
    return () => posts;
}

async function findInChat(request, type, name, orgId, chatGroupId) {
    const r = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, cache: false, recordCount: 1,
            fields: [{ name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId },
                { name: 'groupId', comparator: 'equals', value: Number(chatGroupId) }],
            request: ['id', 'objectId', 'name'] }
    });
    const b = await r.json().catch(() => null);
    return (b && b.results && b.results.length) ? b.results[0] : null;
}

// The LANDSCAPE node of page `sceneIndex` and what its selected artifact was rendered with.
async function readPageRender(request, bookOid, sceneIndex, label) {
    const wf = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/workflow'), 'GET workflow');
    const node = (wf.nodes || []).find(n => n.nodeType === 'LANDSCAPE' && n.sceneIndex === sceneIndex);
    expect(node, label + ': LANDSCAPE node for page ' + sceneIndex + ' in '
        + JSON.stringify((wf.nodes || []).map(n => n.nodeType + '#' + n.sceneIndex))).toBeTruthy();
    return readNodePrompt(request, bookOid, node.objectId, label);
}

async function awaitJob(request, jobId) {
    for (;;) {
        const j = await jsonOf(await request.get(REST + '/job/' + jobId), 'GET job');
        if (j.terminal) return j;
        await new Promise(r => setTimeout(r, 3000));
    }
}

// Render one page with a BARE body — no sdPrompt, no chatConfig — exactly what the Ux review card sends when the
// stored prompt is blank, and what any caller gets when it leaves prompt resolution to the server.
async function renderBare(request, bookOid, sceneOid, label) {
    const started = Date.now();
    const r = await request.post(CB_REST + '/scene/' + sceneOid + '/generate', {
        data: { schema: 'olio.pictureBookRequest' }, timeout: REQUEST_TIMEOUT_MS
    });
    const text = await r.text();
    console.log('[p10c1 ' + label + ' render] HTTP ' + r.status() + ' in ' + (Date.now() - started) + 'ms → ' + text);
    expect([200, 202].includes(r.status()), label + ' render HTTP ' + r.status() + ' ' + text).toBe(true);
    let body = JSON.parse(text);
    if (r.status() === 202) {
        const job = await awaitJob(request, body.jobId);
        console.log('[p10c1 ' + label + ' render] polled job → ' + JSON.stringify(job));
        expect(job.status).toBe('completed');
    }
    const read = await readNodePrompt(request, bookOid, body.nodeObjectId, label);
    console.log('[p10c1 ' + label + ' render] rendered=' + body.rendered
        + ' llmUnavailable=' + body.llmUnavailable + ' llmDegraded=' + body.llmDegraded
        + ' nodeStatus=' + body.nodeStatus);
    return Object.assign({ body }, read);
}

// The prompt SD was actually sent: the selected landscape artifact's persisted generatorRequest (a JSON
// string — PbServiceFacade.artifactView puts the column through as-is) and the node's promptText.
async function readNodePrompt(request, bookOid, nodeOid, label) {
    const nv = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/workflow/node/' + nodeOid), 'GET node');
    const chain = (nv.artifacts && nv.artifacts.landscape) || [];
    expect(chain.length, label + ': a landscape artifact on node ' + nodeOid).toBeGreaterThanOrEqual(1);
    const sel = chain.find(a => a.selected) || chain[chain.length - 1];
    const art = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/artifact/' + sel.objectId), 'GET artifact');
    let gen = art.generatorRequest || {};
    if (typeof gen === 'string') { try { gen = JSON.parse(gen); } catch (e) { gen = { raw: gen }; } }
    const renderedPrompt = String(gen.prompt || gen.positivePrompt || (gen.request && gen.request.prompt) || '');
    console.log('[p10c1 ' + label + ' node] promptText=' + JSON.stringify((nv.promptText || '').slice(0, 200))
        + ' artifact=' + sel.objectId + ' reviewStatus=' + sel.reviewStatus);
    console.log('[p10c1 ' + label + ' artifact] generatorRequest keys=' + Object.keys(gen).join(',')
        + ' prompt=' + JSON.stringify(renderedPrompt.slice(0, 300)));
    return { node: nv, artifact: art, artifactObjectId: sel.objectId, renderedPrompt, promptText: nv.promptText || '' };
}

async function saveImage(request, bookOid, sceneOid, file) {
    const after = (await bookPages(request, bookOid)).find(p => p.objectId === sceneOid);
    expect(after && after.imageName, 'page carries its image').toBeTruthy();
    const url = REST.replace(/\/rest$/, '') + '/media/Development/data.data' + after.imageGroupPath + '/' + after.imageName;
    const r = await request.get(url);
    expect(r.ok(), 'image GET ' + url + ' → ' + r.status()).toBe(true);
    const bytes = await r.body();
    fs.mkdirSync(OUT_DIR, { recursive: true });
    const out = path.join(OUT_DIR, file);
    fs.writeFileSync(out, bytes);
    console.log('[p10c1 image] ' + out + ' (' + bytes.length + ' bytes)');
    return out;
}

test.describe('P10-C 1 — ChapBook page prompt with no override', () => {

    test('REST: create from winter_1.docx, render a page bare, read the whole prompt chain', async ({ request }) => {
        test.skip(!LIVE, 'set PB_SD_TESTS=1 PB_LLM_TESTS=1 to run the live LLM+SD prompt-chain test');
        test.setTimeout(TEST_TIMEOUT_MS);
        const route = await resolveChatRoute();
        console.log('[p10c1] e2e LLM route=' + route.route + ' server=' + route.serverUrl);

        await ensureSharedTestUser(request);
        await restLoginShared(request);
        const { organizationId } = await ensurePoemsGroup(request);

        // 1. The poem text really is the poem (extraction, not the symptom).
        const { poemOid, poemTitle, text } = await uploadWinterDocx(request, organizationId);
        console.log('[p10c1 poem] len=' + text.length + ' head=' + JSON.stringify(text.slice(0, 120)));
        expect(text.length, 'poem text extracted').toBeGreaterThan(0);
        expect(STANZA_IMAGERY.test(text), 'poem text carries the winter imagery').toBe(true);

        // 2. DEFAULT create (no chatConfig) and NAMED create (what the Ux dialog sends).
        const stamp = Date.now().toString(36);
        const bookDefault = await createBook(request, 'p10c1-default-' + stamp, poemTitle + ' (default)', poemOid, null);
        const bookNamed = await createBook(request, 'p10c1-named-' + stamp, poemTitle + ' (named)', poemOid, 'contentAnalysis');

        const def = await readChain(request, bookDefault, 'DEFAULT');
        const named = await readChain(request, bookNamed, 'NAMED');
        for (const row of def.rows.concat(named.rows)) {
            expect(row.stanzaLen, 'page ' + row.sceneIndex + ' carries its stanza').toBeGreaterThan(0);
        }

        // 3. Render page 0 of each with a bare body and read what SD was actually asked for.
        const rDef = await renderBare(request, bookDefault, def.pages[0].objectId, 'DEFAULT');
        const imgDef = await saveImage(request, bookDefault, def.pages[0].objectId, 'default-page0.png');
        const rNamed = await renderBare(request, bookNamed, named.pages[0].objectId, 'NAMED');
        const imgNamed = await saveImage(request, bookNamed, named.pages[0].objectId, 'named-page0.png');

        const summary = {
            route: route.route, poemTextLen: text.length,
            default: { book: bookDefault, rows: def.rows, render: rDef.body, renderedPrompt: rDef.renderedPrompt, image: imgDef },
            named: { book: bookNamed, rows: named.rows, render: rNamed.body, renderedPrompt: rNamed.renderedPrompt, image: imgNamed }
        };
        fs.mkdirSync(OUT_DIR, { recursive: true });
        fs.writeFileSync(path.join(OUT_DIR, 'chain.json'), JSON.stringify(summary, null, 2));

        // 4. The product claim under test: a page rendered with no override must be rendered on a prompt built
        //    from its stanza — never on the stanza-free "landscape, " fallback — on BOTH resolution routes, and
        //    when the LLM step could not run the render must say so (llmDegraded/llmUnavailable), never pass
        //    the fallback off as a normal render.
        for (const [label, r, rows] of [['DEFAULT', rDef, def.rows], ['NAMED', rNamed, named.rows]]) {
            const stored = rows[0].sdPrompt;
            const comp = rows[0].compPrompt || '';
            console.log('[p10c1 ' + label + ' verdict] storedFallback=' + FALLBACK_RE.test(stored)
                + ' compFallback=' + FALLBACK_RE.test(comp) + ' renderedFallback=' + FALLBACK_RE.test(r.renderedPrompt)
                + ' renderedHasImagery=' + STANZA_IMAGERY.test(r.renderedPrompt)
                + ' llmDegraded=' + r.body.llmDegraded + ' llmUnavailable=' + r.body.llmUnavailable);
            expect(FALLBACK_RE.test(r.renderedPrompt), label + ': rendered prompt is the stanza-free fallback: ' + r.renderedPrompt).toBe(false);
            expect(STANZA_IMAGERY.test(r.renderedPrompt), label + ': rendered prompt carries no stanza imagery: ' + r.renderedPrompt).toBe(true);
            expect(FALLBACK_RE.test(stored), label + ': stored scene.sdPrompt is the fallback shape: ' + stored).toBe(false);
        }
    });

    test('UI: create dialog → review → render page 1 with no prompt edit; the page is the poem', async ({ page, request }) => {
        test.skip(!LIVE, 'set PB_SD_TESTS=1 PB_LLM_TESTS=1 to run the live LLM+SD UI walk');
        test.setTimeout(TEST_TIMEOUT_MS);

        await ensureSharedTestUser(request);
        await restLoginShared(request);
        const { organizationId } = await ensurePoemsGroup(request);
        const { poemOid, poemTitle } = await uploadWinterDocx(request, organizationId);
        await request.get(REST + '/logout');

        await loginSharedWithToasts(page);
        await page.goto('/#!/chap-book', { timeout: 30000 });
        // Poem library table (chapBook.js renderPoemLibrary): tick the imported poem's row checkbox, then the
        // "Create ChapBook (n)" primary action opens the create dialog.
        const row = page.locator('table tbody tr').filter({ hasText: poemTitle }).first();
        await expect(row, 'imported poem listed in the library').toBeVisible({ timeout: 30000 });
        await row.locator('input[type="checkbox"]').check();
        await page.locator('button:has-text("Create ChapBook (")').click();
        const dialog = page.locator('div.fixed').filter({ has: page.locator('h3:has-text("Create ChapBook")') }).first();
        await expect(dialog, 'create dialog open').toBeVisible({ timeout: 15000 });
        // Typing the title derives the slug (oninput → slugify); the Chat Config row shows what the dialog resolved.
        await dialog.locator('input[type="text"]').first().fill('P10C1 UI ' + Date.now().toString(36));
        const slug = await dialog.locator('input[type="text"]').nth(1).inputValue();
        const chatConfigShown = (await dialog.locator('label:has-text("Chat Config") + div span').first().textContent() || '').trim();
        console.log('[p10c1 ui] slug=' + slug + ' chatConfig shown=' + JSON.stringify(chatConfigShown));
        fs.mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(OUT_DIR, 'ui-1-create-dialog.png'), fullPage: true });
        await dialog.locator('button:text-is("Create")').click();

        // Creation runs as a job (startCreateChapBook → pollJob); on completion the Ux routes to the reader.
        await page.waitForFunction(() => /chap-book\/read\//.test(window.location.hash), null, { timeout: 20 * 60 * 1000 });
        const bookOid = await page.evaluate(() => window.location.hash.split('/').pop());
        console.log('[p10c1 ui] book=' + bookOid);
        await page.screenshot({ path: path.join(OUT_DIR, 'ui-2-after-create.png'), fullPage: true });

        await restLoginShared(request);
        const chain = await readChain(request, bookOid, 'UI');

        // Review view: the first page card's "Re-render this page" with its prompt textarea untouched.
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
        const promptBox = page.locator('textarea.cb-scene-prompt').first();
        await expect(promptBox, 'page 1 prompt textarea').toBeVisible({ timeout: 60000 });
        const shownPrompt = await promptBox.inputValue();
        console.log('[p10c1 ui] page 1 prompt shown to the user=' + JSON.stringify(shownPrompt.slice(0, 300)));
        const getPosts = captureGeneratePosts(page);
        await page.locator('[data-testid="cb-page-rerender"]').first().click();
        const img = page.locator('img.cb-scene-image').first();
        await expect(img, 'page 1 image on the card').toBeVisible({ timeout: 30 * 60 * 1000 });
        await page.waitForFunction(() => { const i = document.querySelector('img.cb-scene-image'); return i && i.complete && i.naturalWidth > 0; }, null, { timeout: 10 * 60 * 1000 });
        await page.screenshot({ path: path.join(OUT_DIR, 'ui-3-rendered.png'), fullPage: true });
        console.log('[p10c1 ui] generate POST body=' + JSON.stringify((getPosts()[0] || {}).body) + ' toasts=' + JSON.stringify(await capturedToasts(page)));

        // What that click actually rendered: the LANDSCAPE node for page 1 and its selected artifact.
        const idx0 = chain.pages[0].sceneIndex != null ? chain.pages[0].sceneIndex : 0;
        const r = await readPageRender(request, bookOid, idx0, 'UI');
        await saveImage(request, bookOid, chain.pages[0].objectId, 'ui-page0.png');

        expect(FALLBACK_RE.test(chain.rows[0].sdPrompt), 'UI-created page 1 stored the stanza-free fallback: ' + chain.rows[0].sdPrompt).toBe(false);
        expect(FALLBACK_RE.test(r.renderedPrompt), 'UI re-render used the stanza-free fallback: ' + r.renderedPrompt).toBe(false);
        expect(STANZA_IMAGERY.test(r.renderedPrompt), 'UI re-render prompt carries no stanza imagery: ' + r.renderedPrompt).toBe(true);
    });

    // Deterministic on any stack: the book is created with a chatConfig that can never answer, so every page
    // stores the stanza-free fallback (unlocked, no composition) — the state a book is left in whenever the
    // landscape LLM was down at create time. The user then does the natural thing on the review card: clicks
    // "Re-render" WITHOUT touching the prompt. What that click sends, what the server persists as a result, what
    // SD was asked for, and what the user is told are all read back. The user's own `contentAnalysis` (the by-name
    // default) points at the live LLM, so a render that leaves prompt resolution to the server CAN recover the
    // stanza prompt; nothing in this walk supplies a prompt.
    test('UI: Re-render of a fallback-stored page, prompt untouched — must not lock the fallback in as a user edit', async ({ page, request }) => {
        test.skip(!LIVE, 'set PB_SD_TESTS=1 PB_LLM_TESTS=1 to run the live LLM+SD Re-render walk');
        test.setTimeout(TEST_TIMEOUT_MS);

        await ensureSharedTestUser(request);
        await restLoginShared(request);
        const { organizationId } = await ensurePoemsGroup(request);
        const chatDir = await jsonOf(await request.get(REST + '/path/make/auth.group/data/B64-'
            + Buffer.from('~/Chat').toString('base64').replace(/=/g, '%3D')), 'ensure ~/Chat');
        const { poemOid, poemTitle } = await uploadWinterDocx(request, organizationId);

        let cleanupError = null;
        try {
            const cfgName = await ensureChatConfig(request, organizationId, {
                configName: DEAD_CFG, connectionName: DEAD_CONN, serverUrl: DEAD_URL,
                dialect: 'ollama', upstream: 'ollama', requestTimeout: 30
            });
            expect(cfgName, 'dead chatConfig fixture').toBe(DEAD_CFG);
            await restLoginShared(request);
            const bookOid = await createBook(request, 'p10c1-dead-' + Date.now().toString(36), poemTitle + ' (dead cfg)', poemOid, DEAD_CFG);

            // Premise: the create fell back on every page (unlocked, no composition yet).
            const before = await readChain(request, bookOid, 'DEAD-before');
            for (const row of before.rows) {
                expect(FALLBACK_RE.test(row.sdPrompt), 'page ' + row.sceneIndex + ' did not store the fallback (fixture not dead?): ' + row.sdPrompt).toBe(true);
                expect(row.promptLocked, 'page ' + row.sceneIndex + ' is not locked at create').toBe(false);
            }
            await request.get(REST + '/logout');

            await loginSharedWithToasts(page);
            const getPosts = captureGeneratePosts(page);
            await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
            const promptBox = page.locator('textarea.cb-scene-prompt').first();
            await expect(promptBox, 'page 1 prompt textarea').toBeVisible({ timeout: 60000 });
            const shownPrompt = await promptBox.inputValue();
            const regenVisible = await page.locator('[data-testid="cb-page-regenerate"]').first().isVisible().catch(() => false);
            console.log('[p10c1 dead] page 1 prompt shown=' + JSON.stringify(shownPrompt.slice(0, 200)) + ' regenerateAffordance=' + regenVisible);
            fs.mkdirSync(OUT_DIR, { recursive: true });
            await page.screenshot({ path: path.join(OUT_DIR, 'dead-1-review-before.png'), fullPage: true });

            const rerender = page.locator('[data-testid="cb-page-rerender"]').first();
            await expect(rerender, 'page 1 Re-render button').toBeEnabled({ timeout: 10000 });
            await rerender.click();
            // Terminal outcome of the click = either toast wording; the render itself takes as long as SD takes.
            await expect.poll(async () => (await capturedToasts(page)).some(t => t === DEGRADED_MSG || t === RERENDER_SUCCESS
                || /^Re-render failed/.test(t) || /^Still no usable prompt/.test(t)), {
                message: 'no terminal Re-render toast', timeout: 30 * 60 * 1000, intervals: [1000]
            }).toBe(true);
            const toasts = await capturedToasts(page);
            const posts = getPosts();
            console.log('[p10c1 dead] generate POST body=' + JSON.stringify((posts[0] || {}).body) + ' toasts=' + JSON.stringify(toasts));
            // The card must now show the prompt the new image was actually made from — the server replaced the
            // placeholder, and the card re-reads the scene after a render it sent no override for.
            await expect.poll(async () => await promptBox.inputValue(), {
                message: 'page 1 prompt textarea still shows the placeholder after the re-render', timeout: 30000
            }).not.toMatch(FALLBACK_RE);
            const shownAfter = await promptBox.inputValue();
            console.log('[p10c1 dead] page 1 prompt shown AFTER re-render=' + JSON.stringify(shownAfter.slice(0, 200)));
            await page.screenshot({ path: path.join(OUT_DIR, 'dead-2-review-after.png'), fullPage: true });

            await restLoginShared(request);
            const after = await readChain(request, bookOid, 'DEAD-after');
            const idx0 = before.pages[0].sceneIndex != null ? before.pages[0].sceneIndex : 0;
            const r = await readPageRender(request, bookOid, idx0, 'DEAD');
            await saveImage(request, bookOid, before.pages[0].objectId, 'dead-page0.png');
            fs.writeFileSync(path.join(OUT_DIR, 'dead-chain.json'), JSON.stringify({
                book: bookOid, before: before.rows, after: after.rows, post: posts[0] && posts[0].body, toasts, renderedPrompt: r.renderedPrompt
            }, null, 2));

            const a0 = after.rows[0];
            const sentPrompt = String((posts[0] && posts[0].body && posts[0].body.sdPrompt) || '');
            console.log('[p10c1 dead verdict] sentFallback=' + FALLBACK_RE.test(sentPrompt) + ' sentChatConfig=' + JSON.stringify(posts[0] && posts[0].body && posts[0].body.chatConfig)
                + ' afterLocked=' + a0.promptLocked + ' compLocked=' + a0.compLocked + ' compSource=' + a0.compSource + ' compUserEdited=' + a0.compUserEdited
                + ' renderedFallback=' + FALLBACK_RE.test(r.renderedPrompt) + ' renderedHasImagery=' + STANZA_IMAGERY.test(r.renderedPrompt));

            // The product claim: an untouched fallback is not the user's prompt. The click must not send it as an
            // override, the server must not persist it as a locked USER edit, SD must not be asked for it, and the
            // user must not be told the page re-rendered normally on it.
            expect(posts.length, 'the click sent a generate POST').toBeGreaterThan(0);
            expect(FALLBACK_RE.test(sentPrompt), 'Re-render sent the stanza-free fallback as sdPrompt: ' + sentPrompt).toBe(false);
            expect(a0.promptLocked && FALLBACK_RE.test(a0.sdPrompt), 'the fallback was locked in on the scene: ' + a0.sdPrompt).toBe(false);
            expect(a0.compLocked === true && FALLBACK_RE.test(a0.compPrompt || ''), 'the fallback was persisted as a locked composition prompt (source=' + a0.compSource + ')').toBe(false);
            expect(toasts.includes(RERENDER_SUCCESS) && FALLBACK_RE.test(r.renderedPrompt), 'told "' + RERENDER_SUCCESS + '" while SD rendered the fallback').toBe(false);
            expect(FALLBACK_RE.test(r.renderedPrompt), 'Re-render rendered the stanza-free fallback: ' + r.renderedPrompt).toBe(false);
            expect(STANZA_IMAGERY.test(r.renderedPrompt), 'Re-render prompt carries no stanza imagery: ' + r.renderedPrompt).toBe(true);
            // And the user sees it on the card: the textarea now equals the scene's STORED prompt (the recovered one
            // the image was made from), not the placeholder it showed before the click.
            expect(shownAfter.trim(), 'the card textarea does not show the recovered stored prompt').toBe(String(a0.sdPrompt || '').trim());
            expect(FALLBACK_RE.test(shownAfter), 'the card still shows the placeholder after the re-render').toBe(false);
        } finally {
            try {
                await restLoginShared(request);
                for (const [type, name] of [['olio.llm.chatConfig', DEAD_CFG], ['system.connection', DEAD_CONN]]) {
                    const rec = await findInChat(request, type, name, organizationId, chatDir.id);
                    if (rec && rec.objectId) {
                        await deleteObject(request, type, rec.objectId);
                        const left = await findInChat(request, type, name, organizationId, chatDir.id);
                        if (left) throw new Error(type + ' ' + name + ' (id=' + rec.id + ') still present after DELETE');
                        console.log('[p10c1 dead] cleanup: deleted ' + type + ' ' + name + ' id=' + rec.id);
                    }
                }
                await request.get(REST + '/logout');
            } catch (e) {
                cleanupError = e;
            }
        }
        expect(cleanupError, 'dead-config fixture cleanup failed: ' + (cleanupError && cleanupError.message)).toBeNull();
    });
});
