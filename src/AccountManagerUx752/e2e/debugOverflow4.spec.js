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

test('grid mode overflow at short viewport', async ({ page, request }) => {
    let testInfo = await setupTestUser(request, { suffix: 'ovf4' + Date.now().toString(36), noteCount: 20, notePrefix: 'OvfNote' });
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    let notesGroup;
    try {
        await ctx.post(REST + '/login', { data: { schema: 'auth.credential', organizationPath: '/Development', name: testInfo.testUserName, credential: b64(testInfo.testPassword), type: 'hashed_password' } });
        let resp = await ctx.get(REST + '/path/make/auth.group/data/' + encodePath('~/Notes'));
        notesGroup = await safeJson(resp);
    } finally { await ctx.dispose(); }

    await page.setViewportSize({ width: 900, height: 400 });
    await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    await page.goto('/#!/list/data.note/' + notesGroup.objectId);
    await page.waitForTimeout(2000);

    // toggle to grid mode
    let gridBtn = page.locator('button:has(span:text("apps")), button:has(span:text("grid_view"))').first();
    await gridBtn.click();
    await page.waitForTimeout(2000);
    await page.screenshot({ path: 'e2e/screenshots/debugOverflow-grid.png', fullPage: false });

    let info = await page.evaluate(() => {
        function measure(sel) {
            let el = document.querySelector(sel);
            if (!el) return { sel, missing: true };
            let cs = getComputedStyle(el);
            return { sel, scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, overflowY: cs.overflowY };
        }
        return ['.grid-preview-container', '.grid-preview-container .flex-1.overflow-y-auto', '.image-grid-tile', '.image-grid-5'].map(measure);
    });
    console.log('GRID INFO', JSON.stringify(info, null, 2));

    // also check toolbar wrap
    let toolbarInfo = await page.evaluate(() => {
        let el = document.querySelector('.result-nav-outer');
        if (!el) return null;
        let r = el.getBoundingClientRect();
        return { height: r.height, top: r.top, bottom: r.bottom };
    });
    console.log('TOOLBAR', JSON.stringify(toolbarInfo));
});
