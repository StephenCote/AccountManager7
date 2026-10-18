// @vitest-environment jsdom
/**
 * PictureBook V3 locations library (P3-7, design §26/§27) — /picture-book/:id/locations, the ChapBook
 * page-location picker, the plan inspector's location rows and the canvas LOCATION accent.
 *
 * Why: every card shows server state only (one GET /{book}/locations, refetched after every write);
 * the wire shapes of the P3-7 routes (render query string, lock body, the scene-pointing PUT that
 * sends only non-null keys and clears with `{}`, accept {overwrite}) must not drift; a pinned LOCATION
 * node is a 409 the server words itself and is answered with "Unpin and render", never a silent unpin;
 * the artifact chain offers exactly artifactActions(a, node.pinned). No network, LLM or SD is touched:
 * fetch, am7client.get/dotPath/clearCache and page.toast are stubbed.
 *
 * pictureBookLocations.js no longer imports pictureBook.js (deriveStageList/runSummaryText live in
 * workflows/pictureBookWorkflow.js since P4-0), so either module may be imported first.
 */
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import * as pbf from '../features/pictureBook.js';
import * as lib from '../features/pictureBookLocations.js';
import * as wf from '../workflows/pictureBookWorkflow.js';
import { PbPlanInspector } from '../components/PbPlanInspector.js';

const BOOK = 'book-oid-1';
const PB = '/rest/olio/picture-book/' + BOOK;
const PINNED_MSG = 'Node location-tavern is pinned. Unpin it first - pinning means do not replace this artifact.';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function flushPromises() { return new Promise(r => setTimeout(r, 0)); }
async function waitFor(cond, ms) {
    let until = Date.now() + (ms || 2000);
    while (!cond()) {
        if (Date.now() > until) throw new Error('waitFor timed out');
        await new Promise(r => setTimeout(r, 2));
    }
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
    if (v == null || v === false) return '';
    if (typeof v === 'string' || typeof v === 'number') return String(v);
    if (Array.isArray(v)) return v.map(textOf).join('');
    if (v.tag === '#') return String(v.children);
    return textOf(v.children);
}
function seqOf(calls) { return calls.map(c => c.method + ' ' + c.url); }
function only(calls, re) { return calls.filter(c => re.test(c.method + ' ' + c.url)); }

/**
 * fetch router. o.route(method, url, body, calls) answers first; then GET /rest/job/{id} via o.job(id, nth);
 * then the library's two reads (GET …/locations from o.locations(), GET …/locations/proposed from o.proposals()).
 */
function wire(o) {
    o = o || {};
    let calls = [];
    let polls = {};
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u).replace(/^.*\/rest\//, '/rest/');
        let method = (init && init.method) || 'GET';
        let body = init && typeof init.body === 'string' ? JSON.parse(init.body) : null;
        calls.push({ url, method, body, rawBody: init ? init.body : undefined, headers: init ? init.headers : undefined });
        if (o.route) {
            let r = o.route(method, url, body, calls);
            if (r) return r;
        }
        let mj = /^\/rest\/job\/([^/?]+)$/.exec(url);
        if (mj && method === 'GET') {
            polls[mj[1]] = (polls[mj[1]] || 0) + 1;
            return o.job ? o.job(mj[1], polls[mj[1]]) : jsonResponse(404, { error: 'Job not found' });
        }
        if (method === 'GET' && url === PB + '/locations') return jsonResponse(200, { bookObjectId: BOOK, worldObjectId: 'W1', locations: o.locations ? o.locations() : [] });
        if (method === 'GET' && url.indexOf(PB + '/locations/proposed') === 0) return jsonResponse(200, { proposals: o.proposals ? o.proposals() : [] });
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}

// Wire fixtures: PbServiceFacade locationDto / variantDto / locationNodeDto shapes (NON_EMPTY, so absent keys are normal).
function artifact(extra) {
    return Object.assign({ objectId: 'a1', revision: 1, reviewStatus: 'GENERATED', selected: false, dataObjectId: 'd1', nodeObjectId: 'n1', backend: 'swarm', seed: 42 }, extra || {});
}
function node(extra) {
    return Object.assign({ objectId: 'n1', handle: 'location-tavern', storedStatus: 'DONE', pinned: false, chain: [artifact()] }, extra || {});
}
function tavernWire(extra) {
    return Object.assign({
        objectId: 'L1', name: 'The Drowned Anchor', description: 'A low tavern by the quay, lamplit', kind: 'mixed', aliases: ['the anchor'], locked: false,
        usedByScenes: [{ sceneObjectId: 's7', sceneIndex: 7 }, { sceneObjectId: 's3', sceneIndex: 3 }],
        node: node(),
        variants: [{ objectId: 'V1', name: 'The Drowned Anchor - dusk, rain', locationObjectId: 'L1', timeOfDay: 'dusk', weather: 'rain', usedByScenes: [] }]
    }, extra || {});
}
function tavern(extra) { return wf.normalizeLocation(tavernWire(extra)); }
function quayWire(extra) {
    return Object.assign({ objectId: 'L2', name: 'The Quay', kind: 'exterior', usedByScenes: [], variants: [] }, extra || {});
}
function proposalWire(extra) {
    return Object.assign({ objectId: 'P1', proposedName: 'The Drowned Anchor', kind: 'interior', description: 'a tavern', aliases: ['the anchor'], sceneObjectIds: ['s3', 's7'], proposalStatus: 'proposed' }, extra || {});
}
function doneJob(extra) {
    return Object.assign({ jobId: 'j1', kind: 'pb.render', status: 'completed', phase: 'location-tavern', current: 1, total: 1, terminal: true,
        result: { runObjectId: 'r1', runStatus: 'COMPLETED', executedNodeCount: 1, failedNodeCount: 0, skippedNodeCount: 0, nodes: [{ handle: 'location-tavern', status: 'DONE', artifactObjectId: 'a2' }] } }, extra || {});
}
function runningJob() {
    return { jobId: 'j1', kind: 'pb.render', status: 'running', phase: 'location-tavern', current: 0, total: 1, terminal: false };
}

function freshState(extra) {
    return Object.assign({
        bookObjectId: BOOK, locations: [], worldObjectId: null, loading: false, error: null, proposals: [], proposalFilter: 'PROPOSED',
        proposalsLoading: false, proposalsError: null, proposing: false, busy: {}, edits: {}, runs: {}, pinnedRefusals: {}, overwrite: {},
        imageUrls: {}, pollIntervalMs: 1, loadSeq: 0
    }, extra || {});
}

function renderDialogsInto() {
    let container = document.createElement('div');
    document.body.appendChild(container);
    m.render(container, Dialog.loadDialogs());
    return container;
}
function rerender(container) { m.render(container, Dialog.loadDialogs()); return container; }
function setInput(container, testid, value, ev) {
    let el = container.querySelector('[data-testid="' + testid + '"]');
    expect(el, testid + ' not rendered').toBeTruthy();
    el.value = value;
    el.dispatchEvent(new Event(ev || 'input', { bubbles: true }));
    return el;
}
function clickTestId(container, testid) {
    let el = container.querySelector('[data-testid="' + testid + '"]');
    expect(el, testid + ' not rendered').toBeTruthy();
    expect(el.disabled, testid + ' is disabled').toBe(false);
    el.click();
    return el;
}

let origFetch;

beforeEach(() => {
    origFetch = global.fetch;
    Dialog._reset();
    document.body.innerHTML = '';
    lib.__setLocationsStateForTest(freshState());
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(am7client, 'get').mockImplementation((type, id, cb) => { cb(null); });
});

afterEach(() => {
    lib.__setLocationsStateForTest(freshState({ bookObjectId: null }));
    Dialog._reset();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── workflow helpers: wire shapes ───────────────────────────────

describe('pictureBookWorkflow location helpers — URL / method / body', () => {
    it('listLocations GETs /{book}/locations and normalizes every optional key', async () => {
        let calls = wire({ locations: () => [tavernWire({ aliases: undefined, locked: undefined, variants: undefined, node: undefined, usedByScenes: undefined, kind: undefined })] });
        let r = await wf.listLocations(BOOK);
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/locations']);
        expect(r.worldObjectId).toBe('W1');
        expect(r.locations).toHaveLength(1);
        expect(r.locations[0]).toMatchObject({ objectId: 'L1', name: 'The Drowned Anchor', aliases: [], locked: false, variants: [], node: null, usedByScenes: [], kind: null, approvedRenderObjectId: null });
    });
    it('normalizeLocation keeps node/variant/artifact shapes: pinned bool, chain normalized, usedBy entries nulled, variant deltas nulled', () => {
        let l = tavern({ node: node({ pinned: 1, chain: [{ objectId: 'a9' }] }), variants: [{ objectId: 'V9' }] });
        expect(l.node.pinned).toBe(true);
        expect(l.node.chain[0]).toMatchObject({ objectId: 'a9', reviewStatus: null, selected: false, dataObjectId: null, nodeObjectId: null });
        expect(l.usedByScenes[0]).toMatchObject({ sceneObjectId: 's7', sceneIndex: 7, variantObjectId: null });
        expect(l.variants[0]).toMatchObject({ objectId: 'V9', name: null, timeOfDay: null, weather: null, season: null, viewpoint: null, extra: null, usedByScenes: [], node: null, approvedRenderObjectId: null });
    });
    it('getLocation GETs /{book}/location/{id}', async () => {
        let calls = wire({ route: (mth, url) => mth === 'GET' && url === PB + '/location/L1' ? jsonResponse(200, tavernWire()) : null });
        let r = await wf.getLocation(BOOK, 'L1');
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/location/L1']);
        expect(r.name).toBe('The Drowned Anchor');
    });
    it('createLocation POSTs /{book}/locations with the body as given (JSON content type)', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'POST' && url === PB + '/locations' ? jsonResponse(201, Object.assign({ objectId: 'L3' }, body)) : null });
        let r = await wf.createLocation(BOOK, { name: 'The Quay', kind: 'exterior', aliases: ['quay'] });
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/locations', body: { name: 'The Quay', kind: 'exterior', aliases: ['quay'] } });
        expect(calls[0].headers['Content-Type']).toBe('application/json');
        expect(r).toMatchObject({ objectId: 'L3', name: 'The Quay', locked: false, variants: [] });
    });
    it('updateLocation PATCHes /{book}/location/{id} with only the keys given', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'PATCH' && url === PB + '/location/L1' ? jsonResponse(200, Object.assign(tavernWire(), body)) : null });
        let r = await wf.updateLocation(BOOK, 'L1', { description: 'new' });
        expect(calls[0]).toMatchObject({ method: 'PATCH', url: PB + '/location/L1', body: { description: 'new' } });
        expect(Object.keys(calls[0].body)).toEqual(['description']);
        expect(r.description).toBe('new');
    });
    it('deleteLocation DELETEs /{book}/location/{id} and returns the step report', async () => {
        let calls = wire({ route: (mth, url) => mth === 'DELETE' && url === PB + '/location/L1' ? jsonResponse(200, { deleted: true, locationObjectId: 'L1', steps: ['node'] }) : null });
        let r = await wf.deleteLocation(BOOK, 'L1');
        expect(seqOf(calls)).toEqual(['DELETE ' + PB + '/location/L1']);
        expect(r.deleted).toBe(true);
    });
    it('createLocationVariant POSTs /{book}/location/{id}/variants', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'POST' && url === PB + '/location/L1/variants' ? jsonResponse(201, Object.assign({ objectId: 'V2', name: 'The Drowned Anchor - dawn' }, body)) : null });
        let r = await wf.createLocationVariant(BOOK, 'L1', { timeOfDay: 'dawn' });
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/location/L1/variants', body: { timeOfDay: 'dawn' } });
        expect(r).toMatchObject({ objectId: 'V2', timeOfDay: 'dawn', weather: null, node: null });
    });
    it('lockLocation POSTs /{book}/location/{id}/lock with a boolean {locked}', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'POST' && url === PB + '/location/L1/lock' ? jsonResponse(200, tavernWire({ locked: body.locked })) : null });
        expect((await wf.lockLocation(BOOK, 'L1', true)).locked).toBe(true);
        expect((await wf.lockLocation(BOOK, 'L1', 0)).locked).toBe(false);
        expect(calls.map(c => c.body)).toEqual([{ locked: true }, { locked: false }]);
    });
    it('mergeLocations POSTs /{book}/locations/merge {duplicateObjectId, survivorObjectId}', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/merge' ? jsonResponse(200, { merged: true, survivor: tavernWire() }) : null });
        let r = await wf.mergeLocations(BOOK, 'L2', 'L1');
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/locations/merge', body: { duplicateObjectId: 'L2', survivorObjectId: 'L1' } });
        expect(r.survivor.name).toBe('The Drowned Anchor');
        expect(r.merged).toBe(true);
    });
    it('renderLocation POSTs /{book}/location/{id}/render with ?variant= / &force=true only when given, no body', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && /\/location\/L1\/render/.test(url) ? jsonResponse(202, { jobId: 'j1', runObjectId: 'r1', nodeObjectId: 'n1', variantObjectId: null }) : null });
        let r = await wf.renderLocation(BOOK, 'L1');
        await wf.renderLocation(BOOK, 'L1', 'V 1');
        await wf.renderLocation(BOOK, 'L1', 'V1', true);
        await wf.renderLocation(BOOK, 'L1', null, true);
        expect(seqOf(calls)).toEqual([
            'POST ' + PB + '/location/L1/render',
            'POST ' + PB + '/location/L1/render?variant=V%201',
            'POST ' + PB + '/location/L1/render?variant=V1&force=true',
            'POST ' + PB + '/location/L1/render?force=true'
        ]);
        expect(calls[0].rawBody).toBeUndefined();
        expect(r).toMatchObject({ jobId: 'j1', runObjectId: 'r1', nodeObjectId: 'n1', variantObjectId: null, planned: [] });
    });
    it('isPinnedNodeRefusal: only a 409 whose text says "is pinned" (incl. the real wfFetch error)', async () => {
        wire({ route: () => jsonResponse(409, { error: PINNED_MSG }) });
        let err = await wf.renderLocation(BOOK, 'L1').catch(e => e);
        expect(err.status).toBe(409);
        expect(err.message).toBe('(409) ' + PINNED_MSG);
        expect(wf.isPinnedNodeRefusal(err)).toBe(true);
        expect(wf.isPinnedNodeRefusal({ status: 409, message: 'Location is locked' })).toBe(false);
        expect(wf.isPinnedNodeRefusal({ status: 400, message: PINNED_MSG })).toBe(false);
        expect(wf.isPinnedNodeRefusal(null)).toBe(false);
        expect(wf.isPinnedNodeRefusal({ status: 409, message: 'Nothing to render (0 reused, 1 pinned)' })).toBe(false);
    });
    it('proposeLocations POSTs /{book}/locations/propose with {} or {chatConfigName}; pass counts default to 0', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/propose' ? jsonResponse(200, { proposed: 2, scenesConsidered: 5, proposals: [proposalWire()] }) : null });
        let r = await wf.proposeLocations(BOOK);
        await wf.proposeLocations(BOOK, 'generalChat');
        expect(calls.map(c => c.body)).toEqual([{}, { chatConfigName: 'generalChat' }]);
        expect(r).toMatchObject({ proposed: 2, scenesConsidered: 5, scenesSkipped: 0, hardFailure: false, warnings: [] });
        expect(r.proposals[0].proposalStatus).toBe('PROPOSED');
    });
    it('listLocationProposals GETs /{book}/locations/proposed with a lowercase ?status= only when given', async () => {
        let calls = wire({ proposals: () => [proposalWire({ proposalStatus: 'accepted', locationObjectId: 'L1' })] });
        let r = await wf.listLocationProposals(BOOK, 'ACCEPTED');
        await wf.listLocationProposals(BOOK);
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/locations/proposed?status=accepted', 'GET ' + PB + '/locations/proposed']);
        expect(r.proposals[0]).toMatchObject({ proposalStatus: 'ACCEPTED', locationObjectId: 'L1', sameAs: null, reason: null });
    });
    it('normalizeProposal: `name` is the proposedName fallback; aliases/sceneObjectIds default to []', () => {
        expect(wf.normalizeProposal({ objectId: 'P2', name: 'The Quay' })).toMatchObject({ proposedName: 'The Quay', aliases: [], sceneObjectIds: [], proposalStatus: null });
    });
    it('acceptLocationProposal POSTs …/proposed/{p}/accept with a boolean {overwrite}; counters default to 0', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/proposed/P1/accept' ? jsonResponse(200, { locationObjectId: 'L1', locationCreated: true, scenesRepointed: 2 }) : null });
        let r = await wf.acceptLocationProposal(BOOK, 'P1');
        await wf.acceptLocationProposal(BOOK, 'P1', true);
        expect(calls.map(c => c.body)).toEqual([{ overwrite: false }, { overwrite: true }]);
        expect(r).toMatchObject({ locationObjectId: 'L1', locationCreated: true, scenesRepointed: 2, scenesSkipped: 0, aliasesFolded: 0, warnings: [], proposal: null });
    });
    it('rejectLocationProposal POSTs …/proposed/{p}/reject with {reason} only when non-empty', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/proposed/P1/reject' ? jsonResponse(200, proposalWire({ proposalStatus: 'rejected', reason: 'dup' })) : null });
        let r = await wf.rejectLocationProposal(BOOK, 'P1', 'dup');
        await wf.rejectLocationProposal(BOOK, 'P1', '');
        expect(calls.map(c => c.body)).toEqual([{ reason: 'dup' }, {}]);
        expect(r.proposalStatus).toBe('REJECTED');
    });
    it('setSceneLocation PUTs /{book}/scene/{s}/location with only the non-null keys; {} clears', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'PUT' && url === PB + '/scene/s1/location'
            ? jsonResponse(200, { sceneObjectId: 's1', locationObjectId: body.locationObjectId, locationName: body.locationObjectId ? 'The Drowned Anchor' : null, variantObjectId: body.variantObjectId, variantName: body.variantObjectId ? 'dusk' : null, updated: true, nodeStale: true, pinned: false, nodeStatus: 'STALE' }) : null });
        let r = await wf.setSceneLocation(BOOK, 's1', 'L1', 'V1');
        await wf.setSceneLocation(BOOK, 's1', 'L1', null);
        let cleared = await wf.setSceneLocation(BOOK, 's1', null, null);
        expect(seqOf(calls)).toEqual(['PUT ' + PB + '/scene/s1/location', 'PUT ' + PB + '/scene/s1/location', 'PUT ' + PB + '/scene/s1/location']);
        expect(calls.map(c => c.body)).toEqual([{ locationObjectId: 'L1', variantObjectId: 'V1' }, { locationObjectId: 'L1' }, {}]);
        expect(r).toMatchObject({ sceneObjectId: 's1', locationObjectId: 'L1', locationName: 'The Drowned Anchor', variantObjectId: 'V1', variantName: 'dusk', updated: true, nodeStale: true, pinned: false, nodeStatus: 'STALE' });
        expect(cleared).toMatchObject({ locationObjectId: null, locationName: null, variantObjectId: null, variantName: null });
        expect(wf.normalizeLocationPass(null)).toMatchObject({ proposed: 0, hardFailure: false, proposals: [], warnings: [] });
    });
});

// ─────────────────────────────── pure derivations ───────────────────────────────

describe('pure derivations', () => {
    it('usedByText: sorted de-duplicated indices; count-only when indices are missing', () => {
        expect(lib.usedByText([])).toBe('Not used by any scene');
        expect(lib.usedByText(null)).toBe('Not used by any scene');
        expect(lib.usedByText([{ sceneIndex: 3 }])).toBe('Used by scene 3');
        expect(lib.usedByText([{ sceneIndex: 7 }, { sceneIndex: 3 }, { sceneIndex: 7, variantObjectId: 'V1' }])).toBe('Used by scenes 3, 7');
        expect(lib.usedByText([{ sceneObjectId: 'a' }, { sceneObjectId: 'b' }])).toBe('Used by 2 scenes');
        expect(lib.usedByText([{ sceneObjectId: 'a' }])).toBe('Used by 1 scene');
    });
    it('parseAliases: comma / newline split, trimmed, case-insensitively de-duplicated', () => {
        expect(lib.parseAliases(' the anchor, Anchor Inn\nthe anchor ,, THE ANCHOR ')).toEqual(['the anchor', 'Anchor Inn']);
        expect(lib.parseAliases('')).toEqual([]);
        expect(lib.parseAliases(null)).toEqual([]);
    });
    it('editPatch: only description / kind / aliases that differ; kind lowercased; empty kind never sent', () => {
        let l = tavern();
        expect(lib.editPatch(l, { description: l.description, kind: 'mixed', aliases: 'the anchor' })).toEqual({});
        expect(lib.editPatch(l, { description: 'x', kind: 'Interior', aliases: 'the anchor, quay bar' })).toEqual({ description: 'x', kind: 'interior', aliases: ['the anchor', 'quay bar'] });
        expect(lib.editPatch(l, { description: l.description, kind: '', aliases: '' })).toEqual({ aliases: [] });
        expect(lib.editPatch(null, {})).toEqual({});
    });
    it('variantDeltaText: non-empty deltas joined with " · ", else the name', () => {
        expect(lib.variantDeltaText({ timeOfDay: 'dusk', weather: 'rain', season: null })).toBe('dusk · rain');
        expect(lib.variantDeltaText({ name: 'Named', timeOfDay: null })).toBe('Named');
        expect(lib.variantDeltaText(null)).toBe('');
    });
    it('matchLocations: name or alias, case-insensitive, excluding one objectId', () => {
        let list = [tavern(), wf.normalizeLocation(quayWire())];
        expect(lib.matchLocations(list, 'THE ANCHOR').map(l => l.objectId)).toEqual(['L1']);
        expect(lib.matchLocations(list, 'the quay').map(l => l.objectId)).toEqual(['L2']);
        expect(lib.matchLocations(list, 'the quay', 'L2')).toEqual([]);
        expect(lib.matchLocations(list, '')).toEqual([]);
    });
    it('subjectKey: the variant objectId when given, else the location', () => {
        expect(lib.subjectKey({ objectId: 'L1' }, { objectId: 'V1' })).toBe('V1');
        expect(lib.subjectKey({ objectId: 'L1' }, null)).toBe('L1');
        expect(lib.subjectKey(null, null)).toBeNull();
    });
});

// ─────────────────────────────── load ───────────────────────────────

describe('loadLocations', () => {
    it('reads GET /{book}/locations then GET …/proposed?status=proposed; thumbs resolved through am7client.get', async () => {
        let calls = wire({ locations: () => [tavernWire()], proposals: () => [proposalWire()] });
        await lib.loadLocations();
        await flushPromises();
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/locations', 'GET ' + PB + '/locations/proposed?status=proposed']);
        let st = lib.__locationsStateForTest();
        expect(st.locations.map(l => l.objectId)).toEqual(['L1']);
        expect(st.worldObjectId).toBe('W1');
        expect(st.proposals.map(p => p.objectId)).toEqual(['P1']);
        expect(st.loading).toBe(false);
        expect(am7client.get).toHaveBeenCalledWith('data.data', 'd1', expect.any(Function));
        expect(st.imageUrls).toEqual({ d1: null });
    });
    it('a failed read shows the server message and keeps nothing stale', async () => {
        wire({ route: (mth, url) => url === PB + '/locations' ? jsonResponse(404, { error: 'Book not found: ' + BOOK }) : null });
        lib.__setLocationsStateForTest({ locations: [tavern()] });
        await lib.loadLocations();
        let st = lib.__locationsStateForTest();
        expect(st.error).toBe('(404) Book not found: ' + BOOK);
        expect(st.locations).toEqual([]);
        let body = lib.renderLocationsBody();
        expect(textOf(findByTestId(body, 'pb-loc-error'))).toContain('Book not found');
    });
    it('empty library renders the empty hint and the proposals panel', () => {
        let body = lib.renderLocationsBody();
        expect(findByTestId(body, 'pb-loc-empty')).toBeTruthy();
        expect(findByTestId(body, 'pb-loc-proposals')).toBeTruthy();
        expect(findByTestId(body, 'pb-loc-proposals-empty')).toBeTruthy();
        expect(textOf(findByTestId(lib.renderLocationsHeader(), 'pb-loc-count'))).toBe('0 locations');
    });
});

// ─────────────────────────────── cards ───────────────────────────────

describe('renderLocationCard', () => {
    it('shows name, kind, description, aliases, "Used by scenes 3, 7", the chain and the variant', () => {
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        let v = lib.renderLocationCard(l);
        expect(v.attrs).toMatchObject({ 'data-testid': 'pb-loc-card', 'data-location-id': 'L1', 'data-location-name': 'The Drowned Anchor', 'data-locked': '0' });
        expect(textOf(findByTestId(v, 'pb-loc-name'))).toBe('The Drowned Anchor');
        expect(textOf(findByTestId(v, 'pb-loc-kind'))).toBe('mixed');
        expect(findByTestId(v, 'pb-loc-locked')).toBeNull();
        expect(textOf(findByTestId(v, 'pb-loc-description'))).toBe('A low tavern by the quay, lamplit');
        expect(findAllByTestId(v, 'pb-loc-alias').map(textOf)).toEqual(['the anchor']);
        expect(textOf(findByTestId(v, 'pb-loc-used-by'))).toBe('Used by scenes 3, 7');
        let chain = findByTestId(v, 'pb-loc-chain');
        expect(chain.attrs).toMatchObject({ 'data-node-id': 'n1', 'data-node-pinned': '0' });
        expect(findAllByTestId(v, 'pb-loc-not-rendered')).toHaveLength(1);
        let variant = findByTestId(v, 'pb-loc-variant');
        expect(variant.attrs['data-variant-id']).toBe('V1');
        expect(textOf(findByTestId(variant, 'pb-loc-variant-name'))).toBe('The Drowned Anchor - dusk, rain');
        expect(textOf(findByTestId(variant, 'pb-loc-variant-used-by'))).toBe('Not used by any scene');
        expect(findByTestId(variant, 'pb-loc-not-rendered')).toBeTruthy();
        expect(findByTestId(variant, 'pb-loc-variant-render').attrs.disabled).toBe(false);
        expect(findByTestId(v, 'pb-loc-merge').attrs.disabled).toBe(true);
    });
    it('an unrendered location says "Not rendered yet"; a node with no artifacts says "No artifact yet"', () => {
        expect(findByTestId(lib.renderLocationCard(tavern({ node: undefined })), 'pb-loc-not-rendered')).toBeTruthy();
        expect(findByTestId(lib.renderLocationCard(tavern({ node: node({ chain: [] }) })), 'pb-loc-chain-empty')).toBeTruthy();
    });
    it('chain is newest first and each artifact offers exactly artifactActions(a, node.pinned)', () => {
        let l = tavern({ node: node({ chain: [
            artifact({ objectId: 'a1', revision: 1, reviewStatus: 'ARCHIVED' }),
            artifact({ objectId: 'a2', revision: 2, reviewStatus: 'REJECTED' }),
            artifact({ objectId: 'a3', revision: 3, reviewStatus: 'APPROVED', approvedBy: 'AUTO', selected: true }),
            artifact({ objectId: 'a4', revision: 4, reviewStatus: 'GENERATED' })
        ] }) });
        let arts = findAllByTestId(lib.renderLocationCard(l), 'pb-loc-artifact');
        expect(arts.map(a => a.attrs['data-artifact-id'])).toEqual(['a4', 'a3', 'a2', 'a1']);
        expect(arts.map(a => a.attrs['data-review-status'])).toEqual(['GENERATED', 'APPROVED', 'REJECTED', 'ARCHIVED']);
        let offered = arts.map(a => ['pb-loc-use', 'pb-loc-approve', 'pb-loc-reject'].filter(t => findByTestId(a, t)));
        expect(offered).toEqual([
            ['pb-loc-use', 'pb-loc-approve', 'pb-loc-reject'],
            ['pb-loc-reject'],
            ['pb-loc-use', 'pb-loc-approve'],
            []
        ]);
        expect(arts[1].attrs['data-selected']).toBe('1');
        expect(findByTestId(arts[1], 'pb-loc-artifact-current')).toBeTruthy();
        expect(textOf(arts[1])).toContain('auto-approved');
    });
    it('a pinned node shows the pinned chip and never offers "Use this"', () => {
        let v = lib.renderLocationCard(tavern({ node: node({ pinned: true }) }));
        expect(findByTestId(v, 'pb-loc-chain').attrs['data-node-pinned']).toBe('1');
        expect(findByTestId(v, 'pb-loc-node-pinned')).toBeTruthy();
        expect(findByTestId(v, 'pb-loc-use')).toBeNull();
        expect(findByTestId(v, 'pb-loc-approve')).toBeTruthy();
    });
    it('locked: chip shown, render / edit / add-variant / merge / delete / chain verdicts disabled, Lock reads Unlock', () => {
        let l = tavern({ locked: true });
        lib.__setLocationsStateForTest({ locations: [l, wf.normalizeLocation(quayWire())] });
        let v = lib.renderLocationCard(l);
        expect(v.attrs['data-locked']).toBe('1');
        expect(textOf(findByTestId(v, 'pb-loc-locked'))).toBe('Locked');
        ['pb-loc-render', 'pb-loc-edit', 'pb-loc-add-variant', 'pb-loc-merge', 'pb-loc-delete', 'pb-loc-variant-render', 'pb-loc-use', 'pb-loc-approve', 'pb-loc-reject'].forEach(t => {
            expect(findByTestId(v, t).attrs.disabled, t).toBe(true);
        });
        let lock = findByTestId(v, 'pb-loc-lock');
        expect(lock.attrs.disabled).toBe(false);
        expect(textOf(lock)).toContain('Unlock');
        let un = lib.renderLocationCard(tavern());
        expect(textOf(findByTestId(un, 'pb-loc-lock'))).toContain('Lock');
        expect(findByTestId(un, 'pb-loc-merge').attrs.disabled).toBe(false);
    });
    it('artifact image is shown once resolveImageUrl answers', () => {
        lib.__setLocationsStateForTest({ imageUrls: { d1: '/AccountManagerService7/media/Public/data.data/x/a.png' } });
        let v = lib.renderLocationCard(tavern());
        let art = findByTestId(v, 'pb-loc-artifact');
        expect(JSON.stringify(art)).toContain('"src":"/AccountManagerService7/media/Public/data.data/x/a.png"');
    });
});

// ─────────────────────────────── lock / edit / chain verdicts (write → toast → refetch) ───────────────────────────────

describe('card writes', () => {
    it('Lock button → POST …/lock {locked:true}; toast "Locked …"; refetch', async () => {
        let locked = false;
        let calls = wire({
            route: (mth, url, body) => mth === 'POST' && url === PB + '/location/L1/lock' ? (locked = body.locked, jsonResponse(200, tavernWire({ locked }))) : null,
            locations: () => [tavernWire({ locked })]
        });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        findByTestId(lib.renderLocationCard(l), 'pb-loc-lock').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/locations$/).length === 1);
        await flushPromises();
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/location/L1/lock', body: { locked: true } });
        expect(page.toast).toHaveBeenCalledWith('success', 'Locked The Drowned Anchor');
        expect(lib.__locationsStateForTest().locations[0].locked).toBe(true);
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.pb.scene', true);
        page.toast.mockClear();
        await lib.toggleLock(lib.__locationsStateForTest().locations[0]);
        expect(calls.filter(c => c.method === 'POST').map(c => c.body)).toEqual([{ locked: true }, { locked: false }]);
        expect(page.toast).toHaveBeenCalledWith('success', 'Unlocked The Drowned Anchor');
    });
    it('a refused write toasts the server message verbatim and still refetches', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && /\/lock$/.test(url) ? jsonResponse(409, { error: 'Location "The Drowned Anchor" is locked' }) : null, locations: () => [tavernWire()] });
        await lib.toggleLock(tavern());
        expect(page.toast).toHaveBeenCalledWith('error', '(409) Location "The Drowned Anchor" is locked');
        expect(seqOf(calls)).toEqual(['POST ' + PB + '/location/L1/lock', 'GET ' + PB + '/locations', 'GET ' + PB + '/locations/proposed?status=proposed']);
    });
    it('edit: beginEdit seeds the draft; an unchanged Save sends no PATCH; a changed Save PATCHes only the diff', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'PATCH' && url === PB + '/location/L1' ? jsonResponse(200, Object.assign(tavernWire(), body)) : null, locations: () => [tavernWire()] });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        findByTestId(lib.renderLocationCard(l), 'pb-loc-edit').attrs.onclick();
        let st = lib.__locationsStateForTest();
        expect(st.edits.L1).toEqual({ description: 'A low tavern by the quay, lamplit', kind: 'mixed', aliases: 'the anchor' });
        let v = lib.renderLocationCard(l);
        expect(findByTestId(v, 'pb-loc-editor')).toBeTruthy();
        expect(findByTestId(v, 'pb-loc-edit-description').attrs.value).toBe('A low tavern by the quay, lamplit');
        expect(findByTestId(v, 'pb-loc-edit-aliases').attrs.value).toBe('the anchor');
        await lib.saveEdit(l);
        expect(calls).toHaveLength(0);
        expect(lib.__locationsStateForTest().edits.L1).toBeUndefined();

        lib.beginEdit(l);
        lib.__locationsStateForTest().edits.L1.kind = 'interior';
        lib.__locationsStateForTest().edits.L1.aliases = 'the anchor, anchor inn';
        await lib.saveEdit(l);
        expect(calls[0]).toMatchObject({ method: 'PATCH', url: PB + '/location/L1', body: { kind: 'interior', aliases: ['the anchor', 'anchor inn'] } });
        expect(Object.keys(calls[0].body).sort()).toEqual(['aliases', 'kind']);
        expect(page.toast).toHaveBeenCalledWith('success', 'Saved The Drowned Anchor');
        expect(lib.__locationsStateForTest().edits.L1).toBeUndefined();
        expect(seqOf(calls).slice(1)).toEqual(['GET ' + PB + '/locations', 'GET ' + PB + '/locations/proposed?status=proposed']);
        findByTestId(lib.renderLocationCard(l), 'pb-loc-edit').attrs.onclick();
        findByTestId(lib.renderLocationCard(l), 'pb-loc-cancel-edit').attrs.onclick();
        expect(lib.__locationsStateForTest().edits.L1).toBeUndefined();
    });
    it('Use this / Approve / Reject → PUT …/artifact/{a}/select|approve|reject then refetch', async () => {
        let calls = wire({ route: (mth, url) => mth === 'PUT' && /\/artifact\/a1\/(select|approve|reject)$/.test(url) ? jsonResponse(200, artifact({ selected: true })) : null, locations: () => [tavernWire()] });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        let art = findByTestId(lib.renderLocationCard(l), 'pb-loc-artifact');
        findByTestId(art, 'pb-loc-use').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        findByTestId(art, 'pb-loc-approve').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 2);
        findByTestId(art, 'pb-loc-reject').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 3);
        expect(only(calls, /PUT/).map(c => c.url)).toEqual([PB + '/artifact/a1/select', PB + '/artifact/a1/approve', PB + '/artifact/a1/reject']);
        expect(page.toast.mock.calls.map(c => c[1])).toEqual(['Revision 1 is now current', 'Approved', 'Rejected']);
    });
});

// ─────────────────────────────── render / run watching ───────────────────────────────

describe('render a location', () => {
    it('Render → POST …/location/L1/render (202) → poll /rest/job/j1 → stages → toast runSummaryText → refetch', async () => {
        let release;
        let gate = new Promise(r => { release = r; });
        let calls = wire({
            route: (mth, url) => mth === 'POST' && url === PB + '/location/L1/render' ? jsonResponse(202, { locationObjectId: 'L1', nodeObjectId: 'n1', jobId: 'j1', runObjectId: 'r1', runStatus: 'PENDING', planned: [{ handle: 'location-tavern', nodeObjectId: 'n1' }] }) : null,
            job: (id, nth) => nth === 1 ? jsonResponse(200, runningJob()) : gate.then(() => jsonResponse(200, doneJob())),
            locations: () => [tavernWire()]
        });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        let btn = findByTestId(lib.renderLocationCard(l), 'pb-loc-render');
        expect(btn.attrs.disabled).toBe(false);
        btn.attrs.onclick();
        await waitFor(() => { let r = lib.__locationsStateForTest().runs.L1; return r && r.job; });
        let mid = lib.renderLocationCard(l);
        let midRun = findByTestId(mid, 'pb-loc-run');
        expect(midRun.attrs['data-run-terminal']).toBe('0');
        expect(textOf(findByTestId(midRun, 'pb-loc-run-counter'))).toBe('0 of 1 · location-tavern');
        expect(findAllByTestId(midRun, 'pb-loc-run-stage').map(s => s.attrs['data-stage-state'])).toEqual(['running']);
        expect(findByTestId(midRun, 'pb-loc-run-dismiss')).toBeNull();
        expect(findByTestId(mid, 'pb-loc-render').attrs.disabled).toBe(true);
        expect(textOf(findByTestId(mid, 'pb-loc-render'))).toContain('Rendering…');
        release();
        await waitFor(() => page.toast.mock.calls.length > 0);
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(page.toast).toHaveBeenCalledWith('success', 'Render complete: 1 rendered');
        expect(seqOf(calls).slice(0, 3)).toEqual(['POST ' + PB + '/location/L1/render', 'GET /rest/job/j1', 'GET /rest/job/j1']);
        expect(seqOf(calls).slice(3)).toEqual(['GET ' + PB + '/locations', 'GET ' + PB + '/locations/proposed?status=proposed']);
        let done = lib.renderLocationCard(lib.__locationsStateForTest().locations[0]);
        let run = findByTestId(done, 'pb-loc-run');
        expect(run.attrs['data-run-terminal']).toBe('1');
        expect(textOf(run)).toContain('Render The Drowned Anchor');
        expect(textOf(findByTestId(run, 'pb-loc-run-counter'))).toBe('Render complete: 1 rendered');
        expect(findAllByTestId(run, 'pb-loc-run-stage').map(s => [textOf(s), s.attrs['data-stage-state']])).toEqual([[expect.stringContaining('location-tavern'), 'done']]);
        expect(findByTestId(done, 'pb-loc-render').attrs.disabled).toBe(false);
        findByTestId(run, 'pb-loc-run-dismiss').attrs.onclick();
        expect(findByTestId(lib.renderLocationCard(l), 'pb-loc-run')).toBeNull();
    });
    it('a variant render carries ?variant=V1, is keyed by the variant and labelled "<location> / <variant>"', async () => {
        let calls = wire({
            route: (mth, url) => mth === 'POST' && /\/location\/L1\/render/.test(url) ? jsonResponse(202, { jobId: 'j1', runObjectId: 'r1', nodeObjectId: 'n2', variantObjectId: 'V1', planned: [{ handle: 'location-tavern-dusk' }] }) : null,
            job: () => jsonResponse(200, doneJob({ result: { runStatus: 'COMPLETED', executedNodeCount: 1, nodes: [{ handle: 'location-tavern-dusk', status: 'DONE' }] } })),
            locations: () => [tavernWire()]
        });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        findByTestId(findByTestId(lib.renderLocationCard(l), 'pb-loc-variant'), 'pb-loc-variant-render').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(calls[0].url).toBe(PB + '/location/L1/render?variant=V1');
        let run = findByTestId(findByTestId(lib.renderLocationCard(l), 'pb-loc-variant'), 'pb-loc-run');
        expect(run).toBeTruthy();
        expect(textOf(run)).toContain('Render The Drowned Anchor / The Drowned Anchor - dusk, rain');
        expect(lib.__locationsStateForTest().runs.V1).toBeTruthy();
        expect(lib.__locationsStateForTest().runs.L1).toBeUndefined();
    });
    it('a failed job toasts a warning with runSummaryText and shows the failed stage', async () => {
        wire({
            route: (mth, url) => mth === 'POST' && url === PB + '/location/L1/render' ? jsonResponse(202, { jobId: 'j1', runObjectId: 'r1', planned: [{ handle: 'location-tavern' }] }) : null,
            job: () => jsonResponse(200, doneJob({ status: 'failed', result: { runStatus: 'FAILED', executedNodeCount: 0, failedNodeCount: 1, nodes: [{ handle: 'location-tavern', status: 'FAILED', error: 'SD backend unreachable' }] } })),
            locations: () => [tavernWire()]
        });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        await lib.startRender(l, null);
        await waitFor(() => page.toast.mock.calls.length > 0);
        expect(page.toast).toHaveBeenCalledWith('warn', 'Render failed: 0 rendered, 1 failed');
        let stage = findByTestId(lib.renderLocationCard(l), 'pb-loc-run-stage');
        expect(stage.attrs['data-stage-state']).toBe('failed');
        expect(textOf(stage)).toContain('SD backend unreachable');
    });
    it('a 409 "is pinned" refusal shows the server text with "Unpin and render"; the button unpins then re-submits', async () => {
        let refused = true;
        let calls = wire({
            route: (mth, url) => {
                if (mth === 'POST' && url === PB + '/location/L1/render') return refused ? jsonResponse(409, { error: PINNED_MSG }) : jsonResponse(202, { jobId: 'j1', runObjectId: 'r1', planned: [{ handle: 'location-tavern' }] });
                if (mth === 'POST' && url === PB + '/node/n1/pin') { refused = false; return jsonResponse(200, { nodeObjectId: 'n1', pinned: false }); }
                return null;
            },
            job: () => jsonResponse(200, doneJob()),
            locations: () => [tavernWire()]
        });
        let l = tavern({ node: node({ pinned: true }) });
        lib.__setLocationsStateForTest({ locations: [l] });
        await lib.startRender(l, null);
        expect(page.toast).toHaveBeenCalledWith('error', '(409) ' + PINNED_MSG);
        expect(seqOf(calls)).toEqual(['POST ' + PB + '/location/L1/render']);
        let v = lib.renderLocationCard(l);
        let pinned = findByTestId(v, 'pb-loc-pinned');
        expect(textOf(pinned)).toContain(PINNED_MSG);
        expect(findByTestId(v, 'pb-loc-run')).toBeNull();
        expect(lib.__locationsStateForTest().pinnedRefusals.L1).toEqual({ message: '(409) ' + PINNED_MSG, nodeObjectId: 'n1' });
        page.toast.mockClear();
        findByTestId(pinned, 'pb-loc-unpin').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(seqOf(calls).slice(1, 4)).toEqual(['POST ' + PB + '/node/n1/pin', 'POST ' + PB + '/location/L1/render', 'GET /rest/job/j1']);
        expect(calls[1].body).toEqual({ pinned: false });
        expect(lib.__locationsStateForTest().pinnedRefusals.L1).toBeUndefined();
        expect(page.toast).toHaveBeenCalledWith('success', 'Render complete: 1 rendered');
    });
    it('a non-pinned 409 (locked / in flight) is toasted verbatim and offers no unpin', async () => {
        wire({ route: (mth, url) => mth === 'POST' && /\/render$/.test(url) ? jsonResponse(409, { error: 'A render of this location is already in flight' }) : null });
        let l = tavern();
        await lib.startRender(l, null);
        expect(page.toast).toHaveBeenCalledWith('error', '(409) A render of this location is already in flight');
        expect(findByTestId(lib.renderLocationCard(l), 'pb-loc-pinned')).toBeNull();
    });
    it('a 202 without a jobId is reported, not polled', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && /\/render$/.test(url) ? jsonResponse(202, { nodeObjectId: 'n1' }) : null });
        await lib.startRender(tavern(), null);
        await flushPromises();
        expect(page.toast).toHaveBeenCalledWith('error', 'Render was accepted but no job id was returned');
        expect(seqOf(calls)).toEqual(['POST ' + PB + '/location/L1/render']);
    });
});

// ─────────────────────────────── dialogs (rendered through Dialog.loadDialogs) ───────────────────────────────

describe('dialogs', () => {
    it('New location → POST /{book}/locations {name, kind, aliases[]} (blank description omitted); toast; refetch', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'POST' && url === PB + '/locations' ? jsonResponse(201, Object.assign({ objectId: 'L3' }, body)) : null, locations: () => [tavernWire(), quayWire()] });
        lib.openNewLocationDialog();
        let c = renderDialogsInto();
        expect(c.querySelector('[data-testid="pb-loc-new-form"]')).toBeTruthy();
        setInput(c, 'pb-loc-new-name', '  The Quay ');
        setInput(c, 'pb-loc-new-kind', 'exterior', 'change');
        setInput(c, 'pb-loc-new-aliases', 'quay, the quay, quay');
        clickTestId(c, 'pb-loc-new-create');
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/locations', body: { name: 'The Quay', kind: 'exterior', aliases: ['quay', 'the quay'] } });
        expect('description' in calls[0].body).toBe(false);
        expect(page.toast).toHaveBeenCalledWith('success', 'Created The Quay');
        expect(Dialog.stackDepth()).toBe(0);
        expect(lib.__locationsStateForTest().locations.map(l => l.objectId)).toEqual(['L1', 'L2']);
    });
    it('New location: a 400 is toasted verbatim and the dialog stays open', async () => {
        wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations' ? jsonResponse(400, { error: 'name is required' }) : null });
        lib.openNewLocationDialog();
        let c = renderDialogsInto();
        clickTestId(c, 'pb-loc-new-create');
        await flushPromises();
        expect(page.toast).toHaveBeenCalledWith('error', '(400) name is required');
        expect(Dialog.stackDepth()).toBe(1);
    });
    it('Add variant → POST …/location/L1/variants with only the filled deltas', async () => {
        let calls = wire({ route: (mth, url, body) => mth === 'POST' && url === PB + '/location/L1/variants' ? jsonResponse(201, Object.assign({ objectId: 'V2', name: 'The Drowned Anchor - dawn, fog' }, body)) : null, locations: () => [tavernWire()] });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        findByTestId(lib.renderLocationCard(l), 'pb-loc-add-variant').attrs.onclick();
        let c = renderDialogsInto();
        expect(c.querySelector('.am7-dialog-title').textContent).toBe('Add variant of The Drowned Anchor');
        setInput(c, 'pb-loc-variant-timeOfDay', 'dawn');
        setInput(c, 'pb-loc-variant-weather', ' fog ');
        clickTestId(c, 'pb-loc-variant-create');
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/location/L1/variants', body: { timeOfDay: 'dawn', weather: 'fog' } });
        expect(Object.keys(calls[0].body).sort()).toEqual(['timeOfDay', 'weather']);
        expect(page.toast).toHaveBeenCalledWith('success', 'Added variant The Drowned Anchor - dawn, fog');
    });
    it('Merge into… pre-selects the alias match and POSTs …/locations/merge {duplicateObjectId, survivorObjectId}', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/merge' ? jsonResponse(200, { merged: true, survivor: tavernWire() }) : null, locations: () => [tavernWire()] });
        let dup = wf.normalizeLocation(quayWire({ objectId: 'L2', name: 'Anchor Inn' }));
        let other = wf.normalizeLocation(quayWire({ objectId: 'L4', name: 'Zed' }));
        lib.__setLocationsStateForTest({ locations: [other, tavern(), dup] });
        lib.openMergeDialog(dup, 'the anchor');
        let c = renderDialogsInto();
        let sel = c.querySelector('[data-testid="pb-loc-merge-survivor"]');
        expect(sel.value).toBe('L1');
        expect([...sel.options].map(o => o.value)).toEqual(['L4', 'L1']);
        clickTestId(c, 'pb-loc-merge-confirm');
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/locations/merge', body: { duplicateObjectId: 'L2', survivorObjectId: 'L1' } });
        expect(page.toast).toHaveBeenCalledWith('success', 'Merged Anchor Inn into The Drowned Anchor');
        expect(Dialog.stackDepth()).toBe(0);
    });
    it('Merge with no other location: the survivor select is replaced by a notice and Merge is disabled', () => {
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        lib.openMergeDialog(l, null);
        let c = renderDialogsInto();
        expect(c.querySelector('[data-testid="pb-loc-merge-none"]')).toBeTruthy();
        expect(c.querySelector('[data-testid="pb-loc-merge-confirm"]').disabled).toBe(true);
    });
    it('Delete asks (destructive confirm) and only then DELETEs …/location/L1', async () => {
        let calls = wire({ route: (mth, url) => mth === 'DELETE' && url === PB + '/location/L1' ? jsonResponse(200, { deleted: true, locationObjectId: 'L1', steps: [] }) : null });
        let l = tavern();
        lib.__setLocationsStateForTest({ locations: [l] });
        let p = lib.confirmDelete(l);
        let c = renderDialogsInto();
        expect(c.querySelector('.am7-dialog-body').textContent).toContain('Delete "The Drowned Anchor" with its 1 variant(s)');
        [...c.querySelectorAll('.am7-dialog-btn')].find(b => /Cancel/.test(b.textContent)).click();
        await p;
        expect(calls).toHaveLength(0);
        let p2 = lib.confirmDelete(l);
        rerender(c);
        c.querySelector('.am7-dialog-btn-destructive').click();
        await p2;
        expect(seqOf(calls)[0]).toBe('DELETE ' + PB + '/location/L1');
        expect(page.toast).toHaveBeenCalledWith('success', 'Deleted The Drowned Anchor');
    });
    it('a blocked delete (scenes still point at it) toasts the 409 verbatim', async () => {
        wire({ route: (mth) => mth === 'DELETE' ? jsonResponse(409, { error: '2 scene(s) still point at "The Drowned Anchor"; repoint or clear them first' }) : null });
        let p = lib.confirmDelete(tavern());
        renderDialogsInto().querySelector('.am7-dialog-btn-destructive').click();
        await p;
        expect(page.toast).toHaveBeenCalledWith('error', '(409) 2 scene(s) still point at "The Drowned Anchor"; repoint or clear them first');
    });
});

// ─────────────────────────────── proposals ───────────────────────────────

describe('proposals', () => {
    it('an open proposal offers Accept / Merge into… / Dismiss with the sameAs hint; a closed one offers nothing', () => {
        lib.__setLocationsStateForTest({ locations: [tavern()], proposals: [wf.normalizeProposal(proposalWire({ sameAs: 'The Drowned Anchor' })), wf.normalizeProposal(proposalWire({ objectId: 'P2', proposalStatus: 'accepted', locationObjectId: 'L1' }))] });
        let panel = lib.renderProposalsPanel();
        expect(textOf(findByTestId(panel, 'pb-loc-proposal-count'))).toBe('2');
        let [open, closed] = findAllByTestId(panel, 'pb-loc-proposal');
        expect(open.attrs['data-proposal-status']).toBe('PROPOSED');
        expect(textOf(findByTestId(open, 'pb-loc-proposal-sameas'))).toBe('same as: The Drowned Anchor');
        expect(textOf(open)).toContain('2 scenes');
        ['pb-loc-proposal-accept', 'pb-loc-proposal-merge', 'pb-loc-proposal-dismiss', 'pb-loc-proposal-overwrite'].forEach(t => expect(findByTestId(open, t), t).toBeTruthy());
        expect(closed.attrs['data-proposal-status']).toBe('ACCEPTED');
        expect(findByTestId(closed, 'pb-loc-proposal-accept')).toBeNull();
        expect(textOf(closed)).toContain('→ location');
    });
    it('Accept → POST …/proposed/P1/accept {overwrite:false}; toast names created/matched + repointed counts; refetch', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/proposed/P1/accept' ? jsonResponse(200, { locationObjectId: 'L1', locationCreated: false, scenesRepointed: 2, scenesSkipped: 1, aliasesFolded: 1 }) : null, locations: () => [tavernWire()] });
        let p = wf.normalizeProposal(proposalWire());
        lib.__setLocationsStateForTest({ proposals: [p] });
        findByTestId(lib.renderProposalsPanel(), 'pb-loc-proposal-accept').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/locations/proposed/P1/accept', body: { overwrite: false } });
        expect(page.toast).toHaveBeenCalledWith('success', 'Accepted The Drowned Anchor: matched an existing location, 2 scenes repointed, 1 skipped, 1 alias(es) folded');
    });
    it('the overwrite checkbox makes Accept send {overwrite:true}', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && /\/accept$/.test(url) ? jsonResponse(200, { locationObjectId: 'L1', locationCreated: true, scenesRepointed: 1 }) : null });
        let p = wf.normalizeProposal(proposalWire());
        lib.__setLocationsStateForTest({ proposals: [p] });
        findByTestId(lib.renderProposalsPanel(), 'pb-loc-proposal-overwrite').attrs.onchange({ target: { checked: true } });
        await lib.acceptProposal(p);
        expect(calls[0].body).toEqual({ overwrite: true });
        expect(page.toast).toHaveBeenCalledWith('success', 'Accepted The Drowned Anchor: created, 1 scene repointed');
    });
    it('Dismiss → reason dialog → POST …/proposed/P1/reject {reason}', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/proposed/P1/reject' ? jsonResponse(200, proposalWire({ proposalStatus: 'rejected' })) : null });
        let p = wf.normalizeProposal(proposalWire());
        lib.__setLocationsStateForTest({ proposals: [p] });
        findByTestId(lib.renderProposalsPanel(), 'pb-loc-proposal-dismiss').attrs.onclick();
        let c = renderDialogsInto();
        setInput(c, 'pb-loc-dismiss-reason', ' duplicate of the tavern ');
        clickTestId(c, 'pb-loc-dismiss-confirm');
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/locations/proposed/P1/reject', body: { reason: 'duplicate of the tavern' } });
        expect(page.toast).toHaveBeenCalledWith('success', 'Dismissed The Drowned Anchor');
    });
    it('Merge into… = accept, then the merge dialog pre-selected on sameAs', async () => {
        let calls = wire({ route: (mth, url) => mth === 'POST' && /\/accept$/.test(url) ? jsonResponse(200, { locationObjectId: 'L3', locationCreated: true, scenesRepointed: 2 }) : null,
            locations: () => [tavernWire(), quayWire({ objectId: 'L3', name: 'Anchor Inn' })] });
        let p = wf.normalizeProposal(proposalWire({ proposedName: 'Anchor Inn', sameAs: 'the anchor' }));
        lib.__setLocationsStateForTest({ locations: [tavern()], proposals: [p] });
        await lib.acceptAndMerge(p);
        expect(calls[0].url).toBe(PB + '/locations/proposed/P1/accept');
        expect(Dialog.stackDepth()).toBe(1);
        let c = renderDialogsInto();
        expect(c.querySelector('.am7-dialog-title').textContent).toBe('Merge Anchor Inn into…');
        expect(c.querySelector('[data-testid="pb-loc-merge-survivor"]').value).toBe('L1');
    });
    it('Propose from scenes → POST …/locations/propose {}; the summary toast; hardFailure is an error', async () => {
        let n = 0;
        let calls = wire({ route: (mth, url) => mth === 'POST' && url === PB + '/locations/propose'
            ? jsonResponse(200, ++n === 1 ? { scenesConsidered: 5, scenesSkipped: 1, proposed: 2, warnings: ['chunk 2 returned nothing'], proposals: [proposalWire()] } : { hardFailure: true, warnings: ['the LLM returned no JSON'] }) : null });
        findByTestId(lib.renderLocationsHeader(), 'pb-loc-propose').attrs.onclick();
        await waitFor(() => only(calls, /GET .*\/proposed/).length === 1);
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/locations/propose', body: {} });
        expect(page.toast).toHaveBeenCalledWith('success', 'Proposed 2 locations from 5 scenes (1 skipped) · 1 warning(s)');
        await lib.propose();
        expect(page.toast).toHaveBeenCalledWith('error', 'Location pass failed: the LLM returned no JSON');
    });
    it('the status filter reloads GET …/proposed?status=<lower>', async () => {
        let calls = wire({ proposals: () => [] });
        findByTestId(lib.renderProposalsPanel(), 'pb-loc-proposal-filter').attrs.onchange({ target: { value: 'REJECTED' } });
        await flushPromises();
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/locations/proposed?status=rejected']);
        expect(lib.__locationsStateForTest().proposalFilter).toBe('REJECTED');
        expect(textOf(findByTestId(lib.renderProposalsPanel(), 'pb-loc-proposals-empty'))).toBe('Nothing rejected.');
        lib.setProposalFilter('bogus');
        expect(lib.__locationsStateForTest().proposalFilter).toBe('REJECTED');
    });
});

// ─────────────────────────────── route / reader chip (pictureBook.js) ───────────────────────────────

describe('pictureBook.js integration', () => {
    it('the locations route is spread into the PB2 routes', () => {
        expect(Object.keys(lib.routes)).toEqual(['/picture-book/:bookObjectId/locations']);
        expect(pbf.routes['/picture-book/:bookObjectId/locations']).toBe(lib.routes['/picture-book/:bookObjectId/locations']);
    });
    it('pageLocationText / renderPb2LocationChip: "Tavern / dusk" from bookPageView names, nothing when unlocated', () => {
        expect(pbf.pageLocationText({ locationName: 'Tavern', locationVariantName: 'dusk' })).toBe('Tavern / dusk');
        expect(pbf.pageLocationText({ locationName: 'Tavern' })).toBe('Tavern');
        expect(pbf.pageLocationText({ locationVariantName: 'dusk' })).toBeNull();
        expect(pbf.renderPb2LocationChip({})).toBeNull();
        let chip = pbf.renderPb2LocationChip({ locationObjectId: 'L1', locationName: 'Tavern', locationVariantObjectId: 'V1', locationVariantName: 'dusk' });
        expect(chip.attrs).toMatchObject({ 'data-testid': 'pb2-page-location', 'data-location-id': 'L1', 'data-variant-id': 'V1' });
        expect(textOf(chip)).toContain('Tavern / dusk');
    });
});

// ─────────────────────────────── ChapBook page card: chip + picker ───────────────────────────────

describe('chapBook page location chip + picker', () => {
    let cb;
    let origUser;
    beforeAll(async () => { cb = await import('../features/chapBook.js'); });
    beforeEach(() => {
        origUser = page.user;
        cb.__setReviewStateForTest({ reviewBookObjectId: BOOK, reviewGroupId: null, reviewScenes: [], roleWarning: null });
    });
    afterEach(() => {
        page.user = origUser;
        cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewGroupId: null, reviewScenes: [], roleWarning: null });
    });

    function scene(extra) {
        return Object.assign({ objectId: 's1', sceneIndex: 1, name: 'Scene 1', location: null, locationVariant: null, _saving: false, _dirty: new Set() }, extra || {});
    }

    it('pageLocationLabel: "unlocated" / "located: Name" / "located: Name / Variant" (objectId fallback)', () => {
        expect(cb.pageLocationLabel(scene())).toBe('unlocated');
        expect(cb.pageLocationLabel(scene({ location: { objectId: 'L1', name: 'Tavern' } }))).toBe('located: Tavern');
        expect(cb.pageLocationLabel(scene({ location: { objectId: 'L1', name: 'Tavern' }, locationVariant: { objectId: 'V1', name: 'dusk' } }))).toBe('located: Tavern / dusk');
        expect(cb.pageLocationLabel(scene({ location: { objectId: 'L1' } }))).toBe('located: L1');
    });
    it('renderPageLocationChip carries the scene / location / variant ids and is disabled while saving or without the role', () => {
        let chip = cb.renderPageLocationChip(scene({ location: { objectId: 'L1', name: 'Tavern' }, locationVariant: { objectId: 'V1', name: 'dusk' } }));
        expect(chip.attrs).toMatchObject({ 'data-testid': 'cb-page-location', 'data-scene-oid': 's1', 'data-location-id': 'L1', 'data-variant-id': 'V1' });
        expect(chip.attrs.disabled).toBeFalsy();
        expect(textOf(chip)).toContain('located: Tavern / dusk');
        let bare = cb.renderPageLocationChip(scene());
        expect(bare.attrs).toMatchObject({ 'data-location-id': '', 'data-variant-id': '' });
        expect(textOf(bare)).toContain('unlocated');
        expect(cb.renderPageLocationChip(scene({ _saving: true })).attrs.disabled).toBe(true);
        cb.__setReviewStateForTest({ roleWarning: 'no role' });
        expect(cb.renderPageLocationChip(scene()).attrs.disabled).toBe('no role');
    });
    it('picker: GET /{book}/locations → choose location + variant → Apply → PUT …/scene/s1/location; stale toast', async () => {
        let calls = wire({
            route: (mth, url, body) => mth === 'PUT' && url === PB + '/scene/s1/location'
                ? jsonResponse(200, { sceneObjectId: 's1', locationObjectId: body.locationObjectId, locationName: 'The Drowned Anchor', variantObjectId: body.variantObjectId || null, variantName: body.variantObjectId ? 'The Drowned Anchor - dusk, rain' : null, updated: true, nodeStale: true, pinned: false, nodeStatus: 'STALE' }) : null,
            locations: () => [tavernWire(), quayWire()]
        });
        let s = scene();
        cb.__setReviewStateForTest({ reviewScenes: [s] });
        cb.renderPageLocationChip(s).attrs.onclick();
        let c = renderDialogsInto();
        expect(c.querySelector('[data-testid="cb-page-location-picker"]').textContent).toContain('Loading locations…');
        expect(c.querySelector('[data-testid="cb-page-location-apply"]').disabled).toBe(true);
        await waitFor(() => only(calls, /GET .*\/locations$/).length === 1);
        await flushPromises();
        rerender(c);
        let sel = c.querySelector('[data-testid="cb-page-location-select"]');
        expect([...sel.options].map(o => o.value)).toEqual(['', 'L1', 'L2']);
        expect([...sel.options].map(o => o.textContent)).toEqual(['— none —', 'The Drowned Anchor (mixed)', 'The Quay (exterior)']);
        expect(c.querySelector('[data-testid="cb-page-location-variant"]')).toBeNull();
        expect(c.querySelector('[data-testid="cb-page-location-clear"]').disabled).toBe(true);
        sel.value = 'L1';
        sel.dispatchEvent(new Event('change', { bubbles: true }));
        rerender(c);
        let vsel = c.querySelector('[data-testid="cb-page-location-variant"]');
        expect([...vsel.options].map(o => o.value)).toEqual(['', 'V1']);
        vsel.value = 'V1';
        vsel.dispatchEvent(new Event('change', { bubbles: true }));
        rerender(c);
        clickTestId(c, 'cb-page-location-apply');
        await waitFor(() => only(calls, /PUT/).length === 1);
        await flushPromises();
        expect(calls.find(x => x.method === 'PUT')).toMatchObject({ url: PB + '/scene/s1/location', body: { locationObjectId: 'L1', variantObjectId: 'V1' } });
        expect(s.location).toEqual({ objectId: 'L1', name: 'The Drowned Anchor' });
        expect(s.locationVariant).toEqual({ objectId: 'V1', name: 'The Drowned Anchor - dusk, rain' });
        expect(s._saving).toBe(false);
        expect(page.toast).toHaveBeenCalledWith('success', 'Page located: The Drowned Anchor / The Drowned Anchor - dusk, rain — node marked stale');
        expect(am7client.clearCache).toHaveBeenCalledWith('olio.pb.scene', true);
        expect(Dialog.stackDepth()).toBe(0);
        expect(only(calls, /\/rest\/model\/search/)).toHaveLength(0);
    });
    it('picker Clear → PUT {} ; a pinned node is worded as a warning; the overrides batch-read refreshes every card', async () => {
        page.user = { organizationId: 2 };
        let calls = wire({
            route: (mth, url, body) => {
                if (mth === 'PUT' && url === PB + '/scene/s1/location') return jsonResponse(200, { sceneObjectId: 's1', updated: true, nodeStale: true, pinned: true, nodeStatus: 'STALE' });
                if (mth === 'POST' && url === '/rest/model/search') return jsonResponse(200, [
                    { objectId: 's1', name: 'Scene 1', location: null, locationVariant: null },
                    { objectId: 's2', name: 'Scene 2', location: { objectId: 'L1', name: 'The Drowned Anchor' }, locationVariant: { objectId: 'V1', name: 'dusk' } }
                ]);
                return null;
            },
            locations: () => [tavernWire()]
        });
        let s1 = scene({ location: { objectId: 'L1', name: 'The Drowned Anchor' }, locationVariant: null });
        let s2 = scene({ objectId: 's2', sceneIndex: 2 });
        cb.__setReviewStateForTest({ reviewScenes: [s1, s2], reviewGroupId: 77 });
        cb.openSceneLocationPicker(s1);
        let c = renderDialogsInto();
        await waitFor(() => only(calls, /GET .*\/locations$/).length === 1);
        await flushPromises();
        rerender(c);
        expect(c.querySelector('[data-testid="cb-page-location-select"]').value).toBe('L1');
        clickTestId(c, 'cb-page-location-clear');
        await waitFor(() => only(calls, /\/rest\/model\/search/).length === 1);
        await flushPromises();
        expect(calls.find(x => x.method === 'PUT').body).toEqual({});
        expect(page.toast).toHaveBeenCalledWith('warn', 'Page location cleared — node marked stale; it is pinned, so Render will skip it until unpinned');
        let search = calls.find(x => x.url === '/rest/model/search').body;
        expect(search).toMatchObject({ schema: 'io.query', type: 'olio.pb.scene', cache: false, recordCount: 500 });
        expect(search.request).toEqual(expect.arrayContaining(['location', 'locationVariant']));
        expect(search.fields).toEqual([{ name: 'groupId', comparator: 'EQUALS', value: 77 }, { name: 'organizationId', comparator: 'EQUALS', value: 2 }]);
        expect(s1.location).toBeNull();
        expect(s2.location).toEqual({ objectId: 'L1', name: 'The Drowned Anchor' });
        expect(s2.locationVariant).toEqual({ objectId: 'V1', name: 'dusk' });
    });
    it('a refused PUT toasts the server message and leaves the scene pointer alone', async () => {
        wire({ route: (mth) => mth === 'PUT' ? jsonResponse(404, { error: 'Location not found in this book: L9' }) : null });
        let s = scene({ location: { objectId: 'L1', name: 'Tavern' } });
        await cb.doSetSceneLocation(s, 'L9', null);
        expect(page.toast).toHaveBeenCalledWith('error', '(404) Location not found in this book: L9');
        expect(s.location).toEqual({ objectId: 'L1', name: 'Tavern' });
        expect(s._saving).toBe(false);
    });
    it('picker with no locations shows the empty hint and keeps Apply disabled; a failed read is shown', async () => {
        let calls = wire({ locations: () => [] });
        cb.openSceneLocationPicker(scene());
        let c = renderDialogsInto();
        await waitFor(() => calls.length === 1);
        await flushPromises();
        rerender(c);
        expect(c.querySelector('[data-testid="cb-page-location-empty"]')).toBeTruthy();
        expect(c.querySelector('[data-testid="cb-page-location-apply"]').disabled).toBe(true);
        Dialog._reset();
        wire({ route: () => jsonResponse(500, { error: 'boom' }) });
        cb.openSceneLocationPicker(scene());
        c = renderDialogsInto();
        await flushPromises();
        rerender(c);
        expect(c.querySelector('[data-testid="cb-page-location-error"]').textContent).toBe('(500) boom');
    });
    it('loadSceneOverrides returns {} without a group or organization and maps the FK sub-records', async () => {
        page.user = null;
        expect(await cb.loadSceneOverrides(77)).toEqual({});
        page.user = { organizationId: 2 };
        expect(await cb.loadSceneOverrides(null)).toEqual({});
        wire({ route: (mth, url) => url === '/rest/model/search' ? jsonResponse(200, { results: [{ objectId: 's1', name: 'Scene 1', location: { objectId: 'L1', name: 'Tavern' }, locationVariant: { id: 0 } }] }) : null });
        let map = await cb.loadSceneOverrides('77');
        expect(map.s1).toMatchObject({ name: 'Scene 1', location: { objectId: 'L1', name: 'Tavern' }, locationVariant: null });
    });
});

// ─────────────────────────────── plan inspector + canvas ───────────────────────────────

describe('PbPlanInspector location rows', () => {
    it('shows location id / variant id / hash / render artifact and the two inputs when the plan carries them', () => {
        let plan = { strategy: { name: 'TEXT_ONLY', stages: ['scene'] }, location: { description: 'a low tavern', locationId: 'L1', variantId: 'V1', revisionHash: 'abc123', renderArtifactId: 'a7' }, inputs: { location: 'n-loc', locationVariant: 'n-var' } };
        let v = PbPlanInspector.view({ attrs: { plan } });
        let text = textOf(v);
        ['a low tavern', 'Location idL1', 'Variant idV1', 'Location hashabc123', 'Location rendera7', 'Input: locationn-loc', 'Input: variantn-var'].forEach(t => expect(text).toContain(t));
    });
    it('omits every location row but the description when the plan has no location', () => {
        let text = textOf(PbPlanInspector.view({ attrs: { plan: { strategy: { name: 'x' } } } }));
        expect(text).toContain('Location—');
        ['Location id', 'Variant id', 'Location hash', 'Location render', 'Input: location', 'Input: variant'].forEach(t => expect(text).not.toContain(t));
    });
});

describe('canvas LOCATION nodes', () => {
    let cv;
    beforeAll(async () => { cv = await import('../features/pictureBookWorkflow.js'); });
    afterEach(() => { cv.__setCanvasStateForTest({ positions: {}, selectedNodeId: null, nodeDetails: null, graphData: null }); });

    it('LOCATION ranks between landscape and reference; the accent is matched case-insensitively by substring', () => {
        let r = cv.NODE_TYPE_RANK;
        expect(r.landscape).toBeLessThan(r.location);
        expect(r.location).toBeLessThan(r.reference);
        expect(r.reference).toBeLessThan(r.scene);
        expect(cv.typeAccent({ nodeType: 'LOCATION' })).toEqual({ label: 'LOCATION', color: '#0d9488' });
        expect(cv.typeAccent({ nodeType: 'location_variant' })).toEqual(cv.TYPE_ACCENT.location);
        expect(cv.typeAccent({ nodeType: 'SCENE' })).toBeNull();
        expect(cv.typeAccent(null)).toBeNull();
    });
    it('a LOCATION card carries data-node-type, the teal accent border and the LOCATION chip; a scene card does not', () => {
        cv.__setCanvasStateForTest({ positions: { n1: { x: 10, y: 20 }, n2: { x: 10, y: 200 } }, selectedNodeId: null, nodeDetails: null });
        let card = cv.renderNodeCard({ objectId: 'n1', nodeType: 'LOCATION', handle: 'location-tavern', status: 'DONE', pinned: false });
        expect(card.attrs['data-node-type']).toBe('location');
        expect(card.attrs.style).toContain('border:2px solid #0d9488');
        expect(textOf(findByTestId(card, 'pb-canvas-type-accent'))).toBe('LOCATION');
        expect(textOf(card)).toContain('location-tavern');
        let sc = cv.renderNodeCard({ objectId: 'n2', nodeType: 'SCENE', handle: 'scene-3', status: 'STALE' });
        expect(sc.attrs['data-node-type']).toBe('scene');
        expect(findByTestId(sc, 'pb-canvas-type-accent')).toBeNull();
        expect(sc.attrs.style).toContain('border:2px solid #e2e8f0');
        expect(cv.renderNodeCard({ objectId: 'n9', nodeType: 'LOCATION' })).toBeNull();
    });
});
