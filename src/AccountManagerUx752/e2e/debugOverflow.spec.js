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

test('debug overflow at short viewport', async ({ page, request }) => {
    let testInfo = await setupTestUser(request, { suffix: 'ovf' + Date.now().toString(36), noteCount: 20, notePrefix: 'OvfNote' });

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

    let info = await page.evaluate(async () => {
        function measure(sel) {
            let el = document.querySelector(sel);
            if (!el) return { sel, missing: true };
            let r = el.getBoundingClientRect();
            let cs = getComputedStyle(el);
            return { sel, scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, rectHeight: Math.round(r.height), top: Math.round(r.top), overflowY: cs.overflowY, display: cs.display, minHeight: cs.minHeight, maxHeight: cs.maxHeight };
        }
        return ['main[role=main]', 'div.content-outer', 'div.content-main', '.list-results-container', '.list-results', '.list-results > div.flex-1', '.tabular-results-table', '.tabular-results-overflow'].map(measure);
    });
    console.log(JSON.stringify(info, null, 2));
    await page.screenshot({ path: 'e2e/screenshots/debugOverflow-main.png', fullPage: false });
});
