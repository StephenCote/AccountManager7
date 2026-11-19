/**
 * bookUi — small shared primitives for the PictureBook / ChapBook surfaces
 * (aiDocs/PictureBookUxConsistencyDesign.md §4.4, §4.6). One copy of each; the per-file INPUT / CHIP / field()
 * copies in pictureBookCompose / Locations / Style are replaced by these.
 *
 *   segmented({ options:[{value,label,icon?,testid?,title?}], value, onchange, testid, size })
 *   stateBlock({ loading, error, empty, loadingText, emptyText, testids:{loading,error,empty}, align })
 *       → vnode | null   (null = "render your content")
 *   INPUT, CHIP, WARN_CHIP
 *   field(label, control, { dirty, inherited, hint })
 *   dirtyMark(isDirty, testid)
 *   masterDetail({ testid, items, selectedId, onSelect, rowView, toolbar, detailView, emptyText, listTestid,
 *                  detailTestid, rowTestid, listWidth })
 */
import m from 'mithril';
import { chip } from './pageHeader.js';

export const INPUT = 'w-full px-2 py-1 text-sm rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800';
export const CHIP = 'text-xs px-2 py-0.5 rounded-full bg-gray-200 text-gray-600 dark:bg-gray-700 dark:text-gray-300';
export const WARN_CHIP = 'text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200';

export function segmented(o) {
    o = o || {};
    let opts = o.options || [];
    let sm = o.size !== 'md';
    return m('div', {
        class: 'inline-flex rounded border border-gray-300 dark:border-gray-600 overflow-hidden',
        role: 'radiogroup',
        'data-testid': o.testid,
        'data-value': o.value
    }, opts.map(function (op, i) {
        let active = op.value === o.value;
        return m('button', {
            key: op.value,
            type: 'button',
            role: 'radio',
            'aria-checked': active ? 'true' : 'false',
            'data-testid': op.testid,
            'data-active': active ? '1' : '0',
            title: op.title || op.label,
            disabled: !!op.disabled,
            class: (sm ? 'text-xs px-2.5 py-1' : 'text-sm px-3 py-1.5') + ' flex items-center gap-1 '
                + (i > 0 ? 'border-l border-gray-300 dark:border-gray-600 ' : '')
                + (active ? 'bg-purple-600 text-white' : 'bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700')
                + ' disabled:opacity-40',
            onclick: function () { if (!active && o.onchange) o.onchange(op.value); }
        }, [op.icon ? m('span', { class: 'material-symbols-outlined', style: 'font-size:16px', 'aria-hidden': 'true' }, op.icon) : null, op.label]);
    }));
}

export function stateBlock(o) {
    o = o || {};
    let t = o.testids || {};
    let cls = (o.align === 'left' ? 'text-left p-4' : 'text-center py-12') + ' text-sm';
    if (o.loading) return m('div', { class: cls + ' text-gray-500', 'data-testid': t.loading, 'data-state': 'loading' }, o.loadingText || 'Loading…');
    if (o.error) return m('div', { class: cls + ' text-red-500', 'data-testid': t.error, 'data-state': 'error' }, String(o.error));
    if (o.empty) return m('div', { class: cls + ' text-gray-500', 'data-testid': t.empty, 'data-state': 'empty' }, o.emptyText || 'Nothing here yet.');
    return null;
}

export function dirtyMark(isDirty, testid) {
    return isDirty ? m('span', { class: 'text-amber-500 ml-1', title: 'Unsaved change', 'data-testid': testid, 'data-dirty': '1' }, '•') : null;
}

export function field(label, control, opts) {
    opts = opts || {};
    return m('label', { class: 'block mb-3' }, [
        m('span', { class: 'block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1' }, [
            label, dirtyMark(opts.dirty, opts.dirtyTestid),
            opts.inherited ? m('span', { class: 'ml-2 text-[10px] text-gray-400 italic' }, 'Inherited from ' + opts.inherited) : null
        ]),
        control,
        opts.hint ? m('span', { class: 'block text-[11px] text-gray-400 mt-0.5' }, opts.hint) : null
    ]);
}

/**
 * Master / detail, after the Characters manager (workflows/pictureBookCharacters.js renderContent) and the
 * ChapBook page list: a w-56 list with badges on the left (own scroll), the selected item's detail on the right
 * (own scroll). rowView(item) → { title, subtitle?, badges:[{text, tone, attrs?}], dirty?, attrs? }.
 * Arrow keys move the selection while the list has focus.
 */
export function masterDetail(o) {
    o = o || {};
    let items = o.items || [];
    let idOf = o.idOf || function (it) { return it && (it.objectId || it.id); };
    let sel = o.selectedId;
    let selected = null;
    for (let i = 0; i < items.length; i++) if (idOf(items[i]) === sel) selected = items[i];
    function move(delta) {
        if (!items.length || !o.onSelect) return;
        let idx = -1;
        for (let i = 0; i < items.length; i++) if (idOf(items[i]) === sel) idx = i;
        let n = Math.max(0, Math.min(items.length - 1, idx + delta));
        if (n !== idx) o.onSelect(idOf(items[n]), items[n]);
    }
    return m('div', { class: 'flex flex-1 min-h-0 border border-gray-200 dark:border-gray-700 rounded', 'data-testid': o.testid, style: o.minHeight ? 'min-height:' + o.minHeight : null }, [
        m('div', { class: (o.listWidth || 'w-56') + ' flex-shrink-0 border-r border-gray-200 dark:border-gray-700 flex flex-col min-h-0' }, [
            o.toolbar ? m('div', { class: 'p-2 border-b border-gray-200 dark:border-gray-700 flex flex-wrap items-center gap-2' }, o.toolbar) : null,
            m('div', {
                class: 'flex-1 overflow-y-auto',
                tabindex: 0,
                'data-testid': o.listTestid,
                onkeydown: function (e) {
                    if (e.key === 'ArrowDown') { e.preventDefault(); move(1); }
                    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
                }
            }, items.length ? items.map(function (it) {
                let id = idOf(it);
                let rv = (o.rowView ? o.rowView(it) : null) || { title: String(id) };
                let active = id === sel;
                return m('button', Object.assign({
                    key: id,
                    type: 'button',
                    'data-testid': o.rowTestid,
                    'data-item-id': id,
                    'data-selected': active ? '1' : '0',
                    class: 'w-full text-left px-3 py-2 border-b border-gray-100 dark:border-gray-800 text-sm '
                        + (active ? 'bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300' : 'hover:bg-gray-50 dark:hover:bg-gray-800'),
                    onclick: function () { if (!active && o.onSelect) o.onSelect(id, it); }
                }, rv.attrs || {}), [
                    m('div', { class: 'flex items-center gap-2 min-w-0' }, [
                        rv.thumb ? m('img', { src: rv.thumb, class: 'w-8 h-8 rounded object-cover flex-shrink-0', alt: '' }) : null,
                        m('span', { class: 'truncate flex-1 font-medium' }, rv.title),
                        rv.dirty ? m('span', { class: 'text-amber-500', title: 'Unsaved changes', 'data-row-dirty': '1' }, '•') : null
                    ]),
                    rv.subtitle ? m('div', { class: 'text-xs text-gray-500 truncate' }, rv.subtitle) : null,
                    (rv.badges && rv.badges.length) ? m('div', { class: 'flex flex-wrap gap-1 mt-1' }, rv.badges.filter(Boolean).map(function (b, bi) {
                        return chip(b.text, b.tone || 'gray', Object.assign({ key: bi }, b.attrs || {}));
                    })) : null
                ]);
            }) : m('div', { class: 'p-3 text-xs text-gray-500', 'data-testid': o.emptyTestid }, o.emptyText || 'Nothing here yet.'))
        ]),
        // detailFallback: the route named an item this list does not offer (e.g. Series art on a standalone book) — the
        // host explains it in the pane without adding a row the list deliberately leaves out.
        m('div', { class: 'flex-1 min-w-0 overflow-y-auto p-4', 'data-testid': o.detailTestid }, selected
            ? (o.detailView ? o.detailView(selected) : null)
            : (o.detailFallback || m('div', { class: 'text-sm text-gray-500 text-center py-12' }, items.length ? 'Select an item.' : (o.emptyText || 'Nothing here yet.'))))
    ]);
}
