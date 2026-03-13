/**
 * Object Picker — searchable list dialog for selecting objects by type (ESM)
 * Port of Ux7 client/components/picker.js
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';

// ── Picker state ───────────────────────────────────────────────────

let pickerMode = {
    enabled: false,
    containerId: null,
    type: null,
    handler: null,
    title: null,
    filterField: null,
    items: [],
    total: 0,
    start: 0,
    pageSize: 20,
    searchText: "",
    loading: false,
    error: null
};

function resetPicker() {
    pickerMode.enabled = false;
    pickerMode.containerId = null;
    pickerMode.type = null;
    pickerMode.handler = null;
    pickerMode.title = null;
    pickerMode.filterField = null;
    pickerMode.items = [];
    pickerMode.total = 0;
    pickerMode.start = 0;
    pickerMode.searchText = "";
    pickerMode.loading = false;
    pickerMode.error = null;
}

// ── Container resolution ────────────────────────────────────────────

async function resolveContainer(type, altPath) {
    let model = am7model.getModel(type);
    if (!model) {
        console.warn("[Picker] Unknown type:", type);
        return null;
    }

    // For auth types (role, permission), use user context
    if (type === "auth.role" || type === "auth.permission") {
        return new Promise(function(resolve) {
            am7client.user(type, "user", function(v) {
                if (v && v.length > 0) {
                    let parent = v[0];
                    resolve(parent.objectId || null);
                } else {
                    resolve(null);
                }
            });
        });
    }

    // Check if type has a groupId field (group-based objects like data.color,
    // olio.charPerson, etc.) or is a group/parent type. The container is always
    // an auth.group directory — matches Ux7's preparePicker pattern.
    let hasGroupId = am7model.hasField(type, "groupId");
    if (hasGroupId || am7model.isGroup(model) || am7model.isParent(model)) {
        // Check system library path first (e.g., data.color → /Library/Colors)
        let libraryPath = am7model.system && am7model.system.library && am7model.system.library[type];
        let path = altPath || libraryPath || am7client.currentOrganization;
        if (!path) return null;
        let grp = await page.findObject("auth.group", "data", path);
        if (!grp && path !== am7client.currentOrganization) {
            // Fallback: try org root
            grp = await page.findObject("auth.group", "data", am7client.currentOrganization);
        }
        return grp ? grp.objectId : null;
    }

    // system.user — no container needed
    if (type === "system.user") {
        return "__user__";
    }

    console.warn("[Picker] Unhandled type for container resolution:", type);
    return null;
}

// ── Data loading ────────────────────────────────────────────────────

async function loadItems() {
    if (!pickerMode.containerId || !pickerMode.type) return;
    pickerMode.loading = true;
    pickerMode.error = null;
    m.redraw();

    try {
        let result = await new Promise(function(resolve) {
            am7client.list(
                pickerMode.type,
                pickerMode.containerId,
                pickerMode.filterField || null,
                pickerMode.start,
                pickerMode.pageSize,
                function(v) { resolve(v); }
            );
        });

        if (result && Array.isArray(result)) {
            pickerMode.items = result;
            pickerMode.total = result.length < pickerMode.pageSize
                ? pickerMode.start + result.length
                : pickerMode.start + pickerMode.pageSize + 1;
        } else {
            pickerMode.items = [];
            pickerMode.total = 0;
        }
    } catch (e) {
        console.error("[Picker] loadItems failed:", e);
        pickerMode.error = "Failed to load items";
        pickerMode.items = [];
    }

    pickerMode.loading = false;
    m.redraw();
}

async function searchItems(query) {
    if (!pickerMode.type) return;
    pickerMode.loading = true;
    pickerMode.error = null;
    pickerMode.start = 0;
    m.redraw();

    try {
        let inst = am7model.newInstance(pickerMode.type);
        let q = am7view.viewQuery(inst);
        if (query && query.trim()) {
            q.field("name", query.trim() + "*");
        }
        // containerId is an objectId — use it to scope via groupId only if
        // the type has a groupId field and it's not the special user sentinel.
        if (pickerMode.containerId && pickerMode.containerId !== "__user__") {
            // am7client.list uses objectId, but search needs numeric .id for groupId.
            // Use the containerId to find the group's numeric id first,
            // or just rely on loadItems which uses am7client.list (objectId-based).
            // For search, skip groupId filter — it searches across the org.
        }
        q.range(pickerMode.start, pickerMode.pageSize);

        let result = await page.search(q);
        if (result && result.results) {
            pickerMode.items = result.results;
            pickerMode.total = result.totalCount || result.results.length;
        } else {
            pickerMode.items = [];
            pickerMode.total = 0;
        }
    } catch (e) {
        console.error("[Picker] searchItems failed:", e);
        pickerMode.error = "Search failed";
        pickerMode.items = [];
    }

    pickerMode.loading = false;
    m.redraw();
}

// ── Public API ───────────────────────────────────────────────────────

const ObjectPicker = {
    /**
     * Open the picker for a given type.
     * @param {object} opts
     * @param {string} opts.type - Model type (e.g., "olio.llm.chatConfig")
     * @param {string} opts.title - Dialog title
     * @param {string} opts.containerId - Pre-resolved container ID (optional)
     * @param {string} opts.altPath - Alternative path for container resolution
     * @param {string} opts.filterField - Field name filter for list
     * @param {Function} opts.onSelect - Callback with selected object
     */
    open: async function(opts) {
        resetPicker();
        pickerMode.type = opts.type;
        pickerMode.handler = opts.onSelect || null;
        pickerMode.title = opts.title || "Select";
        pickerMode.filterField = opts.filterField || null;

        if (opts.containerId) {
            pickerMode.containerId = opts.containerId;
        } else {
            pickerMode.containerId = await resolveContainer(opts.type, opts.altPath);
        }

        if (!pickerMode.containerId) {
            page.toast("warn", "Could not resolve container for " + opts.type);
            return;
        }

        pickerMode.enabled = true;
        m.redraw();
        await loadItems();
    },

    /**
     * Open picker for a library type using the chat library directory endpoint.
     * @param {object} opts
     * @param {string} opts.libraryType - "chatConfig" | "promptConfig" | "promptTemplate"
     * @param {string} opts.title
     * @param {Function} opts.onSelect
     */
    openLibrary: async function(opts) {
        let typeMap = {
            chatConfig: "olio.llm.chatConfig",
            promptConfig: "olio.llm.promptConfig",
            promptTemplate: "olio.llm.promptTemplate"
        };
        let modelType = typeMap[opts.libraryType];
        if (!modelType) {
            page.toast("warn", "Unknown library type: " + opts.libraryType);
            return;
        }

        // Use LLMConnector to get library dir
        let dirMod;
        try {
            dirMod = await import('../chat/LLMConnector.js');
        } catch(e) {
            page.toast("error", "Chat module not available");
            return;
        }
        let dir = await dirMod.LLMConnector.getLibraryGroup(opts.libraryType);
        if (!dir || !dir.objectId) {
            page.toast("warn", "Library directory not found for " + opts.libraryType);
            return;
        }

        await ObjectPicker.open({
            type: modelType,
            title: opts.title || "Select " + opts.libraryType,
            containerId: dir.objectId,
            onSelect: opts.onSelect
        });
    },

    close: function() {
        resetPicker();
        m.redraw();
    },

    isOpen: function() {
        return pickerMode.enabled;
    },

    select: function(item) {
        let handler = pickerMode.handler;
        resetPicker();
        m.redraw();
        if (handler && item) {
            handler(item);
        }
    },

    /**
     * Check if a field's picker format should be active based on required attributes.
     * Matches Ux7 page.components.picker.validateFormat().
     */
    validateFormat: function(inst, field, format, useName) {
        if (format === "picker" && field.pickerProperty && field.pickerProperty.requiredAttributes) {
            if (!am7view.showField(inst, field.pickerProperty, useName)) {
                return "text";
            }
        }
        return format;
    },

    /**
     * Check if the picker modal is currently open.
     * Used by designer.js and other components to hide content behind picker.
     */
    inPickMode: function() {
        return pickerMode.enabled;
    }
};

// ── Picker View Component ────────────────────────────────────────────

let searchDebounce = null;

ObjectPicker.PickerView = {
    view: function() {
        if (!pickerMode.enabled) return null;

        return m("div", {
            class: "fixed inset-0 z-50 flex items-center justify-center",
            onclick: function(e) {
                if (e.target === e.currentTarget) ObjectPicker.close();
            }
        }, [
            m("div", { class: "absolute inset-0 bg-black/50", onclick: function() { ObjectPicker.close(); } }),
            m("div", { class: "relative bg-white dark:bg-gray-900 rounded-lg shadow-xl w-full max-w-lg mx-4 max-h-[80vh] flex flex-col" }, [
                // Header
                m("div", { class: "flex items-center justify-between px-4 py-3 border-b border-gray-200 dark:border-gray-700 shrink-0" }, [
                    m("h3", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, pickerMode.title),
                    m("button", {
                        class: "text-gray-400 hover:text-gray-600 dark:hover:text-gray-300",
                        onclick: function() { ObjectPicker.close(); }
                    }, m("span", { class: "material-symbols-outlined" }, "close"))
                ]),
                // Search
                m("div", { class: "px-4 py-2 border-b border-gray-200 dark:border-gray-700 shrink-0" }, [
                    m("input", {
                        type: "text",
                        class: "w-full px-3 py-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500",
                        placeholder: "Search...",
                        value: pickerMode.searchText,
                        oninput: function(e) {
                            pickerMode.searchText = e.target.value;
                            if (searchDebounce) clearTimeout(searchDebounce);
                            searchDebounce = setTimeout(function() {
                                if (pickerMode.searchText.trim()) {
                                    searchItems(pickerMode.searchText);
                                } else {
                                    pickerMode.start = 0;
                                    loadItems();
                                }
                            }, 300);
                        }
                    })
                ]),
                // Items
                m("div", { class: "flex-1 overflow-y-auto" }, [
                    pickerMode.loading
                        ? m("div", { class: "flex items-center justify-center py-8" }, [
                            m("span", { class: "material-symbols-outlined animate-spin text-blue-500", style: "font-size:24px" }, "progress_activity"),
                            m("span", { class: "ml-2 text-gray-500 text-sm" }, "Loading...")
                        ])
                        : pickerMode.error
                            ? m("div", { class: "text-center py-8 text-red-500 text-sm" }, pickerMode.error)
                            : pickerMode.items.length === 0
                                ? m("div", { class: "text-center py-8 text-gray-400 text-sm" }, "No items found")
                                : pickerMode.items.map(function(item) {
                                    return m("button", {
                                        key: item.objectId || item.id,
                                        class: "w-full text-left px-4 py-2.5 hover:bg-blue-50 dark:hover:bg-blue-900/30 border-b border-gray-100 dark:border-gray-800 flex items-center gap-3 transition-colors",
                                        onclick: function() { ObjectPicker.select(item); }
                                    }, [
                                        m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:20px" }, "description"),
                                        m("div", { class: "min-w-0 flex-1" }, [
                                            m("div", { class: "text-sm font-medium text-gray-800 dark:text-white truncate" }, item.name || "(unnamed)"),
                                            item.description ? m("div", { class: "text-xs text-gray-500 dark:text-gray-400 truncate" }, item.description) : null
                                        ])
                                    ]);
                                })
                ]),
                // Pagination
                pickerMode.items.length > 0 ? m("div", { class: "flex items-center justify-between px-4 py-2 border-t border-gray-200 dark:border-gray-700 shrink-0 text-xs text-gray-500" }, [
                    m("span", {}, (pickerMode.start + 1) + "-" + (pickerMode.start + pickerMode.items.length) + " of " + (pickerMode.total > pickerMode.start + pickerMode.pageSize ? pickerMode.total + "+" : pickerMode.total)),
                    m("div", { class: "flex gap-1" }, [
                        m("button", {
                            class: "px-2 py-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800 disabled:opacity-30",
                            disabled: pickerMode.start === 0,
                            onclick: function() {
                                pickerMode.start = Math.max(0, pickerMode.start - pickerMode.pageSize);
                                if (pickerMode.searchText.trim()) searchItems(pickerMode.searchText);
                                else loadItems();
                            }
                        }, "Prev"),
                        m("button", {
                            class: "px-2 py-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800 disabled:opacity-30",
                            disabled: pickerMode.items.length < pickerMode.pageSize,
                            onclick: function() {
                                pickerMode.start += pickerMode.pageSize;
                                if (pickerMode.searchText.trim()) searchItems(pickerMode.searchText);
                                else loadItems();
                            }
                        }, "Next")
                    ])
                ]) : null
            ])
        ]);
    }
};

export { ObjectPicker };
export default ObjectPicker;
