// @vitest-environment jsdom
/**
 * ChapBook Read / Edit (aiDocs/PictureBookUxConsistencyDesign.md §8, slice U4).
 *
 * Why: the ChapBook reader carried the whole ChapBookConfig panel (poem queue, Create, defaults, Render) plus a Review
 * button, a chat-config picker and Analyze; the editor was a separate "Review" page; the book-style bar sent N PATCHes on
 * every change; and a bulk render showed nothing but a button label. These tests pin the corrected behaviour against
 * the real module and real Mithril (jsdom DOM): Read has no edit controls, the route table is a literal with the
 * /review redirect, the style bar is a draft until one Save (N PATCHes, partial failure reported), the Pages section
 * registers its drafts with the dirty guard, and render progress lands in the bookActivity store.
 *
 * Network is a fetch stub; nothing here talks to a backend (the live paths are covered by the e2e specs).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { pageHeader } from '../components/pageHeader.js';
import { anyDirty, dirtyLabels, guardDirty, __resetDirtyForTest } from '../components/dirtyGuard.js';
import { getActivity, summaryFor, __resetActivityForTest } from '../workflows/bookActivity.js';

const BOOK = 'cb-ws-book';
const here = dirname(fileURLToPath(import.meta.url));

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

function scene(n, extra) {
    return Object.assign({
        objectId: 'cs' + n, id: 10 + n, name: 'Scene ' + n, sceneIndex: n, title: 'Title ' + n,
        poemStanza: 'Stanza ' + n, pageFont: '', pageBgColor: '', pageBgOpacity: null, pageTextAlign: '', pageTextColor: '',
        configOverride: null, sdPrompt: 'a quiet harbour, page ' + n, imageObjectId: null, imageStale: false,
        sceneNodeObjectId: null, pinned: false, mood: '', location: null, locationVariant: null, promptLocked: true,
        _dirty: new Set(), _saving: false
    }, extra || {});
}

function ctxFor(section, extra) {
    return Object.assign({
        kind: 'cb', bookObjectId: BOOK, section: section, item: null, tab: null, guided: false,
        go: vi.fn(), select: vi.fn(), setTab: vi.fn(), read: vi.fn()
    }, extra || {});
}

let cb;
let root;
let origFetch;

function draw(vnode) { m.render(root, vnode); return root; }
function q(sel) { return root.querySelector(sel); }
function qa(sel) { return Array.from(root.querySelectorAll(sel)); }

function seedEdit(scenes) {
    cb.__setReviewStateForTest({
        reviewBookObjectId: BOOK,
        reviewBook: { objectId: BOOK, id: 5, name: 'Tidewater', slug: 'tidewater', fixPageHeight: false },
        reviewScenes: scenes,
        reviewSelectedOid: null,
        reviewSceneImageUrls: {},
        reviewCovers: null,
        roleWarning: false
    });
    cb.__setEditStateForTest({
        editNeedsReload: false, reviewLoading: false, reviewError: null,
        bookStyle: { pageFont: '', pageTextColor: '', pageBgColor: '', pageBgOpacity: null, pageTextAlign: '' },
        bookStyleSaved: { pageFont: '', pageTextColor: '', pageBgColor: '', pageBgOpacity: null, pageTextAlign: '' }
    });
}

beforeEach(async () => {
    origFetch = global.fetch;
    cb = await import('../features/chapBook.js');
    root = document.createElement('div');
    document.body.appendChild(root);
    __resetDirtyForTest();
    __resetActivityForTest();
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
});

afterEach(() => {
    m.render(root, null);
    root.remove();
    cb.cbPagesSection.remove();
    cb.__setEditStateForTest({ pagesCtx: null, editCtx: null, readerFocusOid: null });
    cb.__setReaderStateForTest({ readerBook: null, readerPages: [], readerCovers: null, readerBookObjectId: null, currentPage: 0, fullscreen: false });
    cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewBook: null, reviewScenes: [], reviewSelectedOid: null, reviewSceneImageUrls: {}, reviewCovers: null });
    global.fetch = origFetch;
    vi.useRealTimers();
    vi.restoreAllMocks();
});

// ── Read mode (§8.1) ──────────────────────────────────────────────────

describe('ChapBook Read mode — no config panel, no edit controls', () => {
    function seedReader(currentPage) {
        cb.__setReaderStateForTest({
            readerBookObjectId: BOOK,
            readerBook: { objectId: BOOK, name: 'Tidewater', slug: 'tidewater', fixPageHeight: false },
            readerPages: [
                { objectId: 'cs1', title: 'Quay', poemStanza: 'Salt on the rail' },
                { objectId: 'cs2', title: 'Harbour', poemStanza: 'Gulls over the slip' },
                { objectId: 'cs3', title: 'Tide', poemStanza: 'The water turns' }
            ],
            readerCovers: null, readerLoading: false, currentPage: currentPage || 0, fullscreen: false
        });
    }

    it('renders bookHeader in Read mode over the reader, with none of the old reader edit surfaces', () => {
        seedReader(0);
        draw(cb.ChapBookReader.view());
        let header = q('[data-testid="book-header"]');
        expect(header).not.toBeNull();
        expect(header.getAttribute('data-mode')).toBe('read');
        expect(header.getAttribute('data-book-kind')).toBe('cb');
        expect(q('[data-testid="book-mode"]').getAttribute('data-value')).toBe('read');
        expect(q('[data-testid="book-back"]').getAttribute('title')).toBe('Back to ChapBooks');
        // Gone from Read (§8.1): the config panel, the reanalyze chat-config picker, Review / Analyze / Render buttons.
        expect(q('[data-testid="cb-config-panel"]')).toBeNull();
        expect(q('[data-testid="cb-config-chatconfig"]')).toBeNull();
        expect(q('[data-testid="cb-reanalyze-chatconfig"]')).toBeNull();
        expect(q('[data-testid="cb-fix-page-height"]')).toBeNull();
        let labels = qa('button').map(b => b.textContent.trim());
        expect(labels.some(t => /^Review$/.test(t))).toBe(false);
        expect(labels.some(t => /Analyze/.test(t))).toBe(false);
        expect(labels.some(t => /^Render$/.test(t))).toBe(false);
        expect(qa('button.bg-orange-600').length).toBe(0);
        // No form controls at all in Read.
        expect(qa('input, textarea, select').length).toBe(0);
        // Read keeps export and fullscreen (on the bookHeader), and the shell's own header row is hidden outside fullscreen.
        expect(q('[data-testid="cb-read-export"]')).not.toBeNull();
        expect(q('[data-testid="cb-read-fullscreen"]')).not.toBeNull();
        expect(q('[data-testid="book-page-label"]').textContent).toBe('Cover');
    });

    it('Read → Edit opens Edit › Pages › the page Read is showing, on its Page tab', async () => {
        seedReader(2); // cover = 0, Page 1 = 1, Page 2 = 2
        draw(cb.ChapBookReader.view());
        expect(q('[data-testid="book-page-label"]').textContent).toBe('Page 2 of 3');
        expect(cb.readerCurrentPageOid()).toBe('cs2');
        q('[data-testid="book-mode-edit"]').click();
        await vi.waitFor(() => expect(m.route.set).toHaveBeenCalled());
        expect(m.route.set).toHaveBeenCalledWith('/chap-book/edit/' + BOOK + '/pages/cs2?tab=page');
        // On the cover there is no page: Edit › Pages with no item.
        expect(cb.readerEditRoute(BOOK, null)).toBe('/chap-book/edit/' + BOOK + '/pages');
    });

    it('the bookHeader page controls flip pages; fullscreen drops the layout chrome (the route check PB1 has)', async () => {
        seedReader(0);
        draw(cb.ChapBookReader.view());
        q('[data-testid="cb-read-next"]').click();
        draw(cb.ChapBookReader.view());
        expect(q('[data-testid="book-page-label"]').textContent).toBe('Page 1 of 3');

        let { routes } = await import('../features/chapBookWorkspace.js');
        let readRoute = routes['/chap-book/read/:bookObjectId'];
        let normal = readRoute.view();
        q('[data-testid="cb-read-fullscreen"]').click();
        let fs = readRoute.view();
        // In fullscreen the route returns the reader vnode itself (no layout/pageLayout wrapper) …
        expect(typeof fs.tag.view).toBe('function');
        expect(normal.tag).not.toBe(fs.tag);
        // … and inside the overlay the one header is still bookHeader, drawn through ReaderShell's renderHeader slot
        // (design §4.1 / D5): no back arrow, an exit-fullscreen toggle, and no second (shell) header row.
        draw(cb.ChapBookReader.view());
        expect(document.querySelectorAll('[data-testid="book-header"]').length).toBe(1);
        expect(q('[data-testid="book-back"]')).toBeNull();
        expect(q('[data-testid="cb-read-fullscreen"]').getAttribute('title')).toBe('Exit fullscreen');
        expect(document.querySelector('[title="Export as HTML"]:not([data-testid])')).toBeNull();
    });
});

// ── Routes (§3) ───────────────────────────────────────────────────────

describe('ChapBook route table — literal, with the /review redirect', () => {
    it('exports exactly the library, Read, the /review redirect and the three Edit arities, as a literal', async () => {
        let { routes } = await import('../features/chapBookWorkspace.js');
        let keys = [
            '/chap-book', '/chap-book/read/:bookObjectId', '/chap-book/review/:bookObjectId',
            '/chap-book/edit/:bookObjectId', '/chap-book/edit/:bookObjectId/:section', '/chap-book/edit/:bookObjectId/:section/:item'
        ];
        expect(Object.keys(routes)).toEqual(keys);
        keys.forEach(k => expect(routes[k], k).toBeTruthy());
        // featureFlags.test.js reads the keys out of the source text, so each must be written literally.
        let src = readFileSync(resolve(here, '..', 'features', 'chapBookWorkspace.js'), 'utf-8');
        expect(/export const routes = \{/.test(src)).toBe(true);
        keys.forEach(k => expect(src.indexOf("'" + k + "':"), k).toBeGreaterThan(0));
        // features.js points the chapBook feature at this table.
        let features = readFileSync(resolve(here, '..', 'features.js'), 'utf-8');
        expect(features).toMatch(/chapBook:\s*\{\s*routes: \(\) => import\('\.\/features\/chapBookWorkspace\.js'\)/);
    });

    it('/chap-book/review/:id replaces itself with /chap-book/edit/:id/pages', async () => {
        let { routes } = await import('../features/chapBookWorkspace.js');
        routes['/chap-book/review/:bookObjectId'].onmatch({ bookObjectId: 'B1' }, '/chap-book/review/B1');
        expect(m.route.set).toHaveBeenCalledWith('/chap-book/edit/B1/pages', null, { replace: true });
    });

    it('the workspace registers the CB sections, with the shared Locations / Review / Cover adapters', async () => {
        let ws = await import('../features/chapBookWorkspace.js');
        let loc = await import('../features/pictureBookLocations.js');
        let rev = await import('../features/pictureBookReview.js');
        let cmp = await import('../features/pictureBookCompose.js');
        let a = ws.cbAdapters();
        expect(a.book).toBe(cb.cbBookSection);
        expect(a.style).toBe(cb.cbStyleSection);
        expect(a.pages).toBe(cb.cbPagesSection);
        expect(a.render).toBe(cb.cbRenderSection);
        expect(a.locations).toBe(loc.locationsSection);
        expect(a.review).toBe(rev.reviewSection);
        if (cmp.coverSection) expect(a.cover).toBe(cmp.coverSection);
        else expect(a.cover).toBeUndefined();
    });
});

// ── Edit › Style (D4) ─────────────────────────────────────────────────

describe('Edit › Style — the book-style bar is a draft until one Save', () => {
    function stubPatch(failOids) {
        let calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let body = init && init.body ? JSON.parse(init.body) : null;
            calls.push({ url: String(u), method: (init && init.method) || 'GET', body });
            if (init && init.method === 'PATCH') return jsonResponse(200, failOids.indexOf(body.objectId) >= 0 ? false : true);
            return jsonResponse(404, { error: 'unmocked' });
        });
        return calls;
    }

    it('a change sends nothing; Save sends one PATCH per page with name + every changed field; a refused page is reported and stays retryable', async () => {
        let s1 = scene(1), s2 = scene(2), s3 = scene(3);
        seedEdit([s1, s2, s3]);
        let calls = stubPatch(['cs2']);
        let ctx = ctxFor('style');
        cb.cbStyleSection.init(ctx);

        draw(cb.cbStyleSection.body(ctx));
        let font = q('[data-testid="cb-style-font"]');
        font.value = 'Georgia, serif';
        font.dispatchEvent(new Event('change'));
        let opacity = q('[data-testid="cb-style-bg-opacity"]');
        opacity.value = '70';
        opacity.dispatchEvent(new Event('input'));

        // A draft: nothing on the wire, the field marks and the guard know it.
        expect(global.fetch).not.toHaveBeenCalled();
        expect(cb.bookStyleChangedKeys()).toEqual(['pageFont', 'pageBgOpacity']);
        expect(anyDirty(['cb-style'])).toBe(true);
        expect(dirtyLabels(['cb-style'])).toEqual(['Book style']);
        draw(cb.cbStyleSection.body(ctx));
        expect(q('[data-testid="cb-style-dirty-pageFont"]')).not.toBeNull();
        expect(q('[data-testid="cb-style-dirty-pageTextColor"]')).toBeNull();
        // The preview draws the draft, the pages themselves are untouched.
        expect(q('[data-testid="cb-style-preview"] p').getAttribute('style')).toMatch(/Georgia, serif/);
        expect(s1.pageFont).toBe('');

        // Save from the section toolbar.
        draw(pageHeader(Object.assign({ bar: true }, cb.cbStyleSection.toolbar(ctx))));
        let save = q('[data-testid="cb-style-save"]');
        expect(save.disabled).toBe(false);
        save.click();
        await vi.waitFor(() => expect(page.toast).toHaveBeenCalled());

        let patches = calls.filter(c => c.method === 'PATCH');
        expect(patches.length).toBe(3);
        expect(patches.map(c => c.body.objectId)).toEqual(['cs1', 'cs2', 'cs3']);
        patches.forEach((c, i) => {
            expect(c.url).toMatch(/\/rest\/model$/);
            expect(c.body).toEqual({ schema: 'olio.pb.scene', objectId: 'cs' + (i + 1), name: 'Scene ' + (i + 1), pageFont: 'Georgia, serif', pageBgOpacity: 70 });
        });
        // Partial failure: reported by page; the pages that took it carry it; the draft stays dirty for a retry.
        expect(page.toast).toHaveBeenCalledWith('error', 'Book style saved to 2 of 3 pages; failed: page 2 — Save again to retry');
        expect(s1.pageFont).toBe('Georgia, serif');
        expect(s3.pageBgOpacity).toBe(70);
        expect(s2.pageFont).toBe('');
        expect(cb.bookStyleChangedKeys()).toEqual(['pageFont', 'pageBgOpacity']);

        // Save again once the server takes it: three more PATCHes, the draft is clean.
        page.toast.mockClear();
        let calls2 = stubPatch([]);
        expect(await cb.saveBookStyle()).toBe(true);
        expect(calls2.filter(c => c.method === 'PATCH').length).toBe(3);
        expect(cb.bookStyleChangedKeys()).toEqual([]);
        expect(anyDirty(['cb-style'])).toBe(false);
        expect(s2.pageFont).toBe('Georgia, serif');
        expect(page.toast).toHaveBeenCalledWith('success', 'Book style saved to all 3 pages');
    });

    it('Revert discards the draft without a request; a thrown PATCH counts as a failed page', async () => {
        seedEdit([scene(1), scene(2)]);
        global.fetch = vi.fn(async () => jsonResponse(500, 'boom'));
        let ctx = ctxFor('style');
        cb.cbStyleSection.init(ctx);
        draw(cb.cbStyleSection.body(ctx));
        q('[data-testid="cb-style-align-left"]').click();
        expect(cb.bookStyleChangedKeys()).toEqual(['pageTextAlign']);
        draw(pageHeader(Object.assign({ bar: true }, cb.cbStyleSection.toolbar(ctx))));
        q('[data-testid="cb-style-revert"]').click();
        expect(cb.bookStyleChangedKeys()).toEqual([]);
        expect(global.fetch).not.toHaveBeenCalled();

        draw(cb.cbStyleSection.body(ctx));
        q('[data-testid="cb-style-align-right"]').click();
        expect(await cb.saveBookStyle()).toBe(false);
        expect(global.fetch).toHaveBeenCalledTimes(2);
        expect(page.toast).toHaveBeenCalledWith('error', 'Book style saved to 0 of 2 pages; failed: pages 1, 2 — Save again to retry');
    });
});

// ── Edit › Pages — dirty guard + guarded selection ────────────────────

describe('Edit › Pages — registers its drafts with the dirty guard', () => {
    it('init registers "Pages"; a page edit makes it dirty; the guard\'s Save saves the page (name + changed field) before moving on', async () => {
        let s1 = scene(1), s2 = scene(2);
        seedEdit([s1, s2]);
        let ctx = ctxFor('pages');
        cb.cbPagesSection.init(ctx);
        expect(cb.cbPagesSection.dirtyKeys).toEqual(['cb-pages']);
        expect(anyDirty(['cb-pages'])).toBe(false);

        s2.title = 'Edited';
        s2._dirty.add('title');
        expect(anyDirty(['cb-pages'])).toBe(true);
        expect(dirtyLabels(['cb-pages'])).toEqual(['Pages']);

        let calls = [];
        global.fetch = vi.fn(async (u, init) => {
            calls.push({ method: init && init.method, body: init && init.body ? JSON.parse(init.body) : null });
            return jsonResponse(200, true);
        });
        // Stay first: nothing runs, nothing is sent, the draft survives.
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        let open = vi.spyOn(Dialog, 'open').mockImplementation(o => { o.actions.find(a => a.label === 'Stay').onclick(); });
        let next = vi.fn();
        expect(await guardDirty(next, ['cb-pages'])).toBe(false);
        expect(next).not.toHaveBeenCalled();
        expect(calls.length).toBe(0);
        expect(s2._dirty.has('title')).toBe(true);

        // Save: the page PATCH goes out, then the navigation runs.
        open.mockImplementation(o => { o.actions.find(a => a.label === 'Save').onclick(); });
        expect(await guardDirty(next, ['cb-pages'])).toBe(true);
        expect(next).toHaveBeenCalledTimes(1);
        let patches = calls.filter(c => c.method === 'PATCH');
        expect(patches.length).toBe(1);
        expect(patches[0].body).toEqual({ schema: 'olio.pb.scene', objectId: 'cs2', name: 'Scene 2', title: 'Edited' });
        expect(anyDirty(['cb-pages'])).toBe(false);
    });

    it('a page-list row selects through the section ctx (the guarded route), and the list keeps its unsaved badge', () => {
        let s2 = scene(2);
        s2._dirty.add('poemStanza');
        seedEdit([scene(1), s2, scene(3)]);
        let ctx = ctxFor('pages', { tab: 'page' });
        cb.cbPagesSection.init(ctx);
        draw(cb.cbPagesSection.body(ctx));
        expect(q('[data-page-item="cs2"] [data-page-badge="unsaved"]')).not.toBeNull();
        q('[data-page-item="cs3"]').click();
        expect(ctx.select).toHaveBeenCalledWith('cs3', { tab: 'page' });
        // the route — not the click — moves the selection
        expect(cb.selectedReviewIndex()).toBe(0);
        cb.cbPagesSection.select(ctxFor('pages', { item: 'cs3', tab: 'page' }));
        expect(cb.selectedReviewIndex()).toBe(2);
    });

    it('the Page tab draws the page as Read does, flips with ‹ ›, and links its location to Edit › Locations', () => {
        seedEdit([scene(1), scene(2, { location: { objectId: 'loc-1', name: 'Tavern' } }), scene(3)]);
        let ctx = ctxFor('pages', { item: 'cs2', tab: 'page' });
        cb.cbPagesSection.init(ctx);
        draw(cb.cbPagesSection.body(ctx));
        expect(q('[data-testid="cb-page-detail"]').getAttribute('data-tab')).toBe('page');
        expect(q('[data-testid="cb-page-view"]').getAttribute('data-scene-oid')).toBe('cs2');
        expect(q('[data-testid="cb-page-view-label"]').textContent).toBe('Page 2 of 3');
        expect(q('[data-testid="cb-page-view"]').textContent).toMatch(/Stanza 2/);
        // the Page tab is a view: no stanza editor
        expect(qa('[data-testid="cb-page-view"] textarea').length).toBe(0);
        q('[data-testid="cb-page-next"]').click();
        expect(ctx.select).toHaveBeenCalledWith('cs3', { tab: 'page' });
        q('[data-testid="cb-page-prev"]').click();
        expect(ctx.select).toHaveBeenCalledWith('cs1', { tab: 'page' });
        let link = q('[data-testid="cb-page-location-link"]');
        expect(link.getAttribute('href')).toBe('#!/chap-book/edit/' + BOOK + '/locations/loc-1');
        link.click();
        expect(ctx.go).toHaveBeenCalledWith('locations', 'loc-1', undefined);
    });
});

// ── Render progress → bookActivity (§4.5) ─────────────────────────────

describe('renderChapBookScenes publishes per-page progress to the bookActivity store', () => {
    function doneBody() {
        return { imageObjectId: null, rendered: true, skipped: false, pending: false, llmUnavailable: false, llmDegraded: false,
            jobId: null, runObjectId: null, nodeObjectId: null, artifactObjectId: null, nodeStatus: null, reviewStatus: null };
    }

    it('one stage per page (running → done / failed), "N of M", terminal + failed at the end, dismiss clears it', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
        global.fetch = vi.fn(async (u) => {
            let url = String(u);
            if (/\/rest\/olio\/picture-book\/[^/]+\/pages$/.test(url)) {
                return jsonResponse(200, [{ objectId: 'p1', sceneIndex: 1, pinned: false }, { objectId: 'p2', sceneIndex: 2, pinned: false }]);
            }
            let mg = /\/chap-book\/scene\/([^/]+)\/generate$/.exec(url);
            if (mg) return mg[1] === 'p1' ? jsonResponse(200, doneBody()) : jsonResponse(500, { error: 'sd down' });
            if (/\/rest\/model\/search$/.test(url)) return jsonResponse(200, []);
            return jsonResponse(404, { error: 'unmocked ' + url });
        });

        let run = cb.renderChapBookScenes(BOOK, 'cfg', {});
        // Page 1 settles, then the 5 s cooldown holds page 2 back: the store shows a running run mid-way.
        await vi.waitFor(() => {
            let a = getActivity(BOOK, 'cb-render');
            expect(a && a.stages.length === 2 && a.stages[0].state === 'done').toBe(true);
        });
        let mid = getActivity(BOOK, 'cb-render');
        expect(mid.terminal).toBe(false);
        expect(mid.label).toBe('Render');
        expect(mid.counter).toBe('1 of 2');
        expect(mid.section).toBe('render');
        expect(mid.stages).toEqual([{ handle: 'Page 1', state: 'done', error: null }, { handle: 'Page 2', state: 'pending', error: null }]);
        expect(summaryFor(BOOK)).toMatchObject({ running: true, label: 'Render', counter: '1 of 2', section: 'render' });

        await vi.advanceTimersByTimeAsync(6000);
        let result = await run;
        expect(result).toMatchObject({ rendered: 1, failed: 1, total: 2 });

        let end = getActivity(BOOK, 'cb-render');
        expect(end.terminal).toBe(true);
        expect(end.failed).toBe(true);
        expect(end.counter).toBe('2 of 2');
        expect(end.stages[0]).toEqual({ handle: 'Page 1', state: 'done', error: null });
        expect(end.stages[1].handle).toBe('Page 2');
        expect(end.stages[1].state).toBe('failed');
        expect(end.stages[1].error).toBeTruthy();
        expect(end.error).toBe(cb.renderResultMessage(result));
        expect(summaryFor(BOOK)).toMatchObject({ running: false, failed: true });

        end.onDismiss();
        expect(getActivity(BOOK, 'cb-render')).toBeNull();
        expect(summaryFor(BOOK)).toBeNull();
    });

    it('renderStages maps each renderProgress state (pinned and need-a-prompt pages are "skipped", with why)', () => {
        let stages = cb.renderStages(['a', 'b', 'c', 'd', 'e'],
            { a: 'generating', b: 'done', c: 'pinned', d: 'error', e: 'done' },
            { b: { result: { rendered: true } }, d: { error: 'boom' }, e: { result: { rendered: false, skipped: true } } });
        expect(stages).toEqual([
            { handle: 'Page 1', state: 'running', error: null },
            { handle: 'Page 2', state: 'done', error: null },
            { handle: 'Page 3', state: 'skipped', error: 'left pinned' },
            { handle: 'Page 4', state: 'failed', error: 'boom' },
            { handle: 'Page 5', state: 'skipped', error: 'needs a prompt' }
        ]);
    });

    it('the Render / Pages rail count is the pages needing a render (outdated image, no prompt, no image)', () => {
        expect(cb.pagesNeedingRender([
            scene(1, { imageObjectId: 'i1' }),
            scene(2, { imageObjectId: 'i2', imageStale: true }),
            scene(3, { imageObjectId: null }),
            scene(4, { imageObjectId: null, promptLocked: false, sdPrompt: '' })
        ])).toBe(3);
        seedEdit([scene(1, { imageObjectId: 'i1' }), scene(2)]);
        expect(cb.cbRenderSection.status(ctxFor('render'))).toMatchObject({ kind: 'count', count: 1 });
        expect(cb.cbPagesSection.status(ctxFor('pages'))).toMatchObject({ kind: 'count', count: 1 });
    });
});

// ── Library (§7.2, §6.4) ──────────────────────────────────────────────

describe('ChapBook library — "ChapBooks", Read / Edit / Delete rows', () => {
    it('a create lands in Edit, guided (the workspace resolves the next incomplete section)', () => {
        expect(cb.createdBookRoute('NEW1')).toBe('/chap-book/edit/NEW1?guided=1');
    });
});
