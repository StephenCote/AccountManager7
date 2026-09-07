/**
 * Pure unit tests for the card DECK / card GAME-definition split transform.
 *
 * These import ONLY state/gameDefTransform.js — no REST/servlet/Mithril layer is
 * touched or mocked (per repo rule: never mock the REST layer to test business
 * logic; keep the transform a pure callable function).
 */
import { describe, it, expect } from 'vitest';
import { splitGameDef, resolveGameConfig, hasKeys } from '../cardGame/state/gameDefTransform.js';

describe('gameDefTransform.hasKeys', () => {
    it('is false for null/undefined/non-object', () => {
        expect(hasKeys(null)).toBe(false);
        expect(hasKeys(undefined)).toBe(false);
        expect(hasKeys('x')).toBe(false);
        expect(hasKeys(5)).toBe(false);
    });
    it('is false for an empty object, true for a populated one', () => {
        expect(hasKeys({})).toBe(false);
        expect(hasKeys({ a: 1 })).toBe(true);
    });
});

describe('gameDefTransform.splitGameDef', () => {
    const composite = {
        deckName: 'Rogues',
        cards: [{ type: 'character', name: 'A' }],
        artRefs: { background: 'obj-bg-1' },
        gameConfig: {
            narrationEnabled: false,
            opponentVoiceEnabled: true,
            opponentVoiceProfileId: 'voice-9',
            announcerEnabled: true,
            pokerFaceEnabled: true,
            banterLevel: 'aggressive'
        }
    };

    it('extracts gameConfig into the game definition', () => {
        const { gameDef } = splitGameDef(composite, 'deck-objid-123');
        expect(gameDef.gameConfig).toEqual(composite.gameConfig);
        expect(gameDef.version).toBe(1);
        expect(gameDef.deckRef).toBe('deck-objid-123');
    });

    it('strips gameConfig from the deck but keeps content + art refs', () => {
        const { strippedDeck } = splitGameDef(composite, 'deck-objid-123');
        expect(strippedDeck.gameConfig).toBeUndefined();
        expect(strippedDeck.deckName).toBe('Rogues');
        expect(strippedDeck.cards).toBe(composite.cards);
        expect(strippedDeck.artRefs).toBe(composite.artRefs);
    });

    it('does NOT mutate the input deck', () => {
        const before = JSON.stringify(composite);
        splitGameDef(composite, 'deck-objid-123');
        expect(JSON.stringify(composite)).toBe(before);
        expect(composite.gameConfig).toBeDefined();
    });

    it('copies gameConfig (game def edits do not leak back into the source)', () => {
        const { gameDef } = splitGameDef(composite, 'deck-objid-123');
        gameDef.gameConfig.banterLevel = 'subtle';
        expect(composite.gameConfig.banterLevel).toBe('aggressive');
    });

    it('handles a deck with no gameConfig (empty config, still emits deckRef)', () => {
        const { strippedDeck, gameDef } = splitGameDef({ deckName: 'Bare' }, 'ref-x');
        expect(strippedDeck.deckName).toBe('Bare');
        expect(gameDef.gameConfig).toEqual({});
        expect(gameDef.deckRef).toBe('ref-x');
    });

    it('nulls deckRef when none is supplied (never falls back to deckName)', () => {
        const { gameDef } = splitGameDef(composite);
        expect(gameDef.deckRef).toBeNull();
    });

    it('tolerates a null deck', () => {
        const { strippedDeck, gameDef } = splitGameDef(null, 'ref-y');
        expect(strippedDeck).toEqual({});
        expect(gameDef.gameConfig).toEqual({});
        expect(gameDef.deckRef).toBe('ref-y');
    });
});

describe('gameDefTransform.resolveGameConfig', () => {
    it('prefers the persisted game definition config', () => {
        const gameDef = { gameConfig: { narrationEnabled: true, source: 'gamedef' } };
        const deck = { gameConfig: { narrationEnabled: false, source: 'deck' } };
        expect(resolveGameConfig(gameDef, deck)).toEqual(gameDef.gameConfig);
    });

    it('falls back to the inline deck config when the game def is empty', () => {
        const deck = { gameConfig: { announcerEnabled: true } };
        expect(resolveGameConfig({ gameConfig: {} }, deck)).toEqual(deck.gameConfig);
        expect(resolveGameConfig(null, deck)).toEqual(deck.gameConfig);
    });

    it('returns an empty object when neither source has config', () => {
        expect(resolveGameConfig(null, null)).toEqual({});
        expect(resolveGameConfig({ gameConfig: {} }, {})).toEqual({});
    });

    it('round-trips a split back to the original config', () => {
        const original = { narrationEnabled: false, banterLevel: 'moderate' };
        const { strippedDeck, gameDef } = splitGameDef({ deckName: 'RT', gameConfig: original }, 'ref');
        // Deck no longer carries config; game def does — resolve pulls from game def.
        expect(resolveGameConfig(gameDef, strippedDeck)).toEqual(original);
    });
});
