/**
 * KI-29 — reimageApparel.js must send the full sdConfig (not just hires/seed) and its
 * denoisingStrength slider must converge onto the same 0-100 UI / 0-1 wire scale as
 * reimage.js, instead of the old bespoke 0-1/step-0.05 slider that bypassed the
 * am7model range decorator entirely.
 *
 * These are real behavioral checks: the actual reimageApparel() workflow function is invoked,
 * its actual rendered Mithril vnode tree is walked to find the real <input type="range">,
 * a real "drag" is simulated via the real oninput handler, and the real Dialog "Generate"
 * action's onclick is invoked end-to-end to inspect the actual POST body that would be sent
 * to the live server (m.request is intercepted only at the network boundary — nothing about
 * reimageApparel.js's own logic, decorator wiring, or form definitions is mocked/faked).
 */
import { describe, it, expect, vi, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
// formDef.js's module-load side effect is `am7model.forms = forms` — force it to load before any
// assertions touch am7model.forms.sdMannequinConfig/sdConfig (mirrors how the real app bootstraps).
import '../core/formDef.js';

// --- Mock mithril: enough to render + fire events + capture the network call ---
function mockM(tag, attrs, children) {
    if (typeof attrs === 'string' || Array.isArray(attrs)) {
        children = attrs;
        attrs = {};
    }
    return { tag, attrs: attrs || {}, children };
}
mockM.redraw = vi.fn();
mockM.trust = (s) => s;
mockM.route = { set: vi.fn(), get: () => '/main' };
mockM.request = vi.fn();
vi.mock('mithril', () => ({ default: mockM }));

// --- Mock Dialog: capture the config passed to Dialog.open instead of rendering a real modal ---
let capturedDialogCfg = null;
vi.mock('../components/dialogCore.js', () => ({
    Dialog: {
        open: vi.fn((cfg) => { capturedDialogCfg = cfg; }),
        close: vi.fn()
    }
}));

// --- Mock am7client: only base()/member() are touched by reimageApparel.js ---
vi.mock('../core/am7client.js', () => ({
    am7client: {
        base: () => 'https://localhost:8443/rest',
        member: vi.fn().mockResolvedValue(true)
    }
}));

// --- Mock pageClient: only toast/clearToast/clearContextObject are touched ---
vi.mock('../core/pageClient.js', () => ({
    page: {
        toast: vi.fn(),
        clearToast: vi.fn(),
        clearContextObject: vi.fn()
    }
}));

am7model._view = am7view;
am7model._page = { user: null, context: () => ({ roles: {} }) };
am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
// reimageApparel.js reads am7model._sd (late-bound, same as production bootstrap in
// features/chat.js / features/media.js) rather than importing sdConfig.js directly.
// NOTE (out of scope for KI-29, flagged not fixed): reimageApparel.js:24 and reimage.js:123 both
// fall back to `am7model.newPrimitive('olio.sdConfig')` when fetchTemplate() returns nothing — but
// the registered model name is `olio.sd.config` (modelDef.js:9918; used correctly everywhere else,
// e.g. formDef.js:6114, cardGame/services/artPipeline.js:257). That typo'd fallback returns null and
// then throws in prepareInstance(null, form). It's invisible in practice only because a saved
// template config normally exists server-side. Since it's unrelated to KI-29, this test mocks
// fetchTemplate to return a real (correctly-named) primitive so it doesn't trip over that separate,
// pre-existing bug.
am7model._sd = {
    fetchModels: vi.fn().mockResolvedValue([]),
    // A FRESH primitive per call. mockResolvedValue would hand every test the same object, so one
    // test's slider drag would leak into the next one's "untouched slider" assertions.
    fetchTemplate: vi.fn(async () => am7model.newPrimitive('olio.sd.config')),
    loadConfig: vi.fn().mockResolvedValue(null),
    applyConfig: vi.fn(),
    saveConfig: vi.fn(),
    fillStyleDefaults: vi.fn()
};

function findByTag(vnode, tag) {
    let out = [];
    (function walk(n) {
        if (Array.isArray(n)) { n.forEach(walk); return; }
        if (!n) return;
        if (n.tag === tag) out.push(n);
        if (n.children) walk(n.children);
    })(vnode);
    return out;
}

describe('reimageApparel.js — denoisingStrength scale converges onto reimage.js (KI-29)', () => {
    it('forms.sdMannequinConfig now declares denoisingStrength as format:"range" (was missing entirely)', () => {
        expect(am7model.forms.sdMannequinConfig).toBeDefined();
        let field = am7model.forms.sdMannequinConfig.fields.denoisingStrength;
        expect(field).toBeDefined();
        expect(field.format).toBe('range');
    });

    it('cinst.api.denoisingStrength on the sdMannequinConfig form now decorates 0-100 UI <-> 0-1 wire, matching forms.sdConfig', () => {
        let entityA = am7model.newPrimitive('olio.sd.config');
        let cinstMannequin = am7model.prepareInstance(entityA, am7model.forms.sdMannequinConfig);
        cinstMannequin.api.denoisingStrength(80);
        expect(cinstMannequin.entity.denoisingStrength).toBe(0.8);
        expect(cinstMannequin.api.denoisingStrength()).toBe(80);

        let entityB = am7model.newPrimitive('olio.sd.config');
        let cinstReimage = am7model.prepareInstance(entityB, am7model.forms.sdConfig);
        cinstReimage.api.denoisingStrength(80);
        expect(cinstReimage.entity.denoisingStrength).toBe(0.8);
        expect(cinstReimage.api.denoisingStrength()).toBe(80);
    });

    it('renders a single 0-100/step-5 mannequin-creativity slider (same bounds as reimage.js denoising), not the old 0-1/step-0.05 slider', async () => {
        capturedDialogCfg = null;
        mockM.request.mockResolvedValue([]);
        const { reimageApparel } = await import('../workflows/reimageApparel.js');
        let inst = { model: { name: 'olio.apparel' }, api: { name: () => 'TestApparel', objectId: () => 'apparel-obj-123' } };
        await reimageApparel({}, inst);

        expect(capturedDialogCfg).toBeTruthy();
        // reimageApparel.js renders 4 range sliders (Steps, Refiner Steps, CFG, Mannequin Creativity)
        // — find the creativity one specifically by its bounds, which must match reimage.js's
        // denoising slider exactly (0-100, step 5), not the old bespoke 0-1/step-0.05 slider.
        let vnode = capturedDialogCfg.content.view();
        let rangeInputs = findByTag(vnode, 'input').filter((i) => i.attrs.type === 'range');
        expect(rangeInputs.length).toBe(4);
        let creativitySlider = rangeInputs.find((i) => i.attrs.min === 0 && i.attrs.max === 100 && i.attrs.step === 5);
        expect(creativitySlider).toBeDefined();
        // KI-43: unset must display the server's own MANNEQUIN_INIT_IMAGE_CREATIVITY (0.85 -> 85),
        // not the range decorator's no-default 0 — otherwise the slider lies about what will render.
        expect(creativitySlider.attrs.value).toBe('85');
    });

    it('the Generate action sends the full sdConfig (model/sampler/scheduler/cfg/steps/loras) AND mannequinCreativity converted from the 0-100 slider to the 0-1 wire value the server schema expects', async () => {
        capturedDialogCfg = null;
        mockM.request.mockResolvedValue([{ objectId: 'img-1' }]);
        const { reimageApparel } = await import('../workflows/reimageApparel.js');
        let inst = { model: { name: 'olio.apparel' }, api: { name: () => 'TestApparel', objectId: () => 'apparel-obj-456' } };
        await reimageApparel({}, inst);

        // Simulate a real user drag on the rendered mannequin-creativity slider (not the Steps/
        // RefinerSteps/CFG sliders that share the same tag/type) to 80% before hitting Generate.
        let vnode = capturedDialogCfg.content.view();
        let rangeInput = findByTag(vnode, 'input')
            .filter((i) => i.attrs.type === 'range')
            .find((i) => i.attrs.min === 0 && i.attrs.max === 100 && i.attrs.step === 5);
        expect(rangeInput).toBeDefined();
        rangeInput.attrs.oninput({ target: { value: '80' } });

        let generateAction = capturedDialogCfg.actions.find((a) => a.label === 'Generate');
        expect(generateAction).toBeDefined();
        await generateAction.onclick();

        expect(mockM.request).toHaveBeenCalled();
        let call = mockM.request.mock.calls[mockM.request.mock.calls.length - 1][0];
        expect(call.method).toBe('POST');
        expect(call.url).toBe('https://localhost:8443/rest/olio/apparel/apparel-obj-456/reimage');

        let body = call.body;
        // The old bug: server discarded everything except hires/seed. Prove the client already
        // sends (and, per the paired backend fix, the server now consumes) the full config.
        expect(body).toHaveProperty('model');
        expect(body).toHaveProperty('sampler');
        expect(body).toHaveProperty('scheduler');
        expect(body).toHaveProperty('cfg');
        expect(body).toHaveProperty('steps');
        expect(body).toHaveProperty('hires');
        expect(body).toHaveProperty('seed');

        // The actual point of this test file: 80% on the (now-converged) slider must reach the
        // wire as 0.8 — matching olio.sd.config's schema (0.0-1.0 double), not 80 (which is what
        // the old, undecorated 0-1/step-0.05 apparel slider would have stored as 0.8 already, but
        // which a naive "just widen the slider" fix would have broken).
        //
        // KI-43 moved the contract from denoisingStrength to mannequinCreativity: SDUtil
        // .generateMannequinImages reads mannequinCreativity and NEVER denoisingStrength, so the
        // 0-100/0-1 conversion has to land on the field the server actually consumes.
        expect(body.mannequinCreativity).toBe(0.8);
    });

    /**
     * KI-43 regression: the mannequin dialog's slider must reach the field SDUtil actually reads.
     * denoisingStrength carries a 0.75 schema default (never null), which is precisely why
     * generateMannequinImages was given its own mannequinCreativity field — writing the slider to
     * denoisingStrength left every mannequin at the hardcoded MANNEQUIN_INIT_IMAGE_CREATIVITY (0.85)
     * while the UI showed whatever the user picked.
     */
    describe('mannequin creativity is wired to the field the server reads (KI-43)', () => {
        it('forms.sdMannequinConfig declares mannequinCreativity as format:"range"', () => {
            let field = am7model.forms.sdMannequinConfig.fields.mannequinCreativity;
            expect(field).toBeDefined();
            expect(field.format).toBe('range');
        });

        it('mannequinCreativity is a 0-1 double on olio.sd.config with NO declared default, so an untouched slider leaves the server default in force', () => {
            let entity = am7model.newPrimitive('olio.sd.config');
            let model = am7model.getModel('olio.sd.config');
            let f = model.fields.find((x) => x.name === 'mannequinCreativity');
            expect(f).toBeDefined();
            expect(f.type).toBe('double');
            expect(f.minValue).toBe(0.0);
            expect(f.maxValue).toBe(1.0);
            // A declared default here would be never-null and would silently override the server's
            // 0.85 — the exact defect denoisingStrength (default 0.75) has. newPrimitive still
            // materializes an undeclared double as 0, and SDUtil.generateMannequinImages reads 0 as
            // "unset" (`cfgDenoise != null && cfgDenoise > 0`), so 0 on the wire IS the server default.
            expect(f.default).toBeUndefined();
            expect(entity.mannequinCreativity).toBe(0);
            // Contrast: denoisingStrength really does carry a never-null default, which is why it
            // could never express the intended mannequin value.
            expect(entity.denoisingStrength).toBe(0.75);
        });

        it('cinst.api.mannequinCreativity decorates 0-100 UI <-> 0-1 wire', () => {
            let cinst = am7model.prepareInstance(am7model.newPrimitive('olio.sd.config'), am7model.forms.sdMannequinConfig);
            cinst.api.mannequinCreativity(60);
            expect(cinst.entity.mannequinCreativity).toBe(0.6);
            expect(cinst.api.mannequinCreativity()).toBe(60);
        });

        it('an untouched slider sends the server-default sentinel (0), so the server keeps its measured-best 0.85 — and displays 85, never a misleading 0', async () => {
            capturedDialogCfg = null;
            mockM.request.mockResolvedValue([{ objectId: 'img-2' }]);
            const { reimageApparel } = await import('../workflows/reimageApparel.js');
            let inst = { model: { name: 'olio.apparel' }, api: { name: () => 'TestApparel', objectId: () => 'apparel-obj-789' } };
            await reimageApparel({}, inst);

            let vnode = capturedDialogCfg.content.view();
            let creativitySlider = findByTag(vnode, 'input')
                .filter((i) => i.attrs.type === 'range')
                .find((i) => i.attrs.min === 0 && i.attrs.max === 100 && i.attrs.step === 5);
            expect(creativitySlider.attrs.value).toBe('85');

            let generateAction = capturedDialogCfg.actions.find((a) => a.label === 'Generate');
            await generateAction.onclick();

            let call = mockM.request.mock.calls[mockM.request.mock.calls.length - 1][0];
            // 0 is the "unset" sentinel SDUtil already honours; anything non-zero would pin the value.
            expect(call.body.mannequinCreativity).toBe(0);
        });
    });
});
