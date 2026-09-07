/**
 * gameDefTransform.js — pure deck/game-definition split transforms (ESM)
 *
 * A card DECK is a reusable art+content artifact; a card GAME definition holds the
 * play-time knobs (narration/voice/announcer/poker-face/banter). Historically both
 * lived fused on a single `deck.json` blob under `~/CardGame/{deckName}`. These pure
 * functions extract the game concern into a separate `game.json` record and resolve
 * the effective config back out, with the legacy inline `deck.gameConfig` kept as a
 * one-release fallback.
 *
 * Deliberately import-free so they are directly unit-testable without touching the
 * REST/servlet layer or Mithril.
 */

function hasKeys(obj) {
    return !!obj && typeof obj === 'object' && Object.keys(obj).length > 0;
}

/**
 * Split a composite deck into a stripped deck (art + content, no game config) and a
 * standalone game definition. Does NOT mutate the input deck.
 *
 * @param {object} deck      the (possibly composite) deck blob
 * @param {string} [deckRef] a STABLE reference to the deck — the deck group's objectId
 *                           (UUID), never the mutable deckName
 * @returns {{ strippedDeck: object, gameDef: object }}
 */
function splitGameDef(deck, deckRef) {
    let src = deck || {};
    let gameConfig = hasKeys(src.gameConfig) ? Object.assign({}, src.gameConfig) : {};

    let strippedDeck = Object.assign({}, src);
    delete strippedDeck.gameConfig;

    let gameDef = {
        version: 1,
        deckRef: deckRef || null,
        gameConfig: gameConfig
    };

    return { strippedDeck: strippedDeck, gameDef: gameDef };
}

/**
 * Resolve the effective game config: prefer the persisted game definition, and fall
 * back to the legacy inline `deck.gameConfig` (a one-release compatibility path).
 *
 * @param {object} [gameDef] the loaded game.json record ({ version, deckRef, gameConfig })
 * @param {object} [deck]    the deck (may still carry inline gameConfig during migration)
 * @returns {object} the effective gameConfig (never null)
 */
function resolveGameConfig(gameDef, deck) {
    if (gameDef && hasKeys(gameDef.gameConfig)) return gameDef.gameConfig;
    if (deck && deck.gameConfig) return deck.gameConfig;
    return {};
}

export { splitGameDef, resolveGameConfig, hasKeys };
