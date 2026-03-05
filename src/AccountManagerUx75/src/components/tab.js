/**
 * tab.js — Minimal tab/panel layout wrapper (ESM)
 * Port of Ux7 client/components/tab.js
 */
import m from 'mithril';

function newTabComponent() {
    let activeTab = 0;

    return {
        view: function(vnode) {
            let tabs = vnode.attrs.tabs || [];
            if (!tabs.length) return m("div");

            return m("div", { class: "flex flex-col h-full" }, [
                m("div", { class: "flex border-b border-gray-200 dark:border-gray-700 shrink-0" },
                    tabs.map((tab, i) => m("button", {
                        class: "px-3 py-1.5 text-sm " + (activeTab === i
                            ? "border-b-2 border-blue-500 text-blue-600 dark:text-blue-400 font-medium"
                            : "text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"),
                        onclick: () => { activeTab = i; }
                    }, tab.label || ("Tab " + (i + 1))))
                ),
                m("div", { class: "flex-1 overflow-auto" },
                    tabs[activeTab] && tabs[activeTab].content ? tabs[activeTab].content() : null
                )
            ]);
        }
    };
}

export { newTabComponent };
export default newTabComponent;
