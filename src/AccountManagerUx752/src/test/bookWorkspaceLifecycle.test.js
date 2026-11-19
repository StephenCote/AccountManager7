/**
 * @vitest-environment jsdom
 *
 * Regression tests for the verifier's findings on the Read | Edit change (2026-11-19):
 *   1. no request loop when a workspace is MOUNTED and left to redraw (the unit suite used to render once only);
 *   2. an empty / already-loaded book is not fully reloaded on every ensureBook (that stopped watching the run);
 *   3. ChapBook: entering Read during an Edit render keeps its progress and keeps Render disabled on return;
 *   4. the leave guard: a route change that leaves the workspace with drafts asks first;
 *   5. leaving the workspace runs the section's remove(); a remount inside it does not;
 *   6. the bare /edit entry never redirects after the user navigated away.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { createBookWorkspace } from '../components/bookWorkspace.js';
import { registerDirty, __resetDirtyForTest, shouldAskOnLeave, setLeaveScope, installLeaveGuard, __setRouteSetForTest } from '../components/dirtyGuard.js';
import { Dialog } from '../components/dialogCore.js';

function jsonResponse(status, body) {
    let text = JSON.stringify(body);
    return { ok: status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
const PAGE = { objectId: 's1', sceneIndex: 1, title: 'Q', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', dataObjectId: 'd1' };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let origFetch;
let counts;
function countingFetch(pages) {
    counts = {};
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u).replace(/^.*\/rest\//, '/rest/');
        let k = ((init && init.method) || 'GET') + ' ' + url.replace(/\?.*$/, '');
        counts[k] = (counts[k] || 0) + 1;
        if (/\/B1\/pages$/.test(url)) return jsonResponse(200, pages);
        return jsonResponse(404, { error: 'x' });
    });
}
function hot(limit) { return Object.fromEntries(Object.entries(counts).filter(([, v]) => v > limit)); }

beforeEach(() => {
    origFetch = global.fetch;
    __resetDirtyForTest();
    vi.spyOn(page, 'toast').mockImplementation(() => {});
});
afterEach(() => { global.fetch = origFetch; vi.restoreAllMocks(); });

describe('1 — a mounted PictureBook workspace does not loop requests', () => {
    for (const section of ['book', 'style', 'scenes', 'render']) {
        it(section + ': request counts stop growing while the mounted workspace idles (no redraw → request loop)', async () => {
            countingFetch([PAGE]);
            const pbf = await import('../features/pictureBook.js');
            const edit = await import('../features/pictureBookEdit.js');
            pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'B1', pb2BookName: 'Q', pb2Pages: [PAGE], pb2LoadedBookId: 'B1' });
            edit.pbWorkspace.__resetForTest();
            let root = document.createElement('div');
            document.body.appendChild(root);
            m.mount(root, { view: () => m(edit.pbWorkspace.view, { bookObjectId: 'B1', section }) });
            await sleep(600);
            let early = Object.assign({}, counts);
            await sleep(900);
            m.mount(root, null);
            // Entry loads are bounded (a handful per endpoint); a loop keeps climbing — it was ~44 / s before the fix.
            let grew = Object.keys(counts).filter(k => counts[k] > (early[k] || 0));
            expect(grew, 'grew while idle: ' + JSON.stringify({ early, late: counts })).toEqual([]);
            expect(hot(5), JSON.stringify(counts)).toEqual({});
        });
    }
});

describe('2 — pb2EnsureBook on a book that is already loaded', () => {
    it('an EMPTY loaded book (no scene rows yet) is refreshed, not reloaded — the watched run survives', async () => {
        countingFetch([]);
        const pbf = await import('../features/pictureBook.js');
        let run = { jobId: 'j1', planned: [], job: null, label: 'Render all' };
        pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'B1', pb2Pages: [], pb2LoadedBookId: 'B1', pb2Run: run });
        pbf.pb2EnsureBook('B1');
        pbf.pb2EnsureBook('B1');
        await sleep(50);
        expect(pbf.__pb2V3StateForTest().pb2Run).toBe(run);
        // a refresh reads /pages; a full load would also read the book record (search) and /workflow
        expect(counts['GET /rest/olio/picture-book/B1/workflow'] || 0).toBe(0);
    });
    it('a book that is NOT the loaded one gets a full load', async () => {
        countingFetch([]);
        const pbf = await import('../features/pictureBook.js');
        pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'B1', pb2Pages: [], pb2LoadedBookId: null });
        pbf.pb2EnsureBook('B1');
        expect(pbf.__pb2V3StateForTest().pb2Run).toBe(null);
    });
});

describe('3 — ChapBook: Read during an Edit render', () => {
    it('re-entering Edit keeps Render disabled while the run is in flight for that book', async () => {
        const cb = await import('../features/chapBook.js');
        countingFetch([]);
        cb.__setEditStateForTest({ renderRunningFor: 'C1', editNeedsReload: true });
        cb.__ensureEditBookForTest('C1');
        expect(cb.__editStateForTest().reviewRendering).toBe(true);
        cb.__setEditStateForTest({ renderRunningFor: null, editNeedsReload: true });
        cb.__ensureEditBookForTest('C1');
        expect(cb.__editStateForTest().reviewRendering).toBe(false);
    });
});

describe('4 — leave guard', () => {
    it('shouldAskOnLeave: only when dirty and the target is outside the scope', () => {
        let scope = (r) => r.indexOf('/picture-book/B1/edit') === 0;
        expect(shouldAskOnLeave(scope, '/picture-book/B1/edit/style', true)).toBe(false);
        expect(shouldAskOnLeave(scope, '/picture-book/v2/B1', true)).toBe(true);
        expect(shouldAskOnLeave(scope, '/picture-book/v2/B1', false)).toBe(false);
        expect(shouldAskOnLeave(null, '/x', true)).toBe(false);
    });
    it('m.route.set out of the workspace with a draft asks first; Stay goes nowhere, Save goes on', async () => {
        let navigated = [];
        installLeaveGuard();
        __setRouteSetForTest(function (r) { navigated.push(r); });
        let opened = [];
        vi.spyOn(Dialog, 'open').mockImplementation(function (cfg) { opened.push(cfg); });
        vi.spyOn(Dialog, 'close').mockImplementation(function () { let c = opened.pop(); if (c && c.onClose) c.onClose(); });
        try {
            let saved = 0;
            let dirty = true;
            registerDirty('d', { label: 'Draft', isDirty: () => dirty, save: async () => { saved++; dirty = false; return true; } });
            setLeaveScope((r) => r.indexOf('/picture-book/B1/edit') === 0);
            // inside: passes straight through
            m.route.set('/picture-book/B1/edit/scenes');
            expect(navigated).toEqual(['/picture-book/B1/edit/scenes']);
            // leaving: asks, Stay → nothing
            m.route.set('/picture-book');
            await Promise.resolve();
            expect(opened.length).toBe(1);
            opened[0].actions.find(a => a.label === 'Stay').onclick();
            await sleep(0);
            expect(navigated).toEqual(['/picture-book/B1/edit/scenes']);
            // leaving again: Save → saved, then navigates
            m.route.set('/picture-book');
            await Promise.resolve();
            opened[opened.length - 1].actions.find(a => a.label === 'Save').onclick();
            await sleep(0);
            expect(saved).toBe(1);
            expect(navigated[navigated.length - 1]).toBe('/picture-book');
        } finally {
            setLeaveScope(null);
        }
    });
});

describe('5 / 6 — workspace exit and the entry route', () => {
    it('remove() runs when the route leaves the workspace, not on a remount inside it', async () => {
        let removed = [];
        let route = '/picture-book/B1/edit/style';
        vi.spyOn(m.route, 'get').mockImplementation(() => route);
        let ws = createBookWorkspace({ kind: 'pb', adapters: { style: { init: () => {}, remove: () => removed.push('style'), body: () => null }, book: { body: () => null } } });
        let root = document.createElement('div');
        m.render(root, m(ws.view, { bookObjectId: 'B1', section: 'style' }));
        m.render(root, null);           // unmount while still inside (an arity remount)
        await sleep(5);
        expect(removed).toEqual([]);
        m.render(root, m(ws.view, { bookObjectId: 'B1', section: 'style' }));
        route = '/picture-book/v2/B1';  // really left
        m.render(root, null);
        await sleep(5);
        expect(removed).toEqual(['style']);
    });
    it('the bare /edit entry does not redirect once the route has moved on', async () => {
        let route = '/picture-book/B1/edit';
        vi.spyOn(m.route, 'get').mockImplementation(() => route);
        let set = vi.spyOn(m.route, 'set').mockImplementation(() => {});
        let ready = false;
        let ws = createBookWorkspace({ kind: 'pb', statusesReady: () => ready, adapters: { book: { body: () => null }, scenes: { body: () => null } } });
        let entry = ws.routes('/picture-book/:bookObjectId/edit')['/picture-book/:bookObjectId/edit'];
        let root = document.createElement('div');
        m.render(root, entry.render({ attrs: { bookObjectId: 'B1' } }));
        route = '/picture-book';        // user navigated away while statuses load
        ready = true;
        await sleep(400);
        expect(set).not.toHaveBeenCalled();
        m.render(root, null);
    });
});

describe('Canvas in the workspace — the node detail panel is anchored to the canvas, not the viewport', () => {
    it('embedded: absolute inside a position:relative canvas body (cannot cover the section toolbar); never fixed', async () => {
        const wfm = await import('../features/pictureBookWorkflow.js');
        wfm.__setCanvasStateForTest({ graphData: { nodes: [{ objectId: 'n1', handle: 'scene@1', nodeType: 'render_plan', status: 'DONE' }], edges: [] }, selectedNodeId: 'n1', nodeDetails: null });
        let root = document.createElement('div');
        m.render(root, wfm.canvasSection.body({ kind: 'pb', bookObjectId: 'B1', section: 'canvas' }));
        let body = root.querySelector('[data-testid="pb-canvas-section"]');
        let panel = root.querySelector('[data-testid="pbwf-node-panel"]');
        expect(body.getAttribute('style')).toMatch(/position:\s*relative/);
        expect(panel, 'the selected node shows its panel').not.toBeNull();
        expect(panel.getAttribute('style')).toMatch(/position:\s*absolute/);
        expect(panel.getAttribute('style')).not.toMatch(/position:\s*fixed/);
        // below the dialog stack (dialogCore z-40+), so it never covers a modal's buttons
        expect(Number((panel.getAttribute('style').match(/z-index:\s*(\d+)/) || [])[1])).toBeLessThan(40);
        wfm.canvasSection.remove({});
        m.render(root, null);
    });
});
