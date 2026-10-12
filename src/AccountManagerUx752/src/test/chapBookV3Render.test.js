// @vitest-environment jsdom
/**
 * ChapBook V3 per-page render (P1-10 S8) — features/chapBook.js.
 *
 * What changed, and what these tests drive through the REAL exported functions with a stubbed fetch:
 *   - renderChapBookScene: a 202 { pending:true, jobId, nodeObjectId } is polled at GET /rest/job/{id}
 *     to the terminal job; the page outcome is read off result.nodes[] and the image objectId off a fresh
 *     by-objectId scene search (the outcome carries the artifact, not the image). A 200 keeps the v1 shape.
 *   - the one-shot sdConfig overlay (book save failed) is persisted as scene.configOverride BEFORE a v3
 *     render, merged UNDER the scene's existing override (scene wins), never on v1 / never when empty.
 *   - a pinned refusal (facade 409 "Node X is pinned …", or a job outcome SKIPPED_PINNED) is classified
 *     by isChapBookPinnedRefusal; renderPageWithPinConfirm shows the server's text and only an explicit
 *     "Unpin and re-render" confirmation sends POST …/node/{n}/pin {pinned:false}; the bulk path never
 *     unpins and reports "N left pinned".
 * No network, LLM or SD is touched; am7client.getRenderPath, Dialog.confirm and page.toast are stubbed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

const PINNED_MSG = 'Node scene-3 is pinned. Unpin it first - pinning means do not replace this artifact.';
const NO_PROMPT_MSG = 'Page 3 has no prompt; set or generate its landscape prompt before rendering it';

function v1Body(extra) {
    return Object.assign({ imageObjectId: 'img-v1', rendered: true, skipped: false, pending: false, llmUnavailable: false, llmDegraded: false,
        renderPath: 'v1', jobId: null, runObjectId: null, nodeObjectId: null, artifactObjectId: null, nodeStatus: null, reviewStatus: null,
        autoApproved: false, autoApproveSkipped: null }, extra || {});
}
function pendingBody(extra) {
    return Object.assign({ imageObjectId: null, rendered: false, skipped: false, pending: true, llmUnavailable: false, llmDegraded: false,
        renderPath: 'v3', jobId: 'j1', runObjectId: 'r1', nodeObjectId: 'n3', artifactObjectId: null, nodeStatus: null, reviewStatus: null,
        autoApproved: false, autoApproveSkipped: null }, extra || {});
}
function doneJob(nodes, extra) {
    return Object.assign({ jobId: 'j1', kind: 'pb.render', status: 'completed', phase: 'scene-3', current: 1, total: 1, terminal: true,
        result: { runObjectId: 'r1', runStatus: 'COMPLETED', executedNodeCount: 1, failedNodeCount: 0, skippedNodeCount: 0, nodes: nodes } }, extra || {});
}

/**
 * fetch router for the per-page wire. opts.generate(sceneOid, nth) answers the generate POST; opts.job(jobId, nth)
 * the poll; opts.scene(objectId) the by-objectId scene search row; opts.pages the book page list.
 */
function mockWire(opts) {
    let o = opts || {};
    let calls = [];
    let polls = {};
    let gens = {};
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u);
        let method = (init && init.method) || 'GET';
        let body = (init && init.body) ? JSON.parse(init.body) : null;
        calls.push({ url: url.replace(/^.*\/rest\//, '/rest/'), method, body });
        let mg = /\/rest\/olio\/chap-book\/scene\/([^/]+)\/generate$/.exec(url);
        if (mg) {
            gens[mg[1]] = (gens[mg[1]] || 0) + 1;
            return o.generate ? o.generate(mg[1], gens[mg[1]], body) : jsonResponse(200, v1Body());
        }
        let mj = /\/rest\/job\/([^/]+)$/.exec(url);
        if (mj) {
            polls[mj[1]] = (polls[mj[1]] || 0) + 1;
            return o.job ? o.job(mj[1], polls[mj[1]]) : jsonResponse(404, { error: 'Job not found' });
        }
        if (/\/rest\/model\/search$/.test(url)) {
            let oid = body && body.fields && body.fields[0] ? body.fields[0].value : null;
            let row = o.scene ? o.scene(oid) : null;
            return jsonResponse(200, row ? [row] : []);
        }
        let mo = /\/rest\/olio\/picture-book\/scene\/([^/]+)\/config-override$/.exec(url);
        if (mo) return jsonResponse(200, { sceneObjectId: mo[1], configOverride: body.configOverride });
        let mp = /\/rest\/olio\/picture-book\/([^/]+)\/node\/([^/]+)\/pin$/.exec(url);
        if (mp) return jsonResponse(200, { nodeObjectId: mp[2], pinned: !!body.pinned });
        if (/\/rest\/olio\/picture-book\/([^/]+)\/pages$/.test(url)) return jsonResponse(200, o.pages || []);
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
    return calls;
}
function seqOf(calls) { return calls.map(c => c.method + ' ' + c.url); }
function only(calls, re) { return calls.filter(c => re.test(c.url)); }

let cb;
let origFetch;

beforeEach(async () => {
    origFetch = global.fetch;
    cb = await import('../features/chapBook.js');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'getRenderPath').mockResolvedValue({ picturebook: 'v1', chapbook: 'v3' });
});
afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── pure helpers ───────────────────────────────

describe('coerceSceneRenderResult — v1 shape unless the server sent renderPath', () => {
    it('no renderPath → exactly the five v1 keys (older bodies / v1 fixtures)', () => {
        expect(cb.coerceSceneRenderResult({})).toEqual({ imageObjectId: null, rendered: false, skipped: false, llmUnavailable: false, llmDegraded: false });
        expect(Object.keys(cb.coerceSceneRenderResult({ imageObjectId: 'i', rendered: true }))).toHaveLength(5);
    });
    it('renderPath present → v3 keys attached, enum-ish strings upper-cased', () => {
        let r = cb.coerceSceneRenderResult(pendingBody({ nodeStatus: 'running', reviewStatus: 'generated' }));
        expect(r).toMatchObject({ renderPath: 'v3', pending: true, jobId: 'j1', runObjectId: 'r1', nodeObjectId: 'n3', artifactObjectId: null,
            nodeStatus: 'RUNNING', reviewStatus: 'GENERATED', rendered: false, imageObjectId: null });
        expect(cb.coerceSceneRenderResult(v1Body()).renderPath).toBe('v1');
        expect(cb.coerceSceneRenderResult(v1Body()).pending).toBe(false);
    });
});

describe('v3JobOutcome — mirrors ChapBookUtil.v3PageResult', () => {
    it('completed job + DONE_UNVERIFIED node + artifact → ok (job status is lowercase on the wire)', () => {
        let o = cb.v3JobOutcome(doneJob([{ nodeObjectId: 'n3', handle: 'scene-3', status: 'DONE_UNVERIFIED', artifactObjectId: 'a9', autoApproved: false, reviewStatus: 'GENERATED' }]), 'n3');
        expect(o).toMatchObject({ ok: true, pinnedSkipped: false, nodeStatus: 'DONE_UNVERIFIED', artifactObjectId: 'a9', reviewStatus: 'GENERATED', error: null });
        expect(cb.v3JobOutcome(doneJob([{ nodeObjectId: 'n3', status: 'DONE', artifactObjectId: 'a9' }]), 'n3').ok).toBe(true);
    });
    it('DONE without an artifact, or a failed job, is not ok and carries the node error first', () => {
        expect(cb.v3JobOutcome(doneJob([{ nodeObjectId: 'n3', status: 'DONE' }]), 'n3').ok).toBe(false);
        let o = cb.v3JobOutcome(doneJob([{ nodeObjectId: 'n3', status: 'FAILED', error: 'SD returned no image' }], { status: 'failed', error: '1 node(s) failed: scene-3' }), 'n3');
        expect(o.ok).toBe(false);
        expect(o.error).toBe('SD returned no image');
        let c = cb.v3JobOutcome({ status: 'cancelled', terminal: true, error: 'Cancelled after 0 of 1 stage(s)', result: { nodes: [{ nodeObjectId: 'n3', status: 'CANCELLED' }] } }, 'n3');
        expect(c.error).toBe('Cancelled after 0 of 1 stage(s)');
        expect(cb.v3JobOutcome(doneJob([]), 'n3').error).toMatch(/job completed, node unknown/);
    });
    it('SKIPPED_PINNED → pinnedSkipped with a "is pinned" message; a lone entry is accepted when the id is unknown', () => {
        let o = cb.v3JobOutcome(doneJob([{ nodeObjectId: 'n3', handle: 'scene-3', status: 'SKIPPED_PINNED' }]), 'n3');
        expect(o.pinnedSkipped).toBe(true);
        expect(o.error).toMatch(/scene-3 is pinned/);
        expect(cb.v3JobOutcome(doneJob([{ nodeObjectId: 'nX', status: 'DONE', artifactObjectId: 'a' }]), null).ok).toBe(true);
        expect(cb.v3JobOutcome(doneJob([{ nodeObjectId: 'nX', status: 'DONE', artifactObjectId: 'a' }, { nodeObjectId: 'nY', status: 'DONE' }]), null).ok).toBe(false);
        expect(cb.v3JobOutcome(null, 'n3').ok).toBe(false);
    });
});

describe('isChapBookPinnedRefusal — the facade 409 shape, not the un-prompted 409', () => {
    it('matches only a 409 carrying "is pinned" (or err.pinned from a job outcome)', () => {
        let pinned = Object.assign(new Error('Scene render failed: ' + PINNED_MSG), { status: 409, error: PINNED_MSG, body: JSON.stringify({ error: PINNED_MSG }) });
        expect(cb.isChapBookPinnedRefusal(pinned)).toBe(true);
        let noPrompt = Object.assign(new Error('Scene render failed: ' + NO_PROMPT_MSG), { status: 409, error: NO_PROMPT_MSG });
        expect(cb.isChapBookPinnedRefusal(noPrompt)).toBe(false);
        expect(cb.isChapBookPinnedRefusal(Object.assign(new Error('x is pinned'), { status: 500 }))).toBe(false);
        expect(cb.isChapBookPinnedRefusal(Object.assign(new Error('Scene render failed: node is pinned'), { pinned: true }))).toBe(true);
        expect(cb.isChapBookPinnedRefusal(null)).toBe(false);
        expect(cb.isChapBookPinnedRefusal(new Error('Scene render failed: 500'))).toBe(false);
    });
});

describe('mergeSceneOverlay — sparse, scene override wins, schema key re-added', () => {
    it('overlay alone; existing override on top; identity/schema keys dropped; nothing → null', () => {
        expect(JSON.parse(cb.mergeSceneOverlay({ steps: 20, schema: 'olio.sd.config', id: 5, groupId: 7 }, null)))
            .toEqual({ steps: 20, schema: 'olio.sd.config' });
        let merged = JSON.parse(cb.mergeSceneOverlay({ steps: 20, cfgScale: 4, sampler: 'euler' }, JSON.stringify({ schema: 'olio.sd.config', steps: 30, objectId: 'x' })));
        expect(merged).toEqual({ steps: 30, cfgScale: 4, sampler: 'euler', schema: 'olio.sd.config' });
        expect(cb.mergeSceneOverlay({ steps: null, schema: 'olio.sd.config' }, '')).toBeNull();
        expect(cb.mergeSceneOverlay({}, 'not json')).toBeNull();
        expect(cb.mergeSceneOverlay(null, null)).toBeNull();
    });
});

describe('renderResultMessage / renderResultLevel — pages left pinned', () => {
    it('adds the "left pinned" clause and warns; absent/zero keeps the original wording', () => {
        expect(cb.renderResultMessage({ rendered: 2, failed: 0, skipped: 0, llmUnavailable: 0, total: 3, pinnedLeft: 1 }))
            .toBe('Render complete: 2 generated, 1 left pinned (unpin from the page\'s Re-render to replace)');
        expect(cb.renderResultMessage({ rendered: 1, failed: 1, skipped: 1, llmUnavailable: 0, total: 4, pinnedLeft: 1 }))
            .toBe('Render complete: 1 generated, 1 skipped (need prompts), 1 failed, 1 left pinned (unpin from the page\'s Re-render to replace)');
        expect(cb.renderResultMessage({ rendered: 3, failed: 0, skipped: 0, llmUnavailable: 0, total: 3 })).toBe('Render complete: 3 scene(s) generated');
        expect(cb.renderResultLevel({ rendered: 2, pinnedLeft: 1 })).toBe('warn');
        expect(cb.renderResultLevel({ rendered: 2, pinnedLeft: 0 })).toBe('success');
        expect(cb.renderResultLevel({ rendered: 0, pinnedLeft: 1, llmUnavailable: 1 })).toBe('error');
    });
});

describe('chapBookRenderPath — the chapbook key of GET /rest/config/render-path', () => {
    it("'v3' only when chapbook is v3; picturebook alone, null, odd shapes and failures are 'v1'", async () => {
        expect(await cb.chapBookRenderPath()).toBe('v3');
        am7client.getRenderPath.mockResolvedValue({ picturebook: 'v3', chapbook: 'v1' });
        expect(await cb.chapBookRenderPath()).toBe('v1');
        am7client.getRenderPath.mockResolvedValue({ chapbook: 'V3' });
        expect(await cb.chapBookRenderPath()).toBe('v3');
        am7client.getRenderPath.mockResolvedValue(null);
        expect(await cb.chapBookRenderPath()).toBe('v1');
        am7client.getRenderPath.mockRejectedValue(new Error('down'));
        expect(await cb.chapBookRenderPath()).toBe('v1');
    });
});

// ─────────────────────────────── renderChapBookScene ───────────────────────────────

describe('renderChapBookScene — 200 keeps the v1 contract', () => {
    it('v1 200 → rendered + image; v3 keys present but no poll, no scene read, no override write', async () => {
        let calls = mockWire({ generate: () => jsonResponse(200, v1Body()) });
        let r = await cb.renderChapBookScene('s3', 'cfg', { steps: 20 }, null, { renderPath: 'v1' });
        expect(r).toMatchObject({ imageObjectId: 'img-v1', rendered: true, skipped: false, llmUnavailable: false, llmDegraded: false, renderPath: 'v1', pending: false });
        expect(seqOf(calls)).toEqual(['POST /rest/olio/chap-book/scene/s3/generate']);
        expect(calls[0].body.sdConfig).toEqual({ steps: 20 });
        expect(r.overridePersisted).toBeUndefined();
    });
    it('a v3 200 (job finished inside the server bound) is taken as-is', async () => {
        mockWire({ generate: () => jsonResponse(200, pendingBody({ pending: false, rendered: true, imageObjectId: 'img-3', artifactObjectId: 'a9', nodeStatus: 'DONE_UNVERIFIED', reviewStatus: 'GENERATED' })) });
        let r = await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3' });
        expect(r).toMatchObject({ rendered: true, imageObjectId: 'img-3', renderPath: 'v3', pending: false, nodeStatus: 'DONE_UNVERIFIED', reviewStatus: 'GENERATED', artifactObjectId: 'a9' });
        expect(global.fetch).toHaveBeenCalledTimes(1);
    });
    it('non-2xx → "Scene render failed: <server error>" with status/error/body on the Error (json-only stubs too)', async () => {
        mockWire({ generate: () => jsonResponse(500, { error: 'boom' }) });
        let err = await cb.renderChapBookScene('s3', null, {}).catch(e => e);
        expect(err.message).toBe('Scene render failed: boom');
        expect(err.status).toBe(500);
        expect(err.error).toBe('boom');
        expect(err.body).toBe('{"error":"boom"}');
        global.fetch = vi.fn(async () => ({ ok: false, status: 503, json: async () => ({ error: 'no sd' }) }));
        let e2 = await cb.renderChapBookScene('s3', null, {}).catch(e => e);
        expect(e2.message).toBe('Scene render failed: no sd');
        expect(e2.status).toBe(503);
    });
});

describe('renderChapBookScene — 202 pending → poll GET /rest/job/{jobId} → scene read-back', () => {
    it('polls to the terminal job, reads the image off a fresh cache:false scene search, returns rendered', async () => {
        let calls = mockWire({
            generate: () => jsonResponse(202, pendingBody()),
            job: (id, nth) => nth < 3
                ? jsonResponse(200, { jobId: id, status: 'running', phase: 'scene-3', current: 0, total: 1, terminal: false })
                : jsonResponse(200, doneJob([{ nodeObjectId: 'n3', handle: 'scene-3', status: 'DONE_UNVERIFIED', artifactObjectId: 'a9', autoApproved: false, reviewStatus: 'GENERATED' }])),
            scene: (oid) => ({ id: 31, objectId: oid, imageObjectId: 'img-new', imageStale: false, configOverride: null })
        });
        let progress = [];
        let r = await cb.renderChapBookScene('s3', 'cfg', {}, null, { renderPath: 'v3', pollIntervalMs: 1, onJobProgress: j => progress.push(j.status) });
        expect(r).toMatchObject({ rendered: true, skipped: false, pending: false, imageObjectId: 'img-new', jobId: 'j1', nodeObjectId: 'n3',
            artifactObjectId: 'a9', nodeStatus: 'DONE_UNVERIFIED', reviewStatus: 'GENERATED', renderPath: 'v3' });
        expect(progress).toEqual(['running', 'running', 'completed']);
        expect(seqOf(calls)).toEqual([
            'POST /rest/olio/chap-book/scene/s3/generate',
            'GET /rest/job/j1', 'GET /rest/job/j1', 'GET /rest/job/j1',
            'POST /rest/model/search'
        ]);
        let search = calls[4].body;
        expect(search).toMatchObject({ schema: 'io.query', type: 'olio.pb.scene', cache: false, recordCount: 1 });
        expect(search.fields).toEqual([{ name: 'objectId', comparator: 'EQUALS', value: 's3' }]);
        expect(search.request).toEqual(expect.arrayContaining(['imageObjectId', 'configOverride', 'imageStale']));
    });
    it('a 200 body that says pending:true is polled too (status is not the only signal)', async () => {
        mockWire({
            generate: () => ({ ok: true, json: async () => pendingBody() }),
            job: () => jsonResponse(200, doneJob([{ nodeObjectId: 'n3', status: 'DONE', artifactObjectId: 'a9' }])),
            scene: (oid) => ({ objectId: oid, imageObjectId: 'img-new' })
        });
        let r = await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3', pollIntervalMs: 1 });
        expect(r.rendered).toBe(true);
        expect(r.imageObjectId).toBe('img-new');
    });
    it('a failed job → rendered:false with the node error; no scene read; llmDegraded cleared', async () => {
        let calls = mockWire({
            generate: () => jsonResponse(202, pendingBody({ llmDegraded: true, llmUnavailable: true })),
            job: () => jsonResponse(200, doneJob([{ nodeObjectId: 'n3', handle: 'scene-3', status: 'FAILED', error: 'SD returned no image' }], { status: 'failed', error: '1 node(s) failed: scene-3' }))
        });
        let r = await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3', pollIntervalMs: 1 });
        expect(r).toMatchObject({ rendered: false, skipped: false, pending: false, imageObjectId: null, nodeStatus: 'FAILED', error: 'SD returned no image', llmUnavailable: true, llmDegraded: false });
        expect(only(calls, /\/rest\/model\/search$/)).toHaveLength(0);
    });
    it('a job whose node was SKIPPED_PINNED throws a pinned refusal (never unpins)', async () => {
        let calls = mockWire({
            generate: () => jsonResponse(202, pendingBody()),
            job: () => jsonResponse(200, doneJob([{ nodeObjectId: 'n3', handle: 'scene-3', status: 'SKIPPED_PINNED' }], { result: { nodes: [{ nodeObjectId: 'n3', handle: 'scene-3', status: 'SKIPPED_PINNED' }], skippedNodeCount: 1 } }))
        });
        let err = await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3', pollIntervalMs: 1 }).catch(e => e);
        expect(err).toBeInstanceOf(Error);
        expect(err.pinned).toBe(true);
        expect(cb.isChapBookPinnedRefusal(err)).toBe(true);
        expect(only(calls, /\/pin$/)).toHaveLength(0);
    });
    it('the image is still rendered when the read-back finds nothing — imageObjectId null, never a throw', async () => {
        mockWire({
            generate: () => jsonResponse(202, pendingBody()),
            job: () => jsonResponse(200, doneJob([{ nodeObjectId: 'n3', status: 'DONE_UNVERIFIED', artifactObjectId: 'a9' }])),
            scene: () => null
        });
        let r = await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3', pollIntervalMs: 1 });
        expect(r.rendered).toBe(true);
        expect(r.imageObjectId).toBeNull();
    });
    it('pending without a jobId is an error, not a hang', async () => {
        mockWire({ generate: () => jsonResponse(202, pendingBody({ jobId: null })) });
        await expect(cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3' })).rejects.toThrow(/pending but the server sent no jobId/);
    });
});

describe('renderChapBookScene — one-shot sdConfig overlay persisted as scene.configOverride before a v3 render', () => {
    const OVERLAY = { steps: 20, cfgScale: 4, schema: 'olio.sd.config' };
    it('v3 + overlay + known scene override → PUT config-override (scene wins) BEFORE the generate POST; result.overridePersisted', async () => {
        let calls = mockWire({ generate: () => jsonResponse(200, pendingBody({ pending: false, rendered: true, imageObjectId: 'img-3', artifactObjectId: 'a9', nodeStatus: 'DONE_UNVERIFIED' })) });
        let existing = JSON.stringify({ schema: 'olio.sd.config', steps: 30 });
        let r = await cb.renderChapBookScene('s3', null, OVERLAY, null, { renderPath: 'v3', sceneConfigOverride: existing });
        expect(seqOf(calls)).toEqual(['PUT /rest/olio/picture-book/scene/s3/config-override', 'POST /rest/olio/chap-book/scene/s3/generate']);
        let persisted = JSON.parse(calls[0].body.configOverride);
        expect(persisted).toEqual({ steps: 30, cfgScale: 4, schema: 'olio.sd.config' });
        expect(JSON.parse(r.overridePersisted)).toEqual(persisted);
        expect(calls[1].body.sdConfig).toEqual(OVERLAY);   // still on the wire (harmless on v3, used on v1)
    });
    it('v3 + overlay + unknown (null) scene override → the existing override is READ first, then merged under', async () => {
        let calls = mockWire({
            generate: () => jsonResponse(200, pendingBody({ pending: false, rendered: true, imageObjectId: 'img-3' })),
            scene: (oid) => ({ objectId: oid, imageObjectId: 'img-old', configOverride: JSON.stringify({ schema: 'olio.sd.config', sampler: 'dpm' }) })
        });
        await cb.renderChapBookScene('s3', null, OVERLAY, null, { renderPath: 'v3', sceneConfigOverride: null });
        expect(seqOf(calls)).toEqual(['POST /rest/model/search', 'PUT /rest/olio/picture-book/scene/s3/config-override', 'POST /rest/olio/chap-book/scene/s3/generate']);
        expect(JSON.parse(calls[1].body.configOverride)).toEqual({ steps: 20, cfgScale: 4, sampler: 'dpm', schema: 'olio.sd.config' });
    });
    it('NOT persisted on v1, nor on v3 with an empty overlay, nor without a renderPath', async () => {
        let calls = mockWire({ generate: () => jsonResponse(200, v1Body()) });
        await cb.renderChapBookScene('s3', null, OVERLAY, null, { renderPath: 'v1', sceneConfigOverride: null });
        await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3', sceneConfigOverride: null });
        await cb.renderChapBookScene('s3', null, OVERLAY, null);
        expect(only(calls, /config-override$/)).toHaveLength(0);
        expect(only(calls, /\/rest\/model\/search$/)).toHaveLength(0);
        expect(only(calls, /\/generate$/)).toHaveLength(3);
    });
    it('a failed override PUT aborts the render (the dialog values would otherwise be silently ignored on v3)', async () => {
        let calls = mockWire({ generate: () => jsonResponse(200, v1Body()) });
        let real = global.fetch;
        global.fetch = vi.fn(async (u, init) => /config-override$/.test(String(u)) ? jsonResponse(403, { error: 'denied' }) : real(u, init));
        await expect(cb.renderChapBookScene('s3', null, OVERLAY, null, { renderPath: 'v3', sceneConfigOverride: '{"schema":"olio.sd.config","steps":1}' }))
            .rejects.toThrow(/Save override failed: 403/);
        expect(only(calls, /\/generate$/)).toHaveLength(0);
    });
});

describe('renderChapBookScene — the pinned 409', () => {
    it('rejects with the server body on the Error; isChapBookPinnedRefusal classifies it; the un-prompted 409 is not pinned', async () => {
        mockWire({ generate: () => jsonResponse(409, { error: PINNED_MSG }) });
        let err = await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3' }).catch(e => e);
        expect(err.message).toBe('Scene render failed: ' + PINNED_MSG);
        expect(err.status).toBe(409);
        expect(err.error).toBe(PINNED_MSG);
        expect(cb.isChapBookPinnedRefusal(err)).toBe(true);
        mockWire({ generate: () => jsonResponse(409, { error: NO_PROMPT_MSG }) });
        let e2 = await cb.renderChapBookScene('s3', null, {}, null, { renderPath: 'v3' }).catch(e => e);
        expect(e2.status).toBe(409);
        expect(cb.isChapBookPinnedRefusal(e2)).toBe(false);
    });
});

// ─────────────────────────────── per-page confirm ───────────────────────────────

describe('renderPageWithPinConfirm — the only path that unpins is the explicit confirmation', () => {
    function pinnedErr() {
        return Object.assign(new Error('Scene render failed: ' + PINNED_MSG), { status: 409, error: PINNED_MSG, body: JSON.stringify({ error: PINNED_MSG }) });
    }
    it('accepted → server text in the dialog, POST …/node/{n}/pin {pinned:false}, then the render runs again', async () => {
        let calls = mockWire({});
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let scene = { objectId: 's3', sceneIndex: 3, sceneNodeObjectId: 'n3', pinned: false };
        let run = vi.fn().mockRejectedValueOnce(pinnedErr()).mockResolvedValueOnce({ rendered: true, imageObjectId: 'img-2' });
        let r = await cb.renderPageWithPinConfirm(scene, 'book-1', run);
        expect(r).toEqual({ rendered: true, imageObjectId: 'img-2' });
        expect(run).toHaveBeenCalledTimes(2);
        expect(confirm).toHaveBeenCalledTimes(1);
        let cfg = confirm.mock.calls[0][0];
        expect(cfg.title).toBe('Page 3 is pinned');
        expect(cfg.message).toContain(PINNED_MSG);
        expect(cfg.confirmLabel).toBe('Unpin and re-render');
        expect(seqOf(calls)).toEqual(['POST /rest/olio/picture-book/book-1/node/n3/pin']);
        expect(calls[0].body).toEqual({ pinned: false });
        expect(scene.pinned).toBe(false);
    });
    it('declined → no pin call, no second render, null result, "left pinned" toast; the scene is marked pinned', async () => {
        let calls = mockWire({});
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        let scene = { objectId: 's3', sceneIndex: 3, sceneNodeObjectId: 'n3', pinned: false };
        let run = vi.fn().mockRejectedValue(pinnedErr());
        let r = await cb.renderPageWithPinConfirm(scene, 'book-1', run);
        expect(r).toBeNull();
        expect(run).toHaveBeenCalledTimes(1);
        expect(calls).toHaveLength(0);
        expect(scene.pinned).toBe(true);
        expect(page.toast).toHaveBeenCalledWith('info', 'Page 3 left pinned');
    });
    it('no node on the scene → the page list is read to find it; none there → error toast, no dialog', async () => {
        let calls = mockWire({ pages: [{ objectId: 's1', sceneIndex: 1, pinned: false }, { objectId: 's3', sceneIndex: 3, sceneNodeObjectId: 'n3', nodeStatus: 'DONE', pinned: true }] });
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let scene = { objectId: 's3', sceneIndex: 3 };
        let run = vi.fn().mockRejectedValueOnce(pinnedErr()).mockResolvedValueOnce({ rendered: true, imageObjectId: 'img-2' });
        let r = await cb.renderPageWithPinConfirm(scene, 'book-1', run);
        expect(r.rendered).toBe(true);
        expect(scene.sceneNodeObjectId).toBe('n3');
        expect(seqOf(calls)).toEqual(['GET /rest/olio/picture-book/book-1/pages', 'POST /rest/olio/picture-book/book-1/node/n3/pin']);
        // no node anywhere → cannot unpin from here
        confirm.mockClear();
        let calls2 = mockWire({ pages: [{ objectId: 's3', sceneIndex: 3, pinned: true }] });
        let scene2 = { objectId: 's3', sceneIndex: 3 };
        let r2 = await cb.renderPageWithPinConfirm(scene2, 'book-1', vi.fn().mockRejectedValue(pinnedErr()));
        expect(r2).toBeNull();
        expect(confirm).not.toHaveBeenCalled();
        expect(only(calls2, /\/pin$/)).toHaveLength(0);
        expect(page.toast).toHaveBeenCalledWith('error', expect.stringMatching(/node could not be found/));
    });
    it('any other error propagates untouched — no dialog, no pin call', async () => {
        let calls = mockWire({});
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let run = vi.fn().mockRejectedValue(Object.assign(new Error('Scene render failed: boom'), { status: 500 }));
        await expect(cb.renderPageWithPinConfirm({ objectId: 's3', sceneNodeObjectId: 'n3' }, 'book-1', run)).rejects.toThrow(/boom/);
        expect(confirm).not.toHaveBeenCalled();
        expect(calls).toHaveLength(0);
    });
    it('end to end through the real generate call: 409 → confirm → unpin → 202 → poll → read-back', async () => {
        let calls = mockWire({
            generate: (oid, nth) => nth === 1 ? jsonResponse(409, { error: PINNED_MSG }) : jsonResponse(202, pendingBody()),
            job: () => jsonResponse(200, doneJob([{ nodeObjectId: 'n3', status: 'DONE_UNVERIFIED', artifactObjectId: 'a9', reviewStatus: 'GENERATED' }])),
            scene: (oid) => ({ objectId: oid, imageObjectId: 'img-new' })
        });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let scene = { objectId: 's3', sceneIndex: 3, sceneNodeObjectId: 'n3', configOverride: null, pinned: true };
        let r = await cb.renderPageWithPinConfirm(scene, 'book-1', () => cb.renderChapBookScene('s3', null, {}, 'a verbatim prompt', { renderPath: 'v3', pollIntervalMs: 1, sceneConfigOverride: scene.configOverride }));
        expect(r).toMatchObject({ rendered: true, imageObjectId: 'img-new', reviewStatus: 'GENERATED' });
        expect(seqOf(calls)).toEqual([
            'POST /rest/olio/chap-book/scene/s3/generate',
            'POST /rest/olio/picture-book/book-1/node/n3/pin',
            'POST /rest/olio/chap-book/scene/s3/generate',
            'GET /rest/job/j1',
            'POST /rest/model/search'
        ]);
        expect(calls[2].body.sdPrompt).toBe('a verbatim prompt');
        expect(scene.pinned).toBe(false);
    });
});

describe('applyPageRenderResult — mirror onto the review card', () => {
    it('takes the image only when present and mirrors a persisted overlay as the scene override', () => {
        let scene = { objectId: 's3', imageObjectId: 'img-old', configOverride: null, _dirty: new Set(['override']) };
        cb.applyPageRenderResult(scene, { rendered: true, imageObjectId: null });
        expect(scene.imageObjectId).toBe('img-old');
        cb.applyPageRenderResult(scene, { rendered: true, imageObjectId: 'img-new', overridePersisted: '{"schema":"olio.sd.config","steps":9}' });
        expect(scene.imageObjectId).toBe('img-new');
        expect(scene.configOverride).toBe('{"schema":"olio.sd.config","steps":9}');
        expect(scene._dirty.has('override')).toBe(false);
        cb.applyPageRenderResult(null, { rendered: true });
    });
});

// ─────────────────────────────── bulk path ───────────────────────────────

describe('renderScenesSerially — a pinned result is neither rendered, skipped nor failed', () => {
    it('counts pinnedLeft (only when > 0), reports progress "pinned", keeps the 6-key shape otherwise', async () => {
        let progress = [];
        let gen = async (oid) => oid === 'p' ? { rendered: false, skipped: false, pinned: true, imageObjectId: null } : { rendered: true, imageObjectId: 'i-' + oid };
        let r = await cb.renderScenesSerially(['a', 'p', 'b'], gen, { sleep: async () => {}, onProgress: (oid, st) => progress.push(oid + ':' + st) });
        expect(r).toEqual({ rendered: 2, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 3, pinnedLeft: 1 });
        expect(progress).toEqual(['a:generating', 'a:done', 'p:generating', 'p:pinned', 'b:generating', 'b:done']);
        let r2 = await cb.renderScenesSerially(['a'], gen, { sleep: async () => {} });
        expect(r2).toEqual({ rendered: 1, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1 });
    });
});

describe('renderChapBookScenes — v3 bulk never unpins and never asks', () => {
    it('a page pinned in the page list is not submitted; a server pinned 409 is folded to "left pinned"; no dialog, no pin call', async () => {
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let calls = mockWire({
            pages: [{ objectId: 's2', sceneIndex: 2, sceneNodeObjectId: 'n2', nodeStatus: 'DONE', pinned: true }],
            generate: () => jsonResponse(200, v1Body())
        });
        let r = await cb.renderChapBookScenes('book-1', 'cfg', {});
        expect(r).toEqual({ rendered: 0, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1, pinnedLeft: 1 });
        expect(only(calls, /\/generate$/)).toHaveLength(0);
        expect(am7client.getRenderPath).toHaveBeenCalledTimes(1);

        let calls2 = mockWire({
            pages: [{ objectId: 's3', sceneIndex: 3, sceneNodeObjectId: 'n3', nodeStatus: 'DONE', pinned: false }],
            generate: () => jsonResponse(409, { error: PINNED_MSG })
        });
        let r2 = await cb.renderChapBookScenes('book-1', 'cfg', {});
        expect(r2).toEqual({ rendered: 0, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1, pinnedLeft: 1 });
        expect(only(calls2, /\/generate$/)).toHaveLength(1);
        expect(only(calls2, /\/pin$/)).toHaveLength(0);
        expect(confirm).not.toHaveBeenCalled();
        expect(cb.renderResultLevel(r2)).toBe('warn');
    });
    it('a v3 page the server finished inside its bound renders normally; other errors still count as failed', async () => {
        let calls = mockWire({
            pages: [{ objectId: 's1', sceneIndex: 1, sceneNodeObjectId: 'n1', nodeStatus: 'READY', pinned: false }],
            generate: () => jsonResponse(200, pendingBody({ pending: false, rendered: true, imageObjectId: 'img-1', nodeObjectId: 'n1', artifactObjectId: 'a1', nodeStatus: 'DONE_UNVERIFIED' }))
        });
        let r = await cb.renderChapBookScenes('book-1', null, {});
        expect(r).toEqual({ rendered: 1, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1 });
        expect(only(calls, /\/generate$/)).toHaveLength(1);
        mockWire({ pages: [{ objectId: 's1', sceneIndex: 1, pinned: false }], generate: () => jsonResponse(500, { error: 'boom' }) });
        let r2 = await cb.renderChapBookScenes('book-1', null, {});
        expect(r2).toEqual({ rendered: 0, failed: 1, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1 });
    });
    it('on v1 the page-list pin flag is ignored (there is nothing to pin) and the generate call is made', async () => {
        am7client.getRenderPath.mockResolvedValue({ picturebook: 'v1', chapbook: 'v1' });
        let calls = mockWire({ pages: [{ objectId: 's2', sceneIndex: 2, pinned: true }], generate: () => jsonResponse(200, v1Body()) });
        let r = await cb.renderChapBookScenes('book-1', null, {});
        expect(r).toEqual({ rendered: 1, failed: 0, skipped: 0, llmUnavailable: 0, llmDegraded: 0, total: 1 });
        expect(only(calls, /\/generate$/)).toHaveLength(1);
        expect(only(calls, /config-override$/)).toHaveLength(0);
    });
});
