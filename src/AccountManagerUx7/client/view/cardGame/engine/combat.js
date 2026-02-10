/**
 * CardGame Engine - Combat System
 * Dice utilities, actor stat helpers, attack/defense rolls, damage calculation,
 * and the main combat resolver with dual-wield, criticals, and counter-attacks.
 *
 * Depends on: CardGame.Constants (COMBAT_OUTCOMES), CardGame.Engine (effects)
 * Exposes: window.CardGame.Engine.{DiceUtils, rollD20, rollInitiative,
 *   getActorATK, isDualWielding, getWeaponATKs, getActorDEF,
 *   rollAttack, rollDefense, getCombatOutcome, COMBAT_OUTCOMES,
 *   calculateDamage, applyDamage, resolveCombat}
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Engine = window.CardGame.Engine || {};

    const C = window.CardGame.Constants;
    const E = window.CardGame.Engine;  // effects module (loaded before this)

    // ── Dice Utilities (Phase 7.0) ──────────────────────────────────────
    // Centralized dice rolling for testability and consistency
    const DiceUtils = {
        // Roll a die with specified sides (default d20)
        roll(sides = 20) {
            return Math.floor(Math.random() * sides) + 1;
        },

        // Roll with a single modifier, returning breakdown
        rollWithMod(stat = 0, statName = "") {
            const raw = this.roll(20);
            const total = raw + stat;
            const breakdown = statName ? `${raw} + ${stat} ${statName} = ${total}` : `${raw} + ${stat} = ${total}`;
            return { raw, modifier: stat, total, breakdown, isCrit: raw === 20, isFumble: raw === 1 };
        },

        // Initiative roll (d20 + AGI)
        initiative(stats) {
            const raw = this.roll(20);
            const modifier = (stats && stats.AGI) || 0;
            return { raw, modifier, total: raw + modifier };
        }
    };

    // Backwards compatibility
    function rollD20() {
        return DiceUtils.roll(20);
    }

    function rollInitiative(stats) {
        return DiceUtils.initiative(stats);
    }

    // ── Actor Stat Helpers ──────────────────────────────────────────────

    // Get total ATK bonus from actor's equipped items and card stack (weapons)
    function getActorATK(actor) {
        let total = 0;
        let counted = new Set();
        // From equipped slots (primary source)
        if (actor.equipped) {
            Object.values(actor.equipped).forEach(card => {
                if (card && card.atk && !counted.has(card)) {
                    counted.add(card);
                    total += card.atk;
                }
            });
        }
        // From cardStack (unequipped items that still contribute)
        (actor.cardStack || []).forEach(card => {
            if (card.atk && !counted.has(card)) total += card.atk;
        });
        return total;
    }

    // Check if actor is dual-wielding (two one-handed weapons)
    function isDualWielding(actor) {
        if (actor.equipped) {
            let handL = actor.equipped.handL;
            let handR = actor.equipped.handR;
            return handL && handR && handL !== handR &&
                handL.type === "item" && handL.subtype === "weapon" &&
                handR.type === "item" && handR.subtype === "weapon";
        }
        let oneHandedWeapons = (actor.cardStack || []).filter(card =>
            card.type === "item" &&
            card.subtype === "weapon" &&
            card.slot &&
            card.slot.includes("1H")
        );
        return oneHandedWeapons.length >= 2;
    }

    // Get ATK values for each weapon (for dual wield separate rolls)
    function getWeaponATKs(actor) {
        let weapons = [];
        let seen = new Set();
        // From equipped hand slots
        if (actor.equipped) {
            [actor.equipped.handL, actor.equipped.handR].forEach(card => {
                if (card && card.type === "item" && card.subtype === "weapon" && card.atk && !seen.has(card)) {
                    seen.add(card);
                    weapons.push({ name: card.name, atk: card.atk || 0 });
                }
            });
        }
        // Fallback to cardStack
        if (weapons.length === 0) {
            (actor.cardStack || []).filter(card =>
                card.type === "item" && card.subtype === "weapon" && card.atk
            ).forEach(w => weapons.push({ name: w.name, atk: w.atk || 0 }));
        }
        return weapons;
    }

    // Get total DEF bonus from actor's equipped items and card stack (armor, apparel)
    function getActorDEF(actor) {
        let total = 0;
        let counted = new Set();
        // From equipped slots (primary source)
        if (actor.equipped) {
            Object.values(actor.equipped).forEach(card => {
                if (card && card.def && !counted.has(card)) {
                    counted.add(card);
                    total += card.def;
                }
            });
        }
        // From cardStack (unequipped items that still contribute)
        (actor.cardStack || []).forEach(card => {
            if (card.def && !counted.has(card)) total += card.def;
        });
        return total;
    }

    // ── Combat Rolls ────────────────────────────────────────────────────

    // Roll attack: 1d20 + STR + weapon ATK + skill mods (from stack) + status effects
    function rollAttack(attacker, stack) {
        let raw = rollD20();
        let stats = attacker.character.stats || {};
        let strMod = stats.STR || 0;
        let atkBonus = getActorATK(attacker);
        let skillMod = E.getStackSkillMod(stack, "attack");

        // Add status effect modifiers
        let statusMods = E.getStatusModifiers(attacker);
        let statusAtkMod = statusMods.atk + statusMods.roll;

        let total = raw + strMod + atkBonus + skillMod + statusAtkMod;

        let breakdown = `${raw} + ${strMod} STR + ${atkBonus} ATK`;
        if (skillMod) breakdown += ` + ${skillMod} Skill`;
        if (statusAtkMod) breakdown += ` + ${statusAtkMod} Status`;
        breakdown += ` = ${total}`;

        return {
            raw,
            strMod,
            atkBonus,
            skillMod,
            statusMod: statusAtkMod,
            total,
            formula: "1d20 + STR + ATK" + (skillMod ? " + Skill" : "") + (statusAtkMod ? " + Status" : ""),
            breakdown
        };
    }

    // Roll defense: 1d20 + END + armor DEF + weapon parry (if applicable) + status effects
    function rollDefense(defender) {
        let raw = rollD20();
        let stats = defender.character.stats || {};
        let endMod = stats.END || 0;
        let defBonus = getActorDEF(defender);
        // Parry: check if defender has a weapon with parry property
        let parryBonus = 0;
        (defender.cardStack || []).forEach(card => {
            if (card.parry) parryBonus += card.parry;
        });

        // Add status effect modifiers
        let statusMods = E.getStatusModifiers(defender);
        let statusDefMod = statusMods.def + statusMods.roll;

        let total = raw + endMod + defBonus + parryBonus + statusDefMod;

        let breakdown = `${raw} + ${endMod} END + ${defBonus} DEF`;
        if (parryBonus) breakdown += ` + ${parryBonus} Parry`;
        if (statusDefMod) breakdown += ` + ${statusDefMod} Status`;
        breakdown += ` = ${total}`;

        return {
            raw,
            endMod,
            defBonus,
            parryBonus,
            statusMod: statusDefMod,
            total,
            formula: "1d20 + END + DEF" + (parryBonus ? " + Parry" : "") + (statusDefMod ? " + Status" : ""),
            breakdown
        };
    }

    // ── Combat Outcomes ─────────────────────────────────────────────────

    // Outcome table (based on roll difference + natural 1/20)
    // Uses the constants from COMBAT_OUTCOMES but kept as local ref for the resolver
    const COMBAT_OUTCOMES = C.COMBAT_OUTCOMES;

    function getCombatOutcome(attackRoll, defenseRoll) {
        let diff = attackRoll.total - defenseRoll.total;
        let attackNat = attackRoll.raw;
        let defenseNat = defenseRoll.raw;

        // Natural 20 on attack = Critical Hit (always)
        if (attackNat === 20) return { ...COMBAT_OUTCOMES.CRITICAL_HIT, diff };

        // Natural 1 on attack = Critical Miss (always)
        if (attackNat === 1) return { ...COMBAT_OUTCOMES.CRITICAL_MISS, diff };

        // Natural 20 on defense with successful block = Critical Parry (with reward)
        if (defenseNat === 20 && diff <= 0) return { ...COMBAT_OUTCOMES.CRITICAL_PARRY, diff };

        // Diff-based outcomes (no criticals)
        if (diff >= 10) return { ...COMBAT_OUTCOMES.DEVASTATING, diff };
        if (diff >= 5)  return { ...COMBAT_OUTCOMES.STRONG_HIT, diff };
        if (diff >= 1)  return { ...COMBAT_OUTCOMES.GLANCING_HIT, diff };
        if (diff === 0) return { ...COMBAT_OUTCOMES.CLASH, diff };
        if (diff >= -4) return { ...COMBAT_OUTCOMES.DEFLECT, diff };
        return { ...COMBAT_OUTCOMES.PARRY, diff };
    }

    // ── Damage Calculation ──────────────────────────────────────────────

    // Calculate damage: base = weapon ATK + STR, modified by outcome
    function calculateDamage(attacker, outcome) {
        let stats = attacker.character.stats || {};
        let strMod = stats.STR || 0;
        let weaponAtk = getActorATK(attacker);
        let baseDamage = weaponAtk + strMod;

        // Apply outcome multiplier
        let finalDamage = Math.floor(baseDamage * outcome.damageMultiplier);
        // Minimum 1 damage on any successful hit
        if (outcome.damageMultiplier > 0 && finalDamage < 1) finalDamage = 1;

        return {
            baseDamage,
            multiplier: outcome.damageMultiplier,
            finalDamage,
            breakdown: `(${weaponAtk} ATK + ${strMod} STR) \u00D7 ${outcome.damageMultiplier} = ${finalDamage}`
        };
    }

    // Apply damage to a target actor
    function applyDamage(actor, damage) {
        let def = getActorDEF(actor);
        let reduced = Math.max(1, damage - def);  // Minimum 1 damage
        actor.hp = Math.max(0, actor.hp - reduced);

        // Remove "until hit" status effects (like shielded)
        E.onActorHit(actor);

        return {
            rawDamage: damage,
            armorReduction: def,
            finalDamage: reduced,
            newHp: actor.hp
        };
    }

    // ── Main Combat Resolver ────────────────────────────────────────────

    // Resolve combat between attacker and defender
    function resolveCombat(attackerActor, defenderActor, stack) {
        // Check for dual wield (two one-handed weapons = two attack rolls)
        let dualWield = isDualWielding(attackerActor);
        let weapons = dualWield ? getWeaponATKs(attackerActor) : null;

        let attackRoll = rollAttack(attackerActor, stack);
        let defenseRoll = rollDefense(defenderActor);
        let outcome = getCombatOutcome(attackRoll, defenseRoll);
        let damage = calculateDamage(attackerActor, outcome);
        let damageResult = null;
        let selfDamageResult = null;

        // Dual wield: second attack roll
        let secondAttack = null;
        if (dualWield && weapons && weapons.length >= 2) {
            // Second attack uses the second weapon's ATK
            let secondRoll = rollAttack(attackerActor, stack);
            let secondOutcome = getCombatOutcome(secondRoll, defenseRoll);
            let secondDamage = calculateDamage(attackerActor, secondOutcome);

            secondAttack = {
                weaponName: weapons[1].name,
                attackRoll: secondRoll,
                outcome: secondOutcome,
                damage: secondDamage,
                damageResult: null
            };

            // Apply second attack damage
            if (secondOutcome.damageMultiplier > 0) {
                secondAttack.damageResult = applyDamage(defenderActor, secondDamage.finalDamage);
            }

            // Tag first attack with weapon name
            if (weapons[0]) {
                attackRoll.weaponName = weapons[0].name;
            }

            console.log("[CardGame v2] DUAL WIELD: Second attack with", weapons[1].name, "- Roll:", secondRoll.total, "Outcome:", secondOutcome.label);
        }

        // Apply effects based on outcome (first/main attack)
        let criticalEffects = { itemDropped: null, attackerStunned: false, rewardCard: null };

        if (outcome.damageMultiplier > 0) {
            // Hit - apply damage to defender
            damageResult = applyDamage(defenderActor, damage.finalDamage);

            // Decrement defender's apparel durability on hit
            let brokenArmor = decrementApparelDurability(defenderActor, outcome.isCriticalHit);
            if (brokenArmor.length > 0) {
                criticalEffects.brokenArmor = brokenArmor;
            }

            // Critical Hit: ALWAYS reward the attacker with an elevated item card
            if (outcome.isCriticalHit) {
                // Generate a special elevated item as reward
                let rewardCard = E.generateCriticalReward("attack", attackerActor);
                attackerActor.hand.push(rewardCard);
                criticalEffects.rewardCard = rewardCard;
                console.log("[CardGame v2] CRITICAL HIT! Reward card:", rewardCard.name);

                // ALSO 50% chance to make defender drop an item
                if (defenderActor.cardStack && defenderActor.cardStack.length > 0 && Math.random() < 0.5) {
                    let droppableItems = defenderActor.cardStack.filter(c =>
                        c.type === "item" || c.type === "apparel"
                    );
                    if (droppableItems.length > 0) {
                        let droppedItem = droppableItems[Math.floor(Math.random() * droppableItems.length)];
                        let idx = defenderActor.cardStack.indexOf(droppedItem);
                        if (idx >= 0) {
                            defenderActor.cardStack.splice(idx, 1);
                            // Add to round loot pool (winner claims at cleanup)
                            let gs = CardGame.ctx?.gameState;
                            if (gs && E.addToRoundLoot) {
                                E.addToRoundLoot(gs, droppedItem, "critical hit drop");
                            } else if (gs) {
                                gs.pot.push(droppedItem);
                            }
                            criticalEffects.itemDropped = droppedItem;
                            console.log("[CardGame v2] CRITICAL HIT! Item dropped to loot:", droppedItem.name);
                        }
                    }
                }
            }
        } else if (outcome.damageMultiplier < 0) {
            // Critical counter - attacker takes damage
            selfDamageResult = applyDamage(attackerActor, damage.finalDamage * -1);

            // Critical Counter: stun the attacker (skip next action)
            if (outcome.isCriticalCounter) {
                E.applyStatusEffect(attackerActor, "stunned", "Critical Counter");
                criticalEffects.attackerStunned = true;
                console.log("[CardGame v2] CRITICAL COUNTER! Attacker stunned");
            }

            // Critical Parry: defender gets a reward card
            if (outcome.isCriticalParry) {
                let rewardCard = E.generateCriticalReward("defense", defenderActor);
                defenderActor.hand.push(rewardCard);
                criticalEffects.rewardCard = rewardCard;
                console.log("[CardGame v2] CRITICAL PARRY! Reward card:", rewardCard.name);
            }
        } else if (outcome.bothTakeDamage) {
            // Clash - both take fixed damage
            damageResult = applyDamage(defenderActor, outcome.bothTakeDamage);
            selfDamageResult = applyDamage(attackerActor, outcome.bothTakeDamage);
        }

        let result = {
            attackRoll,
            defenseRoll,
            outcome,
            damage,
            damageResult,
            selfDamageResult,
            attackerName: attackerActor.character.name || "Attacker",
            defenderName: defenderActor.character.name || "Defender",
            // Dual wield info
            dualWield: dualWield,
            secondAttack: secondAttack,
            // Critical effects
            criticalEffects: criticalEffects
        };

        console.log("[CardGame v2] Combat resolved:", result);
        return result;
    }

    // Check if game is over (either actor HP <= 0)
    // ── Durability System ────────────────────────────────────────────────

    // Decrement apparel durability on defender when hit
    function decrementApparelDurability(actor, isCritical) {
        let broken = [];
        let decAmount = isCritical ? 2 : 1;
        if (!actor.equipped) return broken;

        for (let slotKey of Object.keys(actor.equipped)) {
            let card = actor.equipped[slotKey];
            if (!card) continue;
            if (card.type !== "apparel" && !(card.type === "item" && card.subtype === "armor")) continue;
            if (card.durability == null) continue;
            // Skip duplicate for two-handed (only decrement once)
            if (slotKey === "handL" && actor.equipped.handR === card) continue;

            card.durability = Math.max(0, card.durability - decAmount);
            if (card.durability <= 0) {
                broken.push({ card: card, slotKey: slotKey });
                // Remove from equipped
                if (card.slot === "Hand (2H)") {
                    actor.equipped.handL = null;
                    actor.equipped.handR = null;
                } else {
                    actor.equipped[slotKey] = null;
                }
                // Add to pot as salvage
                let gs = window.CardGame.ctx ? window.CardGame.ctx.gameState : null;
                if (gs && gs.pot) {
                    gs.pot.push(card);
                    console.log("[CardGame v2] DURABILITY: " + card.name + " destroyed, added to pot");
                }
            }
        }
        return broken;
    }

    // Decrement weapon durability after use in attack
    function decrementWeaponDurability(actor) {
        let broken = [];
        if (!actor.equipped) return broken;

        let seen = new Set();
        for (let slotKey of ["handL", "handR"]) {
            let card = actor.equipped[slotKey];
            if (!card || seen.has(card)) continue;
            seen.add(card);
            if (card.type !== "item" || card.subtype !== "weapon") continue;
            if (card.durability == null) continue;

            card.durability = Math.max(0, card.durability - 1);
            if (card.durability <= 0) {
                broken.push({ card: card, slotKey: slotKey });
                if (card.slot === "Hand (2H)") {
                    actor.equipped.handL = null;
                    actor.equipped.handR = null;
                } else {
                    actor.equipped[slotKey] = null;
                }
                let gs = window.CardGame.ctx ? window.CardGame.ctx.gameState : null;
                if (gs && gs.pot) {
                    gs.pot.push(card);
                    console.log("[CardGame v2] DURABILITY: Weapon " + card.name + " destroyed, added to pot");
                }
            }
        }
        return broken;
    }

    function checkGameOver(state) {
        let gs = state || (window.CardGame.ctx ? window.CardGame.ctx.gameState : null);
        if (!gs) return null;
        if (gs.player.hp <= 0) return "opponent";
        if (gs.opponent.hp <= 0) return "player";
        return null;
    }

    // ── Export ───────────────────────────────────────────────────────────
    Object.assign(window.CardGame.Engine, {
        DiceUtils,
        rollD20,
        rollInitiative,
        getActorATK,
        isDualWielding,
        getWeaponATKs,
        getActorDEF,
        rollAttack,
        rollDefense,
        getCombatOutcome,
        calculateDamage,
        applyDamage,
        resolveCombat,
        checkGameOver,
        decrementApparelDurability,
        decrementWeaponDurability
    });

    console.log('[CardGame] Engine/combat loaded');

}());
