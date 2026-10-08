/**
 * REST-level tests for three Service7 routes touched on 2026-10-07 (LLM lane items 13/14/15):
 *
 *   13. POST /rest/chat/chain?async=true  +  GET /rest/chat/chain/status/{planId}
 *       The status route used to be a placeholder; it now reads the AsyncJobRegistry. Also covers
 *       the escJson fix: the chain result embeds an MCP block with literal newlines, which used to
 *       produce an unparseable body.
 *   14. POST /rest/chat/{objectId}/generateScene — an unrecognized compositeMode is rejected with
 *       400 BEFORE any session/config lookup (previously silently downgraded to "classic").
 *   15. POST /rest/olio/olio.charPerson/{objectId}/reimage — the literal "((DEPRECATED))" prompt
 *       argument was removed end-to-end (OlioService → SDUtil → SWUtil → NarrativeUtil). This hits
 *       the live Swarm SD server configured on the stack and is gated behind E2E_SD_LIVE=1 because
 *       it generates a real image (30-120 s) and must run serially. Also asserts the rendered PNG is
 *       the 512x512 the posted sdConfig asked for (SWUtil.newTxt2Img now honors width/height).
 *
 * All calls run as the shared non-admin test user (ensureSharedTestUser), never admin.
 * Run: PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chatChainSceneReimageRoutes.spec.js --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { randomUUID } from 'crypto';
import { gunzipSync } from 'node:zlib';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

async function userContext(request) {
    const shared = await ensureSharedTestUser(request);
    const ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    const login = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(login.ok(), 'shared test user login').toBe(true);
    return { ctx, shared };
}

async function pollChainStatus(ctx, jobId, maxMs = 30000) {
    const started = Date.now();
    let last = null;
    while (Date.now() - started < maxMs) {
        const resp = await ctx.get(REST + '/chat/chain/status/' + jobId);
        expect(resp.status(), 'status route should answer 200 for an owned job').toBe(200);
        const text = await resp.text();
        /// JSON.parse is the point: the result embeds an MCP block containing newlines, and the
        /// old escJson did not escape control characters, so this used to throw.
        last = JSON.parse(text);
        if (last.terminal) return last;
        await new Promise(r => setTimeout(r, 500));
    }
    return last;
}

test.describe('chat chain async status route (item 13)', () => {

    test('unknown planId answers 404 with status "unknown"', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const resp = await ctx.get(REST + '/chat/chain/status/' + randomUUID());
            expect(resp.status()).toBe(404);
            const body = await resp.json();
            expect(body.status).toBe('unknown');
            expect(body.error).toContain('No chain job');
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('unauthenticated status request is rejected', async () => {
        const ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            const resp = await ctx.get(REST + '/chat/chain/status/' + randomUUID());
            expect([401, 403]).toContain(resp.status());
        } finally {
            await ctx.dispose();
        }
    });

    test('missing planQuery answers 400', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const resp = await ctx.post(REST + '/chat/chain', {
                data: { plan: { schema: 'tool.plan', name: 'e2e-no-query', executed: true, steps: [] } }
            });
            expect(resp.status()).toBe(400);
            const body = await resp.json();
            expect(body.error).toContain('planQuery');
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('async chain with an already-executed plan completes and status returns a parseable result', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const name = 'e2e-chain-' + randomUUID().slice(0, 8);
            const submit = await ctx.post(REST + '/chat/chain?async=true', {
                data: {
                    planQuery: 'e2e async chain ' + name,
                    /// executed:true makes ChainExecutor return early (no LLM, no tool calls), so the
                    /// test exercises the job plumbing deterministically.
                    plan: { schema: 'tool.plan', name: name, executed: true, steps: [] }
                }
            });
            expect(submit.status(), 'async submit should be accepted').toBe(202);
            const accepted = await submit.json();
            expect(accepted.jobId).toBeTruthy();

            const status = await pollChainStatus(ctx, accepted.jobId);
            expect(status, 'job should reach a terminal state').toBeTruthy();
            expect(status.planId).toBe(accepted.jobId);
            expect(status.jobId).toBe(accepted.jobId);
            expect(status.kind).toBe('chat.chain');
            expect(status.terminal).toBe(true);
            expect(status.status).toBe('completed');
            expect(status.cancelled).toBe(false);
            expect(status.result).toBeTruthy();
            expect(status.result.status).toBe('complete');
            expect(status.result.planQuery).toBe('e2e async chain ' + name);
            expect(status.result.mcpContext).toContain('am7://chain/' + name);
            expect(status.result.mcpContext).toContain('\n');
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('async chain with an empty unexecuted plan reports failed with the executor error', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const name = 'e2e-chain-fail-' + randomUUID().slice(0, 8);
            const submit = await ctx.post(REST + '/chat/chain?async=true', {
                data: {
                    planQuery: 'e2e failing chain ' + name,
                    plan: { schema: 'tool.plan', name: name, executed: false, steps: [] }
                }
            });
            expect(submit.status()).toBe(202);
            const accepted = await submit.json();

            const status = await pollChainStatus(ctx, accepted.jobId);
            expect(status.terminal).toBe(true);
            expect(status.status).toBe('failed');
            expect(status.error).toContain('No steps in plan');
            expect(status.result).toBeUndefined();
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('a job submitted by another user is not visible', async ({ request }) => {
        const { ctx } = await userContext(request);
        const other = await ensureSharedTestUser(request, { name: 'e2etest_chain_other' });
        const otherCtx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            const submit = await ctx.post(REST + '/chat/chain?async=true', {
                data: { planQuery: 'e2e owner scope', plan: { schema: 'tool.plan', name: 'e2e-scope', executed: true, steps: [] } }
            });
            expect(submit.status()).toBe(202);
            const accepted = await submit.json();

            const login = await apiLogin(otherCtx, { user: other.testUserName, password: other.testPassword });
            expect(login.ok()).toBe(true);
            const resp = await otherCtx.get(REST + '/chat/chain/status/' + accepted.jobId);
            expect(resp.status(), 'foreign job must look unknown').toBe(404);
        } finally {
            await apiLogout(otherCtx);
            await otherCtx.dispose();
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });
});

test.describe('generateScene composite mode validation (item 14)', () => {

    test('unsupported compositeMode answers 400 before any session lookup', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const resp = await ctx.post(REST + '/chat/' + randomUUID() + '/generateScene', {
                data: { schema: 'olio.sd.config', compositeMode: 'bogus' }
            });
            expect(resp.status()).toBe(400);
            const body = await resp.json();
            expect(body.error).toBe("Unsupported composite mode 'bogus'; expected flux2|kontext|classic");
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('each supported compositeMode passes validation and reaches the session lookup', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            for (const mode of ['flux2', 'kontext', 'classic', 'KONTEXT']) {
                const resp = await ctx.post(REST + '/chat/' + randomUUID() + '/generateScene', {
                    data: { schema: 'olio.sd.config', compositeMode: mode }
                });
                expect(resp.status(), 'mode ' + mode + ' should not be rejected as unsupported').toBe(404);
                const body = await resp.json();
                expect(body.error).toBe('Chat request not found');
            }
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });
});

test.describe('charPerson reimage without the deprecated prompt argument (item 15)', () => {
    test.skip(!process.env.E2E_SD_LIVE, 'set E2E_SD_LIVE=1 to generate a real image on the stack SD server');

    test('POST /olio/olio.charPerson/{id}/reimage returns a new portrait', async ({ request }) => {
        test.setTimeout(400000);
        const { ctx, shared } = await userContext(request);
        let profileId = null;
        let originalPortraitId = null;
        try {
            /// ensureSharedTestUser only projects id/objectId/name; the principal route carries the
            /// numeric organizationId the search condition needs (typed long, so a number).
            const princResp = await ctx.get(REST + '/principal');
            expect(princResp.ok()).toBe(true);
            const principal = await princResp.json();
            expect(principal.name).toBe(shared.testUserName);

            /// A character the shared user owns (its PictureBook/ChapBook test data) — reimage needs
            /// a fully populated person (profile, narrative, apparel) for prompt assembly.
            const search = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.charPerson',
                    request: ['id', 'objectId', 'name', 'ownerId'],
                    fields: [
                        { name: 'organizationId', comparator: 'EQUALS', value: principal.organizationId },
                        { name: 'ownerId', comparator: 'EQUALS', value: principal.id }
                    ],
                    recordCount: 1,
                    cache: false
                }
            });
            expect(search.ok()).toBe(true);
            const found = (await search.json()).results || [];
            test.skip(found.length === 0, 'shared test user owns no olio.charPerson to reimage');
            const person = found[0];

            const full = await ctx.get(REST + '/model/olio.charPerson/' + person.objectId + '/full');
            expect(full.ok()).toBe(true);
            const fullPerson = await full.json();
            profileId = fullPerson.profile ? fullPerson.profile.id : null;
            originalPortraitId = fullPerson.profile && fullPerson.profile.portrait ? fullPerson.profile.portrait.id : null;

            /// Same template the Ux uses (sdConfig.fetchTemplate → /olio/randomImageConfig), so the
            /// checkpoint name is the one this deployment actually has installed.
            const tmplResp = await ctx.get(REST + '/olio/randomImageConfig');
            expect(tmplResp.ok()).toBe(true);
            const sdConfig = await tmplResp.json();
            sdConfig.hires = false;
            sdConfig.steps = 12;
            sdConfig.width = 512;
            sdConfig.height = 512;
            sdConfig.seed = -1;
            sdConfig.imageSetting = 'a quiet city park at noon';
            sdConfig.bodyStyle = 'full body';
            sdConfig.imageAction = 'standing';
            delete sdConfig.style;
            delete sdConfig.landscapeSetting;
            delete sdConfig.referenceImageId;

            const started = Date.now();
            const resp = await ctx.post(REST + '/olio/olio.charPerson/' + person.objectId + '/reimage', {
                data: sdConfig, timeout: 380000
            });
            const elapsed = Date.now() - started;
            expect(resp.status()).toBe(200);
            const text = await resp.text();
            console.log('[item15] reimage ' + person.name + ' took ' + elapsed + 'ms, body chars=' + text.length);
            expect(text.length, 'reimage should return the new portrait record, not {}').toBeGreaterThan(2);
            const image = JSON.parse(text);
            expect(image.schema).toBe('data.data');
            expect(image.objectId).toBeTruthy();
            expect(image.contentType).toContain('image');
            console.log('[item15] new portrait objectId=' + image.objectId + ' name=' + image.name + ' group=' + image.groupPath);

            /// The response is the only read path the acting user has for this record (it is created
            /// as the olio principal in the world gallery), so inspect the bytes here: a real PNG whose
            /// IHDR carries the 512x512 the posted sdConfig asked for. The Swarm portrait path used to
            /// drop width/height and render at the request object's 1024x1024 default.
            let raw = Buffer.from(image.dataBytesStore || '', 'base64');
            if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
            expect(raw.subarray(0, 8).toString('hex'), 'portrait bytes are a PNG').toBe('89504e470d0a1a0a');
            const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
            console.log('[item15] rendered ' + dims[0] + 'x' + dims[1]);
            expect(dims, 'rendered dimensions follow the posted sdConfig width/height').toEqual([512, 512]);
        } finally {
            /// Put the character's portrait back the way we found it (reimage re-points profile.portrait).
            if (profileId && originalPortraitId) {
                await ctx.patch(REST + '/model', {
                    data: { schema: 'identity.profile', id: profileId, portrait: { schema: 'data.data', id: originalPortraitId } }
                }).catch(() => {});
            }
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });
});
