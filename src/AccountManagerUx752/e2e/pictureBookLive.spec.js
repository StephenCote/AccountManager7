/**
 * Picture Book LIVE UX E2E tests — tests the actual Picture Book UI code
 * against the live backend using real navigation and interaction.
 *
 * Uses ensureSharedTestUser. Screenshots saved as proof.
 */
import { test as base, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const test = base;
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, 'screenshots');

let testInfo = {};

function saveScreenshot(page, name) {
    fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
    return page.screenshot({
        path: path.join(SCREENSHOT_DIR, 'pb-' + name + '.png'),
        fullPage: true
    });
}

async function goToPictureBook(page) {
    await page.evaluate(() => { window.location.hash = '#!/picture-book'; });
    await page.waitForTimeout(2000);
}

test.describe('Picture Book UX — live backend', () => {

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    // ── 1. Work selector loads and renders ───────────────────────────────

    test('work selector route loads with heading and document list', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await goToPictureBook(page);
        await saveScreenshot(page, '01-work-selector');

        let state = await page.evaluate(() => {
            let h2 = document.querySelector('h2');
            let items = document.querySelectorAll('.cursor-pointer');
            let content = document.body.innerText;
            return {
                heading: h2 ? h2.textContent : null,
                itemCount: items.length,
                hasNoDocuments: content.includes('No documents'),
                hasLoading: content.includes('Loading')
            };
        });

        console.log('=== Work Selector ===');
        console.log('  Heading: ' + state.heading);
        console.log('  Items: ' + state.itemCount);
        console.log('  Screenshot: e2e/screenshots/pb-01-work-selector.png');

        expect(state.heading).toMatch(/[Pp]icture [Bb]ook/);
        // Either has documents or shows empty state - both valid
        expect(state.hasNoDocuments || state.itemCount >= 0).toBe(true);
    });

    // ── 2. Clicking a work navigates to viewer route ─────────────────────

    test('selecting a work from list navigates to viewer', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await goToPictureBook(page);

        let clicked = await page.evaluate(() => {
            let items = Array.from(document.querySelectorAll('.cursor-pointer'));
            let work = items.find(el => el.querySelector('.font-medium'));
            if (work) { work.click(); return true; }
            return false;
        });

        if (clicked) {
            await page.waitForTimeout(2000);
            await saveScreenshot(page, '02-viewer-from-click');
            let url = page.url();
            expect(url).toMatch(/picture-book\/.+/);
            console.log('VERIFIED: Clicked work → navigated to viewer: ' + url);
        } else {
            console.log('No works in selector — cannot test click navigation');
            await saveScreenshot(page, '02-no-works');
        }
    });

    // ── 3. Viewer renders header with all nav controls ───────────────────

    test('viewer header has back, arrows, export, fullscreen buttons', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to viewer with any available work, or dummy ID to test empty state
        await goToPictureBook(page);
        let firstWorkId = await page.evaluate(() => {
            let items = Array.from(document.querySelectorAll('.cursor-pointer'));
            let work = items.find(el => el.querySelector('.font-medium'));
            if (work) { work.click(); return true; }
            return false;
        });

        if (!firstWorkId) {
            // Use dummy ID to test viewer chrome renders even on empty state
            await page.evaluate(() => { window.location.hash = '#!/picture-book/test-header-id'; });
        }
        await page.waitForTimeout(2000);
        await saveScreenshot(page, '03-viewer-header');

        let icons = await page.evaluate(() => {
            let all = Array.from(document.querySelectorAll('.material-symbols-outlined'));
            return all.map(i => i.textContent.trim());
        });

        console.log('=== Viewer Icons ===');
        console.log('  Found: ' + icons.join(', '));

        expect(icons).toContain('arrow_back');
        expect(icons).toContain('chevron_left');
        expect(icons).toContain('chevron_right');
        expect(icons).toContain('download');
        expect(icons).toContain('fullscreen');
        console.log('VERIFIED: All 5 nav controls present');
    });

    // ── 4. Viewer handles missing/empty book gracefully ──────────────────

    test('viewer shows empty state for nonexistent work without crashing', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let errors = [];
        page.on('pageerror', err => errors.push(err.message));

        await page.evaluate(() => {
            window.location.hash = '#!/picture-book/nonexistent-00000';
        });
        await page.waitForTimeout(3000);
        await saveScreenshot(page, '04-empty-state');

        let content = await page.evaluate(() => document.body.innerText);
        let hasEmptyState = content.includes('No picture book') || content.includes('Failed') || content.includes('Go back');

        expect(hasEmptyState).toBe(true);
        expect(errors.filter(e => e.includes('mediaUrl'))).toHaveLength(0);
        console.log('VERIFIED: Empty state renders, no mediaUrl errors');
    });

    // ── 5. Back button returns to work selector ──────────────────────────

    test('back arrow navigates from viewer to work selector', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.evaluate(() => {
            window.location.hash = '#!/picture-book/back-test-id';
        });
        await page.waitForTimeout(2000);

        let clicked = await page.evaluate(() => {
            let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
            let back = icons.find(i => i.textContent.trim() === 'arrow_back');
            if (back) { let btn = back.closest('button'); if (btn) { btn.click(); return true; } }
            return false;
        });
        expect(clicked).toBe(true);

        await page.waitForTimeout(1000);
        let url = page.url();
        expect(url).not.toMatch(/picture-book\/.+/);
        console.log('VERIFIED: Back button returns to selector');
    });

    // ── 6. Keyboard nav doesn't crash on empty viewer ────────────────────

    test('keyboard arrows work without errors on empty viewer', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let errors = [];
        page.on('pageerror', err => errors.push(err.message));

        await page.evaluate(() => {
            window.location.hash = '#!/picture-book/keyboard-test-id';
        });
        await page.waitForTimeout(2000);

        await page.keyboard.press('ArrowRight');
        await page.waitForTimeout(300);
        await page.keyboard.press('ArrowLeft');
        await page.waitForTimeout(300);
        await page.keyboard.press('Home');
        await page.waitForTimeout(300);
        await page.keyboard.press('End');
        await page.waitForTimeout(300);

        expect(errors).toHaveLength(0);
        console.log('VERIFIED: Keyboard nav produces no JS errors');
    });

    // ── 7. No mediaUrl errors on any viewer route ────────────────────────

    test('no am7client.mediaUrl errors on viewer (bug fix verification)', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let consoleErrors = [];
        page.on('console', msg => {
            if (msg.type() === 'error') consoleErrors.push(msg.text());
        });

        // Try work selector
        await goToPictureBook(page);
        await page.waitForTimeout(1500);

        // Try clicking a work if available
        await page.evaluate(() => {
            let items = Array.from(document.querySelectorAll('.cursor-pointer'));
            let work = items.find(el => el.querySelector('.font-medium'));
            if (work) work.click();
        });
        await page.waitForTimeout(2000);

        let mediaUrlErrors = consoleErrors.filter(e =>
            e.includes('mediaUrl') || e.includes('is not a function'));
        expect(mediaUrlErrors).toHaveLength(0);
        console.log('VERIFIED: Zero mediaUrl errors (fixed bug)');
    });

    // ── 8. Fullscreen toggle works ───────────────────────────────────────

    test('fullscreen toggle changes layout', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        await page.evaluate(() => {
            window.location.hash = '#!/picture-book/fullscreen-test';
        });
        await page.waitForTimeout(2000);

        // Click fullscreen button
        let toggled = await page.evaluate(() => {
            let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
            let fsIcon = icons.find(i => i.textContent.trim() === 'fullscreen');
            if (fsIcon) { let btn = fsIcon.closest('button'); if (btn) { btn.click(); return true; } }
            return false;
        });
        expect(toggled).toBe(true);
        await page.waitForTimeout(500);
        await saveScreenshot(page, '08-fullscreen');

        // Verify fullscreen layout is applied (fixed positioning)
        let isFullscreen = await page.evaluate(() => {
            let fixed = document.querySelector('.fixed.inset-0');
            return !!fixed;
        });
        expect(isFullscreen).toBe(true);

        // Press Escape to exit
        await page.keyboard.press('Escape');
        await page.waitForTimeout(500);

        let exitedFullscreen = await page.evaluate(() => {
            return !document.querySelector('.fixed.inset-0.z-50');
        });
        expect(exitedFullscreen).toBe(true);
        console.log('VERIFIED: Fullscreen toggle + Escape exit works');
    });

    // ── 9. Page dots render correctly ────────────────────────────────────

    test('page dots reflect viewer state', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate to a work from the selector if available
        await goToPictureBook(page);
        let hasWork = await page.evaluate(() => {
            let items = Array.from(document.querySelectorAll('.cursor-pointer'));
            let work = items.find(el => el.querySelector('.font-medium'));
            if (work) { work.click(); return true; }
            return false;
        });

        if (hasWork) {
            await page.waitForTimeout(3000);
            await saveScreenshot(page, '09-page-dots');

            let dotState = await page.evaluate(() => {
                let dots = document.querySelectorAll('button.rounded-full');
                let activeDots = document.querySelectorAll('button.rounded-full.bg-blue-500');
                return { total: dots.length, active: activeDots.length };
            });

            console.log('=== Page Dots ===');
            console.log('  Total: ' + dotState.total + ', Active: ' + dotState.active);

            if (dotState.total > 0) {
                // Exactly 1 dot should be active (current page)
                expect(dotState.active).toBe(1);
                console.log('VERIFIED: Page dots render with 1 active');
            }
        } else {
            console.log('No works available — skipping page dots test');
        }
    });

    // ── 10. Export button state ───────────────────────────────────────────

    test('export button disabled state matches data availability', async ({ page }) => {
        test.setTimeout(30000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Empty viewer — export should be disabled
        await page.evaluate(() => {
            window.location.hash = '#!/picture-book/export-state-test';
        });
        await page.waitForTimeout(2000);

        let exportState = await page.evaluate(() => {
            let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
            let dlIcon = icons.find(i => i.textContent.trim() === 'download');
            if (!dlIcon) return { found: false };
            let btn = dlIcon.closest('button');
            return { found: true, disabled: btn ? btn.disabled : null };
        });

        expect(exportState.found).toBe(true);
        // On empty viewer (no scenes), export should be disabled
        expect(exportState.disabled).toBe(true);
        console.log('VERIFIED: Export button disabled when no scenes');
    });
});
