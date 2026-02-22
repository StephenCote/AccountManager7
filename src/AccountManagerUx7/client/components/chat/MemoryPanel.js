/**
 * MemoryPanel — Sidebar component for memory browsing, search, and creation
 * Phase 13f item 23: Collapsible panel showing memory state for current character pair.
 * Phase 14: Manual memory creation, CSS readability fixes.
 *
 * Resolves: OI-69, OI-73
 *
 * Exposes: window.MemoryPanel
 */
(function() {
    "use strict";

    // ── State ────────────────────────────────────────────────────────────

    let expanded = true;
    let memories = [];
    let memoryCount = 0;
    let loading = false;
    let filterQuery = "";
    let expandedMemoryId = null;
    let currentConfig = null;
    let chatRequestObjectId = null;
    let extracting = false;
    let showCreateForm = false;
    let createForm = { content: "", summary: "", memoryType: "NOTE", importance: 5 };

    // Phase 3 (chatRefactor2): Cross-conversation memory browsing
    let viewMode = "pair";        // "pair" | "sysChar" | "usrChar"
    let characterMemories = [];
    let characterLoading = false;

    // ── Memory type icons (Material Symbols) ─────────────────────────────

    let typeIcons = {
        "OUTCOME":       "flag",
        "RELATIONSHIP":  "favorite",
        "FACT":          "notes",
        "NOTE":          "notes",
        "INSIGHT":       "lightbulb",
        "DECISION":      "gavel",
        "DISCOVERY":     "explore",
        "BEHAVIOR":      "psychology",
        "EMOTION":       "mood",
        "ERROR_LESSON":  "warning"
    };

    let typeOptions = ["NOTE", "FACT", "RELATIONSHIP", "EMOTION", "DECISION", "DISCOVERY", "INSIGHT", "OUTCOME"];

    function getTypeIcon(memoryType) {
        return typeIcons[memoryType] || "notes";
    }

    // ── Data Loading ─────────────────────────────────────────────────────

    async function loadForPair(person1ObjectId, person2ObjectId) {
        if (!person1ObjectId || !person2ObjectId) {
            memories = [];
            memoryCount = 0;
            return;
        }
        loading = true;
        m.redraw();
        try {
            let result = await m.request({
                method: 'GET',
                url: am7client.base() + "/memory/pair/" + person1ObjectId + "/" + person2ObjectId + "/50",
                withCredentials: true
            });
            if (result && Array.isArray(result)) {
                memories = result;
                memoryCount = result.length;
            } else {
                memories = [];
                memoryCount = 0;
            }
        } catch(e) {
            /// 404 is normal — no memories yet or characters not resolved
            if (e && e.code !== 404) {
                console.warn("[MemoryPanel] Failed to load pair memories:", e);
            }
            memories = [];
            memoryCount = 0;
        }
        loading = false;
        m.redraw();
    }

    async function loadCount(person1ObjectId, person2ObjectId) {
        if (!person1ObjectId || !person2ObjectId) {
            memoryCount = 0;
            return;
        }
        try {
            let result = await m.request({
                method: 'GET',
                url: am7client.base() + "/memory/count/" + person1ObjectId + "/" + person2ObjectId,
                withCredentials: true
            });
            if (typeof result === "number") {
                memoryCount = result;
            }
        } catch(e) {
            // Silently fail count
        }
    }

    /// Client-side filter: match filterQuery against memory content, summary, or type
    function filterMemoryList(list) {
        if (!filterQuery || filterQuery.trim().length < 2) return list;
        let q = filterQuery.trim().toLowerCase();
        return list.filter(function(mem) {
            let content = (mem.content || "").toLowerCase();
            let summary = (mem.summary || "").toLowerCase();
            let mtype = (mem.memoryType || "").toLowerCase();
            return content.indexOf(q) >= 0 || summary.indexOf(q) >= 0 || mtype.indexOf(q) >= 0;
        });
    }

    // Phase 3 (chatRefactor2): Load all memories for a character across conversations
    async function loadForCharacter(personObjectId) {
        if (!personObjectId) {
            characterMemories = [];
            return;
        }
        characterLoading = true;
        m.redraw();
        try {
            let result = await m.request({
                method: 'GET',
                url: am7client.base() + "/memory/person/" + personObjectId + "/50",
                withCredentials: true
            });
            characterMemories = (result && Array.isArray(result)) ? result : [];
        } catch(e) {
            console.warn("[MemoryPanel] Failed to load character memories:", e);
            characterMemories = [];
        }
        characterLoading = false;
        m.redraw();
    }

    // Build the person foreign key payload for memory create requests
    function buildPersonPayload() {
        if (!currentConfig) return null;
        let sys = currentConfig.systemCharacter;
        let usr = currentConfig.userCharacter;
        if (!sys || !usr || !sys.objectId || !usr.objectId) return null;
        let pModel = sys.schema || "olio.charPerson";
        return {
            person1Model: pModel,
            person1: { objectId: sys.objectId },
            person2Model: pModel,
            person2: { objectId: usr.objectId },
            conversationId: currentConfig.objectId || null
        };
    }

    // Phase 3 (chatRefactor2): Share a memory from system character view to current pair
    async function shareMemoryToCurrentPair(mem) {
        if (!mem || !currentConfig) {
            page.toast("warn", "No active chat session");
            return;
        }
        let pp = buildPersonPayload();
        if (!pp) {
            page.toast("warn", "Character pair not available");
            return;
        }
        try {
            let body = Object.assign({
                content: mem.content || "",
                summary: mem.summary || null,
                memoryType: mem.memoryType || "NOTE",
                importance: mem.importance || 5
            }, pp);
            await m.request({
                method: 'POST',
                url: am7client.base() + "/memory/create",
                withCredentials: true,
                body: body
            });
            page.toast("success", "Memory shared to current pair");
            let sys = currentConfig.systemCharacter;
            let usr = currentConfig.userCharacter;
            if (sys && usr && sys.objectId && usr.objectId) {
                await loadForPair(sys.objectId, usr.objectId);
            }
        } catch(e) {
            page.toast("error", "Failed to share memory");
        }
        m.redraw();
    }

    // Inject a memory as MCP context into the user's next message
    function injectMemoryToChat(mem) {
        if (!mem || !mem.content) {
            page.toast("warn", "Memory has no content");
            return;
        }
        let memType = mem.memoryType || "NOTE";
        let summary = mem.summary || "";
        let mcpBlock = "<mcp:context uri=\"am7://memory/" + (mem.objectId || "injected") + "\" type=\"urn:am7:memory:" + memType.toLowerCase() + "\">\n"
            + (summary ? summary + ": " : "") + mem.content + "\n"
            + "</mcp:context>";
        let inputEl = document.querySelector("[name='chatmessage']");
        if (inputEl) {
            let existing = inputEl.value.trim();
            inputEl.value = existing ? existing + "\n" + mcpBlock : mcpBlock;
            inputEl.focus();
            page.toast("info", "Memory added to message");
        } else {
            page.toast("warn", "Chat input not found");
        }
    }

    async function deleteMemory(mem) {
        if (!mem || !mem.objectId) return;
        try {
            await m.request({
                method: 'DELETE',
                url: am7client.base() + "/memory/" + mem.objectId,
                withCredentials: true
            });
            memories = memories.filter(function(m2) { return m2.objectId !== mem.objectId; });
            memoryCount = Math.max(0, memoryCount - 1);
            page.toast("success", "Memory deleted");
        } catch(e) {
            page.toast("error", "Failed to delete memory");
        }
        m.redraw();
    }

    async function forceExtract() {
        if (!chatRequestObjectId) {
            page.toast("warn", "No active chat session");
            return;
        }
        extracting = true;
        let lockToken = 0;
        if (window.LLMConnector) {
            lockToken = LLMConnector.lockBgActivity();
            LLMConnector.setBgActivity("neurology", "Forming memories\u2026");
        }
        m.redraw();
        try {
            let result = await m.request({
                method: 'POST',
                url: am7client.base() + "/memory/extract/" + chatRequestObjectId,
                withCredentials: true
            });
            let count = (result && Array.isArray(result)) ? result.length : 0;
            page.toast("info", count > 0 ? "Extracted " + count + " memories" : "No new memories extracted", 3000);
            // Refresh memories to show new entries
            if (currentConfig) {
                let sys = currentConfig.systemCharacter;
                let usr = currentConfig.userCharacter;
                if (sys && usr && sys.objectId && usr.objectId) {
                    await loadForPair(sys.objectId, usr.objectId);
                }
                // Also refresh character view if active
                if (viewMode === "sysChar" && sys && sys.objectId) {
                    await loadForCharacter(sys.objectId);
                } else if (viewMode === "usrChar" && usr && usr.objectId) {
                    await loadForCharacter(usr.objectId);
                }
            }
        } catch(e) {
            page.toast("error", "Memory extraction failed");
            console.warn("[MemoryPanel] forceExtract error:", e);
        }
        extracting = false;
        if (window.LLMConnector) {
            LLMConnector.unlockBgActivity(lockToken);
            LLMConnector.setBgActivity(null, null);
        }
        m.redraw();
    }

    async function createMemoryFromForm() {
        if (!createForm.content || createForm.content.trim().length < 3) {
            page.toast("warn", "Memory content is required");
            return;
        }
        let pp = buildPersonPayload();
        if (!pp) {
            page.toast("warn", "No active chat session or character pair not available");
            return;
        }

        loading = true;
        m.redraw();
        try {
            let body = Object.assign({
                content: createForm.content.trim(),
                summary: createForm.summary.trim() || null,
                memoryType: createForm.memoryType,
                importance: createForm.importance
            }, pp);
            await m.request({
                method: 'POST',
                url: am7client.base() + "/memory/create",
                withCredentials: true,
                body: body
            });
            page.toast("success", "Memory created");
            createForm = { content: "", summary: "", memoryType: "NOTE", importance: 5 };
            showCreateForm = false;
            let sys = currentConfig.systemCharacter;
            let usr = currentConfig.userCharacter;
            if (sys && usr && sys.objectId && usr.objectId) {
                await loadForPair(sys.objectId, usr.objectId);
            }
        } catch(e) {
            page.toast("error", "Failed to create memory");
        }
        loading = false;
        m.redraw();
    }

    // ── Views ────────────────────────────────────────────────────────────

    function memoryItemView(mem) {
        let isExpanded = expandedMemoryId === mem.objectId;
        let memType = mem.memoryType || "NOTE";
        let icon = getTypeIcon(memType);
        let summary = mem.summary || (mem.content ? mem.content.substring(0, 60) + "..." : "\u2014");
        let importance = mem.importance || 0;

        return m("div", {
            key: mem.objectId,
            class: "memory-item"
        }, [
            m("div", {
                class: "memory-item-row group",
                onclick: function() {
                    expandedMemoryId = isExpanded ? null : mem.objectId;
                }
            }, [
                m("span", {
                    class: "material-symbols-outlined memory-type-icon",
                    title: memType
                }, icon),
                m("span", { class: "memory-summary" }, summary),
                importance > 0 ? m("span", { class: "memory-importance" }, importance) : "",
                // Share/inject buttons visible in non-pair modes
                (viewMode === "sysChar") ? m("span", {
                    class: "material-symbols-outlined memory-share",
                    title: "Share to current pair",
                    onclick: function(e) {
                        e.stopPropagation();
                        shareMemoryToCurrentPair(mem);
                    }
                }, "share") : "",
                (viewMode === "usrChar") ? m("span", {
                    class: "material-symbols-outlined memory-share",
                    title: "Add to next message",
                    onclick: function(e) {
                        e.stopPropagation();
                        injectMemoryToChat(mem);
                    }
                }, "chat_add_on") : "",
                m("span", {
                    class: "material-symbols-outlined memory-delete",
                    title: "Delete",
                    onclick: function(e) {
                        e.stopPropagation();
                        deleteMemory(mem);
                    }
                }, "delete_outline")
            ]),
            isExpanded ? m("div", { class: "memory-detail" }, [
                m("div", { class: "memory-detail-type" }, memType),
                m("div", { class: "memory-detail-content" }, mem.content || "\u2014")
            ]) : ""
        ]);
    }

    function searchInputView() {
        return m("div", { class: "memory-search" }, [
            m("input", {
                type: "text",
                class: "memory-search-input",
                placeholder: "Filter memories...",
                value: filterQuery,
                oninput: function(e) { filterQuery = e.target.value; }
            })
        ]);
    }

    function createFormView() {
        if (!showCreateForm) return "";
        return m("div", { class: "memory-create-form" }, [
            m("textarea", {
                class: "memory-create-textarea",
                placeholder: "Memory content...",
                rows: 3,
                value: createForm.content,
                oninput: function(e) { createForm.content = e.target.value; }
            }),
            m("input", {
                type: "text",
                class: "memory-create-input",
                placeholder: "Summary (optional, auto-generated if blank)",
                value: createForm.summary,
                oninput: function(e) { createForm.summary = e.target.value; }
            }),
            m("div", { class: "memory-create-row" }, [
                m("select", {
                    class: "memory-create-select",
                    value: createForm.memoryType,
                    onchange: function(e) { createForm.memoryType = e.target.value; }
                }, typeOptions.map(function(t) {
                    return m("option", { value: t }, t);
                })),
                m("input", {
                    type: "number",
                    class: "memory-create-importance",
                    min: 1, max: 10,
                    value: createForm.importance,
                    oninput: function(e) { createForm.importance = parseInt(e.target.value) || 5; }
                }),
                m("button", {
                    class: "memory-create-btn",
                    onclick: createMemoryFromForm,
                    disabled: loading
                }, "Save"),
                m("button", {
                    class: "memory-cancel-btn",
                    onclick: function() { showCreateForm = false; }
                }, "Cancel")
            ])
        ]);
    }

    function memoryListView() {
        let list;
        let isLoading = loading;
        if (viewMode === "sysChar" || viewMode === "usrChar") {
            list = characterMemories;
            isLoading = characterLoading;
        } else {
            list = memories;
        }
        if (isLoading) {
            return m("div", { class: "memory-empty" }, "Loading...");
        }
        if (!list || list.length === 0) {
            let emptyMsg = (viewMode === "sysChar" || viewMode === "usrChar") ? "No character memories" : "No memories";
            return m("div", { class: "memory-empty" }, emptyMsg);
        }
        list = filterMemoryList(list);
        if (list.length === 0) {
            return m("div", { class: "memory-empty" }, "No matches");
        }
        return list.map(function(mem) { return memoryItemView(mem); });
    }

    // Phase 3 (chatRefactor2): View mode selector
    function viewModeSelector() {
        let sysName = currentConfig && currentConfig.systemCharacter ? (currentConfig.systemCharacter.firstName || "System") : "System";
        let usrName = currentConfig && currentConfig.userCharacter ? (currentConfig.userCharacter.firstName || "User") : "User";
        let modes = [
            { key: "pair", label: "Pair", icon: "people" },
            { key: "sysChar", label: sysName, icon: "smart_toy" },
            { key: "usrChar", label: usrName, icon: "person" }
        ];
        return m("div", { class: "memory-mode-selector" },
            modes.map(function(mode) {
                return m("button", {
                    class: "memory-mode-btn" + (viewMode === mode.key ? " active" : ""),
                    title: mode.label,
                    onclick: function() {
                        viewMode = mode.key;
                        if (mode.key === "sysChar" && currentConfig) {
                            let sys = currentConfig.systemCharacter;
                            if (sys && sys.objectId) {
                                loadForCharacter(sys.objectId);
                            }
                        } else if (mode.key === "usrChar" && currentConfig) {
                            let usr = currentConfig.userCharacter;
                            if (usr && usr.objectId) {
                                loadForCharacter(usr.objectId);
                            }
                        }
                    }
                }, [
                    m("span", {
                        class: "material-symbols-outlined",
                        style: "font-size: 14px;"
                    }, mode.icon)
                ]);
            })
        );
    }

    // ── Panel Component ──────────────────────────────────────────────────

    let PanelView = {
        view: function() {
            let badge = memoryCount > 0 ? " (" + memoryCount + ")" : "";
            return m("div", { class: "memory-panel" }, [
                // Header
                m("div", { class: "memory-header" }, [
                    m("button", {
                        class: "flyout-button memory-header-btn",
                        onclick: function() { expanded = !expanded; }
                    }, [
                        m("span", {
                            class: "material-symbols-outlined mr-2",
                            style: "font-size: 16px;"
                        }, "psychology"),
                        m("span", { class: "flex-1 text-left" }, "Memories" + badge),
                        m("span", {
                            class: "material-symbols-outlined",
                            style: "font-size: 16px;"
                        }, expanded ? "expand_less" : "expand_more")
                    ]),
                    expanded ? m("span", { class: "memory-header-actions" }, [
                        m("button", {
                            class: "memory-add-btn",
                            title: "Extract memories from conversation",
                            disabled: extracting,
                            onclick: forceExtract
                        }, [
                            m("span", {
                                class: "material-symbols-outlined" + (extracting ? " memory-spin" : ""),
                                style: "font-size: 16px;"
                            }, extracting ? "hourglass_top" : "auto_awesome")
                        ]),
                        m("button", {
                            class: "memory-add-btn",
                            title: "Create memory",
                            onclick: function() { showCreateForm = !showCreateForm; }
                        }, [
                            m("span", {
                                class: "material-symbols-outlined",
                                style: "font-size: 16px;"
                            }, "add_circle")
                        ])
                    ]) : ""
                ]),
                // Expanded content
                expanded ? m("div", { class: "memory-body" }, [
                    viewModeSelector(),
                    createFormView(),
                    searchInputView(),
                    memoryListView()
                ]) : ""
            ]);
        }
    };

    // ── Public API ───────────────────────────────────────────────────────

    let MemoryPanel = {

        PanelView: PanelView,

        view: function() { return m(PanelView); },

        setConfig: function(cfg) { currentConfig = cfg; },

        loadForPair: loadForPair,

        loadForCharacter: loadForCharacter,

        loadForSession: function(chatConfig, chatReqObjectId) {
            currentConfig = chatConfig;
            chatRequestObjectId = chatReqObjectId || null;
            if (!chatConfig) {
                memories = [];
                memoryCount = 0;
                m.redraw();
                return;
            }
            let sys = chatConfig.systemCharacter;
            let usr = chatConfig.userCharacter;
            if (sys && usr && sys.objectId && usr.objectId) {
                loadForPair(sys.objectId, usr.objectId);
            } else {
                memories = [];
                memoryCount = 0;
                m.redraw();
            }
        },

        getMemoryCount: function() {
            return memoryCount;
        },

        refresh: function() {
            if (currentConfig) {
                MemoryPanel.loadForSession(currentConfig);
            }
        }
    };

    // ── Export ───────────────────────────────────────────────────────────

    window.MemoryPanel = MemoryPanel;

    console.log("[MemoryPanel] loaded");
}());
