// @vitest-environment jsdom
/**
 * Gap 6 — ChapBook per-scene serial render loop.
 *
 * These tests exercise the REAL exported `renderScenesSerially` from features/chapBook.js — the exact
 * dependency-injected core the three ChapBook Render buttons (PoemLibrary / ChapBookReader /
 * ChapBookReview) drive in production, so this is genuine behavioral coverage, not a re-implementation.
 *
 * The loop mirrors PB2's canonical `doGenerateAll` (workflows/pictureBook.js): iterate scenes ONE AT A
 * TIME (never parallel — avoids hammering the shared SD server), cool down between scenes (injectable
 * `sleep`, stubbed to a no-op here so tests run instantly), record per-scene progress, surface each
 * image as its call returns, and aggregate rendered/failed counts. What we assert:
 *   - serial ordering: generateOne is called once per scene, in order, never concurrently
 *   - the cooldown fires BETWEEN scenes only (total-1 times), never before the first
 *   - onProgress emits 'generating' before each call, then 'done' (success) or 'error' (throw)
 *   - onImage fires with (oid, imageObjectId) only when a result carries an imageObjectId
 *   - rendered/failed/total aggregation is correct across mixed success/failure/no-op results
 */
import { describe, it, expect, vi } from 'vitest';

describe('renderScenesSerially (Gap 6 — per-scene serial render core)', () => {
    it('calls generateOne once per scene, in order, one at a time (never parallel)', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let order = [];
        let inFlight = 0;
        let maxInFlight = 0;
        let generateOne = async (oid) => {
            inFlight++;
            maxInFlight = Math.max(maxInFlight, inFlight);
            order.push(oid);
            await Promise.resolve(); // yield — a parallel impl would overlap here
            inFlight--;
            return { imageObjectId: 'img-' + oid, rendered: true };
        };
        let result = await renderScenesSerially(['s1', 's2', 's3'], generateOne, { sleep: async () => {} });
        expect(order).toEqual(['s1', 's2', 's3']);
        expect(maxInFlight).toBe(1);
        expect(result).toEqual({ rendered: 3, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 3 });
    });

    it('cools down BETWEEN scenes only — sleep fires total-1 times, never before the first', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let sleep = vi.fn(async () => {});
        // Track that no sleep happened before the very first generateOne.
        let sleepBeforeFirst = false;
        let firstSeen = false;
        let generateOne = async (oid) => {
            if (!firstSeen) { firstSeen = true; if (sleep.mock.calls.length > 0) sleepBeforeFirst = true; }
            return { imageObjectId: 'img-' + oid, rendered: true };
        };
        await renderScenesSerially(['a', 'b', 'c', 'd'], generateOne, { sleep });
        expect(sleepBeforeFirst).toBe(false);
        expect(sleep).toHaveBeenCalledTimes(3); // 4 scenes → 3 gaps
    });

    it('a single scene renders with no cooldown at all', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let sleep = vi.fn(async () => {});
        let result = await renderScenesSerially(['only'], async (oid) => ({ imageObjectId: 'img', rendered: true }), { sleep });
        expect(sleep).not.toHaveBeenCalled();
        expect(result).toEqual({ rendered: 1, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1 });
    });

    it('emits generating→done progress per scene and surfaces each image as it returns', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let progress = [];
        let images = [];
        let generateOne = async (oid) => ({ imageObjectId: 'img-' + oid, rendered: true });
        await renderScenesSerially(['x', 'y'], generateOne, {
            sleep: async () => {},
            onProgress: (oid, status, done, total) => progress.push([oid, status, done, total]),
            onImage: (oid, imageObjectId) => images.push([oid, imageObjectId])
        });
        // 'generating' must precede 'done' for each scene, and done/total must advance.
        expect(progress).toEqual([
            ['x', 'generating', 0, 2],
            ['x', 'done', 1, 2],
            ['y', 'generating', 1, 2],
            ['y', 'done', 2, 2]
        ]);
        expect(images).toEqual([['x', 'img-x'], ['y', 'img-y']]);
    });

    it('a thrown generateOne counts as failed, emits error progress, and does NOT stop the loop', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let progress = [];
        let images = [];
        let generateOne = async (oid) => {
            if (oid === 's2') throw new Error('SD server hiccup');
            return { imageObjectId: 'img-' + oid, rendered: true };
        };
        let result = await renderScenesSerially(['s1', 's2', 's3'], generateOne, {
            sleep: async () => {},
            onProgress: (oid, status) => progress.push([oid, status]),
            onImage: (oid, imageObjectId) => images.push([oid, imageObjectId])
        });
        // s2 fails but s3 still runs — the loop is resilient.
        expect(result).toEqual({ rendered: 2, failed: 1, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 3 });
        expect(progress).toEqual([
            ['s1', 'generating'], ['s1', 'done'],
            ['s2', 'generating'], ['s2', 'error'],
            ['s3', 'generating'], ['s3', 'done']
        ]);
        // No image surfaced for the failed scene.
        expect(images).toEqual([['s1', 'img-s1'], ['s3', 'img-s3']]);
    });

    it('a rendered:false / no-image / not-skipped result counts as none of rendered/skipped/failed, and surfaces no image', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let images = [];
        let generateOne = async (oid) => ({ imageObjectId: null, rendered: false });
        let result = await renderScenesSerially(['noop'], generateOne, {
            sleep: async () => {},
            onImage: (oid, imageObjectId) => images.push([oid, imageObjectId])
        });
        expect(result).toEqual({ rendered: 0, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1 });
        expect(images).toEqual([]);
    });

    it('an empty scene list is a clean no-op (never calls generateOne or sleep)', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let generateOne = vi.fn(async () => ({ imageObjectId: 'x', rendered: true }));
        let sleep = vi.fn(async () => {});
        let result = await renderScenesSerially([], generateOne, { sleep });
        expect(generateOne).not.toHaveBeenCalled();
        expect(sleep).not.toHaveBeenCalled();
        expect(result).toEqual({ rendered: 0, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 0 });
    });

    it('tolerates absent hooks — no onProgress/onImage/sleep still completes and aggregates', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let generateOne = async (oid) => ({ imageObjectId: 'img-' + oid, rendered: true });
        // No hooks object at all.
        let result = await renderScenesSerially(['a', 'b'], generateOne);
        expect(result).toEqual({ rendered: 2, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 2 });
    });
});

describe('renderScenesSerially — skip tally (un-prompted scenes)', () => {
    it('tallies rendered / skipped / failed independently across a mixed batch', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        // s1 renders, s2 skipped (un-prompted, no image, NOT a failure), s3 throws (failure),
        // s4 renders. Confirms the three counters are driven independently and none is conflated.
        let generateOne = async (oid) => {
            if (oid === 's2') return { imageObjectId: null, rendered: false, skipped: true };
            if (oid === 's3') throw new Error('SD server hiccup');
            return { imageObjectId: 'img-' + oid, rendered: true, skipped: false };
        };
        let result = await renderScenesSerially(['s1', 's2', 's3', 's4'], generateOne, { sleep: async () => {} });
        expect(result).toEqual({ rendered: 2, failed: 1, skipped: 1, llmUnavailable: 0, llmDegraded: 0, total: 4 });
    });

    it('a skipped scene surfaces NO image and is not counted as rendered or failed', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let images = [];
        let generateOne = async (oid) => ({ imageObjectId: null, rendered: false, skipped: true });
        let result = await renderScenesSerially(['skip1', 'skip2'], generateOne, {
            sleep: async () => {},
            onImage: (oid, imageObjectId) => images.push([oid, imageObjectId])
        });
        expect(result).toEqual({ rendered: 0, failed: 0, skipped: 2, llmUnavailable: 0, llmDegraded: 0, total: 2 });
        expect(images).toEqual([]);
    });

    it('rendered takes precedence over a stray skipped flag on the same result (never double-counts)', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let generateOne = async (oid) => ({ imageObjectId: 'img', rendered: true, skipped: true });
        let result = await renderScenesSerially(['s1'], generateOne, { sleep: async () => {} });
        expect(result).toEqual({ rendered: 1, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1 });
    });
});

describe('renderResultMessage (skip-aware render toast text)', () => {
    it('keeps the original success wording when nothing was skipped or failed', async () => {
        let { renderResultMessage } = await import('../features/chapBook.js');
        expect(renderResultMessage({ rendered: 3, skipped: 0, failed: 0, total: 3 }))
            .toBe('Render complete: 3 scene(s) generated');
    });

    it('adds only the skipped clause when scenes were skipped but none failed', async () => {
        let { renderResultMessage } = await import('../features/chapBook.js');
        expect(renderResultMessage({ rendered: 2, skipped: 1, failed: 0, total: 3 }))
            .toBe('Render complete: 2 generated, 1 skipped (need prompts)');
    });

    it('adds only the failed clause when scenes failed but none were skipped', async () => {
        let { renderResultMessage } = await import('../features/chapBook.js');
        expect(renderResultMessage({ rendered: 2, skipped: 0, failed: 1, total: 3 }))
            .toBe('Render complete: 2 generated, 1 failed');
    });

    it('includes both clauses, skipped before failed, when both occur', async () => {
        let { renderResultMessage } = await import('../features/chapBook.js');
        expect(renderResultMessage({ rendered: 1, skipped: 2, failed: 3, total: 6 }))
            .toBe('Render complete: 1 generated, 2 skipped (need prompts), 3 failed');
    });

    it('tolerates a partial/absent result object', async () => {
        let { renderResultMessage } = await import('../features/chapBook.js');
        expect(renderResultMessage(undefined)).toBe('Render complete: 0 scene(s) generated');
    });
});

describe('isSceneUnprompted (review-card "Needs prompt" detection)', () => {
    it('flags an image-less scene with a blank sdPrompt', async () => {
        let { isSceneUnprompted } = await import('../features/chapBook.js');
        expect(isSceneUnprompted({ imageObjectId: null, sdPrompt: '' })).toBe(true);
        expect(isSceneUnprompted({ imageObjectId: null, sdPrompt: '   ' })).toBe(true);
        expect(isSceneUnprompted({ imageObjectId: null })).toBe(true);
    });

    it('flags an image-less scene whose sdPrompt is the "landscape, " fallback shape', async () => {
        let { isSceneUnprompted } = await import('../features/chapBook.js');
        expect(isSceneUnprompted({
            imageObjectId: null,
            sdPrompt: 'landscape, moonlit shore, poetic atmosphere, painterly, soft light'
        })).toBe(true);
    });

    it('does NOT flag a scene that already has an image, regardless of prompt shape', async () => {
        let { isSceneUnprompted } = await import('../features/chapBook.js');
        expect(isSceneUnprompted({ imageObjectId: 'img-123', sdPrompt: '' })).toBe(false);
        expect(isSceneUnprompted({ imageObjectId: 'img-123', sdPrompt: 'landscape, foo' })).toBe(false);
    });

    it('does NOT flag an image-less scene that carries a genuine (non-fallback) LLM prompt', async () => {
        let { isSceneUnprompted } = await import('../features/chapBook.js');
        expect(isSceneUnprompted({
            imageObjectId: null,
            sdPrompt: 'A windswept clifftop at dusk, gulls wheeling, oil-painting style'
        })).toBe(false);
    });

    it('is null/undefined safe', async () => {
        let { isSceneUnprompted } = await import('../features/chapBook.js');
        expect(isSceneUnprompted(null)).toBe(false);
        expect(isSceneUnprompted(undefined)).toBe(false);
    });
});

// ---------------------------------------------------------------------------------------------
// LLM-unavailable signal (this fix). The backend now returns two extra per-scene fields —
// llmUnavailable (a HARD chat-config/LLM-infra fault, NOT a normal per-stanza soft refusal) and
// llmDegraded (rendered on the STORED prompt because that step was down). The Ux must surface a
// DISTINCT signal for those, clearly different from the benign "un-prompted / run Analyze" skip and
// from plain success. sceneLlmSignal is the exact per-scene branch doRegenerateScene drives.
// ---------------------------------------------------------------------------------------------
describe('sceneLlmSignal (per-scene distinct LLM-unavailable branch — the 4 response shapes)', () => {
    it('SHAPE 1 — degraded render (rendered on stored prompt) → a distinct WARNING', async () => {
        let { sceneLlmSignal } = await import('../features/chapBook.js');
        let sig = sceneLlmSignal({ imageObjectId: 'img-1', rendered: true, skipped: false, llmUnavailable: true, llmDegraded: true });
        expect(sig).not.toBeNull();
        expect(sig.level).toBe('warn');
        expect(sig.message).toBe('Rendered using the stored prompt — the LLM prompt step was unavailable (no usable chat config or the LLM is unreachable).');
    });

    it('SHAPE 2 — LLM unavailable + skipped (not rendered) → a distinct ERROR', async () => {
        let { sceneLlmSignal } = await import('../features/chapBook.js');
        let sig = sceneLlmSignal({ imageObjectId: null, rendered: false, skipped: true, llmUnavailable: true, llmDegraded: false });
        expect(sig).not.toBeNull();
        expect(sig.level).toBe('error');
        expect(sig.message).toBe('LLM prompt step unavailable (no usable chat config or the LLM is unreachable) — scene not rendered.');
    });

    it('SHAPE 3 — benign un-prompted skip (llmUnavailable false) → NO signal (existing message kept)', async () => {
        let { sceneLlmSignal } = await import('../features/chapBook.js');
        expect(sceneLlmSignal({ imageObjectId: null, rendered: false, skipped: true, llmUnavailable: false, llmDegraded: false })).toBeNull();
    });

    it('SHAPE 4 — plain success (rendered, no LLM flags) → NO signal (existing success kept)', async () => {
        let { sceneLlmSignal } = await import('../features/chapBook.js');
        expect(sceneLlmSignal({ imageObjectId: 'img-9', rendered: true, skipped: false, llmUnavailable: false, llmDegraded: false })).toBeNull();
    });

    it('the two distinct hard-fault messages differ from each other and from the benign skip wording', async () => {
        let { sceneLlmSignal } = await import('../features/chapBook.js');
        let degraded = sceneLlmSignal({ rendered: true, llmDegraded: true, llmUnavailable: true }).message;
        let unavailable = sceneLlmSignal({ skipped: true, llmUnavailable: true }).message;
        let benignSkipWording = 'Still no usable prompt — Analyze the poem or edit the stanza, then regenerate';
        expect(degraded).not.toBe(unavailable);
        expect(degraded).not.toBe(benignSkipWording);
        expect(unavailable).not.toBe(benignSkipWording);
    });

    it('is null/partial-object safe', async () => {
        let { sceneLlmSignal } = await import('../features/chapBook.js');
        expect(sceneLlmSignal(null)).toBeNull();
        expect(sceneLlmSignal(undefined)).toBeNull();
        expect(sceneLlmSignal({})).toBeNull();
    });
});

describe('renderScenesSerially — LLM-unavailable tally (bulk aggregation)', () => {
    it('counts a hard skip under llmUnavailable ONLY — never as a benign "need prompts" skip', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let generateOne = async () => ({ imageObjectId: null, rendered: false, skipped: true, llmUnavailable: true, llmDegraded: false });
        let result = await renderScenesSerially(['s1'], generateOne, { sleep: async () => {} });
        expect(result).toEqual({ rendered: 0, failed: 0, skipped: 0, llmUnavailable: 1, llmDegraded: 0, total: 1 });
    });

    it('counts a degraded render under BOTH rendered and llmUnavailable (and llmDegraded)', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let generateOne = async (oid) => ({ imageObjectId: 'img-' + oid, rendered: true, skipped: false, llmUnavailable: true, llmDegraded: true });
        let result = await renderScenesSerially(['s1'], generateOne, { sleep: async () => {} });
        expect(result).toEqual({ rendered: 1, failed: 0, skipped: 0, llmUnavailable: 1, llmDegraded: 1, total: 1 });
    });

    it('mixed batch: benign skip, hard skip, degraded render and clean render tally independently', async () => {
        let { renderScenesSerially } = await import('../features/chapBook.js');
        let generateOne = async (oid) => {
            if (oid === 'benign') return { imageObjectId: null, rendered: false, skipped: true };
            if (oid === 'hard')   return { imageObjectId: null, rendered: false, skipped: true, llmUnavailable: true };
            if (oid === 'degraded') return { imageObjectId: 'img-d', rendered: true, llmUnavailable: true, llmDegraded: true };
            return { imageObjectId: 'img-' + oid, rendered: true };
        };
        let result = await renderScenesSerially(['benign', 'hard', 'degraded', 'clean'], generateOne, { sleep: async () => {} });
        // clean + degraded rendered (2); benign skip only (1); hard + degraded affected by LLM (2);
        // degraded is the only one rendered-on-stored-prompt (1); nothing threw (0 failed).
        expect(result).toEqual({ rendered: 2, failed: 0, skipped: 1, llmUnavailable: 2, llmDegraded: 1, total: 4 });
    });
});

describe('renderResultMessage / renderResultLevel — bulk LLM-unavailable summary', () => {
    it('adds a distinct llm-unavailable clause when scenes were affected', async () => {
        let { renderResultMessage } = await import('../features/chapBook.js');
        expect(renderResultMessage({ rendered: 3, skipped: 0, failed: 0, llmUnavailable: 2, llmDegraded: 1, total: 3 }))
            .toBe('Render complete: 3 generated — 2 scene(s) affected by an unavailable LLM/chat config');
    });

    it('the llm-unavailable clause coexists with skipped/failed clauses, in order', async () => {
        let { renderResultMessage } = await import('../features/chapBook.js');
        expect(renderResultMessage({ rendered: 1, skipped: 1, failed: 1, llmUnavailable: 1, total: 4 }))
            .toBe('Render complete: 1 generated, 1 skipped (need prompts), 1 failed — 1 scene(s) affected by an unavailable LLM/chat config');
    });

    it('escalates the toast level to error when any scene hit the LLM-unavailable fault', async () => {
        let { renderResultLevel } = await import('../features/chapBook.js');
        expect(renderResultLevel({ rendered: 2, skipped: 0, failed: 0, llmUnavailable: 1 })).toBe('error');
        // error outranks the warn a plain skip/fail would produce
        expect(renderResultLevel({ rendered: 0, skipped: 1, failed: 1, llmUnavailable: 1 })).toBe('error');
    });

    it('keeps warn for skip/fail and success for a clean run (no regression when llmUnavailable is 0)', async () => {
        let { renderResultLevel } = await import('../features/chapBook.js');
        expect(renderResultLevel({ rendered: 3, skipped: 0, failed: 0 })).toBe('success');
        expect(renderResultLevel({ rendered: 2, skipped: 1, failed: 0 })).toBe('warn');
        expect(renderResultLevel({ rendered: 2, skipped: 0, failed: 1 })).toBe('warn');
    });
});
