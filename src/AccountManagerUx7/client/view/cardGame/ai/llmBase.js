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

        // Phase 10 (OI-47): Static methods now delegate to LLMConnector

        // Find the ~/Chat directory
        static async findChatDir() {
            return LLMConnector.findChatDir();
        }

        // Find or create the ~/CardGame/Chats directory (CardGame-specific, not in LLMConnector)
        static async findCardGameChatDir() {
            const chatDir = await page.makePath("auth.group", "data", "~/CardGame/Chats");
            if (!chatDir) {
                console.warn("[CardGameLLM] ~/CardGame/Chats could not be created");
            }
            return chatDir;
        }

        // Get the "Open Chat" template config
        static async getOpenChatTemplate(chatDir) {
            return LLMConnector.getOpenChatTemplate(chatDir);
        }

        // Ensure a prompt config exists (create or find)
        static async ensurePromptConfig(chatDir, name, systemPrompt) {
            // LLMConnector.ensurePrompt expects array; wrap single string
            let system = Array.isArray(systemPrompt) ? systemPrompt : [systemPrompt];
            return LLMConnector.ensurePrompt(name, system);
        }

        // Ensure a chat config exists (find existing or create from template)
        static async ensureChatConfig(chatDir, template, name, temperature) {
            let overrides = {};
            if (temperature) overrides.chatOptions = { temperature: temperature };
            return LLMConnector.ensureConfig(name, template, overrides, chatDir);
        }

        // Extract text content from LLM response
        static extractContent(response) {
            let content = LLMConnector.extractContent(response);
            return content || (response ? String(response) : "");
        }

        // Clean JSON from LLM response (returns cleaned string, not parsed)
        static cleanJsonResponse(content) {
            if (!content) return null;
            // Use LLMConnector.parseDirective for full repair, then re-stringify
            let parsed = LLMConnector.parseDirective(content);
            return parsed ? JSON.stringify(parsed) : null;
        }

        // Initialize LLM with standard pattern (Phase 10: uses LLMConnector)
        async initializeLLM(chatName, promptName, systemPrompt, temperature) {
            try {
                if (typeof LLMConnector === "undefined" || !LLMConnector) {
                    this.lastError = "LLMConnector not available";
                    console.warn("[CardGameLLM] LLMConnector not available - LLM features disabled");
                    return false;
                }

                const chatDir = await LLMConnector.findChatDir();
                if (!chatDir) {
                    this.lastError = "~/Chat directory not found";
                    return false;
                }

                const template = await LLMConnector.getOpenChatTemplate(chatDir);
                if (!template) {
                    this.lastError = "Open Chat template not found";
                    return false;
                }

                // Use ~/CardGame/Chats for chatConfig storage (CardGame-specific)
                const cgChatDir = await CardGameLLM.findCardGameChatDir();
                if (!cgChatDir) {
                    this.lastError = "~/CardGame/Chats directory not found";
                    return false;
                }

                let system = Array.isArray(systemPrompt) ? systemPrompt : [systemPrompt];
                this.promptConfig = await LLMConnector.ensurePrompt(promptName, system);
                if (!this.promptConfig) {
                    this.lastError = "Failed to create prompt config";
                    return false;
                }

                let overrides = {};
                if (temperature) overrides.chatOptions = { temperature: temperature };
                this.chatConfig = await LLMConnector.ensureConfig(chatName, template, overrides, cgChatDir);
                if (!this.chatConfig) {
                    this.lastError = "Failed to create chat config";
                    return false;
                }

                this.chatRequest = await LLMConnector.createSession(chatName, this.chatConfig, this.promptConfig);
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

        // Send a message and get response (Phase 10: LLMConnector)
        async chat(prompt) {
            if (!this.initialized || !this.chatRequest) return null;
            try {
                return await LLMConnector.chat(this.chatRequest, prompt);
            } catch (err) {
                this.lastError = err.message;
                throw err;
            }
        }
    }

    window.CardGame.AI.CardGameLLM = CardGameLLM;

    console.log("[CardGame] AI/llmBase loaded");
}());
