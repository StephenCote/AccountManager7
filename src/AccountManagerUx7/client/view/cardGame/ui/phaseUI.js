// phaseUI.js — Phase UI components for card game
// Initiative, Equip, Resolution, Cleanup phase displays
(function() {
    "use strict";

    const NS = window.CardGame = window.CardGame || {};
    NS.UI = NS.UI || {};

    // ── Shorthand for module functions vs state ──
    function GS() { return NS.GameState || {}; }

    // ── Initiative Phase UI ───────────────────────────────────────────
    function InitiativePhaseUI() {
        let playerFlipped = false;
        let opponentFlipped = false;

        return {
            oninit() {
                let gs = GS().state;
                let initAnimState = gs.initAnimState;
                if (!initAnimState.rolling && !initAnimState.rollComplete && initAnimState.countdown === 3) {
                    // Wait for narration to be ready before starting initiative animation
                    let gameState = gs.gameState;
                    if (gameState && !gameState.narrationReady) {
                        // Poll until narration is displayed, with 8s safety timeout
                        let pollStart = Date.now();
                        let pollInterval = setInterval(() => {
                            let currentGs = GS().state?.gameState;
                            let timedOut = Date.now() - pollStart > 8000;
                            if (!currentGs || currentGs.narrationReady || timedOut) {
                                clearInterval(pollInterval);
                                if (timedOut) console.warn("[CardGame v2] Narration ready timeout — starting initiative anyway");
                                // Give narration subtitle time to be read before initiative starts
                                setTimeout(() => {
                                    GS().startInitiativeAnimation();
                                }, timedOut ? 500 : 2000);
                            }
                        }, 100);
                    } else {
                        // Narration already ready (e.g. round 2+) — start immediately
                        GS().startInitiativeAnimation();
                    }
                }
            },
            view() {
                let gs = GS().state;
                let gameState = gs.gameState;
                if (!gameState) return null;

                let GAME_PHASES = NS.Constants.GAME_PHASES;
                let CardFace = NS.Rendering.CardFace;
                let D20Dice = NS.Rendering.D20Dice;
                let ctx = NS.ctx || {};

                let init = gameState.initiative;
                let anim = gs.initAnimState;
                let player = gameState.player;
                let opponent = gameState.opponent;

                let showFlipped = anim.rolling || anim.rollComplete;

                let viewingDeck = ctx.viewingDeck;
                let cardFrontBg = viewingDeck && viewingDeck.cardFrontImageUrl ? viewingDeck.cardFrontImageUrl : null;
                let cardBackBg = viewingDeck && viewingDeck.cardBackImageUrl ? viewingDeck.cardBackImageUrl : null;

                function renderInitCard(who, character, roll, isWinner, diceFace, flipped) {
                    let isFlipped = showFlipped || flipped;
                    let isLoser = anim.rollComplete && !isWinner && init.winner;

                    let backStyle = {};
                    if (cardBackBg) {
                        backStyle.backgroundImage = "url('" + cardBackBg + "')";
                        backStyle.backgroundSize = "cover";
                        backStyle.backgroundPosition = "center";
                    }

                    // Short display name for back label (truncate long names)
                    let charName = character.name || (who === "player" ? "You" : "Opponent");
                    if (charName.length > 16) charName = charName.substring(0, 14) + "\u2026";

                    return m("div", {
                        class: "cg2-init-card-wrap" + (isFlipped ? " cg2-init-flipped" : "") +
                            (isWinner ? " cg2-init-winner-card" : "") +
                            (isLoser ? " cg2-init-loser-card" : ""),
                        onclick() {
                            if (anim.rollComplete) {
                                if (who === "player") playerFlipped = !playerFlipped;
                                else opponentFlipped = !opponentFlipped;
                            }
                        }
                    }, [
                        m("div", { class: "cg2-init-card-inner" }, [
                            m("div", { class: "cg2-init-card-front" }, [
                                m(CardFace, { card: character, bgImage: cardFrontBg, compact: true })
                            ]),
                            m("div", { class: "cg2-init-card-back", style: backStyle }, [
                                m("div", { class: "cg2-init-back-content" }, [
                                    m("div", { class: "cg2-init-back-label" }, charName),
                                    m("div", { class: "cg2-init-back-center" }, [
                                        m("div", { class: "cg2-dice-container" }, [
                                            m(D20Dice, {
                                                value: anim.rolling ? diceFace : (roll ? roll.raw : "?"),
                                                rolling: anim.rolling,
                                                winner: isWinner && anim.rollComplete
                                            })
                                        ])
                                    ]),
                                    m("div", { class: "cg2-init-back-footer" }, [
                                        roll && anim.rollComplete ? m("div", { class: "cg2-roll-breakdown" }, [
                                            m("span", roll.raw),
                                            m("span", " + "),
                                            m("span", { class: "cg2-mod" }, roll.modifier),
                                            m("span", " AGI = "),
                                            m("strong", roll.total)
                                        ]) : null,
                                        anim.rollComplete && init.winner
                                            ? (isWinner
                                                ? m("div", { class: "cg2-init-winner-badge" }, [
                                                    m("span", { class: "material-symbols-outlined" }, "star"),
                                                    " Goes First!"
                                                ])
                                                : m("div", { class: "cg2-init-loser-badge" }, [
                                                    m("span", { class: "material-symbols-outlined", style: "font-size:12px" }, "schedule"),
                                                    " Goes Second"
                                                ]))
                                            : null
                                    ])
                            ])
                        ])
                    ])]);
                }

                // Countdown phase
                if (anim.countdown > 0) {
                    return m("div", { class: "cg2-phase-panel cg2-initiative-panel" }, [
                        m("h2", "Initiative Phase"),
                        m("p", "Preparing to roll for turn order..."),
                        m("div", { class: "cg2-init-cards" }, [
                            renderInitCard("player", player.character, null, false, 1, false),
                            m("div", { class: "cg2-init-vs" }, "VS"),
                            renderInitCard("opponent", opponent.character, null, false, 1, false),
                            m("div", { class: "cg2-init-countdown" }, [
                                m("div", { class: "cg2-countdown-number" }, anim.countdown)
                            ])
                        ])
                    ]);
                }

                // Rolling or results
                return m("div", { class: "cg2-phase-panel cg2-initiative-panel" }, [
                    m("h2", "Initiative Phase"),
                    m("div", { class: "cg2-init-cards" }, [
                        renderInitCard("player", player.character, init.playerRoll, init.winner === "player", anim.playerDiceFace, playerFlipped),
                        m("div", { class: "cg2-init-vs" }, "VS"),
                        renderInitCard("opponent", opponent.character, init.opponentRoll, init.winner === "opponent", anim.opponentDiceFace, opponentFlipped)
                    ]),

                    // Nat 1 warning
                    anim.rollComplete && gameState.beginningThreats && gameState.beginningThreats.length > 0
                        ? m("div", { class: "cg2-threat-warning" }, [
                            m("div", { class: "cg2-threat-warning-header" }, [
                                m("span", { class: "material-symbols-outlined" }, "warning"),
                                " CRITICAL FAILURE - THREAT TRIGGERED!"
                            ]),
                            m("div", { class: "cg2-threat-rolls", style: { fontSize: "11px", color: "#c62828", marginBottom: "8px" } }, [
                                "Rolls: You (", init.playerRoll ? init.playerRoll.raw : "?", ") vs Opp (",
                                init.opponentRoll ? init.opponentRoll.raw : "?", ")"
                            ]),
                            gameState.beginningThreats.map((threat, i) =>
                                m("div", { class: "cg2-threat-warning-item" }, [
                                    m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle" }, threat.imageIcon || "pets"),
                                    " ", m("strong", threat.name),
                                    " (Diff ", threat.difficulty, ") attacks ",
                                    m("span", { class: threat.target === "player" ? "cg2-threat-target-you" : "cg2-threat-target-opp" },
                                        threat.target === "player" ? "YOU" : "Opponent"),
                                    " (rolled Nat 1)"
                                ])
                            )
                        ])
                        : null
                ]);
            }
        };
    }

    // ── Equip Phase UI ────────────────────────────────────────────────
    function EquipPhaseUI() {
        return {
            view() {
                let gs = GS().state;
                let gameState = gs.gameState;
                if (!gameState) return null;

                let player = gameState.player;
                let equipped = player.equipped;

                return m("div", { class: "cg2-phase-panel cg2-equip-panel" }, [
                    m("h2", "Equip Phase"),
                    m("p", "Adjust your equipment before combat. (Free action)"),
                    m("div", { class: "cg2-equip-slots" }, [
                        m(EquipSlot, { slot: "head", label: "Head", item: equipped.head }),
                        m(EquipSlot, { slot: "body", label: "Body", item: equipped.body }),
                        m(EquipSlot, { slot: "handL", label: "Left Hand", item: equipped.handL }),
                        m(EquipSlot, { slot: "handR", label: "Right Hand", item: equipped.handR }),
                        m(EquipSlot, { slot: "feet", label: "Feet", item: equipped.feet }),
                        m(EquipSlot, { slot: "ring", label: "Ring", item: equipped.ring }),
                        m(EquipSlot, { slot: "back", label: "Back", item: equipped.back })
                    ]),
                    m("button", {
                        class: "cg2-btn cg2-btn-primary",
                        style: { marginTop: "16px" },
                        onclick() { GS().advancePhase(); }
                    }, "Continue to Placement Phase")
                ]);
            }
        };
    }

    function EquipSlot() {
        return {
            view(vnode) {
                let { slot, label, item } = vnode.attrs;
                return m("div", { class: "cg2-equip-slot" + (item ? " cg2-slot-filled" : "") }, [
                    m("span", { class: "cg2-slot-label" }, label),
                    item
                        ? m("span", { class: "cg2-slot-item" }, item.name)
                        : m("span", { class: "cg2-slot-empty" }, "Empty")
                ]);
            }
        };
    }

    // ── Resolution Phase UI (DEPRECATED — now rendered inline in ActionBar) ──
    function ResolutionPhaseUI() {
        return {
            view() {
                let gs = GS().state;
                let gameState = gs.gameState;
                if (!gameState) return null;

                let D20Dice = NS.Rendering.D20Dice;
                let totals = gs.resolutionTotals || {};

                let bar = gameState.actionBar;
                let currentPos = bar.positions[bar.resolveIndex];
                let card = currentPos?.stack?.coreCard;
                let isAttack = (card && card.name === "Attack") || currentPos?.isThreat;
                let isThreat = currentPos?.isThreat;
                let isRolling = gs.resolutionPhase === "rolling";
                let showResult = gs.resolutionPhase === "result" || gs.resolutionPhase === "done";
                let combat = gs.currentCombatResult;

                // Generate descriptive action label
                let actionLabel = "";
                if (currentPos) {
                    let ownerName = currentPos.owner === "player" ? "Player" : "Opponent";
                    if (isThreat) {
                        actionLabel = (currentPos.threat?.name || "Threat") + " Attacks!";
                    } else if (card) {
                        let actionVerb = card.name === "Attack" ? "Attacks" :
                                        card.name === "Rest" ? "Rests" :
                                        card.name === "Flee" ? "Flees" :
                                        card.name === "Guard" ? "Guards" :
                                        card.name === "Investigate" ? "Investigates" :
                                        card.name === "Craft" ? "Crafts" :
                                        card.name === "Trade" ? "Trades" :
                                        card.type === "talk" ? "Talks" :
                                        card.type === "magic" ? "Casts " + card.name :
                                        "Acts";
                        actionLabel = ownerName + " " + actionVerb + "!";
                    }
                }

                if (!currentPos) {
                    return m("div", { class: "cg2-resolution-complete" }, "All actions resolved!");
                }

                // Step progress dots
                let progressDots = m("div", { class: "cg2-step-progress" },
                    bar.positions.map(function(pos, i) {
                        let state = pos.resolved ? "done" : (i === bar.resolveIndex ? "active" : "pending");
                        let isPlayerPos = pos.owner === "player";
                        return m("span", {
                            key: i,
                            class: "cg2-step-dot cg2-step-" + state + (isPlayerPos ? " cg2-dot-player" : " cg2-dot-opponent"),
                            title: "Step " + (i + 1) + " - " + (pos.stack?.coreCard?.name || (pos.isThreat ? "Threat" : "?"))
                        });
                    })
                );

                // Running totals bar
                let totalsBar = m("div", { class: "cg2-resolution-totals" }, [
                    m("span", { class: "cg2-totals-player" }, [
                        m("strong", "You: "),
                        m("span", { class: "cg2-totals-dealt" }, totals.playerDamageDealt || 0),
                        " dealt, ",
                        m("span", { class: "cg2-totals-taken" }, totals.playerDamageTaken || 0),
                        " taken"
                    ]),
                    m("span", { class: "cg2-totals-divider" }, "|"),
                    m("span", { class: "cg2-totals-opponent" }, [
                        m("strong", "Opp: "),
                        m("span", { class: "cg2-totals-dealt" }, totals.opponentDamageDealt || 0),
                        " dealt, ",
                        m("span", { class: "cg2-totals-taken" }, totals.opponentDamageTaken || 0),
                        " taken"
                    ])
                ]);

                // Unified resolution panel
                return m("div", { class: "cg2-resolution-panel" }, [
                    // Header: step counter + action label + progress dots
                    m("div", { class: "cg2-resolution-header" }, [
                        m("div", { class: "cg2-resolution-header-top" }, [
                            m("span", { class: "cg2-step-number" }, "Step " + (bar.resolveIndex + 1) + "/" + bar.positions.length),
                            m("span", { class: "cg2-step-action" }, actionLabel)
                        ]),
                        progressDots
                    ]),

                    // Body: combat or non-combat content
                    isAttack && (isRolling || showResult)
                        ? m("div", { class: "cg2-resolution-body" }, [
                            // Horizontal layout: dice left, outcome right
                            m("div", { class: "cg2-resolution-combat-row" }, [
                                // Dice section
                                m("div", { class: "cg2-resolution-dice" }, [
                                    m("div", { class: "cg2-combat-header" }, [
                                        m("span", { class: "cg2-combatant cg2-attacker" },
                                            combat ? combat.attackerName : (isThreat ? (currentPos.threat?.name || "Threat") : (currentPos.owner === "player" ? "You" : "Opponent"))),
                                        m("span", { class: "cg2-combat-vs" }, "vs"),
                                        m("span", { class: "cg2-combatant cg2-defender" },
                                            combat ? combat.defenderName : (isThreat ? (currentPos.target === "player" ? "You" : "Opponent") : (currentPos.owner === "player" ? "Opponent" : "You")))
                                    ]),
                                    m("div", { class: "cg2-combat-dice-row" }, [
                                        m("div", { class: "cg2-combat-roll-card" }, [
                                            m("div", { class: "cg2-roll-label" }, "ATK"),
                                            m(D20Dice, {
                                                value: isRolling ? gs.resolutionDiceFaces.attack : (combat ? combat.attackRoll.raw : "?"),
                                                rolling: isRolling,
                                                winner: combat && showResult && combat.outcome.damageMultiplier > 0
                                            }),
                                            combat && showResult ? m("div", { class: "cg2-roll-breakdown" }, [
                                                m("span", combat.attackRoll.raw),
                                                m("span", "+"),
                                                m("span", { class: "cg2-mod" }, combat.attackRoll.strMod + "S"),
                                                m("span", "+"),
                                                m("span", { class: "cg2-mod cg2-mod-atk" }, combat.attackRoll.atkBonus + "A"),
                                                combat.attackRoll.skillMod ? [m("span", "+"), m("span", { class: "cg2-mod cg2-mod-skill" }, combat.attackRoll.skillMod + "Sk")] : null,
                                                combat.attackRoll.statusMod ? [m("span", "+"), m("span", { class: "cg2-mod cg2-mod-status" }, combat.attackRoll.statusMod + "St")] : null,
                                                m("span", "="),
                                                m("strong", combat.attackRoll.total)
                                            ]) : null
                                        ]),
                                        m("div", { class: "cg2-combat-roll-card" }, [
                                            m("div", { class: "cg2-roll-label" }, "DEF"),
                                            m(D20Dice, {
                                                value: isRolling ? gs.resolutionDiceFaces.defense : (combat ? combat.defenseRoll.raw : "?"),
                                                rolling: isRolling,
                                                winner: combat && showResult && combat.outcome.damageMultiplier <= 0
                                            }),
                                            combat && showResult ? m("div", { class: "cg2-roll-breakdown" }, [
                                                m("span", combat.defenseRoll.raw),
                                                m("span", "+"),
                                                m("span", { class: "cg2-mod" }, combat.defenseRoll.endMod + "E"),
                                                m("span", "+"),
                                                m("span", { class: "cg2-mod cg2-mod-def" }, combat.defenseRoll.defBonus + "D"),
                                                combat.defenseRoll.parryBonus ? [m("span", "+"), m("span", { class: "cg2-mod cg2-mod-parry" }, combat.defenseRoll.parryBonus + "P")] : null,
                                                combat.defenseRoll.statusMod ? [m("span", "+"), m("span", { class: "cg2-mod cg2-mod-status" }, combat.defenseRoll.statusMod + "St")] : null,
                                                m("span", "="),
                                                m("strong", combat.defenseRoll.total)
                                            ]) : null
                                        ])
                                    ])
                                ]),
                                // Outcome section
                                m("div", { class: "cg2-resolution-outcome" }, [
                                    combat && showResult ? m("div", { class: "cg2-combat-outcome" }, [
                                        m("div", {
                                            class: "cg2-outcome-label cg2-outcome-" +
                                                (combat.outcome.damageMultiplier > 0 ? "hit" :
                                                 combat.outcome.damageMultiplier < 0 ? "counter" : "miss")
                                        }, [
                                            m("span", { class: "cg2-outcome-text" }, combat.outcome.label),
                                            m("span", { class: "cg2-outcome-diff" },
                                                " (" + (combat.outcome.diff >= 0 ? "+" : "") + combat.outcome.diff + ")")
                                        ]),
                                        m("div", { class: "cg2-outcome-effect" }, combat.outcome.effect),
                                        combat.damageResult ? m("div", { class: "cg2-damage-display" }, [
                                            m("span", { class: "cg2-damage-number cg2-damage-dealt" },
                                                "-" + combat.damageResult.finalDamage + " HP"),
                                            m("span", { class: "cg2-damage-target" },
                                                " to " + combat.defenderName)
                                        ]) : null,
                                        combat.selfDamageResult ? m("div", { class: "cg2-damage-display" }, [
                                            m("span", { class: "cg2-damage-number cg2-damage-self" },
                                                "-" + combat.selfDamageResult.finalDamage + " HP"),
                                            m("span", { class: "cg2-damage-target" },
                                                " to " + combat.attackerName + " (counter!)")
                                        ]) : null,
                                        combat.criticalEffects && (combat.criticalEffects.rewardCard || combat.criticalEffects.itemDropped || combat.criticalEffects.attackerStunned)
                                            ? m("div", { class: "cg2-critical-effects" }, [
                                                combat.criticalEffects.rewardCard
                                                    ? m("div", { class: "cg2-critical-effect cg2-reward-earned" }, [
                                                        m("span", { class: "material-symbols-outlined" }, "auto_awesome"),
                                                        " ", m("strong", combat.criticalEffects.rewardCard.name),
                                                        m("span", { class: "cg2-reward-rarity cg2-rarity-" + (combat.criticalEffects.rewardCard.rarity || "common") },
                                                            " [" + (combat.criticalEffects.rewardCard.rarity || "common") + "]")
                                                    ]) : null,
                                                combat.criticalEffects.itemDropped
                                                    ? m("div", { class: "cg2-critical-effect cg2-item-dropped" }, [
                                                        m("span", { class: "material-symbols-outlined" }, "backpack"),
                                                        " ", m("strong", combat.criticalEffects.itemDropped.name)
                                                    ]) : null,
                                                combat.criticalEffects.attackerStunned
                                                    ? m("div", { class: "cg2-critical-effect cg2-attacker-stunned" }, [
                                                        m("span", { class: "material-symbols-outlined" }, "flash_off"),
                                                        " ", m("strong", combat.attackerName), " STUNNED!"
                                                    ]) : null
                                            ]) : null
                                    ]) : m("div", { class: "cg2-outcome-pending" }, [
                                        m("span", { class: "material-symbols-outlined cg2-spin" }, "casino"),
                                        " Rolling..."
                                    ])
                                ])
                            ])
                        ])
                        : m("div", { class: "cg2-resolution-body cg2-resolution-noncombat" }, [
                            m("div", { class: "cg2-resolving-action" }, [
                                m("span", { class: "material-symbols-outlined cg2-spin" }, "sync"),
                                " Resolving ", card ? card.name : "action", "..."
                            ])
                        ]),

                    // Running totals footer
                    totalsBar
                ]);
            }
        };
    }

    // ── End Threat Combat Result Display ────────────────────────────
    function renderEndThreatResult(etr) {
        let cr = etr.combatResult;
        if (!cr) {
            // No combat data - simple fallback
            return m("div", { class: "cg2-scenario-threat" }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;color:#c62828" },
                    etr.threat.imageIcon || "pets"),
                " ", m("strong", etr.threat.name),
                etr.damageDealt > 0
                    ? [" dealt ", m("span", { style: "color:#c62828;font-weight:bold" }, etr.damageDealt), " damage!"]
                    : [" was ", m("span", { style: "color:#4CAF50;font-weight:bold" }, "defeated"), "!"]
            ]);
        }
        let resultType = cr.resultType || (cr.defended ? "defended" : "hit");
        let outcomeLabel = cr.outcome?.label || "Unknown";
        let atkBreak = cr.attackRoll?.breakdown || "";
        let defBreak = cr.defenseRoll?.breakdown || "";

        return m("div", { class: "cg2-threat-combat-result" }, [
            // Outcome header
            m("div", { class: "cg2-threat-outcome-header cg2-threat-outcome-" + resultType }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:18px;vertical-align:middle;margin-right:4px" },
                    resultType === "defended" ? "shield" : resultType === "clash" ? "flash_on" : "swords"),
                m("strong", outcomeLabel)
            ]),
            // Roll breakdown
            m("div", { class: "cg2-threat-rolls" }, [
                m("div", { class: "cg2-threat-roll-row" }, [
                    m("span", { class: "cg2-roll-label" }, etr.threat.name + " ATK:"),
                    m("span", { class: "cg2-roll-value" }, atkBreak)
                ]),
                m("div", { class: "cg2-threat-roll-row" }, [
                    m("span", { class: "cg2-roll-label" }, "Your DEF:"),
                    m("span", { class: "cg2-roll-value" }, defBreak)
                ])
            ]),
            // Damage result
            resultType === "hit"
                ? m("div", { class: "cg2-threat-damage-line cg2-threat-dmg-hit" }, [
                    m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" }, "heart_broken"),
                    " You take ", m("strong", etr.damageDealt), " damage"
                ])
                : resultType === "clash"
                    ? m("div", { class: "cg2-threat-damage-line cg2-threat-dmg-clash" }, [
                        m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" }, "flash_on"),
                        " Both take ", m("strong", etr.damageDealt || 1), " damage"
                    ])
                    : m("div", { class: "cg2-threat-damage-line cg2-threat-dmg-def" }, [
                        m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" }, "shield"),
                        " Threat defeated!",
                        cr.counterDmg > 0 ? [" Counter: ", m("strong", cr.counterDmg), " damage"] : null
                    ]),
            // Loot earned
            etr.loot ? m("div", { class: "cg2-threat-loot-earned" }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle;color:#FFB300" }, "inventory_2"),
                " Earned: ", m("strong", etr.loot.name)
            ]) : null,
            // Pot forfeiture display
            etr.potForfeited ? m("div", { class: "cg2-threat-pot-forfeited" }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle;color:#c62828" }, "money_off"),
                " Pot (", m("strong", etr.potForfeited), " cards) forfeited to ",
                m("strong", etr.potForfeitedTo === "player" ? "You" : "Opponent"), "!"
            ]) : null,
            // Flee result display
            etr.fleeResult ? m("div", { class: "cg2-threat-flee-result" }, [
                m("div", { class: "cg2-threat-flee-roll" }, [
                    m("span", { class: "cg2-roll-label" }, "Flee Roll: "),
                    m("span", { class: "cg2-roll-value" },
                        etr.fleeResult.raw + " + " + etr.fleeResult.agiMod + " AGI = " + etr.fleeResult.total + " vs DC " + etr.fleeResult.difficulty)
                ]),
                m("div", {
                    class: etr.fleeResult.success ? "cg2-threat-flee-success" : "cg2-threat-flee-fail"
                }, [
                    m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" },
                        etr.fleeResult.success ? "directions_run" : "block"),
                    " ", etr.fleeResult.message
                ]),
                etr.fleeResult.potForfeited ? m("div", { class: "cg2-threat-pot-forfeited" }, [
                    m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle;color:#c62828" }, "money_off"),
                    " Pot (", etr.fleeResult.potForfeited, " cards) forfeited to opponent"
                ]) : null
            ]) : null,
            // Carry-over warning
            etr.carriedOver ? m("div", { class: "cg2-threat-carryover-warn" }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle;color:#E65100" }, "warning"),
                " ", m("strong", etr.threat.name), " survives! Carries to next round with +2 ATK"
            ]) : null
        ]);
    }

    // ── Cleanup Phase UI ──────────────────────────────────────────────
    function CleanupPhaseUI() {
        return {
            oninit() {
                let gs = GS().state;
                let gameState = gs.gameState;
                if (!gameState || gameState.cleanupApplied) return;

                let playerPts = gameState.player.roundPoints;
                let oppPts = gameState.opponent.roundPoints;

                if (playerPts > oppPts) {
                    gameState.roundWinner = "player";
                    gameState.player.hpRecovery = 5;
                    gameState.opponent.hpRecovery = 2;
                    gameState.player.energyRecovery = 3;
                    gameState.opponent.energyRecovery = 1;
                } else if (oppPts > playerPts) {
                    gameState.roundWinner = "opponent";
                    gameState.player.hpRecovery = 2;
                    gameState.opponent.hpRecovery = 5;
                    gameState.player.energyRecovery = 1;
                    gameState.opponent.energyRecovery = 3;
                } else {
                    gameState.roundWinner = "tie";
                    gameState.player.hpRecovery = 2;
                    gameState.opponent.hpRecovery = 2;
                    gameState.player.energyRecovery = 2;
                    gameState.opponent.energyRecovery = 2;
                }

                // Apply HP recovery
                gameState.player.hp = Math.min(gameState.player.maxHp, gameState.player.hp + gameState.player.hpRecovery);
                gameState.opponent.hp = Math.min(gameState.opponent.maxHp, gameState.opponent.hp + gameState.opponent.hpRecovery);
                // Apply Energy recovery
                gameState.player.energy = Math.min(gameState.player.maxEnergy, gameState.player.energy + gameState.player.energyRecovery);
                gameState.opponent.energy = Math.min(gameState.opponent.maxEnergy, gameState.opponent.energy + gameState.opponent.energyRecovery);

                // Track loot before claiming
                gameState.lootClaimed = [];
                if (gameState.roundLoot && gameState.roundLoot.length > 0) {
                    gameState.lootClaimed = gameState.roundLoot.slice();
                }

                // Winner claims pot + round loot
                if (gameState.roundWinner !== "tie") {
                    gameState.potClaimed = gameState.pot.length + (gameState.roundLoot?.length || 0);
                    NS.Engine.claimPot(gameState, gameState.roundWinner);
                } else {
                    gameState.potClaimed = 0;
                    console.log("[CardGame v2] Tie - pot carries over:", gameState.pot.length, "cards");
                }

                // End threat check
                gameState.endThreatResult = NS.Engine.checkEndThreat(gameState);
                if (gameState.endThreatResult && gameState.endThreatResult.threat) {
                    gameState.endThreatResult.responded = false;
                    console.log("[CardGame v2] End threat pending:", gameState.endThreatResult.threat.name,
                        "- targets", gameState.endThreatResult.threat.target);
                }

                // If scenario granted loot, trigger reveal animation
                if (gameState.endThreatResult && gameState.endThreatResult.bonusApplied
                    && gameState.endThreatResult.bonusApplied.type === "loot") {
                    gameState.endThreatResult._lootRevealActive = true;
                    setTimeout(function() {
                        if (gameState && gameState.endThreatResult) {
                            gameState.endThreatResult._lootRevealActive = false;
                            m.redraw();
                        }
                    }, 3000);
                }

                gameState.cleanupApplied = true;
                console.log("[CardGame v2] Cleanup - Round winner:", gameState.roundWinner,
                    "| Player HP:", gameState.player.hp, "(+" + gameState.player.hpRecovery + ")",
                    "Energy:", gameState.player.energy, "(+" + gameState.player.energyRecovery + ")",
                    "| Opponent HP:", gameState.opponent.hp, "(+" + gameState.opponent.hpRecovery + ")",
                    "Energy:", gameState.opponent.energy, "(+" + gameState.opponent.energyRecovery + ")");

                // Trigger round end narration
                GS().narrateRoundEnd(gameState.roundWinner);

                // Auto-save
                let Storage = NS.Storage;
                console.log("[CardGame v2] Auto-save: deckName=" + gameState.deckName, "round=" + gameState.round,
                    "Storage available:", !!Storage, "gameStorage:", !!(Storage && Storage.gameStorage));
                if (Storage && Storage.gameStorage) {
                    Storage.gameStorage.save(gameState.deckName, gameState).then(saved => {
                        console.log("[CardGame v2] Auto-save result:", saved ? "SUCCESS (objectId=" + (saved.objectId || saved.id || "?") + ")" : "FAILED (null returned)");
                    }).catch(e => console.warn("[CardGame v2] Auto-save exception:", e));
                } else {
                    console.warn("[CardGame v2] Auto-save SKIPPED: Storage not available");
                }
            },
            view() {
                let gs = GS().state;
                let gameState = gs.gameState;
                if (!gameState) return null;

                let winnerText = gameState.roundWinner === "player" ? "You win the round!" :
                                 gameState.roundWinner === "opponent" ? "Opponent wins the round!" :
                                 "Round is a tie!";

                return m("div", { class: "cg2-phase-panel cg2-cleanup-panel" }, [
                    // Loot reveal overlay
                    gameState.endThreatResult && gameState.endThreatResult._lootRevealActive
                        && gameState.endThreatResult.bonusApplied && gameState.endThreatResult.bonusApplied.type === "loot"
                        ? m("div", { class: "cg2-loot-reveal-overlay", onclick: function() {
                            gameState.endThreatResult._lootRevealActive = false;
                            m.redraw();
                        }}, [
                            m("div", { class: "cg2-loot-reveal-card" }, [
                                m(NS.Rendering.CardFace, { card: gameState.endThreatResult.bonusApplied.card }),
                                m("div", { class: "cg2-loot-reveal-label" }, [
                                    m("span", { class: "material-symbols-outlined" }, "auto_awesome"),
                                    " Loot Discovered!"
                                ])
                            ])
                        ]) : null,
                    m("h2", "Cleanup Phase"),
                    m("p", "Round " + gameState.round + " complete!"),
                    m("div", { class: "cg2-round-summary" }, [
                        m("div", { class: "cg2-round-winner " + (gameState.roundWinner === "player" ? "cg2-win" : gameState.roundWinner === "opponent" ? "cg2-lose" : "") }, winnerText),
                        m("div", { class: "cg2-round-points" }, [
                            m("span", "Your points: " + gameState.player.roundPoints),
                            m("span", " | "),
                            m("span", "Opponent: " + gameState.opponent.roundPoints)
                        ]),
                        m("div", { class: "cg2-round-recovery" }, [
                            m("div", { class: "cg2-recovery-row" }, [
                                m("span", { class: "cg2-recovery-label" }, "HP:"),
                                m("span", { class: gameState.roundWinner === "player" ? "cg2-winner" : "" },
                                    "You +" + gameState.player.hpRecovery),
                                m("span", " | "),
                                m("span", { class: gameState.roundWinner === "opponent" ? "cg2-winner" : "" },
                                    "Opp +" + gameState.opponent.hpRecovery)
                            ]),
                            m("div", { class: "cg2-recovery-row" }, [
                                m("span", { class: "cg2-recovery-label" }, "Energy:"),
                                m("span", { class: gameState.roundWinner === "player" ? "cg2-winner" : "" },
                                    "You +" + gameState.player.energyRecovery),
                                m("span", " | "),
                                m("span", { class: gameState.roundWinner === "opponent" ? "cg2-winner" : "" },
                                    "Opp +" + gameState.opponent.energyRecovery)
                            ])
                        ]),
                        gameState.potClaimed > 0 ? m("div", { class: "cg2-pot-claimed" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" }, "redeem"),
                            " ", gameState.roundWinner === "player" ? "You" : "Opponent",
                            " claimed ", gameState.potClaimed, " card", gameState.potClaimed > 1 ? "s" : "", " from the pot!"
                        ]) : null,
                        gameState.pot.length > 0 ? m("div", { class: "cg2-pot-carries" },
                            "Pot carries over: " + gameState.pot.length + " cards"
                        ) : null,
                        // Loot summary
                        gameState.lootClaimed && gameState.lootClaimed.length > 0
                            ? m("div", { class: "cg2-loot-summary" }, [
                                m("div", { class: "cg2-loot-title" }, [
                                    m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" }, "inventory_2"),
                                    " Loot Claimed"
                                ]),
                                m("div", { class: "cg2-loot-items" },
                                    gameState.lootClaimed.map((item, i) =>
                                        m("span", {
                                            key: i,
                                            class: "cg2-loot-item cg2-loot-" + (item.rarity || "COMMON").toLowerCase()
                                        }, [
                                            m("span", { class: "material-symbols-outlined", style: "font-size:12px" },
                                                item.type === "apparel" ? "checkroom" : item.subtype === "weapon" ? "swords" : "category"),
                                            " ", item.name
                                        ])
                                    )
                                )
                            ]) : null,

                        // Scenario card display (rendered as actual card via CardFace)
                        gameState.endThreatResult ? m("div", { class: "cg2-scenario-card-area" }, [
                            // Header: explain what this card is
                            m("div", { class: "cg2-scenario-card-header" }, [
                                m("span", { class: "material-symbols-outlined", style: "font-size:18px;vertical-align:middle;margin-right:6px;color:#B8860B" },
                                    gameState.endThreatResult.scenario.icon || "explore"),
                                "Round Event: ",
                                m("strong", gameState.endThreatResult.scenario.name)
                            ]),
                            m("div", { class: "cg2-scenario-card-body" }, [
                                // Render scenario as a CardFace card
                                m("div", { class: "cg2-scenario-card-wrap" },
                                    m(NS.Rendering.CardFace, { card: gameState.endThreatResult.scenario, compact: true })
                                ),
                                // Bonus effect summary (adjacent to the card)
                                m("div", { class: "cg2-scenario-result-panel" }, [
                                    gameState.endThreatResult.bonusApplied
                                        ? m("div", { class: "cg2-scenario-bonus" }, [
                                            gameState.endThreatResult.bonusApplied.type === "loot"
                                                ? [m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;color:#FFB300" }, "inventory_2"),
                                                   " Found: ", m("strong", gameState.endThreatResult.bonusApplied.card.name),
                                                   " \u2192 ", m("em", gameState.endThreatResult.bonusApplied.target === "player" ? "You" : "Opponent")]
                                                : gameState.endThreatResult.bonusApplied.type === "heal"
                                                    ? [m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;color:#4CAF50" }, "favorite"),
                                                       " Both recover ", m("strong", "+" + gameState.endThreatResult.bonusApplied.amount + " HP")]
                                                    : gameState.endThreatResult.bonusApplied.type === "energy"
                                                        ? [m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;color:#42A5F5" }, "bolt"),
                                                           " Both recover ", m("strong", "+" + gameState.endThreatResult.bonusApplied.amount + " Energy")]
                                                        : null
                                        ]) : null,

                                    // Threat info (pending)
                                    gameState.endThreatResult.threat && !gameState.endThreatResult.responded
                                        ? m("div", { class: "cg2-scenario-threat cg2-end-threat-pending" }, [
                                            m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;color:#c62828" },
                                                gameState.endThreatResult.threat.imageIcon || "pets"),
                                            " ", m("strong", gameState.endThreatResult.threat.name),
                                            " approaches! ",
                                            m("span", { class: gameState.endThreatResult.threat.target === "player" ? "cg2-threat-target-you" : "cg2-threat-target-opp" },
                                                gameState.endThreatResult.threat.target === "player" ? "You" : "Opponent"),
                                            " must defend.",
                                            m("div", { class: "cg2-threat-stats-mini" }, [
                                                m("span", "ATK " + gameState.endThreatResult.threat.atk),
                                                m("span", "DEF " + gameState.endThreatResult.threat.def),
                                                m("span", "HP " + gameState.endThreatResult.threat.hp)
                                            ])
                                        ])
                                        : null,

                                    // Threat combat result (resolved)
                                    gameState.endThreatResult.threat && gameState.endThreatResult.responded
                                        ? renderEndThreatResult(gameState.endThreatResult)
                                        : null,

                                    // Fallback: no bonus and no threat
                                    !gameState.endThreatResult.bonusApplied && !gameState.endThreatResult.threat
                                        ? m("div", { class: "cg2-scenario-no-effect" }, [
                                            m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle;color:#81c784" }, "check_circle"),
                                            " No threats this round."
                                        ]) : null
                                ])
                            ])
                        ]) : null
                    ]),

                    // Narration text display (fixed-height slot to prevent layout shift)
                    m("div", { class: "cg2-cleanup-narration" },
                        gameState.narrationText
                            ? [
                                m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;margin-right:4px;color:#B8860B" }, "campaign"),
                                m("span", { class: "cg2-cleanup-narration-text" }, gameState.narrationText)
                            ]
                            : m("span", { style: "opacity:0.3;font-size:12px" }, "Awaiting narration...")
                    ),

                    // Button - face threat or start next round (disabled while narration plays)
                    gameState.endThreatResult && gameState.endThreatResult.threat && !gameState.endThreatResult.responded
                        ? m("button", {
                            class: "cg2-btn cg2-btn-primary cg2-btn-threat",
                            disabled: !!gameState.narrationBusy,
                            onclick() {
                                gameState.cleanupApplied = false;
                                GS().advancePhase();
                            }
                        }, gameState.narrationBusy
                            ? [m("span", { class: "material-symbols-outlined cg2-spin", style: "vertical-align:middle;margin-right:4px;font-size:14px" }, "sync"), "Narrating..."]
                            : [m("span", { class: "material-symbols-outlined", style: "vertical-align:middle;margin-right:4px" }, "shield"), "Face the Threat"])
                        : m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: !!gameState.narrationBusy,
                            onclick() {
                                gameState.cleanupApplied = false;
                                GS().advancePhase();
                            }
                        }, gameState.narrationBusy
                            ? [m("span", { class: "material-symbols-outlined cg2-spin", style: "vertical-align:middle;margin-right:4px;font-size:14px" }, "sync"), "Narrating..."]
                            : "Start Round " + (gameState.round + 1))
                ]);
            }
        };
    }

    // ── Expose ────────────────────────────────────────────────────────
    NS.UI.InitiativePhaseUI = InitiativePhaseUI;
    NS.UI.EquipPhaseUI = EquipPhaseUI;
    NS.UI.EquipSlot = EquipSlot;
    NS.UI.ResolutionPhaseUI = ResolutionPhaseUI;
    NS.UI.CleanupPhaseUI = CleanupPhaseUI;

}());
