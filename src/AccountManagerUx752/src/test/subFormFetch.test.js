/**
 * KI-16 Finding A — redundant sub-form fetch bypasses cache-freshness guarantees.
 *
 * Two independent, pure-logic pieces fixed in components:
 *  1. core/model.js `am7model.hasPopulatedData()` — new helper used by views/object.js's
 *     `modelForm()` (`form.model && form.property` branch) to skip the redundant
 *     `am7client.search()` fix-up fetch when the parent record's own load (am7client.getFull())
 *     already populated the sub-object's non-identity fields.
 *  2. core/am7client.js `q.key()` — now includes the query's `request` field projection, so two
 *     queries against the same type/filter/sort/paging but with different requested fields can no
 *     longer collide on the same cache key (previously the `request` join was computed into a local
 *     `r` variable and then never actually included in the returned key string).
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';

beforeAll(() => {
    am7model._view = { path: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
});

describe('am7model.hasPopulatedData (KI-16 Finding A)', () => {
    it('returns false for a bare identity-only stub', () => {
        expect(am7model.hasPopulatedData({ schema: 'olio.statistics', id: 5 })).toBe(false);
    });

    it('returns false for an entity with no schema key at all', () => {
        expect(am7model.hasPopulatedData({ id: 5 })).toBe(false);
    });

    it('returns true once a real non-identity field differs from its default', () => {
        // olio.statistics `height` field: type double, default 5.10 (modelDef.js)
        expect(am7model.hasPopulatedData({ schema: 'olio.statistics', id: 5, height: 6.2 })).toBe(true);
    });

    it('a field left exactly at its schema default is not (and cannot be) distinguished from unset', () => {
        // Documented heuristic limitation, not a bug: a value equal to the default looks identical to
        // "never arrived" from the wire. Full-fidelity "did the server explicitly send this" tracking
        // would need per-field arrival flags the wire format doesn't carry.
        expect(am7model.hasPopulatedData({ schema: 'olio.statistics', id: 5, height: 5.10 })).toBe(false);
    });

    it('treats a non-empty populated list field as populated data', () => {
        // system.user inherits fields including `tags` (common.tagList) — a list field.
        expect(am7model.hasPopulatedData({ schema: 'system.user', id: 1, tags: [{ name: 'x' }] })).toBe(true);
    });
});

describe('am7client query cache key includes field projection (KI-16 Finding A)', () => {
    it('two queries with identical type/sort/paging but different `request` projections get different keys', async () => {
        const { am7client } = await import('../core/am7client.js');

        let qNarrow = am7client.newQuery('olio.statistics');
        qNarrow.entity.request = ['id', 'objectId', 'name'];

        let qFull = am7client.newQuery('olio.statistics');
        qFull.entity.request = ['id', 'objectId', 'name', 'physicalStrength', 'agility', 'speed'];

        expect(qNarrow.key()).not.toBe(qFull.key());
    });

    it('the request projection is actually present in the key string, not silently dropped', async () => {
        const { am7client } = await import('../core/am7client.js');
        let q = am7client.newQuery('olio.statistics');
        q.entity.request = ['physicalStrength', 'agility'];
        expect(q.key()).toContain('physicalStrength,agility');
    });

    it('identical projections still produce identical keys (no over-fragmentation)', async () => {
        const { am7client } = await import('../core/am7client.js');
        let q1 = am7client.newQuery('olio.statistics');
        q1.entity.request = ['id', 'name'];
        let q2 = am7client.newQuery('olio.statistics');
        q2.entity.request = ['id', 'name'];
        expect(q1.key()).toBe(q2.key());
    });
});
