/**
 * Async job client tests — the poller that replaced holding one fetch open for a whole extraction.
 *
 * THE BUG these exist for: scene extraction was a single synchronous POST. On a real document the
 * run took ~27 minutes, nginx's proxy_read_timeout returned 504 at exactly 900s while the server
 * carried on to chunk 11/17, and every extracted scene was discarded — the only copy was headed
 * for a socket that had already closed, and nothing server-side had "failed" so nothing logged an
 * error. Extraction now starts a background job (202 + jobId) whose result is retained
 * server-side, and the client polls it.
 *
 * Covered here, at the unit level:
 *   - startExtractScenes sends async=true, requires 202, and passes fresh=true through
 *   - pollJob stops on `terminal`, reports progress every tick, and is bounded by a DEADLINE
 *     rather than a poll count (27 min at 3s = 540 polls; a count cap is meaningless)
 *   - a deadline overrun does NOT cancel the job (the result is still collectable)
 *   - 401/403 is distinguishable from a job failure and triggers forceLogin
 *   - scenesFromResult unwraps all three response shapes this endpoint can produce
 *
 * The live behaviour against the real backend (checkpointing, restart resume, mid-run cancel) is
 * covered by the Objects7 JUnit suite and the Docker integration driver.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
    startExtractScenes, pollJob, cancelJob, listJobs, getJob, scenesFromResult, JobAuthError
} from '../workflows/sceneExtractor.js';
import { am7client } from '../core/am7client.js';

function jsonResponse(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status: status,
        json: async () => body
    };
}

let originalFetch;

beforeEach(() => {
    originalFetch = global.fetch;
    vi.useRealTimers();
});

afterEach(() => {
    global.fetch = originalFetch;
    delete am7client.forceLogin;
});

describe('startExtractScenes', () => {
    it('requests the ASYNC path and returns the jobId from a 202', async () => {
        let seenUrl = null;
        let seenBody = null;
        global.fetch = vi.fn(async (url, opts) => {
            seenUrl = url;
            seenBody = JSON.parse(opts.body);
            return jsonResponse(202, { jobId: 'job-1', status: 'queued' });
        });

        let started = await startExtractScenes('work-abc', 'cfgA', null, null);

        expect(started.jobId).toBe('job-1');
        expect(seenUrl).toContain('/work-abc/extract-scenes-only');
        expect(seenUrl).toContain('async=true');
        // No `fresh` unless asked — a resumable checkpoint must not be silently discarded.
        expect(seenUrl).not.toContain('fresh=true');
        expect(seenBody.chatConfig).toBe('cfgA');
        expect(seenBody.schema).toBe('olio.pictureBookRequest');
    });

    it('passes fresh=true so a user can discard a resumable checkpoint on purpose', async () => {
        let seenUrl = null;
        global.fetch = vi.fn(async (url) => {
            seenUrl = url;
            return jsonResponse(202, { jobId: 'job-2', status: 'queued' });
        });

        await startExtractScenes('work-abc', null, null, null, { fresh: true });
        expect(seenUrl).toContain('fresh=true');
    });

    it('omits count when it is null, so the SERVER default applies', async () => {
        // Sending count:0 made the server ask the LLM for the 0 most notable scenes, which returns
        // a valid empty array fast and looks exactly like "the model found nothing".
        let seenBody = null;
        global.fetch = vi.fn(async (url, opts) => {
            seenBody = JSON.parse(opts.body);
            return jsonResponse(202, { jobId: 'j', status: 'queued' });
        });
        await startExtractScenes('w', null, null, null);
        expect('count' in seenBody).toBe(false);
    });

    it('treats a 200 as a failure — async mode must answer 202', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, []));
        await expect(startExtractScenes('w', null, null, null))
            .rejects.toThrow(/Start extract scenes failed: 200/);
    });
});

describe('pollJob', () => {
    it('polls until terminal and returns the finished payload', async () => {
        let ticks = [
            { jobId: 'j', status: 'running', current: 1, total: 5, terminal: false },
            { jobId: 'j', status: 'running', current: 3, total: 5, terminal: false },
            { jobId: 'j', status: 'completed', current: 5, total: 5, terminal: true,
              result: { sceneList: [{ title: 'A' }], chunked: true } }
        ];
        let i = 0;
        global.fetch = vi.fn(async () => jsonResponse(200, ticks[Math.min(i++, ticks.length - 1)]));

        let seen = [];
        let job = await pollJob('j', { intervalMs: 1, onProgress: (p) => seen.push(p.current) });

        expect(job.status).toBe('completed');
        expect(job.result.sceneList).toHaveLength(1);
        // Progress is reported on EVERY tick including the terminal one, so a caller that renders
        // from onProgress finishes showing 5/5 rather than freezing at the last in-flight value.
        expect(seen).toEqual([1, 3, 5]);
    });

    it('returns a CANCELLED job normally — partial scenes are a result, not an error', async () => {
        // Cancellation is cooperative: the chunk loop breaks at a boundary and returns what it
        // already extracted. Throwing here would discard exactly the work that design preserves.
        global.fetch = vi.fn(async () => jsonResponse(200, {
            jobId: 'j', status: 'cancelled', current: 2, total: 17, terminal: true,
            result: { sceneList: [{ title: 'A' }, { title: 'B' }], extractionComplete: false,
                      chunksProcessed: 2 }
        }));

        let job = await pollJob('j', { intervalMs: 1 });
        expect(job.status).toBe('cancelled');
        expect(job.result.sceneList).toHaveLength(2);
        expect(job.result.extractionComplete).toBe(false);
    });

    it('is bounded by a DEADLINE, not a poll count', async () => {
        // The reference summarize poller caps at a fixed tick count. For a 27-minute run at 3s a
        // second that is 540 ticks, so a count cap either kills healthy runs or means nothing.
        let calls = 0;
        global.fetch = vi.fn(async () => {
            calls++;
            return jsonResponse(200, { jobId: 'j', status: 'running', current: 1, total: 17, terminal: false });
        });

        await expect(pollJob('j', { intervalMs: 1, deadlineMs: 30 }))
            .rejects.toThrow(/Timed out waiting for job j/);
        // It kept polling until the clock ran out rather than stopping after N ticks.
        expect(calls).toBeGreaterThan(1);
    });

    it('does NOT cancel the job when it gives up waiting', async () => {
        // Giving up watching is not a reason to destroy the work: the result stays retained
        // server-side for a TTL precisely so a disconnected client can come back for it.
        let cancelCalls = 0;
        global.fetch = vi.fn(async (url, opts) => {
            if (opts && opts.method === 'POST' && String(url).includes('/cancel')) {
                cancelCalls++;
                return jsonResponse(200, { cancelled: true });
            }
            return jsonResponse(200, { jobId: 'j', status: 'running', current: 1, total: 9, terminal: false });
        });

        await expect(pollJob('j', { intervalMs: 1, deadlineMs: 20 })).rejects.toThrow(/Timed out/);
        expect(cancelCalls).toBe(0);
    });

    it('stops polling when the signal aborts, without cancelling the job', async () => {
        let ctrl = new AbortController();
        let cancelCalls = 0;
        global.fetch = vi.fn(async (url, opts) => {
            if (opts && opts.method === 'POST') { cancelCalls++; return jsonResponse(200, {}); }
            ctrl.abort();
            return jsonResponse(200, { jobId: 'j', status: 'running', current: 1, total: 4, terminal: false });
        });

        await expect(pollJob('j', { intervalMs: 1, signal: ctrl.signal }))
            .rejects.toMatchObject({ name: 'AbortError' });
        expect(cancelCalls).toBe(0);
    });

    it('surfaces a FAILED job as a terminal result carrying the error, not a thrown poll error', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, {
            jobId: 'j', status: 'failed', terminal: true, error: 'No text content found in work'
        }));
        let job = await pollJob('j', { intervalMs: 1 });
        expect(job.status).toBe('failed');
        expect(job.error).toBe('No text content found in work');
    });

    it('keeps a caller\'s onProgress exception from breaking the poll', async () => {
        let i = 0;
        let ticks = [
            { jobId: 'j', status: 'running', terminal: false, current: 1, total: 2 },
            { jobId: 'j', status: 'completed', terminal: true, current: 2, total: 2, result: [] }
        ];
        global.fetch = vi.fn(async () => jsonResponse(200, ticks[Math.min(i++, 1)]));
        let job = await pollJob('j', {
            intervalMs: 1,
            onProgress: () => { throw new Error('render blew up'); }
        });
        expect(job.status).toBe('completed');
    });
});

describe('authentication during a long run', () => {
    it('raises JobAuthError and triggers forceLogin on 401', async () => {
        // A 27-minute run can outlive the session. These workflow files use a bare fetch rather
        // than am7client, so without this the expiry surfaced as an opaque status code with no
        // re-login prompt.
        let forced = 0;
        am7client.forceLogin = () => { forced++; };
        global.fetch = vi.fn(async () => jsonResponse(401, {}));

        await expect(getJob('j')).rejects.toBeInstanceOf(JobAuthError);
        expect(forced).toBe(1);
    });

    it('raises JobAuthError on 403 as well', async () => {
        am7client.forceLogin = () => {};
        global.fetch = vi.fn(async () => jsonResponse(403, {}));
        await expect(getJob('j')).rejects.toMatchObject({ name: 'JobAuthError', status: 403 });
    });

    it('does not blow up when forceLogin is not registered', async () => {
        global.fetch = vi.fn(async () => jsonResponse(401, {}));
        await expect(getJob('j')).rejects.toBeInstanceOf(JobAuthError);
    });

    it('reports a dropped/expired job distinctly from an auth failure', async () => {
        // 404 also covers another user's jobId — the server answers identically for unknown and
        // non-owned on purpose, so a client cannot probe what anyone else is running.
        global.fetch = vi.fn(async () => jsonResponse(404, { error: 'Job not found' }));
        await expect(getJob('j')).rejects.toThrow(/no longer retained/);
    });
});

describe('cancelJob / listJobs', () => {
    it('reports cancelled:false rather than throwing when the server refuses', async () => {
        // cancelled:false is not an error — it also covers already-finished, already-cancelled,
        // unknown, and someone else's job, all deliberately indistinguishable.
        global.fetch = vi.fn(async () => jsonResponse(404, {}));
        let out = await cancelJob('j');
        expect(out.cancelled).toBe(false);
    });

    it('POSTs to the cancel route', async () => {
        let seen = null;
        global.fetch = vi.fn(async (url, opts) => {
            seen = { url: String(url), method: opts.method };
            return jsonResponse(200, { jobId: 'j', cancelled: true });
        });
        let out = await cancelJob('j');
        expect(seen.method).toBe('POST');
        expect(seen.url).toContain('/rest/job/j/cancel');
        expect(out.cancelled).toBe(true);
    });

    it('returns an empty list instead of throwing when the listing is unavailable', async () => {
        // Reattach is best-effort: failing to list jobs must not stop the wizard from opening.
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        expect(await listJobs()).toEqual([]);
    });

    it('tolerates a non-array body', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, { unexpected: true }));
        expect(await listJobs()).toEqual([]);
    });
});

describe('scenesFromResult', () => {
    it('unwraps all three shapes this endpoint produces', () => {
        // Short text returns a bare array; chunked text returns { sceneList, chunked }; the async
        // job wraps the latter as its result. Callers each used to re-derive this.
        expect(scenesFromResult([{ title: 'A' }])).toHaveLength(1);
        expect(scenesFromResult({ sceneList: [{ title: 'A' }, { title: 'B' }], chunked: true }))
            .toHaveLength(2);
        expect(scenesFromResult(null)).toEqual([]);
        expect(scenesFromResult({})).toEqual([]);
        expect(scenesFromResult({ sceneList: null })).toEqual([]);
    });

    it('does not mistake a non-array sceneList for scenes', () => {
        expect(scenesFromResult({ sceneList: 'oops' })).toEqual([]);
    });
});
