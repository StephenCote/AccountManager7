/**
 * CardGame Engine - Status Effects & Effect Parsing
 * Manages application/removal/ticking of status effects on actors,
 * and provides a unified parser for card effect strings (damage, heal, status, etc.)
 *
 * Depends on: CardGame.Constants (STATUS_EFFECTS)
 * Exposes: window.CardGame.Engine.{applyStatusEffect, removeStatusEffect, hasStatusEffect,
 *   getStatusModifiers, processStatusEffectsTurnStart, tickStatusEffects, onActorHit,
 *   parseEffect, applyParsedEffects, isEffectParseable, SKILL_ACTION_KEYWORDS, getStackSkillMod}
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Engine = window.CardGame.Engine || {};

    const C = window.CardGame.Constants;

    // ── Status Effect Management ────────────────────────────────────────

    // Apply a status effect to an actor
    function applyStatusEffect(actor, effectId, sourceName) {
        if (!actor.statusEffects) actor.statusEffects = [];
        let effectDef = C.STATUS_EFFECTS[effectId.toUpperCase()];
        if (!effectDef) {
            console.warn("[CardGame v2] Unknown status effect:", effectId);
            return false;
        }
        // Check if already has this effect (refresh duration instead of stacking)
        let existing = actor.statusEffects.find(e => e.id === effectDef.id);
        if (existing) {
            existing.turnsRemaining = effectDef.duration;
            console.log("[CardGame v2] Refreshed", effectDef.name, "on", actor.character?.name || "actor");
            return true;
        }
        // Add new effect
        actor.statusEffects.push({
            id: effectDef.id,
            name: effectDef.name,
            icon: effectDef.icon,
            color: effectDef.color,
            turnsRemaining: effectDef.duration,
            durationType: effectDef.durationType,
            source: sourceName || "Unknown"
        });
        console.log("[CardGame v2] Applied", effectDef.name, "to", actor.character?.name || "actor");
        return true;
    }

    // Remove a status effect from an actor
    function removeStatusEffect(actor, effectId) {
        if (!actor.statusEffects) return;
        let idx = actor.statusEffects.findIndex(e => e.id === effectId);
        if (idx >= 0) {
            let removed = actor.statusEffects.splice(idx, 1)[0];
            console.log("[CardGame v2] Removed", removed.name, "from", actor.character?.name || "actor");
        }
    }

    // Check if actor has a specific status effect
    function hasStatusEffect(actor, effectId) {
        if (!actor.statusEffects) return false;
        return actor.statusEffects.some(e => e.id === effectId);
    }

    // Get total status effect modifiers for an actor
    function getStatusModifiers(actor) {
        let mods = { atk: 0, def: 0, roll: 0 };
        if (!actor.statusEffects) return mods;
        actor.statusEffects.forEach(effect => {
            let def = C.STATUS_EFFECTS[effect.id.toUpperCase()];
            if (def) {
                if (def.atkBonus) mods.atk += def.atkBonus;
                if (def.defBonus) mods.def += def.defBonus;
                if (def.defPenalty) mods.def += def.defPenalty;
                if (def.rollPenalty) mods.roll += def.rollPenalty;
                if (def.rollBonus) mods.roll += def.rollBonus;
            }
        });
        return mods;
    }

    // Process status effects at turn start (for effects like poison)
    function processStatusEffectsTurnStart(actor) {
        if (!actor.statusEffects) return [];
        let messages = [];
        actor.statusEffects.forEach(effect => {
            let def = C.STATUS_EFFECTS[effect.id.toUpperCase()];
            if (def && def.onTurnStart) {
                let result = def.onTurnStart(actor);
                if (result && result.message) {
                    messages.push({ effect: effect.name, message: result.message });
                }
            }
        });
        return messages;
    }

    // Tick down status effect durations (call at end of turn/round)
    function tickStatusEffects(actor) {
        if (!actor.statusEffects) return;
        actor.statusEffects = actor.statusEffects.filter(effect => {
            if (effect.durationType === "turns") {
                effect.turnsRemaining--;
                if (effect.turnsRemaining <= 0) {
                    console.log("[CardGame v2]", effect.name, "expired on", actor.character?.name || "actor");
                    return false;
                }
            }
            return true;
        });
    }

    // Remove "until hit" effects when actor is hit
    function onActorHit(actor) {
        if (!actor.statusEffects) return;
        actor.statusEffects = actor.statusEffects.filter(effect => {
            if (effect.durationType === "untilHit") {
                console.log("[CardGame v2]", effect.name, "removed (hit) from", actor.character?.name || "actor");
                return false;
            }
            return true;
        });
    }

    // ── Unified Effect Parser ───────────────────────────────────────────
    // Parses an effect string and returns all recognized mechanical effects.
    // Used by magic, consumable, and custom card evaluation.
    // Supported patterns:
    //   "Deal N damage"        -> { damage: N }
    //   "Heal N" / "Restore N HP"  -> { healHp: N }
    //   "Restore N Energy"     -> { restoreEnergy: N }
    //   "Restore N Morale"     -> { restoreMorale: N }
    //   "Drain N"              -> { damage: N, healHp: N }  (damage + self-heal)
    //   "Draw N"               -> { draw: N }
    //   "Burn" / "Ignite"      -> { status: "burning", target: "enemy" }
    //   "Bleed"                -> { status: "bleeding", target: "enemy" }
    //   "Stun"                 -> { status: "stunned", target: "enemy" }
    //   "Poison"               -> { status: "poisoned", target: "enemy" }
    //   "Shield" / "Protect"   -> { status: "shielded", target: "self" }
    //   "Enrage" / "Fury"      -> { status: "enraged", target: "self" }
    //   "Weaken"               -> { status: "weakened", target: "enemy" }
    //   "Fortify" / "Bolster"  -> { status: "fortified", target: "self" }
    //   "Inspire"              -> { status: "inspired", target: "self" }
    //   "Regenerate" / "Regen" -> { status: "regenerating", target: "self" }
    //   "Cure" / "Cleanse"     -> { cure: true }  (removes negative status)
    function parseEffect(effectStr) {
        if (!effectStr) return {};
        let e = effectStr.toLowerCase();
        let result = {};

        // Numeric extractions
        let damageMatch = effectStr.match(/deal\s+(\d+)/i);
        if (damageMatch) result.damage = parseInt(damageMatch[1], 10);

        let drainMatch = effectStr.match(/drain\s+(\d+)/i);
        if (drainMatch) {
            let val = parseInt(drainMatch[1], 10);
            result.damage = (result.damage || 0) + val;
            result.healHp = (result.healHp || 0) + val;
        }

        let healMatch = effectStr.match(/heal\s+(\d+)|restore\s+(\d+)\s+hp/i);
        if (healMatch) result.healHp = (result.healHp || 0) + parseInt(healMatch[1] || healMatch[2], 10);

        let energyMatch = effectStr.match(/restore\s+(\d+)\s+energy/i);
        if (energyMatch) result.restoreEnergy = parseInt(energyMatch[1], 10);

        let moraleMatch = effectStr.match(/restore\s+(\d+)\s+morale/i);
        if (moraleMatch) result.restoreMorale = parseInt(moraleMatch[1], 10);

        let drawMatch = effectStr.match(/draw\s+(\d+)/i);
        if (drawMatch) result.draw = parseInt(drawMatch[1], 10);

        // Status effect keywords -> { statusEffects: [{id, target}] }
        let statuses = [];
        if (e.includes("stun"))                                    statuses.push({ id: "stunned", target: "enemy" });
        if (e.includes("poison"))                                  statuses.push({ id: "poisoned", target: "enemy" });
        if (e.includes("burn") || e.includes("ignite"))            statuses.push({ id: "burning", target: "enemy" });
        if (e.includes("bleed"))                                   statuses.push({ id: "bleeding", target: "enemy" });
        if (e.includes("weaken"))                                  statuses.push({ id: "weakened", target: "enemy" });
        if (e.includes("shield") || e.includes("protect"))         statuses.push({ id: "shielded", target: "self" });
        if (e.includes("enrage") || e.includes("fury"))            statuses.push({ id: "enraged", target: "self" });
        if (e.includes("fortify") || e.includes("bolster"))        statuses.push({ id: "fortified", target: "self" });
        if (e.includes("inspire"))                                 statuses.push({ id: "inspired", target: "self" });
        if (e.includes("regenerat") || e.includes("regen"))        statuses.push({ id: "regenerating", target: "self" });
        if (statuses.length > 0) result.statusEffects = statuses;

        // Cure/cleanse
        if (e.includes("cure") || e.includes("cleanse") || e.includes("purify")) result.cure = true;

        return result;
    }

    // Apply parsed effects to owner/target actors
    function applyParsedEffects(parsed, owner, target, sourceName) {
        let log = [];
        if (parsed.damage && target) {
            let dmgResult = window.CardGame.Engine.applyDamage(target, parsed.damage);
            log.push(sourceName + " deals " + dmgResult.finalDamage + " damage");
        }
        if (parsed.healHp && owner) {
            let before = owner.hp;
            owner.hp = Math.min(owner.maxHp, owner.hp + parsed.healHp);
            log.push(sourceName + " heals " + (owner.hp - before) + " HP");
        }
        if (parsed.restoreEnergy && owner) {
            owner.energy = Math.min(owner.maxEnergy, owner.energy + parsed.restoreEnergy);
            log.push(sourceName + " restores " + parsed.restoreEnergy + " Energy");
        }
        if (parsed.restoreMorale && owner) {
            owner.morale = Math.min(owner.maxMorale, owner.morale + parsed.restoreMorale);
            log.push(sourceName + " restores " + parsed.restoreMorale + " Morale");
        }
        if (parsed.draw && owner) {
            window.CardGame.Engine.drawCardsForActor(owner, parsed.draw);
            log.push(sourceName + " draws " + parsed.draw + " card(s)");
        }
        if (parsed.statusEffects) {
            parsed.statusEffects.forEach(se => {
                let actor = se.target === "self" ? owner : target;
                if (actor) applyStatusEffect(actor, se.id, sourceName);
                log.push(sourceName + " applies " + se.id + " to " + (se.target === "self" ? "self" : "target"));
            });
        }
        if (parsed.cure && owner) {
            // Remove all negative status effects from owner
            let negatives = ["stunned", "poisoned", "burning", "bleeding", "weakened"];
            negatives.forEach(id => removeStatusEffect(owner, id));
            log.push(sourceName + " cures negative effects");
        }
        return log;
    }

    // Check if an effect string has any mechanically parseable content
    function isEffectParseable(effectStr) {
        let parsed = parseEffect(effectStr);
        return Object.keys(parsed).length > 0;
    }

    // Skill action keyword map: actionType -> keywords that match
    const SKILL_ACTION_KEYWORDS = {
        attack:     ["attack", "combat", "melee", "strike", "offensive"],
        defense:    ["defense", "defend", "parry", "block", "defensive"],
        talk:       ["talk", "social", "charisma", "persuade", "diplomacy", "speech"],
        initiative: ["initiative", "speed", "first"],
        investigate:["investigate", "search", "discover", "perception"],
        flee:       ["flee", "escape", "evasion", "retreat"],
        craft:      ["craft", "create", "forge", "build"],
        magic:      ["magic", "spell", "cast", "arcane", "psionic"]
    };

    function getStackSkillMod(stack, actionType) {
        if (!stack || !stack.modifiers) return 0;
        let total = 0;
        let keywords = SKILL_ACTION_KEYWORDS[actionType] || [actionType];
        stack.modifiers.forEach(mod => {
            if (mod.type === "skill" && mod.modifier) {
                let match = mod.modifier.match(/\+(\d+)/);
                if (match) {
                    let modLower = mod.modifier.toLowerCase();
                    if (keywords.some(kw => modLower.includes(kw))) {
                        total += parseInt(match[1], 10);
                    }
                }
            }
        });
        return total;
    }

    // ── Export ───────────────────────────────────────────────────────────
    Object.assign(window.CardGame.Engine, {
        applyStatusEffect,
        removeStatusEffect,
        hasStatusEffect,
        getStatusModifiers,
        processStatusEffectsTurnStart,
        tickStatusEffects,
        onActorHit,
        parseEffect,
        applyParsedEffects,
        isEffectParseable,
        SKILL_ACTION_KEYWORDS,
        getStackSkillMod
    });

    console.log('[CardGame] Engine/effects loaded');

}());
