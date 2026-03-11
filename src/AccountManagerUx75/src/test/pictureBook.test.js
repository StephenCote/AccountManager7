import { describe, it, expect, vi, beforeEach } from 'vitest';

// ── sceneExtractor module exports ─────────────────────────────────────

describe('sceneExtractor module exports', () => {
    it('should export DEFAULT_SD_CONFIG', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(mod.DEFAULT_SD_CONFIG).toBeDefined();
        expect(typeof mod.DEFAULT_SD_CONFIG).toBe('object');
    });

    it('DEFAULT_SD_CONFIG has required fields', async () => {
        let { DEFAULT_SD_CONFIG } = await import('../workflows/sceneExtractor.js');
        expect(DEFAULT_SD_CONFIG.steps).toBe(20);
        expect(DEFAULT_SD_CONFIG.refinerSteps).toBe(20);
        expect(DEFAULT_SD_CONFIG.cfg).toBe(5);
        expect(DEFAULT_SD_CONFIG.hires).toBe(false);
        expect(DEFAULT_SD_CONFIG.style).toBe('illustration');
    });

    it('should export MAX_SCENES_DEFAULT as 3', async () => {
        let { MAX_SCENES_DEFAULT } = await import('../workflows/sceneExtractor.js');
        expect(MAX_SCENES_DEFAULT).toBe(3);
    });

    it('should export extractScenes as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.extractScenes).toBe('function');
    });

    it('should export fullExtract as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.fullExtract).toBe('function');
    });

    it('should export generateSceneImage as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.generateSceneImage).toBe('function');
    });

    it('should export regenerateBlurb as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.regenerateBlurb).toBe('function');
    });

    it('should export loadPictureBook as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.loadPictureBook).toBe('function');
    });

    it('should export reorderScenes as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.reorderScenes).toBe('function');
    });

    it('should export resetPictureBook as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.resetPictureBook).toBe('function');
    });

    it('should export buildMeta as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.buildMeta).toBe('function');
    });
});

// ── buildMeta utility ─────────────────────────────────────────────────

describe('buildMeta', () => {
    it('builds meta structure with required fields', async () => {
        let { buildMeta } = await import('../workflows/sceneExtractor.js');
        let scenes = [
            { objectId: 'abc', title: 'Scene 1', imageObjectId: null, characters: ['Alice'] },
            { objectId: 'def', title: 'Scene 2', imageObjectId: 'img-1', characters: ['Bob'] }
        ];
        let meta = buildMeta('work-123', 'My Story', scenes);
        expect(meta.workObjectId).toBe('work-123');
        expect(meta.workName).toBe('My Story');
        expect(meta.sceneCount).toBe(2);
        expect(meta.scenes).toHaveLength(2);
        expect(meta.scenes[0].index).toBe(0);
        expect(meta.scenes[1].index).toBe(1);
        expect(meta.extractedAt).toBeDefined();
        expect(meta.generatedAt).toBeNull();
    });

    it('preserves scene objectId and imageObjectId', async () => {
        let { buildMeta } = await import('../workflows/sceneExtractor.js');
        let scenes = [{ objectId: 'xyz', title: 'T', imageObjectId: 'img-42', characters: [] }];
        let meta = buildMeta('w', 'W', scenes);
        expect(meta.scenes[0].objectId).toBe('xyz');
        expect(meta.scenes[0].imageObjectId).toBe('img-42');
    });

    it('handles empty scenes array', async () => {
        let { buildMeta } = await import('../workflows/sceneExtractor.js');
        let meta = buildMeta('w', 'W', []);
        expect(meta.sceneCount).toBe(0);
        expect(meta.scenes).toHaveLength(0);
    });
});

// ── pictureBook workflow export ───────────────────────────────────────

describe('pictureBook workflow export', () => {
    it('should export pictureBook as a function', async () => {
        let mod = await import('../workflows/pictureBook.js');
        expect(typeof mod.pictureBook).toBe('function');
    });

    it('pictureBook is also the default export', async () => {
        let mod = await import('../workflows/pictureBook.js');
        expect(mod.default).toBe(mod.pictureBook);
    });
});

// ── features manifest ─────────────────────────────────────────────────

describe('pictureBook feature manifest', () => {
    it('features.js includes pictureBook entry', async () => {
        let { features } = await import('../features.js');
        expect(features.pictureBook).toBeDefined();
    });

    it('pictureBook feature has correct id', async () => {
        let { features } = await import('../features.js');
        expect(features.pictureBook.id).toBe('pictureBook');
    });

    it('pictureBook depends on core and chat', async () => {
        let { features } = await import('../features.js');
        expect(features.pictureBook.deps).toContain('core');
        expect(features.pictureBook.deps).toContain('chat');
    });

    it('pictureBook has aside menu item', async () => {
        let { features } = await import('../features.js');
        let mi = features.pictureBook.menuItems;
        expect(Array.isArray(mi)).toBe(true);
        expect(mi[0].section).toBe('aside');
        expect(mi[0].route).toBe('/picture-book');
    });

    it('pictureBook is not adminOnly', async () => {
        let { features } = await import('../features.js');
        let mi = features.pictureBook.menuItems;
        expect(mi[0].adminOnly).toBeFalsy();
    });

    it('pictureBook has auto_stories icon', async () => {
        let { features } = await import('../features.js');
        expect(features.pictureBook.menuItems[0].icon).toBe('auto_stories');
    });

    it('pictureBook has lazy routes import', async () => {
        let { features } = await import('../features.js');
        expect(typeof features.pictureBook.routes).toBe('function');
    });
});

// ── pictureBook feature routes (lazy import check — no DOM import) ───

describe('pictureBook feature routes shape', () => {
    it('features.js pictureBook routes factory returns a Promise', async () => {
        let { features } = await import('../features.js');
        // routes is a lazy import function — calling it returns a thenable
        let result = features.pictureBook.routes();
        expect(typeof result.then).toBe('function');
    });
});
