/**
 * pbChatConfig — the chat-config pick every PB LLM surface sends by NAME (P10-C walk, ledger §6.C 267).
 *
 * Why: a PB request that carries no chatConfigName falls to the server's generalChat default
 * (PictureBookUtil → ChatUtil.resolveConfig(user, "generalChat")). The composition board, the wizard
 * and the covers all send a name — the library default resolved here, contentAnalysis first — so they
 * reach the config the user actually has; the Locations panel's "Propose from scenes" sent nothing and
 * was the one PB button that died on a stack whose library generalChat points at a dead connection.
 * This module is the board's resolver + picker + control (pictureBookCompose.js, P9-8) lifted out so
 * the Locations panel uses the same pick instead of a fourth copy.
 *
 * The server resolves the NAME per user (own group first, then /Library), so the client only needs the
 * name: resolveDefaultChatConfig is library-only (GET /rest/chat/library/chat/{name}, no LLM call) and
 * never overrides a pick — callers keep their own {name, objectId} ref and pass it in.
 */
import m from 'mithril';
import { ObjectPicker } from './picker.js';
import { LLMConnector } from '../chat/LLMConnector.js';

/** Library names tried, in order, for the default chat config (the ChapBook editor's order). */
export const DEFAULT_CHAT_CONFIG_NAMES = ['contentAnalysis', 'generalChat'];

/** Pure: request options with the picked chat config NAME added; nothing is added without a pick. */
export function withChatConfig(opts, ref) {
    let o = Object.assign({}, opts || {});
    if (ref && typeof ref.name === 'string' && ref.name.trim()) o.chatConfigName = ref.name.trim();
    return o;
}

/** Pure: the picked NAME to send, or undefined when there is no pick (the server default applies). */
export function chatConfigName(ref) {
    return withChatConfig({}, ref).chatConfigName;
}

/** Pure: the "Chat Config" control's label. */
export function chatConfigLabel(ref, resolving) {
    if (ref && ref.name) return ref.name;
    return resolving ? 'Resolving default…' : '(click to select)';
}

/** The first library name that resolves, as {name, objectId}; null when none does. Never throws. */
export async function resolveDefaultChatConfig() {
    for (let i = 0; i < DEFAULT_CHAT_CONFIG_NAMES.length; i++) {
        try {
            let rec = await LLMConnector.resolveConfig(DEFAULT_CHAT_CONFIG_NAMES[i]);
            if (rec && rec.name) return { name: rec.name, objectId: rec.objectId };
        } catch (e) { /* try the next name */ }
    }
    return null;
}

/** The library picker; onPick({name, objectId}) only for a real selection. */
export function openChatConfigPicker(onPick) {
    ObjectPicker.openLibrary({
        libraryType: 'chatConfig',
        title: 'Select Chat Config',
        onSelect: function (item) {
            if (item && item.name) onPick({ name: item.name, objectId: item.objectId });
        }
    });
}

/** The "Chat Config" control: label + click-to-pick box. o = {testid, ref, resolving, title, onPick}. */
export function renderChatConfigControl(o) {
    return m('div', { class: 'flex items-center gap-1' }, [
        m('label', { class: 'field-label text-xs whitespace-nowrap mb-0' }, 'Chat Config'),
        m('div', {
            'data-testid': o.testid,
            class: 'text-sm px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 cursor-pointer flex items-center gap-1 max-w-xs',
            title: o.title,
            onclick: function () { openChatConfigPicker(o.onPick); }
        }, [
            m('span', { class: 'truncate' + (o.ref ? '' : ' text-gray-400') }, chatConfigLabel(o.ref, o.resolving)),
            m('span', { class: 'material-symbols-outlined text-sm text-gray-400' }, 'search')
        ])
    ]);
}
