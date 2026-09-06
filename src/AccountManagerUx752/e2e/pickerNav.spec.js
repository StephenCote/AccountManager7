/**
 * Object-picker navigation E2E — REGRESSION guard for the ChapBook "Add Poems" / import-data picker.
 *
 * What this verifies: in the object-picker "Import Data" dialog the user CAN navigate UP to the
 * parent group and DOWN into a different child group, seeing each container's distinct contents.
 *
 * IMPORTANT (honest scope): the originally-reported "Bug B" — user stuck in ~/Notes, cannot go
 * up/down — does NOT reproduce against the current backend, and this test is NOT a
 * failing-then-passing proof of a fix. Investigation (2026-09-06, live am7test stack):
 *   - The claimed root cause was that pagination.js:354's am7client.get (minimal by-id GET) omits
 *     the virtual `path`, leaving pg.container pathless so navigateUp falls to "unhandled type".
 *   - That is FALSE for the picker's container, an auth.group: auth/groupModel.json declares
 *     "query":["type","path","organizationId"], so `path` is a DEFAULT query field and the minimal
 *     GET returns a path-bearing container. navigateUp's pre-existing group-contained branch
 *     (views/list.js) then handles UP/DOWN nav correctly WITHOUT any change.
 *   - Confirmed empirically: this test passes on the committed (unfixed) bundle, and a runtime
 *     probe showed the starting container header path = "/home/<user>/Notes" and UP-nav flipping
 *     to group-browse listing folders.
 * The views/list.js navigateUp picker-recovery block is retained because it fixes a *separate*
 * latent bug (a getFull(type,oid,callback) call whose callback getFull silently drops) and guards
 * the container-absent/load-failure race — but it is inert for this reported flow.
 *
 * Entry point: this test invokes the IDENTICAL production call ChapBook's openSourcePicker makes
 *   (features/chapBook.js:487  →  ObjectPicker.open({type:'data.note', multiSelect:true, ...})),
 *   via the globally-mounted picker exposed at window.am7page.components.picker (main.js:36,
 *   router.js:90). It is the real ObjectPicker + real views/list.js under test — not a mock.
 *   Driving it directly avoids the heavy/flaky ChapBook book-creation UI, which is orthogonal to
 *   the picker-navigation behavior.
 *
 * Requires the Docker test stack (am7test-am7-1) live on host :9443. Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pickerNav.spec.js \
 *       --project=chromium --workers=1
 *
 * 127.0.0.1 (NOT localhost) avoids the IPv6 ::1 resolution gotcha; the WebSocket is stubbed before
 * goto so nginx's cookie-stripped WS upgrade doesn't force a re-login redirect.
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, createNote, createObject } from './helpers/api.js';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://127.0.0.1:9443';
const REST = '/AccountManagerService7/rest';

// Unique per run so freshly-created records never collide with prior runs and each picker filter
// query key is new (so /rest/model/search's per-key cache never serves stale results).
const RUN = Date.now().toString(36);
const START_NOTE = 'PNStart_' + RUN;    // lives ONLY in ~/Notes
const FOLDER = 'PNFolder_' + RUN;       // a child group of home (~), sibling of Notes
const CHILD_NOTE = 'PNChild_' + RUN;    // lives ONLY in ~/PNFolder_<RUN>

let folderRec = null;

function sharedLoginBody() {
    return {
        schema: 'auth.credential',
        organizationPath: '/Development',
        name: 'e2etest_shared',
        credential: Buffer.from('password').toString('base64'),
        type: 'hashed_password'
    };
}

test.describe('Object picker — up/down navigation (regression)', () => {
    test.describe.configure({ timeout: 90000 });

    test.beforeAll(async ({ request }) => {
        // Ensure the shared test user + home directory exist (NEVER admin for the actual UI test).
        await ensureSharedTestUser(request);

        // Seed data AS the shared user, in its own logged-in context.
        const ctx = await pwRequest.newContext({ baseURL: BASE, ignoreHTTPSErrors: true });
        try {
            const login = await ctx.post(BASE + REST + '/login', { data: sharedLoginBody() });
            if (!login.ok() && login.status() !== 204) {
                throw new Error('shared-user API login failed: HTTP ' + login.status());
            }

            // (1) A note in the starting container ~/Notes.
            const start = await createNote(ctx, '~/Notes', START_NOTE, 'Bug B start note');
            expect(start && start.objectId, 'START_NOTE created').toBeTruthy();

            // (2) A child group of home (~), a sibling of Notes, that the picker can browse into.
            folderRec = await ensurePath(ctx, 'auth.group', 'data', '~/' + FOLDER);
            expect(folderRec && folderRec.id, 'FOLDER group created').toBeTruthy();

            // (3) A note that lives ONLY inside that child group.
            const child = await createObject(ctx, 'data.note', {
                name: CHILD_NOTE,
                groupId: folderRec.id,
                groupPath: folderRec.path,
                text: 'Bug B child note'
            });
            expect(child && child.objectId, 'CHILD_NOTE created').toBeTruthy();
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

    // Open the picker exactly as ChapBook does: ObjectPicker.open({type:'data.note', multiSelect:true}).
    async function openImportPicker(page) {
        await page.waitForFunction(
            () => window.am7page && window.am7page.components && window.am7page.components.picker,
            { timeout: 15000 }
        );
        await page.evaluate(() => {
            window.__pickerSelected = null;
            window.am7page.components.picker.open({
                type: 'data.note',
                title: 'Import Data',
                multiSelect: true,
                onSelect: function (items) { window.__pickerSelected = items; }
            });
        });
        await expect(page.locator('.am7-picker-overlay')).toBeVisible({ timeout: 15000 });
    }

    // Type a term into the picker's own filter and press Enter. The filter is Enter-triggered
    // (textField binds onkeydown; doFilter fires on e.which===13 — views/list.js:143,1124). Waits for
    // the resulting search round-trip + a redraw tick so the following assertion is deterministic.
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
        await page.waitForTimeout(400);
    }

    // A navigation click (up button / folder double-click) kicks off async hops in views/list.js:
    // navigateUp re-fetches the container via getFull (a /full GET), then navigateToPath (a search),
    // then pagination.update (another search) before the picker settles into the new container/mode.
    // Let those settle before filtering so the filter runs against the correct listContainerId/mode
    // rather than the pre-navigation one (navFilter leaks across the transition either way, but the
    // final filterPicker() re-issues the query for the container we care about).
    async function settleNav(page) {
        await page.waitForTimeout(1200);
    }

    function pickerRow(page, name) {
        return page.locator('.am7-picker-overlay tr.tabular-row', { hasText: name });
    }

    test('picker navigates UP to parent group and DOWN into a different child group', async ({ page }) => {
        await loginBrowser(page);
        await openImportPicker(page);

        const overlay = page.locator('.am7-picker-overlay');
        const upBtn = overlay.locator('button:has(span.material-symbols-outlined:text-is("north_west"))');

        // ── STEP 1: starting container is ~/Notes ───────────────────────────────────────────────
        await filterPicker(page, START_NOTE);
        await expect(pickerRow(page, START_NOTE), 'start note visible in ~/Notes').toBeVisible();
        // Cross-container check: the child note does NOT exist in ~/Notes.
        await filterPicker(page, CHILD_NOTE);
        await expect(pickerRow(page, CHILD_NOTE), 'child note absent from ~/Notes').toHaveCount(0);

        // ── STEP 2: navigate UP → parent group (home), group-browse mode lists folders ───────────
        // Verified to work on the committed backend: the container (auth.group) carries `path`, so
        // navigateUp's group-contained branch drills to the parent and flips to group-browse mode,
        // where the FOLDER row appears. (This step was the alleged Bug B failure; it does not fail.)
        await expect(upBtn, 'up button present').toBeVisible();
        await upBtn.click();
        await settleNav(page);
        await filterPicker(page, FOLDER);
        await expect(pickerRow(page, FOLDER), 'child FOLDER visible after UP into home group-browse')
            .toBeVisible({ timeout: 15000 });

        // ── STEP 3: navigate DOWN into the child group → its own contents ────────────────────────
        await pickerRow(page, FOLDER).first().dblclick();
        await settleNav(page);
        await filterPicker(page, CHILD_NOTE);
        await expect(pickerRow(page, CHILD_NOTE), 'child note visible inside child group (DOWN)')
            .toBeVisible({ timeout: 15000 });
        // Cross-container identity check: the start note is NOT in the child group.
        await filterPicker(page, START_NOTE);
        await expect(pickerRow(page, START_NOTE), 'start note absent from child group').toHaveCount(0);

        // ── STEP 4: navigate UP again → back to parent (home), folder visible once more ──────────
        await expect(upBtn, 'up button present in child group').toBeVisible();
        await upBtn.click();
        await settleNav(page);
        await filterPicker(page, FOLDER);
        await expect(pickerRow(page, FOLDER), 'FOLDER visible again after UP from child group')
            .toBeVisible({ timeout: 15000 });
    });
});
