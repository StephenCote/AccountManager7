/**
 * Group navigation in list view.
 * Uses shared test user (NEVER admin).
 * Login via actual app UI to ensure mithril client is fully initialized.
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
        if (dataDir) {
            dataDirOid = dataDir.objectId;
            // Add an object so the group is not empty
            await ctx.post(REST + '/model', {
                data: { schema: 'data.data', groupId: dataDir.id, name: 'groupnav_test_' + Date.now(), contentType: 'text/plain' }
            });
        }
        // Also create a subdir with content
        let subDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Data/TestSub').replace(/=/g, '%3D')));
        if (subDir) {
            await ctx.post(REST + '/model', {
                data: { schema: 'data.data', groupId: subDir.id, name: 'sub_test_' + Date.now(), contentType: 'text/plain' }
            });
        }
        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test('Navigate up from ~/Data via container mode', async ({ page }) => {
        expect(dataDirOid).toBeTruthy();

        // Login via API and transfer session cookie
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();

        // Load the app
        await page.goto('/');
        await page.waitForTimeout(2000);

        // Capture console for debugging
        let navLogs = [];
        page.on('console', msg => {
            let t = msg.text();
            if (t.includes('navigateUp') || t.includes('Error loading container') || t.includes('navigateToPath')) {
                navLogs.push(t);
            }
        });

        // Navigate to ~/Data as data.data
        await page.goto('#!/list/data.data/' + dataDirOid);
        await page.waitForTimeout(3000);
        await page.screenshot({ path: 'e2e/screenshots/groupnav-01-data.png' });

        // Check placeholder
        let filterInput = page.locator('#listFilter');
        if (await filterInput.isVisible({ timeout: 2000 }).catch(() => false)) {
            let plc = await filterInput.getAttribute('placeholder') || '';
            console.log('Placeholder before container toggle:', JSON.stringify(plc));
        }

        // Toggle container mode
        let containerBtn = page.locator('button:has(span:text("group_work"))');
        if (await containerBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
            await containerBtn.click();
            await page.waitForTimeout(3000);
            await page.screenshot({ path: 'e2e/screenshots/groupnav-02-container.png' });

            // Now try navigate up
            let upBtn = page.locator('button:has(span:text("north_west"))');
            if (await upBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
                let urlBefore = page.url();
                await upBtn.click();
                await page.waitForTimeout(3000);
                let urlAfter = page.url();
                await page.screenshot({ path: 'e2e/screenshots/groupnav-03-afterup.png' });

                console.log('URL before up:', urlBefore);
                console.log('URL after up:', urlAfter);
                console.log('Nav logs:', navLogs.join(' | '));

                // URL should have changed
                expect(urlAfter).not.toBe(urlBefore);
                console.log('PASS: URL changed after navigate up');
            } else {
                console.log('FAIL: north_west button not visible after container toggle');
                console.log('Nav logs:', navLogs.join(' | '));
                expect(false, 'north_west button should be visible').toBe(true);
            }
        } else {
            console.log('FAIL: group_work button not visible');
            expect(false, 'group_work button should be visible').toBe(true);
        }
    });
});
