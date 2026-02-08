/**
 * CardGame UI - Threat Response Phase
 * Shows threat card vs defender card, threat stats (ATK, DEF, HP, Loot),
 * defense stack, AI auto-response after delay, and player defense card placement.
 *
 * Extracted from cardGame-v2.js (lines ~8627-8790).
 *
 * Depends on:
 *   - CardGame.Constants (GAME_PHASES)
 *   - CardGame.ctx (gameState, viewingDeck)
 *   - CardGame.Actions (placeThreatDefenseCard, skipThreatResponse,
 *                       resolveThreatCombat, resolveEndThreatCombat)
 *
 * Exposes: window.CardGame.UI.ThreatResponseUI
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    // ── Threat Response Phase UI ─────────────────────────────────────
    let threatAutoRespondScheduled = false;  // Guard against multiple auto-responds

    function ThreatResponseUI() {
        return {
            oninit() {
                // Reset auto-respond guard when component initializes
                threatAutoRespondScheduled = false;
            },
            view() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;
                let GAME_PHASES = CardGame.Constants.GAME_PHASES;

                if (!gameState || !gameState.threatResponse || !gameState.threatResponse.active) {
                    return null;
                }

                let tr = gameState.threatResponse;
                let isEndThreat = tr.type === "end" || gameState.phase === GAME_PHASES.END_THREAT;
                let responder = tr.responder;
                let isPlayerResponder = responder === "player";
                let actor = isPlayerResponder ? gameState.player : gameState.opponent;
                let apRemaining = actor.threatResponseAP || 0;
                let threats = tr.threats || [];
                let defenseStack = tr.defenseStack || [];

                // For AI opponent, auto-respond after delay (only schedule once)
                if (!isPlayerResponder && !threatAutoRespondScheduled) {
                    threatAutoRespondScheduled = true;
                    console.log("[CardGame v2] Scheduling AI threat response");
                    setTimeout(() => {
                        let gs = (window.CardGame.ctx || {}).gameState;
                        if (gs && gs.threatResponse && gs.threatResponse.active) {
                            // AI places a defensive card if available
                            let aiActor = isPlayerResponder ? gs.player : gs.opponent;
                            let defenseCards = aiActor.hand.filter(c =>
                                c.type === "action" && (c.name === "Block" || c.name === "Dodge") ||
                                c.type === "item" && c.subtype === "armor"
                            );
                            if (defenseCards.length > 0 && aiActor.threatResponseAP > 0) {
                                CardGame.GameState.placeThreatDefenseCard(defenseCards[0]);
                                m.redraw();
                            } else {
                                CardGame.GameState.skipThreatResponse();
                            }
                        }
                        threatAutoRespondScheduled = false;  // Reset for next threat
                    }, 1500);  // Give player time to see threat
                }

                // Get the threat and defender info
                let threat = threats[0];
                let defenderActor = threat?.target === "player" ? gameState.player : gameState.opponent;
                let defenderName = threat?.target === "player" ? "You" : "Opponent";

                return m("div", { class: "cg2-phase-panel cg2-threat-response-panel" }, [
                    m("h2", isEndThreat ? "End-of-Round Threat!" : "Threat Incoming!"),
                    m("p", { class: "cg2-threat-explain" },
                        isEndThreat
                            ? (isPlayerResponder
                                ? "A threat emerges targeting you! Prepare to defend yourself!"
                                : "A threat emerges targeting the opponent! They must defend...")
                            : (isPlayerResponder
                                ? "Your fumble attracted danger! Prepare to defend yourself!"
                                : "Opponent's fumble attracted danger! They must defend...")
                    ),

                    // Two-card layout like initiative phase
                    m("div", { class: "cg2-init-cards cg2-threat-encounter" }, [
                        // Threat card (left) — full card-like display
                        threat ? m("div", { class: "cg2-init-card-wrap cg2-threat-card-wrap" }, [
                            m("div", { class: "cg2-threat-encounter-card cg2-threat-type-" + (threat.creatureType || "monster") }, [
                                // Creature type badge
                                m("div", { class: "cg2-threat-type-badge" },
                                    (threat.creatureType || "monster").toUpperCase()),
                                // Icon/portrait area
                                m("div", { class: "cg2-threat-encounter-icon" }, [
                                    m("span", { class: "material-symbols-outlined" }, threat.imageIcon || "pets")
                                ]),
                                m("div", { class: "cg2-threat-encounter-name" }, threat.name),
                                // Stats row
                                m("div", { class: "cg2-threat-encounter-stats" }, [
                                    m("div", { class: "cg2-threat-stat-row" }, [
                                        m("span", { class: "material-symbols-outlined" }, "swords"),
                                        m("span", "ATK " + threat.atk)
                                    ]),
                                    m("div", { class: "cg2-threat-stat-row" }, [
                                        m("span", { class: "material-symbols-outlined" }, "shield"),
                                        m("span", "DEF " + threat.def)
                                    ]),
                                    m("div", { class: "cg2-threat-stat-row" }, [
                                        m("span", { class: "material-symbols-outlined" }, "favorite"),
                                        m("span", "HP " + threat.hp + "/" + (threat.maxHp || threat.hp))
                                    ])
                                ]),
                                // Behavior text
                                threat.behavior ? m("div", { class: "cg2-threat-behavior" }, [
                                    m("span", { class: "material-symbols-outlined", style: "font-size:12px" }, "psychology"),
                                    " ", threat.behavior
                                ]) : null,
                                // Action stack display
                                threat.actionStack ? m("div", { class: "cg2-threat-action-stack" }, [
                                    m("span", { class: "cg2-threat-action-label" }, "Action: "),
                                    m("span", { class: "cg2-threat-action-name" }, threat.actionStack.coreAction),
                                    threat.actionStack.modifiers.length > 0
                                        ? m("span", { class: "cg2-threat-action-mods" },
                                            " +" + threat.actionStack.modifiers.map(mod => mod.name).join(", "))
                                        : null
                                ]) : null,
                                // Loot preview
                                m("div", { class: "cg2-threat-encounter-loot" }, [
                                    m("span", { class: "material-symbols-outlined" }, "inventory_2"),
                                    threat.lootItems && threat.lootItems.length > 0
                                        ? " " + threat.lootItems.map(l => l.name).join(", ")
                                        : " " + (threat.lootRarity || "COMMON") + " Loot"
                                ])
                            ])
                        ]) : null,

                        // VS indicator
                        m("div", { class: "cg2-init-vs cg2-threat-vs" }, [
                            m("span", { class: "material-symbols-outlined" }, "swords"),
                            m("div", "VS")
                        ]),

                        // Defender card (right)
                        m("div", { class: "cg2-init-card-wrap cg2-defender-card-wrap" }, [
                            m("div", { class: "cg2-defender-card" + (isPlayerResponder ? " cg2-defender-you" : "") }, [
                                m("div", { class: "cg2-defender-label" }, defenderName),
                                m("div", { class: "cg2-defender-stats" }, [
                                    m("div", { class: "cg2-defender-stat" }, [
                                        m("span", { class: "material-symbols-outlined" }, "favorite"),
                                        " HP: ", defenderActor?.hp || 0
                                    ]),
                                    m("div", { class: "cg2-defender-stat" }, [
                                        m("span", { class: "material-symbols-outlined" }, "shield"),
                                        " END: ", defenderActor?.character?.stats?.END || 0
                                    ])
                                ]),

                                // Defense stack display
                                m("div", { class: "cg2-defender-stack-area" }, [
                                    m("div", { class: "cg2-defender-stack-label" }, "Defense Stack"),
                                    defenseStack.length > 0
                                        ? m("div", { class: "cg2-defender-stack-cards" },
                                            defenseStack.map(card =>
                                                m("div", { class: "cg2-defender-stack-card" }, card.name)
                                            )
                                        )
                                        : m("div", { class: "cg2-defender-stack-empty" }, "Empty - click cards below")
                                ]),

                                // AP indicator
                                isPlayerResponder ? m("div", { class: "cg2-defender-ap" }, [
                                    m("span", { class: "material-symbols-outlined" }, "bolt"),
                                    " ", apRemaining, " / ", tr.bonusAP, " AP"
                                ]) : null
                            ])
                        ])
                    ]),

                    // Action buttons
                    isPlayerResponder ? m("div", { class: "cg2-threat-actions" }, [
                        apRemaining > 0
                            ? m("button", {
                                class: "cg2-btn cg2-btn-secondary",
                                onclick: function() { CardGame.GameState.skipThreatResponse(); }
                            }, "Skip Defense")
                            : null,
                        m("button", {
                            class: "cg2-btn cg2-btn-primary cg2-btn-threat",
                            onclick: function() {
                                let gs = (window.CardGame.ctx || {}).gameState;
                                let GP = CardGame.Constants.GAME_PHASES;
                                console.log("[CardGame v2] Face Threat clicked, phase:", gs ? gs.phase : "null");
                                if (gs && gs.phase === GP.THREAT_RESPONSE) {
                                    CardGame.GameState.resolveThreatCombat();
                                } else if (gs && gs.phase === GP.END_THREAT) {
                                    CardGame.GameState.resolveEndThreatCombat();
                                } else {
                                    console.warn("[CardGame v2] Face Threat: unexpected phase", gs ? gs.phase : "null");
                                    // Try to resolve anyway
                                    if (gs && gs.threatResponse && gs.threatResponse.active) {
                                        CardGame.GameState.resolveThreatCombat();
                                    }
                                }
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: "vertical-align: middle; margin-right: 4px" }, "shield"),
                            apRemaining > 0 ? "Face Threat Now" : "Resolve Combat"
                        ])
                    ]) : m("div", { class: "cg2-threat-waiting" }, "Opponent is preparing defense...")
                ]);
            }
        };
    }

    window.CardGame.UI.ThreatResponseUI = ThreatResponseUI;

})();
