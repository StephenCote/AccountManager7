/**
 * SD config persistence test — verifies create/patch/search round-trip via REST API.
 *
 * Phase 6c S3/S4: olio.sd.config is now a persistable model (no longer embedded).
 * Runs against the Docker stack with PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443.
 * Does not use Vite dev-server source imports (cross-origin session cookies break those).
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

function b64(str) {
    return Buffer.from(str).toString('base64');
}

function encodePath(p) {
    return 'B64-' + Buffer.from(p).toString('base64').replace(/=/g, '%3D');
}

test.describe('SD config persistence (REST API round-trip)', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('olio.sd.config create then search then patch round-trips correctly', async ({}) => {
        test.setTimeout(60000);

        // Isolated API context for this test
        const ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });

        try {
            // Login as shared test user
            const loginResp = await ctx.post(REST + '/login', {
                data: {
                    schema: 'auth.credential',
                    organizationPath: '/Development',
                    name: testInfo.testUserName,
                    credential: b64(testInfo.testPassword),
                    type: 'hashed_password'
                }
            });
            expect(loginResp.ok(), 'login must succeed').toBe(true);

            // Ensure the preferences directory exists
            const dirResp = await ctx.get(REST + '/path/make/auth.group/DATA/' + encodePath('~/Data/.preferences'));
            expect(dirResp.ok(), 'make preferences dir must succeed').toBe(true);
            const dir = await dirResp.json().catch(() => null);
            expect(dir, 'preferences dir must be returned').toBeTruthy();
            expect(dir.id, 'dir must have numeric id').toBeGreaterThan(0);

            // Create an olio.sd.config record
            const configName = 'E2E-SDPersist-' + Date.now().toString(36);
            const createResp = await ctx.post(REST + '/model', {
                data: {
                    schema: 'olio.sd.config',
                    name: configName,
                    groupId: dir.id,
                    groupPath: dir.path,
                    style: 'photograph',
                    cfg: 9,
                    steps: 25,
                    sampler: 'Euler'
                }
            });
            expect(createResp.ok(), 'POST /rest/model for olio.sd.config must succeed').toBe(true);
            const created = await createResp.json().catch(() => null);
            console.log('[sdConfig create]', JSON.stringify(created));
            expect(created, 'create must return a record').toBeTruthy();

            // Search for the config by name + groupId
            const searchResp = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.sd.config',
                    cache: false,
                    fields: [
                        { name: 'name', comparator: 'equals', value: configName },
                        { name: 'groupId', comparator: 'equals', value: dir.id }
                    ],
                    request: ['id', 'objectId', 'name', 'style', 'cfg', 'steps', 'sampler'],
                    recordCount: 1
                }
            });
            expect(searchResp.ok(), 'search for olio.sd.config must succeed').toBe(true);
            const searchResult = await searchResp.json().catch(() => null);
            console.log('[sdConfig search]', JSON.stringify(searchResult));
            expect(searchResult, 'search result must not be null').toBeTruthy();
            const found = searchResult.results && searchResult.results[0];
            expect(found, 'must find the created olio.sd.config record').toBeTruthy();
            expect(found.name).toBe(configName);
            expect(found.style).toBe('photograph');
            expect(Number(found.cfg)).toBe(9);
            expect(Number(found.steps)).toBe(25);
            expect(found.sampler).toBe('Euler');

            // PATCH: update style only (must include name per model validation; value must be in the style limit list)
            const patchResp = await ctx.patch(REST + '/model', {
                data: {
                    schema: 'olio.sd.config',
                    id: found.id,
                    objectId: found.objectId,
                    name: found.name,
                    style: 'art'
                }
            });
            expect(patchResp.ok(), 'PATCH for olio.sd.config must succeed').toBe(true);
            const patchBody = await patchResp.text().catch(() => '');
            console.log('[sdConfig patch response]', patchBody);
            expect(patchBody.trim(), 'PATCH must return true (not silent false)').toBe('true');

            // Search again to confirm patch applied
            const searchResp2 = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.sd.config',
                    cache: false,
                    fields: [
                        { name: 'name', comparator: 'equals', value: configName },
                        { name: 'groupId', comparator: 'equals', value: dir.id }
                    ],
                    request: ['id', 'objectId', 'name', 'style'],
                    recordCount: 1
                }
            });
            const searchResult2 = await searchResp2.json().catch(() => null);
            console.log('[sdConfig after patch]', JSON.stringify(searchResult2));
            const patched = searchResult2 && searchResult2.results && searchResult2.results[0];
            expect(patched, 'patched record must be found').toBeTruthy();
            expect(patched.style, 'style must be updated to art').toBe('art');
            expect(patched.name, 'name must be unchanged after patch').toBe(configName);

        } finally {
            await ctx.get(REST + '/logout').catch(() => {});
            await ctx.dispose();
        }
    });
});
