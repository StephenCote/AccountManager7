// @vitest-environment jsdom
/**
 * PictureBook V3 — character manager "affected scenes" panel (P1-10 S5).
 *
 * Why: a charPerson edit under the V3 graph path marks the scenes bound to that character STALE on
 * the server; the manager must show that (once per detail open, once after a save — never per row,
 * never on a poll), navigate to the review queue, and re-render the affected scenes serially with
 * the same "never silently unpin" rule the reader uses. The wizard (data.group id, v1) is untouched.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function flushPromises() { return new Promise(r => setTimeout(r, 0)); }

/** Walk a Mithril vnode tree collecting nodes whose attrs match. */
function findAll(vnode, pred, out) {
    out = out || [];
    if (!vnode) return out;
    if (Array.isArray(vnode)) { vnode.forEach(v => findAll(v, pred, out)); return out; }
    if (typeof vnode !== 'object') return out;
    if (vnode.attrs && pred(vnode)) out.push(vnode);
    if (vnode.children) findAll(vnode.children, pred, out);
    return out;
}
function byTestId(vnode, id) { return findAll(vnode, v => v.attrs['data-testid'] === id); }
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}

const ENTITY = { objectId: 'c1', id: 11, name: 'Alice', description: 'A heroine', gender: 'FEMALE' };
const AFFECTED = { bookObjectId: 'book-1', refModel: 'olio.charPerson', refObjectId: 'c1', boundNodes: 2, drifted: true,
    driftedHandles: ['scene-3'], affectedSceneIndexes: [3, 5], staleSceneIndexes: [3], markPersisted: true };
const PAGES = [
    { objectId: 's1', sceneIndex: 1, pinned: false },
    { objectId: 's3', sceneIndex: 3, sceneNodeObjectId: 'n3', nodeStatus: 'STALE', pinned: false },
    { objectId: 's5', sceneIndex: 5, sceneNodeObjectId: 'n5', nodeStatus: 'DONE', pinned: true }
];

let origFetch;
let pbc;

beforeEach(async () => {
    origFetch = global.fetch;
    pbc = await import('../workflows/pictureBookCharacters.js');
    pbc.__setPbCharStateForTest({
        bookObjectId: 'book-1', standalone: true, renderPath: 'v3', selectedObjectId: null, selectedInst: null,
        affected: null, lastSaveKind: null, regen: null, editingIdentity: false, editName: '', editDescription: '', regenPollIntervalMs: 5
    });
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'getFull').mockResolvedValue(Object.assign({}, ENTITY));
    vi.spyOn(am7model, 'prepareInstance').mockImplementation((entity) => ({ entity }));
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(page, 'patchObject').mockResolvedValue(true);
    vi.spyOn(Dialog, 'closeAll').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
});

afterEach(() => {
    pbc.__setPbCharStateForTest({ regen: null, affected: null, selectedInst: null, selectedObjectId: null });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

/** fetch stub for the manager's wire: characters list, affected-by, pages, scene render, job, pin. */
function mockStack(opts) {
    let o = opts || {};
    let calls = [];
    let polls = {};
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u);
        let method = (init && init.method) || 'GET';
        calls.push({ url, method, body: init && init.body });
        let mj = /\/rest\/job\/(j\d)$/.exec(url);
        if (mj) {
            polls[mj[1]] = (polls[mj[1]] || 0) + 1;
            if (polls[mj[1]] === 1) return jsonResponse(200, { jobId: mj[1], status: 'running', phase: 'x', current: 0, total: 1, terminal: false });
            return jsonResponse(200, { jobId: mj[1], status: 'done', current: 1, total: 1, terminal: true,
                result: { runObjectId: 'r-' + mj[1], runStatus: 'DONE', executedNodeCount: 1, failedNodeCount: 0, skippedNodeCount: 0, nodes: [] } });
        }
        if (/\/book-1\/affected-by\/olio\.charPerson\/c1$/.test(url)) {
            if (o.affected) return o.affected(calls);
            return jsonResponse(200, AFFECTED);
        }
        if (/\/book-1\/characters$/.test(url)) return jsonResponse(200, [{ objectId: 'c1', name: 'Alice' }]);
        if (/\/book-1\/pages$/.test(url)) return jsonResponse(200, o.pages || PAGES);
        let ms = /\/book-1\/scene\/(s\d)\/render(\?force=true)?$/.exec(url);
        if (ms) {
            if (o.sceneRender) { let r = o.sceneRender(ms[1], calls, !!ms[2]); if (r) return r; }
            return jsonResponse(202, { sceneObjectId: ms[1], jobId: 'j' + ms[1].substring(1), runObjectId: 'r-' + ms[1], runStatus: 'RUNNING', plannedNodeCount: 1, planned: ['scene-' + ms[1].substring(1)] });
        }
        let mp = /\/book-1\/node\/(n\d)\/pin$/.exec(url);
        if (mp) return jsonResponse(200, { nodeObjectId: mp[1], pinned: false });
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}
function seqOf(calls) { return calls.map(c => c.method + ' ' + c.url.replace(/^.*\/rest\//, '/rest/')); }

// ─────────────────────────────── pure helpers ───────────────────────────────

describe('identityChangeKind / affectedCaveat — watched vs unwatched edits', () => {
    it('name is watched; description alone is unwatched; nothing → none (name is trimmed)', () => {
        expect(pbc.identityChangeKind(ENTITY, 'Alice B', 'A heroine')).toBe('watched');
        expect(pbc.identityChangeKind(ENTITY, '  Alice  ', 'A heroine')).toBe('none');
        expect(pbc.identityChangeKind(ENTITY, 'Alice', 'A villain')).toBe('unwatched');
        expect(pbc.identityChangeKind(ENTITY, 'Alice B', 'A villain')).toBe('watched');
        expect(pbc.identityChangeKind({ objectId: 'x' }, 'N', '')).toBe('watched');
        expect(pbc.identityChangeKind(null, '', '')).toBe('none');
    });
    it('caveat only for an unwatched-only save, and it names what IS watched', () => {
        expect(pbc.affectedCaveat('watched')).toBeNull();
        expect(pbc.affectedCaveat('none')).toBeNull();
        expect(pbc.affectedCaveat(null)).toBeNull();
        let c = pbc.affectedCaveat('unwatched');
        expect(c).toMatch(/^Description is not a watched field/);
        expect(c).toContain('hairColor');
        expect(c).toContain('pbDescription');
        expect(c).toContain('narrative.sdPrompt and statistics are not watched');
    });
    it('WATCHED_CHAR_FIELDS mirrors PbWatchedFields.CHAR_PERSON (no description)', () => {
        expect(pbc.WATCHED_CHAR_FIELDS).toEqual(['name', 'firstName', 'lastName', 'gender', 'age', 'race', 'raceLabel', 'ethnicity', 'hairColor', 'eyeColor', 'hairStyle', 'alignment']);
        expect(pbc.WATCHED_CHAR_FIELDS).not.toContain('description');
    });
});

describe('affectedSummary / scenesForIndexes — affected-by → panel numbers → page rows', () => {
    it('summarizes a normalized affected-by result and tolerates the absent-list wire shape', () => {
        expect(pbc.affectedSummary(AFFECTED)).toEqual({ count: 2, sceneIndexes: [3, 5], staleCount: 1, boundNodes: 2, drifted: true, driftedHandles: ['scene-3'] });
        // Include.NON_EMPTY: lists absent when empty, drifted false, boundNodes 0
        expect(pbc.affectedSummary({ bookObjectId: 'b', refModel: 'olio.charPerson', refObjectId: 'c' })).toEqual({ count: 0, sceneIndexes: [], staleCount: 0, boundNodes: 0, drifted: false, driftedHandles: [] });
        expect(pbc.affectedSummary(null).count).toBe(0);
    });
    it('picks the page rows for the affected indexes, ascending, dropping indexes with no row', () => {
        let rows = pbc.scenesForIndexes([PAGES[2], PAGES[0], PAGES[1]], [5, 3, 9]);
        expect(rows.map(r => r.objectId)).toEqual(['s3', 's5']);
        expect(pbc.scenesForIndexes(PAGES, [])).toEqual([]);
        expect(pbc.scenesForIndexes(null, [1])).toEqual([]);
        expect(pbc.scenesForIndexes([{ sceneIndex: 1 }], [1])).toEqual([]); // no objectId → cannot render
    });
});

// ─────────────────────────────── panel rendering ───────────────────────────────

describe('renderAffectedPanel — gating and states', () => {
    it('renders nothing unless standalone + v3 + a loaded character', () => {
        pbc.__setPbCharStateForTest({ selectedInst: { entity: ENTITY }, affected: { forObjectId: 'c1', loading: false, data: AFFECTED, error: null } });
        expect(pbc.renderAffectedPanel()).not.toBeNull();
        pbc.__setPbCharStateForTest({ renderPath: 'v1' });
        expect(pbc.renderAffectedPanel()).toBeNull();
        pbc.__setPbCharStateForTest({ renderPath: 'v3', standalone: false });
        expect(pbc.renderAffectedPanel()).toBeNull();
        pbc.__setPbCharStateForTest({ standalone: true, selectedInst: null });
        expect(pbc.renderAffectedPanel()).toBeNull();
    });
    it('loading / error / loaded states with count, stale badge, Review + Regenerate', () => {
        pbc.__setPbCharStateForTest({ selectedInst: { entity: ENTITY }, affected: null });
        expect(textOf(pbc.renderAffectedPanel())).toContain('Checking affected scenes');
        pbc.__setPbCharStateForTest({ affected: { forObjectId: 'c1', loading: false, data: null, error: '(404) Book not found' } });
        let v = pbc.renderAffectedPanel();
        expect(textOf(byTestId(v, 'pbchar-affected-error')[0])).toBe('(404) Book not found');
        pbc.__setPbCharStateForTest({ affected: { forObjectId: 'c1', loading: false, data: AFFECTED, error: null } });
        v = pbc.renderAffectedPanel();
        expect(byTestId(v, 'pbchar-affected').length).toBe(1);
        let count = byTestId(v, 'pbchar-affected-count')[0];
        expect(count.attrs['data-count']).toBe('2');
        expect(textOf(count)).toBe('Affected scenes: 2 (3, 5)');
        expect(textOf(byTestId(v, 'pbchar-affected-drifted')[0])).toBe('1 stale');
        expect(byTestId(v, 'pbchar-affected-review')[0].attrs.disabled).toBe(false);
        expect(byTestId(v, 'pbchar-affected-regenerate')[0].attrs.disabled).toBe(false);
        expect(byTestId(v, 'pbchar-affected-caveat').length).toBe(0);
    });
    it('a panel for a different character than the one loaded shows the loading state (no stale data)', () => {
        pbc.__setPbCharStateForTest({ selectedInst: { entity: { objectId: 'c2', name: 'Bob' } }, affected: { forObjectId: 'c1', loading: false, data: AFFECTED, error: null } });
        expect(textOf(pbc.renderAffectedPanel())).toContain('Checking affected scenes');
    });
    it('zero affected scenes disables Regenerate; no drifted badge', () => {
        pbc.__setPbCharStateForTest({ selectedInst: { entity: ENTITY }, affected: { forObjectId: 'c1', loading: false, data: { boundNodes: 0, drifted: false, driftedHandles: [], affectedSceneIndexes: [], staleSceneIndexes: [] }, error: null } });
        let v = pbc.renderAffectedPanel();
        expect(textOf(byTestId(v, 'pbchar-affected-count')[0])).toBe('Affected scenes: 0');
        expect(byTestId(v, 'pbchar-affected-drifted').length).toBe(0);
        expect(byTestId(v, 'pbchar-affected-regenerate')[0].attrs.disabled).toBe(true);
    });
    it('caveat appears after an unwatched-only save; progress label + disabled buttons while regenerating', () => {
        pbc.__setPbCharStateForTest({ selectedInst: { entity: ENTITY }, lastSaveKind: 'unwatched', affected: { forObjectId: 'c1', loading: false, data: AFFECTED, error: null } });
        let v = pbc.renderAffectedPanel();
        expect(textOf(byTestId(v, 'pbchar-affected-caveat')[0])).toMatch(/^Description is not a watched field/);
        pbc.__setPbCharStateForTest({ lastSaveKind: 'watched', regen: { total: 2, done: 1, current: 5, aborted: false, skipped: 0, failed: 0 } });
        v = pbc.renderAffectedPanel();
        expect(byTestId(v, 'pbchar-affected-caveat').length).toBe(0);
        let rg = byTestId(v, 'pbchar-affected-regenerate')[0];
        expect(rg.attrs.disabled).toBe(true);
        expect(textOf(rg)).toBe('Regenerating 2 of 2 (scene 5)…');
        expect(byTestId(v, 'pbchar-affected-review')[0].attrs.disabled).toBe(true);
    });
    it('Review closes the manager and routes to the book review queue', () => {
        pbc.__setPbCharStateForTest({ selectedInst: { entity: ENTITY }, affected: { forObjectId: 'c1', loading: false, data: AFFECTED, error: null } });
        byTestId(pbc.renderAffectedPanel(), 'pbchar-affected-review')[0].attrs.onclick();
        expect(Dialog.closeAll).toHaveBeenCalledTimes(1);
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/book-1/review');
    });
});

// ─────────────────────────────── detail open / save → ONE affected-by read each ───────────────────────────────

describe('selectCharacter / saveIdentity — affected-by call budget', () => {
    it('opening a detail makes exactly one GET affected-by and one GET wardrobe (P4-9); v1 makes none', async () => {
        let calls = mockStack();
        await pbc.__pbCharSelectCharacterForTest('c1');
        let st = pbc.__pbCharStateForTest();
        expect(st.selectedInst.entity.objectId).toBe('c1');
        expect(st.affected.loading).toBe(false);
        expect(st.affected.data.affectedSceneIndexes).toEqual([3, 5]);
        expect(seqOf(calls)).toEqual([
            'GET /rest/olio/picture-book/book-1/affected-by/olio.charPerson/c1',
            'GET /rest/olio/picture-book/book-1/character/c1/wardrobe'
        ]);
        expect(st.wardrobe.forObjectId).toBe('c1');
        expect(st.wardrobe.loading).toBe(false);

        pbc.__setPbCharStateForTest({ renderPath: 'v1' });
        await pbc.__pbCharSelectCharacterForTest('c1');
        expect(seqOf(calls).length).toBe(2);
        expect(pbc.__pbCharStateForTest().affected).toBeNull();
        expect(pbc.__pbCharStateForTest().wardrobe).toBeNull();
    });
    it('a 404 (unknown book) / 400 (unknown refModel) lands in the panel error, not a toast', async () => {
        mockStack({ affected: () => jsonResponse(404, { error: 'Book not found' }) });
        await pbc.__pbCharSelectCharacterForTest('c1');
        expect(pbc.__pbCharStateForTest().affected.error).toBe('(404) Book not found');
        expect(page.toast).not.toHaveBeenCalled();
        mockStack({ affected: () => jsonResponse(400, { error: 'Unknown refModel olio.nope' }) });
        await pbc.__pbCharSelectCharacterForTest('c1');
        expect(pbc.__pbCharStateForTest().affected.error).toBe('(400) Unknown refModel olio.nope');
    });
    it('saveIdentity (description only) → PATCH, ONE post-save affected-by, caveat kept for the same character', async () => {
        let calls = mockStack();
        await pbc.__pbCharSelectCharacterForTest('c1');
        expect(seqOf(calls).filter(s => /affected-by/.test(s)).length).toBe(1);
        pbc.__setPbCharStateForTest({ editingIdentity: true, editName: 'Alice', editDescription: 'A villain' });
        await pbc.__pbCharSaveIdentityForTest();
        expect(page.patchObject).toHaveBeenCalledWith({ schema: 'olio.charPerson', id: 11, objectId: 'c1', name: 'Alice', description: 'A villain' });
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.charPerson', true);
        let st = pbc.__pbCharStateForTest();
        expect(st.lastSaveKind).toBe('unwatched');
        expect(st.editingIdentity).toBe(false);
        let seq = seqOf(calls);
        expect(seq.filter(s => /affected-by/.test(s)).length).toBe(2);
        expect(seq).toContain('GET /rest/olio/picture-book/book-1/characters');
        expect(textOf(byTestId(pbc.renderAffectedPanel(), 'pbchar-affected-caveat')[0])).toMatch(/^Description is not a watched field/);
        // switching to another character drops the caveat
        vi.spyOn(am7client, 'getFull').mockResolvedValue({ objectId: 'c2', id: 12, name: 'Bob' });
        global.fetch = vi.fn(async () => jsonResponse(200, { affectedSceneIndexes: [] }));
        await pbc.__pbCharSelectCharacterForTest('c2');
        expect(pbc.__pbCharStateForTest().lastSaveKind).toBeNull();
    });
    it('saveIdentity (name) → lastSaveKind watched, no caveat', async () => {
        mockStack();
        await pbc.__pbCharSelectCharacterForTest('c1');
        pbc.__setPbCharStateForTest({ editingIdentity: true, editName: ' Alicia ', editDescription: 'A heroine' });
        await pbc.__pbCharSaveIdentityForTest();
        expect(page.patchObject).toHaveBeenCalledWith({ schema: 'olio.charPerson', id: 11, objectId: 'c1', name: 'Alicia' });
        expect(pbc.__pbCharStateForTest().lastSaveKind).toBe('watched');
        expect(byTestId(pbc.renderAffectedPanel(), 'pbchar-affected-caveat').length).toBe(0);
    });
    it('a failed save makes no affected-by call and keeps the editor open', async () => {
        let calls = mockStack();
        await pbc.__pbCharSelectCharacterForTest('c1');
        page.patchObject.mockResolvedValue(undefined);
        pbc.__setPbCharStateForTest({ editingIdentity: true, editName: 'Alicia', editDescription: 'A heroine' });
        await pbc.__pbCharSaveIdentityForTest();
        expect(page.toast).toHaveBeenCalledWith('error', 'Failed to save character: server rejected the update');
        expect(seqOf(calls).filter(s => /affected-by/.test(s)).length).toBe(1);
        expect(pbc.__pbCharStateForTest().editingIdentity).toBe(true);
    });
});

// ─────────────────────────────── Regenerate: serial renderScene with the pinned-409 rule ───────────────────────────────

describe('regenerateAffected — serial per-scene render over the affected scenes', () => {
    beforeEach(() => {
        pbc.__setPbCharStateForTest({ selectedObjectId: 'c1', selectedInst: { entity: ENTITY }, affected: { forObjectId: 'c1', loading: false, data: AFFECTED, error: null } });
    });

    it('submits scene 3 (forced), polls its job to terminal, THEN scene 5; clears cache; re-reads affected-by once', async () => {
        // A current, unpinned node (a description-only edit) is "nothing to render" unless forced; the
        // mock refuses a non-forced submit the way the planner does so a dropped ?force=true fails here.
        let calls = mockStack({ sceneRender: (s, c, forced) => forced ? null
            : jsonResponse(409, { error: 'Nothing to render: every node of scene ' + s.substring(1) + ' is current (1 reused, 0 pinned)' }) });
        await pbc.__pbCharRegenerateAffectedForTest();
        let seq = seqOf(calls);
        expect(seq).toEqual([
            'GET /rest/olio/picture-book/book-1/pages',
            'POST /rest/olio/picture-book/book-1/scene/s3/render?force=true',
            'GET /rest/job/j3', 'GET /rest/job/j3',
            'POST /rest/olio/picture-book/book-1/scene/s5/render?force=true',
            'GET /rest/job/j5', 'GET /rest/job/j5',
            'GET /rest/olio/picture-book/book-1/affected-by/olio.charPerson/c1'
        ]);
        expect(calls.filter(c => /\/render\?force=true$/.test(c.url)).every(c => c.body === undefined)).toBe(true);
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.pb.scene', true);
        expect(page.toast).toHaveBeenCalledWith('success', 'Regenerated 2 of 2 scenes');
        expect(page.toast).not.toHaveBeenCalledWith('error', expect.anything());
        expect(pbc.__pbCharStateForTest().regen).toBeNull();
        expect(Dialog.closeAll).not.toHaveBeenCalled();
    });

    it('pinned 409 → verbatim confirm; declined → scene skipped, no pin call, loop continues', async () => {
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        let calls = mockStack({ sceneRender: (s) => s === 's3' ? jsonResponse(409, { error: 'Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)' }) : null });
        await pbc.__pbCharRegenerateAffectedForTest();
        expect(Dialog.confirm).toHaveBeenCalledTimes(1);
        let cfg = Dialog.confirm.mock.calls[0][0];
        expect(cfg.confirmLabel).toBe('Unpin and re-render');
        expect(cfg.message).toContain('(409) Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)');
        let seq = seqOf(calls);
        expect(seq.filter(s => /\/pin$/.test(s)).length).toBe(0);
        expect(seq).toContain('POST /rest/olio/picture-book/book-1/scene/s5/render?force=true');
        expect(page.toast).toHaveBeenCalledWith('success', 'Regenerated 1 of 2 scenes, 1 skipped (pinned)');
    });

    it('pinned 409 → accepted → POST node pin {pinned:false} then re-submit that scene', async () => {
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let first = true;
        let calls = mockStack({ sceneRender: (s, c, forced) => {
            if (s === 's3' && first) { first = false; return jsonResponse(409, { error: 'Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)' }); }
            // after the unpin the node is current: only a forced re-submit renders (else the pin is lost for nothing)
            if (!forced) return jsonResponse(409, { error: 'Nothing to render: every node of scene ' + s.substring(1) + ' is current (1 reused, 0 pinned)' });
            return null;
        } });
        await pbc.__pbCharRegenerateAffectedForTest();
        let seq = seqOf(calls);
        expect(seq.slice(0, 5)).toEqual([
            'GET /rest/olio/picture-book/book-1/pages',
            'POST /rest/olio/picture-book/book-1/scene/s3/render?force=true',
            'POST /rest/olio/picture-book/book-1/node/n3/pin',
            'POST /rest/olio/picture-book/book-1/scene/s3/render?force=true',
            'GET /rest/job/j3'
        ]);
        let pin = calls.find(c => /\/node\/n3\/pin$/.test(c.url));
        expect(JSON.parse(pin.body)).toEqual({ pinned: false });
        expect(page.toast).toHaveBeenCalledWith('success', 'Regenerated 2 of 2 scenes');
    });

    it('a 429 / in-flight 409 is toasted verbatim and stops the loop — never unpins', async () => {
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let calls = mockStack({ sceneRender: (s) => s === 's3' ? jsonResponse(429, { error: 'Principal already has 1 of 1 in-flight runs' }) : null });
        await pbc.__pbCharRegenerateAffectedForTest();
        expect(Dialog.confirm).not.toHaveBeenCalled();
        expect(page.toast).toHaveBeenCalledWith('error', '(429) Principal already has 1 of 1 in-flight runs');
        let seq = seqOf(calls);
        expect(seq).not.toContain('POST /rest/olio/picture-book/book-1/scene/s5/render?force=true');
        expect(seq.filter(s => /\/pin$/.test(s)).length).toBe(0);
        expect(pbc.__pbCharStateForTest().regen).toBeNull();

        page.toast.mockClear();
        calls = mockStack({ sceneRender: (s) => s === 's3' ? jsonResponse(409, { error: 'Node n3 is being rendered by run r-9' }) : null });
        await pbc.__pbCharRegenerateAffectedForTest();
        expect(Dialog.confirm).not.toHaveBeenCalled();
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Node n3 is being rendered by run r-9');
        expect(seqOf(calls)).not.toContain('POST /rest/olio/picture-book/book-1/scene/s5/render?force=true');
    });

    it('affected scenes with no page row are skipped with a warning; nothing to do when count is 0', async () => {
        let calls = mockStack({ pages: [PAGES[1]] });
        await pbc.__pbCharRegenerateAffectedForTest();
        expect(page.toast).toHaveBeenCalledWith('warning', '1 affected scene has no page row and will be skipped');
        expect(page.toast).toHaveBeenCalledWith('success', 'Regenerated 1 of 1 scene');
        expect(seqOf(calls).filter(s => /\/render\?force=true$/.test(s)).length).toBe(1);

        calls = mockStack();
        pbc.__setPbCharStateForTest({ affected: { forObjectId: 'c1', loading: false, data: { affectedSceneIndexes: [] }, error: null } });
        await pbc.__pbCharRegenerateAffectedForTest();
        expect(calls.length).toBe(0);
    });

    it('is a no-op under v1 / while another regenerate is running', async () => {
        let calls = mockStack();
        pbc.__setPbCharStateForTest({ renderPath: 'v1' });
        await pbc.__pbCharRegenerateAffectedForTest();
        expect(calls.length).toBe(0);
        pbc.__setPbCharStateForTest({ renderPath: 'v3', regen: { total: 1, done: 0 } });
        await pbc.__pbCharRegenerateAffectedForTest();
        expect(calls.length).toBe(0);
    });
});
