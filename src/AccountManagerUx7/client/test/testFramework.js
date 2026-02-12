/**
 * Shared Test Framework
 * Phase 6: Provides reusable test infrastructure for all test suites
 * (CardGame, LLM, etc.)
 *
 * Exposes: window.TestFramework = {
 *   testState, testLog, testLogData, runSuite, registerSuite,
 *   getSuites, clearLogs, exportLogs,
 *   TestConsoleUI, TestToolbarUI, TestCategoryToggleUI, TestResultsSummaryUI
 * }
 */
(function() {
    "use strict";

    // ── Registered suites ─────────────────────────────────────────────
    let suites = {};

    // ── State ─────────────────────────────────────────────────────────
    let testState = {
        running: false,
        logs: [],
        results: { pass: 0, fail: 0, warn: 0, skip: 0 },
        currentTest: null,
        completed: false,
        selectedCategories: [],
        logFilter: "all",
        selectedSuite: null
    };

    // ── Logging ───────────────────────────────────────────────────────
    function testLog(category, message, status) {
        if (status === undefined) status = "info";
        let entry = {
            time: new Date().toISOString().substring(11, 19),
            category: category,
            message: message,
            status: status,
            type: "log"
        };
        testState.logs.push(entry);
        if (status === "pass") testState.results.pass++;
        else if (status === "fail") testState.results.fail++;
        else if (status === "warn") testState.results.warn++;
        else if (status === "skip") testState.results.skip++;
        console.log("[TestFramework] [" + (category || "-") + "] [" + status + "] " + message);
        m.redraw();
    }

    function testLogData(category, label, data) {
        let text;
        if (typeof data === "string") {
            text = data;
        } else {
            try {
                text = JSON.stringify(data, null, 2);
            } catch (e) {
                text = String(data);
            }
        }
        let entry = {
            time: new Date().toISOString().substring(11, 19),
            category: category,
            message: label,
            data: text,
            status: "data",
            type: "data",
            collapsed: true
        };
        testState.logs.push(entry);
        console.log("[TestFramework] [" + (category || "-") + "] [DATA] " + label);
        m.redraw();
    }

    // ── Suite registration ────────────────────────────────────────────
    function registerSuite(suiteId, config) {
        suites[suiteId] = config;
        if (!testState.selectedSuite) {
            testState.selectedSuite = suiteId;
        }
    }

    function getSuites() {
        return suites;
    }

    // ── Run suite ─────────────────────────────────────────────────────
    async function runSuite(suiteId) {
        let suite = suites[suiteId || testState.selectedSuite];
        if (!suite) {
            testLog("", "No suite found: " + (suiteId || testState.selectedSuite), "fail");
            return;
        }

        testState.running = true;
        testState.logs = [];
        testState.results = { pass: 0, fail: 0, warn: 0, skip: 0 };
        testState.completed = false;
        testState.currentTest = null;
        m.redraw();

        try {
            await suite.run(testState.selectedCategories);
        } catch (e) {
            testLog("", "Suite error: " + e.message, "fail");
            testLogData("", "Error stack", e.stack);
        }

        testState.currentTest = null;
        testState.running = false;
        testState.completed = true;
        testLog("", "=== Suite complete: " + testState.results.pass + " pass, "
            + testState.results.fail + " fail, " + testState.results.warn + " warn, "
            + testState.results.skip + " skip ===",
            testState.results.fail > 0 ? "fail" : "pass");
        m.redraw();
    }

    // ── Log utilities ─────────────────────────────────────────────────
    function clearLogs() {
        testState.logs = [];
        testState.results = { pass: 0, fail: 0, warn: 0, skip: 0 };
        testState.completed = false;
        testState.currentTest = null;
        m.redraw();
    }

    function exportLogs() {
        let lines = [];
        for (let entry of testState.logs) {
            let prefix = "[" + entry.time + "] [" + (entry.category || "-") + "]";
            if (entry.type === "data") {
                lines.push(prefix + " [DATA] " + entry.message + ":");
                lines.push("  " + (entry.data || "").replace(/\n/g, "\n  "));
            } else {
                let tag = (entry.status || "info").toUpperCase();
                lines.push(prefix + " [" + tag + "] " + entry.message);
            }
        }
        let text = lines.join("\n");
        if (navigator.clipboard) {
            navigator.clipboard.writeText(text).then(function() {
                if (window.page && page.toast) page.toast("success", "Logs copied to clipboard");
            });
        }
        return text;
    }

    // ── Shared UI Components (Mithril) ────────────────────────────────

    let statusColors = { info: "#777", pass: "#2E7D32", fail: "#C62828", warn: "#E65100", data: "#1565C0", skip: "#9E9E9E" };
    let statusIcons = { info: "info", pass: "check_circle", fail: "error", warn: "warning", data: "data_object", skip: "skip_next" };

    // TestConsoleUI — scrollable log console
    function TestConsoleUI() {
        let consoleEl = null;

        function scrollToBottom() {
            if (consoleEl) consoleEl.scrollTop = consoleEl.scrollHeight;
        }

        return {
            oncreate: function() { scrollToBottom(); },
            onupdate: function() { scrollToBottom(); },
            view: function(vnode) {
                let categories = vnode.attrs.categories || {};
                let filterIssues = testState.logFilter === "issues";
                let visibleLogs = filterIssues
                    ? testState.logs.filter(function(e) { return e.status === "fail" || e.status === "warn"; })
                    : testState.logs;

                return m("div", {
                    class: "tf-test-console",
                    style: vnode.attrs.style || {},
                    oncreate: function(v) { consoleEl = v.dom; scrollToBottom(); },
                    onupdate: function(v) { consoleEl = v.dom; scrollToBottom(); }
                }, (function() {
                    let items = [];
                    let lastCat = null;
                    for (let entry of visibleLogs) {
                        if (entry.category && entry.category !== lastCat) {
                            let catLabel = (categories[entry.category] && categories[entry.category].label)
                                ? categories[entry.category].label : entry.category;
                            items.push(m("div", { class: "tf-test-section-header" }, catLabel));
                            lastCat = entry.category;
                        }
                        if (entry.type === "data") {
                            items.push(m("div", { class: "tf-test-log-entry", "data-status": "data" }, [
                                m("span", { class: "tf-test-log-time" }, entry.time),
                                entry.category ? m("span", { class: "tf-test-log-cat", "data-cat": entry.category }, entry.category) : null,
                                m("span", {
                                    class: "material-symbols-outlined",
                                    style: { fontSize: "13px", color: statusColors.data, verticalAlign: "middle", marginRight: "4px", cursor: "pointer" }
                                }, entry.collapsed ? "expand_more" : "expand_less"),
                                m("span", {
                                    style: { color: statusColors.data, cursor: "pointer", fontStyle: "italic" },
                                    onclick: function() { entry.collapsed = !entry.collapsed; m.redraw(); }
                                }, "DATA: " + entry.message),
                                !entry.collapsed ? m("pre", {
                                    class: "tf-test-data-block",
                                    style: { whiteSpace: "pre-wrap", fontSize: "11px", background: "#f5f5f5",
                                        border: "1px solid #ddd", borderRadius: "4px", padding: "6px",
                                        margin: "2px 0 4px 28px", maxHeight: "300px", overflow: "auto" }
                                }, entry.data) : null
                            ]));
                        } else {
                            let isBold = entry.status === "fail" || entry.status === "warn";
                            items.push(m("div", { class: "tf-test-log-entry", "data-status": entry.status }, [
                                m("span", { class: "tf-test-log-time" }, entry.time),
                                entry.category ? m("span", { class: "tf-test-log-cat", "data-cat": entry.category }, entry.category) : null,
                                m("span", {
                                    class: "material-symbols-outlined",
                                    style: { fontSize: "13px", color: statusColors[entry.status] || "#666", verticalAlign: "middle", marginRight: "4px" }
                                }, statusIcons[entry.status] || "info"),
                                m("span", { style: {
                                    color: statusColors[entry.status] || "#333",
                                    fontWeight: isBold ? 700 : 400
                                } }, entry.message)
                            ]));
                        }
                    }
                    if (testState.logs.length === 0) {
                        items.push(m("div", { class: "tf-test-empty" }, "Select categories and click Run to begin testing."));
                    }
                    return items;
                })());
            }
        };
    }

    // TestToolbarUI — run button, suite selector, status
    function TestToolbarUI() {
        return {
            view: function(vnode) {
                let onBack = vnode.attrs.onBack;
                let title = vnode.attrs.title || "Test Suite";
                let subtitle = vnode.attrs.subtitle;

                return m("div", { class: "tf-toolbar" }, [
                    onBack ? m("button", { class: "tf-btn", onclick: onBack }, "\u2190 Back") : null,
                    m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, title),
                    subtitle ? m("span", {
                        style: { marginLeft: "8px", fontSize: "12px", background: "#e8e8e8",
                            padding: "2px 8px", borderRadius: "4px" }
                    }, subtitle) : null,

                    // Suite selector
                    Object.keys(suites).length > 1 ? m("select", {
                        class: "tf-suite-select",
                        style: { marginLeft: "12px" },
                        value: testState.selectedSuite || "",
                        onchange: function(e) {
                            testState.selectedSuite = e.target.value;
                            let suite = suites[testState.selectedSuite];
                            if (suite) {
                                testState.selectedCategories = Object.keys(suite.categories || {});
                            }
                            clearLogs();
                        }
                    }, Object.entries(suites).map(function(pair) {
                        return m("option", { value: pair[0] }, pair[1].label || pair[0]);
                    })) : null,

                    // Run / status
                    !testState.running ? m("button", {
                        class: "tf-btn tf-btn-primary", style: { marginLeft: "auto" },
                        onclick: function() { runSuite(testState.selectedSuite); }
                    }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "play_arrow"),
                        "Run Tests"
                    ]) : null,
                    testState.running ? m("span", { style: { marginLeft: "auto", color: "#B8860B", fontWeight: 600, fontSize: "13px" } }, [
                        m("span", { class: "material-symbols-outlined tf-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "sync"),
                        testState.currentTest || "Running..."
                    ]) : null
                ]);
            }
        };
    }

    // TestCategoryToggleUI — category checkboxes
    function TestCategoryToggleUI() {
        return {
            view: function(vnode) {
                let categories = vnode.attrs.categories || {};
                return m("div", { class: "tf-test-categories" },
                    Object.entries(categories).map(function(pair) {
                        let key = pair[0];
                        let cat = pair[1];
                        let active = testState.selectedCategories.includes(key);
                        return m("label", {
                            class: "tf-test-cat" + (active ? " active" : ""),
                            onclick: function() {
                                if (active) testState.selectedCategories = testState.selectedCategories.filter(function(c) { return c !== key; });
                                else testState.selectedCategories.push(key);
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, cat.icon),
                            " " + cat.label
                        ]);
                    })
                );
            }
        };
    }

    // TestResultsSummaryUI — pass/fail/warn/skip counts with filter buttons
    function TestResultsSummaryUI() {
        return {
            view: function() {
                if (!testState.completed) return null;
                let filterIssues = testState.logFilter === "issues";
                let issueCount = testState.results.fail + testState.results.warn;

                return m("div", { class: "tf-test-summary" }, [
                    m("span", { class: "tf-test-result-pass" }, testState.results.pass + " pass"),
                    m("span", { class: "tf-test-result-fail" }, testState.results.fail + " fail"),
                    m("span", { class: "tf-test-result-warn" }, testState.results.warn + " warn"),
                    testState.results.skip > 0 ? m("span", { class: "tf-test-result-skip" }, testState.results.skip + " skip") : null,
                    m("span", { style: { marginLeft: "auto", display: "flex", gap: "4px" } }, [
                        m("button", {
                            class: "tf-test-filter-btn" + (!filterIssues ? " active" : ""),
                            onclick: function() { testState.logFilter = "all"; m.redraw(); }
                        }, "All"),
                        m("button", {
                            class: "tf-test-filter-btn" + (filterIssues ? " active" : ""),
                            onclick: function() { testState.logFilter = "issues"; m.redraw(); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", verticalAlign: "middle", marginRight: "2px" } }, "error"),
                            "Issues" + (issueCount > 0 ? " (" + issueCount + ")" : "")
                        ]),
                        m("button", {
                            class: "tf-test-filter-btn",
                            onclick: function() { exportLogs(); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", verticalAlign: "middle", marginRight: "2px" } }, "content_copy"),
                            "Copy Logs"
                        ])
                    ])
                ]);
            }
        };
    }

    // ── Expose ────────────────────────────────────────────────────────
    window.TestFramework = {
        testState: testState,
        testLog: testLog,
        testLogData: testLogData,
        runSuite: runSuite,
        registerSuite: registerSuite,
        getSuites: getSuites,
        clearLogs: clearLogs,
        exportLogs: exportLogs,
        TestConsoleUI: TestConsoleUI,
        TestToolbarUI: TestToolbarUI,
        TestCategoryToggleUI: TestCategoryToggleUI,
        TestResultsSummaryUI: TestResultsSummaryUI
    };

    console.log("[TestFramework] loaded");
}());
