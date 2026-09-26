/**
 * Chaptered-manuscript extraction when the model server CANNOT BE REACHED — driven through the real
 * browser against the live Docker stack.
 *
 * This is the exact failure Stephen hit on 2026-09-24/25: the Ollama host dropped off the network
 * mid-run, the server misread the connect failure as a slow request, kept "processing" chapters
 * with zero results, marked them COMPLETED, cleared their checkpoints, and the wizard closed on a
 * transient toast and navigated away — nine hours, half the chapters gone, "no error anywhere".
 *
 * Required behaviour, asserted here in the Ux:
 *   1. the extraction job stops at the FIRST chapter after consecutive unreachable chunks, with a
 *      typed `stoppedEarly:true` failure entry naming the host, and `extractionComplete:false`;
 *   2. the wizard STAYS OPEN on a persistent error that says which chapter stopped and why
 *      ("could not reach the model server at <host>") — it does NOT close, toast, or navigate;
 *   3. no later chapter is created or falsely reported; the one chapter book has no scenes;
 *   4. the book list shows the series with that chapter marked as having no scenes;
 *   5. no uncaught page error anywhere in the run.
 *
 * Deterministic and independent of the real LLM: a dedicated persistent non-admin user
 * (e2etest_pbdown) owns a `contentAnalysis` chatConfig whose system.connection points at
 * TEST-NET-1 (192.0.2.1, RFC 5737 — never routable), so the wizard's by-name config resolution
 * (user's own ~/Chat wins over the shared library) picks it up with nothing shared being mutated.
 * The manuscript is the real HarlotsEight_Vol1_SM.docx; the detect-boundaries RESPONSE is truncated
 * to 3 chapters test-side purely to bound the series size.
 *
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test \
 *       e2e/pictureBookUnreachableLlmUx.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensurePath } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';

const PBDOWN_USER = 'e2etest_pbdown';
const PBDOWN_PASSWORD = 'password';
const ORG_PATH = '/Development';

const DOCX_PATH = process.env.PB_UX_DOCX
    || path.resolve(__dirname, '../../AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx');
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

// RFC 5737 TEST-NET-1: guaranteed unroutable, so a connect to it can only time out or be rejected.
const UNREACHABLE_LLM = 'http://192.0.2.1:11434';
const LLM_MODEL = process.env.PB_UX_LLM_MODEL || 'goekdenizguelmez/JOSIEFIED-Qwen3:8b';
const CHAT_CONFIG_NAME = 'contentAnalysis';
const MAX_CHAPTERS = 3;

function b64(str) { return Buffer.from(str).toString('base64'); }
function fmtSec(ms) { return (ms / 1000).toFixed(0) + 's'; }

async function restLogin(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: ORG_PATH,
            name: PBDOWN_USER,
            credential: b64(PBDOWN_PASSWORD),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, PBDOWN_USER + ' login failed: ' + resp.status()).toBe(true);
}

async function searchOne(request, type, fields, requestFields) {
    const resp = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, cache: false, fields, request: requestFields, recordCount: 5 }
    });
    if (!resp.ok()) return null;
    // No match comes back as an OK status with an empty body, not an empty array.
    const text = await resp.text();
    if (!text || !text.trim()) return null;
    const body = JSON.parse(text);
    const results = Array.isArray(body) ? body : (body && body.results) || [];
    return results.length ? results[0] : null;
}

async function getJson(request, url) {
    const resp = await request.get(url, { headers: { Accept: 'application/json' } });
    expect(resp.ok(), 'GET ' + url + ' -> ' + resp.status()).toBe(true);
    return resp.json();
}

/**
 * The wizard auto-resolves a chatConfig named "contentAnalysis" and ChatUtil.resolveConfig checks
 * the caller's OWN ~/Chat first. This user exists only to own one that points at an unreachable host.
 * Idempotent; refuses (rather than silently repoints) a pre-existing config aimed elsewhere, because
 * a chatConfig cached with its old connection would not see a connection PATCH.
 */
async function ensureUnreachableContentAnalysisConfig(request) {
    const chatDir = await ensurePath(request, 'auth.group', 'data', '~/Chat');
    expect(chatDir && chatDir.id, 'could not ensure ~/Chat: ' + JSON.stringify(chatDir)).toBeTruthy();
    const orgId = chatDir.organizationId;
    expect(typeof orgId, '~/Chat returned no numeric organizationId').toBe('number');

    // A nested-path projection ("connection.serverUrl") is rejected server-side (FieldException) and
    // /rest/model/search then answers 200 with an EMPTY body — which reads exactly like "no such record".
    // Project the foreign record and resolve its serverUrl with a second search.
    const existing = await searchOne(request, 'olio.llm.chatConfig', [
        { name: 'name', comparator: 'EQUALS', value: CHAT_CONFIG_NAME },
        { name: 'groupId', comparator: 'EQUALS', value: chatDir.id },
        { name: 'organizationId', comparator: 'EQUALS', value: orgId }
    ], ['id', 'objectId', 'name', 'model', 'connection']);

    if (existing) {
        const connId = existing.connection && existing.connection.id;
        const conn = connId ? await searchOne(request, 'system.connection', [
            { name: 'id', comparator: 'EQUALS', value: connId },
            { name: 'organizationId', comparator: 'EQUALS', value: orgId }
        ], ['id', 'objectId', 'name', 'serverUrl']) : null;
        const url = conn && conn.serverUrl;
        expect(url, PBDOWN_USER + "'s " + CHAT_CONFIG_NAME + ' chatConfig (id=' + existing.id
            + ') must point at ' + UNREACHABLE_LLM + ' but points at ' + url
            + ' — delete it and re-run').toBe(UNREACHABLE_LLM);
        console.log('[ux-llm-down] reusing ' + CHAT_CONFIG_NAME + ' chatConfig id=' + existing.id + ' -> ' + url);
        return { orgId };
    }

    const connResp = await request.post(REST + '/model', {
        data: {
            schema: 'system.connection',
            name: CHAT_CONFIG_NAME + ' Connection (unreachable)',
            groupId: chatDir.id,
            groupPath: chatDir.path,
            serverUrl: UNREACHABLE_LLM,
            requestTimeout: 60
        }
    });
    const connBody = await connResp.text();
    expect(connResp.ok(), 'system.connection create failed (' + connResp.status() + '): ' + connBody).toBe(true);
    const conn = JSON.parse(connBody);
    expect(conn && conn.objectId, 'connection create returned no objectId').toBeTruthy();

    const cfgResp = await request.post(REST + '/model', {
        data: {
            schema: 'olio.llm.chatConfig',
            name: CHAT_CONFIG_NAME,
            groupId: chatDir.id,
            groupPath: chatDir.path,
            model: LLM_MODEL,
            analyzeModel: LLM_MODEL,
            serviceType: 'ollama',
            stream: false,
            connection: { schema: 'system.connection', id: conn.id, objectId: conn.objectId }
        }
    });
    const cfgBody = await cfgResp.text();
    expect(cfgResp.ok(), 'chatConfig create failed (' + cfgResp.status() + '): ' + cfgBody).toBe(true);
    console.log('[ux-llm-down] created ' + CHAT_CONFIG_NAME + ' chatConfig -> ' + UNREACHABLE_LLM);
    return { orgId };
}

async function uploadManuscript(request, name) {
    const dir = await ensurePath(request, 'auth.group', 'data', '~/Manuscripts');
    expect(dir && dir.id, 'could not ensure ~/Manuscripts').toBeTruthy();
    const bytes = fs.readFileSync(DOCX_PATH);
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data',
            name,
            groupId: dir.id,
            groupPath: dir.path,
            contentType: DOCX_MIME,
            dataBytesStore: bytes.toString('base64')
        }
    });
    expect(resp.ok(), 'manuscript upload failed: ' + resp.status()).toBe(true);
    const rec = await resp.json();
    expect(rec && rec.objectId, 'manuscript create returned no objectId').toBeTruthy();
    return rec.objectId;
}

test.describe('PictureBook chaptered extraction with an unreachable model server — real browser on Docker', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

    let docName = null;
    let docFileName = null;
    let docObjectId = null;
    let firstChapterTitle = null;
    const pageErrors = [];
    const consoleErrors = [];

    test.beforeAll(async ({ request }) => {
        test.setTimeout(240000);
        await ensureSharedTestUser(request, { name: PBDOWN_USER, password: PBDOWN_PASSWORD });
        await restLogin(request);
        await ensureUnreachableContentAnalysisConfig(request);

        docName = 'HarlotsEight-LLMDOWN-' + Date.now().toString(36);
        docFileName = docName + '.docx';
        docObjectId = await uploadManuscript(request, docFileName);

        const ranges = await getJson(request, PB_REST + '/chapter/detect-boundaries?sourceDataObjectId='
            + encodeURIComponent(docObjectId));
        expect(Array.isArray(ranges) && ranges.length >= MAX_CHAPTERS, 'manuscript did not detect as chaptered').toBe(true);
        firstChapterTitle = String(ranges[0].title || '').trim();
        expect(firstChapterTitle.length, 'first chapter has a title').toBeGreaterThan(0);
        console.log('[ux-llm-down] manuscript=' + docName + ' oid=' + docObjectId + ' detected=' + ranges.length
            + ' chapters; wizard will be handed ' + MAX_CHAPTERS + '; first="' + firstChapterTitle + '"');
    });

    test('wizard stops at chapter 1, stays open, names the unreachable host; nothing is falsely saved',
        async ({ page }) => {
        test.setTimeout(12 * 60 * 1000);

        page.on('pageerror', (err) => {
            pageErrors.push(String(err && err.stack || err));
            console.log('[PAGE-ERROR] ' + (err && err.message));
        });
        page.on('console', (msg) => {
            if (msg.type() === 'error') {
                consoleErrors.push(msg.text());
                console.log('[PAGE-CONSOLE-ERROR] ' + msg.text());
            }
        });

        // Every extraction job the wizard polls, keyed by id; the last terminal payload is the
        // server's own account of the run and is asserted below.
        const jobPayloads = new Map();
        const extractJobIds = [];
        page.on('response', async (resp) => {
            const u = resp.url();
            const method = resp.request().method();
            if (u.includes('/olio/picture-book/') && resp.status() >= 400) {
                let body = '';
                try { body = (await resp.text()).substring(0, 500); } catch (_) { body = '<unreadable>'; }
                console.log('[NETWORK ' + resp.status() + '] ' + method + ' ' + u + ' ' + body);
            }
            if (u.includes('/extract-scenes') && method === 'POST' && resp.ok()) {
                try {
                    const j = await resp.json();
                    if (j && j.jobId) extractJobIds.push(j.jobId);
                } catch (_) {}
            }
            if (/\/rest\/job\/[^/?]+$/.test(u) && method === 'GET' && resp.ok()) {
                try {
                    const j = await resp.json();
                    if (j && j.jobId) jobPayloads.set(j.jobId, j);
                } catch (_) {}
            }
        });

        await page.route('**/chapter/detect-boundaries*', async (route) => {
            const resp = await route.fetch();
            let json = await resp.json();
            if (Array.isArray(json) && json.length > MAX_CHAPTERS) json = json.slice(0, MAX_CHAPTERS);
            await route.fulfill({ response: resp, json });
        });

        await login(page, { org: ORG_PATH, user: PBDOWN_USER, password: PBDOWN_PASSWORD });

        // ── Step 1: open the wizard on the manuscript and Extract ──────────────────────────────
        await page.goto('/#!/picture-book/' + docObjectId);
        const generateBtn = page.locator('button:has-text("Generate Picture Book")');
        await expect(generateBtn).toBeVisible({ timeout: 20000 });
        await generateBtn.click();
        const dialog = page.locator('[role="dialog"]').first();
        await expect(dialog).toContainText('Source: ' + docFileName, { timeout: 10000 });
        await expect(page.locator('text=' + CHAT_CONFIG_NAME).first()).toBeVisible({ timeout: 20000 });
        await screenshot(page, 'ux-llm-down-step1');

        const extractBtn = page.locator('button:has-text("Extract")').first();
        await expect(extractBtn).toBeEnabled({ timeout: 5000 });
        const startedAt = Date.now();
        await extractBtn.click();

        // ── Wait for the breaker: the wizard must stay open and report the stop ────────────────
        //    Two connect attempts (10s connect timeout each) plus polling is well under a minute;
        //    six minutes is a generous ceiling before this is called a hang.
        const budgetMs = 6 * 60 * 1000;
        const stopRe = /Extraction stopped at chapter 1\/3/;
        let dialogText = '';
        for (;;) {
            if (/\/picture-book\/[^/]+\/workflow/.test(page.url())) {
                await screenshot(page, 'ux-llm-down-navigated-away');
                throw new Error('wizard navigated to the workflow route on a run that reached no model server: ' + page.url());
            }
            const visible = await dialog.isVisible().catch(() => false);
            if (!visible) {
                await screenshot(page, 'ux-llm-down-dialog-vanished');
                throw new Error('wizard dialog disappeared without reporting; url=' + page.url()
                    + ' pageErrors=' + JSON.stringify(pageErrors));
            }
            dialogText = (await dialog.textContent().catch(() => '')) || '';
            if (stopRe.test(dialogText)) break;
            if (/No chapters were saved|Extraction failed|Extraction was cancelled|No scenes were extracted from any chapter/.test(dialogText)) {
                await screenshot(page, 'ux-llm-down-wrong-error');
                throw new Error('wizard reported a different error than the breaker stop: '
                    + dialogText.replace(/\s+/g, ' ').slice(0, 800));
            }
            if (await page.locator('[data-pb-chapter-summary]').isVisible().catch(() => false)) {
                await screenshot(page, 'ux-llm-down-summary-instead');
                throw new Error('wizard rendered a partial-run summary instead of the breaker stop: '
                    + dialogText.replace(/\s+/g, ' ').slice(0, 800));
            }
            if (Date.now() - startedAt > budgetMs) {
                await screenshot(page, 'ux-llm-down-timeout');
                throw new Error('breaker did not trip within ' + fmtSec(budgetMs) + '; dialog: '
                    + dialogText.replace(/\s+/g, ' ').slice(0, 800));
            }
            await page.waitForTimeout(3000);
        }
        const elapsed = Date.now() - startedAt;
        // The message renders below the Prompt Templates box; it must have scrolled itself into the viewport,
        // not merely exist in the DOM below the fold where the user never sees it.
        const errorEl = page.locator('[data-pb-extract-error]');
        await expect(errorEl).toBeVisible({ timeout: 5000 });
        await expect(errorEl).toBeInViewport({ timeout: 5000 });
        await screenshot(page, 'ux-llm-down-stopped');
        const shown = dialogText.replace(/\s+/g, ' ');
        console.log('[ux-llm-down] breaker reported after ' + fmtSec(elapsed) + ': '
            + shown.slice(shown.indexOf('Extraction stopped'), shown.indexOf('Extraction stopped') + 700));

        // The message says which chapter, that the model server was the cause, and WHICH host.
        expect(shown).toMatch(/Chapter 1 \(/);
        expect(shown).toContain(firstChapterTitle);
        expect(shown).toMatch(/consecutive chunks could not reach the model server/);
        expect(shown).toContain('192.0.2.1');
        expect(shown).toMatch(/re-run to resume/);
        // Not the misclassification that lost the nine-hour run.
        expect(shown).not.toMatch(/Request timed out after/);
        // The wizard is still usable: Step 1 with Extract re-enabled, so the user can fix the host and retry.
        await expect(dialog).toContainText('Source: ' + docFileName);
        await expect(page.locator('button:has-text("Extract")').first()).toBeEnabled({ timeout: 10000 });
        expect(/\/picture-book\/[^/]+\/workflow/.test(page.url()), 'still not navigated').toBe(false);

        // ── The server's own account of the ONE job that ran ─────────────────────────────────
        expect(extractJobIds.length, 'exactly one extraction job was started (fan-out stopped at chapter 1); saw '
            + JSON.stringify(extractJobIds)).toBe(1);
        const job = jobPayloads.get(extractJobIds[0]);
        expect(job, 'terminal payload for job ' + extractJobIds[0] + ' was polled').toBeTruthy();
        console.log('[ux-llm-down] job ' + job.jobId + ' status=' + job.status + ' terminal=' + job.terminal
            + ' current=' + job.current + ' total=' + job.total
            + ' result=' + JSON.stringify(job.result).slice(0, 600));
        expect(job.terminal).toBe(true);
        expect(job.status).not.toBe('cancelled');
        const result = job.result || {};
        expect(result.extractionComplete, 'a run that never reached the model is NOT complete').toBe(false);
        const scenes = Array.isArray(result.sceneList) ? result.sceneList : [];
        expect(scenes.length, 'no scenes can come from an unreachable model').toBe(0);
        const failed = Array.isArray(result.failedExtractions) ? result.failedExtractions : [];
        expect(failed.length, 'failedExtractions recorded').toBeGreaterThan(0);
        const parsed = failed.map(f => {
            if (typeof f !== 'string') return f;
            try { return JSON.parse(f); } catch (_) { return { error: f }; }
        });
        const breaker = parsed.find(f => f && f.stoppedEarly === true);
        expect(breaker, 'a typed stoppedEarly entry: ' + JSON.stringify(parsed).slice(0, 800)).toBeTruthy();
        expect(String(breaker.error)).toMatch(/could not reach the model server/);
        expect(String(breaker.error)).toContain('192.0.2.1');
        expect(String(breaker.error)).toMatch(/Could not connect to the model server at http:\/\/192\.0\.2\.1:11434/);
        expect(String(breaker.context)).toMatch(/^extract-scenes-chunk:\d+\/\d+$/);

        // ── REST truth as the same user: one chapter book, no scenes, no later chapters ───────
        const allBooks = await getJson(page.request, PB_REST + '/books');
        const mine = allBooks.filter(b => b.seriesName === docFileName);
        expect(mine.length, 'books in series "' + docFileName + '": ' + JSON.stringify(mine.map(b =>
            ({ ch: b.chapter, slug: b.slug, status: b.bookStatus, sceneCount: b.sceneCount })))).toBe(1);
        const chapterBook = mine[0];
        expect(Number(chapterBook.chapter)).toBe(1);
        expect(Number(chapterBook.sceneCount || 0), 'chapter 1 must not report scenes').toBe(0);
        const seriesOid = chapterBook.seriesObjectId;
        expect(seriesOid, 'seriesObjectId on the list DTO').toBeTruthy();
        const seriesBooks = await getJson(page.request, PB_REST + '/series/' + seriesOid + '/books');
        expect(seriesBooks.length, 'series has only chapter 1').toBe(1);
        console.log('[ux-llm-down] series ' + seriesOid + ' books: ' + JSON.stringify(seriesBooks.map(b =>
            ({ ch: b.chapter, slug: b.slug, status: b.bookStatus }))));

        // ── The book list must show the truth: the series card, its one chapter WITHOUT scenes ──
        await page.keyboard.press('Escape').catch(() => {});
        await page.goto('/#!/picture-book');
        await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: 30000 });
        const card = page.locator('[data-pb2-series="' + seriesOid + '"]');
        await expect(card).toBeVisible({ timeout: 30000 });
        const header = card.locator('[data-pb2-series-header]');
        const headerText = ((await header.textContent()) || '').replace(/\s+/g, ' ').trim();
        console.log('[ux-llm-down] series card header: ' + headerText);
        expect(headerText).toContain('1 chapter');
        expect(headerText).toContain('0 with scenes');
        await header.click();
        const rows = card.locator('[data-pb2-series-chapters] [data-pb2-book]');
        await expect(rows).toHaveCount(1, { timeout: 10000 });
        const rowText = ((await rows.first().textContent()) || '').replace(/\s+/g, ' ');
        console.log('[ux-llm-down] chapter row: ' + rowText.trim());
        expect(rowText).toContain('no scenes');
        await screenshot(page, 'ux-llm-down-book-list');

        // ── No script error anywhere in the run ───────────────────────────────────────────────
        expect(pageErrors, 'uncaught page errors').toEqual([]);
    });
});
