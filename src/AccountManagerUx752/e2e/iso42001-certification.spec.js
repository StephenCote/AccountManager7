/**
 * ISO 42001 certification lifecycle (Phase 2) — live REST integration against the running Service7.
 *
 * Exercises the endpoints the certification UI drives, end to end: generate a report from a real run, request
 * certification (with a requested certifier), read the single request + its message thread, append a message,
 * approve & sign, verify, then revoke and confirm verification now fails.
 *
 * GATED behind ISO_LLM_E2E=1 (it needs a report, which needs a real run against the LLM). Run single-threaded.
 * The endpoint is an ISO-user-OWNED olio.llm.chatConfig provisioned by ensureChatConfig (route resolved by
 * resolveChatRoute: local container → LiteLLM → .42), unless ISO_LLM_ENDPOINT names an existing one.
 *   ISO_LLM_E2E=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/iso42001-certification.spec.js --workers=1 --project=chromium
 *
 * Uses the provisioned ISO user (Testers/Reporters/Certifiers/Administrators) so one principal can drive the
 * whole flow. Never admin.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureIso42001TestUser, ensureChatConfig, apiLogin, apiLogout, ensurePath } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const ISO = REST + '/iso42001';

async function jpost(ctx, url, body) {
    let resp = await ctx.post(url, { headers: { 'Content-Type': 'application/json' }, data: body });
    let text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* non-JSON */ }
    return { status: resp.status(), json, text };
}

test.describe.serial('ISO 42001 certification lifecycle (live)', () => {
    let iso = {};
    let ctx;
    let llmEndpoint = process.env.ISO_LLM_ENDPOINT || null;

    test.skip(process.env.ISO_LLM_E2E !== '1',
        'gated behind ISO_LLM_E2E=1 (needs a report from a real run; single-thread LLM)');

    test.beforeAll(async ({ request }) => {
        iso = await ensureIso42001TestUser(request);
        if (!llmEndpoint) {
            // Distinct names: the helper's defaults are the shared user's records, and a name+org search
            // that also matches another user's row is denied outright by PBAC (no groupId condition).
            llmEndpoint = await ensureChatConfig(request, null, {
                user: iso.testUserName, password: iso.testPassword,
                configName: 'e2e-iso-llm', connectionName: 'e2e-iso-conn'
            });
        }
        expect(llmEndpoint, 'an ISO-user-owned chatConfig endpoint must be provisioned').toBeTruthy();
        ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        await apiLogin(ctx, { user: iso.testUserName, password: iso.testPassword });
    });

    test.afterAll(async () => {
        if (ctx) { await apiLogout(ctx); await ctx.dispose(); }
    });

    let reportId, requestId, certId, baselineMsgCount;

    test('full lifecycle: report → request → message → approve → verify → revoke', async () => {
        test.setTimeout(300000);

        // 1. A report needs a completed run. Create a minimal campaign and launch it.
        let group = await ensurePath(ctx, 'auth.group', 'data', '~/ISO42001');
        expect(group && group.id, 'home group resolves').toBeTruthy();

        let cfg = await jpost(ctx, ISO + '/config', {
            schema: 'iso42001.testConfig', name: 'e2e-cert-' + Date.now().toString(36),
            groupId: group.id, organizationId: group.organizationId,
            moduleId: 'BIAS', testIds: ['BIAS-ATTR-002'], endpointName: llmEndpoint,
            endpointType: 'openai', samplesPerGroup: 1, tier: 1
        });
        expect(cfg.status, 'config create: ' + cfg.text).toBe(200);

        let run = await jpost(ctx, ISO + '/run', { testConfigId: cfg.json.objectId });
        expect(run.status, 'run: ' + run.text).toBe(200);
        expect(run.json.status, 'run should be COMPLETED (synchronous)').toBe('COMPLETED');

        let rpt = await jpost(ctx, ISO + '/report', {
            name: 'e2e cert report ' + Date.now().toString(36), reportType: 'COMPLIANCE', testRunIds: [run.json.objectId]
        });
        expect(rpt.status, 'report: ' + rpt.text).toBe(200);
        reportId = rpt.json.objectId;
        expect(reportId).toBeTruthy();

        // 2. Request certification, naming the ISO user as the requested certifier.
        let req = await jpost(ctx, ISO + '/certification/request', {
            reportId: reportId, certifierId: iso.user.objectId, justification: 'E2E lifecycle justification'
        });
        expect(req.status, 'request: ' + req.text).toBe(200);
        requestId = req.json.objectId;
        expect(requestId).toBeTruthy();

        // 3. Single-request read returns the justification + seeded message (Phase 2 endpoint).
        let got = await ctx.get(ISO + '/certification/request/' + requestId);
        expect(got.status()).toBe(200);
        let gotJson = JSON.parse(await got.text());
        expect(gotJson.justification).toContain('E2E lifecycle justification');
        baselineMsgCount = Array.isArray(gotJson.messages) ? gotJson.messages.length : 0;
        expect(baselineMsgCount, 'request seeded with at least one message').toBeGreaterThanOrEqual(1);

        // 4. Append a message; the thread grows.
        let msg = await jpost(ctx, ISO + '/certification/request/' + requestId + '/message', { text: 'Reviewed mitigation plan.' });
        expect(msg.status, 'append message: ' + msg.text).toBe(200);
        expect(Array.isArray(msg.json.messages) ? msg.json.messages.length : 0,
            'message thread grew after append').toBeGreaterThan(baselineMsgCount);

        // 5. Approve & sign → a certification is produced.
        let appr = await jpost(ctx, ISO + '/certification/approve/' + requestId, { note: 'Compliance Officer' });
        expect(appr.status, 'approve: ' + appr.text).toBe(200);
        certId = appr.json.objectId;
        expect(certId, 'approval yields a certification objectId').toBeTruthy();

        // 6. Verify the fresh signature — valid.
        let ver = await jpost(ctx, ISO + '/certification/verify/' + certId, {});
        expect(ver.status).toBe(200);
        expect(ver.json.valid, 'fresh certification verifies as valid: ' + ver.text).toBe(true);

        // 7. Revoke (admin) → status REVOKED.
        let rev = await jpost(ctx, ISO + '/certification/' + certId + '/revoke', { reason: 'E2E revocation' });
        expect(rev.status, 'revoke: ' + rev.text).toBe(200);
        expect(rev.json.status).toBe('REVOKED');

        // 8. Verify again — now invalid (status no longer VALID).
        let ver2 = await jpost(ctx, ISO + '/certification/verify/' + certId, {});
        expect(ver2.status).toBe(200);
        expect(ver2.json.valid, 'revoked certification must fail verification').toBe(false);
    });
});
