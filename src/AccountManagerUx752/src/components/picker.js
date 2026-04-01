/**
 * Object Picker — reuses the full list view inside a modal dialog (ESM)
 * Provides navigation, pagination, grid modes, search, breadcrumbs, thumbnails.
 * Starts at user's own group path (e.g., ~/Colors), with toggle to shared library.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { newListControl } from '../views/list.js';

// ── Picker state ───────────────────────────────────────────────────

let pickerState = {
    enabled: false,
    title: null,
    handler: null
};

let pickerListControl = newListControl();

// ── Container resolution ────────────────────────────────────────────

/**
 * Resolve user path for a type (e.g., data.color → ~/Colors → /home/user/Colors).
 * Uses makePath (find-or-create) — matches Ux7's am7client.make pattern.
 * Returns objectId or null.
 */
async function resolveUserContainer(type) {
    let userPath = am7view.pathForType(type);
    if (!userPath) return null;
    try {
        let grp = await page.makePath("auth.group", "DATA", userPath);
        return grp ? (grp.objectId || grp.id) : null;
    } catch(e) {
        console.warn("[Picker] resolveUserContainer failed for", type, e);
        return null;
    }
}

/**
 * Resolve the shared library path for a type (e.g., data.color → /Library/Colors).
 * Returns objectId or null.
 */
async function resolveLibraryContainer(type) {
    let libraryPath = am7model.system && am7model.system.library && am7model.system.library[type];
    if (!libraryPath) return null;
    try {
        let grp = await page.findObject("auth.group", "DATA", libraryPath);
        return grp ? (grp.objectId || grp.id) : null;
    } catch(e) {
        console.warn("[Picker] resolveLibraryContainer failed for", type, e);
        return null;
    }
}

/**
 * Resolve the user's Favorites bucket.
 * Returns objectId or null.
 */
async function resolveFavoritesContainer() {
    let fav = await page.favorites();
    return fav ? fav.objectId : null;
}

async function resolveContainer(type) {
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
                    resolve(v[0].objectId || null);
                } else {
                    resolve(null);
                }
            });
        });
    }

    let hasGroupId = am7model.hasField(type, "groupId");
    if (hasGroupId || am7model.isGroup(model) || am7model.isParent(model)) {
        // Try user path first (e.g., ~/Colors), then fall back to library
        let userId = await resolveUserContainer(type);
        if (userId) return userId;

        let libId = await resolveLibraryContainer(type);
        if (libId) return libId;

        // Last resort: org root
        if (am7client.currentOrganization) {
            let grp = await page.findObject("auth.group", "DATA", am7client.currentOrganization);
            return grp ? grp.objectId : null;
        }
        return null;
    }

    // system.user — no container needed
    if (type === "system.user") {
        return "__user__";
    }

    console.warn("[Picker] Unhandled type for container resolution:", type);
    return null;
}

// ── Public API ───────────────────────────────────────────────────────

const ObjectPicker = {
    /**
     * Open the picker for a given type.
     * Starts at user's own path (~/group) with toggle to shared library.
     */
    open: async function(opts) {
        pickerState.handler = opts.onSelect || null;
        pickerState.title = opts.title || "Select";

        // Resolve containers: user path, library, favorites
        let userContainerId = opts.userContainerId || null;
        let libraryContainerId = opts.libraryContainerId || null;
        let favoritesContainerId = opts.favoritesContainerId || null;
        let containerId = opts.containerId || null;

        if (!containerId) {
            // Resolve model default path (~/Colors), library (/Library/Colors), favorites
            if (!userContainerId) userContainerId = await resolveUserContainer(opts.type);
            if (!libraryContainerId) {
                // Use explicit library path from field definition if provided
                if (opts.libraryPath) {
                    let grp = await page.findObject("auth.group", "data", opts.libraryPath);
                    if (grp) libraryContainerId = grp.objectId;
                }
                if (!libraryContainerId) libraryContainerId = await resolveLibraryContainer(opts.type);
            }
            if (!favoritesContainerId) favoritesContainerId = await resolveFavoritesContainer();

            // Start at user's own path; fall back to library; fall back to generic resolve
            containerId = userContainerId || libraryContainerId || await resolveContainer(opts.type);
        }

        if (!containerId) {
            page.toast("warn", "Could not resolve container for " + opts.type);
            return;
        }

        pickerListControl.openForPicker({
            type: opts.type,
            containerId: containerId,
            userContainerId: userContainerId,
            libraryContainerId: libraryContainerId,
            favoritesContainerId: favoritesContainerId,
            onSelect: function(items) {
                let handler = pickerState.handler;
                ObjectPicker.close();
                if (handler && items) {
                    // getSelected() returns array; unwrap for single-select
                    let item = Array.isArray(items) ? items[0] : items;
                    if (item) handler(item);
                }
            }
        });

        pickerState.enabled = true;
        m.redraw();
    },

    /**
     * Open picker for a library type using the chat library directory endpoint.
     */
    openLibrary: async function(opts) {
        let typeMap = {
            chatConfig: "olio.llm.chatConfig",
            promptConfig: "olio.llm.promptConfig",
            promptTemplate: "olio.llm.promptTemplate"
        };
        // Backend library dir endpoint uses different names than frontend
        let dirTypeMap = {
            chatConfig: "chat",
            promptConfig: "prompt",
            promptTemplate: "promptTemplate"
        };
        let modelType = typeMap[opts.libraryType];
        if (!modelType) {
            page.toast("warn", "Unknown library type: " + opts.libraryType);
            return;
        }

        let dirMod;
        try {
            dirMod = await import('../chat/LLMConnector.js');
        } catch(e) {
            page.toast("error", "Chat module not available");
            return;
        }
        let dirType = dirTypeMap[opts.libraryType] || opts.libraryType;

        // Ensure prompt templates are populated (idempotent — skips existing)
        if (dirType === 'prompt' || dirType === 'promptTemplate') {
            try { await dirMod.LLMConnector.initPromptLibrary(); } catch (e) { /* non-fatal */ }
        }

        let dir = await dirMod.LLMConnector.getLibraryGroup(dirType);
        if (!dir || !dir.objectId) {
            page.toast("warn", "Library directory not found for " + opts.libraryType);
            return;
        }

        // Resolve user container (model default group path ~/Chat) — matches Ux7 preparePicker
        let userContId = null;
        try { userContId = await resolveUserContainer(modelType); } catch(e) { /* ignore */ }
        let favId = null;
        try { let fav = await page.favorites(); if (fav) favId = fav.objectId; } catch(e) { /* ignore */ }

        // Start at model default group path (~/Chat), fall back to library dir
        let startId = userContId || dir.objectId;

        await ObjectPicker.open({
            type: modelType,
            title: opts.title || "Select " + opts.libraryType,
            containerId: startId,
            userContainerId: userContId,
            libraryContainerId: dir.objectId,
            favoritesContainerId: favId,
            onSelect: opts.onSelect
        });
    },

    close: function() {
        pickerState.enabled = false;
        pickerState.handler = null;
        pickerState.title = null;
        pickerListControl.closePickerMode();
        m.redraw();
    },

    isOpen: function() {
        return pickerState.enabled;
    },

    select: function(item) {
        let handler = pickerState.handler;
        ObjectPicker.close();
        if (handler && item) handler(item);
    },

    validateFormat: function(inst, field, format, useName) {
        if (format === "picker" && field.pickerProperty && field.pickerProperty.requiredAttributes) {
            if (!am7view.showField(inst, field.pickerProperty, useName)) {
                return "text";
            }
        }
        return format;
    },

    inPickMode: function() {
        return pickerState.enabled;
    }
};

// ── Picker View Component ────────────────────────────────────────────

ObjectPicker.PickerView = {
    view: function() {
        if (!pickerState.enabled) return null;

        return m("div", {
            class: "am7-picker-overlay fixed inset-0 z-[60] flex items-center justify-center"
        }, [
            m("div", { class: "absolute inset-0 bg-black/50", onclick: function() { ObjectPicker.close(); } }),
            m("div", {
                class: "relative bg-white dark:bg-gray-900 rounded-lg shadow-xl w-full max-w-4xl mx-4 flex flex-col",
                style: "height:85vh;max-height:85vh",
                onclick: function(e) { e.stopPropagation(); }
            }, [
                // Header — title and close button
                m("div", { class: "flex items-center justify-between px-4 py-3 border-b border-gray-200 dark:border-gray-700 shrink-0" }, [
                    m("h3", { class: "text-lg font-semibold text-gray-800 dark:text-white" }, pickerState.title),
                    m("button", {
                        class: "text-gray-400 hover:text-gray-600 dark:hover:text-gray-300",
                        onclick: function() { ObjectPicker.close(); }
                    }, m("span", { class: "material-symbols-outlined" }, "close"))
                ]),
                // Embedded list view — toolbar, breadcrumbs, grid/table, pagination
                m("div", { class: "flex-1 overflow-auto flex flex-col", style: "min-height:0" }, [
                    pickerListControl.renderContent({ attrs: {} })
                ])
            ])
        ]);
    }
};

export { ObjectPicker };
export default ObjectPicker;
