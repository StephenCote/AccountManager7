/**
 * ChatSetupWizard — First-time setup for the shared chat config library.
 * Shown when LLMConnector.ensureLibrary() returns false.
 * Collects: serviceType, serverUrl, model name.
 * Calls POST /rest/chat/library/init to populate defaults.
 *
 * Exposes: window.ChatSetupWizard
 */
(function() {
    "use strict";

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

    let _presets = {
        ollama:   { serverUrl: "http://localhost:11434",    label: "Ollama (Local)" },
        openai:   { serverUrl: "https://api.openai.com/v1", label: "OpenAI" },
        llamacpp: { serverUrl: "http://localhost:8080",     label: "llama.cpp (Local)" },
        custom:   { serverUrl: "",                          label: "Custom" }
    };

    let _presetKeys = ["ollama", "openai", "llamacpp", "custom"];

    function selectServiceType(type) {
        _config.serviceType = type;
        let preset = _presets[type];
        if (preset && preset.serverUrl) {
            _config.serverUrl = preset.serverUrl;
        }
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
            let result = await LLMConnector.initLibrary(
                _config.serverUrl,
                _config.model,
                _config.serviceType
            );
            if (result && result.status === "ok") {
                LLMConnector.resetLibraryCache();
                _visible = false;
                page.toast("success", "Chat library initialized");
                if (_onComplete) {
                    _onComplete();
                }
            } else {
                _error = "Setup failed. Please check your settings.";
            }
        } catch (e) {
            _error = e.message || "Setup failed";
        }
        _saving = false;
        m.redraw();
    }

    function renderStep0() {
        return m("div", { class: "p-4" }, [
            m("p", { class: "mb-4 text-sm" }, "Select your LLM service provider:"),
            m("div", { class: "flex flex-col gap-2" },
                _presetKeys.map(function(key) {
                    let preset = _presets[key];
                    let active = _config.serviceType === key;
                    return m("button", {
                        class: "p-3 text-left rounded border " + (active ? "border-blue-500 bg-blue-900 text-white" : "border-gray-600 bg-gray-800 text-gray-300 hover:bg-gray-700"),
                        onclick: function() { selectServiceType(key); m.redraw(); }
                    }, preset.label);
                })
            )
        ]);
    }

    function renderStep1() {
        return m("div", { class: "p-4" }, [
            m("p", { class: "mb-4 text-sm" }, "Enter the server URL for your LLM service:"),
            m("input", {
                type: "text",
                class: "w-full p-2 rounded border border-gray-600 bg-gray-800 text-white",
                value: _config.serverUrl,
                placeholder: "http://localhost:11434",
                oninput: function(e) { _config.serverUrl = e.target.value; }
            })
        ]);
    }

    function renderStep2() {
        return m("div", { class: "p-4" }, [
            m("p", { class: "mb-4 text-sm" }, "Enter the default model name:"),
            m("input", {
                type: "text",
                class: "w-full p-2 rounded border border-gray-600 bg-gray-800 text-white",
                value: _config.model,
                placeholder: _config.serviceType === "openai" ? "gpt-4o" : "llama3.2",
                oninput: function(e) { _config.model = e.target.value; }
            }),
            m("p", { class: "mt-2 text-xs text-gray-400" }, "This will be set on all default chat config templates. You can change individual configs later.")
        ]);
    }

    function renderStep3() {
        return m("div", { class: "p-4" }, [
            m("p", { class: "mb-4 text-sm" }, "Review your settings:"),
            m("div", { class: "flex flex-col gap-2 p-3 rounded bg-gray-800" }, [
                m("div", { class: "flex justify-between" }, [
                    m("span", { class: "text-gray-400" }, "Service:"),
                    m("span", { class: "text-white" }, _presets[_config.serviceType].label)
                ]),
                m("div", { class: "flex justify-between" }, [
                    m("span", { class: "text-gray-400" }, "Server URL:"),
                    m("span", { class: "text-white" }, _config.serverUrl)
                ]),
                m("div", { class: "flex justify-between" }, [
                    m("span", { class: "text-gray-400" }, "Model:"),
                    m("span", { class: "text-white" }, _config.model)
                ])
            ]),
            m("p", { class: "mt-3 text-xs text-gray-400" }, "This will create shared chat config templates (General Chat, RPG, Coding, etc.) in /Library/ChatConfigs available to all users.")
        ]);
    }

    let _stepRenderers = [renderStep0, renderStep1, renderStep2, renderStep3];
    let _stepTitles = ["Service Type", "Server URL", "Model", "Confirm"];

    let ChatSetupWizard = {

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

        hide: function() {
            _visible = false;
            m.redraw();
        },

        isVisible: function() {
            return _visible;
        },

        view: function() {
            if (!_visible) return null;

            let isFirst = _step === 0;
            let isLast = _step === _stepTitles.length - 1;

            return [
                m("div", { class: "screen-glass screen-glass-open" }, [
                    m("div", { class: "screen-glass-gray" })
                ]),
                m("div", { class: "page-dialog-container" }, [
                    m("div", { class: "page-dialog page-dialog-75", style: "overflow-y: auto;" }, [
                        m("div", { class: "list-results-container" }, [
                            m("div", { class: "list-results", style: "overflow-y: auto;" }, [
                                m("div", { class: "result-nav-outer" }, [
                                    m("div", { class: "result-nav-inner" }, [
                                        m("nav", { class: "result-nav" }, [
                                            m("h3", { class: "box-title" }, "Chat Library Setup"),
                                            m("span", { class: "ml-4 text-xs text-gray-400" },
                                                "Step " + (_step + 1) + " of " + _stepTitles.length
                                            )
                                        ])
                                    ])
                                ]),
                                m("div", { class: "p-2" }, [
                                    m("h4", { class: "text-sm font-bold mb-2 px-4" }, _stepTitles[_step]),
                                    _stepRenderers[_step](),
                                    (_error ? m("p", { class: "px-4 text-red-400 text-sm mt-2" }, _error) : null)
                                ]),
                                m("div", { class: "result-nav-outer" }, [
                                    m("div", { class: "result-nav-inner" }, [
                                        m("nav", { class: "result-nav" }, [
                                            (!isFirst ? m("button", {
                                                class: "page-dialog-button",
                                                disabled: _saving,
                                                onclick: function() { _step--; _error = null; m.redraw(); }
                                            }, [
                                                m("span", { class: "material-symbols-outlined material-symbols-outlined-white md-18 mr-2" }, "arrow_back"),
                                                "Back"
                                            ]) : null),
                                            (isLast ?
                                                m("button", {
                                                    class: "page-dialog-button",
                                                    disabled: _saving,
                                                    onclick: submit
                                                }, [
                                                    m("span", { class: "material-symbols-outlined material-symbols-outlined-white md-18 mr-2" }, _saving ? "hourglass_empty" : "check"),
                                                    (_saving ? "Setting up..." : "Initialize")
                                                ])
                                                :
                                                m("button", {
                                                    class: "page-dialog-button",
                                                    onclick: function() { _step++; _error = null; m.redraw(); }
                                                }, [
                                                    "Next",
                                                    m("span", { class: "material-symbols-outlined material-symbols-outlined-white md-18 ml-2" }, "arrow_forward")
                                                ])
                                            ),
                                            m("button", {
                                                class: "page-dialog-button",
                                                disabled: _saving,
                                                onclick: function() { _visible = false; m.redraw(); }
                                            }, [
                                                m("span", { class: "material-symbols-outlined material-symbols-outlined-white md-18 mr-2" }, "cancel"),
                                                "Cancel"
                                            ])
                                        ])
                                    ])
                                ])
                            ])
                        ])
                    ])
                ])
            ];
        }
    };

    if (typeof module != "undefined") {
        module.ChatSetupWizard = ChatSetupWizard;
    } else {
        window.ChatSetupWizard = ChatSetupWizard;
    }

    console.log("[ChatSetupWizard] loaded");
}());
