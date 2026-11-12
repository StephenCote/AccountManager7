// @vitest-environment jsdom
/**
 * ChapBook review — the "N of N pages have no LLM landscape prompt" toast fires once per book load (P10-D 5, §6.C 266 j).
 *
 * Why: loadReviewBook toasts placeholderPromptSummary(reviewScenes) on every call, and it is also the reload
 * after Split / Merge / Remove — so a split showed the summary twice (once on entry, once after the reload,
 * with the new page count). The review now remembers the book it has toasted for this entry
 * (placeholderToastedFor) and ChapBookReview.oninit resets it, so re-entering the review says it again.
 *
 * The real oninit → loadReviewBook load path and the real doSplitScene → reload path run; only fetch
 * (book search, /pages, /covers, scene searches, the split POST) and the SD-panel priming are stubbed.
 * page.toast is spied, not replaced: the assertions are about how many times it is asked for the summary.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7sd } from '../components/sdConfig.js';
import { applicationPath } from '../core/config.js';

const BOOK = 'cb-toast-1';
const BOOK2 = 'cb-toast-2';
const PB = '/rest/olio/picture-book/';

function jsonResponse(status, body) {
    let text = JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
async function waitFor(cond, ms) {
    let until = Date.now() + (ms || 2000);
    while (!cond()) {
        if (Date.now() > until) throw new Error('waitFor timed out');
        await new Promise(r => setTimeout(r, 2));
    }
}
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}

/** Per-book page state the router serves; a split appends a page (the server splits the stanza at its midpoint). */
let pagesByBook;
let calls;
function pageWire(oid, idx, stanza) {
    return { objectId: oid, id: 100 + idx, groupId: 77, name: 'p' + idx, sceneIndex: idx, title: 'Page ' + idx, poemStanza: stanza, sdPrompt: 'landscape, Winter, calm atmosphere', promptLocked: false };
}
function router() {
    global.fetch = vi.fn(async (u, init) => {
        let url = String(u).replace(applicationPath, '');
        let method = (init && init.method) || 'GET';
        let body = init && init.body ? JSON.parse(init.body) : null;
        calls.push(method + ' ' + url);
        let bookMatch = url.match(/\/picture-book\/([^/]+)\/(pages|covers)$/);
        if (method === 'POST' && /\/rest\/model\/search$/.test(url) && body && body.type === 'olio.pb.book') {
            let oid = body.fields[0].value;
            return jsonResponse(200, [{ objectId: oid, id: oid === BOOK ? 5 : 6, name: 'Book ' + oid, slug: oid, bookStatus: 'draft', fixPageHeight: false }]);
        }
        if (method === 'POST' && /\/rest\/model\/search$/.test(url) && body && body.type === 'olio.pb.scene') {
            // loadSceneFields (by objectId, recordCount 1) and loadSceneOverrides (by groupId) both read scenes.
            let all = [].concat(pagesByBook[BOOK], pagesByBook[BOOK2]);
            let byOid = body.fields.find(f => f.name === 'objectId');
            return jsonResponse(200, byOid ? all.filter(p => p.objectId === byOid.value) : all);
        }
        if (method === 'GET' && bookMatch && bookMatch[2] === 'pages') return jsonResponse(200, pagesByBook[bookMatch[1]] || []);
        if (method === 'GET' && bookMatch && bookMatch[2] === 'covers') return jsonResponse(404, { error: 'no covers' });
        let split = url.match(/\/chap-book\/scene\/([^/]+)\/split$/);
        if (method === 'POST' && split) {
            let list = pagesByBook[BOOK];
            let idx = list.findIndex(p => p.objectId === split[1]);
            list.splice(idx + 1, 0, pageWire(split[1] + '-b', list.length + 1, 'Dry damp clothes\nand warm cold bones'));
            return jsonResponse(200, { objectId: split[1] + '-b' });
        }
        return jsonResponse(404, { error: 'unmocked ' + method + ' ' + url });
    });
}
function summaryToasts() {
    return page.toast.mock.calls.filter(c => c[0] === 'warn' && /no LLM landscape prompt/.test(String(c[1]))).map(c => c[1]);
}
async function loaded(cb) {
    await waitFor(() => !/Loading scenes/.test(textOf(cb.ChapBookReview.view())), 4000);
    expect(textOf(cb.ChapBookReview.view())).not.toMatch(/Error:/);
}

let cb, origFetch, origUser;
beforeEach(async () => {
    origFetch = global.fetch;
    origUser = page.user;
    page.user = { organizationId: 2 };           // loadSceneOverrides needs an org to issue its batch read
    calls = [];
    pagesByBook = {};
    pagesByBook[BOOK] = [pageWire('s1', 1, 'Outside, all is pristine,\nFrom cobalt skies\nDescending upon snow\nTo silver veins'), pageWire('s2', 2, 'Inside hearts\nand hearths\nand homes')];
    pagesByBook[BOOK2] = [pageWire('t1', 1, 'Ochre embers\nand ebon cinders')];
    router();
    cb = await import('../features/chapBook.js');
    vi.spyOn(am7sd, 'fetchModels').mockResolvedValue([]);
    vi.spyOn(am7sd, 'fetchLoras').mockResolvedValue([]);
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(console, 'warn').mockImplementation(() => {});    // the un-stubbed /settings priming warns
    cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewScenes: [], reviewBook: null, placeholderToastedFor: null });
});
afterEach(() => {
    cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewBook: null, reviewScenes: [], reviewSceneImageUrls: {}, reviewCovers: null, placeholderToastedFor: null });
    global.fetch = origFetch;
    page.user = origUser;
    vi.restoreAllMocks();
});

describe('landscape-prompt placeholder toast — once per book load (266 j)', () => {
    it('entry toasts the summary once; the reload after a Split (page count changed) does not toast again', async () => {
        cb.ChapBookReview.oninit({ attrs: { bookObjectId: BOOK } });
        await loaded(cb);
        expect(summaryToasts()).toEqual([expect.stringMatching(/^2 of 2 pages have no LLM landscape prompt/)]);
        expect(calls.filter(c => c === 'GET ' + PB + BOOK + '/pages').length).toBe(1);

        await cb.__doSplitSceneForTest(0);
        expect(calls).toContain('POST /rest/olio/chap-book/scene/s1/split');
        expect(calls.filter(c => c === 'GET ' + PB + BOOK + '/pages').length, 'the split reloaded the review').toBe(2);
        expect(page.toast.mock.calls.map(c => c[1])).toContain('Scene split');
        // Pre-fix: a second summary, now reading "3 of 3 pages …" (a different message, so the toast region's
        // duplicate-collapse would not have caught it either).
        expect(summaryToasts().length, 'summary toasts after the split reload: ' + JSON.stringify(summaryToasts())).toBe(1);
        expect(cb.__reviewPageCountForTest()).toBe(3);
    });

    it('re-entering the review (oninit) for the same book says it again — once', async () => {
        cb.ChapBookReview.oninit({ attrs: { bookObjectId: BOOK } });
        await loaded(cb);
        expect(summaryToasts().length).toBe(1);
        cb.ChapBookReview.oninit({ attrs: { bookObjectId: BOOK } });
        await loaded(cb);
        expect(summaryToasts().length).toBe(2);
    });

    it('a different book is its own load: it toasts once even without a fresh oninit reset', async () => {
        cb.ChapBookReview.oninit({ attrs: { bookObjectId: BOOK } });
        await loaded(cb);
        expect(summaryToasts().length).toBe(1);
        // Point the (already-entered) review at another book and reload it directly, no oninit in between.
        cb.__setReviewStateForTest({ reviewBookObjectId: BOOK2 });
        await cb.__loadReviewBookForTest(BOOK2);
        expect(summaryToasts()).toEqual([
            expect.stringMatching(/^2 of 2 pages/),
            expect.stringMatching(/^1 of 1 page has no LLM landscape prompt/)
        ]);
        await cb.__loadReviewBookForTest(BOOK2);
        expect(summaryToasts().length, 'a plain reload of the second book stays quiet').toBe(2);
    });

    it('a book whose pages all have LLM prompts never toasts, before or after a reload', async () => {
        pagesByBook[BOOK].forEach(p => { p.sdPrompt = 'a serene alpine lake at dawn, oil on canvas'; });
        cb.ChapBookReview.oninit({ attrs: { bookObjectId: BOOK } });
        await loaded(cb);
        await cb.__loadReviewBookForTest(BOOK);
        expect(summaryToasts()).toEqual([]);
    });
});
