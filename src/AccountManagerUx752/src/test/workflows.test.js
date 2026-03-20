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

describe('sdConfig utility', () => {
    it('should export expected API surface', async () => {
        let { am7sd } = await import('../components/sdConfig.js');
        expect(typeof am7sd.fetchTemplate).toBe('function');
        expect(typeof am7sd.loadConfig).toBe('function');
        expect(typeof am7sd.saveConfig).toBe('function');
        expect(typeof am7sd.applyConfig).toBe('function');
        expect(typeof am7sd.fillStyleDefaults).toBe('function');
        expect(typeof am7sd.applyOverrides).toBe('function');
        expect(typeof am7sd.buildEntity).toBe('function');
        expect(typeof am7sd.fetchModels).toBe('function');
    });

    it('applyConfig should skip excluded fields (seed, groupId, etc.)', async () => {
        let { am7sd } = await import('../components/sdConfig.js');
        let cinst = { api: { steps: (v) => { cinst._steps = v; }, cfg: (v) => { cinst._cfg = v; }, seed: (v) => { cinst._seed = v; } }, _steps: 0, _cfg: 0, _seed: 0 };
        am7sd.applyConfig(cinst, { steps: 30, cfg: 7, seed: 12345, groupId: 999 });
        expect(cinst._steps).toBe(30);
        expect(cinst._cfg).toBe(7);
        // seed is in APPLY_EXCLUDE list, should NOT be applied
        expect(cinst._seed).toBe(0);
    });

    it('fillStyleDefaults populates photograph fields', async () => {
        let { am7sd } = await import('../components/sdConfig.js');
        let entity = { style: 'photograph' };
        am7sd.fillStyleDefaults(entity);
        expect(entity.stillCamera).toBeDefined();
        expect(entity.film).toBeDefined();
        expect(entity.lens).toBeDefined();
        expect(entity.colorProcess).toBeDefined();
        expect(entity.photographer).toBeDefined();
    });

    it('fillStyleDefaults does not overwrite existing values', async () => {
        let { am7sd } = await import('../components/sdConfig.js');
        let entity = { style: 'photograph', stillCamera: 'MyCamera' };
        am7sd.fillStyleDefaults(entity);
        expect(entity.stillCamera).toBe('MyCamera');
    });

    it('applyOverrides sets hires field', async () => {
        let { am7sd } = await import('../components/sdConfig.js');
        let entity = { hires: true };
        am7sd.applyOverrides(entity, { hires: false });
        expect(entity.hires).toBe(false);
    });
});

describe('reimageApparel loadConfig is async', () => {
    it('reimageApparel source uses await on loadConfig', async () => {
        let src = await import('../workflows/reimageApparel.js?raw');
        let code = typeof src.default === 'string' ? src.default : '';
        if (!code) {
            let mod = await import('../workflows/reimageApparel.js');
            expect(typeof mod.reimageApparel).toBe('function');
            return;
        }
        // Every loadConfig call must be preceded by await
        let loadCalls = code.match(/am7sd\.loadConfig/g) || [];
        let awaitCalls = code.match(/await am7sd\.loadConfig/g) || [];
        expect(loadCalls.length).toBeGreaterThan(0);
        expect(awaitCalls.length).toBe(loadCalls.length);
    });
});
