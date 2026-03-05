/**
 * Chat Config Toolbar — shows current chat/prompt config bindings with change buttons (ESM)
 */
import m from 'mithril';
import { ObjectPicker } from '../components/picker.js';
import { ContextPanel } from './ContextPanel.js';
import { am7client } from '../core/am7client.js';

// ── Config Toolbar Component ─────────────────────────────────────────

function configChip(label, name, icon, onclick) {
    return m("div", { class: "flex items-center gap-1 text-xs" }, [
        m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:14px" }, icon),
        m("span", { class: "text-gray-500 dark:text-gray-400" }, label + ":"),
        m("button", {
            class: "text-blue-600 dark:text-blue-400 hover:underline truncate max-w-[120px]",
            title: name || "None",
            onclick: onclick
        }, name || "None")
    ]);
}

const ChatConfigToolbar = {
    /**
     * Render the config toolbar.
     * @param {object} attrs
     * @param {object} attrs.inst - Current session instance (am7model.prepareInstance result)
     * @param {object} attrs.chatCfg - Current chat config state
     * @param {Function} attrs.onConfigChange - Called after config change; should re-peek session
     */
    view: function(vnode) {
        let inst = vnode.attrs.inst;
        let chatCfg = vnode.attrs.chatCfg;
        let onConfigChange = vnode.attrs.onConfigChange;
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

        return m("div", { class: "flex items-center gap-4 px-4 py-1.5 border-b border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-900/50 shrink-0 overflow-x-auto" }, [
            configChip("Chat Config", chatConfigName, "settings", function() {
                ObjectPicker.openLibrary({
                    libraryType: "chatConfig",
                    title: "Select Chat Config",
                    onSelect: function(obj) {
                        attachConfig(inst, "chatConfig", obj, onConfigChange);
                    }
                });
            }),
            configChip("Prompt", promptConfigName || promptTemplateName, "edit_note", function() {
                ObjectPicker.openLibrary({
                    libraryType: "promptTemplate",
                    title: "Select Prompt Template",
                    onSelect: function(obj) {
                        attachConfig(inst, "promptTemplate", obj, onConfigChange);
                    }
                });
            })
        ]);
    }
};

async function attachConfig(inst, configType, obj, onConfigChange) {
    if (!inst || !obj) return;

    try {
        // ContextPanel.attach uses its internal _sessionId (set via ContextPanel.load)
        await ContextPanel.attach(configType, obj.objectId);
        am7client.clearCache("olio.llm.chatRequest");
        if (onConfigChange) onConfigChange();
    } catch (e) {
        console.warn("[ChatConfigToolbar] attachConfig failed:", e);
    }
    m.redraw();
}

export { ChatConfigToolbar };
export default ChatConfigToolbar;
