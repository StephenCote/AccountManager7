/**
 * KI-35 — the dress-up/dress-down patches must carry every field the server's writer validates,
 * and their results must not be swallowed.
 *
 * olio.wearable inherits olio.item -> common.name, whose `name` field is required/allowNull:false
 * with a $notEmpty rule. The writer validates the PATCH RECORD ITSELF, not the merged result, so an
 * {id, inuse} patch is rejected before it can write. Verified live against the backend
 * (TestPictureBookKnownIssues#TestKi35ActingUserCanToggleInuseOnOlioOwnedWearable): the same patch
 * succeeds with `name` and does not persist without it. The reported symptom was inuse stuck true
 * forever ("always worn") while the UI reported success.
 *
 * These are behavioral checks: the real dressApparel() is invoked and the real patch bodies it hands
 * to page.patchObject are captured. Only the network boundary (page.search/page.patchObject) is
 * stubbed.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import '../core/formDef.js';

function mockM(tag, attrs, children) { return { tag, attrs, children }; }
mockM.redraw = vi.fn();
mockM.request = vi.fn();
vi.mock('mithril', () => ({ default: mockM }));

let patched = [];
let patchResult = true;
let toasts = [];

const WEARABLES = [
    { schema: 'olio.wearable', id: 11, objectId: 'w-11', name: 'undershirt', level: 'UNDER', inuse: true, groupId: 5 },
    { schema: 'olio.wearable', id: 12, objectId: 'w-12', name: 'blouse', level: 'BASE', inuse: true, groupId: 5 },
    { schema: 'olio.wearable', id: 13, objectId: 'w-13', name: 'coat', level: 'OUTER', inuse: true, groupId: 5 }
];
const APPAREL = {
    schema: 'olio.apparel', id: 7, objectId: 'ap-7', name: 'Field Outfit', description: '',
    wearables: WEARABLES
};

vi.mock('../core/am7client.js', () => ({
    am7client: { base: () => 'https://localhost:8443/rest', clearCache: vi.fn().mockResolvedValue(true) }
}));

// vi.mock is hoisted above every top-level binding, so the stub must be built INSIDE the factory.
vi.mock('../core/pageClient.js', () => ({
    page: {
        toast: (lvl, msg) => { toasts.push(lvl + ':' + msg); },
        search: async (q) => {
            let type = q.entity ? q.entity.type : q.type;
            if (type === 'olio.apparel') return { results: [JSON.parse(JSON.stringify(APPAREL))] };
            return { results: JSON.parse(JSON.stringify(WEARABLES)) };
        },
        patchObject: async (body) => { patched.push(body); return patchResult; }
    }
}));
const { page: pageStub } = await import('../core/pageClient.js');

am7model._view = am7view;
am7model._page = pageStub;
// Minimal stand-in for the real query builder: enough surface for dressApparel's own calls
// (cache/range/field) while recording the type so page.search can answer correctly.
function stubQuery(type) {
    let q = {
        entity: { type: type, request: [], fields: [] },
        type: type,
        cache: function () { return q; },
        range: function () { return q; },
        field: function (name, value) { let f = { name: name, value: value }; q.entity.fields.push(f); return f; },
        sort: function () { return q; }
    };
    return q;
}
am7model._client = { newQuery: (t) => stubQuery(t), clearCache: vi.fn() };

describe('dressApparel patches (KI-35)', () => {
    beforeEach(() => {
        patched = [];
        toasts = [];
        patchResult = true;
    });

    it('every olio.wearable patch carries the required `name` (and identity), not just {id, inuse}', async () => {
        const { am7olio } = await import('../components/olio.js');
        await am7olio.dressApparel({ objectId: 'ap-7' }, false);

        let wearablePatches = patched.filter((p) => p.schema === 'olio.wearable');
        expect(wearablePatches.length).toBeGreaterThan(0);
        for (let p of wearablePatches) {
            // The whole point: without `name` the writer rejects the patch and inuse never changes.
            expect(typeof p.name).toBe('string');
            expect(p.name.length).toBeGreaterThan(0);
            expect(p.id).toBeGreaterThan(0);
            expect(typeof p.inuse).toBe('boolean');
        }
    });

    it('the olio.apparel description patch also carries `name` (olio.apparel inherits common.name too)', async () => {
        const { am7olio } = await import('../components/olio.js');
        await am7olio.dressApparel({ objectId: 'ap-7' }, false);

        let apparelPatches = patched.filter((p) => p.schema === 'olio.apparel');
        expect(apparelPatches.length).toBeGreaterThan(0);
        for (let p of apparelPatches) {
            expect(typeof p.name).toBe('string');
            expect(p.name.length).toBeGreaterThan(0);
        }
    });

    it('a rejected patch is reported, not swallowed — the silent failure is what made inuse look stuck', async () => {
        patchResult = false;
        const { am7olio } = await import('../components/olio.js');
        let result = await am7olio.dressApparel({ objectId: 'ap-7' }, false);

        expect(result).toBe(false);
        expect(toasts.some((t) => t.startsWith('error:'))).toBe(true);
    });
});
