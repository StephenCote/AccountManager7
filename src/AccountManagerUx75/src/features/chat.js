/**
 * Chat feature — main chat view + route export (ESM)
 * Three-panel layout: sidebar (sessions/context/memory) | chat messages | input
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { applicationPath } from '../core/config.js';
import { layout, pageLayout } from '../router.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import { ConversationManager } from '../chat/ConversationManager.js';
import { ContextPanel } from '../chat/ContextPanel.js';
import { MemoryPanel } from '../chat/MemoryPanel.js';
import { ChatTokenRenderer } from '../chat/ChatTokenRenderer.js';
import { ChatSetupWizard } from '../chat/ChatSetupWizard.js';
import { AnalysisManager } from '../chat/AnalysisManager.js';
import { LLMDebugPanel } from '../chat/LLMDebugPanel.js';
import { am7chat } from '../chat/chatUtil.js';
import { ChatConfigToolbar } from '../chat/ChatConfigToolbar.js';
import { ObjectPicker } from '../components/picker.js';
import { GossipPanel } from '../chat/GossipPanel.js';
import { SceneGenerator } from '../chat/SceneGenerator.js';
import { ChainManager } from '../chat/ChainManager.js';
import { ChatExport } from '../chat/ChatExport.js';

// ── Chat state ──────────────────────────────────────────────────────

let inst = null;
let chatCfg = newChatConfig();
let hideThoughts = true;
let sidebarCollapsed = true;
let sidebarActivePanel = null; // null | "conversations" | "memories" | "context"
let editMode = false;
let deletedIndices = new Set();
let fullMode = false;

function newChatConfig() {
    return {
        history: { messages: [] },
        pending: false,
        streaming: false,
        peek: false,
        chat: null,
        user: null,
        system: null,
        streamText: ""
    };
}

// ── Session lifecycle ───────────────────────────────────────────────

function doClear() {
    inst = null;
    chatCfg = newChatConfig();
    ContextPanel.clear();
}

function pickSession(obj) {
    doClear();
    inst = am7model.prepareInstance(obj);
    if (obj && obj.objectId) {
        ContextPanel.load(obj.objectId);
        ContextPanel.onContextChange(function(ctxData) {
            if (ctxData && ctxData.summarizing) {
                LLMConnector.setBgActivity("progress_activity", "Creating summary...");
            } else if (LLMConnector.bgActivity && LLMConnector.bgActivity.label === "Creating summary...") {
                LLMConnector.setBgActivity(null, null);
            }
            m.redraw();
        });
    }
    doPeek().then(function() {
        if (MemoryPanel && chatCfg.chat) {
            MemoryPanel.loadForSession(chatCfg.chat, inst ? inst.api.objectId() : null);
        }
    }).catch(function(e) {
        console.warn("Failed to peek session", e);
    });
}

async function doPeek() {
    if (!inst) return;
    chatCfg.peek = true;

    let cc = inst.api.chatConfig ? inst.api.chatConfig() : null;
    let pc = inst.api.promptConfig ? inst.api.promptConfig() : null;
    let pt = inst.api.promptTemplate ? inst.api.promptTemplate() : null;

    if (cc && cc.objectId) {
        try {
            let fullCc = await am7client.getFull("olio.llm.chatConfig", cc.objectId);
            if (fullCc) {
                chatCfg.chat = fullCc;
                if (fullCc.userCharacter) chatCfg.user = fullCc.userCharacter;
                if (fullCc.systemCharacter) chatCfg.system = fullCc.systemCharacter;
            }
        } catch(e) {
            console.warn("Failed to load chatConfig:", e);
        }
    }

    let h = await getHistory();
    if (h) {
        if (!h.messages) h.messages = [];
        chatCfg.history = h;
    }
    chatCfg.peek = false;
    m.redraw();
}

async function getHistory() {
    if (!inst) return null;
    try {
        return await LLMConnector.getHistory(inst.entity);
    } catch (e) {
        console.warn("getHistory failed:", e);
        return null;
    }
}

// ── Chat send ───────────────────────────────────────────────────────

function newChatStream() {
    let cfg = {
        streamId: undefined,
        request: undefined,
        onchatstart: function(id) {
            cfg.streamId = id;
            chatCfg.streaming = true;
            chatCfg.streamText = "";
            m.redraw();
        },
        onchatupdate: function(id, data) {
            if (cfg.streamId !== id) return;
            chatCfg.streamText += (data || "");
            m.redraw();
        },
        onchatcomplete: function(id) {
            chatCfg.streaming = false;
            if (chatCfg.streamText) {
                if (!chatCfg.history.messages) chatCfg.history.messages = [];
                chatCfg.history.messages.push({ role: "assistant", content: chatCfg.streamText });
            }
            chatCfg.streamText = "";
            getHistory().then(function(h) {
                if (h) {
                    if (!h.messages) h.messages = [];
                    chatCfg.history = h;
                }
                m.redraw();
            });
            page.chatStream = null;
        },
        onchaterror: function(id, err) {
            chatCfg.streaming = false;
            chatCfg.streamText = "";
            page.chatStream = null;
            page.toast("error", "Chat error: " + (err || "unknown"));
            m.redraw();
        }
    };
    return cfg;
}

async function doSend(message) {
    if (!inst || !message || !message.trim()) return;

    // Phase 6a: If streaming, interrupt current stream and resend
    if (chatCfg.streaming) {
        try { LLMConnector.cancelStream(inst.entity); } catch(e) {}
        chatCfg.streaming = false;
        chatCfg.streamText = "";
        page.chatStream = null;
        // Brief pause to let cancel propagate
        await new Promise(function(r) { setTimeout(r, 200); });
    }

    chatCfg.pending = true;
    if (!chatCfg.history.messages) chatCfg.history.messages = [];
    chatCfg.history.messages.push({ role: "user", content: message });
    m.redraw();

    try {
        let stream = newChatStream();
        page.chatStream = stream;
        await LLMConnector.streamChat(inst.entity, message, stream);
    } catch (e) {
        // Fallback to buffered chat
        try {
            let resp = await LLMConnector.chat(inst.entity, message);
            let content = LLMConnector.extractContent(resp);
            if (content) {
                if (!chatCfg.history.messages) chatCfg.history.messages = [];
                chatCfg.history.messages.push({ role: "assistant", content: content });
            }
        } catch (e2) {
            page.toast("error", "Chat failed: " + (e2.message || e2));
        }
    }

    chatCfg.pending = false;
    m.redraw();
}

async function doCancel() {
    chatCfg.pending = false;
    chatCfg.streaming = false;
    chatCfg.history = { messages: [] };
    if (inst && inst.api.objectId()) {
        try {
            await m.request({
                method: 'POST',
                url: applicationPath + "/rest/chat/clear",
                withCredentials: true,
                body: {
                    schema: inst.model.name,
                    objectId: inst.api.objectId(),
                    uid: page.uid()
                }
            });
        } catch(e) { /* ignore */ }
    }
    m.redraw();
}

async function doDelete() {
    if (!inst) return;
    try {
        await LLMConnector.deleteSession(inst.entity, false);
        doClear();
        await ConversationManager.refresh();
        ConversationManager.autoSelectFirst();
    } catch (e) {
        page.toast("error", "Delete failed");
    }
    m.redraw();
}

// ── Edit mode ───────────────────────────────────────────────────────

function toggleEditMode() {
    editMode = !editMode;
    deletedIndices.clear();
    m.redraw();
}

async function saveEdits() {
    if (!inst || !chatCfg.history.messages) return;
    let messages = [];
    chatCfg.history.messages.forEach(function(msg, idx) {
        if (deletedIndices.has(idx)) return;
        let el = document.getElementById("editMessage-" + idx);
        let content = el ? el.value : msg.content;
        messages.push({ role: msg.role, content: content });
    });

    // Update local state
    chatCfg.history.messages = messages;
    editMode = false;
    deletedIndices.clear();

    // Refresh from server
    let h = await getHistory();
    if (h) {
        if (!h.messages) h.messages = [];
        chatCfg.history = h;
    }
    m.redraw();
}

// ── Render helpers ──────────────────────────────────────────────────

function renderMessage(msg, idx) {
    let isUser = msg.role === "user";
    let content = msg.content || "";
    let isDeleted = deletedIndices.has(idx);

    // Edit mode: render textarea
    if (editMode) {
        return m("div", {
            class: "flex mb-3 " + (isUser ? "justify-end" : "justify-start") + (isDeleted ? " opacity-30 line-through" : ""),
            key: "msg-" + idx
        }, [
            m("div", { class: "max-w-[80%] w-full" }, [
                m("div", { class: "flex items-center gap-1 mb-1" }, [
                    m("span", { class: "text-xs opacity-60" }, isUser ? "You" : (chatCfg.system ? chatCfg.system.name : "Assistant")),
                    m("button", {
                        class: "text-xs px-1 rounded " + (isDeleted ? "text-green-500 hover:bg-green-50" : "text-red-400 hover:bg-red-50"),
                        onclick: function() {
                            if (isDeleted) deletedIndices.delete(idx);
                            else deletedIndices.add(idx);
                            m.redraw();
                        }
                    }, isDeleted ? "restore" : "delete")
                ]),
                m("textarea", {
                    id: "editMessage-" + idx,
                    class: "w-full px-3 py-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-sm resize-y min-h-[60px]",
                    value: content,
                    disabled: isDeleted
                })
            ])
        ]);
    }

    // Process tokens
    content = ChatTokenRenderer.processContent(content);

    // Hide <think> blocks if toggle is on
    if (hideThoughts) {
        content = content.replace(/<think>[\s\S]*?<\/think>/g, "");
    }

    let bubbleClass = isUser
        ? "ml-auto bg-blue-600 text-white rounded-lg rounded-br-none"
        : "mr-auto bg-gray-100 dark:bg-gray-800 text-gray-800 dark:text-gray-200 rounded-lg rounded-bl-none";

    // Phase 6c: Character avatar
    let avatar = null;
    if (!isUser && chatCfg.system && chatCfg.system.profile && chatCfg.system.profile.portrait) {
        let pp = chatCfg.system.profile.portrait;
        let thumbUrl = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + (pp.groupPath || "") + "/" + (pp.name || "") + "/48x48";
        avatar = m("img", { src: thumbUrl, class: "w-8 h-8 rounded-full shrink-0 mt-1", onerror: function(e) { e.target.style.display = "none"; } });
    } else if (!isUser) {
        avatar = m("span", { class: "material-symbols-outlined text-gray-400 shrink-0 mt-1", style: "font-size:28px" }, "smart_toy");
    }
    if (isUser && chatCfg.user && chatCfg.user.profile && chatCfg.user.profile.portrait) {
        let up = chatCfg.user.profile.portrait;
        let thumbUrl = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + (up.groupPath || "") + "/" + (up.name || "") + "/48x48";
        avatar = m("img", { src: thumbUrl, class: "w-8 h-8 rounded-full shrink-0 mt-1", onerror: function(e) { e.target.style.display = "none"; } });
    }

    return m("div", { class: "flex mb-3 gap-2 " + (isUser ? "justify-end" : "justify-start"), key: "msg-" + idx }, [
        !isUser ? avatar : null,
        m("div", { class: "max-w-[80%] px-4 py-2 text-sm " + bubbleClass }, [
            m("div", { class: "text-xs opacity-60 mb-1" }, isUser ? "You" : (chatCfg.system ? chatCfg.system.name : "Assistant")),
            m.trust(formatContent(content))
        ]),
        isUser ? avatar : null
    ]);
}

function formatContent(text) {
    if (!text) return "";
    // Basic markdown-like formatting
    text = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    // Code blocks
    text = text.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre class="bg-gray-900 text-green-400 p-2 rounded my-2 overflow-x-auto text-xs"><code>$2</code></pre>');
    // Inline code
    text = text.replace(/`([^`]+)`/g, '<code class="bg-gray-200 dark:bg-gray-700 px-1 rounded text-sm">$1</code>');
    // Bold
    text = text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    // Italic
    text = text.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    // Line breaks
    text = text.replace(/\n/g, '<br>');
    return text;
}

function renderStreamingMessage() {
    if (!chatCfg.streaming || !chatCfg.streamText) return null;
    let content = ChatTokenRenderer.processContent(chatCfg.streamText);
    if (hideThoughts) {
        content = content.replace(/<think>[\s\S]*?<\/think>/g, "");
    }
    return m("div", { class: "flex mb-3 justify-start" }, [
        m("div", { class: "max-w-[80%] px-4 py-2 text-sm mr-auto bg-gray-100 dark:bg-gray-800 text-gray-800 dark:text-gray-200 rounded-lg rounded-bl-none" }, [
            m("div", { class: "text-xs opacity-60 mb-1" }, chatCfg.system ? chatCfg.system.name : "Assistant"),
            m.trust(formatContent(content)),
            m("span", { class: "inline-block w-2 h-4 bg-blue-500 animate-pulse ml-1" })
        ])
    ]);
}

function renderPendingIndicator() {
    if (!chatCfg.pending || chatCfg.streaming) return null;
    return m("div", { class: "flex justify-start mb-3" }, [
        m("div", { class: "px-4 py-2 text-sm mr-auto bg-gray-100 dark:bg-gray-800 rounded-lg" }, [
            m("div", { class: "flex items-center gap-2 text-gray-400" }, [
                m("span", { class: "material-symbols-outlined animate-spin", style: "font-size:16px" }, "progress_activity"),
                "Thinking..."
            ])
        ])
    ]);
}

// ── Sidebar ─────────────────────────────────────────────────────────

function toggleSidebar(panel) {
    if (sidebarActivePanel === panel && !sidebarCollapsed) {
        sidebarCollapsed = true;
        sidebarActivePanel = null;
    } else {
        sidebarCollapsed = false;
        sidebarActivePanel = panel;
    }
    m.redraw();
}

function renderSidebar() {
    let iconStrip = m("div", { class: "flex flex-col items-center py-2 gap-1 border-r border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-900 w-12 shrink-0" }, [
        sidebarIcon("forum", "conversations", "Sessions"),
        sidebarIcon("attach_file", "context", "Context"),
        sidebarIcon("psychology", "memories", "Memories")
    ]);

    if (sidebarCollapsed) return iconStrip;

    let panelContent = null;
    if (sidebarActivePanel === "conversations") {
        panelContent = m(ConversationManager.SidebarView);
    } else if (sidebarActivePanel === "context") {
        panelContent = m(ContextPanel.PanelView);
    } else if (sidebarActivePanel === "memories") {
        panelContent = m(MemoryPanel.PanelView);
    }

    return m("div", { class: "flex h-full" }, [
        iconStrip,
        m("div", { class: "w-64 border-r border-gray-200 dark:border-gray-700 overflow-y-auto bg-white dark:bg-gray-900" }, [
            panelContent
        ])
    ]);
}

function sidebarIcon(icon, panel, title) {
    let active = sidebarActivePanel === panel && !sidebarCollapsed;
    return m("button", {
        class: "p-2 rounded " + (active ? "bg-blue-100 dark:bg-blue-900 text-blue-600" : "text-gray-500 hover:bg-gray-200 dark:hover:bg-gray-800"),
        title: title,
        onclick: function() { toggleSidebar(panel); }
    }, m("span", { class: "material-symbols-outlined", style: "font-size:20px" }, icon));
}

// ── Toolbar ─────────────────────────────────────────────────────────

function renderToolbar() {
    let sessionName = inst ? (inst.api.name ? inst.api.name() : "Chat") : "No session";
    let bgActivity = LLMConnector.bgActivity;

    return m("div", { class: "flex items-center justify-between px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 shrink-0" }, [
        m("div", { class: "flex items-center gap-3" }, [
            m("h2", { class: "text-sm font-semibold text-gray-800 dark:text-white truncate max-w-xs" }, sessionName),
            bgActivity && bgActivity.icon ? m("span", {
                class: "material-symbols-outlined text-blue-500 animate-spin",
                style: "font-size:16px",
                title: bgActivity.label || ""
            }, bgActivity.icon) : null
        ]),
        m("div", { class: "flex items-center gap-1" }, [
            m("button", {
                class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                title: hideThoughts ? "Show thoughts" : "Hide thoughts",
                onclick: function() { hideThoughts = !hideThoughts; m.redraw(); }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, hideThoughts ? "visibility_off" : "visibility")),
            // Edit mode toggle
            inst ? m("button", {
                class: "p-1.5 rounded " + (editMode ? "text-blue-600 bg-blue-50 dark:bg-blue-900/30" : "text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"),
                title: editMode ? "Exit edit mode" : "Edit messages",
                onclick: toggleEditMode
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "edit_note")) : null,
            // Save edits button (visible only in edit mode)
            editMode ? m("button", {
                class: "p-1.5 rounded text-green-600 hover:bg-green-50 dark:hover:bg-green-900/30",
                title: "Save edits",
                onclick: saveEdits
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "check")) : null,
            // Gossip
            inst && chatCfg.system ? m("div", { class: "relative" }, [
                m("button", {
                    class: "p-1.5 rounded " + (GossipPanel.isVisible() ? "text-purple-600 bg-purple-50 dark:bg-purple-900/30" : "text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"),
                    title: "Memory Gossip",
                    onclick: function() {
                        let personId = chatCfg.system.objectId;
                        GossipPanel.toggle(personId, {}, function(content) {
                            let input = document.querySelector("[name='chatmessage']");
                            if (input) input.value = (input.value ? input.value + " " : "") + content;
                            GossipPanel.hide();
                        });
                    }
                }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "psychology_alt")),
                m(GossipPanel.PanelView)
            ]) : null,
            // Scene generation
            inst ? m("div", { class: "relative" }, [
                m("button", {
                    class: "p-1.5 rounded " + (SceneGenerator.isVisible() ? "text-green-600 bg-green-50 dark:bg-green-900/30" : "text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"),
                    title: "Generate Scene",
                    onclick: function() {
                        SceneGenerator.toggle(inst.api.objectId(), function(result) {
                            // Append scene result to chat
                            if (result && result.content) {
                                chatCfg.history.messages.push({ role: "assistant", content: result.content });
                                m.redraw();
                            }
                        });
                    }
                }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "landscape")),
                m(SceneGenerator.PanelView)
            ]) : null,
            // Export
            inst && chatCfg.history.messages.length > 0 ? m("button", {
                class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                title: "Export chat",
                onclick: function() {
                    let name = inst.api.name ? inst.api.name() : "chat";
                    ChatExport.exportChat(chatCfg.history, name, "markdown", chatCfg);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "download")) : null,
            // Fullscreen toggle
            m("button", {
                class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                title: fullMode ? "Exit fullscreen" : "Fullscreen",
                onclick: function() { fullMode = !fullMode; m.redraw(); }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, fullMode ? "close_fullscreen" : "open_in_full")),
            inst ? m("button", {
                class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                title: "Clear history",
                onclick: doCancel
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "delete_sweep")) : null,
            inst ? m("button", {
                class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                title: "Delete session",
                onclick: doDelete
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "delete")) : null,
            m("button", {
                class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                title: "LLM Debug",
                onclick: function() { LLMDebugPanel.toggle(); }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "bug_report"))
        ])
    ]);
}

// ── Message input ───────────────────────────────────────────────────

function renderInput() {
    // Allow typing while streaming (will interrupt), disable only when pending non-stream
    let disabled = !inst || (chatCfg.pending && !chatCfg.streaming);

    return m("div", { class: "border-t border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 p-3 shrink-0" }, [
        m("form", {
            class: "flex gap-2",
            onsubmit: function(e) {
                e.preventDefault();
                let input = e.target.querySelector("[name='chatmessage']");
                if (input && input.value.trim()) {
                    doSend(input.value.trim());
                    input.value = "";
                }
            }
        }, [
            m("input", {
                type: "text",
                name: "chatmessage",
                class: "flex-1 px-3 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50",
                placeholder: disabled ? "Select or create a session..." : "Type a message...",
                disabled: disabled,
                onkeydown: function(e) {
                    if (e.key === "Enter" && !e.shiftKey) {
                        e.preventDefault();
                        e.target.form.requestSubmit();
                    }
                }
            }),
            chatCfg.streaming
                ? m("button", {
                    type: "button",
                    class: "px-4 py-2 rounded-lg bg-red-600 text-white text-sm hover:bg-red-500",
                    onclick: function() {
                        if (inst) LLMConnector.cancelStream(inst.entity);
                    }
                }, "Stop")
                : m("button", {
                    type: "submit",
                    class: "px-4 py-2 rounded-lg bg-blue-600 text-white text-sm hover:bg-blue-500 disabled:opacity-50",
                    disabled: disabled
                }, "Send")
        ])
    ]);
}

// ── New session dialog ──────────────────────────────────────────────

let showNewSession = false;
let newSessionName = "";

function renderNewSessionDialog() {
    if (!showNewSession) return null;
    return m("div", { class: "fixed inset-0 z-50 flex items-center justify-center" }, [
        m("div", { class: "absolute inset-0 bg-black/50", onclick: function() { showNewSession = false; m.redraw(); } }),
        m("div", { class: "relative bg-white dark:bg-gray-900 rounded-lg shadow-xl w-full max-w-sm mx-4 p-4" }, [
            m("h3", { class: "text-lg font-semibold text-gray-800 dark:text-white mb-4" }, "New Chat Session"),
            m("input", {
                type: "text",
                class: "w-full px-3 py-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-sm mb-4",
                placeholder: "Session name",
                value: newSessionName,
                oninput: function(e) { newSessionName = e.target.value; }
            }),
            m("div", { class: "flex justify-end gap-2" }, [
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-800",
                    onclick: function() { showNewSession = false; m.redraw(); }
                }, "Cancel"),
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500",
                    onclick: async function() {
                        if (!newSessionName.trim()) return;
                        showNewSession = false;
                        try {
                            let req = await ConversationManager.createSession(newSessionName.trim());
                            if (req) {
                                await ConversationManager.refresh();
                                pickSession(req);
                            }
                        } catch(e) {
                            page.toast("error", "Failed to create session");
                        }
                        newSessionName = "";
                        m.redraw();
                    }
                }, "Create")
            ])
        ])
    ]);
}

// ── Empty state / library check ─────────────────────────────────────

function renderEmptyState() {
    return m("div", { class: "flex-1 flex items-center justify-center" }, [
        m("div", { class: "text-center p-8" }, [
            m("span", { class: "material-symbols-outlined text-gray-300 dark:text-gray-600", style: "font-size:64px" }, "chat"),
            m("p", { class: "mt-4 text-gray-500 dark:text-gray-400" }, "Select a conversation or create a new one"),
            m("button", {
                class: "mt-4 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm hover:bg-blue-500",
                onclick: function() {
                    showNewSession = true;
                    newSessionName = "Chat " + new Date().toLocaleString();
                    m.redraw();
                }
            }, "New Session")
        ])
    ]);
}

// ── Main chat view component ────────────────────────────────────────

let chatViewInitialized = false;

const chatView = {
    oninit: async function() {
        if (chatViewInitialized) return;
        chatViewInitialized = true;

        // Check library status
        let libOk = await LLMConnector.ensureLibrary();
        if (!libOk) {
            ChatSetupWizard.show(async function() {
                await initChatView();
            });
            m.redraw();
            return;
        }

        await initChatView();
    },

    onremove: function() {
        chatViewInitialized = false;
        // Don't clear state — user may navigate back
    },

    view: function() {
        return m("div", { class: "flex h-full w-full bg-white dark:bg-gray-950" }, [
            // Sidebar (hidden in fullscreen)
            fullMode ? null : renderSidebar(),
            // Main chat area
            m("div", { class: "flex-1 flex flex-col min-w-0" }, [
                renderToolbar(),
                m(ChatConfigToolbar, { inst: inst, chatCfg: chatCfg, onConfigChange: function() { doPeek(); } }),
                m(ChainManager.ProgressView),
                // Messages area
                inst ? m("div", {
                    class: "flex-1 overflow-y-auto p-4",
                    oncreate: function(vnode) { scrollToBottom(vnode.dom); },
                    onupdate: function(vnode) { scrollToBottom(vnode.dom); }
                }, [
                    (chatCfg.history.messages || []).map(renderMessage),
                    renderStreamingMessage(),
                    renderPendingIndicator()
                ]) : renderEmptyState(),
                renderInput()
            ]),
            // Overlays
            m(ChatSetupWizard.WizardView),
            m(LLMDebugPanel.PanelView),
            m(ObjectPicker.PickerView),
            renderNewSessionDialog()
        ]);
    }
};

function scrollToBottom(el) {
    if (el) el.scrollTop = el.scrollHeight;
}

async function initChatView() {
    // Wire conversation manager callbacks
    ConversationManager.onSelect(pickSession);
    ConversationManager.onDelete(async function(obj) {
        try {
            await LLMConnector.deleteSession(obj, false);
            await ConversationManager.refresh();
            if (inst && obj.objectId === inst.api.objectId()) {
                doClear();
                ConversationManager.autoSelectFirst();
            }
        } catch(e) {
            page.toast("error", "Delete failed");
        }
        m.redraw();
    });

    // Wire new session button in ConversationManager
    ConversationManager.onNewSession(function() {
        showNewSession = true;
        newSessionName = "Chat " + new Date().toLocaleString();
        m.redraw();
    });

    // Register policy event display
    LLMConnector.onPolicyEvent(function(evt) {
        let msg = "Policy violation detected";
        if (evt && evt.data) {
            try {
                let details = typeof evt.data === "string" ? JSON.parse(evt.data) : evt.data;
                if (details.details) msg = details.details;
            } catch(e) {}
        }
        page.toast("warn", msg, 5000);
    });

    // Load conversation list
    await ConversationManager.load();

    // Execute pending analysis if any
    if (AnalysisManager.getActiveAnalysis()) {
        await AnalysisManager.executePending();
    } else {
        ConversationManager.autoSelectFirst();
    }

    m.redraw();
}

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/chat": {
        oninit: function() { chatView.oninit(); },
        onremove: function() { chatView.onremove(); },
        view: function() {
            return layout(
                m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
                    chatView.view()
                ])
            );
        }
    }
};
