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
            this.playerName = null;
            this.npcPersonality = null;
            this.interactionHistory = [];
            this.currentConversation = [];
            this.chatActive = false;
        }

        async initialize(npcCharacter, playerCharacter, themeId, sessionSuffix) {
            this.npcName = npcCharacter?.name || "NPC";
            this.playerName = playerCharacter?.name || "Player";
            this.npcPersonality = this._extractNpcPersonality(npcCharacter);
            await loadChatPrompts();
            const systemPrompt = this._buildChatPrompt(npcCharacter, playerCharacter, themeId);
            const chatName = sessionSuffix ? "CG Chat " + sessionSuffix : "CardGame NPC Chat";
            const ok = await this.initializeLLM(
                chatName,
                "CardGame NPC Chat Prompt",
                systemPrompt,
                0.8  // Higher temperature for varied dialogue
            );
            if (ok) console.log("[CardGameChatManager] Initialized for NPC:", this.npcName, "vs Player:", this.playerName);
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

        _buildChatPrompt(npcCharacter, playerCharacter, themeId) {
            const name = npcCharacter?.name || "Unknown";
            const desc = npcCharacter?.description || "";
            const npcRace = npcCharacter?.race || "unknown";
            const personality = this.npcPersonality;
            const pName = playerCharacter?.name || "the opponent";
            const pDesc = playerCharacter?.description || "";
            const pRace = playerCharacter?.race || "unknown";
            const pTraits = playerCharacter?.personality?.split(",").map(t => t.trim()).join(", ") || "";
            const pAlignment = playerCharacter?.alignment || "";

            // Use loaded prompts if available
            if (chatPrompts?.systemPrompt) {
                let prompt = chatPrompts.systemPrompt;
                prompt = prompt.replace(/\{npcName\}/g, name)
                               .replace(/\{themeId\}/g, themeId || "unknown")
                               .replace(/\{npcDescription\}/g, desc)
                               .replace(/\{npcRace\}/g, npcRace)
                               .replace(/\{personalityTraits\}/g, personality.traits.join(", ") || "mysterious")
                               .replace(/\{personalityTone\}/g, personality.tone)
                               .replace(/\{playerName\}/g, pName)
                               .replace(/\{playerDescription\}/g, pDesc)
                               .replace(/\{playerRace\}/g, pRace)
                               .replace(/\{playerTraits\}/g, pTraits)
                               .replace(/\{playerAlignment\}/g, pAlignment)
                               .replace(/\{hostileHint\}/g, personality.tone === "hostile" ? "You are hostile but can be swayed by a convincing argument." : "")
                               .replace(/\{friendlyHint\}/g, personality.tone === "friendly" ? "You are open to conversation and willing to listen." : "");
                return prompt;
            }

            // Hardcoded fallback
            let prompt = `You are ${name}, a ${npcRace} in a card game battle. Theme: ${themeId}.
${desc ? `Your description: ${desc}` : ""}
Your personality traits: ${personality.traits.join(", ") || "mysterious"}
Your disposition: ${personality.tone}

You are speaking with ${pName}${pRace !== "unknown" ? `, a ${pRace}` : ""}.
${pDesc ? `They are described as: ${pDesc}` : ""}
${pTraits ? `Their personality: ${pTraits}` : ""}

${pName} has engaged you in conversation during combat via a Talk card.
Respond in character as ${name} with 1-3 sentences. Stay true to your personality and background.
Address ${pName} by name when appropriate.
${personality.tone === "hostile" ? "You are hostile but can be swayed by a convincing argument." : ""}
${personality.tone === "friendly" ? "You are open to conversation and willing to listen." : ""}

React naturally based on what ${pName} says:
- Taunt: React according to your personality (angry, amused, dismissive)
- Persuade: Consider their words but stay in character
- Intimidate: Show fear or defiance based on your traits
- Negotiate: Be open or closed depending on your alignment

Respond naturally in character. No game mechanics or meta-commentary, just dialogue.`;
            return prompt;
        }

        async startConversation() {
            this.chatActive = true;
            this.currentConversation = [];
            console.log("[CardGameChatManager] Conversation started with:", this.npcName);
            return true;
        }

        async sendMessage(playerMessage, gameContext) {
            if (!this.initialized || !this.chatActive) {
                return { text: "...", error: true };
            }

            try {
                // Build game state context preamble
                let contextParts = [];
                if (gameContext) {
                    let stateLine = `[Round ${gameContext.round || "?"}`;
                    if (gameContext.opponentHp != null) stateLine += ` | Your HP: ${gameContext.opponentHp}`;
                    if (gameContext.opponentMorale != null) stateLine += `, Morale: ${gameContext.opponentMorale}`;
                    if (gameContext.playerHp != null) stateLine += ` | ${this.playerName}'s HP: ${gameContext.playerHp}`;
                    stateLine += "]";
                    contextParts.push(stateLine);
                    if (gameContext.playerEmotion?.description) {
                        contextParts.push(`${this.playerName} ${gameContext.playerEmotion.description}.`);
                    }
                }

                // Build context with recent conversation
                const contextMessages = this.currentConversation.slice(-4)
                    .map(m => `${m.role === "player" ? this.playerName : this.npcName}: ${m.text}`)
                    .join("\n");

                let prompt = "";
                if (contextParts.length > 0) prompt += contextParts.join("\n") + "\n\n";
                if (contextMessages) prompt += `Previous exchange:\n${contextMessages}\n\n`;
                prompt += `${this.playerName}: ${playerMessage}\n\n${this.npcName}:`;

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

        /**
         * Generate a short banter line from the NPC based on game events.
         * Used for per-turn opponent chit-chat (not a full conversation).
         * @param {Object} context - { event, playerAction, opponentAction, playerHp, opponentHp, round, emotion }
         * @returns {{ text: string, speaker: string } | null}
         */
        async generateBanter(context) {
            if (!this.initialized) return null;

            try {
                let parts = [`[Banter — Round ${context.round || "?"}]`];
                if (context.event) parts.push(`Event: ${context.event}`);
                if (context.playerAction) parts.push(`${this.playerName} played: ${context.playerAction}`);
                if (context.opponentAction) parts.push(`You played: ${context.opponentAction}`);
                if (context.playerHp != null) parts.push(`${this.playerName} HP: ${context.playerHp}`);
                if (context.opponentHp != null) parts.push(`Your HP: ${context.opponentHp}`);
                if (context.emotion) {
                    let level = context.banterLevel || "moderate";
                    if (level === "aggressive") {
                        parts.push(`POKER FACE: ${this.playerName} looks ${context.emotion}. Call it out!`);
                    } else if (level === "moderate") {
                        parts.push(`POKER FACE: ${this.playerName} ${context.emotionDesc || "seems " + context.emotion}. Reference it indirectly.`);
                    }
                    // subtle: don't include emotion at all
                }
                parts.push(`\nAs ${this.npcName}, say ONE short quip or taunt (max 1 sentence). Stay in character.`);

                const prompt = parts.join("\n");
                const response = await this.chat(prompt);
                const text = CardGameLLM.extractContent(response).trim();
                if (text) {
                    return { text, speaker: this.npcName };
                }
                return null;
            } catch (err) {
                console.warn("[CardGameChatManager] Banter generation failed:", err);
                return null;
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
