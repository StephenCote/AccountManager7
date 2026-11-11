/**
 * pictureBookWorkflowOverhaul.spec.js — end-to-end proof of the "PictureBook Workflow Overhaul"
 * W-series through the real REST + UX stack, against a live LLM (whichever tier
 * helpers/api.js resolveChatRoute() answers — local Ollama container via LiteLLM, Azure, or the
 * DGX at 192.168.1.42; the `[e2e-llm] route=… ` line in the run log says which) via the Docker stack
 * on https://127.0.0.1:9443.
 *
 * Gating (a skipped gated test is NOT a pass — run it with the flag set and read the summary line):
 *   PB_LLM_TESTS=1 (or PB_SD_TESTS=1)  runs W5 (LLM extraction, no SD). LLM-touching: always --workers=1.
 *
 * This exercises the wizard Step-2 Continue sequence (pictureBook.js):
 *     createChapBookRecord(slug, title)  -> pb2BookObjectId
 *     createFromScenes(work, chat, genre, name, scenes, charStubs, pb2BookObjectId) -> meta
 *
 * CORE (W1/W4) and W2 — the graph written by the PB1 scene render and its graphWriteFailures — went
 * with POST /scene/{id}/generate at Phase 10 P10-1; the V3 node render → canvas proof is
 * pictureBookNewBookRender.spec.js. What remains:
 *   W5:           a freshly-created book that has NOT been rendered (its own source note, its own
 *                 extraction) is navigated to the canvas:
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
import { ensureSharedTestUser, ensureChatConfig, ensurePath } from './helpers/api.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE + '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const JOB = REST + '/job';

// Same flag conventions as pictureBook.spec.js / pictureBookV3Ux.spec.js (PB_SD_TESTS) and the
// LLM-only PictureBook Ux specs (PB_LLM_TESTS). Visible test.skip() below, never a silent return.
const LLM_ENABLED = process.env.PB_SD_TESTS === '1' || process.env.PB_LLM_TESTS === '1';

const SHOTS = path.resolve(__dirname, 'screenshots');
function shot(name) { fs.mkdirSync(SHOTS, { recursive: true }); return path.join(SHOTS, name + '.png'); }
function fmtMs(ms) { return ms + 'ms (' + (ms / 60000).toFixed(1) + ' min)'; }

// Verbatim excerpt of Stephen's "The Big Way Out" (src/AccountManagerObjects7/media/The Big Way Out.doc),
// extracted with antiword. Real document content — NOT synthetic filler.
const BWO_TEXT = fs.readFileSync(path.resolve(__dirname, 'fixtures', 'bigWayOut.txt'), 'utf8');

// Shared state across the serial tests.
let sharedCreds;         // { testUserName, testPassword }
let chatConfigName;      // 'e2e-chapbook-llm'
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

test.describe.serial('PictureBook Workflow Overhaul (W-series) — live LLM end-to-end', () => {

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

        // Self-contained: W5 provisions its OWN source note + extraction. The book is created and
        // populated but NOT rendered.
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
