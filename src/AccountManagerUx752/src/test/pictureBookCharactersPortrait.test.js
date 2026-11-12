// @vitest-environment jsdom
/**
 * PictureBook V3 — character manager `no portrait` badge after Regenerate Portrait (P10-D 3, §6.C 267 i).
 *
 * Why: §16 gate shot `05d` showed the detail pane with the freshly rendered portrait and the toast
 * "Created 1 image(s)" while the list row still read "no portrait". doReimage awaited reimage(), which
 * resolves when its dialog OPENS, so the list refresh ran before Generate. The manager now hands reimage
 * an `onImages` callback and re-reads GET /{book}/characters (server-side `hasPortrait`, uncached) from it.
 *
 * reimage.js is replaced by a stub that captures the callback (its own ordering is covered by
 * reimageOnImages.test.js); the manager, its fetch wire and its rendering are real.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';

let reimageCalls = [];
vi.mock('../workflows/reimage.js', () => ({
    reimage: vi.fn(async (entity, inst, opts) => { reimageCalls.push({ entity, inst, opts }); })
}));

function jsonResponse(status, body) {
    let text = JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}
function findAll(vnode, pred, out) {
    out = out || [];
    if (!vnode) return out;
    if (Array.isArray(vnode)) { vnode.forEach(v => findAll(v, pred, out)); return out; }
    if (typeof vnode !== 'object') return out;
    if (vnode.attrs && pred(vnode)) out.push(vnode);
    if (vnode.children) findAll(vnode.children, pred, out);
    return out;
}
function rowOf(pbc, oid) {
    let rows = findAll(pbc.renderCharacterManagerContent(), v => v.attrs['data-char-item'] === oid);
    expect(rows.length).toBe(1);
    return textOf(rows[0]).replace(/\s+/g, ' ').trim();
}

const ENTITY = { objectId: 'c1', id: 11, name: 'Alekhya', profile: { id: 22, objectId: 'p1', portrait: null } };
let origFetch, pbc, hasPortrait, listCalls;

beforeEach(async () => {
    origFetch = global.fetch;
    reimageCalls = [];
    hasPortrait = false;
    listCalls = 0;
    global.fetch = vi.fn(async (u) => {
        let url = String(u);
        if (/\/book-1\/characters$/.test(url)) {
            listCalls++;
            return jsonResponse(200, [
                { objectId: 'c1', name: 'Alekhya', hasPortrait: hasPortrait, apparelCount: 1 },
                { objectId: 'c2', name: 'Biju Rao', hasPortrait: false, apparelCount: 1 }
            ]);
        }
        if (/\/book-1\/affected-by\//.test(url)) return jsonResponse(404, { error: 'no workflow' });
        return jsonResponse(404, { error: 'unmocked ' + url });
    });
    pbc = await import('../workflows/pictureBookCharacters.js');
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'getFull').mockResolvedValue(Object.assign({}, ENTITY, { profile: Object.assign({}, ENTITY.profile) }));
    vi.spyOn(am7model, 'prepareInstance').mockImplementation((entity) => ({ entity }));
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    await pbc.initCharacterManager('book-1', { standalone: true });
});

afterEach(() => {
    pbc.__setPbCharStateForTest({ selectedInst: null, selectedObjectId: null, affected: null, wardrobe: null });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

describe('Regenerate Portrait → `no portrait` badge', () => {
    it('the badge is gone after the portrait is created: the list is re-read from onImages, not at dialog open', async () => {
        expect(listCalls).toBe(1);
        expect(rowOf(pbc, 'c1')).toContain('no portrait');
        await pbc.__pbCharSelectCharacterForTest('c1');
        expect(pbc.__pbCharStateForTest().selectedInst.entity.objectId).toBe('c1');

        // Regenerate Portrait: reimage() returns as soon as its dialog is open — nothing rendered yet.
        await pbc.__pbCharDoReimageForTest();
        expect(reimageCalls.length).toBe(1);
        expect(reimageCalls[0].inst).toBe(pbc.__pbCharStateForTest().selectedInst);
        expect(typeof reimageCalls[0].opts.onImages).toBe('function');
        expect(listCalls, 'no list re-read at dialog open (the pre-fix refresh that raced the render)').toBe(1);
        expect(rowOf(pbc, 'c1')).toContain('no portrait');

        // Generate finishes: reimage has patched identity.profile and the server now answers hasPortrait=true.
        hasPortrait = true;
        await reimageCalls[0].opts.onImages([{ objectId: 'img-1' }]);
        expect(listCalls).toBe(2);
        expect(rowOf(pbc, 'c1')).not.toContain('no portrait');
        expect(rowOf(pbc, 'c1')).toContain('Alekhya');
        expect(rowOf(pbc, 'c2'), 'other rows keep their own state').toContain('no portrait');
    });

    it('a Generate that fails never calls back, so the badge (correctly) stays', async () => {
        await pbc.__pbCharSelectCharacterForTest('c1');
        await pbc.__pbCharDoReimageForTest();
        expect(listCalls).toBe(1);
        expect(rowOf(pbc, 'c1')).toContain('no portrait');
    });

    it('doReimage is a no-op without a selected character', async () => {
        pbc.__setPbCharStateForTest({ selectedInst: null, selectedObjectId: null });
        await pbc.__pbCharDoReimageForTest();
        expect(reimageCalls.length).toBe(0);
    });
});
