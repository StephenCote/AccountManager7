/**
 * workflows/reimage.js — `reimage(entity, inst, opts)` and the `opts.onImages(images)` callback (P10-D 3, §6.C 267 i).
 *
 * Why: reimage() resolves when its Dialog OPENS (Dialog.open does not await the Generate action), so a
 * caller that `await reimage(...)` and then refreshes runs its refresh before any image exists. The
 * character manager did exactly that, and its `no portrait` badge survived "Created 1 image(s)". The
 * callback is invoked from inside the Generate action, after the portrait patch and the success toast.
 *
 * Real reimage() is invoked and the real Generate action's onclick is run end-to-end; only the network
 * boundary (m.request), the Dialog, am7client, pageClient and the SD config service are stubbed — the
 * same shape as reimageApparelConfig.test.js.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import '../core/formDef.js';

function mockM(tag, attrs, children) {
    if (typeof attrs === 'string' || Array.isArray(attrs)) { children = attrs; attrs = {}; }
    return { tag, attrs: attrs || {}, children };
}
mockM.redraw = vi.fn();
mockM.trust = (s) => s;
mockM.route = { set: vi.fn(), get: () => '/main' };
mockM.request = vi.fn();
vi.mock('mithril', () => ({ default: mockM }));

let capturedDialogCfg = null;
vi.mock('../components/dialogCore.js', () => ({
    Dialog: { open: vi.fn((cfg) => { capturedDialogCfg = cfg; }), close: vi.fn() }
}));

/** Every observable side effect in the order it happened — the assertions are about ORDER. */
let seq = [];
vi.mock('../core/am7client.js', () => ({
    am7client: {
        base: () => 'https://localhost:8443/rest',
        member: vi.fn().mockResolvedValue(true),
        applyImageTags: vi.fn(async () => { seq.push('applyImageTags'); return true; }),
        getFull: vi.fn(async () => ({ objectId: 'img-1', attributes: [{ name: 'seed', value: '42' }] })),
        patchAttribute: vi.fn(async (o, name) => { seq.push('patchAttribute:' + name); return true; }),
        newQuery: () => ({ field: vi.fn(), range: vi.fn() })
    }
}));
vi.mock('../core/pageClient.js', () => ({
    page: {
        toast: vi.fn((type, msg) => { seq.push('toast:' + type + ':' + msg); }),
        clearToast: vi.fn(),
        clearContextObject: vi.fn(),
        patchObject: vi.fn(async (o) => { seq.push('patchObject:' + Object.keys(o).sort().join(',')); return true; }),
        search: vi.fn(async () => ({ results: [{ objectId: 'tag-1' }] })),
        findObject: vi.fn(async () => null),
        createObject: vi.fn(async () => null),
        imageGallery: vi.fn(() => { seq.push('imageGallery'); })
    }
}));
vi.mock('../components/sdConfig.js', () => ({
    am7sd: {
        fetchModels: vi.fn().mockResolvedValue([]),
        fetchTemplate: vi.fn(async () => am7model.newPrimitive('olio.sd.config')),
        loadConfig: vi.fn().mockResolvedValue(null),
        applyConfig: vi.fn(),
        saveConfig: vi.fn(async () => { seq.push('saveConfig'); }),
        fillStyleDefaults: vi.fn()
    }
}));

am7model._view = am7view;
am7model._page = { user: null, context: () => ({ roles: {} }) };
am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };

function charInst() {
    let entity = { objectId: 'char-1', id: 101, name: 'Alekhya', profile: { id: 202, objectId: 'prof-1', portrait: null }, attributes: [] };
    return { entity, model: { name: 'olio.charPerson' }, api: { name: () => 'Alekhya', objectId: () => 'char-1' } };
}
async function openAndGenerate(opts) {
    capturedDialogCfg = null;
    const { reimage } = await import('../workflows/reimage.js');
    const { page } = await import('../core/pageClient.js');
    let inst = charInst();
    await reimage(inst.entity, inst, opts);
    expect(capturedDialogCfg, 'Dialog.open was called').toBeTruthy();
    let generate = capturedDialogCfg.actions.find((a) => a.label === 'Generate');
    expect(generate).toBeDefined();
    await generate.onclick();
    return { inst, page };
}

beforeEach(() => {
    seq = [];
    vi.clearAllMocks();
    mockM.request.mockReset();
    mockM.request.mockResolvedValue({ objectId: 'img-1', id: 303, name: 'portrait-1', attributes: [] });
    // Generate narrates a charPerson first (formDef.js forms.commands.narrate → server); not under test.
    vi.spyOn(am7model.forms.commands, 'narrate').mockImplementation(async () => { seq.push('narrate'); });
});

describe('reimage(entity, inst, opts).onImages', () => {
    it('reimage() resolves at dialog open — before Generate, before any image exists', async () => {
        capturedDialogCfg = null;
        const { reimage } = await import('../workflows/reimage.js');
        let inst = charInst();
        let onImages = vi.fn();
        await reimage(inst.entity, inst, { onImages });
        expect(capturedDialogCfg).toBeTruthy();
        expect(mockM.request).not.toHaveBeenCalled();
        expect(onImages).not.toHaveBeenCalled();
        expect(inst.entity.profile.portrait).toBeNull();
    });

    it('Generate calls onImages once, with the images, AFTER the profile portrait patch and the success toast', async () => {
        let onImages = vi.fn(async (images) => { seq.push('onImages:' + images.map((i) => i.objectId).join(',')); });
        let { inst } = await openAndGenerate({ onImages });
        expect(mockM.request).toHaveBeenCalledTimes(1);
        expect(mockM.request.mock.calls[0][0].url).toBe('https://localhost:8443/rest/olio/olio.charPerson/char-1/reimage');
        expect(onImages).toHaveBeenCalledTimes(1);
        expect(onImages.mock.calls[0][0].map((i) => i.objectId)).toEqual(['img-1']);
        // The callback is what the character manager hangs its list refresh on, so it must run after the
        // server already knows about the portrait: the identity.profile patch precedes it, as does the toast.
        let iPatch = seq.indexOf('patchObject:id,portrait,' + am7model.jsonModelKey);
        let iToast = seq.indexOf('toast:success:Created 1 image(s)');
        let iCb = seq.indexOf('onImages:img-1');
        expect(iPatch, 'portrait patch happened: ' + JSON.stringify(seq)).toBeGreaterThanOrEqual(0);
        expect(iToast, 'success toast happened: ' + JSON.stringify(seq)).toBeGreaterThanOrEqual(0);
        expect(iCb).toBeGreaterThan(iPatch);
        expect(iCb).toBeGreaterThan(iToast);
        expect(inst.entity.profile.portrait.objectId).toBe('img-1');
        expect(seq.indexOf('imageGallery')).toBeGreaterThanOrEqual(0);
    });

    it('a throwing callback is swallowed (warned) and the render result — toast, portrait, gallery — stands', async () => {
        let warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        let onImages = vi.fn(async () => { throw new Error('list refresh failed'); });
        let { inst, page } = await openAndGenerate({ onImages });
        expect(onImages).toHaveBeenCalledTimes(1);
        expect(warn.mock.calls.some((c) => String(c[0]).indexOf('onImages') >= 0)).toBe(true);
        expect(inst.entity.profile.portrait.objectId).toBe('img-1');
        expect(page.imageGallery).toHaveBeenCalledTimes(1);
        expect(seq).toContain('toast:success:Created 1 image(s)');
        expect(seq.some((s) => s.indexOf('toast:error') === 0)).toBe(false);
        warn.mockRestore();
    });

    it('no image produced → onImages is not called and the failure toast is shown', async () => {
        mockM.request.mockResolvedValue(null);
        let onImages = vi.fn();
        let { inst } = await openAndGenerate({ onImages });
        expect(onImages).not.toHaveBeenCalled();
        expect(inst.entity.profile.portrait).toBeNull();
        expect(seq).toContain('toast:error:Failed to create image 1');
        expect(seq).toContain('toast:error:Reimage failed');
    });

    it('the two-argument form (every other caller) still renders exactly as before', async () => {
        let { inst } = await openAndGenerate(undefined);
        expect(mockM.request).toHaveBeenCalledTimes(1);
        expect(inst.entity.profile.portrait.objectId).toBe('img-1');
        expect(seq).toContain('toast:success:Created 1 image(s)');
    });
});
