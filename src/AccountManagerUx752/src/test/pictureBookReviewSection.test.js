// @vitest-environment jsdom
/**
 * Edit › Review — the bookWorkspace SectionAdapter (aiDocs/PictureBookUxConsistencyDesign.md §5.8, §4.4, §4.6).
 *
 * Why: in Edit mode the review queue is a section, not a page. Its toolbar is a pageHeader with no back arrow
 * (the rail replaces it), the All / Auto-approved filter is the shared `segmented` control, the Auto-approve chip
 * links to Edit › Book through the dirty guard, the body is master/detail (nodes with derived badges on the left,
 * the selected node's artifact rows and its render plan — expanded, no toggle — on the right), buttons come from
 * components/buttons.js, and the rail count is the queue's PENDING count (what waits for a decision — §4.3 / D6:
 * an all-approved book must not look incomplete), loadable without opening the section.
 * Rendered with the real Mithril renderer into jsdom; REST is the real client over a mocked fetch.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { pageHeader } from '../components/pageHeader.js';
import { __resetDirtyForTest } from '../components/dirtyGuard.js';
import {
    reviewSection, loadReviewCount, nodeBadges, loadQueue, renderReviewSectionBody,
    __reviewStateForTest, __setReviewStateForTest
} from '../features/pictureBookReview.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function flushPromises() { return new Promise(r => setTimeout(r, 0)); }
function restPath(u) { return String(u).replace(/^.*\/rest\//, '/rest/'); }
function render(vnode) {
    let div = document.createElement('div');
    m.render(div, vnode);
    return div;
}
/** What bookWorkspace.renderPane does with toolbar(ctx). */
function renderToolbar(ctx) {
    return render(pageHeader(Object.assign({ bar: true }, reviewSection.toolbar(ctx), { back: null })));
}

const QA_FAIL = { result: 'FAIL', provider: 'ollama', checks: [{ kind: 'PERSON_COUNT', result: 'FAIL', expected: 2, observed: 1 }] };
const QA_WARN = { result: 'WARN', checks: [] };
// n1: two pending artifacts, one QA FAIL           → "2 unreviewed", "QA warning"(red)
// n2: STALE, current artifact APPROVED, one WARN    → "QA warning"(amber), "stale · approved"
// n3: DONE, everything decided                       → no badges
const N1 = { nodeObjectId: 'n1', handle: 'scene-1', sceneIndex: 1, nodeStatus: 'DONE_UNVERIFIED', pinned: false, artifacts: [
    { objectId: 'a1', revision: 1, reviewStatus: 'GENERATED', selected: false, dataObjectId: 'd1', qa: QA_FAIL },
    { objectId: 'a2', revision: 2, reviewStatus: 'IN_REVIEW', selected: true, dataObjectId: 'd2' }
] };
const N2 = { nodeObjectId: 'n2', handle: 'scene-2', sceneIndex: 2, nodeStatus: 'stale', pinned: true, artifacts: [
    { objectId: 'b1', revision: 1, reviewStatus: 'APPROVED', approvedBy: 'steve', selected: true, dataObjectId: 'e1', qa: QA_WARN }
] };
const N3 = { nodeObjectId: 'n3', handle: 'cover', sceneIndex: null, nodeStatus: 'DONE', pinned: false, artifacts: [
    { objectId: 'c1', revision: 1, reviewStatus: 'REJECTED', selected: false },
    { objectId: 'c2', revision: 2, reviewStatus: 'APPROVED', approvedBy: 'AUTO', selected: true }
] };
const QUEUE = { bookObjectId: 'B1', filter: 'all', autoApprove: true, pendingCount: 2, artifactCount: 5, nodes: [N1, N2, N3] };
const PLAN = { planVersion: 'illustration/1', purpose: 'SCENE', canvas: { width: 1216, height: 832 }, strategy: { name: 'TEXT_ONLY', stages: ['BASE'] } };
const LOADED = function (oid) { return { open: true, loading: false, plan: PLAN, artifact: { objectId: oid, revision: 1 }, qa: null, error: null }; };

function ctxFor(over) {
    return Object.assign({ kind: 'pb', bookObjectId: 'B1', section: 'review', item: null, select: vi.fn() }, over || {});
}

let origFetch;
beforeEach(() => {
    origFetch = global.fetch;
    __resetDirtyForTest();
    __setReviewStateForTest({
        bookObjectId: 'B1', queue: null, loading: false, error: null, filter: 'all', busyArtifact: null,
        nodePlans: {}, qaOpen: {}, selectedNode: null, reviewCounts: {}
    });
    vi.spyOn(am7client, 'get').mockImplementation((type, id, cb) => { cb({ objectId: id, groupPath: '/PictureBook/x', name: id + '.png' }); });
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
});
afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

describe('nodeBadges (derived from the queue payload only)', () => {
    it('unreviewed count, QA warning tone by worst verdict, stale-approved on a STALE node whose current image is APPROVED', () => {
        expect(nodeBadges(N1)).toEqual([
            { key: 'unreviewed', text: '2 unreviewed', tone: 'amber' },
            { key: 'qa', text: 'QA warning', tone: 'red' }
        ]);
        expect(nodeBadges(N2)).toEqual([
            { key: 'qa', text: 'QA warning', tone: 'amber' },
            { key: 'stale-approved', text: 'stale · approved', tone: 'purple' }
        ]);
        expect(nodeBadges(N3)).toEqual([]);
        // STALE but the current image is not approved → not "stale-approved"
        expect(nodeBadges({ nodeStatus: 'STALE', artifacts: [{ reviewStatus: 'REJECTED', selected: true }] })).toEqual([]);
        expect(nodeBadges(null)).toEqual([]);
    });
});

describe('toolbar(ctx)', () => {
    it('has no back arrow; title, counts, refresh and the filter keep their testids', () => {
        __setReviewStateForTest({ queue: QUEUE });
        let opts = reviewSection.toolbar(ctxFor());
        expect(opts.back).toBeUndefined();
        let el = renderToolbar(ctxFor());
        expect(el.querySelector('[data-testid="pb2-review-back"]')).toBeNull();
        expect(el.querySelector('h2').textContent).toBe('Review queue');
        expect(el.querySelector('[data-testid="pb2-review-counts"]').textContent).toBe('2 pending · 5 artifacts · 3 nodes');
        expect(el.querySelector('[data-testid="pb2-review-refresh"]')).not.toBeNull();
        let seg = el.querySelector('[data-testid="pb2-review-filter"]');
        expect(seg.getAttribute('role')).toBe('radiogroup');
        expect(seg.getAttribute('data-value')).toBe('all');
        expect(el.querySelector('[data-testid="pb2-review-filter-all"]').getAttribute('aria-checked')).toBe('true');
        expect(el.querySelector('[data-testid="pb2-review-filter-auto"]').getAttribute('data-active')).toBe('0');
    });

    it('the Auto-approve chip links to Edit › Book (PictureBook and ChapBook) through the dirty guard', async () => {
        __setReviewStateForTest({ queue: QUEUE });
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let el = renderToolbar(ctxFor());
        let chipEl = el.querySelector('[data-testid="pb2-review-auto-approve"]');
        expect(chipEl.textContent).toBe('Auto-approve: ON');
        expect(chipEl.getAttribute('data-on')).toBe('1');
        expect(chipEl.getAttribute('role')).toBe('link');
        expect(chipEl.getAttribute('data-href')).toBe('/picture-book/B1/edit/book');
        chipEl.click();
        await flushPromises();
        expect(set).toHaveBeenCalledWith('/picture-book/B1/edit/book');
        let cb = renderToolbar(ctxFor({ kind: 'cb', bookObjectId: 'C7' }));
        expect(cb.querySelector('[data-testid="pb2-review-auto-approve"]').getAttribute('data-href')).toBe('/chap-book/edit/C7/book');
    });

    it('the segmented filter switches the filter: one GET with filter=auto-approved, the active option is not re-fetched', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push(((o && o.method) || 'GET') + ' ' + restPath(u)); return jsonResponse(200, Object.assign({}, QUEUE, { filter: 'auto-approved', nodes: [N3] })); });
        __setReviewStateForTest({ queue: QUEUE });
        renderToolbar(ctxFor()).querySelector('[data-testid="pb2-review-filter-auto"]').click();
        await flushPromises();
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/review-queue?filter=auto-approved']);
        expect(__reviewStateForTest().filter).toBe('auto-approved');
        let el = renderToolbar(ctxFor());
        expect(el.querySelector('[data-testid="pb2-review-filter"]').getAttribute('data-value')).toBe('auto-approved');
        el.querySelector('[data-testid="pb2-review-filter-auto"]').click();
        await flushPromises();
        expect(calls.length).toBe(1);
    });
});

describe('body(ctx): master/detail', () => {
    it('list = one row per queue node with its badges; no :item selects the first; detail shows its artifacts newest-first and the plan expanded', () => {
        __setReviewStateForTest({ queue: QUEUE, nodePlans: { n1: LOADED('p1'), n2: LOADED('p2'), n3: LOADED('p3') } });
        let el = render(reviewSection.body(ctxFor()));
        let rows = [...el.querySelectorAll('[data-testid="pb2-review-row"]')];
        expect(rows.map(r => r.getAttribute('data-item-id'))).toEqual(['n1', 'n2', 'n3']);
        expect(rows.map(r => r.getAttribute('data-selected'))).toEqual(['1', '0', '0']);
        expect([...rows[0].querySelectorAll('[data-badge]')].map(b => b.getAttribute('data-badge') + ':' + b.textContent)).toEqual(['unreviewed:2 unreviewed', 'qa:QA warning']);
        expect([...rows[1].querySelectorAll('[data-badge]')].map(b => b.getAttribute('data-badge'))).toEqual(['qa', 'stale-approved']);
        expect(rows[2].querySelectorAll('[data-badge]').length).toBe(0);
        expect(rows[0].textContent).toContain('Scene 1 · scene-1');
        let detail = el.querySelector('[data-testid="pb2-review-detail"]');
        expect(detail.querySelector('[data-testid="pb2-review-node"]').getAttribute('data-node-id')).toBe('n1');
        expect([...detail.querySelectorAll('[data-testid="pb2-review-artifact"]')].map(a => a.getAttribute('data-artifact-id'))).toEqual(['a2', 'a1']);
        let plan = detail.querySelector('[data-testid="pb2-review-plan"]');
        expect(plan).not.toBeNull();
        expect(plan.className).not.toContain('max-h-96');
        expect(detail.querySelector('[data-testid="pb2-review-plan-toggle"]')).toBeNull();
        // only the selected node's detail is rendered
        expect(el.querySelectorAll('[data-testid="pb2-review-node"]').length).toBe(1);
    });

    it(':item selects that node; clicking a row asks the workspace to select it (route-driven, no local switch)', () => {
        __setReviewStateForTest({ queue: QUEUE, nodePlans: { n1: LOADED('p1'), n2: LOADED('p2'), n3: LOADED('p3') } });
        let ctx = ctxFor({ item: 'n2' });
        reviewSection.select(ctx);
        let el = render(reviewSection.body(ctx));
        expect(el.querySelector('[data-testid="pb2-review-row"][data-selected="1"]').getAttribute('data-item-id')).toBe('n2');
        expect(el.querySelector('[data-testid="pb2-review-node"]').getAttribute('data-node-id')).toBe('n2');
        expect(el.querySelector('[data-testid="pb2-review-node"]').textContent).toContain('pinned');
        el.querySelector('[data-testid="pb2-review-row"][data-item-id="n3"]').click();
        expect(ctx.select).toHaveBeenCalledWith('n3');
        expect(__reviewStateForTest().selectedNode).toBe('n2');
    });

    it('when the selected node leaves the queue the selection re-seats at the same index (ensureSelection)', () => {
        __setReviewStateForTest({ queue: QUEUE, nodePlans: { n1: LOADED('p1'), n2: LOADED('p2'), n3: LOADED('p3') }, selectedNode: 'n2' });
        render(renderReviewSectionBody(ctxFor({ item: 'n2' })));
        __setReviewStateForTest({ queue: Object.assign({}, QUEUE, { nodes: [N1, N3] }) });
        let el = render(renderReviewSectionBody(ctxFor({ item: 'n2' })));
        expect(el.querySelector('[data-testid="pb2-review-row"][data-selected="1"]').getAttribute('data-item-id')).toBe('n3');
    });

    it('the detail loads the selected node\'s plan without a toggle (nodeView → no renderPlan → the reason is shown)', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = restPath(u);
            calls.push(((o && o.method) || 'GET') + ' ' + url);
            if (/\/workflow\/node\/n1$/.test(url)) return jsonResponse(200, { objectId: 'n1', artifacts: { image: [] }, bindings: [] });
            return jsonResponse(404, { error: 'unexpected ' + url });
        });
        __setReviewStateForTest({ queue: QUEUE });
        let el = render(reviewSection.body(ctxFor()));
        expect(el.querySelector('[data-testid="pb2-review-plan-loading"]')).not.toBeNull();
        await flushPromises(); await flushPromises();
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/workflow/node/n1']);
        el = render(reviewSection.body(ctxFor()));
        expect(el.querySelector('[data-testid="pb2-review-plan-error"]').textContent).toBe('This node has no render-plan artifact');
        // rendering again does not refetch
        render(reviewSection.body(ctxFor()));
        await flushPromises();
        expect(calls.length).toBe(1);
    });

    it('buttons use the shared classes (components/buttons.js), not hand-written strings', () => {
        __setReviewStateForTest({ queue: QUEUE, nodePlans: { n1: LOADED('p1') } });
        let el = render(reviewSection.body(ctxFor()));
        let use = el.querySelectorAll('[data-testid="pb2-review-use"]');
        expect(use.length).toBe(2);
        use.forEach(b => expect(b.className).toBe('btn btn-sm'));
        el.querySelectorAll('[data-testid="pb2-review-approve"]').forEach(b => expect(b.className).toBe('btn btn-sm'));
        el.querySelectorAll('[data-testid="pb2-review-reject"]').forEach(b => expect(b.className).toBe('btn btn-danger btn-sm'));
        // a1 is GENERATED and not current → every action enabled; a2 is current → Use disabled
        let a1 = el.querySelector('[data-artifact-id="a1"]');
        expect(a1.querySelector('[data-testid="pb2-review-use"]').disabled).toBe(false);
        expect(el.querySelector('[data-artifact-id="a2"] [data-testid="pb2-review-use"]').disabled).toBe(true);
    });

    it('loading / error / empty use stateBlock and keep their testids', () => {
        __setReviewStateForTest({ queue: null, loading: true });
        let el = render(reviewSection.body(ctxFor()));
        expect(el.firstChild.getAttribute('data-state')).toBe('loading');
        expect(el.textContent).toBe('Loading review queue...');
        __setReviewStateForTest({ loading: false, error: '(409) Review queue requires the v3 picturebook render path' });
        el = render(reviewSection.body(ctxFor()));
        expect(el.querySelector('[data-testid="pb2-review-error"]').textContent).toBe('(409) Review queue requires the v3 picturebook render path');
        __setReviewStateForTest({ error: null, queue: { nodes: [] }, filter: 'auto-approved' });
        el = render(reviewSection.body(ctxFor()));
        expect(el.querySelector('[data-testid="pb2-review-empty"]').textContent).toBe('No auto-approved artifacts.');
        expect(el.querySelector('[data-testid="pb2-review-md"]')).toBeNull();
    });
});

describe('init(ctx) and status(ctx)', () => {
    // Intentionally changed (§4.3 / D6): the rail counts pending items, not queue nodes (QUEUE has 3 nodes, 2 pending).
    it('init loads the queue for any book id (a ChapBook here) and resets per-book state; status is null until loaded, then the pending count', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push(((o && o.method) || 'GET') + ' ' + restPath(u)); return jsonResponse(200, Object.assign({}, QUEUE, { bookObjectId: 'CB9' })); });
        __setReviewStateForTest({ filter: 'auto-approved', nodePlans: { n1: LOADED('p1') } });
        let ctx = ctxFor({ kind: 'cb', bookObjectId: 'CB9', item: 'n3' });
        expect(reviewSection.status(ctx)).toBeNull();
        reviewSection.init(ctx);
        await flushPromises();
        expect(calls).toEqual(['GET /rest/olio/picture-book/CB9/review-queue?filter=all']);
        let st = __reviewStateForTest();
        expect(st.bookObjectId).toBe('CB9');
        expect(st.filter).toBe('all');
        expect(st.nodePlans).toEqual({});
        expect(st.selectedNode).toBe('n3');
        expect(reviewSection.status(ctx)).toEqual({ kind: 'count', count: 2, title: '2 waiting for review · 3 nodes in the queue' });
    });

    it('loadReviewCount fills the rail without opening the section, caches per book, and shares an in-flight request', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u) => { calls.push(restPath(u)); return jsonResponse(200, Object.assign({}, QUEUE, { nodes: [N1, N2], pendingCount: 1 })); });
        let [x, y] = await Promise.all([loadReviewCount('B7'), loadReviewCount('B7')]);
        expect(x).toBe(2);
        expect(y).toBe(2);
        expect(calls).toEqual(['/rest/olio/picture-book/B7/review-queue?filter=all']);
        expect(await loadReviewCount('B7')).toBe(2);
        expect(calls.length).toBe(1);
        // loadReviewCount resolves to the node count; the rail status shows the pending count
        expect(reviewSection.status(ctxFor({ bookObjectId: 'B7' }))).toMatchObject({ kind: 'count', count: 1 });
        expect(await loadReviewCount('B7', { refresh: true })).toBe(2);
        expect(calls.length).toBe(2);
        // another book is a separate entry; an unreadable queue (v1 409) is null, never a throw
        global.fetch = vi.fn(async () => jsonResponse(409, { error: 'v1' }));
        expect(await loadReviewCount('B8')).toBeNull();
        expect(reviewSection.status(ctxFor({ bookObjectId: 'B8' }))).toBeNull();
    });

    it('the count is refreshed after Approve — under the Auto-approved filter with a separate filter=all read', async () => {
        let calls = [];
        let afterApprove = false;
        global.fetch = vi.fn(async (u, o) => {
            let url = restPath(u);
            let method = (o && o.method) || 'GET';
            calls.push(method + ' ' + url);
            if (method === 'PUT') { afterApprove = true; return jsonResponse(200, {}); }
            // the server's pendingCount drops by one with the approval
            if (/filter=all$/.test(url)) return jsonResponse(200, Object.assign({}, QUEUE, afterApprove ? { nodes: [N1, N2, N3], pendingCount: 1 } : { nodes: [N1, N2, N3], pendingCount: 2 }));
            return jsonResponse(200, Object.assign({}, QUEUE, { filter: 'auto-approved', nodes: [N3], pendingCount: 0 }));
        });
        await loadReviewCount('B1');
        expect(reviewSection.status(ctxFor()).count).toBe(2);
        __setReviewStateForTest({ filter: 'auto-approved' });
        await loadQueue();
        // an auto-approved load (its own pendingCount 0, nodes [N3]) never overwrites the rail count
        expect(reviewSection.status(ctxFor()).count).toBe(2);
        __setReviewStateForTest({ nodePlans: { n3: LOADED('p3') } });
        let el = render(reviewSection.body(ctxFor()));
        el.querySelector('[data-artifact-id="c1"] [data-testid="pb2-review-approve"]').click();
        for (let i = 0; i < 6; i++) await flushPromises();
        expect(calls.slice(-3)).toEqual([
            'PUT /rest/olio/picture-book/B1/artifact/c1/approve',
            'GET /rest/olio/picture-book/B1/review-queue?filter=auto-approved',
            'GET /rest/olio/picture-book/B1/review-queue?filter=all'
        ]);
        expect(reviewSection.status(ctxFor()).count).toBe(1);
        expect(reviewSection.status(ctxFor()).title).toBe('1 waiting for review · 3 nodes in the queue');
        expect(page.toast).toHaveBeenCalledWith('success', 'Approved');
    });
});
