// @vitest-environment jsdom
/**
 * The picture-book wizard's async-extraction STATE MACHINE.
 *
 * Why: the server-side equivalents of these branches shipped two real defects before they had
 * tests — an interrupted run deleting its own checkpoint and reporting COMPLETED, and a stopped run
 * reporting extractionComplete:true after extracting zero scenes. The client half has the same
 * shape: a terminal job can be completed, cancelled-with-partial-scenes, cancelled-with-nothing, or
 * failed, and collapsing those is how a partial scene list gets presented as a finished one.
 *
 * Covered here: applyExtractJob's four terminal outcomes, doExtract's full lifecycle including the
 * finally-block reset, and reattachExtractJob's guard against starting a second run against a
 * document already being extracted.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

function jsonResponse(status, body) {
    return { ok: status >= 200 && status < 300, status, json: async () => body };
}

let origFetch;
let pb;

beforeEach(async () => {
    origFetch = global.fetch;
    pb = await import('../workflows/pictureBook.js');
    pb.__resetExtractStateForTest('work-1');
});

afterEach(() => {
    global.fetch = origFetch;
});

describe('applyExtractJob — the four terminal outcomes must stay distinguishable', () => {
    it('completed: scenes adopted, advances to step 2, NOT flagged partial', () => {
        pb.applyExtractJob({
            status: 'completed', terminal: true,
            result: { sceneList: [{ title: 'A' }, { title: 'B' }], chunked: true,
                      extractionComplete: true }
        });
        let s = pb.__extractStateForTest();
        expect(s.extractedScenes).toHaveLength(2);
        expect(s.step).toBe(2);
        expect(s.extractPartial).toBe(false);
        expect(s.extractError).toBeNull();
    });

    it('cancelled WITH scenes: kept, advances, and flagged PARTIAL', () => {
        // Cancellation is cooperative — the chunk loop returns what it already extracted. Throwing
        // those away, or presenting them as a finished list, are both wrong.
        pb.applyExtractJob({
            status: 'cancelled', terminal: true,
            result: { sceneList: [{ title: 'A' }], chunked: true, extractionComplete: false,
                      chunksProcessed: 2 }
        });
        let s = pb.__extractStateForTest();
        expect(s.extractedScenes).toHaveLength(1);
        expect(s.step).toBe(2);
        expect(s.extractPartial).toBe(true);
        expect(s.extractError).toBeNull();
    });

    it('completed but extractionComplete:false is ALSO partial', () => {
        // This is the interrupt / unreachable-LLM case: the job status is `completed` because
        // nobody cancelled, but the run stopped early. Keying the banner on status alone would
        // silently present a truncated scene list as finished.
        pb.applyExtractJob({
            status: 'completed', terminal: true,
            result: { sceneList: [{ title: 'A' }], chunked: true, extractionComplete: false }
        });
        let s = pb.__extractStateForTest();
        expect(s.extractPartial).toBe(true);
        expect(s.step).toBe(2);
    });

    it('cancelled with NO scenes: an error, not a silent empty step 2', () => {
        pb.applyExtractJob({
            status: 'cancelled', terminal: true, result: { sceneList: [], extractionComplete: false }
        });
        let s = pb.__extractStateForTest();
        expect(s.step).toBe(1);
        expect(s.extractError).toMatch(/cancelled/i);
    });

    it('failed: surfaces the server error and does not advance', () => {
        pb.applyExtractJob({
            status: 'failed', terminal: true, error: 'No text content found in work'
        });
        let s = pb.__extractStateForTest();
        expect(s.step).toBe(1);
        expect(s.extractError).toBe('No text content found in work');
        expect(s.extractedScenes).toHaveLength(0);
    });

    it('surfaces failedExtractions from either the result or the job', () => {
        pb.applyExtractJob({
            status: 'completed', terminal: true,
            result: { sceneList: [{ title: 'A' }], failedExtractions: ['chunk 3 unparseable'] }
        });
        expect(pb.__extractStateForTest().extractFailedChunks).toHaveLength(1);

        pb.__resetExtractStateForTest('work-1');
        pb.applyExtractJob({
            status: 'completed', terminal: true, failedExtractions: ['from the job'],
            result: { sceneList: [{ title: 'A' }] }
        });
        expect(pb.__extractStateForTest().extractFailedChunks).toEqual(['from the job']);
    });

    it('a bare array result (short-text path) is accepted', () => {
        pb.applyExtractJob({ status: 'completed', terminal: true, result: [{ title: 'Only' }] });
        let s = pb.__extractStateForTest();
        expect(s.extractedScenes).toHaveLength(1);
        expect(s.step).toBe(2);
    });
});

describe('doExtract — lifecycle', () => {
    it('starts the job, polls it, adopts the scenes and clears the in-flight flags', async () => {
        let urls = [];
        let tick = 0;
        global.fetch = vi.fn(async (url) => {
            urls.push(String(url));
            if (String(url).includes('extract-scenes-only')) {
                return jsonResponse(202, { jobId: 'job-x', status: 'running' });
            }
            tick++;
            if (tick < 2) {
                return jsonResponse(200, { jobId: 'job-x', status: 'running', current: 1, total: 3,
                                           terminal: false });
            }
            return jsonResponse(200, {
                jobId: 'job-x', status: 'completed', current: 3, total: 3, terminal: true,
                result: { sceneList: [{ title: 'Done' }], chunked: true, extractionComplete: true }
            });
        });

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        expect(urls[0]).toContain('async=true');
        expect(s.extractedScenes).toHaveLength(1);
        expect(s.step).toBe(2);
        // The finally block must always run — an earlier version of this flow left `extracting`
        // stuck true, which disabled the whole wizard until a reload.
        expect(s.extracting).toBe(false);
        expect(s.extractJobId).toBeNull();
        expect(s.extractProgress).toBeNull();
    });

    it('passes fresh=true through to the server', async () => {
        let startUrl = null;
        global.fetch = vi.fn(async (url) => {
            if (String(url).includes('extract-scenes-only')) {
                startUrl = String(url);
                return jsonResponse(202, { jobId: 'j', status: 'running' });
            }
            return jsonResponse(200, { jobId: 'j', status: 'completed', terminal: true,
                                       result: { sceneList: [{ title: 'A' }] } });
        });
        await pb.doExtract({ fresh: true });
        expect(startUrl).toContain('fresh=true');
    });

    it('records the error and still clears the flags when the start fails', async () => {
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        await pb.doExtract();
        let s = pb.__extractStateForTest();
        expect(s.extractError).toMatch(/Start extract scenes failed: 500/);
        expect(s.extracting).toBe(false);
        expect(s.extractJobId).toBeNull();
        expect(s.step).toBe(1);
    });
});

describe('reattachExtractJob — must not start a second run', () => {
    it('adopts a running job for THIS document and applies its result', async () => {
        let listed = false;
        global.fetch = vi.fn(async (url) => {
            let u = String(url);
            if (u.endsWith('/rest/job')) {
                listed = true;
                return jsonResponse(200, [
                    { jobId: 'other', kind: 'pb.extractScenes', key: 'another-doc', terminal: false },
                    { jobId: 'mine', kind: 'pb.extractScenes', key: 'work-1', terminal: false,
                      current: 2, total: 5 }
                ]);
            }
            return jsonResponse(200, {
                jobId: 'mine', status: 'completed', terminal: true, current: 5, total: 5,
                result: { sceneList: [{ title: 'Reattached' }], extractionComplete: true }
            });
        });

        let attached = await pb.reattachExtractJob();

        expect(listed).toBe(true);
        expect(attached).toBe(true);
        let s = pb.__extractStateForTest();
        expect(s.extractedScenes).toHaveLength(1);
        expect(s.step).toBe(2);
        expect(s.extracting).toBe(false);
    });

    it('ignores another document\'s job, a different kind, and terminal jobs', async () => {
        global.fetch = vi.fn(async (url) => {
            if (String(url).endsWith('/rest/job')) {
                return jsonResponse(200, [
                    { jobId: 'a', kind: 'pb.extractScenes', key: 'other-doc', terminal: false },
                    { jobId: 'b', kind: 'cb.create', key: 'work-1', terminal: false },
                    { jobId: 'c', kind: 'pb.extractScenes', key: 'work-1', terminal: true }
                ]);
            }
            throw new Error('must not poll anything');
        });
        expect(await pb.reattachExtractJob()).toBe(false);
        expect(pb.__extractStateForTest().extracting).toBe(false);
    });

    it('is a no-op with no work document', async () => {
        pb.__resetExtractStateForTest(null);
        global.fetch = vi.fn(async () => { throw new Error('must not call the server'); });
        expect(await pb.reattachExtractJob()).toBe(false);
    });

    it('returns false and leaves state clean when the listing fails', async () => {
        // Reattach is best-effort — a failure must never stop the wizard from opening.
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        expect(await pb.reattachExtractJob()).toBe(false);
        let s = pb.__extractStateForTest();
        expect(s.extracting).toBe(false);
        expect(s.extractJobId).toBeNull();
    });

    it('a second concurrent reattach is refused while the first lookup is in flight', async () => {
        // The `extracting` guard alone is evaluated BEFORE the listJobs() round-trip, so without a
        // synchronously-claimed flag two callers could both attach and race to null extractJobId,
        // leaving Cancel a no-op.
        let release;
        let gate = new Promise((r) => { release = r; });
        global.fetch = vi.fn(async (url) => {
            if (String(url).endsWith('/rest/job')) {
                await gate;
                return jsonResponse(200, []);
            }
            return jsonResponse(200, {});
        });

        let first = pb.reattachExtractJob();
        let second = await pb.reattachExtractJob();
        expect(second).toBe(false);
        release();
        await first;
    });
});
