/**
 * LLMDebugPanel — live view of active LLM requests and summarizations (ESM port).
 * Rendered inline by the App Panel (#!/app). Polls GET /rest/chat/llm/active every 2 seconds
 * while mounted. Shows request metadata and per-row abort controls.
 */
import m from 'mithril';
import { applicationPath } from '../core/config.js';

let _data = null;
let _loaded = false;
let _poller = null;
let _pollInterval = 2000;
let _generation = 0;

async function fetchActive() {
    let gen = _generation;
    let data = null;
    try {
        data = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/chat/llm/active",
            withCredentials: true,
            extract: function(xhr) {
                if (xhr.status !== 200 || !xhr.responseText) return null;
                try { return JSON.parse(xhr.responseText); }
                catch(e) { return null; }
            }
        });
    } catch (e) {
        console.warn("[LLMDebugPanel] fetch failed:", e);
    }
    // A response that lands after the panel was unmounted must not repopulate the next mount.
    if (gen !== _generation) return;
    _data = data;
    _loaded = true;
    m.redraw();
}

function startPoller() {
    if (_poller) return;
    fetchActive();
    _poller = setInterval(fetchActive, _pollInterval);
}

function stopPoller() {
    _generation++;
    if (_poller) {
        clearInterval(_poller);
        _poller = null;
    }
}

function abortAll() {
    m.request({
        method: 'POST',
        url: applicationPath + "/rest/chat/llm/abort-all",
        withCredentials: true
    }).then(function() {
        fetchActive();
    }).catch(function(e) {
        console.error("[LLMDebugPanel] abort-all failed:", e);
    });
}

function cancelSummarize(sessionId, objectId) {
    m.request({
        method: 'POST',
        url: applicationPath + "/rest/chat/summarize/cancel",
        withCredentials: true,
        body: { sessionId: sessionId, objectId: objectId }
    }).then(function() {
        fetchActive();
    }).catch(function(e) {
        console.error("[LLMDebugPanel] cancel summarize failed:", e);
    });
}

function truncateId(id) {
    if (!id) return "—";
    return id.length > 12 ? id.substring(0, 12) + "…" : id;
}

const ROW = "border-b border-gray-200 dark:border-gray-700";
const HEAD = "text-gray-500 dark:text-gray-400 border-b border-gray-300 dark:border-gray-600";
const SECTION = "font-semibold mb-1 text-gray-600 dark:text-gray-400";

function llmRequestRow(req) {
    return m("tr", { class: ROW }, [
        m("td", { class: "px-2 py-1", title: req.requestId }, truncateId(req.requestId)),
        m("td", { class: "px-2 py-1" }, req.model || "—"),
        m("td", { class: "px-2 py-1 text-right" }, req.tokenCount || 0),
        m("td", { class: "px-2 py-1" }, req.serviceType || "—"),
        m("td", { class: "px-2 py-1" }, req.stopped
            ? m("span", { class: "text-red-600 dark:text-red-400" }, "stopping")
            : m("span", { class: "text-green-600 dark:text-green-400" }, "active"))
    ]);
}

function fmtAge(ageMs) {
    if (ageMs == null || ageMs < 0) return "—";
    if (ageMs < 1000) return ageMs + "ms";
    if (ageMs < 60000) return (ageMs / 1000).toFixed(1) + "s";
    let mins = Math.floor(ageMs / 60000);
    let secs = Math.floor((ageMs % 60000) / 1000);
    return mins + "m" + (secs < 10 ? "0" : "") + secs + "s";
}

function kindColor(kind) {
    if (!kind) return "text-gray-400";
    if (kind === "chat") return "text-blue-600 dark:text-blue-300";
    if (kind.startsWith("memory:")) return "text-purple-600 dark:text-purple-300";
    if (kind.startsWith("embed:")) return "text-cyan-600 dark:text-cyan-300";
    if (kind === "compliance") return "text-yellow-600 dark:text-yellow-300";
    if (kind === "autotune") return "text-pink-600 dark:text-pink-300";
    if (kind === "interaction") return "text-green-600 dark:text-green-300";
    if (kind === "titleIcon") return "text-orange-600 dark:text-orange-300";
    return "text-gray-600 dark:text-gray-300";
}

function activeCallRow(c) {
    let ageMs = c.ageMs;
    /// Highlight stalled calls (>30s) so the user can spot the offender at a glance.
    let stalled = ageMs != null && ageMs > 30000;
    return m("tr", { class: ROW }, [
        m("td", { class: "px-2 py-1 font-mono", title: c.id }, truncateId(c.id)),
        m("td", { class: "px-2 py-1 font-semibold " + kindColor(c.kind), title: c.kind }, c.kind || "—"),
        m("td", {
            class: "px-2 py-1 text-right " + (stalled ? "text-red-600 dark:text-red-400 font-semibold" : "")
        }, fmtAge(ageMs))
    ]);
}

function summRow(s) {
    let phaseLabel = s.phase || "pending";
    if (s.total > 0) phaseLabel += " " + s.current + "/" + s.total;
    if (s.elapsed > 0) phaseLabel += " (" + s.elapsed + "s)";

    return m("tr", { class: ROW }, [
        m("td", { class: "px-2 py-1", title: s.objectId }, truncateId(s.objectId)),
        m("td", { class: "px-2 py-1", title: s.sessionId }, truncateId(s.sessionId)),
        m("td", { class: "px-2 py-1" }, phaseLabel),
        m("td", { class: "px-2 py-1" },
            s.cancelled
                ? m("span", { class: "text-gray-500" }, "cancelled")
                : m("button", {
                    class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                    title: "Cancel",
                    onclick: function() { cancelSummarize(s.sessionId, s.objectId); }
                }, m("span", { class: "material-symbols-outlined text-red-600 dark:text-red-400", style: "font-size: 16px;" }, "stop_circle"))
        )
    ]);
}

function inlineView() {
    let llmRequests = (_data && _data.llmRequests) ? _data.llmRequests : [];
    let summarizations = (_data && _data.summarizations) ? _data.summarizations : [];
    let bufferStreams = (_data && _data.bufferModeStreams) ? _data.bufferModeStreams : 0;
    /// Phase 5.3 (ConversationQualityPlan): per-call kind visibility.
    /// Each entry: { id, kind, startMs, ageMs }. Covers streams AND sync
    /// embedding calls (which previously didn't show up anywhere). Sorted
    /// newest-first by startMs so the oldest stalled call sits at the bottom.
    let activeCalls = (_data && _data.activeLLMCalls) ? _data.activeLLMCalls.slice() : [];
    activeCalls.sort(function(a, b) { return (b.startMs || 0) - (a.startMs || 0); });
    let isEmpty = llmRequests.length === 0 && summarizations.length === 0 && bufferStreams === 0 && activeCalls.length === 0;

    return m("div", { class: "text-xs text-gray-700 dark:text-gray-300", "data-llm-debug": "1" }, [
        m("div", { class: "flex items-center justify-end gap-2 mb-2" }, [
            m("button", {
                class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                title: "Abort all",
                onclick: abortAll
            }, m("span", { class: "material-symbols-outlined text-red-600 dark:text-red-400", style: "font-size: 18px;" }, "stop_circle")),
            m("button", {
                class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                title: "Refresh",
                onclick: fetchActive
            }, m("span", { class: "material-symbols-outlined", style: "font-size: 18px;" }, "refresh"))
        ]),
        isEmpty
            ? m("div", { class: "text-gray-500 text-center py-4" },
                !_loaded ? "Loading…" : (_data ? "No active requests" : "LLM status unavailable"))
            : [
                llmRequests.length > 0 ? [
                    m("div", { class: SECTION }, "LLM Requests (" + llmRequests.length + ")"),
                    m("table", { class: "w-full mb-3" }, [
                        m("thead", m("tr", { class: HEAD }, [
                            m("th", { class: "px-2 py-0.5 text-left" }, "ID"),
                            m("th", { class: "px-2 py-0.5 text-left" }, "Model"),
                            m("th", { class: "px-2 py-0.5 text-right" }, "Tokens"),
                            m("th", { class: "px-2 py-0.5 text-left" }, "Type"),
                            m("th", { class: "px-2 py-0.5 text-left" }, "Status")
                        ])),
                        m("tbody", llmRequests.map(llmRequestRow))
                    ])
                ] : null,
                activeCalls.length > 0 ? [
                    m("div", { class: SECTION },
                        "Active Calls (" + activeCalls.length + ") — kind / age"),
                    m("table", { class: "w-full mb-3" }, [
                        m("thead", m("tr", { class: HEAD }, [
                            m("th", { class: "px-2 py-0.5 text-left" }, "ID"),
                            m("th", { class: "px-2 py-0.5 text-left" }, "Kind"),
                            m("th", { class: "px-2 py-0.5 text-right" }, "Age")
                        ])),
                        m("tbody", activeCalls.map(activeCallRow))
                    ])
                ] : null,
                bufferStreams > 0 ? m("div", { class: "mb-2 text-yellow-700 dark:text-yellow-400" }, "Buffer-mode streams: " + bufferStreams) : null,
                summarizations.length > 0 ? [
                    m("div", { class: SECTION }, "Summarizations (" + summarizations.length + ")"),
                    m("table", { class: "w-full" }, [
                        m("thead", m("tr", { class: HEAD }, [
                            m("th", { class: "px-2 py-0.5 text-left" }, "Object"),
                            m("th", { class: "px-2 py-0.5 text-left" }, "Session"),
                            m("th", { class: "px-2 py-0.5 text-left" }, "Phase"),
                            m("th", { class: "px-2 py-0.5 text-left" }, "")
                        ])),
                        m("tbody", summarizations.map(summRow))
                    ])
                ] : null
            ]
    ]);
}

const LLMDebugPanel = {
    fetchActive: fetchActive,
    abortAll: abortAll,
    InlineView: {
        oninit: function() { startPoller(); },
        onremove: function() { stopPoller(); _data = null; _loaded = false; },
        view: inlineView
    }
};

export { LLMDebugPanel };
export default LLMDebugPanel;
