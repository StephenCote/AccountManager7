/**
 * Bug Fix Sprint #5 — Issues 1, 4, 13 (PB2/ChapBook context)
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

// ── Issue 4: list cache count invalidation ───────────────────────────────────

describe('Issue 4: am7client.clearCache also clears type-Count cache', () => {
    it('clearCache deletes the type-Count in-memory cache key', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'am7client.js'), 'utf-8');
        // Find the clearCache function body (the else branch that handles named type)
        let clearIdx = src.indexOf('function clearCache(');
        expect(clearIdx).toBeGreaterThan(-1);
        // Extract a reasonable window around the function
        let block = src.substring(clearIdx, clearIdx + 1500);
        // Must delete both the type cache AND the type-Count cache
        expect(block).toContain('delete cache[sType]');
        expect(block).toContain('delete cache[sType + "-Count"]');
    });

    it('search() caches counts at type+"-Count" key', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'am7client.js'), 'utf-8');
        // The search function sets type = q.entity.type + (bCount ? "-Count" : "")
        expect(src).toContain('"-Count"');
        // And addToCache uses that type as the cache key
        let addIdx = src.indexOf('addToCache(type,"GET"');
        expect(addIdx).toBeGreaterThan(-1);
    });
});

// ── Issue 1: picker navigation resets state correctly ────────────────────────

describe('Issue 1: picker navigation calls update(), not just m.redraw()', () => {
    it('navInPlace calls update(fakeVnode) in picker mode instead of just m.redraw()', () => {
        const src = readFileSync(resolve(__dirname, '..', 'views', 'list.js'), 'utf-8');
        let navIdx = src.indexOf('function navInPlace(');
        expect(navIdx).toBeGreaterThan(-1);
        let block = src.substring(navIdx, navIdx + 1200);
        // Must call update() in picker mode — not just m.redraw()
        expect(block).toContain('pickerMode');
        expect(block).toContain('update(fakeVnode)');
        expect(block).toContain('initParams(fakeVnode)');
    });

    it('pickerGroupNavMode state variable exists in list.js for group-nav in picker mode', () => {
        const src = readFileSync(resolve(__dirname, '..', 'views', 'list.js'), 'utf-8');
        expect(src).toContain('pickerGroupNavMode');
        // Must be set to false on navigateDown (entering a folder resets to pickerType)
        let downIdx = src.indexOf('pickerGroupNavMode = false');
        expect(downIdx).toBeGreaterThan(-1);
    });
});

// ── Issue 13: PictureBook failed character warnings ──────────────────────────

describe('Issue 13: pictureBook.js surfaces failedCharacters after createFromScenes', () => {
    it('shows toast warning when meta.failedCharacters is non-empty', () => {
        const src = readFileSync(resolve(__dirname, '..', 'workflows', 'pictureBook.js'), 'utf-8');
        // After createFromScenes, failedCharacters must be checked and toasted
        expect(src).toContain('meta.failedCharacters');
        expect(src).toContain('meta.failedExtractions');
        // Should include a warning toast, not just silent pass-through
        let checkIdx = src.indexOf('failedCharacters.length');
        expect(checkIdx).toBeGreaterThan(-1);
        let block = src.substring(checkIdx, checkIdx + 200);
        expect(block).toContain('page.toast');
    });

    it('createFromScenes catch block uses safe error extraction', () => {
        const src = readFileSync(resolve(__dirname, '..', 'workflows', 'pictureBook.js'), 'utf-8');
        // The catch block must safely handle non-Error objects (e.message may be undefined)
        expect(src).toContain("e?.message || (typeof e === 'string' ? e : null) || 'Unknown error'");
    });

    it('initCharacterManager is outside the main try block (non-fatal)', () => {
        const src = readFileSync(resolve(__dirname, '..', 'workflows', 'pictureBook.js'), 'utf-8');
        // initCharacterManager is in its own try/catch so it doesn't mask a successful book creation
        let initIdx = src.indexOf('initCharacterManager(bookObjectId)');
        expect(initIdx).toBeGreaterThan(-1);
        // Should be inside a try/catch that's separate (non-fatal)
        let block = src.substring(initIdx - 200, initIdx + 200);
        expect(block).toContain('try {');
        expect(block).toContain('} catch');
    });
});
