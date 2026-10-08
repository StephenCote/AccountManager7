/**
 * KI-13 inventory item (cache invalidation / container resolution) — am7client.find()/make() sub-type casing.
 *
 * Views and components resolve containers through page.findObject/page.makePath with the sub-type written
 * in whatever case the author used ("DATA" in list.js/picker.js/tree.js/sdConfig.js, "data" in panel.js/
 * dnd.js/pageClient.systemLibrary/formDef.js, "UNKNOWN" in pageClient.navigateToPath). PathService
 * upper-cases it, so every spelling resolves the same group — but am7client.makeFind keyed its cache on the
 * raw string ("FIND-data" vs "FIND-DATA"), so the same path was fetched once per spelling and cached twice.
 * Since 2026-10-07 makeFind normalizes the sub-type before the cache lookup and the URL.
 *
 * mithril.request is mocked (records each GET); the real am7client is exercised.
 */
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest';

const { requests, mockRequest } = vi.hoisted(() => {
    const requests = [];
    const mockRequest = vi.fn((opts) => {
        requests.push(opts);
        return Promise.resolve({ schema: 'auth.group', id: 42, objectId: 'G-42', name: 'Gallery', path: '/home/e2e/Gallery' });
    });
    return { requests, mockRequest };
});

vi.mock('mithril', () => ({
    default: Object.assign((tag, attrs, children) => ({ tag, attrs, children }), {
        request: mockRequest,
        redraw: vi.fn(),
        route: { set: vi.fn(), get: () => '/' },
        trust: (s) => s
    })
}));

let am7client;

beforeAll(async () => {
    globalThis.window = globalThis.window || { location: { protocol: 'https:', host: 'localhost:8899', port: '8899' } };
    ({ am7client } = await import('../core/am7client.js'));
});

beforeEach(async () => {
    requests.length = 0;
    mockRequest.mockClear();
    await am7client.clearCache('auth.group', true);
});

describe('am7client.find / make — sub-type casing does not split the cache', () => {
    it('find("data") then find("DATA") for the same path issues ONE request; the second is a cache hit', async () => {
        let a = await am7client.find('auth.group', 'data', '~/Gallery');
        expect(a.objectId).toBe('G-42');
        expect(requests.length).toBe(1);
        expect(requests[0].method).toBe('GET');
        // The URL carries the normalized sub-type (what PathService would have used anyway).
        expect(requests[0].url).toMatch(/\/rest\/path\/find\/auth\.group\/DATA\//);

        let b = await Promise.resolve(am7client.find('auth.group', 'DATA', '~/Gallery'));
        expect(b.objectId).toBe('G-42');
        expect(requests.length).toBe(1);
    });

    it('the cached-hit callback path fires fH with the cached record and does not request again', async () => {
        await am7client.find('auth.group', 'DATA', '~/Voices');
        expect(requests.length).toBe(1);
        let seen = null;
        am7client.find('auth.group', 'Data', '~/Voices', (v) => { seen = v; });
        expect(seen && seen.objectId).toBe('G-42');
        expect(requests.length).toBe(1);
    });

    it('different paths or sub-types are still distinct entries', async () => {
        await am7client.find('auth.group', 'data', '~/Gallery');
        await am7client.find('auth.group', 'data', '~/Characters');
        await am7client.find('auth.group', 'bucket', '~/Gallery');
        expect(requests.length).toBe(3);
        expect(requests.map((r) => r.url.replace(/^.*\/rest\/path\//, ''))).toEqual([
            'find/auth.group/DATA/~/Gallery',
            'find/auth.group/DATA/~/Characters',
            'find/auth.group/BUCKET/~/Gallery'
        ]);
    });

    it('make() shares the same normalized key, so a path made once is found from cache afterwards', async () => {
        await am7client.make('auth.group', 'data', '~/Data/.preferences');
        expect(requests.length).toBe(1);
        expect(requests[0].url).toMatch(/\/rest\/path\/make\/auth\.group\/DATA\//);
        // Dotted/absolute paths are B64-encoded in the URL; the cache key uses that encoded form, so the
        // later find() with the same path (any casing) is a hit.
        let g = await Promise.resolve(am7client.find('auth.group', 'DATA', '~/Data/.preferences'));
        expect(g.objectId).toBe('G-42');
        expect(requests.length).toBe(1);
    });
});
