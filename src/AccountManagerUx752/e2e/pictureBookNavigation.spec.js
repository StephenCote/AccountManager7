/**
 * PictureBook (PB2) navigation / editing E2E — the list-row Edit + Characters actions, the reader
 * header's Edit + Characters, the honest "no workflow yet" canvas state with its scene list and its
 * single "Open in wizard to render" entry point, the canvas "← Book" landing on the PB2 reader with
 * the book name, same-route chapter navigation on the canvas (series tile) and in the reader
 * (chapter <select>), the character manager's "Open Full Editor" in both launch contexts
 * (standalone → navigate in place; from inside the wizard → new tab, wizard state kept), and the
 * inline character rename.
 *
 * Reads only, plus ONE reversible write (rename a character and rename it back). NEVER clicks
 * Generate / Render (SD). Uses ensureSharedTestUser (e2etest_shared / password) — never admin — and
 * picks its books dynamically from GET /books, never hardcoded ids. Run single-threaded:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookNavigation.spec.js \
 *       --workers=1 --project=chromium
 * (127.0.0.1 is mandatory — localhost resolves to IPv6 ::1 which Docker does not map.)
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';

function b64(str) { return Buffer.from(str).toString('base64'); }

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';

async function restLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: b64('password'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'login failed: ' + resp.status()).toBe(true);
    // LoginService answers 200 with a bare "false" body when the credential is rejected (e.g. the app
    // booted before Postgres was ready and IOSystem has no context) — a 200 alone is not a login.
    const body = (await resp.text().catch(() => '')).trim();
    expect(body, 'login returned ' + resp.status() + ' but body was "' + body + '" (credential rejected)').not.toBe('false');
}

// Stub the WebSocket so onclose never fires (Docker nginx strips the session cookie on the WS
// upgrade → Tomcat closes it → reconnect → forceLogin → #!/sig). See troubleshooting.md.
// Installed per page by loginAsSharedUser; a test that opens a popup installs it on the context too.
function wsStubInit() {
    window.WebSocket = class StubWS {
        constructor(url) {
            this.url = url; this.readyState = 0;
            this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
            this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
            setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
        }
        send() {}
        close() { this.readyState = 3; }
        addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
    };
    window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
    window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
}

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
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login failed: HTTP ' + resp.status());

    await page.addInitScript(wsStubInit);

    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

function displayName(b) { return (b && (b.name || b.title || b.slug)) || 'Untitled'; }

async function gotoHash(page, hash) {
    await page.evaluate((h) => { window.location.hash = h; }, hash);
}

// Reveal a PB2 book's list row: chapters live inside their (collapsed) series card.
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

async function closeTopDialog(page, buttonLabel) {
    const dlg = page.locator('.am7-dialog').last();
    await dlg.locator('.am7-dialog-footer button').filter({ hasText: buttonLabel }).first().click();
}

// ── Dynamic fixtures (chosen from the shared user's real books) ───────────────────────────────────
let books = [];
let unrendered = null;   // sceneCount > 0, /workflow 404 "no workflow yet", /pages empty
let rendered = null;     // /workflow 200 with seriesObjectId (a real graph, in a series)
let sibling = null;      // another chapter of `rendered`'s series
let charBook = null;     // a book whose /characters is non-empty
let character = null;    // { objectId, name } from charBook
let renderedSceneTotal = null;    // GET /{rendered}/scenes length — the M in "N rendered of M scenes"
let unrenderedSceneTotal = null;  // GET /{unrendered}/scenes length — the canvas scene-list length

test.describe('PictureBook (PB2) navigation + editing', () => {
    test.describe.configure({ timeout: 180000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLoginShared(request);

        const resp = await request.get(PB_REST + '/books');
        expect(resp.ok(), 'GET /books failed: ' + resp.status()).toBe(true);
        books = await resp.json();
        expect(Array.isArray(books) && books.length > 0, 'shared user has no PB2 books').toBe(true);

        const withScenes = books.filter((b) => (b.sceneCount || 0) > 0);
        for (const b of withScenes) {
            if (unrendered && rendered) break;
            const wf = await request.get(PB_REST + '/' + b.objectId + '/workflow');
            if (wf.status() === 404) {
                const txt = (await wf.text().catch(() => '')).toLowerCase();
                if (!unrendered && txt.includes('no workflow')) {
                    const pages = await request.get(PB_REST + '/' + b.objectId + '/pages');
                    const pj = pages.ok() ? await pages.json().catch(() => []) : [];
                    if (Array.isArray(pj) && pj.length === 0) unrendered = b;
                }
            } else if (wf.ok() && !rendered) {
                const graph = await wf.json().catch(() => null);
                if (graph && graph.seriesObjectId && Array.isArray(graph.nodes) && graph.nodes.length > 0) {
                    const sibs = books.filter((x) => x.seriesObjectId === graph.seriesObjectId && x.objectId !== b.objectId);
                    if (sibs.length) { rendered = b; sibling = sibs[0]; }
                }
            }
        }

        if (rendered) {
            const sc = await request.get(PB_REST + '/' + rendered.objectId + '/scenes');
            const sj = sc.ok() ? await sc.json().catch(() => null) : null;
            renderedSceneTotal = Array.isArray(sj) ? sj.length : null;
        }
        if (unrendered) {
            const sc = await request.get(PB_REST + '/' + unrendered.objectId + '/scenes');
            const sj = sc.ok() ? await sc.json().catch(() => null) : null;
            unrenderedSceneTotal = Array.isArray(sj) ? sj.length : null;
        }

        for (const b of [rendered, unrendered].concat(withScenes)) {
            if (!b) continue;
            const ch = await request.get(PB_REST + '/' + b.objectId + '/characters');
            const cj = ch.ok() ? await ch.json().catch(() => []) : [];
            const c = Array.isArray(cj) ? cj.find((x) => x && x.objectId && x.name) : null;
            if (c) { charBook = b; character = { objectId: c.objectId, name: c.name }; break; }
        }

        console.log('[pb-nav] unrendered=' + (unrendered && unrendered.objectId) + ' rendered=' + (rendered && rendered.objectId)
            + ' sibling=' + (sibling && sibling.objectId) + ' charBook=' + (charBook && charBook.objectId)
            + ' character=' + (character && character.objectId + ' "' + character.name + '"'));
    });

    test.afterAll(async ({ request }) => {
        await request.get(REST + '/logout').catch(() => {});
    });

    // 1. Book list row → Edit opens the wizard, titled with the book's name, resumed on a scene step.
    test('list row Edit opens the wizard titled with the PB2 book name', async ({ page }) => {
        test.skip(!unrendered, 'no PB2 book with scenes but no workflow available to the shared user');
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book');
        const row = await revealBookRow(page, unrendered);

        await row.locator('[data-pb2-edit="' + unrendered.objectId + '"]').click();
        const dlg = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Picture Book —' }) }).last();
        await expect(dlg, 'wizard dialog did not open').toBeVisible({ timeout: 30000 });
        await expect(dlg.locator('.am7-dialog-title')).toHaveText('Picture Book — ' + displayName(unrendered));
        // Resume lands on Images (4) or View (5) — never Step 1 treating the book id as a source doc.
        const activeStep = dlg.locator('.rounded-full.bg-blue-500').first();
        await expect(activeStep).toBeVisible({ timeout: 30000 });
        expect(['4', '5'], 'wizard did not resume on a scene step').toContain((await activeStep.textContent()).trim());

        await closeTopDialog(page, 'Cancel');
        await expect(dlg).toHaveCount(0, { timeout: 10000 });
    });

    // 2. Book list row → Characters opens the manager directly against the book.
    test('list row Characters opens the Manage Characters dialog for that book', async ({ page }) => {
        test.skip(!charBook, 'no PB2 book with characters available to the shared user');
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book');
        const row = await revealBookRow(page, charBook);

        const charsReq = page.waitForRequest((r) => r.url().includes('/picture-book/' + charBook.objectId + '/characters'), { timeout: 30000 });
        await row.locator('[data-pb2-characters="' + charBook.objectId + '"]').click();
        await charsReq;
        const dlg = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Manage Characters' }) }).last();
        await expect(dlg).toBeVisible({ timeout: 30000 });
        await expect(dlg.locator('[data-char-item="' + character.objectId + '"]')).toBeVisible({ timeout: 30000 });
        await closeTopDialog(page, 'Close');
        await expect(dlg).toHaveCount(0, { timeout: 10000 });
    });

    // 3. Reader header carries Edit + Characters; cover says "N rendered of M scenes", not a page title.
    test('PB2 reader header has Edit + Characters and the cover reports rendered-of-total', async ({ page }) => {
        test.skip(!rendered, 'no rendered PB2 book (graph + series) available to the shared user');
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book/v2/' + rendered.objectId);

        const header = page.locator('[data-pb2-book-name]');
        await expect(header).toHaveText(displayName(rendered), { timeout: 30000 });
        await expect(page.locator('[data-pb2-edit="' + rendered.objectId + '"]')).toBeVisible();
        await expect(page.locator('[data-pb2-characters="' + rendered.objectId + '"]')).toBeVisible();

        const count = page.locator('[data-pb2-cover-count]');
        await expect(count).toBeVisible({ timeout: 30000 });
        const label = (await count.textContent()).trim();
        const mt = label.match(/^(\d+) rendered of (\d+) scenes?$/);
        expect(mt, 'cover count label malformed: "' + label + '"').not.toBeNull();
        const n = parseInt(mt[1], 10), total = parseInt(mt[2], 10);
        expect(n).toBeGreaterThan(0);
        expect(total).toBeGreaterThanOrEqual(n);
        // M is the /scenes total for this book, not the page count.
        expect(renderedSceneTotal, '/scenes for the rendered book was not readable').not.toBeNull();
        expect(total).toBe(Math.max(renderedSceneTotal, n));

        // Header Edit opens the wizard titled with this book's name (resumed, not Step 1).
        await page.locator('[data-pb2-edit="' + rendered.objectId + '"]').click();
        const dlg = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Picture Book —' }) }).last();
        await expect(dlg).toBeVisible({ timeout: 30000 });
        await expect(dlg.locator('.am7-dialog-title')).toHaveText('Picture Book — ' + displayName(rendered));
        await closeTopDialog(page, 'Cancel');
        await expect(dlg).toHaveCount(0, { timeout: 10000 });

        // Header Characters opens the manager.
        await page.locator('[data-pb2-characters="' + rendered.objectId + '"]').click();
        const cdlg = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Manage Characters' }) }).last();
        await expect(cdlg).toBeVisible({ timeout: 30000 });
        await closeTopDialog(page, 'Close');
        await expect(cdlg).toHaveCount(0, { timeout: 10000 });
    });

    // 4/5. Canvas on an unrendered book: honest "no workflow yet" state + read-only scene list + ONE
    //      "Open in wizard to render" button (the wizard owns SD config via ensureSdConfig /
    //      persistBookSettings — there are no per-scene Render buttons on the canvas), then "← Book"
    //      lands on the PB2 reader with the book's name.
    test('canvas on an unrendered book lists scenes, offers only the wizard to render, and "← Book" opens the PB2 reader by name', async ({ page }) => {
        test.skip(!unrendered, 'no PB2 book with scenes but no workflow available to the shared user');
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book/' + unrendered.objectId + '/workflow');

        const empty = page.locator('.pb-wf-empty');
        await expect(empty).toBeVisible({ timeout: 45000 });
        expect(unrenderedSceneTotal, '/scenes for the unrendered book was not readable').not.toBeNull();
        await expect(empty).toContainText('No scenes have been rendered yet — ' + unrenderedSceneTotal + ' extracted.');
        await expect(empty).not.toContainText('has no scenes, so there is nothing to show here');
        const items = empty.locator('.pb-wf-empty-scene');
        await expect(items.first()).toBeVisible({ timeout: 30000 });
        expect(await items.count()).toBe(unrenderedSceneTotal);
        await expect(items.first()).toContainText('1.');
        // No per-scene Render buttons on the canvas — rendering goes through the wizard only.
        expect(await empty.locator('button').count()).toBe(1);
        expect(await empty.locator('.pb-wf-render-scene').count()).toBe(0);
        const openWizard = empty.locator('[data-wf-open-wizard="' + unrendered.objectId + '"]');
        await expect(openWizard).toHaveCount(1);
        await expect(openWizard).toHaveText(/Open in wizard to render/);
        // Toolbar title resolves the book name even though there is no graph DTO to read it from.
        await expect(page.locator('[data-wf-title]')).toHaveText(displayName(unrendered) + ' — Workflow', { timeout: 30000 });

        // The single button opens the wizard resumed on this book (NOT clicking Generate/Render inside it).
        await openWizard.click();
        const wiz = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Picture Book —' }) }).last();
        await expect(wiz, 'wizard dialog did not open from the canvas empty state').toBeVisible({ timeout: 30000 });
        await expect(wiz.locator('.am7-dialog-title')).toHaveText('Picture Book — ' + displayName(unrendered));
        const activeStep = wiz.locator('.rounded-full.bg-blue-500').first();
        await expect(activeStep).toBeVisible({ timeout: 30000 });
        expect(['4', '5'], 'wizard did not resume on a scene step').toContain((await activeStep.textContent()).trim());
        await closeTopDialog(page, 'Cancel');
        await expect(wiz).toHaveCount(0, { timeout: 10000 });
        // Still on the canvas route after the wizard closes.
        expect(await page.evaluate(() => window.location.hash)).toBe('#!/picture-book/' + unrendered.objectId + '/workflow');

        await page.locator('[data-back-to-book]').click();
        await expect.poll(() => page.evaluate(() => window.location.hash), { timeout: 15000 })
            .toBe('#!/picture-book/v2/' + unrendered.objectId);
        await expect(page.locator('[data-pb2-book-name]')).toHaveText(displayName(unrendered), { timeout: 30000 });
        await expect(page.locator('[data-pb2-empty]')).toHaveText(
            unrenderedSceneTotal + ' scene' + (unrenderedSceneTotal !== 1 ? 's' : '') + ' extracted — none rendered yet.',
            { timeout: 30000 });
    });

    // 6a. Same-route canvas navigation: a series chapter tile issues a NEW /workflow request and the
    //     toolbar header changes to the sibling chapter.
    test('series chapter tile re-inits the canvas for the sibling chapter', async ({ page }) => {
        test.skip(!rendered || !sibling, 'no rendered PB2 book with a sibling chapter available to the shared user');
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book/' + rendered.objectId + '/workflow');

        const title = page.locator('[data-wf-title]');
        await expect(title).toHaveText(displayName(rendered) + ' — Workflow', { timeout: 45000 });
        await expect(page.locator('[data-node-count]')).toBeVisible({ timeout: 30000 });
        const nodeLabel = (await page.locator('[data-node-count]').textContent()).trim();
        expect(nodeLabel, 'header must count the returned nodes, not the stored 0').not.toBe('0 nodes');

        await page.locator('[data-view-mode="series"]').click();
        const tile = page.locator('[data-series-view] [data-series-chapter="' + sibling.objectId + '"]');
        await expect(tile).toBeVisible({ timeout: 30000 });

        const wfReq = page.waitForRequest((r) => r.url().includes('/picture-book/' + sibling.objectId + '/workflow'), { timeout: 30000 });
        await tile.click();
        await wfReq;
        await expect.poll(() => page.evaluate(() => window.location.hash), { timeout: 15000 })
            .toBe('#!/picture-book/' + sibling.objectId + '/workflow');
        await expect(title).toHaveText(displayName(sibling) + ' — Workflow', { timeout: 45000 });
    });

    // 6b. Reader chapter <select> switches chapters in place (same route component).
    test('reader chapter select switches to the sibling chapter', async ({ page }) => {
        test.skip(!rendered || !sibling, 'no rendered PB2 book with a sibling chapter available to the shared user');
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book/v2/' + rendered.objectId);
        await expect(page.locator('[data-pb2-book-name]')).toHaveText(displayName(rendered), { timeout: 30000 });

        const sel = page.locator('[data-pb2-chapter-select]');
        await expect(sel).toBeVisible({ timeout: 30000 });
        const pagesReq = page.waitForRequest((r) => r.url().includes('/picture-book/' + sibling.objectId + '/pages'), { timeout: 30000 });
        await sel.selectOption(sibling.objectId);
        await pagesReq;
        await expect.poll(() => page.evaluate(() => window.location.hash), { timeout: 15000 })
            .toBe('#!/picture-book/v2/' + sibling.objectId);
        await expect(page.locator('[data-pb2-book-name]')).toHaveText(displayName(sibling), { timeout: 30000 });
    });

    // 8a. Character manager launched STANDALONE (book list / reader header → openCharacterManager(oid,
    //     {standalone:true})): "Open Full Editor" navigates in place to /view/olio.charPerson/<oid>,
    //     closing the manager. There is no wizard underneath, so nothing is lost.
    test('standalone character manager "Open Full Editor" navigates in place to the generic editor', async ({ page }) => {
        test.skip(!charBook || !character, 'no PB2 book with a named character available to the shared user');
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book');
        const row = await revealBookRow(page, charBook);
        await row.locator('[data-pb2-characters="' + charBook.objectId + '"]').click();

        const mgr = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Manage Characters' }) }).last();
        await expect(mgr).toBeVisible({ timeout: 30000 });
        await mgr.locator('[data-char-item="' + character.objectId + '"]').click({ timeout: 30000 });
        const link = mgr.locator('[data-char-full-editor="' + character.objectId + '"]');
        await expect(link).toBeVisible({ timeout: 30000 });

        let popupOpened = false;
        page.once('popup', () => { popupOpened = true; });
        await link.click();

        await expect.poll(() => page.evaluate(() => window.location.hash), { timeout: 15000 })
            .toBe('#!/view/olio.charPerson/' + character.objectId);
        await expect(mgr, 'manager dialog should close when navigating in place').toHaveCount(0, { timeout: 10000 });
        expect(popupOpened, 'standalone must navigate in place, not open a tab').toBe(false);
        // The generic editor (views/object.js) renders its toolbar and the charPerson form with the name.
        await expect(page.locator('.list-results-container .result-nav')).toBeVisible({ timeout: 30000 });
        await expect.poll(() => page.evaluate((n) => Array.from(document.querySelectorAll('.list-results-container input'))
            .some((i) => i.value === n), character.name), { timeout: 30000 }).toBe(true);
    });

    // 8b. Character manager launched FROM INSIDE THE WIZARD (footer "Manage Characters" on step 4/5):
    //     "Open Full Editor" opens a NEW TAB at /view/olio.charPerson/<oid>; the wizard and manager
    //     dialogs — and the in-progress wizard state — stay exactly as they were in the original page.
    test('wizard-launched character manager "Open Full Editor" opens a new tab and keeps the wizard open', async ({ page }) => {
        test.skip(!charBook || !character, 'no PB2 book with a named character available to the shared user');
        // The popup is a second page in the same context — it needs the WebSocket stub too.
        await page.context().addInitScript(wsStubInit);
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book');
        const row = await revealBookRow(page, charBook);

        await row.locator('[data-pb2-edit="' + charBook.objectId + '"]').click();
        const wiz = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Picture Book —' }) }).last();
        await expect(wiz, 'wizard dialog did not open').toBeVisible({ timeout: 60000 });
        await expect(wiz.locator('.am7-dialog-title')).toHaveText('Picture Book — ' + displayName(charBook));
        const activeStep = wiz.locator('.rounded-full.bg-blue-500').first();
        await expect(activeStep).toBeVisible({ timeout: 30000 });
        expect(['4', '5'], 'wizard did not resume on a scene step').toContain((await activeStep.textContent()).trim());

        const manageBtn = wiz.locator('.am7-dialog-footer button').filter({ hasText: 'Manage Characters' }).first();
        await expect(manageBtn, 'wizard footer has no Manage Characters (bookObjectId not resumed?)').toBeVisible({ timeout: 30000 });
        await manageBtn.click();
        const mgr = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Manage Characters' }) }).last();
        await expect(mgr).toBeVisible({ timeout: 30000 });
        await mgr.locator('[data-char-item="' + character.objectId + '"]').click({ timeout: 30000 });
        const link = mgr.locator('[data-char-full-editor="' + character.objectId + '"]');
        await expect(link).toBeVisible({ timeout: 30000 });

        const popupP = page.waitForEvent('popup', { timeout: 30000 });
        await link.click();
        const popup = await popupP;
        await popup.waitForURL((u) => u.hash === '#!/view/olio.charPerson/' + character.objectId, { timeout: 30000 });
        // The new tab shows the generic editor for the character (same session cookie).
        await expect(popup.locator('.list-results-container .result-nav')).toBeVisible({ timeout: 45000 });

        // Original page: route unchanged, wizard AND manager still open.
        expect(await page.evaluate(() => window.location.hash)).toBe('#!/picture-book');
        await expect(mgr).toBeVisible();
        await expect(wiz).toBeVisible();
        await expect(wiz.locator('.am7-dialog-title')).toHaveText('Picture Book — ' + displayName(charBook));

        await popup.close();
        await closeTopDialog(page, 'Close');
        await expect(mgr).toHaveCount(0, { timeout: 10000 });
        await closeTopDialog(page, 'Cancel');
        await expect(wiz).toHaveCount(0, { timeout: 10000 });
    });

    // 7. Inline rename round-trip in the character manager (PATCH olio.charPerson), restored afterwards.
    test('inline character rename round-trips through PATCH and is restored', async ({ page, request }) => {
        test.skip(!charBook || !character, 'no PB2 book with a named character available to the shared user');
        const original = character.name;
        const renamed = original + ' (e2e ' + Date.now().toString(36) + ')';

        // The per-test `request` fixture is a fresh, unauthenticated context — log it in as the shared user.
        await restLoginShared(request);

        // Identity for the safety-net restore (PATCH needs id + objectId + name).
        const full = await request.get(REST + '/model/olio.charPerson/' + character.objectId);
        expect(full.ok(), 'GET charPerson failed: ' + full.status()).toBe(true);
        const rec = await full.json();
        expect(rec && rec.objectId).toBe(character.objectId);

        const readName = async () => {
            const r = await request.get(REST + '/model/olio.charPerson/' + character.objectId);
            const j = await r.json().catch(() => null);
            return j && j.name;
        };

        try {
            await loginAsSharedUser(page);
            await gotoHash(page, '!/picture-book/v2/' + charBook.objectId);
            await page.locator('[data-pb2-characters="' + charBook.objectId + '"]').click({ timeout: 30000 });
            const dlg = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Manage Characters' }) }).last();
            await expect(dlg).toBeVisible({ timeout: 30000 });

            const item = dlg.locator('[data-char-item="' + character.objectId + '"]');
            await expect(item).toBeVisible({ timeout: 30000 });
            await item.click();
            const nameEl = dlg.locator('[data-char-name]');
            await expect(nameEl).toHaveText(original, { timeout: 30000 });

            const rename = async (to) => {
                await dlg.locator('[data-char-edit]').click();
                const input = dlg.locator('[data-char-name-input]');
                await expect(input).toBeVisible({ timeout: 10000 });
                await input.fill(to);
                const patchReq = page.waitForRequest((r) => r.method() === 'PATCH' && r.url().includes('/rest/model'), { timeout: 30000 });
                await dlg.locator('[data-char-save]').click();
                const req = await patchReq;
                const body = req.postDataJSON();
                expect(body.schema).toBe('olio.charPerson');
                expect(body.objectId).toBe(character.objectId);
                expect(body.id).toBe(rec.id);
                expect(body.name).toBe(to);
                await expect(page.locator('.toast-box').filter({ hasText: 'Character saved' })).toBeVisible({ timeout: 30000 });
                await expect(nameEl).toHaveText(to, { timeout: 30000 });
                await expect(item).toContainText(to, { timeout: 30000 });
                expect(await readName(), 'server did not persist the rename').toBe(to);
            };

            await rename(renamed);
            await rename(original);
        } finally {
            // Safety net: never leave the shared character renamed if the UI half of the round-trip failed.
            if ((await readName()) !== original) {
                await request.patch(REST + '/model', {
                    data: { schema: 'olio.charPerson', id: rec.id, objectId: character.objectId, name: original }
                });
            }
        }
        expect(await readName()).toBe(original);
    });
});
