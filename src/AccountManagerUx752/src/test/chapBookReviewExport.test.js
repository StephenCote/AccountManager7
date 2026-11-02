// @vitest-environment jsdom
/**
 * ChapBook review export cover (P9-8, ledger §6.C 238 k).
 *
 * Why: chapBook.js exportReviewBook() handed the shared export engine (components/readerShell.js exportBook)
 * `coverImageUrl: () => first page's image`, so an exported ChapBook opened on page 1's landscape even when
 * the book had an APPROVED cover render — the one its reader page shows. The review now reads the cover-art
 * view with the pages (GET /{book}/covers, find-only on the server) and the export's cover is the approved
 * COVER render when there is one, else page 1 exactly as before. The reader and the export resolve the cover
 * through the same pair (coverImageRefFor(covers, 'COVER', false) → chapImageUrl), so a merely-latest,
 * unapproved render is never exported either.
 *
 * No network, LLM or SD is touched: the export engine is replaced by a vi.fn so its options are inspected
 * instead of a file being built; fetch, am7client.dotPath, am7sd.fetchModels / fetchLoras and page.toast are
 * stubbed for the one test that drives the review's real oninit → loadReviewBook load path.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7sd } from '../components/sdConfig.js';
import { applicationPath } from '../core/config.js';
import { exportBook } from '../components/readerShell.js';

vi.mock('../components/readerShell.js', async function (importOriginal) {
    const actual = await importOriginal();
    return Object.assign({}, actual, { exportBook: vi.fn(async function () { return true; }) });
});

const BOOK = 'cb-book-1';
const PB = '/rest/olio/picture-book/' + BOOK;
const MEDIA = applicationPath + '/media/Public/data.data';
const COVER_URL = MEDIA + '/Pb/covers/cover-r1.png';
const PAGE1_URL = MEDIA + '/Pb/pages/quay.png';
const PAGE2_URL = MEDIA + '/Pb/pages/market.png';

const SCENES = [
    { objectId: 'cs1', sceneIndex: 1, title: 'Quay', poemStanza: 'Salt on the rail' },
    { objectId: 'cs2', sceneIndex: 2, title: 'Market', poemStanza: 'Bells at noon' }
];
const PREVIEWS = { cs1: PAGE1_URL, cs2: PAGE2_URL };

function imageRefWire(n, extra) {
    return Object.assign({ artifactObjectId: 'art-' + n, revision: n, reviewStatus: 'generated', dataObjectId: 'd-' + n, imageGroupPath: '/Pb/covers', imageName: 'cover-r' + n + '.png', imageContentType: 'image/png' }, extra || {});
}
function coverEntryWire(extra) {
    return Object.assign({ compositionObjectId: 'comp-1', purpose: 'cover', source: 'user', titleSafeBand: 'unknown', chainLength: 0, pinned: false }, extra || {});
}
/** Rendered twice: revision 1 approved + selected, revision 2 the latest (unapproved). */
function renderedEntryWire(extra) {
    return Object.assign(coverEntryWire({
        source: 'llm', titleSafeBand: 'bottom', nodeObjectId: 'n-cover', nodeStatus: 'done_unverified', chainLength: 2, reviewStatus: 'approved', selectedArtifactObjectId: 'art-1',
        approved: imageRefWire(1, { reviewStatus: 'approved' }), latest: imageRefWire(2)
    }), extra || {});
}
function coverViewWire(extra) {
    return Object.assign({ bookObjectId: BOOK, workflowObjectId: 'wf-1', covers: { COVER: coverEntryWire() } }, extra || {});
}
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
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}

let cb;
let wf;
let origFetch;

function seedReview(opts) {
    let o = opts || {};
    cb.__setReviewStateForTest({
        reviewBookObjectId: BOOK,
        reviewBook: 'reviewBook' in o ? o.reviewBook : { objectId: BOOK, name: 'Tidewater', slug: 'tidewater', fixPageHeight: false },
        reviewScenes: 'reviewScenes' in o ? o.reviewScenes : SCENES.map(s => Object.assign({}, s)),
        reviewSceneImageUrls: 'previews' in o ? o.previews : Object.assign({}, PREVIEWS),
        reviewCovers: 'covers' in o ? o.covers : null,
        reviewExporting: false
    });
}
function lastExportOpts() {
    expect(exportBook.mock.calls.length, 'exportBook called once').toBe(1);
    return exportBook.mock.calls[0][0];
}

beforeEach(async () => {
    origFetch = global.fetch;
    cb = await import('../features/chapBook.js');
    wf = await import('../workflows/pictureBookWorkflow.js');
    exportBook.mockClear();
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    seedReview({ reviewScenes: [], previews: {}, covers: null, reviewBook: null });
});

afterEach(() => {
    global.fetch = origFetch;
    cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewBook: null, reviewScenes: [], reviewSceneImageUrls: {}, reviewCovers: null, reviewExporting: false });
    vi.restoreAllMocks();
});

describe('ChapBook review export cover (238 k)', () => {
    it('with an APPROVED cover the export\'s coverImageUrl() is the cover\'s media URL - the reader\'s own source - and the pages keep their previews', async () => {
        seedReview({ covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire() } })) });
        // The same pair the reader page uses: approved first, never a merely-latest render.
        expect(cb.__reviewCoverImageUrlForTest()).toBe(COVER_URL);

        await cb.__exportReviewBookForTest();
        let opts = lastExportOpts();
        expect(typeof opts.coverImageUrl).toBe('function');
        expect(opts.coverImageUrl()).toBe(COVER_URL);
        expect(opts.coverImageUrl()).not.toBe(PAGE1_URL);
        // The rest of the export contract is unchanged.
        expect(opts.pages.map(p => p.objectId)).toEqual(['cs1', 'cs2']);
        expect(opts.imageUrlFor(opts.pages[0])).toBe(PAGE1_URL);
        expect(opts.imageUrlFor(opts.pages[1])).toBe(PAGE2_URL);
        expect(opts.imageUrlFor({ objectId: 'nope' })).toBeNull();
        expect(opts.title).toBe('Tidewater');
        expect(opts.countNoun).toBe('Page');
        expect(opts.titleSuffix).toBe('ChapBook');
        expect(opts.nameSuffix).toBe('-chapbook.html');
        expect(opts.toast).toBe('ChapBook exported');
        expect(typeof opts.buildPageHtml).toBe('function');
    });

    it('without a cover view the export\'s coverImageUrl() is page 1\'s preview, as before', async () => {
        seedReview({ covers: null });
        expect(cb.__reviewCoverImageUrlForTest()).toBe(PAGE1_URL);

        await cb.__exportReviewBookForTest();
        expect(lastExportOpts().coverImageUrl()).toBe(PAGE1_URL);
    });

    it('a COVER composition that never rendered, or one with only an unapproved latest render, also falls back to page 1', async () => {
        seedReview({ covers: wf.normalizeCoverView(coverViewWire()) });
        expect(cb.__reviewCoverImageUrlForTest()).toBe(PAGE1_URL);

        seedReview({ covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire({ approved: null, reviewStatus: 'generated', selectedArtifactObjectId: 'art-2' }) } })) });
        expect(cb.__reviewCoverImageUrlForTest()).toBe(PAGE1_URL);
        await cb.__exportReviewBookForTest();
        expect(lastExportOpts().coverImageUrl()).toBe(PAGE1_URL);

        // A cover of another purpose is not THE cover.
        seedReview({ covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: null, TITLE_PAGE: renderedEntryWire({ purpose: 'title_page' }) } })) });
        expect(cb.__reviewCoverImageUrlForTest()).toBe(PAGE1_URL);
    });

    it('page 1 without a resolved preview yields null (the engine then renders a text-only cover); no pages means no export at all', async () => {
        seedReview({ covers: null, previews: {} });
        expect(cb.__reviewCoverImageUrlForTest()).toBeNull();

        seedReview({ reviewScenes: [], previews: {}, covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire() } })) });
        // Covers alone are not a book: the guard is on pages, unchanged.
        await cb.__exportReviewBookForTest();
        expect(exportBook.mock.calls.length).toBe(0);
        expect(cb.__reviewCoverImageUrlForTest()).toBe(COVER_URL);
    });

    it('the export engine\'s in-flight guard still holds: a second export while one runs is a no-op', async () => {
        seedReview({ covers: wf.normalizeCoverView(coverViewWire({ covers: { COVER: renderedEntryWire() } })) });
        let release;
        exportBook.mockImplementationOnce(() => new Promise(r => { release = r; }));
        let first = cb.__exportReviewBookForTest();
        await waitFor(() => exportBook.mock.calls.length === 1);
        await cb.__exportReviewBookForTest();
        expect(exportBook.mock.calls.length).toBe(1);
        release(true);
        await first;
        await cb.__exportReviewBookForTest();
        expect(exportBook.mock.calls.length).toBe(2);
        expect(exportBook.mock.calls[1][0].coverImageUrl()).toBe(COVER_URL);
    });
});

describe('ChapBook review load reads the cover-art view with the pages (238 k)', () => {
    let calls;
    function router(handlers) {
        calls = [];
        global.fetch = vi.fn(async function (url, opts) {
            let method = (opts && opts.method) || 'GET';
            let body = opts && typeof opts.body === 'string' ? JSON.parse(opts.body) : null;
            calls.push({ method, url, body });
            for (let h of handlers) {
                if (h.match(method, url, body)) return h.respond(method, url, body);
            }
            return jsonResponse(404, { message: 'not stubbed: ' + method + ' ' + url });
        });
    }
    const pagesWire = [{ objectId: 'cs1', id: 11, sceneIndex: 1, title: 'Quay', poemStanza: 'Salt on the rail' }];
    function handlersWith(coversRespond) {
        return [
            { match: (mth, url, body) => mth === 'POST' && /\/rest\/model\/search$/.test(url) && body && body.type === 'olio.pb.book', respond: () => jsonResponse(200, [{ objectId: BOOK, id: 5, name: 'Tidewater', slug: 'tidewater', bookStatus: 'draft', fixPageHeight: false }]) },
            { match: (mth, url, body) => mth === 'POST' && /\/rest\/model\/search$/.test(url) && body && body.type === 'olio.pb.scene', respond: () => jsonResponse(200, [{ objectId: 'cs1', id: 11, groupId: 77, name: 'Quay' }]) },
            { match: (mth, url) => mth === 'GET' && url.endsWith(PB + '/pages'), respond: () => jsonResponse(200, pagesWire) },
            { match: (mth, url) => mth === 'GET' && url.endsWith(PB + '/covers'), respond: coversRespond }
        ];
    }

    beforeEach(() => {
        vi.spyOn(am7sd, 'fetchModels').mockResolvedValue([]);
        vi.spyOn(am7sd, 'fetchLoras').mockResolvedValue([]);
        // oninit also primes the book SD panel (GET /chap-book/{book}/settings, not stubbed → 404); that path
        // only warns + toasts, both stubbed here so the run output stays clean.
        vi.spyOn(console, 'warn').mockImplementation(() => {});
    });

    it('oninit → loadReviewBook issues GET /{book}/covers alongside /pages, and the export then carries the approved cover', async () => {
        router(handlersWith(() => jsonResponse(200, coverViewWire({ covers: { COVER: renderedEntryWire() } }))));
        cb.ChapBookReview.oninit({ attrs: { bookObjectId: BOOK } });
        await waitFor(() => cb.__reviewCoverImageUrlForTest() === COVER_URL, 4000);

        let seq = calls.map(c => c.method + ' ' + c.url.replace(applicationPath, ''));
        expect(seq).toContain('GET ' + PB + '/pages');
        expect(seq).toContain('GET ' + PB + '/covers');
        // Pages and covers are read together, both after the book-record read.
        expect(seq.indexOf('GET ' + PB + '/covers')).toBeGreaterThan(seq.indexOf('POST /rest/model/search'));
        // The view shows the loaded book, not an error.
        await waitFor(() => !/Loading scenes/.test(textOf(cb.ChapBookReview.view())), 4000);
        let text = textOf(cb.ChapBookReview.view());
        expect(text).toMatch(/Tidewater/);
        expect(text).not.toMatch(/Error:/);

        await cb.__exportReviewBookForTest();
        expect(exportBook.mock.calls.length).toBe(1);
        expect(exportBook.mock.calls[0][0].coverImageUrl()).toBe(COVER_URL);
        expect(exportBook.mock.calls[0][0].pages.map(p => p.objectId)).toEqual(['cs1']);
        expect(exportBook.mock.calls[0][0].title).toBe('Tidewater');
    });

    it('a failed covers read never blocks the pages: the review loads and the export falls back to page 1 (null preview here)', async () => {
        router(handlersWith(() => jsonResponse(500, { message: 'boom' })));
        cb.ChapBookReview.oninit({ attrs: { bookObjectId: BOOK } });
        // 'Loading scenes...' clears only when loadReviewBook has settled (reviewLoading = false), i.e. after
        // the pages + covers reads and the scene backfill.
        await waitFor(() => calls.some(c => c.url.endsWith(PB + '/covers')), 4000);
        await waitFor(() => !/Loading scenes/.test(textOf(cb.ChapBookReview.view())), 4000);
        let text = textOf(cb.ChapBookReview.view());
        expect(text).toMatch(/Tidewater/);
        expect(text).not.toMatch(/Error:/);
        expect(cb.__reviewCoverImageUrlForTest()).toBeNull();

        await cb.__exportReviewBookForTest();
        expect(exportBook.mock.calls.length).toBe(1);
        expect(exportBook.mock.calls[0][0].coverImageUrl()).toBeNull();
        expect(exportBook.mock.calls[0][0].pages.map(p => p.objectId)).toEqual(['cs1']);
    });
});
