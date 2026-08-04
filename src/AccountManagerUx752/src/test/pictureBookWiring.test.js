/**
 * PictureBook client-wiring behavioral tests.
 *
 * These exercise the actual sceneExtractor.js client wrappers that this change wired up, by
 * stubbing global.fetch and asserting the exact endpoint / method / body / AbortSignal each wrapper
 * sends — i.e. the observable client-side contract with the REST layer. They are NOT typeof-only
 * checks: each drives the real function and inspects the real request it issued.
 *
 * What they cover:
 *  - U1: cancelPictureBook() POSTs to /rest/olio/picture-book/{key}/cancel and returns the parsed
 *        {cancelled} body (the KI-10 endpoint that previously had no client caller).
 *  - U1: prepareSceneImagePrompts() forwards the caller's AbortSignal to fetch, so a Cancel during
 *        the prepare-images phase actually stops the client awaiting.
 *  - U3: regenerateBlurb() POSTs to /scene/{id}/blurb and returns the parsed {blurb} body.
 *
 * Behavior that needs a live browser/stack (the Cancel button actually aborting an in-flight
 * generation, the Step 2 "Regenerate blurb" button re-rendering) is NOT claimed here — see the
 * task report's SUSPECTED section.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';

let calls;

function mockFetch(responseBody, ok = true, status = 200) {
    calls = [];
    let fn = vi.fn(function (url, opts) {
        calls.push({ url, opts: opts || {} });
        return Promise.resolve({
            ok: ok,
            status: status,
            json: function () { return Promise.resolve(responseBody); }
        });
    });
    vi.stubGlobal('fetch', fn);
    return fn;
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('cancelPictureBook (U1 — KI-10 cancel wrapper)', () => {
    it('POSTs to /rest/olio/picture-book/{key}/cancel and returns the parsed {cancelled} body', async () => {
        mockFetch({ cancelled: true });
        const { cancelPictureBook } = await import('../workflows/sceneExtractor.js');

        let result = await cancelPictureBook('book-abc-123');

        expect(calls).toHaveLength(1);
        expect(calls[0].url).toContain('/rest/olio/picture-book/book-abc-123/cancel');
        expect(calls[0].opts.method).toBe('POST');
        expect(calls[0].opts.credentials).toBe('include');
        expect(result).toEqual({ cancelled: true });
    });

    it('returns {cancelled:false} (not an error) when nothing was in-flight for that key', async () => {
        mockFetch({ cancelled: false });
        const { cancelPictureBook } = await import('../workflows/sceneExtractor.js');

        let result = await cancelPictureBook('work-xyz');
        expect(result).toEqual({ cancelled: false });
    });

    it('throws on a non-ok response (so callers can .catch it)', async () => {
        mockFetch({}, false, 500);
        const { cancelPictureBook } = await import('../workflows/sceneExtractor.js');

        await expect(cancelPictureBook('k')).rejects.toThrow(/Cancel failed: 500/);
    });
});

describe('prepareSceneImagePrompts (U1 — AbortSignal forwarding + full common config)', () => {
    it('POSTs to /{bookObjectId}/prepare-images with the scene ids + full common sdConfig and forwards the AbortSignal', async () => {
        mockFetch({ prepared: 2 });
        const { prepareSceneImagePrompts } = await import('../workflows/sceneExtractor.js');

        // New contract: the whole common olio.sd.config entity is sent (style is the seam), NOT a
        // bare {style} object — the server only types a schema-tagged record as a BaseRecord.
        let common = { schema: 'olio.sd.config', style: 'digitalArt', steps: 30, width: 1024, height: 768 };
        let controller = new AbortController();
        await prepareSceneImagePrompts('book-1', ['s1', 's2'], 'contentAnalysis', common, null, controller.signal);

        expect(calls).toHaveLength(1);
        expect(calls[0].url).toContain('/rest/olio/picture-book/book-1/prepare-images');
        expect(calls[0].opts.method).toBe('POST');
        // The exact object the caller passed must reach fetch — this is what lets Cancel stop the await.
        expect(calls[0].opts.signal).toBe(controller.signal);

        let body = JSON.parse(calls[0].opts.body);
        expect(body.schema).toBe('olio.pictureBookRequest');
        expect(body.sceneObjectIds).toEqual(['s1', 's2']);
        expect(body.chatConfig).toBe('contentAnalysis');
        expect(body.sdConfig).toEqual(common);
    });

    it('omits the signal cleanly when none is passed (back-compat with non-cancel callers)', async () => {
        mockFetch({});
        const { prepareSceneImagePrompts } = await import('../workflows/sceneExtractor.js');

        await prepareSceneImagePrompts('book-2', ['s1'], null, null, null);
        expect(calls[0].opts.signal).toBeUndefined();
    });
});

describe('generateSceneImage (real olio.sd.config common + per-scene delta)', () => {
    it('POSTs to /scene/{id}/generate with the common sdConfig, the per-scene delta, and forwards the AbortSignal', async () => {
        mockFetch({ imageObjectId: 'img-9', seed: 12345 });
        const { generateSceneImage } = await import('../workflows/sceneExtractor.js');

        let common = { schema: 'olio.sd.config', style: 'digitalArt', steps: 30, useKontext: false };
        let delta = { schema: 'olio.sd.config', steps: 45 };
        let controller = new AbortController();
        let result = await generateSceneImage('scene-7', {
            sdConfig: common,
            sdConfigOverride: delta,
            chatConfig: 'contentAnalysis',
            promptTemplate: 'pictureBook.landscape-prompt'
        }, controller.signal);

        expect(calls).toHaveLength(1);
        expect(calls[0].url).toContain('/rest/olio/picture-book/scene/scene-7/generate');
        expect(calls[0].opts.method).toBe('POST');
        expect(calls[0].opts.signal).toBe(controller.signal);

        let body = JSON.parse(calls[0].opts.body);
        expect(body.schema).toBe('olio.pictureBookRequest');
        expect(body.sdConfig).toEqual(common);
        expect(body.sdConfigOverride).toEqual(delta);
        expect(body.chatConfig).toBe('contentAnalysis');
        expect(body.promptTemplate).toBe('pictureBook.landscape-prompt');
        // No bespoke merged DEFAULT_SD_CONFIG blob and no bare `style` word anymore.
        expect(result.imageObjectId).toBe('img-9');
    });

    it('omits sdConfigOverride/compositeSdConfig when not supplied (unedited scene sends no delta)', async () => {
        mockFetch({ imageObjectId: 'img-1' });
        const { generateSceneImage } = await import('../workflows/sceneExtractor.js');

        await generateSceneImage('scene-1', { sdConfig: { schema: 'olio.sd.config', style: 'digitalArt' } });
        let body = JSON.parse(calls[0].opts.body);
        expect('sdConfigOverride' in body).toBe(false);
        expect('compositeSdConfig' in body).toBe(false);
    });
});

describe('setBookSdConfig (PUT /settings — store the common config once)', () => {
    it('PUTs the common (+ optional composite) olio.sd.config to /{bookObjectId}/settings', async () => {
        mockFetch({ stored: true });
        const { setBookSdConfig } = await import('../workflows/sceneExtractor.js');

        let common = { schema: 'olio.sd.config', style: 'digitalArt', hires: false };
        let composite = { schema: 'olio.sd.config', style: 'photograph' };
        await setBookSdConfig('book-5', common, composite);

        expect(calls).toHaveLength(1);
        expect(calls[0].url).toContain('/rest/olio/picture-book/book-5/settings');
        expect(calls[0].opts.method).toBe('PUT');
        let body = JSON.parse(calls[0].opts.body);
        expect(body.schema).toBe('olio.pictureBookRequest');
        expect(body.sdConfig).toEqual(common);
        expect(body.compositeSdConfig).toEqual(composite);
    });

    it('omits compositeSdConfig when not supplied', async () => {
        mockFetch({ stored: true });
        const { setBookSdConfig } = await import('../workflows/sceneExtractor.js');

        await setBookSdConfig('book-6', { schema: 'olio.sd.config', style: 'digitalArt' });
        let body = JSON.parse(calls[0].opts.body);
        expect('compositeSdConfig' in body).toBe(false);
    });
});

describe('regenerateBlurb (U3 — wired-in blurb regen wrapper)', () => {
    it('POSTs to /scene/{id}/blurb and returns the parsed {blurb} body', async () => {
        mockFetch({ blurb: 'A hush fell over the courtyard.' });
        const { regenerateBlurb } = await import('../workflows/sceneExtractor.js');

        let result = await regenerateBlurb('scene-42', 'contentAnalysis');

        expect(calls).toHaveLength(1);
        expect(calls[0].url).toContain('/rest/olio/picture-book/scene/scene-42/blurb');
        expect(calls[0].opts.method).toBe('POST');
        let body = JSON.parse(calls[0].opts.body);
        expect(body.chatConfig).toBe('contentAnalysis');
        expect(result.blurb).toBe('A hush fell over the courtyard.');
    });
});
