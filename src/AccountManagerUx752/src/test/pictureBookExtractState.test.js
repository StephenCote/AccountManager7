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

    it('re-run: a 409 on a chapter slug REUSES the series\' existing chapter book instead of failing the chapter', async () => {
        // Chapter slugs are deterministic (<manuscript-slug>-chN). A re-run of the same manuscript —
        // the real case was 22 chapters left behind by a run that died on an LLM 400 — 409s on every
        // createChapter. Measured 2026-09-24: "No chapters were saved. Chapter 1 ...: createChapter
        // failed: 409 | ... | Chapter 22 ...: 409". The single-book path already reuses the user's own
        // conflicting book; the fan-out must do the same via the series' chapter list.
        let chapterBodies = [];
        let seriesBooksCalls = 0;
        let extractUrls = [];
        let createFromScenesBodies = [];
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) {
                return jsonResponse(200, [
                    { startOffset: 0, endOffset: 100, title: 'Chapter One' },
                    { startOffset: 100, endOffset: 250, title: 'Chapter Two' }
                ]);
            }
            if (u.endsWith('/series')) return jsonResponse(200, { seriesObjectId: 'SER-1', worldObjectId: 'W-1' });
            if (u.endsWith('/chapter')) {
                chapterBodies.push(JSON.parse(init.body));
                return jsonResponse(409, { message: 'A book with slug already exists' });
            }
            if (u.endsWith('/series/SER-1/books')) {
                seriesBooksCalls++;
                // The series already holds a chapter book for every slug the wizard has asked for.
                return jsonResponse(200, chapterBodies.map(b => ({
                    objectId: 'BK-EXIST-' + b.chapter, name: b.title, slug: b.slug, bookStatus: 'draft',
                    chapter: b.chapter, seriesObjectId: 'SER-1', worldObjectId: 'W-1'
                })));
            }
            if (u.includes('extract-scenes-only')) {
                extractUrls.push(u);
                return jsonResponse(202, { jobId: 'job-' + extractUrls.length, status: 'running' });
            }
            if (u.includes('create-from-scenes')) {
                let body = JSON.parse(init.body);
                createFromScenesBodies.push(body);
                return jsonResponse(200, { pb2BookObjectId: body.pb2BookObjectId, scenes: body.sceneList });
            }
            let jobId = u.substring(u.lastIndexOf('/') + 1);
            return jsonResponse(200, {
                jobId, status: 'completed', terminal: true, current: 1, total: 1,
                result: { sceneList: [{ title: 'Scene for ' + jobId, characters: ['Alice'] }], extractionComplete: true }
            });
        });

        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        // Exactly one createChapter attempt per chapter — the 409 is resolved by lookup, not by retrying
        // with a forked slug (which would mint a duplicate chapter in the series).
        expect(chapterBodies).toHaveLength(2);
        expect(chapterBodies[0].slug).toMatch(/-ch1$/);
        expect(chapterBodies[1].slug).toMatch(/-ch2$/);
        expect(seriesBooksCalls).toBe(2);
        // Both chapters still extract and persist — into the EXISTING chapter books.
        expect(extractUrls).toHaveLength(2);
        expect(createFromScenesBodies).toHaveLength(2);
        expect(createFromScenesBodies[0].pb2BookObjectId).toBe('BK-EXIST-1');
        expect(createFromScenesBodies[1].pb2BookObjectId).toBe('BK-EXIST-2');
        // Ends on the series canvas of the reused first chapter, with no error.
        expect(s.extractError).toBeNull();
        expect(s.step).not.toBe(2);
        expect(dialogClose).toHaveBeenCalled();
        expect(routeSet).toHaveBeenCalledWith('/picture-book/BK-EXIST-1/workflow');
    });

    it('a 409 whose slug is NOT one of this series\' chapters forks a suffixed slug (same rule as the single-book path)', async () => {
        // The slug collides with a book outside this series (another user's / another series'). The
        // wizard must not adopt that book; it retries once with a suffixed slug in THIS series.
        let chapterBodies = [];
        let createFromScenesBodies = [];
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) {
                return jsonResponse(200, [
                    { startOffset: 0, endOffset: 100, title: 'Chapter One' },
                    { startOffset: 100, endOffset: 250, title: 'Chapter Two' }
                ]);
            }
            if (u.endsWith('/series')) return jsonResponse(200, { seriesObjectId: 'SER-1' });
            if (u.endsWith('/chapter')) {
                let body = JSON.parse(init.body);
                chapterBodies.push(body);
                if (/-ch\d+$/.test(body.slug)) return jsonResponse(409, {}); // the bare slug is taken elsewhere
                return jsonResponse(200, { bookObjectId: 'BK-NEW-' + body.chapter, slug: body.slug });
            }
            if (u.endsWith('/series/SER-1/books')) return jsonResponse(200, []); // not one of ours
            if (u.includes('extract-scenes-only')) return jsonResponse(202, { jobId: 'j', status: 'running' });
            if (u.includes('create-from-scenes')) {
                let body = JSON.parse(init.body);
                createFromScenesBodies.push(body);
                return jsonResponse(200, {});
            }
            return jsonResponse(200, { jobId: 'j', status: 'completed', terminal: true,
                                       result: { sceneList: [{ title: 'A' }], extractionComplete: true } });
        });

        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        // Two attempts per chapter: the bare slug (409) then one suffixed retry that keeps the ordinal.
        expect(chapterBodies).toHaveLength(4);
        expect(chapterBodies[1].slug.startsWith(chapterBodies[0].slug + '-')).toBe(true);
        expect(chapterBodies[1].chapter).toBe(1);
        expect(chapterBodies[3].slug.startsWith(chapterBodies[2].slug + '-')).toBe(true);
        expect(chapterBodies[3].chapter).toBe(2);
        expect(createFromScenesBodies.map(b => b.pb2BookObjectId)).toEqual(['BK-NEW-1', 'BK-NEW-2']);
        expect(pb.__extractStateForTest().extractError).toBeNull();
        expect(routeSet).toHaveBeenCalledWith('/picture-book/BK-NEW-1/workflow');
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

describe('doExtract — chaptered fan-out REPORTS missing/incomplete chapters instead of navigating away', () => {
    // Measured 2026-09-25 on a 6-chapter run of the real manuscript: the Ollama host dropped off the
    // network mid-run, chapter 5 lost a third of its passages, chapter 6 finished with ZERO scenes,
    // and the client showed a toast for a few seconds then closed the wizard and navigated to the
    // series canvas. Nine hours in, "no error anywhere, half the chapters missing." These tests pin
    // the replacement: a persistent summary that stays until the user acts on it, and a clean run
    // that still lands on the series canvas.

    /** Three chapters; the caller shapes each chapter's terminal job by jobId. */
    function fanOutFetch(jobFor) {
        let extractCount = 0;
        let createFromScenesBodies = [];
        let fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) {
                return jsonResponse(200, [
                    { startOffset: 0, endOffset: 100, title: 'Chapter One' },
                    { startOffset: 100, endOffset: 250, title: 'Chapter Two' },
                    { startOffset: 250, endOffset: 400, title: 'Chapter Three' }
                ]);
            }
            if (u.endsWith('/series')) return jsonResponse(200, { seriesObjectId: 'SER-1', worldObjectId: 'W-1' });
            if (u.endsWith('/chapter')) {
                let body = JSON.parse(init.body);
                return jsonResponse(200, { bookObjectId: 'BK-' + body.chapter, slug: body.slug });
            }
            if (u.includes('extract-scenes-only')) {
                extractCount++;
                return jsonResponse(202, { jobId: 'job-' + extractCount, status: 'running' });
            }
            if (u.includes('create-from-scenes')) {
                let body = JSON.parse(init.body);
                createFromScenesBodies.push(body);
                return jsonResponse(200, { bookObjectId: 'PB1', pb2BookObjectId: body.pb2BookObjectId, scenes: body.sceneList });
            }
            let jobId = u.substring(u.lastIndexOf('/') + 1);
            return jsonResponse(200, Object.assign({ jobId, terminal: true }, jobFor(jobId)));
        });
        return { fetch, extracts: () => extractCount, persisted: () => createFromScenesBodies };
    }

    it('stoppedEarlyReason keys on the server\'s stoppedEarly flag, not the wording', () => {
        // The server's unreachable-LLM breaker entry — exactly as PictureBookUtil writes it.
        let unreachable = JSON.stringify({
            context: 'extract-scenes-chunk:3/15', stoppedEarly: true,
            error: '2 consecutive chunks could not reach the model server. Could not connect to the model '
                + 'server at http://192.168.1.42:11434 (HttpConnectTimeoutException: HTTP connect timed out) '
                + '— it is unreachable. Extraction stopped early; fix the cause and re-run to resume from the checkpoint.'
        });
        expect(pb.stoppedEarlyReason(unreachable)).toMatch(/could not reach the model server/);
        // Same entry as an object (the poll may hand back parsed JSON).
        expect(pb.stoppedEarlyReason(JSON.parse(unreachable))).toMatch(/could not reach the model server/);
        // The pre-flag wording is still honored for a server that predates the flag.
        expect(pb.stoppedEarlyReason(JSON.stringify({ context: 'x', error: '2 consecutive LLM calls failed immediately.' })))
            .toMatch(/failed immediately/);
        expect(pb.stoppedEarlyReason('LLM call failed immediately')).toMatch(/failed immediately/);
        // An ordinary per-passage parse failure is NOT a breaker stop.
        expect(pb.stoppedEarlyReason(JSON.stringify({ context: 'extract-scenes-chunk:4/15', error: 'Unparseable JSON' }))).toBeNull();
        expect(pb.stoppedEarlyReason(JSON.stringify({ context: 'x', stoppedEarly: false, error: 'Request timed out after 900 seconds' }))).toBeNull();
        expect(pb.stoppedEarlyReason(null)).toBeNull();
    });

    it('the server\'s unreachable-model breaker (stoppedEarly:true) aborts the fan-out and stays in the wizard', async () => {
        let breaker = JSON.stringify({
            context: 'extract-scenes-chunk:2/12', stoppedEarly: true,
            error: '2 consecutive chunks could not reach the model server. Could not connect to the model server '
                + 'at http://192.168.1.42:11434 (HttpConnectTimeoutException: HTTP connect timed out) — it is unreachable. '
                + 'Extraction stopped early; fix the cause and re-run to resume from the checkpoint.'
        });
        let f = fanOutFetch(jobId => {
            if (jobId === 'job-1') return { status: 'completed', current: 10, total: 10,
                result: { sceneList: [{ title: 'S1', characters: ['Alice'] }], extractionComplete: true, chunksProcessed: 10 } };
            // Chapter 2: the host went away. The loop wrote NO scenes and the breaker entry.
            return { status: 'completed', current: 2, total: 12,
                result: { sceneList: [], extractionComplete: false, chunksProcessed: 0, failedExtractions: [breaker] } };
        });
        global.fetch = f.fetch;
        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        // Chapter 3 was never attempted — every remaining chapter would hit the same wall.
        expect(f.extracts()).toBe(2);
        expect(f.persisted()).toHaveLength(1);
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
        expect(s.step).toBe(1);
        expect(s.extractError).toMatch(/Extraction stopped at chapter 2\/3/);
        expect(s.extractError).toMatch(/1 chapter\(s\) were saved/);
        expect(s.extractError).toMatch(/could not reach the model server/);
        expect(s.extractPartial).toBe(true);
        expect(s.extracting).toBe(false);
    });

    it('a chapter that stopped early but saved scenes leaves a PERSISTENT summary — no toast-and-navigate', async () => {
        let f = fanOutFetch(jobId => {
            if (jobId === 'job-2') {
                // Chapter 2 read 10 of 15 passages then stopped (the server retained its checkpoint).
                return { status: 'completed', current: 10, total: 15,
                    result: { sceneList: [{ title: 'S2a', characters: ['Bob'] }, { title: 'S2b', characters: ['Bob'] }],
                              extractionComplete: false, chunksProcessed: 10 } };
            }
            return { status: 'completed', current: 5, total: 5,
                result: { sceneList: [{ title: 'S-' + jobId, characters: ['Alice'] }], extractionComplete: true, chunksProcessed: 5 } };
        });
        global.fetch = f.fetch;
        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        // All three chapters ran and all three persisted what they had — chapter 2's partial list included.
        expect(f.extracts()).toBe(3);
        expect(f.persisted()).toHaveLength(3);
        expect(f.persisted()[1].pb2BookObjectId).toBe('BK-2');
        expect(f.persisted()[1].sceneList).toHaveLength(2);
        // But the wizard did NOT close or navigate: the incomplete chapter is reported and stays reported.
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
        expect(s.extractChapterSummary).not.toBeNull();
        expect(s.extractChapterSummary.saved).toBe(3);
        expect(s.extractChapterSummary.total).toBe(3);
        expect(s.extractChapterSummary.seriesBookOid).toBe('BK-1');
        expect(s.extractChapterSummary.cancelled).toBe(false);
        expect(s.extractChapterSummary.problems).toHaveLength(1);
        expect(s.extractChapterSummary.problems[0]).toMatch(/Chapter 2 \(Chapter Two\): extraction stopped early after 10 of 15 passages/);
        expect(s.extractChapterSummary.problems[0]).toMatch(/2 scene\(s\) read so far were saved; re-run to resume/);
        expect(s.extractPartial).toBe(true);
        expect(s.extractError).toBeNull();
        expect(s.extracting).toBe(false);
    });

    it('a chapter that completed with ZERO scenes is reported as missing, and the run does not navigate', async () => {
        // This is exactly what chapter 6 of the real run looked like before the server fix: every
        // passage skipped, checkpoint cleared, status COMPLETED, sceneList empty.
        let f = fanOutFetch(jobId => {
            if (jobId === 'job-3') {
                return { status: 'completed', current: 12, total: 12,
                    result: { sceneList: [], extractionComplete: true, chunksProcessed: 12 } };
            }
            return { status: 'completed', current: 5, total: 5,
                result: { sceneList: [{ title: 'S-' + jobId, characters: ['Alice'] }], extractionComplete: true, chunksProcessed: 5 } };
        });
        global.fetch = f.fetch;
        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        expect(f.extracts()).toBe(3);
        expect(f.persisted()).toHaveLength(2); // nothing to persist for chapter 3
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
        expect(s.extractChapterSummary).not.toBeNull();
        expect(s.extractChapterSummary.saved).toBe(2);
        expect(s.extractChapterSummary.total).toBe(3);
        expect(s.extractChapterSummary.problems).toHaveLength(1);
        expect(s.extractChapterSummary.problems[0]).toMatch(/Chapter 3 \(Chapter Three\): no scenes were extracted/);
    });

    it('a chapter with unreadable passages (no breaker) reports EACH passage with its reason, and keeps what a retry needs', async () => {
        // One typed entry (as the server writes it today) and one legacy entry with no `kind`.
        let refused = JSON.stringify({ context: 'extract-scenes-chunk:4/9', kind: 'refusal', chunk: 4, total: 9,
            error: 'The model declined to process this passage (content policy).',
            rawResponse: 'I\'m sorry, but I can\'t help with that request.' });
        let timedOut = JSON.stringify({ context: 'extract-scenes-chunk:7/9', error: 'Request timed out after 900 seconds' });
        let f = fanOutFetch(jobId => {
            if (jobId === 'job-1') {
                return { status: 'completed', current: 9, total: 9,
                    result: { sceneList: [{ title: 'S1', characters: ['Alice'] }], extractionComplete: true,
                              chunksProcessed: 9, failedExtractions: [refused, timedOut] } };
            }
            return { status: 'completed', current: 5, total: 5,
                result: { sceneList: [{ title: 'S-' + jobId, characters: ['Bob'] }], extractionComplete: true, chunksProcessed: 5 } };
        });
        global.fetch = f.fetch;
        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        // Slow-but-alive timeouts are NOT a breaker stop: all three chapters ran.
        expect(f.extracts()).toBe(3);
        expect(f.persisted()).toHaveLength(3);
        expect(routeSet).not.toHaveBeenCalled();
        expect(s.extractChapterSummary).not.toBeNull();
        expect(s.extractChapterSummary.saved).toBe(3);
        // One line PER passage, saying what actually happened — not "2 passage(s) could not be read".
        expect(s.extractChapterSummary.problems).toHaveLength(2);
        expect(s.extractChapterSummary.problems[0]).toMatch(/^Chapter 1 \(Chapter One\): Passage 4 of 9 — the model declined to process this passage \(content policy\)/);
        expect(s.extractChapterSummary.problems[1]).toMatch(/^Chapter 1 \(Chapter One\): Passage 7 of 9 — Request timed out after 900 seconds\./);
        expect(s.extractFailedChunks).toHaveLength(2);
        // The per-chapter record carries exactly what a per-passage retry needs: the chapter's book
        // and the offsets its checkpoint is keyed on.
        let chapters = s.extractChapterSummary.chapters;
        expect(chapters).toHaveLength(3);
        expect(chapters[0]).toMatchObject({ num: 1, title: 'Chapter One', bookOid: 'BK-1', startOffset: 0, endOffset: 100, persisted: true });
        expect(chapters[0].failed).toHaveLength(2);
        expect(chapters[1].failed).toHaveLength(0);
        expect(s.extractChapterSummary.seriesObjectId).toBe('SER-1');
    });

    it('Retry failed passages (chaptered): re-runs ONLY the chapters with retryable passages, against their own offsets, and re-persists the merged list', async () => {
        let refused = JSON.stringify({ context: 'extract-scenes-chunk:2/5', kind: 'refusal', chunk: 2, total: 5,
            error: 'declined', rawResponse: 'I cannot' });
        let f = fanOutFetch(jobId => {
            if (jobId === 'job-2') {
                return { status: 'completed', current: 5, total: 5,
                    result: { sceneList: [{ title: 'S2a', characters: ['Bob'], sourceChunk: 0 }], extractionComplete: true,
                              chunksProcessed: 5, failedExtractions: [refused] } };
            }
            return { status: 'completed', current: 5, total: 5,
                result: { sceneList: [{ title: 'S-' + jobId, characters: ['Alice'], sourceChunk: 0 }], extractionComplete: true, chunksProcessed: 5 } };
        });
        global.fetch = f.fetch;
        vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        await pb.doExtract();
        expect(pb.__extractStateForTest().extractChapterSummary.problems).toHaveLength(1);

        // Now the retry: the server re-reads passage 2 of chapter 2 and returns the MERGED list.
        let retryUrls = [];
        let persistedAfter = [];
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (u.includes('extract-retry-failed')) {
                retryUrls.push(u);
                expect(JSON.parse(init.body)).toMatchObject({ schema: 'olio.pictureBookRequest', chatConfig: 'better-model', seriesObjectId: 'SER-1' });
                return jsonResponse(202, { jobId: 'retry-1', status: 'running' });
            }
            if (u.includes('create-from-scenes')) {
                let body = JSON.parse(init.body);
                persistedAfter.push(body);
                return jsonResponse(200, { bookObjectId: 'PB1', pb2BookObjectId: body.pb2BookObjectId, scenes: body.sceneList });
            }
            return jsonResponse(200, { jobId: 'retry-1', status: 'completed', terminal: true, current: 1, total: 1,
                result: { sceneList: [{ title: 'S2a', sourceChunk: 0 }, { title: 'S2-recovered', sourceChunk: 1 }],
                          extractionComplete: true, chunksProcessed: 5, chunked: true } });
        });
        pb.__setExtractStateForTest({ chatConfigRef: { name: 'better-model', objectId: 'cc-2' } });

        await pb.doRetryFailedChapters();

        let s = pb.__extractStateForTest();
        // Only chapter 2 had a retryable passage, so exactly one retry — bounded to ITS offsets.
        expect(retryUrls).toHaveLength(1);
        expect(retryUrls[0]).toMatch(/\/work-1\/extract-retry-failed\?async=true&startOffset=100&endOffset=250$/);
        // The merged list went back into chapter 2's book.
        expect(persistedAfter).toHaveLength(1);
        expect(persistedAfter[0].pb2BookObjectId).toBe('BK-2');
        expect(persistedAfter[0].sceneList.map(x => x.title)).toEqual(['S2a', 'S2-recovered']);
        expect(persistedAfter[0].chatConfig).toBe('better-model');
        // Nothing left to report; flags reset.
        expect(s.extractChapterSummary.problems).toHaveLength(0);
        expect(s.extractChapterSummary.chapters[1].failed).toHaveLength(0);
        expect(s.extractFailedChunks).toHaveLength(0);
        expect(s.extracting).toBe(false);
        expect(s.retryingFailed).toBe(false);
        expect(s.extractError).toBeNull();
    });

    it('a chaptered run in which EVERY chapter failed keeps the per-chapter report and retry (not just an error string)', async () => {
        // Each chapter's book was created before its extraction, so the books exist in the series
        // with zero scenes. Before the fix this fell to "No chapters were saved. ..." with no retry
        // affordance — and a plain re-run is served from the kept checkpoints without calling the
        // model, so the user was stuck.
        let timeoutFor = (n, total) => JSON.stringify({ context: 'extract-scenes-chunk:' + n + '/' + total, kind: 'timeout',
            chunk: n, total: total, error: 'Request timed out after 8 seconds' });
        let f = fanOutFetch(jobId => ({ status: 'completed', current: 2, total: 2,
            result: { sceneList: [], extractionComplete: true, chunksProcessed: 2,
                      failedExtractions: [timeoutFor(1, 2), timeoutFor(2, 2)] } }));
        global.fetch = f.fetch;
        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        expect(f.extracts()).toBe(3);
        expect(f.persisted()).toHaveLength(0);
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
        expect(s.step).toBe(1);
        expect(s.extractChaptered).toBe(true);
        expect(s.extractError).toBeNull();
        expect(s.extractChapterSummary).not.toBeNull();
        expect(s.extractChapterSummary.saved).toBe(0);
        expect(s.extractChapterSummary.total).toBe(3);
        expect(s.extractChapterSummary.seriesBookOid).toBe('BK-1');
        // One "no scenes" line plus two passage lines per chapter — each passage says why.
        expect(s.extractChapterSummary.problems).toHaveLength(9);
        expect(s.extractChapterSummary.problems[0]).toMatch(/^Chapter 1 \(Chapter One\): no scenes were extracted/);
        expect(s.extractChapterSummary.problems[1]).toMatch(/^Chapter 1 \(Chapter One\): Passage 1 of 2 — the model did not finish within the request timeout/);
        expect(s.extractFailedChunks).toHaveLength(6);
        s.extractChapterSummary.chapters.forEach((c, i) => {
            expect(c.bookOid).toBe('BK-' + (i + 1));
            expect(c.persisted).toBeFalsy();
            expect(c.failed).toHaveLength(2);
        });

        // Retry with a better model: chapters 1 and 3 recover, chapter 2's second passage is refused.
        let retryUrls = [];
        let persistedAfter = [];
        let stillRefused = JSON.stringify({ context: 'extract-scenes-chunk:2/2', kind: 'refusal', chunk: 2, total: 2,
            error: 'declined', rawResponse: 'I cannot help with that.' });
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (u.includes('extract-retry-failed')) {
                retryUrls.push(u);
                expect(JSON.parse(init.body)).toMatchObject({ schema: 'olio.pictureBookRequest', chatConfig: 'better-model', seriesObjectId: 'SER-1' });
                return jsonResponse(202, { jobId: 'retry-' + retryUrls.length, status: 'running' });
            }
            if (u.includes('create-from-scenes')) {
                let body = JSON.parse(init.body);
                persistedAfter.push(body);
                return jsonResponse(200, { bookObjectId: 'PB1', pb2BookObjectId: body.pb2BookObjectId, scenes: body.sceneList });
            }
            let jobId = u.substring(u.lastIndexOf('/') + 1);
            if (jobId === 'retry-2') {
                return jsonResponse(200, { jobId, status: 'completed', terminal: true, current: 2, total: 2,
                    result: { sceneList: [{ title: 'C2-a', sourceChunk: 0 }], extractionComplete: true, chunksProcessed: 2, chunked: true,
                              failedExtractions: [stillRefused] } });
            }
            return jsonResponse(200, { jobId, status: 'completed', terminal: true, current: 2, total: 2,
                result: { sceneList: [{ title: jobId + '-a', sourceChunk: 0 }, { title: jobId + '-b', sourceChunk: 1 }],
                          extractionComplete: true, chunksProcessed: 2, chunked: true } });
        });
        pb.__setExtractStateForTest({ chatConfigRef: { name: 'better-model', objectId: 'cc-2' } });

        await pb.doRetryFailedChapters();

        s = pb.__extractStateForTest();
        expect(retryUrls).toHaveLength(3);
        expect(retryUrls[0]).toMatch(/\/work-1\/extract-retry-failed\?async=true&startOffset=0&endOffset=100$/);
        expect(retryUrls[1]).toMatch(/\/work-1\/extract-retry-failed\?async=true&startOffset=100&endOffset=250$/);
        expect(retryUrls[2]).toMatch(/\/work-1\/extract-retry-failed\?async=true&startOffset=250&endOffset=400$/);
        // Recovered scenes land in EACH chapter's own (previously empty) book.
        expect(persistedAfter.map(b => b.pb2BookObjectId)).toEqual(['BK-1', 'BK-2', 'BK-3']);
        expect(persistedAfter[0].sceneList.map(x => x.title)).toEqual(['retry-1-a', 'retry-1-b']);
        expect(persistedAfter[1].sceneList.map(x => x.title)).toEqual(['C2-a']);
        expect(s.extractChapterSummary.saved).toBe(3);
        expect(s.extractChapterSummary.chapters.map(c => c.persisted)).toEqual([true, true, true]);
        // Only chapter 2's refused passage remains, and it still says why. The stale "no scenes were
        // extracted" lines are gone — every chapter now has scenes.
        expect(s.extractFailedChunks).toHaveLength(1);
        expect(pb.describeFailedPassage(s.extractFailedChunks[0]).kind).toBe('refusal');
        expect(s.extractChapterSummary.chapters[1].failed).toHaveLength(1);
        expect(s.extractChapterSummary.problems).toEqual([
            expect.stringMatching(/^Chapter 2 \(Chapter Two\): Passage 2 of 2 — the model declined/)
        ]);
        expect(s.extracting).toBe(false);
        expect(s.retryingFailed).toBe(false);
        expect(s.extractError).toBeNull();
    });

    it('a CLEAN run still closes the wizard and lands on the series canvas', async () => {
        let f = fanOutFetch(jobId => ({ status: 'completed', current: 5, total: 5,
            result: { sceneList: [{ title: 'S-' + jobId, characters: ['Alice'] }], extractionComplete: true, chunksProcessed: 5 } }));
        global.fetch = f.fetch;
        let routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        let s = pb.__extractStateForTest();
        expect(f.persisted()).toHaveLength(3);
        expect(s.extractChapterSummary).toBeNull();
        expect(s.extractError).toBeNull();
        expect(s.extractPartial).toBe(false);
        expect(dialogClose).toHaveBeenCalled();
        expect(routeSet).toHaveBeenCalledWith('/picture-book/BK-1/workflow');
    });

    it('the summary is cleared when a new extraction starts', async () => {
        let f = fanOutFetch(jobId => jobId === 'job-3'
            ? { status: 'completed', current: 1, total: 1, result: { sceneList: [], extractionComplete: true, chunksProcessed: 1 } }
            : { status: 'completed', current: 1, total: 1,
                result: { sceneList: [{ title: 'S', characters: ['A'] }], extractionComplete: true, chunksProcessed: 1 } });
        global.fetch = f.fetch;
        vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        await pb.doExtract();
        expect(pb.__extractStateForTest().extractChapterSummary).not.toBeNull();

        // Re-run: every chapter clean this time.
        let g = fanOutFetch(() => ({ status: 'completed', current: 1, total: 1,
            result: { sceneList: [{ title: 'S', characters: ['A'] }], extractionComplete: true, chunksProcessed: 1 } }));
        global.fetch = g.fetch;
        await pb.doExtract();
        expect(pb.__extractStateForTest().extractChapterSummary).toBeNull();
    });
});

describe('failed passages — explain WHY, and retry only those', () => {
    // Entries exactly as PictureBookUtil.recordFailedExtraction writes them (JSON strings).
    const entry = (o) => JSON.stringify(o);

    it('describeFailedPassage keys the explanation on the server\'s typed kind, keeps the model\'s own words, and mirrors the server\'s retryable rule', () => {
        let d = pb.describeFailedPassage(entry({ context: 'extract-scenes-chunk:3/12', kind: 'refusal', chunk: 3, total: 12,
            error: 'The model declined to process this passage (content policy).',
            rawResponse: ' I\'m sorry, but I can\'t assist with that. ' }));
        expect(d).toMatchObject({ chunk: 3, total: 12, kind: 'refusal', retryable: true });
        expect(d.reason).toMatch(/declined to process this passage \(content policy\) — retry it with a different model/);
        expect(d.detail).toBe('The model declined to process this passage (content policy).');
        expect(d.raw).toBe('I\'m sorry, but I can\'t assist with that.');

        // Every kind the server can emit has a plain-language reading (not the kind token itself).
        for (let k of ['stalled', 'truncated', 'timeout', 'unreachable', 'error', 'empty', 'no-json', 'parse']) {
            let x = pb.describeFailedPassage(entry({ kind: k, chunk: 1, total: 2, error: 'e' }));
            expect(x.kind).toBe(k);
            expect(x.reason).not.toBe(k);
            expect(x.reason.length).toBeGreaterThan(20);
            expect(x.retryable).toBe(true);
        }
        // A breaker stop is NOT retryable through the per-passage path — Resume handles it.
        let stop = pb.describeFailedPassage(entry({ context: 'extract-scenes-chunk:5/12', kind: 'stopped-early', chunk: 5, total: 12,
            stoppedEarly: true, error: '2 consecutive chunks could not reach the model server.' }));
        expect(stop.retryable).toBe(false);
        expect(stop.reason).toMatch(/stopped before this passage was attempted/);
        // Legacy entry (pre-`kind`, pre-`chunk`): position parsed from context, reason is the server text.
        let legacy = pb.describeFailedPassage(entry({ context: 'extract-scenes-chunk:4/9', error: 'Request timed out after 900 seconds' }));
        expect(legacy).toMatchObject({ chunk: 4, total: 9, kind: null, retryable: true, reason: 'Request timed out after 900 seconds' });
        // Unparseable / unknown: shown verbatim, never retryable (no passage to retry).
        expect(pb.describeFailedPassage('chunk 3 unparseable')).toMatchObject({ chunk: 0, retryable: false, reason: 'chunk 3 unparseable' });
        // Object form (the poll may hand back parsed JSON).
        expect(pb.describeFailedPassage({ kind: 'stalled', chunk: 2, total: 2 })).toMatchObject({ chunk: 2, kind: 'stalled', retryable: true });
    });

    it('mergeRecoveredScenes keeps the user\'s list and inserts recovered scenes among their neighbours by provenance', () => {
        // The user renamed scene A and deleted scene B (from chunk 1) in Step 2; chunk 1 failed and
        // was retried; the server returns the full merged list with B back and a new scene N.
        let current = [{ title: 'A renamed', sourceChunk: 0 }, { title: 'C', sourceChunk: 2 }, { title: 'manual' }];
        let merged = [{ title: 'A', sourceChunk: 0 }, { title: 'N1', sourceChunk: 1 }, { title: 'N2', sourceChunk: 1 }, { title: 'C', sourceChunk: 2 }];
        let out = pb.mergeRecoveredScenes(current, merged, { 2: true });
        expect(out.map(s => s.title)).toEqual(['A renamed', 'N1', 'N2', 'C', 'manual']);
        // Nothing retried for chunk 0, so the server's un-renamed 'A' did NOT clobber the user's edit.
        expect(out[0].title).toBe('A renamed');
        // A recovered scene from the LAST chunk goes to the end.
        let tail = pb.mergeRecoveredScenes([{ title: 'A', sourceChunk: 0 }], [{ title: 'Z', sourceChunk: 5 }], { 6: true });
        expect(tail.map(s => s.title)).toEqual(['A', 'Z']);
    });

    it('doRetryFailed posts to extract-retry-failed with the chosen chat config, polls, folds in the recovered scenes, and clears the failures', async () => {
        pb.__setExtractStateForTest({
            step: 2,
            extractedScenes: [{ title: 'S0', sourceChunk: 0 }, { title: 'S2', sourceChunk: 2 }],
            extractFailedChunks: [entry({ context: 'extract-scenes-chunk:2/3', kind: 'refusal', chunk: 2, total: 3, error: 'declined', rawResponse: 'no' })],
            chatConfigRef: { name: 'permissive-model', objectId: 'cc-9' }
        });
        let urls = [];
        let tick = 0;
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            urls.push(u);
            if (u.includes('extract-retry-failed')) {
                expect(init.method).toBe('POST');
                expect(JSON.parse(init.body)).toEqual({ schema: 'olio.pictureBookRequest', chatConfig: 'permissive-model' });
                return jsonResponse(202, { jobId: 'retry-7', status: 'running' });
            }
            tick++;
            if (tick < 2) return jsonResponse(200, { jobId: 'retry-7', status: 'running', current: 0, total: 1, terminal: false });
            return jsonResponse(200, { jobId: 'retry-7', status: 'completed', terminal: true, current: 1, total: 1,
                result: { sceneList: [{ title: 'S0', sourceChunk: 0 }, { title: 'S1 recovered', sourceChunk: 1 }, { title: 'S2', sourceChunk: 2 }],
                          extractionComplete: true, chunksProcessed: 3, chunked: true } });
        });

        let p = pb.doRetryFailed();
        expect(pb.__extractStateForTest().extracting).toBe(true);
        expect(pb.__extractStateForTest().retryingFailed).toBe(true);
        await p;

        let s = pb.__extractStateForTest();
        expect(urls[0]).toMatch(/\/work-1\/extract-retry-failed\?async=true$/);
        expect(urls.filter(u => u.includes('/rest/job/retry-7'))).toHaveLength(2);
        expect(s.extractedScenes.map(x => x.title)).toEqual(['S0', 'S1 recovered', 'S2']);
        expect(s.extractFailedChunks).toEqual([]);
        expect(s.extractPartial).toBe(false);
        expect(s.step).toBe(2);
        expect(s.extracting).toBe(false);
        expect(s.retryingFailed).toBe(false);
        expect(s.extractJobId).toBeNull();
        expect(s.extractError).toBeNull();
    });

    it('doRetryFailed: a passage that fails AGAIN stays listed (with the new reason), and the list is untouched', async () => {
        let stillBad = entry({ context: 'extract-scenes-chunk:2/3', kind: 'refusal', chunk: 2, total: 3, error: 'declined again', rawResponse: 'still no' });
        pb.__setExtractStateForTest({
            step: 2,
            extractedScenes: [{ title: 'S0', sourceChunk: 0 }],
            extractFailedChunks: [entry({ context: 'extract-scenes-chunk:2/3', kind: 'refusal', chunk: 2, total: 3, error: 'declined' })]
        });
        global.fetch = vi.fn(async (url) => {
            if (String(url).includes('extract-retry-failed')) return jsonResponse(202, { jobId: 'retry-8', status: 'running' });
            return jsonResponse(200, { jobId: 'retry-8', status: 'completed', terminal: true, current: 1, total: 1,
                result: { sceneList: [{ title: 'S0', sourceChunk: 0 }], extractionComplete: true, chunksProcessed: 3, failedExtractions: [stillBad] } });
        });
        await pb.doRetryFailed();
        let s = pb.__extractStateForTest();
        expect(s.extractedScenes.map(x => x.title)).toEqual(['S0']);
        expect(s.extractFailedChunks).toEqual([stillBad]);
        expect(pb.describeFailedPassage(s.extractFailedChunks[0]).detail).toBe('declined again');
        expect(s.extracting).toBe(false);
    });

    it('doRetryFailed: the server having no checkpoint (404) is reported, not swallowed', async () => {
        pb.__setExtractStateForTest({
            step: 2, extractedScenes: [{ title: 'S0', sourceChunk: 0 }],
            extractFailedChunks: [entry({ kind: 'timeout', chunk: 2, total: 3, error: 't/o' })]
        });
        global.fetch = vi.fn(async () => jsonResponse(404, { error: 'no checkpoint' }));
        await pb.doRetryFailed();
        let s = pb.__extractStateForTest();
        expect(s.extractError).toMatch(/Nothing left to retry.*404/);
        expect(s.extractedScenes).toHaveLength(1);
        expect(s.extracting).toBe(false);
        expect(s.retryingFailed).toBe(false);
    });

    it('a single-document run in which EVERY passage failed keeps the reasons on Step 1, and a successful retry advances to Step 2', async () => {
        // The slow-model / whole-document-refusal shape: the job completes with zero scenes and one
        // typed failure per passage. Before, this dead-ended on "No scenes returned by LLM" with the
        // failures discarded; the per-passage reasons and the retry must survive on Step 1.
        const timeouts = [1, 2, 3].map(n => entry({ context: 'extract-scenes-chunk:' + n + '/3', kind: 'timeout', chunk: n, total: 3,
            error: 'Request timed out after 8 seconds' }));
        global.fetch = vi.fn(async (url) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) return jsonResponse(200, []);
            if (u.includes('extract-scenes-only')) return jsonResponse(202, { jobId: 'all-fail', status: 'running' });
            return jsonResponse(200, { jobId: 'all-fail', status: 'completed', terminal: true, current: 3, total: 3,
                result: { sceneList: [], chunked: true, extractionComplete: true, chunksProcessed: 3, failedExtractions: timeouts } });
        });
        await pb.doExtract();
        let s = pb.__extractStateForTest();
        expect(s.step).toBe(1);
        expect(s.extractChaptered).toBe(false);
        expect(s.extractError).toBe('No scenes returned by LLM');
        expect(s.extractFailedChunks).toEqual(timeouts);
        expect(s.extractFailedChunks.map(f => pb.describeFailedPassage(f).retryable)).toEqual([true, true, true]);

        // Retry (with the default config) recovers two passages: the list now exists, so Step 2.
        let body = null;
        global.fetch = vi.fn(async (url, init) => {
            if (String(url).includes('extract-retry-failed')) { body = JSON.parse(init.body); return jsonResponse(202, { jobId: 'retry-9', status: 'running' }); }
            return jsonResponse(200, { jobId: 'retry-9', status: 'completed', terminal: true, current: 3, total: 3,
                result: { sceneList: [{ title: 'R1', sourceChunk: 0 }, { title: 'R3', sourceChunk: 2 }], chunked: true, extractionComplete: true, chunksProcessed: 3,
                          failedExtractions: [entry({ context: 'extract-scenes-chunk:2/3', kind: 'refusal', chunk: 2, total: 3, error: 'declined' })] } });
        });
        await pb.doRetryFailed();
        s = pb.__extractStateForTest();
        expect(body).toEqual({ schema: 'olio.pictureBookRequest' });
        expect(s.step).toBe(2);
        expect(s.extractedScenes.map(x => x.title)).toEqual(['R1', 'R3']);
        expect(s.extractFailedChunks).toHaveLength(1);
        expect(pb.describeFailedPassage(s.extractFailedChunks[0]).kind).toBe('refusal');
        expect(s.extractError).toBeNull();
        expect(s.extracting).toBe(false);
    });

    it('doRetryFailed is a no-op when nothing is retryable (only a breaker stop)', async () => {
        pb.__setExtractStateForTest({
            step: 2, extractedScenes: [{ title: 'S0', sourceChunk: 0 }],
            extractFailedChunks: [entry({ kind: 'stopped-early', chunk: 2, total: 3, stoppedEarly: true, error: 'stopped' })]
        });
        global.fetch = vi.fn(async () => { throw new Error('must not be called'); });
        await pb.doRetryFailed();
        expect(global.fetch).not.toHaveBeenCalled();
        expect(pb.__extractStateForTest().extracting).toBe(false);
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
