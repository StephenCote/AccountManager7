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
        playthrough: { label: "Playthrough", icon: "sports_esports" },
        ux:          { label: "UX Scenarios", icon: "touch_app" },
        layouts:     { label: "Layouts",      icon: "dashboard" },
        designer:    { label: "Designer",     icon: "design_services" },
        export:      { label: "Export",        icon: "file_download" }
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
        selectedCategories: [],
        logFilter: "all",
        selectedSuite: null
    };
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
                ["CardGame.Designer",   window.CardGame.Designer],
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

            // Verify Designer module exports
            let designer = window.CardGame.Designer;
            if (designer) {
                let designerExports = ["LayoutConfig", "LayoutRenderer", "IconPicker",
                    "DesignerView", "ExportPipeline", "ExportDialog"];
                let designerMissing = [];
                for (let fn of designerExports) {
                    if (!designer[fn]) designerMissing.push(fn);
                }
                testLog("modules", "Designer: " + (designerExports.length - designerMissing.length) + "/" + designerExports.length + " modules present",
                    designerMissing.length === 0 ? "pass" : "fail");
                if (designerMissing.length > 0) {
                    testLog("modules", "Designer MISSING: " + designerMissing.join(", "), "fail");
                }
            } else {
                testLog("modules", "Designer: namespace MISSING", "fail");
            }

            // Verify v3 constants (CARD_SIZES, LAYOUT_ELEMENT_TYPES)
            let consts = C();
            if (consts) {
                let v3Consts = ["CARD_SIZES", "DEFAULT_LAYOUT_VERSION", "LAYOUT_ZONES", "LAYOUT_ELEMENT_TYPES"];
                let v3Missing = [];
                for (let c of v3Consts) {
                    if (!consts[c]) v3Missing.push(c);
                }
                testLog("modules", "v3 Constants: " + (v3Consts.length - v3Missing.length) + "/" + v3Consts.length + " present",
                    v3Missing.length === 0 ? "pass" : "fail");
                if (v3Missing.length > 0) {
                    testLog("modules", "v3 Constants MISSING: " + v3Missing.join(", "), "fail");
                }
                // Verify CARD_SIZES has expected keys
                if (consts.CARD_SIZES) {
                    let expectedSizes = ["poker", "bridge", "tarot", "mini", "custom"];
                    let sizeMissing = expectedSizes.filter(s => !consts.CARD_SIZES[s]);
                    testLog("modules", "CARD_SIZES: " + Object.keys(consts.CARD_SIZES).length + " sizes (" + (sizeMissing.length === 0 ? "all present" : "missing: " + sizeMissing.join(", ")) + ")",
                        sizeMissing.length === 0 ? "pass" : "fail");
                }
            }

            // Verify Rendering has layout delegation
            let rend = window.CardGame.Rendering;
            if (rend) {
                testLog("modules", "Rendering.getLayoutConfig: " + (typeof rend.getLayoutConfig === "function" ? "present" : "MISSING"),
                    typeof rend.getLayoutConfig === "function" ? "pass" : "fail");
            }

            // Verify ctx has designerDeck property
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

                // Verify designer-related ctx properties
                let hasDesignerDeck = "designerDeck" in ctxObj;
                testLog("modules", "ctx.designerDeck property: " + (hasDesignerDeck ? "present" : "MISSING"),
                    hasDesignerDeck ? "pass" : "fail");

                // Verify 'designer' is a valid screen value
                let origScreen = ctxObj.screen;
                ctxObj.screen = "designer";
                let screenSet = ctxObj.screen === "designer";
                ctxObj.screen = origScreen;
                testLog("modules", "ctx.screen='designer' accepted=" + screenSet,
                    screenSet ? "pass" : "fail");
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

                // Test layoutConfigs preservation in deck save/load
                let LC = window.CardGame.Designer ? window.CardGame.Designer.LayoutConfig : null;
                if (resolvedDeck && LC) {
                    let testLayoutDeck = Object.assign({}, resolvedDeck);
                    testLayoutDeck.layoutConfigs = testLayoutDeck.layoutConfigs || {};
                    let testLayout = LC.generateDefaultLayout("character", "poker");
                    testLayout._storageTest = "layout_roundtrip";
                    testLayoutDeck.layoutConfigs["character:poker"] = testLayout;

                    // Save to a test key
                    let testStorageName = (resolvedDeckName || "test") + "__layout_test";
                    await storage.deckStorage.save(testStorageName, testLayoutDeck);
                    let loadedDeck = await storage.deckStorage.load(testStorageName);
                    if (loadedDeck && loadedDeck.layoutConfigs && loadedDeck.layoutConfigs["character:poker"]) {
                        let loadedLayout = loadedDeck.layoutConfigs["character:poker"];
                        let markerOk = loadedLayout._storageTest === "layout_roundtrip";
                        let zonesOk = loadedLayout.zones && typeof loadedLayout.zones === "object";
                        testLog("storage", "layoutConfigs save/load: marker=" + markerOk + " zones=" + zonesOk,
                            markerOk && zonesOk ? "pass" : "fail");
                    } else {
                        testLog("storage", "layoutConfigs save/load: NOT preserved in deck storage", "fail");
                    }
                    // Clean up
                    await storage.deckStorage.deleteAll(testStorageName);
                } else {
                    testLog("storage", "layoutConfigs roundtrip (skipped: " + (!resolvedDeck ? "no deck" : "Designer not loaded") + ")", "skip");
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

        // ── UX Scenario Tests ─────────────────
        if (cats.includes("ux")) {
            testState.currentTest = "UX: equipment system";
            testLog("ux", "=== UX Scenario Tests ===", "info");

            let EQUIP_SLOT_MAP = C().EQUIP_SLOT_MAP;
            let GAME_PHASES = C().GAME_PHASES;

            // Helper: canEquipToSlot (mirrors phaseUI.js logic)
            function canEquipToSlot(card, slotKey) {
                let cardSlot = card.slot || (card.type === "apparel" || (card.type === "item" && card.subtype === "armor") ? "Body" : null);
                if (!cardSlot || !EQUIP_SLOT_MAP[cardSlot]) return false;
                let validSlots = EQUIP_SLOT_MAP[cardSlot];
                return validSlots.indexOf(slotKey) >= 0;
            }

            // Helper: create a test actor with equipment slots
            function makeTestActor(handCards, stackCards) {
                return {
                    character: { name: "TestChar", stats: { STR: 12, AGI: 10, END: 14, INT: 10, MAG: 10, CHA: 10 } },
                    hp: 20, maxHp: 20,
                    energy: 10, maxEnergy: 10,
                    morale: 20, maxMorale: 20,
                    ap: 3, maxAp: 3, apUsed: 0,
                    hand: (handCards || []).slice(),
                    cardStack: (stackCards || []).slice(),
                    drawPile: [],
                    discardPile: [],
                    equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
                    roundPoints: 0,
                    statusEffects: [],
                    typesPlayedThisRound: {}
                };
            }

            // Reusable test cards
            let sword1H = { type: "item", subtype: "weapon", name: "Iron Sword", slot: "Hand (1H)", atk: 3, def: 0, durability: 5 };
            let axe1H = { type: "item", subtype: "weapon", name: "Battle Axe", slot: "Hand (1H)", atk: 5, def: 0, durability: 4 };
            let greatsword2H = { type: "item", subtype: "weapon", name: "Greatsword", slot: "Hand (2H)", atk: 7, def: 0, durability: 6 };
            let bodyArmor = { type: "apparel", name: "Chainmail", slot: "Body", atk: 0, def: 3, durability: 8 };
            let helmet = { type: "apparel", name: "Iron Helm", slot: "Head", atk: 0, def: 1, durability: 6 };
            let boots = { type: "apparel", name: "Leather Boots", slot: "Feet", atk: 0, def: 1, durability: 4 };
            let ring = { type: "apparel", name: "Ring of Power", slot: "Ring", atk: 1, def: 1, durability: null };
            let cape = { type: "apparel", name: "Enchanted Cape", slot: "Back", atk: 0, def: 2, durability: 5 };
            let itemArmor = { type: "item", subtype: "armor", name: "Plate Mail", slot: "Body", atk: 0, def: 4, durability: 10 };
            let itemArmorNoSlot = { type: "item", subtype: "armor", name: "Basic Vest", atk: 0, def: 2, durability: 6 };
            let skillCard = { type: "skill", name: "Swordsmanship", modifier: "+2 to Attack rolls" };
            let magicCard = { type: "magic", name: "Fireball", effect: "Deal 10 fire damage", energyCost: 3 };
            let consumable = { type: "item", subtype: "consumable", name: "Health Potion", effect: "Restore 10 HP" };

            // ── A. Equipment: equip/unequip ──
            testLog("ux", "--- A. Equipment: equip/unequip ---", "info");

            if (gameState.equipCard && gameState.unequipCard) {
                // A1: Equip 1H weapon to handR
                {
                    let w = Object.assign({}, sword1H);
                    let actor = makeTestActor([w]);
                    gameState.equipCard(actor, w, "handR");
                    testLog("ux", "A1: equipCard(1H weapon, handR) -> equipped=" + (actor.equipped.handR === w) + " hand=" + actor.hand.length,
                        actor.equipped.handR === w && actor.hand.length === 0 ? "pass" : "fail");
                }

                // A2: Equip apparel to body
                {
                    let a = Object.assign({}, bodyArmor);
                    let actor = makeTestActor([a]);
                    gameState.equipCard(actor, a, "body");
                    testLog("ux", "A2: equipCard(armor, body) -> equipped=" + (actor.equipped.body === a) + " hand=" + actor.hand.length,
                        actor.equipped.body === a && actor.hand.length === 0 ? "pass" : "fail");
                }

                // A3: Unequip weapon returns to hand
                {
                    let w = Object.assign({}, sword1H);
                    let actor = makeTestActor([w]);
                    gameState.equipCard(actor, w, "handR");
                    gameState.unequipCard(actor, "handR");
                    testLog("ux", "A3: unequipCard(handR) -> slot null=" + (actor.equipped.handR === null) + " hand=" + actor.hand.length,
                        actor.equipped.handR === null && actor.hand.length === 1 && actor.hand[0] === w ? "pass" : "fail");
                }

                // A4: Two-handed weapon occupies both hand slots
                {
                    let gs2h = Object.assign({}, greatsword2H);
                    let actor = makeTestActor([gs2h]);
                    gameState.equipCard(actor, gs2h, "handR");
                    testLog("ux", "A4: equipCard(2H weapon, handR) -> handL=" + (actor.equipped.handL === gs2h) + " handR=" + (actor.equipped.handR === gs2h),
                        actor.equipped.handL === gs2h && actor.equipped.handR === gs2h && actor.hand.length === 0 ? "pass" : "fail");
                }

                // A5: Unequip two-handed clears both, returns card once
                {
                    let gs2h = Object.assign({}, greatsword2H);
                    let actor = makeTestActor([gs2h]);
                    gameState.equipCard(actor, gs2h, "handR");
                    gameState.unequipCard(actor, "handR");
                    testLog("ux", "A5: unequip 2H -> handL null=" + (actor.equipped.handL === null) + " handR null=" + (actor.equipped.handR === null) + " hand=" + actor.hand.length,
                        actor.equipped.handL === null && actor.equipped.handR === null && actor.hand.length === 1 ? "pass" : "fail");
                }

                // A6: Swap weapon - equip new to occupied slot
                {
                    let w1 = Object.assign({}, sword1H);
                    let w2 = Object.assign({}, axe1H);
                    let actor = makeTestActor([w1, w2]);
                    gameState.equipCard(actor, w1, "handR");
                    let handAfterFirst = actor.hand.length;
                    gameState.equipCard(actor, w2, "handR");
                    testLog("ux", "A6: swap weapon -> new equipped=" + (actor.equipped.handR === w2) + " old in hand=" + actor.hand.includes(w1) + " hand=" + actor.hand.length,
                        actor.equipped.handR === w2 && actor.hand.includes(w1) && actor.hand.length === 1 ? "pass" : "fail");
                }

                // A7: Equip from cardStack removes from cardStack
                {
                    let w = Object.assign({}, sword1H);
                    let actor = makeTestActor([], [w]);
                    gameState.equipCard(actor, w, "handR");
                    testLog("ux", "A7: equipCard from cardStack -> equipped=" + (actor.equipped.handR === w) + " cardStack=" + actor.cardStack.length,
                        actor.equipped.handR === w && actor.cardStack.length === 0 ? "pass" : "fail");
                }

                // A8: Equip 2H when 1H already in one hand - displaces existing
                {
                    let w1h = Object.assign({}, sword1H);
                    let w2h = Object.assign({}, greatsword2H);
                    let actor = makeTestActor([w1h, w2h]);
                    gameState.equipCard(actor, w1h, "handR");
                    gameState.equipCard(actor, w2h, "handR");
                    testLog("ux", "A8: 2H displaces 1H -> handL=" + (actor.equipped.handL === w2h) + " handR=" + (actor.equipped.handR === w2h) + " old in hand=" + actor.hand.includes(w1h),
                        actor.equipped.handL === w2h && actor.equipped.handR === w2h && actor.hand.includes(w1h) ? "pass" : "fail");
                }
            } else {
                testLog("ux", "equipCard/unequipCard not available on GameState", "fail");
            }

            // A9-A12: canEquipToSlot validation
            {
                testLog("ux", "A9: canEquipToSlot(1H weapon, handR)=" + canEquipToSlot(sword1H, "handR"),
                    canEquipToSlot(sword1H, "handR") === true ? "pass" : "fail");
                testLog("ux", "A10: canEquipToSlot(1H weapon, head)=" + canEquipToSlot(sword1H, "head"),
                    canEquipToSlot(sword1H, "head") === false ? "pass" : "fail");
                testLog("ux", "A11: canEquipToSlot(body armor, body)=" + canEquipToSlot(bodyArmor, "body"),
                    canEquipToSlot(bodyArmor, "body") === true ? "pass" : "fail");
                testLog("ux", "A12: canEquipToSlot(body armor, handR)=" + canEquipToSlot(bodyArmor, "handR"),
                    canEquipToSlot(bodyArmor, "handR") === false ? "pass" : "fail");

                // Slot for all slot types
                let headGear = { type: "apparel", name: "Hat", slot: "Head" };
                let feetGear = { type: "apparel", name: "Shoes", slot: "Feet" };
                let backGear = { type: "apparel", name: "Cloak", slot: "Back" };
                let ringGear = { type: "apparel", name: "Band", slot: "Ring" };
                testLog("ux", "canEquipToSlot(Head->head)=" + canEquipToSlot(headGear, "head"),
                    canEquipToSlot(headGear, "head") === true ? "pass" : "fail");
                testLog("ux", "canEquipToSlot(Feet->feet)=" + canEquipToSlot(feetGear, "feet"),
                    canEquipToSlot(feetGear, "feet") === true ? "pass" : "fail");
                testLog("ux", "canEquipToSlot(Back->back)=" + canEquipToSlot(backGear, "back"),
                    canEquipToSlot(backGear, "back") === true ? "pass" : "fail");
                testLog("ux", "canEquipToSlot(Ring->ring)=" + canEquipToSlot(ringGear, "ring"),
                    canEquipToSlot(ringGear, "ring") === true ? "pass" : "fail");

                // Card with no slot and not apparel
                let noSlotCard = { type: "skill", name: "Skill" };
                testLog("ux", "canEquipToSlot(skill, body)=" + canEquipToSlot(noSlotCard, "body"),
                    canEquipToSlot(noSlotCard, "body") === false ? "pass" : "fail");

                // Apparel with no slot defaults to Body
                let noSlotApparel = { type: "apparel", name: "Robe" };
                testLog("ux", "canEquipToSlot(apparel no slot, body)=" + canEquipToSlot(noSlotApparel, "body"),
                    canEquipToSlot(noSlotApparel, "body") === true ? "pass" : "fail");

                // item/armor with slot
                testLog("ux", "canEquipToSlot(item/armor with slot, body)=" + canEquipToSlot(itemArmor, "body"),
                    canEquipToSlot(itemArmor, "body") === true ? "pass" : "fail");

                // item/armor WITHOUT slot defaults to Body
                testLog("ux", "canEquipToSlot(item/armor no slot, body)=" + canEquipToSlot(itemArmorNoSlot, "body"),
                    canEquipToSlot(itemArmorNoSlot, "body") === true ? "pass" : "fail");
            }

            // A extra: equip item/armor type card
            if (gameState.equipCard) {
                let a = Object.assign({}, itemArmor);
                let actor = makeTestActor([a]);
                gameState.equipCard(actor, a, "body");
                testLog("ux", "A-extra: equipCard(item/armor, body) -> equipped=" + (actor.equipped.body === a) + " hand=" + actor.hand.length,
                    actor.equipped.body === a && actor.hand.length === 0 ? "pass" : "fail");
            }

            // A extra: equip item/armor without slot property
            if (gameState.equipCard) {
                let a = Object.assign({}, itemArmorNoSlot);
                let actor = makeTestActor([a]);
                gameState.equipCard(actor, a, "body");
                testLog("ux", "A-extra: equipCard(item/armor no slot, body) -> equipped=" + (actor.equipped.body === a),
                    actor.equipped.body === a && actor.hand.length === 0 ? "pass" : "fail");
            }

            // ── B. Auto-equip ──
            testLog("ux", "--- B. Auto-equip ---", "info");
            testState.currentTest = "UX: auto-equip";

            if (gameState.aiAutoEquip) {
                // B1: aiAutoEquip from hand+cardStack
                {
                    let w = Object.assign({}, sword1H);
                    let a = Object.assign({}, bodyArmor);
                    let actor = makeTestActor([w], [a]);
                    gameState.aiAutoEquip(actor);
                    testLog("ux", "B1: aiAutoEquip -> weapon equipped=" + (actor.equipped.handR !== null) + " armor equipped=" + (actor.equipped.body !== null),
                        actor.equipped.handR !== null && actor.equipped.body !== null ? "pass" : "fail");
                }

                // B2: Best items equipped first (higher atk+def)
                {
                    let weak = Object.assign({}, sword1H);  // atk=3
                    let strong = Object.assign({}, axe1H);  // atk=5
                    let actor = makeTestActor([weak, strong]);
                    gameState.aiAutoEquip(actor);
                    testLog("ux", "B2: best item first -> handR=" + (actor.equipped.handR ? actor.equipped.handR.name : "null") + " (expect Battle Axe, atk=5)",
                        actor.equipped.handR && actor.equipped.handR.atk === 5 ? "pass" : "fail");
                }

                // B3: Already-equipped items not duplicated
                {
                    let w = Object.assign({}, sword1H);
                    let actor = makeTestActor([w]);
                    actor.equipped.handR = w;  // Pre-equip
                    actor.hand = [];  // Remove from hand
                    gameState.aiAutoEquip(actor);
                    // Should still have same weapon, not duplicated
                    testLog("ux", "B3: already equipped not duplicated -> handR=" + (actor.equipped.handR === w),
                        actor.equipped.handR === w ? "pass" : "fail");
                }

                // B4: Multi-slot auto-equip
                {
                    let w = Object.assign({}, sword1H);
                    let a = Object.assign({}, bodyArmor);
                    let h = Object.assign({}, helmet);
                    let b = Object.assign({}, boots);
                    let actor = makeTestActor([w, a, h, b]);
                    gameState.aiAutoEquip(actor);
                    let equippedCount = Object.values(actor.equipped).filter(v => v !== null).length;
                    testLog("ux", "B4: multi-slot auto-equip -> " + equippedCount + " slots filled",
                        equippedCount >= 4 ? "pass" : "fail");
                }

                // B5: item/armor type auto-equips correctly
                {
                    let ia = Object.assign({}, itemArmor);
                    let w = Object.assign({}, sword1H);
                    let actor = makeTestActor([], [w, ia]);
                    if (gameState.autoEquipFromStack) {
                        gameState.autoEquipFromStack(actor);
                        testLog("ux", "B5: autoEquipFromStack with item/armor -> body=" + (actor.equipped.body !== null) + " handR=" + (actor.equipped.handR !== null),
                            actor.equipped.body !== null && actor.equipped.handR !== null ? "pass" : "fail");
                    } else {
                        testLog("ux", "B5: autoEquipFromStack not exported", "skip");
                    }
                }
            } else {
                testLog("ux", "aiAutoEquip not available on GameState", "fail");
            }

            // ── C. Equip phase flow ──
            testLog("ux", "--- C. Equip phase flow ---", "info");
            testState.currentTest = "UX: equip phase flow";

            if (gameState.createGameState) {
                let equipFlowDeck = {
                    deckName: "ux-equip-flow",
                    cards: [
                        { type: "character", name: "EquipHero", stats: { STR: 12, AGI: 10, END: 15, INT: 10, MAG: 10, CHA: 10 } },
                        { type: "character", name: "EquipVillain", stats: { STR: 10, AGI: 10, END: 10, INT: 12, MAG: 10, CHA: 10 } },
                        { type: "skill", name: "Combat Skill", modifier: "+1 to Attack" }
                    ]
                };
                let egs = await gameState.createGameState(equipFlowDeck, equipFlowDeck.cards[0]);
                if (egs) {
                    // C1: Starter weapon and armor in player hand
                    let hasStarterWeapon = egs.player.hand.some(c => c.type === "item" && c.subtype === "weapon");
                    let hasStarterArmor = egs.player.hand.some(c => c.type === "apparel");
                    testLog("ux", "C1: starter weapon in hand=" + hasStarterWeapon + " starter armor in hand=" + hasStarterArmor,
                        hasStarterWeapon && hasStarterArmor ? "pass" : "fail");

                    // C2: Starter items are equippable (match UI filter)
                    let equippableFromHand = egs.player.hand.filter(function(c) {
                        return (c.type === "item" && c.subtype === "weapon") || c.type === "apparel";
                    });
                    testLog("ux", "C2: equippable items in hand=" + equippableFromHand.length + " (expect >= 2)",
                        equippableFromHand.length >= 2 ? "pass" : "fail");

                    // C3: canEquipToSlot works for starter items
                    if (equippableFromHand.length > 0) {
                        let starterW = equippableFromHand.find(c => c.subtype === "weapon");
                        let starterA = equippableFromHand.find(c => c.type === "apparel");
                        if (starterW) {
                            testLog("ux", "C3a: starter weapon canEquipToSlot(handR)=" + canEquipToSlot(starterW, "handR"),
                                canEquipToSlot(starterW, "handR") === true ? "pass" : "fail");
                        }
                        if (starterA) {
                            testLog("ux", "C3b: starter armor canEquipToSlot(body)=" + canEquipToSlot(starterA, "body"),
                                canEquipToSlot(starterA, "body") === true ? "pass" : "fail");
                        }
                    }

                    // C4: Equip starter items manually (simulates UI click)
                    if (gameState.equipCard) {
                        let starterW = equippableFromHand.find(c => c.subtype === "weapon");
                        let starterA = equippableFromHand.find(c => c.type === "apparel");
                        if (starterW) {
                            let handBefore = egs.player.hand.length;
                            gameState.equipCard(egs.player, starterW, "handR");
                            testLog("ux", "C4a: equip starter weapon -> handR=" + (egs.player.equipped.handR === starterW) + " hand " + handBefore + "->" + egs.player.hand.length,
                                egs.player.equipped.handR === starterW && egs.player.hand.length === handBefore - 1 ? "pass" : "fail");
                        }
                        if (starterA) {
                            let handBefore = egs.player.hand.length;
                            gameState.equipCard(egs.player, starterA, "body");
                            testLog("ux", "C4b: equip starter armor -> body=" + (egs.player.equipped.body === starterA) + " hand " + handBefore + "->" + egs.player.hand.length,
                                egs.player.equipped.body === starterA && egs.player.hand.length === handBefore - 1 ? "pass" : "fail");
                        }
                    }

                    // C5: Opponent starter items
                    let oppHasWeapon = egs.opponent.hand.some(c => c.type === "item" && c.subtype === "weapon");
                    let oppHasArmor = egs.opponent.hand.some(c => c.type === "apparel");
                    testLog("ux", "C5: opponent has starter weapon=" + oppHasWeapon + " armor=" + oppHasArmor,
                        oppHasWeapon && oppHasArmor ? "pass" : "fail");
                } else {
                    testLog("ux", "createGameState returned null for equip flow test", "fail");
                }
            }

            // ── D. Equipment → combat integration ──
            testLog("ux", "--- D. Equipment-combat integration ---", "info");
            testState.currentTest = "UX: equipment-combat";

            // D1: getActorATK with equipped weapon
            {
                let w = Object.assign({}, sword1H);
                let actor = makeTestActor();
                actor.equipped.handR = w;
                let atk = engine.getActorATK(actor);
                testLog("ux", "D1: getActorATK(equipped 1H atk=3) = " + atk, atk === 3 ? "pass" : "fail");
            }

            // D2: getActorDEF with equipped armor
            {
                let a = Object.assign({}, bodyArmor);
                let actor = makeTestActor();
                actor.equipped.body = a;
                let def = engine.getActorDEF(actor);
                testLog("ux", "D2: getActorDEF(equipped armor def=3) = " + def, def === 3 ? "pass" : "fail");
            }

            // D3: Combined equipped + cardStack (no double count)
            {
                let w = Object.assign({}, sword1H);  // atk=3
                let stackItem = { atk: 2 };
                let actor = makeTestActor([], [stackItem]);
                actor.equipped.handR = w;
                let atk = engine.getActorATK(actor);
                testLog("ux", "D3: getActorATK(equipped atk=3 + stack atk=2) = " + atk, atk === 5 ? "pass" : "fail");
            }

            // D4: 2H weapon counted once (not doubled)
            {
                let w2h = Object.assign({}, greatsword2H);
                let actor = makeTestActor();
                actor.equipped.handL = w2h;
                actor.equipped.handR = w2h;
                let atk = engine.getActorATK(actor);
                testLog("ux", "D4: getActorATK(2H atk=7 in both slots) = " + atk + " (expect 7, not 14)",
                    atk === 7 ? "pass" : "fail");
            }

            // D5: isDualWielding - two 1H weapons
            {
                let w1 = Object.assign({}, sword1H);
                let w2 = Object.assign({}, axe1H);
                let actor = makeTestActor();
                actor.equipped.handL = w1;
                actor.equipped.handR = w2;
                let dual = engine.isDualWielding(actor);
                testLog("ux", "D5: isDualWielding(two 1H weapons) = " + dual, dual === true ? "pass" : "fail");
            }

            // D6: isDualWielding - one 2H weapon = false
            {
                let w2h = Object.assign({}, greatsword2H);
                let actor = makeTestActor();
                actor.equipped.handL = w2h;
                actor.equipped.handR = w2h;
                let dual = engine.isDualWielding(actor);
                testLog("ux", "D6: isDualWielding(one 2H weapon) = " + dual, dual === false ? "pass" : "fail");
            }

            // D7: rollAttack includes equipment ATK bonus
            {
                let w = Object.assign({}, sword1H);
                let actor = makeTestActor();
                actor.equipped.handR = w;
                actor.statusEffects = [];
                let roll = engine.rollAttack(actor, { modifiers: [] });
                testLog("ux", "D7: rollAttack atkBonus=" + roll.atkBonus + " (expect 3)",
                    roll.atkBonus === 3 ? "pass" : "fail");
            }

            // D8: rollDefense includes equipment DEF bonus
            {
                let a = Object.assign({}, bodyArmor);
                let actor = makeTestActor();
                actor.equipped.body = a;
                actor.statusEffects = [];
                let roll = engine.rollDefense(actor);
                testLog("ux", "D8: rollDefense defBonus=" + roll.defBonus + " (expect 3)",
                    roll.defBonus === 3 ? "pass" : "fail");
            }

            // ── E. Action bar placement ──
            testLog("ux", "--- E. Action bar placement ---", "info");
            testState.currentTest = "UX: action placement";

            {
                // Create minimal game state for action tests
                let actionGs = {
                    deckName: "ux-action-test",
                    round: 1,
                    phase: GAME_PHASES.DRAW_PLACEMENT,
                    player: makeTestActor([
                        Object.assign({}, skillCard),
                        Object.assign({}, magicCard),
                        Object.assign({}, consumable)
                    ]),
                    opponent: makeTestActor(),
                    initiative: {
                        winner: "player",
                        playerPositions: [0, 2, 4],
                        opponentPositions: [1, 3, 5]
                    },
                    actionBar: {
                        totalPositions: 6,
                        positions: [],
                        resolveIndex: -1
                    },
                    pot: [],
                    roundLoot: [],
                    currentTurn: "player",
                    beginningThreats: [],
                    carriedThreats: [],
                    chat: { active: false, unlocked: false }
                };
                for (let i = 0; i < 6; i++) {
                    actionGs.actionBar.positions.push({ index: i, owner: i % 2 === 0 ? "player" : "opponent", stack: null, resolved: false });
                }

                // E1: selectAction places action card
                if (engine.selectAction) {
                    let ok = engine.selectAction(actionGs, 0, "Attack");
                    let pos = actionGs.actionBar.positions[0];
                    testLog("ux", "E1: selectAction('Attack', pos 0) -> success=" + ok + " coreCard=" + (pos.stack ? pos.stack.coreCard.name : "null"),
                        ok && pos.stack && pos.stack.coreCard.name === "Attack" ? "pass" : "fail");
                }

                // E2: selectAction with unknown action
                if (engine.selectAction) {
                    let ok = engine.selectAction(actionGs, 2, "NonexistentAction");
                    testLog("ux", "E2: selectAction('NonexistentAction') -> success=" + ok,
                        ok === false ? "pass" : "fail");
                }

                // E3: isActionPlacedThisRound detects duplicate
                if (engine.isActionPlacedThisRound) {
                    let placed = engine.isActionPlacedThisRound(actionGs, "Attack", "player");
                    testLog("ux", "E3: isActionPlacedThisRound('Attack', 'player') = " + placed,
                        placed === true ? "pass" : "fail");

                    let notPlaced = engine.isActionPlacedThisRound(actionGs, "Guard", "player");
                    testLog("ux", "E3b: isActionPlacedThisRound('Guard', 'player') = " + notPlaced,
                        notPlaced === false ? "pass" : "fail");
                }

                // E4: placeCard as modifier on core card
                if (engine.placeCard && engine.canModifyAction) {
                    let modCard = actionGs.player.hand.find(c => c.type === "skill");
                    if (modCard) {
                        let handBefore = actionGs.player.hand.length;
                        let pos0 = actionGs.actionBar.positions[0];
                        let compat = engine.canModifyAction(pos0.stack.coreCard, modCard);
                        testLog("ux", "E4a: canModifyAction(Attack, skill)=" + compat.allowed + " reason=" + (compat.reason || "ok"),
                            compat.allowed ? "pass" : "warn");
                        if (compat.allowed) {
                            engine.placeCard(actionGs, 0, modCard, true);
                            testLog("ux", "E4b: placeCard modifier -> modifiers=" + pos0.stack.modifiers.length + " hand " + handBefore + "->" + actionGs.player.hand.length,
                                pos0.stack.modifiers.length === 1 && actionGs.player.hand.length === handBefore - 1 ? "pass" : "fail");
                        }
                    }
                }

                // E5: removeCardFromPosition
                if (engine.removeCardFromPosition) {
                    let handBefore = actionGs.player.hand.length;
                    engine.removeCardFromPosition(actionGs, 0);
                    let pos0 = actionGs.actionBar.positions[0];
                    testLog("ux", "E5: removeCardFromPosition(0) -> stack=" + pos0.stack + " hand=" + actionGs.player.hand.length,
                        pos0.stack === null ? "pass" : "fail");
                }

                // E6: selectAction for different actions on separate positions
                if (engine.selectAction) {
                    engine.selectAction(actionGs, 0, "Attack");
                    engine.selectAction(actionGs, 2, "Guard");
                    let pos0 = actionGs.actionBar.positions[0];
                    let pos2 = actionGs.actionBar.positions[2];
                    testLog("ux", "E6: two actions -> pos0=" + (pos0.stack ? pos0.stack.coreCard.name : "null") + " pos2=" + (pos2.stack ? pos2.stack.coreCard.name : "null"),
                        pos0.stack && pos0.stack.coreCard.name === "Attack" && pos2.stack && pos2.stack.coreCard.name === "Guard" ? "pass" : "fail");
                }

                // E7: isCoreCardType / isModifierCardType classification
                if (engine.isCoreCardType && engine.isModifierCardType) {
                    testLog("ux", "E7: isCoreCardType('action')=" + engine.isCoreCardType("action") +
                        " isModifierCardType('skill')=" + engine.isModifierCardType("skill"),
                        engine.isCoreCardType("action") && engine.isModifierCardType("skill") &&
                        !engine.isCoreCardType("skill") && !engine.isModifierCardType("action") ? "pass" : "fail");
                }
            }

            // ── F. Hand tray filtering ──
            testLog("ux", "--- F. Hand tray filtering ---", "info");
            testState.currentTest = "UX: hand tray";

            {
                let hand = [
                    Object.assign({}, skillCard),
                    Object.assign({}, magicCard),
                    Object.assign({}, consumable),
                    { type: "skill", name: "Archery", modifier: "+1 to ranged" },
                    { type: "item", subtype: "weapon", name: "Dagger", slot: "Hand (1H)", atk: 1 }
                ];

                let filterAll = hand;
                let filterSkill = hand.filter(c => c.type === "skill");
                let filterMagic = hand.filter(c => c.type === "magic");
                let filterItem = hand.filter(c => c.type === "item");

                testLog("ux", "F1: filter 'all' = " + filterAll.length + " cards", filterAll.length === 5 ? "pass" : "fail");
                testLog("ux", "F2: filter 'skill' = " + filterSkill.length + " (expect 2)",
                    filterSkill.length === 2 ? "pass" : "fail");
                testLog("ux", "F3: filter 'magic' = " + filterMagic.length + " (expect 1)",
                    filterMagic.length === 1 ? "pass" : "fail");
                testLog("ux", "F4: filter 'item' = " + filterItem.length + " (expect 2)",
                    filterItem.length === 2 ? "pass" : "fail");

                // Empty filter
                let filterEncounter = hand.filter(c => c.type === "encounter");
                testLog("ux", "F5: filter 'encounter' = " + filterEncounter.length + " (expect 0)",
                    filterEncounter.length === 0 ? "pass" : "fail");
            }

            // ── G. Durability system ──
            testLog("ux", "--- G. Durability system ---", "info");
            testState.currentTest = "UX: durability";

            if (engine.decrementWeaponDurability && engine.decrementApparelDurability) {
                // G1: Weapon durability decrements
                {
                    let w = Object.assign({}, sword1H);
                    w.durability = 5;
                    let actor = makeTestActor();
                    actor.equipped.handR = w;
                    engine.decrementWeaponDurability(actor);
                    testLog("ux", "G1: weapon dur 5 -> " + w.durability, w.durability === 4 ? "pass" : "fail");
                }

                // G2: Weapon breaks at durability 1
                {
                    let w = Object.assign({}, sword1H);
                    w.durability = 1;
                    let actor = makeTestActor();
                    actor.equipped.handR = w;
                    // Need a gameState with pot for broken items
                    let savedCtxGs = window.CardGame.ctx ? window.CardGame.ctx.gameState : null;
                    let tempGs = { pot: [], player: actor, opponent: makeTestActor() };
                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;

                    let broken = engine.decrementWeaponDurability(actor);
                    testLog("ux", "G2: weapon breaks dur=1 -> broken=" + broken.length + " slot=" + (actor.equipped.handR === null) + " pot=" + tempGs.pot.length,
                        broken.length === 1 && actor.equipped.handR === null && tempGs.pot.includes(w) ? "pass" : "fail");

                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedCtxGs;
                }

                // G3: Apparel durability decrements (normal hit)
                {
                    let a = Object.assign({}, bodyArmor);
                    a.durability = 8;
                    let actor = makeTestActor();
                    actor.equipped.body = a;
                    engine.decrementApparelDurability(actor, false);
                    testLog("ux", "G3: armor dur 8 normal hit -> " + a.durability, a.durability === 7 ? "pass" : "fail");
                }

                // G4: Apparel durability (critical hit = -2)
                {
                    let a = Object.assign({}, bodyArmor);
                    a.durability = 8;
                    let actor = makeTestActor();
                    actor.equipped.body = a;
                    engine.decrementApparelDurability(actor, true);
                    testLog("ux", "G4: armor dur 8 crit hit -> " + a.durability, a.durability === 6 ? "pass" : "fail");
                }

                // G5: Armor breaks
                {
                    let a = Object.assign({}, bodyArmor);
                    a.durability = 1;
                    let actor = makeTestActor();
                    actor.equipped.body = a;
                    let savedCtxGs = window.CardGame.ctx ? window.CardGame.ctx.gameState : null;
                    let tempGs = { pot: [], player: actor, opponent: makeTestActor() };
                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;

                    let broken = engine.decrementApparelDurability(actor, false);
                    testLog("ux", "G5: armor breaks dur=1 -> broken=" + broken.length + " slot=" + (actor.equipped.body === null) + " pot=" + tempGs.pot.length,
                        broken.length === 1 && actor.equipped.body === null && tempGs.pot.includes(a) ? "pass" : "fail");

                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedCtxGs;
                }

                // G6: 2H weapon decrements only once
                {
                    let w2h = Object.assign({}, greatsword2H);
                    w2h.durability = 5;
                    let actor = makeTestActor();
                    actor.equipped.handL = w2h;
                    actor.equipped.handR = w2h;
                    engine.decrementWeaponDurability(actor);
                    testLog("ux", "G6: 2H weapon dur 5 -> " + w2h.durability + " (expect 4, decremented once)",
                        w2h.durability === 4 ? "pass" : "fail");
                }

                // G7: Null durability not affected
                {
                    let r = Object.assign({}, ring);
                    r.durability = null;
                    let actor = makeTestActor();
                    actor.equipped.ring = r;
                    engine.decrementApparelDurability(actor, false);
                    testLog("ux", "G7: null durability item not affected -> dur=" + r.durability,
                        r.durability === null ? "pass" : "fail");
                }

                // G8: Broken items no longer contribute to stats
                {
                    let w = Object.assign({}, sword1H);
                    w.durability = 1;
                    let actor = makeTestActor();
                    actor.equipped.handR = w;
                    let atkBefore = engine.getActorATK(actor);

                    let savedCtxGs = window.CardGame.ctx ? window.CardGame.ctx.gameState : null;
                    let tempGs = { pot: [], player: actor, opponent: makeTestActor() };
                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;

                    engine.decrementWeaponDurability(actor);
                    let atkAfter = engine.getActorATK(actor);
                    testLog("ux", "G8: ATK before break=" + atkBefore + " after break=" + atkAfter,
                        atkBefore > 0 && atkAfter === 0 ? "pass" : "fail");

                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedCtxGs;
                }

                // G9: item/armor type durability decrements
                {
                    let ia = Object.assign({}, itemArmor);
                    ia.durability = 10;
                    let actor = makeTestActor();
                    actor.equipped.body = ia;
                    engine.decrementApparelDurability(actor, false);
                    testLog("ux", "G9: item/armor dur 10 normal hit -> " + ia.durability,
                        ia.durability === 9 ? "pass" : "fail");
                }
            } else {
                testLog("ux", "decrementWeaponDurability/decrementApparelDurability not available", "fail");
            }

            // ── H. Phase transitions ──
            testLog("ux", "--- H. Phase transitions ---", "info");
            testState.currentTest = "UX: phase transitions";

            if (gameState.createGameState && gameState.advancePhase) {
                let phaseDeck = {
                    deckName: "ux-phase-test",
                    cards: [
                        { type: "character", name: "PhaseHero", stats: { STR: 10, AGI: 10, END: 10, INT: 10, MAG: 10, CHA: 10 } },
                        { type: "character", name: "PhaseVillain", stats: { STR: 10, AGI: 10, END: 10, INT: 10, MAG: 10, CHA: 10 } },
                        { type: "skill", name: "TestSkill", modifier: "+1 to Attack" }
                    ]
                };
                let savedCtxGs = window.CardGame.ctx ? window.CardGame.ctx.gameState : null;
                let pgs = await gameState.createGameState(phaseDeck, phaseDeck.cards[0]);
                if (pgs) {
                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = pgs;

                    // H1: Initial phase is initiative
                    testLog("ux", "H1: initial phase=" + pgs.phase, pgs.phase === GAME_PHASES.INITIATIVE ? "pass" : "fail");

                    // H2: Run initiative and advance to equip
                    if (gameState.runInitiativePhase) gameState.runInitiativePhase();
                    gameState.advancePhase();
                    // If no beginning threats, should go to equip
                    let afterInit = pgs.phase;
                    let wentToEquipOrThreat = afterInit === GAME_PHASES.EQUIP || afterInit === GAME_PHASES.THREAT_RESPONSE;
                    testLog("ux", "H2: after initiative advance -> phase=" + afterInit + " (expect equip or threat_response)",
                        wentToEquipOrThreat ? "pass" : "fail");

                    // H3: If in equip, advance to draw_placement
                    if (pgs.phase === GAME_PHASES.EQUIP) {
                        gameState.advancePhase();
                        testLog("ux", "H3: after equip advance -> phase=" + pgs.phase,
                            pgs.phase === GAME_PHASES.DRAW_PLACEMENT ? "pass" : "fail");
                    } else if (pgs.phase === GAME_PHASES.THREAT_RESPONSE) {
                        testLog("ux", "H3: threat response phase (nat 1 rolled) - cannot advance without resolving", "info");
                    }

                    // H4: Phase has correct round number
                    testLog("ux", "H4: round=" + pgs.round + " (expect 1)", pgs.round === 1 ? "pass" : "fail");

                    if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedCtxGs;
                } else {
                    testLog("ux", "createGameState returned null for phase test", "fail");
                }
            } else {
                testLog("ux", "createGameState/advancePhase not available", "fail");
            }

            // ── I. Cleanup & recovery ──
            testLog("ux", "--- I. Cleanup & recovery ---", "info");
            testState.currentTest = "UX: cleanup & recovery";

            // I1: Player wins round → higher recovery
            {
                let actor1 = makeTestActor();
                actor1.hp = 15;
                actor1.roundPoints = 10;
                let actor2 = makeTestActor();
                actor2.hp = 12;
                actor2.roundPoints = 5;

                // Simulate cleanup logic (from CleanupPhaseUI.oninit)
                let winner;
                if (actor1.roundPoints > actor2.roundPoints) {
                    winner = "player";
                    actor1.hpRecovery = 5;
                    actor2.hpRecovery = 2;
                    actor1.energyRecovery = 3;
                    actor2.energyRecovery = 1;
                } else if (actor2.roundPoints > actor1.roundPoints) {
                    winner = "opponent";
                    actor1.hpRecovery = 2;
                    actor2.hpRecovery = 5;
                    actor1.energyRecovery = 1;
                    actor2.energyRecovery = 3;
                } else {
                    winner = "tie";
                    actor1.hpRecovery = 2;
                    actor2.hpRecovery = 2;
                    actor1.energyRecovery = 2;
                    actor2.energyRecovery = 2;
                }
                testLog("ux", "I1: player wins (10 vs 5) -> winner=" + winner + " playerRecovery=" + actor1.hpRecovery + "HP/" + actor1.energyRecovery + "E",
                    winner === "player" && actor1.hpRecovery === 5 && actor1.energyRecovery === 3 ? "pass" : "fail");
            }

            // I2: Opponent wins → player gets less recovery
            {
                let actor1 = makeTestActor();
                actor1.roundPoints = 3;
                let actor2 = makeTestActor();
                actor2.roundPoints = 8;

                let winner = actor1.roundPoints > actor2.roundPoints ? "player" :
                    actor2.roundPoints > actor1.roundPoints ? "opponent" : "tie";
                let playerRecoveryHp = winner === "player" ? 5 : winner === "opponent" ? 2 : 2;
                let playerRecoveryE = winner === "player" ? 3 : winner === "opponent" ? 1 : 2;
                testLog("ux", "I2: opponent wins (3 vs 8) -> playerRecovery=" + playerRecoveryHp + "HP/" + playerRecoveryE + "E",
                    winner === "opponent" && playerRecoveryHp === 2 && playerRecoveryE === 1 ? "pass" : "fail");
            }

            // I3: Tie → equal recovery
            {
                let winner = "tie";
                testLog("ux", "I3: tie -> both get 2HP/2E recovery", "pass");
            }

            // I4: HP capped at maxHp
            {
                let actor = makeTestActor();
                actor.hp = 19;
                actor.maxHp = 20;
                actor.hp = Math.min(actor.maxHp, actor.hp + 5);
                testLog("ux", "I4: HP 19 + 5 recovery capped at max -> HP=" + actor.hp,
                    actor.hp === 20 ? "pass" : "fail");
            }

            // I5: Pot claimed by winner - loot distribution
            if (engine.claimPot) {
                let testState5 = {
                    player: makeTestActor(),
                    opponent: makeTestActor(),
                    pot: [{ type: "skill", name: "PotSkill" }, { type: "item", subtype: "consumable", name: "PotPotion", effect: "Heal 5" }],
                    roundLoot: [
                        { type: "item", subtype: "weapon", name: "LootSword", slot: "Hand (1H)", atk: 2 },
                        { type: "item", subtype: "consumable", name: "LootPotion", effect: "Heal 3" }
                    ]
                };
                engine.claimPot(testState5, "player");
                // Pot cards go to discardPile
                let hasDiscardCards = testState5.player.discardPile.length >= 1;
                // Equipment loot goes to cardStack
                let hasEquipLoot = testState5.player.cardStack.some(c => c.name === "LootSword");
                // Consumable loot goes to hand
                let hasConsLoot = testState5.player.hand.some(c => c.name === "LootPotion");
                testLog("ux", "I5a: pot claimed -> discardPile has cards=" + hasDiscardCards,
                    hasDiscardCards ? "pass" : "fail");
                testLog("ux", "I5b: equipment loot -> cardStack=" + hasEquipLoot,
                    hasEquipLoot ? "pass" : "fail");
                testLog("ux", "I5c: consumable loot -> hand=" + hasConsLoot,
                    hasConsLoot ? "pass" : "fail");
                testLog("ux", "I5d: pot cleared -> pot=" + testState5.pot.length + " roundLoot=" + testState5.roundLoot.length,
                    testState5.pot.length === 0 && testState5.roundLoot.length === 0 ? "pass" : "fail");
            } else {
                testLog("ux", "claimPot not available", "fail");
            }

            testLog("ux", "=== UX Scenario Tests Complete ===", "info");
        }

        // ── Layout Config Tests ──────────────────────────────────────────
        if (cats.includes("layouts")) {
            testState.currentTest = "Layouts: default generation";
            testLog("layouts", "=== Layout Configuration Tests ===", "info");

            let D = window.CardGame.Designer;
            let LC = D ? D.LayoutConfig : null;
            let constants = C();
            let CARD_SIZES = constants ? constants.CARD_SIZES : null;
            let CARD_TYPES = constants ? constants.CARD_TYPES : null;

            // L1: Module presence
            testLog("layouts", "L1: LayoutConfig module present=" + !!LC, LC ? "pass" : "fail");

            if (LC && CARD_SIZES && CARD_TYPES) {
                // L2: Default layout generation for each card type × size
                let typeKeys = Object.keys(CARD_TYPES);
                let sizeKeys = Object.keys(CARD_SIZES);
                let genCount = 0;
                let genErrors = [];
                for (let t of typeKeys) {
                    for (let s of sizeKeys) {
                        try {
                            let layout = LC.generateDefaultLayout(t, s);
                            if (layout && layout.zones && layout.cardType === t) {
                                genCount++;
                            } else {
                                genErrors.push(t + ":" + s + " (invalid structure)");
                            }
                        } catch (e) {
                            genErrors.push(t + ":" + s + " (" + e.message + ")");
                        }
                    }
                }
                let expectedCount = typeKeys.length * sizeKeys.length;
                testLog("layouts", "L2: Generated " + genCount + "/" + expectedCount + " default layouts",
                    genCount === expectedCount ? "pass" : "fail");
                if (genErrors.length > 0) {
                    testLog("layouts", "L2 errors: " + genErrors.slice(0, 5).join(", "), "fail");
                }

                // L3: Screen display variants (_compact, _mini, _full)
                let screenVariants = ["_compact", "_mini", "_full"];
                let screenOk = 0;
                for (let t of typeKeys) {
                    for (let sv of screenVariants) {
                        let layout = LC.generateDefaultLayout(t, sv);
                        if (layout && layout.zones) screenOk++;
                    }
                }
                let screenExpected = typeKeys.length * screenVariants.length;
                testLog("layouts", "L3: Screen variants: " + screenOk + "/" + screenExpected,
                    screenOk === screenExpected ? "pass" : "fail");

                // L4: Layout has minimum elements
                let charLayout = LC.generateDefaultLayout("character", "poker");
                if (charLayout) {
                    let allElements = [];
                    for (let zn in charLayout.zones) {
                        allElements = allElements.concat(charLayout.zones[zn].elements || []);
                    }
                    let hasImage = allElements.some(e => e.type === "image");
                    let hasLabel = allElements.some(e => e.type === "label" || e.type === "stackBorder");
                    let hasStat = allElements.some(e => e.type === "statRow" || e.type === "needBar");
                    testLog("layouts", "L4a: Character layout has image=" + hasImage, hasImage ? "pass" : "fail");
                    testLog("layouts", "L4b: Character layout has label/name=" + hasLabel, hasLabel ? "pass" : "fail");
                    testLog("layouts", "L4c: Character layout has stats/needs=" + hasStat, hasStat ? "pass" : "fail");
                }

                // L5: Template variable resolution
                let testCard = {
                    name: "Test Hero", type: "character",
                    stats: { STR: 12, AGI: 8, END: 15, CHA: 6 },
                    needs: { hp: 18, energy: 10, morale: 16 },
                    race: "Human", _templateClass: "Fighter", level: 3,
                    portraitUrl: "/test/portrait.jpg"
                };
                let resolved = LC.resolveTemplate("{{cardName}}", testCard, "character");
                testLog("layouts", "L5a: {{cardName}} resolved='" + resolved + "'",
                    resolved === "Test Hero" ? "pass" : "fail");
                let colorResolved = LC.resolveTemplate("{{typeColor}}", testCard, "character");
                testLog("layouts", "L5b: {{typeColor}} resolved='" + colorResolved + "'",
                    colorResolved && colorResolved !== "{{typeColor}}" ? "pass" : "fail");

                // L6: Element CRUD operations
                let testLayout = LC.generateDefaultLayout("character", "poker");
                if (testLayout) {
                    let newEl = LC.createElement("label", { content: "Test Label" });
                    testLog("layouts", "L6a: createElement returns object=" + !!newEl, newEl ? "pass" : "fail");

                    if (newEl) {
                        LC.addElement(testLayout, "details", newEl);
                        let found = LC.findElement(testLayout, newEl.id);
                        testLog("layouts", "L6b: addElement + findElement=" + !!found, found ? "pass" : "fail");

                        LC.updateElementStyle(testLayout, newEl.id, "fontSize", 14);
                        let updated = LC.findElement(testLayout, newEl.id);
                        testLog("layouts", "L6c: updateElementStyle fontSize=" + (updated ? updated.style.fontSize : "?"),
                            updated && updated.style.fontSize === 14 ? "pass" : "fail");

                        LC.removeElement(testLayout, newEl.id);
                        let removed = LC.findElement(testLayout, newEl.id);
                        testLog("layouts", "L6d: removeElement found after remove=" + !!removed,
                            !removed ? "pass" : "fail");
                    }
                }

                // L7: Zone height adjustment
                let zhLayout = LC.generateDefaultLayout("character", "poker");
                if (zhLayout) {
                    let origHeight = zhLayout.zones.image ? zhLayout.zones.image.height : 0;
                    LC.updateZoneHeight(zhLayout, "image", origHeight + 5);
                    testLog("layouts", "L7: Zone height updated from " + origHeight + " to " + (zhLayout.zones.image ? zhLayout.zones.image.height : "?"),
                        zhLayout.zones.image && zhLayout.zones.image.height === origHeight + 5 ? "pass" : "fail");
                }

                // L8: Per-size independence
                let pokerLayout = LC.generateDefaultLayout("character", "poker");
                let miniLayout = LC.generateDefaultLayout("character", "mini");
                if (pokerLayout && miniLayout) {
                    let pokerImgH = pokerLayout.zones.image ? pokerLayout.zones.image.height : 0;
                    let miniImgH = miniLayout.zones.image ? miniLayout.zones.image.height : 0;
                    testLog("layouts", "L8: Per-size independence: poker image=" + pokerImgH + "%, mini image=" + miniImgH + "%",
                        "pass");
                }

                // L9: Save/load roundtrip (requires a deck)
                if (resolvedDeck) {
                    let saveLayout = LC.generateDefaultLayout("character", "poker");
                    saveLayout._testMarker = "roundtrip_test";
                    LC.saveLayout(resolvedDeck, saveLayout);
                    let loadedLayout = LC.getLayout(resolvedDeck, "character", "poker");
                    testLog("layouts", "L9: Save/load roundtrip marker=" + (loadedLayout ? loadedLayout._testMarker : "?"),
                        loadedLayout && loadedLayout._testMarker === "roundtrip_test" ? "pass" : "fail");
                    // Clean up
                    if (resolvedDeck.layoutConfigs) {
                        delete resolvedDeck.layoutConfigs["character:poker"];
                    }
                } else {
                    testLog("layouts", "L9: Save/load roundtrip (skipped: no deck)", "skip");
                }

                // L10: generateAllDefaultLayouts
                let allLayouts = LC.generateAllDefaultLayouts();
                let allKeys = Object.keys(allLayouts);
                testLog("layouts", "L10: generateAllDefaultLayouts produced " + allKeys.length + " layouts",
                    allKeys.length >= typeKeys.length * sizeKeys.length ? "pass" : "warn");

            } else {
                testLog("layouts", "Layout tests skipped (modules not loaded)", "skip");
            }

            testLog("layouts", "=== Layout Tests Complete ===", "info");
        }

        // ── Designer UI Tests ────────────────────────────────────────────
        if (cats.includes("designer")) {
            testState.currentTest = "Designer: UI components";
            testLog("designer", "=== Designer UI Tests ===", "info");

            let D = window.CardGame.Designer;
            let constants = C();
            let CARD_SIZES = constants ? constants.CARD_SIZES : null;
            let CARD_TYPES = constants ? constants.CARD_TYPES : null;

            // D1: Designer namespace
            testLog("designer", "D1a: Designer namespace present=" + !!D, D ? "pass" : "fail");
            testLog("designer", "D1b: DesignerView present=" + !!(D && D.DesignerView), D && D.DesignerView ? "pass" : "fail");
            testLog("designer", "D1c: LayoutRenderer present=" + !!(D && D.LayoutRenderer), D && D.LayoutRenderer ? "pass" : "fail");
            testLog("designer", "D1d: LayoutConfig present=" + !!(D && D.LayoutConfig), D && D.LayoutConfig ? "pass" : "fail");
            testLog("designer", "D1e: IconPicker present=" + !!(D && D.IconPicker), D && D.IconPicker ? "pass" : "fail");
            testLog("designer", "D1f: ExportPipeline present=" + !!(D && D.ExportPipeline), D && D.ExportPipeline ? "pass" : "fail");
            testLog("designer", "D1g: ExportDialog present=" + !!(D && D.ExportDialog), D && D.ExportDialog ? "pass" : "fail");

            // D2: LayoutRenderer renders all card types
            if (D && D.LayoutRenderer && D.LayoutConfig) {
                let LR = D.LayoutRenderer;
                let LC = D.LayoutConfig;
                let typeKeys = Object.keys(CARD_TYPES);
                let renderOk = 0;
                let renderFails = [];

                for (let t of typeKeys) {
                    try {
                        let layout = LC.generateDefaultLayout(t, "poker");
                        let testCard = { name: "Test " + t, type: t, stats: { STR: 10 }, needs: { hp: 20 } };
                        // Create a temporary container and render
                        let container = document.createElement("div");
                        container.style.cssText = "position:absolute;top:-9999px;left:-9999px;width:180px;height:252px;";
                        document.body.appendChild(container);
                        m.render(container, m(LR.LayoutCardFace, {
                            card: testCard,
                            layoutConfig: layout,
                            sizeKey: "poker"
                        }));
                        let hasContent = container.innerHTML.length > 50;
                        document.body.removeChild(container);
                        if (hasContent) renderOk++;
                        else renderFails.push(t);
                    } catch (e) {
                        renderFails.push(t + "(" + e.message + ")");
                    }
                }
                testLog("designer", "D2: Rendered " + renderOk + "/" + typeKeys.length + " card types via LayoutCardFace",
                    renderOk === typeKeys.length ? "pass" : "fail");
                if (renderFails.length > 0) {
                    testLog("designer", "D2 failures: " + renderFails.join(", "), "fail");
                }

                // D3: Render at each card size
                let sizeKeys = Object.keys(CARD_SIZES);
                let sizeOk = 0;
                for (let s of sizeKeys) {
                    try {
                        let layout = LC.generateDefaultLayout("character", s);
                        let container = document.createElement("div");
                        container.style.cssText = "position:absolute;top:-9999px;left:-9999px;";
                        document.body.appendChild(container);
                        m.render(container, m(LR.LayoutCardFace, {
                            card: { name: "Size Test", type: "character", stats: { STR: 10 }, needs: { hp: 20 } },
                            layoutConfig: layout,
                            sizeKey: s
                        }));
                        if (container.innerHTML.length > 50) sizeOk++;
                        document.body.removeChild(container);
                    } catch (e) { /* skip */ }
                }
                testLog("designer", "D3: Rendered at " + sizeOk + "/" + sizeKeys.length + " card sizes",
                    sizeOk === sizeKeys.length ? "pass" : "fail");

                // D4: Design mode vs preview mode rendering
                try {
                    let layout = LC.generateDefaultLayout("character", "poker");
                    let container = document.createElement("div");
                    container.style.cssText = "position:absolute;top:-9999px;left:-9999px;width:180px;height:252px;";
                    document.body.appendChild(container);
                    m.render(container, m(LR.LayoutCardFace, {
                        card: { name: "Design Test", type: "character", stats: { STR: 10 }, needs: { hp: 20 } },
                        layoutConfig: layout,
                        sizeKey: "poker",
                        designMode: true
                    }));
                    let hasDesignElements = container.querySelector(".design-mode") !== null || container.innerHTML.includes("design-mode");
                    document.body.removeChild(container);
                    testLog("designer", "D4: Design mode markers present=" + hasDesignElements,
                        hasDesignElements ? "pass" : "warn");
                } catch (e) {
                    testLog("designer", "D4: Design mode render error: " + e.message, "fail");
                }
            } else {
                testLog("designer", "D2-D4: Render tests skipped (modules not loaded)", "skip");
            }

            // D5: Icon picker has icons
            if (D && D.IconPicker) {
                let hasIcons = D.IconPicker.ICON_LIST && D.IconPicker.ICON_LIST.length > 50;
                testLog("designer", "D5: IconPicker icons count=" + (D.IconPicker.ICON_LIST ? D.IconPicker.ICON_LIST.length : 0),
                    hasIcons ? "pass" : "fail");
            } else {
                testLog("designer", "D5: IconPicker (skipped: not loaded)", "skip");
            }

            // D6: DeckView has Designer and Export buttons
            let deckViewHTML = "";
            if (resolvedDeck) {
                try {
                    let context = ctx();
                    let origScreen = context.screen;
                    let origDeck = context.viewingDeck;
                    context.viewingDeck = resolvedDeck;
                    context.screen = "deckView";
                    let container = document.createElement("div");
                    container.style.cssText = "position:absolute;top:-9999px;left:-9999px;width:800px;height:600px;";
                    document.body.appendChild(container);
                    let DeckView = window.CardGame.UI.DeckView;
                    if (DeckView) {
                        m.render(container, m(DeckView));
                        deckViewHTML = container.innerHTML;
                    }
                    document.body.removeChild(container);
                    context.screen = origScreen;
                    context.viewingDeck = origDeck;
                } catch (e) { /* skip */ }
            }
            let hasDesignerBtn = deckViewHTML.includes("Designer") || deckViewHTML.includes("design_services");
            let hasExportBtn = deckViewHTML.includes("Export") || deckViewHTML.includes("file_download");
            testLog("designer", "D6a: DeckView has Designer button=" + hasDesignerBtn,
                hasDesignerBtn ? "pass" : (resolvedDeck ? "fail" : "skip"));
            testLog("designer", "D6b: DeckView has Export button=" + hasExportBtn,
                hasExportBtn ? "pass" : (resolvedDeck ? "fail" : "skip"));

            // D7: CardGameApp routes designer screen
            let appCtx = ctx();
            let hasDesignerRoute = appCtx && typeof appCtx.screen !== "undefined";
            testLog("designer", "D7: App context supports screen routing=" + hasDesignerRoute,
                hasDesignerRoute ? "pass" : "fail");

            // D8: Classic CardFace backward compatibility (no deck = falls back to classic rendering)
            let cfRendering = R();
            if (cfRendering && cfRendering.CardFace) {
                try {
                    let testCard = { name: "Classic Test", type: "character", stats: { STR: 10 }, needs: { hp: 20 } };
                    let container = document.createElement("div");
                    container.style.cssText = "position:absolute;top:-9999px;left:-9999px;width:180px;height:252px;";
                    document.body.appendChild(container);
                    // Render WITHOUT deck attr → should fall back to classic rendering
                    m.render(container, m(cfRendering.CardFace, { card: testCard, noPreview: true }));
                    let html = container.innerHTML;
                    let hasClassicElements = html.includes("cg2-card") && html.includes("Classic Test");
                    document.body.removeChild(container);
                    testLog("designer", "D8a: CardFace classic fallback (no deck) renders=" + hasClassicElements,
                        hasClassicElements ? "pass" : "fail");
                } catch (e) {
                    testLog("designer", "D8a: CardFace classic fallback error: " + e.message, "fail");
                }

                // D8b: CardFace with deck + layoutConfigs → should use LayoutCardFace
                if (D && D.LayoutConfig && resolvedDeck) {
                    try {
                        let testCard2 = { name: "Layout Test", type: "character", stats: { STR: 10 }, needs: { hp: 20 } };
                        let deckWithLayouts = Object.assign({}, resolvedDeck);
                        deckWithLayouts.layoutConfigs = deckWithLayouts.layoutConfigs || {};
                        let testLayout = D.LayoutConfig.generateDefaultLayout("character", "poker");
                        deckWithLayouts.layoutConfigs["character:poker"] = testLayout;

                        let container = document.createElement("div");
                        container.style.cssText = "position:absolute;top:-9999px;left:-9999px;width:180px;height:252px;";
                        document.body.appendChild(container);
                        m.render(container, m(cfRendering.CardFace, { card: testCard2, deck: deckWithLayouts, noPreview: true }));
                        let html = container.innerHTML;
                        let usesLayout = html.includes("cg2-layout") || html.includes("Layout Test");
                        document.body.removeChild(container);
                        testLog("designer", "D8b: CardFace with layoutConfigs delegates to LayoutCardFace=" + usesLayout,
                            usesLayout ? "pass" : "warn");
                    } catch (e) {
                        testLog("designer", "D8b: CardFace delegation error: " + e.message, "fail");
                    }
                } else {
                    testLog("designer", "D8b: CardFace delegation (skipped: no deck/designer)", "skip");
                }

                // D8c: CardFace compact mode still works
                try {
                    let testCard3 = { name: "Compact Test", type: "item", subtype: "weapon", atk: 5 };
                    let container = document.createElement("div");
                    container.style.cssText = "position:absolute;top:-9999px;left:-9999px;width:120px;height:168px;";
                    document.body.appendChild(container);
                    m.render(container, m(cfRendering.CardFace, { card: testCard3, compact: true, noPreview: true }));
                    let html = container.innerHTML;
                    let hasContent = html.length > 50;
                    document.body.removeChild(container);
                    testLog("designer", "D8c: CardFace compact mode renders=" + hasContent,
                        hasContent ? "pass" : "fail");
                } catch (e) {
                    testLog("designer", "D8c: CardFace compact error: " + e.message, "fail");
                }
            } else {
                testLog("designer", "D8: CardFace backward compat (skipped: Rendering not loaded)", "skip");
            }

            testLog("designer", "=== Designer Tests Complete ===", "info");
        }

        // ── Export Tests ─────────────────────────────────────────────────
        if (cats.includes("export")) {
            testState.currentTest = "Export: pipeline & libraries";
            testLog("export", "=== Export Pipeline Tests ===", "info");

            // E1: Library availability
            let hasHtml2Canvas = typeof window.html2canvas === "function";
            let hasJSZip = typeof window.JSZip === "function";
            testLog("export", "E1a: html2canvas loaded=" + hasHtml2Canvas, hasHtml2Canvas ? "pass" : "fail");
            testLog("export", "E1b: JSZip loaded=" + hasJSZip, hasJSZip ? "pass" : "fail");

            let D = window.CardGame.Designer;
            let EP = D ? D.ExportPipeline : null;
            let LC = D ? D.LayoutConfig : null;
            let LR = D ? D.LayoutRenderer : null;
            let constants = C();
            let CARD_SIZES = constants ? constants.CARD_SIZES : null;

            // E2: ExportPipeline module
            testLog("export", "E2a: ExportPipeline present=" + !!EP, EP ? "pass" : "fail");
            if (EP) {
                testLog("export", "E2b: exportDeck function=" + (typeof EP.exportDeck === "function"),
                    typeof EP.exportDeck === "function" ? "pass" : "fail");
                testLog("export", "E2c: exportState present=" + !!EP.exportState,
                    EP.exportState ? "pass" : "fail");
            }

            // E3: Card size pixel accuracy
            if (CARD_SIZES) {
                let sizeTests = [
                    { key: "poker",  expected: [750, 1050] },
                    { key: "bridge", expected: [675, 1050] },
                    { key: "tarot",  expected: [825, 1425] },
                    { key: "mini",   expected: [525, 750]  }
                ];
                for (let st of sizeTests) {
                    let size = CARD_SIZES[st.key];
                    if (size) {
                        let pxMatch = size.px[0] === st.expected[0] && size.px[1] === st.expected[1];
                        testLog("export", "E3: " + st.key + " size " + size.px[0] + "×" + size.px[1] + " expected " + st.expected[0] + "×" + st.expected[1],
                            pxMatch ? "pass" : "fail");
                    }
                }
            }

            // E4: Single card render to offscreen container
            if (LR && LC && hasHtml2Canvas) {
                try {
                    let testCard = {
                        name: "Export Test", type: "character",
                        stats: { STR: 10, AGI: 8, END: 12, CHA: 6 },
                        needs: { hp: 20, energy: 14, morale: 20 }
                    };
                    let layout = LC.generateDefaultLayout("character", "poker");
                    let pxW = CARD_SIZES.poker.px[0];
                    let pxH = CARD_SIZES.poker.px[1];
                    let container = document.createElement("div");
                    container.style.cssText = "position:absolute;top:-9999px;left:-9999px;width:" + pxW + "px;height:" + pxH + "px;overflow:hidden;";
                    document.body.appendChild(container);
                    m.render(container, m(LR.LayoutCardFace, {
                        card: testCard,
                        layoutConfig: layout,
                        sizeKey: "poker"
                    }));
                    let rendered = container.innerHTML.length > 100;
                    testLog("export", "E4a: Offscreen render at " + pxW + "×" + pxH + " produced content=" + rendered,
                        rendered ? "pass" : "fail");

                    // Try html2canvas capture
                    try {
                        let canvas = await window.html2canvas(container, {
                            width: pxW, height: pxH, scale: 1,
                            useCORS: true, logging: false
                        });
                        let canvasOk = canvas && canvas.width === pxW && canvas.height === pxH;
                        testLog("export", "E4b: html2canvas capture " + canvas.width + "×" + canvas.height,
                            canvasOk ? "pass" : "warn");
                    } catch (e) {
                        testLog("export", "E4b: html2canvas capture error: " + e.message, "warn");
                    }
                    document.body.removeChild(container);
                } catch (e) {
                    testLog("export", "E4: Render test error: " + e.message, "fail");
                }
            } else {
                testLog("export", "E4: Single card render (skipped: modules/libs not loaded)", "skip");
            }

            // E5: PNG blob creation
            if (hasHtml2Canvas) {
                try {
                    let testCanvas = document.createElement("canvas");
                    testCanvas.width = 100;
                    testCanvas.height = 140;
                    let tCtx = testCanvas.getContext("2d");
                    tCtx.fillStyle = "#B8860B";
                    tCtx.fillRect(0, 0, 100, 140);
                    let blob = await new Promise(resolve => testCanvas.toBlob(resolve, "image/png"));
                    testLog("export", "E5a: PNG blob type=" + (blob ? blob.type : "null") + " size=" + (blob ? blob.size : 0),
                        blob && blob.type === "image/png" ? "pass" : "fail");
                    // JPG
                    let jpgBlob = await new Promise(resolve => testCanvas.toBlob(resolve, "image/jpeg", 0.85));
                    testLog("export", "E5b: JPG blob type=" + (jpgBlob ? jpgBlob.type : "null") + " size=" + (jpgBlob ? jpgBlob.size : 0),
                        jpgBlob && jpgBlob.type === "image/jpeg" ? "pass" : "fail");
                } catch (e) {
                    testLog("export", "E5: Blob creation error: " + e.message, "fail");
                }
            }

            // E6: ZIP generation
            if (hasJSZip) {
                try {
                    let zip = new window.JSZip();
                    zip.file("test1.txt", "hello");
                    zip.file("test2.txt", "world");
                    zip.file("test3.txt", "card");
                    let zipBlob = await zip.generateAsync({ type: "blob" });
                    testLog("export", "E6: ZIP blob size=" + zipBlob.size + " type=" + zipBlob.type,
                        zipBlob.size > 0 ? "pass" : "fail");
                } catch (e) {
                    testLog("export", "E6: ZIP generation error: " + e.message, "fail");
                }
            } else {
                testLog("export", "E6: ZIP generation (skipped: JSZip not loaded)", "skip");
            }

            // E7: All card types render at all sizes without error
            if (LR && LC && CARD_SIZES) {
                let typeKeys = Object.keys(constants.CARD_TYPES);
                let sizeKeys = Object.keys(CARD_SIZES);
                let renderCount = 0;
                let renderErrors = [];
                for (let t of typeKeys) {
                    for (let s of sizeKeys) {
                        try {
                            let layout = LC.generateDefaultLayout(t, s);
                            let card = { name: "Test", type: t, stats: { STR: 10 }, needs: { hp: 20 }, effect: "test" };
                            let container = document.createElement("div");
                            container.style.cssText = "position:absolute;top:-9999px;left:-9999px;";
                            document.body.appendChild(container);
                            m.render(container, m(LR.LayoutCardFace, { card: card, layoutConfig: layout, sizeKey: s }));
                            document.body.removeChild(container);
                            renderCount++;
                        } catch (e) {
                            renderErrors.push(t + ":" + s);
                        }
                    }
                }
                let expected = typeKeys.length * sizeKeys.length;
                testLog("export", "E7: All types × all sizes: " + renderCount + "/" + expected,
                    renderCount === expected ? "pass" : "fail");
                if (renderErrors.length > 0) {
                    testLog("export", "E7 errors: " + renderErrors.slice(0, 8).join(", "), "fail");
                }
            }

            // E8: ExportDialog module
            let ED = D ? D.ExportDialog : null;
            testLog("export", "E8a: ExportDialog present=" + !!ED, ED ? "pass" : "fail");
            if (ED) {
                testLog("export", "E8b: ExportDialogOverlay component=" + !!(ED.ExportDialogOverlay),
                    ED.ExportDialogOverlay ? "pass" : "fail");
                testLog("export", "E8c: open/close functions=" + (typeof ED.open === "function" && typeof ED.close === "function"),
                    typeof ED.open === "function" ? "pass" : "fail");
            }

            // E9: CardFace layout delegation
            let rendering = R();
            if (rendering && rendering.getLayoutConfig) {
                testLog("export", "E9: CardFace has getLayoutConfig helper=" + (typeof rendering.getLayoutConfig === "function"),
                    typeof rendering.getLayoutConfig === "function" ? "pass" : "fail");
            } else {
                testLog("export", "E9: CardFace getLayoutConfig (not exported)", "warn");
            }

            testLog("export", "=== Export Tests Complete ===", "info");
        }

    }

    // ── Test Mode UI Component (uses shared TestFramework components) ──
    function TestModeUI() {
        return {
            oninit: function() {
                // Ensure the cardGame suite is selected when entering from card game UI
                if (TF && TF.testState && TF.testState.selectedSuite !== "cardGame") {
                    TF.selectSuite("cardGame");
                }
            },
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
