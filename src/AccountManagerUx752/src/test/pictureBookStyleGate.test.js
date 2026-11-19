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
 * Covered: renderGate / styleStepFinishState / isStyleRequiredRefusal (pure); Read mode carrying none of the
 * editing controls and each of them in its Edit section (aiDocs/PictureBookUxConsistencyDesign.md §5.1 — the old
 * toolbar grouping / Tools overflow is gone): Render all in Edit › Render, Auto-approve and Chat config in
 * Edit › Book, the Style ⚠/✓ as the Edit › Style rail status, the per-scene gate in Edit › Scenes › Render; the
 * render and plan dialogs' hard block, the 409 → verbatim toast + lock, and contextMenu's attrs / disabled items.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import * as wf from '../workflows/pictureBookWorkflow.js';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { contextMenu } from '../components/contextMenu.js';
import { pageHeader } from '../components/pageHeader.js';
import { __resetDirtyForTest } from '../components/dirtyGuard.js';
import { __resetActivityForTest } from '../workflows/bookActivity.js';
import * as nav from '../workflows/bookWorkspaceNav.js';

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
        expect(wf.styleStepFinishState({ style: none, draft: {} })).toEqual({ enabled: false, reason: 'Fill from a preset or write a description, then Save' });
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
let edit;
let styleMod;
let origFetch;
const PAGES = [{ objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'READY', pinned: false }];

beforeEach(async () => {
    origFetch = global.fetch;
    pbf = await import('../features/pictureBook.js');
    edit = await import('../features/pictureBookEdit.js');
    styleMod = await import('../features/pictureBookStyle.js');
    __resetDirtyForTest();
    __resetActivityForTest();
    edit.__resetPbEditForTest();
    styleMod.__setStyleStateForTest({ bookObjectId: null, style: null, draft: {} });
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
    styleMod.__setStyleStateForTest({ bookObjectId: null, style: null, draft: {} });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────── Read vs Edit (aiDocs/PictureBookUxConsistencyDesign.md §4.1, §5.1, §5.6, §5.9) ───────────────
// The old toolbar grouping (reading · setup · Render · Tools overflow · right) is gone: Read mode carries no
// editing control, and each control moved to its Edit section. These tests pin both halves — absence in Read,
// presence (with the same gating) in the Edit section — rendered with the real Mithril renderer into jsdom.

/** A tiny DOM render: the section bodies mix plain vnodes and components (the render settings body). */
function render(vnode) {
    let div = document.createElement('div');
    m.render(div, vnode);
    return div;
}
function q(el, testid) { return el.querySelector('[data-testid="' + testid + '"]'); }
function ctxFor(over) {
    return Object.assign({ kind: 'pb', bookObjectId: 'B1', section: 'scenes', item: 's1', tab: 'render',
        go: vi.fn(), select: vi.fn(), setTab: vi.fn() }, over || {});
}
/** Seed the reader state for `pages` so a Scenes body renders without a scene search or a degradation read. */
function seedScenes(pages) {
    pbf.__setPb2V3StateForTest({ pb2Pages: pages, pb2SceneDegradations: { n1: { loading: false, summary: null, error: null } } });
    let recs = {};
    pages.forEach(p => { recs[p.objectId] = { loading: false, error: null, rec: { id: 1, objectId: p.objectId, title: p.title || '' } }; });
    edit.__resetPbEditForTest();
    edit.__setPbEditStateForTest({ sceneRecs: recs });
}
function renderSceneTab(tab, item) {
    return render(edit.scenesSection.body(ctxFor({ section: 'scenes', item: item || 's1', tab })));
}
/** What bookWorkspace.renderPane does with an adapter's toolbar(ctx). */
function renderToolbar(section, ctx) {
    return render(pageHeader(Object.assign({ bar: true }, section.toolbar(ctx || ctxFor({ section: 'render', item: null, tab: null })), { back: null })));
}
function renderRenderBody() {
    return render(edit.renderSection.body(ctxFor({ section: 'render', item: null, tab: null })));
}
const RENDERED_PAGE = { objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE', sceneNodeObjectId: 'n1', dataObjectId: 'd1', imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'APPROVED' };

// Every testid the old Read toolbar and its Tools overflow carried; none of them may be in Read now.
const OLD_READ_EDIT_CONTROLS = ['pb2-style-link', 'pb2-style-status', 'pb2-render-all', 'pb2-render-needs-style', 'pb2-tools-menu',
    'pb2-locations-link', 'pb2-compose-link', 'pb2-cover-link', 'pb2-review-link', 'pb2-workflow-link', 'pb2-chat-config',
    'pb2-auto-approve-badge', 'pb-book-delete'];

describe('Read mode has no editing controls; each lives in its Edit section', () => {
    it('the Read header carries none of the old toolbar / Tools controls (absence by testid), no Characters, no Delete', () => {
        let el = render(pbf.renderPb2Header());
        OLD_READ_EDIT_CONTROLS.forEach(id => expect(q(el, id), id).toBeNull());
        expect(el.querySelector('[data-pb2-characters]')).toBeNull();
        expect(el.querySelector('[title="Delete picture book"]')).toBeNull();
        expect(el.textContent).not.toContain('Characters');
        // what Read keeps: reading navigation, the mode toggle, export, fullscreen
        ['book-back', 'pb2-prev', 'pb2-next', 'book-mode', 'book-mode-read', 'book-mode-edit', 'pb2-export', 'pb2-fullscreen']
            .forEach(id => expect(q(el, id), id).not.toBeNull());
        expect(q(el, 'book-header').getAttribute('data-mode')).toBe('read');
    });
    it('Render all is the Edit › Render toolbar action; Auto-approve and Chat config are Edit › Book controls', () => {
        let tb = renderToolbar(edit.renderSection);
        let start = q(tb, 'pb2-render-all-start');
        expect(start).not.toBeNull();
        expect(start.textContent).toBe('Render all');
        let book = render(edit.bookSection.body(ctxFor({ section: 'book', item: null, tab: null })));
        let aa = q(book, 'pb2-auto-approve-badge');
        expect(aa.type).toBe('checkbox');
        expect(aa.getAttribute('data-on')).toBe('0');
        expect(aa.checked).toBe(false);
        expect(q(book, 'pb2-chat-config')).not.toBeNull();
        expect(q(book, 'pb-book-delete')).not.toBeNull();
    });
    // The Tools overflow (Locations, Composition, Cover, Review, Canvas) is replaced by the Edit rail (§4.2).
    it('the Edit rail offers every tool view and routes to it; Style carries the ⚠ / ✓ mark', async () => {
        global.fetch = vi.fn(() => new Promise(() => {})); // section loads stay pending: the render is synchronous state
        styleMod.__setStyleStateForTest({ bookObjectId: 'B1', style: wf.normalizeStyle({ effective: { fields: {} } }) });
        edit.pbWorkspace.__resetForTest();
        let el = render(m(edit.pbWorkspace.view, { bookObjectId: 'B1', section: 'render' }));
        ['book', 'style', 'characters', 'locations', 'scenes', 'cover', 'review', 'render', 'canvas']
            .forEach(id => expect(q(el, 'ws-rail-' + id), id).not.toBeNull());
        expect(q(el, 'ws-rail-style').getAttribute('data-status')).toBe('attention');
        expect(q(el, 'ws-rail-render').getAttribute('data-active')).toBe('1');
        expect(q(el, 'book-header').getAttribute('data-mode')).toBe('edit');
        q(el, 'ws-rail-locations').click();
        await flush(); await flush();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/B1/edit/locations');
        q(el, 'ws-rail-canvas').click();
        await flush(); await flush();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/B1/edit/canvas');
        m.render(el, null);
        edit.pbWorkspace.__resetForTest();
    });
    it('Edit › Book Auto-approve toggles through the same PUT as the render settings', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, init) => { calls.push((init && init.method) + ' ' + String(u).replace(/^.*\/rest\//, '/rest/')); return jsonResponse(200, { autoApprove: true }); });
        let book = render(edit.bookSection.body(ctxFor({ section: 'book', item: null, tab: null })));
        let aa = q(book, 'pb2-auto-approve-badge');
        aa.checked = true;
        aa.dispatchEvent(new Event('change'));
        await flush(); await flush();
        expect(calls[0]).toBe('PUT /rest/olio/picture-book/B1/settings/auto-approve');
        expect(pbf.__pb2V3StateForTest().pb2AutoApprove).toBe(true);
        // the render settings (inline in Edit › Render) send the same request
        calls.length = 0;
        let body = renderRenderBody();
        let t = q(body, 'pb2-auto-approve-toggle');
        expect(t.checked).toBe(true);
        t.checked = false;
        t.dispatchEvent(new Event('change'));
        await flush(); await flush();
        expect(calls[0]).toBe('PUT /rest/olio/picture-book/B1/settings/auto-approve');
    });
});

describe('render gate — Style mark, Render, per-scene buttons (Edit sections)', () => {
    // The Style ⚠ / ✓ that sat on the Read toolbar is now the Edit › Style rail status (§5.3, styleSection.status).
    it('no style: Style needs attention; Render disabled with the reason and a link to the style view', async () => {
        styleMod.__setStyleStateForTest({ bookObjectId: 'B1', style: wf.normalizeStyle({ effective: { fields: {} } }) });
        expect(edit.pbStyleSection.status(ctxFor({ section: 'style' }))).toMatchObject({ kind: 'attention' });
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false });
        let start = q(renderToolbar(edit.renderSection), 'pb2-render-all-start');
        expect(start.disabled).toBe(true);
        expect(start.title).toBe('Set the book style first');
        let body = renderRenderBody();
        let link = q(body, 'pb2-render-needs-style');
        expect(link.textContent).toContain('Set the book style first');
        link.click();
        await flush(); await flush();
        // §3: the style view is now the Edit › Style section
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/B1/edit/style');
    });
    it('a style: ✓ on Style, Render enabled ("Render all scenes"), no link', () => {
        styleMod.__setStyleStateForTest({ bookObjectId: 'B1', style: wf.normalizeStyle({ effective: { fields: { description: 'ink and watercolor' } } }) });
        expect(edit.pbStyleSection.status(ctxFor({ section: 'style' }))).toMatchObject({ kind: 'ok' });
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true });
        let start = q(renderToolbar(edit.renderSection), 'pb2-render-all-start');
        expect(start.disabled).toBe(false);
        expect(start.title).toBe('Render every scene whose image is missing, stale or rejected');
        expect(q(renderRenderBody(), 'pb2-render-needs-style')).toBeNull();
    });
    it('unknown style: no mark, Render enabled', () => {
        styleMod.__setStyleStateForTest({ bookObjectId: 'B1', style: null });
        expect(edit.pbStyleSection.status(ctxFor({ section: 'style' }))).toBeNull();
        expect(q(renderToolbar(edit.renderSection), 'pb2-render-all-start').disabled).toBe(false);
        expect(q(renderRenderBody(), 'pb2-render-needs-style')).toBeNull();
    });
    it('Render all is disabled while a run is being watched, with the reason as its title', () => {
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true, pb2Run: { jobId: 'J1', runObjectId: 'R1', planned: ['scene-1'], job: { terminal: false }, label: 'Render all', error: null } });
        let start = q(renderToolbar(edit.renderSection), 'pb2-render-all-start');
        expect(start.disabled).toBe(true);
        expect(start.title).toBe('A render is already running');
    });
    it('per-scene: Render and Regenerate background disabled with the reason; Plan & generate stays open for the preview', () => {
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false });
        seedScenes([RENDERED_PAGE]);
        let el = renderSceneTab('render');
        let r = q(el, 'pb2-scene-render');
        expect(r.disabled).toBe(true);
        expect(r.title).toBe('Set the book style first');
        expect(q(el, 'pb2-scene-regen-background').disabled).toBe(true);
        expect(q(el, 'pb2-plan-render').disabled).toBe(false);
        expect(q(el, 'pb2-scene-needs-style').textContent).toContain('Set the book style first');
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true });
        el = renderSceneTab('render');
        expect(q(el, 'pb2-scene-render').disabled).toBe(false);
        expect(q(el, 'pb2-scene-needs-style')).toBeNull();
        // and none of the per-scene buttons are on the Read page
        let read = render(pbf.renderPb2ScenePage());
        ['pb2-scene-render', 'pb2-scene-regen-background', 'pb2-plan-render', 'pb2-scene-compose', 'pb2-scene-needs-style']
            .forEach(id => expect(q(read, id), id).toBeNull());
        expect(q(read, 'pb2-edit-page')).not.toBeNull();
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
        styleMod.__setStyleStateForTest({ bookObjectId: 'B1', style: wf.normalizeStyle({ effective: { fields: {} } }) });
        // the raw style status would say ⚠; the workspace's Style section suppresses it for a ChapBook
        expect(styleMod.styleSection.status(ctxFor({ section: 'style' }))).toMatchObject({ kind: 'attention' });
        expect(edit.pbStyleSection.status(ctxFor({ section: 'style' }))).toBeNull();
        let start = q(renderToolbar(edit.renderSection), 'pb2-render-all-start');
        expect(start.disabled).toBe(false);
        expect(start.title).toBe('Render every scene whose image is missing, stale or rejected');
        let body = renderRenderBody();
        expect(q(body, 'pb2-render-needs-style')).toBeNull();
        expect(q(body, 'pb2-render-no-style')).toBeNull();
        expect(pbf.pb2RenderGate().enabled).toBe(true);
    });
    // §5.1 / §8.2: Characters left Read mode; it is an Edit section, and ChapBook's section list has none.
    it('a ChapBook has no characters: its Edit sections have no Characters; a PictureBook\'s do; Read offers it for neither', () => {
        expect(nav.sectionsFor('cb').map(s => s.id)).not.toContain('characters');
        expect(nav.sectionsFor('pb').map(s => s.id)).toContain('characters');
        pbf.__setPb2V3StateForTest({ pb2IsChapBook: true });
        expect(render(pbf.renderPb2Header()).textContent).not.toContain('Characters');
        pbf.__setPb2V3StateForTest({ pb2IsChapBook: false });
        expect(render(pbf.renderPb2Header()).textContent).not.toContain('Characters');
    });
    it('a ChapBook page: Render and Regenerate background stay enabled without a style', () => {
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false, pb2IsChapBook: true });
        seedScenes([RENDERED_PAGE]);
        let el = renderSceneTab('render');
        expect(q(el, 'pb2-scene-render').disabled).toBe(false);
        expect(q(el, 'pb2-scene-regen-background').disabled).toBe(false);
        expect(q(el, 'pb2-scene-needs-style')).toBeNull();
    });
    it('a ChapBook render with no style still submits (the request is made)', async () => {
        global.fetch = vi.fn(async () => jsonResponse(202, { jobId: 'J1', runObjectId: 'R1' }));
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: false, pb2IsChapBook: true });
        await pbf.__pb2SubmitRenderSceneForTest(PAGES[0]);
        expect(global.fetch).toHaveBeenCalled();
        expect(String(global.fetch.mock.calls[0][0])).toMatch(/\/scene\/s1\/render/);
    });
});

// 2026-11-16: every image of a scene rejected in review - the page says so and points at Re-render (Render all
// re-renders it too: PbRenderJobUtil.selectedImageRejected).
describe('a scene whose images were all rejected', () => {
    const REJECTED_PAGE = { objectId: 's1', sceneIndex: 1, nodeStatus: 'DONE_UNVERIFIED', sceneNodeObjectId: 'n1', dataObjectId: 'd1',
        imageGroupPath: '/PictureBook/x', imageName: 'scene-1.png', reviewStatus: 'REJECTED', artifactObjectId: 'a1' };
    it('red Rejected chip, an "All images rejected" badge, and Re-render offered with the reason', () => {
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true });
        seedScenes([REJECTED_PAGE]);
        // Read: the badge is status about the page itself, so it stays on the image (§5.1)
        let read = render(pbf.renderPb2ScenePage());
        expect(q(read, 'pb2-rejected-badge').textContent).toContain('All images rejected');
        expect(q(read, 'pb2-stale-badge')).toBeNull();
        // Edit › Scenes › Page: the chip
        let pageTab = renderSceneTab('page');
        let chip = q(pageTab, 'pb2-scene-chip');
        expect(chip.getAttribute('data-chip')).toBe('rejected');
        expect(chip.textContent).toBe('Rejected');
        expect(q(pageTab, 'pb2-rejected-badge').textContent).toContain('All images rejected');
        // Edit › Scenes › Render: Re-render with the reason
        let r = q(renderSceneTab('render'), 'pb2-scene-render');
        expect(r.disabled).toBe(false);
        expect(r.textContent).toContain('Re-render');
        expect(r.title).toBe('Every image of this scene was rejected; render a new one');
    });
    it('a GENERATED selection is still Review, with no rejected badge', () => {
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true });
        seedScenes([Object.assign({}, REJECTED_PAGE, { reviewStatus: 'GENERATED' })]);
        let pageTab = renderSceneTab('page');
        expect(q(pageTab, 'pb2-scene-chip').getAttribute('data-chip')).toBe('review');
        expect(q(pageTab, 'pb2-rejected-badge')).toBeNull();
        expect(q(render(pbf.renderPb2ScenePage()), 'pb2-rejected-badge')).toBeNull();
    });
    it('the Render dialog says rejected scenes are rendered', async () => {
        let opened = null;
        vi.spyOn(Dialog, 'open').mockImplementation(c => { opened = c; });
        global.fetch = vi.fn(async () => jsonResponse(200, { effective: { fields: { description: 'ink' } } }));
        await pbf.__pb2OpenRenderDialogForTest();
        let body = opened.content.view();
        expect(textOf(body)).toContain('missing, stale, or rejected');
        // the same settings body is drawn inline in Edit › Render
        expect(renderRenderBody().textContent).toContain('missing, stale, or rejected');
    });
});

describe('normalizeSubmit carries rejectedNodeCount', () => {
    it('defaults to 0 and passes the server count through', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, init) => { calls.push(String(u)); return jsonResponse(202, { jobId: 'J', runObjectId: 'R', planned: ['h'], rejectedNodeCount: 3 }); });
        let r = await wf.renderAll('B1', {});
        expect(r.rejectedNodeCount).toBe(3);
        global.fetch = vi.fn(async () => jsonResponse(202, { jobId: 'J', runObjectId: 'R', planned: [] }));
        expect((await wf.renderAll('B1', {})).rejectedNodeCount).toBe(0);
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

// Render all is both the Render dialog's Start and the Edit › Render toolbar action (design §5.9). Starting it from
// the toolbar must not close whatever unrelated dialog is on top; starting it from the dialog closes that dialog.
describe('Render all closes only its own dialog', () => {
    function accept() {
        global.fetch = vi.fn(async (url, init) => {
            let u = String(url);
            if (init && init.method === 'POST' && /\/render/.test(u)) return jsonResponse(202, { jobId: 'j1', runObjectId: 'r1', planned: ['scene@1'] });
            if (/\/job\//.test(u)) return jsonResponse(200, { jobId: 'j1', terminal: true, current: 1, total: 1, result: { runStatus: 'DONE' } });
            return jsonResponse(200, []);
        });
    }
    it('from the Edit › Render toolbar (no Render dialog open): Dialog.close is not called', async () => {
        accept();
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true, pb2RenderDialogOpen: false });
        let close = vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        await pbf.__pb2SubmitRenderAllForTest();
        expect(global.fetch.mock.calls.some(c => /\/render/.test(String(c[0])) && c[1] && c[1].method === 'POST')).toBe(true);
        expect(close).not.toHaveBeenCalled();
    });
    it('from the Render dialog: Start closes that dialog', async () => {
        accept();
        pbf.__setPb2V3StateForTest({ pb2StyleDescribed: true });
        vi.spyOn(Dialog, 'open').mockImplementation(() => {});
        let close = vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        pbf.__pb2OpenRenderDialogForTest();
        await pbf.__pb2SubmitRenderAllForTest();
        expect(close).toHaveBeenCalledTimes(1);
    });
});
