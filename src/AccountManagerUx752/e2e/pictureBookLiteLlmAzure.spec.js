/**
 * PictureBook scene extraction through LiteLLM → Azure OpenAI (OPENAI_COMPAT dialect).
 *
 * THE DEFECT THIS PROVES FIXED (2026-09-24). `olio.llm.request.options` is Ollama's native
 * sub-object. Chat serialized it on every dialect, and Azure (via LiteLLM) rejected the request:
 *   HTTP 400 litellm.BadRequestError: AzureException BadRequestError - Unknown parameter: 'options'
 * Chat.chatInternal now prunes `options` whenever the resolved service type is not OLLAMA. This
 * spec drives a real extraction end-to-end through the Docker stack so the pruning is measured on
 * the wire rather than read off the diff.
 *
 * Run against the Docker UAT stack, injecting the proxy key WITHOUT printing it:
 *   LITELLM_MASTER_KEY=$(docker exec am7test-litellm-1 printenv LITELLM_MASTER_KEY) \
 *   PB_LITELLM_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookLiteLlmAzure.spec.js --workers=1 --project=chromium
 *
 * Optional: LITELLM_SERVER_URL (default http://litellm:4000 — the compose-network name Tomcat sees),
 *           LITELLM_MODEL (default gpt-5.6-terra — the alias in src/litellm/config.yaml).
 *
 * Gated (PB_LITELLM_TESTS=1 + LITELLM_MASTER_KEY) and serial: it spends real Azure tokens.
 * Never uses the admin user — provisioning and every assertion run as e2etest_shared.
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig, apiLogin, createNote, deleteObject } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const JOB = REST + '/job';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));

const ENABLED = process.env.PB_LITELLM_TESTS === '1' && !!process.env.LITELLM_MASTER_KEY;
const SKIP_REASON = 'set PB_LITELLM_TESTS=1 and LITELLM_MASTER_KEY to run the live LiteLLM→Azure extraction';

const CONFIG_NAME = 'e2e-litellm-azure';
const CONNECTION_NAME = 'e2e-litellm-azure-conn';
const SERVER_URL = process.env.LITELLM_SERVER_URL || 'http://litellm:4000';
const MODEL = process.env.LITELLM_MODEL || 'gpt-5.6-terra';

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';

// Stephen's real text, 5465 bytes — below MAX_EXTRACTION_TEXT_CHARS (8000), so this is the
// single-shot path: exactly one LLM call, which is the call the 400 came back on.
const WORK_TEXT_PATH = path.resolve(SPEC_DIR, 'fixtures/bigWayOut.txt');

const LLM_TEST_TIMEOUT_MS = 15 * 60 * 1000;

async function pollJob(ctx, jobId, budgetMs) {
    const deadline = Date.now() + budgetMs;
    let last = null;
    while (Date.now() < deadline) {
        const resp = await ctx.get(JOB + '/' + jobId);
        expect(resp.status(), 'job poll').toBe(200);
        const job = await resp.json();
        const sig = `${job.status} ${job.phase || ''} ${job.current}/${job.total}`;
        if (sig !== last) {
            console.log(`[pbLiteLlm] ${jobId.slice(0, 8)} ${sig} elapsed=${job.elapsed}s`);
            last = sig;
        }
        if (job.terminal) return job;
        await new Promise(r => setTimeout(r, 3000));
    }
    throw new Error('job ' + jobId + ' did not reach a terminal state in time');
}

test.describe.configure({ mode: 'serial' });

test.describe('PictureBook extraction via LiteLLM → Azure (OPENAI_COMPAT, gated)', () => {
    let chatConfigName = null;
    let note = null;

    test.beforeAll(async () => {
        if (!ENABLED) return;
        const ctx = await pwRequest.newContext({
            baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true
        });
        try {
            await ensureSharedTestUser(ctx);
            chatConfigName = await ensureChatConfig(ctx, null, {
                configName: CONFIG_NAME,
                connectionName: CONNECTION_NAME,
                serverUrl: SERVER_URL,
                model: MODEL,
                serviceType: 'openai_compat',
                dialect: 'openai_compat',
                apiKey: process.env.LITELLM_MASTER_KEY
            });
            if (chatConfigName !== CONFIG_NAME) {
                throw new Error('ensureChatConfig did not yield the LiteLLM chat config: ' + JSON.stringify(chatConfigName));
            }

            await apiLogin(ctx, { user: SHARED_USER, password: SHARED_PASSWORD });
            const text = fs.readFileSync(WORK_TEXT_PATH, 'utf8');
            expect(text.length, 'fixture must be below the 8000-char chunk threshold').toBeLessThan(8000);
            note = await createNote(ctx, '~/Data', 'e2e-litellm-bigwayout-' + Date.now().toString(36), text);
            expect(note && note.objectId, 'work note must be created').toBeTruthy();
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        if (!ENABLED || !note || !note.objectId) return;
        const ctx = await pwRequest.newContext({
            baseURL: test.info().project.use.baseURL, ignoreHTTPSErrors: true
        });
        try {
            await apiLogin(ctx, { user: SHARED_USER, password: SHARED_PASSWORD });
            await deleteObject(ctx, 'data.note', note.objectId);
        } finally {
            await ctx.dispose();
        }
    });

    test('L1: single-shot extraction completes against azure/gpt-5.6 with no "Unknown parameter: options" 400',
        async ({ request }) => {
            test.skip(!ENABLED, SKIP_REASON);
            test.setTimeout(LLM_TEST_TIMEOUT_MS);
            await apiLogin(request, { user: SHARED_USER, password: SHARED_PASSWORD });

            const resp = await request.post(
                PB + '/' + note.objectId + '/extract-scenes-only?async=true&fresh=true',
                { data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName } });
            expect(resp.status(), 'async start must be 202 Accepted').toBe(202);
            const body = await resp.json();
            expect(body.jobId, 'a 202 must carry a jobId').toBeTruthy();
            test.info().annotations.push({ type: 'jobId', description: body.jobId });

            const done = await pollJob(request, body.jobId, LLM_TEST_TIMEOUT_MS - 60000);
            // Before the fix this surfaced as status=failed (or completed with zero scenes and the
            // 400 only in the server log). Either way the assertions below go red.
            expect(done.status, 'the run must not fail: ' + JSON.stringify(done.result || done.error || done))
                .toBe('completed');
            const scenes = (done.result && done.result.sceneList) || [];
            expect(scenes.length, 'a healthy extraction must produce scenes').toBeGreaterThan(0);
            expect(done.result.extractionComplete, 'a run that stopped early must NOT report itself complete').toBe(true);
            expect(done.result.chunked, 'bigWayOut.txt is <8000 chars so it must NOT chunk').toBe(false);
            // Only present when non-empty; a rejected/unparseable LLM response lands here by label.
            expect(done.result.failedExtractions || [], 'no LLM call may have been rejected').toEqual([]);
            for (const s of scenes) {
                expect(typeof s.title === 'string' && s.title.trim().length > 0, 'every scene needs a title').toBe(true);
            }
            console.log(`[pbLiteLlm] ${scenes.length} scene(s): ${scenes.map(s => s.title).join(' | ')}`);
        });
});
