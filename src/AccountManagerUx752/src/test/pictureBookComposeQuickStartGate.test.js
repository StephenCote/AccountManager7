// @vitest-environment jsdom
/**
 * Compose Quick start renders a draft of every composed scene on the SD server, so it carries the scene-render
 * style gate (2026-11-16; server: PbServiceFacade.requestCompose 409 STYLE_REQUIRED_MESSAGE). The compose dialog
 * reads the book's style and type when it opens: a non-ChapBook book with no style description gets a disabled
 * Quick start with "Set the book style first" and a link to the style view; a described book or a ChapBook (its
 * look comes from its SD config) keeps Quick start; an unreadable book never blocks.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';

const BOOK = 'book-1';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function findAll(vnode, pred, out) {
    out = out || [];
    if (!vnode) return out;
    if (Array.isArray(vnode)) { vnode.forEach(v => findAll(v, pred, out)); return out; }
    if (typeof vnode !== 'object') return out;
    if (vnode.attrs && pred(vnode)) out.push(vnode);
    if (vnode.children) findAll(vnode.children, pred, out);
    return out;
}
function byTestId(vnode, id) { return findAll(vnode, v => v.attrs['data-testid'] === id); }
function flush() { return new Promise(r => setTimeout(r, 0)); }

let cmp;
let origFetch;

/** Stub GET /{book}/style and the olio.pb.book search for bookType. */
function stubBook(description, bookType) {
    global.fetch = vi.fn(async (u) => {
        if (/\/book-1\/style$/.test(String(u))) {
            return jsonResponse(200, { bookObjectId: BOOK, effective: { fields: description == null ? {} : { description } } });
        }
        return jsonResponse(404, 'unexpected ' + u);
    });
    return vi.spyOn(am7client, 'search').mockResolvedValue({ results: [{ objectId: BOOK, bookType }] });
}

beforeEach(async () => {
    origFetch = global.fetch;
    cmp = await import('../features/pictureBookCompose.js');
    cmp.__setComposeStateForTest({ bookObjectId: BOOK, sceneObjectId: null, pages: [], board: null, busy: {}, composeRun: null, chatConfigRef: null, chatConfigResolving: false });
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m, 'redraw').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
});

afterEach(() => {
    cmp.__setComposeStateForTest({ bookObjectId: null, busy: {}, composeRun: null });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

describe('quickStartStyleBlocked — the Quick start style predicate', () => {
    it('a STORY book with no style description is blocked; the bookType is requested', async () => {
        let search = stubBook(null, 'story');
        expect(await cmp.quickStartStyleBlocked(BOOK)).toBe(true);
        expect(search.mock.calls[0][0].entity.request).toContain('bookType');
    });
    it('a described style is allowed', async () => {
        stubBook('ink wash, muted palette', 'STORY');
        expect(await cmp.quickStartStyleBlocked(BOOK)).toBe(false);
    });
    it('a ChapBook with no style is allowed (its look comes from its SD config)', async () => {
        stubBook(null, 'chapbook');
        expect(await cmp.quickStartStyleBlocked(BOOK)).toBe(false);
    });
    it('an unreadable book is unknown (null): never blocks, the server is the final gate', async () => {
        stubBook(null, 'STORY').mockRejectedValue(new Error('search failed'));
        expect(await cmp.quickStartStyleBlocked(BOOK)).toBeNull();
    });
});

describe('compose dialog — Quick start under the style gate', () => {
    async function openDialog() {
        let cfg = null;
        vi.spyOn(Dialog, 'open').mockImplementation(c => { cfg = c; });
        vi.spyOn(Dialog, 'close').mockImplementation(() => {});
        cmp.__openComposeDialogForTest();
        await flush();
        await flush();
        return cfg;
    }

    it('style-less STORY book: Quick start disabled with the reason; the link opens the style view', async () => {
        stubBook(null, 'STORY');
        let cfg = await openDialog();
        let view = cfg.content.view();
        let box = byTestId(view, 'pb-cmp-quickstart')[0];
        expect(box.attrs.disabled).toBe(true);
        expect(box.attrs.title).toBe('Set the book style first');
        let link = byTestId(view, 'pb-cmp-quickstart-needs-style');
        expect(link.length).toBe(1);
        link[0].attrs.onclick();
        expect(Dialog.close).toHaveBeenCalled();
        expect(m.route.set).toHaveBeenCalledWith('/picture-book/' + BOOK + '/style');
    });

    it('described book: Quick start enabled, no link', async () => {
        stubBook('ink wash, muted palette', 'STORY');
        let view = (await openDialog()).content.view();
        expect(byTestId(view, 'pb-cmp-quickstart')[0].attrs.disabled).toBe(false);
        expect(byTestId(view, 'pb-cmp-quickstart-needs-style').length).toBe(0);
    });

    it('ChapBook with no style: Quick start enabled, no link', async () => {
        stubBook(null, 'CHAPBOOK');
        let view = (await openDialog()).content.view();
        expect(byTestId(view, 'pb-cmp-quickstart')[0].attrs.disabled).toBe(false);
        expect(byTestId(view, 'pb-cmp-quickstart-needs-style').length).toBe(0);
    });

    it('blocked: a checked Quick start is cleared and Start never sends quickStart', async () => {
        stubBook(null, 'STORY');
        let cfg = await openDialog();
        let box = byTestId(cfg.content.view(), 'pb-cmp-quickstart')[0];
        box.attrs.onchange({ target: { checked: true } });
        expect(byTestId(cfg.content.view(), 'pb-cmp-quickstart')[0].attrs.checked).toBe(false);
        let posts = [];
        let base = global.fetch;
        global.fetch = vi.fn(async (u, init) => {
            if (init && init.method === 'POST') { posts.push({ url: String(u), body: init.body }); return jsonResponse(200, { submitted: false, reason: 'The book has no scenes' }); }
            return base(u, init);
        });
        cfg.actions.find(a => a.label === 'Start').onclick();
        await flush();
        await flush();
        let compose = posts.filter(p => /\/compose$/.test(p.url));
        expect(compose.length).toBe(1);
        expect(JSON.parse(compose[0].body || '{}').quickStart).toBeUndefined();
    });
});
