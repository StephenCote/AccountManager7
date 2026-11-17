/**
 * Wardrobe token chips (aiDocs/ChatWardrobeTokensDesign.md §3). The server applies ${outfit...}, ${dress...}
 * and ${give...} on receipt and stores the resolved form plus a hidden <private>[wardrobe] ...</private> note
 * (ChatWardrobeTokenUtil). This module only renders the token as a short chip so the raw token text is not
 * shown. Dependency-free on purpose: it is unit-checkable without the app's module graph.
 *
 * The chip is ONE <span> with no nested elements: chat.js stashes token HTML before markdown with a lazy
 * <span ...data-token-key...>...</span> match, which a nested span would cut short.
 */

const WARDROBE_TOKEN_RE = /\$\{(outfit|dress|give)((?:\.[^:}]*)?)(?::([^}]*))?\}/g;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_ITEMS = 6;

function esc(s) {
    return String(s == null ? "" : s)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;").replace(/'/g, "&#39;")
        // $ too: the chip's title carries the raw token, and later token passes scan for "${"
        .replace(/\$/g, "&#36;");
}

/** Item display names from a payload: embedded "category:name:LEVEL:g:loc" -> name; bare names as-is. */
function itemNames(payload) {
    if (!payload) return [];
    return payload.split(",").map(function(it) {
        let t = it.trim();
        let p = t.split(":");
        return p.length >= 2 ? p[1].trim() : t;
    }).filter(function(n) { return n.length > 0; });
}

function shortList(names) {
    if (names.length <= MAX_ITEMS) return names.join(", ");
    return names.slice(0, MAX_ITEMS).join(", ") + " +" + (names.length - MAX_ITEMS);
}

/** Parse one token match into {kind, resolved, id, who, op, payload}. */
function parseToken(kind, qualifier, payload) {
    let parts = (qualifier || "").split(".").map(function(s) { return s.trim(); }).filter(function(s) { return s.length; });
    let id = null;
    if (parts.length && UUID_RE.test(parts[0])) id = parts.shift();
    let who = null;
    if (parts.length && /^(me|you)$/i.test(parts[0])) who = parts.shift().toLowerCase();
    if (!who) who = (kind === "give" ? "you" : "me");
    return { kind: kind, resolved: !!id, id: id, who: who, op: parts.length ? parts[0] : null, payload: payload || null };
}

/** Chip label text for a parsed token. */
function chipLabel(t) {
    let names = itemNames(t.payload);
    if (t.kind === "outfit") {
        return (t.who === "you" ? "put on them: " : "outfit: ") + shortList(names);
    }
    if (t.kind === "dress") {
        let op = t.op || "";
        if (/^up$/i.test(op)) return "dress up a layer";
        if (/^down$/i.test(op)) return "dress down a layer";
        return (t.who === "you" ? "dressed them to " : "dressed to ") + op.toUpperCase();
    }
    if (t.kind === "give") {
        return "gave them: " + shortList(names);
    }
    return t.kind;
}

/// A token the server could not apply is followed by its "Not applied" note (ChatWardrobeTokenUtil).
const FAILED_NOTE_RE = /^\s*<private>\[wardrobe\]\s*Not applied:\s*([^<]*)<\/private>/;

/**
 * Replace every wardrobe token with a chip. MUST run before <private> blocks are pruned: that is the only
 * way to tell the three states apart —
 *   resolved (id in the token)                          -> applied, linked chip
 *   unresolved + "[wardrobe] Not applied: why" note     -> failed, struck-through, reason in the tooltip
 *   unresolved, no note                                 -> pending: the message the user just sent, shown
 *                                                          before the server's resolved copy reloads
 */
function processWardrobeTokens(content) {
    if (!content || typeof content !== "string" || !/\$\{(outfit|dress|give)/.test(content)) return content;
    return content.replace(WARDROBE_TOKEN_RE, function(match, kind, qualifier, payload, offset, whole) {
        let t = parseToken(kind, qualifier, payload);
        let failed = null;
        if (!t.resolved) {
            let fm = FAILED_NOTE_RE.exec(whole.substring(offset + match.length));
            if (fm) failed = fm[1].trim() || "not applied";
        }
        let label = chipLabel(t) + (failed ? " (not applied)" : (t.resolved ? "" : "…"));
        let cls = "inline-block rounded px-2 py-0.5 mx-0.5 text-xs bg-purple-100 dark:bg-purple-900 text-purple-800 dark:text-purple-200"
            + (failed ? " opacity-60 line-through" : (t.resolved ? "" : " opacity-70"));
        if (failed) match = match + " — not applied: " + failed;
        let text = '\u{1F454} ' + esc(label);
        let model = linkModel(t);
        if (model) {
            /// Opens the record's generic object view in a new tab (same route pictureBookCharacters.openInNewTab uses)
            text = '<a href="#!/view/' + model + '/' + esc(t.id) + '" target="_blank" rel="noopener" class="hover:underline" title="Open ' + esc(model) + '">' + text + '</a>';
        }
        return '<span class="' + cls + '" data-token-key="wardrobe:' + esc(t.id || kind) + '" title="' + esc(match) + '">' + text + '</span>';
    });
}

/**
 * Model of the record a resolved token's id points at: a new outfit and a dress change name the apparel;
 * an item put on / given names the wearable (ChatWardrobeTokenUtil writes those ids). Null when unresolved.
 */
function linkModel(t) {
    if (!t.resolved || !t.id) return null;
    if (t.kind === "dress") return "olio.apparel";
    if (t.kind === "outfit") return t.who === "you" ? "olio.wearable" : "olio.apparel";
    if (t.kind === "give") return "olio.wearable";
    return null;
}

function stripWardrobeTokens(content) {
    if (!content) return "";
    return content.replace(WARDROBE_TOKEN_RE, "");
}

const am7wardrobeTokens = {
    regex: WARDROBE_TOKEN_RE,
    parseToken,
    itemNames,
    chipLabel,
    linkModel,
    processWardrobeTokens,
    stripWardrobeTokens
};

export { am7wardrobeTokens, processWardrobeTokens, stripWardrobeTokens, parseToken, chipLabel, itemNames };
export default am7wardrobeTokens;
