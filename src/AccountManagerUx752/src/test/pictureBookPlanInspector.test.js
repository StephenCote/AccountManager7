// @vitest-environment jsdom
/**
 * PictureBook V3 plan inspector + estimate display (P2-9, design §26).
 *
 * Why: the plan is the server's artifact (POST /{book}/scene/{s}/plan, or the persisted renderPlan
 * artifact's artifactText) and the UI must show it as received — the estimate source verbatim when
 * nothing is measured, unresolved models and degradations by code — and never invent a deadline or a
 * result: Generate waits on POST /{book}/scene/{s}/render-plan and shows its status + message
 * verbatim (a 503 carries the plan artifact id). The review page finds a node's plan through
 * /workflow/node/{n} because listReviewQueue omits the renderPlan role. Shapes are the live captures
 * from the Docker stack (C:\tmp\p29-plan2.json, 2026-10-15).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { PbPlanInspector, estimateText, modelRows } from '../components/PbPlanInspector.js';
import { normalizePlan, planFromArtifact, latestArtifact, loadNodePlan } from '../workflows/pictureBookWorkflow.js';
import * as review from '../features/pictureBookReview.js';
import * as canvas from '../features/pictureBookWorkflow.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

function flushPromises() { return new Promise(r => setTimeout(r, 0)); }

function findByTestId(vnode, testid) {
    let found = null;
    (function walk(v) {
        if (!v || found) return;
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.attrs && v.attrs['data-testid'] === testid) { found = v; return; }
        walk(v.children);
    })(vnode);
    return found;
}

// Mithril normalizes a string child into [{tag:'#', children:'…'}]; read the text back out.
function textOf(v) {
    if (v == null) return '';
    if (typeof v === 'string' || typeof v === 'number') return String(v);
    if (Array.isArray(v)) return v.map(textOf).join('');
    if (v.tag === '#') return String(v.children);
    return textOf(v.children);
}

function restPath(u) { return String(u).replace(/^.*\/rest\//, '/rest/'); }

// POST /{book}/scene/{s}/plan with the .39 Swarm down (measured): no model, INVENTORY_UNAVAILABLE, estimate source only.
const PLAN_OID = 'd0a7c7ce-plan-artifact';
const PLAN = {
    planVersion: 'illustration/1', purpose: 'SCENE', canvas: { width: 1216, height: 832 },
    preset: { preset: 'STANDARD', steps: 25, variants: 2, upscale: false, qa: 'CANONICAL' },
    style: { styleObjectId: 'st-1', revisionHash: 'abc123', description: 'ink wash', photoreal: false },
    location: { locationId: 'loc-1', description: 'a harbour at dusk' }, intent: { raw: false },
    strategy: { name: 'TEXT_ONLY', stages: ['BASE'], reason: 'no reference images bound' },
    models: {
        location: { task: 'location', reason: 'INVENTORY_UNAVAILABLE', degraded: false },
        sceneDirect: { task: 'sceneDirect', reason: 'INVENTORY_UNAVAILABLE', degraded: true, degradationText: 'x' }
    },
    compiled: { BASE: { prompt: 'a harbour at dusk, ink wash', estimatedTokens: '16' } },
    degradations: [{ code: 'MODEL_SUBSTITUTED', message: 'Model for LOCATION is unresolved: INVENTORY_UNAVAILABLE (Could not read the installed-model list from the SD server)', stage: 'BASE' }],
    configNotApplied: null,
    estimate: { scenes: 1, stages: 1, variants: 2, steps: 25, measured: false, source: 'NONE: no measurement for this server/family yet' },
    contentHash: 'h1', config: { model: 'juggernautXL_v9.safetensors', sampler: 'euler', scheduler: 'karras', cfg: 7, seed: -1, steps: 25, width: 1216, height: 832 }
};
const RENDER_503 = 'Renderer inventory unavailable; cannot verify a model for scene 1 before rendering - Model for LOCATION is unresolved: INVENTORY_UNAVAILABLE (Could not read the installed-model list from the SD server) (plan artifact ' + PLAN_OID + ')';
const PLAN_ARTIFACT = { objectId: PLAN_OID, artifactType: 'TEXT', artifactText: JSON.stringify(PLAN), backend: 'PLAN', byteLength: 1200, contentHash: 'h1', revision: 1, role: 'renderPlan', reviewStatus: 'GENERATED', selected: true };

let origFetch;

beforeEach(() => {
    origFetch = global.fetch;
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
});

afterEach(() => {
    Dialog._reset();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── pure helpers ───────────────────────────────

describe('estimateText', () => {
    it('unmeasured: counts then the server\'s source verbatim', () => {
        expect(estimateText(PLAN.estimate)).toBe('1 scene · 1 stage · 2 variants · 25 steps · NONE: no measurement for this server/family yet');
    });
    it('measured: ~seconds (or minutes ≥ 90 s) with the per-step rate; plural forms', () => {
        expect(estimateText({ scenes: 3, stages: 2, variants: 1, steps: 30, measured: true, seconds: 42.4, secondsPerStep: 1.413 })).toBe('3 scenes · 2 stages · 1 variant · 30 steps · ~42 s (1.41 s/step)');
        expect(estimateText({ measured: true, seconds: 150 })).toBe('~2.5 min');
    });
    it('nothing → "No estimate"; source only; measured:true without seconds falls back to the source', () => {
        expect(estimateText(null)).toBe('No estimate');
        expect(estimateText({})).toBe('No estimate');
        expect(estimateText({ source: 'NONE' })).toBe('NONE');
        expect(estimateText({ steps: 25, measured: true, source: 'MEASURED' })).toBe('25 steps · MEASURED');
    });
});

describe('modelRows / normalizePlan / planFromArtifact / latestArtifact', () => {
    it('modelRows keeps the plan\'s task names, null model when unresolved, degraded as boolean', () => {
        expect(modelRows(PLAN.models)).toEqual([
            { task: 'location', model: null, reason: 'INVENTORY_UNAVAILABLE', degraded: false, degradationText: null },
            { task: 'sceneDirect', model: null, reason: 'INVENTORY_UNAVAILABLE', degraded: true, degradationText: 'x' }
        ]);
        expect(modelRows({ portrait: { model: 'm.safetensors' } })).toEqual([{ task: 'portrait', model: 'm.safetensors', reason: '', degraded: false, degradationText: null }]);
        expect(modelRows(null)).toEqual([]);
    });
    it('normalizePlan: every container present; configNotApplied null → []; NON_EMPTY-dropped lists become []', () => {
        let p = normalizePlan(PLAN);
        expect(p.configNotApplied).toEqual([]);
        expect(p.degradations.length).toBe(1);
        let empty = normalizePlan({});
        expect(empty.strategy.stages).toEqual([]);
        expect(empty.models).toEqual({});
        expect(empty.compiled).toEqual({});
        expect(empty.degradations).toEqual([]);
        expect(empty.estimate).toEqual({});
        expect(empty.canvas).toEqual({});
        expect(normalizePlan(null).preset).toEqual({});
    });
    it('planFromArtifact: plan JSON in artifactText → normalized plan; blank / bad JSON / non-object → null', () => {
        expect(planFromArtifact(PLAN_ARTIFACT).strategy.name).toBe('TEXT_ONLY');
        expect(planFromArtifact(PLAN_ARTIFACT).configNotApplied).toEqual([]);
        expect(planFromArtifact({ artifactText: '' })).toBeNull();
        expect(planFromArtifact({ artifactText: '{not json' })).toBeNull();
        expect(planFromArtifact({ artifactText: '"a string"' })).toBeNull();
        expect(planFromArtifact({ objectId: 'x' })).toBeNull();
        expect(planFromArtifact(null)).toBeNull();
    });
    it('latestArtifact: the highest revision regardless of order (the server lists ascending)', () => {
        expect(latestArtifact([{ objectId: 'a', revision: 1 }, { objectId: 'b', revision: 3 }, { objectId: 'c', revision: 2 }]).objectId).toBe('b');
        expect(latestArtifact([{ objectId: 'b', revision: '3' }, null, { objectId: 'a', revision: 1 }]).objectId).toBe('b');
        expect(latestArtifact([])).toBeNull();
        expect(latestArtifact(null)).toBeNull();
    });
    it('loadNodePlan: null when the node has no renderPlan chain; else GET /{book}/artifact/{latest}', async () => {
        expect(await loadNodePlan('B1', null)).toBeNull();
        expect(await loadNodePlan('B1', { image: [{ objectId: 'i1', revision: 1 }] })).toBeNull();
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push(((o && o.method) || 'GET') + ' ' + restPath(u)); return jsonResponse(200, PLAN_ARTIFACT); });
        let r = await loadNodePlan('B1', { renderPlan: [{ objectId: 'old', revision: 1 }, { objectId: PLAN_OID, revision: 2 }] });
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/artifact/' + PLAN_OID]);
        expect(r.artifact.objectId).toBe(PLAN_OID);
        expect(r.plan.estimate.steps).toBe(25);
        global.fetch = vi.fn(async () => jsonResponse(200, { objectId: 'x', role: 'renderPlan', revision: 1 }));
        let bare = await loadNodePlan('B1', { renderPlan: [{ objectId: 'x', revision: 1 }] });
        expect(bare.plan).toBeNull();
        expect(bare.artifact.objectId).toBe('x');
    });
});

// ─────────────────────────────── inspector component ───────────────────────────────

describe('PbPlanInspector.view', () => {
    it('renders strategy, estimate, per-task model rows (unresolved/degraded), compiled stages and degradations by code', () => {
        let v = PbPlanInspector.view({ attrs: { plan: PLAN } });
        let text = JSON.stringify(v);
        expect(text).toContain('"data-testid":"pb2-plan-inspector"');
        expect(JSON.stringify(findByTestId(v, 'pb2-plan-strategy'))).toContain('TEXT_ONLY');
        expect(text).toContain('illustration/1');
        expect(text).toContain('no reference images bound');
        expect(JSON.stringify(findByTestId(v, 'pb2-plan-estimate'))).toContain('1 scene · 1 stage · 2 variants · 25 steps · NONE: no measurement for this server/family yet');
        expect(text).toContain('1216 × 832');
        expect(text).toContain('euler / karras');
        let models = findByTestId(v, 'pb2-plan-models');
        expect(models).not.toBeNull();
        let mt = JSON.stringify(models);
        expect(mt).toContain('"data-task":"location","data-degraded":"0"');
        expect(mt).toContain('"data-task":"sceneDirect","data-degraded":"1"');
        expect(mt).toContain('unresolved');
        expect(mt).toContain('"degraded"');
        let stage = findByTestId(v, 'pb2-plan-stage');
        expect(stage.attrs['data-stage']).toBe('BASE');
        expect(JSON.stringify(stage)).toContain('~16 tokens');
        expect(JSON.stringify(stage)).toContain('a harbour at dusk, ink wash');
        let deg = findByTestId(v, 'pb2-plan-degradations');
        expect(JSON.stringify(deg)).toContain('"data-code":"MODEL_SUBSTITUTED"');
        expect(JSON.stringify(deg)).toContain('[BASE] Model for LOCATION is unresolved: INVENTORY_UNAVAILABLE');
        expect(text).not.toContain('pb2-plan-config-not-applied');
        expect(text).toContain('a harbour at dusk');
    });
    it('an empty plan renders the "none" placeholders instead of throwing', () => {
        let text = JSON.stringify(PbPlanInspector.view({ attrs: { plan: null } }));
        expect(text).toContain('strategy ?');
        expect(text).toContain('No estimate');
        expect(text).toContain('No model resolution in this plan.');
        expect(text).toContain('No compiled prompts.');
        expect(text).toContain('None.');
    });
    it('configNotApplied entries are listed', () => {
        let text = JSON.stringify(PbPlanInspector.view({ attrs: { plan: Object.assign({}, PLAN, { configNotApplied: ['hiresFix', { key: 'refiner' }] }) } }));
        expect(text).toContain('"data-testid":"pb2-plan-config-not-applied"');
        expect(text).toContain('hiresFix, {\\"key\\":\\"refiner\\"}');
    });
});

// ─────────────────────────────── review page: per-node "Plan" ───────────────────────────────

describe('review page toggleNodePlan', () => {
    const NODE = { nodeObjectId: 'n1', sceneIndex: 1, handle: 'scene-1', nodeStatus: 'DONE', pinned: false, artifacts: [{ objectId: 'img1', role: 'image', revision: 1, reviewStatus: 'GENERATED', selected: true }] };

    beforeEach(() => {
        review.__setReviewStateForTest({ bookObjectId: 'B1', queue: { nodes: [NODE], pendingCount: 1, artifactCount: 1, autoApprove: false }, loading: false, error: null, filter: 'all', busyArtifact: null, nodePlans: {} });
    });

    it('"Plan" → /workflow/node/{n} → newest renderPlan artifact → inspector with "rev N · oid"', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = restPath(u);
            calls.push(((o && o.method) || 'GET') + ' ' + url);
            if (/\/workflow\/node\/n1$/.test(url)) return jsonResponse(200, { objectId: 'n1', nodeType: 'RENDER_PLAN', artifacts: { renderPlan: [{ objectId: 'old', revision: 1, role: 'renderPlan' }, { objectId: PLAN_OID, revision: 2, role: 'renderPlan' }] }, bindings: [] });
            if (/\/artifact\//.test(url)) return jsonResponse(200, Object.assign({}, PLAN_ARTIFACT, { revision: 2 }));
            return jsonResponse(404, { error: 'unexpected ' + url });
        });
        let before = review.renderReviewBody();
        let btn = findByTestId(before, 'pb2-review-plan-toggle');
        expect(btn.attrs.disabled).toBe(false);
        expect(textOf(btn)).toBe('Plan');
        expect(findByTestId(before, 'pb2-review-plan')).toBeNull();

        let p = review.toggleNodePlan('n1');
        let loading = review.renderReviewBody();
        expect(findByTestId(loading, 'pb2-review-plan-toggle').attrs.disabled).toBe(true);
        expect(textOf(findByTestId(loading, 'pb2-review-plan-toggle'))).toBe('Loading plan…');
        await p;

        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/workflow/node/n1', 'GET /rest/olio/picture-book/B1/artifact/' + PLAN_OID]);
        let after = review.renderReviewBody();
        let panel = findByTestId(after, 'pb2-review-plan');
        expect(panel).not.toBeNull();
        let text = JSON.stringify(panel);
        expect(text).toContain('rev 2 · ' + PLAN_OID);
        expect(text).toContain('"plan"');
        expect(textOf(findByTestId(after, 'pb2-review-plan-toggle'))).toBe('Hide plan');
        expect(findByTestId(after, 'pb2-review-plan-error')).toBeNull();
        let st = review.__reviewStateForTest().nodePlans.n1;
        expect(st.open).toBe(true);
        expect(st.plan.strategy.name).toBe('TEXT_ONLY');

        // Hide → no request; show again → still no request (loaded once per node).
        await review.toggleNodePlan('n1');
        expect(findByTestId(review.renderReviewBody(), 'pb2-review-plan')).toBeNull();
        await review.toggleNodePlan('n1');
        expect(findByTestId(review.renderReviewBody(), 'pb2-review-plan')).not.toBeNull();
        expect(calls.length).toBe(2);
    });
    it('a node without a renderPlan artifact reports it; a non-2xx nodeView reports its status', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, { objectId: 'n1', artifacts: { image: [{ objectId: 'img1', revision: 1 }] } }));
        await review.toggleNodePlan('n1');
        let v = review.renderReviewBody();
        expect(findByTestId(v, 'pb2-review-plan')).toBeNull();
        expect(textOf(findByTestId(v, 'pb2-review-plan-error'))).toBe('This node has no render-plan artifact');

        review.__setReviewStateForTest({ nodePlans: {} });
        global.fetch = vi.fn(async () => jsonResponse(403, { error: 'forbidden' }));
        await review.toggleNodePlan('n1');
        expect(textOf(findByTestId(review.renderReviewBody(), 'pb2-review-plan-error'))).toBe('nodeView failed: 403');

        review.__setReviewStateForTest({ nodePlans: {} });
        global.fetch = vi.fn(async (u) => /\/artifact\//.test(String(u)) ? jsonResponse(200, { objectId: 'p0', revision: 1 }) : jsonResponse(200, { artifacts: { renderPlan: [{ objectId: 'p0', revision: 1 }] } }));
        await review.toggleNodePlan('n1');
        expect(textOf(findByTestId(review.renderReviewBody(), 'pb2-review-plan-error'))).toBe('The render-plan artifact p0 does not carry plan JSON');
    });
});

// ─────────────────────────────── canvas: node detail "View plan" ───────────────────────────────

describe('canvas node detail View plan', () => {
    const GRAPH = { nodes: [{ objectId: 'n1', nodeType: 'RENDER_PLAN', handle: 'scene-1', status: 'DONE' }], edges: [] };

    it('the toggle appears only when the node view carries a renderPlan chain', () => {
        canvas.__setCanvasStateForTest({ graphData: GRAPH, pb2BookObjectId: 'B1', selectedNodeId: 'n1', nodeDetailsLoading: false, nodeDetails: { artifacts: { image: [{ objectId: 'i1', revision: 1 }] }, bindings: [] }, nodePlan: { nodeId: 'n1' } });
        expect(findByTestId(canvas.renderNodeDetailPanel(), 'pbwf-node-plan')).toBeNull();
        canvas.__setCanvasStateForTest({ nodeDetails: { artifacts: { renderPlan: [{ objectId: PLAN_OID, revision: 1 }] }, bindings: [] } });
        let v = canvas.renderNodeDetailPanel();
        expect(findByTestId(v, 'pbwf-node-plan')).not.toBeNull();
        expect(textOf(findByTestId(v, 'pbwf-node-plan-toggle'))).toBe('View plan');
        canvas.__setCanvasStateForTest({ selectedNodeId: null });
        expect(canvas.renderNodeDetailPanel()).toBeNull();
    });
    it('View plan → GET /{book}/artifact/{latest} → inspector; Hide → no request; errors shown in the panel', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push(((o && o.method) || 'GET') + ' ' + restPath(u)); return jsonResponse(200, PLAN_ARTIFACT); });
        canvas.__setCanvasStateForTest({ graphData: GRAPH, pb2BookObjectId: 'B1', selectedNodeId: 'n1', nodeDetailsLoading: false,
            nodeDetails: { artifacts: { renderPlan: [{ objectId: 'old', revision: 1 }, { objectId: PLAN_OID, revision: 1 }], image: [] }, bindings: [] }, nodePlan: { nodeId: 'n1' } });
        let p = canvas.toggleNodePlan();
        expect(findByTestId(canvas.renderNodeDetailPanel(), 'pbwf-node-plan-toggle').attrs.disabled).toBe(true);
        await p;
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/artifact/old']);
        let v = canvas.renderNodeDetailPanel();
        let text = JSON.stringify(findByTestId(v, 'pbwf-node-plan'));
        expect(textOf(findByTestId(v, 'pbwf-node-plan-toggle'))).toBe('Hide plan');
        expect(text).toContain('rev 1 · ' + PLAN_OID);
        expect(text).toContain('"plan"');
        expect(canvas.__canvasStateForTest().nodePlan.plan.estimate.steps).toBe(25);
        await canvas.toggleNodePlan();
        expect(canvas.__canvasStateForTest().nodePlan.open).toBe(false);
        expect(calls.length).toBe(1);

        global.fetch = vi.fn(async () => jsonResponse(404, { error: 'Artifact not found' }));
        canvas.__setCanvasStateForTest({ nodePlan: { nodeId: 'n1' } });
        await canvas.toggleNodePlan();
        expect(textOf(findByTestId(canvas.renderNodeDetailPanel(), 'pbwf-node-plan-error'))).toBe('(404) Artifact not found');
    });
});

// ─────────────────────────────── reader: Plan & generate dialog ───────────────────────────────

describe('reader Plan & generate dialog', () => {
    let pbf;
    const SCENE = { objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', pinned: false, dataObjectId: 'd1' };

    beforeEach(async () => {
        pbf = await import('../features/pictureBook.js');
        pbf.__pb2StopWatchingForTest();
        pbf.__setPb2V3StateForTest({
            pb2RenderPath: 'v3', pb2AutoApprove: false, pb2AutoApproveSaving: false, pb2Run: null, pb2ResumeRun: null,
            pb2Submitting: false, pb2CurrentPage: 1, pb2BookObjectId: 'B1', pb2BookName: 'P29', pb2PollIntervalMs: 5,
            pb2Pages: [SCENE]
        });
        pbf.__setPb2PlanStateForTest({ bookId: null, sceneOid: null, plan: null, error: null, loading: false, rendering: false, open: false, inspect: false, preset: '' });
    });
    afterEach(() => {
        pbf.__pb2StopWatchingForTest();
        pbf.__setPb2V3StateForTest({ pb2Run: null, pb2ResumeRun: null });
    });

    function planFetch(handlers) {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = restPath(u);
            let method = (o && o.method) || 'GET';
            calls.push({ method, url, body: o && o.body ? JSON.parse(o.body) : undefined });
            for (let h of handlers) if (h.re.test(method + ' ' + url)) return h.fn(url, o);
            return jsonResponse(404, { error: 'unexpected ' + method + ' ' + url });
        });
        return calls;
    }

    it('v1 path shows neither the Plan & generate button nor the style link', () => {
        pbf.__setPb2V3StateForTest({ pb2RenderPath: 'v1' });
        expect(findByTestId(pbf.renderPb2ScenePage(), 'pb2-plan-render')).toBeNull();
        expect(findByTestId(pbf.renderPb2Header(), 'pb2-style-link')).toBeNull();
        pbf.__setPb2V3StateForTest({ pb2RenderPath: 'v3' });
        expect(findByTestId(pbf.renderPb2ScenePage(), 'pb2-plan-render')).not.toBeNull();
        expect(findByTestId(pbf.renderPb2Header(), 'pb2-style-link')).not.toBeNull();
    });

    it('opens the dialog, POSTs /scene/{s}/plan with an empty body, shows the estimate verbatim and enables Generate', async () => {
        let calls = planFetch([{ re: /^POST .*\/scene\/s1\/plan$/, fn: () => jsonResponse(200, PLAN) }]);
        let openSpy = vi.spyOn(Dialog, 'open');
        findByTestId(pbf.renderPb2ScenePage(), 'pb2-plan-render').attrs.onclick();
        expect(Dialog.stackDepth()).toBe(1);
        let cfg = openSpy.mock.calls[0][0];
        expect(cfg.title).toBe('Plan & generate');
        let body = cfg.content.view();
        expect(findByTestId(body, 'pb2-plan-loading')).not.toBeNull();
        expect(findByTestId(body, 'pb2-plan-preset').attrs.disabled).toBe(true);
        expect(cfg.actions.view().find(a => a.attrs['data-testid'] === 'pb2-plan-generate').disabled).toBe(true);
        await flushPromises();
        expect(calls).toEqual([{ method: 'POST', url: '/rest/olio/picture-book/B1/scene/s1/plan', body: {} }]);
        body = cfg.content.view();
        expect(findByTestId(body, 'pb2-plan-loading')).toBeNull();
        expect(JSON.stringify(findByTestId(body, 'pb2-plan-dialog-strategy'))).toContain('TEXT_ONLY');
        expect(JSON.stringify(findByTestId(body, 'pb2-plan-dialog-estimate'))).toContain('Estimate: ');
        expect(JSON.stringify(findByTestId(body, 'pb2-plan-dialog-estimate'))).toContain('1 scene · 1 stage · 2 variants · 25 steps · NONE: no measurement for this server/family yet');
        expect(JSON.stringify(findByTestId(body, 'pb2-plan-dialog-degradations'))).toContain('MODEL_SUBSTITUTED: Model for LOCATION is unresolved');
        expect(JSON.stringify(body)).toContain('1216 × 832');
        expect(findByTestId(body, 'pb2-plan-inspector')).toBeNull();
        findByTestId(body, 'pb2-plan-inspect-toggle').attrs.onclick();
        body = cfg.content.view();
        expect(JSON.stringify(body)).toContain('"plan"');
        expect(textOf(findByTestId(body, 'pb2-plan-inspect-toggle'))).toBe('Hide plan');
        let actions = cfg.actions.view();
        expect(actions.map(a => a.label)).toEqual(['Close', 'Generate']);
        expect(actions[1].disabled).toBe(false);
        expect(pbf.__pb2PlanStateForTest().sceneTitle).toBe('Arrival');
        // Preset selector re-plans with {preset} — preview only.
        findByTestId(body, 'pb2-plan-preset').attrs.onchange({ target: { value: 'DRAFT' } });
        await flushPromises();
        expect(calls[1]).toEqual({ method: 'POST', url: '/rest/olio/picture-book/B1/scene/s1/plan', body: { preset: 'DRAFT' } });
        expect(JSON.stringify(cfg.content.view())).toContain('The preset above only changes this preview; Generate uses the book style\'s quality preset.');
        actions[0].onclick();
        expect(Dialog.stackDepth()).toBe(0);
        expect(pbf.__pb2PlanStateForTest().open).toBe(false);
    });

    it('a /plan failure is shown verbatim in the dialog and Generate stays disabled', async () => {
        planFetch([{ re: /^POST .*\/plan$/, fn: () => jsonResponse(400, { error: true, message: 'Malformed request body' }) }]);
        let openSpy = vi.spyOn(Dialog, 'open');
        findByTestId(pbf.renderPb2ScenePage(), 'pb2-plan-render').attrs.onclick();
        await flushPromises();
        let cfg = openSpy.mock.calls[0][0];
        expect(textOf(findByTestId(cfg.content.view(), 'pb2-plan-error'))).toBe('(400) Malformed request body');
        expect(cfg.actions.view()[1].disabled).toBe(true);
        expect(Dialog.stackDepth()).toBe(1);
    });

    it('Generate → POST /render-plan; a 503 keeps the dialog open with the server message verbatim (plan artifact id included)', async () => {
        let calls = planFetch([
            { re: /^POST .*\/plan$/, fn: () => jsonResponse(200, PLAN) },
            { re: /^POST .*\/render-plan$/, fn: () => jsonResponse(503, { error: RENDER_503 }) }
        ]);
        let openSpy = vi.spyOn(Dialog, 'open');
        findByTestId(pbf.renderPb2ScenePage(), 'pb2-plan-render').attrs.onclick();
        await flushPromises();
        let cfg = openSpy.mock.calls[0][0];
        let gen = cfg.actions.view()[1];
        expect(gen.disabled).toBe(false);
        let p = gen.onclick();
        expect(findByTestId(cfg.content.view(), 'pb2-plan-rendering')).not.toBeNull();
        expect(cfg.actions.view()[1].label).toBe('Generating…');
        expect(cfg.actions.view()[1].disabled).toBe(true);
        await p;
        expect(calls[1].method + ' ' + calls[1].url).toBe('POST /rest/olio/picture-book/B1/scene/s1/render-plan');
        expect(calls[1].body).toBeUndefined();
        expect(Dialog.stackDepth()).toBe(1);
        let body = cfg.content.view();
        expect(findByTestId(body, 'pb2-plan-rendering')).toBeNull();
        expect(textOf(findByTestId(body, 'pb2-plan-error'))).toBe('(503) ' + RENDER_503);
        expect(cfg.actions.view()[1].disabled).toBe(false);
        expect(page.toast).not.toHaveBeenCalledWith('error', expect.anything());
    });

    it('Generate success closes the dialog, toasts the artifact id and re-reads /pages (+health)', async () => {
        let calls = planFetch([
            { re: /^POST .*\/plan$/, fn: () => jsonResponse(200, PLAN) },
            { re: /^POST .*\/render-plan$/, fn: () => jsonResponse(200, { artifactObjectId: 'art-9', nodeObjectId: 'n1', planArtifactObjectId: PLAN_OID }) },
            { re: /^GET .*\/pages$/, fn: () => jsonResponse(200, [Object.assign({}, SCENE, { artifactObjectId: 'art-9', reviewStatus: 'GENERATED' })]) },
            { re: /^GET .*\/health$/, fn: () => jsonResponse(404, { error: 'none' }) }
        ]);
        let openSpy = vi.spyOn(Dialog, 'open');
        findByTestId(pbf.renderPb2ScenePage(), 'pb2-plan-render').attrs.onclick();
        await flushPromises();
        let cfg = openSpy.mock.calls[0][0];
        await cfg.actions.view()[1].onclick();
        expect(Dialog.stackDepth()).toBe(0);
        expect(page.toast).toHaveBeenCalledWith('success', 'Scene rendered (artifact art-9)');
        expect(calls.map(c => c.method + ' ' + c.url)).toEqual([
            'POST /rest/olio/picture-book/B1/scene/s1/plan',
            'POST /rest/olio/picture-book/B1/scene/s1/render-plan',
            'GET /rest/olio/picture-book/B1/pages',
            'GET /rest/olio/picture-book/B1/health'
        ]);
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.pb.artifact', true);
        expect(pbf.__pb2V3StateForTest().pb2Pages[0].artifactObjectId).toBe('art-9');
        expect(pbf.__pb2PlanStateForTest().open).toBe(false);
        expect(pbf.__pb2PlanStateForTest().rendering).toBe(false);
    });
});
