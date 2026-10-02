// @vitest-environment jsdom
/**
 * Unfinished extractions (server-side checkpoints with no book behind them).
 *
 * Why: a single-document extraction persists ONLY its checkpoint note until createFromScenes runs,
 * so an interrupted run was invisible in both book lists and the only way to remove it was
 * `?fresh=true` — which starts another LLM run. These cover the client half of the surface that
 * fixes that: the three REST helpers' request shapes, the Step 1 banner's load/render/discard
 * state, and the refresh after an extraction run.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { listExtractCheckpoints, getExtractCheckpoints, discardExtractCheckpoints } from '../workflows/sceneExtractor.js';

function jsonResponse(status, body) {
    return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const ROW_PARTIAL = {
    workObjectId: 'work-1', noteObjectId: 'note-a', startOffset: null, endOffset: null,
    chunksProcessed: 5, totalChunks: 17, sceneCount: 3, failedCount: 0, complete: false,
    updatedAt: '2026-10-02T18:00:00Z', workName: 'Ourselves.doc', workMissing: false
};
const ROW_COMPLETE_FAILED = {
    workObjectId: 'work-1', noteObjectId: 'note-b', startOffset: 0, endOffset: 1000,
    chunksProcessed: 4, totalChunks: 4, sceneCount: 7, failedCount: 2, complete: true,
    updatedAt: '2026-10-02T19:00:00Z', workName: 'Ourselves.doc', workMissing: false
};

let origFetch;
let pb;

beforeEach(async () => {
    origFetch = global.fetch;
    pb = await import('../workflows/pictureBook.js');
    pb.__resetExtractStateForTest('work-1');
    pb.__setExtractStateForTest({ workName: 'Ourselves.doc' });
});

afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

describe('sceneExtractor checkpoint helpers — request shapes', () => {
    it('listExtractCheckpoints GETs the user-wide route and returns the array', async () => {
        let calls = [];
        global.fetch = vi.fn(async (url, opts) => { calls.push({ url: String(url), opts }); return jsonResponse(200, [ROW_PARTIAL]); });
        let rows = await listExtractCheckpoints();
        expect(rows).toEqual([ROW_PARTIAL]);
        expect(calls).toHaveLength(1);
        expect(calls[0].url).toMatch(/\/rest\/olio\/picture-book\/extract-checkpoints$/);
        expect(calls[0].opts.method).toBe('GET');
        expect(calls[0].opts.credentials).toBe('include');
    });

    it('getExtractCheckpoints GETs the per-document route; a non-array body is an empty list', async () => {
        let url = null;
        global.fetch = vi.fn(async (u) => { url = String(u); return jsonResponse(200, { unexpected: true }); });
        let rows = await getExtractCheckpoints('work-1');
        expect(url).toMatch(/\/picture-book\/work-1\/extract-checkpoints$/);
        expect(rows).toEqual([]);
    });

    it('discardExtractCheckpoints DELETEs with no range for a whole-document checkpoint', async () => {
        let call = null;
        global.fetch = vi.fn(async (u, o) => { call = { url: String(u), opts: o }; return jsonResponse(200, { deleted: 1 }); });
        let deleted = await discardExtractCheckpoints('work-1', { startOffset: null, endOffset: null });
        expect(deleted).toBe(1);
        expect(call.url).toMatch(/\/picture-book\/work-1\/extract-checkpoints$/);
        expect(call.url).not.toContain('startOffset');
        expect(call.opts.method).toBe('DELETE');
    });

    it('discardExtractCheckpoints carries the chapter range as query params', async () => {
        let url = null;
        global.fetch = vi.fn(async (u) => { url = String(u); return jsonResponse(200, { deleted: 1 }); });
        await discardExtractCheckpoints('work-1', { startOffset: 0, endOffset: 1000 });
        expect(url).toContain('/work-1/extract-checkpoints?startOffset=0&endOffset=1000');
    });

    it('a non-2xx discard throws rather than reporting 0 deleted', async () => {
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        await expect(discardExtractCheckpoints('work-1', {})).rejects.toThrow(/500/);
    });
});

describe('wizard Step 1 checkpoint banner', () => {
    it('loadPendingCheckpoints fills pendingCheckpoints for the open document', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, [ROW_PARTIAL, ROW_COMPLETE_FAILED]));
        await pb.loadPendingCheckpoints();
        expect(pb.__extractStateForTest().pendingCheckpoints).toHaveLength(2);
    });

    it('a failed lookup hides the banner instead of throwing', async () => {
        pb.__setExtractStateForTest({ pendingCheckpoints: [ROW_PARTIAL] });
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        await pb.loadPendingCheckpoints();
        expect(pb.__extractStateForTest().pendingCheckpoints).toEqual([]);
    });

    it('describeCheckpointRow states progress, scenes saved, failures and the chapter range', () => {
        expect(pb.describeCheckpointRow(ROW_PARTIAL)).toBe('5 of 17 passages · 3 scenes saved');
        expect(pb.describeCheckpointRow(ROW_COMPLETE_FAILED)).toBe('chars 0–1000 · 4 of 4 passages · 7 scenes saved · 2 failed passages');
    });

    it('renders nothing with no checkpoints, and nothing while a run is in flight', () => {
        expect(pb.renderCheckpointBanner()).toBeNull();
        pb.__setExtractStateForTest({ pendingCheckpoints: [ROW_PARTIAL], extracting: true });
        expect(pb.renderCheckpointBanner()).toBeNull();
    });

    it('renders Resume for an incomplete checkpoint and "Review saved scenes" for a complete-with-failures one', () => {
        pb.__setExtractStateForTest({ pendingCheckpoints: [ROW_PARTIAL, ROW_COMPLETE_FAILED] });
        let vnode = pb.renderCheckpointBanner();
        expect(vnode).not.toBeNull();
        expect(vnode.attrs['data-pb-checkpoint-banner']).toBe('1');
        let text = JSON.stringify(vnode);
        expect(text).toContain('"data-pb-checkpoint-resume"');
        expect(text).toContain('"data-pb-checkpoint-discard"');
        expect(text).toContain('Resume');
        expect(text).toContain('Review saved scenes');
        expect(text).toContain('2 earlier extractions');
    });

    it('discardPendingCheckpoint: confirm → DELETE with the row range → toast → reload', async () => {
        pb.__setExtractStateForTest({ pendingCheckpoints: [ROW_COMPLETE_FAILED] });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            calls.push({ url: String(u), method: o && o.method });
            if (o && o.method === 'DELETE') return jsonResponse(200, { deleted: 1 });
            return jsonResponse(200, []);
        });

        await pb.discardPendingCheckpoint(ROW_COMPLETE_FAILED);

        let del = calls.find(c => c.method === 'DELETE');
        expect(del).toBeTruthy();
        expect(del.url).toContain('/work-1/extract-checkpoints?startOffset=0&endOffset=1000');
        // The reload after discard re-reads the per-document checkpoints.
        let reload = calls.find(c => c.method === 'GET');
        expect(reload.url).toMatch(/\/work-1\/extract-checkpoints$/);
        expect(toast).toHaveBeenCalledWith('success', expect.stringMatching(/discarded/i));
        let s = pb.__extractStateForTest();
        expect(s.pendingCheckpoints).toEqual([]);
        expect(s.checkpointDiscarding).toBe(false);
    });

    it('discardPendingCheckpoint: a declined confirm sends nothing', async () => {
        pb.__setExtractStateForTest({ pendingCheckpoints: [ROW_PARTIAL] });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        global.fetch = vi.fn(async () => jsonResponse(200, { deleted: 1 }));
        await pb.discardPendingCheckpoint(ROW_PARTIAL);
        expect(global.fetch).not.toHaveBeenCalled();
        expect(pb.__extractStateForTest().pendingCheckpoints).toEqual([ROW_PARTIAL]);
    });

    it('discardPendingCheckpoint: a server failure toasts an error and keeps the row', async () => {
        pb.__setExtractStateForTest({ pendingCheckpoints: [ROW_PARTIAL] });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        global.fetch = vi.fn(async (u, o) => (o && o.method === 'DELETE')
            ? jsonResponse(500, {}) : jsonResponse(200, [ROW_PARTIAL]));
        await pb.discardPendingCheckpoint(ROW_PARTIAL);
        expect(toast).toHaveBeenCalledWith('error', expect.stringMatching(/500/));
        expect(pb.__extractStateForTest().pendingCheckpoints).toEqual([ROW_PARTIAL]);
    });

    it('doExtract refreshes the banner when the run ends — a clean finish clears it', async () => {
        pb.__setExtractStateForTest({ pendingCheckpoints: [ROW_PARTIAL] });
        let checkpointReads = 0;
        global.fetch = vi.fn(async (url) => {
            let u = String(url);
            if (u.includes('detect-boundaries')) return jsonResponse(200, []);
            if (u.includes('extract-scenes-only')) return jsonResponse(202, { jobId: 'j', status: 'running' });
            if (u.endsWith('/extract-checkpoints')) { checkpointReads++; return jsonResponse(200, []); }
            return jsonResponse(200, { jobId: 'j', status: 'completed', terminal: true,
                result: { sceneList: [{ title: 'A' }], extractionComplete: true } });
        });
        await pb.doExtract();
        // The refresh is fire-and-forget from the finally block; let it settle.
        await new Promise(r => setTimeout(r, 0));
        await new Promise(r => setTimeout(r, 0));
        expect(checkpointReads).toBe(1);
        expect(pb.__extractStateForTest().pendingCheckpoints).toEqual([]);
    });
});
