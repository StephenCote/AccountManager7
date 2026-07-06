/**
 * CardGame AI -- Narrator (LLM-powered Narration)
 * Extracted from cardGame-v2.js (lines ~6808-6969).
 *
 * Includes:
 *   - CardGameNarrator  (extends CardGameLLM)
 *
 * Depends on:
 *   CardGame.AI.CardGameLLM  (llmBase.js)
 *
 * Exposes: window.CardGame.AI.CardGameNarrator
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.AI = window.CardGame.AI || {};

    const CardGameLLM = window.CardGame.AI.CardGameLLM;

    // ── Load external prompts (optional) ─────────────────────────────
    let narratorPrompts = null;

    async function loadNarratorPrompts() {
        if (narratorPrompts) return narratorPrompts;
        try {
            narratorPrompts = await m.request({
                method: "GET",
                url: "media/cardGame/prompts/narrator-system.json"
            });
        } catch (e) {
            console.warn("[CardGameNarrator] Could not load narrator prompts, using defaults");
            narratorPrompts = null;
        }
        return narratorPrompts;
    }

    // ── CardGameNarrator ─────────────────────────────────────────────
    // LLM-powered narration at key game moments
    let gameNarrator = null;

    class CardGameNarrator extends CardGameLLM {
        static TRIGGER_POINTS = {
            GAME_START: "game_start",
            ROUND_START: "round_start",
            ENCOUNTER_REVEAL: "encounter_reveal",
            STACK_REVEAL: "stack_reveal",
            RESOLUTION: "resolution",
            ROUND_END: "round_end",
            GAME_END: "game_end"
        };

        static PROFILES = {
            "arena-announcer": {
                name: "Arena Announcer",
                personality: "Bombastic sports commentator. Over-the-top excitement, play-by-play analysis.",
                maxSentences: { game_start: 3, round_start: 2, resolution: 4, round_end: 1, game_end: 3 }
            },
            "dungeon-master": {
                name: "Dungeon Master",
                personality: "Classic tabletop DM. Atmospheric, descriptive, world-building.",
                maxSentences: { game_start: 3, round_start: 2, resolution: 4, round_end: 2, game_end: 3 }
            },
            "war-correspondent": {
                name: "War Correspondent",
                personality: "Gritty battlefield reporter. Terse, factual with emotional undertones.",
                maxSentences: { game_start: 2, round_start: 1, resolution: 3, round_end: 1, game_end: 2 }
            },
            "bard": {
                name: "Bard",
                personality: "Poetic narrator. Speaks in rhythm, references lore, foreshadows.",
                maxSentences: { game_start: 3, round_start: 2, resolution: 5, round_end: 2, game_end: 4 }
            }
        };

        constructor() {
            super();
            this.profile = "arena-announcer";
            this.lastNarration = null;
            this.enabled = true;
        }

        async initialize(profileId, themeId, sessionSuffix) {
            this.profile = profileId || "arena-announcer";
            await loadNarratorPrompts();

            // Merge loaded profiles into PROFILES if available
            if (narratorPrompts?.profiles) {
                Object.assign(CardGameNarrator.PROFILES, narratorPrompts.profiles);
            }

            const profileConfig = CardGameNarrator.PROFILES[this.profile];
            const systemPrompt = this._buildNarratorPrompt(profileConfig, themeId);
            const chatName = sessionSuffix ? "CG Narrator " + sessionSuffix : "CardGame Narrator";
            const ok = await this.initializeLLM(
                chatName,
                "CardGame Narrator Prompt",
                systemPrompt,
                0.7  // Higher temperature for creative narration
            );
            if (ok) console.log("[CardGameNarrator] Initialized with profile:", this.profile);
            return ok;
        }

        _buildNarratorPrompt(profileConfig, themeId) {
            // Use loaded prompts if available
            if (narratorPrompts?.systemPrompt) {
                let prompt = narratorPrompts.systemPrompt;
                prompt = prompt.replace(/\{profileName\}/g, profileConfig.name)
                               .replace(/\{themeId\}/g, themeId || "unknown")
                               .replace(/\{personality\}/g, profileConfig.personality);
                return prompt;
            }

            // Hardcoded fallback
            return `You are a ${profileConfig.name} narrating a card game battle. Theme: ${themeId}.

Personality: ${profileConfig.personality}

You will receive game events and must provide brief, engaging narration.
Keep responses concise - usually 1-3 sentences.
Match the tone to the event (dramatic for combat, tense for close calls).

For RESOLUTION events, also suggest a scene for image generation:
Add a line starting with "IMAGE:" describing a single dramatic moment.

Respond with plain text narration only. No JSON, no markdown.`;
        }

        async narrate(trigger, context) {
            if (!this.enabled || !this.initialized || !this.chatRequest) {
                return { text: null, imagePrompt: null };
            }

            try {
                const prompt = this._buildTriggerPrompt(trigger, context);
                const response = await this.chat(prompt);
                const content = CardGameLLM.extractContent(response);
                const parsed = this._parseNarration(content);
                this.lastNarration = parsed;
                return parsed;
            } catch (err) {
                console.error("[CardGameNarrator] Narration failed:", err);
                return { text: null, imagePrompt: null };
            }
        }

        _buildTriggerPrompt(trigger, context) {
            const profile = CardGameNarrator.PROFILES[this.profile];
            const maxSentences = profile?.maxSentences?.[trigger] || 3;

            let prompt = `EVENT: ${trigger.toUpperCase()}\n`;

            switch (trigger) {
                case "game_start":
                    prompt += `A new battle begins!\n`;
                    prompt += `${context.playerName} faces ${context.opponentName}\n`;
                    prompt += `Introduce both combatants dramatically.\n`;
                    break;

                case "game_end":
                    prompt += `The battle is over after ${context.rounds} rounds!\n`;
                    prompt += `Winner: ${context.winner}\n`;
                    prompt += `Defeated: ${context.loser}\n`;
                    prompt += context.isPlayerVictory
                        ? `Celebrate the player's triumph!\n`
                        : `Acknowledge the player's defeat with dignity.\n`;
                    break;

                case "round_start":
                    prompt += `Round ${context.roundNumber}\n`;
                    prompt += `Player HP: ${context.playerHp}/20, Opponent HP: ${context.opponentHp}/20\n`;
                    break;

                case "resolution":
                    prompt += `Player played: ${context.playerStack || "nothing"}\n`;
                    prompt += `Player roll: ${context.playerRoll?.raw || "?"} + mods = ${context.playerRoll?.total || "?"}\n`;
                    prompt += `Opponent played: ${context.opponentStack || "nothing"}\n`;
                    prompt += `Opponent roll: ${context.opponentRoll?.raw || "?"} + mods = ${context.opponentRoll?.total || "?"}\n`;
                    prompt += `Result: ${context.outcome} (${context.damage || 0} damage)\n`;
                    break;

                case "round_end":
                    prompt += `Round ${context.roundNumber} complete\n`;
                    prompt += `Player HP: ${context.playerHp}, Opponent HP: ${context.opponentHp}\n`;
                    break;
            }

            // Add Poker Face emotion hint if available
            if (context.playerEmotion && context.playerEmotionDesc) {
                prompt += `\nPOKER FACE: The player ${context.playerEmotionDesc}. Work this into your narration subtly.`;
            }

            prompt += `\nNarrate in ${maxSentences} sentences or less.`;
            if (trigger === "resolution") {
                prompt += `\nAlso add "IMAGE:" line for scene generation.`;
            }

            return prompt;
        }

        _parseNarration(content) {
            const lines = content.split("\n");
            let text = "";
            let imagePrompt = null;

            for (const line of lines) {
                if (line.trim().toUpperCase().startsWith("IMAGE:")) {
                    imagePrompt = line.replace(/^IMAGE:\s*/i, "").trim();
                } else if (line.trim()) {
                    text += (text ? " " : "") + line.trim();
                }
            }

            return { text, imagePrompt };
        }
    }

    // ── Narrator singleton accessor ──────────────────────────────────
    function getNarrator() { return gameNarrator; }
    function setNarrator(n) { gameNarrator = n; }

    // ── Expose on CardGame.AI namespace ──────────────────────────────
    Object.assign(window.CardGame.AI, {
        CardGameNarrator,
        getNarrator,
        setNarrator
    });

    console.log("[CardGame] AI/narrator loaded");
}());
