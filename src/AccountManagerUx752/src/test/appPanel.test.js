/**
 * @vitest-environment jsdom
 *
 * App Panel (#!/app) — the per-user panel that replaced the flyout's Explorer / Passkeys /
 * Access Requests / Breadcrumb Bar entries and the chat toolbar's LLM Debug button.
 * Mounted through the REAL Mithril router so card navigation is exercised, not just vnode shape.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import m from 'mithril';
import { initFeatures, getMenuItems } from '../features.js';
import { page } from '../core/pageClient.js';
import '../components/breadcrumb.js'; // registers page.components.breadCrumb
import { appPanelView } from '../views/appPanel.js';
import { asideMenu } from '../components/asideMenu.js';

const EMPTY_STATUS = { summarizations: [], llmRequests: [], bufferModeStreams: 0, activeLLMCallCount: 0, activeLLMCalls: [] };

let root = null;
let requestSpy = null;

async function settle() {
    for (let i = 0; i < 8; i++) {
        await new Promise(function (r) { setTimeout(r, 0); });
    }
}

/** Mithril's route resolution + redraw are async (and jsdom fires popstate late); poll instead of guessing ticks. */
async function waitFor(pred, label) {
    for (let i = 0; i < 100; i++) {
        if (pred()) return;
        await new Promise(function (r) { setTimeout(r, 10); });
    }
    throw new Error('timed out waiting for ' + label + '; route=' + m.route.get() + ' html=' + (root ? root.innerHTML.substring(0, 300) : '<no root>'));
}

// One shared routes object: Mithril's router keeps the last resolved component at module level, so a
// re-mount with a fresh routes object renders the old component first and then swaps it for the new
// identity on resolution — re-running oninit/onremove on everything inside.
const ROUTES = {
    "/app": { view: function () { return m(appPanelView); } },
    "/explorer": { view: function () { return m("div", { class: "marker" }, "EXPLORER-VIEW"); } },
    "/webauthn": { view: function () { return m("div", { class: "marker" }, "PASSKEYS-VIEW"); } }
};

async function mountApp() {
    root = document.createElement('div');
    document.body.appendChild(root);
    m.route(root, "/app", ROUTES);
    await waitFor(function () { return m.route.get() === '/app' && !!root.querySelector('[data-toggle="breadcrumb"]'); }, 'App Panel to render');
    return root;
}

function stubStatus(payload) {
    requestSpy = vi.spyOn(m, 'request').mockImplementation(function (opts) {
        if (opts && /\/rest\/chat\/llm\/active$/.test(opts.url)) return Promise.resolve(payload);
        return Promise.resolve(null);
    });
}

describe('App Panel view', () => {

    beforeEach(() => {
        window.location.hash = '';
        document.body.innerHTML = '';
        stubStatus(EMPTY_STATUS);
    });

    afterEach(async () => {
        if (root) { m.mount(root, null); root = null; }
        await settle();
        if (requestSpy) { requestSpy.mockRestore(); requestSpy = null; }
        if (page.components.breadCrumb && !page.components.breadCrumb.isVisible()) page.components.breadCrumb.toggleBreadcrumb();
        document.body.innerHTML = '';
    });

    it('renders header, Explorer card and a card per enabled app-section feature', async () => {
        initFeatures(['core', 'webauthn', 'accessRequests']);
        await mountApp();
        expect(root.textContent).toContain('App Panel');
        expect(root.querySelector('[data-tool="/explorer"]')).not.toBeNull();
        let passkeys = root.querySelector('[data-tool="/webauthn"]');
        expect(passkeys).not.toBeNull();
        expect(passkeys.textContent).toContain('Passkeys');
        expect(passkeys.textContent).toContain('WebAuthn/FIDO2 passwordless authentication');
        let ar = root.querySelector('[data-tool="/accessRequests"]');
        expect(ar).not.toBeNull();
        expect(ar.textContent).toContain('Access Requests');
        expect(ar.textContent).toContain('Self-service access request and approval workflow');
    });

    it('omits cards for disabled features but always offers Explorer', async () => {
        initFeatures(['core']);
        await mountApp();
        expect(root.querySelector('[data-tool="/explorer"]')).not.toBeNull();
        expect(root.querySelector('[data-tool="/webauthn"]')).toBeNull();
        expect(root.querySelector('[data-tool="/accessRequests"]')).toBeNull();
        expect(getMenuItems('app')).toEqual([]);
    });

    it('clicking a tool card routes to the tool', async () => {
        initFeatures(['core', 'webauthn']);
        await mountApp();
        root.querySelector('[data-tool="/webauthn"]').click();
        await waitFor(function () { return m.route.get() === '/webauthn' && /PASSKEYS-VIEW/.test(root.textContent); }, 'route to /webauthn');
        expect(m.route.get()).toBe('/webauthn');
        expect(root.textContent).toContain('PASSKEYS-VIEW');
    });

    it('the Breadcrumb Bar switch flips page.components.breadCrumb and its active state', async () => {
        initFeatures(['core']);
        await mountApp();
        let bc = page.components.breadCrumb;
        expect(bc.isVisible()).toBe(true);
        let toggle = root.querySelector('[data-toggle="breadcrumb"]');
        expect(toggle).not.toBeNull();
        expect(toggle.className).toContain('active');
        toggle.click();
        await waitFor(function () { return !/active/.test(root.querySelector('[data-toggle="breadcrumb"]').className); }, 'toggle to redraw inactive');
        expect(bc.isVisible()).toBe(false);
        root.querySelector('[data-toggle="breadcrumb"]').click();
        await waitFor(function () { return /active/.test(root.querySelector('[data-toggle="breadcrumb"]').className); }, 'toggle to redraw active');
        expect(bc.isVisible()).toBe(true);
    });

    it('LLM Debug polls /rest/chat/llm/active on mount and reports an idle server', async () => {
        initFeatures(['core']);
        await mountApp();
        await waitFor(function () { return /No active requests/.test(root.querySelector('[data-llm-debug]').textContent); }, 'idle status to render');
        let calls = requestSpy.mock.calls.filter(function (c) { return /\/rest\/chat\/llm\/active$/.test(c[0].url); });
        expect(calls.length).toBeGreaterThanOrEqual(1);
        expect(calls[0][0].method).toBe('GET');
    });

    it('LLM Debug renders the server payload: requests, stalled active calls, summarizations', async () => {
        requestSpy.mockRestore();
        stubStatus({
            summarizations: [{ objectId: 'obj-1234567890abcdef', sessionId: 'sess-1', phase: 'summarizing', current: 2, total: 5, elapsed: 12, cancelled: false }],
            llmRequests: [{ requestId: 'req-1234567890abcdef', model: 'qwen3:8b-jos-ctr', tokenCount: 42, serviceType: 'chat', stopped: false }],
            bufferModeStreams: 1,
            activeLLMCallCount: 2,
            activeLLMCalls: [
                { id: 'call-a', kind: 'chat', startMs: 1000, ageMs: 45000 },
                { id: 'call-b', kind: 'embed:note', startMs: 2000, ageMs: 800 }
            ]
        });
        initFeatures(['core']);
        await mountApp();
        await waitFor(function () { return /LLM Requests \(1\)/.test(root.querySelector('[data-llm-debug]').textContent); }, 'status payload to render');
        let dbg = root.querySelector('[data-llm-debug]');
        expect(dbg.textContent).toContain('LLM Requests (1)');
        expect(dbg.textContent).toContain('qwen3:8b-jos-ctr');
        expect(dbg.textContent).toContain('Active Calls (2)');
        expect(dbg.textContent).toContain('Buffer-mode streams: 1');
        expect(dbg.textContent).toContain('Summarizations (1)');
        expect(dbg.textContent).toContain('summarizing 2/5 (12s)');
        // Stalled (>30s) call is flagged red; the 800ms one is not.
        let ageCells = Array.from(dbg.querySelectorAll('td')).filter(function (td) { return /45\.0s|800ms/.test(td.textContent); });
        let stalled = ageCells.find(function (td) { return td.textContent === '45.0s'; });
        let fresh = ageCells.find(function (td) { return td.textContent === '800ms'; });
        expect(stalled.className).toContain('text-red-600');
        expect(fresh.className).not.toContain('text-red-600');
        expect(dbg.querySelector('button[title="Abort all"]')).not.toBeNull();
        expect(dbg.querySelector('button[title="Cancel"]')).not.toBeNull();
    });

    it('LLM Debug ignores a fetch that resolves after the panel was unmounted', async () => {
        requestSpy.mockRestore();
        // Every status fetch stays pending until the test resolves it, so timing is under test control.
        let pending = [];
        requestSpy = vi.spyOn(m, 'request').mockImplementation(function (opts) {
            if (opts && /\/rest\/chat\/llm\/active$/.test(opts.url)) {
                return new Promise(function (r) { pending.push(r); });
            }
            return Promise.resolve(null);
        });
        initFeatures(['core']);
        await mountApp();
        expect(pending.length).toBe(1);
        expect(root.querySelector('[data-llm-debug]').textContent).toContain('Loading');
        m.mount(root, null);
        root = null;
        await settle();
        // The first response arrives after unmount, carrying rows that must never be shown later.
        pending[0]({ summarizations: [], llmRequests: [{ requestId: 'stale-req-0000000000', model: 'stale-model', tokenCount: 1, serviceType: 'chat', stopped: false }], bufferModeStreams: 0, activeLLMCallCount: 0, activeLLMCalls: [] });
        await settle();
        window.location.hash = '';
        await mountApp();
        expect(pending.length).toBe(2);
        let text = root.querySelector('[data-llm-debug]').textContent;
        expect(text).toContain('Loading');
        expect(text).not.toContain('stale-model');
        pending[1](EMPTY_STATUS);
        await waitFor(function () { return /No active requests/.test(root.querySelector('[data-llm-debug]').textContent); }, 'fresh idle status');
    });

    it('LLM Debug stops polling when the panel is unmounted', async () => {
        initFeatures(['core']);
        await mountApp();
        m.mount(root, null);
        root = null;
        await settle();
        let before = requestSpy.mock.calls.length;
        await new Promise(function (r) { setTimeout(r, 2300); });
        expect(requestSpy.mock.calls.length).toBe(before);
    }, 10000);
});

describe('Flyout (asideMenu) after the App Panel move', () => {

    beforeEach(() => {
        document.body.innerHTML = '';
        initFeatures(['core', 'webauthn', 'accessRequests', 'pictureBook']);
    });

    afterEach(() => {
        document.body.innerHTML = '';
    });

    function renderAside() {
        let div = document.createElement('div');
        document.body.appendChild(div);
        m.render(div, asideMenu.view());
        return div;
    }

    function buttonLabels(div) {
        return Array.from(div.querySelectorAll('button')).map(function (b) { return b.textContent.replace(/\s+/g, ' ').trim(); });
    }

    it('offers "App Panel" and no longer lists Explorer, Passkeys, Access Requests or Breadcrumb Bar', () => {
        let div = renderAside();
        let labels = buttonLabels(div);
        expect(labels.some(function (l) { return /App Panel/.test(l); })).toBe(true);
        expect(labels.some(function (l) { return /Explorer/.test(l); })).toBe(false);
        expect(labels.some(function (l) { return /Passkeys/.test(l); })).toBe(false);
        expect(labels.some(function (l) { return /Access Requests/.test(l); })).toBe(false);
        expect(labels.some(function (l) { return /Breadcrumb Bar/.test(l); })).toBe(false);
        expect(div.textContent).not.toContain('Display');
        expect(div.textContent).not.toContain('Browse');
    });

    it('still lists aside-section features and the System actions', () => {
        let div = renderAside();
        let labels = buttonLabels(div);
        expect(labels.some(function (l) { return /Picture Book/.test(l); })).toBe(true);
        expect(labels.some(function (l) { return /Clear Cache/.test(l); })).toBe(true);
        expect(labels.some(function (l) { return /Cleanup/.test(l); })).toBe(true);
    });
});
