/**
 * LLMDebugPanel — live view of active LLM requests and summarizations (ESM port).
 * Rendered inline by the App Panel (#!/app). Polls GET /rest/chat/llm/active every 2 seconds
 * while mounted. Shows request metadata and per-row abort controls.
 * SessionView is the per-chat variant: the chat view's "LLM Debug" drawer, and the App Panel's
 * "Last active chat" section (fed by setLastChatSession).
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

/// Mounted views sharing the one poller: the App Panel renders InlineView and (when a chat was active)
/// SessionView together, and the chat's debug drawer renders SessionView on its own. The poller and
/// its data live until the last subscriber unmounts.
let _subscribers = 0;

function startPoller() {
    _subscribers++;
    if (_poller) return;
    fetchActive();
    _poller = setInterval(fetchActive, _pollInterval);
}

function stopPoller() {
    if (_subscribers > 0) _subscribers--;
    if (_subscribers > 0) return;
    _generation++;
    if (_poller) {
        clearInterval(_poller);
        _poller = null;
    }
    _data = null;
    _loaded = false;
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

// ── Per-chat session view ───────────────────────────────────────────

/// Phase text for an attached context object's summarization (fields from GET /rest/chat/context).
function summarizeProgressText(ref) {
    let phase = ref.summaryPhase || "pending";
    if (phase === "vectorize") return "vectorizing...";
    if (phase === "vectorize-summary") return "vectorizing summary...";
    if (phase === "map") return ref.summaryTotal > 0 ? "summarizing " + ref.summaryCurrent + "/" + ref.summaryTotal + "..." : "summarizing...";
    if (phase === "reduce") return ref.summaryTotal > 0 ? "merging " + ref.summaryCurrent + "/" + ref.summaryTotal + "..." : "merging...";
    if (phase === "complete") return "complete";
    if (phase === "failed") return "failed";
    if (phase === "cancelled") return "cancelled";
    return "summarizing...";
}

/// Last chat session the chat view reported (plain-data snapshot; callbacks are dropped so the App Panel
/// never drives chat-view state it is not showing).
let _lastChatSession = null;

function setLastChatSession(info) {
    if (!info) { _lastChatSession = null; return; }
    let snap = {};
    Object.keys(info).forEach(function(k) {
        let v = info[k];
        if (typeof v === "function") return;
        if (k === "contextRefs") return;
        snap[k] = v;
    });
    _lastChatSession = snap;
}

function getLastChatSession() { return _lastChatSession; }

/// Server rows that belong to this chat. llmRequests are keyed by the chat's OpenAI request (session)
/// objectId (ChatListener.getRequestInfo requestId); summarizations by the chatRequest objectId
/// (ChatService summarizingRefs). activeLLMCalls carry only a stream/call id, so they cannot be attributed.
function sessionRows(info) {
    let ids = [info.sessionId, info.requestId].filter(function(v) { return !!v; });
    let llmRequests = (_data && _data.llmRequests) ? _data.llmRequests : [];
    let summarizations = (_data && _data.summarizations) ? _data.summarizations : [];
    return {
        llmRequests: llmRequests.filter(function(r) { return ids.indexOf(r.requestId) !== -1; }),
        summarizations: summarizations.filter(function(s) { return info.sessionId && s.sessionId === info.sessionId; })
    };
}

function infoRow(label, value, key) {
    return m("div", { class: "flex gap-2", "data-session-field": key }, [
        m("span", { class: "text-gray-500 dark:text-gray-400 w-28 shrink-0" }, label),
        m("span", { class: "truncate", title: value || "" }, value || "—")
    ]);
}

function toggleRow(on, label, onTitle, offTitle, key, onToggle) {
    return m("div", { class: "flex items-center gap-2" }, [
        m("button", {
            class: "toggle-field mt-0" + (on ? " active" : ""),
            title: on ? onTitle : offTitle,
            "data-toggle": key,
            onclick: onToggle
        }, m("span", { class: "toggle-knob" })),
        m("span", label)
    ]);
}

function iconButton(icon, title, onclick, cls, attrs) {
    return m("button", Object.assign({
        class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
        title: title,
        onclick: onclick
    }, attrs || {}), m("span", { class: "material-symbols-outlined " + (cls || ""), style: "font-size: 16px;" }, icon));
}

function contextSummaryControls(info) {
    let refs = (info.contextRefs || []).filter(function(r) { return (r.refSchema || r.schema) !== "data.tag"; });
    if (!refs.length && !info.summarizing) return null;
    return [
        m("div", { class: SECTION + " mt-3 flex items-center gap-2" }, [
            "Context summaries",
            info.summarizing && info.onCancelSummarize
                ? iconButton("stop_circle", "Stop all summarizations", function() { info.onCancelSummarize(null); }, "text-red-600 dark:text-red-400")
                : null
        ]),
        refs.map(function(ref) {
            let busy = ref.summarizing === true;
            return m("div", { class: "flex items-center gap-2 py-0.5", "data-summary-ref": ref.objectId }, [
                m("span", { class: "flex-1 truncate", title: ref.name || ref.objectId }, ref.name || ref.objectId),
                m("span", { class: busy ? "text-yellow-600 dark:text-yellow-400 italic" : "text-gray-500" },
                    busy ? summarizeProgressText(ref) : (ref.summaryPhase ? summarizeProgressText(ref) : "")),
                busy && info.onCancelSummarize ? iconButton("stop_circle", "Stop summarization", function() { info.onCancelSummarize(ref.objectId); }, "text-red-600 dark:text-red-400") : null,
                !busy && info.onRetrySummarize ? iconButton("refresh", "Re-summarize", function() { info.onRetrySummarize(ref.objectId); }, "text-blue-600 dark:text-blue-400") : null
            ]);
        })
    ];
}

function sessionView(vnode) {
    let info = vnode.attrs.info || {};
    let rows = sessionRows(info);
    let promptName = info.promptConfigName || info.promptTemplateName;
    let hasControls = !!(info.onToggleThoughts || info.onToggleMcp);

    return m("div", { class: "text-xs text-gray-700 dark:text-gray-300", "data-llm-session": info.sessionId || "" }, [
        m("div", { class: "space-y-0.5" }, [
            infoRow("Session", info.name, "name"),
            infoRow("Model", info.model, "model"),
            infoRow("Rating", info.rating, "rating"),
            infoRow("Chat config", info.chatConfigName, "chatConfig"),
            infoRow(info.promptConfigName ? "Prompt config" : "Prompt template", promptName, "prompt"),
            infoRow("Context type", info.contextType, "contextType")
        ]),
        hasControls ? m("div", { class: "mt-3 space-y-1" }, [
            info.onToggleThoughts ? toggleRow(!info.hideThoughts, "Show thoughts", "Hide thoughts", "Show thoughts", "thoughts", info.onToggleThoughts) : null,
            info.onToggleMcp ? toggleRow(!!info.showMcp, "Show MCP context in chat", "Hide MCP context in chat", "Show MCP context in chat", "mcp", info.onToggleMcp) : null
        ]) : null,
        contextSummaryControls(info),
        info.onExtractMemories && info.canExtract ? m("div", { class: "mt-3 flex items-center gap-2" }, [
            m("button", {
                class: "flex items-center gap-1 px-2 py-0.5 rounded border dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-50",
                title: "Extract memories from conversation",
                disabled: !!info.extracting,
                onclick: info.onExtractMemories
            }, [
                m("span", { class: "material-symbols-outlined" + (info.extracting ? " animate-spin" : ""), style: "font-size: 14px;" }, info.extracting ? "progress_activity" : "auto_awesome"),
                info.extracting ? "Extracting…" : "Extract memories"
            ])
        ]) : null,
        m("div", { class: SECTION + " mt-3" }, "Server activity for this chat"),
        (rows.llmRequests.length === 0 && rows.summarizations.length === 0)
            ? m("div", { class: "text-gray-500", "data-session-idle": "1" }, !_loaded ? "Loading…" : (_data ? "No server activity for this chat" : "LLM status unavailable"))
            : [
                rows.llmRequests.length > 0 ? m("table", { class: "w-full mb-2" }, [
                    m("thead", m("tr", { class: HEAD }, [
                        m("th", { class: "px-2 py-0.5 text-left" }, "ID"),
                        m("th", { class: "px-2 py-0.5 text-left" }, "Model"),
                        m("th", { class: "px-2 py-0.5 text-right" }, "Tokens"),
                        m("th", { class: "px-2 py-0.5 text-left" }, "Type"),
                        m("th", { class: "px-2 py-0.5 text-left" }, "Status")
                    ])),
                    m("tbody", rows.llmRequests.map(llmRequestRow))
                ]) : null,
                rows.summarizations.length > 0 ? m("table", { class: "w-full" }, [
                    m("thead", m("tr", { class: HEAD }, [
                        m("th", { class: "px-2 py-0.5 text-left" }, "Object"),
                        m("th", { class: "px-2 py-0.5 text-left" }, "Session"),
                        m("th", { class: "px-2 py-0.5 text-left" }, "Phase"),
                        m("th", { class: "px-2 py-0.5 text-left" }, "")
                    ])),
                    m("tbody", rows.summarizations.map(summRow))
                ]) : null
            ]
    ]);
}

const LLMDebugPanel = {
    fetchActive: fetchActive,
    abortAll: abortAll,
    setLastChatSession: setLastChatSession,
    getLastChatSession: getLastChatSession,
    InlineView: {
        oninit: function() { startPoller(); },
        onremove: function() { stopPoller(); },
        view: inlineView
    },
    /**
     * One chat's LLM details: attrs.info = { sessionId (chatRequest objectId), requestId (session objectId),
     * name, model, rating, chatConfigName, promptConfigName, promptTemplateName, contextType,
     * hideThoughts, onToggleThoughts, showMcp, onToggleMcp, summarizing, contextRefs,
     * onCancelSummarize(objectId|null), onRetrySummarize(objectId), canExtract, extracting, onExtractMemories }.
     * Controls render only when their callback is present (the App Panel passes a callback-free snapshot).
     */
    SessionView: {
        oninit: function() { startPoller(); },
        onremove: function() { stopPoller(); },
        view: sessionView
    }
};

export { LLMDebugPanel, setLastChatSession };
export default LLMDebugPanel;
