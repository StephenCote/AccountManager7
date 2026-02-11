/**
 * CardGame AI -- LLM Base Class
 * Shared LLM infrastructure for Director, Narrator, and Chat.
 * Extracted from cardGame-v2.js (lines ~6449-6617).
 *
 * Exposes: window.CardGame.AI.CardGameLLM
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.AI = window.CardGame.AI || {};

    // ── CardGameLLM Base Class ────────────────────────────────────────
    // Shared LLM infrastructure for Director, Narrator, and Chat
    class CardGameLLM {
        constructor() {
            this.chatRequest = null;
            this.chatConfig = null;
            this.promptConfig = null;
            this.initialized = false;
            this.lastError = null;
        }

        // Find the ~/Chat directory (used for template lookup)
        static async findChatDir() {
            const chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
            if (!chatDir) {
                console.warn("[CardGameLLM] ~/Chat not found");
            }
            return chatDir;
        }

        // Find or create the ~/CardGame/Chats directory for CardGame chatConfig storage
        static async findCardGameChatDir() {
            const chatDir = await page.makePath("auth.group", "data", "~/CardGame/Chats");
            if (!chatDir) {
                console.warn("[CardGameLLM] ~/CardGame/Chats could not be created");
            }
            return chatDir;
        }

        // Get the "Open Chat" template config
        static async getOpenChatTemplate(chatDir) {
            const chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 50);
            const templateCfg = chatConfigs.find(c => c.name === "Open Chat");
            if (!templateCfg) {
                console.warn("[CardGameLLM] 'Open Chat' config not found");
                return null;
            }
            return am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);
        }

        // Ensure a prompt config exists (create or find) using am7chat.makePrompt
        static async ensurePromptConfig(chatDir, name, systemPrompt) {
            try {
                // Delegate to am7chat.makePrompt which correctly handles
                // the 'system' array field and create-or-find logic
                let cfg = await am7chat.makePrompt(name, [systemPrompt]);
                return cfg;
            } catch (err) {
                console.error("[CardGameLLM] ensurePromptConfig failed:", err);
                return null;
            }
        }

        // Ensure a chat config exists (find existing or create from template)
        static async ensureChatConfig(chatDir, template, name, temperature) {
            try {
                // Search for existing config using page API (same pattern as am7chat.makeChat)
                let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatConfig"));
                q.field("groupId", chatDir.id);
                q.field("name", name);
                q.cache(false);
                let qr = await page.search(q);

                if (qr?.results?.length > 0) {
                    return qr.results[0];
                }

                // Clone from template and create via page API
                let icfg = am7model.newInstance("olio.llm.chatConfig");
                icfg.api.groupId(chatDir.id);
                icfg.api.groupPath(chatDir.path);
                icfg.api.name(name);
                // Copy relevant fields from template
                if (template.model) icfg.api.model(template.model);
                if (template.serverUrl) icfg.api.serverUrl(template.serverUrl);
                if (template.serviceType) icfg.api.serviceType(template.serviceType);
                if (template.messageTrim) icfg.api.messageTrim(template.messageTrim);
                await page.createObject(icfg.entity);

                // Re-search to get the created object
                qr = await page.search(q);
                if (qr?.results?.length > 0) {
                    return qr.results[0];
                }
                return null;
            } catch (err) {
                console.error("[CardGameLLM] ensureChatConfig failed:", err);
                return null;
            }
        }

        // Extract text content from LLM response
        static extractContent(response) {
            if (!response) return "";
            if (response.messages?.length > 0) {
                const lastMsg = response.messages[response.messages.length - 1];
                return lastMsg.content || lastMsg.text || "";
            }
            return response.content || response.text || String(response);
        }

        // Clean JSON from LLM response (strip markdown, fix common issues)
        static cleanJsonResponse(content) {
            if (!content) return null;

            let cleaned = content
                .replace(/```json\s*/gi, "")
                .replace(/```\s*/g, "")
                .replace(/^[^{]*/, "")
                .replace(/[^}]*$/, "")
                .trim();

            if (!cleaned) return null;

            // Fix common JSON issues
            cleaned = cleaned
                .replace(/,(\s*[}\]])/g, "$1")
                .replace(/'/g, '"');

            return cleaned;
        }

        // Initialize LLM with standard pattern
        async initializeLLM(chatName, promptName, systemPrompt, temperature) {
            try {
                // Check if am7chat is available
                if (typeof am7chat === "undefined" || !am7chat) {
                    this.lastError = "am7chat not available";
                    console.warn("[CardGameLLM] am7chat not available - LLM features disabled");
                    return false;
                }

                const chatDir = await CardGameLLM.findChatDir();
                if (!chatDir) {
                    this.lastError = "~/Chat directory not found";
                    return false;
                }

                const template = await CardGameLLM.getOpenChatTemplate(chatDir);
                if (!template) {
                    this.lastError = "Open Chat template not found";
                    return false;
                }

                // Use ~/CardGame/Chats for chatConfig storage
                const cgChatDir = await CardGameLLM.findCardGameChatDir();
                if (!cgChatDir) {
                    this.lastError = "~/CardGame/Chats directory not found";
                    return false;
                }

                this.promptConfig = await CardGameLLM.ensurePromptConfig(chatDir, promptName, systemPrompt);
                if (!this.promptConfig) {
                    this.lastError = "Failed to create prompt config";
                    return false;
                }

                this.chatConfig = await CardGameLLM.ensureChatConfig(cgChatDir, template, chatName, temperature);
                if (!this.chatConfig) {
                    this.lastError = "Failed to create chat config";
                    return false;
                }

                this.chatRequest = await am7chat.getChatRequest(chatName, this.chatConfig, this.promptConfig);
                this.initialized = !!this.chatRequest;
                if (!this.initialized) {
                    this.lastError = "Failed to get chat request";
                }
                return this.initialized;
            } catch (err) {
                this.lastError = err.message || String(err);
                console.error("[CardGameLLM] initializeLLM failed:", err);
                return false;
            }
        }

        // Send a message and get response
        async chat(prompt) {
            if (!this.initialized || !this.chatRequest) return null;
            try {
                return await am7chat.chat(this.chatRequest, prompt);
            } catch (err) {
                this.lastError = err.message;
                throw err;
            }
        }
    }

    window.CardGame.AI.CardGameLLM = CardGameLLM;

    console.log("[CardGame] AI/llmBase loaded");
}());
