// @vitest-environment jsdom
/**
 * PictureBook V3 cover art (P9-4, design §40) — the composition board in cover mode, the covers workflow helpers,
 * and the two readers' cover pages.
 *
 * Why: a cover is a composition keyed by purpose (COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART) instead of by
 * scene, over the P9-3 routes: GET /{book}/covers (find-only), GET|PATCH|POST /{book}/covers/{purpose}, POST
 * .../plan, POST .../render (202 + job). The board must (a) read the cover board + outfits + plan + the covers
 * list, (b) show Create (auto / from a scene / manual) on the "has no COVER composition" 404 — the cover 404 wording
 * differs from the scene one and must still be the empty state, not an error — (c) send the title-safe band on the
 * camera save as a cover-keyed PATCH, (d) follow a render 202 to its terminal counter and re-read, (e) approve the
 * latest render through the artifact route, and (f) the readers must show the APPROVED cover first (never a
 * merely-latest one), placing the title block in the band.
 *
 * No network, LLM or SD is touched: fetch, am7client.clearCache / dotPath, page.toast, m.route.set and
 * LLMConnector.resolveConfig are stubbed. Render-function vnode trees are asserted without mounting; dialogs are
 * rendered through Dialog.loadDialogs() into a detached container so the real radio / click handlers run.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import { applicationPath } from '../core/config.js';

const BOOK = 'book-1';
const PB = '/rest/olio/picture-book/' + BOOK;
const MEDIA = applicationPath + '/media/Public/data.data';

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

// ── Wire fixtures (PbServiceFacade coverEntryDto / coverView / cover board; NON_EMPTY, enums lowercase) ──

function imageRefWire(n, extra) {
    return Object.assign({ artifactObjectId: 'art-' + n, revision: n, reviewStatus: 'generated', dataObjectId: 'd-' + n, imageGroupPath: '/Pb/covers', imageName: 'cover-r' + n + '.png', imageContentType: 'image/png' }, extra || {});
}
/** A COVER entry that has never rendered (no workflow / node yet - the find-only read's shape for a fresh book). */
function coverEntryWire(extra) {
    return Object.assign({ compositionObjectId: 'comp-1', purpose: 'cover', source: 'user', titleSafeBand: 'unknown', chainLength: 0, pinned: false }, extra || {});
}
/** A COVER entry rendered twice: revision 1 approved and selected, revision 2 the latest. */
function renderedEntryWire(extra) {
    return Object.assign(coverEntryWire({
        source: 'llm', titleSafeBand: 'bottom', nodeObjectId: 'n-cover', nodeStatus: 'done_unverified', chainLength: 2, reviewStatus: 'approved', selectedArtifactObjectId: 'art-1',
        approved: imageRefWire(1, { reviewStatus: 'approved' }), latest: imageRefWire(2)
    }), extra || {});
}
function coverViewWire(extra) {
    return Object.assign({ bookObjectId: BOOK, workflowObjectId: 'wf-1', covers: { COVER: coverEntryWire() } }, extra || {});
}
function compositionWire(extra) {
    return Object.assign({
        objectId: 'comp-1', name: 'cover-composition', source: 'user', purpose: 'cover', shotType: 'wide', viewpoint: 'eye_level',
        cameraText: '', mood: 'luminous', settingText: 'a lighthouse at dawn', action: '', props: '', titleSafeBand: 'unknown',
        focalActorObjectId: null, userEdited: false, promptLocked: false, revision: 1
    }, extra || {});
}
function actorWire(extra) {
    return Object.assign({ objectId: 'a1', characterObjectId: 'c1', characterName: 'Alice', role: 'hero', priority: 1, pose: 'standing', expression: 'calm', action: '', facing: 'front', referencePolicy: 'auto', x: 0.3, y: 0.2, w: 0.4, h: 0.7, depth: 1, count: 1 }, extra || {});
}
/** GET /{book}/covers/{purpose}: the board shape (no sceneObjectId) + purpose + the covers-list entry. */
function coverBoardWire(extra) {
    return Object.assign({ bookObjectId: BOOK, purpose: 'cover', compositionObjectId: 'comp-1', composition: compositionWire(), actors: [actorWire()], elements: [], cover: coverEntryWire() }, extra || {});
}
function outfitsWire() {
    return { compositionObjectId: 'comp-1', actors: [{ actorObjectId: 'a1', actorName: 'Alice', characterObjectId: 'c1', apparelObjectId: 'ap1', apparelName: 'Sea coat', apparelDescription: 'an oilskin coat', source: 'baseline' }] };
}
function planWire(extra) {
    return Object.assign({ canvas: { width: 1024, height: 1536 }, reserved: { x: 0, y: 0.75, w: 1, h: 0.25 }, compiled: { base: { prompt: 'a lighthouse at dawn', negative: 'blurry', estimatedTokens: 12 } }, degradations: [], strategy: { stages: [] } }, extra || {});
}
function createResultWire(extra) {
    return Object.assign({ bookObjectId: BOOK, purpose: 'cover', mode: 'manual', created: true, written: true, source: 'user', compositionObjectId: 'comp-1', composition: compositionWire(), actors: [], elements: [] }, extra || {});
}
const PAGES = [{ objectId: 's1', sceneIndex: 1, title: 'Quay' }, { objectId: 's2', sceneIndex: 2, title: 'Market' }];

let origFetch;
let cmp;
let wf;

function st() { return cmp.__composeStateForTest(); }
function view() { return cmp.composeView.view(); }

/** Seed the board in cover mode (COVER) from a cover-board wire; no fetch is issued. */
function seedCover(opts) {
    let o = opts || {};
    let b = wf.normalizeBoard(o.board || coverBoardWire());
    let entryWire = o.board && 'cover' in o.board ? o.board.cover : coverEntryWire();
    b.purpose = 'COVER';
    b.cover = wf.normalizeCoverEntry(entryWire);
    cmp.__setComposeStateForTest({
        bookObjectId: BOOK, sceneObjectId: null, coverPurpose: o.coverPurpose || 'COVER', pages: o.pages || PAGES, loading: false, error: null, noComposition: false, noCompositionMessage: null,
        board: b, cover: b.cover, covers: o.covers !== undefined ? o.covers : wf.normalizeCoverView(coverViewWire()).covers,
        outfits: { a1: wf.normalizeResolvedOutfit(outfitsWire().actors[0]) },
        plan: o.plan !== undefined ? o.plan : wf.normalizePlan(planWire()),
        planError: null, planLoading: false, busy: {}, edits: { composition: null, actors: {}, elements: {} }, lastEdit: null,
        characters: [], composeRun: null, renderRun: o.renderRun || null, drag: null, overrideInst: null, overrideExpanded: false, pollIntervalMs: 1
    });
}

beforeEach(async () => {
    origFetch = global.fetch;
    cmp = await import('../features/pictureBookCompose.js');
    wf = await import('../workflows/pictureBookWorkflow.js');
    cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, coverPurpose: null, cover: null, covers: null, renderRun: null, pages: [], board: null, loading: false, error: null, noComposition: false, noCompositionMessage: null, loadSeq: 0, outfits: {}, plan: null, planError: null, planLoading: false, busy: {}, edits: null, lastEdit: null, characters: [], composeRun: null, drag: null, overrideInst: null, overrideExpanded: false, sdModels: [], sdLoras: [], pollIntervalMs: 1, chatConfigRef: null, chatConfigResolving: false });
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
    vi.spyOn(LLMConnector, 'resolveConfig').mockResolvedValue(null);
});

afterEach(() => {
    cmp.composeView.onremove();
    cmp.__setComposeStateForTest({ bookObjectId: null, sceneObjectId: null, coverPurpose: null, cover: null, covers: null, renderRun: null, pages: [], board: null, composeRun: null, busy: {}, edits: null, lastEdit: null, chatConfigRef: null, chatConfigResolving: false });
    Dialog._reset();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

/**
 * fetch router for the cover wire. o.route(method, url, body, calls) answers first; then GET /rest/job/{id} via
 * o.job(id, nth); then the reads cover mode issues: the page list, the cover board, its outfits, the plan, the
 * covers list.
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
        if (method === 'GET' && url === PB + '/covers/cover') return jsonResponse(200, o.board ? o.board(calls) : coverBoardWire());
        if (method === 'GET' && url === PB + '/covers') return jsonResponse(200, o.covers ? o.covers(calls) : coverViewWire());
        if (method === 'GET' && url === PB + '/composition/comp-1/outfits') return jsonResponse(200, outfitsWire());
        if (method === 'POST' && url === PB + '/covers/cover/plan') return jsonResponse(200, o.plan ? o.plan(calls) : planWire());
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}

const BOARD_READ = 'GET ' + PB + '/covers/cover';
const OUTFITS_READ = 'GET ' + PB + '/composition/comp-1/outfits';
const PLAN_READ = 'POST ' + PB + '/covers/cover/plan';
const COVERS_READ = 'GET ' + PB + '/covers';
function reads(calls) { return seqOf(calls).filter(s => s === BOARD_READ || s === OUTFITS_READ || s === PLAN_READ || s === COVERS_READ); }
/** Every refetch re-reads the board, its outfits, the covers list and the plan; wait for all four after a write. */
async function settled(calls, planCount) {
    await waitFor(() => Object.keys(st().busy).length === 0 && !st().loading && !st().planLoading && seqOf(calls).filter(s => s === PLAN_READ).length >= (planCount || 1) && seqOf(calls).filter(s => s === COVERS_READ).length >= (planCount || 1));
}
function initCover(purpose) {
    cmp.composeView.oninit({ attrs: { bookObjectId: BOOK, mode: 'cover', purpose: purpose || 'cover' } });
}

// ─────────────────────────────── workflow helpers (pure) ───────────────────────────────

describe('cover workflow helpers', () => {
    it('coverPathWord / coverPurposeOf / coverPurposeLabel round-trip the four purposes and reject anything else', () => {
        expect(wf.COVER_PURPOSES).toEqual(['COVER', 'TITLE_PAGE', 'CHAPTER_OPENER', 'SERIES_ART']);
        expect(wf.COVER_PURPOSES.map(wf.coverPathWord)).toEqual(['cover', 'title-page', 'chapter-opener', 'series-art']);
        expect(['cover', 'title-page', 'Chapter Opener', 'SERIES_ART', ' title_page '].map(wf.coverPurposeOf)).toEqual(['COVER', 'TITLE_PAGE', 'CHAPTER_OPENER', 'SERIES_ART', 'TITLE_PAGE']);
        expect([null, undefined, '', 'scene', 'chapbook_page', 'covers'].map(wf.coverPurposeOf)).toEqual([null, null, null, null, null, null]);
        expect(wf.COVER_PURPOSES.map(wf.coverPurposeLabel)).toEqual(['Cover', 'Title page', 'Chapter opener', 'Series art']);
        expect(wf.coverPurposeLabel('title-page')).toBe('Title page');
        expect(wf.coverPurposeLabel('scene')).toBe('scene');
        expect(wf.coverPurposeLabel(null)).toBe('');
    });
    it('normalizeCoverEntry upper-cases every enum, booleans pinned, defaults chainLength and nulls the refs', () => {
        let e = wf.normalizeCoverEntry(renderedEntryWire());
        expect(e).toMatchObject({ compositionObjectId: 'comp-1', purpose: 'COVER', source: 'LLM', titleSafeBand: 'BOTTOM', nodeObjectId: 'n-cover', nodeStatus: 'DONE_UNVERIFIED', pinned: false, lastError: null, chainLength: 2, reviewStatus: 'APPROVED', selectedArtifactObjectId: 'art-1' });
        expect(e.approved).toEqual({ artifactObjectId: 'art-1', revision: 1, reviewStatus: 'APPROVED', dataObjectId: 'd-1', imageGroupPath: '/Pb/covers', imageName: 'cover-r1.png', imageContentType: 'image/png' });
        expect(e.latest.reviewStatus).toBe('GENERATED');
        let fresh = wf.normalizeCoverEntry(coverEntryWire());
        expect(fresh).toMatchObject({ nodeObjectId: null, nodeStatus: null, pinned: false, chainLength: 0, reviewStatus: null, approved: null, latest: null, seriesObjectId: null });
        expect(wf.normalizeCoverEntry({ pinned: 'true', chainLength: null })).toMatchObject({ pinned: true, chainLength: 0 });
        expect(wf.normalizeCoverEntry(null)).toBeNull();
        expect(wf.normalizeCoverEntry('x')).toBeNull();
        expect(wf.normalizeCoverImageRef(null)).toBeNull();
        expect(wf.normalizeCoverImageRef({})).toEqual({ artifactObjectId: null, revision: 0, reviewStatus: null, dataObjectId: null, imageGroupPath: null, imageName: null, imageContentType: null });
    });
    it('normalizeCoverView carries every purpose (null = no composition) and accepts lower-case keys', () => {
        let v = wf.normalizeCoverView(coverViewWire({ covers: { cover: coverEntryWire(), SERIES_ART: coverEntryWire({ purpose: 'series_art', compositionObjectId: 'comp-9' }) } }));
        expect(v.bookObjectId).toBe(BOOK);
        expect(v.workflowObjectId).toBe('wf-1');
        expect(Object.keys(v.covers)).toEqual(['COVER', 'TITLE_PAGE', 'CHAPTER_OPENER', 'SERIES_ART']);
        expect(v.covers.COVER.compositionObjectId).toBe('comp-1');
        expect(v.covers.TITLE_PAGE).toBeNull();
        expect(v.covers.CHAPTER_OPENER).toBeNull();
        expect(v.covers.SERIES_ART).toMatchObject({ purpose: 'SERIES_ART', compositionObjectId: 'comp-9' });
        let empty = wf.normalizeCoverView({ bookObjectId: BOOK });
        expect(empty.workflowObjectId).toBeNull();
        expect(empty.covers).toEqual({ COVER: null, TITLE_PAGE: null, CHAPTER_OPENER: null, SERIES_ART: null });
        expect(wf.normalizeCoverView(null).covers.COVER).toBeNull();
    });
    it('coverImageRefFor: the APPROVED render first; the latest only when allowed; null for no cover, no render or a non-cover purpose', () => {
        let rendered = wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire() } }));
        expect(wf.coverImageRefFor(rendered, 'COVER', false).artifactObjectId).toBe('art-1');
        expect(wf.coverImageRefFor(rendered, 'cover', true).artifactObjectId).toBe('art-1');
        let latestOnly = wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ approved: null, reviewStatus: 'generated', selectedArtifactObjectId: 'art-2' }) } }));
        expect(wf.coverImageRefFor(latestOnly, 'COVER', false)).toBeNull();
        expect(wf.coverImageRefFor(latestOnly, 'COVER', true).artifactObjectId).toBe('art-2');
        expect(wf.coverImageRefFor(wf.normalizeCoverView(coverViewWire()), 'COVER', true)).toBeNull();
        expect(wf.coverImageRefFor(rendered, 'TITLE_PAGE', true)).toBeNull();
        expect(wf.coverImageRefFor(rendered, 'scene', true)).toBeNull();
        expect(wf.coverImageRefFor(null, 'COVER', true)).toBeNull();
    });
    it('isNoCompositionYet accepts the scene wording and the cover wording ("has no COVER composition"), only on a 404', () => {
        let scene = new Error('(404) Scene 3 has no composition yet'); scene.status = 404;
        let cover = new Error('(404) Book harlots-eight has no COVER composition - create it first'); cover.status = 404;
        let titlePage = new Error('(404) Book harlots-eight has no TITLE_PAGE composition - create it first'); titlePage.status = 404;
        let bodyOnly = new Error('(404) getCover failed'); bodyOnly.status = 404; bodyOnly.body = '{"error":"Book x has no SERIES_ART composition - create it first"}';
        let notFound = new Error('(404) Book not found'); notFound.status = 404;
        let forbidden = new Error('(403) Book harlots-eight has no COVER composition - create it first'); forbidden.status = 403;
        expect([scene, cover, titlePage, bodyOnly].map(wf.isNoCompositionYet)).toEqual([true, true, true, true]);
        expect([notFound, forbidden, null].map(wf.isNoCompositionYet)).toEqual([false, false, false]);
    });
    it('createCover lower-cases the mode and sends only the options given; renderCover adds ?force=true only when forced', async () => {
        let calls = wire({ route: (method, url) => (method === 'POST' && /\/covers\/[a-z-]+(\/render)?/.test(url)) ? jsonResponse(method === 'POST' && /render/.test(url) ? 202 : 200, /render/.test(url) ? { jobId: 'j1', nodeMaterialized: true } : createResultWire()) : null });
        let res = await wf.createCover(BOOK, 'TITLE_PAGE', { mode: 'AUTO', chatConfigName: 'e2e-chapbook-llm' });
        expect(calls[0].url).toBe(PB + '/covers/title-page');
        expect(calls[0].body).toEqual({ mode: 'auto', chatConfigName: 'e2e-chapbook-llm' });
        // Manual mode creates a USER-source composition: PbCompositionSourceEnumType has no MANUAL value.
        expect(res).toMatchObject({ purpose: 'COVER', mode: 'MANUAL', created: true, written: true, existingReturned: false, source: 'USER', warnings: [], heroCast: [] });
        await wf.createCover(BOOK, 'cover', { mode: 'from_scene', sceneObjectId: 's2' });
        expect(calls[1].body).toEqual({ mode: 'from_scene', sceneObjectId: 's2' });
        await wf.createCover(BOOK, 'cover', {});
        expect(calls[2].body).toEqual({});
        let sub = await wf.renderCover(BOOK, 'cover', false);
        expect(calls[3].url).toBe(PB + '/covers/cover/render');
        expect(sub).toMatchObject({ jobId: 'j1', nodeMaterialized: true, purpose: 'COVER', runObjectId: null });
        await wf.renderCover(BOOK, 'chapter_opener', true);
        expect(calls[4].url).toBe(PB + '/covers/chapter-opener/render?force=true');
    });
    it('getCover returns the board with purpose + the normalized entry; patchCover / planCover hit the cover-keyed routes', async () => {
        let calls = wire({ route: (method, url) => method === 'PATCH' && url === PB + '/covers/cover' ? jsonResponse(200, { compositionObjectId: 'comp-1', purpose: 'cover', revision: 2, nodeStale: true, nodeStatus: 'stale', updated: true, composition: compositionWire({ titleSafeBand: 'top', revision: 2 }) }) : null });
        let b = await wf.getCover(BOOK, 'COVER');
        expect(b.purpose).toBe('COVER');
        expect(b.sceneObjectId).toBeNull();
        expect(b.composition.titleSafeBand).toBe('unknown');
        expect(b.cover).toMatchObject({ purpose: 'COVER', nodeObjectId: null, chainLength: 0 });
        let r = await wf.patchCover(BOOK, 'cover', { titleSafeBand: 'top' });
        expect(calls[1]).toMatchObject({ method: 'PATCH', url: PB + '/covers/cover', body: { titleSafeBand: 'top' } });
        expect(r).toMatchObject({ purpose: 'COVER', revision: 2, nodeStale: true, nodeStatus: 'stale', updated: true });
        expect(r.composition.titleSafeBand).toBe('top');
        let p = await wf.planCover(BOOK, 'cover');
        expect(calls[2]).toMatchObject({ method: 'POST', url: PB + '/covers/cover/plan', body: {} });
        expect(p.reserved).toEqual({ x: 0, y: 0.75, w: 1, h: 0.25 });
        await wf.getCovers(BOOK);
        expect(calls[3]).toMatchObject({ method: 'GET', url: PB + '/covers' });
    });
});

// ─────────────────────────────── compose helpers (pure) ───────────────────────────────

describe('cover board helpers', () => {
    it('coverStateText: no entry, never rendered, then "<status> · pinned · N renders · <review>"', () => {
        expect(cmp.coverStateText(null)).toBe('No composition');
        expect(cmp.coverStateText(wf.normalizeCoverEntry(coverEntryWire()))).toBe('Not rendered yet');
        expect(cmp.coverStateText(wf.normalizeCoverEntry(renderedEntryWire()))).toBe('DONE_UNVERIFIED · 2 renders · approved');
        expect(cmp.coverStateText(wf.normalizeCoverEntry(renderedEntryWire({ pinned: true, chainLength: 1, reviewStatus: 'generated' })))).toBe('DONE_UNVERIFIED · pinned · 1 render · generated');
        expect(cmp.coverStateText(wf.normalizeCoverEntry(renderedEntryWire({ nodeStatus: null, chainLength: 0, reviewStatus: null })))).toBe('UNKNOWN · 0 renders');
    });
    it('isRenderedStatus: DONE and DONE_UNVERIFIED (any case) mean "a render finished"; STALE / FAILED / READY / none do not', () => {
        // A RENDER_PLAN cover node closes DONE_UNVERIFIED on the real stack (P9-4 run 4) — a DONE-only check left the
        // strip reading "Render" (no force=true) after a finished render.
        ['DONE', 'DONE_UNVERIFIED', 'done', 'done_unverified'].forEach(s => expect(cmp.isRenderedStatus(s), s).toBe(true));
        ['STALE', 'FAILED', 'READY', 'RUNNING', 'UNKNOWN', '', null, undefined].forEach(s => expect(cmp.isRenderedStatus(s), String(s)).toBe(false));
    });
    it('renderCounterText: submitting, "cur of tot · phase", then Rendered / Failed / Cancelled', () => {
        expect(cmp.renderCounterText(null)).toBe('Submitting…');
        expect(cmp.renderCounterText({ current: 1, total: 3, phase: 'render' })).toBe('1 of 3 · render');
        expect(cmp.renderCounterText({ current: 0, total: 0 })).toBe('Rendering');
        expect(cmp.renderCounterText({ phase: 'queued' })).toBe('Rendering · queued');
        expect(cmp.renderCounterText({ terminal: true })).toBe('Rendered');
        expect(cmp.renderCounterText({ terminal: true, error: 'SD backend not configured' })).toBe('Failed: SD backend not configured');
        expect(cmp.renderCounterText({ terminal: true, cancelled: true, error: 'x' })).toBe('Cancelled');
    });
    it('createCoverText per mode, existing, not-written and warnings', () => {
        expect(cmp.createCoverText(null, 'Cover')).toBe('Cover created');
        expect(cmp.createCoverText({ written: true, mode: 'MANUAL' }, 'Title page')).toBe('Title page created - fill in the setting and camera, then Render');
        expect(cmp.createCoverText({ written: true, mode: 'FROM_SCENE' }, 'Cover')).toBe('Cover created from the scene');
        expect(cmp.createCoverText({ written: true, mode: 'AUTO', actors: [{}, {}], elements: [{}], castTier: 'HERO' }, 'Cover')).toBe('Cover created (2 actors, 1 elements, cast hero)');
        expect(cmp.createCoverText({ written: true, mode: 'AUTO', warnings: ['Unresolved: Bob'] }, 'Cover')).toBe('Cover created (0 actors, 0 elements) — Unresolved: Bob');
        expect(cmp.createCoverText({ existingReturned: true, written: false }, 'Cover')).toBe('Cover already exists - showing it');
        expect(cmp.createCoverText({ written: false, llmFailure: true, failureReason: 'The LLM returned no JSON' }, 'Cover')).toBe('Cover was not created: The LLM returned no JSON');
        expect(cmp.createCoverText({ written: false }, null)).toBe('Cover was not created');
    });
    it('coverImageUrl builds the MediaServlet path from the page-view ref, null without both path fields; isNothingToRenderError matches the empty-manual-cover lastError', () => {
        expect(cmp.coverImageUrl(wf.normalizeCoverImageRef(imageRefWire(2)))).toBe(MEDIA + '/Pb/covers/cover-r2.png');
        expect(cmp.coverImageUrl({ artifactObjectId: 'art-1', dataObjectId: 'd-1' })).toBeNull();
        expect(cmp.coverImageUrl({ imageGroupPath: '/Pb/covers' })).toBeNull();
        expect(cmp.coverImageUrl(null)).toBeNull();
        expect(cmp.isNothingToRenderError({ lastError: 'Cover composition has no description to render from' })).toBe(true);
        expect(cmp.isNothingToRenderError({ lastError: 'SD backend not configured' })).toBe(false);
        expect(cmp.isNothingToRenderError({ lastError: null })).toBe(false);
        expect(cmp.isNothingToRenderError(null)).toBe(false);
        expect(cmp.COVER_COMPOSITION_KEYS).toEqual(cmp.COMPOSITION_KEYS.concat(['titleSafeBand']));
    });
});

// ─────────────────────────────── cover mode: loading ───────────────────────────────

describe('cover mode init', () => {
    it('reads the pages, the cover board, its outfits, then the covers list and a fresh plan — cover-keyed, no scene', async () => {
        let calls = wire();
        initCover('cover');
        await waitFor(() => st().board && st().plan && st().covers && !st().planLoading);
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/pages', BOARD_READ, OUTFITS_READ, COVERS_READ, PLAN_READ]);
        expect(calls[4].body).toEqual({});
        let s = st();
        expect(s.coverPurpose).toBe('COVER');
        expect(s.sceneObjectId).toBeNull();
        expect(s.pages).toEqual(PAGES);
        expect(s.board.purpose).toBe('COVER');
        expect(s.board.compositionObjectId).toBe('comp-1');
        expect(s.board.composition.settingText).toBe('a lighthouse at dawn');
        expect(s.cover).toMatchObject({ purpose: 'COVER', nodeObjectId: null, chainLength: 0 });
        expect(s.covers.COVER.compositionObjectId).toBe('comp-1');
        expect(s.covers.TITLE_PAGE).toBeNull();
        expect(s.outfits.a1.apparelName).toBe('Sea coat');
        expect(s.plan.reserved).toEqual({ x: 0, y: 0.75, w: 1, h: 0.25 });
        expect(s.edits).toEqual({ composition: null, actors: {}, elements: {} });
    });
    it('a route word selects the purpose (title-page → TITLE_PAGE); an unknown word falls to COVER', async () => {
        let calls = wire({ route: (method, url) => method === 'GET' && url === PB + '/covers/title-page' ? jsonResponse(200, coverBoardWire({ purpose: 'title_page', cover: coverEntryWire({ purpose: 'title_page' }) })) : (method === 'POST' && url === PB + '/covers/title-page/plan' ? jsonResponse(200, planWire()) : null) });
        initCover('title-page');
        await waitFor(() => st().board && st().plan && !st().planLoading);
        expect(st().coverPurpose).toBe('TITLE_PAGE');
        expect(st().board.purpose).toBe('TITLE_PAGE');
        expect(seqOf(calls)).toContain('GET ' + PB + '/covers/title-page');
        expect(seqOf(calls)).toContain('POST ' + PB + '/covers/title-page/plan');
        expect(textOf(one(view(), 'pb-cmp-purpose-title-page'))).toContain('Title page');
        expect(one(view(), 'pb-cmp-purpose-title-page').attrs['data-active']).toBe('true');

        cmp.__setComposeStateForTest({ bookObjectId: null, coverPurpose: null, board: null });
        calls = wire();
        initCover('not-a-purpose');
        await waitFor(() => st().board && st().plan && !st().planLoading);
        expect(st().coverPurpose).toBe('COVER');
        expect(seqOf(calls)).toContain(BOARD_READ);
    });
    it('the cover 404 ("has no COVER composition - create it first") is the Create empty state, not an error; the plan 400 is shown beside it', async () => {
        wire({ route: (method, url) => {
            if (method === 'GET' && url === PB + '/covers/cover') return jsonResponse(404, { error: 'Book harlots-eight has no COVER composition - create it first' });
            if (method === 'POST' && url === PB + '/covers/cover/plan') return jsonResponse(404, { error: 'Book harlots-eight has no COVER composition - create it first' });
            return null;
        }, covers: () => coverViewWire({ covers: {} }) });
        initCover('cover');
        await waitFor(() => st().noComposition && st().planError && st().covers);
        let s = st();
        expect(s.error).toBeNull();
        expect(s.board).toBeNull();
        expect(s.cover).toBeNull();
        expect(s.noCompositionMessage).toBe('(404) Book harlots-eight has no COVER composition - create it first');
        let v = view();
        none(v, 'pb-cmp-error');
        none(v, 'pb-cmp-cover');
        none(v, 'pb-cmp-camera');
        none(v, 'pb-cmp-compose-scene');
        one(v, 'pb-cmp-empty');
        expect(textOf(one(v, 'pb-cmp-empty-message'))).toBe('(404) Book harlots-eight has no COVER composition - create it first');
        expect(textOf(one(v, 'pb-cmp-create-cover-open'))).toContain('Create cover…');
        expect(textOf(one(v, 'pb-cmp-plan-error'))).toContain('has no COVER composition');
        expect(one(v, 'pb-cmp-purpose-cover').attrs['data-exists']).toBe('false');
    });
    it('a plan 400 ("nothing to render from" on an empty manual cover) is a plan error, the board still loads', async () => {
        wire({ route: (method, url) => method === 'POST' && url === PB + '/covers/cover/plan' ? jsonResponse(400, { error: 'Cover composition has no description to render from' }) : null });
        initCover('cover');
        await waitFor(() => st().board && st().planError);
        expect(st().plan).toBeNull();
        expect(st().planError).toBe('(400) Cover composition has no description to render from');
        let v = view();
        one(v, 'pb-cmp-camera');
        one(v, 'pb-cmp-cover');
        expect(textOf(one(v, 'pb-cmp-plan-error'))).toBe('(400) Cover composition has no description to render from');
    });
    it('the same book + purpose with a board loaded is a no-op; a purpose change resets the board and the render run but keeps the pages and the covers list', async () => {
        let calls = wire();
        initCover('cover');
        await waitFor(() => st().board && st().plan && st().covers && !st().planLoading);
        let before = calls.length;
        cmp.__setComposeStateForTest({ renderRun: { jobId: 'j1', purpose: 'COVER', job: null, error: null } });
        initCover('cover');
        await new Promise(r => setTimeout(r, 10));
        expect(calls.length).toBe(before);
        expect(st().renderRun).not.toBeNull();

        calls = wire({ route: (method, url) => method === 'GET' && url === PB + '/covers/series-art' ? jsonResponse(404, { error: 'Book harlots-eight has no SERIES_ART composition - create it first' }) : (method === 'POST' && url === PB + '/covers/series-art/plan' ? jsonResponse(404, { error: 'Book harlots-eight has no SERIES_ART composition - create it first' }) : null) });
        initCover('series-art');
        await waitFor(() => st().noComposition && st().planError);
        expect(st().coverPurpose).toBe('SERIES_ART');
        expect(st().renderRun).toBeNull();
        expect(st().board).toBeNull();
        expect(st().pages).toEqual(PAGES);
        expect(st().covers.COVER.compositionObjectId).toBe('comp-1');
        expect(seqOf(calls)).not.toContain('GET ' + PB + '/pages');
        expect(textOf(one(view(), 'pb-cmp-create-cover-open'))).toContain('Create series art…');
    });
    it('switching from scene mode to cover mode on the same book drops the scene key and reads the cover board', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: 's1', pages: PAGES, board: wf.normalizeBoard({ bookObjectId: BOOK, sceneObjectId: 's1', compositionObjectId: 'comp-s1', composition: compositionWire({ objectId: 'comp-s1', purpose: 'scene' }), actors: [], elements: [] }) });
        let calls = wire();
        initCover('cover');
        await waitFor(() => st().board && st().board.purpose === 'COVER' && st().plan && !st().planLoading);
        expect(seqOf(calls)).not.toContain('GET ' + PB + '/pages');
        expect(seqOf(calls)).toContain(BOARD_READ);
        expect(st().coverPurpose).toBe('COVER');
        expect(st().board.compositionObjectId).toBe('comp-1');
    });
});

// ─────────────────────────────── cover mode: header, tabs, strip ───────────────────────────────

describe('cover mode rendering', () => {
    it('header: "Cover art", purpose tabs with the existing dot, the Scenes link; no stepper, no Compose, no Re-compose, no Covers link', () => {
        seedCover({ covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: coverEntryWire(), chapter_opener: coverEntryWire({ purpose: 'chapter_opener', compositionObjectId: 'comp-3' }) } })).covers });
        let v = view();
        expect(textOf(one(v, 'pb-cmp-title'))).toBe('Cover art');
        let tabs = one(v, 'pb-cmp-purpose-tabs');
        expect(tabs.children.map(t => t.attrs['data-testid'])).toEqual(['pb-cmp-purpose-cover', 'pb-cmp-purpose-title-page', 'pb-cmp-purpose-chapter-opener', 'pb-cmp-purpose-series-art']);
        expect(tabs.children.map(t => t.attrs['data-active'])).toEqual(['true', 'false', 'false', 'false']);
        expect(tabs.children.map(t => t.attrs['data-exists'])).toEqual(['true', 'false', 'true', 'false']);
        expect(textOf(one(v, 'pb-cmp-purpose-chapter-opener'))).toContain('Chapter opener');
        one(v, 'pb-cmp-scenes-link');
        none(v, 'pb-cmp-scene-select');
        none(v, 'pb-cmp-scene-prev');
        none(v, 'pb-cmp-compose-book');
        none(v, 'pb-cmp-cover-link');
        none(v, 'pb-cmp-recompose');
        none(v, 'pb-cmp-no-scenes');
        expect(textOf(one(v, 'pb-cmp-purpose'))).toBe('cover');
        expect(textOf(one(v, 'pb-cmp-status'))).not.toContain('location');
        one(v, 'pb-cmp-scenes-link').attrs.onclick();
        expect(m.route.set).toHaveBeenLastCalledWith('/picture-book/' + BOOK + '/compose');
        one(v, 'pb-cmp-purpose-title-page').attrs.onclick();
        expect(m.route.set).toHaveBeenLastCalledWith('/picture-book/' + BOOK + '/cover/title-page');
        m.route.set.mockClear();
        one(v, 'pb-cmp-purpose-cover').attrs.onclick();
        expect(m.route.set).not.toHaveBeenCalled();
    });
    it('without a covers list the tabs show every purpose as not-yet-known (no dot)', () => {
        seedCover({ covers: null });
        let tabs = one(view(), 'pb-cmp-purpose-tabs');
        expect(tabs.children.map(t => t.attrs['data-exists'])).toEqual(['false', 'false', 'false', 'false']);
    });
    it('scene mode shows the Covers link to /picture-book/{book}/cover and no purpose tabs', () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: 's1', coverPurpose: null, pages: PAGES, board: wf.normalizeBoard({ bookObjectId: BOOK, sceneObjectId: 's1', compositionObjectId: 'comp-s1', composition: compositionWire({ objectId: 'comp-s1', purpose: 'scene' }), actors: [], elements: [] }), plan: null });
        let v = view();
        expect(textOf(one(v, 'pb-cmp-title'))).toBe('Composition board');
        none(v, 'pb-cmp-purpose-tabs');
        none(v, 'pb-cmp-scenes-link');
        none(v, 'pb-cmp-cover');
        none(v, 'pb-cmp-title-band');
        one(v, 'pb-cmp-recompose');
        one(v, 'pb-cmp-cover-link').attrs.onclick();
        expect(m.route.set).toHaveBeenLastCalledWith('/picture-book/' + BOOK + '/cover');
    });
    it('strip, never rendered: "Not rendered yet", Render enabled, Approve disabled, no band chip, both tiles empty', () => {
        seedCover();
        let v = view();
        let strip = one(v, 'pb-cmp-cover');
        expect(strip.attrs['data-node-status']).toBe('');
        expect(strip.attrs['data-pinned']).toBe('false');
        expect(textOf(one(v, 'pb-cmp-cover-state'))).toBe('Not rendered yet');
        none(v, 'pb-cmp-cover-band');
        none(v, 'pb-cmp-cover-error');
        let render = one(v, 'pb-cmp-render-cover');
        expect(render.attrs.disabled).toBe(false);
        expect(textOf(render)).toContain('Render');
        expect(textOf(render)).not.toContain('Re-render');
        let approve = one(v, 'pb-cmp-approve-cover');
        expect(approve.attrs.disabled).toBe(true);
        expect(approve.attrs.title).toBe('Nothing to approve yet');
        expect(one(v, 'pb-cmp-cover-approved').attrs['data-artifact']).toBe('');
        expect(textOf(one(v, 'pb-cmp-cover-approved'))).toContain('none');
        expect(one(v, 'pb-cmp-cover-latest').attrs['data-artifact']).toBe('');
        expect(findAll(v, n => n.tag === 'img').length).toBe(0);
    });
    it('strip, rendered + approved == latest: DONE_UNVERIFIED state (what a RENDER_PLAN node closes with), band chip, Re-render, Approve disabled as already approved, one tile with the image', () => {
        seedCover({ board: coverBoardWire({ composition: compositionWire({ titleSafeBand: 'bottom' }), cover: renderedEntryWire({ latest: imageRefWire(1, { reviewStatus: 'approved' }), chainLength: 1 }) }) });
        let v = view();
        let strip = one(v, 'pb-cmp-cover');
        expect(strip.attrs['data-node-status']).toBe('DONE_UNVERIFIED');
        expect(textOf(one(v, 'pb-cmp-cover-state'))).toBe('DONE_UNVERIFIED · 1 render · approved');
        expect(textOf(one(v, 'pb-cmp-cover-band'))).toBe('band bottom');
        expect(textOf(one(v, 'pb-cmp-render-cover'))).toContain('Re-render');
        expect(one(v, 'pb-cmp-render-cover').attrs.disabled).toBe(false);
        let approve = one(v, 'pb-cmp-approve-cover');
        expect(approve.attrs.disabled).toBe(true);
        expect(approve.attrs.title).toBe('The latest render is approved');
        let tile = one(v, 'pb-cmp-cover-approved');
        expect(tile.attrs['data-artifact']).toBe('art-1');
        expect(textOf(tile)).toContain('Approved · rev 1 · approved');
        none(v, 'pb-cmp-cover-latest');
        let imgs = findAll(tile, n => n.tag === 'img');
        expect(imgs.length).toBe(1);
        expect(imgs[0].attrs.src).toBe(MEDIA + '/Pb/covers/cover-r1.png');
        expect(one(v, 'pb-cmp-title-band').attrs.value).toBe('bottom');
    });
    it('strip, latest newer than approved: both tiles, Approve enabled', () => {
        seedCover({ board: coverBoardWire({ cover: renderedEntryWire() }) });
        let v = view();
        expect(one(v, 'pb-cmp-cover-approved').attrs['data-artifact']).toBe('art-1');
        let latest = one(v, 'pb-cmp-cover-latest');
        expect(latest.attrs['data-artifact']).toBe('art-2');
        expect(textOf(latest)).toContain('Latest · rev 2 · generated');
        expect(findAll(latest, n => n.tag === 'img')[0].attrs.src).toBe(MEDIA + '/Pb/covers/cover-r2.png');
        let approve = one(v, 'pb-cmp-approve-cover');
        expect(approve.attrs.disabled).toBe(false);
        expect(approve.attrs.title).toBe('Approve the latest render as the cover');
    });
    it('strip, pinned: Render disabled with the unpin hint, the state names it, data-pinned', () => {
        seedCover({ board: coverBoardWire({ cover: renderedEntryWire({ pinned: true }) }) });
        let v = view();
        expect(one(v, 'pb-cmp-cover').attrs['data-pinned']).toBe('true');
        expect(textOf(one(v, 'pb-cmp-cover-state'))).toBe('DONE_UNVERIFIED · pinned · 2 renders · approved');
        let render = one(v, 'pb-cmp-render-cover');
        expect(render.attrs.disabled).toBe(true);
        expect(render.attrs.title).toBe('Unpin the node from the review queue first');
    });
    it('strip, last render failed on an empty manual cover: the error line plus the fill-in hint; another error has no hint', () => {
        seedCover({ board: coverBoardWire({ cover: coverEntryWire({ nodeObjectId: 'n-cover', nodeStatus: 'failed', chainLength: 0, lastError: 'Cover composition has no description to render from' }) }) });
        let v = view();
        expect(textOf(one(v, 'pb-cmp-cover-state'))).toBe('FAILED · 0 renders');
        expect(textOf(one(v, 'pb-cmp-cover-error'))).toContain('Last render: Cover composition has no description to render from');
        expect(textOf(one(v, 'pb-cmp-cover-hint'))).toBe('Fill in a setting, an action or a prompt override below and save, then Render again.');
        seedCover({ board: coverBoardWire({ cover: coverEntryWire({ nodeObjectId: 'n-cover', nodeStatus: 'failed', lastError: 'SD backend not configured' }) }) });
        v = view();
        one(v, 'pb-cmp-cover-error');
        none(v, 'pb-cmp-cover-hint');
    });
    it('the camera section carries the title-safe band select (none / top / bottom) and the band note only in cover mode', () => {
        seedCover();
        let v = view();
        let band = one(v, 'pb-cmp-title-band');
        expect(band.attrs.value).toBe('');
        expect(band.children.map(o => o.attrs.value)).toEqual(['', 'none', 'top', 'bottom']);
        expect(textOf(one(v, 'pb-cmp-camera'))).toContain('reserved for the title');
    });
});

// ─────────────────────────────── cover mode: writes ───────────────────────────────

describe('cover mode writes', () => {
    it('Create dialog, Manual: POST /covers/cover {mode:"manual"}, the toast, then the board is re-read', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: null, coverPurpose: 'COVER', pages: PAGES, board: null, noComposition: true, noCompositionMessage: '(404) Book harlots-eight has no COVER composition - create it first', busy: {}, covers: wf.normalizeCoverView(coverViewWire({ covers: {} })).covers });
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/covers/cover' ? jsonResponse(200, createResultWire()) : null });
        one(view(), 'pb-cmp-create-cover-open').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=pb-cmp-create-cover]')).not.toBeNull();
        expect(container.querySelector('[data-testid=pb-cmp-create-auto]').checked).toBe(true);
        expect(container.querySelector('[data-testid=pb-cmp-create-from-scene]').disabled).toBe(false);
        expect(container.querySelector('[data-testid=pb-cmp-create-scene]')).toBeNull();
        let manual = container.querySelector('[data-testid=pb-cmp-create-manual]');
        manual.checked = true;
        manual.dispatchEvent(new Event('change'));
        container.querySelector('[data-testid=pb-cmp-create-submit]').click();
        await waitFor(() => calls.some(c => c.method === 'POST' && c.url === PB + '/covers/cover'));
        expect(calls[0].body).toEqual({ mode: 'manual' });
        await settled(calls);
        m.render(container, null);
        expect(toasts()).toEqual([['success', 'Cover created - fill in the setting and camera, then Render']]);
        expect(reads(calls)).toEqual([BOARD_READ, OUTFITS_READ, COVERS_READ, PLAN_READ]);
        expect(st().noComposition).toBe(false);
        expect(st().board.compositionObjectId).toBe('comp-1');
        one(view(), 'pb-cmp-cover');
    });
    it('Create dialog, Auto with a picked chat config sends chatConfigName; From a scene sends the picked sceneObjectId', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: null, coverPurpose: 'COVER', pages: PAGES, board: null, noComposition: true, busy: {}, chatConfigRef: { name: 'e2e-chapbook-llm', objectId: 'cc-e2e' } });
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/covers/cover' ? jsonResponse(200, createResultWire({ mode: 'auto', source: 'llm', castTier: 'hero', actors: [actorWire()], elements: [], warnings: ['Unresolved: Bob'] })) : null });
        one(view(), 'pb-cmp-create-cover-open').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=pb-cmp-create-cover]').textContent).toContain('Chat config: e2e-chapbook-llm');
        container.querySelector('[data-testid=pb-cmp-create-submit]').click();
        await waitFor(() => calls.some(c => c.method === 'POST' && c.url === PB + '/covers/cover'));
        expect(calls[0].body).toEqual({ mode: 'auto', chatConfigName: 'e2e-chapbook-llm' });
        await settled(calls);
        m.render(container, null);
        Dialog._reset();
        expect(toasts()).toEqual([['success', 'Cover created (1 actors, 0 elements, cast hero) — Unresolved: Bob']]);

        cmp.__setComposeStateForTest({ board: null, noComposition: true, busy: {}, chatConfigRef: null });
        calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/covers/cover' ? jsonResponse(200, createResultWire({ mode: 'from_scene', sourceSceneObjectId: 's2' })) : null });
        one(view(), 'pb-cmp-create-cover-open').attrs.onclick();
        container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        let fromScene = container.querySelector('[data-testid=pb-cmp-create-from-scene]');
        fromScene.checked = true;
        fromScene.dispatchEvent(new Event('change'));
        // the container is not mounted, so the dialog's redraw must be driven by hand
        m.render(container, Dialog.loadDialogs());
        let select = container.querySelector('[data-testid=pb-cmp-create-scene]');
        expect(select).not.toBeNull();
        expect(Array.from(select.options).map(o => o.value)).toEqual(['s1', 's2']);
        select.value = 's2';
        select.dispatchEvent(new Event('change'));
        container.querySelector('[data-testid=pb-cmp-create-submit]').click();
        await waitFor(() => calls.some(c => c.method === 'POST' && c.url === PB + '/covers/cover'));
        expect(calls[0].body).toEqual({ mode: 'from_scene', sceneObjectId: 's2' });
        await settled(calls);
        m.render(container, null);
        expect(toasts()[1]).toEqual(['success', 'Cover created from the scene']);
    });
    it('Create dialog on a book with no pages: From a scene is disabled; an existing cover comes back as "already exists"', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: null, coverPurpose: 'COVER', pages: [], board: null, noComposition: true, busy: {} });
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/covers/cover' ? jsonResponse(200, createResultWire({ created: false, existingReturned: true, written: false })) : null, pages: () => [] });
        one(view(), 'pb-cmp-create-cover-open').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        expect(container.querySelector('[data-testid=pb-cmp-create-from-scene]').disabled).toBe(true);
        expect(container.querySelector('[data-testid=pb-cmp-create-cover]').textContent).toContain('The book has no pages to clone from.');
        container.querySelector('[data-testid=pb-cmp-create-submit]').click();
        await waitFor(() => calls.some(c => c.method === 'POST'));
        expect(calls[0].body).toEqual({ mode: 'auto' });
        await settled(calls);
        m.render(container, null);
        expect(toasts()).toEqual([['success', 'Cover already exists - showing it']]);
    });
    it('a create refusal (409 not V3) is an error toast and the empty state stays', async () => {
        cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: null, coverPurpose: 'COVER', pages: PAGES, board: null, noComposition: true, noCompositionMessage: 'x', busy: {} });
        let calls = wire({ route: (method, url) => {
            if (method === 'POST' && url === PB + '/covers/cover') return jsonResponse(409, { error: 'Book harlots-eight is not on the V3 render path' });
            if (method === 'GET' && url === PB + '/covers/cover') return jsonResponse(404, { error: 'Book harlots-eight has no COVER composition - create it first' });
            if (method === 'POST' && url === PB + '/covers/cover/plan') return jsonResponse(404, { error: 'Book harlots-eight has no COVER composition - create it first' });
            return null;
        } });
        one(view(), 'pb-cmp-create-cover-open').attrs.onclick();
        let container = document.createElement('div');
        m.render(container, Dialog.loadDialogs());
        container.querySelector('[data-testid=pb-cmp-create-submit]').click();
        await waitFor(() => Object.keys(st().busy).length === 0 && st().planError && calls.filter(c => c.url === PB + '/covers').length >= 1);
        m.render(container, null);
        expect(toasts()).toEqual([['error', '(409) Book harlots-eight is not on the V3 render path']]);
        expect(st().noComposition).toBe(true);
        one(view(), 'pb-cmp-create-cover-open');
    });
    it('camera Save in cover mode PATCHes /covers/cover with the changed keys including titleSafeBand, then re-reads', async () => {
        seedCover();
        let calls = wire({ route: (method, url) => method === 'PATCH' && url === PB + '/covers/cover' ? jsonResponse(200, { compositionObjectId: 'comp-1', purpose: 'cover', revision: 2, nodeStale: false, updated: true, composition: compositionWire({ titleSafeBand: 'top', revision: 2 }) }) : null });
        let v = view();
        one(v, 'pb-cmp-title-band').attrs.onchange({ target: { value: 'top' } });
        one(v, 'pb-cmp-action').attrs.oninput({ target: { value: 'the keeper climbs the stair' } });
        one(v, 'pb-cmp-save-camera').attrs.onclick();
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('PATCH ' + PB + '/covers/cover');
        expect(calls[0].body).toEqual({ action: 'the keeper climbs the stair', titleSafeBand: 'top' });
        expect(seqOf(calls)).not.toContain('PATCH ' + PB + '/scene/s1/composition');
        expect(reads(calls)).toEqual([BOARD_READ, OUTFITS_READ, COVERS_READ, PLAN_READ]);
        expect(toasts()).toEqual([['success', 'Composition saved']]);
        expect(st().lastEdit).toEqual({ revision: 2, nodeStale: false, nodeStatus: null });
    });
    it('a rendered cover edited: the PATCH result marks the node stale and the status shows it', async () => {
        seedCover({ board: coverBoardWire({ cover: renderedEntryWire() }) });
        let calls = wire({ route: (method, url) => method === 'PATCH' && url === PB + '/covers/cover' ? jsonResponse(200, { compositionObjectId: 'comp-1', purpose: 'cover', revision: 2, nodeStale: true, nodeStatus: 'stale', updated: true }) : null, board: () => coverBoardWire({ cover: renderedEntryWire({ nodeStatus: 'stale' }) }) });
        let v = view();
        one(v, 'pb-cmp-mood').attrs.oninput({ target: { value: 'stormy' } });
        one(v, 'pb-cmp-save-camera').attrs.onclick();
        await settled(calls);
        expect(calls[0].body).toEqual({ mood: 'stormy' });
        v = view();
        expect(textOf(one(v, 'pb-cmp-stale'))).toBe('render stale · stale');
        expect(one(v, 'pb-cmp-stale').attrs.title).toBe('The cover render node is stale after this edit');
        expect(one(v, 'pb-cmp-cover').attrs['data-node-status']).toBe('STALE');
        expect(textOf(one(v, 'pb-cmp-render-cover'))).toContain('Render');
        expect(textOf(one(v, 'pb-cmp-render-cover'))).not.toContain('Re-render');
    });
});

// ─────────────────────────────── cover mode: render job + approve ───────────────────────────────

describe('cover render and approve', () => {
    it('Render: POST .../render (no force on a never-rendered cover) → 202 → the run panel follows the job → terminal toast → re-read', async () => {
        seedCover();
        // the job stays at its first progress tick until the test has looked at the panel (a 1 ms poll interval
        // would otherwise race past it under suite load)
        let finish = false;
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/covers/cover/render' ? jsonResponse(202, { bookObjectId: BOOK, purpose: 'cover', compositionObjectId: 'comp-1', nodeObjectId: 'n-cover', nodeMaterialized: true, jobId: 'j1', runObjectId: 'run-1' }) : null,
            job: id => finish ? jsonResponse(200, { jobId: id, terminal: true, current: 3, total: 3 }) : jsonResponse(200, { jobId: id, terminal: false, current: 1, total: 3, phase: 'render' }),
            board: calls => calls.some(c => c.method === 'POST' && /render/.test(c.url)) ? coverBoardWire({ cover: renderedEntryWire({ approved: null, reviewStatus: 'generated', selectedArtifactObjectId: 'art-2', latest: imageRefWire(2), chainLength: 1 }) }) : coverBoardWire()
        });
        one(view(), 'pb-cmp-render-cover').attrs.onclick();
        await waitFor(() => st().renderRun && st().renderRun.job && st().renderRun.job.current === 1);
        expect(calls[0]).toMatchObject({ method: 'POST', url: PB + '/covers/cover/render' });
        expect(calls[0].rawBody).toBeUndefined();
        expect(toasts()[0]).toEqual(['info', 'Cover render started (render node created)']);
        let v = view();
        let run = one(v, 'pb-cmp-render-run');
        expect(run.attrs['data-run-terminal']).toBe('false');
        expect(textOf(one(v, 'pb-cmp-render-counter'))).toBe('1 of 3 · render');
        expect(textOf(run)).toContain('Render cover');
        let render = one(v, 'pb-cmp-render-cover');
        expect(render.attrs.disabled).toBe(true);
        expect(textOf(render)).toContain('Rendering…');
        expect(st().renderRun).toMatchObject({ jobId: 'j1', purpose: 'COVER', nodeObjectId: 'n-cover', runObjectId: 'run-1' });
        finish = true;
        await waitFor(() => st().renderRun.job.terminal);
        await settled(calls);
        expect(toasts()[1]).toEqual(['success', 'Cover: Rendered']);
        expect(reads(calls)).toEqual([BOARD_READ, OUTFITS_READ, COVERS_READ, PLAN_READ]);
        expect(st().renderAbort).toBeNull();
        v = view();
        expect(one(v, 'pb-cmp-render-run').attrs['data-run-terminal']).toBe('true');
        expect(textOf(one(v, 'pb-cmp-render-counter'))).toBe('Rendered');
        expect(one(v, 'pb-cmp-cover-latest').attrs['data-artifact']).toBe('art-2');
        expect(one(v, 'pb-cmp-approve-cover').attrs.disabled).toBe(false);
        one(v, 'pb-cmp-render-dismiss').attrs.onclick();
        expect(st().renderRun).toBeNull();
        none(view(), 'pb-cmp-render-run');
    });
    it('Re-render on a current (DONE_UNVERIFIED) cover sends ?force=true; a failed job is a warn toast with the error', async () => {
        seedCover({ board: coverBoardWire({ cover: renderedEntryWire() }) });
        let calls = wire({
            route: (method, url) => method === 'POST' && url === PB + '/covers/cover/render?force=true' ? jsonResponse(202, { jobId: 'j2', nodeObjectId: 'n-cover', nodeMaterialized: false }) : null,
            job: id => jsonResponse(200, { jobId: id, terminal: true, error: 'SD backend not configured' })
        });
        one(view(), 'pb-cmp-render-cover').attrs.onclick();
        await waitFor(() => st().renderRun && st().renderRun.job && st().renderRun.job.terminal);
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('POST ' + PB + '/covers/cover/render?force=true');
        expect(toasts()).toEqual([['info', 'Cover render started'], ['warn', 'Cover: Failed: SD backend not configured']]);
        expect(textOf(one(view(), 'pb-cmp-render-counter'))).toBe('Failed: SD backend not configured');
    });
    it('a 409 (pinned / already rendering) is a warn toast, nothing is followed; a 500 (no SD) is an error toast', async () => {
        seedCover();
        let calls = wire({ route: (method, url) => method === 'POST' && url === PB + '/covers/cover/render' ? jsonResponse(409, { error: 'Node n-cover is pinned; unpin it from the review queue first' }) : null });
        one(view(), 'pb-cmp-render-cover').attrs.onclick();
        await waitFor(() => Object.keys(st().busy).length === 0 && calls.length === 1);
        await new Promise(r => setTimeout(r, 10));
        expect(toasts()).toEqual([['warn', '(409) Node n-cover is pinned; unpin it from the review queue first']]);
        expect(st().renderRun).toBeNull();
        expect(calls.length).toBe(1);

        wire({ route: (method, url) => method === 'POST' && url === PB + '/covers/cover/render' ? jsonResponse(500, { error: 'No SD backend is configured for this organization' }) : null });
        one(view(), 'pb-cmp-render-cover').attrs.onclick();
        await waitFor(() => toasts().length === 2);
        expect(toasts()[1]).toEqual(['error', '(500) No SD backend is configured for this organization']);
        expect(st().renderRun).toBeNull();
    });
    it('Approve latest: PUT /{book}/artifact/{latest}/approve, the toast, then the re-read shows it approved', async () => {
        seedCover({ board: coverBoardWire({ cover: renderedEntryWire() }) });
        let calls = wire({
            route: (method, url) => method === 'PUT' && url === PB + '/artifact/art-2/approve' ? jsonResponse(200, { objectId: 'art-2', reviewStatus: 'approved', revision: 2 }) : null,
            board: () => coverBoardWire({ cover: renderedEntryWire({ approved: imageRefWire(2, { reviewStatus: 'approved' }), selectedArtifactObjectId: 'art-2' }) })
        });
        one(view(), 'pb-cmp-approve-cover').attrs.onclick();
        expect(st().busy.approve).toBe(true);
        expect(textOf(one(view(), 'pb-cmp-approve-cover'))).toContain('Approving…');
        await settled(calls);
        expect(seqOf(calls)[0]).toBe('PUT ' + PB + '/artifact/art-2/approve');
        expect(toasts()).toEqual([['success', 'Cover approved']]);
        expect(reads(calls)).toEqual([BOARD_READ, OUTFITS_READ, COVERS_READ, PLAN_READ]);
        let v = view();
        expect(one(v, 'pb-cmp-cover-approved').attrs['data-artifact']).toBe('art-2');
        none(v, 'pb-cmp-cover-latest');
        expect(one(v, 'pb-cmp-approve-cover').attrs.disabled).toBe(true);
    });
    it('Approve with nothing to approve is a no-op (no request)', async () => {
        seedCover();
        let calls = wire();
        one(view(), 'pb-cmp-approve-cover').attrs.onclick();
        await new Promise(r => setTimeout(r, 10));
        expect(calls.length).toBe(0);
        expect(toasts()).toEqual([]);
    });
    it('leaving the board (onremove) stops following the render job without cancelling it', async () => {
        seedCover();
        let polls = 0;
        wire({
            route: (method, url) => method === 'POST' && url === PB + '/covers/cover/render' ? jsonResponse(202, { jobId: 'j3', nodeObjectId: 'n-cover' }) : null,
            job: id => { polls++; return jsonResponse(200, { jobId: id, terminal: false, current: 0, total: 1 }); }
        });
        one(view(), 'pb-cmp-render-cover').attrs.onclick();
        await waitFor(() => polls >= 2);
        expect(st().renderAbort).not.toBeNull();
        cmp.composeView.onremove();
        let at = polls;
        await new Promise(r => setTimeout(r, 15));
        expect(polls).toBeLessThanOrEqual(at + 1);
        expect(st().renderAbort).toBeNull();
        expect(toasts().length).toBe(1);
    });
});

// ─────────────────────────────── readers: the cover page ───────────────────────────────

describe('PictureBook reader cover page', () => {
    let pbf;
    const PAGE_VIEW = [{ objectId: 's1', sceneIndex: 1, title: 'Quay', dataObjectId: 'd-p1', imageGroupPath: '/Pb/pages', imageName: 'p1.png' }];
    beforeEach(async () => {
        pbf = await import('../features/pictureBook.js');
        pbf.__setPbHealthStateForTest({ pb2BookObjectId: 'B1', pb2BookName: 'Harlot\'s Eight', pb2Pages: PAGE_VIEW, pb2Covers: null });
    });
    afterEach(() => { pbf.__setPbHealthStateForTest({ pb2Pages: [], pb2Covers: null }); });

    it('without a covers view the first page is the dimmed backdrop (source=page), title centered', () => {
        let v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-source']).toBe('page');
        expect(v.attrs['data-pb2-cover-band']).toBe('');
        expect(v.attrs.className).toContain('justify-center');
        let img = findAll(v, n => n.attrs['data-pb2-cover-image'])[0];
        expect(img.attrs.src).toBe(MEDIA + '/Pb/pages/p1.png');
        expect(img.attrs.className).toContain('opacity-40');
        expect(textOf(v)).toContain('Harlot\'s Eight');
    });
    it('an APPROVED cover render is the cover (source=cover, undimmed) with the title block in the band', () => {
        pbf.__setPbHealthStateForTest({ pb2Covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire() } })) });
        let v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-source']).toBe('cover');
        expect(v.attrs['data-pb2-cover-band']).toBe('BOTTOM');
        expect(v.attrs.className).toContain('justify-end');
        let img = findAll(v, n => n.attrs['data-pb2-cover-image'])[0];
        expect(img.attrs.src).toBe(MEDIA + '/Pb/covers/cover-r1.png');
        expect(img.attrs.className).toContain('opacity-100');
        expect(findAll(v, n => /bg-black\/40/.test(n.attrs.className || '')).length).toBe(1);
        pbf.__setPbHealthStateForTest({ pb2Covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ titleSafeBand: 'top' }) } })) });
        v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-band']).toBe('TOP');
        expect(v.attrs.className).toContain('justify-start');
        pbf.__setPbHealthStateForTest({ pb2Covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ titleSafeBand: 'none' }) } })) });
        v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-band']).toBe('');
        expect(v.attrs.className).toContain('justify-center');
    });
    it('a merely-latest (unapproved) render is NOT the cover: the reader falls back to the first page', () => {
        pbf.__setPbHealthStateForTest({ pb2Covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ approved: null, reviewStatus: 'generated', selectedArtifactObjectId: 'art-2' }) } })) });
        let v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-source']).toBe('page');
        expect(findAll(v, n => n.attrs['data-pb2-cover-image'])[0].attrs.src).toBe(MEDIA + '/Pb/pages/p1.png');
    });
    it('no pages and no approved cover: no image (source=none); an approved cover alone still shows the cover', () => {
        pbf.__setPbHealthStateForTest({ pb2Pages: [], pb2Covers: wf.normalizeCoverView(coverViewWire()) });
        let v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-source']).toBe('none');
        expect(findAll(v, n => n.attrs['data-pb2-cover-image']).length).toBe(0);
        pbf.__setPbHealthStateForTest({ pb2Pages: [], pb2Covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire() } })) });
        v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-source']).toBe('cover');
        expect(findAll(v, n => n.attrs['data-pb2-cover-image'])[0].attrs.src).toBe(MEDIA + '/Pb/covers/cover-r1.png');
    });
    it('238 d: with a real cover the frame is a fixed-aspect square showing the whole render and the title block is slim, in the band (TOP / BOTTOM / none); the page-1 fallback keeps the wide dimmed backdrop', () => {
        ['top', 'bottom', 'none'].forEach(band => {
            pbf.__setPbHealthStateForTest({ pb2Covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ titleSafeBand: band }) } })) });
            let v = pbf.__renderPb2CoverForTest();
            expect(v.attrs['data-pb2-cover-source']).toBe('cover');
            expect(v.attrs['data-pb2-cover-band']).toBe(band === 'none' ? '' : band.toUpperCase());
            expect(v.attrs.className).toContain(band === 'top' ? 'justify-start' : (band === 'bottom' ? 'justify-end' : 'justify-center'));
            assertCoverFrame(v, 'data-pb2-cover-image', 'data-pb2-cover-count');
            expect(textOf(v)).toContain('Harlot\'s Eight');
        });
        pbf.__setPbHealthStateForTest({ pb2Covers: null });
        let v = pbf.__renderPb2CoverForTest();
        expect(v.attrs['data-pb2-cover-source']).toBe('page');
        assertFallbackFrame(v, 'data-pb2-cover-image', 'data-pb2-cover-count');
    });
});

// 238 d (P9-8): the real-cover frame is a square sized to the viewport height (never min-h-[60vh]), the image is
// shown whole (object-contain, never object-cover) and undimmed, and the one scrimmed title block is the slim
// one (no p-8, no text-4xl; the count and Begin on one row).
function assertCoverFrame(v, imgAttr, countAttr) {
    let root = v.attrs.className;
    expect(root).toContain('aspect-square');
    expect(root).toContain('max-w-[75vh]');
    expect(root).not.toContain('min-h-[60vh]');
    let img = findAll(v, n => n.attrs[imgAttr])[0];
    expect(img.attrs.className).toContain('object-contain');
    expect(img.attrs.className).not.toContain('object-cover');
    expect(img.attrs.className).toContain('opacity-100');
    let blocks = findAll(v, n => /bg-black\/40/.test(n.attrs.className || ''));
    expect(blocks.length).toBe(1);
    expect(blocks[0].attrs.className).toContain('py-3');
    expect(blocks[0].attrs.className).not.toContain('p-8');
    let h1 = findAll(v, n => n.tag === 'h1')[0];
    expect(h1.attrs.className).toContain('text-2xl');
    expect(h1.attrs.className).not.toContain('text-4xl');
    let count = findAll(v, n => n.attrs[countAttr] || (n.tag === 'p' && /Page/.test(textOf(n))))[0];
    expect(count.attrs.className).toContain('text-sm');
    let button = findAll(v, n => n.tag === 'button')[0];
    expect(button.attrs.className).toContain('py-1');
    expect(button.attrs.className).not.toContain('mt-8');
    expect(textOf(button)).toContain('Begin');
}
function assertFallbackFrame(v, imgAttr, countAttr) {
    let root = v.attrs.className;
    expect(root).toContain('min-h-[60vh]');
    expect(root).not.toContain('aspect-square');
    let img = findAll(v, n => n.attrs[imgAttr])[0];
    expect(img.attrs.className).toContain('object-cover');
    expect(findAll(v, n => /bg-black\/40/.test(n.attrs.className || '')).length).toBe(0);
    expect(findAll(v, n => /\bp-8\b/.test(n.attrs.className || '')).length).toBe(1);
    expect(findAll(v, n => n.tag === 'h1')[0].attrs.className).toContain('text-4xl');
    let count = findAll(v, n => n.attrs[countAttr] || (n.tag === 'p' && /Page/.test(textOf(n))))[0];
    expect(count.attrs.className).toContain('text-lg');
    expect(findAll(v, n => n.tag === 'button')[0].attrs.className).toContain('mt-8');
}

describe('ChapBook reader cover page', () => {
    let cb;
    const SCENES = [{ objectId: 'cs1', sceneIndex: 1, imageGroupPath: '/Cb/pages', imageName: 'c1.png' }];
    beforeEach(async () => {
        cb = await import('../features/chapBook.js');
        cb.__setReaderStateForTest({ readerBook: { objectId: 'CB1', name: 'Tidewater', slug: 'tidewater' }, readerPages: SCENES, readerCovers: null });
    });
    afterEach(() => { cb.__setReaderStateForTest({ readerBook: null, readerPages: [], readerCovers: null }); });

    it('without a covers view page 1 is the dimmed backdrop and the export cover', () => {
        let nav = { goToPage: vi.fn() };
        let v = cb.__renderReaderCoverForTest(nav);
        expect(v.attrs['data-cb-cover-source']).toBe('page');
        expect(v.attrs['data-cb-cover-band']).toBe('');
        expect(v.attrs.className).toContain('justify-center');
        let img = findAll(v, n => n.attrs['data-cb-cover-image'])[0];
        expect(img.attrs.src).toBe(MEDIA + '/Cb/pages/c1.png');
        expect(img.attrs.className).toContain('opacity-50');
        expect(textOf(v)).toContain('Tidewater');
        expect(cb.__readerCoverImageUrlForTest()).toBe(MEDIA + '/Cb/pages/c1.png');
    });
    it('an APPROVED cover render is the reader cover and the export cover, title in the band', () => {
        cb.__setReaderStateForTest({ readerCovers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ titleSafeBand: 'top' }) } })) });
        let v = cb.__renderReaderCoverForTest({ goToPage: vi.fn() });
        expect(v.attrs['data-cb-cover-source']).toBe('cover');
        expect(v.attrs['data-cb-cover-band']).toBe('TOP');
        expect(v.attrs.className).toContain('justify-start');
        let img = findAll(v, n => n.attrs['data-cb-cover-image'])[0];
        expect(img.attrs.src).toBe(MEDIA + '/Pb/covers/cover-r1.png');
        expect(img.attrs.className).toContain('opacity-100');
        expect(cb.__readerCoverImageUrlForTest()).toBe(MEDIA + '/Pb/covers/cover-r1.png');
    });
    it('a merely-latest render is not the cover; with no pages and no cover there is no image', () => {
        cb.__setReaderStateForTest({ readerCovers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ approved: null, reviewStatus: 'generated' }) } })) });
        let v = cb.__renderReaderCoverForTest({ goToPage: vi.fn() });
        expect(v.attrs['data-cb-cover-source']).toBe('page');
        cb.__setReaderStateForTest({ readerPages: [], readerCovers: null });
        v = cb.__renderReaderCoverForTest({ goToPage: vi.fn() });
        expect(v.attrs['data-cb-cover-source']).toBe('none');
        expect(findAll(v, n => n.attrs['data-cb-cover-image']).length).toBe(0);
        expect(cb.__readerCoverImageUrlForTest()).toBeNull();
    });
    it('238 d: with a real cover the frame is a fixed-aspect square showing the whole render and the title block is slim, in the band (TOP / BOTTOM / none); the page-1 fallback keeps the wide dimmed backdrop', () => {
        ['top', 'bottom', 'none'].forEach(band => {
            cb.__setReaderStateForTest({ readerCovers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ titleSafeBand: band }) } })) });
            let nav = { goToPage: vi.fn() };
            let v = cb.__renderReaderCoverForTest(nav);
            expect(v.attrs['data-cb-cover-source']).toBe('cover');
            expect(v.attrs['data-cb-cover-band']).toBe(band === 'none' ? '' : band.toUpperCase());
            expect(v.attrs.className).toContain(band === 'top' ? 'justify-start' : (band === 'bottom' ? 'justify-end' : 'justify-center'));
            assertCoverFrame(v, 'data-cb-cover-image', 'data-cb-cover-count');
            expect(textOf(v)).toContain('Tidewater');
            expect(textOf(v)).toContain('1 Page');
            findAll(v, n => n.tag === 'button')[0].attrs.onclick();
            expect(nav.goToPage).toHaveBeenCalledWith(1);
        });
        cb.__setReaderStateForTest({ readerCovers: null });
        let v = cb.__renderReaderCoverForTest({ goToPage: vi.fn() });
        expect(v.attrs['data-cb-cover-source']).toBe('page');
        assertFallbackFrame(v, 'data-cb-cover-image', 'data-cb-cover-count');
    });
    it('coverPageLayout is the one source of both readers\' frame / block classes', () => {
        let withCover = wf.coverPageLayout(true);
        let without = wf.coverPageLayout(false);
        expect(withCover.frame).toBe('w-full max-w-[75vh] aspect-square mx-auto');
        expect(withCover.image).toBe('object-contain');
        expect(without.frame).toBe('min-h-[60vh]');
        expect(without.image).toBe('object-cover');
        ['frame', 'image', 'block', 'kicker', 'title', 'meta', 'count', 'button'].forEach(k => {
            expect(typeof withCover[k]).toBe('string');
            expect(typeof without[k]).toBe('string');
        });
    });
});

// ─────────────────────────────── cover board: the ChapBook's carried chat config (240 d) ───────────────────────────────

describe('cover board chat config for a ChapBook (240 d)', () => {
    let cb;
    const CARRIED = { name: 'e2e-chapbook-llm', objectId: 'cc-e2e' };
    beforeEach(async () => {
        cb = await import('../features/chapBook.js');
        cb.__setChatConfigStateForTest({ configChatConfigRef: null, renderChatConfigRef: null, createChatConfigRef: null, readerBookObjectId: null, reviewBookObjectId: null, myBooks: [] });
    });
    afterEach(() => {
        cb.__setChatConfigStateForTest({ configChatConfigRef: null, renderChatConfigRef: null, createChatConfigRef: null, readerBookObjectId: null, reviewBookObjectId: null, myBooks: [] });
    });

    it('a ChapBook the session knows opens the cover board on the config its own panel carries — no library resolution', async () => {
        cb.__setChatConfigStateForTest({ configChatConfigRef: CARRIED, readerBookObjectId: BOOK });
        LLMConnector.resolveConfig.mockResolvedValue({ name: 'contentAnalysis', objectId: 'cc-ca' });
        let calls = wire();
        initCover('cover');
        await waitFor(() => st().board && st().plan && st().covers && !st().planLoading && !st().chatConfigResolving);
        expect(st().chatConfigRef).toEqual(CARRIED);
        expect(LLMConnector.resolveConfig).not.toHaveBeenCalled();
        expect(textOf(one(view(), 'pb-cmp-chatconfig'))).toContain('e2e-chapbook-llm');
        expect(seqOf(calls)).toEqual(['GET ' + PB + '/pages', BOARD_READ, OUTFITS_READ, COVERS_READ, PLAN_READ]);
    });
    it('a ChapBook that carries nothing, or a book the ChapBook feature does not know, falls back to the library default as before', async () => {
        // Known ChapBook (in My ChapBooks), but no config picked or resolved on its screens yet.
        cb.__setChatConfigStateForTest({ myBooks: [{ objectId: BOOK, name: 'Tidewater' }] });
        LLMConnector.resolveConfig.mockImplementation(async name => name === 'contentAnalysis' ? { name: 'contentAnalysis', objectId: 'cc-ca' } : null);
        wire();
        initCover('cover');
        await waitFor(() => st().board && !st().chatConfigResolving && st().chatConfigRef);
        expect(LLMConnector.resolveConfig.mock.calls.map(c => c[0])).toEqual(['contentAnalysis']);
        expect(st().chatConfigRef).toEqual({ name: 'contentAnalysis', objectId: 'cc-ca' });

        // A different book carrying a pick on the ChapBook screens, but this book is not one the feature knows.
        cmp.__setComposeStateForTest({ bookObjectId: null, coverPurpose: null, board: null, chatConfigRef: null, chatConfigResolving: false });
        cb.__setChatConfigStateForTest({ configChatConfigRef: CARRIED, readerBookObjectId: 'some-other-chapbook', myBooks: [] });
        LLMConnector.resolveConfig.mockClear();
        wire();
        initCover('cover');
        await waitFor(() => st().board && !st().chatConfigResolving && st().chatConfigRef);
        expect(LLMConnector.resolveConfig.mock.calls.map(c => c[0])).toEqual(['contentAnalysis']);
        expect(st().chatConfigRef).toEqual({ name: 'contentAnalysis', objectId: 'cc-ca' });
    });
    it('chapBookCarriedChatConfig: reader / review / My ChapBooks identify the book; panel → render → create precedence (unchanged from effectiveChatConfigName); an empty name is nothing', () => {
        expect(cb.chapBookCarriedChatConfig(BOOK)).toBeNull();
        cb.__setChatConfigStateForTest({ createChatConfigRef: { name: 'fromCreate', objectId: 'c1' }, renderChatConfigRef: { name: 'fromRender', objectId: 'r1' } });
        expect(cb.chapBookCarriedChatConfig(BOOK)).toBeNull();
        cb.__setChatConfigStateForTest({ reviewBookObjectId: BOOK });
        expect(cb.chapBookCarriedChatConfig(BOOK)).toEqual({ name: 'fromRender', objectId: 'r1' });
        cb.__setChatConfigStateForTest({ reviewBookObjectId: null, myBooks: [{ objectId: 'cb-9' }, { objectId: BOOK }] });
        expect(cb.chapBookCarriedChatConfig(BOOK)).toEqual({ name: 'fromRender', objectId: 'r1' });
        expect(cb.chapBookCarriedChatConfig('cb-unknown')).toBeNull();
        cb.__setChatConfigStateForTest({ configChatConfigRef: { name: 'fromPanel' } });
        expect(cb.chapBookCarriedChatConfig(BOOK)).toEqual({ name: 'fromPanel', objectId: null });
        expect(cb.effectiveChatConfigName()).toBe('fromPanel');
        // An empty name is skipped exactly as the pre-existing name precedence skipped it (a whitespace-only name
        // is left to the shared registry's trim in carriedBookChatConfig, tested below).
        cb.__setChatConfigStateForTest({ configChatConfigRef: { name: '' }, renderChatConfigRef: null });
        expect(cb.chapBookCarriedChatConfig(BOOK)).toEqual({ name: 'fromCreate', objectId: 'c1' });
        cb.__setChatConfigStateForTest({ createChatConfigRef: null });
        expect(cb.chapBookCarriedChatConfig(BOOK)).toBeNull();
        expect(cb.effectiveChatConfigName()).toBeNull();
    });
    it('carriedBookChatConfig asks the registered providers in order, skips throwing / blank ones, and the registration is undoable', () => {
        let unregA = wf.registerBookChatConfigProvider(id => { throw new Error('boom'); });
        let unregB = wf.registerBookChatConfigProvider(id => id === 'b' ? { name: '  spaced  ', objectId: 'x' } : { name: '' });
        let unregC = wf.registerBookChatConfigProvider(id => ({ name: 'third', objectId: null }));
        try {
            expect(wf.carriedBookChatConfig('b')).toEqual({ name: 'spaced', objectId: 'x' });
            expect(wf.carriedBookChatConfig('z')).toEqual({ name: 'third', objectId: null });
            expect(wf.carriedBookChatConfig(null)).toBeNull();
            unregC();
            expect(wf.carriedBookChatConfig('z')).toBeNull();
            expect(typeof wf.registerBookChatConfigProvider(null)).toBe('function');
        } finally {
            unregA(); unregB(); unregC();
        }
        expect(wf.carriedBookChatConfig('b')).toBeNull();
    });
});
