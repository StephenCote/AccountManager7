import { describe, it, expect } from 'vitest';
import { summarize, vectorize, reimage, reimageApparel, memberCloud, adoptCharacter, outfitBuilder } from '../workflows/index.js';

describe('workflow module exports', () => {
    it('should export summarize as a function', () => {
        expect(typeof summarize).toBe('function');
    });

    it('should export vectorize as a function', () => {
        expect(typeof vectorize).toBe('function');
    });

    it('should export reimage as a function', () => {
        expect(typeof reimage).toBe('function');
    });

    it('should export reimageApparel as a function', () => {
        expect(typeof reimageApparel).toBe('function');
    });

    it('should export memberCloud as a function', () => {
        expect(typeof memberCloud).toBe('function');
    });

    it('should export adoptCharacter as a function', () => {
        expect(typeof adoptCharacter).toBe('function');
    });

    it('should export outfitBuilder as a function', () => {
        expect(typeof outfitBuilder).toBe('function');
    });
});

describe('reimage shared utilities', () => {
    it('should export socialSharingMap from reimage', async () => {
        let mod = await import('../workflows/reimage.js');
        expect(mod.socialSharingMap).toBeDefined();
        expect(mod.socialSharingMap['NONE']).toBe('nude');
        expect(mod.socialSharingMap['BASE']).toBe('intimate');
        expect(mod.socialSharingMap['SUIT']).toBe('public');
        expect(mod.socialSharingMap['UNKNOWN']).toBe(null);
    });

    it('should export getOrCreateSharingTag from reimage', async () => {
        let mod = await import('../workflows/reimage.js');
        expect(typeof mod.getOrCreateSharingTag).toBe('function');
    });
});
