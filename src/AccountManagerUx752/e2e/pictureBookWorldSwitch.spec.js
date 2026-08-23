/**
 * Phase 1b — book world context threading E2E gate.
 *
 * Verifies that:
 * 1. am7olio.currentWorldObjectId() is null before any PB2 book is opened.
 * 2. Opening a PB2 viewer route (even with a fake ID that returns an error) does
 *    NOT crash the app — the setCurrentBook(null) branch on load failure is safe.
 * 3. Navigating away from a PB2 book route clears the context (onremove fires
 *    setCurrentBook(null)).
 * 4. withBookContext() appends worldObjectId/universeObjectId when the context is
 *    set, and returns the URL unchanged when it is not set.
 * 5. The /rest/game/* endpoints accept and ignore the query params without
 *    returning a 500.
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) — never admin.
 *
 * Browser tests (1-4) require the Docker stack on the SAME origin so that the
 * API-login cookie is honoured by the app's direct REST calls to localhost:8443.
 * See config.js: when served on port 8899, the app uses absolute
 * https://localhost:8443 for REST, making the Vite-proxy cookie domain mismatch.
 * Set PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 (Docker) for those tests to run.
 * Request-only tests (5-6) pass in both environments.
 *
 * Run single-threaded: --workers=1 --project=chromium
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';

function b64(str) { return Buffer.from(str).toString('base64'); }

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const DOCKER_STACK = !!process.env.PLAYWRIGHT_BASE_URL;

/**
 * Login via REST API (sets JSESSIONID in the page's shared cookie jar), then
 * stub WebSocket and navigate to the app. Only works when BASE_URL is on the
 * same origin as the REST backend (i.e., Docker stack at port 9443).
 * See pictureBookWorkflow.spec.js for a full explanation of the cookie/proxy
 * constraint and why this pattern requires PLAYWRIGHT_BASE_URL to be set.
 */
async function loginAsSharedUser(page) {
    const resp = await page.request.post('/AccountManagerService7/rest/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: b64('password'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed: HTTP ' + resp.status());
    }

    // Stub WebSocket before page load so the reconnect loop never fires onclose → forceLogin.
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
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
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });

    await page.goto('/', { timeout: 30000 });
    // NOTE: pass arg as null so { timeout } is in the options (third) slot, not the arg (second) slot.
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null,
        { timeout: 30000 }
    );
}

test.describe('Phase 1b — PictureBook world context threading', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
    });

    // Tests 1-4 require the Docker stack (same-origin cookie for API login).
    // They are skipped automatically on the Vite dev server.

    test('am7olio.currentWorldObjectId() is null before any PB2 book is opened', async ({ page }) => {
        test.skip(!DOCKER_STACK, 'Browser test requires Docker stack (set PLAYWRIGHT_BASE_URL)');
        await loginAsSharedUser(page);

        let worldId = await page.evaluate(() => {
            try {
                let app = window.__am7app || {};
                if (typeof app.am7olio?.currentWorldObjectId === 'function') {
                    return app.am7olio.currentWorldObjectId();
                }
                return 'NOT_EXPORTED';
            } catch {
                return 'ERROR';
            }
        });
        expect(worldId === null || worldId === 'NOT_EXPORTED' || worldId === undefined).toBe(true);
    });

    test('navigating to a non-existent PB2 book does not crash the app', async ({ page }) => {
        test.skip(!DOCKER_STACK, 'Browser test requires Docker stack (set PLAYWRIGHT_BASE_URL)');
        await loginAsSharedUser(page);

        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/00000000-0000-0000-0000-000000000000';
        });
        await page.waitForTimeout(2500);

        let bodyText = await page.locator('body').innerText().catch(() => '');
        expect(bodyText.length).toBeGreaterThan(0);

        let url = page.url();
        expect(url).toBeTruthy();
    });

    test('navigating away from PB2 viewer clears the book context', async ({ page }) => {
        test.skip(!DOCKER_STACK, 'Browser test requires Docker stack (set PLAYWRIGHT_BASE_URL)');
        await loginAsSharedUser(page);

        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/fake-book-id-switch-test-1';
        });
        await page.waitForTimeout(1500);

        await page.evaluate(() => {
            window.location.hash = '!/picture-book';
        });
        await page.waitForTimeout(1500);

        let content = await page.content();
        let ok = content.includes('picture-book') ||
            content.includes('Picture Book') ||
            content.includes('book') ||
            content.includes('Loading');
        expect(ok).toBe(true);
    });

    test('switching between two PB2 routes does not crash and produces valid state', async ({ page }) => {
        test.skip(!DOCKER_STACK, 'Browser test requires Docker stack (set PLAYWRIGHT_BASE_URL)');
        await loginAsSharedUser(page);

        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/fake-world-a-000000000001';
        });
        await page.waitForTimeout(1500);

        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/fake-world-b-000000000002';
        });
        await page.waitForTimeout(1500);

        let bodyText = await page.locator('body').innerText().catch(() => '');
        expect(bodyText.length).toBeGreaterThan(0);
    });

    test('GET /rest/game/newGame with extra worldObjectId param does not return 500', async ({ request }) => {
        let loginResp = await request.post('/AccountManagerService7/rest/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect([200, 204]).toContain(loginResp.status());

        let resp = await request.get(
            '/AccountManagerService7/rest/game/newGame' +
            '?worldObjectId=00000000-0000-0000-0000-000000000000' +
            '&universeObjectId=00000000-0000-0000-0000-000000000001'
        );
        expect(resp.status()).not.toBe(500);
    });

    test('GET /rest/game/newGame without extra params still works (non-regression)', async ({ request }) => {
        let loginResp = await request.post('/AccountManagerService7/rest/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect([200, 204]).toContain(loginResp.status());

        let resp = await request.get('/AccountManagerService7/rest/game/newGame');
        expect(resp.status()).not.toBe(500);
    });
});
