/**
 * App Panel (#!/app) — the per-user panel that absorbed the flyout's Explorer / Passkeys /
 * Access Requests / Breadcrumb Bar entries and the chat toolbar's LLM Debug button
 * (src/views/appPanel.js, src/components/asideMenu.js, src/chat/LLMDebugPanel.js, src/features/chat.js).
 *
 * Proves, in a real browser as the shared (non-admin) user against the live stack:
 *   - the flyout offers "App Panel" and no longer lists Explorer / Passkeys / Access Requests /
 *     Breadcrumb Bar; clicking it lands on #!/app and closes the drawer;
 *   - the panel shows Explorer, Passkeys and Access Requests cards, and each card routes to its tool;
 *   - the LLM Debug card really calls GET /rest/chat/llm/active (200) as a plain user and renders
 *     either the idle text or the request table;
 *   - the Breadcrumb Bar switch hides and re-shows nav.breadcrumb-bar;
 *   - the chat toolbar still has Fullscreen but no LLM Debug button.
 * Passkeys / Access Requests are feature-gated; the suite asserts the org config enables them rather
 * than mutating the organization's feature set.
 *
 * Run (Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/appPanel.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { apiLogin, ensureSharedTestUser, getOrgFeatures } from './helpers/api.js';

let shared = null;
let orgFeatures = [];

async function restLogin(ctx) {
    const resp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(resp.ok() || resp.status() === 204, 'shared user login failed: ' + resp.status()).toBe(true);
}

async function loginAsSharedUser(page) {
    await restLogin(page.request);
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {} close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function openDrawer(page) {
    const menuToggle = page.locator('div.menuToggle button').first();
    await expect(menuToggle, 'drawer toggle not visible').toBeVisible({ timeout: 10000 });
    await menuToggle.click();
    const aside = page.locator('aside.transition');
    await expect(aside).toHaveClass(/transition-full/, { timeout: 5000 });
    return aside;
}

function asideButton(page, aside, label) {
    return aside.locator('button', { has: page.locator('span:not([class])', { hasText: new RegExp('^' + label + '$') }) });
}

async function gotoAppPanel(page) {
    await page.evaluate(() => { window.location.hash = '#!/app'; });
    await expect(page.locator('[data-tool="/explorer"]'), 'App Panel did not render').toBeVisible({ timeout: 15000 });
}

test.describe('App Panel — flyout entry, tool cards, LLM Debug, breadcrumb switch', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        const feats = await getOrgFeatures(request, { userName: shared.testUserName, password: shared.testPassword });
        expect(feats.status, 'GET /rest/config/features failed').toBe(200);
        orgFeatures = feats.features || [];
        expect(orgFeatures, 'Passkeys card is feature-gated on webauthn; the org config must enable it for this test to mean anything').toContain('webauthn');
        expect(orgFeatures, 'Access Requests card is feature-gated on accessRequests; the org config must enable it').toContain('accessRequests');
    });

    test('flyout offers App Panel (not Explorer/Passkeys/Access Requests/Breadcrumb Bar) and routes to #!/app', async ({ page }) => {
        const pageErrors = [];
        page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));
        await loginAsSharedUser(page);

        const aside = await openDrawer(page);
        await expect(asideButton(page, aside, 'App Panel'), 'no "App Panel" button in the aside').toHaveCount(1);
        await expect(asideButton(page, aside, 'Explorer'), 'Explorer must have moved off the flyout').toHaveCount(0);
        await expect(asideButton(page, aside, 'Passkeys'), 'Passkeys must have moved off the flyout').toHaveCount(0);
        await expect(asideButton(page, aside, 'Access Requests'), 'Access Requests must have moved off the flyout').toHaveCount(0);
        await expect(asideButton(page, aside, 'Breadcrumb Bar'), 'Breadcrumb Bar toggle must have moved off the flyout').toHaveCount(0);
        await expect(aside.locator('h4', { hasText: /^App$/ })).toHaveCount(1);
        await expect(aside.locator('h4', { hasText: /^(Display|Browse)$/ })).toHaveCount(0);

        await asideButton(page, aside, 'App Panel').click();
        await page.waitForFunction(() => window.location.hash === '#!/app', { timeout: 15000 });
        await expect(aside, 'drawer should close after navigating').toHaveClass(/transition-0/, { timeout: 5000 });

        const main = page.locator('[role="main"]');
        await expect(main.locator('h2', { hasText: 'App Panel' })).toBeVisible({ timeout: 15000 });
        await expect(main.locator('[data-tool="/explorer"]')).toBeVisible();
        await expect(main.locator('[data-tool="/webauthn"]')).toContainText('Passkeys');
        await expect(main.locator('[data-tool="/webauthn"]')).toContainText('WebAuthn/FIDO2 passwordless authentication');
        await expect(main.locator('[data-tool="/accessRequests"]')).toContainText('Access Requests');
        await expect(main.locator('[data-tool="/accessRequests"]')).toContainText('Self-service access request and approval workflow');
        await expect(main.locator('[data-toggle="breadcrumb"]')).toBeVisible();
        await expect(main.locator('[data-llm-debug]')).toBeVisible();
        expect(pageErrors, 'uncaught page errors: ' + pageErrors.join(' | ')).toEqual([]);
    });

    test('tool cards route to Explorer, Passkeys and Access Requests', async ({ page }) => {
        await loginAsSharedUser(page);

        await gotoAppPanel(page);
        await page.locator('[data-tool="/explorer"]').click();
        await page.waitForFunction(() => window.location.hash === '#!/explorer', { timeout: 15000 });
        await expect(page.locator('[role="main"]'), 'Explorer view did not render').toBeVisible({ timeout: 15000 });

        await gotoAppPanel(page);
        await page.locator('[data-tool="/webauthn"]').click();
        await page.waitForFunction(() => window.location.hash === '#!/webauthn', { timeout: 15000 });
        await expect(page.locator('[role="main"]')).toContainText(/Passkey/i, { timeout: 20000 });

        await gotoAppPanel(page);
        await page.locator('[data-tool="/accessRequests"]').click();
        await page.waitForFunction(() => window.location.hash === '#!/accessRequests', { timeout: 15000 });
        await expect(page.locator('[role="main"]')).toContainText(/Access Request/i, { timeout: 20000 });
    });

    test('LLM Debug card polls GET /rest/chat/llm/active as a plain user and renders the status', async ({ page }) => {
        await loginAsSharedUser(page);

        const statusResp = page.waitForResponse(
            (r) => /\/rest\/chat\/llm\/active$/.test(r.url()) && r.request().method() === 'GET',
            { timeout: 20000 }
        );
        await gotoAppPanel(page);
        const resp = await statusResp;
        expect(resp.status(), 'GET /rest/chat/llm/active must succeed for the shared (non-admin) user').toBe(200);
        const body = await resp.json();
        expect(body).toHaveProperty('llmRequests');
        expect(body).toHaveProperty('activeLLMCalls');
        expect(body).toHaveProperty('summarizations');

        const dbg = page.locator('[data-llm-debug]');
        await expect(dbg.locator('button[title="Abort all"]')).toBeVisible();
        const idle = !body.llmRequests.length && !body.activeLLMCalls.length && !body.summarizations.length && !body.bufferModeStreams;
        if (idle) {
            await expect(dbg).toContainText('No active requests', { timeout: 10000 });
        } else {
            await expect(dbg).toContainText(/LLM Requests \(\d+\)|Active Calls \(\d+\)|Summarizations \(\d+\)|Buffer-mode streams: \d+/, { timeout: 10000 });
        }
        await expect(dbg).not.toContainText('LLM status unavailable');

        // Poller keeps going while the panel is open (2s cadence).
        await page.waitForResponse(
            (r) => /\/rest\/chat\/llm\/active$/.test(r.url()) && r.request().method() === 'GET',
            { timeout: 10000 }
        );
    });

    test('Breadcrumb Bar switch hides and re-shows nav.breadcrumb-bar', async ({ page }) => {
        await loginAsSharedUser(page);
        await gotoAppPanel(page);

        const bar = page.locator('nav.breadcrumb-bar');
        const toggle = page.locator('[data-toggle="breadcrumb"]');
        await expect(bar, 'breadcrumb bar should be visible by default on #!/app').toBeVisible({ timeout: 10000 });
        await expect(toggle).toHaveClass(/active/);

        await toggle.click();
        await expect(bar).toHaveCount(0, { timeout: 5000 });
        await expect(toggle).not.toHaveClass(/active/);

        await toggle.click();
        await expect(bar).toBeVisible({ timeout: 5000 });
        await expect(toggle).toHaveClass(/active/);
    });

    test('chat toolbar keeps Fullscreen but has no LLM Debug button', async ({ page }) => {
        test.skip(!orgFeatures.includes('chat'), 'chat feature is not enabled for this org');
        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '#!/chat'; });
        const fullscreen = page.locator('button[title="Fullscreen"], button[title="Exit fullscreen"]');
        await expect(fullscreen.first(), 'chat toolbar did not render').toBeVisible({ timeout: 30000 });
        await expect(page.locator('button[title="LLM Debug"]')).toHaveCount(0);
        await expect(page.locator('[data-llm-debug]')).toHaveCount(0);
    });
});
