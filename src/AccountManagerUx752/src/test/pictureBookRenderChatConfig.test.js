// @vitest-environment jsdom
/**
 * 226 a (ledger §24 I-2): the chat config a render run is opened with travels from the reader to the three render
 * routes as {chatConfigName} and comes back on the submit / run DTOs as chatConfig {objectId, name}.
 *
 * Why: the visual-QA hook (220 e) reads the run's chatConfig FK, so a render submitted from the reader must name
 * the config the user picked (or the carried / library default) exactly as the compose board does, while a reader
 * with no config keeps every request body-less (the pre-226 a contract the existing reader tests pin).
 *
 * Covers: renderBody (pure), normalizeRun / the submit normalizer (chatConfig null ↔ ref), the three workflow
 * helpers' wire shape (query flag + JSON body only when a name is set), the reader's seeding (carried config,
 * else the library default, a pick wins), its four submit sites, the header control and the run-progress chip.
 * The live counterpart is e2e/pictureBookRenderChatConfig.spec.js (PB_LLM_TESTS=1), which asserts the run's
 * chatConfig through GET /run/{id} against the Docker stack.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as wf from '../workflows/pictureBookWorkflow.js';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { LLMConnector } from '../chat/LLMConnector.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function flushPromises() { return new Promise(r => setTimeout(r, 0)); }
function restPath(u) { return String(u).replace(/^.*\/rest\//, '/rest/'); }
function findByTestId(vnode, testid) {
    let found = null;
    (function walk(v) {
        if (!v || found) return;
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.attrs && v.attrs['data-testid'] === testid) { found = v; return; }
        walk(v.children);
    })(vnode);
    return found;
}
function textOf(v) {
    if (v == null) return '';
    if (typeof v === 'string' || typeof v === 'number') return String(v);
    if (Array.isArray(v)) return v.map(textOf).join('');
    if (v.tag === '#') return String(v.children);
    return textOf(v.children);
}

const CFG = { name: 'pbux-render-cfg', objectId: 'cc-1' };
const SCENE = { objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', pinned: false, dataObjectId: 'd1' };
const PLAN = {
    planVersion: 'illustration/1', purpose: 'SCENE', canvas: { width: 1216, height: 832 },
    preset: { preset: 'STANDARD', steps: 25, variants: 2, upscale: false, qa: 'CANONICAL' },
    style: { styleObjectId: 'st-1', revisionHash: 'abc123', description: 'ink wash', photoreal: false },
    location: { locationId: 'loc-1', description: 'a harbour at dusk' }, intent: { raw: false },
    strategy: { name: 'TEXT_ONLY', stages: ['BASE'], reason: 'no reference images bound' },
    models: { location: { task: 'location', reason: 'INVENTORY_UNAVAILABLE', degraded: false } },
    compiled: { BASE: { prompt: 'a harbour at dusk, ink wash', estimatedTokens: '16' } },
    degradations: [], configNotApplied: null,
    estimate: { scenes: 1, stages: 1, variants: 2, steps: 25, basis: 'NONE', basisText: 'no measurement for this server/family yet' }
};
const DONE = { jobId: 'J1', kind: 'pb.render', status: 'done', current: 1, total: 1, terminal: true,
    result: { runObjectId: 'R1', runStatus: 'DONE', executedNodeCount: 1, failedNodeCount: 0, plannedNodeCount: 1,
        nodes: [{ nodeObjectId: 'n1', handle: 'scene-1', status: 'DONE', artifactObjectId: 'a1' }] } };

/** Records every fetch as {method, url, body (parsed), contentType}; the submit routes answer 202 with chatConfig echoed. */
function mockFetch(overrides) {
    let o = overrides || {};
    let calls = [];
    global.fetch = vi.fn(async (u, init) => {
        let url = restPath(u);
        let method = (init && init.method) || 'GET';
        let headers = (init && init.headers) || {};
        calls.push({ method, url, body: init && init.body !== undefined ? JSON.parse(init.body) : undefined, contentType: headers['Content-Type'] });
        let echo = calls[calls.length - 1].body && calls[calls.length - 1].body.chatConfigName
            ? { chatConfig: { objectId: 'cc-1', name: calls[calls.length - 1].body.chatConfigName } } : {};
        if (/\/rest\/job\/J1$/.test(url)) return jsonResponse(200, DONE);
        if (/\/scene\/s1\/render(\?force=true)?$/.test(url)) return o.sceneRender ? o.sceneRender(calls) : jsonResponse(202, Object.assign({ jobId: 'J1', runObjectId: 'R1', runStatus: 'RUNNING', planned: ['scene-1'] }, echo));
        if (/\/render-all$/.test(url)) return jsonResponse(202, Object.assign({ jobId: 'J1', runObjectId: 'R1', runStatus: 'RUNNING', planned: ['scene-1'], sceneCount: 1, scenesWithoutNode: [] }, echo));
        if (/\/scene\/s1\/render-plan$/.test(url)) return jsonResponse(200, Object.assign({ artifactObjectId: 'art-9', nodeObjectId: 'n1' }, echo));
        if (/\/scene\/s1\/plan$/.test(url)) return jsonResponse(200, PLAN);
        if (/\/node\/n1\/pin$/.test(url)) return jsonResponse(200, { nodeObjectId: 'n1', pinned: false });
        if (/\/pages$/.test(url)) return jsonResponse(200, [SCENE]);
        if (/\/health$/.test(url)) return jsonResponse(404, { error: 'none' });
        if (/\/covers$/.test(url)) return jsonResponse(200, { bookObjectId: 'B1', covers: {} });
        if (/\/workflow\/node\/n1$/.test(url)) return jsonResponse(200, { artifacts: {} });
        return jsonResponse(404, { error: 'unexpected ' + method + ' ' + url });
    });
    return calls;
}

// ─────────────────────────────── pure: renderBody + normalizers ───────────────────────────────

describe('renderBody — the optional {chatConfigName} body of the render routes', () => {
    it('is undefined without a usable name (no opts, blank, non-string), so the request stays body-less', () => {
        expect(wf.renderBody()).toBeUndefined();
        expect(wf.renderBody(null)).toBeUndefined();
        expect(wf.renderBody({ force: true })).toBeUndefined();
        expect(wf.renderBody({ chatConfigName: '   ' })).toBeUndefined();
        expect(wf.renderBody({ chatConfigName: 42 })).toBeUndefined();
    });
    it('carries only the trimmed name (force stays a query flag, never a body key)', () => {
        expect(wf.renderBody({ force: true, chatConfigName: '  cfgA  ' })).toEqual({ chatConfigName: 'cfgA' });
    });
});

describe('normalizeRun — chatConfig {objectId, name} or null (NON_EMPTY drops it when the run names none)', () => {
    it('null when absent / not an object; the ref when present', () => {
        expect(wf.normalizeRun({ runObjectId: 'R1' }).chatConfig).toBeNull();
        expect(wf.normalizeRun({ chatConfig: 'x' }).chatConfig).toBeNull();
        expect(wf.normalizeRun({ chatConfig: { objectId: 'cc-1', name: 'cfgA' } }).chatConfig).toEqual({ objectId: 'cc-1', name: 'cfgA' });
        expect(wf.normalizeRun({ chatConfig: { name: 'cfgA' } }).chatConfig).toEqual({ objectId: null, name: 'cfgA' });
    });
});

// ─────────────────────────────── workflow helpers: wire shape ───────────────────────────────

describe('renderScene / renderAll / renderPlanScene send {chatConfigName} only when named', () => {
    let origFetch;
    beforeEach(() => { origFetch = global.fetch; });
    afterEach(() => { global.fetch = origFetch; vi.restoreAllMocks(); });

    it('renderScene: ?force=true plus a JSON body with the name; the submit carries chatConfig', async () => {
        let calls = mockFetch();
        let submit = await wf.renderScene('B1', 's1', { force: true, chatConfigName: CFG.name });
        expect(calls[0].method + ' ' + calls[0].url).toBe('POST /rest/olio/picture-book/B1/scene/s1/render?force=true');
        expect(calls[0].body).toEqual({ chatConfigName: CFG.name });
        expect(calls[0].contentType).toBe('application/json');
        expect(submit.chatConfig).toEqual({ objectId: 'cc-1', name: CFG.name });
    });
    it('renderScene without a name: no body, no content type, chatConfig null', async () => {
        let calls = mockFetch();
        let submit = await wf.renderScene('B1', 's1', { force: true });
        expect(calls[0].url).toBe('/rest/olio/picture-book/B1/scene/s1/render?force=true');
        expect(calls[0].body).toBeUndefined();
        expect(calls[0].contentType).toBeUndefined();
        expect(submit.chatConfig).toBeNull();
    });
    it('renderAll: body only with a name', async () => {
        let calls = mockFetch();
        await wf.renderAll('B1');
        await wf.renderAll('B1', { chatConfigName: ' cfgB ' });
        expect(calls.map(c => c.method + ' ' + c.url)).toEqual(['POST /rest/olio/picture-book/B1/render-all', 'POST /rest/olio/picture-book/B1/render-all']);
        expect(calls[0].body).toBeUndefined();
        expect(calls[1].body).toEqual({ chatConfigName: 'cfgB' });
    });
    it('renderPlanScene: body only with a name; the response is returned as-is (chatConfig echoed by the server)', async () => {
        let calls = mockFetch();
        let plain = await wf.renderPlanScene('B1', 's1');
        let named = await wf.renderPlanScene('B1', 's1', { chatConfigName: CFG.name });
        expect(calls[0].url).toBe('/rest/olio/picture-book/B1/scene/s1/render-plan');
        expect(calls[0].body).toBeUndefined();
        expect(calls[1].body).toEqual({ chatConfigName: CFG.name });
        expect(plain.chatConfig).toBeUndefined();
        expect(named.chatConfig).toEqual({ objectId: 'cc-1', name: CFG.name });
    });
    it('a 404 "Chat config not found" is surfaced verbatim with its status', async () => {
        mockFetch({ sceneRender: () => jsonResponse(404, { error: 'Chat config not found: nope' }) });
        let err = null;
        try { await wf.renderScene('B1', 's1', { force: true, chatConfigName: 'nope' }); } catch (e) { err = e; }
        expect(err && err.message).toBe('(404) Chat config not found: nope');
        expect(err.status).toBe(404);
    });
});

// ─────────────────────────────── reader: seeding, submits, control, chip ───────────────────────────────

describe('reader: the picked / carried / default chat config reaches every render submit', () => {
    let origFetch;
    let pbf;

    beforeEach(async () => {
        origFetch = global.fetch;
        pbf = await import('../features/pictureBook.js');
        pbf.__pb2StopWatchingForTest();
        pbf.__setPb2V3StateForTest({
            pb2AutoApprove: false, pb2AutoApproveSaving: false, pb2Run: null, pb2ResumeRun: null, pb2Submitting: false,
            pb2CurrentPage: 1, pb2BookObjectId: 'B1', pb2BookName: 'Harlot\'s Eight', pb2PollIntervalMs: 5,
            pb2Pages: [SCENE], pb2ChatConfig: null, pb2ChatConfigResolving: false
        });
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        vi.spyOn(page, 'toast').mockImplementation(() => {});
    });
    afterEach(() => {
        pbf.__pb2StopWatchingForTest();
        pbf.__setPb2V3StateForTest({ pb2Run: null, pb2ResumeRun: null, pb2ChatConfig: null, pb2ChatConfigResolving: false });
        global.fetch = origFetch;
        vi.restoreAllMocks();
    });

    async function waitForTerminal() {
        let until = Date.now() + 3000;
        for (;;) {
            let st = pbf.__pb2V3StateForTest();
            if (st.pb2Run && st.pb2Run.job && st.pb2Run.job.terminal) return st;
            if (Date.now() > until) throw new Error('run did not reach terminal: ' + JSON.stringify(st.pb2Run));
            await flushPromises();
        }
    }

    it('no config: Render / Render all stay body-less (the pre-226 a contract) and the progress panel shows no chip', async () => {
        let calls = mockFetch();
        await pbf.__pb2SubmitRenderSceneForTest(SCENE);
        await waitForTerminal();
        expect(calls[0].url).toBe('/rest/olio/picture-book/B1/scene/s1/render?force=true');
        expect(calls[0].body).toBeUndefined();
        expect(pbf.__pb2V3StateForTest().pb2Run.chatConfig).toBeNull();
        expect(findByTestId(pbf.renderPb2RunProgress(), 'pb2-run-chat-config')).toBeNull();
    });

    it('a picked config: Render (force) sends {chatConfigName}; the run carries the echoed ref and the progress panel shows it', async () => {
        pbf.__setPb2V3StateForTest({ pb2ChatConfig: CFG });
        let calls = mockFetch();
        await pbf.__pb2SubmitRenderSceneForTest(SCENE);
        let st = await waitForTerminal();
        expect(calls[0].method + ' ' + calls[0].url).toBe('POST /rest/olio/picture-book/B1/scene/s1/render?force=true');
        expect(calls[0].body).toEqual({ chatConfigName: CFG.name });
        expect(st.pb2Run.chatConfig).toEqual({ objectId: 'cc-1', name: CFG.name });
        expect(textOf(findByTestId(pbf.renderPb2RunProgress(), 'pb2-run-chat-config'))).toBe(CFG.name);
    });

    it('a picked config: the unpin-and-re-render re-submit also names it', async () => {
        pbf.__setPb2V3StateForTest({ pb2ChatConfig: CFG });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let attempt = 0;
        let calls = mockFetch({ sceneRender: (c) => {
            attempt++;
            if (attempt === 1) return jsonResponse(409, { error: 'Nothing to render: every node of scene 1 is current (0 reused, 1 pinned)' });
            return jsonResponse(202, { jobId: 'J1', runObjectId: 'R1', runStatus: 'RUNNING', planned: ['scene-1'], chatConfig: { objectId: 'cc-1', name: c[c.length - 1].body.chatConfigName } });
        } });
        await pbf.__pb2SubmitRenderSceneForTest(SCENE);
        await waitForTerminal();
        expect(calls.slice(0, 3).map(c => c.method + ' ' + c.url)).toEqual([
            'POST /rest/olio/picture-book/B1/scene/s1/render?force=true',
            'POST /rest/olio/picture-book/B1/node/n1/pin',
            'POST /rest/olio/picture-book/B1/scene/s1/render?force=true'
        ]);
        expect(calls[0].body).toEqual({ chatConfigName: CFG.name });
        expect(calls[2].body).toEqual({ chatConfigName: CFG.name });
    });

    it('a picked config: Render all sends {chatConfigName} and the run carries it', async () => {
        pbf.__setPb2V3StateForTest({ pb2ChatConfig: CFG });
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        let calls = mockFetch();
        await pbf.__pb2SubmitRenderAllForTest();
        let st = await waitForTerminal();
        expect(calls[0].method + ' ' + calls[0].url).toBe('POST /rest/olio/picture-book/B1/render-all');
        expect(calls[0].body).toEqual({ chatConfigName: CFG.name });
        expect(st.pb2Run.chatConfig).toEqual({ objectId: 'cc-1', name: CFG.name });
    });

    it('a picked config: Plan & generate → POST /render-plan with {chatConfigName}; /plan itself is unchanged', async () => {
        pbf.__setPb2V3StateForTest({ pb2ChatConfig: CFG });
        pbf.__setPb2PlanStateForTest({ bookId: null, sceneOid: null, plan: null, error: null, loading: false, rendering: false, open: false, inspect: false, preset: '' });
        let calls = mockFetch();
        let openSpy = vi.spyOn(Dialog, 'open');
        findByTestId(pbf.renderPb2ScenePage(), 'pb2-plan-render').attrs.onclick();
        await flushPromises();
        let cfg = openSpy.mock.calls[0][0];
        await cfg.actions.view()[1].onclick();
        expect(calls[0].method + ' ' + calls[0].url).toBe('POST /rest/olio/picture-book/B1/scene/s1/plan');
        expect(calls[0].body).toEqual({});
        expect(calls[1].method + ' ' + calls[1].url).toBe('POST /rest/olio/picture-book/B1/scene/s1/render-plan');
        expect(calls[1].body).toEqual({ chatConfigName: CFG.name });
        expect(page.toast).toHaveBeenCalledWith('success', 'Scene rendered (artifact art-9)');
    });

    it('header control: "(click to select)" without a config, the name with one; onPick replaces the ref', () => {
        let ctl = findByTestId(pbf.renderPb2Header(), 'pb2-chat-config');
        expect(ctl).not.toBeNull();
        expect(textOf(ctl)).toContain('(click to select)');
        pbf.__setPb2V3StateForTest({ pb2ChatConfig: CFG });
        expect(textOf(findByTestId(pbf.renderPb2Header(), 'pb2-chat-config'))).toContain(CFG.name);
        pbf.__setPb2V3StateForTest({ pb2ChatConfig: null, pb2ChatConfigResolving: true });
        expect(textOf(findByTestId(pbf.renderPb2Header(), 'pb2-chat-config'))).toContain('Resolving default…');
    });

    it('opening a book seeds the carried config (a ChapBook) else the library default (contentAnalysis first), and never overwrites a pick', async () => {
        // The reader's open path: pb2BookObjectId is set by the route, then loadPb2Pages(id) runs the seeding.
        let resolve = vi.spyOn(LLMConnector, 'resolveConfig').mockImplementation(async (name) => name === 'contentAnalysis' ? { name: 'contentAnalysis', objectId: 'cc-ca' } : null);
        let unregister = wf.registerBookChatConfigProvider(oid => oid === 'CB1' ? { name: 'cb-carried', objectId: 'cc-cb' } : null);
        try {
            // loadPb2Pages reads the book/world context through am7client.search (XHR), not fetch.
            vi.spyOn(am7client, 'newQuery').mockImplementation(() => ({ entity: { request: [] }, cache() {}, field() {}, range() {} }));
            vi.spyOn(am7client, 'search').mockResolvedValue({ results: [] });
            global.fetch = vi.fn(async (u) => {
                let url = restPath(u);
                if (/\/pages$/.test(url)) return jsonResponse(200, [SCENE]);
                if (/\/scenes/.test(url)) return jsonResponse(200, []);
                if (/\/health$/.test(url)) return jsonResponse(404, { error: 'none' });
                if (/\/covers$/.test(url)) return jsonResponse(200, { bookObjectId: 'B1', covers: {} });
                if (/\/workflow$/.test(url)) return jsonResponse(404, { error: 'none' });
                if (/\/settings\/auto-approve$/.test(url)) return jsonResponse(200, { autoApprove: false });
                return jsonResponse(404, { error: 'unexpected ' + url });
            });
            // A PictureBook: nothing carried → the library default.
            pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'B1', pb2ChatConfig: null });
            pbf.routes['/picture-book/v2/:pb2BookObjectId'].oninit({ attrs: { pb2BookObjectId: 'B1' } });
            await flushPromises(); await flushPromises(); await flushPromises();
            expect(pbf.__pb2V3StateForTest().pb2ChatConfig).toEqual({ name: 'contentAnalysis', objectId: 'cc-ca' });
            expect(resolve).toHaveBeenCalledWith('contentAnalysis');
            // A ChapBook: the carried config wins and the library is not asked.
            resolve.mockClear();
            pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'CB1' });
            pbf.routes['/picture-book/v2/:pb2BookObjectId'].oninit({ attrs: { pb2BookObjectId: 'CB1' } });
            await flushPromises(); await flushPromises();
            expect(pbf.__pb2V3StateForTest().pb2ChatConfig).toEqual({ name: 'cb-carried', objectId: 'cc-cb' });
            expect(resolve).not.toHaveBeenCalled();
        } finally {
            unregister();
            pbf.__pb2StopWatchingForTest();
        }
    });
});
