import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
    isSystemModel, isSystemField, FIELD_TYPES,
    createModelSchema, addFieldsToModel, deleteModel, deleteField,
    fetchModelNames, fetchModelSchema, fetchModelFields
} from '../features/schema.js';

// Mock mithril's m.request
vi.mock('mithril', () => {
    let _handler = null;
    return {
        default: Object.assign(
            function m() { return { tag: 'div' }; },
            {
                request: function (opts) {
                    if (_handler) return _handler(opts);
                    return Promise.resolve({});
                },
                redraw: function () {},
                route: { set: function () {} }
            }
        ),
        __setRequestHandler: function (fn) { _handler = fn; }
    };
});

// Mock pageClient
vi.mock('../core/pageClient.js', () => ({
    page: {
        context: () => ({ roles: { admin: true } }),
        toast: vi.fn(),
        makePath: vi.fn().mockResolvedValue({ id: 1, objectId: 'abc', path: '/test' })
    }
}));

// Mock am7client
vi.mock('../core/am7client.js', () => ({
    am7client: {
        list: vi.fn().mockResolvedValue({ results: [] }),
        create: vi.fn().mockResolvedValue({ objectId: 'new-id' }),
        patch: vi.fn().mockResolvedValue(true),
        delete: vi.fn().mockResolvedValue(true),
        getFull: vi.fn().mockResolvedValue({})
    }
}));

// Mock model
vi.mock('../core/model.js', () => ({
    am7model: { getModel: () => null, _page: null }
}));

// Mock router
vi.mock('../router.js', () => ({
    layout: (c) => c,
    pageLayout: (c) => c
}));

// Mock config
vi.mock('../core/config.js', () => ({
    applicationPath: 'http://localhost:8080/AccountManagerService7'
}));

describe('Schema Write Feature (Phase 13)', () => {

    describe('isSystemModel', () => {
        it('returns true for system models (system=true)', () => {
            expect(isSystemModel({ name: 'system.user', system: true })).toBe(true);
        });

        it('returns true when system flag is absent (default is system)', () => {
            expect(isSystemModel({ name: 'auth.group' })).toBe(true);
        });

        it('returns false for user-defined models (system=false)', () => {
            expect(isSystemModel({ name: 'custom.test', system: false })).toBe(false);
        });

        it('returns falsy for null/undefined', () => {
            expect(isSystemModel(null)).toBeFalsy();
            expect(isSystemModel(undefined)).toBeFalsy();
        });
    });

    describe('isSystemField', () => {
        it('returns true for system fields', () => {
            expect(isSystemField({ name: 'id', system: true })).toBe(true);
        });

        it('returns true when system flag is absent', () => {
            expect(isSystemField({ name: 'name' })).toBe(true);
        });

        it('returns false for user-defined fields', () => {
            expect(isSystemField({ name: 'customField', system: false })).toBe(false);
        });

        it('returns falsy for null/undefined', () => {
            expect(isSystemField(null)).toBeFalsy();
            expect(isSystemField(undefined)).toBeFalsy();
        });
    });

    describe('FIELD_TYPES', () => {
        it('contains standard field types', () => {
            expect(FIELD_TYPES).toContain('string');
            expect(FIELD_TYPES).toContain('int');
            expect(FIELD_TYPES).toContain('long');
            expect(FIELD_TYPES).toContain('double');
            expect(FIELD_TYPES).toContain('boolean');
            expect(FIELD_TYPES).toContain('enum');
            expect(FIELD_TYPES).toContain('blob');
            expect(FIELD_TYPES).toContain('timestamp');
            expect(FIELD_TYPES).toContain('zonetime');
            expect(FIELD_TYPES).toContain('list');
        });

        it('has at least 10 types', () => {
            expect(FIELD_TYPES.length).toBeGreaterThanOrEqual(10);
        });
    });

    describe('Schema API functions are exported', () => {
        it('exports createModelSchema', () => {
            expect(typeof createModelSchema).toBe('function');
        });

        it('exports addFieldsToModel', () => {
            expect(typeof addFieldsToModel).toBe('function');
        });

        it('exports deleteModel', () => {
            expect(typeof deleteModel).toBe('function');
        });

        it('exports deleteField', () => {
            expect(typeof deleteField).toBe('function');
        });

        it('exports read API functions', () => {
            expect(typeof fetchModelNames).toBe('function');
            expect(typeof fetchModelSchema).toBe('function');
            expect(typeof fetchModelFields).toBe('function');
        });
    });

    describe('Schema write API calls', () => {
        let lastRequest = null;

        beforeEach(async () => {
            const mMod = await import('mithril');
            mMod.__setRequestHandler(function (opts) {
                lastRequest = opts;
                return Promise.resolve({ name: 'test', fields: [] });
            });
        });

        it('createModelSchema sends POST to /rest/schema', async () => {
            await createModelSchema({ name: 'custom.test', fields: [] });
            expect(lastRequest.method).toBe('POST');
            expect(lastRequest.url).toContain('/rest/schema');
            expect(lastRequest.body.name).toBe('custom.test');
        });

        it('addFieldsToModel sends PUT to /rest/schema/{type}', async () => {
            await addFieldsToModel('custom.test', [{ name: 'newField', type: 'string' }]);
            expect(lastRequest.method).toBe('PUT');
            expect(lastRequest.url).toContain('/rest/schema/custom.test');
            expect(lastRequest.body.fields).toHaveLength(1);
        });

        it('deleteModel sends DELETE to /rest/schema/{type}', async () => {
            await deleteModel('custom.test');
            expect(lastRequest.method).toBe('DELETE');
            expect(lastRequest.url).toContain('/rest/schema/custom.test');
        });

        it('deleteField sends DELETE to /rest/schema/{type}/field/{name}', async () => {
            await deleteField('custom.test', 'myField');
            expect(lastRequest.method).toBe('DELETE');
            expect(lastRequest.url).toContain('/rest/schema/custom.test/field/myField');
        });
    });
});
