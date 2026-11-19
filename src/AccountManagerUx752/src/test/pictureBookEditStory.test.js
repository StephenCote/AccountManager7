// @vitest-environment jsdom
/**
 * Edit › Scenes › Story — the editable scene text (aiDocs/PictureBookUxConsistencyDesign.md §5.6, D7, §6.2-5).
 *
 * Why: Save sends one generic PATCH /rest/model with schema + identity + only the changed Story keys + userEdited:true,
 * never `name` (common.name's \S rule rejects a null/blank name and the whole write fails — .claude/rules/model-api.md),
 * and the PATCH result is checked: a non-2xx or a 2xx `false` keeps the draft and toasts, never reports success.
 * Story edits title / blurb / summary only (setting / action / mood live on olio.pb.composition — the Composition tab).
 * Rendered with the real Mithril renderer into jsdom; REST is the real client over a mocked fetch.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { __resetDirtyForTest } from '../components/dirtyGuard.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}
function flushPromises() { return new Promise(r => setTimeout(r, 0)); }
function restPath(u) { return String(u).replace(/^.*\/rest\//, '/rest/'); }

const REC = { id: 41, objectId: 's1', name: 'scene-1', title: 'Quay', blurb: 'The boats came in.', summary: 'Arrival', userEdited: false };
const PAGE = { objectId: 's1', sceneIndex: 1, title: 'Quay', sceneNodeObjectId: 'n1', nodeStatus: 'DONE', dataObjectId: 'd1' };

let pbf, edit, origFetch;

beforeEach(async () => {
    origFetch = global.fetch;
    pbf = await import('../features/pictureBook.js');
    edit = await import('../features/pictureBookEdit.js');
    __resetDirtyForTest();
    pbf.__pb2StopWatchingForTest();
    pbf.__setPb2V3StateForTest({ pb2BookObjectId: 'B1', pb2BookName: 'Quay', pb2Pages: [PAGE], pb2CurrentPage: 1, pb2Covers: null,
        pb2Run: null, pb2ResumeRun: null, pb2SceneDegradations: { n1: { loading: false, summary: null, error: null } } });
    edit.__resetPbEditForTest();
    edit.__setPbEditStateForTest({ sceneRecs: { s1: { loading: false, error: null, rec: Object.assign({}, REC) } } });
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    vi.spyOn(am7client, 'dotPath').mockReturnValue('Public');
});
afterEach(() => {
    edit.__resetPbEditForTest();
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

describe('buildStoryPatch — the Story PATCH body', () => {
    it('schema + identity + the changed Story keys + userEdited:true; never name, never a foreign key', () => {
        let body = edit.buildStoryPatch(REC, { title: 'The quay', blurb: '' });
        expect(body).toEqual({ schema: 'olio.pb.scene', id: 41, objectId: 's1', userEdited: true, title: 'The quay', blurb: '' });
        // a draft carrying name (or anything that is not a Story field) never sends it
        body = edit.buildStoryPatch(REC, { name: '', summary: 'Landfall', objectId: 'x', bogus: 1 });
        expect(body).toEqual({ schema: 'olio.pb.scene', id: 41, objectId: 's1', userEdited: true, summary: 'Landfall' });
        expect('name' in body).toBe(false);
    });
    it('userEdited is always true, even when the record says false', () => {
        expect(edit.buildStoryPatch(Object.assign({}, REC, { userEdited: false }), { title: 'x' }).userEdited).toBe(true);
        expect(edit.buildStoryPatch(Object.assign({}, REC, { userEdited: true }), { title: 'x' }).userEdited).toBe(true);
    });
    it('nothing to send → null (no record, no draft, or no Story key in the draft)', () => {
        expect(edit.buildStoryPatch(null, { title: 'x' })).toBeNull();
        expect(edit.buildStoryPatch(REC, null)).toBeNull();
        expect(edit.buildStoryPatch(REC, {})).toBeNull();
        expect(edit.buildStoryPatch(REC, { name: 'n' })).toBeNull();
    });
});

describe('saveStory — the PATCH result is checked', () => {
    function patchFetch(patchResponse) {
        let calls = [];
        global.fetch = vi.fn(async (u, init) => {
            let url = restPath(u);
            let method = (init && init.method) || 'GET';
            calls.push({ method, url, body: init && init.body ? JSON.parse(init.body) : undefined });
            if (method === 'PATCH' && url === '/rest/model') return patchResponse();
            if (/\/B1\/pages$/.test(url)) return jsonResponse(200, [Object.assign({}, PAGE, { nodeStatus: 'STALE' })]);
            if (/\/B1\/health$/.test(url)) return jsonResponse(404, { error: 'none' });
            if (/\/B1\/covers$/.test(url)) return jsonResponse(200, { bookObjectId: 'B1', covers: {} });
            return jsonResponse(404, { error: 'unexpected ' + method + ' ' + url });
        });
        return calls;
    }

    it('a non-ok PATCH response returns false, keeps the draft, toasts the status and does not re-read', async () => {
        edit.__setPbEditStateForTest({ storyDrafts: { s1: { title: 'The quay' } } });
        let search = vi.spyOn(am7client, 'search');
        let calls = patchFetch(() => jsonResponse(422, 'Validation of olio.pb.scene.title failed'));
        let ok = await edit.saveStory('s1');
        expect(ok).toBe(false);
        expect(calls.map(c => c.method + ' ' + c.url)).toEqual(['PATCH /rest/model']);
        expect(calls[0].body).toEqual({ schema: 'olio.pb.scene', id: 41, objectId: 's1', userEdited: true, title: 'The quay' });
        expect(edit.__pbEditStateForTest().storyDrafts.s1).toEqual({ title: 'The quay' });
        expect(edit.__pbEditStateForTest().storySaving).toBe(false);
        expect(page.toast).toHaveBeenCalledWith('error', 'Scene not saved (422: Validation of olio.pb.scene.title failed)');
        expect(page.toast).not.toHaveBeenCalledWith('success', expect.anything());
        expect(search).not.toHaveBeenCalled();
    });
    it('a 200 `false` (nothing written) returns false and keeps the draft', async () => {
        edit.__setPbEditStateForTest({ storyDrafts: { s1: { blurb: 'New text' } } });
        let calls = patchFetch(() => jsonResponse(200, 'false'));
        expect(await edit.saveStory('s1')).toBe(false);
        expect(calls.length).toBe(1);
        expect(edit.__pbEditStateForTest().storyDrafts.s1).toEqual({ blurb: 'New text' });
        expect(page.toast).toHaveBeenCalledWith('error', 'Scene not saved (200: false)');
    });
    it('a network failure returns false and keeps the draft', async () => {
        edit.__setPbEditStateForTest({ storyDrafts: { s1: { summary: 'S' } } });
        global.fetch = vi.fn(async () => { throw new Error('Failed to fetch'); });
        expect(await edit.saveStory('s1')).toBe(false);
        expect(edit.__pbEditStateForTest().storyDrafts.s1).toEqual({ summary: 'S' });
        expect(page.toast).toHaveBeenCalledWith('error', 'Scene not saved: Failed to fetch');
    });
    it('a `true` PATCH clears the draft, re-reads the scene (cache:false) and the pages — the stale chip is the server\'s', async () => {
        edit.__setPbEditStateForTest({ storyDrafts: { s1: { blurb: 'The boats did not come.' } } });
        let q = { entity: { request: [] }, cacheArg: null, fields: [], cache(v) { this.cacheArg = v; }, field(k, v) { this.fields.push([k, v]); }, range() {} };
        vi.spyOn(am7client, 'newQuery').mockReturnValue(q);
        vi.spyOn(am7client, 'search').mockResolvedValue({ results: [Object.assign({}, REC, { blurb: 'The boats did not come.', userEdited: true })] });
        let calls = patchFetch(() => jsonResponse(200, 'true'));
        expect(await edit.saveStory('s1')).toBe(true);
        expect(edit.__pbEditStateForTest().storyDrafts.s1).toBeUndefined();
        expect(q.cacheArg).toBe(false);
        expect(q.fields).toEqual([['objectId', 's1']]);
        expect(edit.__pbEditStateForTest().sceneRecs.s1.rec.userEdited).toBe(true);
        expect(calls.map(c => c.method + ' ' + c.url)).toEqual(['PATCH /rest/model',
            'GET /rest/olio/picture-book/B1/pages', 'GET /rest/olio/picture-book/B1/health', 'GET /rest/olio/picture-book/B1/covers',
            // the refresh drops the degradation cache and re-reads the open page's node (pb2RefreshPages)
            'GET /rest/olio/picture-book/B1/workflow/node/n1']);
        expect(pbf.__pb2V3StateForTest().pb2Pages[0].nodeStatus).toBe('STALE');
        expect(page.toast).toHaveBeenCalledWith('success', 'Scene saved');
    });
});

describe('Story tab — draft, dirty marks, Save', () => {
    const ctx = { kind: 'pb', bookObjectId: 'B1', section: 'scenes', item: 's1', tab: 'story', go: vi.fn(), select: vi.fn(), setTab: vi.fn() };
    function renderBody() { let d = document.createElement('div'); m.render(d, edit.scenesSection.body(ctx)); return d; }
    const q = (el, id) => el.querySelector('[data-testid="' + id + '"]');

    it('edits title / blurb / summary; an edit marks the field and the row dirty; Save sends only the changed key', async () => {
        let el = renderBody();
        ['pb-story-title', 'pb-story-blurb', 'pb-story-summary'].forEach(id => expect(q(el, id), id).not.toBeNull());
        expect(q(el, 'pb-story-title').value).toBe('Quay');
        expect(q(el, 'pb-story-save').disabled).toBe(true);
        let t = q(el, 'pb-story-title');
        t.value = 'The quay';
        t.dispatchEvent(new Event('input'));
        el = renderBody();
        expect(q(el, 'pb-story-dirty-title')).not.toBeNull();
        expect(q(el, 'pb-story-dirty-blurb')).toBeNull();
        expect(el.querySelector('[data-item-id="s1"] [data-row-dirty]')).not.toBeNull();
        expect(q(el, 'pb-story-save').disabled).toBe(false);
        let calls = [];
        global.fetch = vi.fn(async (u, init) => { calls.push({ method: init && init.method, body: init && init.body ? JSON.parse(init.body) : undefined }); return jsonResponse(500, 'boom'); });
        q(el, 'pb-story-save').click();
        for (let i = 0; i < 4; i++) await flushPromises();
        expect(calls[0]).toEqual({ method: 'PATCH', body: { schema: 'olio.pb.scene', id: 41, objectId: 's1', userEdited: true, title: 'The quay' } });
        // the failed save keeps the draft: still dirty after a re-render
        expect(q(renderBody(), 'pb-story-dirty-title')).not.toBeNull();
        // typing the original value back un-dirties the field
        el = renderBody();
        t = q(el, 'pb-story-title');
        t.value = 'Quay';
        t.dispatchEvent(new Event('input'));
        expect(q(renderBody(), 'pb-story-save').disabled).toBe(true);
    });
});
