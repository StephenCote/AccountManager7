import m from 'mithril';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { getMenuItems } from '../features.js';

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
                            onclick: function () {
                                if (page.navigable) page.navigable.drawer(true);
                                m.route.set("/main");
                            }
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
