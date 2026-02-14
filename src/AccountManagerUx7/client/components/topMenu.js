
(function () {
    const topMenu = {};
    let vnode;
    let app;
    let profileDimensions = "48x48";
    let dnd = page.components.dnd;
    let showFavButton = true;
    let trayExpanded = false;
    let shutOnEmpty = true;

    function button(sLabel, sIco, sCls, fHandler, sAltIco) {
        let props = {};
        if (typeof fHandler == "function") {
            props = { onclick: fHandler };
        }
        else if (typeof fHandler == "object") {
            props = fHandler;
        }
        props.class = "menu-button" + (sCls ? " " + sCls : "");
        return m("button", props, (sIco ? m("span", { class: (sLabel ? "mr-2 " : "") + "material-symbols-outlined" + (sAltIco ? " " + sAltIco : "") }, sIco) : ""), (sLabel || ""));
    }
    // Phase 13e item 20: Use page.userProfilePath (resolved from identity.person)
    // with fallback to v1-profile-path attribute (OI-78)
    function profileContextButton() {
        let xProf = page.userProfilePath || am7client.getAttributeValue(page.user, "v1-profile-path", 0);
        let img;
        if (xProf) img = m("img", { class: "h-8 w-8 rounded-full", src: g_application_path + "/thumbnail/" + xProf + "/" + profileDimensions });
        else img = m("span", { class: "material-symbols-outlined" }, "account_circle");
        return m("button", { id: "profileContextMenuButton", class: "context-menu-button px-2 py-1 space-x-4" }, [img]);

    }

    let activeShuffle;
    let favGroup;
    function doPickShuffle(object, filter) {

        let bBlend = object[am7model.jsonModelKey].match(/^(auth\.group|auth\.role|data\.tag|olio\.event)$/gi);
        if (!filter || (filter && bBlend) || !activeShuffle) {
            activeShuffle = object;
        }
    }

    async function loadFavGroup() {
        if (favGroup) return favGroup;
        let grp = await page.favorites();
        favGroup = grp;
        if (!activeShuffle) {
            activeShuffle = grp;
            dnd.workingSet.push(grp);
        }
        //m.redraw();
    }
    function shuffleTray() {

        let buttons = dnd.workingSet.map((o) => {
            if (activeShuffle && activeShuffle.objectId == o.objectId) {
                let modType = am7model.getModel(o[am7model.jsonModelKey]);
                let icon = "device_unknown";
                if (modType.icon) {
                    icon = modType.icon;
                }
                return button(o.name, icon, "truncate");
            }
            return "";
        });
        let workingBtns = [];
        let bid = "shuffleButton";
        let mid = bid + "-menu";
        let blendable = false;
        let blendy = false;
        let blendCls = "";
        dnd.workingSet.forEach((o) => {
            if ((activeShuffle && activeShuffle.objectId == o.objectId) || o[am7model.jsonModelKey].match(/^(auth\.group|auth\.role|data\.tag|olio\.event)$/gi)) {
                blendable = true;
            }
            else {
                blendy = true;
            }
            let modType = am7model.getModel(o[am7model.jsonModelKey]);
            let icon = "device_unknown";
            if (modType.icon) {
                icon = modType.icon;
            }
            workingBtns.push(page.navigable.contextMenuButton(mid, o.name, icon, function () { doPickShuffle(o); }));
        });
        if (blendable && blendy) {
            blendCls = "material-symbols-outlined-orange active-orange";
            if (dnd.workingSet.length > 2) blendCls += " filled";
        }

        let favButton = "";
        let blendButton = "";
        let backButton = "";
        let expanded = (trayExpanded || dnd.workingSet.length > 0);
        if (expanded) {
            if (showFavButton) {
                favButton = button(0, "favorite", "", async function () {
                    await loadFavGroup();
                    if (dnd.workingSet.filter(f => f.objectId == favGroup.objectId).length == 0) {
                        dnd.workingSet.push(favGroup);
                    }
                    activeShuffle = favGroup;
                    m.redraw();
                });
            }
            backButton = button(0, "backspace", "", function () {
                dnd.workingSet.length = 0;
                activeShuffle = undefined;
                if (shutOnEmpty) expanded = false;
            });
        }

        blendButton = button(0, "blender", "", async function () {
            if (dnd.workingSet.length == 0) {
                trayExpanded = !trayExpanded;
            }
            else {
                await dnd.doBlend((activeShuffle ? activeShuffle.objectId : undefined));
                m.redraw();
            }
        }, blendCls);


        buttons.push(favButton, backButton, blendButton);

        let menuCls = "menu";
        let trayCls = "place-content-end menu-buttons ml-4";
        if (expanded) {
            trayCls += " w-96 bg-orange-100 border rounded-lg border-orange-600";
        }
        else {
            menuCls = "hidden";
        }

        let tray = m("div[draggable]", {
            class: trayCls,
            ondragover: function (e) {
                e.preventDefault();
            },
            ondrop: function (e) {
                //activeShuffle = dnd.dragTarget;
                doPickShuffle(dnd.dragTarget, true);
                dnd.doDrop(e, "set");
            }
        }, [
            buttons,
            button(0, "menu", menuCls, { id: bid }),
            m("div", { class: "context-menu-container" }, [
                m("div", { class: "transition transition-0 context-menu-96", id: mid }, [
                    workingBtns
                ])
            ])
        ]);
        page.navigable.pendContextMenu("#" + mid, "#" + bid, null, "context-menu-96");
        return tray;
    }
   

    topMenu.component = {
        menuButton: button,
        activeShuffle: function(o){
            activeShuffle = o;
        },
        oninit: function (x) {
            page.navigable.setupPendingContextMenus();
        },
        oncreate: function (x) {
            page.navigable.addContextMenu("#profileContextMenu", "#profileContextMenuButton");
        },
        onupdate: function () {
            page.navigable.setupPendingContextMenus();
        },
        onremove: function (x) {
            if (app) app.destroy();
        },

        view: function (x) {
            let lastNot;
            let not = page.context().notifications;
            let notiActive = (not && not.length && not[not.length - 1].newMessageCount > 0);
            let mr = m.route.get();
            vnode = m("div", {
                class: "menu-bar"
            }, [
                m("div", { class: "menu-bar-flex" }, [
                    m("div", { class: "flex-center" }, [
                        m("div", { class: "flex-shrink-0" }, [
                            m("img", { class: "h-8", src: "/media/logo_128_alt.png" })
                        ]),
                        m("div", { class: "menu-buttons-spaced" }, [
                            button(null, "dashboard", (mr.match(/^\/main/gi) ? "active" : ""), page.home),
                            button(0, "explore", (mr.match(/^\/nav/gi) ? "active" : ""), page.nav),
                            button(0, "chat", (mr.match(/^\/chat/gi) ? "active" : ""), page.chat),
                            button(0, "notifications", (notiActive ? "active-red" : (mr.match(/^\/list\/message/gi) ? "active" : "")), page.messages)
                        ])
                    ]),
                    (x.attrs.customTray ? x.attrs.customTray() : ""),
                    shuffleTray(),
                    m("div", { class: "flex-center2" }, [
                        (page.testMode || !page.productionMode) ? button(0, "science", (mr.match(/^\/test/gi) ? "active" : ""), function(){ m.route.set("/test"); }) : null,
                        m(page.components.games.component),
                        m(page.components.emoji.component),
                        (page.components.moodRing.enabled() ? m(page.components.moodRing.component) : ""),
                        m("div", { class: "context-menu-container" }, [
                            profileContextButton(),
                            m("div", { id: "profileContextMenu", class: "transition transition-0 context-menu-48" }, [
                                page.navigable.contextMenuButton(".context-menu-48", "Your Profile", "account_circle", page.profile),
                                page.navigable.contextMenuButton(".context-menu-48", "Your Settings", "settings"),
                                page.navigable.contextMenuButton(".context-menu-48", "Sign Out", "lock", page.logout)
                            ])
                        ])
                    ])
                ])
            ]);
            return vnode;
        }
    };


    page.components.topMenu = topMenu.component;
}());
