// @vitest-environment jsdom
/**
 * PictureBook P7-3 / P10-1 — the wizard hands off to the V3 workspace; existing books open in V3.
 *
 * Why: the three PB1 routes the wizard's old steps 4–5 called (scene generate, prepare-images, PUT
 * settings) no longer exist (P10-1). The wizard ENDS at step 4 (Style, required) — step 3's "Continue to
 * Style" opens the book style editor inline on the pb2 book the step-2 Continue created, and step 4's
 * "Continue to Book" (disabled until the SAVED style has a description) opens the V3 reader — and an
 * existing book never re-enters the
 * wizard (the list pencils and the /picture-book/:groupOid route resolve to the V3 reader instead). A
 * pure PB1 book (scenes, no olio.pb.book) is the one thing left on the PB1 viewer, read-only, with the
 * "replaced by V3 — import" notice P7-4 wires up.
 *
 * Covered: resolvePb2BookObjectId's three lookups, the step-3 and step-4 actions, the progress bar,
 * tryResumeExistingBook's four outcomes, handOffToReader with/without a V3 record, the PB1 viewer's
 * redirect / legacy notice / no Edit pencil, and the PB2 reader header never offering its Edit pencil.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import * as wf from '../workflows/pictureBookWorkflow.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

// One fetch stub for every lookup the hand-off touches, keyed by what the server knows:
//   pb2Books    — olio.pb.book objectIds (GET /rest/model/olio.pb.book/{id} → 200)
//   bridges     — PB1 group oid → pb2 oid (GET /{group}/pb2 → {pb2BookObjectId})
//   scenes      — id → scene list (GET /{id}/scenes; the dual lookup accepts group or pb2 oid)
//   books       — the /books DTO list
function stubServer(server) {
    let calls = [];
    global.fetch = vi.fn(async (url) => {
        let u = String(url);
        calls.push(u);
        let mm;
        if ((mm = u.match(/\/rest\/model\/olio\.pb\.book\/([^/?]+)$/))) {
            let id = decodeURIComponent(mm[1]);
            return (server.pb2Books || []).includes(id) ? jsonResponse(200, { objectId: id, schema: 'olio.pb.book' }) : jsonResponse(404, 'Not found');
        }
        if ((mm = u.match(/\/picture-book\/([^/]+)\/pb2$/))) {
            let pb2 = (server.bridges || {})[mm[1]];
            return pb2 ? jsonResponse(200, { pb2BookObjectId: pb2, slug: 'slug', bookName: 'Book' }) : jsonResponse(404, 'Book group not found');
        }
        if ((mm = u.match(/\/picture-book\/([^/]+)\/scenes$/))) {
            let sc = (server.scenes || {})[mm[1]];
            return sc ? jsonResponse(200, sc) : jsonResponse(404, 'Not found');
        }
        if ((mm = u.match(/\/picture-book\/([^/]+)\/style$/))) {
            let st = (server.styles || {})[mm[1]];
            return st ? jsonResponse(200, st) : jsonResponse(404, 'Book not found');
        }
        if (/\/picture-book\/[^/]+\/characters$/.test(u)) return jsonResponse(200, []);
        if (/\/picture-book\/books(\?|$)/.test(u)) return jsonResponse(200, server.books || []);
        if (/\/picture-book\/[^/]+\/settings$/.test(u)) return jsonResponse(200, {});
        return jsonResponse(404, 'unstubbed ' + u);
    });
    return calls;
}

function flush() { return new Promise(r => setTimeout(r, 0)); }

let origFetch;
let pb;
let pbf;
let routeSet;
let dialogClose;

beforeEach(async () => {
    origFetch = global.fetch;
    pb = await import('../workflows/pictureBook.js');
    pbf = await import('../features/pictureBook.js');
    pb.__resetExtractStateForTest('work-1');
    routeSet = vi.spyOn(m.route, 'set').mockImplementation(() => {});
    dialogClose = vi.spyOn(Dialog, 'close').mockImplementation(() => {});
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    // The inlined style editor also reads the book record and the SD model lists; neither matters here.
    vi.spyOn(am7client, 'getFull').mockResolvedValue(null);
});

afterEach(() => {
    pb.__resetExtractStateForTest(null);
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────── resolvePb2BookObjectId ─────────────────────────

describe('resolvePb2BookObjectId — any id the wizard is handed → olio.pb.book oid or null', () => {
    it('a pb2 book oid resolves to itself from the by-id read (one request)', async () => {
        let calls = stubServer({ pb2Books: ['PB2-A'] });
        expect(await wf.resolvePb2BookObjectId('PB2-A')).toBe('PB2-A');
        expect(calls).toHaveLength(1);
        expect(calls[0]).toMatch(/\/rest\/model\/olio\.pb\.book\/PB2-A$/);
    });
    it('a PB1 group oid resolves through the /pb2 bridge', async () => {
        stubServer({ bridges: { 'GRP-1': 'PB2-B' } });
        expect(await wf.resolvePb2BookObjectId('GRP-1')).toBe('PB2-B');
    });
    it('falls back to the /books list when the by-id read cannot see the record', async () => {
        stubServer({ books: [{ objectId: 'PB2-C', name: 'Shared chapter' }] });
        expect(await wf.resolvePb2BookObjectId('PB2-C')).toBe('PB2-C');
    });
    it('is null for a source document / pure PB1 book / unknown id, and for no id', async () => {
        stubServer({ books: [{ objectId: 'PB2-C' }] });
        expect(await wf.resolvePb2BookObjectId('DOC-1')).toBeNull();
        expect(await wf.resolvePb2BookObjectId(null)).toBeNull();
        expect(await wf.resolvePb2BookObjectId('undefined')).toBeNull();
    });
    it('a network failure on every lookup is null, never a throw', async () => {
        global.fetch = vi.fn(async () => { throw new Error('ECONNRESET'); });
        await expect(wf.resolvePb2BookObjectId('PB2-A')).resolves.toBeNull();
    });
});

// ──────────────────────── step 3 → step 4 (Style) → reader ────────────────────────

const STYLED = { bookObjectId: 'PB2-A', effective: { fields: { description: 'ink and watercolor' }, sources: {} }, chain: [] };
const UNSTYLED = { bookObjectId: 'PB2-A', effective: { fields: {}, sources: {} }, chain: [] };

function action(label) {
    let a = pb.buildActions().find(x => x.label === label);
    expect(a, 'the wizard must offer "' + label + '"').toBeTruthy();
    return a;
}
async function settle() { for (let i = 0; i < 6; i++) await flush(); }

describe('step 3 "Continue to Style"', () => {
    it('opens step 4 on the pb2 book (no hand-off yet; the dialog stays open)', async () => {
        stubServer({ pb2Books: ['PB2-A'], styles: { 'PB2-A': UNSTYLED } });
        pb.__setExtractStateForTest({ step: 3, bookObjectId: 'GRP-1', pb2WizardBookOid: 'PB2-A' });
        expect(pb.buildActions().find(x => x.label === 'Continue to Images')).toBeUndefined();
        expect(await pb.continueToStyle()).toBe('PB2-A');
        await settle();
        expect(pb.__extractStateForTest().step).toBe(4);
        expect(dialogClose).not.toHaveBeenCalled();
        expect(routeSet).not.toHaveBeenCalled();
        const style = await import('../features/pictureBookStyle.js');
        expect(style.styleEditorState().bookObjectId).toBe('PB2-A');
    });
    it('resumed on a PB1 group without a cached pb2 oid: resolves it via /pb2 first', async () => {
        stubServer({ bridges: { 'GRP-1': 'PB2-B' }, styles: { 'PB2-B': UNSTYLED } });
        pb.__setExtractStateForTest({ step: 3, bookObjectId: 'GRP-1', pb2WizardBookOid: null });
        action('Continue to Style').onclick();
        await settle();
        expect(pb.__extractStateForTest().pb2WizardBookOid).toBe('PB2-B');
        expect(pb.__extractStateForTest().step).toBe(4);
    });
    it('no V3 record: stays on step 3 with an error toast, no route, dialog open', async () => {
        stubServer({});
        pb.__setExtractStateForTest({ step: 3, bookObjectId: 'GRP-1', pb2WizardBookOid: null });
        expect(await pb.continueToStyle()).toBeNull();
        expect(page.toast).toHaveBeenCalledWith('error', expect.stringMatching(/no V3 record/));
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
        expect(pb.__extractStateForTest().step).toBe(3);
    });
});

describe('step 4 "Continue to Book" — required style', () => {
    async function openStep4(styleDto) {
        stubServer({ pb2Books: ['PB2-A'], styles: { 'PB2-A': styleDto } });
        pb.__setExtractStateForTest({ step: 3, bookObjectId: 'GRP-1', pb2WizardBookOid: 'PB2-A' });
        await pb.continueToStyle();
        await settle();
    }
    it('no style description: disabled, the reason on the button and under the editor; the hand-off refuses', async () => {
        await openStep4(UNSTYLED);
        let a = action('Continue to Book');
        expect(a.disabled).toBe(true);
        expect(a.attrs.title).toBe('Fill from a preset or write a description, then Save');
        expect(JSON.stringify(pb.renderStep4())).toContain('Fill from a preset or write a description, then Save');
        expect(await pb.handOffToReader()).toBeNull();
        expect(routeSet).not.toHaveBeenCalled();
        expect(dialogClose).not.toHaveBeenCalled();
    });
    it('the inlined editor is the shared style editor, without its route-changing Back button', async () => {
        await openStep4(UNSTYLED);
        let text = JSON.stringify(pb.renderStep4());
        expect(text).toContain('"data-testid":"pb2-style-save"');
        expect(text).toContain('"data-testid":"pb2-style-description"');
        expect(text).not.toContain('"data-testid":"pb2-style-back"');
    });
    it('an unsaved description does not count: "Save the style to continue"', async () => {
        await openStep4(UNSTYLED);
        const style = await import('../features/pictureBookStyle.js');
        style.__setStyleStateForTest({ draft: { description: 'ink and watercolor' } });
        let a = action('Continue to Book');
        expect(a.disabled).toBe(true);
        expect(a.attrs.title).toBe('Save the style to continue');
    });
    it('a saved style description enables it; it closes the wizard and opens the V3 reader', async () => {
        await openStep4(STYLED);
        let a = action('Continue to Book');
        expect(a.disabled).toBe(false);
        a.onclick();
        await settle();
        expect(dialogClose).toHaveBeenCalledTimes(1);
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-A');
    });
    it('Back from step 4 returns to step 3', async () => {
        await openStep4(UNSTYLED);
        action('Back').onclick();
        expect(pb.__extractStateForTest().step).toBe(3);
    });
});

describe('renderProgressBar', () => {
    function labelsOf(vnode) {
        let out = [];
        (function walk(v) {
            if (!v) return;
            if (Array.isArray(v)) return v.forEach(walk);
            if (typeof v === 'string') return out.push(v);
            if (v.children) walk(v.children);
            else if (v.text != null) out.push(String(v.text));
        })(vnode);
        return out.filter(t => /^[A-Z][a-z]+$/.test(t));
    }
    // Intentionally changed (aiDocs/PictureBookUxConsistencyDesign.md §7.1): the wizard covers only Source › Scenes;
    // Characters, Style and the rest are the Edit workspace's sections, opened in guided mode after Step 2.
    it('ends at Scenes, then the book workspace — no Characters / Style / Images / View steps', () => {
        pb.__setExtractStateForTest({ step: 2 });
        let v = pb.renderProgressBar();
        let labels = labelsOf(v);
        expect(labels).toEqual(['Source', 'Scenes']);
        let div = document.createElement('div');
        m.render(div, v);
        expect(div.querySelector('[data-testid="pb-wizard-then"]').textContent).toBe('› then the book workspace');
    });
});

// ─────────────────────────── tryResumeExistingBook ──────────────────────────

describe('tryResumeExistingBook — existing books never re-enter the wizard', () => {
    it('a pb2 book oid (list / reader pencils) → the V3 reader route', async () => {
        stubServer({ pb2Books: ['PB2-A'], scenes: { 'PB2-A': [{ objectId: 's1' }] } });
        expect(await pb.tryResumeExistingBook('PB2-A')).toEqual({ route: '/picture-book/v2/PB2-A' });
        expect(pb.__extractStateForTest().step).toBe(1);
        expect(pb.__extractStateForTest().bookObjectId).toBeNull();
    });
    it('a PB1 group oid bridged via /pb2 (viewer pencil) → the V3 reader route', async () => {
        stubServer({ bridges: { 'GRP-1': 'PB2-B' }, scenes: { 'GRP-1': [{ objectId: 's1' }] } });
        expect(await pb.tryResumeExistingBook('GRP-1')).toEqual({ route: '/picture-book/v2/PB2-B' });
    });
    it('a pure PB1 book (scenes, no V3 record) → the read-only PB1 viewer route', async () => {
        stubServer({ scenes: { 'GRP-OLD': [{ objectId: 's1', imageObjectId: 'i1' }] } });
        expect(await pb.tryResumeExistingBook('GRP-OLD')).toEqual({ route: '/picture-book/GRP-OLD' });
        expect(pb.__extractStateForTest().step).toBe(1);
    });
    it('a fresh source document → null, the wizard opens at step 1', async () => {
        stubServer({});
        expect(await pb.tryResumeExistingBook('DOC-1')).toBeNull();
        expect(pb.__extractStateForTest().step).toBe(1);
    });
});

// ─────────────────────────────── PB1 viewer ────────────────────────────────

describe('PB1 viewer /picture-book/:groupOid — a bridge to the V3 reader', () => {
    beforeEach(() => {
        vi.spyOn(am7client, 'search').mockResolvedValue({ results: [] });
        pbf.__setViewerStateForTest({ viewerBookId: null, viewerScenes: [], viewerLoading: false, viewerError: null, viewerLegacy: false });
    });
    it('redirects (replace) to the V3 reader when the group has a pb2 record, without loading PB1 scenes', async () => {
        let calls = stubServer({ bridges: { 'GRP-1': 'PB2-B' }, scenes: { 'GRP-1': [{ objectId: 's1' }] } });
        await pbf.__loadViewerForTest('GRP-1');
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-B', null, { replace: true });
        expect(calls.some(u => /\/scenes$/.test(u))).toBe(false);
        expect(pbf.__viewerStateForTest().viewerLegacy).toBe(false);
    });
    it('a pb2 book oid handed straight to the route also redirects', async () => {
        stubServer({ pb2Books: ['PB2-A'] });
        await pbf.__loadViewerForTest('PB2-A');
        expect(routeSet).toHaveBeenCalledWith('/picture-book/v2/PB2-A', null, { replace: true });
    });
    it('a pure PB1 book shows read-only: legacy notice rendered, no Edit pencil', async () => {
        stubServer({ scenes: { 'GRP-OLD': [{ objectId: 's1', title: 'One' }, { objectId: 's2', title: 'Two' }] } });
        await pbf.__loadViewerForTest('GRP-OLD');
        let s = pbf.__viewerStateForTest();
        expect(routeSet).not.toHaveBeenCalled();
        expect(s.viewerScenes).toHaveLength(2);
        expect(s.viewerLegacy).toBe(true);
        expect(s.viewerLoading).toBe(false);
        let notice = pbf.__renderViewerLegacyNoticeForTest();
        expect(notice).not.toBeNull();
        expect(notice.attrs['data-pb1-legacy-notice']).toBe('1');
        expect(JSON.stringify(notice)).toMatch(/replaced by V3/);
        expect(JSON.stringify(notice)).toMatch(/Import the book/);
        expect(pbf.__renderViewerActionsLeftForTest({ fullscreen: false })).toBeNull();
    });
    it('a source document with no book yet is not legacy: empty state, no notice', async () => {
        stubServer({});
        await pbf.__loadViewerForTest('DOC-1');
        let s = pbf.__viewerStateForTest();
        expect(routeSet).not.toHaveBeenCalled();
        expect(s.viewerScenes).toHaveLength(0);
        expect(s.viewerLegacy).toBe(false);
        expect(pbf.__renderViewerLegacyNoticeForTest()).toBeNull();
    });
});

// ─────────────────────────── PB2 reader header pencil ───────────────────────

// aiDocs/PictureBookUxConsistencyDesign.md §5.1 / §5.4 (D2): Characters left the Read header — it is the Edit
// workspace's Characters section (the rail row), reached through the Read | Edit toggle.
describe('renderPb2Header — the Edit (wizard) pencil is never offered; Characters moved to the Edit rail', () => {
    beforeEach(() => {
        pbf.__pb2StopWatchingForTest();
        vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
        pbf.__setPb2V3StateForTest({
            pb2AutoApprove: false, pb2AutoApproveSaving: false, pb2Run: null, pb2ResumeRun: null, pb2Submitting: false,
            pb2CurrentPage: 1, pb2BookObjectId: 'B1', pb2BookName: "Harlot's Eight", pb2Covers: null, pb2IsChapBook: false,
            pb2Pages: [{ objectId: 's1', sceneIndex: 1, title: 'Arrival', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', dataObjectId: 'd1' }]
        });
    });
    afterEach(() => { pbf.__pb2StopWatchingForTest(); });
    function header() { let d = document.createElement('div'); m.render(d, pbf.renderPb2Header()); return d; }
    it('pencil hidden, Characters not in Read; the Edit toggle is the way in and the PictureBook rail has Characters', async () => {
        let el = header();
        expect(el.querySelectorAll('[data-pb2-edit]').length).toBe(0);
        expect(el.querySelectorAll('[data-pb2-characters]').length).toBe(0);
        expect(el.textContent).not.toContain('Characters');
        expect(el.querySelector('[data-testid="book-mode-edit"]')).not.toBeNull();
        let nav = await import('../workflows/bookWorkspaceNav.js');
        expect(nav.sectionDef('pb', 'characters')).toMatchObject({ id: 'characters', label: 'Characters' });
        expect(nav.editLink('pb', 'B1', { type: 'character', id: 'c1' })).toBe('/picture-book/B1/edit/characters/c1');
    });
});
