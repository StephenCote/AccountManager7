import m from 'mithril';
import { am7client } from './core/am7client.js';
import { am7model } from './core/model.js';
import { page } from './core/pageClient.js';
import { initFeatures, loadFeatureRoutes } from './features.js';

// Import views
import sigView from './views/sig.js';
import { panel } from './components/panel.js';
import { navigation, navigable } from './components/navigation.js';
import { newListControl } from './views/list.js';
import { newObjectPage } from './views/object.js';
import { newNavigatorControl } from './views/navigator.js';
import { decorator } from './components/decorator.js';
import { newPaginationControl } from './components/pagination.js';
import { initTheme } from './components/topMenu.js';
import { startPolling, stopPolling } from './components/notifications.js';

// Wire components onto page for cross-module access
page.components.panel = panel;
page.components.navigation = navigation;
page.components.decorator = decorator;
page.navigable = navigable();
page.navigable.init();
page.pagination = newPaginationControl;

// Initialize theme from localStorage
initTheme();

// Create shared list and object page instances
let listControl = newListControl();
let objectPageControl = newObjectPage();
let navigatorControl = newNavigatorControl();

// Layout wrapper that renders dialogs + toast on every route
function layout(content) {
    return [
        content,
        page.loadToast(),
        page.components.dialog.loadDialogs()
    ];
}

// Shared page layout: nav + scrollable content area
function pageLayout(innerContent, navAttrs) {
    return m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
        m(navigation, navAttrs || {}),
        m("div", { class: "flex-1 overflow-auto flex bg-white dark:bg-gray-900" }, [
            innerContent
        ])
    ]);
}

const routes = {
    "/sig": {
        view: function () {
            return layout(m(sigView));
        }
    },
    "/main": {
        view: function () {
            if (!page.authenticated()) return layout(m("div", "Not authenticated"));
            return layout(pageLayout(m(panel), { hideBreadcrumb: true }));
        }
    },
    "/list/:type": {
        oninit: function (v) { listControl.view.oninit(v); },
        oncreate: function (v) { listControl.view.oncreate(v); },
        onupdate: function (v) { listControl.view.onupdate(v); },
        onremove: function () { listControl.view.onremove(); },
        view: function (vnode) {
            return layout(pageLayout(listControl.renderContent(vnode)));
        }
    },
    "/list/:type/:objectId": {
        oninit: function (v) { listControl.view.oninit(v); },
        oncreate: function (v) { listControl.view.oncreate(v); },
        onupdate: function (v) { listControl.view.onupdate(v); },
        onremove: function () { listControl.view.onremove(); },
        view: function (vnode) {
            return layout(pageLayout(listControl.renderContent(vnode)));
        }
    },
    "/plist/:type/:objectId": {
        oninit: function (v) { v.attrs.navigateByParent = true; listControl.view.oninit(v); },
        oncreate: function (v) { listControl.view.oncreate(v); },
        onupdate: function (v) { listControl.view.onupdate(v); },
        onremove: function () { listControl.view.onremove(); },
        view: function (vnode) {
            return layout(pageLayout(listControl.renderContent(vnode)));
        }
    },
    "/view/:type/:objectId": {
        oninit: function (v) { objectPageControl.view.oninit(v); },
        oncreate: function (v) { if (objectPageControl.view.oncreate) objectPageControl.view.oncreate(v); },
        onremove: function () { objectPageControl.view.onremove(); },
        view: function (vnode) {
            try {
                return layout(pageLayout(objectPageControl.renderContent(vnode)));
            } catch (e) {
                console.error('[router] view error', e);
                return layout(pageLayout(m("div", { style: "color:red;padding:20px" }, "Error: " + e.message)));
            }
        }
    },
    "/nav": {
        oninit: function(v) { navigatorControl.view.oninit(v); },
        onremove: function() { if (navigatorControl.view.onremove) navigatorControl.view.onremove(); },
        view: function(vnode) {
            return layout(pageLayout(navigatorControl.renderContent()));
        }
    },
    "/new/:type/:objectId": {
        oninit: function (v) { v.attrs.new = true; objectPageControl.view.oninit(v); },
        oncreate: function (v) { if (objectPageControl.view.oncreate) objectPageControl.view.oncreate(v); },
        onremove: function () { objectPageControl.view.onremove(); },
        view: function (vnode) {
            try {
                return layout(pageLayout(objectPageControl.renderContent(vnode)));
            } catch (e) {
                console.error('[router] new error', e);
                return layout(pageLayout(m("div", { style: "color:red;padding:20px" }, "Error: " + e.message)));
            }
        }
    }
};

function init() {
    window.addEventListener("beforeunload", takeDown);
    refreshApplication();
}

function takeDown() {
    if (page.wss) {
        page.wss.close();
    }
}

async function refreshApplication() {
    let usr = await am7client.principal();
    let rt = window.location.hash.slice(window.location.hash.indexOf("/"));
    page.application = undefined;
    page.user = usr;

    // Initialize feature profile from URL param, Vite define, or default
    let profile = new URLSearchParams(window.location.search).get('features')
        || (typeof __FEATURE_PROFILE__ !== 'undefined' ? __FEATURE_PROFILE__ : null)
        || 'full';
    initFeatures(profile);

    if (usr == null) {
        page.wss.close();
        stopPolling();
        rt = "/sig";
        m.route.set(rt);
        m.route(document.body, rt, routes);
    } else {
        am7client.currentOrganization = usr.organizationPath;
        let app = await am7client.application();
        page.application = app;
        if (app && app != null) {
            if (am7model.updateListModel) {
                for (let v in app) {
                    if (app[v] instanceof Array) {
                        am7model.updateListModel(app[v], am7model.getModelField(app.schema, v));
                    }
                }
            }
            setContextRoles(app);
            if (!rt.length || rt == "/sig") rt = "/main";
            page.wss.connect();
            startPolling();
        } else {
            rt = "/sig";
        }

        // Load lazy feature routes and merge with core routes
        let featureRoutes = await loadFeatureRoutes();
        let allRoutes = Object.assign({}, routes, featureRoutes);

        m.route.set(rt);
        m.route(document.body, rt, allRoutes);
    }

    page.router = {
        refresh: refreshApplication,
        routes,
        route: function (path) {
            m.route.set(path);
        }
    };
}

function hasRole(roles, role) {
    return (roles.filter(r => r.name.toLowerCase() === role.toLowerCase()).length > 0);
}

function setContextRoles(app) {
    let ctxRoles = page.context().roles;
    let roles = (app && app.userRoles ? app.userRoles : []);

    ctxRoles.user = hasRole(roles, 'accountusers');
    ctxRoles.api = hasRole(roles, 'apiusers');
    ctxRoles.userReader = hasRole(roles, 'accountusersreader');
    ctxRoles.roleReader = hasRole(roles, 'rolereaders');
    ctxRoles.permissionReader = hasRole(roles, 'permissionreaders');
    ctxRoles.author = hasRole(roles, 'articleauthors');
    ctxRoles.systemAdmin = hasRole(roles, 'systemadministrators');
    ctxRoles.accountAdmin = hasRole(roles, 'accountadministrators');
    ctxRoles.dataAdmin = hasRole(roles, 'dataadministrators');
    ctxRoles.objectAdmin = hasRole(roles, 'objectadministrators');
    ctxRoles.scriptExecutor = hasRole(roles, 'scriptexecutors');
    ctxRoles.admin = (ctxRoles.systemAdmin || ctxRoles.accountAdmin);
}

export { init, refreshApplication, layout, pageLayout };
export default { init };
