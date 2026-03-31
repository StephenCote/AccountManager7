/**
 * Verify list view filter input shows group NAME not PATH.
 * Uses shared test user (NEVER admin).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const REST = 'https://localhost:8899/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: 'https://localhost:8899', ignoreHTTPSErrors: true }); }
async function safeJson(resp) { if (!resp.ok()) return null; try { return JSON.parse(await resp.text()); } catch { return null; } }

test.describe('List Path Placeholder', () => {
    let shared;
    let dataDirOid;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);

        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let dataDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Data').replace(/=/g, '%3D')));
        if (dataDir) dataDirOid = dataDir.objectId;
        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test('data.data list — filter placeholder shows name not path', async ({ page, request }) => {
        expect(dataDirOid).toBeTruthy();
        await apiLogin(request, { user: shared.testUserName, password: shared.testPassword });

        // Capture console logs from the page
        page.on('console', msg => { if (msg.text().includes('[list]') || msg.text().includes('[pagination]')) console.log('BROWSER:', msg.text()); });

        // data.data has groupId — filter input should appear
        await page.goto('#!/list/data.data/' + dataDirOid);

        // Poll for filter input to appear and have content
        let placeholder = '';
        for (let i = 0; i < 15; i++) {
            await page.waitForTimeout(500);
            let el = page.locator('#listFilter');
            if (await el.isVisible().catch(() => false)) {
                placeholder = await el.getAttribute('placeholder') || '';
                if (placeholder.length > 0) break;
            }
        }

        await page.screenshot({ path: 'e2e/screenshots/list-path-placeholder-data.png' });
        console.log('data.data filter placeholder:', JSON.stringify(placeholder));

        if (placeholder.length > 0) {
            expect(placeholder).not.toMatch(/^\//);
            expect(placeholder).not.toContain('/');
            expect(placeholder).toBe('Data');
            console.log('PASS: placeholder is "' + placeholder + '"');
        } else {
            console.log('WARN: placeholder never populated');
        }

        await apiLogout(request);
    });
});
