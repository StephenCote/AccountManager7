// CardGameApp.js — Main orchestrator for Card Game v2
// Assembles modules, manages shared state, routes screens
(function() {
    "use strict";

    const NS = window.CardGame = window.CardGame || {};

    // ── Shared Application Context ────────────────────────────────────
    // All modules access shared state through NS.ctx
    let ctx = NS.ctx = {
        // Screen routing
        screen: "deckList",    // "deckList" | "builder" | "deckView" | "game" | "test" | "themeEditor"
        builderStep: 1,        // 1=theme, 2=character, 3=review/build
        fullMode: false,

        // Deck state
        viewingDeck: null,
        savedDecks: [],
        decksLoading: false,

        // Builder state
        availableCharacters: [],
        charsLoading: false,
        selectedChars: [],
        builtDeck: null,
        buildingDeck: false,
        deckNameInput: "",

        // Game state (gameState, gameCharSelection, activeCampaign
        // are proxied to GameState.state below — do NOT define them here)

        // Theme state (proxied to Themes.state below — do NOT define here)

        // Test state
        testDeck: null,
        testDeckName: null
    };

    // ── State Proxies ────────────────────────────────────────────────
    // Wire ctx properties to module state so UI components can read/write
    // ctx.* and values are stored/retrieved from the correct module.
    function proxyState(target, moduleFn, props) {
        let defs = {};
        for (let p of props) {
            defs[p] = {
                get() { let s = moduleFn(); return s ? s[p] : undefined; },
                set(v) { let s = moduleFn(); if (s) s[p] = v; },
                enumerable: true,
                configurable: true
            };
        }
        Object.defineProperties(target, defs);
    }

    // ArtPipeline state → ctx
    proxyState(ctx, function() { return NS.ArtPipeline ? NS.ArtPipeline.state : null; }, [
        "artQueue", "artProcessing", "artCompleted", "artTotal", "artPaused", "artDir",
        "backgroundImageId", "backgroundThumbUrl", "backgroundPrompt", "backgroundGenerating",
        "tabletopImageId", "tabletopThumbUrl", "tabletopGenerating",
        "cardFrontImageUrl", "cardBackImageUrl", "cardFrontGenerating", "cardBackGenerating",
        "sequenceCardId", "sequenceProgress",
        "sdOverrides", "sdConfigExpanded", "sdConfigTab", "gameConfigExpanded",
        "voiceProfiles", "voiceProfilesLoaded",
        "flippedCards", "sdOverrideInsts", "sdOverrideViews"
    ]);

    // Themes state → ctx
    proxyState(ctx, function() { return NS.Themes ? NS.Themes.state : null; }, [
        "activeTheme", "themeLoading", "applyingOutfits"
    ]);

    // GameState state → ctx
    // Critical: ctx.gameState must proxy to GS().state.gameState so that when
    // gameView sets ctx.gameState, phaseUI/gameOverUI reading GS().state.gameState
    // see the same value.
    proxyState(ctx, function() { return NS.GameState ? NS.GameState.state : null; }, [
        "gameState", "gameCharSelection", "activeCampaign",
        "gameChatManager", "gameVoice", "gameAnnouncerVoice",
        "initAnimState", "llmStatus", "gameDirector", "gameNarrator",
        "resolutionAnimating", "resolutionPhase", "resolutionDiceFaces", "currentCombatResult"
    ]);

    // activeTheme is now proxied from Themes.state (initialized to DEFAULT_THEME in themes.js)

    // ── Helper: Toggle Full Mode ──────────────────────────────────────
    function toggleFullMode() {
        ctx.fullMode = !ctx.fullMode;
        m.redraw();
    }

    // ── Deck Operations (delegate to deckList module) ──────────────────
    function viewDeck(storageName) {
        if (NS.UI && NS.UI.viewDeck) return NS.UI.viewDeck(storageName);
    }
    function playDeck(storageName) {
        if (NS.UI && NS.UI.playDeck) return NS.UI.playDeck(storageName);
    }
    function resumeGame(storageName) {
        if (NS.UI && NS.UI.resumeGame) return NS.UI.resumeGame(storageName);
    }

    // ── Load Saved Decks (delegates to deckList module) ────────────────
    function loadSavedDecks() {
        if (NS.UI && NS.UI.loadSavedDecks) {
            return NS.UI.loadSavedDecks();
        }
    }

    // ── Theme List Loading ────────────────────────────────────────────
    async function loadThemeList() {
        if (NS.Themes && NS.Themes.loadThemeList) {
            await NS.Themes.loadThemeList();
        }
    }

    // ── Expose context functions ──────────────────────────────────────
    ctx.viewDeck = viewDeck;
    ctx.playDeck = playDeck;
    ctx.resumeGame = resumeGame;
    ctx.loadSavedDecks = loadSavedDecks;
    ctx.loadThemeList = loadThemeList;
    ctx.toggleFullMode = toggleFullMode;

    // ── Main Mithril Component ────────────────────────────────────────
    let cardGame = {
        oninit() {
            // Load theme on first mount
            if (!ctx.themeLoading && NS.Themes) {
                ctx.themeLoading = true;
                NS.Themes.loadThemeConfig("high-fantasy").then(theme => {
                    if (theme) ctx.activeTheme = theme;
                });
            }
            loadSavedDecks();
        },
        view() {
            if (!page.authenticated()) return m("");

            let isGameScreen = ctx.screen === "game";
            let DeckList = NS.UI.DeckList;
            let BuilderThemeStep = NS.UI.BuilderThemeStep;
            let BuilderCharacterStep = NS.UI.BuilderCharacterStep;
            let BuilderReviewStep = NS.UI.BuilderReviewStep;
            let DeckView = NS.UI.DeckView;
            let GameView = NS.UI.GameView;
            let TestModeUI = NS.TestMode ? NS.TestMode.TestModeUI : null;
            let ThemeEditorUI = NS.Themes ? NS.Themes.ThemeEditorUI : null;

            return m("div", { class: "content-outer" }, [
                ctx.fullMode ? "" : m(page.components.navigation),
                m("div", { class: "content-main" }, [
                    m("div", { class: "cg2-container" + (isGameScreen ? " cg2-game-mode" : "") }, [
                        // Header (hidden in game mode)
                        !isGameScreen ? m("div", { class: "cg2-toolbar" }, [
                            page.iconButton("button mr-4", ctx.fullMode ? "close_fullscreen" : "open_in_new", "", toggleFullMode),
                            m("span", { style: { fontWeight: 700, fontSize: "16px", marginRight: "16px" } }, "Card Game"),
                            ctx.screen === "deckList" ? [
                                m("button", { class: "cg2-btn cg2-btn-sm", onclick() { loadThemeList(); ctx.screen = "themeEditor"; m.redraw(); } }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "2px" } }, "palette"),
                                    " Themes"
                                ]),
                                m("button", { class: "cg2-btn cg2-btn-sm", style: { marginLeft: "4px" }, onclick() { ctx.screen = "test"; m.redraw(); } }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "2px" } }, "science"),
                                    " Test"
                                ])
                            ] : null,
                            m("span", { style: { marginLeft: "auto", fontSize: "11px", color: "#999" } },
                                "Theme: " + (ctx.activeTheme ? ctx.activeTheme.name : "Loading..."))
                        ]) : null,

                        // Screen router
                        ctx.screen === "deckList" && DeckList ? m(DeckList) : null,
                        ctx.screen === "builder" ? [
                            m("div", { class: "cg2-toolbar" }, [
                                [1, 2, 3].map(s =>
                                    m("span", {
                                        style: {
                                            padding: "3px 10px", borderRadius: "12px", fontSize: "12px", cursor: "pointer",
                                            background: ctx.builderStep === s ? "#B8860B" : "transparent",
                                            color: ctx.builderStep === s ? "#fff" : "#888",
                                            fontWeight: ctx.builderStep === s ? "700" : "400"
                                        },
                                        onclick: () => { if (s < ctx.builderStep) { ctx.builderStep = s; m.redraw(); } }
                                    }, s + ". " + ["Theme", "Character", "Outfit & Review"][s - 1])
                                )
                            ]),
                            ctx.builderStep === 1 && BuilderThemeStep ? m(BuilderThemeStep) : null,
                            ctx.builderStep === 2 && BuilderCharacterStep ? m(BuilderCharacterStep) : null,
                            ctx.builderStep === 3 && BuilderReviewStep ? m(BuilderReviewStep) : null
                        ] : null,
                        ctx.screen === "deckView" && DeckView ? m(DeckView) : null,
                        ctx.screen === "game" && GameView ? m(GameView) : null,
                        ctx.screen === "test" && TestModeUI ? m(TestModeUI) : null,
                        ctx.screen === "themeEditor" && ThemeEditorUI ? m(ThemeEditorUI) : null
                    ])
                ]),
                page.components.dialog.loadDialog(),
                page.loadToast()
            ]);
        }
    };

    // ── Register View ─────────────────────────────────────────────────
    page.views.cardGameV2 = cardGame;

    // ── Public API Export ──────────────────────────────────────────────
    // Backward compatibility: expose key functions/objects via page.cardGameV2
    page.cardGameV2 = {
        // Constants
        get CARD_TYPES() { return NS.Constants ? NS.Constants.CARD_TYPES : {}; },
        get GAME_PHASES() { return NS.Constants ? NS.Constants.GAME_PHASES : {}; },
        get COMBAT_OUTCOMES() { return NS.Constants ? NS.Constants.COMBAT_OUTCOMES : {}; },
        get STATUS_EFFECTS() { return NS.Constants ? NS.Constants.STATUS_EFFECTS : {}; },
        get SKILL_ACTION_KEYWORDS() { return NS.Constants ? NS.Constants.SKILL_ACTION_KEYWORDS : {}; },

        // Rendering
        get CardFace() { return NS.Rendering ? NS.Rendering.CardFace : null; },
        get CardBack() { return NS.Rendering ? NS.Rendering.CardBack : null; },
        get CardFlipContainer() { return NS.Rendering ? NS.Rendering.CardFlipContainer : null; },
        get NeedBar() { return NS.Rendering ? NS.Rendering.NeedBar : null; },
        get StatBlock() { return NS.Rendering ? NS.Rendering.StatBlock : null; },

        // Storage
        get deckStorage() { return NS.Storage ? NS.Storage.deckStorage : null; },
        get gameStorage() { return NS.Storage ? NS.Storage.gameStorage : null; },
        get campaignStorage() { return NS.Storage ? NS.Storage.campaignStorage : null; },
        get themeStorage() { return NS.Storage ? NS.Storage.themeStorage : null; },

        // Theme
        loadThemeConfig: function(id) { return NS.Themes ? NS.Themes.loadThemeConfig(id) : Promise.resolve(null); },
        activeTheme: function() { return ctx.activeTheme; },

        // Characters
        get assembleStarterDeck() { return NS.Characters ? NS.Characters.assembleStarterDeck : null; },
        get mapStats() { return NS.Characters ? NS.Characters.mapStats : null; },
        get getPortraitUrl() { return NS.Characters ? NS.Characters.getPortraitUrl : null; },

        // Art Pipeline
        get queueDeckArt() { return NS.ArtPipeline ? NS.ArtPipeline.queueDeckArt : null; },
        get generateCardArt() { return NS.ArtPipeline ? NS.ArtPipeline.generateCardArt : null; },
        get buildCardPrompt() { return NS.ArtPipeline ? NS.ArtPipeline.buildCardPrompt : null; },
        artQueue: function() { return NS.ArtPipeline ? NS.ArtPipeline.artQueue : []; },

        // Game Engine
        get createGameState() { return NS.GameState ? NS.GameState.createGameState : null; },
        gameState: function() { return ctx.gameState; },
        get rollD20() { return NS.Engine ? NS.Engine.Combat.rollD20 : null; },
        get rollInitiative() { return NS.Engine ? NS.Engine.Combat.rollInitiative : null; },
        get advancePhase() { return NS.GameState ? NS.GameState.state.advancePhase : null; },
        get placeCard() { return NS.GameState ? NS.GameState.state.placeCard : null; },
        get advanceResolution() { return NS.Engine ? NS.Engine.Actions.advanceResolution : null; },

        // Combat
        get rollAttack() { return NS.Engine ? NS.Engine.Combat.rollAttack : null; },
        get rollDefense() { return NS.Engine ? NS.Engine.Combat.rollDefense : null; },
        get getCombatOutcome() { return NS.Engine ? NS.Engine.Combat.getCombatOutcome : null; },
        get calculateDamage() { return NS.Engine ? NS.Engine.Combat.calculateDamage : null; },
        get applyDamage() { return NS.Engine ? NS.Engine.Combat.applyDamage : null; },
        get resolveCombat() { return NS.Engine ? NS.Engine.Combat.resolveCombat : null; },
        get checkGameOver() { return NS.GameState ? NS.GameState.state.checkGameOver : null; },
        currentCombatResult: function() { return NS.GameState ? NS.GameState.state.currentCombatResult : null; },

        // Effects
        get parseEffect() { return NS.Engine ? NS.Engine.Effects.parseEffect : null; },
        get applyParsedEffects() { return NS.Engine ? NS.Engine.Effects.applyParsedEffects : null; },
        get isEffectParseable() { return NS.Engine ? NS.Engine.Effects.isEffectParseable : null; },

        // Test
        get TEST_CARDS() { return NS.TestMode ? NS.TestMode.TEST_CARDS : []; },
        testState: function() { return NS.TestMode ? NS.TestMode.testState : null; },
        get runTestSuite() { return NS.TestMode ? NS.TestMode.runTestSuite : null; }
    };

    console.log("[CardGame v2] CardGameApp orchestrator loaded");

}());
