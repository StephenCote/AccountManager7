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
// Last successfully-built path string. While a newly-selected group's context is
// fetching ('pending'), we rebuild crumbs from this path so the bar doesn't blank
// on each hop.
let lastRenderedPath = null;
let _prevFetchOid = null;

// Compute route params needed for context fetching (shared by _fetchContext and modelBreadCrumb).
function _routeParams() {
    let type = m.route.param("type") || "data.data";
    let modType = am7model.getModel(type);
    type = (modType && modType.type) || type;
    let objType = type;
    if (m.route.get().match(/\/(list|new)\//gi)) objType = "auth.group";
    let needsGroupContext = objType === "auth.group" || (modType && (am7model.isGroup(modType) || am7model.isParent(modType)));
    return { type: type, modType: modType, objType: objType, needsGroupContext: needsGroupContext };
}

// Fetch group context for the current objectId if not already cached.
// Must be called from lifecycle hooks (oninit/onupdate), NOT from view() — calling
// m.request from inside view() makes Mithril's auto-redraw unreliable on route changes.
function _fetchContext() {
    let objectId = m.route.param("objectId");
    if (!objectId || objectId === 'null' || objectId === 'undefined') return;
    let p = _routeParams();
    if (!p.needsGroupContext) return;
    let model = page.context();
    if (model.contextObjects[objectId] !== undefined) return; // already fetched or pending
    model.contextObjects[objectId] = 'pending';
    am7client.getFull(p.objType, objectId).then(function (v) {
        if (v && (v.path || v.groupPath)) {
            model.contextObjects[objectId] = v;
            setTimeout(m.redraw, 0);
        } else if (p.objType !== p.type) {
            am7client.getFull(p.type, objectId).then(function (v2) {
                model.contextObjects[objectId] = (v2 && v2 != null) ? v2 : null;
                setTimeout(m.redraw, 0);
            }).catch(function () {
                model.contextObjects[objectId] = null;
                setTimeout(m.redraw, 0);
            });
        } else {
            model.contextObjects[objectId] = null;
            setTimeout(m.redraw, 0);
        }
    }).catch(function () {
        model.contextObjects[objectId] = null;
        setTimeout(m.redraw, 0);
    });
}

function modelBreadCrumb() {
    let sPath = page.user.homeDirectory.path;
    let model = page.context();
    let contextLoaded = false;

    let objectId = m.route.param("objectId");
    let rp = _routeParams();
    let modType = rp.modType;
    let type = rp.type;

    if (rp.needsGroupContext && objectId != null && objectId != "null" && objectId != undefined && objectId != "undefined") {
        let ctxVal = model.contextObjects[objectId];
        if (ctxVal === undefined || ctxVal === 'pending') {
            // fetch is in-flight (triggered by oninit/onupdate) — show last known path
        }
        else if (ctxVal === null) {
            contextLoaded = true;
        }
        else {
            sPath = ctxVal.path || ctxVal.groupPath;
            contextLoaded = true;
        }
    } else {
        contextLoaded = true; // no context needed
    }

    let crumbs = [];
    crumbButtons = [];
    // While context is loading for a group-type route, do NOT render home crumbs.
    // Use lastRenderedPath to rebuild fresh vnodes from the last known path.
    if (!contextLoaded) {
        sPath = lastRenderedPath || sPath;
        if (!sPath || !sPath.length) return crumbs;
        // fall through to rebuild crumbs from the last known path
    }
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
    lastRenderedPath = sPath;
    return crumbs;
}

function contextMenuItemHandler(query, object) {
    let aP = query.items.filter(function (i) {
        if (object.path == i.path) return true;
        return false;
    });

    let navObj = (aP.length && aP[0]) ? aP[0] : object;
    let navId = navObj ? navObj.objectId : null;

    // Pre-populate context so the breadcrumb renders with the correct path
    // on the FIRST render after the route change — avoids a missing second render.
    if (navId && navObj && (navObj.path || navObj.groupPath)) {
        let model = page.context();
        if (model.contextObjects[navId] === undefined || model.contextObjects[navId] === null) {
            model.contextObjects[navId] = navObj;
        }
    }

    // Ux7 pattern: determine type from folder name, not route param.
    // Fall back to the PARENT segment path (e.g., "/Universes" → "olio.world") so children of
    // olio-typed segments navigate correctly even when the child name is arbitrary (e.g., "MyWorld").
    let type = am7view.typeByPath(object.name) || am7view.typeByPath(query.path) || "data.data";
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
    let rp = _routeParams();
    let crumbs = modelBreadCrumb();
    return m("nav", { class: "breadcrumb-bar", 'aria-label': "Breadcrumb" }, [
        m("div", { class: "breadcrumb-container" }, [
            m("nav", { class: "breadcrumb" }, [
                m("ol", { id: "listBreadcrumb", class: "breadcrumb-list" }, [
                    // Issue 12: clicking the type icon opens the type-picker popover (registered by
                    // list.js on page.components.toggleTypePicker when a standalone list is mounted).
                    m("li", rp.modType ? m("span", {
                        class: "material-symbols-outlined material-icons-24 cursor-pointer",
                        title: "Switch list type",
                        onclick: function (e) {
                            if (page.components.toggleTypePicker) page.components.toggleTypePicker(e);
                        }
                    }, rp.modType.icon) : null),
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
        _prevFetchOid = m.route.param("objectId");
        _fetchContext();
    },
    onupdate: function () {
        let currentOid = m.route.param("objectId");
        _fetchContext();
        cleanupContextMenus();
        setupDisplayState();
        if (currentOid !== _prevFetchOid) {
            _prevFetchOid = currentOid;
            setTimeout(m.redraw, 0);
        }
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
