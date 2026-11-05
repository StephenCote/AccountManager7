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

/**
 * P10-C 1 (PictureBook V3 Phase 10) — the per-page "Re-render this page" echo.
 *
 * The review card used to send its textarea back as sdPrompt on EVERY re-render, edited or not. The server
 * took that echo for a human edit and persisted it as a USER-sourced, LOCKED page prompt — including the
 * stanza-free "landscape, <title>, …" placeholder written when the LLM failed at create time — after which
 * the lock protected the placeholder from LLM recovery for good: a permanently random landscape, with no
 * signal. Reproduced live 2026-11-03 (chapBookPromptNoOverride.spec.js, test 3).
 *
 * Now rerenderPromptOverride(scene) returns the textarea text ONLY when the user edited it on this card
 * since the last save/render ('sdPrompt' in scene._dirty) and it is non-blank; doRerenderScene sends that
 * (verbatim) and no chatConfig, or — unedited/blank — NO sdPrompt and the session's chat config so the
 * server renders the stored prompt as it stands and can recover a placeholder through the LLM.
 *
 * Driven through the REAL exported doRerenderScene with the review state seeded (__setReviewStateForTest),
 * a stubbed fetch router (GET …/settings → a stored row, so no PUT; POST …/generate → a v1 body) and
 * am7sd.loadConfig / am7client.getRenderPath / am7client.get / page.toast stubbed. No network, LLM or SD.
 */
describe('P10-C 1: rerenderPromptOverride — only an EDITED, non-blank textarea is an override', () => {
    let rerenderPromptOverride;
    beforeEach(async () => {
        ({ rerenderPromptOverride } = await import('../features/chapBook.js'));
    });

    it('unedited (no dirty mark) → null, whatever the stored text looks like', () => {
        expect(rerenderPromptOverride({ sdPrompt: 'landscape, Winter, calm atmosphere, painterly' })).toBeNull();
        expect(rerenderPromptOverride({ sdPrompt: 'a genuine LLM prompt', _dirty: new Set() })).toBeNull();
        expect(rerenderPromptOverride({ sdPrompt: 'a genuine LLM prompt', _dirty: new Set(['title']) })).toBeNull();
    });

    it('edited and non-blank → the textarea text VERBATIM (never trimmed)', () => {
        expect(rerenderPromptOverride({ sdPrompt: '  my edit  ', _dirty: new Set(['sdPrompt']) })).toBe('  my edit  ');
        // The server decides edit-vs-echo by equality, so a placeholder-shaped edit is still sent.
        expect(rerenderPromptOverride({ sdPrompt: 'landscape, my own text', _dirty: new Set(['sdPrompt']) })).toBe('landscape, my own text');
    });

    it('edited but blank → null (a blank is not a prompt; the server resolves from what is stored)', () => {
        expect(rerenderPromptOverride({ sdPrompt: '   ', _dirty: new Set(['sdPrompt']) })).toBeNull();
        expect(rerenderPromptOverride({ sdPrompt: '', _dirty: new Set(['sdPrompt']) })).toBeNull();
        expect(rerenderPromptOverride(null)).toBeNull();
    });
});

describe('P10-C 1: doRerenderScene — what the generate POST carries', () => {
    let cb, calls;
    const STORED_ROW = { schema: 'olio.sd.config', objectId: 'cfg-stored', name: 'book-sdConfig', bodyStyle: 'x', steps: 20 };

    // sceneRows: what the post-render scene re-read (refreshScenePrompt) returns, keyed by objectId.
    function wire(generateBody, sceneRows) {
        calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let url = String(u);
            let method = (init && init.method) || 'GET';
            let body = (init && init.body) ? JSON.parse(init.body) : null;
            calls.push({ url: url.replace(/^.*\/rest\//, '/rest/'), method, body });
            if (/\/rest\/olio\/chap-book\/[^/]+\/settings$/.test(url)) {
                // A STORED book row with nothing changed → syncBookSdConfigForRender sends no PUT.
                return { ok: true, status: 200, json: async () => Object.assign({}, STORED_ROW) };
            }
            if (/\/rest\/olio\/chap-book\/scene\/[^/]+\/generate$/.test(url)) {
                return { ok: true, status: 200, json: async () => generateBody };
            }
            if (/\/rest\/model\/search$/.test(url) && body && body.type === 'olio.pb.scene') {
                let oidCond = (body.fields || []).find(f => f.name === 'objectId');
                let row = oidCond && sceneRows ? sceneRows[oidCond.value] : null;
                return { ok: true, status: 200, json: async () => (row ? [Object.assign({ schema: 'olio.pb.scene' }, row)] : []) };
            }
            return { ok: false, status: 404, text: async () => 'unmocked ' + method + ' ' + url, json: async () => ({ error: 'unmocked' }) };
        });
    }
    function generateCalls() { return calls.filter(c => /\/generate$/.test(c.url)); }
    function sceneReads() { return calls.filter(c => /\/rest\/model\/search$/.test(c.url) && c.body && c.body.type === 'olio.pb.scene'); }

    beforeEach(async () => {
        cb = await import('../features/chapBook.js');
        const { am7sd } = await import('../components/sdConfig.js');
        const { am7client } = await import('../core/am7client.js');
        const { page } = await import('../core/pageClient.js');
        vi.spyOn(am7sd, 'loadConfig').mockResolvedValue({ schema: 'olio.sd.config', objectId: 'pref-1', name: 'sdcfg-default', bodyStyle: 'seeded' });
        vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v1', chapbook: 'v1' });
        vi.spyOn(am7client, 'get').mockImplementation((type, id, cbk) => { cbk(null); });
        if (typeof page.toast !== 'function') page.toast = () => {};
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        // The post-render scene re-read is org-scoped; a session always has an organization.
        page.user = Object.assign({}, page.user || {}, { organizationId: 7 });
        // The session's chat config (the create dialog's pick) is what an unedited re-render must carry.
        cb.__setChatConfigStateForTest({ configChatConfigRef: { name: 'cfgX', objectId: 'cc-1' }, renderChatConfigRef: null, createChatConfigRef: null });
    });
    afterEach(() => {
        cb.__setChatConfigStateForTest({ configChatConfigRef: null, renderChatConfigRef: null, createChatConfigRef: null });
        cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewScenes: [] });
        vi.restoreAllMocks();
        delete global.fetch;
    });

    it('UNEDITED placeholder (the bug): NO sdPrompt on the wire, the chat config IS — the server resolves/recovers, and the card shows the recovered prompt', async () => {
        const RECOVERED = 'masterpiece, best quality, landscape, snowy forest with cobalt skies';
        wire({ imageObjectId: 'img-1', rendered: true, skipped: false, llmUnavailable: false, llmDegraded: false },
            { 'scene-echo': { objectId: 'scene-echo', sdPrompt: RECOVERED, promptLocked: false } });
        let scene = { objectId: 'scene-echo', sceneIndex: 0, sdPrompt: 'landscape, Winter, calm atmosphere, painterly, soft light, wide natural vista', promptLocked: false, configOverride: null };
        cb.__setReviewStateForTest({ reviewBookObjectId: 'book-echo', reviewScenes: [scene] });
        await cb.__doRerenderSceneForTest(0);
        let gens = generateCalls();
        expect(gens).toHaveLength(1);
        expect(gens[0].url).toBe('/rest/olio/chap-book/scene/scene-echo/generate');
        expect('sdPrompt' in gens[0].body).toBe(false);
        expect(gens[0].body.chatConfig).toBe('cfgX');
        expect(scene.imageObjectId).toBe('img-1');
        // The server replaced the placeholder with the LLM prompt it rendered from: one fresh, uncached
        // re-read of THIS scene, after the generate, and the textarea now shows what the image is.
        let reads = sceneReads();
        expect(reads).toHaveLength(1);
        expect(reads[0].body.cache).toBe(false);
        expect(reads[0].body.fields).toEqual(expect.arrayContaining([{ name: 'objectId', comparator: 'EQUALS', value: 'scene-echo' }]));
        expect(calls.indexOf(reads[0])).toBeGreaterThan(calls.indexOf(gens[0]));
        expect(scene.sdPrompt).toBe(RECOVERED);
        expect(scene.promptLocked).toBe(false);
        expect(scene._saving).toBe(false);
    });

    it('EDITED, non-blank: the edit goes VERBATIM as sdPrompt with no chatConfig; the dirty mark clears on success; no re-read', async () => {
        wire({ imageObjectId: 'img-2', rendered: true, skipped: false, llmUnavailable: false, llmDegraded: false },
            { 'scene-edit': { objectId: 'scene-edit', sdPrompt: 'SERVER COPY — must not replace the card', promptLocked: true } });
        let scene = { objectId: 'scene-edit', sceneIndex: 1, sdPrompt: '  a frozen river under cobalt skies  ', promptLocked: false, configOverride: null, _dirty: new Set(['sdPrompt', 'title']) };
        cb.__setReviewStateForTest({ reviewBookObjectId: 'book-edit', reviewScenes: [scene] });
        await cb.__doRerenderSceneForTest(0);
        let gens = generateCalls();
        expect(gens).toHaveLength(1);
        expect(gens[0].body.sdPrompt).toBe('  a frozen river under cobalt skies  ');
        expect('chatConfig' in gens[0].body).toBe(false);
        expect(scene._dirty.has('sdPrompt')).toBe(false);
        expect(scene._dirty.has('title')).toBe(true);
        // The lock is the server's call (edit vs echo); the card never sets it on its own — and the text the
        // user sent IS what rendered, so there is nothing to re-read.
        expect(sceneReads()).toHaveLength(0);
        expect(scene.sdPrompt).toBe('  a frozen river under cobalt skies  ');
        expect(scene.promptLocked).toBe(false);
    });

    it('UNEDITED, render SKIPPED: no re-read (nothing rendered, the stored prompt did not change)', async () => {
        wire({ imageObjectId: null, rendered: false, skipped: true, llmUnavailable: true, llmDegraded: false },
            { 'scene-noimg': { objectId: 'scene-noimg', sdPrompt: 'should not be read', promptLocked: false } });
        let scene = { objectId: 'scene-noimg', sceneIndex: 4, sdPrompt: '', promptLocked: false, configOverride: null, _dirty: new Set() };
        cb.__setReviewStateForTest({ reviewBookObjectId: 'book-noimg', reviewScenes: [scene] });
        await cb.__doRerenderSceneForTest(0);
        expect(generateCalls()).toHaveLength(1);
        expect(sceneReads()).toHaveLength(0);
        expect(scene.sdPrompt).toBe('');
    });

    it('EDITED but the render did not happen (skipped): the edit stays dirty so the next Save/Re-render still carries it', async () => {
        wire({ imageObjectId: null, rendered: false, skipped: true, llmUnavailable: false, llmDegraded: false });
        let scene = { objectId: 'scene-skip', sceneIndex: 2, sdPrompt: 'my edit', promptLocked: false, configOverride: null, _dirty: new Set(['sdPrompt']) };
        cb.__setReviewStateForTest({ reviewBookObjectId: 'book-skip', reviewScenes: [scene] });
        await cb.__doRerenderSceneForTest(0);
        expect(generateCalls()[0].body.sdPrompt).toBe('my edit');
        expect(scene._dirty.has('sdPrompt')).toBe(true);
    });

    it('UNEDITED genuine prompt: no sdPrompt either (the server prefers its stored prompt; no USER lock is minted)', async () => {
        wire({ imageObjectId: 'img-3', rendered: true, skipped: false, llmUnavailable: false, llmDegraded: false });
        let scene = { objectId: 'scene-genuine', sceneIndex: 3, sdPrompt: 'a serene alpine lake at dawn', promptLocked: false, configOverride: null, _dirty: new Set() };
        cb.__setReviewStateForTest({ reviewBookObjectId: 'book-genuine', reviewScenes: [scene] });
        await cb.__doRerenderSceneForTest(0);
        let gens = generateCalls();
        expect(gens).toHaveLength(1);
        expect('sdPrompt' in gens[0].body).toBe(false);
        expect(gens[0].body.chatConfig).toBe('cfgX');
    });
});

// ─────────────────────────────────────────────────────────────────────────────
// P10-C 1: refreshScenePrompt — the card's post-render re-read of the scene's stored prompt.
// ─────────────────────────────────────────────────────────────────────────────
describe('P10-C 1: refreshScenePrompt — the card follows the prompt the server rendered from', () => {
    let refreshScenePrompt, calls;
    function wireScene(row) {
        calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let body = (init && init.body) ? JSON.parse(init.body) : null;
            calls.push({ url: String(u).replace(/^.*\/rest\//, '/rest/'), body });
            return { ok: true, status: 200, json: async () => (row ? [row] : []) };
        });
    }
    beforeEach(async () => {
        ({ refreshScenePrompt } = await import('../features/chapBook.js'));
        const { page } = await import('../core/pageClient.js');
        page.user = Object.assign({}, page.user || {}, { organizationId: 7 });
    });
    afterEach(() => { vi.restoreAllMocks(); delete global.fetch; });

    it('replaces the card prompt and lock with the stored ones when they differ; the read is uncached and org-scoped', async () => {
        wireScene({ schema: 'olio.pb.scene', objectId: 's-1', sdPrompt: 'a recovered prompt', promptLocked: false });
        let scene = { objectId: 's-1', sdPrompt: 'landscape, placeholder', promptLocked: false };
        expect(await refreshScenePrompt(scene)).toBe(true);
        expect(scene.sdPrompt).toBe('a recovered prompt');
        expect(calls).toHaveLength(1);
        expect(calls[0].body).toEqual(expect.objectContaining({ schema: 'io.query', type: 'olio.pb.scene', cache: false }));
        expect(calls[0].body.fields).toEqual(expect.arrayContaining([{ name: 'organizationId', comparator: 'EQUALS', value: 7 }]));
        expect(calls[0].body.request).toEqual(expect.arrayContaining(['sdPrompt', 'promptLocked']));
    });

    it('never overwrites an unsaved edit: a dirty sdPrompt is left alone and nothing is read', async () => {
        wireScene({ schema: 'olio.pb.scene', objectId: 's-2', sdPrompt: 'server text', promptLocked: true });
        let scene = { objectId: 's-2', sdPrompt: 'my unsaved edit', promptLocked: false, _dirty: new Set(['sdPrompt']) };
        expect(await refreshScenePrompt(scene)).toBe(false);
        expect(scene.sdPrompt).toBe('my unsaved edit');
        expect(scene.promptLocked).toBe(false);
        expect(calls).toHaveLength(0);
    });

    it('unchanged when the stored prompt equals the card; false when the row is missing or the read fails', async () => {
        wireScene({ schema: 'olio.pb.scene', objectId: 's-3', sdPrompt: 'same', promptLocked: false });
        let scene = { objectId: 's-3', sdPrompt: 'same', promptLocked: false };
        expect(await refreshScenePrompt(scene)).toBe(false);
        wireScene(null);
        expect(await refreshScenePrompt({ objectId: 's-4', sdPrompt: 'keep', promptLocked: false })).toBe(false);
        global.fetch = vi.fn(async () => { throw new Error('network'); });
        let s5 = { objectId: 's-5', sdPrompt: 'keep', promptLocked: false };
        expect(await refreshScenePrompt(s5)).toBe(false);
        expect(s5.sdPrompt).toBe('keep');
        expect(await refreshScenePrompt(null)).toBe(false);
        expect(await refreshScenePrompt({ sdPrompt: 'no id' })).toBe(false);
    });

    it('a lock the server minted (an echo-free USER edit elsewhere) is followed too', async () => {
        wireScene({ schema: 'olio.pb.scene', objectId: 's-6', sdPrompt: 'locked text', promptLocked: true });
        let scene = { objectId: 's-6', sdPrompt: 'locked text', promptLocked: false };
        expect(await refreshScenePrompt(scene)).toBe(true);
        expect(scene.promptLocked).toBe(true);
    });
});

// ─────────────────────────────────────────────────────────────────────────────
// P10-C 1 (F4): the review-load signal for pages still on the create-time placeholder. Unlike
// isSceneUnprompted it ignores the image — a degrade-rendered placeholder page HAS an image (a random
// landscape) and is exactly the page the user must be told about. A user lock is the only exclusion.
// ─────────────────────────────────────────────────────────────────────────────
describe('P10-C 1: isScenePlaceholderPrompt / placeholderPromptSummary — pages with no LLM prompt', () => {
    let isScenePlaceholderPrompt, placeholderPromptSummary;
    beforeEach(async () => {
        ({ isScenePlaceholderPrompt, placeholderPromptSummary } = await import('../features/chapBook.js'));
    });

    it('a "landscape, " placeholder WITH an image is still a placeholder (the image is irrelevant)', () => {
        expect(isScenePlaceholderPrompt({ sdPrompt: 'landscape, Winter, calm atmosphere, painterly, soft light, wide natural vista', imageObjectId: 'img-1', promptLocked: false })).toBe(true);
    });

    it('a blank / missing prompt is a placeholder', () => {
        expect(isScenePlaceholderPrompt({ sdPrompt: '', promptLocked: false })).toBe(true);
        expect(isScenePlaceholderPrompt({ sdPrompt: '   ', promptLocked: false })).toBe(true);
        expect(isScenePlaceholderPrompt({ promptLocked: false })).toBe(true);
    });

    it('a user-locked prompt is never a placeholder, whatever its shape', () => {
        expect(isScenePlaceholderPrompt({ sdPrompt: 'landscape, my deliberate edit', promptLocked: true })).toBe(false);
        expect(isScenePlaceholderPrompt({ sdPrompt: '', promptLocked: true })).toBe(false);
    });

    it('a genuine LLM prompt is not a placeholder; "landscape" must be the leading token with the comma', () => {
        expect(isScenePlaceholderPrompt({ sdPrompt: 'a serene alpine lake at dawn, wide natural vista', promptLocked: false })).toBe(false);
        expect(isScenePlaceholderPrompt({ sdPrompt: 'a landscape, frozen river', promptLocked: false })).toBe(false);
        expect(isScenePlaceholderPrompt({ sdPrompt: 'landscape painting of a frozen river', promptLocked: false })).toBe(false);
        expect(isScenePlaceholderPrompt(null)).toBe(false);
    });

    it('summary is null when every page has an LLM prompt or a lock, or there are no pages', () => {
        expect(placeholderPromptSummary([])).toBeNull();
        expect(placeholderPromptSummary(null)).toBeNull();
        expect(placeholderPromptSummary([
            { sdPrompt: 'a serene alpine lake at dawn', promptLocked: false },
            { sdPrompt: 'landscape, kept on purpose', promptLocked: true }
        ])).toBeNull();
    });

    it('summary counts the placeholder pages and says what to do', () => {
        let s = placeholderPromptSummary([
            { sdPrompt: 'landscape, Winter, calm atmosphere, painterly, soft light, wide natural vista', imageObjectId: 'img-1', promptLocked: false },
            { sdPrompt: 'a serene alpine lake at dawn', promptLocked: false },
            { sdPrompt: '', promptLocked: false }
        ]);
        expect(s).toEqual(expect.objectContaining({ count: 2, total: 3 }));
        expect(s.message).toBe('2 of 3 pages have no LLM landscape prompt (the placeholder stored when the LLM could not be reached) — check the chat config, then Regenerate prompt on those pages');
    });

    it('summary grammar for a single page', () => {
        let s = placeholderPromptSummary([{ sdPrompt: 'landscape, x', promptLocked: false }]);
        expect(s.count).toBe(1);
        expect(s.message.indexOf('1 of 1 page has no LLM landscape prompt')).toBe(0);
    });
});
