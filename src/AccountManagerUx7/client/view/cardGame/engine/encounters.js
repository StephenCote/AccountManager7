/**
 * CardGame Engine - Encounter & Threat System
 * Threat creature generation, critical rewards, scenario cards,
 * and beginning/end-of-round threat checks.
 *
 * Depends on: CardGame.Engine (effects, combat)
 * Exposes: window.CardGame.Engine.{loadEncounterData, loadBalanceData, getThreatCreatures,
 *   getScenarioCards, generateCriticalReward, createThreatEncounter, checkNat1Threats,
 *   insertBeginningThreats, drawScenarioCard, checkEndThreat, THREAT_CREATURES, SCENARIO_CARDS}
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Engine = window.CardGame.Engine || {};

    // ── Balance Data Loader ─────────────────────────────────────────────
    let balanceData = null;
    async function loadBalanceData() {
        if (balanceData) return balanceData;
        try {
            balanceData = await m.request({ method: "GET", url: "media/cardGame/game-balance.json" });
        } catch(e) {
            console.warn("[CardGame] Could not load game-balance.json, using defaults");
            balanceData = {};
        }
        return balanceData;
    }

    // ── Encounter Data (loaded from JSON) ─────────────────────────────
    // Defaults used if JSON fails to load
    let THREAT_CREATURES = [
        { name: "Wolf", type: "animal", atk: 2, def: 1, hp: 8, imageIcon: "pets",
          behavior: "Attacks lowest HP target", loot: ["Wolf Pelt"] },
        { name: "Goblin Scout", type: "npc", atk: 3, def: 1, hp: 10, imageIcon: "face",
          behavior: "Flanks weakest defender", loot: ["Goblin Dagger"] },
        { name: "Giant Spider", type: "animal", atk: 2, def: 2, hp: 12, imageIcon: "bug_report",
          behavior: "Poison on hit (2 turns)", loot: ["Spider Silk"] },
        { name: "Bandit", type: "npc", atk: 4, def: 2, hp: 15, imageIcon: "person_alert",
          behavior: "Steals item on crit", loot: ["Stolen Coin Pouch"] }
    ];

    let SCENARIO_CARDS = [
        { name: "Peaceful Respite", effect: "no_threat", description: "The area is calm.",
          icon: "park", cardColor: "#43A047", weight: 40 },
        { name: "Ambush!", effect: "threat", description: "Enemies emerge from hiding!",
          icon: "visibility_off", cardColor: "#C62828", threatBonus: 0, weight: 15 }
    ];

    let encounterDataLoaded = false;

    async function loadEncounterData() {
        if (encounterDataLoaded) return;
        try {
            let data = await m.request({ method: "GET", url: "media/cardGame/encounters.json" });
            if (data && data.threatCreatures) {
                THREAT_CREATURES = data.threatCreatures;
                window.CardGame.Engine.THREAT_CREATURES = THREAT_CREATURES;
            }
            if (data && data.scenarioCards) {
                SCENARIO_CARDS = data.scenarioCards;
                window.CardGame.Engine.SCENARIO_CARDS = SCENARIO_CARDS;
            }
            encounterDataLoaded = true;
            console.log("[CardGame] Encounter data loaded:", THREAT_CREATURES.length, "creatures,", SCENARIO_CARDS.length, "scenarios");
        } catch(e) {
            console.warn("[CardGame] Could not load encounters.json, using defaults");
            encounterDataLoaded = true;
        }
    }

    function getThreatCreatures() {
        return balanceData?.threatCreatures || THREAT_CREATURES;
    }

    function getScenarioCards() {
        return balanceData?.scenarioCards || SCENARIO_CARDS;
    }

    // ── Critical Rewards ────────────────────────────────────────────────

    /**
     * Generate a reward card for critical success (nat 20)
     * Tries to pull a rare/epic card from deck, falls back to stat bonus
     * @param {string} type - "attack" or "defense"
     * @param {Object} actor - The actor receiving the reward
     * @returns {Object} Generated reward card
     */
    function generateCriticalReward(type, actor) {
        // Try to find a rare/epic card from the draw pile
        let rarityOrder = ["LEGENDARY", "EPIC", "RARE", "UNCOMMON"];
        let drawPile = actor.drawPile || [];

        // Look for items/apparel matching the type
        let typeMatch = type === "attack"
            ? c => c.type === "item" && (c.subtype === "weapon" || c.atk > 0)
            : c => (c.type === "item" || c.type === "apparel") && (c.subtype === "armor" || c.def > 0);

        for (let rarity of rarityOrder) {
            let candidates = drawPile.filter(c =>
                c.rarity === rarity && typeMatch(c)
            );
            if (candidates.length > 0) {
                let reward = { ...candidates[Math.floor(Math.random() * candidates.length)] };
                // Remove from draw pile
                let idx = drawPile.findIndex(c => c.name === reward.name);
                if (idx >= 0) drawPile.splice(idx, 1);

                reward.id = "crit-reward-" + Date.now();
                reward.isCriticalReward = true;
                console.log("[CardGame v2] Critical reward from deck:", reward.name);
                return reward;
            }
        }

        // Fallback: generate a simple stat bonus consumable
        let bonus = type === "attack"
            ? { type: "item", subtype: "consumable", name: "Critical Bonus", atk: 3, rarity: "RARE",
                effect: "+3 ATK this round" }
            : { type: "item", subtype: "consumable", name: "Critical Bonus", def: 3, rarity: "RARE",
                effect: "+3 DEF this round" };

        bonus.id = "crit-reward-" + Date.now();
        bonus.isCriticalReward = true;
        console.log("[CardGame v2] Critical reward (fallback):", bonus.name);
        return bonus;
    }

    // ── Threat Encounter Creation ───────────────────────────────────────

    /**
     * Create a threat encounter based on difficulty
     * @param {number} difficulty - Threat difficulty (affects stats and loot)
     * @returns {Object} Threat encounter object
     */
    function createThreatEncounter(difficulty) {
        let creatures = getThreatCreatures();
        // Pick a creature based on difficulty
        let creatureIdx = Math.min(Math.floor(difficulty / 2), creatures.length - 1);
        let base = creatures[creatureIdx];

        // Scale stats based on difficulty
        let scaleFactor = 1 + (difficulty - 4) * 0.1;
        let threat = {
            type: "encounter",
            subtype: "threat",
            creatureType: base.type || "monster",
            name: base.name,
            difficulty: difficulty,
            atk: Math.round(base.atk * scaleFactor),
            def: Math.round(base.def * scaleFactor),
            hp: Math.round(base.hp * scaleFactor),
            maxHp: Math.round(base.hp * scaleFactor),
            imageIcon: base.imageIcon,
            behavior: base.behavior || "Attacks target",
            artPrompt: base.artPrompt || null,
            isThreat: true,

            // Loot items the threat drops when defeated
            lootItems: (base.loot || []).map(name => ({
                type: "item", subtype: "loot", name: name,
                rarity: difficulty <= 4 ? "COMMON" : difficulty <= 8 ? "UNCOMMON" : "RARE"
            })),

            // Action stack representing the threat's attack
            actionStack: {
                coreAction: "Attack",
                modifiers: difficulty >= 6 ? [{ name: "Ferocious", bonus: 1, type: "skill" }] : []
            }
        };

        // Determine loot rarity based on difficulty
        if (difficulty <= 4) {
            threat.lootRarity = "COMMON";
            threat.lootCount = 1;
        } else if (difficulty <= 8) {
            threat.lootRarity = "UNCOMMON";
            threat.lootCount = 1;
        } else {
            threat.lootRarity = "RARE";
            threat.lootCount = 2;
        }

        return threat;
    }

    // ── Beginning Threat Checks ─────────────────────────────────────────

    /**
     * Check for Nat 1 on initiative and create beginning threats
     * @param {Object} state - The current game state
     * @returns {Array} Array of threat objects with target info
     */
    function checkNat1Threats(state) {
        if (!state || !state.initiative) return [];

        let threats = [];
        let difficulty = state.round + 2;  // Beginning threat difficulty = round + 2

        // Check player for Nat 1
        if (state.initiative.playerRoll && state.initiative.playerRoll.raw === 1) {
            let threat = createThreatEncounter(difficulty);
            threat.target = "player";  // Threat attacks the fumbler
            threats.push(threat);
            console.log("[CardGame v2] BEGINNING THREAT! Player rolled Nat 1 - spawning", threat.name);
        }

        // Check opponent for Nat 1
        if (state.initiative.opponentRoll && state.initiative.opponentRoll.raw === 1) {
            let threat = createThreatEncounter(difficulty);
            threat.target = "opponent";  // Threat attacks the fumbler
            threats.push(threat);
            console.log("[CardGame v2] BEGINNING THREAT! Opponent rolled Nat 1 - spawning", threat.name);
        }

        // Max 2 beginning threats (one per player Nat 1)
        return threats.slice(0, 2);
    }

    /**
     * Store beginning threats for THREAT_RESPONSE phase
     * (No longer inserts into action bar - combat handled in separate phase)
     * @param {Object} state - The current game state
     * @param {Array} threats - Array of threat objects
     */
    function insertBeginningThreats(state, threats) {
        if (!state || !threats || threats.length === 0) return;

        // Store threats for THREAT_RESPONSE phase
        state.beginningThreats = threats;

        console.log("[CardGame v2] Beginning threats queued for response phase:", threats.length, "threats");
    }

    // ── Scenario Card Drawing ───────────────────────────────────────────

    /**
     * Draw a scenario card using weighted random selection
     * @returns {Object} Selected scenario card
     */
    function drawScenarioCard() {
        let cards = getScenarioCards();
        let totalWeight = cards.reduce((sum, card) => sum + card.weight, 0);
        let roll = Math.random() * totalWeight;
        let cumulative = 0;

        for (let card of cards) {
            cumulative += card.weight;
            if (roll <= cumulative) {
                return { ...card };
            }
        }
        return { ...cards[0] };  // Fallback
    }

    // ── End-of-Round Threat Check ───────────────────────────────────────

    /**
     * Check for end-of-round threat from scenario card
     * @param {Object} state - The current game state
     * @returns {Object|null} End threat info or null if no threat
     */
    function checkEndThreat(state) {
        let scenario = drawScenarioCard();
        console.log("[CardGame v2] Scenario card drawn:", scenario.name);

        if (scenario.effect === "threat") {
            let difficulty = state.round + 3 + (scenario.threatBonus || 0);
            let threat = createThreatEncounter(difficulty);
            // End threats target the round loser (or random on tie)
            if (state.roundWinner === "tie") {
                threat.target = Math.random() < 0.5 ? "player" : "opponent";
            } else {
                threat.target = state.roundWinner === "player" ? "opponent" : "player";
            }
            return {
                scenario: scenario,
                threat: threat
            };
        }

        return { scenario: scenario, threat: null };
    }

    // ── Export ───────────────────────────────────────────────────────────
    Object.assign(window.CardGame.Engine, {
        loadEncounterData,
        loadBalanceData,
        getThreatCreatures,
        getScenarioCards,
        THREAT_CREATURES,
        SCENARIO_CARDS,
        generateCriticalReward,
        createThreatEncounter,
        checkNat1Threats,
        insertBeginningThreats,
        drawScenarioCard,
        checkEndThreat
    });

    console.log('[CardGame] Engine/encounters loaded');

}());
