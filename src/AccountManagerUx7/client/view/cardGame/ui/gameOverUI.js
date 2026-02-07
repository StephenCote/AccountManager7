/**
 * CardGame UI - Game Over and Level Up
 * Victory/defeat screen with stats, campaign progress, level-up button.
 * Level-up UI shows 6 stats with descriptions, click to select 2, confirm button.
 *
 * Extracted from cardGame-v2.js (lines ~9457-9690).
 *
 * Depends on:
 *   - CardGame.Constants (GAME_PHASES)
 *   - CardGame.ctx (gameState, viewingDeck, activeCampaign, levelUpState, screen)
 *   - CardGame.Actions (createGameState, applyCampaignBonuses, initializeLLMComponents,
 *                       resetInitAnimState, startInitiativeAnimation, narrateGameEnd,
 *                       calculateXP, saveCampaignProgress)
 *   - CardGame.Storage (gameStorage, campaignStorage)
 *
 * Exposes: window.CardGame.UI.GameOverUI, window.CardGame.UI.LevelUpUI
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    // ── Level Up Constants ────────────────────────────────────────────
    const LEVEL_UP_STATS = ["STR", "AGI", "END", "INT", "MAG", "CHA"];
    const STAT_DESCRIPTIONS = {
        STR: "Strength \u2014 melee damage",
        AGI: "Agility \u2014 initiative rolls",
        END: "Endurance \u2014 action points",
        INT: "Intelligence \u2014 skill effects",
        MAG: "Magic \u2014 energy pool & spells",
        CHA: "Charisma \u2014 talk & morale"
    };

    // ── Game Over UI ──────────────────────────────────────────────────
    function GameOverUI() {
        let narratedEnd = false;
        let campaignSaved = false;

        return {
            async oninit() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;
                let activeCampaign = ctx.activeCampaign;

                // Trigger end game narration once
                if (!narratedEnd && gameState && gameState.winner) {
                    narratedEnd = true;
                    CardGame.Actions.narrateGameEnd(gameState.winner);
                }
                // Save campaign progress once
                if (!campaignSaved && gameState && gameState.winner) {
                    campaignSaved = true;
                    let isVictory = gameState.winner === "player";
                    let xpGain = CardGame.Actions.calculateXP(gameState, isVictory);
                    gameState.xpGained = xpGain;
                    ctx.activeCampaign = await CardGame.Actions.saveCampaignProgress(gameState, isVictory, xpGain);
                    // Delete game saves on game end
                    CardGame.Storage.gameStorage.deleteAll(gameState.deckName);
                    m.redraw();
                }
            },
            view() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;
                let activeCampaign = ctx.activeCampaign;
                let levelUpState = ctx.levelUpState;

                // Show level-up dialog if pending
                if (levelUpState) {
                    return m(LevelUpUI);
                }

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
                            gameState.xpGained ? m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value cg2-xp-gain" }, "+" + gameState.xpGained),
                                m("div", { class: "cg2-game-over-stat-label" }, "XP Gained")
                            ]) : null
                        ]),

                        // Campaign progress bar
                        activeCampaign ? m("div", { class: "cg2-game-over-campaign" }, [
                            m("div", { class: "cg2-campaign-progress" }, [
                                m("span", "Level " + activeCampaign.level),
                                m("span", { class: "cg2-campaign-xp-bar" }, [
                                    m("span", { class: "cg2-campaign-xp-fill", style: { width: (activeCampaign.xp % 100) + "%" } }),
                                ]),
                                m("span", activeCampaign.xp + " XP"),
                            ]),
                            m("div", { class: "cg2-campaign-record-line" },
                                activeCampaign.wins + "W / " + activeCampaign.losses + "L (" + activeCampaign.totalGamesPlayed + " games)")
                        ]) : null,

                        m("div", { class: "cg2-game-over-actions" }, [
                            // Level up button (if pending)
                            activeCampaign?.pendingLevelUps > 0 ? m("button", {
                                class: "cg2-btn cg2-btn-accent",
                                onclick() {
                                    ctx.levelUpState = {
                                        campaign: activeCampaign,
                                        statsSelected: [],
                                        remaining: 2
                                    };
                                    m.redraw();
                                }
                            }, [
                                m("span", { class: "material-symbols-outlined" }, "upgrade"),
                                " Level Up! (+" + (activeCampaign.pendingLevelUps * 2) + " stats)"
                            ]) : null,
                            m("button", {
                                class: "cg2-btn cg2-btn-primary",
                                async onclick() {
                                    let viewingDeck = ctx.viewingDeck;
                                    // Restart with same characters
                                    let playerChar = player.character;
                                    ctx.gameState = CardGame.Actions.createGameState(viewingDeck, playerChar);
                                    if (ctx.gameState) {
                                        if (ctx.activeCampaign) CardGame.Actions.applyCampaignBonuses(ctx.gameState, ctx.activeCampaign);
                                        CardGame.Actions.initializeLLMComponents(ctx.gameState, viewingDeck);
                                        CardGame.Actions.resetInitAnimState();
                                        CardGame.Actions.startInitiativeAnimation();
                                    }
                                    m.redraw();
                                }
                            }, "Play Again"),
                            m("button", {
                                class: "cg2-btn",
                                onclick() {
                                    ctx.gameState = null;
                                    ctx.activeCampaign = null;
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

    // ── Level Up UI Component ───────────────────────────────────────────
    function LevelUpUI() {
        return {
            view() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;
                let levelUpState = ctx.levelUpState;
                let activeCampaign = ctx.activeCampaign;

                if (!levelUpState) return null;
                let { campaign, statsSelected, remaining } = levelUpState;
                let playerStats = gameState?.player?.character?.stats || {};

                return m("div", { class: "cg2-levelup-overlay" }, [
                    m("div", { class: "cg2-levelup-panel" }, [
                        m("h2", { class: "cg2-levelup-title" }, "Level Up!"),
                        m("p", { class: "cg2-levelup-subtitle" },
                            "Level " + campaign.level + " reached! Choose " + remaining + " stat" + (remaining > 1 ? "s" : "") + " to increase."),

                        m("div", { class: "cg2-levelup-stats" },
                            LEVEL_UP_STATS.map(stat => {
                                let currentVal = playerStats[stat] || 0;
                                let gained = (campaign.statGains?.[stat] || 0);
                                let selectedCount = statsSelected.filter(s => s === stat).length;
                                let isSelected = selectedCount > 0;

                                return m("div", {
                                    class: "cg2-stat-pick" + (isSelected ? " selected" : ""),
                                    onclick() {
                                        if (remaining > 0) {
                                            statsSelected.push(stat);
                                            levelUpState.remaining--;
                                        } else if (isSelected) {
                                            // Deselect last instance
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
                                    m("div", { class: "cg2-stat-pick-desc" }, STAT_DESCRIPTIONS[stat]),
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
                                    let gs = (window.CardGame.ctx || {}).gameState;
                                    await CardGame.Storage.campaignStorage.save(gs ? gs.deckName : "", campaign);
                                    ctx.activeCampaign = campaign;

                                    // Check if more level-ups pending
                                    if (campaign.pendingLevelUps > 0) {
                                        ctx.levelUpState = {
                                            campaign,
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
                                onclick() {
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
