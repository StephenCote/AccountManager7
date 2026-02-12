/**
 * Test Registry
 * Phase 6: Provides suite discovery for the test view.
 * Suites register themselves via TestFramework.registerSuite().
 * This module provides helpers for the test view to enumerate and select suites.
 *
 * Depends on: window.TestFramework (testFramework.js)
 */
(function() {
    "use strict";

    let TF = window.TestFramework;

    // ── SuiteSelectorUI — dropdown for picking which suite to run ─────
    function SuiteSelectorUI() {
        return {
            view: function(vnode) {
                let suites = TF.getSuites();
                let keys = Object.keys(suites);
                if (keys.length <= 1) return null;

                return m("select", {
                    class: "tf-suite-select",
                    value: TF.testState.selectedSuite || "",
                    onchange: function(e) {
                        TF.testState.selectedSuite = e.target.value;
                        let suite = suites[TF.testState.selectedSuite];
                        if (suite) {
                            TF.testState.selectedCategories = Object.keys(suite.categories || {});
                        }
                        TF.clearLogs();
                    }
                }, keys.map(function(key) {
                    let s = suites[key];
                    return m("option", { value: key }, s.label || key);
                }));
            }
        };
    }

    // ── Convenience: get the currently selected suite's categories ────
    function getSelectedSuiteCategories() {
        let suites = TF.getSuites();
        let suite = suites[TF.testState.selectedSuite];
        return suite ? (suite.categories || {}) : {};
    }

    function getSelectedSuite() {
        let suites = TF.getSuites();
        return suites[TF.testState.selectedSuite] || null;
    }

    window.TestRegistry = {
        SuiteSelectorUI: SuiteSelectorUI,
        getSelectedSuiteCategories: getSelectedSuiteCategories,
        getSelectedSuite: getSelectedSuite
    };

    console.log("[TestRegistry] loaded");
}());
