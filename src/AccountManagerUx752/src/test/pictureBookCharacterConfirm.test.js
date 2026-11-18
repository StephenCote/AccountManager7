// @vitest-environment jsdom
/**
 * Manage Characters: merge and delete confirm through the in-page Dialog.confirm, never the
 * browser's native window.confirm (which looked nothing like the rest of the app). Both actions
 * delete records, so Cancel must send nothing and Confirm must send exactly the one request.
 * fetch is stubbed — this checks the client flow, not the server endpoints.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Dialog } from '../components/dialogCore.js';

function jsonResponse(status, body) {
    return { ok: status >= 200 && status < 300, status, json: async () => body };
}

let pc;
let origFetch;
let calls;

beforeEach(async () => {
    origFetch = global.fetch;
    calls = [];
    global.fetch = vi.fn(async (url, init) => {
        calls.push({ url: String(url), method: (init && init.method) || 'GET' });
        if ((init && init.method) === 'DELETE') return jsonResponse(200, { deleted: true, deletedName: 'a sigh', scenesDetached: 2 });
        if (String(url).includes('/characters/merge')) return jsonResponse(200, { keptName: 'Dad', mergedNames: ['the father'], scenesRepointed: 3, failedDeletes: [] });
        return jsonResponse(200, []);
    });
    pc = await import('../workflows/pictureBookCharacters.js');
    pc.__setPbCharStateForTest({
        bookObjectId: 'book-1',
        selectedObjectId: 'keep-1',
        characters: [{ objectId: 'keep-1', name: 'Dad' }, { objectId: 'drop-1', name: 'the father' }],
        mergeSelection: { 'drop-1': true }
    });
    vi.spyOn(window, 'confirm').mockImplementation(() => { throw new Error('native window.confirm used'); });
});

afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

const mutating = () => calls.filter(c => c.method === 'DELETE' || c.url.includes('/characters/merge'));

describe('delete character', () => {
    it('Cancel in the in-page dialog deletes nothing', async () => {
        let spy = vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        await pc.__pbCharDeleteSelectedForTest();
        expect(spy).toHaveBeenCalledTimes(1);
        let cfg = spy.mock.calls[0][0];
        expect(cfg.title).toBe('Delete Character');
        expect(cfg.message).toContain('Delete "Dad"');
        expect(cfg.destructive).toBe(true);
        expect(cfg.confirmLabel).toBe('Delete');
        expect(mutating()).toHaveLength(0);
        expect(window.confirm).not.toHaveBeenCalled();
    });

    it('Confirm sends the one DELETE', async () => {
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        await pc.__pbCharDeleteSelectedForTest();
        let dels = mutating();
        expect(dels).toHaveLength(1);
        expect(dels[0].method).toBe('DELETE');
        expect(dels[0].url).toContain('/book-1/character/keep-1');
        expect(window.confirm).not.toHaveBeenCalled();
    });
});

describe('merge characters', () => {
    it('Cancel in the in-page dialog merges nothing', async () => {
        let spy = vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        await pc.__pbCharMergeSelectedForTest();
        let cfg = spy.mock.calls[0][0];
        expect(cfg.title).toBe('Merge Characters');
        expect(cfg.message).toContain('Merge the father into "Dad"?');
        expect(cfg.destructive).toBe(true);
        expect(cfg.confirmLabel).toBe('Merge');
        expect(mutating()).toHaveLength(0);
        expect(window.confirm).not.toHaveBeenCalled();
    });

    it('Confirm sends the one merge POST', async () => {
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        await pc.__pbCharMergeSelectedForTest();
        let merges = mutating();
        expect(merges).toHaveLength(1);
        expect(merges[0].url).toContain('/book-1/characters/merge');
        expect(window.confirm).not.toHaveBeenCalled();
    });
});

describe('the real Dialog.confirm (not mocked)', () => {
    it('opens an in-page dialog and Cancel resolves false without closing what is underneath', async () => {
        Dialog.closeAll();
        Dialog.open({ title: 'Manage Characters', content: null });   // the manager the confirm stacks over
        expect(Dialog.stackDepth()).toBe(1);
        let p = pc.__pbCharDeleteSelectedForTest();
        await Promise.resolve();
        expect(Dialog.stackDepth()).toBe(2);   // the confirm is an in-page dialog on top
        // Cancel = close the top dialog (what the Cancel button does)
        Dialog.close();
        await p;
        expect(Dialog.stackDepth()).toBe(1);   // the manager is still open
        expect(mutating()).toHaveLength(0);
        expect(window.confirm).not.toHaveBeenCalled();
        Dialog.closeAll();
    });
});
