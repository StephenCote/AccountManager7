(function () {
    const panel = {};
    let space;
    let vnode;
    let entityName = "sig";
    let app;
    let model;

    function modelPanel() {
        let panels = [];
        am7model.categories.forEach((cat) => {
            let k = cat.name;
            panels.push(
                m("div", { class: "panel-card" },
                    m("p", { "class": "card-title" }, [
                        (cat.icon ? m("span", { class: "material-symbols-outlined material-icons-cm mr-2" }, cat.icon) : ""),
                        (cat.label || k)
                    ]),
                    m("div", { "class": "card-contents" }, modelPanelContents(k))
                )
            );
        });

        return panels;

    }

    async function clickPanelItem(item, type) {
        let path = am7view.pathForType(type);
        if (item.prototype) {
            type = item.type;
            path = page.user.homeDirectory.path + "/" + item.group;
        }
        let base = item;
        let baseType = type;
        if (item.limit && item.limit.length) {
            baseType = item.limit[0];
            base = am7model.getModel(baseType);
        }
        let listPathBase = "/list/" + baseType;
        if (am7model.isGroup(base) || base == 'auth.group') {
            /// find the requested path, and make it if it doesn't exist
            ///
            am7client.make("auth.group", "data", path, function (v) {
                if (v != null) {
                    m.route.set(listPathBase + "/" + v.objectId);
                }
                else console.error("Error: Unable to load path " + path);
            });
        }
        else if (am7model.inherits(base, "common.parent") && type.match(/^(auth\.role|auth\.permission)$/gi)) {
            am7client.user(type, "user", function (v) {
                uwm.getDefaultParentForType(type, v).then((parObj) => {
                    if (parObj != null) {
                        m.route.set(listPathBase + "/" + parObj.objectId);
                    }
                    else console.error("Handle parent: " + listPathBase);
                });
            });
        }
        else if (type.match(/^message$/)) {
            let dir = await page.makePath("auth.group", "data", "~/.messages");
            if (dir != null) {
                m.route.set(listPathBase + "/" + dir.objectId);
            }
            else {
                console.error("Failed to find .messages");
            }
        }
        else {
            console.warn("Handle non-group/non-parent or type " + type + " / " + listPathBase, base);
            m.route.set(listPathBase);
        }
    }
    function getProtoCat(cat, name) {
        if (cat && cat.prototypes) {
            let ap = cat.prototypes.filter(c => c.name == name);
            if (ap.length) return ap[0];
        }
    }
    function modelPanelContents(key) {
        let cat = am7model.categories.find(o => o.name == key);
        if (!cat) {
            console.error("Failed to find: " + key);
            return;
        }
        let order = cat.order;
        if (!order) {
            order = [];
            am7models.models.forEach((m) => {
                if (m.categories && m.categories.includes(key)) {
                    order.push(m.name);
                }
            });
        }
        let items = [];

        order.forEach((l) => {
            let item = getProtoCat(cat, l) || am7model.getModel(l);
            if (item == null) {
                // console.error("Null item for " + l);
            }
            else {
                items.push(
                    m("div", { class: "card-item" },
                        m("button", { onclick: function () { clickPanelItem(item, l); } }, [
                            (item.icon ? m("span", { class: "material-symbols-outlined material-icons-cm mr-2" }, item.icon) : ""),
                            (item.label || l)
                        ])
                    )
                );
            }

        });
        return items;
    }
    panel.component = {
        clickPanelItem,
        oninit: function (x) {
            model = x.attrs.model;
        },
        oncreate: function (x) {

        },
        onremove: function (x) {

        },

        view: function () {
            return m("div", {
                class: "panel-container"
            },
                m("div", { class: "panel-grid" },
                    modelPanel()
                )
            );
        }
    };

    page.components.panel = panel.component;
}());
