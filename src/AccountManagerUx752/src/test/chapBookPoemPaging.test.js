// @vitest-environment jsdom
/**
 * ChapBook poem-queue paging accumulator (the "poems vanish after ~25/26" fix).
 *
 * Reported bug: the poem queue stops SHOWING new entries past ~26 even though they persist fine.
 * Root cause was a CLIENT QUERY BUG — `fetchPoems` issued a single GET /olio/chap-book/poems with NO
 * recordCount, and the server (ChapBookUtil.listPoems) defaults to a 25-record page when recordCount<=0.
 * The poem queue is a load-all, client-side-sorted/filtered table with NO pagination UI, so poems 26+
 * were silently invisible. Raw-API proof captured live: GET /poems → 25 rows; GET /poems?recordCount=1000
 * → 87 rows (all of them). The fix pages through startRecord/recordCount and accumulates every page.
 *
 * These tests exercise the REAL exported `fetchAllPoemPages` — the dependency-injected core `fetchPoems`
 * drives in production — with an injected page fetcher, so this is genuine behavioral coverage of the
 * paging logic, not a re-implementation. The injected fetcher models the server's startRecord/recordCount
 * contract exactly (return the requested slice; a short final page signals the end).
 */
import { describe, it, expect, vi } from 'vitest';

// Build a fake corpus of N poems and a page fetcher that honors startRecord/recordCount like the server.
function makeCorpusFetcher(total) {
    let corpus = [];
    for (let i = 0; i < total; i++) corpus.push({ objectId: 'poem-' + i, name: 'Poem ' + i });
    let calls = [];
    let fetchPage = vi.fn(async (startRecord, pageSize) => {
        calls.push([startRecord, pageSize]);
        return corpus.slice(startRecord, startRecord + pageSize);
    });
    return { corpus, fetchPage, calls };
}

describe('fetchAllPoemPages (poem-queue paging accumulator — the >26 fix)', () => {
    it('accumulates EVERY poem across multiple pages, not just the first 25', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        // 87 poems is the exact live count from the raw-API reproduction; page size 25 == the server cap.
        let { corpus, fetchPage } = makeCorpusFetcher(87);
        let all = await fetchAllPoemPages(fetchPage, 25);
        expect(all.length).toBe(87);
        expect(all).toEqual(corpus);
        // Proof the bug is fixed: far more than the 25/26 that used to be visible.
        expect(all.length).toBeGreaterThan(26);
    });

    it('pages in order via startRecord and stops on the first short page', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        let { fetchPage, calls } = makeCorpusFetcher(87);
        await fetchAllPoemPages(fetchPage, 25);
        // 87 / 25 → pages at 0,25,50,75; the 75 page returns 12 rows (<25) and ends the loop.
        expect(calls).toEqual([[0, 25], [25, 25], [50, 25], [75, 25]]);
    });

    it('an exact multiple of the page size fetches one extra (empty) page to confirm the end', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        let { fetchPage, calls } = makeCorpusFetcher(50);
        let all = await fetchAllPoemPages(fetchPage, 25);
        expect(all.length).toBe(50);
        // 25 + 25 are both full pages, so a third request is needed to see the empty page and stop.
        expect(calls).toEqual([[0, 25], [25, 25], [50, 25]]);
    });

    it('a single short page returns immediately (one request only)', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        let { fetchPage, calls } = makeCorpusFetcher(10);
        let all = await fetchAllPoemPages(fetchPage, 25);
        expect(all.length).toBe(10);
        expect(calls).toEqual([[0, 25]]);
    });

    it('an empty corpus is a clean empty result (one request, no throw)', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        let { fetchPage, calls } = makeCorpusFetcher(0);
        let all = await fetchAllPoemPages(fetchPage, 25);
        expect(all).toEqual([]);
        expect(calls).toEqual([[0, 25]]);
    });

    it('defaults to a large page size (200) when none is passed — comfortably above the old 25 cap', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        let { fetchPage, calls } = makeCorpusFetcher(87);
        let all = await fetchAllPoemPages(fetchPage);
        expect(all.length).toBe(87);
        // 87 < 200, so the very first (short) page returns everything in a single request.
        expect(calls).toEqual([[0, 200]]);
    });

    it('tolerates a non-array page (e.g. null on error) by stopping cleanly', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        let fetchPage = vi.fn(async () => null);
        let all = await fetchAllPoemPages(fetchPage, 25);
        expect(all).toEqual([]);
        expect(fetchPage).toHaveBeenCalledTimes(1);
    });

    it('does not loop unbounded — a fetcher that always returns full pages is capped by the guard', async () => {
        let { fetchAllPoemPages } = await import('../features/chapBook.js');
        // Pathological endpoint: every page is "full", so length < pageSize never trips.
        let fetchPage = vi.fn(async (startRecord, pageSize) => {
            let page = [];
            for (let i = 0; i < pageSize; i++) page.push({ objectId: 'p' + (startRecord + i) });
            return page;
        });
        let all = await fetchAllPoemPages(fetchPage, 25);
        // The 1000-iteration guard bounds it rather than hanging the UI.
        expect(fetchPage).toHaveBeenCalledTimes(1000);
        expect(all.length).toBe(25 * 1000);
    });
});
