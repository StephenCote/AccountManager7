/**
 * Chat UX cleanup (features/chat.js, chat/LLMDebugPanel.js): in a real browser as the shared (non-admin)
 * user against the live stack —
 *   - the visible toolbar keeps Edit / Scenes and a "More chat actions" overflow; Export / Portraits /
 *     Fullscreen / LLM Debug / Clear history / Delete session are overflow items;
 *   - Clear history asks first, and declining sends no POST /rest/chat/clear;
 *   - the overflow's LLM Debug item opens the per-chat drawer, which reads GET /rest/chat/llm/active (200)
 *     and carries the relocated Show thoughts / Show MCP context toggles.
 * No LLM generation is involved: the session is a bare olio.llm.chatRequest created over REST.
 *
 * Run (Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chatToolbarOverflow.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { apiLogin, ensureSharedTestUser, getOrgFeatures } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(s) { return Buffer.from(s).toString('base64'); }
function uniq() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }

let shared = null;
let orgFeatures = [];

async function loginAsSharedUser(page) {
    const resp = await apiLogin(page.request, { user: shared.testUserName, password: shared.testPassword });
    expect(resp.ok() || resp.status() === 204, 'shared user login failed: ' + resp.status()).toBe(true);
    // Docker nginx does not forward the session cookie on the WS upgrade; stub it (troubleshooting.md).
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

async function createSession(page) {
    const dir = await page.request.get(REST + '/path/make/auth.group/data/B64-' +
        b64('/home/' + shared.testUserName + '/ChatRequests').replace(/=/g, '%3D'));
    expect(dir.ok(), 'ChatRequests dir: ' + dir.status()).toBe(true);
    const dirJson = await dir.json();
    const name = 'OverflowTest_' + uniq();
    const r = await page.request.post(REST + '/model', {
        data: { schema: 'olio.llm.chatRequest', name: name, groupId: dirJson.id, groupPath: dirJson.path }
    });
    expect(r.ok(), 'create chatRequest: ' + r.status()).toBe(true);
    const created = await r.json();
    return { name: name, objectId: created.objectId };
}

async function openOverflow(page) {
    const menu = page.locator('.context-menu');
    if (await menu.isVisible().catch(() => false)) { await page.keyboard.press('Escape'); await expect(menu).toHaveCount(0); }
    await page.locator('button[title="More chat actions"]').click();
    await expect(menu).toBeVisible({ timeout: 5000 });
    return menu;
}

test.describe('Chat toolbar overflow + per-chat LLM Debug drawer', () => {
    test.describe.configure({ timeout: 120000 });
    let session = null;

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        const feats = await getOrgFeatures(request, { userName: shared.testUserName, password: shared.testPassword });
        expect(feats.status, 'GET /rest/config/features failed').toBe(200);
        orgFeatures = feats.features || [];
    });

    test.afterEach(async ({ page }) => {
        if (session && session.objectId) {
            await page.request.delete(REST + '/model/olio.llm.chatRequest/' + session.objectId).catch(() => {});
        }
        session = null;
    });

    test('overflow holds the moved actions, Clear history confirm can be declined, LLM Debug opens the drawer', async ({ page }) => {
        test.skip(!orgFeatures.includes('chat'), 'chat feature is not enabled for this org');
        await loginAsSharedUser(page);
        session = await createSession(page);

        const clearPosts = [];
        const activeGets = [];
        page.on('request', (req) => { if (/\/rest\/chat\/clear$/.test(req.url())) clearPosts.push(req.method()); });
        page.on('response', (resp) => { if (/\/rest\/chat\/llm\/active$/.test(resp.url())) activeGets.push(resp.status()); });

        // The only stubbed call: report the chat library as initialized so the view loads the session list
        // instead of the setup wizard (the wizard would create shared library records, which this UI test
        // must not do). Every other call below is the live backend.
        await page.route('**/AccountManagerService7/rest/chat/library/status', (route) =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ initialized: true }) }));

        await page.evaluate(() => { window.location.hash = '#!/chat'; });
        await expect(page.locator('button[title="More chat actions"]'), 'chat toolbar did not render').toBeVisible({ timeout: 30000 });
        await expect(page.locator('text=Chat Library Setup')).toHaveCount(0);

        await page.locator('button[title="Sessions"]').click();
        const filter = page.locator('input[placeholder="Filter sessions..."]').first();
        if (await filter.isVisible({ timeout: 5000 }).catch(() => false)) await filter.fill(session.name);
        const title = page.locator('span.text-sm.truncate', { hasText: session.name }).first();
        await expect(title, 'new session in the sidebar').toBeVisible({ timeout: 15000 });
        await title.click();
        await expect(page.locator('[data-chat-toolbar="actions"] button[title="Edit messages"]'), 'session selected').toBeVisible({ timeout: 15000 });

        // Visible toolbar: kept vs moved.
        const bar = page.locator('[data-chat-toolbar="actions"]');
        await expect(bar.locator('button[title="Scene Gallery"]')).toBeVisible();
        for (const moved of ['Export chat', 'Show portraits', 'Fullscreen', 'Clear history', 'Delete session', 'Show thoughts', 'Hide thoughts']) {
            await expect(bar.locator('button[title="' + moved + '"]'), moved + ' should not be on the toolbar').toHaveCount(0);
        }

        // Overflow contents (a bare session has no messages or characters, so no Export / Portraits).
        let menu = await openOverflow(page);
        for (const t of ['Fullscreen', 'Clear history', 'Delete session']) {
            await expect(menu.locator('button[title="' + t + '"]'), t + ' in overflow').toBeVisible();
        }
        await expect(menu.locator('[data-testid="chat-llm-debug"]')).toBeVisible();

        // Clear history: decline.
        await menu.locator('button[title="Clear history"]').click();
        const dialog = page.locator('[role="dialog"]');
        await expect(dialog).toContainText('Clear all messages in', { timeout: 5000 });
        await dialog.locator('.am7-dialog-btn', { hasText: 'Cancel' }).click();
        await expect(dialog).toHaveCount(0, { timeout: 5000 });
        await page.waitForTimeout(500);
        expect(clearPosts, 'declined confirm must not POST /rest/chat/clear').toEqual([]);

        // LLM Debug drawer.
        menu = await openOverflow(page);
        await menu.locator('[data-testid="chat-llm-debug"]').click();
        const drawer = page.locator('[data-testid="chat-llm-debug-drawer"]');
        await expect(drawer).toBeVisible({ timeout: 5000 });
        await expect(drawer.locator('[data-session-field="name"]')).toContainText(session.name);
        await expect(drawer).toContainText(/No server activity for this chat|ID/, { timeout: 15000 });
        expect(activeGets.length, 'drawer polled /rest/chat/llm/active').toBeGreaterThan(0);
        expect(activeGets[0]).toBe(200);
        await expect(drawer.locator('button[title="Show thoughts"]')).toBeVisible();
        await expect(drawer.locator('button[title="Show MCP context in chat"]')).toBeVisible();
        await drawer.locator('button[title="Show thoughts"]').click();
        await expect(drawer.locator('button[title="Hide thoughts"]')).toBeVisible();
        await drawer.locator('button[title="Close LLM debug drawer"]').click();
        await expect(drawer).toHaveCount(0);

        // The App Panel now shows this chat as the last active one.
        await page.evaluate(() => { window.location.hash = '#!/app'; });
        const last = page.locator('[data-last-chat]');
        await expect(last, 'App Panel "Last active chat" section').toBeVisible({ timeout: 15000 });
        await expect(last.locator('[data-session-field="name"]')).toContainText(session.name);
    });
});
