// @vitest-environment jsdom
/**
 * PictureBook V3 §23 variants + retention UI (Phase 6, P6-4).
 *
 * Why: a chain now carries N variants per attempt (attempt = run objectId, variantIndex 0..N-1, seed =
 * seed0 + i) plus ARCHIVED rows left behind by the retention sweep, and the node detail strip must show
 * that structure as the server reports it — grouped by attempt, newest first, archived hidden until asked,
 * "bytes deleted" when the data FK was cleared — and never invent a seed, an attempt or a status. The
 * retention dialog is dry-run → apply over GET/POST /{book}/retention[/apply] with K/D passed through
 * verbatim (the server validates: a 400 is shown, not swallowed) and Apply enabled only for a dry run of
 * the CURRENT inputs with ≥1 actionable item. Wire shapes follow PbServiceFacade.artifactSummary and
 * PbRetentionUtil.Sweep.toMap (Include.NON_EMPTY, so absent keys are the norm).
 *
 * Offline (fetch mocked). The live leg is e2e/pictureBookVariants.spec.js against the Docker stack.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7model } from '../core/model.js';
import {
    normalizeArtifact, isArchivedArtifact, groupArtifactsByAttempt, formatBytes,
    normalizeRetention, retentionActionable, scanRetention, applyRetention
} from '../workflows/pictureBookWorkflow.js';
import * as canvas from '../features/pictureBookWorkflow.js';

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
const tick = () => new Promise(r => setTimeout(r, 0));

function renderDialogsInto() {
    let container = document.createElement('div');
    document.body.appendChild(container);
    m.render(container, Dialog.loadDialogs());
    return container;
}
function rerender(container) { m.render(container, Dialog.loadDialogs()); return container; }
function byTestId(container, id) { return container.querySelector('[data-testid="' + id + '"]'); }

// ── fixtures: one portrait node, two attempts × two variants, one archived pre-P6 row ────────────
// Revision order on the wire is ascending; attempt R2 is the newest (revisions 4,5); R1 is 2,3; the
// pre-P6 row (no attempt) is revision 1 and has been ARCHIVED with its bytes deleted.
const RUN1 = 'aaaaaaaa-run-1';
const RUN2 = 'bbbbbbbb-run-2';
const CHAIN = [
    { objectId: 'a-old', artifactType: 'IMAGE', role: 'image', revision: 1, reviewStatus: 'ARCHIVED', archivedAt: '2026-10-01T00:00:00Z', bytesDeleted: true, byteLength: 4096, mimeType: 'image/png' },
    { objectId: 'a-r1v0', artifactType: 'IMAGE', role: 'image', revision: 2, reviewStatus: 'GENERATED', attempt: RUN1, seed: 100, dataObjectId: 'd-r1v0', mimeType: 'image/png', byteLength: 1536 },
    { objectId: 'a-r1v1', artifactType: 'IMAGE', role: 'image', revision: 3, reviewStatus: 'REJECTED', attempt: RUN1, variantIndex: 1, seed: 101, dataObjectId: 'd-r1v1', mimeType: 'image/png', byteLength: 1536 },
    { objectId: 'a-r2v0', artifactType: 'IMAGE', role: 'image', revision: 4, reviewStatus: 'APPROVED', selected: true, attempt: RUN2, seed: 200, dataObjectId: 'd-r2v0', mimeType: 'image/png', byteLength: 2048, approvedBy: 'u', approvedAt: '2026-10-02T00:00:00Z' },
    { objectId: 'a-r2v1', artifactType: 'IMAGE', role: 'image', revision: 5, reviewStatus: 'GENERATED', attempt: RUN2, variantIndex: 1, seed: 201, dataObjectId: 'd-r2v1', mimeType: 'image/png', byteLength: 2048 },
];
const GRAPH = { nodes: [{ objectId: 'n1', nodeType: 'PORTRAIT', handle: 'hero', status: 'DONE' }], edges: [] };

// PbRetentionUtil.Sweep.toMap for the chain above with K=1: R1's two variants archive; the old archived row's
// bytes are already gone (NONE); R2 kept (one selected/approved, one by K).
const SCAN = {
    scope: 'book', bookObjectId: 'B1', slug: 'pb63-retention', checkedAt: '2026-10-29T10:00:00Z',
    keepAttempts: 1, deleteBytesAfterDays: 30, nodes: 1, total: 5,
    counts: { keep: 2, archive: 2, deleteBytes: 0, none: 1 }, bytesReclaimable: 3072,
    items: [
        { objectId: 'a-r2v0', nodeObjectId: 'n1', nodeHandle: 'hero', role: 'image', attempt: RUN2, revision: 4, reviewStatus: 'APPROVED', selected: true, hasBytes: true, byteLength: 2048, action: 'KEEP', reason: 'selected' },
        { objectId: 'a-r2v1', nodeObjectId: 'n1', nodeHandle: 'hero', role: 'image', attempt: RUN2, variantIndex: 1, revision: 5, reviewStatus: 'GENERATED', hasBytes: true, byteLength: 2048, action: 'KEEP', reason: 'within keepAttempts' },
        { objectId: 'a-r1v0', nodeObjectId: 'n1', nodeHandle: 'hero', role: 'image', attempt: RUN1, revision: 2, reviewStatus: 'GENERATED', hasBytes: true, byteLength: 1536, action: 'ARCHIVE', reason: 'attempt 2 of 2 exceeds keepAttempts=1' },
        { objectId: 'a-r1v1', nodeObjectId: 'n1', nodeHandle: 'hero', role: 'image', attempt: RUN1, variantIndex: 1, revision: 3, reviewStatus: 'REJECTED', hasBytes: true, byteLength: 1536, action: 'ARCHIVE', reason: 'attempt 2 of 2 exceeds keepAttempts=1' },
        { objectId: 'a-old', nodeObjectId: 'n1', nodeHandle: 'hero', role: 'image', revision: 1, reviewStatus: 'ARCHIVED', archivedAt: '2026-10-01T00:00:00Z', action: 'NONE', reason: 'bytes already deleted' },
    ]
};
const APPLIED = Object.assign({}, SCAN, {
    checkedAt: undefined, appliedAt: '2026-10-29T10:01:00Z', archived: 2, bytesDeleted: 0, bytesFreed: 0, refused: 0, denied: 0, failed: 0,
    items: SCAN.items.map(it => Object.assign({}, it, { outcome: it.action === 'ARCHIVE' ? 'archived' : 'kept' }))
});

// ─────────────────────────────── pure helpers ───────────────────────────────

describe('normalizeArtifact / groupArtifactsByAttempt (§23 variants)', () => {
    it('fills the NON_EMPTY omissions without inventing: variantIndex 0, seed null, attempt null, bytesDeleted false', () => {
        let a = normalizeArtifact({ objectId: 'x', revision: 2, reviewStatus: 'generated' });
        expect(a).toMatchObject({ objectId: 'x', revision: 2, variantIndex: 0, seed: null, attempt: null, archivedAt: null, bytesDeleted: false, byteLength: 0, selected: false });
        let b = normalizeArtifact({ objectId: 'y', attempt: RUN1, variantIndex: '1', seed: '101', archivedAt: '2026-10-01T00:00:00Z', bytesDeleted: true, byteLength: '12' });
        expect(b).toMatchObject({ attempt: RUN1, variantIndex: 1, seed: 101, archivedAt: '2026-10-01T00:00:00Z', bytesDeleted: true, byteLength: 12 });
        expect(normalizeArtifact(null).variantIndex).toBe(0);
    });
    it('seed 0 is "no seed": the column is a long with DDL default 0, so TEXT / renderPlan / pre-P6 rows arrive as seed: 0', () => {
        // Found live on the Docker stack (Playwright pictureBookVariants.spec.js): Jackson NON_EMPTY keeps numeric 0,
        // so every artifact without a real seed carried `seed: 0` and the strip rendered an " · s0" badge on it.
        expect(normalizeArtifact({ objectId: 'z', revision: 1, reviewStatus: 'archived', seed: 0 }).seed).toBeNull();
        expect(normalizeArtifact({ objectId: 'z', seed: '0' }).seed).toBeNull();
        expect(normalizeArtifact({ objectId: 'z', seed: -1 }).seed).toBeNull();
        expect(normalizeArtifact({ objectId: 'z', seed: 1 }).seed).toBe(1);
    });
    it('isArchivedArtifact is case-insensitive (enums are lowercase on the wire, UPPERCASE in Java)', () => {
        expect(isArchivedArtifact({ reviewStatus: 'archived' })).toBe(true);
        expect(isArchivedArtifact({ reviewStatus: 'ARCHIVED' })).toBe(true);
        expect(isArchivedArtifact({ reviewStatus: 'rejected' })).toBe(false);
        expect(isArchivedArtifact(null)).toBe(false);
    });
    it('groups by attempt, newest attempt first, variants by variantIndex; a no-attempt row is its own group', () => {
        let g = groupArtifactsByAttempt(CHAIN);
        expect(g.archivedCount).toBe(1);
        expect(g.active).toBe(4);
        expect(g.attempts.map(x => x.attempt)).toEqual([RUN2, RUN1, null]);
        expect(g.attempts[0].variants.map(v => v.objectId)).toEqual(['a-r2v0', 'a-r2v1']);
        expect(g.attempts[0].variants.map(v => v.variantIndex)).toEqual([0, 1]);
        expect(g.attempts[0].variants.map(v => v.seed)).toEqual([200, 201]);
        expect(g.attempts[0].label).toBe('Attempt bbbbbbbb · 2 variants');
        expect(g.attempts[1].maxRevision).toBe(3);
        expect(g.attempts[2].label).toBe('Rev 1');
        expect(g.attempts[2].archivedCount).toBe(1);
        expect(g.attempts[2].variants[0].bytesDeleted).toBe(true);
    });
    it('variants that arrive out of revision order still sort by variantIndex; two no-attempt rows are NOT merged', () => {
        let g = groupArtifactsByAttempt([
            { objectId: 'v1', revision: 7, attempt: 'r', variantIndex: 1 },
            { objectId: 'v0', revision: 6, attempt: 'r', variantIndex: 0 },
            { objectId: 'solo-a', revision: 1 },
            { objectId: 'solo-b', revision: 2 },
        ]);
        expect(g.attempts.length).toBe(3);
        expect(g.attempts[0].variants.map(v => v.objectId)).toEqual(['v0', 'v1']);
        expect(g.attempts.slice(1).map(x => x.variants.length)).toEqual([1, 1]);
    });
    it('null / empty / malformed lists → empty grouping', () => {
        expect(groupArtifactsByAttempt(null)).toEqual({ attempts: [], archivedCount: 0, active: 0 });
        expect(groupArtifactsByAttempt([])).toEqual({ attempts: [], archivedCount: 0, active: 0 });
        expect(groupArtifactsByAttempt([null, undefined]).attempts).toEqual([]);
    });
    it('formatBytes', () => {
        expect(formatBytes(0)).toBe('0 B');
        expect(formatBytes(null)).toBe('0 B');
        expect(formatBytes(512)).toBe('512 B');
        expect(formatBytes(1536)).toBe('1.5 KB');
        expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB');
    });
});

describe('normalizeRetention / retentionActionable (PbRetentionUtil.Sweep.toMap)', () => {
    it('fills every count / list the server may omit and upper-cases actions', () => {
        let s = normalizeRetention({ scope: 'book', total: 0 });
        expect(s).toMatchObject({ scope: 'book', total: 0, nodes: 0, items: [], warnings: [], counts: { keep: 0, archive: 0, deleteBytes: 0, none: 0 }, bytesReclaimable: 0, archived: 0, bytesDeleted: 0, detached: 0, applied: false });
        let t = normalizeRetention({ appliedAt: 'x', items: [{ objectId: 'i', action: 'archive', outcome: 'archived', byteLength: '7' }] });
        expect(t.applied).toBe(true);
        expect(t.items[0]).toMatchObject({ action: 'ARCHIVE', outcome: 'archived', byteLength: 7, variantIndex: 0, attempt: null, selected: false });
    });
    it('carries the `detached` apply count and outcome (a shared data.data row: reference cleared, bytes kept)', () => {
        let d = normalizeRetention({ appliedAt: 'x', archived: 0, bytesDeleted: 0, detached: '1', bytesFreed: 0,
            items: [{ objectId: 's0', action: 'DELETE_BYTES', outcome: 'detached', outcomeReason: 'data.data 9 is shared with artifact l0; reference cleared, row kept', hasBytes: false, byteLength: 5000 }] });
        expect(d.detached).toBe(1);
        expect(d.bytesFreed).toBe(0);
        expect(d.items[0]).toMatchObject({ action: 'DELETE_BYTES', outcome: 'detached', hasBytes: false, bytesFreed: 0 });
        expect(d.items[0].outcomeReason).toContain('shared with artifact l0');
    });
    it('retentionActionable keeps only ARCHIVE and DELETE_BYTES items', () => {
        expect(retentionActionable(SCAN).map(i => i.objectId)).toEqual(['a-r1v0', 'a-r1v1']);
        expect(retentionActionable(null)).toEqual([]);
    });
});

describe('scanRetention / applyRetention wire contract', () => {
    afterEach(() => { vi.restoreAllMocks(); });
    it('GET /{book}/retention with K/D as query params only when given; POST …/apply with a JSON body', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push({ m: (o && o.method) || 'GET', p: restPath(u), b: o && o.body }); return jsonResponse(200, SCAN); });
        let s = await scanRetention('B1', {});
        expect(s.counts.archive).toBe(2);
        await scanRetention('B1', { keepAttempts: '1', deleteBytesAfterDays: '' });
        await scanRetention('B1', { keepAttempts: 2, deleteBytesAfterDays: 0 });
        await applyRetention('B1', { keepAttempts: '1' });
        await applyRetention('B1', {});
        expect(calls.map(c => c.m + ' ' + c.p)).toEqual([
            'GET /rest/olio/picture-book/B1/retention',
            'GET /rest/olio/picture-book/B1/retention?keepAttempts=1',
            'GET /rest/olio/picture-book/B1/retention?keepAttempts=2&deleteBytesAfterDays=0',
            'POST /rest/olio/picture-book/B1/retention/apply',
            'POST /rest/olio/picture-book/B1/retention/apply',
        ]);
        expect(JSON.parse(calls[3].b)).toEqual({ keepAttempts: 1 });
        expect(JSON.parse(calls[4].b)).toEqual({});
    });
    it('a server 400 (out-of-range K) surfaces as "(400) message", not a silent default', async () => {
        global.fetch = vi.fn(async () => jsonResponse(400, { error: 'keepAttempts must be between 1 and 20' }));
        await expect(scanRetention('B1', { keepAttempts: 99 })).rejects.toThrow('(400) keepAttempts must be between 1 and 20');
    });
});

// ─────────────────────────────── node detail strip ───────────────────────────────

describe('canvas node detail: attempt-grouped variant strip', () => {
    beforeEach(() => {
        canvas.__setCanvasStateForTest({ graphData: GRAPH, pb2BookObjectId: 'B1', selectedNodeId: 'n1', nodeDetailsLoading: false,
            nodeDetails: { artifacts: { image: CHAIN }, bindings: [] }, nodePlan: { nodeId: 'n1' }, artifactUi: { showArchived: false, compare: [] } });
    });
    it('renders one group per attempt, newest first, with v{n} and seed badges; archived hidden behind "Show archived (1)"', () => {
        let v = canvas.renderNodeDetailPanel();
        let role = findByTestId(v, 'pbwf-art-role');
        expect(role.attrs['data-role']).toBe('image');
        let groups = findAllByTestId(v, 'pbwf-art-attempt');
        expect(groups.map(g => g.attrs['data-attempt'])).toEqual([RUN2, RUN1]);
        let tiles = findAllByTestId(v, 'pbwf-art-tile');
        expect(tiles.map(t => t.attrs['data-artifact-id'])).toEqual(['a-r2v0', 'a-r2v1', 'a-r1v0', 'a-r1v1']);
        expect(tiles.map(t => t.attrs['data-variant-index'])).toEqual([0, 1, 0, 1]);
        expect(tiles.map(t => t.attrs['data-review-status'])).toEqual(['approved', 'generated', 'generated', 'rejected']);
        expect(findAllByTestId(v, 'pbwf-art-variant-badge').map(textOf)).toEqual(['v0', 'v1', 'v0', 'v1']);
        expect(findAllByTestId(v, 'pbwf-art-seed-badge').map(textOf)).toEqual([' · s200', ' · s201', ' · s100', ' · s101']);
        expect(textOf(findByTestId(v, 'pbwf-art-archived-toggle'))).toBe('Show archived (1)');
        expect(findByTestId(v, 'pbwf-art-bytes-deleted')).toBeNull();
        // Tooltip carries the facts the badges abbreviate.
        expect(tiles[0].attrs.title).toBe('Rev 4 · v0 · seed 200 · current · approved');
    });
    it('tile tint: selected blue, APPROVED green, REJECTED grey, ARCHIVED dashed+dim; default border otherwise', () => {
        expect(canvas.artifactTileStyle({ selected: true, reviewStatus: 'GENERATED' }).border).toContain('#3b82f6');
        expect(canvas.artifactTileStyle({ reviewStatus: 'approved' }).border).toContain('#16a34a');
        expect(canvas.artifactTileStyle({ reviewStatus: 'REJECTED' })).toEqual({ border: '2px solid #9ca3af', opacity: '.7' });
        expect(canvas.artifactTileStyle({ reviewStatus: 'archived' }).border).toContain('dashed');
        expect(canvas.artifactTileStyle({ reviewStatus: 'GENERATED' }).border).toContain('#e2e8f0');
        // selected wins over status so the current artifact is always findable
        expect(canvas.artifactTileStyle({ selected: true, reviewStatus: 'ARCHIVED' }).border).toContain('#3b82f6');
    });
    it('"Show archived" reveals the archived row as its own group with the bytes-deleted badge; toggle flips the label', () => {
        let v = canvas.renderNodeDetailPanel();
        findByTestId(v, 'pbwf-art-archived-toggle').attrs.onclick({ stopPropagation() {} });
        expect(canvas.__canvasStateForTest().artifactUi.showArchived).toBe(true);
        v = canvas.renderNodeDetailPanel();
        expect(textOf(findByTestId(v, 'pbwf-art-archived-toggle'))).toBe('Hide archived (1)');
        let groups = findAllByTestId(v, 'pbwf-art-attempt');
        expect(groups.length).toBe(3);
        expect(groups[2].attrs['data-attempt']).toBe('');
        let tiles = findAllByTestId(v, 'pbwf-art-tile');
        expect(tiles.map(t => t.attrs['data-artifact-id'])).toEqual(['a-r2v0', 'a-r2v1', 'a-r1v0', 'a-r1v1', 'a-old']);
        expect(findByTestId(v, 'pbwf-art-bytes-deleted')).not.toBeNull();
        expect(tiles[4].attrs.title).toBe('Rev 1 · archived · archived 2026-10-01T00:00:00Z · bytes deleted');
        // No image is rendered for an artifact whose bytes are gone (dataObjectId absent).
        expect(JSON.stringify(tiles[4])).not.toContain('/rest/resource/data.data/');
    });
    it('a chain with every row archived says so instead of rendering an empty strip', () => {
        canvas.__setCanvasStateForTest({ nodeDetails: { artifacts: { image: [CHAIN[0]] }, bindings: [] } });
        let v = canvas.renderNodeDetailPanel();
        expect(findAllByTestId(v, 'pbwf-art-tile').length).toBe(0);
        expect(textOf(findByTestId(v, 'pbwf-art-role'))).toContain('All 1 archived');
    });
    it('clicking tiles builds a two-up compare (A/B); a third pick replaces the oldest; Clear empties it', () => {
        let v = canvas.renderNodeDetailPanel();
        expect(findByTestId(v, 'pbwf-art-compare')).toBeNull();
        let tiles = findAllByTestId(v, 'pbwf-art-tile');
        tiles[0].attrs.onclick({ stopPropagation() {} });
        v = canvas.renderNodeDetailPanel();
        let cmp = findByTestId(v, 'pbwf-art-compare');
        expect(cmp).not.toBeNull();
        expect(findAllByTestId(v, 'pbwf-art-compare-item').length).toBe(1);
        expect(textOf(cmp)).toContain('Pick a second tile to compare');
        findAllByTestId(v, 'pbwf-art-tile')[2].attrs.onclick({ stopPropagation() {} });
        v = canvas.renderNodeDetailPanel();
        expect(canvas.__canvasStateForTest().artifactUi.compare).toEqual(['a-r2v0', 'a-r1v0']);
        let items = findAllByTestId(v, 'pbwf-art-compare-item');
        expect(items.length).toBe(2);
        expect(textOf(items[0])).toContain('A image r4 v0 · seed 200 · approved · 2.0 KB');
        expect(textOf(items[1])).toContain('B image r2 v0 · seed 100 · generated · 1.5 KB');
        expect(JSON.stringify(items[0])).toContain('/rest/resource/data.data/d-r2v0');
        expect(findAllByTestId(v, 'pbwf-art-tile').map(t => t.attrs['data-compare'])).toEqual(['1', undefined, '2', undefined]);
        // third pick drops the oldest (A), B becomes A
        findAllByTestId(v, 'pbwf-art-tile')[3].attrs.onclick({ stopPropagation() {} });
        expect(canvas.__canvasStateForTest().artifactUi.compare).toEqual(['a-r1v0', 'a-r1v1']);
        // clicking a picked tile again un-picks it
        canvas.toggleCompare('a-r1v1');
        expect(canvas.__canvasStateForTest().artifactUi.compare).toEqual(['a-r1v0']);
        v = canvas.renderNodeDetailPanel();
        findByTestId(v, 'pbwf-art-compare-clear').attrs.onclick({ stopPropagation() {} });
        expect(canvas.__canvasStateForTest().artifactUi.compare).toEqual([]);
        expect(findByTestId(canvas.renderNodeDetailPanel(), 'pbwf-art-compare')).toBeNull();
    });
    it('a chain of pre-P6 rows (no attempt, seed 0 on the wire) renders one group per row with r{n} labels and no v/seed badges', () => {
        // `seed: 0` is what the server really sends for these rows (long column, DDL default 0, NON_EMPTY keeps 0).
        canvas.__setCanvasStateForTest({ nodeDetails: { artifacts: { image: [{ objectId: 'p1', revision: 1, reviewStatus: 'GENERATED', seed: 0 }, { objectId: 'p2', revision: 2, reviewStatus: 'GENERATED', selected: true, seed: 0 }] }, bindings: [] } });
        let v = canvas.renderNodeDetailPanel();
        let groups = findAllByTestId(v, 'pbwf-art-attempt');
        expect(groups.length).toBe(2);
        expect(groups.map(g => textOf(g.children[0]))).toEqual(['Rev 2', 'Rev 1']);
        expect(findAllByTestId(v, 'pbwf-art-variant-badge').length).toBe(0);
        expect(findAllByTestId(v, 'pbwf-art-seed-badge').length).toBe(0);
        expect(findByTestId(v, 'pbwf-art-archived-toggle')).toBeNull();
    });
});

// ─────────────────────────────── retention dialog ───────────────────────────────

describe('canvas Retention dialog: dry run → apply', () => {
    let fetchMock;
    beforeEach(() => {
        Dialog.closeAll();
        Dialog._reset();
        document.body.innerHTML = '';
        if (!am7model._page) am7model._page = page;
        if (typeof page.toast !== 'function') page.toast = () => {};
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        canvas.__setCanvasStateForTest({ graphData: GRAPH, pb2BookObjectId: 'B1', selectedNodeId: null, nodeDetails: null, retention: {} });
    });
    afterEach(() => {
        vi.restoreAllMocks();
        Dialog.closeAll();
        Dialog._reset();
    });

    it('opens ONE dialog on the shared stack with K/D inputs, Dry run enabled and Apply disabled until a scan', () => {
        canvas.openRetentionDialog();
        expect(Dialog.stackDepth()).toBe(1);
        canvas.openRetentionDialog();
        expect(Dialog.stackDepth()).toBe(1);
        let c = renderDialogsInto();
        expect(c.querySelector('.am7-dialog-title').textContent).toBe('Variant Retention');
        expect(byTestId(c, 'pbwf-retention-keep')).toBeTruthy();
        expect(byTestId(c, 'pbwf-retention-days')).toBeTruthy();
        expect(byTestId(c, 'pbwf-retention-scan').disabled).toBe(false);
        expect(byTestId(c, 'pbwf-retention-apply').disabled).toBe(true);
        expect(byTestId(c, 'pbwf-retention-apply').closest('.am7-dialog-footer')).toBeTruthy();
    });

    it('Dry run GETs /retention with the typed K/D, shows counts + only the actionable items, enables Apply', async () => {
        let calls = [];
        fetchMock = vi.fn(async (u, o) => { calls.push(((o && o.method) || 'GET') + ' ' + restPath(u)); return jsonResponse(200, SCAN); });
        global.fetch = fetchMock;
        canvas.openRetentionDialog();
        let c = renderDialogsInto();
        let keep = byTestId(c, 'pbwf-retention-keep');
        keep.value = '1';
        keep.dispatchEvent(new Event('input', { bubbles: true }));
        byTestId(c, 'pbwf-retention-scan').click();
        await tick(); await tick();
        rerender(c);
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/retention?keepAttempts=1']);
        let summary = byTestId(c, 'pbwf-retention-summary');
        expect(summary).toBeTruthy();
        expect(summary.textContent).toContain('5 artifacts across 1 node with K=1, D=30');
        expect(byTestId(c, 'pbwf-retention-archive-count').textContent).toBe('2 archive');
        expect(byTestId(c, 'pbwf-retention-delete-count').textContent).toBe('0 delete bytes');
        expect(byTestId(c, 'pbwf-retention-reclaimable').textContent).toBe('3.0 KB reclaimable');
        let items = [...c.querySelectorAll('[data-testid="pbwf-retention-item"]')];
        expect(items.map(i => i.getAttribute('data-action'))).toEqual(['ARCHIVE', 'ARCHIVE']);
        expect(items[0].textContent).toContain('hero · image r2 v0');
        expect(items[0].textContent).toContain('attempt 2 of 2 exceeds keepAttempts=1');
        expect(byTestId(c, 'pbwf-retention-apply').disabled).toBe(false);
        expect(byTestId(c, 'pbwf-retention-result')).toBeNull();
    });

    it('editing K or D after a dry run discards it: Apply goes back to disabled until a new dry run', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, SCAN));
        canvas.openRetentionDialog();
        let c = renderDialogsInto();
        byTestId(c, 'pbwf-retention-scan').click();
        await tick(); await tick();
        rerender(c);
        expect(byTestId(c, 'pbwf-retention-apply').disabled).toBe(false);
        let days = byTestId(c, 'pbwf-retention-days');
        days.value = '7';
        days.dispatchEvent(new Event('input', { bubbles: true }));
        rerender(c);
        expect(canvas.__canvasStateForTest().retention.scan).toBeNull();
        expect(byTestId(c, 'pbwf-retention-apply').disabled).toBe(true);
        expect(byTestId(c, 'pbwf-retention-summary')).toBeNull();
    });

    it('a dry run with nothing actionable says so and keeps Apply disabled', async () => {
        let nothing = Object.assign({}, SCAN, { counts: { keep: 5, archive: 0, deleteBytes: 0, none: 0 }, bytesReclaimable: 0, items: SCAN.items.map(it => Object.assign({}, it, { action: 'KEEP' })) });
        global.fetch = vi.fn(async () => jsonResponse(200, nothing));
        canvas.openRetentionDialog();
        let c = renderDialogsInto();
        byTestId(c, 'pbwf-retention-scan').click();
        await tick(); await tick();
        rerender(c);
        expect(byTestId(c, 'pbwf-retention-nothing')).toBeTruthy();
        expect(byTestId(c, 'pbwf-retention-items')).toBeNull();
        expect(byTestId(c, 'pbwf-retention-apply').disabled).toBe(true);
    });

    it('a 400 from the server is shown verbatim in the dialog and nothing is applied', async () => {
        global.fetch = vi.fn(async () => jsonResponse(400, { error: 'keepAttempts must be between 1 and 20' }));
        canvas.openRetentionDialog();
        let c = renderDialogsInto();
        let keep = byTestId(c, 'pbwf-retention-keep');
        keep.value = '99';
        keep.dispatchEvent(new Event('input', { bubbles: true }));
        byTestId(c, 'pbwf-retention-scan').click();
        await tick(); await tick();
        rerender(c);
        expect(byTestId(c, 'pbwf-retention-error').textContent).toBe('(400) keepAttempts must be between 1 and 20');
        expect(byTestId(c, 'pbwf-retention-apply').disabled).toBe(true);
        expect(Dialog.stackDepth()).toBe(1);
    });

    it('Apply POSTs /retention/apply with the same K/D, shows per-item outcomes from the APPLY response, toasts, and reloads the selected node', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let p = restPath(u); let method = (o && o.method) || 'GET';
            calls.push(method + ' ' + p + (o && o.body ? ' ' + o.body : ''));
            if (/\/retention\/apply$/.test(p)) return jsonResponse(200, APPLIED);
            if (/\/retention/.test(p)) return jsonResponse(200, SCAN);
            if (/\/workflow\/node\//.test(p)) return jsonResponse(200, { artifacts: { image: CHAIN.map(a => a.attempt === RUN1 ? Object.assign({}, a, { reviewStatus: 'ARCHIVED', archivedAt: '2026-10-29T10:01:00Z' }) : a) }, bindings: [] });
            return jsonResponse(404, { error: 'unexpected ' + p });
        });
        canvas.__setCanvasStateForTest({ selectedNodeId: 'n1', nodeDetails: { artifacts: { image: CHAIN }, bindings: [] }, artifactUi: { showArchived: false, compare: ['a-r1v0', 'a-r2v0'] } });
        canvas.openRetentionDialog();
        let c = renderDialogsInto();
        let keep = byTestId(c, 'pbwf-retention-keep');
        keep.value = '1';
        keep.dispatchEvent(new Event('input', { bubbles: true }));
        byTestId(c, 'pbwf-retention-scan').click();
        await tick(); await tick();
        rerender(c);
        byTestId(c, 'pbwf-retention-apply').click();
        await tick(); await tick(); await tick();
        rerender(c);
        expect(calls[0]).toBe('GET /rest/olio/picture-book/B1/retention?keepAttempts=1');
        expect(calls[1]).toBe('POST /rest/olio/picture-book/B1/retention/apply {"keepAttempts":1}');
        expect(calls[2]).toBe('GET /rest/olio/picture-book/B1/workflow/node/n1');
        let result = byTestId(c, 'pbwf-retention-result');
        expect(result).toBeTruthy();
        expect(byTestId(c, 'pbwf-retention-archived').textContent).toBe('2 archived');
        expect(byTestId(c, 'pbwf-retention-bytes-deleted').textContent).toBe('0 bytes-deleted');
        let items = [...c.querySelectorAll('[data-testid="pbwf-retention-item"]')];
        expect(items.map(i => i.getAttribute('data-outcome'))).toEqual(['archived', 'archived']);
        // the scan is consumed; Apply is disabled again until a fresh dry run
        expect(canvas.__canvasStateForTest().retention.scan).toBeNull();
        expect(byTestId(c, 'pbwf-retention-apply').disabled).toBe(true);
        expect(byTestId(c, 'pbwf-retention-summary')).toBeNull();
        let ok = page.toast.mock.calls.filter(x => x[0] === 'success');
        expect(ok.length).toBe(1);
        expect(ok[0][1]).toBe('Retention applied: 2 archived, 0 bytes-deleted');
        // the node strip now hides the archived attempt and the compare pick still exists (archived ≠ gone)
        let st = canvas.__canvasStateForTest();
        expect(st.nodeDetails.artifacts.image.filter(a => a.reviewStatus === 'ARCHIVED').length).toBe(3);
        expect(st.artifactUi.compare).toEqual(['a-r1v0', 'a-r2v0']);
        let v = canvas.renderNodeDetailPanel();
        expect(findAllByTestId(v, 'pbwf-art-attempt').map(g => g.attrs['data-attempt'])).toEqual([RUN2]);
        expect(textOf(findByTestId(v, 'pbwf-art-archived-toggle'))).toBe('Show archived (3)');
    });

    it('Apply is a no-op without a current scan (state-level guard), and Close resets the form', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, APPLIED));
        canvas.openRetentionDialog();
        await canvas.doApplyRetention();
        expect(global.fetch).not.toHaveBeenCalled();
        let c = renderDialogsInto();
        let keep = byTestId(c, 'pbwf-retention-keep');
        keep.value = '2';
        keep.dispatchEvent(new Event('input', { bubbles: true }));
        expect(canvas.__canvasStateForTest().retention.keepAttempts).toBe('2');
        [...c.querySelectorAll('.am7-dialog-btn')].find(b => /Close/.test(b.textContent)).click();
        expect(Dialog.stackDepth()).toBe(0);
        expect(canvas.__canvasStateForTest().retention).toMatchObject({ open: false, keepAttempts: '', scan: null, result: null });
    });
});
