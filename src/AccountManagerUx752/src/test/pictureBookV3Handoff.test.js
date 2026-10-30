// @vitest-environment jsdom
/**
 * PictureBook P7-3 — the wizard hands off to the V3 workspace; existing books open in V3.
 *
 * Why: under picturebook renderPath v3 the server answers 409 "replaced by V3" for the three PB1
 * routes the wizard's steps 4–5 call (scene generate, prepare-images, PUT settings). So under v3 the
 * wizard must END at step 3 — "Continue to Images" opens the V3 reader on the pb2 book the step-2
 * Continue created — and an existing book must never re-enter the wizard (the list pencils, the
 * viewer pencil and the /picture-book/:groupOid route all resolve to the V3 reader instead). A pure
 * PB1 book (scenes, no olio.pb.book) is the one thing left on the PB1 viewer, read-only, with the
 * "replaced by V3 — import" notice P7-4 wires up. v1 keeps every pre-P7-3 behaviour.
 *
 * Covered: pictureBookRenderPath (v1 only when explicit, v3 on failure), resolvePb2BookObjectId's
 * three lookups, the step-3 action under v3 vs v1, the progress bar, tryResumeExistingBook's four
 * outcomes, handOffToReader with/without a V3 record, the PB1 viewer's redirect / legacy notice / no
 * Edit pencil, and the PB2 reader header hiding its Edit pencil under v3.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import * as wf from '../workflows/pictureBookWorkflow.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

// One fetch stub for every lookup the hand-off touches, keyed by what the server knows:
//   pb2Books    — olio.pb.book objectIds (GET /rest/model/olio.pb.book/{id} → 200)
//   bridges     — PB1 group oid → pb2 oid (GET /{group}/pb2 → {pb2BookObjectId})
//   scenes      — id → scene list (GET /{id}/scenes; the dual lookup accepts group or pb2 oid)
//   books       — the /books DTO list
function stubServer(server) {
    let calls = [];
    global.fetch = vi.fn(async (url) => {
        let u = String(url);
        calls.push(u);
        let mm;
        if ((mm = u.match(/\/rest\/model\/olio\.pb\.book\/([^/?]+)$/))) {
            let id = decodeURIComponent(mm[1]);
            return (server.pb2Books || []).includes(id) ? jsonResponse(200, { objectId: id, schema: 'olio.pb.book' }) : jsonResponse(404, 'Not found');
        }
        if ((mm = u.match(/\/picture-book\/([^/]+)\/pb2$/))) {
            let pb2 = (server.bridges || {})[mm[1]];
            return pb2 ? jsonResponse(200, { pb2BookObjectId: pb2, slug: 'slug', bookName: 'Book' }) : jsonResponse(404, 'Book group not found');
        }
        if ((mm = u.match(/\/picture-book\/([^/]+)\/scenes$/))) {
            let sc = (server.scenes || {})[mm[1]];
            return sc ? jsonResponse(200, sc) : jsonResponse(404, 'Not found');
        }
        if (/\/picture-book\/[^/]+\/characters$/.test(u)) return jsonResponse(200, []);
        if (/\/picture-book\/books(\?|$)/.test(u)) return jsonResponse(200, server.books || []);
        if (/\/picture-book\/[^/]+\/settings$/.test(u)) return jsonResponse(200, {});
        return jsonResponse(404, 'unstubbed ' + u);
    });
    return calls;
}

function flush() { return new Promise(r => setTimeout(r, 0)); }

let origFetch;
let pb;
let pbf;
let routeSet;
let dialogClose;

beforeEach(async () => {
    origFetch = global.fetch;
    pb = await import('../workflows/pictureBook.js');
    pbf = await import('../features/pictureBook.js');
    pb.__resetExtractStateForTest('work-1');
    routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
    dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
});

afterEach(() => {
    pb.__resetExtractStateForTest(null);
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── render path ───────────────────────────────

describe('pictureBookRenderPath — v1 only when the server says so', () => {
    it('is v1 for an explicit picturebook v1', async () => {
        vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v1', chapbook: 'v3', chat: 'v1' });
        expect(await pb.pictureBookRenderPath()).toBe('v1');
    });
    it('is v3 for v3, for a missing key, and when the config cannot be read', async () => {
        let spy = vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'V3' });
        expect(await pb.pictureBookRenderPath()).toBe('v3');
        spy.mockResolvedValue({ chapbook: 'v1' });
        expect(await pb.pictureBookRenderPath()).toBe('v3');
        spy.mockRejectedValue(new Error('401'));
        expect(await pb.pictureBookRenderPath()).toBe('v3');
    });
});

// ─────────────────────────── resolvePb2BookObjectId ─────────────────────────

describe('resolvePb2BookObjectId — any id the wizard is handed → olio.pb.book oid or null', () => {
    it('a pb2 book oid resolves to itself from the by-id read (one request)', async () => {
        let calls = stubServer({ pb2Books: ['PB2-A'] });
        expect(await wf.resolvePb2BookObjectId('PB2-A')).toBe('PB2-A');
        expect(calls).toHaveLength(1);
        expect(calls[0]).toMatch(/\/rest\/model\/olio\.pb\.book\/PB2-A$/);
    });
    it('a PB1 group oid resolves through the /pb2 bridge', async () => {
        stubServer({ bridges: { 'GRP-1': 'PB2-B' } });
        expect(await wf.resolvePb2BookObjectId('GRP-1')).toBe('PB2-B');
    });
    it('falls back to the /books list when the by-id read cannot see the record', async () => {
        stubServer({ books: [{ objectId: 'PB2-C', name: 'Shared chapter' }] });
        expect(await wf.resolvePb2BookObjectId('PB2-C')).toBe('PB2-C');
    });
    it('is null for a source document / pure PB1 book / unknown id, and for no id', async () => {
        stubServer({ books: [{ objectId: 'PB2-C' }] });
        expect(await wf.resolvePb2BookObjectId('DOC-1')).toBeNull();
        expect(await wf.resolvePb2BookObjectId(null)).toBeNull();
        expect(await wf.resolvePb2BookObjectId('undefined')).toBeNull();
    });
    it('a network failure on every lookup is null, never a throw', async () => {
        global.fetch = vi.fn(async () => { throw new Error('ECONNRESET'); });
        await expect(wf.resolvePb2BookObjectId('PB2-A')).resolves.toBeNull();
    });
});

// ───────────────────────────── step 3 → reader ──────────────────────────────

function continueToImages() {
    let a = pb.buildActions().find(x => x.label === 'Continue to Images');
    expect(a, 'step 3 must still offer "Continue to Images"').toBeTruthy();
    return a;
}

describe('step 3 "Continue to Images"', () => {
    it('v3: closes the wizard and opens the V3 reader on the pb2 book; step 4 is never reached', async () => {
        stubServer({ pb2Books: ['PB2-A'] });
        pb.__setExtractStateForTest({ step: 3, pbRenderPath: 'v3', bookObjectId: 'GRP-1', pb2WizardBookOid: 'PB2-A' });
        continueToImages().onclick();
        await flush();
        expect(dialogClose).toHaveBeenCalledTimes(1);
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-A');
        expect(pb.__extractStateForTest().step).toBe(3);
        expect(global.fetch).not.toHaveBeenCalled();
    });
    it('v3 resumed on a PB1 group without a cached pb2 oid: resolves it via /pb2 first', async () => {
        stubServer({ bridges: { 'GRP-1': 'PB2-B' } });
        pb.__setExtractStateForTest({ step: 3, pbRenderPath: 'v3', bookObjectId: 'GRP-1', pb2WizardBookOid: null });
        continueToImages().onclick();
        await flush();
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-B');
        expect(pb.__extractStateForTest().pb2WizardBookOid).toBe('PB2-B');
    });
    it('v3 with no V3 record: stays on step 3 with an error toast, no route, dialog open', async () => {
        stubServer({});
        pb.__setExtractStateForTest({ step: 3, pbRenderPath: 'v3', bookObjectId: 'GRP-1', pb2WizardBookOid: null });
        expect(await pb.handOffToReader()).toBeNull();
        expect(page.toast).toHaveBeenCalledWith('error', expect.stringMatching(/no V3 record/));
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
        expect(pb.__extractStateForTest().step).toBe(3);
    });
    it('v1: advances to step 4 exactly as before, no route, dialog stays open', async () => {
        stubServer({});
        pb.__setExtractStateForTest({ step: 3, pbRenderPath: 'v1', bookObjectId: 'GRP-1', pb2WizardBookOid: 'PB2-A' });
        continueToImages().onclick();
        await flush();
        expect(pb.__extractStateForTest().step).toBe(4);
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
    });
});

describe('renderProgressBar', () => {
    function labelsOf(vnode) {
        let out = [];
        (function walk(v) {
            if (!v) return;
            if (Array.isArray(v)) return v.forEach(walk);
            if (typeof v === 'string') return out.push(v);
            if (v.children) walk(v.children);
            else if (v.text != null) out.push(String(v.text));
        })(vnode);
        return out.filter(t => /^[A-Z][a-z]+$/.test(t));
    }
    it('v3 ends at Characters — no Images / View steps', () => {
        pb.__setExtractStateForTest({ step: 3, pbRenderPath: 'v3' });
        let labels = labelsOf(pb.renderProgressBar());
        expect(labels).toEqual(['Source', 'Scenes', 'Characters']);
    });
    it('v1 keeps all five', () => {
        pb.__setExtractStateForTest({ step: 3, pbRenderPath: 'v1' });
        expect(labelsOf(pb.renderProgressBar())).toEqual(['Source', 'Scenes', 'Characters', 'Images', 'View']);
    });
});

// ─────────────────────────── tryResumeExistingBook ──────────────────────────

describe('tryResumeExistingBook under v3 — existing books never re-enter the wizard', () => {
    it('a pb2 book oid (list / reader pencils) → the V3 reader route', async () => {
        stubServer({ pb2Books: ['PB2-A'], scenes: { 'PB2-A': [{ objectId: 's1' }] } });
        pb.__setExtractStateForTest({ pbRenderPath: 'v3' });
        expect(await pb.tryResumeExistingBook('PB2-A')).toEqual({ route: '/picture-book/v2/PB2-A' });
        expect(pb.__extractStateForTest().step).toBe(1);
        expect(pb.__extractStateForTest().bookObjectId).toBeNull();
    });
    it('a PB1 group oid bridged via /pb2 (viewer pencil) → the V3 reader route', async () => {
        stubServer({ bridges: { 'GRP-1': 'PB2-B' }, scenes: { 'GRP-1': [{ objectId: 's1' }] } });
        pb.__setExtractStateForTest({ pbRenderPath: 'v3' });
        expect(await pb.tryResumeExistingBook('GRP-1')).toEqual({ route: '/picture-book/v2/PB2-B' });
    });
    it('a pure PB1 book (scenes, no V3 record) → the read-only PB1 viewer route', async () => {
        stubServer({ scenes: { 'GRP-OLD': [{ objectId: 's1', imageObjectId: 'i1' }] } });
        pb.__setExtractStateForTest({ pbRenderPath: 'v3' });
        expect(await pb.tryResumeExistingBook('GRP-OLD')).toEqual({ route: '/picture-book/GRP-OLD' });
        expect(pb.__extractStateForTest().step).toBe(1);
    });
    it('a fresh source document → null, the wizard opens at step 1', async () => {
        stubServer({});
        pb.__setExtractStateForTest({ pbRenderPath: 'v3' });
        expect(await pb.tryResumeExistingBook('DOC-1')).toBeNull();
        expect(pb.__extractStateForTest().step).toBe(1);
    });
    it('v1 is unchanged: a PB1 book with unresolved scenes resumes onto step 4 in the wizard', async () => {
        stubServer({ scenes: { 'GRP-1': [{ objectId: 's1', status: 'pending' }] } });
        vi.spyOn(am7client, 'get').mockImplementation((t, id, cb) => cb(null));
        pb.__setExtractStateForTest({ pbRenderPath: 'v1' });
        let r = await pb.tryResumeExistingBook('GRP-1');
        expect(r).toBeUndefined();
        let s = pb.__extractStateForTest();
        expect(s.step).toBe(4);
        expect(s.bookObjectId).toBe('GRP-1');
        expect(routeSet).not.toHaveBeenCalled();
    });
});

// ─────────────────────────────── PB1 viewer ────────────────────────────────

describe('PB1 viewer /picture-book/:groupOid under v3', () => {
    beforeEach(() => {
        vi.spyOn(am7client, 'search').mockResolvedValue({ results: [] });
        pbf.__setViewerStateForTest({ viewerBookId: null, viewerScenes: [], viewerLoading: false, viewerError: null, viewerRenderPath: 'v1', viewerLegacy: false });
    });
    it('redirects (replace) to the V3 reader when the group has a pb2 record, without loading PB1 scenes', async () => {
        vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v3' });
        let calls = stubServer({ bridges: { 'GRP-1': 'PB2-B' }, scenes: { 'GRP-1': [{ objectId: 's1' }] } });
        await pbf.__loadViewerForTest('GRP-1');
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-B', null, { replace: true });
        expect(calls.some(u => /\/scenes$/.test(u))).toBe(false);
        expect(pbf.__viewerStateForTest().viewerLegacy).toBe(false);
    });
    it('a pb2 book oid handed straight to the route also redirects', async () => {
        vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v3' });
        stubServer({ pb2Books: ['PB2-A'] });
        await pbf.__loadViewerForTest('PB2-A');
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-A', null, { replace: true });
    });
    it('a pure PB1 book shows read-only: legacy notice rendered, no Edit pencil', async () => {
        vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v3' });
        stubServer({ scenes: { 'GRP-OLD': [{ objectId: 's1', title: 'One' }, { objectId: 's2', title: 'Two' }] } });
        await pbf.__loadViewerForTest('GRP-OLD');
        let s = pbf.__viewerStateForTest();
        expect(routeSet).not.toHaveBeenCalled();
        expect(s.viewerScenes).toHaveLength(2);
        expect(s.viewerLegacy).toBe(true);
        expect(s.viewerLoading).toBe(false);
        let notice = pbf.__renderViewerLegacyNoticeForTest();
        expect(notice).not.toBeNull();
        expect(notice.attrs['data-pb1-legacy-notice']).toBe('1');
        expect(JSON.stringify(notice)).toMatch(/replaced by V3/);
        expect(JSON.stringify(notice)).toMatch(/Import the book/);
        expect(pbf.__renderViewerActionsLeftForTest({ fullscreen: false })).toBeNull();
    });
    it('a source document with no book yet is not legacy: empty state, no notice', async () => {
        vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v3' });
        stubServer({});
        await pbf.__loadViewerForTest('DOC-1');
        let s = pbf.__viewerStateForTest();
        expect(routeSet).not.toHaveBeenCalled();
        expect(s.viewerScenes).toHaveLength(0);
        expect(s.viewerLegacy).toBe(false);
        expect(pbf.__renderViewerLegacyNoticeForTest()).toBeNull();
    });
    it('v1: no redirect, no notice, Edit pencil present for a book with scenes', async () => {
        vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v1' });
        stubServer({ bridges: { 'GRP-1': 'PB2-B' }, scenes: { 'GRP-1': [{ objectId: 's1' }] } });
        await pbf.__loadViewerForTest('GRP-1');
        let s = pbf.__viewerStateForTest();
        expect(routeSet).not.toHaveBeenCalled();
        expect(s.viewerLegacy).toBe(false);
        expect(pbf.__renderViewerLegacyNoticeForTest()).toBeNull();
        let pencil = pbf.__renderViewerActionsLeftForTest({ fullscreen: false });
        expect(pencil).not.toBeNull();
        expect(pencil.attrs.title).toBe('Edit Book');
    });
});

// ─────────────────────────── PB2 reader header pencil ───────────────────────

describe('renderPb2Header — the Edit (wizard) pencil is v1-only', () => {
    beforeEach(() => {
        pbf.__pb2StopWatchingForTest();
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        pbf.__setPb2V3StateForTest({
            pb2AutoApprove: false, pb2AutoApproveSaving: false, pb2Run: null, pb2ResumeRun: null, pb2Submitting: false,
            pb2CurrentPage: 1, pb2BookObjectId: 'B1', pb2BookName: 'Harlot\'s Eight',
            pb2Pages: [{ objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', dataObjectId: 'd1' }]
        });
    });
    afterEach(() => { pbf.__pb2StopWatchingForTest(); });
    function pencilCount() {
        return (JSON.stringify(pbf.renderPb2Header()).match(/"data-pb2-edit"/g) || []).length;
    }
    function charactersCount() {
        return (JSON.stringify(pbf.renderPb2Header()).match(/"data-pb2-characters"/g) || []).length;
    }
    it('v1: pencil and Characters both offered', () => {
        pbf.__setPb2V3StateForTest({ pb2RenderPath: 'v1' });
        expect(pencilCount()).toBe(1);
        expect(charactersCount()).toBe(1);
    });
    it('v3: pencil hidden, Characters stays', () => {
        pbf.__setPb2V3StateForTest({ pb2RenderPath: 'v3' });
        expect(pencilCount()).toBe(0);
        expect(charactersCount()).toBe(1);
    });
});
