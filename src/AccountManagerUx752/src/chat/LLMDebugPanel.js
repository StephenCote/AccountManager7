/**
 * LLMDebugPanel — Debug panel showing active LLM requests and summarizations (ESM port).
 * Toggleable from the aside menu. Polls GET /rest/chat/llm/active every 2 seconds
 * when visible. Shows request metadata and per-row abort controls.
 */
import m from 'mithril';
import { applicationPath } from '../core/config.js';

let _visible = false;
let _data = null;
let _poller = null;
let _pollInterval = 2000;

async function fetchActive() {
    try {
        _data = await m.request({
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
        _data = null;
    }
    m.redraw();
}

function startPoller() {
    if (_poller) return;
    fetchActive();
    _poller = setInterval(fetchActive, _pollInterval);
}

function stopPoller() {
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
    if (!id) return "\u2014";
    return id.length > 12 ? id.substring(0, 12) + "\u2026" : id;
}

function llmRequestRow(req) {
    return m("tr", { class: "border-b border-gray-700" }, [
        m("td", { class: "px-2 py-1", title: req.requestId }, truncateId(req.requestId)),
        m("td", { class: "px-2 py-1" }, req.model || "\u2014"),
        m("td", { class: "px-2 py-1 text-right" }, req.tokenCount || 0),
        m("td", { class: "px-2 py-1" }, req.serviceType || "\u2014"),
        m("td", { class: "px-2 py-1" }, req.stopped
            ? m("span", { class: "text-red-400" }, "stopping")
            : m("span", { class: "text-green-400" }, "active"))
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
    if (kind === "chat") return "text-blue-300";
    if (kind.startsWith("memory:")) return "text-purple-300";
    if (kind.startsWith("embed:")) return "text-cyan-300";
    if (kind === "compliance") return "text-yellow-300";
    if (kind === "autotune") return "text-pink-300";
    if (kind === "interaction") return "text-green-300";
    if (kind === "titleIcon") return "text-orange-300";
    return "text-gray-300";
}

function activeCallRow(c) {
    let ageMs = c.ageMs;
    /// Highlight stalled calls (>30s) so the user can spot the offender at a glance.
    let stalled = ageMs != null && ageMs > 30000;
    return m("tr", { class: "border-b border-gray-700" }, [
        m("td", { class: "px-2 py-1 font-mono", title: c.id }, truncateId(c.id)),
        m("td", { class: "px-2 py-1 font-semibold " + kindColor(c.kind), title: c.kind }, c.kind || "—"),
        m("td", {
            class: "px-2 py-1 text-right " + (stalled ? "text-red-400 font-semibold" : "")
        }, fmtAge(ageMs))
    ]);
}

function summRow(s) {
    let phaseLabel = s.phase || "pending";
    if (s.total > 0) phaseLabel += " " + s.current + "/" + s.total;
    if (s.elapsed > 0) phaseLabel += " (" + s.elapsed + "s)";

    return m("tr", { class: "border-b border-gray-700" }, [
        m("td", { class: "px-2 py-1", title: s.objectId }, truncateId(s.objectId)),
        m("td", { class: "px-2 py-1", title: s.sessionId }, truncateId(s.sessionId)),
        m("td", { class: "px-2 py-1" }, phaseLabel),
        m("td", { class: "px-2 py-1" },
            s.cancelled
                ? m("span", { class: "text-gray-500" }, "cancelled")
                : m("button", {
                    class: "p-0.5 rounded hover:bg-gray-700",
                    title: "Cancel",
                    onclick: function() { cancelSummarize(s.sessionId, s.objectId); }
                }, m("span", { class: "material-symbols-outlined text-red-400", style: "font-size: 16px;" }, "stop_circle"))
        )
    ]);
}

function panelView() {
    if (!_visible) return null;

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

    return m("div", { class: "fixed bottom-0 right-0 w-96 max-h-80 bg-gray-900 border border-gray-600 rounded-tl-lg shadow-lg z-50 flex flex-col text-xs text-gray-300 overflow-hidden" }, [
        m("div", { class: "flex items-center justify-between px-3 py-2 bg-gray-800 border-b border-gray-600" }, [
            m("span", { class: "font-semibold" }, "LLM Debug"),
            m("span", { class: "flex items-center gap-2" }, [
                m("button", {
                    class: "p-0.5 rounded hover:bg-gray-700",
                    title: "Abort all",
                    onclick: abortAll
                }, m("span", { class: "material-symbols-outlined text-red-400", style: "font-size: 18px;" }, "stop_circle")),
                m("button", {
                    class: "p-0.5 rounded hover:bg-gray-700",
                    title: "Refresh",
                    onclick: fetchActive
                }, m("span", { class: "material-symbols-outlined", style: "font-size: 18px;" }, "refresh")),
                m("button", {
                    class: "p-0.5 rounded hover:bg-gray-700",
                    title: "Close",
                    onclick: function() { LLMDebugPanel.toggle(); }
                }, m("span", { class: "material-symbols-outlined", style: "font-size: 18px;" }, "close"))
            ])
        ]),
        m("div", { class: "overflow-auto flex-1 p-2" }, [
            isEmpty
                ? m("div", { class: "text-gray-500 text-center py-4" }, "No active requests")
                : [
                    llmRequests.length > 0 ? [
                        m("div", { class: "font-semibold mb-1 text-gray-400" }, "LLM Requests (" + llmRequests.length + ")"),
                        m("table", { class: "w-full mb-3" }, [
                            m("thead", m("tr", { class: "text-gray-500 border-b border-gray-600" }, [
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
                        m("div", { class: "font-semibold mb-1 text-gray-400" },
                            "Active Calls (" + activeCalls.length + ") — kind / age"),
                        m("table", { class: "w-full mb-3" }, [
                            m("thead", m("tr", { class: "text-gray-500 border-b border-gray-600" }, [
                                m("th", { class: "px-2 py-0.5 text-left" }, "ID"),
                                m("th", { class: "px-2 py-0.5 text-left" }, "Kind"),
                                m("th", { class: "px-2 py-0.5 text-right" }, "Age")
                            ])),
                            m("tbody", activeCalls.map(activeCallRow))
                        ])
                    ] : null,
                    bufferStreams > 0 ? m("div", { class: "mb-2 text-yellow-400" }, "Buffer-mode streams: " + bufferStreams) : null,
                    summarizations.length > 0 ? [
                        m("div", { class: "font-semibold mb-1 text-gray-400" }, "Summarizations (" + summarizations.length + ")"),
                        m("table", { class: "w-full" }, [
                            m("thead", m("tr", { class: "text-gray-500 border-b border-gray-600" }, [
                                m("th", { class: "px-2 py-0.5 text-left" }, "Object"),
                                m("th", { class: "px-2 py-0.5 text-left" }, "Session"),
                                m("th", { class: "px-2 py-0.5 text-left" }, "Phase"),
                                m("th", { class: "px-2 py-0.5 text-left" }, "")
                            ])),
                            m("tbody", summarizations.map(summRow))
                        ])
                    ] : null
                ]
        ])
    ]);
}

const LLMDebugPanel = {
    toggle: function() {
        _visible = !_visible;
        if (_visible) {
            startPoller();
        } else {
            stopPoller();
            _data = null;
        }
        m.redraw();
    },

    isVisible: function() { return _visible; },

    PanelView: {
        view: panelView
    }
};

export { LLMDebugPanel };
export default LLMDebugPanel;
