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
 *   1) POST /rest/olio/chap-book/scene/{oid}/prompt/regenerate  body { chatConfig? } → { sdPrompt, promptLocked }
 *   2) POST /rest/olio/chap-book/scene/{oid}/analyze            body { chatConfig? } → { success, mood }
 *
 * What is asserted (with a stubbed global.fetch — no live backend):
 *   - correct URL suffix + POST method + JSON content-type + credentials:'include'
 *   - chatConfig is present in the body ONLY when a name is supplied (never sent as undefined/null)
 *   - the parsed JSON body is returned as-is on 200
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
        global.__cbNextResponse = jsonResponse(200, { sdPrompt: 'a moonlit shore, painterly', promptLocked: false });
        let res = await regenerateScenePrompt('scene-oid-1', 'contentAnalysis');
        expect(calls).toHaveLength(1);
        expect(calls[0].url.endsWith('/rest/olio/chap-book/scene/scene-oid-1/prompt/regenerate')).toBe(true);
        expect(calls[0].opts.method).toBe('POST');
        expect(calls[0].opts.headers['Content-Type']).toBe('application/json');
        expect(calls[0].opts.credentials).toBe('include');
        expect(calls[0].body).toEqual({ chatConfig: 'contentAnalysis' });
        // The parsed response is returned verbatim so the caller can update scene.sdPrompt/promptLocked.
        expect(res).toEqual({ sdPrompt: 'a moonlit shore, painterly', promptLocked: false });
    });

    it('omits chatConfig from the body entirely when no name is supplied (never sends undefined/null)', async () => {
        let { regenerateScenePrompt } = await import('../features/chapBook.js');
        global.__cbNextResponse = jsonResponse(200, { sdPrompt: 'x', promptLocked: true });
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
