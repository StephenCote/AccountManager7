/**
 * pageHeader — the one "back · title · meta … actions · refresh" header for feature sub-pages
 * (PictureBook Review / Style / Locations / Compose / Workflow, ChapBook Review).
 *
 *   pageHeader({
 *     testid,                                  // data-testid on the header row
 *     back:    { testid, title, onclick },     // optional back arrow (icon-only, accessible name = title)
 *     icon:    'edit_note',                    // optional decorative icon before the title
 *     title:   'Locations', titleTestid, titleTip, titleAttrs: { 'data-wf-title': true },
 *     meta:    [vnode, ...],                   // counts / chips right after the title
 *     actions: [vnode, ...],                   // right-aligned controls
 *     refresh: { testid, disabled, onclick },  // optional trailing refresh icon button
 *     bar:     false                           // true → full-bleed toolbar (border-bottom, no margin)
 *   })
 *
 *   chip(text, tone, attrs) — small status pill; tone: gray | green | amber | purple | red
 */
import m from 'mithril';
import { iconBtn } from './buttons.js';

const CHIP_TONES = {
    gray: 'bg-gray-200 text-gray-600 dark:bg-gray-700 dark:text-gray-300',
    green: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-200',
    amber: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200',
    purple: 'bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200',
    red: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200'
};

function chip(text, tone, attrs) {
    return m('span', Object.assign({}, attrs || {}, {
        class: 'text-xs px-2 py-0.5 rounded-full whitespace-nowrap ' + (CHIP_TONES[tone] || CHIP_TONES.gray) + ((attrs && attrs.class) ? ' ' + attrs.class : '')
    }), text);
}

/** Muted inline text for counts ("3 locations"). */
function metaText(text, attrs) {
    return m('span', Object.assign({}, attrs || {}, { class: 'text-xs text-gray-500 dark:text-gray-400' }), text);
}

function pageHeader(o) {
    o = o || {};
    let rowClass = o.bar
        ? 'flex flex-wrap items-center gap-2 px-4 py-2 border-b border-gray-200 dark:border-gray-700 flex-shrink-0'
        : 'flex flex-wrap items-center gap-3 mb-4';
    let backAttrs = o.back ? Object.assign({ 'data-testid': o.back.testid }, o.back.attrs || {}) : null;
    return m('div', { class: rowClass, 'data-testid': o.testid, 'data-page-header': '1' }, [
        o.back ? iconBtn('arrow_back', o.back.title || 'Back', o.back.onclick, backAttrs) : null,
        o.icon ? m('span', { class: 'material-symbols-outlined text-xl text-gray-500 dark:text-gray-400', 'aria-hidden': 'true' }, o.icon) : null,
        m('h2', Object.assign({
            class: 'text-base font-semibold text-gray-900 dark:text-white truncate',
            'data-testid': o.titleTestid,
            title: o.titleTip
        }, o.titleAttrs || {}), o.title),
        (o.meta || []),
        m('div', { class: 'flex-1' }),
        (o.actions || []),
        o.refresh ? iconBtn('refresh', 'Refresh', o.refresh.onclick, {
            'data-testid': o.refresh.testid, disabled: !!o.refresh.disabled
        }) : null
    ]);
}

export { pageHeader, chip, metaText };
export default pageHeader;
