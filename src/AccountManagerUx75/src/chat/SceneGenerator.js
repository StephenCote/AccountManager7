/**
 * SceneGenerator — SD scene generation config panel + generation (ESM)
 * Uses shared SdConfigPanel for consistent config rendering across features.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';

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
        refinerModel: "",
        style: "photograph",
        steps: 30,
        refinerSteps: 20,
        cfg: 7,
        refinerCfg: 7,
        denoisingStrength: 0.65,
        sampler: "dpmpp_2m",
        scheduler: "Karras",
        width: 1024,
        height: 768,
        hires: true,
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
        let result = await m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/" + encodeURIComponent(_sessionObjectId) + "/generateScene",
            withCredentials: true,
            body: sdConfig
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

                // Shared SD config form
                m(SdConfigPanel, { config: sdConfig, models: sdModels, onChange: saveConfig, compact: true }),

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
