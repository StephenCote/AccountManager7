/**
 * CardGame Test Mode - Comprehensive Test Suite
 * Phase 9: Runs tests across storage, narration, combat, card evaluation,
 * campaign, LLM, voice, and automated playthrough categories.
 *
 * Depends on:
 *   - window.CardGame.Constants (STATUS_EFFECTS, COMBAT_OUTCOMES)
 *   - window.CardGame.Storage (deckStorage, gameStorage, campaignStorage, encodeJson, decodeJson,
 *       createCampaignData, calculateXP, applyCampaignBonuses)
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
    function ctx()     { return window.CardGame.ctx || {}; }

    // ── Test Categories ──────────────────────────────────────────────────
    const TEST_CATEGORIES = {
        storage:     { label: "Storage",     icon: "database" },
        narration:   { label: "Narration",   icon: "campaign" },
        combat:      { label: "Combat",      icon: "swords" },
        cards:       { label: "Card Eval",   icon: "playing_cards" },
        campaign:    { label: "Campaign",    icon: "military_tech" },
        llm:         { label: "LLM",         icon: "smart_toy" },
        voice:       { label: "Voice",       icon: "record_voice_over" },
        playthrough: { label: "Playthrough", icon: "sports_esports" }
    };

    // ── State ────────────────────────────────────────────────────────────
    let testDeck = null;       // Deck selected for testing (set from DeckView)
    let testDeckName = null;

    let testState = {
        running: false,
        logs: [],       // [{ time, category, message, status: "info"|"pass"|"fail"|"warn" }]
        results: { pass: 0, fail: 0, warn: 0, skip: 0 },
        currentTest: null,
        completed: false,
        selectedCategories: Object.keys(TEST_CATEGORIES),
        autoPlaySpeed: 500  // ms between auto-play actions
    };

    // ── Public setters for testDeck (called from DeckView / DeckList) ────
    function setTestDeck(deck, name) {
        testDeck = deck;
        testDeckName = name;
    }
    function clearTestDeck() {
        testDeck = null;
        testDeckName = null;
    }

    // ── Logging ──────────────────────────────────────────────────────────
    function testLog(category, message, status) {
        if (status === undefined) status = "info";
        let entry = { time: new Date().toISOString().substring(11, 19), category: category, message: message, status: status };
        testState.logs.push(entry);
        if (status === "pass") testState.results.pass++;
        else if (status === "fail") testState.results.fail++;
        else if (status === "warn") testState.results.warn++;
        console.log("[TestMode] [" + category + "] [" + status + "] " + message);
        m.redraw();
    }

    // ── Main Test Suite ──────────────────────────────────────────────────
    async function runTestSuite() {
        testState.running = true;
        testState.logs = [];
        testState.results = { pass: 0, fail: 0, warn: 0, skip: 0 };
        testState.completed = false;
        m.redraw();

        let cats = testState.selectedCategories;

        // Resolve shorthand accessors
        let storage = S();
        let engine = Eng();
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
            let activeTheme = themes.activeTheme ? themes.activeTheme() : null;
            if (resolvedDeck.themeId && (!activeTheme || activeTheme.themeId !== resolvedDeck.themeId)) {
                if (themes.loadThemeConfig) {
                    await themes.loadThemeConfig(resolvedDeck.themeId);
                    activeTheme = themes.activeTheme ? themes.activeTheme() : null;
                }
                testLog("", "Loaded theme: " + (activeTheme?.name || resolvedDeck.themeId), "info");
            }
        } else {
            testLog("", "No deck available for testing -- some tests will be skipped", "warn");
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
                // Access game state and narration functions from context
                let gameState = context.gameState;
                let showNarrationSubtitle = engine.showNarrationSubtitle;
                let triggerNarration = engine.triggerNarration;
                let gameNarrator = ai.getNarrator ? ai.getNarrator() : null;

                // Create temporary gameState for testing
                let savedGs = gameState;
                let tempGs = { narrationText: null, narrationTime: null, player: { character: { name: "TestHero" } }, opponent: { character: { name: "TestVillain" } }, round: 1 };

                if (context.setGameState) context.setGameState(tempGs);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;

                if (showNarrationSubtitle) {
                    showNarrationSubtitle(testText);
                    let gs = context.gameState || tempGs;
                    if (gs.narrationText === testText) {
                        testLog("narration", "showNarrationSubtitle sets narrationText", "pass");
                    } else {
                        testLog("narration", "showNarrationSubtitle did NOT set narrationText", "fail");
                    }
                    // Timer is async and can't be verified synchronously
                    testLog("narration", "Auto-hide timer scheduled (8s, cannot verify synchronously)", "info");
                    gs.narrationText = null;
                } else {
                    testLog("narration", "showNarrationSubtitle not available (module not yet extracted)", "warn");
                }

                // Restore
                if (context.setGameState) context.setGameState(savedGs);
                else if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs;
            } catch (e) {
                testLog("narration", "Narration test error: " + e.message, "fail");
            }

            // Test triggerNarration fallback text
            try {
                let triggerNarration = engine.triggerNarration;
                let gameState = context.gameState;
                let savedGs = gameState;
                let savedNarrator = ai.getNarrator ? ai.getNarrator() : null;

                if (ai.setNarrator) ai.setNarrator(null); // Force fallback path
                let tempGs = { narrationText: null, narrationTime: null, player: { character: { name: "Hero" }, hp: 15, energy: 10 }, opponent: { character: { name: "Villain" }, hp: 12, energy: 8 }, round: 3 };
                if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;

                if (triggerNarration) {
                    await triggerNarration("game_start");
                    let gs = window.CardGame.ctx?.gameState || tempGs;
                    if (gs.narrationText && gs.narrationText.includes("Hero")) {
                        testLog("narration", "game_start fallback text includes player name", "pass");
                    } else {
                        testLog("narration", "game_start fallback text missing", "fail");
                    }
                    gs.narrationText = null;
                    await triggerNarration("round_start");
                    if (gs.narrationText && gs.narrationText.includes("Round 3")) {
                        testLog("narration", "round_start fallback for round 3", "pass");
                    } else {
                        testLog("narration", "round_start fallback missing/wrong", gs.round <= 1 ? "pass" : "fail");
                    }
                    gs.narrationText = null;
                    await triggerNarration("resolution", { isPlayerAttack: true, outcome: "CRIT", damage: 15 });
                    if (gs.narrationText && gs.narrationText.includes("Critical")) {
                        testLog("narration", "resolution CRIT fallback text", "pass");
                    } else {
                        testLog("narration", "resolution CRIT fallback missing", "fail");
                    }
                } else {
                    testLog("narration", "triggerNarration not available (module not yet extracted)", "warn");
                }

                if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs;
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
            let activeTheme = themes.activeTheme ? themes.activeTheme() : null;
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
            testState.currentTest = "Campaign: XP, levels, stat gains";
            testLog("campaign", "Testing campaign system...");

            // Test createCampaignData
            let testChar = { name: "TestHero", sourceId: "test-123" };
            let cd = storage.createCampaignData(testChar);
            if (cd.level === 1 && cd.xp === 0 && cd.characterName === "TestHero") {
                testLog("campaign", "createCampaignData defaults correct", "pass");
            } else {
                testLog("campaign", "createCampaignData defaults wrong", "fail");
            }

            // Test calculateXP
            let testState1 = { round: 5, player: { hp: 15 } };
            let xpWin = storage.calculateXP(testState1, true);
            let xpLose = storage.calculateXP(testState1, false);
            testLog("campaign", "calculateXP(5 rounds, 15hp, win)=" + xpWin + " (expect 130)", xpWin === 130 ? "pass" : "fail");
            testLog("campaign", "calculateXP(5 rounds, 15hp, loss)=" + xpLose + " (expect 80)", xpLose === 80 ? "pass" : "fail");

            // Test level calculation
            let levels = [
                { xp: 0, expect: 1 }, { xp: 99, expect: 1 }, { xp: 100, expect: 2 },
                { xp: 250, expect: 3 }, { xp: 999, expect: 10 }, { xp: 1500, expect: 10 }
            ];
            for (let l of levels) {
                let level = Math.min(10, Math.floor(l.xp / 100) + 1);
                testLog("campaign", "XP " + l.xp + " -> Level " + level + " (expect " + l.expect + ")", level === l.expect ? "pass" : "fail");
            }

            // Test applyCampaignBonuses
            let testGsState = {
                player: {
                    character: { stats: { STR: 10, AGI: 10, END: 15, INT: 10, MAG: 12, CHA: 10 } },
                    ap: 4, maxAp: 4, energy: 12, maxEnergy: 12
                }
            };
            let testCampaign = { statGains: { STR: 2, END: 5 } };
            storage.applyCampaignBonuses(testGsState, testCampaign);
            if (testGsState.player.character.stats.STR === 12 && testGsState.player.character.stats.END === 20) {
                testLog("campaign", "applyCampaignBonuses: stats applied (STR 10->12, END 15->20)", "pass");
            } else {
                testLog("campaign", "applyCampaignBonuses: stats wrong", "fail");
            }
            // END=20 -> AP = floor(20/5)+1 = 5
            if (testGsState.player.ap === 5) {
                testLog("campaign", "applyCampaignBonuses: AP recalculated (END 20 -> AP 5)", "pass");
            } else {
                testLog("campaign", "applyCampaignBonuses: AP not recalculated (got " + testGsState.player.ap + ")", "fail");
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
                    if (engine.createGameState) {
                        let statsBefore = JSON.stringify(ch.stats);
                        let gs = engine.createGameState(deck, ch);
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
                if (deck && engine.createGameState) {
                    let gs = engine.createGameState(deck);
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
                } else if (!engine.createGameState) {
                    testLog("storage", "createGameState not available (module not yet extracted)", "warn");
                }
            }
        }

        // ── LLM Tests ────────────────────────
        if (cats.includes("llm")) {
            testState.currentTest = "LLM: connectivity and chat";
            testLog("llm", "Testing LLM connectivity...");

            try {
                let checkLlmConnectivity = ai.checkLlmConnectivity || engine.checkLlmConnectivity;
                let llmStatus = ai.llmStatus || engine.llmStatus || { checked: false, available: false };
                let CardGameLLM = ai.CardGameLLM;
                let CardGameNarrator = ai.CardGameNarrator;

                if (checkLlmConnectivity) {
                    await checkLlmConnectivity();
                    llmStatus = ai.llmStatus || engine.llmStatus || llmStatus;
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
                                let themeId = resolvedDeck?.themeId || (themes.activeTheme ? themes.activeTheme()?.themeId : null) || "high-fantasy";
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
                let savedGs = context.gameState;
                let tempGs = { narrationText: null, narrationTime: null };
                if (window.CardGame.ctx) window.CardGame.ctx.gameState = tempGs;
                await testVoice1.speak("Voice test subtitle text");
                let gs = window.CardGame.ctx?.gameState || tempGs;
                if (gs.narrationText === "Voice test subtitle text") {
                    testLog("voice", "speak() in subtitlesOnly mode -> subtitle displayed", "pass");
                } else {
                    testLog("voice", "speak() in subtitlesOnly mode -> subtitle NOT displayed (narrationText=" + gs.narrationText + ")", "fail");
                }
                gs.narrationText = null;
                if (window.CardGame.ctx) window.CardGame.ctx.gameState = savedGs;

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

            let initializeLLMComponents = engine.initializeLLMComponents || ai.initializeLLMComponents;
            let CardGameVoice = ai.CardGameVoice;
            let llmStatus = ai.llmStatus || engine.llmStatus || { checked: false, available: false };

            if (resolvedDeck && initializeLLMComponents) {
                // Save/restore actual game globals
                let savedNarrator = ai.getNarrator ? ai.getNarrator() : null;
                let savedDirector = ai.getDirector ? ai.getDirector() : null;
                let savedChatMgr = ai.getChatManager ? ai.getChatManager() : null;
                let savedVoice = ai.getVoice ? ai.getVoice() : null;
                let savedAnnVoice = ai.getAnnouncerVoice ? ai.getAnnouncerVoice() : null;
                let savedGs = context.gameState;

                // Create a temporary game state for the tests
                let testGs = engine.createGameState ? engine.createGameState(resolvedDeck) : null;
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
                    await initializeLLMComponents(testGs, disabledDeck);

                    let gameNarrator = ai.getNarrator ? ai.getNarrator() : null;
                    let gameChatManager = ai.getChatManager ? ai.getChatManager() : null;
                    let gameVoice = ai.getVoice ? ai.getVoice() : null;
                    let gameAnnouncerVoice = ai.getAnnouncerVoice ? ai.getAnnouncerVoice() : null;

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
                        announcerVoiceEnabled: true,
                        announcerProfile: "dungeon-master"
                    };
                    await initializeLLMComponents(testGs, enabledDeck);

                    gameNarrator = ai.getNarrator ? ai.getNarrator() : null;
                    gameChatManager = ai.getChatManager ? ai.getChatManager() : null;
                    gameVoice = ai.getVoice ? ai.getVoice() : null;
                    gameAnnouncerVoice = ai.getAnnouncerVoice ? ai.getAnnouncerVoice() : null;

                    if (llmStatus.available) {
                        testLog("llm", "narration=on -> gameNarrator=" + (gameNarrator ? "active (" + (gameNarrator.profile || "?") + ")" : "null (LLM init may have failed)"),
                            gameNarrator ? "pass" : "warn");
                        testLog("llm", "narration=on -> gameChatManager=" + (gameChatManager ? "active" : "null (LLM init may have failed)"),
                            gameChatManager ? "pass" : "warn");
                        testLog("llm", "announcer profile: " + (gameNarrator?.profile || "not set"),
                            gameNarrator?.profile === "dungeon-master" ? "pass" : "warn");
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
                        announcerVoiceEnabled: false,
                        announcerProfile: "war-correspondent"
                    };
                    await initializeLLMComponents(testGs, mixedDeck);

                    gameVoice = ai.getVoice ? ai.getVoice() : null;
                    gameAnnouncerVoice = ai.getAnnouncerVoice ? ai.getAnnouncerVoice() : null;
                    gameNarrator = ai.getNarrator ? ai.getNarrator() : null;

                    testLog("voice", "oppVoice=off, annVoice=off -> opp subtitlesOnly=" + gameVoice?.subtitlesOnly,
                        gameVoice?.subtitlesOnly === true ? "pass" : "fail");
                    testLog("voice", "annVoice=off -> gameAnnouncerVoice=" + (gameAnnouncerVoice === null ? "null" : "active"),
                        gameAnnouncerVoice === null ? "pass" : "fail");
                    if (llmStatus.available && gameNarrator) {
                        testLog("llm", "announcer profile: " + (gameNarrator.profile || "not set"),
                            gameNarrator.profile === "war-correspondent" ? "pass" : "warn");
                    }

                    // ── Test 4: Deck's actual saved config ──
                    let deckGc = resolvedDeck.gameConfig;
                    if (deckGc && Object.keys(deckGc).length > 0) {
                        testLog("llm", "Config test: deck's saved config...");
                        testLog("llm", "Deck gameConfig: " + JSON.stringify(deckGc), "info");
                        await initializeLLMComponents(testGs, resolvedDeck);

                        gameNarrator = ai.getNarrator ? ai.getNarrator() : null;
                        gameChatManager = ai.getChatManager ? ai.getChatManager() : null;
                        gameVoice = ai.getVoice ? ai.getVoice() : null;
                        gameAnnouncerVoice = ai.getAnnouncerVoice ? ai.getAnnouncerVoice() : null;

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
                if (ai.setNarrator) ai.setNarrator(savedNarrator);
                if (ai.setDirector) ai.setDirector(savedDirector);
                if (ai.setChatManager) ai.setChatManager(savedChatMgr);
                if (ai.setVoice) ai.setVoice(savedVoice);
                if (ai.setAnnouncerVoice) ai.setAnnouncerVoice(savedAnnVoice);
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
            } else if (!engine.createGameState) {
                testLog("playthrough", "createGameState not available (module not yet extracted)", "warn");
            } else {
                let deckName = resolvedDeckName;
                let deck = resolvedDeck;
                {
                    testLog("playthrough", "Using deck: " + (deck.deckName || deckName) + " (" + (deck.cards || []).length + " cards)", "pass");

                    // Create game state
                    let gs = engine.createGameState(deck);
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

        testState.currentTest = null;
        testState.running = false;
        testState.completed = true;
        testLog("", "=== Test suite complete: " + testState.results.pass + " pass, " + testState.results.fail + " fail, " + testState.results.warn + " warn ===", testState.results.fail > 0 ? "fail" : "pass");
        m.redraw();
    }

    // ── Test Mode UI Component ───────────────────────────────────────────
    function TestModeUI() {
        return {
            view: function() {
                let statusColors = { info: "#777", pass: "#2E7D32", fail: "#C62828", warn: "#E65100" };
                let statusIcons = { info: "info", pass: "check_circle", fail: "error", warn: "warning" };
                let context = ctx();

                return m("div", { class: "cg2-test-mode" }, [
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", { class: "cg2-btn", onclick: function() {
                            if (testDeck && context.viewingDeck) {
                                if (window.CardGame.ctx) window.CardGame.ctx.screen = "deckView";
                            } else {
                                if (window.CardGame.ctx) window.CardGame.ctx.screen = "deckList";
                            }
                            m.redraw();
                        } }, "\u2190 Back"),
                        m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, "Test Mode"),
                        testDeckName ? m("span", { class: "cg2-deck-theme-badge", style: { marginLeft: "8px" } }, testDeckName) : null,
                        !testState.running ? m("button", {
                            class: "cg2-btn cg2-btn-primary", style: { marginLeft: "auto" },
                            onclick: function() { runTestSuite(); }
                        }, [m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "play_arrow"), "Run All Tests"]) : null,
                        testState.running ? m("span", { style: { marginLeft: "auto", color: "#B8860B", fontWeight: 600, fontSize: "13px" } }, [
                            m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "sync"),
                            testState.currentTest || "Running..."
                        ]) : null
                    ]),

                    // Category toggles
                    m("div", { class: "cg2-test-categories" },
                        Object.entries(TEST_CATEGORIES).map(function(pair) {
                            let key = pair[0];
                            let cat = pair[1];
                            let active = testState.selectedCategories.includes(key);
                            return m("label", { class: "cg2-test-cat" + (active ? " active" : ""), onclick: function() {
                                if (active) testState.selectedCategories = testState.selectedCategories.filter(function(c) { return c !== key; });
                                else testState.selectedCategories.push(key);
                                m.redraw();
                            } }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, cat.icon),
                                " " + cat.label
                            ]);
                        })
                    ),

                    // Results summary
                    testState.completed ? m("div", { class: "cg2-test-summary" }, [
                        m("span", { class: "cg2-test-result-pass" }, testState.results.pass + " pass"),
                        m("span", { class: "cg2-test-result-fail" }, testState.results.fail + " fail"),
                        m("span", { class: "cg2-test-result-warn" }, testState.results.warn + " warn")
                    ]) : null,

                    // Debug console log
                    m("div", { class: "cg2-test-console" }, (function() {
                        let items = [];
                        let lastCat = null;
                        for (let entry of testState.logs) {
                            // Add section header when category changes
                            if (entry.category && entry.category !== lastCat) {
                                let catLabel = TEST_CATEGORIES[entry.category]?.label || entry.category;
                                items.push(m("div", { class: "cg2-test-section-header" }, catLabel));
                                lastCat = entry.category;
                            }
                            items.push(m("div", { class: "cg2-test-log-entry", "data-status": entry.status }, [
                                m("span", { class: "cg2-test-log-time" }, entry.time),
                                entry.category ? m("span", { class: "cg2-test-log-cat", "data-cat": entry.category }, entry.category) : null,
                                m("span", {
                                    class: "material-symbols-outlined",
                                    style: { fontSize: "13px", color: statusColors[entry.status] || "#666", verticalAlign: "middle", marginRight: "4px" }
                                }, statusIcons[entry.status] || "info"),
                                m("span", { style: { color: statusColors[entry.status] || "#333" } }, entry.message)
                            ]));
                        }
                        if (testState.logs.length === 0) {
                            items.push(m("div", { class: "cg2-test-empty" }, testDeck
                                ? "Click 'Run All Tests' to test deck: " + (testDeckName || "selected")
                                : "Select a deck first, or click 'Run All Tests' for generic tests."));
                        }
                        return items;
                    })())
                ]);
            }
        };
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

    console.log("[CardGame] test/testMode loaded");
}());
