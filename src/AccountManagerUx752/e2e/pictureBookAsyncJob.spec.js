/**
 * Async background-job E2E for PictureBook scene extraction.
 *
 * THE DEFECT THIS PROVES FIXED. `POST /{workObjectId}/extract-scenes-only` was one synchronous
 * request. Measured on this stack 2026-09-13: a 17-chunk extraction ran ~27 minutes, nginx's
 * `proxy_read_timeout` returned 504 at exactly 900s while Tomcat carried on to chunk 11/17, and
 * every extracted scene was discarded — nothing was persisted, and nothing server-side had
 * "failed", so nothing was even logged. The decisive property is therefore NOT duration: it is
 * that the result survives the originating connection going away. Test A3 below asserts exactly
 * that by collecting the result from a SEPARATE browser context.
 *
 * Run against the Docker UAT stack (single origin serving Ux + /AccountManagerService7):
 *   PB_ASYNC_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookAsyncJob.spec.js --workers=1 --project=chromium
 *
 * GATED + SERIAL on purpose. Every test here drives real LLM extraction against the Ollama server
 * on the DGX Spark (192.168.1.42); the job pool caps concurrency at 2 because that box is known to
 * fall over under sustained load, and firing this in parallel with other LLM/SD work crashes it.
 * `--workers=1` and the PB_ASYNC_TESTS gate are both load-bearing.
 *
 * NEVER uses the admin user for assertions — ensureSharedTestUser()/ensureChatConfig() provision;
 * every assertion runs as e2etest_shared.
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

const ASYNC_ENABLED = process.env.PB_ASYNC_TESTS === '1';

/**
 * media/AIME.pdf is ~2500 words (aiDocs/PictureBookDesign.md:5) — comfortably over
 * MAX_EXTRACTION_TEXT_CHARS = 8000, so it takes the CHUNKED branch with no configuration changes.
 * Do NOT substitute the abridged 2,254-char AIME_TEXT constant from pbCharacterList.spec.js: that
 * one is below the threshold and exercises the single-shot path instead.
 */
const AIME_PDF = path.resolve(SPEC_DIR, '../../AccountManagerObjects7/media/AIME.pdf');

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
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed: HTTP ' + resp.status());
    }
}

async function newLoggedInContext(user, password) {
    const ctx = await pwRequest.newContext({
        baseURL: test.info().project.use.baseURL,
        ignoreHTTPSErrors: true
    });
    const resp = await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: user || SHARED_USER,
            credential: Buffer.from(password || SHARED_PASSWORD).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('secondary login failed: HTTP ' + resp.status());
    }
    return ctx;
}

function b64Path(p) {
    return 'B64-' + Buffer.from(p).toString('base64').replace(/=/g, '%3D');
}

/** Upload AIME.pdf as a data.data the extraction endpoint can read. */
async function uploadAime(request, suffix) {
    const dirResp = await request.get(REST + '/path/make/auth.group/data/' + b64Path('~/Data'));
    const dir = await dirResp.json();
    expect(dir && dir.id, '~/Data group must resolve').toBeTruthy();

    const bytes = fs.readFileSync(AIME_PDF);
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data',
            groupId: dir.id,
            groupPath: dir.path,
            name: 'AIME-async-' + suffix + '.pdf',
            contentType: 'application/pdf',
            // The byte field is dataBytesStore. Sending `byteStore` is silently dropped by the
            // filtered deserializer, producing a 0-byte record with NO error — extraction then
            // fails with the misleading "No text content found in work".
            dataBytesStore: bytes.toString('base64')
        }
    });
    expect(resp.status(), 'data.data create').toBe(200);
    const created = await resp.json();
    expect(created && created.objectId).toBeTruthy();

    // Always verify the bytes actually landed; a 0-byte record looks identical until extraction.
    const fullResp = await request.get(REST + '/model/data.data/' + created.objectId + '/full');
    const full = await fullResp.json();
    expect(full.dataBytesStore, 'uploaded bytes must persist').toBeTruthy();
    return created.objectId;
}

async function startAsyncExtract(request, workObjectId, chatConfigName, query) {
    const resp = await request.post(
        PB + '/' + workObjectId + '/extract-scenes-only?async=true' + (query || ''),
        { data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName } });
    expect(resp.status(), 'async start must be 202 Accepted').toBe(202);
    const body = await resp.json();
    expect(body.jobId, 'a 202 must carry a jobId').toBeTruthy();
    return body.jobId;
}

/**
 * Poll a job to a terminal state. Bounded by a DEADLINE, not a poll count — the production client
 * is too, because 27 minutes at 3s is 540 polls and a count cap stops meaning anything.
 */
async function pollJob(ctx, jobId, opts = {}) {
    const deadline = Date.now() + (opts.budgetMs || 25 * 60 * 1000);
    let last = null;
    while (Date.now() < deadline) {
        const resp = await ctx.get(JOB + '/' + jobId);
        expect(resp.status(), 'job poll').toBe(200);
        const job = await resp.json();
        const sig = `${job.status} ${job.current}/${job.total}`;
        if (sig !== last) {
            console.log(`[pbAsync] ${jobId.slice(0, 8)} ${sig} elapsed=${job.elapsed}s`);
            last = sig;
        }
        if (opts.onTick) await opts.onTick(job);
        if (job.terminal) return job;
        await new Promise(r => setTimeout(r, 3000));
    }
    throw new Error('job ' + jobId + ' did not reach a terminal state in time');
}

// A chunked AIME extraction is minutes of real LLM work, so the default 60s per-test timeout
// kills these mid-run — the first attempt failed with "Test timeout of 60000ms exceeded" while
// the server was happily on chunk 3/5. The poller's own budget is irrelevant if Playwright has
// already aborted the test.
const LLM_TEST_TIMEOUT_MS = 20 * 60 * 1000;

test.describe.configure({ mode: 'serial' });

test.describe('PictureBook async extraction jobs (LLM, gated)', () => {
    let chatConfigName = null;
    let workObjectId = null;

    test.beforeAll(async () => {
        if (!ASYNC_ENABLED) return;
        const ctx = await pwRequest.newContext({
            baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true
        });
        try {
            await ensureSharedTestUser(ctx);
            // ensureChatConfig returns the config NAME as a plain string, not a record. Reading
            // `.name` off it yielded undefined, the body then omitted chatConfig entirely, the
            // server fell back to resolving "generalChat" (which this user is DENIED), and every
            // LLM call returned nothing — so the extraction "completed" with zero scenes while
            // A1/A2 still passed. Fail loudly here instead of testing against a broken config.
            chatConfigName = await ensureChatConfig(ctx, null);
            if (!chatConfigName || typeof chatConfigName !== 'string') {
                throw new Error('ensureChatConfig did not yield a usable chat config name: '
                    + JSON.stringify(chatConfigName));
            }
            await loginCtx(ctx);
            workObjectId = await uploadAime(ctx, Date.now().toString(36));
        } finally {
            await ctx.dispose();
        }
    });

    test('A1: async start returns 202 + jobId immediately instead of holding the request open',
        async ({ request }) => {
            test.skip(!ASYNC_ENABLED,
                'PB_ASYNC_TESTS!=1 — skips live LLM extraction (Ollama on the DGX Spark 192.168.1.42)');
            test.setTimeout(LLM_TEST_TIMEOUT_MS);
            await loginCtx(request);

            const t0 = Date.now();
            const jobId = await startAsyncExtract(request, workObjectId, chatConfigName);
            const elapsed = Date.now() - t0;

            // The whole point: the POST returns while the work is still running. A chunked AIME
            // extraction takes minutes, so anything near that means it ran synchronously.
            expect(elapsed, 'the 202 must come back promptly, not after the run').toBeLessThan(20000);

            const first = await request.get(JOB + '/' + jobId);
            expect(first.status()).toBe(200);
            const job = await first.json();
            expect(['queued', 'running', 'completed']).toContain(job.status);
            expect(job.kind).toBe('pb.extractScenes');
            expect(job.key).toBe(workObjectId);

            // CONTRACT CHECK. src/test/asyncJobPoller.test.js drives the production poller against
            // hand-written fixtures, so a server-side field rename would leave BOTH sides green
            // while the real client broke. This is the one place the two meet — assert the exact
            // field names the poller depends on, so a rename fails here instead of in production.
            for (const key of ['jobId', 'kind', 'key', 'status', 'phase', 'current', 'total',
                               'elapsed', 'cancelled', 'terminal']) {
                expect(job, 'JobService must keep emitting "' + key + '" — the client poller reads it')
                    .toHaveProperty(key);
            }
            expect(typeof job.terminal, '`terminal` drives the poll loop and must be boolean')
                .toBe('boolean');
            expect(typeof job.current).toBe('number');
            expect(typeof job.total).toBe('number');

            test.info().annotations.push({ type: 'jobId', description: jobId });
            const done = await pollJob(request, jobId);
            // Assert the run actually DID something. Checking only the status transitions let a
            // completely broken extraction (unresolvable chat config -> no LLM response -> circuit
            // breaker at chunk 2) pass this test.
            expect(done.status, 'the run must not fail').not.toBe('failed');
            const produced = (done.result && done.result.sceneList) || [];
            expect(produced.length, 'a healthy extraction must produce scenes').toBeGreaterThan(0);
            expect(done.result.extractionComplete,
                'a run that stopped early must NOT report itself complete').toBe(true);
        });

    test('A2: an unknown or non-owned jobId is 404 and cannot be cancelled',
        async ({ request }) => {
            test.skip(!ASYNC_ENABLED, 'PB_ASYNC_TESTS!=1 — skips live LLM extraction');
            test.setTimeout(LLM_TEST_TIMEOUT_MS);
            await loginCtx(request);

            // Ownership is enforced in the registry by a composite (principal, jobId) key, so an
            // unknown id and ANOTHER USER'S id answer identically — the endpoint must not be usable
            // to probe whether someone else has a job in flight.
            const bogus = '00000000-0000-4000-8000-000000000000';
            const getResp = await request.get(JOB + '/' + bogus);
            expect(getResp.status()).toBe(404);

            const cancelResp = await request.post(JOB + '/' + bogus + '/cancel');
            expect(cancelResp.status()).toBe(200);
            expect((await cancelResp.json()).cancelled).toBe(false);
        });

    test('A3: the result survives the originating connection — collected from a SEPARATE context',
        async ({ request }) => {
            test.skip(!ASYNC_ENABLED, 'PB_ASYNC_TESTS!=1 — skips live LLM extraction');
            test.setTimeout(LLM_TEST_TIMEOUT_MS);
            await loginCtx(request);

            // Start on one context...
            const starter = await newLoggedInContext();
            let jobId;
            try {
                jobId = await startAsyncExtract(starter, workObjectId, chatConfigName);
            } finally {
                // ...then DESTROY it. This is the reported failure reproduced deliberately: the
                // connection that asked for the work is gone long before the work finishes.
                await starter.dispose();
            }

            const collector = await newLoggedInContext();
            try {
                const job = await pollJob(collector, jobId);
                expect(job.status, 'job must complete despite the starter being gone')
                    .toBe('completed');

                const scenes = (job.result && job.result.sceneList) || [];
                expect(scenes.length, 'the full scene list must still be collectable')
                    .toBeGreaterThan(0);
                expect(job.result.chunked, 'AIME.pdf is >8000 chars so it must chunk').toBe(true);
                expect(job.result.extractionComplete).toBe(true);
                expect(job.result.chunksProcessed, 'real chunk count, not the old hardcoded -1')
                    .toBeGreaterThan(0);

                // Resume must never duplicate or drop a scene: revisions and removals are matched
                // BY TITLE against the accumulated list, so duplicate titles would silently make a
                // later chunk's revision land on the wrong scene.
                const titles = scenes.map(s => s.title).filter(Boolean);
                expect(new Set(titles).size, 'scene titles must be distinct')
                    .toBe(titles.length);
            } finally {
                await collector.dispose();
            }
        });

    test('A4: a mid-run cancel stops the run and KEEPS the scenes already extracted',
        async ({ request }) => {
            test.skip(!ASYNC_ENABLED, 'PB_ASYNC_TESTS!=1 — skips live LLM extraction');
            test.setTimeout(LLM_TEST_TIMEOUT_MS);
            await loginCtx(request);

            // fresh=true so this starts from chunk 1 rather than resuming the checkpoint a previous
            // cancelled run may have left behind.
            const jobId = await startAsyncExtract(request, workObjectId, chatConfigName, '&fresh=true');

            let cancelled = false;
            const job = await pollJob(request, jobId, {
                onTick: async (j) => {
                    if (!cancelled && (j.current || 0) >= 1 && !j.terminal) {
                        const resp = await request.post(JOB + '/' + jobId + '/cancel');
                        expect(resp.status()).toBe(200);
                        cancelled = true;
                    }
                }
            });

            test.skip(!cancelled,
                'the run finished before a cancel could be issued — inconclusive, not a failure');

            expect(job.status).toBe('cancelled');
            // Cancellation is cooperative: the chunk loop breaks at a boundary and RETURNS what it
            // extracted. Discarding that would defeat the point of offering cancel at all.
            const partial = (job.result && job.result.sceneList) || [];
            expect(partial.length, 'partial scenes must be retained after a cancel')
                .toBeGreaterThan(0);
            expect(job.result.extractionComplete).toBe(false);
        });

    test('A5: GET /rest/job lists the caller\'s own jobs so a reloaded client can reattach',
        async ({ request }) => {
            test.skip(!ASYNC_ENABLED, 'PB_ASYNC_TESTS!=1 — skips live LLM extraction');
            test.setTimeout(LLM_TEST_TIMEOUT_MS);
            await loginCtx(request);

            const resp = await request.get(JOB);
            expect(resp.status()).toBe(200);
            const jobs = await resp.json();
            expect(Array.isArray(jobs)).toBe(true);

            // The earlier tests in this serial describe ran real extractions for this work id, and
            // finished jobs are retained for 30 minutes precisely so a disconnected client can come
            // back for them.
            const mine = jobs.filter(j => j.kind === 'pb.extractScenes' && j.key === workObjectId);
            expect(mine.length, 'this run\'s jobs must be listed for reattach')
                .toBeGreaterThan(0);
            for (const j of mine) {
                expect(j.jobId).toBeTruthy();
                // The listing deliberately omits results (a scene list is large) — fetch the
                // individual job to collect one.
                expect(j.result).toBeUndefined();
            }
        });
});
