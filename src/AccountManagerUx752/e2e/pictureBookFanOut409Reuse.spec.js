/**
 * PictureBook chaptered fan-out: a RE-RUN over the same manuscript must REUSE the series' existing
 * chapter books instead of dying on `createChapter failed: 409` for every chapter.
 *
 * THE DEFECT THIS PROVES FIXED (2026-09-24). Chapter slugs are deterministic
 * (`generateSlug(name) + '-chN'`), so the first run of a novel creates N chapter books and any later
 * run of the same document 409s on every one of them. fanOutChaptersExtract treated every create
 * failure as fatal for the chapter, so the whole submission ended with
 *   "No chapters were saved. Chapter 1 (Chapter 1): could not create its book — createChapter failed: 409 | ..."
 * The fix (pictureBook.js fanOutChaptersExtract) narrows the catch to 409, looks the slug up in
 * GET /series/{id}/books and reuses the existing chapter book; it forks a suffixed slug only when the
 * slug is NOT one of this series' chapters.
 *
 * This spec reproduces the exact collision state through the REAL wizard UI, against the Docker stack,
 * as e2etest_shared (never admin):
 *   1. Upload the first two chapters of Stephen's real HarlotsEight manuscript as a data.data record.
 *   2. Pre-create the series + every detected chapter book via REST — exactly what a first wizard run
 *      does — and confirm a repeat POST /chapter on a chapter slug is a 409.
 *   3. Drive the wizard: /#!/picture-book → Browse Documents → pick the record → Chat Config picker →
 *      Extract. Before the fix this ends on the red "No chapters were saved" banner; after the fix it
 *      lands on /picture-book/{firstChapterBook}/workflow with the SAME chapter books (no suffixed
 *      duplicates) and scenes persisted into the real chapters.
 *
 * Run against the Docker UAT stack, injecting the proxy key WITHOUT printing it:
 *   LITELLM_MASTER_KEY=$(docker exec am7test-litellm-1 printenv LITELLM_MASTER_KEY) \
 *   PB_LITELLM_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookFanOut409Reuse.spec.js --workers=1 --project=chromium
 *
 * Gated (PB_LITELLM_TESTS=1 + LITELLM_MASTER_KEY) and serial: it spends real Azure tokens
 * (two real chapters, ~50K chars, chunked).
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig, apiLogin } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));

const ENABLED = process.env.PB_LITELLM_TESTS === '1' && !!process.env.LITELLM_MASTER_KEY;
const SKIP_REASON = 'set PB_LITELLM_TESTS=1 and LITELLM_MASTER_KEY to run the live LiteLLM→Azure fan-out';

// Picked through the ObjectPicker, which lists ~/Chat ten rows a page sorted by name; the '000-' prefix
// keeps the fixture on the first page (the pictureBookNewBookRender convention). The API-only
// pictureBookLiteLlmAzure spec keeps its own 'e2e-litellm-azure' pair.
const CONFIG_NAME = '000-litellm-azure';
const CONNECTION_NAME = '000-litellm-azure-conn';
const SERVER_URL = process.env.LITELLM_SERVER_URL || 'http://litellm:4000';
const MODEL = process.env.LITELLM_MODEL || 'gpt-5.6-terra';

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';

// Title line + "Chapter 1" + "Chapter 2" of the real manuscript (src/AccountManagerObjects7/media/
// HarlotsEight_Vol1_SM.docx). Two real chapters are enough to take the fan-out branch (>= 2 ranges)
// and to collide on every chapter slug, without spending the full 21-chapter novel on Azure.
const FIXTURE_PATH = path.resolve(SPEC_DIR, 'fixtures/harlotsEight_ch1-2.txt');

const LLM_TEST_TIMEOUT_MS = 25 * 60 * 1000;

/** Mirror of pictureBook.js generateSlug — the wizard derives the series slug from the record name. */
function generateSlug(name) {
    if (!name) return 'book-' + Date.now().toString(36);
    let slug = String(name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    return slug.substring(0, 64) || 'book-' + Date.now().toString(36);
}

function chapterTitle(range, chapNum) {
    return (range.title != null && String(range.title).trim().length)
        ? String(range.title).trim() : 'Chapter ' + chapNum;
}

async function makeDataDir(ctx) {
    const b64 = Buffer.from('~/Data').toString('base64').replace(/=/g, '%3D');
    const resp = await ctx.get(REST + '/path/make/auth.group/data/B64-' + b64);
    expect(resp.ok(), 'path/make ~/Data failed: ' + resp.status()).toBe(true);
    const dir = await resp.json();
    expect(dir && dir.id, '~/Data must resolve to a group with an id').toBeTruthy();
    return dir;
}

// §24 UAT-4: the Select Document picker's own page-1 query for ~/Data (ObjectPicker → list.openForPicker →
// pagination.getSearchQuery), verbatim from the retained trace of the first U-3 run — same conditions, sort,
// page size and request projection, so it lands on the SAME server-side cache key (QueryUtil.key: actor,
// type, sort, order, range, request list, conditions). Sent once BEFORE the upload with cache:true to warm the
// server cache the way a previous picker open does, so the picker opened later would be served the stale
// (doc-less) page unless it busts the cache (the UAT-4 fix). Returns the page's names.
async function pickerPage1Names(ctx, dir) {
    const resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'data.data',
            fields: [
                { comparator: 'equals', name: 'organizationId', value: dir.organizationId },
                { comparator: 'equals', name: 'groupId', value: dir.id }
            ],
            order: 'ascending', comparator: 'group_and', recordCount: 10, startRecord: 0, sortField: 'name',
            cache: true,
            request: ['contentType', 'organizationId', 'groupId', 'groupPath', 'organizationId', 'groupId', 'name',
                'id', 'urn', 'objectId', 'ownerId', 'id', 'objectId', 'ownerId', 'organizationId', 'organizationId',
                'organizationPath', 'createdDate', 'modifiedDate', 'groupPath', 'groupId', 'description', 'tags']
        }
    });
    expect(resp.ok(), 'picker page-1 search failed: ' + resp.status()).toBe(true);
    // /rest/model/search answers an io.queryResult envelope {schema, count, queryHash, queryKey, results}, not a
    // bare array (U-3 rerun 2 read "0 rows" from the misparsed envelope).
    const qr = await resp.json();
    const rows = Array.isArray(qr) ? qr : (qr && qr.results) || [];
    return rows.map(r => r.name);
}

async function uploadFixture(ctx, name) {
    const dir = await makeDataDir(ctx);
    expect(dir.organizationId, '~/Data must carry organizationId').toBeTruthy();
    const warm = await pickerPage1Names(ctx, dir);
    console.log('[fanout409] warmed the picker page-1 cache for ~/Data (' + warm.length + ' rows, first: ' + warm[0] + ')');
    const text = fs.readFileSync(FIXTURE_PATH, 'utf8');
    const resp = await ctx.post(REST + '/model', {
        data: {
            schema: 'data.data',
            name: name,
            groupId: dir.id,
            groupPath: dir.path,
            contentType: 'text/plain',
            dataBytesStore: Buffer.from(text, 'utf8').toString('base64')
        }
    });
    expect(resp.ok(), 'manuscript upload failed: ' + resp.status()).toBe(true);
    const rec = await resp.json();
    expect(rec && rec.objectId, 'upload returned no objectId').toBeTruthy();
    // Observation only (not asserted — the server-side cache policy is not what this spec pins): with the
    // page warmed above, a cache:true re-read after the create still omits the new record until the entry
    // ages out (CacheDBSearch.maximumCacheAgeMS) — the condition the picker must survive.
    const after = await pickerPage1Names(ctx, dir);
    console.log('[fanout409] cache:true page-1 re-read after upload lists the doc: ' + (after.includes(name) ? 'yes' : 'NO (stale page served)'));
    return { objectId: rec.objectId, chars: text.length };
}

async function detectBoundaries(ctx, sourceDataObjectId) {
    const resp = await ctx.get(PB + '/chapter/detect-boundaries?sourceDataObjectId='
        + encodeURIComponent(sourceDataObjectId));
    expect(resp.ok(), 'detect-boundaries failed: ' + resp.status()).toBe(true);
    const ranges = await resp.json();
    expect(Array.isArray(ranges), 'detect-boundaries must return an array').toBe(true);
    return ranges;
}

async function listSeriesBooks(ctx, seriesObjectId) {
    const resp = await ctx.get(PB + '/series/' + seriesObjectId + '/books');
    expect(resp.ok(), 'GET /series/{id}/books failed: ' + resp.status()).toBe(true);
    const books = await resp.json();
    expect(Array.isArray(books), 'series books must be an array').toBe(true);
    return books;
}

/** Same request body pictureBookWorkflow.createChapter sends for a series-first chapter. */
function chapterBody(slug, title, seriesObjectId, chapNum, sourceDataObjectId, range) {
    return {
        slug, title, seriesObjectId, chapter: chapNum, sourceDataObjectId,
        sourceRange: {
            startOffset: Math.round(Number(range.startOffset)),
            endOffset: Math.round(Number(range.endOffset)),
            title
        }
    };
}

/**
 * The wizard's "Chat Config" picker (ObjectPicker.openLibrary) refuses to open — toast only, no
 * overlay — unless GET /rest/chat/library/dir/chat resolves, i.e. the org's shared /Library/ChatConfigs
 * exists. The Chat feature creates it on first use via POST /rest/chat/library/init; do the same here
 * (idempotent, `user` role) so the picker opens for a user whose org has never opened Chat.
 */
async function ensureChatLibraryDir(ctx) {
    let dirResp = await ctx.get(REST + '/chat/library/dir/chat');
    if (dirResp.status() === 200) return await dirResp.json();
    const init = await ctx.post(REST + '/chat/library/init', {
        data: { serverUrl: SERVER_URL, model: MODEL, serviceType: 'openai_compat' }
    });
    expect(init.ok(), 'POST /chat/library/init failed: ' + init.status()).toBe(true);
    dirResp = await ctx.get(REST + '/chat/library/dir/chat');
    expect(dirResp.status(), 'chat library dir must resolve after init').toBe(200);
    return await dirResp.json();
}

async function loginBrowserAsShared(page) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: SHARED_USER,
            credential: Buffer.from(SHARED_PASSWORD).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login failed: HTTP ' + resp.status());

    // Docker's nginx does not forward the session cookie on the WS upgrade; Tomcat closes the socket
    // and pageClient.reconnect() forces a logout. Stub the socket before the app loads.
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => {
                    this.readyState = 1;
                    if (this.onopen) this.onopen({ type: 'open', target: this });
                }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0;
        window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2;
        window.WebSocket.CLOSED = 3;
    });

    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 });
}

/** Pick a row by name in the ObjectPicker overlay and confirm with the check button. */
async function pickFromPicker(page, pickerTitle, rowName) {
    const picker = page.locator('div.am7-picker-overlay').filter({ has: page.locator('h3:has-text("' + pickerTitle + '")') });
    await expect(picker).toBeVisible({ timeout: 15000 });
    const row = picker.locator('tr.tabular-row').filter({ hasText: rowName }).first();
    await expect(row, 'picker row "' + rowName + '" must be listed').toBeVisible({ timeout: 20000 });
    await row.click();
    const confirm = picker.locator('button:has(span:text("check"))');
    await expect(confirm).toBeVisible({ timeout: 5000 });
    await confirm.click();
    await expect(picker).not.toBeVisible({ timeout: 5000 });
}

test.describe.configure({ mode: 'serial' });

test.describe('PictureBook fan-out re-run reuses existing chapter books on 409 (gated)', () => {
    let docName = null;
    let doc = null;
    let ranges = [];
    let seriesSlugBase = null;
    let seriesObjectId = null;
    let seriesWorldObjectId = null;
    let preCreated = []; // [{chapNum, slug, title, objectId}]

    test.beforeAll(async () => {
        if (!ENABLED) return;
        const ctx = await pwRequest.newContext({
            baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true
        });
        try {
            await ensureSharedTestUser(ctx);
            const cfg = await ensureChatConfig(ctx, null, {
                configName: CONFIG_NAME,
                connectionName: CONNECTION_NAME,
                serverUrl: SERVER_URL,
                model: MODEL,
                serviceType: 'openai_compat',
                dialect: 'openai_compat',
                apiKey: process.env.LITELLM_MASTER_KEY
            });
            if (cfg !== CONFIG_NAME) {
                throw new Error('ensureChatConfig did not yield the LiteLLM chat config: ' + JSON.stringify(cfg));
            }

            await apiLogin(ctx, { user: SHARED_USER, password: SHARED_PASSWORD });
            const chatLib = await ensureChatLibraryDir(ctx);
            console.log('[fanout409] chat library dir: ' + (chatLib && chatLib.path));

            // A unique record name => unique series slug => a FRESH series per run, so the collision
            // this test manufactures is the only thing GET /series/{id}/books can be reporting on.
            // '000-' because the Select Document picker pages ~/Data ten rows a page sorted by name and
            // ~/Data accumulates every spec's uploads (the first §24 U-3 run found it on page 1 of 7
            // behind the '6a-winter-import-*' fixtures and timed out).
            docName = '000-harlots-eight-ch1-2-' + Date.now().toString(36) + '.txt';
            doc = await uploadFixture(ctx, docName);
            console.log('[fanout409] uploaded ' + docName + ' (' + doc.chars + ' chars) as ' + doc.objectId);

            ranges = await detectBoundaries(ctx, doc.objectId);
            console.log('[fanout409] ranges: ' + JSON.stringify(ranges.map(r => ({ t: r.title, s: r.startOffset, e: r.endOffset }))));
            expect(ranges.length, 'two real chapters must detect >= 2 ranges (fan-out branch)').toBeGreaterThanOrEqual(2);

            // ── Manufacture the collision: series + one chapter book per detected range, exactly as
            //    fanOutChaptersExtract does on a first run (same slugs, titles, ordinals, ranges).
            seriesSlugBase = generateSlug(docName);
            const sResp = await ctx.post(PB + '/series', { data: { seriesSlug: seriesSlugBase, title: docName } });
            expect(sResp.ok(), 'POST /series failed: ' + sResp.status()).toBe(true);
            const series = await sResp.json();
            seriesObjectId = series.seriesObjectId;
            seriesWorldObjectId = series.worldObjectId;
            expect(seriesObjectId, 'createSeries returned no seriesObjectId').toBeTruthy();

            for (let i = 0; i < ranges.length; i++) {
                const chapNum = i + 1;
                const title = chapterTitle(ranges[i], chapNum);
                const slug = seriesSlugBase + '-ch' + chapNum;
                const cResp = await ctx.post(PB + '/chapter', {
                    data: chapterBody(slug, title, seriesObjectId, chapNum, doc.objectId, ranges[i])
                });
                expect(cResp.ok(), 'POST /chapter ' + slug + ' failed: ' + cResp.status()).toBe(true);
                const ch = await cResp.json();
                expect(ch && ch.bookObjectId, 'chapter ' + slug + ' returned no bookObjectId').toBeTruthy();
                preCreated.push({ chapNum, slug, title, objectId: ch.bookObjectId });
            }
            console.log('[fanout409] pre-created series ' + seriesObjectId + ' with '
                + preCreated.map(c => c.slug + '=' + c.objectId).join(', '));

            // The collision is real: the same slug again is a 409 (this is what every chapter hit).
            const again = await ctx.post(PB + '/chapter', {
                data: chapterBody(preCreated[0].slug, preCreated[0].title, seriesObjectId, 1, doc.objectId, ranges[0])
            });
            expect(again.status(), 'a repeat POST /chapter on an existing slug must be 409').toBe(409);

            const books = await listSeriesBooks(ctx, seriesObjectId);
            expect(books.map(b => b.slug).sort(), 'series must list exactly the pre-created chapter slugs')
                .toEqual(preCreated.map(c => c.slug).sort());
        } finally {
            await ctx.dispose();
        }
    });

    test('re-running the wizard over an already-chaptered manuscript reuses the chapter books and saves scenes',
        async ({ page, request }) => {
            test.skip(!ENABLED, SKIP_REASON);
            test.setTimeout(LLM_TEST_TIMEOUT_MS);

            await loginBrowserAsShared(page);
            await page.goto('/#!/picture-book', { timeout: 30000 });

            // Enter the wizard the way the user does, so the REAL record name drives the series slug.
            const browseDocs = page.locator('button').filter({ hasText: 'Browse Documents' });
            await expect(browseDocs).toBeVisible({ timeout: 20000 });
            await browseDocs.click();
            await pickFromPicker(page, 'Select Document', docName);

            const dialog = page.locator('.am7-dialog').filter({ hasText: 'Picture Book — ' + docName });
            await expect(dialog).toBeVisible({ timeout: 20000 });

            // Select the LiteLLM→Azure chat config explicitly (the auto-resolved default may point at
            // a model server that is not up).
            const chatConfigControl = dialog.locator('xpath=//label[normalize-space()="Chat Config"]/following-sibling::div[1]');
            await expect(chatConfigControl).toBeVisible({ timeout: 10000 });
            await chatConfigControl.click();
            await pickFromPicker(page, 'Select Chat Config', CONFIG_NAME);
            await expect(chatConfigControl).toContainText(CONFIG_NAME, { timeout: 10000 });

            const extractBtn = dialog.locator('.am7-dialog-footer button.am7-dialog-btn-primary').filter({ hasText: /^\s*\S*\s*Extract\s*$/ });
            await expect(extractBtn).toBeVisible({ timeout: 10000 });
            await expect(extractBtn).toBeEnabled({ timeout: 10000 });
            await extractBtn.click();

            // Wait for the fan-out to finish. Two product outcomes are a finished fan-out:
            //   (a) a clean run lands on the first chapter book's workflow route (the series canvas);
            //   (b) a run in which the model declined passages stays in the wizard with the per-chapter report
            //       (`renderChapterSummary`, data-pb-chapter-summary) whose "Open series" button leads to (a).
            // The pre-fix FAILURE this spec pins is neither: the red "No chapters were saved. ... 409" banner, or a
            // report whose problems say a chapter's book could not be created / its scenes could not be saved.
            // §24 U-3 READ (2026-11-15): asserted — only (a); product now — (b) since 2026-09-24 (a transient toast +
            // navigate is how a nine-hour run lost half its chapters); why — azure/gpt-5.6-terra via LiteLLM answered
            // three of Harlot's Eight's seven passages with a 0-/1-token completion (HTTP 200; the same chapter's
            // location pass got an explicit ContentPolicyViolationError 400), which is the route's content filter,
            // not the product. The 409-reuse property is asserted in BOTH outcomes; the declined passages are logged,
            // never a threshold.
            const firstChapterOid = preCreated[0].objectId;
            const landing = new RegExp('#!/picture-book/' + firstChapterOid + '/workflow');
            const errorBanner = dialog.locator('div.text-red-500');
            const summary = dialog.locator('[data-pb-chapter-summary]');
            const deadline = Date.now() + LLM_TEST_TIMEOUT_MS - 3 * 60 * 1000;
            let lastProgress = null;
            let landedVia = null;
            for (;;) {
                if (landing.test(page.url())) { landedVia = 'route'; break; }
                if (await errorBanner.isVisible().catch(() => false)) {
                    const msg = (await errorBanner.textContent({ timeout: 1000 }).catch(() => '')) || '';
                    if (/No chapters were saved|could not create its book|Extraction failed|failed/i.test(msg)) {
                        throw new Error('wizard reported an extraction failure: ' + msg);
                    }
                }
                if (await summary.isVisible().catch(() => false)) { landedVia = 'report'; break; }
                const progress = await dialog.locator('text=/Chapter \\d+\\/\\d+/').first().textContent({ timeout: 1000 }).catch(() => null);
                if (progress && progress !== lastProgress) {
                    console.log('[fanout409] ' + progress.trim());
                    lastProgress = progress;
                }
                expect(Date.now() < deadline, 'fan-out did not finish within the budget (last URL ' + page.url() + ')').toBe(true);
                await page.waitForTimeout(3000);
            }
            // Chapters the report says received no scenes (1-based chapter numbers). Filled from the report;
            // a clean run (route landing) has none.
            const missingChapters = new Set();
            if (landedVia === 'report') {
                const headline = ((await summary.locator('[data-pb-chapter-summary-headline]').textContent({ timeout: 5000 })) || '').trim();
                const problems = (await summary.locator('[data-pb-chapter-problem]').allTextContents()).map(s => s.trim());
                console.log('[fanout409] wizard report: ' + headline);
                for (const p of problems) console.log('[fanout409]   problem: ' + p);
                // The pre-fix 409 fork signature must not be among the problems — that is the property under test.
                for (const p of problems) {
                    expect(p, 'report must not carry a chapter-book create / save failure').not.toMatch(/could not create its book|\b409\b|could not be saved|createChapter/i);
                }
                // §24 UAT-5 READ (2026-11-15, rerun 3): asserted — "N of N chapters saved" (every chapter persisted);
                // product now — a chapter whose every passage the route's content filter withheld has nothing to
                // persist, and the report says so: "Chapter k (…): no scenes were extracted." plus one
                // "Passage i of n — the model declined to process this passage …" line per withheld passage, and
                // the headline counts it as missing; why — the route (azure/gpt-5.6-terra via LiteLLM) declines
                // Harlot's Eight passages at its own discretion, so which chapters land is not the product's
                // doing and a missing chapter is evidence of nothing — PROVIDED the report attributes it to
                // declined passages. Before UAT-5 the same withheld completion was mislabelled "did not finish
                // within the request timeout" (an infrastructure failure, retried nowhere) or counted as a
                // successful reply with no scenes; that mislabel is what this block now pins.
                const saved = /(\d+) of (\d+) chapters saved/.exec(headline);
                expect(saved, 'report headline must state how many chapters were saved: ' + headline).toBeTruthy();
                expect(Number(saved[2]), 'the report must cover every pre-created chapter').toBe(preCreated.length);
                const declinedLine = /Passage \d+ of \d+ — the model declined to process this passage/i;
                const chapterOf = (p) => { const mt = /^Chapter (\d+) \(/.exec(p); return mt ? Number(mt[1]) : 0; };
                for (const p of problems) {
                    if (/no scenes were extracted/i.test(p)) { missingChapters.add(chapterOf(p)); continue; }
                    expect(p, 'a remaining problem must be passage-level, not a chapter failure').toMatch(/Passage \d+ of \d+ — /);
                }
                for (const num of missingChapters) {
                    const range = ranges[num - 1];
                    if (!range || range.title == null) continue; // the untitled front-matter range legitimately yields no scenes
                    const passageLines = problems.filter(p => chapterOf(p) === num && /Passage \d+ of \d+ — /.test(p));
                    expect(passageLines.length, 'chapter ' + num + ' received no scenes but the report names no failed passage — nothing attributes the loss').toBeGreaterThan(0);
                    for (const p of passageLines) {
                        // A withheld completion is a DECLINE. Any other label on a passage of a chapter that
                        // received nothing — "did not finish within the request timeout", "did not respond",
                        // "empty reply", "no JSON" — is the pre-UAT-5 mislabel (or a real failure to READ), never
                        // an accepted way to lose a chapter.
                        expect(p, 'a missing chapter may only be attributed to passages the model declined').toMatch(declinedLine);
                    }
                }
                expect(Number(saved[1]) + missingChapters.size, 'headline saved count must agree with the per-chapter "no scenes" lines: ' + headline).toBe(Number(saved[2]));
                expect(Number(saved[1]), 'the route declined every passage of every chapter — the run produced no evidence of the 409-reuse save path; rerun (' + headline + ')').toBeGreaterThanOrEqual(1);
                console.log('[fanout409] chapters saved=' + saved[1] + '/' + saved[2] + ' missing (attributed to declines)=' + JSON.stringify([...missingChapters]));
                // The report's own way to the series canvas is the same route a clean run lands on.
                await summary.locator('[data-pb-open-series]').click();
                await expect.poll(() => page.url(), { timeout: 20000 }).toMatch(landing);
            }
            console.log('[fanout409] landed on ' + page.url() + ' via ' + landedVia);

            // ── The 409s were REUSED, not forked: same series, same slugs, same objectIds, no extras.
            await apiLogin(request, { user: SHARED_USER, password: SHARED_PASSWORD });
            const books = await listSeriesBooks(request, seriesObjectId);
            console.log('[fanout409] series books after UI run: ' + JSON.stringify(books.map(b => ({ slug: b.slug, oid: b.objectId, chapter: b.chapter }))));
            expect(books.length, 'the re-run must not add chapter books to the series').toBe(preCreated.length);
            for (const pc of preCreated) {
                const b = books.find(x => x.slug === pc.slug);
                expect(b, 'chapter slug ' + pc.slug + ' must still be in the series').toBeTruthy();
                expect(b.objectId, 'chapter ' + pc.slug + ' must be the SAME book, not a re-created one').toBe(pc.objectId);
                expect(b.worldObjectId, 'every chapter book shares the series world').toBe(seriesWorldObjectId);
            }
            expect(books.some(b => /-ch\d+-[0-9a-z]{4}$/.test(b.slug)), 'no suffixed fork slugs may appear').toBe(false);

            // ── Scenes were persisted into the reused REAL chapter books (titled ranges). The
            //    leading front-matter range ("Harlot's Eight") legitimately yields no scenes, and so does a
            //    chapter the report attributed to declined passages (UAT-5) — asserted empty, logged, never
            //    a threshold. Every other titled chapter must carry scenes in its REUSED book.
            //    READ (U-3 pb-litellm rerun 5, 2026-11-15): asserted — every chapter book answers 200 on
            //    /scenes. Product — `PictureBookUtil.listScenes` resolves a pb.book to its linked scene group
            //    and throws 404 "Book not found" when none was ever linked, which is exactly the state of a
            //    chapter whose every passage the route declined (no createFromScenes ran). Rerun 3's READ had
            //    already recorded "<ch2>/scenes → 404 Book not found = nothing linked". So a DECLINED chapter
            //    may answer 404 (nothing linked) or 200 + []; a SAVED chapter must answer 200 with scenes.
            const realChapters = preCreated.filter(c => ranges[c.chapNum - 1].title != null);
            expect(realChapters.length, 'fixture must carry >= 2 titled chapters').toBeGreaterThanOrEqual(2);
            let savedWithScenes = 0;
            for (const c of realChapters) {
                const scResp = await request.get(PB + '/' + c.objectId + '/scenes');
                const declined = missingChapters.has(c.chapNum);
                if (declined && scResp.status() === 404) {
                    console.log('[fanout409] ' + c.slug + ' (' + c.title + ') persisted scenes=0'
                        + ' [report: no scenes — passages declined; /scenes → 404 (nothing linked)]');
                    continue;
                }
                expect(scResp.ok(), 'GET /' + c.objectId + '/scenes failed: ' + scResp.status()
                    + (declined ? ' (declined chapter: only 404 = nothing linked, or 200 + [], is acceptable)' : '')).toBe(true);
                const scenes = await scResp.json();
                console.log('[fanout409] ' + c.slug + ' (' + c.title + ') persisted scenes=' + scenes.length
                    + (declined ? ' [report: no scenes — passages declined]' : '')
                    + (scenes.length ? ': ' + scenes.map(s => s.title).slice(0, 8).join(' | ') : ''));
                if (declined) {
                    expect(scenes.length, 'chapter ' + c.slug + ' was reported as receiving no scenes, yet its reused book holds some — report and book disagree').toBe(0);
                } else {
                    expect(scenes.length, 'reused chapter book ' + c.slug + ' must have persisted scenes').toBeGreaterThan(0);
                    savedWithScenes++;
                }
            }
            expect(savedWithScenes, 'at least one reused chapter book must hold the scenes the run saved').toBeGreaterThanOrEqual(1);
        });
});
