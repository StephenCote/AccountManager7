/**
 * ChapBook Review Redesign — EXPLICIT SAVE (D1)
 *
 * The redesign replaced on-blur auto-save with a single explicit per-card Save (plus a book-level
 * "Save all"). These tests drive the real UI and verify the BACKEND result:
 *   1. A card Save commits EVERY dirty field in one go — title, stanza, font, and alignment — and the
 *      Save button gates on dirty state (disabled when clean → enabled after an edit → disabled again
 *      after a successful save).
 *   2. "Save all" commits unsaved edits across multiple cards at once.
 *
 * Verification is via the /pages endpoint (bookPages), which now surfaces every style field.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookSave.spec.js \
 *       --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, bookPages,
    POEM_1, POEM_2
} from './helpers/chapbook.js';

async function seedFreshBook(request, slugPrefix) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    return await createChapBook(request, slugPrefix + '-' + Date.now(), 'E2E Save Book', [p1, p2], 4);
}

// The scene cards: a card is the bordered div containing the rows=12 stanza textarea.
function sceneCards(page) {
    return page.locator('div.rounded-lg.border').filter({ has: page.locator('textarea[rows="12"]') });
}

async function pageByOid(request, bookOid, oid) {
    const pages = await bookPages(request, bookOid);
    return pages.find(p => p.objectId === oid) || null;
}

test.describe('ChapBook Review — explicit Save', () => {

    test('card Save commits title + stanza + font + align in one action; gates on dirty state', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-save');
        const before = await bookPages(request, bookOid);
        expect(before.length).toBeGreaterThanOrEqual(1);
        const oid = before[0].objectId;

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });

        const card = sceneCards(page).first();
        await expect(card).toBeVisible({ timeout: 20000 });

        // Clean state: the Save button reads "No unsaved changes" (disabled).
        await expect(card.locator('button[title="No unsaved changes"]')).toBeVisible();

        const NEW_TITLE = 'E2E Save Title ' + Date.now();
        const NEW_STANZA = 'Edited stanza line one\nEdited stanza line two';

        await card.locator('input[type="text"]').first().fill(NEW_TITLE);
        await card.locator('textarea[rows="12"]').fill(NEW_STANZA);
        await card.locator('select').first().selectOption('Georgia, serif');   // Font → Serif
        await card.getByTitle('Right', { exact: true }).click();               // Align → right

        // Editing enabled the single Save.
        const saveBtn = card.locator('button[title="Save unsaved changes on this page"]');
        await expect(saveBtn).toBeEnabled({ timeout: 10000 });
        await saveBtn.click();

        // After a successful save the card is clean again.
        await expect(card.locator('button[title="No unsaved changes"]')).toBeVisible({ timeout: 15000 });

        // Backend: all four fields persisted in one save.
        await expect.poll(async () => {
            const p = await pageByOid(request, bookOid, oid);
            return p && [p.title, p.poemStanza, p.pageFont, p.pageTextAlign];
        }, { timeout: 20000 }).toEqual([NEW_TITLE, NEW_STANZA, 'Georgia, serif', 'right']);
    });

    test('Save all commits unsaved edits across multiple cards', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-saveall');
        const before = await bookPages(request, bookOid);
        expect(before.length, 'need >=2 pages for Save all').toBeGreaterThanOrEqual(2);
        const oid0 = before[0].objectId;
        const oid1 = before[1].objectId;

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });

        const cards = sceneCards(page);
        await expect.poll(() => cards.count(), { timeout: 20000 }).toBe(before.length);

        const T0 = 'E2E All Card0 ' + Date.now();
        const T1 = 'E2E All Card1 ' + Date.now();
        await cards.nth(0).locator('input[type="text"]').first().fill(T0);
        await cards.nth(1).locator('input[type="text"]').first().fill(T1);

        // Two cards are now dirty. Click the book-level Save all.
        const saveAll = page.locator('button[title="Save every page that has unsaved changes"]');
        await expect(saveAll).toBeVisible({ timeout: 10000 });
        await saveAll.click();

        // Both cards clean afterward.
        await expect(cards.nth(0).locator('button[title="No unsaved changes"]')).toBeVisible({ timeout: 15000 });
        await expect(cards.nth(1).locator('button[title="No unsaved changes"]')).toBeVisible({ timeout: 15000 });

        // Backend: both titles persisted.
        await expect.poll(async () => {
            const p0 = await pageByOid(request, bookOid, oid0);
            const p1 = await pageByOid(request, bookOid, oid1);
            return [p0 && p0.title, p1 && p1.title];
        }, { timeout: 20000 }).toEqual([T0, T1]);
    });
});
