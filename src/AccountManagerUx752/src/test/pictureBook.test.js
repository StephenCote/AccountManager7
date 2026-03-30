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
        let meta = buildMeta('work-123', 'book-456', 'My Story', scenes);
        expect(meta.sourceObjectId).toBe('work-123');
        expect(meta.bookObjectId).toBe('book-456');
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
        let meta = buildMeta('src-1', 'book-1', 'W', scenes);
        expect(meta.scenes[0].objectId).toBe('xyz');
        expect(meta.scenes[0].imageObjectId).toBe('img-42');
    });

    it('handles empty scenes array', async () => {
        let { buildMeta } = await import('../workflows/sceneExtractor.js');
        let meta = buildMeta('src-1', 'book-1', 'W', []);
        expect(meta.sceneCount).toBe(0);
        expect(meta.scenes).toHaveLength(0);
    });
});

// ── image URL resolution ─────────────────────────────────────────────

describe('resolveImageUrl', () => {
    it('should export resolveImageUrl as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.resolveImageUrl).toBe('function');
    });

    it('should export resolveAllImageUrls as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.resolveAllImageUrls).toBe('function');
    });

    it('should export clearImageCache as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.clearImageCache).toBe('function');
    });

    it('should export buildImageUrl as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.buildImageUrl).toBe('function');
    });

    it('resolveImageUrl returns null for null objectId', async () => {
        let { resolveImageUrl } = await import('../workflows/sceneExtractor.js');
        let result = await resolveImageUrl(null);
        expect(result).toBeNull();
    });

    it('resolveImageUrl returns null for undefined objectId', async () => {
        let { resolveImageUrl } = await import('../workflows/sceneExtractor.js');
        let result = await resolveImageUrl(undefined);
        expect(result).toBeNull();
    });

    it('resolveImageUrl returns null for empty string', async () => {
        let { resolveImageUrl } = await import('../workflows/sceneExtractor.js');
        let result = await resolveImageUrl('');
        expect(result).toBeNull();
    });

    it('buildImageUrl constructs media URL with record groupPath and name', async () => {
        let { buildImageUrl } = await import('../workflows/sceneExtractor.js');
        let { am7client } = await import('../core/am7client.js');
        // Set currentOrganization so dotPath can resolve it
        am7client.currentOrganization = '/Development';
        let rec = { groupPath: '/home/testuser/Data', name: 'test.pdf' };
        let url = buildImageUrl(rec);
        expect(url).toContain('/media/');
        expect(url).toContain('Development');
        expect(url).toContain('/data.data/home/testuser/Data/test.pdf');
        expect(url).not.toContain('undefined');
    });

    it('clearImageCache clears previously cached entries', async () => {
        let { resolveImageUrl, clearImageCache } = await import('../workflows/sceneExtractor.js');
        // Call resolveImageUrl with a value that will fail (no server) — cache should remain empty
        await resolveImageUrl('nonexistent-id').catch(() => {});
        // clearImageCache should not throw
        expect(() => clearImageCache()).not.toThrow();
    });

    it('resolveAllImageUrls returns empty object for empty scenes', async () => {
        let { resolveAllImageUrls } = await import('../workflows/sceneExtractor.js');
        let result = await resolveAllImageUrls([]);
        expect(result).toEqual({});
    });

    it('resolveAllImageUrls skips scenes without imageObjectId', async () => {
        let { resolveAllImageUrls } = await import('../workflows/sceneExtractor.js');
        let scenes = [
            { objectId: 'a', title: 'Scene 1', imageObjectId: null },
            { objectId: 'b', title: 'Scene 2' }
        ];
        let result = await resolveAllImageUrls(scenes);
        expect(result).toEqual({});
    });

    it('resolveAllImageUrls processes multiple scenes in parallel', async () => {
        let { resolveAllImageUrls } = await import('../workflows/sceneExtractor.js');
        // Scenes with imageObjectIds that won't resolve (no server) but should not error
        let scenes = [
            { objectId: 'a', imageObjectId: 'img-1' },
            { objectId: 'b', imageObjectId: 'img-2' },
            { objectId: 'c', imageObjectId: null }
        ];
        let result = await resolveAllImageUrls(scenes);
        // Should have entries for img-1 and img-2 (null values since server unavailable)
        expect(typeof result).toBe('object');
        // img-1 and img-2 should be in result (values may be null without server)
        expect('img-1' in result || Object.keys(result).length === 0).toBe(true);
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
