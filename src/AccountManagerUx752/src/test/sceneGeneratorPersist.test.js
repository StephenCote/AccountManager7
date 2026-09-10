/**
 * Chat scene SD config — SERVER-SIDE per-chat persistence.
 *
 * The chat scene generator used to hold its SD config only in a GLOBAL localStorage key, so the
 * config never persisted per-chat and could not be reused. It now saves the config server-side via
 * am7sd.saveConfig/loadConfig (olio.sd.config records, same mechanism as charPerson reimage), keyed to
 * the durable olio.llm.chatConfig objectId — so every session of the same chat reuses it — plus an
 * optional chat-global shared config ('sharedChatSD.json'), distinct from the character 'sharedSD.json'.
 *
 * These are the invariants that make the feature correct and safe:
 *  1. The per-chat record name is keyed to the chatConfig objectId.
 *  2. ensureSdConfig loads the per-chat record first, and only falls back to the shared record.
 *  3. A saved config's model/refinerModel are NEVER restored onto the fresh template (node-poisoning
 *     guard) — the template's node-valid model survives while other saved fields overlay.
 *  4. persistConfig always saves the per-chat record (model stripped), and the shared record only when
 *     "Save as Shared" is set.
 *  5. Switching chat rebuilds the config and loads the new chat's record.
 *
 * ensureSdConfig / persistConfig / perChatConfigName are module-private; exercised via the test-only
 * seam exported from SceneGenerator.js (mirrors pictureBook.js's __resetSdConfigForTest pattern).
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';

const {
    mockLoadConfig,
    mockSaveConfig,
    mockBuildEntity,
    mockFillStyleDefaults,
    mockPrepareInstance
} = vi.hoisted(() => ({
    mockLoadConfig: vi.fn(),
    mockSaveConfig: vi.fn(),
    mockBuildEntity: vi.fn(),
    mockFillStyleDefaults: vi.fn(),
    mockPrepareInstance: vi.fn((entity) => ({ entity, api: {} }))
}));

// ── Module mocks ────────────────────────────────────────────────────────

vi.mock('mithril', () => ({
    default: Object.assign(
        (tag, attrs, children) => ({ tag, attrs, children }),
        { redraw: vi.fn(), route: { set: vi.fn(), get: () => '/' }, trust: (s) => s }
    )
}));

vi.mock('../core/pageClient.js', () => ({
    page: { toast: vi.fn(), clearToast: vi.fn() }
}));

vi.mock('../core/config.js', () => ({ applicationPath: '' }));

vi.mock('../components/SdConfigPanel.js', () => ({ SdConfigPanel: vi.fn() }));

vi.mock('../components/dialogCore.js', () => ({
    Dialog: { open: vi.fn(), close: vi.fn() }
}));

vi.mock('../core/model.js', () => ({
    am7model: {
        jsonModelKey: 'schema',
        forms: { sdConfig: {} },
        prepareInstance: mockPrepareInstance,
        newPrimitive: vi.fn(() => ({}))
    }
}));

// THE KEY MOCK: am7sd. stripNeverRestore uses the REAL deletion logic so the node-poisoning invariant
// is genuinely exercised (a no-op stub would make invariant #3 pass vacuously).
vi.mock('../components/sdConfig.js', () => ({
    am7sd: {
        loadConfig: mockLoadConfig,
        saveConfig: mockSaveConfig,
        buildEntity: mockBuildEntity,
        fillStyleDefaults: mockFillStyleDefaults,
        stripNeverRestore: (obj) => {
            if (obj && typeof obj === 'object') { delete obj.model; delete obj.refinerModel; }
            return obj;
        }
    }
}));

// ── Test suite ──────────────────────────────────────────────────────────

describe('chat scene SD config — server-side per-chat persistence', () => {
    let ensureSdConfig, persistConfig, perChatConfigName;
    let __setChatConfigForTest, __setSaveSharedForTest, __getSdConfigForTest, __resetSceneGeneratorForTest;

    beforeAll(async () => {
        let mod = await import('../chat/SceneGenerator.js');
        ensureSdConfig = mod.ensureSdConfig;
        persistConfig = mod.persistConfig;
        perChatConfigName = mod.perChatConfigName;
        __setChatConfigForTest = mod.__setChatConfigForTest;
        __setSaveSharedForTest = mod.__setSaveSharedForTest;
        __getSdConfigForTest = mod.__getSdConfigForTest;
        __resetSceneGeneratorForTest = mod.__resetSceneGeneratorForTest;
        [ensureSdConfig, persistConfig, perChatConfigName, __setChatConfigForTest,
         __setSaveSharedForTest, __getSdConfigForTest, __resetSceneGeneratorForTest]
            .forEach((fn) => expect(typeof fn).toBe('function'));
    });

    beforeEach(() => {
        __resetSceneGeneratorForTest();
        vi.clearAllMocks();
        mockPrepareInstance.mockImplementation((entity) => ({ entity, api: {} }));
        mockFillStyleDefaults.mockImplementation(() => {});
        // Fresh node-valid template each build.
        mockBuildEntity.mockImplementation(async () => ({
            schema: 'olio.sd.config',
            model: 'nodeValidModel.safetensors',
            steps: 40,
            style: 'photograph'
        }));
        mockLoadConfig.mockResolvedValue(null);
        mockSaveConfig.mockResolvedValue(true);
    });

    it('keys the per-chat record name to the chatConfig objectId (null when none)', () => {
        expect(perChatConfigName()).toBe(null);
        __setChatConfigForTest('CHAT_A');
        expect(perChatConfigName()).toBe('sdcfg-chat-CHAT_A');
    });

    it('loads the per-chat record first and does NOT fall back to shared when it exists', async () => {
        __setChatConfigForTest('CHAT_A');
        mockLoadConfig.mockImplementation((name) =>
            Promise.resolve(name === 'sdcfg-chat-CHAT_A' ? { schema: 'olio.sd.config', steps: 25 } : null));

        await ensureSdConfig();

        expect(mockLoadConfig).toHaveBeenCalledWith('sdcfg-chat-CHAT_A');
        expect(mockLoadConfig).not.toHaveBeenCalledWith('sharedChatSD.json');
    });

    it('falls back to the shared record when the per-chat record is absent', async () => {
        __setChatConfigForTest('CHAT_A');
        mockLoadConfig.mockResolvedValue(null); // neither exists

        await ensureSdConfig();

        expect(mockLoadConfig).toHaveBeenCalledWith('sdcfg-chat-CHAT_A');
        expect(mockLoadConfig).toHaveBeenCalledWith('sharedChatSD.json');
    });

    it('overlays saved fields but KEEPS the template model (never restores a saved model)', async () => {
        __setChatConfigForTest('CHAT_A');
        // Saved config carries a poison model (valid only on the node it was saved from) plus real tweaks.
        mockLoadConfig.mockImplementation((name) =>
            Promise.resolve(name === 'sdcfg-chat-CHAT_A'
                ? { schema: 'olio.sd.config', model: 'poisonNodeModel', steps: 30, style: 'anime' }
                : null));

        await ensureSdConfig();
        let cfg = __getSdConfigForTest();

        // model came from the template and survives; the saved model was stripped and never overlaid.
        expect(cfg.model).toBe('nodeValidModel.safetensors');
        // real tweaks overlaid.
        expect(cfg.steps).toBe(30);
        expect(cfg.style).toBe('anime');
    });

    it('persistConfig saves the per-chat record without model/refinerModel', async () => {
        __setChatConfigForTest('CHAT_A');
        await ensureSdConfig();               // sdConfig now has template model 'nodeValidModel.safetensors'
        __setSaveSharedForTest(false);

        let ok = await persistConfig();

        expect(ok).toBe(true);
        expect(mockSaveConfig).toHaveBeenCalledTimes(1);
        let [name, saved] = mockSaveConfig.mock.calls[0];
        expect(name).toBe('sdcfg-chat-CHAT_A');
        // node-specific model pair must never be persisted.
        expect('model' in saved).toBe(false);
        expect('refinerModel' in saved).toBe(false);
        // real fields still persisted.
        expect(saved.steps).toBe(40);
    });

    it('persistConfig also writes the shared record only when "Save as Shared" is set', async () => {
        __setChatConfigForTest('CHAT_A');
        await ensureSdConfig();
        __setSaveSharedForTest(true);

        await persistConfig();

        let names = mockSaveConfig.mock.calls.map((c) => c[0]);
        expect(names).toContain('sdcfg-chat-CHAT_A');
        expect(names).toContain('sharedChatSD.json');
    });

    it('rebuilds and loads the new chat record when the chat changes', async () => {
        __setChatConfigForTest('CHAT_A');
        await ensureSdConfig();
        expect(mockLoadConfig).toHaveBeenCalledWith('sdcfg-chat-CHAT_A');

        vi.clearAllMocks();
        mockBuildEntity.mockImplementation(async () => ({ schema: 'olio.sd.config', model: 'nodeValidModel.safetensors', steps: 40, style: 'photograph' }));
        mockLoadConfig.mockResolvedValue(null);

        __setChatConfigForTest('CHAT_B');     // resets the memoized config
        await ensureSdConfig();

        expect(mockBuildEntity).toHaveBeenCalledTimes(1);
        expect(mockLoadConfig).toHaveBeenCalledWith('sdcfg-chat-CHAT_B');
        expect(mockLoadConfig).not.toHaveBeenCalledWith('sdcfg-chat-CHAT_A');
    });
});
