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
import m from 'mithril';
import { pageHeader } from '../components/pageHeader.js';
import { __resetDirtyForTest } from '../components/dirtyGuard.js';
import * as activity from '../workflows/bookActivity.js';
import * as nav from '../workflows/bookWorkspaceNav.js';
import * as wf from '../workflows/pictureBookWorkflow.js';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

/** First vnode (depth-first) whose data-testid matches, or null. */
function findDeep(v, testid) {
    if (!v || typeof v !== 'object') return null;
    if (Array.isArray(v)) { for (let c of v) { let r = findDeep(c, testid); if (r) return r; } return null; }
    if (v.attrs && v.attrs['data-testid'] === testid) return v;
    return findDeep(v.children, testid);
}

let origFetch;
let pbf;
let edit;

beforeEach(async () => {
    origFetch = global.fetch;
    pbf = await import('../features/pictureBook.js');
    edit = await import('../features/pictureBookEdit.js');
    __resetDirtyForTest();
    activity.__resetActivityForTest();
    edit.__resetPbEditForTest();
    pbf.__pb2StopWatchingForTest();
    pbf.__setPb2V3StateForTest({
        pb2AutoApprove: false, pb2AutoApproveSaving: false, pb2Run: null, pb2ResumeRun: null,
        pb2Submitting: false, pb2CurrentPage: 1, pb2BookObjectId: 'B1', pb2BookName: 'Harlot\'s Eight', pb2PollIntervalMs: 5, pb2StyleDescribed: null,
        pb2Covers: null, pb2Fullscreen: false, pb2IsChapBook: false, pb2SceneDegradations: {},
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
        // 2026-11-16: a REJECTED selection means every candidate was rejected - its own chip, and Render all re-renders it
        expect(pbf.sceneChip({ nodeStatus: 'DONE', reviewStatus: 'REJECTED', artifactObjectId: 'a1' })).toBe('rejected');
        expect(pbf.sceneChip({ nodeStatus: 'DONE_UNVERIFIED', reviewStatus: 'rejected', artifactObjectId: 'a1' })).toBe('rejected');
        expect(pbf.sceneChip({ nodeStatus: 'STALE', reviewStatus: 'REJECTED', artifactObjectId: 'a1' })).toBe('stale');
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
// aiDocs/PictureBookUxConsistencyDesign.md §5.1 / §5.2 / §5.6 / §5.9: the V3 controls left the Read header. Render all
// is the Edit › Render toolbar action, Auto-approve an Edit › Book control, the per-scene Render / Regenerate / Plan
// buttons the Edit › Scenes › Render tab, and the tool views (Review, …) Edit rail sections. Rendered into jsdom.

function render(vnode) { let div = document.createElement('div'); m.render(div, vnode); return div; }
function q(el, testid) { return el.querySelector('[data-testid="' + testid + '"]'); }
function ctxFor(over) {
    return Object.assign({ kind: 'pb', bookObjectId: 'B1', section: 'scenes', item: 's1', tab: 'render', go: vi.fn(), select: vi.fn(), setTab: vi.fn() }, over || {});
}
function seedScenes(pages) {
    pbf.__setPb2V3StateForTest({ pb2Pages: pages, pb2SceneDegradations: { n1: { loading: false, summary: null, error: null } } });
    let recs = {};
    pages.forEach(p => { recs[p.objectId] = { loading: false, error: null, rec: { id: 1, objectId: p.objectId, title: p.title || '' } }; });
    edit.__resetPbEditForTest();
    edit.__setPbEditStateForTest({ sceneRecs: recs });
}
function sceneTab(tab) { return render(edit.scenesSection.body(ctxFor({ tab }))); }
function renderToolbar() {
    return render(pageHeader(Object.assign({ bar: true }, edit.renderSection.toolbar(ctxFor({ section: 'render', item: null, tab: null })), { back: null })));
}
function bookBody() { return render(edit.bookSection.body(ctxFor({ section: 'book', item: null, tab: null }))); }

describe('renderPb2Header — V3 controls', () => {
    // Intentionally changed (§5.1 / §5.2): the controls moved out of the Read header into Edit › Book / Render / the rail.
    it('Read has no auto-approve / Render all / Review link; Edit › Book reflects auto-approve, Edit › Render has Render all, the rail has Review', () => {
        pbf.__setPb2V3StateForTest({ pb2AutoApprove: true });
        let header = render(pbf.renderPb2Header());
        ['pb2-auto-approve-badge', 'pb2-render-all', 'pb2-render-all-start', 'pb2-review-link'].forEach(id => expect(q(header, id), id).toBeNull());
        let aa = q(bookBody(), 'pb2-auto-approve-badge');
        expect(aa.getAttribute('data-on')).toBe('1');
        expect(aa.checked).toBe(true);
        expect(q(renderToolbar(), 'pb2-render-all-start')).not.toBeNull();
        expect(nav.sectionsFor('pb').map(s => s.id)).toContain('review');
        expect(edit.pbWorkspace).toBeTruthy();
        pbf.__setPb2V3StateForTest({ pb2AutoApprove: false });
        expect(q(bookBody(), 'pb2-auto-approve-badge').getAttribute('data-on')).toBe('0');
    });
    it('Render-all is disabled while a run is being watched, with the reason as its title', () => {
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1'], job: { terminal: false }, label: 'Render all', error: null } });
        let b = q(renderToolbar(), 'pb2-render-all-start');
        expect(b.disabled).toBe(true);
        expect(b.title).toBe('A render is already running');
    });
    it('Read header in Read mode has no edit controls, and the reader view has no per-scene / run / resume controls', () => {
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1'], job: { terminal: false, current: 0, total: 1 }, label: 'Render all', error: null },
            pb2Pages: [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED', sceneNodeObjectId: 'n1' }],
            pb2SceneDegradations: { n1: { loading: false, summary: { count: 2, codes: ['MODEL_SUBSTITUTED', 'PROMPT_TRUNCATED'], truncatedDropped: ['the dropped words'] }, error: null } } });
        let el = render(pbf.__pb2PageReaderViewForTest.view());
        expect(q(el, 'pb2-reader').getAttribute('data-mode')).toBe('read');
        ['pb2-style-link', 'pb2-style-status', 'pb2-render-all', 'pb2-tools-menu', 'pb2-chat-config', 'pb2-auto-approve-badge',
            'pb2-review-link', 'pb2-locations-link', 'pb2-compose-link', 'pb2-cover-link', 'pb2-workflow-link', 'pb-book-delete',
            'pb2-scene-render', 'pb2-scene-regen-background', 'pb2-plan-render', 'pb2-scene-compose', 'pb2-scene-chip',
            'pb2-scene-degradations', 'pb2-scene-truncated', 'pb2-run-progress', 'pb2-run-cancel', 'pb2-resume-banner', 'pb2-resume-run']
            .forEach(id => expect(q(el, id), id).toBeNull());
        expect(el.querySelector('[data-pb2-characters]')).toBeNull();
        expect(el.querySelector('[data-pb2-edit]')).toBeNull();
        expect(el.querySelector('[title="Delete picture book"]')).toBeNull();
        // the run is shown as the one-line activity pill instead (§4.5)
        expect(q(el, 'book-activity').getAttribute('data-running')).toBe('1');
        expect(q(el, 'pb2-edit-page')).not.toBeNull();
        // the same state does draw the edit-only page parts in Edit › Scenes › Page, so the absences above are real
        edit.__setPbEditStateForTest({ sceneRecs: { s1: { loading: false, error: null, rec: { id: 1, objectId: 's1' } } } });
        let pageTab = render(edit.scenesSection.body(ctxFor({ tab: 'page' })));
        ['pb2-scene-chip', 'pb2-scene-degradations', 'pb2-scene-truncated', 'pb-page-goto-render'].forEach(id => expect(q(pageTab, id), id).not.toBeNull());
        expect(q(pageTab, 'pb2-edit-page')).toBeNull();
    });
    it('the Read → Edit toggle routes to Edit › Scenes › the current scene, Page tab; on a front page to bare /edit', async () => {
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let el = render(pbf.renderPb2Header());
        expect(q(el, 'book-mode-read').getAttribute('aria-checked')).toBe('true');
        q(el, 'book-mode-edit').click();
        await flushPromises(); await flushPromises();
        expect(set).toHaveBeenCalledWith('/picture-book/B1/edit/scenes/s1?tab=page');
        // page 0 is the cover (a front page): no scene to land on → the D6 entry route
        set.mockClear();
        pbf.__setPb2V3StateForTest({ pb2CurrentPage: 0 });
        el = render(pbf.renderPb2Header());
        q(el, 'book-mode-edit').click();
        await flushPromises(); await flushPromises();
        expect(set).toHaveBeenCalledWith('/picture-book/B1/edit');
        // clicking the active mode does nothing
        set.mockClear();
        q(el, 'book-mode-read').click();
        await flushPromises();
        expect(set).not.toHaveBeenCalled();
    });
    it('"Edit this page" on the Read image routes to Edit › Scenes › that scene, Page tab', () => {
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let el = render(pbf.renderPb2ScenePage());
        q(el, 'pb2-edit-page').click();
        expect(set).toHaveBeenCalledWith('/picture-book/B1/edit/scenes/s1?tab=page');
    });
});

describe('pb2PublishActivity — the run goes to the bookActivity store, not the page flow (§4.5)', () => {
    it('sets "pb-render" from the watched run and clears it when there is none; a paused run is "pb-resume"', () => {
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1', 'scene-2'], label: 'Render all', error: null,
            job: { status: 'running', phase: 'scene-2', current: 1, total: 2, terminal: false } } });
        pbf.pb2PublishActivity();
        let a = activity.getActivity('B1', 'pb-render');
        expect(a).toMatchObject({ section: 'render', testid: 'pb2-run-progress', terminal: false, label: 'Render all', counter: '1 of 2 · scene-2' });
        expect(a.stages.map(s => s.state)).toEqual(['done', 'running']);
        expect(typeof a.onCancel).toBe('function');
        expect(activity.summaryFor('B1')).toMatchObject({ running: true, label: 'Render all' });
        expect(activity.getActivity('B1', 'pb-resume')).toBeNull();
        // the run is dropped → the activity is cleared
        pbf.__setPb2V3StateForTest({ pb2Run: null });
        pbf.pb2PublishActivity();
        expect(activity.getActivity('B1', 'pb-render')).toBeNull();
        expect(activity.summaryFor('B1')).toBeNull();
        // a resumable INTERRUPTED run, not being watched → the paused activity
        pbf.__setPb2V3StateForTest({ pb2ResumeRun: { runObjectId: 'R1', name: 'run-3', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1, 2], executedNodeCount: 1 } });
        pbf.pb2PublishActivity();
        expect(activity.getActivity('B1', 'pb-resume')).toMatchObject({ label: 'Render paused', terminal: true, counter: '1 of 2 · Resume available' });
        pbf.__setPb2V3StateForTest({ pb2ResumeRun: null });
        pbf.pb2PublishActivity();
        expect(activity.getActivity('B1', 'pb-resume')).toBeNull();
    });
    it('no open book → nothing is published', () => {
        pbf.__setPb2V3StateForTest({ pb2BookObjectId: null, pb2Run: { jobId: 'J1', planned: [], job: { terminal: false }, label: 'x', error: null } });
        pbf.pb2PublishActivity();
        expect(activity.activitiesFor(null)).toEqual([]);
        pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'B1', pb2Run: null });
    });
});

describe('renderPb2ScenePage — chip, stale overlay, per-scene Render button', () => {
    // The chip is drawn in Edit › Scenes › Page (mode 'edit'); the Render button is in the Render tab (§5.6).
    it('approved + pinned page: approved chip with pinned suffix, Re-render button, no badge', () => {
        seedScenes([{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', pinned: true, dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED', sceneNodeObjectId: 'n1' }]);
        let pg = sceneTab('page');
        let chip = q(pg, 'pb2-scene-chip');
        expect(chip.getAttribute('data-chip')).toBe('approved');
        expect(chip.textContent).toBe('Approved · pinned');
        expect(q(pg, 'pb2-stale-badge')).toBeNull();
        let r = q(sceneTab('render'), 'pb2-scene-render');
        expect(r.textContent).toBe('Re-render page');
    });
    it('never-rendered page: no image, "Needs render" badge only when STALE, button says Render', () => {
        seedScenes([{ objectId: 's1', sceneIndex: 1, nodeStatus: 'READY' }]);
        let read = render(pbf.renderPb2ScenePage());
        expect(q(read, 'pb2-scene-image')).toBeNull();
        expect(q(read, 'pb2-stale-badge')).toBeNull();
        expect(q(sceneTab('page'), 'pb2-scene-chip')).toBeNull();
        expect(q(sceneTab('render'), 'pb2-scene-render').textContent).toBe('Render page');
        seedScenes([{ objectId: 's1', sceneIndex: 1, nodeStatus: 'STALE' }]);
        expect(q(render(pbf.renderPb2ScenePage()), 'pb2-stale-badge').textContent).toContain('Needs render');
    });
    it('§24 I-8: "Regenerate background" is drawn only when the scene has an image, and is disabled while a run is watched', () => {
        seedScenes([{ objectId: 's1', sceneIndex: 1, nodeStatus: 'READY' }]);
        expect(q(sceneTab('render'), 'pb2-scene-regen-background')).toBeNull();
        seedScenes([{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED', sceneNodeObjectId: 'n1' }]);
        let b = q(sceneTab('render'), 'pb2-scene-regen-background');
        expect(b.disabled).toBe(false);
        expect(b.textContent).toBe('Regenerate background');
        pbf.__setPb2V3StateForTest({ pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1'], job: { terminal: false }, label: 'Render scene 1', error: null } });
        b = q(sceneTab('render'), 'pb2-scene-regen-background');
        expect(b.disabled).toBe(true);
        expect(b.title).toBe('A render is already running');
    });
});

describe('scenesNeedingRender — the Scenes / Render rail count', () => {
    it('counts stale, rejected and never-rendered scenes; approved / in-review rendered scenes do not count', () => {
        expect(edit.scenesNeedingRender([
            { objectId: 'a', nodeStatus: 'STALE', dataObjectId: 'd' },                                         // stale
            { objectId: 'b', nodeStatus: 'DONE', reviewStatus: 'REJECTED', artifactObjectId: 'x', dataObjectId: 'd' }, // rejected
            { objectId: 'c', nodeStatus: 'READY' },                                                            // never rendered
            { objectId: 'd', nodeStatus: 'DONE', imageStale: true, reviewStatus: 'APPROVED', dataObjectId: 'd' },  // stale image
            { objectId: 'e', nodeStatus: 'DONE', reviewStatus: 'APPROVED', dataObjectId: 'd' },                 // done
            { objectId: 'f', nodeStatus: 'DONE_UNVERIFIED', reviewStatus: 'GENERATED', artifactObjectId: 'y', dataObjectId: 'd' }, // in review
            { objectId: 'g', dataObjectId: 'd' },                                                              // legacy image
            null
        ])).toBe(4);
        expect(edit.scenesNeedingRender([])).toBe(0);
        expect(edit.scenesNeedingRender(null)).toBe(0);
    });
    it('is what the Scenes and Render sections report', () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [{ objectId: 'a', nodeStatus: 'STALE', dataObjectId: 'd' }, { objectId: 'c', nodeStatus: 'READY' }, { objectId: 'e', nodeStatus: 'DONE', reviewStatus: 'APPROVED', dataObjectId: 'd' }] });
        expect(edit.scenesSection.status(ctxFor())).toMatchObject({ kind: 'count', count: 2 });
        expect(edit.renderSection.status(ctxFor({ section: 'render' }))).toMatchObject({ kind: 'count', count: 2 });
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
        if (/\/B1\/scene\/s1\/regenerate-background$/.test(url)) {
            if (o.regen) return o.regen(calls);
            return jsonResponse(202, { jobId: 'J1', jobStatus: 'pending', kind: 'pb.regenerateBackground', runObjectId: 'R1', runStatus: 'RUNNING', plannedNodeCount: 1, planned: ['scene-1'], reusedNodeCount: 0, pinnedNodeCount: 0, regenerate: 'background' });
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

describe('§24 I-8 regenerate background → 202 → job polling → terminal refresh', () => {
    const PAGE_WITH_IMAGE = { objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', pinned: false,
        dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED' };

    it('POSTs /scene/{id}/regenerate-background with no body and no force flag, polls /rest/job/{jobId}, then reloads pages', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [PAGE_WITH_IMAGE] });
        let calls = mockRenderStack();
        await pbf.__pb2SubmitRegenBackgroundForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        let st = await waitForTerminal();
        expect(st.pb2Run.jobId).toBe('J1');
        expect(st.pb2Run.planned).toEqual(['scene-1']);
        expect(st.pb2Run.label).toBe('Regenerate background 1');
        await flushPromises(); await flushPromises(); await flushPromises();
        let seq = calls.map(c => c.method + ' ' + c.url.replace(/^.*\/rest\//, '/rest/'));
        expect(seq[0]).toBe('POST /rest/olio/picture-book/B1/scene/s1/regenerate-background');
        expect(calls[0].body).toBeUndefined();   // the route takes no force / variants: one fresh BASE, actors keep their seed
        expect(seq.filter(s => s === 'GET /rest/job/J1').length).toBe(2);
        expect(seq).toContain('GET /rest/olio/picture-book/B1/pages');
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.pb.scene', true);
        expect(page.toast).toHaveBeenCalledWith('success', 'Render complete: 1 rendered');
    });

    it('a picked chat config travels as {chatConfigName} and nothing else', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [PAGE_WITH_IMAGE], pb2ChatConfig: { name: 'qa-cfg', objectId: 'c1' } });
        let calls = mockRenderStack();
        try {
            await pbf.__pb2SubmitRegenBackgroundForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
            await waitForTerminal();
            expect(JSON.parse(calls[0].body)).toEqual({ chatConfigName: 'qa-cfg' });
        }
        finally {
            pbf.__setPb2V3StateForTest({ pb2ChatConfig: null });
        }
    });

    it('"render it first" 409 and the LANDSCAPE 409 are toasted verbatim: no confirmation, no pin call', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [PAGE_WITH_IMAGE] });
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let calls = mockRenderStack({ regen: () => jsonResponse(409, { error: 'Scene has no selected render to regenerate the background of; render it first' }) });
        await pbf.__pb2SubmitRegenBackgroundForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Scene has no selected render to regenerate the background of; render it first');
        expect(confirm).not.toHaveBeenCalled();
        expect(calls.some(c => /\/pin$/.test(c.url))).toBe(false);
        expect(pbf.__pb2V3StateForTest().pb2Run).toBeNull();
        expect(pbf.__pb2V3StateForTest().pb2Submitting).toBe(false);

        calls.length = 0;
        mockRenderStack({ regen: () => jsonResponse(409, { error: 'Scene node landscape@s1 is a LANDSCAPE, not a RENDER_PLAN node: it has no actor passes to keep. Render the scene instead.' }) });
        await pbf.__pb2SubmitRegenBackgroundForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(page.toast).toHaveBeenCalledWith('error', expect.stringContaining('(409) Scene node landscape@s1 is a LANDSCAPE'));
        expect(confirm).not.toHaveBeenCalled();
    });

    it('pinned 409: the confirmation offers "Unpin and regenerate"; declining sends nothing more', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [PAGE_WITH_IMAGE] });
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        let calls = mockRenderStack({ regen: () => jsonResponse(409, { error: 'Node render_plan@s1 is pinned. Unpin it first - a pinned scene is never re-rendered' }) });
        await pbf.__pb2SubmitRegenBackgroundForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(confirm).toHaveBeenCalledTimes(1);
        let cfg = confirm.mock.calls[0][0];
        expect(cfg.confirmLabel).toBe('Unpin and regenerate');
        expect(cfg.message).toContain('(409) Node render_plan@s1 is pinned');
        expect(calls.length).toBe(1);
        expect(page.toast).not.toHaveBeenCalledWith('error', expect.anything());
        expect(pbf.__pb2V3StateForTest().pb2Submitting).toBe(false);
    });

    it('pinned 409: accepting sends pin {pinned:false} to the scene node, then re-submits and watches', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [PAGE_WITH_IMAGE] });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let attempt = 0;
        let calls = mockRenderStack({ regen: () => {
            attempt++;
            if (attempt === 1) return jsonResponse(409, { error: 'Node render_plan@s1 is pinned. Unpin it first - a pinned scene is never re-rendered' });
            return jsonResponse(202, { jobId: 'J1', kind: 'pb.regenerateBackground', runObjectId: 'R1', runStatus: 'RUNNING', planned: ['scene-1'], regenerate: 'background' });
        } });
        await pbf.__pb2SubmitRegenBackgroundForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        let st = await waitForTerminal();
        expect(st.pb2Run.jobId).toBe('J1');
        let seq = calls.map(c => c.method + ' ' + c.url.replace(/^.*\/rest\//, '/rest/'));
        expect(seq.slice(0, 3)).toEqual([
            'POST /rest/olio/picture-book/B1/scene/s1/regenerate-background',
            'POST /rest/olio/picture-book/B1/node/n1/pin',
            'POST /rest/olio/picture-book/B1/scene/s1/regenerate-background'
        ]);
        expect(JSON.parse(calls[1].body)).toEqual({ pinned: false });
        expect(page.toast).not.toHaveBeenCalledWith('error', expect.anything());
    });

    it('pinned 409 on a page with no sceneNodeObjectId: toasted, no confirmation (nothing to unpin)', async () => {
        pbf.__setPb2V3StateForTest({ pb2Pages: [Object.assign({}, PAGE_WITH_IMAGE, { sceneNodeObjectId: undefined })] });
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        mockRenderStack({ regen: () => jsonResponse(409, { error: 'Node render_plan@s1 is pinned. Unpin it first - a pinned scene is never re-rendered' }) });
        await pbf.__pb2SubmitRegenBackgroundForTest(pbf.__pb2V3StateForTest().pb2Pages[0]);
        expect(confirm).not.toHaveBeenCalled();
        expect(page.toast).toHaveBeenCalledWith('error', expect.stringContaining('(409) Node render_plan@s1 is pinned'));
    });
});

describe('render-all dialog → 202 → watch', () => {
    it('warns about scenesWithoutNode, closes the dialog and starts watching the job', async () => {
        // Started from the Render dialog (the toolbar path, which must NOT close another dialog, is pinned in
        // pictureBookStyleGate.test.js "Render all closes only its own dialog").
        pbf.__setPb2V3StateForTest({ pb2RenderDialogOpen: true });
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
    function findButton(testid) { return findDeep(pbf.renderPb2Header(), testid); }
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
    // Same behaviour; the back tooltip is now bookHeader's "Back to Picture Books" (§4.1, §6.4 terminology).
    it('Fullscreen: toggles title/icon (Fullscreen/fullscreen <-> Exit fullscreen/fullscreen_exit); Back hidden in fullscreen', () => {
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: false });
        let b = findButton('pb2-fullscreen');
        expect(b.attrs.title).toBe('Fullscreen');
        expect(JSON.stringify(b)).toContain('"fullscreen"');
        let el = render(pbf.renderPb2Header());
        expect(q(el, 'book-back').title).toBe('Back to Picture Books');

        b.attrs.onclick();
        expect(pbf.__pb2V3StateForTest().pb2Fullscreen).toBe(true);
        b = findButton('pb2-fullscreen');
        expect(b.attrs.title).toBe('Exit fullscreen');
        expect(JSON.stringify(b)).toContain('"fullscreen_exit"');
        el = render(pbf.renderPb2Header());
        expect(q(el, 'book-back')).toBeNull();
        expect(el.querySelector('[title="Back to Picture Books"]')).toBeNull();

        b.attrs.onclick();
        expect(pbf.__pb2V3StateForTest().pb2Fullscreen).toBe(false);
    });
    it('Back goes to the Picture Books library', async () => {
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        q(render(pbf.renderPb2Header()), 'book-back').click();
        await flushPromises(); await flushPromises();
        expect(set).toHaveBeenCalledWith('/picture-book');
    });
    // Intentionally changed (§5.1, §4.2): Read keeps only Export / Fullscreen; the tool routes, Characters, Canvas are
    // Edit rail sections and Delete is Edit › Book's danger zone.
    it('Read offers only Export / Fullscreen; the tool routes, Characters, Canvas are Edit rail sections and Delete is in Edit › Book', async () => {
        let header = render(pbf.renderPb2Header());
        ['pb2-export', 'pb2-fullscreen'].forEach(id => expect(q(header, id), id).not.toBeNull());
        ['pb2-render-all', 'pb2-review-link', 'pb2-style-link', 'pb2-locations-link', 'pb2-compose-link', 'pb2-cover-link', 'pb2-workflow-link']
            .forEach(id => expect(q(header, id), id).toBeNull());
        expect(header.textContent).not.toContain('Open Workflow Canvas');
        expect(header.querySelector('[title="Delete picture book"]')).toBeNull();
        expect(header.textContent).not.toContain('Edit Book');
        expect(nav.sectionsFor('pb').map(s => s.id)).toEqual(['book', 'style', 'characters', 'locations', 'scenes', 'cover', 'review', 'render', 'canvas']);
        expect(nav.sectionDef('pb', 'canvas').advanced).toBe(true);
        // Delete: Edit › Book › Danger zone asks first; declining deletes nothing and stays
        global.fetch = vi.fn(async () => jsonResponse(500, 'must not be called'));
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        let book = render(edit.bookSection.body(ctxFor({ section: 'book', item: null, tab: null })));
        q(book, 'pb-book-delete').click();
        await flushPromises();
        expect(confirm).toHaveBeenCalledTimes(1);
        expect(confirm.mock.calls[0][0]).toMatchObject({ title: 'Delete Picture Book', destructive: true });
        expect(global.fetch).not.toHaveBeenCalled();
        expect(set).not.toHaveBeenCalled();
    });
});

describe('UAT-2 view container + Escape', () => {
    // Same container contract. The resume banner / run panel are no longer in the reader flow — a run (or a paused
    // run) is the header's activity pill (§4.5, §5.1), so "run progress kept" in fullscreen is now the pill.
    it('normal: flex root with data-fullscreen=0; fullscreen: fixed inset-0 z-30 overlay, banners hidden, run progress kept', () => {
        pbf.__setPbHealthStateForTest({ pb2Health: { scope: 'book', findings: [{ code: 'STALE_GRAPH', severity: 'ERROR', message: 'stale' }], summary: { errors: 1, warnings: 0, infos: 0, healable: 1 } } });
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: false, pb2Run: null,
            pb2ResumeRun: { runObjectId: 'R1', name: 'run-2', runStatus: 'INTERRUPTED', resumable: true, requestedNodeIds: [1, 2], executedNodeCount: 1, nodes: [] } });
        let v = pbf.__pb2PageReaderViewForTest.view();
        expect(v.attrs['data-testid']).toBe('pb2-reader');
        expect(v.attrs['data-fullscreen']).toBe('0');
        expect(v.attrs.className).toContain('flex-1 flex flex-col');
        expect(v.attrs.className).not.toContain('z-30');
        let el = render(v);
        expect(q(el, 'pb2-resume-banner')).toBeNull();
        expect(q(el, 'pb2-health-fix-in-edit')).not.toBeNull();
        expect(q(el, 'book-activity-label').textContent).toBe('Render paused');

        pbf.__setPb2V3StateForTest({ pb2Fullscreen: true, pb2ResumeRun: null,
            pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1'], job: { terminal: false, current: 0, total: 1 }, label: 'Render all', error: null } });
        v = pbf.__pb2PageReaderViewForTest.view();
        expect(v.attrs['data-fullscreen']).toBe('1');
        expect(v.attrs.className).toContain('fixed inset-0 z-30');
        // Below the dialog stack (dialogCore.getZIndex(0) = 40) and the toast container (z-50).
        expect(v.attrs.className).not.toMatch(/z-(40|50)/);
        el = render(v);
        expect(q(el, 'pb2-health-fix-in-edit')).toBeNull();
        expect(el.querySelector('[data-pb2-health-notice]')).toBeNull();
        expect(q(el, 'book-activity').getAttribute('data-running')).toBe('1');
        expect(q(el, 'book-activity-label').textContent).toBe('Render all');
        // The page itself is still rendered inside the overlay.
        expect(el.textContent).toContain('Arrival');
        pbf.__setPb2V3StateForTest({ pb2Fullscreen: false, pb2ResumeRun: null, pb2Run: null });
        pbf.__setPbHealthStateForTest({ pb2Health: null });
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

// §8.2: a ChapBook already loaded by the reader and then opened on a PictureBook Edit route must still be sent to its
// own product — the refresh path of pb2EnsureBook, not only a full load (found live by e2e/bookModes.spec.js).
describe('pb2EnsureBook — ChapBook re-entered on a PictureBook route', () => {
    it('redirects to the ChapBook route on the already-loaded (refresh) path, without a page reload', () => {
        pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'C1', pb2Pages: [{ objectId: 's1' }], pb2IsChapBook: true, pb2LoadedBookId: 'C1' });
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(m.route, 'get').mockReturnValue('/picture-book/C1/edit/scenes');
        pbf.pb2EnsureBook('C1');
        expect(set).toHaveBeenCalledWith('/chap-book/edit/C1/pages', null, { replace: true });
    });
    it('a PictureBook on the same path is left where it is', () => {
        pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'B1', pb2Pages: [{ objectId: 's1' }], pb2IsChapBook: false, pb2LoadedBookId: 'B1' });
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(m.route, 'get').mockReturnValue('/picture-book/B1/edit/scenes');
        global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [], text: async () => '[]' }));
        pbf.pb2EnsureBook('B1');
        expect(set).not.toHaveBeenCalled();
    });
});
