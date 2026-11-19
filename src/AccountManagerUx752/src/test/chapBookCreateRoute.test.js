// @vitest-environment jsdom
/**
 * ChapBook create → Edit, guided (aiDocs/PictureBookUxConsistencyDesign.md §7.2, slice U4).
 *
 * Why: a successful create used to land on the reader (`/chap-book/read/:id`), so the next thing the user had to do —
 * check the pages, set the style, render — was a Review button away. It now routes to `/chap-book/edit/:id?guided=1`,
 * where the workspace opens the next incomplete section (D6), and the create dialog's chat config becomes the session
 * default Edit › Book shows. The create job and its poller are stubbed (the job layer has its own tests); the POST body
 * is the real one.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../workflows/sceneExtractor.js', async function (importOriginal) {
    let actual = await importOriginal();
    return Object.assign({}, actual, {
        listJobs: vi.fn(async () => []),
        pollJob: vi.fn(async () => ({ status: 'completed', result: { objectId: 'NEW-BOOK', slug: 'tidewater' } }))
    });
});

import m from 'mithril';
import { page } from '../core/pageClient.js';

function jsonResponse(status, body) {
    let text = JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => body };
}

let cb;
let origFetch;

beforeEach(async () => {
    origFetch = global.fetch;
    cb = await import('../features/chapBook.js');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m.route, 'set').mockImplementation(() => {});
});

afterEach(() => {
    global.fetch = origFetch;
    cb.__setCreateStateForTest({ selectedIds: [], createSlug: '', createTitle: '', createChatConfigRef: null, configChatConfigRef: null, renderSdConfigInst: null, renderSdConfigBookId: null, creating: false });
    vi.restoreAllMocks();
});

describe('doCreateChapBook — success opens the new book in Edit, guided', () => {
    it('POSTs the async create, then routes to /chap-book/edit/:id?guided=1 (not the reader) and carries the chat config', async () => {
        let posts = [];
        global.fetch = vi.fn(async (u, init) => {
            if (init && init.method === 'POST') posts.push({ url: String(u), body: JSON.parse(init.body) });
            if (/\/chap-book\/create\?async=true$/.test(String(u))) return jsonResponse(202, { jobId: 'job-1', status: 'queued' });
            return jsonResponse(404, { error: 'unmocked' });
        });
        cb.__setCreateStateForTest({
            selectedIds: ['p1', 'p2'], createSlug: 'tidewater', createTitle: 'Tidewater', createMaxLines: 6,
            createChatConfigRef: { name: 'poetChat', objectId: 'cc-1' }, configChatConfigRef: null,
            renderSdConfigInst: null, renderSdConfigBookId: null, creating: false
        });

        await cb.__doCreateChapBookForTest();

        expect(posts.length).toBe(1);
        expect(posts[0].body).toMatchObject({ slug: 'tidewater', title: 'Tidewater', poemObjectIds: ['p1', 'p2'], maxLinesPerPage: 6, chatConfig: 'poetChat' });
        expect(m.route.set).toHaveBeenCalledTimes(1);
        expect(m.route.set).toHaveBeenCalledWith('/chap-book/edit/NEW-BOOK?guided=1');
        expect(m.route.set.mock.calls[0][0]).not.toMatch(/\/chap-book\/read\//);
        expect(page.toast).toHaveBeenCalledWith('success', 'ChapBook created: tidewater');
        // The create's chat config is this session's default for the new book (Edit › Book, "for this session").
        expect(cb.__editStateForTest().configChatConfigRef).toEqual({ name: 'poetChat', objectId: 'cc-1' });
    });

    it('a failed create routes nowhere', async () => {
        global.fetch = vi.fn(async () => jsonResponse(500, { error: 'boom' }));
        cb.__setCreateStateForTest({ selectedIds: ['p1'], createSlug: 's', createTitle: 'T', creating: false });
        await cb.__doCreateChapBookForTest();
        expect(m.route.set).not.toHaveBeenCalled();
        expect(page.toast).toHaveBeenCalledWith('error', expect.stringMatching(/^Failed to create: /));
    });
});
