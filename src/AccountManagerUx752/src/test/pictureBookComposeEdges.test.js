// @vitest-environment jsdom
/**
 * PictureBook V3 composition board (P4-9) — edge cases the P4-9 suites left uncovered (test-author review, 2026-10-22).
 *
 * 1. boardDelta (pictureBookWorkflow.js:1294) value-by-value: enum case/UNKNOWN, blank enum never sent, numeric ''
 *    skipped, string clear sends '', boolean compared directly, absent key skipped.
 * 2. normalizeBoard / normalizeActorRow / normalizeElementRow with ABSENT lists and fields (NON_EMPTY wire).
 * 3. degradationSummary (pictureBookWorkflow.js:1680) and the reader's P4-9 additions in features/pictureBook.js:
 *    the degradation chip / PROMPT_TRUNCATED lines read from the scene node's newest renderPlan artifact (one read
 *    per node per refresh), and the Compose deep-link.
 * 4. composeScene / composeBook wire bodies straight from the workflow (composeBody is private).
 * 5. The board's Image-config-override Save (pb-cmp-config-save) and what a failed camera PATCH does to the draft.
 *
 * No network, LLM or SD: fetch, am7client.clearCache, page.toast, m.route.set and LLMConnector.resolveConfig are stubbed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import am7model from '../core/model.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import * as wf from '../workflows/pictureBookWorkflow.js';

const BOOK = 'book-1';
const PB = '/rest/olio/picture-book/' + BOOK;

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
async function waitFor(cond, ms) {
    let until = Date.now() + (ms || 2000);
    while (!cond()) {
        if (Date.now() > until) throw new Error('waitFor timed out');
        await new Promise(r => setTimeout(r, 2));
    }
}
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
function one(vnode, id) {
    let list = byTestId(vnode, id);
    expect(list.length, 'exactly one ' + id).toBe(1);
    return list[0];
}
function none(vnode, id) { expect(byTestId(vnode, id).length, 'no ' + id).toBe(0); }
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}
function seqOf(calls) { return calls.map(c => c.method + ' ' + c.url); }
function toasts() { return page.toast.mock.calls.map(c => [c[0], c[1]]); }

// ─────────────────────────────── 1. boardDelta ───────────────────────────────

describe('boardDelta — the row-level PATCH body', () => {
    const ROW = { shotType: 'MEDIUM', viewpoint: 'UNKNOWN', mood: 'tense', cameraText: 'low angle', x: 0.1, count: 1, promptLocked: false, promptOverride: '' };

    it('enums compare case-insensitively; UNKNOWN and "" are one value; a changed enum is sent lower-cased', () => {
        expect(wf.boardDelta(ROW, { shotType: 'medium' }, ['shotType'])).toEqual({});
        expect(wf.boardDelta(ROW, { shotType: ' Medium ' }, ['shotType'])).toEqual({});
        expect(wf.boardDelta(ROW, { viewpoint: '' }, ['viewpoint'])).toEqual({});
        expect(wf.boardDelta(ROW, { viewpoint: 'unknown' }, ['viewpoint'])).toEqual({});
        expect(wf.boardDelta(ROW, { shotType: 'WIDE' }, ['shotType'])).toEqual({ shotType: 'wide' });
        expect(wf.boardDelta(ROW, { viewpoint: 'low' }, ['viewpoint'])).toEqual({ viewpoint: 'low' });
    });
    it('(unset) on a row that had a value is sent as "unknown" (the enum\'s no-value member; a bare "" would 400); (unset) on UNKNOWN/blank is not sent', () => {
        expect(wf.boardDelta(ROW, { shotType: '' }, ['shotType'])).toEqual({ shotType: 'unknown' });
        expect(wf.boardDelta(ROW, { shotType: 'UNKNOWN' }, ['shotType'])).toEqual({ shotType: 'unknown' });
        expect(wf.boardDelta(ROW, { shotType: null }, ['shotType'])).toEqual({ shotType: 'unknown' });
        expect(wf.boardDelta(ROW, { viewpoint: '' }, ['viewpoint'])).toEqual({});
        expect(wf.boardDelta({ facing: null }, { facing: '' }, ['facing'])).toEqual({});
        expect(wf.boardDelta({ facing: 'LEFT' }, { facing: '' }, ['facing'])).toEqual({ facing: 'unknown' });
    });
    it('numbers: "" / null / NaN are skipped, a string number is sent as a number, an equal number is not sent', () => {
        expect(wf.boardDelta(ROW, { x: '' }, ['x'])).toEqual({});
        expect(wf.boardDelta(ROW, { x: null }, ['x'])).toEqual({});
        expect(wf.boardDelta(ROW, { x: 'abc' }, ['x'])).toEqual({});
        expect(wf.boardDelta(ROW, { x: '0.1' }, ['x'])).toEqual({});
        expect(wf.boardDelta(ROW, { x: '0.25' }, ['x'])).toEqual({ x: 0.25 });
        expect(wf.boardDelta(ROW, { count: 2 }, ['count'])).toEqual({ count: 2 });
        expect(wf.boardDelta(ROW, { count: '1' }, ['count'])).toEqual({});
    });
    it('int fields (depth, priority, count) are rounded before the compare and the send; box fractions are not', () => {
        const R = { depth: 2, priority: 1, count: 1, x: 0.1 };
        expect(wf.boardDelta(R, { depth: '2.6' }, ['depth'])).toEqual({ depth: 3 });
        expect(wf.boardDelta(R, { depth: 2.4 }, ['depth'])).toEqual({});
        expect(wf.boardDelta(R, { depth: 0 }, ['depth'])).toEqual({ depth: 0 });
        expect(wf.boardDelta(R, { priority: '0.5' }, ['priority']), 'rounds to the stored 1, so nothing to send').toEqual({});
        expect(wf.boardDelta(R, { priority: '3.2' }, ['priority'])).toEqual({ priority: 3 });
        expect(wf.boardDelta(R, { count: 2.5 }, ['count'])).toEqual({ count: 3 });
        expect(wf.boardDelta(R, { x: 0.125 }, ['x'])).toEqual({ x: 0.125 });
        expect(wf.boardDelta({ depth: 0.4 }, { depth: 0 }, ['depth']), 'a legacy fractional row rounds to its layer').toEqual({});
    });
    it('strings: clearing sends ""; null edit of a text field is ""; an unchanged string is not sent', () => {
        expect(wf.boardDelta(ROW, { cameraText: '' }, ['cameraText'])).toEqual({ cameraText: '' });
        expect(wf.boardDelta(ROW, { cameraText: null }, ['cameraText'])).toEqual({ cameraText: '' });
        expect(wf.boardDelta(ROW, { mood: 'tense' }, ['mood'])).toEqual({});
        expect(wf.boardDelta({ mood: null }, { mood: '' }, ['mood'])).toEqual({});
        expect(wf.boardDelta(ROW, { mood: 'calm' }, ['mood'])).toEqual({ mood: 'calm' });
    });
    it('booleans compare directly; only the keys asked for are looked at; keys absent from the edit are skipped', () => {
        expect(wf.boardDelta(ROW, { promptLocked: true }, ['promptLocked'])).toEqual({ promptLocked: true });
        expect(wf.boardDelta(ROW, { promptLocked: false }, ['promptLocked'])).toEqual({});
        expect(wf.boardDelta(ROW, { promptLocked: true, mood: 'calm' }, ['promptLocked'])).toEqual({ promptLocked: true });
        expect(wf.boardDelta(ROW, { mood: 'calm' }, ['promptLocked', 'promptOverride'])).toEqual({});
        expect(wf.boardDelta(ROW, {}, ['mood', 'shotType'])).toEqual({});
    });
    it('tolerates null rows and a non-array key list', () => {
        expect(wf.boardDelta(null, null, ['mood'])).toEqual({});
        expect(wf.boardDelta(null, { mood: 'x' }, ['mood'])).toEqual({ mood: 'x' });
        expect(wf.boardDelta(ROW, { mood: 'x' }, null)).toEqual({});
    });
});

// ─────────────────────────────── 2. normalize* with absent lists ───────────────────────────────

describe('normalizeBoard / normalizeActorRow / normalizeElementRow — NON_EMPTY wire', () => {
    it('normalizeBoard({}) has every container: null ids, a defaulted composition, [] actors and elements', () => {
        let b = wf.normalizeBoard({});
        expect(b.compositionObjectId).toBeNull();
        expect(b.sceneObjectId).toBeNull();
        expect(b.actors).toEqual([]);
        expect(b.elements).toEqual([]);
        expect(b.composition).toMatchObject({ objectId: null, shotType: null, viewpoint: null, cameraText: '', mood: '', settingText: '', action: '', props: '', promptOverride: '', configOverride: '', focalActorObjectId: null, userEdited: false, promptLocked: false, revision: 0 });
        expect(wf.normalizeBoard(null).actors).toEqual([]);
        expect(wf.normalizeBoard({ actors: 'nope', elements: { a: 1 } })).toMatchObject({ actors: [], elements: [] });
    });
    it('normalizeActorRow({}) defaults the box to 0, count to 1, text to "", enums to null; enums are lower-cased', () => {
        expect(wf.normalizeActorRow({})).toEqual({ objectId: null, name: null, characterObjectId: null, characterName: null, apparelObjectId: null, role: null, referencePolicy: null, facing: null, priority: 0, pose: '', expression: '', action: '', description: '', x: 0, y: 0, w: 0, h: 0, depth: 0, count: 1 });
        expect(wf.normalizeActorRow({ role: 'HERO', facing: 'LEFT', referencePolicy: 'Auto', x: 0.5 })).toMatchObject({ role: 'hero', facing: 'left', referencePolicy: 'auto', x: 0.5 });
    });
    it('normalizeElementRow({}) defaults the same way; itemObjectId null until promoted', () => {
        expect(wf.normalizeElementRow({})).toEqual({ objectId: null, elementType: null, importance: null, description: '', itemObjectId: null, x: 0, y: 0, w: 0, h: 0, depth: 0 });
        expect(wf.normalizeElementRow({ elementType: 'PROP', importance: 'High', itemObjectId: 'it-1' })).toMatchObject({ elementType: 'prop', importance: 'high', itemObjectId: 'it-1' });
    });
});

// ─────────────────────────────── 3. degradationSummary ───────────────────────────────

describe('degradationSummary', () => {
    it('counts every entry, lists codes once each (upper-cased), and keeps PROMPT_TRUNCATED messages verbatim', () => {
        let s = wf.degradationSummary({ degradations: [
            { code: 'model_substituted', message: 'm1' },
            { code: 'PROMPT_TRUNCATED', message: 'style dropped: a, b (12 tokens over budget 75)' },
            { code: 'MODEL_SUBSTITUTED', message: 'm2' },
            { code: 'PROMPT_TRUNCATED', message: 'props dropped: c (3 tokens over budget 75)' },
            null
        ] });
        expect(s).toEqual({ count: 5, codes: ['MODEL_SUBSTITUTED', 'PROMPT_TRUNCATED'], truncatedDropped: ['style dropped: a, b (12 tokens over budget 75)', 'props dropped: c (3 tokens over budget 75)'] });
    });
    it('absent / non-array degradations are an empty summary', () => {
        expect(wf.degradationSummary(null)).toEqual({ count: 0, codes: [], truncatedDropped: [] });
        expect(wf.degradationSummary({})).toEqual({ count: 0, codes: [], truncatedDropped: [] });
        expect(wf.degradationSummary({ degradations: 'x' })).toEqual({ count: 0, codes: [], truncatedDropped: [] });
    });
});

// ─────────────────────────────── 4. compose wire bodies ───────────────────────────────

describe('composeScene / composeBook bodies', () => {
    let origFetch;
    let calls;
    beforeEach(() => {
        origFetch = global.fetch;
        calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let url = String(u).replace(/^.*\/rest\//, '/rest/');
            calls.push({ url, method: (init && init.method) || 'GET', body: init && typeof init.body === 'string' ? JSON.parse(init.body) : null });
            return jsonResponse(200, { sceneObjectId: 's1', written: true });
        });
    });
    afterEach(() => { global.fetch = origFetch; });

    it('composeScene sends only set force/chatConfigName; quickStart is never a single-scene key; no opts → {}', async () => {
        await wf.composeScene(BOOK, 's1');
        await wf.composeScene(BOOK, 's1', { force: false, chatConfigName: '' });
        await wf.composeScene(BOOK, 's1', { force: true, quickStart: true });
        await wf.composeScene(BOOK, 's1', { chatConfigName: 'cfg-a' });
        expect(seqOf(calls)).toEqual(Array(4).fill('POST ' + PB + '/scene/s1/compose'));
        expect(calls.map(c => c.body)).toEqual([{}, {}, { force: true }, { chatConfigName: 'cfg-a' }]);
    });
    it('composeBook forwards quickStart/force/chatConfigName only when truthy', async () => {
        await wf.composeBook(BOOK, { quickStart: true, force: false, chatConfigName: 'cfg-b' });
        await wf.composeBook(BOOK, {});
        expect(seqOf(calls)).toEqual(['POST ' + PB + '/compose', 'POST ' + PB + '/compose']);
        expect(calls.map(c => c.body)).toEqual([{ quickStart: true, chatConfigName: 'cfg-b' }, {}]);
    });
});

// ─────────────────────────────── 5. board: config-override Save, failed PATCH ───────────────────────────────

function compositionWire(extra) {
    return Object.assign({
        objectId: 'comp-1', name: 'scene-1-composition', source: 'llm', purpose: 'scene', shotType: 'medium', viewpoint: 'eye_level',
        cameraText: 'low angle', mood: 'tense', settingText: 'a quay at dusk', action: 'Alice waits', props: 'lantern, rope',
        locationObjectId: 'loc-1', focalActorObjectId: 'a1', userEdited: false, promptLocked: false, revision: 3
    }, extra || {});
}
function boardWire(extra) {
    return Object.assign({ bookObjectId: BOOK, sceneObjectId: 's1', compositionObjectId: 'comp-1', composition: compositionWire(),
        actors: [{ objectId: 'a1', characterObjectId: 'c1', characterName: 'Alice', role: 'hero', x: 0.1, y: 0.2, w: 0.3, h: 0.5, depth: 1 }],
        elements: [{ objectId: 'e1', elementType: 'prop', description: 'a brass lantern', importance: 'medium', x: 0.6, y: 0.7, w: 0.1, h: 0.1, depth: 0 }] }, extra || {});
}
function outfitsWire() { return { compositionObjectId: 'comp-1', sceneObjectId: 's1', actors: [] }; }
function planWire() { return { canvas: { width: 1024, height: 768 }, compiled: { base: { prompt: 'a quay at dusk', negative: 'blurry' } }, degradations: [], strategy: { stages: [] } }; }
function editResultWire(extra) { return Object.assign({ compositionObjectId: 'comp-1', sceneObjectId: 's1', revision: 4, nodeStale: true, nodeStatus: 'STALE', updated: true }, extra || {}); }
const PAGES = [{ objectId: 's1', sceneIndex: 1, title: 'Quay' }];

describe('composition board — config override Save and a refused PATCH', () => {
    let origFetch;
    let cmp;
    function st() { return cmp.__composeStateForTest(); }
    function view() { return cmp.composeView.view(); }
    function seedBoard(o) {
        o = o || {};
        cmp.__setComposeStateForTest({
            bookObjectId: BOOK, sceneObjectId: 's1', pages: PAGES, loading: false, error: null, noComposition: false, noCompositionMessage: null,
            board: wf.normalizeBoard(o.board || boardWire()), outfits: {}, plan: wf.normalizePlan(planWire()), planError: null, planLoading: false,
            busy: {}, edits: { composition: null, actors: {}, elements: {} }, lastEdit: null, characters: [], composeRun: null, drag: null,
            overrideInst: o.overrideInst || null, overrideExpanded: !!o.overrideInst, pollIntervalMs: 1
        });
    }
    function wire(route) {
        let calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let url = String(u).replace(/^.*\/rest\//, '/rest/');
            let method = (init && init.method) || 'GET';
            let body = init && typeof init.body === 'string' ? JSON.parse(init.body) : null;
            calls.push({ url, method, body });
            let r = route ? route(method, url, body) : null;
            if (r) return r;
            if (method === 'GET' && url === PB + '/pages') return jsonResponse(200, PAGES);
            if (method === 'GET' && url === PB + '/scene/s1/composition') return jsonResponse(200, boardWire());
            if (method === 'GET' && url === PB + '/composition/comp-1/outfits') return jsonResponse(200, outfitsWire());
            if (method === 'POST' && url === PB + '/scene/s1/plan') return jsonResponse(200, planWire());
            return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
        });
        return calls;
    }
    async function settled(calls) {
        await waitFor(() => Object.keys(st().busy).length === 0 && !st().loading && !st().planLoading && calls.some(c => c.method === 'POST' && c.url === PB + '/scene/s1/plan'));
    }

    beforeEach(async () => {
        origFetch = global.fetch;
        cmp = await import('../features/pictureBookCompose.js');
        cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, pages: [], board: null, loading: false, error: null, noComposition: false, noCompositionMessage: null, loadSeq: 0, outfits: {}, plan: null, planError: null, planLoading: false, busy: {}, edits: null, lastEdit: null, characters: [], composeRun: null, drag: null, overrideInst: null, overrideExpanded: false, sdModels: [], sdLoras: [], pollIntervalMs: 1, chatConfigRef: null, chatConfigResolving: false });
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        vi.spyOn(m.route, 'set').mockImplementation(() => {});
        vi.spyOn(LLMConnector, 'resolveConfig').mockResolvedValue(null);
    });
    afterEach(() => {
        cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, pages: [], board: null, composeRun: null, busy: {}, edits: null, lastEdit: null, overrideInst: null, overrideExpanded: false });
        Dialog._reset();
        global.fetch = origFetch;
        vi.restoreAllMocks();
    });

    it('config Save with panel changes PATCHes configOverride as the sparse olio.sd.config JSON (stored keys merged) and toasts "saved"', async () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ configOverride: '{"width":512,"schema":"olio.sd.config"}' }) }),
            overrideInst: { changes: ['steps', 'cfg'], entity: { steps: 30, cfg: 6.5, name: 'ignored', id: 9 } } });
        let calls = wire((method, url) => method === 'PATCH' && url === PB + '/scene/s1/composition' ? jsonResponse(200, editResultWire({ revision: 5 })) : null);
        let btn = one(view(), 'pb-cmp-config-save');
        expect(btn.attrs.disabled).toBe(false);
        btn.attrs.onclick();
        expect(st().busy.config).toBe(true);
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('PATCH ' + PB + '/scene/s1/composition');
        expect(Object.keys(calls[0].body)).toEqual(['configOverride']);
        expect(JSON.parse(calls[0].body.configOverride)).toEqual({ width: 512, steps: 30, cfg: 6.5, [am7model.jsonModelKey]: 'olio.sd.config' });
        expect(toasts()).toEqual([['success', 'Image config override saved']]);
        expect(st().lastEdit).toEqual({ revision: 5, nodeStale: true, nodeStatus: 'STALE' });
        // the board was re-read and the panel instance dropped, so the next expand re-parses the stored override
        expect(st().overrideInst).toBeNull();
        expect(seqOf(calls).filter(s => s === 'GET ' + PB + '/scene/s1/composition').length).toBe(1);
    });
    it('config Save that blanks every stored key PATCHes configOverride "" and toasts "cleared"', async () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ configOverride: '{"steps":20}' }) }),
            overrideInst: { changes: ['steps'], entity: { steps: '' } } });
        let calls = wire((method, url) => method === 'PATCH' ? jsonResponse(200, editResultWire()) : null);
        one(view(), 'pb-cmp-config-save').attrs.onclick();
        await settled(calls);
        expect(calls[0].method).toBe('PATCH');
        expect(calls[0].body).toEqual({ configOverride: '' });
        expect(toasts()).toEqual([['success', 'Image config override cleared']]);
    });
    it('config Save with NO stored override and NO panel change is "Nothing changed" and sends nothing (like every other Save on the board)', async () => {
        seedBoard({ overrideInst: { changes: [], entity: {} } });
        let calls = wire((method, url) => method === 'PATCH' ? jsonResponse(200, editResultWire()) : null);
        one(view(), 'pb-cmp-config-save').attrs.onclick();
        await new Promise(r => setTimeout(r, 20));
        expect(seqOf(calls), 'an unchanged config Save must not PATCH (a PATCH {configOverride:""} bumps the revision and marks the composition user-edited on the server)').toEqual([]);
        expect(toasts()).toEqual([['info', 'Nothing changed']]);
    });
    it('config Save with a stored override and an untouched panel is "Nothing changed" and sends nothing', async () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ configOverride: '{"steps":20,"schema":"olio.sd.config"}' }) }),
            overrideInst: { changes: [], entity: { steps: 20 } } });
        let calls = wire((method, url) => method === 'PATCH' ? jsonResponse(200, editResultWire()) : null);
        one(view(), 'pb-cmp-config-save').attrs.onclick();
        await new Promise(r => setTimeout(r, 20));
        expect(seqOf(calls)).toEqual([]);
        expect(toasts()).toEqual([['info', 'Nothing changed']]);
        expect(st().overrideInst).not.toBeNull();
    });
    it('a refused config PATCH (400) keeps the open panel instance so the user can correct and retry', async () => {
        seedBoard({ overrideInst: { changes: ['steps'], entity: { steps: 999 } } });
        let calls = wire((method, url) => method === 'PATCH' ? jsonResponse(400, { error: 'steps out of range' }) : null);
        one(view(), 'pb-cmp-config-save').attrs.onclick();
        await settled(calls);
        expect(calls[0].method).toBe('PATCH');
        expect(toasts()).toEqual([['error', '(400) steps out of range']]);
        expect(st().overrideInst, 'panel instance after a refused PATCH').not.toBeNull();
        expect(st().overrideInst.entity.steps).toBe(999);
    });
    it('a refused outfits read (403) is shown on the Actors header, not rendered as every actor having "(none)"', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: 's1', pages: PAGES, board: null, edits: null, busy: {}, overrideInst: null });
        let calls = wire((method, url) => method === 'GET' && url === PB + '/composition/comp-1/outfits' ? jsonResponse(403, { error: 'not permitted' }) : null);
        cmp.composeView.oninit({ attrs: { bookObjectId: BOOK, sceneObjectId: 's1' } });
        await settled(calls);
        expect(st().outfits).toEqual({});
        expect(st().outfitsError).toBe('(403) not permitted');
        let v = view();
        let chip = one(v, 'pb-cmp-outfits-error');
        expect(textOf(chip)).toBe('Outfits not read: (403) not permitted');
        expect(one(v, 'pb-cmp-actor-outfit-chip').attrs['data-source']).toBe('none');
    });
    it('a refused camera PATCH (400) keeps the draft dirty: the typed values stay in the fields after the re-read', async () => {
        seedBoard();
        let calls = wire((method, url) => method === 'PATCH' ? jsonResponse(400, { error: 'shotType must be one of close_up, medium, full_body, wide, establishing' }) : null);
        let v = view();
        one(v, 'pb-cmp-shot').attrs.onchange({ target: { value: 'sideways' } });
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'calm, salt wind' } });
        one(v, 'pb-cmp-save-camera').attrs.onclick();
        await settled(calls);
        expect(calls[0].body).toEqual({ shotType: 'sideways', mood: 'calm, salt wind' });
        expect(toasts()).toEqual([['error', '(400) shotType must be one of close_up, medium, full_body, wide, establishing']]);
        // the server wrote nothing, so the re-read board is unchanged …
        expect(st().board.composition.mood).toBe('tense');
        // … and the user's edit must survive the refusal (nothing was saved, so there is nothing to discard)
        let after = view();
        expect(st().edits.composition && st().edits.composition.mood, 'draft after a refused PATCH').toBe('calm, salt wind');
        expect(one(after, 'pb-cmp-mood').attrs.value).toBe('calm, salt wind');
    });
});

// ─────────────────────────────── 6. reader: degradation chip, truncated lines, Compose link ───────────────────────────────

const PLAN_OID = 'plan-art-1';
const READER_PLAN = {
    planVersion: 'illustration/1', purpose: 'SCENE', canvas: { width: 1216, height: 832 },
    strategy: { name: 'TEXT_ONLY', stages: ['BASE'] }, compiled: { BASE: { prompt: 'a quay', estimatedTokens: '16' } },
    degradations: [
        { code: 'MODEL_SUBSTITUTED', message: 'Model for LOCATION is unresolved: INVENTORY_UNAVAILABLE', stage: 'BASE' },
        { code: 'PROMPT_TRUNCATED', message: 'props dropped: rope, lantern (9 tokens over budget 75)', stage: 'BASE' }
    ],
    estimate: { scenes: 1, stages: 1, variants: 1, steps: 25, measured: false, source: 'NONE' }
};

describe('reader (features/pictureBook.js) — P4-9 degradation chip and Compose deep-link', () => {
    let origFetch;
    let pbf;
    let calls;
    const PAGES2 = [
        { objectId: 's1', sceneIndex: 1, title: 'Quay', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', pinned: false, dataObjectId: 'd1' },
        { objectId: 's2', sceneIndex: 2, title: 'Market', pinned: false }
    ];

    function nextButton() {
        let btns = findAll(pbf.renderPb2Header(), v => v.tag === 'button' && textOf(v) === 'chevron_right');
        expect(btns.length, 'one next-page button').toBe(1);
        return btns[0];
    }

    beforeEach(async () => {
        origFetch = global.fetch;
        pbf = await import('../features/pictureBook.js');
        pbf.__pb2StopWatchingForTest();
        pbf.__setPb2V3StateForTest({ pb2RenderPath: 'v3', pb2Run: null, pb2ResumeRun: null, pb2Submitting: false, pb2CurrentPage: 0, pb2BookObjectId: 'B1', pb2BookName: 'Quay', pb2Pages: PAGES2, pb2SceneDegradations: {} });
        calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let url = String(u).replace(/^.*\/rest\//, '/rest/');
            calls.push(((init && init.method) || 'GET') + ' ' + url);
            if (url === '/rest/olio/picture-book/B1/workflow/node/n1') return jsonResponse(200, { objectId: 'n1', nodeType: 'RENDER_PLAN', artifacts: { renderPlan: [{ objectId: 'older', revision: 1, role: 'renderPlan' }, { objectId: PLAN_OID, revision: 2, role: 'renderPlan' }] } });
            if (url === '/rest/olio/picture-book/B1/artifact/' + PLAN_OID) return jsonResponse(200, { objectId: PLAN_OID, artifactType: 'TEXT', role: 'renderPlan', revision: 2, artifactText: JSON.stringify(READER_PLAN) });
            return jsonResponse(404, { error: 'unexpected ' + url });
        });
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        vi.spyOn(m.route, 'set').mockImplementation(() => {});
    });
    afterEach(() => {
        pbf.__pb2StopWatchingForTest();
        pbf.__setPb2V3StateForTest({ pb2RenderPath: 'v1', pb2Run: null, pb2ResumeRun: null, pb2CurrentPage: 0, pb2Pages: [], pb2SceneDegradations: {} });
        global.fetch = origFetch;
        vi.restoreAllMocks();
    });

    it('turning to a page reads its node\'s NEWEST renderPlan once; the chip shows count + codes, the truncated lines are verbatim; the read is cached', async () => {
        nextButton().attrs.onclick();
        await waitFor(() => { let e = pbf.__pb2V3StateForTest().pb2SceneDegradations.n1; return e && !e.loading; });
        expect(calls).toEqual(['GET /rest/olio/picture-book/B1/workflow/node/n1', 'GET /rest/olio/picture-book/B1/artifact/' + PLAN_OID]);
        let entry = pbf.__pb2V3StateForTest().pb2SceneDegradations.n1;
        expect(entry.error).toBeNull();
        expect(entry.summary).toEqual({ count: 2, codes: ['MODEL_SUBSTITUTED', 'PROMPT_TRUNCATED'], truncatedDropped: ['props dropped: rope, lantern (9 tokens over budget 75)'] });

        let v = pbf.renderPb2ScenePage();
        let chip = one(v, 'pb2-scene-degradations');
        expect(chip.attrs['data-count']).toBe(2);
        expect(chip.attrs['data-codes']).toBe('MODEL_SUBSTITUTED,PROMPT_TRUNCATED');
        expect(chip.attrs.title).toBe('The last compiled plan degraded 2 things: MODEL_SUBSTITUTED, PROMPT_TRUNCATED');
        expect(textOf(chip)).toContain('2 degraded');
        expect(textOf(one(v, 'pb2-scene-truncated'))).toBe('props dropped: rope, lantern (9 tokens over budget 75)');

        // Cover and back: the cached node is not re-read.
        pbf.__setPb2V3StateForTest({ pb2CurrentPage: 0 });
        nextButton().attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(calls.length).toBe(2);
        // A page whose node has no renderPlan yet / no node at all: nothing is read and nothing is shown.
        nextButton().attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(pbf.__pb2V3StateForTest().pb2CurrentPage).toBe(2);
        expect(calls.length).toBe(2);
        none(pbf.renderPb2ScenePage(), 'pb2-scene-degradations');
        none(pbf.renderPb2ScenePage(), 'pb2-scene-truncated');
    });
    it('a plan with no degradations shows no chip and no truncated block; a failed node read lands in the entry error, never a toast', async () => {
        pbf.__setPb2V3StateForTest({ pb2CurrentPage: 1, pb2SceneDegradations: { n1: { loading: false, summary: wf.degradationSummary({ degradations: [] }), error: null } } });
        let v = pbf.renderPb2ScenePage();
        none(v, 'pb2-scene-degradations');
        none(v, 'pb2-scene-truncated');
        expect(pbf.pb2SceneDegradationEntry(PAGES2[0], null)).toBeNull();
        expect(pbf.pb2SceneDegradationEntry(PAGES2[1], { n1: {} })).toBeNull();

        pbf.__setPb2V3StateForTest({ pb2CurrentPage: 0, pb2SceneDegradations: {} });
        global.fetch = vi.fn(async () => jsonResponse(500, { error: 'node read failed' }));
        nextButton().attrs.onclick();
        await waitFor(() => { let e = pbf.__pb2V3StateForTest().pb2SceneDegradations.n1; return e && !e.loading; });
        let entry = pbf.__pb2V3StateForTest().pb2SceneDegradations.n1;
        expect(entry.summary).toBeNull();
        // nodeView (pictureBookWorkflow.js:73) reports the status, not the body
        expect(entry.error).toBe('nodeView failed: 500');
        expect(page.toast).not.toHaveBeenCalled();
        let v2 = pbf.renderPb2ScenePage();
        none(v2, 'pb2-scene-degradations');
        // … but the failed read is visible on the page, not silently "no degradations"
        let errChip = one(v2, 'pb2-scene-degradations-error');
        expect(errChip.attrs.title).toBe('nodeView failed: 500');
        expect(textOf(errChip)).toContain('plan not read');
    });
    it('the Compose button routes to /picture-book/{book}/compose/{scene}; under v1 there is no button, no chip and no node read', async () => {
        pbf.__setPb2V3StateForTest({ pb2CurrentPage: 1 });
        let btn = one(pbf.renderPb2ScenePage(), 'pb2-scene-compose');
        expect(btn.attrs.title).toBe('Open the composition board for this scene (camera, actors, elements, wardrobe)');
        btn.attrs.onclick();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/B1/compose/s1');

        pbf.__setPb2V3StateForTest({ pb2RenderPath: 'v1', pb2CurrentPage: 0, pb2SceneDegradations: {} });
        nextButton().attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(calls).toEqual([]);
        let v = pbf.renderPb2ScenePage();
        none(v, 'pb2-scene-compose');
        none(v, 'pb2-scene-degradations');
    });
});
