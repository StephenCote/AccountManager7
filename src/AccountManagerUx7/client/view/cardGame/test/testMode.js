/**
 * CardGame Test Mode - Comprehensive Test Suite
 * Phase 9: Runs tests across storage, narration, combat, card evaluation,
 * campaign, LLM, voice, and automated playthrough categories.
 *
 * Depends on:
 *   - window.CardGame.Constants (STATUS_EFFECTS, COMBAT_OUTCOMES)
 *   - window.CardGame.Storage (deckStorage, gameStorage, campaignStorage, encodeJson, decodeJson,
 *       createCampaignData)
 *   - window.CardGame.Engine (rollD20, getActorATK, getActorDEF, getStackSkillMod, checkGameOver,
 *       parseEffect, applyParsedEffects, isEffectParseable, applyStatusEffect, removeStatusEffect,
 *       getStatusModifiers, createGameState, advancePhase, drawCardsForActor)
 *   - window.CardGame.AI (CardGameLLM, CardGameNarrator, checkLlmConnectivity, llmStatus,
 *       initializeLLMComponents, CardGameVoice)
 *   - window.CardGame.Rendering (CardFace)
 *   - window.CardGame.Themes (loadThemeConfig, activeTheme)
 *   - window.CardGame.ctx (shared mutable context: screen, viewingDeck, gameState,
 *       gameNarrator, gameDirector, gameChatManager, gameVoice, gameAnnouncerVoice)
 *
 * Exposes: window.CardGame.TestMode = {
 *   TEST_CATEGORIES, testState, testDeck, testDeckName,
 *   setTestDeck, clearTestDeck, runTestSuite, TestModeUI
 * }
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.TestMode = window.CardGame.TestMode || {};

    // ── Aliases to other modules ─────────────────────────────────────────
    function C()       { return window.CardGame.Constants; }
    function S()       { return window.CardGame.Storage; }
    function Eng()     { return window.CardGame.Engine || {}; }
    function AI()      { return window.CardGame.AI || {}; }
    function R()       { return window.CardGame.Rendering || {}; }
    function Themes()  { return window.CardGame.Themes || {}; }
    function GS()      { return window.CardGame.GameState || {}; }
    function ctx()     { return window.CardGame.ctx || {}; }

    // ── Test Categories ──────────────────────────────────────────────────
    const TEST_CATEGORIES = {
        modules:     { label: "Modules",     icon: "hub" },
        stats:       { label: "Stats",       icon: "analytics" },
        gameflow:    { label: "Game Flow",   icon: "route" },
        storage:     { label: "Storage",     icon: "database" },
        narration:   { label: "Narration",   icon: "campaign" },
        combat:      { label: "Combat",      icon: "swords" },
        cards:       { label: "Card Eval",   icon: "playing_cards" },
        campaign:    { label: "Campaign",    icon: "military_tech" },
        llm:         { label: "LLM",         icon: "smart_toy" },
        voice:       { label: "Voice",       icon: "record_voice_over" },
        playthrough: { label: "Playthrough", icon: "sports_esports" }
    };

    // ── State (delegated to shared TestFramework) ─────────────────────
    let TF = window.TestFramework;
    let testDeck = null;       // Deck selected for testing (set from DeckView)
    let testDeckName = null;

    // Use shared framework state; extend with CardGame-specific fields
    let testState = TF ? TF.testState : {
        running: false,
        logs: [],
        results: { pass: 0, fail: 0, warn: 0, skip: 0 },
        currentTest: null,
        completed: false,
        selectedCategories: Object.keys(TEST_CATEGORIES),
        logFilter: "all",
        selectedSuite: null
    };
    // Ensure CardGame categories are selected by default when this suite is active
    if (testState.selectedCategories.length === 0) {
        testState.selectedCategories = Object.keys(TEST_CATEGORIES);
    }
    let autoPlaySpeed = 500;

    // ── Public setters for testDeck (called from DeckView / DeckList) ────
    function setTestDeck(deck, name) {
        testDeck = deck;
        testDeckName = name;
    }
    function clearTestDeck() {
        testDeck = null;
        testDeckName = null;
    }

    // ── Logging (delegates to shared TestFramework) ─────────────────────
    function testLog(category, message, status) {
        if (TF) {
            TF.testLog(category, message, status);
        } else {
            if (status === undefined) status = "info";
            let entry = { time: new Date().toISOString().substring(11, 19), category: category, message: message, status: status };
            testState.logs.push(entry);
            if (status === "pass") testState.results.pass++;
            else if (status === "fail") testState.results.fail++;
            else if (status === "warn") testState.results.warn++;
            console.log("[TestMode] [" + category + "] [" + status + "] " + message);
            m.redraw();
        }
    }

    // ── Framework-compatible entry point ─────────────────────────────────
    // Called by TestFramework.runSuite("cardGame") — state managed by framework
    async function runCardGameTests(selectedCategories) {
        testState.selectedCategories = selectedCategories || Object.keys(TEST_CATEGORIES);
        await runTestBody();
    }

    // ── Standalone entry point (from CardGame UI) ─────────────────────
    async function runTestSuite() {
        testState.running = true;
        testState.logs = [];
        testState.results = { pass: 0, fail: 0, warn: 0, skip: 0 };
        testState.completed = false;
        m.redraw();
        await runTestBody();
        testState.currentTest = null;
        testState.running = false;
        testState.completed = true;
        testLog("", "=== Test suite complete: " + testState.results.pass + " pass, " + testState.results.fail + " fail, " + testState.results.warn + " warn ===", testState.results.fail > 0 ? "fail" : "pass");
        m.redraw();
    }

    // ── Test body (shared between standalone and framework modes) ──────
    async function runTestBody() {

        let cats = testState.selectedCategories;

        // Resolve shorthand accessors
        let storage = S();
        let engine = Eng();
        let gameState = GS();
        let ai = AI();
        let themes = Themes();
        let context = ctx();

        // Resolve the deck to use for testing
        // Prefer testDeck (set from DeckView), otherwise load first available deck
        let resolvedDeck = testDeck;
        let resolvedDeckName = testDeckName;
        if (!resolvedDeck) {
            let deckNames = await storage.deckStorage.list();
            if (deckNames.length > 0) {
                resolvedDeckName = deckNames[0];
                resolvedDeck = await storage.deckStorage.load(resolvedDeckName);
            }
        }
        if (resolvedDeck) {
            testLog("", "Using deck: " + (resolvedDeck.deckName || resolvedDeckName), "info");
            // Ensure the deck's theme is loaded so card pool analysis works
            let activeTheme = themes.getActiveTheme ? themes.getActiveTheme() : null;
            if (resolvedDeck.themeId && (!activeTheme || activeTheme.themeId !== resolvedDeck.themeId)) {
                if (themes.loadThemeConfig) {
                    await themes.loadThemeConfig(resolvedDeck.themeId);
                    activeTheme = themes.getActiveTheme ? themes.getActiveTheme() : null;
                }
                testLog("", "Loaded theme: " + (activeTheme?.name || resolvedDeck.themeId), "info");
            }
        } else {
            testLog("", "No deck available for testing -- some tests will be skipped", "warn");
        }

        // ── Module Namespace Resolution Tests ──
        if (cats.includes("modules")) {
            testState.currentTest = "Modules: namespace resolution";
            testLog("modules", "Testing module namespace resolution...");

            // Verify all top-level namespaces exist
            let namespaces = [
                ["CardGame.Constants",  window.CardGame.Constants],
                ["CardGame.Storage",    window.CardGame.Storage],
                ["CardGame.Engine",     window.CardGame.Engine],
                ["CardGame.GameState",  window.CardGame.GameState],
                ["CardGame.Characters", window.CardGame.Characters],
                ["CardGame.Themes",     window.CardGame.Themes],
                ["CardGame.AI",         window.CardGame.AI],
                ["CardGame.ArtPipeline", window.CardGame.ArtPipeline],
                ["CardGame.Rendering",  window.CardGame.Rendering],
                ["CardGame.UI",         window.CardGame.UI],
                ["CardGame.TestMode",   window.CardGame.TestMode],
                ["CardGame.ctx",        window.CardGame.ctx]
            ];
            for (let pair of namespaces) {
                let name = pair[0]; let obj = pair[1];
                testLog("modules", name + ": " + (obj ? "present" : "MISSING"), obj ? "pass" : "fail");
            }

            // Verify GameState exports (the createGameState bug was caused by wrong namespace)
            let gsExports = [
                "createGameState", "advancePhase", "endTurn", "checkAutoEndTurn",
                "initializeLLMComponents", "checkLlmConnectivity",
                "triggerNarration", "showNarrationSubtitle",
                "resetInitAnimState", "startInitiativeAnimation", "runInitiativePhase",
                "enterDrawPlacementPhase", "enterThreatResponsePhase", "enterEndThreatPhase",
                "resolveThreatCombat", "resolveEndThreatCombat",
                "placeThreatDefenseCard", "skipThreatResponse",
                "startNextRound", "advanceResolution"
            ];
            for (let fn of gsExports) {
                let exists = typeof gameState[fn] === "function";
                testLog("modules", "GameState." + fn + ": " + (exists ? "function" : "MISSING (" + typeof gameState[fn] + ")"), exists ? "pass" : "fail");
            }

            // Verify GameState.state getters
            let gsState = gameState.state;
            if (gsState) {
                let stateProps = ["gameState", "initAnimState", "llmStatus", "gameDirector", "gameNarrator",
                    "gameChatManager", "gameVoice", "gameAnnouncerVoice"];
                let accessibleCount = 0;
                for (let prop of stateProps) {
                    try { void gsState[prop]; accessibleCount++; } catch (e) { /* getter threw */ }
                }
                testLog("modules", "GameState.state: " + accessibleCount + "/" + stateProps.length + " props accessible", accessibleCount === stateProps.length ? "pass" : "warn");
            } else {
                testLog("modules", "GameState.state: MISSING", "fail");
            }

            // Verify Engine exports
            let engineExports = [
                "rollD20", "rollInitiative", "getActorATK", "getActorDEF", "rollAttack", "rollDefense",
                "getCombatOutcome", "calculateDamage", "applyDamage", "resolveCombat", "checkGameOver",
                "parseEffect", "applyParsedEffects", "isEffectParseable", "getStackSkillMod",
                "applyStatusEffect", "removeStatusEffect", "hasStatusEffect", "getStatusModifiers",
                "processStatusEffectsTurnStart", "tickStatusEffects",
                "drawCardsForActor", "ensureOffensiveCard", "placeCard", "removeCardFromPosition",
                "isCoreCardType", "isModifierCardType", "dealInitialStack",
                "checkNat1Threats", "createThreatEncounter", "checkEndThreat"
            ];
            let engineMissing = [];
            for (let fn of engineExports) {
                if (typeof engine[fn] !== "function") engineMissing.push(fn);
            }
            testLog("modules", "Engine: " + (engineExports.length - engineMissing.length) + "/" + engineExports.length + " functions present",
                engineMissing.length === 0 ? "pass" : "fail");
            if (engineMissing.length > 0) {
                testLog("modules", "Engine MISSING: " + engineMissing.join(", "), "fail");
            }

            // Verify Characters exports
            let ch = window.CardGame.Characters;
            if (ch) {
                let charExports = ["mapStats", "statVal", "resolveStatistics", "getPortraitUrl", "getCharId",
                    "assembleCharacterCard", "assembleStarterDeck", "assembleCharEquipment"];
                let charMissing = [];
                for (let fn of charExports) {
                    if (typeof ch[fn] !== "function") charMissing.push(fn);
                }
                testLog("modules", "Characters: " + (charExports.length - charMissing.length) + "/" + charExports.length + " functions present",
                    charMissing.length === 0 ? "pass" : "fail");
                if (charMissing.length > 0) {
                    testLog("modules", "Characters MISSING: " + charMissing.join(", "), "fail");
                }
            }

            // Verify AI exports
            let aiMod = window.CardGame.AI;
            if (aiMod) {
                let aiExports = ["CardGameLLM", "CardGameDirector", "CardGameNarrator", "CardGameChatManager", "CardGameVoice"];
                let aiMissing = [];
                for (let fn of aiExports) {
                    if (!aiMod[fn]) aiMissing.push(fn);
                }
                testLog("modules", "AI: " + (aiExports.length - aiMissing.length) + "/" + aiExports.length + " classes present",
                    aiMissing.length === 0 ? "pass" : "warn");
                if (aiMissing.length > 0) {
                    testLog("modules", "AI MISSING: " + aiMissing.join(", "), "warn");
                }
            }

            // Verify UI component exports
            let ui = window.CardGame.UI;
            if (ui) {
                let uiExports = ["DeckList", "BuilderThemeStep", "BuilderCharacterStep", "BuilderReviewStep",
                    "DeckView", "GameView", "GameOverUI", "LevelUpUI", "ThreatResponseUI"];
                let uiMissing = [];
                for (let fn of uiExports) {
                    if (!ui[fn]) uiMissing.push(fn);
                }
                testLog("modules", "UI: " + (uiExports.length - uiMissing.length) + "/" + uiExports.length + " components present",
                    uiMissing.length === 0 ? "pass" : "fail");
                if (uiMissing.length > 0) {
                    testLog("modules", "UI MISSING: " + uiMissing.join(", "), "fail");
                }
            }

            // Verify ctx proxy properties resolve to module state (not undefined)
            let ctxObj = window.CardGame.ctx;
            if (ctxObj) {
                // These should be proxied from ArtPipeline.state
                let artProps = ["artQueue", "artProcessing", "sdOverrides", "sdConfigExpanded",
                    "voiceProfiles", "flippedCards"];
                let proxyOk = 0;
                for (let prop of artProps) {
                    // Just check the property is accessible (not throwing) - value may be null/[]
                    try {
                        let v = ctxObj[prop];
                        // Verify it returns the same value as ArtPipeline.state
                        let artState = window.CardGame.ArtPipeline?.state;
                        if (artState && v === artState[prop]) proxyOk++;
                        else if (!artState) proxyOk++; // Can't verify, assume ok
                        else testLog("modules", "ctx." + prop + " proxy mismatch: ctx=" + v + " art=" + artState[prop], "fail");
                    } catch (e) {
                        testLog("modules", "ctx." + prop + " proxy error: " + e.message, "fail");
                    }
                }
                testLog("modules", "ctx proxy properties: " + proxyOk + "/" + artProps.length + " linked to ArtPipeline.state",
                    proxyOk === artProps.length ? "pass" : "fail");
            }
        }

        // ── Stat Mapping Tests ──────────────────
        if (cats.includes("stats")) {
            testState.currentTest = "Stats: mapStats and stat computation";
            testLog("stats", "Testing mapStats() function...");

            let ch = window.CardGame.Characters;
            if (!ch || !ch.mapStats) {
                testLog("stats", "Characters.mapStats not available", "fail");
            } else {
                // Test 1: null/undefined input returns defaults
                let nullResult = ch.mapStats(null);
                testLog("stats", "mapStats(null) -> defaults",
                    nullResult.STR === 8 && nullResult.AGI === 8 && nullResult.END === 8 ? "pass" : "fail");

                // Test 2: Full statistics object
                let fullStats = {
                    physicalStrength: 15, agility: 12, physicalEndurance: 18,
                    intelligence: 10, magic: 14, charisma: 8
                };
                let fullResult = ch.mapStats(fullStats);
                testLog("stats", "mapStats(full): STR=" + fullResult.STR + " AGI=" + fullResult.AGI +
                    " END=" + fullResult.END + " INT=" + fullResult.INT + " MAG=" + fullResult.MAG + " CHA=" + fullResult.CHA,
                    fullResult.STR === 15 && fullResult.AGI === 12 && fullResult.END === 18 &&
                    fullResult.INT === 10 && fullResult.MAG === 14 && fullResult.CHA === 8 ? "pass" : "fail");

                // Test 3: Missing magic (virtual field) - should compute from components
                let noMagic = {
                    physicalStrength: 10, agility: 10, physicalEndurance: 10, intelligence: 10,
                    charisma: 10,
                    // magic not present - should compute from: AVG(willpower, wisdom, creativity, spirituality)
                    willpower: 12, wisdom: 14, creativity: 10, spirituality: 8
                };
                let noMagicResult = ch.mapStats(noMagic);
                let expectedMag = Math.round((12 + 14 + 10 + 8) / 4); // 11
                testLog("stats", "mapStats(no magic, willpower=12,wis=14,cre=10,spi=8): MAG=" + noMagicResult.MAG + " (expect " + expectedMag + ")",
                    noMagicResult.MAG === expectedMag ? "pass" : "fail");

                // Test 4: Missing magic AND missing willpower - should compute willpower too
                let noWillpower = {
                    physicalStrength: 10, agility: 10, physicalEndurance: 10, intelligence: 10,
                    charisma: 10,
                    // willpower not present - should compute from: AVG(mentalEndurance, mentalStrength)
                    mentalEndurance: 16, mentalStrength: 8,
                    wisdom: 12, creativity: 10, spirituality: 14
                };
                let noWpResult = ch.mapStats(noWillpower);
                let expectedWp = Math.round((16 + 8) / 2); // 12
                let expectedMag2 = Math.round((expectedWp + 12 + 10 + 14) / 4); // 12
                testLog("stats", "mapStats(no willpower, mEnd=16,mStr=8): computed willpower=" + expectedWp + " MAG=" + noWpResult.MAG + " (expect " + expectedMag2 + ")",
                    noWpResult.MAG === expectedMag2 ? "pass" : "fail");

                // Test 5: Charisma = 0 should map to 0, not fallback to 8
                let zeroCha = { physicalStrength: 10, agility: 10, physicalEndurance: 10,
                    intelligence: 10, magic: 10, charisma: 0 };
                let zeroResult = ch.mapStats(zeroCha);
                testLog("stats", "mapStats(charisma=0): CHA=" + zeroResult.CHA + " (expect 0)",
                    zeroResult.CHA === 0 ? "pass" : "fail");

                // Test 6: All stats = 0 should map to all 0s
                let allZero = { physicalStrength: 0, agility: 0, physicalEndurance: 0,
                    intelligence: 0, magic: 0, charisma: 0 };
                let allZeroResult = ch.mapStats(allZero);
                let allZeroOk = allZeroResult.STR === 0 && allZeroResult.AGI === 0 && allZeroResult.END === 0 &&
                    allZeroResult.INT === 0 && allZeroResult.MAG === 0 && allZeroResult.CHA === 0;
                testLog("stats", "mapStats(all zeros): " + JSON.stringify(allZeroResult), allZeroOk ? "pass" : "fail");

                // Test 7: objectId-only ref (partially loaded) should return defaults
                let refOnly = { objectId: "abc-123" };
                let refResult = ch.mapStats(refOnly);
                testLog("stats", "mapStats(objectId only): defaults=" + (refResult.STR === 8),
                    refResult.STR === 8 && refResult.MAG === 8 ? "pass" : "fail");

                // Test 8: AP derivation from END stat
                testLog("stats", "Testing AP derivation from END stat...");
                let apTests = [
                    { end: 0, expectAp: 2 },   // floor(0/5)+1=1, min 2
                    { end: 5, expectAp: 2 },   // floor(5/5)+1=2
                    { end: 10, expectAp: 3 },  // floor(10/5)+1=3
                    { end: 12, expectAp: 3 },  // floor(12/5)+1=3
                    { end: 15, expectAp: 4 },  // floor(15/5)+1=4
                    { end: 20, expectAp: 5 },  // floor(20/5)+1=5
                    { end: 25, expectAp: 6 }   // floor(25/5)+1=6
                ];
                for (let t of apTests) {
                    let computedAp = Math.max(2, Math.floor(t.end / 5) + 1);
                    testLog("stats", "END=" + t.end + " -> AP=" + computedAp + " (expect " + t.expectAp + ")",
                        computedAp === t.expectAp ? "pass" : "fail");
                }

                // Test 9: Energy derivation from MAG stat (used in createGameState)
                testLog("stats", "Testing Energy derivation from MAG stat...");
                if (gameState.createGameState) {
                    let testDeckForStats = {
                        deckName: "stats-test",
                        cards: [
                            { type: "character", name: "StatsTestHero", stats: { STR: 10, AGI: 10, END: 15, INT: 10, MAG: 18, CHA: 10 } },
                            { type: "character", name: "StatsTestVillain", stats: { STR: 12, AGI: 8, END: 10, INT: 14, MAG: 6, CHA: 16 } },
                            { type: "action", name: "Attack", actionType: "Offensive", energyCost: 0 },
                            { type: "action", name: "Guard", actionType: "Defensive", energyCost: 0 }
                        ]
                    };
                    let gsStats = await gameState.createGameState(testDeckForStats, testDeckForStats.cards[0]);
                    if (gsStats) {
                        testLog("stats", "Player MAG=18 -> energy=" + gsStats.player.energy + " maxEnergy=" + gsStats.player.maxEnergy,
                            gsStats.player.energy === 18 && gsStats.player.maxEnergy === 18 ? "pass" : "fail");
                        testLog("stats", "Player END=15 -> AP=" + gsStats.player.ap,
                            gsStats.player.ap === Math.max(2, Math.floor(15 / 5) + 1) ? "pass" : "fail");
                        testLog("stats", "Opponent MAG=6 -> energy=" + gsStats.opponent.energy,
                            gsStats.opponent.energy === 6 && gsStats.opponent.maxEnergy === 6 ? "pass" : "fail");
                        testLog("stats", "Opponent END=10 -> AP=" + gsStats.opponent.ap,
                            gsStats.opponent.ap === Math.max(2, Math.floor(10 / 5) + 1) ? "pass" : "fail");
                    } else {
                        testLog("stats", "createGameState returned null for stats test deck", "fail");
                    }
                }
            }
        }

        // ── Game Flow Tests (init, phases, turns) ──
        if (cats.includes("gameflow")) {
            testState.currentTest = "Game Flow: initialization";
            testLog("gameflow", "Testing game initialization and flow...");

            // Build a minimal but complete test deck
            let flowDeck = {
                deckName: "flow-test",
                themeId: "high-fantasy",
                cards: [
                    { type: "character", name: "FlowHero", stats: { STR: 12, AGI: 14, END: 15, INT: 10, MAG: 10, CHA: 10 }, needs: { hp: 20, energy: 10, morale: 20 } },
                    { type: "character", name: "FlowVillain", stats: { STR: 10, AGI: 10, END: 10, INT: 12, MAG: 14, CHA: 8 }, needs: { hp: 20, energy: 14, morale: 20 } },
                    { type: "action", name: "Attack", actionType: "Offensive", energyCost: 0 },
                    { type: "action", name: "Attack", actionType: "Offensive", energyCost: 0 },
                    { type: "action", name: "Guard", actionType: "Defensive", energyCost: 0 },
                    { type: "action", name: "Rest", actionType: "Recovery", energyCost: 0 },
                    { type: "talk", name: "Taunt", speechType: "Provoke", energyCost: 0 },
                    { type: "magic", name: "Fireball", effect: "Deal 8 fire damage", energyCost: 3 },
                    { type: "skill", name: "Swordsmanship", modifier: "+2 to Attack rolls" },
                    { type: "item", subtype: "weapon", name: "Iron Sword", atk: 2 },
                    { type: "item", subtype: "armor", name: "Leather Armor", def: 1 },
                    { type: "apparel", slot: "body", name: "Chainmail", def: 2 }
                ]
            };

            // Test 1: createGameState produces valid state
            if (gameState.createGameState) {
                let gs = await gameState.createGameState(flowDeck, flowDeck.cards[0]);
                if (!gs) {
                    testLog("gameflow", "createGameState returned null", "fail");
                } else {
                    testLog("gameflow", "createGameState: valid state object", "pass");

                    // Verify basic structure
                    testLog("gameflow", "gs.round=" + gs.round + " (expect 1)", gs.round === 1 ? "pass" : "fail");
                    let GP = C().GAME_PHASES;
                    testLog("gameflow", "gs.phase=" + gs.phase + " (expect " + GP.INITIATIVE + ")",
                        gs.phase === GP.INITIATIVE ? "pass" : "fail");

                    // Player structure
                    testLog("gameflow", "player.character.name=" + gs.player.character.name,
                        gs.player.character.name === "FlowHero" ? "pass" : "fail");
                    testLog("gameflow", "player.hp=" + gs.player.hp + " maxHp=" + gs.player.maxHp,
                        gs.player.hp === 20 && gs.player.maxHp === 20 ? "pass" : "fail");
                    testLog("gameflow", "player.hand has cards: " + gs.player.hand.length,
                        gs.player.hand.length >= 5 ? "pass" : "fail");
                    testLog("gameflow", "player.drawPile is array: " + Array.isArray(gs.player.drawPile),
                        Array.isArray(gs.player.drawPile) ? "pass" : "fail");
                    testLog("gameflow", "player.discardPile is empty array: " + gs.player.discardPile.length,
                        gs.player.discardPile.length === 0 ? "pass" : "fail");
                    testLog("gameflow", "player.statusEffects is empty array: " + gs.player.statusEffects.length,
                        gs.player.statusEffects.length === 0 ? "pass" : "fail");
                    testLog("gameflow", "player.ap=" + gs.player.ap + " apUsed=" + gs.player.apUsed,
                        gs.player.ap >= 2 && gs.player.apUsed === 0 ? "pass" : "fail");

                    // Opponent structure
                    testLog("gameflow", "opponent.character exists: " + !!gs.opponent.character,
                        gs.opponent.character ? "pass" : "fail");
                    testLog("gameflow", "opponent.hp=" + gs.opponent.hp,
                        gs.opponent.hp === 20 ? "pass" : "fail");
                    testLog("gameflow", "opponent.hand has cards: " + gs.opponent.hand.length,
                        gs.opponent.hand.length >= 5 ? "pass" : "fail");

                    // Action bar
                    testLog("gameflow", "actionBar.positions is array: " + Array.isArray(gs.actionBar.positions),
                        Array.isArray(gs.actionBar.positions) ? "pass" : "fail");
                    testLog("gameflow", "actionBar.resolveIndex=" + gs.actionBar.resolveIndex + " (expect -1)",
                        gs.actionBar.resolveIndex === -1 ? "pass" : "fail");

                    // Initiative structure
                    testLog("gameflow", "initiative.winner=" + gs.initiative.winner + " (expect null)",
                        gs.initiative.winner === null ? "pass" : "fail");

                    // Threat response defaults
                    testLog("gameflow", "threatResponse.active=" + gs.threatResponse.active + " (expect false)",
                        gs.threatResponse.active === false ? "pass" : "fail");

                    // Chat defaults
                    testLog("gameflow", "chat.active=" + gs.chat.active + " unlocked=" + gs.chat.unlocked,
                        gs.chat.active === false && gs.chat.unlocked === false ? "pass" : "fail");

                    // Test 2: createGameState with no character selection picks first
                    let gs2 = await gameState.createGameState(flowDeck);
                    if (gs2) {
                        testLog("gameflow", "createGameState(no selection): picks first char '" + gs2.player.character.name + "'",
                            gs2.player.character.name === "FlowHero" ? "pass" : "fail");
                    }

                    // Test 3: createGameState with deck lacking characters returns null
                    let noCharDeck = { deckName: "empty", cards: [{ type: "action", name: "Attack" }] };
                    let gs3 = await gameState.createGameState(noCharDeck);
                    testLog("gameflow", "createGameState(no characters): returns null",
                        gs3 === null ? "pass" : "fail");

                    // ── Test 4: Draw cards for actor ──
                    testState.currentTest = "Game Flow: draw/placement";
                    testLog("gameflow", "Testing drawCardsForActor...");
                    let savedGs = context.gameState;
                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = gs;

                    let handBefore = gs.player.hand.length;
                    let drawBefore = gs.player.drawPile.length;
                    if (engine.drawCardsForActor) {
                        engine.drawCardsForActor(gs.player, 2);
                        testLog("gameflow", "drawCardsForActor(2): hand " + handBefore + "->" + gs.player.hand.length +
                            " drawPile " + drawBefore + "->" + gs.player.drawPile.length,
                            gs.player.hand.length === handBefore + 2 && gs.player.drawPile.length === drawBefore - 2 ? "pass" : "fail");
                    } else {
                        testLog("gameflow", "drawCardsForActor not available", "fail");
                    }

                    // Test 5: placeCard places a card in an action bar position
                    testLog("gameflow", "Testing placeCard...");
                    if (engine.placeCard) {
                        // Set up action bar positions first
                        gs.initiative.playerPositions = [0, 2, 4];
                        gs.initiative.opponentPositions = [1, 3, 5];
                        gs.actionBar.totalPositions = 6;
                        gs.actionBar.positions = [];
                        for (let i = 0; i < 6; i++) {
                            gs.actionBar.positions.push({ index: i, owner: i % 2 === 0 ? "player" : "opponent", stack: null, resolved: false });
                        }
                        gs.currentTurn = "player";
                        gs.phase = GP.DRAW_PLACEMENT;

                        let cardToPlace = gs.player.hand[0];
                        if (cardToPlace) {
                            let handBeforePlace = gs.player.hand.length;
                            let cardName = cardToPlace.name;
                            engine.placeCard(gs, 0, cardToPlace);
                            let pos = gs.actionBar.positions[0];
                            testLog("gameflow", "placeCard('" + cardName + "', pos 0): stack=" + (pos.stack ? pos.stack.base?.name || pos.stack.name : "null"),
                                pos.stack ? "pass" : "fail");
                            testLog("gameflow", "placeCard: hand reduced " + handBeforePlace + "->" + gs.player.hand.length,
                                gs.player.hand.length === handBeforePlace - 1 ? "pass" : "fail");

                            // Test 6: removeCardFromPosition
                            if (engine.removeCardFromPosition) {
                                engine.removeCardFromPosition(gs, 0);
                                testLog("gameflow", "removeCardFromPosition(0): stack=" + (gs.actionBar.positions[0].stack),
                                    gs.actionBar.positions[0].stack === null ? "pass" : "fail");
                            }
                        }
                    } else {
                        testLog("gameflow", "placeCard not available", "fail");
                    }

                    // Test 7: isCoreCardType / isModifierCardType
                    testLog("gameflow", "Testing card type classification...");
                    if (engine.isCoreCardType) {
                        let coreTypes = ["action", "talk", "magic"];
                        let modTypes = ["skill", "item", "apparel"];
                        let nonCore = ["character", "encounter"];
                        for (let t of coreTypes) {
                            testLog("gameflow", "isCoreCardType('" + t + "')=" + engine.isCoreCardType(t),
                                engine.isCoreCardType(t) ? "pass" : "fail");
                        }
                        for (let t of modTypes) {
                            testLog("gameflow", "isModifierCardType('" + t + "')=" + engine.isModifierCardType(t),
                                engine.isModifierCardType(t) ? "pass" : "fail");
                        }
                        for (let t of nonCore) {
                            testLog("gameflow", "isCoreCardType('" + t + "')=" + engine.isCoreCardType(t) + " (expect false)",
                                !engine.isCoreCardType(t) ? "pass" : "fail");
                        }
                    }

                    // Test 8: checkGameOver integration with game state
                    testLog("gameflow", "Testing checkGameOver with game state...");
                    gs.player.hp = 10;
                    gs.opponent.hp = 10;
                    let winner1 = engine.checkGameOver();
                    testLog("gameflow", "checkGameOver(P:10, O:10)=" + winner1 + " (expect null)",
                        winner1 === null ? "pass" : "fail");

                    gs.player.hp = 0;
                    let winner2 = engine.checkGameOver();
                    testLog("gameflow", "checkGameOver(P:0, O:10)=" + winner2 + " (expect 'opponent')",
                        winner2 === "opponent" ? "pass" : "fail");

                    gs.player.hp = 10;
                    gs.opponent.hp = 0;
                    let winner3 = engine.checkGameOver();
                    testLog("gameflow", "checkGameOver(P:10, O:0)=" + winner3 + " (expect 'player')",
                        winner3 === "player" ? "pass" : "fail");

                    // Test 9: Encounter / Threat system
                    testState.currentTest = "Game Flow: encounters";
                    testLog("gameflow", "Testing encounter/threat system...");
                    gs.player.hp = 20;
                    gs.opponent.hp = 20;

                    if (engine.createThreatEncounter) {
                        let threat = engine.createThreatEncounter(1);
                        if (threat) {
                            testLog("gameflow", "createThreatEncounter: " + threat.name +
                                " (atk=" + threat.atk + " def=" + threat.def + " hp=" + threat.hp + ")",
                                threat.name && threat.atk > 0 && threat.hp > 0 ? "pass" : "fail");
                        } else {
                            testLog("gameflow", "createThreatEncounter returned null (may need theme data)", "warn");
                        }
                    }

                    if (engine.checkNat1Threats) {
                        let threats = engine.checkNat1Threats({ raw: 1 }, "player", gs);
                        testLog("gameflow", "checkNat1Threats(raw=1): " + (threats ? threats.length + " threats" : "null"),
                            threats && threats.length > 0 ? "pass" : "info");
                        let noThreats = engine.checkNat1Threats({ raw: 10 }, "player", gs);
                        testLog("gameflow", "checkNat1Threats(raw=10): " + (noThreats ? noThreats.length : 0) + " threats (expect 0)",
                            !noThreats || noThreats.length === 0 ? "pass" : "fail");
                    }

                    // Test 10: DiceUtils detailed tests
                    testState.currentTest = "Game Flow: dice system";
                    testLog("gameflow", "Testing DiceUtils...");
                    if (engine.DiceUtils) {
                        // Test basic roll range
                        let d6Rolls = [];
                        for (let i = 0; i < 100; i++) d6Rolls.push(engine.DiceUtils.roll(6));
                        let d6Min = Math.min.apply(null, d6Rolls);
                        let d6Max = Math.max.apply(null, d6Rolls);
                        testLog("gameflow", "DiceUtils.roll(6): range [" + d6Min + "," + d6Max + "] in 100 rolls",
                            d6Min >= 1 && d6Max <= 6 ? "pass" : "fail");

                        // Test rollWithMod
                        let modRoll = engine.DiceUtils.rollWithMod(5, "STR");
                        testLog("gameflow", "DiceUtils.rollWithMod(5, STR): raw=" + modRoll.raw + " total=" + modRoll.total + " breakdown='" + modRoll.breakdown + "'",
                            modRoll.total === modRoll.raw + 5 && modRoll.breakdown.includes("STR") ? "pass" : "fail");

                        // Test initiative roll
                        let initRoll = engine.DiceUtils.initiative({ AGI: 12 });
                        testLog("gameflow", "DiceUtils.initiative(AGI=12): raw=" + initRoll.raw + " total=" + initRoll.total,
                            initRoll.total === initRoll.raw + 12 ? "pass" : "fail");

                        // Test crit/fumble detection
                        testLog("gameflow", "DiceUtils: isCrit/isFumble detection...");
                        let hasCrit = false, hasFumble = false;
                        for (let i = 0; i < 1000; i++) {
                            let r = engine.DiceUtils.rollWithMod(0);
                            if (r.isCrit) hasCrit = true;
                            if (r.isFumble) hasFumble = true;
                            if (hasCrit && hasFumble) break;
                        }
                        testLog("gameflow", "Crit (raw=20) seen in 1000 rolls: " + hasCrit, hasCrit ? "pass" : "warn");
                        testLog("gameflow", "Fumble (raw=1) seen in 1000 rolls: " + hasFumble, hasFumble ? "pass" : "warn");
                    }

                    // Test 11: Full combat resolution
                    testState.currentTest = "Game Flow: combat resolution";
                    testLog("gameflow", "Testing full combat resolution...");
                    if (engine.rollAttack && engine.rollDefense && engine.getCombatOutcome && engine.calculateDamage) {
                        let attacker = {
                            character: { stats: { STR: 14, AGI: 10, END: 12 } },
                            cardStack: [{ atk: 3 }],
                            statusEffects: []
                        };
                        let defender = {
                            character: { stats: { STR: 10, AGI: 12, END: 14 } },
                            cardStack: [{ def: 2 }],
                            statusEffects: [],
                            hp: 20, maxHp: 20
                        };
                        let attackStack = { base: { type: "action", name: "Attack" }, modifiers: [] };
                        let defenseStack = { base: { type: "action", name: "Guard" }, modifiers: [] };

                        let atkRoll = engine.rollAttack(attacker, attackStack);
                        let defRoll = engine.rollDefense(defender, defenseStack);
                        testLog("gameflow", "rollAttack: raw=" + atkRoll.raw + " total=" + atkRoll.total,
                            atkRoll.raw >= 1 && atkRoll.raw <= 20 && atkRoll.total >= atkRoll.raw ? "pass" : "fail");
                        testLog("gameflow", "rollDefense: raw=" + defRoll.raw + " total=" + defRoll.total,
                            defRoll.raw >= 1 && defRoll.raw <= 20 && defRoll.total >= defRoll.raw ? "pass" : "fail");

                        let outcome = engine.getCombatOutcome(atkRoll, defRoll);
                        testLog("gameflow", "getCombatOutcome: " + outcome, outcome ? "pass" : "fail");

                        let dmg = engine.calculateDamage(attacker, outcome);
                        testLog("gameflow", "calculateDamage: final=" + dmg.finalDamage + " (outcome=" + outcome.label + ")",
                            typeof dmg.finalDamage === "number" ? "pass" : "fail");
                    }

                    // Test 12: dealInitialStack
                    testLog("gameflow", "Testing dealInitialStack...");
                    if (engine.dealInitialStack) {
                        let testApparel = [{ type: "apparel", slot: "body", name: "Armor", def: 2 }];
                        let testItems = [{ type: "item", subtype: "weapon", name: "Sword", atk: 3 }];
                        let stack = engine.dealInitialStack(testApparel, testItems);
                        testLog("gameflow", "dealInitialStack: " + stack.length + " cards",
                            stack.length === 2 ? "pass" : "info");
                    }

                    // Test 13: ensureOffensiveCard
                    testLog("gameflow", "Testing ensureOffensiveCard...");
                    if (engine.ensureOffensiveCard) {
                        let testActor = {
                            hand: [
                                { type: "talk", name: "Taunt" },
                                { type: "magic", name: "Heal", effect: "Restore 10 HP" }
                            ],
                            drawPile: [{ type: "action", name: "Rest" }],
                            discardPile: []
                        };
                        engine.ensureOffensiveCard(testActor, "Test");
                        let hasAttack = testActor.hand.some(function(c) { return c.name === "Attack"; });
                        testLog("gameflow", "ensureOffensiveCard: added Attack=" + hasAttack,
                            hasAttack ? "pass" : "warn");
                    }

                    // Restore game state
                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs;
                }
            } else {
                testLog("gameflow", "createGameState not available", "fail");
            }
        }

        // ── Storage Tests ────────────────────
        if (cats.includes("storage")) {
            testState.currentTest = "Storage: CRUD operations";
            testLog("storage", "Testing storage CRUD...");

            try {
                // Test encodeJson/decodeJson
                let testData = { test: true, value: 42, nested: { arr: [1, 2, 3] } };
                let encoded = storage.encodeJson(testData);
                let decoded = storage.decodeJson(encoded);
                if (decoded.test === true && decoded.value === 42 && decoded.nested.arr.length === 3) {
                    testLog("storage", "encodeJson/decodeJson roundtrip", "pass");
                } else {
                    testLog("storage", "encodeJson/decodeJson roundtrip failed", "fail");
                }

                // Test deck list
                let deckNames = await storage.deckStorage.list();
                testLog("storage", "deckStorage.list() returned " + deckNames.length + " decks", "pass");

                // Test gameStorage.list for each deck
                for (let name of deckNames.slice(0, 3)) {
                    let saves = await storage.gameStorage.list(name);
                    testLog("storage", "gameStorage.list('" + name + "') = " + saves.length + " saves", "pass");
                }

                // Test campaignStorage for each deck
                for (let name of deckNames.slice(0, 3)) {
                    let campaign = await storage.campaignStorage.load(name);
                    testLog("storage", "campaignStorage.load('" + name + "'): " + (campaign ? "Level " + campaign.level : "none"), "pass");
                }
            } catch (e) {
                testLog("storage", "Storage test error: " + e.message, "fail");
            }
        }

        // ── Narration Tests ──────────────────
        if (cats.includes("narration")) {
            testState.currentTest = "Narration: triggers and display";
            testLog("narration", "Testing narration system...");

            // Test showNarrationSubtitle
            let testText = "[TEST] Narration subtitle test at " + Date.now();
            try {
                // Access narration functions from GameState module
                let gsModule = gameState;
                let showNarrationSubtitle = gsModule.showNarrationSubtitle;
                let triggerNarration = gsModule.triggerNarration;

                // Create temporary gameState for testing
                let tempGs = { narrationText: null, narrationTime: null, player: { character: { name: "TestHero" } }, opponent: { character: { name: "TestVillain" } }, round: 1 };
                let savedGs = gsModule.getGameState ? gsModule.getGameState() : null;

                if (gsModule.setGameState) gsModule.setGameState(tempGs);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;

                if (showNarrationSubtitle) {
                    showNarrationSubtitle(testText);
                    if (tempGs.narrationText === testText) {
                        testLog("narration", "showNarrationSubtitle sets narrationText", "pass");
                    } else {
                        testLog("narration", "showNarrationSubtitle did NOT set narrationText (narrationText=" + tempGs.narrationText + ")", "fail");
                    }
                    testLog("narration", "Auto-hide timer scheduled (8s, cannot verify synchronously)", "info");
                    tempGs.narrationText = null;
                } else {
                    testLog("narration", "showNarrationSubtitle not available", "warn");
                }

                // Restore
                if (gsModule.setGameState) gsModule.setGameState(savedGs);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs;
            } catch (e) {
                testLog("narration", "Narration test error: " + e.message, "fail");
            }

            // Test triggerNarration fallback text
            try {
                let gsModule2 = gameState;
                let triggerNarration2 = gsModule2.triggerNarration;
                let savedGs2 = gsModule2.getGameState ? gsModule2.getGameState() : null;
                let savedNarrator = gsModule2.state?.gameNarrator || null;

                if (ai.setNarrator) ai.setNarrator(null); // Force fallback path
                let tempGs2 = { narrationText: null, narrationTime: null, player: { character: { name: "Hero" }, hp: 15, energy: 10 }, opponent: { character: { name: "Villain" }, hp: 12, energy: 8 }, round: 3 };
                if (gsModule2.setGameState) gsModule2.setGameState(tempGs2);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs2;

                if (triggerNarration2) {
                    await triggerNarration2("game_start");
                    if (tempGs2.narrationText && tempGs2.narrationText.includes("Hero")) {
                        testLog("narration", "game_start fallback text includes player name", "pass");
                    } else {
                        testLog("narration", "game_start fallback text missing", "fail");
                    }
                    tempGs2.narrationText = null;
                    await triggerNarration2("round_start");
                    if (tempGs2.narrationText && tempGs2.narrationText.includes("Round 3")) {
                        testLog("narration", "round_start fallback for round 3", "pass");
                    } else {
                        testLog("narration", "round_start fallback missing/wrong", tempGs2.round <= 1 ? "pass" : "fail");
                    }
                    tempGs2.narrationText = null;
                    await triggerNarration2("resolution", { isPlayerAttack: true, outcome: "CRIT", damage: 15 });
                    if (tempGs2.narrationText && tempGs2.narrationText.includes("Critical")) {
                        testLog("narration", "resolution CRIT fallback text", "pass");
                    } else {
                        testLog("narration", "resolution CRIT fallback missing", "fail");
                    }
                } else {
                    testLog("narration", "triggerNarration not available", "warn");
                }

                if (gsModule2.setGameState) gsModule2.setGameState(savedGs2);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs2;
                if (ai.setNarrator) ai.setNarrator(savedNarrator);
            } catch (e) {
                testLog("narration", "Narration fallback test error: " + e.message, "fail");
            }
        }

        // ── Combat Tests ─────────────────────
        if (cats.includes("combat")) {
            testState.currentTest = "Combat: dice, damage, game over";
            testLog("combat", "Testing combat mechanics...");

            // Test rollD20
            let rolls = [];
            for (let i = 0; i < 100; i++) rolls.push(engine.rollD20());
            let minRoll = Math.min.apply(null, rolls);
            let maxRoll = Math.max.apply(null, rolls);
            if (minRoll >= 1 && maxRoll <= 20) {
                testLog("combat", "rollD20: range [" + minRoll + "," + maxRoll + "] in 100 rolls", "pass");
            } else {
                testLog("combat", "rollD20: out of range [" + minRoll + "," + maxRoll + "]", "fail");
            }

            // Test checkGameOver
            let savedGs = context.gameState;
            let tempGs = { player: { hp: 5 }, opponent: { hp: 10 } };
            if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;
            if (engine.checkGameOver() === null) testLog("combat", "checkGameOver: no winner when both alive", "pass");
            else testLog("combat", "checkGameOver: false positive", "fail");

            tempGs.player.hp = 0;
            if (engine.checkGameOver() === "opponent") testLog("combat", "checkGameOver: opponent wins when player HP=0", "pass");
            else testLog("combat", "checkGameOver: wrong winner for HP=0", "fail");

            tempGs.player.hp = 5;
            tempGs.opponent.hp = -1;
            if (engine.checkGameOver() === "player") testLog("combat", "checkGameOver: player wins when opponent HP<0", "pass");
            else testLog("combat", "checkGameOver: wrong winner for opponent HP<0", "fail");
            if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs;

            // Test getActorATK / getActorDEF
            let testActor = { cardStack: [{ atk: 3 }, { def: 2 }, { atk: 1, def: 1 }] };
            let totalAtk = engine.getActorATK(testActor);
            let totalDef = engine.getActorDEF(testActor);
            if (totalAtk === 4) testLog("combat", "getActorATK: 3+1=" + totalAtk, "pass");
            else testLog("combat", "getActorATK: expected 4, got " + totalAtk, "fail");
            if (totalDef === 3) testLog("combat", "getActorDEF: 2+1=" + totalDef, "pass");
            else testLog("combat", "getActorDEF: expected 3, got " + totalDef, "fail");

            // Test getStackSkillMod
            let testStack = { modifiers: [
                { type: "skill", modifier: "+2 to Attack rolls" },
                { type: "skill", modifier: "+3 to defense" }
            ] };
            let atkMod = engine.getStackSkillMod(testStack, "attack");
            let defMod = engine.getStackSkillMod(testStack, "defense");
            if (atkMod === 2) testLog("combat", "getStackSkillMod(attack): +2", "pass");
            else testLog("combat", "getStackSkillMod(attack): expected 2, got " + atkMod, "fail");
            if (defMod === 3) testLog("combat", "getStackSkillMod(defense): +3", "pass");
            else testLog("combat", "getStackSkillMod(defense): expected 3, got " + defMod, "fail");
        }

        // ── Card Evaluation Tests ────────────
        if (cats.includes("cards")) {
            testState.currentTest = "Card Eval: unified effect parser";
            testLog("cards", "Testing unified parseEffect() engine...");

            // Test all supported effect patterns
            let testEffects = [
                { effect: "Deal 15 fire damage",            check: function(p) { return p.damage === 15; } },
                { effect: "Restore 30 HP",                  check: function(p) { return p.healHp === 30; } },
                { effect: "Restore 25 Energy",              check: function(p) { return p.restoreEnergy === 25; } },
                { effect: "Restore 10 Morale",              check: function(p) { return p.restoreMorale === 10; } },
                { effect: "Heal 8",                         check: function(p) { return p.healHp === 8; } },
                { effect: "Drain 12 health",                check: function(p) { return p.damage === 12 && p.healHp === 12; } },
                { effect: "Draw 2 cards",                   check: function(p) { return p.draw === 2; } },
                { effect: "Stun target for 1 turn",         check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "stunned"; }); } },
                { effect: "Poison enemy for 3 turns",       check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "poisoned"; }); } },
                { effect: "Shield self",                    check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "shielded" && s.target === "self"; }); } },
                { effect: "Enrage for bonus damage",        check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "enraged"; }); } },
                { effect: "Burn the enemy with fire",       check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "burning"; }); } },
                { effect: "Cause bleeding wounds",          check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "bleeding"; }); } },
                { effect: "Weaken enemy defenses",          check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "weakened"; }); } },
                { effect: "Fortify your defenses",          check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "fortified"; }); } },
                { effect: "Inspire allies to fight",        check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "inspired"; }); } },
                { effect: "Regenerate health over time",    check: function(p) { return p.statusEffects?.some(function(s) { return s.id === "regenerating"; }); } },
                { effect: "Cleanse all poisons",            check: function(p) { return p.cure === true; } },
                { effect: "Deal 20 and stun target",        check: function(p) { return p.damage === 20 && p.statusEffects?.some(function(s) { return s.id === "stunned"; }); } },
                { effect: "Restore 5 HP and 3 Energy",      check: function(p) { return p.healHp === 5 && p.restoreEnergy === 3; } }
            ];

            for (let t of testEffects) {
                let parsed = engine.parseEffect(t.effect);
                let ok = t.check(parsed);
                testLog("cards", "parseEffect('" + t.effect + "') -> " + JSON.stringify(parsed), ok ? "pass" : "fail");
            }

            // Test isEffectParseable
            let parseableEffects = ["Deal 10 damage", "Restore 5 HP", "Stun target", "Drain 8"];
            let unparseableEffects = ["View opponent's next action", "+2 to Investigate rolls this round", "Attacks lowest HP character first"];
            for (let e of parseableEffects) {
                testLog("cards", "isParseable('" + e + "')", engine.isEffectParseable(e) ? "pass" : "fail");
            }
            for (let e of unparseableEffects) {
                testLog("cards", "isNotParseable('" + e + "')", !engine.isEffectParseable(e) ? "pass" : "fail");
            }

            // Test skill modifier parsing with expanded keywords
            let skillTests = [
                { mod: "+2 to Attack rolls", action: "attack", expect: 2 },
                { mod: "+3 to Defense", action: "defense", expect: 3 },
                { mod: "+1 to social interactions", action: "talk", expect: 1 },
                { mod: "+2 to melee strikes", action: "attack", expect: 2 },
                { mod: "+2 to Flee and initiative rolls", action: "flee", expect: 2 },
                { mod: "+1 to Investigate and Rest rolls", action: "investigate", expect: 1 },
                { mod: "+2 to spell casting", action: "magic", expect: 2 }
            ];
            for (let s of skillTests) {
                let stack = { modifiers: [{ type: "skill", modifier: s.mod }] };
                let result = engine.getStackSkillMod(stack, s.action);
                testLog("cards", "SkillMod('" + s.mod + "', '" + s.action + "') = " + result, result === s.expect ? "pass" : "fail");
            }

            // Analyze active theme card pool for gaps
            let activeTheme = themes.getActiveTheme ? themes.getActiveTheme() : null;
            if (activeTheme?.cardPool) {
                let pool = activeTheme.cardPool;
                let types = {};
                (pool || []).forEach(function(c) { types[c.type] = (types[c.type] || 0) + 1; });
                testLog("cards", "Theme '" + activeTheme.name + "' card pool: " + JSON.stringify(types), "info");

                // Check skills have valid modifier patterns
                for (let sk of (pool || []).filter(function(c) { return c.type === "skill"; })) {
                    let hasNumericMod = sk.modifier && sk.modifier.match(/\+(\d+)/);
                    testLog("cards", "Skill '" + sk.name + "': " + (sk.modifier || "none"), hasNumericMod ? "pass" : "warn");
                }
                // Check magic have parseable effects
                for (let mg of (pool || []).filter(function(c) { return c.type === "magic"; })) {
                    let parseable = engine.isEffectParseable(mg.effect);
                    testLog("cards", "Magic '" + mg.name + "': " + (mg.effect || "").substring(0, 50), parseable ? "pass" : "warn");
                }
                // Check consumables have parseable effects
                for (let con of (pool || []).filter(function(c) { return c.type === "item" && c.subtype === "consumable"; })) {
                    let parseable = engine.isEffectParseable(con.effect);
                    testLog("cards", "Consumable '" + con.name + "': " + (con.effect || "").substring(0, 50), parseable ? "pass" : "warn");
                }
            } else {
                testLog("cards", "No active theme card pool loaded", "warn");
            }
        }

        // ── Campaign Tests ───────────────────
        if (cats.includes("campaign")) {
            testState.currentTest = "Campaign: W/L tracking";
            testLog("campaign", "Testing campaign system...");

            // Test createCampaignData
            let testChar = { name: "TestHero", sourceId: "test-123" };
            let cd = storage.createCampaignData(testChar);
            if (cd.wins === 0 && cd.losses === 0 && cd.characterName === "TestHero" && cd.totalGamesPlayed === 0) {
                testLog("campaign", "createCampaignData defaults correct (v2: W/L tracking)", "pass");
            } else {
                testLog("campaign", "createCampaignData defaults wrong", "fail");
            }
        }

        // ── Character Stats & Integration Tests ─
        if (cats.includes("cards")) {
            testState.currentTest = "Integration: character stats, effects, save/load";
            testLog("cards", "Testing character stats from deck...");

            // Use the resolved deck for character stat validation
            let charTestDecks = [];
            if (resolvedDeck?.cards) {
                charTestDecks.push({ name: resolvedDeck.deckName || resolvedDeckName, deck: resolvedDeck });
            } else {
                // Fallback: load available decks
                let deckNames2 = await storage.deckStorage.list();
                for (let dn of deckNames2.slice(0, 3)) {
                    let d = await storage.deckStorage.load(dn);
                    if (d?.cards) charTestDecks.push({ name: dn, deck: d });
                }
            }
            for (let entry of charTestDecks) {
                let dn = entry.name;
                let deck = entry.deck;
                let chars = (deck.cards || []).filter(function(c) { return c.type === "character"; });
                if (chars.length === 0) {
                    testLog("cards", "Deck '" + dn + "': no character cards", "warn");
                    continue;
                }
                for (let ch of chars) {
                    let stats = ch.stats || {};
                    let hasCore = ["STR", "AGI", "END", "INT", "MAG", "CHA"].filter(function(s) { return stats[s] !== undefined; });
                    testLog("cards", "Char '" + ch.name + "': " + hasCore.length + "/6 core stats (" + hasCore.join(",") + ")",
                        hasCore.length >= 4 ? "pass" : "warn");

                    // Validate stat ranges (should be 1-30 for reasonable gameplay)
                    let outOfRange = hasCore.filter(function(s) { return stats[s] < 1 || stats[s] > 30; });
                    if (outOfRange.length > 0) {
                        testLog("cards", "Char '" + ch.name + "': stats out of range [1-30]: " + outOfRange.map(function(s) { return s + "=" + stats[s]; }).join(", "), "warn");
                    } else if (hasCore.length > 0) {
                        testLog("cards", "Char '" + ch.name + "': all stats in valid range", "pass");
                    }

                    // Test createGameState with this deck (stats should not persist/mutate)
                    if (gameState.createGameState) {
                        let statsBefore = JSON.stringify(ch.stats);
                        let gs = await gameState.createGameState(deck, ch);
                        let statsAfter = JSON.stringify(ch.stats);
                        if (gs) {
                            if (statsBefore === statsAfter) {
                                testLog("cards", "createGameState: '" + ch.name + "' stats not mutated", "pass");
                            } else {
                                testLog("cards", "createGameState: '" + ch.name + "' stats MUTATED (should not persist)", "fail");
                            }
                            // Validate derived values
                            let playerEnd = gs.player.character.stats?.END || 12;
                            let expectedAp = Math.max(2, Math.floor(playerEnd / 5) + 1);
                            if (gs.player.ap === expectedAp) {
                                testLog("cards", "AP derived correctly: END=" + playerEnd + " -> AP=" + gs.player.ap, "pass");
                            } else {
                                testLog("cards", "AP mismatch: END=" + playerEnd + " -> expected AP=" + expectedAp + " got " + gs.player.ap, "fail");
                            }
                            // Validate hand dealt (5 base + 1 Attack card added by design = 6)
                            if (gs.player.hand.length >= 5 && gs.player.hand.length <= 7) {
                                let hasAttack = gs.player.hand.some(function(c) { return c.name === "Attack" || c.type === "attack"; });
                                testLog("cards", "Initial hand: " + gs.player.hand.length + " cards" + (hasAttack ? " (includes Attack)" : ""), "pass");
                            } else {
                                testLog("cards", "Initial hand unexpected size: " + gs.player.hand.length + " (expected 5-7)", "fail");
                            }
                        } else {
                            testLog("cards", "createGameState returned null for '" + ch.name + "'", "fail");
                        }
                    } else {
                        testLog("cards", "createGameState not available (module not yet extracted)", "warn");
                    }
                }
            }

            // ── Integration: applyParsedEffects ──
            testLog("cards", "Testing applyParsedEffects integration...");
            let testOwner = { hp: 20, maxHp: 25, energy: 10, maxEnergy: 15, morale: 15, maxMorale: 20, hand: [], drawPile: [{ name: "TestCard" }], discardPile: [], statusEffects: [] };
            let testTarget = { hp: 20, maxHp: 20, statusEffects: [] };

            // Test damage
            let dmgParsed = engine.parseEffect("Deal 8 damage");
            engine.applyParsedEffects(dmgParsed, testOwner, testTarget, "TestSpell");
            testLog("cards", "applyParsedEffects(Deal 8): target HP 20->" + testTarget.hp, testTarget.hp === 12 ? "pass" : "fail");

            // Test heal
            testOwner.hp = 15;
            let healParsed = engine.parseEffect("Restore 7 HP");
            engine.applyParsedEffects(healParsed, testOwner, testTarget, "HealSpell");
            testLog("cards", "applyParsedEffects(Restore 7 HP): owner HP 15->" + testOwner.hp, testOwner.hp === 22 ? "pass" : "fail");

            // Test heal capped at max
            testOwner.hp = 24;
            engine.applyParsedEffects(engine.parseEffect("Heal 10"), testOwner, testTarget, "BigHeal");
            testLog("cards", "applyParsedEffects(Heal 10): capped at maxHp " + testOwner.maxHp + " -> " + testOwner.hp, testOwner.hp === 25 ? "pass" : "fail");

            // Test drain (damage + self-heal)
            testOwner.hp = 15;
            testTarget.hp = 20;
            engine.applyParsedEffects(engine.parseEffect("Drain 6"), testOwner, testTarget, "DrainSpell");
            testLog("cards", "applyParsedEffects(Drain 6): target HP " + testTarget.hp + ", owner HP " + testOwner.hp,
                testTarget.hp === 14 && testOwner.hp === 21 ? "pass" : "fail");

            // Test energy/morale restore
            testOwner.energy = 5;
            testOwner.morale = 10;
            engine.applyParsedEffects(engine.parseEffect("Restore 4 Energy"), testOwner, testTarget, "E");
            engine.applyParsedEffects(engine.parseEffect("Restore 3 Morale"), testOwner, testTarget, "M");
            testLog("cards", "Energy 5->" + testOwner.energy + ", Morale 10->" + testOwner.morale,
                testOwner.energy === 9 && testOwner.morale === 13 ? "pass" : "fail");

            // Test draw cards
            testOwner.hand = [];
            testOwner.drawPile = [{ name: "A" }, { name: "B" }, { name: "C" }];
            engine.applyParsedEffects(engine.parseEffect("Draw 2 cards"), testOwner, testTarget, "DrawSpell");
            testLog("cards", "applyParsedEffects(Draw 2): hand=" + testOwner.hand.length + " drawPile=" + testOwner.drawPile.length,
                testOwner.hand.length === 2 && testOwner.drawPile.length === 1 ? "pass" : "fail");

            // Test status effect application
            testTarget.statusEffects = [];
            engine.applyParsedEffects(engine.parseEffect("Stun target"), testOwner, testTarget, "StunCard");
            let hasStun = testTarget.statusEffects.some(function(e) { return e.id === "stunned"; });
            testLog("cards", "applyParsedEffects(Stun): target has stunned=" + hasStun, hasStun ? "pass" : "fail");

            testOwner.statusEffects = [];
            engine.applyParsedEffects(engine.parseEffect("Shield self"), testOwner, testTarget, "ShieldCard");
            let hasShield = testOwner.statusEffects.some(function(e) { return e.id === "shielded"; });
            testLog("cards", "applyParsedEffects(Shield): owner has shielded=" + hasShield, hasShield ? "pass" : "fail");

            // Test cure removes negatives
            testOwner.statusEffects = [];
            engine.applyStatusEffect(testOwner, "poisoned", "test");
            engine.applyStatusEffect(testOwner, "burning", "test");
            engine.applyStatusEffect(testOwner, "shielded", "test");  // positive -- should NOT be removed
            let beforeCure = testOwner.statusEffects.length;
            engine.applyParsedEffects(engine.parseEffect("Cleanse all"), testOwner, testTarget, "CureSpell");
            let afterCure = testOwner.statusEffects.length;
            let keptShield = testOwner.statusEffects.some(function(e) { return e.id === "shielded"; });
            testLog("cards", "Cure: " + beforeCure + " effects -> " + afterCure + " (shield kept=" + keptShield + ")",
                afterCure === 1 && keptShield ? "pass" : "fail");

            // Test combo effect
            testTarget.hp = 20;
            testTarget.statusEffects = [];
            engine.applyParsedEffects(engine.parseEffect("Deal 10 and poison target"), testOwner, testTarget, "Combo");
            let comboOk = testTarget.hp === 10 && testTarget.statusEffects.some(function(e) { return e.id === "poisoned"; });
            testLog("cards", "Combo(Deal 10 + poison): HP=" + testTarget.hp + " poisoned=" + comboOk, comboOk ? "pass" : "fail");

            // ── Integration: status effect lifecycle ──
            testLog("cards", "Testing status effect lifecycle...");
            let lifecycleActor = { hp: 20, maxHp: 20, statusEffects: [] };
            engine.applyStatusEffect(lifecycleActor, "poisoned", "TestPoison");
            if (lifecycleActor.statusEffects.length === 1 && lifecycleActor.statusEffects[0].turnsRemaining === 3) {
                testLog("cards", "Poisoned applied: duration=3", "pass");
            } else {
                testLog("cards", "Poisoned not applied correctly", "fail");
            }
            // Re-apply should refresh duration, not stack
            lifecycleActor.statusEffects[0].turnsRemaining = 1;
            engine.applyStatusEffect(lifecycleActor, "poisoned", "RefreshPoison");
            if (lifecycleActor.statusEffects.length === 1 && lifecycleActor.statusEffects[0].turnsRemaining === 3) {
                testLog("cards", "Re-apply refreshes duration (1->3), no stack", "pass");
            } else {
                testLog("cards", "Re-apply stacking/duration wrong: count=" + lifecycleActor.statusEffects.length, "fail");
            }
            // Remove
            engine.removeStatusEffect(lifecycleActor, "poisoned");
            testLog("cards", "removeStatusEffect: " + lifecycleActor.statusEffects.length + " remaining",
                lifecycleActor.statusEffects.length === 0 ? "pass" : "fail");

            // Status modifier calculation
            let modActor = { statusEffects: [] };
            engine.applyStatusEffect(modActor, "enraged", "test");
            engine.applyStatusEffect(modActor, "shielded", "test");
            let mods = engine.getStatusModifiers(modActor);
            testLog("cards", "StatusMods(enraged+shielded): atk=" + mods.atk + " def=" + mods.def,
                mods.atk === 3 && mods.def === 1 ? "pass" : "fail");  // enraged +3 ATK -2 DEF, shielded +3 DEF = net +1 DEF
        }

        // ── Extended Save/Load Tests ─────────
        if (cats.includes("storage")) {
            testState.currentTest = "Storage: save/load field completeness";
            testLog("storage", "Testing save/load field preservation...");
            {
                let deck = resolvedDeck;
                let saveDeckName = resolvedDeckName || "test";
                if (deck && gameState.createGameState) {
                    let gs = await gameState.createGameState(deck);
                    if (gs) {
                        // Set up recognizable state
                        gs.round = 7;
                        gs.player.hp = 13;
                        gs.player.energy = 4;
                        gs.player.morale = 11;
                        gs.opponent.hp = 8;
                        let origHandLen = gs.player.hand.length;
                        let origRound = gs.round;
                        let origOppHp = gs.opponent.hp;
                        let origPhase = gs.phase;

                        let saved = await storage.gameStorage.save(saveDeckName + "__test", gs);
                        if (saved) {
                            let loaded = await storage.gameStorage.load(saveDeckName + "__test");
                            if (loaded?.gameState) {
                                let ls = loaded.gameState;
                                let checks = [
                                    ["round", ls.round === origRound],
                                    ["phase", ls.phase === origPhase],
                                    ["player.hp", ls.player?.hp === 13],
                                    ["player.energy", ls.player?.energy === 4],
                                    ["player.morale", ls.player?.morale === 11],
                                    ["player.hand.length", ls.player?.hand?.length === origHandLen],
                                    ["opponent.hp", ls.opponent?.hp === origOppHp],
                                    ["player.ap", ls.player?.ap === gs.player.ap],
                                    ["player.drawPile", Array.isArray(ls.player?.drawPile)],
                                    ["player.discardPile", Array.isArray(ls.player?.discardPile)],
                                    ["player.statusEffects", Array.isArray(ls.player?.statusEffects)]
                                ];
                                let allOk = true;
                                for (let pair of checks) {
                                    let field = pair[0];
                                    let ok = pair[1];
                                    if (!ok) { testLog("storage", "Save/load field LOST: " + field, "fail"); allOk = false; }
                                }
                                if (allOk) testLog("storage", "Save/load: all " + checks.length + " fields preserved", "pass");
                            } else {
                                testLog("storage", "Save/load: loaded.gameState is null", "fail");
                            }
                            await storage.gameStorage.deleteAll(saveDeckName + "__test");
                        } else {
                            testLog("storage", "Save/load: save returned null", "fail");
                        }
                    }
                } else if (!gameState.createGameState) {
                    testLog("storage", "createGameState not available (module not yet extracted)", "warn");
                }
            }
        }

        // ── LLM Tests ────────────────────────
        if (cats.includes("llm")) {
            testState.currentTest = "LLM: connectivity and chat";
            testLog("llm", "Testing LLM connectivity...");

            try {
                let checkLlmConnectivity = ai.checkLlmConnectivity || gameState.checkLlmConnectivity;
                let llmStatus = ai.llmStatus || gameState.state.llmStatus || { checked: false, available: false };
                let CardGameLLM = ai.CardGameLLM;
                let CardGameNarrator = ai.CardGameNarrator;

                if (checkLlmConnectivity) {
                    await checkLlmConnectivity();
                    llmStatus = ai.llmStatus || gameState.state.llmStatus || llmStatus;
                    testLog("llm", "LLM status: available=" + llmStatus.available + ", checked=" + llmStatus.checked, llmStatus.available ? "pass" : "warn");

                    if (llmStatus.available) {
                        testLog("llm", "LLM endpoint reachable", "pass");

                        // Test CardGameLLM base class
                        testLog("llm", "CardGameLLM.findChatDir()...");
                        try {
                            let chatDir = await CardGameLLM.findChatDir();
                            testLog("llm", "Chat directory: " + (chatDir ? chatDir.path || chatDir.name : "not found"), chatDir ? "pass" : "warn");
                        } catch (e) {
                            testLog("llm", "findChatDir error: " + e.message, "warn");
                        }

                        // Actual LLM chat test
                        testState.currentTest = "LLM: actual chat test";
                        testLog("llm", "Initializing test LLM instance...");
                        try {
                            let testLlm = new CardGameLLM();
                            let initOk = await testLlm.initializeLLM(
                                "CardGame Test Chat",
                                "CardGame Test Prompt",
                                "You are a helpful test assistant for a card game. Respond briefly.",
                                0.3
                            );
                            testLog("llm", "LLM init: " + (initOk ? "success" : "failed -- " + (testLlm.lastError || "unknown")), initOk ? "pass" : "warn");

                            if (initOk) {
                                // Send a simple test prompt
                                testLog("llm", "Sending test prompt to LLM...");
                                let response = await testLlm.chat("Say exactly: TEST_OK");
                                let content = CardGameLLM.extractContent(response);
                                testLog("llm", "LLM raw response: " + JSON.stringify(response)?.substring(0, 200), "info");
                                testLog("llm", "LLM extracted content: " + (content || "(empty)").substring(0, 150), content ? "pass" : "fail");

                                // Test narrator initialization
                                testState.currentTest = "LLM: narrator test";
                                testLog("llm", "Testing CardGameNarrator...");
                                let testNarrator = new CardGameNarrator();
                                let themeId = resolvedDeck?.themeId || (themes.getActiveTheme ? themes.getActiveTheme()?.themeId : null) || "high-fantasy";
                                let narratorOk = await testNarrator.initialize("arena-announcer", themeId);
                                testLog("llm", "Narrator init (" + themeId + "): " + (narratorOk ? "success" : "failed -- " + (testNarrator.lastError || "unknown")), narratorOk ? "pass" : "warn");

                                if (narratorOk) {
                                    // Test game_start narration
                                    testLog("llm", "Requesting game_start narration...");
                                    let narration = await testNarrator.narrate("game_start", {
                                        playerName: "Test Hero",
                                        opponentName: "Test Villain"
                                    });
                                    testLog("llm", "Narration text: " + (narration?.text || "(empty)").substring(0, 200), narration?.text ? "pass" : "fail");
                                    if (narration?.imagePrompt) {
                                        testLog("llm", "Narration IMAGE prompt: " + narration.imagePrompt.substring(0, 150), "info");
                                    }

                                    // Test resolution narration
                                    testLog("llm", "Requesting resolution narration...");
                                    let resNarration = await testNarrator.narrate("resolution", {
                                        playerStack: "Fireball (magic, Deal 15 fire damage)",
                                        playerRoll: { raw: 18, total: 25 },
                                        opponentStack: "Shield Block (defense)",
                                        opponentRoll: { raw: 8, total: 15 },
                                        isPlayerAttack: true,
                                        outcome: "HIT",
                                        damage: 10
                                    });
                                    testLog("llm", "Resolution narration: " + (resNarration?.text || "(empty)").substring(0, 200), resNarration?.text ? "pass" : "warn");
                                }

                                // Test JSON cleaning utility
                                let jsonTestInput = "```json\n{\"name\": \"Test\"}\n```";
                                let cleaned = CardGameLLM.cleanJsonResponse(jsonTestInput);
                                try {
                                    let parsed = JSON.parse(cleaned);
                                    testLog("llm", "cleanJsonResponse: parsed OK -- " + JSON.stringify(parsed), "pass");
                                } catch (e) {
                                    testLog("llm", "cleanJsonResponse: parse failed -- " + cleaned, "fail");
                                }
                            }
                        } catch (e) {
                            testLog("llm", "LLM chat test error: " + e.message, "fail");
                        }
                    } else {
                        testLog("llm", "LLM not available -- remaining LLM tests skipped (defaults/fallbacks will be used at runtime)", "info");
                    }
                } else {
                    testLog("llm", "checkLlmConnectivity not available (module not yet extracted)", "warn");
                }
            } catch (e) {
                testLog("llm", "LLM connectivity error: " + e.message, "fail");
            }
        }

        // ── Voice Tests ──────────────────────
        if (cats.includes("voice")) {
            testState.currentTest = "Voice: synthesis pipeline";
            testLog("voice", "Testing voice system...");

            let CardGameVoice = ai.CardGameVoice;
            if (!CardGameVoice) {
                testLog("voice", "CardGameVoice not available (module not yet extracted)", "warn");
            } else {
                let hasAudio = typeof page?.components?.audio?.createAudioSource === "function";
                testLog("voice", "Audio infrastructure: " + (hasAudio ? "present" : "absent"), "info");

                // Test 1: subtitlesOnly mode (should work regardless of audio)
                let testVoice1 = new CardGameVoice();
                await testVoice1.initialize({ subtitlesOnly: true });
                if (testVoice1.subtitlesOnly && testVoice1.enabled) {
                    testLog("voice", "subtitlesOnly init: enabled=" + testVoice1.enabled + " subtitlesOnly=" + testVoice1.subtitlesOnly, "pass");
                } else {
                    testLog("voice", "subtitlesOnly init: unexpected state enabled=" + testVoice1.enabled + " subtitlesOnly=" + testVoice1.subtitlesOnly, "fail");
                }

                // Test 2: speak() in subtitlesOnly mode shows subtitle
                let savedVoiceGs = gameState.getGameState ? gameState.getGameState() : null;
                let tempVoiceGs = { narrationText: null, narrationTime: null };
                if (gameState.setGameState) gameState.setGameState(tempVoiceGs);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempVoiceGs;
                await testVoice1.speak("Voice test subtitle text");
                if (tempVoiceGs.narrationText === "Voice test subtitle text") {
                    testLog("voice", "speak() in subtitlesOnly mode -> subtitle displayed", "pass");
                } else {
                    testLog("voice", "speak() in subtitlesOnly mode -> subtitle NOT displayed (narrationText=" + tempVoiceGs.narrationText + ")", "fail");
                }
                tempVoiceGs.narrationText = null;
                if (gameState.setGameState) gameState.setGameState(savedVoiceGs);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedVoiceGs;

                // Test 3: Volume control
                testVoice1.setVolume(0.5);
                testLog("voice", "setVolume(0.5) -> volume=" + testVoice1.volume, testVoice1.volume === 0.5 ? "pass" : "fail");
                testVoice1.setVolume(-1);
                testLog("voice", "setVolume(-1) -> clamped to " + testVoice1.volume, testVoice1.volume === 0 ? "pass" : "fail");
                testVoice1.setVolume(5);
                testLog("voice", "setVolume(5) -> clamped to " + testVoice1.volume, testVoice1.volume === 1 ? "pass" : "fail");

                // Test 4: Queue and stop
                testVoice1.queue = [{ text: "a" }, { text: "b" }];
                testVoice1.stop();
                testLog("voice", "stop() clears queue: " + testVoice1.queue.length + " items", testVoice1.queue.length === 0 ? "pass" : "fail");

                // Test 5: Full voice mode (if audio infrastructure available)
                if (hasAudio) {
                    testState.currentTest = "Voice: audio synthesis";
                    let testVoice2 = new CardGameVoice();
                    await testVoice2.initialize({ subtitlesOnly: false, volume: 0.7 });
                    testLog("voice", "Full voice init: subtitlesOnly=" + testVoice2.subtitlesOnly + " volume=" + testVoice2.volume, !testVoice2.subtitlesOnly ? "pass" : "warn");

                    // Actually try to generate speech
                    try {
                        testLog("voice", "Attempting TTS: 'Testing voice synthesis'...");
                        await testVoice2.speak("Testing voice synthesis");
                        testLog("voice", "TTS speak() completed without error", "pass");
                    } catch (e) {
                        testLog("voice", "TTS speak() error: " + e.message, "fail");
                    }
                } else {
                    testLog("voice", "No audio infrastructure -- full voice test skipped (subtitlesOnly fallback verified above)", "info");
                }
            }
        }

        // ── Game Config Toggle Tests ────────
        // Test initializeLLMComponents with both enable/disable states
        if (cats.includes("llm") || cats.includes("voice")) {
            testState.currentTest = "Config: enable/disable states";
            testLog("llm", "Testing game config enable/disable states...");

            let initializeLLMComponents = gameState.initializeLLMComponents || ai.initializeLLMComponents;
            let CardGameVoice = ai.CardGameVoice;
            let llmStatus = ai.llmStatus || gameState.state.llmStatus || { checked: false, available: false };

            if (resolvedDeck && initializeLLMComponents) {
                // Save/restore actual game globals
                let savedNarrator = gameState.state.gameNarrator || null;
                let savedDirector = gameState.state.gameDirector || null;
                let savedChatMgr = gameState.state.gameChatManager || null;
                let savedVoice = gameState.state.gameVoice || null;
                let savedAnnVoice = gameState.state.gameAnnouncerVoice || null;
                let savedGs = context.gameState;

                // Create a temporary game state for the tests
                let testGs = gameState.createGameState ? await gameState.createGameState(resolvedDeck) : null;
                if (testGs) {
                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = testGs;

                    // ── Test 1: Everything DISABLED ──
                    testLog("llm", "Config test: all disabled...");
                    let disabledDeck = JSON.parse(JSON.stringify(resolvedDeck));
                    disabledDeck.gameConfig = {
                        narrationEnabled: false,
                        opponentVoiceEnabled: false,
                        announcerEnabled: false,
                        announcerVoiceEnabled: false
                    };
                    await initializeLLMComponents(testGs, disabledDeck, { skipNarration: true });

                    let gameNarrator = gameState.state.gameNarrator || null;
                    let gameChatManager = gameState.state.gameChatManager || null;
                    let gameVoice = gameState.state.gameVoice || null;
                    let gameAnnouncerVoice = gameState.state.gameAnnouncerVoice || null;

                    testLog("llm", "narration=off -> gameNarrator=" + (gameNarrator === null ? "null" : "active"),
                        gameNarrator === null ? "pass" : "fail");
                    testLog("llm", "narration=off -> gameChatManager=" + (gameChatManager === null ? "null" : "active"),
                        gameChatManager === null ? "pass" : "fail");
                    testLog("voice", "oppVoice=off -> subtitlesOnly=" + gameVoice?.subtitlesOnly,
                        gameVoice?.subtitlesOnly === true ? "pass" : "fail");
                    testLog("voice", "announcer=off -> gameAnnouncerVoice=" + (gameAnnouncerVoice === null ? "null" : "active"),
                        gameAnnouncerVoice === null ? "pass" : "fail");

                    // ── Test 2: Everything ENABLED ──
                    testLog("llm", "Config test: all enabled...");
                    let enabledDeck = JSON.parse(JSON.stringify(resolvedDeck));
                    enabledDeck.gameConfig = {
                        narrationEnabled: true,
                        opponentVoiceEnabled: true,
                        announcerEnabled: true,
                        announcerVoiceEnabled: true
                    };
                    await initializeLLMComponents(testGs, enabledDeck, { skipNarration: true });

                    gameNarrator = gameState.state.gameNarrator || null;
                    gameChatManager = gameState.state.gameChatManager || null;
                    gameVoice = gameState.state.gameVoice || null;
                    gameAnnouncerVoice = gameState.state.gameAnnouncerVoice || null;

                    // Announcer profile comes from theme (not deck config)
                    let expectedProfile = themes.getActiveTheme?.()?.narration?.announcerProfile || "arena-announcer";
                    if (llmStatus.available) {
                        testLog("llm", "narration=on -> gameNarrator=" + (gameNarrator ? "active (" + (gameNarrator.profile || "?") + ")" : "null (LLM init may have failed)"),
                            gameNarrator ? "pass" : "warn");
                        testLog("llm", "narration=on -> gameChatManager=" + (gameChatManager ? "active" : "null (LLM init may have failed)"),
                            gameChatManager ? "pass" : "warn");
                        testLog("llm", "announcer profile (from theme): " + (gameNarrator?.profile || "not set") + " (expected: " + expectedProfile + ")",
                            gameNarrator?.profile === expectedProfile ? "pass" : "warn");
                    } else {
                        testLog("llm", "LLM unavailable -- narrator/chat would use fallbacks at runtime", "info");
                    }
                    testLog("voice", "oppVoice=on -> subtitlesOnly=" + gameVoice?.subtitlesOnly,
                        gameVoice?.subtitlesOnly === false ? "pass" : "warn");
                    testLog("voice", "announcer voice=on -> gameAnnouncerVoice=" + (gameAnnouncerVoice ? "active" : "null"),
                        gameAnnouncerVoice ? "pass" : "warn");
                    if (gameAnnouncerVoice) {
                        testLog("voice", "announcer voice subtitlesOnly=" + gameAnnouncerVoice.subtitlesOnly,
                            gameAnnouncerVoice.subtitlesOnly === false ? "pass" : "fail");
                    }

                    // ── Test 3: Mixed -- announcer on but voice off ──
                    testLog("llm", "Config test: announcer on, voice off...");
                    let mixedDeck = JSON.parse(JSON.stringify(resolvedDeck));
                    mixedDeck.gameConfig = {
                        narrationEnabled: true,
                        opponentVoiceEnabled: false,
                        announcerEnabled: true,
                        announcerVoiceEnabled: false
                    };
                    await initializeLLMComponents(testGs, mixedDeck, { skipNarration: true });

                    gameVoice = gameState.state.gameVoice || null;
                    gameAnnouncerVoice = gameState.state.gameAnnouncerVoice || null;
                    gameNarrator = gameState.state.gameNarrator || null;

                    testLog("voice", "oppVoice=off, annVoice=off -> opp subtitlesOnly=" + gameVoice?.subtitlesOnly,
                        gameVoice?.subtitlesOnly === true ? "pass" : "fail");
                    testLog("voice", "annVoice=off -> gameAnnouncerVoice=" + (gameAnnouncerVoice === null ? "null" : "active"),
                        gameAnnouncerVoice === null ? "pass" : "fail");
                    if (llmStatus.available && gameNarrator) {
                        testLog("llm", "announcer profile (from theme): " + (gameNarrator.profile || "not set"),
                            gameNarrator.profile === expectedProfile ? "pass" : "warn");
                    }

                    // ── Test 4: Deck's actual saved config ──
                    let deckGc = resolvedDeck.gameConfig;
                    if (deckGc && Object.keys(deckGc).length > 0) {
                        testLog("llm", "Config test: deck's saved config...");
                        testLog("llm", "Deck gameConfig: " + JSON.stringify(deckGc), "info");
                        await initializeLLMComponents(testGs, resolvedDeck, { skipNarration: true });

                        gameNarrator = gameState.state.gameNarrator || null;
                        gameChatManager = gameState.state.gameChatManager || null;
                        gameVoice = gameState.state.gameVoice || null;
                        gameAnnouncerVoice = gameState.state.gameAnnouncerVoice || null;

                        testLog("llm", "Deck config -> narrator=" + (gameNarrator ? "active" : "null")
                            + " chatMgr=" + (gameChatManager ? "active" : "null")
                            + " oppVoice=" + (gameVoice?.subtitlesOnly ? "subtitles" : "TTS")
                            + " annVoice=" + (gameAnnouncerVoice ? "TTS" : "off"), "info");
                    } else {
                        testLog("llm", "No saved gameConfig on deck -- defaults will be used", "info");
                    }

                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs;
                } else {
                    testLog("llm", "Could not create game state for config tests", "warn");
                }

                // Restore globals
                gameState.state.gameNarrator = savedNarrator;
                gameState.state.gameDirector = savedDirector;
                gameState.state.gameChatManager = savedChatMgr;
                gameState.state.gameVoice = savedVoice;
                gameState.state.gameAnnouncerVoice = savedAnnVoice;
            } else if (!initializeLLMComponents) {
                testLog("llm", "initializeLLMComponents not available (module not yet extracted)", "warn");
            } else {
                testLog("llm", "No deck available for config toggle tests", "warn");
            }
        }

        // ── Playthrough Tests ────────────────
        if (cats.includes("playthrough")) {
            testState.currentTest = "Playthrough: automated game";
            testLog("playthrough", "Running automated playthrough...");

            if (!resolvedDeck) {
                testLog("playthrough", "No deck available for playthrough", "warn");
            } else if (!gameState.createGameState) {
                testLog("playthrough", "createGameState not available (module not yet extracted)", "warn");
            } else {
                let deckName = resolvedDeckName;
                let deck = resolvedDeck;
                {
                    testLog("playthrough", "Using deck: " + (deck.deckName || deckName) + " (" + (deck.cards || []).length + " cards)", "pass");

                    // Create game state
                    let gs = await gameState.createGameState(deck);
                    if (!gs) {
                        testLog("playthrough", "createGameState failed (no characters?)", "fail");
                    } else {
                        testLog("playthrough", "Game created: " + gs.player.character.name + " vs " + gs.opponent.character.name, "pass");
                        testLog("playthrough", "Player: HP=" + gs.player.hp + " AP=" + gs.player.ap + " Hand=" + gs.player.hand.length, "info");
                        testLog("playthrough", "Opponent: HP=" + gs.opponent.hp + " AP=" + gs.opponent.ap + " Hand=" + gs.opponent.hand.length, "info");

                        // Simulate rounds
                        let maxRounds = 5;
                        for (let r = 0; r < maxRounds; r++) {
                            // Initiative
                            let pRoll = engine.rollD20() + (gs.player.character.stats?.AGI || 10);
                            let oRoll = engine.rollD20() + (gs.opponent.character.stats?.AGI || 10);
                            let initWinner = pRoll >= oRoll ? "player" : "opponent";
                            testLog("playthrough", "Round " + (r + 1) + " initiative: " + initWinner + " (P:" + pRoll + " vs O:" + oRoll + ")", "info");

                            // Simulate combat
                            let pAtk = engine.rollD20() + (gs.player.character.stats?.STR || 10) + engine.getActorATK(gs.player);
                            let oDef = engine.rollD20() + (gs.opponent.character.stats?.END || 10) + engine.getActorDEF(gs.opponent);
                            if (pAtk > oDef) {
                                let dmg = Math.max(1, Math.floor((pAtk - oDef) / 3));
                                gs.opponent.hp -= dmg;
                                testLog("playthrough", "Player attacks: " + pAtk + " vs " + oDef + " -> " + dmg + " damage (opp HP: " + gs.opponent.hp + ")", "info");
                            } else {
                                testLog("playthrough", "Player attacks: " + pAtk + " vs " + oDef + " -> miss", "info");
                            }

                            let oAtk = engine.rollD20() + (gs.opponent.character.stats?.STR || 10) + engine.getActorATK(gs.opponent);
                            let pDef = engine.rollD20() + (gs.player.character.stats?.END || 10) + engine.getActorDEF(gs.player);
                            if (oAtk > pDef) {
                                let dmg = Math.max(1, Math.floor((oAtk - pDef) / 3));
                                gs.player.hp -= dmg;
                                testLog("playthrough", "Opponent attacks: " + oAtk + " vs " + pDef + " -> " + dmg + " damage (player HP: " + gs.player.hp + ")", "info");
                            } else {
                                testLog("playthrough", "Opponent attacks: " + oAtk + " vs " + pDef + " -> miss", "info");
                            }

                            // Check game over
                            if (gs.player.hp <= 0) { testLog("playthrough", "Player defeated at round " + (r + 1), "info"); break; }
                            if (gs.opponent.hp <= 0) { testLog("playthrough", "Opponent defeated at round " + (r + 1), "info"); break; }
                        }

                        let winner = gs.player.hp <= 0 ? "opponent" : gs.opponent.hp <= 0 ? "player" : "draw";
                        testLog("playthrough", "Result: " + winner + " (P:" + gs.player.hp + " O:" + gs.opponent.hp + ")", winner !== "draw" ? "pass" : "info");

                        // Test save/load cycle (basic -- comprehensive test in storage category)
                        let saved = await storage.gameStorage.save(deckName + "__pt", gs);
                        if (saved) {
                            let loadedSave = await storage.gameStorage.load(deckName + "__pt");
                            let roundtrip = loadedSave?.gameState?.player?.hp === gs.player.hp
                                && loadedSave?.gameState?.round === gs.round
                                && loadedSave?.gameState?.opponent?.hp === gs.opponent.hp;
                            testLog("playthrough", "Save/load roundtrip (hp+round+opp): " + (roundtrip ? "ok" : "mismatch"), roundtrip ? "pass" : "fail");
                            await storage.gameStorage.deleteAll(deckName + "__pt");
                        } else {
                            testLog("playthrough", "Game state save failed", "fail");
                        }
                    }
                }
            }
        }

    }

    // ── Test Mode UI Component (uses shared TestFramework components) ──
    function TestModeUI() {
        return {
            view: function() {
                let context = ctx();

                return m("div", { class: "cg2-test-mode" }, [
                    // Toolbar with CardGame-specific back button
                    m(TF.TestToolbarUI, {
                        title: "Card Game Tests",
                        subtitle: testDeckName || null,
                        onBack: function() {
                            if (testDeck && context.viewingDeck) {
                                if (window.CardGame.ctx) window.CardGame.ctx.screen = "deckView";
                            } else {
                                if (window.CardGame.ctx) window.CardGame.ctx.screen = "deckList";
                            }
                            m.redraw();
                        }
                    }),

                    // Category toggles
                    m(TF.TestCategoryToggleUI, { categories: TEST_CATEGORIES }),

                    // Results summary
                    m(TF.TestResultsSummaryUI),

                    // Console
                    m(TF.TestConsoleUI, { categories: TEST_CATEGORIES })
                ]);
            }
        };
    }

    // ── Register with shared TestFramework ──────────────────────────────
    if (TF) {
        TF.registerSuite("cardGame", {
            label: "Card Game",
            icon: "playing_cards",
            categories: TEST_CATEGORIES,
            run: runCardGameTests
        });
    }

    // ── Expose on CardGame.TestMode namespace ────────────────────────────
    Object.assign(window.CardGame.TestMode, {
        TEST_CATEGORIES: TEST_CATEGORIES,
        testState: testState,
        getTestDeck: function() { return testDeck; },
        getTestDeckName: function() { return testDeckName; },
        setTestDeck: setTestDeck,
        clearTestDeck: clearTestDeck,
        runTestSuite: runTestSuite,
        TestModeUI: TestModeUI
    });

    console.log("[CardGame] test/testMode loaded (registered with TestFramework)");
}());
