/**
 * PictureBook Read | Edit modes — aiDocs/PictureBookUxConsistencyDesign.md (U1 / U2 / U3 / U5 proof).
 *
 * Runs as the SHARED test user (ensureSharedTestUser) against the live Docker stack:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/bookModes.spec.js --workers=1 --project=chromium
 * The default tests need no LLM / SD: they use a PictureBook (STORY) book the shared user already owns that has
 * olio.pb.scene rows (rows are created by the first render, PbPipelineUtil.getCreateSceneRow). The one render test is
 * gated behind PB_SD_TESTS=1 (it renders a page through SwarmUI).
 *
 * The Story-tab test edits a scene's `blurb` through the UI, verifies the PATCH landed (REST, cache:false), records
 * whether the server then reports the page stale (design §9-2 — measured, not assumed), and restores the original text.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { ensureBookStyleDescription } from './helpers/pbReader.js';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';

let shared = null;
let book = null;      // { objectId, name }
let pages = [];       // /pages of that book

function b64(s) { return Buffer.from(s).toString('base64'); }
function credential() {
    return { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' };
}

async function apiLogin(request) {
    const r = await request.post(REST + '/login', { data: credential() });
    expect(r.ok() || r.status() === 204, 'login as ' + shared.testUserName + ': HTTP ' + r.status()).toBe(true);
}

async function searchOne(request, type, objectId, fields) {
    const r = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, fields: [{ name: 'objectId', comparator: 'equals', value: objectId }], request: fields, recordCount: 1, cache: false }
    });
    expect(r.ok(), 'search ' + type + ': HTTP ' + r.status()).toBe(true);
    const j = await r.json();
    return (j && j.results && j.results[0]) || null;
}

// Canonical page login (chapBook.spec.js / pictureBookV3Ux.spec.js): API login on the page context, WebSocket stub
// BEFORE page.goto (Docker nginx drops the cookie on the WS upgrade → forceLogin → #!/sig).
async function loginPage(page) {
    const resp = await page.request.post(REST + '/login', { data: credential() });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login failed: HTTP ' + resp.status());
    await page.addInitScript(() => {
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
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1; window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(() => window.location.hash.includes('/main') && document.querySelector('[role="main"]'), { timeout: 30000 });
}

async function go(page, route) {
    await page.goto('/#!' + route, { timeout: 30000 });
}

function hashRoute(page) { return decodeURIComponent(new URL(page.url()).hash.replace(/^#!/, '')); }

test.describe.serial('PictureBook Read | Edit modes', () => {
    test.describe.configure({ timeout: 180000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await apiLogin(request);
        const list = await (await request.get(PB + '/books')).json();
        expect(Array.isArray(list), '/books returns a list').toBe(true);
        // Newest first: the shared user's most recent STORY book with scene rows, preferring one whose first page has a
        // rendered image and a workflow node — the Story-tab stale measurement (§9-2) means nothing on a page with no
        // node (nodeStatus is absent there).
        let fallback = null;
        for (const b of list.slice().reverse()) {
            const rec = await searchOne(request, 'olio.pb.book', b.objectId, ['id', 'objectId', 'name', 'bookType']);
            if (!rec || String(rec.bookType || '').toUpperCase() === 'CHAPBOOK') continue;
            const pg = await (await request.get(PB + '/' + b.objectId + '/pages')).json();
            if (!Array.isArray(pg) || !pg.length) continue;
            if (pg[0].dataObjectId && pg[0].sceneNodeObjectId) { book = { objectId: b.objectId, name: rec.name }; pages = pg; break; }
            if (!fallback) fallback = { book: { objectId: b.objectId, name: rec.name }, pages: pg };
        }
        if (!book && fallback) { book = fallback.book; pages = fallback.pages; }
        console.log('[bookModes] book=' + JSON.stringify(book) + ' pages=' + pages.length);
    });

    test('Read mode carries no editing controls; the mode is visible; "Edit this page" deep-links into Edit', async ({ page }) => {
        test.skip(!book, 'the shared user owns no PictureBook with scene rows (render one first)');
        await loginPage(page);
        await go(page, '/picture-book/v2/' + book.objectId);
        const header = page.getByTestId('book-header');
        await expect(header).toBeVisible({ timeout: 30000 });
        await expect(header).toHaveAttribute('data-mode', 'read');
        await expect(page.getByTestId('book-mode-read')).toHaveAttribute('data-active', '1');
        await expect(page.getByTestId('book-editing')).toHaveCount(0);
        // The old reader's edit controls are gone from Read.
        for (const id of ['pb2-tools-menu', 'pb2-style-link', 'pb2-render-all', 'pb2-scene-compose', 'pb2-scene-render', 'pb2-plan-render', 'pb2-scene-regen-background', 'pb2-chat-config', 'pb2-auto-approve-badge']) {
            await expect(page.getByTestId(id), id + ' must not be in Read mode').toHaveCount(0);
        }
        await expect(page.getByTestId('pb2-export')).toBeVisible();
        await expect(page.getByTestId('pb2-fullscreen')).toBeVisible();
        // Go to the first scene page and use its "Edit this page" icon.
        // Wait for the book to load (cover or a scene page), then page forward to the first scene page.
        await expect(page.locator('[data-pb2-cover-source], [data-testid="pb2-scene-page"], [data-pb2-series-art]').first()).toBeVisible({ timeout: 30000 });
        // End = the last page, always a scene page (front pages come first), and every scene page carries the pencil.
        await page.keyboard.press('End');
        await expect(page.getByTestId('pb2-edit-page')).toBeVisible({ timeout: 30000 });
        const sceneId = await page.locator('[data-testid="pb2-scene-page"]').getAttribute('data-scene-id');
        await page.getByTestId('pb2-edit-page').click();
        await expect(page.getByTestId('book-workspace')).toBeVisible({ timeout: 30000 });
        expect(hashRoute(page)).toBe('/picture-book/' + book.objectId + '/edit/scenes/' + sceneId + '?tab=page');
        await expect(page.getByTestId('book-header')).toHaveAttribute('data-mode', 'edit');
        await expect(page.getByTestId('book-editing')).toBeVisible();
        await expect(page.getByTestId('pb-edit-page')).toBeVisible({ timeout: 30000 });
    });

    test('Edit workspace: every rail section opens; Back / Next walk the sections; Edit → Read keeps the page', async ({ page }) => {
        test.skip(!book, 'no PictureBook with scene rows');
        await loginPage(page);
        await go(page, '/picture-book/' + book.objectId + '/edit/book');
        await expect(page.getByTestId('book-workspace')).toBeVisible({ timeout: 30000 });
        for (const s of ['book', 'style', 'characters', 'locations', 'scenes', 'cover', 'review', 'render', 'canvas']) {
            await expect(page.getByTestId('ws-rail-' + s), 'rail offers ' + s).toBeVisible();
        }
        for (const s of ['style', 'characters', 'locations', 'scenes', 'cover', 'review', 'render', 'canvas']) {
            await page.getByTestId('ws-rail-' + s).click();
            await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', s, { timeout: 30000 });
            expect(hashRoute(page)).toContain('/picture-book/' + book.objectId + '/edit/' + s);
            await expect(page.getByTestId('ws-unknown-section')).toHaveCount(0);
            // No section carries its own back arrow any more — the rail is the navigation.
            await expect(page.locator('[data-testid="ws-pane"] [data-testid$="-back"]')).toHaveCount(0);
        }
        // Footer walk from Book: Next → Style, Back → Book.
        await page.getByTestId('ws-rail-book').click();
        await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'book');
        await page.getByTestId('ws-next').click();
        await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'style');
        await page.getByTestId('ws-back').click();
        await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'book');
        // Scenes › a scene → Read lands on that scene's page.
        const target = pages[pages.length - 1];
        await go(page, '/picture-book/' + book.objectId + '/edit/scenes/' + target.objectId + '?tab=page');
        await expect(page.getByTestId('pb-edit-page')).toHaveAttribute('data-scene-id', target.objectId, { timeout: 30000 });
        await page.getByTestId('book-mode-read').click();
        await expect(page.getByTestId('pb2-reader')).toBeVisible({ timeout: 30000 });
        await expect(page.locator('[data-testid="pb2-scene-page"]')).toHaveAttribute('data-scene-id', target.objectId, { timeout: 30000 });
    });

    test('Page tab flips pages inside Edit; the bare /edit route opens a section (D6)', async ({ page }) => {
        test.skip(!book, 'no PictureBook with scene rows');
        await loginPage(page);
        await go(page, '/picture-book/' + book.objectId + '/edit/scenes/' + pages[0].objectId + '?tab=page');
        await expect(page.getByTestId('pb-edit-page')).toHaveAttribute('data-scene-id', pages[0].objectId, { timeout: 30000 });
        if (pages.length > 1) {
            await page.getByTestId('pb-page-next').click();
            await expect(page.getByTestId('pb-edit-page')).toHaveAttribute('data-scene-id', pages[1].objectId, { timeout: 30000 });
            expect(hashRoute(page)).toContain('/edit/scenes/' + pages[1].objectId);
            await page.getByTestId('pb-page-prev').click();
            await expect(page.getByTestId('pb-edit-page')).toHaveAttribute('data-scene-id', pages[0].objectId, { timeout: 30000 });
        } else {
            await expect(page.getByTestId('pb-page-next')).toBeDisabled();
        }
        await go(page, '/picture-book/' + book.objectId + '/edit');
        await expect(page.getByTestId('ws-pane')).toBeVisible({ timeout: 30000 });
        const sec = await page.getByTestId('ws-pane').getAttribute('data-section');
        console.log('[bookModes] bare /edit opened section=' + sec);
        expect(['book', 'style', 'characters', 'locations', 'scenes', 'cover', 'review', 'render']).toContain(sec);
    });

    test('legacy sub-routes redirect into their Edit sections (D8)', async ({ page }) => {
        test.skip(!book, 'no PictureBook with scene rows');
        await loginPage(page);
        const cases = [
            ['/style', '/edit/style'], ['/locations', '/edit/locations'], ['/review', '/edit/review'],
            ['/compose', '/edit/scenes'], ['/cover', '/edit/cover'], ['/workflow', '/edit/canvas'],
            ['/compose/' + pages[0].objectId, '/edit/scenes/' + pages[0].objectId + '?tab=composition']
        ];
        for (const [from, to] of cases) {
            await go(page, '/picture-book/' + book.objectId + from);
            await expect.poll(() => hashRoute(page), { timeout: 30000, message: from + ' → ' + to }).toBe('/picture-book/' + book.objectId + to);
            await expect(page.getByTestId('book-workspace')).toBeVisible({ timeout: 30000 });
        }
    });

    test('library row: the Edit pencil opens Edit; the Characters icon opens Edit › Characters (no pop-in)', async ({ page }) => {
        test.skip(!book, 'no PictureBook with scene rows');
        await loginPage(page);
        await go(page, '/picture-book');
        const row = page.locator('[data-pb2-book="' + book.objectId + '"]');
        // A chapter row may sit inside a collapsed series card.
        if (await row.count() === 0) {
            const headers = page.locator('[data-pb2-series-header]');
            const n = await headers.count();
            for (let i = 0; i < n && await row.count() === 0; i++) await headers.nth(i).click();
        }
        await expect(row).toBeVisible({ timeout: 30000 });
        await row.locator('[data-pb2-characters="' + book.objectId + '"]').click();
        await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'characters', { timeout: 30000 });
        await expect(page.locator('.am7-dialog')).toHaveCount(0);
        await go(page, '/picture-book');
        if (await row.count() === 0) {
            const headers = page.locator('[data-pb2-series-header]');
            const n = await headers.count();
            for (let i = 0; i < n && await row.count() === 0; i++) await headers.nth(i).click();
        }
        await row.locator('[data-pb2-edit="' + book.objectId + '"]').click();
        await expect(page.getByTestId('book-workspace')).toBeVisible({ timeout: 30000 });
        expect(hashRoute(page)).toMatch(new RegExp('^/picture-book/' + book.objectId + '/edit/'));
    });

    test('Story tab (D7): an edit is a draft, leaving asks Save / Discard / Stay, Save PATCHes the scene (measured: stale?)', async ({ page, request }) => {
        test.skip(!book, 'no PictureBook with scene rows');
        const scene = pages[0];
        await apiLogin(request);
        const before = await searchOne(request, 'olio.pb.scene', scene.objectId, ['id', 'objectId', 'blurb', 'userEdited']);
        expect(before && before.id, 'scene record readable as the shared user').toBeTruthy();
        const original = before.blurb == null ? '' : before.blurb;
        const pageBefore = (await (await request.get(PB + '/' + book.objectId + '/pages')).json()).find(p => p.objectId === scene.objectId);
        const staleOf = (p) => !!(p && (String(p.nodeStatus || '').toUpperCase() === 'STALE' || p.imageStale === true));
        const edited = (original ? original + ' ' : '') + '[bookModes ' + Date.now().toString(36) + ']';
        let patchBodies = [];
        page.on('request', (req) => { if (req.method() === 'PATCH' && /\/rest\/model$/.test(req.url())) patchBodies.push(req.postDataJSON()); });

        await loginPage(page);
        try {
            await go(page, '/picture-book/' + book.objectId + '/edit/scenes/' + scene.objectId + '?tab=story');
            const blurb = page.getByTestId('pb-story-blurb');
            await expect(blurb).toBeVisible({ timeout: 30000 });
            await blurb.fill(edited);
            await expect(page.getByTestId('pb-story-dirty-blurb')).toBeVisible();
            await expect(page.getByTestId('pb-story-save')).toBeEnabled();
            await expect(page.locator('[data-testid="ws-rail-scenes"] [data-rail-dirty]')).toBeVisible();
            // Leaving the section asks; Stay keeps the draft and the route.
            await page.getByTestId('ws-rail-book').click();
            await expect(page.getByTestId('dirty-guard')).toBeVisible();
            await page.getByTestId('dirty-guard-stay').click();
            await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'scenes');
            await expect(blurb).toHaveValue(edited);
            expect(patchBodies.length, 'Stay writes nothing').toBe(0);
            // Save.
            await page.getByTestId('pb-story-save').click();
            await expect(page.getByTestId('pb-story-dirty-blurb')).toHaveCount(0, { timeout: 30000 });
            expect(patchBodies.length).toBe(1);
            const body = patchBodies[0];
            expect(body.schema).toBe('olio.pb.scene');
            expect(body.objectId).toBe(scene.objectId);
            expect(body.blurb).toBe(edited);
            expect(body.userEdited).toBe(true);
            expect('name' in body, 'name is never sent (common.name validation trap)').toBe(false);
            // It landed (fresh read).
            const after = await searchOne(request, 'olio.pb.scene', scene.objectId, ['id', 'objectId', 'blurb', 'userEdited']);
            expect(after.blurb).toBe(edited);
            expect(after.userEdited).toBe(true);
            // §9-2 measurement: does the server now report the page stale?
            const pg = (await (await request.get(PB + '/' + book.objectId + '/pages')).json()).find(p => p.objectId === scene.objectId);
            console.log('[bookModes] D7 measurement — BEFORE the edit: nodeStatus=' + (pageBefore && pageBefore.nodeStatus) + ' stale=' + staleOf(pageBefore)
                + ' | AFTER a blurb edit: nodeStatus=' + (pg && pg.nodeStatus) + ' imageStale=' + (pg && pg.imageStale) + ' stale=' + staleOf(pg)
                + (staleOf(pageBefore) ? ' (inconclusive: already stale before the edit)' : ''));
            // Now with a draft, Discard leaves without writing.
            await blurb.fill(edited + ' discard-me');
            await page.getByTestId('ws-rail-book').click();
            await page.getByTestId('dirty-guard-discard').click();
            await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'book', { timeout: 30000 });
            expect(patchBodies.length, 'Discard writes nothing').toBe(1);
        } finally {
            // Restore the fixture's text (as the shared user, same PATCH shape).
            const r = await request.patch(REST + '/model', { data: { schema: 'olio.pb.scene', id: before.id, objectId: before.objectId, blurb: original, userEdited: !!before.userEdited } });
            console.log('[bookModes] restore blurb: HTTP ' + r.status());
        }
    });

    test('browser Back out of Edit with an unsaved draft asks first; Stay keeps the draft (§6.2-3)', async ({ page }) => {
        test.skip(!book, 'no PictureBook with scene rows');
        const scene = pages[0];
        await loginPage(page);
        // Enter Edit IN-APP (one history entry, as a user does). page.goto of a hash route adds two entries in this
        // app (measured: every goto, the library's too), so a goto'd Edit would have Edit behind it, not Read.
        await go(page, '/picture-book/v2/' + book.objectId);
        await expect(page.getByTestId('pb2-reader')).toBeVisible({ timeout: 30000 });
        await page.keyboard.press('End');
        await page.getByTestId('book-mode-edit').click();
        await expect(page.getByTestId('pb-edit-page')).toBeVisible({ timeout: 30000 });
        await page.getByTestId('pb-scene-tab-story').click();
        const blurb = page.getByTestId('pb-story-blurb');
        await expect(blurb).toBeVisible({ timeout: 30000 });
        const draft = (await blurb.inputValue()) + ' [back-guard]';
        await blurb.fill(draft);
        // Back walks in-workspace entries (the Story tab click is one) without asking — drafts are kept inside Edit —
        // and asks on the step that would leave Edit. At most three entries separate the draft from Read here.
        async function backUntilAsked() {
            for (let i = 0; i < 3; i++) {
                await page.evaluate(() => history.back());
                try { await expect(page.getByTestId('dirty-guard')).toBeVisible({ timeout: 3000 }); return true; } catch (e) { /* still inside Edit */ }
                expect(hashRoute(page), 'Back must not leave Edit without asking').toContain('/edit/');
            }
            return false;
        }
        expect(await backUntilAsked(), 'Back out of Edit with a draft must ask').toBe(true);
        await page.getByTestId('dirty-guard-stay').click();
        await expect(page.getByTestId('book-workspace')).toBeVisible();
        // Stay returns to the last in-Edit entry (here the Page tab Back had stepped onto); the draft is still there.
        expect(hashRoute(page)).toContain('/picture-book/' + book.objectId + '/edit/scenes');
        await expect(page.locator('[data-testid="pb-scene-tab-story"] .text-amber-500'), 'Story tab still marked unsaved').toBeVisible();
        await page.getByTestId('pb-scene-tab-story').click();
        await expect(blurb).toHaveValue(draft);
        // Discard then really leaves, writing nothing.
        let patches = 0;
        page.on('request', (req) => { if (req.method() === 'PATCH') patches++; });
        expect(await backUntilAsked(), 'asks again on the next Back out').toBe(true);
        await page.getByTestId('dirty-guard-discard').click();
        await expect.poll(() => hashRoute(page), { timeout: 30000 }).not.toContain('/edit/');
        expect(patches).toBe(0);
    });

    test('[SD] a render started in Edit shows in the Read header pill and does not move the page', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS=1 renders through SwarmUI');
        test.skip(!book, 'no PictureBook with scene rows');
        test.setTimeout(900000);
        // The render gate needs a style description; give the fixture one only if its effective style has none.
        await apiLogin(request);
        await ensureBookStyleDescription(request, PB, book.objectId);
        await loginPage(page);
        const scene = pages[0];
        await go(page, '/picture-book/' + book.objectId + '/edit/scenes/' + scene.objectId + '?tab=render');
        const render = page.getByTestId('pb2-scene-render');
        await expect(render).toBeVisible({ timeout: 30000 });
        await expect(render, 'render gate: ' + (await render.getAttribute('title'))).toBeEnabled({ timeout: 30000 });
        await render.click();
        await expect(page.getByTestId('book-activity')).toBeVisible({ timeout: 60000 });
        await expect(page.getByTestId('ws-dock')).toBeVisible();
        await page.getByTestId('book-mode-read').click();
        await expect(page.getByTestId('pb2-reader')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('book-activity')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb2-run-progress'), 'no run panel in the Read page flow').toHaveCount(0);
        const img = page.getByTestId('pb2-scene-image');
        if (await img.count()) {
            const a = await img.boundingBox();
            await page.waitForTimeout(5000);
            const b = await img.boundingBox();
            expect(b.y, 'the page does not move while the run progresses').toBe(a.y);
        }
        await expect(page.getByTestId('book-activity')).toHaveAttribute('data-running', '0', { timeout: 840000 });
        console.log('[bookModes] render finished: failed=' + await page.getByTestId('book-activity').getAttribute('data-failed'));
    });
});

// ── ChapBook (§8) ──────────────────────────────────────────────────────────
let cbBook = null;

test.describe.serial('ChapBook Read | Edit modes', () => {
    test.describe.configure({ timeout: 180000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = shared || await ensureSharedTestUser(request);
        await apiLogin(request);
        const list = await (await request.get(REST + '/olio/chap-book/books')).json();
        const withPages = [];
        for (const b of (Array.isArray(list) ? list : []).slice().reverse()) {
            const pg = await (await request.get(PB + '/' + b.objectId + '/pages')).json();
            if (Array.isArray(pg) && pg.length) { withPages.push({ objectId: b.objectId, name: b.name, pages: pg }); break; }
        }
        cbBook = withPages[0] || null;
        console.log('[bookModes] chapbook=' + JSON.stringify(cbBook && { objectId: cbBook.objectId, name: cbBook.name, pages: cbBook.pages.length }));
    });

    test('Read has no config panel or edit controls; Read → Edit opens Pages on the current page', async ({ page }) => {
        test.skip(!cbBook, 'the shared user owns no ChapBook with pages');
        await loginPage(page);
        await go(page, '/chap-book/read/' + cbBook.objectId);
        await expect(page.getByTestId('book-header')).toHaveAttribute('data-mode', 'read', { timeout: 30000 });
        await expect(page.getByTestId('book-header')).toHaveAttribute('data-book-kind', 'cb');
        for (const id of ['cb-config-panel', 'cb-reanalyze-chatconfig', 'cb-render-start', 'cb-save-all']) {
            await expect(page.getByTestId(id), id + ' must not be in Read').toHaveCount(0);
        }
        // exactly one header — ReaderShell draws bookHeader through its renderHeader slot
        await expect(page.getByTestId('book-header')).toHaveCount(1);
        await page.getByTestId('cb-read-next').click();
        await page.getByTestId('book-mode-edit').click();
        await expect(page.getByTestId('book-workspace')).toBeVisible({ timeout: 30000 });
        expect(hashRoute(page)).toMatch(new RegExp('^/chap-book/edit/' + cbBook.objectId + '/pages'));
        for (const s of ['book', 'style', 'locations', 'pages', 'cover', 'review', 'render']) {
            await expect(page.getByTestId('ws-rail-' + s), 'ChapBook rail offers ' + s).toBeVisible();
        }
        await expect(page.getByTestId('ws-rail-canvas'), 'ChapBooks keep the canvas (advanced)').toBeVisible();
        for (const s of ['characters', 'scenes']) await expect(page.getByTestId('ws-rail-' + s)).toHaveCount(0);
    });

    test('/chap-book/review redirects to Edit › Pages; every section opens (§9-1: Cover & Art and Review on a CHAPBOOK)', async ({ page }) => {
        test.skip(!cbBook, 'the shared user owns no ChapBook with pages');
        let serverErrors = [];
        page.on('response', (r) => { if (r.status() >= 500 && /\/rest\//.test(r.url())) serverErrors.push(r.status() + ' ' + r.url()); });
        await loginPage(page);
        await go(page, '/chap-book/review/' + cbBook.objectId);
        await expect.poll(() => hashRoute(page), { timeout: 30000 }).toBe('/chap-book/edit/' + cbBook.objectId + '/pages');
        await expect(page.getByTestId('cb-page-list')).toBeVisible({ timeout: 30000 });
        for (const s of ['book', 'style', 'locations', 'pages', 'cover', 'review', 'render', 'canvas']) {
            await page.getByTestId('ws-rail-' + s).click();
            await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', s, { timeout: 30000 });
            await expect(page.getByTestId('ws-unknown-section')).toHaveCount(0);
        }
        // §9-1, measured: does Cover & Art / Review load for a CHAPBOOK book?
        await page.getByTestId('ws-rail-cover').click();
        await page.waitForTimeout(3000);
        const coverErr = await page.getByTestId('pb-cmp-error').count() ? await page.getByTestId('pb-cmp-error').innerText() : null;
        await page.getByTestId('ws-rail-review').click();
        await page.waitForTimeout(3000);
        const reviewErr = await page.getByTestId('pb2-review-error').count() ? await page.getByTestId('pb2-review-error').innerText() : null;
        console.log('[bookModes] §9-1 measurement — CHAPBOOK cover error=' + JSON.stringify(coverErr) + ' review error=' + JSON.stringify(reviewErr) + ' 5xx=' + JSON.stringify(serverErrors));
        expect(coverErr, 'Cover & Art loads on a ChapBook').toBeNull();
        expect(reviewErr, 'Review loads on a ChapBook').toBeNull();
        expect(serverErrors).toEqual([]);
    });

    test('a ChapBook opened on a PictureBook route goes to its own product (§8.2)', async ({ page }) => {
        test.skip(!cbBook, 'the shared user owns no ChapBook with pages');
        await loginPage(page);
        await go(page, '/picture-book/v2/' + cbBook.objectId);
        await expect.poll(() => hashRoute(page), { timeout: 30000 }).toBe('/chap-book/read/' + cbBook.objectId);
        await go(page, '/picture-book/' + cbBook.objectId + '/edit/scenes');
        await expect.poll(() => hashRoute(page), { timeout: 30000 }).toBe('/chap-book/edit/' + cbBook.objectId + '/pages');
    });
});
