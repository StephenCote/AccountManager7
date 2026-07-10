/**
 * PageIndex (Tier 3 Ux752) — live UI tests against the running stack (Vite + Service7/Tomcat).
 * Uses the shared persistent test user (ensureSharedTestUser — NEVER admin).
 *
 * Two describes:
 *  1. Empty-state + confirm-dialog wiring — fully deterministic, no LLM call actually fired
 *     (the build confirm dialog is cancelled rather than confirmed).
 *  2. Build + tree render + ranked query — hits the LLM (summarization) via PageIndexUtil.
 *     GATED behind PAGEINDEX_E2E=1 and run single-threaded so the default 4-worker suite never
 *     fires parallel runs at the DGX Spark. Run it explicitly:
 *        PAGEINDEX_E2E=1 npx playwright test e2e/pageIndex.spec.js -g "build an index" --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, createNote, createObject, ensurePath } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }

async function loginCtx(ctx, shared) {
    await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password'
        }
    });
}

async function attachSharedSession(page, shared) {
    let ctx = await newCtx();
    await loginCtx(ctx, shared);
    let cookies = (await ctx.storageState()).cookies;
    await page.context().addCookies(cookies);
    await ctx.dispose();
}

test.describe('PageIndex — empty state + build dialog (no LLM)', () => {
    let shared, note;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);
        let ctx = await newCtx();
        await loginCtx(ctx, shared);
        note = await createNote(ctx, '~/Notes', 'pageIndexEmpty-' + Date.now(),
            'PageIndex UI test fixture — empty-state note, never built.');
        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test.beforeEach(async ({ page }) => {
        await attachSharedSession(page, shared);
    });

    test('PageIndex tab shows empty state with a Build Index affordance, and the confirm dialog opens', async ({ page }) => {
        test.skip(!note || !note.objectId, 'Note fixture was not created');

        await page.goto('/#!/view/data.note/' + note.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/data.note/'), { timeout: 15000 });

        let tab = page.locator('button:has-text("PageIndex")');
        await expect(tab).toBeVisible({ timeout: 15000 });
        await tab.click();

        await expect(page.getByText('No PageIndex has been built for this document yet.')).toBeVisible({ timeout: 10000 });
        let buildBtn = page.locator('button:has-text("Build Index")');
        await expect(buildBtn).toBeVisible();

        // Opens the confirm dialog — cancel it rather than confirm, so this test never fires the
        // real LLM-backed build call (that path is covered, gated, in the describe below).
        await buildBtn.click();
        await expect(page.locator('text=Build PageIndex')).toBeVisible({ timeout: 5000 });
        await page.locator('.am7-dialog-footer button:has-text("Cancel")').click();
        await expect(page.locator('text=Build PageIndex')).toHaveCount(0);

        // Cancelling must not have built anything — empty state still shown.
        await expect(page.getByText('No PageIndex has been built for this document yet.')).toBeVisible();
    });

    test('chat config editor exposes the usePageIndex toggle', async ({ page }) => {
        // Create (or reuse) a chatConfig owned by the shared test user directly via REST —
        // avoids depending on list-UI navigation and never touches admin.
        let ctx = await newCtx();
        await loginCtx(ctx, shared);
        let dir = await ensurePath(ctx, 'auth.group', 'data', '~/Chat');
        test.skip(!dir || !dir.id, 'Could not resolve ~/Chat directory for shared test user');
        let cfg = dir && dir.id ? await createObject(ctx, 'olio.llm.chatConfig', {
            groupId: dir.id, groupPath: dir.path, name: 'e2e-pageindex-chatconfig'
        }) : null;
        await ctx.get(REST + '/logout');
        await ctx.dispose();
        test.skip(!cfg || !cfg.objectId, 'Could not create/find a chatConfig fixture');

        await page.goto('/#!/view/olio.llm.chatConfig/' + cfg.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/olio.llm.chatConfig/'), { timeout: 15000 });

        // The toggle renders as a checkbox next to its "Use PageIndex" label.
        await expect(page.getByText('Use PageIndex')).toBeVisible({ timeout: 15000 });
    });
});

test.describe('PageIndex — build, tree render, ranked query (live LLM)', () => {
    test.skip(process.env.PAGEINDEX_E2E !== '1',
        'PageIndex build/query is gated behind PAGEINDEX_E2E=1 (single-thread DGX Spark; run with --workers=1)');

    let shared, note;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);
        let ctx = await newCtx();
        await loginCtx(ctx, shared);
        note = await createNote(ctx, '~/Notes', 'pageIndexBuild-' + Date.now(),
            'PageIndex is a hierarchical table-of-contents index built over a document\'s content, ' +
            'offering structure-aware retrieval as a complement to flat vector similarity search. ' +
            'Instead of comparing embeddings directly, PageIndex walks the document\'s actual outline ' +
            '(root, sections, and leaf chunks) to find the most relevant passages for a query. ' +
            'This paragraph exists so a real build has enough content to summarize and index into ' +
            'at least one chunk for the end-to-end Ux752 Tier 3 test.');
        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test.beforeEach(async ({ page }) => {
        await attachSharedSession(page, shared);
    });

    test('build an index, see the tree render, then run a ranked query', async ({ page }) => {
        test.setTimeout(180000); // real LLM summarization call; DGX Spark can be slow under load
        test.skip(!note || !note.objectId, 'Note fixture was not created');

        await page.goto('/#!/view/data.note/' + note.objectId);
        await page.waitForFunction(() => window.location.hash.includes('/view/data.note/'), { timeout: 15000 });

        await page.locator('button:has-text("PageIndex")').click();
        await expect(page.locator('button:has-text("Build Index")')).toBeVisible({ timeout: 10000 });
        await page.locator('button:has-text("Build Index")').click();

        await expect(page.locator('text=Build PageIndex')).toBeVisible({ timeout: 5000 });
        await page.locator('.am7-dialog-btn-primary:has-text("Build")').click();

        // Real LLM call (structural split + summarization) — generous timeout for completion toast.
        await expect(page.locator('text=PageIndex build complete')).toBeVisible({ timeout: 150000 });

        // Tree renders with at least one node (Rebuild Index only shows once a tree exists).
        await expect(page.locator('button:has-text("Rebuild Index")')).toBeVisible({ timeout: 15000 });
        await expect(page.locator('text=/\\d+ nodes?/')).toBeVisible({ timeout: 10000 });

        // Run a ranked query against the built index.
        let queryInput = page.locator('input[placeholder="Ask a question about this document..."]');
        await expect(queryInput).toBeVisible();
        await queryInput.fill('What is PageIndex?');
        // Scoped to the query row itself — the global toolbar has an unrelated icon-only
        // "Find" button whose ligature text also matches a loose "Search" substring locator.
        await queryInput.locator('xpath=following-sibling::button[1]').click();

        // A ranked leaf with a descending-cosine score renders.
        await expect(page.locator('text=/score: [\\d.]+/').first()).toBeVisible({ timeout: 30000 });
    });
});
