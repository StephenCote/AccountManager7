/**
 * ChapBook Review Redesign — MERGE + DELETE (D3)
 *
 * Exercises the real per-page Merge-up and Remove buttons in the ChapBook Review UI against the
 * live Docker stack, and verifies the BACKEND result of each UI action (scene records via REST):
 *   - Merge folds scene[i+1]'s stanza into scene[i]: survivor.poemStanza == old[i] + "\n" + old[i+1],
 *     the folded scene is gone, the survivor keeps its objectId and is flagged imageStale, and the
 *     survivors are reindexed to a contiguous 0..n-1.
 *   - Remove deletes a scene (after the confirm dialog) and reindexes the survivors 0..n-1.
 *
 * Each test seeds its OWN fresh chapbook so the tests are independent and retry-safe (createChapBook
 * does not call the LLM when no org-default chatConfig exists — it stores stanza-excerpt prompts).
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookMerge.spec.js \
 *       --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, bookPages, sceneByObjectId,
    POEM_1, POEM_2
} from './helpers/chapbook.js';

// sceneByObjectId already projects sdPrompt + promptLocked (helpers/chapbook.js). ChapBook create
// (no org chatConfig) stores a non-empty "landscape, <title>, <mood> atmosphere..." create-time
// prompt on every scene (ChapBookUtil.createChapBookScene), so the survivor has a real prompt to
// preserve across a merge — the whole point of the preserve-prompt fix.

// Count of scene cards in the review UI == number of "Remove this page" buttons (every card has one).
async function cardCount(page) {
    return await page.locator('button[title="Remove this page"]').count();
}

// Seed a fresh chapbook as the shared user; returns its bookObjectId. Logs the request fixture in too.
async function seedFreshBook(request, slugPrefix) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    // maxLinesPerPage=4 chunks the two multi-line stanzas into several scenes (>=3 needed to merge).
    return await createChapBook(request, slugPrefix + '-' + Date.now(), 'E2E Merge Book', [p1, p2], 4);
}

test.describe('ChapBook Review — merge & delete', () => {

    test('Merge folds next stanza into this one, reindexes, flags imageStale', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-merge');

        // --- backend state BEFORE the UI merge ---
        const before = await bookPages(request, bookOid);
        expect(before.length, 'need at least 3 scenes to exercise a merge').toBeGreaterThanOrEqual(3);
        const oldStanza0 = before[0].poemStanza;
        const oldStanza1 = before[1].poemStanza;
        const survivorOid = before[0].objectId;
        const foldedOid = before[1].objectId;
        expect(oldStanza0, 'scene0 has stanza text').toBeTruthy();
        expect(oldStanza1, 'scene1 has stanza text').toBeTruthy();

        // Capture the survivor's landscape prompt BEFORE the merge. The preserve-prompt fix
        // (ChapBookUtil.mergeSceneUp) must leave sdPrompt + promptLocked untouched; only poemStanza
        // and imageStale change. A create-time prompt is non-empty, so this is a real preservation test.
        const survivorBefore = await sceneByObjectId(request, survivorOid);
        expect(survivorBefore, 'survivor scene readable before merge').toBeTruthy();
        const oldSdPrompt = survivorBefore.sdPrompt;
        const oldPromptLocked = !!survivorBefore.promptLocked;
        expect(oldSdPrompt, 'survivor has a non-empty landscape prompt to preserve').toBeTruthy();

        // --- drive the real review UI ---
        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
        await expect.poll(() => cardCount(page), { timeout: 20000 }).toBe(before.length);

        const firstMerge = page.locator('button[title="Merge this page with the next"]').first();
        await expect(firstMerge).toBeEnabled();
        await firstMerge.click();

        // UI reflects N-1 cards after the server merge + reload
        await expect.poll(() => cardCount(page), { timeout: 20000 }).toBe(before.length - 1);

        // --- backend state AFTER the UI merge ---
        const survivor = await sceneByObjectId(request, survivorOid);
        expect(survivor, 'survivor scene still exists').toBeTruthy();
        expect(survivor.poemStanza).toBe(oldStanza0 + '\n' + oldStanza1);
        expect(survivor.imageStale, 'merge flags the survivor imageStale').toBe(true);

        // Preserve-prompt fix: the survivor's landscape prompt + lock flag must be UNCHANGED by the
        // merge (previously merge cleared them). Only stanza + imageStale should have moved.
        expect(survivor.sdPrompt, 'merge PRESERVES the survivor sdPrompt (not cleared/blanked)').toBe(oldSdPrompt);
        expect(!!survivor.promptLocked, 'merge PRESERVES the survivor promptLocked flag').toBe(oldPromptLocked);

        // Folded scene is gone
        const folded = await sceneByObjectId(request, foldedOid);
        expect(folded, 'folded scene[1] was deleted').toBeNull();

        // Survivors reindexed to contiguous 0..n-1 (bookPages reports sceneIndex incl. 0)
        const after = await bookPages(request, bookOid);
        expect(after.length).toBe(before.length - 1);
        const indices = after.map(p => p.sceneIndex).sort((a, b) => a - b);
        expect(indices).toEqual(indices.map((_, i) => i));
        // The survivor is page 0
        expect(after[0].objectId).toBe(survivorOid);
    });

    test('Remove deletes a scene and reindexes survivors', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-del');

        const before = await bookPages(request, bookOid);
        const n = before.length;
        expect(n, 'need at least 2 scenes to exercise a delete').toBeGreaterThanOrEqual(2);
        const removedOid = before[0].objectId;

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });
        await expect.poll(() => cardCount(page), { timeout: 20000 }).toBe(n);

        await page.locator('button[title="Remove this page"]').first().click();
        // Confirm dialog (Dialog.confirm, destructive) — click the destructive confirm button
        const confirmBtn = page.locator('.am7-dialog-btn-destructive');
        await expect(confirmBtn).toBeVisible({ timeout: 10000 });
        await confirmBtn.click();

        await expect.poll(() => cardCount(page), { timeout: 20000 }).toBe(n - 1);

        // Removed scene is gone
        const removed = await sceneByObjectId(request, removedOid);
        expect(removed, 'removed scene is deleted').toBeNull();

        // Survivors reindexed contiguous 0..n-2
        const after = await bookPages(request, bookOid);
        expect(after.length).toBe(n - 1);
        const indices = after.map(p => p.sceneIndex).sort((a, b) => a - b);
        expect(indices).toEqual(indices.map((_, i) => i));
    });
});
