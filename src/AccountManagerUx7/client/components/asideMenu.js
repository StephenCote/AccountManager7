(function () {
    const asideMenu = {};
    let vnode;
    let app;

    function button(sLabel, sIco, icoId, cls, fHandler, fHold) {
        let fWrapper = function (e) {
            if (!fHold) page.navigable.drawer();
            if (fHandler) fHandler(e);
        };
        let btnCls = "flyout-button";
        if (cls) {
            btnCls += " " + cls;
        }
        return m("button", { onclick: fWrapper, class: btnCls }, (sIco ? m("span", { id: icoId, class: "mr-2 material-symbols-outlined" }, sIco) : ""), (sLabel || ""));
    }

    function clicky() {
        console.log("Clicky!");
    }
    function expandCategory(key) {
        expanded = key;
    }
    let expanded;
    function getCategories() {
        let cats = [];
        let model = am7model;
        model.categories.forEach(cat => {
            let k = cat.name;
            if (!expanded) {
                expanded = k;
            }
            cats.push(button(cat.label, cat.icon, undefined, undefined, function (e) { expandCategory(k); }, true));
            if (expanded == k) {
                let order = cat.order;
                if (!order) {
                    order = [];
                    Object.keys(model.types).forEach((l) => { if (types[l].category == key) order.push(key); });
                }
                order.forEach((l) => {
                    let item = am7model.getModel(l);
                    if (cat.prototype && cat.prototype[l]) item = cat.prototype[l];
                    if (item == null) console.error("Null item for " + l);
                    else {
                        cats.push(button(item.label || l, item.icon, undefined, "ml-4", function (e) { page.components.panel.clickPanelItem(item, l); }));
                    }

                });
            }
            /// add in sub cat buttons
        });
        return cats;
    }

    asideMenu.component = {
        oninit: function (x) {

        },
        oncreate: function (x) {
            //app = page.space(entityName, x, {});
        },
        onremove: function (x) {
            if (app) app.destroy();
        },

        view: function () {
            vnode = m("aside", {
                class: "transition transition-0"
            }, [
                m("span", { class: "flyout-banner" }, [
                    m("img", { src: "/media/5715116.png", class: "object-scale-down h-24 w-24" })
                ]),
                button("Home", "home"),
                getCategories(),
                m("div", { class: "flyout-gutter" }, [
                    //button("Community Mode", "group_off", "icoCommunityMode", undefined, page.components.breadCrumb.toggleCommunity),
                    button("Clear Cache", "cached", null, undefined, async function(){ await am7client.clearCache();}),
                    button("Settings", "settings", null, undefined, clicky),
                    button("Code", "code", null, undefined, clicky)
                ])
            ]);
            return vnode;
        }
    };

    page.components.asideMenu = asideMenu.component;
}());
