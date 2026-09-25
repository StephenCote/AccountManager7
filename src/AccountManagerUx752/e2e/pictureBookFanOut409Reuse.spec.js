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

const CONFIG_NAME = 'e2e-litellm-azure';
const CONNECTION_NAME = 'e2e-litellm-azure-conn';
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

async function uploadFixture(ctx, name) {
    const dir = await makeDataDir(ctx);
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
            docName = 'harlots-eight-ch1-2-' + Date.now().toString(36) + '.txt';
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

            // Wait for the fan-out to finish: SUCCESS lands on the first chapter book's workflow route;
            // the pre-fix FAILURE is the red "No chapters were saved. ... 409" banner inside the wizard.
            const firstChapterOid = preCreated[0].objectId;
            const landing = new RegExp('#!/picture-book/' + firstChapterOid + '/workflow');
            const errorBanner = dialog.locator('div.text-red-500');
            const deadline = Date.now() + LLM_TEST_TIMEOUT_MS - 3 * 60 * 1000;
            let lastProgress = null;
            for (;;) {
                if (landing.test(page.url())) break;
                if (await errorBanner.isVisible().catch(() => false)) {
                    const msg = (await errorBanner.textContent()) || '';
                    if (/No chapters were saved|could not create its book|Extraction failed|failed/i.test(msg)) {
                        throw new Error('wizard reported an extraction failure: ' + msg);
                    }
                }
                const progress = await dialog.locator('text=/Chapter \\d+\\/\\d+/').first().textContent().catch(() => null);
                if (progress && progress !== lastProgress) {
                    console.log('[fanout409] ' + progress.trim());
                    lastProgress = progress;
                }
                expect(Date.now() < deadline, 'fan-out did not finish within the budget (last URL ' + page.url() + ')').toBe(true);
                await page.waitForTimeout(3000);
            }
            console.log('[fanout409] landed on ' + page.url());

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
            //    leading front-matter range ("Harlot's Eight") legitimately yields no scenes.
            const realChapters = preCreated.filter(c => ranges[c.chapNum - 1].title != null);
            expect(realChapters.length, 'fixture must carry >= 2 titled chapters').toBeGreaterThanOrEqual(2);
            for (const c of realChapters) {
                const scResp = await request.get(PB + '/' + c.objectId + '/scenes');
                expect(scResp.ok(), 'GET /' + c.objectId + '/scenes failed: ' + scResp.status()).toBe(true);
                const scenes = await scResp.json();
                console.log('[fanout409] ' + c.slug + ' (' + c.title + ') persisted scenes=' + scenes.length
                    + (scenes.length ? ': ' + scenes.map(s => s.title).slice(0, 8).join(' | ') : ''));
                expect(scenes.length, 'reused chapter book ' + c.slug + ' must have persisted scenes').toBeGreaterThan(0);
            }
        });
});
