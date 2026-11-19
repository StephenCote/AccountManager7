/**
 * @vitest-environment jsdom
 *
 * U0 of aiDocs/PictureBookUxConsistencyDesign.md: the pure navigation rules (bookWorkspaceNav), the dirty
 * guard's decision + save/discard behaviour (dirtyGuard), the activity store (bookActivity), and the shared
 * chrome rendered with the real Mithril renderer (bookHeader, bookWorkspace rail/footer, masterDetail).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import m from 'mithril';
import {
    PB_SECTIONS, CB_SECTIONS, nextSection, nextAfter, adjacent, editRoute, readRoute, legacyRedirect,
    editLink, guardDecision, ensureSelection, isIncomplete, editQuery, chapBookRouteFor
} from '../workflows/bookWorkspaceNav.js';
import { registerDirty, unregisterDirty, anyDirty, guardDirty, __resetDirtyForTest } from '../components/dirtyGuard.js';
import { Dialog } from '../components/dialogCore.js';
import { setActivity, clearActivity, activitiesFor, summaryFor, __resetActivityForTest } from '../workflows/bookActivity.js';
import { bookHeader } from '../components/bookHeader.js';
import { masterDetail, segmented, stateBlock } from '../components/bookUi.js';
import { createBookWorkspace } from '../components/bookWorkspace.js';

function render(vnode) {
    let div = document.createElement('div');
    m.render(div, vnode);
    return div;
}

describe('bookWorkspaceNav — routes', () => {
    it('edit / read routes for both products', () => {
        expect(editRoute('pb', 'B1')).toBe('/picture-book/B1/edit');
        expect(editRoute('pb', 'B1', 'scenes', 'S9', { tab: 'composition', guided: 1 })).toBe('/picture-book/B1/edit/scenes/S9?tab=composition&guided=1');
        expect(editRoute('cb', 'C1', 'pages')).toBe('/chap-book/edit/C1/pages');
        expect(readRoute('pb', 'B1')).toBe('/picture-book/v2/B1');
        expect(readRoute('cb', 'C1')).toBe('/chap-book/read/C1');
        expect(readRoute('pb', null)).toBe('/picture-book');
    });

    it('legacy sub-routes redirect to their Edit sections (D8)', () => {
        expect(legacyRedirect('/picture-book/B1/style')).toBe('/picture-book/B1/edit/style');
        expect(legacyRedirect('/picture-book/B1/locations')).toBe('/picture-book/B1/edit/locations');
        expect(legacyRedirect('/picture-book/B1/review')).toBe('/picture-book/B1/edit/review');
        expect(legacyRedirect('/picture-book/B1/compose')).toBe('/picture-book/B1/edit/scenes');
        expect(legacyRedirect('/picture-book/B1/compose/S2')).toBe('/picture-book/B1/edit/scenes/S2?tab=composition');
        expect(legacyRedirect('/picture-book/B1/cover')).toBe('/picture-book/B1/edit/cover');
        expect(legacyRedirect('/picture-book/B1/cover/title')).toBe('/picture-book/B1/edit/cover/title');
        expect(legacyRedirect('/picture-book/B1/workflow')).toBe('/picture-book/B1/edit/canvas');
        expect(legacyRedirect('/chap-book/review/C1')).toBe('/chap-book/edit/C1/pages');
        expect(legacyRedirect('/picture-book/v2/B1')).toBeNull();
        expect(legacyRedirect('/picture-book/B1')).toBeNull();
        expect(legacyRedirect('/picture-book/v2/style')).toBeNull();
    });

    it('a ChapBook on a PictureBook route maps to its own product (§8.2)', () => {
        expect(chapBookRouteFor('/picture-book/v2/C1', 'C1')).toBe('/chap-book/read/C1');
        expect(chapBookRouteFor('/picture-book/C1/edit', 'C1')).toBe('/chap-book/edit/C1');
        expect(chapBookRouteFor('/picture-book/C1/edit/scenes/S1?tab=page', 'C1')).toBe('/chap-book/edit/C1/pages/S1?tab=page');
        expect(chapBookRouteFor('/picture-book/C1/edit/locations/L1', 'C1')).toBe('/chap-book/edit/C1/locations/L1');
        expect(chapBookRouteFor('/picture-book/C1/edit/characters/P1', 'C1')).toBe('/chap-book/edit/C1');
        expect(chapBookRouteFor('/picture-book/C1/edit/canvas', 'C1')).toBe('/chap-book/edit/C1/canvas');
        expect(chapBookRouteFor('/picture-book/OTHER/edit/style', 'C1')).toBeNull();
        expect(chapBookRouteFor('/picture-book', 'C1')).toBeNull();
    });

    it('editLink knows where each entity is edited (§4.7)', () => {
        expect(editLink('pb', 'B', { type: 'character', id: 'P1' })).toBe('/picture-book/B/edit/characters/P1');
        expect(editLink('pb', 'B', { type: 'location', id: 'L1' })).toBe('/picture-book/B/edit/locations/L1');
        expect(editLink('pb', 'B', { type: 'scene', id: 'S1', tab: 'page' })).toBe('/picture-book/B/edit/scenes/S1?tab=page');
        expect(editLink('cb', 'C', { type: 'page', id: 'S1' })).toBe('/chap-book/edit/C/pages/S1');
        expect(editLink('cb', 'C', { type: 'location', id: 'L' })).toBe('/chap-book/edit/C/locations/L');
        expect(editLink('pb', 'B', { type: 'style' })).toBe('/picture-book/B/edit/style');
        expect(editLink('pb', 'B', {})).toBe('/picture-book/B/edit');
    });

    it('editQuery reads tab and guided', () => {
        expect(editQuery({ tab: 'story', guided: '1' })).toEqual({ tab: 'story', guided: true });
        expect(editQuery({})).toEqual({ tab: null, guided: false });
    });
});

describe('bookWorkspaceNav — D6 next incomplete section', () => {
    it('isIncomplete: attention, or a positive count', () => {
        expect(isIncomplete({ kind: 'attention' })).toBe(true);
        expect(isIncomplete({ kind: 'count', count: 2 })).toBe(true);
        expect(isIncomplete({ kind: 'count', count: 0 })).toBe(false);
        expect(isIncomplete({ kind: 'ok' })).toBe(false);
        expect(isIncomplete({ kind: 'running' })).toBe(false);
        expect(isIncomplete(null)).toBe(false);
    });

    it('opens the first incomplete section in rail order', () => {
        expect(nextSection('pb', { book: { kind: 'ok' }, style: { kind: 'attention' }, scenes: { kind: 'count', count: 3 } })).toBe('style');
        expect(nextSection('pb', { style: { kind: 'ok' }, characters: { kind: 'count', count: 1 } })).toBe('characters');
        expect(nextSection('pb', { book: { kind: 'attention' }, style: { kind: 'attention' } })).toBe('book');
    });

    it('falls back to Scenes / Pages when nothing is incomplete or statuses are unknown', () => {
        expect(nextSection('pb', {})).toBe('scenes');
        expect(nextSection('pb', { style: { kind: 'ok' }, review: { kind: 'count', count: 0 } })).toBe('scenes');
        expect(nextSection('cb', {})).toBe('pages');
    });

    it('never picks an advanced section (the canvas)', () => {
        expect(nextSection('pb', { canvas: { kind: 'attention' } })).toBe('scenes');
    });

    it('guided Next skips to the next incomplete section after the current one, else the adjacent one', () => {
        let st = { style: { kind: 'ok' }, locations: { kind: 'ok' }, scenes: { kind: 'count', count: 4 }, render: { kind: 'count', count: 4 } };
        expect(nextAfter('pb', st, 'characters')).toBe('scenes');
        expect(nextAfter('pb', st, 'scenes')).toBe('render');
        expect(nextAfter('pb', {}, 'characters')).toBe('locations');
        expect(nextAfter('pb', {}, 'render')).toBeNull();
        expect(nextAfter('cb', {}, 'render')).toBeNull();
    });

    it('adjacent keeps advanced sections apart from the main sequence', () => {
        expect(adjacent('pb', 'book')).toEqual({ prev: null, next: 'style' });
        expect(adjacent('pb', 'render')).toEqual({ prev: 'review', next: null });
        expect(adjacent('pb', 'canvas')).toEqual({ prev: null, next: null });
        expect(adjacent('cb', 'pages')).toEqual({ prev: 'locations', next: 'cover' });
    });

    it('section sets: ChapBook has no Characters / Scenes / Canvas', () => {
        let cb = CB_SECTIONS.map(s => s.id);
        expect(cb).not.toContain('characters');
        expect(cb).not.toContain('scenes');
        expect(cb[cb.length - 1]).toBe('canvas');   // advanced, as for PictureBook (ChapBooks have a graph)
        expect(nextSection('cb', { canvas: { kind: 'attention' } })).toBe('pages');
        expect(PB_SECTIONS.map(s => s.id)).toEqual(['book', 'style', 'characters', 'locations', 'scenes', 'cover', 'review', 'render', 'canvas']);
    });
});

describe('bookWorkspaceNav — guard decision and selection re-seat', () => {
    it('guardDecision table', () => {
        expect(guardDecision(false, null)).toEqual({ save: false, proceed: true });
        expect(guardDecision(true, 'save')).toEqual({ save: true, proceed: true });
        expect(guardDecision(true, 'discard')).toEqual({ save: false, proceed: true });
        expect(guardDecision(true, 'stay')).toEqual({ save: false, proceed: false });
        expect(guardDecision(true, undefined)).toEqual({ save: false, proceed: false });
    });

    it('ensureSelection keeps, re-seats by index, else first; null when empty', () => {
        expect(ensureSelection(['a', 'b', 'c'], 'b', 1)).toBe('b');
        expect(ensureSelection(['a', 'c'], 'b', 1)).toBe('c');
        expect(ensureSelection(['a'], 'z', 5)).toBe('a');
        expect(ensureSelection(['a', 'b'], null)).toBe('a');
        expect(ensureSelection([], 'a', 0)).toBeNull();
    });
});

describe('dirtyGuard', () => {
    let opened;
    beforeEach(() => {
        __resetDirtyForTest();
        opened = [];
        vi.restoreAllMocks();
        vi.spyOn(Dialog, 'open').mockImplementation(function (cfg) { opened.push(cfg); });
        vi.spyOn(Dialog, 'close').mockImplementation(function () {
            let cfg = opened.pop();
            if (cfg && cfg.onClose) cfg.onClose();
        });
    });

    function click(label) {
        let cfg = opened[opened.length - 1];
        cfg.actions.find(a => a.label === label).onclick();
    }

    it('runs next immediately when nothing is dirty', async () => {
        let ran = 0;
        expect(await guardDirty(() => { ran++; })).toBe(true);
        expect(ran).toBe(1);
        expect(opened.length).toBe(0);
    });

    it('Stay does not proceed and does not save', async () => {
        let saved = 0, ran = 0;
        registerDirty('k', { label: 'Scene 3', isDirty: () => true, save: async () => { saved++; return true; } });
        let p = guardDirty(() => { ran++; });
        await Promise.resolve();
        expect(opened.length).toBe(1);
        click('Stay');
        expect(await p).toBe(false);
        expect(saved).toBe(0);
        expect(ran).toBe(0);
    });

    it('Save saves then proceeds; a failed save stays', async () => {
        let ran = 0;
        registerDirty('k', { isDirty: () => true, save: async () => true });
        let p = guardDirty(() => { ran++; });
        await Promise.resolve();
        click('Save');
        expect(await p).toBe(true);
        expect(ran).toBe(1);

        registerDirty('k', { isDirty: () => true, save: async () => false });
        let p2 = guardDirty(() => { ran++; });
        await Promise.resolve();
        click('Save');
        expect(await p2).toBe(false);
        expect(ran).toBe(1);
    });

    it('Discard calls discard and proceeds; keys limit the guard; closing the dialog = Stay', async () => {
        let discarded = 0, ran = 0;
        registerDirty('a', { isDirty: () => true, discard: () => { discarded++; } });
        registerDirty('b', { isDirty: () => true });
        expect(anyDirty(['a'])).toBe(true);
        let p = guardDirty(() => { ran++; }, ['a']);
        await Promise.resolve();
        click('Discard');
        expect(await p).toBe(true);
        expect(discarded).toBe(1);
        expect(ran).toBe(1);

        unregisterDirty('a');
        expect(await guardDirty(() => { ran++; }, ['a'])).toBe(true); // only b is dirty, not guarded
        expect(ran).toBe(2);

        let p3 = guardDirty(() => { ran++; });
        await Promise.resolve();
        Dialog.close(); // X / backdrop
        expect(await p3).toBe(false);
        expect(ran).toBe(2);
    });
});

describe('bookActivity', () => {
    beforeEach(() => __resetActivityForTest());

    it('summaryFor prefers the newest running activity, else the newest finished one', () => {
        expect(summaryFor('B')).toBeNull();
        setActivity('B', 'render', { label: 'Render', counter: '1 of 4', terminal: false, section: 'render' });
        setActivity('B', 'loc', { label: 'Location render', terminal: true, section: 'locations' });
        let s = summaryFor('B');
        expect(s.key).toBe('render');
        expect(s.running).toBe(true);
        expect(s.count).toBe(2);
        setActivity('B', 'render', { label: 'Render', counter: 'done', terminal: true, section: 'render' });
        s = summaryFor('B');
        expect(s.running).toBe(false);
        expect(activitiesFor('B').map(a => a.key)).toEqual(['loc', 'render']);
        clearActivity('B', 'loc');
        clearActivity('B', 'render');
        expect(summaryFor('B')).toBeNull();
    });
});

describe('shared chrome renders', () => {
    beforeEach(() => { __resetActivityForTest(); __resetDirtyForTest(); });

    it('bookHeader: Read mode has no Editing chip; Edit mode does; the mode toggle marks the active mode', () => {
        let read = render(bookHeader({ kind: 'pb', bookObjectId: 'B', mode: 'read', title: 'My Book', series: 'Saga' }));
        expect(read.querySelector('[data-testid="book-header"]').getAttribute('data-mode')).toBe('read');
        expect(read.querySelector('[data-testid="book-editing"]')).toBeNull();
        expect(read.querySelector('[data-testid="book-mode-read"]').getAttribute('data-active')).toBe('1');
        expect(read.querySelector('[data-testid="book-series"]').textContent).toContain('Saga');
        expect(read.querySelector('[data-testid="book-back"]').getAttribute('title')).toBe('Back to Picture Books');
        let edit = render(bookHeader({ kind: 'cb', bookObjectId: 'C', mode: 'edit', title: 'Poems' }));
        expect(edit.querySelector('[data-testid="book-editing"]')).not.toBeNull();
        expect(edit.querySelector('[data-testid="book-mode-edit"]').getAttribute('data-active')).toBe('1');
        expect(edit.querySelector('[data-testid="book-back"]').getAttribute('title')).toBe('Back to ChapBooks');
    });

    it('bookHeader: the activity pill appears only with an activity', () => {
        let none = render(bookHeader({ kind: 'pb', bookObjectId: 'B', mode: 'read', title: 'T' }));
        expect(none.querySelector('[data-testid="book-activity"]')).toBeNull();
        setActivity('B', 'render', { label: 'Render', counter: '2 of 5', terminal: false });
        let el = render(bookHeader({ kind: 'pb', bookObjectId: 'B', mode: 'read', title: 'T' }));
        let pill = el.querySelector('[data-testid="book-activity"]');
        expect(pill.getAttribute('data-running')).toBe('1');
        expect(el.querySelector('[data-testid="book-activity-counter"]').textContent).toBe('2 of 5');
    });

    it('segmented calls onchange only for a different value', () => {
        let got = [];
        let el = render(segmented({ testid: 's', value: 'a', onchange: v => got.push(v), options: [{ value: 'a', label: 'A', testid: 'sa' }, { value: 'b', label: 'B', testid: 'sb' }] }));
        el.querySelector('[data-testid="sa"]').click();
        el.querySelector('[data-testid="sb"]').click();
        expect(got).toEqual(['b']);
    });

    it('stateBlock: loading > error > empty > null', () => {
        expect(render(stateBlock({ loading: true, error: 'x', testids: { loading: 'l' } })).querySelector('[data-testid="l"]')).not.toBeNull();
        expect(render(stateBlock({ error: 'boom', testids: { error: 'e' } })).querySelector('[data-testid="e"]').textContent).toBe('boom');
        expect(render(stateBlock({ empty: true, emptyText: 'None', testids: { empty: 'z' } })).querySelector('[data-testid="z"]').textContent).toBe('None');
        expect(stateBlock({})).toBeNull();
    });

    it('masterDetail: rows, selection, badges, dirty dot, detail; arrow keys move the selection', () => {
        let picked = [];
        let items = [{ objectId: 'a', name: 'Alpha' }, { objectId: 'b', name: 'Beta' }];
        let el = render(masterDetail({
            testid: 'md', listTestid: 'md-list', rowTestid: 'md-row', detailTestid: 'md-detail',
            items, selectedId: 'a',
            rowView: it => ({ title: it.name, badges: [{ text: 'stale', tone: 'amber' }], dirty: it.objectId === 'a' }),
            detailView: it => m('div', { 'data-testid': 'md-d' }, 'Detail ' + it.name),
            onSelect: id => picked.push(id)
        }));
        let rows = el.querySelectorAll('[data-testid="md-row"]');
        expect(rows.length).toBe(2);
        expect(rows[0].getAttribute('data-selected')).toBe('1');
        expect(rows[0].querySelector('[data-row-dirty]')).not.toBeNull();
        expect(rows[1].querySelector('[data-row-dirty]')).toBeNull();
        expect(el.querySelector('[data-testid="md-d"]').textContent).toBe('Detail Alpha');
        rows[1].click();
        rows[0].click(); // already selected → no call
        let list = el.querySelector('[data-testid="md-list"]');
        list.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        expect(picked).toEqual(['b', 'b']);
    });

    it('bookWorkspace: rail shows statuses, the active section, dirty dot; footer Back/Next; unavailable sections are hidden', () => {
        let inits = [];
        registerDirty('style-draft', { isDirty: () => true });
        let ws = createBookWorkspace({
            kind: 'pb',
            header: () => ({ title: 'Book' }),
            adapters: {
                book: { status: () => ({ kind: 'ok' }), body: () => m('div', 'book') },
                style: { dirtyKeys: ['style-draft'], status: () => ({ kind: 'attention' }), init: c => inits.push(c.section), toolbar: () => ({ title: 'Book style' }), body: () => m('div', { 'data-testid': 'style-body' }, 'style') },
                characters: { status: () => ({ kind: 'count', count: 2 }), body: () => null },
                cover: { available: () => false, body: () => null },
                scenes: { body: () => null }
            }
        });
        let div = document.createElement('div');
        m.render(div, m(ws.view, { bookObjectId: 'B', section: 'style' }));
        expect(inits).toEqual(['style']);
        expect(div.querySelector('[data-testid="book-header"]').getAttribute('data-mode')).toBe('edit');
        expect(div.querySelector('[data-testid="ws-rail-style"]').getAttribute('data-active')).toBe('1');
        expect(div.querySelector('[data-testid="ws-rail-style"]').getAttribute('data-status')).toBe('attention');
        expect(div.querySelector('[data-testid="ws-rail-style"] [data-rail-dirty]')).not.toBeNull();
        expect(div.querySelector('[data-testid="ws-rail-characters"] [data-count="2"]')).not.toBeNull();
        expect(div.querySelector('[data-testid="ws-rail-book"]').getAttribute('data-status')).toBe('ok');
        expect(div.querySelector('[data-testid="ws-rail-cover"]')).toBeNull();
        expect(div.querySelector('[data-testid="ws-rail-locations"]')).toBeNull(); // no adapter → not offered
        expect(div.querySelector('[data-testid="style-body"]')).not.toBeNull();
        expect(div.querySelector('[data-testid="ws-pane"] [data-page-header]')).not.toBeNull();
        expect(div.querySelector('[data-testid="ws-back"]').textContent).toContain('Book');
        // next adjacent from style is characters (available)
        expect(div.querySelector('[data-testid="ws-next"]').textContent).toContain('Characters');
        // re-render with same section does not re-init
        m.render(div, m(ws.view, { bookObjectId: 'B', section: 'style' }));
        expect(inits).toEqual(['style']);
    });
});
