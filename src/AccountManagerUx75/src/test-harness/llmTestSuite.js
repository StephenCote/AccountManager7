/**
 * LLM Test Suite — Stub for lazy-loaded test suite registration.
 * The full test suite (25+ categories, ~257KB) will be ported from Ux7 as needed.
 * For now, registers a minimal suite to validate the framework infrastructure.
 */
import { TestFramework } from './testFramework.js';
import { LLMConnector } from '../chat/LLMConnector.js';

const categories = {
    library: { label: "Library Status", icon: "library_books" },
    config: { label: "Config Resolution", icon: "settings" },
    session: { label: "Session Lifecycle", icon: "chat" }
};

async function run(selectedCategories) {
    let TF = TestFramework;

    if (selectedCategories.includes("library")) {
        TF.testState.currentTest = "Library Status";
        TF.testLog("library", "Checking LLM library status...", "info");
        try {
            let ok = await LLMConnector.ensureLibrary();
            if (ok) {
                TF.testLog("library", "Library initialized", "pass");
            } else {
                TF.testLog("library", "Library not initialized (setup wizard needed)", "warn");
            }
        } catch (e) {
            TF.testLog("library", "Library check failed: " + e.message, "fail");
        }
    }

    if (selectedCategories.includes("config")) {
        TF.testState.currentTest = "Config Resolution";
        TF.testLog("config", "Resolving default chatConfig...", "info");
        try {
            let cfg = await LLMConnector.resolveConfig("default");
            if (cfg) {
                TF.testLog("config", "Resolved chatConfig: " + (cfg.name || cfg.objectId), "pass");
                TF.testLogData("config", "ChatConfig", cfg);
            } else {
                TF.testLog("config", "No default chatConfig found", "warn");
            }
        } catch (e) {
            TF.testLog("config", "Config resolution failed: " + e.message, "fail");
        }

        TF.testLog("config", "Resolving default promptTemplate...", "info");
        try {
            let pt = await LLMConnector.resolveTemplate("default");
            if (pt) {
                TF.testLog("config", "Resolved promptTemplate: " + (pt.name || pt.objectId), "pass");
            } else {
                TF.testLog("config", "No default promptTemplate found", "warn");
            }
        } catch (e) {
            TF.testLog("config", "Template resolution failed: " + e.message, "fail");
        }
    }

    if (selectedCategories.includes("session")) {
        TF.testState.currentTest = "Session Lifecycle";
        TF.testLog("session", "Session lifecycle tests require active library — skipping if not initialized", "info");
        let ok = await LLMConnector.ensureLibrary();
        if (!ok) {
            TF.testLog("session", "Library not initialized, skipping session tests", "skip");
        } else {
            TF.testLog("session", "Session lifecycle tests (create/chat/delete) — full suite pending port from Ux7", "skip");
        }
    }
}

function register() {
    TestFramework.registerSuite("llm", {
        label: "LLM Tests",
        icon: "psychology",
        categories: categories,
        run: run
    });
}

export { register };
