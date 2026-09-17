/**
 * Issue 3, client half: merging duplicate extracted characters.
 *
 * The chunked extractor refers to an unnamed character differently in different chunks ("Darby's
 * dad", "the father", "Dad"). The server canonicalises the spellings that are unambiguously the
 * same person and reduces how many the model invents (by threading the established-name roster into
 * the chunk prompt), but a bare relation in a book with two families genuinely cannot be resolved
 * automatically — those are left as separate characters on purpose, and this is the repair path.
 *
 * What matters on the client side:
 *  1. The request shape the server contract requires (keepObjectId + mergeObjectIds).
 *  2. A failure surfaces the SERVER's message, not a generic status line — a merge deletes records
 *     and rewrites scenes, so "403 Not authorized for this book" must not become "failed: 403".
 *  3. The result is returned intact, because it reports what actually moved (scenesRepointed,
 *     failedDeletes) rather than a bare success flag.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

let fetchMock;

beforeEach(function () {
    fetchMock = vi.fn();
    global.fetch = fetchMock;
});

function jsonResponse(body, ok, status) {
    return {
        ok: ok !== false,
        status: status || 200,
        json: async function () { return body; }
    };
}

describe('mergeCharacters client', function () {
    it('posts keepObjectId and mergeObjectIds to the book merge endpoint', async function () {
        const { mergeCharacters } = await import('../workflows/sceneExtractor.js');
        fetchMock.mockResolvedValue(jsonResponse({
            keptName: "Darby's dad", mergedNames: ['the father', 'Dad'],
            scenesRepointed: 7, metaUpdated: true, failedDeletes: []
        }));

        const result = await mergeCharacters('book-oid', 'keep-oid', ['drop-1', 'drop-2']);

        expect(fetchMock).toHaveBeenCalledTimes(1);
        const [url, opts] = fetchMock.mock.calls[0];
        expect(url).toContain('/book-oid/characters/merge');
        expect(opts.method).toBe('POST');
        // credentials: the endpoint is @RolesAllowed, so the session cookie has to go with it.
        expect(opts.credentials).toBe('include');
        const body = JSON.parse(opts.body);
        expect(body.keepObjectId).toBe('keep-oid');
        expect(body.mergeObjectIds).toEqual(['drop-1', 'drop-2']);

        // Returned intact — scenesRepointed and failedDeletes are what the UI reports.
        expect(result.scenesRepointed).toBe(7);
        expect(result.keptName).toBe("Darby's dad");
    });

    it('surfaces the server message on failure rather than a bare status', async function () {
        const { mergeCharacters } = await import('../workflows/sceneExtractor.js');
        fetchMock.mockResolvedValue(jsonResponse(
            { error: true, message: 'Not authorized for this book' }, false, 403));

        await expect(mergeCharacters('book-oid', 'keep-oid', ['drop-1']))
            .rejects.toThrow('Not authorized for this book');
    });

    it('still throws usefully when the error body is not JSON', async function () {
        const { mergeCharacters } = await import('../workflows/sceneExtractor.js');
        fetchMock.mockResolvedValue({
            ok: false,
            status: 500,
            json: async function () { throw new Error('not json'); }
        });

        await expect(mergeCharacters('book-oid', 'keep-oid', ['drop-1']))
            .rejects.toThrow('500');
    });
});
