/**
 * The hand-curated client olio.sd.config block (core/modelDef.js) must mirror the server model JSON
 * (AccountManagerObjects7/src/main/resources/models/olio/sd/configModel.json) field for field.
 *
 * Found 2026-10-10 in the Phase 0 full gate: the client block still declared the retired checkpoint
 * default model="sdXL_v10VAEFix.safetensors" (and invented refinerModel="juggernautXL_ragnarokBy.safetensors",
 * which the server never declares). am7model.newPrimitive('olio.sd.config') seeds those defaults into every
 * config the client builds without a user model pick, and the server honours whatever model the request
 * carries (SDUtil.resolveModel only falls back when the field is blank), so Swarm was asked for a checkpoint
 * that is not installed: "Invalid model value for param Model - 'sdXL_v10VAEFix.safetensors'". The e2e that
 * sent it (apparelSequence.spec.js) still reported green because it never asserted on the result.
 *
 * This test reads the REAL server JSON off disk so a future divergence fails here, before it reaches Swarm.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { am7model } from '../core/model.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const serverJsonPath = path.resolve(here, '../../../AccountManagerObjects7/src/main/resources/models/olio/sd/configModel.json');
const server = JSON.parse(readFileSync(serverJsonPath, 'utf8'));
const client = am7model.getModel('olio.sd.config');

const COMPARED = ['type', 'default', 'minValue', 'maxValue', 'baseModel', 'foreign'];

function byName(fields) {
    return Object.fromEntries(fields.map(f => [f.name, f]));
}

describe('client olio.sd.config mirrors the server configModel.json', () => {
    const sf = byName(server.fields);
    const cf = byName(client.fields);

    it('declares every server field and no extras', () => {
        expect(Object.keys(cf).sort()).toEqual(Object.keys(sf).sort());
    });

    it('inherits what the server inherits, so the client treats olio.sd.config as directory-scoped', () => {
        // Without `inherits: ["data.directory"]` the client block had no groupId/groupPath/organizationId,
        // am7model.isGroup() said false, and list queries went out without the organizationId condition
        // PBAC requires for data.directory-derived types.
        expect(client.inherits).toEqual(server.inherits);
        expect(am7model.isGroup(client)).toBe(true);
        expect(am7model.inherits(client, 'data.directory')).toBe(true);
        let inherited = am7model.getModelFields('olio.sd.config').map(f => f.name);
        expect(inherited).toEqual(expect.arrayContaining(['groupId', 'groupPath', 'organizationId', 'name']));
    });

    it('agrees on type, default, bounds, baseModel and foreign for every field', () => {
        let diffs = [];
        Object.keys(sf).forEach(name => {
            if (!cf[name]) return;
            COMPARED.forEach(k => {
                if (JSON.stringify(sf[name][k]) !== JSON.stringify(cf[name][k])) {
                    diffs.push(name + '.' + k + ': server=' + JSON.stringify(sf[name][k]) + ' client=' + JSON.stringify(cf[name][k]));
                }
            });
        });
        expect(diffs, diffs.join('\n')).toEqual([]);
    });

    it('every checkpoint-name default the client seeds is exactly the server default (never an invented name)', () => {
        let modelFields = Object.keys(sf).filter(n => /model$/i.test(n));
        expect(modelFields).toEqual(expect.arrayContaining(['model', 'refinerModel', 'flux2Model', 'kontextModel']));
        let prim = am7model.newPrimitive('olio.sd.config');
        modelFields.forEach(name => {
            if (sf[name].default === undefined) expect(prim[name], name + ' must stay unset').toBeNull();
            else expect(prim[name], name).toBe(sf[name].default);
        });
        expect(prim.model).toBe('OfficialStableDiffusion/sd_xl_base_1.0.safetensors');
        expect(prim.refinerModel).toBeNull();
    });
});
