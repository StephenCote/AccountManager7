// @vitest-environment jsdom
/**
 * PictureBook P7-4 — opening a pre-V3 book: the legacy notice's Import runs POST /migrate-v1 and
 * opens the V3 reader on the imported book with the result (scenesImported / scenesFailed /
 * landscapesImported / warnings) shown over it.
 *
 * Why: under renderPath v3 a pure PB1 book (scene notes, no olio.pb.book) is shown read-only; the
 * only way forward is the server's PbMigrationUtil.importV1Book, which never modifies the PB1
 * records and answers 404 (no book group / no .pictureBookMeta), 400 (slug unparseable), 409 (a V3
 * book with that slug already exists) or 200 with the counts. The UI must relay those verbatim,
 * never route on a failure, and never double-submit (a second POST during the first is a 409).
 *
 * Covered: migrateV1Book's request (POST, JSON {groupObjectId}), normalizeMigration's NON_EMPTY
 * handling (the server mapper drops 0 / [] / null), migrationSummaryText, the notice's Import
 * button (present, disabled while importing), importLegacyBook's success path (route to the V3
 * reader THEN the result dialog with counts + warnings), its failure paths (409 / network → toast,
 * no route, button re-armed, no dialog), the no-bookObjectId guard and the double-click guard.
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

// The one route this feature calls. `answer` is a Response-like or a function(url, opts) → Response-like
// (or a thrown Error for a network failure). Records every call's url + parsed body.
function stubMigrate(answer) {
    let calls = [];
    global.fetch = vi.fn(async (url, opts) => {
        let u = String(url);
        let body = null;
        try { body = opts && typeof opts.body === 'string' ? JSON.parse(opts.body) : opts && opts.body; } catch (_) { body = opts.body; }
        calls.push({ url: u, method: (opts && opts.method) || 'GET', headers: (opts && opts.headers) || {}, body });
        if (/\/picture-book\/migrate-v1$/.test(u)) {
            return typeof answer === 'function' ? answer(u, opts) : answer;
        }
        return jsonResponse(404, 'unstubbed ' + u);
    });
    return calls;
}

function textOf(vnode) { return JSON.stringify(vnode); }

let origFetch;
let pbf;
let routeSet;
let dialogOpen;
let dialogClose;
let toast;

beforeEach(async () => {
    origFetch = global.fetch;
    pbf = await import('../features/pictureBook.js');
    routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
    dialogOpen = vi.spyOn(Dialog, 'open').mockImplementation(() => {});
    dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});
    toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(m, 'redraw').mockImplementation(() => {});
    pbf.__setViewerStateForTest({ viewerBookId: 'GRP-OLD', viewerScenes: [{ objectId: 's1' }], viewerLoading: false, viewerError: null, viewerRenderPath: 'v3', viewerLegacy: true, viewerImporting: false });
});

afterEach(() => {
    pbf.__setViewerStateForTest({ viewerBookId: null, viewerScenes: [], viewerLegacy: false, viewerImporting: false });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── workflow helpers ───────────────────────────────

describe('migrateV1Book — POST /migrate-v1 {groupObjectId}', () => {
    it('posts the group oid as JSON and returns the server body', async () => {
        let calls = stubMigrate(jsonResponse(200, { bookObjectId: 'PB2-N', slug: 'old-book', scenesImported: 2, landscapesImported: 1 }));
        let r = await wf.migrateV1Book('GRP-OLD');
        expect(calls).toHaveLength(1);
        expect(calls[0].method).toBe('POST');
        expect(calls[0].headers['Content-Type']).toBe('application/json');
        expect(calls[0].body).toEqual({ groupObjectId: 'GRP-OLD' });
        expect(r.bookObjectId).toBe('PB2-N');
    });
    it('a 409 throws the server\'s {error} text with the status on the Error', async () => {
        stubMigrate(jsonResponse(409, { error: "A PB2 book with slug 'old-book' already exists. Delete or rename the existing book before re-importing." }));
        let err;
        try { await wf.migrateV1Book('GRP-OLD'); } catch (e) { err = e; }
        expect(err).toBeTruthy();
        expect(err.status).toBe(409);
        expect(err.message).toBe("(409) A PB2 book with slug 'old-book' already exists. Delete or rename the existing book before re-importing.");
    });
    it('a 404 (no .pictureBookMeta) throws the same shape', async () => {
        stubMigrate(jsonResponse(404, { error: 'No .pictureBookMeta note found in book group' }));
        await expect(wf.migrateV1Book('GRP-X')).rejects.toMatchObject({ status: 404, message: '(404) No .pictureBookMeta note found in book group' });
    });
});

describe('normalizeMigration — the server\'s NON_EMPTY mapper drops zeros and empty lists', () => {
    it('fills the four counts and warnings from a full body', () => {
        let r = wf.normalizeMigration({ bookObjectId: 'B', slug: 's', scenesImported: 3, scenesFailed: 1, landscapesImported: 2, warnings: ['w1', 'w2'] });
        expect(r).toEqual({ bookObjectId: 'B', slug: 's', scenesImported: 3, scenesFailed: 1, landscapesImported: 2, warnings: ['w1', 'w2'] });
    });
    it('missing counts / warnings are 0 / [] (never NaN or undefined)', () => {
        let r = wf.normalizeMigration({ bookObjectId: 'B', slug: 's' });
        expect(r).toEqual({ bookObjectId: 'B', slug: 's', scenesImported: 0, scenesFailed: 0, landscapesImported: 0, warnings: [] });
    });
    it('a non-object body is an empty result, not a throw', () => {
        expect(wf.normalizeMigration(null).bookObjectId).toBeNull();
        expect(wf.normalizeMigration('oops').warnings).toEqual([]);
        expect(wf.normalizeMigration({ scenesImported: 'x', warnings: 'not a list' })).toEqual({ bookObjectId: null, slug: null, scenesImported: 0, scenesFailed: 0, landscapesImported: 0, warnings: [] });
    });
});

describe('migrationSummaryText', () => {
    it('pluralises and omits a zero failure count', () => {
        expect(pbf.migrationSummaryText({ scenesImported: 1, scenesFailed: 0, landscapesImported: 1 })).toBe('1 scene imported, 1 landscape imported');
        expect(pbf.migrationSummaryText({ scenesImported: 3, scenesFailed: 1, landscapesImported: 0 })).toBe('3 scenes imported, 1 failed, 0 landscapes imported');
    });
});

// ─────────────────────────────── the notice's Import button ───────────────────────────────

describe('legacy notice Import button', () => {
    it('is rendered, enabled, labelled "Import into V3" when not importing', () => {
        let notice = pbf.__renderViewerLegacyNoticeForTest();
        expect(notice).not.toBeNull();
        let s = textOf(notice);
        expect(s).toMatch(/"data-pb1-import":"1"/);
        expect(s).toMatch(/Import into V3/);
        expect(s).toMatch(/"disabled":false/);
        // The notice tells the user what is and is not carried over, and that PB1 is untouched.
        expect(s).toMatch(/portraits, composites and the workflow are not/);
        expect(s).toMatch(/nothing here is changed/);
    });
    it('is disabled and reads "Importing…" while the POST is in flight', () => {
        pbf.__setViewerStateForTest({ viewerImporting: true });
        let s = textOf(pbf.__renderViewerLegacyNoticeForTest());
        expect(s).toMatch(/"disabled":true/);
        expect(s).toMatch(/Importing…/);
        expect(s).not.toMatch(/Import into V3/);
    });
    it('is absent entirely when the book is not legacy', () => {
        pbf.__setViewerStateForTest({ viewerLegacy: false });
        expect(pbf.__renderViewerLegacyNoticeForTest()).toBeNull();
    });
});

// ─────────────────────────────── importLegacyBook ───────────────────────────────

describe('importLegacyBook — success', () => {
    it('POSTs the viewer\'s group oid, routes to the V3 reader, then opens the result dialog over it', async () => {
        let calls = stubMigrate(jsonResponse(200, {
            bookObjectId: 'PB2-N', slug: 'old-book', scenesImported: 2, scenesFailed: 1, landscapesImported: 1,
            locationPass: { proposed: 0 },
            warnings: ['Scene 3: landscapeObjectId abc not found', 'Scene 2: no imageObjectId']
        }));
        let r = await pbf.__importLegacyBookForTest();
        expect(calls).toHaveLength(1);
        expect(calls[0].body).toEqual({ groupObjectId: 'GRP-OLD' });
        expect(r.bookObjectId).toBe('PB2-N');
        // Route first, so closing the dialog leaves the user in the reader on the imported book.
        expect(routeSet).toHaveBeenCalledTimes(1);
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-N');
        expect(dialogOpen).toHaveBeenCalledTimes(1);
        expect(routeSet.mock.invocationCallOrder[0]).toBeLessThan(dialogOpen.mock.invocationCallOrder[0]);
        let args = dialogOpen.mock.calls[0][0];
        expect(args.title).toBe('Imported into V3');
        let body = textOf(args.content);
        expect(body).toMatch(/"data-pb1-import-result":"1"/);
        expect(body).toMatch(/2 scenes imported, 1 failed, 1 landscape imported/);
        expect(body).toMatch(/old-book/);
        expect(body).toMatch(/2 warnings/);
        expect(body).toMatch(/landscapeObjectId abc not found/);
        expect(body).toMatch(/no imageObjectId/);
        // locationPass is not part of the ledger's result contract and is not shown.
        expect(body).not.toMatch(/locationPass|proposed/);
        expect(args.actions).toHaveLength(1);
        expect(args.actions[0].label).toBe('Close');
        args.actions[0].onclick();
        expect(dialogClose).toHaveBeenCalledTimes(1);
        expect(toast).not.toHaveBeenCalled();
        expect(pbf.__viewerStateForTest().viewerImporting).toBe(false);
        expect(am7client.clearCache).toHaveBeenCalled();
    });
    it('a result with no warnings shows the counts and no warning list', async () => {
        stubMigrate(jsonResponse(200, { bookObjectId: 'PB2-N', slug: 'old-book', scenesImported: 1, landscapesImported: 1 }));
        await pbf.__importLegacyBookForTest();
        let body = textOf(dialogOpen.mock.calls[0][0].content);
        expect(body).toMatch(/1 scene imported, 1 landscape imported/);
        expect(body).not.toMatch(/warning/);
    });
    it('sets viewerImporting while the POST is in flight and the button is disabled for that span', async () => {
        let release;
        let gate = new Promise(r => { release = r; });
        stubMigrate(async () => { await gate; return jsonResponse(200, { bookObjectId: 'PB2-N', slug: 's', scenesImported: 1 }); });
        let p = pbf.__importLegacyBookForTest();
        await Promise.resolve();
        expect(pbf.__viewerStateForTest().viewerImporting).toBe(true);
        expect(textOf(pbf.__renderViewerLegacyNoticeForTest())).toMatch(/"disabled":true/);
        release();
        await p;
        expect(pbf.__viewerStateForTest().viewerImporting).toBe(false);
    });
});

describe('importLegacyBook — failure leaves the PB1 view as it was', () => {
    it('409 slug taken → error toast with the server text, no route, no dialog, button re-armed', async () => {
        let msg = "A PB2 book with slug 'old-book' already exists. Delete or rename the existing book before re-importing.";
        stubMigrate(jsonResponse(409, { error: msg }));
        let r = await pbf.__importLegacyBookForTest();
        expect(r).toBeUndefined();
        expect(toast).toHaveBeenCalledTimes(1);
        expect(toast).toHaveBeenCalledWith('error', '(409) ' + msg);
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogOpen).not.toHaveBeenCalled();
        let s = pbf.__viewerStateForTest();
        expect(s.viewerImporting).toBe(false);
        expect(s.viewerLegacy).toBe(true);
        expect(s.viewerBookId).toBe('GRP-OLD');
        expect(textOf(pbf.__renderViewerLegacyNoticeForTest())).toMatch(/"disabled":false/);
    });
    it('404 no meta note → error toast with the server text', async () => {
        stubMigrate(jsonResponse(404, { error: 'No .pictureBookMeta note found in book group' }));
        await pbf.__importLegacyBookForTest();
        expect(toast).toHaveBeenCalledWith('error', '(404) No .pictureBookMeta note found in book group');
        expect(routeSet).not.toHaveBeenCalled();
    });
    it('network failure → error toast, no route, button re-armed', async () => {
        stubMigrate(() => { throw new Error('ECONNRESET'); });
        await pbf.__importLegacyBookForTest();
        expect(toast).toHaveBeenCalledWith('error', 'ECONNRESET');
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogOpen).not.toHaveBeenCalled();
        expect(pbf.__viewerStateForTest().viewerImporting).toBe(false);
    });
    it('200 without a bookObjectId → error toast, no route (never opens the reader on nothing)', async () => {
        stubMigrate(jsonResponse(200, { slug: 's', scenesImported: 1 }));
        await pbf.__importLegacyBookForTest();
        expect(toast).toHaveBeenCalledWith('error', 'Import returned no V3 book');
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogOpen).not.toHaveBeenCalled();
    });
});

describe('importLegacyBook — guards', () => {
    it('a second click while the first POST is in flight does not POST again', async () => {
        let release;
        let gate = new Promise(r => { release = r; });
        let calls = stubMigrate(async () => { await gate; return jsonResponse(200, { bookObjectId: 'PB2-N', slug: 's', scenesImported: 1 }); });
        let p1 = pbf.__importLegacyBookForTest();
        await Promise.resolve();
        let r2 = await pbf.__importLegacyBookForTest();
        expect(r2).toBeUndefined();
        release();
        await p1;
        expect(calls).toHaveLength(1);
        expect(routeSet).toHaveBeenCalledTimes(1);
    });
    it('does nothing when the view is not legacy or has no book id', async () => {
        let calls = stubMigrate(jsonResponse(200, { bookObjectId: 'PB2-N' }));
        pbf.__setViewerStateForTest({ viewerLegacy: false });
        await pbf.__importLegacyBookForTest();
        pbf.__setViewerStateForTest({ viewerLegacy: true, viewerBookId: null });
        await pbf.__importLegacyBookForTest();
        expect(calls).toHaveLength(0);
        expect(routeSet).not.toHaveBeenCalled();
        expect(toast).not.toHaveBeenCalled();
    });
});
