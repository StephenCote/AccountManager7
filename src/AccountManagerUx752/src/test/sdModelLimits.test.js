/**
 * SD checkpoint dropdowns in the GENERIC form path were driven by a hardcoded list that nothing
 * ever replaced.
 *
 * formDef.js carried a module-scope block meant to overwrite `field.limit` with the checkpoints the
 * SD server reports. It never ran once: the guard required `am7model._sd` and
 * `am7model._page.authenticated()`, but `formDef.js` is imported by main.js BEFORE main.js assigns
 * `am7model._page`, and `am7model._sd` is only ever set by a lazy dynamic import in
 * features/chat.js and features/media.js. Both were undefined at evaluation time, so the block
 * short-circuited — and even with the ordering fixed, module scope runs at boot, before login.
 *
 * It went unnoticed because components/SdConfigPanel.js — the SD panel the picture book and chat
 * use — fetches its own list and passes it as a prop, never reading `field.limit`. Only the generic
 * form path reads it (core/view.js getDefaultValuesForField), which is how `forms.sdConfigOverrides`
 * renders the per-scene override editor in PictureBook, ChapBook and CardGame. Those showed an
 * SDXL-era list that no longer contained flux2Klein_9b, the checkpoint the books actually use.
 *
 * The patching is now a pure exported function called from the authenticated branch of
 * refreshApplication. These tests drive that function directly.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { forms, applySdModelLimits } from '../core/formDef.js';
import { am7model } from '../core/model.js';

const SERVER_MODELS = ['flux2Klein_9b', 'juggernautXL_ragnarokBy.safetensors', 'someNewCheckpoint'];

// Every form that carries an SD checkpoint picker.
const SD_FORMS = ['sdConfig', 'sdConfigOverrides', 'sdMannequinConfig'];

describe('SD model limits', function () {
    beforeEach(function () {
        SD_FORMS.forEach(function (name) {
            ['model', 'refinerModel'].forEach(function (f) {
                if (forms[name] && forms[name].fields[f]) forms[name].fields[f].field.limit = [];
            });
        });
    });

    it('ships with NO hardcoded checkpoints — the installed set is a property of the server', function () {
        SD_FORMS.forEach(function (name) {
            ['model', 'refinerModel'].forEach(function (f) {
                let field = forms[name].fields[f].field;
                expect(Array.isArray(field.limit), name + '.' + f + ' must declare a list').toBe(true);
                // A stale hardcoded list is how this shipped: it outlived the checkpoints it named.
                expect(field.limit.filter(function (v) { return /juggernaut|dreamshaper|chilloutmix/i.test(v); }))
                    .toHaveLength(0);
            });
        });
    });

    it('fills every SD form dropdown from the server list', function () {
        let updated = applySdModelLimits(SERVER_MODELS);

        SD_FORMS.forEach(function (name) {
            ['model', 'refinerModel'].forEach(function (f) {
                expect(forms[name].fields[f].field.limit, name + '.' + f).toEqual(SERVER_MODELS);
            });
        });
        // 3 forms x 2 fields, plus the 2 model-level field definitions.
        expect(updated).toBe(8);
    });

    it('covers sdMannequinConfig, which the old block silently missed', function () {
        applySdModelLimits(SERVER_MODELS);
        // The dead block patched only sdConfig and sdConfigOverrides, so this form's list was never
        // dynamic even in intent.
        expect(forms.sdMannequinConfig.fields.model.field.limit).toEqual(SERVER_MODELS);
        expect(forms.sdMannequinConfig.fields.refinerModel.field.limit).toEqual(SERVER_MODELS);
    });

    it('updates the model-level field definitions too, not just the named forms', function () {
        applySdModelLimits(SERVER_MODELS);
        expect(am7model.getModelField('olio.sd.config', 'model').limit).toEqual(SERVER_MODELS);
        expect(am7model.getModelField('olio.sd.config', 'refinerModel').limit).toEqual(SERVER_MODELS);
    });

    it('leaves the dropdowns alone when the server returns nothing', function () {
        // An unreachable SD server must not wipe a list that a previous successful call filled.
        applySdModelLimits(SERVER_MODELS);
        expect(applySdModelLimits([])).toBe(0);
        expect(applySdModelLimits(null)).toBe(0);
        expect(applySdModelLimits(undefined)).toBe(0);
        expect(forms.sdConfig.fields.model.field.limit).toEqual(SERVER_MODELS);
    });

    it('reports how many fields it matched, so a rename cannot fail silently', function () {
        // Returning a count is what lets the caller warn when the form/field names move — the
        // silent version of that is precisely how the old block went unnoticed for so long.
        expect(applySdModelLimits(SERVER_MODELS)).toBeGreaterThan(0);
    });
});
