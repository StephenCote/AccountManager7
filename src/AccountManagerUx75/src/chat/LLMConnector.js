/**
 * LLMConnector — Unified LLM interaction API (ESM port)
 * Core module for all chat/LLM operations: config management, chat/stream,
 * response processing, library resolution, policy/memory events.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { applicationPath } from '../core/config.js';

// ── Policy Event System ──────────────────────────────────────────────
let _policyEventHandlers = [];

// ── Error State ──────────────────────────────────────────────────────
let _errorState = {
    lastError: null,
    consecutiveErrors: 0,
    reset() {
        _errorState.lastError = null;
        _errorState.consecutiveErrors = 0;
    },
    record(error) {
        _errorState.lastError = error;
        _errorState.consecutiveErrors++;
    }
};

const LLMConnector = {

    // ── Config Management ────────────────────────────────────────────

    findChatDir: async function() {
        let chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
        if (!chatDir) console.warn("[LLMConnector] ~/Chat not found");
        return chatDir;
    },

    getOpenChatTemplate: async function(chatDir) {
        if (chatDir) {
            let chatConfigs = await page.listObjects("olio.llm.chatConfig", chatDir.objectId, null, 0, 50);
            let templateCfg = chatConfigs.find(c => c.name === "Open Chat");
            if (templateCfg) {
                return am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);
            }
        }
        let resolved = await LLMConnector.resolveConfig("Open Chat");
        if (resolved) return resolved;
        resolved = await LLMConnector.resolveConfig("generalChat");
        if (resolved) return resolved;
        console.warn("[LLMConnector] No chat template found");
        return null;
    },

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
                let existingSystem = existing.system || [];
                let newSystem = system || [];
                if (JSON.stringify(existingSystem) !== JSON.stringify(newSystem)) {
                    existing.system = newSystem;
                    await page.patchObject(existing);
                }
                return existing;
            }

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
                let needsPatch = false;
                let syncFields = ["serverUrl", "serviceType", "model", "apiVersion"];
                for (let f of syncFields) {
                    if (template && template[f] && existing[f] !== template[f]) {
                        existing[f] = template[f];
                        needsPatch = true;
                    }
                }
                if (overrides) {
                    for (let f in overrides) {
                        if (!overrides.hasOwnProperty(f)) continue;
                        if (f === "chatOptions" && typeof overrides[f] === "object") {
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
                    delete existing.apiKey;
                    await page.patchObject(existing);
                }
                return existing;
            }

            // Create new from template
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
                for (let f of cloneFields) {
                    if (template[f] !== undefined) newCfg[f] = template[f];
                }
                if (template.chatOptions) {
                    let co = { schema: "olio.llm.chatOptions" };
                    let optKeys = ["max_tokens", "min_p", "num_ctx", "num_gpu",
                        "repeat_last_n", "repeat_penalty", "temperature", "top_k",
                        "top_p", "typical_p", "frequency_penalty", "presence_penalty", "seed"];
                    for (let k of optKeys) {
                        if (template.chatOptions[k] !== undefined) co[k] = template.chatOptions[k];
                    }
                    newCfg.chatOptions = co;
                }
            }
            if (overrides) {
                for (let f in overrides) {
                    if (overrides.hasOwnProperty(f)) {
                        if (f === "chatOptions" && newCfg.chatOptions && typeof overrides[f] === "object") {
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

    createSession: async function(name, chatCfg, promptCfg) {
        // Delegates to chatUtil.getChatRequest — import lazily to avoid circular
        const { am7chat } = await import('./chatUtil.js');
        return am7chat.getChatRequest(name, chatCfg, promptCfg);
    },

    // ── Chat Operations ──────────────────────────────────────────────

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
        let timeoutMs = ((session.chatConfig && session.chatConfig.requestTimeout) || 120) * 1000;
        return m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/text",
            withCredentials: true,
            body: session,
            config: function(xhr) { xhr.timeout = timeoutMs; }
        });
    },

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

    getHistory: async function(session) {
        if (!session || !session.objectId) return null;
        let schema = session[am7model.jsonModelKey] || session.schema || "olio.llm.chatRequest";
        let chatReq = {
            schema: schema,
            objectId: session.objectId,
            uid: page.uid()
        };
        return m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/history",
            withCredentials: true,
            body: chatReq
        });
    },

    // ── Response Processing ──────────────────────────────────────────

    extractContent: function(response) {
        if (!response) return null;
        if (typeof response === 'string') return response;

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

    parseDirective: function(content, options) {
        if (!content) return null;
        let opts = options || {};
        let jsonStr = content.trim();

        jsonStr = jsonStr.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '');
        let firstBrace = jsonStr.indexOf('{');
        if (firstBrace === -1) return null;

        let lastBrace = jsonStr.lastIndexOf('}');
        if (lastBrace !== -1 && lastBrace > firstBrace) {
            jsonStr = jsonStr.substring(firstBrace, lastBrace + 1);
        } else {
            jsonStr = jsonStr.substring(firstBrace);
        }

        jsonStr = LLMConnector.repairJson(jsonStr);

        try {
            return JSON.parse(jsonStr);
        } catch (strictErr) {
            if (opts.strict) return null;
        }

        // Lenient JS-object parsing
        try {
            let fixed = jsonStr;
            fixed = fixed.replace(/\/\/[^\n]*/g, '');
            fixed = fixed.replace(/\/\*[\s\S]*?\*\//g, '');

            let preserved = [];
            fixed = fixed.replace(/"(?:[^"\\]|\\.)*"/g, function(match) {
                preserved.push(match);
                return '"__P' + (preserved.length - 1) + '__"';
            });

            fixed = fixed.replace(/([{,\[]\s*)([a-zA-Z_]\w*)\s*:/g, '$1"$2":');
            fixed = fixed.replace(/:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*([,}\]])/g, function(match, val, term) {
                if (val === 'true' || val === 'false' || val === 'null') return match;
                return ': "' + val + '"' + term;
            });
            fixed = fixed.replace(/([[,]\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=[,\]])/g, function(match, prefix, val) {
                if (val === 'true' || val === 'false' || val === 'null') return match;
                return prefix + '"' + val + '"';
            });

            fixed = fixed.replace(/'([^']*?)'/g, '"$1"');
            fixed = fixed.replace(/,\s*([}\]])/g, '$1');
            fixed = fixed.replace(/([{\[]\s*),/g, '$1');
            fixed = fixed.replace(/"__P(\d+)__"/g, function(_, i) { return preserved[parseInt(i)]; });
            fixed = LLMConnector.repairJson(fixed);
            return JSON.parse(fixed);
        } catch (lenientErr) {
            return null;
        }
    },

    repairJson: function(json) {
        if (!json) return json;
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

        if (inString) json += stringChar;
        if (openBrackets > 0 || openBraces > 0) {
            for (let i = 0; i < openBrackets; i++) json += ']';
            for (let i = 0; i < openBraces; i++) json += '}';
            json = json.replace(/,\s*([}\]])/g, '$1');
        }
        return json;
    },

    // ── Session Management ───────────────────────────────────────────

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

    // ── Content Pruning ──────────────────────────────────────────────

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

    pruneToMark: function(cnt, mark) {
        if (!cnt) return cnt || "";
        let idx = cnt.indexOf(mark);
        if (idx > -1) cnt = cnt.substring(0, idx);
        return cnt;
    },

    pruneOther: function(cnt) {
        if (!cnt) return cnt || "";
        return cnt.replace(/\[interrupted\]/g, "");
    },

    pruneOut: function(cnt, start, end) {
        if (!cnt) return cnt || "";
        let idx1 = cnt.indexOf(start);
        let idx2 = cnt.indexOf(end);
        if (idx1 > -1 && idx2 > -1 && idx2 > idx1) {
            cnt = cnt.substring(0, idx1) + cnt.substring(idx2 + end.length, cnt.length);
        }
        return cnt;
    },

    pruneAll: function(cnt) {
        if (!cnt) return "";
        cnt = LLMConnector.pruneToMark(cnt, "<|reserved_special_token");
        cnt = LLMConnector.pruneTag(cnt, "think");
        cnt = LLMConnector.pruneTag(cnt, "thought");
        cnt = LLMConnector.pruneToMark(cnt, "(Metrics");
        cnt = LLMConnector.pruneToMark(cnt, "(Reminder");
        cnt = LLMConnector.pruneToMark(cnt, "(KeyFrame");
        cnt = LLMConnector.pruneOut(cnt, "--- INTERACTION HISTORY", "END INTERACTION HISTORY ---");
        cnt = LLMConnector.pruneOther(cnt);
        return cnt;
    },

    pruneCode: function(cnt) {
        if (!cnt) return cnt || "";
        return cnt.replace(/```[\s\S]*?```/g, "").trim();
    },

    // ── Config Cloning ───────────────────────────────────────────────

    cloneConfig: function(template, overrides) {
        let clone = {};
        if (template) {
            for (let key in template) {
                if (template.hasOwnProperty(key)) clone[key] = template[key];
            }
        }
        delete clone.objectId;
        delete clone.id;
        delete clone.urn;
        delete clone.createdDate;
        delete clone.modifiedDate;

        if (overrides) {
            for (let key in overrides) {
                if (overrides.hasOwnProperty(key)) clone[key] = overrides[key];
            }
        }
        return clone;
    },

    // ── Policy Event System ──────────────────────────────────────────

    onPolicyEvent: function(handler) {
        if (typeof handler === "function") _policyEventHandlers.push(handler);
    },

    handlePolicyEvent: function(data) {
        for (let h of _policyEventHandlers) {
            try { h(data); } catch (e) { console.error("[LLMConnector] Policy event handler error:", e); }
        }
    },

    removePolicyEventHandler: function(handler) {
        let idx = _policyEventHandlers.indexOf(handler);
        if (idx > -1) _policyEventHandlers.splice(idx, 1);
    },

    // ── Error State ──────────────────────────────────────────────────

    errorState: _errorState,

    // ── Memory Events ────────────────────────────────────────────────

    lastMemoryEvent: null,

    handleMemoryEvent: function(data) {
        LLMConnector.lastMemoryEvent = data;
        m.redraw();
    },

    // ── Background Activity ──────────────────────────────────────────

    bgActivity: null,
    _bgActivityTimer: null,
    _bgActivityLock: 0,

    lockBgActivity: function() {
        return ++LLMConnector._bgActivityLock;
    },

    unlockBgActivity: function(token) {
        if (LLMConnector._bgActivityLock === token) LLMConnector._bgActivityLock = 0;
    },

    setBgActivity: function(icon, label) {
        if (LLMConnector._bgActivityLock > 0 && !(icon && label)) return;
        if (LLMConnector._bgActivityTimer) {
            clearTimeout(LLMConnector._bgActivityTimer);
            LLMConnector._bgActivityTimer = null;
        }
        LLMConnector.bgActivity = icon && label ? { icon, label } : null;
        if (LLMConnector.bgActivity) {
            LLMConnector._bgActivityTimer = setTimeout(function() {
                LLMConnector.bgActivity = null;
                LLMConnector._bgActivityTimer = null;
                LLMConnector._bgActivityLock = 0;
                m.redraw();
            }, 90000);
        }
        m.redraw();
    },

    // ── Shared Library ───────────────────────────────────────────────

    _libraryStatus: null,
    _libraryStatusChecked: false,

    checkLibrary: async function() {
        try {
            return await m.request({
                method: 'GET',
                url: applicationPath + "/rest/chat/library/status",
                withCredentials: true
            });
        } catch (err) {
            console.error("[LLMConnector] checkLibrary failed:", err);
            return { initialized: false };
        }
    },

    initLibrary: async function(serverUrl, model, serviceType) {
        try {
            return await m.request({
                method: 'POST',
                url: applicationPath + "/rest/chat/library/init",
                withCredentials: true,
                body: { serverUrl, model, serviceType }
            });
        } catch (err) {
            console.error("[LLMConnector] initLibrary failed:", err);
            return null;
        }
    },

    initPromptLibrary: async function() {
        try {
            return await m.request({
                method: 'POST',
                url: applicationPath + "/rest/chat/library/prompt/init",
                withCredentials: true
            });
        } catch (err) {
            console.error("[LLMConnector] initPromptLibrary failed:", err);
            return null;
        }
    },

    initPolicyLibrary: async function() {
        try {
            return await m.request({
                method: 'POST',
                url: applicationPath + "/rest/chat/library/policy/init",
                withCredentials: true
            });
        } catch (err) {
            console.error("[LLMConnector] initPolicyLibrary failed:", err);
            return null;
        }
    },

    getLibraryGroup: async function(type) {
        try {
            let result = await m.request({
                method: 'GET',
                url: applicationPath + "/rest/chat/library/dir/" + encodeURIComponent(type),
                withCredentials: true,
                extract: function(xhr) {
                    if (xhr.status === 404) return null;
                    try { return JSON.parse(xhr.responseText); } catch(e) { return null; }
                }
            });
            if (result && result.error) return null;
            return result;
        } catch (err) {
            return null;
        }
    },

    findLibraryChatDir: async function() {
        let dir = await page.findObject("auth.group", "DATA", "/Library/ChatConfigs");
        if (!dir) console.warn("[LLMConnector] /Library/ChatConfigs not found");
        return dir;
    },

    resolveConfig: async function(name, group) {
        try {
            let url = applicationPath + "/rest/chat/library/chat/" + encodeURIComponent(name);
            if (group) url += "?group=" + encodeURIComponent(group);
            return await m.request({ method: 'GET', url, withCredentials: true });
        } catch (err) {
            console.warn("[LLMConnector] resolveConfig failed for '" + name + "':", err);
            return null;
        }
    },

    resolvePrompt: async function(name, group) {
        try {
            let url = applicationPath + "/rest/chat/library/prompt/" + encodeURIComponent(name);
            if (group) url += "?group=" + encodeURIComponent(group);
            return await m.request({ method: 'GET', url, withCredentials: true });
        } catch (err) {
            console.warn("[LLMConnector] resolvePrompt failed for '" + name + "':", err);
            return null;
        }
    },

    resolveTemplate: async function(name, group) {
        try {
            let url = applicationPath + "/rest/chat/library/template/" + encodeURIComponent(name);
            if (group) url += "?group=" + encodeURIComponent(group);
            return await m.request({ method: 'GET', url, withCredentials: true });
        } catch (err) {
            console.warn("[LLMConnector] resolveTemplate failed for '" + name + "':", err);
            return null;
        }
    },

    ensureLibrary: async function() {
        if (LLMConnector._libraryStatusChecked && LLMConnector._libraryStatus
            && LLMConnector._libraryStatus.initialized) {
            return true;
        }
        let status = await LLMConnector.checkLibrary();
        LLMConnector._libraryStatus = status;
        LLMConnector._libraryStatusChecked = true;
        return status && status.initialized;
    },

    resetLibraryCache: function() {
        LLMConnector._libraryStatus = null;
        LLMConnector._libraryStatusChecked = false;
    }
};

export { LLMConnector };
export default LLMConnector;
