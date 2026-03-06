import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { getMenuItems } from '../features.js';

function navigateToCategory(cat) {
    if (page.navigable) page.navigable.drawer(true);
    let order = cat.order;
    if (!order || !order.length) {
        order = [];
        am7model.models.forEach(function (mod) {
            if (mod.categories && mod.categories.includes(cat.name)) order.push(mod.name);
        });
    }
    if (!order.length) { m.route.set("/main"); return; }
    let type = order[0];
    let mod = am7model.getModel(type);
    if (mod && (am7model.isGroup(mod) || mod.group)) {
        let path = page.user ? page.user.homeDirectory.path + "/" + (cat.group || cat.name) : null;
        if (path) {
            am7client.make("auth.group", "data", path, function (v) {
                if (v) m.route.set("/list/" + type + "/" + v.objectId);
                else m.route.set("/main");
            });
        } else {
            m.route.set("/main");
        }
    } else {
        m.route.set("/list/" + type);
    }
}

const asideMenu = {
    view: function () {
        let cats = am7model.categories || [];
        let asideItems = getMenuItems('aside');
        return m("aside", { class: "transition transition-0" }, [
            m("div", { class: "p-4 border-b border-gray-200 dark:border-gray-700" }, [
                m("h4", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, "Categories")
            ]),
            m("ul", { class: "p-2" },
                cats.map(function (cat) {
                    return m("li", { class: "py-1" },
                        m("button", {
                            class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                            onclick: function () { navigateToCategory(cat); }
                        }, [
                            cat.icon ? m("span", { class: "material-symbols-outlined material-icons-cm" }, cat.icon) : null,
                            m("span", {}, cat.label || cat.name)
                        ])
                    );
                })
            ),
            asideItems.length ? [
                m("div", { class: "p-4 border-t border-gray-200 dark:border-gray-700" }, [
                    m("h4", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, "Features")
                ]),
                m("ul", { class: "p-2" },
                    asideItems.map(function (mi) {
                        return m("li", { class: "py-1" },
                            m("button", {
                                class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                                onclick: function () {
                                    if (page.navigable) page.navigable.drawer(true);
                                    m.route.set(mi.route);
                                }
                            }, [
                                m("span", { class: "material-symbols-outlined material-icons-cm" }, mi.icon),
                                m("span", {}, mi.label)
                            ])
                        );
                    })
                )
            ] : null
        ]);
    }
};

export { asideMenu };
export default asideMenu;
