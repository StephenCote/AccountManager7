/**
 * A5 — ISO 42001 campaign "analysisProfile picker" (scoring profile FK).
 *
 * Pure client logic for the form helpers in features/iso42001/campaignsView.js:
 *   - blankForm() defaults analysisProfile to '' (None → engine uses spec defaults).
 *   - formValues(form) emits the FOREIGN field BY ID REFERENCE ({schema, objectId}) when a profile is
 *     selected, and OMITS it entirely when "None" — per model-api.md (foreign fields patch by ID; never a
 *     full graph). Because formValues() is spread into both the create body and the PATCH body (which also
 *     carry schema + identity + name), the patch always carries name + identity + the FK-by-id.
 *
 * Node-env safe: mithril + pageClient are mocked so importing campaignsView pulls no DOM/network. The
 * setup.js RAF shim is not exercised here (no m.request is triggered). Do NOT import mithril in setup.js.
 */
import { describe, it, expect, vi } from 'vitest';

vi.mock('mithril', () => ({
    default: {
        redraw: vi.fn(),
        request: vi.fn(() => Promise.resolve(null)),
        route: { set: vi.fn(), param: vi.fn() }
    }
}));

vi.mock('../core/pageClient.js', () => ({
    page: {
        user: { organizationId: 2 },
        context: () => ({ roles: {} }),
        toast: vi.fn(),
        patchObject: vi.fn(),
        deleteObject: vi.fn(),
        makePath: vi.fn()
    }
}));

import { blankForm, formValues } from '../features/iso42001/campaignsView.js';

const PROFILE_ID = 'prof-obj-abc-123';

describe('A5 analysisProfile picker — campaign form helpers', () => {

    it('blankForm() defaults analysisProfile to "" (None)', () => {
        let f = blankForm();
        expect(f).toHaveProperty('analysisProfile');
        expect(f.analysisProfile).toBe('');
    });

    it('formValues() with "None" OMITS analysisProfile (engine falls back to spec defaults)', () => {
        let f = blankForm();
        f.name = 'Campaign A';
        f.endpointName = 'llm-default';
        let v = formValues(f);
        // Omitted entirely — not present, not a stray null/empty object.
        expect(v).not.toHaveProperty('analysisProfile');
        expect(v.analysisProfile).toBeUndefined();
        // Sanity: the validated `name` field is still emitted (required for the PATCH to persist).
        expect(v.name).toBe('Campaign A');
    });

    it('formValues() with a selected profile emits the FK BY ID REFERENCE ({schema, objectId})', () => {
        let f = blankForm();
        f.name = 'Campaign B';
        f.endpointName = 'llm-default';
        f.analysisProfile = PROFILE_ID;
        let v = formValues(f);
        expect(v.analysisProfile).toEqual({ schema: 'iso42001.analysisProfile', objectId: PROFILE_ID });
        // FK-by-id ONLY — no full profile graph (no scoring knobs leak into the FK reference).
        expect(Object.keys(v.analysisProfile).sort()).toEqual(['objectId', 'schema']);
        // name still present so a PATCH carrying identity + this value passes validation.
        expect(v.name).toBe('Campaign B');
    });
});
