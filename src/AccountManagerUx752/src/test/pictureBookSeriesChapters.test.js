import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { detectBoundaries, createChapter, listSeriesBooks } from '../workflows/pictureBookWorkflow.js';
import { groupBooksBySeries } from '../workflows/pictureBookSeries.js';

// N3/N4 unit coverage. The REST client is exercised with a mocked fetch so we can assert the exact
// request the backend receives — the sourceRange "no inner schema key" rule is load-bearing
// (an inner schema would defeat PictureBookService.ensureSchema top-level injection).

let lastCall = null;

function mockFetch(responseJson, ok = true, status = 200) {
    lastCall = null;
    global.fetch = vi.fn(async (url, init) => {
        lastCall = { url, init };
        return {
            ok, status,
            json: async () => responseJson,
            text: async () => JSON.stringify(responseJson),
        };
    });
}

beforeEach(() => { lastCall = null; });
afterEach(() => { vi.restoreAllMocks(); });

describe('detectBoundaries (N3)', () => {
    it('GETs the detect-boundaries endpoint with the encoded sourceDataObjectId', async () => {
        mockFetch([{ startOffset: 0, endOffset: 100, title: null }]);
        let result = await detectBoundaries('abc 123/def');
        expect(lastCall.url).toContain('/rest/olio/picture-book/chapter/detect-boundaries?sourceDataObjectId=');
        // encodeURIComponent must be applied
        expect(lastCall.url).toContain(encodeURIComponent('abc 123/def'));
        expect(lastCall.init.credentials).toBe('include');
        expect(Array.isArray(result)).toBe(true);
        expect(result[0].endOffset).toBe(100);
    });

    it('throws with the HTTP status on a non-ok response', async () => {
        mockFetch({}, false, 500);
        await expect(detectBoundaries('x')).rejects.toThrow(/detectBoundaries failed: 500/);
    });
});

describe('createChapter body building (N3)', () => {
    function bodyOf() { return JSON.parse(lastCall.init.body); }

    it('sends a minimal body (slug only) with fromBookObjectId when provided', async () => {
        mockFetch({ slug: 'ch-2' });
        await createChapter('BOOK-OID', 'ch-2');
        let body = bodyOf();
        expect(body.slug).toBe('ch-2');
        expect(body.fromBookObjectId).toBe('BOOK-OID');
        expect(body.sourceRange).toBeUndefined();
        expect(lastCall.url).toContain('/rest/olio/picture-book/chapter');
        expect(lastCall.init.method).toBe('POST');
    });

    it('omits fromBookObjectId when no source book is passed (series-first path)', async () => {
        mockFetch({ slug: 'ch-1' });
        await createChapter(null, 'ch-1', 'Chapter One', null, null, { seriesObjectId: 'SER-1', chapter: 1 });
        let body = bodyOf();
        expect(body.fromBookObjectId).toBeUndefined();
        expect(body.seriesObjectId).toBe('SER-1');
        expect(body.chapter).toBe(1);
        expect(body.title).toBe('Chapter One');
    });

    it('sends sourceRange with numeric offsets, trimmed title, and NO inner schema key', async () => {
        mockFetch({ slug: 'ch-3' });
        await createChapter('BOOK-OID', 'ch-3', null, null, null, {
            sourceDataObjectId: 'SRC-1',
            sourceRange: { startOffset: '120.4', endOffset: 999.6, title: '  Chapter Three  ' },
        });
        let body = bodyOf();
        expect(body.sourceDataObjectId).toBe('SRC-1');
        expect(body.sourceRange.startOffset).toBe(120);   // rounded number, not string
        expect(body.sourceRange.endOffset).toBe(1000);
        expect(typeof body.sourceRange.startOffset).toBe('number');
        expect(body.sourceRange.title).toBe('Chapter Three'); // trimmed
        // Critical: the serialized payload must carry no nested schema key
        expect('schema' in body.sourceRange).toBe(false);
        expect(lastCall.init.body).not.toContain('"schema"');
    });

    it('drops a blank title from sourceRange', async () => {
        mockFetch({ slug: 'ch-4' });
        await createChapter('BOOK-OID', 'ch-4', null, null, null, {
            sourceRange: { startOffset: 0, endOffset: 50, title: '   ' },
        });
        let body = bodyOf();
        expect(body.sourceRange.startOffset).toBe(0);
        expect(body.sourceRange.endOffset).toBe(50);
        expect('title' in body.sourceRange).toBe(false);
    });

    it('does not emit an empty sourceRange object', async () => {
        mockFetch({ slug: 'ch-5' });
        await createChapter('BOOK-OID', 'ch-5', null, null, null, { sourceRange: {} });
        let body = bodyOf();
        expect(body.sourceRange).toBeUndefined();
    });
});

describe('groupBooksBySeries (N4)', () => {
    it('returns a single "All books" group with hasSeriesInfo=false when no series linkage exists', () => {
        let out = groupBooksBySeries([{ objectId: 'a', name: 'A' }, { objectId: 'b', name: 'B' }]);
        expect(out.hasSeriesInfo).toBe(false);
        expect(out.groups.length).toBe(1);
        expect(out.groups[0].chapters.length).toBe(2);
    });

    it('handles empty / non-array input without throwing', () => {
        expect(groupBooksBySeries(null).hasSeriesInfo).toBe(false);
        expect(groupBooksBySeries(undefined).groups[0].chapters.length).toBe(0);
        expect(groupBooksBySeries([]).groups[0].chapters.length).toBe(0);
    });

    it('groups by series objectId string and orders chapters by ordinal', () => {
        let out = groupBooksBySeries([
            { objectId: 'b3', name: 'Third', series: 'S1', chapter: 3 },
            { objectId: 'b1', name: 'First', series: 'S1', chapter: 1 },
            { objectId: 'b2', name: 'Second', series: 'S1', chapter: 2 },
        ]);
        expect(out.hasSeriesInfo).toBe(true);
        expect(out.groups.length).toBe(1);
        let chapters = out.groups[0].chapters;
        expect(chapters.map(c => c.chapter)).toEqual([1, 2, 3]);
    });

    it('resolves a series NAME from a series record object and separates standalone books', () => {
        let out = groupBooksBySeries([
            { objectId: 'b1', name: 'One', series: { objectId: 'S1', name: 'My Saga' }, chapter: 1 },
            { objectId: 'b2', name: 'Two', series: { objectId: 'S1', name: 'My Saga' }, chapter: 2 },
            { objectId: 'x', name: 'Loner' }, // no series → standalone bucket
        ]);
        expect(out.hasSeriesInfo).toBe(true);
        let saga = out.groups.find(g => g.seriesKey === 'S1');
        expect(saga).toBeTruthy();
        expect(saga.seriesName).toBe('My Saga');
        expect(saga.chapters.length).toBe(2);
        let standalone = out.groups.find(g => g.seriesKey === '__standalone__');
        expect(standalone).toBeTruthy();
        expect(standalone.chapters.length).toBe(1);
    });

    it('honors seriesObjectId as an alternate linkage field', () => {
        let out = groupBooksBySeries([{ objectId: 'b1', name: 'One', seriesObjectId: 'S9', chapter: 1 }]);
        expect(out.hasSeriesInfo).toBe(true);
        expect(out.groups[0].seriesKey).toBe('S9');
    });
});

describe('listSeriesBooks REST client (N4)', () => {
    it('GETs the per-series endpoint with the encoded seriesObjectId and returns the chapter array', async () => {
        mockFetch([
            { objectId: 'b1', name: 'One', slug: 'ch1', bookStatus: 'DONE', chapter: 1, seriesObjectId: 'S1', worldObjectId: 'W1' },
            { objectId: 'b2', name: 'Two', slug: 'ch2', bookStatus: 'PENDING', chapter: 2, seriesObjectId: 'S1', worldObjectId: 'W1' },
        ]);
        let result = await listSeriesBooks('SER 1/oid');
        expect(lastCall.url).toContain('/rest/olio/picture-book/series/');
        expect(lastCall.url).toContain(encodeURIComponent('SER 1/oid'));
        expect(lastCall.url).toContain('/books');
        expect(lastCall.init.credentials).toBe('include');
        expect(Array.isArray(result)).toBe(true);
        expect(result.map(b => b.chapter)).toEqual([1, 2]);
        // Feeding through the pure grouper yields one ordered series group.
        let grouped = groupBooksBySeries(result);
        expect(grouped.hasSeriesInfo).toBe(true);
        expect(grouped.groups.length).toBe(1);
        expect(grouped.groups[0].chapters.map(c => c.chapter)).toEqual([1, 2]);
    });

    it('throws a distinct notFound error on 404 (series does not exist)', async () => {
        mockFetch({ error: 'Series not found: S1' }, false, 404);
        let err = null;
        try { await listSeriesBooks('S1'); } catch (e) { err = e; }
        expect(err).toBeTruthy();
        expect(err.notFound).toBe(true);
        expect(err.message).toContain('Series not found');
    });

    it('resolves an empty array (series exists, caller not entitled / no chapters) — NOT an error', async () => {
        mockFetch([]);
        let result = await listSeriesBooks('S1');
        expect(Array.isArray(result)).toBe(true);
        expect(result.length).toBe(0);
    });

    it('throws a generic (non-notFound) error on other non-ok statuses', async () => {
        mockFetch({}, false, 500);
        let err = null;
        try { await listSeriesBooks('S1'); } catch (e) { err = e; }
        expect(err).toBeTruthy();
        expect(err.notFound).toBeUndefined();
        expect(err.message).toContain('listSeriesBooks failed: 500');
    });
});
