/**
 * CardGame AI -- LLM Base Class
 * Shared LLM infrastructure for Director, Narrator, and Chat.
 *
 * Ported from Ux7 IIFE to ESM.
 *
 * Exports: CardGameLLM
 */
import { am7model } from '../../core/model.js';

function getPage() { return am7model._page; }
function getClient() { return am7model._client; }
function getLLMConnector() { return getPage()?.components?.llmConnector; }

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
        return getLLMConnector()?.findChatDir();
    }

    // Find or create the ~/CardGame/Chats directory (CardGame-specific, not in LLMConnector)
    static async findCardGameChatDir() {
        const chatDir = await getPage().makePath("auth.group", "data", "~/CardGame/Chats");
        if (!chatDir) {
            console.warn("[CardGameLLM] ~/CardGame/Chats could not be created");
        }
        return chatDir;
    }

    // Get the "Open Chat" template config
    static async getOpenChatTemplate(chatDir) {
        return getLLMConnector()?.getOpenChatTemplate(chatDir);
    }

    // Ensure a prompt config exists (create or find)
    static async ensurePromptConfig(chatDir, name, systemPrompt) {
        // LLMConnector.ensurePrompt expects array; wrap single string
        let system = Array.isArray(systemPrompt) ? systemPrompt : [systemPrompt];
        return getLLMConnector()?.ensurePrompt(name, system);
    }

    // Ensure a chat config exists (find existing or create from template)
    static async ensureChatConfig(chatDir, template, name, temperature) {
        let overrides = {};
        if (temperature) overrides.chatOptions = { temperature: temperature };
        return getLLMConnector()?.ensureConfig(name, template, overrides, chatDir);
    }

    // Extract text content from LLM response
    static extractContent(response) {
        const connector = getLLMConnector();
        let content = connector?.extractContent(response);
        return content || (response ? String(response) : "");
    }

    // Clean JSON from LLM response (returns cleaned string, not parsed)
    static cleanJsonResponse(content) {
        if (!content) return null;
        const connector = getLLMConnector();
        // Use LLMConnector.parseDirective for full repair, then re-stringify
        let parsed = connector?.parseDirective(content);
        return parsed ? JSON.stringify(parsed) : null;
    }

    // Initialize LLM with standard pattern (Phase 10: uses LLMConnector)
    async initializeLLM(chatName, promptName, systemPrompt, temperature) {
        try {
            const connector = getLLMConnector();
            if (!connector) {
                this.lastError = "LLMConnector not available";
                console.warn("[CardGameLLM] LLMConnector not available - LLM features disabled");
                return false;
            }

            // Ensure shared library is initialized; show wizard if not
            let libReady = await connector.ensureLibrary();
            if (!libReady) {
                const ChatSetupWizard = getPage()?.components?.chatSetupWizard;
                if (ChatSetupWizard) {
                    let self = this;
                    ChatSetupWizard.show(function() {
                        self.initializeLLM(chatName, promptName, systemPrompt, temperature);
                    });
                }
                return false;
            }

            const chatDir = await connector.findChatDir();
            if (!chatDir) {
                this.lastError = "~/Chat directory not found";
                return false;
            }

            const template = await connector.getOpenChatTemplate(chatDir);
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
            this.promptConfig = await connector.ensurePrompt(promptName, system);
            if (!this.promptConfig) {
                this.lastError = "Failed to create prompt config";
                return false;
            }

            let overrides = {};
            if (temperature) overrides.chatOptions = { temperature: temperature };
            this.chatConfig = await connector.ensureConfig(chatName, template, overrides, cgChatDir);
            if (!this.chatConfig) {
                this.lastError = "Failed to create chat config";
                return false;
            }

            this.chatRequest = await connector.createSession(chatName, this.chatConfig, this.promptConfig);
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
            const connector = getLLMConnector();
            return await connector.chat(this.chatRequest, prompt);
        } catch (err) {
            this.lastError = err.message;
            throw err;
        }
    }
}

export { CardGameLLM };

console.log("[CardGame] AI/llmBase loaded");
