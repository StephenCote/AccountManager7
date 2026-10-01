/**
 * App Panel (#!/app) — per-user tools gathered from the flyout gutter and the chat toolbar:
 * Explorer, feature tools whose menu item is tagged `section: 'app'` (Passkeys, Access Requests),
 * the Breadcrumb Bar toggle and the LLM Debug monitor. Same visual model as the admin Feature
 * Configuration panel (features/featureConfig.js) but a core route open to every user.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { features, isEnabled, isMenuItemVisible } from '../features.js';
import { LLMDebugPanel } from '../chat/LLMDebugPanel.js';

const CORE_TOOLS = [
    { icon: 'folder_open', label: 'Explorer', route: '/explorer', description: 'Browse your groups and objects as a folder tree.' }
];

const CARD = "border rounded-lg p-4 border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800";
const CARD_ACTIVE = "border rounded-lg p-4 border-blue-500 bg-blue-50/50 dark:bg-blue-900/20";

function sectionHeading(text) {
    return m("h3", { class: "field-label mb-2 mt-6" }, text);
}

/** Core tools plus every enabled feature's `section: 'app'` menu items the current user may see. */
function tools() {
    let ctx = { roles: page.context().roles, devMode: page.devMode };
    let list = CORE_TOOLS.slice();
    Object.keys(features).forEach(function (id) {
        if (!isEnabled(id)) return;
        let f = features[id];
        f.menuItems.forEach(function (mi) {
            if (mi.section !== 'app' || !isMenuItemVisible(mi, ctx)) return;
            list.push({ icon: mi.icon, label: mi.label, route: mi.route, description: f.description });
        });
    });
    return list;
}

function toolCard(t) {
    return m("button", {
        key: t.route,
        class: CARD + " text-left flex items-start gap-3 hover:border-blue-500 hover:bg-blue-50/50 dark:hover:bg-blue-900/20",
        "data-tool": t.route,
        onclick: function () { m.route.set(t.route); }
    }, [
        m("span", { class: "material-symbols-outlined text-2xl text-gray-600 dark:text-gray-300" }, t.icon),
        m("div", [
            m("div", { class: "font-medium text-gray-900 dark:text-white" }, t.label),
            t.description ? m("p", { class: "mt-1 text-sm text-gray-500 dark:text-gray-400" }, t.description) : null
        ])
    ]);
}

function breadcrumbCard() {
    let bc = page.components.breadCrumb;
    let on = !!(bc && bc.isVisible());
    return m("div", { class: on ? CARD_ACTIVE : CARD }, [
        m("div", { class: "flex items-center gap-3" }, [
            m("button", {
                class: "toggle-field mt-0" + (on ? " active" : ""),
                title: on ? "Hide the breadcrumb bar" : "Show the breadcrumb bar",
                "data-toggle": "breadcrumb",
                onclick: function () { if (bc) bc.toggleBreadcrumb(); }
            }, m("span", { class: "toggle-knob" })),
            m("span", { class: "material-symbols-outlined text-gray-600 dark:text-gray-300" }, "footprint"),
            m("span", { class: "font-medium text-gray-900 dark:text-white" }, "Breadcrumb Bar")
        ]),
        m("p", { class: "mt-1 ml-14 text-sm text-gray-500 dark:text-gray-400" }, "Show the folder path above list and object views.")
    ]);
}

function llmDebugCard() {
    return m("div", { class: CARD }, [
        m("div", { class: "flex items-center gap-3 mb-2" }, [
            m("span", { class: "material-symbols-outlined text-gray-600 dark:text-gray-300" }, "bug_report"),
            m("span", { class: "font-medium text-gray-900 dark:text-white" }, "LLM Debug")
        ]),
        m("p", { class: "mb-2 text-sm text-gray-500 dark:text-gray-400" }, "Live LLM requests, active calls and summarizations for this server. Refreshes every 2 seconds while this page is open."),
        m(LLMDebugPanel.InlineView)
    ]);
}

const appPanelView = {
    view: function () {
        return m("div", { class: "p-4 max-w-4xl w-full" }, [
            m("div", { class: "mb-4" }, [
                m("h2", { class: "text-xl font-semibold" }, [
                    m("span", { class: "material-symbols-outlined text-xl align-middle mr-2" }, "apps"),
                    "App Panel"
                ]),
                m("p", { class: "field-label mt-1" }, "Tools, display settings and diagnostics for your account.")
            ]),
            sectionHeading("Tools"),
            m("div", { class: "grid gap-3 sm:grid-cols-2" }, tools().map(toolCard)),
            sectionHeading("Display"),
            breadcrumbCard(),
            sectionHeading("Diagnostics"),
            llmDebugCard()
        ]);
    }
};

export { appPanelView };
export default appPanelView;
