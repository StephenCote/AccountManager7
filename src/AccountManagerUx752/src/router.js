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
import { newExplorerControl } from './views/explorer.js';
import { am7decorator } from './components/decorator.js';
import { newPaginationControl } from './components/pagination.js';
import { initTheme } from './components/topMenu.js';
import { startPolling, stopPolling } from './components/notifications.js';
import { contextMenuComponent } from './components/contextMenu.js';
import { ObjectPicker } from './components/picker.js';

// --- Performance: guard components skip re-render when state unchanged ---

let _navRoute = null;
let _navHide = undefined;

const NavGuard = {
    onbeforeupdate(vnode) {
        let route = m.route.get();
        let hide = vnode.attrs.hideBreadcrumb;
        if (route === _navRoute && hide === _navHide) return false;
        _navRoute = route;
        _navHide = hide;
        return true;
    },
    view(vnode) {
        return m(navigation, vnode.attrs);
    }
};

const OverlayGuard = {
    view() {
        return [
            m(contextMenuComponent),
            page.loadToast(),
            page.components.dialog.loadDialogs()
        ];
    }
};

// Wire components onto page for cross-module access
page.components.panel = panel;
page.components.navigation = navigation;
page.components.decorator = am7decorator;
page.navigable = navigable();
page.navigable.init();
page.pagination = newPaginationControl;

// Initialize theme from localStorage
initTheme();

// Create shared list and object page instances
let listControl = newListControl();
let objectPageControl = newObjectPage();
page.objectPage = objectPageControl;
let navigatorControl = newNavigatorControl();
let explorerControl = newExplorerControl();

// Layout wrapper that renders overlays via guarded component
function layout(content) {
    return [
        content,
        m(OverlayGuard)
    ];
}

// Shared page layout: nav (guarded) + scrollable content area
function pageLayout(innerContent, navAttrs) {
    return m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
        m(NavGuard, navAttrs || {}),
        m("main", { class: "flex-1 overflow-auto flex bg-white dark:bg-gray-900", role: "main" }, [
            innerContent
        ]),
        m(ObjectPicker.PickerView)
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
    "/explorer": {
        oninit: function(v) { explorerControl.view.oninit(v); },
        onremove: function() { if (explorerControl.view.onremove) explorerControl.view.onremove(); },
        view: function() {
            return layout(pageLayout(explorerControl.renderContent()));
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
    // Mithril v2 listens for popstate, not hashchange. Bridge external hash
    // changes (e.g. Playwright page.goto, browser address bar edits) so the
    // router picks them up.
    window.addEventListener('hashchange', function() {
        try {
            let hash = window.location.hash;
            if (hash.startsWith('#!')) {
                let route = hash.slice(2);
                if (m.route.get() !== route) {
                    m.route.set(route);
                }
            }
        } catch(e) { /* router not yet initialised */ }
    });
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
    // Restore saved return route from 401 redirect — only when authenticated
    if (usr != null) {
        let returnRoute = sessionStorage.getItem("am7.returnRoute");
        if (returnRoute) {
            sessionStorage.removeItem("am7.returnRoute");
            rt = returnRoute;
        }
    }
    page.application = undefined;
    page.user = usr;

    // Initialize feature profile: try server config first, then URL param / build define / default
    let profile = 'full';
    if (usr != null) {
        try {
            let serverConfig = await am7client.getFeatureConfig();
            if (serverConfig && serverConfig.features && Array.isArray(serverConfig.features)) {
                profile = serverConfig.features;
            }
        } catch (e) {
            console.warn('[router] Failed to fetch server feature config, using fallback', e);
        }
    }
    if (typeof profile === 'string') {
        // Fallback to URL param or Vite define
        profile = new URLSearchParams(window.location.search).get('features')
            || (typeof __FEATURE_PROFILE__ !== 'undefined' ? __FEATURE_PROFILE__ : null)
            || 'full';
    }
    initFeatures(profile);

    if (usr == null) {
        page.wss.close();
        stopPolling();
        rt = "/sig";
        m.route(document.body, "/sig", routes);
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

        // Initialize Mithril router with all routes (core + feature).
        // Always explicitly navigate to the desired route after mounting, because
        // if the current URL hash matches a route (e.g. #!/sig from a prior unauthenticated
        // visit), Mithril will route there instead of using the default route.
        m.route(document.body, "/main", allRoutes);
        m.route.set(rt);
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
