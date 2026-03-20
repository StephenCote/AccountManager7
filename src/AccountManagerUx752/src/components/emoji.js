/**
 * emoji.js — Emoji picker and search component (ESM)
 * Port of Ux7 client/components/emoji.js
 *
 * Provides: emoji search, category browsing, clipboard copy, markdown emoji wrapping.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getPage() { return am7model._page; }

let emojiLoadReq = false;
let emojiDict;
let emojiReq = false;
let emojiDesc;
let emojiCat;
let emojiSubCat;
let emojiResults = [];
let emojiOpen = false;

// ── Emoji Loading ─────────────────────────────────────────────────

async function loadEmojiDict() {
    if (!emojiDict && !emojiReq) {
        emojiReq = true;
        try {
            let edd = await m.request({ method: 'GET', url: "/common/emojiDesc.json", withCredentials: true });
            if (edd && edd.length > 0) emojiDesc = edd;
        } catch (e) { /* optional */ }
        try {
            let ed = await m.request({ method: 'GET', url: "/common/emojis.categories.json", withCredentials: true });
            if (ed && ed.emojis) emojiDict = ed.emojis;
        } catch (e) { /* optional */ }
    }
}

// ── Search & Extraction ───────────────────────────────────────────

function extractEmojis(text) {
    if (!text || typeof text !== 'string') return [];
    const emojiRegex = /\p{Emoji_Presentation}|\p{Extended_Pictographic}/gu;
    return text.match(emojiRegex) || [];
}

function findEmojiDesc(emoji) {
    if (emojiDesc) {
        let ad = emojiDesc.filter(e => e.emoji === emoji);
        if (ad && ad.length > 0) return ad[0];
    }
    return undefined;
}

function findEmojiName(targetEmoji) {
    for (let cat in emojiDict) {
        let subs = emojiDict[cat];
        for (let sub in subs) {
            let found = subs[sub].find(e => e.emoji === targetEmoji.trim());
            if (found) return found.name;
        }
    }
    return undefined;
}

async function findEmojis(targetEmoji) {
    await loadEmojiDict();
    let be = extractEmojis(targetEmoji).length > 0;
    let ar = [];
    let et = targetEmoji.trim();
    for (let cat in emojiDict) {
        let subs = emojiDict[cat];
        for (let sub in subs) {
            let found = subs[sub].find(e => (be && e.emoji === et) || (!be && e.name.toLowerCase().indexOf(et.toLowerCase()) > -1));
            if (found) ar.push({ name: found.name, emoji: found.emoji, category: cat, subCategory: sub });
        }
    }
    return ar;
}

function searchEmojis(e) {
    let search = e.target.value.trim().toLowerCase();
    emojiCat = undefined;
    if (search.length == 0) {
        emojiResults = [];
        m.redraw();
        return;
    }
    findEmojis(search).then(results => {
        emojiResults = results;
        m.redraw();
    });
}

function markdownEmojis(text) {
    if (!emojiDict) {
        loadEmojiDict().then(() => m.redraw());
        return text;
    }
    let ea = Array.from(new Set(extractEmojis(text)));
    for (let i = 0; i < ea.length; i++) {
        let desc = findEmojiDesc(ea[i]);
        let txt = desc?.alt || desc?.description;
        if (!txt) txt = findEmojiName(ea[i]);
        let lbl = "[" + ea[i] + "](## \"" + txt + "\")";
        text = text.replaceAll(ea[i], lbl);
    }
    return text;
}

async function exportEmojis(delim) {
    await loadEmojiDict();
    const headers = ['Category', 'Subcategory', 'Emoji', 'Name', 'Keywords', 'Unicode'];
    let tsvContent = headers.join(delim || '\t') + '\n';
    for (let cat in emojiDict) {
        for (let sub in emojiDict[cat]) {
            let ems = emojiDict[cat][sub];
            if (Array.isArray(ems)) {
                ems.forEach(emoji => {
                    let keywords = Array.isArray(emoji.keywords) ? emoji.keywords.join(', ') : 'N/A';
                    tsvContent += [cat, sub, emoji.emoji || '', emoji.name || 'N/A', keywords, emoji.unicode || 'N/A'].join('\t') + '\n';
                });
            }
        }
    }
    return tsvContent;
}

// ── Emoji Button Renderer ─────────────────────────────────────────

function emojiMenuButton(o) {
    let page = getPage();
    return m("button", {
        class: "flex items-center gap-2 w-full px-2 py-1 text-left text-sm hover:bg-gray-100 dark:hover:bg-gray-700 rounded",
        onclick: function() {
            emojiOpen = false;
            navigator.clipboard.writeText(o.emoji);
            page.toast("info", o.emoji + " copied to clipboard", 2000);
        }
    }, [
        m("span", { class: "text-lg" }, o.emoji),
        m("span", { class: "text-gray-600 dark:text-gray-400" }, o.name)
    ]);
}

function emojiCategories() {
    let ret = [];
    if (!emojiLoadReq && !emojiDict) {
        emojiLoadReq = true;
        loadEmojiDict().then(() => m.redraw());
        return ret;
    }
    if (!emojiDict) return ret;

    if (emojiCat) {
        ret.push(m("button", { class: "flex items-center gap-1 px-2 py-1 text-xs text-blue-500 hover:underline", onclick: () => { emojiCat = undefined; emojiSubCat = undefined; m.redraw(); } },
            m("span", { class: "material-symbols-outlined text-sm" }, "arrow_back"), emojiCat));
        if (emojiSubCat) {
            ret.push(m("button", { class: "flex items-center gap-1 px-2 py-1 text-xs text-blue-500 hover:underline", onclick: () => { emojiSubCat = undefined; m.redraw(); } },
                m("span", { class: "material-symbols-outlined text-sm" }, "arrow_back"), emojiSubCat));
        }
    }

    let ejs = (emojiCat ? emojiDict[emojiCat] : emojiDict);
    if (emojiSubCat) ejs = ejs[emojiSubCat];

    for (let e in ejs) {
        if (emojiSubCat) {
            ret.push(emojiMenuButton(ejs[e]));
        } else {
            ret.push(m("button", {
                class: "w-full px-2 py-1 text-left text-sm hover:bg-gray-100 dark:hover:bg-gray-700 rounded",
                onclick: () => {
                    if (emojiCat) emojiSubCat = e;
                    else { emojiCat = e; emojiSubCat = undefined; }
                    m.redraw();
                }
            }, e));
        }
    }
    return ret;
}

// ── Emoji Picker Component ────────────────────────────────────────

function renderEmojiButton() {
    return m("div", { class: "relative inline-block" }, [
        m("button", {
            class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
            onclick: (e) => { e.stopPropagation(); emojiOpen = !emojiOpen; m.redraw(); }
        }, m("span", { class: "material-symbols-outlined" }, "add_reaction")),

        emojiOpen ? m("div", {
            class: "absolute bottom-full right-0 mb-1 w-72 max-h-80 overflow-y-auto bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-lg z-50 p-2",
            onclick: e => e.stopPropagation()
        }, [
            m("input", {
                type: "text", class: "w-full px-2 py-1 text-sm border rounded mb-2 dark:bg-gray-900 dark:border-gray-600",
                placeholder: "Search emojis", oninput: searchEmojis,
                oncreate: v => v.dom.focus()
            }),
            m("div", { class: "space-y-0.5" },
                emojiResults.length > 0 ? emojiResults.map(o => emojiMenuButton(o)) : emojiCategories()
            )
        ]) : null
    ]);
}

// Close on outside click
if (typeof document !== 'undefined') {
    document.addEventListener('click', () => { if (emojiOpen) { emojiOpen = false; m.redraw(); } });
}

// ── Export ─────────────────────────────────────────────────────────

const emoji = {
    menuButton: renderEmojiButton,
    markdownEmojis,
    findEmojis,
    findEmojiDesc,
    emojis: () => emojiDict,
    emojisDesc: () => emojiDesc,
    loadEmojiDict,
    exportEmojis,
    extractEmojis,
    component: {
        view: function() { return renderEmojiButton(); }
    }
};

export { emoji };
export default emoji;
