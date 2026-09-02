/**
 * ChapBook DELETE regression — proves the reported "still can't delete failed/incomplete picturebook"
 * bug is fixed in the ACTUAL RUNNING Docker application, through the REAL delete path (not a JUnit).
 *
 * The regression state (the exact undeletable book):
 *   Every olio.pb.book is owned by the OLIO PRINCIPAL ("olioUser"). A ChapBook whose world creation
 *   FAILED mid-flight (createBook throws after writing the book row, before the creator's grants) never
 *   got the creator's grants, so the creator can NEVER read it through AccessPoint (by-id GET → 404) —
 *   yet it STILL appears in the creator's org /books list, because ChapBookUtil.listChapBooks uses
 *   AccessPoint.list (org-scoped, NOT per-record filtered). That is the permanently-undeletable book.
 *
 * The fix: ChapBookUtil.deleteChapBook, when the grant-gated readBook() returns null, calls
 *   PictureBookUtil.deleteIncompleteBookAsOlio(user, bookObjectId, orgId), which re-resolves the row AS
 *   the olio principal, enforces a creator/orphan+incomplete guard (403 for a stranger), and deletes it
 *   as olio. So the creator can finally delete it, and a stranger still cannot.
 *
 * Reproduction is fixture-only (admin) because the failed-mid-flight state cannot be produced through
 * normal UX/REST — this mirrors the JUnit fixture. The DELETE ASSERTED ON goes through the real path as
 * the CREATOR (never admin, never a raw AccessPoint call):
 *   Test 1 drives the UX delete affordance (row's red delete button → confirm dialog → real REST DELETE),
 *          with BEFORE (present) / AFTER (gone) screenshots + raw-path before/after contrast.
 *   Test 2 is the guard: a DIFFERENT same-org user's REST DELETE → 403 and the book survives; then the
 *          creator's real REST DELETE succeeds (proving the guard blocks only the stranger).
 *
 * Run against the Docker stack (host 9443, 127.0.0.1 required — localhost resolves to IPv6 ::1 which
 * Docker does not map):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookDeleteRegression.spec.js \
 *     --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, setupTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const RESULTS = path.resolve(SPEC_DIR, '../test-results');
const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

function b64(s) { return Buffer.from(s).toString('base64'); }
function encPath(p) { return 'B64-' + b64(p).replace(/=/g, '%3D'); }

// Shared state seeded in beforeAll.
let orgId = null;        // numeric organizationId of /Development on the Docker stack
let olioId = null;       // numeric id of the olio principal ("olioUser") — the book's real owner
let adminGroupId = null; // admin's ~/Data group id — where the repro book row is created
let sharedUserOid = null;// objectId of the creator (e2etest_shared)
let stranger = null;     // { user, testUserName, testPassword } — a same-org NON-creator user

async function restLogin(request, name, password) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name, credential: b64(password), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'REST login failed for ' + name).toBe(true);
}
async function restLogout(request) { await request.get(REST + '/logout'); }

// WebSocket stub — Docker's nginx strips the session cookie on the WS upgrade, so Tomcat closes it,
// which triggers forceLogin() → redirect to #!/sig. Stub it BEFORE goto (addInitScript runs on every
// navigation, including reload).
function wsStub() {
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

async function loginAndLoad(page, name, password) {
    await page.context().clearCookies();
    await restLogin(page.request, name, password);
    await page.addInitScript(wsStub);
    await page.goto('about:blank');
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

/**
 * Provision the EXACT undeletable state, AS ADMIN (fixture-only): a CHAPBOOK olio.pb.book, attributed to
 * `creatorObjectId`, world-creation incomplete, owned by the olio principal (so it is unreadable by the
 * creator through AccessPoint yet resolvable by the olio principal — the state the fix must handle).
 * Returns { id, objectId }.
 */
async function provisionUndeletableBook(request, slug, creatorObjectId) {
    await restLogin(request, 'admin', 'password');
    // 1. Create a CHAPBOOK book row in admin's ~/Data group, attributed to the creator, world incomplete.
    const createResp = await request.post(REST + '/model', {
        data: {
            schema: 'olio.pb.book', name: slug, slug,
            groupId: adminGroupId, bookType: 'CHAPBOOK', bookStatus: 'DRAFT',
            createdByObjectId: creatorObjectId
        }
    });
    expect(createResp.ok(), 'provision create failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
    const created = await createResp.json();
    const bookId = created.id, bookOid = created.objectId;
    expect(bookOid && bookId, 'no id/objectId from provision create').toBeTruthy();
    // 2. Re-own to the olio principal — the row is now olio-owned (undeletable via the creator's grants)
    //    yet readable by the olio principal, which the fix's find(olioUser) requires.
    const patchResp = await request.fetch(REST + '/model', {
        method: 'PATCH',
        data: { schema: 'olio.pb.book', id: bookId, objectId: bookOid, name: slug, ownerId: olioId }
    });
    expect(patchResp.ok(), 'provision owner-patch failed: ' + patchResp.status() + ' ' + await patchResp.text()).toBe(true);
    await restLogout(request);
    return { id: bookId, objectId: bookOid };
}

/** The creator's org /books list (as the given user). */
async function booksListAs(request, name, password) {
    await restLogin(request, name, password);
    const resp = await request.get(CB_REST + '/books');
    const arr = await resp.json().catch(() => []);
    await restLogout(request);
    return Array.isArray(arr) ? arr : [];
}

/** By-id GET status as the given user (404 = the creator cannot read the olio-owned row). */
async function byIdStatusAs(request, name, password, bookOid) {
    await restLogin(request, name, password);
    const resp = await request.get(REST + '/model/olio.pb.book/' + bookOid);
    const st = resp.status();
    await restLogout(request);
    return st;
}

/** Ground truth: number of DB rows for the book (admin, cache:false). 1 = exists, 0 = gone. */
async function dbCount(request, bookOid) {
    await restLogin(request, 'admin', 'password');
    const resp = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.pb.book',
            fields: [{ name: 'objectId', comparator: 'equals', value: bookOid }],
            request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
        }
    });
    const body = await resp.json().catch(() => null);
    await restLogout(request);
    return (body && body.results) ? body.results.length : 0;
}

test.describe('ChapBook — DELETE regression (incomplete/failed book is deletable through the real path)', () => {
    test.describe.configure({ timeout: 120000, mode: 'serial' });

    test.beforeAll(async ({ request }) => {
        const shared = await ensureSharedTestUser(request);
        sharedUserOid = shared.user.objectId;
        expect(sharedUserOid, 'could not resolve shared user objectId').toBeTruthy();

        // A different same-org user (NOT the creator) for the 403 guard. noteCount:0 keeps it minimal.
        stranger = await setupTestUser(request, { suffix: 'cbdelstranger', noteCount: 0 });
        expect(stranger.user && stranger.user.objectId, 'could not provision stranger user').toBeTruthy();

        await restLogin(request, 'admin', 'password');
        // admin's ~/Data group (holds the repro row) + the org id.
        const dataDir = await request.get(REST + '/path/make/auth.group/data/' + encPath('~/Data'));
        const dataBody = await dataDir.json();
        adminGroupId = dataBody && dataBody.id;
        orgId = dataBody && dataBody.organizationId;
        expect(adminGroupId && orgId, 'could not resolve admin ~/Data group/org').toBeTruthy();

        // Resolve the olio principal's numeric id (the book's legitimate owner).
        const olioSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'system.user',
                fields: [
                    { name: 'name', comparator: 'equals', value: 'olioUser' },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const olioBody = await olioSearch.json();
        olioId = olioBody.results && olioBody.results[0] && olioBody.results[0].id;
        expect(olioId, 'could not resolve olioUser id').toBeTruthy();
        await restLogout(request);

        fs.mkdirSync(RESULTS, { recursive: true });
        console.log('[setup] orgId=' + orgId + ' olioId=' + olioId + ' adminGroupId=' + adminGroupId
            + ' creator=' + sharedUserOid + ' stranger=' + stranger.user.objectId);
    });

    // ── Test 1: the creator deletes the undeletable ChapBook through the UX; BEFORE present, AFTER gone ──
    test('creator deletes an incomplete/undeletable ChapBook via the UX — before present, after gone', async ({ page, request }) => {
        // '000-' prefix sorts it to the top of the name-ascending /books list so the UX row is on page 1.
        const slug = '000-cbdel-ux-' + Date.now().toString(36);
        const book = await provisionUndeletableBook(request, slug, sharedUserOid);
        console.log('[test1] provisioned undeletable book ' + book.objectId + ' slug=' + slug);

        // ── BEFORE (raw path, as the CREATOR): unreadable by id (404) yet present in the creator /books list.
        const beforeStatus = await byIdStatusAs(request, 'e2etest_shared', 'password', book.objectId);
        expect(beforeStatus, 'creator by-id GET should be 404 BEFORE (olio-owned, no grants)').toBe(404);
        const beforeList = await booksListAs(request, 'e2etest_shared', 'password');
        expect(beforeList.some(b => b.objectId === book.objectId),
            'book should be PRESENT in creator /books BEFORE delete').toBe(true);
        expect(await dbCount(request, book.objectId), 'book should exist in DB BEFORE delete').toBe(1);
        console.log('[test1] BEFORE: by-id=404, present in /books=true, dbCount=1');

        // ── UX: log in as the creator, open the ChapBook list, confirm the row, capture BEFORE screenshot.
        await loginAndLoad(page, 'e2etest_shared', 'password');
        await page.evaluate(() => { window.location.hash = '!/chap-book'; });
        const row = page.locator('div.space-y-2 > div', { hasText: slug });
        await expect(row.first(), 'undeletable book row not visible in UX list BEFORE delete').toBeVisible({ timeout: 20000 });
        // Scroll the "My ChapBooks" section + target row into view so the BEFORE screenshot is self-contained.
        await row.first().scrollIntoViewIfNeeded();
        await page.screenshot({ path: path.join(RESULTS, 'cbdel-BEFORE-present.png') });

        // ── Delete through the REAL UX affordance: the row's red delete button → Dialog.confirm → destructive.
        //    doDeleteBook() → deleteBook() issues the real REST DELETE (fetch DELETE /olio/chap-book/{oid}).
        await row.first().locator('button').last().click();
        const confirmBtn = page.locator('button.am7-dialog-btn-destructive');
        await expect(confirmBtn, 'delete confirm dialog did not open').toBeVisible({ timeout: 10000 });
        await confirmBtn.click();

        // The row must disappear after the delete + loadMyBooks() refresh.
        await expect(page.locator('div.space-y-2 > div', { hasText: slug }),
            'book row STILL present in UX list AFTER delete').toHaveCount(0, { timeout: 20000 });
        // Scroll the "My ChapBooks" heading into view so the AFTER screenshot shows the same section, minus the book.
        await page.locator('h3:has-text("My ChapBooks")').scrollIntoViewIfNeeded().catch(() => {});
        await page.screenshot({ path: path.join(RESULTS, 'cbdel-AFTER-gone.png') });

        // ── AFTER (raw path): creator by-id still 404, ABSENT from a fresh creator /books list, GONE from DB.
        const afterStatus = await byIdStatusAs(request, 'e2etest_shared', 'password', book.objectId);
        expect(afterStatus, 'creator by-id GET should be 404 AFTER delete').toBe(404);
        const afterList = await booksListAs(request, 'e2etest_shared', 'password');
        expect(afterList.some(b => b.objectId === book.objectId),
            'book should be ABSENT from creator /books AFTER delete').toBe(false);
        expect(await dbCount(request, book.objectId), 'book should be GONE from DB AFTER delete').toBe(0);
        console.log('[test1] AFTER: by-id=404, absent from /books, dbCount=0 — deleted through the UX affordance');
    });

    // ── Test 2: guard — a DIFFERENT same-org user cannot delete it (403); it survives; creator then can ──
    test('a different same-org user cannot delete the incomplete ChapBook (403); creator still can', async ({ request }) => {
        const slug = '000-cbdel-guard-' + Date.now().toString(36);
        const book = await provisionUndeletableBook(request, slug, sharedUserOid);
        console.log('[test2] provisioned undeletable book ' + book.objectId + ' slug=' + slug);

        // Stranger (same org, NOT the creator) attempts the REAL REST DELETE → 403; book survives.
        await restLogin(request, stranger.testUserName, stranger.testPassword);
        const delResp = await request.delete(CB_REST + '/' + book.objectId);
        const delStatus = delResp.status();
        const delBody = await delResp.text();
        await restLogout(request);
        expect(delStatus, 'stranger DELETE should be 403; got ' + delStatus + ' ' + delBody).toBe(403);
        expect(delBody, 'stranger 403 body should carry the guard message')
            .toContain('Not authorized to delete this book');
        expect(await dbCount(request, book.objectId), 'book MUST survive the stranger 403').toBe(1);
        console.log('[test2] stranger DELETE → 403 "' + delBody + '"; book survives (dbCount=1)');

        // The CREATOR can still delete it via the real REST DELETE → 200 {"deleted":true}; book gone.
        await restLogin(request, 'e2etest_shared', 'password');
        const ownerDel = await request.delete(CB_REST + '/' + book.objectId);
        const ownerStatus = ownerDel.status();
        const ownerBody = await ownerDel.text();
        await restLogout(request);
        expect(ownerStatus, 'creator DELETE should be 200; got ' + ownerStatus + ' ' + ownerBody).toBe(200);
        expect(ownerBody, 'creator delete should return deleted:true').toContain('"deleted":true');
        expect(await dbCount(request, book.objectId), 'book MUST be gone after the creator delete').toBe(0);
        console.log('[test2] creator DELETE → 200 ' + ownerBody + '; book gone (dbCount=0)');
    });
});
