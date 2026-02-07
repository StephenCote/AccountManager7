/**
 * CardGame Engine - Action Resolution
 * Pot system (ante/claim), hoarding prevention (lethargy/exhausted),
 * card drawing, action bar placement/removal, and initial stack dealing.
 *
 * Depends on: CardGame.Constants (GAME_PHASES), CardGame.Engine (effects, combat, encounters)
 * Exposes: window.CardGame.Engine.{anteCard, claimPot, addToPot,
 *   checkLethargy, checkExhausted,
 *   drawCardsForActor, ensureOffensiveCard,
 *   isCoreCardType, isModifierCardType, placeCard, removeCardFromPosition,
 *   dealInitialStack, shuffle}
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
        // Pick a random card from hand
        let idx = Math.floor(Math.random() * actor.hand.length);
        let card = actor.hand.splice(idx, 1)[0];
        state.pot.push(card);
        console.log("[CardGame v2]", actorName, "anted", card.name, "to pot");
        return card;
    }

    // Round winner claims all cards in the pot
    function claimPot(state, winner) {
        if (!state.pot || state.pot.length === 0) return;
        let winnerActor = winner === "player" ? state.player : state.opponent;
        state.pot.forEach(card => {
            winnerActor.discardPile.push(card);
        });
        console.log("[CardGame v2]", winner, "claimed pot with", state.pot.length, "cards");
        state.pot = [];
    }

    // Add a card to the pot (for mid-round drops)
    function addToPot(state, card, reason) {
        if (!card) return;
        state.pot.push(card);
        console.log("[CardGame v2] Added", card.name, "to pot (" + reason + ")");
    }

    // ── Hoarding Prevention ─────────────────────────────────────────────

    /**
     * Lethargy Check (at cleanup)
     * If actor holds 2+ copies of the same action type and played 0 this round,
     * keep 1 copy and return the rest to the encounter deck.
     * @param {Object} state - The current game state
     * @param {Object} actor - The actor to check
     * @param {string} actorName - Display name for logging
     * @returns {Array} Array of {actionType, stripped} objects
     */
    function checkLethargy(state, actor, actorName) {
        if (!actor.hand || actor.hand.length === 0) return [];

        let typesPlayed = actor.typesPlayedThisRound || {};
        let results = [];

        // Count action cards by name (only action type cards)
        let actionCounts = {};
        actor.hand.forEach(card => {
            if (card.type === "action") {
                let key = card.name;
                actionCounts[key] = (actionCounts[key] || 0) + 1;
            }
        });

        // Check each action type with 2+ copies
        Object.keys(actionCounts).forEach(actionType => {
            let count = actionCounts[actionType];
            let played = typesPlayed[actionType] || 0;

            // Lethargy: 2+ copies AND 0 played this round
            if (count >= 2 && played === 0) {
                let stripped = count - 1;  // Keep 1, strip the rest
                let removed = 0;

                // Remove extras from hand and add to encounter deck
                for (let i = actor.hand.length - 1; i >= 0 && removed < stripped; i--) {
                    let card = actor.hand[i];
                    if (card.type === "action" && card.name === actionType) {
                        actor.hand.splice(i, 1);
                        // Return to encounter deck
                        if (state.encounterDeck) {
                            state.encounterDeck.push(card);
                        }
                        removed++;
                    }
                }

                if (removed > 0) {
                    results.push({ actionType, stripped: removed });
                    console.log("[CardGame v2] LETHARGY:", actorName, "- stripped", removed, actionType, "card(s)");
                }
            }
        });

        // Shuffle encounter deck after adding cards
        if (results.length > 0 && state.encounterDeck) {
            state.encounterDeck = shuffle(state.encounterDeck);
        }

        return results;
    }

    /**
     * Exhausted Check (during resolution)
     * If actor played 2+ of a type this round, last one failed, and holds 2+ extras in hand,
     * keep 1 extra and return the rest to encounter deck.
     * @param {Object} state - The current game state
     * @param {Object} actor - The actor to check
     * @param {string} actorName - Display name for logging
     * @param {string} failedActionType - The action type that failed
     * @returns {Object|null} { actionType, stripped } or null if no trigger
     */
    function checkExhausted(state, actor, actorName, failedActionType) {
        if (!actor.hand || !failedActionType) return null;

        let typesPlayed = actor.typesPlayedThisRound || {};
        let playedCount = typesPlayed[failedActionType] || 0;

        // Must have played 2+ of this type
        if (playedCount < 2) return null;

        // Count how many of this type remain in hand
        let handCount = actor.hand.filter(c => c.type === "action" && c.name === failedActionType).length;

        // Exhausted: played 2+, failed, and hold 2+ extras in hand
        if (handCount >= 2) {
            let stripped = handCount - 1;  // Keep 1, strip the rest
            let removed = 0;

            for (let i = actor.hand.length - 1; i >= 0 && removed < stripped; i--) {
                let card = actor.hand[i];
                if (card.type === "action" && card.name === failedActionType) {
                    actor.hand.splice(i, 1);
                    if (state.encounterDeck) {
                        state.encounterDeck.push(card);
                    }
                    removed++;
                }
            }

            if (removed > 0) {
                // Shuffle encounter deck
                if (state.encounterDeck) {
                    state.encounterDeck = shuffle(state.encounterDeck);
                }
                console.log("[CardGame v2] EXHAUSTED:", actorName, "- stripped", removed, failedActionType, "card(s)");
                return { actionType: failedActionType, stripped: removed };
            }
        }

        return null;
    }

    // ── Draw Cards Helper ───────────────────────────────────────────────
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

    /**
     * Ensure actor has at least one offensive card (Attack) in hand.
     * If not, find one from draw/discard pile or create a basic Attack.
     */
    function ensureOffensiveCard(actor, actorName) {
        // Check if hand already has an Attack card
        let hasAttack = actor.hand.some(c => c.type === "action" && c.name === "Attack");
        if (hasAttack) return;

        // Try to find Attack in draw pile
        let attackIdx = actor.drawPile.findIndex(c => c.type === "action" && c.name === "Attack");
        if (attackIdx >= 0) {
            let attackCard = actor.drawPile.splice(attackIdx, 1)[0];
            actor.hand.push(attackCard);
            console.log("[CardGame v2]", actorName, "guaranteed Attack card from draw pile");
            return;
        }

        // Try discard pile
        attackIdx = actor.discardPile.findIndex(c => c.type === "action" && c.name === "Attack");
        if (attackIdx >= 0) {
            let attackCard = actor.discardPile.splice(attackIdx, 1)[0];
            actor.hand.push(attackCard);
            console.log("[CardGame v2]", actorName, "guaranteed Attack card from discard pile");
            return;
        }

        // Last resort: create a basic Attack card
        let basicAttack = {
            type: "action",
            name: "Attack",
            effect: "Roll ATK vs DEF. Deal STR damage on hit.",
            rarity: "COMMON"
        };
        actor.hand.push(basicAttack);
        console.log("[CardGame v2]", actorName, "granted basic Attack card (none in deck)");
    }

    // ── Placement Actions ───────────────────────────────────────────────

    // Determine if a card type can be a core card (action that drives the stack)
    function isCoreCardType(cardType) {
        return cardType === "action" || cardType === "talk" || cardType === "magic";
    }

    // Determine if a card type can be a modifier (stacks on top of core)
    // Note: magic is NOT a modifier - it's a core action like attack/talk
    function isModifierCardType(cardType) {
        return cardType === "skill" || cardType === "item";
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
            // Core action types (action, talk, magic) cannot be stacked - reject them
            if (card.type === "action" || card.type === "talk" || card.type === "magic") {
                console.warn("[CardGame v2] Cannot stack multiple action cards - position already has:", pos.stack.coreCard.name);
                if (typeof page !== "undefined" && page.toast) page.toast("warn", "Position already has an action card");
                return false;
            } else if (isModifierCardType(card.type)) {
                // Modifiers (skill, item) can be added to existing stack
                isModifier = true;
            } else {
                console.warn("[CardGame v2] Position already has a core card, and", card.type, "cannot be a modifier");
                return false;
            }
        }

        // Double-check stack state after potential removal
        if (!isModifier && pos.stack && pos.stack.coreCard) {
            console.error("[CardGame v2] Stack still has core card after removal - blocking duplicate");
            return false;
        }

        if (isModifier) {
            // Add modifier to existing stack (no AP cost for modifiers)
            if (!pos.stack || !pos.stack.coreCard) {
                console.warn("[CardGame v2] No core card to modify - place an action first");
                return false;
            }
            pos.stack.modifiers.push(card);
            console.log("[CardGame v2] Added modifier", card.name, "to stack at position", positionIndex);
        } else {
            // Place core card (action/talk/magic)
            if (!isCoreCardType(card.type)) {
                // Skill dropped on empty slot - can't be core
                console.warn("[CardGame v2]", card.type, "cards need an action card first");
                return false;
            }

            // Check AP for core cards
            if (actor.apUsed >= actor.ap) {
                console.warn("[CardGame v2] No AP remaining");
                return false;
            }

            // Check energy cost
            if (card.energyCost && card.energyCost > actor.energy) {
                console.warn("[CardGame v2] Not enough energy for", card.name);
                return false;
            }

            pos.stack = { coreCard: card, modifiers: [] };
            actor.apUsed++;

            // Deduct energy if needed
            if (card.energyCost) {
                actor.energy -= card.energyCost;
            }

            // Track action type for hoarding prevention (Lethargy/Exhausted)
            let actionKey = card.name;  // Use card name as the action type key
            if (!actor.typesPlayedThisRound) actor.typesPlayedThisRound = {};
            actor.typesPlayedThisRound[actionKey] = (actor.typesPlayedThisRound[actionKey] || 0) + 1;

            console.log("[CardGame v2] Placed core card", card.name, "at position", positionIndex);
        }

        // Remove card from hand
        if (currentPlayer === "player") {
            let idx = state.player.hand.findIndex(c => c === card || (c.name === card.name && c.type === card.type));
            if (idx >= 0) state.player.hand.splice(idx, 1);
        }

        m.redraw();
        // Player manually ends turn - no auto-commit
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

        // Return cards to hand and refund costs
        if (pos.stack.coreCard) {
            actor.hand.push(pos.stack.coreCard);
            actor.apUsed = Math.max(0, actor.apUsed - 1);

            // Refund energy cost
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

        // Return modifiers to hand
        pos.stack.modifiers.forEach(mod => actor.hand.push(mod));

        pos.stack = null;
        if (!skipRedraw) m.redraw();
        return true;
    }

    // ── Deal Initial Stack ──────────────────────────────────────────────
    // Deal initial modifier cards to character stack (weapon + armor + apparel)
    function dealInitialStack(apparelCards, itemCards) {
        let stack = [];

        // 1. Always add a weapon - find one from deck or create basic
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

        // 2. Always add armor - find one from deck or create basic
        let armors = itemCards.filter(i => i.subtype === "armor");
        if (armors.length > 0) {
            stack.push(shuffle([...armors])[0]);
        } else {
            stack.push({
                type: "item", subtype: "armor", name: "Basic Armor",
                slot: "Body", rarity: "COMMON", def: 2,
                effect: "+2 DEF"
            });
        }

        // 3. Add 1 random apparel card if available, or basic garb
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
        checkLethargy,
        checkExhausted,
        drawCardsForActor,
        ensureOffensiveCard,
        isCoreCardType,
        isModifierCardType,
        placeCard,
        removeCardFromPosition,
        dealInitialStack,
        shuffle
    });

    console.log('[CardGame] Engine/actions loaded');

}());
