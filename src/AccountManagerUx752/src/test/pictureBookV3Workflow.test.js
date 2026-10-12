import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import {
    regenerateNode, renderScene, renderAll, getRun, resumeRun, cancelRun,
    selectArtifact, approveArtifact, rejectArtifact, getReviewQueue,
    getAutoApprove, setAutoApprove, getAffectedBy, getStale, unpinNode,
    isPinnedRefusal, normalizeRun, normalizeReviewQueue, normalizeAffected
} from '../workflows/pictureBookWorkflow.js';

// P1-10 S1: every V3 helper shares one non-2xx pattern (the testNode pattern) and normalizes the
// server's Include.NON_EMPTY omissions once, here, so views never test for a missing array.
// 409 bodies are the planner's real messages (PbRenderJobUtil / PbServiceFacade).

const PINNED_409 = '{"error":"Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)"}';
const INFLIGHT_409 = '{"error":"Node scene-3-image is being rendered by run run-7"}';
const RESUME_409 = '{"error":"Run run-7 is DONE; only an INTERRUPTED run can be resumed"}';
const QUOTA_429 = '{"error":"Principal already has 2 of 2 picture-book render jobs in flight"}';

function mockResponse(status, bodyText) {
    let ok = status >= 200 && status < 300;
    return {
        ok, status,
        text: async () => bodyText,
        json: async () => JSON.parse(bodyText),
    };
}

function mockFetch(status, bodyText) {
    global.fetch = vi.fn(async () => mockResponse(status, bodyText));
    return global.fetch;
}

beforeEach(() => { global.fetch = vi.fn(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('uniform non-2xx pattern', () => {
    it('regenerateNode surfaces the 409 body instead of discarding it', async () => {
        mockFetch(409, INFLIGHT_409);
        let err;
        try { await regenerateNode('book', 'node'); } catch (e) { err = e; }
        expect(err).toBeTruthy();
        expect(err.message).toBe('(409) Node scene-3-image is being rendered by run run-7');
        expect(err.status).toBe(409);
        expect(err.body).toBe(INFLIGHT_409);
    });
    it('renderScene throws (409) + the pinned message, and isPinnedRefusal recognises it', async () => {
        mockFetch(409, PINNED_409);
        let err;
        try { await renderScene('book', 'scene'); } catch (e) { err = e; }
        expect(err.message).toBe('(409) Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)');
        expect(isPinnedRefusal(err)).toBe(true);
    });
    it('an in-flight 409 and a 429 are NOT pinned refusals', async () => {
        mockFetch(409, INFLIGHT_409);
        let a; try { await renderAll('book'); } catch (e) { a = e; }
        expect(isPinnedRefusal(a)).toBe(false);
        mockFetch(429, QUOTA_429);
        let b; try { await renderAll('book'); } catch (e) { b = e; }
        expect(b.message).toBe('(429) Principal already has 2 of 2 picture-book render jobs in flight');
        expect(b.status).toBe(429);
        expect(isPinnedRefusal(b)).toBe(false);
        expect(isPinnedRefusal(null)).toBe(false);
        expect(isPinnedRefusal(new Error('(409) Nothing to render: every node of scene 3 is current (2 reused, 0 pinned)'))).toBe(false);
    });
    it('resumeRun surfaces the "only an INTERRUPTED run" 409 verbatim', async () => {
        mockFetch(409, RESUME_409);
        await expect(resumeRun('book', 'run-7')).rejects.toThrow('(409) Run run-7 is DONE; only an INTERRUPTED run can be resumed');
    });
    it('uses the raw text when the body is not {error} JSON, and the fallback when empty', async () => {
        mockFetch(500, '<html>Internal error</html>');
        await expect(getRun('book', 'run')).rejects.toThrow('(500) <html>Internal error</html>');
        mockFetch(502, '');
        await expect(getRun('book', 'run')).rejects.toThrow('(502) getRun failed');
    });
    it('auto-approve PUT 400 ({error:true,message}) surfaces the message field', async () => {
        mockFetch(400, '{"error":true,"message":"autoApprove must be a JSON boolean"}');
        await expect(setAutoApprove('book', true)).rejects.toThrow('(400) autoApprove must be a JSON boolean');
    });
});

describe('request shapes', () => {
    it('renderScene / renderAll / resumeRun / cancelRun POST to the facade routes', async () => {
        let f = mockFetch(202, '{"jobId":"j1","runObjectId":"r1","planned":["scene-3-image"]}');
        await renderScene('B', 'S');
        expect(f.mock.calls[0][0]).toMatch(/\/rest\/olio\/picture-book\/B\/scene\/S\/render$/);
        expect(f.mock.calls[0][1].method).toBe('POST');
        expect(f.mock.calls[0][1].credentials).toBe('include');
        expect(f.mock.calls[0][1].body).toBeUndefined();
        await renderAll('B');
        expect(f.mock.calls[1][0]).toMatch(/\/B\/render-all$/);
        await resumeRun('B', 'R');
        expect(f.mock.calls[2][0]).toMatch(/\/B\/run\/R\/resume$/);
        mockFetch(200, '{"runObjectId":"R","runStatus":"CANCELLED","cancelRequested":true}');
        await cancelRun('B', 'R');
        expect(global.fetch.mock.calls[0][0]).toMatch(/\/B\/run\/R\/cancel$/);
        expect(global.fetch.mock.calls[0][1].method).toBe('POST');
    });
    it('renderScene appends ?force=true only when opts.force is set (explicit per-scene re-roll)', async () => {
        let f = mockFetch(202, '{"jobId":"j1","runObjectId":"r1","planned":["scene-3-image"]}');
        await renderScene('B', 'S', { force: true });
        expect(f.mock.calls[0][0]).toMatch(/\/rest\/olio\/picture-book\/B\/scene\/S\/render\?force=true$/);
        expect(f.mock.calls[0][1].method).toBe('POST');
        expect(f.mock.calls[0][1].body).toBeUndefined();
        await renderScene('B', 'S', { force: false });
        expect(f.mock.calls[1][0]).toMatch(/\/B\/scene\/S\/render$/);
        await renderScene('B', 'S', {});
        expect(f.mock.calls[2][0]).toMatch(/\/B\/scene\/S\/render$/);
    });
    it('select / approve / reject are PUTs on the artifact route', async () => {
        let f = mockFetch(200, '{"objectId":"A","reviewStatus":"APPROVED","selected":true}');
        await selectArtifact('B', 'A');
        await approveArtifact('B', 'A');
        await rejectArtifact('B', 'A');
        expect(f.mock.calls.map(c => c[0].replace(/^.*\/B\//, ''))).toEqual([
            'artifact/A/select', 'artifact/A/approve', 'artifact/A/reject']);
        expect(f.mock.calls.every(c => c[1].method === 'PUT')).toBe(true);
    });
    it('setAutoApprove sends {autoApprove: bool} as JSON, not a bare boolean', async () => {
        let f = mockFetch(200, '{"bookObjectId":"B","autoApprove":true}');
        let r = await setAutoApprove('B', 1);
        expect(f.mock.calls[0][0]).toMatch(/\/B\/settings\/auto-approve$/);
        expect(f.mock.calls[0][1].method).toBe('PUT');
        expect(f.mock.calls[0][1].headers['Content-Type']).toBe('application/json');
        expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ autoApprove: true });
        expect(r.autoApprove).toBe(true);
    });
    it('unpinNode posts {pinned:false} to the pin route', async () => {
        let f = mockFetch(200, '{"objectId":"N","pinned":false}');
        await unpinNode('B', 'N');
        expect(f.mock.calls[0][0]).toMatch(/\/B\/node\/N\/pin$/);
        expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ pinned: false });
    });
    it('getReviewQueue only ever sends filter=all or filter=auto-approved', async () => {
        let f = mockFetch(200, '{"bookObjectId":"B"}');
        await getReviewQueue('B', 'auto-approved');
        await getReviewQueue('B', 'bogus');
        await getReviewQueue('B');
        expect(f.mock.calls[0][0]).toMatch(/review-queue\?filter=auto-approved$/);
        expect(f.mock.calls[1][0]).toMatch(/review-queue\?filter=all$/);
        expect(f.mock.calls[2][0]).toMatch(/review-queue\?filter=all$/);
    });
    it('getAffectedBy encodes the ref path segments', async () => {
        let f = mockFetch(200, '{"bookObjectId":"B","boundNodes":2}');
        await getAffectedBy('B', 'olio.charPerson', 'c 1');
        expect(f.mock.calls[0][0]).toMatch(/\/B\/affected-by\/olio\.charPerson\/c%201$/);
    });
});

describe('NON_EMPTY normalization', () => {
    it('submit result: missing planned[] / scenesWithoutNode[] / counts default', async () => {
        mockFetch(202, '{"jobId":"j1","runObjectId":"r1","runStatus":"RUNNING"}');
        let r = await renderAll('B');
        expect(r.planned).toEqual([]);
        expect(r.scenesWithoutNode).toEqual([]);
        expect(r.plannedNodeCount).toBe(0);
        expect(r.reusedNodeCount).toBe(0);
        expect(r.pinnedNodeCount).toBe(0);
        expect(r.jobId).toBe('j1');
    });
    it('getRun: nodes[]/requestedNodeIds[] default, jobId/error null, resumable boolean', async () => {
        mockFetch(200, '{"runObjectId":"r1","runStatus":"INTERRUPTED","resumable":true}');
        let r = await getRun('B', 'r1');
        expect(r.nodes).toEqual([]);
        expect(r.requestedNodeIds).toEqual([]);
        expect(r.jobId).toBeNull();
        expect(r.error).toBeNull();
        expect(r.resumable).toBe(true);
        expect(r.inFlightInThisProcess).toBe(false);
        expect(r.executedNodeCount).toBe(0);
        expect(normalizeRun(null).runStatus).toBeNull();
        expect(normalizeRun(undefined).nodes).toEqual([]);
    });
    it('review queue: nodes[] and per-node artifacts[] default; counts and autoApprove default', async () => {
        mockFetch(200, '{"bookObjectId":"B","filter":"all","nodes":[{"nodeObjectId":"n1","handle":"scene-1-image"}]}');
        let q = await getReviewQueue('B', 'all');
        expect(q.autoApprove).toBe(false);
        expect(q.artifactCount).toBe(0);
        expect(q.pendingCount).toBe(0);
        expect(q.nodes.length).toBe(1);
        expect(q.nodes[0].artifacts).toEqual([]);
        expect(q.nodes[0].pinned).toBe(false);
        expect(q.nodes[0].sceneIndex).toBeNull();
        let e = normalizeReviewQueue({});
        expect(e.nodes).toEqual([]);
        expect(e.filter).toBe('all');
    });
    it('review queue artifact rows keep server fields and get selected/reviewStatus defaults', () => {
        let q = normalizeReviewQueue({ nodes: [{ nodeObjectId: 'n', artifacts: [{ objectId: 'a1', revision: 1 }, { objectId: 'a2', revision: 2, selected: true, reviewStatus: 'approved' }] }] });
        expect(q.nodes[0].artifacts[0]).toMatchObject({ objectId: 'a1', revision: 1, selected: false, reviewStatus: null, dataObjectId: null });
        expect(q.nodes[0].artifacts[1]).toMatchObject({ objectId: 'a2', selected: true, reviewStatus: 'approved' });
    });
    it('affected-by: all three index lists and the flags default', async () => {
        mockFetch(200, '{"bookObjectId":"B","refModel":"olio.charPerson","refObjectId":"c1"}');
        let a = await getAffectedBy('B', 'olio.charPerson', 'c1');
        expect(a.boundNodes).toBe(0);
        expect(a.drifted).toBe(false);
        expect(a.markPersisted).toBe(false);
        expect(a.driftedHandles).toEqual([]);
        expect(a.affectedSceneIndexes).toEqual([]);
        expect(a.staleSceneIndexes).toEqual([]);
        expect(normalizeAffected(null).affectedSceneIndexes).toEqual([]);
        expect(normalizeAffected({ affectedSceneIndexes: [2, 5], drifted: true }).affectedSceneIndexes).toEqual([2, 5]);
    });
    it('getStale returns [] for a non-array body; getAutoApprove coerces to boolean', async () => {
        mockFetch(200, 'null');
        expect(await getStale('B')).toEqual([]);
        mockFetch(200, '[{"objectId":"n1","nodeStatus":"STALE"}]');
        expect((await getStale('B')).length).toBe(1);
        mockFetch(200, '{"bookObjectId":"B"}');
        expect((await getAutoApprove('B')).autoApprove).toBe(false);
        mockFetch(200, '{"bookObjectId":"B","autoApprove":true}');
        expect((await getAutoApprove('B')).autoApprove).toBe(true);
    });
    it('selectArtifact result gets selected/reviewStatus defaults', async () => {
        mockFetch(200, '{"objectId":"A","revision":3}');
        let r = await selectArtifact('B', 'A');
        expect(r).toMatchObject({ objectId: 'A', revision: 3, selected: false, reviewStatus: null });
    });
});
