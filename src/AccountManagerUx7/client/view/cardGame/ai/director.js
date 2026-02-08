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
    function getGameState()      { return window.CardGame.GameState?.state?.gameState; }
    function getConstants()      { return window.CardGame.Constants; }
    function isCoreCardType(t)   { return window.CardGame.Engine?.isCoreCardType?.(t); }
    function drawCardsForActor(a, n) { return window.CardGame.Engine?.drawCardsForActor?.(a, n); }
    function advancePhase()      { return window.CardGame.GameState?.advancePhase?.(); }
    function checkGameOver()     { return window.CardGame.Engine?.checkGameOver?.(); }
    function selectAction(state, posIdx, actionName) { return window.CardGame.Engine?.selectAction?.(state, posIdx, actionName); }
    function getActionsForActor(actor) { return window.CardGame.Engine?.getActionsForActor?.(actor) || []; }
    function isActionPlacedThisRound(state, name, owner) { return window.CardGame.Engine?.isActionPlacedThisRound?.(state, name, owner); }

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
{"stacks":[{"position":2,"coreCard":"Attack","modifiers":["Precise Strike"]}],"strategy":"brief"}

Rules:
- "position" must be from availablePositions array
- "coreCard" must match an action name from your availableActions list exactly
- Each action can only be placed once per round
- Optional: "modifiers" array of modifier card names from your modifierCards
- Place up to your AP actions
- Consider energy costs (actions with energyCost > 0 consume energy)
- Your hand only contains modifier cards (skill, magic, item) — not actions

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
            const ACTION_DEFS = getConstants()?.ACTION_DEFINITIONS || {};

            // Get available actions for the opponent's class
            let availableActions = getActionsForActor(opp);
            let actionDetails = availableActions.map(name => {
                let def = ACTION_DEFS[name];
                return {
                    name: name,
                    type: def?.type || "Unknown",
                    energyCost: def?.energyCost || 0,
                    desc: def?.desc || "",
                    alreadyPlaced: isActionPlacedThisRound(gameState, name, "opponent")
                };
            }).filter(a => !a.alreadyPlaced);

            return JSON.stringify({
                type: "placement",
                round: gameState.roundNumber,
                yourTurn: gameState.currentTurn === "opponent",
                ai: {
                    ap: opp.ap - opp.apUsed,
                    energy: opp.energy || 14,
                    hp: opp.needs?.hp || 20,
                    morale: opp.needs?.morale || 20,
                    availableActions: actionDetails,
                    modifierCards: opp.hand.map(c => ({
                        name: c.name,
                        type: c.type,
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
            // Use available actions from class (not hand cards)
            const opp = gameState.opponent;
            const positions = gameState.initiative.opponentPositions;
            const ACTION_DEFS = getConstants()?.ACTION_DEFINITIONS || {};

            // Get available actions, prioritize Attack
            let availableActions = getActionsForActor(opp).slice();
            let stacks = [];

            for (const posIdx of positions) {
                if (stacks.length >= opp.ap - opp.apUsed) break;
                if (availableActions.length === 0) break;

                // Pick best action: Attack first, then other offensive, then any
                let actionName = availableActions.find(a => a === "Attack") ||
                                 availableActions.find(a => ACTION_DEFS[a]?.type === "Offensive") ||
                                 availableActions.find(a => ACTION_DEFS[a]?.type === "Magic") ||
                                 availableActions[0];

                if (!actionName) break;
                let def = ACTION_DEFS[actionName];

                // Check energy cost
                if (def?.energyCost && def.energyCost > (opp.energy || 0)) {
                    availableActions = availableActions.filter(a => a !== actionName);
                    continue;
                }

                // Check not already placed this round
                if (isActionPlacedThisRound(gameState, actionName, "opponent")) {
                    availableActions = availableActions.filter(a => a !== actionName);
                    continue;
                }

                stacks.push({
                    position: posIdx,
                    coreCard: actionName,
                    modifiers: [],
                    target: "player"
                });
                availableActions = availableActions.filter(a => a !== actionName);
            }

            return { stacks, fallback: true };
        }
    }

    // ── Apply AI Decision ────────────────────────────────────────────
    // Apply AI decision from CardGameDirector to game state
    // Actions come from the icon picker (selectAction), modifiers from hand
    function applyAIDecision(decision) {
        const gameState = getGameState();
        if (!gameState || !decision || !decision.stacks) return;

        const opp = gameState.opponent;
        const savedTurn = gameState.currentTurn;
        gameState.currentTurn = "opponent"; // Ensure placement goes to opponent

        for (const stack of decision.stacks) {
            // Use selectAction to place the core action (from picker, not hand)
            let placed = selectAction(gameState, stack.position, stack.coreCard);
            if (!placed) {
                console.warn("[CardGame v2] AI could not place action:", stack.coreCard, "at", stack.position);
                continue;
            }

            // Add modifier cards from hand
            const modifiers = (stack.modifiers || [])
                .map(name => opp.hand.find(c => c.name === name))
                .filter(Boolean);

            const placeCard = window.CardGame.Engine?.placeCard;
            for (const mod of modifiers) {
                if (placeCard) {
                    placeCard(gameState, stack.position, mod, true);
                }
            }

            console.log("[CardGame v2] AI placed:", stack.coreCard, "at position", stack.position,
                modifiers.length > 0 ? "with " + modifiers.length + " modifiers" : "");
        }

        gameState.currentTurn = savedTurn;
    }

    // ── Simple AI Card Placement ─────────────────────────────────────
    // Now with optional LLM integration via CardGameDirector
    async function aiPlaceCards() {
        const gameState = getGameState();
        if (!gameState) return;

        let opp = gameState.opponent;
        let positions = gameState.initiative.opponentPositions;

        console.log("[CardGame v2] AI placing cards. Hand:", opp.hand.length, "AP:", opp.ap - opp.apUsed);

        // Set LLM busy indicator
        gameState.llmBusy = "AI is thinking...";
        m.redraw();

        // Try LLM-based placement if director is available
        if (gameDirector && gameDirector.initialized) {
            try {
                const decision = await gameDirector.requestPlacement(gameState);
                if (decision && decision.stacks && !decision.fallback) {
                    applyAIDecision(decision);
                    gameState.llmBusy = null;
                    checkPlacementComplete();
                    m.redraw();
                    return;
                }
            } catch (err) {
                console.warn("[CardGame v2] Director placement failed, using fallback:", err);
            }
        }

        // Fallback: Select actions from available class actions (not from hand)
        const ACTION_DEFS = getConstants()?.ACTION_DEFINITIONS || {};
        let availableActions = getActionsForActor(opp).slice();
        let modifierCards = opp.hand.filter(c => c.type === "skill" || c.type === "item" || c.type === "magic");

        // Ensure opponent turn for placement
        let savedTurn = gameState.currentTurn;
        gameState.currentTurn = "opponent";

        // Phase 1: Select actions from available actions via icon picker
        for (let posIdx of positions) {
            if (opp.apUsed >= opp.ap) break;
            if (availableActions.length === 0) break;

            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            if (!pos || pos.stack) continue;

            // Pick best action: Attack first, then offense/magic, then any
            let actionName = availableActions.find(a => a === "Attack") ||
                            availableActions.find(a => ACTION_DEFS[a]?.type === "Offensive") ||
                            availableActions.find(a => ACTION_DEFS[a]?.type === "Magic") ||
                            availableActions[0];

            if (!actionName) break;
            let def = ACTION_DEFS[actionName];

            // Check energy cost
            if (def?.energyCost && def.energyCost > opp.energy) {
                availableActions = availableActions.filter(a => a !== actionName);
                continue;
            }

            // Use selectAction (handles AP, energy, duplicate checks)
            let placed = selectAction(gameState, posIdx, actionName);
            if (placed) {
                availableActions = availableActions.filter(a => a !== actionName);
                console.log("[CardGame v2] AI selected action:", actionName, "at position", posIdx);
            } else {
                availableActions = availableActions.filter(a => a !== actionName);
            }
        }

        // Phase 2: Add modifier cards from hand to placed stacks (no AP cost)
        let placeCardFn = window.CardGame.Engine?.placeCard;
        for (let posIdx of positions) {
            if (modifierCards.length === 0) break;

            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            if (!pos || !pos.stack || !pos.stack.coreCard) continue;
            if (pos.owner !== "opponent") continue;

            // Add one modifier to this stack
            let mod = modifierCards.shift();
            if (mod && placeCardFn) {
                placeCardFn(gameState, posIdx, mod, true);
                console.log("[CardGame v2] AI added modifier:", mod.name, "to position", posIdx);
            }
        }

        gameState.currentTurn = savedTurn;

        // Check for empty positions — forfeit remaining AP if can't place more
        let emptyPositions = positions.filter(posIdx => {
            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            return pos && !pos.stack;
        });

        if (opp.apUsed < opp.ap && emptyPositions.length === 0) {
            console.log("[CardGame v2] AI forfeiting remaining AP - no empty positions");
            opp.apUsed = opp.ap;
        }

        // Clear LLM busy indicator
        gameState.llmBusy = null;

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
