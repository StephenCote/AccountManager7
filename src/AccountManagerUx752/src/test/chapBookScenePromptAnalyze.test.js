// @vitest-environment jsdom
/**
 * ChapBook per-scene prompt/analysis endpoint helpers — regenerateScenePrompt + analyzeScene.
 *
 * Exercises the REAL exported functions from features/chapBook.js that the two per-page review
 * buttons drive: "Regen prompt" (cb-page-regen-prompt) and "Analyze" (cb-page-analyze). Both operate
 * on a SCENE objectId (a ChapBook scene keeps NO link back to its source poem, so they deliberately
 * avoid the poem-level /analyze/{poemObjectId} endpoint) and both are prompt/analysis ONLY — neither
 * renders an SD image.
 *
 * Contracts under test (built by the backend agent, wired verbatim here):
 *   1) POST /rest/olio/chap-book/scene/{oid}/prompt/regenerate  body { chatConfig? }
 *        → { sdPrompt, promptLocked:false, updated, compositionUpdated, sceneUpdated[, compositionObjectId][, error] }
 *   2) POST /rest/olio/chap-book/scene/{oid}/analyze            body { chatConfig? } → { success, mood }
 *   3) PUT  /rest/olio/chap-book/scene/{oid}/prompt             body { sdPrompt|null }
 *        → { updated, compositionUpdated, sceneUpdated, promptLocked[, compositionObjectId][, error] }
 *   4) promptWriteOutcome(body, verb) — the P4-8 (ee) classifier the review view feeds (1) and (3) through:
 *        updated:false → error; compositionUpdated:false → warn naming the composition (pre-P4-10 shape);
 *        sceneUpdated:false (or an error) → warn naming the scene copy (P4-10: the composition is the
 *        authority, the scene its mirror) — both carrying the server's reason verbatim; otherwise success.
 *        A partial write must reach the user, never be read as "saved".
 *
 * What is asserted (with a stubbed global.fetch — no live backend):
 *   - correct URL suffix + method + JSON content-type + credentials:'include'
 *   - chatConfig is present in the body ONLY when a name is supplied (never sent as undefined/null)
 *   - the parsed JSON body is returned as-is on 200 (all of its P4-8 flags intact)
 *   - a non-2xx response throws an Error whose `.status` carries the HTTP status (so the caller can
 *     surface a 503 "no org chat config" distinctly from a generic failure instead of silently no-oping)
 *
 * The live LLM round-trip and the DOM button → toast behavior are a Playwright concern, not claimed here.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// A minimal fetch Response stand-in: records nothing itself; the stub records the call.
function jsonResponse(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status: status,
        json: async () => body
    };
}

let calls;
beforeEach(() => {
    calls = [];
    global.fetch = vi.fn(async (url, opts) => {
        let call = { url: url, opts: opts, body: opts && opts.body ? JSON.parse(opts.body) : null };
        calls.push(call);
        // The per-test override sets global.__cbNextResponse; default is a benign 200.
        let next = global.__cbNextResponse;
        return next || jsonResponse(200, {});
    });
});
afterEach(() => {
    delete global.__cbNextResponse;
    vi.restoreAllMocks();
});

describe('regenerateScenePrompt (per-scene landscape-prompt regenerate — prompt only)', () => {
    it('POSTs to /scene/{oid}/prompt/regenerate with the chatConfig when a name is supplied', async () => {
        let { regenerateScenePrompt } = await import('../features/chapBook.js');
        let body = { sdPrompt: 'a moonlit shore, painterly', promptLocked: false, updated: true, compositionUpdated: true, compositionObjectId: 'comp-1' };
        global.__cbNextResponse = jsonResponse(200, body);
        let res = await regenerateScenePrompt('scene-oid-1', 'contentAnalysis');
        expect(calls).toHaveLength(1);
        expect(calls[0].url.endsWith('/rest/olio/chap-book/scene/scene-oid-1/prompt/regenerate')).toBe(true);
        expect(calls[0].opts.method).toBe('POST');
        expect(calls[0].opts.headers['Content-Type']).toBe('application/json');
        expect(calls[0].opts.credentials).toBe('include');
        expect(calls[0].body).toEqual({ chatConfig: 'contentAnalysis' });
        // The parsed response is returned verbatim — sdPrompt/promptLocked for the card, and the P4-8
        // updated/compositionUpdated/compositionObjectId flags for promptWriteOutcome.
        expect(res).toEqual(body);
    });

    it('returns a partial-write body verbatim (compositionUpdated:false + error) rather than dropping the flags', async () => {
        let { regenerateScenePrompt } = await import('../features/chapBook.js');
        let body = { sdPrompt: 'x', promptLocked: false, updated: true, compositionUpdated: false,
            error: 'Scene scene-oid-2 sdPrompt regenerated but its page composition was not: refused' };
        global.__cbNextResponse = jsonResponse(200, body);
        let res = await regenerateScenePrompt('scene-oid-2', 'contentAnalysis');
        expect(res).toEqual(body);
        expect(res.compositionUpdated).toBe(false);
        expect(res.error).toContain('page composition was not');
    });

    it('omits chatConfig from the body entirely when no name is supplied (never sends undefined/null)', async () => {
        let { regenerateScenePrompt } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(200, { sdPrompt: 'x', promptLocked: false, updated: true, compositionUpdated: true });
        await regenerateScenePrompt('scene-oid-2', null);
        expect(calls[0].body).toEqual({});
        expect('chatConfig' in calls[0].body).toBe(false);
    });

    it('throws an Error carrying .status on a 503 (no org chat config) so the caller can surface it', async () => {
        let { regenerateScenePrompt } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(503, { error: 'no chat config' });
        let err = null;
        try { await regenerateScenePrompt('scene-oid-3', 'generalChat'); }
        catch (e) { err = e; }
        expect(err).toBeInstanceOf(Error);
        expect(err.status).toBe(503);
    });

    it('returns {} (never null) when a 200 response has an empty/absent JSON body', async () => {
        let { regenerateScenePrompt } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(200, null);
        let res = await regenerateScenePrompt('scene-oid-4');
        expect(res).toEqual({});
    });
});

describe('analyzeScene (per-scene mood analysis — analysis only)', () => {
    it('POSTs to /scene/{oid}/analyze with the chatConfig when a name is supplied', async () => {
        let { analyzeScene } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(200, { success: true, mood: 'melancholy' });
        let res = await analyzeScene('scene-oid-9', 'contentAnalysis');
        expect(calls).toHaveLength(1);
        expect(calls[0].url.endsWith('/rest/olio/chap-book/scene/scene-oid-9/analyze')).toBe(true);
        expect(calls[0].opts.method).toBe('POST');
        expect(calls[0].opts.credentials).toBe('include');
        expect(calls[0].body).toEqual({ chatConfig: 'contentAnalysis' });
        expect(res).toEqual({ success: true, mood: 'melancholy' });
    });

    it('omits chatConfig from the body entirely when no name is supplied', async () => {
        let { analyzeScene } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(200, { success: true, mood: 'calm' });
        await analyzeScene('scene-oid-10');
        expect(calls[0].body).toEqual({});
        expect('chatConfig' in calls[0].body).toBe(false);
    });

    it('throws an Error carrying .status on a 503 (no org chat config)', async () => {
        let { analyzeScene } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(503, { error: 'no chat config' });
        let err = null;
        try { await analyzeScene('scene-oid-11', 'generalChat'); }
        catch (e) { err = e; }
        expect(err).toBeInstanceOf(Error);
        expect(err.status).toBe(503);
    });

    it('does NOT hit the poem-level /analyze/{poemObjectId} shape — the URL targets a SCENE', async () => {
        let { analyzeScene } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(200, { success: true, mood: 'wistful' });
        await analyzeScene('scene-oid-12', null);
        // The scene endpoint has the '/scene/' segment before the objectId; the poem endpoint does not.
        expect(calls[0].url).toContain('/scene/scene-oid-12/analyze');
        expect(calls[0].url).not.toMatch(/\/analyze\/scene-oid-12$/);
    });
});

describe('putSceneLandscapePrompt (per-scene landscape-prompt PUT — verbatim store or clear)', () => {
    it('PUTs { sdPrompt } to /scene/{oid}/prompt and returns the P4-8 body verbatim', async () => {
        let { putSceneLandscapePrompt } = await import('../features/chapBook.js');
        let body = { updated: true, compositionUpdated: true, promptLocked: true, compositionObjectId: 'comp-7' };
        global.__cbNextResponse = jsonResponse(200, body);
        let res = await putSceneLandscapePrompt('scene-oid-20', '  a rain-slick quay at dusk  ');
        expect(calls).toHaveLength(1);
        expect(calls[0].url.endsWith('/rest/olio/chap-book/scene/scene-oid-20/prompt')).toBe(true);
        expect(calls[0].opts.method).toBe('PUT');
        expect(calls[0].opts.headers['Content-Type']).toBe('application/json');
        expect(calls[0].opts.credentials).toBe('include');
        // Non-blank is sent exactly as given — the backend trims and persists verbatim.
        expect(calls[0].body).toEqual({ sdPrompt: '  a rain-slick quay at dusk  ' });
        expect(res).toEqual(body);
    });

    it('sends sdPrompt:null for a blank value (a clear) — never an empty string', async () => {
        let { putSceneLandscapePrompt } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(200, { updated: true, compositionUpdated: true, promptLocked: false });
        await putSceneLandscapePrompt('scene-oid-21', '   ');
        expect(calls[0].body).toEqual({ sdPrompt: null });
        await putSceneLandscapePrompt('scene-oid-21', null);
        expect(calls[1].body).toEqual({ sdPrompt: null });
    });

    it('throws on a non-2xx response', async () => {
        let { putSceneLandscapePrompt } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(404, { error: 'Scene not found' });
        let err = null;
        try { await putSceneLandscapePrompt('scene-oid-22', 'x'); }
        catch (e) { err = e; }
        expect(err).toBeInstanceOf(Error);
        expect(err.message).toContain('404');
    });
});

describe('promptWriteOutcome (P4-8 prompt-write body → toast; a partial write is never read as saved)', () => {
    it('is success when both the scene copy and the page composition landed', async () => {
        let { promptWriteOutcome } = await import('../features/chapBook.js');
        expect(promptWriteOutcome({ updated: true, compositionUpdated: true, promptLocked: true, compositionObjectId: 'c' }, 'saved'))
            .toEqual({ level: 'success', message: 'Prompt saved' });
        expect(promptWriteOutcome({ sdPrompt: 'x', promptLocked: false, updated: true, compositionUpdated: true }, 'regenerated'))
            .toEqual({ level: 'success', message: 'Prompt regenerated' });
    });

    it('is a warning carrying the server reason verbatim when the scene copy landed but the composition did not', async () => {
        let { promptWriteOutcome } = await import('../features/chapBook.js');
        let reason = 'Scene s-1 sdPrompt persisted but its page composition was not: Composition update refused';
        let out = promptWriteOutcome({ updated: true, compositionUpdated: false, promptLocked: true, error: reason }, 'saved');
        expect(out.level).toBe('warn');
        expect(out.message).toBe('Prompt saved, composition not updated: ' + reason);
        // Regenerate uses the same shape with its own verb.
        let out2 = promptWriteOutcome({ sdPrompt: 'x', promptLocked: false, updated: true, compositionUpdated: false, error: reason }, 'regenerated');
        expect(out2.level).toBe('warn');
        expect(out2.message).toBe('Prompt regenerated, composition not updated: ' + reason);
    });

    it('is a warning naming the scene copy when the composition (authority since P4-10) landed but the scene mirror did not', async () => {
        let { promptWriteOutcome } = await import('../features/chapBook.js');
        let reason = 'Page composition c-9 persisted but scene s-1 sdPrompt was not: the scene mirror write was refused';
        let out = promptWriteOutcome({ updated: true, compositionUpdated: true, sceneUpdated: false, promptLocked: true, compositionObjectId: 'c-9', error: reason }, 'saved');
        expect(out.level).toBe('warn');
        expect(out.message).toBe('Prompt saved, scene copy not updated: ' + reason);
        let out2 = promptWriteOutcome({ sdPrompt: 'x', promptLocked: false, updated: true, compositionUpdated: true, sceneUpdated: false, error: reason }, 'regenerated');
        expect(out2.level).toBe('warn');
        expect(out2.message).toBe('Prompt regenerated, scene copy not updated: ' + reason);
        // The complete P4-10 shape (both flags true) is still success.
        expect(promptWriteOutcome({ updated: true, compositionUpdated: true, sceneUpdated: true, promptLocked: true, compositionObjectId: 'c-9' }, 'saved'))
            .toEqual({ level: 'success', message: 'Prompt saved' });
    });

    it('is a warning (not success) when an error is present even if compositionUpdated is absent', async () => {
        let { promptWriteOutcome } = await import('../features/chapBook.js');
        let out = promptWriteOutcome({ updated: true, error: 'something was refused' }, 'saved');
        expect(out.level).toBe('warn');
        expect(out.message).toContain('something was refused');
    });

    it('is an error when nothing landed (updated:false) so the prompt stays dirty', async () => {
        let { promptWriteOutcome } = await import('../features/chapBook.js');
        expect(promptWriteOutcome({ updated: false, compositionUpdated: false, promptLocked: true }, 'saved'))
            .toEqual({ level: 'error', message: 'Prompt was not saved' });
        expect(promptWriteOutcome({ updated: false, compositionUpdated: false, error: 'db refused' }, 'regenerated'))
            .toEqual({ level: 'error', message: 'Prompt was not regenerated: db refused' });
    });

    it('reads absent flags / an empty body as success and defaults the verb to saved (never fabricates a failure)', async () => {
        let { promptWriteOutcome } = await import('../features/chapBook.js');
        expect(promptWriteOutcome({}, 'saved')).toEqual({ level: 'success', message: 'Prompt saved' });
        expect(promptWriteOutcome(null)).toEqual({ level: 'success', message: 'Prompt saved' });
        expect(promptWriteOutcome(undefined, 'regenerated')).toEqual({ level: 'success', message: 'Prompt regenerated' });
    });
});
