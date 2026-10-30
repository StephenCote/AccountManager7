/**
 * pictureBookWorkflowOverhaul.spec.js — end-to-end proof of the "PictureBook Workflow Overhaul"
 * W-series through the real REST + UX stack, against a live LLM (whichever tier
 * helpers/api.js resolveChatRoute() answers — local Ollama container via LiteLLM, Azure, or the
 * DGX at 192.168.1.42; the `[e2e-llm] route=… ` line in the run log says which) and live SD
 * (Swarm @ 192.168.1.39) via the Docker stack on https://127.0.0.1:9443.
 *
 * Gating (a skipped gated test is NOT a pass — run it with the flag set and read the summary line):
 *   PB_SD_TESTS=1   runs everything (CORE + W2 need a real SD render; W5 needs only the LLM).
 *   PB_LLM_TESTS=1  runs W5 alone (LLM extraction, no SD) when PB_SD_TESTS is unset.
 *   Both tests are LLM-touching: always --workers=1.
 *
 * This exercises the EXACT wizard Step-2 Continue sequence (pictureBook.js:1544-1578):
 *     createChapBookRecord(slug, title)  -> pb2BookObjectId
 *     createFromScenes(work, chat, genre, name, scenes, charStubs, pb2BookObjectId) -> meta
 *     generateSceneImage(scenes[0].objectId, {chatConfig, sdConfig})               -> genResult
 *     GET /rest/olio/picture-book/{pb2BookObjectId}/workflow                       -> nodes[]
 *
 * What each assertion proves about the W-series:
 *   CORE (W1/W4): the workflow graph (olio.pb.workflow/node/binding/artifact) is written
 *                 UNCONDITIONALLY during scene generation — no PbFeatureFlag gate. After one real
 *                 SD render, GET /workflow returns nodes.length >= 1, and the canvas UI paints
 *                 [data-node-id] cards (not an empty state).
 *   W2:           the scene-generation result JSON carries a `graphWriteFailures` array; on the
 *                 happy path it is EMPTY (no swallowed graph writes). A NON-empty array is a real
 *                 defect and FAILS the test with the failures printed — it is not hidden.
 *   W5:           a freshly-created book that has NOT been rendered (its own source note, its own
 *                 extraction — it does not depend on CORE having run) is navigated to the canvas:
 *                 zero [data-node-id] cards and one of the canvas's own empty-state titles
 *                 (src/features/pictureBookWorkflow.js: noWorkflowYetState "No workflow graph yet",
 *                 "No workflow graph for this book", "Nothing rendered yet", "Workflow was not
 *                 recorded"). Which one appears is reported, not asserted.
 *
 * Rules honored: shared test user only (never admin as actor); no DB reset; app writes/reads only;
 * a fresh book version is created (no reuse of pre-existing books); single-threaded LLM/SD path;
 * durations are recorded (durationMs) and never used as pass/fail thresholds.
 */
import { test, expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { ensureSharedTestUser, ensureChatConfig, ensurePath, pb1Retired, PB1_RETIRED_SKIP } from './helpers/api.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE + '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const JOB = REST + '/job';

// Same flag conventions as pictureBook.spec.js / pictureBookV3Ux.spec.js (PB_SD_TESTS) and the
// LLM-only PictureBook Ux specs (PB_LLM_TESTS). Visible test.skip() below, never a silent return.
const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const LLM_ENABLED = SD_ENABLED || process.env.PB_LLM_TESTS === '1';

const SHOTS = path.resolve(__dirname, 'screenshots');
function shot(name) { fs.mkdirSync(SHOTS, { recursive: true }); return path.join(SHOTS, name + '.png'); }
function fmtMs(ms) { return ms + 'ms (' + (ms / 60000).toFixed(1) + ' min)'; }

// Verbatim excerpt of Stephen's "The Big Way Out" (src/AccountManagerObjects7/media/The Big Way Out.doc),
// extracted with antiword. Real document content — NOT synthetic filler.
const BWO_TEXT = fs.readFileSync(path.resolve(__dirname, 'fixtures', 'bigWayOut.txt'), 'utf8');

// Shared state across the serial tests.
let sharedCreds;         // { testUserName, testPassword }
let chatConfigName;      // 'e2e-chapbook-llm'
let workObjectId;        // source data.note objectId
let pb2BookObjectId;     // olio.pb.book objectId (from create-from-scenes meta.pb2BookObjectId)
let firstSceneObjectId;  // scenes[0].objectId
let genResult;           // scene-generation result JSON (W2)
const RUN = Date.now().toString(36);

// ── WebSocket stub (Docker nginx strips the session cookie on WS upgrade). Must be installed via
//    addInitScript BEFORE any goto so the SPA does not reconnect->forceLogin->#!/sig.
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

// Log into the SPA as the shared test user via REST (cookie shared with the page context) then load.
async function loginSharedUser(page, creds) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: creds.testUserName, credential: Buffer.from(creds.testPassword).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) throw new Error('Shared-user REST login failed: HTTP ' + resp.status());
    await installWsStub(page);
    await page.goto('/', { timeout: 45000 });
    // page.waitForFunction(fn, arg, options): the options object is the THIRD argument. Passing it
    // second makes it the page-function argument and the wait unbounded (the W5 20-minute hang).
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null,
        { timeout: 45000 }
    );
}

// Create a fresh data.note under the shared user's ~/Notes from the real BWO text and run one LLM
// scene extraction on it (async job, exactly as the wizard does). Returns { workObjectId, scenes }.
async function createSourceAndExtract(page, tag, count) {
    const dir = await ensurePath(page.request, 'auth.group', 'data', '~/Notes');
    expect(dir && dir.id, 'resolved ~/Notes group').toBeTruthy();
    const noteName = 'BWO Overhaul Source ' + tag + ' ' + RUN;
    const noteResp = await page.request.post(REST + '/model', {
        data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name: noteName, text: BWO_TEXT }
    });
    expect(noteResp.ok(), 'create source note (' + tag + ')').toBeTruthy();
    const note = await noteResp.json();
    expect(note.objectId, 'source note objectId (' + tag + ')').toBeTruthy();
    console.log('[' + tag + '] source note =', note.objectId, '(' + BWO_TEXT.length + ' chars)');

    const t0 = Date.now();
    const startResp = await page.request.post(PB + '/' + note.objectId + '/extract-scenes-only?async=true', {
        data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName, count },
        timeout: 60000
    });
    expect(startResp.status(), 'extract-scenes-only returns 202 (' + tag + ')').toBe(202);
    const started = await startResp.json();
    expect(started.jobId, 'extraction jobId (' + tag + ')').toBeTruthy();
    console.log('[' + tag + '] extraction jobId =', started.jobId);
    const extractJob = await pollJob(page, started.jobId, 20 * 60 * 1000);
    const extractMs = Date.now() - t0;
    expect(extractJob.status, 'extraction job status (' + tag + ')').not.toBe('failed');
    const scenes = scenesFromResult(extractJob.result);
    console.log('[' + tag + '] extracted scenes =', scenes.length, '| extraction durationMs =', fmtMs(extractMs));
    expect(scenes.length, 'at least one scene extracted from the LLM (' + tag + ')').toBeGreaterThanOrEqual(1);
    return { workObjectId: note.objectId, scenes, extractMs };
}

// Inline reimplementation of sceneExtractor.pollJob (deadline-bounded), using page.request.
async function pollJob(page, jobId, deadlineMs) {
    const deadline = Date.now() + (deadlineMs || 15 * 60 * 1000);
    for (;;) {
        const r = await page.request.get(JOB + '/' + jobId, { timeout: 60000 });
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

test.describe.serial('PictureBook Workflow Overhaul (W-series) — live LLM+SD end-to-end', () => {

    test.beforeAll(async () => {
        if (!LLM_ENABLED) return; // the tests below skip visibly; do not provision for a skipped run
        // Provision the shared test user + a chatConfig it owns whose connection is whichever LLM tier
        // resolveChatRoute() finds (prints "[e2e-llm] route=… server=… analysis=… pb=…"). These use
        // their own isolated API contexts internally (admin only to create the user/credential; never
        // as actor). The shared user's default contentAnalysis config is NOT used: on this stack it
        // points at an LLM the container cannot reach.
        const req = await (await import('@playwright/test')).request.newContext({ baseURL: BASE, ignoreHTTPSErrors: true });
        try {
            sharedCreds = await ensureSharedTestUser(req);
            chatConfigName = await ensureChatConfig(req, null);
        } finally {
            await req.dispose();
        }
        expect(sharedCreds && sharedCreds.testUserName, 'shared test user provisioned').toBeTruthy();
        expect(chatConfigName, 'chatConfig provisioned via resolveChatRoute()').toBeTruthy();
        console.log('[setup] shared user =', sharedCreds.testUserName, '| chatConfig =', chatConfigName);
    });

    test('CORE + W2: extract -> create PB2 book -> render scene -> workflow graph populates', async ({ page }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live LLM extraction + SD render (W1/W2/W4)');
        // P7-2: step 5 renders through the PB1 entry POST /scene/{id}/generate (409 under v3); W5 is not gated.
        test.skip(await pb1Retired(page.request), PB1_RETIRED_SKIP);
        test.setTimeout(35 * 60 * 1000); // live LLM extraction + SD render (budget, not a threshold)
        const testStart = Date.now();

        await loginSharedUser(page, sharedCreds);

        // 1+2) FRESH source document (data.note) from the real BWO text, then LLM scene extraction.
        const src = await createSourceAndExtract(page, 'CORE', 6);
        workObjectId = src.workObjectId;
        const scenes = src.scenes;

        // 3) Create the PB2 book first (this is what makes the graph populate on render).
        const slug = 'bwo-overhaul-' + RUN;
        const bookName = 'BWO Overhaul ' + RUN;
        const chapterResp = await page.request.post(PB + '/chapter', {
            data: { slug, title: bookName }, timeout: 120000
        });
        expect(chapterResp.ok(), 'POST /chapter creates a PB2 book').toBeTruthy();
        const chapter = await chapterResp.json();
        const createdPb2 = chapter.bookObjectId || chapter.objectId;
        expect(createdPb2, 'created PB2 book objectId').toBeTruthy();
        console.log('[step3] createChapBookRecord -> pb2BookObjectId =', createdPb2);

        // 4) create-from-scenes WITH the pb2BookObjectId — links the PB1 group into the PB2 world.
        const cfsResp = await page.request.post(PB + '/' + workObjectId + '/create-from-scenes', {
            data: {
                schema: 'olio.pictureBookRequest',
                sceneList: scenes,
                chatConfig: chatConfigName,
                bookName: bookName,
                pb2BookObjectId: createdPb2
            },
            timeout: 20 * 60 * 1000
        });
        if (!cfsResp.ok()) {
            console.error('[step4] create-from-scenes body:', await cfsResp.text());
        }
        expect(cfsResp.ok(), 'create-from-scenes succeeds').toBeTruthy();
        const meta = await cfsResp.json();
        pb2BookObjectId = meta.pb2BookObjectId || createdPb2;
        const metaScenes = meta.scenes || [];
        expect(metaScenes.length, 'meta carries persisted scenes').toBeGreaterThanOrEqual(1);
        firstSceneObjectId = metaScenes[0].objectId;
        expect(firstSceneObjectId, 'first persisted scene objectId').toBeTruthy();
        console.log('[step4] meta.bookObjectId =', meta.bookObjectId, '| meta.pb2BookObjectId =', pb2BookObjectId,
            '| scenes =', metaScenes.length);

        // 5) Render the first scene via SD (4-stage pipeline). W1/W4: this is what writes the graph.
        const genStart = Date.now();
        const genResp = await page.request.post(PB + '/scene/' + firstSceneObjectId + '/generate', {
            data: {
                schema: 'olio.pictureBookRequest',
                chatConfig: chatConfigName,
                sdConfig: { steps: 20, hires: false }
            },
            timeout: 15 * 60 * 1000
        });
        const genMs = Date.now() - genStart;
        if (!genResp.ok()) {
            console.error('[step5] scene generate body:', await genResp.text());
        }
        expect(genResp.ok(), 'scene image generation succeeds').toBeTruthy();
        genResult = await genResp.json();
        console.log('[step5] genResult.imageObjectId =', genResult.imageObjectId,
            '| graphWriteFailures =', JSON.stringify(genResult.graphWriteFailures),
            '| render durationMs =', fmtMs(genMs));
        expect(genResult.imageObjectId, 'a real image was rendered (imageObjectId present)').toBeTruthy();

        // Save the rendered image next to the screenshots so a human (or the Read tool) can look at it,
        // fetched from the MediaServlet exactly as pictureBook.spec.js does. Evidence only: nothing is
        // asserted about image content.
        const dataResp = await page.request.get(REST + '/model/data.data/' + genResult.imageObjectId + '/full', { timeout: 60000 });
        if (dataResp.ok()) {
            const dataRec = await dataResp.json();
            if (dataRec && dataRec.groupPath && dataRec.name) {
                const mediaUrl = '/AccountManagerService7/media/Development/data.data'
                    + encodeURI(dataRec.groupPath) + '/' + encodeURIComponent(dataRec.name);
                const imgResp = await page.request.get(mediaUrl, { timeout: 60000 });
                if (imgResp.ok()) {
                    const buf = Buffer.from(await imgResp.body());
                    const out = shot('pb-overhaul-CORE-rendered-scene');
                    fs.writeFileSync(out, buf);
                    console.log('[step5] rendered image ->', out, '(' + buf.length + ' bytes, contentType=' + dataRec.contentType + ')');
                } else {
                    console.log('[step5] media fetch failed: HTTP ' + imgResp.status() + ' for ' + mediaUrl);
                }
            } else {
                console.log('[step5] image data.data missing groupPath/name: ' + JSON.stringify(dataRec).substring(0, 300));
            }
        } else {
            console.log('[step5] could not read rendered image record: HTTP ' + dataResp.status());
        }

        // ── CORE (priority assertion — evaluated FIRST): workflow graph populated after the render.
        const wfResp = await page.request.get(PB + '/' + pb2BookObjectId + '/workflow', { timeout: 60000 });
        expect(wfResp.ok(), 'GET /workflow succeeds after render').toBeTruthy();
        const wf = await wfResp.json();
        const nodes = wf.nodes || [];
        console.log('[CORE] workflow nodeCount =', wf.nodeCount, '| nodes =', nodes.length,
            '| edges =', (wf.edges || []).length, '| graphStatus =', wf.graphStatus);
        if (nodes.length) {
            console.log('[CORE] node handles =', nodes.map((n) => n.handle || n.nodeType).join(', '));
        }
        expect(nodes.length, 'CORE: workflow graph has >= 1 node after rendering').toBeGreaterThanOrEqual(1);

        // If a node exposes bindings/artifacts, assert them (best-effort per task) via node detail.
        const nodeWithBindings = nodes[0];
        if (nodeWithBindings && nodeWithBindings.objectId) {
            const ndResp = await page.request.get(
                PB + '/' + pb2BookObjectId + '/workflow/node/' + nodeWithBindings.objectId, { timeout: 60000 });
            if (ndResp.ok()) {
                const nd = await ndResp.json();
                const bindings = nd.bindings || [];
                const artifactRoles = nd.artifacts ? Object.keys(nd.artifacts) : [];
                console.log('[CORE] node[0] bindings =', bindings.length, '| artifact roles =', JSON.stringify(artifactRoles));
                // Not required to be non-empty (depends on node type), but if present must be well-formed.
                expect(Array.isArray(bindings), 'node bindings is an array').toBeTruthy();
            }
        }

        // ── CORE UI: navigate to the canvas and prove [data-node-id] cards paint (not an empty state).
        await page.goto('/#!/picture-book/' + pb2BookObjectId + '/workflow', { timeout: 45000 });
        await page.waitForSelector('[data-node-id]', { timeout: 60000 });
        const cardCount = await page.locator('[data-node-id]').count();
        console.log('[CORE-UI] rendered node cards =', cardCount);
        expect(cardCount, 'CORE-UI: canvas shows >= 1 node card').toBeGreaterThanOrEqual(1);
        await page.screenshot({ path: shot('pb-overhaul-CORE-workflow-canvas'), fullPage: true });
        console.log('[CORE-UI] screenshot ->', shot('pb-overhaul-CORE-workflow-canvas'));

        // ── W2: swallowed graph-write failures are surfaced on the scene result (not only in the log).
        //   VERIFIED behavior (PictureBookUtil.java:7484-7486): the result carries `graphWriteFailures`
        //   ONLY when the list is non-empty — so on the happy path the field is ABSENT, not `[]`.
        //   (The pre-investigation belief that toFullString would emit `[]` was wrong.) Either
        //   representation proves "no swallowed failures"; a NON-empty array would be a real defect and
        //   fails here with the failures printed. The non-empty (failure) branch is NOT exercised
        //   because inducing a real graph-write failure requires a forbidden change — it is not faked.
        const gwf = genResult.graphWriteFailures;
        console.log('[W2] graphWriteFailures =', JSON.stringify(gwf),
            '(absent on happy path is correct — field is set only when non-empty)');
        if (gwf !== undefined && gwf !== null) {
            expect(Array.isArray(gwf), 'W2: graphWriteFailures, when present, is an array').toBeTruthy();
            expect(gwf, 'W2: no swallowed graph-write failures on the happy path — got: '
                + JSON.stringify(gwf)).toHaveLength(0);
        }
        console.log('[CORE] durationMs =', fmtMs(Date.now() - testStart));
    });

    // The canvas's own empty-state titles (src/features/pictureBookWorkflow.js). A PB2 book that has
    // scenes but was never rendered has no olio.pb.workflow row, so the view renders
    // noWorkflowYetState() -> "No workflow graph yet" (:236-245, used at :282/:300/:321). The other three
    // are the legacy no-scenes state (:305), the zero-node workflow row (:334) and the not-recorded
    // banner. Which one appears is reported; the assertion is "one of the UI's own empty states, and
    // zero node cards".
    const EMPTY_STATES = ['No workflow graph yet', 'Nothing rendered yet', 'Workflow was not recorded', 'No workflow graph for this book'];

    test('W5: a freshly-created, un-rendered book shows an honest empty state', async ({ page }) => {
        test.skip(!LLM_ENABLED, 'PB_SD_TESTS!=1 and PB_LLM_TESTS!=1 — skips the live LLM extraction behind W5');
        test.setTimeout(30 * 60 * 1000); // one LLM extraction (budget, not a threshold)
        const testStart = Date.now();

        await loginSharedUser(page, sharedCreds);

        // Self-contained: W5 provisions its OWN source note + extraction so it does not depend on CORE
        // having run (CORE is SD-gated; W5 is not). The book is created and populated but NOT rendered.
        const src = await createSourceAndExtract(page, 'W5', 6);
        const scenes = src.scenes;

        const slug2 = 'bwo-overhaul-empty-' + RUN;
        const bookName2 = 'BWO Overhaul Empty ' + RUN;
        const chapterResp = await page.request.post(PB + '/chapter', { data: { slug: slug2, title: bookName2 }, timeout: 120000 });
        expect(chapterResp.ok(), 'POST /chapter (un-rendered book)').toBeTruthy();
        const chapter = await chapterResp.json();
        const pb2Empty = chapter.bookObjectId || chapter.objectId;
        expect(pb2Empty, 'un-rendered PB2 book objectId').toBeTruthy();

        const cfsResp = await page.request.post(PB + '/' + src.workObjectId + '/create-from-scenes', {
            data: {
                schema: 'olio.pictureBookRequest', sceneList: scenes, chatConfig: chatConfigName,
                bookName: bookName2, pb2BookObjectId: pb2Empty
            },
            timeout: 20 * 60 * 1000
        });
        if (!cfsResp.ok()) console.error('[W5] create-from-scenes body:', await cfsResp.text());
        expect(cfsResp.ok(), 'create-from-scenes (un-rendered book)').toBeTruthy();
        const meta = await cfsResp.json();
        console.log('[W5] book =', pb2Empty, '| persisted scenes =', (meta.scenes || []).length);

        // REST view of the same fact: no workflow row exists until first render (404 + error text).
        const wfResp = await page.request.get(PB + '/' + pb2Empty + '/workflow', { timeout: 60000 });
        const wfText = await wfResp.text();
        console.log('[W5] GET /workflow ->', wfResp.status(), wfText.substring(0, 200));

        // Navigate to the canvas of the un-rendered book. It must NOT show node cards; it must show
        // one of the canvas's honest empty-state titles. Report whichever legitimately appears.
        await page.goto('/#!/picture-book/' + pb2Empty + '/workflow', { timeout: 45000 });
        // Wait for the route to settle: either an empty-state title or (unexpectedly) a node card.
        // waitForFunction(fn, arg, options) — the timeout goes in the THIRD argument.
        await page.waitForFunction((states) => {
            const t = document.body.innerText || '';
            return states.some((s) => t.includes(s)) || document.querySelector('[data-node-id]') !== null;
        }, EMPTY_STATES, { timeout: 120000 });

        const nodeCards = await page.locator('[data-node-id]').count();
        const bodyText = await page.evaluate(() => document.body.innerText || '');
        const shown = EMPTY_STATES.find((s) => bodyText.includes(s)) || null;
        console.log('[W5] un-rendered book: nodeCards =', nodeCards, '| empty-state shown =', JSON.stringify(shown));
        await page.screenshot({ path: shot('pb-overhaul-W5-empty-state'), fullPage: true });
        console.log('[W5] screenshot ->', shot('pb-overhaul-W5-empty-state'));

        expect(nodeCards, 'W5: un-rendered book shows NO node cards').toBe(0);
        expect(shown, 'W5: one of the canvas empty-state titles ' + JSON.stringify(EMPTY_STATES)
            + ' is shown for an un-rendered book; body was: ' + bodyText.substring(0, 400)).toBeTruthy();
        console.log('[W5] durationMs =', fmtMs(Date.now() - testStart));
    });
});
