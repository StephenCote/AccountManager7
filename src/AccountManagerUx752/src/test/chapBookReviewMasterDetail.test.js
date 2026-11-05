// @vitest-environment jsdom
/**
 * ChapBook review master/detail (P10-C 4, ledger §6.C 266).
 *
 * Why: the review stacked every page card down one column, so a 20-page ChapBook was 20 full editors
 * (stanza, style row, prompt, image, overrides) to scroll through — "chapbook Ux is unwieldy". The review
 * is now the Characters tab's list ↔ detail layout: a page list on the left (one row per page, the
 * selected row highlighted, unsaved / image outdated / needs prompt / image ready badges) and ONE page
 * card on the right. The selection is the page's objectId: it survives a reload (split keeps the page,
 * merge keeps the absorbing page), and a reload that no longer finds it (Remove) lands on the page that
 * took its index, clamped to the last page.
 *
 * No network: the review's module state is seeded through __setReviewStateForTest and the view is
 * rendered as a vnode tree. The card's own contents (renderSceneCard) are unchanged and covered by the
 * other review tests; this file asserts the list, the single card, and the selection rules.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';

const BOOK = 'cb-md-book';

function scene(n, extra) {
    return Object.assign({
        objectId: 'cs' + n, id: 10 + n, name: 'Scene ' + n, sceneIndex: n, title: 'Title ' + n,
        poemStanza: 'Stanza ' + n, pageFont: '', pageBgColor: '', pageBgOpacity: null, pageTextAlign: '', pageTextColor: '',
        configOverride: null, sdPrompt: 'a quiet harbour at dusk, page ' + n, imageObjectId: null, imageStale: false,
        sceneNodeObjectId: null, pinned: false, mood: '', location: null, locationVariant: null, promptLocked: true,
        _dirty: new Set(), _saving: false
    }, extra || {});
}

/** Every vnode in the tree (depth-first) for which pred(vnode) is true. */
function findAll(vnode, pred, out) {
    out = out || [];
    if (vnode == null || vnode === false) return out;
    if (Array.isArray(vnode)) { vnode.forEach(v => findAll(v, pred, out)); return out; }
    if (typeof vnode !== 'object') return out;
    if (pred(vnode)) out.push(vnode);
    findAll(vnode.children, pred, out);
    return out;
}
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}
function pageItems(view) { return findAll(view, v => v.attrs && 'data-page-item' in v.attrs); }
function selectedItems(view) { return pageItems(view).filter(v => v.attrs['data-page-selected'] === '1'); }
function stanzaTextareas(view) { return findAll(view, v => v.tag === 'textarea' && v.attrs && v.attrs.rows === 12); }
function badgesOf(item) { return findAll(item, v => v.attrs && v.attrs['data-page-badge']).map(v => v.attrs['data-page-badge']); }

let cb;

function seed(scenes, selectedOid, previews) {
    cb.__setReviewStateForTest({
        reviewBookObjectId: BOOK,
        reviewBook: { objectId: BOOK, name: 'Tidewater', slug: 'tidewater', fixPageHeight: false },
        reviewScenes: scenes,
        reviewSelectedOid: selectedOid === undefined ? null : selectedOid,
        reviewSceneImageUrls: previews || {},
        reviewCovers: null,
        reviewExporting: false,
        roleWarning: false
    });
}

beforeEach(async () => {
    cb = await import('../features/chapBook.js');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m, 'redraw').mockImplementation(() => {});
});

afterEach(() => {
    cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewBook: null, reviewScenes: [], reviewSelectedOid: null, reviewSceneImageUrls: {}, reviewCovers: null, reviewExporting: false });
    vi.restoreAllMocks();
});

describe('ChapBook review master/detail — list + one card (P10-C 4)', () => {
    it('renders one list row per page and exactly ONE page card, the first page selected by default', () => {
        seed([scene(1), scene(2), scene(3)], null);
        let view = cb.ChapBookReview.view();
        let items = pageItems(view);
        expect(items.map(v => v.attrs['data-page-item'])).toEqual(['cs1', 'cs2', 'cs3']);
        expect(selectedItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs1']);
        // One stanza editor (the card), not three — the whole point of the layout.
        expect(stanzaTextareas(view).length).toBe(1);
        let text = textOf(view);
        expect(text).toMatch(/Page 1 of 3/);
        expect(text).not.toMatch(/Page 2 of 3/);
        expect(text).not.toMatch(/Page 3 of 3/);
        // The list carries the page count and the titles.
        expect(text).toMatch(/Pages3/);
        expect(text).toMatch(/Title 2/);
        // Every page-scoped action is on the one card only.
        expect(findAll(view, v => v.attrs && v.attrs.title === 'Remove this page').length).toBe(1);
        expect(findAll(view, v => v.attrs && v.attrs['data-testid'] === 'cb-page-rerender').length).toBe(1);
        expect(findAll(view, v => v.attrs && v.attrs['data-testid'] === 'cb-page-rerender')[0].attrs['data-scene-oid']).toBe('cs1');
    });

    it('selecting a page (selectReviewPage / the row onclick) swaps the card; the other pages stay in the list', () => {
        seed([scene(1), scene(2), scene(3)], null);
        cb.selectReviewPage('cs2');
        let view = cb.ChapBookReview.view();
        expect(selectedItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs2']);
        expect(cb.selectedReviewIndex()).toBe(1);
        expect(textOf(view)).toMatch(/Page 2 of 3/);
        expect(stanzaTextareas(view).length).toBe(1);
        expect(stanzaTextareas(view)[0].attrs.value).toBe('Stanza 2');
        expect(findAll(view, v => v.attrs && v.attrs['data-testid'] === 'cb-page-rerender')[0].attrs['data-scene-oid']).toBe('cs2');
        expect(pageItems(view).length).toBe(3);

        // The row's onclick is the same path.
        let row3 = pageItems(view).find(v => v.attrs['data-page-item'] === 'cs3');
        row3.attrs.onclick();
        view = cb.ChapBookReview.view();
        expect(selectedItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs3']);
        expect(textOf(view)).toMatch(/Page 3 of 3/);
        // Merge is disabled on the last page, as before.
        let merge = findAll(view, v => v.attrs && v.attrs.title === 'Merge this page with the next')[0];
        expect(merge.attrs.disabled).toBe(true);
    });

    it('an unsaved edit on a page stays flagged in the list after switching away and is still there on return', () => {
        let s2 = scene(2);
        seed([scene(1), s2, scene(3)], 'cs2');
        s2.title = 'Edited title';
        s2._dirty.add('title');
        let view = cb.ChapBookReview.view();
        let row2 = pageItems(view).find(v => v.attrs['data-page-item'] === 'cs2');
        expect(badgesOf(row2)).toContain('unsaved');
        expect(badgesOf(pageItems(view).find(v => v.attrs['data-page-item'] === 'cs1'))).not.toContain('unsaved');
        cb.selectReviewPage('cs1');
        view = cb.ChapBookReview.view();
        expect(badgesOf(pageItems(view).find(v => v.attrs['data-page-item'] === 'cs2'))).toContain('unsaved');
        cb.selectReviewPage('cs2');
        view = cb.ChapBookReview.view();
        expect(findAll(view, v => v.tag === 'input' && v.attrs && v.attrs.type === 'text')[0].attrs.value).toBe('Edited title');
        expect(findAll(view, v => v.attrs && v.attrs.title === 'Save unsaved changes on this page').length).toBe(1);
    });

    it('list badges: image outdated, needs prompt, image ready, Saving…', () => {
        seed([
            scene(1, { imageObjectId: 'img-1', imageStale: true }),
            scene(2, { promptLocked: false, sdPrompt: '' }),
            scene(3, { imageObjectId: 'img-3' }),
            scene(4, { _saving: true })
        ], 'cs1', { cs3: '/media/x/page3.png' });
        let view = cb.ChapBookReview.view();
        let by = {};
        pageItems(view).forEach(v => { by[v.attrs['data-page-item']] = badgesOf(v); });
        expect(by.cs1).toEqual(['image-outdated']);
        expect(by.cs2).toEqual(['needs-prompt']);
        expect(by.cs3).toEqual(['image-ready']);
        expect(by.cs4).toEqual([]);
        expect(textOf(pageItems(view).find(v => v.attrs['data-page-item'] === 'cs4'))).toMatch(/Saving…/);
    });

    it('ensureReviewSelection: keeps a page that is still present, re-seats on the page at the old index (clamped) when it is gone, first page when nothing was selected, null with no pages', () => {
        // Still present (split / merge survivor / save): untouched.
        seed([scene(1), scene(2), scene(3)], 'cs2');
        expect(cb.ensureReviewSelection(1)).toBe('cs2');
        expect(cb.ensureReviewSelection(0)).toBe('cs2');
        // Gone from the middle (Remove page 2): the page that moved up into index 1.
        seed([scene(1), scene(3)], 'cs2');
        expect(cb.ensureReviewSelection(1)).toBe('cs3');
        // Gone from the end (Remove the last page): clamped to the new last page.
        seed([scene(1), scene(2)], 'cs3');
        expect(cb.ensureReviewSelection(2)).toBe('cs2');
        // Nothing selected before (first load): page 1.
        seed([scene(1), scene(2)], null);
        expect(cb.ensureReviewSelection(-1)).toBe('cs1');
        // A stale selection with no hint: page 1.
        seed([scene(1), scene(2)], 'gone');
        expect(cb.ensureReviewSelection(undefined)).toBe('cs1');
        // No pages: null, and the view shows the empty state (no list, no card).
        seed([], 'cs1');
        expect(cb.ensureReviewSelection(0)).toBeNull();
        let view = cb.ChapBookReview.view();
        expect(pageItems(view).length).toBe(0);
        expect(stanzaTextareas(view).length).toBe(0);
        expect(textOf(view)).toMatch(/No pages yet/);
    });

    it('a selection pointing at no page is re-seated on page 1 by the view itself (a seeded or stale oid never blanks the detail pane)', () => {
        seed([scene(1), scene(2)], 'not-a-page');
        let view = cb.ChapBookReview.view();
        expect(selectedItems(view).map(v => v.attrs['data-page-item'])).toEqual(['cs1']);
        expect(stanzaTextareas(view).length).toBe(1);
        expect(cb.selectedReviewIndex()).toBe(0);
    });

    it('the standalone list renderer lists every page with its index-based label', () => {
        seed([scene(1), scene(2, { title: '' })], 'cs2');
        let list = cb.__renderReviewPageListForTest();
        expect(list.attrs['data-testid']).toBe('cb-page-list');
        let items = pageItems(list);
        expect(items.length).toBe(2);
        expect(textOf(items[0])).toMatch(/Page 1/);
        expect(textOf(items[0])).toMatch(/Title 1/);
        expect(textOf(items[1])).toBe('Page 2');
        expect(items[1].attrs.title).toBe('Page 2');
        expect(items[1].attrs['data-page-selected']).toBe('1');
    });
});
