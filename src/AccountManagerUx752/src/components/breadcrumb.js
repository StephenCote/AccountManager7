/**
 * breadcrumb.js — Group path breadcrumb (direct port from Ux7)
 *
 * Ux7 IIFE converted to ESM. Logic preserved as-is.
 * Registers as page.components.breadCrumb.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';

let crumbButtons;
let showBreadcrumb = true; // default on for testing — Ux7 defaulted to false

function modelBreadCrumb() {
    let sPath = page.user.homeDirectory.path;
    let model = page.context();
    let contextLoaded = false;

    let objectId = m.route.param("objectId");


    let modType;
    let type = m.route.param("type") || "data.data";
    if (type) {
        modType = am7model.getModel(type);
        type = modType.type || type;
    }

    let objType = type;
    if (m.route.get().match(/\/(list|new)\//gi)) {
        objType = "auth.group";
    }

    if (modType && am7model.isGroup(modType) && objectId != null && objectId != "null" && objectId != undefined && objectId != "undefined") {
        let ctxVal = model.contextObjects[objectId];
        if (ctxVal === undefined) {
            // Not fetched yet — start fetch
            model.contextObjects[objectId] = 'pending';
            am7client.get(objType, objectId, function (v) {
                    if (v && v != null) {
                    model.contextObjects[objectId] = v;
                    m.redraw();
                }
                else {
                    model.contextObjects[objectId] = undefined;
                    console.error("Failed to retrieve group", objType, objectId);
                }
            });
        }
        else if (ctxVal && ctxVal !== 'pending') {
            // Context loaded — use its path
            sPath = ctxVal[(objType.match(/^auth\.group/gi) ? "path" : "groupPath")];
            contextLoaded = true;
        }
    } else {
        contextLoaded = true; // no context needed
    }

    let crumbs = [];
    crumbButtons = [];
    if (!sPath || !sPath.length) {
        console.warn("Unexpected path: " + sPath);
        return crumbs;
    }
    let aSp = sPath.split("/").slice(1);
    let bBack = [];

    aSp.forEach(function (p) {
        bBack.push("/" + p);

        let bJoi = bBack.join("");
        let id = bJoi.replace(/\//g, "zZz").replace(/\s/g, "yYy");
        let bid = id + "Button";
        let handler;
        let menuCls = "context-menu-container-slim";
        let btnCls = "multi-button";
        let disabled = false;
        if ((bJoi.match(/^\/home$/gi) || bJoi.match(/^\/$/)) && !page.context().roles.admin) {
            menuCls += " context-menu-container-disabled";
            btnCls = "multi-button-disabled";
            disabled = true;
        }
        else {
            handler = function () {
                page.navigateToPath(type, modType, bJoi).then(function (navId) {
                    if (navId) {
                        page.listByType(type, navId);
                    }
                });
            };
        }

        if (!disabled) crumbButtons.push({ id: id, bid: bid, path: bJoi, handler: handler, items: [] });
        crumbs.push(m("li", "/"));
        crumbs.push(m("li", [
            m("div", { class: menuCls }, [
                m("button" + (disabled ? "[disabled = 'true']" : ""), { id: id + "Nav", class: "rounded-l " + btnCls }, p),
                (!disabled ? m("button", { id: bid, class: "rounded-r " + btnCls }, m("span", { class: "material-symbols-outlined material-icons-cm" }, "expand_more")) : ""),
                (!disabled ? m("div", { id: id, class: "transition transition-0 context-menu-48" }, [
                    page.navigable.contextMenuButton(id, "Loading", "folder_off"),
                ]) : "")
            ])
        ]));
    });
    return crumbs;
}

function contextMenuItemHandler(query, object) {
    let aP = query.items.filter(function (i) {
        if (object.path == i.path) return true;
        return false;
    });

    // Ux7 pattern: determine type from folder name, not route param
    let type = am7view.typeByPath(object.name) || "data.data";
    if (aP.length) {
        page.listByType(type, aP[0].objectId);
    } else if (object.objectId) {
        page.listByType(type, object.objectId);
    }
}

function configureContextMenus() {
    if (!showBreadcrumb) return;
    if (!crumbButtons) return;

    crumbButtons.forEach(function (v) {
        // Attach segment click handler via DOM (not Mithril onclick) to avoid render artifacts
        let navEl = document.querySelector("#" + v.id + "Nav");
        if (navEl && v.handler) {
            navEl.onclick = v.handler;
        }
        page.navigable.addContextMenu("#" + v.id, "#" + v.bid, {
            menu: v.id,
            action: "list",
            type: "auth.group",
            subType: "data",
            objectId: 0,
            path: v.path,
            handler: contextMenuItemHandler,
            icon: "folder"
        });
    });
}

function cleanupContextMenus() {
    if (crumbButtons) {
        crumbButtons.forEach(function (v) { page.navigable.removeContextMenu("#" + v.id); });
    }
}

function setupDisplayState() {
    configureContextMenus();
}

function buildBreadCrumb() {
    if (!showBreadcrumb) return "";
    let type = m.route.param("type") || "data.data";
    let modType = am7model.getModel(type);

    // Build crumbs first — this sets pathResolved
    let crumbs = modelBreadCrumb();

    // Always render — guards on click handlers prevent navigation with stale path

    return m("nav", { class: "breadcrumb-bar", 'aria-label': "Breadcrumb" }, [
        m("div", { class: "breadcrumb-container" }, [
            m("nav", { class: "breadcrumb" }, [
                m("ol", { id: "listBreadcrumb", class: "breadcrumb-list" }, [
                    m("li", m("span", { class: "material-symbols-outlined material-icons-24" }, modType.icon)),
                    crumbs
                ])
            ])
        ])
    ]);
}

const breadCrumb = {
    toggleBreadcrumb: function () {
        showBreadcrumb = !showBreadcrumb;
        m.redraw();
    },
    isVisible: function () {
        return showBreadcrumb;
    },
    oninit: function () {
    },
    onupdate: function () {
        cleanupContextMenus();
        setupDisplayState();
    },
    oncreate: function () {
        setupDisplayState();
    },
    onremove: function () {
        cleanupContextMenus();
    },
    view: function () {
        return m("div", { class: "" },
            buildBreadCrumb()
        );
    }
};

// Register on page so navigation.js and asideMenu.js can access it
page.components.breadCrumb = breadCrumb;

export { breadCrumb };
export default breadCrumb;
