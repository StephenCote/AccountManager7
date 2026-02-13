/**
 * ChatTokenRenderer — Shared image/audio token processing
 * Phase 10a: Extracts token rendering logic from view/chat.js into a reusable module.
 *
 * Resolves: OI-20 (token standardization — image/audio varies between views)
 *
 * Exposes: window.ChatTokenRenderer
 */
(function() {
    "use strict";

    function escapeHtmlAttr(str) {
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    let ChatTokenRenderer = {

        /**
         * Parse image tokens from content string.
         * Returns array of token descriptors if am7imageTokens is available.
         * @param {string} content
         * @returns {Array}
         */
        parseImageTokens: function(content) {
            if (!content || !window.am7imageTokens) return [];
            return window.am7imageTokens.parse(content);
        },

        /**
         * Parse audio tokens from content string.
         * Returns array of token descriptors if am7audioTokens is available.
         * @param {string} content
         * @returns {Array}
         */
        parseAudioTokens: function(content) {
            if (!content || !window.am7audioTokens) return [];
            return window.am7audioTokens.parse(content);
        },

        /**
         * Process content string: replace ${image.*} tokens with HTML for resolved images or placeholders.
         * Extracted from: view/chat.js processImageTokensInContent()
         * @param {string} content - message content
         * @param {string} msgRole - "user" or "assistant"
         * @param {number} msgIndex - index in message list
         * @param {Object} characters - {system, user} character objects
         * @param {Object} state - shared resolution state {resolvingImages: {}}
         * @param {Function} [onResolve] - callback after async resolution completes
         * @returns {string} content with tokens replaced by HTML
         */
        processImageTokens: function(content, msgRole, msgIndex, characters, state, onResolve) {
            if (!content || !window.am7imageTokens) return content;
            let tokens = window.am7imageTokens.parse(content);
            if (!tokens.length) return content;

            // Trigger async resolution for unresolved/uncached tokens
            if (state && !state.resolvingImages[msgIndex]) {
                let needsResolution = tokens.some(function(t) {
                    return !t.id || !window.am7imageTokens.cache[t.id];
                });
                if (needsResolution) {
                    state.resolvingImages[msgIndex] = true;
                    ChatTokenRenderer._resolveImages(msgIndex, characters, state, onResolve);
                }
            }

            // Replace tokens with HTML (reverse order to preserve positions)
            let result = content;
            for (let i = tokens.length - 1; i >= 0; i--) {
                let token = tokens[i];
                let html;
                if (token.id && window.am7imageTokens.cache[token.id]) {
                    let cached = window.am7imageTokens.cache[token.id];
                    html = '<img src="' + cached.url + '" class="max-w-[256px] rounded-lg my-2 cursor-pointer" onclick="page.imageView(window.am7imageTokens.cache[\'' + token.id + '\'].image)" />';
                } else {
                    html = '<span class="inline-flex items-center gap-1 px-2 py-1 rounded bg-gray-500/30 text-xs my-1"><span class="material-symbols-outlined text-xs">photo_camera</span>' + token.tags.join(", ") + '...</span>';
                }
                result = result.substring(0, token.start) + html + result.substring(token.end);
            }
            return result;
        },

        /**
         * Process content string: replace ${audio.text} tokens with inline audio player buttons.
         * Extracted from: view/chat.js processAudioTokensInContent()
         * @param {string} content - message content
         * @param {string} msgRole - "user" or "assistant"
         * @param {number} msgIndex - index in message list
         * @param {Object} characters - {system, user} character objects with profile.objectId
         * @param {string} sessionId - session objectId for stable naming
         * @returns {string} content with tokens replaced by audio player buttons
         */
        processAudioTokens: function(content, msgRole, msgIndex, characters, sessionId) {
            if (!content || !window.am7audioTokens) return content;
            let tokens = window.am7audioTokens.parse(content);
            if (!tokens.length) return content;

            let profileId;
            if (msgRole === "assistant" && characters && characters.system) {
                profileId = characters.system.profile ? characters.system.profile.objectId : undefined;
            } else if (characters && characters.user) {
                profileId = characters.user.profile ? characters.user.profile.objectId : undefined;
            }

            let sid = sessionId || 'nosess';

            let result = content;
            for (let i = tokens.length - 1; i >= 0; i--) {
                let token = tokens[i];
                let textHash = (page.components && page.components.audioComponents)
                    ? page.components.audioComponents.simpleHash(token.text)
                    : token.text.length;
                let name = sid + '-' + msgRole + '-audio-' + msgIndex + '-' + i + '-' + textHash;
                window.am7audioTokens.register(name, token.text, profileId);
                let state = window.am7audioTokens.state(name);

                let icon, iconClass;
                switch (state) {
                    case 'loading':
                        icon = 'hourglass_top';
                        iconClass = ' animate-pulse';
                        break;
                    case 'playing':
                        icon = 'pause_circle';
                        iconClass = '';
                        break;
                    default:
                        icon = 'play_circle';
                        iconClass = '';
                        break;
                }

                let btn = '<button class="inline-flex items-center p-1 rounded-full bg-indigo-500/20 hover:bg-indigo-500/40 border border-indigo-500/30 align-middle cursor-pointer select-none" ' +
                    'onclick="window.am7audioTokens.play(\'' + name + '\'); return false;" ' +
                    'title="' + escapeHtmlAttr(token.text) + '">' +
                    '<span class="material-symbols-outlined text-lg text-indigo-400' + iconClass + '">' + icon + '</span></button>';
                let html = btn + ' *"' + token.text + '"*';

                result = result.substring(0, token.start) + html + result.substring(token.end);
            }
            return result;
        },

        /**
         * Apply all content pruning for display.
         * Wraps LLMConnector.prune* methods with common display pipeline.
         * @param {string} content
         * @param {boolean} hideThoughts - whether to remove <think>/<thought> tags
         * @returns {string}
         */
        pruneForDisplay: function(content, hideThoughts) {
            if (!content) return "";
            let c = content;
            if (hideThoughts) {
                c = LLMConnector.pruneTag(c, "think");
                c = LLMConnector.pruneTag(c, "thought");
            }
            c = LLMConnector.pruneOut(c, "--- CITATION", "END CITATIONS ---");
            c = LLMConnector.pruneToMark(c, "<|reserved_special_token");
            c = LLMConnector.pruneToMark(c, "(Metrics");
            c = LLMConnector.pruneToMark(c, "(Reminder");
            c = LLMConnector.pruneToMark(c, "(KeyFrame");
            c = LLMConnector.pruneOther(c);
            return c;
        },

        /**
         * Internal: Async image resolution pipeline.
         * Resolves one token at a time, then triggers redraw.
         * @private
         */
        _resolveImages: async function(msgIndex, characters, state, onResolve) {
            if (!window.am7imageTokens || !state) return;
            try {
                // Note: The actual resolution requires access to the message content and
                // a server-patch callback. This method is intentionally a lightweight wrapper;
                // the full resolution with server patching is still done by the view layer
                // since it depends on the chatConfig history and inst context.
                if (onResolve) {
                    await onResolve(msgIndex);
                }
            } finally {
                if (state) state.resolvingImages[msgIndex] = false;
                if (typeof m !== "undefined") m.redraw();
            }
        }
    };

    // ── Export ───────────────────────────────────────────────────────────

    if (typeof module != "undefined") {
        module.ChatTokenRenderer = ChatTokenRenderer;
    } else {
        window.ChatTokenRenderer = ChatTokenRenderer;
    }

    console.log("[ChatTokenRenderer] loaded");
}());
