import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';

// Wire am7view stub for tests that call newPrimitive/prepareInstance
beforeAll(() => {
    am7model._view = {
        path: () => '',
        formField: () => null
    };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
});

describe('am7model', () => {
    it('should have jsonModelKey defined', () => {
        expect(am7model.jsonModelKey).toBe('schema');
    });

    it('should have models loaded', () => {
        expect(am7model.models).toBeDefined();
        expect(am7model.models.length).toBeGreaterThan(0);
    });

    it('should have categories loaded', () => {
        expect(am7model.categories).toBeDefined();
        expect(am7model.categories.length).toBeGreaterThan(0);
    });

    it('should find a model by name', () => {
        const model = am7model.getModel('system.user');
        expect(model).toBeTruthy();
        expect(model.name).toBe('system.user');
    });

    it('should return null for unknown model', () => {
        const model = am7model.getModel('nonexistent.model');
        expect(model).toBeNull();
    });

    it('should have forms object initialized', () => {
        expect(am7model.forms).toBeDefined();
        expect(typeof am7model.forms).toBe('object');
    });

    it('should create a new primitive', () => {
        const prim = am7model.newPrimitive('common.nameId');
        expect(prim).toBeTruthy();
        expect(prim.schema).toBe('common.nameId');
    });

    it('should get model fields', () => {
        const fields = am7model.getModelFields('system.user');
        expect(fields).toBeDefined();
        expect(fields.length).toBeGreaterThan(0);
    });
});
