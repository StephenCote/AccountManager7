/**
 * ChapBook Review Redesign — SHARED ReaderShell parity (D5)
 *
 * The redesign extracted the page-flip reader into ONE shared component,
 * src/components/readerShell.js, so PictureBook and ChapBook use the identical reader. Both mount it:
 *   - src/features/pictureBook.js  → import { ReaderShell } from '../components/readerShell.js'
 *   - src/features/chapBook.js     → the same ReaderShell (ChapBookReader.view)
 * with the same nav contract (implicit cover = page 0, one page at a time, chevrons, page dots,
 * keyboard Arrow/Home/End/Esc, fullscreen, self-contained HTML export).
 *
 * PictureBook's reader needs a fully generated PB book (SD/LLM pipeline) to seed, which Docker cannot
 * drive (no LAN route to the SD/LLM hosts). A ChapBook is seedable without generation and mounts the
 * SAME component, so these tests exercise the shared ReaderShell's behavior through the ChapBook
 * reader — the mechanics verified here are exactly those PictureBook's reader inherits from the shared
 * shell.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookReaderParity.spec.js \
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
    return await createChapBook(request, slugPrefix + '-' + Date.now(), 'E2E Parity Book', [p1, p2], 4);
}

// Header chevrons are icon buttons whose only text is the material-symbol ligature.
function prevChevron(page) { return page.locator('button:has(span:text-is("chevron_left"))'); }
function nextChevron(page) { return page.locator('button:has(span:text-is("chevron_right"))'); }

async function openReader(page, request, slug) {
    const bookOid = await seedFreshBook(request, slug);
    const pages = await bookPages(request, bookOid);
    expect(pages.length, 'need >=2 pages for nav-boundary coverage').toBeGreaterThanOrEqual(2);
    await loginAsSharedUser(page);
    await page.goto('/#!/chap-book/read/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button:has-text("Begin")')).toBeVisible({ timeout: 20000 });
    return pages;
}

test.describe('Shared ReaderShell parity (the reader PictureBook and ChapBook both mount)', () => {

    test('cover/last-page boundaries, page dots, and keyboard nav', async ({ page, request }) => {
        const pages = await openReader(page, request, 'e2e-parity-nav');
        const N = pages.length;

        // On the cover the previous chevron is disabled; the header label reads "Cover".
        await expect(prevChevron(page)).toBeDisabled();
        await expect(page.getByText('Cover', { exact: true }).first()).toBeVisible();

        // Begin → page 1; the page-1 dot becomes the active (blue) dot.
        await page.locator('button:has-text("Begin")').click();
        await expect(page.getByText('Page 1 of ' + N, { exact: true }).first()).toBeVisible({ timeout: 15000 });
        await expect(page.getByTitle('Page 1', { exact: true })).toHaveClass(/bg-blue-500/);
        await expect(page.getByTitle('Cover', { exact: true })).not.toHaveClass(/bg-blue-500/);

        // Keyboard: ArrowRight advances, ArrowLeft goes back (shared shell keydown handler).
        await page.keyboard.press('ArrowRight');
        await expect(page.getByText('Page 2 of ' + N, { exact: true }).first()).toBeVisible({ timeout: 15000 });
        await expect(page.getByTitle('Page 2', { exact: true })).toHaveClass(/bg-blue-500/);
        await page.keyboard.press('ArrowLeft');
        await expect(page.getByText('Page 1 of ' + N, { exact: true }).first()).toBeVisible({ timeout: 15000 });

        // End jumps to the last page, where the next chevron is disabled (cp >= total-1).
        await page.keyboard.press('End');
        await expect(page.getByText('Page ' + N + ' of ' + N, { exact: true }).first()).toBeVisible({ timeout: 15000 });
        await expect(nextChevron(page)).toBeDisabled();

        // Home returns to the cover, where prev is disabled again.
        await page.keyboard.press('Home');
        await expect(page.getByText('Cover', { exact: true }).first()).toBeVisible({ timeout: 15000 });
        await expect(prevChevron(page)).toBeDisabled();
    });

    test('fullscreen toggle and Escape exit', async ({ page, request }) => {
        await openReader(page, request, 'e2e-parity-fs');
        await page.locator('button:has-text("Begin")').click();

        // Enter fullscreen: the shell swaps in a fixed inset-0 z-50 overlay and the button flips to Exit.
        await page.getByTitle('Fullscreen', { exact: true }).click();
        await expect(page.locator('div.fixed.inset-0.z-50')).toBeVisible({ timeout: 10000 });
        await expect(page.getByTitle('Exit fullscreen', { exact: true })).toBeVisible();

        // Escape exits fullscreen (shared shell keydown handler).
        await page.keyboard.press('Escape');
        await expect(page.locator('div.fixed.inset-0.z-50')).toHaveCount(0, { timeout: 10000 });
        await expect(page.getByTitle('Fullscreen', { exact: true })).toBeVisible();
    });

    test('export produces a self-contained HTML download', async ({ page, request }) => {
        await openReader(page, request, 'e2e-parity-export');

        // The shared shell's Export button (title "Export as HTML") builds a self-contained HTML blob
        // and triggers an <a download>. Capture the download and assert its name.
        const [download] = await Promise.all([
            page.waitForEvent('download', { timeout: 20000 }),
            page.getByTitle('Export as HTML', { exact: true }).click()
        ]);
        const name = download.suggestedFilename();
        expect(name.toLowerCase()).toContain('chapbook');
        expect(name.toLowerCase().endsWith('.html')).toBe(true);
    });
});
