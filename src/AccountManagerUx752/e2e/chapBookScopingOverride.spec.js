/**
 * ChapBook C3 (poem scoping) + C5 (per-scene SD-config override editor) E2E tests.
 *
 * Run against the LIVE Docker stack:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookScopingOverride.spec.js --workers=1 --project=chromium
 *
 * C3 is a pure REST round-trip (Playwright `request`); C5 drives the real review UI.
 * No image render (no LLM/SD) is needed — scenes exist immediately after chapbook creation.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

const LOGIN_BODY = {
    schema: 'auth.credential',
    organizationPath: '/Development',
    name: 'e2etest_shared',
    credential: Buffer.from('password').toString('base64'),
    type: 'hashed_password'
};

// A multi-line poem so createChapBook chunks it into >=1 scene.
const POEM_TEXT = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green
To silver veins of icy sheens,
Born of spells and sorcery.

Inside hearts and hearths and homes,
Ochre embers and ebon cinders,
Faded life stirred by motherly crones,
Dry damp clothes and warm cold bones
And illuminate the age-old spellbound tomes.`;

async function login(ctx) {
    const resp = await ctx.post(REST + '/login', { data: LOGIN_BODY });
    expect(resp.ok() || resp.status() === 204, 'login failed: HTTP ' + resp.status()).toBe(true);
}

async function makeGroup(ctx, path) {
    const resp = await ctx.get(REST + '/path/make/auth.group/data/B64-' +
        Buffer.from(path).toString('base64').replace(/=/g, '%3D'));
    const body = await resp.json();
    expect(body && body.id, 'could not ensure group ' + path).toBeTruthy();
    return body; // { id, organizationId, ... }
}

async function createNote(ctx, groupId, name, text) {
    const resp = await ctx.post(REST + '/model', {
        data: { schema: 'data.note', name, groupId, text }
    });
    const body = await resp.json().catch(() => null);
    expect(body && body.objectId, 'note create failed for ' + name + ' HTTP ' + resp.status()).toBeTruthy();
    return body.objectId;
}

async function createPoem(ctx, groupId, name, title, text) {
    const resp = await ctx.post(REST + '/model', {
        data: { schema: 'olio.cb.poem', name, title, author: 'C3 Test', groupId, text }
    });
    const body = await resp.json().catch(() => null);
    expect(body && body.objectId, 'poem create failed for ' + name + ' HTTP ' + resp.status()).toBeTruthy();
    return body.objectId;
}

// Extract the book reference id/objectId from a serialized poem, tolerant of FK shapes.
function bookRef(poem) {
    let b = poem.book != null ? poem.book : poem.book_FK;
    if (b == null) return null;
    if (typeof b === 'object') return b.objectId || b.id || null;
    return b;
}

// ─────────────────────────────── C3: poem scoping REST round-trip ───────────────────────────────

test.describe('C3 — poem scoping to a chapbook (REST)', () => {
    test.describe.configure({ timeout: 120000 });

    test('scoped import filters GET /poems; bad bookObjectId => 404', async ({ request }) => {
        await ensureSharedTestUser(request);
        await login(request);

        const stamp = Date.now().toString(36);
        const poemsGrp = await makeGroup(request, '~/Poems');
        const notesGrp = await makeGroup(request, '~/Notes');
        const orgId = poemsGrp.organizationId;

        // Seed poem to build the CHAPBOOK book from (stays global — createChapBook does not stamp book FK).
        const seedPoemOid = await createPoem(request, poemsGrp.id,
            'c3-seed-' + stamp, 'C3 Seed', POEM_TEXT);

        // Create CHAPBOOK book B.
        const createResp = await request.post(CB_REST + '/create', {
            data: {
                slug: 'c3-scope-' + stamp,
                title: 'C3 Scope Book',
                poemObjectIds: [seedPoemOid],
                maxLinesPerPage: 4
            }
        });
        expect(createResp.ok(), 'create book failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        const bookObjectId = created.bookObjectId || created.objectId;
        expect(bookObjectId, 'no bookObjectId in create response').toBeTruthy();

        // Confirm it is a CHAPBOOK.
        const bookSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.book', cache: false,
                request: ['id', 'objectId', 'slug', 'bookType'],
                fields: [
                    { name: 'objectId', comparator: 'equals', value: bookObjectId },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ]
            }
        });
        const bookRow = (await bookSearch.json()).results[0];
        expect(bookRow, 'book not found').toBeTruthy();
        expect(String(bookRow.bookType).toUpperCase()).toBe('CHAPBOOK');
        const bookId = bookRow.id;

        // Two notes scoped to B, one note global.
        const note1 = await createNote(request, notesGrp.id, 'c3-note1-' + stamp, POEM_TEXT);
        const note2 = await createNote(request, notesGrp.id, 'c3-note2-' + stamp, POEM_TEXT);
        const note3 = await createNote(request, notesGrp.id, 'c3-note3-' + stamp, POEM_TEXT);

        // Import 2 poems SCOPED to B via root bookObjectId.
        const scopedImp = await request.post(CB_REST + '/poems', {
            data: {
                bookObjectId,
                sources: [
                    { type: 'data.note', objectId: note1, title: 'C3 Scoped One ' + stamp },
                    { type: 'data.note', objectId: note2, title: 'C3 Scoped Two ' + stamp }
                ]
            }
        });
        expect(scopedImp.ok(), 'scoped import failed: ' + scopedImp.status() + ' ' + await scopedImp.text()).toBe(true);
        const scopedBody = await scopedImp.json();
        expect(scopedBody.errors || [], 'scoped import errors: ' + JSON.stringify(scopedBody.errors)).toEqual([]);
        expect(scopedBody.poems.length, 'expected 2 scoped poems imported').toBe(2);
        const scopedOids = scopedBody.poems.map(p => p.objectId).sort();

        // Import 1 poem GLOBAL (no bookObjectId).
        const globalImp = await request.post(CB_REST + '/poems', {
            data: { sources: [{ type: 'data.note', objectId: note3, title: 'C3 Global ' + stamp }] }
        });
        expect(globalImp.ok(), 'global import failed: ' + globalImp.status()).toBe(true);
        const globalOid = (await globalImp.json()).poems[0].objectId;
        expect(globalOid).toBeTruthy();

        // (1) GET /poems?bookObjectId=B => EXACTLY the 2 scoped poems, each with book referencing B.
        const scopedList = await request.get(CB_REST + '/poems', {
            params: { bookObjectId, recordCount: 100 }
        });
        expect(scopedList.ok(), 'scoped GET /poems failed: ' + scopedList.status()).toBe(true);
        const scopedArr = await scopedList.json();
        expect(Array.isArray(scopedArr), 'scoped list should be a JSON array').toBe(true);
        const scopedListOids = scopedArr.map(p => p.objectId).sort();
        expect(scopedListOids, 'scoped list must be exactly the 2 scoped poems').toEqual(scopedOids);
        expect(scopedListOids.includes(globalOid), 'global poem must NOT appear in scoped list').toBe(false);
        expect(scopedListOids.includes(seedPoemOid), 'seed poem must NOT appear in scoped list').toBe(false);
        // Each scoped poem's book FK references B.
        for (const p of scopedArr) {
            const ref = bookRef(p);
            expect(ref != null, 'scoped poem missing book FK: ' + JSON.stringify(p)).toBe(true);
            const refStr = String(ref);
            expect(refStr === bookObjectId || refStr === String(bookId),
                'scoped poem book FK (' + refStr + ') does not reference B (' + bookObjectId + '/' + bookId + ')').toBe(true);
        }

        // (2) GET /poems (unscoped) => full library still includes the global poem.
        const fullList = await request.get(CB_REST + '/poems', { params: { recordCount: 500 } });
        expect(fullList.ok(), 'unscoped GET /poems failed: ' + fullList.status()).toBe(true);
        const fullArr = await fullList.json();
        const fullOids = fullArr.map(p => p.objectId);
        expect(fullOids.includes(globalOid), 'global poem must appear in unscoped library').toBe(true);
        expect(fullOids.includes(scopedOids[0]) && fullOids.includes(scopedOids[1]),
            'scoped poems must also appear in unscoped library').toBe(true);

        // (3) POST /poems with a non-blank, nonexistent bookObjectId => HTTP 404.
        const badImp = await request.post(CB_REST + '/poems', {
            data: {
                bookObjectId: '00000000-0000-0000-0000-000000000000',
                sources: [{ type: 'data.note', objectId: note3, title: 'C3 Bad ' + stamp }]
            }
        });
        expect(badImp.status(), 'nonexistent bookObjectId must be 404, got ' + badImp.status()).toBe(404);

        await request.get(REST + '/logout');
    });
});

// ─────────────────────────────── C5: per-scene SD-config override editor (UI) ───────────────────────────────

async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', { data: LOGIN_BODY });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login failed: HTTP ' + resp.status());

    // Stub WebSocket BEFORE any navigation — Docker nginx strips the cookie on the WS upgrade,
    // so Tomcat closes it, which triggers forceLogin() and a redirect to #!/sig.
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
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });

    await page.goto('/', { timeout: 30000 });
    // waitForFunction(fn, arg, options): the options object is the THIRD argument.
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null,
        { timeout: 30000 }
    );
}

// Route into the review page cleanly (via /main first) so ChapBookReview.oninit re-runs and
// re-fetches configOverride from the backend with cache:false.
async function gotoReview(page, oid) {
    await page.evaluate(() => { window.location.hash = '!/main'; });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null, { timeout: 30000 });
    await page.evaluate((o) => { window.location.hash = '!/chap-book/review/' + o; }, oid);
    await page.waitForFunction(
        () => window.location.hash.includes('/chap-book/review'), null, { timeout: 30000 });
    // Scene cards carry the "Image config overrides" toggle.
    await expect(page.locator('button:has-text("Image config overrides")').first())
        .toBeVisible({ timeout: 20000 });
}

// The FIRST scene card on the review page (the card root is the rounded-border div that carries
// the "Image config overrides" toggle). All per-scene controls are scoped to it so the assertions
// cannot be satisfied by a sibling card's Save/Clear/steps control.
function firstSceneCard(page) {
    return page.locator('div.rounded-lg.border')
        .filter({ has: page.locator('button:has-text("Image config overrides")') })
        .first();
}

// The per-scene override editor is the shared SdConfigPanel (src/components/SdConfigPanel.js); its
// range widgets (formFieldRenderers.renderRange) carry NO name attribute — each is an sr-only
// <label> holding the field key ("steps") followed by an <input type=range> and an
// <input type=number> sibling. Target the number spinner that follows the exact "steps" label
// (NOT "refinerSteps").
function stepsSpinner(card) {
    return card.locator('label.sr-only:text-is("steps")')
        .locator('xpath=following-sibling::input[@type="number"]')
        .first();
}

test.describe('C5 — per-scene SD-config override editor (UI)', () => {
    test.describe.configure({ timeout: 180000 });

    test('override save persists (set badge + value), clear removes it', async ({ page }) => {
        await ensureSharedTestUser(page.request);
        await loginAsSharedUser(page);

        // Build a fresh CHAPBOOK book with >=1 scene via REST (page.request is authenticated).
        const stamp = Date.now().toString(36);
        const poemsGrp = await makeGroup(page.request, '~/Poems');
        const seedPoemOid = await createPoem(page.request, poemsGrp.id,
            'c5-seed-' + stamp, 'C5 Seed', POEM_TEXT);
        const createResp = await page.request.post(CB_REST + '/create', {
            data: { slug: 'c5-ovr-' + stamp, title: 'C5 Override Book', poemObjectIds: [seedPoemOid], maxLinesPerPage: 6 }
        });
        expect(createResp.ok(), 'C5 book create failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const c5Created = await createResp.json();
        const bookObjectId = c5Created.bookObjectId || c5Created.objectId;
        expect(bookObjectId, 'no bookObjectId for C5 book: ' + JSON.stringify(c5Created)).toBeTruthy();

        // Enter review.
        await gotoReview(page, bookObjectId);

        const card = firstSceneCard(page);
        const firstToggle = card.locator('button:has-text("Image config overrides")');
        // No override yet → no "set" badge, and no Clear button (it only renders when overridden).
        await expect(firstToggle.getByText('set', { exact: true })).toHaveCount(0);
        await expect(card.locator('button[title*="Clear this page"]')).toHaveCount(0);

        // The card's single Save (D1) is disabled while the card is clean.
        const saveBtn = card.locator('button[title="Save unsaved changes on this page"], button[title="No unsaved changes"]');
        await expect(saveBtn).toHaveCount(1);
        await expect(saveBtn).toBeDisabled();
        await expect(saveBtn).toHaveText(/Saved/);

        // Expand the overrides section on the first scene → the shared SdConfigPanel mounts in
        // override mode (chapBook.js renderSceneCard → m(SdConfigPanel, { inst: getSceneOverrideInst }).
        await firstToggle.click();
        const stepsInput = stepsSpinner(card);
        await expect(stepsInput, 'SdConfigPanel steps spinner did not mount in the first scene card')
            .toBeVisible({ timeout: 10000 });
        // Unset override → the panel shows the shared RANGE_DEFAULTS.steps (20), not the new value.
        const NEW_VAL = '37';
        await expect(stepsInput).not.toHaveValue(NEW_VAL);

        // Change the numeric "steps" field to a distinct value. SdConfigPanel.rangeInput writes it to
        // the override inst (inst.changes grows) and fires onChange → markSceneDirty(scene,'override'),
        // so the card's Save flips to enabled and the toggle shows the "set" badge (unsaved edits count).
        await stepsInput.fill(NEW_VAL);
        await expect(stepsInput).toHaveValue(NEW_VAL);
        await expect(saveBtn).toBeEnabled({ timeout: 10000 });
        await expect(saveBtn).toHaveText(/Save$/);
        await expect(firstToggle.getByText('set', { exact: true })).toBeVisible();

        // Save → the card's Save issues PUT /rest/olio/picture-book/scene/{oid}/config-override with
        // the SPARSE delta.
        const savePut = page.waitForResponse(r =>
            r.url().includes('/config-override') && r.request().method() === 'PUT', { timeout: 30000 });
        await saveBtn.click();
        const saveResp = await savePut;
        expect(saveResp.status(), 'PUT config-override (save) must be 200').toBe(200);
        // Body shape: { configOverride: "<sparse olio.sd.config JSON string>" } (putSceneConfigOverride).
        const savedBody = JSON.parse(saveResp.request().postData() || '{}');
        const savedDelta = JSON.parse(savedBody.configOverride || 'null');
        expect(savedDelta, 'config-override PUT must carry a sparse override object').toBeTruthy();
        expect(savedDelta.steps, 'sparse override must carry the edited steps value').toBe(parseInt(NEW_VAL, 10));
        expect(Object.keys(savedDelta).filter(k => k !== 'schema').sort(),
            'sparse override must carry ONLY the edited field (no materialized defaults)').toEqual(['steps']);
        await expect(saveBtn, 'card Save must return to the clean/disabled state after a successful save')
            .toBeDisabled({ timeout: 10000 });

        // Reload the whole page (clears all in-memory JS state) then re-enter review — proves the
        // override was persisted server-side, not just held in the client.
        await page.reload({ timeout: 30000 });
        await page.waitForFunction(() => document.querySelector('[role="main"]'), null, { timeout: 30000 });
        await gotoReview(page, bookObjectId);

        // (a) "set" badge is present after reload.
        const cardAfter = firstSceneCard(page);
        const toggleAfter = cardAfter.locator('button:has-text("Image config overrides")');
        await expect(toggleAfter.getByText('set', { exact: true })).toBeVisible({ timeout: 10000 });

        // (b) Persisted value reads back.
        await toggleAfter.click();
        const stepsAfter = stepsSpinner(cardAfter);
        await expect(stepsAfter).toBeVisible({ timeout: 10000 });
        await expect(stepsAfter).toHaveValue(NEW_VAL);

        // Clear the override (explicit per-scene Clear, only rendered while overridden).
        const clearBtn = cardAfter.locator('button[title*="Clear this page"]');
        await expect(clearBtn).toBeEnabled();
        const clearPut = page.waitForResponse(r =>
            r.url().includes('/config-override') && r.request().method() === 'PUT', { timeout: 30000 });
        await clearBtn.click();
        const clearResp = await clearPut;
        expect(clearResp.status(), 'PUT config-override (clear) must be 200').toBe(200);
        // Clear rebuilds the panel from the (now empty) override → badge and Clear button vanish, and
        // the steps spinner falls back to the shared default rather than the cleared value.
        await expect(toggleAfter.getByText('set', { exact: true })).toHaveCount(0, { timeout: 10000 });
        await expect(cardAfter.locator('button[title*="Clear this page"]')).toHaveCount(0);
        await expect(stepsSpinner(cardAfter)).not.toHaveValue(NEW_VAL);

        // Reload again → override removed server-side (no "set" badge, no Clear button).
        await page.reload({ timeout: 30000 });
        await page.waitForFunction(() => document.querySelector('[role="main"]'), null, { timeout: 30000 });
        await gotoReview(page, bookObjectId);
        const cardCleared = firstSceneCard(page);
        const toggleCleared = cardCleared.locator('button:has-text("Image config overrides")');
        await expect(toggleCleared.getByText('set', { exact: true })).toHaveCount(0);
        await expect(cardCleared.locator('button[title*="Clear this page"]')).toHaveCount(0);
        await toggleCleared.click();
        await expect(stepsSpinner(cardCleared)).toBeVisible({ timeout: 10000 });
        await expect(stepsSpinner(cardCleared)).not.toHaveValue(NEW_VAL);

        await page.request.get(REST + '/logout');
    });
});
