/**
 * @vitest-environment jsdom
 *
 * Chat UX cleanup: the chat toolbar keeps session icon / bg-activity / Edit / Gossip / Scenes and moves
 * Export, Portraits, Fullscreen, LLM Debug, Clear history and Delete session into a more_vert overflow
 * (components/contextMenu.js). Clear history now asks first (components/dialogCore.js Dialog.confirm).
 * Debug-only info moved into LLMDebugPanel.SessionView, rendered in the chat's LLM Debug drawer.
 *
 * Real Mithril renders into jsdom; only the network (m.request, am7client.getFull) is stubbed.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { Dialog } from '../components/dialogCore.js';
import { contextMenu, contextMenuComponent } from '../components/contextMenu.js';
import { ContextPanel } from '../chat/ContextPanel.js';
import { MemoryPanel } from '../chat/MemoryPanel.js';
import { LLMDebugPanel } from '../chat/LLMDebugPanel.js';
import { pickSession, renderToolbar, renderDebugDrawer, sessionDebugInfo, chatOverflowItems } from '../features/chat.js';

const HISTORY = { messages: [{ role: 'user', content: 'hello' }, { role: 'assistant', content: 'hi there' }] };
const CONTEXT = {
    chatConfig: { name: 'Test CC', objectId: 'cc-1' },
    summarizing: true,
    contextRefs: [
        { objectId: 'doc-1', name: 'Doc One', refSchema: 'data.data', summarizing: true, summaryPhase: 'map', summaryCurrent: 1, summaryTotal: 3 },
        { objectId: 'doc-2', name: 'Doc Two', refSchema: 'data.data', summarizing: false }
    ]
};
const ACTIVE = {
    llmRequests: [
        { requestId: 'sess-1', model: 'qwen3:8b', tokenCount: 42, serviceType: 'OLLAMA', stopped: false },
        { requestId: 'someone-else', model: 'other-model', tokenCount: 7, serviceType: 'OLLAMA', stopped: false }
    ],
    summarizations: [
        { sessionId: 'cr-1', objectId: 'doc-1', phase: 'map', current: 1, total: 3, elapsed: 4, cancelled: false },
        { sessionId: 'cr-OTHER', objectId: 'doc-9', phase: 'reduce', current: 2, total: 2, elapsed: 9, cancelled: false }
    ],
    bufferModeStreams: 0, activeLLMCalls: [], activeLLMCallCount: 0
};

let root = null;
let requests = [];
let requestSpy = null;
let getFullSpy = null;

async function settle() {
    for (let i = 0; i < 12; i++) await new Promise(function (r) { setTimeout(r, 0); });
}

async function waitFor(pred, label) {
    for (let i = 0; i < 200; i++) {
        if (pred()) return;
        await new Promise(function (r) { setTimeout(r, 5); });
    }
    throw new Error('timed out waiting for ' + label + '; html=' + (root ? root.innerHTML.substring(0, 400) : ''));
}

function stubNetwork() {
    requests = [];
    requestSpy = vi.spyOn(m, 'request').mockImplementation(function (opts) {
        requests.push(opts);
        let url = (opts && opts.url) || '';
        if (/\/rest\/chat\/history$/.test(url)) return Promise.resolve(JSON.parse(JSON.stringify(HISTORY)));
        if (/\/rest\/chat\/context\//.test(url)) return Promise.resolve(JSON.parse(JSON.stringify(CONTEXT)));
        if (/\/rest\/chat\/llm\/active$/.test(url)) return Promise.resolve(JSON.parse(JSON.stringify(ACTIVE)));
        if (/\/rest\/chat\/clear$/.test(url)) return Promise.resolve({});
        if (/\/rest\/memory\//.test(url)) return Promise.resolve([]);
        return Promise.resolve(null);
    });
    getFullSpy = vi.spyOn(am7client, 'getFull').mockImplementation(async function (type, oid) {
        if (type === 'olio.llm.chatConfig') {
            return { schema: type, objectId: oid, name: 'Test CC', model: 'qwen3:8b', rating: 'e',
                systemCharacter: { objectId: 'sys-1' }, userCharacter: { objectId: 'usr-1' } };
        }
        if (type === 'olio.charPerson') return { schema: type, objectId: oid, name: oid === 'sys-1' ? 'Sys Char' : 'User Char' };
        return null;
    });
}

async function selectTestSession() {
    pickSession({
        schema: 'olio.llm.chatRequest', objectId: 'cr-1', name: 'Overflow test chat',
        chatConfig: { schema: 'olio.llm.chatConfig', objectId: 'cc-1', name: 'Test CC' },
        promptTemplate: { schema: 'olio.llm.promptTemplate', objectId: 'pt-1', name: 'Test PT' },
        session: { id: 5, objectId: 'sess-1' }, sessionType: 'olio.llm.openai.openaiRequest'
    });
    await waitFor(function () { return chatOverflowItems().some(function (i) { return i.label === 'Export chat'; }); }, 'history + chatConfig to load');
}

/** Toolbar + the global context menu + the dialog stack, as the app layout renders them. */
function mountToolbar() {
    root = document.createElement('div');
    document.body.appendChild(root);
    m.mount(root, { view: function () { return [renderToolbar(), renderDebugDrawer(sessionDebugInfo()), m(contextMenuComponent), Dialog.loadDialogs()]; } });
}

function titles(sel) {
    return Array.from(root.querySelectorAll(sel)).map(function (b) { return b.getAttribute('title'); });
}

async function openOverflow() {
    root.querySelector('button[title="More chat actions"]').click();
    m.redraw.sync();
    await waitFor(function () { return !!root.querySelector('.context-menu'); }, 'overflow menu');
}

describe('chat toolbar overflow', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
        Dialog._reset();
        stubNetwork();
    });

    afterEach(async () => {
        if (contextMenu.items().length) contextMenu.dismiss();
        if (root) { m.mount(root, null); root = null; }
        await settle();
        ContextPanel.clear();
        MemoryPanel.clear();
        Dialog._reset();
        requestSpy.mockRestore();
        getFullSpy.mockRestore();
        document.body.innerHTML = '';
    });

    it('keeps Edit / Gossip / Scenes visible and moves the rest into the overflow', async () => {
        await selectTestSession();
        mountToolbar();
        let visible = titles('[data-chat-toolbar="actions"] button');
        expect(visible).toContain('Edit messages');
        expect(visible).toContain('Memory Gossip');
        expect(visible).toContain('Scene Gallery');
        expect(visible).toContain('More chat actions');
        for (let moved of ['Export chat', 'Show portraits', 'Hide portraits', 'Fullscreen', 'Exit fullscreen', 'Clear history', 'Delete session', 'Show thoughts', 'Hide thoughts']) {
            expect(visible, moved + ' must not be on the visible toolbar').not.toContain(moved);
        }

        await openOverflow();
        let menuTitles = titles('.context-menu [role="menuitem"]');
        expect(menuTitles).toEqual(expect.arrayContaining(['Export chat', 'Show portraits', 'Fullscreen', 'Show the LLM debug drawer for this chat', 'Clear history', 'Delete session']));
        let labels = Array.from(root.querySelectorAll('.context-menu [role="menuitem"]')).map(function (b) { return b.querySelector('span:not([aria-hidden])').textContent.trim(); });
        expect(labels).toEqual(expect.arrayContaining(['Export chat', 'Show portraits', 'Fullscreen', 'LLM Debug', 'Clear history', 'Delete session']));
    });

    it('Clear history does not clear when the confirm is declined, and clears when accepted', async () => {
        await selectTestSession();
        mountToolbar();

        await openOverflow();
        root.querySelector('.context-menu [title="Clear history"]').click();
        await waitFor(function () { return !!root.querySelector('.am7-dialog-btn'); }, 'confirm dialog');
        expect(root.textContent).toContain('Clear all messages in');
        let cancel = Array.from(root.querySelectorAll('.am7-dialog-btn')).find(function (b) { return /Cancel$/.test(b.textContent.trim()); });
        cancel.click();
        await settle();
        expect(requests.some(function (r) { return /\/rest\/chat\/clear$/.test(r.url); }), 'declined confirm must not POST /rest/chat/clear').toBe(false);
        expect(chatOverflowItems().some(function (i) { return i.label === 'Export chat'; }), 'messages still present after decline').toBe(true);

        await openOverflow();
        root.querySelector('.context-menu [title="Clear history"]').click();
        await waitFor(function () { return !!root.querySelector('.am7-dialog-btn-destructive'); }, 'confirm dialog (2)');
        root.querySelector('.am7-dialog-btn-destructive').click();
        await waitFor(function () { return requests.some(function (r) { return /\/rest\/chat\/clear$/.test(r.url); }); }, 'POST /rest/chat/clear');
        let clearReq = requests.find(function (r) { return /\/rest\/chat\/clear$/.test(r.url); });
        expect(clearReq.method).toBe('POST');
        expect(clearReq.body.objectId).toBe('cr-1');
        expect(chatOverflowItems().some(function (i) { return i.label === 'Export chat'; }), 'history cleared after accept').toBe(false);
    });

    it('the LLM Debug item opens the drawer with this chat\'s model, configs and session-filtered server rows', async () => {
        await selectTestSession();
        mountToolbar();
        expect(root.querySelector('[data-testid="chat-llm-debug-drawer"]')).toBeNull();

        await openOverflow();
        root.querySelector('.context-menu [data-testid="chat-llm-debug"]').click();
        m.redraw.sync();
        let drawer = root.querySelector('[data-testid="chat-llm-debug-drawer"]');
        expect(drawer).not.toBeNull();
        await waitFor(function () { return /qwen3:8b/.test((drawer.querySelector('tbody') || { textContent: '' }).textContent); }, 'server rows');

        expect(drawer.querySelector('[data-session-field="model"]').textContent).toContain('qwen3:8b');
        expect(drawer.querySelector('[data-session-field="rating"]').textContent).toContain('E');
        expect(drawer.querySelector('[data-session-field="chatConfig"]').textContent).toContain('Test CC');
        expect(drawer.querySelector('[data-session-field="prompt"]').textContent).toContain('Test PT');
        let tables = drawer.textContent;
        expect(tables).toContain('sess-1');
        expect(tables).not.toContain('other-model');
        expect(tables).not.toContain('doc-9');
        // relocated summarization controls (from ContextPanel)
        expect(drawer.querySelector('[data-summary-ref="doc-1"]').textContent).toContain('summarizing 1/3');
        expect(drawer.querySelector('[data-summary-ref="doc-1"] button[title="Stop summarization"]')).not.toBeNull();
        expect(drawer.querySelector('[data-summary-ref="doc-2"] button[title="Re-summarize"]')).not.toBeNull();
        // relocated memory force-extract (from MemoryPanel)
        expect(drawer.querySelector('button[title="Extract memories from conversation"]')).not.toBeNull();

        // thoughts toggle moved here; flipping it changes the session info the chat renders with
        expect(sessionDebugInfo().hideThoughts).toBe(true);
        drawer.querySelector('button[title="Show thoughts"]').click();
        m.redraw.sync();
        expect(sessionDebugInfo().hideThoughts).toBe(false);
        expect(root.querySelector('button[title="Hide thoughts"]')).not.toBeNull();
        root.querySelector('button[title="Hide thoughts"]').click();
        m.redraw.sync();
        expect(sessionDebugInfo().hideThoughts).toBe(true);

        // Stop summarization posts the cancel for that object only
        drawer.querySelector('[data-summary-ref="doc-1"] button[title="Stop summarization"]').click();
        await settle();
        let cancel = requests.find(function (r) { return /\/rest\/chat\/summarize\/cancel$/.test(r.url); });
        expect(cancel.body).toEqual({ sessionId: 'cr-1', objectId: 'doc-1' });

        // MCP toggle wires the previously hard-wired debugMode=false
        expect(sessionDebugInfo().showMcp).toBe(false);
        root.querySelector('button[title="Show MCP context in chat"]').click();
        m.redraw.sync();
        expect(sessionDebugInfo().showMcp).toBe(true);
        root.querySelector('button[title="Hide MCP context in chat"]').click();

        // close the drawer again
        root.querySelector('button[title="Close LLM debug drawer"]').click();
        m.redraw.sync();
        expect(root.querySelector('[data-testid="chat-llm-debug-drawer"]')).toBeNull();
    });

    it('the chat reports the active session to the App Panel setter as a callback-free snapshot', async () => {
        await selectTestSession();
        LLMDebugPanel.setLastChatSession(sessionDebugInfo());
        let last = LLMDebugPanel.getLastChatSession();
        expect(last.sessionId).toBe('cr-1');
        expect(last.model).toBe('qwen3:8b');
        expect(Object.values(last).some(function (v) { return typeof v === 'function'; })).toBe(false);
        LLMDebugPanel.setLastChatSession(null);
        expect(LLMDebugPanel.getLastChatSession()).toBeNull();
    });
});

describe('LLMDebugPanel.SessionView', () => {
    beforeEach(() => { document.body.innerHTML = ''; stubNetwork(); });
    afterEach(async () => {
        if (root) { m.mount(root, null); root = null; }
        await settle();
        requestSpy.mockRestore();
        getFullSpy.mockRestore();
    });

    it('renders model info and only this session\'s server rows; no controls without callbacks', async () => {
        root = document.createElement('div');
        document.body.appendChild(root);
        m.mount(root, { view: function () {
            return m(LLMDebugPanel.SessionView, { info: { sessionId: 'cr-1', requestId: 'sess-1', name: 'Snap', model: 'qwen3:8b', contextType: 'olio.charPerson' } });
        } });
        await waitFor(function () { return /qwen3:8b/.test((root.querySelector('tbody') || { textContent: '' }).textContent); }, 'session rows');
        expect(requests.some(function (r) { return /\/rest\/chat\/llm\/active$/.test(r.url); })).toBe(true);
        let text = root.textContent;
        expect(root.querySelector('[data-session-field="contextType"]').textContent).toContain('olio.charPerson');
        expect(text).toContain('sess-1');
        expect(text).toContain('map 1/3');
        expect(text).not.toContain('someone-else');
        expect(text).not.toContain('cr-OTHER');
        expect(root.querySelector('[data-toggle]')).toBeNull();
        expect(root.querySelector('button[title="Extract memories from conversation"]')).toBeNull();
    });
});
