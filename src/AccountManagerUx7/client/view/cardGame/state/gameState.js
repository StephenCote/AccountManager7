/**
 * CardGame State - Game State Creation & Phase Management
 * Manages game state lifecycle: creation, phase transitions, initiative,
 * threat combat, resolution, narration, and round management.
 *
 * Extracted from the monolithic cardGame-v2.js.
 *
 * Dependencies (loaded before this module):
 *   - CardGame.Constants   (GAME_PHASES, STATUS_EFFECTS)
 *   - CardGame.Engine      (combat: rollD20, rollInitiative, rollAttack, rollDefense,
 *                           getCombatOutcome, calculateDamage, applyDamage, resolveCombat,
 *                           getStackSkillMod)
 *   - CardGame.Engine      (effects: applyStatusEffect, tickStatusEffects,
 *                           processStatusEffectsTurnStart, parseEffect, applyParsedEffects)
 *   - CardGame.Engine      (encounters: checkNat1Threats, insertBeginningThreats)
 *   - CardGame.Engine      (actions: drawCardsForActor, ensureOffensiveCard, dealInitialStack,
 *                           checkExhausted, checkGameOver, anteCard)
 *   - CardGame.Characters  (shuffle)
 *   - CardGame.AI          (CardGameDirector, CardGameNarrator, aiPlaceCards)
 *   - CardGame.AI          (ChatManager, Voice — may not be extracted yet)
 *   - CardGame.Themes      (getActiveTheme)
 *   - CardGame.Storage     (campaignStorage)
 *   - AM7 globals: m, g_application_path, page
 *
 * Exposes: window.CardGame.GameState
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.GameState = window.CardGame.GameState || {};

    // ── Shorthand references to sibling modules ─────────────────────────
    // These resolve at call-time so load order only matters for the IIFE body.
    const C  = () => window.CardGame.Constants;
    const E  = () => window.CardGame.Engine;
    const Ch = () => window.CardGame.Characters;
    const AI = () => window.CardGame.AI;
    const Th = () => window.CardGame.Themes;
    const St = () => window.CardGame.Storage;

    // ── LLM Connectivity ────────────────────────────────────────────────
    let llmStatus = { checked: false, available: false, error: null };

    async function checkLlmConnectivity() {
        llmStatus = { checked: false, available: false, error: null };
        m.redraw();
        try {
            // Test LLM connectivity by fetching chat prompt endpoint
            let response = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/chat/prompt",
                withCredentials: true
            });
            llmStatus = { checked: true, available: true, error: null };
            console.log("[CardGame v2] LLM connectivity check passed");
        } catch (err) {
            llmStatus = { checked: true, available: false, error: err.message || "LLM service unavailable" };
            console.warn("[CardGame v2] LLM connectivity check failed:", err);
        }
        m.redraw();
        return llmStatus.available;
    }

    // ── Game State Model ────────────────────────────────────────────────
    let gameState = null;  // null when not in game
    let gameCharSelection = null;  // { characters: [], selected: null } when picking character

    // ── Initiative Animation State ──────────────────────────────────────
    let initAnimState = {
        countdown: 3,           // Countdown from 3
        rolling: false,         // Whether dice are animating
        rollComplete: false,    // Whether final roll is shown
        playerDiceFace: 1,      // Current displayed face during animation
        opponentDiceFace: 1,
        animInterval: null      // Animation interval ID
    };

    function resetInitAnimState() {
        if (initAnimState.animInterval) clearInterval(initAnimState.animInterval);
        initAnimState = {
            countdown: 3,
            rolling: false,
            rollComplete: false,
            playerDiceFace: 1,
            opponentDiceFace: 1,
            animInterval: null
        };
    }

    function startInitiativeAnimation() {
        resetInitAnimState();

        // Start countdown
        let countdownInterval = setInterval(() => {
            initAnimState.countdown--;
            m.redraw();

            if (initAnimState.countdown <= 0) {
                clearInterval(countdownInterval);
                // Start dice rolling animation
                initAnimState.rolling = true;
                initAnimState.animInterval = setInterval(() => {
                    initAnimState.playerDiceFace = Math.floor(Math.random() * 20) + 1;
                    initAnimState.opponentDiceFace = Math.floor(Math.random() * 20) + 1;
                    m.redraw();
                }, 80);

                // After 2 seconds of rolling, show final result
                setTimeout(() => {
                    clearInterval(initAnimState.animInterval);
                    initAnimState.rolling = false;
                    initAnimState.rollComplete = true;
                    runInitiativePhase();  // Actually compute the roll
                    m.redraw();
                }, 2000);
            }
        }, 1000);
    }

    // ── Game State Creation ─────────────────────────────────────────────
    function createGameState(deck, selectedCharacter) {
        const shuffle = Ch().shuffle;
        const dealInitialStack = E().dealInitialStack;
        const ensureOffensiveCard = E().ensureOffensiveCard;
        const GAME_PHASES = C().GAME_PHASES;

        // Use selected character or find first one
        let cards = deck.cards || [];
        let allCharacters = cards.filter(c => c.type === "character");
        let playerCharCard = selectedCharacter || allCharacters[0];

        if (!playerCharCard) {
            console.error("[CardGame v2] No character card found in deck");
            return null;
        }

        // Pick an opponent from remaining characters, or create a generic one
        let otherChars = allCharacters.filter(c => c !== playerCharCard);
        let opponentCharCard = otherChars.length > 0
            ? otherChars[Math.floor(Math.random() * otherChars.length)]
            : null;

        // Calculate initial AP from END: floor(END/5) + 1, minimum 2 AP
        // Check for both uppercase and lowercase stat names, with proper numeric validation
        let playerStats = playerCharCard.stats || {};
        let rawPlayerEnd = playerStats.END ?? playerStats.end ?? playerStats.endurance;
        let playerEnd = (typeof rawPlayerEnd === "number" && rawPlayerEnd > 0) ? rawPlayerEnd : 12;  // Default 12 = 3 AP
        let playerAp = Math.max(2, Math.floor(playerEnd / 5) + 1);  // Minimum 2 AP for playability

        // Calculate initial energy from MAG
        let rawPlayerMag = playerStats.MAG ?? playerStats.mag ?? playerStats.magic;
        let playerMag = (typeof rawPlayerMag === "number" && rawPlayerMag > 0) ? rawPlayerMag : 12;

        console.log("[CardGame v2] Player character:", playerCharCard.name, "raw stats:", JSON.stringify(playerStats));
        console.log("[CardGame v2] Player END:", playerEnd, "(raw:", rawPlayerEnd, ") → AP:", playerAp, "| MAG:", playerMag);

        // Opponent stats - use actual opponent character if available
        let opponentStats = opponentCharCard
            ? Object.assign({}, opponentCharCard.stats || {})
            : Object.assign({}, playerStats);
        let rawOppEnd = opponentStats.END ?? opponentStats.end ?? opponentStats.endurance;
        let opponentEnd = (typeof rawOppEnd === "number" && rawOppEnd > 0) ? rawOppEnd : 12;
        let opponentAp = Math.max(2, Math.floor(opponentEnd / 5) + 1);  // Minimum 2 AP
        let rawOppMag = opponentStats.MAG ?? opponentStats.mag ?? opponentStats.magic;
        let opponentMag = (typeof rawOppMag === "number" && rawOppMag > 0) ? rawOppMag : 12;

        console.log("[CardGame v2] Opponent END:", opponentEnd, "(raw:", rawOppEnd, ") → AP:", opponentAp, "| MAG:", opponentMag);

        // Separate cards by type (cards already defined above)
        let actionCards = cards.filter(c => c.type === "action");
        let talkCards = cards.filter(c => c.type === "talk");
        let itemCards = shuffle(cards.filter(c => c.type === "item"));
        let apparelCards = shuffle(cards.filter(c => c.type === "apparel"));
        let skillCards = cards.filter(c => c.type === "skill");
        let magicCards = cards.filter(c => c.type === "magic");
        let encounterCards = cards.filter(c => c.type === "encounter");

        // Split items and apparel for player vs opponent (so they get different equipment)
        let itemMid = Math.ceil(itemCards.length / 2);
        let apparelMid = Math.ceil(apparelCards.length / 2);
        let playerItems = itemCards.slice(0, itemMid);
        let opponentItems = itemCards.slice(itemMid);
        let playerApparel = apparelCards.slice(0, apparelMid);
        let opponentApparel = apparelCards.slice(apparelMid);

        // Create playable card pool (actions, skills, magic, talk)
        let playableCards = [...actionCards, ...talkCards, ...skillCards, ...magicCards];

        console.log("[CardGame v2] Card counts - action:", actionCards.length,
            "talk:", talkCards.length, "skill:", skillCards.length,
            "magic:", magicCards.length, "playable total:", playableCards.length);

        // If deck has no playable cards, create basic cards
        if (playableCards.length === 0) {
            console.log("[CardGame v2] No playable cards in deck, using default starter cards");
            playableCards = [
                { type: "action", name: "Attack", actionType: "Offensive", energyCost: 0 },
                { type: "action", name: "Attack", actionType: "Offensive", energyCost: 0 },
                { type: "action", name: "Guard", actionType: "Defensive", energyCost: 0 },
                { type: "action", name: "Guard", actionType: "Defensive", energyCost: 0 },
                { type: "action", name: "Rest", actionType: "Recovery", energyCost: 0 },
                { type: "action", name: "Rest", actionType: "Recovery", energyCost: 0 },
                { type: "action", name: "Feint", actionType: "Utility", energyCost: 0 },
                { type: "talk", name: "Taunt", speechType: "Provoke", energyCost: 0 },
                { type: "talk", name: "Persuade", speechType: "Charm", energyCost: 0 }
            ];
        }

        // Create draw piles for both sides (shuffle and split)
        let shuffledPool = shuffle([...playableCards, ...playableCards]); // Double the pool
        let midPoint = Math.floor(shuffledPool.length / 2);
        let playerDrawPile = shuffledPool.slice(0, midPoint);
        let opponentDrawPile = shuffledPool.slice(midPoint);

        // Deal initial hands (5 cards each)
        let initialHandSize = 5;
        let playerHand = playerDrawPile.splice(0, initialHandSize);
        let opponentHand = opponentDrawPile.splice(0, initialHandSize);

        console.log("[CardGame v2] Initial hands dealt - player:", playerHand.length, "opponent:", opponentHand.length);
        console.log("[CardGame v2] Player hand:", playerHand.map(c => c.name));
        console.log("[CardGame v2] Draw piles - player:", playerDrawPile.length, "opponent:", opponentDrawPile.length);

        let state = {
            // Meta
            deckName: deck.deckName,
            themeId: deck.themeId,
            startedAt: Date.now(),

            // Round tracking
            round: 1,
            phase: GAME_PHASES.INITIATIVE,

            // Initiative
            initiative: {
                playerRoll: null,      // { raw, modifier, total }
                opponentRoll: null,
                winner: null,          // "player" | "opponent"
                playerPositions: [],   // [1,3,5,...] or [2,4,6,...]
                opponentPositions: []
            },

            // Action Bar
            actionBar: {
                totalPositions: 0,
                positions: [],  // [{ owner, stack, resolved }]
                resolveIndex: -1  // current resolution position (-1 = not started)
            },

            // Player state
            player: {
                character: playerCharCard,
                hp: 20,
                maxHp: 20,
                energy: playerMag,
                maxEnergy: playerMag,
                morale: 20,
                maxMorale: 20,
                ap: playerAp,
                maxAp: playerAp,
                apUsed: 0,
                hand: playerHand,
                drawPile: playerDrawPile,
                discardPile: [],
                // Card stack: modifier cards placed on character (apparel, items, buffs)
                cardStack: dealInitialStack(playerApparel, playerItems),
                roundPoints: 0,
                statusEffects: [],  // Active status effects (stunned, poisoned, etc.)
                typesPlayedThisRound: {}  // Track action types played this round for hoarding prevention
            },

            // Opponent state (AI) - use actual character from deck if available
            opponent: {
                name: opponentCharCard ? (opponentCharCard.name || "Opponent") : "Challenger",
                character: opponentCharCard || {
                    type: "character",
                    name: "Challenger",
                    race: "UNKNOWN",
                    alignment: "NEUTRAL",
                    level: 1,
                    stats: opponentStats,
                    needs: { hp: 20, energy: opponentMag, morale: 20 },
                    equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
                    activeSkills: [null, null, null, null],
                    portraitUrl: null
                },
                hp: 20,
                maxHp: 20,
                energy: opponentMag,
                maxEnergy: opponentMag,
                morale: 20,
                maxMorale: 20,
                ap: opponentAp,
                maxAp: opponentAp,
                apUsed: 0,
                hand: opponentHand,
                drawPile: opponentDrawPile,
                discardPile: [],
                // Card stack: modifier cards placed on character
                cardStack: dealInitialStack(opponentApparel, opponentItems),
                roundPoints: 0,
                statusEffects: [],  // Active status effects (stunned, poisoned, etc.)
                typesPlayedThisRound: {}  // Track action types played this round for hoarding prevention
            },

            // Encounter deck (shuffled)
            encounterDeck: shuffle([...encounterCards]),

            // Round pot
            pot: [],

            // Timing
            turnTimer: null,
            isPaused: false,

            // Turn tracking for draw/placement
            currentTurn: null,  // "player" | "opponent"
            turnActions: [],    // log of actions this placement phase

            // Beginning threats (from Nat 1 on initiative)
            beginningThreats: [],

            // Threat response state
            threatResponse: {
                active: false,
                type: null,        // "beginning" | "end"
                threats: [],       // Array of active threats
                responder: null,   // "player" | "opponent" - who gets to respond
                bonusAP: 0,        // AP for threat response (limited)
                defenseStack: null,// Stack placed for defense
                resolved: false
            },

            // Chat state (Phase 8)
            chat: {
                active: false,
                unlocked: false,   // True when Talk card is active (Silence Rule)
                messages: [],      // [{ role: "player"|"npc", text, timestamp }]
                npcName: null,
                inputText: "",
                pending: false,    // Waiting for LLM response
                talkCard: null,    // The Talk card that opened this chat
                talkPosition: null // The action bar position for resolution
            }
        };

        // Ensure each player has at least one Attack card in starting hand
        ensureOffensiveCard(state.player, "Player");
        ensureOffensiveCard(state.opponent, "Opponent");

        return state;
    }

    // ── LLM Component State ─────────────────────────────────────────────
    let gameDirector = null;
    let gameNarrator = null;
    let gameChatManager = null;
    let gameVoice = null;
    let gameAnnouncerVoice = null;  // Separate voice instance for announcer

    // Initialize LLM components (Director and Narrator) for a game
    async function initializeLLMComponents(state, deck) {
        const activeTheme = Th()?.getActiveTheme?.() || { themeId: "high-fantasy" };
        const themeId = deck?.themeId || activeTheme?.themeId || "high-fantasy";
        const opponentChar = state?.opponent?.character;
        const gc = deck?.gameConfig || {};
        const narrationEnabled = gc.narrationEnabled !== false;       // Default: enabled
        const opponentVoiceEnabled = gc.opponentVoiceEnabled === true; // Default: disabled
        const opponentVoiceProfileId = gc.opponentVoiceProfileId || null;
        const announcerEnabled = gc.announcerEnabled === true;         // Default: disabled
        const announcerProfile = gc.announcerProfile || "arena-announcer";
        const announcerVoiceEnabled = gc.announcerVoiceEnabled === true;
        const announcerVoiceProfileId = gc.announcerVoiceProfileId || null;

        console.log("[CardGame v2] Game config: llm=" + narrationEnabled
            + " oppVoice=" + opponentVoiceEnabled + " (profile=" + (opponentVoiceProfileId || "default") + ")"
            + " announcer=" + announcerEnabled + " (style=" + announcerProfile + " voice=" + announcerVoiceEnabled + ")");

        // Resolve AI classes — may come from extracted modules or monolith fallback
        const CardGameDirector = AI()?.CardGameDirector;
        const CardGameNarrator = AI()?.CardGameNarrator;
        const CardGameChatManager = AI()?.CardGameChatManager;
        const CardGameVoice = AI()?.CardGameVoice;

        // Initialize AI Director (optional - falls back to FIFO if unavailable)
        if (CardGameDirector) {
            try {
                gameDirector = new CardGameDirector();
                const directorOk = await gameDirector.initialize(opponentChar, themeId);
                if (!directorOk) {
                    console.log("[CardGame v2] LLM Director unavailable, using fallback AI");
                    gameDirector = null;
                }
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize Director:", err);
                gameDirector = null;
            }
        } else {
            gameDirector = null;
        }

        // Initialize Announcer narrator (optional -- separate from opponent)
        if (announcerEnabled && narrationEnabled && CardGameNarrator) {
            try {
                gameNarrator = new CardGameNarrator();
                const narratorOk = await gameNarrator.initialize(announcerProfile, themeId);
                if (!narratorOk) {
                    console.log("[CardGame v2] Announcer LLM unavailable, using fallback narration");
                    gameNarrator = null;
                }
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize Announcer:", err);
                gameNarrator = null;
            }
        } else if (!announcerEnabled) {
            console.log("[CardGame v2] Announcer disabled by deck config");
            gameNarrator = null;
        } else {
            console.log("[CardGame v2] LLM disabled — announcer uses fallback text");
            gameNarrator = null;
        }

        // Always trigger game start narration (uses fallback if announcer unavailable)
        narrateGameStart();

        // Initialize Chat Manager for Talk cards / opponent dialogue (requires LLM enabled)
        if (narrationEnabled && CardGameChatManager) {
            try {
                gameChatManager = new CardGameChatManager();
                const chatOk = await gameChatManager.initialize(opponentChar, themeId);
                if (!chatOk) {
                    console.log("[CardGame v2] LLM Chat Manager unavailable, Talk cards use fallback. Error:", gameChatManager?.lastError);
                    gameChatManager = null;
                } else {
                    console.log("[CardGame v2] Chat Manager initialized successfully");
                }
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize Chat Manager:", err);
                gameChatManager = null;
            }
        } else {
            gameChatManager = null;
        }

        // Initialize Opponent Voice (the opponent character's TTS voice)
        if (CardGameVoice) {
            try {
                gameVoice = new CardGameVoice();
                await gameVoice.initialize({
                    subtitlesOnly: !opponentVoiceEnabled,
                    voiceProfileId: opponentVoiceProfileId,
                    volume: 1.0
                });
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize opponent voice:", err);
                gameVoice = null;
            }
        } else {
            gameVoice = null;
        }

        // Initialize Announcer Voice (separate TTS for commentary)
        if (announcerEnabled && announcerVoiceEnabled && CardGameVoice) {
            try {
                gameAnnouncerVoice = new CardGameVoice();
                await gameAnnouncerVoice.initialize({
                    subtitlesOnly: false,
                    voiceProfileId: announcerVoiceProfileId,
                    volume: 1.0
                });
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize announcer voice:", err);
                gameAnnouncerVoice = null;
            }
        } else {
            gameAnnouncerVoice = null;
        }
    }

    // ── Unified Narration System ────────────────────────────────────────
    // Centralized narration with LLM and fallback support

    // Build emotion context from Poker Face (mood ring) if available
    function buildEmotionContext() {
        const emotion = (page?.components?.moodRing?.enabled?.()) ? page.components.moodRing.emotion() : null;
        if (!emotion || emotion === "neutral") return null;

        const emotionDescriptions = {
            happy: "appears pleased",
            sad: "looks dejected",
            angry: "seems frustrated",
            fear: "appears nervous",
            surprise: "looks startled",
            disgust: "seems unimpressed"
        };

        return {
            emotion,
            description: emotionDescriptions[emotion] || null
        };
    }

    async function triggerNarration(trigger, extraContext = {}) {
        if (!gameState) return;
        console.log("[CardGame v2] triggerNarration called:", trigger);

        const playerName = gameState.player?.character?.name || "Player";
        const opponentName = gameState.opponent?.character?.name || "Opponent";
        const baseContext = { playerName, opponentName };

        // Build trigger-specific context
        let context, fallbackText;
        switch (trigger) {
            case "game_start":
                context = { ...baseContext, round: 1 };
                fallbackText = `The arena awaits! ${playerName} faces ${opponentName} in a battle of wits and steel. Let the cards decide your fate!`;
                break;

            case "game_end": {
                const isVictory = extraContext.winner === "player";
                const winnerName = isVictory ? playerName : opponentName;
                const loserName = isVictory ? opponentName : playerName;
                context = { winner: winnerName, loser: loserName, isPlayerVictory: isVictory, rounds: gameState.round };
                fallbackText = isVictory
                    ? `Victory! ${playerName} stands triumphant over ${opponentName} after ${gameState.round} rounds of fierce combat!`
                    : `Defeat... ${opponentName} has bested ${playerName}. The arena falls silent after ${gameState.round} rounds.`;
                break;
            }

            case "round_start":
                context = {
                    ...baseContext,
                    roundNumber: gameState.round,
                    playerHp: gameState.player.hp,
                    opponentHp: gameState.opponent.hp,
                    playerEnergy: gameState.player.energy,
                    opponentEnergy: gameState.opponent.energy
                };
                if (gameState.round <= 1) return; // Round 1 uses game_start
                const tension = (gameState.player.hp < 10 || gameState.opponent.hp < 10)
                    ? " The tension mounts as both combatants show signs of wear." : "";
                fallbackText = `Round ${gameState.round} begins!${tension}`;
                break;

            case "round_end": {
                const roundWinner = extraContext.roundWinner || "tie";
                const winName = roundWinner === "player" ? playerName : roundWinner === "opponent" ? opponentName : "Neither";
                context = {
                    ...baseContext,
                    roundNumber: gameState.round,
                    roundWinner: winName,
                    playerHp: gameState.player.hp,
                    opponentHp: gameState.opponent.hp
                };
                fallbackText = roundWinner === "tie"
                    ? `Round ${gameState.round} ends in a stalemate!`
                    : `${winName} takes Round ${gameState.round}!`;
                break;
            }

            case "resolution": {
                const attackerName = extraContext.attackerName || (extraContext.isPlayerAttack ? playerName : opponentName);
                const defenderName = extraContext.defenderName || (extraContext.isPlayerAttack ? opponentName : playerName);
                const outcome = extraContext.outcome || "Hit";
                const damage = extraContext.damage || 0;
                context = {
                    ...baseContext,
                    attackerName, defenderName, outcome, damage,
                    roundNumber: gameState.round
                };
                if (outcome === "CRIT") {
                    fallbackText = `Critical hit! ${attackerName} devastates ${defenderName} for ${damage} damage!`;
                } else if (outcome === "MISS" || damage <= 0) {
                    fallbackText = `${defenderName} deflects ${attackerName}'s assault!`;
                } else {
                    fallbackText = `${attackerName} strikes ${defenderName} for ${damage} damage!`;
                }
                break;
            }

            default:
                context = { ...baseContext, ...extraContext };
                fallbackText = null;
        }

        // Add Poker Face emotion context if available
        const emotionCtx = buildEmotionContext();
        if (emotionCtx) {
            context.playerEmotion = emotionCtx.emotion;
            context.playerEmotionDesc = emotionCtx.description;
        }

        // Try LLM narration
        let narrationText = null;
        if (gameNarrator?.initialized) {
            try {
                const narration = await gameNarrator.narrate(trigger, context);
                if (narration?.text) {
                    narrationText = narration.text;
                }
            } catch (e) {
                console.warn(`[CardGame v2] ${trigger} narration failed:`, e);
            }
        }

        // Use fallback if LLM failed
        const finalText = narrationText || fallbackText;
        if (!finalText) return;

        // Show subtitle
        showNarrationSubtitle(finalText);

        // Speak with announcer voice (narration/commentary uses announcer, not opponent)
        if (gameAnnouncerVoice?.enabled && !gameAnnouncerVoice.subtitlesOnly) {
            gameAnnouncerVoice.speak(finalText);
        }
    }

    // Convenience wrappers for backward compatibility
    function narrateGameStart() { return triggerNarration("game_start"); }
    function narrateGameEnd(winner) { return triggerNarration("game_end", { winner }); }
    function narrateRoundStart() { return triggerNarration("round_start"); }
    function narrateRoundEnd(roundWinner) { return triggerNarration("round_end", { roundWinner }); }

    // Show narrator text as a subtitle overlay
    function showNarrationSubtitle(text) {
        if (!text) return;
        console.log("[CardGame v2] showNarrationSubtitle:", text.substring(0, 80) + (text.length > 80 ? "..." : ""));
        // Store in game state for UI to display
        if (gameState) {
            gameState.narrationText = text;
            gameState.narrationTime = Date.now();
            m.redraw();

            // Auto-hide after 8 seconds (extended for readability)
            setTimeout(() => {
                if (gameState && gameState.narrationTime && Date.now() - gameState.narrationTime >= 7900) {
                    gameState.narrationText = null;
                    m.redraw();
                }
            }, 8000);
        }
    }

    // ── Initiative Phase ────────────────────────────────────────────────
    function runInitiativePhase() {
        if (!gameState) return;
        const GAME_PHASES = C().GAME_PHASES;
        const rollInitiative = E().rollInitiative;
        const checkNat1Threats = E().checkNat1Threats;
        const insertBeginningThreats = E().insertBeginningThreats;

        let playerStats = gameState.player.character.stats || {};
        let opponentStats = gameState.opponent.character.stats || {};

        gameState.initiative.playerRoll = rollInitiative(playerStats);
        gameState.initiative.opponentRoll = rollInitiative(opponentStats);

        // Determine winner (re-roll ties handled here simply by random)
        let pTotal = gameState.initiative.playerRoll.total;
        let oTotal = gameState.initiative.opponentRoll.total;

        if (pTotal > oTotal) {
            gameState.initiative.winner = "player";
        } else if (oTotal > pTotal) {
            gameState.initiative.winner = "opponent";
        } else {
            // Tie-breaker: random
            gameState.initiative.winner = Math.random() < 0.5 ? "player" : "opponent";
        }

        // Calculate total positions - each player gets exactly their AP number of slots
        let playerAp = gameState.player.ap;
        let opponentAp = gameState.opponent.ap;
        let totalPositions = playerAp + opponentAp;

        // Assign positions: interleave winner and loser, but each gets exactly their AP count
        // Winner acts at positions 1, 3, 5... up to their AP count
        // Loser acts at positions 2, 4, 6... up to their AP count
        let winnerAp = gameState.initiative.winner === "player" ? playerAp : opponentAp;
        let loserAp = gameState.initiative.winner === "player" ? opponentAp : playerAp;

        let winnerPositions = [];
        let loserPositions = [];
        let pos = 1;
        let wCount = 0, lCount = 0;

        // Interleave: winner first, then loser, alternating until both filled
        while (wCount < winnerAp || lCount < loserAp) {
            if (wCount < winnerAp) {
                winnerPositions.push(pos++);
                wCount++;
            }
            if (lCount < loserAp) {
                loserPositions.push(pos++);
                lCount++;
            }
        }

        if (gameState.initiative.winner === "player") {
            gameState.initiative.playerPositions = winnerPositions;
            gameState.initiative.opponentPositions = loserPositions;
        } else {
            gameState.initiative.playerPositions = loserPositions;
            gameState.initiative.opponentPositions = winnerPositions;
        }

        // Build action bar positions
        gameState.actionBar.totalPositions = totalPositions;
        gameState.actionBar.positions = [];
        for (let i = 1; i <= totalPositions; i++) {
            let owner = gameState.initiative.playerPositions.includes(i) ? "player" : "opponent";
            gameState.actionBar.positions.push({
                index: i,
                owner: owner,
                stack: null,  // { coreCard, modifiers: [] }
                resolved: false
            });
        }

        // Check for Nat 1 (critical initiative failure) - triggers beginning threats
        let beginningThreats = checkNat1Threats(gameState);
        if (beginningThreats.length > 0) {
            insertBeginningThreats(gameState, beginningThreats);
        }

        console.log("[CardGame v2] Initiative:", gameState.initiative);
        m.redraw();
    }

    // ── Phase Transitions ───────────────────────────────────────────────
    function advancePhase() {
        if (!gameState) return;
        const GAME_PHASES = C().GAME_PHASES;

        // Handle phase-specific transitions
        if (gameState.phase === GAME_PHASES.INITIATIVE) {
            // Check if there are beginning threats that need response
            if (gameState.beginningThreats && gameState.beginningThreats.length > 0) {
                enterThreatResponsePhase("beginning");
            } else {
                enterDrawPlacementPhase();
            }
        } else if (gameState.phase === GAME_PHASES.THREAT_RESPONSE) {
            // Threat response complete - resolve the threat combat, then continue to placement
            resolveThreatCombat();
        } else if (gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
            gameState.phase = GAME_PHASES.RESOLUTION;
            gameState.actionBar.resolveIndex = 0;
            console.log("[CardGame v2] Phase advanced to:", gameState.phase);
            // Auto-start resolution
            setTimeout(() => {
                if (gameState && gameState.phase === GAME_PHASES.RESOLUTION) {
                    advanceResolution();
                }
            }, 500);
        } else if (gameState.phase === GAME_PHASES.RESOLUTION) {
            gameState.phase = GAME_PHASES.CLEANUP;
            console.log("[CardGame v2] Phase advanced to:", gameState.phase);
        } else if (gameState.phase === GAME_PHASES.CLEANUP) {
            // Check for end-of-round threat before starting next round
            if (gameState.endThreatResult && gameState.endThreatResult.threat && !gameState.endThreatResult.responded) {
                enterEndThreatPhase();
            } else {
                startNextRound();
            }
        } else if (gameState.phase === GAME_PHASES.END_THREAT) {
            // End threat response complete - resolve it, then start next round
            resolveEndThreatCombat();
        }
        m.redraw();
    }

    function enterDrawPlacementPhase() {
        const GAME_PHASES = C().GAME_PHASES;
        const aiPlaceCards = AI()?.aiPlaceCards;

        gameState.phase = GAME_PHASES.DRAW_PLACEMENT;
        gameState.currentTurn = gameState.initiative.winner;
        console.log("[CardGame v2] Phase advanced to:", gameState.phase, "- Current turn:", gameState.currentTurn);

        // If AI goes first, trigger AI placement
        if (gameState.currentTurn === "opponent") {
            setTimeout(() => {
                if (gameState && gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                    console.log("[CardGame v2] Triggering AI placement (goes first)");
                    if (aiPlaceCards) aiPlaceCards();
                    m.redraw();
                }
            }, 500);
        }
    }

    function enterThreatResponsePhase(type) {
        const GAME_PHASES = C().GAME_PHASES;

        console.log("[CardGame v2] Entering threat response phase for:", type, "threats");
        gameState.phase = GAME_PHASES.THREAT_RESPONSE;

        // Determine who responds - the fumbler(s) who triggered the threat
        let responders = [];
        gameState.beginningThreats.forEach(threat => {
            if (!responders.includes(threat.target)) {
                responders.push(threat.target);
            }
        });

        // Set up threat response state
        gameState.threatResponse = {
            active: true,
            type: type,
            threats: gameState.beginningThreats.slice(),
            responder: responders[0] || "player",  // First responder
            responderQueue: responders,
            bonusAP: 2,  // Limited AP for threat defense
            defenseStack: [],
            resolved: false
        };

        // Give responder bonus AP for defense
        let responder = gameState.threatResponse.responder === "player" ? gameState.player : gameState.opponent;
        responder.threatResponseAP = gameState.threatResponse.bonusAP;

        console.log("[CardGame v2] Threat response:", gameState.threatResponse.responder, "gets", gameState.threatResponse.bonusAP, "AP to defend");
    }

    function enterEndThreatPhase() {
        const GAME_PHASES = C().GAME_PHASES;

        console.log("[CardGame v2] Entering end threat phase");
        gameState.phase = GAME_PHASES.END_THREAT;

        // Responder is the threat's target (the one being attacked)
        let threat = gameState.endThreatResult.threat;
        let responder = threat.target;

        gameState.threatResponse = {
            active: true,
            type: "end",
            threats: [threat],
            responder: responder,
            bonusAP: 2,  // Bonus AP for end threat response
            defenseStack: [],
            resolved: false
        };

        // Give responder (defender) bonus AP
        let actor = responder === "player" ? gameState.player : gameState.opponent;
        actor.threatResponseAP = gameState.threatResponse.bonusAP;

        console.log("[CardGame v2] End threat response:", responder, "must defend against", threat.name);
    }

    // ── Threat Combat ───────────────────────────────────────────────────
    function resolveThreatCombat() {
        const rollAttack = E().rollAttack;
        const rollDefense = E().rollDefense;
        const getCombatOutcome = E().getCombatOutcome;
        const calculateDamage = E().calculateDamage;
        const applyDamage = E().applyDamage;
        const checkGameOver = E().checkGameOver;

        console.log("[CardGame v2] resolveThreatCombat called, phase:", gameState?.phase,
            "threatResponse:", !!gameState?.threatResponse,
            "threats:", gameState?.threatResponse?.threats?.length || 0);

        if (!gameState || !gameState.threatResponse) {
            console.warn("[CardGame v2] resolveThreatCombat: No threat response state");
            return;
        }

        let threats = gameState.threatResponse.threats;
        let defenseStack = gameState.threatResponse.defenseStack || [];

        if (!threats || threats.length === 0) {
            console.warn("[CardGame v2] resolveThreatCombat: No threats to resolve");
            // Still need to advance phase
            gameState.threatResponse.resolved = true;
            gameState.threatResponse.active = false;
            gameState.player.threatResponseAP = 0;
            gameState.opponent.threatResponseAP = 0;
            enterDrawPlacementPhase();
            return;
        }

        // Resolve each threat as combat
        threats.forEach(threat => {
            let targetActor = threat.target === "player" ? gameState.player : gameState.opponent;

            // Build threat attacker object
            let threatAttacker = {
                name: threat.name,
                character: {
                    name: threat.name,
                    stats: { STR: threat.atk, END: threat.def }
                },
                hp: threat.hp,
                maxHp: threat.maxHp,
                cardStack: [{ type: "item", subtype: "weapon", atk: threat.atk }]
            };

            // Check if defender has a defense stack
            let defenderStack = defenseStack.filter(card => card.target === threat.target);
            targetActor.cardStack = defenderStack;

            // Roll combat
            let attackRoll = rollAttack(threatAttacker, []);
            let defenseRoll = rollDefense(targetActor);
            let outcome = getCombatOutcome(attackRoll, defenseRoll);

            console.log("[CardGame v2] Threat combat:", threat.name, "vs", threat.target, "- Outcome:", outcome.label);

            if (outcome.damageMultiplier > 0) {
                // Threat hit
                let damage = calculateDamage(threatAttacker, outcome);
                let damageResult = applyDamage(targetActor, damage.finalDamage);
                console.log("[CardGame v2] Threat dealt", damage.finalDamage, "damage to", threat.target);

                // Store result for UI
                if (!gameState.threatResults) gameState.threatResults = [];
                gameState.threatResults.push({
                    threatName: threat.name,
                    target: threat.target,
                    outcome: "hit",
                    damage: damage.finalDamage,
                    attackRoll,
                    defenseRoll,
                    combatOutcome: outcome
                });
            } else {
                // Defender blocked/parried
                console.log("[CardGame v2]", threat.target, "defended against", threat.name);

                // Grant loot on successful defense
                let lootCard = {
                    type: "item",
                    subtype: "consumable",
                    name: "Threat Loot (" + threat.lootRarity + ")",
                    rarity: threat.lootRarity,
                    effect: threat.lootRarity === "RARE" ? "Restore 5 HP" : "Restore 3 HP",
                    flavor: "Spoils from defeating " + threat.name
                };
                targetActor.hand.push(lootCard);

                if (!gameState.threatResults) gameState.threatResults = [];
                gameState.threatResults.push({
                    threatName: threat.name,
                    target: threat.target,
                    outcome: "defeated",
                    loot: lootCard,
                    attackRoll,
                    defenseRoll,
                    combatOutcome: outcome
                });
            }
        });

        // Clear threat response and continue to placement
        gameState.threatResponse.resolved = true;
        gameState.threatResponse.active = false;

        // Clear threat response AP
        gameState.player.threatResponseAP = 0;
        gameState.opponent.threatResponseAP = 0;

        // Check for game over after threat combat
        let winner = checkGameOver(gameState);
        if (winner) {
            console.log("[CardGame v2] Game over from threat combat - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        // Continue to draw/placement phase
        enterDrawPlacementPhase();
    }

    function resolveEndThreatCombat() {
        const rollAttack = E().rollAttack;
        const rollDefense = E().rollDefense;
        const getCombatOutcome = E().getCombatOutcome;
        const calculateDamage = E().calculateDamage;
        const applyDamage = E().applyDamage;
        const checkGameOver = E().checkGameOver;
        const GAME_PHASES = C().GAME_PHASES;

        if (!gameState || !gameState.endThreatResult || !gameState.endThreatResult.threat) {
            startNextRound();
            return;
        }

        let threat = gameState.endThreatResult.threat;
        let responder = gameState.threatResponse?.responder || gameState.roundWinner;
        let responderActor = responder === "player" ? gameState.player : gameState.opponent;
        let defenseStack = gameState.threatResponse?.defenseStack || [];

        // Build threat attacker object
        let threatAttacker = {
            name: threat.name,
            character: {
                name: threat.name,
                stats: { STR: threat.atk, END: threat.def }
            },
            hp: threat.hp,
            maxHp: threat.maxHp,
            cardStack: [{ type: "item", subtype: "weapon", atk: threat.atk }]
        };

        // Assign defense stack
        responderActor.cardStack = defenseStack;

        // Roll combat
        let attackRoll = rollAttack(threatAttacker, []);
        let defenseRoll = rollDefense(responderActor);
        let outcome = getCombatOutcome(attackRoll, defenseRoll);

        console.log("[CardGame v2] End threat combat:", threat.name, "vs", responder, "- Outcome:", outcome.label);

        if (outcome.damageMultiplier > 0) {
            // Threat hit
            let damage = calculateDamage(threatAttacker, outcome);
            applyDamage(responderActor, damage.finalDamage);
            gameState.endThreatResult.damageDealt = damage.finalDamage;
            gameState.endThreatResult.combatResult = { attackRoll, defenseRoll, outcome, damage };
            console.log("[CardGame v2] End threat dealt", damage.finalDamage, "damage to", responder);
        } else {
            // Defender won
            gameState.endThreatResult.damageDealt = 0;
            gameState.endThreatResult.combatResult = { attackRoll, defenseRoll, outcome, defended: true };

            // Grant loot
            let lootCard = {
                type: "item",
                subtype: "consumable",
                name: "End Threat Loot (" + threat.lootRarity + ")",
                rarity: threat.lootRarity,
                effect: "Restore 4 HP",
                flavor: "Spoils from defeating " + threat.name
            };
            responderActor.hand.push(lootCard);
            gameState.endThreatResult.loot = lootCard;
            console.log("[CardGame v2]", responder, "defeated end threat, earned loot");
        }

        gameState.endThreatResult.responded = true;

        // Clear threat response state
        if (gameState.threatResponse) {
            gameState.threatResponse.active = false;
            gameState.threatResponse.resolved = true;
        }

        // Clear threat response AP
        gameState.player.threatResponseAP = 0;
        gameState.opponent.threatResponseAP = 0;

        // Check for game over after end threat combat
        let winner = checkGameOver(gameState);
        if (winner) {
            console.log("[CardGame v2] Game over from end threat - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        // Return to cleanup phase to show the result before next round
        gameState.phase = GAME_PHASES.CLEANUP;
        console.log("[CardGame v2] End threat resolved, returning to cleanup to show result");
        m.redraw();
    }

    // Place a card in threat response defense stack
    function placeThreatDefenseCard(card) {
        if (!gameState || !gameState.threatResponse || !gameState.threatResponse.active) return false;

        let responder = gameState.threatResponse.responder;
        let actor = responder === "player" ? gameState.player : gameState.opponent;

        // Check AP
        let apCost = 1;
        if ((actor.threatResponseAP || 0) < apCost) {
            console.log("[CardGame v2] Not enough threat response AP");
            return false;
        }

        // Deduct AP and add to defense stack
        actor.threatResponseAP -= apCost;
        gameState.threatResponse.defenseStack.push({
            ...card,
            target: gameState.threatResponse.threats[0]?.target || responder
        });

        // Remove from hand
        let handIndex = actor.hand.indexOf(card);
        if (handIndex >= 0) {
            actor.hand.splice(handIndex, 1);
        }

        console.log("[CardGame v2] Placed threat defense card:", card.name);
        m.redraw();
        return true;
    }

    // Skip threat response and let combat resolve
    function skipThreatResponse() {
        if (!gameState || !gameState.threatResponse) return;
        const GAME_PHASES = C().GAME_PHASES;

        console.log("[CardGame v2] Skipping threat response");

        if (gameState.phase === GAME_PHASES.THREAT_RESPONSE) {
            resolveThreatCombat();
        } else if (gameState.phase === GAME_PHASES.END_THREAT) {
            resolveEndThreatCombat();
        }
    }

    // ── Round Management ────────────────────────────────────────────────
    function startNextRound() {
        if (!gameState) return;
        const GAME_PHASES = C().GAME_PHASES;
        const checkGameOver = E().checkGameOver;
        const tickStatusEffects = E().tickStatusEffects;
        const processStatusEffectsTurnStart = E().processStatusEffectsTurnStart;
        const drawCardsForActor = E().drawCardsForActor;
        const ensureOffensiveCard = E().ensureOffensiveCard;
        const anteCard = E().anteCard;

        // Check for game over BEFORE starting next round
        let winner = checkGameOver(gameState);
        if (winner) {
            console.log("[CardGame v2] Game over detected at round start - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        // Tick down status effect durations at end of round
        tickStatusEffects(gameState.player);
        tickStatusEffects(gameState.opponent);

        // Process turn-start effects (like poison damage)
        let playerEffects = processStatusEffectsTurnStart(gameState.player);
        let opponentEffects = processStatusEffectsTurnStart(gameState.opponent);
        if (playerEffects.length > 0 || opponentEffects.length > 0) {
            console.log("[CardGame v2] Status effects triggered:", { player: playerEffects, opponent: opponentEffects });
        }

        // Check again after status effects (poison could kill)
        winner = checkGameOver(gameState);
        if (winner) {
            console.log("[CardGame v2] Game over from status effects - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        gameState.round++;
        gameState.phase = GAME_PHASES.INITIATIVE;

        // Reset AP
        gameState.player.apUsed = 0;
        gameState.opponent.apUsed = 0;

        // Reset round points
        gameState.player.roundPoints = 0;
        gameState.opponent.roundPoints = 0;

        // Reset hoarding prevention tracking
        gameState.player.typesPlayedThisRound = {};
        gameState.opponent.typesPlayedThisRound = {};
        gameState.exhaustedThisRound = [];
        gameState.playerLethargy = [];
        gameState.opponentLethargy = [];

        // Clear action bar
        gameState.actionBar.positions = [];
        gameState.actionBar.resolveIndex = -1;

        // Clear beginning threats from previous round
        gameState.beginningThreats = [];

        // Clear pot (winner claimed it)
        gameState.pot = [];

        // Auto-draw at start of rounds 2+
        // Draw cards to refill hand (up to 5 cards, draw difference)
        let targetHandSize = 5;
        let playerDraw = Math.max(0, targetHandSize - gameState.player.hand.length);
        let opponentDraw = Math.max(0, targetHandSize - gameState.opponent.hand.length);

        if (playerDraw > 0) {
            console.log("[CardGame v2] Round", gameState.round, "- Player draws", playerDraw, "cards");
            drawCardsForActor(gameState.player, playerDraw);
        }
        if (opponentDraw > 0) {
            console.log("[CardGame v2] Round", gameState.round, "- Opponent draws", opponentDraw, "cards");
            drawCardsForActor(gameState.opponent, opponentDraw);
        }

        // Ensure each player has at least one Attack card
        ensureOffensiveCard(gameState.player, "Player");
        ensureOffensiveCard(gameState.opponent, "Opponent");

        // Mandatory ante: each player puts 1 random card into pot
        anteCard(gameState.player, "Player");
        anteCard(gameState.opponent, "Opponent");

        // Reset initiative animation state so it replays
        resetInitAnimState();

        // Clear previous initiative results
        gameState.initiative.playerRoll = null;
        gameState.initiative.opponentRoll = null;
        gameState.initiative.winner = null;

        console.log("[CardGame v2] Starting round", gameState.round, "- Pot has", gameState.pot.length, "cards");
        m.redraw();

        // Trigger round start narration (non-blocking)
        if (gameState.round > 1) {
            narrateRoundStart();
        }

        // Start initiative animation for round 2+ (oninit won't fire again)
        setTimeout(() => {
            if (gameState && gameState.phase === GAME_PHASES.INITIATIVE) {
                startInitiativeAnimation();
            }
        }, 100);
    }

    // ── Campaign State ──────────────────────────────────────────────────
    // Module-level campaign state (loaded at game start, used in sidebar/game-over)
    let activeCampaign = null;
    let levelUpState = null; // { campaign, statsSelected: [], onComplete: fn }

    // Apply campaign stat gains to a game state's player character
    function applyCampaignBonuses(state, campaign) {
        if (!campaign?.statGains || !state?.player?.character?.stats) return;
        let stats = state.player.character.stats;
        for (let [stat, gain] of Object.entries(campaign.statGains)) {
            if (typeof stats[stat] === "number") {
                stats[stat] += gain;
            }
        }
        // Recalculate derived values from updated stats
        let end = stats.END ?? stats.end ?? 12;
        state.player.ap = Math.max(2, Math.floor(end / 5) + 1);
        state.player.maxAp = state.player.ap;
        let mag = stats.MAG ?? stats.mag ?? 12;
        state.player.energy = mag;
        state.player.maxEnergy = mag;
        console.log("[CardGame v2] Campaign bonuses applied:", JSON.stringify(campaign.statGains),
            "\u2192 AP:", state.player.ap, "Energy:", state.player.energy);
    }

    // ── Resolution Animation State ──────────────────────────────────────
    let resolutionAnimating = false;
    let resolutionPhase = "idle";  // "idle" | "rolling" | "result" | "done"
    let resolutionDiceFaces = { attack: 1, defense: 1 };
    let resolutionDiceInterval = null;
    let currentCombatResult = null;

    // ── Resolution Driver ───────────────────────────────────────────────
    // The main resolution phase driver — handles combat, rest, guard, flee,
    // investigate, trade, craft, talk, magic action types.
    function advanceResolution() {
        if (!gameState) return;
        const GAME_PHASES = C().GAME_PHASES;
        if (gameState.phase !== GAME_PHASES.RESOLUTION) return;
        if (resolutionAnimating) return;

        const rollD20 = E().rollD20;
        const rollAttack = E().rollAttack;
        const rollDefense = E().rollDefense;
        const getCombatOutcome = E().getCombatOutcome;
        const calculateDamage = E().calculateDamage;
        const applyDamage = E().applyDamage;
        const resolveCombat = E().resolveCombat;
        const checkGameOver = E().checkGameOver;
        const checkExhausted = E().checkExhausted;
        const drawCardsForActor = E().drawCardsForActor;
        const applyStatusEffect = E().applyStatusEffect;
        const parseEffect = E().parseEffect;
        const applyParsedEffects = E().applyParsedEffects;
        const getStackSkillMod = E().getStackSkillMod;
        // openTalkChat may come from UI module or monolith
        const openTalkChat = window.CardGame.UI?.openTalkChat || window.CardGame._openTalkChat;

        let bar = gameState.actionBar;
        if (bar.resolveIndex >= bar.positions.length) {
            // Resolution complete - check for game over
            let winner = checkGameOver(gameState);
            if (winner) {
                gameState.winner = winner;
                gameState.phase = "GAME_OVER";
                m.redraw();
                return;
            }
            advancePhase();
            return;
        }

        let pos = bar.positions[bar.resolveIndex];
        if (pos.resolved) {
            bar.resolveIndex++;
            advanceResolution();
            return;
        }

        // Check if this is a threat position (beginning threat from Nat 1)
        let isThreat = pos.isThreat && pos.threat;

        // Determine if this is a combat action
        let card = pos.stack?.coreCard;
        let isAttack = (card && card.name === "Attack") || isThreat;

        if (isAttack) {
            // Combat resolution with dice animation
            resolutionAnimating = true;
            resolutionPhase = "rolling";
            currentCombatResult = null;

            // Start dice animation
            resolutionDiceInterval = setInterval(() => {
                resolutionDiceFaces.attack = rollD20();
                resolutionDiceFaces.defense = rollD20();
                m.redraw();
            }, 80);

            m.redraw();

            // After 1.5 seconds, resolve the combat
            setTimeout(() => {
                clearInterval(resolutionDiceInterval);
                resolutionDiceInterval = null;

                // Determine attacker and defender
                let attacker, defender;
                if (isThreat) {
                    // Threat attacks the player who fumbled (rolled Nat 1)
                    attacker = {
                        name: pos.threat.name,
                        character: {
                            name: pos.threat.name,
                            stats: { STR: pos.threat.atk, END: pos.threat.def }
                        },
                        hp: pos.threat.hp,
                        maxHp: pos.threat.maxHp,
                        cardStack: [{ type: "item", subtype: "weapon", atk: pos.threat.atk }]
                    };
                    defender = pos.target === "player" ? gameState.player : gameState.opponent;
                } else {
                    attacker = pos.owner === "player" ? gameState.player : gameState.opponent;
                    defender = pos.owner === "player" ? gameState.opponent : gameState.player;
                }

                // Resolve combat (pass stack for skill modifiers)
                resolveCombat(attacker, defender, pos.stack);
                resolutionPhase = "result";

                // Trigger narrator for resolution (non-blocking, with fallback)
                if (currentCombatResult) {
                    triggerNarration("resolution", {
                        isPlayerAttack: pos.owner === "player",
                        attackerName: isThreat ? pos.threat?.name : (pos.owner === "player" ? gameState.player.character?.name : gameState.opponent.character?.name),
                        defenderName: isThreat ? (pos.target === "player" ? gameState.player.character?.name : gameState.opponent.character?.name) : (pos.owner === "player" ? gameState.opponent.character?.name : gameState.player.character?.name),
                        outcome: currentCombatResult.outcome?.label || "Hit",
                        damage: currentCombatResult.damageDealt || 0
                    });
                }

                m.redraw();

                // After showing result, mark as resolved
                setTimeout(() => {
                    pos.resolved = true;
                    pos.combatResult = currentCombatResult;
                    resolutionAnimating = false;
                    resolutionPhase = "done";
                    bar.resolveIndex++;

                    // Exhausted check: if attack failed (miss/counter), check for hoarding
                    if (currentCombatResult && currentCombatResult.outcome.damageMultiplier <= 0) {
                        let actionType = pos.stack?.coreCard?.name;
                        let ownerKey = pos.owner;
                        let ownerActor = ownerKey === "player" ? gameState.player : gameState.opponent;
                        let ownerName = ownerKey === "player" ? "Player" : "Opponent";

                        let exhaustedResult = checkExhausted(ownerActor, ownerName, actionType);
                        if (exhaustedResult) {
                            // Store for UI display
                            if (!gameState.exhaustedThisRound) gameState.exhaustedThisRound = [];
                            gameState.exhaustedThisRound.push({
                                owner: ownerKey,
                                actionType: exhaustedResult.actionType,
                                stripped: exhaustedResult.stripped
                            });
                            console.log("[CardGame v2] Exhausted triggered for", ownerName, "- stripped", exhaustedResult.stripped, exhaustedResult.actionType);
                        }
                    }

                    // Handle threat resolution outcome
                    if (isThreat && pos.threat) {
                        let targetActor = pos.target === "player" ? gameState.player : gameState.opponent;
                        let targetName = pos.target === "player" ? "Player" : "Opponent";

                        // Check if defender won (threat missed or was countered)
                        if (currentCombatResult && currentCombatResult.outcome.damageMultiplier <= 0) {
                            // Defender survived/won - grant loot
                            console.log("[CardGame v2]", targetName, "defeated the threat! Loot earned:", pos.threat.lootRarity);

                            // Create loot card based on threat rarity
                            let lootCard = {
                                type: "item",
                                subtype: "consumable",
                                name: "Threat Loot (" + pos.threat.lootRarity + ")",
                                rarity: pos.threat.lootRarity,
                                effect: pos.threat.lootRarity === "RARE" ? "Restore 5 HP" : "Restore 3 HP",
                                flavor: "Spoils from defeating " + pos.threat.name
                            };
                            targetActor.hand.push(lootCard);

                            // Store threat result for UI
                            if (!gameState.threatResults) gameState.threatResults = [];
                            gameState.threatResults.push({
                                threatName: pos.threat.name,
                                target: pos.target,
                                outcome: "defeated",
                                loot: lootCard.name
                            });
                        } else {
                            // Threat hit the defender
                            console.log("[CardGame v2]", targetName, "was hit by", pos.threat.name);

                            if (!gameState.threatResults) gameState.threatResults = [];
                            gameState.threatResults.push({
                                threatName: pos.threat.name,
                                target: pos.target,
                                outcome: "hit",
                                damage: currentCombatResult?.damageResult?.finalDamage || 0
                            });
                        }
                    }

                    // Move played cards to owner's discard pile (or return magic to hand)
                    if (pos.stack && pos.stack.coreCard && !isThreat) {
                        let owner = pos.owner === "player" ? gameState.player : gameState.opponent;
                        let coreCard = pos.stack.coreCard;

                        // Magic cards return to hand (reusable)
                        if (coreCard.type === "magic" && coreCard.reusable !== false) {
                            owner.hand.push(coreCard);
                            console.log("[CardGame v2] Magic card", coreCard.name, "returned to hand");
                        } else {
                            owner.discardPile.push(coreCard);
                        }

                        // Modifiers always go to discard
                        pos.stack.modifiers.forEach(mod => owner.discardPile.push(mod));
                    }

                    // Check for game over after each combat
                    let winner = checkGameOver(gameState);
                    if (winner) {
                        gameState.winner = winner;
                        gameState.phase = "GAME_OVER";
                        m.redraw();
                        return;
                    }

                    m.redraw();

                    // Auto-advance after 3 seconds total between actions
                    if (bar.resolveIndex < bar.positions.length) {
                        setTimeout(() => advanceResolution(), 1500);  // 1.5s after result shown
                    } else {
                        setTimeout(() => advancePhase(), 1000);
                    }
                }, 1500);  // Show result for 1.5 seconds (total ~3s per action)
            }, 1500);  // Roll dice for 1.5 seconds
        } else {
            // Non-combat action (Rest, Flee, etc.) - simple resolution
            resolutionAnimating = true;
            currentCombatResult = null;

            setTimeout(() => {
                pos.resolved = true;
                resolutionAnimating = false;
                bar.resolveIndex++;

                // Handle non-combat actions
                if (card) {
                    let owner = pos.owner === "player" ? gameState.player : gameState.opponent;
                    let target = pos.owner === "player" ? gameState.opponent : gameState.player;

                    // Rest action: restore HP and Energy
                    if (card.name === "Rest") {
                        let hpBefore = owner.hp;
                        let energyBefore = owner.energy;
                        let moraleBefore = owner.morale;
                        owner.hp = Math.min(owner.maxHp, owner.hp + 2);
                        owner.energy = Math.min(owner.maxEnergy, owner.energy + 3);
                        owner.morale = Math.min(owner.maxMorale, owner.morale + 2);
                        console.log("[CardGame v2]", pos.owner, "rested:",
                            "HP", hpBefore, "->", owner.hp, "(max:" + owner.maxHp + ")",
                            "| Energy", energyBefore, "->", owner.energy,
                            "| Morale", moraleBefore, "->", owner.morale);
                    } else {
                        console.log("[CardGame v2] Non-combat action card:", card.name, card.type);
                    }

                    // Guard/Defend action: gain shielded status
                    if (card.name === "Guard" || card.name === "Defend") {
                        applyStatusEffect(owner, "shielded", card.name);
                        console.log("[CardGame v2]", pos.owner, "is now shielded (+3 DEF until hit)");
                    }

                    // Flee action: AGI roll to escape (forfeit pot, end combat)
                    if (card.name === "Flee" || card.name === "Escape") {
                        let ownerAgi = owner.character.stats?.AGI || 10;
                        let fleeRoll = rollD20() + ownerAgi;
                        let fleeDC = 12;  // Base difficulty, could be modified by encounter

                        if (fleeRoll >= fleeDC) {
                            console.log("[CardGame v2]", pos.owner, "fled successfully! Roll:", fleeRoll, "vs DC", fleeDC);
                            // Forfeit pot to opponent
                            if (gameState.pot.length > 0) {
                                let other = pos.owner === "player" ? gameState.opponent : gameState.player;
                                gameState.pot.forEach(c => other.discardPile.push(c));
                                gameState.pot = [];
                                console.log("[CardGame v2] Pot forfeited to", pos.owner === "player" ? "opponent" : "player");
                            }
                            // End the round early (skip remaining actions)
                            bar.resolveIndex = bar.positions.length;
                        } else {
                            console.log("[CardGame v2]", pos.owner, "flee failed! Roll:", fleeRoll, "vs DC", fleeDC);
                            // Failed flee - lose morale
                            owner.morale = Math.max(0, owner.morale - 2);
                        }
                    }

                    // Investigate action: INT roll to reveal information
                    if (card.name === "Investigate" || card.name === "Search") {
                        let ownerInt = owner.character.stats?.INT || 10;
                        let investRoll = rollD20() + ownerInt;
                        let investDC = 10;

                        if (investRoll >= investDC) {
                            console.log("[CardGame v2]", pos.owner, "investigated successfully! Roll:", investRoll);
                            // Draw an extra card as reward
                            drawCardsForActor(owner, 1);
                        } else {
                            console.log("[CardGame v2]", pos.owner, "investigation found nothing. Roll:", investRoll);
                        }
                    }

                    // Use Item action: apply consumable effect
                    if (card.type === "item" && card.subtype === "consumable") {
                        // Use unified effect parser for consumable effects
                        let parsed = parseEffect(card.effect || "");
                        let log = applyParsedEffects(parsed, owner, target, card.name);
                        log.forEach(msg => console.log("[CardGame v2]", pos.owner, "used:", msg));
                        // Consumables are NOT returned to hand - they stay in discard
                    }

                    // Feint action: weaken opponent's next defense
                    if (card.name === "Feint") {
                        applyStatusEffect(target, "weakened", card.name);
                        console.log("[CardGame v2]", pos.owner, "feinted! Target is weakened (-2 all rolls)");
                    }

                    // Trade action: exchange items (vs AI: auto-decline unless items offered)
                    if (card.name === "Trade") {
                        let offeredItems = (pos.stack?.modifiers || []).filter(m => m.type === "item");
                        if (offeredItems.length > 0) {
                            // AI evaluates trade: 50% accept if items of equal or greater value offered
                            let acceptChance = 0.5;
                            if (Math.random() < acceptChance) {
                                // Accept: AI gives a random item from hand in exchange
                                let aiItems = target.hand.filter(c => c.type === "item");
                                if (aiItems.length > 0) {
                                    let giveItem = aiItems[Math.floor(Math.random() * aiItems.length)];
                                    target.hand = target.hand.filter(c => c !== giveItem);
                                    owner.hand.push(giveItem);
                                    // Give offered items to AI
                                    offeredItems.forEach(item => {
                                        owner.hand = owner.hand.filter(c => c !== item);
                                        target.hand.push(item);
                                    });
                                    console.log("[CardGame v2] Trade accepted!", owner.name, "gave", offeredItems.map(i => i.name).join(", "), "received", giveItem.name);
                                    pos.tradeResult = { accepted: true, gave: offeredItems, received: [giveItem] };
                                } else {
                                    console.log("[CardGame v2] Trade declined - AI has no items to trade");
                                    pos.tradeResult = { accepted: false, reason: "No items available" };
                                }
                            } else {
                                console.log("[CardGame v2] Trade declined by opponent");
                                pos.tradeResult = { accepted: false, reason: "Declined" };
                            }
                        } else {
                            console.log("[CardGame v2] Trade failed - no items offered");
                            pos.tradeResult = { accepted: false, reason: "No items offered" };
                        }
                    }

                    // Craft action: INT roll to create item from materials
                    if (card.name === "Craft") {
                        let ownerInt = owner.character.stats?.INT || 10;
                        let materials = (pos.stack?.modifiers || []).filter(m => m.type === "item" && m.subtype === "material");

                        if (materials.length >= 2) {
                            let craftRoll = rollD20() + ownerInt;
                            let craftDC = 12 + (materials.length * 2);  // Harder with more materials

                            if (craftRoll >= craftDC) {
                                // Success: create item based on materials
                                let craftedItem = {
                                    type: "item",
                                    subtype: "consumable",
                                    name: "Crafted " + materials[0].name + " Potion",
                                    effect: "Restore " + (5 + materials.length) + " HP",
                                    rarity: materials.length >= 3 ? "RARE" : "COMMON",
                                    flavor: "Crafted from " + materials.map(m => m.name).join(" + ")
                                };
                                owner.hand.push(craftedItem);
                                // Remove materials from hand
                                materials.forEach(mat => {
                                    owner.hand = owner.hand.filter(c => c !== mat);
                                });
                                console.log("[CardGame v2] Crafting success! Roll:", craftRoll, "vs DC", craftDC, "- Created:", craftedItem.name);
                                pos.craftResult = { success: true, roll: craftRoll, dc: craftDC, created: craftedItem };
                            } else {
                                console.log("[CardGame v2] Crafting failed. Roll:", craftRoll, "vs DC", craftDC);
                                // Lose one material on failure
                                if (materials.length > 0) {
                                    let lostMat = materials[0];
                                    owner.hand = owner.hand.filter(c => c !== lostMat);
                                    owner.discardPile.push(lostMat);
                                }
                                pos.craftResult = { success: false, roll: craftRoll, dc: craftDC };
                            }
                        } else {
                            console.log("[CardGame v2] Crafting failed - need at least 2 materials");
                            pos.craftResult = { success: false, reason: "Insufficient materials" };
                        }
                    }

                    // Talk action: Open chat UI for player, dice roll for AI
                    if (card.type === "talk") {
                        if (pos.owner === "player") {
                            // Player Talk: Open chat UI (Phase 8)
                            // Chat works with or without LLM - will use fallback responses if needed
                            console.log("[CardGame v2] Opening Talk chat. LLM available:", !!gameChatManager?.initialized);
                            if (openTalkChat) openTalkChat(card, bar.resolveIndex);
                            // Resolution continues when chat ends
                            return;  // Don't auto-advance - chat will handle it
                        } else {
                            // Opponent Talk: Use dice roll
                            let ownerCha = owner.character.stats?.CHA || 10;
                            let targetCha = target.character.stats?.CHA || 10;
                            let skillMod = getStackSkillMod(pos.stack, "talk");

                            let ownerRoll = rollD20() + ownerCha + skillMod;
                            let targetRoll = rollD20() + targetCha;

                            if (ownerRoll > targetRoll) {
                                let moraleDmg = Math.max(1, Math.floor((ownerRoll - targetRoll) / 2));
                                target.morale = Math.max(0, target.morale - moraleDmg);
                                owner.morale = Math.min(owner.maxMorale, owner.morale + 1);
                                console.log("[CardGame v2]", pos.owner, "Talk success! Target morale -" + moraleDmg + ", own +1");
                            } else {
                                owner.morale = Math.max(0, owner.morale - 1);
                                console.log("[CardGame v2]", pos.owner, "Talk failed. Own morale -1");
                            }

                            if (target.morale <= 0) {
                                console.log("[CardGame v2] Target surrendered due to morale!");
                                gameState.winner = pos.owner;
                                gameState.phase = "GAME_OVER";
                                m.redraw();
                                return;
                            }
                        }
                    }

                    // Magic card resolution
                    if (card.type === "magic") {
                        let ownerStats = owner.character.stats || {};
                        let fizzled = false;

                        // Check stat requirements (fizzle if not met)
                        if (card.requires) {
                            for (let stat in card.requires) {
                                let required = card.requires[stat];
                                let actual = ownerStats[stat] || 0;
                                if (actual < required) {
                                    fizzled = true;
                                    console.log("[CardGame v2] Spell fizzled!", card.name, "requires", stat, required, "but actor has", actual);
                                    break;
                                }
                            }
                        }

                        if (!fizzled) {
                            // Use unified effect parser for all magic effects
                            let parsed = parseEffect(card.effect || "");
                            let log = applyParsedEffects(parsed, owner, target, card.name);
                            log.forEach(msg => console.log("[CardGame v2]", pos.owner, "cast:", msg));

                            // Check for defeat after damage
                            if (parsed.damage && target.hp <= 0) {
                                gameState.winner = pos.owner;
                                gameState.phase = "GAME_OVER";
                                m.redraw();
                                return;
                            }
                        }
                    }

                    // Move played cards to discard pile OR return magic to hand
                    if (card.type === "magic" && card.reusable !== false) {
                        // Magic cards return to hand (reusable)
                        owner.hand.push(card);
                        console.log("[CardGame v2] Magic card", card.name, "returned to hand");
                    } else {
                        owner.discardPile.push(card);
                    }

                    // Modifiers always go to discard
                    if (pos.stack.modifiers) {
                        pos.stack.modifiers.forEach(mod => owner.discardPile.push(mod));
                    }
                }

                m.redraw();

                // Auto-advance after ~3 seconds total
                if (bar.resolveIndex < bar.positions.length) {
                    setTimeout(() => advanceResolution(), 2000);
                } else {
                    setTimeout(() => advancePhase(), 1000);
                }
            }, 1000);  // Show action result for 1 second
        }

        m.redraw();
    }

    // ── Export ───────────────────────────────────────────────────────────
    window.CardGame.GameState = {
        // Mutable state (accessed via getters/setters for encapsulation)
        state: {
            get gameState() { return gameState; },
            set gameState(v) { gameState = v; },
            get gameCharSelection() { return gameCharSelection; },
            set gameCharSelection(v) { gameCharSelection = v; },
            get initAnimState() { return initAnimState; },
            set initAnimState(v) { initAnimState = v; },
            get llmStatus() { return llmStatus; },
            set llmStatus(v) { llmStatus = v; },
            get gameDirector() { return gameDirector; },
            set gameDirector(v) { gameDirector = v; },
            get gameNarrator() { return gameNarrator; },
            set gameNarrator(v) { gameNarrator = v; },
            get gameChatManager() { return gameChatManager; },
            set gameChatManager(v) { gameChatManager = v; },
            get gameVoice() { return gameVoice; },
            set gameVoice(v) { gameVoice = v; },
            get gameAnnouncerVoice() { return gameAnnouncerVoice; },
            set gameAnnouncerVoice(v) { gameAnnouncerVoice = v; },
            get activeCampaign() { return activeCampaign; },
            set activeCampaign(v) { activeCampaign = v; },
            get levelUpState() { return levelUpState; },
            set levelUpState(v) { levelUpState = v; },
            // Resolution animation sub-state (read by UI renderers)
            get resolutionAnimating() { return resolutionAnimating; },
            set resolutionAnimating(v) { resolutionAnimating = v; },
            get resolutionPhase() { return resolutionPhase; },
            set resolutionPhase(v) { resolutionPhase = v; },
            get resolutionDiceFaces() { return resolutionDiceFaces; },
            set resolutionDiceFaces(v) { resolutionDiceFaces = v; },
            get currentCombatResult() { return currentCombatResult; },
            set currentCombatResult(v) { currentCombatResult = v; }
        },

        // Convenience getters/setters
        getGameState: () => gameState,
        setGameState: (s) => { gameState = s; },
        getGameCharSelection: () => gameCharSelection,
        setGameCharSelection: (s) => { gameCharSelection = s; },

        // LLM connectivity
        checkLlmConnectivity,

        // Game state creation
        createGameState,

        // LLM component initialization
        initializeLLMComponents,

        // Narration system
        triggerNarration,
        narrateGameStart,
        narrateGameEnd,
        narrateRoundStart,
        narrateRoundEnd,
        showNarrationSubtitle,
        buildEmotionContext,

        // Initiative
        resetInitAnimState,
        startInitiativeAnimation,
        runInitiativePhase,

        // Phase transitions
        advancePhase,
        enterDrawPlacementPhase,
        enterThreatResponsePhase,
        enterEndThreatPhase,

        // Threat combat
        resolveThreatCombat,
        resolveEndThreatCombat,
        placeThreatDefenseCard,
        skipThreatResponse,

        // Round management
        startNextRound,

        // Campaign
        applyCampaignBonuses,

        // Resolution driver
        advanceResolution
    };

    console.log('[CardGame] State/gameState loaded (' +
        Object.keys(window.CardGame.GameState).length + ' exports)');

}());
