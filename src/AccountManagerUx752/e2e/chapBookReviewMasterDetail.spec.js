/**
 * ChapBook review master/detail (P10-C 4, ledger §6.C 266) — E2E, no LLM / SD.
 *
 * Why: the review stacked every page card down one column, so a book of N pages was N full editors to
 * scroll through ("chapbook Ux is unwieldy"). The review is now the Characters tab's list ↔ detail
 * layout: a page list on the left (one row per page, the selected row highlighted, per-page badges) and
 * ONE page card on the right. Merge / Remove / Save-all under the new layout are asserted in
 * chapBookMerge.spec.js / chapBookSave.spec.js; this spec drives the layout itself against the live
 * stack as the shared user:
 *   1. the list has one row per page, page 1 is selected by default, exactly one card is shown;
 *   2. clicking a row swaps the card (header, stanza, per-page controls are that page's);
 *   3. an unsaved edit is flagged in the list and survives switching away and back;
 *   4. Split keeps the split page selected; the list grows by one.
 *
 * Run against the Docker test stack (127.0.0.1 forces IPv4 — Docker only maps IPv4; see troubleshooting.md):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookReviewMasterDetail.spec.js \
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
    return await createChapBook(request, slugPrefix + '-' + Date.now(), 'E2E Master Detail Book', [p1, p2], 4);
}

// The one page card: the bordered div containing the rows=12 stanza textarea.
function sceneCards(page) {
    return page.locator('div.rounded-lg.border').filter({ has: page.locator('textarea[rows="12"]') });
}
function pageHeader(page) {
    return sceneCards(page).first().locator('span.font-mono', { hasText: /^Page \d+ of \d+$/ });
}

test.describe('ChapBook Review — master/detail page list', () => {

    test('one row per page, page 1 selected by default, one card; clicking a row swaps the card; an unsaved edit survives switching; Split keeps the page selected', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-md');
        const before = await bookPages(request, bookOid);
        expect(before.length, 'need >=3 pages').toBeGreaterThanOrEqual(3);
        const n = before.length;

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });

        // 1. The list and the one card.
        const items = page.locator('[data-page-item]');
        await expect(items).toHaveCount(n, { timeout: 20000 });
        await expect(page.getByTestId('cb-page-list')).toContainText('Pages');
        await expect(page.getByTestId('cb-page-list')).toContainText(String(n));
        const cards = sceneCards(page);
        await expect(cards).toHaveCount(1);
        const oids = await items.evaluateAll(els => els.map(e => e.getAttribute('data-page-item')));
        expect(oids, 'list order is page order').toEqual(before.map(p => p.objectId));
        const item = oid => page.locator('[data-page-item="' + oid + '"]');
        await expect(item(oids[0])).toHaveAttribute('data-page-selected', '1');
        await expect(page.locator('[data-page-selected="1"]')).toHaveCount(1);
        await expect(pageHeader(page)).toHaveText('Page 1 of ' + n);
        await expect(cards.first().locator('textarea[rows="12"]')).toHaveValue(before[0].poemStanza);
        await expect(page.locator('button[title="Remove this page"]')).toHaveCount(1);
        await expect(page.locator('[data-testid="cb-page-rerender"]')).toHaveCount(1);
        await expect(page.locator('[data-testid="cb-page-rerender"]')).toHaveAttribute('data-scene-oid', oids[0]);

        // 2. Click page 2: the card is page 2's.
        await item(oids[1]).click();
        await expect(item(oids[1])).toHaveAttribute('data-page-selected', '1');
        await expect(item(oids[0])).toHaveAttribute('data-page-selected', '0');
        await expect(pageHeader(page)).toHaveText('Page 2 of ' + n);
        await expect(cards).toHaveCount(1);
        await expect(cards.first().locator('textarea[rows="12"]')).toHaveValue(before[1].poemStanza);
        await expect(page.locator('[data-testid="cb-page-rerender"]')).toHaveAttribute('data-scene-oid', oids[1]);
        await expect(page.locator('textarea.cb-scene-prompt[data-scene-oid="' + oids[1] + '"]')).toHaveCount(1);
        await expect(page.locator('textarea.cb-scene-prompt[data-scene-oid="' + oids[0] + '"]')).toHaveCount(0);

        // The last page: Merge is disabled there, as before.
        await item(oids[n - 1]).click();
        await expect(pageHeader(page)).toHaveText('Page ' + n + ' of ' + n);
        await expect(page.locator('button[title="Merge this page with the next"]')).toBeDisabled();

        // 3. An unsaved edit on page 2 is flagged in the list and survives switching away and back.
        await item(oids[1]).click();
        const NEW_TITLE = 'E2E MD Title ' + Date.now();
        await cards.first().locator('input[type="text"]').first().fill(NEW_TITLE);
        await expect(item(oids[1]).locator('[data-page-badge="unsaved"]')).toBeVisible();
        await expect(cards.first().locator('button[title="Save unsaved changes on this page"]')).toBeVisible();
        await item(oids[0]).click();
        await expect(pageHeader(page)).toHaveText('Page 1 of ' + n);
        await expect(cards.first().locator('button[title="No unsaved changes"]')).toBeVisible();
        await expect(item(oids[1]).locator('[data-page-badge="unsaved"]')).toBeVisible();
        await item(oids[1]).click();
        await expect(cards.first().locator('input[type="text"]').first()).toHaveValue(NEW_TITLE);
        await expect(cards.first().locator('button[title="Save unsaved changes on this page"]')).toBeVisible();
        // Save it so the split below starts clean and the title is on the server.
        await cards.first().locator('button[title="Save unsaved changes on this page"]').click();
        await expect(cards.first().locator('button[title="No unsaved changes"]')).toBeVisible({ timeout: 15000 });
        await expect(item(oids[1]).locator('[data-page-badge="unsaved"]')).toHaveCount(0);
        await expect.poll(async () => {
            const p = (await bookPages(request, bookOid)).find(x => x.objectId === oids[1]);
            return p && p.title;
        }, { timeout: 20000 }).toBe(NEW_TITLE);

        // 4. Split page 2: the list grows by one and page 2 stays selected (its objectId survives).
        await page.screenshot({ path: 'e2e/screenshots/cb-review-masterdetail-before-split.png', fullPage: true });
        await page.locator('button[title="Split stanza at midpoint into two pages"]').click();
        await expect(items).toHaveCount(n + 1, { timeout: 20000 });
        await expect(item(oids[1])).toHaveAttribute('data-page-selected', '1');
        await expect(pageHeader(page)).toHaveText('Page 2 of ' + (n + 1));
        await expect(cards).toHaveCount(1);
        await page.screenshot({ path: 'e2e/screenshots/cb-review-masterdetail-after-split.png', fullPage: true });
        const after = await bookPages(request, bookOid);
        expect(after.length).toBe(n + 1);
        expect(after[1].objectId).toBe(oids[1]);
        const oidsAfter = await items.evaluateAll(els => els.map(e => e.getAttribute('data-page-item')));
        expect(oidsAfter, 'list follows the server order after the split').toEqual(after.map(p => p.objectId));
    });
});
