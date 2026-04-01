/**
 * Group navigation in list view — navigate up changes container, stays in same view.
 * Uses shared test user (NEVER admin).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const REST = 'https://localhost:8899/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: 'https://localhost:8899', ignoreHTTPSErrors: true }); }
async function safeJson(resp) { if (!resp.ok()) return null; try { return JSON.parse(await resp.text()); } catch { return null; } }

test.describe('Group Navigation', () => {
    let shared;
    let dataDirOid, subDirOid;

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

        let subDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Data/TestSub').replace(/=/g, '%3D')));
        if (subDir) {
            subDirOid = subDir.objectId;
            await ctx.post(REST + '/model', {
                data: { schema: 'data.data', groupId: subDir.id, name: 'nav_test_' + Date.now(), contentType: 'text/plain' }
            });
        }

        console.log('~/Data oid:', dataDirOid, '~/Data/TestSub oid:', subDirOid);
        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test('Navigate up from ~/Data/TestSub stays in list view, changes container to ~/Data', async ({ page }) => {
        expect(subDirOid).toBeTruthy();
        expect(dataDirOid).toBeTruthy();

        // Login via API cookies
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();

        await page.goto('/');
        await page.waitForTimeout(1500);

        let navLogs = [];
        page.on('console', msg => {
            let t = msg.text();
            if (t.includes('navigateUp') || t.includes('Navigate') || t.includes('listByType') || t.includes('Error loading'))
                navLogs.push(t);
        });

        // Start at ~/Data/TestSub as data.data list
        await page.goto('#!/list/data.data/' + subDirOid);
        await page.waitForTimeout(3000);

        // Check placeholder shows the full path
        let filterInput = page.locator('#listFilter');
        if (await filterInput.isVisible({ timeout: 2000 }).catch(() => false)) {
            let plc = await filterInput.getAttribute('placeholder') || '';
            console.log('Placeholder at TestSub:', JSON.stringify(plc));
            expect(plc).toContain('TestSub');
        }

        // Toggle container mode
        let containerBtn = page.locator('button:has(span:text("group_work"))');
        await expect(containerBtn).toBeVisible({ timeout: 3000 });
        await containerBtn.click();
        await page.waitForTimeout(2000);

        // Navigate up
        let upBtn = page.locator('button:has(span:text("north_west"))');
        await expect(upBtn).toBeVisible({ timeout: 3000 });
        await upBtn.click();
        await page.waitForTimeout(3000);

        let urlAfter = page.url();
        console.log('URL after navigate up:', urlAfter);
        if (navLogs.length) console.log('Nav logs:', navLogs.slice(0, 3).join(' | '));

        await page.screenshot({ path: 'e2e/screenshots/groupnav-afterup.png' });

        // Should still be in a list view (not /main)
        expect(urlAfter).toContain('/list/');
        // Should contain ~/Data's objectId (the parent)
        expect(urlAfter).toContain(dataDirOid);
        console.log('PASS: navigated up to ~/Data container');
    });
});
