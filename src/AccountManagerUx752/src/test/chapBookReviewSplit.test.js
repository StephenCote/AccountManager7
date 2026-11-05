// @vitest-environment jsdom
/**
 * ChapBook review — Split a page (P10-C 4, ledger §6.C 266).
 *
 * Why: the review's Split button 404'd on the live stack. The old client did the split itself: PATCH the
 * first half onto the page, then POST the second half to /rest/model/olio.pb.scene — a route that does not
 * exist (create is POST /rest/model with the schema in the body), so the page lost half its stanza and no
 * new page appeared. Split is now one server call, POST /rest/olio/chap-book/scene/{oid}/split
 * (ChapBookUtil.splitScene creates the new page FIRST, then truncates the original, then reorders), and the
 * client reloads the book like merge does. This file pins the client half of that contract:
 *   - the request is the POST to the split route for the SELECTED page's objectId, then a reload;
 *   - the page keeps its objectId and stays selected after the reload; the new page lands after it;
 *   - an unsaved stanza edit is saved (one PATCH /rest/model) BEFORE the split, so the server splits what
 *     the user sees; a failed save cancels the split (no split call);
 *   - a stanza with nothing after its midpoint is refused client-side with no network call (the server
 *     refuses it too, 400 — TestChapBookSceneSplit);
 *   - a refused split toasts and leaves the page as it was.
 *
 * fetch is stubbed per URL; the review state is seeded through __setReviewStateForTest and the Split button
 * is taken from the rendered card so the card → handler wiring is exercised, not just the handler.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';

const BOOK = 'cb-split-book';
const SPLIT_URL = oid => applicationPath + '/rest/olio/chap-book/scene/' + oid + '/split';

function scene(n, extra) {
    return Object.assign({
        objectId: 'cs' + n, id: 10 + n, name: 'Scene ' + n, sceneIndex: n, title: 'Title ' + n,
        poemStanza: 'line one of ' + n + '\nline two of ' + n + '\nline three of ' + n + '\nline four of ' + n,
        pageFont: '', pageBgColor: '', pageBgOpacity: null, pageTextAlign: '', pageTextColor: '',
        configOverride: null, sdPrompt: 'a quiet harbour at dusk, page ' + n, imageObjectId: null, imageStale: false,
        sceneNodeObjectId: null, pinned: false, mood: '', location: null, locationVariant: null, promptLocked: true,
        _dirty: new Set(), _saving: false
    }, extra || {});
}
/** What GET /picture-book/{book}/pages returns for a page (bookPages' projection). */
function pageRow(s, idx) {
    return { objectId: s.objectId, id: s.id, name: s.name, sceneIndex: idx, title: s.title, poemStanza: s.poemStanza, sdPrompt: s.sdPrompt };
}

function findAll(vnode, pred, out) {
    out = out || [];
    if (vnode == null || vnode === false) return out;
    if (Array.isArray(vnode)) { vnode.forEach(v => findAll(v, pred, out)); return out; }
    if (typeof vnode !== 'object') return out;
    if (pred(vnode)) out.push(vnode);
    findAll(vnode.children, pred, out);
    return out;
}
function pageItems(view) { return findAll(view, v => v.attrs && 'data-page-item' in v.attrs); }
function selectedItems(view) { return pageItems(view).filter(v => v.attrs['data-page-selected'] === '1'); }
function splitButtons(view) { return findAll(view, v => v.tag === 'button' && v.attrs && v.attrs.title === 'Split stanza at midpoint into two pages'); }

let cb;
let calls;        // [{url, method, body}] in order
let pagesAfter;   // what the reload's /pages returns
let splitStatus;  // status of the split response

function seed(scenes, selectedOid) {
    cb.__setReviewStateForTest({
        reviewBookObjectId: BOOK,
        reviewBook: { objectId: BOOK, name: 'Tidewater', slug: 'tidewater', fixPageHeight: false },
        reviewScenes: scenes,
        reviewSelectedOid: selectedOid === undefined ? null : selectedOid,
        reviewSceneImageUrls: {},
        reviewCovers: null,
        reviewExporting: false,
        roleWarning: false
    });
}

function ok(body) { return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) }; }
function fail(status) { return { ok: false, status: status, json: async () => ({}), text: async () => '' }; }

beforeEach(async () => {
    cb = await import('../features/chapBook.js');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m, 'redraw').mockImplementation(() => {});
    calls = [];
    pagesAfter = [];
    splitStatus = 200;
    global.fetch = vi.fn(async (url, opts) => {
        opts = opts || {};
        calls.push({ url: url, method: opts.method || 'GET', body: opts.body ? JSON.parse(opts.body) : null });
        if (/\/chap-book\/scene\/[^/]+\/split$/.test(url)) {
            return splitStatus === 200 ? ok({ split: true, sceneObjectId: 'cs-new' }) : fail(splitStatus);
        }
        if (/\/rest\/model$/.test(url)) return ok(true);                       // the pre-split PATCH
        if (/\/rest\/model\/search$/.test(url)) return ok([]);                 // book lookup / scene fields
        if (/\/picture-book\/[^/]+\/pages$/.test(url)) return ok(pagesAfter);  // the reload
        if (/\/covers$/.test(url)) return fail(404);                            // getCovers: caught, null
        throw new Error('unexpected fetch in test: ' + url);
    });
});

afterEach(() => {
    cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewBook: null, reviewScenes: [], reviewSelectedOid: null, reviewSceneImageUrls: {}, reviewCovers: null, reviewExporting: false });
    vi.restoreAllMocks();
    delete global.fetch;
});

describe('ChapBook review — Split a page is one server call, then a reload (P10-C 4)', () => {
    it('the card Split button POSTs the split route for the selected page, reloads, and keeps that page selected with the new page after it', async () => {
        let s1 = scene(1), s2 = scene(2), s3 = scene(3);
        seed([s1, s2, s3], 'cs2');
        // The server's answer to the reload: page 2 kept its objectId, the new page landed after it.
        let sNew = scene(9, { objectId: 'cs-new', title: 'Title 2 (cont.)', poemStanza: 'line three of 2\nline four of 2' });
        pagesAfter = [s1, s2, sNew, s3].map(pageRow);

        let view = cb.ChapBookReview.view();
        let buttons = splitButtons(view);
        expect(buttons.length, 'one Split button: the one card').toBe(1);
        expect(buttons[0].attrs.disabled).toBeFalsy();
        buttons[0].attrs.onclick();
        await vi.waitFor(() => expect(calls.some(c => c.url === SPLIT_URL('cs2'))).toBe(true));
        await vi.waitFor(() => expect(calls.some(c => /\/pages$/.test(c.url))).toBe(true));
        await vi.waitFor(() => expect(cb.selectedReviewIndex()).toBe(1));

        // Exactly one split call, a POST with credentials, for page 2 — not page 1, and not a /rest/model create.
        let splits = calls.filter(c => /\/split$/.test(c.url));
        expect(splits.length).toBe(1);
        expect(splits[0].url).toBe(SPLIT_URL('cs2'));
        expect(splits[0].method).toBe('POST');
        expect(global.fetch.mock.calls.find(c => c[0] === SPLIT_URL('cs2'))[1].credentials).toBe('include');
        expect(calls.filter(c => /\/rest\/model\/olio\.pb\.scene$/.test(c.url)).length).toBe(0);
        // The page was clean: nothing was PATCHed before the split.
        expect(calls.filter(c => c.method === 'PATCH').length).toBe(0);
        // The split preceded the reload.
        expect(calls.findIndex(c => /\/split$/.test(c.url))).toBeLessThan(calls.findIndex(c => /\/pages$/.test(c.url)));

        // After the reload: four pages in server order, page 2 (same objectId) still selected, new page right after it.
        view = cb.ChapBookReview.view();
        expect(pageItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs1', 'cs2', 'cs-new', 'cs3']);
        expect(selectedItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs2']);
        expect(page.toast).toHaveBeenCalledWith('success', 'Scene split');
    });

    it('an unsaved stanza edit is saved (one PATCH carrying the name) before the split, so the server splits what the user sees', async () => {
        let s1 = scene(1), s2 = scene(2);
        s2.poemStanza = 'edited one\nedited two\nedited three\nedited four';
        s2._dirty = new Set(['poemStanza']);
        seed([s1, s2], 'cs2');
        pagesAfter = [s1, s2, scene(9, { objectId: 'cs-new' })].map(pageRow);

        await cb.__doSplitSceneForTest(1);

        let patchIdx = calls.findIndex(c => c.method === 'PATCH');
        let splitIdx = calls.findIndex(c => /\/split$/.test(c.url));
        expect(patchIdx, 'the save happened').toBeGreaterThanOrEqual(0);
        expect(splitIdx, 'the split happened').toBeGreaterThanOrEqual(0);
        expect(patchIdx).toBeLessThan(splitIdx);
        expect(calls[patchIdx].url).toBe(applicationPath + '/rest/model');
        expect(calls[patchIdx].body).toEqual({ schema: 'olio.pb.scene', objectId: 'cs2', name: 'Scene 2', poemStanza: 'edited one\nedited two\nedited three\nedited four' });
        expect(calls[splitIdx].url).toBe(SPLIT_URL('cs2'));
        expect(calls.filter(c => c.method === 'PATCH').length).toBe(1);
    });

    it('a stanza edit that cannot be saved cancels the split — no split call, the page keeps its edit', async () => {
        let s1 = scene(1), s2 = scene(2);
        s2.poemStanza = 'edited one\nedited two';
        s2._dirty = new Set(['poemStanza']);
        seed([s1, s2], 'cs2');
        global.fetch.mockImplementation(async (url, opts) => {
            calls.push({ url: url, method: (opts || {}).method || 'GET', body: null });
            if (/\/rest\/model$/.test(url)) return fail(422);
            throw new Error('unexpected fetch in test: ' + url);
        });

        await cb.__doSplitSceneForTest(1);

        expect(calls.filter(c => /\/split$/.test(c.url)).length).toBe(0);
        expect(calls.filter(c => /\/pages$/.test(c.url)).length).toBe(0);
        expect(page.toast).toHaveBeenCalledWith('error', 'Split cancelled: this page has unsaved changes that could not be saved');
        expect(s2.poemStanza).toBe('edited one\nedited two');
        expect(s2._dirty.has('poemStanza')).toBe(true);
        expect(cb.selectedReviewIndex()).toBe(1);
    });

    it('a stanza with nothing after its midpoint is refused with no network call', async () => {
        seed([scene(1, { poemStanza: 'only one line' }), scene(2, { poemStanza: 'first\n   \n' })], 'cs1');
        await cb.__doSplitSceneForTest(0);
        await cb.__doSplitSceneForTest(1);
        expect(calls.length).toBe(0);
        expect(page.toast).toHaveBeenCalledTimes(2);
        expect(page.toast).toHaveBeenCalledWith('warn', 'Not enough text to split');
    });

    it('a refused split toasts the failure, reloads nothing, and leaves the page in place', async () => {
        let s1 = scene(1), s2 = scene(2);
        seed([s1, s2], 'cs1');
        splitStatus = 403;

        await cb.__doSplitSceneForTest(0);

        expect(calls.filter(c => /\/split$/.test(c.url)).length).toBe(1);
        expect(calls.filter(c => /\/pages$/.test(c.url)).length).toBe(0);
        expect(page.toast).toHaveBeenCalledWith('error', 'Split failed: Split scene failed: 403');
        expect(s1._saving).toBe(false);
        let view = cb.ChapBookReview.view();
        expect(pageItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs1', 'cs2']);
        expect(selectedItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs1']);
    });
});
