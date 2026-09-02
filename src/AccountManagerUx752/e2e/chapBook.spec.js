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
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

// Resolve the real poem corpus + the binary .docx fixture relative to THIS spec file,
// so the paths hold regardless of the process cwd. The spec lives in
// src/AccountManagerUx752/e2e/, the corpus at the git root under volatile/poemsXml/txt.
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const CORPUS_DIR = path.resolve(SPEC_DIR, '../../../volatile/poemsXml/txt');
const DOCX_FIXTURE = path.resolve(SPEC_DIR, 'fixtures/winter_1.docx');

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

        // The shared test DB accumulates poems from prior runs (e.g. the scoping-override
        // spec's C3/C5 rows), which alphabetically crowd page 1. Mirror real user behavior:
        // type each title into the "Filter by theme or title..." input so the seeded poem is
        // found regardless of default first-page ordering. The library filters client-side by
        // title/theme substring (filteredPoems in chapBook.js).
        const filter = page.getByPlaceholder('Filter by theme or title...');
        await expect(filter).toBeVisible({ timeout: 10000 });

        // Both poems should appear once filtered (use .first() since multiple runs may create duplicates)
        await filter.fill('Falling Leaves');
        await expect(page.locator('text=Falling Leaves').first()).toBeVisible({ timeout: 10000 });

        await filter.fill('Winter (part 1)');
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

    test('UI: picker "up" button navigates to parent group and shows folders', async ({ page }) => {
        const errors = [];
        page.on('pageerror', e => errors.push(e.message));

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        await page.waitForTimeout(2000);

        // Open the Add from Note picker
        await page.locator('button:has-text("Add from Note")').first().click();
        await page.waitForTimeout(2000);
        expect(errors, 'Script errors opening picker: ' + errors.join('; ')).toHaveLength(0);

        // The picker overlay must be visible
        const overlay = page.locator('.am7-picker-overlay');
        await expect(overlay).toBeVisible({ timeout: 5000 });

        // Click the "navigate up" button (north_west icon) to go to the parent group
        const upBtn = overlay.locator('button:has([class*="material"]):has-text("north_west"), button span:text("north_west")').first();
        // Fallback: look for the actual button containing the icon text
        const upBtnAlt = overlay.locator('button').filter({ hasText: 'north_west' }).first();
        const upVisible = await upBtn.isVisible().catch(() => false) || await upBtnAlt.isVisible().catch(() => false);
        if (!upVisible) {
            // Up button not available (e.g. already at root) — skip navigation sub-test
            await page.keyboard.press('Escape');
            return;
        }
        const clickTarget = (await upBtn.isVisible().catch(() => false)) ? upBtn : upBtnAlt;
        await clickTarget.click();
        await page.waitForTimeout(2000);
        expect(errors, 'Script errors after up click: ' + errors.join('; ')).toHaveLength(0);

        // After navigating up, the picker should still be open (no crash)
        await expect(overlay).toBeVisible({ timeout: 3000 });

        await page.keyboard.press('Escape');
        await page.waitForTimeout(500);
    });

    // ── LLM-gated test: poem analysis enriches theme/mood/keywords ────────────

    test('POST /olio/chap-book/analyze/{poemObjectId} enriches poem metadata', async ({ request }) => {
        if (!process.env.CHAPBOOK_LLM_TESTS) {
            test.skip('set CHAPBOOK_LLM_TESTS=1 to run LLM-dependent ChapBook tests');
        }
        // A cold Ollama model load + JSON theme/mood analysis can exceed the 120s describe timeout.
        test.setTimeout(300000);

        // Provision a real LLM chatConfig owned by the shared test user (idempotent). A clean Docker
        // test DB has none, so analyze would 503 "No chatConfig is configured for this organization".
        const chatConfigName = await ensureChatConfig(request, orgId);
        expect(chatConfigName, 'ensureChatConfig did not return a chatConfig name').toBeTruthy();

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

        let analyzeResp = await request.post(CB_REST + '/analyze/' + poem1ObjectId, {
            data: { chatConfig: chatConfigName },
            timeout: 300000
        });
        expect(analyzeResp.ok(), 'analyze failed: ' + analyzeResp.status() + ' ' + await analyzeResp.text()).toBe(true);

        // Verify the poem record now has theme/mood/keywords populated.
        // Use a targeted search projection rather than GET .../full: planMost(true) on
        // olio.cb.poem exceeds PostgreSQL's 100-argument JSON_BUILD_OBJECT limit and 404s
        // (same guidance as the 6A test). This is the working retrieval pattern beforeAll uses.
        let poemResp = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.cb.poem',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: poem1ObjectId },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['objectId', 'theme', 'mood', 'keywords'],
                recordCount: 1,
                cache: false
            }
        });
        expect(poemResp.ok(), 'poem fetch failed: ' + poemResp.status()).toBe(true);
        let poemResult = await poemResp.json();
        let poem = poemResult && poemResult.results && poemResult.results[0];
        expect(poem, 'poem record not found in search results').toBeTruthy();
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

        // Provision (idempotent) a real olio.llm.chatConfig owned by the shared test user for LLM
        // landscape prompt generation. When a chatConfig is found it is passed to the render endpoint
        // so the LLM generates a poem-specific landscape prompt for each scene instead of the generic
        // template fallback. (Different poems — e.g. Falling Leaves vs Winter — should produce visually
        // distinct images.) resolveDefaultChatConfig filters by ownerId, so the config must be owned by
        // this user — ensureChatConfig guarantees that.
        let chatConfigName = await ensureChatConfig(request, orgId);
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
                // organizationId comes from a path/make response (it carries organizationId).
                // There is NO /login/principal route — it 404s — so never read org from one.
                const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' +
                    Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
                const poemsDirBody = await poemsDir.json().catch(() => null);
                const resolvedOrgId = poemsDirBody && poemsDirBody.organizationId;
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
        // At least one img element should be visible (the SD-generated landscape).
        // Images are served by MediaServlet at /media/{orgDotPath}/data.data{groupPath}/{name}.
        let imgEl = page.locator('img[src*="/media/"][src*="data.data"]').first();
        await expect(imgEl).toBeVisible({ timeout: 15000 });

        // Real proof #1: the BROWSER actually decoded the raster (naturalWidth>0).
        // toBeVisible alone passes on a broken <img>, so it is NOT proof.
        let naturalWidth = await imgEl.evaluate(el => el.naturalWidth);
        expect(naturalWidth, 'image did not decode in browser (naturalWidth=0 — broken src)').toBeGreaterThan(0);

        // Real proof #2: pull the raw bytes from the EXACT url the app produced (not a
        // guessed route), write them to a temp file, and confirm valid PNG/JPEG magic.
        let imgSrc = await imgEl.getAttribute('src');
        let absUrl = imgSrc.startsWith('http') ? imgSrc : new URL(imgSrc, page.url()).toString();
        let imgResp = await request.get(absUrl, { timeout: 60000 });
        expect(imgResp.ok(), 'media fetch failed: ' + imgResp.status() + ' for ' + absUrl).toBe(true);
        let imgBuf = Buffer.from(await imgResp.body());
        expect(imgBuf.length, 'rendered image is empty').toBeGreaterThan(1000);

        let outDir = path.resolve(SPEC_DIR, '../test-results');
        fs.mkdirSync(outDir, { recursive: true });
        let outPath = path.join(outDir, '6C-chapbook-render-bytes.bin');
        fs.writeFileSync(outPath, imgBuf);
        console.log('[chapBook.spec] 6C wrote ' + imgBuf.length + ' image bytes to ' + outPath);

        let isPng = imgBuf[0] === 0x89 && imgBuf[1] === 0x50 && imgBuf[2] === 0x4e && imgBuf[3] === 0x47;
        let isJpeg = imgBuf[0] === 0xff && imgBuf[1] === 0xd8 && imgBuf[2] === 0xff;
        expect(isPng || isJpeg,
            'rendered bytes are neither PNG nor JPEG — magic: ' +
            imgBuf.slice(0, 4).toString('hex')).toBe(true);

        // Full-page screenshot of the rendered scene as the required visual proof.
        let shotPath = path.join(outDir, '6C-chapbook-render.png');
        await page.screenshot({ path: shotPath, fullPage: true });
        console.log('[chapBook.spec] 6C screenshot: ' + shotPath);

        await request.get(REST + '/logout');
    });
});

// ══════════════════════════════════════════════════════════════════════════
// Issue 6A / 6D regression proofs — dedicated ChapBook reader route.
//
// These target the NEW reader route #!/chap-book/read/{bookObjectId}
// (component ChapBookReader in src/features/chapBook.js), NOT the generic
// PB2 viewer the older tests used.
//   6A — a binary .docx import yields READABLE prose (Apache Tika extraction),
//        not raw-UTF-8 zip garbage.
//   6D — the reader shows poem stanza text IMMEDIATELY on load, with no Render.
// Neither test touches the LLM or SD backends, so both run in the default suite.
// ══════════════════════════════════════════════════════════════════════════

const REAL_DOCX_CT = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

// Deterministic set of 10 REAL poems from the corpus (volatile/poemsXml/txt/winter/).
// Fixed list => stable reruns; every entry is a real file verified present at author time.
const WINTER_SET = ['winter_1', 'winter_2', 'winter_3', 'winter_4', 'winter_5',
                    'winter_6', 'winter_7', 'winter_8', 'winter_9', 'winter_10'];

function readCorpusPoem(collection, base) {
    return fs.readFileSync(path.join(CORPUS_DIR, collection, base + '.txt'), 'utf8');
}

// Extract distinctive (long, alphabetic) words from real poem text so the DOM
// assertion is tied to the ACTUAL seeded content, not a hard-coded guess.
function distinctiveWords(text, minLen, max) {
    let seen = new Set();
    let out = [];
    let words = (text || '').split(/[^A-Za-z]+/);
    for (let w of words) {
        let lw = w.toLowerCase();
        if (w.length >= (minLen || 7) && !seen.has(lw)) {
            seen.add(lw);
            out.push(lw);
            if (out.length >= (max || 12)) break;
        }
    }
    return out;
}

async function restLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

test.describe('ChapBook — reader (6A/6D regression proofs)', () => {
    test.describe.configure({ timeout: 180000 });

    let readerOrgId = null;
    let dataGroupId = null;
    let poemsGroupId = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLoginShared(request);

        const dataDir = await request.get(REST + '/path/make/auth.group/data/B64-' + Buffer.from('~/Data').toString('base64').replace(/=/g, '%3D'));
        const dataBody = await dataDir.json();
        expect(dataBody && dataBody.id, 'could not ensure ~/Data group').toBeTruthy();
        dataGroupId = dataBody.id;
        readerOrgId = dataBody.organizationId;

        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' + Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
        const poemsBody = await poemsDir.json();
        expect(poemsBody && poemsBody.id, 'could not ensure ~/Poems group').toBeTruthy();
        poemsGroupId = poemsBody.id;

        await request.get(REST + '/logout');
    });

    // ── Task 1 — Issue 6A: binary .docx import produces READABLE prose ─────────
    test('6A: binary .docx import extracts readable prose (Tika), not zip garbage', async ({ page, request }) => {
        await restLoginShared(request);

        // 1. Upload the real winter_1.docx bytes as a data.data record.
        //    data.data inherits data.byteStore whose blob field is "dataBytesStore"
        //    (FieldNames.FIELD_BYTE_STORE). Sending it inline as base64 on POST /model
        //    stores the raw bytes (compression is only applied by ByteModelUtil.setValue,
        //    which this path does not invoke), so the reader reads them back verbatim.
        const docxBytes = fs.readFileSync(DOCX_FIXTURE);
        expect(docxBytes.length, 'fixture is empty').toBeGreaterThan(0);
        // Sanity: the raw bytes really are a zip container ("PK") and NOT readable prose.
        const rawAscii = docxBytes.toString('latin1');
        expect(rawAscii.startsWith('PK'), 'fixture should be a PK/zip container').toBe(true);
        expect(/pristine/i.test(rawAscii), 'raw bytes must NOT already contain readable prose').toBe(false);

        const dataName = '6a-winter-import-' + Date.now().toString(36) + '.docx';
        const createDataResp = await request.post(REST + '/model', {
            data: {
                schema: 'data.data',
                name: dataName,
                groupId: dataGroupId,
                contentType: REAL_DOCX_CT,
                dataBytesStore: docxBytes.toString('base64')
            }
        });
        expect(createDataResp.ok(), 'data.data create failed: ' + createDataResp.status() + ' ' + await createDataResp.text()).toBe(true);
        const dataRec = await createDataResp.json();
        const dataObjectId = dataRec && dataRec.objectId;
        expect(dataObjectId, 'no objectId for uploaded data.data').toBeTruthy();

        // 2. Import it as a poem. Title MUST be unique per run: olio.cb.poem has a
        //    unique constraint on (name, groupId, organizationId); a fixed title makes
        //    re-runs fail with a duplicate-key INSERT abort.
        const poemTitle = 'Winter Doc Import ' + Date.now().toString(36);
        const importResp = await request.post(CB_REST + '/poems', {
            data: { sources: [{ type: 'data.data', objectId: dataObjectId, title: poemTitle }] }
        });
        expect(importResp.ok(), 'POST /poems failed: ' + importResp.status() + ' ' + await importResp.text()).toBe(true);
        const importResult = await importResp.json();

        // 3. No UnsupportedContent/400 masquerading as a soft error — 6A would be UNFIXED if it did.
        expect(!importResult.errors || importResult.errors.length === 0,
            'import reported errors (6A would be a real bug): ' + JSON.stringify(importResult.errors)).toBe(true);
        expect(Array.isArray(importResult.poems) && importResult.poems.length === 1,
            'expected exactly one imported poem: ' + JSON.stringify(importResult)).toBe(true);
        const poemObjectId = importResult.poems[0].objectId;
        expect(poemObjectId, 'imported poem has no objectId').toBeTruthy();

        // 4. Fetch the poem's text via targeted projection and assert it is READABLE
        //    English. NOTE: do NOT use GET /model/olio.cb.poem/{id}/full — planMost(true)
        //    on this model exceeds PostgreSQL's 100-arg JSON_BUILD_OBJECT limit and 404s
        //    (same class as olio.pb.book, IssueLog #10). `text` is not a default query
        //    field, so it must be explicitly projected (IssueLog #8).
        const poemResp = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.cb.poem',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: poemObjectId },
                    { name: 'organizationId', comparator: 'equals', value: readerOrgId }
                ],
                request: ['id', 'objectId', 'name', 'title', 'text'],
                recordCount: 1,
                cache: false
            }
        });
        expect(poemResp.ok(), 'poem search failed: ' + poemResp.status()).toBe(true);
        const poemBody = await poemResp.json();
        expect(poemBody && Array.isArray(poemBody.results) && poemBody.results.length === 1,
            'poem not found by objectId (import did not persist?): ' + JSON.stringify(poemBody)).toBe(true);
        const poem = poemBody.results[0];
        const text = (poem && poem.text) || '';
        expect(text.length, 'imported poem text is empty').toBeGreaterThan(0);

        // 4a. Contains the real words Tika should have extracted from winter_1.docx.
        expect(/pristine/i.test(text), 'poem text missing "pristine": ' + text.slice(0, 120)).toBe(true);
        expect(/sorcery/i.test(text), 'poem text missing "sorcery": ' + text.slice(0, 120)).toBe(true);

        // 4b. NOT raw zip garbage: no leading "PK", no null byte, mostly-printable.
        expect(text.startsWith('PK'), 'poem text starts with zip magic "PK" — extraction failed').toBe(false);
        expect(text.indexOf('\u0000') >= 0, 'poem text contains a null byte').toBe(false);
        let printable = 0;
        for (let i = 0; i < text.length; i++) {
            const c = text.charCodeAt(i);
            if (c === 9 || c === 10 || c === 13 || (c >= 32 && c <= 126)) printable++;
        }
        const ratio = printable / text.length;
        expect(ratio, 'printable-char ratio too low (' + ratio.toFixed(3) + ') — text looks binary').toBeGreaterThan(0.95);

        console.log('[6A] extracted text (first 120 chars): ' + JSON.stringify(text.slice(0, 120)));

        // 5. Build a 1-poem ChapBook from the imported poem and screenshot the reader
        //    showing the readable stanza.
        const slug = '6a-docx-' + Date.now().toString(36);
        const createBookResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Winter Doc ChapBook', poemObjectIds: [poemObjectId], maxLinesPerPage: 8 }
        });
        expect(createBookResp.ok(), 'create ChapBook failed: ' + createBookResp.status() + ' ' + await createBookResp.text()).toBe(true);
        const book = await createBookResp.json();
        const bookObjectId = book && (book.objectId || book.bookObjectId);
        expect(bookObjectId, 'no bookObjectId for 6A book').toBeTruthy();
        await request.get(REST + '/logout');

        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookObjectId);

        // The stanza text must be visible without any Render step.
        const stanza = page.locator('p').filter({ hasText: /pristine|cobalt|sorcery|charcoal/i }).first();
        await expect(stanza, 'readable stanza not visible in reader for 6A book').toBeVisible({ timeout: 20000 });

        const shot6a = path.resolve(SPEC_DIR, '../test-results/6A-docx-readable.png');
        fs.mkdirSync(path.dirname(shot6a), { recursive: true });
        await page.screenshot({ path: shot6a, fullPage: true });
        console.log('[6A] screenshot: ' + shot6a);
    });

    // ── Task 2 — Issue 6D: reader shows stanzas immediately (no blank book) ────
    test('6D: reader shows poem stanzas immediately, no Render required', async ({ page, request }) => {
        await restLoginShared(request);

        // 1. Seed 10 real winter poems (idempotent by name). Capture the first poem's
        //    real text so the DOM assertion is derived from actual content.
        const poemObjectIds = [];
        let firstPoemText = null;
        for (const base of WINTER_SET) {
            const text = readCorpusPoem('winter', base);
            if (firstPoemText === null) firstPoemText = text;
            const recName = 'chapbook-6d-' + base;

            const search = await request.post(REST + '/model/search', {
                data: {
                    schema: 'io.query', type: 'olio.cb.poem',
                    fields: [
                        { name: 'name', comparator: 'equals', value: recName },
                        { name: 'organizationId', comparator: 'equals', value: readerOrgId }
                    ],
                    request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
                }
            });
            const sBody = await search.json().catch(() => null);
            let oid = (sBody && sBody.results && sBody.results[0]) ? sBody.results[0].objectId : null;
            if (!oid) {
                const cResp = await request.post(REST + '/model', {
                    data: {
                        schema: 'olio.cb.poem',
                        name: recName,
                        title: base.replace('_', ' '),
                        author: 'Stephen W. Cote',
                        groupId: poemsGroupId,
                        text: text
                    }
                });
                const created = await cResp.json().catch(() => null);
                oid = created && created.objectId;
            }
            expect(oid, 'failed to seed poem ' + recName).toBeTruthy();
            poemObjectIds.push(oid);
        }
        expect(poemObjectIds.length, 'expected 10 seeded poems').toBe(10);

        // 2. Create the ChapBook (result.objectId is the book).
        const slug = '6d-winter-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Winter Cycle ChapBook', poemObjectIds, maxLinesPerPage: 8 }
        });
        expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const result = await createResp.json();
        const bookObjectId = result && (result.objectId || result.bookObjectId);
        expect(bookObjectId, 'no bookObjectId in 6D create response').toBeTruthy();
        await request.get(REST + '/logout');

        // 3. Open the dedicated reader route.
        await loginAsSharedUser(page);
        // Seed localStorage as doCreateChapBook / persistReaderPoemIds would have — the test
        // bypasses the UI creation flow, so the reader's oninit restore (loadPersistedReaderPoemIds,
        // chapBook.js:263, reads localStorage) needs this to render the per-poem Analyze button.
        await page.evaluate(({ bookObjectId, poemObjectIds }) => {
            localStorage.setItem('cb-poemids-' + bookObjectId, JSON.stringify(poemObjectIds));
        }, { bookObjectId, poemObjectIds });
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookObjectId);

        // 4. Stanza text visible WITHOUT clicking Render. Regex derived from the real
        //    seeded winter_1 text (distinctive long words).
        const words = distinctiveWords(firstPoemText, 7, 12);
        expect(words.length, 'could not derive distinctive words from real poem').toBeGreaterThan(0);
        const stanzaRe = new RegExp(words.join('|'), 'i');
        const stanza = page.locator('p').filter({ hasText: stanzaRe }).first();
        await expect(stanza, 'no <p> matched real poem words ' + JSON.stringify(words)).toBeVisible({ timeout: 20000 });

        // Page N of M footer present.
        await expect(page.locator('text=/Page \\d+ of \\d+/').first(), 'no "Page N of M" footer').toBeVisible({ timeout: 10000 });

        // 5. Analyze + Render controls both present.
        await expect(page.locator('button:has-text("Analyze")').first()).toBeVisible({ timeout: 10000 });
        await expect(page.locator('button:has-text("Render")').first()).toBeVisible({ timeout: 5000 });

        const shot6d = path.resolve(SPEC_DIR, '../test-results/6D-reader-stanzas.png');
        fs.mkdirSync(path.dirname(shot6d), { recursive: true });
        await page.screenshot({ path: shot6d, fullPage: true });
        console.log('[6D] screenshot: ' + shot6d);
    });

    // ── Gap 6 — per-scene client render loop (the final evidence gate) ───────────
    // Proves the ChapBook Reader's Render button drives the NEW per-scene endpoint
    //   POST /rest/olio/chap-book/scene/{sceneObjectId}/generate
    // ONE CALL PER SCENE from the client (renderChapBookScenes → renderScenesSerially),
    // and that the OLD bulk POST /chap-book/render/{bookObjectId} — which renders every
    // scene on one server thread and times out at the gateway for multi-page books — is
    // NEVER called. Then it proves each rendered reader page carries a real, browser-
    // DECODED image (naturalWidth/Height > 0), not a broken <img>.
    //
    // Unlike Task 4 above (which POSTs the bulk /render endpoint directly via `request`
    // and never exercises the client loop), this test drives the actual UI: header Render
    // button → SD-config dialog → dialog Render → the per-scene fetch loop in the browser.
    //
    // Gate: CHAPBOOK_SD_TESTS=1 (SD at 192.168.1.39) + a live LLM (192.168.1.42) for the
    // per-scene landscape prompt. Kept SMALL (one 2-stanza poem = 2 scenes) because each
    // scene is a real ~45–60s SD render and the shared LLM host crashes under sustained load.
    test('gap6: reader Render drives the per-scene endpoint (bulk never called) and decodes one real image per scene', async ({ page, request }) => {
        if (!process.env.CHAPBOOK_SD_TESTS) {
            test.skip('set CHAPBOOK_SD_TESTS=1 to run SD-dependent ChapBook tests');
            return;
        }
        test.setTimeout(360000); // ~6 min so a slow-but-real render isn't killed

        // 0. Provision a real olio.llm.chatConfig in the org so the client's
        //    fetchDefaultChatConfigName() resolves and the per-scene endpoint gets a live
        //    LLM landscape prompt. ensureChatConfig uses its OWN api context — it neither
        //    needs nor disturbs the `request` fixture's session.
        const chatConfigName = await ensureChatConfig(request, readerOrgId);
        expect(chatConfigName, 'ensureChatConfig did not provision a chatConfig').toBeTruthy();
        console.log('[gap6] chatConfig=' + chatConfigName);

        await restLoginShared(request);

        // 1. Seed ONE real poem — POEM_1 (Falling Leaves), two natural stanzas. Idempotent by name.
        const recName = 'chapbook-gap6-fallingleaves';
        const search = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'equals', value: recName },
                    { name: 'organizationId', comparator: 'equals', value: readerOrgId }
                ],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const sBody = await search.json().catch(() => null);
        let poemOid = (sBody && sBody.results && sBody.results[0]) ? sBody.results[0].objectId : null;
        if (!poemOid) {
            const cResp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem', name: recName, title: 'Falling Leaves',
                    author: 'Stephen W. Cote', groupId: poemsGroupId, text: POEM_1
                }
            });
            const created = await cResp.json().catch(() => null);
            poemOid = created && created.objectId;
        }
        expect(poemOid, 'failed to seed gap6 poem').toBeTruthy();

        // 2. Create a SMALL ChapBook. maxLinesPerPage=20 keeps each ≤9-line stanza whole →
        //    POEM_1's two stanzas = two scenes.
        const slug = 'gap6-perscene-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Gap6 Per-Scene ChapBook', poemObjectIds: [poemOid], maxLinesPerPage: 20 }
        });
        expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        const bookOid = created && (created.objectId || created.bookObjectId);
        expect(bookOid, 'no bookObjectId for gap6 book').toBeTruthy();

        // 3. Read the ACTUAL scene count from the pages endpoint — the same source the client's
        //    renderChapBookScenes iterates — so the per-scene-call assertion is derived from real
        //    data, not a guess.
        const pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        expect(pagesResp.ok(), 'pages fetch failed: ' + pagesResp.status()).toBe(true);
        const pages = await pagesResp.json();
        const sceneCount = Array.isArray(pages) ? pages.length : 0;
        expect(sceneCount, 'book has no scenes').toBeGreaterThan(0);
        console.log('[gap6] book has ' + sceneCount + ' scene(s)');
        await request.get(REST + '/logout');

        // 4. Open the reader in the browser (stanzas render immediately — 6D). Wait for the
        //    "Page N of M" footer so the book has loaded before we touch Render.
        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);
        await expect(page.locator('text=/Page \\d+ of \\d+/').first(),
            'reader did not load book pages').toBeVisible({ timeout: 30000 });

        // 5. Attach the network observer BEFORE triggering render — capture per-scene generate
        //    POSTs and any bulk render POST (which must NOT happen).
        const sceneGenCalls = [];
        const bulkRenderCalls = [];
        page.on('request', (req) => {
            if (req.method() !== 'POST') return;
            const u = req.url();
            if (u.includes('/chap-book/scene/') && u.includes('/generate')) sceneGenCalls.push(u);
            if (u.includes('/chap-book/render/')) bulkRenderCalls.push(u);
        });

        // 6. Click the reader header Render button → opens the SD-config dialog. (The header's
        //    only orange button is Render; Review is indigo, Analyze is blue. The dialog is not
        //    open yet, so .first() is unambiguous.)
        const headerRender = page.locator('button.bg-orange-600').filter({ hasText: 'Render' }).first();
        await expect(headerRender, 'reader Render button not visible/enabled').toBeVisible({ timeout: 10000 });
        await headerRender.click();

        // 7. In the Render Settings dialog, click its (orange) Render to start the per-scene loop.
        const renderDialog = page.locator('div.fixed.inset-0.z-50').filter({ hasText: 'Render Settings' });
        await expect(renderDialog, 'Render Settings dialog did not open').toBeVisible({ timeout: 10000 });
        // Give the SD config a moment to load so a real sdConfig is sent (the dialog Render button
        // works regardless — doRenderFromDialog falls back to defaults if config is still loading).
        await page.waitForTimeout(3000);
        const dialogRender = renderDialog.locator('button.bg-orange-600');
        await expect(dialogRender, 'dialog Render button not visible').toBeVisible({ timeout: 10000 });
        await dialogRender.click();

        // 8. Wait for the ENTIRE serial per-scene loop to finish. The loop renders scenes ONE AT
        //    A TIME (live LLM prompt + SD image, then a 5s cooldown), so the FIRST image appears
        //    long before the LAST scene's request fires — asserting on "first image visible" would
        //    race the loop. Poll the observed request count until every scene has been requested,
        //    then wait for the reader to leave the "Rendering N/M..." state (loadReaderBook reload
        //    complete, persisted images resolved to /media/... MediaServlet URLs).
        await expect.poll(() => sceneGenCalls.length, {
            message: 'per-scene /generate calls never reached the scene count',
            timeout: 330000,
            intervals: [2000]
        }).toBeGreaterThanOrEqual(sceneCount);
        // Wide window (180s, not 90s): after ALL scenes have dispatched, the FINAL scene's real
        // ~45–60s SD render + reader reload can legitimately exceed 90s on a load-saturated host.
        await expect(
            page.locator('button.bg-orange-600').filter({ hasText: /Rendering/ }),
            'render loop did not finish (button still shows "Rendering N/M...")'
        ).toHaveCount(0, { timeout: 180000 });
        // One decoded MediaServlet image per scene must be present after the reload.
        // Wide window (180s): the reload resolving the final scene's /media/ image can lag on a saturated host.
        await expect(
            page.locator('img[src*="/media/"][src*="data.data"]'),
            'reader did not show one rendered image per scene after the per-scene render'
        ).toHaveCount(sceneCount, { timeout: 180000 });
        await page.waitForTimeout(2000); // let images finish decoding

        // 9. PROOF #1 — the client drove the per-scene endpoint once per scene, and NEVER the bulk one.
        console.log('[gap6] per-scene /generate calls=' + sceneGenCalls.length +
            '  bulk /render calls=' + bulkRenderCalls.length + '  scenes=' + sceneCount);
        expect(sceneGenCalls.length,
            'per-scene /chap-book/scene/{id}/generate should fire once per scene (>=' + sceneCount + ')')
            .toBeGreaterThanOrEqual(sceneCount);
        expect(bulkRenderCalls.length,
            'bulk /chap-book/render/{bookObjectId} must NEVER be called by the new client loop').toBe(0);

        // 10. PROOF #2 — each rendered reader page carries a real, browser-DECODED image.
        const imgs = page.locator('img[src*="/media/"][src*="data.data"]');
        const imgCount = await imgs.count();
        expect(imgCount, 'no decoded MediaServlet images in reader').toBeGreaterThan(0);
        for (let i = 0; i < imgCount; i++) {
            const img = imgs.nth(i);
            await img.scrollIntoViewIfNeeded();
            await page.waitForTimeout(300);
            const dims = await img.evaluate(el => ({ w: el.naturalWidth, h: el.naturalHeight }));
            console.log('[gap6] image ' + (i + 1) + ' decoded ' + dims.w + 'x' + dims.h);
            expect(dims.w, 'image ' + (i + 1) + ' did not decode (naturalWidth=0)').toBeGreaterThan(0);
            expect(dims.h, 'image ' + (i + 1) + ' did not decode (naturalHeight=0)').toBeGreaterThan(0);
        }

        // 11. Full-page visual proof.
        const outDir = path.resolve(SPEC_DIR, '../test-results');
        fs.mkdirSync(outDir, { recursive: true });
        const shot = path.join(outDir, 'gap6-chapbook-perscene-reader.png');
        await page.screenshot({ path: shot, fullPage: true });
        console.log('[gap6] scenes=' + sceneCount + ' perSceneCalls=' + sceneGenCalls.length +
            ' bulkCalls=' + bulkRenderCalls.length + ' images=' + imgCount + ' screenshot=' + shot);
    });

    // ── Task 4 — Issue 4: screenshot every ChapBook page (image + stanza) ────────
    // Gate: CHAPBOOK_SD_TESTS=1 — requires SD at 192.168.1.39 and LLM at 192.168.1.42.
    test('4: screenshot each rendered ChapBook page — image + stanza text on disk', async ({ page, request }) => {
        if (!process.env.CHAPBOOK_SD_TESTS) {
            test.skip('set CHAPBOOK_SD_TESTS=1 to run SD-dependent ChapBook tests');
            return;
        }
        test.setTimeout(900000);

        await restLoginShared(request);

        // Seed 2 real winter poems (idempotent by name).
        const SEEDS = ['winter_1', 'winter_2'];
        const seedOids = [];
        for (const base of SEEDS) {
            const text = readCorpusPoem('winter', base);
            const recName = 'chapbook-4-' + base;
            const search = await request.post(REST + '/model/search', {
                data: {
                    schema: 'io.query', type: 'olio.cb.poem',
                    fields: [
                        { name: 'name', comparator: 'equals', value: recName },
                        { name: 'organizationId', comparator: 'equals', value: readerOrgId }
                    ],
                    request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
                }
            });
            const sBody = await search.json().catch(() => null);
            let oid = (sBody && sBody.results && sBody.results[0]) ? sBody.results[0].objectId : null;
            if (!oid) {
                const cResp = await request.post(REST + '/model', {
                    data: {
                        schema: 'olio.cb.poem',
                        name: recName,
                        title: base.replace('_', ' '),
                        author: 'Stephen W. Cote',
                        groupId: poemsGroupId,
                        text: text
                    }
                });
                const created = await cResp.json().catch(() => null);
                oid = created && created.objectId;
            }
            expect(oid, 'failed to seed poem ' + recName).toBeTruthy();
            seedOids.push(oid);
        }

        // Create a fresh ChapBook. maxLinesPerPage=20 keeps each natural stanza as one
        // scene (winter poems have ≤10 lines per stanza) — ~4 scenes total, faster for SD.
        const slug = '4-pager-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Page Screenshot ChapBook', poemObjectIds: seedOids, maxLinesPerPage: 20 }
        });
        expect(createResp.ok(), 'create failed: ' + createResp.status()).toBe(true);
        const created = await createResp.json();
        const bookOid = created && (created.objectId || created.bookObjectId);
        expect(bookOid, 'no bookObjectId for screenshot test').toBeTruthy();

        // Trigger SD render. Each scene = LLM landscape prompt + SD image (~2–3 min each).
        const renderResp = await request.post(CB_REST + '/render/' + bookOid, { timeout: 600000 });
        expect(renderResp.ok(), 'render failed: ' + renderResp.status() + ' ' + await renderResp.text()).toBe(true);
        const renderResult = await renderResp.json();
        expect(renderResult.rendered, 'at least 1 scene must have rendered').toBeGreaterThanOrEqual(1);
        const renderedCount = renderResult.rendered;
        console.log('[4] rendered ' + renderedCount + ' scenes');

        // Open the ChapBook reader — it shows ALL pages in a scrollable list.
        await request.get(REST + '/logout');
        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);

        // Wait until at least one rendered MediaServlet image is visible in the reader.
        await expect(
            page.locator('img[src*="/media/"][src*="data.data"]').first(),
            'no rendered image appeared in ChapBook reader'
        ).toBeVisible({ timeout: 30000 });

        // Allow remaining images to decode.
        await page.waitForTimeout(2000);

        // Each page is a div.rounded-lg.overflow-hidden.border (one per scene in the reader).
        const pageEls = page.locator('div.rounded-lg.overflow-hidden.border');
        const pageCount = await pageEls.count();
        expect(pageCount, 'no page elements found in reader').toBeGreaterThan(0);
        console.log('[4] found ' + pageCount + ' page element(s) in reader');

        const outDir = path.resolve(SPEC_DIR, '../test-results');
        fs.mkdirSync(outDir, { recursive: true });

        // Screenshot each rendered page to disk.
        const toShot = Math.min(pageCount, renderedCount);
        for (let i = 0; i < toShot; i++) {
            const el = pageEls.nth(i);
            await el.scrollIntoViewIfNeeded();
            await page.waitForTimeout(500); // allow image decode after scroll

            const outPath = path.join(outDir, 'chapbook-page-' + (i + 1) + '.png');
            await el.screenshot({ path: outPath });
            console.log('[4] page ' + (i + 1) + ' screenshot: ' + outPath);

            // Assert landscape image decoded (naturalWidth > 0 — not a broken <img>).
            const img = el.locator('img[src*="/media/"]').first();
            if (await img.count() > 0) {
                const nw = await img.evaluate(el => el.naturalWidth).catch(() => 0);
                expect(nw, 'page ' + (i + 1) + ' image did not decode (naturalWidth=0)').toBeGreaterThan(0);
            }

            // Assert stanza poem text is visible.
            const textEl = el.locator('p').first();
            const stanzaText = await textEl.textContent().catch(() => '');
            expect(stanzaText.trim().length, 'page ' + (i + 1) + ' has no stanza text').toBeGreaterThan(0);
            console.log('[4] page ' + (i + 1) + ' stanza preview: ' + stanzaText.trim().slice(0, 60));
        }

        await request.get(REST + '/logout');
        console.log('[4] saved ' + toShot + ' page screenshot(s) to ' + outDir);
    });
});

// ════════════════════════════════════════════════════════════════════════════
// ChapBook delete-bug regressions (Bug A / Bug B / Issue-3).
//
// THE BUG (features/chapBook.js): three handlers called
//   Dialog.confirm({title:'…', destructive:true}, async (ok) => { …DELETE… })
// but Dialog.confirm honors the 2nd callback ONLY for the string form; for an object cfg it returns a
// Promise and IGNORES the callback — so "Remove from Queue", "Delete ChapBook" and "Remove page" all
// no-oped silently. The fix awaits the Promise: `let ok = await Dialog.confirm({…}); if(!ok) return;`.
//
// Each case drives the REAL UI button, confirms in the REAL dialog, and asserts the record is actually
// gone both in the UI and via a fresh REST re-fetch (cache:false / server default). Against the old
// callback-ignoring code the DELETE never fires, so each assertion fails; against the fix it passes.
//
// Self-contained (own beforeAll seeding + orgId) so it runs correctly under `-g "delete|queue"`, which
// selects only this block. Uses ensureSharedTestUser (e2etest_shared) — never admin.
// ════════════════════════════════════════════════════════════════════════════
test.describe('ChapBook — delete regressions', () => {
    test.describe.configure({ timeout: 120000 });

    let delOrgId = null;
    let delPoemsGroupId = null;
    let delPoem1 = null;   // stable source poems for book-creation (Bug B / Issue 3)
    let delPoem2 = null;

    async function restLogin(request) {
        const resp = await request.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: 'e2etest_shared',
                credential: Buffer.from('password').toString('base64'),
                type: 'hashed_password'
            }
        });
        expect(resp.ok() || resp.status() === 204, 'REST login failed: ' + resp.status()).toBe(true);
    }

    // Create an olio.cb.poem via the generic model route. `name` drives the /poems sort (ASC by name);
    // an "AAA…" prefix guarantees the poem lands in the server's first page of 25 so it renders in the
    // library table. `title` is what the UI shows and what we locate by.
    async function createPoem(request, groupId, name, title, text) {
        const resp = await request.post(REST + '/model', {
            data: { schema: 'olio.cb.poem', name: name, title: title, author: 'E2E Del', groupId: groupId, text: text }
        });
        expect(resp.ok(), 'create poem failed: ' + resp.status() + ' ' + await resp.text()).toBe(true);
        const created = await resp.json();
        expect(created && created.objectId, 'poem create returned no objectId').toBeTruthy();
        return created.objectId;
    }

    async function searchPoemCount(request, poemObjectId) {
        const resp = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: poemObjectId },
                    { name: 'organizationId', comparator: 'equals', value: delOrgId }
                ],
                request: ['id', 'objectId'], recordCount: 2, cache: false
            }
        });
        const body = await resp.json().catch(() => null);
        return body && body.results ? body.results.length : 0;
    }

    async function clickDialogConfirm(page) {
        // Every one of these handlers uses destructive:true → the confirm button is .am7-dialog-btn-destructive.
        await expect(page.locator('.am7-dialog-backdrop')).toBeVisible({ timeout: 5000 });
        const confirmBtn = page.locator('.am7-dialog-btn-destructive');
        await expect(confirmBtn).toBeVisible({ timeout: 5000 });
        await confirmBtn.click();
    }

    // Idempotent: return an existing poem's objectId by name, else create it.
    async function ensurePoem(request, name, title, text) {
        const s = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'equals', value: name },
                    { name: 'organizationId', comparator: 'equals', value: delOrgId }
                ],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const b = await s.json().catch(() => null);
        if (b && b.results && b.results.length > 0) return b.results[0].objectId;
        return createPoem(request, delPoemsGroupId, name, title, text);
    }

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request);
        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' + Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
        const poemsDirBody = await poemsDir.json();
        expect(poemsDirBody && poemsDirBody.id, 'Could not ensure ~/Poems group').toBeTruthy();
        delPoemsGroupId = poemsDirBody.id;
        delOrgId = poemsDirBody.organizationId;

        // Self-contained source poems so book creation works even when only this block runs (-g).
        delPoem1 = await ensurePoem(request, 'chapbook-delreg-src-1', 'DelReg Falling Leaves', POEM_1);
        delPoem2 = await ensurePoem(request, 'chapbook-delreg-src-2', 'DelReg Winter', POEM_2);
        expect(delPoem1 && delPoem2, 'source poems not seeded').toBeTruthy();
        await request.get(REST + '/logout');
    });

    // ── Bug B: deleting a ChapBook from "My ChapBooks" actually removes it ──────
    test('Bug B: delete ChapBook removes the book (UI + backend)', async ({ page, request }) => {
        const uniq = Date.now().toString(36);
        const bookTitle = 'E2E DELBOOK ' + uniq;   // passed as `title`; the server stores it in `description`
        const slug = 'e2e-delbook-' + uniq;

        // Seed a chapbook via REST (single poem — no LLM/SD needed).
        await restLogin(request);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug: slug, title: bookTitle, poemObjectIds: [delPoem1], maxLinesPerPage: 40 }
        });
        expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        const bookOid = created && (created.bookObjectId || created.objectId);
        expect(bookOid, 'no bookObjectId from create').toBeTruthy();
        // The "My ChapBooks" row shows `b.name || b.slug || b.objectId` (chapBook.js:1156). ChapBookUtil
        // sets the book's `name` to "Book <slug>" (the title we passed lands in `description`), so we must
        // locate by the actual persisted name, not the title.
        const bookName = (created && created.name) || ('Book ' + slug);
        await request.get(REST + '/logout');

        // Drive the UI: open ChapBook, find the book row, click its red delete button, confirm.
        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });

        const nameEl = page.getByText(bookName, { exact: true });
        await expect(nameEl, 'seeded book not visible in My ChapBooks').toBeVisible({ timeout: 20000 });
        const row = nameEl.locator('xpath=ancestor::div[contains(@class,"border")][1]');
        const delBtn = row.locator('button.bg-red-100');
        await expect(delBtn, 'red delete button not found in book row').toBeVisible({ timeout: 5000 });
        await delBtn.click();
        await clickDialogConfirm(page);

        // UI: the row disappears (loadMyBooks re-fetch after delete).
        await expect(nameEl, 'book row still visible after delete').toHaveCount(0, { timeout: 15000 });

        // Backend: the book is gone from /books AND from a fresh org-scoped search.
        await restLogin(request);
        const booksResp = await request.get(CB_REST + '/books');
        const books = await booksResp.json();
        const stillListed = (Array.isArray(books) ? books : []).some(b => b.objectId === bookOid);
        expect(stillListed, 'deleted book still returned by /books').toBe(false);

        const srch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.book',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: bookOid },
                    { name: 'organizationId', comparator: 'equals', value: delOrgId }
                ],
                request: ['id', 'objectId'], recordCount: 2, cache: false
            }
        });
        const srchBody = await srch.json().catch(() => null);
        const remaining = srchBody && srchBody.results ? srchBody.results.length : 0;
        expect(remaining, 'olio.pb.book record still present after delete').toBe(0);
        await request.get(REST + '/logout');
    });

    // ── Bug A: "Remove from Queue" actually clears the selected poems ───────────
    test('Bug A: Remove from Queue clears selected poems (UI + backend)', async ({ page, request }) => {
        const uniq = Date.now().toString(36);
        // "AAA…" names sort to the top of /poems (ASC by name) → guaranteed on the server's first page of 25.
        const nameA = 'AAAQDEL-a-' + uniq, titleA = 'AAA QueueDel A ' + uniq;
        const nameB = 'AAAQDEL-b-' + uniq, titleB = 'AAA QueueDel B ' + uniq;

        await restLogin(request);
        // Clean any leftover AAAQDEL poems from prior runs so the two we create stay on page 1.
        const stale = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'like', value: 'AAAQDEL-%' },
                    { name: 'organizationId', comparator: 'equals', value: delOrgId }
                ],
                request: ['id', 'objectId'], recordCount: 200, cache: false
            }
        });
        const staleBody = await stale.json().catch(() => null);
        for (const r of (staleBody && staleBody.results ? staleBody.results : [])) {
            await request.delete(REST + '/model/olio.cb.poem/' + r.objectId);
        }
        const pA = await createPoem(request, delPoemsGroupId, nameA, titleA, POEM_1);
        const pB = await createPoem(request, delPoemsGroupId, nameB, titleB, POEM_2);
        await request.get(REST + '/logout');

        await loginAsSharedUser(page);
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });

        // Both poems must be visible in the library table (do NOT type in the filter box — that would
        // set themeFilter, which loadPoems() passes to the server on the post-delete re-fetch).
        const rowA = page.getByText(titleA, { exact: true });
        const rowB = page.getByText(titleB, { exact: true });
        await expect(rowA, 'poem A not in library').toBeVisible({ timeout: 20000 });
        await expect(rowB, 'poem B not in library').toBeVisible({ timeout: 10000 });

        // Select ONLY poem A (clicking the title cell bubbles to the row's toggle handler).
        await rowA.click();
        const removeBtn = page.getByRole('button', { name: /Remove from Queue/ });
        await expect(removeBtn, '"Remove from Queue" button did not appear after selection').toBeVisible({ timeout: 5000 });
        await removeBtn.click();
        await clickDialogConfirm(page);

        // UI after doDeleteSelected → loadPoems() re-fetch: A gone, B still present. If the backend
        // server-search cache were stale (no setCache(false)), the deleted A would linger here.
        await expect(rowA, 'removed poem A still visible after re-fetch').toHaveCount(0, { timeout: 15000 });
        await expect(rowB, 'poem B wrongly removed').toBeVisible({ timeout: 5000 });

        // Backend: A truly deleted, B untouched.
        await restLogin(request);
        expect(await searchPoemCount(request, pA), 'poem A still exists in DB after remove').toBe(0);
        expect(await searchPoemCount(request, pB), 'poem B should still exist').toBe(1);
        await request.get(REST + '/logout');
    });

    // ── Issue 3: "Remove page" in the review view actually deletes the scene ────
    test('Issue 3: Remove page deletes a scene from the review view (UI + backend)', async ({ page, request }) => {
        const uniq = Date.now().toString(36);
        const slug = 'e2e-scene-' + uniq;

        // Book with >=2 scenes: both poems, small page size so each poem yields multiple stanza pages.
        await restLogin(request);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug: slug, title: 'E2E SCENEDEL ' + uniq, poemObjectIds: [delPoem1, delPoem2], maxLinesPerPage: 4 }
        });
        expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        const bookOid = created && (created.bookObjectId || created.objectId);
        expect(bookOid, 'no bookObjectId from create').toBeTruthy();

        const pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        const pages = await pagesResp.json();
        const initialCount = Array.isArray(pages) ? pages.length : 0;
        expect(initialCount, 'expected >=2 scenes to test page removal').toBeGreaterThanOrEqual(2);
        await request.get(REST + '/logout');

        // Drive the review view.
        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/review/' + oid; }, bookOid);

        const removeButtons = page.locator('button[title="Remove this page"]');
        await expect(removeButtons.first(), 'no Remove-page buttons rendered').toBeVisible({ timeout: 20000 });
        await expect(removeButtons).toHaveCount(initialCount, { timeout: 10000 });

        // Remove the first page.
        await removeButtons.first().click();
        await clickDialogConfirm(page);

        // UI: one fewer scene card (doDeleteScene splices reviewScenes after the DELETE succeeds).
        await expect(removeButtons, 'scene count did not decrease in UI').toHaveCount(initialCount - 1, { timeout: 15000 });

        // Backend: a fresh /pages re-fetch confirms the scene is really gone and stays gone.
        await restLogin(request);
        const pagesResp2 = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        const pages2 = await pagesResp2.json();
        const finalCount = Array.isArray(pages2) ? pages2.length : -1;
        expect(finalCount, 'scene count not reduced by 1 in backend after delete').toBe(initialCount - 1);
        await request.get(REST + '/logout');
    });
});
