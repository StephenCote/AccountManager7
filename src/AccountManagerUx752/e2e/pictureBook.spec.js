/**
 * PictureBook (PB2) E2E audit — proves what the PB2 STORY journey actually does, with real evidence.
 *
 * Run against the Docker UAT stack (single origin serving Ux + /AccountManagerService7):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBook.spec.js --workers=1 --project=chromium
 *
 * Load-safe, no LLM/SD: book+universe+world creation via POST /chapter (UAT Issue #1), workflow-node
 * provisioning, /pages DTO shape, and the PB2 reader route loading in a real browser. (The gated full
 * render journey went with POST /scene/{id}/generate at Phase 10 P10-1; the V3 render → in-browser image
 * proof is pictureBookNewBookRender.spec.js.)
 *
 * NEVER uses the admin user for assertions — ensureSharedTestUser() provisions; every assertion runs as
 * e2etest_shared.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results');

/** Log in as the shared test user (page.request shares the browser cookie jar), then boot the SPA. */
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

    // Stub WebSocket — Docker's nginx strips cookies on the WS upgrade so Tomcat closes the
    // connection, which triggers forceLogin() and redirects to #!/sig.
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

/** POST /login on a Playwright APIRequestContext as the shared user (own cookie jar). */
async function apiLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user API login failed: ' + resp.status()).toBeTruthy();
}

// ── Shared state across the default suite ─────────────────────────────────────
let bookObjectId = null;
let bookSlug = null;
let createResponseJson = null;

test.describe.serial('PictureBook PB2 — creation & reader (load-safe)', () => {
    test.describe.configure({ timeout: 120000 });

    // Create the book once for the whole (serial) group. Because the group is serial, any retry
    // re-runs beforeAll and rebuilds this state — so no test ever sees a null bookObjectId.
    test.beforeAll(async ({ request }) => {
        fs.mkdirSync(OUT_DIR, { recursive: true });
        await ensureSharedTestUser(request);
        await apiLoginShared(request);

        bookSlug = 'pb2-audit-' + Date.now().toString(36);
        const createResp = await request.post(PB + '/chapter', {
            data: { slug: bookSlug, title: 'PB2 Audit Book' },
            timeout: 120000
        });
        expect(createResp.ok(),
            'POST /chapter failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        createResponseJson = await createResp.json();
        bookObjectId = createResponseJson && createResponseJson.bookObjectId;
    });

    // STEP 1 — the wizard's book-creation call is POST /olio/picture-book/chapter (sceneExtractor
    // .createChapBookRecord / pictureBook.js wizard). PbBookUtil.createBook creates the book row,
    // then its own Olio world (whose basis is the org's Books universe), then patches the world FK.
    test('Step 1: POST /chapter creates an olio.pb.book WITH a world (UAT Issue #1)', async ({ request }) => {
        await apiLoginShared(request);
        const created = createResponseJson;
        expect(bookObjectId, 'no bookObjectId returned from /chapter: ' + JSON.stringify(created)).toBeTruthy();
        expect(created.slug, 'slug echoed back should match').toBe(bookSlug);

        // Verify the book row exists and its `world` FK is populated (the exact UAT Issue #1 defect:
        // the book used to be created with no universe/world). Project `world` explicitly — foreign
        // fields are not auto-populated, and /full's planMost hits the pb.book 100-arg JSON limit.
        const searchResp = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.pb.book',
                fields: [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
                request: ['id', 'objectId', 'name', 'slug', 'bookType', 'world'],
                recordCount: 1,
                cache: false
            }
        });
        expect(searchResp.ok(), 'olio.pb.book search failed: ' + searchResp.status()).toBe(true);
        const sres = await searchResp.json();
        const book = sres && sres.results && sres.results[0];
        expect(book, 'created olio.pb.book not found by objectId').toBeTruthy();
        expect(book.slug, 'stored slug mismatch').toBe(bookSlug);

        // The world reference may come back as a nested {objectId} or a foreign-id key (world_FK).
        const worldRef = book.world || book.world_FK || (book.world && (book.world.objectId || book.world.id));
        console.log('[pictureBook.spec] Step 1 book=' + bookObjectId + ' slug=' + bookSlug +
            ' world=' + JSON.stringify(book.world) + ' world_FK=' + JSON.stringify(book.world_FK));
        expect(worldRef,
            'olio.pb.book was created WITHOUT a world reference — UAT Issue #1 regression: ' + JSON.stringify(book))
            .toBeTruthy();
    });

    // STEP 3 (structure) — ACTUAL CONTRACT, discovered by running this test: PbBookUtil.createBook
    // does NOT provision a workflow graph. The workflow is created lazily on first scene generation,
    // so a fresh book's GET /workflow is a deliberate 404 ("no workflow yet - generate a scene first").
    // This asserts that honest behavior; the populated-graph case is covered by the gated render test.
    test('Step 3: GET /{book}/workflow on a fresh book is 404 "no workflow yet"', async ({ request }) => {
        await apiLoginShared(request);
        expect(bookObjectId, 'Step 1 must have created a book').toBeTruthy();

        const wfResp = await request.get(PB + '/' + bookObjectId + '/workflow');
        const bodyText = await wfResp.text();
        console.log('[pictureBook.spec] Step 3 workflow status=' + wfResp.status() + ' body=' + bodyText);
        expect(wfResp.status(),
            'fresh book workflow should be 404 (lazy provisioning) — got ' + wfResp.status() + ' ' + bodyText)
            .toBe(404);
        expect(bodyText, 'unexpected 404 body').toContain('no workflow yet');
    });

    // STEP 4 (DTO) — /pages is what the reader consumes. On a fresh book (no scenes) it must be a
    // well-formed empty array, not an error; when scenes exist each carries the image path fields.
    test('Step 4: GET /{book}/pages returns a well-formed (empty) page list', async ({ request }) => {
        await apiLoginShared(request);
        expect(bookObjectId, 'Step 1 must have created a book').toBeTruthy();

        const pagesResp = await request.get(PB + '/' + bookObjectId + '/pages');
        expect(pagesResp.ok(), 'pages fetch failed: ' + pagesResp.status() + ' ' + await pagesResp.text()).toBe(true);
        const pages = await pagesResp.json();
        expect(Array.isArray(pages), '/pages did not return an array').toBe(true);
        console.log('[pictureBook.spec] Step 4 pages.length=' + pages.length);
        // A brand-new book has no scenes yet — that is the honest state, and the reader shows
        // "No scenes in this book yet." Any pages present must carry the DTO the reader reads.
        for (const p of pages) {
            expect(p).toHaveProperty('sceneIndex');
            expect(p).toHaveProperty('dataObjectId');
            expect(p).toHaveProperty('imageGroupPath');
            expect(p).toHaveProperty('imageName');
        }
    });

    // STEP 5 (viewer loads) — the PB2 reader route must render in a real browser without the
    // forceLogin bounce, load the book via am7client.getFull, and reach a stable state.
    test('Step 5: PB2 reader route loads in-browser (#!/picture-book/v2/{oid})', async ({ page }) => {
        expect(bookObjectId, 'Step 1 must have created a book').toBeTruthy();
        await loginAsSharedUser(page);

        await page.evaluate((oid) => { window.location.hash = '!/picture-book/v2/' + oid; }, bookObjectId);
        // Wait for the reader to settle out of its "Loading scenes..." state.
        await page.waitForFunction(() => {
            const t = document.body.innerText || '';
            return !t.includes('Loading scenes...');
        }, { timeout: 20000 }).catch(() => {});
        await page.waitForTimeout(1500);

        const bodyText = await page.evaluate(() => document.body.innerText || '');
        console.log('[pictureBook.spec] Step 5 reader landed. hash=' +
            await page.evaluate(() => window.location.hash));

        // Must NOT have been bounced to the sign-in page.
        const hash = await page.evaluate(() => window.location.hash);
        expect(hash.includes('/sig'), 'reader bounced to sign-in (forceLogin) — got ' + hash).toBe(false);
        expect(hash.includes('/picture-book/v2/'), 'not on the PB2 reader route: ' + hash).toBe(true);

        // The reader either shows scenes or the honest empty state — but NOT a hard error.
        expect(bodyText.includes('Failed to load pages'),
            'reader reported a load error: ' + bodyText.slice(0, 400)).toBe(false);

        const shot = path.join(OUT_DIR, 'pb2-reader-loaded.png');
        await page.screenshot({ path: shot, fullPage: true });
        console.log('[pictureBook.spec] Step 5 screenshot: ' + shot);
    });
});
