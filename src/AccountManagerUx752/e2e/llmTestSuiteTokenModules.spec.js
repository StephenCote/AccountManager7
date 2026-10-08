/**
 * test-harness/llmTestSuite.js — am7sd / am7imageTokens / am7audioTokens wiring (live).
 *
 * Until 2026-10-07 the suite read the three modules off globalThis (Ux7 IIFE globals that Ux752 never
 * sets), so the "Tokens" category always logged "am7imageTokens not available — image token test
 * skipped" / "104b-e: am7audioTokens not available — audio tests skipped" and the image/audio token
 * tests never ran. They are now imported like every other consumer. This spec runs the real harness
 * (/#!/test, LLM suite, Tokens category only — no LLM calls in that category) as the shared user and
 * reads the TestFramework console lines.
 *
 * Runs against the Vite dev server (:8899); uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('LLM test harness — token modules are imported, not read off globalThis', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('Tokens category runs the image/audio token tests instead of skipping them', async ({ page }) => {
        test.setTimeout(180000);
        let lines = [];
        page.on('console', (msg) => {
            let t = msg.text();
            if (t.startsWith('[TestFramework]')) lines.push(t);
        });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/test');
        let runBtn = page.getByRole('button', { name: /Run Tests/ });
        await expect(runBtn).toBeVisible({ timeout: 30000 });

        // The LLM suite registers lazily on first render; its category toggles appear once it has.
        // Category toggle = [icon "image", label "Tokens"].
        let tokensToggle = page.locator('button').filter({ hasText: /^\s*(image)?\s*Tokens\s*$/ }).first();
        await expect(tokensToggle).toBeVisible({ timeout: 30000 });
        await page.getByRole('button', { name: 'None', exact: true }).first().click();
        await tokensToggle.click();
        await runBtn.click();

        // The completion line is pushed straight into the log list (not through console.log), so read it
        // from the console panel; the per-test lines are taken from the browser console.
        await expect(page.getByText(/=== Suite (complete|aborted):/)).toBeVisible({ timeout: 150000 });
        let summary = await page.getByText(/=== Suite (complete|aborted):/).textContent();

        let tokenLines = lines.filter((l) => l.includes('[token]'));
        expect(lines.filter((l) => /Suite error:/.test(l)), 'suite did not crash (' + summary + ')').toEqual([]);
        expect(tokenLines.length, 'token category produced output').toBeGreaterThan(0);

        // The old skip messages must be gone...
        expect(tokenLines.filter((l) => /not available/.test(l))).toEqual([]);
        // ...and the image/audio token tests that were behind them must have run and passed.
        expect(tokenLines.some((l) => /\[pass\] parseImageTokens: found 2 tokens/.test(l))).toBe(true);
        expect(tokenLines.some((l) => /\[pass\] parseAudioTokens: found \d+ tokens/.test(l))).toBe(true);
        expect(tokenLines.some((l) => /\[pass\] 103b: Multiple image tokens: found 3\/3/.test(l))).toBe(true);
        expect(tokenLines.some((l) => /\[pass\] 104b: Multiple audio tokens: found 2\/2/.test(l))).toBe(true);
        expect(tokenLines.some((l) => /\[pass\] 104f: Mixed content: 1 image \+ 1 audio/.test(l))).toBe(true);
        // Test 101 (API completeness) must pass again: parseImageTokens/parseAudioTokens restored.
        expect(tokenLines.some((l) => /\[pass\] All 5 methods present/.test(l))).toBe(true);
        // Known, unrelated, pre-existing harness failure (not part of this fix, reported 2026-10-07):
        // 102b "pruneForDisplay citations removed" — Ux752's pruneForDisplay dropped the Ux7
        // `pruneOut("--- CITATION", "END CITATIONS ---")` step. Anything else failing is a regression.
        let fails = tokenLines.filter((l) => /\[fail\]/.test(l) && !/pruneForDisplay citations removed/.test(l));
        expect(fails, 'no token test failed (other than the known 102b citation prune)').toEqual([]);
    });
});
