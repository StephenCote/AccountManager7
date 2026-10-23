// @vitest-environment jsdom
/**
 * PictureBook V3 composition board (P4-9, design §26) — features/pictureBookCompose.js rendering and writes.
 *
 * Why: the board shows server state only (GET /{book}/scene/{scene}/composition + GET .../outfits + a freshly
 * compiled POST .../plan, re-read after every write); every edit is ONE row-level PATCH against the P4-8 routes
 * carrying only the boardDelta of that row; deletes are confirm-gated; Re-compose warns when the composition is
 * hand-edited; the compose dialog submits POST /{book}/compose and follows the 202 job to its terminal counter.
 * No network, LLM or SD is touched: fetch, am7client.clearCache, page.toast, Dialog.confirm and
 * LLMConnector.resolveConfig (the board's library-only chat-config default lookup) are stubbed.
 *
 * Render-function vnode trees are asserted without mounting, so component lifecycles (PbPlanInspector,
 * SdConfigPanel) never run; handlers are invoked directly.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { PbPlanInspector } from '../components/PbPlanInspector.js';
import { LLMConnector } from '../chat/LLMConnector.js';

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

// Wire fixtures: PbServiceFacade board / outfits / plan DTOs (NON_EMPTY, so absent lists are normal on the wire).
function compositionWire(extra) {
    return Object.assign({
        objectId: 'comp-1', name: 'scene-1-composition', source: 'llm', purpose: 'scene', shotType: 'medium', viewpoint: 'eye_level',
        cameraText: 'low angle', mood: 'tense', settingText: 'a quay at dusk', action: 'Alice waits', props: 'lantern, rope',
        locationObjectId: 'loc-1', focalActorObjectId: 'a1', userEdited: false, promptLocked: false, revision: 3
    }, extra || {});
}
function actorWire(extra) {
    return Object.assign({
        objectId: 'a1', characterObjectId: 'c1', characterName: 'Alice', role: 'hero', priority: 1, pose: 'standing', expression: 'wary',
        action: 'waits', facing: 'left', referencePolicy: 'auto', x: 0.1, y: 0.2, w: 0.3, h: 0.5, depth: 1, count: 1
    }, extra || {});
}
function elementWire(extra) {
    return Object.assign({ objectId: 'e1', elementType: 'prop', description: 'a brass lantern', importance: 'medium', x: 0.6, y: 0.7, w: 0.1, h: 0.1, depth: 0 }, extra || {});
}
function boardWire(extra) {
    return Object.assign({ bookObjectId: BOOK, sceneObjectId: 's1', compositionObjectId: 'comp-1', composition: compositionWire(), actors: [actorWire()], elements: [elementWire()] }, extra || {});
}
function outfitsWire(extra) {
    return Object.assign({ compositionObjectId: 'comp-1', sceneObjectId: 's1', actors: [
        { actorObjectId: 'a1', actorName: 'Alice', characterObjectId: 'c1', apparelObjectId: 'ap2', apparelName: 'Rain cloak', apparelDescription: 'a grey wool cloak', source: 'carried', fromSceneIndex: 2 }
    ] }, extra || {});
}
function planWire(extra) {
    return Object.assign({ canvas: { width: 1024, height: 768 }, compiled: { base: { prompt: 'a quay at dusk, Alice waits', negative: 'blurry', estimatedTokens: 40 } }, degradations: [], strategy: { stages: [] } }, extra || {});
}
function editResultWire(extra) {
    return Object.assign({ compositionObjectId: 'comp-1', sceneObjectId: 's1', revision: 4, nodeStale: true, nodeStatus: 'STALE', updated: true }, extra || {});
}
const PAGES = [{ objectId: 's1', sceneIndex: 1, title: 'Quay' }, { objectId: 's2', sceneIndex: 2, title: 'Market' }, { objectId: 's3', sceneIndex: 3 }];

let origFetch;
let cmp;
let wf;

function st() { return cmp.__composeStateForTest(); }
function view() { return cmp.composeView.view(); }

function seedBoard(opts) {
    let o = opts || {};
    cmp.__setComposeStateForTest({
        bookObjectId: BOOK, sceneObjectId: o.sceneObjectId || 's1', pages: PAGES, loading: false, error: null, noComposition: false, noCompositionMessage: null,
        board: wf.normalizeBoard(o.board || boardWire()),
        outfits: o.outfits !== undefined ? o.outfits : { a1: wf.normalizeResolvedOutfit(outfitsWire().actors[0]) },
        plan: o.plan !== undefined ? o.plan : wf.normalizePlan(planWire()),
        planError: null, planLoading: false, busy: {}, edits: { composition: null, actors: {}, elements: {} }, lastEdit: o.lastEdit || null,
        characters: o.characters || [], composeRun: o.composeRun || null, drag: null, overrideInst: null, overrideExpanded: false, pollIntervalMs: 1
    });
}

beforeEach(async () => {
    origFetch = global.fetch;
    cmp = await import('../features/pictureBookCompose.js');
    wf = await import('../workflows/pictureBookWorkflow.js');
    cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, pages: [], board: null, loading: false, error: null, noComposition: false, noCompositionMessage: null, loadSeq: 0, outfits: {}, plan: null, planError: null, planLoading: false, busy: {}, edits: null, lastEdit: null, characters: [], composeRun: null, drag: null, overrideInst: null, overrideExpanded: false, sdModels: [], sdLoras: [], pollIntervalMs: 1, chatConfigRef: null, chatConfigResolving: false });
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
    vi.spyOn(LLMConnector, 'resolveConfig').mockResolvedValue(null);
});

afterEach(() => {
    cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, pages: [], board: null, composeRun: null, busy: {}, edits: null, lastEdit: null, chatConfigRef: null, chatConfigResolving: false });
    Dialog._reset();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

/**
 * fetch router for the board wire. o.route(method, url, body, calls) answers first; then GET /rest/job/{id} via
 * o.job(id, nth); then the three reads every refetch issues (composition, outfits, plan) and the page list.
 */
function wire(o) {
    o = o || {};
    let calls = [];
    let polls = {};
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u).replace(/^.*\/rest\//, '/rest/');
        let method = (init && init.method) || 'GET';
        let body = init && typeof init.body === 'string' ? JSON.parse(init.body) : null;
        calls.push({ url, method, body, rawBody: init ? init.body : undefined });
        if (o.route) {
            let r = o.route(method, url, body, calls);
            if (r) return r;
        }
        let mj = /^\/rest\/job\/([^/?]+)$/.exec(url);
        if (mj && method === 'GET') {
            polls[mj[1]] = (polls[mj[1]] || 0) + 1;
            return o.job ? o.job(mj[1], polls[mj[1]]) : jsonResponse(404, { error: 'Job not found' });
        }
        if (method === 'GET' && url === PB + '/pages') return jsonResponse(200, o.pages ? o.pages(calls) : PAGES);
        if (method === 'GET' && url === PB + '/scene/s1/composition') return jsonResponse(200, o.board ? o.board(calls) : boardWire());
        if (method === 'GET' && url === PB + '/composition/comp-1/outfits') return jsonResponse(200, o.outfits ? o.outfits(calls) : outfitsWire());
        if (method === 'POST' && url === PB + '/scene/s1/plan') return jsonResponse(200, o.plan ? o.plan(calls) : planWire());
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}

function reads(calls) { return seqOf(calls).filter(s => s === 'GET ' + PB + '/scene/s1/composition' || s === 'GET ' + PB + '/composition/comp-1/outfits' || s === 'POST ' + PB + '/scene/s1/plan'); }
async function settled(calls) {
    await waitFor(() => Object.keys(st().busy).length === 0 && !st().loading && !st().planLoading && calls.some(c => c.method === 'POST' && c.url === PB + '/scene/s1/plan'));
}

// ─────────────────────────────── loading ───────────────────────────────

describe('init', () => {
    it('loads the pages, then the composition, its outfits and a fresh plan — in that order, one read each', async () => {
        let calls = wire();
        cmp.composeView.oninit({ attrs: { bookObjectId: BOOK } });
        await waitFor(() => st().board && st().plan && !st().planLoading);
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/pages', 'GET ' + PB + '/scene/s1/composition', 'GET ' + PB + '/composition/comp-1/outfits', 'POST ' + PB + '/scene/s1/plan']);
        expect(calls[3].body).toEqual({});
        let s = st();
        expect(s.sceneObjectId).toBe('s1');
        expect(s.pages).toEqual(PAGES);
        expect(s.board.compositionObjectId).toBe('comp-1');
        expect(s.board.composition.shotType).toBe('medium');
        expect(s.outfits.a1.source).toBe('carried');
        expect(s.plan.canvas.width).toBe(1024);
        expect(s.edits).toEqual({ composition: null, actors: {}, elements: {} });
    });
    it('a 404 "has no composition" is the empty state, not an error', async () => {
        wire({ route: (method, url) => method === 'GET' && url === PB + '/scene/s1/composition' ? jsonResponse(404, { error: 'Scene 1 has no composition yet' }) : null });
        cmp.composeView.oninit({ attrs: { bookObjectId: BOOK, sceneObjectId: 's1' } });
        await waitFor(() => st().noComposition);
        let s = st();
        expect(s.error).toBeNull();
        expect(s.board).toBeNull();
        expect(s.noCompositionMessage).toBe('(404) Scene 1 has no composition yet');
        let v = view();
        one(v, 'pb-cmp-empty');
        expect(textOf(one(v, 'pb-cmp-empty-message'))).toBe('(404) Scene 1 has no composition yet');
        expect(textOf(one(v, 'pb-cmp-compose-scene'))).toContain('Compose this scene');
        none(v, 'pb-cmp-camera');
    });
    it('any other failure is shown verbatim', async () => {
        wire({ route: (method, url) => method === 'GET' && url === PB + '/scene/s1/composition' ? jsonResponse(403, { error: 'Not permitted' }) : null });
        cmp.composeView.oninit({ attrs: { bookObjectId: BOOK, sceneObjectId: 's1' } });
        await waitFor(() => st().error);
        expect(st().error).toBe('(403) Not permitted');
        expect(st().noComposition).toBe(false);
        expect(textOf(one(view(), 'pb-cmp-error'))).toBe('(403) Not permitted');
    });
    it('a book with no pages says so and reads nothing else', async () => {
        let calls = wire({ pages: () => [] });
        cmp.composeView.oninit({ attrs: { bookObjectId: BOOK } });
        await waitFor(() => st().pages !== null && calls.length >= 1);
        await new Promise(r => setTimeout(r, 10));
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/pages']);
        expect(st().sceneObjectId).toBeNull();
        expect(textOf(one(view(), 'pb-cmp-no-scenes'))).toBe('This book has no pages to compose.');
    });
    it('shows Loading… while the first read is in flight', () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: 's1', pages: PAGES, loading: true, board: null });
        let v = view();
        one(v, 'pb-cmp-loading');
        expect(one(v, 'pb-cmp-refresh').attrs.disabled).toBe(true);
    });
});

// ─────────────────────────────── header ───────────────────────────────

describe('header', () => {
    it('lists the pages, disables prev on the first scene and routes next to the following scene', () => {
        seedBoard();
        let v = view();
        let sel = one(v, 'pb-cmp-scene-select');
        expect(sel.attrs.value).toBe('s1');
        expect(sel.children.map(o => [o.attrs.value, textOf(o), !!o.attrs.selected])).toEqual([['s1', '1 · Quay', true], ['s2', '2 · Market', false], ['s3', '3', false]]);
        expect(one(v, 'pb-cmp-scene-prev').attrs.disabled).toBe(true);
        let next = one(v, 'pb-cmp-scene-next');
        expect(next.attrs.disabled).toBe(false);
        next.attrs.onclick();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/book-1/compose/s2');
        sel.attrs.onchange({ target: { value: 's3' } });
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/book-1/compose/s3');
        one(v, 'pb-cmp-back').attrs.onclick();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/v2/book-1');
    });
    it('disables next on the last scene and ignores a no-op scene change', () => {
        seedBoard({ sceneObjectId: 's3' });
        let v = view();
        expect(one(v, 'pb-cmp-scene-next').attrs.disabled).toBe(true);
        expect(one(v, 'pb-cmp-scene-prev').attrs.disabled).toBe(false);
        one(v, 'pb-cmp-scene-select').attrs.onchange({ target: { value: 's3' } });
        expect(m.route.set).not.toHaveBeenCalled();
    });
    it('Refresh clears the scene/node/artifact caches and re-reads the board', async () => {
        seedBoard();
        let calls = wire();
        one(view(), 'pb-cmp-refresh').attrs.onclick();
        await settled(calls);
        expect(am7client.clearCache.mock.calls.map(c => c[0]).sort()).toEqual(['olio.pb.artifact', 'olio.pb.node', 'olio.pb.scene']);
        expect(am7client.clearCache.mock.calls.every(c => c[1] === true)).toBe(true);
        expect(reads(calls)).toEqual(['GET ' + PB + '/scene/s1/composition', 'GET ' + PB + '/composition/comp-1/outfits', 'POST ' + PB + '/scene/s1/plan']);
    });
});

// ─────────────────────────────── chat config ───────────────────────────────
// Every compose on the board sends the picked chat config NAME as chatConfigName (design §24 note: a compose
// without one falls to the organization's generalChat, keyless on the Docker stack → "LLM unavailable").

describe('chat config', () => {
    it('withChatConfig adds the trimmed name only when a ref is picked and never mutates the input', () => {
        let opts = { force: true };
        expect(cmp.withChatConfig(opts, { name: ' e2e-chapbook-llm ', objectId: 'cc1' })).toEqual({ force: true, chatConfigName: 'e2e-chapbook-llm' });
        expect(opts).toEqual({ force: true });
        expect(cmp.withChatConfig({}, null)).toEqual({});
        expect(cmp.withChatConfig({ quickStart: true }, { name: '   ' })).toEqual({ quickStart: true });
        expect(cmp.withChatConfig(undefined, { objectId: 'cc1' })).toEqual({});
    });
    it('chatConfigLabel: the picked name, else "Resolving default…" while resolving, else the click hint', () => {
        expect(cmp.chatConfigLabel({ name: 'contentAnalysis' }, true)).toBe('contentAnalysis');
        expect(cmp.chatConfigLabel(null, true)).toBe('Resolving default…');
        expect(cmp.chatConfigLabel(null, false)).toBe('(click to select)');
    });
    it('init resolves the library default in order (contentAnalysis, then generalChat) without any fetch and shows it in the header', async () => {
        LLMConnector.resolveConfig.mockImplementation(async name => name === 'generalChat' ? { name: 'generalChat', objectId: 'cc-gen' } : null);
        let calls = wire();
        cmp.composeView.oninit({ attrs: { bookObjectId: BOOK } });
        await waitFor(() => st().board && st().plan && !st().planLoading && !st().chatConfigResolving);
        expect(LLMConnector.resolveConfig.mock.calls.map(c => c[0])).toEqual(cmp.DEFAULT_CHAT_CONFIG_NAMES);
        expect(cmp.DEFAULT_CHAT_CONFIG_NAMES).toEqual(['contentAnalysis', 'generalChat']);
        expect(st().chatConfigRef).toEqual({ name: 'generalChat', objectId: 'cc-gen' });
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/pages', 'GET ' + PB + '/scene/s1/composition', 'GET ' + PB + '/composition/comp-1/outfits', 'POST ' + PB + '/scene/s1/plan']);
        expect(textOf(one(view(), 'pb-cmp-chatconfig'))).toContain('generalChat');
    });
    it('no library default leaves the control on the click hint; a picker selection wins and is kept', async () => {
        seedBoard();
        let v = view();
        expect(textOf(one(v, 'pb-cmp-chatconfig'))).toContain('(click to select)');
        let { ObjectPicker } = await import('../components/picker.js');
        vi.spyOn(ObjectPicker, 'openLibrary').mockImplementation(async opts => { opts.onSelect({ name: 'e2e-chapbook-llm', objectId: 'cc-e2e' }); });
        one(v, 'pb-cmp-chatconfig').attrs.onclick();
        await waitFor(() => st().chatConfigRef);
        expect(ObjectPicker.openLibrary.mock.calls[0][0]).toMatchObject({ libraryType: 'chatConfig', title: 'Select Chat Config' });
        expect(st().chatConfigRef).toEqual({ name: 'e2e-chapbook-llm', objectId: 'cc-e2e' });
        expect(textOf(one(view(), 'pb-cmp-chatconfig'))).toContain('e2e-chapbook-llm');
        ObjectPicker.openLibrary.mockImplementation(async opts => { opts.onSelect(null); });
        one(view(), 'pb-cmp-chatconfig').attrs.onclick();
        await new Promise(r => setTimeout(r, 5));
        expect(st().chatConfigRef).toEqual({ name: 'e2e-chapbook-llm', objectId: 'cc-e2e' });
    });
    it('a picked config rides on Re-compose, the empty-state compose and Compose book… as chatConfigName', async () => {
        seedBoard();
        cmp.__setComposeStateForTest({ chatConfigRef: { name: 'e2e-chapbook-llm', objectId: 'cc-e2e' } });
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/scene/s1/compose' ? jsonResponse(200, { written: true, source: 'llm', actors: [], elements: [] }) : null });
        one(view(), 'pb-cmp-recompose').attrs.onclick();
        await settled(calls);
        expect(calls[0].body).toEqual({ force: true, chatConfigName: 'e2e-chapbook-llm' });

        cmp.__setComposeStateForTest({ board: null, noComposition: true, noCompositionMessage: 'This scene has no composition yet', busy: {}, chatConfigRef: { name: 'e2e-chapbook-llm', objectId: 'cc-e2e' } });
        calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/scene/s1/compose' ? jsonResponse(200, { written: true, source: 'llm', actors: [], elements: [] }) : null });
        one(view(), 'pb-cmp-compose-scene').attrs.onclick();
        await settled(calls);
        expect(calls[0].body).toEqual({ chatConfigName: 'e2e-chapbook-llm' });

        seedBoard();
        cmp.__setComposeStateForTest({ chatConfigRef: { name: 'e2e-chapbook-llm', objectId: 'cc-e2e' } });
        calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(202, { jobId: 'j9', submitted: true, sceneCount: 3, scenesToCompose: 3 }) : null,
            job: id => jsonResponse(200, { jobId: id, terminal: true, current: 3, total: 3, result: { composed: 3, current: 0, scenes: [] } })
        });
        one(view(), 'pb-cmp-compose-book').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=pb-cmp-compose-chatconfig]').textContent).toBe('Chat config: e2e-chapbook-llm');
        let force = container.querySelector('[data-testid=pb-cmp-force]');
        force.checked = true;
        force.dispatchEvent(new Event('change'));
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        await waitFor(() => calls.some(c => c.url === PB + '/compose'));
        expect(calls[0].body).toEqual({ force: true, chatConfigName: 'e2e-chapbook-llm' });
        await waitFor(() => st().composeRun && st().composeRun.result);
        await settled(calls);
        m.render(container, null);
    });
    it('the compose dialog says when no config is picked, and the bodies carry no chatConfigName', async () => {
        seedBoard();
        one(view(), 'pb-cmp-compose-book').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=pb-cmp-compose-chatconfig]').textContent).toBe('Chat config: (none picked — the server uses its default)');
        m.render(container, null);
        Dialog._reset();
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/scene/s1/compose' ? jsonResponse(200, { written: true, source: 'llm', actors: [], elements: [] }) : null });
        one(view(), 'pb-cmp-recompose').attrs.onclick();
        await settled(calls);
        expect(calls[0].body).toEqual({ force: true });
    });
    it('switching books forgets the pick and re-resolves the default for the new book', async () => {
        seedBoard();
        cmp.__setComposeStateForTest({ chatConfigRef: { name: 'e2e-chapbook-llm', objectId: 'cc-e2e' } });
        LLMConnector.resolveConfig.mockResolvedValue({ name: 'contentAnalysis', objectId: 'cc-ca' });
        global.fetch = vi.fn(async (u, init) => {
            let url = String(u).replace(/^.*\/rest\//, '/rest/');
            if (url === '/rest/olio/picture-book/book-2/pages') return jsonResponse(200, []);
            return jsonResponse(404, { error: 'unmocked ' + url });
        });
        cmp.composeView.oninit({ attrs: { bookObjectId: 'book-2' } });
        await waitFor(() => !st().chatConfigResolving && st().chatConfigRef && st().chatConfigRef.name === 'contentAnalysis');
        expect(LLMConnector.resolveConfig.mock.calls.map(c => c[0])).toEqual(['contentAnalysis']);
        expect(st().bookObjectId).toBe('book-2');
    });
});

// ─────────────────────────────── board rendering ───────────────────────────────

describe('board', () => {
    it('shows the status chips from the composition', () => {
        seedBoard();
        let v = view();
        expect(textOf(one(v, 'pb-cmp-revision'))).toBe('rev 3');
        expect(textOf(one(v, 'pb-cmp-source'))).toBe('llm');
        expect(textOf(one(v, 'pb-cmp-purpose'))).toBe('scene');
        none(v, 'pb-cmp-user-edited');
        none(v, 'pb-cmp-locked');
        none(v, 'pb-cmp-stale');
        none(v, 'pb-cmp-recompose-warning');
        expect(textOf(one(v, 'pb-cmp-status'))).toContain('location set');
    });
    it('flags hand-edited, locked and a stale render node after an edit', () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ userEdited: true, promptLocked: true, locationObjectId: null }) }), lastEdit: { revision: 7, nodeStale: true, nodeStatus: 'STALE' } });
        let v = view();
        expect(textOf(one(v, 'pb-cmp-revision'))).toBe('rev 7');
        expect(textOf(one(v, 'pb-cmp-user-edited'))).toBe('user-edited');
        expect(textOf(one(v, 'pb-cmp-locked'))).toBe('prompt locked');
        expect(textOf(one(v, 'pb-cmp-stale'))).toBe('render stale · STALE');
        expect(textOf(one(v, 'pb-cmp-recompose-warning'))).toBe('Hand-edited: re-composing overwrites it');
        expect(textOf(one(v, 'pb-cmp-status'))).toContain('no location');
    });
    it('renders the camera fields from the composition with the fallback enum vocabulary', () => {
        seedBoard();
        let v = view();
        let shot = one(v, 'pb-cmp-shot');
        expect(shot.attrs.value).toBe('medium');
        expect(shot.children.map(o => o.attrs.value)).toEqual(['', 'close_up', 'medium', 'full_body', 'wide', 'establishing']);
        expect(shot.children.map(o => textOf(o))).not.toContain('unknown');
        expect(one(v, 'pb-cmp-viewpoint').attrs.value).toBe('eye_level');
        let focal = one(v, 'pb-cmp-focal');
        expect(focal.attrs.value).toBe('a1');
        expect(focal.children.map(o => [o.attrs.value, textOf(o)])).toEqual([['', '(none)'], ['a1', 'Alice']]);
        expect(one(v, 'pb-cmp-camera-text').attrs.value).toBe('low angle');
        expect(one(v, 'pb-cmp-mood').attrs.value).toBe('tense');
        expect(one(v, 'pb-cmp-setting').attrs.value).toBe('a quay at dusk');
        expect(one(v, 'pb-cmp-action').attrs.value).toBe('Alice waits');
        expect(one(v, 'pb-cmp-props').attrs.value).toBe('lantern, rope');
        expect(one(v, 'pb-cmp-prompt-override').attrs.value).toBe('');
        expect(one(v, 'pb-cmp-prompt-locked').attrs.checked).toBe(false);
    });
    it('a stored UNKNOWN (or null) enum selects the (unset) option, not a blank select', () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ shotType: 'UNKNOWN', viewpoint: null }), actors: [actorWire({ facing: 'unknown' })] }) });
        let v = view();
        expect(one(v, 'pb-cmp-shot').attrs.value).toBe('');
        expect(one(v, 'pb-cmp-viewpoint').attrs.value).toBe('');
        expect(one(one(v, 'pb-cmp-actor-a1'), 'pb-cmp-actor-facing').attrs.value).toBe('');
    });
    it('renders one actor row with its outfit chip and one element row', () => {
        seedBoard();
        let v = view();
        expect(textOf(one(v, 'pb-cmp-actors'))).toContain('Actors (1)');
        let row = one(v, 'pb-cmp-actor-a1');
        expect(row.attrs['data-actor-objectid']).toBe('a1');
        expect(textOf(row)).toContain('Alice');
        let chip = one(row, 'pb-cmp-actor-outfit-chip');
        expect(textOf(chip)).toBe('Rain cloak (from scene 2)');
        expect(chip.attrs['data-source']).toBe('carried');
        expect(chip.attrs.title).toBe('a grey wool cloak');
        expect(one(row, 'pb-cmp-actor-outfit-pick').attrs.disabled).toBe(false);
        expect(one(row, 'pb-cmp-actor-role').attrs.value).toBe('hero');
        expect(one(row, 'pb-cmp-actor-facing').attrs.value).toBe('left');
        expect(one(row, 'pb-cmp-actor-policy').attrs.value).toBe('auto');
        expect(one(row, 'pb-cmp-actor-priority').attrs.value).toBe(1);
        expect(one(row, 'pb-cmp-actor-pose').attrs.value).toBe('standing');
        expect(one(row, 'pb-cmp-actor-x').attrs.value).toBe(0.1);
        expect(one(row, 'pb-cmp-actor-depth').attrs.value).toBe(1);
        expect(one(row, 'pb-cmp-actor-depth').attrs.step, 'depth is the int layer order').toBe(1);
        expect(one(row, 'pb-cmp-actor-depth').attrs.max).toBeUndefined();
        none(row, 'pb-cmp-actor-description');
        expect(textOf(one(v, 'pb-cmp-elements'))).toContain('Elements (1)');
        let el = one(v, 'pb-cmp-element-e1');
        expect(textOf(el)).toContain('a brass lantern');
        none(el, 'pb-cmp-element-item');
        expect(one(el, 'pb-cmp-element-promote').attrs.disabled).toBe(false);
        expect(one(el, 'pb-cmp-element-type').attrs.value).toBe('prop');
        expect(one(el, 'pb-cmp-element-importance').attrs.value).toBe('medium');
        expect(one(el, 'pb-cmp-element-description').attrs.value).toBe('a brass lantern');
    });
    it('an unnamed actor shows its description field, a "(none)" chip and no wardrobe picker', () => {
        seedBoard({ board: boardWire({ actors: [actorWire({ objectId: 'a9', characterObjectId: null, characterName: null, description: 'a passing fisherman' })] }), outfits: {} });
        let row = one(view(), 'pb-cmp-actor-a9');
        expect(textOf(row)).toContain('a passing fisherman');
        expect(textOf(row)).toContain('unnamed');
        let chip = one(row, 'pb-cmp-actor-outfit-chip');
        expect(textOf(chip)).toBe('(none)');
        expect(chip.attrs['data-source']).toBe('none');
        expect(one(row, 'pb-cmp-actor-outfit-pick').attrs.disabled).toBe(true);
        expect(one(row, 'pb-cmp-actor-description').attrs.value).toBe('a passing fisherman');
    });
    it('a promoted element shows the item chip and disables Promote', () => {
        seedBoard({ board: boardWire({ elements: [elementWire({ itemObjectId: 'it-1' })] }) });
        let el = one(view(), 'pb-cmp-element-e1');
        expect(one(el, 'pb-cmp-element-item').attrs.title).toBe('it-1');
        expect(one(el, 'pb-cmp-element-promote').attrs.disabled).toBe(true);
    });
    it('a ChapBook page disables adding actors and still allows elements', () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ purpose: 'chapbook_page' }), actors: [] }), outfits: {} });
        let v = view();
        expect(textOf(one(v, 'pb-cmp-purpose'))).toBe('chapbook_page');
        expect(textOf(one(v, 'pb-cmp-actors-disabled'))).toBe('Actors are not enabled on ChapBook pages');
        none(v, 'pb-cmp-actor-add');
        expect(one(v, 'pb-cmp-element-add').attrs.disabled).toBe(false);
    });
    it('lists every actor and element and says so when there are none', () => {
        seedBoard({ board: boardWire({ actors: [actorWire(), actorWire({ objectId: 'a2', characterObjectId: 'c2', characterName: 'Bob' })], elements: [] }) });
        let v = view();
        expect(textOf(one(v, 'pb-cmp-actors'))).toContain('Actors (2)');
        one(v, 'pb-cmp-actor-a2');
        expect(textOf(one(v, 'pb-cmp-elements'))).toContain('No elements on this composition.');
        expect(one(v, 'pb-cmp-focal').children.map(o => o.attrs.value)).toEqual(['', 'a1', 'a2']);
    });
});

describe('canvas', () => {
    it('draws one box per actor and element at the row fractions, aspect from the plan canvas', () => {
        seedBoard();
        let v = view();
        let canvas = one(v, 'pb-cmp-canvas');
        expect(canvas.attrs.style.aspectRatio).toBe(String(1024 / 768));
        expect(textOf(one(v, 'pb-cmp-canvas-section'))).toContain('1024×768');
        let a = one(v, 'pb-cmp-box-actor-a1');
        expect(a.attrs['data-kind']).toBe('actor');
        expect(a.attrs.style).toEqual({ position: 'absolute', left: '10.00%', top: '20.00%', width: '30.00%', height: '50.00%', zIndex: '999' });
        expect(textOf(a)).toBe('Alice');
        expect(a.attrs.className).not.toContain('ring-amber');
        let e = one(v, 'pb-cmp-box-element-e1');
        expect(e.attrs['data-kind']).toBe('element');
        expect(e.attrs.style.left).toBe('60.00%');
        expect(textOf(e)).toBe('a brass lantern');
    });
    it('falls back to 4:3 without a plan', () => {
        seedBoard({ plan: null });
        let v = view();
        expect(one(v, 'pb-cmp-canvas').attrs.style.aspectRatio).toBe(String(4 / 3));
        expect(textOf(one(v, 'pb-cmp-canvas-section'))).toContain('(4:3)');
    });
    it('dragging moves the row draft (not the persisted row) and marks the box moved until Save', () => {
        seedBoard();
        let box = one(view(), 'pb-cmp-box-actor-a1');
        let parent = { getBoundingClientRect: () => ({ width: 400, height: 300 }) };
        box.attrs.onpointerdown({ currentTarget: { parentNode: parent, setPointerCapture: () => { throw new Error('jsdom'); } }, pointerId: 1, clientX: 100, clientY: 100, preventDefault: () => {} });
        expect(st().drag.objectId).toBe('a1');
        box.attrs.onpointermove({ clientX: 140, clientY: 70 });
        box.attrs.onpointerup();
        expect(st().drag).toBeNull();
        expect(st().edits.actors.a1.x).toBe(0.2);
        expect(st().edits.actors.a1.y).toBe(0.1);
        expect(st().board.actors[0].x).toBe(0.1);
        let v = view();
        let moved = one(v, 'pb-cmp-box-actor-a1');
        expect(moved.attrs.className).toContain('ring-amber');
        expect(moved.attrs.style.left).toBe('20.00%');
        expect(one(v, 'pb-cmp-actor-x').attrs.value).toBe(0.2);
    });
    it('a pointermove for another box or without a drag is ignored', () => {
        seedBoard();
        let box = one(view(), 'pb-cmp-box-element-e1');
        // rendering a row seeds its draft as a copy of the server row; a stray move must leave it equal
        expect(st().edits.elements.e1).toEqual(st().board.elements[0]);
        box.attrs.onpointermove({ clientX: 999, clientY: 999 });
        expect(st().edits.elements.e1.x).toBe(0.6);
        cmp.__setComposeStateForTest({ drag: { kind: 'actor', objectId: 'a1', startX: 0, startY: 0, orig: { x: 0.1, y: 0.2, w: 0.3, h: 0.5 }, rect: { width: 100, height: 100 } } });
        box.attrs.onpointermove({ clientX: 50, clientY: 50 });
        expect(st().edits.elements.e1.x).toBe(0.6);
        expect(st().edits.elements.e1.y).toBe(0.7);
        expect(one(view(), 'pb-cmp-box-element-e1').attrs.className).not.toContain('ring-amber');
    });
});

describe('plan side', () => {
    it('shows the headline compiled prompt beside the composition and hands the full plan to PbPlanInspector', () => {
        seedBoard();
        let v = view();
        let side = one(v, 'pb-cmp-plan-side');
        let pre = one(side, 'pb-cmp-plan-prompt');
        expect(textOf(pre)).toBe('a quay at dusk, Alice waits');
        expect(pre.attrs['data-stage']).toBe('base');
        expect(textOf(side)).toContain('Prompt · base');
        expect(textOf(one(side, 'pb-cmp-plan-negative'))).toBe('blurry');
        let insp = findAll(side, x => x.tag === PbPlanInspector);
        expect(insp.length).toBe(1);
        expect(insp[0].attrs.plan).toBe(st().plan);
        none(side, 'pb-cmp-plan-error');
        none(v, 'pb-cmp-degradations');
    });
    it('lists the plan degradations and the PROMPT_TRUNCATED drops verbatim', () => {
        seedBoard({ plan: wf.normalizePlan(planWire({ degradations: [
            { code: 'PROMPT_TRUNCATED', message: 'actors dropped: Bob, Carol (12 tokens over budget 150)' },
            { code: 'model_fallback', message: 'x' },
            { code: 'PROMPT_TRUNCATED', message: 'elements dropped: rope' }
        ] })) });
        let v = view();
        let d = one(v, 'pb-cmp-degradations');
        expect(d.attrs['data-count']).toBe('3');
        expect(findAll(d, x => x.attrs['data-code']).map(x => x.attrs['data-code'])).toEqual(['PROMPT_TRUNCATED', 'MODEL_FALLBACK']);
        expect(one(d, 'pb-cmp-truncated').children.map(textOf)).toEqual(['actors dropped: Bob, Carol (12 tokens over budget 150)', 'elements dropped: rope']);
    });
    it('shows the plan error verbatim and no prompt when the plan failed to compile', () => {
        seedBoard({ plan: null });
        cmp.__setComposeStateForTest({ planError: '(409) No style resolved for this book' });
        let side = one(view(), 'pb-cmp-plan-side');
        expect(textOf(one(side, 'pb-cmp-plan-error'))).toBe('(409) No style resolved for this book');
        none(side, 'pb-cmp-plan-prompt');
        expect(findAll(side, x => x.tag === PbPlanInspector).length).toBe(0);
    });
    it('Recompile posts the plan again and nothing else', async () => {
        seedBoard();
        let calls = wire();
        one(view(), 'pb-cmp-plan-refresh').attrs.onclick();
        expect(st().planLoading).toBe(true);
        expect(textOf(one(view(), 'pb-cmp-plan-refresh'))).toBe('Compiling…');
        await waitFor(() => !st().planLoading);
        expect(seqOf(calls)).toEqual(['POST ' + PB + '/scene/s1/plan']);
    });
    it('the config override chip reflects a stored sparse diff', () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ configOverride: '{"steps":20,"schema":"olio.sd.config"}' }) }) });
        let v = view();
        expect(textOf(one(v, 'pb-cmp-config-set'))).toBe('set');
        one(v, 'pb-cmp-config-clear');
        none(v, 'pb-cmp-config-save');
        seedBoard();
        v = view();
        none(v, 'pb-cmp-config-set');
        none(v, 'pb-cmp-config-clear');
    });
});

// ─────────────────────────────── row-level PATCH writes ───────────────────────────────

describe('camera / prompt save', () => {
    it('Save sends one PATCH of the changed composition keys only, then re-reads the board', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'PATCH' && url === PB + '/scene/s1/composition' ? jsonResponse(200, editResultWire({ revision: 4 })) : null });
        let v = view();
        one(v, 'pb-cmp-shot').attrs.onchange({ target: { value: 'wide' } });
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'calm' } });
        one(v, 'pb-cmp-setting').attrs.oninput({ target: { value: 'a quay at dusk' } });
        one(v, 'pb-cmp-focal').attrs.onchange({ target: { value: '' } });
        one(v, 'pb-cmp-prompt-override').attrs.oninput({ target: { value: 'ignored by camera save' } });
        one(v, 'pb-cmp-save-camera').attrs.onclick();
        expect(st().busy.camera).toBe(true);
        expect(textOf(one(view(), 'pb-cmp-save-camera'))).toBe('Saving…');
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('PATCH ' + PB + '/scene/s1/composition');
        expect(calls[0].body).toEqual({ shotType: 'wide', mood: 'calm', focalActorObjectId: '' });
        expect(reads(calls)).toEqual(['GET ' + PB + '/scene/s1/composition', 'GET ' + PB + '/composition/comp-1/outfits', 'POST ' + PB + '/scene/s1/plan']);
        expect(toasts()).toEqual([['success', 'Composition saved']]);
        expect(st().lastEdit).toEqual({ revision: 4, nodeStale: true, nodeStatus: 'STALE' });
        expect(st().edits.composition).toBeNull();
        let after = view();
        expect(textOf(one(after, 'pb-cmp-stale'))).toBe('render stale · STALE');
        // the re-read board, not the pre-save draft, is what the fields show now
        expect(one(after, 'pb-cmp-mood').attrs.value).toBe('tense');
        expect(one(after, 'pb-cmp-prompt-override').attrs.value).toBe('');
    });
    it('an unchanged camera is "Nothing changed" and sends nothing', async () => {
        seedBoard();
        let calls = wire();
        let v = view();
        one(v, 'pb-cmp-shot').attrs.onchange({ target: { value: 'MEDIUM' } });
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'tense' } });
        one(v, 'pb-cmp-save-camera').attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(calls.length).toBe(0);
        expect(toasts()).toEqual([['info', 'Nothing changed']]);
        expect(st().busy).toEqual({});
    });
    it('Prompt Save sends only promptOverride/promptLocked', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'PATCH' && url === PB + '/scene/s1/composition' ? jsonResponse(200, editResultWire()) : null });
        let v = view();
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'calm' } });
        one(v, 'pb-cmp-prompt-override').attrs.oninput({ target: { value: 'a locked prompt' } });
        one(v, 'pb-cmp-prompt-locked').attrs.onchange({ target: { checked: true } });
        one(v, 'pb-cmp-save-prompt').attrs.onclick();
        await settled(calls);
        expect(calls[0].method).toBe('PATCH');
        expect(calls[0].body).toEqual({ promptOverride: 'a locked prompt', promptLocked: true });
        expect(toasts()).toEqual([['success', 'Prompt saved']]);
    });
    it('a 400 from the server is shown verbatim and the board is still re-read', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'PATCH' ? jsonResponse(400, { error: 'shotType must be one of close_up, medium, full_body, wide, establishing' }) : null });
        let v = view();
        one(v, 'pb-cmp-shot').attrs.onchange({ target: { value: 'sideways' } });
        one(v, 'pb-cmp-save-camera').attrs.onclick();
        await settled(calls);
        expect(toasts()).toEqual([['error', '(400) shotType must be one of close_up, medium, full_body, wide, establishing']]);
        expect(reads(calls).length).toBe(3);
        expect(st().lastEdit).toBeNull();
    });
    it('Clear config override PATCHes an empty configOverride', async () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ configOverride: '{"steps":20}' }) }) });
        let calls = wire({ route: (method, url) => method === 'PATCH' ? jsonResponse(200, editResultWire()) : null });
        one(view(), 'pb-cmp-config-clear').attrs.onclick();
        await settled(calls);
        expect(calls[0].body).toEqual({ configOverride: '' });
        expect(toasts()).toEqual([['success', 'Image config override cleared']]);
    });
});

describe('actor rows', () => {
    it('Save sends one PATCH /composition/{comp}/actor/{actor} with that row\'s delta and swaps in the returned row', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'PATCH' && url === PB + '/composition/comp-1/actor/a1'
            ? jsonResponse(200, editResultWire({ actorObjectId: 'a1', actor: actorWire({ pose: 'crouching', x: 0.25, role: 'supporting' }) })) : null });
        let row = one(view(), 'pb-cmp-actor-a1');
        one(row, 'pb-cmp-actor-pose').attrs.oninput({ target: { value: 'crouching' } });
        one(row, 'pb-cmp-actor-x').attrs.oninput({ target: { value: '0.25' } });
        one(row, 'pb-cmp-actor-role').attrs.onchange({ target: { value: 'supporting' } });
        one(row, 'pb-cmp-actor-priority').attrs.oninput({ target: { value: '' } });
        one(row, 'pb-cmp-actor-facing').attrs.onchange({ target: { value: '' } }); // (unset) on a row that had 'left' → 'unknown'
        one(row, 'pb-cmp-actor-save').attrs.onclick();
        expect(st().busy['actor:a1']).toBe(true);
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('PATCH ' + PB + '/composition/comp-1/actor/a1');
        expect(calls[0].body).toEqual({ role: 'supporting', pose: 'crouching', x: 0.25, facing: 'unknown' });
        expect(toasts()).toEqual([['success', 'Actor saved']]);
        expect(reads(calls).length).toBe(3);
    });
    it('an unchanged actor row is "Nothing changed"', async () => {
        seedBoard();
        let calls = wire();
        let row = one(view(), 'pb-cmp-actor-a1');
        one(row, 'pb-cmp-actor-expression').attrs.oninput({ target: { value: 'wary' } });
        one(row, 'pb-cmp-actor-save').attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(calls.length).toBe(0);
        expect(toasts()).toEqual([['info', 'Nothing changed']]);
    });
    it('Delete asks first, then DELETEs the row and reports a cleared focal actor', async () => {
        seedBoard();
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let calls = wire({ route: (method, url) => method === 'DELETE' && url === PB + '/composition/comp-1/actor/a1' ? jsonResponse(200, editResultWire({ actorObjectId: 'a1', deleted: true, focalActorCleared: true })) : null });
        one(one(view(), 'pb-cmp-actor-a1'), 'pb-cmp-actor-delete').attrs.onclick();
        await settled(calls);
        expect(Dialog.confirm).toHaveBeenCalledWith({ title: 'Remove actor', message: 'Remove Alice from the composition?', confirmLabel: 'Remove', destructive: true });
        expect(seqOf(calls)[0]).toBe('DELETE ' + PB + '/composition/comp-1/actor/a1');
        expect(calls[0].rawBody).toBeUndefined();
        expect(toasts()).toEqual([['success', 'Actor removed; the focal actor was cleared']]);
    });
    it('a declined confirm sends nothing', async () => {
        seedBoard();
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        let calls = wire();
        one(one(view(), 'pb-cmp-actor-a1'), 'pb-cmp-actor-delete').attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(calls.length).toBe(0);
        expect(toasts()).toEqual([]);
    });
    it('Add actor posts the character (or description) and role from the dialog', async () => {
        seedBoard({ characters: [{ objectId: 'c2', name: 'Bob' }] });
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/composition/comp-1/actor' ? jsonResponse(201, editResultWire({ actorObjectId: 'a2', created: true, actor: actorWire({ objectId: 'a2', characterObjectId: 'c2', characterName: 'Bob' }) })) : null });
        one(view(), 'pb-cmp-actor-add').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        await waitFor(() => container.querySelector('[data-testid=pb-cmp-actor-add-dialog]'));
        let sel = container.querySelector('[data-testid=pb-cmp-add-actor-character]');
        expect(Array.from(sel.options).map(o => o.value)).toEqual(['', 'c2']);
        sel.value = 'c2';
        sel.dispatchEvent(new Event('change'));
        let role = container.querySelector('[data-testid=pb-cmp-add-actor-role]');
        role.value = 'extra';
        role.dispatchEvent(new Event('change'));
        container.querySelector('[data-testid=pb-cmp-add-actor-submit]').click();
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('POST ' + PB + '/composition/comp-1/actor');
        expect(calls[0].body).toEqual({ characterObjectId: 'c2', role: 'extra' });
        expect(toasts()).toEqual([['success', 'Actor added']]);
        m.render(container, null);
    });
    it('Add actor refuses an empty form without a request', async () => {
        seedBoard({ characters: [{ objectId: 'c2', name: 'Bob' }] });
        let calls = wire();
        one(view(), 'pb-cmp-actor-add').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        await waitFor(() => container.querySelector('[data-testid=pb-cmp-add-actor-submit]'));
        container.querySelector('[data-testid=pb-cmp-add-actor-submit]').click();
        m.render(container, Dialog.loadDialogs());
        expect(container.textContent).toContain('Pick a character or describe the actor');
        expect(calls.length).toBe(0);
        m.render(container, null);
    });
});

describe('outfit picker', () => {
    it('lists the wardrobe, marks the pinned entry and PUTs the chosen apparel', async () => {
        seedBoard({ board: boardWire({ actors: [actorWire({ apparelObjectId: 'ap1' })] }) });
        let calls = wire({ route: (method, url) => {
            if (method === 'GET' && url === PB + '/character/c1/wardrobe') return jsonResponse(200, { entries: [{ objectId: 'ap1', name: 'Rain cloak', isDefault: true }, { objectId: 'ap2', name: 'Market dress', sceneIndex: 2, description: 'a red dress' }], warnings: [] });
            if (method === 'PUT' && url === PB + '/composition/comp-1/actor/a1/apparel') return jsonResponse(200, { actorObjectId: 'a1', apparelObjectId: 'ap2', pinned: true, staled: 2, affectedSceneIndexes: [1, 3] });
            return null;
        } });
        one(one(view(), 'pb-cmp-actor-a1'), 'pb-cmp-actor-outfit-pick').attrs.onclick();
        let container = document.createElement('div');
        await waitFor(() => calls.some(c => c.url === PB + '/character/c1/wardrobe'));
        await waitFor(() => { m.render(container, Dialog.loadDialogs()); return container.querySelector('[data-testid=pb-cmp-outfit-entry-ap2]'); });
        expect(container.querySelector('[data-testid=pb-cmp-outfit-current]').textContent).toBe('Rain cloak (from scene 2)');
        expect(container.querySelector('[data-testid=pb-cmp-outfit-entry-ap1]').textContent).toContain('pinned');
        expect(container.querySelector('[data-testid=pb-cmp-outfit-pin-ap1]')).toBeNull();
        expect(container.querySelector('[data-testid=pb-cmp-outfit-entry-ap2]').textContent).toContain('scene 2');
        container.querySelector('[data-testid=pb-cmp-outfit-pin-ap2]').click();
        await settled(calls);
        let put = calls.find(c => c.method === 'PUT');
        expect(put.url).toBe(PB + '/composition/comp-1/actor/a1/apparel');
        expect(put.body).toEqual({ apparelObjectId: 'ap2' });
        expect(toasts()).toEqual([['success', 'Outfit pinned; scenes 1, 3 marked stale']]);
        m.render(container, null);
    });
    it('Unpin PUTs an empty body', async () => {
        seedBoard({ board: boardWire({ actors: [actorWire({ apparelObjectId: 'ap1' })] }) });
        let calls = wire({ route: (method, url) => {
            if (method === 'GET' && url === PB + '/character/c1/wardrobe') return jsonResponse(200, { entries: [] });
            if (method === 'PUT') return jsonResponse(200, { actorObjectId: 'a1', pinned: false, staled: 0, affectedSceneIndexes: [] });
            return null;
        } });
        one(one(view(), 'pb-cmp-actor-a1'), 'pb-cmp-actor-outfit-pick').attrs.onclick();
        let container = document.createElement('div');
        await waitFor(() => calls.some(c => c.url === PB + '/character/c1/wardrobe'));
        await waitFor(() => { m.render(container, Dialog.loadDialogs()); return container.textContent.indexOf('This character has no wardrobe entries yet') >= 0; });
        expect(container.querySelector('[data-testid=pb-cmp-outfit-unpin]')).not.toBeNull();
        container.querySelector('[data-testid=pb-cmp-outfit-unpin]').click();
        await settled(calls);
        expect(calls.find(c => c.method === 'PUT').body).toEqual({});
        expect(toasts()).toEqual([['success', 'Outfit unpinned']]);
        m.render(container, null);
    });
    it('an unnamed actor has no wardrobe', () => {
        seedBoard({ board: boardWire({ actors: [actorWire({ objectId: 'a9', characterObjectId: null, characterName: null, description: 'fisherman' })] }), outfits: {} });
        let calls = wire();
        one(one(view(), 'pb-cmp-actor-a9'), 'pb-cmp-actor-outfit-pick').attrs.onclick();
        expect(calls.length).toBe(0);
        expect(toasts()).toEqual([['info', 'Unnamed actors have no wardrobe']]);
        expect(Dialog.loadDialogs()).toBeFalsy();
    });
});

describe('element rows', () => {
    it('Save sends one PATCH /composition/{comp}/element/{el} with that row\'s delta', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'PATCH' && url === PB + '/composition/comp-1/element/e1' ? jsonResponse(200, editResultWire({ elementObjectId: 'e1', element: elementWire({ importance: 'high', description: '' }) })) : null });
        let el = one(view(), 'pb-cmp-element-e1');
        one(el, 'pb-cmp-element-importance').attrs.onchange({ target: { value: 'high' } });
        one(el, 'pb-cmp-element-description').attrs.oninput({ target: { value: '' } });
        one(el, 'pb-cmp-element-depth').attrs.oninput({ target: { value: '0.2' } }); // rounds to the stored 0: not sent
        one(el, 'pb-cmp-element-save').attrs.onclick();
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('PATCH ' + PB + '/composition/comp-1/element/e1');
        expect(calls[0].body).toEqual({ description: '', importance: 'high' });
        expect(toasts()).toEqual([['success', 'Element saved']]);
    });
    it('Delete asks with the description, then DELETEs', async () => {
        seedBoard();
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let calls = wire({ route: (method, url) => method === 'DELETE' ? jsonResponse(200, editResultWire({ elementObjectId: 'e1', deleted: true })) : null });
        one(one(view(), 'pb-cmp-element-e1'), 'pb-cmp-element-delete').attrs.onclick();
        await settled(calls);
        expect(Dialog.confirm).toHaveBeenCalledWith({ title: 'Remove element', message: 'Remove "a brass lantern" from the composition?', confirmLabel: 'Remove', destructive: true });
        expect(seqOf(calls)[0]).toBe('DELETE ' + PB + '/composition/comp-1/element/e1');
        expect(toasts()).toEqual([['success', 'Element removed']]);
    });
    it('Promote POSTs .../promote with no body and names the created or linked item', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/composition/comp-1/element/e1/promote' ? jsonResponse(200, { elementObjectId: 'e1', promoted: true, created: true, itemObjectId: 'it-1', itemName: 'brass lantern', staled: 1, warnings: ['scene 1 render marked stale'] }) : null });
        one(one(view(), 'pb-cmp-element-e1'), 'pb-cmp-element-promote').attrs.onclick();
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('POST ' + PB + '/composition/comp-1/element/e1/promote');
        expect(calls[0].rawBody).toBeUndefined();
        expect(toasts()).toEqual([['success', 'Created item brass lantern — scene 1 render marked stale']]);
    });
    it('Promote reports a reuse and a refusal', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'POST' && /\/promote$/.test(url) ? jsonResponse(200, { elementObjectId: 'e1', promoted: true, reused: true, itemObjectId: 'it-7', itemName: 'lantern' }) : null });
        one(one(view(), 'pb-cmp-element-e1'), 'pb-cmp-element-promote').attrs.onclick();
        await settled(calls);
        expect(toasts()).toEqual([['success', 'Linked existing item lantern']]);
        page.toast.mockClear();
        seedBoard();
        calls = wire({ route: (method, url) => method === 'POST' && /\/promote$/.test(url) ? jsonResponse(200, { elementObjectId: 'e1', promoted: false, warnings: ['the scene has no location'] }) : null });
        one(one(view(), 'pb-cmp-element-e1'), 'pb-cmp-element-promote').attrs.onclick();
        await settled(calls);
        expect(toasts()).toEqual([['success', 'Element was not promoted — the scene has no location']]);
    });
    it('Add element posts type, description and importance from the dialog', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/composition/comp-1/element' ? jsonResponse(201, editResultWire({ elementObjectId: 'e2', created: true })) : null });
        one(view(), 'pb-cmp-element-add').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        let type = container.querySelector('[data-testid=pb-cmp-add-element-type]');
        type.value = 'furniture';
        type.dispatchEvent(new Event('change'));
        let desc = container.querySelector('[data-testid=pb-cmp-add-element-description]');
        desc.value = '  an oak table  ';
        desc.dispatchEvent(new Event('input'));
        let imp = container.querySelector('[data-testid=pb-cmp-add-element-importance]');
        imp.value = 'high';
        imp.dispatchEvent(new Event('change'));
        container.querySelector('[data-testid=pb-cmp-add-element-submit]').click();
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('POST ' + PB + '/composition/comp-1/element');
        expect(calls[0].body).toEqual({ elementType: 'furniture', description: 'an oak table', importance: 'high' });
        expect(toasts()).toEqual([['success', 'Element added']]);
        m.render(container, null);
    });
});

// ─────────────────────────────── compose ───────────────────────────────

describe('re-compose', () => {
    it('forces a single-scene compose and reports the result counts', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/scene/s1/compose' ? jsonResponse(200, { sceneObjectId: 's1', sceneIndex: 1, written: true, source: 'llm', actors: [{}, {}], elements: [{}], warnings: ['unresolved: Carol'] }) : null });
        one(view(), 'pb-cmp-recompose').attrs.onclick();
        expect(st().busy.recompose).toBe(true);
        expect(textOf(one(view(), 'pb-cmp-recompose'))).toContain('Re-composing…');
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('POST ' + PB + '/scene/s1/compose');
        expect(calls[0].body).toEqual({ force: true });
        expect(toasts()).toEqual([['success', 'Scene re-composed (llm, 2 actors, 1 elements) — unresolved: Carol']]);
        expect(reads(calls).length).toBe(3);
    });
    it('a hand-edited composition is confirm-gated with the userEdited warning', async () => {
        seedBoard({ board: boardWire({ composition: compositionWire({ userEdited: true }) }) });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        let calls = wire();
        one(view(), 'pb-cmp-recompose').attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(Dialog.confirm).toHaveBeenCalledWith({ title: 'Re-compose this scene', message: cmp.USER_EDITED_WARNING, confirmLabel: 'Re-compose', destructive: true });
        expect(calls.length).toBe(0);
        Dialog.confirm.mockResolvedValue(true);
        calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/scene/s1/compose' ? jsonResponse(200, { written: false, failureReason: 'LLM returned no actors' }) : null });
        one(view(), 'pb-cmp-recompose').attrs.onclick();
        await settled(calls);
        expect(calls[0].body).toEqual({ force: true });
        expect(toasts()).toEqual([['success', 'Scene was not re-composed: LLM returned no actors']]);
    });
    it('the empty state composes the scene without force', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: 's1', pages: PAGES, loading: false, board: null, noComposition: true, noCompositionMessage: 'This scene has no composition yet', busy: {}, edits: { composition: null, actors: {}, elements: {} } });
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/scene/s1/compose' ? jsonResponse(200, { written: true, source: 'llm', actors: [], elements: [] }) : null });
        one(view(), 'pb-cmp-compose-scene').attrs.onclick();
        expect(textOf(one(view(), 'pb-cmp-compose-scene'))).toContain('Composing…');
        await settled(calls);
        expect(calls[0].body).toEqual({});
        expect(toasts()).toEqual([['success', 'Scene composed']]);
        expect(st().noComposition).toBe(false);
        expect(st().board.compositionObjectId).toBe('comp-1');
    });
    it('a 409 while the book compose job runs is a warning, not an error', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/scene/s1/compose' ? jsonResponse(409, { error: 'A compose job is running for this book; wait for it to finish' }) : null });
        one(view(), 'pb-cmp-recompose').attrs.onclick();
        await settled(calls);
        expect(toasts()).toEqual([['error', '(409) A compose job is running for this book; wait for it to finish']]);
    });
});

describe('compose book dialog', () => {
    function openDialog() {
        one(view(), 'pb-cmp-compose-book').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=pb-cmp-compose-dialog]')).not.toBeNull();
        return container;
    }
    it('Start submits POST /compose with only the checked options and follows the job to its terminal counter', async () => {
        seedBoard();
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(202, { jobId: 'j1', jobStatus: 'QUEUED', submitted: true, reused: false, kind: 'pb.compose', quickStart: true, force: false, sceneCount: 3, scenesToCompose: 2, scenesCurrent: 1, pagesSkipped: 0 }) : null,
            job: (id, nth) => nth < 3
                ? jsonResponse(200, { jobId: id, terminal: false, current: nth, total: 2, phase: 'scene ' + nth })
                : jsonResponse(200, { jobId: id, terminal: true, current: 2, total: 2, result: { composed: 2, current: 1, quickStartRendered: 2, scenes: [
                    { sceneObjectId: 's2', sceneIndex: 2, title: 'Market', status: 'composed', actorCount: 2, elementCount: 1, warnings: [], draftRender: {} },
                    { sceneObjectId: 's3', sceneIndex: 3, status: 'composed', actorCount: 1, elementCount: 0, warnings: ['unresolved: Dan'], draftRenderError: 'no SD backend' }
                ] } })
        });
        let container = openDialog();
        expect(container.querySelector('[data-testid=pb-cmp-force-warning]')).toBeNull();
        let qs = container.querySelector('[data-testid=pb-cmp-quickstart]');
        qs.checked = true;
        qs.dispatchEvent(new Event('change'));
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        expect(Dialog.loadDialogs()).toBeFalsy();
        await waitFor(() => calls.some(c => c.url === PB + '/compose'));
        expect(calls[0].body).toEqual({ quickStart: true });
        await waitFor(() => st().composeRun && st().composeRun.job && st().composeRun.job.terminal === false);
        let v = view();
        let run = one(v, 'pb-cmp-compose-run');
        expect(run.attrs['data-run-terminal']).toBe('false');
        expect(textOf(run)).toContain('Compose + quick start');
        expect(textOf(one(v, 'pb-cmp-compose-counter'))).toMatch(/^[12] of 2 · scene [12]$/);
        expect(one(v, 'pb-cmp-compose-book').attrs.disabled).toBe(true);
        await waitFor(() => st().composeRun && st().composeRun.result);
        await settled(calls);
        expect(toasts()).toEqual([['info', 'Compose job started (2 of 3 scenes)'], ['success', 'Done: composed 2, current 1, drafts rendered 2']]);
        v = view();
        run = one(v, 'pb-cmp-compose-run');
        expect(run.attrs['data-run-terminal']).toBe('true');
        expect(textOf(one(v, 'pb-cmp-compose-counter'))).toBe('Done: composed 2, current 1, drafts rendered 2');
        let s2 = one(v, 'pb-cmp-compose-scene-2');
        expect(s2.attrs['data-status']).toBe('composed');
        expect(textOf(s2)).toContain('Scene 2 · Market');
        expect(textOf(s2)).toContain('2 actors, 1 elements');
        let s3 = one(v, 'pb-cmp-compose-scene-3');
        expect(textOf(s3)).toContain('draft: no SD backend');
        expect(textOf(s3)).toContain('unresolved: Dan');
        expect(one(v, 'pb-cmp-compose-book').attrs.disabled).toBe(false);
        expect(reads(calls).length).toBe(3);
        expect(calls.filter(c => /^\/rest\/job\//.test(c.url)).length).toBe(3);
        one(v, 'pb-cmp-compose-dismiss').attrs.onclick();
        none(view(), 'pb-cmp-compose-run');
        m.render(container, null);
    });
    it('Re-compose in order shows the overwrite warning and sends force:true', async () => {
        seedBoard();
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(202, { jobId: 'j2', submitted: true, reused: true, force: true, sceneCount: 3, scenesToCompose: 3 }) : null,
            job: (id) => jsonResponse(200, { jobId: id, terminal: true, error: 'LLM unavailable', result: { composed: 0, hardFailures: 3 } })
        });
        let container = openDialog();
        let force = container.querySelector('[data-testid=pb-cmp-force]');
        force.checked = true;
        force.dispatchEvent(new Event('change'));
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=pb-cmp-force-warning]').textContent).toBe(cmp.USER_EDITED_WARNING);
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        await waitFor(() => st().composeRun && st().composeRun.job && st().composeRun.job.terminal);
        await settled(calls);
        expect(calls[0].body).toEqual({ force: true });
        expect(toasts()).toEqual([['info', 'Joined the running compose job (3 of 3 scenes)'], ['warn', 'Failed: LLM unavailable']]);
        let run = one(view(), 'pb-cmp-compose-run');
        expect(textOf(run)).toContain('Compose (re-compose in order)');
        expect(textOf(one(run, 'pb-cmp-compose-counter'))).toBe('Failed: LLM unavailable');
        m.render(container, null);
    });
    it('a 200 with submitted:false is an info toast with the server reason and no job', async () => {
        seedBoard();
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(200, { submitted: false, reason: 'Every scene already has a current composition', sceneCount: 3, scenesCurrent: 3 }) : null });
        let container = openDialog();
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        await waitFor(() => toasts().length >= 1);
        await new Promise(r => setTimeout(r, 10));
        expect(calls[0].body).toEqual({});
        expect(toasts()).toEqual([['info', 'Every scene already has a current composition']]);
        expect(st().composeRun).toBeNull();
        expect(calls.length).toBe(1);
        m.render(container, null);
    });
    it('a 409 refusal is a warning and any other failure an error', async () => {
        seedBoard();
        wire({ route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(409, { error: 'A compose job is already running for this book' }) : null });
        let container = openDialog();
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        await waitFor(() => toasts().length >= 1);
        expect(toasts()).toEqual([['warn', '(409) A compose job is already running for this book']]);
        page.toast.mockClear();
        wire({ route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(409, { error: 'Book is not on the V3 render path' }) : null });
        container = openDialog();
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        await waitFor(() => toasts().length >= 1);
        expect(toasts()).toEqual([['error', '(409) Book is not on the V3 render path']]);
        m.render(container, null);
    });
    it('Dismiss while running stops following but never cancels the job', async () => {
        seedBoard();
        let polls = 0;
        wire({
            route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(202, { jobId: 'j3', submitted: true, sceneCount: 1, scenesToCompose: 1 }) : null,
            job: (id) => { polls++; return jsonResponse(200, { jobId: id, terminal: false, current: 0, total: 1 }); }
        });
        let container = openDialog();
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        await waitFor(() => polls >= 2);
        one(view(), 'pb-cmp-compose-dismiss').attrs.onclick();
        expect(st().composeRun).toBeNull();
        expect(st().composeAbort).toBeNull();
        let seen = polls;
        await new Promise(r => setTimeout(r, 15));
        expect(polls).toBeLessThanOrEqual(seen + 1);
        expect(global.fetch.mock.calls.some(c => /\/cancel/.test(String(c[0])) || (c[1] && c[1].method === 'DELETE'))).toBe(false);
        expect(toasts()).toEqual([['info', 'Compose job started (1 of 1 scenes)']]);
        m.render(container, null);
    });
});
