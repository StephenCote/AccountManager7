/**
 * UAT#3 regression: ensureSdConfig() in pictureBook.js must use the user's saved
 * default config (loadConfig('sdcfg-default', '~/Data/.preferences')) BEFORE falling
 * back to am7sd.buildEntity() / randomImageConfig. The bug was that new books always
 * got a random config regardless of any saved defaults.
 *
 * ensureSdConfig() is module-private; it is exposed via the test-only seam
 * `export { ensureSdConfig }` and `export function __resetSdConfigForTest()` that
 * follow the same pattern as __setPromptStateForTest / getPromptTemplate.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';

// vi.hoisted — create the controllable stubs BEFORE vi.mock() factories run
// (factories are hoisted to the top of the file; plain `const x = vi.fn()` would
//  not be initialised yet when the factory body executes).
const {
    mockLoadConfig,
    mockBuildEntity,
    mockFillStyleDefaults,
    mockPrepareInstance
} = vi.hoisted(() => ({
    mockLoadConfig: vi.fn(),
    mockBuildEntity: vi.fn(),
    mockFillStyleDefaults: vi.fn(),
    mockPrepareInstance: vi.fn((entity) => ({ entity, api: {} }))
}));

// ── Module mocks ────────────────────────────────────────────────────────

vi.mock('mithril', () => ({
    default: Object.assign(
        (tag, attrs, children) => ({ tag, attrs, children }),
        {
            redraw: vi.fn(),
            route: { set: vi.fn(), get: () => '/' },
            trust: (s) => s
        }
    )
}));

vi.mock('../core/am7client.js', () => ({
    am7client: {
        base: () => 'https://localhost:8443/rest',
        member: vi.fn().mockResolvedValue(true)
    }
}));

vi.mock('../core/pageClient.js', () => ({
    page: {
        toast: vi.fn(),
        clearToast: vi.fn(),
        clearContextObject: vi.fn(),
        user: null,
        context: () => ({ roles: {} })
    }
}));

vi.mock('../components/dialogCore.js', () => ({
    Dialog: { open: vi.fn(), close: vi.fn() }
}));

vi.mock('../workflows/sceneExtractor.js', () => ({
    extractScenes: vi.fn(),
    createFromScenes: vi.fn(),
    createChapBookRecord: vi.fn(),
    generateSceneImage: vi.fn(),
    prepareSceneImagePrompts: vi.fn(),
    cancelPictureBook: vi.fn(),
    regenerateBlurb: vi.fn(),
    loadPictureBook: vi.fn(),
    getBookSdConfig: vi.fn(),
    setBookSdConfig: vi.fn(),
    setSceneStatus: vi.fn(),
    resolveImageUrl: vi.fn(),
    resolveAllImageUrls: vi.fn()
}));

vi.mock('../workflows/pictureBookCharacters.js', () => ({
    openCharacterManager: vi.fn(),
    initCharacterManager: vi.fn(),
    renderCharacterManagerContent: vi.fn()
}));

vi.mock('../components/picker.js', () => ({
    ObjectPicker: vi.fn()
}));

vi.mock('../chat/LLMConnector.js', () => ({
    LLMConnector: vi.fn()
}));

vi.mock('../components/SdConfigPanel.js', () => ({
    SdConfigPanel: vi.fn()
}));

// am7model: provide enough surface for ensureSdConfig to run without throwing.
// jsonModelKey, forms.sdConfig, prepareInstance, newPrimitive are the only fields touched.
vi.mock('../core/model.js', () => ({
    am7model: {
        jsonModelKey: 'schema',
        forms: { sdConfig: {} },
        prepareInstance: mockPrepareInstance,
        newPrimitive: vi.fn(() => ({})),
        _client: { newQuery: () => ({ entity: { request: [] }, field: () => {} }) },
        _page: { user: null, context: () => ({ roles: {} }) },
        _view: {}
    }
}));

// THE KEY MOCK: am7sd — controls the two code paths under test.
vi.mock('../components/sdConfig.js', () => ({
    am7sd: {
        loadConfig: mockLoadConfig,
        buildEntity: mockBuildEntity,
        fillStyleDefaults: mockFillStyleDefaults,
        fetchTemplate: vi.fn().mockResolvedValue(null),
        fetchModels: vi.fn().mockResolvedValue([]),
        fetchLoras: vi.fn().mockResolvedValue([]),
        saveConfig: vi.fn().mockResolvedValue(null),
        applyConfig: vi.fn()
    }
}));

// ── Test suite ──────────────────────────────────────────────────────────

describe('ensureSdConfig — UAT#3: saved default takes priority over randomImageConfig', () => {
    let ensureSdConfig;
    let __resetSdConfigForTest;

    beforeAll(async () => {
        let mod = await import('../workflows/pictureBook.js');
        ensureSdConfig = mod.ensureSdConfig;
        __resetSdConfigForTest = mod.__resetSdConfigForTest;
        expect(typeof ensureSdConfig).toBe('function');
        expect(typeof __resetSdConfigForTest).toBe('function');
    });

    beforeEach(() => {
        // Reset module-level cache so each test exercises the full async logic.
        __resetSdConfigForTest();
        // Clear mock call history / return values; set baseline no-ops.
        vi.clearAllMocks();
        mockPrepareInstance.mockImplementation((entity) => ({ entity, api: {} }));
    });

    it('calls loadConfig with the correct key and path', async () => {
        // Saved config found — buildEntity path must be skipped entirely.
        let savedConfig = { schema: 'olio.sd.config', model: 'realisticVisionV6', steps: 30 };
        mockLoadConfig.mockResolvedValue(savedConfig);
        mockBuildEntity.mockResolvedValue({ schema: 'olio.sd.config', model: 'random' });

        await ensureSdConfig();

        expect(mockLoadConfig).toHaveBeenCalledTimes(1);
        expect(mockLoadConfig).toHaveBeenCalledWith('sdcfg-default', '~/Data/.preferences');
    });

    it('does NOT call buildEntity when loadConfig returns a saved config object', async () => {
        let savedConfig = { schema: 'olio.sd.config', model: 'realisticVisionV6', steps: 30 };
        mockLoadConfig.mockResolvedValue(savedConfig);
        mockBuildEntity.mockResolvedValue({ schema: 'olio.sd.config', model: 'random' });

        await ensureSdConfig();

        expect(mockBuildEntity).not.toHaveBeenCalled();
    });

    it('calls buildEntity as fallback when loadConfig returns null', async () => {
        mockLoadConfig.mockResolvedValue(null);
        let fallbackEntity = { schema: 'olio.sd.config', model: 'randomSd' };
        mockBuildEntity.mockResolvedValue(fallbackEntity);

        await ensureSdConfig();

        expect(mockLoadConfig).toHaveBeenCalledWith('sdcfg-default', '~/Data/.preferences');
        expect(mockBuildEntity).toHaveBeenCalledTimes(1);
    });

    it('calls buildEntity as fallback when loadConfig throws', async () => {
        mockLoadConfig.mockRejectedValue(new Error('network error'));
        let fallbackEntity = { schema: 'olio.sd.config', model: 'randomSd' };
        mockBuildEntity.mockResolvedValue(fallbackEntity);

        await ensureSdConfig();

        // The catch inside ensureSdConfig swallows the error and proceeds to buildEntity
        expect(mockBuildEntity).toHaveBeenCalledTimes(1);
    });

    it('returns the sdConfigInst built from the saved config (not from buildEntity)', async () => {
        let savedConfig = { schema: 'olio.sd.config', model: 'preferred' };
        mockLoadConfig.mockResolvedValue(savedConfig);
        let sentinel = { entity: savedConfig, api: {}, _fromSaved: true };
        mockPrepareInstance.mockReturnValue(sentinel);

        let result = await ensureSdConfig();

        expect(result).toBe(sentinel);
    });

    it('second call returns the cached sdConfigInst without re-calling loadConfig or buildEntity', async () => {
        mockLoadConfig.mockResolvedValue({ schema: 'olio.sd.config', model: 'x' });
        mockBuildEntity.mockResolvedValue({ schema: 'olio.sd.config', model: 'y' });

        let first = await ensureSdConfig();
        // Do NOT call __resetSdConfigForTest — sdConfigInst is now set.
        vi.clearAllMocks();

        let second = await ensureSdConfig();

        // Both calls return the same instance.
        expect(second).toBe(first);
        // Cache hit — neither am7sd function is called again.
        expect(mockLoadConfig).not.toHaveBeenCalled();
        expect(mockBuildEntity).not.toHaveBeenCalled();
    });
});
