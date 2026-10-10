// @vitest-environment jsdom
/**
 * P0-10d (PictureBook V3 Phase 0, gap G-32 UX half) — ChapBook book-level SD config persistence.
 *
 * What landed in features/chapBook.js and is exercised here against the REAL exported functions
 * (stubbed global.fetch, no network / LLM / SD):
 *
 *   1. computeBookSdDelta(inst, stored) — the sparse wire shape. Unstored book: every non-null
 *      entity field minus identity + the server's BOOK_SD_CONFIG_SKIP set. Stored book: ONLY the
 *      fields the user wrote through inst.api (inst.changes), same skip set. Always tagged
 *      schema:'olio.sd.config'; null when nothing is left.
 *   2. getBookSettings / putBookSettings — GET/PUT {cbBase}/{bookObjectId}/settings, `{}` → null,
 *      non-2xx → rejects; PUT body is { schema:'olio.pictureBookRequest', sdConfig:<sparse> }.
 *   3. syncBookSdConfigForRender — persist-before-render: PUT ok → { sdConfig:null } (the stored row
 *      carries it); PUT fails → { sdConfig:<delta> } as a one-shot render overlay, never throws.
 *
 * am7sd.loadConfig is stubbed (it would otherwise hit am7client/page.makePath) so the "book has no
 * stored row" seed path is deterministic.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { am7model } from '../core/model.js';
import '../core/formDef.js';
import { am7sd } from '../components/sdConfig.js';
import { page } from '../core/pageClient.js';

function makeInst(entity) {
    let e = Object.assign({ schema: 'olio.sd.config' }, entity);
    return am7model.prepareInstance(e, am7model.forms.sdConfig);
}

describe('computeBookSdDelta — sparse book-level SD config', () => {
    let computeBookSdDelta;
    beforeEach(async () => {
        ({ computeBookSdDelta } = await import('../features/chapBook.js'));
    });

    it('unstored (stored=false): every non-null field, minus identity and the server skip set', () => {
        let inst = makeInst({
            bodyStyle: 'x', steps: 20, objectId: 'abc', name: 'book-sdConfig', description: 'd',
            imagePath: 'p', tags: ['t'], cfg: null
        });
        let delta = computeBookSdDelta(inst, false);
        expect(delta).not.toBeNull();
        expect(delta.bodyStyle).toBe('x');
        expect(delta.steps).toBe(20);
        expect(delta.schema).toBe('olio.sd.config');
        expect(delta).not.toHaveProperty('objectId');
        expect(delta).not.toHaveProperty('name');
        expect(delta).not.toHaveProperty('description');
        expect(delta).not.toHaveProperty('imagePath');
        expect(delta).not.toHaveProperty('tags');
        expect(delta).not.toHaveProperty('cfg');
    });

    it('stored (stored=true): null with no changes; exactly the changed field after inst.api writes', () => {
        let inst = makeInst({ bodyStyle: 'x', steps: 20, objectId: 'abc', name: 'book-sdConfig' });
        expect(computeBookSdDelta(inst, true)).toBeNull();

        inst.api.bodyStyle('y');
        expect(computeBookSdDelta(inst, true)).toEqual({ bodyStyle: 'y', schema: 'olio.sd.config' });

        // description is in the skip set even when the user changed it.
        inst.api.description('z');
        let delta = computeBookSdDelta(inst, true);
        expect(delta).toEqual({ bodyStyle: 'y', schema: 'olio.sd.config' });
        expect(delta).not.toHaveProperty('description');
    });

    it('stored: a change back to null/undefined is dropped (server treats missing as unchanged)', () => {
        let inst = makeInst({ bodyStyle: 'x' });
        inst.api.bodyStyle(null);
        expect(computeBookSdDelta(inst, true)).toBeNull();
    });

    it('null / entity-less instance → null', () => {
        expect(computeBookSdDelta(null, false)).toBeNull();
        expect(computeBookSdDelta({}, true)).toBeNull();
    });
});

describe('getBookSettings / putBookSettings — {cbBase}/{bookObjectId}/settings', () => {
    let getBookSettings, putBookSettings, fetchMock;

    beforeEach(async () => {
        ({ getBookSettings, putBookSettings } = await import('../features/chapBook.js'));
        fetchMock = vi.fn();
        global.fetch = fetchMock;
    });
    afterEach(() => {
        vi.restoreAllMocks();
        delete global.fetch;
    });

    it('GET: `{}` (book has no stored row) → null', async () => {
        fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
        expect(await getBookSettings('book-1')).toBeNull();
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(fetchMock.mock.calls[0][0]).toMatch(/\/rest\/olio\/chap-book\/book-1\/settings$/);
        expect(fetchMock.mock.calls[0][1].method).toBeUndefined(); // plain GET
    });

    it('GET: a stored row (carries schema) is returned as-is', async () => {
        let row = { schema: 'olio.sd.config', objectId: 'cfg-1', name: 'book-sdConfig', bodyStyle: 'x', steps: 30 };
        fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => row });
        expect(await getBookSettings('book-1')).toEqual(row);
    });

    it('GET: 404 → rejects', async () => {
        fetchMock.mockResolvedValue({ ok: false, status: 404, json: async () => ({}) });
        await expect(getBookSettings('nope')).rejects.toThrow(/404/);
    });

    it('PUT: method, URL and the exact olio.pictureBookRequest body', async () => {
        let stored = { schema: 'olio.sd.config', objectId: 'cfg-9', bodyStyle: 'y' };
        fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => stored });
        let result = await putBookSettings('book-7', { bodyStyle: 'y', schema: 'olio.sd.config' });
        expect(result).toEqual(stored);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        let [url, opts] = fetchMock.mock.calls[0];
        expect(url).toMatch(/\/rest\/olio\/chap-book\/book-7\/settings$/);
        expect(opts.method).toBe('PUT');
        expect(opts.headers['Content-Type']).toBe('application/json');
        expect(JSON.parse(opts.body)).toEqual({
            schema: 'olio.pictureBookRequest',
            sdConfig: { bodyStyle: 'y', schema: 'olio.sd.config' }
        });
    });

    it('PUT: non-2xx → rejects with the status and response text', async () => {
        fetchMock.mockResolvedValue({ ok: false, status: 403, text: async () => 'not a ChapBook' });
        await expect(putBookSettings('book-7', { bodyStyle: 'y' })).rejects.toThrow(/403.*not a ChapBook/);
    });
});

describe('syncBookSdConfigForRender — persist before render, overlay only on save failure', () => {
    let syncBookSdConfigForRender, fetchMock;
    const seed = { schema: 'olio.sd.config', objectId: 'pref-1', name: 'sdcfg-default', bodyStyle: 'seeded', steps: 25 };

    beforeEach(async () => {
        ({ syncBookSdConfigForRender } = await import('../features/chapBook.js'));
        // The "no stored row" branch seeds from the user's sdcfg-default; keep that off the network.
        vi.spyOn(am7sd, 'loadConfig').mockResolvedValue(Object.assign({}, seed));
        if (typeof page.toast !== 'function') page.toast = () => {};
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        fetchMock = vi.fn();
        global.fetch = fetchMock;
    });
    afterEach(() => {
        vi.restoreAllMocks();
        delete global.fetch;
    });

    function stubGetEmptyThenPut(putResponse) {
        fetchMock.mockImplementation(async (url, opts) => {
            if (opts && opts.method === 'PUT') return putResponse;
            return { ok: true, status: 200, json: async () => ({}) };
        });
    }

    it('GET {} then PUT 500 → resolves { sdConfig: <delta> } (not null), warns, does not throw', async () => {
        stubGetEmptyThenPut({ ok: false, status: 500, text: async () => 'boom' });
        let { sdConfig } = await syncBookSdConfigForRender('book-sync-fail');
        expect(sdConfig).not.toBeNull();
        expect(sdConfig.schema).toBe('olio.sd.config');
        expect(sdConfig.bodyStyle).toBe('seeded');
        expect(sdConfig.steps).toBe(25);
        // identity / name from the seed never reach the wire
        expect(sdConfig).not.toHaveProperty('objectId');
        expect(sdConfig).not.toHaveProperty('name');
        let puts = fetchMock.mock.calls.filter(c => c[1] && c[1].method === 'PUT');
        expect(puts.length).toBe(1);
        expect(puts[0][0]).toMatch(/\/chap-book\/book-sync-fail\/settings$/);
        let warns = page.toast.mock.calls.filter(c => c[0] === 'warn');
        expect(warns.length).toBe(1);
        expect(warns[0][1]).toMatch(/rendering with the dialog values/);
    });

    it('GET {} then PUT 200 row → { sdConfig: null } and the whole seeded entity was PUT once', async () => {
        let storedRow = { schema: 'olio.sd.config', objectId: 'cfg-new', name: 'book-sdConfig', bodyStyle: 'seeded', steps: 25 };
        stubGetEmptyThenPut({ ok: true, status: 200, json: async () => storedRow });
        let { sdConfig } = await syncBookSdConfigForRender('book-sync-ok');
        expect(sdConfig).toBeNull();
        let puts = fetchMock.mock.calls.filter(c => c[1] && c[1].method === 'PUT');
        expect(puts.length).toBe(1);
        let body = JSON.parse(puts[0][1].body);
        expect(body.schema).toBe('olio.pictureBookRequest');
        expect(body.sdConfig.bodyStyle).toBe('seeded');
        expect(body.sdConfig.steps).toBe(25);
        expect(body.sdConfig.schema).toBe('olio.sd.config');
        expect(body.sdConfig).not.toHaveProperty('objectId');
        let ok = page.toast.mock.calls.filter(c => c[0] === 'success');
        expect(ok.length).toBe(1);

        // Now stored: a second sync with nothing changed sends NOTHING and still returns null.
        fetchMock.mockClear();
        page.toast.mockClear();
        let again = await syncBookSdConfigForRender('book-sync-ok');
        expect(again.sdConfig).toBeNull();
        expect(fetchMock).not.toHaveBeenCalled();
    });
});

/**
 * P0-10d verifier findings (2026-10-09):
 *   Medium — a failed GET .../settings was treated as "no row", so the panel seeded from sdcfg-default
 *            and the next Save/Render PUT the whole seed over a real stored row.
 *   Low    — the in-flight guard compared keys, and null is both the create-view key and "idle", so a
 *            create-view build settling after a book build overwrote the book's instance with the seed.
 */
describe('ensureRenderSdConfig — a failed GET never seeds; a stale build never publishes', () => {
    let ensureRenderSdConfig, syncBookSdConfigForRender, fetchMock;
    const seed = { schema: 'olio.sd.config', objectId: 'pref-1', name: 'sdcfg-default', bodyStyle: 'seeded', steps: 25 };
    const isPut = c => c[1] && c[1].method === 'PUT';

    beforeEach(async () => {
        ({ ensureRenderSdConfig, syncBookSdConfigForRender } = await import('../features/chapBook.js'));
        vi.spyOn(am7sd, 'loadConfig').mockResolvedValue(Object.assign({}, seed));
        if (typeof page.toast !== 'function') page.toast = () => {};
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        fetchMock = vi.fn();
        global.fetch = fetchMock;
    });
    afterEach(() => {
        vi.restoreAllMocks();
        delete global.fetch;
    });

    it('GET 500 → resolves null, no seed, warn toast; sync sends no PUT; Retry after recovery loads the stored row', async () => {
        let row = { schema: 'olio.sd.config', objectId: 'cfg-s', name: 'book-sdConfig', bodyStyle: 'stored', steps: 33 };
        let gets = 0;
        fetchMock.mockImplementation(async (url, opts) => {
            if (isPut([url, opts])) throw new Error('no PUT may happen in this test');
            gets++;
            // the load AND the render-time sync both fail; the user's Retry then succeeds
            if (gets <= 2) return { ok: false, status: 500, json: async () => ({}) };
            return { ok: true, status: 200, json: async () => row };
        });

        expect(await ensureRenderSdConfig('book-get-fail')).toBeNull();
        expect(am7sd.loadConfig).not.toHaveBeenCalled();
        let warns = page.toast.mock.calls.filter(c => c[0] === 'warn');
        expect(warns.length).toBe(1);
        expect(warns[0][1]).toMatch(/Could not load book image settings: .*500/);

        // Render while the load is failed: nothing to send, the server's stored row stays authoritative.
        let { sdConfig } = await syncBookSdConfigForRender('book-get-fail');
        expect(sdConfig).toBeNull();
        expect(fetchMock.mock.calls.filter(isPut).length).toBe(0);
        expect(am7sd.loadConfig).not.toHaveBeenCalled();
        expect(gets).toBe(2);

        // Retry (what the panel's Retry button calls): the failed build left no memo, so it re-attempts.
        let inst = await ensureRenderSdConfig('book-get-fail');
        expect(inst).not.toBeNull();
        expect(inst.entity.bodyStyle).toBe('stored');
        expect(inst.entity.steps).toBe(33);
        expect(inst.entity).not.toHaveProperty('objectId');
        expect(gets).toBe(3);

        // Memoised and stored: the next sync neither fetches nor PUTs.
        let again = await syncBookSdConfigForRender('book-get-fail');
        expect(again.sdConfig).toBeNull();
        expect(gets).toBe(3);
        expect(fetchMock.mock.calls.filter(isPut).length).toBe(0);
        expect(am7sd.loadConfig).not.toHaveBeenCalled();
    });

    it('a create-view (null key) build that settles after a later book build does not replace the book instance', async () => {
        let releaseSeed;
        am7sd.loadConfig.mockImplementation(() => new Promise(resolve => {
            releaseSeed = () => resolve(Object.assign({}, seed));
        }));
        let row = { schema: 'olio.sd.config', objectId: 'cfg-r', name: 'book-sdConfig', bodyStyle: 'stored-race', steps: 44 };
        fetchMock.mockImplementation(async () => ({ ok: true, status: 200, json: async () => row }));

        let createBuild = ensureRenderSdConfig(null);        // hangs on the seed
        let bookBuild = ensureRenderSdConfig('book-race');   // settles first
        let bookInst = await bookBuild;
        expect(bookInst.entity.bodyStyle).toBe('stored-race');

        releaseSeed();
        expect(await createBuild).toBeNull();                 // superseded: discarded, not published

        // The book instance survived: a re-ask is a memo hit (no fetch) and returns the SAME instance.
        fetchMock.mockClear();
        let bookAgain = await ensureRenderSdConfig('book-race');
        expect(bookAgain).toBe(bookInst);
        expect(bookAgain.entity.bodyStyle).toBe('stored-race');
        expect(fetchMock).not.toHaveBeenCalled();

        // And the stored, unchanged book sends nothing on render — the seed never became its delta.
        let { sdConfig } = await syncBookSdConfigForRender('book-race');
        expect(sdConfig).toBeNull();
        expect(fetchMock.mock.calls.filter(isPut).length).toBe(0);
    });
});
