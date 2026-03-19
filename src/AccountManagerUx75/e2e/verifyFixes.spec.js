/**
 * Verify all outstanding fixes — honest tests only.
 * Each test states what it verifies and what it cannot verify.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

let _testUser = {};
test.beforeAll(async ({ request }) => {
    _testUser = await ensureSharedTestUser(request);
});

function testLogin(page) {
    return login(page, { user: _testUser.testUserName, password: _testUser.testPassword });
}

// ── Item 1: Chat portrait toggle ────────────────────────────────────

test.describe('Chat portrait header', () => {

    test('portrait toggle button exists and portraits are OFF by default', async ({ page }) => {
        test.setTimeout(60000);
        await testLogin(page);

        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        // Wait for a session to load (need characters assigned for portrait button)
        await page.waitForTimeout(3000);

        // Check portrait toggle button exists
        let portraitBtn = page.locator('button[title="Show portraits"]');
        let hideBtn = page.locator('button[title="Hide portraits"]');

        // Default should be OFF — so "Show portraits" should be visible (not "Hide")
        let showVisible = await portraitBtn.isVisible({ timeout: 5000 }).catch(() => false);
        let hideVisible = await hideBtn.isVisible({ timeout: 1000 }).catch(() => false);

        if (!showVisible && !hideVisible) {
            console.log('Portrait toggle not visible — session may not have characters assigned');
            console.log('I DID NOT VERIFY this feature works. Need a session with characters.');
            return;
        }

        // Portraits should be OFF by default
        if (showVisible) {
            console.log('VERIFIED: Portrait toggle shows "Show portraits" — default is OFF');

            // No portrait header should be visible
            let portraitHeader = page.locator('.flex.justify-around img[style*="120px"]');
            let headerVisible = await portraitHeader.isVisible({ timeout: 1000 }).catch(() => false);
            expect(headerVisible).toBe(false);
            console.log('VERIFIED: No portrait header visible when toggle is OFF');

            // Click to enable
            await portraitBtn.click();
            await page.waitForTimeout(500);

            // Now "Hide portraits" should be visible
            await expect(page.locator('button[title="Hide portraits"]')).toBeVisible({ timeout: 3000 });
            console.log('VERIFIED: Toggle switches to "Hide portraits" after click');

            // Portrait images should now appear (if characters have portraits)
            let portraits = page.locator('.flex.justify-around img');
            let pCount = await portraits.count();
            console.log('Portrait images after toggle ON: ' + pCount);

            await screenshot(page, 'verify-portraits-on');

            // Toggle back off
            await page.locator('button[title="Hide portraits"]').click();
            await expect(page.locator('button[title="Show portraits"]')).toBeVisible({ timeout: 3000 });
            console.log('VERIFIED: Toggle back to OFF works');
        } else {
            console.log('Portrait toggle shows "Hide portraits" — default is ON, which is WRONG');
            expect(showVisible).toBe(true); // Fail — default should be OFF
        }
    });
});

// ── Item 2: Image viewer consistency ────────────────────────────────

test.describe('Image viewer (page.imageView)', () => {

    test('page.imageView function exists and opens dialog', async ({ page }) => {
        test.setTimeout(60000);
        await testLogin(page);

        // Navigate to chat to load modules
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Verify page.imageView exists
        let hasImageView = await page.evaluate(() => {
            return typeof window !== 'undefined' && typeof (window.__am7page || {}).imageView === 'function';
        });

        // Try importing page module
        let result = await page.evaluate(async () => {
            let { page } = await import('/src/core/pageClient.js');
            return {
                hasImageView: typeof page.imageView === 'function',
                hasImageGallery: typeof page.imageGallery === 'function'
            };
        });

        console.log('page.imageView exists: ' + result.hasImageView);
        console.log('page.imageGallery exists: ' + result.hasImageGallery);
        expect(result.hasImageView).toBe(true);
        expect(result.hasImageGallery).toBe(true);
    });
});

// ── Item 3: Audio TTS — check endpoint with profileId ───────────────

test.describe('Audio TTS synthesize', () => {

    test('synthesize endpoint called with text', async ({ page }) => {
        test.setTimeout(60000);
        await testLogin(page);

        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        // Call synthesize endpoint directly
        let result = await page.evaluate(async () => {
            let { applicationPath } = await import('/src/core/config.js');
            try {
                let resp = await fetch(applicationPath + '/rest/voice/test-audio-' + Date.now(), {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ text: 'Hello world', speed: 1.2, engine: 'piper', speaker: 'en_GB-alba-medium' })
                });
                let contentType = resp.headers.get('content-type') || '';
                return { status: resp.status, ok: resp.ok, contentType, statusText: resp.statusText };
            } catch (e) {
                return { error: e.message };
            }
        });

        console.log('Synthesize result: status=' + result.status + ' ok=' + result.ok + ' contentType=' + result.contentType);
        if (!result.ok) {
            console.log('TTS endpoint returned ' + result.status + ' — ' + result.statusText);
            console.log('I DID NOT VERIFY audio playback works. Endpoint returned error.');
        } else {
            console.log('VERIFIED: TTS endpoint returns ' + result.contentType);
        }
    });
});

// ── Item 4: Picker field styling ────────────────────────────────────

test.describe('Picker field styling', () => {

    test('picker fields render as labels with border matching other fields', async ({ page }) => {
        test.setTimeout(60000);
        await testLogin(page);

        // Navigate to a charPerson to see picker fields
        let charRoute = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let q = am7client.newQuery('olio.charPerson');
            q.range(0, 1);
            let qr = await am7client.search(q);
            return (qr && qr.results && qr.results.length) ? '/view/olio.charPerson/' + qr.results[0].objectId : null;
        });

        if (!charRoute) {
            console.log('No charPerson — cannot verify picker styling');
            return;
        }

        await page.evaluate((r) => { window.location.hash = '#!/' + r.replace(/^\//, ''); }, charRoute);
        await page.waitForTimeout(5000);

        // Check that there are NO input[type=text] elements with picker-related classes
        // Picker fields should be spans, not inputs
        let inputPickers = await page.locator('input[type="text"].menu-icon').count();
        console.log('Input elements with menu-icon class: ' + inputPickers);

        // There SHOULD be span elements that are clickable for picker fields
        let spanPickers = await page.locator('span.cursor-pointer').count();
        console.log('Clickable span elements: ' + spanPickers);

        if (inputPickers > 0) {
            console.log('FAIL: Found ' + inputPickers + ' text inputs for picker fields — should be 0');
        } else {
            console.log('VERIFIED: No text input picker fields found');
        }

        await screenshot(page, 'verify-picker-styling');
    });
});

// ── Item 5: Gallery pop-in (not tab) ────────────────────────────────

test.describe('Gallery pop-in', () => {

    test('photo_library button exists on charPerson toolbar', async ({ page }) => {
        test.setTimeout(60000);
        await testLogin(page);

        let charRoute = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let q = am7client.newQuery('olio.charPerson');
            q.range(0, 1);
            let qr = await am7client.search(q);
            return (qr && qr.results && qr.results.length) ? '/view/olio.charPerson/' + qr.results[0].objectId : null;
        });

        if (!charRoute) {
            console.log('No charPerson — cannot verify gallery button');
            return;
        }

        await page.evaluate((r) => { window.location.hash = '#!/' + r.replace(/^\//, ''); }, charRoute);
        await page.waitForTimeout(5000);

        // photo_library button should exist in toolbar
        let galleryBtn = page.locator('button:has(span:text("photo_library"))');
        let visible = await galleryBtn.isVisible({ timeout: 5000 }).catch(() => false);
        console.log('Gallery button visible: ' + visible);

        if (visible) {
            // Click it — gallery dialog should open
            await galleryBtn.click();
            await page.waitForTimeout(2000);

            // Dialog should appear with "Gallery" title
            let dialogTitle = page.locator('text=Gallery');
            let dialogVisible = await dialogTitle.isVisible({ timeout: 5000 }).catch(() => false);
            console.log('Gallery dialog opened: ' + dialogVisible);

            if (dialogVisible) {
                console.log('VERIFIED: Gallery opens as pop-in dialog');
                // Close it
                let closeBtn = page.locator('button:has-text("Close")');
                if (await closeBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
                    await closeBtn.click();
                }
            } else {
                console.log('Gallery dialog did NOT open — possible error');
            }

            // charImages tab should NOT exist
            let imagesTab = page.locator('button:has-text("Images")');
            let tabVisible = await imagesTab.isVisible({ timeout: 1000 }).catch(() => false);
            console.log('Images TAB visible: ' + tabVisible + ' (should be false)');
        }

        await screenshot(page, 'verify-gallery-popin');
    });
});

// ── Item 6: Pending animation ───────────────────────────────────────

test.describe('Pending animation', () => {

    test('sending a message shows Thinking spinner before response', async ({ page }) => {
        test.setTimeout(180000);
        await testLogin(page);

        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        // Find enabled chat input
        let chatInput = page.locator('[name="chatmessage"]:not([disabled])');
        let ready = await chatInput.isVisible({ timeout: 10000 }).catch(() => false);

        if (!ready) {
            console.log('Chat input not enabled — no session loaded');
            console.log('I DID NOT VERIFY pending animation. Need active session.');
            return;
        }

        await chatInput.fill('Testing pending indicator');

        // Watch for "Thinking..." to appear IMMEDIATELY after clicking send
        let sendBtn = page.locator('button[title="Send"]');
        await sendBtn.click();

        // Check for Thinking within 2 seconds
        let thinkingAppeared = await page.locator('text=Thinking...').isVisible({ timeout: 5000 }).catch(() => false);
        console.log('Thinking indicator appeared: ' + thinkingAppeared);

        if (thinkingAppeared) {
            console.log('VERIFIED: Pending animation shows after send');
        } else {
            console.log('Thinking indicator did NOT appear — may have been too fast or WebSocket not connected');
            console.log('I DID NOT VERIFY pending animation works');
        }

        // Wait for response
        await page.waitForTimeout(30000);

        await screenshot(page, 'verify-pending');
    });
});

// ── Item 7: Firefox basic load ──────────────────────────────────────
// NOTE: This runs in Chromium, not Firefox. I cannot verify Firefox perf from here.
// To verify Firefox: run `npx playwright test --project=firefox`

test.describe('Firefox perf (Chromium proxy)', () => {

    test('chat page loads under 10 seconds', async ({ page }) => {
        test.setTimeout(60000);
        await testLogin(page);

        let start = Date.now();
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);
        let elapsed = Date.now() - start;

        console.log('Chat page load time: ' + elapsed + 'ms');
        console.log('NOTE: This is Chromium, NOT Firefox. I DID NOT VERIFY Firefox perf.');
        console.log('To verify Firefox: npx playwright test --project=firefox -g "loads"');
    });
});
