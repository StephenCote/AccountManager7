/**
 * ChatTokenRenderer — Token processing for LLM responses (ESM port)
 * Handles image tokens (${image.*}), audio tokens (${audio.text "..."}),
 * MCP context blocks, and display pruning.
 *
 * Image/audio resolution requires am7imageTokens/am7audioTokens libraries
 * (not yet ported). Current implementation renders placeholder UI elements.
 */
import m from 'mithril';
import { LLMConnector } from './LLMConnector.js';
import { am7imageTokens } from './imageTokens.js';
import { am7audioTokens } from './audioTokens.js';

// Register on window for backward compat with existing window.am7imageTokens checks
if (typeof window !== "undefined") {
    window.am7imageTokens = am7imageTokens;
    window.am7audioTokens = am7audioTokens;
}

const IMAGE_TOKEN_RE = /\$\{image\.([^}]+)\}/g;
// Backend produces ${audio.TEXT} — arbitrary text after "audio." until closing }
const AUDIO_TOKEN_RE = /\$\{audio\.([^}]+)\}/g;
// Backend uses XML format: <mcp:context type="..." uri="..." ephemeral="true">...</mcp:context>
// Match the full tag and extract attributes in the handler function
const MCP_BLOCK_RE = /<mcp:context\s+([^>]*)>([\s\S]*?)<\/mcp:context>/g;
// Also match self-closing <mcp:resource uri="..." tags="..." /> for inline media
const MCP_RESOURCE_RE = /<mcp:resource\s+[^>]*?\/>/g;

function mcpAttr(attrs, name) {
    let re = new RegExp(name + '="([^"]*)"');
    let m = re.exec(attrs);
    return m ? m[1] : "";
}

function escapeHtmlAttr(str) {
    if (!str) return "";
    if (typeof str !== "string") str = String(str);
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
              .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

const ChatTokenRenderer = {

    /**
     * Process image tokens in content string.
     * With am7imageTokens loaded: resolves to <img> elements with cached URLs.
     * Without: renders placeholder spans.
     */
    processImageTokens: function(content, msgRole, msgIndex, characters, state, onResolve) {
        if (!content || typeof content !== "string" || content.indexOf("${image.") === -1) return content;

        // Full resolution when token library available
        if (typeof window !== "undefined" && window.am7imageTokens) {
            let tokens = window.am7imageTokens.parse(content);
            if (!tokens || !tokens.length) return content;
            let result = content;
            // Process in reverse to preserve indices
            for (let i = tokens.length - 1; i >= 0; i--) {
                let tok = tokens[i];
                let cached = window.am7imageTokens.cache ? window.am7imageTokens.cache[tok.key] : null;
                let replacement;
                if (cached && cached.url) {
                    replacement = '<img src="' + escapeHtmlAttr(cached.url) + '" '
                        + 'alt="' + escapeHtmlAttr(tok.tags) + '" '
                        + 'class="inline-block max-w-xs rounded shadow cursor-pointer" '
                        + 'data-token-key="' + escapeHtmlAttr(tok.key) + '" />';
                } else {
                    replacement = '<span class="inline-block bg-gray-200 dark:bg-gray-700 rounded px-2 py-1 text-xs text-gray-500">'
                        + '<span class="material-symbols-outlined" style="font-size:14px;vertical-align:middle;">image</span> '
                        + escapeHtmlAttr(tok.tags) + '</span>';
                    // Trigger async resolution
                    if (state && !state.resolvingImages[tok.key]) {
                        state.resolvingImages[tok.key] = true;
                        ChatTokenRenderer._resolveImages(msgIndex, characters, state, onResolve);
                    }
                }
                result = result.substring(0, tok.start) + replacement + result.substring(tok.end);
            }
            return result;
        }

        // Fallback: placeholder spans
        return content.replace(IMAGE_TOKEN_RE, function(match, tags) {
            return '<span class="inline-block bg-gray-200 dark:bg-gray-700 rounded px-2 py-1 text-xs text-gray-500">'
                + '<span class="material-symbols-outlined" style="font-size:14px;vertical-align:middle;">image</span> '
                + escapeHtmlAttr(tags) + '</span>';
        });
    },

    /**
     * Process audio tokens in content string.
     * With am7audioTokens loaded: creates interactive audio player buttons.
     * Without: renders placeholder spans.
     */
    processAudioTokens: function(content, msgRole, msgIndex, characters, sessionId) {
        if (!content || typeof content !== "string" || content.indexOf("${audio.") === -1) return content;

        // Full audio when token library available
        if (typeof window !== "undefined" && window.am7audioTokens) {
            let tokens = window.am7audioTokens.parse(content);
            if (!tokens || !tokens.length) return content;

            let profileId = null;
            if (characters) {
                if (msgRole === "assistant" && characters.system && characters.system.profile) {
                    profileId = characters.system.profile.objectId;
                } else if (characters.user && characters.user.profile) {
                    profileId = characters.user.profile.objectId;
                }
            }

            let result = content;
            for (let i = tokens.length - 1; i >= 0; i--) {
                let tok = tokens[i];
                let btnId = "audio_" + msgIndex + "_" + i;
                window.am7audioTokens.register(btnId, tok.text, profileId, sessionId);
                let state = window.am7audioTokens.state(btnId);
                let icon = state === "playing" ? "stop" : "volume_up";
                let replacement = '<button class="inline-flex items-center gap-1 bg-blue-100 dark:bg-blue-900 rounded px-2 py-0.5 text-xs cursor-pointer hover:bg-blue-200 dark:hover:bg-blue-800" '
                    + 'data-audio-id="' + escapeHtmlAttr(btnId) + '" '
                    + 'data-audio-text="' + escapeHtmlAttr(tok.text) + '">'
                    + '<span class="material-symbols-outlined" style="font-size:14px;">' + icon + '</span>'
                    + '<span>' + escapeHtmlAttr(tok.text) + '</span></button>';
                result = result.substring(0, tok.start) + replacement + result.substring(tok.end);
            }
            return result;
        }

        // Fallback: placeholder spans
        return content.replace(AUDIO_TOKEN_RE, function(match, text) {
            return '<span class="inline-flex items-center gap-1 bg-blue-100 dark:bg-blue-900 rounded px-2 py-0.5 text-xs">'
                + '<span class="material-symbols-outlined" style="font-size:14px;">volume_up</span>'
                + '<span>' + escapeHtmlAttr(text) + '</span></span>';
        });
    },

    /**
     * Process MCP context blocks in content.
     * In debug mode: render as collapsible cards with type/uri info.
     * In normal mode: strip all MCP blocks from display.
     */
    processMcpTokens: function(content, debugMode) {
        if (!content || (content.indexOf("<mcp:context") === -1 && content.indexOf("<mcp:resource") === -1)) return content;

        if (!debugMode) {
            return content.replace(MCP_BLOCK_RE, "").replace(MCP_RESOURCE_RE, "").trim();
        }

        // Strip inline resource tags in debug mode too (they render via image tokens)
        content = content.replace(MCP_RESOURCE_RE, "");

        return content.replace(MCP_BLOCK_RE, function(match, attrs, body) {
            let type = mcpAttr(attrs, "type");
            let uri = mcpAttr(attrs, "uri");
            let iconMap = {
                memory: "psychology",
                keyframe: "bookmark",
                reminder: "notifications",
                context: "description",
                directive: "policy",
                resource: "description",
                reasoning: "lightbulb"
            };
            let icon = iconMap[type] || "data_object";
            let headerText = type || "context";
            if (uri) headerText += " — " + uri;
            let preview = body.trim().substring(0, 200);
            if (body.trim().length > 200) preview += "...";

            return '<details class="my-1 rounded border border-gray-300 dark:border-gray-600 text-xs">'
                + '<summary class="flex items-center gap-1 px-2 py-1 bg-gray-100 dark:bg-gray-800 cursor-pointer">'
                + '<span class="material-symbols-outlined" style="font-size:14px;">' + icon + '</span>'
                + '<span class="text-gray-500">' + escapeHtmlAttr(headerText) + '</span>'
                + '</summary>'
                + '<div class="px-2 py-1 text-gray-600 dark:text-gray-400 whitespace-pre-wrap">'
                + escapeHtmlAttr(preview)
                + '</div></details>';
        });
    },

    /**
     * Prune content for display — removes LLM artifacts and metadata.
     * Optionally strips thinking/thought tags.
     */
    pruneForDisplay: function(content, hideThoughts) {
        if (!content) return "";
        if (hideThoughts) {
            content = LLMConnector.pruneTag(content, "think");
            content = LLMConnector.pruneTag(content, "thought");
        }
        content = LLMConnector.pruneToMark(content, "<|reserved_special_token");
        content = LLMConnector.pruneToMark(content, "(Metrics");
        content = LLMConnector.pruneToMark(content, "(Reminder");
        content = LLMConnector.pruneToMark(content, "(KeyFrame");
        content = LLMConnector.pruneOut(content, "--- INTERACTION HISTORY", "END INTERACTION HISTORY ---");
        content = LLMConnector.pruneOther(content);
        // Strip citation markers like [1], [2] etc.
        content = content.replace(/\[\d+\]/g, "");
        return content;
    },

    /**
     * Apply all standard display pruning and token processing to chat content.
     * Returns processed content with tokens rendered.
     */
    processContent: function(content, options) {
        if (!content) return "";
        if (typeof content !== "string") content = String(content);
        let opts = options || {};
        content = ChatTokenRenderer.pruneForDisplay(content, opts.hideThoughts !== false);
        content = ChatTokenRenderer.processMcpTokens(content, opts.debugMode);
        content = ChatTokenRenderer.processImageTokens(
            content, opts.msgRole, opts.msgIndex, opts.characters, opts.state, opts.onResolve
        );
        content = ChatTokenRenderer.processAudioTokens(
            content, opts.msgRole, opts.msgIndex, opts.characters, opts.sessionId
        );
        return content;
    },

    /**
     * Strip all tokens from content (for plain-text display).
     */
    stripTokens: function(content) {
        if (!content) return "";
        content = content.replace(IMAGE_TOKEN_RE, "");
        content = content.replace(AUDIO_TOKEN_RE, "");
        content = content.replace(MCP_BLOCK_RE, "");
        content = content.replace(MCP_RESOURCE_RE, "");
        return content.trim();
    },

    /**
     * Internal: async image resolution pipeline.
     * Called when processImageTokens encounters unresolved tokens.
     */
    _resolveImages: function(msgIndex, characters, state, onResolve) {
        if (!window.am7imageTokens || !onResolve) return;
        Promise.resolve(onResolve(msgIndex, characters, state)).then(() => {
            m.redraw();
        }).catch(e => {
            console.warn("[ChatTokenRenderer] Image resolution failed:", e);
        });
    }
};

export { ChatTokenRenderer };
export default ChatTokenRenderer;
