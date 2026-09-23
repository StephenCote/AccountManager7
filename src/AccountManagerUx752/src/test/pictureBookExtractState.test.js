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
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';

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
    vi.restoreAllMocks();
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
        // doExtract now checks for chapter boundaries FIRST; an empty array means "one document",
        // so the flow falls through to the original single whole-document extraction path.
        let urls = [];
        let tick = 0;
        global.fetch = vi.fn(async (url) => {
            urls.push(String(url));
            if (String(url).includes('detect-boundaries')) return jsonResponse(200, []);
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
        // The extraction call — wherever it lands in the request sequence now that detection runs
        // first — must still be the async background job.
        let extractUrl = urls.find(u => u.includes('extract-scenes-only'));
        expect(extractUrl).toContain('async=true');
        // Single-document fallback: no per-chapter span is appended.
        expect(extractUrl).not.toContain('startOffset');
        expect(s.extractedScenes).toHaveLength(1);
        expect(s.step).toBe(2);
        // The finally block must always run — an earlier version of this flow left `extracting`
        // stuck true, which disabled the whole wizard until a reload.
        expect(s.extracting).toBe(false);
        expect(s.extractJobId).toBeNull();
        expect(s.extractProgress).toBeNull();
        expect(s.extractChapterCount).toBe(0);
    });

    it('passes fresh=true through to the server', async () => {
        let startUrl = null;
        global.fetch = vi.fn(async (url) => {
            if (String(url).includes('detect-boundaries')) return jsonResponse(200, []);
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

describe('doExtract — chaptered novel fan-out (Issue 1, Full N-series)', () => {
    // A manuscript with >= 2 detected chapter boundaries must NOT be submitted as one unbounded job
    // (~4-5h, past the client poll deadline). It fans out into one bounded extraction per chapter,
    // run sequentially, and each chapter's scenes are PERSISTED INTO ITS OWN chapter book in the ONE
    // shared series world (createFromScenes(..., chapterBookObjectId)). There is NO Step-2 aggregate
    // review for chaptered novels — the flow finishes on the series canvas (/workflow route).

    it('series once, per-chapter book+extraction+persist, ends on the series canvas — NO Step-2 aggregate', async () => {
        let seriesCalls = 0;
        let chapterBodies = [];
        let extractUrls = [];
        let createFromScenesBodies = [];
        let order = [];
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) {
                return jsonResponse(200, [
                    { startOffset: 0, endOffset: 100, title: 'Chapter One' },
                    { startOffset: 100, endOffset: 250, title: 'Chapter Two' }
                ]);
            }
            if (u.endsWith('/series')) {
                seriesCalls++;
                return jsonResponse(200, { seriesObjectId: 'SER-1', worldObjectId: 'W-1' });
            }
            if (u.endsWith('/chapter')) {
                chapterBodies.push(JSON.parse(init.body));
                order.push('chapter');
                // Confirmed contract: createChapter returns the chapter's olio.pb.book objectId as
                // `bookObjectId` (facade toBook.getObjectId), which createFromScenes takes as pb2BookObjectId.
                return jsonResponse(200, { bookObjectId: 'BK-' + chapterBodies.length, slug: 'x' });
            }
            if (u.includes('extract-scenes-only')) {
                extractUrls.push(u);
                order.push('extract');
                return jsonResponse(202, { jobId: 'job-' + extractUrls.length, status: 'running' });
            }
            if (u.includes('create-from-scenes')) {
                let body = JSON.parse(init.body);
                createFromScenesBodies.push(body);
                order.push('createFromScenes');
                return jsonResponse(200, {
                    bookObjectId: 'PB1-' + createFromScenesBodies.length,
                    pb2BookObjectId: body.pb2BookObjectId,
                    scenes: body.sceneList
                });
            }
            // Job poll — terminal immediately; tag the scene + a distinct character by the jobId so
            // per-chapter persistence (not aggregation) is observable in the createFromScenes calls.
            let jobId = u.substring(u.lastIndexOf('/') + 1);
            let who = jobId === 'job-1' ? 'Alice' : 'Bob';
            return jsonResponse(200, {
                jobId, status: 'completed', terminal: true, current: 1, total: 1,
                result: { sceneList: [{ title: 'Scene for ' + jobId, characters: [who] }],
                          extractionComplete: true }
            });
        });

        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        // ONE get-or-create for the series; two chapter books; two bounded extractions; two persists.
        expect(seriesCalls).toBe(1);
        expect(chapterBodies).toHaveLength(2);
        expect(extractUrls).toHaveLength(2);
        expect(createFromScenesBodies).toHaveLength(2);
        // Each extraction carries its chapter's explicit character span (server clamps + slices).
        expect(extractUrls[0]).toContain('async=true');       // always the background job
        expect(extractUrls[0]).toContain('startOffset=0');
        expect(extractUrls[0]).toContain('endOffset=100');
        expect(extractUrls[0]).not.toContain('seriesObjectId'); // series travels in the body, not the query
        expect(extractUrls[1]).toContain('startOffset=100');
        expect(extractUrls[1]).toContain('endOffset=250');
        // Chapters carry the series linkage, ordinal, source manuscript and boundary range.
        expect(chapterBodies[0].seriesObjectId).toBe('SER-1');
        expect(chapterBodies[0].chapter).toBe(1);
        expect(chapterBodies[0].sourceDataObjectId).toBe('work-1');
        expect(chapterBodies[0].sourceRange.startOffset).toBe(0);
        expect(chapterBodies[0].sourceRange.endOffset).toBe(100);
        expect(chapterBodies[1].chapter).toBe(2);
        // Each chapter's scenes are persisted INTO ITS OWN chapter book (the captured bookObjectId),
        // carrying only THAT chapter's scenes and cast — not an aggregate.
        expect(createFromScenesBodies[0].pb2BookObjectId).toBe('BK-1');
        expect(createFromScenesBodies[0].sceneList).toHaveLength(1);
        expect(createFromScenesBodies[0].sceneList[0].title).toBe('Scene for job-1');
        expect(createFromScenesBodies[0].characters.map(c => c.name)).toEqual(['Alice']);
        expect(createFromScenesBodies[1].pb2BookObjectId).toBe('BK-2');
        expect(createFromScenesBodies[1].sceneList[0].title).toBe('Scene for job-2');
        expect(createFromScenesBodies[1].characters.map(c => c.name)).toEqual(['Bob']);
        // Strictly sequential: chapter 1 is created, extracted AND persisted before chapter 2 starts.
        expect(order).toEqual(['chapter', 'extract', 'createFromScenes',
                               'chapter', 'extract', 'createFromScenes']);
        // NO Step-2 aggregate: the wizard does NOT advance to step 2 and builds no aggregate list.
        expect(s.step).not.toBe(2);
        expect(s.extractedScenes).toHaveLength(0);
        // Finishes on the SERIES CANVAS — the first chapter book's workflow route — with the dialog closed.
        expect(dialogClose).toHaveBeenCalled();
        expect(routeSet).toHaveBeenCalledWith('/picture-book/BK-1/workflow');
        // In-flight flags cleared by the finally block.
        expect(s.extracting).toBe(false);
        expect(s.extractJobId).toBeNull();
        expect(s.extractChapterCount).toBe(0);
        expect(s.extractChapterIndex).toBe(0);
    });

    it('surfaces a per-chapter createChapter failure and does NOT navigate when nothing persists', async () => {
        // A createChapter failure means the chapter has nowhere to persist — it must be reported, not
        // silently swallowed. When every chapter fails to create, nothing persists, so the wizard
        // stays on step 1 with an error and never navigates to a series canvas that has no content.
        let extractCalls = 0;
        let createFromScenesCalls = 0;
        global.fetch = vi.fn(async (url) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) {
                return jsonResponse(200, [
                    { startOffset: 0, endOffset: 100, title: 'Chapter One' },
                    { startOffset: 100, endOffset: 250, title: 'Chapter Two' }
                ]);
            }
            if (u.endsWith('/series')) return jsonResponse(200, { seriesObjectId: 'SER-1' });
            if (u.endsWith('/chapter')) return jsonResponse(500, {}); // every chapter book fails
            if (u.includes('extract-scenes-only')) { extractCalls++; return jsonResponse(202, { jobId: 'j' }); }
            if (u.includes('create-from-scenes')) { createFromScenesCalls++; return jsonResponse(200, {}); }
            return jsonResponse(200, { terminal: true, status: 'completed', result: { sceneList: [] } });
        });

        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        // A failed book means the chapter is skipped BEFORE extraction — no extraction, no persist.
        expect(extractCalls).toBe(0);
        expect(createFromScenesCalls).toBe(0);
        // Nothing persisted ⇒ no navigation, dialog stays open, and the error names the failed chapters.
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
        expect(s.step).toBe(1);
        expect(s.extractError).toMatch(/No chapters were saved/i);
        expect(s.extracting).toBe(false);
        expect(s.extractJobId).toBeNull();
    });

    it('a SINGLE detected chapter falls back to the unchanged whole-document path (no series, no offsets)', async () => {
        let seriesCalls = 0;
        let extractUrl = null;
        global.fetch = vi.fn(async (url) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) {
                return jsonResponse(200, [{ startOffset: 0, endOffset: 100, title: 'Only chapter' }]);
            }
            if (u.endsWith('/series')) { seriesCalls++; return jsonResponse(200, {}); }
            if (u.includes('extract-scenes-only')) {
                extractUrl = u;
                return jsonResponse(202, { jobId: 'j', status: 'running' });
            }
            return jsonResponse(200, { jobId: 'j', status: 'completed', terminal: true,
                                       result: { sceneList: [{ title: 'A' }], extractionComplete: true } });
        });

        await pb.doExtract();

        // Fewer than two boundaries ⇒ no series minted, no per-chapter span appended.
        expect(seriesCalls).toBe(0);
        expect(extractUrl).not.toBeNull();
        expect(extractUrl).toContain('async=true');
        expect(extractUrl).not.toContain('startOffset');
        expect(extractUrl).not.toContain('seriesObjectId');
        expect(pb.__extractStateForTest().step).toBe(2);
    });

    it('a detection failure falls back to the single whole-document path (best-effort)', async () => {
        let extractUrl = null;
        global.fetch = vi.fn(async (url) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) return jsonResponse(500, {}); // detection blows up
            if (u.includes('extract-scenes-only')) {
                extractUrl = u;
                return jsonResponse(202, { jobId: 'j', status: 'running' });
            }
            return jsonResponse(200, { jobId: 'j', status: 'completed', terminal: true,
                                       result: { sceneList: [{ title: 'A' }], extractionComplete: true } });
        });

        await pb.doExtract();

        // A failed detection must NOT abort extraction — it degrades to one document.
        expect(extractUrl).not.toBeNull();
        expect(extractUrl).toContain('async=true');
        expect(extractUrl).not.toContain('startOffset');
        let s = pb.__extractStateForTest();
        expect(s.step).toBe(2);
        expect(s.extractError).toBeNull();
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
