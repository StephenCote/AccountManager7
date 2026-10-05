/**
 * Live check for Chat's runaway detector on the document that exposed the problem.
 *
 * Reported 2026-10-05: an Ourselves.doc extraction "ran quickly for the first half, then seemed to
 * stall and hit the timeout (300 sec)". LiteLLM request 80b340dc showed it was not a stall: on
 * chunk 6/10 the model streamed the same two scene objects alternately, verbatim, at a steady
 * ~34 tok/s for the full 300 s and produced 48,710 characters of JSON that yielded zero scenes.
 * Chunk 8 went the same way. Chat's RunawayDetector now stops a buffer-mode reply once its last
 * 300 characters have already appeared 5 times, keeps the prefix before the repetition began, and
 * the chunk loop salvages the distinct scenes from it (TestChatRunawayDetection, TestRunawaySalvage,
 * TestExtractChunkLoop cover the mechanics against the recorded reply).
 *
 * What THIS spec proves, against the deployed stack and a real model: the whole document extracts,
 * and no passage is allowed to run to the 300 s per-call timeout any more. A model is free not to
 * loop on a given run — that is the point of the chatConfig tuning — so the detector firing is
 * reported (from the container log) but not required.
 *
 * Run against the Docker stack (MUST use 127.0.0.1 — localhost resolves to unmapped IPv6 ::1):
 *   PB_RUNAWAY_LIVE=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookRunawayLive.spec.js --workers=1 --project=chromium
 *
 * PB_RUNAWAY_MODEL=<litellm alias> runs the passages on that model instead of the route's default
 * PictureBook model, under its own chatConfig (the shared default is never repointed). The reported
 * run was on qwen3:30b-jos — the .42 DGX route — which is where the loop reproduces; the default
 * qwen3:8b-jos-ctr (the CPU-only in-stack container) extracts cleanly but at ~4 min a passage.
 *
 * GATED + SERIAL: ten passages of real LLM work. Never uses the admin user — ensureSharedTestUser()
 * / ensureChatConfig() provision, every call runs as e2etest_shared.
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const JOB = REST + '/job';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));

const LIVE = process.env.PB_RUNAWAY_LIVE === '1';
const MODEL_OVERRIDE = process.env.PB_RUNAWAY_MODEL || null;
const OURSELVES_DOC = path.resolve(SPEC_DIR, '../../AccountManagerObjects7/media/Ourselves.doc');

/// The connection's per-call hard timeout ensureChatConfig provisions (helpers/api.js default). The
/// reported failure was passages running right up to this; the detector must keep every passage
/// well under it.
const REQUEST_TIMEOUT_S = 300;
const LLM_TEST_TIMEOUT_MS = 40 * 60 * 1000;

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';

async function loginCtx(ctx) {
    const resp = await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: SHARED_USER,
            credential: Buffer.from(SHARED_PASSWORD).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login failed: HTTP ' + resp.status());
}

function b64Path(p) {
    return 'B64-' + Buffer.from(p).toString('base64').replace(/=/g, '%3D');
}

async function uploadOurselves(request, suffix) {
    const dirResp = await request.get(REST + '/path/make/auth.group/data/' + b64Path('~/Data'));
    const dir = await dirResp.json();
    expect(dir && dir.id, '~/Data group must resolve').toBeTruthy();

    const bytes = fs.readFileSync(OURSELVES_DOC);
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data',
            groupId: dir.id,
            groupPath: dir.path,
            name: 'Ourselves-runaway-' + suffix + '.doc',
            contentType: 'application/msword',
            // dataBytesStore, not byteStore — the latter is silently dropped (see pictureBookAsyncJob.spec.js).
            dataBytesStore: bytes.toString('base64')
        }
    });
    expect(resp.status(), 'data.data create').toBe(200);
    const created = await resp.json();
    expect(created && created.objectId).toBeTruthy();
    const full = await (await request.get(REST + '/model/data.data/' + created.objectId + '/full')).json();
    expect(full.dataBytesStore, 'uploaded bytes must persist').toBeTruthy();
    return created.objectId;
}

function parseFailures(result) {
    return ((result && result.failedExtractions) || []).map(f => {
        try { return typeof f === 'string' ? JSON.parse(f) : f; } catch (_) { return { raw: f }; }
    });
}

/// Best-effort: the detector logs [STREAM-RUNAWAY] to stdout in the Docker image. Diagnostic only.
function runawayLogLines(sinceIso) {
    try {
        const out = execSync('docker logs --since ' + sinceIso + ' am7test-am7-1 2>&1', { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
        return out.split(/\r?\n/).filter(l => l.includes('[STREAM-RUNAWAY]') || l.includes('started repeating itself'));
    } catch (e) {
        console.log('[runaway-live] could not read docker logs: ' + (e.message || e));
        return null;
    }
}

test.describe.configure({ mode: 'serial' });

test.describe('PictureBook — Ourselves.doc extraction with the runaway detector (LLM, gated)', () => {
    let chatConfigName = null;
    let workObjectId = null;

    test.beforeAll(async () => {
        if (!LIVE) return;
        const ctx = await pwRequest.newContext({ baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true });
        try {
            await ensureSharedTestUser(ctx);
            const cfgOpts = MODEL_OVERRIDE
                ? { configName: 'e2e-runaway-' + MODEL_OVERRIDE.replace(/[^A-Za-z0-9]+/g, '-'), model: MODEL_OVERRIDE, analyzeModel: MODEL_OVERRIDE }
                : {};
            chatConfigName = await ensureChatConfig(ctx, null, cfgOpts);
            if (!chatConfigName || typeof chatConfigName !== 'string') {
                throw new Error('ensureChatConfig did not yield a usable chat config name: ' + JSON.stringify(chatConfigName));
            }
            console.log('[runaway-live] chatConfig=' + chatConfigName + (MODEL_OVERRIDE ? ' model=' + MODEL_OVERRIDE : ' (route default model)'));
            await loginCtx(ctx);
            workObjectId = await uploadOurselves(ctx, Date.now().toString(36));
        } finally {
            await ctx.dispose();
        }
    });

    test('every passage of Ourselves.doc extracts without any passage running to the 300 s timeout', async ({ request }) => {
        test.skip(!LIVE, 'PB_RUNAWAY_LIVE!=1 — skips the live ten-passage LLM extraction');
        test.setTimeout(LLM_TEST_TIMEOUT_MS);
        await loginCtx(request);

        const startedAt = new Date();
        const resp = await request.post(PB + '/' + workObjectId + '/extract-scenes-only?async=true&fresh=true',
            { data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName } });
        expect(resp.status(), 'async start must be 202 Accepted').toBe(202);
        const { jobId } = await resp.json();
        expect(jobId).toBeTruthy();

        // Per-passage wall time, from the job's own progress counter.
        const chunkTimes = [];
        let lastCurrent = 0, lastTick = Date.now(), lastSig = null, job = null;
        const deadline = Date.now() + LLM_TEST_TIMEOUT_MS - 60000;
        while (Date.now() < deadline) {
            const r = await request.get(JOB + '/' + jobId);
            expect(r.status(), 'job poll').toBe(200);
            job = await r.json();
            const cur = job.current || 0;
            if (cur > lastCurrent) {
                const now = Date.now();
                for (let c = lastCurrent + 1; c <= cur; c++) chunkTimes.push({ chunk: c, seconds: Math.round((now - lastTick) / 1000) });
                lastCurrent = cur; lastTick = now;
            }
            const sig = `${job.status} ${cur}/${job.total}`;
            if (sig !== lastSig) { console.log(`[runaway-live] ${jobId.slice(0, 8)} ${sig} elapsed=${job.elapsed}s`); lastSig = sig; }
            if (job.terminal) break;
            await new Promise(res => setTimeout(res, 3000));
        }
        expect(job && job.terminal, 'job did not reach a terminal state').toBeTruthy();

        const result = job.result || {};
        const scenes = result.sceneList || [];
        const failures = parseFailures(result);
        console.log('[runaway-live] status=' + job.status + ' chunks=' + result.chunksProcessed + ' scenes=' + scenes.length
            + ' failures=' + failures.length + ' chunkSeconds=' + JSON.stringify(chunkTimes.map(c => c.seconds)));
        for (const f of failures) console.log('[runaway-live] failure chunk=' + f.chunk + ' kind=' + f.kind + ' error=' + f.error);
        const detectorLines = runawayLogLines(startedAt.toISOString());
        if (detectorLines) {
            console.log('[runaway-live] detector fired ' + detectorLines.length + ' time(s)');
            for (const l of detectorLines) console.log('  ' + l.slice(0, 300));
        }

        expect(job.status).toBe('completed');
        expect(result.chunked, 'Ourselves.doc is well over 8000 chars so it must chunk').toBe(true);
        expect(result.extractionComplete).toBe(true);
        expect(scenes.length, 'the document must yield scenes').toBeGreaterThan(0);

        // THE property. Before the detector, a looping passage ran at full speed until the 300 s
        // request timeout and yielded nothing. A passage that loops now is cut when the repetition
        // is unmistakable (5 copies of a 300-char window — ~2 minutes at the observed 34 tok/s).
        const slowest = chunkTimes.reduce((m, c) => (c.seconds > m.seconds ? c : m), { chunk: 0, seconds: 0 });
        expect(slowest.seconds, 'passage ' + slowest.chunk + ' ran to the per-call timeout: ' + JSON.stringify(chunkTimes))
            .toBeLessThan(REQUEST_TIMEOUT_S - 10);
        for (const f of failures) {
            expect(f.kind, 'a passage timed out: ' + JSON.stringify(f)).not.toBe('timeout');
        }
    });
});
