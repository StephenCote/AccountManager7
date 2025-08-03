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
                    m("img", { src: "/media/logo_128.png", class: "object-scale-down h-24 w-24" })
                ]),
                m("div", {class: "flyout-body"}, [
                    button("Home", "home"),
                    //getCategories(),
                ]),
                m("div", { class: "flyout-gutter" }, [
                    button("Dark Mode", "dark_mode", null, undefined, async function(){ let de = document.documentElement; let cls = de.className; if(cls != "dark") de.className = "dark"; else de.className = "light";}),
                    button("Breadcrumb Bar", "footprint", null, undefined, async function(){ page.components.breadCrumb.toggleBreadcrumb();}),
                    button("Clear Cache", "cached", null, undefined, async function(){ await am7client.clearCache();}),
                    button("Cleanup", "cached", null, undefined, async function(){ await page.cleanup();})
                ])
            ]);
            return vnode;
        }
    };

    page.components.asideMenu = asideMenu.component;
}());
