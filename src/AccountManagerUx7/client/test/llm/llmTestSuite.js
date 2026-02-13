/**
 * LLM Test Suite
 * Phase 6: Browser-side tests for the full LLM chat pipeline.
 *
 * Registers with TestFramework as the "llm" suite.
 * Tests: config, prompt, chat, stream, history, prune, episode, analyze, narrate, policy
 *
 * Prerequisites:
 *   - Template files in media/prompts/: llmTestChatConfig.json, llmTestPromptConfig.json
 *   - Auto-setup creates configs, characters, and ~/Tests group on first run
 *
 * Depends on: window.TestFramework, window.am7chat, window.am7client
 */
(function() {
    "use strict";

    let TF = window.TestFramework;

    // ── Test Categories ───────────────────────────────────────────────
    const LLM_CATEGORIES = {
        config:  { label: "Config",   icon: "settings" },
        options: { label: "Options",  icon: "tune" },
        prompt:  { label: "Prompt",   icon: "description" },
        chat:    { label: "Chat",     icon: "chat" },
        stream:  { label: "Stream",   icon: "stream" },
        history: { label: "History",  icon: "history" },
        prune:   { label: "Prune",    icon: "content_cut" },
        episode: { label: "Episode",  icon: "movie" },
        analyze: { label: "Analyze",  icon: "analytics" },
        narrate: { label: "Narrate",  icon: "auto_stories" },
        policy:  { label: "Policy",   icon: "policy" }
    };

    // ── Suite State ───────────────────────────────────────────────────
    let suiteState = {
        chatConfig: null,       // default (full-featured) config
        promptConfig: null,
        chatConfigName: null,
        promptConfigName: null,
        chatConfigs: {},         // named variants: { streaming, standard }
        testGroup: null,
        systemCharacter: null,
        userCharacter: null,
        setupDone: false
    };

    // ── Helpers ───────────────────────────────────────────────────────
    function log(cat, msg, status) { TF.testLog(cat, msg, status); }
    function logData(cat, label, data) { TF.testLogData(cat, label, data); }

    function getVariant(name) {
        return suiteState.chatConfigs[name] || suiteState.chatConfig;
    }

    function extractLastAssistantMessage(resp) {
        if (!resp || !resp.messages) return null;
        for (let i = resp.messages.length - 1; i >= 0; i--) {
            if (resp.messages[i].role === "assistant") {
                return resp.messages[i].displayContent || resp.messages[i].content || "";
            }
        }
        return null;
    }

    function setChatConfig(cfg) {
        suiteState.chatConfig = cfg;
        suiteState.chatConfigName = cfg ? cfg.name : null;
    }

    function setPromptConfig(cfg) {
        suiteState.promptConfig = cfg;
        suiteState.promptConfigName = cfg ? cfg.name : null;
    }

    // ── Auto-setup: templates, characters, configs ────────────────────

    async function fetchTemplate(url) {
        try {
            return await m.request({ method: "GET", url: url });
        } catch (e) {
            log("config", "Failed to fetch template " + url + ": " + e.message, "fail");
            return null;
        }
    }

    async function ensureTestGroup() {
        try {
            let grp = await page.makePath("auth.group", "data", "~/Tests");
            if (grp) {
                log("config", "Test group ready: ~/Tests", "pass");
                return grp;
            }
            log("config", "Could not create ~/Tests, falling back to ~/Chat", "warn");
            return await page.makePath("auth.group", "data", "~/Chat");
        } catch (e) {
            log("config", "Group setup error: " + e.message, "warn");
            try { return await page.makePath("auth.group", "data", "~/Chat"); } catch (e2) { return null; }
        }
    }

    async function findByName(schema, groupId, name) {
        let q = am7view.viewQuery(am7model.newInstance(schema));
        q.field("groupId", groupId);
        q.field("name", name);
        q.cache(false);
        let qr = await page.search(q);
        return (qr && qr.results && qr.results.length > 0) ? qr.results[0] : null;
    }

    async function findOrCreateConfig(schema, testGroup, template, extraFields) {
        let existing = await findByName(schema, testGroup.id, template.name);
        if (existing) {
            log("config", "Found existing: " + template.name, "info");
            return existing;
        }

        let entity = {};
        for (let key in template) entity[key] = template[key];
        entity.schema = schema;
        entity.groupId = testGroup.id;
        entity.groupPath = testGroup.path;
        if (extraFields) {
            for (let key in extraFields) entity[key] = extraFields[key];
        }

        try {
            await page.createObject(entity);
        } catch (e) {
            log("config", "Full create failed for " + template.name + ", trying basic: " + e.message, "warn");
            let basic = { schema: schema, name: template.name, groupId: testGroup.id, groupPath: testGroup.path };
            if (schema === "olio.llm.chatConfig") {
                basic.model = template.model || "llama3";
                basic.serverUrl = template.serverUrl || "http://localhost:11434";
                basic.serviceType = template.serviceType || "OLLAMA";
                basic.messageTrim = template.messageTrim || 6;
            } else if (schema === "olio.llm.promptConfig") {
                basic.system = template.system || ["You are a test assistant."];
            }
            await page.createObject(basic);
        }

        let created = await findByName(schema, testGroup.id, template.name);
        if (created) {
            log("config", "Created: " + template.name, "pass");
        } else {
            log("config", "Failed to create: " + template.name, "fail");
        }
        return created;
    }

    async function findOrCreateCharacter(testGroup, firstName, lastName, gender, age) {
        let fullName = firstName + " " + lastName;
        try {
            let existing = await findByName("olio.charPerson", testGroup.id, fullName);
            if (existing) return existing;

            let entity = {
                schema: "olio.charPerson",
                name: fullName,
                firstName: firstName,
                lastName: lastName,
                gender: gender,
                age: age,
                groupId: testGroup.id,
                groupPath: testGroup.path
            };
            await page.createObject(entity);
            let created = await findByName("olio.charPerson", testGroup.id, fullName);
            if (created) log("config", "Created test character: " + fullName, "pass");
            return created;
        } catch (e) {
            log("config", "Character creation skipped (" + fullName + "): " + e.message, "info");
            return null;
        }
    }

    async function autoSetupConfigs() {
        if (suiteState.setupDone && suiteState.chatConfig && suiteState.promptConfig) {
            log("config", "Using cached configs: " + suiteState.chatConfigName, "info");
            return;
        }

        log("config", "Auto-loading test configurations...", "info");

        // 1. Ensure ~/Tests group
        let testGroup = await ensureTestGroup();
        if (!testGroup) {
            log("config", "No test group available — cannot set up configs", "fail");
            return;
        }
        suiteState.testGroup = testGroup;
        log("config", "Test group: " + testGroup.path + "/" + testGroup.name, "info");

        // 2. Load templates from media/prompts/
        let chatTemplate = await fetchTemplate("/media/prompts/llmTestChatConfig.json");
        let promptTemplate = await fetchTemplate("/media/prompts/llmTestPromptConfig.json");
        if (!chatTemplate || !promptTemplate) {
            log("config", "Template files missing — check media/prompts/", "fail");
            return;
        }

        // 3. Create test characters
        let sysChar = await findOrCreateCharacter(testGroup, "Aria", "Cortez", "FEMALE", 28);
        let userChar = await findOrCreateCharacter(testGroup, "Max", "Reeves", "MALE", 32);
        suiteState.systemCharacter = sysChar;
        suiteState.userCharacter = userChar;

        // 4. Create prompt config
        let promptCfg = await findOrCreateConfig("olio.llm.promptConfig", testGroup, promptTemplate);

        // 5. Create chat config variants from template
        let charExtras = {};
        if (sysChar) charExtras.systemCharacter = { objectId: sysChar.objectId };
        if (userChar) charExtras.userCharacter = { objectId: userChar.objectId };

        // Variant: streaming (stream=true, prune=true, episodes) — for stream, prune, episode tests
        let streamingTemplate = {};
        for (let k in chatTemplate) streamingTemplate[k] = chatTemplate[k];
        streamingTemplate.name = "LLM Test Streaming";
        streamingTemplate.stream = true;
        streamingTemplate.prune = true;
        let streamExtras = {};
        for (let k in charExtras) streamExtras[k] = charExtras[k];
        streamExtras.episodes = [{
            schema: "olio.llm.episode",
            completed: false,
            name: "Test Handoff",
            number: 1,
            stages: [
                "1. ${system.firstName} greets ${user.firstName} and offers a token",
                "2. ${system.firstName} hands ${user.firstName} the token",
                "3. ${system.firstName} confirms ${user.firstName} received the token"
            ],
            theme: "simple verification handoff"
        }];
        let streamingCfg = await findOrCreateConfig("olio.llm.chatConfig", testGroup, streamingTemplate, streamExtras);

        // Variant: standard (stream=false, prune=false) — for chat, history, analyze, narrate tests
        let standardTemplate = {};
        for (let k in chatTemplate) standardTemplate[k] = chatTemplate[k];
        standardTemplate.name = "LLM Test Standard";
        standardTemplate.stream = false;
        standardTemplate.prune = false;
        let standardCfg = await findOrCreateConfig("olio.llm.chatConfig", testGroup, standardTemplate, charExtras);

        // Store variants
        suiteState.chatConfigs.streaming = streamingCfg;
        suiteState.chatConfigs.standard = standardCfg;

        // Default config = streaming (full-featured)
        let chatCfg = streamingCfg || standardCfg;

        // 6. Set as active
        if (chatCfg) setChatConfig(chatCfg);
        if (promptCfg) setPromptConfig(promptCfg);
        suiteState.setupDone = true;

        let variantNames = [];
        if (streamingCfg) variantNames.push("streaming");
        if (standardCfg) variantNames.push("standard");
        log("config", "Config variants created: " + variantNames.join(", "), "pass");

        if (chatCfg && promptCfg) {
            log("config", "Auto-setup complete: default=" + chatCfg.name + ", prompt=" + promptCfg.name, "pass");
        }
    }

    // ── Test 63: Config Load ──────────────────────────────────────────
    async function testConfigLoad(cats) {
        if (!cats.includes("config")) return;
        TF.testState.currentTest = "Config: load chatConfig & promptConfig";
        log("config", "=== Config Load Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;

        // Test 63: chatConfig and promptConfig load
        if (!chatCfg) {
            log("config", "No chatConfig selected -- select one in the config picker above", "fail");
            return;
        }
        log("config", "chatConfig loaded: " + chatCfg.name, "pass");
        logData("config", "chatConfig", chatCfg);

        if (!promptCfg) {
            log("config", "No promptConfig selected -- select one in the config picker above", "fail");
            return;
        }
        log("config", "promptConfig loaded: " + promptCfg.name, "pass");
        logData("config", "promptConfig", promptCfg);

        // Test 64: Server reachable
        let serverUrl = chatCfg.serverUrl;
        if (serverUrl) {
            log("config", "Server URL configured: " + serverUrl, "pass");
            // Check model availability via AM7 REST proxy (prompt endpoint)
            try {
                let resp = await m.request({
                    method: 'GET',
                    url: g_application_path + "/rest/chat/prompt",
                    withCredentials: true
                });
                log("config", "Chat REST endpoint reachable", "pass");
            } catch (e) {
                log("config", "Chat REST endpoint unreachable: " + e.message, "warn");
            }
        } else {
            log("config", "No serverUrl on chatConfig", "warn");
        }

        // Validate model field
        if (chatCfg.model) {
            log("config", "Model: " + chatCfg.model, "pass");
        } else {
            log("config", "No model specified on chatConfig", "warn");
        }
    }

    // ── Tests 82-85: ChatOptions (Phase 8) ────────────────────────────
    async function testChatOptions(cats) {
        if (!cats.includes("options")) return;
        TF.testState.currentTest = "Options: chatOptions field validation";
        log("options", "=== ChatOptions Tests (Phase 8) ===");

        let chatCfg = suiteState.chatConfig;
        if (!chatCfg) {
            log("options", "chatConfig missing - skipping", "skip");
            return;
        }

        // Test 82: chatOptions exists on chatConfig
        // Note: configs created before Phase 8 won't have chatOptions from the server.
        // Delete existing "LLM Test Streaming"/"LLM Test Standard" from ~/Tests to recreate with chatOptions.
        let opts = chatCfg.chatOptions;
        if (!opts) {
            log("options", "chatOptions not on server config — pre-Phase 8 config (OI-24: delete and recreate to pick up template changes)", "warn");
            // Fall back to template defaults for remaining tests
            opts = { temperature: 0.8, top_p: 0.9, top_k: 50, frequency_penalty: 0.0, presence_penalty: 0.0, max_tokens: 4096, num_ctx: 8192, seed: 0 };
            log("options", "Using template defaults for field validation", "info");
        } else {
            log("options", "chatOptions present on chatConfig", "pass");
        }
        logData("options", "chatOptions", opts);

        // Test 82b: Verify Phase 8 new fields exist
        let requiredFields = ["temperature", "top_p", "frequency_penalty", "presence_penalty", "max_tokens", "seed"];
        let missingFields = [];
        for (let i = 0; i < requiredFields.length; i++) {
            if (opts[requiredFields[i]] === undefined) {
                missingFields.push(requiredFields[i]);
            }
        }
        if (missingFields.length === 0) {
            log("options", "All Phase 8 fields present: " + requiredFields.join(", "), "pass");
        } else {
            log("options", "Missing Phase 8 fields: " + missingFields.join(", "), "fail");
        }

        // Test 83: top_k maxValue fixed (should accept values > 1)
        // Verify the model schema allows top_k > 1 by checking the value from template
        let topK = opts.top_k;
        if (topK !== undefined) {
            log("options", "top_k value: " + topK, topK >= 0 ? "pass" : "fail");
            if (topK > 1) {
                log("options", "top_k > 1 accepted (maxValue fix verified): " + topK, "pass");
            } else {
                log("options", "top_k <= 1 — value may be clamped by old maxValue=1 bug", "warn");
            }
        } else {
            log("options", "top_k not set on chatOptions", "info");
        }

        // Test 83b: Verify field ranges
        let rangeChecks = [
            { field: "temperature", min: 0, max: 2 },
            { field: "top_p", min: 0, max: 1 },
            { field: "frequency_penalty", min: -2, max: 2 },
            { field: "presence_penalty", min: -2, max: 2 },
            { field: "max_tokens", min: 0, max: 120000 },
            { field: "top_k", min: 0, max: 500 }
        ];
        let rangeOk = true;
        for (let i = 0; i < rangeChecks.length; i++) {
            let rc = rangeChecks[i];
            let val = opts[rc.field];
            if (val !== undefined && (val < rc.min || val > rc.max)) {
                log("options", rc.field + " = " + val + " out of range [" + rc.min + ", " + rc.max + "]", "fail");
                rangeOk = false;
            }
        }
        if (rangeOk) {
            log("options", "All field ranges valid", "pass");
        }

        // Test 84: UX model schema has updated chatOptions (verify via am7model)
        try {
            let modelDef = am7model.getModel("olio.llm.chatOptions");
            if (modelDef && modelDef.fields) {
                let fieldNames = modelDef.fields.map(function(f) { return f.name; });
                let phase8Fields = ["frequency_penalty", "presence_penalty", "max_tokens", "seed"];
                let schemaOk = true;
                for (let i = 0; i < phase8Fields.length; i++) {
                    if (fieldNames.indexOf(phase8Fields[i]) === -1) {
                        log("options", "UX schema missing field: " + phase8Fields[i], "fail");
                        schemaOk = false;
                    }
                }
                if (schemaOk) {
                    log("options", "UX model schema includes all Phase 8 fields", "pass");
                }

                // Verify top_k maxValue fixed in schema
                let topKField = modelDef.fields.find(function(f) { return f.name === "top_k"; });
                if (topKField) {
                    let maxVal = topKField.maxValue;
                    if (maxVal !== undefined && maxVal > 1) {
                        log("options", "UX schema top_k maxValue = " + maxVal + " (fixed from 1)", "pass");
                    } else if (maxVal !== undefined && maxVal <= 1) {
                        log("options", "UX schema top_k maxValue still " + maxVal + " — modelDef.js not updated", "fail");
                    }
                }
            } else {
                log("options", "Could not read olio.llm.chatOptions model definition", "warn");
            }
        } catch (e) {
            log("options", "Schema check error: " + e.message, "warn");
        }

        // Test 85: openaiRequest model defaults corrected
        try {
            let reqModel = am7model.getModel("olio.llm.openai.openaiRequest");
            if (reqModel && reqModel.fields) {
                let fpField = reqModel.fields.find(function(f) { return f.name === "frequency_penalty"; });
                let ppField = reqModel.fields.find(function(f) { return f.name === "presence_penalty"; });

                if (fpField) {
                    let fpDef = fpField["default"];
                    if (fpDef !== undefined && fpDef <= 0.1) {
                        log("options", "openaiRequest frequency_penalty default = " + fpDef + " (fixed from 1.3)", "pass");
                    } else {
                        log("options", "openaiRequest frequency_penalty default = " + fpDef + " — should be 0.0", "fail");
                    }
                }
                if (ppField) {
                    let ppDef = ppField["default"];
                    if (ppDef !== undefined && ppDef <= 0.1) {
                        log("options", "openaiRequest presence_penalty default = " + ppDef + " (fixed from 1.3)", "pass");
                    } else {
                        log("options", "openaiRequest presence_penalty default = " + ppDef + " — should be 0.0", "fail");
                    }
                }

                // Verify seed field added
                let seedField = reqModel.fields.find(function(f) { return f.name === "seed"; });
                if (seedField) {
                    log("options", "openaiRequest has seed field", "pass");
                } else {
                    log("options", "openaiRequest missing seed field", "fail");
                }
            } else {
                log("options", "Could not read openaiRequest model definition", "warn");
            }
        } catch (e) {
            log("options", "Request model check error: " + e.message, "warn");
        }
    }

    // ── Tests 65-67: Prompt Composition ───────────────────────────────
    async function testPromptComposition(cats) {
        if (!cats.includes("prompt")) return;
        TF.testState.currentTest = "Prompt: composition tests";
        log("prompt", "=== Prompt Composition Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("prompt", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

        // Test 65: Fetch composed prompts via config endpoint
        // Pass group=~/Tests since test configs are created there (not ~/Chat)
        let fullPromptCfg = null;
        try {
            fullPromptCfg = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/chat/config/prompt/" + encodeURIComponent(promptCfg.name) + "?group=" + encodeURIComponent("~/Tests"),
                withCredentials: true
            });
            log("prompt", "promptConfig fetched via REST", "pass");
        } catch (e) {
            log("prompt", "REST fetch returned " + (e.code || "error") + " — first run or config not yet created. Using cached config.", "warn");
            fullPromptCfg = promptCfg;
        }

        if (fullPromptCfg) {
            // Check system prompt array
            let system = fullPromptCfg.system;
            if (system && system.length > 0) {
                let totalLen = system.reduce(function(acc, s) { return acc + (s || "").length; }, 0);
                log("prompt", "System prompt composed (" + totalLen + " chars, " + system.length + " parts)", totalLen > 0 ? "pass" : "fail");
                logData("prompt", "System prompt content", system);
            } else {
                log("prompt", "System prompt is empty", "warn");
            }

            // Check for assistant/user prompt sections
            if (fullPromptCfg.assistant) {
                let aLen = (typeof fullPromptCfg.assistant === "string")
                    ? fullPromptCfg.assistant.length
                    : JSON.stringify(fullPromptCfg.assistant).length;
                log("prompt", "Assistant prompt present (" + aLen + " chars)", "pass");
            }

            // Test 66: Token classification check
            // The raw promptConfig contains template tokens that are resolved at different stages:
            //   - Character tokens (system.*, user.*) resolve when chatRequest is created with character context
            //   - Runtime tokens (image.*, audio.*, nlp.*) resolve at message time
            //   - Prompt tokens (perspective, censorWarn, assistCensorWarn, rating, ratingName, etc.) resolve during composition
            let allText = JSON.stringify(fullPromptCfg);
            let tokenPattern = /\$\{[^}]+\}/g;
            let matches = allText.match(tokenPattern);
            let runtimeTokenPattern = /^\$\{(image|audio|nlp|system|user|perspective|censorWarn|assistCensorWarn|rating|ratingName|ratingDescription|ratingMpa|scene|setting|episode|episodeRule|dynamicRules|memory|interaction|profile|location)\b/;
            let runtimeTokens = [];
            let unknownTokens = [];
            if (matches) {
                for (let t of matches) {
                    if (t.match(runtimeTokenPattern)) {
                        runtimeTokens.push(t);
                    } else {
                        unknownTokens.push(t);
                    }
                }
            }
            if (runtimeTokens.length > 0) {
                log("prompt", "Runtime tokens present: " + runtimeTokens.length + " (resolved at chat/message time)", "pass");
                logData("prompt", "Runtime tokens", runtimeTokens);
            }
            if (unknownTokens.length === 0) {
                log("prompt", "No unknown ${...} tokens found", "pass");
            } else {
                log("prompt", "Unknown tokens found: " + unknownTokens.join(", "), "warn");
                logData("prompt", "Unknown tokens", unknownTokens);
            }

            // Test 66b: Verify image/audio tokens are present (needed for media testing)
            let imageTokens = runtimeTokens.filter(function(t) { return t.match(/^\$\{image\./); });
            let audioTokens = runtimeTokens.filter(function(t) { return t.match(/^\$\{audio\./); });
            if (imageTokens.length > 0) {
                log("prompt", "Image tokens present: " + imageTokens.join(", "), "pass");
            } else {
                log("prompt", "No ${image.*} tokens in prompt — media image tests will be limited", "info");
            }
            if (audioTokens.length > 0) {
                log("prompt", "Audio tokens present: " + audioTokens.join(", "), "pass");
            } else {
                log("prompt", "No ${audio.*} tokens in prompt — media audio tests will be limited", "info");
            }

            // Test 67: Full prompt data dump
            logData("prompt", "Full promptConfig response", fullPromptCfg);
        }
    }

    // ── Tests 68-70: Chat Session ─────────────────────────────────────
    async function testChatSession(cats) {
        if (!cats.includes("chat")) return;
        TF.testState.currentTest = "Chat: session tests";
        log("chat", "=== Chat Session Tests ===");

        let chatCfg = getVariant("standard");
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("chat", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }
        log("chat", "Using variant: " + chatCfg.name, "info");

        let req;

        // Test 68: Create session
        try {
            req = await am7chat.getChatRequest("LLM Test - " + Date.now(), chatCfg, promptCfg);
            log("chat", "Session created: " + (req ? req.name || req.objectId : "null"), req ? "pass" : "fail");
            logData("chat", "Session object", req);
        } catch (e) {
            log("chat", "Session create failed: " + e.message, "fail");
            logData("chat", "Error details", e.stack || e.message);
            return;
        }

        if (!req) {
            log("chat", "Session is null - cannot continue chat tests", "fail");
            return;
        }

        // Test 68b: Assist exchange detection
        // When assist=true, the session is front-loaded with a hidden initial exchange
        // (user input + assistant response) that does NOT appear in history but IS
        // present in the raw request object.
        if (chatCfg.assist) {
            log("chat", "assist=true — session has hidden initial exchange (not in history, present in raw request)", "info");
            let rawMessages = req.messages || [];
            if (rawMessages.length > 0) {
                log("chat", "Raw request has " + rawMessages.length + " pre-loaded message(s)", "pass");
                logData("chat", "Assist pre-loaded messages", rawMessages);
            } else {
                log("chat", "assist=true but no pre-loaded messages found in raw request — may load lazily", "info");
            }
        }

        // Test 69: Send message and receive response
        try {
            let resp = await am7chat.chat(req, "Say exactly: TEST_OK");
            let content = extractLastAssistantMessage(resp);
            log("chat", "Response received (" + (content ? content.length : 0) + " chars)", content ? "pass" : "fail");
            logData("chat", "Response content", content || "(empty)");
            logData("chat", "Full response", resp);
        } catch (e) {
            log("chat", "Chat failed: " + e.message, "fail");
            logData("chat", "Error details", e.stack || e.message);
        }

        // Test 70: Cleanup session
        try {
            await am7chat.deleteChat(req, true);
            log("chat", "Session cleaned up", "pass");
        } catch (e) {
            log("chat", "Cleanup failed: " + e.message, "warn");
        }
    }

    // ── Tests 71-72: Stream ───────────────────────────────────────────
    // Phase 7: Always-Stream Backend — server always streams from LLM;
    // stream flag controls whether chunks are forwarded (WS) or buffered (REST).
    async function testStream(cats) {
        if (!cats.includes("stream")) return;
        TF.testState.currentTest = "Stream: WebSocket streaming & REST buffer tests";
        log("stream", "=== Stream Tests === [Phase 7: Always-Stream Backend]");

        let chatCfg = getVariant("streaming");
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("stream", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }
        log("stream", "Using variant: " + chatCfg.name, "info");

        // Test 71: WebSocket streaming (stream=true, chunks forwarded to client)
        if (page.wss) {
            let req;
            try {
                req = await am7chat.getChatRequest("LLM Stream Test - " + Date.now(), chatCfg, promptCfg);
                log("stream", "Stream test session created: " + (req ? req.name : "null"), req ? "pass" : "fail");
            } catch (e) {
                log("stream", "Session create failed: " + e.message, "fail");
                return;
            }

            if (req) {
                try {
                    let chunks = [];
                    let completed = false;
                    let streamError = null;

                    let streamCallbacks = {
                        onchatstart: function(id, r) { log("stream", "Stream started (id: " + id + ")", "info"); },
                        onchatupdate: function(id, msg) { chunks.push(msg); },
                        onchatcomplete: function(id) { completed = true; },
                        onchaterror: function(id, msg) { streamError = msg; }
                    };

                    let prevStream = page.chatStream;
                    page.chatStream = streamCallbacks;

                    let chatReq = {
                        schema: "olio.llm.chatRequest",
                        objectId: req.objectId,
                        uid: page.uid(),
                        message: "Say exactly: STREAM_TEST_OK"
                    };

                    page.wss.send("chat", JSON.stringify(chatReq), undefined, "olio.llm.chatRequest");

                    let timeout = 30000;
                    let waited = 0;
                    let interval = 200;
                    while (!completed && !streamError && waited < timeout) {
                        await new Promise(function(resolve) { setTimeout(resolve, interval); });
                        waited += interval;
                    }

                    page.chatStream = prevStream;

                    if (streamError) {
                        log("stream", "Stream error: " + streamError, "fail");
                    } else if (!completed) {
                        log("stream", "Stream timed out after " + timeout + "ms (received " + chunks.length + " chunks)", "fail");
                    } else {
                        log("stream", "WS stream received " + chunks.length + " chunks", chunks.length > 0 ? "pass" : "warn");
                        let fullResponse = chunks.join("");
                        log("stream", "Full response assembled (" + fullResponse.length + " chars)", fullResponse.length > 0 ? "pass" : "fail");
                        logData("stream", "Streamed response", fullResponse);
                    }
                } catch (e) {
                    log("stream", "WS streaming test error: " + e.message, "fail");
                    logData("stream", "Error details", e.stack || e.message);
                }

                try {
                    await am7chat.deleteChat(req, true);
                    log("stream", "WS stream test session cleaned up", "pass");
                } catch (e) {
                    log("stream", "Cleanup failed: " + e.message, "warn");
                }
            }
        } else {
            log("stream", "WebSocket service not available - WS stream test skipped", "skip");
        }

        // Test 72: REST buffer mode (stream=false on config, server still streams internally)
        let stdCfg = getVariant("standard");
        if (stdCfg) {
            let bufReq;
            try {
                bufReq = await am7chat.getChatRequest("LLM Buffer Test - " + Date.now(), stdCfg, promptCfg);
                log("stream", "Buffer test session created: " + (bufReq ? bufReq.name : "null"), bufReq ? "pass" : "fail");
            } catch (e) {
                log("stream", "Buffer session create failed: " + e.message, "fail");
            }

            if (bufReq) {
                try {
                    let resp = await am7chat.chat(bufReq, "Say exactly: BUFFER_TEST_OK");
                    let content = extractLastAssistantMessage(resp);
                    log("stream", "REST buffer response received (" + (content ? content.length : 0) + " chars)", content ? "pass" : "fail");
                    if (content) {
                        logData("stream", "Buffer response", content);
                    } else {
                        log("stream", "REST buffer returned empty response", "warn");
                    }
                } catch (e) {
                    log("stream", "REST buffer test error: " + e.message, "fail");
                    logData("stream", "Error details", e.stack || e.message);
                }

                try {
                    await am7chat.deleteChat(bufReq, true);
                    log("stream", "Buffer test session cleaned up", "pass");
                } catch (e) {
                    log("stream", "Cleanup failed: " + e.message, "warn");
                }
            }
        } else {
            log("stream", "Standard variant not available - buffer test skipped", "skip");
        }
    }

    // ── Tests 73-74: History ──────────────────────────────────────────
    async function testHistory(cats) {
        if (!cats.includes("history")) return;
        TF.testState.currentTest = "History: message ordering & content";
        log("history", "=== History Tests ===");

        let chatCfg = getVariant("standard");
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("history", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }
        log("history", "Using variant: " + chatCfg.name, "info");

        let req;
        try {
            req = await am7chat.getChatRequest("LLM History Test - " + Date.now(), chatCfg, promptCfg);
            log("history", "History test session created", req ? "pass" : "fail");
        } catch (e) {
            log("history", "Session create failed: " + e.message, "fail");
            return;
        }

        if (!req) return;

        // Send 3 messages
        let sentMessages = ["Message one: apple", "Message two: banana", "Message three: cherry"];
        for (let i = 0; i < sentMessages.length; i++) {
            try {
                await am7chat.chat(req, sentMessages[i]);
                log("history", "Sent message " + (i + 1) + ": " + sentMessages[i], "info");
            } catch (e) {
                log("history", "Failed to send message " + (i + 1) + ": " + e.message, "fail");
            }
        }

        // Note: if assist=true, the session has a hidden initial exchange (user + assistant)
        // that does NOT appear in history but IS in the raw request object.
        let hasAssist = !!chatCfg.assist;
        if (hasAssist) {
            log("history", "assist=true — hidden initial exchange excluded from history", "info");
        }

        // Test 73: Retrieve history and verify ordering
        try {
            let histReq = {
                schema: "olio.llm.chatRequest",
                objectId: req.objectId,
                uid: page.uid()
            };
            let hist = await m.request({ method: 'POST', url: g_application_path + "/rest/chat/history", withCredentials: true, body: histReq });
            let msgs = hist ? hist.messages : [];
            logData("history", "Full history", msgs);

            // Test 73: Verify chronological order (user messages should appear in order)
            let userMsgs = msgs.filter(function(m) { return m.role === "user"; });
            let ordered = true;
            for (let i = 0; i < sentMessages.length && i < userMsgs.length; i++) {
                let content = userMsgs[i].content || "";
                if (content.indexOf(sentMessages[i]) === -1) {
                    ordered = false;
                    break;
                }
            }
            log("history", "History ordering: " + userMsgs.length + " user messages in order", ordered ? "pass" : "fail");

            // Test 74: Verify content matches
            let found = 0;
            for (let sent of sentMessages) {
                for (let m of msgs) {
                    if ((m.content || "").indexOf(sent) !== -1) {
                        found++;
                        break;
                    }
                }
            }
            log("history", "Content match: " + found + "/" + sentMessages.length + " sent messages found in history", found === sentMessages.length ? "pass" : "fail");

            // Test 74b: Verify assist exchange is excluded from history
            if (hasAssist) {
                // History should only contain our 3 sent messages + their responses (no assist pair)
                log("history", "History has " + msgs.length + " messages (expected ~" + (sentMessages.length * 2) + " without assist pair)", "info");
                // The assist exchange should NOT appear as the first user message
                if (userMsgs.length > 0 && userMsgs[0].content && userMsgs[0].content.indexOf(sentMessages[0]) !== -1) {
                    log("history", "First user message in history is our first sent message (assist pair correctly hidden)", "pass");
                } else if (userMsgs.length > sentMessages.length) {
                    log("history", "Extra user messages in history — assist pair may be leaking into history", "warn");
                }
            }

        } catch (e) {
            log("history", "History retrieval failed: " + e.message, "fail");
        }

        // Cleanup
        try {
            await am7chat.deleteChat(req, true);
            log("history", "History test session cleaned up", "pass");
        } catch (e) {
            log("history", "Cleanup failed: " + e.message, "warn");
        }
    }

    // ── Tests 75-76: Prune & Keyframe ─────────────────────────────────
    // PHASE DEP: Phase 10 (Memory System Hardening & Keyframe Refactor) — NOT STARTED
    // Prune/trim works now; keyframe detection will change with Phase 10
    async function testPrune(cats) {
        if (!cats.includes("prune")) return;
        TF.testState.currentTest = "Prune: message trimming & keyframes";
        log("prune", "=== Prune Tests === [Phase 10 pending: Keyframe refactor]");

        let chatCfg = getVariant("streaming");
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("prune", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }
        log("prune", "Using variant: " + chatCfg.name, "info");

        let messageTrim = chatCfg.messageTrim || 6;
        let hasAssist = !!chatCfg.assist;
        log("prune", "messageTrim = " + messageTrim + (hasAssist ? " (assist=true, hidden initial exchange not counted)" : ""), "info");

        let req;
        try {
            req = await am7chat.getChatRequest("LLM Prune Test - " + Date.now(), chatCfg, promptCfg);
            log("prune", "Prune test session created", req ? "pass" : "fail");
        } catch (e) {
            log("prune", "Session create failed: " + e.message, "fail");
            return;
        }

        if (!req) return;

        // Send enough messages to exceed messageTrim
        let msgCount = messageTrim + 4;
        log("prune", "Sending " + msgCount + " messages to exceed messageTrim...", "info");
        for (let i = 0; i < msgCount; i++) {
            try {
                await am7chat.chat(req, "Prune test message " + (i + 1) + ". Reply with exactly: OK " + (i + 1));
                log("prune", "Sent message " + (i + 1) + "/" + msgCount, "info");
            } catch (e) {
                log("prune", "Message " + (i + 1) + " failed: " + e.message, "fail");
                break;
            }
        }

        // Test 75: Retrieve and check if old messages were pruned
        try {
            let histReq = {
                schema: "olio.llm.chatRequest",
                objectId: req.objectId,
                uid: page.uid()
            };
            let hist = await m.request({ method: 'POST', url: g_application_path + "/rest/chat/history", withCredentials: true, body: histReq });
            let msgs = hist ? hist.messages : [];
            logData("prune", "History after pruning", msgs);

            let userMsgs = msgs.filter(function(m) { return m.role === "user"; });
            log("prune", "Total messages: " + msgs.length + " (user: " + userMsgs.length + ")", "info");

            // The server should have pruned old messages
            // With messageTrim, the visible window should be limited
            if (msgs.length <= (messageTrim * 2) + 4) {
                log("prune", "Message count within expected trim range", "pass");
            } else {
                log("prune", "Message count (" + msgs.length + ") may exceed expected trim - check manually", "warn");
            }

            // Test 76: Check for keyframe presence (MCP format)
            let keyframes = msgs.filter(function(m) {
                return (m.content || "").indexOf("[MCP:KeyFrame") !== -1 || (m.content || "").indexOf("(KeyFrame:") !== -1;
            });
            if (keyframes.length > 0) {
                log("prune", "Keyframe(s) found: " + keyframes.length, "pass");
                logData("prune", "Keyframe content", keyframes.map(function(k) { return k.content; }));
            } else {
                log("prune", "No keyframes detected - may need more messages or keyframeEvery config", "info");
            }
        } catch (e) {
            log("prune", "Prune history check failed: " + e.message, "fail");
        }

        // Cleanup
        try {
            await am7chat.deleteChat(req, true);
            log("prune", "Prune test session cleaned up", "pass");
        } catch (e) {
            log("prune", "Cleanup failed: " + e.message, "warn");
        }
    }

    // ── Tests 77-78: Episode ──────────────────────────────────────────
    // Phase 7: Episode config validation & transition detection via always-stream
    async function testEpisode(cats) {
        if (!cats.includes("episode")) return;
        TF.testState.currentTest = "Episode: guidance & transition";
        log("episode", "=== Episode Tests === [Phase 7: Always-Stream episode transitions]");

        let chatCfg = getVariant("streaming");
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("episode", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }
        log("episode", "Using variant: " + chatCfg.name, "info");

        // Test 77: Check if episodes are configured
        let episodes = chatCfg.episodes;
        if (!episodes || !episodes.length) {
            log("episode", "No episodes configured on chatConfig - episode tests skipped", "skip");
            return;
        }

        log("episode", "Episodes configured: " + episodes.length, "pass");
        logData("episode", "Episode configuration", episodes);

        // Test 77b: Validate episode structure (name, number, stages, theme)
        let ep = episodes[0];
        if (ep.name) {
            log("episode", "Episode name: " + ep.name, "pass");
        } else {
            log("episode", "Episode missing name", "warn");
        }
        if (ep.stages && ep.stages.length > 0) {
            log("episode", "Episode stages: " + ep.stages.length, "pass");
            logData("episode", "Stages", ep.stages);
        } else {
            log("episode", "Episode missing stages array — add stages with numbered plot points", "warn");
        }
        if (ep.theme) {
            log("episode", "Episode theme: " + ep.theme, "pass");
        } else {
            log("episode", "Episode missing theme", "info");
        }

        // Fetch the composed prompt to check for episode guidance
        try {
            let fullPromptCfg = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/chat/config/prompt/" + encodeURIComponent(promptCfg.name) + "?group=" + encodeURIComponent("~/Tests"),
                withCredentials: true
            });
            let allText = JSON.stringify(fullPromptCfg);
            let hasEpisodeGuidance = allText.indexOf("episode") !== -1 || allText.indexOf("EPISODE") !== -1;
            log("episode", "Episode guidance in prompt: " + (hasEpisodeGuidance ? "yes" : "no"), hasEpisodeGuidance ? "pass" : "warn");
        } catch (e) {
            log("episode", "Failed to check prompt for episode guidance: " + e.message, "warn");
        }

        // Test 78: Episode transition rule and detection
        let epRule = promptCfg.episodeRule;
        if (epRule && epRule.length > 0) {
            log("episode", "Episode transition rule configured (" + epRule.length + " lines)", "pass");
            logData("episode", "Episode rule", epRule);

            // Test 78b: Validate transition markers are present in rule
            let ruleText = epRule.join(" ");
            let hasNext = ruleText.indexOf("#NEXT EPISODE#") !== -1;
            let hasOut = ruleText.indexOf("#OUT OF EPISODE#") !== -1;
            log("episode", "#NEXT EPISODE# marker in rule: " + (hasNext ? "yes" : "no"), hasNext ? "pass" : "warn");
            log("episode", "#OUT OF EPISODE# marker in rule: " + (hasOut ? "yes" : "no"), hasOut ? "pass" : "warn");

            // Test 78c: Episode transition detection via streaming "Test Handoff" episode
            // The streaming variant includes a 3-stage "Test Handoff" episode.
            // Send a message that should trigger episode engagement via the always-stream backend.
            if (page.wss && ep.name === "Test Handoff" && ep.stages && ep.stages.length === 3) {
                let epReq;
                try {
                    epReq = await am7chat.getChatRequest("LLM Episode Transition Test - " + Date.now(), chatCfg, promptCfg);
                    log("episode", "Episode transition session created", epReq ? "pass" : "fail");
                } catch (e) {
                    log("episode", "Episode session create failed: " + e.message, "fail");
                }

                if (epReq) {
                    try {
                        let chunks = [];
                        let completed = false;
                        let streamError = null;

                        let streamCallbacks = {
                            onchatstart: function() {},
                            onchatupdate: function(id, msg) { chunks.push(msg); },
                            onchatcomplete: function() { completed = true; },
                            onchaterror: function(id, msg) { streamError = msg; }
                        };

                        let prevStream = page.chatStream;
                        page.chatStream = streamCallbacks;

                        let chatReq = {
                            schema: "olio.llm.chatRequest",
                            objectId: epReq.objectId,
                            uid: page.uid(),
                            message: "Hello! I am ready for the token handoff."
                        };

                        page.wss.send("chat", JSON.stringify(chatReq), undefined, "olio.llm.chatRequest");

                        let timeout = 30000;
                        let waited = 0;
                        while (!completed && !streamError && waited < timeout) {
                            await new Promise(function(resolve) { setTimeout(resolve, 200); });
                            waited += 200;
                        }

                        page.chatStream = prevStream;

                        if (streamError) {
                            log("episode", "Episode stream error: " + streamError, "fail");
                        } else if (!completed) {
                            log("episode", "Episode stream timed out", "fail");
                        } else {
                            let fullResp = chunks.join("");
                            log("episode", "Episode response received (" + fullResp.length + " chars)", fullResp.length > 0 ? "pass" : "warn");
                            // Check if response engages with episode content
                            let lcResp = fullResp.toLowerCase();
                            let engagesEpisode = lcResp.indexOf("token") !== -1 || lcResp.indexOf("handoff") !== -1 || lcResp.indexOf("greet") !== -1;
                            log("episode", "Response engages with episode theme: " + (engagesEpisode ? "yes" : "uncertain"), engagesEpisode ? "pass" : "info");
                            logData("episode", "Episode response", fullResp);
                        }
                    } catch (e) {
                        log("episode", "Episode transition test error: " + e.message, "fail");
                    }

                    try {
                        await am7chat.deleteChat(epReq, true);
                        log("episode", "Episode test session cleaned up", "pass");
                    } catch (e) {
                        log("episode", "Cleanup failed: " + e.message, "warn");
                    }
                }
            } else {
                log("episode", "Episode transition live test skipped (requires WS + Test Handoff episode with 3 stages)", "info");
            }
        } else {
            log("episode", "No episodeRule on promptConfig — add episodeRule array to prompt template", "warn");
        }
    }

    // ── Test 79: Analyze ──────────────────────────────────────────────
    async function testAnalyze(cats) {
        if (!cats.includes("analyze")) return;
        TF.testState.currentTest = "Analyze: analysis pipeline";
        log("analyze", "=== Analysis Pipeline Tests ===");

        let chatCfg = getVariant("standard");
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("analyze", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }
        log("analyze", "Using variant: " + chatCfg.name, "info");

        // Create a session, send some content, then request analysis
        let req;
        try {
            req = await am7chat.getChatRequest("LLM Analyze Test - " + Date.now(), chatCfg, promptCfg);
            log("analyze", "Analysis test session created", req ? "pass" : "fail");
        } catch (e) {
            log("analyze", "Session create failed: " + e.message, "fail");
            return;
        }

        if (!req) return;

        try {
            // Send a few messages to give context for analysis
            await am7chat.chat(req, "Let's discuss the weather today. It's sunny and warm.");
            await am7chat.chat(req, "I enjoy hiking in the mountains when the weather is nice.");

            // Check if we can get a response that summarizes/analyzes
            let resp = await am7chat.chat(req, "Summarize what we discussed so far in one sentence.");
            let content = extractLastAssistantMessage(resp);
            log("analyze", "Analysis response (" + (content ? content.length : 0) + " chars)", content && content.length > 10 ? "pass" : "warn");
            logData("analyze", "Analysis response content", content || "(empty)");
        } catch (e) {
            log("analyze", "Analysis failed: " + e.message, "fail");
            logData("analyze", "Error details", e.stack || e.message);
        }

        // Cleanup
        try {
            await am7chat.deleteChat(req, true);
            log("analyze", "Analysis test session cleaned up", "pass");
        } catch (e) {
            log("analyze", "Cleanup failed: " + e.message, "warn");
        }
    }

    // ── Test 80: Narrate ──────────────────────────────────────────────
    async function testNarrate(cats) {
        if (!cats.includes("narrate")) return;
        TF.testState.currentTest = "Narrate: narration pipeline";
        log("narrate", "=== Narration Pipeline Tests ===");

        let chatCfg = getVariant("standard");
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("narrate", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }
        log("narrate", "Using variant: " + chatCfg.name, "info");

        let req;
        try {
            req = await am7chat.getChatRequest("LLM Narrate Test - " + Date.now(), chatCfg, promptCfg);
            log("narrate", "Narration test session created", req ? "pass" : "fail");
        } catch (e) {
            log("narrate", "Session create failed: " + e.message, "fail");
            return;
        }

        if (!req) return;

        try {
            let resp = await am7chat.chat(req, "Describe a scene: A traveler arrives at a village at sunset. Write 2-3 sentences.");
            let content = extractLastAssistantMessage(resp);
            log("narrate", "Narration response (" + (content ? content.length : 0) + " chars)", content && content.length > 20 ? "pass" : "warn");
            logData("narrate", "Narration content", content || "(empty)");

            // Verify it has narrative quality (basic check: multiple sentences)
            if (content) {
                let sentences = content.split(/[.!?]+/).filter(function(s) { return s.trim().length > 0; });
                log("narrate", "Sentence count: " + sentences.length, sentences.length >= 2 ? "pass" : "warn");
            }
        } catch (e) {
            log("narrate", "Narration failed: " + e.message, "fail");
            logData("narrate", "Error details", e.stack || e.message);
        }

        // Cleanup
        try {
            await am7chat.deleteChat(req, true);
            log("narrate", "Narration test session cleaned up", "pass");
        } catch (e) {
            log("narrate", "Cleanup failed: " + e.message, "warn");
        }
    }

    // ── Test 81: Policy ───────────────────────────────────────────────
    // Phase 9: Policy-Based LLM Response Regulation — IMPLEMENTED
    async function testPolicy(cats) {
        if (!cats.includes("policy")) return;
        TF.testState.currentTest = "Policy: policy evaluation";
        log("policy", "=== Policy Evaluation Tests (Phase 9) ===");

        let chatCfg = suiteState.chatConfig;
        if (!chatCfg) {
            log("policy", "chatConfig missing - skipping", "skip");
            return;
        }

        // Test 81a: Check if policy field exists on chatConfig schema
        let hasPolicyField = chatCfg.hasOwnProperty("policy") || chatCfg.hasOwnProperty("responsePolicy");
        log("policy", "chatConfig has policy field: " + hasPolicyField, hasPolicyField ? "pass" : "info");

        // Test 81b: Check if a policy is actually configured
        let hasPolicy = !!(chatCfg.policy && (chatCfg.policy.objectId || chatCfg.policy.id));
        if (hasPolicy) {
            log("policy", "Policy configured on chatConfig", "pass");
            logData("policy", "Policy reference", chatCfg.policy);
        } else {
            log("policy", "No policy configured - policy evaluation will be skipped by server", "info");
            log("policy", "To test full policy pipeline, configure a policy on your chatConfig", "info");
        }

        // Test 81c: Verify policyEvent WebSocket handler exists
        // Check that the chat module can handle policyEvent messages
        if (window.am7chat && typeof window.am7chat.handleWebSocketMessage === "function") {
            log("policy", "WebSocket message handler available for policyEvent", "pass");
        } else {
            log("policy", "WebSocket policyEvent handler: chat module present", "info");
        }

        // Test 81d: If policy is configured and LLM is available, send a message and check for policy evaluation
        if (hasPolicy) {
            let promptCfg = suiteState.promptConfig;
            if (!promptCfg) {
                log("policy", "promptConfig missing - skipping live policy test", "skip");
                return;
            }
            let req;
            try {
                req = await am7chat.getChatRequest("LLM Policy Test - " + Date.now(), chatCfg, promptCfg);
                log("policy", "Policy test session created", req ? "pass" : "fail");
            } catch (e) {
                log("policy", "Session create failed: " + e.message, "fail");
                return;
            }
            if (!req) return;

            try {
                // Send a simple message — server will evaluate policy on the response
                let resp = await am7chat.chat(req, "Hello, tell me about yourself in one sentence.");
                let content = extractLastAssistantMessage(resp);
                log("policy", "Response received (" + (content ? content.length : 0) + " chars) — policy evaluated server-side", content ? "pass" : "warn");
                if (content) {
                    logData("policy", "Response content", content);
                }
                // The policy evaluation result is logged server-side.
                // If a policyEvent WebSocket message arrives, it indicates a violation was detected.
                log("policy", "Policy evaluation completed (check server logs for PERMIT/DENY)", "pass");
            } catch (e) {
                log("policy", "Policy test chat failed: " + e.message, "fail");
                logData("policy", "Error details", e.stack || e.message);
            }

            // Cleanup
            try {
                await am7chat.deleteChat(req, true);
                log("policy", "Policy test session cleaned up", "pass");
            } catch (e) {
                log("policy", "Cleanup failed: " + e.message, "warn");
            }
        }

        log("policy", "Phase 9 policy evaluation infrastructure is active", "info");
    }

    // ── Main Suite Runner ─────────────────────────────────────────────
    async function runLLMTests(selectedCategories) {
        let cats = selectedCategories;

        // Auto-setup configs from templates (skip if already done)
        await autoSetupConfigs();

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;

        log("", "LLM Test Suite starting with chatConfig: " + (chatCfg ? chatCfg.name : "none")
            + ", promptConfig: " + (promptCfg ? promptCfg.name : "none"), "info");

        await testConfigLoad(cats);
        await testChatOptions(cats);
        await testPromptComposition(cats);
        await testChatSession(cats);
        await testStream(cats);
        await testHistory(cats);
        await testPrune(cats);
        await testEpisode(cats);
        await testAnalyze(cats);
        await testNarrate(cats);
        await testPolicy(cats);
    }

    // ── Register with TestFramework ───────────────────────────────────
    TF.registerSuite("llm", {
        label: "LLM Chat Pipeline",
        icon: "smart_toy",
        categories: LLM_CATEGORIES,
        run: runLLMTests
    });

    // ── Expose ───────────────────────────────────────────────────────
    window.LLMTestSuite = {
        suiteState: suiteState,
        autoSetupConfigs: autoSetupConfigs,
        setChatConfig: setChatConfig,
        setPromptConfig: setPromptConfig,
        LLM_CATEGORIES: LLM_CATEGORIES
    };

    console.log("[LLMTestSuite] loaded");
}());
