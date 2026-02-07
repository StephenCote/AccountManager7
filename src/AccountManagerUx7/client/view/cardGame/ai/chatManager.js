/**
 * CardGame AI -- Chat Manager (LLM-powered NPC Conversation)
 * Extracted from cardGame-v2.js (lines ~7008-7146).
 *
 * Includes:
 *   - CardGameChatManager  (extends CardGameLLM)
 *
 * Depends on:
 *   CardGame.AI.CardGameLLM  (llmBase.js)
 *
 * Exposes: window.CardGame.AI.CardGameChatManager
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.AI = window.CardGame.AI || {};

    const CardGameLLM = window.CardGame.AI.CardGameLLM;

    // ── Load external prompts (optional) ─────────────────────────────
    let chatPrompts = null;

    async function loadChatPrompts() {
        if (chatPrompts) return chatPrompts;
        try {
            chatPrompts = await m.request({
                method: "GET",
                url: "media/cardGame/prompts/chat-system.json"
            });
        } catch (e) {
            console.warn("[CardGameChatManager] Could not load chat prompts, using defaults");
            chatPrompts = null;
        }
        return chatPrompts;
    }

    // ── CardGameChatManager ──────────────────────────────────────────
    // LLM-powered NPC conversation for Talk cards
    let gameChatManager = null;

    class CardGameChatManager extends CardGameLLM {
        constructor() {
            super();
            this.npcName = null;
            this.npcPersonality = null;
            this.interactionHistory = [];
            this.currentConversation = [];
            this.chatActive = false;
        }

        async initialize(npcCharacter, themeId) {
            this.npcName = npcCharacter?.name || "NPC";
            this.npcPersonality = this._extractNpcPersonality(npcCharacter);
            await loadChatPrompts();
            const systemPrompt = this._buildChatPrompt(npcCharacter, themeId);

            const ok = await this.initializeLLM(
                "CardGame NPC Chat",
                "CardGame NPC Chat Prompt",
                systemPrompt,
                0.8  // Higher temperature for varied dialogue
            );
            if (ok) console.log("[CardGameChatManager] Initialized for NPC:", this.npcName);
            return ok;
        }

        _extractNpcPersonality(charPerson) {
            if (!charPerson) return { tone: "neutral", traits: [] };
            const traits = charPerson.personality?.split(",").map(t => t.trim()) || [];
            const alignment = charPerson.alignment || "NEUTRAL";
            let tone = "neutral";
            if (alignment.includes("EVIL")) tone = "hostile";
            else if (alignment.includes("GOOD")) tone = "friendly";
            else if (alignment.includes("CHAOTIC")) tone = "unpredictable";
            return { tone, traits, alignment };
        }

        _buildChatPrompt(npcCharacter, themeId) {
            const name = npcCharacter?.name || "Unknown";
            const desc = npcCharacter?.description || "";
            const personality = this.npcPersonality;

            // Use loaded prompts if available
            if (chatPrompts?.systemPrompt) {
                let prompt = chatPrompts.systemPrompt;
                prompt = prompt.replace(/\{name\}/g, name)
                               .replace(/\{themeId\}/g, themeId || "unknown")
                               .replace(/\{description\}/g, desc)
                               .replace(/\{traits\}/g, personality.traits.join(", ") || "mysterious")
                               .replace(/\{tone\}/g, personality.tone);
                return prompt;
            }

            // Hardcoded fallback
            return `You are ${name}, an NPC in a card game battle. Theme: ${themeId}.
${desc ? `Description: ${desc}` : ""}
Personality traits: ${personality.traits.join(", ") || "mysterious"}
Disposition: ${personality.tone}

You are being addressed by your opponent during combat via a Talk card.
Respond in character with 1-3 sentences. Stay true to your personality.
${personality.tone === "hostile" ? "You are hostile but can be persuaded." : ""}
${personality.tone === "friendly" ? "You are open to conversation." : ""}

If the player is trying to:
- Taunt: React according to your personality (angry, amused, dismissive)
- Persuade: Consider their words but stay in character
- Intimidate: Show fear or defiance based on your traits
- Negotiate: Be open or closed depending on alignment

Respond naturally in character. No game mechanics, just dialogue.`;
        }

        async startConversation() {
            this.chatActive = true;
            this.currentConversation = [];
            console.log("[CardGameChatManager] Conversation started with:", this.npcName);
            return true;
        }

        async sendMessage(playerMessage) {
            if (!this.initialized || !this.chatActive) {
                return { text: "...", error: true };
            }

            try {
                // Build context with recent conversation
                const contextMessages = this.currentConversation.slice(-4)
                    .map(m => `${m.role === "player" ? "Player" : this.npcName}: ${m.text}`)
                    .join("\n");

                const prompt = contextMessages
                    ? `Previous exchange:\n${contextMessages}\n\nPlayer: ${playerMessage}\n\n${this.npcName}:`
                    : `Player: ${playerMessage}\n\n${this.npcName}:`;

                const response = await this.chat(prompt);
                const npcReply = CardGameLLM.extractContent(response).trim();

                // Store in conversation history
                this.currentConversation.push({ role: "player", text: playerMessage });
                this.currentConversation.push({ role: "npc", text: npcReply });

                return { text: npcReply, speaker: this.npcName };
            } catch (err) {
                console.error("[CardGameChatManager] Message failed:", err);
                return { text: "...", error: true };
            }
        }

        async concludeConversation() {
            this.chatActive = false;

            // Create interaction record for future context
            const interaction = {
                timestamp: Date.now(),
                npcName: this.npcName,
                messages: [...this.currentConversation],
                outcome: this._evaluateOutcome()
            };

            this.interactionHistory.push(interaction);
            console.log("[CardGameChatManager] Conversation concluded. Outcome:", interaction.outcome);

            return interaction;
        }

        _evaluateOutcome() {
            // Simple heuristic based on conversation length and content
            const msgCount = this.currentConversation.length;
            if (msgCount === 0) return "silent";
            if (msgCount <= 2) return "brief";
            if (msgCount <= 4) return "moderate";
            return "extensive";
        }

        loadInteractionHistory(history) {
            this.interactionHistory = history || [];
        }

        getInteractionSummary() {
            return this.interactionHistory.map(i => ({
                npc: i.npcName,
                when: new Date(i.timestamp).toLocaleDateString(),
                outcome: i.outcome,
                preview: i.messages[0]?.text?.substring(0, 50) + "..."
            }));
        }
    }

    // ── ChatManager singleton accessor ───────────────────────────────
    function getChatManager() { return gameChatManager; }
    function setChatManager(cm) { gameChatManager = cm; }

    // ── Expose on CardGame.AI namespace ──────────────────────────────
    Object.assign(window.CardGame.AI, {
        CardGameChatManager,
        getChatManager,
        setChatManager
    });

    console.log("[CardGame] AI/chatManager loaded");
}());
