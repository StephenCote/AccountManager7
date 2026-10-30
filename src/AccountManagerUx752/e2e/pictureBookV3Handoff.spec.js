/**
 * PictureBook V3 hand-off (P7-3, design §30 row 7). Under picturebook.renderPath=v3 (the default) the
 * PB1 wizard ends at step 3 — "Continue to Images" hands off to the V3 reader (/picture-book/v2/{oid})
 * instead of opening the retired steps 4–5 — and every "open an existing book" path (the list-row Edit
 * pencil, the PB1 viewer route handed a book group) lands on the V3 reader too. The PB1 viewer route is
 * only a bridge: it redirects when /pb2 resolves, else shows the PB1 book read-only under the
 * "replaced by V3 — import" notice (P7-4's entry point) with no Edit pencil. Ux752 must not call the
 * three retired PB1 routes (POST /scene/{id}/generate, POST /{book}/prepare-images, PUT /{book}/settings)
 * anywhere on these paths — the server answers 409 for them since P7-2.
 *
 * Fixtures are built through the REST API as e2etest_shared (never admin), LLM/SD-free:
 *   A. a data.note source document the wizard is driven on. Manual scene entry — no chat config on the
 *      request and no character stubs — so PictureBookUtil.createFromScenes makes no LLM call.
 *   B. a "bridged" book: POST /chapter (olio.pb.book + world) then POST /{note}/create-from-scenes with
 *      pb2BookObjectId → a PB1 book group whose /pb2 resolves to its olio.pb.book.
 *   C. a "legacy" pure-PB1 book: ~/Data/PictureBooks/<name> + /Scenes + one scene note + a .pictureBookMeta
 *      note POSTed directly (createNote() returns ANY existing note of that name first, so it is not used
 *      for the meta), and NO olio.pb.book — its /pb2 404s.
 * Under v3 create-from-scenes also queues a background compose job (P4-4); the reset in afterAll can race
 * it — harness noise, logged, never asserted.
 *
 * The whole suite skips when the stack is on v1 (the v1 wizard path is pictureBookWizardUx.spec.js). Run
 * single-threaded against the Docker stack — 127.0.0.1, not localhost (IPv6 ::1 is not mapped):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookV3Handoff.spec.js \
 *       --workers=1 --project=chromium
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, deleteObject, pb1Retired } from './helpers/api.js';
import { screenshot } from './helpers/auth.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';
const V1_SKIP = 'picturebook.renderPath=v1: the V3 hand-off under test only exists under v3 (P7-3).';

// POST/PUT to a retired PB1 route — the thing Ux752 must never do under v3 (P7-2 answers 409).
const RETIRED_PB1_RE = /\/olio\/picture-book\/(scene\/[^/]+\/generate|[^/]+\/prepare-images|[^/]+\/settings)(\?|$)/;

const STORY = 'A lighthouse keeper rows out at dawn to free a gull tangled in a drifting net. '
    + 'The tide turns before the keeper is halfway back, and the gull rides home on the bow.';

let shared = null;
let v3 = true;

async function restLogin(ctx) {
    expect(shared, 'ensureSharedTestUser() must run before any login').toBeTruthy();
    const resp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
    const body = (await resp.text().catch(() => '')).trim();
    expect(body, 'login returned ' + resp.status() + ' but body was "' + body + '" (credential rejected)').not.toBe('false');
}

// WebSocket stub: Docker's nginx drops the session cookie on the WS upgrade, Tomcat closes the socket,
// and the app's reconnect path forces #!/sig. Installed before page.goto (troubleshooting.md).
function wsStubInit() {
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
}

async function loginAsSharedUser(page) {
    await restLogin(page.request);
    await page.addInitScript(wsStubInit);
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function gotoHash(page, hash) {
    await page.evaluate((h) => { window.location.hash = h; }, hash);
}

function currentHash(page) {
    return page.evaluate(() => window.location.hash);
}

// What the V3 reader header shows: the olio.pb.book `name`, which PbBookUtil.bookName() derives as
// "Book <slug>" (the typed title is the DTO's `title`). Same contract pictureBookNavigation.spec.js asserts.
function displayName(b) { return (b && (b.name || b.title || b.slug)) || 'Untitled'; }

async function findBook(request, oid) {
    const resp = await request.get(PB_REST + '/books');
    expect(resp.ok(), 'GET /books failed: ' + resp.status()).toBe(true);
    return (await resp.json()).find((b) => b.objectId === oid) || null;
}

// Reveal a PB2 book's list row (chapters live inside their collapsed series card).
async function revealBookRow(page, book) {
    const list = page.locator('[data-pb2-book-list]');
    await expect(list, 'PB2 book list did not render').toBeVisible({ timeout: 30000 });
    const row = list.locator('[data-pb2-book="' + book.objectId + '"]');
    if ((await row.count()) === 0 && book.seriesObjectId) {
        const card = list.locator(':scope > [data-pb2-series="' + book.seriesObjectId + '"]');
        await expect(card, 'series card for the book is missing').toBeVisible({ timeout: 15000 });
        await card.locator('[data-pb2-series-header]').click();
    }
    await expect(row, 'book row did not render').toBeVisible({ timeout: 15000 });
    return row;
}

// Record every POST/PUT the page issues to a retired PB1 route; the tests assert the list stays empty.
function trackRetiredPb1Calls(page) {
    const hits = [];
    page.on('request', (r) => {
        if ((r.method() === 'POST' || r.method() === 'PUT') && RETIRED_PB1_RE.test(r.url())) {
            hits.push(r.method() + ' ' + r.url());
        }
    });
    return hits;
}

function wizardDialog(page) {
    return page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Picture Book —' }) });
}

// A data.note POSTed directly (createNote() would hand back any existing note of the same name).
async function postNote(request, dir, name, text) {
    const resp = await request.post(REST + '/model', {
        data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name, text }
    });
    expect(resp.ok(), 'POST data.note "' + name + '" failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const rec = await resp.json();
    expect(rec && rec.objectId, 'data.note "' + name + '" returned no objectId').toBeTruthy();
    return rec;
}

async function resetBook(request, oid) {
    if (!oid) return;
    try {
        const r = await request.delete(PB_REST + '/' + oid + '/reset');
        console.log('[p73-cleanup] reset ' + oid + ' -> ' + r.status() + ' ' + (await r.text().catch(() => '')).slice(0, 160));
    } catch (e) { console.log('[p73-cleanup] reset ' + oid + ' threw ' + (e && e.message)); }
}

// ── Fixtures ───────────────────────────────────────────────────────────────────────────────────────
let tag = null;
let noteA = null;              // wizard source document (fixture A)
let noteB = null;              // source document of the bridged book (fixture B)
let bridgedPb2Oid = null;      // olio.pb.book objectId of the bridged book
let bridgedGroupOid = null;    // PB1 book group objectId of the bridged book (what /picture-book/:id gets)
let bridgedTitle = null;
let legacyGroup = null;        // pure PB1 book group (fixture C)
let legacyScenesGroup = null;
let legacySceneNote = null;
let legacyMetaNote = null;
let legacyName = null;
const LEGACY_SCENE_TITLE = 'The Keeper Rows Out';
let wizardPb2Oid = null;       // the book the wizard creates in test 1 — reset in afterAll

test.describe('PictureBook V3 hand-off (P7-3)', () => {
    test.describe.configure({ timeout: 240000 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        v3 = await pb1Retired(request, { userName: shared.testUserName, password: shared.testPassword });
        if (!v3) return;
        await restLogin(request);

        tag = Date.now().toString(36);
        const noteDir = await ensurePath(request, 'auth.group', 'data', '~/Notes');
        expect(noteDir && noteDir.id, '~/Notes could not be ensured').toBeTruthy();

        // A. the wizard's source document
        noteA = await postNote(request, noteDir, 'P7-3 wizard source ' + tag, STORY);

        // B. the bridged book: pb2 record first (as the wizard's step-2 Continue does), then the PB1
        //    group + scenes linked to it through create-from-scenes.
        const bridgedSlug = 'e2e-p73-bridged-' + tag;
        bridgedTitle = 'E2E P73 Bridged ' + tag;
        const ch = await request.post(PB_REST + '/chapter', { data: { slug: bridgedSlug, title: bridgedTitle } });
        expect(ch.ok(), 'POST /chapter failed: ' + ch.status() + ' ' + (await ch.text())).toBe(true);
        bridgedPb2Oid = (await ch.json()).bookObjectId;
        expect(bridgedPb2Oid, 'POST /chapter returned no bookObjectId').toBeTruthy();

        noteB = await postNote(request, noteDir, 'P7-3 bridged source ' + tag, STORY);
        const cfs = await request.post(PB_REST + '/' + noteB.objectId + '/create-from-scenes', {
            data: {
                schema: 'olio.pictureBookRequest',
                bookName: bridgedTitle,
                pb2BookObjectId: bridgedPb2Oid,
                sceneList: [{
                    index: 0, title: 'The Net at Dawn', blurb: 'The keeper rows toward a gull caught in a drifting net.',
                    setting: 'open water at dawn', action: 'rowing', mood: 'urgent', characters: []
                }]
            }
        });
        expect(cfs.ok(), 'POST /create-from-scenes failed: ' + cfs.status() + ' ' + (await cfs.text())).toBe(true);
        const meta = await cfs.json();
        bridgedGroupOid = meta.bookObjectId;
        expect(bridgedGroupOid, 'create-from-scenes returned no bookObjectId (PB1 group)').toBeTruthy();
        expect(meta.pb2BookObjectId, 'create-from-scenes did not stamp pb2BookObjectId').toBe(bridgedPb2Oid);
        console.log('[p73] bridged pb2=' + bridgedPb2Oid + ' group=' + bridgedGroupOid
            + ' composeJob=' + JSON.stringify(meta.composeJob || null)
            + ' composeJobSkipped=' + JSON.stringify(meta.composeJobSkipped || null)
            + ' composeJobError=' + JSON.stringify(meta.composeJobError || null));
        // Precondition the redirect test depends on: the PB1 group bridges to the pb2 book.
        const info = await request.get(PB_REST + '/' + bridgedGroupOid + '/pb2');
        expect(info.status(), 'GET /{bridgedGroup}/pb2 should be 200').toBe(200);
        expect((await info.json()).pb2BookObjectId).toBe(bridgedPb2Oid);

        // C. the legacy pure-PB1 book: the exact on-disk shape PictureBookUtil.listScenes reads
        //    (group → .pictureBookMeta note → scenes[] → per-scene note), with no olio.pb.book anywhere.
        legacyName = 'e2e-p73-legacy-' + tag;
        legacyGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName);
        expect(legacyGroup && legacyGroup.objectId, 'legacy book group could not be ensured').toBeTruthy();
        legacyScenesGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName + '/Scenes');
        expect(legacyScenesGroup && legacyScenesGroup.id, 'legacy Scenes group could not be ensured').toBeTruthy();
        legacySceneNote = await postNote(request, legacyScenesGroup, 'Scene 1', JSON.stringify({
            index: 0, title: LEGACY_SCENE_TITLE, blurb: 'The keeper pushes the skiff off the shingle.',
            setting: 'shingle beach', action: 'launching a skiff', mood: 'resolute', characters: []
        }));
        legacyMetaNote = await postNote(request, legacyGroup, '.pictureBookMeta', JSON.stringify({
            bookObjectId: legacyGroup.objectId, workName: legacyName, sceneCount: 1,
            scenes: [{
                objectId: legacySceneNote.objectId, index: 0, title: LEGACY_SCENE_TITLE,
                description: 'The keeper pushes the skiff off the shingle.',
                setting: 'shingle beach', action: 'launching a skiff', mood: 'resolute', characters: []
            }]
        }));
        // Preconditions the read-only test depends on: scenes resolve, /pb2 does not.
        const sc = await request.get(PB_REST + '/' + legacyGroup.objectId + '/scenes');
        expect(sc.status(), 'GET /{legacyGroup}/scenes should be 200').toBe(200);
        const scenes = await sc.json();
        expect(Array.isArray(scenes) && scenes.length === 1 && scenes[0].title === LEGACY_SCENE_TITLE,
            'legacy fixture did not read back as one scene: ' + JSON.stringify(scenes).slice(0, 300)).toBe(true);
        const noPb2 = await request.get(PB_REST + '/' + legacyGroup.objectId + '/pb2');
        expect(noPb2.status(), 'GET /{legacyGroup}/pb2 should be 404 for a pure PB1 book').toBe(404);
        console.log('[p73] legacy group=' + legacyGroup.objectId + ' noteA=' + noteA.objectId);
    });

    test.afterAll(async ({ request }) => {
        if (!v3) return;
        try {
            await restLogin(request);
            await resetBook(request, wizardPb2Oid);
            await resetBook(request, bridgedPb2Oid);
            // A pure PB1 book has no pb2 record: remove its notes and groups directly (a group delete
            // never cascades to its contents).
            for (const n of [legacyMetaNote, legacySceneNote, noteA, noteB]) {
                if (n && n.objectId) await deleteObject(request, 'data.note', n.objectId).catch(() => {});
            }
            for (const g of [legacyScenesGroup, legacyGroup]) {
                if (g && g.objectId) await deleteObject(request, 'auth.group', g.objectId).catch(() => {});
            }
            await apiLogout(request).catch(() => {});
        } catch (e) {
            console.log('[p73-cleanup] ' + (e && e.message));
        }
    });

    // 1. create → step 3 → reader. Manual scene entry on a fresh source document; the v3 progress bar
    //    has no Images/View steps; "Continue to Images" closes the wizard and opens the V3 reader on the
    //    book the step-2 Continue created; no retired PB1 route is called along the way.
    test('wizard: step 3 "Continue to Images" hands off to the V3 reader', async ({ page }) => {
        test.skip(!v3, V1_SKIP);
        const retired = trackRetiredPb1Calls(page);
        await loginAsSharedUser(page);

        // The viewer route on a bare source document renders the empty state whose "Generate Picture
        // Book" is the same wizard entry the document picker uses.
        await gotoHash(page, '!/picture-book/' + noteA.objectId);
        const generateBtn = page.locator('button:has-text("Generate Picture Book")');
        await expect(generateBtn).toBeVisible({ timeout: 30000 });
        await generateBtn.click();

        const dlg = wizardDialog(page).last();
        await expect(dlg, 'wizard dialog did not open').toBeVisible({ timeout: 30000 });
        // v3 progress bar: Source › Scenes › Characters — the retired Images/View steps are not offered.
        await expect(dlg.getByText('Characters', { exact: true })).toBeVisible();
        await expect(dlg.getByText('Images', { exact: true })).toHaveCount(0);
        await expect(dlg.getByText('View', { exact: true })).toHaveCount(0);
        await screenshot(page, 'p73-wizard-step1');

        const bookName = 'E2E P73 Wizard ' + tag;
        await dlg.locator('label:has-text("Picture Book Name") + input').fill(bookName);
        await dlg.locator('input[type="radio"][name="method"][value="manual"]').check();
        await dlg.locator('.am7-dialog-footer button').filter({ hasText: 'Continue' }).filter({ hasNotText: 'Images' }).click();

        // Step 2: one manual scene.
        await expect(dlg.locator('text=/Scene List \\(\\d+\\)/')).toBeVisible({ timeout: 10000 });
        await dlg.locator('button:has-text("Add Scene")').click();
        await dlg.locator('input[placeholder="Scene title"]').first().fill('The Gull in the Net');
        await dlg.locator('textarea[placeholder="Scene description/blurb"]').first().fill('The keeper leans over the gunwale and cuts the net away from the gull.');
        await screenshot(page, 'p73-wizard-step2');
        await dlg.locator('.am7-dialog-footer button').filter({ hasText: 'Continue' }).filter({ hasNotText: 'Images' }).click();

        // Step 3 (Characters) — reached once POST /chapter + create-from-scenes return. No LLM on this
        // path, but /chapter builds a world: give it room.
        const continueToImages = dlg.locator('.am7-dialog-footer button').filter({ hasText: 'Continue to Images' });
        await expect(continueToImages).toBeVisible({ timeout: 180000 });
        await screenshot(page, 'p73-wizard-step3');
        await continueToImages.click();

        // Hand-off: the wizard closes and the V3 reader opens on the new book; step 4 never renders.
        await expect.poll(() => currentHash(page), { timeout: 30000 }).toMatch(/^#!\/picture-book\/v2\/[^/]+$/);
        wizardPb2Oid = (await currentHash(page)).split('/').pop();
        // The book the reader opened is the one the wizard's step-2 Continue created from the typed
        // name: its slug is generateSlug(bookName) and the title is the name itself.
        const book = await findBook(page.request, wizardPb2Oid);
        expect(book, 'the book the reader opened (' + wizardPb2Oid + ') is not in GET /books').toBeTruthy();
        expect(book.slug).toBe('e2e-p73-wizard-' + tag);
        expect(book.title).toBe(bookName);
        await expect(page.locator('[data-pb2-book-name]')).toHaveText(displayName(book), { timeout: 30000 });
        await expect(wizardDialog(page)).toHaveCount(0);
        await expect(page.locator('text=Image Generation')).toHaveCount(0);
        await screenshot(page, 'p73-wizard-reader-handoff');
        expect(retired, 'Ux752 called a retired PB1 route under v3').toEqual([]);
        console.log('[p73] wizard book pb2=' + wizardPb2Oid);
    });

    // 2. resume: the list-row Edit pencil on an existing book opens the V3 reader, never the wizard.
    test('list-row Edit on an existing book opens the V3 reader, not the wizard', async ({ page }) => {
        test.skip(!v3, V1_SKIP);
        const retired = trackRetiredPb1Calls(page);
        await loginAsSharedUser(page);
        const book = await findBook(page.request, bridgedPb2Oid);
        expect(book, 'bridged book is not in GET /books').toBeTruthy();

        await gotoHash(page, '!/picture-book');
        const row = await revealBookRow(page, book);
        await row.locator('[data-pb2-edit="' + bridgedPb2Oid + '"]').click();

        await expect.poll(() => currentHash(page), { timeout: 30000 }).toBe('#!/picture-book/v2/' + bridgedPb2Oid);
        await expect(page.locator('[data-pb2-book-name]')).toHaveText(displayName(book), { timeout: 30000 });
        await expect(wizardDialog(page)).toHaveCount(0);
        await expect(page.locator('text=Image Generation')).toHaveCount(0);
        expect(retired, 'Ux752 called a retired PB1 route under v3').toEqual([]);
    });

    // 3. redirect: the PB1 viewer route on a book group that bridges to a pb2 book replaces itself with
    //    the V3 reader (history entry replaced, no legacy notice).
    test('PB1 viewer route redirects to the V3 reader when /pb2 resolves', async ({ page }) => {
        test.skip(!v3, V1_SKIP);
        const retired = trackRetiredPb1Calls(page);
        await loginAsSharedUser(page);
        const book = await findBook(page.request, bridgedPb2Oid);
        expect(book, 'bridged book is not in GET /books').toBeTruthy();
        expect(book.title).toBe(bridgedTitle);
        const pb2Lookup = page.waitForResponse((r) => r.url().includes('/picture-book/' + bridgedGroupOid + '/pb2'), { timeout: 30000 });
        await gotoHash(page, '!/picture-book/' + bridgedGroupOid);
        expect((await pb2Lookup).status(), '/pb2 bridge should answer 200 for the bridged group').toBe(200);

        await expect.poll(() => currentHash(page), { timeout: 30000 }).toBe('#!/picture-book/v2/' + bridgedPb2Oid);
        await expect(page.locator('[data-pb2-book-name]')).toHaveText(displayName(book), { timeout: 30000 });
        await expect(page.locator('[data-pb1-legacy-notice]')).toHaveCount(0);
        await expect(wizardDialog(page)).toHaveCount(0);
        // The redirect's {replace:true} (Back must not bounce through the bridge) is asserted in
        // src/test/pictureBookV3Handoff.test.js: it cannot be proven here, because driving navigation
        // through window.location.hash makes router.js's hashchange bridge re-push the same route
        // before Mithril resolves it, so a page.goBack() lands on that duplicate entry and redirects again.
        expect(retired, 'Ux752 called a retired PB1 route under v3').toEqual([]);
    });

    // 4. no V3 record: a pure PB1 book stays on the viewer route, read-only, under the "replaced by V3 —
    //    import" notice, with no Edit pencil (the wizard cannot take it anywhere).
    test('PB1 viewer shows a pure PB1 book read-only with the replaced-by-V3 notice', async ({ page }) => {
        test.skip(!v3, V1_SKIP);
        const retired = trackRetiredPb1Calls(page);
        await loginAsSharedUser(page);
        const pb2Lookup = page.waitForResponse((r) => r.url().includes('/picture-book/' + legacyGroup.objectId + '/pb2'), { timeout: 30000 });
        await gotoHash(page, '!/picture-book/' + legacyGroup.objectId);
        expect((await pb2Lookup).status(), '/pb2 bridge should answer 404 for a pure PB1 book').toBe(404);

        const notice = page.locator('[data-pb1-legacy-notice]');
        await expect(notice).toBeVisible({ timeout: 30000 });
        await expect(notice).toContainText('replaced by V3');
        expect(await currentHash(page)).toBe('#!/picture-book/' + legacyGroup.objectId);
        // The book itself is still readable: cover (group name, scene count) then the scene page.
        await expect(page.locator('h1', { hasText: legacyName })).toBeVisible({ timeout: 30000 });
        await expect(page.locator('text=1 Scene')).toBeVisible();
        await expect(page.locator('button[title="Edit Book"]')).toHaveCount(0);
        await screenshot(page, 'p73-pb1-viewer-legacy-notice');
        await page.locator('button:has-text("Begin")').click();
        await expect(page.locator('h2', { hasText: LEGACY_SCENE_TITLE })).toBeVisible({ timeout: 15000 });
        await expect(notice).toBeVisible();
        await expect(page.locator('button[title="Edit Book"]')).toHaveCount(0);
        await expect(wizardDialog(page)).toHaveCount(0);
        expect(retired, 'Ux752 called a retired PB1 route under v3').toEqual([]);
    });
});
