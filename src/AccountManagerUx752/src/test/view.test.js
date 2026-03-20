import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

beforeAll(() => {
    am7model._view = {
        path: () => '',
        formField: () => null
    };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
});

describe('am7view field rendering', () => {
    it('should map boolean type to checkbox format', () => {
        expect(am7view.getFormatForType('boolean')).toBe('checkbox');
    });

    it('should map list type to select format', () => {
        expect(am7view.getFormatForType('list')).toBe('select');
    });

    it('should map enum type to select format', () => {
        expect(am7view.getFormatForType('enum')).toBe('select');
    });

    it('should map zonetime to datetime-local format', () => {
        expect(am7view.getFormatForType('zonetime')).toBe('datetime-local');
    });

    it('should default unknown types to text format', () => {
        expect(am7view.getFormatForType('unknown')).toBe('text');
        expect(am7view.getFormatForType('string')).toBe('text');
    });

    it('should get default values for list field', () => {
        let field = { type: 'list', limit: ['a', 'b', 'c'] };
        let vals = am7view.defaultValuesForField(field);
        expect(vals).toEqual(['a', 'b', 'c']);
    });

    it('should return empty for unhandled field type', () => {
        let field = { type: 'string' };
        let vals = am7view.defaultValuesForField(field);
        expect(vals).toEqual([]);
    });

    it('should detect binary content types', () => {
        expect(am7view.isBinary(null, 'image/png')).toBe(true);
        expect(am7view.isBinary(null, 'application/pdf')).toBe(true);
        expect(am7view.isBinary(null, 'text/plain')).toBe(false);
        expect(am7view.isBinary(null, 'application/json')).toBe(false);
        expect(am7view.isBinary(null, 'text/javascript')).toBe(false);
    });

    it('should get base path by type', () => {
        let path = am7view.path('system.user');
        let model = am7model.getModel('system.user');
        if (model && model.group) {
            expect(path).toBe('~/' + model.group);
        } else {
            expect(path).toBe('~');
        }
    });

    it('should look up formField from form', () => {
        let form = {
            fields: {
                name: { layout: 'full', label: 'Name' },
                age: { layout: 'half' }
            }
        };
        expect(am7view.formField(form, 'name')).toEqual({ layout: 'full', label: 'Name' });
        expect(am7view.formField(form, 'age')).toEqual({ layout: 'half' });
        expect(am7view.formField(form, 'missing')).toBeUndefined();
    });

    it('should return null formField for null form', () => {
        expect(am7view.formField(null, 'name')).toBeUndefined();
    });
});

describe('am7view showField', () => {
    it('should show field with no required roles', () => {
        let ref = {};
        expect(am7view.showField(null, ref)).toBe(true);
    });

    it('should hide field when required role not met', () => {
        let ref = { requiredRoles: ['admin'] };
        let inst = { entity: {} };
        expect(am7view.showField(inst, ref)).toBe(false);
    });
});
