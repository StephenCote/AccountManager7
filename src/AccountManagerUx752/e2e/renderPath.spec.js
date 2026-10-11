/**
 * P1-8 — the boot-pinned PictureBook V3 render-path switch (design §28.1 Stage 1), live against the
 * Docker test stack, which docker-compose.test.yml boots with BOTH products on v3:
 *
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/renderPath.spec.js --workers=1 --project=chromium
 *
 * What is proven here, as the shared non-admin user:
 *   1. GET /rest/config/render-path reports { picturebook, chapbook } and the test stack is v3/v3
 *      (so every other Phase 1 spec is really exercising V3, not silently being refused);
 *      an unauthenticated call is not served.
 *   2. A STORY book and a CHAPBOOK book the shared user owns are ADMITTED past the switch: a V3 route
 *      answers (auto-approve 200) and an unknown run id gets the 404 behind the switch, never the
 *      "render path is v1" 409.
 *   3. The admin Feature Configuration page shows both values read-only (as the AccountAdministrators
 *      -role TEST user, never the admin account).
 *
 * The v1 refusal itself cannot be produced against a running stack without restarting it on a
 * different environment; that side is covered by Service7's TestPbRenderPathRefusal (H2) and Objects7's
 * TestPbRenderPath (am7db), which flip the process-global switch in-JVM.
 *
 * Fixtures are find-or-create by stable slug so the olio world behind each book is created at most once.
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, ensureAdminRoleTestUser } from './helpers/api.js';
import { ensurePoemsGroup, ensurePoem, createChapBook, POEM_1, POEM_2 } from './helpers/chapbook.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const STORY_SLUG = 'rp-spec-story';
const CHAPBOOK_SLUG = 'rp-spec-chapbook';
const REFUSAL = 'render path is v1';

test.describe.configure({ mode: 'serial' });

let shared = null;
let orgId = null;
let storyBookOid = null;
let chapBookOid = null;

async function apiLogin(request, name, password) {
    await request.get(REST + '/logout');
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name,
            credential: Buffer.from(password).toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'API login failed for ' + name + ': ' + resp.status()).toBeTruthy();
}

async function findBookBySlug(request, slug) {
    const s = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.pb.book',
            fields: [
                { name: 'slug', comparator: 'equals', value: slug },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: ['id', 'objectId', 'name', 'slug', 'bookType'], recordCount: 1, cache: false
        }
    });
    const b = await s.json().catch(() => null);
    return (b && b.results && b.results.length) ? b.results[0] : null;
}

test.describe('P1-8 render-path switch — live', () => {

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await apiLogin(request, shared.testUserName, shared.testPassword);
        // There is no /login/principal route; the shared user's organizationId rides on the ~/Poems
        // directory (path/make), which the CHAPBOOK fixture needs anyway.
        const poems = await ensurePoemsGroup(request);
        orgId = poems.organizationId;
        expect(typeof orgId, 'organizationId from ~/Poems path/make').toBe('number');

        // STORY book: POST /chapter with no series creates a standalone PB2 (olio.pb.book, bookType STORY)
        // with no LLM involved — the same call pictureBookWorkflowOverhaul.spec.js step 3 makes.
        let story = await findBookBySlug(request, STORY_SLUG);
        if (!story) {
            const r = await request.post(PB + '/chapter', { data: { slug: STORY_SLUG, title: 'RP Spec Story' }, timeout: 120000 });
            expect(r.ok(), 'POST /chapter (STORY fixture): ' + r.status() + ' ' + await r.text()).toBe(true);
            const c = await r.json();
            storyBookOid = c.bookObjectId || c.objectId;
        } else {
            storyBookOid = story.objectId;
        }
        expect(storyBookOid, 'STORY fixture objectId').toBeTruthy();

        let chap = await findBookBySlug(request, CHAPBOOK_SLUG);
        if (!chap) {
            const p1 = await ensurePoem(request, poems.groupId, orgId, 'rp-spec-poem-1', 'Falling Leaves (RP)', POEM_1);
            const p2 = await ensurePoem(request, poems.groupId, orgId, 'rp-spec-poem-2', 'Winter (RP)', POEM_2);
            chapBookOid = await createChapBook(request, CHAPBOOK_SLUG, 'RP Spec ChapBook', [p1, p2], 20);
        } else {
            chapBookOid = chap.objectId;
        }
        expect(chapBookOid, 'CHAPBOOK fixture objectId').toBeTruthy();
        await request.get(REST + '/logout');
        console.log('[renderPath] story =', storyBookOid, '| chapbook =', chapBookOid);
    });

    test('GET /rest/config/render-path reports v3/v3 on the test stack and needs a principal', async ({ request }) => {
        await apiLogin(request, shared.testUserName, shared.testPassword);
        const r = await request.get(REST + '/config/render-path');
        expect(r.status(), 'render-path status (a 404 means the Service7 under test predates P1-8)').toBe(200);
        const body = await r.json();
        expect(body).toEqual({ picturebook: 'v3', chapbook: 'v3' });
        await request.get(REST + '/logout');

        // No session at all: a fresh context must not be served the switch.
        const anon = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            const a = await anon.get(REST + '/config/render-path');
            expect([401, 403], 'unauthenticated render-path must be refused, got ' + a.status()).toContain(a.status());
        } finally {
            await anon.dispose();
        }
    });

    test('a STORY book is admitted past the picturebook switch (no "render path is v1" 409)', async ({ request }) => {
        await apiLogin(request, shared.testUserName, shared.testPassword);
        const fixture = await findBookBySlug(request, STORY_SLUG);
        expect(fixture && String(fixture.bookType).toLowerCase(), 'fixture bookType').toBe('story');

        const aa = await request.get(PB + '/' + storyBookOid + '/settings/auto-approve');
        const aaText = await aa.text();
        expect(aa.status(), 'auto-approve GET: ' + aaText).toBe(200);
        const aaBody = JSON.parse(aaText);
        expect(aaBody.bookObjectId).toBe(storyBookOid);
        expect(typeof aaBody.autoApprove).toBe('boolean');

        const run = await request.get(PB + '/' + storyBookOid + '/run/no-such-run-' + Date.now().toString(36));
        const runText = await run.text();
        expect(run.status(), 'unknown run id under v3 reaches the 404 behind the switch: ' + runText).toBe(404);
        expect(runText).not.toContain(REFUSAL);

        // A book straight from POST /chapter has no workflow yet, so the queue is either the empty
        // list (200) or the facade's own "no workflow yet" 404 — both are behind the switch.
        const q = await request.get(PB + '/' + storyBookOid + '/review-queue');
        const qText = await q.text();
        expect([200, 404], 'review-queue under v3: ' + qText).toContain(q.status());
        expect(qText).not.toContain(REFUSAL);
        if (q.status() === 404) expect(qText).toContain('no workflow yet');
        await request.get(REST + '/logout');
    });

    test('a CHAPBOOK book is admitted past the chapbook switch (no "render path is v1" 409)', async ({ request }) => {
        await apiLogin(request, shared.testUserName, shared.testPassword);
        const fixture = await findBookBySlug(request, CHAPBOOK_SLUG);
        expect(fixture && String(fixture.bookType).toLowerCase(), 'fixture bookType').toBe('chapbook');

        const aa = await request.get(PB + '/' + chapBookOid + '/settings/auto-approve');
        const aaText = await aa.text();
        expect(aa.status(), 'auto-approve GET: ' + aaText).toBe(200);
        expect(JSON.parse(aaText).bookObjectId).toBe(chapBookOid);

        const run = await request.get(PB + '/' + chapBookOid + '/run/no-such-run-' + Date.now().toString(36));
        const runText = await run.text();
        expect(run.status(), 'unknown run id under v3: ' + runText).toBe(404);
        expect(runText).not.toContain(REFUSAL);
        await request.get(REST + '/logout');
    });

    test('the Feature Configuration page shows both products read-only', async ({ page, request }) => {
        const featAdmin = await ensureAdminRoleTestUser(request);
        expect(featAdmin.user && featAdmin.user.objectId, 'admin-role test user').toBeTruthy();
        expect(featAdmin.roleAssigned, 'AccountAdministrators role on the test user').toBe(true);

        await login(page, { user: featAdmin.testUserName, password: featAdmin.testPassword });
        await page.waitForFunction(
            () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
            { timeout: 30000 }
        );
        await page.evaluate(() => window.m.route.set('/admin/features'));
        await expect(page.locator('h2').filter({ hasText: 'Feature Configuration' })).toBeVisible({ timeout: 15000 });

        const pb = page.locator('[data-testid="render-path-picturebook"]');
        const cb = page.locator('[data-testid="render-path-chapbook"]');
        await expect(pb).toHaveAttribute('data-value', 'v3', { timeout: 15000 });
        await expect(cb).toHaveAttribute('data-value', 'v3');
        await expect(pb).toContainText('v3 (graph)');
        await expect(cb).toContainText('v3 (graph)');
        // Read-only: no control inside the section.
        const section = page.locator('[data-testid="render-path"]');
        await expect(section).toBeVisible();
        expect(await section.locator('button, input, select').count()).toBe(0);
        await section.scrollIntoViewIfNeeded();
        await section.screenshot({ path: 'test-results/renderPath-admin-chips.png' });
    });
});
