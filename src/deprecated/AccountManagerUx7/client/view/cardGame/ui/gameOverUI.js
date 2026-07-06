/**
 * CardGame UI - Game Over
 * Victory/defeat screen with stats, XP earned, campaign record, and level-up stat picker.
 *
 * Extracted from cardGame-v2.js (lines ~9457-9690).
 *
 * Depends on:
 *   - CardGame.Constants (GAME_PHASES, STAT_DESCRIPTIONS)
 *   - CardGame.ctx (gameState, viewingDeck, activeCampaign, levelUpState, screen)
 *   - CardGame.GameState (createGameState, initializeLLMComponents,
 *                         resetInitAnimState, startInitiativeAnimation, narrateGameEnd)
 *   - CardGame.Storage (gameStorage, saveCampaignProgress, campaignStorage)
 *
 * Exposes: window.CardGame.UI.GameOverUI, window.CardGame.UI.LevelUpUI
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    const LEVEL_UP_STATS = ["STR", "AGI", "END", "INT", "MAG", "CHA"];

    // ── Game Over UI ──────────────────────────────────────────────────
    function GameOverUI() {
        let narratedEnd = false;
        let campaignSaved = false;

        return {
            async oninit() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;

                // Trigger end game narration once
                if (!narratedEnd && gameState && gameState.winner) {
                    narratedEnd = true;
                    CardGame.GameState.narrateGameEnd(gameState.winner);
                }

                // Save campaign W/L record once
                if (!campaignSaved && gameState && gameState.winner) {
                    campaignSaved = true;
                    let isVictory = gameState.winner === "player";
                    ctx.activeCampaign = await CardGame.Storage.saveCampaignProgress(gameState, isVictory);

                    // v3: Level-up removed — stat changes come from card effects only

                    // Delete game saves on game end
                    console.log("[CardGame v2] Game over: deleting saves for", gameState.deckName);
                    CardGame.Storage.gameStorage.deleteAll(gameState.deckName);
                    m.redraw();
                }
            },
            view() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;
                let activeCampaign = ctx.activeCampaign;

                let isVictory = gameState.winner === "player";
                let player = gameState.player;
                let opponent = gameState.opponent;
                let rounds = gameState.round;

                // Overlay on top of current game view
                return m("div", { class: "cg2-game-over-overlay" }, [
                    m("div", { class: "cg2-game-over-panel" }, [
                        m("div", {
                            class: "cg2-game-over-title " + (isVictory ? "cg2-victory" : "cg2-defeat")
                        }, isVictory ? "Victory!" : "Defeat"),

                        m("div", { class: "cg2-game-over-subtitle" },
                            isVictory
                                ? opponent.character.name + " has been defeated!"
                                : "You have been defeated by " + opponent.character.name),

                        m("div", { class: "cg2-game-over-stats" }, [
                            m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value" }, rounds),
                                m("div", { class: "cg2-game-over-stat-label" }, "Rounds")
                            ]),
                            m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value" }, player.hp + "/" + player.maxHp),
                                m("div", { class: "cg2-game-over-stat-label" }, "Your HP")
                            ]),
                            m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value" }, opponent.hp + "/" + opponent.maxHp),
                                m("div", { class: "cg2-game-over-stat-label" }, "Opponent HP")
                            ]),
                        ]),

                        // Campaign W/L record
                        activeCampaign ? m("div", { class: "cg2-game-over-campaign" }, [
                            m("div", { class: "cg2-campaign-record-line" },
                                activeCampaign.wins + "W / " + activeCampaign.losses + "L (" + activeCampaign.totalGamesPlayed + " games)")
                        ]) : null,

                        m("div", { class: "cg2-game-over-actions" }, [
                            m("button", {
                                class: "cg2-btn cg2-btn-primary",
                                async onclick() {
                                    // Stop all voice/audio immediately
                                    if (CardGame.GameState?.cleanupAudio) CardGame.GameState.cleanupAudio();
                                    let viewingDeck = ctx.viewingDeck;
                                    // Restart with same characters
                                    let playerChar = player.character;
                                    ctx.gameState = await CardGame.GameState.createGameState(viewingDeck, playerChar);
                                    if (ctx.gameState) {
                                        CardGame.GameState.initializeLLMComponents(ctx.gameState, viewingDeck);
                                        CardGame.GameState.resetInitAnimState();
                                        CardGame.GameState.startInitiativeAnimation();
                                    }
                                    m.redraw();
                                }
                            }, "Play Again"),
                            m("button", {
                                class: "cg2-btn",
                                onclick() {
                                    // Stop all voice/audio before leaving game
                                    if (CardGame.GameState?.cleanupAudio) CardGame.GameState.cleanupAudio();
                                    ctx.gameState = null;
                                    ctx.activeCampaign = null;
                                    ctx.levelUpState = null;
                                    // Restore deck view image state from viewingDeck
                                    let viewingDeck = ctx.viewingDeck;
                                    if (viewingDeck) {
                                        ctx.backgroundImageId = viewingDeck.backgroundImageId || null;
                                        ctx.backgroundPrompt = viewingDeck.backgroundPrompt || null;
                                        ctx.backgroundThumbUrl = viewingDeck.backgroundThumbUrl || null;
                                        ctx.tabletopImageId = viewingDeck.tabletopImageId || null;
                                        ctx.tabletopThumbUrl = viewingDeck.tabletopThumbUrl || null;
                                    }
                                    ctx.screen = "deckView";
                                    m.redraw();
                                }
                            }, "Back to Deck")
                        ])
                    ])
                ]);
            }
        };
    }

    // ── Level Up UI ──────────────────────────────────────────────────
    function LevelUpUI() {
        let STAT_DESCRIPTIONS = window.CardGame.Constants?.STAT_DESCRIPTIONS || {};

        return {
            view() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;
                let levelUpState = ctx.levelUpState;

                if (!levelUpState) return null;
                let campaign = levelUpState.campaign;
                let statsSelected = levelUpState.statsSelected;
                let remaining = levelUpState.remaining;
                let playerStats = gameState?.player?.character?.stats || {};

                return m("div", { class: "cg2-levelup-overlay" }, [
                    m("div", { class: "cg2-levelup-panel" }, [
                        m("h2", { class: "cg2-levelup-title" }, "Level Up!"),
                        m("p", { class: "cg2-levelup-subtitle" },
                            "Level " + campaign.level + " reached! Choose " + remaining + " stat" + (remaining > 1 ? "s" : "") + " to increase."),

                        m("div", { class: "cg2-levelup-stats" },
                            LEVEL_UP_STATS.map(function(stat) {
                                let currentVal = playerStats[stat] || 0;
                                let gained = (campaign.statGains?.[stat] || 0);
                                let selectedCount = statsSelected.filter(function(s) { return s === stat; }).length;
                                let isSelected = selectedCount > 0;

                                return m("div", {
                                    class: "cg2-stat-pick" + (isSelected ? " selected" : ""),
                                    onclick: function() {
                                        if (remaining > 0) {
                                            statsSelected.push(stat);
                                            levelUpState.remaining--;
                                        } else if (isSelected) {
                                            let idx = statsSelected.lastIndexOf(stat);
                                            if (idx >= 0) {
                                                statsSelected.splice(idx, 1);
                                                levelUpState.remaining++;
                                            }
                                        }
                                        m.redraw();
                                    }
                                }, [
                                    m("div", { class: "cg2-stat-pick-name" }, stat),
                                    m("div", { class: "cg2-stat-pick-value" }, currentVal + (selectedCount > 0 ? " +" + selectedCount : "")),
                                    m("div", { class: "cg2-stat-pick-desc" }, STAT_DESCRIPTIONS[stat] || stat),
                                    gained > 0 ? m("div", { class: "cg2-stat-pick-gained" }, "Campaign: +" + gained) : null
                                ]);
                            })
                        ),

                        m("div", { class: "cg2-levelup-actions" }, [
                            m("button", {
                                class: "cg2-btn cg2-btn-primary",
                                disabled: remaining > 0,
                                async onclick() {
                                    // Apply stat gains
                                    for (let stat of statsSelected) {
                                        campaign.statGains[stat] = (campaign.statGains[stat] || 0) + 1;
                                    }
                                    campaign.pendingLevelUps = Math.max(0, (campaign.pendingLevelUps || 1) - 1);

                                    // Save updated campaign
                                    let gs = ctx.gameState;
                                    await CardGame.Storage.campaignStorage.save(gs ? gs.deckName : "", campaign);
                                    ctx.activeCampaign = campaign;

                                    // Check if more level-ups pending
                                    if (campaign.pendingLevelUps > 0) {
                                        ctx.levelUpState = {
                                            campaign: campaign,
                                            statsSelected: [],
                                            remaining: 2
                                        };
                                    } else {
                                        ctx.levelUpState = null;
                                    }
                                    m.redraw();
                                }
                            }, remaining > 0 ? "Select " + remaining + " more" : "Confirm"),
                            m("button", {
                                class: "cg2-btn",
                                onclick: function() {
                                    levelUpState.statsSelected = [];
                                    levelUpState.remaining = 2;
                                    m.redraw();
                                }
                            }, "Reset")
                        ])
                    ])
                ]);
            }
        };
    }

    window.CardGame.UI.GameOverUI = GameOverUI;
    window.CardGame.UI.LevelUpUI = LevelUpUI;

})();
