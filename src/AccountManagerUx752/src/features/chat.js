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
import { am7chat } from '../chat/chatUtil.js';
import { ObjectPicker } from '../components/picker.js';
import { GossipPanel } from '../chat/GossipPanel.js';
import { SceneGenerator } from '../chat/SceneGenerator.js';
import { ChainManager } from '../chat/ChainManager.js';
import { ChatExport } from '../chat/ChatExport.js';
import { am7imageTokens } from '../chat/imageTokens.js';
import { LLMDebugPanel } from '../chat/LLMDebugPanel.js';
import { contextMenu } from '../components/contextMenu.js';
import { Dialog } from '../components/dialogCore.js';
import { marked } from 'marked';

// Ensure am7model._sd is loaded for image token generation (fetchTemplate)
if (!am7model._sd) {
    import('../components/sdConfig.js').then(function(mod) {
        am7model._sd = mod.am7sd;
    }).catch(function() {});
}

// ── Chat state ──────────────────────────────────────────────────────

let inst = null;
let chatCfg = newChatConfig();
let resolvingImages = {};
let hideThoughts = true;
let sidebarCollapsed = true;
let sidebarActivePanel = null; // null | "conversations" | "memories" | "context"
let editMode = false;
let deletedIndices = new Set();
let fullMode = false;
let showPortraits = false;
/// Render <mcp:context> blocks as debug cards instead of stripping them (LLM Debug drawer toggle).
let showMcpContext = false;
/// The per-chat LLM Debug drawer under the toolbar (opened from the toolbar overflow).
let debugDrawerOpen = false;

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

/// Active outfit per chat character, for the toolbar's outfit links: { objectId, name } or null.
let activeApparel = { system: null, user: null };

/// Find each character's active apparel the way components/olio.js dressCharacter does: the store's
/// apparel with inuse (the server applies wardrobe tokens, so this is re-read after a turn that used them).
async function refreshActiveApparel() {
    for (let role of ["system", "user"]) {
        let ch = chatCfg[role];
        let storeRef = ch && ch.store ? ch.store : null;
        if (!storeRef || !storeRef.objectId) { activeApparel[role] = null; continue; }
        try {
            await am7client.clearCache("olio.store");
            let sto = await am7client.getFull("olio.store", storeRef.objectId);
            let ap = sto && sto.apparel ? (sto.apparel.find(a => a.inuse) || null) : null;
            activeApparel[role] = ap ? { objectId: ap.objectId, name: ap.name || "outfit" } : null;
        } catch (e) {
            console.warn("refreshActiveApparel failed for " + role, e);
            activeApparel[role] = null;
        }
    }
    m.redraw();
}

function hasWardrobeTokens(msgs, lastN) {
    if (!msgs || !msgs.length) return false;
    return msgs.slice(-lastN).some(mm => mm && mm.content && /\$\{(outfit|dress|give)/.test(mm.content));
}

/// Same route pictureBookCharacters.openInNewTab opens: the generic object view.
function objectViewHref(type, objectId) {
    return window.location.pathname + "#!/view/" + type + "/" + objectId;
}

function doClear() {
    activeApparel = { system: null, user: null };
    inst = null;
    chatCfg = newChatConfig();
    clearFormattedCache();
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
        // Auto-start system-start sessions with no history
        doAutoStart();
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
                // Resolve characters fully — getFull may return stubs with only objectId
                if (fullCc.systemCharacter && fullCc.systemCharacter.objectId) {
                    try {
                        let fullSys = await am7client.getFull("olio.charPerson", fullCc.systemCharacter.objectId);
                        chatCfg.system = fullSys || fullCc.systemCharacter;
                    } catch(e2) {
                        console.warn("Failed to resolve systemCharacter:", e2);
                        chatCfg.system = fullCc.systemCharacter;
                    }
                }
                if (fullCc.userCharacter && fullCc.userCharacter.objectId) {
                    try {
                        let fullUsr = await am7client.getFull("olio.charPerson", fullCc.userCharacter.objectId);
                        chatCfg.user = fullUsr || fullCc.userCharacter;
                    } catch(e2) {
                        console.warn("Failed to resolve userCharacter:", e2);
                        chatCfg.user = fullCc.userCharacter;
                    }
                }
            }
        } catch(e) {
            console.warn("Failed to load chatConfig:", e);
        }
    }

    let h = await getHistory();
    if (h) {
        if (!h.messages) h.messages = [];
        chatCfg.history = h;
        clearFormattedCache();
    }
    chatCfg.peek = false;
    m.redraw();
    refreshActiveApparel();
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

// ── Image token resolution (port of Ux7 chat.js:229-375) ────────────

/**
 * Patch resolved image tokens on the server session, then reload history.
 * replacements: array of {from, to, role?, content?}. When role/content are given (resolveChatImages
 * always gives them) only the server message with that role whose content matches is patched — the
 * same unresolved token (e.g. ${image.casual,public}) appears in both the user and assistant intros,
 * and patching every message that contained it gave the user's intro the system character's picture.
 * Only an occurrence NOT already followed by an [image] note is replaced, so annotation is idempotent.
 * Returns true when something was patched.
 */
async function patchChatImageToken(replacements) {
    if (!inst || !replacements || !replacements.length) return false;
    let entity = inst.entity;
    if (!entity || !entity.session || !entity.sessionType) return false;
    let patched = false;

    try {
        let q = am7client.newQuery(entity.sessionType);
        q.field("id", entity.session.id);
        q.cache(false);
        q.entity.request.push("id", "objectId", "messages");
        let qr = await page.search(q);
        if (qr && qr.results && qr.results.length > 0) {
            let req = qr.results[0];
            if (req.messages) {
                for (let r of replacements) {
                    /// Prefer the exact message (role + identical content); fall back to the most recent
                    /// message of that role containing the token when the client copy differs.
                    let candidates = req.messages.filter(function(sm) {
                        return sm.content && (!r.role || sm.role === r.role) && sm.content.indexOf(r.from) !== -1;
                    });
                    let target = (r.content ? candidates.find(function(sm) { return sm.content === r.content; }) : null)
                        || (candidates.length ? candidates[candidates.length - 1] : null);
                    if (!target) continue;
                    let next = am7imageTokens.replaceUnannotated(target.content, r.from, r.to);
                    if (next !== target.content) {
                        target.content = next;
                        patched = true;
                    }
                }
                if (patched) {
                    await page.patchObject(req);
                    // Reload history if not streaming
                    if (!chatCfg.streaming) {
                        let h = await getHistory();
                        if (h) {
                            if (!h.messages) h.messages = [];
                            chatCfg.history = h;
                        }
                    }
                }
            }
        }
    } catch (e) {
        console.error("patchChatImageToken server error:", e);
        return false;
    }
    return patched;
}

/**
 * Resolve all image tokens in a message one at a time, patching after each.
 * Port of Ux7 chat.js:322-375.
 */
async function resolveChatImages(msgIndex) {
    if (!am7imageTokens) return;
    // Skip during streaming
    if (chatCfg.streaming) return;
    let maxIterations = 10;
    let iteration = 0;
    /// Token strings already handled this pass, so a patch that does not land (PBAC, race) cannot loop.
    let attempted = new Set();

    while (iteration < maxIterations) {
        iteration++;

        let msgs = chatCfg.history ? chatCfg.history.messages : null;
        if (!msgs || !msgs[msgIndex]) return;
        let msg = msgs[msgIndex];
        if (!msg.content) return;

        let character = msg.role === "user" ? chatCfg.user : chatCfg.system;
        if (!character) return;

        let tokens = am7imageTokens.parse(msg.content);

        // First token that is unresolved, not yet cached, or still missing its [image] note
        // (aiDocs/ChatWardrobeTokensDesign.md §3.4b — the note also backfills older chats).
        let pending = null;
        for (let token of tokens) {
            if (attempted.has(token.match)) continue;
            if (!token.id || !am7imageTokens.cache[token.id] || am7imageTokens.needsImageNote(msg.content, token.match)) {
                pending = token;
                break;
            }
        }

        if (!pending) break;
        attempted.add(pending.match);

        try {
            let resolved = await am7imageTokens.resolve(pending, character, {});
            if (!resolved || !resolved.image) break;
            let resolvedToken = pending.id
                ? pending.match
                : "${image." + resolved.image.objectId + "." + pending.tags.join(",") + "}";
            if (!pending.id || am7imageTokens.needsImageNote(msg.content, pending.match)) {
                let caption = await am7imageTokens.describeImage(resolved.image.objectId);
                let note = am7imageTokens.buildImageNote(character, pending.tags, caption);
                await patchChatImageToken([{ from: pending.match, to: resolvedToken + note, role: msg.role, content: msg.content }]);
            }
        } catch (e) {
            console.error("Error resolving image token:", pending.match, e);
            break;
        }
    }
    // Clear memoized HTML so re-render picks up resolved images
    clearFormattedCache();
    m.redraw();
}

// ── Chat send ───────────────────────────────────────────────────────

// Throttled redraw for streaming updates — ~15fps to avoid layout thrashing in Firefox
let _streamRedrawTimer = 0;
function scheduleStreamRedraw() {
    if (!_streamRedrawTimer) {
        _streamRedrawTimer = setTimeout(function() {
            _streamRedrawTimer = 0;
            m.redraw();
        }, 66); // ~15fps — smooth enough for text streaming, much easier on Firefox
    }
}

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
            scheduleStreamRedraw();
        },
        onchatcomplete: function(id) {
            if (cfg.streamId !== id) return;
            chatCfg.streaming = false;
            chatCfg.pending = false;
            if (chatCfg.streamText) {
                if (!chatCfg.history.messages) chatCfg.history.messages = [];
                chatCfg.history.messages.push({ role: "assistant", content: chatCfg.streamText });
            }
            chatCfg.streamText = "";
            _streamCache.text = "";
            _streamCache.formatted = "";
            getHistory().then(function(h) {
                if (h) {
                    if (!h.messages) h.messages = [];
                    chatCfg.history = h;
                    clearFormattedCache();
                    if (hasWardrobeTokens(h.messages, 2)) refreshActiveApparel();
                }
                m.redraw();
            });
            page.chatStream = null;
        },
        onchaterror: function(id, err) {
            if (cfg.streamId !== id) return;
            chatCfg.streaming = false;
            _streamCache.text = "";
            _streamCache.formatted = "";
            chatCfg.pending = false;
            chatCfg.streamText = "";
            page.chatStream = null;
            page.toast("error", "Chat error: " + (err || "unknown"));
            m.redraw();
        }
    };
    return cfg;
}

async function doAutoStart() {
    if (!inst || !chatCfg.chat) return;
    let startMode = (chatCfg.chat.startMode || "").toLowerCase();
    if (startMode !== "system") return;
    // Only auto-start if conversation has no messages yet
    if (chatCfg.history.messages && chatCfg.history.messages.length > 0) return;
    // Send empty string to trigger system-start
    chatCfg.pending = true;
    m.redraw();
    // Yield to renderer so pending indicator is visible before stream connects
    await new Promise(function(r) { requestAnimationFrame(r); });
    try {
        let stream = newChatStream();
        page.chatStream = stream;
        LLMConnector.streamChat(inst.entity, "", stream);
        // Don't clear pending here — onchatstart/onchatcomplete/onchaterror handle it
    } catch (e) {
        try {
            let resp = await LLMConnector.chat(inst.entity, "");
            let content = LLMConnector.extractContent(resp);
            if (content) {
                if (!chatCfg.history.messages) chatCfg.history.messages = [];
                chatCfg.history.messages.push({ role: "assistant", content: content });
            }
        } catch (e2) {
            console.warn("Auto-start failed:", e2);
        }
        chatCfg.pending = false;
        m.redraw();
    }
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
        LLMConnector.streamChat(inst.entity, message, stream);
        // Don't clear pending here — onchatstart/onchatcomplete/onchaterror handle it
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
        chatCfg.pending = false;
        m.redraw();
    }
}

/// Toolbar overflow "Clear history": wipes the session's messages on the server, so ask first.
async function confirmClearHistory() {
    if (!inst) return false;
    let name = inst.api.name ? inst.api.name() : null;
    let ok = await Dialog.confirm({
        title: "Clear history",
        message: "Clear all messages in \"" + (name || "this chat") + "\"? This cannot be undone.",
        confirmLabel: "Clear",
        confirmIcon: "delete_sweep",
        destructive: true
    });
    if (!ok) return false;
    await doCancel();
    return true;
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

function doDelete() {
    if (!inst) return;
    // Use the confirm dialog callback pattern — the actual deletion and UI refresh
    // must happen INSIDE the callback, not after deleteSession returns (which is immediate).
    LLMConnector.deleteSession(inst.entity, false, async function() {
        doClear();
        await ConversationManager.refresh();
        ConversationManager.autoSelectFirst();
        m.redraw();
    });
}

// ── Edit mode ───────────────────────────────────────────────────────

function toggleEditMode() {
    if (editMode) {
        // Exiting edit mode — save all changes (edits + deletions)
        saveEdits();
        return;
    }
    editMode = true;
    deletedIndices.clear();
    clearFormattedCache();
    m.redraw();
}

async function saveEdits() {
    if (!inst || !chatCfg.history.messages) return;
    let entity = inst.entity;
    if (!entity || !entity.session || !entity.sessionType) {
        console.error("No session available for edit save");
        editMode = false; deletedIndices.clear(); return;
    }

    // Fetch the full server session record with messages (Ux7 pattern: viewQuery + messages in request)
    let q = am7view.viewQuery(am7model.newInstance(entity.sessionType));
    q.field("id", entity.session.id);
    q.cache(false);
    q.entity.request.push("messages");
    let qr;
    try {
        qr = await page.search(q);
    } catch (e) {
        console.error("Failed to fetch session for edit save:", e);
        editMode = false; deletedIndices.clear(); return;
    }
    if (!qr || !qr.results || !qr.results.length) {
        console.error("Server session record not found for edit save");
        editMode = false; deletedIndices.clear(); return;
    }
    let req = qr.results[0];
    if (!req.messages || !req.messages.length) {
        console.error("Server session has no messages");
        editMode = false; deletedIndices.clear(); return;
    }

    // Calculate offset: system/template messages at start of server record
    // that are not shown in the display
    let displayMsgs = chatCfg.history.messages;
    let offset = req.messages.length - displayMsgs.length;
    if (offset < 0) offset = 0;

    // Apply edits from textareas (preserve roles)
    for (let i = 0; i < displayMsgs.length; i++) {
        if (deletedIndices.has(i)) continue;
        let el = document.getElementById("editMessage-" + i);
        if (el && (i + offset) < req.messages.length) {
            req.messages[i + offset].content = el.value;
        }
    }

    // Remove deleted messages (reverse order to preserve indices)
    let sorted = Array.from(deletedIndices).sort(function(a, b) { return b - a; });
    for (let idx of sorted) {
        let serverIdx = idx + offset;
        if (serverIdx >= 0 && serverIdx < req.messages.length) {
            req.messages.splice(serverIdx, 1);
        }
    }

    // Patch server with edited messages
    await page.patchObject(req);
    editMode = false;
    deletedIndices.clear();

    // Clear cache and re-peek from server (matches Ux75 pattern)
    am7client.clearCache();
    clearFormattedCache();
    chatCfg.peek = false;
    chatCfg.history = { messages: [] };
    await doPeek();
    m.redraw();
}

// ── Render helpers ──────────────────────────────────────────────────

// Memoization cache: avoids re-running regex processing for unchanged messages.
// Key = msg content + role, value = formatted HTML string.
// Cleared on history reload or edit mode toggle.
let _formattedCache = new Map();

function clearFormattedCache() {
    _formattedCache.clear();
    _streamCache.text = "";
    _streamCache.formatted = "";
}

function getFormattedContent(content, msg, idx) {
    /// hideThoughts MUST be part of the cache key — without it, toggling the
    /// show/hide-thoughts button has no visible effect on already-rendered
    /// messages because the cached HTML is returned unchanged.
    let cacheKey = msg.role + ":" + idx + ":" + (hideThoughts ? "h" : "s") + (showMcpContext ? "m" : "") + ":" + content;
    let cached = _formattedCache.get(cacheKey);
    if (cached !== undefined) return cached;

    /// Wardrobe chips first: telling "failed" from "pending" needs the <private>[wardrobe] note that
    /// pruneForDisplay is about to strip (chat/wardrobeTokens.js).
    let processed = ChatTokenRenderer.processWardrobeTokens(content);
    processed = ChatTokenRenderer.pruneForDisplay(processed, hideThoughts);
    /// Strip <think>/<thought>/<private> when hidden; when shown, wrap them in a
    /// visible styled <details> block (see ChatTokenRenderer.renderThoughts).
    processed = ChatTokenRenderer.renderThoughts(processed, hideThoughts);
    // 1. Strip MCP blocks before markdown (they contain XML that marked would escape);
    //    the LLM Debug drawer's "Show MCP context in chat" renders them as cards instead.
    processed = ChatTokenRenderer.processMcpTokens(processed, showMcpContext);

    // 2. Extract image/audio tokens into placeholders before markdown rendering.
    //    marked.parse() would escape ${...} and the <img>/<button> HTML they produce.
    let characters = { system: chatCfg.system, user: chatCfg.user };
    let sessionId = inst ? inst.api.objectId() : null;
    let placeholders = [];
    processed = ChatTokenRenderer.processImageTokens(processed, msg.role, idx, characters, { resolvingImages: resolvingImages }, function(msgIdx) {
        return resolveChatImages(msgIdx);
    });
    processed = ChatTokenRenderer.processAudioTokens(processed, msg.role, idx, characters, sessionId);
    // Stash rendered HTML tokens as placeholders so marked doesn't escape them
    let phIdx = 0;
    processed = processed.replace(/<(?:img|button|span)\s[^>]*(?:data-token-key|data-audio-id)[^>]*>[\s\S]*?(?:<\/(?:button|span)>)?/g, function(match) {
        let key = "\x00PH" + (phIdx++) + "\x00";
        placeholders.push({ key: key, html: match });
        return key;
    });

    // 3. Markdown rendering
    processed = formatContent(processed);

    // 4. Restore placeholders
    for (let ph of placeholders) {
        processed = processed.replace(ph.key, ph.html);
    }

    _formattedCache.set(cacheKey, processed);
    return processed;
}

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
                    m("span", { class: "text-xs opacity-60" }, isUser ? (chatCfg.user ? chatCfg.user.name : "You") : (chatCfg.system ? chatCfg.system.name : "Assistant")),
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
                    // Use oncreate to set initial value ONCE — avoids Mithril redraw overwriting user edits
                    oncreate: function(vnode) { vnode.dom.value = content; },
                    disabled: isDeleted
                })
            ])
        ]);
    }

    let bubbleClass = isUser
        ? "ml-auto bg-blue-600 text-white rounded-lg rounded-br-none"
        : "mr-auto bg-gray-100 dark:bg-gray-800 text-gray-800 dark:text-gray-200 rounded-lg rounded-bl-none";

    // Character avatar — Ux7 style: 96x96 rounded-full, gender fallback, onclick imageView
    let avatar = null;
    let char = isUser ? chatCfg.user : chatCfg.system;
    if (char && char.profile && char.profile.portrait) {
        let pp = char.profile.portrait;
        let thumbUrl = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + (pp.groupPath || "") + "/" + (pp.name || "") + "/96x96";
        avatar = m("img", {
            src: thumbUrl,
            class: (isUser ? "ml-2" : "mr-2") + " rounded-full shrink-0 mt-1 cursor-pointer",
            style: "width:32px;height:32px",
            onclick: function() { if (page.imageView) page.imageView(pp); },
            onerror: function(e) { e.target.style.display = "none"; }
        });
    } else if (char) {
        let genderIcon = (char.gender === "female") ? "woman" : "man";
        avatar = m("span", { class: "material-icons-outlined text-gray-400 shrink-0 mt-1", style: "font-size:28px" }, genderIcon);
    } else if (!isUser) {
        avatar = m("span", { class: "material-symbols-outlined text-gray-400 shrink-0 mt-1", style: "font-size:28px" }, "smart_toy");
    }

    // Use memoized formatted content to avoid re-running regex processing on every redraw
    let formatted = getFormattedContent(content, msg, idx);

    return m("div", { class: "flex mb-3 gap-2 " + (isUser ? "justify-end" : "justify-start"), key: "msg-" + idx }, [
        !isUser ? avatar : null,
        m("div", { class: "max-w-[80%] px-4 py-2 text-sm chat-prose " + bubbleClass }, [
            m("div", { class: "text-xs opacity-60 mb-1" }, isUser ? (chatCfg.user ? chatCfg.user.name : "You") : (chatCfg.system ? chatCfg.system.name : "Assistant")),
            m.trust(formatted)
        ]),
        isUser ? avatar : null
    ]);
}

// Configure marked for chat rendering — Ux7 uses marked.parse() for full markdown
marked.setOptions({
    breaks: true,       // Convert \n to <br>
    gfm: true,          // GitHub-flavored markdown (tables, strikethrough, etc.)
    headerIds: false,    // Don't generate id attributes on headers
    mangle: false        // Don't mangle email addresses
});

function formatContent(text) {
    if (!text) return "";
    try {
        return marked.parse(text);
    } catch(e) {
        // Fallback: escape and linebreak only
        return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/\n/g, '<br>');
    }
}

// During streaming, limit how many older messages we render to reduce Firefox layout work.
// When not streaming, render all messages.
const MAX_VISIBLE_MESSAGES_STREAMING = 30;

function renderMessages() {
    let msgs = chatCfg.history.messages || [];
    if (!chatCfg.streaming || msgs.length <= MAX_VISIBLE_MESSAGES_STREAMING) {
        return msgs.map(renderMessage);
    }
    // During streaming with many messages, only render the tail
    let start = msgs.length - MAX_VISIBLE_MESSAGES_STREAMING;
    let truncated = m("div", {
        class: "text-center text-xs text-gray-400 py-2",
        key: "msg-truncated"
    }, start + " earlier messages hidden during streaming");
    let visible = [];
    for (let i = start; i < msgs.length; i++) {
        visible.push(renderMessage(msgs[i], i));
    }
    return [truncated].concat(visible);
}

let _streamCache = { text: "", formatted: "" };

function renderStreamingMessage() {
    if (!chatCfg.streaming || !chatCfg.streamText) return null;
    let raw = chatCfg.streamText;
    /// Re-process when the streaming text changes OR when the
    /// hideThoughts toggle was flipped while a stream is active.
    let cacheTag = raw + "|" + (hideThoughts ? "h" : "s");
    if (cacheTag !== _streamCache.text) {
        let content = ChatTokenRenderer.pruneForDisplay(raw, hideThoughts);
        content = ChatTokenRenderer.renderThoughts(content, hideThoughts);
        _streamCache.text = cacheTag;
        _streamCache.formatted = formatContent(content);
    }
    return m("div", { class: "flex mb-3 justify-start" }, [
        m("div", { class: "max-w-[80%] px-4 py-2 text-sm chat-prose mr-auto bg-gray-100 dark:bg-gray-800 text-gray-800 dark:text-gray-200 rounded-lg rounded-bl-none" }, [
            m("div", { class: "text-xs opacity-60 mb-1" }, chatCfg.system ? chatCfg.system.name : "Assistant"),
            m.trust(_streamCache.formatted),
            m("span", { class: "inline-block w-2 h-4 bg-blue-500 animate-pulse ml-1" })
        ])
    ]);
}

// Portrait header: system character left, user character right — show/hide toggle, default off
/// Toolbar links: each chat character (opens the charPerson) with its active outfit (opens the apparel).
function renderCharacterLinks() {
    let link = "inline-flex items-center gap-0.5 text-xs text-gray-500 dark:text-gray-400 hover:text-blue-600 dark:hover:text-blue-400";
    function one(role) {
        let ch = chatCfg[role];
        if (!ch || !ch.objectId) return null;
        let nm = ch.firstName || ch.name || role;
        let ap = activeApparel[role];
        return m("span", { class: "inline-flex items-center gap-1", "data-testid": "chat-char-links-" + role }, [
            m("a", { href: objectViewHref("olio.charPerson", ch.objectId), target: "_blank", rel: "noopener", class: link, title: "Open " + (ch.name || nm), "data-testid": "chat-char-link-" + role }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "person"), nm
            ]),
            ap ? m("a", { href: objectViewHref("olio.apparel", ap.objectId), target: "_blank", rel: "noopener", class: link, title: "Open outfit: " + ap.name, "data-testid": "chat-outfit-link-" + role },
                m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "checkroom")) : null
        ]);
    }
    return m("div", { class: "hidden sm:flex items-center gap-3 min-w-0" }, [one("system"), one("user")]);
}

function renderPortraitHeader() {
    if (!chatCfg.system && !chatCfg.user) return null;

    function charPortrait(char) {
        if (!char) return null;
        if (char.profile && char.profile.portrait) {
            let pp = char.profile.portrait;
            let thumbUrl = applicationPath + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/256x256";
            return m("div", { class: "flex flex-col items-center" }, [
                m("img", {
                    src: thumbUrl,
                    class: "rounded-lg shadow cursor-pointer hover:opacity-90",
                    style: "width:120px;height:120px;object-fit:cover",
                    onclick: function() { page.imageView(pp); }
                }),
                m("span", { class: "text-xs text-gray-500 dark:text-gray-400 mt-1 truncate max-w-[120px]" }, char.name || "")
            ]);
        }
        let g = (char.gender === "female") ? "woman" : "man";
        return m("div", { class: "flex flex-col items-center" }, [
            m("span", { class: "material-icons-outlined text-gray-400", style: "font-size:48px" }, g),
            m("span", { class: "text-xs text-gray-500 dark:text-gray-400 mt-1" }, char.name || "")
        ]);
    }

    if (!showPortraits) return null;

    return m("div", { class: "flex justify-around items-end px-4 py-3 bg-gray-50 dark:bg-gray-900/50 border-b border-gray-200 dark:border-gray-700" }, [
        charPortrait(chatCfg.system),
        charPortrait(chatCfg.user)
    ]);
}

function renderPendingIndicator() {
    // Show pending when waiting for response AND no stream text has arrived yet.
    // Once streamText is non-empty, renderStreamingMessage takes over.
    if (!chatCfg.pending) return null;
    if (chatCfg.streaming && chatCfg.streamText) return null;
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

let configAccordionOpen = false;

function renderConfigAccordion() {
    if (!inst) return null;
    let chatConfigName = chatCfg && chatCfg.chat ? chatCfg.chat.name : null;
    let promptConfigName = null;
    let promptTemplateName = null;
    if (inst.api.promptConfig) {
        let pc = inst.api.promptConfig();
        if (pc && pc.name) promptConfigName = pc.name;
    }
    if (inst.api.promptTemplate) {
        let pt = inst.api.promptTemplate();
        if (pt && pt.name) promptTemplateName = pt.name;
    }
    return m("div", { class: "border-t border-gray-200 dark:border-gray-600" }, [
        m("button", {
            class: "w-full text-xs flex items-center gap-1 px-2 py-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700",
            onclick: function () { configAccordionOpen = !configAccordionOpen; }
        }, [
            m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "settings"),
            " Config",
            m("span", { class: "material-symbols-outlined ml-auto", style: "font-size:14px" }, configAccordionOpen ? "expand_less" : "expand_more")
        ]),
        configAccordionOpen ? m("div", { class: "px-3 py-2 space-y-1" }, [
            configRow("Chat Config", chatConfigName, "settings", "chatConfig"),
            configRow("Prompt", promptConfigName || promptTemplateName, "edit_note", "promptTemplate")
            // Model and rating moved to the LLM Debug drawer (renderDebugDrawer / LLMDebugPanel.SessionView).
        ]) : null
    ]);
}

function configRow(label, name, icon, libraryType) {
    function openConfigPicker(e) {
        e.stopPropagation();
        ObjectPicker.openLibrary({
            libraryType: libraryType,
            title: "Select " + label,
            onSelect: function (obj) {
                ContextPanel.attach(libraryType, obj.objectId).then(function () {
                    am7client.clearCache("olio.llm.chatRequest");
                    doPeek();
                }).catch(function (e) { console.warn("Config attach failed:", e); });
                m.redraw();
            }
        }).catch(function(err) {
            console.error("[configRow] Picker open failed:", err);
            page.toast("error", "Failed to open picker: " + (err.message || err));
        });
    }
    return m("div", {
        class: "flex items-center gap-1 text-xs cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800 rounded px-1 py-0.5",
        onclick: openConfigPicker,
        title: name || "None"
    }, [
        m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:14px" }, icon),
        m("span", { class: "text-gray-500 dark:text-gray-400" }, label + ":"),
        m("span", { class: "text-blue-600 dark:text-blue-400 truncate max-w-[120px]" }, name || "None")
    ]);
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
        panelContent = m("div", { class: "flex flex-col h-full" }, [
            m("div", { class: "flex-1 overflow-y-auto min-h-0" }, [
                m(ConversationManager.SidebarView)
            ]),
            renderConfigAccordion()
        ]);
    } else if (sidebarActivePanel === "context") {
        panelContent = m(ContextPanel.PanelView);
    } else if (sidebarActivePanel === "memories") {
        panelContent = m(MemoryPanel.PanelView);
    }

    return m("div", { class: "flex h-full" }, [
        iconStrip,
        m("div", { class: "w-64 border-r border-gray-200 dark:border-gray-700 overflow-y-auto bg-white dark:bg-gray-900", style: "contain: layout style;" }, [
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
    let sessionMeta = inst ? ConversationManager.getSelectedSession() : null;
    let sessionTitle = null;
    let sessionIcon = null;
    if (sessionMeta) {
        sessionTitle = sessionMeta.chatTitle || sessionMeta.name || "Chat";
        sessionIcon = sessionMeta.chatIcon || null;
    } else if (inst) {
        sessionTitle = inst.api.name ? inst.api.name() : "Chat";
    } else {
        sessionTitle = "No session";
    }
    let bgActivity = LLMConnector.bgActivity;

    return m("div", { class: "flex items-center justify-between px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 shrink-0" }, [
        m("div", { class: "flex items-center gap-3" }, [
            sessionIcon ? (sessionIcon.match(/^[a-z0-9_]+$/)
                ? m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:18px" }, sessionIcon)
                : m("span", { style: "font-size:16px; line-height:1;" }, sessionIcon)
            ) : null,
            m("h2", { class: "text-sm font-semibold text-gray-800 dark:text-white truncate max-w-xs" }, sessionTitle),
            inst ? renderCharacterLinks() : null,
            bgActivity && bgActivity.icon ? m("span", {
                class: "material-symbols-outlined text-blue-500 animate-spin",
                style: "font-size:16px",
                title: bgActivity.label || ""
            }, bgActivity.icon) : null
        ]),
        m("div", { class: "flex items-center gap-1", "data-chat-toolbar": "actions" }, [
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
            // Scene gallery (with Generate button inside; auto-opens generator when empty)
            inst ? m("div", { class: "relative" }, [
                m("button", {
                    class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                    title: "Scene Gallery",
                    onclick: function() {
                        SceneGenerator.openSceneGallery(inst.api.objectId(), chatCfg, function(result) {
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
            // Export, portraits, fullscreen, LLM Debug, clear history and delete session live in the overflow
            m("button", {
                class: "p-1.5 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800",
                title: "More chat actions",
                "aria-haspopup": "menu",
                "data-testid": "chat-overflow",
                onclick: openChatOverflow
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "more_vert"))
        ])
    ]);
}

/**
 * The chat toolbar's overflow (components/contextMenu.js items, same pattern as the PB2 reader's Tools
 * overflow). Each item keeps the title its old toolbar button had, so existing selectors still find it once
 * the menu is open. Exported for Vitest.
 */
export function chatOverflowItems() {
    let items = [];
    if (inst && chatCfg.history.messages && chatCfg.history.messages.length > 0) {
        items.push({ label: "Export chat", icon: "download", attrs: { "data-testid": "chat-export", title: "Export chat" }, action: function() {
            let name = inst.api.name ? inst.api.name() : "chat";
            ChatExport.exportChat(chatCfg.history, name, "markdown", chatCfg);
        } });
    }
    if (chatCfg.system || chatCfg.user) {
        items.push({ label: showPortraits ? "Hide portraits" : "Show portraits", icon: "portrait",
            attrs: { "data-testid": "chat-portraits", title: showPortraits ? "Hide portraits" : "Show portraits" },
            action: function() { showPortraits = !showPortraits; m.redraw(); } });
    }
    items.push({ label: fullMode ? "Exit fullscreen" : "Fullscreen", icon: fullMode ? "close_fullscreen" : "open_in_full",
        attrs: { "data-testid": "chat-fullscreen", title: fullMode ? "Exit fullscreen" : "Fullscreen" },
        action: function() { fullMode = !fullMode; m.redraw(); } });
    if (inst) {
        items.push({ label: debugDrawerOpen ? "Hide LLM Debug" : "LLM Debug", icon: "bug_report",
            attrs: { "data-testid": "chat-llm-debug", title: debugDrawerOpen ? "Hide the LLM debug drawer" : "Show the LLM debug drawer for this chat" },
            action: function() { debugDrawerOpen = !debugDrawerOpen; m.redraw(); } });
        items.push({ divider: true });
        items.push({ label: "Clear history", icon: "delete_sweep", attrs: { "data-testid": "chat-clear-history", title: "Clear history" }, action: confirmClearHistory });
        items.push({ label: "Delete session", icon: "delete", attrs: { "data-testid": "chat-delete-session", title: "Delete session" }, action: doDelete });
    }
    return items;
}

/** Open the overflow under its button (keyboard activation has no pointer position). */
function openChatOverflow(e) {
    let r = e && e.currentTarget && e.currentTarget.getBoundingClientRect ? e.currentTarget.getBoundingClientRect() : null;
    let at = r ? { clientX: Math.max(8, r.right - 180), clientY: r.bottom + 4 } : { clientX: e ? e.clientX : 0, clientY: e ? e.clientY : 0 };
    contextMenu.show({
        preventDefault: function () { if (e && e.preventDefault) e.preventDefault(); },
        stopPropagation: function () { if (e && e.stopPropagation) e.stopPropagation(); },
        clientX: at.clientX, clientY: at.clientY
    }, chatOverflowItems());
}

// ── LLM Debug drawer ────────────────────────────────────────────────

/// What LLMDebugPanel.SessionView shows for the active chat; the callbacks drive this view's own state.
function sessionDebugInfo() {
    if (!inst) return null;
    let meta = ConversationManager.getSelectedSession() || {};
    let pc = inst.api.promptConfig ? inst.api.promptConfig() : null;
    let pt = inst.api.promptTemplate ? inst.api.promptTemplate() : null;
    let cc = chatCfg.chat;
    let ctx = ContextPanel.getData();
    let session = inst.entity ? inst.entity.session : null;
    return {
        sessionId: inst.api.objectId(),
        requestId: session && session.objectId ? session.objectId : null,
        name: meta.chatTitle || meta.name || (inst.api.name ? inst.api.name() : null),
        model: cc && cc.model ? cc.model : null,
        rating: cc && cc.rating ? String(cc.rating).toUpperCase() : null,
        chatConfigName: cc && cc.name ? cc.name : null,
        promptConfigName: pc && pc.name ? pc.name : null,
        promptTemplateName: pt && pt.name ? pt.name : null,
        contextType: meta.contextType || null,
        hideThoughts: hideThoughts,
        onToggleThoughts: function() { hideThoughts = !hideThoughts; m.redraw(); },
        showMcp: showMcpContext,
        onToggleMcp: function() { showMcpContext = !showMcpContext; clearFormattedCache(); m.redraw(); },
        summarizing: !!(ctx && ctx.summarizing),
        contextRefs: ctx && ctx.contextRefs ? ctx.contextRefs : [],
        onCancelSummarize: function(objectId) { ContextPanel.cancelSummarize(objectId); },
        onRetrySummarize: function(objectId) { ContextPanel.retrySummarize(objectId); },
        canExtract: !!MemoryPanel.getChatRequestObjectId(),
        extracting: MemoryPanel.isExtracting(),
        onExtractMemories: function() { MemoryPanel.forceExtract(); }
    };
}

function renderDebugDrawer(info) {
    if (!debugDrawerOpen || !info) return null;
    return m("div", {
        class: "px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-900/60 max-h-72 overflow-y-auto shrink-0",
        "data-testid": "chat-llm-debug-drawer"
    }, [
        m("div", { class: "flex items-center gap-2 mb-2" }, [
            m("span", { class: "material-symbols-outlined text-gray-500", style: "font-size:16px" }, "bug_report"),
            m("span", { class: "text-xs font-semibold text-gray-700 dark:text-gray-200 flex-1" }, "LLM Debug — this chat"),
            m("button", {
                class: "p-0.5 rounded text-gray-500 hover:bg-gray-200 dark:hover:bg-gray-700",
                title: "Close LLM debug drawer",
                onclick: function() { debugDrawerOpen = false; m.redraw(); }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "close"))
        ]),
        m(LLMDebugPanel.SessionView, { info: info })
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
let newSessionSelectedChatConfig = null;
let newSessionSelectedPromptConfig = null;
let newSessionSelectedPromptTemplate = null;

async function loadNewSessionDefaults() {
    // List chat configs from library dir and pick first (prefer "Open Chat")
    if (!newSessionSelectedChatConfig) {
        try {
            let dir = await LLMConnector.getLibraryGroup("chat");
            if (dir && dir.objectId) {
                let cfgs = await page.listObjects("olio.llm.chatConfig", dir.objectId, "name,objectId", 0, 50);
                if (cfgs && cfgs.length) {
                    let openChat = cfgs.find(function(c) { return c.name === "Open Chat"; });
                    newSessionSelectedChatConfig = openChat || cfgs[0];
                }
            }
        } catch(e) { /* ignore */ }
        // Fall back to user's own configs
        if (!newSessionSelectedChatConfig) {
            let userCfgs = ConversationManager.getChatConfigs();
            if (userCfgs && userCfgs.length > 0) newSessionSelectedChatConfig = userCfgs[0];
        }
    }

    // List prompt templates from library dir, then fall back to prompt configs
    if (!newSessionSelectedPromptTemplate && !newSessionSelectedPromptConfig) {
        try {
            let dir = await LLMConnector.getLibraryGroup("promptTemplate");
            if (dir && dir.objectId) {
                let tpls = await page.listObjects("olio.llm.promptTemplate", dir.objectId, "name,objectId", 0, 50);
                if (tpls && tpls.length) {
                    newSessionSelectedPromptTemplate = tpls[0];
                }
            }
        } catch(e) { /* ignore */ }
        if (!newSessionSelectedPromptTemplate) {
            try {
                let dir = await LLMConnector.getLibraryGroup("prompt");
                if (dir && dir.objectId) {
                    let pcs = await page.listObjects("olio.llm.promptConfig", dir.objectId, "name,objectId", 0, 50);
                    if (pcs && pcs.length) {
                        newSessionSelectedPromptConfig = pcs[0];
                    }
                }
            } catch(e) { /* ignore */ }
        }
    }

    m.redraw();
}

function randomSessionName() {
    let a = ["turtle", "bunny", "kitty", "puppy", "duckling", "pony", "fishy", "birdie"];
    let b = ["fluffy", "cute", "ornery", "obnoxious", "scratchy", "licky", "cuddly", "mangy"];
    let c = ["little", "tiny", "enormous", "big", "skinny", "lumpy"];
    return b[Math.floor(Math.random() * b.length)] + " " + c[Math.floor(Math.random() * c.length)] + " " + a[Math.floor(Math.random() * a.length)];
}

async function updateNewSessionDefaultName() {
    let cc = newSessionSelectedChatConfig;
    let pc = newSessionSelectedPromptConfig;
    let pt = newSessionSelectedPromptTemplate;
    if (!cc) { newSessionName = randomSessionName(); m.redraw(); return; }

    // Load full chat config to get character names and rating
    let fullCc = null;
    try {
        fullCc = await am7client.getFull("olio.llm.chatConfig", cc.objectId);
    } catch(e) { /* ignore */ }

    let name = "";
    if (fullCc && fullCc.systemCharacter && fullCc.userCharacter) {
        let sysName = fullCc.systemCharacter.firstName || (fullCc.systemCharacter.name || "").split(" ")[0];
        let usrName = fullCc.userCharacter.firstName || (fullCc.userCharacter.name || "").split(" ")[0];
        let rating = (fullCc.rating || "").toUpperCase();
        name = sysName + " and " + usrName;
        if (rating) name += " (" + rating + ")";
    } else {
        name = randomSessionName();
    }

    // Append prompt info
    let promptName = pt ? pt.name : (pc ? pc.name : null);
    if (promptName) name += " - " + promptName;

    // Dedup against existing sessions
    let existing = ConversationManager.getSessions() || [];
    let baseName = name;
    let counter = 1;
    while (existing.some(function(s) { return s.name === name; })) {
        counter++;
        name = baseName + " " + counter;
    }

    newSessionName = name;
    m.redraw();
}

function pickerField(label, selected, libraryType, onSelect) {
    let displayName = selected ? (selected.name || selected.objectId) : "(none)";
    function openPicker() {
        ObjectPicker.openLibrary({
            libraryType: libraryType,
            title: "Select " + label,
            onSelect: function(item) {
                onSelect(item);
                m.redraw();
            }
        });
    }
    return m("div", { class: "mb-3" }, [
        m("label", { class: "block text-xs text-gray-500 dark:text-gray-400 mb-1" }, label),
        m("div", { class: "flex items-center gap-1" }, [
            m("span", {
                class: "flex-1 text-sm text-gray-800 dark:text-white truncate cursor-pointer hover:text-blue-600 dark:hover:text-blue-400",
                onclick: openPicker
            }, displayName),
            m("button", {
                class: "p-1.5 rounded hover:bg-gray-100 dark:hover:bg-gray-700",
                title: "Find",
                onclick: openPicker
            }, m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:18px" }, "search")),
            selected ? m("button", {
                class: "p-1.5 rounded hover:bg-gray-100 dark:hover:bg-gray-700",
                title: "Clear",
                onclick: function(e) { e.stopPropagation(); onSelect(null); m.redraw(); }
            }, m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:18px" }, "backspace")) : null
        ])
    ]);
}

function renderNewSessionDialog() {
    if (!showNewSession) return null;
    return m("div", { class: "fixed inset-0 z-50 flex items-center justify-center" }, [
        m("div", { class: "absolute inset-0 bg-black/50", onclick: function() { showNewSession = false; m.redraw(); } }),
        m("div", { class: "relative bg-white dark:bg-gray-900 rounded-lg shadow-xl w-full max-w-md mx-4 p-4" }, [
            m("h3", { class: "text-lg font-semibold text-gray-800 dark:text-white mb-4" }, "New Chat Session"),
            m("div", { class: "mb-3" }, [
                m("label", { class: "block text-xs text-gray-500 dark:text-gray-400 mb-1" }, "Session Name"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-sm",
                    placeholder: "Session name",
                    value: newSessionName,
                    oninput: function(e) { newSessionName = e.target.value; }
                })
            ]),
            pickerField("Chat Config", newSessionSelectedChatConfig, "chatConfig", function(item) {
                newSessionSelectedChatConfig = item;
                updateNewSessionDefaultName();
            }),
            pickerField("Prompt Config", newSessionSelectedPromptConfig, "promptConfig", function(item) {
                newSessionSelectedPromptConfig = item;
                updateNewSessionDefaultName();
            }),
            pickerField("Prompt Template", newSessionSelectedPromptTemplate, "promptTemplate", function(item) {
                newSessionSelectedPromptTemplate = item;
                updateNewSessionDefaultName();
            }),
            m("div", { class: "flex justify-end gap-2 mt-4" }, [
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-800",
                    onclick: function() { showNewSession = false; m.redraw(); }
                }, "Cancel"),
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500",
                    disabled: !newSessionSelectedChatConfig || (!newSessionSelectedPromptConfig && !newSessionSelectedPromptTemplate),
                    onclick: async function() {
                        if (!newSessionName.trim()) return;
                        showNewSession = false;
                        m.redraw(); // Close dialog immediately
                        try {
                            let req = await ConversationManager.createSession(
                                newSessionName.trim(),
                                newSessionSelectedChatConfig,
                                newSessionSelectedPromptConfig,
                                newSessionSelectedPromptTemplate
                            );
                            if (req) {
                                // Refresh list and select new session — single redraw at end.
                                // selectSession (not a bare pickSession) also records the selected
                                // objectId, which the toolbar title and the sidebar highlight read.
                                await ConversationManager.refresh();
                                ConversationManager.selectSession(req);
                            }
                        } catch(e) {
                            page.toast("error", "Failed to create session");
                        }
                        newSessionName = "";
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
                onclick: async function() {
                    showNewSession = true;
                    newSessionSelectedChatConfig = null;
                    newSessionSelectedPromptConfig = null;
                    newSessionSelectedPromptTemplate = null;
                    newSessionName = "";
                    m.redraw();
                    await loadNewSessionDefaults();
                    await updateNewSessionDefaultName();
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
        let debugInfo = sessionDebugInfo();
        if (debugInfo) LLMDebugPanel.setLastChatSession(debugInfo);
        return m("div", { class: "flex flex-1 w-full bg-white dark:bg-gray-950 min-h-0" }, [
            // Sidebar (hidden in fullscreen)
            fullMode ? null : renderSidebar(),
            // Main chat area
            m("div", { class: "flex-1 flex flex-col min-w-0" }, [
                renderToolbar(),
                renderDebugDrawer(debugInfo),
                m(ChainManager.ProgressView),
                // Portrait header (Ux7 style)
                inst ? renderPortraitHeader() : null,
                // Messages area
                inst ? m("div", {
                    class: "flex-1 overflow-y-auto p-4",
                    style: "contain: layout style;",
                    oncreate: function(vnode) { scrollToBottom(vnode.dom, true); },
                    onupdate: function(vnode) { scrollToBottom(vnode.dom); }
                }, [
                    renderMessages(),
                    renderStreamingMessage(),
                    renderPendingIndicator()
                ]) : renderEmptyState(),
                renderInput()
            ]),
            // Overlays
            m(ChatSetupWizard.WizardView),
            m(ObjectPicker.PickerView),
            renderNewSessionDialog()
        ]);
    }
};

let _scrollRaf = 0;
let _scrollLastMsgCount = 0;
let _scrollLastStreamLen = 0;
function scrollToBottom(el, force) {
    if (!el) return;
    // Only scroll when content actually changed (new messages or streaming growth)
    let msgCount = chatCfg.history.messages ? chatCfg.history.messages.length : 0;
    let streamLen = chatCfg.streamText ? chatCfg.streamText.length : 0;
    if (!force && msgCount === _scrollLastMsgCount && streamLen === _scrollLastStreamLen) return;
    _scrollLastMsgCount = msgCount;
    _scrollLastStreamLen = streamLen;
    if (_scrollRaf) cancelAnimationFrame(_scrollRaf);
    _scrollRaf = requestAnimationFrame(function() {
        _scrollRaf = 0;
        if (el) el.scrollTop = el.scrollHeight;
    });
}

// Click delegation for inline chat images (rendered via m.trust with data-token-key)
if (typeof document !== 'undefined') {
    document.addEventListener('click', function(e) {
        let img = e.target.closest('img[data-token-key]');
        if (img && img.src) {
            // Extract the full-size URL from the thumbnail URL (remove size suffix)
            let src = img.src;
            let fullSrc = src.replace(/\/\d+x\d+$/, '');
            // Open in image viewer dialog
            let Dialog = page.components.dialog;
            if (Dialog) {
                Dialog.open({
                    title: img.alt || 'Image',
                    size: 'lg',
                    content: m('div', { class: 'flex flex-col items-center' }, [
                        m('img', { src: fullSrc, class: 'max-w-full max-h-[70vh] rounded shadow', style: 'object-fit:contain' })
                    ]),
                    closable: true,
                    actions: [{ label: 'Close', icon: 'close', onclick: function() { Dialog.close(); } }]
                });
                m.redraw();
            }
        }
    });
}

async function initChatView() {
    // Wire conversation manager callbacks
    ConversationManager.onSelect(pickSession);
    ConversationManager.onDelete(function(obj) {
        // Session already deleted and list refreshed by ConversationManager —
        // always clear the active view, then auto-select next session
        doClear();
        ConversationManager.autoSelectFirst();
        m.redraw();
    });

    // Wire new session button in ConversationManager
    ConversationManager.onNewSession(async function() {
        showNewSession = true;
        newSessionSelectedChatConfig = null;
        newSessionSelectedPromptConfig = null;
        newSessionSelectedPromptTemplate = null;
        newSessionName = "";
        m.redraw();
        await loadNewSessionDefaults();
        await updateNewSessionDefaultName();
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

/// Exported for Vitest (src/test/chatToolbarOverflow.test.js): select a session and render the toolbar /
/// debug drawer without mounting the whole route (whose oninit needs the chat library on the server).
export { pickSession, renderToolbar, renderDebugDrawer, sessionDebugInfo, confirmClearHistory };

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/chat": {
        oninit: function() { chatView.oninit(); },
        onremove: function() { chatView.onremove(); },
        view: function() {
            return layout(
                m("div", { class: "app-shell" }, [
                    page.components.navigation ? m(page.components.navigation) : null,
                    m("div", { style: "flex:1;display:flex;overflow:hidden;min-height:0" }, [
                        chatView.view()
                    ])
                ])
            );
        }
    }
};
