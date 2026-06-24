/**
 * ISO 42001 — live REST + MCP round-trip (Phase 7 thin shim), against the running Service7 (Tomcat).
 *
 * Uses the shared NON-ADMIN test user (ensureSharedTestUser) — never admin. Scope is what a plain user can
 * prove without an ISO role: the endpoints are reachable + serialize, the MCP JSON-RPC marshaling round-trips
 * (tools/list exposes the iso42001_* tools), and the RBAC boundary rejects an ISO create by a user with no ISO
 * role (the positive ISO-role create path is covered by the Service7 component test against the live DB).
 *
 * Requires: Service7 deployed to Tomcat (the iso42001 endpoints) + the Vite dev server (Playwright starts it).
 * Run: npx playwright test e2e/iso42001.spec.js --project=chromium
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

test.describe('ISO 42001 REST + MCP shim (live)', () => {
    let shared = {};

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
    });

    test('GET /iso42001/modules is reachable and lists the bias suite', async () => {
        let ctx = await newApiContext();
        try {
            await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
            let resp = await ctx.get(REST + '/iso42001/modules');
            expect(resp.status(), 'modules should be reachable for an authenticated user').toBe(200);
            let body = await resp.text();
            expect(body, 'modules listing must include BIAS-ATTR-002').toContain('BIAS-ATTR-002');
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('negative RBAC: a non-ISO-role user cannot create a testConfig', async () => {
        let ctx = await newApiContext();
        try {
            await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
            let resp = await ctx.post(REST + '/iso42001/config', {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    schema: 'iso42001.testConfig',
                    name: 'e2e-denied-' + Date.now().toString(36),
                    organizationId: shared.user && shared.user.organizationId,
                    moduleId: 'BIAS',
                    endpointName: 'spark-ollama',
                    endpointType: 'ollama',
                    samplesPerGroup: 2,
                    tier: 1
                }
            });
            // The model-level create role is ISO42001Testers; a plain shared user has no ISO role,
            // so the boundary must reject (403 from the service when AccessPoint.create returns null).
            expect([401, 403], 'create without an ISO role must be denied, got ' + resp.status())
                .toContain(resp.status());
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('MCP JSON-RPC round-trip exposes the iso42001_* tools', async () => {
        let ctx = await newApiContext();
        try {
            await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });

            // initialize → capture the Mcp-Session-Id
            let initResp = await ctx.post(REST + '/mcp', {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    jsonrpc: '2.0', id: 1, method: 'initialize',
                    params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } }
                }
            });
            expect(initResp.status(), 'MCP initialize should succeed').toBe(200);
            let sessionId = initResp.headers()['mcp-session-id'];
            expect(sessionId, 'MCP initialize must return a session id').toBeTruthy();

            // Per the MCP handshake, send notifications/initialized before any other request (else the
            // server rejects with "Session not yet initialized").
            let notifResp = await ctx.post(REST + '/mcp', {
                headers: { 'Content-Type': 'application/json', 'Mcp-Session-Id': sessionId },
                data: { jsonrpc: '2.0', method: 'notifications/initialized', params: {} }
            });
            expect(notifResp.status(), 'initialized notification should be accepted (204)').toBe(204);

            // tools/list → must include the four ISO 42001 tools (composed alongside the am7_ tools)
            let listResp = await ctx.post(REST + '/mcp', {
                headers: { 'Content-Type': 'application/json', 'Mcp-Session-Id': sessionId },
                data: { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }
            });
            expect(listResp.status()).toBe(200);
            let body = await listResp.text();
            expect(body, 'tools/list must include iso42001_run_test').toContain('iso42001_run_test');
            expect(body, 'tools/list must include iso42001_certify').toContain('iso42001_certify');
            expect(body, 'tools/list must still include the core am7_ tools').toContain('am7_');

            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });
});
