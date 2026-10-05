/**
 * "Apply LLM Options Preset" (formDef.js forms.chatOptions.optionsPreset) — the six UX presets must
 * stay a 1:1 mirror of the Objects7 templates (olio/llm/templates/chatConfig.*.json), must only set
 * knobs the server actually emits (typical_p was dropped 2026-10-05: ChatUtil.applyChatOptions never
 * sends it), and every value must be representable by the chatOptions form, whose range sliders
 * clamp to modelDef.js minValue/maxValue (RPG num_ctx 131072 used to exceed the 120000 bound, and
 * repeat_last_n was capped at 100 so a scene-sized penalty window could not be set at all).
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { am7model } from '../core/model.js';
import { chatOptionsPresets } from '../core/formDef.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const TEMPLATE_DIR = resolve(HERE, '../../../AccountManagerObjects7/src/main/resources/olio/llm/templates');

const PRESET_TO_TEMPLATE = {
    'General Chat': 'generalChat',
    'RPG / Creative': 'rpg',
    'Behavioral': 'behavioral',
    'Content Analysis': 'contentAnalysis',
    'Coding': 'coding',
    'Technical Eval': 'technicalEval'
};

function presetValues(p) {
    const out = {};
    Object.keys(p).forEach(k => { if (k !== 'desc') out[k] = p[k]; });
    return out;
}

function templateOptions(name) {
    const json = JSON.parse(readFileSync(resolve(TEMPLATE_DIR, 'chatConfig.' + name + '.json'), 'utf8'));
    const opts = Object.assign({}, json.chatOptions);
    delete opts.schema;
    return opts;
}

function optionField(name) {
    return am7model.getModelFields('olio.llm.chatOptions').filter(f => f.name === name)[0];
}

beforeAll(() => {
    am7model._view = { path: () => '', pathForType: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }), toast: () => {} };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
});

describe('chatOptions presets', () => {
    it('exposes exactly the six presets the Objects7 templates define', () => {
        expect(Object.keys(chatOptionsPresets).sort()).toEqual(Object.keys(PRESET_TO_TEMPLATE).sort());
    });

    it('never sets typical_p — the server does not emit it, so a preset carrying it would lie', () => {
        for (const name of Object.keys(chatOptionsPresets)) {
            expect(chatOptionsPresets[name], name).not.toHaveProperty('typical_p');
            expect(templateOptions(PRESET_TO_TEMPLATE[name]), name + ' template').not.toHaveProperty('typical_p');
        }
    });

    it('mirrors the Objects7 chatConfig template value-for-value', () => {
        for (const name of Object.keys(chatOptionsPresets)) {
            expect(presetValues(chatOptionsPresets[name]), name).toEqual(templateOptions(PRESET_TO_TEMPLATE[name]));
        }
    });

    it('every preset value lies within the form bounds the range slider clamps to', () => {
        for (const name of Object.keys(chatOptionsPresets)) {
            const p = presetValues(chatOptionsPresets[name]);
            for (const k of Object.keys(p)) {
                const f = optionField(k);
                expect(f, name + '.' + k + ' is not a chatOptions field').toBeDefined();
                if (f.minValue !== undefined) expect(p[k], name + '.' + k + ' below minValue').toBeGreaterThanOrEqual(f.minValue);
                if (f.maxValue !== undefined) expect(p[k], name + '.' + k + ' above maxValue').toBeLessThanOrEqual(f.maxValue);
            }
        }
    });

    it('repeat_last_n can be set wide enough to cover a scene-sized passage', () => {
        // 64 tokens only sees token-level loops; the Ourselves.doc chunk-6 loop had a ~450-500 token
        // period. Ollama accepts up to num_ctx; the old maxValue of 100 made 512+ unreachable.
        const f = optionField('repeat_last_n');
        expect(f.minValue).toBe(0);
        expect(f.maxValue).toBeGreaterThanOrEqual(2048);
    });
});

describe('applying a preset through the form button', () => {
    function applyPreset(presetName) {
        const inst = am7model.newInstance('olio.llm.chatOptions');
        const before = Object.assign({}, inst.entity);
        let opened = null;
        am7model._dialogCore = { Dialog: { open: (o) => { opened = o; }, close: () => {} } };
        am7model.forms.chatOptions.fields.optionsPreset.field.command(null, inst, 'optionsPreset');
        expect(opened, 'preset dialog must open').toBeTruthy();
        const rows = opened.content.view().children;
        // Mithril 2.2 normalises a lone string child into a '#' text vnode.
        const rowTitle = r => r.children[0].children[0].children;
        const row = rows.filter(r => rowTitle(r) === presetName)[0];
        expect(row, 'preset row "' + presetName + '" must be listed').toBeTruthy();
        row.attrs.onclick();
        return { inst, before };
    }

    it('writes every preset knob onto the instance and records each as a change', () => {
        const { inst } = applyPreset('Content Analysis');
        const expected = presetValues(chatOptionsPresets['Content Analysis']);
        for (const k of Object.keys(expected)) {
            expect(inst.entity[k], k).toBe(expected[k]);
            expect(inst.changes, k + ' must be marked changed').toContain(k);
        }
    });

    it('leaves typical_p at the model default rather than overwriting it', () => {
        const { inst, before } = applyPreset('RPG / Creative');
        expect(inst.entity.typical_p).toBe(before.typical_p);
        expect(inst.changes).not.toContain('typical_p');
    });

    it('RPG / Creative num_ctx lands inside the slider range instead of past it', () => {
        const { inst } = applyPreset('RPG / Creative');
        expect(inst.entity.num_ctx).toBe(131072);
        expect(optionField('num_ctx').maxValue).toBeGreaterThanOrEqual(131072);
    });
});
