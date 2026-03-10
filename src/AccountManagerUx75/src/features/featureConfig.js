/**
 * Feature Configuration — Admin panel for server-side feature toggles
 * Phase 14: GET/PUT /rest/config/features, dependency graph, toggle UI
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { layout, pageLayout } from '../router.js';
import { features as clientFeatures, initFeatures, getEnabledFeatures } from '../features.js';

// ── State ───────────────────────────────────────────────────────────

let availableFeatures = [];
let currentConfig = null;
let enabledSet = new Set();
let loading = true;
let saving = false;
let error = null;
let successMsg = null;

// ── API ─────────────────────────────────────────────────────────────

async function loadConfig() {
    loading = true;
    error = null;
    try {
        let [config, available] = await Promise.all([
            am7client.getFeatureConfig(),
            am7client.getAvailableFeatures()
        ]);
        availableFeatures = available || [];
        currentConfig = config || { features: Object.keys(clientFeatures), profile: 'full' };
        enabledSet = new Set(currentConfig.features || []);
    } catch (e) {
        error = 'Failed to load feature configuration';
        console.error('[featureConfig] load error', e);
    }
    loading = false;
    m.redraw();
}

async function saveConfig() {
    saving = true;
    error = null;
    successMsg = null;
    try {
        let result = await am7client.updateFeatureConfig({
            features: Array.from(enabledSet),
            profile: 'custom'
        });
        if (result && result.features) {
            currentConfig = result;
            enabledSet = new Set(result.features);
            successMsg = 'Feature configuration saved. Reload the page to apply changes.';
            // Update the client-side feature state to match
            initFeatures(result.features);
        } else {
            error = 'Failed to save — server returned an error';
        }
    } catch (e) {
        error = 'Failed to save feature configuration';
        console.error('[featureConfig] save error', e);
    }
    saving = false;
    m.redraw();
}

// ── Dependency Logic ────────────────────────────────────────────────

function getDependents(featureId) {
    let dependents = [];
    for (let f of availableFeatures) {
        if (f.deps && f.deps.includes(featureId) && enabledSet.has(f.id)) {
            dependents.push(f.id);
        }
    }
    return dependents;
}

function getDeps(featureId) {
    let f = availableFeatures.find(function (x) { return x.id === featureId; });
    return f ? (f.deps || []) : [];
}

function toggleFeature(featureId) {
    let f = availableFeatures.find(function (x) { return x.id === featureId; });
    if (!f) return;

    if (f.required) return; // Cannot toggle required features

    if (enabledSet.has(featureId)) {
        // Disabling — check for dependents
        let dependents = getDependents(featureId);
        if (dependents.length > 0) {
            error = 'Cannot disable "' + f.label + '" — these features depend on it: ' + dependents.join(', ');
            m.redraw();
            return;
        }
        enabledSet.delete(featureId);
    } else {
        // Enabling — auto-enable dependencies
        let deps = getDeps(featureId);
        for (let dep of deps) {
            enabledSet.add(dep);
        }
        enabledSet.add(featureId);
    }
    error = null;
    successMsg = null;
    m.redraw();
}

function hasUnsavedChanges() {
    if (!currentConfig || !currentConfig.features) return false;
    let saved = new Set(currentConfig.features);
    if (saved.size !== enabledSet.size) return true;
    for (let id of enabledSet) {
        if (!saved.has(id)) return true;
    }
    return false;
}

// ── View ────────────────────────────────────────────────────────────

let featureConfigView = {
    oninit: function () {
        loadConfig();
    },
    view: function () {
        if (loading) {
            return m("div", { class: "p-8 text-gray-500 dark:text-gray-400" }, "Loading feature configuration...");
        }

        return m("div", { class: "p-6 max-w-4xl mx-auto" }, [
            // Header
            m("div", { class: "flex items-center justify-between mb-6" }, [
                m("div", [
                    m("h2", { class: "text-2xl font-bold text-gray-900 dark:text-white" }, "Feature Configuration"),
                    m("p", { class: "text-sm text-gray-500 dark:text-gray-400 mt-1" }, "Enable or disable features for this organization. Changes take effect on page reload.")
                ]),
                m("div", { class: "flex gap-2 items-center" }, [
                    hasUnsavedChanges() ? m("span", { class: "text-sm text-amber-600 dark:text-amber-400 mr-2" }, "Unsaved changes") : null,
                    m("button", {
                        class: "px-4 py-2 rounded text-white font-medium " +
                            (saving ? "bg-gray-400 cursor-not-allowed" : "bg-blue-600 hover:bg-blue-700"),
                        disabled: saving || !hasUnsavedChanges(),
                        onclick: saveConfig
                    }, saving ? "Saving..." : "Save")
                ])
            ]),

            // Messages
            error ? m("div", { class: "mb-4 p-3 rounded bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-300 text-sm" }, error) : null,
            successMsg ? m("div", { class: "mb-4 p-3 rounded bg-green-50 dark:bg-green-900/30 text-green-700 dark:text-green-300 text-sm" }, successMsg) : null,

            // Current profile
            currentConfig ? m("div", { class: "mb-4 text-sm text-gray-600 dark:text-gray-400" },
                "Current profile: ",
                m("span", { class: "font-medium text-gray-800 dark:text-gray-200" }, currentConfig.profile || "custom"),
                " — ",
                m("span", {}, enabledSet.size + " of " + availableFeatures.length + " features enabled")
            ) : null,

            // Feature cards
            m("div", { class: "grid gap-3" },
                availableFeatures.map(function (f) {
                    let enabled = enabledSet.has(f.id);
                    let isRequired = f.required;
                    let deps = f.deps || [];
                    let dependents = getDependents(f.id);
                    let hasEnabledDependents = dependents.length > 0;

                    return m("div", {
                        key: f.id,
                        class: "border rounded-lg p-4 " +
                            (enabled
                                ? "border-blue-200 dark:border-blue-800 bg-blue-50/50 dark:bg-blue-900/20"
                                : "border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800")
                    }, [
                        m("div", { class: "flex items-center justify-between" }, [
                            m("div", { class: "flex items-center gap-3" }, [
                                // Toggle switch
                                m("button", {
                                    class: "relative inline-flex h-6 w-11 items-center rounded-full transition-colors " +
                                        (isRequired ? "bg-blue-400 cursor-not-allowed opacity-60" :
                                            enabled ? "bg-blue-600" : "bg-gray-300 dark:bg-gray-600"),
                                    disabled: isRequired,
                                    onclick: function () { toggleFeature(f.id); },
                                    title: isRequired ? "Required feature — cannot be disabled" :
                                        (hasEnabledDependents ? "Has active dependents: " + dependents.join(", ") : "")
                                }, [
                                    m("span", {
                                        class: "inline-block h-4 w-4 transform rounded-full bg-white transition-transform " +
                                            (enabled ? "translate-x-6" : "translate-x-1")
                                    })
                                ]),
                                m("div", [
                                    m("span", { class: "font-medium text-gray-900 dark:text-white" }, f.label),
                                    isRequired ? m("span", { class: "ml-2 text-xs px-1.5 py-0.5 rounded bg-blue-100 dark:bg-blue-800 text-blue-700 dark:text-blue-300" }, "Required") : null,
                                    m("span", { class: "ml-2 text-xs text-gray-400 dark:text-gray-500 font-mono" }, f.id)
                                ])
                            ])
                        ]),
                        m("p", { class: "mt-1 ml-14 text-sm text-gray-500 dark:text-gray-400" }, f.description),
                        // Dependencies
                        deps.length > 0 ? m("div", { class: "mt-2 ml-14 text-xs text-gray-400 dark:text-gray-500" }, [
                            m("span", {}, "Depends on: "),
                            deps.map(function (d, i) {
                                return [
                                    i > 0 ? ", " : null,
                                    m("span", {
                                        class: enabledSet.has(d) ? "text-green-600 dark:text-green-400" : "text-red-500 dark:text-red-400"
                                    }, d)
                                ];
                            })
                        ]) : null,
                        // Active dependents warning
                        hasEnabledDependents ? m("div", { class: "mt-1 ml-14 text-xs text-amber-600 dark:text-amber-400" },
                            "Active dependents: " + dependents.join(", ")
                        ) : null
                    ]);
                })
            ),

            // Quick profile buttons
            m("div", { class: "mt-6 border-t border-gray-200 dark:border-gray-700 pt-4" }, [
                m("h3", { class: "text-sm font-medium text-gray-700 dark:text-gray-300 mb-2" }, "Quick Profiles"),
                m("div", { class: "flex gap-2 flex-wrap" }, [
                    profileButton("Minimal", ["core"]),
                    profileButton("Standard", ["core", "chat"]),
                    profileButton("Enterprise", ["core", "chat", "iso42001", "schema", "webauthn", "accessRequests", "featureConfig"]),
                    profileButton("Gaming", ["core", "chat", "cardGame", "games", "biometrics"]),
                    profileButton("Full", availableFeatures.map(function (f) { return f.id; }))
                ])
            ])
        ]);
    }
};

function profileButton(label, featureList) {
    return m("button", {
        class: "px-3 py-1.5 text-sm rounded border border-gray-300 dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-700 dark:text-gray-300",
        onclick: function () {
            enabledSet = new Set(featureList);
            // Ensure core is always present
            enabledSet.add("core");
            error = null;
            successMsg = null;
            m.redraw();
        }
    }, label);
}

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/admin/features": {
        oninit: function () { featureConfigView.oninit(); },
        view: function () {
            return layout(pageLayout(featureConfigView.view()));
        }
    }
};
