/**
 * Shared page buttons — the one button vocabulary for page/toolbar buttons (styles in main.css
 * `.btn*`). Dialog footer actions keep using Dialog's `actions` array (`am7-dialog-btn-*`).
 *
 *   btnClass('primary', 'sm')            → "btn btn-primary btn-sm"
 *   btn({ label, icon, kind, size, title, disabled, onclick, attrs })
 *   iconBtn(icon, title, onclick, attrs) → borderless icon-only button
 *
 * kind: 'default' | 'primary' | 'secondary' | 'danger' | 'icon';  size: 'md' (default) | 'sm'
 */
import m from 'mithril';

const KINDS = { default: '', primary: 'btn-primary', secondary: 'btn-secondary', danger: 'btn-danger', icon: 'btn-icon' };

function btnClass(kind, size, extra) {
    let cls = ['btn'];
    let k = KINDS[kind || 'default'];
    if (k === undefined) throw new Error('buttons: unknown kind ' + kind);
    if (k) cls.push(k);
    if (size === 'sm') cls.push('btn-sm');
    if (extra) cls.push(extra);
    return cls.join(' ');
}

/** Small-size class constants for code that builds its own vnodes. */
const BTN = btnClass('default', 'sm');
const BTN_PRIMARY = btnClass('primary', 'sm');
const BTN_DANGER = btnClass('danger', 'sm');
const BTN_ICON = btnClass('icon');

function iconNode(icon, size) {
    return m('span', { class: 'material-symbols-outlined', style: 'font-size:' + (size || 16) + 'px', 'aria-hidden': 'true' }, icon);
}

function btn(o) {
    o = o || {};
    let attrs = Object.assign({
        class: btnClass(o.kind, o.size, o.class),
        title: o.title || (typeof o.label === 'string' ? o.label : undefined),
        disabled: !!o.disabled,
        onclick: o.onclick
    }, o.attrs || {});
    if (!o.label && o.title) attrs['aria-label'] = o.title;
    return m('button', attrs, [o.icon ? iconNode(o.icon, o.iconSize) : null, o.label || null]);
}

function iconBtn(icon, title, onclick, attrs) {
    return btn({ icon: icon, title: title, kind: 'icon', onclick: onclick, iconSize: 18, attrs: attrs });
}

export { btnClass, btn, iconBtn, BTN, BTN_PRIMARY, BTN_DANGER, BTN_ICON };
export default { btnClass, btn, iconBtn, BTN, BTN_PRIMARY, BTN_DANGER, BTN_ICON };
