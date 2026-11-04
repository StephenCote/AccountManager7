import { describe, it, expect, vi, afterEach } from 'vitest';
import { buildGraph } from '../workflows/pictureBookWorkflow.js';

// P10-B 1: POST /{book}/graph/build (PictureBookService.buildGraph → PbServiceFacade.requestBuildGraph →
// buildSceneGraph). The server DTO is {bookObjectId, workflowObjectId, sceneCount, nodesMaterialized};
// the client normalizes the two counts to numbers so the canvas can branch on them. Bodies match the
// facade's DTO keys (PbServiceFacade.buildSceneGraph, 2026-11-04).

afterEach(() => { vi.restoreAllMocks(); });

function mockResp(status, body) {
    global.fetch = vi.fn(async () => ({
        ok: status >= 200 && status < 300, status,
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
        json: async () => (typeof body === 'string' ? JSON.parse(body) : body),
    }));
}

describe('buildGraph', () => {
    it('POSTs to /{book}/graph/build and returns the DTO with numeric counts', async () => {
        mockResp(200, { bookObjectId: 'b1', workflowObjectId: 'w1', sceneCount: 3, nodesMaterialized: 3 });
        let r = await buildGraph('b1');
        expect(global.fetch).toHaveBeenCalledTimes(1);
        let [url, opts] = global.fetch.mock.calls[0];
        expect(url).toMatch(/\/rest\/olio\/picture-book\/b1\/graph\/build$/);
        expect(opts.method).toBe('POST');
        expect(r).toEqual({ bookObjectId: 'b1', workflowObjectId: 'w1', sceneCount: 3, nodesMaterialized: 3 });
    });
    it('is idempotent on the wire: a second call answers nodesMaterialized 0 and the client keeps it 0 (not undefined)', async () => {
        mockResp(200, { bookObjectId: 'b1', workflowObjectId: 'w1', sceneCount: 3, nodesMaterialized: 0 });
        let r = await buildGraph('b1');
        expect(r.nodesMaterialized).toBe(0);
        expect(r.sceneCount).toBe(3);
    });
    it('defaults missing counts to 0 so the canvas never branches on undefined', async () => {
        mockResp(200, { bookObjectId: 'b1', workflowObjectId: 'w1' });
        let r = await buildGraph('b1');
        expect(r.sceneCount).toBe(0);
        expect(r.nodesMaterialized).toBe(0);
    });
    it('throws the server message with the status on 404 / 403 / 409 (same shape the reader toasts)', async () => {
        mockResp(409, { error: 'This book is not on the V3 render path' });
        await expect(buildGraph('b1')).rejects.toThrow('(409) This book is not on the V3 render path');
        mockResp(403, { error: 'Not authorized to update this book' });
        await expect(buildGraph('b1')).rejects.toThrow(/^\(403\)/);
        mockResp(404, { error: 'Book not found' });
        await expect(buildGraph('b1')).rejects.toThrow('(404) Book not found');
    });
});
