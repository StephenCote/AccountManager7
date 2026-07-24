/**
 * KI-31 follow-up ("prompts are still completely broken") — client-side half of the fix.
 *
 * Root cause: pictureBook.js's "single prompt template" mode applied ONE user-selected template
 * (resolved against an extraction-shaped default) to EVERY LLM operation, including
 * landscape-prompt/scene-image-prompt calls whose real templates need completely different vars
 * (setting/action/mood/charNarrations, not {text}/{count}). The mismatched template's placeholders
 * went unsubstituted server-side, and the LLM responded with a conversational clarifying question
 * that got cached and reused forever (see aiDocs/KnownIssues.md's KI-31 entry for the full
 * server-side root cause and fix).
 *
 * getPromptTemplate(key) is the single chokepoint that decides which override (if any) is sent for
 * a given prompt "slot" — this drives it directly (a real, exported function), not a reimplementation.
 */
import { describe, it, expect } from 'vitest';

describe('pictureBook.js getPromptTemplate — "single" mode must not leak into image-prompt slots (KI-31 follow-up)', () => {
    it('single mode applies the user\'s template to extraction-shaped slots', async () => {
        const { getPromptTemplate, __setPromptStateForTest } = await import('../workflows/pictureBook.js');
        __setPromptStateForTest('single', { name: 'my.custom.extraction.template', objectId: 'x1' }, null);

        expect(getPromptTemplate('extractScenes')).toBe('my.custom.extraction.template');
        expect(getPromptTemplate('extractChunk')).toBe('my.custom.extraction.template');
        expect(getPromptTemplate('extractCharacter')).toBe('my.custom.extraction.template');
        expect(getPromptTemplate('sceneBlurb')).toBe('my.custom.extraction.template');
    });

    it('single mode returns null (server default) for landscapePrompt/sceneImagePrompt — the exact bug class fixed here', async () => {
        const { getPromptTemplate, __setPromptStateForTest } = await import('../workflows/pictureBook.js');
        __setPromptStateForTest('single', { name: 'pictureBook.extract-scenes', objectId: 'x2' }, null);

        // This is literally the reported failure: an extraction template silently applied to an
        // image-prompt call. It must now be refused client-side (null = "use the server default"),
        // not forwarded.
        expect(getPromptTemplate('landscapePrompt')).toBeNull();
        expect(getPromptTemplate('sceneImagePrompt')).toBeNull();
    });

    it('per-prompt mode is unaffected — each slot uses its own explicit selection, including landscapePrompt', async () => {
        const { getPromptTemplate, __setPromptStateForTest } = await import('../workflows/pictureBook.js');
        __setPromptStateForTest('per-prompt', null, {
            extractScenes: { name: 'my.extract.scenes', objectId: 'a' },
            landscapePrompt: { name: 'my.landscape', objectId: 'b' }
        });

        expect(getPromptTemplate('extractScenes')).toBe('my.extract.scenes');
        expect(getPromptTemplate('landscapePrompt')).toBe('my.landscape');
        // A slot with no explicit per-prompt selection yet falls through to null (server default),
        // never to another slot's template.
        expect(getPromptTemplate('sceneBlurb')).toBeNull();
    });

    it('single mode with no template selected yet returns null for every slot (no crash on unresolved default)', async () => {
        const { getPromptTemplate, __setPromptStateForTest } = await import('../workflows/pictureBook.js');
        __setPromptStateForTest('single', null, null);

        expect(getPromptTemplate('extractScenes')).toBeNull();
        expect(getPromptTemplate('landscapePrompt')).toBeNull();
    });
});
