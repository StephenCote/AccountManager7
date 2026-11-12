// @vitest-environment jsdom
/**
 * One default chat-config pick for every PB/ChapBook LLM surface (P10-D 4, §6.C 267 d).
 *
 * Why: components/pbChatConfig.js (P10-C 3) holds the library names tried for the default chat config
 * (contentAnalysis, then generalChat) and the lookup loop, used by the composition board and the Locations
 * panel. ChapBook kept its own CB_DEFAULT_CHAT_CONFIG_NAMES + loop, and the PB wizard kept a one-name copy
 * (contentAnalysis only — a stack whose contentAnalysis is missing left the wizard with no pick and the
 * server's generalChat default). Both now resolve through pbChatConfig.resolveDefaultChatConfig; ChapBook
 * keeps only its library priming (ensureLibrary + initPromptLibrary) in front of it.
 *
 * LLMConnector is stubbed at the library-lookup boundary; the real ChapBook and wizard functions run.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { LLMConnector } from '../chat/LLMConnector.js';
import { DEFAULT_CHAT_CONFIG_NAMES } from '../components/pbChatConfig.js';

function flushPromises() { return new Promise(r => setTimeout(r, 0)); }
function jsonResponse(status, body) {
    let text = JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

let origFetch;
beforeEach(() => {
    origFetch = global.fetch;
    global.fetch = vi.fn(async (u) => jsonResponse(404, { error: 'unmocked ' + String(u) }));
    vi.spyOn(LLMConnector, 'ensureLibrary').mockResolvedValue(true);
    vi.spyOn(LLMConnector, 'initPromptLibrary').mockResolvedValue(true);
    vi.spyOn(LLMConnector, 'resolveTemplate').mockResolvedValue(null);
    // contentAnalysis is absent on this stack; generalChat resolves.
    vi.spyOn(LLMConnector, 'resolveConfig').mockImplementation(async (name) => name === 'generalChat' ? { name: 'generalChat', objectId: 'cc-gen', extra: 'full record' } : null);
});
afterEach(() => { global.fetch = origFetch; vi.restoreAllMocks(); });

describe('pbChatConfig.DEFAULT_CHAT_CONFIG_NAMES is the one list', () => {
    it('contentAnalysis, then generalChat', () => {
        expect(DEFAULT_CHAT_CONFIG_NAMES).toEqual(['contentAnalysis', 'generalChat']);
    });
});

describe('ChapBook render dialog default', () => {
    it('resolves through pbChatConfig (same names, same order) after priming the libraries, and does not overwrite a pick', async () => {
        let cb = await import('../features/chapBook.js');
        cb.__setChatConfigStateForTest({ configChatConfigRef: null, renderChatConfigRef: null, createChatConfigRef: null });
        cb.openRenderConfigDialog('book-cb-1', null);
        await flushPromises(); await flushPromises();
        expect(LLMConnector.ensureLibrary).toHaveBeenCalledTimes(1);
        expect(LLMConnector.initPromptLibrary).toHaveBeenCalledTimes(1);
        expect(LLMConnector.resolveConfig.mock.calls.map(c => c[0])).toEqual(DEFAULT_CHAT_CONFIG_NAMES);
        expect(cb.effectiveChatConfigRef()).toEqual({ name: 'generalChat', objectId: 'cc-gen' });

        // A pick made before the resolver returns is kept (the resolver is advisory).
        LLMConnector.resolveConfig.mockClear();
        cb.__setChatConfigStateForTest({ configChatConfigRef: null, renderChatConfigRef: null, createChatConfigRef: null });
        let resolveGate;
        LLMConnector.resolveConfig.mockImplementation(() => new Promise(r => { resolveGate = () => r({ name: 'contentAnalysis', objectId: 'cc-ca' }); }));
        cb.openRenderConfigDialog('book-cb-2', null);
        await flushPromises();
        cb.__setChatConfigStateForTest({ renderChatConfigRef: { name: 'myPick', objectId: 'cc-pick' } });
        resolveGate();
        await flushPromises(); await flushPromises();
        expect(cb.effectiveChatConfigRef()).toEqual({ name: 'myPick', objectId: 'cc-pick' });
    });
});

describe('PB wizard loadDefaults', () => {
    it('falls through to generalChat when contentAnalysis is missing (the wizard copy stopped at contentAnalysis)', async () => {
        let pb = await import('../workflows/pictureBook.js');
        pb.__setExtractStateForTest({ chatConfigRef: null });
        await pb.__loadDefaultsForTest();
        expect(LLMConnector.resolveConfig.mock.calls.map(c => c[0])).toEqual(DEFAULT_CHAT_CONFIG_NAMES);
        // toRef shape: {name, objectId} only — the full record's other fields never reach the wizard state.
        expect(pb.__extractStateForTest().chatConfigRef).toEqual({ name: 'generalChat', objectId: 'cc-gen' });
    });
    it('keeps an existing pick and does not look the default up at all', async () => {
        let pb = await import('../workflows/pictureBook.js');
        pb.__setExtractStateForTest({ chatConfigRef: { name: 'myPick', objectId: 'cc-pick' } });
        await pb.__loadDefaultsForTest();
        expect(LLMConnector.resolveConfig).not.toHaveBeenCalled();
        expect(pb.__extractStateForTest().chatConfigRef).toEqual({ name: 'myPick', objectId: 'cc-pick' });
    });
    it('no library config at all → no pick (the server default applies), never a throw', async () => {
        LLMConnector.resolveConfig.mockImplementation(async () => { throw new Error('library unreachable'); });
        let pb = await import('../workflows/pictureBook.js');
        pb.__setExtractStateForTest({ chatConfigRef: null });
        await pb.__loadDefaultsForTest();
        expect(pb.__extractStateForTest().chatConfigRef).toBeNull();
    });
});
