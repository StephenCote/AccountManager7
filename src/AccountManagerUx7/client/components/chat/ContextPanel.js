/**
 * ContextPanel — Session context bindings display and management
 * Phase 10c: Shows chatConfig, promptConfig, characters, and generic context
 * object associated with the current chat session. Supports attach/detach
 * via REST endpoints and drag-and-drop from dnd.workingSet.
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
        } catch (e) {
            console.warn("[ContextPanel] Failed to load context:", e);
            _contextData = null;
        }
        _loading = false;
        m.redraw();
    }

    /**
     * Attach an object to the current session.
     * @param {string} attachType - chatConfig, promptConfig, systemCharacter, userCharacter, context
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

            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/context/attach",
                withCredentials: true,
                body: body
            });
            await loadContext(_sessionId);
            if (_onContextChange) _onContextChange(_contextData);
        } catch (e) {
            console.error("[ContextPanel] attach failed:", e);
        }
    }

    /**
     * Detach an object from the current session.
     * @param {string} detachType - systemCharacter, userCharacter, context
     */
    async function detach(detachType) {
        if (!_sessionId) return;
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/context/detach",
                withCredentials: true,
                body: {
                    sessionId: _sessionId,
                    detachType: detachType
                }
            });
            await loadContext(_sessionId);
            if (_onContextChange) _onContextChange(_contextData);
        } catch (e) {
            console.error("[ContextPanel] detach failed:", e);
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
        } else {
            attach("context", oid, schema);
        }
    }

    // ── Mithril Views ──────────────────────────────────────────────────

    // Phase 12: OI-54 — binding count for collapsed header badge
    function getBindingCount() {
        if (!_contextData) return 0;
        let count = 0;
        if (_contextData.chatConfig) count++;
        if (_contextData.promptConfig) count++;
        if (_contextData.systemCharacter) count++;
        if (_contextData.userCharacter) count++;
        if (_contextData.context) count++;
        return count;
    }

    // Phase 12: OI-55 — schema-type icon mapping
    function schemaIcon(label) {
        if (label === "Config") return "settings";
        if (label === "Prompt") return "description";
        if (label.indexOf("Char") !== -1) return "person";
        return "link";
    }

    function contextRowView(label, data, detachType) {
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
                    detach(detachType);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, "link_off")) : ""
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
            if (cc.model) {
                rows.push(m("div", { class: "text-xs text-gray-500 pl-3" }, "Model: " + cc.model));
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
        if (_contextData.context) {
            let ctx = _contextData.context;
            let label = (ctx.type || "object");
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
