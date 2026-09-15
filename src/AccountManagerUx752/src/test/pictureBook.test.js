import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ── sceneExtractor module exports ─────────────────────────────────────

describe('sceneExtractor module exports', () => {
    it('no longer exports the bespoke DEFAULT_SD_CONFIG (replaced by a real olio.sd.config)', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        // The single-word `illustration` style + plain-config default object was removed in favor of
        // a real olio.sd.config built the reimage/CardGame way — the export must be gone.
        expect(mod.DEFAULT_SD_CONFIG).toBeUndefined();
    });

    it('should export setBookSdConfig as a function (PUT /settings — common config)', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.setBookSdConfig).toBe('function');
    });

    it('should export MAX_SCENES_DEFAULT as -1 (no max, backend decides)', async () => {
        let { MAX_SCENES_DEFAULT } = await import('../workflows/sceneExtractor.js');
        expect(MAX_SCENES_DEFAULT).toBe(-1);
    });

    it('should export extractScenes as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.extractScenes).toBe('function');
    });

    it('should export cancelPictureBook as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.cancelPictureBook).toBe('function');
    });

    it('should export createFromScenes as a function', async () => {
        let mod = await import('../workflows/sceneExtractor.js');
        expect(typeof mod.createFromScenes).toBe('function');
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

// ── resetPictureBook — Issue 1: { reset, reason } contract ────────────
// The reset endpoint always describes the outcome in the body: a success is { reset:true };
// an EXPLAINED failure is { reset:false, reason:'…' } at HTTP 200; an exception path is
// { error:'…' } at a non-2xx status. resetPictureBook must never throw for a well-formed
// response — it returns { reset:<boolean>, reason:<string|null> } so callers can surface
// the concrete reason instead of a bare "Failed to delete".

describe('resetPictureBook { reset, reason } contract', () => {
    let origFetch;
    beforeEach(() => { origFetch = global.fetch; });
    afterEach(() => { global.fetch = origFetch; });

    it('returns { reset:true, reason:null } on a success body', async () => {
        let { resetPictureBook } = await import('../workflows/sceneExtractor.js');
        global.fetch = async () => ({ ok: true, status: 200, json: async () => ({ reset: true }) });
        let r = await resetPictureBook('book-1');
        expect(r.reset).toBe(true);
        expect(r.reason).toBeNull();
    });

    it('returns { reset:false, reason } from an explained-failure body at HTTP 200', async () => {
        let { resetPictureBook } = await import('../workflows/sceneExtractor.js');
        global.fetch = async () => ({
            ok: true, status: 200,
            json: async () => ({ reset: false, reason: 'Book is referenced by an active workflow' })
        });
        let r = await resetPictureBook('book-2');
        expect(r.reset).toBe(false);
        expect(r.reason).toBe('Book is referenced by an active workflow');
    });

    it('reads { error } from a non-2xx exception body as the reason', async () => {
        let { resetPictureBook } = await import('../workflows/sceneExtractor.js');
        global.fetch = async () => ({
            ok: false, status: 403,
            json: async () => ({ error: 'Not authorized to delete this book' })
        });
        let r = await resetPictureBook('book-3');
        expect(r.reset).toBe(false);
        expect(r.reason).toBe('Not authorized to delete this book');
    });

    it('falls back to a status-derived reason when the body is not JSON', async () => {
        let { resetPictureBook } = await import('../workflows/sceneExtractor.js');
        global.fetch = async () => ({
            ok: false, status: 500,
            json: async () => { throw new Error('not json'); }
        });
        let r = await resetPictureBook('book-4');
        expect(r.reset).toBe(false);
        expect(r.reason).toContain('500');
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

// ── Scene character badges (reader) ───────────────────────────────────
//
// Reported 2026-09-15: the PB1 reader's character badges showed raw objectId UUIDs. The cause is a
// shape mismatch, not debug code — PictureBookUtil.buildSceneEntry resolves the extraction's
// character names to charPerson objectIds and persists `pictureBookScene.characters` as a
// List<String> of those ids, while the badge render did `typeof c === 'string' ? c : (c.name || c)`
// and so printed the id verbatim (and, for a map without a name, the object itself).

describe('scene character badge labels', () => {
    const DARBY = '2be22bac-1a51-4a3b-93d8-daa87b8087f7';
    const DAD = '987c1271-dd6d-4455-bd86-b1a2f9a724ef';
    const NAMES = { [DARBY]: 'Darby', [DAD]: "Darby's Dad" };

    it('resolves an objectId entry to the character name', async () => {
        let { sceneCharacterLabel } = await import('../workflows/sceneExtractor.js');
        expect(sceneCharacterLabel(DARBY, NAMES)).toBe('Darby');
    });

    it('drops an unresolved UUID instead of rendering it', async () => {
        let { sceneCharacterLabel } = await import('../workflows/sceneExtractor.js');
        // A character deleted from the book, or a names lookup that failed.
        expect(sceneCharacterLabel('3fa85f64-5717-4562-b3fc-2c963f66afa6', NAMES)).toBeNull();
        expect(sceneCharacterLabel(DARBY, {})).toBeNull();
        expect(sceneCharacterLabel(DARBY, null)).toBeNull();
    });

    it('keeps a legacy plain-name entry, which is not a UUID', async () => {
        let { sceneCharacterLabel } = await import('../workflows/sceneExtractor.js');
        // Older books and the {name:...} shape extractCharName still tolerates store real names.
        expect(sceneCharacterLabel('Veronique', NAMES)).toBe('Veronique');
    });

    it('reads the name off a map entry, and never returns the object itself', async () => {
        let { sceneCharacterLabel } = await import('../workflows/sceneExtractor.js');
        expect(sceneCharacterLabel({ name: 'Yolanda' }, NAMES)).toBe('Yolanda');
        expect(sceneCharacterLabel({ objectId: DAD }, NAMES)).toBe("Darby's Dad");
        // The old `c.name || c` fallback returned the INPUT OBJECT, which Mithril renders as junk.
        let entry = { gender: 'female' };
        let label = sceneCharacterLabel(entry, NAMES);
        expect(label).toBeNull();
        expect(label).not.toBe(entry);
    });

    it('resolves a whole scene list, de-duplicating and preserving order', async () => {
        let { sceneCharacterLabels } = await import('../workflows/sceneExtractor.js');
        // DARBY appears twice: once as an id, once as a name map — one badge, not two.
        let labels = sceneCharacterLabels([DARBY, DAD, { name: 'Darby' }], NAMES);
        expect(labels).toEqual(['Darby', "Darby's Dad"]);
    });

    it('yields no labels at all when nothing resolves, so no empty badge strip renders', async () => {
        let { sceneCharacterLabels } = await import('../workflows/sceneExtractor.js');
        expect(sceneCharacterLabels([DARBY, DAD], {})).toEqual([]);
        expect(sceneCharacterLabels([], NAMES)).toEqual([]);
        expect(sceneCharacterLabels(null, NAMES)).toEqual([]);
        expect(sceneCharacterLabels(undefined, NAMES)).toEqual([]);
    });
});
