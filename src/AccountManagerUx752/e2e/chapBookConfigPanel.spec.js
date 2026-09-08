/**
 * ChapBook — SHARED COLLAPSIBLE CONFIG PANEL + per-page LLM actions
 *
 * Verifies three deployed ChapBook UI changes against the live Docker stack, as the shared test user
 * (never admin). Exercises the REAL UI and the REAL backend/LLM — no stubs beyond the WebSocket shim
 * (helpers/loginAsSharedUser) that Docker's proxy requires.
 *
 *  (1) One shared collapsible ChapBookConfig panel (cb-config-panel / cb-config-toggle) is rendered at
 *      the TOP of ALL THREE ChapBook views — create (PoemLibrary), review (ChapBookReview), read
 *      (ChapBookReader) — inline (not a tab/drawer): it collapses/expands in place. The POEM QUEUE lives
 *      INSIDE the panel (descendant of cb-config-panel). Exactly one panel + one toggle per view.
 *
 *  (2) Two per-page buttons on each review scene card:
 *        - cb-page-regen-prompt → POST /scene/{oid}/prompt/regenerate → re-derives the landscape prompt
 *          via the LLM, persists it with promptLocked:false (prompt only, no image render).
 *        - cb-page-analyze      → POST /scene/{oid}/analyze → re-derives the mood via the LLM and
 *          persists it (analysis only, no image render).
 *      The two LLM tests are gated behind CHAPBOOK_LLM_TESTS=1 (LLM at 192.168.1.42) and must run
 *      --workers=1. They assert BEFORE/AFTER evidence that the live LLM produced a new prompt / mood.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 CHAPBOOK_LLM_TESTS=1 \
 *     npx playwright test e2e/chapBookConfigPanel.spec.js --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, bookPages, sceneByObjectId,
    REST, POEM_1, POEM_2
} from './helpers/chapbook.js';
import { ensureChatConfig } from './helpers/api.js';

const PANEL = '[data-testid="cb-config-panel"]';
const TOGGLE = '[data-testid="cb-config-toggle"]';

// Seed a fresh chapbook as the shared user; returns { bookOid, groupId, organizationId }.
// maxLinesPerPage=4 chunks the two multi-stanza poems into several scenes.
async function seedBook(request, slugPrefix) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    const bookOid = await createChapBook(request, slugPrefix + '-' + Date.now(),
        'E2E Config Panel Book', [p1, p2], 4);
    return { bookOid, groupId, organizationId };
}

// olio.pb.scene mood is NOT in sceneByObjectId's projection, so read it directly (cache:false).
async function sceneMood(request, objectId) {
    const r = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.pb.scene', cache: false,
            request: ['id', 'objectId', 'mood'],
            fields: [{ name: 'objectId', comparator: 'EQUALS', value: objectId }],
            recordCount: 1
        }
    });
    expect(r.ok(), 'scene mood fetch failed: ' + r.status()).toBe(true);
    const b = await r.json();
    const row = Array.isArray(b) ? b[0] : (b && b.results) ? b.results[0] : null;
    return row ? (row.mood || null) : null;
}

// DOM-order proof that the panel is rendered BEFORE the given content anchor (i.e. at the top).
// anchor is either { selector } (querySelector) or { tag, text } (first element of tag containing text).
async function panelPrecedes(page, anchor) {
    return await page.evaluate((a) => {
        const panel = document.querySelector('[data-testid="cb-config-panel"]');
        if (!panel) return { ok: false, panel: false, anchor: false };
        let el = null;
        if (a.selector) el = document.querySelector(a.selector);
        else el = Array.from(document.querySelectorAll(a.tag))
            .find(function (e) { return (e.textContent || '').includes(a.text); });
        if (!el) return { ok: false, panel: true, anchor: false };
        // DOCUMENT_POSITION_FOLLOWING (4) => `el` comes AFTER `panel` in document order.
        const ok = (panel.compareDocumentPosition(el) & 4) !== 0;
        return { ok: ok, panel: true, anchor: true };
    }, anchor);
}

// Assert the shared panel + toggle are present exactly once and the Poem queue heading is a descendant.
async function assertSharedPanel(page) {
    await expect(page.locator(PANEL)).toHaveCount(1);
    await expect(page.locator(TOGGLE)).toHaveCount(1);
    await expect(page.locator(PANEL)).toBeVisible();
    // Poem queue lives INSIDE the panel (expanded by default).
    await expect(page.locator(PANEL).getByRole('heading', { name: 'Poem queue' })).toBeVisible();
}

test.describe('ChapBook shared config panel', () => {

    test('CREATE view: panel + queue at top, inline collapse/expand', async ({ page, request }) => {
        // Seed a book so "My ChapBooks" has content to anchor the top-of-view check against.
        await seedBook(request, 'e2e-cb-cfg-create');

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book', { timeout: 30000 });
        await expect(page.locator(PANEL)).toBeVisible({ timeout: 20000 });

        await assertSharedPanel(page);

        // At the TOP: the panel precedes the "My ChapBooks" section in document order.
        const order = await panelPrecedes(page, { tag: 'h3', text: 'My ChapBooks' });
        expect(order.anchor, 'My ChapBooks section present on create view').toBe(true);
        expect(order.ok, 'config panel is rendered ABOVE the My ChapBooks section').toBe(true);

        // Inline, not a tab/drawer: the toggle collapses the panel IN PLACE (queue disappears), and
        // expands it again — the panel element itself stays mounted the whole time.
        const queue = page.locator(PANEL).getByRole('heading', { name: 'Poem queue' });
        await expect(queue).toBeVisible();
        await page.locator(TOGGLE).click();
        await expect(queue).toHaveCount(0);          // body removed on collapse
        await expect(page.locator(PANEL)).toHaveCount(1); // panel still mounted (inline, not routed away)
        await page.locator(TOGGLE).click();
        await expect(queue).toBeVisible();           // re-expands in place
    });

    test('REVIEW view: panel + queue at top of the scene list', async ({ page, request }) => {
        const { bookOid } = await seedBook(request, 'e2e-cb-cfg-review');

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
        await expect(page.locator(PANEL)).toBeVisible({ timeout: 20000 });
        // scene cards loaded (each card has a per-page analyze button)
        await expect(page.locator('[data-testid="cb-page-analyze"]').first()).toBeVisible({ timeout: 20000 });

        await assertSharedPanel(page);

        // At the TOP: the panel precedes the first scene card's per-page action.
        const order = await panelPrecedes(page, { selector: '[data-testid="cb-page-analyze"]' });
        expect(order.anchor, 'scene cards present on review view').toBe(true);
        expect(order.ok, 'config panel is rendered ABOVE the scene cards').toBe(true);
    });

    test('READ view: panel + queue at top of the reader', async ({ page, request }) => {
        const { bookOid } = await seedBook(request, 'e2e-cb-cfg-read');

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/read/' + bookOid, { timeout: 30000 });
        await expect(page.locator(PANEL)).toBeVisible({ timeout: 20000 });
        // The reader header renders a "Back to Poem Library" control (its only one on the read view).
        await expect(page.locator('button[title="Back to Poem Library"]').first()).toBeVisible({ timeout: 20000 });

        await assertSharedPanel(page);

        // At the TOP: the panel precedes the reader shell (its back control).
        const order = await panelPrecedes(page, { selector: 'button[title="Back to Poem Library"]' });
        expect(order.anchor, 'reader shell present on read view').toBe(true);
        expect(order.ok, 'config panel is rendered ABOVE the reader shell').toBe(true);
    });

    // ── Per-page LLM actions (live LLM at 192.168.1.42) ──────────────────────────────────────────

    test('[LLM] per-page Regen prompt re-derives + persists a genuine landscape prompt', async ({ page, request }) => {
        if (!process.env.CHAPBOOK_LLM_TESTS) {
            test.skip(true, 'set CHAPBOOK_LLM_TESTS=1 (single-threaded) to run the live-LLM ChapBook tests');
            return;
        }
        // The DB-change poll below waits up to 180s for the live LLM; Playwright's default 60s
        // per-test timeout would otherwise cap the poll. Give the whole test room for one qwen3:8b
        // round-trip plus UI navigation.
        test.setTimeout(300000);
        const { bookOid, organizationId } = await seedBook(request, 'e2e-cb-regen');
        // resolveDefaultChatConfig filters by ownerId → the config must be owned by this user.
        const chatConfigName = await ensureChatConfig(request, organizationId);
        expect(chatConfigName, 'ensureChatConfig did not provision a chatConfig').toBeTruthy();

        const pages = await bookPages(request, bookOid);
        expect(pages.length, 'need at least one scene').toBeGreaterThanOrEqual(1);
        const oid = pages[0].objectId;

        // BEFORE: authoritative scene prompt straight from the DB.
        const before = await sceneByObjectId(request, oid);
        const oldPrompt = before.sdPrompt || '';

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
        const regenBtn = page.locator('[data-testid="cb-page-regen-prompt"]').first();
        await expect(regenBtn).toBeVisible({ timeout: 20000 });
        await expect(regenBtn).toBeEnabled();

        // The visible landscape-prompt textarea for the first card (bound to scene.sdPrompt).
        const promptBox = page.locator('.cb-scene-prompt').first();
        const uiBefore = await promptBox.inputValue();

        await regenBtn.click();

        // The live LLM re-derives + the endpoint persists it. Poll the DB until the stored prompt CHANGES
        // (a real LLM landscape prompt of a poem stanza is essentially never byte-identical to the prior
        // value). Generous timeout for qwen3:8b.
        let newScene = null;
        await expect.poll(async () => {
            newScene = await sceneByObjectId(request, oid);
            return (newScene && newScene.sdPrompt && newScene.sdPrompt !== oldPrompt) ? 'changed' : 'same';
        }, { timeout: 180000, intervals: [2000] }).toBe('changed');

        // Backend guarantees for a SUCCESSFUL LLM regenerate:
        //  - promptLocked is set FALSE (LLM-authored, not a locked human edit)
        //  - the stored prompt is "genuine" (ChapBookUtil.isGenuineStoredPrompt): non-empty and NOT the
        //    deterministic "landscape, " fallback shape (the endpoint 500s rather than persist a fallback).
        expect(!!newScene.promptLocked, 'regenerate persists promptLocked=false').toBe(false);
        const newPrompt = (newScene.sdPrompt || '').trim();
        expect(newPrompt.length, 'regenerated prompt is non-empty').toBeGreaterThan(0);
        expect(newPrompt.startsWith('landscape, '),
            'regenerated prompt is a genuine LLM prompt (not the "landscape, " fallback)').toBe(false);

        // The UI reflects the new prompt in the visible textarea (proves the button drove the round-trip).
        await expect.poll(async () => await promptBox.inputValue(), { timeout: 20000 })
            .toBe(newScene.sdPrompt);
        const uiAfter = await promptBox.inputValue();
        expect(uiAfter, 'visible landscape-prompt textarea changed after Regen prompt').not.toBe(uiBefore);

        console.log('[regen] BEFORE sdPrompt:', JSON.stringify(oldPrompt));
        console.log('[regen] AFTER  sdPrompt:', JSON.stringify(newScene.sdPrompt));
    });

    test('[LLM] per-page Analyze re-derives + persists a mood', async ({ page, request }) => {
        if (!process.env.CHAPBOOK_LLM_TESTS) {
            test.skip(true, 'set CHAPBOOK_LLM_TESTS=1 (single-threaded) to run the live-LLM ChapBook tests');
            return;
        }
        // See the regen test: the 180s LLM poll needs a per-test timeout above Playwright's 60s default.
        test.setTimeout(300000);
        const { bookOid, organizationId } = await seedBook(request, 'e2e-cb-analyze');
        const chatConfigName = await ensureChatConfig(request, organizationId);
        expect(chatConfigName, 'ensureChatConfig did not provision a chatConfig').toBeTruthy();

        const pages = await bookPages(request, bookOid);
        expect(pages.length, 'need at least one scene').toBeGreaterThanOrEqual(1);
        const oid = pages[0].objectId;
        const oldMood = await sceneMood(request, oid);

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
        const analyzeBtn = page.locator('[data-testid="cb-page-analyze"]').first();
        await expect(analyzeBtn).toBeVisible({ timeout: 20000 });
        await expect(analyzeBtn).toBeEnabled();

        await analyzeBtn.click();

        // Poll the DB until the LLM-derived mood is present (and, if there was a prior mood, changed).
        let newMood = null;
        await expect.poll(async () => {
            newMood = await sceneMood(request, oid);
            if (!newMood) return 'none';
            if (oldMood && newMood === oldMood) return 'same';
            return 'ready';
        }, { timeout: 180000, intervals: [2000] }).toBe('ready');

        expect(newMood, 'analyze persists a non-empty mood').toBeTruthy();
        expect(newMood.trim().length, 'mood is non-empty text').toBeGreaterThan(0);

        // UI proof: the first card's mood badge is now visible (it only renders when scene.mood is set).
        const moodBadge = analyzeBtn.locator(
            'xpath=../span[@title="Mood derived from this page\'s stanza"]');
        await expect(moodBadge).toBeVisible({ timeout: 20000 });

        console.log('[analyze] BEFORE mood:', JSON.stringify(oldMood));
        console.log('[analyze] AFTER  mood:', JSON.stringify(newMood));
    });
});
