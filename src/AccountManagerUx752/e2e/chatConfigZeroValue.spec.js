/**
 * E2E test for the "0 value gets reset to schema default" bug on chat config
 * int fields (keyframeEvery, remindEvery, memoryExtractionEvery, etc).
 *
 * Root cause: clearing a number input briefly sends v="" through handleChange,
 * which let parseInt("") = NaN flow into the entity. NaN -> JSON null on save
 * -> server stored null -> on re-read the client decorator's `v == undefined`
 * (loose equality, catches null) substituted the schema default.
 *
 * Fix: handleChange skips the update AND sets e.redraw=false while the input
 * is empty / mid-typing / not yet a valid number. Without e.redraw=false the
 * intervening redraw snaps the input back to the prior value and the user
 * can never type a fresh value cleanly.
 *
 * Test strategy: UI-only. Navigate to a chat config, set the field to a
 * known non-zero value via UI + save, reload, confirm. Then set to 0 via UI
 * + save, reload, confirm the displayed value is 0 not the default.
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
                request: ['id', 'objectId', 'name'],
                recordCount: 10
            }
        });
        if (!resp.ok()) return null;
        let body = await resp.json();
        if (!body || !body.results || !body.results.length) return null;
        let chosen = body.results.find(c => c.name === 'Open Chat') || body.results[0];
        await apiLogout(ctx);
        return chosen;
    } finally {
        await ctx.dispose();
    }
}

/// Set a numeric field via the UI: clear input, type value, click Save.
/// Returns the input's displayed value after save (string).
async function setFieldViaUi(page, fieldName, value) {
    let input = page.locator('input[name="' + fieldName + '"]').first();
    await expect(input).toBeVisible({ timeout: 10000 });

    await input.click();
    await input.fill('');
    await page.waitForTimeout(150);
    await input.type(String(value));
    await page.waitForTimeout(150);

    let typed = await input.inputValue();
    if (typed !== String(value)) {
        return { typed, saved: null, error: 'typed value mismatch — UI may have snapped input back' };
    }

    let saveBtn = page.locator('button:has(span.material-symbols-outlined:text("save"))').first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });
    await saveBtn.click();
    await page.waitForTimeout(2000);

    return { typed, saved: typed, error: null };
}

async function readFieldFromUi(page, fieldName) {
    let input = page.locator('input[name="' + fieldName + '"]').first();
    await expect(input).toBeVisible({ timeout: 10000 });
    return input.inputValue();
}

test.describe('Chat config — int fields accept 0 and round-trip correctly', () => {
    let configObjectId = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        let cfg = await findChatConfig();
        expect(cfg).not.toBeNull();
        expect(cfg.objectId).toBeTruthy();
        configObjectId = cfg.objectId;
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: 'admin', password: 'password' });
    });

    test('keyframeEvery: typing 0 results in input showing 0 (not snapping to default)', async ({ page }) => {
        test.setTimeout(90000);

        await page.goto('/#!/view/olio.llm.chatConfig/' + configObjectId);
        await page.waitForTimeout(2500);

        // PRIMARY ASSERTION: the typing/snapping bug. The earlier broken
        // behaviour was: clear input -> Mithril redraws and snaps input back
        // to prior value -> user types "0" -> input shows "60" or "06"
        // (depending on cursor position) -> entity gets a wrong number, never 0.
        // With the fix, typed value is exactly what the user typed.
        let result = await setFieldViaUi(page, 'keyframeEvery', 0);
        expect(result.error).toBeNull();
        expect(result.typed).toBe('0');

        await screenshot(page, 'chat-config-keyframeEvery-0-typed');

        // SECONDARY ASSERTION: round-trip through reload. Even if the typing
        // works, the value needs to actually persist and come back as 0 (not
        // get clobbered to the schema default on re-read).
        await page.reload();
        await page.waitForTimeout(2500);
        let displayed = await readFieldFromUi(page, 'keyframeEvery');
        expect(displayed).toBe('0');
    });

    test('keyframeEvery: typing 5 results in input showing 5 after reload', async ({ page }) => {
        test.setTimeout(90000);

        await page.goto('/#!/view/olio.llm.chatConfig/' + configObjectId);
        await page.waitForTimeout(2500);

        let result = await setFieldViaUi(page, 'keyframeEvery', 5);
        expect(result.error).toBeNull();
        expect(result.typed).toBe('5');

        await page.reload();
        await page.waitForTimeout(2500);
        let displayed = await readFieldFromUi(page, 'keyframeEvery');
        expect(displayed).toBe('5');
    });

    test('remindEvery: typing 0 results in input showing 0 (not snapping to default 6)', async ({ page }) => {
        test.setTimeout(90000);

        await page.goto('/#!/view/olio.llm.chatConfig/' + configObjectId);
        await page.waitForTimeout(2500);

        // Capture ALL writes (POST/PUT/PATCH) hitting /rest/model so we can
        // prove whether the UI actually sent the change.
        let writes = [];
        page.on('request', async (req) => {
            let m = req.method();
            if ((m === 'PATCH' || m === 'POST' || m === 'PUT') && req.url().includes('/rest/model')) {
                writes.push({ method: m, url: req.url(), body: req.postData() });
            }
        });
        page.on('console', (msg) => {
            if (msg.type() === 'warning' || msg.type() === 'error') {
                console.log('[browser', msg.type() + ']', msg.text());
            }
        });

        let result = await setFieldViaUi(page, 'remindEvery', 0);
        expect(result.error).toBeNull();
        expect(result.typed).toBe('0');

        await screenshot(page, 'chat-config-remindEvery-0-typed');

        // Confirm the UI actually sent the change.
        console.log('[writes captured]', JSON.stringify(writes, null, 2));
        let sentRemindEvery0 = writes.some(w => {
            try {
                let parsed = JSON.parse(w.body);
                return parsed.remindEvery === 0;
            } catch (e) { return false; }
        });
        expect(sentRemindEvery0).toBe(true);

        await page.reload();
        await page.waitForTimeout(2500);
        let displayed = await readFieldFromUi(page, 'remindEvery');
        expect(displayed).toBe('0');
    });
});
