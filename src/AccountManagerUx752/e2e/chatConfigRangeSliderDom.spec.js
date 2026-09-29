/**
 * Rendered-DOM regression test for the chat-config Options sliders (Max Tokens / Context Window).
 *
 * Bug (reported by Stephen, fixed in components/formFieldRenderers.js renderRangeSliderSpinner):
 * Mithril's render.js setAttrs() applies vnode attrs in object-insertion order, and a browser
 * <input type="range"> sanitizes `value` against its CURRENT min/max at the moment `value` is set.
 * The attrs used to be emitted `value, min, max, step`, so a persisted num_ctx of 16384 was clamped
 * to the range input's default max (100) before `max=120000` was ever applied — and applying `max`
 * afterwards does not restore the clamped value. The fix emits `min, max, step, value`.
 *
 * The only prior coverage was a Vitest that inspects vnode attr ORDER. This spec drives a real
 * Chromium against the live Docker stack and asserts the rendered DOM: the range input and its
 * companion number spinner must both carry the configured value, not 100.
 *
 * Run (Docker test stack, already up on 9443):
 *   cd src/AccountManagerUx752 && npx vite build && MSYS_NO_PATHCONV=1 docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chatConfigRangeSliderDom.spec.js --workers=1 --project=chromium
 *
 * Runs as the SHARED TEST USER (never admin). Self-cleaning: deletes the chatConfig it creates.
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, deleteObject } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = '/AccountManagerService7/rest';
const ORG = '/Development';

// Deliberately NOT the schema defaults (num_ctx default 8192, max_tokens default 4096): if the
// Options form silently fell back to olio.llm.chatOptions defaults instead of the persisted record,
// a test using the default values would pass for the wrong reason. Both exceed the browser's
// default range max of 100 (the pre-fix clamp target) and sit inside the schema maxValue 120000.
const NUM_CTX = 16384;
const MAX_TOKENS = 6144;
const SCHEMA_MAX = 120000;

// ── UI login as the shared user + WebSocket stub (pattern from chapBook.spec.js loginAsSharedUser) ──
async function loginAsSharedUser(page, creds) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: ORG,
            name: creds.testUserName,
            credential: Buffer.from(creds.testPassword).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed: HTTP ' + resp.status());
    }

    // Stub WebSocket — Docker's nginx strips cookies on the WS upgrade so Tomcat closes the
    // connection, which triggers forceLogin() and redirects to #!/sig ~1s after load.
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0;
                this.onopen = null; this.onclose = null;
                this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => {
                    this.readyState = 1;
                    if (this.onopen) this.onopen({ type: 'open', target: this });
                }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0;
        window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2;
        window.WebSocket.CLOSED = 3;
    });

    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

// ── Test data (created + deleted over REST as the shared user) ──────────────
let creds = null;
let orgId = null;
let cfgName = null;
let cfgObjectId = null;

async function sharedApiContext() {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    let resp = await apiLogin(ctx, { org: ORG, user: creds.testUserName, password: creds.testPassword });
    if (!resp.ok() && resp.status() !== 204) {
        await ctx.dispose();
        throw new Error('shared-user REST login failed: HTTP ' + resp.status());
    }
    return ctx;
}

async function searchChatConfigByName(ctx, name) {
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

test.describe('Chat config Options tab — range slider renders large persisted values (rendered DOM)', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        creds = await ensureSharedTestUser(request);
        expect(creds && creds.testUserName).toBe('e2etest_shared');

        cfgName = 'e2e-slider-dom-' + Date.now().toString(36);

        let ctx = await sharedApiContext();
        try {
            // ~/Chat is where the UI keeps chat configs; path/make also returns organizationId.
            let chatDir = await ensurePath(ctx, 'auth.group', 'data', '~/Chat');
            expect(chatDir && chatDir.id, 'ensurePath(~/Chat) must return the group').toBeTruthy();
            orgId = chatDir.organizationId;
            expect(typeof orgId).toBe('number');

            // chatOptions is an embedded (non-foreign) model on olio.llm.chatConfig, so it is
            // sent inline on create.
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
                        num_ctx: NUM_CTX,
                        max_tokens: MAX_TOKENS
                    }
                }
            });
            expect(createResp.ok(), 'POST /rest/model olio.llm.chatConfig must succeed (HTTP ' + createResp.status() + ')').toBe(true);

            // Create returns identity fields only; re-read with cache:false to (a) get objectId
            // reliably and (b) PROVE the backend persisted the large values, so a UI failure below
            // is a rendering failure and not a data-never-arrived failure.
            let cfg = await searchChatConfigByName(ctx, cfgName);
            expect(cfg && cfg.objectId, 'created chatConfig must be findable by name').toBeTruthy();
            cfgObjectId = cfg.objectId;
            expect(cfg.chatOptions, 'chatOptions must round-trip').toBeTruthy();
            expect(Number(cfg.chatOptions.num_ctx)).toBe(NUM_CTX);
            expect(Number(cfg.chatOptions.max_tokens)).toBe(MAX_TOKENS);

            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async () => {
        if (!creds || !cfgObjectId) return;
        let ctx = await sharedApiContext();
        try {
            await deleteObject(ctx, 'olio.llm.chatConfig', cfgObjectId);
            let gone = await searchChatConfigByName(ctx, cfgName);
            console.log('[chatConfigRangeSliderDom] cleanup ' + cfgName + ' -> ' + (gone ? 'STILL PRESENT' : 'deleted'));
            expect(gone, 'cleanup: chatConfig ' + cfgName + ' must be deleted').toBeNull();
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('num_ctx and max_tokens sliders + spinners show the persisted values, not the clamped 100', async ({ page }) => {
        await loginAsSharedUser(page, creds);

        await page.goto('/#!/view/olio.llm.chatConfig/' + cfgObjectId);

        // Options tab (forms.chatOptionsRef.label = "Options"; tab buttons carry a material icon span).
        let optionsTab = page.locator('button:has(span.material-icons-outlined):has-text("Options")');
        await expect(optionsTab.first()).toBeVisible({ timeout: 20000 });
        await optionsTab.first().click();

        const expected = { num_ctx: NUM_CTX, max_tokens: MAX_TOKENS };
        const observed = {};

        for (let field of Object.keys(expected)) {
            let want = String(expected[field]);
            let range = page.locator('input[type="range"][name="' + field + '"]');
            let spinner = page.locator('input[type="number"][name="' + field + '_num"]');

            await expect(range, field + ' range input must render').toBeVisible({ timeout: 15000 });
            await expect(spinner, field + '_num spinner must render').toBeVisible({ timeout: 15000 });

            // Rendered value on the slider itself. Pre-fix this read back "100": the browser
            // clamped `value` to the default max because `max` had not been applied yet.
            await expect(range, field + ' slider value').toHaveValue(want);
            await expect(spinner, field + ' spinner value').toHaveValue(want);

            // Bounds must actually admit the value (schema maxValue 120000 for both fields).
            let rangeMax = Number(await range.getAttribute('max'));
            expect(rangeMax, field + ' range max attr').toBeGreaterThanOrEqual(expected[field]);
            expect(rangeMax, field + ' range max attr equals schema maxValue').toBe(SCHEMA_MAX);
            let spinnerMax = Number(await spinner.getAttribute('max'));
            expect(spinnerMax, field + ' spinner max attr').toBeGreaterThanOrEqual(expected[field]);

            // Negative control, read straight from the live DOM element (not via Playwright's
            // inputValue helper): el.value and el.valueAsNumber are what the browser actually holds
            // after sanitization. Before the fix both were 100 for these fields — value was set while
            // max was still the default 100, and the later max=120000 did not un-clamp it. If a
            // future refactor reorders the attrs (or a wrapper re-sets value before max), this is
            // the assertion that goes red.
            let dom = await range.evaluate(el => ({
                value: el.value,
                valueAsNumber: el.valueAsNumber,
                min: el.min,
                max: el.max,
                step: el.step
            }));
            observed[field] = dom;
            expect(dom.value, field + ' el.value must not be the pre-fix clamp (100)').not.toBe('100');
            expect(dom.valueAsNumber, field + ' el.valueAsNumber').toBe(expected[field]);
            expect(dom.value, field + ' el.value').toBe(want);
            expect(Number(dom.max), field + ' el.max').toBeGreaterThanOrEqual(expected[field]);
        }

        // Browser-mechanism control (NOT an app assertion): prove this Chromium build really does
        // clamp a range input's value when value is applied before max — i.e. that the app checks
        // above are capable of going red. Replays the property-set order Mithril's setAttrs() uses
        // (input.value/min/max/step are DOM properties, so Mithril assigns them in attr-key order).
        // Pre-fix order (value, min, max, step) must yield "100"; fixed order must yield the value.
        let control = await page.evaluate((v) => {
            let preFix = document.createElement('input');
            preFix.type = 'range';
            preFix.value = String(v); preFix.min = '0'; preFix.max = '120000'; preFix.step = '1';
            let postFix = document.createElement('input');
            postFix.type = 'range';
            postFix.min = '0'; postFix.max = '120000'; postFix.step = '1'; postFix.value = String(v);
            return { preFix: preFix.value, postFix: postFix.value };
        }, NUM_CTX);
        expect(control.preFix, 'control: value-before-max must clamp to 100 in this browser').toBe('100');
        expect(control.postFix, 'control: max-before-value must keep the value').toBe(String(NUM_CTX));

        // Surface the measured DOM state in the report output.
        console.log('[chatConfigRangeSliderDom] configured=' + JSON.stringify(expected)
            + ' observed=' + JSON.stringify(observed) + ' control=' + JSON.stringify(control));

        await page.screenshot({ path: 'e2e/results/chat-config-range-slider-dom.png', fullPage: false });
    });
});
