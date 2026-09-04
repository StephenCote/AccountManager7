// @vitest-environment jsdom
//
// Issue 2b/2c: the CREATE and RE-ANALYZE flows must forward the chosen chat config NAME to the
// backend so theme analysis runs against the user's selected LLM config; when no name is chosen the
// field is omitted and the backend applies its deterministic default. These tests exercise the two
// client contracts (createChapBook, analyzePoem) directly by mocking global.fetch.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

describe('createChapBook — Issue 2b chatConfig in POST body', () => {
    let fetchMock, origFetch;
    beforeEach(() => {
        origFetch = global.fetch;
        fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ objectId: 'book-1', slug: 'x' }) }));
        global.fetch = fetchMock;
    });
    afterEach(() => { global.fetch = origFetch; });

    it('includes chatConfig when a name is provided', async () => {
        let { createChapBook } = await import('../features/chapBook.js');
        await createChapBook('slug', 'Title', ['p1', 'p2'], 8, 'contentAnalysis');
        expect(fetchMock).toHaveBeenCalledTimes(1);
        let [url, opts] = fetchMock.mock.calls[0];
        expect(url).toContain('/create');
        expect(opts.method).toBe('POST');
        let body = JSON.parse(opts.body);
        expect(body.chatConfig).toBe('contentAnalysis');
        expect(body.slug).toBe('slug');
        expect(body.title).toBe('Title');
        expect(body.poemObjectIds).toEqual(['p1', 'p2']);
        expect(body.maxLinesPerPage).toBe(8);
    });

    it('omits chatConfig when no name is provided (backend applies its default)', async () => {
        let { createChapBook } = await import('../features/chapBook.js');
        await createChapBook('slug', 'Title', ['p1'], 8);
        let body = JSON.parse(fetchMock.mock.calls[0][1].body);
        expect('chatConfig' in body).toBe(false);
    });

    it('omits chatConfig when the name is an empty string', async () => {
        let { createChapBook } = await import('../features/chapBook.js');
        await createChapBook('slug', 'Title', ['p1'], 8, '');
        let body = JSON.parse(fetchMock.mock.calls[0][1].body);
        expect('chatConfig' in body).toBe(false);
    });
});

describe('analyzePoem — Issue 2c chatConfig in POST body', () => {
    let fetchMock, origFetch;
    beforeEach(() => {
        origFetch = global.fetch;
        fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ theme: 'x' }) }));
        global.fetch = fetchMock;
    });
    afterEach(() => { global.fetch = origFetch; });

    it('includes chatConfig and targets /analyze/{poemObjectId} when a name is provided', async () => {
        let { analyzePoem } = await import('../features/chapBook.js');
        await analyzePoem('poem-1', 'generalChat');
        expect(fetchMock).toHaveBeenCalledTimes(1);
        let [url, opts] = fetchMock.mock.calls[0];
        expect(url).toContain('/analyze/poem-1');
        expect(opts.method).toBe('POST');
        let body = JSON.parse(opts.body);
        expect(body.chatConfig).toBe('generalChat');
    });

    it('sends an empty body (no chatConfig) when none is provided', async () => {
        let { analyzePoem } = await import('../features/chapBook.js');
        await analyzePoem('poem-2');
        let body = JSON.parse(fetchMock.mock.calls[0][1].body);
        expect('chatConfig' in body).toBe(false);
    });
});
