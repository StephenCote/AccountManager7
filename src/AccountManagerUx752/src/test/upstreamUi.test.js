/**
 * `upstream` UI — the system.connection model descriptor must expose the server's new `upstream`
 * enum so the generic object editor (views/object.js generateDefaultForm -> format "select") renders
 * it as a dropdown, and the shared object-editor save path (model.js inst.patch()) must carry the
 * required `name` field or the server writer silently rejects the PATCH (common.nameId $notEmpty
 * `\S` rule — see .claude/rules/model-api.md and the KI-35 precedent in dressApparelPatch.test.js).
 *
 * Modelled on dialectUi.test.js. `upstream` is the upstream model-server FAMILY behind an endpoint
 * and is an axis INDEPENDENT of the wire `dialect`: Ollama reached through an OpenAI-compatible
 * proxy (LiteLLM) is still Ollama and still accepts Ollama extension parameters. Note the naming
 * trap: ConnectionUpstreamEnumType.OPENAI is NOT ConnectionDialectEnumType.OPENAI (the latter means
 * Azure's /openai/deployments/... URL scheme).
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';

beforeAll(() => {
    // Minimal stubs so prepareInstance/newInstance run in node env (matches model.test.js).
    am7model._view = { path: () => '', pathForType: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
});

function connectionField(name) {
    return am7model.getModelFields('system.connection').filter(f => f.name === name)[0];
}

describe('upstream model descriptor', () => {
    it('system.connection exposes an `upstream` enum field matching the server model', () => {
        let f = connectionField('upstream');
        expect(f).toBeDefined();
        expect(f.type).toBe('enum');
        expect(f.baseClass).toBe('org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType');
        expect(f.maxLength).toBe(16);
        expect(f.default).toBe('UNKNOWN');
    });

    it('the ConnectionUpstreamEnumType enum values are registered so the dropdown can populate', () => {
        // core/view.js getDefaultValuesForField() resolves enum options from
        // am7model.enums[camelCase(baseClass simple name)] — for ConnectionUpstreamEnumType that key
        // is `connectionUpstreamEnumType` (only the first char is lowercased). Without this entry
        // the field renders as an EMPTY dropdown, which looks like a broken form.
        expect(am7model.enums.connectionUpstreamEnumType).toEqual([
            'UNKNOWN', 'OLLAMA', 'OPENAI'
        ]);
    });

    it('the description warns that upstream OPENAI is not dialect OPENAI, and is proxy-independent', () => {
        // The two enums do NOT map 1:1 and a reader will assume they do. The descriptor is the only
        // place the editor surfaces that to an operator, so the caveat must actually be in the text.
        let d = connectionField('upstream').description;
        expect(typeof d).toBe('string');
        expect(d).toMatch(/NOT the same meaning as dialect/i);
        expect(d).toMatch(/LiteLLM/);
        expect(d).toMatch(/num_ctx/);
        expect(d).toMatch(/OPENAI_COMPAT->UNKNOWN/);
    });

    it('upstream does not need a formDef entry, for the same reason dialect does not', () => {
        // views/object.js setInst() looks up am7model.forms[<model simple name>] and only falls back
        // to generateDefaultForm() when absent. `system.connection` has no named form, so the editor
        // is generically driven from modelDef and enum fields get format "select" automatically.
        expect(am7model.forms.connection).toBeUndefined();
    });
});

describe('connection-edit save path (inst.patch) for upstream', () => {
    // Simulate loading an existing connection, then the user changing ONLY upstream.
    function editedConnection(field, value) {
        let inst = am7model.newInstance('system.connection');
        // Fields as they would arrive from the server on load (user did NOT edit these).
        inst.entity.id = 100;
        inst.entity.objectId = 'conn-uuid-100';
        inst.entity.name = 'My LiteLLM Proxy';
        inst.entity.serverUrl = 'http://192.168.1.42:4000';
        inst.entity.dialect = 'openai_compat';
        // The one field the user changes goes through the documented setter, which marks it changed.
        inst.api[field](value);
        return inst;
    }

    it('the patch carries schema + an identity + the changed upstream + a non-empty name', () => {
        // inst.patch() intentionally includes exactly ONE identity field (the b1id guard); that
        // satisfies the server's "at least one identity (id|objectId|urn)" PATCH requirement.
        // `name` must be present or the writer's validation of the PATCH RECORD ITSELF fails and
        // the update result is silently discarded (KI-35 / model-api.md).
        let patch = editedConnection('upstream', 'ollama').patch();
        expect(patch[am7model.jsonModelKey]).toBe('system.connection');
        expect(patch.objectId === 'conn-uuid-100' || patch.id === 100).toBe(true);
        expect(patch.upstream).toBe('ollama');
        expect(typeof patch.name).toBe('string');
        expect(patch.name.length).toBeGreaterThan(0);
    });

    it('changing upstream marks only upstream as changed — not dialect', () => {
        let inst = editedConnection('upstream', 'ollama');
        expect(inst.changes).toContain('upstream');
        expect(inst.changes).not.toContain('dialect');
    });

    it('changing dialect marks only dialect as changed — not upstream', () => {
        // The inverse direction: the two are independent axes, and a regression that coupled them
        // (e.g. deriving one from the other in the setter) would otherwise be invisible.
        let inst = editedConnection('dialect', 'ollama');
        expect(inst.changes).toContain('dialect');
        expect(inst.changes).not.toContain('upstream');
    });

    it('a dialect-only edit does not send upstream in the patch, and vice versa', () => {
        let dPatch = editedConnection('dialect', 'ollama').patch();
        expect(Object.prototype.hasOwnProperty.call(dPatch, 'upstream')).toBe(false);

        let uPatch = editedConnection('upstream', 'openai').patch();
        expect(uPatch.upstream).toBe('openai');
        expect(Object.prototype.hasOwnProperty.call(uPatch, 'dialect')).toBe(false);
    });
});
