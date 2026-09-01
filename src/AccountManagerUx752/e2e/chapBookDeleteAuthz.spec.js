/**
 * ChapBook DELETE authorization — REST integration proofs for the hardening in
 * ChapBookUtil.deleteChapBook + ChapBookService DELETE /{bookObjectId}.
 *
 * The change under test: deleteChapBook now performs an explicit PBAC check
 * (AuthorizationUtil.canDelete) BEFORE deleting, so a delete denial surfaces as
 * HTTP 403 instead of the bare AccessPoint.delete false-return the transport
 * layer would otherwise map to 500. The method also owns the 404 (not found) and
 * 403 (exists but not a CHAPBOOK) distinctions.
 *
 * These are REAL round-trips against the live Service7 stack — every assertion is
 * on the actual HTTP status and JSON body returned by the running backend.
 *
 * Run against the Docker stack (Windows: use 127.0.0.1, not localhost — IPv6 ::1
 * is not mapped by Docker):
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookDeleteAuthz.spec.js --workers=1 --project=chromium
 *
 * No LLM or SD backend is touched, so this runs in the default suite.
 *
 * ── Case coverage & reachability (stated honestly) ─────────────────────────────
 *   Case 1 — 404 not found ................. COVERED (random objectId)
 *   Case 2 — 403 exists-but-not-CHAPBOOK ... COVERED (non-CHAPBOOK olio.pb.book the user owns)
 *   Case 3 — 200 owner delete + gone ....... COVERED (real CHAPBOOK via /create, then re-read empty)
 *   Case 4 — 403 canDelete DENIED .......... NOT reachable via the REST surface with non-admin
 *            users, and NOT faked. A PB2 book row is owned by the olio principal; a *distinct*
 *            second user cannot even READ another user's book (PbBookUtil.readBook → AccessPoint.find
 *            by-identity → null → the endpoint returns 404, not 403 — see TestPbSecurity#case01).
 *            The only way to make a book readable-but-not-deletable by a second user is to enrol them
 *            in that book's per-book Writer role, which requires holding the book ADMIN role
 *            (TestPbSecurity#case05); nothing auto-grants Admin and it is not obtainable through
 *            fresh-user creation without the admin account. So the DENIED branch has no deterministic
 *            REST construction here. Case 3 is the direct evidence that the canDelete PERMIT branch
 *            works for the authorized holder: the CHAPBOOK is olio-principal-owned yet the creator
 *            (per-book Writer/Admin role) gets 200, proving canDelete returns PERMIT for the right
 *            actor. The DENY branch is best exercised by an Objects7 JUnit test calling
 *            deleteChapBook directly with a user enrolled read-only via admin provisioning.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

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

test.describe('ChapBook DELETE authorization', () => {
    test.describe.configure({ timeout: 120000 });

    let orgId = null;
    let dataGroupId = null;
    let poemsGroupId = null;
    let seedPoemObjectId = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLoginShared(request);

        // Home ~/Data group — where the case-2 non-CHAPBOOK book is created (user-owned).
        const dataDir = await request.get(REST + '/path/make/auth.group/data/B64-' +
            Buffer.from('~/Data').toString('base64').replace(/=/g, '%3D'));
        const dataBody = await dataDir.json();
        expect(dataBody && dataBody.id, 'could not ensure ~/Data group').toBeTruthy();
        dataGroupId = dataBody.id;
        orgId = dataBody.organizationId;

        // Home ~/Poems group — for the case-3 CHAPBOOK poem source.
        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' +
            Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
        const poemsBody = await poemsDir.json();
        expect(poemsBody && poemsBody.id, 'could not ensure ~/Poems group').toBeTruthy();
        poemsGroupId = poemsBody.id;

        // Seed one real poem so /create has content to chunk into scenes.
        const poemName = 'del-authz-poem-' + Date.now().toString(36);
        const poemResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.cb.poem',
                name: poemName,
                title: 'Delete Authz Poem',
                author: 'Stephen W. Cote',
                groupId: poemsGroupId,
                text: 'Outside, all is pristine,\nFrom cobalt skies of charcoal unity\nBorn of spells and sorcery.'
            }
        });
        expect(poemResp.ok(), 'poem seed failed: ' + poemResp.status()).toBe(true);
        const poem = await poemResp.json();
        seedPoemObjectId = poem && poem.objectId;
        expect(seedPoemObjectId, 'seeded poem has no objectId').toBeTruthy();

        await request.get(REST + '/logout');
    });

    // ── Case 1: 404 — deleting a nonexistent bookObjectId ─────────────────────
    test('Case 1: DELETE a nonexistent bookObjectId returns 404', async ({ request }) => {
        await restLoginShared(request);

        // A syntactically valid but nonexistent objectId (matches the path regex [0-9A-Za-z-]+).
        const missing = '11111111-2222-3333-4444-555555555555';
        const resp = await request.delete(CB_REST + '/' + missing);
        expect(resp.status(), 'expected 404 for a nonexistent book').toBe(404);
        const body = await resp.json();
        expect(JSON.stringify(body)).toContain('not found');

        await request.get(REST + '/logout');
    });

    // ── Case 2: 403 — the book exists and the user can read it, but it is not a CHAPBOOK ──
    test('Case 2: DELETE a non-CHAPBOOK olio.pb.book the user owns returns 403', async ({ request }) => {
        await restLoginShared(request);

        // Create a plain olio.pb.book (bookType != CHAPBOOK) directly in the user's own ~/Data
        // group. The user OWNS it, so PbBookUtil.readBook finds it (passes the 404 gate); the
        // bookType check then rejects it. Because it is user-owned, canDelete would PERMIT — proving
        // the 403 comes from the bookType gate, not an authorization denial.
        const bookName = 'notchap-' + Date.now().toString(36);
        const mkResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.book', name: bookName, groupId: dataGroupId, bookType: 'picturebook' }
        });
        expect(mkResp.ok(), 'non-CHAPBOOK book create failed: ' + mkResp.status()).toBe(true);
        const mk = await mkResp.json();
        const notChapOid = mk && mk.objectId;
        expect(notChapOid, 'created book has no objectId').toBeTruthy();

        const resp = await request.delete(CB_REST + '/' + notChapOid);
        expect(resp.status(), 'expected 403 for a non-CHAPBOOK book').toBe(403);
        const body = await resp.json();
        expect(JSON.stringify(body)).toContain('is not a CHAPBOOK');

        // The 403 must NOT have deleted it — confirm it is still present, then clean it up via the
        // generic model route (the user owns it).
        const stillThere = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.book',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: notChapOid },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const stBody = await stillThere.json();
        expect(stBody && Array.isArray(stBody.results) && stBody.results.length === 1,
            'a refused (403) delete must leave the book intact').toBe(true);

        await request.delete(REST + '/model/olio.pb.book/' + notChapOid).catch(() => {});
        await request.get(REST + '/logout');
    });

    // ── Case 3: 200 — the owner deletes a real CHAPBOOK, and it is gone afterward ──
    test('Case 3: owner DELETE of a CHAPBOOK returns 200 and the book is gone', async ({ request }) => {
        await restLoginShared(request);
        expect(seedPoemObjectId, 'seed poem not available from beforeAll').toBeTruthy();

        // Create a real CHAPBOOK via the feature endpoint (bookType=CHAPBOOK, PB2 world bootstrap).
        const slug = 'del-authz-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Delete Authz ChapBook', poemObjectIds: [seedPoemObjectId], maxLinesPerPage: 8 }
        });
        expect(createResp.ok(), 'create CHAPBOOK failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        const bookOid = created && (created.bookObjectId || created.objectId);
        expect(bookOid, 'no bookObjectId in create response').toBeTruthy();

        // Owner delete — the book row is olio-principal-owned, so a 200 here proves canDelete
        // returns PERMIT for the creator via the per-book role grant (the hardening's PERMIT branch).
        const delResp = await request.delete(CB_REST + '/' + bookOid);
        expect(delResp.status(), 'owner delete of own CHAPBOOK must be 200 — a 403 here would be a real'
            + ' bug (authorized holder denied)').toBe(200);
        const delBody = await delResp.json();
        expect(delBody).toEqual({ deleted: true });

        // Follow-up read must be empty — the book is actually gone.
        const afterResp = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.book',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: bookOid },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const afterBody = await afterResp.json();
        const remaining = (afterBody && afterBody.results) || [];
        expect(remaining.length, 'the deleted CHAPBOOK must not be found by a fresh (cache:false) read').toBe(0);

        // And a repeat delete now returns 404 (it is gone).
        const reDel = await request.delete(CB_REST + '/' + bookOid);
        expect(reDel.status(), 'deleting an already-deleted CHAPBOOK must be 404').toBe(404);

        await request.get(REST + '/logout');
    });
});
