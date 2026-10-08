/**
 * KI-66 + KI-64 — SdConfigPanel "Skip landscape" toggle reaches the olio.sd.config entity in BOTH
 * panel modes, and every range label agrees with its slider.
 *
 * KI-66 bug (found 2026-10-07 while verifying the toggle): the hand-curated client olio.sd.config block
 * in core/modelDef.js had none of the scene-composite fields (skipLandscape, flux2IncludeLandscapeRef,
 * compositeMode, flux2*, kontext*, ...). SdConfigPanel's inst-driven mode (pictureBook, chapBook) reaches
 * the entity ONLY through the instConfig proxy, which defines a property per getModelFields() entry —
 * so with the fields missing, the checkbox's `config.skipLandscape = ...` landed on a throwaway proxy
 * object, the entity never changed, and `checked: !!config.skipLandscape` always read false. Chat
 * (config-driven, plain entity) was unaffected, which is why the gap hid behind the chat-only KI-66 repro.
 *
 * These tests render the REAL component view against (a) a real am7model instance and (b) a plain
 * config, fire the checkbox's own onchange, and assert on the entity. The live round-trip through the
 * server (save -> load as olio.sd.config) is e2e/sdConfigLandscapePersist.spec.js.
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';
import '../core/formDef.js';

beforeAll(() => {
    am7model._view = { path: () => '', pathForType: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
    am7model._sd = { fillStyleDefaults: () => {} };
});

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

function textOf(vnode) {
    let parts = [];
    (function walk(n) {
        if (Array.isArray(n)) { n.forEach(walk); return; }
        if (n == null || n === false) return;
        if (typeof n === 'string' || typeof n === 'number') { parts.push(String(n)); return; }
        if (n.children != null) walk(n.children);
    })(vnode);
    return parts;
}

/** The "Skip landscape" checkbox: the checkbox whose enclosing label carries that caption. */
function skipLandscapeCheckbox(tree) {
    let labels = findByTag(tree, 'label').filter(l => textOf(l).includes('Skip landscape'));
    expect(labels.length, 'exactly one Skip landscape label').toBe(1);
    let cb = findByTag(labels[0], 'input').filter(i => i.attrs.type === 'checkbox');
    expect(cb.length).toBe(1);
    return cb[0];
}

function modelField(name) {
    return am7model.getModelFields('olio.sd.config').filter(f => f.name === name)[0];
}

describe('KI-66 client olio.sd.config mirrors the server scene-composite fields', () => {
    it('declares skipLandscape (boolean, default false) and flux2IncludeLandscapeRef (boolean, NO default)', () => {
        let skip = modelField('skipLandscape');
        expect(skip).toBeDefined();
        expect(skip.type).toBe('boolean');
        expect(skip.default).toBe(false);
        let inc = modelField('flux2IncludeLandscapeRef');
        expect(inc).toBeDefined();
        expect(inc.type).toBe('boolean');
        // A client default here would make olio/sd/flux2Defaults.json's includeLandscapeRef dead (KI-66).
        expect(inc.default).toBeUndefined();
    });

    it('declares the FLUX.2 knobs with the server bounds and no client default where the server has none', () => {
        let expected = {
            flux2Cfg: { type: 'double', min: 0.5, max: 8.0 },
            flux2Steps: { type: 'int', min: 1, max: 100 },
            flux2ReferenceSize: { type: 'int', min: 256, max: 2048 },
            flux2Width: { type: 'int', min: 256, max: 2048 },
            flux2Height: { type: 'int', min: 256, max: 2048 }
        };
        Object.keys(expected).forEach(name => {
            let f = modelField(name);
            expect(f, name).toBeDefined();
            expect(f.type).toBe(expected[name].type);
            expect(f.minValue).toBe(expected[name].min);
            expect(f.maxValue).toBe(expected[name].max);
            expect(f.default, name + ' must stay default-less').toBeUndefined();
        });
        expect(modelField('compositeMode').default).toBe('flux2');
    });

    it('a prepared instance exposes the new fields through inst.api (what the panel proxy is built from)', () => {
        let inst = am7model.prepareInstance(am7model.newPrimitive('olio.sd.config'), am7model.forms.sdConfig);
        expect(typeof inst.api.skipLandscape).toBe('function');
        expect(typeof inst.api.flux2IncludeLandscapeRef).toBe('function');
        expect(inst.fields.some(f => f.name === 'skipLandscape')).toBe(true);
    });
});

describe('KI-66 "Skip landscape" checkbox binds in inst-driven mode (pictureBook / chapBook)', () => {
    it('writes skipLandscape AND flux2IncludeLandscapeRef onto inst.entity and re-renders checked from the entity', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let entity = am7model.newPrimitive('olio.sd.config');
        let inst = am7model.prepareInstance(entity, am7model.forms.sdConfig);

        let cb = skipLandscapeCheckbox(SdConfigPanel.view({ attrs: { inst } }));
        expect(cb.attrs.checked).toBe(false);

        cb.attrs.onchange({ target: { checked: true } });
        expect(entity.skipLandscape).toBe(true);
        expect(entity.flux2IncludeLandscapeRef).toBe(false);

        // Re-render: the checkbox must reflect the ENTITY, not a stale proxy.
        let cb2 = skipLandscapeCheckbox(SdConfigPanel.view({ attrs: { inst } }));
        expect(cb2.attrs.checked).toBe(true);

        cb2.attrs.onchange({ target: { checked: false } });
        expect(entity.skipLandscape).toBe(false);
        expect(entity.flux2IncludeLandscapeRef).toBe(true);
    });

    it('inst.changes records both fields so inst.patch() would carry them', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let entity = am7model.newPrimitive('olio.sd.config');
        let inst = am7model.prepareInstance(entity, am7model.forms.sdConfig);
        skipLandscapeCheckbox(SdConfigPanel.view({ attrs: { inst } })).attrs.onchange({ target: { checked: true } });
        expect(inst.changes).toContain('skipLandscape');
        expect(inst.changes).toContain('flux2IncludeLandscapeRef');
    });
});

describe('KI-66 "Skip landscape" checkbox binds in config-driven mode (chat SceneGenerator)', () => {
    it('writes both fields onto the plain config object', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let config = { steps: 20, cfg: 7 };
        let cb = skipLandscapeCheckbox(SdConfigPanel.view({ attrs: { config } }));
        expect(cb.attrs.checked).toBe(false);
        cb.attrs.onchange({ target: { checked: true } });
        expect(config.skipLandscape).toBe(true);
        expect(config.flux2IncludeLandscapeRef).toBe(false);
        expect(skipLandscapeCheckbox(SdConfigPanel.view({ attrs: { config } })).attrs.checked).toBe(true);
    });
});

describe('KI-64 every range label shows the same number the slider and spinner carry', () => {
    const RANGE_KEYS = ['steps', 'refinerSteps', 'cfg', 'refinerCfg', 'denoisingStrength'];
    const LABELS = { steps: 'Steps', refinerSteps: 'Refiner Steps', cfg: 'CFG', refinerCfg: 'Refiner CFG', denoisingStrength: 'Denoising' };

    // SdConfigPanel.rangeInput() hands renderRange `label: key` (the sr-only label) and no `name`, so
    // each slider is identified by the sr-only label inside its slider+spinner wrapper div.
    function sliderWidget(tree, key) {
        let wrappers = findByTag(tree, 'div').filter(d => {
            // Mithril's hyperscript normalizes `class` into `className` on the vnode attrs.
            let labels = findByTag(d.children, 'label').filter(l => l.attrs && (l.attrs.className || l.attrs.class) === 'sr-only');
            return labels.length === 1 && textOf(labels[0]).join('') === key
                && findByTag(d.children, 'input').some(i => i.attrs.type === 'range');
        });
        // The innermost wrapper (ancestors containing it also match; take the one with exactly one range).
        let w = wrappers.filter(d => findByTag(d.children, 'input').filter(i => i.attrs.type === 'range').length === 1);
        expect(w.length, 'one slider wrapper for ' + key).toBeGreaterThanOrEqual(1);
        let inner = w[w.length - 1];
        return {
            range: findByTag(inner.children, 'input').find(i => i.attrs.type === 'range'),
            spin: findByTag(inner.children, 'input').find(i => i.attrs.type === 'number')
        };
    }

    function check(tree, expected) {
        let texts = textOf(tree);
        RANGE_KEYS.forEach(key => {
            let { range, spin } = sliderWidget(tree, key);
            expect(range, 'range input for ' + key).toBeTruthy();
            expect(range.attrs.value, key + ' slider value').toBe(expected[key]);
            expect(spin, 'spinner for ' + key).toBeTruthy();
            expect(spin.attrs.value, key + ' spinner value').toBe(expected[key]);
            expect(texts, key + ' label').toContain(LABELS[key] + ': ' + expected[key]);
        });
    }

    it('with explicit config values (what chat feeds it from the server template)', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let config = { steps: 33, refinerSteps: 12, cfg: 4, refinerCfg: 9, denoisingStrength: 0.35 };
        check(SdConfigPanel.view({ attrs: { config } }), config);
    });

    it('with an empty config (the shared RANGE_DEFAULTS, never the slider minimum)', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        check(SdConfigPanel.view({ attrs: { config: {} } }),
            { steps: 20, refinerSteps: 20, cfg: 7, refinerCfg: 7, denoisingStrength: 0.75 });
    });

    it('through a real instance (inst mode): server-template values survive the proxy unchanged', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let entity = am7model.newPrimitive('olio.sd.config');
        Object.assign(entity, { steps: 41, refinerSteps: 17, cfg: 3, refinerCfg: 11, denoisingStrength: 0.6 });
        let inst = am7model.prepareInstance(entity, am7model.forms.sdConfig);
        check(SdConfigPanel.view({ attrs: { inst } }), { steps: 41, refinerSteps: 17, cfg: 3, refinerCfg: 11, denoisingStrength: 0.6 });
    });
});
