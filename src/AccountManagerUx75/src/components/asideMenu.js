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

function favoritesSection() {
    let ctx = page.context();
    let favItems = [];
    Object.keys(ctx.favorites).forEach(function (type) {
        let items = ctx.favorites[type];
        if (items && items.length) {
            items.forEach(function (item) { favItems.push(item); });
        }
    });
    if (!favItems.length) return null;
    return [
        m("div", { class: "p-4 border-t border-gray-200 dark:border-gray-700" }, [
            m("h4", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, [
                m("span", { class: "material-symbols-outlined material-icons-cm mr-2", style: "color:#eab308" }, "star"),
                "Favorites"
            ])
        ]),
        m("ul", { class: "p-2" },
            favItems.map(function (item) {
                let type = item[am7model.jsonModelKey] || '';
                let mod = type ? am7model.getModel(type) : null;
                let icon = (mod && mod.icon) ? mod.icon : 'description';
                return m("li", { class: "py-1" },
                    m("button", {
                        class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                        onclick: function () {
                            if (page.navigable) page.navigable.drawer(true);
                            m.route.set('/view/' + type + '/' + item.objectId);
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined material-icons-cm" }, icon),
                        m("span", { class: "truncate" }, item.name || '(unnamed)')
                    ])
                );
            })
        )
    ];
}

const asideMenu = {
    view: function () {
        let cats = am7model.categories || [];
        let asideItems = getMenuItems('aside').filter(function (mi) {
            if (mi.adminOnly && (!page.context().roles || !page.context().roles.admin)) return false;
            if (mi.devOnly && !page.devMode) return false;
            return true;
        });
        return m("aside", { class: "transition transition-0", style: "display:flex;flex-direction:column;max-height:100vh" }, [
            m("div", { class: "p-4 border-b border-gray-200 dark:border-gray-700 flex-shrink-0" }, [
                m("h4", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, "Categories")
            ]),
            m("div", { style: "flex:1;overflow-y:auto;min-height:0" }, [
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
            // Explorer + Navigator quick links
            m("div", { class: "p-4 border-t border-gray-200 dark:border-gray-700" }, [
                m("h4", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, "Browse")
            ]),
            m("ul", { class: "p-2" }, [
                m("li", { class: "py-1" },
                    m("button", {
                        class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                        onclick: function () {
                            if (page.navigable) page.navigable.drawer(true);
                            m.route.set('/explorer');
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined material-icons-cm" }, "folder_open"),
                        m("span", {}, "Explorer")
                    ])
                ),
                m("li", { class: "py-1" },
                    m("button", {
                        class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                        onclick: function () {
                            if (page.navigable) page.navigable.drawer(true);
                            m.route.set('/nav');
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined material-icons-cm" }, "account_tree"),
                        m("span", {}, "Navigator")
                    ])
                )
            ]),
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
            ] : null,
            favoritesSection(),
            // System actions
            m("div", { class: "p-4 border-t border-gray-200 dark:border-gray-700" }, [
                m("h4", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, "System")
            ]),
            m("ul", { class: "p-2" }, [
                m("li", { class: "py-1" },
                    m("button", {
                        class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                        onclick: function () {
                            am7client.clearCache(0, false);
                            page.toast("success", "Cache cleared");
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined material-icons-cm" }, "cached"),
                        m("span", {}, "Clear Cache")
                    ])
                ),
                m("li", { class: "py-1" },
                    m("button", {
                        class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                        onclick: function () {
                            am7client.cleanup(function () {
                                page.toast("success", "Cleanup complete");
                            });
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined material-icons-cm" }, "cleaning_services"),
                        m("span", {}, "Cleanup")
                    ])
                )
            ])
            ]) // close scrollable div
        ]);
    }
};

export { asideMenu };
export default asideMenu;
