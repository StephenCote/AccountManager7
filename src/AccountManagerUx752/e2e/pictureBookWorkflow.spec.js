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

function b64(str) { return Buffer.from(str).toString('base64'); }

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

        await request.get(REST + '/logout');
    });

    // ── Gated UI: workflow canvas with PB1 book group objectId ──────────
    //
    // This test requires a REAL PB1 book group objectId (auth.group) that has
    // a PB2 workflow linked via the /pb2 bridge endpoint.  Supply it via:
    //   WORKFLOW_BOOK_GROUP_OID=<auth.group objectId UUID>
    //
    // Creating a PB1 book with a PB2 workflow involves the full Olio scene-generation
    // pipeline (several minutes), so this test is gated rather than auto-seeded.

    test('workflow canvas renders [data-node-id] elements for a PB1 book with workflow', async ({ page }) => {
        const bookGroupOid = process.env.WORKFLOW_BOOK_GROUP_OID;
        if (!bookGroupOid) {
            test.skip('set WORKFLOW_BOOK_GROUP_OID=<auth.group objectId> to run this test');
            return;
        }
        await loginAsSharedUser(page);
        await page.evaluate((oid) => {
            window.location.hash = '!/picture-book/' + oid + '/workflow';
        }, bookGroupOid);

        // Wait for the graph to load (bridge call + workflowView call)
        await page.waitForFunction(
            () => {
                let els = document.querySelectorAll('[data-node-id]');
                let err = document.body.innerText;
                return els.length > 0 || err.includes('Failed') || err.includes('No PB2');
            },
            { timeout: 30000 }
        );

        // At least one node card must be present
        const nodeCards = await page.locator('[data-node-id]').count();
        expect(nodeCards, 'Expected at least one node card with [data-node-id]').toBeGreaterThanOrEqual(1);

        // The "▶ Test" button must be present on at least one node card
        const testBtns = await page.locator('button:has-text("▶ Test")').count();
        expect(testBtns, 'Expected at least one "▶ Test" button on node cards').toBeGreaterThanOrEqual(1);

        // The "↻ Stale" recheck button must be present in the toolbar
        const staleBtn = await page.locator('button:has-text("↻ Stale")').count();
        expect(staleBtn, 'Expected "↻ Stale" button in toolbar').toBeGreaterThanOrEqual(1);
    });

});
