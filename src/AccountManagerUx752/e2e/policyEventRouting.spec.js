/**
 * Live-DOM regression proof for the WebSocket chirp-routing fix.
 *
 * USER SYMPTOM (reported): "an error mid chat about multiple policy violations being detected,
 * which I think is related to memory extraction" — with NO server-side policy attached and NO
 * server logs. Root cause: pageClient.routeMessage collapsed policyEvent / evalProgress /
 * autotuneEvent / interactionEvent into a single handlePolicyEvent call that dropped chirps[2],
 * so every memory-extraction progress chirp (evalProgress: memoryExtract/memoryExtractDone) was
 * fed to the policy handler and surfaced as a spurious "Policy violation detected" toast.
 *
 * This spec drives the REAL app against the live Docker stack: it stubs window.WebSocket (Docker's
 * nginx strips the session cookie on the WS upgrade, so a real socket would close and force a
 * re-login), captures the socket instance the app creates, and injects chirp arrays through the
 * SAME onmessage handler the app assigned. So an injected chirp flows through the real
 * routeMessage -> real LLMConnector handler -> real page.toast / real DOM, exactly as a
 * server-pushed chirp would.
 *
 * The strict routing CONTRACT (policyEvent -> handlePolicyEvent with chirps[2] preserved;
 * evalProgress -> handleEvalProgress; neither cross-fires) is proven separately, and against the
 * same real code, by src/test/policyEventRouting.test.js (Vitest). This spec proves the
 * user-visible DOM outcome on the running deployment.
 *
 * Run against the Docker stack (127.0.0.1, not localhost — IPv6 ::1 is unmapped):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/policyEventRouting.spec.js \
 *     --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';

// Install BEFORE any app script runs: WebSocket stub (captures instances + never closes), a
// MutationObserver that records every toast message text ever rendered (toasts auto-dismiss, so we
// cannot rely on point-in-time DOM queries), and an injector that pushes a chirp array through the
// app's own ws.onmessage.
async function installChirpHarness(page) {
    await page.addInitScript(() => {
        window.__wsInstances = [];
        window.__toastTexts = [];

        const recordToastNode = (n) => {
            if (!n || n.nodeType !== 1) return;
            if (n.matches && n.matches('.toast-text')) window.__toastTexts.push(n.textContent || '');
            if (n.querySelectorAll) n.querySelectorAll('.toast-text')
                .forEach(e => window.__toastTexts.push(e.textContent || ''));
        };
        const startObserver = () => {
            const obs = new MutationObserver(muts => {
                for (const mu of muts) for (const n of mu.addedNodes) recordToastNode(n);
            });
            obs.observe(document.documentElement, { childList: true, subtree: true });
        };
        if (document.body) startObserver();
        else document.addEventListener('DOMContentLoaded', startObserver);

        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0;
                this.onopen = null; this.onclose = null;
                this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                window.__wsInstances.push(this);
                setTimeout(() => {
                    this.readyState = 1;
                    if (this.onopen) this.onopen({ type: 'open', target: this });
                }, 50);
            }
            send() {}
            close() { this.readyState = 3; }   // never fire onclose -> no forceLogin redirect
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0;
        window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2;
        window.WebSocket.CLOSED = 3;

        // Push a chirp array through the real onmessage the app installed on its socket.
        window.__injectChirp = (chirps) => {
            const ws = window.__wsInstances[window.__wsInstances.length - 1];
            if (!ws || typeof ws.onmessage !== 'function') {
                throw new Error('app has not attached ws.onmessage yet');
            }
            ws.onmessage({ data: JSON.stringify({ chirps }) });
        };
    });
}

async function loginAndLand(page) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed: HTTP ' + resp.status());
    }
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
    // The app opens its WebSocket during session init; wait until it has attached onmessage so
    // __injectChirp routes through the real handler rather than throwing.
    await page.waitForFunction(
        () => window.__wsInstances && window.__wsInstances.some(w => typeof w.onmessage === 'function'),
        { timeout: 15000 }
    );
}

test.describe('WebSocket chirp routing — memory-extraction is not a policy violation', () => {
    test.describe.configure({ timeout: 90000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await installChirpHarness(page);
        await loginAndLand(page);
    });

    test('memoryExtractDone shows an info toast with the result, NOT a "Policy violation detected" toast', async ({ page }) => {
        await page.evaluate(() => window.__injectChirp(['evalProgress', 'memoryExtractDone', '2 memories extracted']));

        // The correct info toast is rendered...
        await expect.poll(
            () => page.evaluate(() => window.__toastTexts.slice()),
            { timeout: 5000, message: 'expected the memory-extraction result toast to render' }
        ).toContain('2 memories extracted');

        // ...and the spurious policy-violation toast is NEVER rendered.
        const texts = await page.evaluate(() => window.__toastTexts.slice());
        expect(texts).not.toContain('Policy violation detected');
        expect(texts.some(t => /policy violation/i.test(t))).toBe(false);
    });

    test('in-progress memory phases (keyframe, memoryExtract) render NO toast at all', async ({ page }) => {
        await page.evaluate(() => {
            window.__injectChirp(['evalProgress', 'keyframe', '']);
            window.__injectChirp(['evalProgress', 'memoryExtract', '']);
        });
        // Give any (incorrect) toast a chance to render, then assert none did.
        await page.waitForTimeout(1000);
        const texts = await page.evaluate(() => window.__toastTexts.slice());
        expect(texts, 'in-progress phases must not toast (they drive the bg activity indicator)').toEqual([]);
    });

    test('a genuine policyEvent is handled by the policy branch — not the progress branch — and does not crash the app', async ({ page }) => {
        const details = JSON.stringify({ requestId: 'r1', details: 'character identity mismatch' });
        await page.evaluate((d) => window.__injectChirp(['policyEvent', 'violation', d]), details);
        await page.waitForTimeout(1000);

        const texts = await page.evaluate(() => window.__toastTexts.slice());
        // No policy handler is registered on the /main dashboard, so handlePolicyEvent iterates an
        // empty handler list -> no toast. The point here is negative: routing a policyEvent must NOT
        // fall through to the evalProgress/memory-extraction path (which would emit "Evaluating…" /
        // "memories" toasts). And the app must stay alive and on /main.
        expect(texts.some(t => /memories|evaluating/i.test(t))).toBe(false);
        expect(await page.evaluate(() => window.location.hash.includes('/main'))).toBe(true);
        await expect(page.locator('[role="main"]')).toBeVisible();
    });
});
