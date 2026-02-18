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

    let expanded = false;
    let memories = [];
    let memoryCount = 0;
    let loading = false;
    let searchQuery = "";
    let searchResults = null;
    let expandedMemoryId = null;
    let currentConfig = null;
    let showCreateForm = false;
    let createForm = { content: "", summary: "", memoryType: "NOTE", importance: 5 };

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

    async function doSearch() {
        if (!searchQuery || searchQuery.trim().length < 2) {
            searchResults = null;
            m.redraw();
            return;
        }
        loading = true;
        m.redraw();
        try {
            let result = await m.request({
                method: 'POST',
                url: am7client.base() + "/memory/search/20/0.5",
                withCredentials: true,
                body: searchQuery,
                headers: { "Content-Type": "text/plain" }
            });
            searchResults = (result && Array.isArray(result)) ? result : [];
        } catch(e) {
            console.warn("[MemoryPanel] Search failed:", e);
            searchResults = [];
        }
        loading = false;
        m.redraw();
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
            if (searchResults) {
                searchResults = searchResults.filter(function(m2) { return m2.objectId !== mem.objectId; });
            }
            page.toast("success", "Memory deleted");
        } catch(e) {
            page.toast("error", "Failed to delete memory");
        }
        m.redraw();
    }

    async function createMemoryFromForm() {
        if (!createForm.content || createForm.content.trim().length < 3) {
            page.toast("warn", "Memory content is required");
            return;
        }
        if (!currentConfig) {
            page.toast("warn", "No active chat session");
            return;
        }
        let sys = currentConfig.systemCharacter;
        let usr = currentConfig.userCharacter;
        if (!sys || !usr || !sys.objectId || !usr.objectId) {
            page.toast("warn", "Character pair not available");
            return;
        }

        loading = true;
        m.redraw();
        try {
            let body = {
                content: createForm.content.trim(),
                summary: createForm.summary.trim() || null,
                memoryType: createForm.memoryType,
                importance: createForm.importance,
                person1ObjectId: sys.objectId,
                person2ObjectId: usr.objectId,
                conversationId: currentConfig.objectId || null
            };
            await m.request({
                method: 'POST',
                url: am7client.base() + "/memory/create",
                withCredentials: true,
                body: body
            });
            page.toast("success", "Memory created");
            createForm = { content: "", summary: "", memoryType: "NOTE", importance: 5 };
            showCreateForm = false;
            // Reload memories
            if (sys.objectId && usr.objectId) {
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
                placeholder: "Search memories...",
                value: searchQuery,
                oninput: function(e) { searchQuery = e.target.value; },
                onkeydown: function(e) { if (e.key === "Enter") doSearch(); }
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
        let list = searchResults !== null ? searchResults : memories;
        if (loading) {
            return m("div", { class: "memory-empty" }, "Loading...");
        }
        if (!list || list.length === 0) {
            return m("div", { class: "memory-empty" },
                searchResults !== null ? "No search results" : "No memories"
            );
        }
        return list.map(function(mem) { return memoryItemView(mem); });
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
                    expanded ? m("button", {
                        class: "memory-add-btn",
                        title: "Create memory",
                        onclick: function() { showCreateForm = !showCreateForm; }
                    }, [
                        m("span", {
                            class: "material-symbols-outlined",
                            style: "font-size: 16px;"
                        }, "add_circle")
                    ]) : ""
                ]),
                // Expanded content
                expanded ? m("div", { class: "memory-body" }, [
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

        loadForSession: function(chatConfig) {
            currentConfig = chatConfig;
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
