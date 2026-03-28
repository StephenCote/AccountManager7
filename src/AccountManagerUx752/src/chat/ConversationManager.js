/**
 * ConversationManager — Session list sidebar for chat view (ESM port)
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { LLMConnector } from './LLMConnector.js';

let sessions = null;
let promptConfigs = null;
let chatConfigs = null;
let filterText = "";
let selectedObjectId = null;
let metadataExpanded = false;
let loading = false;

let onSelectCallback = null;
let onDeleteCallback = null;
let onNewSessionCallback = null;

async function loadSessions() {
    if (loading) return;
    loading = true;
    try {
        // Do NOT clear cache here — loadSessions is called on initial render,
        // and clearing cache every time causes severe performance degradation.
        // Cache is cleared in refresh() only after create/delete operations.

        // Resolve directories — uses am7client cache (fast on repeat calls).
        // Wrap in Promise.resolve() because am7client returns cached values
        // directly (not promises) on cache hit.
        let [chatDir, reqDir] = await Promise.all([
            Promise.resolve(page.findObject("auth.group", "DATA", "~/Chat")),
            Promise.resolve(page.findObject("auth.group", "DATA", "~/ChatRequests"))
        ]);

        // Load configs and sessions in parallel.
        // Sessions always reload (never rely on local cache after create/delete).
        // Configs only reload when null (first load or after explicit refresh).
        // Wrap in Promise.resolve() because am7client.list() returns either a
        // cached value (not a promise) or a promise, depending on cache state.
        let promises = [];
        if (promptConfigs == null && chatDir) {
            promises.push(Promise.resolve(page.listObjects("olio.llm.promptConfig", chatDir.objectId, null, 0, 0)).then(function(v) { promptConfigs = v || []; }));
        }
        if (chatConfigs == null && chatDir) {
            promises.push(Promise.resolve(page.listObjects("olio.llm.chatConfig", chatDir.objectId, null, 0, 0)).then(function(v) { chatConfigs = v || []; }));
        }
        if (reqDir) {
            promises.push(Promise.resolve(page.listObjects("olio.llm.chatRequest", reqDir.objectId,
                "name,objectId,chatTitle,chatIcon,chatConfig,promptConfig,promptTemplate,session,sessionType,setting,contextType", 0, 0)).then(function(v) { sessions = v || []; }));
        } else {
            sessions = [];
        }
        await Promise.all(promises);
    } catch (e) {
        console.error("[ConversationManager] loadSessions failed:", e);
        sessions = sessions || [];
    }
    loading = false;
    m.redraw();
}

async function refresh() {
    // Clear BOTH local AND server cache for chatRequest so list returns fresh data.
    // bLocalOnly=false triggers GET /cache/clear/olio.llm.chatRequest on server.
    await Promise.resolve(am7client.clearCache("olio.llm.chatRequest", false));
    sessions = null;
    promptConfigs = null;
    chatConfigs = null;
    loading = false;
    await loadSessions();
}

function getFilteredSessions() {
    if (!sessions) return [];
    if (!filterText) return sessions;
    let lower = filterText.toLowerCase();
    return sessions.filter(s => {
        let name = (s.name || "").toLowerCase();
        let title = (s.chatTitle || "").toLowerCase();
        return name.indexOf(lower) !== -1 || title.indexOf(lower) !== -1;
    });
}

function selectSession(session) {
    selectedObjectId = session ? session.objectId : null;
    if (onSelectCallback) onSelectCallback(session);
}

function deleteSession(session) {
    if (!session) return;
    LLMConnector.deleteSession(session, false, async function() {
        if (selectedObjectId === session.objectId) selectedObjectId = null;
        await refresh();
        if (onDeleteCallback) onDeleteCallback(session);
    });
}

function getSelectedMetadata() {
    if (!selectedObjectId || !sessions) return null;
    return sessions.find(s => s.objectId === selectedObjectId) || null;
}

function searchBarView() {
    return m("div", { class: "px-2 py-1" },
        m("input", {
            type: "text",
            class: "text-field w-full text-sm",
            placeholder: "Filter sessions...",
            value: filterText,
            oninput: e => { filterText = e.target.value; }
        })
    );
}

function sessionItemView(session, isSelected) {
    let cls = "flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-700 group";
    if (isSelected) cls += " bg-gray-200 dark:bg-gray-600";

    let autoTitle = session.chatTitle || null;
    let name = session.name || "(unnamed)";
    let icon = session.chatIcon || null;

    let titleContent;
    if (autoTitle) {
        titleContent = m("div", { class: "flex flex-col flex-1 min-w-0" }, [
            m("span", { class: "text-sm truncate text-gray-800 dark:text-gray-200" }, autoTitle),
            m("span", { class: "text-xs truncate text-gray-400" }, name)
        ]);
    } else {
        titleContent = m("div", { class: "flex flex-col flex-1 min-w-0" }, [
            m("span", { class: "text-sm truncate text-gray-800 dark:text-gray-200" }, name)
        ]);
    }

    return m("div", {
        key: session.objectId,
        class: cls,
        onclick: () => selectSession(session)
    }, [
        m("span", {
            class: "material-symbols-outlined flex-shrink-0 opacity-30 group-hover:opacity-100 transition-opacity cursor-pointer",
            title: "Delete session",
            style: "font-size: 16px;",
            onclick: e => { e.stopPropagation(); deleteSession(session); }
        }, "delete_outline"),
        icon ? (icon.match(/^[a-z0-9_]+$/)
            ? m("span", { class: "material-symbols-outlined flex-shrink-0 text-gray-400", style: "font-size:16px" }, icon)
            : m("span", { class: "flex-shrink-0", style: "font-size:14px; line-height:1;" }, icon)
        ) : "",
        titleContent
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
    return filtered.map(s => sessionItemView(s, s.objectId === selectedObjectId));
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

    rows.push(m("div", { class: "text-xs text-gray-400 flex items-center gap-1" }, [
        m("span", "Title: "),
        m("input", {
            type: "text",
            class: "flex-1 text-xs bg-transparent border-b border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-200 px-1",
            value: meta.chatTitle || "",
            placeholder: "(auto-generated)",
            onchange: e => {
                meta.chatTitle = e.target.value;
                if (meta.objectId) ConversationManager.updateSessionTitle(meta.objectId, e.target.value);
            }
        })
    ]));

    rows.push(m("div", { class: "text-xs text-gray-400 flex items-center gap-1" }, [
        m("span", "Icon: "),
        meta.chatIcon ? m("span", { style: "font-size: 14px;" }, meta.chatIcon) : "",
        m("input", {
            type: "text",
            class: "flex-1 text-xs bg-transparent border-b border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-200 px-1",
            value: meta.chatIcon || "",
            placeholder: "(emoji)",
            onchange: e => {
                meta.chatIcon = e.target.value;
                if (meta.objectId) ConversationManager.updateSessionIcon(meta.objectId, e.target.value);
            }
        })
    ]));

    if (meta.chatConfig) {
        let cc = meta.chatConfig;
        if (chatConfigs && cc.objectId) {
            let full = chatConfigs.find(c => c.objectId === cc.objectId);
            if (full) cc = full;
        }
        if (cc.model) rows.push(metaRow("Model", cc.model));
    }
    if (meta.contextType) rows.push(metaRow("Context", meta.contextType));

    return m("div", { class: "px-3 py-2 border-t border-gray-200 dark:border-gray-600 space-y-1" }, rows);
}

const ConversationManager = {
    onSelect: fn => { onSelectCallback = fn; },
    onDelete: fn => { onDeleteCallback = fn; },
    onNewSession: fn => { onNewSessionCallback = fn; },
    getPromptConfigs: () => promptConfigs,
    getChatConfigs: () => chatConfigs,
    getSessions: () => sessions,
    refresh,
    load: loadSessions,

    clear: function() {
        sessions = null;
        promptConfigs = null;
        chatConfigs = null;
        selectedObjectId = null;
        filterText = "";
        metadataExpanded = false;
    },

    selectSession,
    setSelected: oid => { selectedObjectId = oid; },
    getSelectedId: () => selectedObjectId,
    getSelectedSession: () => getSelectedMetadata(),

    updateSessionTitle: function(objectId, title) {
        if (!sessions || !objectId || !title) return;
        let s = sessions.find(s => s.objectId === objectId);
        if (s) { s.chatTitle = title; m.redraw(); }
    },

    updateSessionIcon: function(objectId, icon) {
        if (!sessions || !objectId || !icon) return;
        let s = sessions.find(s => s.objectId === objectId);
        if (s) { s.chatIcon = icon; m.redraw(); }
    },

    createSession: async function(name, chatCfgOverride, promptCfgOverride, promptTemplateOverride) {
        // Resolve chatConfig: explicit override > user configs > library listing
        let cc = chatCfgOverride || null;
        if (!cc) {
            let chatCfgs = chatConfigs || [];
            cc = chatCfgs.length > 0 ? chatCfgs[0] : null;
        }
        if (!cc) {
            try {
                let dir = await LLMConnector.getLibraryGroup("chat");
                if (dir && dir.objectId) {
                    let { page: pg } = await import('../core/pageClient.js');
                    let cfgs = await pg.listObjects("olio.llm.chatConfig", dir.objectId, "name,objectId", 0, 10);
                    if (cfgs && cfgs.length) {
                        let openChat = cfgs.find(function(c) { return c.name === "Open Chat"; });
                        cc = openChat || cfgs[0];
                    }
                }
            } catch(e) { /* ignore */ }
        }
        if (!cc) {
            console.error("[ConversationManager] No chatConfig available for new session");
            page.toast("error", "Cannot create session: no chat configuration found.");
            return null;
        }

        let chatReq = {
            schema: "olio.llm.chatRequest",
            name: name || "Chat " + Date.now(),
            chatConfig: { objectId: cc.objectId },
            uid: page.uid()
        };

        // Attach prompt config/template if provided
        if (promptCfgOverride && promptCfgOverride.objectId) {
            chatReq.promptConfig = { objectId: promptCfgOverride.objectId };
        }
        if (promptTemplateOverride && promptTemplateOverride.objectId) {
            chatReq.promptTemplate = { objectId: promptTemplateOverride.objectId };
        }
        // If no prompt specified, list from library directories
        if (!chatReq.promptConfig && !chatReq.promptTemplate) {
            let userPcs = promptConfigs || [];
            if (userPcs.length > 0) {
                chatReq.promptConfig = { objectId: userPcs[0].objectId };
            }
            if (!chatReq.promptConfig && !chatReq.promptTemplate) {
                try {
                    let dir = await LLMConnector.getLibraryGroup("promptTemplate");
                    if (dir && dir.objectId) {
                        let { page: pg } = await import('../core/pageClient.js');
                        let tpls = await pg.listObjects("olio.llm.promptTemplate", dir.objectId, "name,objectId", 0, 10);
                        if (tpls && tpls.length) chatReq.promptTemplate = { objectId: tpls[0].objectId };
                    }
                } catch(e) { /* ignore */ }
            }
            if (!chatReq.promptConfig && !chatReq.promptTemplate) {
                try {
                    let dir = await LLMConnector.getLibraryGroup("prompt");
                    if (dir && dir.objectId) {
                        let { page: pg } = await import('../core/pageClient.js');
                        let pcs = await pg.listObjects("olio.llm.promptConfig", dir.objectId, "name,objectId", 0, 10);
                        if (pcs && pcs.length) chatReq.promptConfig = { objectId: pcs[0].objectId };
                    }
                } catch(e) { /* ignore */ }
            }
        }

        // Validate: backend requires both chatConfig AND at least one prompt
        if (!chatReq.promptConfig && !chatReq.promptTemplate) {
            console.error("[ConversationManager] No promptConfig or promptTemplate available for new session");
            page.toast("error", "Cannot create session: no prompt configuration found. Initialize the chat library first.");
            return null;
        }

        try {
            let { applicationPath } = await import('../core/config.js');
            let obj = await m.request({
                method: 'POST',
                url: applicationPath + "/rest/chat/new",
                withCredentials: true,
                body: chatReq
            });
            return obj;
        } catch (e) {
            console.error("[ConversationManager] createSession failed:", e);
            return null;
        }
    },

    autoSelectFirst: function() {
        if (selectedObjectId) return getSelectedMetadata();
        if (sessions && sessions.length > 0) {
            selectSession(sessions[0]);
            return sessions[0];
        }
        return null;
    },

    SidebarView: {
        oninit: function() {
            if (!sessions) loadSessions();
        },
        view: function(vnode) {
            let onNew = vnode.attrs.onNew || onNewSessionCallback;
            return m("div", { class: "flex flex-col flex-1 overflow-hidden" }, [
                searchBarView(),
                onNew ? m("div", { class: "px-2 py-1 border-b border-gray-200 dark:border-gray-600" }, [
                    m("button", {
                        class: "w-full text-xs px-2 py-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700 flex items-center gap-1",
                        onclick: () => onNew()
                    }, [
                        m("span", { class: "material-symbols-outlined", style: "font-size: 18px;" }, "add"),
                        " New"
                    ])
                ]) : "",
                m("div", { class: "overflow-y-auto flex-1" }, sessionListView()),
                m("div", { class: "border-t border-gray-200 dark:border-gray-600" }, [
                    m("div", { class: "px-2 py-1" }, [
                        m("button", {
                            class: "text-xs flex items-center gap-1 px-2 py-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700",
                            title: "Toggle session info",
                            onclick: () => { metadataExpanded = !metadataExpanded; }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size: 18px;" }, "info"),
                            " Session Info"
                        ])
                    ]),
                    metadataView()
                ])
            ]);
        }
    }
};

export { ConversationManager };
export default ConversationManager;
