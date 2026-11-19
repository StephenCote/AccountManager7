// @vitest-environment jsdom
/**
 * Edit › Locations as a bookWorkspace section (aiDocs/PictureBookUxConsistencyDesign.md §4.2, §4.5, §4.7, §5.5,
 * §6.2). Rendered with the real Mithril renderer into jsdom — inside the real createBookWorkspace shell where the
 * behaviour is the workspace's (toolbar without back, the dock, the rail status), on its own for the body.
 *
 * Why: the section toolbar must lose its back arrow (the rail replaces it); the card grid became master/detail
 * with a pinned Proposals pseudo-row; an open inline edit must be held by the dirty guard (a failed Save keeps the
 * user where they are); a location render's progress goes to the activity store (dock / pill) and never into the
 * page flow; "used by scenes 3, 7" links each scene to its Page tab. Network is a stubbed fetch with the
 * PbServiceFacade wire shapes; no LLM / SD is touched.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import * as lib from '../features/pictureBookLocations.js';
import * as wf from '../workflows/pictureBookWorkflow.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import { createBookWorkspace } from '../components/bookWorkspace.js';
import { anyDirty, guardDirty, __resetDirtyForTest } from '../components/dirtyGuard.js';
import { getActivity, activitiesFor, __resetActivityForTest } from '../workflows/bookActivity.js';

const BOOK = 'book-oid-1';
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
function wire(o) {
    o = o || {};
    let calls = [];
    let polls = {};
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u).replace(/^.*\/rest\//, '/rest/');
        let method = (init && init.method) || 'GET';
        let body = init && typeof init.body === 'string' ? JSON.parse(init.body) : null;
        calls.push({ url, method, body });
        if (o.route) { let r = o.route(method, url, body); if (r) return r; }
        let mj = /^\/rest\/job\/([^/?]+)$/.exec(url);
        if (mj && method === 'GET') { polls[mj[1]] = (polls[mj[1]] || 0) + 1; return o.job ? o.job(mj[1], polls[mj[1]]) : jsonResponse(404, { error: 'Job not found' }); }
        if (method === 'GET' && url === PB + '/locations') return jsonResponse(200, { bookObjectId: BOOK, worldObjectId: 'W1', locations: o.locations ? o.locations() : [] });
        if (method === 'GET' && url.indexOf(PB + '/locations/proposed') === 0) return jsonResponse(200, { proposals: o.proposals ? o.proposals() : [] });
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}
function tavernWire(extra) {
    return Object.assign({
        objectId: 'L1', name: 'The Drowned Anchor', description: 'A low tavern by the quay, lamplit', kind: 'mixed', aliases: ['the anchor'], locked: false,
        usedByScenes: [{ sceneObjectId: 's7', sceneIndex: 7 }, { sceneObjectId: 's3', sceneIndex: 3 }],
        node: { objectId: 'n1', handle: 'location-tavern', storedStatus: 'DONE', pinned: false, chain: [] },
        variants: []
    }, extra || {});
}
function quayWire(extra) { return Object.assign({ objectId: 'L2', name: 'The Quay', kind: 'exterior', locked: true, usedByScenes: [], variants: [] }, extra || {}); }
function proposalWire(extra) {
    return Object.assign({ objectId: 'P1', proposedName: 'The Lighthouse', kind: 'exterior', aliases: [], sceneObjectIds: ['s1'], proposalStatus: 'proposed' }, extra || {});
}
function freshState(extra) {
    return Object.assign({
        bookObjectId: BOOK, locations: [], worldObjectId: null, loading: false, error: null, proposals: [], proposalFilter: 'PROPOSED',
        proposalsLoading: false, proposalsError: null, proposing: false, proposeJob: null, busy: {}, edits: {}, runs: {}, pinnedRefusals: {}, overwrite: {},
        imageUrls: {}, pollIntervalMs: 1, loadSeq: 0, chatConfigRef: null, chatConfigResolving: false, chatConfigPending: null,
        openProposalCount: null, selectedId: null, selectedIndex: -1, wsKind: null
    }, extra || {});
}
function render(vnode) {
    let div = document.createElement('div');
    document.body.appendChild(div);
    m.render(div, vnode);
    return div;
}
function q(el, testid) { return el.querySelector('[data-testid="' + testid + '"]'); }
function qa(el, testid) { return Array.from(el.querySelectorAll('[data-testid="' + testid + '"]')); }

let origFetch;
beforeEach(() => {
    origFetch = global.fetch;
    Dialog._reset();
    document.body.innerHTML = '';
    __resetActivityForTest();
    __resetDirtyForTest();
    lib.__setLocationsStateForTest(freshState());
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(am7client, 'get').mockImplementation((type, id, cb) => { cb(null); });
    vi.spyOn(LLMConnector, 'resolveConfig').mockResolvedValue(null);
});
afterEach(() => {
    lib.locationsSection.remove({});
    lib.__setLocationsStateForTest(freshState({ bookObjectId: null }));
    Dialog._reset();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

function workspace() {
    return createBookWorkspace({ kind: 'pb', header: () => ({ title: 'Book' }), adapters: { locations: lib.locationsSection, scenes: { body: () => null } } });
}

describe('locationsSection inside bookWorkspace', () => {
    it('init loads the book; the toolbar has title, count, chat config, Propose, New, refresh — and no back arrow', async () => {
        lib.__setLocationsStateForTest(freshState({ bookObjectId: null }));
        let calls = wire({ locations: () => [tavernWire(), quayWire()], proposals: () => [proposalWire()] });
        let ws = workspace();
        let div = render(m(ws.view, { bookObjectId: BOOK, section: 'locations' }));
        await waitFor(() => calls.filter(c => /\/proposed/.test(c.url)).length === 1 && !lib.__locationsStateForTest().proposalsLoading);
        m.render(div, m(ws.view, { bookObjectId: BOOK, section: 'locations' }));
        let pane = q(div, 'ws-pane');
        expect(pane.getAttribute('data-section')).toBe('locations');
        let hdr = pane.querySelector('[data-page-header]');
        expect(hdr).not.toBeNull();
        expect(hdr.querySelector('h2').textContent).toBe('Locations');
        expect(q(div, 'pb-loc-back')).toBeNull();
        expect(q(hdr, 'pb-loc-count').textContent).toBe('2 locations');
        ['pb-loc-chatconfig', 'pb-loc-propose', 'pb-loc-new', 'pb-loc-refresh'].forEach(t => expect(q(hdr, t), t).not.toBeNull());
        // the rail's Locations mark is the open-proposal count, learned from the PROPOSED read
        let rail = q(div, 'ws-rail-locations');
        expect(rail.getAttribute('data-status')).toBe('count');
        expect(rail.querySelector('[data-count="1"]')).not.toBeNull();
        // the body is master/detail, defaulting to the first location without a route change
        expect(q(div, 'pb-loc-md')).not.toBeNull();
        expect(q(q(div, 'pb-loc-detail'), 'pb-loc-card').getAttribute('data-location-id')).toBe('L1');
    });

    it('a location render polls into bookActivity: the dock shows pb-loc-run, the body never does, the rail marks Locations running', async () => {
        let release;
        let gate = new Promise(r => { release = r; });
        wire({
            route: (mth, url) => mth === 'POST' && url === PB + '/location/L1/render' ? jsonResponse(202, { jobId: 'j1', runObjectId: 'r1', planned: [{ handle: 'location-tavern' }] }) : null,
            job: (id, nth) => nth === 1
                ? jsonResponse(200, { jobId: 'j1', status: 'running', phase: 'location-tavern', current: 0, total: 1, terminal: false })
                : gate.then(() => jsonResponse(200, { jobId: 'j1', status: 'completed', current: 1, total: 1, terminal: true, result: { runStatus: 'COMPLETED', executedNodeCount: 1, failedNodeCount: 0, nodes: [{ handle: 'location-tavern', status: 'DONE' }] } })),
            locations: () => [tavernWire()]
        });
        lib.__setLocationsStateForTest({ locations: [wf.normalizeLocation(tavernWire())] });
        let ws = workspace();
        let attrs = { bookObjectId: BOOK, section: 'locations', item: 'L1' };
        let div = render(m(ws.view, attrs));
        q(div, 'pb-loc-render').click();
        await waitFor(() => { let a = getActivity(BOOK, 'location-render:L1'); return a && a.stages && a.stages.length && a.stages[0].state === 'running'; });
        m.render(div, m(ws.view, attrs));
        expect(q(q(div, 'ws-body'), 'pb-loc-run'), 'no run panel in the section body').toBeNull();
        expect(q(div, 'ws-rail-locations').getAttribute('data-status')).toBe('running');
        expect(q(div, 'ws-dock')).not.toBeNull();
        q(div, 'ws-dock-toggle').click();
        m.render(div, m(ws.view, attrs));
        let run = q(q(div, 'ws-dock-panel'), 'pb-loc-run');
        expect(run.getAttribute('data-run-terminal')).toBe('0');
        expect(q(run, 'pb-loc-run-counter').textContent).toBe('0 of 1 · location-tavern');
        expect(q(div, 'pb-loc-render').textContent).toContain('Rendering…');
        release();
        await waitFor(() => getActivity(BOOK, 'location-render:L1').terminal);
        await waitFor(() => page.toast.mock.calls.length > 0);
        m.render(div, m(ws.view, attrs));
        run = q(q(div, 'ws-dock-panel'), 'pb-loc-run');
        expect(run.getAttribute('data-run-terminal')).toBe('1');
        expect(q(run, 'pb-loc-run-counter').textContent).toBe('Render complete: 1 rendered');
        expect(q(q(div, 'ws-body'), 'pb-loc-run')).toBeNull();
        q(run, 'pb-loc-run-dismiss').click();
        expect(activitiesFor(BOOK)).toEqual([]);
    });

    it('leaving the section (remove) stops watching and drops a still-running activity', async () => {
        wire({
            route: (mth, url) => mth === 'POST' && /\/render$/.test(url) ? jsonResponse(202, { jobId: 'j1', planned: [{ handle: 'location-tavern' }] }) : null,
            job: () => jsonResponse(200, { jobId: 'j1', status: 'running', current: 0, total: 1, terminal: false }),
            locations: () => [tavernWire()]
        });
        let l = wf.normalizeLocation(tavernWire());
        lib.__setLocationsStateForTest({ locations: [l] });
        await lib.startRender(l, null);
        await waitFor(() => !!getActivity(BOOK, 'location-render:L1'));
        expect(lib.__locationsStateForTest().runAborts.L1).toBeTruthy();
        lib.locationsSection.remove({});
        expect(lib.__locationsStateForTest().runAborts.L1).toBeUndefined();
        expect(getActivity(BOOK, 'location-render:L1')).toBeNull();
    });
});

describe('locationsSection body — master/detail', () => {
    function seed() {
        lib.__setLocationsStateForTest({
            locations: [wf.normalizeLocation(tavernWire()), wf.normalizeLocation(quayWire())],
            proposals: [wf.normalizeProposal ? wf.normalizeProposal(proposalWire()) : proposalWire()],
            openProposalCount: 1
        });
    }

    it('rows: the pinned Proposals pseudo-row first, then name / kind chip / lock badge / "Used by N scenes"', () => {
        seed();
        let div = render(lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK, item: null, select: () => {} }));
        let list = q(div, 'pb-loc-list');
        let buttons = Array.from(list.children);
        expect(buttons[0].getAttribute('data-testid')).toBe('pb-loc-proposals-row');
        expect(buttons[0].getAttribute('data-item-id')).toBe(lib.PROPOSALS_ITEM);
        expect(buttons[0].textContent).toContain('Proposals');
        expect(q(buttons[0], 'pb-loc-proposals-row-count').textContent).toBe('1');
        let rows = qa(div, 'pb-loc-row');
        expect(rows.map(r => r.getAttribute('data-item-id'))).toEqual(['L1', 'L2']);
        expect(rows[0].textContent).toContain('The Drowned Anchor');
        expect(q(rows[0], 'pb-loc-row-kind').textContent).toBe('mixed');
        expect(q(rows[0], 'pb-loc-row-locked')).toBeNull();
        expect(rows[0].textContent).toContain('Used by 2 scenes');
        expect(q(rows[1], 'pb-loc-row-kind').textContent).toBe('exterior');
        expect(q(rows[1], 'pb-loc-row-locked').textContent).toBe('Locked');
        expect(rows[1].textContent).toContain('Used by 0 scenes');
        // no item → the first location, chosen locally
        expect(rows[0].getAttribute('data-selected')).toBe('1');
        expect(q(div, 'pb-loc-card').getAttribute('data-location-id')).toBe('L1');
    });

    it('selecting a row goes through ctx.select; ctx.item = proposals shows the proposals panel with its status filter', () => {
        seed();
        let picked = [];
        let ctx = { kind: 'pb', bookObjectId: BOOK, item: null, select: id => picked.push(id) };
        let div = render(lib.locationsSection.body(ctx));
        qa(div, 'pb-loc-row')[1].click();
        q(div, 'pb-loc-proposals-row').click();
        expect(picked).toEqual(['L2', lib.PROPOSALS_ITEM]);
        lib.locationsSection.select({ item: lib.PROPOSALS_ITEM });
        m.render(div, lib.locationsSection.body(ctx));
        let detail = q(div, 'pb-loc-detail');
        expect(q(detail, 'pb-loc-proposals')).not.toBeNull();
        expect(q(detail, 'pb-loc-proposal-filter').value).toBe('PROPOSED');
        expect(q(detail, 'pb-loc-proposal-name').textContent).toBe('The Lighthouse');
        expect(q(detail, 'pb-loc-card')).toBeNull();
        expect(q(div, 'pb-loc-proposals-row').getAttribute('data-selected')).toBe('1');
    });

    it('a deleted selection re-seats to the location at the same index (ensureSelection), else the Proposals row', () => {
        seed();
        lib.locationsSection.select({ item: 'L2' });
        let div = render(lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK, select: () => {} }));
        expect(q(div, 'pb-loc-card').getAttribute('data-location-id')).toBe('L2');
        lib.__setLocationsStateForTest({ locations: [wf.normalizeLocation(tavernWire())] });
        m.render(div, lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK, select: () => {} }));
        expect(q(div, 'pb-loc-card').getAttribute('data-location-id')).toBe('L1');
        lib.__setLocationsStateForTest({ locations: [] });
        m.render(div, lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK, select: () => {} }));
        expect(q(div, 'pb-loc-empty')).not.toBeNull();
        expect(q(div, 'pb-loc-proposals')).not.toBeNull();
    });

    it('loading and error use stateBlock with the existing testids', () => {
        lib.__setLocationsStateForTest({ loading: true });
        let div = render(lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK }));
        expect(q(div, 'pb-loc-loading').getAttribute('data-state')).toBe('loading');
        lib.__setLocationsStateForTest({ loading: false, error: '(404) Book not found' });
        m.render(div, lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK }));
        expect(q(div, 'pb-loc-error').textContent).toBe('(404) Book not found');
        expect(q(div, 'pb-loc-error').getAttribute('data-state')).toBe('error');
    });

    it('"Used by scenes 3, 7" links each number to the scene\'s Page tab (PB and CB routes) through the guard', async () => {
        seed();
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        lib.locationsSection.init({ kind: 'cb', bookObjectId: BOOK, item: 'L1' });
        let div = render(lib.locationsSection.body({ kind: 'cb', bookObjectId: BOOK, item: 'L1', select: () => {} }));
        let used = q(div, 'pb-loc-used-by');
        expect(used.textContent).toBe('Used by scenes 3, 7');
        let links = qa(used, 'pb-loc-scene-link');
        expect(links.map(a => [a.textContent, a.getAttribute('data-scene-id'), a.getAttribute('href')])).toEqual([
            ['3', 's3', '#!/chap-book/edit/' + BOOK + '/pages/s3?tab=page'],
            ['7', 's7', '#!/chap-book/edit/' + BOOK + '/pages/s7?tab=page']
        ]);
        links[1].click();
        await waitFor(() => set.mock.calls.length === 1);
        expect(set).toHaveBeenCalledWith('/chap-book/edit/' + BOOK + '/pages/s7?tab=page');
        lib.locationsSection.select({ item: 'L1' });
        lib.__setLocationsStateForTest({ wsKind: 'pb' });
        m.render(div, lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK, item: 'L1', select: () => {} }));
        expect(q(div, 'pb-loc-scene-link').getAttribute('href')).toBe('#!/picture-book/' + BOOK + '/edit/scenes/s3?tab=page');
        // the legacy route (no workspace) keeps plain text
        lib.__setLocationsStateForTest({ wsKind: null });
        m.render(div, lib.locationsSection.body({ kind: 'pb', bookObjectId: BOOK, item: 'L1', select: () => {} }));
        expect(q(div, 'pb-loc-scene-link')).toBeNull();
        expect(q(div, 'pb-loc-used-by').textContent).toBe('Used by scenes 3, 7');
    });
});

describe('locationsSection inline edit + dirty guard', () => {
    it('an open edit registers pb-location-edit; typing makes it dirty (row + field marks); Cancel unregisters', () => {
        let l = wf.normalizeLocation(tavernWire());
        lib.__setLocationsStateForTest({ locations: [l] });
        expect(lib.locationsSection.dirtyKeys).toEqual(['pb-location-edit']);
        let ctx = { kind: 'pb', bookObjectId: BOOK, item: 'L1', select: () => {} };
        let div = render(lib.locationsSection.body(ctx));
        q(div, 'pb-loc-edit').click();
        m.render(div, lib.locationsSection.body(ctx));
        expect(q(div, 'pb-loc-editor')).not.toBeNull();
        expect(anyDirty(['pb-location-edit']), 'an untouched editor is not dirty').toBe(false);
        let ta = q(div, 'pb-loc-edit-description');
        ta.value = 'A low tavern by the quay, lamplit and loud';
        ta.dispatchEvent(new Event('input', { bubbles: true }));
        expect(anyDirty(['pb-location-edit'])).toBe(true);
        m.render(div, lib.locationsSection.body(ctx));
        expect(q(div, 'pb-loc-editor').getAttribute('data-dirty')).toBe('1');
        expect(q(div, 'pb-loc-editor').querySelector('[data-dirty="1"]')).not.toBeNull();
        expect(qa(div, 'pb-loc-row')[0].querySelector('[data-row-dirty]')).not.toBeNull();
        expect(q(qa(div, 'pb-loc-row')[0], 'pb-loc-row-unsaved')).not.toBeNull();
        q(div, 'pb-loc-cancel-edit').click();
        expect(anyDirty(['pb-location-edit'])).toBe(false);
        expect(lib.__locationsStateForTest().edits.L1).toBeUndefined();
    });

    it('the guard\'s Save PATCHes only the diff and proceeds; a refused PATCH keeps the draft and stays', async () => {
        let refuse = true;
        let calls = wire({
            route: (mth, url, body) => mth === 'PATCH' && url === PB + '/location/L1'
                ? (refuse ? jsonResponse(409, { error: 'Location "The Drowned Anchor" is locked' }) : jsonResponse(200, Object.assign(tavernWire(), body))) : null,
            locations: () => [tavernWire()]
        });
        let l = wf.normalizeLocation(tavernWire());
        lib.__setLocationsStateForTest({ locations: [l] });
        lib.beginEdit(l);
        lib.__locationsStateForTest().edits.L1.kind = 'interior';
        let ran = 0;
        let dlg = document.createElement('div');
        document.body.appendChild(dlg);

        let p = guardDirty(() => { ran++; }, ['pb-location-edit']);
        m.render(dlg, Dialog.loadDialogs());
        expect(q(dlg, 'dirty-guard').textContent).toContain('The Drowned Anchor');
        q(dlg, 'dirty-guard-save').click();
        expect(await p).toBe(false);
        expect(ran).toBe(0);
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Location "The Drowned Anchor" is locked');
        expect(lib.__locationsStateForTest().edits.L1.kind).toBe('interior');
        expect(anyDirty(['pb-location-edit'])).toBe(true);

        refuse = false;
        Dialog._reset();
        p = guardDirty(() => { ran++; }, ['pb-location-edit']);
        m.render(dlg, Dialog.loadDialogs());
        q(dlg, 'dirty-guard-save').click();
        expect(await p).toBe(true);
        expect(ran).toBe(1);
        let patches = calls.filter(c => c.method === 'PATCH');
        expect(patches.map(c => c.body)).toEqual([{ kind: 'interior' }, { kind: 'interior' }]);
        expect(lib.__locationsStateForTest().edits.L1).toBeUndefined();
        expect(anyDirty(['pb-location-edit'])).toBe(false);
    });

    it('the guard\'s Discard drops every draft without a write', async () => {
        let calls = wire({ locations: () => [tavernWire()] });
        let l = wf.normalizeLocation(tavernWire());
        lib.__setLocationsStateForTest({ locations: [l] });
        lib.beginEdit(l);
        lib.__locationsStateForTest().edits.L1.aliases = 'the anchor, anchor inn';
        let dlg = document.createElement('div');
        document.body.appendChild(dlg);
        let p = guardDirty(() => {}, ['pb-location-edit']);
        m.render(dlg, Dialog.loadDialogs());
        q(dlg, 'dirty-guard-discard').click();
        expect(await p).toBe(true);
        expect(lib.__locationsStateForTest().edits).toEqual({});
        expect(anyDirty()).toBe(false);
        expect(calls).toHaveLength(0);
    });
});

describe('locationsSection.status', () => {
    it('null until the PROPOSED read lands, then the open count; null for another book; a write under another filter forgets it', async () => {
        expect(lib.locationsSection.status({ bookObjectId: BOOK })).toBeNull();
        let open = [proposalWire(), proposalWire({ objectId: 'P2', proposedName: 'Quay' })];
        wire({
            locations: () => [tavernWire()], proposals: () => open,
            route: (mth, url) => mth === 'POST' && /\/lock$/.test(url) ? jsonResponse(200, tavernWire({ locked: true })) : null
        });
        await lib.loadLocations();
        await waitFor(() => !lib.__locationsStateForTest().proposalsLoading);
        expect(lib.locationsSection.status({ bookObjectId: BOOK })).toMatchObject({ kind: 'count', count: 2 });
        expect(lib.locationsSection.status({ bookObjectId: 'other-book' })).toBeNull();
        lib.setProposalFilter('ACCEPTED');
        await waitFor(() => !lib.__locationsStateForTest().proposalsLoading);
        expect(lib.locationsSection.status({ bookObjectId: BOOK }).count, 'browsing another filter keeps the known count').toBe(2);
        await lib.toggleLock(wf.normalizeLocation(tavernWire()));
        expect(lib.locationsSection.status({ bookObjectId: BOOK }), 'after a write the open count is unknown until re-read').toBeNull();
    });
});
