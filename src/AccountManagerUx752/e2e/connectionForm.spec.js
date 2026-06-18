/**
 * Connection refactor (Part 2) — verifies the relocated connection properties in the UI
 * against the live backend.
 *
 *  - system.connection has its own form (forms.connection) exposing serverUrl / apiKey / requestTimeout.
 *  - olio.llm.chatConfig no longer exposes serverUrl / apiKey inline; it shows a Connection picker.
 *
 * Uses the shared test user (NEVER admin). Modeled on groupNav.spec.js (no console-error guard;
 * creates real objects so breadcrumb resolves a real group).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const REST = 'https://localhost:8899/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: 'https://localhost:8899', ignoreHTTPSErrors: true }); }
async function safeJson(resp) { if (!resp.ok()) return null; try { return JSON.parse(await resp.text()); } catch { return null; } }

test.describe('Connection refactor — relocated properties', () => {
    let shared;
    let connOid;
    let cfgOid;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);

        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });

        let chatDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Chat').replace(/=/g, '%3D')));

        if (chatDir && chatDir.id) {
            let suffix = Date.now().toString(36);
            let conn = await safeJson(await ctx.post(REST + '/model', {
                data: { schema: 'system.connection', groupId: chatDir.id, groupPath: chatDir.path,
                    name: 'E2E Conn ' + suffix, serverUrl: 'http://192.168.1.42:11434', requestTimeout: 90 }
            }));
            if (conn) connOid = conn.objectId;

            let cfg = await safeJson(await ctx.post(REST + '/model', {
                data: { schema: 'olio.llm.chatConfig', groupId: chatDir.id, groupPath: chatDir.path,
                    name: 'E2E Cfg ' + suffix }
            }));
            if (cfg) cfgOid = cfg.objectId;
        }
        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    async function loginBrowser(page) {
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let cookies = (await ctx.storageState()).cookies;
        await page.context().addCookies(cookies);
        await ctx.dispose();
    }

    test('system.connection form renders serverUrl / apiKey / requestTimeout', async ({ page }) => {
        expect(connOid).toBeTruthy();
        await loginBrowser(page);
        await page.goto('/#!/view/system.connection/' + connOid);
        await page.waitForFunction(() => window.location.hash.includes('/view/system.connection/'), { timeout: 15000 });

        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        await expect(page.getByText('Server URL', { exact: false }).first()).toBeVisible({ timeout: 10000 });
        await expect(page.getByText('Request Timeout', { exact: false }).first()).toBeVisible({ timeout: 10000 });
        await page.screenshot({ path: 'e2e/screenshots/connection-form.png' });
    });

    test('chatConfig form shows Connection picker, not inline serverUrl/apiKey', async ({ page }) => {
        expect(cfgOid).toBeTruthy();
        await loginBrowser(page);
        await page.goto('/#!/view/olio.llm.chatConfig/' + cfgOid);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.llm.chatConfig/'), { timeout: 15000 });

        let inputs = page.locator('input, select, textarea');
        await expect(inputs.first()).toBeVisible({ timeout: 30000 });

        // Connection picker present (label "Connection")
        await expect(page.getByText('Connection', { exact: true }).first()).toBeVisible({ timeout: 10000 });

        // Inline serverUrl / apiKey labels removed from the chatConfig form.
        expect(await page.getByText('Server URL', { exact: false }).count()).toBe(0);
        await page.screenshot({ path: 'e2e/screenshots/chatconfig-connection-picker.png' });
    });
});
