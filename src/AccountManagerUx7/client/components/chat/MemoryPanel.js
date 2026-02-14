/**
 * MemoryPanel — Sidebar component for memory browsing and search
 * Phase 13f item 23: Collapsible panel showing memory state for current character pair.
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
        "ERROR_LESSON":  "warning"
    };

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
            console.warn("[MemoryPanel] Failed to load pair memories:", e);
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

    // ── Views ────────────────────────────────────────────────────────────

    function memoryItemView(mem) {
        let isExpanded = expandedMemoryId === mem.objectId;
        let memType = mem.memoryType || "NOTE";
        let icon = getTypeIcon(memType);
        let summary = mem.summary || (mem.content ? mem.content.substring(0, 60) + "..." : "—");
        let importance = mem.importance || 0;

        return m("div", {
            key: mem.objectId,
            class: "border-b border-gray-700 py-1 px-2 text-xs"
        }, [
            m("div", {
                class: "flex items-center gap-1 cursor-pointer hover:bg-gray-700/30 rounded px-1 py-0.5",
                onclick: function() {
                    expandedMemoryId = isExpanded ? null : mem.objectId;
                }
            }, [
                m("span", {
                    class: "material-symbols-outlined flex-shrink-0",
                    style: "font-size: 14px;",
                    title: memType
                }, icon),
                m("span", { class: "flex-1 truncate text-gray-300" }, summary),
                importance > 0 ? m("span", {
                    class: "flex-shrink-0 px-1 rounded text-[10px] bg-amber-500/20 text-amber-400"
                }, importance) : "",
                m("span", {
                    class: "material-symbols-outlined flex-shrink-0 opacity-0 group-hover:opacity-100 hover:text-red-400",
                    style: "font-size: 14px;",
                    title: "Delete",
                    onclick: function(e) {
                        e.stopPropagation();
                        deleteMemory(mem);
                    }
                }, "delete_outline")
            ]),
            isExpanded ? m("div", {
                class: "mt-1 p-2 bg-gray-800/50 rounded text-gray-400 text-[11px] whitespace-pre-wrap"
            }, mem.content || "—") : ""
        ]);
    }

    function searchInputView() {
        return m("div", { class: "px-2 py-1" }, [
            m("input", {
                type: "text",
                class: "text-field w-full text-xs",
                placeholder: "Search memories...",
                value: searchQuery,
                oninput: function(e) { searchQuery = e.target.value; },
                onkeydown: function(e) { if (e.key === "Enter") doSearch(); }
            })
        ]);
    }

    function memoryListView() {
        let list = searchResults !== null ? searchResults : memories;
        if (loading) {
            return m("div", { class: "px-3 py-2 text-gray-400 text-xs" }, "Loading...");
        }
        if (!list || list.length === 0) {
            return m("div", { class: "px-3 py-2 text-gray-400 text-xs" },
                searchResults !== null ? "No search results" : "No memories"
            );
        }
        return list.map(function(mem) { return memoryItemView(mem); });
    }

    // ── Panel Component ──────────────────────────────────────────────────

    let PanelView = {
        view: function() {
            let badge = memoryCount > 0 ? " (" + memoryCount + ")" : "";
            return m("div", { class: "border-b border-gray-700" }, [
                // Header
                m("button", {
                    class: "flyout-button flex items-center w-full px-3 py-2 text-sm font-medium text-gray-300",
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
                // Expanded content
                expanded ? m("div", { class: "max-h-64 overflow-y-auto" }, [
                    searchInputView(),
                    memoryListView()
                ]) : ""
            ]);
        }
    };

    // ── Public API ───────────────────────────────────────────────────────

    let MemoryPanel = {

        PanelView: PanelView,

        /**
         * Load memories for the current session's character pair.
         * @param {Object} chatConfig - the chat config with systemCharacter/userCharacter
         */
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
                // Try count endpoint only
                if (sys && usr && sys.objectId && usr.objectId) {
                    loadCount(sys.objectId, usr.objectId);
                } else {
                    memories = [];
                    memoryCount = 0;
                    m.redraw();
                }
            }
        },

        /**
         * Returns current memory count for badge display.
         */
        getMemoryCount: function() {
            return memoryCount;
        },

        /**
         * Force reload from server.
         */
        refresh: function() {
            if (currentConfig) {
                MemoryPanel.loadForSession(currentConfig);
            }
        }
    };

    // ── Export ───────────────────────────────────────────────────────────

    if (typeof module != "undefined") {
        module.MemoryPanel = MemoryPanel;
    } else {
        window.MemoryPanel = MemoryPanel;
    }

    console.log("[MemoryPanel] loaded");
}());
