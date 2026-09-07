/**
 * CardGame SD-config caller alignment with the SD_CONFIG_NEVER_RESTORE guard (PictureBook / ChapBook
 * consistency pass).
 *
 * cardGame/ui/deckView.js restores per-deck SD overrides from the PERSISTED deck record and applies them
 * via am7sd.applyOverrides, which sets model/refinerModel. The olio.sd.config schema defaults hardcode
 * specific checkpoint names (model "sdXL_v10VAEFix.safetensors", refinerModel "juggernautXL_ragnarokBy
 * .safetensors"), and a user can pick any model per deck — so a stale/foreign checkpoint saved on one SD
 * node could be restored and overwrite the node-valid template model on another, the exact failure
 * chat/SceneGenerator.js guards with SD_CONFIG_NEVER_RESTORE.
 *
 * The fix shares one guard on the canonical am7sd module (am7sd.NEVER_RESTORE / am7sd.stripNeverRestore),
 * used by deckView.js on both the restore and save paths. These tests exercise the REAL am7sd functions
 * (no mocks) and prove the guarded restore preserves the node-valid model while still applying non-model
 * tweaks — and that WITHOUT the guard the same data poisons the template.
 */
import { describe, it, expect } from 'vitest';
import { am7sd } from '../components/sdConfig.js';

describe('am7sd NEVER_RESTORE guard', () => {
    it('marks model and refinerModel as never-restore', () => {
        expect(am7sd.NEVER_RESTORE).toContain('model');
        expect(am7sd.NEVER_RESTORE).toContain('refinerModel');
    });

    it('stripNeverRestore removes only model/refinerModel and keeps every other tweak', () => {
        const stripped = am7sd.stripNeverRestore({
            model: 'stale.safetensors', refinerModel: 'staleRef.safetensors',
            cfg: 9, steps: 42, style: 'art'
        });
        expect(stripped.model).toBeUndefined();
        expect(stripped.refinerModel).toBeUndefined();
        expect(stripped.cfg).toBe(9);
        expect(stripped.steps).toBe(42);
        expect(stripped.style).toBe('art');
    });

    it('tolerates null / non-object input', () => {
        expect(am7sd.stripNeverRestore(null)).toBe(null);
        expect(() => am7sd.stripNeverRestore(undefined)).not.toThrow();
    });
});

describe('Guarded restore preserves the node-valid template model (deckView restore path)', () => {
    // Mirrors deckView.js: a fresh template carries a node-valid model; a persisted override is
    // stripped of model/refinerModel before applyOverrides overlays it.
    const persistedOverride = { model: 'stale.safetensors', refinerModel: 'staleRef.safetensors', cfg: 9, style: 'art' };

    it('applyOverrides on a STRIPPED override keeps template model but applies the other tweaks', () => {
        const template = { schema: 'olio.sd.config', model: 'nodeValid.safetensors', refinerModel: 'nodeRef.safetensors', cfg: 7, style: 'photo' };
        const restored = am7sd.stripNeverRestore(JSON.parse(JSON.stringify(persistedOverride)));
        am7sd.applyOverrides(template, restored);
        expect(template.model).toBe('nodeValid.safetensors');       // node-valid model preserved
        expect(template.refinerModel).toBe('nodeRef.safetensors');  // node-valid refiner preserved
        expect(template.cfg).toBe(9);                               // non-model tweak still applied
        expect(template.style).toBe('art');                         // non-model tweak still applied
    });

    it('control: WITHOUT the guard the SAME persisted override poisons the template model', () => {
        const template = { schema: 'olio.sd.config', model: 'nodeValid.safetensors', refinerModel: 'nodeRef.safetensors', cfg: 7 };
        am7sd.applyOverrides(template, JSON.parse(JSON.stringify(persistedOverride)));
        expect(template.model).toBe('stale.safetensors');           // demonstrates the guard is load-bearing
        expect(template.refinerModel).toBe('staleRef.safetensors');
    });
});
