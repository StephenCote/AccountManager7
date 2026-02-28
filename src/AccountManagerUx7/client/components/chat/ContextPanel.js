/**
 * ContextPanel — Session context bindings display and management
 * Phase 10c: Shows chatConfig, promptConfig, characters, and generic context
 * objects associated with the current chat session. Supports attach/detach
 * via REST endpoints and drag-and-drop from dnd.workingSet.
 *
 * Phase 15: Multi-ref contextRefs support — multiple context objects and tags
 * are persisted to chatRequest.contextRefs for automatic RAG injection.
 *
 * Resolves: OI-49 (generic object association API)
 *
 * Depends on: LLMConnector, am7client, am7model, page
 * Exposes: window.ContextPanel
 */
(function() {
    "use strict";

    let _contextData = null;
    let _sessionId = null;
    let _loading = false;
    let _expanded = false;
    let _onContextChange = null;
    let _summarizePoller = null;

    /**
     * Load context bindings from server for a session.
     * @param {string} sessionId - objectId of the chatRequest
     */
    async function loadContext(sessionId) {
        if (!sessionId || _loading) return;
        _loading = true;
        _sessionId = sessionId;
        try {
            let resp = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/chat/context/" + sessionId,
                withCredentials: true,
                extract: function(xhr) {
                    if (xhr.status !== 200 || !xhr.responseText) return {};
                    try { return JSON.parse(xhr.responseText); }
                    catch(e) { return {}; }
                }
            });
            _contextData = resp && Object.keys(resp).length > 0 ? resp : null;

            /// If any contextRef is still being summarized, poll until done
            if (_contextData && _contextData.summarizing) {
                startSummarizePoller();
            } else {
                stopSummarizePoller();
            }
        } catch (e) {
            console.warn("[ContextPanel] Failed to load context:", e);
            _contextData = null;
        }
        _loading = false;
        m.redraw();
    }

    /**
     * Attach an object to the current session.
     * @param {string} attachType - chatConfig, promptConfig, systemCharacter, userCharacter, context, tag
     * @param {string} objectId - objectId of the object to attach
     * @param {string} [objectType] - required when attachType is 'context'
     */
    async function attach(attachType, objectId, objectType) {
        if (!_sessionId) return;
        try {
            let body = {
                sessionId: _sessionId,
                attachType: attachType,
                objectId: objectId
            };
            if (objectType) body.objectType = objectType;

            let result = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/context/attach",
                withCredentials: true,
                body: body
            });
            await loadContext(_sessionId);
            /// If server kicked off async summarization, start polling immediately
            if (result && result.summarizing) {
                startSummarizePoller();
            }
            if (_onContextChange) _onContextChange(_contextData);
        } catch (e) {
            let errMsg = (e && typeof e === 'object') ? JSON.stringify(e) : String(e);
            console.error("[ContextPanel] attach failed:", errMsg, e);
        }
    }

    /**
     * Detach an object from the current session.
     * @param {string} detachType - systemCharacter, userCharacter, context, tag
     * @param {string} [objectId] - specific objectId to remove from contextRefs
     */
    async function detach(detachType, objectId) {
        if (!_sessionId) return;
        try {
            let body = {
                sessionId: _sessionId,
                detachType: detachType
            };
            if (objectId) body.objectId = objectId;

            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/context/detach",
                withCredentials: true,
                body: body
            });
            await loadContext(_sessionId);
            if (_onContextChange) _onContextChange(_contextData);
        } catch (e) {
            let errMsg = (e && typeof e === 'object') ? JSON.stringify(e) : String(e);
            console.error("[ContextPanel] detach failed:", errMsg, e);
        }
    }

    function startSummarizePoller() {
        if (_summarizePoller) return;
        _summarizePoller = setInterval(function() {
            if (_sessionId) loadContext(_sessionId);
        }, 3000);
    }

    function stopSummarizePoller() {
        if (_summarizePoller) {
            clearInterval(_summarizePoller);
            _summarizePoller = null;
        }
    }

    /**
     * Handle drop from dnd.workingSet — attach dropped objects as context.
     */
    function handleDrop(e) {
        e.preventDefault();
        if (!_sessionId) return;

        let dnd = page.components.dnd;
        if (!dnd || !dnd.workingSet || dnd.workingSet.length === 0) return;

        let item = dnd.workingSet[0];
        let schema = item.schema || item[am7model.jsonModelKey];
        let oid = item.objectId;

        if (!schema || !oid) return;

        if (schema === "olio.llm.chatConfig") {
            attach("chatConfig", oid);
        } else if (schema === "olio.llm.promptConfig") {
            attach("promptConfig", oid);
        } else if (schema === "olio.charPerson") {
            attach("systemCharacter", oid);
        } else if (schema === "data.tag") {
            attach("tag", oid);
        } else {
            attach("context", oid, schema);
        }
    }

    // ── Mithril Views ──────────────────────────────────────────────────

    function getBindingCount() {
        if (!_contextData) return 0;
        let count = 0;
        if (_contextData.chatConfig) count++;
        if (_contextData.promptConfig) count++;
        if (_contextData.systemCharacter) count++;
        if (_contextData.userCharacter) count++;
        /// Count contextRefs (replaces single context count)
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
        if (schema.indexOf("chatRequest") !== -1) return "chat";
        if (schema.indexOf("data.data") !== -1 || schema.indexOf("data.") === 0) return "description";
        return "link";
    }

    function refSchemaLabel(schema) {
        if (!schema) return "Object";
        if (schema === "data.tag") return "Tag";
        if (schema.indexOf("charPerson") !== -1) return "Character";
        if (schema.indexOf("chatRequest") !== -1) return "Chat Session";
        if (schema.indexOf("data.data") !== -1) return "Document";
        // Shorten schema for display: "olio.narrative" -> "Narrative"
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
            m("span", { class: "flex items-center text-gray-400 truncate flex-1 min-w-0", title: data.name || data.objectId || "" }, [
                m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 14px;" }, schemaIcon(label)),
                label + ": " + (data.name || data.objectId || "\u2014")
            ]),
            detachType ? m("button", {
                class: "menu-button ml-1 flex-shrink-0",
                title: "Detach " + label,
                onclick: function(e) {
                    e.stopPropagation();
                    detach(detachType, detachObjectId);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, "link_off")) : ""
        ]);
    }

    function contextRefRowView(ref) {
        if (!ref) return "";
        let icon = refSchemaIcon(ref.schema);
        let label = refSchemaLabel(ref.schema);
        let displayName = ref.name || ref.objectId || "\u2014";
        let detachType = ref.schema === "data.tag" ? "tag" : "context";
        let isSummarizing = ref.summarizing === true;

        return m("div", { class: "flex items-center justify-between text-xs py-0.5" }, [
            m("span", { class: "flex items-center truncate flex-1 min-w-0" + (isSummarizing ? " text-yellow-400" : " text-gray-400"), title: ref.schema + " " + displayName }, [
                isSummarizing
                    ? m("span", { class: "material-symbols-outlined mr-1 animate-spin", style: "font-size: 14px;" }, "progress_activity")
                    : m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 14px;" }, icon),
                label + ": " + displayName,
                isSummarizing ? m("span", { class: "ml-1 text-yellow-500 italic" }, "summarizing...") : ""
            ]),
            m("button", {
                class: "menu-button ml-1 flex-shrink-0",
                title: "Detach " + label,
                onclick: function(e) {
                    e.stopPropagation();
                    detach(detachType, ref.objectId);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, "link_off"))
        ]);
    }

    function panelContentView() {
        if (_loading && !_contextData) {
            return m("div", { class: "text-xs text-gray-500 px-2 py-1" }, "Loading...");
        }
        if (!_contextData) {
            return m("div", { class: "text-xs text-gray-500 px-2 py-1" }, "No session selected");
        }

        let rows = [];

        if (_contextData.chatConfig) {
            let cc = _contextData.chatConfig;
            rows.push(contextRowView("Config", cc, null));
            /// schema field carries the LLM model name for chatConfig refs
            let modelName = cc.model || cc.schema;
            if (modelName) {
                rows.push(m("div", { class: "text-xs text-gray-500 pl-3" }, "Model: " + modelName));
            }
        }
        if (_contextData.promptConfig) {
            rows.push(contextRowView("Prompt", _contextData.promptConfig, null));
        }
        if (_contextData.systemCharacter) {
            rows.push(contextRowView("System Char", _contextData.systemCharacter, "systemCharacter"));
        }
        if (_contextData.userCharacter) {
            rows.push(contextRowView("User Char", _contextData.userCharacter, "userCharacter"));
        }

        /// Display persisted contextRefs (multi-ref: documents, tags, objects)
        if (_contextData.contextRefs && _contextData.contextRefs.length > 0) {
            for (let i = 0; i < _contextData.contextRefs.length; i++) {
                rows.push(contextRefRowView(_contextData.contextRefs[i]));
            }
        } else if (_contextData.context) {
            /// Fallback: display legacy single context
            let ctx = _contextData.context;
            let label = (ctx.type || ctx.schema || "object");
            rows.push(contextRowView("Context (" + label + ")", ctx, "context"));
        }

        if (rows.length === 0) {
            rows.push(m("div", { class: "text-xs text-gray-500" }, "No bindings"));
        }

        return m("div", { class: "px-2 py-1 space-y-0.5" }, rows);
    }

    // ── Public API ──────────────────────────────────────────────────────

    let ContextPanel = {

        /**
         * Load or refresh context for a session.
         * @param {string} sessionId
         */
        load: function(sessionId) {
            if (sessionId !== _sessionId || !_contextData) {
                loadContext(sessionId);
            }
        },

        /**
         * Force refresh current context.
         */
        refresh: function() {
            if (_sessionId) loadContext(_sessionId);
        },

        /**
         * Get current context data.
         */
        getData: function() { return _contextData; },

        /**
         * Get current session ID.
         */
        getSessionId: function() { return _sessionId; },

        /**
         * Set callback for context changes.
         * @param {Function} fn - receives updated context data
         */
        onContextChange: function(fn) { _onContextChange = fn; },

        /**
         * Attach an object to the current session.
         */
        attach: attach,

        /**
         * Detach an object from the current session.
         */
        detach: detach,

        /**
         * Toggle panel expanded state.
         */
        toggle: function() { _expanded = !_expanded; },

        /**
         * Check if panel is expanded.
         */
        isExpanded: function() { return _expanded; },

        /**
         * Clear cached context (e.g. when switching sessions).
         */
        clear: function() {
            _contextData = null;
            _sessionId = null;
            stopSummarizePoller();
        },

        /**
         * Mithril component: collapsible context panel.
         * Usage: m(ContextPanel.PanelView)
         */
        PanelView: {
            view: function() {
                let count = getBindingCount();
                let label = "Context" + (count > 0 ? " (" + count + ")" : "");
                return m("div", {
                    class: "border-t border-gray-600",
                    ondragover: function(e) { e.preventDefault(); },
                    ondrop: handleDrop
                }, [
                    m("button", {
                        class: "flyout-button text-xs w-full flex items-center justify-between px-2 py-1",
                        onclick: function() { _expanded = !_expanded; }
                    }, [
                        m("span", { class: "flex items-center" }, [
                            m("span", { class: "material-symbols-outlined material-icons-24 mr-1" }, "link"),
                            label
                        ]),
                        m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" },
                            _expanded ? "expand_less" : "expand_more"
                        )
                    ]),
                    _expanded ? panelContentView() : ""
                ]);
            }
        }
    };

    window.ContextPanel = ContextPanel;
    console.log("[ContextPanel] loaded");
}());
