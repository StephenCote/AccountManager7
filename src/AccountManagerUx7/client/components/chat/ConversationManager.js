/**
 * ConversationManager — Session list sidebar for chat view
 * Phase 10b: Replaces inline session list in view/chat.js with a reusable component.
 *
 * Provides:
 *   - Session list with search/filter
 *   - Session selection, deletion, creation
 *   - Metadata panel for selected session
 *
 * Depends on: LLMConnector, am7client, am7model, am7view, page
 * Exposes: window.ConversationManager
 */
(function() {
    "use strict";

    let sessions = null;
    let promptConfigs = null;
    let chatConfigs = null;
    let filterText = "";
    let selectedObjectId = null;
    let metadataExpanded = false;
    let loading = false;

    let onSelectCallback = null;
    let onDeleteCallback = null;

    /**
     * Load session list and config lists from server.
     * Caches results; call refresh() to force reload.
     */
    async function loadSessions() {
        if (loading) return;
        loading = true;
        try {
            await am7client.clearCache(undefined, true);
            let chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
            let reqDir = await page.findObject("auth.group", "DATA", "~/ChatRequests");

            if (promptConfigs == null && chatDir) {
                promptConfigs = await am7client.list("olio.llm.promptConfig", chatDir.objectId, null, 0, 0) || [];
            }
            if (chatConfigs == null && chatDir) {
                chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 0) || [];
            }
            if (reqDir) {
                sessions = await am7client.list("olio.llm.chatRequest", reqDir.objectId, null, 0, 0) || [];
            } else {
                sessions = [];
            }
        } catch (e) {
            console.error("[ConversationManager] loadSessions failed:", e);
            sessions = sessions || [];
        }
        loading = false;
        m.redraw();
    }

    /**
     * Force reload of all lists.
     */
    async function refresh() {
        sessions = null;
        promptConfigs = null;
        chatConfigs = null;
        await loadSessions();
    }

    /**
     * Get filtered session list.
     */
    function getFilteredSessions() {
        if (!sessions) return [];
        if (!filterText) return sessions;
        let lower = filterText.toLowerCase();
        return sessions.filter(function(s) {
            let name = (s.name || "").toLowerCase();
            let title = (window.am7client && am7client.getAttributeValue
                ? (am7client.getAttributeValue(s, "chatTitle", 0) || "") : "").toLowerCase();
            return name.indexOf(lower) !== -1 || title.indexOf(lower) !== -1;
        });
    }

    /**
     * Select a session by object reference.
     */
    function selectSession(session) {
        selectedObjectId = session ? session.objectId : null;
        if (onSelectCallback) {
            onSelectCallback(session);
        }
    }

    /**
     * Delete a session via LLMConnector.
     */
    function deleteSession(session) {
        if (!session) return;
        LLMConnector.deleteSession(session, false, async function() {
            if (selectedObjectId === session.objectId) {
                selectedObjectId = null;
            }
            if (onDeleteCallback) {
                onDeleteCallback(session);
            }
            await refresh();
        });
    }

    /**
     * Get metadata for currently selected session.
     */
    function getSelectedMetadata() {
        if (!selectedObjectId || !sessions) return null;
        for (let i = 0; i < sessions.length; i++) {
            if (sessions[i].objectId === selectedObjectId) {
                return sessions[i];
            }
        }
        return null;
    }

    // ── Mithril Views ──────────────────────────────────────────────────

    function searchBarView() {
        return m("div", { class: "px-2 py-1" },
            m("input", {
                type: "text",
                class: "text-field w-full text-sm",
                placeholder: "Filter sessions...",
                value: filterText,
                oninput: function(e) { filterText = e.target.value; }
            })
        );
    }

    // Phase 13g item 29: Display chatTitle/chatIcon attributes when present (OI-80)
    function sessionItemView(session, isSelected) {
        let cls = "flyout-button flex items-center w-full group";
        if (isSelected) cls += " active";

        let title = (window.am7client && am7client.getAttributeValue
            ? am7client.getAttributeValue(session, "chatTitle", 0) : null) || session.name || "(unnamed)";
        let icon = (window.am7client && am7client.getAttributeValue
            ? am7client.getAttributeValue(session, "chatIcon", 0) : null);

        return m("button", {
            key: session.objectId,
            class: cls,
            onclick: function() { selectSession(session); }
        }, [
            m("span", {
                class: "material-symbols-outlined material-icons-24 flex-shrink-0 mr-1 opacity-30 group-hover:opacity-100 transition-opacity",
                title: "Delete session",
                style: "font-size: 18px;",
                onclick: function(e) {
                    e.stopPropagation();
                    deleteSession(session);
                }
            }, "delete_outline"),
            icon ? m("span", {
                class: "material-symbols-outlined flex-shrink-0 mr-1",
                style: "font-size: 16px;"
            }, icon) : "",
            m("span", { class: "flex-1 truncate text-left" }, title)
        ]);
    }

    function sessionListView() {
        let filtered = getFilteredSessions();
        if (loading && !sessions) {
            return m("div", { class: "px-3 py-2 text-gray-400 text-sm" }, "Loading...");
        }
        if (filtered.length === 0) {
            return m("div", { class: "px-3 py-2 text-gray-400 text-sm" },
                sessions && sessions.length > 0 ? "No matches" : "No sessions"
            );
        }
        return filtered.map(function(s) {
            return sessionItemView(s, s.objectId === selectedObjectId);
        });
    }

    // Phase 13g item 28: Object links in chat details (OI-79)
    function objectLinkRow(label, obj, modelType) {
        let name = obj.name || obj.objectId || "—";
        return m("div", { class: "text-xs text-gray-400 flex items-center" }, [
            m("span", label + ": "),
            m("a", {
                class: "text-blue-400 hover:text-blue-300 cursor-pointer truncate ml-1",
                title: "Open " + name,
                onclick: function(e) {
                    e.preventDefault();
                    if (window.page && page.rule) {
                        page.rule.browseObject(modelType, obj.objectId);
                    }
                }
            }, name)
        ]);
    }

    function metaRow(label, value) {
        return m("div", { class: "text-xs text-gray-400" }, label + ": " + value);
    }

    function metadataView() {
        if (!metadataExpanded) return "";
        let meta = getSelectedMetadata();
        if (!meta) {
            return m("div", { class: "px-3 py-2 text-gray-500 text-xs" }, "Select a session to view details");
        }

        let rows = [];
        rows.push(metaRow("Name", meta.name || "—"));

        if (meta.chatConfig) {
            // Resolve full chatConfig from cached list (foreign ref on chatRequest is a stub)
            let cc = meta.chatConfig;
            if (chatConfigs && cc.objectId) {
                let full = chatConfigs.find(function(c) { return c.objectId === cc.objectId; });
                if (full) cc = full;
            }
            rows.push(objectLinkRow("Config", cc, "olio.llm.chatConfig"));
            if (cc.model) rows.push(metaRow("Model", cc.model));
            if (cc.messageTrim != null) rows.push(metaRow("Trim", cc.messageTrim));
            rows.push(metaRow("Stream", cc.stream ? "yes" : "no"));
            rows.push(metaRow("Prune", cc.prune !== false ? "yes" : "no"));
            if (cc.autoTunePrompts) rows.push(metaRow("Auto-Tune Prompts", "yes"));
            if (cc.autoTuneChatOptions) rows.push(metaRow("Auto-Tune Options", "yes"));
            if (cc.systemCharacter) {
                rows.push(objectLinkRow("System", cc.systemCharacter, "olio.charPerson"));
            }
            if (cc.userCharacter) {
                rows.push(objectLinkRow("User Char", cc.userCharacter, "olio.charPerson"));
            }
        }
        if (meta.promptConfig) {
            rows.push(objectLinkRow("Prompt", meta.promptConfig, "olio.llm.promptConfig"));
        }

        return m("div", { class: "px-3 py-2 border-t border-gray-600 space-y-1" }, rows);
    }

    // ── Public API ──────────────────────────────────────────────────────

    let ConversationManager = {

        /**
         * Set callback for when a session is selected.
         * @param {Function} fn - receives session object
         */
        onSelect: function(fn) { onSelectCallback = fn; },

        /**
         * Set callback for when a session is deleted.
         * @param {Function} fn - receives deleted session object
         */
        onDelete: function(fn) { onDeleteCallback = fn; },

        /**
         * Get the list of loaded prompt configs.
         */
        getPromptConfigs: function() { return promptConfigs; },

        /**
         * Get the list of loaded chat configs.
         */
        getChatConfigs: function() { return chatConfigs; },

        /**
         * Get the current session list.
         */
        getSessions: function() { return sessions; },

        /**
         * Force refresh of all data.
         */
        refresh: refresh,

        /**
         * Load sessions (alias for external callers).
         */
        load: loadSessions,

        /**
         * Clear all cached data (sessions, configs).
         */
        clear: function() {
            sessions = null;
            promptConfigs = null;
            chatConfigs = null;
            selectedObjectId = null;
            filterText = "";
            metadataExpanded = false;
        },

        /**
         * Select a session programmatically (triggers onSelect callback).
         * @param {Object} session - session object with at least objectId
         */
        selectSession: selectSession,

        /**
         * Set the selected session by objectId (for external sync).
         */
        setSelected: function(objectId) { selectedObjectId = objectId; },

        /**
         * Get the currently selected objectId.
         */
        getSelectedId: function() { return selectedObjectId; },

        /**
         * Get the full selected session object (not just objectId).
         */
        getSelectedSession: function() { return getSelectedMetadata(); },

        /**
         * Phase 13: Update session title from WebSocket titleEvent.
         * @param {string} objectId - chatRequest objectId
         * @param {string} title - auto-generated title
         */
        updateSessionTitle: function(objectId, title) {
            if (!sessions || !objectId || !title) return;
            for (let i = 0; i < sessions.length; i++) {
                if (sessions[i].objectId === objectId) {
                    if (!sessions[i].attributes) sessions[i].attributes = [];
                    let found = false;
                    for (let j = 0; j < sessions[i].attributes.length; j++) {
                        if (sessions[i].attributes[j].name === "chatTitle") {
                            sessions[i].attributes[j].values = [title];
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        sessions[i].attributes.push({ name: "chatTitle", values: [title] });
                    }
                    m.redraw();
                    return;
                }
            }
        },

        /**
         * Phase 13g: Update session icon from WebSocket iconEvent.
         * @param {string} objectId - chatRequest objectId
         * @param {string} icon - Material Symbols icon name
         */
        updateSessionIcon: function(objectId, icon) {
            if (!sessions || !objectId || !icon) return;
            for (let i = 0; i < sessions.length; i++) {
                if (sessions[i].objectId === objectId) {
                    if (!sessions[i].attributes) sessions[i].attributes = [];
                    let found = false;
                    for (let j = 0; j < sessions[i].attributes.length; j++) {
                        if (sessions[i].attributes[j].name === "chatIcon") {
                            sessions[i].attributes[j].values = [icon];
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        sessions[i].attributes.push({ name: "chatIcon", values: [icon] });
                    }
                    m.redraw();
                    return;
                }
            }
        },

        /**
         * Auto-select first session if none selected.
         * Returns the selected session or null.
         */
        autoSelectFirst: function() {
            if (selectedObjectId) return getSelectedMetadata();
            if (sessions && sessions.length > 0) {
                selectSession(sessions[0]);
                return sessions[0];
            }
            return null;
        },

        /**
         * Mithril component: sidebar view.
         * Usage: m(ConversationManager.SidebarView, { onNew: openChatSettings })
         */
        SidebarView: {
            oninit: function() {
                if (!sessions) loadSessions();
            },
            view: function(vnode) {
                let onNew = vnode.attrs.onNew;
                return m("div", { class: "flex flex-col flex-1 overflow-hidden" }, [
                    searchBarView(),
                    onNew ? m("div", { class: "px-2 py-1 border-b border-gray-600" }, [
                        m("button", {
                            class: "flyout-button text-xs w-full",
                            onclick: function() { onNew(); }
                        }, [
                            m("span", { class: "material-symbols-outlined material-icons-24" }, "add"),
                            " New"
                        ])
                    ]) : "",
                    m("div", { class: "overflow-y-auto flex-1" }, sessionListView()),
                    m("div", { class: "border-t border-gray-600" }, [
                        m("div", { class: "px-2 py-1" }, [
                            m("button", {
                                class: "flyout-button text-xs",
                                title: "Toggle details",
                                onclick: function() { metadataExpanded = !metadataExpanded; }
                            }, [
                                m("span", { class: "material-symbols-outlined material-icons-24" }, "info"),
                                " Details"
                            ])
                        ]),
                        metadataView()
                    ])
                ]);
            }
        }
    };

    window.ConversationManager = ConversationManager;
    console.log("[ConversationManager] loaded");
}());
