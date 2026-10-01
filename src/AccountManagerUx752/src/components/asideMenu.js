import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { getMenuItems, isMenuItemVisible, visibleCategories } from '../features.js';

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
        // Same resolution as panel.clickPanelItem: an explicit category group (absolute, or relative to
        // home), otherwise the model's own default folder (~/Characters for olio.charPerson, etc.).
        let path;
        if (cat.group && cat.group.match(/^\//)) {
            path = cat.group;
        } else if (cat.group) {
            path = page.user ? page.user.homeDirectory.path + "/" + cat.group : null;
        } else {
            path = am7view.pathForType(type);
        }
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
        // Feature-tagged categories: untagged categories are core and always shown (design §4a D5).
        let cats = visibleCategories(am7model.categories);
        let menuCtx = { roles: page.context().roles, devMode: page.devMode };
        let asideItems = getMenuItems('aside').filter(function (mi) {
            return isMenuItemVisible(mi, menuCtx);
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
            // App Panel: Explorer, Passkeys, Access Requests, Breadcrumb Bar toggle and LLM Debug live there.
            m("div", { class: "p-4 border-t border-gray-200 dark:border-gray-700" }, [
                m("h4", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, "App")
            ]),
            m("ul", { class: "p-2" }, [
                m("li", { class: "py-1" },
                    m("button", {
                        class: "w-full text-left px-3 py-2 rounded hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300 flex items-center gap-2",
                        onclick: function () {
                            if (page.navigable) page.navigable.drawer(true);
                            m.route.set('/app');
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined material-icons-cm" }, "apps"),
                        m("span", {}, "App Panel")
                    ])
                ),
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
                            if (page.navigable) page.navigable.drawer(true);
                            try {
                                am7client.clearCache(0, false);
                            } catch (e) {
                                console.error("clearCache error:", e);
                            }
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
                            if (page.navigable) page.navigable.drawer(true);
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
