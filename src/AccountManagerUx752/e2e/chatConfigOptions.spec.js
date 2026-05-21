/**
 * E2E test for chat config Options tab — verifies max_tokens (and other range
 * fields) show their actual value clearly via a number input next to the slider.
 *
 * Regression: range sliders for fields with large maxValue (e.g. max_tokens
 * 0..120000) showed thumb pinned near 0 with the actual value only as a small
 * span underneath the slider. Users couldn't tell what value was set.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

async function findChatConfig() {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let loginResp = await apiLogin(ctx);
        if (!loginResp.ok()) throw new Error('admin login failed');
        let resp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.llm.chatConfig',
                request: ['id', 'objectId', 'name', 'chatOptions'],
                recordCount: 10
            }
        });
        if (!resp.ok()) return null;
        let body = await resp.json();
        if (!body || !body.results || !body.results.length) return null;
        let openChat = body.results.find(c => c.name === 'Open Chat');
        let chosen = openChat || body.results[0];
        await apiLogout(ctx);
        return chosen;
    } finally {
        await ctx.dispose();
    }
}

test.describe('Chat config Options tab — range slider readability', () => {
    let testInfo = {};
    let configObjectId = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        let cfg = await findChatConfig();
        expect(cfg).not.toBeNull();
        expect(cfg.objectId).toBeTruthy();
        configObjectId = cfg.objectId;
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: 'admin', password: 'password' });
    });

    test('max_tokens shows as a number input with non-zero value', async ({ page }) => {
        test.setTimeout(60000);

        // Navigate directly to the chat config editor
        await page.goto('/#!/view/olio.llm.chatConfig/' + configObjectId);
        await page.waitForTimeout(3000);

        // Click the Options tab (forms.chatOptionsRef.label = "Options")
        let optionsTab = page.locator('button:has(span.material-icons-outlined):has-text("Options")');
        await expect(optionsTab.first()).toBeVisible({ timeout: 15000 });
        await optionsTab.first().click();
        await page.waitForTimeout(1500);

        // The fix added a number input named "max_tokens_num" next to the range
        let maxTokensNumber = page.locator('input[name="max_tokens_num"]');
        await expect(maxTokensNumber).toBeVisible({ timeout: 10000 });

        let val = await maxTokensNumber.inputValue();
        // Non-empty, parseable, and > 0 (the chatOptions schema default is 4096)
        expect(val).not.toBe('');
        let n = Number(val);
        expect(Number.isFinite(n)).toBe(true);
        expect(n).toBeGreaterThan(0);

        // The corresponding range slider should be present and have the same value
        let maxTokensRange = page.locator('input[type="range"][name="max_tokens"]');
        await expect(maxTokensRange).toBeVisible();
        let rangeVal = await maxTokensRange.inputValue();
        expect(Number(rangeVal)).toBe(n);

        await screenshot(page, 'chat-config-options-max-tokens');
    });

    test('other range fields (num_ctx, temperature) also show number inputs', async ({ page }) => {
        test.setTimeout(60000);

        await page.goto('/#!/view/olio.llm.chatConfig/' + configObjectId);
        await page.waitForTimeout(3000);

        let optionsTab = page.locator('button:has(span.material-icons-outlined):has-text("Options")');
        await expect(optionsTab.first()).toBeVisible({ timeout: 15000 });
        await optionsTab.first().click();
        await page.waitForTimeout(1500);

        // num_ctx default is 8192 — same kind of bug as max_tokens before the fix
        let numCtxNumber = page.locator('input[name="num_ctx_num"]');
        await expect(numCtxNumber).toBeVisible({ timeout: 10000 });
        let numCtxVal = Number(await numCtxNumber.inputValue());
        expect(numCtxVal).toBeGreaterThan(0);

        // temperature is a double in [0,2] — should also have number input
        let tempNumber = page.locator('input[name="temperature_num"]');
        await expect(tempNumber).toBeVisible({ timeout: 5000 });
        let tempVal = Number(await tempNumber.inputValue());
        expect(tempVal).toBeGreaterThanOrEqual(0);
        expect(tempVal).toBeLessThanOrEqual(2);
    });

    test('typing in number input updates the slider', async ({ page }) => {
        test.setTimeout(60000);

        await page.goto('/#!/view/olio.llm.chatConfig/' + configObjectId);
        await page.waitForTimeout(3000);

        let optionsTab = page.locator('button:has(span.material-icons-outlined):has-text("Options")');
        await expect(optionsTab.first()).toBeVisible({ timeout: 15000 });
        await optionsTab.first().click();
        await page.waitForTimeout(1500);

        let maxTokensNumber = page.locator('input[name="max_tokens_num"]');
        let maxTokensRange = page.locator('input[type="range"][name="max_tokens"]');
        await expect(maxTokensNumber).toBeVisible({ timeout: 10000 });

        // Set a new value via the number input
        await maxTokensNumber.fill('2048');
        await maxTokensNumber.blur();
        await page.waitForTimeout(500);

        // Both inputs should reflect the new value after the form binds the change
        let numberVal = await maxTokensNumber.inputValue();
        let rangeVal = await maxTokensRange.inputValue();
        expect(Number(numberVal)).toBe(2048);
        expect(Number(rangeVal)).toBe(2048);
    });
});
