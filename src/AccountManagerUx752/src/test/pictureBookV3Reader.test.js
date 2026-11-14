// @vitest-environment jsdom
/**
 * PictureBook V3 reader (P1-10 S2 + S3) — the PB2 reader's per-scene status chip, async render-run
 * progress and resume/re-attach flows.
 *
 * Why: V3 renders are 202 + background job, and the reader must (a) offer the V3 controls whenever a
 * book is loaded (there is no other path since P10-1), (b) derive the scene chip from the bookPageView fields the server now emits,
 * (c) drive the stage list from job polling, (d) re-attach from SERVER state only (workflow
 * lastRunObjectId → run → jobId | INTERRUPTED+resumable), and (e) never silently unpin: a pinned 409 is
 * shown verbatim and only an explicit "Unpin and re-render" confirmation sends pin {pinned:false}.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as wf from '../workflows/pictureBookWorkflow.js';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

let origFetch;
let pbf;

beforeEach(async () => {
    origFetch = global.fetch;
    pbf = await import('../features/pictureBook.js');
    pbf.__pb2StopWatchingForTest();
    pbf.__setPb2V3StateForTest({
        pb2AutoApprove: false, pb2AutoApproveSaving: false, pb2Run: null, pb2ResumeRun: null,
        pb2Submitting: false, pb2CurrentPage: 1, pb2BookObjectId: 'B1', pb2BookName: 'Harlot\'s Eight', pb2PollIntervalMs: 5,
        pb2Pages: [{ objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', pinned: false, dataObjectId: 'd1' }]
    });
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
});

afterEach(() => {
    pbf.__pb2StopWatchingForTest();
    pbf.__setPb2V3StateForTest({ pb2Run: null, pb2ResumeRun: null });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── pure helpers ───────────────────────────────

describe('sceneChip — bookPageView fields → chip', () => {
    it('STALE node → stale; imageStale flag too', () => {
        expect(pbf.sceneChip({ nodeStatus: 'STALE', reviewStatus: 'APPROVED' })).toBe('stale');
        expect(pbf.sceneChip({ nodeStatus: 'stale' })).toBe('stale');
        expect(pbf.sceneChip({ nodeStatus: 'DONE', imageStale: true })).toBe('stale');
    });
    it('APPROVED → approved; selected artifact (GENERATED/REJECTED) → review; no artifact → null', () => {
        expect(pbf.sceneChip({ nodeStatus: 'DONE', reviewStatus: 'APPROVED' })).toBe('approved');
        expect(pbf.sceneChip({ nodeStatus: 'DONE', reviewStatus: 'approved' })).toBe('approved');
        expect(pbf.sceneChip({ nodeStatus: 'DONE_UNVERIFIED', reviewStatus: 'GENERATED', artifactObjectId: 'a1' })).toBe('review');
        expect(pbf.sceneChip({ nodeStatus: 'DONE', reviewStatus: 'REJECTED', artifactObjectId: 'a1' })).toBe('review');
        // Legacy page image with no artifact: nothing is in the review queue, so no chip.
        expect(pbf.sceneChip({ nodeStatus: 'DONE', dataObjectId: 'd1' })).toBeNull();
        // Wire: sceneNodeObjectId/nodeStatus/reviewStatus/artifactObjectId absent (not null) without a node; pinned always boolean.
        expect(pbf.sceneChip({ pinned: false, dataObjectId: 'd1' })).toBeNull();
        expect(pbf.sceneChip({ nodeStatus: 'READY' })).toBeNull();
        expect(pbf.sceneChip(null)).toBeNull();
    });
});

describe('deriveStageList — planned[] × job payload', () => {
    const planned = ['scene-1', 'scene-2', 'scene-3'];

    it('no job yet: every planned handle is pending', () => {
        expect(wf.deriveStageList(planned, null).map(s => s.state)).toEqual(['pending', 'pending', 'pending']);
    });
    it('in flight: phase = running, i < current = done, rest pending', () => {
        let states = wf.deriveStageList(planned, { status: 'running', phase: 'scene-2', current: 1, total: 3, terminal: false }).map(s => s.state);
        expect(states).toEqual(['done', 'running', 'pending']);
    });
    it('terminal: result.nodes outcomes win (DONE/FAILED/SKIPPED_PINNED), unlisted handles are unrun', () => {
        let job = { status: 'done', terminal: true, current: 3, total: 3, result: { runStatus: 'DONE', nodes: [
            { handle: 'scene-1', status: 'DONE', artifactObjectId: 'a1', autoApproved: true, reviewStatus: 'APPROVED' },
            { handle: 'scene-2', status: 'FAILED', error: 'SD timeout' },
            { handle: 'scene-3', status: 'SKIPPED_PINNED' }
        ] } };
        let stages = wf.deriveStageList(planned.concat(['scene-4']), job);
        expect(stages.map(s => s.state)).toEqual(['done', 'failed', 'skipped', 'unrun']);
        expect(stages[0]).toMatchObject({ artifactObjectId: 'a1', autoApproved: true, reviewStatus: 'APPROVED' });
        expect(stages[1].error).toBe('SD timeout');
    });
    it('tolerates planned entries as objects and lowercase statuses', () => {
        let stages = wf.deriveStageList([{ handle: 'h1' }, { nodeObjectId: 'n2' }, null, ''],
            { terminal: true, result: { nodes: [{ handle: 'h1', status: 'done' }] } });
        expect(stages.map(s => s.handle)).toEqual(['h1', 'n2']);
        expect(stages.map(s => s.state)).toEqual(['done', 'unrun']);
    });
    it('non-array planned → empty list', () => {
        expect(wf.deriveStageList(null, null)).toEqual([]);
        expect(wf.deriveStageList('scene-1', null)).toEqual([]);
    });
});

describe('resumeBannerState — only INTERRUPTED + resumable', () => {
    it('shows for a resumable INTERRUPTED run with counts', () => {
        let s = pbf.resumeBannerState({ runObjectId: 'R1', name: 'run-7', runStatus: 'INTERRUPTED', resumable: true,
            requestedNodeIds: [1, 2, 3, 4], executedNodeCount: 2, failedNodeCount: 1, error: 'process restarted' });
        expect(s).toMatchObject({ show: true, runObjectId: 'R1', name: 'run-7', executed: 2, failed: 1, requested: 4, error: 'process restarted' });
    });
    it('INTERRUPTED but not resumable → hidden with reason', () => {
        expect(pbf.resumeBannerState({ runStatus: 'interrupted', resumable: false })).toEqual({ show: false, reason: 'not-resumable' });
    });
    it('DONE / CANCELLED / RUNNING / null → hidden', () => {
        ['DONE', 'CANCELLED', 'RUNNING', 'FAILED'].forEach(st => {
            expect(pbf.resumeBannerState({ runStatus: st, resumable: true }).show).toBe(false);
        });
        expect(pbf.resumeBannerState(null).show).toBe(false);
    });
});

describe('runSummaryText', () => {
    it('summarizes the result counts and run status', () => {
        expect(wf.runSummaryText({ status: 'done', terminal: true, result: { runStatus: 'DONE', executedNodeCount: 3, failedNodeCount: 0, skippedNodeCount: 1 } }))
            .toBe('Render complete: 3 rendered, 1 skipped');
        expect(wf.runSummaryText({ terminal: true, result: { runStatus: 'INTERRUPTED', executedNodeCount: 1, failedNodeCount: 1, error: 'boom' } }))
            .toBe('Render interrupted: 1 rendered, 1 failed — boom');
        expect(wf.runSummaryText({ terminal: true, result: { runStatus: 'cancelled', executedNodeCount: 2 } })).toBe('Render cancelled: 2 rendered');
    });
    it('job-level error without result, cancelled without result, empty', () => {
        expect(wf.runSummaryText({ terminal: true, error: 'worker died' })).toBe('Render failed: worker died');
        expect(wf.runSummaryText({ terminal: true, cancelled: true })).toBe('Render cancelled');
        expect(wf.runSummaryText(null)).toBe('');
    });
});

// ─────────────────────────────── view gating ───────────────────────────────

describe('renderPb2Header — V3 controls', () => {
    it('badge reflects auto-approve state; Render-all and Review link present', () => {
        pbf.__setPb2V3StateForTest({ pb2AutoApprove: true });
        let text = JSON.stringify(pbf.renderPb2Header());
        expect(text).toContain('"data-testid":"pb2-auto-approve-badge"');
        expect(text).toContain('"data-on":"1"');
        expect(text).toContain('Auto-approve: ON');
        expect(text).toContain('"data-testid":"pb2-render-all"');
        expect(text).toContain('"data-testid":"pb2-review-link"');
        pbf.__setPb2V3StateForTest({ pb2AutoApprove: false });
        expect(JSON.stringify(pbf.renderPb2Header())).toContain('"data-on":"0"');
    });
    it('Render-all is disabled while a run is being watched', () => {
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1'], job: { terminal: false }, label: 'Render all', error: null } });
        let text = JSON.stringify(pbf.renderPb2Header());
        expect(text).toMatch(/"data-testid":"pb2-render-all","class":null,"disabled":true/);
    });
});

describe('renderPb2ScenePage — chip, stale overlay, per-scene Render button', () => {
    it('approved + pinned page: approved chip with pinned suffix, Re-render button, no badge', () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', pinned: true, dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED' }] });
        let text = JSON.stringify(pbf.renderPb2ScenePage());
        expect(text).toContain('"data-chip":"approved"');
        expect(text).toContain('Approved · pinned');
        expect(text).toContain('"data-testid":"pb2-scene-render"');
        expect(text).toContain('Re-render');
        expect(text).not.toContain('pb2-stale-badge');
    });
    it('never-rendered page: no image, "Needs render" badge only when STALE, button says Render', () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'READY' }] });
        let text = JSON.stringify(pbf.renderPb2ScenePage());
        expect(text).not.toContain('pb2-scene-image');
        expect(text).not.toContain('pb2-stale-badge');
        expect(text).not.toContain('pb2-scene-chip');
        expect(text).toMatch(/"data-testid":"pb2-scene-render".*"Render"/);
        pbf.__setPb2V3StateForTest({ pb2Pages: [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'STALE' }] });
        expect(JSON.stringify(pbf.renderPb2ScenePage())).toContain('Needs render');
    });
});

describe('renderPb2RunProgress / renderPb2ResumeBanner', () => {
    it('progress panel lists stages with data-stage-state and a cancel while in flight', () => {
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1', 'scene-2'], label: 'Render all', error: null,
            job: { status: 'running', phase: 'scene-2', current: 1, total: 2, terminal: false } } });
        let text = JSON.stringify(pbf.renderPb2RunProgress());
        expect(text).toContain('"data-run-terminal":"0"');
        expect(text).toContain('"data-testid":"pb2-run-cancel"');
        expect(text).not.toContain('pb2-run-dismiss');
        expect(text).toContain('"data-stage-state":"done"');
        expect(text).toContain('"data-stage-state":"running"');
        expect(text).toContain('1 of 2 · scene-2');
    });
    it('terminal: summary text, dismiss button, no cancel', () => {
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1'], label: 'Render all', error: null,
            job: { status: 'done', terminal: true, current: 1, total: 1, result: { runStatus: 'DONE', executedNodeCount: 1, nodes: [{ handle: 'scene-1', status: 'DONE' }] } } } });
        let text = JSON.stringify(pbf.renderPb2RunProgress());
        expect(text).toContain('"data-run-terminal":"1"');
        expect(text).toContain('"data-testid":"pb2-run-dismiss"');
        expect(text).not.toContain('pb2-run-cancel');
        expect(text).toContain('Render complete: 1 rendered');
    });
    it('no run → null; resume banner only for a resumable INTERRUPTED run and not while watching', () => {
        pbf.__setPb2V3StateForTest({ pb2Run: null, pb2ResumeRun: null });
        expect(pbf.renderPb2RunProgress()).toBeNull();
        expect(pbf.renderPb2ResumeBanner()).toBeNull();
        pbf.__setPb2V3StateForTest({ pb2ResumeRun: { runObjectId: 'R1', name: 'run-3', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1, 2], executedNodeCount: 1 } });
        let text = JSON.stringify(pbf.renderPb2ResumeBanner());
        expect(text).toContain('"data-testid":"pb2-resume-banner"');
        expect(text).toContain('"data-testid":"pb2-resume-run"');
        expect(text).toContain('(run-3) was interrupted after 1 of 2 nodes');
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J2', planned: [], job: { terminal: false }, error: null } });
        expect(pbf.renderPb2ResumeBanner()).toBeNull();
    });
});

// ─────────────────────────────── flows against mocked REST ───────────────────────────────

function flushPromises() { return new Promise(r => setTimeout(r, 0)); }

const RUNNING = { jobId: 'J1', kind: 'pb.render', status: 'running', phase: 'scene-1', current: 0, total: 1, terminal: false };
const DONE = { jobId: 'J1', kind: 'pb.render', status: 'done', current: 1, total: 1, terminal: true,
    result: { runObjectId: 'R1', runStatus: 'DONE', executedNodeCount: 1, failedNodeCount: 0, skippedNodeCount: 0, plannedNodeCount: 1,
        nodes: [{ nodeObjectId: 'n1', handle: 'scene-1', status: 'DONE', artifactObjectId: 'a1' }] } };

function mockRenderStack(opts) {
    let o = opts || {};
    let calls = [];
    let jobPolls = 0;
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u);
        let method = (init && init.method) || 'GET';
        calls.push({ url, method, body: init && init.body });
        if (/\/rest\/job\/J1$/.test(url)) {
            jobPolls++;
            return jsonResponse(200, jobPolls === 1 ? RUNNING : DONE);
        }
        if (/\/B1\/scene\/s1\/render(\?force=true)?$/.test(url)) {
            if (o.sceneRender) return o.sceneRender(calls);
            return jsonResponse(202, { jobId: 'J1', jobStatus: 'pending', kind: 'pb.render', runObjectId: 'R1', runStatus: 'RUNNING', plannedNodeCount: 1, planned: ['scene-1'], reusedNodeCount: 0, pinnedNodeCount: 0 });
        }
        if (/\/B1\/render-all$/.test(url)) {
            if (o.renderAll) return o.renderAll(calls);
            return jsonResponse(202, { jobId: 'J1', jobStatus: 'pending', runObjectId: 'R1', runStatus: 'RUNNING', plannedNodeCount: 1, planned: ['scene-1'], sceneCount: 2, scenesWithoutNode: ['s9'] });
        }
        if (/\/B1\/node\/n1\/pin$/.test(url)) return jsonResponse(200, { nodeObjectId: 'n1', pinned: false });
        if (/\/B1\/run\/R1\/resume$/.test(url)) {
            if (o.resume) return o.resume(calls);
            return jsonResponse(202, { jobId: 'J1', runObjectId: 'R1', runStatus: 'RUNNING', planned: ['scene-1'] });
        }
        if (/\/B1\/run\/R1$/.test(url)) return jsonResponse(200, o.run || { runObjectId: 'R1', runStatus: 'DONE', nodes: [] });
        if (/\/B1\/pages$/.test(url)) return jsonResponse(200, [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', reviewStatus: 'GENERATED', artifactObjectId: 'a1', dataObjectId: 'd1' }]);
        if (/\/B1\/health$/.test(url)) return jsonResponse(200, { scope: 'book', findings: [], summary: { errors: 0, warnings: 0, infos: 0, healable: 0 } });
        if (/\/B1\/settings\/auto-approve$/.test(url)) {
            let body = init && init.body ? JSON.parse(init.body) : {};
            return jsonResponse(200, { bookObjectId: 'B1', autoApprove: !!body.autoApprove });
        }
        return jsonResponse(404, { error: 'unexpected ' + method + ' ' + url });
    });
    return calls;
}

async function waitForTerminal(maxMs) {
    let until = Date.now() + (maxMs || 3000);
    for (;;) {
        let st = pbf.__pb2V3StateForTest();
        if (st.pb2Run && st.pb2Run.job && st.pb2Run.job.terminal) return st;
        if (Date.now() > until) throw new Error('run did not reach terminal: ' + JSON.stringify(st.pb2Run));
        await flushPromises();
    }
}

describe('per-scene render → 202 → job polling → terminal refresh', () => {
    it('POSTs /scene/{id}/render?force=true (an explicit per-scene render re-rolls a current node), polls /rest/job/{jobId}, then clears scene cache and reloads pages', async () => {
        pbf.__setPb2V3StateForTest({});
        let calls = mockRenderStack();
        await pbf.__pb2SubmitRenderSceneForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        let st = await waitForTerminal();
        expect(st.pb2Run.jobId).toBe('J1');
        expect(st.pb2Run.planned).toEqual(['scene-1']);
        expect(st.pb2Run.job.result.runStatus).toBe('DONE');
        // let pb2AfterTerminal finish (toast + refresh)
        await flushPromises(); await flushPromises(); await flushPromises();
        let seq = calls.map(c => c.method + ' ' + c.url.replace(/^.*\/rest\//, '/rest/'));
        expect(seq[0]).toBe('POST /rest/olio/picture-book/B1/scene/s1/render?force=true');
        expect(calls[0].body).toBeUndefined();   // force travels as the query flag, never a body
        expect(seq.filter(s => s === 'GET /rest/job/J1').length).toBe(2);
        expect(seq).toContain('GET /rest/olio/picture-book/B1/pages');
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.pb.scene', true);
        expect(page.toast).toHaveBeenCalledWith('success', 'Render complete: 1 rendered');
        expect(pbf.__pb2V3StateForTest().pb2Pages[0].artifactObjectId).toBe('a1');
    });

    it('a 429 / in-flight 409 is toasted verbatim and never unpins', async () => {
        pbf.__setPb2V3StateForTest({});
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let calls = mockRenderStack({ sceneRender: () => jsonResponse(429, { error: 'Principal already has 2 of 2 render runs in flight' }) });
        await pbf.__pb2SubmitRenderSceneForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(page.toast).toHaveBeenCalledWith('error', '(429) Principal already has 2 of 2 render runs in flight');
        expect(confirm).not.toHaveBeenCalled();
        expect(calls.some(c => /\/pin$/.test(c.url))).toBe(false);
        expect(pbf.__pb2V3StateForTest().pb2Run).toBeNull();

        calls.length = 0;
        mockRenderStack({ sceneRender: () => jsonResponse(409, { error: 'Node scene-1 is being rendered by run R0' }) });
        await pbf.__pb2SubmitRenderSceneForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Node scene-1 is being rendered by run R0');
        expect(confirm).not.toHaveBeenCalled();
    });

    it('pinned 409: body shown in the confirmation; declining sends nothing more', async () => {
        pbf.__setPb2V3StateForTest({});
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        let calls = mockRenderStack({ sceneRender: () => jsonResponse(409, { error: 'Nothing to render: every node of scene 1 is current (0 reused, 1 pinned)' }) });
        await pbf.__pb2SubmitRenderSceneForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(confirm).toHaveBeenCalledTimes(1);
        let cfg = confirm.mock.calls[0][0];
        expect(cfg.confirmLabel).toBe('Unpin and re-render');
        expect(cfg.message).toContain('(409) Nothing to render: every node of scene 1 is current (0 reused, 1 pinned)');
        expect(calls.length).toBe(1);   // only the refused render
        expect(page.toast).not.toHaveBeenCalledWith('error', expect.anything());
        expect(pbf.__pb2V3StateForTest().pb2Submitting).toBe(false);
    });

    it('pinned 409: accepting sends pin {pinned:false} to the scene node, then re-submits WITH force and watches', async () => {
        pbf.__setPb2V3StateForTest({});
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let attempt = 0;
        // The planner's view: a pinned terminal is a 409 (pinned count) whatever the flag; once unpinned the node is
        // current, so only a forced submit is a 202 — an unforced one is the "(1 reused, 0 pinned)" refusal the
        // verifier reproduced live (§6.C 109). Encoded here so the mock cannot pass an unforced second submit.
        let calls = mockRenderStack({ sceneRender: (c) => {
            attempt++;
            let forced = /\?force=true$/.test(c[c.length - 1].url);
            if (attempt === 1) return jsonResponse(409, { error: 'Nothing to render: every node of scene 1 is current (0 reused, 1 pinned)' });
            if (!forced) return jsonResponse(409, { error: 'Nothing to render: every node of scene 1 is current (1 reused, 0 pinned)' });
            return jsonResponse(202, { jobId: 'J1', runObjectId: 'R1', runStatus: 'RUNNING', planned: ['scene-1'] });
        } });
        await pbf.__pb2SubmitRenderSceneForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        let st = await waitForTerminal();
        expect(st.pb2Run.jobId).toBe('J1');
        let seq = calls.map(c => c.method + ' ' + c.url.replace(/^.*\/rest\//, '/rest/'));
        expect(seq.slice(0, 3)).toEqual([
            'POST /rest/olio/picture-book/B1/scene/s1/render?force=true',
            'POST /rest/olio/picture-book/B1/node/n1/pin',
            'POST /rest/olio/picture-book/B1/scene/s1/render?force=true'
        ]);
        expect(JSON.parse(calls[1].body)).toEqual({ pinned: false });
        expect(page.toast).not.toHaveBeenCalledWith('error', expect.anything());
    });

    it('pinned 409 on a page with no sceneNodeObjectId: toasted, no confirmation (nothing to unpin)', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE' }] });
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        mockRenderStack({ sceneRender: () => jsonResponse(409, { error: 'Nothing to render: every node of scene 1 is current (0 reused, 1 pinned)' }) });
        await pbf.__pb2SubmitRenderSceneForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(confirm).not.toHaveBeenCalled();
        expect(page.toast).toHaveBeenCalledWith('error', expect.stringContaining('(409) Nothing to render'));
    });
});

describe('render-all dialog → 202 → watch', () => {
    it('warns about scenesWithoutNode, closes the dialog and starts watching the job', async () => {
        pbf.__setPb2V3StateForTest({});
        let close = vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        let calls = mockRenderStack();
        await pbf.__pb2SubmitRenderAllForTest();
        expect(calls[0].method + ' ' + calls[0].url.replace(/^.*\/rest\//, '/rest/')).toBe('POST /rest/olio/picture-book/B1/render-all');
        expect(page.toast).toHaveBeenCalledWith('warning', '1 scene has no graph node yet and will not render');
        expect(close).toHaveBeenCalled();
        let st = await waitForTerminal();
        expect(st.pb2Run.label).toBe('Render all');
    });
    it('a 409 "nothing to render" from render-all is toasted verbatim (no unpin offer for the whole book)', async () => {
        pbf.__setPb2V3StateForTest({});
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        mockRenderStack({ renderAll: () => jsonResponse(409, { error: 'Nothing to render: every node of book B1 is current (3 reused, 2 pinned)' }) });
        await pbf.__pb2SubmitRenderAllForTest();
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Nothing to render: every node of book B1 is current (3 reused, 2 pinned)');
        expect(confirm).not.toHaveBeenCalled();
        expect(pbf.__pb2V3StateForTest().pb2Run).toBeNull();
    });
});

describe('re-attach from server state (no localStorage)', () => {
    it('no lastRunObjectId → nothing', async () => {
        let calls = mockRenderStack();
        await pbf.__pb2ReattachRunForTest('B1', { noWorkflow: false });
        expect(calls.length).toBe(0);
        expect(pbf.__pb2V3StateForTest().pb2Run).toBeNull();
        expect(pbf.__pb2V3StateForTest().pb2ResumeRun).toBeNull();
    });
    it('run with a jobId (in flight in this process) → resume polling with planned from run.nodes', async () => {
        let calls = mockRenderStack({ run: { runObjectId: 'R1', runStatus: 'RUNNING', jobId: 'J1', inFlightInThisProcess: true, resumable: false,
            nodes: [{ nodeObjectId: 'n1', handle: 'scene-1', nodeStatus: 'RUNNING' }] } });
        await pbf.__pb2ReattachRunForTest('B1', { lastRunObjectId: 'R1' });
        expect(calls[0].url).toMatch(/\/B1\/run\/R1$/);
        let st = await waitForTerminal();
        expect(st.pb2Run.jobId).toBe('J1');
        expect(st.pb2Run.planned).toEqual(['scene-1']);
        expect(st.pb2Run.label).toBe('Render (resumed watching)');
    });
    it('INTERRUPTED + resumable → resume banner; DONE → nothing', async () => {
        mockRenderStack({ run: { runObjectId: 'R1', name: 'run-2', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1, 2], executedNodeCount: 1, nodes: [] } });
        await pbf.__pb2ReattachRunForTest('B1', { lastRunObjectId: 'R1' });
        expect(pbf.__pb2V3StateForTest().pb2ResumeRun).toMatchObject({ runObjectId: 'R1', runStatus: 'INTERRUPTED' });
        expect(pbf.__pb2V3StateForTest().pb2Run).toBeNull();

        mockRenderStack({ run: { runObjectId: 'R1', runStatus: 'DONE', resumable: false, nodes: [] } });
        await pbf.__pb2ReattachRunForTest('B1', { lastRunObjectId: 'R1' });
        expect(pbf.__pb2V3StateForTest().pb2ResumeRun).toBeNull();
        expect(pbf.__pb2V3StateForTest().pb2Run).toBeNull();
    });
    it('a run GET failure (e.g. 404) attaches nothing and does not toast', async () => {
        global.fetch = vi.fn(async () => jsonResponse(404, { error: 'Run not found' }));
        await pbf.__pb2ReattachRunForTest('B1', { lastRunObjectId: 'gone' });
        expect(pbf.__pb2V3StateForTest().pb2Run).toBeNull();
        expect(pbf.__pb2V3StateForTest().pb2ResumeRun).toBeNull();
        expect(page.toast).not.toHaveBeenCalled();
    });
});

describe('resume an INTERRUPTED run', () => {
    it('POST /run/{id}/resume → 202 → watching; banner cleared', async () => {
        pbf.__setPb2V3StateForTest({ pb2ResumeRun: { runObjectId: 'R1', name: 'run-2', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1], nodes: [] } });
        let calls = mockRenderStack();
        await pbf.__pb2ResumeInterruptedForTest();
        expect(calls[0].method + ' ' + calls[0].url.replace(/^.*\/rest\//, '/rest/')).toBe('POST /rest/olio/picture-book/B1/run/R1/resume');
        expect(pbf.__pb2V3StateForTest().pb2ResumeRun).toBeNull();
        let st = await waitForTerminal();
        expect(st.pb2Run.label).toBe('Resume run-2');
    });
    it('409 (run no longer INTERRUPTED) → toasted verbatim and the banner is dropped; other errors keep it', async () => {
        pbf.__setPb2V3StateForTest({ pb2ResumeRun: { runObjectId: 'R1', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1], nodes: [] } });
        mockRenderStack({ resume: () => jsonResponse(409, { error: 'Run R1 is DONE; only an INTERRUPTED run can be resumed' }) });
        await pbf.__pb2ResumeInterruptedForTest();
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Run R1 is DONE; only an INTERRUPTED run can be resumed');
        expect(pbf.__pb2V3StateForTest().pb2ResumeRun).toBeNull();

        pbf.__setPb2V3StateForTest({ pb2ResumeRun: { runObjectId: 'R1', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1], nodes: [] } });
        mockRenderStack({ resume: () => jsonResponse(429, { error: 'Principal already has 2 of 2 render runs in flight' }) });
        await pbf.__pb2ResumeInterruptedForTest();
        expect(pbf.__pb2V3StateForTest().pb2ResumeRun).not.toBeNull();
    });
});

describe('auto-approve toggle', () => {
    it('PUT /settings/auto-approve {autoApprove:true} and mirrors the server answer', async () => {
        pbf.__setPb2V3StateForTest({ pb2AutoApprove: false });
        let calls = mockRenderStack();
        await pbf.__pb2ToggleAutoApproveForTest(true);
        expect(calls[0].method).toBe('PUT');
        expect(calls[0].url).toMatch(/\/B1\/settings\/auto-approve$/);
        expect(JSON.parse(calls[0].body)).toEqual({ autoApprove: true });
        expect(pbf.__pb2V3StateForTest().pb2AutoApprove).toBe(true);
        expect(pbf.__pb2V3StateForTest().pb2AutoApproveSaving).toBe(false);
    });
    it('a 400 keeps the previous state and toasts the server message', async () => {
        pbf.__setPb2V3StateForTest({ pb2AutoApprove: false });
        global.fetch = vi.fn(async () => jsonResponse(400, { error: true, message: 'autoApprove must be a boolean' }));
        await pbf.__pb2ToggleAutoApproveForTest(true);
        expect(pbf.__pb2V3StateForTest().pb2AutoApprove).toBe(false);
        expect(page.toast).toHaveBeenCalledWith('error', '(400) autoApprove must be a boolean');
    });
});

// ─────────────────────────────── UAT-2: fullscreen + HTML export ───────────────────────────────
// The V3 reader keeps its own header, so it mirrors ReaderShell's fullscreen/export contract (same icons,
// titles, Escape) and reuses the shell's export ENGINE (exportBook). Pinned here: the buttons, their states,
// the exported page shape, the z-30 overlay (below dialogs z-40+ and toasts z-50) and the Escape exit.

describe('UAT-2 pb2ExportPageHtml — exported scene section', () => {
    it('uses the on-screen text precedence (poemStanza > blurb > summary) and escapes everything', () => {
        let html = pbf.pb2ExportPageHtml({ title: 'A <b>Title</b>', poemStanza: 'stanza & co', blurb: 'blurb', summary: 'sum',
            characters: ['Ann', { name: 'Bob <x>' }, null] }, 0, 'data:image/png;base64,AAA', 3);
        expect(html).toContain('<h2>A &lt;b&gt;Title&lt;/b&gt;</h2>');
        expect(html).toContain('<p class="blurb">stanza &amp; co</p>');
        expect(html).toContain('<div class="characters">Ann, Bob &lt;x&gt;</div>');
        expect(html).toContain('<img src="data:image/png;base64,AAA" alt="A &lt;b&gt;Title&lt;/b&gt;" />');
        expect(html).toContain('<div class="page-num">Page 1 of 3</div>');
        expect(pbf.pb2ExportPageHtml({ blurb: 'b', summary: 's' }, 1, null, 2)).toContain('<p class="blurb">b</p>');
        expect(pbf.pb2ExportPageHtml({ summary: 's' }, 1, null, 2)).toContain('<p class="blurb">s</p>');
    });
    it('falls back to "Scene N", omits the image and character block when absent', () => {
        let html = pbf.pb2ExportPageHtml({}, 4, null, 9);
        expect(html).toContain('<h2>Scene 5</h2>');
        expect(html).not.toContain('<img');
        expect(html).not.toContain('class="characters"');
        expect(html).toContain('Page 5 of 9');
    });
});

describe('UAT-2 header — export and fullscreen buttons', () => {
    function findButton(testid) {
        let header = pbf.renderPb2Header();
        return (header.children || []).find(c => c && c.attrs && c.attrs['data-testid'] === testid) || null;
    }
    it('Export: "Export as HTML", download icon, enabled only with pages, hourglass + disabled while exporting', () => {
        let b = findButton('pb2-export');
        expect(b).not.toBeNull();
        expect(b.attrs.title).toBe('Export as HTML');
        expect(b.attrs.disabled).toBe(false);
        expect(JSON.stringify(b)).toContain('"download"');

        pbf.__setPb2V3StateForTest({ pb2Pages: [] });
        expect(findButton('pb2-export').attrs.disabled).toBe(true);

        pbf.__setPb2V3StateForTest({ pb2Pages: [{ objectId: 's1', sceneIndex: 1, title: 'Arrival' }], pb2Exporting: true });
        b = findButton('pb2-export');
        expect(b.attrs.disabled).toBe(true);
        expect(JSON.stringify(b)).toContain('"hourglass_empty"');
        pbf.__setPb2V3StateForTest({ pb2Exporting: false });
    });
    it('Fullscreen: toggles title/icon (Fullscreen/fullscreen <-> Exit fullscreen/fullscreen_exit); Back hidden in fullscreen', () => {
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: false });
        let b = findButton('pb2-fullscreen');
        expect(b.attrs.title).toBe('Fullscreen');
        expect(JSON.stringify(b)).toContain('"fullscreen"');
        expect(JSON.stringify(pbf.renderPb2Header())).toContain('Back to book list');

        b.attrs.onclick();
        expect(pbf.__pb2V3StateForTest().pb2Fullscreen).toBe(true);
        b = findButton('pb2-fullscreen');
        expect(b.attrs.title).toBe('Exit fullscreen');
        expect(JSON.stringify(b)).toContain('"fullscreen_exit"');
        expect(JSON.stringify(pbf.renderPb2Header())).not.toContain('Back to book list');

        b.attrs.onclick();
        expect(pbf.__pb2V3StateForTest().pb2Fullscreen).toBe(false);
    });
    it('the tool routes, Characters, Canvas and Delete are still offered alongside (the V3 header contract)', () => {
        let text = JSON.stringify(pbf.renderPb2Header());
        ['pb2-render-all', 'pb2-review-link', 'pb2-style-link', 'pb2-locations-link', 'pb2-compose-link', 'pb2-cover-link',
            'pb2-export', 'pb2-fullscreen'].forEach(id => expect(text).toContain('"data-testid":"' + id + '"'));
        expect(text).toContain('Open Workflow Canvas');
        expect(text).toContain('Delete picture book');
        expect(text).not.toContain('Edit Book');
    });
});

describe('UAT-2 view container + Escape', () => {
    it('normal: flex root with data-fullscreen=0; fullscreen: fixed inset-0 z-30 overlay, banners hidden, run progress kept', () => {
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: false, pb2Run: null,
            pb2ResumeRun: { runObjectId: 'R1', name: 'run-2', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1, 2], executedNodeCount: 1, nodes: [] } });
        let v = pbf.__pb2PageReaderViewForTest.view();
        expect(v.attrs['data-testid']).toBe('pb2-reader');
        expect(v.attrs['data-fullscreen']).toBe('0');
        expect(v.attrs.className).toContain('flex-1 p-4');
        expect(v.attrs.className).not.toContain('z-30');
        expect(JSON.stringify(v)).toContain('"data-testid":"pb2-resume-banner"');

        pbf.__setPb2V3StateForTest({ pb2Fullscreen: true });
        v = pbf.__pb2PageReaderViewForTest.view();
        expect(v.attrs['data-fullscreen']).toBe('1');
        expect(v.attrs.className).toContain('fixed inset-0 z-30');
        // Below the dialog stack (dialogCore.getZIndex(0) = 40) and the toast container (z-50).
        expect(v.attrs.className).not.toMatch(/z-(40|50)/);
        expect(JSON.stringify(v)).not.toContain('"data-testid":"pb2-resume-banner"');
        // The page itself is still rendered inside the overlay.
        expect(JSON.stringify(v)).toContain('Arrival');
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: false, pb2ResumeRun: null });
    });
    it('Escape leaves fullscreen (and is ignored otherwise); arrows still page', () => {
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: true, pb2CurrentPage: 1 });
        let ev = { key: 'Escape', preventDefault: vi.fn() };
        pbf.__pb2OnKeyDownForTest(ev);
        expect(pbf.__pb2V3StateForTest().pb2Fullscreen).toBe(false);
        expect(ev.preventDefault).toHaveBeenCalled();

        ev = { key: 'Escape', preventDefault: vi.fn() };
        pbf.__pb2OnKeyDownForTest(ev);
        expect(ev.preventDefault).not.toHaveBeenCalled();

        pbf.__pb2OnKeyDownForTest({ key: 'ArrowLeft', preventDefault: function () {} });
        expect(pbf.__pb2V3StateForTest().pb2CurrentPage).toBe(0);
    });
    it('onremove resets fullscreen so the next book opens in the normal layout', () => {
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: true });
        pbf.__pb2PageReaderViewForTest.onremove();
        expect(pbf.__pb2V3StateForTest().pb2Fullscreen).toBe(false);
    });
});

describe('UAT-2 pb2ExportBook — through the shared exportBook engine', () => {
    let blobs, anchors, origBlob, origCreate, origRevoke, origClick;
    beforeEach(() => {
        blobs = []; anchors = [];
        origBlob = global.Blob;
        global.Blob = class { constructor(parts, opts) { blobs.push({ text: parts.join(''), type: opts && opts.type }); } };
        origCreate = URL.createObjectURL; origRevoke = URL.revokeObjectURL;
        URL.createObjectURL = () => 'blob:test'; URL.revokeObjectURL = () => {};
        origClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function () { anchors.push({ href: this.href, download: this.download }); };
    });
    afterEach(() => {
        global.Blob = origBlob; URL.createObjectURL = origCreate; URL.revokeObjectURL = origRevoke;
        HTMLAnchorElement.prototype.click = origClick;
    });
    it('downloads <title>-picturebook.html with the cover h1, the scene count and every scene, then toasts', async () => {
        let fetches = [];
        global.fetch = vi.fn(async (url) => { fetches.push(url); return { ok: false, status: 404 }; });
        pbf.__setPb2V3StateForTest({ pb2BookName: 'Harlot\'s Eight', pb2Covers: null, pb2Pages: [
            { objectId: 's1', sceneIndex: 1, title: 'Arrival', blurb: 'They arrive.', imageGroupPath: '/Harlot/Images', imageName: 'scene-1.png' },
            { objectId: 's2', sceneIndex: 2, title: 'Departure', summary: 'They leave.' }
        ] });
        await pbf.__pb2ExportBookForTest();
        expect(anchors.length).toBe(1);
        expect(anchors[0].download).toBe('Harlot_s_Eight-picturebook.html');
        expect(blobs.length).toBe(1);
        let html = blobs[0].text;
        expect(html).toMatch(/^<!DOCTYPE html>/);
        expect(html).toContain("<title>Harlot's Eight — Picture Book</title>");
        expect(html).toContain("<h1>Harlot's Eight</h1>");
        expect(html).toContain('<p>2 Scenes</p>');
        expect(html).toContain('<h2>Arrival</h2>');
        expect(html).toContain('<p class="blurb">They arrive.</p>');
        expect(html).toContain('<h2>Departure</h2>');
        expect(html).toContain('<p class="blurb">They leave.</p>');
        expect(html).toContain('Page 2 of 2');
        expect(html).toMatch(/<\/html>$/);
        // Scene 1 carries a MediaServlet image (cover fallback + the page); scene 2 has none, so no fetch for it.
        expect(fetches.filter(u => /\/media\/Public\/data.data\/Harlot\/Images\/scene-1\.png$/.test(u)).length).toBe(2);
        expect(fetches.length).toBe(2);
        expect(page.toast).toHaveBeenCalledWith('success', 'Picture book exported');
        expect(pbf.__pb2V3StateForTest().pb2Exporting).toBe(false);
    });
    it('no pages -> nothing is downloaded; a failing export still clears pb2Exporting', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [] });
        await pbf.__pb2ExportBookForTest();
        expect(anchors.length).toBe(0);
        expect(page.toast).not.toHaveBeenCalled();

        pbf.__setPb2V3StateForTest({ pb2Pages: [{ objectId: 's1', title: 'Arrival' }] });
        global.Blob = class { constructor() { throw new Error('boom'); } };
        await expect(pbf.__pb2ExportBookForTest()).rejects.toThrow('boom');
        expect(pbf.__pb2V3StateForTest().pb2Exporting).toBe(false);
    });
});
