/**
 * Test Harness feature — Test framework UI + route export (ESM)
 * Composes: toolbar + suite tabs + category toggles + results summary + console
 */
import m from 'mithril';
import { layout, pageLayout } from '../router.js';
import { TestFramework } from '../test-harness/testFramework.js';
import { TestRegistry } from '../test-harness/testRegistry.js';

// ── Lazy-load LLM test suite on first render ────────────────────────

let llmSuiteLoaded = false;

async function ensureLLMSuite() {
    if (llmSuiteLoaded) return;
    llmSuiteLoaded = true;
    try {
        let mod = await import('../test-harness/llmTestSuite.js');
        if (mod && mod.register) mod.register();
    } catch (e) {
        console.warn("[TestHarness] LLM test suite not available:", e.message);
    }
}

// ── Test view component ─────────────────────────────────────────────

const testView = {
    oninit: function() {
        ensureLLMSuite();
    },
    view: function() {
        let categories = TestRegistry.getSelectedSuiteCategories();
        let suite = TestRegistry.getSelectedSuite();
        let title = suite ? suite.label : "Test Suite";

        let ToolbarUI = TestFramework.TestToolbarUI();
        let TabsUI = TestFramework.SuiteTabsUI();
        let CatUI = TestFramework.TestCategoryToggleUI();
        let SummaryUI = TestFramework.TestResultsSummaryUI();
        let ConsoleUI = TestFramework.TestConsoleUI();

        return m("div", { class: "flex flex-col h-full w-full bg-white dark:bg-gray-950" }, [
            m(ToolbarUI, { title: title }),
            m(TabsUI),
            m(CatUI, { categories: categories }),
            m(SummaryUI),
            m(ConsoleUI, { categories: categories })
        ]);
    }
};

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/test": {
        oninit: function() { testView.oninit(); },
        view: function() {
            return layout(
                m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
                    testView.view()
                ])
            );
        }
    }
};
