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

    // ── State ──────────────────────────────────────────────────────────
    let handTrayFilter = "all";  // "all" | "action" | "skill" | "magic" | "item"
    let stackFlipped = {};  // { "player-0": true, "opponent-1": false }

    // ── Helpers ─────────────────────────────────────────────────────────
    function ctx() { return window.CardGame.ctx || {}; }
    function storage() { return window.CardGame.Storage || {}; }
    function rendering() { return window.CardGame.Rendering || {}; }

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
                let llmStatus = ctx().llmStatus || { checked: false, available: false };

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
                let createGameState = ctx().createGameState;
                let applyCampaignBonuses = storage().applyCampaignBonuses;
                let campaignStorage = storage().campaignStorage;
                let initializeLLMComponents = ctx().initializeLLMComponents;

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
                                            ctx().gameState = createGameState(viewingDeck, char);
                                            ctx().gameCharSelection = null;
                                            if (ctx().gameState) {
                                                // Load and apply campaign bonuses
                                                ctx().activeCampaign = await campaignStorage.load(viewingDeck.deckName || viewingDeck.storageName);
                                                if (ctx().activeCampaign) applyCampaignBonuses(ctx().gameState, ctx().activeCampaign);
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
                let llmStatus = ctx().llmStatus || {};

                // Check LLM connectivity at game start
                if (!llmStatus.checked && typeof ctx().checkLlmConnectivity === "function") {
                    ctx().checkLlmConnectivity();
                }

                if (!gameState && viewingDeck) {
                    // Always let player pick from all characters
                    let allChars = (viewingDeck.cards || []).filter(c => c.type === "character");
                    console.log("[CardGame v2] Game init - found " + allChars.length + " character cards:", allChars.map(c => c.name));

                    if (allChars.length > 0 && !gameCharSelection) {
                        // Show character selection for ALL characters
                        ctx().gameCharSelection = { characters: allChars, selected: null };
                    } else if (gameCharSelection?.selected) {
                        // Character selected - start game
                        ctx().gameState = ctx().createGameState(viewingDeck, gameCharSelection.selected);
                        ctx().gameCharSelection = null;
                        if (ctx().gameState) {
                            // Load and apply campaign bonuses (async, non-blocking)
                            storage().campaignStorage.load(viewingDeck.deckName).then(c => {
                                ctx().activeCampaign = c;
                                if (c) storage().applyCampaignBonuses(ctx().gameState, c);
                                m.redraw();
                            });
                            if (typeof ctx().initializeLLMComponents === "function") {
                                ctx().initializeLLMComponents(ctx().gameState, viewingDeck);
                            }
                            // Animation will trigger runInitiativePhase() when complete
                        }
                    } else if (allChars.length === 0) {
                        console.error("[CardGame v2] No characters in deck");
                    }
                }
            },
            view() {
                let gameState = ctx().gameState;
                let gameCharSelection = ctx().gameCharSelection;
                let viewingDeck = ctx().viewingDeck;
                let initAnimState = ctx().initAnimState;

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

                // Resolve engine functions from ctx
                let advancePhase = ctx().advancePhase;
                let drawCardsForActor = ctx().drawCardsForActor;
                let checkPlacementComplete = ctx().checkPlacementComplete;
                let endTurn = ctx().endTurn;

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
                                // Action Bar (visible in placement and resolution)
                                (isPlacement || gameState.phase === GAME_PHASES.RESOLUTION)
                                    ? m(ActionBar)
                                    : null,

                                // Narrator text overlay
                                gameState.narrationText
                                    ? m("div", { class: "cg2-narration-overlay" }, [
                                        m("div", { class: "cg2-narration-text" }, [
                                            m("span", { class: "material-symbols-outlined cg2-narration-icon" }, "campaign"),
                                            gameState.narrationText
                                        ])
                                    ])
                                    : null,

                                // Talk card chat overlay (Phase 8)
                                gameState.chat?.active && TalkChatUI ? m(TalkChatUI) : null,

                                // Game Over overlay
                                gameState.winner && GameOverUI ? m(GameOverUI) : null
                            ]),

                            // Action Panel (hidden during resolution to avoid duplicate UI)
                            gameState.phase !== GAME_PHASES.RESOLUTION ? m("div", { class: "cg2-action-panel" }, [
                                // Status message - dynamic based on phase and state
                                m("div", { class: "cg2-action-status" },
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
                            ]) : null
                        ]),

                        // Right sidebar: Opponent
                        m("div", { class: "cg2-game-sidebar cg2-opponent-side" }, [
                            m(CharacterSidebar, { actor: gameState.opponent, label: "Opponent", isOpponent: true })
                        ])
                    ]),

                    // Bottom: Hand Tray (visible in initiative, placement, and threat response phases)
                    (gameState.phase === GAME_PHASES.INITIATIVE ||
                     gameState.phase === GAME_PHASES.DRAW_PLACEMENT ||
                     gameState.phase === GAME_PHASES.THREAT_RESPONSE ||
                     gameState.phase === GAME_PHASES.END_THREAT)
                        ? m(HandTray)
                        : null
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
                                    // Double-click to flip
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

                return m("div", { class: "cg2-char-sidebar" + (isOpponent ? " cg2-opponent" : "") }, [
                    m("div", { class: "cg2-sidebar-label" }, label),

                    // Prominent game stats bar
                    m("div", { class: "cg2-sidebar-stats" }, [
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

                    // Character portrait (clickable for popup)
                    m("div", {
                        class: "cg2-sidebar-portrait",
                        onclick() { if (showCardPreview) showCardPreview(char); },
                        title: "Click to view full card"
                    }, [
                        char.portraitUrl
                            ? m("img", { src: char.portraitUrl, class: "cg2-portrait-img-sidebar" })
                            : m("span", { class: "material-symbols-outlined", style: "font-size:48px;color:#B8860B" }, "person"),
                        m("div", { class: "cg2-portrait-name" }, char.name || "Unknown")
                    ]),

                    // Campaign level badge (player only)
                    !isOpponent && activeCampaign ? m("div", { class: "cg2-campaign-badge" }, [
                        m("span", { class: "cg2-campaign-level" }, "Lv." + activeCampaign.level),
                        m("span", { class: "cg2-campaign-xp" }, activeCampaign.xp + " XP"),
                        m("span", { class: "cg2-campaign-record" }, activeCampaign.wins + "W/" + activeCampaign.losses + "L")
                    ]) : null,

                    // AP indicator
                    m("div", { class: "cg2-ap-indicator" }, [
                        m("span", "AP: "),
                        m("span", { class: "cg2-ap-value" }, (actor.ap - actor.apUsed) + "/" + actor.ap)
                    ]),

                    // Compact counts row
                    m("div", { class: "cg2-hand-count" }, [
                        m("span", { title: "Cards in hand" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:12px;vertical-align:middle" }, "playing_cards"),
                            " " + (actor.hand ? actor.hand.length : 0)
                        ]),
                        m("span", { title: "Cards in deck" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:12px;vertical-align:middle" }, "layers"),
                            " " + (actor.drawPile ? actor.drawPile.length : 0)
                        ])
                    ]),

                    // Opponent hand visual (stack of card backs)
                    isOpponent && actor.hand && actor.hand.length > 0
                        ? m("div", { class: "cg2-opp-hand-visual" }, [
                            m("div", { class: "cg2-opp-hand-stack" },
                                // Show up to 5 card backs in a fanned stack
                                Array.from({ length: Math.min(actor.hand.length, 5) }).map((_, i) =>
                                    m("div", {
                                        key: i,
                                        class: "cg2-opp-card-back",
                                        style: {
                                            left: (i * 6) + "px",
                                            top: (i * 2) + "px",
                                            zIndex: i,
                                            transform: "rotate(" + ((i - 2) * 3) + "deg)"
                                        }
                                    })
                                )
                            )
                        ])
                        : null,

                    // Chat button (Silence Rule: locked unless Talk card active)
                    isOpponent ? m("button", {
                        class: "cg2-chat-btn" + (gameState?.chat?.unlocked ? " cg2-chat-unlocked" : " cg2-chat-locked"),
                        disabled: !gameState?.chat?.unlocked,
                        title: gameState?.chat?.unlocked
                            ? "Chat with " + (char.name || "opponent")
                            : "Play a Talk card to speak with your opponent",
                        onclick() {
                            if (gameState?.chat?.unlocked && !gameState?.chat?.active) {
                                // Re-open chat if unlocked but closed
                                gameState.chat.active = true;
                                m.redraw();
                            }
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, "chat"),
                        gameState?.chat?.unlocked ? " Chat" : " Locked"
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
                let showCardPreview = rendering().showCardPreview || ctx().showCardPreview;
                let placeCard = ctx().placeCard;
                let removeCardFromPosition = ctx().removeCardFromPosition;
                let ResolutionPhaseUI = CardGame.UI.ResolutionPhaseUI;

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

                            return m("div", {
                                key: pos.index,
                                class: "cg2-action-position" +
                                       (isThreatPos ? " cg2-threat-pos" : (isPlayerPos ? " cg2-player-pos" : " cg2-opponent-pos")) +
                                       (isActive ? " cg2-active" : "") +
                                       (isResolved ? " cg2-resolved" : "") +
                                       (isLocked ? " cg2-locked" : "") +
                                       (canDrop && !isLocked ? " cg2-droppable" : ""),
                                ondragover(e) {
                                    if (canDrop && !isLocked) e.preventDefault();
                                },
                                ondrop(e) {
                                    if (!canDrop || isLocked) return;
                                    e.preventDefault();
                                    let cardData = e.dataTransfer.getData("text/plain");
                                    try {
                                        let card = JSON.parse(cardData);
                                        placeCard(pos.index, card);
                                    } catch (err) {
                                        console.error("[CardGame v2] Drop error:", err);
                                    }
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
                                                        removeCardFromPosition(pos.index);
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
                                            isLocked ? [m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "lock"), " No AP"]
                                                     : (canDrop ? "Drop here" : "\u2013")),

                                // Resolution marker
                                isActive ? m("div", { class: "cg2-resolve-marker" }, "\u25B6") : null
                            ]);
                        })
                    ),

                    // Combat/Resolution overlay (inside action bar)
                    isResolution && ResolutionPhaseUI ? m(ResolutionPhaseUI) : null
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
                let showCardPreview = rendering().showCardPreview || ctx().showCardPreview;
                let placeThreatDefenseCard = ctx().placeThreatDefenseCard;

                return m("div", { class: "cg2-hand-tray" }, [
                    // Header with label and filter tabs
                    m("div", { class: "cg2-hand-header" }, [
                        m("span", { class: "cg2-hand-label" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;margin-right:4px" }, "playing_cards"),
                            "Your Hand (" + hand.length + ")"
                        ]),
                        m("div", { class: "cg2-hand-tabs" }, [
                            ["all", "action", "skill", "magic", "talk"].map(f =>
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

                                return m("div", {
                                    key: card.name + "-" + i,
                                    class: "cg2-hand-card-wrapper" + (canPlaceDefense ? " cg2-defense-eligible" : ""),
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
                                        if (showCardPreview) showCardPreview(card);
                                    },
                                    title: canPlaceDefense ? "Click to add to defense" : "Click to enlarge, drag to place"
                                }, [
                                    m(CardFace, { card, bgImage: cardFrontBg, compact: true }),
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
            set stackFlipped(v) { stackFlipped = v; }
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
