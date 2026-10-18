// @vitest-environment jsdom
/**
 * P4-0 (c): pictureBookLocations.js must be loadable without pictureBook.js having been imported
 * first. Before P4-0 the two files imported each other (routes ↔ deriveStageList), so importing the
 * locations module first read `routes` in its temporal dead zone. This file imports nothing from
 * features/ statically and dynamic-imports the locations module FIRST in a fresh module graph.
 */
import { describe, it, expect, vi } from 'vitest';

describe('pictureBookLocations.js loads standalone', () => {
    it('exports routes when imported before pictureBook.js, and the pure helpers come from the workflow module', async () => {
        vi.resetModules();
        const lib = await import('../features/pictureBookLocations.js');
        expect(lib.routes).toBeTruthy();
        expect(Object.keys(lib.routes).length).toBeGreaterThan(0);
        const wf = await import('../workflows/pictureBookWorkflow.js');
        expect(typeof wf.deriveStageList).toBe('function');
        expect(typeof wf.runSummaryText).toBe('function');
        expect(wf.deriveStageList([], null)).toEqual([]);
        const pbf = await import('../features/pictureBook.js');
        expect(pbf.deriveStageList).toBeUndefined();
        expect(pbf.runSummaryText).toBeUndefined();
    });
});
