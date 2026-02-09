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
 *   - CardGame.Engine      (actions: drawCardsForActor, dealInitialStack, selectAction,
 *                           getActionsForActor, checkGameOver, anteCard, claimPot)
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
    async function createGameState(deck, selectedCharacter) {
        const shuffle = Ch().shuffle;
        const dealInitialStack = E().dealInitialStack;
        const getActionsForActor = E().getActionsForActor;
        const GAME_PHASES = C().GAME_PHASES;

        // Load data from JSON (async, uses defaults if unavailable)
        if (C().loadActionDefinitions) {
            await C().loadActionDefinitions();
        }
        if (E().loadEncounterData) {
            await E().loadEncounterData();
        }

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

        // Separate consumables (drawn into hand) from equipment (placed in cardStack)
        let consumableCards = itemCards.filter(c => c.subtype === "consumable");
        let equipmentItems = itemCards.filter(c => c.subtype !== "consumable");

        // Split equipment and apparel for player vs opponent (so they get different gear)
        let itemMid = Math.ceil(equipmentItems.length / 2);
        let apparelMid = Math.ceil(apparelCards.length / 2);
        let playerItems = equipmentItems.slice(0, itemMid);
        let opponentItems = equipmentItems.slice(itemMid);
        let playerApparel = apparelCards.slice(0, apparelMid);
        let opponentApparel = apparelCards.slice(apparelMid);

        // Theme cardPool adds extra modifier cards to the draw pile
        let activeTheme = Th()?.getActiveTheme?.();
        let themeModifiers = [];
        if (activeTheme?.cardPool) {
            themeModifiers = activeTheme.cardPool.filter(c =>
                c.type === "skill" || c.type === "magic" || (c.type === "item" && c.subtype === "consumable")
            );
        }

        let playableCards = [...skillCards, ...magicCards, ...consumableCards, ...themeModifiers];

        console.log("[CardGame v2] Card counts - skill:", skillCards.length,
            "magic:", magicCards.length, "consumable:", consumableCards.length,
            "themeModifiers:", themeModifiers.length, "playable total:", playableCards.length);

        // If deck has no modifier cards, create basic skill cards
        if (playableCards.length === 0) {
            console.log("[CardGame v2] No modifier cards in deck, using default starter skills");
            playableCards = [
                { type: "skill", name: "Quick Reflexes", category: "Defense", modifier: "+2 to Flee and initiative", tier: "COMMON" },
                { type: "skill", name: "Swordsmanship", category: "Combat", modifier: "+2 to Attack rolls with Slashing weapons", tier: "COMMON" },
                { type: "skill", name: "Survival", category: "Survival", modifier: "+1 to Investigate and Rest", tier: "COMMON" }
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

            // Round pot and loot
            pot: [],
            roundLoot: [],  // Loot collected during this round (claimed by winner at cleanup)

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

            // LLM busy indicator (null when idle, string message when busy)
            llmBusy: null,

            // Pre-generated narration lines (used before hardcoded fallbacks).
            // Generic lines that don't mention character names.
            // Sourced from deck.narration or theme config narration section.
            narration: deck.narration || (Th()?.getActiveTheme?.()?.narration) || null,
            narrationReady: false,

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
            },

            // Poker Face state (opt-in biometric emotion detection)
            pokerFace: {
                enabled: (deck?.gameConfig?.pokerFaceEnabled === true) && !!page?.components?.moodRing?.enabled?.(),
                banterLevel: deck?.gameConfig?.banterLevel || "moderate",
                currentEmotion: "neutral",
                emotionHistory: [],
                dominantTrend: "neutral",
                lastTransition: null,
                commentary: null
            }
        };

        // Set available actions for each actor (based on character class template)
        state.player.availableActions = getActionsForActor(state.player);
        state.opponent.availableActions = getActionsForActor(state.opponent);

        console.log("[CardGame v2] Player actions:", state.player.availableActions);
        console.log("[CardGame v2] Opponent actions:", state.opponent.availableActions);

        return state;
    }

    // ── LLM Component State ─────────────────────────────────────────────
    let gameDirector = null;
    let gameNarrator = null;
    let gameChatManager = null;
    let gameVoice = null;
    let gameAnnouncerVoice = null;  // Separate voice instance for announcer

    // Stop all audio and clean up voice instances before starting a new game
    function cleanupAudio() {
        // Stop existing voice instances
        if (gameVoice) {
            gameVoice.stop();
            gameVoice = null;
        }
        if (gameAnnouncerVoice) {
            gameAnnouncerVoice.stop();
            gameAnnouncerVoice = null;
        }
        // Stop any page-level audio (loops, background music, TTS)
        if (page?.components?.audio) {
            if (typeof page.components.audio.stopAll === "function") {
                page.components.audio.stopAll();
            }
            if (typeof page.components.audio.disableLoop === "function") {
                page.components.audio.disableLoop();
            }
        }
        // Clear LLM components
        if (gameChatManager) {
            gameChatManager = null;
        }
        if (gameDirector) {
            gameDirector = null;
        }
        if (gameNarrator) {
            gameNarrator = null;
        }
        console.log("[CardGame v2] Audio and LLM components cleaned up");
    }

    // Initialize LLM components (Director and Narrator) for a game
    async function initializeLLMComponents(state, deck, options) {
        // Clean up any existing audio/voice/LLM from previous game
        cleanupAudio();
        const activeTheme = Th()?.getActiveTheme?.() || { themeId: "high-fantasy" };
        const themeId = deck?.themeId || activeTheme?.themeId || "high-fantasy";
        const opponentChar = state?.opponent?.character;
        const gc = deck?.gameConfig || {};
        const narrationEnabled = gc.narrationEnabled !== false;       // Default: enabled
        const opponentVoiceEnabled = gc.opponentVoiceEnabled === true; // Default: disabled
        const opponentVoiceProfileId = gc.opponentVoiceProfileId || null;
        const announcerEnabled = gc.announcerEnabled === true;         // Default: disabled
        const announcerProfile = activeTheme?.narration?.announcerProfile || "arena-announcer";
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

        // ── Phase 1: Initialize voices FIRST so intro narration can play immediately ──

        // Initialize Opponent Voice (the opponent character's TTS voice)
        const voiceDeckName = deck?.deckName || "";
        if (CardGameVoice) {
            try {
                gameVoice = new CardGameVoice();
                await gameVoice.initialize({
                    subtitlesOnly: !opponentVoiceEnabled,
                    voiceProfileId: opponentVoiceProfileId,
                    volume: 1.0,
                    deckName: voiceDeckName
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
                    volume: 1.0,
                    deckName: voiceDeckName
                });
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize announcer voice:", err);
                gameAnnouncerVoice = null;
            }
        } else {
            gameAnnouncerVoice = null;
        }

        // ── Phase 2: Trigger game start narration (plays while LLM components init) ──
        // Uses fallback text immediately; voice can start speaking during LLM init below
        if (!options?.skipNarration) {
            narrateGameStart();

            // Signal that narration is displayed — initiative animation waits for this
            if (gameState) {
                gameState.narrationReady = true;
                m.redraw();
            }
        }

        // ── Phase 3: Initialize LLM components (these can take time — voice plays in parallel) ──
        // Generate unique session suffix to prevent cross-game context contamination
        const deckName = deck?.deckName || "deck";
        const sessionSuffix = deckName + "-" + Math.random().toString(36).substring(2, 8);

        // Initialize Announcer narrator (optional -- separate from opponent)
        if (announcerEnabled && narrationEnabled && CardGameNarrator) {
            try {
                gameNarrator = new CardGameNarrator();
                const narratorOk = await gameNarrator.initialize(announcerProfile, themeId, sessionSuffix);
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

        // Initialize AI Director (optional - falls back to FIFO if unavailable)
        if (CardGameDirector) {
            try {
                gameDirector = new CardGameDirector();
                const directorOk = await gameDirector.initialize(opponentChar, themeId, sessionSuffix);
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

        // Initialize Chat Manager for Talk cards / opponent dialogue (requires LLM enabled)
        const playerChar = state?.player?.character;
        if (narrationEnabled && CardGameChatManager) {
            try {
                gameChatManager = new CardGameChatManager();
                const chatOk = await gameChatManager.initialize(opponentChar, playerChar, themeId, sessionSuffix);
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
    }

    // ── Unified Narration System ────────────────────────────────────────
    // Centralized narration with LLM and fallback support

    const emotionDescriptions = {
        happy: "appears pleased",
        sad: "looks dejected",
        angry: "seems frustrated",
        fear: "appears nervous",
        surprise: "looks startled",
        disgust: "seems unimpressed"
    };

    // Update Poker Face state from moodRing (called during narration and resolution)
    function updatePokerFace() {
        if (!gameState?.pokerFace?.enabled) return;
        const moodRing = page?.components?.moodRing;
        if (!moodRing?.enabled?.()) return;

        const emotion = moodRing.emotion() || "neutral";
        const pf = gameState.pokerFace;
        const prev = pf.currentEmotion;

        if (emotion !== prev) {
            pf.lastTransition = { from: prev, to: emotion, time: Date.now() };
        }
        pf.currentEmotion = emotion;
        pf.emotionHistory.push({ emotion, timestamp: Date.now() });
        if (pf.emotionHistory.length > 10) pf.emotionHistory.shift();

        // Compute dominant trend (most common in last 10 readings)
        let counts = {};
        pf.emotionHistory.forEach(h => { counts[h.emotion] = (counts[h.emotion] || 0) + 1; });
        pf.dominantTrend = Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0] || "neutral";
    }

    // Build emotion context from Poker Face (mood ring) if available
    function buildEmotionContext() {
        updatePokerFace();
        const pf = gameState?.pokerFace;
        const emotion = pf?.enabled ? pf.currentEmotion : ((page?.components?.moodRing?.enabled?.()) ? page.components.moodRing.emotion() : null);
        if (!emotion || emotion === "neutral") return null;

        return {
            emotion,
            description: emotionDescriptions[emotion] || null,
            trend: pf?.dominantTrend || null,
            transition: pf?.lastTransition || null,
            banterLevel: pf?.banterLevel || null
        };
    }

    async function triggerNarration(trigger, extraContext = {}) {
        if (!gameState) return;
        console.log("[CardGame v2] triggerNarration called:", trigger);

        const playerName = gameState.player?.character?.name || "Player";
        const opponentName = gameState.opponent?.character?.name || "Opponent";
        const baseContext = { playerName, opponentName };

        // Check for pre-generated deck narration lines first (generic, no character names)
        let deckNarration = gameState.narration;

        // Build trigger-specific context
        let context, fallbackText;
        switch (trigger) {
            case "game_start":
                context = { ...baseContext, round: 1 };
                // Deck narration can provide a generic intro; character names are added around it
                if (deckNarration?.gameStart) {
                    fallbackText = deckNarration.gameStart;
                } else {
                    fallbackText = `The arena awaits! ${playerName} faces ${opponentName} in a battle of wits and steel. Let the cards decide your fate!`;
                }
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
                let lowHp = (gameState.player.hp < 10 || gameState.opponent.hp < 10);
                if (lowHp && deckNarration?.lowHpTension?.length) {
                    let lines = deckNarration.lowHpTension;
                    fallbackText = lines[Math.floor(Math.random() * lines.length)];
                } else if (deckNarration?.roundStart && Array.isArray(deckNarration.roundStart)) {
                    let lines = deckNarration.roundStart;
                    fallbackText = lines[Math.floor(Math.random() * lines.length)];
                } else {
                    const tension = lowHp ? " The tension mounts as both combatants show signs of wear." : "";
                    fallbackText = `Round ${gameState.round} begins!${tension}`;
                }
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
                if (roundWinner === "tie" && deckNarration?.roundEndTie?.length) {
                    let lines = deckNarration.roundEndTie;
                    fallbackText = lines[Math.floor(Math.random() * lines.length)];
                } else if (roundWinner === "tie") {
                    fallbackText = `Round ${gameState.round} ends in a stalemate!`;
                } else {
                    fallbackText = `${winName} takes Round ${gameState.round}!`;
                }
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
                if (outcome === "CRIT" && deckNarration?.criticalHit?.length) {
                    let lines = deckNarration.criticalHit;
                    fallbackText = lines[Math.floor(Math.random() * lines.length)];
                } else if (outcome === "CRIT") {
                    fallbackText = `Critical hit! ${attackerName} devastates ${defenderName} for ${damage} damage!`;
                } else if ((outcome === "MISS" || damage <= 0) && deckNarration?.criticalMiss?.length) {
                    let lines = deckNarration.criticalMiss;
                    fallbackText = lines[Math.floor(Math.random() * lines.length)];
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

        // For game_start: show fallback immediately and speak it, then upgrade with LLM text if available
        // Initiative animation waits for narrationReady flag (set in initializeLLMComponents)
        if (trigger === "game_start") {
            if (fallbackText) {
                showNarrationSubtitle(fallbackText);
                // Speak fallback text immediately (voice or subtitles)
                if (gameAnnouncerVoice?.enabled) {
                    gameAnnouncerVoice.speak(fallbackText);
                }
            }
            // Fire-and-forget LLM upgrade (replaces fallback text if available)
            if (gameNarrator?.initialized) {
                gameNarrator.narrate(trigger, context).then(narration => {
                    if (narration?.text && gameState) {
                        showNarrationSubtitle(narration.text);
                        if (gameAnnouncerVoice?.enabled) {
                            gameAnnouncerVoice.stopCurrent();  // Interrupt fallback speech
                            gameAnnouncerVoice.speak(narration.text);
                        }
                    }
                }).catch(e => console.warn("[CardGame v2] game_start LLM narration failed:", e));
            }
            return;
        }

        // Blocking triggers: round_end and game_end should block UI until voice/text finishes
        let isBlocking = (trigger === "round_end" || trigger === "game_end");

        // Other triggers: set busy indicator and await LLM
        gameState.llmBusy = "Narrating...";
        if (isBlocking && gameState) {
            gameState.narrationBusy = true;
        }
        m.redraw();

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
        if (!finalText) {
            if (isBlocking && gameState) {
                gameState.narrationBusy = false;
                m.redraw();
            }
            return;
        }

        // Clear LLM busy indicator
        if (gameState) {
            gameState.llmBusy = null;
            m.redraw();
        }

        // Show subtitle
        showNarrationSubtitle(finalText);

        // Speak with announcer voice
        if (gameAnnouncerVoice?.enabled && !gameAnnouncerVoice.subtitlesOnly) {
            try {
                await gameAnnouncerVoice.speak(finalText);
            } catch (e) {
                console.warn("[CardGame v2] Voice speak failed:", e);
            }
        } else if (isBlocking) {
            // No voice — wait a minimum time for reading (50ms per word, min 3s)
            let wordCount = finalText.split(/\s+/).length;
            let readTime = Math.max(3000, wordCount * 50);
            await new Promise(resolve => setTimeout(resolve, readTime));
        }

        // Clear narration busy for blocking triggers
        if (isBlocking && gameState) {
            gameState.narrationBusy = false;
            m.redraw();
        }
    }

    // Convenience wrappers for backward compatibility
    function narrateGameStart() { return triggerNarration("game_start"); }
    function narrateGameEnd(winner) { return triggerNarration("game_end", { winner }); }
    function narrateRoundStart() { return triggerNarration("round_start"); }
    function narrateRoundEnd(roundWinner) { return triggerNarration("round_end", { roundWinner }); }

    // ── Opponent Banter ──────────────────────────────────────────────────
    // Fire-and-forget opponent quip after resolution actions
    async function triggerOpponentBanter(event, extraContext) {
        if (!gameState || !gameChatManager?.initialized) return;
        const pf = gameState.pokerFace;
        const emotionCtx = buildEmotionContext();

        const banterCtx = {
            event,
            round: gameState.round,
            playerHp: gameState.player.hp,
            opponentHp: gameState.opponent.hp,
            playerAction: extraContext?.playerAction || null,
            opponentAction: extraContext?.opponentAction || null,
            emotion: emotionCtx?.emotion || null,
            emotionDesc: emotionCtx?.description || null,
            banterLevel: pf?.banterLevel || "moderate"
        };

        try {
            const banter = await gameChatManager.generateBanter(banterCtx);
            if (banter?.text && gameState) {
                gameState.pokerFace.commentary = banter.text;
                // Show as subtitle and speak with opponent voice
                showNarrationSubtitle(banter.text);
                if (gameVoice?.enabled && !gameVoice.subtitlesOnly) {
                    gameVoice.speak(banter.text);
                }
                m.redraw();
            }
        } catch (e) {
            console.warn("[CardGame v2] Banter failed:", e);
        }
    }

    // Show narrator text as a subtitle overlay
    function showNarrationSubtitle(text) {
        if (!text) return;
        console.log("[CardGame v2] showNarrationSubtitle:", text.substring(0, 80) + (text.length > 80 ? "..." : ""));
        // Store in game state for UI to display
        if (gameState) {
            gameState.narrationText = text;
            gameState.narrationTime = Date.now();
            m.redraw();

            // Auto-hide after 8 seconds (skip during cleanup — text persists in the panel)
            setTimeout(() => {
                if (gameState && gameState.narrationTime && Date.now() - gameState.narrationTime >= 7900) {
                    let GAME_PHASES = C().GAME_PHASES;
                    if (gameState.phase !== GAME_PHASES.CLEANUP) {
                        gameState.narrationText = null;
                        m.redraw();
                    }
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
            setTimeout(async () => {
                if (gameState && gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                    console.log("[CardGame v2] Triggering AI placement (goes first)");
                    if (aiPlaceCards) await aiPlaceCards();
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

        // Clear pot and round loot (winner claimed them)
        gameState.pot = [];
        gameState.roundLoot = [];

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
                currentCombatResult = resolveCombat(attacker, defender, pos.stack);
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

                    // Fire-and-forget opponent banter after their attack resolves
                    if (pos.owner === "opponent") {
                        triggerOpponentBanter("attack_resolved", {
                            opponentAction: card?.name || "Attack",
                            playerAction: null,
                            damage: currentCombatResult.damageDealt || 0
                        });
                    }
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
                    if (checkExhausted && currentCombatResult && currentCombatResult.outcome.damageMultiplier <= 0) {
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

                    // Apply magic modifiers on Attack (enchanted strike)
                    if (card && card.name === "Attack" && pos.stack && pos.stack.modifiers) {
                        let magicMods = pos.stack.modifiers.filter(m => m.type === "magic");
                        if (magicMods.length > 0) {
                            let owner = pos.owner === "player" ? gameState.player : gameState.opponent;
                            let target = pos.owner === "player" ? gameState.opponent : gameState.player;
                            magicMods.forEach(magicMod => {
                                let parsed = parseEffect(magicMod.effect || "");
                                if (Object.keys(parsed).length > 0) {
                                    let log = applyParsedEffects(parsed, owner, target, magicMod.name);
                                    log.forEach(msg => console.log("[CardGame v2]", pos.owner, "enchanted attack:", msg));
                                }
                            });
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

                    // ── Trade action: AVG(PER, CRE, WIS) roll to take card from opponent's hand ──
                    if (card.name === "Trade") {
                        let stats = owner.character.stats || {};
                        let tradeStat = Math.round(((stats.PER || 8) + (stats.CRE || 8) + (stats.WIS || 8)) / 3);
                        let skillMod = getStackSkillMod(pos.stack, "craft");
                        let rawRoll = rollD20();
                        let tradeRoll = rawRoll + tradeStat + skillMod;
                        let tradeDC = 12;

                        // Eligible cards: opponent hand cards that aren't character cards or in their char stack
                        let eligibleCards = target.hand.filter(c => c.type !== "character");

                        if (rawRoll === 20 && eligibleCards.length > 0) {
                            // Critical success: take the best card (highest rarity)
                            let rarityOrder = { LEGENDARY: 5, EPIC: 4, RARE: 3, UNCOMMON: 2, COMMON: 1 };
                            eligibleCards.sort((a, b) => (rarityOrder[b.rarity] || 0) - (rarityOrder[a.rarity] || 0));
                            let stolen = eligibleCards[0];
                            target.hand = target.hand.filter(c => c !== stolen);
                            owner.hand.push(stolen);
                            console.log("[CardGame v2] Trade critical success! Took:", stolen.name, "Roll:", tradeRoll);
                            pos.tradeResult = { accepted: true, critical: true, roll: rawRoll, total: tradeRoll, dc: tradeDC, received: [stolen] };
                        } else if (tradeRoll >= tradeDC && eligibleCards.length > 0) {
                            // Success: take random card from opponent's hand
                            let stolen = eligibleCards[Math.floor(Math.random() * eligibleCards.length)];
                            target.hand = target.hand.filter(c => c !== stolen);
                            owner.hand.push(stolen);
                            console.log("[CardGame v2] Trade success! Took:", stolen.name, "Roll:", tradeRoll, "vs DC", tradeDC);
                            pos.tradeResult = { accepted: true, roll: rawRoll, total: tradeRoll, dc: tradeDC, received: [stolen] };
                        } else {
                            let reason = eligibleCards.length === 0 ? "No tradeable cards" : "Trade failed";
                            console.log("[CardGame v2] Trade failed. Roll:", tradeRoll, "vs DC", tradeDC);
                            pos.tradeResult = { accepted: false, roll: rawRoll, total: tradeRoll, dc: tradeDC, reason };
                        }
                    }

                    // ── Steal action: like Trade but includes char stack, alignment modifier ──
                    if (card.name === "Steal") {
                        let stats = owner.character.stats || {};
                        let stealStat = Math.round(((stats.PER || 8) + (stats.CRE || 8) + (stats.WIS || 8)) / 3);
                        let skillMod = getStackSkillMod(pos.stack, "craft");

                        // Alignment modifier: Chaotic Evil = +4, Lawful Good = -4
                        let alignMods = {
                            "CHAOTIC EVIL": 4, "CHAOTIC_EVIL": 4,
                            "CHAOTIC NEUTRAL": 2, "CHAOTIC_NEUTRAL": 2,
                            "NEUTRAL EVIL": 2, "NEUTRAL_EVIL": 2,
                            "CHAOTIC GOOD": 0, "CHAOTIC_GOOD": 0,
                            "LAWFUL EVIL": 0, "LAWFUL_EVIL": 0,
                            "NEUTRAL": 0,
                            "NEUTRAL GOOD": -2, "NEUTRAL_GOOD": -2,
                            "LAWFUL NEUTRAL": -2, "LAWFUL_NEUTRAL": -2,
                            "LAWFUL GOOD": -4, "LAWFUL_GOOD": -4
                        };
                        let alignment = (owner.character.alignment || "NEUTRAL").toUpperCase();
                        let alignMod = alignMods[alignment] || 0;

                        let rawRoll = rollD20();
                        let stealRoll = rawRoll + stealStat + skillMod + alignMod;
                        let stealDC = 14;

                        // Eligible: opponent hand cards + equipped cards (char stack)
                        let handCards = target.hand.filter(c => c.type !== "character");
                        let stackCards = (target.cardStack || []).filter(c => c.type !== "character");
                        let allEligible = [...handCards, ...stackCards];

                        if (rawRoll === 20 && allEligible.length > 0) {
                            // Critical: take best card from any source
                            let rarityOrder = { LEGENDARY: 5, EPIC: 4, RARE: 3, UNCOMMON: 2, COMMON: 1 };
                            allEligible.sort((a, b) => (rarityOrder[b.rarity] || 0) - (rarityOrder[a.rarity] || 0));
                            let stolen = allEligible[0];
                            if (stackCards.includes(stolen)) {
                                target.cardStack = target.cardStack.filter(c => c !== stolen);
                            } else {
                                target.hand = target.hand.filter(c => c !== stolen);
                            }
                            owner.hand.push(stolen);
                            console.log("[CardGame v2] Steal critical! Took:", stolen.name, "Roll:", stealRoll, "(align:" + alignMod + ")");
                            pos.stealResult = { success: true, critical: true, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod, stolen };
                        } else if (stealRoll >= stealDC && allEligible.length > 0) {
                            let stolen = allEligible[Math.floor(Math.random() * allEligible.length)];
                            if (stackCards.includes(stolen)) {
                                target.cardStack = target.cardStack.filter(c => c !== stolen);
                            } else {
                                target.hand = target.hand.filter(c => c !== stolen);
                            }
                            owner.hand.push(stolen);
                            console.log("[CardGame v2] Steal success! Took:", stolen.name, "Roll:", stealRoll, "(align:" + alignMod + ")");
                            pos.stealResult = { success: true, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod, stolen };
                        } else if (rawRoll === 1) {
                            // Critical fail: caught! Lose morale and opponent gets bonus
                            owner.morale = Math.max(0, owner.morale - 3);
                            target.morale = Math.min(target.maxMorale, target.morale + 2);
                            console.log("[CardGame v2] Steal critical fail! Caught stealing. Roll:", stealRoll);
                            pos.stealResult = { success: false, critical: true, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod, reason: "Caught!" };
                        } else {
                            console.log("[CardGame v2] Steal failed. Roll:", stealRoll, "vs DC", stealDC);
                            pos.stealResult = { success: false, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod, reason: "Failed" };
                        }
                    }

                    // ── Craft action: AVG(CRE, WIS, DEX) roll for random item draw ──
                    if (card.name === "Craft") {
                        let stats = owner.character.stats || {};
                        let craftStat = Math.round(((stats.CRE || 8) + (stats.WIS || 8) + (stats.DEX || 8)) / 3);
                        let skillMod = getStackSkillMod(pos.stack, "craft");
                        let rawRoll = rollD20();
                        let craftRoll = rawRoll + craftStat + skillMod;
                        let craftDC = 12;

                        // Item pools for random draw
                        let commonItems = [
                            { type: "item", subtype: "consumable", name: "Health Potion", effect: "Restore 6 HP", rarity: "COMMON" },
                            { type: "item", subtype: "consumable", name: "Energy Tonic", effect: "Restore 4 Energy", rarity: "COMMON" },
                            { type: "item", subtype: "consumable", name: "Bandage", effect: "Restore 4 HP", rarity: "COMMON" },
                            { type: "item", subtype: "consumable", name: "Sharpening Stone", effect: "+2 to Attack rolls this round", rarity: "COMMON" },
                            { type: "item", subtype: "consumable", name: "Shield Polish", effect: "+2 to Defense this round", rarity: "COMMON" }
                        ];
                        let rareItems = [
                            { type: "item", subtype: "consumable", name: "Greater Health Potion", effect: "Restore 12 HP", rarity: "RARE" },
                            { type: "item", subtype: "consumable", name: "Elixir of Vigor", effect: "Restore 6 HP and 4 Energy", rarity: "RARE" },
                            { type: "item", subtype: "weapon", name: "Crafted Blade", atk: 3, damageType: "Slashing", slot: "Hand (1H)", rarity: "RARE" },
                            { type: "apparel", subtype: "armor", name: "Reinforced Vest", def: 2, hpBonus: 3, slot: "Body", rarity: "RARE" }
                        ];
                        let uniqueItems = [
                            { type: "item", subtype: "consumable", name: "Phoenix Feather", effect: "Restore to full HP when defeated (auto-use)", rarity: "LEGENDARY", flavor: "A shimmering feather that glows with inner fire" },
                            { type: "item", subtype: "weapon", name: "Masterwork Blade", atk: 5, damageType: "Slashing", slot: "Hand (1H)", rarity: "EPIC", special: "Keen: +2 crit range", flavor: "Forged with extraordinary skill" },
                            { type: "apparel", subtype: "armor", name: "Aegis Plate", def: 4, hpBonus: 5, slot: "Body", rarity: "EPIC", special: "Fortified: -1 incoming damage", flavor: "Nearly impervious craftsmanship" }
                        ];

                        if (rawRoll === 20) {
                            // Critical success: unique item
                            let item = uniqueItems[Math.floor(Math.random() * uniqueItems.length)];
                            item.flavor = (item.flavor || "") + " [Crafted by " + owner.name + "]";
                            owner.hand.push(item);
                            console.log("[CardGame v2] Craft critical! Created unique:", item.name, "Roll:", craftRoll);
                            pos.craftResult = { success: true, critical: true, roll: rawRoll, total: craftRoll, dc: craftDC, created: item };
                        } else if (craftRoll >= craftDC + 5) {
                            // High success: rare item
                            let item = rareItems[Math.floor(Math.random() * rareItems.length)];
                            owner.hand.push(item);
                            console.log("[CardGame v2] Craft great success! Created:", item.name, "Roll:", craftRoll);
                            pos.craftResult = { success: true, roll: rawRoll, total: craftRoll, dc: craftDC, created: item };
                        } else if (craftRoll >= craftDC) {
                            // Normal success: common item
                            let item = commonItems[Math.floor(Math.random() * commonItems.length)];
                            owner.hand.push(item);
                            console.log("[CardGame v2] Craft success! Created:", item.name, "Roll:", craftRoll, "vs DC", craftDC);
                            pos.craftResult = { success: true, roll: rawRoll, total: craftRoll, dc: craftDC, created: item };
                        } else {
                            console.log("[CardGame v2] Craft failed. Roll:", craftRoll, "vs DC", craftDC);
                            pos.craftResult = { success: false, roll: rawRoll, total: craftRoll, dc: craftDC };
                        }
                    }

                    // ── Talk action: INT + CHA roll, outcomes: flee/nothing/critical fail ──
                    if (card.type === "talk") {
                        if (pos.owner === "player") {
                            // Player Talk: Open chat UI (Phase 8)
                            console.log("[CardGame v2] Opening Talk chat. LLM available:", !!gameChatManager?.initialized);
                            if (openTalkChat) openTalkChat(card, bar.resolveIndex);
                            return;  // Don't auto-advance - chat will handle it
                        } else {
                            // Opponent Talk: INT + CHA combined roll
                            let ownerStats = owner.character.stats || {};
                            let targetStats = target.character.stats || {};
                            let talkStat = Math.round(((ownerStats.INT || 8) + (ownerStats.CHA || 8)) / 2);
                            let targetDefense = Math.round(((targetStats.INT || 8) + (targetStats.CHA || 8)) / 2);
                            let skillMod = getStackSkillMod(pos.stack, "talk");

                            let rawRoll = rollD20();
                            let talkRoll = rawRoll + talkStat + skillMod;
                            let defenseRoll = rollD20() + targetDefense;

                            if (rawRoll === 20 || talkRoll >= defenseRoll + 10) {
                                // Critical success: opponent flees / gives up
                                console.log("[CardGame v2]", pos.owner, "Talk critical! Opponent surrenders. Roll:", talkRoll, "vs", defenseRoll);
                                target.morale = 0;
                                gameState.winner = pos.owner;
                                gameState.phase = "GAME_OVER";
                                m.redraw();
                                return;
                            } else if (talkRoll > defenseRoll) {
                                // Success: morale damage
                                let moraleDmg = Math.max(1, Math.floor((talkRoll - defenseRoll) / 2));
                                target.morale = Math.max(0, target.morale - moraleDmg);
                                owner.morale = Math.min(owner.maxMorale, owner.morale + 1);
                                console.log("[CardGame v2]", pos.owner, "Talk success! Target morale -" + moraleDmg);

                                if (target.morale <= 0) {
                                    gameState.winner = pos.owner;
                                    gameState.phase = "GAME_OVER";
                                    m.redraw();
                                    return;
                                }
                            } else if (rawRoll === 1 || talkRoll <= defenseRoll - 10) {
                                // Critical fail: opponent gets upper hand
                                owner.morale = Math.max(0, owner.morale - 3);
                                target.morale = Math.min(target.maxMorale, target.morale + 2);
                                // Opponent draws an extra card
                                drawCardsForActor(target, 1);
                                console.log("[CardGame v2]", pos.owner, "Talk critical fail! Opponent gains upper hand. Morale -3, opponent draws card");
                            } else {
                                // Failure: nothing happens
                                console.log("[CardGame v2]", pos.owner, "Talk failed. Roll:", talkRoll, "vs", defenseRoll);
                            }
                        }
                    }

                    // ── Channel action: cast a spell from stacked magic modifier ──
                    if (card.name === "Channel") {
                        let magicMod = pos.stack.modifiers.find(m => m.type === "magic");
                        let ownerStats = owner.character.stats || {};
                        let magStat = ownerStats.MAG || 8;
                        let skillMod = getStackSkillMod(pos.stack, "magic");

                        if (magicMod) {
                            // Cast the stacked spell
                            let fizzled = false;
                            if (magicMod.requires) {
                                for (let stat in magicMod.requires) {
                                    if ((ownerStats[stat] || 0) < magicMod.requires[stat]) {
                                        fizzled = true;
                                        console.log("[CardGame v2] Spell fizzled!", magicMod.name, "requires", stat, magicMod.requires[stat], "but actor has", ownerStats[stat] || 0);
                                        break;
                                    }
                                }
                            }

                            if (!fizzled) {
                                let rawRoll = rollD20();
                                let channelRoll = rawRoll + magStat + skillMod;
                                console.log("[CardGame v2]", pos.owner, "channels", magicMod.name, "- Roll:", rawRoll, "+ MAG", magStat, "+ skill", skillMod, "=", channelRoll);

                                let parsed = parseEffect(magicMod.effect || "");
                                // Boost damage/healing by channel roll excess over 10
                                let bonus = Math.max(0, Math.floor((channelRoll - 10) / 5));
                                if (parsed.damage) parsed.damage += bonus;
                                if (parsed.healHp) parsed.healHp += bonus;

                                let log = applyParsedEffects(parsed, owner, target, magicMod.name);
                                log.forEach(msg => console.log("[CardGame v2]", pos.owner, "cast:", msg));

                                if (parsed.damage && target.hp <= 0) {
                                    gameState.winner = pos.owner;
                                    gameState.phase = "GAME_OVER";
                                    m.redraw();
                                    return;
                                }
                            }
                        } else {
                            // Channel without a spell: basic arcane burst (MAG/3 damage)
                            let rawRoll = rollD20();
                            let channelRoll = rawRoll + magStat + skillMod;
                            let baseDmg = Math.max(1, Math.floor(magStat / 3));
                            let bonus = Math.max(0, Math.floor((channelRoll - 10) / 5));
                            let totalDmg = baseDmg + bonus;
                            target.hp = Math.max(0, target.hp - totalDmg);
                            console.log("[CardGame v2]", pos.owner, "channels raw arcane energy for", totalDmg, "damage (roll:", channelRoll, ")");

                            if (target.hp <= 0) {
                                gameState.winner = pos.owner;
                                gameState.phase = "GAME_OVER";
                                m.redraw();
                                return;
                            }
                        }
                    }

                    // Magic card resolution (magic placed directly as core card)
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

    // ── End Turn / Auto-End Turn ────────────────────────────────────────
    function endTurn() {
        if (!gameState) return;
        const GAME_PHASES = C().GAME_PHASES;
        if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;

        // Switch turn
        gameState.currentTurn = gameState.currentTurn === "player" ? "opponent" : "player";

        // Check if the current player has 0 AP - auto-skip their turn
        checkAutoEndTurn();
    }

    function checkAutoEndTurn() {
        if (!gameState) return;
        const GAME_PHASES = C().GAME_PHASES;
        if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;

        let currentTurn = gameState.currentTurn;
        let current = currentTurn === "player" ? gameState.player : gameState.opponent;
        let positions = currentTurn === "player"
            ? gameState.initiative.playerPositions
            : gameState.initiative.opponentPositions;
        let apRemaining = current.ap - current.apUsed;

        console.log("[CardGame v2] checkAutoEndTurn:", currentTurn, "AP remaining:", apRemaining);

        // Check if current player can actually place any cards
        if (apRemaining > 0) {
            let isCoreCardType = E().isCoreCardType;
            let coreCards = current.hand.filter(c => isCoreCardType(c.type));
            let playableCores = coreCards.filter(c => !c.energyCost || c.energyCost <= current.energy);
            let emptyPositions = positions.filter(posIdx => {
                let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
                return pos && !pos.stack;
            });

            // Auto-forfeit AP if can't place anything
            if (playableCores.length === 0 || emptyPositions.length === 0) {
                console.log("[CardGame v2]", currentTurn, "can't place - playable cores:", playableCores.length,
                    "(total:", coreCards.length, ") empty:", emptyPositions.length, "energy:", current.energy);
                current.apUsed = current.ap;
                apRemaining = 0;
            }
        }

        if (apRemaining <= 0) {
            // No AP left, check if placement is complete or switch turns
            let checkPlacementComplete = AI()?.checkPlacementComplete;
            if (checkPlacementComplete) checkPlacementComplete();
            if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;  // Phase advanced

            // If still in placement, other player still has AP - switch to them
            let other = currentTurn === "player" ? gameState.opponent : gameState.player;
            if (other.apUsed < other.ap) {
                gameState.currentTurn = currentTurn === "player" ? "opponent" : "player";
                setTimeout(() => { checkAutoEndTurn(); m.redraw(); }, 300);
            }
        } else if (currentTurn === "opponent") {
            // Opponent has AP, let AI place cards
            let aiPlaceCards = AI()?.aiPlaceCards;
            setTimeout(() => {
                if (aiPlaceCards) aiPlaceCards();
                m.redraw();
            }, 500);
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
        triggerOpponentBanter,
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

        // Audio cleanup
        cleanupAudio,

        // Resolution driver
        advanceResolution,

        // Turn management
        endTurn,
        checkAutoEndTurn
    };

    console.log('[CardGame] State/gameState loaded (' +
        Object.keys(window.CardGame.GameState).length + ' exports)');

}());
