/**
 * Test View
 * Phase 6: Integrated test view accessible from the app menu bar.
 *
 * Visibility: Renders when page.testMode === true OR page.productionMode === false
 * URL param: ?testMode=true enables test mode
 *
 * LLM configs auto-load from media/prompts/ templates — no manual picking needed.
 *
 * Depends on: window.TestFramework, window.TestRegistry, page
 */
(function() {
    "use strict";

    let TF = window.TestFramework;

    // ── LLM Config Status (read-only display) ────────────────────────
    function ConfigStatusUI() {
        return {
            view: function() {
                let llm = window.LLMTestSuite;
                if (!llm) return null;

                let currentSuite = TF.testState.selectedSuite;
                if (currentSuite !== "llm") return null;

                let ss = llm.suiteState;
                if (!ss.chatConfig && !ss.promptConfig) {
                    return m("div", { class: "tf-config-status" },
                        m("span", { style: { color: "#999", fontStyle: "italic", fontSize: "12px" } },
                            "Configs auto-load from media/prompts/ when tests run")
                    );
                }

                let badges = [];

                // Show config variants
                let variants = ss.chatConfigs || {};
                let variantKeys = Object.keys(variants);
                if (variantKeys.length > 0) {
                    for (let i = 0; i < variantKeys.length; i++) {
                        let v = variants[variantKeys[i]];
                        if (v) {
                            badges.push(m("span", { class: "tf-config-badge" }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "13px", verticalAlign: "middle", marginRight: "3px" } }, "settings"),
                                v.name
                            ]));
                        }
                    }
                } else if (ss.chatConfig) {
                    badges.push(m("span", { class: "tf-config-badge" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "13px", verticalAlign: "middle", marginRight: "3px" } }, "settings"),
                        ss.chatConfig.name
                    ]));
                }

                if (ss.promptConfig) {
                    badges.push(m("span", { class: "tf-config-badge" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "13px", verticalAlign: "middle", marginRight: "3px" } }, "description"),
                        ss.promptConfig.name
                    ]));
                }
                if (ss.systemCharacter) {
                    badges.push(m("span", { class: "tf-config-badge" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "13px", verticalAlign: "middle", marginRight: "3px" } }, "person"),
                        (ss.systemCharacter.firstName || ss.systemCharacter.name || "sys")
                    ]));
                }
                if (ss.userCharacter) {
                    badges.push(m("span", { class: "tf-config-badge" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "13px", verticalAlign: "middle", marginRight: "3px" } }, "person_outline"),
                        (ss.userCharacter.firstName || ss.userCharacter.name || "user")
                    ]));
                }

                return m("div", { class: "tf-config-status" }, badges);
            }
        };
    }

    // ── Main Test View ────────────────────────────────────────────────
    function getTestView() {
        let suite = window.TestRegistry ? window.TestRegistry.getSelectedSuite() : null;
        let categories = suite ? (suite.categories || {}) : {};

        return m("div", { class: "content-outer" }, [
            m(page.components.navigation, { hideBreadcrumb: true }),
            m("div", { class: "content-main" },
                m("div", { class: "tf-test-view" }, [
                    // Toolbar
                    m(TF.TestToolbarUI, {
                        title: "Test Suite",
                        onBack: function() {
                            m.route.set("/main");
                        }
                    }),

                    // Suite tabs (compartmentalized by app)
                    m(TF.SuiteTabsUI),

                    // LLM config status (auto-loaded, read-only)
                    m(ConfigStatusUI),

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
