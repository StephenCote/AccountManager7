/**
 * Magic 8 SD-config caller alignment (consistency with PictureBook / ChapBook SD-config work).
 *
 * Two confirmed divergences, both fixed and asserted here against ACTUAL default values (not slider
 * markup):
 *
 *  1. ImageGenerationManager._getDefaultConfig() and SessionConfigEditor._getDefaultConfig().imageGeneration
 *     .sdInline hardcoded scheduler "Karras" (capital K). Canon is lowercase 'karras' — it matches
 *     SdConfigPanel.SCHEDULER_OPTIONS / am7sd KNOWN_SCHEDULERS and the olio.sd.config schema default, and a
 *     capital "Karras" survives am7sd.buildEntity's post-normalize overlay and reaches the SD server verbatim.
 *
 *  2. ImageGenerationManager._buildSdConfigEntity() passed a config's model/refinerModel straight into
 *     am7sd.buildEntity, whose overlay only skips `!= null` values — so a blank "" model (the sdInline
 *     default) would overwrite the node-valid template model. The fix adds the blank model pair to
 *     skipFields; a real in-session pick still passes through.
 *
 * These import the REAL modules under test (heavy deps mocked, per the ensureSdConfig.test.js pattern) and
 * read the actual returned config objects / the actual skipFields handed to buildEntity.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Canonical lowercase scheduler set (mirrors SdConfigPanel.SCHEDULER_OPTIONS / am7sd KNOWN_SCHEDULERS).
const CANON_SCHEDULERS = ['normal', 'karras', 'exponential', 'sgm_uniform', 'simple', 'ddim_uniform', 'beta', 'linear_quadratic', 'kl_optimal'];

const { mockBuildEntity } = vi.hoisted(() => ({ mockBuildEntity: vi.fn() }));

vi.mock('mithril', () => ({
    default: Object.assign((tag, attrs, children) => ({ tag, attrs, children }),
        { redraw: vi.fn(), route: { set: vi.fn(), get: () => '/' }, trust: (s) => s })
}));

vi.mock('../core/model.js', () => ({
    am7model: { jsonModelKey: 'schema', _page: { user: null }, _client: {}, _view: {}, newPrimitive: vi.fn(() => ({})) }
}));
vi.mock('../core/view.js', () => ({ am7view: {} }));
vi.mock('../core/am7client.js', () => ({
    am7client: { base: () => 'https://localhost:8443/rest', clearCache: vi.fn() },
    uwm: {}
}));
vi.mock('../components/sdConfig.js', () => ({
    am7sd: { buildEntity: mockBuildEntity, fillStyleDefaults: vi.fn(), applyOverrides: vi.fn() }
}));
// SessionConfigEditor's static imports — mocked so importing the component does not pull Web Audio / LLM.
vi.mock('../magic8/audio/AudioEngine.js', () => ({ AudioEngine: class {} }));
vi.mock('../magic8/ai/SessionDirector.js', () => ({ SessionDirector: class {} }));

describe('Magic8 ImageGenerationManager default SD config', () => {
    let ImageGenerationManager;
    beforeEach(async () => {
        vi.clearAllMocks();
        ({ ImageGenerationManager } = await import('../magic8/generation/ImageGenerationManager.js'));
    });

    it('uses canonical lowercase scheduler "karras", never capital "Karras"', () => {
        const cfg = new ImageGenerationManager()._getDefaultConfig();
        expect(cfg.scheduler).toBe('karras');
        expect(cfg.scheduler).not.toBe('Karras');
        expect(CANON_SCHEDULERS).toContain(cfg.scheduler);
    });

    it('_buildSdConfigEntity skips a BLANK model/refinerModel so the node-valid template model survives', async () => {
        mockBuildEntity.mockResolvedValue({ schema: 'olio.sd.config', model: 'nodeValid.safetensors' });
        const mgr = new ImageGenerationManager();
        await mgr._buildSdConfigEntity({ model: '', refinerModel: '', description: 'x' }, 'ref-1');
        const opts = mockBuildEntity.mock.calls[0][1];
        expect(opts.skipFields).toContain('model');
        expect(opts.skipFields).toContain('refinerModel');
    });

    it('_buildSdConfigEntity does NOT skip a real in-session model pick', async () => {
        mockBuildEntity.mockResolvedValue({ schema: 'olio.sd.config', model: 'chosen.safetensors' });
        const mgr = new ImageGenerationManager();
        await mgr._buildSdConfigEntity({ model: 'chosen.safetensors', refinerModel: 'ref.safetensors' }, 'ref-2');
        const opts = mockBuildEntity.mock.calls[0][1];
        expect(opts.skipFields).not.toContain('model');
        expect(opts.skipFields).not.toContain('refinerModel');
    });
});

describe('Magic8 SessionConfigEditor default sdInline config', () => {
    it('uses canonical lowercase scheduler + refinerScheduler, never capital "Karras"', async () => {
        const { SessionConfigEditor } = await import('../magic8/components/SessionConfigEditor.js');
        const sd = SessionConfigEditor._getDefaultConfig().imageGeneration.sdInline;
        expect(sd.scheduler).toBe('karras');
        expect(sd.refinerScheduler).toBe('karras');
        expect(CANON_SCHEDULERS).toContain(sd.scheduler);
        expect(CANON_SCHEDULERS).toContain(sd.refinerScheduler);
    });

    it('keeps denoisingStrength native 0-1 (0.65), consistent with SdConfigPanel — no 0-100 scale', async () => {
        const { SessionConfigEditor } = await import('../magic8/components/SessionConfigEditor.js');
        const sd = SessionConfigEditor._getDefaultConfig().imageGeneration.sdInline;
        expect(sd.denoisingStrength).toBeGreaterThan(0);
        expect(sd.denoisingStrength).toBeLessThanOrEqual(1);
        expect(sd.denoisingStrength).toBe(0.65);
    });
});
