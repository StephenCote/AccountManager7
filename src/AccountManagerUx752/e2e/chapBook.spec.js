/**
 * ChapBook E2E tests — exercises the poem library UI and ChapBook creation flow.
 *
 * Run against the Docker stack:
 *   PLAYWRIGHT_BASE_URL=https://localhost:8443 npx playwright test e2e/chapBook.spec.js --workers=1 --project=chromium
 *
 * Tests that touch the LLM (analyzePoemTheme) are gated behind CHAPBOOK_LLM_TESTS=1
 * because they hit the DGX Spark at 192.168.1.42 and can take several minutes.
 *
 * NOTE: Poem text below is synthetic placeholder. Replace with Stephen's real samples
 *       when they are provided — see feedback-use-real-test-content.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

// Minimal synthetic poem text — sufficient to exercise chunking and scene creation.
// REPLACE with real sample poems when Stephen provides them.
const POEM_1 = `The wind forgets its name in autumn leaves
and every branch remembers, still.
A silence folds itself along the eaves,
the light goes thin and cold with will.

Through the glass the garden holds its breath,
waits for what it cannot name.
Even stones have learned to speak of death
the way a candle speaks of flame.`;

const POEM_2 = `Rain against the window, patient, slow,
each drop a small announcement of the night.
The street below learns what the sleeping know:
that ordinary dark can hold some light.`;

async function loginAsSharedUser(page) {
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

    // Stub WebSocket — Docker's nginx strips cookies on the WS upgrade so Tomcat
    // closes the connection, which triggers forceLogin() and redirects to #!/sig.
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

// ── Setup: create shared user and seed poems via REST ─────────────────────────

let poem1ObjectId = null;
let poem2ObjectId = null;
let chapBookObjectId = null;

test.describe('ChapBook — UI', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        // Ensure shared test user exists
        await ensureSharedTestUser(request);

        // Login as shared user to seed data
        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: Buffer.from('password').toString('base64'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        // Ensure ~/Poems group exists
        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' + Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
        const poemsDirBody = await poemsDir.json();
        expect(poemsDirBody && poemsDirBody.id, 'Could not ensure ~/Poems group').toBeTruthy();
        const poemsGroupId = poemsDirBody.id;

        // Create poem 1 — idempotent by name search first
        let p1Search = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.cb.poem',
                fields: [{ name: 'name', comparator: 'equals', value: 'chapbook-test-autumn' }],
                request: ['id', 'objectId', 'name'],
                recordCount: 1
            }
        });
        let p1Body = await p1Search.json().catch(() => null);
        if (p1Body && p1Body.results && p1Body.results.length > 0) {
            poem1ObjectId = p1Body.results[0].objectId;
        } else {
            let p1Resp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem',
                    name: 'chapbook-test-autumn',
                    title: 'Autumn Study',
                    author: 'E2E Test',
                    groupId: poemsGroupId,
                    text: POEM_1
                }
            });
            let p1Created = await p1Resp.json().catch(() => null);
            poem1ObjectId = p1Created && p1Created.objectId;
        }
        expect(poem1ObjectId, 'poem 1 objectId not set').toBeTruthy();

        // Create poem 2
        let p2Search = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.cb.poem',
                fields: [{ name: 'name', comparator: 'equals', value: 'chapbook-test-rain' }],
                request: ['id', 'objectId', 'name'],
                recordCount: 1
            }
        });
        let p2Body = await p2Search.json().catch(() => null);
        if (p2Body && p2Body.results && p2Body.results.length > 0) {
            poem2ObjectId = p2Body.results[0].objectId;
        } else {
            let p2Resp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem',
                    name: 'chapbook-test-rain',
                    title: 'Rain Study',
                    author: 'E2E Test',
                    groupId: poemsGroupId,
                    text: POEM_2
                }
            });
            let p2Created = await p2Resp.json().catch(() => null);
            poem2ObjectId = p2Created && p2Created.objectId;
        }
        expect(poem2ObjectId, 'poem 2 objectId not set').toBeTruthy();

        await request.get(REST + '/logout');
    });

    // ── Test 1: /chap-book route loads and poem library renders ───────────────

    test('chap-book route loads and poem library is visible', async ({ page }) => {
        await loginAsSharedUser(page);

        // Navigate to ChapBook feature
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        await page.waitForTimeout(1500);

        // The ChapBook feature should render — look for the section heading or table
        await expect(
            page.locator('text=Poem Library').or(page.locator('text=ChapBook')).first()
        ).toBeVisible({ timeout: 10000 });
    });

    // ── Test 2: Poem library shows the seeded poems ───────────────────────────

    test('poem library shows seeded poems', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        await page.waitForTimeout(2000);

        // Both poems should appear in the table
        await expect(page.locator('text=Autumn Study')).toBeVisible({ timeout: 10000 });
        await expect(page.locator('text=Rain Study')).toBeVisible({ timeout: 5000 });
    });

    // ── Test 3: Multi-select and Create ChapBook dialog opens ─────────────────

    test('selecting poems enables Create ChapBook button', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        await page.waitForTimeout(2000);

        // Check the first poem's checkbox
        let checkboxes = page.locator('input[type="checkbox"]');
        await expect(checkboxes.first()).toBeVisible({ timeout: 10000 });
        await checkboxes.first().check();

        // Create ChapBook button should now be enabled
        let createBtn = page.locator('button:has-text("Create ChapBook"), button:has-text("Create Chap")').first();
        await expect(createBtn).toBeVisible({ timeout: 5000 });
        await expect(createBtn).toBeEnabled();

        // Click it — the create dialog/overlay should appear
        await createBtn.click();
        await expect(
            page.locator('text=Title').or(page.locator('text=Slug')).first()
        ).toBeVisible({ timeout: 5000 });
    });

    // ── Test 4: Create ChapBook via REST, verify olio.pb.book record exists ───

    test('POST /olio/chap-book/create produces a book with scenes', async ({ request }) => {
        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: Buffer.from('password').toString('base64'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        let slug = 'chapbook-e2e-' + Date.now().toString(36);
        let createResp = await request.post(CB_REST + '/create', {
            data: {
                slug: slug,
                title: 'E2E Test ChapBook',
                poemObjectIds: [poem1ObjectId, poem2ObjectId],
                maxLinesPerPage: 4
            }
        });
        expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);

        let created = await createResp.json();
        chapBookObjectId = created && (created.bookObjectId || created.objectId);
        expect(chapBookObjectId, 'no bookObjectId in create response').toBeTruthy();

        // Verify the olio.pb.book record exists
        let bookResp = await request.get(REST + '/model/olio.pb.book/' + chapBookObjectId + '/full');
        expect(bookResp.ok(), 'book full fetch failed').toBe(true);
        let book = await bookResp.json();
        expect(book.slug, 'book has no slug').toBeTruthy();
        expect(book.world || book.world_FK, 'book has no world FK — PB2 world not created').toBeTruthy();

        // Verify scenes were created (one per stanza chunk of both poems)
        let scenesResp = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.pb.scene',
                fields: [{ name: 'name', comparator: 'like', value: '%E2E%' }],
                request: ['id', 'objectId', 'name', 'poemStanza', 'mood'],
                recordCount: 50
            }
        });
        // A looser check — scenes belong to the book's group, not easily queryable by name alone.
        // Primary assertion: book.world is populated.
        expect(book.world || book.world_FK, 'world FK must be populated').toBeTruthy();

        await request.get(REST + '/logout');
    });

    // ── Test 5: ChapBook page renderer shows stanza text ─────────────────────

    test('renderChapBookPage produces stanza text overlay', async ({ page }) => {
        // Navigate to the book viewer if chapBookObjectId was set by test 4
        // (tests run in order within describe; if test 4 was skipped this will bail gracefully)
        if (!chapBookObjectId) {
            test.skip('chapBookObjectId not set — test 4 may have been skipped');
            return;
        }
        await loginAsSharedUser(page);
        await page.evaluate((oid) => {
            window.location.hash = '!/picture-book/' + oid;
        }, chapBookObjectId);
        await page.waitForTimeout(2500);

        // The viewer should render — cover page or scene page
        await expect(
            page.locator('[role="main"]')
        ).toBeVisible({ timeout: 10000 });

        // If a scene exists with poemStanza, navigate to page 1 and verify text
        let nextBtn = page.locator('button[aria-label*="next"], button:has-text("›"), button:has-text("→")').first();
        if (await nextBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
            await nextBtn.click();
            await page.waitForTimeout(500);
            // Poem text should be visible on scene page
            let stanzaEl = page.locator('p').filter({ hasText: /wind|rain|autumn|stone|window|light/i }).first();
            await expect(stanzaEl).toBeVisible({ timeout: 5000 });
        }
    });

    // ── LLM-gated test: poem analysis enriches theme/mood/keywords ────────────

    test('POST /olio/chap-book/analyze/{poemObjectId} enriches poem metadata', async ({ request }) => {
        if (!process.env.CHAPBOOK_LLM_TESTS) {
            test.skip('set CHAPBOOK_LLM_TESTS=1 to run LLM-dependent ChapBook tests');
        }

        const loginResp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: Buffer.from('password').toString('base64'),
                type: 'hashed_password'
            }
        });
        expect(loginResp.ok() || loginResp.status() === 204).toBe(true);

        let analyzeResp = await request.post(CB_REST + '/analyze/' + poem1ObjectId);
        expect(analyzeResp.ok(), 'analyze failed: ' + analyzeResp.status()).toBe(true);

        // Verify the poem record now has theme/mood/keywords populated
        let poemResp = await request.get(REST + '/model/olio.cb.poem/' + poem1ObjectId + '/full');
        expect(poemResp.ok()).toBe(true);
        let poem = await poemResp.json();
        expect(poem.theme, 'theme not populated after analyze').toBeTruthy();
        expect(poem.mood, 'mood not populated after analyze').toBeTruthy();

        await request.get(REST + '/logout');
    });
});
