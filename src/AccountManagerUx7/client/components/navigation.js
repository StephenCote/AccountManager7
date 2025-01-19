(function () {
    const navigation = {};
    let space;
    let vnode;
    let entityName = "sig";
    let app;

    function navigable() {
        let nav = {
            drawerOpen: false,
            contextMenus: [],
            pendingMenus: [],
            cleanupMenus: [],
            init: function () {
                document.addEventListener("keydown", e => {
                    if (e.keyCode == 27) {
                        page.endDialog();
                        this.drawer(1);
                        this.menu(null, true);
                    }
                });

            },
            addDrawerClickEvent: function (eventPath) {
                document.querySelector(eventPath).onclick = function () {
                    nav.drawer();
                };
            },
            removeContextMenu: function (menuPath) {
                let len = this.contextMenus.length;
                this.contextMenus = this.contextMenus.filter(function (o) { return o.menuPath != menuPath; });
            },
            contextMenuButton: function (sMenuId, sLabel, sIco, fHandler) {
                let id = sMenuId;
                if (!sMenuId.match(/^\./)) id = "#" + sMenuId;
                return m("button", {
                    class: "context-menu-item", onclick:
                        function () {

                            page.navigable.menu(id);
                            if (fHandler) fHandler();
                        }
                }, [(sLabel || ""), (sIco ? m("span", { class: "material-symbols-outlined material-symbols-outlined-cm ml-2" }, sIco) : "")]);
            },
            cleanupContextMenus: function () {
                navg.cleanupMenus.forEach((m) => {
                    navg.removeContextMenu(m);
                });
                navg.cleanupMenus = [];
            },
            setupPendingContextMenus: function () {
                navg.pendingMenus.forEach((o) => {
                    navg.addContextMenu(o.menu, o.button, o.query, o.class);
                    navg.cleanupMenus.push(o.menu);
                });
                navg.pendingMenus = [];
            },
            pendContextMenu: function (menuPath, buttonPath, vQuery, altClass) {
                navg.pendingMenus.push({ menu: menuPath, button: buttonPath, class: altClass, query: vQuery });
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
                this.contextMenus.push(ctx);

                oE.onclick = function () {
                    (!vQuery ? Promise.resolve() : nav.populateContextMenu(vQuery)).then(() => {
                        nav.menu(menuPath);
                    });
                };
            },
            menu: function (path, bF, bN) {
                this.contextMenus.forEach((m) => {
                    m.menuOpen = (
                        (!path || m.menuPath === path)
                        && !bF && !m.menuOpen) || false;
                });
                if (!bN) this.flash();
            },
            drawer: function (bF) {
                this.drawerOpen = (!bF && !this.drawerOpen) || false;
                if (this.drawerOpen) this.menu(0, true);
                this.flash();
            },
            glass: function () {
                let cls = "screen-glass screen-glass-slide screen-glass-open";
                document.querySelector("div.screen-glass").className = cls;
            },
            flash: function () {
                let cls = "screen-glass screen-glass-slide screen-glass-" + (this.drawerOpen ? 'open' : 'close');
                document.querySelector("div.screen-glass").className = cls;
                document.querySelector("aside.transition").className = "transition transition-" + (this.drawerOpen ? 'full' : '0');
                this.contextMenus.forEach((m) => {
                    let cls = m.class || "context-menu-48";
                    let mnu = document.querySelector(m.menuPath);
                    if (mnu == null) {
                        console.debug("Null menu for path: " + m.menuPath);
                    }
                    else mnu.className = cls + " transition transition-" + (m.menuOpen ? 'full' : '0');
                });
            },
            populateContextMenu: function (vQuery) {

                /// There isn't an attribute to control visibility (vs. read permission), and the long-standing default has been to preface groups to be hidden with a period
                ///
                let ignoreList = /^\./;

                return (vQuery.loaded ? Promise.resolve() : new Promise((res, rej) => {
                    vQuery.loaded = true;
                    (vQuery.objectId ? Promise.resolve(vQuery.objectId) : new Promise((res2, rej2) => {
                        am7client.find(vQuery.type, vQuery.subType, vQuery.path, function (v) {
                            if (!v) {
                                vQuery.denied = true;
                                res2();
                            }
                            else res2(v.objectId);
                        })
                    }))
                        .then((gid) => {
                            vQuery.objectId = gid;
                            if (vQuery.items) res();
                            else {
                                am7client.list(vQuery.type, gid, null, 0, 0, function (r) {
                                    let oMenu = document.querySelector("#" + vQuery.menu);
                                    oMenu.innerHTML = "";
                                    vQuery.items = r;
                                    r.forEach((g) => {
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
                                            oMenu.appendChild(btn);
                                            btn.onclick = function () {
                                                nav.menu("#" + vQuery.menu);
                                                if (vQuery.handler) vQuery.handler(vQuery, g);
                                            };
                                        }
                                    });
                                    res();
                                });
                            }
                        });
                }));
            }
        };

        nav.init();

        return nav;
    }


    function asideToggle() {
        return m("div", { class: "menuToggle" }, [m("button", { class: "mr-2" }, m("span", { class: "material-symbols-outlined material-icons-48" }, "menu"))]);
    }
    function asideGlass() {
        return m("div", { class: "screen-glass screen-glass-slide screen-glass-close" }, [m("div", { class: "screen-glass-gray" })]);
    }
    let navg;

    navigation.view = {
        onupdate: function (x) {

        },
        oninit: function (x) {
            if (!page.authenticated()) return;
        },
        oncreate: function (x) {
            if (!page.authenticated()) return;
            navg.addDrawerClickEvent("div.screen-glass-slide");
            navg.addDrawerClickEvent("div.menuToggle button");
        },
        onremove: function (x) {

        },

        view: function (vnode) {
            if (!page.authenticated()) return;
            let crumb = "";
            if (!vnode.attrs.hideBreadcrumb) {
                crumb = m(page.components.breadCrumb);
            }
            return [
                m("nav", { class: "pageNav" }, [
                    asideToggle(),
                    m(page.components.topMenu, { customTray: vnode.attrs.customTray }),
                    asideGlass(),
                    , m(page.components.asideMenu)
                ]),
                crumb
            ];


        }
    };
    navg = navigable();
    page.navigable = navg;
    page.components.navigation = navigation.view;
}());
