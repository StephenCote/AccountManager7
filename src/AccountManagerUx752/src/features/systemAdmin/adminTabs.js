/**
 * Common Admin panel shell — one "Admin" entry (core menu item, /admin/system) whose tab bar reaches the
 * three administrative surfaces:
 *
 *   System  /admin/system  core (always present)             ./systemAdminView.js
 *   Olio    /admin/olio    feature id `olioAdmin`            ../olioAdmin/olioAdminView.js
 *   Status  /admin/status  feature id `serviceStatus`        ../serviceStatus/serviceStatusView.js
 *
 * Each tab keeps its own route, so deep links (#!/admin/olio, #!/admin/status) and the per-feature route
 * ownership in features.js (routePrefixes, disabledFeatureForPath) are unchanged. A tab appears only when
 * its feature is enabled for the organization — the Olio and Status routes are not even registered
 * otherwise, so a tab for them would strand the user. The tab bar is cosmetic, like every client admin
 * gate here: each view still renders its own "administrator role required" notice, and the server's
 * @RolesAllowed admin is the real boundary.
 *
 * Tab bar markup follows features/pictureBookEdit.js sceneTabBar (role=tablist / role=tab, aria-selected).
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { isEnabled } from '../../features.js';

export const ADMIN_TABS = [
    { id: 'system', label: 'System', icon: 'admin_panel_settings', route: '/admin/system', feature: null },
    { id: 'olio', label: 'Olio', icon: 'travel_explore', route: '/admin/olio', feature: 'olioAdmin' },
    { id: 'status', label: 'Service Status', icon: 'dns', route: '/admin/status', feature: 'serviceStatus' }
];

/** The tabs visible for the current enabled feature set (pure; exported for tests). */
export function visibleAdminTabs(enabled) {
    let on = (typeof enabled === 'function') ? enabled : isEnabled;
    return ADMIN_TABS.filter(t => !t.feature || on(t.feature));
}

function isAdmin() {
    let ctx = page.context && page.context();
    return !!(ctx && ctx.roles && ctx.roles.admin);
}

export function adminTabBar(activeId) {
    let tabs = visibleAdminTabs();
    if (tabs.length < 2 || !isAdmin()) return null;
    return m('div', { class: 'max-w-6xl mx-auto px-6 pt-4' },
        m('div', { class: 'flex border-b border-gray-200 dark:border-gray-700', role: 'tablist', 'data-testid': 'admin-tabs' },
            tabs.map(function (t) {
                let on = t.id === activeId;
                return m('button', {
                    key: t.id, role: 'tab', 'aria-selected': on ? 'true' : 'false', 'data-testid': 'admin-tab-' + t.id,
                    class: 'flex items-center gap-1 px-3 py-1.5 text-sm ' + (on
                        ? 'border-b-2 border-purple-500 text-purple-700 dark:text-purple-300 font-medium'
                        : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'),
                    onclick: function () { if (!on) m.route.set(t.route); }
                }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, t.icon), t.label]);
            })));
}

/** Wrap a tab's view body in the shared tab bar. */
export function adminShell(activeId, body) {
    return m('div', { 'data-testid': 'admin-shell', 'data-admin-tab': activeId }, [adminTabBar(activeId), body]);
}

/** Route component for one admin tab: layout(pageLayout(adminShell(...))). */
export function adminRoute(activeId, viewObj, layout, pageLayout) {
    return {
        oninit: function () { if (viewObj.oninit) viewObj.oninit(); },
        view: function () { return layout(pageLayout(adminShell(activeId, viewObj.view()))); }
    };
}
