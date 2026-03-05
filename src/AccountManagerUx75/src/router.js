import m from 'mithril';
import { am7client } from './core/am7client.js';
import { am7model } from './core/model.js';
import { page } from './core/pageClient.js';

// Import views
import sigView from './views/sig.js';
import { panel } from './components/panel.js';
import { navigation, navigable } from './components/navigation.js';
import { newListControl } from './views/list.js';
import { newObjectPage } from './views/object.js';
import { decorator } from './components/decorator.js';
import { newPaginationControl } from './components/pagination.js';
import { initTheme } from './components/topMenu.js';

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
        m("div", { style: "flex:1;overflow:auto;display:flex;background:#fff" }, [
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

function refreshApplication() {
    am7client.principal().then((usr) => {
        let rt = window.location.hash.slice(window.location.hash.indexOf("/"));
        page.application = undefined;
        page.user = usr;

        if (usr == null) {
            page.wss.close();
            rt = "/sig";
            m.route.set(rt);
            m.route(document.body, rt, routes);
        } else {
            am7client.currentOrganization = usr.organizationPath;
            am7client.application().then((app) => {
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
                } else {
                    rt = "/sig";
                }
                m.route.set(rt);
                m.route(document.body, rt, routes);
            });
        }
    });

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

export { init, refreshApplication };
export default { init };
