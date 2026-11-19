// @vitest-environment jsdom
/**
 * PictureBook composition board inside the Edit workspace (aiDocs/PictureBookUxConsistencyDesign.md §5.6, §5.7,
 * §4.5, §4.7, §6.2) — features/pictureBookCompose.js `composeApi` (Edit › Scenes › Composition tab) and
 * `coverSection` (Edit › Cover & Art SectionAdapter).
 *
 * Why: the board body moves into the Scenes section without its page header, scene stepper or Covers / Scenes
 * cross-links (the scene list and the rail replace them); drafts are kept per scene instead of being dropped on a
 * scene change (§6.2-4); the guard's Save sends each dirty section's existing delta PATCH and reports a failed write
 * as false (model-api.md: never discard an update result); job panels go to the book's activity store, never the
 * page flow (§4.5); an actor's character links to Edit › Characters (§4.7).
 *
 * No network: fetch, am7client.clearCache / search, page.toast, m.route.set and LLMConnector.resolveConfig are
 * stubbed. Real Mithril renders the composition into jsdom where the DOM matters.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import { anyDirty, dirtyLabels, guardDirty, __resetDirtyForTest } from '../components/dirtyGuard.js';
import { getActivity, activitiesFor, __resetActivityForTest } from '../workflows/bookActivity.js';

const BOOK = 'book-1';
const PB = '/rest/olio/picture-book/' + BOOK;
const PAGES = [{ objectId: 's1', sceneIndex: 1, title: 'Quay' }, { objectId: 's2', sceneIndex: 2, title: 'Market' }];

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
function toasts() { return page.toast.mock.calls.map(c => [c[0], c[1]]); }

function compositionWire(sid, extra) {
    return Object.assign({
        objectId: 'comp-' + sid, name: 'scene-' + sid + '-composition', source: 'llm', purpose: 'scene', shotType: 'medium', viewpoint: 'eye_level',
        cameraText: 'low angle', mood: sid === 's1' ? 'tense' : 'busy', settingText: sid === 's1' ? 'a quay at dusk' : 'a market at noon', action: 'Alice waits',
        props: 'lantern, rope', locationObjectId: 'loc-1', focalActorObjectId: 'a1', userEdited: false, promptLocked: false, revision: 3
    }, extra || {});
}
function actorWire(extra) {
    return Object.assign({
        objectId: 'a1', characterObjectId: 'c1', characterName: 'Alice', role: 'hero', priority: 1, pose: 'standing', expression: 'wary',
        action: 'waits', facing: 'left', referencePolicy: 'auto', x: 0.1, y: 0.2, w: 0.3, h: 0.5, depth: 1, count: 1
    }, extra || {});
}
function boardWire(sid) {
    return { bookObjectId: BOOK, sceneObjectId: sid, compositionObjectId: 'comp-' + sid, composition: compositionWire(sid), actors: [actorWire()], elements: [] };
}
function planWire() {
    return { canvas: { width: 1024, height: 768 }, compiled: { base: { prompt: 'a quay at dusk', negative: 'blurry', estimatedTokens: 10 } }, degradations: [], strategy: { stages: [] } };
}

let origFetch;
let cmp;

function st() { return cmp.__composeStateForTest(); }

/** fetch router: pages, both scenes' boards / outfits / plans; o.route(method, url, body) answers first. */
function wire(o) {
    o = o || {};
    let calls = [];
    vi.spyOn(am7client, 'search').mockResolvedValue({ results: [] });
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u).replace(/^.*\/rest\//, '/rest/');
        let method = (init && init.method) || 'GET';
        let body = init && typeof init.body === 'string' ? JSON.parse(init.body) : null;
        calls.push({ url, method, body });
        if (o.route) {
            let r = o.route(method, url, body, calls);
            if (r) return r;
        }
        let mm;
        if (method === 'GET' && url === PB + '/pages') return jsonResponse(200, PAGES);
        if (method === 'GET' && (mm = /^\/rest\/olio\/picture-book\/book-1\/scene\/(s\d)\/composition$/.exec(url))) return jsonResponse(200, boardWire(mm[1]));
        if (method === 'GET' && (mm = /\/composition\/(comp-s\d)\/outfits$/.exec(url))) return jsonResponse(200, { compositionObjectId: mm[1], actors: [] });
        if (method === 'POST' && /\/scene\/s\d\/plan$/.test(url)) return jsonResponse(200, planWire());
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}

async function loaded(sid) {
    await waitFor(() => st().sceneObjectId === sid && st().board && st().board.sceneObjectId === sid && !st().loading && !st().planLoading && Object.keys(st().busy).length === 0);
}

beforeEach(async () => {
    origFetch = global.fetch;
    __resetActivityForTest();
    __resetDirtyForTest();
    cmp = await import('../features/pictureBookCompose.js');
    cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, coverPurpose: null, cover: null, covers: null, coverSeriesObjectId: null, renderRun: null, pages: [], board: null, loading: false, error: null, noComposition: false, noCompositionMessage: null, loadSeq: 0, outfits: {}, plan: null, planError: null, planLoading: false, busy: {}, edits: null, lastEdit: null, characters: [], composeRun: null, drag: null, overrideInst: null, overrideExpanded: false, sdModels: [], sdLoras: [], pollIntervalMs: 1, chatConfigRef: null, chatConfigResolving: false });
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
    vi.spyOn(LLMConnector, 'resolveConfig').mockResolvedValue(null);
});

afterEach(() => {
    cmp.composeView.onremove();
    cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, coverPurpose: null, pages: [], board: null, composeRun: null, renderRun: null, busy: {}, edits: null, lastEdit: null, chatConfigRef: null, chatConfigResolving: false });
    Dialog._reset();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── Composition tab body ───────────────────────────────

describe('composeApi.renderComposition', () => {
    it('is the board body only: no header, back, scene stepper or Covers / Scenes cross-links; the toolbar actions are separate', async () => {
        wire();
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        let v = cmp.composeApi.renderComposition({});
        expect(one(v, 'pb-cmp-composition').attrs['data-scene']).toBe('s1');
        ['pb-cmp-back', 'pb-cmp-title', 'pb-cmp-refresh', 'pb-cmp-scene-prev', 'pb-cmp-scene-select', 'pb-cmp-scene-next',
            'pb-cmp-cover-link', 'pb-cmp-scenes-link', 'pb-cmp-compose-book', 'pb-cmp-chatconfig', 'pb-cmp-compose-run'].forEach(id => none(v, id));
        ['pb-cmp-status', 'pb-cmp-recompose', 'pb-cmp-camera', 'pb-cmp-canvas', 'pb-cmp-actors', 'pb-cmp-elements', 'pb-cmp-prompt', 'pb-cmp-config', 'pb-cmp-plan-side'].forEach(id => one(v, id));
        // real Mithril into jsdom: the same, as DOM
        let root = document.createElement('div');
        m.render(root, v);
        expect(root.querySelector('[data-testid=pb-cmp-camera]')).not.toBeNull();
        expect(root.querySelector('[data-testid=pb-cmp-back]')).toBeNull();
        expect(root.querySelector('[data-testid=pb-cmp-scene-select]')).toBeNull();
        expect(root.querySelector('[data-testid=pb-cmp-cover-link]')).toBeNull();
        m.render(root, null);
        // the section toolbar's actions: chat config + Compose book… (moved from the board header, testids kept)
        let tb = cmp.composeApi.toolbarActions({});
        one(tb, 'pb-cmp-chatconfig');
        expect(textOf(one(tb, 'pb-cmp-compose-book'))).toContain('Compose book…');
    });
    it('init is idempotent per board: called on every render (the host does), it reads pages + the board once — also while loading and after a 404', async () => {
        let calls = wire({ route: (method, url) => method === 'GET' && url === PB + '/scene/s2/composition' ? jsonResponse(404, { error: 'Scene 2 has no composition yet' }) : null });
        let first = cmp.composeApi.init(BOOK, 's1');
        for (let i = 0; i < 5; i++) cmp.composeApi.init(BOOK, 's1');     // redraws while the page list is in flight
        await first;
        for (let i = 0; i < 5; i++) cmp.composeApi.init(BOOK, 's1');     // redraws after the board landed
        await loaded('s1');
        let count = (u, meth) => calls.filter(c => c.url === u && c.method === (meth || 'GET')).length;
        expect(count(PB + '/pages')).toBe(1);
        expect(count(PB + '/scene/s1/composition')).toBe(1);
        await cmp.composeApi.init(BOOK, 's2');
        await waitFor(() => st().noComposition && !st().loading);
        for (let i = 0; i < 5; i++) await cmp.composeApi.init(BOOK, 's2');
        await new Promise(r => setTimeout(r, 10));
        expect(count(PB + '/scene/s2/composition')).toBe(1);
        // an explicit refresh still re-reads
        await cmp.composeApi.refresh();
        expect(count(PB + '/scene/s2/composition')).toBe(2);
    });
    it('keeps the page testids for loading / error / no pages (stateBlock)', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: 's1', pages: PAGES, loading: true, board: null });
        expect(one(cmp.composeApi.renderComposition({}), 'pb-cmp-loading').attrs['data-state']).toBe('loading');
        cmp.__setComposeStateForTest({ loading: false, error: '(403) Not permitted' });
        expect(textOf(one(cmp.composeApi.renderComposition({}), 'pb-cmp-error'))).toBe('(403) Not permitted');
        cmp.__setComposeStateForTest({ error: null, sceneObjectId: null, pages: [] });
        expect(textOf(one(cmp.composeApi.renderComposition({}), 'pb-cmp-no-scenes'))).toBe('This book has no pages to compose.');
    });
});

// ─────────────────────────────── per-scene drafts ───────────────────────────────

describe('drafts are kept per scene (§6.2-4)', () => {
    it('A → B → A: scene A\'s unsaved camera and actor edits come back; B starts clean; nothing is written', async () => {
        let calls = wire();
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        let v = cmp.composeApi.renderComposition({});
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'calm, salt wind' } });
        one(v, 'pb-cmp-actor-pose').attrs.oninput({ target: { value: 'kneeling' } });
        expect(cmp.composeApi.isDirty()).toBe(true);
        expect(anyDirty(['pb-compose'])).toBe(true);

        await cmp.composeApi.init(BOOK, 's2');
        await loaded('s2');
        expect(cmp.composeApi.isDirty()).toBe(false);
        expect(cmp.composeApi.isSceneDirty('s1')).toBe(true);
        expect(cmp.composeApi.isSceneDirty('s2')).toBe(false);
        v = cmp.composeApi.renderComposition({});
        expect(one(v, 'pb-cmp-mood').attrs.value).toBe('busy');
        expect(one(v, 'pb-cmp-actor-pose').attrs.value).toBe('standing');

        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        expect(cmp.composeApi.isDirty()).toBe(true);
        v = cmp.composeApi.renderComposition({});
        expect(one(v, 'pb-cmp-mood').attrs.value).toBe('calm, salt wind');
        expect(one(v, 'pb-cmp-actor-pose').attrs.value).toBe('kneeling');
        // the board itself is the re-read server state
        expect(st().board.composition.mood).toBe('tense');
        expect(calls.filter(c => c.method === 'PATCH').length).toBe(0);
    });
    it('a clean scene stashes nothing, and Discard drops the drafts of the scene on screen', async () => {
        wire();
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        cmp.composeApi.renderComposition({});
        await cmp.composeApi.init(BOOK, 's2');
        await loaded('s2');
        expect(st().drafts).toEqual({});
        one(cmp.composeApi.renderComposition({}), 'pb-cmp-mood').attrs.oninput({ target: { value: 'quiet' } });
        expect(cmp.composeApi.isDirty()).toBe(true);
        cmp.composeApi.discard();
        expect(cmp.composeApi.isDirty()).toBe(false);
        expect(one(cmp.composeApi.renderComposition({}), 'pb-cmp-mood').attrs.value).toBe('busy');
    });
    it('sceneFields reads setting / action / mood / props from the scene\'s composition record (server values, not drafts)', async () => {
        wire();
        expect(cmp.composeApi.sceneFields('s1')).toBeNull();
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        one(cmp.composeApi.renderComposition({}), 'pb-cmp-mood').attrs.oninput({ target: { value: 'draft mood' } });
        expect(cmp.composeApi.sceneFields('s1')).toEqual({ setting: 'a quay at dusk', action: 'Alice waits', mood: 'tense', props: 'lantern, rope' });
        expect(cmp.composeApi.sceneFields('s2')).toBeNull();
        await cmp.composeApi.init(BOOK, 's2');
        await loaded('s2');
        // s1 is stashed dirty: its base board still answers
        expect(cmp.composeApi.sceneFields('s1').setting).toBe('a quay at dusk');
        expect(cmp.composeApi.sceneFields('s2').setting).toBe('a market at noon');
    });
});

// ─────────────────────────────── dirty registration + guard save ───────────────────────────────

describe('dirty registration (pb-compose) and the guard\'s Save', () => {
    it('registers pb-compose labelled with the scene; Save sends each dirty section\'s own delta PATCH, then one re-read', async () => {
        let calls = wire({
            route: (method, url) => {
                if (method === 'PATCH' && url === PB + '/scene/s1/composition') return jsonResponse(200, { compositionObjectId: 'comp-s1', revision: 4, nodeStale: true, nodeStatus: 'STALE', updated: true });
                if (method === 'PATCH' && url === PB + '/composition/comp-s1/actor/a1') return jsonResponse(200, { compositionObjectId: 'comp-s1', revision: 5, updated: true, actor: actorWire({ pose: 'kneeling' }) });
                return null;
            }
        });
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        expect(cmp.composeApi.dirtyKey).toBe('pb-compose');
        expect(anyDirty(['pb-compose'])).toBe(false);
        let v = cmp.composeApi.renderComposition({});
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'calm' } });
        one(v, 'pb-cmp-prompt-override').attrs.oninput({ target: { value: 'a lantern-lit quay' } });
        one(v, 'pb-cmp-actor-pose').attrs.oninput({ target: { value: 'kneeling' } });
        expect(dirtyLabels(['pb-compose'])).toEqual(['Composition — Quay']);
        let before = calls.length;
        let ok = await cmp.composeApi.save();
        expect(ok).toBe(true);
        await loaded('s1');
        let writes = calls.slice(before).filter(c => c.method === 'PATCH');
        expect(writes.map(c => [c.url, c.body])).toEqual([
            [PB + '/scene/s1/composition', { mood: 'calm' }],
            [PB + '/scene/s1/composition', { promptOverride: 'a lantern-lit quay' }],
            [PB + '/composition/comp-s1/actor/a1', { pose: 'kneeling' }]
        ]);
        let rereads = calls.slice(before).filter(c => c.method === 'GET' && c.url === PB + '/scene/s1/composition');
        expect(rereads.length).toBe(1);
        expect(cmp.composeApi.isDirty()).toBe(false);
        expect(toasts()).toEqual([['success', 'Composition saved']]);
    });
    it('a failed PATCH makes Save resolve false: the guard stays, the failed section keeps its draft, the saved one drops it', async () => {
        let calls = wire({
            route: (method, url) => {
                if (method === 'PATCH' && url === PB + '/scene/s1/composition') return jsonResponse(200, { compositionObjectId: 'comp-s1', revision: 4, updated: true });
                if (method === 'PATCH' && url === PB + '/composition/comp-s1/actor/a1') return jsonResponse(500, { error: 'Actor write failed' });
                return null;
            }
        });
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        let v = cmp.composeApi.renderComposition({});
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'calm' } });
        one(v, 'pb-cmp-actor-pose').attrs.oninput({ target: { value: 'kneeling' } });

        // through the real guard: Save is chosen in the dialog, the save fails, `next` never runs
        let next = vi.fn();
        let guarded = guardDirty(next, ['pb-compose']);
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=dirty-guard]').textContent).toContain('Composition — Quay');
        container.querySelector('[data-testid=dirty-guard-save]').click();
        expect(await guarded).toBe(false);
        expect(next).not.toHaveBeenCalled();
        await loaded('s1');

        expect(calls.filter(c => c.method === 'PATCH').map(c => c.url)).toEqual([PB + '/scene/s1/composition', PB + '/composition/comp-s1/actor/a1']);
        expect(toasts()).toEqual([['error', '(500) Actor write failed']]);
        expect(cmp.composeApi.isDirty()).toBe(true);
        expect(st().edits.actors.a1.pose).toBe('kneeling');
        expect(st().edits.composition).toBeNull();
        // the retry sends only the still-dirty actor
        expect(cmp.pendingOps(st().board, st().edits, null, false).map(o => o.kind)).toEqual(['actor']);
        m.render(container, null);
    });
});

// ─────────────────────────────── actor → character link ───────────────────────────────

describe('actor rows link to Edit › Characters (§4.7)', () => {
    it('a bound actor\'s name routes to edit/characters/<characterObjectId>; an unnamed actor has no link', async () => {
        wire({ route: (method, url) => method === 'GET' && url === PB + '/scene/s1/composition'
            ? jsonResponse(200, Object.assign(boardWire('s1'), { actors: [actorWire(), actorWire({ objectId: 'a2', characterObjectId: null, characterName: null, description: 'a passing fisherman' })] })) : null });
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        let v = cmp.composeApi.renderComposition({});
        let links = byTestId(v, 'pb-cmp-actor-character-link');
        expect(links.length).toBe(1);
        expect(links[0].attrs['data-character-objectid']).toBe('c1');
        expect(textOf(links[0])).toBe('Alice');
        await links[0].attrs.onclick({ preventDefault() {} });
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/book-1/edit/characters/c1');
        expect(textOf(one(v, 'pb-cmp-actor-a2'))).toContain('a passing fisherman');
    });
});

// ─────────────────────────────── jobs → bookActivity ───────────────────────────────

describe('job panels go to the book\'s activity store (§4.5)', () => {
    it('compose-book publishes to activity "compose-book" (section scenes), survives a scene switch, and Dismiss clears it', async () => {
        let polls = 0;
        wire({
            route: (method, url) => method === 'POST' && url === PB + '/compose' ? jsonResponse(202, { jobId: 'j1', submitted: true, sceneCount: 2, scenesToCompose: 2 }) : null
        });
        global.fetch = (function (inner) {
            return vi.fn(async (u, init) => {
                if (/\/rest\/job\/j1$/.test(String(u))) { polls++; return jsonResponse(200, { jobId: 'j1', terminal: false, current: 1, total: 2, phase: 'scene 1' }); }
                return inner(u, init);
            });
        })(global.fetch);
        await cmp.composeApi.init(BOOK, 's1');
        await loaded('s1');
        cmp.__openComposeDialogForTest();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        container.querySelector('[data-testid=pb-cmp-compose-start]').click();
        await waitFor(() => polls >= 1 && getActivity(BOOK, 'compose-book') && getActivity(BOOK, 'compose-book').counter === '1 of 2 · scene 1');
        let a = getActivity(BOOK, 'compose-book');
        expect(a).toMatchObject({ testid: 'pb-cmp-compose-run', section: 'scenes', terminal: false, counterTestid: 'pb-cmp-compose-counter', dismissTestid: 'pb-cmp-compose-dismiss' });
        none(cmp.composeApi.renderComposition({}), 'pb-cmp-compose-run');
        none(cmp.composeView.view(), 'pb-cmp-compose-run');
        // a scene switch keeps following the book-level job
        await cmp.composeApi.init(BOOK, 's2');
        await loaded('s2');
        let seen = polls;
        await waitFor(() => polls > seen);
        expect(getActivity(BOOK, 'compose-book')).not.toBeNull();
        expect(st().composeAbort).not.toBeNull();
        getActivity(BOOK, 'compose-book').onDismiss();
        expect(getActivity(BOOK, 'compose-book')).toBeNull();
        expect(st().composeRun).toBeNull();
        expect(st().composeAbort).toBeNull();
        m.render(container, null);
    });
    it('the cover render job publishes to activity "cover-render" (section cover)', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let url = String(u).replace(/^.*\/rest\//, '/rest/');
            let method = (init && init.method) || 'GET';
            calls.push(method + ' ' + url);
            if (method === 'POST' && url === PB + '/covers/cover/render') return jsonResponse(202, { jobId: 'j9', nodeObjectId: 'n-cover' });
            if (url === '/rest/job/j9') return jsonResponse(200, { jobId: 'j9', terminal: false, current: 1, total: 3, phase: 'render' });
            return jsonResponse(404, { error: 'unmocked' });
        });
        let wf = await import('../workflows/pictureBookWorkflow.js');
        let b = wf.normalizeBoard({ bookObjectId: BOOK, purpose: 'cover', compositionObjectId: 'comp-1', composition: compositionWire('s1', { purpose: 'cover' }), actors: [], elements: [], cover: { compositionObjectId: 'comp-1', purpose: 'cover', hostBookObjectId: BOOK, chainLength: 0 } });
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, coverPurpose: 'COVER', board: b, cover: wf.normalizeCoverEntry({ compositionObjectId: 'comp-1', purpose: 'cover', hostBookObjectId: BOOK, chainLength: 0 }), covers: {}, loading: false, pollIntervalMs: 1 });
        one(cmp.coverSection.body({ select() {} }), 'pb-cmp-render-cover').attrs.onclick();
        await waitFor(() => getActivity(BOOK, 'cover-render') && getActivity(BOOK, 'cover-render').counter === '1 of 3 · render');
        expect(getActivity(BOOK, 'cover-render')).toMatchObject({ testid: 'pb-cmp-render-run', section: 'cover', label: 'Render cover', counterTestid: 'pb-cmp-render-counter' });
        none(cmp.coverSection.body({ select() {} }), 'pb-cmp-render-run');
        getActivity(BOOK, 'cover-render').onDismiss();
        expect(activitiesFor(BOOK)).toEqual([]);
    });
});

// ─────────────────────────────── Cover & Art section ───────────────────────────────

describe('coverSection (Edit › Cover & Art, §5.7)', () => {
    function coverRead(o) {
        o = o || {};
        let calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let url = String(u).replace(/^.*\/rest\//, '/rest/');
            let method = (init && init.method) || 'GET';
            calls.push(method + ' ' + url);
            if (method === 'GET' && url === PB + '/pages') return jsonResponse(200, PAGES);
            if (method === 'GET' && url === PB + '/covers') return jsonResponse(200, { bookObjectId: BOOK, seriesObjectId: o.series || undefined, covers: { COVER: { compositionObjectId: 'comp-c', purpose: 'cover', hostBookObjectId: BOOK, chainLength: 0 } } });
            if (method === 'GET' && url === PB + '/covers/cover') return jsonResponse(200, { bookObjectId: BOOK, purpose: 'cover', compositionObjectId: 'comp-c', composition: compositionWire('c', { purpose: 'cover', objectId: 'comp-c' }), actors: [], elements: [], cover: { compositionObjectId: 'comp-c', purpose: 'cover', hostBookObjectId: BOOK, chainLength: 0 } });
            if (method === 'GET' && url === PB + '/covers/title-page') return jsonResponse(404, { error: 'Book has no TITLE_PAGE composition' });
            // the board GET is find-only: series art on a book that is not a chapter is the same 404 as "none yet"
            if (method === 'GET' && url === PB + '/covers/series-art') return jsonResponse(404, { error: 'Book has no SERIES_ART composition' });
            if (method === 'POST' && /\/covers\/[a-z-]+\/plan$/.test(url)) return jsonResponse(200, planWire());
            if (method === 'GET' && /\/outfits$/.test(url)) return jsonResponse(200, { actors: [] });
            return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
        });
        return calls;
    }
    it('lists the purposes with a ● where a composition exists; selecting routes through ctx.select; the toolbar has no back and no Scenes link', async () => {
        coverRead();
        let ctx = { bookObjectId: BOOK, item: null, select: vi.fn() };
        cmp.coverSection.init(ctx);
        await waitFor(() => st().coverPurpose === 'COVER' && st().board && st().covers && !st().loading && !st().planLoading);
        let v = cmp.coverSection.body(ctx);
        one(v, 'pb-cover-md');
        let cover = one(v, 'pb-cmp-purpose-cover');
        expect(cover.attrs['data-exists']).toBe('true');
        expect(cover.attrs['data-active']).toBe('true');
        expect(textOf(cover)).toContain('Cover●');
        let title = one(v, 'pb-cmp-purpose-title-page');
        expect(title.attrs['data-exists']).toBe('false');
        expect(textOf(title)).not.toContain('●');
        one(v, 'pb-cmp-purpose-chapter-opener');
        none(v, 'pb-cmp-purpose-series-art');            // not a chapter of a series
        one(v, 'pb-cmp-cover');                            // the cover strip in the detail
        one(v, 'pb-cmp-camera');
        title.attrs.onclick();
        expect(ctx.select).toHaveBeenCalledWith('title-page');

        let tb = cmp.coverSection.toolbar(ctx);
        expect(tb.title).toBe('Cover & Art');
        expect(tb.back).toBeUndefined();
        one(tb.actions, 'pb-cmp-chatconfig');
        none(tb.actions, 'pb-cmp-scenes-link');
        expect(cmp.coverSection.dirtyKeys).toEqual(['pb-cover']);
        expect(cmp.coverSection.available(ctx)).toBe(true);
    });
    it('a route that names Series art on a standalone book explains why in the detail, without listing the purpose (e2e SeriesArt, 2026-11-19)', async () => {
        coverRead();
        let ctx = { bookObjectId: BOOK, item: 'series-art', select: vi.fn() };
        cmp.coverSection.init(ctx);
        await waitFor(() => st().coverPurpose === 'SERIES_ART' && st().covers && st().noComposition && !st().loading);
        let v = cmp.coverSection.body(ctx);
        none(v, 'pb-cmp-purpose-series-art');             // a standalone never offers Series art
        one(v, 'pb-cmp-empty-not-chapter');
    });
    it('select() moves to the purpose word; an empty purpose offers "Create title page…"; Series art is listed on a chapter', async () => {
        coverRead({ series: 'ser-1' });
        let ctx = { bookObjectId: BOOK, item: 'cover', select: vi.fn() };
        cmp.coverSection.init(ctx);
        await waitFor(() => st().board && st().covers && !st().loading && !st().planLoading);
        one(cmp.coverSection.body(ctx), 'pb-cmp-purpose-series-art');
        cmp.coverSection.select(Object.assign({}, ctx, { item: 'title-page' }));
        await waitFor(() => st().coverPurpose === 'TITLE_PAGE' && st().noComposition && !st().loading);
        let v = cmp.coverSection.body(ctx);
        expect(one(v, 'pb-cmp-purpose-title-page').attrs['data-active']).toBe('true');
        expect(textOf(one(v, 'pb-cmp-create-cover-open'))).toContain('Create title page…');
    });
});
