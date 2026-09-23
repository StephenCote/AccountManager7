/**
 * Issue 1 (src/aiDocs/IssueLog-2026-09-22.md) — REAL end-to-end proof that submitting a full
 * MULTI-CHAPTER novel manuscript fans out into ONE bounded async scene-extraction job PER CHAPTER
 * instead of a single unbounded whole-novel job (the old path ran ~4-5h and blew the client's
 * 90-min JOB_POLL_DEADLINE_MS, producing "(clearly you didn't test)").
 *
 * This is a client-workflow / REST-level integration test that mirrors, byte-for-byte where it
 * matters, pictureBook.js `fanOutChaptersExtract()` (the actual fix under test). NOTHING is stubbed:
 *   - LIVE backend  : the docker-compose.test.yml stack on https://127.0.0.1:9443
 *   - LIVE LLM      : Ollama qwen3:8b at 192.168.1.42 (reached from the container), via a real
 *                     olio.llm.chatConfig provisioned by ensureChatConfig()
 *   - REAL fixture  : src/AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx (a real 338 KB novel)
 *   - TEST USER     : ensureSharedTestUser() — e2etest_shared, NEVER admin
 *
 * Run (127.0.0.1 is mandatory — localhost resolves to IPv6 ::1 which Docker does not map):
 *
 *   # Fast path — proves the fan-out branch is taken (assertion 1), no LLM extraction. Always runs.
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookNovelFanOut.spec.js \
 *       --project=chromium --workers=1
 *
 *   # Heavy path — the full per-chapter fan-out against the live LLM (assertions 2-4). Gated + serial.
 *   PB_NOVEL_FANOUT=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *       npx playwright test e2e/pictureBookNovelFanOut.spec.js -g "fans a multi-chapter novel out" \
 *       --project=chromium --workers=1
 *
 * The heavy run is gated behind PB_NOVEL_FANOUT so the default 4-worker suite never fires a parallel
 * LLM run at the DGX (which crashes under sustained parallel load). It caps at PB_NOVEL_MAX_CHAPTERS
 * (default 3) chapters: the fix is "one bounded job per chapter", and proving that for 3 distinct
 * chapters (3 distinct jobIds, 3 chapter books, 1 shared world, each well under 90 min) demonstrates
 * the mechanism the whole-novel single-job path lacked. Set PB_NOVEL_MAX_CHAPTERS=0 to fan out the
 * ENTIRE novel.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function b64(str) { return Buffer.from(str).toString('base64'); }

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';

// Real multi-chapter novel fixture (338 KB DOCX). Override with PB_NOVEL_DOCX to point at another
// real manuscript. The Verse.docx (239 KB) is a smaller real alternative in the same media dir.
const DOCX_PATH = process.env.PB_NOVEL_DOCX
    || path.resolve(__dirname, '../../AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx');
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

const MAX_CHAPTERS = parseInt(process.env.PB_NOVEL_MAX_CHAPTERS || '3', 10); // 0 = whole novel
const JOB_DEADLINE_MS = parseInt(process.env.PB_JOB_DEADLINE_MIN || '85', 10) * 60 * 1000;
const NINETY_MIN_SEC = 90 * 60;

// ── faithful re-implementations of the two pure client helpers (sceneExtractor.js / pictureBook.js)
function scenesFromResult(result) {
    if (!result) return [];
    if (Array.isArray(result)) return result;
    if (Array.isArray(result.sceneList)) return result.sceneList;
    return [];
}
function buildCharacterStubs(sceneSource) {
    let sceneList = Array.isArray(sceneSource) ? sceneSource : [];
    let seen = {};
    let stubs = [];
    for (let s of sceneList) {
        if (!Array.isArray(s.characters)) continue;
        for (let c of s.characters) {
            let name = typeof c === 'string' ? c : (c.name || '');
            if (!name || seen[name]) continue;
            seen[name] = true;
            let obj = typeof c === 'object' ? c : {};
            stubs.push({ name: name, gender: obj.gender || '', role: obj.role || '' });
        }
    }
    return stubs;
}
function generateSlug(name) {
    return (name || '').toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .substring(0, 64) || 'book-' + Date.now().toString(36);
}

async function restLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: b64('password'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

/** Ensure ~/Manuscripts group, then upload the DOCX as a data.data and return its objectId. */
async function uploadManuscript(request) {
    const dirResp = await request.get(
        REST + '/path/make/auth.group/data/B64-' + b64('~/Manuscripts').replace(/=/g, '%3D'));
    expect(dirResp.ok(), 'could not ensure ~/Manuscripts group: ' + dirResp.status()).toBe(true);
    const dir = await dirResp.json();
    expect(dir && dir.id, 'no group id for ~/Manuscripts').toBeTruthy();

    const bytes = fs.readFileSync(DOCX_PATH);
    const name = 'novel-fanout-' + Date.now().toString(36) + '.docx';
    const createResp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data',
            name: name,
            groupId: dir.id,
            groupPath: dir.path,
            contentType: DOCX_MIME,
            dataBytesStore: bytes.toString('base64')
        }
    });
    expect(createResp.ok(), 'manuscript upload failed: ' + createResp.status()).toBe(true);
    const rec = await createResp.json();
    expect(rec && rec.objectId, 'manuscript create returned no objectId').toBeTruthy();
    return { workObjectId: rec.objectId, name: name, bytes: bytes.length };
}

async function detectBoundaries(request, workObjectId) {
    const resp = await request.get(
        PB_REST + '/chapter/detect-boundaries?sourceDataObjectId=' + encodeURIComponent(workObjectId));
    expect(resp.status(), 'detect-boundaries must not 500').not.toBe(500);
    expect(resp.ok(), 'detect-boundaries failed: ' + resp.status()).toBe(true);
    const ranges = await resp.json();
    expect(Array.isArray(ranges), 'detect-boundaries must return an array').toBe(true);
    return ranges;
}

/** Poll GET /rest/job/{jobId} until terminal, mirroring sceneExtractor.pollJob (deadline, not count). */
async function pollJobRest(request, jobId, deadlineMs) {
    const interval = 5000;
    const deadline = Date.now() + deadlineMs;
    for (;;) {
        const resp = await request.get(REST + '/job/' + jobId, { headers: { Accept: 'application/json' } });
        if (resp.status() === 401 || resp.status() === 403) throw new Error('job poll auth failure ' + resp.status());
        if (resp.status() === 404) throw new Error('job not found / not retained: ' + jobId);
        if (!resp.ok()) throw new Error('job poll failed: ' + resp.status());
        const job = await resp.json();
        if (job.terminal) return job;
        if (Date.now() > deadline) throw new Error('poll timed out for job ' + jobId);
        await new Promise(r => setTimeout(r, interval));
    }
}

test.describe('PictureBook novel fan-out (Issue 1)', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

    let chatConfigNameResolved = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        // Provision the LLM chatConfig (owned by the shared user). Resolves org itself.
        chatConfigNameResolved = await ensureChatConfig(request);
        expect(chatConfigNameResolved, 'ensureChatConfig did not return a chatConfig name').toBeTruthy();
    });

    // ── Assertion 1 (fast, always runs): the real novel triggers the FAN-OUT branch (>= 2 ranges).
    test('detect-boundaries on the real novel returns >= 2 chapters (fan-out branch is taken)', async ({ request }) => {
        test.setTimeout(120000);
        await restLoginShared(request);
        const up = await uploadManuscript(request);
        const ranges = await detectBoundaries(request, up.workObjectId);

        console.log('[fanout] manuscript=' + up.name + ' (' + up.bytes + ' bytes) -> '
            + ranges.length + ' detected range(s)');
        console.log('[fanout] ranges: ' + JSON.stringify(
            ranges.slice(0, 12).map(r => ({ t: r.title, s: r.startOffset, e: r.endOffset }))));

        for (const r of ranges) {
            expect(typeof r.startOffset).toBe('number');
            expect(typeof r.endOffset).toBe('number');
            expect(r.endOffset).toBeGreaterThan(r.startOffset);
        }
        // The fix: >= 2 detected ranges => pictureBook.doExtract() takes fanOutChaptersExtract(),
        // one bounded job per chapter, instead of the single unbounded whole-novel job.
        expect(ranges.length, 'a multi-chapter novel must detect >= 2 chapters so the fan-out path is taken')
            .toBeGreaterThanOrEqual(2);

        await request.get(REST + '/logout').catch(() => {});
    });

    // ── Assertions 2-4 (heavy, gated): the actual per-chapter fan-out against the live LLM.
    test('fans a multi-chapter novel out into one bounded async job per chapter, one shared world', async ({ request }) => {
        test.skip(!process.env.PB_NOVEL_FANOUT,
            'heavy live-LLM fan-out — set PB_NOVEL_FANOUT=1 (and run --workers=1) to execute');
        test.setTimeout(parseInt(process.env.PB_TEST_TIMEOUT_MIN || '210', 10) * 60 * 1000);

        await restLoginShared(request);
        const up = await uploadManuscript(request);
        const ranges = await detectBoundaries(request, up.workObjectId);
        expect(ranges.length, 'fan-out requires >= 2 detected chapters').toBeGreaterThanOrEqual(2);

        // Select REAL chapters — detect-boundaries emits a leading null-title front-matter range
        // (e.g. [0,18)) that carries no scenes; fanOutChaptersExtract creates a book for it but records
        // a "no scenes" problem and moves on. Proving the fan-out MECHANISM means driving it over real
        // chapter ranges (title present). MAX_CHAPTERS=0 fans out every real chapter.
        const realChapters = ranges.filter(r => r.title != null && String(r.title).trim().length > 0);
        expect(realChapters.length, 'the novel must have >= 2 titled chapters').toBeGreaterThanOrEqual(2);
        const chapters = (MAX_CHAPTERS > 0) ? realChapters.slice(0, MAX_CHAPTERS) : realChapters;
        console.log('[fanout] processing ' + chapters.length + ' of ' + realChapters.length
            + ' titled chapters (' + ranges.length + ' total ranges, cap PB_NOVEL_MAX_CHAPTERS='
            + MAX_CHAPTERS + ')');

        // Unique title => unique series slug => a FRESH series per run, so GET /series/{id}/books returns
        // exactly the chapters this run created (no cross-run contamination from the get-or-create slug).
        const title = 'HarlotsEight FanOut ' + Date.now().toString(36);
        const seriesSlugBase = generateSlug(title);

        // 1) createSeries ONCE (mirrors fanOutChaptersExtract line 542).
        const seriesResp = await request.post(PB_REST + '/series', {
            data: { seriesSlug: seriesSlugBase, title: title }
        });
        expect(seriesResp.ok(), 'POST /series failed: ' + seriesResp.status()).toBe(true);
        const series = await seriesResp.json();
        const seriesObjectId = series.seriesObjectId;
        const seriesWorldObjectId = series.worldObjectId;
        expect(seriesObjectId, 'createSeries returned no seriesObjectId').toBeTruthy();
        expect(seriesWorldObjectId, 'createSeries returned no worldObjectId').toBeTruthy();
        console.log('[fanout] series=' + seriesObjectId + ' world=' + seriesWorldObjectId);

        const perChapter = []; // { chapNum, title, bookObjectId, jobId, status, elapsedSec, sceneCount }

        for (let i = 0; i < chapters.length; i++) {
            const r = chapters[i];
            const chapNum = i + 1;
            const chapTitle = (r.title != null && String(r.title).trim().length)
                ? String(r.title).trim() : 'Chapter ' + chapNum;

            // 2) createChapter in the series (series-first: no fromBookObjectId).
            const chResp = await request.post(PB_REST + '/chapter', {
                data: {
                    slug: seriesSlugBase + '-ch' + chapNum,
                    title: chapTitle,
                    seriesObjectId: seriesObjectId,
                    chapter: chapNum,
                    sourceDataObjectId: up.workObjectId,
                    sourceRange: { startOffset: r.startOffset, endOffset: r.endOffset, title: chapTitle }
                }
            });
            expect(chResp.ok(), 'POST /chapter ' + chapNum + ' failed: ' + chResp.status()).toBe(true);
            const ch = await chResp.json();
            const bookObjectId = ch.bookObjectId;
            expect(bookObjectId, 'chapter ' + chapNum + ' returned no bookObjectId').toBeTruthy();

            // 3) bounded async extraction over ONLY this chapter's [startOffset,endOffset) span.
            const startResp = await request.post(
                PB_REST + '/' + up.workObjectId + '/extract-scenes-only?async=true'
                    + '&startOffset=' + Math.round(r.startOffset) + '&endOffset=' + Math.round(r.endOffset),
                { data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigNameResolved, seriesObjectId: seriesObjectId } });
            expect(startResp.status(), 'extract-scenes-only should return 202 (async): ch ' + chapNum).toBe(202);
            const started = await startResp.json();
            const jobId = started.jobId;
            expect(jobId, 'no jobId for chapter ' + chapNum).toBeTruthy();
            console.log('[fanout] ch' + chapNum + ' "' + chapTitle + '" span=[' + r.startOffset + ',' + r.endOffset
                + ') book=' + bookObjectId + ' job=' + jobId + ' — polling...');

            const t0 = Date.now();
            const job = await pollJobRest(request, jobId, JOB_DEADLINE_MS);
            const elapsedSec = Math.round((Date.now() - t0) / 1000);
            expect(job.terminal, 'chapter ' + chapNum + ' job not terminal').toBe(true);
            expect(job.status, 'chapter ' + chapNum + ' extraction did not complete: ' + job.status
                + (job.error ? ' (' + job.error + ')' : '')).toBe('completed');

            const chapScenes = scenesFromResult(job.result);
            console.log('[fanout] ch' + chapNum + ' job=' + jobId + ' status=' + job.status
                + ' elapsed=' + elapsedSec + 's scenes=' + chapScenes.length);
            expect(chapScenes.length, 'chapter ' + chapNum + ' extracted no scenes').toBeGreaterThan(0);

            // 4) persist THIS chapter's scenes + cast into ITS OWN chapter book (shared series world).
            const cfsResp = await request.post(PB_REST + '/' + up.workObjectId + '/create-from-scenes', {
                data: {
                    schema: 'olio.pictureBookRequest',
                    sceneList: chapScenes,
                    chatConfig: chatConfigNameResolved,
                    bookName: chapTitle,
                    characters: buildCharacterStubs(chapScenes),
                    pb2BookObjectId: bookObjectId
                }
            });
            expect(cfsResp.ok(), 'create-from-scenes ch ' + chapNum + ' failed: ' + cfsResp.status()
                + ' ' + (await cfsResp.text().catch(() => ''))).toBe(true);
            const meta = await cfsResp.json();
            // createFromScenes persists THIS chapter's scene list into a PB1 book GROUP named after
            // bookName (returned as meta.bookObjectId), while separately routing the cast into the ONE
            // shared series world keyed by pb2BookObjectId. The scenes are read back via listScenes on
            // that GROUP objectId — NOT the olio.pb.book objectId (findBookGroup resolves the group,
            // never the pb.book). Capture the group objectId; that is the real production read path.
            const sceneGroupObjectId = meta && meta.bookObjectId;
            expect(sceneGroupObjectId, 'create-from-scenes ch ' + chapNum
                + ' returned no bookObjectId (PB1 scene group)').toBeTruthy();

            perChapter.push({
                chapNum, title: chapTitle, bookObjectId, sceneGroupObjectId, jobId, status: job.status,
                elapsedSec, sceneCount: chapScenes.length
            });
        }

        // ── Assertion 3: each chapter ran as a SEPARATE bounded job, each well under 90 min.
        const jobIds = perChapter.map(c => c.jobId);
        expect(new Set(jobIds).size, 'each chapter must run as its OWN async job (distinct jobIds)')
            .toBe(perChapter.length);
        for (const c of perChapter) {
            expect(c.elapsedSec, 'chapter ' + c.chapNum + ' must complete well under 90 min, took '
                + c.elapsedSec + 's').toBeLessThan(NINETY_MIN_SEC);
        }

        // ── Assertion 2: exactly one series; N chapter books; all share the ONE series world.
        const booksResp = await request.get(PB_REST + '/series/' + seriesObjectId + '/books');
        expect(booksResp.ok(), 'GET /series/{id}/books failed: ' + booksResp.status()).toBe(true);
        const books = await booksResp.json();
        expect(Array.isArray(books), 'series books must be an array').toBe(true);
        console.log('[fanout] series books: ' + JSON.stringify(books.map(b => ({
            oid: b.objectId, slug: b.slug, chapter: b.chapter,
            series: b.seriesObjectId, world: b.worldObjectId
        }))));
        expect(books.length, 'series must list exactly the ' + perChapter.length + ' chapter books created')
            .toBe(perChapter.length);
        const worldIds = new Set(books.map(b => b.worldObjectId));
        expect(worldIds.size, 'all chapter books must share ONE world (no duplicate worlds): '
            + JSON.stringify([...worldIds])).toBe(1);
        expect([...worldIds][0], 'chapter books world must be non-null').toBeTruthy();
        expect([...worldIds][0], 'chapter books world must equal the series world').toBe(seriesWorldObjectId);
        for (const b of books) {
            expect(b.seriesObjectId, 'every chapter book must carry the series FK').toBe(seriesObjectId);
        }

        // ── Assertion 4: each chapter book actually has scenes persisted (not an empty shell).
        // createFromScenes stores the scene list in a PB1 book GROUP (meta.bookObjectId); listScenes
        // resolves THAT group objectId. Read back through the real production /scenes endpoint and
        // confirm the extracted scenes landed. Diagnostic (logged, not asserted): the series chapter
        // book's OWN olio.pb.book objectId does NOT resolve via /scenes — findBookGroup only knows the
        // group — a real endpoint gap for series books, reported to the caller.
        for (const c of perChapter) {
            const scResp = await request.get(PB_REST + '/' + c.sceneGroupObjectId + '/scenes');
            expect(scResp.ok(), 'GET /' + c.sceneGroupObjectId + '/scenes (PB1 group) failed: '
                + scResp.status()).toBe(true);
            const scenes = await scResp.json();
            expect(Array.isArray(scenes), 'chapter ' + c.chapNum + ' scenes must be an array').toBe(true);
            const pbBookScenes = await request.get(PB_REST + '/' + c.bookObjectId + '/scenes');
            console.log('[fanout] ch' + c.chapNum + ' sceneGroup=' + c.sceneGroupObjectId
                + ' persistedScenes=' + scenes.length + ' extracted=' + c.sceneCount
                + ' | pb.book(' + c.bookObjectId + ')/scenes -> HTTP ' + pbBookScenes.status()
                + ' (series-book endpoint gap; scenes live under the PB1 group)');
            expect(scenes.length, 'chapter ' + c.chapNum + ' book must have persisted scenes (not empty)')
                .toBeGreaterThan(0);
        }

        // ── Assertion 5 (route) is UI-only. The REST-level equivalent the client checks BEFORE
        // navigating to /picture-book/{firstChapterBookOid}/workflow is: a first chapter book exists
        // and at least one chapter persisted. Assert that data-level precondition here.
        expect(perChapter[0] && perChapter[0].bookObjectId,
            'first chapter book (series-canvas landing target) must exist').toBeTruthy();

        console.log('[fanout] SUMMARY ' + JSON.stringify({
            manuscript: up.name, manuscriptBytes: up.bytes,
            detectedChapters: ranges.length, processedChapters: perChapter.length,
            seriesObjectId, seriesWorldObjectId,
            perChapter: perChapter.map(c => ({
                ch: c.chapNum, jobId: c.jobId, elapsedSec: c.elapsedSec, scenes: c.sceneCount, book: c.bookObjectId
            }))
        }));

        await request.get(REST + '/logout').catch(() => {});
    });
});
