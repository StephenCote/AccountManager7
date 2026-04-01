/**
 * Group navigation — up/down changes container in-place, stays in same view.
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

    test('Navigate up from ~/Data changes container to home, stays in list view', async ({ page }) => {
        expect(dataDirOid).toBeTruthy();

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
            if (t.includes('NAV-UP') || t.includes('[pagination]')) navLogs.push(t);
        });

        // Start at ~/Data as data.data list
        await page.goto('#!/list/data.data/' + dataDirOid);
        await page.waitForTimeout(3000);

        // Verify placeholder shows the path
        let filterInput = page.locator('#listFilter');
        if (await filterInput.isVisible({ timeout: 2000 }).catch(() => false)) {
            let plc = await filterInput.getAttribute('placeholder') || '';
            console.log('Placeholder at ~/Data:', JSON.stringify(plc));
            expect(plc).toContain('Data');
        }

        await page.screenshot({ path: 'e2e/screenshots/groupnav-01-before.png' });

        // Toggle container mode
        let containerBtn = page.locator('button:has(span:text("group_work"))');
        await expect(containerBtn).toBeVisible({ timeout: 3000 });
        await containerBtn.click();
        await page.waitForTimeout(2000);

        // Wait for container to load after mode toggle
        await page.waitForTimeout(3000);

        // Navigate up
        let upBtn = page.locator('button:has(span:text("north_west"))');
        await expect(upBtn).toBeVisible({ timeout: 3000 });

        let urlBefore = page.url();
        await upBtn.click();
        await page.waitForTimeout(3000);

        let urlAfter = page.url();
        await page.screenshot({ path: 'e2e/screenshots/groupnav-02-after.png' });

        console.log('URL before:', urlBefore);
        console.log('URL after:', urlAfter);
        console.log('Nav logs:', navLogs.join(' | '));

        // URL should NOT have changed — container changed in-place
        expect(urlAfter).toBe(urlBefore);

        // Placeholder should now show the parent path (home)
        if (await filterInput.isVisible({ timeout: 2000 }).catch(() => false)) {
            let plcAfter = await filterInput.getAttribute('placeholder') || '';
            console.log('Placeholder after up:', JSON.stringify(plcAfter));
            // Parent of ~/Data is ~/ (home dir) — should NOT contain "Data"
            expect(plcAfter).not.toContain('/Data');
        }

        console.log('PASS: navigate up changed container in-place');
    });
});
