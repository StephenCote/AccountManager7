/**
 * Per-scene SD prompt in the PictureBook wizard (Step 4), driven THROUGH THE BROWSER against the live
 * Docker stack with the real SD server and a live LLM.
 *
 * Stephen's report (2026-09-25): "Prompt scene overrides need to include the ACTUAL prompt used for
 * THAT scene and allow override when recreating. Right now it only shows the last prompt used as a
 * global, not for the scene." Asserted here in the real Ux:
 *   1. Generating scene A shows "Prompt used:" ON scene A's card with the prompt SD actually received
 *      (== the scene's persisted compositePrompt — the sent request's getPrompt(), in any composite
 *      mode — served back by GET /{book}/scenes as `prompt`).
 *   2. Generating scene B shows B's OWN prompt on B, and A's card STILL shows A's prompt (per scene,
 *      not a book-wide "last prompt").
 *   3. Reject A, open its Scene Overrides: the Prompt field is pre-filled with A's prompt. Edit it and
 *      Generate again: the card shows the edited prompt, the server persisted it as A's
 *      compositePrompt, and B's prompt is untouched.
 *
 * What this spec does NOT prove on its own: that the override drove the IMAGE. It first passed on
 * 2026-09-25 while the override was being silently ignored in flux2/kontext mode (only classic mode
 * read it), because display + persistence were correct and the image was never looked at. That defect
 * is pinned at the request level by TestFlux2Composite (flux2BuilderIgnoresClassicPrompt /
 * promptOverrideReplaces*), and the rendered override image must still be decoded and LOOKED AT — the
 * three rendered images are saved under e2e/screenshots/scene-prompt-render-*.png for exactly that.
 *
 * SELF-PROVISIONING (2026-10-12). The spec used to run against a leftover book
 * (PB_UX_BOOK=HarlotsEight-UX-muhpnuzi.docx) that no longer exists on the stack. It now builds its own
 * book, as the shared test user, from Stephen's real "The Big Way Out" text (e2e/fixtures/bigWayOut.txt):
 *   data.note -> POST /{note}/extract-scenes-only?async=true (live LLM) -> POST /chapter (PB2 book)
 *   -> POST /{note}/create-from-scenes (PB1 scene group linked to the PB2 book)
 *   -> PUT /{book}/settings (steps/hires pinned so three browser-driven renders stay modest)
 *   -> POST /{book}/prepare-images (scene/landscape prompts resolved through the SAME chatConfig).
 * The LLM behind all of that is the spec's OWN e2e-chapbook-llm chatConfig (ensureChatConfig ->
 * resolveChatRoute(), which prints "[e2e-llm] route=... server=... analysis=... pb=..."). The wizard's
 * per-scene Generate button itself sends the wizard default chatConfig name ('contentAnalysis',
 * src/workflows/pictureBook.js:69/:369/:1410 — there is no per-scene picker on the resume path), which
 * is why the prompts are pre-resolved via prepare-images first: that is also what the wizard's own
 * "Generate All" does (:1352) before its per-scene loop. The chatConfig each /generate request carried
 * is logged (evidence), not asserted.
 *
 * GATE: PB_SD_TESTS=1 (live LLM extraction + three live SD renders). Run it alone:
 *
 *   cd src/AccountManagerUx752
 *   PB_SD_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *       npx playwright test e2e/pictureBookScenePromptUx.spec.js --workers=1 --retries=0 --project=chromium
 *
 * Timings are recorded (durationMs) and are never pass/fail thresholds; every wait is a budget.
 */
import { test, expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { ensureSharedTestUser, ensureChatConfig, ensurePath, pb1Retired, PB1_RETIRED_SKIP } from './helpers/api.js';
import { screenshot } from './helpers/auth.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';
const JOB_REST = REST + '/job';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const ORG_PATH = '/Development';
const GEN_MIN = parseInt(process.env.PB_UX_GEN_MIN || '20', 10);   // per-scene render BUDGET (minutes), not a threshold
const SCENE_COUNT = 4;                                              // extraction target; the test needs >= 2
const RUN = Date.now().toString(36);

// Verbatim excerpt of Stephen's "The Big Way Out" (src/AccountManagerObjects7/media/The Big Way Out.doc).
// Real document content — NOT synthetic filler. Same source the Overhaul spec extracts from.
const BWO_TEXT = fs.readFileSync(path.resolve(__dirname, 'fixtures', 'bigWayOut.txt'), 'utf8');

const SHOTS = path.resolve(__dirname, 'screenshots');
function shotPath(name) { fs.mkdirSync(SHOTS, { recursive: true }); return path.join(SHOTS, name + '.png'); }
function fmtMs(ms) { return ms + 'ms (' + (ms / 60000).toFixed(1) + ' min)'; }
function b64(str) { return Buffer.from(str).toString('base64'); }

// ── REST helpers (shared-user session on the given request context) ─────────────────────────────

async function restLogin(request, creds) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: ORG_PATH, name: creds.testUserName,
            credential: b64(creds.testPassword), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

async function getJson(request, url) {
    const resp = await request.get(url, { headers: { Accept: 'application/json' }, timeout: 60000 });
    expect(resp.ok(), 'GET ' + url + ' -> ' + resp.status()).toBe(true);
    return resp.json();
}

async function listScenes(request, bookOid) {
    const scenes = await getJson(request, PB_REST + '/' + bookOid + '/scenes');
    return Array.isArray(scenes) ? scenes : (scenes && scenes.sceneList) || [];
}

async function pollJob(request, jobId, deadlineMs) {
    const deadline = Date.now() + deadlineMs;
    for (;;) {
        const r = await request.get(JOB_REST + '/' + jobId, { timeout: 60000 });
        if (!r.ok()) throw new Error('Job poll failed: HTTP ' + r.status());
        const job = await r.json();
        if (job.terminal) return job;
        if (Date.now() > deadline) throw new Error('Timed out waiting for job ' + jobId);
        await new Promise((res) => setTimeout(res, 3000));
    }
}

function scenesFromResult(result) {
    if (!result) return [];
    if (Array.isArray(result)) return result;
    if (Array.isArray(result.sceneList)) return result.sceneList;
    return [];
}

/**
 * Build the book this spec runs against: note -> LLM extraction -> PB2 chapter -> create-from-scenes
 * -> pinned SD settings -> pre-resolved prompts. Returns { bookOid, pb2BookObjectId, sceneOids }.
 */
async function provisionBook(request, chatConfigName) {
    const dir = await ensurePath(request, 'auth.group', 'data', '~/Notes');
    expect(dir && dir.id, 'resolved ~/Notes group').toBeTruthy();
    const noteName = 'BWO ScenePrompt Source ' + RUN;
    const noteResp = await request.post(REST + '/model', {
        data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name: noteName, text: BWO_TEXT }
    });
    expect(noteResp.ok(), 'create source note').toBeTruthy();
    const note = await noteResp.json();
    expect(note.objectId, 'source note objectId').toBeTruthy();
    console.log('[provision] source note =', note.objectId, '(' + BWO_TEXT.length + ' chars)');

    // 1) Live LLM scene extraction (async job, exactly as the wizard does).
    const tExtract = Date.now();
    const startResp = await request.post(PB_REST + '/' + note.objectId + '/extract-scenes-only?async=true', {
        data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName, count: SCENE_COUNT },
        timeout: 60000
    });
    expect(startResp.status(), 'extract-scenes-only returns 202').toBe(202);
    const started = await startResp.json();
    expect(started.jobId, 'extraction jobId').toBeTruthy();
    console.log('[provision] extraction jobId =', started.jobId);
    const extractJob = await pollJob(request, started.jobId, 20 * 60 * 1000);
    const extractMs = Date.now() - tExtract;
    expect(extractJob.status, 'extraction job status').not.toBe('failed');
    const scenes = scenesFromResult(extractJob.result);
    console.log('[provision] extracted scenes =', scenes.length, '| extraction durationMs =', fmtMs(extractMs));
    expect(scenes.length, 'need at least two extracted scenes').toBeGreaterThanOrEqual(2);

    // 2) PB2 book first, then the PB1 scene group linked to it (the same order the wizard uses).
    const slug = 'bwo-sceneprompt-' + RUN;
    const bookName = 'BWO ScenePrompt ' + RUN;
    const chapterResp = await request.post(PB_REST + '/chapter', { data: { slug, title: bookName }, timeout: 120000 });
    expect(chapterResp.ok(), 'POST /chapter creates the PB2 book').toBeTruthy();
    const chapter = await chapterResp.json();
    const pb2BookObjectId = chapter.bookObjectId || chapter.objectId;
    expect(pb2BookObjectId, 'PB2 book objectId').toBeTruthy();

    const tCreate = Date.now();
    const cfsResp = await request.post(PB_REST + '/' + note.objectId + '/create-from-scenes', {
        data: {
            schema: 'olio.pictureBookRequest',
            sceneList: scenes,
            chatConfig: chatConfigName,
            bookName: bookName,
            pb2BookObjectId: pb2BookObjectId
        },
        timeout: 20 * 60 * 1000
    });
    if (!cfsResp.ok()) console.error('[provision] create-from-scenes body:', await cfsResp.text());
    expect(cfsResp.ok(), 'create-from-scenes succeeds').toBeTruthy();
    const meta = await cfsResp.json();
    const bookOid = meta.bookObjectId;
    expect(bookOid, 'meta.bookObjectId (PB1 book group — the viewer route id)').toBeTruthy();
    const metaScenes = meta.scenes || [];
    console.log('[provision] bookObjectId =', bookOid, '| pb2BookObjectId =', meta.pb2BookObjectId || pb2BookObjectId,
        '| persisted scenes =', metaScenes.length, '| create-from-scenes durationMs =', fmtMs(Date.now() - tCreate));

    // 3) Pin a modest SD config on the book so the three browser-driven renders read it back
    //    (tryResumeExistingBook -> getBookSdConfig -> applySdConfig). Budget, not a threshold.
    const settingsResp = await request.put(PB_REST + '/' + bookOid + '/settings', {
        data: { schema: 'olio.pictureBookRequest', sdConfig: { steps: 20, hires: false } },
        timeout: 120000
    });
    expect(settingsResp.ok(), 'PUT /{book}/settings pins the SD config').toBeTruthy();
    let stored = null;
    try { stored = await settingsResp.json(); } catch (_) { stored = null; }
    console.log('[provision] stored sdConfig: steps =', stored && stored.steps, '| hires =', stored && stored.hires,
        '| style =', stored && stored.style, '| model =', stored && stored.model);

    // 4) Resolve (and cache) the scene + landscape prompts through THIS spec's route-resolved chatConfig,
    //    exactly as the wizard's "Generate All" does before its per-scene loop. The per-scene Generate
    //    button on the resume path sends the wizard default ('contentAnalysis') instead, so without this
    //    step the browser renders would depend on a config this spec does not own.
    const committed = (await listScenes(request, bookOid)).filter((s) => s.objectId);
    expect(committed.length, 'committed scenes after create-from-scenes').toBeGreaterThanOrEqual(2);
    const sceneOids = committed.map((s) => s.objectId);
    const tPrep = Date.now();
    const prepResp = await request.post(PB_REST + '/' + bookOid + '/prepare-images', {
        data: {
            schema: 'olio.pictureBookRequest',
            sceneObjectIds: sceneOids,
            chatConfig: chatConfigName,
            sdConfig: stored && stored.objectId ? stored : { steps: 20, hires: false }
        },
        timeout: 15 * 60 * 1000
    });
    if (!prepResp.ok()) console.error('[provision] prepare-images body:', await prepResp.text());
    expect(prepResp.ok(), 'prepare-images resolves the scene prompts via ' + chatConfigName).toBeTruthy();
    console.log('[provision] prepare-images durationMs =', fmtMs(Date.now() - tPrep), 'for', sceneOids.length, 'scenes');

    return { bookOid, pb2BookObjectId, sceneOids };
}

// ── Browser helpers ──────────────────────────────────────────────────────────────────────────────

// Stub WebSocket (Docker nginx strips the session cookie on the WS upgrade; the SPA would otherwise
// reconnect -> forceLogin -> #!/sig). Must be installed BEFORE the first goto.
async function installWsStub(page) {
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
    });
}

// REST login on the page's own context (cookie shared with the SPA), stub, load, wait for #!/main.
async function loginSharedUser(page, creds) {
    await restLogin(page.request, creds);
    await installWsStub(page);
    await page.goto('/', { timeout: 45000 });
    // page.waitForFunction(fn, arg, options): options is the THIRD argument — passing it second makes
    // it the page-function argument and the wait unbounded.
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null,
        { timeout: 45000 }
    );
}

// Fetch a rendered scene image through the MediaServlet (the same path pictureBook.spec.js uses) and
// save it next to the screenshots. Evidence only — nothing about image content is asserted.
async function saveRenderedImage(request, imageObjectId, name) {
    if (!imageObjectId) { console.log('[scene-prompt] ' + name + ': no imageObjectId to fetch'); return null; }
    const dataResp = await request.get(REST + '/model/data.data/' + imageObjectId + '/full', { timeout: 60000 });
    if (!dataResp.ok()) { console.log('[scene-prompt] ' + name + ': image record HTTP ' + dataResp.status()); return null; }
    const rec = await dataResp.json();
    if (!rec || !rec.groupPath || !rec.name) { console.log('[scene-prompt] ' + name + ': image record lacks groupPath/name'); return null; }
    const mediaUrl = '/AccountManagerService7/media/Development/data.data' + encodeURI(rec.groupPath) + '/' + encodeURIComponent(rec.name);
    const imgResp = await request.get(mediaUrl, { timeout: 60000 });
    if (!imgResp.ok()) { console.log('[scene-prompt] ' + name + ': media HTTP ' + imgResp.status() + ' ' + mediaUrl); return null; }
    const buf = Buffer.from(await imgResp.body());
    const out = shotPath(name);
    fs.writeFileSync(out, buf);
    console.log('[scene-prompt] ' + name + ' ->', out, '(' + buf.length + ' bytes, contentType=' + rec.contentType + ')');
    return out;
}

// The wizard renders Step 4's scene cards inside the `space-y-3 max-h-[32rem]` scroller, in the
// same order GET /{book}/scenes returns them. Cards are `div.border.rounded.p-3.space-y-2`; the
// common SD config panel above the list is also `border rounded p-3` (with mb-3), so scope to the
// scroller's direct children. Uncommitted scenes render as a plain text div, not a card.
function sceneCards(dialog) {
    return dialog.locator('div.space-y-3.max-h-\\[32rem\\] > div.border.rounded.p-3');
}
function sceneCard(dialog, idx) {
    return sceneCards(dialog).nth(idx);
}
// Status badge = the last `text-xs px-2 py-0.5 rounded shrink-0` span in the card's header row (the
// override indicator is px-1.5 and never matches).
function statusBadge(card) {
    return card.locator('div.flex.items-center.gap-2').first().locator('span.text-xs.px-2.py-0\\.5.rounded.shrink-0').last();
}
async function status(card) {
    return ((await statusBadge(card).textContent().catch(() => '')) || '').trim();
}
// Action buttons carry a material-symbols glyph span (textContent "refreshRetry"), so match by
// has-text, not exact text.
function actionButton(card, label) {
    return card.locator('div.flex.gap-2.flex-wrap').first().locator('button:has-text("' + label + '")');
}
async function promptShown(card, oid) {
    const el = card.locator('[data-pb-scene-prompt="' + oid + '"]');
    await expect(el, 'Prompt used shown for scene ' + oid).toBeVisible({ timeout: 10000 });
    await expect(el).toContainText('Prompt used:');
    return ((await el.locator('span').nth(1).textContent()) || '').trim();
}
async function waitForRender(card, label, t0) {
    await expect.poll(async () => status(card), {
        timeout: GEN_MIN * 60 * 1000, intervals: [3000],
        message: label + ': scene render did not reach done/error'
    }).toMatch(/^(done|error)$/);
    const st = await status(card);
    const err = st === 'error' ? ((await card.locator('div.text-red-500').first().textContent().catch(() => '')) || '') : '';
    const ms = Date.now() - t0;
    console.log('[scene-prompt] ' + label + ' -> ' + st + ' | render durationMs = ' + fmtMs(ms) + (err ? ' ERROR: ' + err : ''));
    expect(st, label + ' render failed: ' + err).toBe('done');
    return ms;
}
// Click Generate (pending) or Retry (error) on this card and wait for the render to finish.
async function generateAndWait(card, label) {
    const st = await status(card);
    const btn = actionButton(card, st === 'error' ? 'Retry' : 'Generate');
    await expect(btn, label + ': ' + (st === 'error' ? 'Retry' : 'Generate') + ' button (status=' + st + ')').toBeVisible({ timeout: 10000 });
    await expect(btn).toBeEnabled({ timeout: 30000 });
    const t0 = Date.now();
    await btn.click();
    return waitForRender(card, label, t0);
}

test.describe('PictureBook wizard — per-scene prompt shown and overridable', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

    test('each scene shows its own prompt; override on regenerate is used and persisted', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live LLM extraction + three live SD renders');
        // Budget (never a threshold): extraction <= 20 min, create-from-scenes <= 20 min, prepare-images
        // <= 15 min, three renders <= GEN_MIN each, plus UI slack.
        test.setTimeout((55 + 3 * GEN_MIN + 15) * 60 * 1000);
        const testStart = Date.now();

        const pageErrors = [];
        page.on('pageerror', (err) => { pageErrors.push(String(err && err.stack || err)); console.log('[PAGE-ERROR] ' + (err && err.message)); });
        page.on('response', async (resp) => {
            const u = resp.url();
            if (u.includes('/olio/picture-book/') && resp.status() >= 400) {
                let body = ''; try { body = (await resp.text()).substring(0, 300); } catch (_) { body = '<unreadable>'; }
                console.log('[NETWORK ' + resp.status() + '] ' + u + ' ' + body);
            }
        });
        // Evidence: which chatConfig name the wizard's per-scene Generate actually sends (see header).
        page.on('request', (req) => {
            if (req.method() === 'POST' && /\/olio\/picture-book\/scene\/[^/]+\/generate$/.test(req.url())) {
                let cfg = '?';
                try { cfg = JSON.parse(req.postData() || '{}').chatConfig; } catch (_) { cfg = '<unparsed>'; }
                console.log('[scene-prompt] wizard /generate request chatConfig =', cfg);
            }
        });

        // ── Provision: shared user, the spec's own route-resolved chatConfig, and a fresh book ──
        const creds = await ensureSharedTestUser(request);
        // P7-2: provisionBook drives PUT /{book}/settings + POST /{book}/prepare-images and the wizard's
        // Generate drives POST /scene/{id}/generate - all three PB1 entries, 409 under v3.
        test.skip(await pb1Retired(request), PB1_RETIRED_SKIP);
        const chatConfigName = await ensureChatConfig(request, null);   // prints "[e2e-llm] route=…"
        expect(chatConfigName, 'chatConfig provisioned via resolveChatRoute()').toBeTruthy();
        console.log('[setup] shared user =', creds.testUserName, '| chatConfig =', chatConfigName);
        await restLogin(request, creds);
        const { bookOid } = await provisionBook(request, chatConfigName);

        const before = await listScenes(request, bookOid);
        console.log('[scene-prompt] book ' + bookOid + ' has ' + before.length + ' scene(s)');
        const committed = before.filter(s => s.objectId);
        // Two scenes that are still open for generation (pending / done / error, not accepted/skipped).
        const open = committed.map((s, i) => ({ s, i }))
            .filter(x => !['accepted', 'skipped'].includes(String(x.s.status || '').toLowerCase()));
        expect(open.length, 'need two generatable scenes').toBeGreaterThanOrEqual(2);
        const A = open[0], B = open[1];
        console.log('[scene-prompt] A=#' + A.i + ' "' + A.s.title + '" (' + A.s.objectId + ') B=#' + B.i + ' "' + B.s.title + '" (' + B.s.objectId + ')');

        await loginSharedUser(page, creds);
        await page.goto('/#!/picture-book/' + bookOid, { timeout: 45000 });
        const editBtn = page.locator('button[title="Edit Book"]');
        await expect(editBtn).toBeVisible({ timeout: 60000 });
        await editBtn.click();
        const dialog = page.locator('[role="dialog"]').first();
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog.locator('h3:has-text("Image Generation")')).toBeVisible({ timeout: 60000 });   // Step 4
        await expect(sceneCards(dialog)).toHaveCount(committed.length, { timeout: 30000 });

        const cardA = sceneCard(dialog, A.i), cardB = sceneCard(dialog, B.i);
        await expect(cardA.locator('div.font-medium.text-sm.truncate')).toHaveText(A.s.title || 'Untitled');
        await expect(cardB.locator('div.font-medium.text-sm.truncate')).toHaveText(B.s.title || 'Untitled');
        // Start from a generatable state on both (a 'done' scene offers Reject instead of Generate).
        for (const [c, label] of [[cardA, 'A'], [cardB, 'B']]) {
            const st = await status(c);
            console.log('[scene-prompt] ' + label + ' initial status: ' + st);
            if (st === 'done') {
                await actionButton(c, 'Reject').click();
                await expect(statusBadge(c)).toHaveText('pending', { timeout: 10000 });
            }
        }
        await screenshot(page, 'scene-prompt-step4-before');

        // ── 1. Scene A: its own prompt is shown and matches what the server persisted ──────────
        await generateAndWait(cardA, 'A first render');
        const promptA1 = await promptShown(cardA, A.s.objectId);
        console.log('[scene-prompt] A prompt (' + promptA1.length + ' chars): ' + promptA1.substring(0, 160));
        expect(promptA1.length).toBeGreaterThan(10);
        await expect.poll(async () => {
            const s = (await listScenes(request, bookOid)).find(x => x.objectId === A.s.objectId);
            return s ? (s.prompt || null) : null;
        }, { timeout: 20000, message: 'A.prompt persisted' }).toBe(promptA1);
        await expect(cardB.locator('[data-pb-scene-prompt]'), 'B shows no prompt before its own render').toHaveCount(
            B.s.prompt ? 1 : 0);
        await screenshot(page, 'scene-prompt-A-rendered');
        {
            const sA = (await listScenes(request, bookOid)).find(x => x.objectId === A.s.objectId);
            await saveRenderedImage(request, sA && sA.imageObjectId, 'scene-prompt-render-A1');
        }

        // ── 2. Scene B: its own prompt; A keeps A's ─────────────────────────────────────────────
        await generateAndWait(cardB, 'B first render');
        const promptB1 = await promptShown(cardB, B.s.objectId);
        console.log('[scene-prompt] B prompt (' + promptB1.length + ' chars): ' + promptB1.substring(0, 160));
        expect(promptB1, 'B has its own prompt, not A\'s').not.toBe(promptA1);
        expect(await promptShown(cardA, A.s.objectId), 'A still shows A\'s prompt after B rendered').toBe(promptA1);
        await screenshot(page, 'scene-prompt-B-rendered');
        {
            const sB = (await listScenes(request, bookOid)).find(x => x.objectId === B.s.objectId);
            await saveRenderedImage(request, sB && sB.imageObjectId, 'scene-prompt-render-B1');
        }

        // ── 3. Reject A, override its prompt, regenerate: override used + persisted ─────────────
        await actionButton(cardA, 'Reject').click();
        await expect(statusBadge(cardA)).toHaveText('pending', { timeout: 10000 });
        expect(await promptShown(cardA, A.s.objectId), 'A keeps showing its prompt after Reject').toBe(promptA1);

        await cardA.locator('button:has-text("Scene Overrides")').click();
        const promptField = cardA.locator('input[name="description"], textarea[name="description"]').first();
        await expect(promptField).toBeVisible({ timeout: 20000 });
        await expect(promptField, 'override Prompt pre-filled with THIS scene\'s prompt').toHaveValue(promptA1);

        const override = 'e2e per-scene override ' + Date.now()
            + ': a lone fairy with translucent wings sitting on a moonlit market stall, soft watercolor, muted teal and gold';
        await promptField.fill(override);
        await expect(promptField).toHaveValue(override);
        await screenshot(page, 'scene-prompt-A-override-filled');

        await generateAndWait(cardA, 'A override render');
        const promptA2 = await promptShown(cardA, A.s.objectId);
        expect(promptA2, 'card shows the override as the prompt used').toBe(override);
        await expect.poll(async () => {
            const s = (await listScenes(request, bookOid)).find(x => x.objectId === A.s.objectId);
            return s ? (s.prompt || null) : null;
        }, { timeout: 20000, message: 'override persisted as A.compositePrompt' }).toBe(override);
        expect(await promptShown(cardB, B.s.objectId), 'B untouched by A\'s override').toBe(promptB1);
        const bPersisted = (await listScenes(request, bookOid)).find(x => x.objectId === B.s.objectId);
        expect(bPersisted && bPersisted.prompt, 'B.compositePrompt untouched').toBe(promptB1);
        await screenshot(page, 'scene-prompt-A-override-rendered');
        {
            const sA2 = (await listScenes(request, bookOid)).find(x => x.objectId === A.s.objectId);
            await saveRenderedImage(request, sA2 && sA2.imageObjectId, 'scene-prompt-render-A2-override');
        }

        expect(pageErrors, 'no uncaught page errors').toEqual([]);
        console.log('[scene-prompt] total durationMs =', fmtMs(Date.now() - testStart));
    });
});
