/**
 * ChatSetupWizard — First-time library setup wizard (ESM port)
 * Shown when LLMConnector.ensureLibrary() returns false.
 * Collects: serviceType, serverUrl, model name.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { LLMConnector } from './LLMConnector.js';

let _visible = false;
let _step = 0;
let _saving = false;
let _error = null;
let _onComplete = null;

let _config = {
    serviceType: "ollama",
    serverUrl: "http://localhost:11434",
    model: ""
};

const _presets = {
    ollama:   { serverUrl: "http://localhost:11434",    label: "Ollama (Local)" },
    openai:   { serverUrl: "https://api.openai.com/v1", label: "OpenAI" },
    llamacpp: { serverUrl: "http://localhost:8080",     label: "llama.cpp (Local)" },
    custom:   { serverUrl: "",                          label: "Custom" }
};

const _presetKeys = ["ollama", "openai", "llamacpp", "custom"];
const _stepTitles = ["Service Type", "Server URL", "Model", "Confirm"];

function selectServiceType(type) {
    _config.serviceType = type;
    let preset = _presets[type];
    if (preset && preset.serverUrl) _config.serverUrl = preset.serverUrl;
}

async function submit() {
    if (!_config.serverUrl || !_config.model) {
        _error = "Server URL and model name are required.";
        m.redraw();
        return;
    }
    _saving = true;
    _error = null;
    m.redraw();

    try {
        let result = await LLMConnector.initLibrary(_config.serverUrl, _config.model, _config.serviceType);
        if (result && result.status === "ok") {
            LLMConnector.resetLibraryCache();
            _visible = false;
            page.toast("success", "Chat library initialized");
            if (_onComplete) _onComplete();
        } else {
            _error = "Setup failed. Please check your settings.";
        }
    } catch (e) {
        _error = (e && e.error) ? e.error : (e && e.message) ? e.message : "Setup failed";
    }
    _saving = false;
    m.redraw();
}

function renderStep0() {
    return m("div", { class: "p-4" }, [
        m("p", { class: "mb-4 text-sm text-gray-600 dark:text-gray-300" }, "Select your LLM service provider:"),
        m("div", { class: "flex flex-col gap-2" },
            _presetKeys.map(key => {
                let preset = _presets[key];
                let active = _config.serviceType === key;
                return m("button", {
                    class: "p-3 text-left rounded border " + (active
                        ? "border-blue-500 bg-blue-50 dark:bg-blue-900 text-blue-800 dark:text-white"
                        : "border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700"),
                    onclick: () => { selectServiceType(key); m.redraw(); }
                }, preset.label);
            })
        )
    ]);
}

function renderStep1() {
    return m("div", { class: "p-4" }, [
        m("p", { class: "mb-4 text-sm text-gray-600 dark:text-gray-300" }, "Enter the server URL for your LLM service:"),
        m("input", {
            type: "text",
            class: "w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white",
            value: _config.serverUrl,
            placeholder: "http://localhost:11434",
            oninput: e => { _config.serverUrl = e.target.value; }
        })
    ]);
}

function renderStep2() {
    return m("div", { class: "p-4" }, [
        m("p", { class: "mb-4 text-sm text-gray-600 dark:text-gray-300" }, "Enter the default model name:"),
        m("input", {
            type: "text",
            class: "w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white",
            value: _config.model,
            placeholder: _config.serviceType === "openai" ? "gpt-4o" : "llama3.2",
            oninput: e => { _config.model = e.target.value; }
        }),
        m("p", { class: "mt-2 text-xs text-gray-400" }, "This will be set on all default chat config templates. You can change individual configs later.")
    ]);
}

function renderStep3() {
    return m("div", { class: "p-4" }, [
        m("p", { class: "mb-4 text-sm text-gray-600 dark:text-gray-300" }, "Review your settings:"),
        m("div", { class: "flex flex-col gap-2 p-3 rounded bg-gray-50 dark:bg-gray-800" }, [
            m("div", { class: "flex justify-between" }, [
                m("span", { class: "text-gray-500" }, "Service:"),
                m("span", { class: "text-gray-800 dark:text-white" }, _presets[_config.serviceType].label)
            ]),
            m("div", { class: "flex justify-between" }, [
                m("span", { class: "text-gray-500" }, "Server URL:"),
                m("span", { class: "text-gray-800 dark:text-white" }, _config.serverUrl)
            ]),
            m("div", { class: "flex justify-between" }, [
                m("span", { class: "text-gray-500" }, "Model:"),
                m("span", { class: "text-gray-800 dark:text-white" }, _config.model)
            ])
        ]),
        m("p", { class: "mt-3 text-xs text-gray-400" }, "This will create shared chat config templates in /Library/ChatConfigs available to all users.")
    ]);
}

const _stepRenderers = [renderStep0, renderStep1, renderStep2, renderStep3];

const ChatSetupWizard = {
    show: function(onComplete) {
        _visible = true;
        _step = 0;
        _error = null;
        _saving = false;
        _onComplete = onComplete || null;
        _config.serviceType = "ollama";
        _config.serverUrl = "http://localhost:11434";
        _config.model = "";
        m.redraw();
    },

    hide: () => { _visible = false; m.redraw(); },
    isVisible: () => _visible,

    WizardView: {
        view: function() {
            if (!_visible) return null;
            let isFirst = _step === 0;
            let isLast = _step === _stepTitles.length - 1;

            return m("div", { class: "fixed inset-0 z-50 flex items-center justify-center" }, [
                m("div", {
                    class: "absolute inset-0 bg-black/50",
                    onclick: () => { if (!_saving) { _visible = false; m.redraw(); } }
                }),
                m("div", { class: "relative bg-white dark:bg-gray-900 rounded-lg shadow-xl w-full max-w-md mx-4" }, [
                    m("div", { class: "p-4 border-b border-gray-200 dark:border-gray-700 flex items-center justify-between" }, [
                        m("h3", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, "Chat Library Setup"),
                        m("span", { class: "text-xs text-gray-400" }, "Step " + (_step + 1) + " of " + _stepTitles.length)
                    ]),
                    m("h4", { class: "text-sm font-bold px-4 pt-3 text-gray-700 dark:text-gray-200" }, _stepTitles[_step]),
                    _stepRenderers[_step](),
                    _error ? m("p", { class: "px-4 text-red-500 text-sm" }, _error) : null,
                    m("div", { class: "p-4 border-t border-gray-200 dark:border-gray-700 flex justify-between" }, [
                        !isFirst ? m("button", {
                            class: "px-3 py-1.5 rounded text-sm border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-800",
                            disabled: _saving,
                            onclick: () => { _step--; _error = null; m.redraw(); }
                        }, "Back") : m("span"),
                        m("div", { class: "flex gap-2" }, [
                            m("button", {
                                class: "px-3 py-1.5 rounded text-sm border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-800",
                                disabled: _saving,
                                onclick: () => { _visible = false; m.redraw(); }
                            }, "Cancel"),
                            isLast
                                ? m("button", {
                                    class: "px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500 disabled:opacity-50",
                                    disabled: _saving,
                                    onclick: submit
                                }, _saving ? "Setting up..." : "Initialize")
                                : m("button", {
                                    class: "px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500",
                                    onclick: () => { _step++; _error = null; m.redraw(); }
                                }, "Next")
                        ])
                    ])
                ])
            ]);
        }
    }
};

export { ChatSetupWizard };
export default ChatSetupWizard;
