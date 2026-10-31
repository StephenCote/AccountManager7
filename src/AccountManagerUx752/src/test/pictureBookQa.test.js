// @vitest-environment jsdom
/**
 * PictureBook V3 §17 visual QA in the Ux (Phase 8, P8-5).
 *
 * Why: after a STANDARD/FINAL render the server judges every variant (VLM or Swarm YOLO) and persists one
 * `qa` artifact per image; PbServiceFacade.attachQa then puts the NEWEST report on the judged image's own
 * summary as `qa` in both /workflow/node/{n} and /review-queue (TestPbQaContract case09). The UI must show
 * that verdict where the image is — ✓ / ⚠ / ✗ / ? on the tile, beside the status pill in the review queue,
 * and the full report (checks, expected → observed, note) in the plan inspector — without inventing a
 * verdict for an unjudged image or a check the server did not record. QA is advisory (RenderPlan
 * Degradation.QA_* is non-veto), so nothing here changes selection or review actions.
 *
 * Wire shapes are the live case09 maps (C:\tmp\p85-TestPbQaContract.log:8194, 2026-10-30): QaReport.toMap
 * = { result, provider, model, checks[{ kind, result, expected, observed, detail, actorId }], durationMs,
 * note, imageArtifactObjectId }; Jackson NON_EMPTY drops null note/actorId on the real wire.
 *
 * Offline (fetch mocked). The live leg is e2e/pictureBookQa.spec.js against the Docker stack.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import {
    normalizeArtifact, normalizeQa, qaResultOf, qaBadge, qaFindings, qaReportsForNewestAttempt, groupArtifactsByAttempt
} from '../workflows/pictureBookWorkflow.js';
import { PbPlanInspector, qaEntries } from '../components/PbPlanInspector.js';
import * as canvas from '../features/pictureBookWorkflow.js';
import * as review from '../features/pictureBookReview.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

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

function findAllByTestId(vnode, testid) {
    let out = [];
    (function walk(v) {
        if (!v) return;
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.attrs && v.attrs['data-testid'] === testid) out.push(v);
        walk(v.children);
    })(vnode);
    return out;
}

function textOf(v) {
    if (v == null) return '';
    if (typeof v === 'string' || typeof v === 'number') return String(v);
    if (Array.isArray(v)) return v.map(textOf).join('');
    if (v.tag === '#') return String(v.children);
    return textOf(v.children);
}

function restPath(u) { return String(u).replace(/^.*\/rest\//, '/rest/'); }

// ── fixtures: the case09 node — one attempt, three portrait variants; v0 FAIL, v1 PASS (re-judged), v2 unjudged ──
const RUN = '4099f948-3947-47aa-b37a-a5f723c5d7b9';
const QA_FAIL = {
    result: 'FAIL', provider: 'test-provider', model: 'm', durationMs: 0, imageArtifactObjectId: 'a-v0',
    checks: [
        { kind: 'PERSON_COUNT', result: 'FAIL', expected: '2', observed: '1', detail: 'one figure' },
        { kind: 'ACTOR_PRESENT', result: 'FAIL', expected: 'Tobin', observed: 'absent', detail: 'no second figure', actorId: 'a2' }
    ]
};
const QA_PASS = {
    result: 'PASS', provider: 'test-provider', model: 'm', durationMs: 1234, note: 're-judged', imageArtifactObjectId: 'a-v1',
    checks: [
        { kind: 'PERSON_COUNT', result: 'PASS', expected: '2', observed: '2', detail: 'two figures' },
        { kind: 'ACTOR_PRESENT', result: 'PASS', expected: 'Tobin', observed: 'present', detail: 'right', actorId: 'a2' }
    ]
};
const QA_WARN = { result: 'WARN', provider: 'vlm', model: 'qwen2.5vl:7b', durationMs: 8000, imageArtifactObjectId: 'a-old',
    checks: [{ kind: 'OUTFIT', result: 'WARN', expected: 'red cloak', observed: 'brown coat', detail: 'colour off', actorId: 'a1' }] };
const QA_UNAVAILABLE = { result: 'UNAVAILABLE', provider: 'swarmYolo', note: 'Swarm returned 503', imageArtifactObjectId: 'a-v2x', checks: [] };

// Older attempt (revision 1) carries a WARN so "newest attempt only" is observable; the plan artifact has no attempt.
const CHAIN = [
    { objectId: 'a-old', artifactType: 'IMAGE', role: 'portrait', revision: 1, reviewStatus: 'GENERATED', attempt: 'older-run', seed: 7, dataObjectId: 'd-old', mimeType: 'image/png', byteLength: 100, qa: QA_WARN },
    { objectId: 'a-v0', artifactType: 'IMAGE', role: 'portrait', revision: 2, reviewStatus: 'GENERATED', attempt: RUN, seed: 100, dataObjectId: 'd-v0', mimeType: 'image/png', byteLength: 2048, qa: QA_FAIL },
    { objectId: 'a-v1', artifactType: 'IMAGE', role: 'portrait', revision: 3, reviewStatus: 'APPROVED', selected: true, attempt: RUN, variantIndex: 1, seed: 101, dataObjectId: 'd-v1', mimeType: 'image/png', byteLength: 2048, approvedBy: 'AUTO', approvedAt: '2026-10-30T13:17:10Z', qa: QA_PASS },
    { objectId: 'a-v2', artifactType: 'IMAGE', role: 'portrait', revision: 4, reviewStatus: 'GENERATED', attempt: RUN, variantIndex: 2, seed: 102, dataObjectId: 'd-v2', mimeType: 'image/png', byteLength: 2048 }
];
const QA_CHAIN = [
    { objectId: 'q-old', artifactType: 'TEXT', role: 'qa', revision: 1, reviewStatus: 'GENERATED', attempt: 'older-run' },
    { objectId: 'q-v0', artifactType: 'TEXT', role: 'qa', revision: 2, reviewStatus: 'GENERATED', attempt: RUN },
    { objectId: 'q-v1a', artifactType: 'TEXT', role: 'qa', revision: 3, reviewStatus: 'GENERATED', attempt: RUN, variantIndex: 1 },
    { objectId: 'q-v1b', artifactType: 'TEXT', role: 'qa', revision: 4, reviewStatus: 'GENERATED', attempt: RUN, variantIndex: 1 }
];
const PLAN_OID = 'plan-oid-1';
const PLAN = {
    planVersion: 'illustration/1', purpose: 'PORTRAIT', canvas: { width: 832, height: 1216 },
    preset: { preset: 'STANDARD', steps: 25, variants: 3, upscale: false, qa: 'CANONICAL' },
    strategy: { name: 'TEXT_ONLY', stages: ['BASE'], reason: 'r' }, models: {}, compiled: { BASE: { prompt: 'p' } },
    degradations: [], estimate: { scenes: 1, stages: 1, variants: 3, steps: 25, measured: false, source: 'NONE' }, contentHash: 'h1',
    config: { model: 'x.safetensors', sampler: 'euler', scheduler: 'karras', cfg: 7, seed: -1, steps: 25, width: 832, height: 1216 }
};
const PLAN_ARTIFACT = { objectId: PLAN_OID, artifactType: 'TEXT', artifactText: JSON.stringify(PLAN), backend: 'PLAN', revision: 1, role: 'renderPlan', reviewStatus: 'GENERATED', selected: true };
const ARTIFACTS = { portrait: CHAIN, qa: QA_CHAIN, renderPlan: [{ objectId: PLAN_OID, artifactType: 'TEXT', role: 'renderPlan', revision: 1, reviewStatus: 'GENERATED', selected: true }] };
const GRAPH = { nodes: [{ objectId: 'n1', nodeType: 'PORTRAIT', handle: 'tobin', status: 'DONE' }], edges: [] };

let origFetch;
beforeEach(() => {
    origFetch = global.fetch;
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(am7client, 'get').mockImplementation((type, id, cb) => { cb({ objectId: id, groupPath: '/PictureBook/x', name: id + '.png' }); });
});
afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── pure helpers ───────────────────────────────

describe('normalizeQa / qaResultOf / qaBadge / qaFindings', () => {
    it('normalizeQa: null for an unjudged image, every omission filled, result names uppercased, unknown → UNAVAILABLE', () => {
        expect(normalizeQa(undefined)).toBeNull();
        expect(normalizeQa(null)).toBeNull();
        expect(normalizeQa('FAIL')).toBeNull();
        let q = normalizeQa(QA_FAIL);
        expect(q).toMatchObject({ result: 'FAIL', provider: 'test-provider', model: 'm', note: null, durationMs: 0, imageArtifactObjectId: 'a-v0' });
        expect(q.checks).toEqual([
            { kind: 'PERSON_COUNT', result: 'FAIL', expected: '2', observed: '1', detail: 'one figure', actorId: null },
            { kind: 'ACTOR_PRESENT', result: 'FAIL', expected: 'Tobin', observed: 'absent', detail: 'no second figure', actorId: 'a2' }
        ]);
        expect(normalizeQa({ result: 'pass' }).result).toBe('PASS');
        expect(normalizeQa({ result: 'bogus', checks: [{ kind: 'IDENTITY', result: 'maybe' }] })).toMatchObject({ result: 'UNAVAILABLE', checks: [{ kind: 'IDENTITY', result: 'UNAVAILABLE', expected: null, observed: null, detail: null, actorId: null }] });
        expect(normalizeQa({}).checks).toEqual([]);
        expect(['PASS', 'warn', 'Fail', 'unavailable', '', null, 'x'].map(qaResultOf)).toEqual(['PASS', 'WARN', 'FAIL', 'UNAVAILABLE', 'UNAVAILABLE', 'UNAVAILABLE', 'UNAVAILABLE']);
    });
    it('normalizeArtifact carries qa through normalizeQa; an artifact without one reads qa: null', () => {
        expect(normalizeArtifact(CHAIN[3]).qa).toBeNull();
        expect(normalizeArtifact(CHAIN[1]).qa.result).toBe('FAIL');
        expect(normalizeArtifact(CHAIN[2]).qa.note).toBe('re-judged');
        // groupArtifactsByAttempt normalizes each row, so the tiles see the same shape
        let g = groupArtifactsByAttempt(CHAIN);
        expect(g.attempts[0].variants.map(a => a.qa && a.qa.result)).toEqual(['FAIL', 'PASS', null]);
    });
    it('qaBadge: ✓ PASS / ⚠ WARN / ✗ FAIL / ? UNAVAILABLE with distinct colours; null for no report', () => {
        expect(qaBadge(null)).toBeNull();
        expect(qaBadge(QA_PASS)).toMatchObject({ result: 'PASS', glyph: '✓', label: 'QA pass' });
        expect(qaBadge(QA_WARN)).toMatchObject({ result: 'WARN', glyph: '⚠', label: 'QA warn' });
        expect(qaBadge(QA_FAIL)).toMatchObject({ result: 'FAIL', glyph: '✗', label: 'QA fail' });
        expect(qaBadge(QA_UNAVAILABLE)).toMatchObject({ result: 'UNAVAILABLE', glyph: '?', label: 'QA unavailable' });
        expect(qaBadge({ result: 'nope' }).glyph).toBe('?');
        let colours = [QA_PASS, QA_WARN, QA_FAIL, QA_UNAVAILABLE].map(q => qaBadge(q).bg);
        expect(new Set(colours).size).toBe(4);
    });
    it('qaFindings: only the non-PASS checks, one line each; a PASS report has none; UNAVAILABLE without checks falls back to the note', () => {
        expect(qaFindings(QA_FAIL)).toEqual([
            'PERSON_COUNT fail: expected 2, observed 1 — one figure',
            'ACTOR_PRESENT a2 fail: expected Tobin, observed absent — no second figure'
        ]);
        expect(qaFindings(QA_PASS)).toEqual([]);
        expect(qaFindings(QA_WARN)).toEqual(['OUTFIT a1 warn: expected red cloak, observed brown coat — colour off']);
        expect(qaFindings(QA_UNAVAILABLE)).toEqual(['Swarm returned 503']);
        expect(qaFindings(null)).toEqual([]);
    });
});

describe('qaReportsForNewestAttempt', () => {
    it('lists the judged images of the NEWEST attempt per image role, by variantIndex; skips qa/renderPlan roles and unjudged images', () => {
        let out = qaReportsForNewestAttempt(ARTIFACTS);
        expect(out.map(e => [e.role, e.artifact.objectId, e.qa.result])).toEqual([['portrait', 'a-v0', 'FAIL'], ['portrait', 'a-v1', 'PASS']]);
        // the older attempt's WARN is not in the list; the plan artifact (no attempt) never is
        expect(out.some(e => e.artifact.objectId === 'a-old')).toBe(false);
        expect(qaReportsForNewestAttempt({ renderPlan: ARTIFACTS.renderPlan, qa: QA_CHAIN })).toEqual([]);
        expect(qaReportsForNewestAttempt(null)).toEqual([]);
        expect(qaReportsForNewestAttempt({ image: [] })).toEqual([]);
    });
    it('a newest attempt with no judged image yields []; a second role contributes its own newest attempt', () => {
        expect(qaReportsForNewestAttempt({ portrait: [CHAIN[3]] })).toEqual([]);
        let two = qaReportsForNewestAttempt({ portrait: CHAIN, image: [{ objectId: 'i1', role: 'image', revision: 9, attempt: 'zz', qa: QA_WARN }] });
        expect(two.map(e => e.role + ':' + e.artifact.objectId)).toEqual(['portrait:a-v0', 'portrait:a-v1', 'image:i1']);
    });
});

// ─────────────────────────────── plan inspector: Visual QA section ───────────────────────────────

describe('PbPlanInspector qa attr', () => {
    it('qaEntries: null when the attr is absent (section omitted); entries with a normalized report; unjudged entries dropped', () => {
        expect(qaEntries(undefined)).toBeNull();
        expect(qaEntries(null)).toBeNull();
        expect(qaEntries([])).toEqual([]);
        let e = qaEntries([{ role: 'portrait', artifact: { objectId: 'a-v1', variantIndex: 1 }, qa: QA_PASS }, { role: 'portrait', artifact: { objectId: 'a-v2' }, qa: null }]);
        expect(e.length).toBe(1);
        expect(e[0]).toMatchObject({ role: 'portrait', artifactObjectId: 'a-v1', variantIndex: 1 });
        expect(e[0].qa.result).toBe('PASS');
    });
    it('without a qa attr the inspector renders no Visual QA section (the scene-plan preview has no artifacts yet)', () => {
        let v = PbPlanInspector.view({ attrs: { plan: PLAN } });
        expect(findByTestId(v, 'pb2-plan-inspector')).not.toBeNull();
        expect(findByTestId(v, 'pb2-plan-qa')).toBeNull();
        expect(JSON.stringify(v)).not.toContain('Visual QA');
    });
    it('with reports: one pb2-plan-qa-report per judged image (verdict pill, provider/model, note) and one pb2-plan-qa-check per check', () => {
        let v = PbPlanInspector.view({ attrs: { plan: PLAN, qa: qaReportsForNewestAttempt(ARTIFACTS) } });
        let section = findByTestId(v, 'pb2-plan-qa');
        expect(section).not.toBeNull();
        expect(section.attrs['data-count']).toBe('2');
        let reports = findAllByTestId(v, 'pb2-plan-qa-report');
        expect(reports.map(r => [r.attrs['data-artifact-id'], r.attrs['data-result'], r.attrs['data-role']])).toEqual([['a-v0', 'FAIL', 'portrait'], ['a-v1', 'PASS', 'portrait']]);
        expect(textOf(reports[0])).toContain('portrait v0');
        expect(textOf(reports[0])).toContain('✗ FAIL');
        expect(textOf(reports[0])).toContain('test-provider / m');
        expect(textOf(reports[0])).not.toContain('re-judged');
        expect(textOf(reports[1])).toContain('portrait v1');
        expect(textOf(reports[1])).toContain('✓ PASS');
        expect(textOf(reports[1])).toContain('re-judged');
        expect(textOf(reports[1])).toContain('1.2 s');
        let checks = findAllByTestId(reports[0], 'pb2-plan-qa-check');
        expect(checks.map(c => [c.attrs['data-kind'], c.attrs['data-result']])).toEqual([['PERSON_COUNT', 'FAIL'], ['ACTOR_PRESENT', 'FAIL']]);
        expect(textOf(checks[0])).toContain('PERSON_COUNT');
        expect(textOf(checks[0])).toContain('expected 2 → observed 1 — one figure');
        expect(textOf(checks[1])).toContain('ACTOR_PRESENT a2');
        expect(textOf(checks[1])).toContain('expected Tobin → observed absent — no second figure');
        // the preset still says what the plan asked for
        expect(JSON.stringify(v)).toContain('CANONICAL');
    });
    it('an empty qa array says so in words rather than hiding the section', () => {
        let v = PbPlanInspector.view({ attrs: { plan: PLAN, qa: [] } });
        let section = findByTestId(v, 'pb2-plan-qa');
        expect(section.attrs['data-count']).toBe('0');
        expect(textOf(section)).toBe('No visual-QA reports for this attempt.');
        expect(findAllByTestId(v, 'pb2-plan-qa-report').length).toBe(0);
    });
    it('an UNAVAILABLE report with no checks shows the note and no check rows', () => {
        let v = PbPlanInspector.view({ attrs: { plan: PLAN, qa: [{ role: 'portrait', artifact: { objectId: 'a-v2x', variantIndex: 2 }, qa: QA_UNAVAILABLE }] } });
        let r = findByTestId(v, 'pb2-plan-qa-report');
        expect(r.attrs['data-result']).toBe('UNAVAILABLE');
        expect(textOf(r)).toContain('? UNAVAILABLE');
        expect(textOf(r)).toContain('Swarm returned 503');
        expect(textOf(r)).toContain('swarmYolo');
        expect(findAllByTestId(v, 'pb2-plan-qa-check').length).toBe(0);
    });
});

// ─────────────────────────────── canvas: tile badge, tooltip, compare, inspector wiring ───────────────────────────────

describe('canvas node detail: QA on the artifact tiles', () => {
    beforeEach(() => {
        canvas.__setCanvasStateForTest({ graphData: GRAPH, pb2BookObjectId: 'B1', selectedNodeId: 'n1', nodeDetailsLoading: false,
            nodeDetails: { artifacts: ARTIFACTS, bindings: [] }, nodePlan: { nodeId: 'n1' }, artifactUi: { showArchived: false, compare: [] } });
    });
    it('judged tiles carry data-qa-result and a ✓/⚠/✗ badge; an unjudged tile carries neither; the tooltip ends with the verdict and findings', () => {
        let v = canvas.renderNodeDetailPanel();
        let tiles = findAllByTestId(v, 'pbwf-art-tile').filter(t => /^a-/.test(t.attrs['data-artifact-id']));
        expect(tiles.map(t => t.attrs['data-artifact-id'])).toEqual(['a-v0', 'a-v1', 'a-v2', 'a-old']);
        expect(tiles.map(t => t.attrs['data-qa-result'])).toEqual(['FAIL', 'PASS', undefined, 'WARN']);
        let badges = tiles.map(t => findByTestId(t, 'pbwf-art-qa-badge'));
        expect(badges.map(b => b && b.attrs['data-qa-result'])).toEqual(['FAIL', 'PASS', null, 'WARN']);
        expect(badges.map(b => b && textOf(b))).toEqual(['✗', '✓', null, '⚠']);
        expect(tiles[0].attrs.title).toBe('Rev 2 · v0 · seed 100 · generated · qa fail (test-provider)\nPERSON_COUNT fail: expected 2, observed 1 — one figure\nACTOR_PRESENT a2 fail: expected Tobin, observed absent — no second figure');
        expect(tiles[1].attrs.title).toBe('Rev 3 · v1 · seed 101 · current · approved · qa pass (test-provider)');
        expect(tiles[2].attrs.title).toBe('Rev 4 · v2 · seed 102 · generated');
        // the qa chain itself is a TEXT role and never gets a verdict badge of its own
        let qaTiles = findAllByTestId(v, 'pbwf-art-tile').filter(t => /^q-/.test(t.attrs['data-artifact-id']));
        expect(qaTiles.length).toBe(4);
        expect(qaTiles.every(t => t.attrs['data-qa-result'] === undefined && findByTestId(t, 'pbwf-art-qa-badge') === null)).toBe(true);
    });
    it('the compare pane caption carries the verdict for a judged pick and nothing for an unjudged one', () => {
        canvas.__setCanvasStateForTest({ artifactUi: { showArchived: false, compare: ['a-v0', 'a-v2'] } });
        let v = canvas.renderNodeDetailPanel();
        let items = findAllByTestId(v, 'pbwf-art-compare-item');
        expect(items.length).toBe(2);
        expect(textOf(items[0])).toContain('A portrait r2 v0 · seed 100 · generated · 2.0 KB · qa fail');
        expect(findByTestId(items[0], 'pbwf-art-compare-qa').attrs['data-qa-result']).toBe('FAIL');
        expect(textOf(items[1])).toBe('B portrait r4 v2 · seed 102 · generated · 2.0 KB');
        expect(findByTestId(items[1], 'pbwf-art-compare-qa')).toBeNull();
    });
    it('View plan hands the newest attempt\'s reports to the inspector, which renders the Visual QA section', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push(((o && o.method) || 'GET') + ' ' + restPath(u)); return jsonResponse(200, PLAN_ARTIFACT); });
        await canvas.toggleNodePlan();
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/artifact/' + PLAN_OID]);
        let v = canvas.renderNodeDetailPanel();
        let panel = findByTestId(v, 'pbwf-node-plan');
        // the inspector is a component vnode: find it, then render it to check its section
        let comp = (function find(x) {
            if (!x) return null;
            if (Array.isArray(x)) { for (let c of x) { let r = find(c); if (r) return r; } return null; }
            if (x.tag === PbPlanInspector) return x;
            return find(x.children);
        })(panel);
        expect(comp).not.toBeNull();
        expect(comp.attrs.qa.map(e => e.artifact.objectId)).toEqual(['a-v0', 'a-v1']);
        let rendered = PbPlanInspector.view({ attrs: comp.attrs });
        expect(findAllByTestId(rendered, 'pb2-plan-qa-report').map(r => r.attrs['data-result'])).toEqual(['FAIL', 'PASS']);
    });
});

// ─────────────────────────────── review queue: badge beside the status pill + inline report ───────────────────────────────

describe('review queue: QA badge and inline report', () => {
    const NODE = { nodeObjectId: 'n1', handle: 'tobin', sceneIndex: null, nodeType: 'PORTRAIT', nodeStatus: 'DONE', pinned: false, artifacts: CHAIN };
    beforeEach(() => {
        review.__setReviewStateForTest({ bookObjectId: 'B1', queue: { bookObjectId: 'B1', filter: 'all', autoApprove: false, pendingCount: 2, artifactCount: 4, nodes: [NODE] }, loading: false, error: null, filter: 'all', busyArtifact: null, nodePlans: {}, qaOpen: {} });
    });
    it('each judged row carries data-qa-result and a pb2-review-qa badge; the unjudged row has none; actions are unchanged by the verdict', () => {
        let v = review.renderReviewBody();
        let rows = findAllByTestId(v, 'pb2-review-artifact');
        expect(rows.map(r => r.attrs['data-artifact-id'])).toEqual(['a-v2', 'a-v1', 'a-v0', 'a-old']);
        expect(rows.map(r => r.attrs['data-qa-result'])).toEqual([undefined, 'PASS', 'FAIL', 'WARN']);
        let badges = rows.map(r => findByTestId(r, 'pb2-review-qa'));
        expect(badges.map(b => b && textOf(b))).toEqual([null, '✓ PASS', '✗ FAIL', '⚠ WARN']);
        expect(badges[2].attrs.title).toBe('QA fail (test-provider)\nPERSON_COUNT fail: expected 2, observed 1 — one figure\nACTOR_PRESENT a2 fail: expected Tobin, observed absent — no second figure');
        expect(badges[2].attrs['data-open']).toBe('0');
        expect(findAllByTestId(v, 'pb2-review-qa-report').length).toBe(0);
        // a FAIL verdict does not disable Use this / Approve (QA is advisory)
        let failRow = rows[2];
        expect(findByTestId(failRow, 'pb2-review-use').attrs.disabled).toBe(false);
        expect(findByTestId(failRow, 'pb2-review-approve').attrs.disabled).toBe(false);
        expect(findByTestId(rows[1], 'pb2-review-auto-mark')).not.toBeNull();
    });
    it('clicking the badge expands the inline report (provider/model, note, one row per check); clicking again collapses it', () => {
        let v = review.renderReviewBody();
        let fail = findAllByTestId(v, 'pb2-review-artifact')[2];
        findByTestId(fail, 'pb2-review-qa').attrs.onclick({ stopPropagation() {} });
        expect(review.__reviewStateForTest().qaOpen).toEqual({ 'a-v0': true });
        v = review.renderReviewBody();
        fail = findAllByTestId(v, 'pb2-review-artifact')[2];
        expect(findByTestId(fail, 'pb2-review-qa').attrs['data-open']).toBe('1');
        let rep = findByTestId(fail, 'pb2-review-qa-report');
        expect(rep).not.toBeNull();
        expect(rep.attrs['data-result']).toBe('FAIL');
        expect(textOf(rep)).toContain('test-provider / m');
        let checks = findAllByTestId(rep, 'pb2-review-qa-check');
        expect(checks.map(c => c.attrs['data-kind'])).toEqual(['PERSON_COUNT', 'ACTOR_PRESENT']);
        expect(textOf(checks[0])).toContain('expected 2 → observed 1 — one figure');
        // only the clicked row opened
        expect(findAllByTestId(v, 'pb2-review-qa-report').length).toBe(1);
        // the PASS row shows its note + 1.2 s when opened
        review.toggleQaReport('a-v1');
        v = review.renderReviewBody();
        let pass = findByTestId(findAllByTestId(v, 'pb2-review-artifact')[1], 'pb2-review-qa-report');
        expect(textOf(pass)).toContain('re-judged');
        expect(textOf(pass)).toContain('1.2 s');
        review.toggleQaReport('a-v0');
        review.toggleQaReport('a-v1');
        expect(findAllByTestId(review.renderReviewBody(), 'pb2-review-qa-report').length).toBe(0);
    });
    it('"Plan" stores the newest attempt\'s reports on the node state and the inspector shows them', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = restPath(u);
            calls.push(((o && o.method) || 'GET') + ' ' + url);
            if (/\/workflow\/node\/n1$/.test(url)) return jsonResponse(200, { objectId: 'n1', nodeType: 'PORTRAIT', artifacts: ARTIFACTS, bindings: [] });
            if (/\/artifact\//.test(url)) return jsonResponse(200, PLAN_ARTIFACT);
            return jsonResponse(404, { error: 'unexpected ' + url });
        });
        await review.toggleNodePlan('n1');
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/workflow/node/n1', 'GET /rest/olio/picture-book/B1/artifact/' + PLAN_OID]);
        let st = review.__reviewStateForTest().nodePlans.n1;
        expect(st.qa.map(e => [e.artifact.objectId, e.qa.result])).toEqual([['a-v0', 'FAIL'], ['a-v1', 'PASS']]);
        let v = review.renderReviewBody();
        let panel = findByTestId(v, 'pb2-review-plan');
        expect(panel).not.toBeNull();
        let comp = (function find(x) {
            if (!x) return null;
            if (Array.isArray(x)) { for (let c of x) { let r = find(c); if (r) return r; } return null; }
            if (x.tag === PbPlanInspector) return x;
            return find(x.children);
        })(panel);
        expect(comp.attrs.qa).toBe(st.qa);
        let rendered = PbPlanInspector.view({ attrs: comp.attrs });
        expect(findByTestId(rendered, 'pb2-plan-qa').attrs['data-count']).toBe('2');
    });
});
