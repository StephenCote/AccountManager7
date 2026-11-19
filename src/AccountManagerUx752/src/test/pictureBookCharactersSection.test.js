// @vitest-environment jsdom
/**
 * Edit › Characters — the character manager as a bookWorkspace section (aiDocs/PictureBookUxConsistencyDesign.md
 * §4.2, §4.7, §5.4, §6.2, D2 / D4).
 *
 * Why: the pop-in is retired (D2) and the stat sliders stop saving on every input (D4). In the section a slider move
 * is a draft (dirtyMark, "unsaved" row chip), Save sends ONE sparse olio.statistics PATCH of the changed fields and a
 * failed write keeps the draft (model-api.md: never discard the update result), Revert drops it, the draft is a
 * dirtyGuard source, the selection follows the route's :item, list clicks go through ctx.select (guarded), the
 * affected-scenes panel links into the workspace, and the rail shows a warning count.
 *
 * Real Mithril (mounted in jsdom) and the manager's real fetch wire; only the network, getFull / prepareInstance,
 * patchObject and toast are stubbed, as in the other pictureBookCharacters tests.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { anyDirty, dirtyLabels, guardDirty, __resetDirtyForTest } from '../components/dirtyGuard.js';

function jsonResponse(status, body) {
    let text = JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function flush() { return new Promise(r => setTimeout(r, 0)); }
async function settle() { for (let i = 0; i < 6; i++) await flush(); m.redraw.sync(); }

const BOOK = 'book-1';
const LIST = [
    { objectId: 'c1', name: 'Alice', hasPortrait: true, apparelCount: 2 },
    { objectId: 'c2', name: 'Bob', hasPortrait: false, apparelCount: 1 },
    { objectId: 'c3', name: 'Cara', hasPortrait: true, apparelCount: 0, failedStatistics: true }
];
function entityFor(oid) {
    let c = LIST.find(x => x.objectId === oid);
    return {
        objectId: oid, id: 10 + Number(oid.substring(1)), name: c.name, gender: 'FEMALE',
        statistics: { id: 100 + Number(oid.substring(1)), objectId: 's-' + oid, agility: 5, speed: 7, wisdom: 9 }
    };
}

let origFetch, pbc, root, calls, ctx, affectedBody;

function makeCtx(item) {
    let c = { kind: 'pb', bookObjectId: BOOK, section: 'characters', item: item || null, tab: null, guided: false };
    c.select = vi.fn((it) => Promise.resolve(true));
    c.go = vi.fn(() => Promise.resolve(true));
    return c;
}

function mount() {
    root = document.createElement('div');
    document.body.appendChild(root);
    m.mount(root, { view: () => pbc.charactersSection.body(ctx) });
}

function q(sel) { return root.querySelector(sel); }
function slider(field) { return q('input[type=range][name="' + field + '"]'); }
function move(field, v) {
    let el = slider(field);
    el.value = String(v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    m.redraw.sync();
}
function patchCalls() { return page.patchObject.mock.calls.map(c => c[0]); }

beforeEach(async () => {
    origFetch = global.fetch;
    calls = [];
    affectedBody = { boundNodes: 0, drifted: false, driftedHandles: [], affectedSceneIndexes: [], staleSceneIndexes: [] };
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u);
        calls.push(((init && init.method) || 'GET') + ' ' + url.replace(/^.*\/rest\//, '/rest/'));
        if (/\/book-1\/characters$/.test(url)) return jsonResponse(200, LIST);
        if (/\/book-1\/affected-by\/olio\.charPerson\/c\d$/.test(url)) return jsonResponse(200, affectedBody);
        if (/\/book-1\/character\/c\d\/wardrobe$/.test(url)) return jsonResponse(200, { bookObjectId: BOOK, entries: [], warnings: [] });
        if (/\/book-1\/pages$/.test(url)) return jsonResponse(200, [
            { objectId: 'sc3', sceneIndex: 3 }, { objectId: 'sc5', sceneIndex: 5 }
        ]);
        if (/\/book-1\/style$/.test(url)) return jsonResponse(200, { description: 'ink' });
        return jsonResponse(404, { error: 'unmocked ' + url });
    });
    pbc = await import('../workflows/pictureBookCharacters.js');
    __resetDirtyForTest();
    pbc.__resetPbCharStatusForTest();
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'getFull').mockImplementation(async (type, oid) => entityFor(oid));
    vi.spyOn(am7client, 'search').mockResolvedValue({ results: [{ bookType: 'PICTUREBOOK' }] });
    vi.spyOn(am7model, 'prepareInstance').mockImplementation((entity) => ({ entity }));
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(page, 'patchObject').mockResolvedValue(true);
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
});

afterEach(() => {
    if (root) { m.mount(root, null); root.remove(); root = null; }
    try { pbc.charactersSection.remove(ctx); } catch (_) {}
    __resetDirtyForTest();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

async function enter(item) {
    ctx = makeCtx(item);
    await pbc.charactersSection.init(ctx);
    await settle();
    mount();
    await settle();
}

describe('selection follows the route', () => {
    it('selects ctx.item on init; a later ctx.item change (select hook) loads that character', async () => {
        await enter('c2');
        expect(pbc.__pbCharStateForTest().workspace).toBe(true);
        expect(pbc.__pbCharStateForTest().selectedObjectId).toBe('c2');
        expect(q('[data-char-name]').textContent).toBe('Bob');
        ctx = makeCtx('c3');
        pbc.charactersSection.select(ctx);
        await settle();
        expect(pbc.__pbCharStateForTest().selectedObjectId).toBe('c3');
        expect(q('[data-char-name]').textContent).toBe('Cara');
    });
    it('no ctx.item (or an unknown one) selects the first character without routing', async () => {
        await enter(null);
        expect(pbc.__pbCharStateForTest().selectedObjectId).toBe('c1');
        expect(ctx.select).not.toHaveBeenCalled();
        pbc.charactersSection.select(makeCtx('gone'));
        await settle();
        expect(pbc.__pbCharStateForTest().selectedObjectId).toBe('c1');
    });
    it('a list click calls ctx.select(objectId) (routed + guarded) instead of selecting directly', async () => {
        await enter('c1');
        am7client.getFull.mockClear();
        q('[data-char-item="c3"]').click();
        await settle();
        expect(ctx.select).toHaveBeenCalledWith('c3');
        expect(am7client.getFull).not.toHaveBeenCalled();
        expect(pbc.__pbCharStateForTest().selectedObjectId).toBe('c1');
    });
    it('a ctx.item that arrives while the list is still loading is applied once it loads', async () => {
        ctx = makeCtx('c1');
        let p = pbc.charactersSection.init(ctx);
        expect(pbc.__pbCharStateForTest().loading).toBe(true);
        pbc.charactersSection.select(makeCtx('c3'));
        await p;
        await settle();
        expect(pbc.__pbCharStateForTest().selectedObjectId).toBe('c3');
    });
});

describe('D4: statistic sliders are drafts with explicit Save / Revert', () => {
    it('a slider change does not write; it marks the field, the section and the row dirty', async () => {
        await enter('c1');
        expect(q('[data-testid="pbchar-stats-save"]').disabled).toBe(true);
        expect(q('[data-testid="pbchar-stats-revert"]').disabled).toBe(true);
        move('agility', 12);
        await settle();
        expect(page.patchObject).not.toHaveBeenCalled();
        expect(calls.some(c => /olio\.statistics/.test(c))).toBe(false);
        expect(slider('agility').value).toBe('12');
        expect(q('[data-testid="pbchar-stat-dirty-agility"]')).not.toBeNull();
        expect(q('[data-testid="pbchar-stat-dirty-speed"]')).toBeNull();
        expect(q('[data-testid="pbchar-stats-dirty"]')).not.toBeNull();
        expect(q('[data-char-item="c1"] [data-testid="pbchar-row-unsaved"]')).not.toBeNull();
        expect(q('[data-char-item="c2"] [data-testid="pbchar-row-unsaved"]')).toBeNull();
        expect(q('[data-testid="pbchar-stats-save"]').disabled).toBe(false);
        // the loaded record is not mutated by a draft
        expect(pbc.__pbCharStateForTest().selectedInst.entity.statistics.agility).toBe(5);
        // moving back to the stored value clears that field's draft
        move('agility', 5);
        expect(q('[data-testid="pbchar-stat-dirty-agility"]')).toBeNull();
        expect(q('[data-testid="pbchar-stats-save"]').disabled).toBe(true);
    });

    it('Save sends ONE sparse PATCH of only the changed stats, then clears the draft', async () => {
        await enter('c1');
        move('agility', 12);
        move('wisdom', 3);
        q('[data-testid="pbchar-stats-save"]').click();
        await settle();
        expect(patchCalls()).toEqual([{ schema: 'olio.statistics', id: 101, objectId: 's-c1', agility: 12, wisdom: 3 }]);
        let st = pbc.__pbCharStateForTest();
        expect(st.statDrafts).toEqual({});
        expect(st.selectedInst.entity.statistics).toMatchObject({ agility: 12, speed: 7, wisdom: 3 });
        expect(q('[data-testid="pbchar-stat-dirty-agility"]')).toBeNull();
        expect(page.toast).toHaveBeenCalledWith('success', 'Statistics saved');
        expect(anyDirty()).toBe(false);
    });

    it('a failed write (patchObject resolves undefined) keeps the draft and toasts the error', async () => {
        page.patchObject.mockResolvedValue(undefined);
        await enter('c1');
        move('speed', 15);
        q('[data-testid="pbchar-stats-save"]').click();
        await settle();
        expect(patchCalls()).toEqual([{ schema: 'olio.statistics', id: 101, objectId: 's-c1', speed: 15 }]);
        expect(page.toast).toHaveBeenCalledWith('error', 'Failed to save statistics for Alice: server rejected the update');
        expect(pbc.__pbCharStateForTest().statDrafts.c1.values).toEqual({ speed: 15 });
        expect(pbc.__pbCharStateForTest().selectedInst.entity.statistics.speed).toBe(7);
        expect(slider('speed').value).toBe('15');
        expect(q('[data-testid="pbchar-stat-dirty-speed"]')).not.toBeNull();
        expect(anyDirty(['pb-character'])).toBe(true);
    });

    it('Revert discards the draft without writing', async () => {
        await enter('c1');
        move('agility', 18);
        q('[data-testid="pbchar-stats-revert"]').click();
        m.redraw.sync();
        expect(page.patchObject).not.toHaveBeenCalled();
        expect(pbc.__pbCharStateForTest().statDrafts).toEqual({});
        expect(slider('agility').value).toBe('5');
        expect(q('[data-testid="pbchar-stat-dirty-agility"]')).toBeNull();
        expect(anyDirty()).toBe(false);
    });
});

describe('dirtyGuard registration', () => {
    it('registers pb-character; the guard lists it, Save commits through the same PATCH, Discard drops it', async () => {
        expect(pbc.charactersSection.dirtyKeys).toEqual(['pb-character']);
        await enter('c1');
        expect(anyDirty(['pb-character'])).toBe(false);
        move('agility', 11);
        expect(anyDirty(['pb-character'])).toBe(true);
        expect(dirtyLabels(['pb-character'])).toEqual(['Character — Alice']);

        // Save in the guard dialog → the sparse PATCH, then `next` runs
        let dialogs = [];
        vi.spyOn(Dialog, 'open').mockImplementation((cfg) => { dialogs.push(cfg); });
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        let next = vi.fn();
        let pr = guardDirty(next);
        await flush();
        expect(dialogs.length).toBe(1);
        dialogs[0].actions.find(a => a.label === 'Save').onclick();
        expect(await pr).toBe(true);
        expect(patchCalls()).toEqual([{ schema: 'olio.statistics', id: 101, objectId: 's-c1', agility: 11 }]);
        expect(next).toHaveBeenCalledTimes(1);
        expect(anyDirty()).toBe(false);

        // a failed Save in the guard keeps the user (next does not run) and the draft
        page.patchObject.mockResolvedValue(false);
        move('speed', 2);
        let next2 = vi.fn();
        pr = guardDirty(next2);
        await flush();
        dialogs[1].actions.find(a => a.label === 'Save').onclick();
        expect(await pr).toBe(false);
        expect(next2).not.toHaveBeenCalled();
        expect(pbc.__pbCharStateForTest().statDrafts.c1.values).toEqual({ speed: 2 });

        // Discard drops it without writing
        page.patchObject.mockClear();
        let next3 = vi.fn();
        pr = guardDirty(next3);
        await flush();
        dialogs[2].actions.find(a => a.label === 'Discard').onclick();
        expect(await pr).toBe(true);
        expect(next3).toHaveBeenCalledTimes(1);
        expect(page.patchObject).not.toHaveBeenCalled();
        expect(anyDirty()).toBe(false);
    });

    it('an open, changed identity edit is dirty too; leaving the section unregisters', async () => {
        await enter('c1');
        q('[data-char-edit]').click();
        m.redraw.sync();
        expect(anyDirty(['pb-character'])).toBe(false);   // opened, unchanged
        let inp = q('[data-char-name-input]');
        inp.value = 'Alicia';
        inp.dispatchEvent(new Event('input', { bubbles: true }));
        expect(anyDirty(['pb-character'])).toBe(true);
        pbc.charactersSection.remove(ctx);
        expect(anyDirty()).toBe(false);
    });
});

describe('status / loadCharactersStatus', () => {
    it('null until loaded; then a count of characters with a warning badge', async () => {
        expect(pbc.charactersSection.status({ bookObjectId: BOOK })).toBeNull();
        let st = await pbc.loadCharactersStatus(BOOK);
        expect(st).toMatchObject({ kind: 'count', count: 2 });   // Bob (no portrait), Cara (no apparel + stats failed)
        expect(pbc.charactersSection.status({ bookObjectId: BOOK })).toMatchObject({ kind: 'count', count: 2 });
        expect(calls).toEqual(['GET /rest/olio/picture-book/book-1/characters']);
        expect(pbc.characterWarnings(LIST[2])).toEqual(['no apparel', 'stats failed']);
    });
    it('a failed list read leaves the status unknown and does not throw', async () => {
        global.fetch = vi.fn(async () => jsonResponse(500, { error: 'boom' }));
        expect(await pbc.loadCharactersStatus(BOOK)).toBeNull();
        expect(pbc.charactersSection.status({ bookObjectId: BOOK })).toBeNull();
    });
    it('the section\'s own list read updates the status as well', async () => {
        await enter('c1');
        expect(pbc.charactersSection.status(ctx)).toMatchObject({ kind: 'count', count: 2 });
    });
});

describe('toolbar', () => {
    it('Characters title, count meta, Merge duplicates and refresh; no back', async () => {
        await enter('c1');
        let tb = pbc.charactersSection.toolbar(ctx);
        expect(tb.title).toBe('Characters');
        expect(tb.back).toBeUndefined();
        expect(tb.meta[0].attrs['data-testid']).toBe('pbchar-ws-count');
        expect(tb.meta[0].attrs['data-count']).toBe('3');
        expect(tb.meta[1].attrs['data-count']).toBe('2');
        let merge = tb.actions[0];
        expect(merge.attrs['data-testid']).toBe('pbchar-merge-toggle');
        // the rail's own toggle is not duplicated in the section
        expect(q('[data-testid="pbchar-merge-toggle"]')).toBeNull();
        merge.attrs.onclick();
        expect(pbc.__pbCharStateForTest().mergeMode).toBe(true);
        expect(tb.refresh.testid).toBe('pbchar-ws-refresh');
    });
});

describe('§4.7 cross-links leave through the guard', () => {
    it('affected scene numbers link to edit/scenes/:scene?tab=page; Review to edit/review', async () => {
        affectedBody = { boundNodes: 2, drifted: false, driftedHandles: [], affectedSceneIndexes: [3, 5, 9], staleSceneIndexes: [] };
        await enter('c1');
        expect(calls.filter(c => /\/pages$/.test(c)).length).toBe(1);
        let a3 = q('[data-testid="pbchar-affected-scene-3"]');
        expect(a3.tagName).toBe('A');
        expect(a3.getAttribute('data-scene-objectid')).toBe('sc3');
        // scene 9 has no /pages row → plain text, no link
        expect(q('[data-testid="pbchar-affected-scene-9"]').tagName).toBe('SPAN');
        a3.click();
        await settle();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/book-1/edit/scenes/sc3?tab=page');
        q('[data-testid="pbchar-affected-review"]').click();
        await settle();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/book-1/edit/review');
    });

    it('Open Full Editor asks the guard while a draft is unsaved; Stay does not navigate', async () => {
        await enter('c1');
        let dialogs = [];
        vi.spyOn(Dialog, 'open').mockImplementation((cfg) => { dialogs.push(cfg); });
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        move('agility', 1);
        q('[data-char-full-editor]').click();
        await flush();
        expect(dialogs.length).toBe(1);
        dialogs[0].actions.find(a => a.label === 'Stay').onclick();
        await settle();
        expect(m.route.set).not.toHaveBeenCalled();
        expect(pbc.__pbCharStateForTest().statDrafts.c1.values).toEqual({ agility: 1 });

        q('[data-char-full-editor]').click();
        await flush();
        dialogs[1].actions.find(a => a.label === 'Discard').onclick();
        await settle();
        expect(m.route.set).toHaveBeenCalledWith('/view/olio.charPerson/c1');
        expect(page.patchObject).not.toHaveBeenCalled();
    });
});

describe('wizard step 3 (non-workspace) still works', () => {
    it('initCharacterManager without opts: list click selects directly, sliders are drafts, nothing registered', async () => {
        await pbc.initCharacterManager(BOOK);
        ctx = makeCtx(null);
        root = document.createElement('div');
        document.body.appendChild(root);
        m.mount(root, { view: () => pbc.renderCharacterManagerContent() });
        await settle();
        expect(pbc.__pbCharStateForTest().workspace).toBe(false);
        q('[data-char-item="c2"]').click();
        await settle();
        expect(pbc.__pbCharStateForTest().selectedObjectId).toBe('c2');
        expect(q('[data-testid="pbchar-merge-toggle"]')).not.toBeNull();
        move('agility', 4);
        expect(page.patchObject).not.toHaveBeenCalled();
        expect(anyDirty()).toBe(false);   // only the workspace registers a guard source
        // the draft survives switching characters in this mode
        q('[data-char-item="c1"]').click();
        await settle();
        q('[data-char-item="c2"]').click();
        await settle();
        expect(slider('agility').value).toBe('4');
    });
});
