// @vitest-environment jsdom
/**
 * Picture-book wizard scene-count guidance.
 *
 * Why: the wizard sent no `count`, so every document got the server's default of 10 spread over the
 * chunks (alchemy.doc: 57K chars → 8 chunks → ≤2 new scenes per chunk → 14 scenes for a novelette).
 * The count is now guessed from the source length, editable before extraction, and editable +
 * re-extractable from Step 2. These tests drive the real module functions and check the request
 * bodies that reach extract-scenes-only. fetch is stubbed, so this does NOT exercise the server or
 * the LLM.
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

/** fetch stub: given boundary ranges, records every extract-scenes-only call (url + parsed body). */
function stubSingleRun(ranges, extractCalls) {
    global.fetch = vi.fn(async (url, init) => {
        let u = String(url);
        if (u.includes('detect-boundaries')) return jsonResponse(200, ranges);
        if (u.includes('extract-scenes-only')) {
            extractCalls.push({ url: u, body: JSON.parse(init.body) });
            return jsonResponse(202, { jobId: 'job-' + extractCalls.length, status: 'running' });
        }
        return jsonResponse(200, {
            jobId: 'job-x', status: 'completed', terminal: true, current: 1, total: 1,
            result: { sceneList: [{ title: 'A' }, { title: 'B' }], chunked: true, extractionComplete: true }
        });
    });
}

describe('suggestSceneCount / normalizeSceneCount / chapterSceneCount', () => {
    it('guesses one scene per ~2,500 chars, clamped to 3..60', () => {
        expect(pb.suggestSceneCount(57113)).toBe(23);   // alchemy.doc's measured length
        expect(pb.suggestSceneCount(1000)).toBe(3);
        expect(pb.suggestSceneCount(10000000)).toBe(60);
        expect(pb.suggestSceneCount(0)).toBeNull();
        expect(pb.suggestSceneCount(null)).toBeNull();
    });

    it('blank/invalid input means "server default" (null); numbers clamp to 1..200', () => {
        expect(pb.normalizeSceneCount('')).toBeNull();
        expect(pb.normalizeSceneCount('abc')).toBeNull();
        expect(pb.normalizeSceneCount('0')).toBeNull();
        expect(pb.normalizeSceneCount('15')).toBe(15);
        expect(pb.normalizeSceneCount('999')).toBe(200);
    });

    it('splits a book count across chapters by length, never below 1', () => {
        expect(pb.chapterSceneCount(30, 0, 100, 300)).toBe(10);
        expect(pb.chapterSceneCount(30, 100, 300, 300)).toBe(20);
        expect(pb.chapterSceneCount(3, 0, 1, 1000)).toBe(1);
        expect(pb.chapterSceneCount(null, 0, 100, 300)).toBeNull();
    });
});

describe('measureSource', () => {
    it('fills the suggestion from the last boundary endOffset', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, [{ startOffset: 0, endOffset: 57113, title: null }]));
        await pb.measureSource();
        let s = pb.__extractStateForTest();
        expect(s.sourceChars).toBe(57113);
        expect(s.sceneCount).toBe(23);
        expect(s.sourceMeasureFailed).toBe(false);
    });

    it('does not overwrite a count the user already typed', async () => {
        pb.setSceneCountFromInput('40');
        global.fetch = vi.fn(async () => jsonResponse(200, [{ startOffset: 0, endOffset: 57113, title: null }]));
        await pb.measureSource();
        expect(pb.__extractStateForTest().sceneCount).toBe(40);
        pb.useSuggestedSceneCount();
        expect(pb.__extractStateForTest().sceneCount).toBe(23);
        expect(pb.__extractStateForTest().sceneCountEdited).toBe(false);
    });

    it('data.note source: detect-boundaries 404s, so the note text length is used', async () => {
        global.fetch = vi.fn(async (url) => {
            if (String(url).includes('detect-boundaries')) return jsonResponse(404, {});
            if (String(url).includes('/rest/model/data.note/work-1/full')) {
                return jsonResponse(200, { schema: 'data.note', text: 'x'.repeat(25000) });
            }
            return jsonResponse(404, {});
        });
        await pb.measureSource();
        let s = pb.__extractStateForTest();
        expect(s.sourceChars).toBe(25000);
        expect(s.sceneCount).toBe(10);
    });

    it('a failed measurement leaves the count unset and flags it, never throws', async () => {
        global.fetch = vi.fn(async () => jsonResponse(404, {}));
        await pb.measureSource();
        let s = pb.__extractStateForTest();
        expect(s.sceneCount).toBeNull();
        expect(s.sourceMeasureFailed).toBe(true);
    });
});

describe('doExtract sends the count', () => {
    it('single document: the guessed count is in the request body', async () => {
        let calls = [];
        stubSingleRun([{ startOffset: 0, endOffset: 57113, title: null }], calls);
        await pb.doExtract();
        expect(calls).toHaveLength(1);
        expect(calls[0].body.count).toBe(23);
        expect(pb.__extractStateForTest().lastExtractCount).toBe(23);
    });

    it('single document: a user-typed count wins over the guess', async () => {
        pb.setSceneCountFromInput('35');
        let calls = [];
        stubSingleRun([{ startOffset: 0, endOffset: 57113, title: null }], calls);
        await pb.doExtract();
        expect(calls[0].body.count).toBe(35);
    });

    it('blank count: no `count` key, so the server default applies (old behaviour)', async () => {
        let calls = [];
        stubSingleRun([], calls);   // detection found nothing → no measurement, no guess
        await pb.doExtract();
        expect(calls[0].body).not.toHaveProperty('count');
    });

    it('chaptered: each chapter job gets its length-proportional share', async () => {
        pb.setSceneCountFromInput('30');
        let bodies = [];
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) {
                return jsonResponse(200, [
                    { startOffset: 0, endOffset: 100, title: 'One' },
                    { startOffset: 100, endOffset: 300, title: 'Two' }
                ]);
            }
            if (u.endsWith('/series')) return jsonResponse(200, { seriesObjectId: 'SER-1', worldObjectId: 'W-1' });
            if (u.endsWith('/chapter')) return jsonResponse(200, { bookObjectId: 'BK-' + Math.random(), slug: 'x' });
            if (u.includes('extract-scenes-only')) {
                bodies.push(JSON.parse(init.body));
                return jsonResponse(202, { jobId: 'job-' + bodies.length, status: 'running' });
            }
            if (u.includes('create-from-scenes')) {
                let b = JSON.parse(init.body);
                return jsonResponse(200, { bookObjectId: 'PB1', pb2BookObjectId: b.pb2BookObjectId, scenes: b.sceneList });
            }
            return jsonResponse(200, {
                jobId: 'j', status: 'completed', terminal: true, current: 1, total: 1,
                result: { sceneList: [{ title: 'S', characters: ['A'] }], extractionComplete: true }
            });
        });
        vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});

        await pb.doExtract();

        expect(bodies.map(b => b.count)).toEqual([10, 20]);
    });
});

describe('Step 2 re-extract', () => {
    it('unchanged count resumes (no fresh); changed count re-extracts fresh with the new count', async () => {
        let calls = [];
        stubSingleRun([{ startOffset: 0, endOffset: 57113, title: null }], calls);
        await pb.doExtract();
        expect(pb.__extractStateForTest().step).toBe(2);

        await pb.doReextract();
        expect(calls[1].url).not.toContain('fresh=true');
        expect(calls[1].body.count).toBe(23);

        pb.setSceneCountFromInput('40');
        expect(pb.sceneCountChanged()).toBe(true);
        await pb.doReextract();
        expect(calls[2].url).toContain('fresh=true');
        expect(calls[2].body.count).toBe(40);
        expect(pb.sceneCountChanged()).toBe(false);
    });

    it('a reattached run (count unknown) is never treated as changed, so it is not discarded', () => {
        pb.__setExtractStateForTest({ step: 2, extractedScenes: [{ title: 'A' }], sceneCount: 23, lastExtractCount: null });
        expect(pb.sceneCountChanged()).toBe(false);
    });

    it('Step 1 and Step 2 both render the scene-count input', () => {
        pb.__setExtractStateForTest({ sceneCount: 23, sourceChars: 57113 });
        let html1 = JSON.stringify(pb.renderStep1());
        expect(html1).toContain('Scene Count');
        expect(html1).toContain('data-pb-scene-count');
        expect(html1).toContain('Suggested 23');
        pb.__setExtractStateForTest({ step: 2, extractedScenes: [{ title: 'A' }] });
        let html2 = JSON.stringify(pb.renderStep2());
        expect(html2).toContain('data-pb-scene-count');
        expect(html2).toContain('data-pb-reextract');
    });
});
