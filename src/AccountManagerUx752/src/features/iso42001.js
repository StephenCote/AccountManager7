/**
 * ISO 42001 Compliance feature — Dashboard for AI compliance evaluation
 * Displays policy violation history and compliance check configuration.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { layout, pageLayout } from '../router.js';
import { LLMConnector } from '../chat/LLMConnector.js';

// ── State ───────────────────────────────────────────────────────────

let violations = [];
let filterText = "";
let libraryStatus = null;

// ── Policy event listener ───────────────────────────────────────────

let listenerRegistered = false;

function registerPolicyListener() {
    if (listenerRegistered) return;
    listenerRegistered = true;
    LLMConnector.onPolicyEvent(function(evt) {
        let entry = {
            timestamp: new Date().toISOString(),
            type: "policy_violation",
            details: ""
        };
        if (evt && evt.data) {
            try {
                let parsed = typeof evt.data === "string" ? JSON.parse(evt.data) : evt.data;
                entry.details = parsed.details || parsed.message || JSON.stringify(parsed);
                entry.severity = parsed.severity || "warn";
            } catch(e) {
                entry.details = String(evt.data);
            }
        }
        violations.unshift(entry);
        if (violations.length > 200) violations.length = 200;
        m.redraw();
    });
}

async function loadLibraryStatus() {
    try {
        libraryStatus = await LLMConnector.checkLibrary();
    } catch(e) {
        libraryStatus = null;
    }
    m.redraw();
}

// ── View ────────────────────────────────────────────────────────────

function statusBadge(label, ok) {
    return m("div", { class: "flex items-center gap-2 px-3 py-2 rounded bg-gray-50 dark:bg-gray-800" }, [
        m("span", { class: "material-symbols-outlined " + (ok ? "text-green-500" : "text-gray-400"), style: "font-size:18px" }, ok ? "check_circle" : "cancel"),
        m("span", { class: "text-sm text-gray-700 dark:text-gray-300" }, label)
    ]);
}

function renderViolations() {
    let filtered = violations;
    if (filterText) {
        let lower = filterText.toLowerCase();
        filtered = violations.filter(v => (v.details || "").toLowerCase().indexOf(lower) !== -1);
    }

    if (filtered.length === 0) {
        return m("div", { class: "text-center py-8 text-gray-400" }, "No policy violations recorded in this session.");
    }

    return m("div", { class: "flex flex-col gap-1" },
        filtered.map((v, i) => m("div", {
            key: i,
            class: "flex items-start gap-2 px-3 py-2 rounded bg-gray-50 dark:bg-gray-800 text-sm"
        }, [
            m("span", { class: "material-symbols-outlined shrink-0 " + (v.severity === "error" ? "text-red-500" : "text-yellow-500"), style: "font-size:16px" },
                v.severity === "error" ? "error" : "warning"),
            m("div", { class: "flex-1 min-w-0" }, [
                m("div", { class: "text-xs text-gray-400" }, v.timestamp),
                m("div", { class: "text-gray-700 dark:text-gray-300" }, v.details)
            ])
        ]))
    );
}

const complianceView = {
    oninit: function() {
        registerPolicyListener();
        loadLibraryStatus();
    },
    view: function() {
        let ls = libraryStatus || {};

        return m("div", { class: "max-w-4xl mx-auto p-6 space-y-6" }, [
            m("h1", { class: "text-2xl font-bold text-gray-800 dark:text-white" }, "ISO 42001 Compliance Dashboard"),
            m("p", { class: "text-sm text-gray-500 dark:text-gray-400" }, "AI system compliance evaluation, bias detection, and policy violation monitoring."),

            // Library status section
            m("div", { class: "space-y-2" }, [
                m("h2", { class: "text-lg font-semibold text-gray-700 dark:text-gray-200" }, "System Status"),
                m("div", { class: "grid grid-cols-2 md:grid-cols-4 gap-2" }, [
                    statusBadge("Chat Library", ls.initialized),
                    statusBadge("Prompt Library", ls.promptInitialized),
                    statusBadge("Template Library", ls.promptTemplateInitialized),
                    statusBadge("Policy Library", ls.policyInitialized)
                ])
            ]),

            // Overcorrection policy
            m("div", { class: "p-4 rounded-lg bg-blue-50 dark:bg-blue-900/30 border border-blue-200 dark:border-blue-800" }, [
                m("h3", { class: "text-sm font-semibold text-blue-800 dark:text-blue-200 mb-2" }, "Training Bias Overcorrection Active"),
                m("p", { class: "text-xs text-blue-700 dark:text-blue-300" },
                    "All LLM call paths include the 10-area overcorrection directive per CLAUDE.md policy. " +
                    "The swap test is applied to all character generation, narration, and compliance evaluation.")
            ]),

            // Violations section
            m("div", { class: "space-y-2" }, [
                m("div", { class: "flex items-center justify-between" }, [
                    m("h2", { class: "text-lg font-semibold text-gray-700 dark:text-gray-200" }, "Policy Violations (" + violations.length + ")"),
                    m("input", {
                        type: "text",
                        class: "px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm w-48",
                        placeholder: "Filter violations...",
                        value: filterText,
                        oninput: e => { filterText = e.target.value; }
                    })
                ]),
                m("div", { class: "max-h-96 overflow-y-auto" }, renderViolations())
            ])
        ]);
    }
};

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/compliance": {
        oninit: function() { complianceView.oninit(); },
        view: function() {
            return layout(pageLayout(complianceView.view()));
        }
    }
};
