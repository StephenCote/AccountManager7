/**
 * ContextPanel — Session context bindings display and management (ESM port)
 * Shows chatConfig, promptConfig, characters, and context objects for current session.
 * Supports attach/detach via REST and drag-and-drop.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7model } from '../core/model.js';
import { applicationPath } from '../core/config.js';

let _contextData = null;
let _sessionId = null;
let _loading = false;
let _expanded = false;
let _onContextChange = null;
let _summarizePoller = null;
let _summarizePollCount = 0;
let _maxSummarizePollCount = 60;

async function loadContext(sessionId) {
    if (!sessionId || _loading) return;
    _loading = true;
    _sessionId = sessionId;
    try {
        let resp = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/chat/context/" + sessionId,
            withCredentials: true,
            extract: function(xhr) {
                if (xhr.status !== 200 || !xhr.responseText) return {};
                try { return JSON.parse(xhr.responseText); } catch(e) { return {}; }
            }
        });
        _contextData = resp && Object.keys(resp).length > 0 ? resp : null;
        if (_contextData && _contextData.summarizing) {
            startSummarizePoller();
        } else {
            stopSummarizePoller();
        }
        if (_onContextChange) _onContextChange(_contextData);
    } catch (e) {
        console.warn("[ContextPanel] Failed to load context:", e);
        _contextData = null;
    }
    _loading = false;
    m.redraw();
}

async function attach(attachType, objectId, objectType) {
    if (!_sessionId) return;
    try {
        let body = { sessionId: _sessionId, attachType, objectId };
        if (objectType) body.objectType = objectType;
        let result = await m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/context/attach",
            withCredentials: true,
            body
        });
        await loadContext(_sessionId);
        if (result && result.summarizing) startSummarizePoller();
        if (_onContextChange) _onContextChange(_contextData);
    } catch (e) {
        console.error("[ContextPanel] attach failed:", e);
    }
}

async function detach(detachType, objectId) {
    if (!_sessionId) return;
    try {
        let body = { sessionId: _sessionId, detachType };
        if (objectId) body.objectId = objectId;
        await m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/context/detach",
            withCredentials: true,
            body
        });
        await loadContext(_sessionId);
        if (_onContextChange) _onContextChange(_contextData);
    } catch (e) {
        console.error("[ContextPanel] detach failed:", e);
    }
}

function startSummarizePoller() {
    if (_summarizePoller) return;
    _summarizePollCount = 0;
    _summarizePoller = setInterval(function() {
        _summarizePollCount++;
        if (_summarizePollCount >= _maxSummarizePollCount) {
            stopSummarizePoller();
            return;
        }
        if (_sessionId) loadContext(_sessionId);
    }, 3000);
}

function stopSummarizePoller() {
    if (_summarizePoller) {
        clearInterval(_summarizePoller);
        _summarizePoller = null;
        _summarizePollCount = 0;
    }
}

function handleDrop(e) {
    e.preventDefault();
    if (!_sessionId) return;
    let dnd = page.components.dnd;
    if (!dnd || !dnd.workingSet || dnd.workingSet.length === 0) return;
    let item = dnd.workingSet[0];
    let schema = item.schema || item[am7model.jsonModelKey];
    let oid = item.objectId;
    if (!schema || !oid) return;

    if (schema === "olio.llm.chatConfig") attach("chatConfig", oid);
    else if (schema === "olio.llm.promptConfig") attach("promptConfig", oid);
    else if (schema === "olio.charPerson") attach("systemCharacter", oid);
    else if (schema === "data.tag") attach("tag", oid);
    else attach("context", oid, schema);
}

function getBindingCount() {
    if (!_contextData) return 0;
    let count = 0;
    if (_contextData.chatConfig) count++;
    if (_contextData.promptConfig) count++;
    if (_contextData.systemCharacter) count++;
    if (_contextData.userCharacter) count++;
    if (_contextData.contextRefs && _contextData.contextRefs.length > 0) {
        count += _contextData.contextRefs.length;
    } else if (_contextData.context) {
        count++;
    }
    return count;
}

function refSchemaIcon(schema) {
    if (!schema) return "link";
    if (schema === "data.tag") return "label";
    if (schema.indexOf("charPerson") !== -1) return "person";
    if (schema.indexOf("data.") === 0) return "description";
    return "link";
}

function refSchemaLabel(schema) {
    if (!schema) return "Object";
    if (schema === "data.tag") return "Tag";
    if (schema.indexOf("charPerson") !== -1) return "Character";
    if (schema.indexOf("data.data") !== -1) return "Document";
    let parts = schema.split(".");
    return parts[parts.length - 1].charAt(0).toUpperCase() + parts[parts.length - 1].slice(1);
}

function schemaIcon(label) {
    if (label === "Config") return "settings";
    if (label === "Prompt") return "description";
    if (label.indexOf("Char") !== -1) return "person";
    return "link";
}

function contextRowView(label, data, detachType, detachObjectId) {
    if (!data) return "";
    return m("div", { class: "flex items-center justify-between text-xs py-0.5" }, [
        m("span", { class: "flex items-center text-gray-400 truncate flex-1 min-w-0", title: data.name || "" }, [
            m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 14px;" }, schemaIcon(label)),
            label + ": " + (data.name || data.objectId || "\u2014")
        ]),
        detachType ? m("button", {
            class: "ml-1 flex-shrink-0 p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600",
            title: "Detach " + label,
            onclick: e => { e.stopPropagation(); detach(detachType, detachObjectId); }
        }, m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, "link_off")) : ""
    ]);
}

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

function cancelSummarize(objectId) {
    if (!_sessionId) return;
    m.request({
        method: 'POST',
        url: applicationPath + "/rest/chat/summarize/cancel",
        withCredentials: true,
        body: { sessionId: _sessionId, objectId }
    }).then(() => { if (_sessionId) loadContext(_sessionId); })
      .catch(e => console.warn("[ContextPanel] cancel summarize failed:", e));
}

function retrySummarize(objectId) {
    if (!_sessionId) return;
    m.request({
        method: 'POST',
        url: applicationPath + "/rest/chat/summarize/retry",
        withCredentials: true,
        body: { sessionId: _sessionId, objectId }
    }).then(result => {
        if (result && result.started) startSummarizePoller();
        if (_sessionId) loadContext(_sessionId);
    }).catch(e => console.warn("[ContextPanel] retry summarize failed:", e));
}

function contextRefRowView(ref) {
    if (!ref) return "";
    let rSchema = ref.refSchema || ref.schema;
    let icon = refSchemaIcon(rSchema);
    let label = refSchemaLabel(rSchema);
    let displayName = ref.name || ref.objectId || "\u2014";
    let detachType = rSchema === "data.tag" ? "tag" : "context";
    let isSummarizing = ref.summarizing === true;

    return m("div", { class: "flex items-center justify-between text-xs py-0.5" }, [
        m("span", { class: "flex items-center truncate flex-1 min-w-0" + (isSummarizing ? " text-yellow-400" : " text-gray-400") }, [
            isSummarizing
                ? m("span", { class: "material-symbols-outlined mr-1 animate-spin", style: "font-size: 14px;" }, "progress_activity")
                : m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 14px;" }, icon),
            label + ": " + displayName,
            isSummarizing ? m("span", { class: "ml-1 text-yellow-500 italic" }, summarizeProgressText(ref)) : ""
        ]),
        m("span", { class: "flex items-center flex-shrink-0" }, [
            isSummarizing ? m("button", {
                class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600 ml-1",
                title: "Stop summarization",
                onclick: e => { e.stopPropagation(); cancelSummarize(ref.objectId); }
            }, m("span", { class: "material-symbols-outlined text-red-400", style: "font-size: 16px;" }, "stop_circle")) : "",
            !isSummarizing && rSchema !== "data.tag" ? m("button", {
                class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600 ml-1",
                title: "Re-summarize",
                onclick: e => { e.stopPropagation(); retrySummarize(ref.objectId); }
            }, m("span", { class: "material-symbols-outlined text-blue-400", style: "font-size: 16px;" }, "refresh")) : "",
            m("button", {
                class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600 ml-1",
                title: "Detach " + label,
                onclick: e => { e.stopPropagation(); detach(detachType, ref.objectId); }
            }, m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, "link_off"))
        ])
    ]);
}

function panelContentView() {
    if (_loading && !_contextData) return m("div", { class: "text-xs text-gray-500 px-2 py-1" }, "Loading...");
    if (!_contextData) return m("div", { class: "text-xs text-gray-500 px-2 py-1" }, "No session selected");

    let rows = [];

    if (_contextData.summarizing) {
        let bannerText = "Creating summary for attached document(s)...";
        if (_contextData.contextRefs) {
            let active = _contextData.contextRefs.filter(r => r.summarizing);
            if (active.length > 0) bannerText = active.map(r => summarizeProgressText(r)).join(" | ");
        }
        rows.push(m("div", { class: "flex items-center gap-1 px-2 py-1.5 mb-1 rounded bg-yellow-900/40 border border-yellow-700/50 text-yellow-300 text-xs" }, [
            m("span", { class: "material-symbols-outlined animate-spin", style: "font-size: 16px;" }, "progress_activity"),
            m("span.flex-1", bannerText),
            m("button", {
                class: "ml-1 flex-shrink-0 p-0.5 rounded hover:bg-gray-600",
                title: "Stop all summarizations",
                onclick: e => { e.stopPropagation(); cancelSummarize(null); }
            }, m("span", { class: "material-symbols-outlined text-red-400", style: "font-size: 16px;" }, "stop_circle"))
        ]));
    }

    if (_contextData.chatConfig) {
        rows.push(contextRowView("Config", _contextData.chatConfig, null));
        let modelName = _contextData.chatConfig.model || _contextData.chatConfig.refSchema;
        if (modelName) rows.push(m("div", { class: "text-xs text-gray-500 pl-3" }, "Model: " + modelName));
    }
    if (_contextData.promptConfig) rows.push(contextRowView("Prompt", _contextData.promptConfig, null));
    if (_contextData.systemCharacter) rows.push(contextRowView("System Char", _contextData.systemCharacter, "systemCharacter"));
    if (_contextData.userCharacter) rows.push(contextRowView("User Char", _contextData.userCharacter, "userCharacter"));

    if (_contextData.contextRefs && _contextData.contextRefs.length > 0) {
        _contextData.contextRefs.forEach(ref => rows.push(contextRefRowView(ref)));
    } else if (_contextData.context) {
        let ctx = _contextData.context;
        rows.push(contextRowView("Context (" + (ctx.type || ctx.refSchema || "object") + ")", ctx, "context"));
    }

    if (rows.length === 0) rows.push(m("div", { class: "text-xs text-gray-500" }, "No bindings"));

    return m("div", { class: "px-2 py-1 space-y-0.5" }, rows);
}

const ContextPanel = {
    load: sessionId => { if (sessionId !== _sessionId || !_contextData) loadContext(sessionId); },
    refresh: () => { if (_sessionId) loadContext(_sessionId); },
    getData: () => _contextData,
    getSessionId: () => _sessionId,
    onContextChange: fn => { _onContextChange = fn; },
    attach,
    detach,
    toggle: () => { _expanded = !_expanded; },
    isExpanded: () => _expanded,
    clear: () => { _contextData = null; _sessionId = null; stopSummarizePoller(); },

    PanelView: {
        view: function() {
            let count = getBindingCount();
            let label = "Context" + (count > 0 ? " (" + count + ")" : "");
            let isSummarizing = _contextData && _contextData.summarizing;
            return m("div", {
                class: "border-t border-gray-200 dark:border-gray-600",
                ondragover: e => e.preventDefault(),
                ondrop: handleDrop
            }, [
                m("button", {
                    class: "w-full text-xs flex items-center justify-between px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700",
                    onclick: () => { _expanded = !_expanded; }
                }, [
                    m("span", { class: "flex items-center" }, [
                        isSummarizing
                            ? m("span", { class: "material-symbols-outlined animate-spin mr-1 text-yellow-400", style: "font-size: 18px;" }, "progress_activity")
                            : m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 18px;" }, "link"),
                        isSummarizing ? m("span.text-yellow-400", "Summarizing...") : label
                    ]),
                    m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, _expanded ? "expand_less" : "expand_more")
                ]),
                _expanded ? panelContentView() : ""
            ]);
        }
    }
};

export { ContextPanel };
export default ContextPanel;
