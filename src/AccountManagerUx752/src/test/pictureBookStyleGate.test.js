// @vitest-environment jsdom
/**
 * PictureBook — the book style is a required predicate of rendering.
 *
 * Why (Stephen, 2026-11-16): after extraction the user landed in an unfinished book whose first clickable icon
 * was "render all scenes", with nothing saying the book style was required and the composition board optional.
 * The server now refuses scene render / render-plan / regenerate-background / render-all with HTTP 409 when the
 * book's effective style has no description; the UI gates the same controls up front, says why, links to the
 * style view, and shows the 409 verbatim if it still happens. Unknown (the style could not be read) never
 * blocks — the server is the final gate.
 *
 * Covered: renderGate / styleStepFinishState / isStyleRequiredRefusal (pure), the reader toolbar grouping
 * (reading · setup · Render · Tools overflow · right), the overflow items, the per-scene gate, the render and
 * plan dialogs' hard block, the 409 → verbatim toast + lock, and contextMenu's attrs / disabled items.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import * as wf from '../workflows/pictureBookWorkflow.js';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { contextMenu } from '../components/contextMenu.js';

const STYLE_409 = "Set the book style before rendering: the book's style has no description (open Book style).";

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function flush() { return new Promise(r => setTimeout(r, 0)); }

function findDeep(v, testid) {
    if (!v || typeof v !== 'object') return null;
    if (Array.isArray(v)) { for (let c of v) { let r = findDeep(c, testid); if (r) return r; } return null; }
    if (v.attrs && v.attrs['data-testid'] === testid) return v;
    return findDeep(v.children, testid);
}
function findByAttr(v, name, value) {
    if (!v || typeof v !== 'object') return null;
    if (Array.isArray(v)) { for (let c of v) { let r = findByAttr(c, name, value); if (r) return r; } return null; }
    if (v.attrs && v.attrs[name] === value) return v;
    return findByAttr(v.children, name, value);
}
function textOf(v) {
    if (v == null || v === false) return '';
    if (typeof v === 'string' || typeof v === 'number') return String(v);
    if (Array.isArray(v)) return v.map(textOf).join('');
    if (v.text != null) return String(v.text);
    return textOf(v.children);
}

// ─────────────────────────────── pure predicates ───────────────────────────────

describe('renderGate — may a scene render start?', () => {
    it('no style description blocks with the reason, ahead of every other reason', () => {
        expect(wf.renderGate({ styleDescribed: false })).toEqual({ enabled: false, reason: 'Set the book style first', needsStyle: true });
        expect(wf.renderGate({ styleDescribed: false, watching: true, submitting: true }).reason).toBe('Set the book style first');
    });
    it('unknown (null / undefined) never blocks — the server is the final gate', () => {
        expect(wf.renderGate({ styleDescribed: null })).toEqual({ enabled: true, reason: null, needsStyle: false });
        expect(wf.renderGate({})).toEqual({ enabled: true, reason: null, needsStyle: false });
        expect(wf.renderGate(null).enabled).toBe(true);
    });
    it('a watched run or an in-flight submit blocks with its own reason', () => {
        expect(wf.renderGate({ styleDescribed: true, watching: true })).toEqual({ enabled: false, reason: 'A render is already running', needsStyle: false });
        expect(wf.renderGate({ styleDescribed: true, submitting: true }).enabled).toBe(false);
        expect(wf.renderGate({ styleDescribed: true })).toEqual({ enabled: true, reason: null, needsStyle: false });
    });
});

describe('styleHasDescription / styleStepFinishState — the wizard step-4 gate', () => {
    const described = wf.normalizeStyle({ effective: { fields: { description: 'ink and watercolor' } } });
    const blank = wf.normalizeStyle({ effective: { fields: { description: '   ' } } });
    const none = wf.normalizeStyle({ effective: { fields: {} } });
    it('styleHasDescription reads effective.fields.description, blank is none', () => {
        expect(wf.styleHasDescription(described)).toBe(true);
        expect(wf.styleHasDescription(blank)).toBe(false);
        expect(wf.styleHasDescription(none)).toBe(false);
        expect(wf.styleHasDescription(null)).toBe(false);
    });
    it('enabled only on a SAVED description; unsaved edits, loading, saving and read errors each say why', () => {
        expect(wf.styleStepFinishState({ style: described, draft: {} })).toEqual({ enabled: true, reason: null });
        expect(wf.styleStepFinishState({ style: none, draft: {} })).toEqual({ enabled: false, reason: 'Choose a style preset or write a description, then Save' });
        expect(wf.styleStepFinishState({ style: none, draft: { description: 'x' } })).toEqual({ enabled: false, reason: 'Save the style to continue' });
        expect(wf.styleStepFinishState({ style: null, loading: true }).reason).toBe('Loading the book style…');
        expect(wf.styleStepFinishState({ style: null, error: '(404) Book not found' }).reason).toBe('The book style could not be read: (404) Book not found');
        expect(wf.styleStepFinishState({ style: described, saving: true }).enabled).toBe(false);
        expect(wf.styleStepFinishState(null).enabled).toBe(false);
    });
});

describe('isStyleRequiredRefusal', () => {
    it('only the 409 that names the book style', () => {
        let e = new Error('(409) ' + STYLE_409); e.status = 409;
        expect(wf.isStyleRequiredRefusal(e)).toBe(true);
        let pinned = new Error('(409) Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)'); pinned.status = 409;
        expect(wf.isStyleRequiredRefusal(pinned)).toBe(false);
        expect(wf.isPinnedRefusal(e)).toBe(false);
        let other = new Error('(400) Set the book style before rendering'); other.status = 400;
        expect(wf.isStyleRequiredRefusal(other)).toBe(false);
        expect(wf.isStyleRequiredRefusal(null)).toBe(false);
    });
});

// ─────────────────────────────── reader ───────────────────────────────

let pbf;
let origFetch;
const PAGES = [{ objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'READY', pinned: false }];

beforeEach(async () => {
    origFetch = global.fetch;
    pbf = await import('../features/pictureBook.js');
    pbf.__pb2StopWatchingForTest();
    pbf.__setPb2V3StateForTest({
        pb2AutoApprove: false, pb2AutoApproveSaving: false, pb2Run: null, pb2ResumeRun: null, pb2Submitting: false,
        pb2CurrentPage: 1, pb2BookObjectId: 'B1', pb2BookName: 'Book', pb2Pages: PAGES, pb2Covers: null,
        pb2StyleDescribed: null, pb2IsChapBook: false, pb2ChatConfig: null, pb2ChatConfigResolving: false, pb2PollIntervalMs: 5
    });
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
});

afterEach(() => {
    pbf.__pb2StopWatchingForTest();
    pbf.__setPb2V3StateForTest({ pb2Run: null, pb2ResumeRun: null, pb2StyleDescribed: null, pb2IsChapBook: false });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

describe('reader toolbar grouping', () => {
    function groupOf(header, testid) {
        for (let g of ['reading', 'setup', 'render', 'tools', 'right']) {
            let node = findByAttr(header, 'data-pb2-toolbar-group', g);
            if (node && findDeep(node, testid)) return g;
        }
        return null;
    }
    it('left to right: reading, setup, render, tools, right', () => {
        let header = pbf.renderPb2Header();
        let order = header.children.filter(Boolean).map(c => c.attrs['data-pb2-toolbar-group']);
        expect(order).toEqual(['reading', 'setup', 'render', 'tools', 'right']);
    });
    it('Style + Characters are the setup group; Render is its own labeled group; Export / Fullscreen on the right', () => {
        let header = pbf.renderPb2Header();
        expect(groupOf(header, 'pb2-style-link')).toBe('setup');
        let setup = findByAttr(header, 'data-pb2-toolbar-group', 'setup');
        expect(findByAttr(setup, 'data-pb2-characters', 'B1')).not.toBeNull();
        expect(groupOf(header, 'pb2-render-all')).toBe('render');
        expect(textOf(findDeep(header, 'pb2-render-all'))).toBe('brushRender');
        expect(groupOf(header, 'pb2-tools-menu')).toBe('tools');
        expect(groupOf(header, 'pb2-export')).toBe('right');
        expect(groupOf(header, 'pb2-fullscreen')).toBe('right');
        expect(findByAttr(findByAttr(header, 'data-pb2-toolbar-group', 'right'), 'title', 'Delete picture book')).not.toBeNull();
    });
    it('the optional tools are NOT on the toolbar; they are the overflow items, keeping their testids and titles', () => {
        let header = pbf.renderPb2Header();
        ['pb2-locations-link', 'pb2-compose-link', 'pb2-cover-link', 'pb2-review-link', 'pb2-chat-config', 'pb2-auto-approve-badge']
            .forEach(id => expect(findDeep(header, id), id).toBeNull());
        let items = pbf.pb2OverflowItems().filter(i => !i.divider);
        expect(items.map(i => i.attrs['data-testid'])).toEqual(['pb2-locations-link', 'pb2-compose-link', 'pb2-cover-link',
            'pb2-review-link', 'pb2-workflow-link', 'pb2-chat-config', 'pb2-auto-approve-badge']);
        expect(items.find(i => i.attrs['data-testid'] === 'pb2-review-link').attrs.title).toBe('Review queue');
        expect(items.find(i => i.attrs['data-testid'] === 'pb2-workflow-link').attrs.title).toBe('Open Workflow Canvas');
        let aa = items.find(i => i.attrs['data-testid'] === 'pb2-auto-approve-badge');
        expect(aa.label).toBe('Auto-approve: OFF');
        expect(aa.icon).toBeUndefined();
        expect(aa.attrs['data-on']).toBe('0');
    });
    it('overflow items route to the tool views; Auto-approve toggles through the same PUT as the render dialog', async () => {
        let items = pbf.pb2OverflowItems();
        items.find(i => i.attrs && i.attrs['data-testid'] === 'pb2-locations-link').action();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/B1/locations');
        items.find(i => i.attrs && i.attrs['data-testid'] === 'pb2-workflow-link').action();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/B1/workflow');
        let calls = [];
        global.fetch = vi.fn(async (u, init) => { calls.push((init && init.method) + ' ' + String(u).replace(/^.*\/rest\//, '/rest/')); return jsonResponse(200, { autoApprove: true }); });
        items.find(i => i.attrs && i.attrs['data-testid'] === 'pb2-auto-approve-badge').action();
        await flush(); await flush();
        expect(calls[0]).toBe('PUT /rest/olio/picture-book/B1/settings/auto-approve');
        expect(pbf.__pb2V3StateForTest().pb2AutoApprove).toBe(true);
    });
    it('the Tools button opens the shared contextMenu with those items', () => {
        let btn = findDeep(pbf.renderPb2Header(), 'pb2-tools-menu');
        let target = { getBoundingClientRect: () => ({ left: 500, right: 560, bottom: 40 }) };
        btn.attrs.onclick({ currentTarget: target, preventDefault() {}, stopPropagation() {}, clientX: 0, clientY: 0 });
        let shown = contextMenu.items();
        expect(shown.filter(i => !i.divider).map(i => i.attrs['data-testid'])).toContain('pb2-cover-link');
        contextMenu.dismiss();
        expect(contextMenu.items()).toEqual([]);
    });
});

describe('reader render gate — Style mark, Render, per-scene buttons', () => {
    it('no style: Style needs attention; Render disabled with the reason and a link to the style view', () => {
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false });
        let header = pbf.renderPb2Header();
        let style = findDeep(header, 'pb2-style-link');
        expect(style.attrs['data-style-state']).toBe('attention');
        expect(findDeep(header, 'pb2-style-status').attrs['data-state']).toBe('attention');
        let render = findDeep(header, 'pb2-render-all');
        expect(render.attrs.disabled).toBe(true);
        expect(render.attrs.title).toBe('Set the book style first');
        let link = findDeep(header, 'pb2-render-needs-style');
        expect(textOf(link)).toContain('Set the book style first');
        link.attrs.onclick({ stopPropagation() {} });
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/B1/style');
    });
    it('a style: ✓ on Style, Render enabled ("Render all scenes"), no link', () => {
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true });
        let header = pbf.renderPb2Header();
        expect(findDeep(header, 'pb2-style-status').attrs['data-state']).toBe('ok');
        let render = findDeep(header, 'pb2-render-all');
        expect(render.attrs.disabled).toBe(false);
        expect(render.attrs.title).toBe('Render all scenes');
        expect(findDeep(header, 'pb2-render-needs-style')).toBeNull();
    });
    it('unknown style: no mark, Render enabled', () => {
        let header = pbf.renderPb2Header();
        expect(findDeep(header, 'pb2-style-status')).toBeNull();
        expect(findDeep(header, 'pb2-render-all').attrs.disabled).toBe(false);
    });
    it('per-scene: Render and Regenerate background disabled with the reason; Plan & generate stays open for the preview', () => {
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false,
            pb2Pages: [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', sceneNodeObjectId: 'n1', dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED' }] });
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        let pg = pbf.renderPb2ScenePage();
        let r = findDeep(pg, 'pb2-scene-render');
        expect(r.attrs.disabled).toBe(true);
        expect(r.attrs.title).toBe('Set the book style first');
        expect(findDeep(pg, 'pb2-scene-regen-background').attrs.disabled).toBe(true);
        expect(findDeep(pg, 'pb2-plan-render').attrs.disabled).toBe(false);
        expect(textOf(findDeep(pg, 'pb2-scene-needs-style'))).toContain('Set the book style first');
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true });
        pg = pbf.renderPb2ScenePage();
        expect(findDeep(pg, 'pb2-scene-render').attrs.disabled).toBe(false);
        expect(findDeep(pg, 'pb2-scene-needs-style')).toBeNull();
    });
    it('the submit functions are no-ops while the style is known missing (no request)', async () => {
        global.fetch = vi.fn(async () => jsonResponse(500, 'should not be called'));
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false });
        await pbf.__pb2SubmitRenderSceneForTest(PAGES[0]);
        await pbf.__pb2SubmitRegenBackgroundForTest(PAGES[0]);
        await pbf.__pb2SubmitRenderAllForTest();
        expect(global.fetch).not.toHaveBeenCalled();
    });
});

// ChapBook pages take their look from the SD config (book config + per-page configOverride, ChapBookStraightGen);
// a book-style description would replace that, so the gate never applies to a ChapBook (server exempts it too).
describe('ChapBook books are exempt from the style gate', () => {
    it('isChapBookType: CHAPBOOK in either case; anything else, or nothing, is not', () => {
        expect(wf.isChapBookType('CHAPBOOK')).toBe(true);
        expect(wf.isChapBookType('chapbook')).toBe(true);
        expect(wf.isChapBookType('STORY')).toBe(false);
        expect(wf.isChapBookType(null)).toBe(false);
        expect(wf.isChapBookType(undefined)).toBe(false);
    });
    it('a ChapBook with no style description: Render enabled, no Style mark, no link', () => {
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false, pb2IsChapBook: true });
        let header = pbf.renderPb2Header();
        expect(findDeep(header, 'pb2-render-all').attrs.disabled).toBe(false);
        expect(findDeep(header, 'pb2-render-all').attrs.title).toBe('Render all scenes');
        expect(findDeep(header, 'pb2-style-status')).toBeNull();
        expect(findDeep(header, 'pb2-render-needs-style')).toBeNull();
        expect(pbf.pb2RenderGate().enabled).toBe(true);
    });
    it('a ChapBook has no characters: the setup group offers no Characters control; a PictureBook does', () => {
        const hasCharacters = h => textOf(h).includes('Characters');
        pbf.__setPb2V3StateForTest({ pb2IsChapBook: true });
        expect(hasCharacters(pbf.renderPb2Header())).toBe(false);
        pbf.__setPb2V3StateForTest({ pb2IsChapBook: false });
        expect(hasCharacters(pbf.renderPb2Header())).toBe(true);
    });
    it('a ChapBook page: Render and Regenerate background stay enabled without a style', () => {
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false, pb2IsChapBook: true,
            pb2Pages: [{ objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', sceneNodeObjectId: 'n1', dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED' }] });
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        let pg = pbf.renderPb2ScenePage();
        expect(findDeep(pg, 'pb2-scene-render').attrs.disabled).toBe(false);
        expect(findDeep(pg, 'pb2-scene-regen-background').attrs.disabled).toBe(false);
        expect(findDeep(pg, 'pb2-scene-needs-style')).toBeNull();
    });
    it('a ChapBook render with no style still submits (the request is made)', async () => {
        global.fetch = vi.fn(async () => jsonResponse(202, { jobId: 'J1', runObjectId: 'R1' }));
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false, pb2IsChapBook: true });
        await pbf.__pb2SubmitRenderSceneForTest(PAGES[0]);
        expect(global.fetch).toHaveBeenCalled();
        expect(String(global.fetch.mock.calls[0][0])).toMatch(/\/scene\/s1\/render/);
    });
});

describe('render dialog and plan dialog — hard block', () => {
    it('render dialog: Start disabled with the reason once the style read says "none"; the warning links to Book style', async () => {
        global.fetch = vi.fn(async (u) => /\/B1\/style$/.test(String(u))
            ? jsonResponse(200, { effective: { fields: {} } }) : jsonResponse(404, 'nope'));
        let open = vi.spyOn(Dialog, 'open').mockImplementation(() => {});
        pbf.__pb2OpenRenderDialogForTest();
        await flush(); await flush();
        let cfg = open.mock.calls[0][0];
        let start = cfg.actions.view().find(a => a.attrs['data-testid'] === 'pb2-render-all-start');
        expect(start.disabled).toBe(true);
        expect(start.attrs.title).toBe('Set the book style first');
        expect(findDeep(cfg.content.view(), 'pb2-render-no-style')).not.toBeNull();
    });
    it('render dialog: Start enabled when the style has a description', async () => {
        global.fetch = vi.fn(async (u) => /\/B1\/style$/.test(String(u))
            ? jsonResponse(200, { effective: { fields: { description: 'ink' } } }) : jsonResponse(404, 'nope'));
        let open = vi.spyOn(Dialog, 'open').mockImplementation(() => {});
        pbf.__pb2OpenRenderDialogForTest();
        await flush(); await flush();
        let start = open.mock.calls[0][0].actions.view().find(a => a.attrs['data-testid'] === 'pb2-render-all-start');
        expect(start.disabled).toBe(false);
    });
});

describe('the server 409 reaches the user verbatim and locks the gate', () => {
    function refuse() {
        global.fetch = vi.fn(async () => jsonResponse(409, { error: STYLE_409 }));
    }
    it('per-scene render', async () => {
        refuse();
        await pbf.__pb2SubmitRenderSceneForTest(PAGES[0]);
        expect(page.toast).toHaveBeenCalledWith('error', '(409) ' + STYLE_409);
        expect(pbf.__pb2V3StateForTest().pb2StyleDescribed).toBe(false);
    });
    it('regenerate background', async () => {
        refuse();
        await pbf.__pb2SubmitRegenBackgroundForTest(PAGES[0]);
        expect(page.toast).toHaveBeenCalledWith('error', '(409) ' + STYLE_409);
        expect(pbf.__pb2V3StateForTest().pb2StyleDescribed).toBe(false);
    });
    it('render all (the dialog stays open)', async () => {
        refuse();
        let close = vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        await pbf.__pb2SubmitRenderAllForTest();
        expect(page.toast).toHaveBeenCalledWith('error', '(409) ' + STYLE_409);
        expect(close).not.toHaveBeenCalled();
        expect(pbf.__pb2V3StateForTest().pb2StyleDescribed).toBe(false);
    });
    it('render-plan: shown in the open plan dialog verbatim; Generate then disabled', async () => {
        refuse();
        pbf.__setPb2PlanStateForTest({ bookId: 'B1', sceneOid: 's1', open: true, rendering: false, loading: false, plan: wf.normalizePlan({}), error: null });
        await pbf.__pb2GenerateFromPlanForTest();
        expect(pbf.__pb2PlanStateForTest().error).toBe('(409) ' + STYLE_409);
        expect(pbf.__pb2V3StateForTest().pb2StyleDescribed).toBe(false);
    });
});

describe('contextMenu — attrs and disabled items (the reader overflow relies on both)', () => {
    it('attrs land on the item button; a disabled item renders disabled and does not run', () => {
        let ran = 0;
        contextMenu.show({ preventDefault() {}, stopPropagation() {}, clientX: 10, clientY: 10 }, [
            { label: 'A', attrs: { 'data-testid': 'cm-a', title: 'Item A' }, action: () => { ran++; } },
            { label: 'B', disabled: true, attrs: { 'data-testid': 'cm-b' }, action: () => { ran++; } }
        ]);
        let v = contextMenu.component.view();
        let a = findDeep(v, 'cm-a');
        expect(a.attrs.title).toBe('Item A');
        expect(a.attrs.role).toBe('menuitem');
        let b = findDeep(v, 'cm-b');
        expect(b.attrs.disabled).toBe(true);
        b.attrs.onclick();
        expect(ran).toBe(0);
        a.attrs.onclick();
        expect(ran).toBe(1);
        expect(contextMenu.items()).toEqual([]);
    });
});
