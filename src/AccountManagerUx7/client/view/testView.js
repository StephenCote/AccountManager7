/**
 * Test View
 * Phase 6: Integrated test view accessible from the app menu bar.
 *
 * Visibility: Renders when page.testMode === true OR page.productionMode === false
 * URL param: ?testMode=true enables test mode
 *
 * Depends on: window.TestFramework, window.TestRegistry, window.LLMTestSuite, page
 */
(function() {
    "use strict";

    let TF = window.TestFramework;
    let configsLoaded = false;

    // ── Config Picker UI ──────────────────────────────────────────────
    function ConfigPickerUI() {
        return {
            view: function() {
                let llm = window.LLMTestSuite;
                if (!llm) return null;

                let ss = llm.suiteState;

                // Only show config pickers for LLM suite
                let currentSuite = TF.testState.selectedSuite;
                if (currentSuite !== "llm") return null;

                return m("div", { class: "tf-config-picker" }, [
                    m("label", { style: { fontWeight: 600, fontSize: "13px", marginRight: "8px" } }, "Config:"),

                    // Chat config picker
                    m("select", {
                        class: "tf-config-select",
                        value: ss.chatConfigName || "",
                        onchange: function(e) {
                            let name = e.target.value;
                            let cfg = ss.availableChatConfigs.find(function(c) { return c.name === name; });
                            llm.setChatConfig(cfg || null);
                            m.redraw();
                        }
                    }, [
                        m("option", { value: "" }, "-- chatConfig --"),
                        ss.availableChatConfigs.map(function(c) {
                            return m("option", { value: c.name }, c.name);
                        })
                    ]),

                    // Prompt config picker
                    m("select", {
                        class: "tf-config-select",
                        style: { marginLeft: "8px" },
                        value: ss.promptConfigName || "",
                        onchange: function(e) {
                            let name = e.target.value;
                            let cfg = ss.availablePromptConfigs.find(function(c) { return c.name === name; });
                            llm.setPromptConfig(cfg || null);
                            m.redraw();
                        }
                    }, [
                        m("option", { value: "" }, "-- promptConfig --"),
                        ss.availablePromptConfigs.map(function(c) {
                            return m("option", { value: c.name }, c.name);
                        })
                    ])
                ]);
            }
        };
    }

    // ── Main Test View ────────────────────────────────────────────────
    function getTestView() {
        // Load configs on first render
        if (!configsLoaded) {
            configsLoaded = true;
            if (window.LLMTestSuite) {
                window.LLMTestSuite.loadAvailableConfigs().then(function() {
                    // Auto-select first configs if available
                    let ss = window.LLMTestSuite.suiteState;
                    if (!ss.chatConfig && ss.availableChatConfigs.length > 0) {
                        window.LLMTestSuite.setChatConfig(ss.availableChatConfigs[0]);
                    }
                    if (!ss.promptConfig && ss.availablePromptConfigs.length > 0) {
                        window.LLMTestSuite.setPromptConfig(ss.availablePromptConfigs[0]);
                    }
                    m.redraw();
                });
            }
            // Initialize selected categories from the default suite
            let suite = window.TestRegistry ? window.TestRegistry.getSelectedSuite() : null;
            if (suite && TF.testState.selectedCategories.length === 0) {
                TF.testState.selectedCategories = Object.keys(suite.categories || {});
            }
        }

        let suite = window.TestRegistry ? window.TestRegistry.getSelectedSuite() : null;
        let categories = suite ? (suite.categories || {}) : {};

        return m("div", { class: "content-outer" }, [
            m(page.components.navigation, { hideBreadcrumb: true }),
            m("div", { class: "content-main" },
                m("div", { class: "tf-test-view" }, [
                    // Toolbar
                    m(TF.TestToolbarUI, {
                        title: "Test Suite",
                        subtitle: suite ? suite.label : null,
                        onBack: function() {
                            m.route.set("/main");
                        }
                    }),

                    // Config picker (LLM-specific)
                    m(ConfigPickerUI),

                    // Category toggles
                    m(TF.TestCategoryToggleUI, { categories: categories }),

                    // Results summary
                    m(TF.TestResultsSummaryUI),

                    // Console
                    m(TF.TestConsoleUI, {
                        categories: categories,
                        style: { minHeight: "400px", maxHeight: "calc(100vh - 320px)" }
                    })
                ])
            )
        ]);
    }

    // ── View registration ─────────────────────────────────────────────
    let testViewComponent = {
        view: function(vnode) {
            return getTestView();
        }
    };

    page.views.testView = testViewComponent;

    console.log("[TestView] loaded");
}());
