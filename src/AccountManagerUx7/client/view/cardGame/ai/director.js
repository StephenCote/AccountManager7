/**
 * CardGame AI -- Director (LLM-powered AI Opponent)
 * Extracted from cardGame-v2.js (lines ~6619-6806, 6971-7006, 7282-7424).
 *
 * Includes:
 *   - CardGameDirector  (extends CardGameLLM)
 *   - applyAIDecision   (applies director's JSON to game state)
 *   - aiPlaceCards       (async entry point -- tries LLM then FIFO fallback)
 *   - checkPlacementComplete (advances turn / phase when both sides done)
 *
 * Depends on (via CardGame namespace -- late-bound):
 *   CardGame.AI.CardGameLLM          (llmBase.js)
 *   CardGame.Engine.isCoreCardType   (engine/combat.js -- future)
 *   CardGame.Engine.drawCardsForActor(engine/actions.js -- future)
 *   CardGame.State.gameState         (state/gameState.js -- future)
 *   CardGame.Engine.advancePhase     (engine/actions.js -- future)
 *   CardGame.Engine.checkGameOver    (engine/combat.js -- future)
 *   CardGame.Constants.GAME_PHASES   (constants/gameConstants.js)
 *
 * Exposes: window.CardGame.AI.{ CardGameDirector, applyAIDecision,
 *                                 aiPlaceCards, checkPlacementComplete }
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.AI = window.CardGame.AI || {};

    const CardGameLLM = window.CardGame.AI.CardGameLLM;

    // ── Lazy accessors for cross-module dependencies ─────────────────
    // These modules may not be loaded yet when this file is parsed;
    // we resolve them at call-time so load order doesn't matter.
    function getGameState()      { return window.CardGame.State?.gameState; }
    function getConstants()      { return window.CardGame.Constants; }
    function isCoreCardType(t)   { return window.CardGame.Engine?.isCoreCardType?.(t); }
    function drawCardsForActor(a, n) { return window.CardGame.Engine?.drawCardsForActor?.(a, n); }
    function advancePhase()      { return window.CardGame.Engine?.advancePhase?.(); }
    function checkGameOver()     { return window.CardGame.Engine?.checkGameOver?.(); }

    // ── Load external prompts (optional) ─────────────────────────────
    let directorPrompts = null;

    async function loadDirectorPrompts() {
        if (directorPrompts) return directorPrompts;
        try {
            directorPrompts = await m.request({
                method: "GET",
                url: "media/cardGame/prompts/director-system.json"
            });
        } catch (e) {
            console.warn("[CardGameDirector] Could not load director prompts, using defaults");
            directorPrompts = null;
        }
        return directorPrompts;
    }

    // ── CardGameDirector ─────────────────────────────────────────────
    // LLM-powered AI opponent decision making
    let gameDirector = null;

    class CardGameDirector extends CardGameLLM {
        constructor() {
            super();
            this.personality = null;
            this.lastDirective = null;
            this.consecutiveErrors = 0;
        }

        async initialize(opponentChar, themeId) {
            console.log("[CardGameDirector] Initializing for theme:", themeId);
            await loadDirectorPrompts();
            this.personality = this._extractPersonality(opponentChar);
            const systemPrompt = this._buildSystemPrompt(opponentChar, themeId);
            const ok = await this.initializeLLM(
                "CardGame AI Opponent",
                "CardGame AI Prompt",
                systemPrompt,
                0.4  // Low temperature for consistent decisions
            );
            if (ok) console.log("[CardGameDirector] Initialized successfully");
            return ok;
        }

        _extractPersonality(charPerson) {
            if (!charPerson) return "balanced";
            const alignment = charPerson.alignment || "";
            if (alignment.includes("EVIL") || alignment.includes("CHAOTIC")) return "aggressive";
            if (alignment.includes("LAWFUL") || alignment.includes("GOOD")) return "tactical";
            return "balanced";
        }

        _buildSystemPrompt(opponentChar, themeId) {
            // Use loaded prompts if available
            if (directorPrompts?.systemPrompt) {
                let prompt = directorPrompts.systemPrompt;
                const name = opponentChar?.name || "AI Opponent";
                const personality = this.personality || "balanced";
                // Substitute template variables
                prompt = prompt.replace(/\{name\}/g, name)
                               .replace(/\{themeId\}/g, themeId || "unknown")
                               .replace(/\{personality\}/g, personality);
                return prompt;
            }

            // Hardcoded fallback
            const name = opponentChar?.name || "AI Opponent";
            const personality = this.personality || "balanced";

            return `You are ${name}, an AI opponent in a card game. Theme: ${themeId}.
Personality: ${personality}

You receive game state JSON and respond with placement decision JSON.
Goal: reduce player HP or morale to 0.

Response format:
{"stacks":[{"position":2,"coreCard":"Attack"}],"strategy":"brief"}

Rules:
- "position" must be from availablePositions array
- "coreCard" must match a card name in your hand exactly
- Optional: "modifiers" array of skill card names from hand
- Place up to your AP cards
- Consider energy costs

Reply with ONLY the JSON object, no markdown or text.`;
        }

        async requestPlacement(gameState) {
            if (!this.initialized || !this.chatRequest) {
                return this._fifoFallback(gameState);
            }

            const prompt = this._buildPlacementPrompt(gameState);

            try {
                const response = await am7chat.chat(this.chatRequest, prompt);
                const content = CardGameLLM.extractContent(response);
                const directive = this._parseDirective(content);

                if (directive && directive.stacks) {
                    this.lastDirective = directive;
                    this.consecutiveErrors = 0;
                    console.log("[CardGameDirector] LLM decision:", directive);
                    return directive;
                }

                // Retry once on parse failure
                console.warn("[CardGameDirector] Parse failed, retrying...");
                const retryResponse = await am7chat.chat(this.chatRequest, prompt + "\n\nIMPORTANT: Output ONLY valid JSON, no markdown.");
                const retryContent = CardGameLLM.extractContent(retryResponse);
                const retryDirective = this._parseDirective(retryContent);

                if (retryDirective && retryDirective.stacks) {
                    this.lastDirective = retryDirective;
                    return retryDirective;
                }

                page.toast("warn", "AI decision unclear, using default");
                return this._fifoFallback(gameState);

            } catch (err) {
                this.consecutiveErrors++;
                this.lastError = err.message;
                console.error("[CardGameDirector] Placement request failed:", err);

                if (this.consecutiveErrors === 1) {
                    page.toast("warn", "AI unavailable, using default placement");
                }

                return this._fifoFallback(gameState);
            }
        }

        _buildPlacementPrompt(gameState) {
            const opp = gameState.opponent;
            const player = gameState.player;

            return JSON.stringify({
                type: "placement",
                round: gameState.roundNumber,
                yourTurn: gameState.currentTurn === "opponent",
                ai: {
                    ap: opp.ap - opp.apUsed,
                    energy: opp.needs?.energy || 14,
                    hp: opp.needs?.hp || 20,
                    morale: opp.needs?.morale || 20,
                    hand: opp.hand.map(c => ({
                        name: c.name,
                        type: c.type,
                        energyCost: c.energyCost || 0,
                        atk: c.atk,
                        effect: c.effect || c.onHit
                    }))
                },
                player: {
                    hp: player.needs?.hp || 20,
                    energy: player.needs?.energy || 14,
                    morale: player.needs?.morale || 20
                },
                availablePositions: gameState.initiative.opponentPositions
            }, null, 0);
        }

        _parseDirective(content) {
            if (!content) return null;
            console.log("[CardGameDirector] Raw response:", content.substring(0, 200));

            const cleaned = CardGameLLM.cleanJsonResponse(content);
            if (!cleaned) {
                console.warn("[CardGameDirector] No JSON found in response");
                return null;
            }

            try {
                const parsed = JSON.parse(cleaned);
                if (parsed?.stacks && Array.isArray(parsed.stacks)) {
                    return parsed;
                }
                console.warn("[CardGameDirector] Invalid structure - missing stacks array");
                return null;
            } catch (e) {
                console.warn("[CardGameDirector] JSON parse failed:", e.message);
                return null;
            }
        }

        _fifoFallback(gameState) {
            // Reuse existing simple AI logic
            const opp = gameState.opponent;
            const positions = gameState.initiative.opponentPositions;
            const coreCards = opp.hand.filter(c => isCoreCardType(c.type));

            const stacks = [];
            let cardsUsed = [];

            for (const posIdx of positions) {
                if (stacks.length >= opp.ap - opp.apUsed) break;
                if (coreCards.length === 0) break;

                const playable = coreCards.find(c => c.name === "Attack") ||
                                coreCards.find(c => c.type === "action") ||
                                coreCards[0];

                if (playable && (!playable.energyCost || playable.energyCost <= opp.needs?.energy)) {
                    stacks.push({
                        position: posIdx,
                        coreCard: playable.name,
                        modifiers: [],
                        target: "player"
                    });
                    coreCards.splice(coreCards.indexOf(playable), 1);
                    cardsUsed.push(playable);
                }
            }

            return { stacks, fallback: true };
        }
    }

    // ── Apply AI Decision ────────────────────────────────────────────
    // Apply AI decision from CardGameDirector to game state
    function applyAIDecision(decision) {
        const gameState = getGameState();
        if (!gameState || !decision || !decision.stacks) return;

        const opp = gameState.opponent;

        for (const stack of decision.stacks) {
            const pos = gameState.actionBar.positions.find(p => p.index === stack.position);
            if (!pos || pos.stack) continue;

            // Find the core card by name
            const coreCard = opp.hand.find(c => c.name === stack.coreCard);
            if (!coreCard) continue;

            // Check energy
            if (coreCard.energyCost && coreCard.energyCost > (opp.needs?.energy || 0)) continue;

            // Find modifier cards
            const modifiers = (stack.modifiers || [])
                .map(name => opp.hand.find(c => c.name === name))
                .filter(Boolean);

            // Place the stack
            pos.stack = { coreCard, modifiers };
            opp.apUsed++;

            if (coreCard.energyCost) {
                opp.needs.energy = (opp.needs.energy || 14) - coreCard.energyCost;
            }

            // Remove cards from hand
            opp.hand = opp.hand.filter(c => c !== coreCard && !modifiers.includes(c));

            console.log("[CardGame v2] AI placed:", coreCard.name, "at position", stack.position);
        }
    }

    // ── Simple AI Card Placement ─────────────────────────────────────
    // Now with optional LLM integration via CardGameDirector
    async function aiPlaceCards() {
        const gameState = getGameState();
        if (!gameState) return;

        let opp = gameState.opponent;
        let positions = gameState.initiative.opponentPositions;

        console.log("[CardGame v2] AI placing cards. Hand:", opp.hand.length, "AP:", opp.ap - opp.apUsed);

        // Try LLM-based placement if director is available
        if (gameDirector && gameDirector.initialized) {
            try {
                const decision = await gameDirector.requestPlacement(gameState);
                if (decision && decision.stacks && !decision.fallback) {
                    applyAIDecision(decision);
                    checkPlacementComplete();
                    m.redraw();
                    return;
                }
            } catch (err) {
                console.warn("[CardGame v2] Director placement failed, using fallback:", err);
            }
        }

        // Fallback: Simple FIFO placement
        let coreCards = opp.hand.filter(c => isCoreCardType(c.type));
        let modifierCards = opp.hand.filter(c => c.type === "skill");

        // Phase 1: Place core cards (actions, talk, magic) on available positions
        for (let posIdx of positions) {
            if (opp.apUsed >= opp.ap) break;
            if (coreCards.length === 0) break;

            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            if (!pos || pos.stack) continue;

            // Find a playable core card (prefer Attack)
            let playable = coreCards.find(c => c.name === "Attack") ||
                          coreCards.find(c => c.type === "action") ||
                          coreCards[0];

            if (playable) {
                // Check energy cost
                if (playable.energyCost && playable.energyCost > opp.energy) {
                    coreCards = coreCards.filter(c => c !== playable);
                    continue;
                }

                pos.stack = { coreCard: playable, modifiers: [] };
                opp.apUsed++;

                if (playable.energyCost) {
                    opp.energy -= playable.energyCost;
                }

                // Remove from hand and tracking arrays
                let idx = opp.hand.indexOf(playable);
                if (idx >= 0) opp.hand.splice(idx, 1);
                coreCards = coreCards.filter(c => c !== playable);

                console.log("[CardGame v2] AI placed core:", playable.name, "at position", posIdx);
            }
        }

        // Phase 2: Add skill modifiers to placed stacks (no AP cost)
        for (let posIdx of positions) {
            if (modifierCards.length === 0) break;

            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            if (!pos || !pos.stack || !pos.stack.coreCard) continue;

            // Add one skill modifier to this stack
            let skill = modifierCards.shift();
            if (skill) {
                pos.stack.modifiers.push(skill);
                let idx = opp.hand.indexOf(skill);
                if (idx >= 0) opp.hand.splice(idx, 1);
                console.log("[CardGame v2] AI added modifier:", skill.name, "to position", posIdx);
            }
        }

        // If AI still has AP but can't place anything, try drawing first
        let remainingCores = opp.hand.filter(c => isCoreCardType(c.type));
        let playableCores = remainingCores.filter(c => !c.energyCost || c.energyCost <= opp.energy);
        let emptyPositions = positions.filter(posIdx => {
            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            return pos && !pos.stack;
        });

        // If can't place but have AP and draw pile has cards, draw one
        if (opp.apUsed < opp.ap && playableCores.length === 0 && emptyPositions.length > 0 && opp.drawPile.length > 0) {
            console.log("[CardGame v2] AI drawing a card (no playable cores)");
            drawCardsForActor(opp, 1);
            // Check again for playable cores after draw
            remainingCores = opp.hand.filter(c => isCoreCardType(c.type));
            playableCores = remainingCores.filter(c => !c.energyCost || c.energyCost <= opp.energy);
        }

        // Forfeit if still can't place after potential draw
        if (opp.apUsed < opp.ap && (playableCores.length === 0 || emptyPositions.length === 0)) {
            console.log("[CardGame v2] AI forfeiting remaining AP - playable cores:", playableCores.length,
                "(total cores:", remainingCores.length, ") empty positions:", emptyPositions.length, "energy:", opp.energy);
            opp.apUsed = opp.ap;  // Mark as fully used
        }

        // AI placement done, switch back to player or end placement
        checkPlacementComplete();
    }

    // ── Check Placement Complete ─────────────────────────────────────
    function checkPlacementComplete() {
        const gameState = getGameState();
        if (!gameState) return;
        const GAME_PHASES = getConstants()?.GAME_PHASES;

        let playerDone = gameState.player.apUsed >= gameState.player.ap;
        let opponentDone = gameState.opponent.apUsed >= gameState.opponent.ap;

        console.log("[CardGame v2] checkPlacementComplete - Player done:", playerDone, "Opponent done:", opponentDone);

        if (playerDone && opponentDone) {
            console.log("[CardGame v2] Both done, advancing to resolution in 800ms");
            // Brief delay so player can see their last card placed
            setTimeout(() => {
                const gs = getGameState();
                if (gs && GAME_PHASES && gs.phase === GAME_PHASES.DRAW_PLACEMENT) {
                    advancePhase(); // Move to resolution
                }
            }, 800);
        } else {
            gameState.currentTurn = playerDone ? "opponent" : "player";
            console.log("[CardGame v2] Turn switched to:", gameState.currentTurn);
            m.redraw();

            // If it's now the opponent's turn and they have AP, trigger AI
            if (gameState.currentTurn === "opponent" && !opponentDone) {
                setTimeout(() => {
                    const gs = getGameState();
                    if (gs && GAME_PHASES && gs.phase === GAME_PHASES.DRAW_PLACEMENT) {
                        console.log("[CardGame v2] Triggering AI placement continuation");
                        aiPlaceCards();
                        m.redraw();
                    }
                }, 500);
            }
        }
    }

    // ── Director singleton accessor ──────────────────────────────────
    function getDirector() { return gameDirector; }
    function setDirector(d) { gameDirector = d; }

    // ── Expose on CardGame.AI namespace ──────────────────────────────
    Object.assign(window.CardGame.AI, {
        CardGameDirector,
        applyAIDecision,
        aiPlaceCards,
        checkPlacementComplete,
        getDirector,
        setDirector
    });

    console.log("[CardGame] AI/director loaded");
}());
