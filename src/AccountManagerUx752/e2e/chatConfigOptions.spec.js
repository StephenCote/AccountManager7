/**
 * E2E test for chat config Options tab — verifies max_tokens (and other range
 * fields) show their actual value clearly via a number input next to the slider.
 *
 * Regression: range sliders for fields with large maxValue (e.g. max_tokens
 * 0..120000) showed thumb pinned near 0 with the actual value only as a small
 * span underneath the slider. Users couldn't tell what value was set.
 *
 * Contract under test (components/formFieldRenderers.js renderers.range + core/model.js
 * rangeDecorator): every `format: "range"` field on forms.chatOptions renders a slider named
 * `<field>` and a companion spinner named `<field>_num` carrying the SAME value. Integer fields
 * render the persisted value verbatim with bounds = schema minValue/maxValue. Double fields whose
 * schema maxValue >= 1 (temperature 0..2, top_p 0..1 is NOT scaled) are shown x100 — the decorator
 * emits `parseInt(v * 100)` and the renderer scales min/max to match — so temperature 0.7 is shown
 * as 70 on a 0..200 slider.
 *
 * Runs as the SHARED TEST USER (never admin) against a chatConfig it creates itself with
 * deliberately non-default chatOptions, so a form falling back to schema defaults cannot pass.
 * Self-cleaning: deletes the chatConfig in afterAll.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

// Non-default on purpose: schema defaults are max_tokens 4096, num_ctx 8192, temperature 1.
const MAX_TOKENS = 5120;
const NUM_CTX = 12288;
const TEMPERATURE = 0.7;            // double, schema 0..2 -> rendered x100 as 70 on a 0..200 slider
const TEMPERATURE_UI = Math.round(TEMPERATURE * 100);

// olio.llm.chatOptions schema bounds (Objects7 chatOptionsModel.json, mirrored in src/core/modelDef.js).
const SCHEMA = {
    max_tokens: { min: 0, max: 120000 },
    num_ctx: { min: 0, max: 131072 },
    temperature: { min: 0, max: 2 }
};

async function sharedApiContext(testInfo) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    let resp = await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
    if (!resp.ok() && resp.status() !== 204) {
        await ctx.dispose();
        throw new Error('shared-user REST login failed: HTTP ' + resp.status());
    }
    return ctx;
}

async function searchChatConfigByName(ctx, name, orgId) {
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'olio.llm.chatConfig',
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: ['id', 'objectId', 'name', 'chatOptions'],
            recordCount: 1,
            cache: false
        }
    });
    if (!resp.ok()) return null;
    let body = await resp.json().catch(() => null);
    return (body && body.results && body.results.length) ? body.results[0] : null;
}

test.describe('Chat config Options tab — range slider readability', () => {
    let testInfo = {};
    let orgId = null;
    let cfgName = null;
    let configObjectId = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        expect(testInfo && testInfo.testUserName).toBe('e2etest_shared');
        cfgName = 'e2e-options-tab-' + Date.now().toString(36);

        let ctx = await sharedApiContext(testInfo);
        try {
            let chatDir = await ensurePath(ctx, 'auth.group', 'data', '~/Chat');
            expect(chatDir && chatDir.id, 'ensurePath(~/Chat) must return the group').toBeTruthy();
            orgId = chatDir.organizationId;
            expect(typeof orgId).toBe('number');

            let createResp = await ctx.post(REST + '/model', {
                data: {
                    schema: 'olio.llm.chatConfig',
                    name: cfgName,
                    groupId: chatDir.id,
                    groupPath: chatDir.path,
                    serviceType: 'ollama',
                    model: 'qwen3:8b',
                    chatOptions: {
                        schema: 'olio.llm.chatOptions',
                        max_tokens: MAX_TOKENS,
                        num_ctx: NUM_CTX,
                        temperature: TEMPERATURE
                    }
                }
            });
            expect(createResp.ok(), 'POST /rest/model olio.llm.chatConfig must succeed (HTTP ' + createResp.status() + ')').toBe(true);

            // Create returns identity only; prove the values persisted so a UI mismatch below is a
            // rendering failure, not a data-never-arrived failure.
            let cfg = await searchChatConfigByName(ctx, cfgName, orgId);
            expect(cfg && cfg.objectId, 'created chatConfig must be findable by name').toBeTruthy();
            configObjectId = cfg.objectId;
            expect(cfg.chatOptions, 'chatOptions must round-trip').toBeTruthy();
            expect(Number(cfg.chatOptions.max_tokens)).toBe(MAX_TOKENS);
            expect(Number(cfg.chatOptions.num_ctx)).toBe(NUM_CTX);
            expect(Number(cfg.chatOptions.temperature)).toBeCloseTo(TEMPERATURE, 5);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        if (!configObjectId) return;
        let ctx = await sharedApiContext(testInfo);
        try {
            await deleteObject(ctx, 'olio.llm.chatConfig', configObjectId);
            let gone = await searchChatConfigByName(ctx, cfgName, orgId);
            expect(gone, 'cleanup: chatConfig ' + cfgName + ' must be deleted').toBeNull();
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    async function openOptionsTab(page) {
        await page.goto('/#!/view/olio.llm.chatConfig/' + configObjectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.llm.chatConfig/'), { timeout: 15000 });
        // Options tab (forms.chatOptionsRef.label = "Options"; tab buttons carry a material icon span)
        let optionsTab = page.locator('button:has(span.material-icons-outlined):has-text("Options")');
        await expect(optionsTab.first()).toBeVisible({ timeout: 15000 });
        await optionsTab.first().click();
    }

    test('max_tokens shows as a number input with the persisted value', async ({ page }) => {
        test.setTimeout(60000);
        await openOptionsTab(page);

        // The fix added a number input named "max_tokens_num" next to the range
        let maxTokensNumber = page.locator('input[type="number"][name="max_tokens_num"]');
        await expect(maxTokensNumber).toBeVisible({ timeout: 10000 });
        await expect(maxTokensNumber).toHaveValue(String(MAX_TOKENS));

        // The corresponding range slider carries the same value, with schema bounds (not the
        // browser's default 0..100, which would have clamped the value).
        let maxTokensRange = page.locator('input[type="range"][name="max_tokens"]');
        await expect(maxTokensRange).toBeVisible();
        await expect(maxTokensRange).toHaveValue(String(MAX_TOKENS));
        await expect(maxTokensRange).toHaveAttribute('min', String(SCHEMA.max_tokens.min));
        await expect(maxTokensRange).toHaveAttribute('max', String(SCHEMA.max_tokens.max));
        await expect(maxTokensRange).toHaveAttribute('step', '1');

        await screenshot(page, 'chat-config-options-max-tokens');
    });

    test('other range fields (num_ctx, temperature) also show number inputs with the persisted values', async ({ page }) => {
        test.setTimeout(60000);
        await openOptionsTab(page);

        // num_ctx: integer, rendered verbatim with the 131072 schema max
        let numCtxNumber = page.locator('input[type="number"][name="num_ctx_num"]');
        await expect(numCtxNumber).toBeVisible({ timeout: 10000 });
        await expect(numCtxNumber).toHaveValue(String(NUM_CTX));
        let numCtxRange = page.locator('input[type="range"][name="num_ctx"]');
        await expect(numCtxRange).toHaveValue(String(NUM_CTX));
        await expect(numCtxRange).toHaveAttribute('max', String(SCHEMA.num_ctx.max));

        // temperature: double with schema maxValue 2 (>= 1), so rangeDecorator shows it x100 and the
        // renderer scales the bounds to 0..200 to match. 0.7 persisted -> 70 displayed, in bounds.
        let tempNumber = page.locator('input[type="number"][name="temperature_num"]');
        await expect(tempNumber).toBeVisible({ timeout: 5000 });
        await expect(tempNumber).toHaveValue(String(TEMPERATURE_UI));
        let tempRange = page.locator('input[type="range"][name="temperature"]');
        await expect(tempRange).toHaveValue(String(TEMPERATURE_UI));
        await expect(tempRange).toHaveAttribute('min', String(SCHEMA.temperature.min * 100));
        await expect(tempRange).toHaveAttribute('max', String(SCHEMA.temperature.max * 100));
        let tempVal = Number(await tempNumber.inputValue());
        expect(tempVal).toBeGreaterThanOrEqual(SCHEMA.temperature.min * 100);
        expect(tempVal).toBeLessThanOrEqual(SCHEMA.temperature.max * 100);
    });

    test('typing in number input updates the slider', async ({ page }) => {
        test.setTimeout(60000);
        await openOptionsTab(page);

        let maxTokensNumber = page.locator('input[type="number"][name="max_tokens_num"]');
        let maxTokensRange = page.locator('input[type="range"][name="max_tokens"]');
        await expect(maxTokensNumber).toBeVisible({ timeout: 10000 });
        await expect(maxTokensNumber).toHaveValue(String(MAX_TOKENS));

        // Set a new value via the number input (distinct from the fixture value)
        await maxTokensNumber.fill('2048');
        await maxTokensNumber.blur();

        // Both inputs reflect the new value after the form binds the change
        await expect(maxTokensNumber).toHaveValue('2048');
        await expect(maxTokensRange).toHaveValue('2048');
    });
});
