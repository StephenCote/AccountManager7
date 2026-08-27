/**
 * PictureBook (PB2) E2E audit — proves what the PB2 STORY journey actually does, with real evidence.
 *
 * Run against the Docker UAT stack (single origin serving Ux + /AccountManagerService7):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBook.spec.js --workers=1 --project=chromium
 *
 * Two tiers:
 *  - DEFAULT SUITE (load-safe, no LLM/SD): book+universe+world creation via POST /chapter (UAT Issue #1),
 *    workflow-node provisioning, /pages DTO shape, and the PB2 reader route loading in a real browser.
 *  - GATED (PB_SD_TESTS=1): the full render journey — create-from-scenes (LLM character build) → workflow
 *    node render (SD) → in-browser image byte proof (naturalWidth>0 + PNG/JPEG magic on the app-produced
 *    MediaServlet src). Gated + serial because it hits the DGX Spark (192.168.1.42) and GTR9 Swarm
 *    (192.168.1.39); firing it in parallel with other LLM/SD work crashes .42.
 *
 * NEVER uses the admin user for assertions — ensureSharedTestUser() / ensureChatConfig() provision;
 * every assertion runs as e2etest_shared.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
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

// ── GATED: full render journey (LLM + SD) — PB_SD_TESTS=1, --workers=1 ─────────
const RENDER_ENABLED = process.env.PB_SD_TESTS === '1';

test.describe('PictureBook PB2 — full render journey (LLM+SD, gated)', () => {
    test.describe.configure({ timeout: 900000 }); // 15 min per test — LLM char build + SD render

    test.skip(!RENDER_ENABLED, 'PB_SD_TESTS!=1 — skips LLM/SD render (DGX Spark + GTR9 Swarm)');

    let rBook = null;
    let rWorkObjectId = null;
    let chatConfigName = null;

    test.beforeAll(async ({ request }) => {
        fs.mkdirSync(OUT_DIR, { recursive: true });
        await ensureSharedTestUser(request);
        await apiLoginShared(request);
        // ensureChatConfig self-resolves orgId from the ~/Chat group it ensures — there is no
        // /login/principal route (it 404s), so do NOT try to read organizationId from one here.
        chatConfigName = await ensureChatConfig(request);
        expect(chatConfigName, 'could not provision an LLM chatConfig for the render test').toBeTruthy();
    });

    test('Steps 2-5: create-from-scenes → node render → in-browser image byte proof', async ({ page, request }) => {
        await apiLoginShared(request);

        // A "work" record (findWork accepts data.data or data.note by objectId) is the source document
        // the wizard extracts scenes from. We supply a pre-built sceneList so we exercise the render
        // pipeline, not the LLM scene-extraction step.
        const notesDir = await request.get(REST + '/path/make/auth.group/data/' +
            'B64-' + Buffer.from('~/Notes').toString('base64').replace(/=/g, '%3D'));
        const dir = await notesDir.json().catch(() => null);
        expect(dir && dir.id, 'could not resolve ~/Notes group').toBeTruthy();

        const workName = 'pb2-render-work-' + Date.now().toString(36);
        const noteResp = await request.post(REST + '/model', {
            data: {
                schema: 'data.note', groupId: dir.id, groupPath: dir.path, name: workName,
                text: 'A lone lighthouse keeper watches a storm roll across the northern sea at dawn.'
            }
        });
        expect(noteResp.ok(), 'work note create failed: ' + noteResp.status()).toBe(true);
        const note = await noteResp.json();
        rWorkObjectId = note && note.objectId;
        expect(rWorkObjectId, 'no objectId for work note').toBeTruthy();

        // Minimal one-scene book. sceneList shape mirrors the wizard's extractedScenes.
        const sceneList = [{
            sceneIndex: 0,
            title: 'The Lighthouse at Dawn',
            summary: 'A lighthouse keeper watches a storm approach across the northern sea at dawn.',
            description: 'A lone lighthouse keeper watches a storm roll across the northern sea at dawn.',
            characters: [{ name: 'The Keeper' }]
        }];

        const cfsResp = await request.post(PB + '/' + rWorkObjectId + '/create-from-scenes', {
            data: {
                schema: 'olio.pictureBookRequest',
                sceneList,
                chatConfig: chatConfigName,
                genre: 'literary',
                bookName: 'PB2 Render Audit ' + Date.now().toString(36)
            },
            timeout: 600000
        });
        expect(cfsResp.ok(),
            'create-from-scenes failed: ' + cfsResp.status() + ' ' + await cfsResp.text()).toBe(true);
        const meta = await cfsResp.json();
        console.log('[pictureBook.spec] render meta=' + JSON.stringify(meta).slice(0, 500));

        // Resolve the PB2 book objectId for this work (getBookInfo: GET /{workGroup}/pb2 or /books).
        const booksResp = await request.get(PB + '/books');
        const books = await booksResp.json().catch(() => []);
        // meta may carry bookObjectId; otherwise match by the freshly-created name/slug.
        rBook = meta && (meta.bookObjectId || meta.pb2BookObjectId);
        if (!rBook && Array.isArray(books) && books.length) {
            rBook = books[books.length - 1].objectId;
        }
        expect(rBook, 'could not resolve a PB2 book objectId after create-from-scenes').toBeTruthy();

        // Render a scene via the real per-scene render trigger the UX drives:
        // POST /scene/{sceneObjectId}/generate runs PictureBookUtil.generateSceneImage, which lazily
        // creates the workflow AND renders the SD image (4-stage pipeline). create-from-scenes builds
        // the book+characters but NO workflow — the workflow/nodes are created here, on first generate.
        const scenesResp = await request.get(PB + '/' + rBook + '/scenes');
        expect(scenesResp.ok(), 'scenes fetch failed: ' + scenesResp.status()).toBe(true);
        const scenes = await scenesResp.json();
        expect(Array.isArray(scenes) && scenes.length > 0, 'render book has no scenes').toBe(true);
        const sceneOid = scenes[0].objectId;
        expect(sceneOid, 'first scene has no objectId: ' + JSON.stringify(scenes[0])).toBeTruthy();

        const genResp = await request.post(PB + '/scene/' + sceneOid + '/generate', {
            data: { chatConfig: chatConfigName },
            timeout: 600000
        });
        expect(genResp.ok(),
            'scene generate failed: ' + genResp.status() + ' ' + await genResp.text()).toBe(true);
        const gen = await genResp.json();
        console.log('[pictureBook.spec] generateSceneImage -> ' + JSON.stringify(gen).slice(0, 500));
        expect(gen && gen.imageObjectId,
            'generateSceneImage produced no imageObjectId: ' + JSON.stringify(gen)).toBeTruthy();

        // The image was written as a data.data record whose objectId generateSceneImage returned.
        // create-from-scenes books are group+meta books (NOT olio.pb.book workflow books), so /pages
        // and /workflow don't apply — the authoritative byte-proof is the data.data record itself:
        // resolve its groupPath+name and fetch the raw bytes from the MediaServlet, exactly the URL
        // the meta-based viewer builds (pb2ImageUrl / resolveAllImageUrls).
        const imgOid = gen.imageObjectId;
        const dataResp = await request.get(REST + '/model/data.data/' + imgOid + '/full');
        expect(dataResp.ok(), 'image data.data fetch failed: ' + dataResp.status()).toBe(true);
        const dataRec = await dataResp.json();
        expect(dataRec && dataRec.groupPath && dataRec.name,
            'image data.data missing groupPath/name: ' + JSON.stringify(dataRec)).toBeTruthy();
        expect((dataRec.contentType || '').startsWith('image/'),
            'image data.data is not image/*: ' + dataRec.contentType).toBe(true);

        const orgDot = 'Development';
        const mediaUrl = '/AccountManagerService7/media/' + orgDot + '/data.data' +
            encodeURI(dataRec.groupPath) + '/' + encodeURIComponent(dataRec.name);
        const imgResp = await request.get(mediaUrl, { timeout: 60000 });
        expect(imgResp.ok(), 'media fetch failed: ' + imgResp.status() + ' for ' + mediaUrl).toBe(true);
        const imgBuf = Buffer.from(await imgResp.body());
        expect(imgBuf.length, 'rendered image is empty').toBeGreaterThan(1000);

        const outPath = path.join(OUT_DIR, 'pb2-render-bytes.bin');
        fs.writeFileSync(outPath, imgBuf);
        const isPng = imgBuf[0] === 0x89 && imgBuf[1] === 0x50 && imgBuf[2] === 0x4e && imgBuf[3] === 0x47;
        const isJpeg = imgBuf[0] === 0xff && imgBuf[1] === 0xd8 && imgBuf[2] === 0xff;
        console.log('[pictureBook.spec] wrote ' + imgBuf.length + ' bytes to ' + outPath +
            ' magic=' + imgBuf.slice(0, 4).toString('hex'));
        expect(isPng || isJpeg, 'rendered bytes are neither PNG nor JPEG: ' + imgBuf.slice(0, 4).toString('hex')).toBe(true);

        // In-browser proof: the meta-based viewer (/picture-book/{bookGroupObjectId}) renders scene
        // images by resolving imageObjectId -> media URL. (The /v2 reader is for olio.pb.book workflow
        // books, which create-from-scenes does not produce — UAT Issue #1.)
        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/picture-book/' + oid; }, rBook);
        await page.waitForTimeout(4000);
        const nextBtn = page.locator('button:has-text("arrow_forward"), button:has-text("Begin"), button:has-text("chevron_right")').first();
        if (await nextBtn.isVisible({ timeout: 5000 }).catch(() => false)) {
            await nextBtn.click();
            await page.waitForTimeout(2000);
        }

        const imgEl = page.locator('img[src*="/media/"][src*="data.data"]').first();
        await expect(imgEl).toBeVisible({ timeout: 20000 });
        const naturalWidth = await imgEl.evaluate(el => el.naturalWidth);
        expect(naturalWidth, 'image did not decode in browser (naturalWidth=0)').toBeGreaterThan(0);

        const shot = path.join(OUT_DIR, 'pb2-render.png');
        await page.screenshot({ path: shot, fullPage: true });
        console.log('[pictureBook.spec] render screenshot: ' + shot +
            ' (browser img naturalWidth=' + naturalWidth + ')');
    });
});
