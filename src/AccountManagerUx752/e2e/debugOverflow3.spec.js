import { test, expect, request as pwRequest } from '@playwright/test';
import { login } from './helpers/auth.js';
import { setupTestUser } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function safeJson(resp) {
    if (!resp.ok()) return null;
    try { let t = await resp.text(); if (!t || t.startsWith('<')) return null; return JSON.parse(t); } catch { return null; }
}
function encodePath(p) { return 'B64-' + b64(p).replace(/=/g, '%3D'); }

test('mouse wheel scroll', async ({ page, request }) => {
    let testInfo = await setupTestUser(request, { suffix: 'ovf3' + Date.now().toString(36), noteCount: 20, notePrefix: 'OvfNote' });
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    let notesGroup;
    try {
        await ctx.post(REST + '/login', { data: { schema: 'auth.credential', organizationPath: '/Development', name: testInfo.testUserName, credential: b64(testInfo.testPassword), type: 'hashed_password' } });
        let resp = await ctx.get(REST + '/path/make/auth.group/data/' + encodePath('~/Notes'));
        notesGroup = await safeJson(resp);
    } finally { await ctx.dispose(); }

    await page.setViewportSize({ width: 1280, height: 400 });
    await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    await page.goto('/#!/list/data.note/' + notesGroup.objectId);
    await page.waitForTimeout(2000);

    let before = await page.evaluate(() => document.querySelector('.list-results > div.flex-1').scrollTop);
    await page.mouse.move(640, 300);
    await page.mouse.wheel(0, 400);
    await page.waitForTimeout(500);
    let after = await page.evaluate(() => document.querySelector('.list-results > div.flex-1').scrollTop);
    console.log('scrollTop before/after wheel:', before, after);
});
