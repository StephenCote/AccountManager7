/**
 * LLM Test Suite
 * Phase 6: Browser-side tests for the full LLM chat pipeline.
 *
 * Registers with TestFramework as the "llm" suite.
 * Tests: config, prompt, chat, stream, history, prune, episode, analyze, narrate, policy
 *
 * Prerequisites:
 *   - A named olio.llm.chatConfig must exist in ~/Chat
 *   - A named olio.llm.promptConfig must exist
 *   - The test view provides config pickers to select which configs to use
 *
 * Depends on: window.TestFramework, window.am7chat, window.am7client
 */
(function() {
    "use strict";

    let TF = window.TestFramework;

    // ── Test Categories ───────────────────────────────────────────────
    const LLM_CATEGORIES = {
        config:  { label: "Config",   icon: "settings" },
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
        chatConfig: null,
        promptConfig: null,
        chatConfigName: null,
        promptConfigName: null,
        availableChatConfigs: [],
        availablePromptConfigs: []
    };

    // ── Helpers ───────────────────────────────────────────────────────
    function log(cat, msg, status) { TF.testLog(cat, msg, status); }
    function logData(cat, label, data) { TF.testLogData(cat, label, data); }

    function extractLastAssistantMessage(resp) {
        if (!resp || !resp.messages) return null;
        for (let i = resp.messages.length - 1; i >= 0; i--) {
            if (resp.messages[i].role === "assistant") {
                return resp.messages[i].displayContent || resp.messages[i].content || "";
            }
        }
        return null;
    }

    // ── Load available configs ────────────────────────────────────────
    async function loadAvailableConfigs() {
        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Chat");
            if (!dir) {
                log("config", "~/Chat directory not found", "fail");
                return;
            }
            suiteState.availableChatConfigs = await am7client.list("olio.llm.chatConfig", dir.objectId, null, 0, 0) || [];
            suiteState.availablePromptConfigs = await am7client.list("olio.llm.promptConfig", dir.objectId, null, 0, 0) || [];
        } catch (e) {
            log("config", "Failed to load configs: " + e.message, "fail");
        }
    }

    function setChatConfig(cfg) {
        suiteState.chatConfig = cfg;
        suiteState.chatConfigName = cfg ? cfg.name : null;
    }

    function setPromptConfig(cfg) {
        suiteState.promptConfig = cfg;
        suiteState.promptConfigName = cfg ? cfg.name : null;
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
        try {
            let fullPromptCfg = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/chat/config/prompt/" + encodeURIComponent(promptCfg.name),
                withCredentials: true
            });
            log("prompt", "promptConfig fetched via REST", "pass");

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

            // Test 66: Orphan token check
            let allText = JSON.stringify(fullPromptCfg);
            let orphanPattern = /\$\{[^}]+\}/g;
            let matches = allText.match(orphanPattern);
            // Filter out known runtime tokens (image.*, audio.*)
            let realOrphans = [];
            if (matches) {
                for (let t of matches) {
                    if (!t.match(/^\$\{(image|audio)\./)) {
                        realOrphans.push(t);
                    }
                }
            }
            if (realOrphans.length === 0) {
                log("prompt", "No orphan ${...} tokens found", "pass");
            } else {
                log("prompt", "Orphan tokens found: " + realOrphans.join(", "), "fail");
                logData("prompt", "Orphan tokens", realOrphans);
            }

            // Test 67: Full prompt data dump
            logData("prompt", "Full promptConfig response", fullPromptCfg);

        } catch (e) {
            log("prompt", "Failed to fetch promptConfig: " + e.message, "fail");
            logData("prompt", "Error details", e.stack || e.message);
        }
    }

    // ── Tests 68-70: Chat Session ─────────────────────────────────────
    async function testChatSession(cats) {
        if (!cats.includes("chat")) return;
        TF.testState.currentTest = "Chat: session tests";
        log("chat", "=== Chat Session Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("chat", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

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
    async function testStream(cats) {
        if (!cats.includes("stream")) return;
        TF.testState.currentTest = "Stream: WebSocket streaming tests";
        log("stream", "=== Stream Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("stream", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

        // Check if streaming is available
        if (!chatCfg.stream) {
            log("stream", "chatConfig.stream is not enabled - streaming tests will attempt with REST fallback", "warn");
        }

        if (!page.wss) {
            log("stream", "WebSocket service not available", "skip");
            return;
        }

        let req;
        try {
            req = await am7chat.getChatRequest("LLM Stream Test - " + Date.now(), chatCfg, promptCfg);
            log("stream", "Stream test session created: " + (req ? req.name : "null"), req ? "pass" : "fail");
        } catch (e) {
            log("stream", "Session create failed: " + e.message, "fail");
            return;
        }

        if (!req) return;

        // Test 71-72: Streaming via WebSocket
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

            // Wait for completion or timeout
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
                log("stream", "Stream connected and received " + chunks.length + " chunks", chunks.length > 0 ? "pass" : "warn");
                let fullResponse = chunks.join("");
                log("stream", "Full response assembled (" + fullResponse.length + " chars)", fullResponse.length > 0 ? "pass" : "fail");
                logData("stream", "Streamed response", fullResponse);
            }
        } catch (e) {
            log("stream", "Streaming test error: " + e.message, "fail");
            logData("stream", "Error details", e.stack || e.message);
        }

        // Cleanup
        try {
            await am7chat.deleteChat(req, true);
            log("stream", "Stream test session cleaned up", "pass");
        } catch (e) {
            log("stream", "Cleanup failed: " + e.message, "warn");
        }
    }

    // ── Tests 73-74: History ──────────────────────────────────────────
    async function testHistory(cats) {
        if (!cats.includes("history")) return;
        TF.testState.currentTest = "History: message ordering & content";
        log("history", "=== History Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("history", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

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
    async function testPrune(cats) {
        if (!cats.includes("prune")) return;
        TF.testState.currentTest = "Prune: message trimming & keyframes";
        log("prune", "=== Prune Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("prune", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

        let messageTrim = chatCfg.messageTrim || 6;
        log("prune", "messageTrim = " + messageTrim, "info");

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
    async function testEpisode(cats) {
        if (!cats.includes("episode")) return;
        TF.testState.currentTest = "Episode: guidance & transition";
        log("episode", "=== Episode Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("episode", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

        // Test 77: Check if episodes are configured
        let episodes = chatCfg.episodes;
        if (!episodes || !episodes.length) {
            log("episode", "No episodes configured on chatConfig - episode tests skipped", "skip");
            return;
        }

        log("episode", "Episodes configured: " + episodes.length, "pass");
        logData("episode", "Episode configuration", episodes);

        // Fetch the composed prompt to check for episode guidance
        try {
            let fullPromptCfg = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/chat/config/prompt/" + encodeURIComponent(promptCfg.name),
                withCredentials: true
            });
            let allText = JSON.stringify(fullPromptCfg);
            let hasEpisodeGuidance = allText.indexOf("episode") !== -1 || allText.indexOf("EPISODE") !== -1;
            log("episode", "Episode guidance in prompt: " + (hasEpisodeGuidance ? "yes" : "no"), hasEpisodeGuidance ? "pass" : "warn");
        } catch (e) {
            log("episode", "Failed to check prompt for episode guidance: " + e.message, "warn");
        }

        // Test 78: Episode transition detection
        log("episode", "#NEXT EPISODE# detection is server-side - verifying config only", "info");
        let hasEpisodeRule = !!(chatCfg.episodeRule || chatCfg.episodeTransition);
        log("episode", "Episode transition rule configured: " + (hasEpisodeRule ? "yes" : "not found"), hasEpisodeRule ? "pass" : "info");
    }

    // ── Test 79: Analyze ──────────────────────────────────────────────
    async function testAnalyze(cats) {
        if (!cats.includes("analyze")) return;
        TF.testState.currentTest = "Analyze: analysis pipeline";
        log("analyze", "=== Analysis Pipeline Tests ===");

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("analyze", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

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

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;
        if (!chatCfg || !promptCfg) {
            log("narrate", "chatConfig or promptConfig missing - skipping", "skip");
            return;
        }

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
    async function testPolicy(cats) {
        if (!cats.includes("policy")) return;
        TF.testState.currentTest = "Policy: policy evaluation";
        log("policy", "=== Policy Evaluation Tests ===");

        let chatCfg = suiteState.chatConfig;
        if (!chatCfg) {
            log("policy", "chatConfig missing - skipping", "skip");
            return;
        }

        // Check if policy is configured
        let hasPolicy = !!(chatCfg.responsePolicy || chatCfg.policy);
        if (!hasPolicy) {
            log("policy", "No policy configured on chatConfig - policy tests skipped", "skip");
            log("policy", "To test policies, configure a responsePolicy on your chatConfig", "info");
            return;
        }

        log("policy", "Policy configured", "pass");
        logData("policy", "Policy configuration", chatCfg.responsePolicy || chatCfg.policy);

        // Since Phase 9 (policy implementation) hasn't been implemented yet,
        // we can only verify the configuration exists
        log("policy", "Policy evaluation tests will be fully functional after Phase 9 implementation", "info");
    }

    // ── Main Suite Runner ─────────────────────────────────────────────
    async function runLLMTests(selectedCategories) {
        let cats = selectedCategories;

        let chatCfg = suiteState.chatConfig;
        let promptCfg = suiteState.promptConfig;

        if (!chatCfg && suiteState.availableChatConfigs.length > 0) {
            chatCfg = suiteState.availableChatConfigs[0];
            setChatConfig(chatCfg);
        }
        if (!promptCfg && suiteState.availablePromptConfigs.length > 0) {
            promptCfg = suiteState.availablePromptConfigs[0];
            setPromptConfig(promptCfg);
        }

        log("", "LLM Test Suite starting with chatConfig: " + (chatCfg ? chatCfg.name : "none")
            + ", promptConfig: " + (promptCfg ? promptCfg.name : "none"), "info");

        await testConfigLoad(cats);
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

    // ── Expose for test view config pickers ───────────────────────────
    window.LLMTestSuite = {
        suiteState: suiteState,
        loadAvailableConfigs: loadAvailableConfigs,
        setChatConfig: setChatConfig,
        setPromptConfig: setPromptConfig,
        LLM_CATEGORIES: LLM_CATEGORIES
    };

    console.log("[LLMTestSuite] loaded");
}());
