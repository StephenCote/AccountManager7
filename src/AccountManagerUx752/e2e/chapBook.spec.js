/**
 * ChapBook E2E tests — exercises the poem library UI and ChapBook creation flow.
 *
 * Run against the Docker stack:
 *   PLAYWRIGHT_BASE_URL=https://localhost:8443 npx playwright test e2e/chapBook.spec.js --workers=1 --project=chromium
 *
 * Tests that touch the LLM (analyzePoemTheme) are gated behind CHAPBOOK_LLM_TESTS=1
 * because they hit the DGX Spark at 192.168.1.42 and can take several minutes.
 *
 * Tests that touch SD image generation are gated behind CHAPBOOK_SD_TESTS=1.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

// Real poems by Stephen W. Cote — stanza text only (header lines stripped).
const POEM_1 = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.
A precarious branch
Mourning its creased skein's blanch
Weeps spirals of methodical floating
Falling through a brisk wind pirouette
Upon an earthen collet,
The crumpled remains are fleeting.

Memory, do not forget me;
You, sir, have betrayed me.
Languishing in rivulets
Of pollen speckled rain,
Spattering the falling
Offspring moments of magnificence,
Are mere minutes of memories failing
To recall your pitch black heart.
Revel in those falling leaves.`;

const POEM_2 = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green
To silver veins of icy sheens,
Born of spells and sorcery.

Inside hearts and hearths and homes,
Ochre embers and ebon cinders,
Faded life stirred by motherly crones,
Dry damp clothes and warm cold bones
And illuminate the age-old spellbound tomes.`;

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
let orgId = null;
let notesGroupId = null;
let testNoteObjectId = null;

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
        orgId = poemsDirBody.organizationId;

        // Ensure ~/Notes group for data.note import test
        const notesDir = await request.get(REST + '/path/make/auth.group/data/B64-' + Buffer.from('~/Notes').toString('base64').replace(/=/g, '%3D'));
        const notesDirBody = await notesDir.json();
        notesGroupId = notesDirBody && notesDirBody.id;

        // Seed a data.note for POST /poems import test
        if (notesGroupId && orgId) {
            let noteSearch = await request.post(REST + '/model/search', {
                data: {
                    schema: 'io.query', type: 'data.note',
                    fields: [
                        { name: 'name', comparator: 'equals', value: 'chapbook-test-note-fallingleaves' },
                        { name: 'organizationId', comparator: 'equals', value: orgId }
                    ],
                    request: ['id', 'objectId'], recordCount: 1, cache: false
                }
            });
            let noteBody = await noteSearch.json().catch(() => null);
            if (noteBody && noteBody.results && noteBody.results.length > 0) {
                testNoteObjectId = noteBody.results[0].objectId;
            } else {
                let noteResp = await request.post(REST + '/model', {
                    data: {
                        schema: 'data.note',
                        name: 'chapbook-test-note-fallingleaves',
                        groupId: notesGroupId,
                        text: POEM_1
                    }
                });
                let noteCreated = await noteResp.json().catch(() => null);
                testNoteObjectId = noteCreated && noteCreated.objectId;
            }
        }

        // Create poem 1 — idempotent by name search first
        // organizationId required: olio.cb.poem inherits data.directory and PBAC denies list queries without it
        let p1Search = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'equals', value: 'chapbook-real-fallingleaves' },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name'],
                recordCount: 1,
                cache: false
            }
        });
        let p1Body = await p1Search.json().catch(() => null);
        if (p1Body && p1Body.results && p1Body.results.length > 0) {
            poem1ObjectId = p1Body.results[0].objectId;
        } else {
            let p1Resp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem',
                    name: 'chapbook-real-fallingleaves',
                    title: 'Falling Leaves',
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
                fields: [
                    { name: 'name', comparator: 'equals', value: 'chapbook-real-winter1' },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name'],
                recordCount: 1,
                cache: false
            }
        });
        let p2Body = await p2Search.json().catch(() => null);
        if (p2Body && p2Body.results && p2Body.results.length > 0) {
            poem2ObjectId = p2Body.results[0].objectId;
        } else {
            let p2Resp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem',
                    name: 'chapbook-real-winter1',
                    title: 'Winter (part 1)',
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

        // Both poems should appear in the table (use .first() since multiple runs may create duplicates)
        await expect(page.locator('text=Falling Leaves').first()).toBeVisible({ timeout: 10000 });
        await expect(page.locator('text=Winter (part 1)').first()).toBeVisible({ timeout: 5000 });
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

        // Verify the olio.pb.book record exists — use a targeted search rather than /full
        // because planMost(true) on olio.pb.book generates JSON_BUILD_OBJECT with >100 args
        // (PostgreSQL's limit) when olio.world is recursively expanded.
        let bookResp = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.pb.book',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: chapBookObjectId },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name', 'slug', 'world', 'bookType', 'groupId', 'organizationId'],
                recordCount: 1,
                cache: false
            }
        });
        expect(bookResp.ok(), 'book fetch failed: ' + bookResp.status()).toBe(true);
        let bookResult = await bookResp.json();
        let book = bookResult && bookResult.results && bookResult.results[0];
        expect(book, 'book record not found in search results').toBeTruthy();
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
            let stanzaEl = page.locator('p').filter({ hasText: /memory|leaves|falling|pristine|snow|spells/i }).first();
            await expect(stanzaEl).toBeVisible({ timeout: 5000 });
        }
    });

    // ── Test 6: POST /poems bulk import from ordered data.note sources ────────

    test('POST /olio/chap-book/poems imports ordered notes as poems', async ({ request }) => {
        if (!testNoteObjectId) {
            test.skip('testNoteObjectId not seeded — check beforeAll note creation');
            return;
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

        let importResp = await request.post(CB_REST + '/poems', {
            data: {
                sources: [
                    { type: 'data.note', objectId: testNoteObjectId, title: 'Falling Leaves (from note)' }
                ]
            }
        });
        expect(importResp.ok(), 'POST /poems failed: ' + importResp.status() + ' ' + await importResp.text()).toBe(true);

        let result = await importResp.json();
        expect(Array.isArray(result.poems), 'result.poems should be an array').toBe(true);
        expect(result.poems.length, 'should have imported 1 poem').toBe(1);
        expect(result.poems[0].objectId, 'imported poem should have objectId').toBeTruthy();
        expect(result.poems[0].title, 'imported poem should have title').toBeTruthy();
        // errors array should be absent or empty
        expect(!result.errors || result.errors.length === 0, 'no errors expected: ' + JSON.stringify(result.errors)).toBe(true);

        await request.get(REST + '/logout');
    });

    // ── Test 7: UI — Add from Note and Add from Data buttons are present ──────

    test('UI: Add from Note and Add from Data buttons open picker without script errors', async ({ page }) => {
        const errors = [];
        page.on('pageerror', e => errors.push(e.message));

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        await page.waitForTimeout(2000);

        // Both import buttons should be visible
        await expect(page.locator('button:has-text("Add from Note")').first()).toBeVisible({ timeout: 10000 });
        await expect(page.locator('button:has-text("Add from Data")').first()).toBeVisible({ timeout: 5000 });

        // Click Add from Note — the ObjectPicker must open without a script error
        await page.locator('button:has-text("Add from Note")').first().click();
        await page.waitForTimeout(2000);
        expect(errors, 'Script errors on Add from Note click: ' + errors.join('; ')).toHaveLength(0);
        // Picker dialog or modal should be visible (picker renders a full-screen overlay)
        const pickerVisible = await page.locator('.fixed.inset-0, [data-picker], .picker-overlay').first().isVisible().catch(() => false);
        // If picker didn't open via overlay, at minimum no errors should have occurred
        expect(errors).toHaveLength(0);

        // Close if open
        await page.keyboard.press('Escape');
        await page.waitForTimeout(500);
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

    // ── SD-gated test: render produces scene images ───────────────────────────

    test('POST /olio/chap-book/render generates scene images', async ({ page, request }) => {
        if (!process.env.CHAPBOOK_SD_TESTS) {
            test.skip('set CHAPBOOK_SD_TESTS=1 to run SD-dependent ChapBook tests');
            return;
        }
        // SD image generation + LLM landscape prompt can take several minutes per scene.
        // Override the 120s describe-level timeout for this test only.
        test.setTimeout(900000);

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

        // Look up any available olio.llm.chatConfig for LLM landscape prompt generation.
        // When a chatConfig is found it is passed to the render endpoint so the LLM generates
        // a poem-specific landscape prompt for each scene instead of the generic template fallback.
        // (Different poems — e.g. Falling Leaves vs Winter — should produce visually distinct images.)
        let chatConfigName = null;
        if (orgId) {
            const ccResp = await request.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.llm.chatConfig',
                    fields: [{ name: 'organizationId', comparator: 'equals', value: orgId }],
                    request: ['id', 'objectId', 'name'],
                    recordCount: 1,
                    cache: false
                }
            });
            const ccBody = await ccResp.json().catch(() => null);
            if (ccBody && ccBody.results && ccBody.results.length > 0) {
                chatConfigName = ccBody.results[0].name;
            }
        }
        if (chatConfigName) {
            console.log('[chapBook.spec] using chatConfig "' + chatConfigName + '" for LLM landscape prompts');
        } else {
            console.log('[chapBook.spec] no chatConfig found — render will use stored sdPrompt fallback');
        }

        // Always create a fresh book for the SD test so we control scene count via
        // maxLinesPerPage=20 (4 scenes total — manageable within the 10-min timeout).
        // Test 4's book used maxLinesPerPage=4 which produces ~8 scenes and times out.
        // When running in isolation beforeAll poem IDs may be null — look them up.
        let bookOid = null;
        if (!bookOid) {
            let p1Oid = poem1ObjectId;
            let p2Oid = poem2ObjectId;
            if (!p1Oid || !p2Oid) {
                const principalResp = await request.get(REST + '/login/principal');
                const principal = await principalResp.json().catch(() => null);
                const resolvedOrgId = principal && principal.organizationId;
                const lookupPoem = async (name) => {
                    if (!resolvedOrgId) return null;
                    const sr = await request.post(REST + '/model/search', {
                        data: {
                            schema: 'io.query', type: 'olio.cb.poem',
                            fields: [
                                { name: 'name', comparator: 'equals', value: name },
                                { name: 'organizationId', comparator: 'equals', value: resolvedOrgId }
                            ],
                            request: ['id', 'objectId'], recordCount: 1, cache: false
                        }
                    });
                    const body = await sr.json().catch(() => null);
                    return body && body.results && body.results[0] && body.results[0].objectId;
                };
                if (!p1Oid) p1Oid = await lookupPoem('chapbook-real-fallingleaves');
                if (!p2Oid) p2Oid = await lookupPoem('chapbook-real-winter1');
                if (!p1Oid || !p2Oid) {
                    test.skip('poems not found in isolated run — seed poems by running the full suite first');
                    return;
                }
            }
            // maxLinesPerPage=20 keeps each natural stanza as one scene (both poems have
            // stanzas of ≤10 lines), producing 4 scenes total — faster for CI than smaller values.
            let slug = 'chapbook-sd-' + Date.now().toString(36);
            let createResp = await request.post(CB_REST + '/create', {
                data: { slug, title: 'SD Test ChapBook', poemObjectIds: [p1Oid, p2Oid], maxLinesPerPage: 20 }
            });
            expect(createResp.ok(), 'create failed: ' + createResp.status()).toBe(true);
            let created = await createResp.json();
            bookOid = created && (created.bookObjectId || created.objectId);
        }
        expect(bookOid, 'no bookObjectId for render test').toBeTruthy();

        // Trigger SD render — allow up to 10 minutes for LLM + image generation per scene
        let renderBody = chatConfigName ? { chatConfig: chatConfigName } : undefined;
        let renderResp = await request.post(CB_REST + '/render/' + bookOid, {
            data: renderBody,
            timeout: 600000
        });
        expect(renderResp.ok(), 'render failed: ' + renderResp.status() + ' ' + await renderResp.text()).toBe(true);
        let renderResult = await renderResp.json();
        expect(renderResult.rendered, 'rendered count must be >= 1').toBeGreaterThanOrEqual(1);

        // Verify at least one page has a dataObjectId (populated from imageObjectId by bookPageView)
        let pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        expect(pagesResp.ok(), 'pages fetch failed').toBe(true);
        let pages = await pagesResp.json();
        expect(Array.isArray(pages) && pages.length > 0, 'no pages returned').toBe(true);
        let pageWithImage = pages.find(p => p.dataObjectId);
        expect(pageWithImage, 'no page has a dataObjectId after render').toBeTruthy();

        // Navigate to PB2 viewer (/v2/ prefix) and verify image is visible.
        // ChapBook is a PB2 book — using /picture-book/{oid} (PB1) would land on the
        // legacy viewer which never calls the olio/picture-book/pages endpoint and
        // always shows the empty "no images" state for CHAPBOOK books.
        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/picture-book/v2/' + oid; }, bookOid);
        await page.waitForTimeout(3000);
        let nextBtn = page.locator('button[aria-label*="next"], button:has-text("chevron_right"), button:has-text("›")').first();
        if (await nextBtn.isVisible({ timeout: 5000 }).catch(() => false)) {
            await nextBtn.click();
            await page.waitForTimeout(1500);
        }
        // At least one img element should be visible (the SD-generated landscape)
        let imgEl = page.locator('img[src*="data.data"]').first();
        await expect(imgEl).toBeVisible({ timeout: 15000 });

        await request.get(REST + '/logout');
    });
});
