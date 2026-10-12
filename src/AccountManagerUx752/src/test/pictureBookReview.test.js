// @vitest-environment jsdom
/**
 * PictureBook V3 review queue (P1-10 S4) — /picture-book/:id/review.
 *
 * Why: generated artifacts wait for a verdict unless auto-approve is on, and the page must show
 * server state only: one GET per filter change, artifact chains newest-first, "Use this" / Approve /
 * Reject as PUTs on the artifact route followed by a refetch, and any 409 body toasted verbatim
 * (e.g. selecting on a pinned node, or the whole route under the v1 render path).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import {
    orderNewestFirst, reviewStatusOf, isAutoApproved, artifactActions, queueSummary, nodeLabel,
    renderReviewHeader, renderReviewBody, loadQueue, __reviewStateForTest, __setReviewStateForTest
} from '../features/pictureBookReview.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

const A1 = { objectId: 'a1', revision: 1, reviewStatus: 'REJECTED', selected: false, dataObjectId: 'd1', backend: 'swarm', seed: 11 };
const A2 = { objectId: 'a2', revision: 2, reviewStatus: 'APPROVED', approvedBy: 'AUTO', approvedAt: '2026-10-11T10:00:00Z', selected: true, dataObjectId: 'd2' };
const A3 = { objectId: 'a3', revision: 3, reviewStatus: 'GENERATED', selected: false, dataObjectId: 'd3' };
const QUEUE = {
    bookObjectId: 'B1', filter: 'all', autoApprove: false, pendingCount: 1, artifactCount: 3,
    nodes: [{ nodeObjectId: 'n1', handle: 'scene-1', sceneIndex: 1, sceneObjectId: 's1', nodeStatus: 'DONE_UNVERIFIED', pinned: false, artifacts: [A1, A2, A3] }]
};

let origFetch;

beforeEach(() => {
    origFetch = global.fetch;
    __setReviewStateForTest({ bookObjectId: 'B1', queue: null, loading: false, error: null, filter: 'all', busyArtifact: null });
    // resolveImageUrl → am7client.get (XHR): answer with a path-shaped data.data record.
    vi.spyOn(am7client, 'get').mockImplementation((type, id, cb) => { cb({ objectId: id, groupPath: '/PictureBook/x', name: id + '.png' }); });
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
});

afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── pure derivations ───────────────────────────────

describe('pure derivations', () => {
    it('orderNewestFirst reverses a COPY (server chain is ascending)', () => {
        let chain = [A1, A2, A3];
        let out = orderNewestFirst(chain);
        expect(out.map(a => a.objectId)).toEqual(['a3', 'a2', 'a1']);
        expect(chain.map(a => a.objectId)).toEqual(['a1', 'a2', 'a3']);
        expect(orderNewestFirst(null)).toEqual([]);
    });
    it('reviewStatusOf defaults to GENERATED and uppercases lowercase wire enums', () => {
        expect(reviewStatusOf({})).toBe('GENERATED');
        expect(reviewStatusOf({ reviewStatus: 'approved' })).toBe('APPROVED');
        expect(reviewStatusOf(null)).toBe('GENERATED');
    });
    it('isAutoApproved = APPROVED && approvedBy AUTO (case-insensitive); a human approval is not', () => {
        expect(isAutoApproved(A2)).toBe(true);
        expect(isAutoApproved({ reviewStatus: 'approved', approvedBy: 'auto' })).toBe(true);
        expect(isAutoApproved({ reviewStatus: 'APPROVED', approvedBy: 'steve' })).toBe(false);
        expect(isAutoApproved({ reviewStatus: 'GENERATED', approvedBy: 'AUTO' })).toBe(false);
    });
    it('artifactActions: selected → no Use; pinned node → no Use; verdict already given not re-offered; ARCHIVED nothing', () => {
        expect(artifactActions(A3, false)).toEqual({ canUse: true, canApprove: true, canReject: true });
        expect(artifactActions(A2, false)).toEqual({ canUse: false, canApprove: false, canReject: true });
        expect(artifactActions(A1, false)).toEqual({ canUse: true, canApprove: true, canReject: false });
        expect(artifactActions(A3, true).canUse).toBe(false);
        expect(artifactActions({ reviewStatus: 'ARCHIVED' }, false)).toEqual({ canUse: false, canApprove: false, canReject: false });
    });
    it('queueSummary uses server counts when present and derives artifact count otherwise', () => {
        expect(queueSummary(QUEUE)).toEqual({ nodes: 1, artifacts: 3, pending: 1, autoApprove: false, filter: 'all' });
        expect(queueSummary({ nodes: [{ artifacts: [A1] }, { artifacts: [A2, A3] }], autoApprove: true, filter: 'auto-approved' }))
            .toEqual({ nodes: 2, artifacts: 3, pending: 0, autoApprove: true, filter: 'auto-approved' });
        expect(queueSummary(null)).toEqual({ nodes: 0, artifacts: 0, pending: 0, autoApprove: false, filter: 'all' });
    });
    it('nodeLabel prefers "Scene N · handle"', () => {
        expect(nodeLabel(QUEUE.nodes[0])).toBe('Scene 1 · scene-1');
        expect(nodeLabel({ handle: 'cover' })).toBe('cover');
        expect(nodeLabel({ nodeObjectId: 'n9' })).toBe('n9');
        expect(nodeLabel(null)).toBe('');
    });
});

// ─────────────────────────────── rendering ───────────────────────────────

describe('renderReviewHeader / renderReviewBody', () => {
    it('header: counts, auto-approve pill, filter tabs with the active one marked', () => {
        __setReviewStateForTest({ queue: QUEUE, filter: 'all' });
        let text = JSON.stringify(renderReviewHeader());
        expect(text).toContain('1 pending · 3 artifacts · 1 node');
        expect(text).toContain('"data-testid":"pb2-review-auto-approve","data-on":"0"');
        expect(text).toContain('"data-testid":"pb2-review-filter-all","data-active":"1"');
        expect(text).toContain('"data-testid":"pb2-review-filter-auto","data-active":"0"');
        expect(text).toContain('"data-testid":"pb2-review-back"');
        expect(text).toContain('"data-testid":"pb2-review-refresh"');
    });
    it('body: artifacts newest-first, current + auto-approved marks, per-artifact action enablement', () => {
        __setReviewStateForTest({ queue: QUEUE });
        let body = renderReviewBody();
        let text = JSON.stringify(body);
        let ids = [...text.matchAll(/"data-artifact-id":"(a\d)"/g)].map(x => x[1]);
        expect(ids).toEqual(['a3', 'a2', 'a1']);
        expect(text).toContain('"data-testid":"pb2-review-node","data-node-id":"n1"');
        expect(text).toContain('Scene 1 · scene-1');
        expect(text).toContain('"data-testid":"pb2-review-selected"');
        expect(text).toContain('"data-testid":"pb2-review-auto-mark"');
        expect(text).toContain('approved by AUTO · 2026-10-11T10:00:00Z');
        // a2 is selected+APPROVED: Use/Approve disabled, Reject enabled
        let a2 = text.slice(text.indexOf('"data-artifact-id":"a2"'), text.indexOf('"data-artifact-id":"a1"'));
        expect(a2).toMatch(/"data-testid":"pb2-review-use","class":null,"disabled":true/);
        expect(a2).toMatch(/"data-testid":"pb2-review-approve","class":null,"disabled":true/);
        expect(a2).toMatch(/"data-testid":"pb2-review-reject","class":null,"disabled":false/);
    });
    it('body: empty states per filter; error state; loading state', () => {
        __setReviewStateForTest({ queue: { nodes: [] }, filter: 'all' });
        expect(JSON.stringify(renderReviewBody())).toContain('Nothing to review.');
        __setReviewStateForTest({ filter: 'auto-approved' });
        expect(JSON.stringify(renderReviewBody())).toContain('No auto-approved artifacts.');
        __setReviewStateForTest({ queue: null, error: '(409) Review queue is a V3 feature; the picturebook render path is v1' });
        let text = JSON.stringify(renderReviewBody());
        expect(text).toContain('"data-testid":"pb2-review-error"');
        expect(text).toContain('(409) Review queue is a V3 feature');
        __setReviewStateForTest({ error: null, loading: true });
        expect(JSON.stringify(renderReviewBody())).toContain('Loading review queue...');
    });
});

// ─────────────────────────────── REST flows ───────────────────────────────

function flushPromises() { return new Promise(r => setTimeout(r, 0)); }

function findButton(vnode, testid) {
    let found = null;
    (function walk(v) {
        if (!v || found) return;
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.attrs && v.attrs['data-testid'] === testid) { found = v; return; }
        walk(v.children);
    })(vnode);
    return found;
}

function findArtifactButton(body, artifactId, testid) {
    let art = null;
    (function walk(v) {
        if (!v || art) return;
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.attrs && v.attrs['data-artifact-id'] === artifactId) { art = v; return; }
        walk(v.children);
    })(body);
    return findButton(art, testid);
}

describe('loadQueue + actions', () => {
    it('loadQueue GETs /review-queue?filter=all and stores the normalized queue', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push({ url: String(u), method: (o && o.method) || 'GET' }); return jsonResponse(200, QUEUE); });
        await loadQueue();
        expect(calls[0].method).toBe('GET');
        expect(calls[0].url).toMatch(/\/rest\/olio\/picture-book\/B1\/review-queue\?filter=all$/);
        let st = __reviewStateForTest();
        expect(st.loading).toBe(false);
        expect(st.error).toBeNull();
        expect(st.queue.nodes.length).toBe(1);
        expect(st.queue.nodes[0].artifacts.map(a => a.objectId)).toEqual(['a1', 'a2', 'a3']);
    });
    it('filter tab → one GET with filter=auto-approved; same tab again → no request', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u) => { calls.push(String(u)); return jsonResponse(200, Object.assign({}, QUEUE, { filter: 'auto-approved', nodes: [] })); });
        __setReviewStateForTest({ queue: QUEUE, filter: 'all' });
        findButton(renderReviewHeader(), 'pb2-review-filter-auto').attrs.onclick();
        await flushPromises();
        expect(calls.length).toBe(1);
        expect(calls[0]).toMatch(/review-queue\?filter=auto-approved$/);
        expect(__reviewStateForTest().filter).toBe('auto-approved');
        findButton(renderReviewHeader(), 'pb2-review-filter-auto').attrs.onclick();
        await flushPromises();
        expect(calls.length).toBe(1);
    });
    it('a non-2xx on load shows the server body (e.g. the v1 409) and clears the queue', async () => {
        global.fetch = vi.fn(async () => jsonResponse(409, { error: 'Review queue requires the v3 picturebook render path' }));
        __setReviewStateForTest({ queue: QUEUE });
        await loadQueue();
        let st = __reviewStateForTest();
        expect(st.queue).toBeNull();
        expect(st.error).toBe('(409) Review queue requires the v3 picturebook render path');
    });
    it('"Use this" PUTs /artifact/{id}/select, toasts, then refetches the queue', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = String(u);
            calls.push({ url, method: (o && o.method) || 'GET' });
            if (/\/artifact\/a3\/select$/.test(url)) return jsonResponse(200, Object.assign({}, A3, { selected: true }));
            return jsonResponse(200, QUEUE);
        });
        __setReviewStateForTest({ queue: QUEUE });
        findArtifactButton(renderReviewBody(), 'a3', 'pb2-review-use').attrs.onclick();
        await flushPromises(); await flushPromises();
        let seq = calls.map(c => c.method + ' ' + c.url.replace(/^.*\/rest\//, '/rest/'));
        expect(seq).toEqual([
            'PUT /rest/olio/picture-book/B1/artifact/a3/select',
            'GET /rest/olio/picture-book/B1/review-queue?filter=all'
        ]);
        expect(page.toast).toHaveBeenCalledWith('success', 'Revision 3 is now current');
        expect(__reviewStateForTest().busyArtifact).toBeNull();
    });
    it('Approve / Reject hit their routes', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push(((o && o.method) || 'GET') + ' ' + String(u).replace(/^.*\/rest\//, '/rest/')); return jsonResponse(200, QUEUE); });
        __setReviewStateForTest({ queue: QUEUE });
        findArtifactButton(renderReviewBody(), 'a3', 'pb2-review-approve').attrs.onclick();
        await flushPromises(); await flushPromises();
        findArtifactButton(renderReviewBody(), 'a3', 'pb2-review-reject').attrs.onclick();
        await flushPromises(); await flushPromises();
        expect(calls.filter(c => c.startsWith('PUT'))).toEqual([
            'PUT /rest/olio/picture-book/B1/artifact/a3/approve',
            'PUT /rest/olio/picture-book/B1/artifact/a3/reject'
        ]);
        expect(page.toast).toHaveBeenCalledWith('success', 'Approved');
        expect(page.toast).toHaveBeenCalledWith('success', 'Rejected');
    });
    it('a 409 on an action is toasted verbatim and the queue is still refetched', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = String(u);
            calls.push(((o && o.method) || 'GET') + ' ' + url.replace(/^.*\/rest\//, '/rest/'));
            if (/\/select$/.test(url)) return jsonResponse(409, { error: 'Node scene-1 is pinned; unpin it before selecting another artifact' });
            return jsonResponse(200, QUEUE);
        });
        __setReviewStateForTest({ queue: QUEUE });
        findArtifactButton(renderReviewBody(), 'a3', 'pb2-review-use').attrs.onclick();
        await flushPromises(); await flushPromises();
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Node scene-1 is pinned; unpin it before selecting another artifact');
        expect(calls[1]).toBe('GET /rest/olio/picture-book/B1/review-queue?filter=all');
    });
    it('a second click while an action is in flight is ignored', async () => {
        let resolveSelect;
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = String(u);
            calls.push(((o && o.method) || 'GET') + ' ' + url.replace(/^.*\/rest\//, '/rest/'));
            if (/\/select$/.test(url)) return new Promise(r => { resolveSelect = () => r(jsonResponse(200, A3)); });
            return jsonResponse(200, QUEUE);
        });
        __setReviewStateForTest({ queue: QUEUE });
        let body = renderReviewBody();
        findArtifactButton(body, 'a3', 'pb2-review-use').attrs.onclick();
        await flushPromises();
        expect(__reviewStateForTest().busyArtifact).toBe('a3');
        findArtifactButton(body, 'a1', 'pb2-review-approve').attrs.onclick();
        await flushPromises();
        expect(calls.filter(c => c.startsWith('PUT')).length).toBe(1);
        resolveSelect();
        await flushPromises(); await flushPromises();
        expect(__reviewStateForTest().busyArtifact).toBeNull();
    });
});
