/**
 * ChapBook "Add from Data" sub-group navigation + background-opacity — E2E for two reported bugs.
 *
 * Bug A (sub-group navigation in the data-object picker). Reproduces the user's EXACT steps:
 *   Seed ~/Data/Poems with a couple of data.data "poems", create/open ChapBook, click the real
 *   "Add from Data" button, then exercise BOTH navigation paths and confirm a poem CAN be picked
 *   (the note-order dialog appearing, carrying the poem's name, is the success signal):
 *     (i)  Type the path ~/Data/Poems into the picker search box + Enter, then pick a poem.
 *     (ii) Click "navigate by group" (group_work), select the Poems folder, click "navigate down"
 *          (south_east), click "navigate by group" (group_work) again to return to the data list,
 *          then pick a poem.
 *
 * Bug B (background transparency lost when a bg color is chosen; opacity must be configurable).
 *   Drives the real per-page "Bg color" + "Bg opacity" controls in the ChapBook Review UI, saves,
 *   and asserts the choice BOTH persists (pageBgColor + pageBgOpacity survive on the scene / the
 *   /pages projection the reader loads) AND renders translucent in the reader (the panel's computed
 *   background-color is rgba(r,g,b,alpha) with alpha < 1 — i.e. choosing a color no longer forces a
 *   fully-opaque panel).
 *
 * Requires the Docker test stack (am7test-am7-1) live on host :9443. Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookDataPickerNav.spec.js \
 *       --project=chromium --workers=1
 *
 * 127.0.0.1 (NOT localhost) avoids the IPv6 ::1 gotcha; the WebSocket is stubbed before goto so
 * nginx's cookie-stripped WS upgrade doesn't force a re-login redirect. NEVER admin — e2etest_shared.
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, createObject } from './helpers/api.js';
import {
    restLoginShared, loginAsSharedUser, ensurePoemsGroup, ensurePoem,
    createChapBook, bookPages, POEM_1, POEM_2
} from './helpers/chapbook.js';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://127.0.0.1:9443';
const REST = '/AccountManagerService7/rest';

// Unique per run so freshly-created records never collide with prior runs and each picker filter
// query key is new (so /rest/model/search's per-key cache never serves stale results).
const RUN = Date.now().toString(36);
const POEM_A = 'DPNPoemA_' + RUN;   // a data.data object living ONLY in ~/Data/Poems
const POEM_B = 'DPNPoemB_' + RUN;   // a second data.data object in ~/Data/Poems

function sharedLoginBody() {
    return {
        schema: 'auth.credential',
        organizationPath: '/Development',
        name: 'e2etest_shared',
        credential: Buffer.from('password').toString('base64'),
        type: 'hashed_password'
    };
}

test.describe('ChapBook "Add from Data" sub-group nav + bg opacity', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);

        // Seed AS the shared user (its own session) so the objects are owned/readable by that user.
        const ctx = await pwRequest.newContext({ baseURL: BASE, ignoreHTTPSErrors: true });
        try {
            const login = await ctx.post(BASE + REST + '/login', { data: sharedLoginBody() });
            if (!login.ok() && login.status() !== 204) {
                throw new Error('shared-user API login failed: HTTP ' + login.status());
            }

            // Sub-group ~/Data/Poems (a child of the data.data default container ~/Data).
            const poemsDir = await ensurePath(ctx, 'auth.group', 'data', '~/Data/Poems');
            expect(poemsDir && poemsDir.id, '~/Data/Poems group created').toBeTruthy();

            // Two data.data "poems" that live ONLY inside ~/Data/Poems (unique names → no global
            // createObject short-circuit onto a stale same-named object in another group).
            for (const nm of [POEM_A, POEM_B]) {
                const d = await createObject(ctx, 'data.data', {
                    name: nm,
                    contentType: 'text/plain',
                    groupId: poemsDir.id,
                    groupPath: poemsDir.path
                });
                expect(d && d.objectId, nm + ' created in ~/Data/Poems').toBeTruthy();
            }
        } finally {
            await ctx.dispose();
        }
    });

    // Log the browser session in via REST, stub the WebSocket, land on /main.
    async function loginBrowser(page) {
        const resp = await page.request.post(REST + '/login', { data: sharedLoginBody() });
        if (!resp.ok() && resp.status() !== 204) {
            throw new Error('browser API login failed: HTTP ' + resp.status());
        }
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

    // Navigate to the ChapBook Poem Library view and wait for the real "Add from Data" button.
    async function gotoChapBook(page) {
        await page.goto('/#!/chap-book', { timeout: 30000 });
        await expect(page.locator('button', { hasText: 'Add from Data' }))
            .toBeVisible({ timeout: 20000 });
    }

    function addFromDataBtn(page) {
        return page.locator('button', { hasText: 'Add from Data' });
    }
    function overlay(page) { return page.locator('.am7-picker-overlay'); }
    function pickerRow(page, name) {
        return page.locator('.am7-picker-overlay tr.tabular-row', { hasText: name });
    }
    function toolbarBtn(page, icon) {
        return page.locator('.am7-picker-overlay button:has(span.material-symbols-outlined:text-is("' + icon + '"))');
    }

    // Type into the picker's own filter (Enter-triggered) and wait for the search round-trip + redraw.
    async function filterPicker(page, term) {
        const input = page.locator('.am7-picker-overlay #listFilter');
        await input.waitFor({ state: 'visible', timeout: 15000 });
        await input.fill(term);
        await Promise.all([
            page.waitForResponse(
                r => r.url().includes('/rest/model/') && r.request().method() === 'POST',
                { timeout: 15000 }
            ).catch(() => null),
            input.press('Enter')
        ]);
        await page.waitForTimeout(500);
    }

    // A nav-button click kicks off async hops (pagination.new + update). Let them settle.
    async function settleNav(page) { await page.waitForTimeout(1200); }

    function noteOrderDialog(page) {
        return page.locator('div', { hasText: 'Set poem order' }).locator('h3', { hasText: 'Set poem order' });
    }

    // ── Bug A, path (i): path search box → ~/Data/Poems → pick a poem ────────────────────────────
    test('path (i): type ~/Data/Poems in the picker search box, then pick a poem', async ({ page }) => {
        await loginBrowser(page);
        await gotoChapBook(page);

        await addFromDataBtn(page).click();
        await expect(overlay(page)).toBeVisible({ timeout: 15000 });

        // Enter the sub-group path and press Enter — the picker navigates INTO ~/Data/Poems.
        await filterPicker(page, '~/Data/Poems');
        await settleNav(page);
        await page.screenshot({ path: 'test-results/dpn-i-after-path.png', fullPage: true });

        const row = pickerRow(page, POEM_A);
        await expect(row, 'poem row visible after path-search into ~/Data/Poems').toBeVisible({ timeout: 15000 });

        // Pick it: click the row (checks it), then the picker confirm (check) button.
        await row.first().click();
        await toolbarBtn(page, 'check').click();

        // Success signal: the note-order dialog appears carrying the poem name.
        await expect(page.locator('h3', { hasText: 'Set poem order' }))
            .toBeVisible({ timeout: 15000 });
        await expect(page.locator('li[class*="rounded"] span[title="' + POEM_A + '"]'))
            .toBeVisible({ timeout: 10000 });
        await page.screenshot({ path: 'test-results/dpn-i-picked.png', fullPage: true });
    });

    // ── Bug A, path (ii): group_work → select Poems → south_east → group_work → pick a poem ──────
    test('path (ii): group_work → select Poems → south_east → group_work → pick a poem', async ({ page }) => {
        await loginBrowser(page);
        await gotoChapBook(page);

        await addFromDataBtn(page).click();
        await expect(overlay(page)).toBeVisible({ timeout: 15000 });

        // Click "navigate by group" (group_work) → switch to group-browse of ~/Data → its subfolders.
        await toolbarBtn(page, 'group_work').click();
        await settleNav(page);
        await page.screenshot({ path: 'test-results/dpn-ii-after-groupwork.png', fullPage: true });

        const poemsFolder = pickerRow(page, 'Poems');
        await expect(poemsFolder, 'Poems folder visible in group-browse of ~/Data').toBeVisible({ timeout: 15000 });

        // Select the Poems folder (single click checks it) then click "navigate down" (south_east).
        await poemsFolder.first().click();
        await toolbarBtn(page, 'south_east').click();
        await settleNav(page);
        await page.screenshot({ path: 'test-results/dpn-ii-after-down.png', fullPage: true });

        // Click "navigate by group" again to return to the DATA list, now scoped to ~/Data/Poems.
        await toolbarBtn(page, 'group_work').click();
        await settleNav(page);
        await page.screenshot({ path: 'test-results/dpn-ii-back-to-data.png', fullPage: true });

        const row = pickerRow(page, POEM_A);
        await expect(row, 'poem row visible in the data list of ~/Data/Poems').toBeVisible({ timeout: 15000 });

        await row.first().click();
        await toolbarBtn(page, 'check').click();

        await expect(page.locator('h3', { hasText: 'Set poem order' }))
            .toBeVisible({ timeout: 15000 });
        await expect(page.locator('li[class*="rounded"] span[title="' + POEM_A + '"]'))
            .toBeVisible({ timeout: 10000 });
        await page.screenshot({ path: 'test-results/dpn-ii-picked.png', fullPage: true });
    });

    // ── Bug B: bg color + opacity persist AND render translucent in the reader ───────────────────
    test('bg color + opacity persist and render translucent (transparency not lost)', async ({ page, request }) => {
        // Distinctive, non-default color + a low opacity so the assertions are unambiguous.
        const BG_HEX = '#3366cc';
        const BG_OPACITY = 25;                       // percent
        const EXPECT_RGBA = 'rgba(51, 102, 204, 0.25)';

        await ensureSharedTestUser(request);
        await restLoginShared(request);
        const { groupId, organizationId } = await ensurePoemsGroup(request);
        const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
        const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
        const bookOid = await createChapBook(request, 'e2e-cb-opacity-' + Date.now(), 'E2E Opacity Book', [p1, p2], 4);

        const before = await bookPages(request, bookOid);
        expect(before.length, 'book has at least one page').toBeGreaterThanOrEqual(1);
        const scene0Oid = before[0].objectId;

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });

        // First scene card (its stanza textarea is rows=12; the prompt textarea is rows=3).
        const card = page.locator('div.rounded-lg.border')
            .filter({ has: page.locator('textarea[rows="12"]') }).first();
        await expect(card).toBeVisible({ timeout: 20000 });

        // Bg color = the SECOND input[type=color] on the card (Text color is the first).
        const bgColor = card.locator('input[type="color"]').nth(1);
        await expect(bgColor).toBeVisible();
        await bgColor.evaluate((el, v) => {
            el.value = v;
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
        }, BG_HEX);

        // Bg opacity slider — the range input in the style controls.
        const opacity = card.locator('input[type="range"]').first();
        await expect(opacity).toBeVisible();
        await opacity.evaluate((el, v) => {
            el.value = String(v);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
        }, BG_OPACITY);

        // Save this card.
        const saveBtn = card.locator('button[title="Save unsaved changes on this page"]');
        await expect(saveBtn).toBeEnabled({ timeout: 10000 });
        await saveBtn.click();

        // (Persistence) The /pages projection the reader loads must carry BOTH the chosen color AND
        // the chosen opacity — i.e. the transparency setting is not lost when a bg color is set.
        await expect.poll(async () => {
            const pages = await bookPages(request, bookOid);
            const pg = pages.find(p => p.objectId === scene0Oid) || pages[0];
            return pg ? (pg.pageBgColor + '|' + pg.pageBgOpacity) : null;
        }, { timeout: 20000, message: 'pageBgColor + pageBgOpacity persist on the /pages projection' })
            .toBe(BG_HEX + '|' + BG_OPACITY);

        // (Render) Open the reader; the stanza panel must render the chosen color TRANSLUCENT.
        await page.goto('/#!/chap-book/read/' + bookOid, { timeout: 30000 });
        const begin = page.locator('button:has-text("Begin")');
        await expect(begin).toBeVisible({ timeout: 20000 });
        await begin.click();

        const stanzaP = page.locator('p[style*="pre-wrap"]').first();
        await expect(stanzaP).toBeVisible({ timeout: 20000 });
        await page.screenshot({ path: 'test-results/dpn-b-reader.png', fullPage: true });

        // The panel is the stanza <p>'s parent div; its computed bg must be the chosen rgba (alpha<1).
        const panelBg = await stanzaP.evaluate(el => getComputedStyle(el.parentElement).backgroundColor);
        expect(panelBg, 'reader panel renders the chosen bg color at the chosen opacity').toBe(EXPECT_RGBA);
    });
});
