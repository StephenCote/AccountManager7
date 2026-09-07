/**
 * ChapBook Review Redesign — PER-PAGE FONT COLOR (D1 style controls + reader parity)
 *
 * Exercises the real per-card "Text color" control in the ChapBook Review UI against the live Docker
 * stack, and asserts the choice both PERSISTS and actually RENDERS:
 *   1. Set the first page's Text color to a distinctive hex, click that card's Save.
 *   2. The scene record's pageTextColor persists (verified via REST) — the review Save path works.
 *   3. Open the reader and assert the stanza <p>'s COMPUTED color matches the chosen hex — i.e. the
 *      color the author picked is what the reader shows. This is the reader-parity assertion that
 *      surfaces the bookPageView gap (the /pages endpoint must carry pageTextColor for the reader to
 *      render it).
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookFontColor.spec.js \
 *       --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, bookPages, sceneByObjectId, hexToRgb,
    POEM_1, POEM_2
} from './helpers/chapbook.js';

// rose-600 — deliberately far from the historical default (white text / Georgia serif).
const CHOSEN_HEX = '#e11d48';
const CHOSEN_RGB = hexToRgb(CHOSEN_HEX);   // "rgb(225, 29, 72)"

async function seedFreshBook(request, slugPrefix) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    return await createChapBook(request, slugPrefix + '-' + Date.now(), 'E2E Font Color Book', [p1, p2], 4);
}

test.describe('ChapBook Review — per-page font color', () => {

    test('page Text color persists AND renders in the reader', async ({ page, request }) => {
        const bookOid = await seedFreshBook(request, 'e2e-cb-color');
        const before = await bookPages(request, bookOid);
        expect(before.length, 'book has at least one page').toBeGreaterThanOrEqual(1);
        const scene0Oid = before[0].objectId;

        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookOid, { timeout: 30000 });

        // First scene card, scoped by its stanza textarea (rows=12 — the prompt textarea is rows=3).
        const card = page.locator('div.rounded-lg.border')
            .filter({ has: page.locator('textarea[rows="12"]') }).first();
        await expect(card).toBeVisible({ timeout: 20000 });

        // Within a card the FIRST input[type=color] is "Text color" (2nd is "Bg color").
        const textColor = card.locator('input[type="color"]').first();
        await expect(textColor).toBeVisible();
        // <input type=color> cannot be .fill()'d — set value + fire the onchange handler.
        await textColor.evaluate((el, v) => {
            el.value = v;
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
        }, CHOSEN_HEX);

        // The edit marks the card dirty → the single per-card Save enables.
        const saveBtn = card.locator('button[title="Save unsaved changes on this page"]');
        await expect(saveBtn).toBeEnabled({ timeout: 10000 });
        await saveBtn.click();

        // (2) Persistence — the review Save path stored pageTextColor on the scene.
        await expect.poll(async () => {
            const s = await sceneByObjectId(request, scene0Oid);
            return s && s.pageTextColor;
        }, { timeout: 20000, message: 'pageTextColor persisted on scene' }).toBe(CHOSEN_HEX);

        // (3) Reader parity — open the reader, advance past the cover, assert the stanza renders the color.
        await page.goto('/#!/chap-book/read/' + bookOid, { timeout: 30000 });
        const begin = page.locator('button:has-text("Begin")');
        await expect(begin).toBeVisible({ timeout: 20000 });
        await begin.click();

        // The reader renders one page at a time; the stanza <p> is the only one carrying the
        // pre-wrap inline style (renderChapBookPage). Its computed color must equal the chosen hex.
        const stanzaP = page.locator('p[style*="pre-wrap"]').first();
        await expect(stanzaP).toBeVisible({ timeout: 20000 });
        await page.screenshot({ path: 'test-results/chapbook-fontcolor-reader.png', fullPage: true });

        const color = await stanzaP.evaluate(el => getComputedStyle(el).color);
        expect(color, 'reader stanza renders the author-chosen text color').toBe(CHOSEN_RGB);
    });
});
