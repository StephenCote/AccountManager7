/**
 * TestRegistry — Suite discovery helpers for the test view (ESM port)
 */
import m from 'mithril';
import { TestFramework } from './testFramework.js';

function SuiteSelectorUI() {
    return {
        view: function() {
            let suites = TestFramework.getSuites();
            let keys = Object.keys(suites);
            if (keys.length <= 1) return null;

            return m("select", {
                class: "px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
                value: TestFramework.testState.selectedSuite || "",
                onchange: function(e) {
                    TestFramework.selectSuite(e.target.value);
                }
            }, keys.map(key => {
                let s = suites[key];
                return m("option", { value: key }, s.label || key);
            }));
        }
    };
}

function getSelectedSuiteCategories() {
    let suites = TestFramework.getSuites();
    let suite = suites[TestFramework.testState.selectedSuite];
    return suite ? (suite.categories || {}) : {};
}

function getSelectedSuite() {
    let suites = TestFramework.getSuites();
    return suites[TestFramework.testState.selectedSuite] || null;
}

const TestRegistry = {
    SuiteSelectorUI,
    getSelectedSuiteCategories,
    getSelectedSuite
};

export { TestRegistry };
export default TestRegistry;
