// @vitest-environment jsdom
/**
 * P9-0 §16 step 7 (ledger §6.C 233 d → 234): decorator.js `TabularMemo` kept its render-memo key in a
 * module-global `_tabState`, shared by every table alive and surviving a table's teardown. Reproduced live
 * (scratch/p9-0/probe-picker-memo.mjs): pick row X in the chat-config picker, confirm, reopen the picker,
 * click X again — the selection toggles and confirm applies it, but the row never shows
 * `tabular-row-active`, because the fresh table's first post-click key equals the previous instance's last
 * key, so `onbeforeupdate` returns false and the stale DOM stays.
 *
 * The tests mount the real component with real Mithril and read the row class back from the DOM — the
 * same observation the Playwright step makes. Also pins the hot path the memo exists for: within ONE
 * instance an unchanged key still skips the re-render.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import m from 'mithril';
import { getTabularView } from '../components/decorator.js';

const ROWS = [
    { schema: 'data.data', objectId: 'cfg-a', id: 1, name: '000-p90-gate-llm' },
    { schema: 'data.data', objectId: 'cfg-b', id: 2, name: 'generalChat' }
];

// The shape renderTabular / checkedKey / getTabularRow read off ctl.pagination (pagination.js getPageState).
function makeCtl(rset) {
    let pages = { sort: 'name', order: 'ascending', startRecord: 0, pageState: {} };
    return {
        listType: 'data.data',
        onscroll: null,
        rset,
        pagination: {
            pages: () => pages,
            state(o) {
                if (!pages.pageState[o.objectId]) pages.pageState[o.objectId] = { checked: false };
                return pages.pageState[o.objectId];
            }
        }
    };
}

function mount(ctl) {
    let container = document.createElement('div');
    document.body.appendChild(container);
    m.render(container, getTabularView(ctl, ctl.rset));
    return container;
}

function redraw(container, ctl) {
    m.render(container, getTabularView(ctl, ctl.rset));
}

function unmount(container) {
    m.render(container, null);
    container.remove();
}

function rowFor(container, objectId) {
    return [...container.querySelectorAll('tr.tabular-row')].find(tr => tr.textContent.includes(objectId === 'cfg-a' ? '000-p90-gate-llm' : 'generalChat'));
}

beforeEach(() => { document.body.innerHTML = ''; });

describe('TabularMemo memo key is per instance (P9-0 step 7, §6.C 234)', () => {
    it('a fresh table whose first post-click key equals the previous table\'s last key still re-renders — the clicked row highlights', () => {
        // Picker open 1: click row A, confirm (the picker is torn down).
        let ctl1 = makeCtl(ROWS);
        let c1 = mount(ctl1);
        expect(rowFor(c1, 'cfg-a').className).not.toContain('tabular-row-active');
        ctl1.pagination.state(ROWS[0]).checked = true;
        redraw(c1, ctl1);
        expect(rowFor(c1, 'cfg-a').className).toContain('tabular-row-active');
        unmount(c1);

        // Picker open 2: same cached result array (am7client serves the same `rset` reference), same sort/order/start/type,
        // nothing checked on first paint, then the user clicks row A again → key == instance 1's last key.
        let ctl2 = makeCtl(ROWS);
        let c2 = mount(ctl2);
        expect(rowFor(c2, 'cfg-a').className, 'fresh picker must not inherit the old highlight').not.toContain('tabular-row-active');
        ctl2.pagination.state(ROWS[0]).checked = true;
        redraw(c2, ctl2);
        expect(rowFor(c2, 'cfg-a').className, 'row clicked in the re-opened picker never highlighted (module-global memo key)').toContain('tabular-row-active');
    });

    it('two tables alive at once (list view behind a picker overlay) do not share a memo key', () => {
        let list = makeCtl(ROWS);
        let picker = makeCtl(ROWS);
        let cl = mount(list);
        let cp = mount(picker);
        // Check A in the list, redraw both; then check A in the picker and redraw the picker — its key now equals the list's.
        list.pagination.state(ROWS[0]).checked = true;
        redraw(cl, list);
        redraw(cp, picker);
        expect(rowFor(cl, 'cfg-a').className).toContain('tabular-row-active');
        expect(rowFor(cp, 'cfg-a').className).not.toContain('tabular-row-active');
        picker.pagination.state(ROWS[0]).checked = true;
        redraw(cp, picker);
        expect(rowFor(cp, 'cfg-a').className, 'picker row click lost to the list view\'s memo key').toContain('tabular-row-active');
        // And back: uncheck in the picker, the list must keep its own highlight untouched.
        picker.pagination.state(ROWS[0]).checked = false;
        redraw(cp, picker);
        redraw(cl, list);
        expect(rowFor(cp, 'cfg-a').className).not.toContain('tabular-row-active');
        expect(rowFor(cl, 'cfg-a').className).toContain('tabular-row-active');
    });

    it('within one instance an unchanged key still skips the re-render (the memo still memoizes)', () => {
        let ctl = makeCtl(ROWS);
        let c = mount(ctl);
        let tbodyBefore = c.querySelector('tbody');
        let rowBefore = rowFor(c, 'cfg-a');
        redraw(c, ctl);
        // onbeforeupdate → false keeps the old subtree: same DOM nodes, not just equal markup.
        expect(c.querySelector('tbody')).toBe(tbodyBefore);
        expect(rowFor(c, 'cfg-a')).toBe(rowBefore);
        // A key change (check) re-renders; a second redraw with the same key skips again.
        ctl.pagination.state(ROWS[1]).checked = true;
        redraw(c, ctl);
        let rowB = rowFor(c, 'cfg-b');
        expect(rowB.className).toContain('tabular-row-active');
        redraw(c, ctl);
        expect(rowFor(c, 'cfg-b')).toBe(rowB);
    });
});
