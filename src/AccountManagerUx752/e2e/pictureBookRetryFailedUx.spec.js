// @ts-check
/**
 * PictureBook wizard — the passages the model could not read are EXPLAINED, and can be retried
 * alone with a different chat config. Driven through the real browser against the live Docker stack.
 *
 * What the user used to see: "N passage(s) could not be read by the model" and nothing else — no way
 * to tell a content-policy refusal from a stalled stream from a context-window problem, and the only
 * recovery was resubmitting the whole document. What this spec asserts in the Ux:
 *   1. the banner lists one line PER failed passage with its position, a plain-language reason keyed
 *      on the server's typed `kind`, and the server's own error text;
 *   2. when EVERY passage failed (zero scenes), the wizard stays on Step 1 and STILL shows the banner
 *      and the retry — not just "No scenes returned by LLM";
 *   3. the chat config for the retry can be switched through the picker, and the retry POSTs
 *      `extract-retry-failed` with THAT config, as a `pb.retryFailedChunks` job sized to the failed
 *      passages — it does not go back to extract-scenes-only;
 *   4. the recovered scenes land in the Step-2 list and the banner shrinks (or disappears);
 *   5. no uncaught page error anywhere.
 *
 * What this spec does NOT prove: that a retry re-reads ONLY the failed passages when some succeeded.
 * Every passage fails here, so "failed count" and "whole document" coincide; the partial-failure
 * property is pinned server-side by Objects7 TestExtractChunkLoop
 * (TestRefusedPassageSurvivesCompletionAndIsRecoveredByRetryFailedChunks) and in the wizard by the
 * Vitest in src/test/pictureBookExtractState.test.js.
 *
 * HOW A REAL FAILURE IS PRODUCED. Same as pictureBookRetryFailed.spec.js (R2): the first run uses a
 * chatConfig whose system.connection has a deliberately short requestTimeout, so every passage fails
 * the honest way — `kind:"timeout"` against the real model server — and the server keeps its
 * checkpoint. The wizard's own Extract then returns that stored result (no LLM calls), and the retry
 * runs the failed passages with the normal config through the real LLM. The beforeAll asserts the
 * all-failed/zero-scene shape so the Step-1 assertions below are never silently skipped.
 *
 * Minutes of real LLM work: gated behind PB_ASYNC_TESTS=1. Run serially:
 *   PB_ASYNC_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test \
 *       e2e/pictureBookRetryFailedUx.spec.js --workers=1 --project=chromium --retries=0
 *
 * NEVER uses the admin user — ensureSharedTestUser()/ensureChatConfig() provision; everything runs
 * as e2etest_shared.
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const JOB = REST + '/job';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));

const LLM_ENABLED = process.env.PB_ASYNC_TESTS === '1';

const AIME_PDF = path.resolve(SPEC_DIR, '../../AccountManagerObjects7/media/AIME.pdf');

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';

/** See pictureBookRetryFailed.spec.js: >= LLM_INFRA_FAILURE_MS so it is a timeout, not a breaker trip. */
const RETRY_SLOW_TIMEOUT_S = 8;
const SLOW_CONFIG = 'e2e-pb-retry-slow';
const SLOW_CONN = 'e2e-pb-retry-slow-conn';

const TEST_TIMEOUT_MS = 25 * 60 * 1000;

async function loginCtx(ctx) {
    const resp = await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: SHARED_USER,
            credential: Buffer.from(SHARED_PASSWORD).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login failed: HTTP ' + resp.status());
}

function b64Path(p) {
    return 'B64-' + Buffer.from(p).toString('base64').replace(/=/g, '%3D');
}

async function uploadAime(request, name) {
    const dirResp = await request.get(REST + '/path/make/auth.group/data/' + b64Path('~/Data'));
    const dir = await dirResp.json();
    expect(dir && dir.id, '~/Data group must resolve').toBeTruthy();
    const bytes = fs.readFileSync(AIME_PDF);
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data',
            groupId: dir.id,
            groupPath: dir.path,
            name,
            contentType: 'application/pdf',
            dataBytesStore: bytes.toString('base64')
        }
    });
    expect(resp.status(), 'data.data create').toBe(200);
    const created = await resp.json();
    expect(created && created.objectId).toBeTruthy();
    return created.objectId;
}

async function pollJob(ctx, jobId, budgetMs) {
    const deadline = Date.now() + budgetMs;
    let last = null;
    while (Date.now() < deadline) {
        const resp = await ctx.get(JOB + '/' + jobId);
        expect(resp.status(), 'job poll').toBe(200);
        const job = await resp.json();
        const sig = `${job.status} ${job.current}/${job.total}`;
        if (sig !== last) {
            console.log(`[pbRetryUx] ${jobId.slice(0, 8)} ${job.kind} ${sig} elapsed=${job.elapsed}s`);
            last = sig;
        }
        if (job.terminal) return job;
        await new Promise(r => setTimeout(r, 3000));
    }
    throw new Error('job ' + jobId + ' did not reach a terminal state in time');
}

function failures(result) {
    const raw = (result && result.failedExtractions) || [];
    return raw.map(f => (typeof f === 'string' ? JSON.parse(f) : f))
        .sort((a, b) => (a.chunk || 0) - (b.chunk || 0));
}

/**
 * The library picker opened by a chat-config field: rows are `tr.tabular-row`, a single click selects
 * (a double click toggles it back off), and `button.button` first is the confirm (pattern from
 * chapbook-issues.spec.js). The picker starts in the user's own ~/Chat, where both e2e configs live.
 */
async function pickChatConfig(page, name) {
    const picker = page.locator('.am7-picker-overlay');
    await expect(picker).toBeVisible({ timeout: 15000 });
    const row = picker.locator('tr.tabular-row').filter({ hasText: name }).first();
    await expect(row, 'chat config "' + name + '" listed in the picker').toBeVisible({ timeout: 15000 });
    await row.click();
    await page.waitForTimeout(300);
    await picker.locator('button.button').first().click();
    await expect(picker).toBeHidden({ timeout: 10000 });
}

test.describe('PictureBook wizard — failed passages are explained and retried alone (LLM, gated)', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

    let docName = null;
    let workObjectId = null;
    let goodConfig = null;
    let run1Failed = [];
    let run1Scenes = 0;
    let totalChunks = 0;

    test.beforeAll(async () => {
        test.skip(!LLM_ENABLED, 'PB_ASYNC_TESTS!=1 — skips live LLM extraction');
        test.setTimeout(TEST_TIMEOUT_MS);
        const ctx = await pwRequest.newContext({
            baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true
        });
        try {
            await ensureSharedTestUser(ctx);
            const slowConfig = await ensureChatConfig(ctx, null, {
                configName: SLOW_CONFIG, connectionName: SLOW_CONN, requestTimeout: RETRY_SLOW_TIMEOUT_S
            });
            goodConfig = await ensureChatConfig(ctx, null);
            expect(typeof slowConfig).toBe('string');
            expect(typeof goodConfig).toBe('string');
            expect(slowConfig).not.toBe(goodConfig);

            await loginCtx(ctx);
            docName = 'AIME-retry-ux-' + Date.now().toString(36) + '.pdf';
            workObjectId = await uploadAime(ctx, docName);

            // Run 1 (API): every passage times out against the live server; the run still completes
            // and the server keeps the checkpoint WITH the per-passage failure records.
            const start = await ctx.post(PB + '/' + workObjectId + '/extract-scenes-only?async=true',
                { data: { schema: 'olio.pictureBookRequest', chatConfig: slowConfig } });
            expect(start.status(), 'async start').toBe(202);
            const run1 = await pollJob(ctx, (await start.json()).jobId, 20 * 60 * 1000);
            expect(run1.status, 'run 1 must complete').toBe('completed');
            expect(run1.result.chunked, 'AIME must chunk').toBe(true);
            totalChunks = run1.total;
            run1Failed = failures(run1.result);
            run1Scenes = (run1.result.sceneList || []).length;
            console.log('[pbRetryUx] run 1: ' + run1Failed.length + '/' + totalChunks + ' passages failed, '
                + run1Scenes + ' scenes; kinds=' + run1Failed.map(f => f.kind).join(','));
            // Pin the precondition the Step-1 assertions depend on: EVERY passage timed out and nothing
            // was extracted. An 8-second budget against a model that needs ~2 minutes per passage makes
            // this deterministic in practice; if it ever isn't, fail here rather than silently testing
            // a different shape below.
            expect(run1Failed.length, 'every passage must have failed under the short timeout').toBe(totalChunks);
            expect(run1Scenes, 'no scenes may survive an all-timeout run').toBe(0);
            for (const f of run1Failed) expect(f.kind).toBe('timeout');
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        if (!workObjectId) return;
        const ctx = await pwRequest.newContext({
            baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true
        });
        try {
            await loginCtx(ctx);
            await ctx.delete(REST + '/model/data.data/' + workObjectId);
        } finally {
            await ctx.dispose();
        }
    });

    test('the wizard lists WHY each passage failed, lets the user switch the chat config, and retries them through the retry route',
        async ({ page }) => {
        test.skip(!LLM_ENABLED, 'PB_ASYNC_TESTS!=1 — skips live LLM extraction');
        test.setTimeout(TEST_TIMEOUT_MS);

        const pageErrors = [];
        page.on('pageerror', (err) => {
            pageErrors.push(String(err && err.stack || err));
            console.log('[PAGE-ERROR] ' + (err && err.message));
        });
        const jobPayloads = new Map();
        let retryPost = null;
        page.on('request', (req) => {
            if (req.url().includes('/extract-retry-failed') && req.method() === 'POST') {
                retryPost = { url: req.url(), body: req.postData() };
            }
        });
        page.on('response', async (resp) => {
            const u = resp.url();
            if (u.includes('/olio/picture-book/') && resp.status() >= 400) {
                let body = '';
                try { body = (await resp.text()).substring(0, 500); } catch (_) { body = '<unreadable>'; }
                console.log('[NETWORK ' + resp.status() + '] ' + resp.request().method() + ' ' + u + ' ' + body);
            }
            if (/\/rest\/job\/[^/?]+$/.test(u) && resp.request().method() === 'GET' && resp.ok()) {
                try {
                    const j = await resp.json();
                    if (j && j.jobId) jobPayloads.set(j.jobId, j);
                } catch (_) {}
            }
        });
        // Force the single-document path: the checkpoint kept by run 1 is the whole-document one, and
        // chapter detection on this PDF is irrelevant to what is under test here.
        await page.route('**/chapter/detect-boundaries*', (route) => route.fulfill({ json: [] }));

        await login(page, { org: '/Development', user: SHARED_USER, password: SHARED_PASSWORD });

        // ── Open the wizard on the document, pick the SLOW config (what the user "ran with") ──────
        await page.goto('/#!/picture-book/' + workObjectId);
        const generateBtn = page.locator('button:has-text("Generate Picture Book")');
        await expect(generateBtn).toBeVisible({ timeout: 20000 });
        await generateBtn.click();
        const dialog = page.locator('[role="dialog"]').first();
        await expect(dialog).toContainText('Source: ' + docName, { timeout: 10000 });

        const configField = dialog.locator('label.field-label:has-text("Chat Config")').locator('xpath=following-sibling::div[1]');
        await expect(configField).not.toContainText('Loading default', { timeout: 20000 });
        await configField.click();
        await pickChatConfig(page, SLOW_CONFIG);
        await expect(configField).toContainText(SLOW_CONFIG);

        // ── Extract: served from the kept checkpoint, failures included ───────────────────────────
        const extractBtn = page.locator('button:has-text("Extract")').first();
        await expect(extractBtn).toBeEnabled({ timeout: 5000 });
        const t0 = Date.now();
        await extractBtn.click();

        const banner = page.locator('[data-pb-failed-passages]');
        await expect(banner).toBeVisible({ timeout: 3 * 60 * 1000 });
        console.log('[pbRetryUx] banner shown after ' + ((Date.now() - t0) / 1000).toFixed(0) + 's');
        await screenshot(page, 'pb-retry-ux-banner');

        // (1) one line per failed passage: position, plain-language reason, the server's own text.
        const rows = banner.locator('li[data-pb-failed-passage]');
        await expect(rows).toHaveCount(run1Failed.length);
        const timeoutRows = banner.locator('li[data-pb-failed-passage][data-pb-failed-kind="timeout"]');
        await expect(timeoutRows).toHaveCount(run1Failed.length);
        const bannerText = ((await banner.textContent()) || '').replace(/\s+/g, ' ');
        console.log('[pbRetryUx] banner: ' + bannerText.slice(0, 900));
        expect(bannerText).toContain(run1Failed.length + ' passage' + (run1Failed.length === 1 ? '' : 's') + ' could not be read by the model');
        expect(bannerText).toMatch(/did not finish within the request timeout/);
        expect(bannerText).toMatch(new RegExp('timed out after ' + RETRY_SLOW_TIMEOUT_S + ' seconds'));
        for (const f of run1Failed) {
            expect(bannerText).toContain('Passage ' + f.chunk + ' of ' + totalChunks);
        }
        // The generic dead end is gone: the reason is never JUST "could not be read" without a why.
        expect(bannerText).not.toMatch(/could not be read by the model \(/);

        // (2) zero scenes => still Step 1, and the banner + retry are there anyway.
        const sceneHeader = dialog.locator('h3:has-text("Scene List (")');
        await expect(dialog).toContainText('No scenes returned by LLM');
        await expect(dialog).toContainText('Source: ' + docName);
        expect(await sceneHeader.count(), 'no Step 2 without scenes').toBe(0);
        const retryBtn = banner.locator('[data-pb-retry-failed]');
        await expect(retryBtn).toBeVisible();
        await expect(retryBtn).toBeEnabled();
        await expect(retryBtn).toContainText('Retry ' + run1Failed.length + ' failed passage');

        // (3) switch the chat config for the retry through the picker.
        const cfgBtn = banner.locator('[data-pb-retry-chatconfig]');
        await expect(cfgBtn).toContainText(SLOW_CONFIG);
        await cfgBtn.click();
        await pickChatConfig(page, goodConfig);
        await expect(cfgBtn).toContainText(goodConfig);
        await screenshot(page, 'pb-retry-ux-config-switched');

        const t1 = Date.now();
        await retryBtn.click();
        await expect(dialog).toContainText(/Retrying failed passages/, { timeout: 30000 });
        await expect(retryBtn).toBeDisabled();
        await screenshot(page, 'pb-retry-ux-retrying');

        // The retry is minutes of real LLM work (one passage ~60-110s); wait for the wizard to settle.
        await expect.poll(async () => {
            const txt = ((await dialog.textContent().catch(() => '')) || '');
            return /Retrying failed passages|Retrying\.\.\./.test(txt);
        }, { timeout: 15 * 60 * 1000, intervals: [3000] }).toBe(false);
        console.log('[pbRetryUx] retry settled after ' + ((Date.now() - t1) / 1000).toFixed(0) + 's');
        await screenshot(page, 'pb-retry-ux-after-retry');

        // The POST carried the SWITCHED config and went to the retry route, not a full re-extract.
        expect(retryPost, 'extract-retry-failed was POSTed').toBeTruthy();
        expect(retryPost.url).toMatch(/\/extract-retry-failed\?async=true$/);
        const sent = JSON.parse(retryPost.body || '{}');
        expect(sent.schema).toBe('olio.pictureBookRequest');
        expect(sent.chatConfig).toBe(goodConfig);
        expect(sent.startOffset).toBeUndefined();

        // The server's account: a pb.retryFailedChunks job sized to the failed passages. Because every
        // passage failed in run 1, this count alone cannot distinguish "retried the failed passages"
        // from "re-read the whole document" — that property is pinned with a PARTIAL failure by
        // Objects7 TestExtractChunkLoop.TestRefusedPassageSurvivesCompletionAndIsRecoveredByRetryFailedChunks
        // ("exactly one model call — the refused passage"). What this spec adds is the job KIND, key
        // and the URL: the wizard went to the retry route, not back to extract-scenes-only.
        const retryJob = Array.from(jobPayloads.values()).find(j => j.kind === 'pb.retryFailedChunks' && j.terminal);
        expect(retryJob, 'terminal retry job payload polled by the wizard; saw '
            + JSON.stringify(Array.from(jobPayloads.values()).map(j => ({ kind: j.kind, status: j.status, terminal: j.terminal })))).toBeTruthy();
        console.log('[pbRetryUx] retry job ' + retryJob.jobId + ' status=' + retryJob.status + ' total=' + retryJob.total
            + ' current=' + retryJob.current + ' scenes=' + ((retryJob.result && retryJob.result.sceneList) || []).length
            + ' stillFailed=' + failures(retryJob.result).length);
        expect(retryJob.status).toBe('completed');
        expect(retryJob.key).toBe(workObjectId);
        expect(retryJob.total, 'the retry job is sized to the failed passages').toBe(run1Failed.length);
        const stillFailed = failures(retryJob.result);
        const recoveredScenes = ((retryJob.result && retryJob.result.sceneList) || []).length;
        expect(stillFailed.length, 'the retry must recover at least one passage').toBeLessThan(run1Failed.length);
        expect(recoveredScenes).toBeGreaterThan(run1Scenes);

        // (4) the wizard adopted the result: Step 2 with the recovered scenes, banner shrunk or gone.
        await expect(sceneHeader).toBeVisible({ timeout: 10000 });
        const headerText = (await sceneHeader.textContent()) || '';
        const shownCount = Number((headerText.match(/Scene List \((\d+)\)/) || [])[1]);
        console.log('[pbRetryUx] ' + headerText.trim() + '; banner rows now ' + await rows.count());
        expect(shownCount).toBe(recoveredScenes);
        expect(shownCount).toBeGreaterThan(run1Scenes);
        if (stillFailed.length === 0) {
            await expect(banner).toHaveCount(0);
        } else {
            await expect(rows).toHaveCount(stillFailed.length);
            const after = ((await banner.textContent()) || '').replace(/\s+/g, ' ');
            // A passage that failed again shows its NEW reason, not the stale 8-second timeout.
            expect(after).not.toMatch(new RegExp('timed out after ' + RETRY_SLOW_TIMEOUT_S + ' seconds'));
        }
        await expect(dialog.locator('[data-pb-extract-error]')).toHaveCount(0);

        // (5)
        expect(pageErrors, 'uncaught page errors').toEqual([]);
    });
});
