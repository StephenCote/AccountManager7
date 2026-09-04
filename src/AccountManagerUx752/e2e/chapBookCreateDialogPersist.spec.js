/**
 * FIX 2 — ChapBook create dialog is non-dismissible by backdrop (route #!/chap-book,
 * src/features/chapBook.js, showCreateDialog block ~1370).
 *
 * The bug: the create dialog closed when the backdrop was clicked (or a select-drag released on the
 * backdrop), discarding an in-progress create. The fix removed the backdrop onclick dismiss from THAT
 * dialog only; it still closes via the X button, the Cancel button, or a successful create.
 *
 * These tests only OPEN and CLOSE the dialog — the ChapBook create endpoint contacts the LLM only when
 * the Create button is pressed, which these tests never do, so they are fully LLM/SD-free and run in
 * the default Docker suite.
 *
 * Run (Windows / Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookCreateDialogPersist.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';

const POEM_NAME = 'cbdlg-poem-fixed';
const POEM_TITLE = 'CBDLG Persist Poem';
const POEM_TEXT = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green.`;

async function restLogin(ctx) {
    const resp = await ctx.post(REST + '/login', {
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

// Canonical WS-stub + login pattern copied verbatim from chapBook.spec.js.
async function loginAsSharedUser(page) {
    await restLogin(page.request);
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
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

// Navigate to the ChapBook feature, select the first available poem, and open the create dialog.
// Returns the dialog overlay locator (the fixed inset-0 backdrop containing the panel).
//
// The dialog can only be opened by selecting a poem, but WHICH poem is irrelevant to this fix — these
// tests only open/close the dialog and never create. The library's client-side filter operates over
// only the first page the server returns (GET /olio/chap-book/poems → ChapBookUtil.listPoems: first 25
// by name ASCENDING), so a specific seeded poem can be pushed out of the visible window on an accumulated
// shared DB. So instead of filtering for a title, select whatever poem the library already shows — the
// proven pattern from chapBook.spec.js Test 3 (page.locator('input[type="checkbox"]').first().check()).
async function openCreateDialog(page) {
    await page.evaluate(() => { window.location.hash = '!/chap-book'; });
    // Let the poem library load (fetchPoems → GET /chap-book/poems) before touching a checkbox.
    await page.waitForTimeout(2000);

    const firstCheckbox = page.locator('input[type="checkbox"]').first();
    await expect(firstCheckbox, 'no selectable poem checkbox in the library').toBeVisible({ timeout: 15000 });
    await firstCheckbox.check();

    const createBtn = page.locator('button:has-text("Create ChapBook"), button:has-text("Create Chap")').first();
    await expect(createBtn, '"Create ChapBook" button did not appear after selecting a poem').toBeVisible({ timeout: 10000 });
    await createBtn.click();

    // The dialog is open once its heading renders.
    await expect(page.locator('h3:has-text("Create ChapBook")'), 'create dialog did not open').toBeVisible({ timeout: 10000 });
    return page.locator('div.fixed.inset-0.z-50').filter({ has: page.locator('h3', { hasText: 'Create ChapBook' }) });
}

test.describe('ChapBook — create dialog is non-dismissible by backdrop (FIX 2)', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request);

        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' +
            Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
        const poemsBody = await poemsDir.json();
        expect(poemsBody && poemsBody.id, 'could not ensure ~/Poems group').toBeTruthy();
        const groupId = poemsBody.id;
        const orgId = poemsBody.organizationId;

        // Seed one poem so the library has a selectable row (idempotent by name).
        const search = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'equals', value: POEM_NAME },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId'], recordCount: 1, cache: false
            }
        });
        const sBody = await search.json().catch(() => null);
        if (!(sBody && sBody.results && sBody.results.length)) {
            const cResp = await request.post(REST + '/model', {
                data: {
                    schema: 'olio.cb.poem', name: POEM_NAME, title: POEM_TITLE,
                    author: 'E2E Test', groupId, text: POEM_TEXT
                }
            });
            expect(cResp.ok(), 'seed poem failed: ' + cResp.status()).toBe(true);
        }
        await request.get(REST + '/logout');
    });

    // (a) A backdrop click OUTSIDE the inner panel must NOT close the dialog.
    test('a: clicking the backdrop leaves the create dialog open', async ({ page }) => {
        const errors = [];
        page.on('pageerror', e => errors.push(e.message));

        await loginAsSharedUser(page);
        const overlay = await openCreateDialog(page);
        const titleInput = overlay.locator('input[type="text"]').first();
        await expect(titleInput, 'Title input not present in dialog').toBeVisible();

        // Click the top-left corner of the full-viewport overlay — that is the backdrop, well clear of
        // the centered panel. In the buggy version this dismissed the dialog.
        await page.mouse.click(4, 4);
        await page.waitForTimeout(400);

        await expect(page.locator('h3:has-text("Create ChapBook")'),
            'dialog closed on backdrop click — FIX 2 is NOT in effect').toBeVisible();
        await expect(titleInput, 'Title input gone after backdrop click — dialog was dismissed').toBeVisible();
        expect(errors, 'script errors during backdrop click: ' + errors.join('; ')).toHaveLength(0);
    });

    // (b) A mousedown that STARTS inside the panel and releases (mouseup) on the backdrop — the
    //     "select-drag / mouse-out" path the user reported — must also leave the dialog open.
    test('b: a mousedown-in-panel / mouseup-on-backdrop drag leaves the dialog open', async ({ page }) => {
        await loginAsSharedUser(page);
        const overlay = await openCreateDialog(page);
        const titleInput = overlay.locator('input[type="text"]').first();
        await expect(titleInput).toBeVisible();

        // Press inside the Title input, drag out to the backdrop corner, release there.
        const box = await titleInput.boundingBox();
        expect(box, 'could not measure Title input').toBeTruthy();
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        await page.mouse.down();
        await page.mouse.move(4, 4, { steps: 6 });
        await page.mouse.up();
        await page.waitForTimeout(400);

        await expect(page.locator('h3:has-text("Create ChapBook")'),
            'dialog closed on a select-drag ending on the backdrop — the reported mouse-out bug').toBeVisible();
        await expect(titleInput).toBeVisible();
    });

    // (c) The explicit Cancel button DOES close the dialog.
    test('c: the Cancel button closes the dialog', async ({ page }) => {
        await loginAsSharedUser(page);
        const overlay = await openCreateDialog(page);

        await overlay.locator('button:has-text("Cancel")').click();

        await expect(page.locator('h3:has-text("Create ChapBook")'),
            'Cancel did not close the dialog').toHaveCount(0, { timeout: 5000 });
    });

    // (c2) The explicit X button also closes it (the other intended dismissal).
    test('c2: the X button closes the dialog', async ({ page }) => {
        await loginAsSharedUser(page);
        const overlay = await openCreateDialog(page);

        // The X is the header button whose only content is the "close" material icon.
        await overlay.locator('button:has(span.material-symbols-outlined:text("close"))').first().click();

        await expect(page.locator('h3:has-text("Create ChapBook")'),
            'X button did not close the dialog').toHaveCount(0, { timeout: 5000 });
    });
});
