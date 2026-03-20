/**
 * contextMenu.js — Reusable right-click context menu component (ESM)
 * Usage:
 *   import { contextMenu } from './contextMenu.js';
 *   contextMenu.show(e, [
 *     { label: 'Open', icon: 'open_in_new', action: () => { ... } },
 *     { label: 'Delete', icon: 'delete', action: () => { ... } },
 *     { divider: true },
 *     { label: 'Tag', icon: 'label', action: () => { ... } }
 *   ]);
 */
import m from 'mithril';

let menuState = {
    visible: false,
    x: 0,
    y: 0,
    items: []
};

function show(e, items) {
    e.preventDefault();
    e.stopPropagation();
    menuState.items = items || [];
    menuState.visible = true;
    // Position at cursor, clamped to viewport
    let vw = window.innerWidth;
    let vh = window.innerHeight;
    let menuW = 180;
    let menuH = items.length * 36;
    menuState.x = Math.min(e.clientX, vw - menuW - 8);
    menuState.y = Math.min(e.clientY, vh - menuH - 8);
    m.redraw();
    // Dismiss on next click or Escape
    setTimeout(function () {
        document.addEventListener('click', dismiss, { once: true });
        document.addEventListener('contextmenu', dismiss, { once: true });
        document.addEventListener('keydown', onKeyDown);
    }, 0);
}

function dismiss() {
    menuState.visible = false;
    document.removeEventListener('keydown', onKeyDown);
    m.redraw();
}

function onKeyDown(e) {
    if (e.key === 'Escape') {
        dismiss();
        return;
    }
    // Arrow key navigation within context menu
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        let menu = document.querySelector('.context-menu');
        if (!menu) return;
        let items = Array.from(menu.querySelectorAll('[role="menuitem"]'));
        if (!items.length) return;
        let idx = items.indexOf(document.activeElement);
        if (e.key === 'ArrowDown') idx = (idx + 1) % items.length;
        else idx = (idx - 1 + items.length) % items.length;
        items[idx].focus();
    }
}

function executeItem(item) {
    dismiss();
    if (item.action) item.action();
}

const contextMenuComponent = {
    view: function () {
        if (!menuState.visible || !menuState.items.length) return null;
        return m('div', {
            class: 'context-menu',
            role: 'menu',
            'aria-label': 'Context menu',
            style: 'position:fixed;left:' + menuState.x + 'px;top:' + menuState.y + 'px;z-index:10000',
            oncreate: function(vnode) {
                // Focus first menu item for keyboard access
                let first = vnode.dom.querySelector('[role="menuitem"]');
                if (first) first.focus();
            }
        },
            menuState.items.map(function (item) {
                if (item.divider) {
                    return m('div', { class: 'context-menu-divider', role: 'separator' });
                }
                return m('button', {
                    class: 'context-menu-item',
                    role: 'menuitem',
                    onclick: function () { executeItem(item); }
                }, [
                    item.icon ? m('span', {
                        class: 'material-symbols-outlined',
                        'aria-hidden': 'true',
                        style: 'font-size:16px;margin-right:8px;vertical-align:middle'
                    }, item.icon) : null,
                    m('span', {}, item.label)
                ]);
            })
        );
    }
};

const contextMenu = {
    show: show,
    dismiss: dismiss,
    component: contextMenuComponent
};

export { contextMenu, contextMenuComponent };
export default contextMenu;
