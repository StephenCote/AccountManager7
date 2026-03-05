/**
 * SceneGenerator — SD scene generation config panel + generation (ESM)
 * Port of Ux7 chat.js scene generation and SD config panel.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';

// ── SD Config State (persisted to localStorage) ─────────────────────

const SD_CONFIG_KEY = "am7.sdConfig";

let sdConfig = loadConfig();
let sdModels = [];
let _visible = false;
let _generating = false;
let _sessionObjectId = null;
let _onGenerated = null;

function defaultConfig() {
    return {
        model: "",
        style: "realistic",
        steps: 30,
        cfgScale: 7.5,
        sampler: "Euler a",
        width: 512,
        height: 512,
        negativePrompt: "low quality, blurry, deformed",
        seed: -1
    };
}

function loadConfig() {
    try {
        let stored = localStorage.getItem(SD_CONFIG_KEY);
        if (stored) return Object.assign(defaultConfig(), JSON.parse(stored));
    } catch(e) {}
    return defaultConfig();
}

function saveConfig() {
    try { localStorage.setItem(SD_CONFIG_KEY, JSON.stringify(sdConfig)); } catch(e) {}
}

async function loadModels() {
    if (sdModels.length > 0) return;
    try {
        let result = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/olio/sdModels",
            withCredentials: true
        });
        sdModels = Array.isArray(result) ? result : [];
    } catch(e) {
        sdModels = [];
    }
    m.redraw();
}

// ── Generation ──────────────────────────────────────────────────────

async function doGenerate() {
    if (!_sessionObjectId || _generating) return;
    _generating = true;
    m.redraw();

    try {
        let body = {
            model: sdConfig.model,
            style: sdConfig.style,
            steps: sdConfig.steps,
            cfgScale: sdConfig.cfgScale,
            sampler: sdConfig.sampler,
            width: sdConfig.width,
            height: sdConfig.height,
            negativePrompt: sdConfig.negativePrompt,
            seed: sdConfig.seed
        };

        let result = await m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/" + encodeURIComponent(_sessionObjectId) + "/generateScene",
            withCredentials: true,
            body: body
        });

        if (_onGenerated && result) {
            _onGenerated(result);
        }
        page.toast("success", "Scene generated");
    } catch(e) {
        console.error("[SceneGenerator] generateScene failed:", e);
        page.toast("error", "Scene generation failed: " + (e.message || e));
    }

    _generating = false;
    m.redraw();
}

// ── Helpers ─────────────────────────────────────────────────────────

function configField(label, content) {
    return m("div", { class: "flex items-center justify-between gap-2 mb-2" }, [
        m("label", { class: "text-xs text-gray-500 dark:text-gray-400 shrink-0 w-24" }, label),
        m("div", { class: "flex-1" }, content)
    ]);
}

function inputClass() {
    return "w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-xs";
}

// ── Public API ──────────────────────────────────────────────────────

const SceneGenerator = {
    show: function(sessionObjectId, onGenerated) {
        _sessionObjectId = sessionObjectId;
        _onGenerated = onGenerated || null;
        _visible = true;
        loadModels();
        m.redraw();
    },

    hide: function() {
        _visible = false;
        m.redraw();
    },

    toggle: function(sessionObjectId, onGenerated) {
        if (_visible) SceneGenerator.hide();
        else SceneGenerator.show(sessionObjectId, onGenerated);
    },

    isVisible: function() { return _visible; },

    PanelView: {
        view: function() {
            if (!_visible) return null;

            return m("div", { class: "absolute right-0 top-full mt-1 w-80 bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 rounded-lg shadow-xl z-40 p-3" }, [
                m("div", { class: "flex items-center justify-between mb-3" }, [
                    m("span", { class: "text-sm font-semibold text-gray-700 dark:text-gray-200" }, "Scene Generation"),
                    m("button", {
                        class: "text-gray-400 hover:text-gray-600",
                        onclick: function() { SceneGenerator.hide(); }
                    }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "close"))
                ]),

                // Model
                configField("Model", m("select", {
                    class: inputClass(),
                    value: sdConfig.model,
                    onchange: function(e) { sdConfig.model = e.target.value; saveConfig(); }
                }, [
                    m("option", { value: "" }, "Default"),
                    ...sdModels.map(function(mdl) {
                        let name = typeof mdl === "string" ? mdl : mdl.name || mdl.title || "";
                        return m("option", { value: name }, name);
                    })
                ])),

                // Style
                configField("Style", m("select", {
                    class: inputClass(),
                    value: sdConfig.style,
                    onchange: function(e) { sdConfig.style = e.target.value; saveConfig(); }
                }, ["realistic", "anime", "oil painting", "watercolor", "digital art", "photographic", "fantasy"].map(function(s) {
                    return m("option", { value: s }, s);
                }))),

                // Steps
                configField("Steps", m("input", {
                    type: "number", min: 1, max: 150, class: inputClass(),
                    value: sdConfig.steps,
                    onchange: function(e) { sdConfig.steps = parseInt(e.target.value) || 30; saveConfig(); }
                })),

                // CFG Scale
                configField("CFG Scale", m("input", {
                    type: "number", min: 1, max: 30, step: 0.5, class: inputClass(),
                    value: sdConfig.cfgScale,
                    onchange: function(e) { sdConfig.cfgScale = parseFloat(e.target.value) || 7.5; saveConfig(); }
                })),

                // Sampler
                configField("Sampler", m("select", {
                    class: inputClass(),
                    value: sdConfig.sampler,
                    onchange: function(e) { sdConfig.sampler = e.target.value; saveConfig(); }
                }, ["Euler a", "Euler", "DPM++ 2M Karras", "DPM++ SDE Karras", "DDIM", "LMS"].map(function(s) {
                    return m("option", { value: s }, s);
                }))),

                // Dimensions
                configField("Width", m("input", {
                    type: "number", min: 256, max: 2048, step: 64, class: inputClass(),
                    value: sdConfig.width,
                    onchange: function(e) { sdConfig.width = parseInt(e.target.value) || 512; saveConfig(); }
                })),
                configField("Height", m("input", {
                    type: "number", min: 256, max: 2048, step: 64, class: inputClass(),
                    value: sdConfig.height,
                    onchange: function(e) { sdConfig.height = parseInt(e.target.value) || 512; saveConfig(); }
                })),

                // Negative prompt
                configField("Negative", m("textarea", {
                    class: inputClass() + " resize-y min-h-[40px]",
                    value: sdConfig.negativePrompt,
                    oninput: function(e) { sdConfig.negativePrompt = e.target.value; saveConfig(); }
                })),

                // Generate button
                m("button", {
                    class: "w-full mt-2 px-3 py-2 rounded bg-blue-600 text-white text-sm hover:bg-blue-500 disabled:opacity-50 flex items-center justify-center gap-2",
                    disabled: _generating,
                    onclick: doGenerate
                }, [
                    _generating ? m("span", { class: "material-symbols-outlined animate-spin", style: "font-size:16px" }, "progress_activity") : null,
                    _generating ? "Generating..." : "Generate Scene"
                ])
            ]);
        }
    }
};

export { SceneGenerator };
export default SceneGenerator;
