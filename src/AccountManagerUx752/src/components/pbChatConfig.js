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

/** Pure: the "Chat Config" control's label. placeholder overrides the no-pick text. */
export function chatConfigLabel(ref, resolving, placeholder) {
    if (ref && ref.name) return ref.name;
    return resolving ? 'Resolving default…' : (placeholder || '(click to select)');
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

/**
 * The one chat-config control for every PB / ChapBook surface: label + click-to-pick box.
 * o = { testid, ref, resolving, title, onPick, disabled,
 *       label:       'Chat Config' (default) | any text | null (no label — e.g. inline "using [x]"),
 *       placeholder: text shown with no pick (default "(click to select)"),
 *       stacked:     true → form layout (label above a full-width box) for dialogs / wizard steps,
 *       attrs:       extra attributes for the clickable box (e.g. a legacy data-* selector) }
 */
export function renderChatConfigControl(o) {
    let label = o.label === undefined ? 'Chat Config' : o.label;
    let disabled = !!o.disabled;
    let box = m('div', Object.assign({
        'data-testid': o.testid,
        role: 'button',
        tabindex: disabled ? -1 : 0,
        'aria-disabled': disabled ? 'true' : undefined,
        'aria-label': (label || 'Chat Config') + ': ' + chatConfigLabel(o.ref, o.resolving, o.placeholder),
        class: 'text-sm px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 flex items-center gap-1 '
            + (o.stacked ? 'w-full justify-between' : 'max-w-xs')
            + (disabled ? ' opacity-50 cursor-not-allowed' : ' cursor-pointer hover:border-blue-500'),
        title: o.title,
        onclick: function () { if (!disabled) openChatConfigPicker(o.onPick); },
        onkeydown: function (e) { if (!disabled && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openChatConfigPicker(o.onPick); } }
    }, o.attrs || {}), [
        m('span', { class: 'truncate' + (o.ref && o.ref.name ? '' : ' text-gray-400') }, chatConfigLabel(o.ref, o.resolving, o.placeholder)),
        m('span', { class: 'material-symbols-outlined text-sm text-gray-400' }, 'search')
    ]);
    if (o.stacked) {
        return m('div', [label ? m('label', { class: 'field-label' }, label) : null, box]);
    }
    return m('div', { class: 'flex items-center gap-1' }, [
        label ? m('label', { class: 'field-label text-xs whitespace-nowrap mb-0' }, label) : null,
        box
    ]);
}
