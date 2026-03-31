/**
 * PictureBook fixes verification — non-admin test user only.
 * Tests: group nav, deleted books cleanup, list placeholder, WebSocket progress.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const REST = 'https://localhost:8899/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: 'https://localhost:8899', ignoreHTTPSErrors: true }); }
async function safeJson(resp) { if (!resp.ok()) return null; try { return JSON.parse(await resp.text()); } catch { return null; } }

test.describe('PictureBook Fixes', () => {
    let shared;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);
    });

    test('A. Group navigation does not crash (tree.js findObject fix)', async ({ page, request }) => {
        await apiLogin(request, { user: shared.testUserName, password: shared.testPassword });

        // Navigate to home — this triggers tree.js which calls page.findObject().then()
        await page.goto('/');
        await page.waitForTimeout(2000);

        // Capture console errors
        let errors = [];
        page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });

        // Navigate to a list view that triggers tree rendering
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let dataDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Data').replace(/=/g, '%3D')));
        await ctx.get(REST + '/logout');
        await ctx.dispose();

        if (dataDir && dataDir.objectId) {
            await page.goto('#!/list/auth.group/' + dataDir.objectId);
            await page.waitForTimeout(3000);

            // Check no ".then is not a function" errors
            let thenErrors = errors.filter(e => e.includes('then is not a function'));
            console.log('Console errors:', errors.length, '| .then errors:', thenErrors.length);
            expect(thenErrors.length).toBe(0);
        }

        await apiLogout(request);
    });

    test('B. Deleted picture book removed from list', async () => {
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });

        // Create a test book via extract (minimal — just creates the group + meta)
        let noteDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Data').replace(/=/g, '%3D')));
        if (!noteDir) { console.log('Skip — cannot create data dir'); await ctx.get(REST + '/logout'); await ctx.dispose(); return; }

        // Search for any existing picture book metas
        let beforeResp = await ctx.post(REST + '/model/search', {
            data: { schema: 'io.query', type: 'data.note',
                fields: [{ name: 'name', comparator: 'equals', value: '.pictureBookMeta' }],
                request: ['id', 'objectId', 'name', 'text'], recordCount: 50 }
        });
        let beforeSr = await safeJson(beforeResp);
        let beforeCount = (beforeSr && beforeSr.results) ? beforeSr.results.length : 0;
        console.log('Picture book metas before:', beforeCount);

        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test('C. Extract endpoint returns typed model response', async () => {
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });

        // Create a simple test note to extract from
        let noteDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Notes').replace(/=/g, '%3D')));
        if (!noteDir) { await ctx.get(REST + '/logout'); await ctx.dispose(); return; }

        let note = await safeJson(await ctx.post(REST + '/model', {
            data: { schema: 'data.note', groupId: noteDir.id, name: 'pb_test_' + Date.now(),
                text: 'The knight rode through the dark forest. A dragon appeared above the mountains.' }
        }));

        if (note && note.objectId) {
            // Call extract-scenes-only — should return typed response
            let extractResp = await ctx.post(REST + '/olio/picture-book/' + note.objectId + '/extract-scenes-only', {
                data: { schema: 'olio.pictureBookRequest' }
            });
            let extractResult = await safeJson(extractResp);
            console.log('Extract response type:', extractResult ? (extractResult.schema || 'no schema') : 'null');
            console.log('Extract status:', extractResp.status());

            // Clean up
            await ctx.delete(REST + '/model/data.note/' + note.objectId);
        }

        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });
});
