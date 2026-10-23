// @vitest-environment jsdom
/**
 * PictureBook V3 — character manager "Wardrobe" panel (P4-9, design §26 / §27).
 *
 * Why: the panel shows server state only (one GET /{book}/character/{char}/wardrobe, re-read after every
 * write), every write is one of the P4-8 wardrobe routes with its exact wire shape (PUT .../default with no
 * body, POST .../reference as a 202 job that is polled to terminal, POST /{book}/wardrobe/migrate-scene-tags,
 * POST .../wardrobe {description} with no chatConfigName), the result sentences are built from the DTO
 * counts, and the panel is V3-only. A reference is rendered, never uploaded — no upload route is wired.
 * No network, LLM or SD is touched: fetch, am7client.clearCache and page.toast are stubbed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';

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
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}
function seqOf(calls) { return calls.map(c => c.method + ' ' + c.url); }

// Wire fixtures: PbServiceFacade wardrobeDto / wardrobeEntryDto (NON_EMPTY, so absent lists are normal on the wire).
function entryWire(extra) {
    return Object.assign({ objectId: 'ap1', name: 'Rain cloak', description: 'a grey wool cloak', isDefault: true,
        wearables: [{ objectId: 'w1', name: 'cloak' }, { objectId: 'w2', name: 'boots' }], gallery: [], referenceObjectId: null }, extra || {});
}
function wardrobeWire(entries, extra) {
    return Object.assign({ bookObjectId: BOOK, characterObjectId: 'c1', characterName: 'Alice', storeObjectId: 'st1', defaultApparelObjectId: 'ap1',
        entries: entries, entryCount: entries.length, warnings: [] }, extra || {});
}

let origFetch;
let pbc;
let wf;

beforeEach(async () => {
    origFetch = global.fetch;
    pbc = await import('../workflows/pictureBookCharacters.js');
    wf = await import('../workflows/pictureBookWorkflow.js');
    pbc.__setPbCharStateForTest({
        bookObjectId: BOOK, standalone: true, renderPath: 'v3', selectedObjectId: 'c1', selectedInst: { entity: { objectId: 'c1', name: 'Alice' } },
        affected: null, lastSaveKind: null, regen: null, editingIdentity: false, editName: '', editDescription: '', regenPollIntervalMs: 2,
        wardrobe: null, wardrobeBusy: null, wardrobeAddText: '', wardrobeRuns: {}
    });
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(Dialog, 'closeAll').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
});

afterEach(() => {
    pbc.__setPbCharStateForTest({ wardrobe: null, wardrobeBusy: null, wardrobeAddText: '', wardrobeRuns: {}, selectedInst: null, selectedObjectId: null });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

/**
 * fetch router for the wardrobe wire. o.route(method, url, body, calls) answers first; then GET /rest/job/{id}
 * via o.job(id, nth); then the one wardrobe read from o.wardrobe(calls).
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
        if (method === 'GET' && url === PB + '/character/c1/wardrobe') return jsonResponse(200, o.wardrobe ? o.wardrobe(calls) : wardrobeWire([entryWire()]));
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}

function seedLoaded(entries, extra) {
    pbc.__setPbCharStateForTest({ wardrobe: { forObjectId: 'c1', loading: false, data: wf.normalizeWardrobe(wardrobeWire(entries, extra)), error: null } });
}

// ─────────────────────────────── pure helpers ───────────────────────────────

describe('wardrobeEntryKind / wearablesText', () => {
    it('labels default, legacy scene-tagged and plain alternates', () => {
        expect(pbc.wardrobeEntryKind(null)).toBe('alternate');
        expect(pbc.wardrobeEntryKind({ isDefault: true, sceneIndex: 3 })).toBe('default');
        expect(pbc.wardrobeEntryKind({ isDefault: false, sceneIndex: 3 })).toBe('scene 3');
        expect(pbc.wardrobeEntryKind({ isDefault: false, sceneIndex: 0 })).toBe('scene 0');
        expect(pbc.wardrobeEntryKind({ isDefault: false, sceneIndex: null })).toBe('alternate');
    });
    it('joins the wearable names and drops blanks', () => {
        expect(pbc.wearablesText({ wearables: [{ name: 'cloak' }, { name: '' }, null, { name: 'boots' }] })).toBe('cloak, boots');
        expect(pbc.wearablesText({ wearables: [] })).toBe('');
        expect(pbc.wearablesText({})).toBe('');
        expect(pbc.wearablesText(null)).toBe('');
    });
});

describe('setDefaultText / migrateText — DTO counts → sentences', () => {
    it('setDefaultText', () => {
        expect(pbc.setDefaultText({ changed: false })).toBe('Already the default outfit');
        expect(pbc.setDefaultText(null)).toBe('Already the default outfit');
        expect(pbc.setDefaultText({ changed: true, staled: 0 })).toBe('Default outfit set — no scene wore the previous default');
        expect(pbc.setDefaultText({ changed: true, staled: 1 })).toBe('Default outfit set — 1 scene marked stale');
        expect(pbc.setDefaultText({ changed: true, staled: 3 })).toBe('Default outfit set — 3 scenes marked stale');
    });
    it('migrateText', () => {
        expect(pbc.migrateText(null)).toBe('Scanned 0 tagged apparel: pinned 0');
        expect(pbc.migrateText({ scanned: 4, pinned: 4 })).toBe('Scanned 4 tagged apparel: pinned 4');
        expect(pbc.migrateText({ scanned: 9, pinned: 3, skippedPinned: 2, skippedNoActor: 1, skippedNoScene: 1, skippedNoComposition: 1, ambiguous: 1 }))
            .toBe('Scanned 9 tagged apparel: pinned 3, skipped 5 (2 already pinned, 1 no actor, 1 no scene, 1 no composition), 1 ambiguous');
        expect(pbc.migrateText({ scanned: 2, pinned: 1, skippedNoActor: 1 })).toBe('Scanned 2 tagged apparel: pinned 1, skipped 1 (1 no actor)');
    });
});

// ─────────────────────────────── panel rendering ───────────────────────────────

describe('renderWardrobePanel — gating and states', () => {
    it('is null outside V3 / standalone / a selected character', () => {
        pbc.__setPbCharStateForTest({ renderPath: 'v2' });
        expect(pbc.renderWardrobePanel()).toBeNull();
        pbc.__setPbCharStateForTest({ renderPath: 'v3', standalone: false });
        expect(pbc.renderWardrobePanel()).toBeNull();
        pbc.__setPbCharStateForTest({ standalone: true, bookObjectId: null });
        expect(pbc.renderWardrobePanel()).toBeNull();
        pbc.__setPbCharStateForTest({ bookObjectId: BOOK, selectedInst: null });
        expect(pbc.renderWardrobePanel()).toBeNull();
    });
    it('shows Loading while there is no wardrobe for the selected character, or it is loading', () => {
        let v = pbc.renderWardrobePanel();
        expect(byTestId(v, 'pb-wd').length).toBe(1);
        expect(textOf(one(v, 'pb-wd-loading'))).toBe('Loading wardrobe…');
        pbc.__setPbCharStateForTest({ wardrobe: { forObjectId: 'c-other', loading: false, data: wf.normalizeWardrobe(wardrobeWire([])), error: null } });
        expect(byTestId(pbc.renderWardrobePanel(), 'pb-wd-loading').length).toBe(1);
        pbc.__setPbCharStateForTest({ wardrobe: { forObjectId: 'c1', loading: true, data: null, error: null } });
        expect(byTestId(pbc.renderWardrobePanel(), 'pb-wd-loading').length).toBe(1);
    });
    it('shows the server error verbatim', () => {
        pbc.__setPbCharStateForTest({ wardrobe: { forObjectId: 'c1', loading: false, data: null, error: '(404) Character c9 is not a character of this book' } });
        let v = pbc.renderWardrobePanel();
        expect(textOf(one(v, 'pb-wd-error'))).toBe('(404) Character c9 is not a character of this book');
        expect(byTestId(v, 'pb-wd-entries').length).toBe(0);
    });
    it('empty wardrobe: the empty line, the add row, the migrate button and the no-upload note', () => {
        seedLoaded([]);
        let v = pbc.renderWardrobePanel();
        expect(textOf(one(v, 'pb-wd-empty'))).toBe('No outfits in the wardrobe yet.');
        expect(byTestId(v, 'pb-wd-entries').length).toBe(0);
        expect(byTestId(v, 'pb-wd-warnings').length).toBe(0);
        expect(one(v, 'pb-wd-add').attrs.disabled).toBe(true);
        expect(textOf(one(v, 'pb-wd-add'))).toBe('Add alternate');
        expect(one(v, 'pb-wd-add-text').attrs.disabled).toBe(false);
        expect(one(v, 'pb-wd-migrate').attrs.disabled).toBe(false);
        expect(textOf(one(v, 'pb-wd-migrate'))).toBe('Pin scene tags to actors');
        expect(textOf(one(v, 'pb-wd-note'))).toBe('References are rendered on the character, not uploaded; there is no review step for them yet.');
    });
    it('entries: default chip, scene/alternate kind chip, wearables, thumb placeholder vs component, image count, warnings', () => {
        seedLoaded([
            entryWire(),
            entryWire({ objectId: 'ap2', name: 'Market dress', description: '', isDefault: false, sceneIndex: 4, wearables: [], gallery: [{ objectId: 'g1' }, { objectId: 'g2' }], referenceObjectId: 'g2' }),
            entryWire({ objectId: 'ap3', name: null, isDefault: false, sceneIndex: null, wearables: [{ name: 'hat' }], gallery: [{ objectId: 'g3' }], referenceObjectId: 'g3' })
        ], { warnings: ['store has no default apparel flag', 'two defaults found'] });
        let v = pbc.renderWardrobePanel();
        let list = one(v, 'pb-wd-entries');
        expect(list.attrs['data-count']).toBe('3');
        let e1 = one(v, 'pb-wd-entry-ap1');
        expect(e1.attrs['data-default']).toBe('1');
        expect(e1.attrs['data-apparel-objectid']).toBe('ap1');
        expect(textOf(one(e1, 'pb-wd-default-chip'))).toBe('Default');
        expect(byTestId(e1, 'pb-wd-kind-chip').length).toBe(0);
        expect(textOf(one(e1, 'pb-wd-wearables'))).toBe('cloak, boots');
        expect(byTestId(e1, 'pb-wd-thumb-none').length).toBe(1);
        expect(textOf(e1)).toContain('Rain cloak');
        expect(textOf(e1)).toContain('a grey wool cloak');
        expect(textOf(e1)).not.toContain('image');
        expect(one(e1, 'pb-wd-set-default').attrs.disabled).toBe(true);
        expect(textOf(one(e1, 'pb-wd-render-ref'))).toBe('Render reference');
        expect(one(e1, 'pb-wd-render-ref').attrs.disabled).toBe(false);

        let e2 = one(v, 'pb-wd-entry-ap2');
        expect(e2.attrs['data-default']).toBe('0');
        expect(textOf(one(e2, 'pb-wd-kind-chip'))).toBe('scene 4');
        expect(textOf(one(e2, 'pb-wd-wearables'))).toBe('No wearables listed');
        expect(textOf(e2)).toContain('2 images');
        expect(byTestId(e2, 'pb-wd-thumb-none').length).toBe(0);
        // the reference thumb is a component vnode keyed by the reference data objectId (resolved on mount, not here)
        let thumbs = findAll(e2, n => n.attrs && n.attrs.objectId === 'g2');
        expect(thumbs.length).toBe(1);
        expect(typeof thumbs[0].tag).toBe('object');
        expect(one(e2, 'pb-wd-set-default').attrs.disabled).toBe(false);
        expect(textOf(one(e2, 'pb-wd-render-ref'))).toBe('Re-render reference');

        let e3 = one(v, 'pb-wd-entry-ap3');
        expect(textOf(e3)).toContain('(apparel)');
        expect(textOf(one(e3, 'pb-wd-kind-chip'))).toBe('alternate');
        expect(textOf(e3)).toContain('1 image');
        expect(textOf(e3)).not.toContain('1 images');

        expect(textOf(one(v, 'pb-wd-warnings'))).toBe('store has no default apparel flag · two defaults found');
    });
    it('busy state disables every control and names the running action', () => {
        seedLoaded([entryWire(), entryWire({ objectId: 'ap2', isDefault: false })]);
        pbc.__setPbCharStateForTest({ wardrobeBusy: 'default:ap2', wardrobeAddText: 'a hat' });
        let v = pbc.renderWardrobePanel();
        expect(textOf(one(one(v, 'pb-wd-entry-ap2'), 'pb-wd-set-default'))).toBe('Setting…');
        expect(textOf(one(one(v, 'pb-wd-entry-ap1'), 'pb-wd-set-default'))).toBe('Set default');
        byTestId(v, 'pb-wd-set-default').forEach(b => expect(b.attrs.disabled).toBe(true));
        byTestId(v, 'pb-wd-render-ref').forEach(b => expect(b.attrs.disabled).toBe(true));
        expect(one(v, 'pb-wd-add').attrs.disabled).toBe(true);
        expect(one(v, 'pb-wd-add-text').attrs.disabled).toBe(true);
        expect(one(v, 'pb-wd-migrate').attrs.disabled).toBe(true);
        pbc.__setPbCharStateForTest({ wardrobeBusy: 'add' });
        expect(textOf(one(pbc.renderWardrobePanel(), 'pb-wd-add'))).toBe('Adding…');
        pbc.__setPbCharStateForTest({ wardrobeBusy: 'migrate' });
        expect(textOf(one(pbc.renderWardrobePanel(), 'pb-wd-migrate'))).toBe('Pinning…');
        pbc.__setPbCharStateForTest({ wardrobeBusy: null });
        expect(one(pbc.renderWardrobePanel(), 'pb-wd-add').attrs.disabled).toBe(false);
    });
    it('a running reference render shows its phase on the entry and disables that Render button', () => {
        seedLoaded([entryWire({ isDefault: false })]);
        pbc.__setPbCharStateForTest({ wardrobeRuns: { ap1: { jobId: 'j1', job: { phase: 'apparel-reference', terminal: false }, error: null } } });
        let v = pbc.renderWardrobePanel();
        let e1 = one(v, 'pb-wd-entry-ap1');
        expect(textOf(one(e1, 'pb-wd-render-progress'))).toBe('Rendering reference… apparel-reference');
        expect(textOf(one(e1, 'pb-wd-render-ref'))).toBe('Rendering…');
        expect(one(e1, 'pb-wd-render-ref').attrs.disabled).toBe(true);
        expect(one(e1, 'pb-wd-set-default').attrs.disabled).toBe(false);
        pbc.__setPbCharStateForTest({ wardrobeRuns: { ap1: { jobId: 'j1', job: null, error: null } } });
        expect(textOf(one(pbc.renderWardrobePanel(), 'pb-wd-render-progress'))).toBe('Rendering reference… submitted');
    });
});

// ─────────────────────────────── wire ───────────────────────────────

describe('loadWardrobe — the one read', () => {
    it('GETs /{book}/character/{char}/wardrobe once and normalizes the DTO', async () => {
        let calls = wire({ wardrobe: () => wardrobeWire([entryWire({ gallery: undefined, wearables: undefined })]) });
        await pbc.__pbCharLoadWardrobeForTest('c1');
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/character/c1/wardrobe']);
        let st = pbc.__pbCharStateForTest();
        expect(st.wardrobe.forObjectId).toBe('c1');
        expect(st.wardrobe.loading).toBe(false);
        expect(st.wardrobe.error).toBeNull();
        expect(st.wardrobe.data.entries.length).toBe(1);
        expect(st.wardrobe.data.entries[0].gallery).toEqual([]);
        expect(st.wardrobe.data.entries[0].wearables).toEqual([]);
        expect(st.wardrobe.data.entryCount).toBe(1);
        let v = pbc.renderWardrobePanel();
        expect(byTestId(v, 'pb-wd-entry-ap1').length).toBe(1);
    });
    it('keeps the server message when the read fails, and is a no-op outside V3', async () => {
        let calls = wire({ route: (method, url) => method === 'GET' && /\/wardrobe$/.test(url) ? jsonResponse(404, { error: 'Character c1 is not a character of this book' }) : null });
        await pbc.__pbCharLoadWardrobeForTest('c1');
        let st = pbc.__pbCharStateForTest();
        expect(st.wardrobe.error).toBe('(404) Character c1 is not a character of this book');
        expect(textOf(one(pbc.renderWardrobePanel(), 'pb-wd-error'))).toBe('(404) Character c1 is not a character of this book');
        expect(calls.length).toBe(1);
        pbc.__setPbCharStateForTest({ renderPath: 'v2' });
        await pbc.__pbCharLoadWardrobeForTest('c1');
        expect(pbc.__pbCharStateForTest().wardrobe).toBeNull();
        expect(calls.length).toBe(1);
    });
});

describe('Set default — PUT .../wardrobe/{apparel}/default, no body, then the one re-read', () => {
    it('toasts the staled count and re-reads', async () => {
        seedLoaded([entryWire(), entryWire({ objectId: 'ap2', isDefault: false })]);
        let reads = 0;
        let calls = wire({
            route: (method, url) => method === 'PUT' && url === PB + '/character/c1/wardrobe/ap2/default'
                ? jsonResponse(200, { apparelObjectId: 'ap2', previousApparelObjectIds: ['ap1'], changed: true, scenesScanned: 6, staled: 2 }) : null,
            wardrobe: () => { reads++; return wardrobeWire([entryWire({ isDefault: false }), entryWire({ objectId: 'ap2', isDefault: true })]); }
        });
        let v = pbc.renderWardrobePanel();
        one(one(v, 'pb-wd-entry-ap2'), 'pb-wd-set-default').attrs.onclick();
        expect(pbc.__pbCharStateForTest().wardrobeBusy).toBe('default:ap2');
        await waitFor(() => reads === 1 && pbc.__pbCharStateForTest().wardrobeBusy === null);
        expect(seqOf(calls)).toEqual(['PUT ' + PB + '/character/c1/wardrobe/ap2/default', 'GET ' + PB + '/character/c1/wardrobe']);
        expect(calls[0].rawBody).toBeUndefined();
        expect(page.toast).toHaveBeenCalledWith('success', 'Default outfit set — 2 scenes marked stale');
        expect(one(pbc.renderWardrobePanel(), 'pb-wd-entry-ap2').attrs['data-default']).toBe('1');
    });
    it('a server refusal is toasted verbatim and the wardrobe is still re-read', async () => {
        seedLoaded([entryWire({ isDefault: false })]);
        let calls = wire({
            route: (method, url) => method === 'PUT' && /\/default$/.test(url) ? jsonResponse(400, { error: 'Apparel ap1 is not in the wardrobe of Alice' }) : null
        });
        one(pbc.renderWardrobePanel(), 'pb-wd-set-default').attrs.onclick();
        await waitFor(() => calls.length === 2 && pbc.__pbCharStateForTest().wardrobeBusy === null);
        expect(page.toast).toHaveBeenCalledWith('error', '(400) Apparel ap1 is not in the wardrobe of Alice');
        expect(seqOf(calls)[1]).toBe('GET ' + PB + '/character/c1/wardrobe');
    });
    it('surfaces the DTO warnings as a warning toast after the success toast', async () => {
        seedLoaded([entryWire({ isDefault: false })]);
        wire({
            route: (method, url) => method === 'PUT' && /\/default$/.test(url)
                ? jsonResponse(200, { apparelObjectId: 'ap1', changed: true, staled: 0, warnings: ['scene 3 pins a different outfit'] }) : null
        });
        one(pbc.renderWardrobePanel(), 'pb-wd-set-default').attrs.onclick();
        await waitFor(() => pbc.__pbCharStateForTest().wardrobeBusy === null && page.toast.mock.calls.length >= 2);
        expect(page.toast.mock.calls[0]).toEqual(['success', 'Default outfit set — no scene wore the previous default']);
        expect(page.toast.mock.calls[1]).toEqual(['warning', 'scene 3 pins a different outfit']);
    });
    it('is ignored while another wardrobe write is busy', async () => {
        seedLoaded([entryWire({ isDefault: false })]);
        pbc.__setPbCharStateForTest({ wardrobeBusy: 'migrate' });
        let calls = wire();
        pbc.__pbCharSetDefaultForTest({ objectId: 'ap1' });
        await new Promise(r => setTimeout(r, 5));
        expect(calls.length).toBe(0);
    });
});

describe('Add alternate — POST .../wardrobe {description}, no chatConfigName', () => {
    it('blank text is refused client-side with a warning and no request', async () => {
        seedLoaded([]);
        let calls = wire();
        pbc.__setPbCharStateForTest({ wardrobeAddText: '   ' });
        pbc.__pbCharAddWardrobeEntryForTest();
        await new Promise(r => setTimeout(r, 5));
        expect(calls.length).toBe(0);
        expect(page.toast).toHaveBeenCalledWith('warning', 'Describe the outfit first');
    });
    it('sends the trimmed description only, toasts the wearable/unmatched counts, clears the text and re-reads', async () => {
        seedLoaded([]);
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/character/c1/wardrobe'
                ? jsonResponse(201, { apparelObjectId: 'ap9', name: 'grey wool coat', reused: false, items: [{ name: 'coat' }], unmatched: ['red dress'], wearables: 1, colorsResolved: 1, elapsedMs: 1200 }) : null,
            wardrobe: () => wardrobeWire([entryWire({ objectId: 'ap9', name: 'grey wool coat', isDefault: false })])
        });
        let v = pbc.renderWardrobePanel();
        one(v, 'pb-wd-add-text').attrs.oninput({ target: { value: '  a grey wool coat over a red dress  ' } });
        v = pbc.renderWardrobePanel();
        expect(one(v, 'pb-wd-add').attrs.disabled).toBe(false);
        one(v, 'pb-wd-add').attrs.onclick();
        await waitFor(() => calls.length === 2 && pbc.__pbCharStateForTest().wardrobeBusy === null);
        expect(seqOf(calls)).toEqual(['POST ' + PB + '/character/c1/wardrobe', 'GET ' + PB + '/character/c1/wardrobe']);
        expect(calls[0].body).toEqual({ description: 'a grey wool coat over a red dress' });
        expect(page.toast).toHaveBeenCalledWith('success', 'Added grey wool coat (1 wearable, unmatched: red dress)');
        expect(pbc.__pbCharStateForTest().wardrobeAddText).toBe('');
        expect(byTestId(pbc.renderWardrobePanel(), 'pb-wd-entry-ap9').length).toBe(1);
    });
    it('a reused entry is reported as such; Enter on the text field submits', async () => {
        seedLoaded([entryWire()]);
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/character/c1/wardrobe'
                ? jsonResponse(200, { apparelObjectId: 'ap1', name: 'Rain cloak', reused: true, wearables: 2 }) : null
        });
        pbc.__setPbCharStateForTest({ wardrobeAddText: 'a grey wool cloak' });
        let prevented = false;
        one(pbc.renderWardrobePanel(), 'pb-wd-add-text').attrs.onkeydown({ key: 'Enter', preventDefault: () => { prevented = true; } });
        expect(prevented).toBe(true);
        await waitFor(() => calls.length === 2 && pbc.__pbCharStateForTest().wardrobeBusy === null);
        expect(page.toast).toHaveBeenCalledWith('success', 'That outfit is already in the wardrobe: Rain cloak');
        expect(calls[0].body).toEqual({ description: 'a grey wool cloak' });
    });
    it('a 502 (the LLM produced nothing usable) keeps the text and toasts the server message', async () => {
        seedLoaded([]);
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/character/c1/wardrobe' ? jsonResponse(502, { error: 'The LLM produced no usable apparel' }) : null
        });
        pbc.__setPbCharStateForTest({ wardrobeAddText: 'a hat' });
        pbc.__pbCharAddWardrobeEntryForTest();
        await waitFor(() => calls.length === 2 && pbc.__pbCharStateForTest().wardrobeBusy === null);
        expect(page.toast).toHaveBeenCalledWith('error', '(502) The LLM produced no usable apparel');
        expect(pbc.__pbCharStateForTest().wardrobeAddText).toBe('a hat');
    });
});

describe('Render reference — POST .../wardrobe/{apparel}/reference (202 job), polled to terminal, then re-read', () => {
    it('submits, shows the run, polls GET /rest/job/{id}, toasts, clears the apparel/data caches and re-reads', async () => {
        seedLoaded([entryWire({ isDefault: false })]);
        let reads = 0;
        let release = false;
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/character/c1/wardrobe/ap1/reference'
                ? jsonResponse(202, { bookObjectId: BOOK, kind: 'pb.apparelReference', submitted: true, reused: false, jobId: 'j1', jobStatus: 'queued' }) : null,
            job: (id) => !release
                ? jsonResponse(200, { jobId: id, status: 'running', phase: 'apparel-reference', current: 0, total: 1, terminal: false })
                : jsonResponse(200, { jobId: id, status: 'completed', current: 1, total: 1, terminal: true, result: { apparelObjectId: 'ap1', imageCount: 1, referenceObjectId: 'g9' } }),
            wardrobe: () => { reads++; return wardrobeWire([entryWire({ isDefault: false, gallery: [{ objectId: 'g9' }], referenceObjectId: 'g9' })]); }
        });
        one(pbc.renderWardrobePanel(), 'pb-wd-render-ref').attrs.onclick();
        await waitFor(() => calls.length >= 1);
        expect(calls[0].rawBody).toBeUndefined();
        await waitFor(() => !!pbc.__pbCharStateForTest().wardrobeRuns.ap1);
        expect(pbc.__pbCharStateForTest().wardrobeRuns.ap1.jobId).toBe('j1');
        await waitFor(() => { let r = pbc.__pbCharStateForTest().wardrobeRuns.ap1; return !!(r && r.job && r.job.phase === 'apparel-reference'); });
        expect(textOf(one(pbc.renderWardrobePanel(), 'pb-wd-render-progress'))).toBe('Rendering reference… apparel-reference');
        let pollsBefore = seqOf(calls).filter(s => s.indexOf('/rest/job/j1') >= 0).length;
        expect(pollsBefore).toBeGreaterThanOrEqual(1);
        release = true;
        await waitFor(() => reads === 1 && !pbc.__pbCharStateForTest().wardrobeRuns.ap1, 4000);
        expect(seqOf(calls).slice(0, 1)).toEqual(['POST ' + PB + '/character/c1/wardrobe/ap1/reference']);
        expect(seqOf(calls).filter(s => s.indexOf('/rest/job/j1') >= 0).length).toBeGreaterThan(pollsBefore);
        expect(seqOf(calls)[seqOf(calls).length - 1]).toBe('GET ' + PB + '/character/c1/wardrobe');
        expect(page.toast).toHaveBeenCalledWith('success', 'Reference image rendered (1 image)');
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.apparel', true);
        expect(am7client.clearCache).toHaveBeenCalledWith('data.data', true);
        let v = pbc.renderWardrobePanel();
        expect(byTestId(v, 'pb-wd-render-progress').length).toBe(0);
        expect(textOf(one(v, 'pb-wd-render-ref'))).toBe('Re-render reference');
        expect(textOf(v)).toContain('1 image');
    });
    it('a failed job is a warning with the job error; a rejected submit is an error and nothing is polled', async () => {
        seedLoaded([entryWire({ isDefault: false })]);
        let calls = wire({
            route: (method, url) => method === 'POST' && /\/reference$/.test(url)
                ? jsonResponse(202, { submitted: true, jobId: 'j2', jobStatus: 'queued', kind: 'pb.apparelReference' }) : null,
            job: (id) => jsonResponse(200, { jobId: id, status: 'failed', terminal: true, error: 'No SD backend is configured' })
        });
        await pbc.__pbCharRenderReferenceForTest({ objectId: 'ap1' });
        expect(page.toast).toHaveBeenCalledWith('warning', 'Reference render failed: No SD backend is configured');
        expect(seqOf(calls)[seqOf(calls).length - 1]).toBe('GET ' + PB + '/character/c1/wardrobe');

        page.toast.mockClear();
        let calls2 = wire({ route: (method, url) => method === 'POST' && /\/reference$/.test(url) ? jsonResponse(409, { error: 'A reference render is already running for apparel ap1' }) : null });
        await pbc.__pbCharRenderReferenceForTest({ objectId: 'ap1' });
        expect(page.toast).toHaveBeenCalledWith('error', '(409) A reference render is already running for apparel ap1');
        expect(seqOf(calls2)).toEqual(['POST ' + PB + '/character/c1/wardrobe/ap1/reference']);
    });
    it('an accepted submit without a job id is an error, not a hang', async () => {
        seedLoaded([entryWire({ isDefault: false })]);
        let calls = wire({ route: (method, url) => method === 'POST' && /\/reference$/.test(url) ? jsonResponse(202, { submitted: true }) : null });
        await pbc.__pbCharRenderReferenceForTest({ objectId: 'ap1' });
        expect(page.toast).toHaveBeenCalledWith('error', 'Reference render was accepted but no job id was returned');
        expect(calls.length).toBe(1);
        expect(pbc.__pbCharStateForTest().wardrobeRuns.ap1).toBeUndefined();
    });
});

describe('Pin scene tags — POST /{book}/wardrobe/migrate-scene-tags, then re-read', () => {
    it('toasts the migrate sentence from the counts and the warnings separately', async () => {
        seedLoaded([entryWire()]);
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/wardrobe/migrate-scene-tags'
                ? jsonResponse(200, { scanned: 5, pinned: 3, skippedPinned: 1, skippedNoActor: 1, ambiguous: 0, warnings: ['scene 7: two actors named Alice'] }) : null
        });
        one(pbc.renderWardrobePanel(), 'pb-wd-migrate').attrs.onclick();
        expect(pbc.__pbCharStateForTest().wardrobeBusy).toBe('migrate');
        await waitFor(() => calls.length === 2 && pbc.__pbCharStateForTest().wardrobeBusy === null);
        expect(seqOf(calls)).toEqual(['POST ' + PB + '/wardrobe/migrate-scene-tags', 'GET ' + PB + '/character/c1/wardrobe']);
        expect(calls[0].rawBody).toBeUndefined();
        expect(page.toast).toHaveBeenCalledWith('success', 'Scanned 5 tagged apparel: pinned 3, skipped 2 (1 already pinned, 1 no actor)');
        expect(page.toast).toHaveBeenCalledWith('warning', 'scene 7: two actors named Alice');
    });
});
