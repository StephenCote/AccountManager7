import m from 'mithril';
import { am7client } from './core/am7client.js';
import { am7model } from './core/model.js';
import { page } from './core/pageClient.js';

// Import views
import sigView from './views/sig.js';

// Layout wrapper that renders dialogs + toast on every route
function layout(content) {
    return [
        content,
        page.loadToast(),
        page.components.dialog.loadDialogs()
    ];
}

const routes = {
    "/sig": {
        view: function () {
            return layout(m(sigView));
        }
    },
    "/main": {
        view: function () {
            return layout(
                m("div", { class: "screen-center-gray" }, [
                    m("div", { class: "box-shadow-white" }, [
                        m("h3", { class: "box-title" }, "AccountManager7"),
                        m("p", { class: "mt-4 text-gray-600 dark:text-gray-400" }, "Logged in as " + (page.user ? page.user.name : "unknown")),
                        m("button", {
                            class: "page-dialog-button",
                            onclick: () => page.logout()
                        }, "Logout")
                    ])
                ])
            );
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
