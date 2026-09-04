// @vitest-environment jsdom
/**
 * ChapBook per-scene landscape-prompt review — unit coverage for the two pieces of behavior that
 * landed in features/chapBook.js:
 *
 *   1. isSceneUnprompted(scene) — the FIX. A scene whose prompt the user explicitly saved/edited
 *      (scene.promptLocked === true) is authoritative and must NEVER be flagged "needs prompt",
 *      regardless of the prompt's shape. The promptLocked gate (chapBook.js:2288) is checked BEFORE
 *      both the image check and the old `sdPrompt.startsWith('landscape, ')` shape heuristic, so a
 *      saved edit that happens to begin "landscape, " is no longer mis-flagged for regeneration.
 *
 *   2. renderChapBookScene(oid, chatConfig, sdConfig, sdPrompt) — the per-scene generate call gained
 *      a 4th sdPrompt arg. A non-blank prompt goes into the POST body VERBATIM (never trimmed); a
 *      blank/absent prompt sends NO sdPrompt key at all (backend then resolves its own landscape
 *      prompt, preserving the prior behavior). We drive the REAL exported function with a stubbed
 *      global.fetch and assert the exact JSON body it builds — genuine behavioral coverage of the
 *      shipping code path, not a re-implementation.
 *
 * Both functions are exported from features/chapBook.js. No network, LLM or SD is touched here.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

describe('isSceneUnprompted — promptLocked authoritative-prompt fix', () => {
    let isSceneUnprompted;
    beforeEach(async () => {
        ({ isSceneUnprompted } = await import('../features/chapBook.js'));
    });

    // ── THE FIX ──────────────────────────────────────────────────────────────
    it('promptLocked scene is NEVER un-prompted, even with a "landscape, " shaped prompt and no image', () => {
        // Old behavior (shape check only) would have returned true here and mis-flagged this scene
        // for regeneration; the promptLocked gate makes it authoritative → false.
        expect(isSceneUnprompted({ promptLocked: true, sdPrompt: 'landscape, foo', imageObjectId: null }))
            .toBe(false);
    });

    it('an UNLOCKED "landscape, " shaped prompt with no image IS still un-prompted (true)', () => {
        expect(isSceneUnprompted({ promptLocked: false, sdPrompt: 'landscape, foo', imageObjectId: null }))
            .toBe(true);
    });

    it('promptLocked gate is honored even when the prompt is blank and there is no image', () => {
        // Without the gate a blank prompt returns true; promptLocked must still force false.
        expect(isSceneUnprompted({ promptLocked: true, sdPrompt: '', imageObjectId: null })).toBe(false);
    });

    // ── the pre-existing (non-locked) contract still holds ────────────────────
    it('a genuine detailed landscape prompt (not "landscape, ") with no image is NOT un-prompted', () => {
        expect(isSceneUnprompted({ sdPrompt: 'a genuine detailed landscape prompt', imageObjectId: null }))
            .toBe(false);
    });

    it('a blank prompt with no image IS un-prompted (needs a prompt)', () => {
        expect(isSceneUnprompted({ sdPrompt: '', imageObjectId: null })).toBe(true);
        expect(isSceneUnprompted({ sdPrompt: '   ', imageObjectId: null })).toBe(true);
        expect(isSceneUnprompted({ imageObjectId: null })).toBe(true); // sdPrompt absent
    });

    it('any scene that already has an image is NOT un-prompted, regardless of prompt shape', () => {
        expect(isSceneUnprompted({ sdPrompt: 'landscape, foo', imageObjectId: 'img-1' })).toBe(false);
        expect(isSceneUnprompted({ sdPrompt: '', imageObjectId: 'img-1' })).toBe(false);
        expect(isSceneUnprompted({ promptLocked: false, sdPrompt: 'landscape, x', imageObjectId: 'img-9' }))
            .toBe(false);
    });

    it('a null/undefined scene is not un-prompted (guarded → false)', () => {
        expect(isSceneUnprompted(null)).toBe(false);
        expect(isSceneUnprompted(undefined)).toBe(false);
    });

    it('gate ORDER: promptLocked wins over image and shape; image wins over shape', () => {
        // promptLocked true short-circuits before any other check.
        expect(isSceneUnprompted({ promptLocked: true, sdPrompt: 'landscape, foo', imageObjectId: null }))
            .toBe(false);
        // Not locked, has image → false (image check precedes shape check).
        expect(isSceneUnprompted({ promptLocked: false, sdPrompt: 'landscape, foo', imageObjectId: 'i' }))
            .toBe(false);
        // Not locked, no image, "landscape, " shape → true (shape check reached).
        expect(isSceneUnprompted({ promptLocked: false, sdPrompt: 'landscape, foo', imageObjectId: null }))
            .toBe(true);
    });
});

describe('renderChapBookScene — per-scene generate body (verbatim sdPrompt / omission)', () => {
    let renderChapBookScene;
    let fetchMock;

    beforeEach(async () => {
        ({ renderChapBookScene } = await import('../features/chapBook.js'));
        // Default stub: a successful render response. Individual tests can override .json.
        fetchMock = vi.fn(async () => ({
            ok: true,
            json: async () => ({ imageObjectId: 'stub-img', rendered: true })
        }));
        global.fetch = fetchMock;
    });

    afterEach(() => {
        vi.restoreAllMocks();
        delete global.fetch;
    });

    // Parse the JSON body of the Nth (default first) fetch call.
    function bodyOfCall(n = 0) {
        const call = fetchMock.mock.calls[n];
        return JSON.parse(call[1].body);
    }
    function urlOfCall(n = 0) {
        return fetchMock.mock.calls[n][0];
    }

    it('POSTs to the per-scene generate endpoint for the given objectId', async () => {
        await renderChapBookScene('oid-42', 'cfgName', {}, 'my prompt');
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(urlOfCall()).toContain('/scene/oid-42/generate');
        expect(fetchMock.mock.calls[0][1].method).toBe('POST');
    });

    it('a non-blank sdPrompt goes into the body VERBATIM', async () => {
        await renderChapBookScene('oid-1', 'cfgName', {}, 'my prompt');
        const body = bodyOfCall();
        expect(body.schema).toBe('olio.pictureBookRequest');
        expect(body.sdPrompt).toBe('my prompt');
        expect(body.chatConfig).toBe('cfgName'); // chatConfig still forwarded when supplied
    });

    it('sdPrompt is stored EXACTLY as given — surrounding whitespace is NOT trimmed', async () => {
        // The inclusion guard trims only to decide presence; the value written is untrimmed.
        await renderChapBookScene('oid-2', null, {}, '  keep   spaces  ');
        const body = bodyOfCall();
        expect(body.sdPrompt).toBe('  keep   spaces  ');
    });

    it('an OMITTED sdPrompt (undefined 4th arg) sends NO sdPrompt key', async () => {
        await renderChapBookScene('oid-3', 'cfgName', {});
        const body = bodyOfCall();
        expect('sdPrompt' in body).toBe(false);
    });

    it('a blank / whitespace-only / empty sdPrompt sends NO sdPrompt key', async () => {
        await renderChapBookScene('oid-a', null, {}, '');
        expect('sdPrompt' in bodyOfCall(0)).toBe(false);

        await renderChapBookScene('oid-b', null, {}, '    ');
        expect('sdPrompt' in bodyOfCall(1)).toBe(false);

        await renderChapBookScene('oid-c', null, {}, null);
        expect('sdPrompt' in bodyOfCall(2)).toBe(false);
    });

    it('sdConfig is only included when it has keys; chatConfig only when supplied', async () => {
        await renderChapBookScene('oid-4', null, {}); // empty config, no chatConfig
        let body = bodyOfCall(0);
        expect('sdConfig' in body).toBe(false);
        expect('chatConfig' in body).toBe(false);

        await renderChapBookScene('oid-5', 'cfg', { steps: 20 }, 'p');
        body = bodyOfCall(1);
        expect(body.sdConfig).toEqual({ steps: 20 });
        expect(body.chatConfig).toBe('cfg');
        expect(body.sdPrompt).toBe('p');
    });

    it('coerces the response into the documented result shape (missing fields default)', async () => {
        fetchMock.mockResolvedValueOnce({ ok: true, json: async () => ({}) });
        const result = await renderChapBookScene('oid-6', null, {}, 'p');
        expect(result).toEqual({
            imageObjectId: null,
            rendered: false,
            skipped: false,
            llmUnavailable: false,
            llmDegraded: false
        });
    });

    it('passes through a fully-populated result response', async () => {
        fetchMock.mockResolvedValueOnce({
            ok: true,
            json: async () => ({ imageObjectId: 'real-img', rendered: true, skipped: false, llmUnavailable: true, llmDegraded: true })
        });
        const result = await renderChapBookScene('oid-7', 'cfg', {}, 'p');
        expect(result).toEqual({
            imageObjectId: 'real-img',
            rendered: true,
            skipped: false,
            llmUnavailable: true,
            llmDegraded: true
        });
    });

    it('throws when the backend responds not-ok', async () => {
        fetchMock.mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({ error: 'boom' }) });
        await expect(renderChapBookScene('oid-8', null, {}, 'p')).rejects.toThrow(/Scene render failed/);
    });
});
