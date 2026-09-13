// @vitest-environment jsdom
//
// ChapBook async creation client contract.
//
// WHY: createChapBook makes one LLM landscape-prompt call per stanza chunk, so a real chapbook runs
// for many minutes inside what used to be a single POST. Measured on the live stack: a 10.8K-char
// WordPerfect poem at 4 lines/page produces 80 scenes. ChapBookService had NO progress reporting and
// NO cancel wiring at all, and the ChapBook UI never rendered LLMConnector.bgActivity, so even the
// progress chirps the server already sent went nowhere.
//
// These tests pin the client half: the async start contract (202 + jobId), that the request body is
// byte-identical to the synchronous one (so the two paths cannot drift), and that the shared job
// poller reports scene-level progress and honours cancellation. The live behaviour — real scenes
// persisted as they are created, cancel keeping them — is covered by the Docker integration driver.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

function jsonResponse(status, body) {
    return { ok: status >= 200 && status < 300, status, json: async () => body };
}

describe('startCreateChapBook', () => {
    let origFetch;
    beforeEach(() => { origFetch = global.fetch; });
    afterEach(() => { global.fetch = origFetch; });

    it('POSTs to /create?async=true and returns the jobId from a 202', async () => {
        let seen = null;
        global.fetch = vi.fn(async (url, opts) => {
            seen = { url: String(url), body: JSON.parse(opts.body) };
            return jsonResponse(202, { jobId: 'cb-job-1', status: 'queued' });
        });
        let { startCreateChapBook } = await import('../features/chapBook.js');

        let out = await startCreateChapBook('myslug', 'My Title', ['p1', 'p2'], 8, 'cfgA');

        expect(out.jobId).toBe('cb-job-1');
        expect(seen.url).toContain('/chap-book/create?async=true');
        expect(seen.body.slug).toBe('myslug');
        expect(seen.body.title).toBe('My Title');
        expect(seen.body.poemObjectIds).toEqual(['p1', 'p2']);
        expect(seen.body.maxLinesPerPage).toBe(8);
        expect(seen.body.chatConfig).toBe('cfgA');
    });

    it('rejects a 200 — async mode must answer 202', async () => {
        // A 200 here means the server ran it SYNCHRONOUSLY (an old build, or the async flag being
        // ignored). Treating that as success would leave the client polling a jobId it never got.
        global.fetch = vi.fn(async () => jsonResponse(200, { objectId: 'book-1' }));
        let { startCreateChapBook } = await import('../features/chapBook.js');
        await expect(startCreateChapBook('s', 't', ['p1'], 8, null))
            .rejects.toThrow(/Create ChapBook failed: 200/);
    });

    it('builds the SAME body as the synchronous path', async () => {
        // Both routes share createChapBookBody precisely so the async path cannot quietly drift
        // from the sync one — e.g. dropping chatConfig, which would silently downgrade every scene
        // to the stanza-excerpt fallback prompt (a previously reported regression).
        let bodies = [];
        global.fetch = vi.fn(async (url, opts) => {
            bodies.push(JSON.parse(opts.body));
            return String(url).includes('async=true')
                ? jsonResponse(202, { jobId: 'j', status: 'queued' })
                : jsonResponse(200, { objectId: 'b' });
        });
        let { startCreateChapBook, createChapBook } = await import('../features/chapBook.js');

        await createChapBook('s', 't', ['p1'], 6, 'cfgX');
        await startCreateChapBook('s', 't', ['p1'], 6, 'cfgX');

        expect(bodies).toHaveLength(2);
        expect(bodies[0]).toEqual(bodies[1]);
    });

    it('omits chatConfig when none is chosen, so the server default applies', async () => {
        let seen = null;
        global.fetch = vi.fn(async (url, opts) => {
            seen = JSON.parse(opts.body);
            return jsonResponse(202, { jobId: 'j', status: 'queued' });
        });
        let { startCreateChapBook } = await import('../features/chapBook.js');
        await startCreateChapBook('s', 't', ['p1'], 8, null);
        expect('chatConfig' in seen).toBe(false);
    });

    it('defaults maxLinesPerPage to 8 rather than sending 0', async () => {
        // 0 would be a real value on the wire; the server treats <=0 as 8, but sending it makes the
        // client's intent unreadable in a request log.
        let seen = null;
        global.fetch = vi.fn(async (url, opts) => {
            seen = JSON.parse(opts.body);
            return jsonResponse(202, { jobId: 'j', status: 'queued' });
        });
        let { startCreateChapBook } = await import('../features/chapBook.js');
        await startCreateChapBook('s', 't', ['p1'], 0, null);
        expect(seen.maxLinesPerPage).toBe(8);
    });
});

describe('ChapBook creation progress via the shared poller', () => {
    let origFetch;
    beforeEach(() => { origFetch = global.fetch; });
    afterEach(() => { global.fetch = origFetch; });

    it('reports scene-level current/total and finishes with the book', async () => {
        // The denominator is stanza chunks, computed by createChapBook's pre-pass. Before that pass
        // existed there was no total to report at all.
        let ticks = [
            { jobId: 'j', status: 'running', phase: 'preparing book world', current: 0, total: 0, terminal: false },
            { jobId: 'j', status: 'running', phase: 'creating scenes', current: 3, total: 36, terminal: false },
            { jobId: 'j', status: 'completed', phase: 'completed', current: 36, total: 36, terminal: true,
              result: { objectId: 'book-9', slug: 'myslug' } }
        ];
        let i = 0;
        global.fetch = vi.fn(async () => jsonResponse(200, ticks[Math.min(i++, ticks.length - 1)]));
        let { pollJob } = await import('../workflows/sceneExtractor.js');

        let phases = [];
        let job = await pollJob('j', { intervalMs: 1, onProgress: (p) => phases.push(p.phase) });

        expect(job.status).toBe('completed');
        expect(job.result.objectId).toBe('book-9');
        // The world-creation phase is reported BEFORE a scene total is knowable — on the first
        // chapbook in an org that call alone runs for minutes, and a bare 0/0 with no phase reads
        // as a hung job.
        expect(phases[0]).toBe('preparing book world');
        expect(phases).toContain('creating scenes');
    });

    it('treats a cancelled creation as a result, not an error', async () => {
        // Each scene is persisted as it is created, so a cancelled run leaves a real book with
        // fewer scenes. Throwing here would discard a book that actually exists.
        global.fetch = vi.fn(async () => jsonResponse(200, {
            jobId: 'j', status: 'cancelled', current: 12, total: 36, terminal: true,
            result: { objectId: 'book-partial', slug: 'myslug' }
        }));
        let { pollJob } = await import('../workflows/sceneExtractor.js');
        let job = await pollJob('j', { intervalMs: 1 });
        expect(job.status).toBe('cancelled');
        expect(job.result.objectId).toBe('book-partial');
        expect(job.current).toBe(12);
    });
});
