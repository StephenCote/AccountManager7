import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';

let notifications = [];
let unreadCount = 0;
let panelOpen = false;
let loading = false;
let pollInterval = null;
const POLL_MS = 60000;

function loadNotifications() {
    if (loading || !page.authenticated()) return;
    loading = true;
    let q = am7view.viewQuery(am7model.newInstance('message.spool'));
    q.field('spoolStatus', 'SPOOLED');
    q.recordCount = 20;
    am7client.search(q, function (qr) {
        loading = false;
        if (qr && qr.results) {
            notifications = qr.results;
            unreadCount = qr.count || notifications.length;
        }
        m.redraw();
    });
}

function acknowledge(item) {
    if (!item || !item.objectId) return;
    item.spoolStatus = 'ACKNOWLEGED_RECEIPT';
    am7client.patch('message.spool', item, function (v) {
        if (v) {
            notifications = notifications.filter(function (n) { return n.objectId !== item.objectId; });
            unreadCount = Math.max(0, unreadCount - 1);
        }
        m.redraw();
    });
}

function toggle() {
    panelOpen = !panelOpen;
    if (panelOpen && !notifications.length) loadNotifications();
}

function startPolling() {
    stopPolling();
    loadNotifications();
    pollInterval = setInterval(loadNotifications, POLL_MS);
}

function stopPolling() {
    if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = null;
    }
}

function badge() {
    if (!unreadCount) return null;
    return m('span', {
        class: 'notification-badge'
    }, unreadCount > 99 ? '99+' : String(unreadCount));
}

function notificationPanel() {
    if (!panelOpen) return null;
    return m('div', { class: 'notification-panel' }, [
        m('div', { class: 'flex items-center justify-between p-3 border-b border-gray-200 dark:border-gray-700' }, [
            m('span', { class: 'text-sm font-semibold text-gray-700 dark:text-gray-200' }, 'Notifications'),
            m('button', {
                class: 'text-xs text-blue-500 hover:text-blue-700',
                onclick: function () { loadNotifications(); }
            }, 'Refresh')
        ]),
        loading ? m('div', { class: 'p-4 text-center text-sm text-gray-400' }, 'Loading...') :
        !notifications.length ? m('div', { class: 'p-4 text-center text-sm text-gray-400' }, 'No notifications') :
        m('div', { class: 'notification-list' },
            notifications.map(function (item) {
                return m('div', { class: 'notification-item' }, [
                    m('div', { class: 'flex-1 min-w-0' }, [
                        m('div', { class: 'text-sm font-medium text-gray-800 dark:text-gray-200 truncate' }, item.name || 'Notification'),
                        item.description ? m('div', { class: 'text-xs text-gray-500 dark:text-gray-400 truncate' }, item.description) : null
                    ]),
                    m('button', {
                        class: 'shrink-0 ml-2 text-gray-400 hover:text-green-600',
                        onclick: function (e) { e.stopPropagation(); acknowledge(item); },
                        title: 'Dismiss',
                        'aria-label': 'Dismiss notification'
                    }, m('span', { class: 'material-symbols-outlined', 'aria-hidden': 'true', style: 'font-size:18px' }, 'check'))
                ]);
            })
        )
    ]);
}

const notificationButton = {
    view: function () {
        return m('div', { class: 'relative inline-flex' }, [
            m('button', {
                class: 'menu-button',
                onclick: toggle,
                title: 'Notifications',
                'aria-label': 'Notifications',
                'aria-expanded': panelOpen ? 'true' : 'false'
            }, [
                m('span', { class: 'material-symbols-outlined', 'aria-hidden': 'true' }, 'notifications'),
                badge()
            ]),
            notificationPanel()
        ]);
    }
};

export { notificationButton, startPolling, stopPolling, loadNotifications };
export default notificationButton;
