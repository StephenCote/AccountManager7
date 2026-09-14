// @vitest-environment jsdom
/**
 * SD config sliders must match their MODEL field definitions.
 *
 * THE BUG: cfg and refinerCfg are `"type":"int","minValue":1,"maxValue":20` in modelDef, but their
 * sliders ran 1–30 with a step of 0.5. Dragging produced values the model rejects — non-integers
 * (5.5, 6.5, …) and anything above 20 — so the write never landed and the next redraw snapped the
 * control back to the value the entity still held. Reported as "I change Refiner CFG to 7 and it
 * always resets back to 5", where 5 was reimage.js's tempApplyDefaults value.
 *
 * A second, separate defect in the same panel: the label and the slider each invented their own
 * default, so an unset CFG displayed "CFG: 7" above a slider parked at its min of 1 ("the cfg
 * sliders don't register the default value").
 *
 * These tests read the REAL modelDef and assert the panel's own markup agrees with it, so a slider
 * can never drift from its field again.
 */
import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const panelSrc = fs.readFileSync(path.join(SRC, 'components/SdConfigPanel.js'), 'utf8');
const modelSrc = fs.readFileSync(path.join(SRC, 'core/modelDef.js'), 'utf8');

/** Pull a field's declaration out of modelDef.js (first match wins — olio.sd.config's block). */
function modelField(name) {
    const re = new RegExp('\\{\\s*"name":\\s*"' + name + '"[^}]*\\}', 'g');
    let m;
    while ((m = re.exec(modelSrc)) !== null) {
        const blk = m[0];
        if (/"minValue"/.test(blk) && /"maxValue"/.test(blk)) {
            return {
                type: (blk.match(/"type":\s*"(\w+)"/) || [])[1],
                min: parseFloat((blk.match(/"minValue":\s*([\d.]+)/) || [])[1]),
                max: parseFloat((blk.match(/"maxValue":\s*([\d.]+)/) || [])[1]),
                def: parseFloat((blk.match(/"default":\s*([\d.]+)/) || [])[1])
            };
        }
    }
    return null;
}

/** Pull the panel's rangeInput(...) arguments for a field. */
function slider(key) {
    const m = panelSrc.match(
        new RegExp('rangeInput\\(config,\\s*"' + key + '",\\s*([-\\d.]+),\\s*([-\\d.]+),\\s*([-\\d.]+)'));
    if (!m) return null;
    return { min: parseFloat(m[1]), max: parseFloat(m[2]), step: parseFloat(m[3]) };
}

describe('slider bounds match the model field', () => {
    for (const key of ['cfg', 'refinerCfg', 'refinerSteps']) {
        it(key + ': range and step are valid for its declared type', () => {
            const f = modelField(key);
            const s = slider(key);
            expect(f, 'modelDef must declare ' + key).toBeTruthy();
            expect(s, 'the panel must render a slider for ' + key).toBeTruthy();

            expect(s.min, key + ' slider min must not be below the model minValue')
                .toBeGreaterThanOrEqual(f.min);
            expect(s.max, key + ' slider max must not exceed the model maxValue')
                .toBeLessThanOrEqual(f.max);

            if (f.type === 'int') {
                // A fractional step on an int field emits values the model rejects, and the
                // rejected write silently reverts the control.
                expect(Number.isInteger(s.step),
                    key + ' is an int field, so its step must be a whole number (was ' + s.step + ')')
                    .toBe(true);
            }
        });
    }

    it('denoisingStrength keeps its fractional step — it is a genuine double', () => {
        // The counter-example: this one was always correct, and the fix must not "tidy" it.
        const f = modelField('denoisingStrength');
        const s = slider('denoisingStrength');
        expect(f.type).toBe('double');
        expect(s.step).toBeLessThan(1);
        expect(s.min).toBeGreaterThanOrEqual(f.min);
        expect(s.max).toBeLessThanOrEqual(f.max);
    });
});

describe('label and slider share one default', () => {
    it('no range label carries its own inline default literal', () => {
        // The old shape was:  field("CFG: " + (config.cfg != null ? config.cfg : 7), ...)
        // which let the label disagree with the slider's own fallback (min).
        const inlineDefaults = panelSrc.match(
            /field\("[^"]*:\s*"\s*\+\s*\(config\.\w+\s*!=\s*null\s*\?\s*config\.\w+\s*:\s*[\d.]+\)/g);
        expect(inlineDefaults, 'labels must use rangeValue(), not an inline default: '
            + JSON.stringify(inlineDefaults)).toBeNull();
    });

    it('RANGE_DEFAULTS agrees with each model default', () => {
        const block = panelSrc.match(/const RANGE_DEFAULTS = \{([^}]*)\}/)[1];
        for (const [, key, val] of block.matchAll(/(\w+):\s*([\d.]+)/g)) {
            const f = modelField(key);
            if (f && !Number.isNaN(f.def)) {
                expect(parseFloat(val), key + ': panel default must match the model default')
                    .toBe(f.def);
            }
        }
    });
});
