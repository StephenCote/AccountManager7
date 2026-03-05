/**
 * MemoryPanel — Memory browsing, search, creation, and extraction sidebar (ESM port)
 * Shows memory state for current character pair or individual character.
 * Supports view modes: pair, systemChar, userChar.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { LLMConnector } from './LLMConnector.js';

// ── State ────────────────────────────────────────────────────────────

let _memories = null;
let _loading = false;
let _expanded = false;
let _filterText = "";
let _systemCharId = null;
let _userCharId = null;
let _creating = false;
let _extracting = false;
let _createContent = "";
let _createSummary = "";
let _createType = "NOTE";
let _createImportance = 5;
let _showCreateForm = false;
let _expandedMemoryId = null;
let _currentConfig = null;
let _chatRequestObjectId = null;
let _viewMode = "pair";
let _characterMemories = null;
let _characterLoading = false;
let _memoryCount = 0;

// ── Constants ────────────────────────────────────────────────────────

const MEMORY_TYPES = [
    { id: "NOTE", label: "Note", icon: "sticky_note_2" },
    { id: "FACT", label: "Fact", icon: "fact_check" },
    { id: "RELATIONSHIP", label: "Relationship", icon: "handshake" },
    { id: "EMOTION", label: "Emotion", icon: "favorite" },
    { id: "DECISION", label: "Decision", icon: "gavel" },
    { id: "DISCOVERY", label: "Discovery", icon: "explore" },
    { id: "INSIGHT", label: "Insight", icon: "psychology" },
    { id: "OUTCOME", label: "Outcome", icon: "flag" }
];

function memoryTypeIcon(type) {
    let mt = MEMORY_TYPES.find(t => t.id === type);
    return mt ? mt.icon : "memory";
}

// ── Data Loading ─────────────────────────────────────────────────────

async function loadMemories(systemCharId, userCharId) {
    if (_loading) return;
    _loading = true;
    _systemCharId = systemCharId;
    _userCharId = userCharId;

    if (!systemCharId || !userCharId) {
        _memories = [];
        _memoryCount = 0;
        _loading = false;
        m.redraw();
        return;
    }

    try {
        let result = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/memory/pair/" + systemCharId + "/" + userCharId + "/50",
            withCredentials: true,
            extract: function(xhr) {
                if (xhr.status !== 200) return [];
                try { return JSON.parse(xhr.responseText); } catch(e) { return []; }
            }
        });
        _memories = Array.isArray(result) ? result : [];
    } catch (e) {
        console.warn("[MemoryPanel] Failed to load memories:", e);
        _memories = [];
    }

    loadCount(systemCharId, userCharId);
    _loading = false;
    m.redraw();
}

async function loadCount(person1Id, person2Id) {
    if (!person1Id || !person2Id) { _memoryCount = 0; return; }
    try {
        let result = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/memory/count/" + person1Id + "/" + person2Id,
            withCredentials: true,
            extract: function(xhr) {
                if (xhr.status !== 200) return 0;
                try { return parseInt(xhr.responseText, 10) || 0; } catch(e) { return 0; }
            }
        });
        _memoryCount = result || 0;
    } catch (e) {
        _memoryCount = _memories ? _memories.length : 0;
    }
}

async function loadCharacterMemories(personObjectId) {
    if (_characterLoading || !personObjectId) return;
    _characterLoading = true;
    try {
        let result = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/memory/person/" + personObjectId + "/50",
            withCredentials: true,
            extract: function(xhr) {
                if (xhr.status !== 200) return [];
                try { return JSON.parse(xhr.responseText); } catch(e) { return []; }
            }
        });
        _characterMemories = Array.isArray(result) ? result : [];
    } catch (e) {
        console.warn("[MemoryPanel] Failed to load character memories:", e);
        _characterMemories = [];
    }
    _characterLoading = false;
    m.redraw();
}

// ── Filtering ────────────────────────────────────────────────────────

function getFilteredMemories() {
    let list = _viewMode === "pair" ? _memories : _characterMemories;
    if (!list) return [];
    if (!_filterText || _filterText.length < 2) return list;
    let lower = _filterText.toLowerCase();
    return list.filter(mem => {
        let content = (mem.content || "").toLowerCase();
        let summary = (mem.summary || "").toLowerCase();
        let type = (mem.memoryType || "").toLowerCase();
        return content.indexOf(lower) !== -1 || summary.indexOf(lower) !== -1 || type.indexOf(lower) !== -1;
    });
}

// ── Person Payload ───────────────────────────────────────────────────

function buildPersonPayload() {
    if (!_currentConfig) return {};
    let sys = _currentConfig.systemCharacter;
    let usr = _currentConfig.userCharacter;
    let payload = {};
    if (sys && sys.objectId) {
        payload.person1Model = sys.schema || sys[am7client.jsonModelKey] || "olio.charPerson";
        payload.person1 = { objectId: sys.objectId };
    }
    if (usr && usr.objectId) {
        payload.person2Model = usr.schema || usr[am7client.jsonModelKey] || "olio.charPerson";
        payload.person2 = { objectId: usr.objectId };
    }
    return payload;
}

// ── Actions ──────────────────────────────────────────────────────────

async function createMemoryFromForm() {
    if (!_createContent.trim() || !_systemCharId || !_userCharId) return;
    _creating = true;
    m.redraw();

    let lockToken = LLMConnector.lockBgActivity();
    LLMConnector.setBgActivity("edit_note", "Creating memory...");

    try {
        let body = {
            content: _createContent.trim(),
            memoryType: _createType,
            importance: _createImportance,
            ...buildPersonPayload()
        };
        if (_createSummary.trim()) body.summary = _createSummary.trim();
        if (_chatRequestObjectId) body.conversationId = _chatRequestObjectId;

        await m.request({
            method: 'POST',
            url: applicationPath + "/rest/memory/create",
            withCredentials: true,
            body
        });
        _createContent = "";
        _createSummary = "";
        _showCreateForm = false;
        page.toast("success", "Memory created");
        await loadMemories(_systemCharId, _userCharId);
    } catch (e) {
        page.toast("error", "Failed to create memory");
        console.error("[MemoryPanel] createMemory failed:", e);
    }

    LLMConnector.unlockBgActivity(lockToken);
    LLMConnector.setBgActivity(null, null);
    _creating = false;
    m.redraw();
}

async function deleteMemory(mem) {
    if (!mem || !mem.objectId) return;
    try {
        await m.request({
            method: 'DELETE',
            url: applicationPath + "/rest/memory/" + mem.objectId,
            withCredentials: true,
            extract: function(xhr) { return xhr.status === 200 || xhr.status === 204; }
        });
        page.toast("success", "Memory deleted");
        if (_viewMode === "pair") {
            await loadMemories(_systemCharId, _userCharId);
        } else {
            let charId = _viewMode === "sysChar" ? _systemCharId : _userCharId;
            await loadCharacterMemories(charId);
        }
    } catch (e) {
        page.toast("error", "Failed to delete memory");
        console.error("[MemoryPanel] deleteMemory failed:", e);
    }
}

async function forceExtract() {
    if (!_chatRequestObjectId || _extracting) return;
    _extracting = true;
    m.redraw();

    let lockToken = LLMConnector.lockBgActivity();
    LLMConnector.setBgActivity("auto_awesome", "Extracting memories...");

    try {
        let result = await m.request({
            method: 'POST',
            url: applicationPath + "/rest/memory/extract/" + _chatRequestObjectId,
            withCredentials: true
        });
        let count = Array.isArray(result) ? result.length : 0;
        page.toast("success", "Extracted " + count + " memories");
        await loadMemories(_systemCharId, _userCharId);
    } catch (e) {
        page.toast("error", "Memory extraction failed");
        console.error("[MemoryPanel] forceExtract failed:", e);
    }

    LLMConnector.unlockBgActivity(lockToken);
    LLMConnector.setBgActivity(null, null);
    _extracting = false;
    m.redraw();
}

function shareMemoryToCurrentPair(mem) {
    if (!mem || !_systemCharId || !_userCharId) return;
    let body = {
        content: mem.content || "",
        summary: mem.summary || "",
        memoryType: mem.memoryType || "NOTE",
        importance: mem.importance || 5,
        ...buildPersonPayload()
    };
    m.request({
        method: 'POST',
        url: applicationPath + "/rest/memory/create",
        withCredentials: true,
        body
    }).then(() => {
        page.toast("success", "Memory shared to current pair");
        loadMemories(_systemCharId, _userCharId);
    }).catch(e => {
        page.toast("error", "Failed to share memory");
        console.error("[MemoryPanel] shareMemory failed:", e);
    });
}

// ── Views ────────────────────────────────────────────────────────────

function memoryItemView(mem) {
    let content = mem.content || "(empty)";
    let summary = mem.summary || "";
    let type = mem.memoryType || "NOTE";
    let importance = mem.importance || 0;
    let isExpanded = _expandedMemoryId === mem.objectId;
    let isCharView = _viewMode !== "pair";

    return m("div", { key: mem.objectId, class: "border-b border-gray-100 dark:border-gray-700" }, [
        m("div", {
            class: "flex items-center gap-1 px-2 py-1.5 text-xs cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800",
            onclick: () => { _expandedMemoryId = isExpanded ? null : mem.objectId; }
        }, [
            m("span", { class: "material-symbols-outlined flex-shrink-0 text-gray-400", style: "font-size: 14px;" }, memoryTypeIcon(type)),
            m("span", { class: "flex-1 truncate text-gray-700 dark:text-gray-300" }, summary || content.substring(0, 80)),
            importance > 0 ? m("span", { class: "text-gray-400 flex-shrink-0", title: "Importance: " + importance }, importance) : "",
            isCharView ? m("button", {
                class: "flex-shrink-0 p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600",
                title: "Share to current pair",
                onclick: e => { e.stopPropagation(); shareMemoryToCurrentPair(mem); }
            }, m("span", { class: "material-symbols-outlined text-blue-400", style: "font-size: 14px;" }, "share")) : "",
            m("button", {
                class: "flex-shrink-0 p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600",
                title: "Delete memory",
                onclick: e => { e.stopPropagation(); deleteMemory(mem); }
            }, m("span", { class: "material-symbols-outlined text-red-400 opacity-40 hover:opacity-100", style: "font-size: 14px;" }, "delete"))
        ]),
        isExpanded ? m("div", { class: "px-3 py-1.5 bg-gray-50 dark:bg-gray-800 text-xs" }, [
            m("div", { class: "text-gray-400 capitalize mb-0.5" }, type),
            m("div", { class: "text-gray-700 dark:text-gray-300 whitespace-pre-wrap" }, content)
        ]) : ""
    ]);
}

function viewModeSelector() {
    if (!_systemCharId || !_userCharId) return "";
    let modes = [
        { id: "pair", label: "Pair", icon: "group" },
        { id: "sysChar", label: "System", icon: "smart_toy" },
        { id: "usrChar", label: "User", icon: "person" }
    ];
    return m("div", { class: "flex gap-1 px-2 py-1" },
        modes.map(md => m("button", {
            class: "flex-1 text-xs px-1 py-0.5 rounded " +
                (_viewMode === md.id
                    ? "bg-blue-600 text-white"
                    : "bg-gray-100 dark:bg-gray-800 text-gray-500 hover:bg-gray-200 dark:hover:bg-gray-700"),
            onclick: () => {
                _viewMode = md.id;
                if (md.id === "sysChar") loadCharacterMemories(_systemCharId);
                else if (md.id === "usrChar") loadCharacterMemories(_userCharId);
            }
        }, [
            m("span", { class: "material-symbols-outlined mr-0.5", style: "font-size: 12px;" }, md.icon),
            md.label
        ]))
    );
}

function createFormView() {
    if (!_showCreateForm) return "";
    return m("div", { class: "px-2 py-2 border-b border-gray-200 dark:border-gray-600 space-y-2" }, [
        m("textarea", {
            class: "w-full text-xs p-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200",
            rows: 3,
            placeholder: "Memory content...",
            value: _createContent,
            oninput: e => { _createContent = e.target.value; }
        }),
        m("input", {
            type: "text",
            class: "w-full text-xs p-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200",
            placeholder: "Summary (optional)",
            value: _createSummary,
            oninput: e => { _createSummary = e.target.value; }
        }),
        m("div", { class: "flex items-center gap-2" }, [
            m("select", {
                class: "flex-1 text-xs p-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200",
                value: _createType,
                onchange: e => { _createType = e.target.value; }
            }, MEMORY_TYPES.map(t => m("option", { value: t.id }, t.label))),
            m("input", {
                type: "number",
                class: "w-14 text-xs p-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200",
                min: 1, max: 10,
                value: _createImportance,
                title: "Importance (1-10)",
                oninput: e => { _createImportance = parseInt(e.target.value, 10) || 5; }
            })
        ]),
        m("div", { class: "flex gap-2" }, [
            m("button", {
                class: "text-xs px-2 py-1 rounded bg-blue-600 text-white hover:bg-blue-500 disabled:opacity-50",
                disabled: _creating || !_createContent.trim(),
                onclick: createMemoryFromForm
            }, _creating ? "Creating..." : "Save"),
            m("button", {
                class: "text-xs px-2 py-1 rounded bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600",
                onclick: () => { _showCreateForm = false; }
            }, "Cancel")
        ])
    ]);
}

function searchInputView() {
    return m("div", { class: "px-2 py-1" },
        m("input", {
            type: "text",
            class: "w-full text-xs p-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200",
            placeholder: "Search memories...",
            value: _filterText,
            oninput: e => { _filterText = e.target.value; }
        })
    );
}

function memoryListView() {
    let isLoading = _viewMode === "pair" ? (_loading && !_memories) : _characterLoading;
    if (isLoading) return m("div", { class: "text-xs text-gray-500 px-2 py-1" }, "Loading...");

    let filtered = getFilteredMemories();
    if (filtered.length === 0) {
        let msg = _filterText ? "No matches" : (_viewMode === "pair" ? "No memories" : "No character memories");
        return m("div", { class: "text-xs text-gray-500 px-2 py-1" }, msg);
    }
    return filtered.map(memoryItemView);
}

function panelContentView() {
    if (!_systemCharId || !_userCharId) return m("div", { class: "text-xs text-gray-500 px-2 py-1" }, "No character pair set");

    return m("div", { class: "flex flex-col" }, [
        viewModeSelector(),
        createFormView(),
        searchInputView(),
        m("div", { class: "overflow-y-auto max-h-64" }, memoryListView())
    ]);
}

// ── Public API ───────────────────────────────────────────────────────

const MemoryPanel = {
    load: loadMemories,
    refresh: () => { if (_systemCharId && _userCharId) loadMemories(_systemCharId, _userCharId); },
    getMemories: () => _memories,
    getMemoryCount: () => _memoryCount,
    toggle: () => { _expanded = !_expanded; },
    isExpanded: () => _expanded,

    clear: () => {
        _memories = null;
        _characterMemories = null;
        _systemCharId = null;
        _userCharId = null;
        _filterText = "";
        _currentConfig = null;
        _chatRequestObjectId = null;
        _viewMode = "pair";
        _expandedMemoryId = null;
        _showCreateForm = false;
        _memoryCount = 0;
    },

    setConfig: function(cfg) {
        _currentConfig = cfg;
    },

    loadForSession: function(chatConfig, chatReqObjectId) {
        _chatRequestObjectId = chatReqObjectId || null;
        if (!chatConfig) {
            _memories = null;
            _currentConfig = null;
            m.redraw();
            return;
        }
        _currentConfig = chatConfig;
        let sys = chatConfig.systemCharacter;
        let usr = chatConfig.userCharacter;
        if (sys && usr && sys.objectId && usr.objectId) {
            loadMemories(sys.objectId, usr.objectId);
        } else {
            _memories = null;
            m.redraw();
        }
    },

    loadForPair: function(person1ObjectId, person2ObjectId) {
        loadMemories(person1ObjectId, person2ObjectId);
    },

    loadForCharacter: function(personObjectId) {
        loadCharacterMemories(personObjectId);
    },

    setCharacterPair: function(systemCharId, userCharId) {
        if (systemCharId !== _systemCharId || userCharId !== _userCharId) {
            _memories = null;
            loadMemories(systemCharId, userCharId);
        }
    },

    PanelView: {
        view: function() {
            let count = _memoryCount || (_memories ? _memories.length : 0);
            let label = "Memories" + (count > 0 ? " (" + count + ")" : "");
            return m("div", { class: "border-t border-gray-200 dark:border-gray-600" }, [
                m("button", {
                    class: "w-full text-xs flex items-center justify-between px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700",
                    onclick: () => { _expanded = !_expanded; }
                }, [
                    m("span", { class: "flex items-center" }, [
                        m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 18px;" }, "psychology"),
                        label
                    ]),
                    m("span", { class: "flex items-center gap-1" }, [
                        _chatRequestObjectId ? m("button", {
                            class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600",
                            title: "Extract memories from conversation",
                            onclick: e => { e.stopPropagation(); forceExtract(); }
                        }, m("span", {
                            class: "material-symbols-outlined" + (_extracting ? " animate-spin" : ""),
                            style: "font-size: 16px;"
                        }, _extracting ? "progress_activity" : "auto_awesome")) : "",
                        m("button", {
                            class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-600",
                            title: "Create memory",
                            onclick: e => { e.stopPropagation(); _showCreateForm = !_showCreateForm; }
                        }, m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, "add_circle")),
                        m("span", { class: "material-symbols-outlined", style: "font-size: 16px;" }, _expanded ? "expand_less" : "expand_more")
                    ])
                ]),
                _expanded ? panelContentView() : ""
            ]);
        }
    }
};

export { MemoryPanel };
export default MemoryPanel;
