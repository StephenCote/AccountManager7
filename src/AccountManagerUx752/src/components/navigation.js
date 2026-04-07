import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { topMenu } from './topMenu.js';
import { asideMenu } from './asideMenu.js';
import { FullscreenManager } from './FullscreenManager.js';

let fullscreenMgr = new FullscreenManager();

/**
 * navigable — ported from Ux7 with context menu infrastructure intact.
 */
function navigable() {
    let nav = {
        drawerOpen: false,
        contextMenus: [],
        pendingMenus: [],
        cleanupMenus: [],
        fullscreenManager: fullscreenMgr,
        init: function () {
            document.addEventListener("keydown", function (e) {
                if (e.key === 'Escape') {
                    if (page.components.dialog) page.components.dialog.close();
                    nav.drawer(true);
                    nav.menu(null, true);
                }
                if (e.key === 'F11' && e.ctrlKey) {
                    e.preventDefault();
                    let target = document.querySelector('.flex-1.overflow-auto') || document.body;
                    fullscreenMgr.toggleFullscreen(target);
                }
            });
        },
        addDrawerClickEvent: function (eventPath) {
            let el = document.querySelector(eventPath);
            if (el) {
                el.onclick = function () { nav.drawer(); };
            }
        },

        // --- Context menu infrastructure (ported from Ux7) ---

        removeContextMenu: function (menuPath) {
            nav.contextMenus = nav.contextMenus.filter(function (o) { return o.menuPath !== menuPath; });
        },
        contextMenuButton: function (sMenuId, sLabel, sIco, fHandler) {
            let id = sMenuId;
            if (!sMenuId.match(/^\./)) id = "#" + sMenuId;
            return m("button", {
                class: "context-menu-item",
                onclick: function () {
                    nav.menu(id);
                    if (fHandler) fHandler();
                }
            }, [(sLabel || ""), (sIco ? m("span", { class: "material-symbols-outlined material-symbols-outlined-cm ml-2" }, sIco) : "")]);
        },
        cleanupContextMenus: function () {
            nav.cleanupMenus.forEach(function (mp) { nav.removeContextMenu(mp); });
            nav.cleanupMenus = [];
        },
        setupPendingContextMenus: function () {
            nav.pendingMenus.forEach(function (o) {
                nav.addContextMenu(o.menu, o.button, o.query, o.class);
                nav.cleanupMenus.push(o.menu);
            });
            nav.pendingMenus = [];
        },
        pendContextMenu: function (menuPath, buttonPath, vQuery, altClass) {
            nav.pendingMenus.push({ menu: menuPath, button: buttonPath, class: altClass, query: vQuery });
        },
        addContextMenu: function (menuPath, buttonPath, vQuery, sAltCls) {
            let oE = document.querySelector(buttonPath);
            if (oE == null) {
                console.warn(buttonPath + " does not exist");
                return;
            }
            let ctx = {
                menuOpen: false,
                buttonPath: buttonPath,
                menuPath: menuPath,
                class: sAltCls,
                query: vQuery
            };
            nav.contextMenus.push(ctx);
            oE.onclick = function () {
                (!vQuery ? Promise.resolve() : nav.populateContextMenu(vQuery)).then(function () {
                    nav.menu(menuPath);
                });
            };
        },
        menu: function (path, bF, bN) {
            nav.contextMenus.forEach(function (cm) {
                cm.menuOpen = ((!path || cm.menuPath === path) && !bF && !cm.menuOpen) || false;
            });
            if (!bN) nav.flash();
        },
        populateContextMenu: function (vQuery) {
            let ignoreList = /^\./;
            return (vQuery.loaded ? Promise.resolve() : new Promise(function (res) {
                vQuery.loaded = true;
                (vQuery.objectId ? Promise.resolve(vQuery.objectId) : new Promise(function (res2) {
                    am7client.find(vQuery.type, vQuery.subType, vQuery.path, function (v) {
                        if (!v) { vQuery.denied = true; res2(); }
                        else res2(v.objectId);
                    });
                }))
                .then(function (gid) {
                    vQuery.objectId = gid;
                    if (vQuery.items) { res(); return; }
                    am7client.list(vQuery.type, gid, null, 0, 0, function (r) {
                        let oMenu = document.querySelector("#" + vQuery.menu);
                        if (oMenu) oMenu.innerHTML = "";
                        vQuery.items = r;
                        if (r) {
                            r.forEach(function (g) {
                                if (!g.name.match(ignoreList)) {
                                    let btn = document.createElement("button");
                                    btn.setAttribute("class", "context-menu-item");
                                    btn.appendChild(document.createTextNode(g.name));
                                    if (vQuery.icon) {
                                        let ico = document.createElement("span");
                                        ico.setAttribute("class", "material-symbols-outlined material-icons-cm ml-2");
                                        ico.appendChild(document.createTextNode(vQuery.icon));
                                        btn.appendChild(ico);
                                    }
                                    if (oMenu) oMenu.appendChild(btn);
                                    btn.onclick = function () {
                                        nav.menu("#" + vQuery.menu);
                                        if (vQuery.handler) vQuery.handler(vQuery, g);
                                    };
                                }
                            });
                        }
                        res();
                    });
                });
            }));
        },

        // --- Drawer + flash ---

        drawer: function (forceClose) {
            nav.drawerOpen = (!forceClose && !nav.drawerOpen) || false;
            if (nav.drawerOpen) nav.menu(0, true);
            nav.flash();
        },
        flash: function () {
            let glass = document.querySelector("div.screen-glass-slide");
            if (glass) {
                glass.className = "screen-glass-slide screen-glass-" + (nav.drawerOpen ? 'open' : 'close');
            }
            let aside = document.querySelector("aside.transition");
            if (aside) {
                aside.className = "transition transition-" + (nav.drawerOpen ? 'full' : '0');
            }
            nav.contextMenus.forEach(function (cm) {
                let cls = cm.class || "context-menu-48";
                let mnu = document.querySelector(cm.menuPath);
                if (mnu == null) {
                    console.debug("Null menu for path: " + cm.menuPath);
                } else {
                    mnu.className = cls + " transition transition-" + (cm.menuOpen ? 'full' : '0');
                }
            });
        }
    };
    return nav;
}

function asideToggle() {
    return m("div", { class: "menuToggle" }, [
        m("button", { class: "mr-2", 'aria-label': "Toggle navigation menu", 'aria-expanded': "false" },
            m("span", { class: "material-symbols-outlined material-icons-48", 'aria-hidden': 'true' }, "menu")
        )
    ]);
}

function asideGlass() {
    return m("div", { class: "screen-glass-slide screen-glass-close" });
}

const navigation = {
    oninit: function () {
        if (!page.authenticated()) return;
    },
    oncreate: function () {
        if (!page.authenticated()) return;
        if (page.navigable) {
            page.navigable.addDrawerClickEvent("div.screen-glass-slide");
            page.navigable.addDrawerClickEvent("div.menuToggle button");
        }
    },
    view: function (vnode) {
        if (!page.authenticated()) return '';
        let crumb = "";
        if (!vnode.attrs.hideBreadcrumb && page.components.breadCrumb) {
            crumb = m(page.components.breadCrumb);
        }
        return [
            m("nav", { class: "pageNav", 'aria-label': "Main navigation" }, [
                asideToggle(),
                m(topMenu)
            ]),
            asideGlass(),
            m(asideMenu),
            crumb
        ];
    }
};

export { navigation, navigable };
export default navigation;
