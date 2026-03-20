/**
 * TestFramework — Shared test infrastructure for all test suites (ESM port)
 * Provides logging, execution, abort, and UI components.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';

// ── Registered suites ───────────────────────────────────────────────
let suites = {};

// ── State ───────────────────────────────────────────────────────────
let testState = {
    running: false,
    aborted: false,
    logs: [],
    results: { pass: 0, fail: 0, warn: 0, skip: 0 },
    currentTest: null,
    completed: false,
    selectedCategories: [],
    logFilter: "all",
    selectedSuite: null
};

// ── Logging ─────────────────────────────────────────────────────────
function testLog(category, message, status) {
    if (testState.aborted) throw new Error("__ABORTED__");
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
        try { text = JSON.stringify(data, null, 2); }
        catch (e) { text = String(data); }
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

// ── Suite registration ──────────────────────────────────────────────
function registerSuite(suiteId, config) {
    suites[suiteId] = config;
    if (!testState.selectedSuite) selectSuite(suiteId);
}

function getSuites() { return suites; }

function selectSuite(suiteId) {
    let suite = suites[suiteId];
    if (!suite) return;
    testState.selectedSuite = suiteId;
    testState.selectedCategories = Object.keys(suite.categories || {});
    clearLogs();
}

function stopSuite() {
    if (!testState.running) return;
    testState.aborted = true;
    m.redraw();
}

function isAborted() { return testState.aborted; }

// ── Run suite ───────────────────────────────────────────────────────
async function runSuite(suiteId) {
    let suite = suites[suiteId || testState.selectedSuite];
    if (!suite) {
        testLog("", "No suite found: " + (suiteId || testState.selectedSuite), "fail");
        return;
    }

    testState.running = true;
    testState.aborted = false;
    testState.logs = [];
    testState.results = { pass: 0, fail: 0, warn: 0, skip: 0 };
    testState.completed = false;
    testState.currentTest = null;
    m.redraw();

    let wasAborted = false;
    try {
        await suite.run(testState.selectedCategories);
    } catch (e) {
        if (e.message === "__ABORTED__" || testState.aborted) {
            wasAborted = true;
        } else {
            testState.aborted = false;
            testLog("", "Suite error: " + e.message, "fail");
            testLogData("", "Error stack", e.stack);
        }
    }

    testState.currentTest = null;
    testState.running = false;
    testState.completed = true;
    testState.aborted = false;

    let summary = testState.results.pass + " pass, " + testState.results.fail + " fail, "
        + testState.results.warn + " warn, " + testState.results.skip + " skip";
    testState.logs.push({
        time: new Date().toISOString().substring(11, 19),
        category: "",
        message: wasAborted ? "=== Suite aborted: " + summary + " ===" : "=== Suite complete: " + summary + " ===",
        status: wasAborted ? "warn" : (testState.results.fail > 0 ? "fail" : "pass"),
        type: "log"
    });
    if (wasAborted) testState.results.warn++;
    m.redraw();
}

// ── Log utilities ───────────────────────────────────────────────────
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
            lines.push(prefix + " [" + (entry.status || "info").toUpperCase() + "] " + entry.message);
        }
    }
    let text = lines.join("\n");
    if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(function() {
            page.toast("success", "Logs copied to clipboard");
        });
    }
    return text;
}

// ── UI Components ───────────────────────────────────────────────────

let statusColors = { info: "#777", pass: "#2E7D32", fail: "#C62828", warn: "#E65100", data: "#1565C0", skip: "#9E9E9E" };
let statusIcons = { info: "info", pass: "check_circle", fail: "error", warn: "warning", data: "data_object", skip: "skip_next" };

function TestConsoleUI() {
    let consoleEl = null;
    function scrollToBottom() { if (consoleEl) consoleEl.scrollTop = consoleEl.scrollHeight; }

    return {
        oncreate: function() { scrollToBottom(); },
        onupdate: function() { scrollToBottom(); },
        view: function(vnode) {
            let categories = vnode.attrs.categories || {};
            let filterIssues = testState.logFilter === "issues";
            let visibleLogs = filterIssues
                ? testState.logs.filter(e => e.status === "fail" || e.status === "warn")
                : testState.logs;

            return m("div", {
                class: "flex-1 overflow-y-auto font-mono text-xs p-2 bg-gray-50 dark:bg-gray-950",
                oncreate: v => { consoleEl = v.dom; scrollToBottom(); },
                onupdate: v => { consoleEl = v.dom; scrollToBottom(); }
            }, (function() {
                let items = [];
                let lastCat = null;
                for (let entry of visibleLogs) {
                    if (entry.category && entry.category !== lastCat) {
                        let catLabel = (categories[entry.category] && categories[entry.category].label) || entry.category;
                        items.push(m("div", { class: "text-xs font-bold text-gray-500 mt-3 mb-1 border-b border-gray-300 dark:border-gray-700 pb-1" }, catLabel));
                        lastCat = entry.category;
                    }
                    if (entry.type === "data") {
                        items.push(m("div", { class: "flex items-start gap-1 py-0.5" }, [
                            m("span", { class: "text-gray-400 shrink-0" }, entry.time),
                            m("span", { class: "material-symbols-outlined cursor-pointer", style: "font-size:13px;color:" + statusColors.data, onclick: () => { entry.collapsed = !entry.collapsed; m.redraw(); } }, entry.collapsed ? "expand_more" : "expand_less"),
                            m("span", { class: "cursor-pointer italic", style: "color:" + statusColors.data, onclick: () => { entry.collapsed = !entry.collapsed; m.redraw(); } }, "DATA: " + entry.message),
                            !entry.collapsed ? m("pre", { class: "ml-7 mt-1 p-2 bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 rounded text-xs overflow-auto max-h-60 whitespace-pre-wrap" }, entry.data) : null
                        ]));
                    } else {
                        items.push(m("div", { class: "flex items-center gap-1 py-0.5" }, [
                            m("span", { class: "text-gray-400 shrink-0" }, entry.time),
                            entry.category ? m("span", { class: "text-gray-500 shrink-0" }, "[" + entry.category + "]") : null,
                            m("span", { class: "material-symbols-outlined shrink-0", style: "font-size:13px;color:" + (statusColors[entry.status] || "#666") }, statusIcons[entry.status] || "info"),
                            m("span", { style: "color:" + (statusColors[entry.status] || "#333") + ";font-weight:" + (entry.status === "fail" || entry.status === "warn" ? 700 : 400) }, entry.message)
                        ]));
                    }
                }
                if (testState.logs.length === 0) {
                    items.push(m("div", { class: "text-gray-400 text-center py-8" }, "Select categories and click Run to begin testing."));
                }
                return items;
            })());
        }
    };
}

function TestToolbarUI() {
    return {
        view: function(vnode) {
            let title = vnode.attrs.title || "Test Suite";
            return m("div", { class: "flex items-center gap-2 px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900" }, [
                m("span", { class: "font-bold text-gray-800 dark:text-white" }, title),
                !testState.running ? m("button", {
                    class: "ml-auto px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500 flex items-center gap-1",
                    onclick: () => runSuite(testState.selectedSuite)
                }, [m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "play_arrow"), "Run Tests"]) : null,
                testState.running ? m("div", { class: "ml-auto flex items-center gap-2" }, [
                    m("span", { class: "text-yellow-600 dark:text-yellow-400 text-sm font-semibold flex items-center gap-1" }, [
                        m("span", { class: "material-symbols-outlined animate-spin", style: "font-size:14px" }, "sync"),
                        testState.currentTest || "Running..."
                    ]),
                    m("button", {
                        class: "px-3 py-1.5 rounded text-sm bg-red-600 text-white hover:bg-red-500 flex items-center gap-1",
                        onclick: stopSuite
                    }, [m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "stop"), "Stop"])
                ]) : null
            ]);
        }
    };
}

function SuiteTabsUI() {
    return {
        view: function() {
            let keys = Object.keys(suites);
            if (keys.length <= 1) return null;
            return m("div", { class: "flex gap-1 px-4 py-1 border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-900" },
                keys.map(key => {
                    let suite = suites[key];
                    let active = testState.selectedSuite === key;
                    return m("button", {
                        class: "px-3 py-1 rounded text-sm " + (active ? "bg-blue-100 dark:bg-blue-900 text-blue-700 dark:text-blue-300 font-semibold" : "text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-gray-800"),
                        onclick: () => { if (!active) selectSuite(key); }
                    }, [
                        suite.icon ? m("span", { class: "material-symbols-outlined mr-1", style: "font-size:16px;vertical-align:middle" }, suite.icon) : null,
                        suite.label || key
                    ]);
                })
            );
        }
    };
}

function TestCategoryToggleUI() {
    return {
        view: function(vnode) {
            let categories = vnode.attrs.categories || {};
            let allKeys = Object.keys(categories);
            let allSelected = allKeys.length > 0 && allKeys.every(k => testState.selectedCategories.includes(k));
            let noneSelected = allKeys.every(k => !testState.selectedCategories.includes(k));

            return m("div", { class: "flex flex-wrap gap-1 px-4 py-2 border-b border-gray-200 dark:border-gray-700" }, [
                m("button", {
                    class: "px-2 py-0.5 rounded text-xs " + (allSelected ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300"),
                    onclick: () => { testState.selectedCategories = allKeys.slice(); m.redraw(); }
                }, "All"),
                m("button", {
                    class: "px-2 py-0.5 rounded text-xs " + (noneSelected ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300"),
                    onclick: () => { testState.selectedCategories = []; m.redraw(); }
                }, "None"),
                Object.entries(categories).map(([key, cat]) => {
                    let active = testState.selectedCategories.includes(key);
                    return m("button", {
                        class: "px-2 py-0.5 rounded text-xs flex items-center gap-0.5 " + (active ? "bg-blue-100 dark:bg-blue-900 text-blue-700" : "bg-gray-100 dark:bg-gray-800 text-gray-500"),
                        onclick: () => {
                            if (active) testState.selectedCategories = testState.selectedCategories.filter(c => c !== key);
                            else testState.selectedCategories.push(key);
                            m.redraw();
                        }
                    }, [
                        cat.icon ? m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, cat.icon) : null,
                        cat.label
                    ]);
                })
            ]);
        }
    };
}

function TestResultsSummaryUI() {
    return {
        view: function() {
            if (!testState.completed) return null;
            let filterIssues = testState.logFilter === "issues";
            let issueCount = testState.results.fail + testState.results.warn;

            return m("div", { class: "flex items-center gap-3 px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 text-sm" }, [
                m("span", { class: "text-green-700 font-semibold" }, testState.results.pass + " pass"),
                m("span", { class: "text-red-700 font-semibold" }, testState.results.fail + " fail"),
                m("span", { class: "text-orange-600 font-semibold" }, testState.results.warn + " warn"),
                testState.results.skip > 0 ? m("span", { class: "text-gray-500" }, testState.results.skip + " skip") : null,
                m("span", { class: "ml-auto flex gap-1" }, [
                    m("button", {
                        class: "px-2 py-0.5 rounded text-xs " + (!filterIssues ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700"),
                        onclick: () => { testState.logFilter = "all"; m.redraw(); }
                    }, "All"),
                    m("button", {
                        class: "px-2 py-0.5 rounded text-xs " + (filterIssues ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700"),
                        onclick: () => { testState.logFilter = "issues"; m.redraw(); }
                    }, "Issues" + (issueCount > 0 ? " (" + issueCount + ")" : "")),
                    m("button", {
                        class: "px-2 py-0.5 rounded text-xs bg-gray-200 dark:bg-gray-700",
                        onclick: exportLogs
                    }, "Copy Logs")
                ])
            ]);
        }
    };
}

// ── Export ───────────────────────────────────────────────────────────

const TestFramework = {
    testState,
    testLog,
    testLogData,
    runSuite,
    stopSuite,
    isAborted,
    registerSuite,
    selectSuite,
    getSuites,
    clearLogs,
    exportLogs,
    TestConsoleUI,
    TestToolbarUI,
    TestCategoryToggleUI,
    TestResultsSummaryUI,
    SuiteTabsUI
};

export { TestFramework };
export default TestFramework;
