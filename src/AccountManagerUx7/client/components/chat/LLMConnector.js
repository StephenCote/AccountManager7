/**
 * LLMConnector — Unified LLM interaction API
 * Phase 10a: Extracts duplicated patterns from chat.js, SessionDirector.js, and llmBase.js
 * into a single reusable module.
 *
 * Resolves: OI-46 (missing chat.js exports), OI-47 (SessionDirector duplication),
 *           OI-24 (config update-if-changed), OI-40 (policyEvent handler)
 *
 * Exposes: window.LLMConnector
 */
(function() {
    "use strict";

    // ── Policy Event System (OI-40) ─────────────────────────────────────
    let _policyEventHandlers = [];

    // ── Error State (OI-47: extracted from SessionDirector) ─────────────
    let _errorState = {
        lastError: null,
        consecutiveErrors: 0,
        reset: function() {
            _errorState.lastError = null;
            _errorState.consecutiveErrors = 0;
        },
        record: function(error) {
            _errorState.lastError = error;
            _errorState.consecutiveErrors++;
        }
    };

    // ── LLMConnector ────────────────────────────────────────────────────

    let LLMConnector = {

        // ── Config Management ───────────────────────────────────────────

        /**
         * Find the ~/Chat directory.
         * Extracted from: llmBase.js CardGameLLM.findChatDir()
         * @returns {Object|null} group object
         */
        findChatDir: async function() {
            let chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
            if (!chatDir) {
                console.warn("[LLMConnector] ~/Chat not found");
            }
            return chatDir;
        },

        /**
         * Find the "Open Chat" template chatConfig from a directory.
         * Extracted from: llmBase.js CardGameLLM.getOpenChatTemplate()
         * @param {Object} chatDir - group object for ~/Chat
         * @returns {Object|null} full chatConfig
         */
        getOpenChatTemplate: async function(chatDir) {
            if (!chatDir) return null;
            let chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 50);
            let templateCfg = chatConfigs.find(function(c) { return c.name === "Open Chat"; });
            if (!templateCfg) {
                console.warn("[LLMConnector] 'Open Chat' config not found");
                return null;
            }
            return am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);
        },

        /**
         * Create-or-find prompt config. Updates system[] if config exists and differs.
         * Extracted from: llmBase.js ensurePromptConfig() + SessionDirector._ensurePromptConfig()
         * Addresses: OI-24 (update-if-changed)
         * @param {string} name - prompt config name
         * @param {string[]} system - system prompt lines
         * @param {Object} [groupOverride] - group object; defaults to ~/Chat
         * @returns {Object|null} promptConfig
         */
        ensurePrompt: async function(name, system, groupOverride) {
            try {
                let group = groupOverride || await LLMConnector.findChatDir();
                if (!group) return null;

                let q = am7view.viewQuery(am7model.newInstance("olio.llm.promptConfig"));
                q.field("groupId", group.id);
                q.field("name", name);
                q.cache(false);
                let qr = await page.search(q);

                if (qr && qr.results && qr.results.length > 0) {
                    let existing = qr.results[0];
                    // OI-24: Update system prompt if changed
                    let existingSystem = existing.system || [];
                    let newSystem = system || [];
                    if (JSON.stringify(existingSystem) !== JSON.stringify(newSystem)) {
                        existing.system = newSystem;
                        await page.patchObject(existing);
                        console.log("[LLMConnector] Updated prompt system lines: " + name);
                    }
                    return existing;
                }

                // Create new
                let icfg = am7model.newInstance("olio.llm.promptConfig");
                icfg.api.groupId(group.id);
                icfg.api.groupPath(group.path);
                icfg.api.name(name);
                icfg.entity.system = system || [];
                await page.createObject(icfg.entity);
                qr = await page.search(q);
                return (qr && qr.results && qr.results.length > 0) ? qr.results[0] : null;
            } catch (err) {
                console.error("[LLMConnector] ensurePrompt failed:", err);
                return null;
            }
        },

        /**
         * Create-or-find chat config from template. Syncs key fields if config exists and differs.
         * Extracted from: llmBase.js ensureChatConfig() + SessionDirector._ensureChatConfig()
         * Addresses: OI-24 (update-if-changed)
         * @param {string} name - chat config name
         * @param {Object} template - full chatConfig to clone from
         * @param {Object} [overrides] - field overrides {model, serverUrl, messageTrim, ...}
         * @param {Object} [groupOverride] - group object; defaults to ~/Chat
         * @returns {Object|null} chatConfig
         */
        ensureConfig: async function(name, template, overrides, groupOverride) {
            try {
                let group = groupOverride || await LLMConnector.findChatDir();
                if (!group) return null;

                let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatConfig"));
                q.field("groupId", group.id);
                q.field("name", name);
                q.cache(false);
                let qr = await page.search(q);

                if (qr && qr.results && qr.results.length > 0) {
                    let existing = qr.results[0];
                    // OI-24: Sync key fields from template if changed
                    let needsPatch = false;
                    let syncFields = ["serverUrl", "serviceType", "model", "apiVersion"];
                    for (let i = 0; i < syncFields.length; i++) {
                        let f = syncFields[i];
                        if (template && template[f] && existing[f] !== template[f]) {
                            existing[f] = template[f];
                            needsPatch = true;
                        }
                    }
                    if (overrides) {
                        for (let f in overrides) {
                            if (!overrides.hasOwnProperty(f)) continue;
                            if (f === "chatOptions" && typeof overrides[f] === "object") {
                                // Merge chatOptions overrides
                                if (!existing.chatOptions) existing.chatOptions = { schema: "olio.llm.chatOptions" };
                                for (let k in overrides[f]) {
                                    if (overrides[f].hasOwnProperty(k) && existing.chatOptions[k] !== overrides[f][k]) {
                                        existing.chatOptions[k] = overrides[f][k];
                                        needsPatch = true;
                                    }
                                }
                            } else if (existing[f] !== overrides[f]) {
                                existing[f] = overrides[f];
                                needsPatch = true;
                            }
                        }
                    }
                    if (needsPatch) {
                        delete existing.apiKey; // EncryptFieldProvider safety
                        await page.patchObject(existing);
                        console.log("[LLMConnector] Updated config fields: " + name);
                    }
                    return existing;
                }

                // Create new from template — clone all standard chatConfig fields
                let newCfg = {
                    schema: "olio.llm.chatConfig",
                    groupId: group.id,
                    groupPath: group.path,
                    name: name
                };
                if (template) {
                    let cloneFields = ["model", "serverUrl", "serviceType", "apiVersion",
                        "rating", "setting", "assist", "stream", "prune", "useNLP",
                        "messageTrim", "remindEvery", "keyframeEvery", "autoTitle",
                        "autoTunePrompts", "autoTuneChatOptions",
                        "extractMemories", "memoryBudget", "memoryExtractionEvery",
                        "requestTimeout", "terrain", "populationDescription",
                        "animalDescription", "universeName", "worldName"];
                    for (let i = 0; i < cloneFields.length; i++) {
                        let f = cloneFields[i];
                        if (template[f] !== undefined) newCfg[f] = template[f];
                    }
                    // Clone chatOptions sub-object
                    if (template.chatOptions) {
                        let co = { schema: "olio.llm.chatOptions" };
                        let optKeys = ["max_tokens", "min_p", "num_ctx", "num_gpu",
                            "repeat_last_n", "repeat_penalty", "temperature", "top_k",
                            "top_p", "typical_p", "frequency_penalty", "presence_penalty", "seed"];
                        for (let i = 0; i < optKeys.length; i++) {
                            if (template.chatOptions[optKeys[i]] !== undefined) {
                                co[optKeys[i]] = template.chatOptions[optKeys[i]];
                            }
                        }
                        newCfg.chatOptions = co;
                    }
                }
                if (overrides) {
                    for (let f in overrides) {
                        if (overrides.hasOwnProperty(f)) {
                            if (f === "chatOptions" && newCfg.chatOptions && typeof overrides[f] === "object") {
                                // Merge chatOptions overrides
                                for (let k in overrides[f]) {
                                    if (overrides[f].hasOwnProperty(k)) newCfg.chatOptions[k] = overrides[f][k];
                                }
                            } else {
                                newCfg[f] = overrides[f];
                            }
                        }
                    }
                }
                await page.createObject(newCfg);
                qr = await page.search(q);
                return (qr && qr.results && qr.results.length > 0) ? qr.results[0] : null;
            } catch (err) {
                console.error("[LLMConnector] ensureConfig failed:", err);
                return null;
            }
        },

        /**
         * Get or create a chat session (chatRequest).
         * Delegates to am7chat.getChatRequest.
         * @param {string} name - session name
         * @param {Object} chatCfg - chatConfig
         * @param {Object} promptCfg - promptConfig
         * @returns {Object} chatRequest
         */
        createSession: async function(name, chatCfg, promptCfg) {
            return am7chat.getChatRequest(name, chatCfg, promptCfg);
        },

        // ── Chat Operations ─────────────────────────────────────────────

        /**
         * Send message via REST (buffered mode).
         * Extracted from: chat.js am7chat.chat()
         * @param {Object} session - chatRequest
         * @param {string} message
         * @returns {Object} chat response
         */
        chat: async function(session, message) {
            if (!session) {
                console.error("[LLMConnector] Chat request is not defined.");
                return null;
            }
            if (!session.chatConfig || !session.promptConfig) {
                console.error("[LLMConnector] Chat request is missing the prompt or chat config.");
                return null;
            }
            session.message = message;
            session.uid = page.uid();
            return m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/text",
                withCredentials: true,
                body: session
            });
        },

        /**
         * Send message via WebSocket (streaming mode).
         * Extracted from: view/chat.js doChat() streaming path
         * @param {Object} session - chatRequest with objectId and schema
         * @param {string} message
         * @param {Object} callbacks - {onchatstart, onchatupdate, onchatcomplete, onchaterror}
         */
        streamChat: function(session, message, callbacks) {
            if (!session || !session.objectId) {
                console.error("[LLMConnector] streamChat requires session with objectId");
                return;
            }
            if (!page.wss) {
                console.error("[LLMConnector] WebSocket service not available");
                return;
            }
            let schema = session[am7model.jsonModelKey] || session.schema || "olio.llm.chatRequest";
            let chatReq = {
                schema: schema,
                objectId: session.objectId,
                uid: page.uid(),
                message: message
            };
            page.chatStream = callbacks;
            page.wss.send("chat", JSON.stringify(chatReq), undefined, schema);
        },

        /**
         * Cancel an active stream.
         * @param {Object} session - chatRequest
         */
        cancelStream: function(session) {
            if (!session || !page.wss) return;
            let schema = session[am7model.jsonModelKey] || session.schema || "olio.llm.chatRequest";
            let stopReq = {
                schema: schema,
                objectId: session.objectId,
                uid: page.uid(),
                message: "[stop]"
            };
            page.wss.send("chat", JSON.stringify(stopReq), undefined, schema);
        },

        /**
         * Retrieve message history from server.
         * Extracted from: view/chat.js getHistory()
         * @param {Object} session - object with objectId (and optionally schema)
         * @returns {Object|null} - {messages: [...]}
         */
        getHistory: async function(session) {
            if (!session || !session.objectId) {
                return null;
            }
            let schema = session[am7model.jsonModelKey] || session.schema || "olio.llm.chatRequest";
            let chatReq = {
                schema: schema,
                objectId: session.objectId,
                uid: page.uid()
            };
            return m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/history",
                withCredentials: true,
                body: chatReq
            });
        },

        // ── Response Processing ─────────────────────────────────────────

        /**
         * Extract last assistant message content from any response shape.
         * Extracted from: SessionDirector._extractContent() (handles 5+ shapes)
         * Handles: string, {messages: [...]}, {message}, {content},
         *          {choices: [{message: {content}}]}, {results: [{content}]}
         * @param {Object|string} response
         * @returns {string|null}
         */
        extractContent: function(response) {
            if (!response) return null;
            if (typeof response === 'string') return response;

            // olio.llm.chatResponse — extract last assistant message
            if (response.messages && Array.isArray(response.messages)) {
                for (let i = response.messages.length - 1; i >= 0; i--) {
                    if (response.messages[i].role === 'assistant') {
                        return response.messages[i].displayContent || response.messages[i].content || "";
                    }
                }
            }

            if (response.message) return response.message;
            if (response.content) return response.content;
            if (response.choices && response.choices[0]) {
                let choice = response.choices[0];
                return (choice.message && choice.message.content) || choice.content || choice.text;
            }
            if (response.results && response.results.length > 0) {
                return response.results[0].content || response.results[0].message;
            }

            return null;
        },

        /**
         * Parse JSON directive from LLM response content.
         * Extracted from: SessionDirector._parseDirective() (JSON parsing + lenient repair only).
         * Domain-specific directive validation remains in SessionDirector.
         * @param {string} content - raw LLM response
         * @param {Object} [options] - {strict: false} for strict-only parsing
         * @returns {Object|null} parsed JSON object
         */
        parseDirective: function(content, options) {
            if (!content) return null;
            let opts = options || {};

            let jsonStr = content.trim();

            // Strip markdown code fences
            jsonStr = jsonStr.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '');

            // Find the first { to last } block
            let firstBrace = jsonStr.indexOf('{');
            if (firstBrace === -1) return null;

            let lastBrace = jsonStr.lastIndexOf('}');
            if (lastBrace !== -1 && lastBrace > firstBrace) {
                jsonStr = jsonStr.substring(firstBrace, lastBrace + 1);
            } else {
                jsonStr = jsonStr.substring(firstBrace);
            }

            // Repair unbalanced braces/brackets
            jsonStr = LLMConnector.repairJson(jsonStr);

            // Try strict JSON first
            try {
                return JSON.parse(jsonStr);
            } catch (strictErr) {
                if (opts.strict) return null;
            }

            // Lenient JS-object parsing (unquoted keys, single quotes, trailing commas)
            try {
                let fixed = jsonStr;

                // Strip JS-style comments
                fixed = fixed.replace(/\/\/[^\n]*/g, '');
                fixed = fixed.replace(/\/\*[\s\S]*?\*\//g, '');

                // Protect existing double-quoted strings
                let preserved = [];
                fixed = fixed.replace(/"(?:[^"\\]|\\.)*"/g, function(match) {
                    preserved.push(match);
                    return '"__P' + (preserved.length - 1) + '__"';
                });

                // Quote unquoted keys
                fixed = fixed.replace(/([{,\[]\s*)([a-zA-Z_]\w*)\s*:/g, '$1"$2":');

                // Quote bare identifier values (not true/false/null)
                fixed = fixed.replace(/:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*([,}\]])/g, function(match, val, term) {
                    if (val === 'true' || val === 'false' || val === 'null') return match;
                    return ': "' + val + '"' + term;
                });
                fixed = fixed.replace(/([[,]\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=[,\]])/g, function(match, prefix, val) {
                    if (val === 'true' || val === 'false' || val === 'null') return match;
                    return prefix + '"' + val + '"';
                });

                // Convert single-quoted strings to double-quoted
                fixed = fixed.replace(/'([^']*?)'/g, '"$1"');

                // Remove trailing commas before } or ]
                fixed = fixed.replace(/,\s*([}\]])/g, '$1');

                // Remove leading commas after { or [
                fixed = fixed.replace(/([{\[]\s*),/g, '$1');

                // Restore preserved double-quoted strings
                fixed = fixed.replace(/"__P(\d+)__"/g, function(_, i) { return preserved[parseInt(i)]; });

                // Final repair pass
                fixed = LLMConnector.repairJson(fixed);

                return JSON.parse(fixed);
            } catch (lenientErr) {
                return null;
            }
        },

        /**
         * Repair truncated JSON (unbalanced braces/brackets).
         * Extracted from: SessionDirector._repairTruncatedJson()
         * @param {string} json
         * @returns {string}
         */
        repairJson: function(json) {
            if (!json) return json;

            // Remove trailing incomplete value
            json = json.replace(/,\s*[a-zA-Z_"'][^{}[\]]*$/, '');
            json = json.replace(/:\s*$/, ': null');
            json = json.replace(/,\s*$/, '');

            let openBraces = 0, openBrackets = 0;
            let inString = false, escape = false, stringChar = '';
            for (let i = 0; i < json.length; i++) {
                let ch = json[i];
                if (escape) { escape = false; continue; }
                if (ch === '\\') { escape = true; continue; }
                if (!inString && (ch === '"' || ch === "'")) { inString = true; stringChar = ch; continue; }
                if (inString && ch === stringChar) { inString = false; continue; }
                if (inString) continue;
                if (ch === '{') openBraces++;
                else if (ch === '}') openBraces--;
                else if (ch === '[') openBrackets++;
                else if (ch === ']') openBrackets--;
            }

            // Close unclosed strings
            if (inString) json += stringChar;

            if (openBrackets > 0 || openBraces > 0) {
                for (let i = 0; i < openBrackets; i++) json += ']';
                for (let i = 0; i < openBraces; i++) json += '}';
                // Clean up trailing commas before newly added closers
                json = json.replace(/,\s*([}\]])/g, '$1');
            }

            return json;
        },

        // ── Session Management ──────────────────────────────────────────

        /**
         * Delete session and associated data.
         * Extracted from: chat.js am7chat.deleteChat()
         * @param {Object} session - chatRequest
         * @param {boolean} force - skip confirmation
         * @param {Function} [callback]
         */
        deleteSession: async function(session, force, callback) {
            async function doDelete() {
                if (session.session) {
                    let q = am7client.newQuery(session.sessionType);
                    q.field("id", session.session.id);
                    try {
                        let qr = await page.search(q);
                        if (qr && qr.results && qr.results.length > 0) {
                            await page.deleteObject(session.sessionType, qr.results[0].objectId);
                        }
                    } catch (e) {
                        console.error("[LLMConnector] Error deleting session data", e);
                    }
                }
                await page.deleteObject(session[am7model.jsonModelKey], session.objectId);
                if (callback) callback(session);
            }
            if (!force) {
                page.components.dialog.confirm("Delete chat session?", doDelete);
            } else {
                await doDelete();
            }
        },

        // ── Content Pruning (extracted from view/chat.js) ───────────────

        /**
         * Remove XML tag and its content (case-insensitive, handles multiple occurrences).
         * @param {string} cnt - content string
         * @param {string} tag - tag name (e.g. "think")
         * @returns {string}
         */
        pruneTag: function(cnt, tag) {
            if (!cnt) return cnt || "";
            let tdx1 = cnt.toLowerCase().indexOf("<" + tag + ">");
            let maxCheck = 20;
            let check = 0;
            while (tdx1 > -1) {
                if (check++ >= maxCheck) break;
                let tdx2 = cnt.toLowerCase().indexOf("</" + tag + ">");
                if (tdx1 > -1 && tdx2 > -1 && tdx2 > tdx1) {
                    cnt = cnt.substring(0, tdx1) + cnt.substring(tdx2 + tag.length + 3, cnt.length);
                }
                tdx1 = cnt.toLowerCase().indexOf("<" + tag + ">");
            }
            return cnt;
        },

        /**
         * Truncate content at first occurrence of a marker string.
         * @param {string} cnt
         * @param {string} mark
         * @returns {string}
         */
        pruneToMark: function(cnt, mark) {
            if (!cnt) return cnt || "";
            let idx = cnt.indexOf(mark);
            if (idx > -1) {
                cnt = cnt.substring(0, idx);
            }
            return cnt;
        },

        /**
         * Remove known non-display artifacts ([interrupted], etc).
         * @param {string} cnt
         * @returns {string}
         */
        pruneOther: function(cnt) {
            if (!cnt) return cnt || "";
            return cnt.replace(/\[interrupted\]/g, "");
        },

        /**
         * Remove content between two marker strings.
         * @param {string} cnt
         * @param {string} start
         * @param {string} end
         * @returns {string}
         */
        pruneOut: function(cnt, start, end) {
            if (!cnt) return cnt || "";
            let idx1 = cnt.indexOf(start);
            let idx2 = cnt.indexOf(end);
            if (idx1 > -1 && idx2 > -1 && idx2 > idx1) {
                cnt = cnt.substring(0, idx1) + cnt.substring(idx2 + end.length, cnt.length);
            }
            return cnt;
        },

        /**
         * Apply all standard display pruning.
         * @param {string} cnt
         * @returns {string}
         */
        pruneAll: function(cnt) {
            if (!cnt) return "";
            cnt = LLMConnector.pruneToMark(cnt, "<|reserved_special_token");
            cnt = LLMConnector.pruneTag(cnt, "think");
            cnt = LLMConnector.pruneTag(cnt, "thought");
            cnt = LLMConnector.pruneToMark(cnt, "(Metrics");
            cnt = LLMConnector.pruneToMark(cnt, "(Reminder");
            cnt = LLMConnector.pruneToMark(cnt, "(KeyFrame");
            cnt = LLMConnector.pruneOther(cnt);
            return cnt;
        },

        /**
         * Remove fenced code blocks (```...```) from content.
         * Useful for stripping code artifacts from LLM responses.
         * @param {string} cnt - content string
         * @returns {string}
         */
        pruneCode: function(cnt) {
            if (!cnt) return cnt || "";
            return cnt.replace(/```[\s\S]*?```/g, "").trim();
        },

        // ── Config Cloning ──────────────────────────────────────────────

        /**
         * Shallow clone a config template and apply overrides.
         * Strips identity fields (objectId, id, urn) from the clone.
         * @param {Object} template
         * @param {Object} [overrides]
         * @returns {Object}
         */
        cloneConfig: function(template, overrides) {
            let clone = {};
            if (template) {
                for (let key in template) {
                    if (template.hasOwnProperty(key)) {
                        clone[key] = template[key];
                    }
                }
            }
            // Strip identity fields
            delete clone.objectId;
            delete clone.id;
            delete clone.urn;
            delete clone.createdDate;
            delete clone.modifiedDate;

            if (overrides) {
                for (let key in overrides) {
                    if (overrides.hasOwnProperty(key)) {
                        clone[key] = overrides[key];
                    }
                }
            }
            return clone;
        },

        // ── Policy Event System (OI-40) ─────────────────────────────────

        /**
         * Register a handler for policyEvent WebSocket messages.
         * @param {Function} handler - called with {type, data}
         */
        onPolicyEvent: function(handler) {
            if (typeof handler === "function") {
                _policyEventHandlers.push(handler);
            }
        },

        /**
         * Dispatch a policy event to all registered handlers.
         * Called from pageClient.js chirp routing.
         * @param {Object} data - {type, data}
         */
        handlePolicyEvent: function(data) {
            for (let i = 0; i < _policyEventHandlers.length; i++) {
                try {
                    _policyEventHandlers[i](data);
                } catch (e) {
                    console.error("[LLMConnector] Policy event handler error:", e);
                }
            }
        },

        /**
         * Remove a previously registered policy event handler.
         * @param {Function} handler
         */
        removePolicyEventHandler: function(handler) {
            let idx = _policyEventHandlers.indexOf(handler);
            if (idx > -1) _policyEventHandlers.splice(idx, 1);
        },

        // ── Error State ─────────────────────────────────────────────────

        errorState: _errorState,

        // ── Phase 13f item 24: Memory event handling (OI-71, OI-72) ────

        /** Last memory event received via WebSocket */
        lastMemoryEvent: null,

        /**
         * Handle incoming memory events from WebSocket.
         * @param {Object} data - { type: "recalled"|"extracted"|"keyframe", data: {...} }
         */
        handleMemoryEvent: function(data) {
            LLMConnector.lastMemoryEvent = data;
            if (typeof m !== "undefined") m.redraw();
        },

        /**
         * Background activity indicator for async keyframe/memory operations.
         * Set by evalProgress WebSocket handler, read by chat view.
         * @type {null|{icon: string, label: string}}
         */
        bgActivity: null,
        _bgActivityTimer: null,

        setBgActivity: function(icon, label) {
            if (LLMConnector._bgActivityTimer) {
                clearTimeout(LLMConnector._bgActivityTimer);
                LLMConnector._bgActivityTimer = null;
            }
            LLMConnector.bgActivity = icon && label ? { icon: icon, label: label } : null;
            // Safety auto-clear after 90s in case the "done" event is lost
            if (LLMConnector.bgActivity) {
                LLMConnector._bgActivityTimer = setTimeout(function() {
                    LLMConnector.bgActivity = null;
                    LLMConnector._bgActivityTimer = null;
                    if (typeof m !== "undefined") m.redraw();
                }, 90000);
            }
            if (typeof m !== "undefined") m.redraw();
        }
    };

    // ── Export ───────────────────────────────────────────────────────────

    if (typeof module != "undefined") {
        module.LLMConnector = LLMConnector;
    } else {
        window.LLMConnector = LLMConnector;
    }

    console.log("[LLMConnector] loaded");
}());
