/**
 * CardGameApp.js — Main orchestrator for Card Game v2 (ESM)
 * Port of Ux7 client/view/cardGame/CardGameApp.js
 *
 * Assembles modules, manages shared state, routes screens.
 * Replaces window.CardGame namespace with ESM imports.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

// Import all card game subsystems
import { gameConstants } from './constants/gameConstants.js';
import { storage } from './state/storage.js';
import { gameState, wireModules as wireGameStateModules } from './state/gameState.js';
import { cardComponents } from './rendering/cardComponents.js';
import { cardFace } from './rendering/cardFace.js';
import { overlays } from './rendering/overlays.js';
import { characters } from './services/characters.js';
import { themes } from './services/themes.js';
import { artPipeline } from './services/artPipeline.js';
import * as effects from './engine/effects.js';
import * as combat from './engine/combat.js';
import * as encounters from './engine/encounters.js';
import * as actions from './engine/actions.js';
import * as llmBase from './ai/llmBase.js';
import * as director from './ai/director.js';
import * as narrator from './ai/narrator.js';
import * as chatManager from './ai/chatManager.js';
import * as voice from './ai/voice.js';
import { deckListUI } from './ui/deckList.js';
import { builder } from './ui/builder.js';
import { deckView } from './ui/deckView.js';
import { gameView } from './ui/gameView.js';
import { phaseUI } from './ui/phaseUI.js';
import { threatUI } from './ui/threatUI.js';
import { chatUI } from './ui/chatUI.js';
import { gameOverUI } from './ui/gameOverUI.js';
import { designerCanvas } from './designer/designerCanvas.js';
import { exportDialog } from './designer/exportDialog.js';
import { exportPipeline } from './designer/exportPipeline.js';
import { iconPicker } from './designer/iconPicker.js';
import { layoutConfig } from './designer/layoutConfig.js';
import { layoutRenderer } from './designer/layoutRenderer.js';
import { testMode, TestModeUI, runTestSuite, TEST_CARDS, testState, init as initTestMode } from './test/testMode.js';

function getPage() { return am7model._page; }

// ── Flat Engine namespace (mirrors Ux7 CardGame.Engine) ─────────
const flatEngine = { ...effects, ...combat, ...encounters, ...actions };

// ── Shared Application Context ──────────────────────────────────
// All modules access shared state through NS (namespace)
const NS = {
    Constants: gameConstants,
    Storage: storage,
    GameState: gameState,
    Rendering: { CardFace: cardFace, CardBack: cardFace.CardBack, CardFlipContainer: cardFace.CardFlipContainer, NeedBar: cardComponents.NeedBar, StatBlock: cardComponents.StatBlock, ...cardComponents },
    Overlays: overlays,
    Characters: characters,
    Themes: themes,
    ArtPipeline: artPipeline,
    Engine: flatEngine,
    AI: { LlmBase: llmBase, Director: director, Narrator: narrator, ChatManager: chatManager, Voice: voice },
    UI: { DeckList: deckListUI, Builder: builder, DeckView: deckView, GameView: gameView, PhaseUI: phaseUI, ThreatUI: threatUI, ChatUI: chatUI, GameOverUI: gameOverUI },
    Designer: { DesignerCanvas: designerCanvas, ExportDialog: exportDialog, ExportPipeline: exportPipeline, IconPicker: iconPicker, LayoutConfig: layoutConfig, LayoutRenderer: layoutRenderer },
    TestMode: testMode
};

let ctx = {
    screen: "deckList",
    builderStep: 1,
    fullMode: false,
    viewingDeck: null,
    savedDecks: [],
    decksLoading: false,
    availableCharacters: [],
    charsLoading: false,
    selectedChars: [],
    builtDeck: null,
    buildingDeck: false,
    deckNameInput: "",
    testDeck: null,
    testDeckName: null,
    designerDeck: null
};

// ── State Proxies ────────────────────────────────────────────────
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
if (artPipeline.state) {
    proxyState(ctx, () => artPipeline.state, [
        "artQueue", "artProcessing", "artCompleted", "artTotal", "artPaused", "artDir",
        "backgroundImageId", "backgroundThumbUrl", "backgroundPrompt", "backgroundGenerating",
        "tabletopImageId", "tabletopThumbUrl", "tabletopGenerating",
        "cardFrontImageUrl", "cardBackImageUrl", "cardFrontGenerating", "cardBackGenerating",
        "sequenceCardId", "sequenceProgress",
        "sdOverrides", "sdConfigExpanded", "sdConfigTab", "gameConfigExpanded",
        "voiceProfiles", "voiceProfilesLoaded",
        "flippedCards", "sdOverrideInsts", "sdOverrideViews"
    ]);
}

// Themes state → ctx
if (themes.state) {
    proxyState(ctx, () => themes.state, [
        "activeTheme", "themeLoading", "applyingOutfits"
    ]);
}

// GameState state → ctx
if (gameState.state) {
    proxyState(ctx, () => gameState.state, [
        "gameState", "gameCharSelection", "activeCampaign",
        "gameChatManager", "gameVoice", "gameAnnouncerVoice",
        "initAnimState", "llmStatus", "gameDirector", "gameNarrator",
        "resolutionAnimating", "resolutionPhase", "resolutionDiceFaces", "currentCombatResult"
    ]);
}

// ── Helper functions ─────────────────────────────────────────────
function toggleFullMode() { ctx.fullMode = !ctx.fullMode; m.redraw(); }

function viewDeck(storageName) {
    if (deckListUI.viewDeck) return deckListUI.viewDeck(storageName);
}
function playDeck(storageName) {
    if (deckListUI.playDeck) return deckListUI.playDeck(storageName);
}
function resumeGame(storageName) {
    if (deckListUI.resumeGame) return deckListUI.resumeGame(storageName);
}
function loadSavedDecks() {
    if (deckListUI.loadSavedDecks) return deckListUI.loadSavedDecks();
}

async function loadThemeList() {
    if (themes.loadThemeList) await themes.loadThemeList();
}

ctx.viewDeck = viewDeck;
ctx.playDeck = playDeck;
ctx.resumeGame = resumeGame;
ctx.loadSavedDecks = loadSavedDecks;
ctx.loadThemeList = loadThemeList;
ctx.toggleFullMode = toggleFullMode;

// Make ctx available to all modules
NS.ctx = ctx;

// Wire service module dependencies (replaces Ux7 window.CardGame.* namespace access)
if (characters.wireContext) characters.wireContext(ctx, gameConstants, flatEngine);
if (themes.wireContext) themes.wireContext(gameConstants, storage, characters, flatEngine);
if (artPipeline.wireContext) artPipeline.wireContext(ctx, gameConstants, storage, characters, flatEngine, NS.Rendering);
wireGameStateModules({ engine: flatEngine, characters, ai: { ...NS.AI, openTalkChat: chatUI.openTalkChat }, themes });

// Initialize UI modules with the shared namespace
if (deckListUI.init) deckListUI.init(NS);
if (builder.init) builder.init(NS);
if (deckView.init) deckView.init(NS);
if (gameView.init) gameView.init(NS);
if (phaseUI.init) phaseUI.init(NS);
if (threatUI.init) threatUI.init(NS);
if (chatUI.init) chatUI.init(NS);
if (gameOverUI.init) gameOverUI.init(NS);
initTestMode(NS);

// Wire late-bound references for encounters module
if (encounters.wireEncounterRefs) {
    encounters.wireEncounterRefs(ctx, themes);
}

// Wire late-bound references for rendering modules (circular dep resolution)
if (overlays._setCardFaceRef) overlays._setCardFaceRef(cardFace);
if (overlays._setCtxFn) overlays._setCtxFn(() => ctx);
if (overlays._setArtPipelineRef) overlays._setArtPipelineRef(artPipeline);
if (overlays._setCharactersRef) overlays._setCharactersRef(characters);
if (overlays._setDeckStorageFn) overlays._setDeckStorageFn(() => storage);
if (cardFace._setDesignerRef) cardFace._setDesignerRef(designerCanvas);
if (cardFace._setCtxFn) cardFace._setCtxFn(() => ctx);

// Expose ctx and helpers for E2E testing
if (typeof window !== 'undefined') {
    window.__cardGameCtx = ctx;
    window.__cardGameNS = NS;
    window.__cardGameSetScreen = function(screen) {
        ctx.screen = screen;
        m.redraw();
    };
}

// ── Main Mithril Component ──────────────────────────────────────
const cardGameComponent = {
    oninit() {
        if (!ctx.themeLoading && themes.loadThemeConfig) {
            ctx.themeLoading = true;
            themes.loadThemeConfig("high-fantasy").then(theme => {
                if (theme) ctx.activeTheme = theme;
            });
        }
        loadSavedDecks();
    },
    view() {
        let page = getPage();
        if (!page.authenticated()) return m("");

        let isGameScreen = ctx.screen === "game";

        return m("div", { class: "flex flex-col h-full" }, [
            ctx.fullMode ? null : (page.components.navigation ? m(page.components.navigation) : null),
            m("div", { class: "flex-1 overflow-auto" }, [
                m("div", { class: "h-full" + (isGameScreen ? " flex flex-col" : " p-4") }, [
                    // Header (hidden in game mode)
                    !isGameScreen ? m("div", { class: "flex items-center gap-2 mb-4 px-2" }, [
                        page.iconButton("p-2 rounded hover:bg-gray-200 dark:hover:bg-gray-700", ctx.fullMode ? "close_fullscreen" : "open_in_new", "", toggleFullMode),
                        m("span", { class: "font-bold text-base mr-4 dark:text-white" }, "Card Game"),
                        ctx.screen === "deckList" ? [
                            m("button", { class: "px-3 py-1 rounded bg-gray-200 hover:bg-gray-300 dark:bg-gray-700 dark:hover:bg-gray-600 text-sm flex items-center gap-1", onclick() { loadThemeList(); ctx.screen = "themeEditor"; m.redraw(); } }, [
                                m("span", { class: "material-symbols-outlined text-sm" }, "palette"), " Themes"
                            ]),
                            m("button", { class: "px-3 py-1 rounded bg-gray-200 hover:bg-gray-300 dark:bg-gray-700 dark:hover:bg-gray-600 text-sm flex items-center gap-1 ml-1", onclick() { ctx.screen = "test"; m.redraw(); } }, [
                                m("span", { class: "material-symbols-outlined text-sm" }, "science"), " Test"
                            ])
                        ] : null,
                        m("span", { class: "ml-auto text-xs text-gray-500" },
                            "Theme: " + (ctx.activeTheme ? ctx.activeTheme.name : "Loading..."))
                    ]) : null,

                    // Screen router
                    ctx.screen === "deckList" && deckListUI.DeckList ? m(deckListUI.DeckList, { ctx, NS }) : null,
                    ctx.screen === "builder" ? [
                        m("div", { class: "flex items-center gap-2 mb-3" },
                            [1, 2, 3].map(s =>
                                m("span", {
                                    class: "px-3 py-1 rounded-full text-xs cursor-pointer " +
                                        (ctx.builderStep === s ? "bg-yellow-700 text-white font-bold" : "text-gray-500"),
                                    onclick: () => { if (s < ctx.builderStep) { ctx.builderStep = s; m.redraw(); } }
                                }, s + ". " + ["Theme", "Character", "Outfit & Review"][s - 1])
                            )
                        ),
                        ctx.builderStep === 1 && builder.BuilderThemeStep ? m(builder.BuilderThemeStep, { ctx, NS }) : null,
                        ctx.builderStep === 2 && builder.BuilderCharacterStep ? m(builder.BuilderCharacterStep, { ctx, NS }) : null,
                        ctx.builderStep === 3 && builder.BuilderReviewStep ? m(builder.BuilderReviewStep, { ctx, NS }) : null
                    ] : null,
                    ctx.screen === "deckView" && deckView.DeckView ? m(deckView.DeckView, { ctx, NS }) : null,
                    ctx.screen === "game" && gameView.GameView ? m(gameView.GameView, { ctx, NS }) : null,
                    ctx.screen === "test" ? m(TestModeUI) : null,
                    ctx.screen === "themeEditor" && themes.ThemeEditorUI ? m(themes.ThemeEditorUI, { ctx, NS }) : null,
                    ctx.screen === "designer" && designerCanvas.DesignerView ? m(designerCanvas.DesignerView, { ctx, NS }) : null
                ])
            ]),
            page.components.dialog ? page.components.dialog.loadDialogs() : null,
            page.loadToast()
        ]);
    }
};

// ── Export ────────────────────────────────────────────────────────

const cardGameApp = {
    component: cardGameComponent,
    ctx,
    NS,
    // Public API
    get CARD_TYPES() { return gameConstants.CARD_TYPES || {}; },
    get GAME_PHASES() { return gameConstants.GAME_PHASES || {}; },
    get COMBAT_OUTCOMES() { return gameConstants.COMBAT_OUTCOMES || {}; },
    get CardFace() { return cardFace; },
    get deckStorage() { return storage.deckStorage; },
    get gameStorage() { return storage.gameStorage; },
    loadThemeConfig: (id) => themes.loadThemeConfig ? themes.loadThemeConfig(id) : Promise.resolve(null),
    activeTheme: () => ctx.activeTheme,
    get assembleStarterDeck() { return characters.assembleStarterDeck; },
    get createGameState() { return gameState.createGameState; },
    gameState: () => ctx.gameState,
    get rollD20() { return combat.rollD20; },
    get rollInitiative() { return combat.rollInitiative; },
    get rollAttack() { return combat.rollAttack; },
    get rollDefense() { return combat.rollDefense; },
    get getCombatOutcome() { return combat.getCombatOutcome; },
    get calculateDamage() { return combat.calculateDamage; },
    get applyDamage() { return combat.applyDamage; },
    get resolveCombat() { return combat.resolveCombat; },
    get checkGameOver() { return combat.checkGameOver; },
    get currentCombatResult() { return gameState.state?.currentCombatResult; },
    get parseEffect() { return effects.parseEffect; },
    get applyParsedEffects() { return effects.applyParsedEffects; },
    get isEffectParseable() { return effects.isEffectParseable; },
    get advancePhase() { return gameState.advancePhase; },
    get placeCard() { return actions.placeCard; },
    get advanceResolution() { return gameState.advanceResolution; },
    // Rendering
    get CardBack() { return cardFace.CardBack; },
    get CardFlipContainer() { return cardFace.CardFlipContainer; },
    get NeedBar() { return cardComponents.NeedBar; },
    get StatBlock() { return cardComponents.StatBlock; },
    // Storage
    get campaignStorage() { return storage.campaignStorage; },
    get themeStorage() { return storage.themeStorage; },
    // Characters
    get mapStats() { return characters.mapStats; },
    get getPortraitUrl() { return characters.getPortraitUrl; },
    // Art pipeline
    get queueDeckArt() { return artPipeline.queueDeckArt; },
    get generateCardArt() { return artPipeline.generateCardArt; },
    get buildCardPrompt() { return artPipeline.buildCardPrompt; },
    get artQueue() { return artPipeline.state?.artQueue; },
    // Test
    TEST_CARDS,
    testState,
    runTestSuite
};

export { cardGameApp, cardGameComponent, ctx, NS };
export default cardGameApp;
