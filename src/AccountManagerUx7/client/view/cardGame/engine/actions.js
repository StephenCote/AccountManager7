/**
 * CardGame Engine - Action Resolution
 * Pot system (ante/claim), hoarding prevention (lethargy/exhausted),
 * card drawing, action bar placement/removal, and initial stack dealing.
 *
 * v2.1: Actions are now selected via icon picker (not drawn from hand).
 *   - selectAction() creates virtual action cards from ACTION_DEFINITIONS
 *   - placeCard() updated to handle _fromPicker cards (not removed from hand)
 *   - Hand only contains modifier cards (skill, magic, item)
 *   - ensureOffensiveCard() removed (Attack is always available via picker)
 *   - Hoarding prevention simplified (no action cards in hand to hoard)
 *
 * Depends on: CardGame.Constants (GAME_PHASES, ACTION_DEFINITIONS, COMMON_ACTIONS)
 * Exposes: window.CardGame.Engine.{anteCard, claimPot, addToPot,
 *   drawCardsForActor, getActionsForActor, isActionPlacedThisRound,
 *   selectAction, isCoreCardType, isModifierCardType,
 *   placeCard, removeCardFromPosition, dealInitialStack, shuffle}
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Engine = window.CardGame.Engine || {};

    const C = window.CardGame.Constants;

    // ── Array Shuffle (Fisher-Yates) ────────────────────────────────────
    function shuffle(arr) {
        let a = arr.slice();
        for (let i = a.length - 1; i > 0; i--) {
            let j = Math.floor(Math.random() * (i + 1));
            [a[i], a[j]] = [a[j], a[i]];
        }
        return a;
    }

    // ── Pot System ──────────────────────────────────────────────────────

    // Ante: Each player puts a random card from hand into the pot
    function anteCard(state, actor, actorName) {
        if (!actor.hand || actor.hand.length === 0) {
            console.log("[CardGame v2]", actorName, "has no cards to ante");
            return null;
        }
        let idx = Math.floor(Math.random() * actor.hand.length);
        let card = actor.hand.splice(idx, 1)[0];
        state.pot.push(card);
        console.log("[CardGame v2]", actorName, "anted", card.name, "to pot");
        return card;
    }

    // Round winner claims all cards in the pot + any round loot
    function claimPot(state, winner) {
        let winnerActor = winner === "player" ? state.player : state.opponent;
        let potSize = (state.pot ? state.pot.length : 0) + (state.roundLoot ? state.roundLoot.length : 0);

        // Check for jackpot BEFORE clearing pot
        let isJackpot = potSize >= 5;

        // Claim pot cards
        if (state.pot && state.pot.length > 0) {
            state.pot.forEach(card => {
                winnerActor.discardPile.push(card);
            });
            console.log("[CardGame v2]", winner, "claimed pot with", state.pot.length, "cards");
            state.pot = [];
        }

        // Claim round loot
        if (state.roundLoot && state.roundLoot.length > 0) {
            state.roundLoot.forEach(loot => {
                // Equipment goes to card stack, consumables to hand
                if (loot.type === "item" && loot.subtype === "consumable") {
                    winnerActor.hand.push(loot);
                } else if (loot.type === "item" || loot.type === "apparel") {
                    winnerActor.cardStack.push(loot);
                } else {
                    winnerActor.discardPile.push(loot);
                }
            });
            console.log("[CardGame v2]", winner, "claimed", state.roundLoot.length, "loot items");
            state.roundLoot = [];
        }

        // Trigger jackpot if pot had 5+ cards
        if (isJackpot) {
            state._jackpotTriggered = true;
            state._jackpotWinner = winner;
            state._jackpotPotSize = potSize;
            console.log("[CardGame v2] JACKPOT! Pot had", potSize, "cards - vault draw triggered for", winner);
        }
    }

    // Add a card to the pot (for mid-round drops)
    function addToPot(state, card, reason) {
        if (!card) return;
        state.pot.push(card);
        console.log("[CardGame v2] Added", card.name, "to pot (" + reason + ")");
    }

    // Add a loot item to the round loot pool
    function addToRoundLoot(state, item, source) {
        if (!item) return;
        if (!state.roundLoot) state.roundLoot = [];
        state.roundLoot.push(item);
        console.log("[CardGame v2] Loot added:", item.name, "from", source);
    }

    // ── Draw Cards Helper ───────────────────────────────────────────────
    // Hand now only contains modifier cards (skill, magic, item)
    function drawCardsForActor(actor, count) {
        for (let i = 0; i < count; i++) {
            // If draw pile empty, shuffle discard into draw
            if (actor.drawPile.length === 0 && actor.discardPile.length > 0) {
                actor.drawPile = shuffle([...actor.discardPile]);
                actor.discardPile = [];
                console.log("[CardGame v2] Reshuffled discard pile into draw pile");
            }

            if (actor.drawPile.length > 0) {
                let card = actor.drawPile.shift();
                actor.hand.push(card);
                console.log("[CardGame v2] Drew card:", card.name);
            }
        }
    }

    // ── Action Selection (Icon Picker) ────────────────────────────────────

    /**
     * Get the list of actions available to an actor based on their character class.
     * Returns array of action name strings.
     */
    function getActionsForActor(actor) {
        if (!actor || !actor.character) return C.COMMON_ACTIONS.slice();
        // Character card stores class actions from template
        return actor.character._classActions || C.COMMON_ACTIONS.slice();
    }

    /**
     * Check if a specific action has already been placed on the action bar this round.
     * Actions can only be placed once per round (no duplicates).
     */
    function isActionPlacedThisRound(state, actionName, owner) {
        if (!state || !state.actionBar) return false;
        return state.actionBar.positions.some(pos =>
            pos.owner === owner &&
            pos.stack &&
            pos.stack.coreCard &&
            pos.stack.coreCard.name === actionName
        );
    }

    /**
     * Select an action from the icon picker and place it on an action bar position.
     * Creates a virtual action card object from ACTION_DEFINITIONS.
     * @param {Object} state - Game state
     * @param {number} positionIndex - Action bar position index
     * @param {string} actionName - Name of the action (e.g. "Attack", "Guard")
     * @returns {boolean} Success
     */
    function selectAction(state, positionIndex, actionName) {
        let def = C.ACTION_DEFINITIONS[actionName];
        if (!def) {
            console.warn("[CardGame v2] Unknown action:", actionName);
            return false;
        }

        // Build a virtual action card from the definition
        let actionCard = {
            type: actionName === "Talk" ? "talk" : "action",
            name: actionName,
            actionType: def.type,
            energyCost: def.energyCost || 0,
            icon: def.icon,
            roll: def.roll,
            stackWith: def.stackWith,
            onHit: def.desc,
            _fromPicker: true  // Marker: not a hand card, generated from picker
        };

        return placeCard(state, positionIndex, actionCard);
    }

    // ── Placement Actions ───────────────────────────────────────────────

    // Determine if a card type can be a core card (action that drives the stack)
    function isCoreCardType(cardType) {
        return cardType === "action" || cardType === "talk" || cardType === "magic";
    }

    // Determine if a card type can be a modifier (stacks on top of core)
    function isModifierCardType(cardType) {
        return cardType === "skill" || cardType === "item" || cardType === "magic";
    }

    /**
     * Check if a modifier card is compatible with a core action's stackWith rule.
     * Returns { allowed: boolean, reason: string }
     */
    function canModifyAction(coreCard, modifierCard) {
        if (!coreCard || !modifierCard) return { allowed: false, reason: "Missing card" };

        let actionName = coreCard.name;
        let def = C.ACTION_DEFINITIONS[actionName];
        let stackWith = def ? def.stackWith : coreCard.stackWith;
        if (!stackWith) return { allowed: true, reason: "" };  // No rule = allow

        let sw = stackWith.toLowerCase();

        // "None" means no modifiers allowed (exclusive actions like Rest)
        if (sw === "none") {
            return { allowed: false, reason: actionName + " cannot be modified" };
        }

        let cardType = modifierCard.type;
        let subtype = (modifierCard.subtype || "").toLowerCase();

        // Parse stackWith tokens
        let acceptsSkill  = sw.indexOf("skill") >= 0;
        let acceptsWeapon = sw.indexOf("weapon") >= 0;
        let acceptsMagic  = sw.indexOf("magic") >= 0;
        let acceptsConsumable = sw.indexOf("consumable") >= 0;
        let acceptsMaterials  = sw.indexOf("material") >= 0;
        let acceptsItem   = sw.indexOf("item") >= 0;  // generic "Item(s) to offer"

        if (cardType === "skill") {
            if (acceptsSkill) return { allowed: true, reason: "" };
            return { allowed: false, reason: actionName + " does not accept skills" };
        }

        if (cardType === "magic") {
            if (acceptsMagic) return { allowed: true, reason: "" };
            return { allowed: false, reason: actionName + " does not accept magic" };
        }

        if (cardType === "item") {
            // Weapons
            if (subtype === "weapon" && acceptsWeapon) return { allowed: true, reason: "" };
            // Consumables
            if (subtype === "consumable" && acceptsConsumable) return { allowed: true, reason: "" };
            // Materials
            if (subtype === "material" && acceptsMaterials) return { allowed: true, reason: "" };
            // Generic item acceptance (Trade: "Item(s) to offer")
            if (acceptsItem) return { allowed: true, reason: "" };
            // Apparel as generic items for trade
            if (acceptsItem && (subtype === "apparel" || subtype === "loot")) return { allowed: true, reason: "" };

            return { allowed: false, reason: actionName + " does not accept " + (subtype || "this item type") };
        }

        // Apparel cards
        if (cardType === "apparel") {
            if (acceptsItem) return { allowed: true, reason: "" };
            return { allowed: false, reason: actionName + " does not accept apparel" };
        }

        return { allowed: false, reason: "Unknown card type: " + cardType };
    }

    function placeCard(state, positionIndex, card, forceModifier) {
        if (!state) return false;
        if (state.phase !== C.GAME_PHASES.DRAW_PLACEMENT) return false;
        forceModifier = forceModifier || false;

        let pos = state.actionBar.positions.find(p => p.index === positionIndex);
        if (!pos) return false;

        // Check ownership
        let currentPlayer = state.currentTurn;
        if (pos.owner !== currentPlayer) {
            console.warn("[CardGame v2] Cannot place on opponent's position");
            return false;
        }

        let actor = currentPlayer === "player" ? state.player : state.opponent;

        // Determine if this card should be a modifier or core
        let isModifier = forceModifier;
        let hasExistingCore = pos.stack && pos.stack.coreCard;

        if (!forceModifier && hasExistingCore) {
            // Position already has a core card
            if (card.type === "action" || card.type === "talk" || card.type === "magic") {
                console.warn("[CardGame v2] Cannot stack multiple action cards - position already has:", pos.stack.coreCard.name);
                if (typeof page !== "undefined" && page.toast) page.toast("warn", "Position already has an action card");
                return false;
            } else if (isModifierCardType(card.type)) {
                isModifier = true;
            } else {
                console.warn("[CardGame v2] Position already has a core card, and", card.type, "cannot be a modifier");
                return false;
            }
        }

        // Double-check stack state
        if (!isModifier && pos.stack && pos.stack.coreCard) {
            console.error("[CardGame v2] Stack still has core card - blocking duplicate");
            return false;
        }

        if (isModifier) {
            // Add modifier to existing stack (no AP cost)
            if (!pos.stack || !pos.stack.coreCard) {
                console.warn("[CardGame v2] No core card to modify - pick an action first");
                return false;
            }

            // Validate modifier compatibility with the core action
            let compat = canModifyAction(pos.stack.coreCard, card);
            if (!compat.allowed) {
                console.warn("[CardGame v2] Modifier rejected:", compat.reason);
                if (typeof page !== "undefined" && page.toast) page.toast("warn", compat.reason);
                return false;
            }

            // Prevent duplicate modifier types: only one card of each type per stack
            // e.g. one weapon + one skill + one magic is OK, but two weapons is not
            let modType = card.type === "item" ? (card.subtype || "item") : card.type;
            let existingSameType = pos.stack.modifiers.some(m => {
                let mType = m.type === "item" ? (m.subtype || "item") : m.type;
                return mType === modType;
            });
            if (existingSameType) {
                let typeLabel = modType.charAt(0).toUpperCase() + modType.slice(1);
                console.warn("[CardGame v2] Already has a", modType, "modifier in this stack");
                if (typeof page !== "undefined" && page.toast) page.toast("warn", "Already has a " + typeLabel + " card in this action");
                return false;
            }

            // Prevent stacking a modifier that matches the core card's type
            // (e.g., no magic modifier on a magic core = no double spells)
            let coreType = pos.stack.coreCard.type === "item" ? (pos.stack.coreCard.subtype || "item") : pos.stack.coreCard.type;
            if (modType === coreType) {
                let typeLabel = modType.charAt(0).toUpperCase() + modType.slice(1);
                console.warn("[CardGame v2] Cannot add", modType, "modifier — core card is already", coreType);
                if (typeof page !== "undefined" && page.toast) page.toast("warn", "Cannot stack two " + typeLabel + " cards");
                return false;
            }

            pos.stack.modifiers.push(card);
            console.log("[CardGame v2] Added modifier", card.name, "to stack at position", positionIndex);

            // Remove modifier card from hand (modifiers come from hand)
            let handOwner = currentPlayer === "player" ? state.player : state.opponent;
            let idx = handOwner.hand.findIndex(c => c === card || (c.name === card.name && c.type === card.type));
            if (idx >= 0) handOwner.hand.splice(idx, 1);
        } else {
            // Place core card (action/talk/magic)
            if (!isCoreCardType(card.type)) {
                // Auto-select an action for item cards dropped on empty position
                if (card.type === "item") {
                    let actionName = (card.subtype === "weapon") ? "Attack" : "Use Item";
                    let def = C.ACTION_DEFINITIONS[actionName];
                    if (!def) {
                        console.warn("[CardGame v2] No action definition for auto-select:", actionName);
                        return false;
                    }
                    let actionCard = {
                        type: "action",
                        name: actionName,
                        actionType: def.type,
                        energyCost: def.energyCost || 0,
                        icon: def.icon,
                        roll: def.roll,
                        stackWith: def.stackWith,
                        onHit: def.desc,
                        _fromPicker: true
                    };
                    console.log("[CardGame v2] Auto-selecting", actionName, "for", card.name, "(item on empty position)");
                    if (!placeCard(state, positionIndex, actionCard)) return false;
                    return placeCard(state, positionIndex, card, true);
                }
                console.warn("[CardGame v2]", card.type, "cards need an action card first");
                return false;
            }

            // Check AP
            if (actor.apUsed >= actor.ap) {
                console.warn("[CardGame v2] No AP remaining");
                return false;
            }

            // Check energy cost
            if (card.energyCost && card.energyCost > actor.energy) {
                console.warn("[CardGame v2] Not enough energy for", card.name);
                return false;
            }

            // Check duplicate action (actions can only be placed once per round)
            if (card._fromPicker && isActionPlacedThisRound(state, card.name, currentPlayer)) {
                console.warn("[CardGame v2] Action already placed this round:", card.name);
                if (typeof page !== "undefined" && page.toast) page.toast("warn", card.name + " already placed this round");
                return false;
            }

            pos.stack = { coreCard: card, modifiers: [] };
            actor.apUsed++;

            // Deduct energy
            if (card.energyCost) {
                actor.energy -= card.energyCost;
            }

            // Track action type
            let actionKey = card.name;
            if (!actor.typesPlayedThisRound) actor.typesPlayedThisRound = {};
            actor.typesPlayedThisRound[actionKey] = (actor.typesPlayedThisRound[actionKey] || 0) + 1;

            console.log("[CardGame v2] Placed core card", card.name, "at position", positionIndex,
                card._fromPicker ? "(from picker)" : "(from hand)");

            // Only remove from hand if card came from hand (not from picker)
            if (!card._fromPicker) {
                let handOwner = currentPlayer === "player" ? state.player : state.opponent;
                let idx = handOwner.hand.findIndex(c => c === card || (c.name === card.name && c.type === card.type));
                if (idx >= 0) handOwner.hand.splice(idx, 1);
            }
        }

        m.redraw();
        return true;
    }

    function removeCardFromPosition(state, positionIndex, skipRedraw) {
        if (!state) return false;
        if (state.phase !== C.GAME_PHASES.DRAW_PLACEMENT) return false;
        skipRedraw = skipRedraw || false;

        let pos = state.actionBar.positions.find(p => p.index === positionIndex);
        if (!pos || !pos.stack) return false;

        let currentPlayer = state.currentTurn;
        if (pos.owner !== currentPlayer) return false;

        let actor = currentPlayer === "player" ? state.player : state.opponent;

        // Return core card to hand only if it came from hand (not from picker)
        if (pos.stack.coreCard) {
            if (!pos.stack.coreCard._fromPicker) {
                actor.hand.push(pos.stack.coreCard);
            }
            // Always refund AP and energy
            actor.apUsed = Math.max(0, actor.apUsed - 1);

            if (pos.stack.coreCard.energyCost) {
                actor.energy = Math.min(actor.maxEnergy, actor.energy + pos.stack.coreCard.energyCost);
            }

            // Remove from types played tracking
            let actionKey = pos.stack.coreCard.name;
            if (actor.typesPlayedThisRound && actor.typesPlayedThisRound[actionKey]) {
                actor.typesPlayedThisRound[actionKey]--;
                if (actor.typesPlayedThisRound[actionKey] <= 0) {
                    delete actor.typesPlayedThisRound[actionKey];
                }
            }

            console.log("[CardGame v2] Removed", pos.stack.coreCard.name, "from position", positionIndex);
        }

        // Return modifiers to hand (modifiers always come from hand)
        pos.stack.modifiers.forEach(mod => actor.hand.push(mod));

        pos.stack = null;
        if (!skipRedraw) m.redraw();
        return true;
    }

    // ── Deal Initial Stack ──────────────────────────────────────────────
    function dealInitialStack(apparelCards, itemCards) {
        let stack = [];

        let weapons = itemCards.filter(i => i.subtype === "weapon");
        if (weapons.length > 0) {
            stack.push(shuffle([...weapons])[0]);
        } else {
            stack.push({
                type: "item", subtype: "weapon", name: "Basic Blade",
                slot: "Hand (1H)", rarity: "COMMON", atk: 2, range: "Melee",
                damageType: "Slashing", effect: "+2 ATK"
            });
        }

        let armors = itemCards.filter(i => i.subtype === "armor");
        if (armors.length > 0) {
            stack.push(shuffle([...armors])[0]);
        } else {
            stack.push({
                type: "item", subtype: "armor", name: "Basic Armor",
                slot: "Body", rarity: "COMMON", def: 2, effect: "+2 DEF"
            });
        }

        if (apparelCards.length > 0) {
            let shuffledApparel = shuffle([...apparelCards]);
            stack.push(shuffledApparel[0]);
        } else {
            stack.push({ type: "apparel", name: "Basic Garb", slot: "Body", def: 1, effect: "+1 DEF" });
        }

        console.log("[CardGame v2] Initial card stack:", stack.map(c => c.name));
        return stack;
    }

    // ── Export ───────────────────────────────────────────────────────────
    Object.assign(window.CardGame.Engine, {
        anteCard,
        claimPot,
        addToPot,
        addToRoundLoot,
        drawCardsForActor,
        getActionsForActor,
        isActionPlacedThisRound,
        selectAction,
        isCoreCardType,
        isModifierCardType,
        canModifyAction,
        placeCard,
        removeCardFromPosition,
        dealInitialStack,
        shuffle
    });

    console.log('[CardGame] Engine/actions loaded');

}());
