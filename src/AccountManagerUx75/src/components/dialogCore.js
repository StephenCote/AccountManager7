import m from 'mithril';

// --- Dialog Stack ---

let dialogStack = [];
let previousFocus = [];

function getZIndex(depth) {
    return 40 + (depth * 10);
}

// --- Focus Trap ---

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function trapFocus(e, containerEl) {
    if (e.key !== 'Tab') return;
    if (!containerEl) return;
    let focusable = containerEl.querySelectorAll(FOCUSABLE);
    if (focusable.length === 0) return;
    let first = focusable[0];
    let last = focusable[focusable.length - 1];
    if (e.shiftKey) {
        if (document.activeElement === first) {
            e.preventDefault();
            last.focus();
        }
    } else {
        if (document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }
}

// --- Escape Key Handler ---

function onKeyDown(e) {
    if (e.key === 'Escape' && dialogStack.length > 0) {
        let top = dialogStack[dialogStack.length - 1];
        if (top.closable !== false) {
            Dialog.close();
        }
    }
}

let keyListenerAttached = false;
function ensureKeyListener() {
    if (!keyListenerAttached && typeof document !== 'undefined') {
        document.addEventListener('keydown', onKeyDown);
        keyListenerAttached = true;
    }
}
function removeKeyListener() {
    if (keyListenerAttached && dialogStack.length === 0 && typeof document !== 'undefined') {
        document.removeEventListener('keydown', onKeyDown);
        keyListenerAttached = false;
    }
}

// --- Dialog Rendering ---

function renderDialog(cfg, index) {
    let zIndex = getZIndex(index);
    let isDrawer = cfg.mode === 'drawer';
    let titleId = 'am7-dialog-title-' + index;

    let backdropAttrs = {
        class: 'am7-dialog-backdrop am7-animate-in',
        style: { zIndex },
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': titleId,
        onclick: function (e) {
            if (e.target === e.currentTarget && cfg.closable !== false) {
                Dialog.close();
            }
        },
        oncreate: function (vnode) {
            // Focus first focusable element inside dialog
            let el = vnode.dom.querySelector(FOCUSABLE);
            if (el) el.focus();
            // Attach focus trap
            vnode.dom._trapHandler = function (e) { trapFocus(e, vnode.dom); };
            vnode.dom.addEventListener('keydown', vnode.dom._trapHandler);
        },
        onremove: function (vnode) {
            if (vnode.dom._trapHandler) {
                vnode.dom.removeEventListener('keydown', vnode.dom._trapHandler);
            }
        }
    };

    // Header
    let closeBtn = cfg.closable !== false ? m('button', {
        class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-300',
        onclick: function () { Dialog.close(); },
        'aria-label': 'Close dialog'
    }, m('span', { class: 'material-symbols-outlined' }, 'close')) : null;

    let header = m('div', { class: 'am7-dialog-header' }, [
        m('span', { class: 'am7-dialog-title', id: titleId }, cfg.title || ''),
        closeBtn
    ]);

    // Body
    let body = m('div', { class: 'am7-dialog-body' }, [
        cfg.content || ''
    ]);

    // Footer with actions
    let footer = null;
    if (cfg.actions && cfg.actions.length > 0) {
        let buttons = cfg.actions.map(function (act) {
            let btnClass = 'am7-dialog-btn ';
            if (act.destructive) btnClass += 'am7-dialog-btn-destructive';
            else if (act.primary) btnClass += 'am7-dialog-btn-primary';
            else btnClass += 'am7-dialog-btn-secondary';
            return m('button', {
                class: btnClass,
                onclick: act.onclick
            }, [
                act.icon ? m('span', { class: 'material-symbols-outlined md-18' }, act.icon) : null,
                act.label || ''
            ]);
        });
        footer = m('div', { class: 'am7-dialog-footer' }, buttons);
    }

    // Container
    let containerClass = isDrawer
        ? 'am7-dialog-drawer am7-animate-in'
        : 'am7-dialog am7-dialog-' + (cfg.size || 'md') + ' am7-animate-in';

    let container = m('div', { class: containerClass }, [header, body, footer]);

    return m('div', backdropAttrs, [container]);
}

// --- Dialog API ---

const Dialog = {
    /**
     * Open a dialog.
     * @param {object} cfg
     * @param {string} cfg.title
     * @param {string} cfg.size - "sm"|"md"|"lg"|"xl"|"full"
     * @param {*} cfg.content - Mithril vnode or string
     * @param {Array} cfg.actions - [{label, icon, primary, destructive, onclick}]
     * @param {boolean} cfg.closable - default true
     * @param {Function} cfg.onClose - called on any close
     * @param {string} cfg.mode - "modal"|"drawer", default "modal"
     */
    open: function (cfg) {
        ensureKeyListener();
        if (typeof document !== 'undefined') {
            previousFocus.push(document.activeElement);
        }
        cfg = Object.assign({ closable: true, mode: 'modal', size: 'md' }, cfg);
        dialogStack.push(cfg);
        m.redraw();
    },

    /**
     * Close the top dialog.
     */
    close: function () {
        if (dialogStack.length === 0) return;
        let cfg = dialogStack.pop();
        if (cfg.onClose) cfg.onClose();
        let prev = previousFocus.pop();
        if (prev && prev.focus) {
            setTimeout(function () { prev.focus(); }, 0);
        }
        removeKeyListener();
        m.redraw();
    },

    /**
     * Close all dialogs.
     */
    closeAll: function () {
        while (dialogStack.length > 0) {
            Dialog.close();
        }
    },

    /**
     * Confirmation dialog returning a Promise.
     * @param {object} cfg
     * @param {string} cfg.title
     * @param {string} cfg.message
     * @param {string} cfg.confirmLabel - default "Confirm"
     * @param {string} cfg.confirmIcon
     * @param {boolean} cfg.destructive
     * @returns {Promise<boolean>}
     */
    confirm: function (cfg) {
        return new Promise(function (resolve) {
            Dialog.open({
                title: cfg.title || 'Confirm',
                size: cfg.size || 'sm',
                content: m('p', { class: 'text-gray-700 dark:text-gray-300' }, cfg.message || ''),
                closable: true,
                onClose: function () { resolve(false); },
                actions: [
                    {
                        label: 'Cancel',
                        icon: 'cancel',
                        onclick: function () {
                            Dialog.close();
                        }
                    },
                    {
                        label: cfg.confirmLabel || 'Confirm',
                        icon: cfg.confirmIcon || 'check',
                        primary: !cfg.destructive,
                        destructive: cfg.destructive || false,
                        onclick: function () {
                            // Remove onClose to prevent double-resolve
                            dialogStack[dialogStack.length - 1].onClose = null;
                            Dialog.close();
                            resolve(true);
                        }
                    }
                ]
            });
        });
    },

    /**
     * Render all stacked dialogs. Call from your app layout view.
     * @returns {Array} array of Mithril vnodes
     */
    loadDialogs: function () {
        if (dialogStack.length === 0) return '';
        return dialogStack.map(function (cfg, i) {
            return renderDialog(cfg, i);
        });
    },

    /** Current stack depth (for testing) */
    stackDepth: function () {
        return dialogStack.length;
    },

    /** Reset stack (for testing) */
    _reset: function () {
        dialogStack = [];
        previousFocus = [];
        removeKeyListener();
    }
};

export { Dialog };
export default Dialog;
