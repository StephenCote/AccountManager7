/**
 * CardGame UI - Game View Components
 * Main game container, character selection, sidebar, action bar, and hand tray.
 * Extracted from the monolithic cardGame-v2.js (lines ~7898-9903).
 *
 * Dependencies:
 *   - CardGame.Constants (GAME_PHASES)
 *   - CardGame.Storage (campaignStorage, gameStorage)
 *   - CardGame.Rendering (CardFace, CardBack, CardPreviewOverlay)
 *   - CardGame.Engine (drawCardsForActor)
 *   - CardGame.UI (InitiativePhaseUI, CleanupPhaseUI, ResolutionPhaseUI, etc.)
 *   - CardGame.ctx (shared mutable context: gameState, viewingDeck, screen, etc.)
 *
 * Exposes: window.CardGame.UI.{CharacterSelectUI, GameView, CharacterSidebar,
 *   FannedCardStack, ActionBar, HandTray, formatPhase, calcCharScore,
 *   gameViewState}
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    const GAME_PHASES = window.CardGame.Constants.GAME_PHASES;
    const STATUS_EFFECTS = window.CardGame.Constants.STATUS_EFFECTS;
    const ACTION_DEFINITIONS = window.CardGame.Constants.ACTION_DEFINITIONS;

    // ── State ──────────────────────────────────────────────────────────
    let handTrayFilter = "all";  // "all" | "skill" | "magic" | "item"
    let stackFlipped = {};  // { "player-0": true, "opponent-1": false }
    let selectedHandCard = null;  // Card selected for tap-to-place (touch/tablet support)

    // ── Helpers ─────────────────────────────────────────────────────────
    function ctx() { return window.CardGame.ctx || {}; }
    function storage() { return window.CardGame.Storage || {}; }
    function rendering() { return window.CardGame.Rendering || {}; }
    function GS() { return window.CardGame.GameState || {}; }
    function E() { return window.CardGame.Engine || {}; }
    function AI() { return window.CardGame.AI || {}; }

    function formatPhase(phase) {
        let labels = {
            [GAME_PHASES.INITIATIVE]: "Initiative",
            [GAME_PHASES.EQUIP]: "Equip",
            [GAME_PHASES.THREAT_RESPONSE]: "Threat!",
            [GAME_PHASES.DRAW_PLACEMENT]: "Placement",
            [GAME_PHASES.RESOLUTION]: "Resolution",
            [GAME_PHASES.CLEANUP]: "Cleanup",
            [GAME_PHASES.END_THREAT]: "End Threat!",
            "GAME_OVER": "Game Over"
        };
        return labels[phase] || phase;
    }

    // ── Character Selection UI ──────────────────────────────────────────
    // Calculate total stat score for a character
    function calcCharScore(char) {
        let stats = char.stats || {};
        return (stats.STR || 0) + (stats.AGI || 0) + (stats.END || 0) +
               (stats.INT || 0) + (stats.MAG || 0) + (stats.CHA || 0);
    }

    function CharacterSelectUI() {
        return {
            view() {
                let gameCharSelection = ctx().gameCharSelection;
                if (!gameCharSelection) return null;
                let chars = gameCharSelection.characters || [];
                let viewingDeck = ctx().viewingDeck;
                let gameState = ctx().gameState;
                let llmStatus = GS().state?.llmStatus || { checked: false, available: false };

                // Calculate best and worst characters by total stats
                let bestChar = null, worstChar = null;
                let bestScore = -Infinity, worstScore = Infinity;
                if (chars.length > 1) {
                    chars.forEach(c => {
                        let score = calcCharScore(c);
                        if (score > bestScore) { bestScore = score; bestChar = c; }
                        if (score < worstScore) { worstScore = score; worstChar = c; }
                    });
                    // Don't mark if all same score
                    if (bestScore === worstScore) { bestChar = null; worstChar = null; }
                }

                // Get card front background from deck if available
                let cardFrontBg = viewingDeck && viewingDeck.cardFrontImageUrl
                    ? viewingDeck.cardFrontImageUrl : null;

                // Get tabletop background from deck if available
                let tabletopBg = viewingDeck && viewingDeck.tabletopThumbUrl
                    ? viewingDeck.tabletopThumbUrl.replace(/\/\d+x\d+$/, "/1024x1024")
                    : null;

                let containerStyle = tabletopBg ? {
                    backgroundImage: "url('" + tabletopBg + "')",
                    backgroundSize: "cover",
                    backgroundPosition: "center"
                } : {};

                let CardFace = rendering().CardFace;
                let createGameState = GS().createGameState;
                let campaignStorage = storage().campaignStorage;
                let initializeLLMComponents = GS().initializeLLMComponents;

                return m("div", { class: "cg2-game-container", style: containerStyle }, [
                    m("div", { class: "cg2-game-header" }, [
                        m("button", {
                            class: "cg2-btn",
                            onclick() {
                                ctx().gameCharSelection = null;
                                ctx().screen = "deckView";
                                m.redraw();
                            }
                        }, "\u2190 Back"),
                        m("span", { class: "cg2-game-title" }, "Select Your Character")
                    ]),

                    m("div", { class: "cg2-char-select-container" }, [
                        m("div", { class: "cg2-phase-panel" }, [
                            m("h2", "Choose Your Character"),
                            m("p", "Select which character you want to play (" + chars.length + " available). The other characters will be your opponents."),

                            // LLM Status Indicator
                            m("div", { class: "cg2-llm-status" }, [
                                !llmStatus.checked
                                    ? m("span", { class: "cg2-llm-checking" }, [
                                        m("span", { class: "material-symbols-outlined cg2-spin" }, "sync"),
                                        " Checking LLM connectivity..."
                                    ])
                                    : llmStatus.available
                                        ? m("span", { class: "cg2-llm-online" }, [
                                            m("span", { class: "material-symbols-outlined" }, "check_circle"),
                                            " LLM Online"
                                        ])
                                        : m("span", { class: "cg2-llm-offline" }, [
                                            m("span", { class: "material-symbols-outlined" }, "error"),
                                            " LLM Offline - AI opponent will use basic strategy"
                                        ])
                            ]),

                            m("p", { style: { marginBottom: "16px" } }, "Click a character to start"),

                            m("div", { class: "cg2-char-select-grid" },
                                chars.map(char => {
                                    let isBest = char === bestChar;
                                    let isWorst = char === worstChar;
                                    return m("div", {
                                        class: "cg2-char-select-wrapper",
                                        async onclick() {
                                            // Click to select and immediately start game
                                            gameCharSelection.selected = char;
                                            ctx().gameState = await createGameState(viewingDeck, char);
                                            ctx().gameCharSelection = null;
                                            if (ctx().gameState) {
                                                // Load campaign record
                                                ctx().activeCampaign = await campaignStorage.load(viewingDeck.deckName || viewingDeck.storageName);
                                                // Initialize LLM components in background (non-blocking)
                                                if (typeof initializeLLMComponents === "function") {
                                                    initializeLLMComponents(ctx().gameState, viewingDeck);
                                                }
                                                // Animation will trigger runInitiativePhase() when complete
                                            }
                                            m.redraw();
                                        }
                                    }, [
                                        // Use CardFace for consistent card styling (card front bg, not portrait)
                                        // noPreview: true prevents image click from opening preview instead of selecting
                                        m(CardFace, { card: char, bgImage: cardFrontBg, noPreview: true }),
                                        // Best character indicator (gold check)
                                        isBest ? m("div", { class: "cg2-char-badge cg2-char-best", title: "Highest total stats" }, [
                                            m("span", { class: "material-symbols-outlined" }, "check_circle")
                                        ]) : null,
                                        // Worst character indicator (red X)
                                        isWorst ? m("div", { class: "cg2-char-badge cg2-char-worst", title: "Lowest total stats" }, [
                                            m("span", { class: "material-symbols-outlined" }, "cancel")
                                        ]) : null
                                    ]);
                                })
                            )
                        ])
                    ])
                ]);
            }
        };
    }

    // ── Game View Component ─────────────────────────────────────────────
    function GameView() {
        return {
            oninit() {
                let gameState = ctx().gameState;
                let viewingDeck = ctx().viewingDeck;
                let gameCharSelection = ctx().gameCharSelection;
                let llmStatus = GS().state?.llmStatus || {};

                // Check LLM connectivity at game start
                if (!llmStatus.checked && typeof GS().checkLlmConnectivity === "function") {
                    GS().checkLlmConnectivity();
                }

                if (!gameState && viewingDeck) {
                    // Always let player pick from all characters
                    let allChars = (viewingDeck.cards || []).filter(c => c.type === "character");
                    console.log("[CardGame v2] Game init - found " + allChars.length + " character cards:", allChars.map(c => c.name));

                    if (allChars.length > 0 && !gameCharSelection) {
                        // Show character selection for ALL characters
                        ctx().gameCharSelection = { characters: allChars, selected: null };
                    } else if (gameCharSelection?.selected) {
                        // Character selected - start game (async for JSON loading)
                        GS().createGameState(viewingDeck, gameCharSelection.selected).then(gs => {
                            ctx().gameState = gs;
                            ctx().gameCharSelection = null;
                            if (gs) {
                                storage().campaignStorage.load(viewingDeck.deckName).then(c => {
                                    ctx().activeCampaign = c;
                                    m.redraw();
                                });
                                if (typeof GS().initializeLLMComponents === "function") {
                                    GS().initializeLLMComponents(gs, viewingDeck);
                                }
                            }
                            m.redraw();
                        });
                    } else if (allChars.length === 0) {
                        console.error("[CardGame v2] No characters in deck");
                    }
                }
            },
            view() {
                let gameState = ctx().gameState;
                let gameCharSelection = ctx().gameCharSelection;
                let viewingDeck = ctx().viewingDeck;
                let initAnimState = GS().state?.initAnimState;

                // Character selection screen
                if (gameCharSelection && !gameState) {
                    return m(CharacterSelectUI);
                }

                if (!gameState) {
                    return m("div", { class: "cg2-game-error" }, [
                        m("h2", "Game Error"),
                        m("p", "No game state available. Please load a deck first."),
                        m("button", {
                            class: "cg2-btn",
                            onclick() { ctx().screen = "deckList"; m.redraw(); }
                        }, "Back to Decks")
                    ]);
                }

                // Game Over is now rendered as an overlay, not a separate screen

                let isPlacement = gameState.phase === GAME_PHASES.DRAW_PLACEMENT;
                let isPlayerTurn = gameState.currentTurn === "player";
                let player = gameState.player;

                // Get tabletop background from deck if available
                let tabletopBg = viewingDeck && viewingDeck.tabletopThumbUrl
                    ? viewingDeck.tabletopThumbUrl.replace(/\/\d+x\d+$/, "/1024x1024")
                    : null;

                let containerStyle = tabletopBg ? {
                    backgroundImage: "url('" + tabletopBg + "')",
                    backgroundSize: "cover",
                    backgroundPosition: "center"
                } : {};

                // Resolve UI components from namespace
                let InitiativePhaseUI = CardGame.UI.InitiativePhaseUI;
                let ThreatResponseUI = CardGame.UI.ThreatResponseUI;
                let CleanupPhaseUI = CardGame.UI.CleanupPhaseUI;
                let TalkChatUI = CardGame.UI.TalkChatUI;
                let GameOverUI = CardGame.UI.GameOverUI;

                // Resolve engine functions from modules
                let advancePhase = GS().advancePhase;
                let drawCardsForActor = E().drawCardsForActor;
                let checkPlacementComplete = AI().checkPlacementComplete;
                let endTurn = GS().endTurn;

                return m("div", { class: "cg2-game-container", style: containerStyle }, [
                    // Header with inline placement controls
                    m("div", { class: "cg2-game-header" }, [
                        m("button", {
                            class: "cg2-btn cg2-btn-sm",
                            onclick() {
                                page.components.dialog.confirm("Exit game? Progress will be lost.", function(ok) {
                                    if (ok) {
                                        ctx().gameState = null;
                                        // Restore deck view image state from viewingDeck
                                        if (viewingDeck) {
                                            ctx().backgroundImageId = viewingDeck.backgroundImageId || null;
                                            ctx().backgroundPrompt = viewingDeck.backgroundPrompt || null;
                                            ctx().backgroundThumbUrl = viewingDeck.backgroundThumbUrl || null;
                                            ctx().tabletopImageId = viewingDeck.tabletopImageId || null;
                                            ctx().tabletopThumbUrl = viewingDeck.tabletopThumbUrl || null;
                                        }
                                        ctx().screen = "deckView";
                                        m.redraw();
                                    }
                                });
                            }
                        }, "\u2190 Exit"),
                        m("span", { class: "cg2-round-badge" }, "R" + gameState.round),

                        // Compact status in header
                        m("div", { class: "cg2-header-status" }, [
                            isPlacement ? m("span", { class: "cg2-header-ap" }, [
                                "AP: ", m("strong", (player.ap - player.apUsed) + "/" + player.ap)
                            ]) : null,
                            m("span", { class: "cg2-header-turn" + (isPlayerTurn && isPlacement ? " cg2-your-turn" : "") },
                                isPlacement ? (isPlayerTurn ? "Your turn" : "Opponent...") : ""
                            )
                        ]),

                        m("span", { class: "cg2-phase-badge" }, formatPhase(gameState.phase))
                    ]),

                    // Main game area
                    m("div", { class: "cg2-game-main" }, [
                        // Left sidebar: Player character
                        m("div", { class: "cg2-game-sidebar cg2-player-side" }, [
                            m(CharacterSidebar, { actor: gameState.player, label: "You" })
                        ]),

                        // Center: Phase UI + Action Bar
                        m("div", { class: "cg2-game-center" }, [
                            // Phase-specific content
                            m("div", { class: "cg2-phase-content" }, [
                                gameState.phase === GAME_PHASES.INITIATIVE && InitiativePhaseUI ? m(InitiativePhaseUI) : null,
                                // Threat response phases
                                (gameState.phase === GAME_PHASES.THREAT_RESPONSE || gameState.phase === GAME_PHASES.END_THREAT) && ThreatResponseUI
                                    ? m(ThreatResponseUI)
                                    : null,
                                // Resolution overlay is now inside ActionBar
                                gameState.phase === GAME_PHASES.CLEANUP && CleanupPhaseUI ? m(CleanupPhaseUI) : null,

                                // Resolution narration bar (above action bar so it's not floating in whitespace)
                                (gameState.narrationText && gameState.phase === GAME_PHASES.RESOLUTION)
                                    ? m("div", { class: "cg2-resolution-narration" }, [
                                        m("span", { class: "material-symbols-outlined cg2-narration-icon" }, "campaign"),
                                        m("span", { class: "cg2-resolution-narration-text" }, gameState.narrationText),
                                        m("button", {
                                            class: "cg2-narration-skip-btn",
                                            title: "Skip",
                                            onclick(e) { e.stopPropagation(); CardGame.GameState.skipNarration(); }
                                        }, m("span", { class: "material-symbols-outlined" }, "skip_next"))
                                    ])
                                    : null,

                                // Action Bar (visible in placement and resolution)
                                (isPlacement || gameState.phase === GAME_PHASES.RESOLUTION)
                                    ? m(ActionBar)
                                    : null,

                                // Talk card chat overlay (Phase 8)
                                gameState.chat?.active && TalkChatUI ? m(TalkChatUI) : null,

                                // Game Over overlay
                                gameState.winner && GameOverUI ? m(GameOverUI) : null
                            ]),

                            // Action Panel (hidden during resolution to avoid duplicate UI)
                            gameState.phase !== GAME_PHASES.RESOLUTION ? m("div", { class: "cg2-action-panel" }, [
                                // Status message - dynamic based on phase, narration, and LLM state
                                m("div", { class: "cg2-action-status" + (gameState.narrationText ? " cg2-status-has-narration" : "") },
                                    // Narration ticker takes priority when present
                                    gameState.narrationText
                                        ? m("div", { class: "cg2-status-narration-ticker" }, [
                                            m("span", { class: "material-symbols-outlined cg2-narration-ticker-icon" }, "campaign"),
                                            m("span", { class: "cg2-narration-ticker-text" }, gameState.narrationText)
                                        ])
                                        : // LLM thinking indicator
                                          gameState.llmBusy
                                            ? m("div", { class: "cg2-llm-thinking" }, [
                                                m("span", { class: "material-symbols-outlined cg2-spin" }, "sync"),
                                                m("span", { class: "cg2-llm-thinking-text" }, gameState.llmBusy)
                                            ])
                                            : // Normal phase-based status
                                              gameState.phase === GAME_PHASES.INITIATIVE
                                                ? (initAnimState && initAnimState.rollComplete && gameState.initiative.winner
                                                    ? [
                                                        m("strong", gameState.initiative.winner === "player" ? "You win initiative! " : "Opponent wins initiative! "),
                                                        m("span", gameState.initiative.winner === "player"
                                                            ? "You go first (odd positions: 1, 3, 5...)"
                                                            : "Opponent goes first. You get even positions (2, 4, 6...)")
                                                    ]
                                                    : "Rolling for initiative...")
                                                : (gameState.phase === GAME_PHASES.THREAT_RESPONSE || gameState.phase === GAME_PHASES.END_THREAT)
                                                    ? (gameState.threatResponse?.responder === "player"
                                                        ? "Threat incoming! Click cards to defend."
                                                        : "Opponent is responding to threat...")
                                                    : gameState.phase === GAME_PHASES.DRAW_PLACEMENT
                                                        ? (isPlayerTurn ? "Place cards on the action bar" : "Opponent is placing...")
                                                        : gameState.phase === GAME_PHASES.RESOLUTION
                                                            ? "" // Status shown in resolution overlay above
                                                            : gameState.phase === GAME_PHASES.CLEANUP
                                                                ? "Round complete!"
                                                                : ""
                                ),
                                // Primary action button(s)
                                m("div", { class: "cg2-action-buttons" }, [
                                    // Initiative phase: Continue button
                                    gameState.phase === GAME_PHASES.INITIATIVE && initAnimState && initAnimState.rollComplete
                                        ? m("button", {
                                            class: "cg2-btn cg2-btn-primary cg2-action-btn-lg",
                                            onclick() { advancePhase(); }
                                        }, "Continue")
                                        : null,
                                    // Placement phase: Draw and End Turn buttons
                                    isPlacement && isPlayerTurn ? [
                                        m("button", {
                                            class: "cg2-btn cg2-action-btn-lg",
                                            disabled: player.drawPile.length === 0 || player.apUsed >= player.ap,
                                            title: "Draw a card (costs 1 AP)",
                                            onclick() {
                                                if (player.apUsed < player.ap) {
                                                    drawCardsForActor(player, 1);
                                                    player.apUsed++;
                                                    m.redraw();
                                                }
                                            }
                                        }, [
                                            m("span", { class: "material-symbols-outlined" }, "add_card"),
                                            " Draw"
                                        ]),
                                        m("button", {
                                            class: "cg2-btn cg2-btn-primary cg2-action-btn-lg",
                                            onclick() {
                                                checkPlacementComplete();
                                                if (ctx().gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                                                    endTurn();
                                                }
                                            }
                                        }, player.apUsed > 0 ? "End Turn" : "Pass Turn")
                                    ] : null
                                ])
                            ]) : null,

                            // Hand Tray (inside center column for compact layout)
                            (gameState.phase === GAME_PHASES.INITIATIVE ||
                             gameState.phase === GAME_PHASES.DRAW_PLACEMENT ||
                             gameState.phase === GAME_PHASES.THREAT_RESPONSE ||
                             gameState.phase === GAME_PHASES.END_THREAT)
                                ? m(HandTray)
                                : null
                        ]),

                        // Right sidebar: Opponent
                        m("div", { class: "cg2-game-sidebar cg2-opponent-side" }, [
                            m(CharacterSidebar, { actor: gameState.opponent, label: "Opponent", isOpponent: true })
                        ])
                    ]),

                    // Card Preview Overlay (top-level so it works from sidebar clicks in all phases)
                    rendering().CardPreviewOverlay ? m(rendering().CardPreviewOverlay) : null
                ]);
            }
        };
    }

    // ── Fanned Card Stack Component ─────────────────────────────────────
    function FannedCardStack() {
        return {
            view(vnode) {
                let { cards, stackId, label, maxShow } = vnode.attrs;
                let CardFace = rendering().CardFace;
                let CardBack = rendering().CardBack;
                maxShow = maxShow || 3;
                let cardList = cards || [];

                // Empty placeholder
                if (cardList.length === 0) {
                    return m("div", { class: "cg2-fanned-stack cg2-fanned-empty" }, [
                        m("div", { class: "cg2-stack-label" }, label || "Empty"),
                        m("div", { class: "cg2-stack-placeholder" }, [
                            m("span", { class: "material-symbols-outlined" }, "add_card")
                        ])
                    ]);
                }

                return m("div", { class: "cg2-fanned-stack" }, [
                    m("div", { class: "cg2-stack-label" }, (label || "Stack") + " (" + cardList.length + ")"),
                    m("div", { class: "cg2-fanned-cards" },
                        cardList.slice(0, maxShow).map((card, i) => {
                            let flipKey = stackId + "-" + i;
                            let isFlipped = stackFlipped[flipKey];
                            let fanAngle = (i - (Math.min(cardList.length, maxShow) - 1) / 2) * 8;
                            let fanOffset = i * 25;

                            return m("div", {
                                key: flipKey,
                                class: "cg2-fanned-card-wrap" + (isFlipped ? " cg2-flipped" : ""),
                                style: {
                                    zIndex: 10 + i,
                                    transform: "translateX(" + fanOffset + "px) rotate(" + fanAngle + "deg)"
                                },
                                onclick(e) {
                                    e.stopPropagation();
                                    let showCardPreview = window.CardGame.Rendering?.showCardPreview;
                                    if (showCardPreview) showCardPreview(card);
                                },
                                ondblclick(e) {
                                    e.stopPropagation();
                                    stackFlipped[flipKey] = !stackFlipped[flipKey];
                                    m.redraw();
                                },
                                title: card.name + (card.effect ? ": " + card.effect : "") + "\n(Double-click to flip)"
                            }, [
                                m("div", { class: "cg2-fanned-card-inner" }, [
                                    // Front face
                                    m("div", { class: "cg2-fanned-face cg2-fanned-front" },
                                        m(CardFace, { card, compact: true })
                                    ),
                                    // Back face
                                    m("div", { class: "cg2-fanned-face cg2-fanned-back" },
                                        m(CardBack, { type: card.type })
                                    )
                                ])
                            ]);
                        }),
                        // Show "+N more" badge if more cards
                        cardList.length > maxShow
                            ? m("div", { class: "cg2-fanned-more" }, "+" + (cardList.length - maxShow))
                            : null
                    )
                ]);
            }
        };
    }

    // ── Character Sidebar Component ─────────────────────────────────────
    function CharacterSidebar() {
        return {
            view(vnode) {
                let { actor, label, isOpponent } = vnode.attrs;
                let char = actor.character || {};
                let stackId = isOpponent ? "opponent" : "player";
                let gameState = ctx().gameState;
                let activeCampaign = ctx().activeCampaign;
                let showCardPreview = rendering().showCardPreview || ctx().showCardPreview;

                // HP/Energy/Morale percentages
                let hpPct = Math.max(0, Math.min(100, (actor.hp / actor.maxHp) * 100));
                let energyPct = Math.max(0, Math.min(100, (actor.energy / actor.maxEnergy) * 100));

                // Character stats from the character card
                let stats = char.stats || {};

                return m("div", { class: "cg2-char-sidebar" + (isOpponent ? " cg2-opponent" : "") }, [

                    // Large portrait filling sidebar width, with name overlay
                    m("div", {
                        class: "cg2-sidebar-portrait-lg",
                        onclick() { if (showCardPreview) showCardPreview(char); },
                        title: "Click to view full card"
                    }, [
                        char.portraitUrl
                            ? m("img", { src: char.portraitUrl, class: "cg2-portrait-img-lg" })
                            : m("div", { class: "cg2-portrait-placeholder-lg" },
                                m("span", { class: "material-symbols-outlined" }, "person")),
                        // Name overlay at bottom of image
                        m("div", { class: "cg2-sidebar-name-overlay" }, [
                            m("span", { class: "cg2-sidebar-charname" }, char.name || "Unknown"),
                            char._templateClass ? m("span", { class: "cg2-sidebar-charclass" }, char._templateClass) : null
                        ])
                    ]),

                    // Compact stat grid (2x3)
                    m("div", { class: "cg2-sidebar-stat-grid" }, [
                        ["STR", "AGI", "END", "INT", "MAG", "CHA"].map(stat =>
                            m("div", { key: stat, class: "cg2-stat-cell" }, [
                                m("span", { class: "cg2-stat-label" }, stat),
                                m("span", { class: "cg2-stat-num" }, stats[stat] || "?")
                            ])
                        )
                    ]),

                    // HP and Energy bars
                    m("div", { class: "cg2-sidebar-bars" }, [
                        m("div", { class: "cg2-sidebar-stat cg2-stat-hp" }, [
                            m("span", { class: "cg2-stat-icon" }, "\u2665"),
                            m("div", { class: "cg2-stat-bar" }, [
                                m("div", { class: "cg2-stat-fill cg2-hp-fill", style: { width: hpPct + "%" } })
                            ]),
                            m("span", { class: "cg2-stat-val" }, actor.hp + "/" + actor.maxHp)
                        ]),
                        m("div", { class: "cg2-sidebar-stat cg2-stat-energy" }, [
                            m("span", { class: "cg2-stat-icon" }, "\u26A1"),
                            m("div", { class: "cg2-stat-bar" }, [
                                m("div", { class: "cg2-stat-fill cg2-energy-fill", style: { width: energyPct + "%" } })
                            ]),
                            m("span", { class: "cg2-stat-val" }, actor.energy + "/" + actor.maxEnergy)
                        ])
                    ]),

                    // AP indicator
                    m("div", { class: "cg2-ap-indicator" }, [
                        m("span", "AP: "),
                        m("span", { class: "cg2-ap-value" }, (actor.ap - actor.apUsed) + "/" + actor.ap)
                    ]),

                    // Campaign record (player only, no level)
                    !isOpponent && activeCampaign ? m("div", { class: "cg2-campaign-badge" }, [
                        m("span", { class: "cg2-campaign-record" }, activeCampaign.wins + "W/" + activeCampaign.losses + "L")
                    ]) : null,

                    // Chat button (Silence Rule: locked unless Talk card active)
                    isOpponent ? m("button", {
                        class: "cg2-chat-btn" + (gameState?.chat?.unlocked ? " cg2-chat-unlocked" : " cg2-chat-locked"),
                        disabled: !gameState?.chat?.unlocked,
                        title: gameState?.chat?.unlocked
                            ? "Chat with " + (char.name || "opponent")
                            : "Play a Talk action to speak with your opponent",
                        onclick() {
                            if (gameState?.chat?.unlocked && !gameState?.chat?.active) {
                                gameState.chat.active = true;
                                m.redraw();
                            }
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, "chat"),
                        gameState?.chat?.unlocked ? " Chat" : " Locked"
                    ]) : null,

                    // Poker Face widget (player sidebar only)
                    !isOpponent && gameState?.pokerFace?.enabled ? m("div", { class: "cg2-poker-face" }, [
                        m("span", { class: "cg2-poker-face-label" }, "\uD83C\uDFAD"),
                        m("span", { class: "cg2-poker-face-emotion" }, gameState.pokerFace.currentEmotion || "neutral"),
                        gameState.pokerFace.dominantTrend && gameState.pokerFace.dominantTrend !== "neutral"
                            ? m("span", { class: "cg2-poker-face-trend", title: "Trend: " + gameState.pokerFace.dominantTrend }, "\u2192 " + gameState.pokerFace.dominantTrend)
                            : null
                    ]) : null,

                    // Opponent banter commentary (opponent sidebar)
                    isOpponent && gameState?.pokerFace?.commentary ? m("div", {
                        class: "cg2-banter-bubble",
                        title: "Opponent says..."
                    }, [
                        m("span", { class: "cg2-banter-text" }, "\u201C" + gameState.pokerFace.commentary + "\u201D")
                    ]) : null,

                    // Status effects display
                    actor.statusEffects && actor.statusEffects.length > 0
                        ? m("div", { class: "cg2-status-effects" },
                            actor.statusEffects.map(effect =>
                                m("div", {
                                    key: effect.id,
                                    class: "cg2-status-effect",
                                    style: { borderColor: effect.color },
                                    title: effect.name + ": " + (STATUS_EFFECTS[effect.id.toUpperCase()]?.description || "") +
                                           (effect.durationType === "turns" ? " (" + effect.turnsRemaining + " turns)" : "")
                                }, [
                                    m("span", {
                                        class: "material-symbols-outlined cg2-status-icon",
                                        style: { color: effect.color }
                                    }, effect.icon),
                                    effect.durationType === "turns"
                                        ? m("span", { class: "cg2-status-turns" }, effect.turnsRemaining)
                                        : null
                                ])
                            )
                        )
                        : null,

                    // Fanned card stack (modifier cards on character)
                    m(FannedCardStack, {
                        cards: actor.cardStack,
                        stackId: stackId + "-mods",
                        label: "Equip",
                        maxShow: 3
                    })
                ]);
            }
        };
    }

    // ── Action Bar Component ────────────────────────────────────────────
    let expandedResultIndex = -1;  // Which result slot has its detail popover open

    function ActionBar() {
        return {
            view() {
                let gameState = ctx().gameState;
                let viewingDeck = ctx().viewingDeck;
                let bar = gameState.actionBar;
                let isPlacement = gameState.phase === GAME_PHASES.DRAW_PLACEMENT;
                let isResolution = gameState.phase === GAME_PHASES.RESOLUTION;
                let cardFrontBg = viewingDeck?.cardFrontImageUrl || null;
                let CardFace = rendering().CardFace;
                let showCardPreview = rendering().showCardPreview;
                let placeCard = E().placeCard;
                let canModifyAction = E().canModifyAction;
                let isCoreCardType = E().isCoreCardType;
                let removeCardFromPosition = E().removeCardFromPosition;
                let selectAction = E().selectAction;
                let isActionPlacedThisRound = E().isActionPlacedThisRound;
                let getActionsForActor = E().getActionsForActor;

                // Resolution state for inline result row
                let gs = GS().state;
                let D20Dice = rendering().D20Dice;
                let totals = gs.resolutionTotals || {};

                // Get player's available actions for the icon picker
                let playerActions = getActionsForActor ? getActionsForActor(gameState.player) : [];
                let playerActor = gameState.player;

                return m("div", { class: "cg2-action-bar" }, [
                    m("div", { class: "cg2-action-bar-label" }, "Action Bar"),
                    m("div", { class: "cg2-action-bar-track" },
                        bar.positions.map((pos, i) => {
                            let isActive = isResolution && bar.resolveIndex === i;
                            let isResolved = pos.resolved;
                            let isPlayerPos = pos.owner === "player";
                            let isThreatPos = pos.isThreat;
                            let playerOutOfAP = gameState.player.apUsed >= gameState.player.ap;
                            let canDrop = isPlacement && isPlayerPos && gameState.currentTurn === "player" && !isThreatPos;
                            // Mark empty player positions as locked if out of AP
                            let isLocked = isPlacement && isPlayerPos && !pos.stack && playerOutOfAP && !isThreatPos;

                            let hasSelected = selectedHandCard && canDrop && !isLocked;
                            return m("div", {
                                key: pos.index,
                                class: "cg2-action-position" +
                                       (isThreatPos ? " cg2-threat-pos" : (isPlayerPos ? " cg2-player-pos" : " cg2-opponent-pos")) +
                                       (isActive ? " cg2-active" : "") +
                                       (isResolved ? " cg2-resolved" : "") +
                                       (isLocked ? " cg2-locked" : "") +
                                       (canDrop && !isLocked ? " cg2-droppable" : "") +
                                       (hasSelected ? " cg2-tap-target" : ""),
                                ondragover(e) {
                                    // Allow drops on positions with a core card (modifiers) or empty positions (core cards)
                                    if (canDrop && !isLocked) e.preventDefault();
                                },
                                ondrop(e) {
                                    if (!canDrop || isLocked) return;
                                    e.preventDefault();
                                    selectedHandCard = null;  // Clear selection on drag-drop
                                    let cardData = e.dataTransfer.getData("text/plain");
                                    try {
                                        let card = JSON.parse(cardData);
                                        if (pos.stack && pos.stack.coreCard) {
                                            // Position has a core card — drop as modifier
                                            let isModifier = card.type === "skill" || card.type === "item" || card.type === "magic" || card.type === "apparel";
                                            if (!isModifier) {
                                                if (typeof page !== "undefined" && page.toast) page.toast("warn", "Use the icon picker for actions");
                                                return;
                                            }
                                            let compat = canModifyAction(pos.stack.coreCard, card);
                                            if (!compat.allowed) {
                                                if (typeof page !== "undefined" && page.toast) page.toast("warn", compat.reason);
                                                return;
                                            }
                                            placeCard(gameState, pos.index, card, true);
                                        } else {
                                            // Empty position — allow core cards and items (auto-selects action)
                                            let canPlace = isCoreCardType(card.type) || card.type === "item";
                                            if (!canPlace) {
                                                if (typeof page !== "undefined" && page.toast) page.toast("warn", "Choose an action first for this card type");
                                                return;
                                            }
                                            placeCard(gameState, pos.index, card, false);
                                        }
                                    } catch (err) {
                                        console.error("[CardGame v2] Drop error:", err);
                                    }
                                },
                                // Tap-to-place: if a hand card is selected, tapping a position places it
                                onclick(e) {
                                    if (!selectedHandCard || !canDrop || isLocked) return;
                                    e.stopPropagation();
                                    let card = selectedHandCard;
                                    if (pos.stack && pos.stack.coreCard) {
                                        // Position has core card — place as modifier
                                        let isModifier = card.type === "skill" || card.type === "item" || card.type === "magic" || card.type === "apparel";
                                        if (!isModifier) {
                                            if (typeof page !== "undefined" && page.toast) page.toast("warn", "Use the icon picker for actions");
                                            return;
                                        }
                                        let compat = canModifyAction(pos.stack.coreCard, card);
                                        if (!compat.allowed) {
                                            if (typeof page !== "undefined" && page.toast) page.toast("warn", compat.reason);
                                            return;
                                        }
                                        placeCard(gameState, pos.index, card, true);
                                        selectedHandCard = null;
                                    } else {
                                        // Empty position — allow core cards and items (auto-selects action)
                                        let canPlace = isCoreCardType(card.type) || card.type === "item";
                                        if (canPlace) {
                                            placeCard(gameState, pos.index, card, false);
                                            selectedHandCard = null;
                                        } else {
                                            if (typeof page !== "undefined" && page.toast) page.toast("info", "Choose an action first, then tap to add this card as a modifier");
                                        }
                                    }
                                    m.redraw();
                                }
                            }, [
                                m("div", { class: "cg2-pos-number" }, isThreatPos ? "T" + pos.index : pos.index),
                                m("div", { class: "cg2-pos-owner" },
                                    isThreatPos
                                        ? [m("span", { class: "material-symbols-outlined", style: "font-size:12px;vertical-align:middle" }, pos.threat?.imageIcon || "warning"), " Threat"]
                                        : (isPlayerPos ? "You" : "Opp")),

                                // Threat display
                                isThreatPos && pos.threat
                                    ? m("div", { class: "cg2-pos-threat" }, [
                                        m("div", { class: "cg2-threat-name" }, pos.threat.name),
                                        m("div", { class: "cg2-threat-stats" }, [
                                            m("span", { title: "Attack" }, "ATK " + pos.threat.atk),
                                            m("span", { title: "Defense" }, "DEF " + pos.threat.def),
                                            m("span", { title: "Hit Points" }, "HP " + pos.threat.hp)
                                        ]),
                                        m("div", { class: "cg2-threat-target" }, [
                                            "\u2192 ",
                                            m("span", { class: pos.target === "player" ? "cg2-threat-target-you" : "cg2-threat-target-opp" },
                                                pos.target === "player" ? "You" : "Opponent")
                                        ])
                                    ])
                                    : pos.stack && pos.stack.coreCard
                                        ? m("div", { class: "cg2-pos-stack" }, [
                                            // Core card as mini card (on top)
                                            m("div", {
                                                class: "cg2-pos-core-card",
                                                onclick(e) {
                                                    // If a hand card is selected for tap-to-place, let click bubble to position handler
                                                    if (selectedHandCard && canDrop && !isLocked) return;
                                                    e.stopPropagation();
                                                    if (showCardPreview) showCardPreview(pos.stack.coreCard);
                                                },
                                                title: pos.stack.coreCard.name + (pos.stack.modifiers.length > 0 ? " + " + pos.stack.modifiers.length + " modifier(s)" : "") + " (click to enlarge)"
                                            }, [
                                                m(CardFace, { card: pos.stack.coreCard, bgImage: cardFrontBg, compact: true }),
                                                // Stack count badge
                                                pos.stack.modifiers.length > 0
                                                    ? m("div", { class: "cg2-stack-count" }, "+" + pos.stack.modifiers.length)
                                                    : null,
                                                // Remove button (only during placement phase for player's cards)
                                                canDrop ? m("button", {
                                                    class: "cg2-pos-remove-btn",
                                                    onclick(e) {
                                                        e.stopPropagation();
                                                        removeCardFromPosition(gameState, pos.index);
                                                    },
                                                    title: "Remove card (return to hand)"
                                                }, m("span", { class: "material-symbols-outlined" }, "close")) : null
                                            ]),
                                            // Modifier list (shown below for clarity)
                                            pos.stack.modifiers.length > 0
                                                ? m("div", { class: "cg2-pos-modifiers-list" },
                                                    pos.stack.modifiers.map((mod, mi) =>
                                                        m("div", {
                                                            key: mi,
                                                            class: "cg2-pos-mod-item cg2-mod-" + mod.type,
                                                            onclick(e) {
                                                                // If a hand card is selected for tap-to-place, let click bubble to position handler
                                                                if (selectedHandCard && canDrop && !isLocked) return;
                                                                e.stopPropagation();
                                                                if (showCardPreview) showCardPreview(mod);
                                                            },
                                                            title: mod.name + " (click to enlarge)"
                                                        }, [
                                                            m("span", { class: "material-symbols-outlined", style: "font-size:10px" },
                                                                mod.type === "skill" ? "star" : mod.type === "magic" ? "auto_fix_high" : "category"),
                                                            " " + mod.name
                                                        ])
                                                    )
                                                )
                                                : null
                                        ])
                                        : m("div", { class: "cg2-pos-empty" + (isLocked ? " cg2-pos-locked" : "") },
                                            isLocked
                                                ? [m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "lock"), " No AP"]
                                                : (canDrop && selectAction && playerActions.length > 0)
                                                    ? m("div", { class: "cg2-action-picker" },
                                                        playerActions.map(actionName => {
                                                            let def = ACTION_DEFINITIONS[actionName];
                                                            if (!def) return null;
                                                            let alreadyPlaced = isActionPlacedThisRound && isActionPlacedThisRound(gameState, actionName, "player");
                                                            let cantAfford = def.energyCost > 0 && def.energyCost > playerActor.energy;
                                                            let disabled = alreadyPlaced || cantAfford;
                                                            return m("button", {
                                                                key: actionName,
                                                                class: "cg2-action-icon-btn" + (disabled ? " cg2-action-disabled" : ""),
                                                                disabled: disabled,
                                                                title: actionName +
                                                                    (alreadyPlaced ? " (already placed)" : "") +
                                                                    (cantAfford ? " (need " + def.energyCost + " energy)" : "") +
                                                                    (!disabled ? " (" + (def.desc || def.type) + ")" : ""),
                                                                onclick(e) {
                                                                    e.stopPropagation();
                                                                    if (!disabled) selectAction(gameState, pos.index, actionName);
                                                                }
                                                            }, m("span", { class: "material-symbols-outlined" }, def.icon));
                                                        })
                                                    )
                                                    : "\u2013"),

                                // Resolution marker
                                isActive ? m("div", { class: "cg2-resolve-marker" }, "\u25B6") : null,

                                // Resolved result badge (shows damage or checkmark after resolution)
                                pos.resolved ? m("div", {
                                    class: "cg2-pos-result-badge" + (pos.combatResult && pos.combatResult.damageResult ? " cg2-result-damage" : " cg2-result-ok")
                                }, pos.combatResult && pos.combatResult.damageResult
                                    ? "-" + pos.combatResult.damageResult.finalDamage
                                    : "\u2713"
                                ) : null
                            ]);
                        })
                    ),

                    // Inline result row — one slot per position, aligned with track above
                    isResolution ? m("div", { class: "cg2-result-row" },
                        bar.positions.map(function(pos, i) {
                            let isActiveSlot = i === bar.resolveIndex;
                            let combat = pos.combatResult;
                            let magic = pos.magicResult;
                            // During "result" phase, results aren't on pos yet — use live state
                            let activeCombat = (isActiveSlot && gs.resolutionPhase === "result") ? gs.currentCombatResult : null;
                            let activeMagic = (isActiveSlot && gs.resolutionPhase === "result") ? gs.currentMagicResult : null;
                            let isRolling = isActiveSlot && gs.resolutionPhase === "rolling";
                            let isShowingResult = isActiveSlot && gs.resolutionPhase === "result";
                            let showResult = pos.resolved && combat;
                            let showMagicResult = pos.resolved && magic;
                            let isNonCombat = pos.resolved && !combat && !magic;
                            let isExpanded = expandedResultIndex === i;
                            let isMagicSlot = pos.stack?.coreCard && (pos.stack.coreCard.name === "Channel" || pos.stack.coreCard.type === "magic");

                            // Determine outcome class for coloring (use activeCombat or resolved combat)
                            let displayCombat = activeCombat || combat;
                            let outcomeClass = "";
                            if (displayCombat && displayCombat.outcome) {
                                outcomeClass = displayCombat.outcome.damageMultiplier > 0 ? "hit"
                                    : displayCombat.outcome.damageMultiplier < 0 ? "counter" : "miss";
                            }

                            return m("div", {
                                key: i,
                                class: "cg2-result-slot"
                                    + (isActiveSlot ? " cg2-result-active" : "")
                                    + (showResult || showMagicResult ? " cg2-result-resolved" : "")
                                    + (!pos.resolved && !isActiveSlot ? " cg2-result-pending" : ""),
                                style: { position: "relative" },
                                onclick: (showResult || showMagicResult) ? function() {
                                    expandedResultIndex = expandedResultIndex === i ? -1 : i;
                                    m.redraw();
                                } : null
                            }, [
                                // Combat rolling: ATK vs DEF animated dice
                                isRolling && D20Dice && !isMagicSlot ? m("div", { class: "cg2-result-rolling" }, [
                                    m("div", { class: "cg2-result-die" },
                                        m(D20Dice, { value: gs.resolutionDiceFaces.attack, rolling: true })),
                                    m("span", { class: "cg2-result-vs" }, "vs"),
                                    m("div", { class: "cg2-result-die" },
                                        m(D20Dice, { value: gs.resolutionDiceFaces.defense, rolling: true }))
                                ]) : null,

                                // Magic rolling: single die with casting label
                                isRolling && D20Dice && isMagicSlot ? m("div", { class: "cg2-result-rolling cg2-result-magic-roll" }, [
                                    m("div", { class: "cg2-result-die" },
                                        m(D20Dice, { value: gs.resolutionDiceFaces.attack, rolling: true })),
                                    m("div", { class: "cg2-result-casting" }, "Casting...")
                                ]) : null,

                                // Active combat result showing (brief display before marking resolved)
                                isShowingResult && activeCombat ? m("div", { class: "cg2-result-combat" }, [
                                    m("div", { class: "cg2-result-outcome cg2-outcome-" + outcomeClass },
                                        activeCombat.outcome.label),
                                    activeCombat.damageResult
                                        ? m("div", { class: "cg2-result-dmg" },
                                            "-" + activeCombat.damageResult.finalDamage + " HP")
                                        : null
                                ]) : null,

                                // Active magic result showing
                                isShowingResult && activeMagic ? m("div", { class: "cg2-result-magic" }, [
                                    m("div", { class: "cg2-result-spell-name" }, activeMagic.spellName),
                                    activeMagic.fizzled
                                        ? m("div", { class: "cg2-result-fizzle" }, "Fizzled!")
                                        : [
                                            activeMagic.damage > 0
                                                ? m("div", { class: "cg2-result-dmg" }, "-" + activeMagic.damage + " HP") : null,
                                            activeMagic.healing > 0
                                                ? m("div", { class: "cg2-result-heal" }, "+" + activeMagic.healing + " HP") : null,
                                            !activeMagic.damage && !activeMagic.healing && activeMagic.effects.length > 0
                                                ? m("div", { class: "cg2-result-effect" }, activeMagic.effects[0]) : null
                                        ]
                                ]) : null,

                                // Resolved combat: outcome + damage (permanent)
                                showResult ? m("div", { class: "cg2-result-combat" }, [
                                    m("div", { class: "cg2-result-outcome cg2-outcome-" + outcomeClass },
                                        combat.outcome.label),
                                    combat.damageResult
                                        ? m("div", { class: "cg2-result-dmg" },
                                            "-" + combat.damageResult.finalDamage + " HP")
                                        : m("div", { class: "cg2-result-dmg cg2-result-zero" }, "0"),
                                    combat.selfDamageResult
                                        ? m("div", { class: "cg2-result-counter" },
                                            "+" + combat.selfDamageResult.finalDamage + " counter")
                                        : null
                                ]) : null,

                                // Resolved magic: spell name + effect (permanent)
                                showMagicResult ? m("div", { class: "cg2-result-magic" }, [
                                    m("div", { class: "cg2-result-spell-name" }, magic.spellName),
                                    magic.fizzled
                                        ? m("div", { class: "cg2-result-fizzle" }, "Fizzled!")
                                        : [
                                            magic.damage > 0
                                                ? m("div", { class: "cg2-result-dmg" }, "-" + magic.damage + " HP") : null,
                                            magic.healing > 0
                                                ? m("div", { class: "cg2-result-heal" }, "+" + magic.healing + " HP") : null,
                                            !magic.damage && !magic.healing && magic.effects.length > 0
                                                ? m("div", { class: "cg2-result-effect" }, magic.effects[0]) : null
                                        ]
                                ]) : null,

                                // Resolved non-combat: checkmark + action name (or skipped)
                                isNonCombat ? m("div", { class: "cg2-result-noncombat" }, [
                                    pos.skipped
                                        ? m("span", { class: "material-symbols-outlined", style: "font-size:16px;color:#ef5350" }, "block")
                                        : m("span", { class: "material-symbols-outlined", style: "font-size:16px;color:#81c784" }, "check_circle"),
                                    m("div", { style: "font-size:10px;color:" + (pos.skipped ? "#ef5350" : "#aaa") },
                                        pos.skipped ? (pos.skipReason || "Skipped") : (pos.stack?.coreCard?.name || "Done"))
                                ]) : null,

                                // Pending: dot placeholder
                                !pos.resolved && !isActiveSlot
                                    ? m("div", { class: "cg2-result-empty" }, "\u00B7") : null,

                                // Click-to-expand detail popover (combat)
                                isExpanded && combat ? m("div", { class: "cg2-result-detail", onclick: function(e) { e.stopPropagation(); } }, [
                                    m("div", { class: "cg2-detail-row" }, [
                                        m("span", { class: "cg2-detail-label cg2-detail-atk" }, "ATK"),
                                        m("span", combat.attackRoll.raw + " + " + combat.attackRoll.strMod + "S + " + combat.attackRoll.atkBonus + "A"),
                                        m("strong", " = " + combat.attackRoll.total)
                                    ]),
                                    m("div", { class: "cg2-detail-row" }, [
                                        m("span", { class: "cg2-detail-label cg2-detail-def" }, "DEF"),
                                        m("span", combat.defenseRoll.raw + " + " + combat.defenseRoll.endMod + "E + " + combat.defenseRoll.defBonus + "D"),
                                        m("strong", " = " + combat.defenseRoll.total)
                                    ]),
                                    m("div", { class: "cg2-detail-outcome cg2-outcome-" + outcomeClass },
                                        combat.outcome.label + " (" + (combat.outcome.diff >= 0 ? "+" : "") + combat.outcome.diff + ")")
                                ]) : null,

                                // Click-to-expand detail popover (magic)
                                isExpanded && magic ? m("div", { class: "cg2-result-detail", onclick: function(e) { e.stopPropagation(); } }, [
                                    m("div", { class: "cg2-detail-row" }, [
                                        m("span", { class: "cg2-detail-label cg2-detail-mag" }, "MAG"),
                                        m("span", magic.roll.raw + " + " + magic.roll.magStat + "M" + (magic.roll.skillMod ? " + " + magic.roll.skillMod + "S" : "")),
                                        m("strong", " = " + magic.roll.total)
                                    ]),
                                    magic.effects.length > 0
                                        ? m("div", { class: "cg2-detail-effects" }, magic.effects.map(function(eff) {
                                            return m("div", eff);
                                        }))
                                        : null
                                ]) : null
                            ]);
                        })
                    ) : null,

                    // Running totals strip (shown during resolution)
                    isResolution ? m("div", { class: "cg2-result-totals" }, [
                        m("span", { class: "cg2-totals-player" }, [
                            "You: ",
                            m("span", { class: "cg2-totals-dealt" }, totals.playerDamageDealt || 0),
                            " dealt, ",
                            m("span", { class: "cg2-totals-taken" }, totals.playerDamageTaken || 0),
                            " taken"
                        ]),
                        m("span", { class: "cg2-totals-divider" }, "|"),
                        m("span", { class: "cg2-totals-opponent" }, [
                            "Opp: ",
                            m("span", { class: "cg2-totals-dealt" }, totals.opponentDamageDealt || 0),
                            " dealt, ",
                            m("span", { class: "cg2-totals-taken" }, totals.opponentDamageTaken || 0),
                            " taken"
                        ])
                    ]) : null
                ]);
            }
        };
    }

    // ── Hand Tray Component ─────────────────────────────────────────────
    function HandTray() {
        return {
            view() {
                let gameState = ctx().gameState;
                let viewingDeck = ctx().viewingDeck;
                let hand = gameState.player.hand || [];
                let filteredHand = handTrayFilter === "all"
                    ? hand
                    : hand.filter(c => c.type === handTrayFilter);

                // Get card front image from deck if available
                let cardFrontBg = viewingDeck && viewingDeck.cardFrontImageUrl
                    ? viewingDeck.cardFrontImageUrl : null;

                let CardFace = rendering().CardFace;
                let CardPreviewOverlay = rendering().CardPreviewOverlay;
                let showCardPreview = rendering().showCardPreview;
                let placeThreatDefenseCard = GS().placeThreatDefenseCard;

                return m("div", { class: "cg2-hand-tray" }, [
                    // Header with label and filter tabs
                    m("div", { class: "cg2-hand-header" }, [
                        m("span", { class: "cg2-hand-label" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;margin-right:4px" }, "playing_cards"),
                            "Your Hand (" + hand.length + ")"
                        ]),
                        m("div", { class: "cg2-hand-tabs" }, [
                            ["all", "skill", "magic", "item"].map(f =>
                                m("span", {
                                    class: "cg2-hand-tab" + (handTrayFilter === f ? " cg2-tab-active" : ""),
                                    onclick() { handTrayFilter = f; }
                                }, f.charAt(0).toUpperCase() + f.slice(1))
                            )
                        ])
                    ]),

                    // Cards - using compact card styling with click-to-preview
                    m("div", { class: "cg2-hand-cards" },
                        filteredHand.length > 0
                            ? filteredHand.map((card, i) => {
                                // Check if in threat response mode
                                let isThreatPhase = gameState.phase === GAME_PHASES.THREAT_RESPONSE ||
                                                    gameState.phase === GAME_PHASES.END_THREAT;
                                let isResponder = gameState.threatResponse?.responder === "player";
                                let hasAP = (gameState.player.threatResponseAP || 0) > 0;
                                let canPlaceDefense = isThreatPhase && isResponder && hasAP;

                                // Determine card role for badge
                                let cardRole = (card.type === "character" || card.type === "apparel") ? "equip"
                                    : card.type === "magic" ? "magic"
                                    : card.type === "action" ? "action"
                                    : card.type === "talk" ? "talk"
                                    : card.type === "loot" ? "loot"
                                    : card.type === "item" ? "item"
                                    : card.type === "skill" ? "skill"
                                    : "support";

                                let isSelected = selectedHandCard === card;
                                return m("div", {
                                    key: card.name + "-" + i,
                                    class: "cg2-hand-card-wrapper cg2-role-" + cardRole
                                        + (canPlaceDefense ? " cg2-defense-eligible" : "")
                                        + (isSelected ? " cg2-hand-selected" : ""),
                                    draggable: !isThreatPhase,
                                    ondragstart(e) {
                                        if (isThreatPhase) {
                                            e.preventDefault();
                                            return;
                                        }
                                        e.dataTransfer.setData("text/plain", JSON.stringify(card));
                                        e.dataTransfer.effectAllowed = "move";
                                    },
                                    onclick(e) {
                                        e.stopPropagation();
                                        // In threat response phase, clicking adds to defense stack
                                        if (canPlaceDefense) {
                                            if (placeThreatDefenseCard) placeThreatDefenseCard(card);
                                            return;
                                        }
                                        // During placement, tap-to-select for touch/tablet support
                                        let isPlacementPhase = gameState.phase === GAME_PHASES.DRAW_PLACEMENT;
                                        let isPlayerTurn = gameState.currentTurn === "player";
                                        if (isPlacementPhase && isPlayerTurn) {
                                            // Magic cards: auto-place on first empty position (quick action)
                                            if (card.type === "magic" && !isSelected) {
                                                let bar = gameState.actionBar;
                                                if (bar && bar.positions) {
                                                    let emptyPos = bar.positions.find(p => p.owner === "player" && !p.stack && !p.isThreat);
                                                    if (emptyPos) {
                                                        let placeCard = E().placeCard;
                                                        if (placeCard) placeCard(gameState, emptyPos.index, card, false);
                                                        selectedHandCard = null;
                                                        return;
                                                    }
                                                }
                                            }
                                            // Toggle selection for tap-to-place
                                            if (isSelected) {
                                                selectedHandCard = null;  // Deselect
                                                // Show preview on deselect so user can inspect
                                                if (showCardPreview) showCardPreview(card);
                                            } else {
                                                selectedHandCard = card;  // Select for placement
                                            }
                                            m.redraw();
                                            return;
                                        }
                                        if (showCardPreview) showCardPreview(card);
                                    },
                                    title: canPlaceDefense
                                        ? "Click to add to defense"
                                        : isSelected
                                            ? "Selected - tap an action bar position to place, or tap again to deselect"
                                            : "Tap to select, then tap action bar position to place"
                                }, [
                                    m(CardFace, { card, bgImage: cardFrontBg, compact: true }),
                                    // Role badge overlay
                                    m("div", { class: "cg2-card-role-badge cg2-badge-" + cardRole },
                                        cardRole === "equip" ? "EQUIP"
                                        : cardRole === "magic" ? "SPELL"
                                        : cardRole === "action" ? "ACTION"
                                        : cardRole === "talk" ? "TALK"
                                        : cardRole === "loot" ? "LOOT"
                                        : cardRole === "item" ? "ITEM"
                                        : cardRole === "skill" ? "SKILL"
                                        : "CARD"),
                                    // Stat badge overlay (bottom-left, shows primary adjustment)
                                    (function() {
                                        let getLabel = rendering().getCardStatLabel;
                                        let label = getLabel ? getLabel(card) : null;
                                        return label ? m("div", { class: "cg2-card-stat-badge" }, label) : null;
                                    })(),
                                    // Preview button (always available, doesn't affect selection)
                                    m("button", {
                                        class: "cg2-hand-preview-btn",
                                        title: "Preview " + card.name,
                                        onclick(e) {
                                            e.stopPropagation();
                                            if (showCardPreview) showCardPreview(card);
                                        }
                                    }, m("span", { class: "material-symbols-outlined" }, "visibility")),
                                    // Show art thumbnail if available
                                    card.imageUrl ? m("img", {
                                        src: card.imageUrl,
                                        class: "cg2-card-art-thumb"
                                    }) : null
                                ]);
                            })
                            : m("div", { class: "cg2-hand-empty" }, "No cards of this type")
                    ),
                    // Card Preview Overlay
                    CardPreviewOverlay ? m(CardPreviewOverlay) : null
                ]);
            }
        };
    }

    // ── Expose on CardGame namespace ─────────────────────────────────
    Object.assign(window.CardGame.UI, {
        gameViewState: {
            get handTrayFilter() { return handTrayFilter; },
            set handTrayFilter(v) { handTrayFilter = v; },
            get stackFlipped() { return stackFlipped; },
            set stackFlipped(v) { stackFlipped = v; },
            get selectedHandCard() { return selectedHandCard; },
            set selectedHandCard(v) { selectedHandCard = v; },
            clearSelection() { selectedHandCard = null; }
        },
        calcCharScore,
        formatPhase,
        CharacterSelectUI,
        GameView,
        FannedCardStack,
        CharacterSidebar,
        ActionBar,
        HandTray
    });

    console.log('[CardGame] UI/gameView module loaded');

})();
