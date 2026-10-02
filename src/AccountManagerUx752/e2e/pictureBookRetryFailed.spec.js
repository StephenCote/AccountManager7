// @ts-check
/**
 * PictureBook — retrying ONLY the passages a chunked extraction could not read.
 *
 * THE DEFECT THIS PROVES FIXED. When a model refused a passage (content policy), stalled mid-reply or
 * timed out, the chunk was reported as "could not be read" and that was the end of it: the only way
 * to get those scenes was to resubmit the WHOLE document, re-paying for every passage that had
 * already succeeded. Now a completed run that still has failed passages keeps its server checkpoint
 * (scenes + per-passage failure records, chunksProcessed = total), and
 * `POST /{workObjectId}/extract-retry-failed` re-runs just those passages — with a different
 * chatConfig if the user switched models — and returns the merged list in the same shape as
 * extract-scenes-only's chunked branch.
 *
 * HOW A REAL FAILURE IS PRODUCED HERE. A refusal cannot be forced out of a live model on demand, so
 * the first run uses a chatConfig whose system.connection has a deliberately short requestTimeout
 * (RETRY_SLOW_TIMEOUT_S). Every passage then fails the honest way — "Request timed out after N
 * seconds" from Chat, classified `kind:"timeout"` by the chunk loop — against the real model
 * server, and the retry runs the same passages with the normal 300s config. The selective merge
 * (only chunk N re-run, placed back in document order, stale reason replaced) is pinned chunk by
 * chunk in Objects7's TestExtractChunkLoop against the real DB; this spec proves the transport,
 * the kept checkpoint and the live LLM round trip.
 *
 * R1 needs no LLM and always runs. R2 is minutes of real LLM work and is gated behind
 * PB_ASYNC_TESTS=1 like pictureBookAsyncJob.spec.js. Run it serially:
 *   PB_ASYNC_TESTS=1 npx playwright test e2e/pictureBookRetryFailed.spec.js --workers=1 --project=chromium
 *
 * NEVER uses the admin user — ensureSharedTestUser()/ensureChatConfig() provision; every assertion
 * runs as e2etest_shared.
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const JOB = REST + '/job';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));

const LLM_ENABLED = process.env.PB_ASYNC_TESTS === '1';

/** Same source as pictureBookAsyncJob.spec.js: over MAX_EXTRACTION_TEXT_CHARS, so it chunks. */
const AIME_PDF = path.resolve(SPEC_DIR, '../../AccountManagerObjects7/media/AIME.pdf');

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';

/**
 * Must be >= PictureBookUtil.LLM_INFRA_FAILURE_MS (5s): a reply that fails FASTER than that is
 * treated as "nothing is listening" and trips the circuit breaker (stopped-early, not retryable),
 * whereas a timeout at or above it is "the server is alive but slow" — recorded per passage and the
 * run continues to the end. Well under the ~60-110s a real chunk extraction takes, so every
 * passage times out.
 */
const RETRY_SLOW_TIMEOUT_S = 8;
const SLOW_CONFIG = 'e2e-pb-retry-slow';
const SLOW_CONN = 'e2e-pb-retry-slow-conn';

const LLM_TEST_TIMEOUT_MS = 25 * 60 * 1000;

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
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed: HTTP ' + resp.status());
    }
}

function b64Path(p) {
    return 'B64-' + Buffer.from(p).toString('base64').replace(/=/g, '%3D');
}

async function dataDir(request) {
    const dirResp = await request.get(REST + '/path/make/auth.group/data/' + b64Path('~/Data'));
    const dir = await dirResp.json();
    expect(dir && dir.id, '~/Data group must resolve').toBeTruthy();
    return dir;
}

/** Upload AIME.pdf as a data.data the extraction endpoint can read. */
async function uploadAime(request, suffix) {
    const dir = await dataDir(request);
    const bytes = fs.readFileSync(AIME_PDF);
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data',
            groupId: dir.id,
            groupPath: dir.path,
            name: 'AIME-retry-' + suffix + '.pdf',
            contentType: 'application/pdf',
            dataBytesStore: bytes.toString('base64')
        }
    });
    expect(resp.status(), 'data.data create').toBe(200);
    const created = await resp.json();
    expect(created && created.objectId).toBeTruthy();
    const fullResp = await request.get(REST + '/model/data.data/' + created.objectId + '/full');
    const full = await fullResp.json();
    expect(full.dataBytesStore, 'uploaded bytes must persist').toBeTruthy();
    return created.objectId;
}

/**
 * The server checkpoint: a data.note named `.pbExtractProgress.<workObjectId>` in the work's own
 * group. cache:false because it is rewritten by every run and the search cache is keyed on the
 * query, not invalidated by the note update.
 */
async function loadCheckpoint(request, dir, workObjectId) {
    const resp = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'data.note',
            fields: [
                { name: 'groupId', comparator: 'equals', value: dir.id },
                { name: 'organizationId', comparator: 'equals', value: dir.organizationId },
                { name: 'name', comparator: 'equals', value: '.pbExtractProgress.' + workObjectId }
            ],
            request: ['id', 'objectId', 'name', 'text'],
            recordCount: 1,
            cache: false
        }
    });
    expect(resp.status(), 'checkpoint note search').toBe(200);
    const body = await resp.json();
    const note = (body && body.results && body.results.length) ? body.results[0] : null;
    if (!note) return null;
    return JSON.parse(note.text);
}

async function pollJob(ctx, jobId, opts = {}) {
    const deadline = Date.now() + (opts.budgetMs || 22 * 60 * 1000);
    let last = null;
    while (Date.now() < deadline) {
        const resp = await ctx.get(JOB + '/' + jobId);
        expect(resp.status(), 'job poll').toBe(200);
        const job = await resp.json();
        const sig = `${job.status} ${job.current}/${job.total}`;
        if (sig !== last) {
            console.log(`[pbRetry] ${jobId.slice(0, 8)} ${job.kind} ${sig} elapsed=${job.elapsed}s`);
            last = sig;
        }
        if (job.terminal) return job;
        await new Promise(r => setTimeout(r, 3000));
    }
    throw new Error('job ' + jobId + ' did not reach a terminal state in time');
}

/** failedExtractions arrive as JSON strings (one per passage); parse and sort by chunk number. */
function failures(result) {
    const raw = (result && result.failedExtractions) || [];
    return raw.map(f => (typeof f === 'string' ? JSON.parse(f) : f))
        .sort((a, b) => (a.chunk || 0) - (b.chunk || 0));
}

test.describe.configure({ mode: 'serial' });

test.describe('PictureBook failed-passage retry', () => {

    test.beforeAll(async () => {
        const ctx = await pwRequest.newContext({
            baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true
        });
        try {
            await ensureSharedTestUser(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('R1: with nothing to retry the endpoint is 404 with a reason, and an unknown work is 404',
        async ({ request }) => {
            await loginCtx(request);
            const workObjectId = await uploadAime(request, 'r1-' + Date.now().toString(36));

            // A document that was never extracted has no checkpoint — there is nothing to retry,
            // and the client must be told so rather than get an empty 200 that looks like "no
            // failures left".
            const none = await request.post(PB + '/' + workObjectId + '/extract-retry-failed',
                { data: { schema: 'olio.pictureBookRequest' } });
            expect(none.status(), 'no checkpoint => 404').toBe(404);
            const body = await none.json();
            expect(typeof body.error, 'the 404 carries the reason in {error}').toBe('string');
            expect(body.error.length).toBeGreaterThan(0);

            // The async flavour must not hand back a 202 for a job that can only fail.
            const noneAsync = await request.post(PB + '/' + workObjectId + '/extract-retry-failed?async=true',
                { data: { schema: 'olio.pictureBookRequest' } });
            expect(noneAsync.status(), 'async start is accepted; the job itself reports the miss').toBe(202);
            const job = await pollJob(request, (await noneAsync.json()).jobId, { budgetMs: 60000 });
            expect(job.status).toBe('failed');
            expect(job.kind).toBe('pb.retryFailedChunks');
            expect(String(job.error || '')).toMatch(/checkpoint|retry|extract/i);

            const unknown = await request.post(PB + '/00000000-0000-0000-0000-000000000000/extract-retry-failed',
                { data: { schema: 'olio.pictureBookRequest' } });
            expect(unknown.status(), 'unknown work => 404').toBe(404);

            await request.delete(REST + '/model/data.data/' + workObjectId);
        });

    test('R2: passages that failed stay on the server and are re-run alone, with another chatConfig',
        async ({ request }) => {
            test.skip(!LLM_ENABLED,
                'PB_ASYNC_TESTS!=1 — skips live LLM extraction');
            test.setTimeout(LLM_TEST_TIMEOUT_MS);

            // Two configs on the SAME resolved route (LiteLLM-first, see resolveChatRoute): the
            // "slow" one differs only in its connection's requestTimeout, so its failures are real
            // timeouts against the real model server, not a mis-addressed host.
            const slowConfig = await ensureChatConfig(request, null, {
                configName: SLOW_CONFIG, connectionName: SLOW_CONN, requestTimeout: RETRY_SLOW_TIMEOUT_S
            });
            const goodConfig = await ensureChatConfig(request, null);
            expect(typeof slowConfig, 'slow chatConfig name').toBe('string');
            expect(typeof goodConfig, 'normal chatConfig name').toBe('string');
            expect(slowConfig).not.toBe(goodConfig);

            await loginCtx(request);
            const dir = await dataDir(request);
            const workObjectId = await uploadAime(request, 'r2-' + Date.now().toString(36));
            test.info().annotations.push({ type: 'workObjectId', description: workObjectId });

            // ── Run 1: every passage times out, the run still reaches the end ────────────────
            const start = await request.post(
                PB + '/' + workObjectId + '/extract-scenes-only?async=true',
                { data: { schema: 'olio.pictureBookRequest', chatConfig: slowConfig } });
            expect(start.status(), 'async start').toBe(202);
            const run1 = await pollJob(request, (await start.json()).jobId);
            expect(run1.status, 'run 1 must not fail outright').toBe('completed');
            const r1 = run1.result;
            expect(r1.chunked, 'AIME must take the chunked branch').toBe(true);
            const totalChunks = run1.total;
            expect(totalChunks).toBeGreaterThan(1);

            const failed1 = failures(r1);
            console.log('[pbRetry] run 1: ' + failed1.length + '/' + totalChunks + ' passages failed, '
                + (r1.sceneList || []).length + ' scenes; kinds=' + failed1.map(f => f.kind).join(','));
            expect(failed1.length, 'the short timeout must have failed at least one passage').toBeGreaterThan(0);
            for (const f of failed1) {
                expect(f.kind, 'a timeout against a live server is recorded as a timeout, not a breaker trip')
                    .toBe('timeout');
                expect(f.stoppedEarly, 'a timeout must not stop the run').toBeFalsy();
                expect(f.error).toMatch(new RegExp('timed out after ' + RETRY_SLOW_TIMEOUT_S + ' seconds'));
                expect(f.chunk).toBeGreaterThanOrEqual(1);
                expect(f.chunk).toBeLessThanOrEqual(totalChunks);
            }
            // "Slow is alive": the loop records the failure and continues to the LAST passage.
            expect(r1.extractionComplete, 'timeouts do not stop the run early').toBe(true);
            expect(run1.current, 'every passage was attempted').toBe(totalChunks);

            // The checkpoint is KEPT on completion because passages failed — this is what makes the
            // retry possible without resubmitting the document.
            const cp1 = await loadCheckpoint(request, dir, workObjectId);
            expect(cp1, 'a completed run with failed passages keeps its checkpoint').not.toBeNull();
            expect(cp1.chunksProcessed, 'kept checkpoint is marked complete').toBe(totalChunks);
            expect(cp1.totalChunks).toBe(totalChunks);
            expect((cp1.failedExtractions || []).length).toBe(failed1.length);
            expect((cp1.scenes || []).length).toBe((r1.sceneList || []).length);

            // A plain re-run on a complete-with-failures checkpoint makes no LLM calls: it hands the
            // stored result straight back, failures included. The user is NOT silently charged again.
            const rerun = await request.post(
                PB + '/' + workObjectId + '/extract-scenes-only?async=true',
                { data: { schema: 'olio.pictureBookRequest', chatConfig: goodConfig } });
            expect(rerun.status()).toBe(202);
            const t0 = Date.now();
            const rerunJob = await pollJob(request, (await rerun.json()).jobId);
            expect(rerunJob.status).toBe('completed');
            expect(Date.now() - t0, 'a plain re-run must not re-extract (no LLM calls)').toBeLessThan(60000);
            expect(failures(rerunJob.result).length, 'plain re-run still reports the failures').toBe(failed1.length);

            // ── Retry: ONLY the failed passages, with the normal config ───────────────────────
            const retry = await request.post(
                PB + '/' + workObjectId + '/extract-retry-failed?async=true',
                { data: { schema: 'olio.pictureBookRequest', chatConfig: goodConfig } });
            expect(retry.status(), 'retry start').toBe(202);
            const retryBody = await retry.json();
            expect(retryBody.jobId).toBeTruthy();
            const retryJob = await pollJob(request, retryBody.jobId);
            expect(retryJob.kind).toBe('pb.retryFailedChunks');
            expect(retryJob.key).toBe(workObjectId);
            expect(retryJob.status, 'retry must not fail outright').toBe('completed');
            // The job's total is the number of passages re-run — exactly the failed ones, not the
            // whole document.
            expect(retryJob.total, 'only the failed passages are re-run').toBe(failed1.length);

            const r2 = retryJob.result;
            expect(r2.chunked).toBe(true);
            expect(r2.extractionComplete, 'the retried run still spans the whole document').toBe(true);
            const failed2 = failures(r2);
            console.log('[pbRetry] retry: ' + failed2.length + ' passage(s) still failed, '
                + (r2.sceneList || []).length + ' scenes; kinds=' + failed2.map(f => f.kind).join(','));

            // The retry must have recovered passages: fewer failures, more scenes. A single live
            // passage may legitimately fail again (the LLM is real); what may NOT happen is nothing
            // changing, or a passage being lost.
            expect(failed2.length, 'the retry must recover at least one passage').toBeLessThan(failed1.length);
            expect((r2.sceneList || []).length, 'recovered passages yield scenes')
                .toBeGreaterThan((r1.sceneList || []).length);
            const retriedChunks = new Set(failed1.map(f => f.chunk));
            for (const f of failed2) {
                expect(retriedChunks.has(f.chunk), 'a still-failed passage must be one that was retried').toBe(true);
                expect(f.error, 'a passage that failed again carries the NEW reason, not the stale timeout')
                    .not.toMatch(new RegExp('timed out after ' + RETRY_SLOW_TIMEOUT_S + ' seconds'));
            }
            // Merged result is in document order with contiguous indexes and provenance.
            (r2.sceneList || []).forEach((s, i) => {
                expect(s.index, 'scene indexes are reassigned 0..n-1 after the merge').toBe(i);
                expect(typeof s.sourceChunk, 'each scene keeps the passage it came from').toBe('number');
            });
            for (let i = 1; i < (r2.sceneList || []).length; i++) {
                expect(r2.sceneList[i].sourceChunk).toBeGreaterThanOrEqual(r2.sceneList[i - 1].sourceChunk);
            }

            // Checkpoint fate: cleared the moment no failure remains, otherwise rewritten with the
            // shrunken failure list so a further retry targets only what is still broken.
            const cp2 = await loadCheckpoint(request, dir, workObjectId);
            if (failed2.length === 0) {
                expect(cp2, 'no failures left => checkpoint cleared').toBeNull();
                const again = await request.post(PB + '/' + workObjectId + '/extract-retry-failed',
                    { data: { schema: 'olio.pictureBookRequest', chatConfig: goodConfig } });
                expect(again.status(), 'nothing left to retry => 404').toBe(404);
            } else {
                expect(cp2, 'failures left => checkpoint kept').not.toBeNull();
                expect(cp2.chunksProcessed).toBe(totalChunks);
                expect((cp2.failedExtractions || []).length).toBe(failed2.length);
                expect((cp2.scenes || []).length).toBe((r2.sceneList || []).length);
            }

            await request.delete(REST + '/model/data.data/' + workObjectId);
        });
});
