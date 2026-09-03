/**
 * P2-3 "Dialect UI" — the system.connection model descriptor must expose the server's `dialect`
 * enum so the generic object editor renders it as a dropdown, and the shared object-editor save path
 * (model.js inst.patch()) must carry the required `name` field or the server writer silently rejects
 * the PATCH (olio/common.nameId $notEmpty `\S` rule — see .claude/rules/model-api.md and the KI-35
 * precedent in dressApparelPatch.test.js).
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

describe('P2-3 dialect model descriptor', () => {
    it('system.connection exposes a `dialect` enum field matching the server model', () => {
        let f = connectionField('dialect');
        expect(f).toBeDefined();
        expect(f.type).toBe('enum');
        expect(f.baseClass).toBe('org.cote.accountmanager.schema.type.ConnectionDialectEnumType');
        expect(f.maxLength).toBe(16);
        expect(f.default).toBe('UNKNOWN');
    });

    it('the ConnectionDialectEnumType enum values are registered so the dropdown can populate', () => {
        // core/view.js getDefaultValuesForField() resolves enum options from
        // am7model.enums[camelCase(baseClass simple name)] — for ConnectionDialectEnumType that key
        // is `connectionDialectEnumType` (only the first char is lowercased).
        expect(am7model.enums.connectionDialectEnumType).toEqual([
            'UNKNOWN', 'OLLAMA', 'OPENAI', 'OPENAI_COMPAT'
        ]);
    });

    it('olio.llm.chatConfig.serviceType maxLength is aligned to the server model (16, was 10)', () => {
        let f = am7model.getModelFields('olio.llm.chatConfig').filter(x => x.name === 'serviceType')[0];
        expect(f).toBeDefined();
        expect(f.maxLength).toBe(16);
    });
});

describe('P2-3 connection-edit save path (inst.patch)', () => {
    // Simulate loading an existing connection, then the user changing ONLY dialect.
    function editedConnection() {
        let inst = am7model.newInstance('system.connection');
        // Fields as they would arrive from the server on load (user did NOT edit these).
        inst.entity.id = 100;
        inst.entity.objectId = 'conn-uuid-100';
        inst.entity.name = 'My LiteLLM Proxy';
        inst.entity.serverUrl = 'http://192.168.1.42:4000';
        // The one field the user changes goes through the documented setter, which marks it changed.
        inst.api.dialect('openai_compat');
        return inst;
    }

    it('marks only dialect as changed', () => {
        let inst = editedConnection();
        expect(inst.changes).toContain('dialect');
        expect(inst.changes).not.toContain('name');
    });

    it('the patch carries schema + an identity + the changed dialect', () => {
        // inst.patch() intentionally includes exactly ONE identity field (the b1id guard); that
        // satisfies the server's "at least one identity (id|objectId|urn)" PATCH requirement.
        let patch = editedConnection().patch();
        expect(patch[am7model.jsonModelKey]).toBe('system.connection');
        expect(patch.objectId === 'conn-uuid-100' || patch.id === 100).toBe(true);
        expect(patch.dialect).toBe('openai_compat');
    });

    it('the patch carries the required `name` so the server writer does not silently reject it', () => {
        // common.nameId `name` has a $notEmpty (`\\S`) rule; a PATCH omitting it fails validation
        // on the server and the update result is discarded (KI-35 / model-api.md). The generic
        // object editor (views/object.js) sends inst.patch() verbatim, so name must be present here.
        let patch = editedConnection().patch();
        expect(typeof patch.name).toBe('string');
        expect(patch.name.length).toBeGreaterThan(0);
    });
});
