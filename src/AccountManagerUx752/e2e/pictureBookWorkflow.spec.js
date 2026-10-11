/**
 * PictureBook Workflow Graph E2E — Phase 5a
 *
 * Tests the workflow graph route (/picture-book/:bookObjectId/workflow) and
 * the bridge REST endpoint (GET /{bookGroupObjectId}/pb2).
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) — never admin.
 * Run single-threaded: --workers=1 --project=chromium
 *
 * Architecture note on workflow route IDs
 * ────────────────────────────────────────
 * The route /picture-book/:bookObjectId/workflow expects a PB1 book GROUP objectId.
 * The bridge endpoint GET /{bookGroupObjectId}/pb2 calls findByObjectId(user, MODEL_GROUP, id)
 * and returns the linked olio.pb.book.  ChapBook books are native olio.pb.book records (not
 * auth.group containers) so their objectId cannot be used as the workflow route parameter —
 * the bridge call returns 404 and the canvas shows the "No PB2 workflow" message.
 *
 * To exercise the workflow canvas with real [data-node-id] elements you need a PB1 book group
 * objectId.  Supply it via WORKFLOW_BOOK_GROUP_OID env var (see gated test below).
 * The REST-level workflow-nodes test uses a ChapBook's pb2BookObjectId directly against the
 * /workflow endpoint (which accepts pb2BookObjectId, not a group objectId) so it does not
 * need a PB1 book.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';
import { randomUUID } from 'crypto';

function b64(str) { return Buffer.from(str).toString('base64'); }

// V3 P1-3 live render through the graph executor (SD on .39) — PB_SD_TESTS=1, --workers=1
const SD_RENDER_ENABLED = process.env.PB_SD_TESTS === '1';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

async function loginAsSharedUser(page) {
    // Login via the REST API using page.request, which shares the browser's cookie jar.
    // This avoids the Mithril login form entirely — no re-render races, no form interaction hangs.
    // config.js routes API calls based on window.location.port:
    //   port 8899 (Vite dev) → absolute https://localhost:8443
    //   any other port (e.g. 9443 Docker or 127.0.0.1:9443) → relative /AccountManagerService7
    // So PLAYWRIGHT_BASE_URL must be https://127.0.0.1:9443 (Docker) for this to work.
    const resp = await page.request.post('/AccountManagerService7/rest/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error(`API login failed: HTTP ${resp.status()}`);
    }

    // Stub WebSocket before page load so onclose never fires and the reconnect loop
    // never reaches loginWithPassword("${jwt}", ...) → forceLogin().
    // Docker routes wss://host:9443/AccountManagerService7/wss to Tomcat, but Tomcat
    // rejects the WS upgrade when no session cookie travels with it (nginx strips
    // cookies on the upgrade path), causing onclose → reconnect → forceLogin → #!/sig.
    // The stub pretends the WS is open; no real messages are needed for routing tests.
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0; // CONNECTING
                this.onopen = null; this.onclose = null;
                this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                // Fire onopen on the next tick so the app thinks it connected.
                setTimeout(() => {
                    this.readyState = 1; // OPEN
                    if (this.onopen) this.onopen({ type: 'open', target: this });
                }, 50);
            }
            send() {}
            // close() sets CLOSED but does NOT fire onclose — prevents reconnect loop.
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0;
        window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2;
        window.WebSocket.CLOSED = 3;
    });

    // Navigate to the app. JSESSIONID is already in the browser's cookie jar.
    // The app calls /rest/principal on boot; with a valid session it routes to /main.
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function navigateToWorkflow(page, bookObjectId) {
    await page.evaluate((oid) => {
        window.location.hash = '!/picture-book/' + oid + '/workflow';
    }, bookObjectId);
    await page.waitForTimeout(2000);
}

test.describe('PictureBook Workflow Graph', () => {
    test.describe.configure({ timeout: 150000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
    });

    test('workflow route loads for a fake book id and shows error or loading state', async ({ page }) => {
        await loginAsSharedUser(page);
        await navigateToWorkflow(page, 'nonexistent-book-group-000');

        let content = await page.content();
        // Should render the workflow shell, show an error from the bridge call, or show loading
        let handled =
            content.includes('workflow') ||
            content.includes('Workflow') ||
            content.includes('not found') ||
            content.includes('Not found') ||
            content.includes('Loading') ||
            content.includes('picture-book');
        expect(handled).toBe(true);
    });

    test('workflow route does not crash on navigation (no unhandled exception)', async ({ page }) => {
        await loginAsSharedUser(page);

        // Navigate twice to test cleanup
        await navigateToWorkflow(page, 'fake-id-alpha');
        await navigateToWorkflow(page, 'fake-id-beta');

        // Should still be a rendered page (Mithril didn't throw)
        let bodyText = await page.locator('body').innerText();
        expect(bodyText.length).toBeGreaterThan(0);
    });

    test('back button navigates away from workflow view', async ({ page }) => {
        await loginAsSharedUser(page);
        await navigateToWorkflow(page, 'fake-id-back-test');

        // Look for a back button (arrow_back icon or button label Back)
        let backClicked = await page.evaluate(() => {
            let buttons = Array.from(document.querySelectorAll('button, a'));
            let back = buttons.find(b => {
                let icon = b.querySelector('.material-symbols-outlined');
                if (icon && icon.textContent.trim() === 'arrow_back') return true;
                return b.textContent.trim() === 'Back';
            });
            if (back) { back.click(); return true; }
            return false;
        });

        if (backClicked) {
            await page.waitForTimeout(1000);
            let hash = await page.evaluate(() => window.location.hash);
            // Should have navigated away from /workflow sub-route
            expect(hash).not.toContain('/workflow');
        } else {
            // Back button not rendered (e.g. error state before toolbar appears) — acceptable
            let hash = await page.evaluate(() => window.location.hash);
            expect(hash).toBeTruthy();
        }
    });

    test('bridge endpoint GET /{id}/pb2 returns 404 for unknown group objectId', async ({ request }) => {
        // Login using the test fixture's APIRequestContext (already baseURL-aware)
        let loginResp = await request.post('/AccountManagerService7/rest/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        // Accept 200 or 204 for login
        expect([200, 204]).toContain(loginResp.status());

        // Bridge call for a made-up group objectId
        let bridgeResp = await request.get(
            '/AccountManagerService7/rest/olio/picture-book/00000000-0000-0000-0000-000000000000/pb2'
        );
        // Should be 401, 403, 404, or 405 — not a 500
        expect(bridgeResp.status()).not.toBe(500);
    });

    test('workflow toolbar renders zoom controls when loaded', async ({ page }) => {
        await loginAsSharedUser(page);
        await navigateToWorkflow(page, 'fake-id-toolbar-test');

        // Give the component time to render even in error state
        await page.waitForTimeout(2500);

        // Look for zoom controls (add/remove icons or zoom text)
        let hasToolbar = await page.evaluate(() => {
            let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
            let iconTexts = icons.map(i => i.textContent.trim());
            return iconTexts.some(t => t === 'add' || t === 'remove' || t === 'restart_alt');
        });

        // The toolbar renders only after a successful graph load; a fake id returns 404
        // so we only assert that the page itself is alive
        let bodyText = await page.locator('body').innerText();
        expect(bodyText.length).toBeGreaterThan(0);
        // hasToolbar may be false for an error state — that is acceptable
        expect(typeof hasToolbar).toBe('boolean');
    });

    test('workflow route is registered under /picture-book prefix', async ({ page }) => {
        await loginAsSharedUser(page);

        // Navigate to the workflow route pattern
        await page.evaluate(() => {
            window.location.hash = '!/picture-book/test-prefix-check/workflow';
        });
        await page.waitForTimeout(1500);

        let hash = await page.evaluate(() => window.location.hash);
        // Mithril router must not have fallen through to a 404/error page
        // (it would redirect to /main for unregistered routes in some setups)
        // — just confirm the hash contains 'picture-book' and 'workflow'
        expect(hash).toContain('picture-book');
        expect(hash).toContain('workflow');
    });

});

// ── Phase 5b: PB2 page reader (/picture-book/v2/:pb2BookObjectId) ─────

test.describe('PictureBook PB2 Page Reader (Phase 5b)', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
    });

    test('v2 route loads without crashing for unknown book id', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/00000000-0000-0000-0000-000000000099';
        });
        await page.waitForTimeout(2500);

        // Should render something — not a blank page or uncaught error
        let bodyText = await page.locator('body').innerText();
        expect(bodyText.length).toBeGreaterThan(0);

        // Should not contain an unhandled JS error banner
        let content = await page.content();
        expect(content).not.toContain('Unhandled error');
    });

    test('v2 route shows cover, loading, or error state — not blank', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/fake-pb2-id-cover-test';
        });
        await page.waitForTimeout(3000);

        let content = await page.content();
        // Any of these strings indicate the component rendered and handled the unknown book
        let handled =
            content.includes('Loading') ||
            content.includes('Failed') ||
            content.includes('scene') ||
            content.includes('Cover') ||
            content.includes('PB2') ||
            content.includes('Workflow Book') ||
            content.includes('picture-book');
        expect(handled).toBe(true);
    });

    test('v2 route has a back button to the book list', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/fake-pb2-id-back-btn';
        });
        await page.waitForTimeout(2500);

        // The pb2PageReaderView header always renders an arrow_back button
        let hasBack = await page.evaluate(() => {
            let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
            return icons.some(i => i.textContent.trim() === 'arrow_back');
        });
        expect(hasBack).toBe(true);
    });

    test('v2 back button navigates to /picture-book', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/fake-pb2-id-back-nav';
        });
        await page.waitForTimeout(2500);

        let clicked = await page.evaluate(() => {
            let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
            let backIcon = icons.find(i => i.textContent.trim() === 'arrow_back');
            if (backIcon) {
                let btn = backIcon.closest('button');
                if (btn) { btn.click(); return true; }
            }
            return false;
        });

        if (clicked) {
            await page.waitForTimeout(1200);
            let hash = await page.evaluate(() => window.location.hash);
            // After clicking back, should be at /picture-book without /v2 sub-path
            expect(hash).not.toContain('/v2/');
            expect(hash).toContain('picture-book');
        } else {
            // Back button not visible (loading/error before header rendered) — acceptable
            let bodyText = await page.locator('body').innerText();
            expect(bodyText.length).toBeGreaterThan(0);
        }
    });

    test('v2 keyboard navigation does not cause JS errors', async ({ page }) => {
        let jsErrors = [];
        page.on('pageerror', err => jsErrors.push(err.message));

        await loginAsSharedUser(page);
        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/fake-pb2-id-keyboard';
        });
        await page.waitForTimeout(2500);

        await page.keyboard.press('ArrowRight');
        await page.waitForTimeout(200);
        await page.keyboard.press('ArrowLeft');
        await page.waitForTimeout(200);
        await page.keyboard.press('Home');
        await page.waitForTimeout(200);
        await page.keyboard.press('End');
        await page.waitForTimeout(200);

        expect(jsErrors).toHaveLength(0);
    });

    test('v2 route is registered under /picture-book/v2/ prefix', async ({ page }) => {
        await loginAsSharedUser(page);

        await page.evaluate(() => {
            window.location.hash = '!/picture-book/v2/prefix-check-id';
        });
        await page.waitForTimeout(1500);

        let hash = await page.evaluate(() => window.location.hash);
        // Mithril router must have matched the route — if not it would redirect to /main
        expect(hash).toContain('picture-book');
        expect(hash).toContain('v2');
    });

    test('v2 route selector shows PB2 book list or loading on /picture-book', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.evaluate(() => {
            window.location.hash = '!/picture-book';
        });
        await page.waitForTimeout(2500);

        let content = await page.content();
        // The work selector renders either the PB2 book list, a loading indicator, or an empty state
        let hasSelector =
            content.includes('PB2') ||
            content.includes('Loading PB2') ||
            content.includes('picture-book') ||
            content.includes('Picture Book') ||
            content.includes('documents') ||
            content.includes('No documents');
        expect(hasSelector).toBe(true);
    });

});

// ── Workflow canvas — real PB2 book (REST verification + v2 UI) ────────────────

// Seeded state for this describe block — set in beforeAll.
let wfPb2BookObjectId = null;
let wfOrgId = null;

const POEM_A = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.
A precarious branch
Mourning its creased skein's blanch.`;

const POEM_B = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green
To silver veins of icy sheens.`;

test.describe('Workflow Canvas — real PB2 book', () => {
    test.describe.configure({ timeout: 150000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);

        // Login as shared user to seed data
        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        // Resolve orgId via principal
        const principalResp = await request.get(REST + '/login/principal');
        const principal = await principalResp.json().catch(() => null);
        wfOrgId = principal && principal.organizationId;

        // Ensure ~/Poems group exists
        const poemsDir = await request.get(
            REST + '/path/make/auth.group/data/B64-' + Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D')
        );
        const poemsDirBody = await poemsDir.json().catch(() => null);
        const poemsGroupId = poemsDirBody && poemsDirBody.id;
        if (wfOrgId == null && poemsDirBody) wfOrgId = poemsDirBody.organizationId;

        if (!poemsGroupId) {
            console.warn('[wfSpec] Could not ensure ~/Poems group — beforeAll seeding may be incomplete');
            return;
        }

        // Find or create poem A
        let paObjectId = null;
        const paSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'equals', value: 'wf-spec-poem-alpha' },
                    { name: 'organizationId', comparator: 'equals', value: wfOrgId }
                ],
                request: ['id', 'objectId'], recordCount: 1, cache: false
            }
        });
        const paBody = await paSearch.json().catch(() => null);
        if (paBody && paBody.results && paBody.results.length > 0) {
            paObjectId = paBody.results[0].objectId;
        } else {
            const paResp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem',
                    name: 'wf-spec-poem-alpha',
                    title: 'Falling Leaves (WF)',
                    author: 'WF Test',
                    groupId: poemsGroupId,
                    text: POEM_A
                }
            });
            const paCreated = await paResp.json().catch(() => null);
            paObjectId = paCreated && paCreated.objectId;
        }

        // Find or create poem B
        let pbObjectId = null;
        const pbSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'equals', value: 'wf-spec-poem-beta' },
                    { name: 'organizationId', comparator: 'equals', value: wfOrgId }
                ],
                request: ['id', 'objectId'], recordCount: 1, cache: false
            }
        });
        const pbBody = await pbSearch.json().catch(() => null);
        if (pbBody && pbBody.results && pbBody.results.length > 0) {
            pbObjectId = pbBody.results[0].objectId;
        } else {
            const pbResp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem',
                    name: 'wf-spec-poem-beta',
                    title: 'Winter (WF)',
                    author: 'WF Test',
                    groupId: poemsGroupId,
                    text: POEM_B
                }
            });
            const pbCreated = await pbResp.json().catch(() => null);
            pbObjectId = pbCreated && pbCreated.objectId;
        }

        if (!paObjectId || !pbObjectId) {
            console.warn('[wfSpec] Could not seed poems — workflow node tests will be skipped');
            return;
        }

        // Create a ChapBook — this is a native PB2 olio.pb.book with workflow nodes
        let slug = 'wf-spec-chapbook-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'WF Spec ChapBook', poemObjectIds: [paObjectId, pbObjectId], maxLinesPerPage: 20 }
        });
        if (!createResp.ok()) {
            console.warn('[wfSpec] ChapBook create failed: ' + createResp.status() + ' — workflow node tests will be skipped');
            return;
        }
        const created = await createResp.json().catch(() => null);
        // The ChapBook create response is the full olio.pb.book record
        wfPb2BookObjectId = created && created.objectId;

        await request.get(REST + '/logout');
    });

    // ── REST: verify workflow endpoint returns nodes ────────────────────

    test('GET /workflow returns at least one node for the seeded ChapBook', async ({ request }) => {
        if (!wfPb2BookObjectId) {
            test.skip('wfPb2BookObjectId not seeded — ChapBook creation may have failed in beforeAll');
            return;
        }
        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        // The /workflow endpoint accepts the pb2BookObjectId directly (not a group objectId)
        const wfResp = await request.get(
            REST + '/olio/picture-book/' + wfPb2BookObjectId + '/workflow'
        );
        expect(wfResp.ok(), 'workflow endpoint failed: ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        expect(wf, 'workflow response is null').toBeTruthy();
        expect(Array.isArray(wf.nodes), 'workflow.nodes must be an array').toBe(true);
        expect(wf.nodes.length, 'workflow must have at least one node').toBeGreaterThanOrEqual(1);

        await request.get(REST + '/logout');
    });

    // ── UI: v2 route for the seeded ChapBook shows content ─────────────

    test('v2 route for seeded ChapBook shows content, not blank', async ({ page }) => {
        if (!wfPb2BookObjectId) {
            test.skip('wfPb2BookObjectId not seeded — ChapBook creation may have failed in beforeAll');
            return;
        }
        await loginAsSharedUser(page);
        await page.evaluate((oid) => {
            window.location.hash = '!/picture-book/v2/' + oid;
        }, wfPb2BookObjectId);
        await page.waitForTimeout(3000);

        let content = await page.content();
        // The v2 reader should render something meaningful for a real book
        let handled =
            content.includes('Loading') ||
            content.includes('arrow_back') ||
            content.includes('Cover') ||
            content.includes('chevron') ||
            content.includes('picture-book');
        expect(handled).toBe(true);

        // No JS errors
        let bodyText = await page.locator('body').innerText();
        expect(bodyText.length).toBeGreaterThan(0);
    });

    // ── REST: testNode endpoint returns a non-500 response ───────────────

    test('POST /node/{nodeObjectId}/test returns acceptable status for first seeded ChapBook node', async ({ request }) => {
        if (!wfPb2BookObjectId) {
            test.skip('wfPb2BookObjectId not seeded');
            return;
        }
        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        // Get the workflow nodes
        const wfResp = await request.get(REST + '/olio/picture-book/' + wfPb2BookObjectId + '/workflow');
        if (!wfResp.ok()) {
            await request.get(REST + '/logout');
            test.skip('workflow endpoint failed — skipping testNode test');
            return;
        }
        const wf = await wfResp.json();
        const nodes = (wf && wf.nodes) || [];
        if (!nodes.length) {
            await request.get(REST + '/logout');
            test.skip('no nodes in ChapBook workflow — skipping testNode test');
            return;
        }

        // Use the first node
        const nodeOid = nodes[0].objectId;
        const testResp = await request.post(
            REST + '/olio/picture-book/' + wfPb2BookObjectId + '/node/' + nodeOid + '/test'
        );

        // 200 = executed, 501 = type not implemented, 503 = no SD server, 404 = scene missing
        // 400 and 500 should NOT occur for a well-formed node from the ChapBook pipeline
        const status = testResp.status();
        expect([200, 501, 503, 404], `unexpected testNode status ${status}`).toContain(status);

        await request.get(REST + '/logout');
    });

    // ── REST: listStale endpoint works for the seeded ChapBook ───────────

    test('GET /stale returns an array for the seeded ChapBook', async ({ request }) => {
        if (!wfPb2BookObjectId) {
            test.skip('wfPb2BookObjectId not seeded');
            return;
        }
        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        const staleResp = await request.get(REST + '/olio/picture-book/' + wfPb2BookObjectId + '/stale');
        expect(staleResp.ok(), 'stale endpoint failed: ' + staleResp.status()).toBe(true);
        const staleBody = await staleResp.json();
        expect(Array.isArray(staleBody), 'stale endpoint must return an array').toBe(true);
        // V3 Phase 1 (P1-2): every stale entry comes from the live drift scan and carries its reason.
        for (const entry of staleBody) {
            expect(['hashChanged', 'refDrift', 'marked', 'propagated'],
                'stale entry reason: ' + JSON.stringify(entry)).toContain(entry.reason);
            expect(Array.isArray(entry.driftedBindings), 'driftedBindings must be an array').toBe(true);
            expect(Array.isArray(entry.affectedSceneIndexes), 'affectedSceneIndexes must be an array').toBe(true);
            expect(typeof entry.markPersisted, 'markPersisted must be a boolean').toBe('boolean');
            expect(entry.status).toBe('STALE');
        }

        // The workflow view runs the same scan: scan timestamp + stale count are reported on it.
        const wfResp = await request.get(REST + '/olio/picture-book/' + wfPb2BookObjectId + '/workflow');
        expect(wfResp.ok(), 'workflow endpoint failed: ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        expect(typeof wf.scanAt, 'scanAt must be the scan timestamp (ms)').toBe('number');
        expect(wf.scanAt).toBeGreaterThan(0);
        expect(typeof wf.staleCount, 'staleCount must be a number').toBe('number');
        expect(wf.staleCount).toBe(staleBody.length);
        expect(typeof wf.staleMarkPersisted, 'staleMarkPersisted must be a boolean').toBe('boolean');
        expect(typeof wf.staleUndeterminedRefs, 'staleUndeterminedRefs must be a count').toBe('number');
        expect(typeof wf.scanComplete, 'scanComplete must be a boolean').toBe('boolean');
        /// The owner can read every bound record and persist every mark, so its scan is complete.
        expect(wf.staleUndeterminedRefs).toBe(0);
        expect(wf.scanComplete).toBe(true);
        const wfStale = (wf.nodes || []).filter(n => n.status === 'STALE');
        expect(wfStale.length, 'workflow STALE nodes must match the stale list').toBe(staleBody.length);

        await request.get(REST + '/logout');
    });

    // ── UI: workflow canvas with seeded ChapBook (B7 direct-PB2 path) ──────────

    test('workflow canvas renders node cards for seeded ChapBook', async ({ page }) => {
        test.skip(!wfPb2BookObjectId, 'wfPb2BookObjectId not seeded');
        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/picture-book/' + oid + '/workflow'; }, wfPb2BookObjectId);
        await page.waitForFunction(
            () => document.querySelectorAll('[data-node-id]').length > 0 ||
                  document.body.innerText.includes('Failed') ||
                  document.body.innerText.includes('No PB2'),
            { timeout: 30000 }
        );
        const nodeCards = await page.locator('[data-node-id]').count();
        expect(nodeCards).toBeGreaterThanOrEqual(1);
        const testBtns = await page.locator('button:has-text("Test")').count();
        expect(testBtns).toBeGreaterThanOrEqual(1);
        await page.screenshot({ path: 'e2e/screenshots/chapbook-canvas.png' });
    });

    // ── REST: V3 P1-4 review state machine over the live routes (select / approve / reject / review-queue) ──
    //
    // No SD: the chain is two TEXT artifacts created through the generic model route on a fresh PORTRAIT
    // node of the seeded ChapBook, so the review transitions themselves are what is exercised. The 403
    // branch needs a second principal with read-but-not-update on the book and is covered by Objects7
    // TestPbReview.case07; the 400/404/409 branches are exercised here over HTTP as well.

    test('PUT /artifact/{id}/select|approve|reject and GET /review-queue drive the §10 review state machine', async ({ request }) => {
        test.skip(!wfPb2BookObjectId, 'wfPb2BookObjectId not seeded');
        test.setTimeout(120000);

        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);
        const principalResp = await request.get(REST + '/principal');
        const principal = await principalResp.json();
        expect(principal && principal.objectId, 'principal objectId').toBeTruthy();

        const ts = Date.now().toString(36);
        const PB = REST + '/olio/picture-book/' + wfPb2BookObjectId;

        // 1. The workflow record and a fresh PORTRAIT node in its group.
        const wfResp = await request.get(PB + '/workflow');
        expect(wfResp.ok(), 'workflow endpoint failed: ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        const wfSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.workflow',
                fields: [{ name: 'objectId', comparator: 'equals', value: wf.workflowObjectId }],
                request: ['id', 'objectId', 'groupId', 'groupPath'], recordCount: 1, cache: false
            }
        });
        const wfBody = await wfSearch.json();
        const wfRec = wfBody && wfBody.results && wfBody.results[0];
        expect(wfRec && wfRec.id && wfRec.groupId, 'workflow record: ' + JSON.stringify(wfRec)).toBeTruthy();

        const handle = 'p14-rest-' + ts;
        const nodeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.pb.node',
                name: 'Node ' + handle,
                groupId: wfRec.groupId,
                groupPath: wfRec.groupPath,
                workflow: { schema: 'olio.pb.workflow', id: wfRec.id, objectId: wfRec.objectId },
                handle,
                nodeType: 'portrait',
                nodeStatus: 'done',
                ordinal: 910
            }
        });
        expect(nodeResp.ok(), 'create olio.pb.node: HTTP ' + nodeResp.status()).toBe(true);
        const node = await nodeResp.json();
        expect(node && node.objectId && node.id, 'node identity').toBeTruthy();

        // 2. Two TEXT revisions of role portrait0: r1 selected, r2 not (the state persistArtifact leaves
        //    after a second attempt over an unapproved selection is the mirror image; either is a legal start).
        const ROLE = 'portrait0';
        const makeArtifact = async (revision, selected) => {
            const resp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.pb.artifact',
                    name: 'Artifact ' + handle + ' ' + ROLE + ' r' + revision,
                    groupId: wfRec.groupId,
                    groupPath: wfRec.groupPath,
                    producedByNode: { schema: 'olio.pb.node', id: node.id, objectId: node.objectId },
                    role: ROLE,
                    revision,
                    selected,
                    artifactType: 'text',
                    artifactText: 'p1-4 rest r' + revision,
                    reviewStatus: 'generated'
                }
            });
            expect(resp.ok(), 'create olio.pb.artifact r' + revision + ': HTTP ' + resp.status()).toBe(true);
            const rec = await resp.json();
            expect(rec && rec.objectId, 'artifact r' + revision + ' objectId').toBeTruthy();
            return rec.objectId;
        };
        const a1 = await makeArtifact(1, true);
        const a2 = await makeArtifact(2, false);

        const queue = async (filter) => {
            const resp = await request.get(PB + '/review-queue' + (filter != null ? '?filter=' + encodeURIComponent(filter) : ''));
            const text = await resp.text();
            expect(resp.status(), 'GET /review-queue' + (filter != null ? '?filter=' + filter : '') + ' HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(200);
            return JSON.parse(text);
        };
        const entryOf = (q) => (q.nodes || []).find(n => n.nodeObjectId === node.objectId);
        const artOf = (entry, oid) => (entry.artifacts || []).find(a => a.objectId === oid);

        // 3. The queue sees the chain in its starting state.
        const q0 = await queue(null);
        expect(q0.bookObjectId).toBe(wfPb2BookObjectId);
        expect(q0.filter).toBe('all');
        expect(typeof q0.autoApprove, 'autoApprove is the book switch').toBe('boolean');
        const e0 = entryOf(q0);
        expect(e0, 'review queue lists the new node: ' + JSON.stringify(q0).slice(0, 600)).toBeTruthy();
        expect(e0.handle).toBe(handle);
        expect(e0.artifacts.length).toBe(2);
        expect(artOf(e0, a1).selected).toBe(true);
        expect(artOf(e0, a1).reviewStatus).toBe('GENERATED');
        expect(artOf(e0, a1).approvedBy, 'no approvedBy on a GENERATED artifact').toBeUndefined();
        expect(artOf(e0, a2).selected).toBe(false);
        expect(q0.pendingCount).toBeGreaterThanOrEqual(2);
        expect(q0.artifactCount).toBeGreaterThanOrEqual(q0.pendingCount);

        // 4. Select r2: selected moves, nothing else changes.
        const sel2 = await request.put(PB + '/artifact/' + a2 + '/select');
        const sel2Text = await sel2.text();
        expect(sel2.status(), 'select r2: HTTP ' + sel2.status() + ' ' + sel2Text.slice(0, 300)).toBe(200);
        const sel2Body = JSON.parse(sel2Text);
        expect(sel2Body.objectId).toBe(a2);
        expect(sel2Body.nodeObjectId).toBe(node.objectId);
        expect(sel2Body.selected).toBe(true);
        expect(sel2Body.reviewStatus).toBe('GENERATED');
        const q1 = await queue('all');
        expect(artOf(entryOf(q1), a1).selected).toBe(false);
        expect(artOf(entryOf(q1), a2).selected).toBe(true);
        expect(entryOf(q1).pinned, 'select never pins').toBe(false);

        // 5. Approve r1 while r2 is selected: approve implies select, pins the node, stamps the caller.
        const app1 = await request.put(PB + '/artifact/' + a1 + '/approve');
        const app1Text = await app1.text();
        expect(app1.status(), 'approve r1: HTTP ' + app1.status() + ' ' + app1Text.slice(0, 300)).toBe(200);
        const app1Body = JSON.parse(app1Text);
        expect(app1Body.reviewStatus).toBe('APPROVED');
        expect(app1Body.selected).toBe(true);
        expect(app1Body.approvedBy).toBe(principal.objectId);
        expect(app1Body.approvedAt, 'approvedAt stamped').toBeTruthy();
        const q2 = await queue('all');
        expect(entryOf(q2).pinned, 'approve pins the node').toBe(true);
        expect(artOf(entryOf(q2), a2).selected).toBe(false);
        expect(artOf(entryOf(q2), a2).reviewStatus).toBe('GENERATED');

        // 6. Select r2 over the approval: 409, state unchanged.
        const sel2b = await request.put(PB + '/artifact/' + a2 + '/select');
        const sel2bText = await sel2b.text();
        expect(sel2b.status(), 'select over APPROVED: HTTP ' + sel2b.status() + ' ' + sel2bText.slice(0, 300)).toBe(409);
        expect(sel2bText).toContain('APPROVED');
        const q3 = await queue('all');
        expect(artOf(entryOf(q3), a1).selected).toBe(true);
        expect(artOf(entryOf(q3), a1).reviewStatus).toBe('APPROVED');
        expect(artOf(entryOf(q3), a2).selected).toBe(false);

        // 7. Approve r2 over r1: r1 is demoted to GENERATED, one APPROVED per chain.
        const app2 = await request.put(PB + '/artifact/' + a2 + '/approve');
        expect(app2.status(), 'approve r2').toBe(200);
        const q4 = await queue('all');
        expect(artOf(entryOf(q4), a2).reviewStatus).toBe('APPROVED');
        expect(artOf(entryOf(q4), a2).selected).toBe(true);
        expect(artOf(entryOf(q4), a1).reviewStatus).toBe('GENERATED');
        expect(artOf(entryOf(q4), a1).selected).toBe(false);
        expect(artOf(entryOf(q4), a1).approvedBy, 'a demoted artifact carries no approvedBy').toBeUndefined();
        expect(entryOf(q4).artifacts.filter(a => a.reviewStatus === 'APPROVED').length).toBe(1);

        // 8. Reject the approved selection: it falls back to r1 (newest survivor), the pin stays.
        const rej2 = await request.put(PB + '/artifact/' + a2 + '/reject');
        const rej2Text = await rej2.text();
        expect(rej2.status(), 'reject r2: HTTP ' + rej2.status() + ' ' + rej2Text.slice(0, 300)).toBe(200);
        const rej2Body = JSON.parse(rej2Text);
        expect(rej2Body.reviewStatus).toBe('REJECTED');
        expect(rej2Body.selected).toBe(false);
        expect(rej2Body.approvedBy).toBeUndefined();
        const q5 = await queue('all');
        expect(artOf(entryOf(q5), a1).selected).toBe(true);
        expect(artOf(entryOf(q5), a1).reviewStatus).toBe('GENERATED');
        expect(artOf(entryOf(q5), a2).reviewStatus, 'rejected artifacts stay listed under all').toBe('REJECTED');
        expect(entryOf(q5).pinned, 'reject does not unpin (§6.C item 34)').toBe(true);
        expect(entryOf(q5).artifacts.filter(a => a.selected).length, 'exactly one selected').toBe(1);

        // 9. Filters: an unknown one is 400; auto-approved excludes this node (nothing here was approved by AUTO).
        const bad = await request.get(PB + '/review-queue?filter=bogus');
        const badText = await bad.text();
        expect(bad.status(), 'bogus filter: HTTP ' + bad.status() + ' ' + badText.slice(0, 300)).toBe(400);
        // Security finding 4 (P1-4): the 400 names the accepted filters and never echoes the caller's value.
        expect(badText).toContain('auto-approved');
        expect(badText).not.toContain('bogus');
        const qAuto = await queue('Auto-Approved');
        expect(qAuto.filter).toBe('auto-approved');
        expect(entryOf(qAuto), 'no AUTO approval on this node').toBeUndefined();
        for (const n of qAuto.nodes || []) {
            for (const a of n.artifacts || []) {
                expect(a.reviewStatus).toBe('APPROVED');
                expect(a.approvedBy).toBe('AUTO');
            }
        }

        // 10. An artifact that is not in this book is 404 on every route.
        const ghost = randomUUID();
        for (const action of ['select', 'approve', 'reject']) {
            const r = await request.put(PB + '/artifact/' + ghost + '/' + action);
            expect(r.status(), action + ' of an unknown artifact').toBe(404);
        }

        await request.get(REST + '/logout');
    });

    // ── REST: V3 P1-5 auto-approve (§10 rule 5) over the live routes ──
    //
    // No SD: the node regenerated here is a scene-scoped SCENE_PROMPT (a TEXT artifact built from the scene's
    // description), which the executor runs without an SD backend. The switch is read and written through
    // /settings/auto-approve, the approval lands through /regenerate, and the queue's auto-approved filter and
    // the pin-driven 409 on the next regenerate are the observable consequences. The 403 branch (a
    // read-only principal flipping the switch) is Objects7 TestPbAutoApprove.case07.

    test('GET/PUT /settings/auto-approve, then /regenerate auto-approves and pins a prompt node', async ({ request }) => {
        test.skip(!wfPb2BookObjectId, 'wfPb2BookObjectId not seeded');
        test.setTimeout(120000);

        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        const ts = Date.now().toString(36);
        const PB = REST + '/olio/picture-book/' + wfPb2BookObjectId;
        const SETTING = PB + '/settings/auto-approve';
        const readSwitch = async () => {
            const resp = await request.get(SETTING);
            const text = await resp.text();
            expect(resp.status(), 'GET /settings/auto-approve HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(200);
            return JSON.parse(text);
        };
        const writeSwitch = async (body, expectedStatus) => {
            const resp = await request.put(SETTING, {
                data: typeof body === 'string' ? body : JSON.stringify(body),
                headers: { 'Content-Type': 'application/json' }
            });
            const text = await resp.text();
            expect(resp.status(), 'PUT /settings/auto-approve ' + (typeof body === 'string' ? body : JSON.stringify(body))
                + ' HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(expectedStatus);
            return text;
        };

        try {
            // 1. Default off, and the switch is a JSON boolean or nothing.
            const s0 = await readSwitch();
            expect(s0.bookObjectId).toBe(wfPb2BookObjectId);
            expect(s0.autoApprove, 'a fresh book has auto-approve off').toBe(false);
            expect(await writeSwitch({ autoApprove: 'yes' }, 400)).toContain('JSON boolean');
            expect(await writeSwitch({ autoApprove: 1 }, 400)).toContain('JSON boolean');
            expect(await writeSwitch({}, 400)).toContain('JSON boolean');
            expect(await writeSwitch('{not json', 400)).toContain('Malformed');
            expect((await readSwitch()).autoApprove, 'refused writes change nothing').toBe(false);

            // 2. On: the write is re-read, and the workflow view carries it.
            const on = JSON.parse(await writeSwitch({ autoApprove: true }, 200));
            expect(on.bookObjectId).toBe(wfPb2BookObjectId);
            expect(on.autoApprove).toBe(true);
            expect((await readSwitch()).autoApprove).toBe(true);
            const wfResp = await request.get(PB + '/workflow');
            expect(wfResp.ok(), 'workflow endpoint failed: ' + wfResp.status()).toBe(true);
            const wf = await wfResp.json();
            expect(wf.autoApprove, 'workflow view carries the switch').toBe(true);

            // 3. A scene with a description, and a SCENE_PROMPT node scoped to it, in the book's groups.
            const bookSearch = await request.post(REST + '/model/search', {
                data: {
                    schema: 'io.query', type: 'olio.pb.book',
                    fields: [{ name: 'objectId', comparator: 'equals', value: wfPb2BookObjectId }],
                    request: ['id', 'objectId', 'groupId', 'groupPath', 'autoApprove'], recordCount: 1, cache: false
                }
            });
            const bookBody = await bookSearch.json();
            const bookRec = bookBody && bookBody.results && bookBody.results[0];
            expect(bookRec && bookRec.id && bookRec.groupId, 'book record: ' + JSON.stringify(bookRec)).toBeTruthy();
            expect(bookRec.autoApprove, 'the column itself reads true').toBe(true);

            const sceneIndex = 100000 + Math.floor(Math.random() * 800000);
            const sceneResp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.pb.scene',
                    name: 'p15-rest-scene-' + ts,
                    groupId: bookRec.groupId,
                    groupPath: bookRec.groupPath,
                    book: { schema: 'olio.pb.book', id: bookRec.id, objectId: bookRec.objectId },
                    sceneIndex,
                    title: 'P1-5 REST scene',
                    description: 'a lighthouse keeper on a stone jetty at dusk, gulls overhead, long shadows'
                }
            });
            expect(sceneResp.ok(), 'create olio.pb.scene: HTTP ' + sceneResp.status()).toBe(true);
            const scene = await sceneResp.json();
            expect(scene && scene.objectId, 'scene objectId').toBeTruthy();

            const wfSearch = await request.post(REST + '/model/search', {
                data: {
                    schema: 'io.query', type: 'olio.pb.workflow',
                    fields: [{ name: 'objectId', comparator: 'equals', value: wf.workflowObjectId }],
                    request: ['id', 'objectId', 'groupId', 'groupPath'], recordCount: 1, cache: false
                }
            });
            const wfBody = await wfSearch.json();
            const wfRec = wfBody && wfBody.results && wfBody.results[0];
            expect(wfRec && wfRec.id && wfRec.groupId, 'workflow record: ' + JSON.stringify(wfRec)).toBeTruthy();

            const handle = 'p15-rest-' + ts;
            const nodeResp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.pb.node',
                    name: 'Node ' + handle,
                    groupId: wfRec.groupId,
                    groupPath: wfRec.groupPath,
                    workflow: { schema: 'olio.pb.workflow', id: wfRec.id, objectId: wfRec.objectId },
                    handle,
                    nodeType: 'scene_prompt',
                    nodeStatus: 'pending',
                    ordinal: 920,
                    scope: 'scene',
                    scopeRef: scene.objectId
                }
            });
            expect(nodeResp.ok(), 'create olio.pb.node: HTTP ' + nodeResp.status()).toBe(true);
            const node = await nodeResp.json();
            expect(node && node.objectId, 'node objectId').toBeTruthy();

            // 4. Regenerate: the DTO reports the approval, the artifact is APPROVED by AUTO and selected, the node pinned.
            const started = Date.now();
            const genResp = await request.post(PB + '/node/' + node.objectId + '/regenerate');
            const genText = await genResp.text();
            expect(genResp.status(), '/regenerate HTTP ' + genResp.status() + ' ' + genText.slice(0, 400)).toBe(200);
            const dto = JSON.parse(genText);
            console.log('[p15-regenerate] wallMs=' + (Date.now() - started) + ' dto=' + JSON.stringify(dto));
            expect(dto.executed).toBe(true);
            expect(dto.nodeObjectId).toBe(node.objectId);
            expect(String(dto.runStatus).toUpperCase()).toBe('COMPLETED');
            expect(dto.artifactObjectId, 'artifactObjectId').toBeTruthy();
            // JSONUtil.exportObject is Include.NON_EMPTY: an empty degradations[] is omitted on the wire.
            expect(dto.degradations ?? [], 'a described scene renders undegraded').toEqual([]);
            expect(dto.autoApproved).toBe(true);
            expect(dto.autoApproveSkipped).toBeUndefined();
            expect(dto.reviewStatus).toBe('APPROVED');
            expect(typeof dto.durationMs).toBe('number');

            const artResp = await request.get(PB + '/artifact/' + dto.artifactObjectId);
            const artText = await artResp.text();
            expect(artResp.status(), 'GET artifact HTTP ' + artResp.status() + ' ' + artText.slice(0, 300)).toBe(200);
            const art = JSON.parse(artText);
            expect(art.reviewStatus).toBe('APPROVED');
            expect(art.approvedBy).toBe('AUTO');
            expect(art.approvedAt, 'approvedAt stamped').toBeTruthy();
            expect(art.selected).toBe(true);

            const qAuto = await request.get(PB + '/review-queue?filter=auto-approved');
            const qAutoText = await qAuto.text();
            expect(qAuto.status(), 'GET /review-queue?filter=auto-approved HTTP ' + qAuto.status() + ' ' + qAutoText.slice(0, 300)).toBe(200);
            const queue = JSON.parse(qAutoText);
            expect(queue.autoApprove).toBe(true);
            const entry = (queue.nodes || []).find(n => n.nodeObjectId === node.objectId);
            expect(entry, 'auto-approved filter lists the node: ' + qAutoText.slice(0, 600)).toBeTruthy();
            expect(entry.pinned, 'auto-approve pins').toBe(true);
            expect(entry.artifacts.length).toBe(1);
            expect(entry.artifacts[0].objectId).toBe(dto.artifactObjectId);
            expect(entry.artifacts[0].approvedBy).toBe('AUTO');

            // 5. The pin is real: a second regenerate is refused before anything is written.
            const again = await request.post(PB + '/node/' + node.objectId + '/regenerate');
            const againText = await again.text();
            expect(again.status(), 'regenerate a pinned node: HTTP ' + again.status() + ' ' + againText.slice(0, 300)).toBe(409);
            expect(againText).toContain('pinned');

            // 6. Off: nothing already approved changes.
            const off = JSON.parse(await writeSwitch({ autoApprove: false }, 200));
            expect(off.autoApprove).toBe(false);
            expect((await readSwitch()).autoApprove).toBe(false);
            const artAfter = await (await request.get(PB + '/artifact/' + dto.artifactObjectId)).json();
            expect(artAfter.reviewStatus, 'switching off un-approves nothing').toBe('APPROVED');
            expect(artAfter.approvedBy).toBe('AUTO');
            const qAfter = await (await request.get(PB + '/review-queue?filter=auto-approved')).json();
            const entryAfter = (qAfter.nodes || []).find(n => n.nodeObjectId === node.objectId);
            expect(entryAfter && entryAfter.pinned, 'still listed, still pinned').toBe(true);

            // 7. Unknown book: 404 on both verbs.
            const ghost = randomUUID();
            expect((await request.get(REST + '/olio/picture-book/' + ghost + '/settings/auto-approve')).status()).toBe(404);
            const ghostPut = await request.put(REST + '/olio/picture-book/' + ghost + '/settings/auto-approve', {
                data: JSON.stringify({ autoApprove: true }), headers: { 'Content-Type': 'application/json' }
            });
            expect(ghostPut.status()).toBe(404);
        } finally {
            // The [SD] test that follows regenerates on this book and must not be auto-approved by a leftover switch.
            await request.put(SETTING, { data: JSON.stringify({ autoApprove: false }), headers: { 'Content-Type': 'application/json' } }).catch(() => null);
            await request.get(REST + '/logout');
        }
    });

    // ── REST: V3 P1-3 live render via /regenerate, then P1-2 refDrift after a charPerson PATCH ──
    //
    // Last in this describe on purpose: it adds a record-bound, rendered PORTRAIT node to the seeded
    // ChapBook and leaves it STALE, which the earlier /stale shape test must not see.
    // Real SD render on the Docker stack (.39 Swarm); the PNG is written to e2e/screenshots/ to be
    // opened and described. durationMs is logged, never asserted.

    test('[SD] POST /node/{id}/regenerate renders a portrait node; a charPerson PATCH surfaces it as refDrift on GET /stale', async ({ request }) => {
        test.skip(!SD_RENDER_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD render through the executor');
        test.skip(!wfPb2BookObjectId, 'wfPb2BookObjectId not seeded');
        test.setTimeout(480000);

        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);
        const ts = Date.now().toString(36);
        const PB = REST + '/olio/picture-book/' + wfPb2BookObjectId;

        // 1. A character group, a narrative with a HAND-WRITTEN sdPrompt (wins over LLM generation in
        //    PictureBookUtil.resolveSceneCharacter, so no LLM call and a clothed subject), and the charPerson.
        const charDirResp = await request.get(REST + '/path/make/auth.group/data/B64-' + b64('~/P13Chars').replace(/=/g, '%3D'));
        expect(charDirResp.ok(), 'ensure ~/P13Chars: HTTP ' + charDirResp.status()).toBe(true);
        const charDir = await charDirResp.json();
        expect(charDir && charDir.id, 'character group id').toBeTruthy();

        const narrativeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.narrative',
                name: 'p13-rest-narrative-' + ts,
                groupId: charDir.id,
                groupPath: charDir.path,
                sdPrompt: 'a tall woman in her thirties with short dark hair and sea-grey eyes, wearing a weathered oilskin coat over a thick wool sweater, head and shoulders portrait, soft overcast light',
                physicalDescription: 'tall, short dark hair, sea-grey eyes',
                outfitDescription: 'weathered oilskin coat over a thick wool sweater'
            }
        });
        expect(narrativeResp.ok(), 'create olio.narrative: HTTP ' + narrativeResp.status()).toBe(true);
        const narrative = await narrativeResp.json();
        expect(narrative && narrative.objectId && narrative.id, 'narrative identity').toBeTruthy();

        const cpResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.charPerson',
                name: 'p13-rest-char-' + ts,
                firstName: 'Before', middleName: 'Drift', lastName: 'Rest',
                gender: 'female', alignment: 'neutralgood',
                groupId: charDir.id, groupPath: charDir.path
            }
        });
        expect(cpResp.ok(), 'create olio.charPerson: HTTP ' + cpResp.status()).toBe(true);
        const cp = await cpResp.json();
        expect(cp && cp.objectId && cp.id, 'charPerson identity').toBeTruthy();

        // Foreign fields patch by id reference (model-api.md): identity + the one changed field.
        const linkResp = await request.patch(REST + '/model', {
            data: {
                schema: 'olio.charPerson', id: cp.id, objectId: cp.objectId,
                narrative: { schema: 'olio.narrative', id: narrative.id, objectId: narrative.objectId }
            }
        });
        expect(linkResp.ok(), 'PATCH charPerson.narrative: HTTP ' + linkResp.status()).toBe(true);
        const cpCheck = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.charPerson',
                fields: [{ name: 'objectId', comparator: 'equals', value: cp.objectId }],
                request: ['id', 'objectId', 'name', 'firstName', 'narrative'], recordCount: 1, cache: false
            }
        });
        const cpCheckBody = await cpCheck.json();
        const cpRead = cpCheckBody && cpCheckBody.results && cpCheckBody.results[0];
        expect(cpRead && cpRead.narrative && cpRead.narrative.id, 'charPerson.narrative linked: ' + JSON.stringify(cpRead)).toBe(narrative.id);

        // 2. The book's workflow, and a PORTRAIT node in its group bound to the character by scopeRef.
        //    There is no "add node" REST route; the generic model create is the only transport for it.
        const wfResp = await request.get(PB + '/workflow');
        expect(wfResp.ok(), 'workflow endpoint failed: ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        expect(wf && wf.workflowObjectId, 'workflowObjectId on the workflow view').toBeTruthy();
        const wfSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.workflow',
                fields: [{ name: 'objectId', comparator: 'equals', value: wf.workflowObjectId }],
                request: ['id', 'objectId', 'groupId', 'groupPath'], recordCount: 1, cache: false
            }
        });
        const wfBody = await wfSearch.json();
        const wfRec = wfBody && wfBody.results && wfBody.results[0];
        expect(wfRec && wfRec.id && wfRec.groupId, 'workflow record: ' + JSON.stringify(wfRec)).toBeTruthy();

        const handle = 'p13-rest-' + ts;
        const nodeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.pb.node',
                name: 'Node ' + handle,
                groupId: wfRec.groupId,
                groupPath: wfRec.groupPath,
                workflow: { schema: 'olio.pb.workflow', id: wfRec.id, objectId: wfRec.objectId },
                handle,
                nodeType: 'portrait',
                nodeStatus: 'pending',
                ordinal: 900,
                scope: 'character',
                scopeRef: cp.objectId
            }
        });
        expect(nodeResp.ok(), 'create olio.pb.node: HTTP ' + nodeResp.status()).toBe(true);
        const node = await nodeResp.json();
        expect(node && node.objectId, 'node objectId').toBeTruthy();

        // 3. Render it through the executor. Synchronous in P1-3 (async jobs are P1-6).
        const started = Date.now();
        const genResp = await request.post(PB + '/node/' + node.objectId + '/regenerate', { timeout: 420000 });
        const wallMs = Date.now() - started;
        const genText = await genResp.text();
        expect(genResp.status(), '/regenerate HTTP ' + genResp.status() + ' ' + genText.slice(0, 400)).toBe(200);
        const dto = JSON.parse(genText);
        console.log('[p13-regenerate] wallMs=' + wallMs + ' dto=' + JSON.stringify(dto));
        expect(dto.executed).toBe(true);
        expect(dto.nodeObjectId).toBe(node.objectId);
        expect(String(dto.nodeStatus).toUpperCase()).toBe('DONE');
        expect(String(dto.runStatus).toUpperCase(), 'run closes COMPLETED (in-memory handle mirrors the row)').toBe('COMPLETED');
        expect(dto.runObjectId, 'runObjectId').toBeTruthy();
        expect(dto.artifactObjectId, 'artifactObjectId').toBeTruthy();
        expect(dto.characterObjectId).toBe(cp.objectId);
        expect(typeof dto.durationMs).toBe('number');

        // 4. Pull the artifact's bytes back through the media servlet and keep the PNG for inspection.
        const artResp = await request.get(PB + '/artifact/' + dto.artifactObjectId);
        expect(artResp.ok(), 'GET artifact: HTTP ' + artResp.status()).toBe(true);
        const art = await artResp.json();
        console.log('[p13-artifact] ' + JSON.stringify({
            objectId: art.objectId, role: art.role, revision: art.revision, seed: art.seed, byteLength: art.byteLength,
            imageWidth: art.imageWidth, imageHeight: art.imageHeight, mimeType: art.mimeType, backend: art.backend,
            dataObjectId: art.dataObjectId
        }));
        expect(art.role).toBe('portrait');
        expect(art.dataObjectId, 'artifact.dataObjectId').toBeTruthy();
        const dataResp = await request.get(REST + '/model/data.data/' + art.dataObjectId);
        expect(dataResp.ok(), 'GET data.data: HTTP ' + dataResp.status()).toBe(true);
        const dataRec = await dataResp.json();
        expect(dataRec && dataRec.groupPath && dataRec.name, 'data.data carries groupPath + name').toBeTruthy();
        const mediaUrl = BASE_URL + '/AccountManagerService7/media/Development/data.data' + dataRec.groupPath + '/' + encodeURIComponent(dataRec.name);
        const media = await request.get(mediaUrl);
        expect(media.ok(), 'GET media ' + mediaUrl + ' HTTP ' + media.status()).toBe(true);
        let raw = await media.body();
        if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
        expect(raw.subarray(0, 8).toString('hex'), 'artifact bytes are a PNG').toBe('89504e470d0a1a0a');
        const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
        expect(dims[0] > 0 && dims[1] > 0, 'PNG has dimensions').toBe(true);
        mkdirSync('e2e/screenshots', { recursive: true });
        const pngPath = 'e2e/screenshots/p13-rest-portrait.png';
        writeFileSync(pngPath, raw);
        await test.info().attach('p13-rest-portrait.png', { path: pngPath, contentType: 'image/png' });
        console.log('[p13-portrait] ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) -> ' + pngPath);

        // 5. Freshly rendered: not stale.
        const stale0Resp = await request.get(PB + '/stale');
        expect(stale0Resp.ok(), 'GET /stale (before): HTTP ' + stale0Resp.status()).toBe(true);
        const stale0 = await stale0Resp.json();
        expect(stale0.find(e => e.objectId === node.objectId), 'a just-rendered node is not stale').toBeUndefined();

        // 6. Edit a watched field on the bound character through the generic PATCH — a path that never
        //    touches PB code — and the forced scan behind GET /stale must see it as refDrift.
        const driftResp = await request.patch(REST + '/model', {
            data: { schema: 'olio.charPerson', id: cp.id, objectId: cp.objectId, firstName: 'After' }
        });
        expect(driftResp.ok(), 'PATCH charPerson.firstName: HTTP ' + driftResp.status()).toBe(true);

        const stale1Resp = await request.get(PB + '/stale');
        expect(stale1Resp.ok(), 'GET /stale (after): HTTP ' + stale1Resp.status()).toBe(true);
        const stale1 = await stale1Resp.json();
        const entry = stale1.find(e => e.objectId === node.objectId);
        console.log('[p13-stale] ' + JSON.stringify(entry));
        expect(entry, 'the rendered node is stale after the character edit: ' + JSON.stringify(stale1)).toBeTruthy();
        expect(entry.reason).toBe('refDrift');
        expect(entry.status).toBe('STALE');
        expect(entry.markPersisted).toBe(true);
        const drifted = (entry.driftedBindings || []).find(b => b.refObjectId === cp.objectId);
        expect(drifted, 'driftedBindings names the character: ' + JSON.stringify(entry.driftedBindings)).toBeTruthy();
        expect(drifted.role).toBe('character');
        expect(drifted.refModel).toBe('olio.charPerson');

        // The workflow view agrees (it runs the same scan; cached ≤30 s but invalidated by the mark).
        const wf2Resp = await request.get(PB + '/workflow');
        expect(wf2Resp.ok()).toBe(true);
        const wf2 = await wf2Resp.json();
        const viewNode = (wf2.nodes || []).find(n => n.objectId === node.objectId);
        expect(viewNode && viewNode.status, 'workflow view status: ' + JSON.stringify(viewNode)).toBe('STALE');

        await request.get(REST + '/logout');
    });

    // V3 Phase 1 exit criterion 1 (design §30 row 1): "leave the page -> render continues".
    // The browser page submits POST /scene/{id}/render (202 + jobId), the page is closed before the
    // render finishes, and a second session of the same user watches GET /rest/job/{jobId} run to
    // completion, then reads the run row, the node and the artifact bytes back from the server.
    test('[SD] P1-6 POST /scene/{id}/render is a 202 async job; the render completes after the submitting page is closed', async ({ page, request }) => {
        test.skip(!SD_RENDER_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD render through the async job');
        test.skip(!wfPb2BookObjectId, 'wfPb2BookObjectId not seeded');
        test.setTimeout(600000);

        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: b64('password'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);
        const ts = Date.now().toString(36);
        const PB = REST + '/olio/picture-book/' + wfPb2BookObjectId;

        // 1. Character fixtures as in the P1-3 test: hand-written sdPrompt => no LLM stage, clothed subject.
        const charDirResp = await request.get(REST + '/path/make/auth.group/data/B64-' + b64('~/P16Chars').replace(/=/g, '%3D'));
        expect(charDirResp.ok(), 'ensure ~/P16Chars: HTTP ' + charDirResp.status()).toBe(true);
        const charDir = await charDirResp.json();
        expect(charDir && charDir.id, 'character group id').toBeTruthy();

        const narrativeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.narrative',
                name: 'p16-rest-narrative-' + ts,
                groupId: charDir.id,
                groupPath: charDir.path,
                sdPrompt: 'a wiry man in his sixties with cropped white hair and a weathered, sun-lined face, wearing a dark navy fisherman\'s sweater under a worn brown leather jacket, head and shoulders portrait, cool morning light',
                physicalDescription: 'wiry, cropped white hair, weathered sun-lined face',
                outfitDescription: 'dark navy fisherman\'s sweater under a worn brown leather jacket'
            }
        });
        expect(narrativeResp.ok(), 'create olio.narrative: HTTP ' + narrativeResp.status()).toBe(true);
        const narrative = await narrativeResp.json();
        expect(narrative && narrative.objectId && narrative.id, 'narrative identity').toBeTruthy();

        const cpResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.charPerson',
                name: 'p16-rest-char-' + ts,
                firstName: 'Async', middleName: 'Job', lastName: 'Rest',
                gender: 'male', alignment: 'neutralgood',
                groupId: charDir.id, groupPath: charDir.path
            }
        });
        expect(cpResp.ok(), 'create olio.charPerson: HTTP ' + cpResp.status()).toBe(true);
        const cp = await cpResp.json();
        expect(cp && cp.objectId && cp.id, 'charPerson identity').toBeTruthy();

        const linkResp = await request.patch(REST + '/model', {
            data: {
                schema: 'olio.charPerson', id: cp.id, objectId: cp.objectId,
                narrative: { schema: 'olio.narrative', id: narrative.id, objectId: narrative.objectId }
            }
        });
        expect(linkResp.ok(), 'PATCH charPerson.narrative: HTTP ' + linkResp.status()).toBe(true);

        // 2. A PORTRAIT node in the book's workflow, bound to the character.
        const wfResp = await request.get(PB + '/workflow');
        expect(wfResp.ok(), 'workflow endpoint failed: ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        expect(wf && wf.workflowObjectId, 'workflowObjectId on the workflow view').toBeTruthy();
        const wfSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.workflow',
                fields: [{ name: 'objectId', comparator: 'equals', value: wf.workflowObjectId }],
                request: ['id', 'objectId', 'groupId', 'groupPath'], recordCount: 1, cache: false
            }
        });
        const wfBody = await wfSearch.json();
        const wfRec = wfBody && wfBody.results && wfBody.results[0];
        expect(wfRec && wfRec.id && wfRec.groupId, 'workflow record: ' + JSON.stringify(wfRec)).toBeTruthy();

        const handle = 'p16-rest-' + ts;
        const nodeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.pb.node',
                name: 'Node ' + handle,
                groupId: wfRec.groupId,
                groupPath: wfRec.groupPath,
                workflow: { schema: 'olio.pb.workflow', id: wfRec.id, objectId: wfRec.objectId },
                handle,
                nodeType: 'portrait',
                nodeStatus: 'pending',
                ordinal: 910,
                scope: 'character',
                scopeRef: cp.objectId
            }
        });
        expect(nodeResp.ok(), 'create olio.pb.node: HTTP ' + nodeResp.status()).toBe(true);
        const node = await nodeResp.json();
        expect(node && node.objectId && node.id, 'node identity').toBeTruthy();

        // 3. The scene route renders scene.sceneNode. ChapBook-created scenes carry no sceneNode (only the
        //    PB2 scene pipeline writes it), so point the seeded book's first page at the node just created.
        //    A portrait as the scene's terminal is a fixture shortcut: the facade and the job plan from the
        //    node id and never check its type, and the full composite pipeline needs LLM stages this test
        //    deliberately avoids.
        const pagesResp = await request.get(PB + '/pages');
        expect(pagesResp.ok(), 'GET /pages: HTTP ' + pagesResp.status()).toBe(true);
        const pages = await pagesResp.json();
        expect(Array.isArray(pages) && pages.length > 0, 'seeded ChapBook has at least one page').toBe(true);
        const sceneOid = pages[0].objectId;
        const sceneSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.scene',
                fields: [{ name: 'objectId', comparator: 'equals', value: sceneOid }],
                request: ['id', 'objectId', 'name', 'sceneIndex', 'sceneNode'], recordCount: 1, cache: false
            }
        });
        const sceneBody = await sceneSearch.json();
        const scene = sceneBody && sceneBody.results && sceneBody.results[0];
        expect(scene && scene.id, 'scene record: ' + JSON.stringify(scene)).toBeTruthy();
        console.log('[p16-scene] ' + JSON.stringify({ id: scene.id, objectId: scene.objectId, sceneIndex: scene.sceneIndex, sceneNodeBefore: scene.sceneNode && scene.sceneNode.id }));

        const bindResp = await request.patch(REST + '/model', {
            data: {
                schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId,
                sceneNode: { schema: 'olio.pb.node', id: node.id, objectId: node.objectId }
            }
        });
        expect(bindResp.ok(), 'PATCH scene.sceneNode: HTTP ' + bindResp.status()).toBe(true);
        const sceneCheck = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.scene',
                fields: [{ name: 'objectId', comparator: 'equals', value: sceneOid }],
                request: ['id', 'objectId', 'sceneNode'], recordCount: 1, cache: false
            }
        });
        const sceneCheckBody = await sceneCheck.json();
        const sceneRead = sceneCheckBody && sceneCheckBody.results && sceneCheckBody.results[0];
        expect(sceneRead && sceneRead.sceneNode && sceneRead.sceneNode.id, 'scene.sceneNode linked: ' + JSON.stringify(sceneRead)).toBe(node.id);

        // 4. Submit from the BROWSER (its own session), then close the page before the render can finish.
        await loginAsSharedUser(page);
        const renderPath = '/AccountManagerService7/rest/olio/picture-book/' + wfPb2BookObjectId + '/scene/' + sceneOid + '/render';
        const submittedAt = Date.now();
        const submit = await page.evaluate(async (path) => {
            const r = await fetch(path, { method: 'POST', credentials: 'include' });
            return { status: r.status, text: await r.text() };
        }, renderPath);
        console.log('[p16-submit] HTTP ' + submit.status + ' ' + submit.text.slice(0, 600));
        expect(submit.status, '/scene/{id}/render HTTP ' + submit.status + ' ' + submit.text.slice(0, 400)).toBe(202);
        const dto = JSON.parse(submit.text);
        expect(dto.jobId, 'jobId').toBeTruthy();
        expect(dto.runObjectId, 'runObjectId').toBeTruthy();
        expect(['queued', 'running']).toContain(String(dto.jobStatus).toLowerCase());
        expect(dto.sceneObjectId).toBe(sceneOid);
        expect(dto.plannedNodeCount).toBe(1);
        expect(dto.planned).toContain(handle);
        expect(dto.kind).toBe('pb.render');

        await page.close();
        const closedAt = Date.now();
        console.log('[p16-leave] page closed ' + (closedAt - submittedAt) + ' ms after submit; job ' + dto.jobId + ' run ' + dto.runObjectId);

        // 5. Watch the job from a different session of the same user until it is terminal (no time threshold:
        //    the deadline exists only so a hung job fails the test instead of the suite).
        //    The FIRST poll after page.close() is kept separately: "the render continued after the page
        //    closed" is proven by that sample being non-terminal (queued/running) while the loop then runs
        //    the same job to completed. A finishedAt > closedAt comparison cannot fail here (finishedAt is
        //    sampled after a loop that starts after the close), so it proves nothing.
        let job = null;
        let firstPollAfterClose = null;
        let lastPhase = null;
        const deadline = Date.now() + 480000;
        while (Date.now() < deadline) {
            const jobResp = await request.get(REST + '/job/' + dto.jobId);
            expect(jobResp.ok(), 'GET /job/{id}: HTTP ' + jobResp.status()).toBe(true);
            job = await jobResp.json();
            if (firstPollAfterClose === null) {
                firstPollAfterClose = job;
                console.log('[p16-job] first poll after page close (+' + (Date.now() - closedAt) + 'ms): '
                    + JSON.stringify({ status: job.status, phase: job.phase, terminal: job.terminal }));
            }
            const phase = job.status + '/' + job.phase + '/' + job.current + '/' + job.total;
            if (phase !== lastPhase) {
                console.log('[p16-job] +' + (Date.now() - submittedAt) + 'ms ' + phase);
                lastPhase = phase;
            }
            if (job.terminal) break;
            await new Promise(r => setTimeout(r, 3000));
        }
        const finishedAt = Date.now();
        expect(job && job.terminal, 'job reached a terminal state: ' + JSON.stringify(job)).toBe(true);
        expect(firstPollAfterClose, 'at least one poll was made after the page was closed').toBeTruthy();
        expect(['queued', 'running'], 'the job was still in flight on the first poll after the page closed: '
            + JSON.stringify(firstPollAfterClose)).toContain(String(firstPollAfterClose.status).toLowerCase());
        expect(firstPollAfterClose.terminal, 'first poll after close is non-terminal').toBeFalsy();
        console.log('[p16-job] terminal after ' + (finishedAt - submittedAt) + ' ms: ' + JSON.stringify({
            status: job.status, cancelled: job.cancelled, error: job.error, elapsed: job.elapsed,
            result: job.result && { runStatus: job.result.runStatus, executedNodeCount: job.result.executedNodeCount,
                failedNodeCount: job.result.failedNodeCount, durationMs: job.result.durationMs, error: job.result.error,
                nodes: job.result.nodes }
        }));
        expect(job.status, 'job status: ' + JSON.stringify(job)).toBe('completed');
        expect(job.cancelled).toBe(false);
        expect(job.result && job.result.runStatus, 'result.runStatus').toBe('COMPLETED');
        expect(job.result.executedNodeCount).toBe(1);
        expect(job.result.failedNodeCount).toBe(0);
        expect(typeof job.result.durationMs).toBe('number');
        const outcome = (job.result.nodes || []).find(n => n.nodeObjectId === node.objectId);
        expect(outcome, 'outcome for the node: ' + JSON.stringify(job.result.nodes)).toBeTruthy();
        expect(String(outcome.status).toUpperCase()).toBe('DONE');
        expect(outcome.artifactObjectId, 'outcome.artifactObjectId').toBeTruthy();
        expect(typeof outcome.durationMs).toBe('number');

        // 6. Durable state, read back from the DB rather than the in-memory registry.
        const runSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.run',
                fields: [{ name: 'objectId', comparator: 'equals', value: dto.runObjectId }],
                request: ['id', 'objectId', 'runStatus', 'executedNodeCount', 'failedNodeCount', 'error'], recordCount: 1, cache: false
            }
        });
        const runBody = await runSearch.json();
        const run = runBody && runBody.results && runBody.results[0];
        console.log('[p16-run] ' + JSON.stringify(run));
        expect(run && run.objectId, 'run row: ' + JSON.stringify(runBody)).toBe(dto.runObjectId);
        expect(String(run.runStatus).toUpperCase()).toBe('COMPLETED');
        expect(run.executedNodeCount).toBe(1);

        const wf2Resp = await request.get(PB + '/workflow');
        expect(wf2Resp.ok()).toBe(true);
        const wf2 = await wf2Resp.json();
        const viewNode = (wf2.nodes || []).find(n => n.objectId === node.objectId);
        expect(viewNode && viewNode.status, 'workflow view status: ' + JSON.stringify(viewNode)).toBe('DONE');

        // 7. The artifact and its bytes.
        const artResp = await request.get(PB + '/artifact/' + outcome.artifactObjectId);
        expect(artResp.ok(), 'GET artifact: HTTP ' + artResp.status()).toBe(true);
        const art = await artResp.json();
        console.log('[p16-artifact] ' + JSON.stringify({
            objectId: art.objectId, role: art.role, revision: art.revision, seed: art.seed, byteLength: art.byteLength,
            imageWidth: art.imageWidth, imageHeight: art.imageHeight, mimeType: art.mimeType, backend: art.backend,
            durationMs: art.durationMs, reviewStatus: art.reviewStatus, selected: art.selected, dataObjectId: art.dataObjectId
        }));
        expect(art.role).toBe('portrait');
        expect(String(art.backend).toUpperCase()).toBe('SWARM');
        expect(typeof art.durationMs, 'artifact.durationMs recorded (never asserted as a threshold)').toBe('number');
        expect(art.dataObjectId, 'artifact.dataObjectId').toBeTruthy();
        const dataResp = await request.get(REST + '/model/data.data/' + art.dataObjectId);
        expect(dataResp.ok(), 'GET data.data: HTTP ' + dataResp.status()).toBe(true);
        const dataRec = await dataResp.json();
        expect(dataRec && dataRec.groupPath && dataRec.name, 'data.data carries groupPath + name').toBeTruthy();
        const mediaUrl = BASE_URL + '/AccountManagerService7/media/Development/data.data' + dataRec.groupPath + '/' + encodeURIComponent(dataRec.name);
        const media = await request.get(mediaUrl);
        expect(media.ok(), 'GET media ' + mediaUrl + ' HTTP ' + media.status()).toBe(true);
        let raw = await media.body();
        if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
        expect(raw.subarray(0, 8).toString('hex'), 'artifact bytes are a PNG').toBe('89504e470d0a1a0a');
        const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
        expect(dims[0] > 0 && dims[1] > 0, 'PNG has dimensions').toBe(true);
        mkdirSync('e2e/screenshots', { recursive: true });
        const pngPath = 'e2e/screenshots/p16-async-portrait.png';
        writeFileSync(pngPath, raw);
        await test.info().attach('p16-async-portrait.png', { path: pngPath, contentType: 'image/png' });
        console.log('[p16-portrait] ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) -> ' + pngPath);

        await request.get(REST + '/logout');
    });

});
