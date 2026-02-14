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
            return name.indexOf(lower) !== -1;
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

    function sessionItemView(session, isSelected) {
        let cls = "flyout-button flex justify-between items-center w-full";
        if (isSelected) cls += " active";

        return m("div", { key: session.objectId, class: "flex items-center" }, [
            m("button", {
                class: "menu-button content-end mr-1",
                title: "Delete session",
                onclick: function(e) {
                    e.stopPropagation();
                    deleteSession(session);
                }
            }, m("span", { class: "material-symbols-outlined material-icons-24" }, "delete_outline")),
            m("button", {
                class: cls,
                onclick: function() { selectSession(session); }
            }, session.name || "(unnamed)")
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

    function metadataView() {
        if (!metadataExpanded) return "";
        let meta = getSelectedMetadata();
        if (!meta) {
            return m("div", { class: "px-3 py-2 text-gray-500 text-xs" }, "Select a session to view details");
        }

        let rows = [];
        rows.push(m("div", { class: "text-xs text-gray-400" }, "Name: " + (meta.name || "—")));

        if (meta.chatConfig) {
            let cc = meta.chatConfig;
            rows.push(m("div", { class: "text-xs text-gray-400" }, "Config: " + (cc.name || cc.objectId || "—")));
            if (cc.model) rows.push(m("div", { class: "text-xs text-gray-400" }, "Model: " + cc.model));
            if (cc.messageTrim != null) rows.push(m("div", { class: "text-xs text-gray-400" }, "Trim: " + cc.messageTrim));
            rows.push(m("div", { class: "text-xs text-gray-400" }, "Stream: " + (cc.stream ? "yes" : "no")));
            rows.push(m("div", { class: "text-xs text-gray-400" }, "Prune: " + (cc.prune !== false ? "yes" : "no")));
        }
        if (meta.promptConfig) {
            rows.push(m("div", { class: "text-xs text-gray-400" }, "Prompt: " + (meta.promptConfig.name || "—")));
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
         * Set the selected session by objectId (for external sync).
         */
        setSelected: function(objectId) { selectedObjectId = objectId; },

        /**
         * Get the currently selected objectId.
         */
        getSelectedId: function() { return selectedObjectId; },

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
                return m("div", { class: "splitleftcontainer" }, [
                    searchBarView(),
                    m("div", { class: "overflow-y-auto flex-1" }, sessionListView()),
                    m("div", { class: "border-t border-gray-600" }, [
                        m("div", { class: "flex items-center justify-between px-2 py-1" }, [
                            m("button", {
                                class: "flyout-button text-xs",
                                title: "Toggle details",
                                onclick: function() { metadataExpanded = !metadataExpanded; }
                            }, [
                                m("span", { class: "material-symbols-outlined material-icons-24" }, "info"),
                                " Details"
                            ]),
                            onNew ? m("button", {
                                class: "flyout-button text-xs",
                                onclick: function() { onNew(); }
                            }, [
                                m("span", { class: "material-symbols-outlined material-icons-24" }, "add"),
                                " New"
                            ]) : ""
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
