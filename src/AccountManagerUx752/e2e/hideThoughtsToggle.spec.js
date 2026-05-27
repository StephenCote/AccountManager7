/**
 * E2E test: Show/Hide thoughts button actually toggles the visibility of
 * <think>...</think> blocks in chat messages.
 *
 * The fix:
 *   - getFormattedContent's cache key now includes hideThoughts (without
 *     this, the cached HTML is returned regardless of toggle state)
 *   - When hideThoughts=false, <think>...</think> blocks are wrapped in a
 *     styled <details class="chat-thoughts"> so the user can actually SEE
 *     the thinking text rather than have it pass through as a raw,
 *     unstyled unknown HTML element
 *
 * Uses the shared test user. Mocks /chat/history so we don't need a real
 * session with thinking-mode content.
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(s) { return Buffer.from(s).toString('base64'); }
function uniq() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }

async function asUser(user, password, fn) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let resp = await ctx.post(REST + '/login', {
            data: {
                schema: 'auth.credential', organizationPath: '/Development',
                name: user, credential: b64(password), type: 'hashed_password'
            }
        });
        if (!resp.ok()) throw new Error('login failed');
        return await fn(ctx);
    } finally {
        await ctx.get(REST + '/logout').catch(() => {});
        await ctx.dispose();
    }
}

async function safeJson(resp) {
    if (!resp.ok()) return null;
    let t = await resp.text();
    if (!t) return null;
    try { return JSON.parse(t); } catch(e) { return null; }
}

test.describe('Show/Hide thoughts toggle', () => {
    let testInfo = {};
    let sessionObjectId = null;
    let sessionName = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);

        /// Create a minimal chatRequest session so the chat UI has something
        /// to select. We mock the history response with thinking content.
        await asUser(testInfo.testUserName, testInfo.testPassword, async (ctx) => {
            let dir = await ctx.get(REST + '/path/make/auth.group/data/B64-' +
                b64('/home/' + testInfo.testUserName + '/ChatRequests').replace(/=/g, '%3D'));
            let dirJson = await safeJson(dir);
            if (!dirJson) return;

            sessionName = 'HideThoughtsTest_' + uniq();
            let r = await ctx.post(REST + '/model', {
                data: {
                    schema: 'olio.llm.chatRequest',
                    name: sessionName,
                    groupId: dirJson.id, groupPath: dirJson.path
                }
            });
            let created = await safeJson(r);
            sessionObjectId = created && created.objectId;
        });
    });

    test('toggling reveals and hides <think> blocks in rendered messages', async ({ page }) => {
        test.setTimeout(90000);
        page.on('pageerror', () => {});

        const thinkingMessage = {
            role: 'assistant',
            content: 'Plain text before. <think>Internal reasoning: the answer is 42.</think> Plain text after.'
        };

        // Mock chat/history so the chat view shows our crafted message regardless
        // of whether the session actually has any. We do this for ALL sessions.
        await page.route('**/AccountManagerService7/rest/chat/history', async (route) => {
            console.log('[mock] /chat/history intercepted: ' + route.request().method() + ' ' + route.request().url());
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({ messages: [thinkingMessage] })
            });
        });

        // Log ALL POSTs for visibility
        page.on('request', (req) => {
            if (req.method() === 'POST' && req.url().includes('/chat/')) {
                console.log('[req] ' + req.method() + ' ' + req.url());
            }
        });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/chat');
        await page.waitForTimeout(3000);

        // Dismiss any setup wizard
        let cancelBtn = page.locator('div.fixed.inset-0 button:has-text("Cancel"), div.fixed.inset-0 button:has-text("Close")').first();
        if (await cancelBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
            await cancelBtn.click();
            await page.waitForTimeout(800);
        }

        // Open sessions sidebar
        let sessionsBtn = page.locator('button[title="Sessions"]');
        await expect(sessionsBtn).toBeVisible({ timeout: 10000 });
        await sessionsBtn.click();
        await page.waitForTimeout(800);

        // Filter to any HideThoughtsTest_ session (could be from a prior run —
        // doesn't matter, our route mock provides the rendered message anyway).
        let filterInput = page.locator('input[placeholder="Filter sessions..."]').first();
        if (await filterInput.isVisible({ timeout: 5000 }).catch(() => false)) {
            await filterInput.fill('HideThoughtsTest_');
            await page.waitForTimeout(500);
        }

        /// Click the title span directly — the sidebar row has icons that
        /// also match div.cursor-pointer, and Playwright's hasText can climb
        /// the tree. Targeting the span ensures the onclick of the parent
        /// row fires via event bubbling.
        let titleSpan = page.locator('span.text-sm.truncate', { hasText: 'HideThoughtsTest_' }).first();
        let titleCount = await page.locator('span.text-sm.truncate', { hasText: 'HideThoughtsTest_' }).count();
        console.log('[diag] matching session title spans: ' + titleCount);
        if (await titleSpan.isVisible({ timeout: 5000 }).catch(() => false)) {
            await titleSpan.click();
            await page.waitForTimeout(4000);
        } else {
            console.log('[diag] no title span visible — falling back to first cursor-pointer row');
            let row = page.locator('div.cursor-pointer.hover\\:bg-gray-100').filter({ hasText: 'HideThoughtsTest_' }).first();
            if (await row.isVisible({ timeout: 2000 }).catch(() => false)) {
                await row.click();
                await page.waitForTimeout(4000);
            }
        }
        await screenshot(page, 'after-session-click');

        // Diagnostic: confirm a message is actually rendered.
        let proseCount = await page.locator('.chat-prose').count();
        console.log('\n=== chat-prose count initial: ' + proseCount + ' ===');
        if (proseCount === 0) {
            // No message rendered — the test premise failed. Bail early.
            test.skip(true, 'No message rendered; check session selection / history mock');
            return;
        }

        let hasDetailsInitial = await page.locator('details.chat-thoughts').count();
        let bodyInitial = (await page.locator('body').textContent()) || '';
        expect(hasDetailsInitial, 'no thoughts details element while hidden').toBe(0);
        expect(bodyInitial, 'thinking text should be hidden initially')
            .not.toContain('Internal reasoning: the answer is 42');

        await screenshot(page, 'thoughts-hidden');

        // Click the toggle (visibility_off icon means currently hidden;
        // clicking will show)
        let toggleBtn = page.locator('button[title="Show thoughts"]').first();
        await expect(toggleBtn).toBeVisible({ timeout: 5000 });
        await toggleBtn.click();
        await page.waitForTimeout(800);

        // After toggle: details element should appear AND text should be visible
        let hasDetailsAfter = await page.locator('details.chat-thoughts').count();
        let bodyAfter = (await page.locator('body').textContent()) || '';

        // Diagnostic: show what got rendered
        let proseHtml = await page.locator('.chat-prose').first().innerHTML().catch(() => '(none)');
        console.log('\n=== chat-prose innerHTML after toggle ===');
        console.log(proseHtml.substring(0, 800));
        console.log('=== details count: ' + hasDetailsAfter + ' ===');
        expect(hasDetailsAfter, 'thoughts details element should appear when shown')
            .toBeGreaterThan(0);
        expect(bodyAfter, 'thinking text should now be visible')
            .toContain('Internal reasoning: the answer is 42');

        await screenshot(page, 'thoughts-shown');

        // Toggle off again — back to hidden
        let toggleBtn2 = page.locator('button[title="Hide thoughts"]').first();
        await expect(toggleBtn2).toBeVisible({ timeout: 5000 });
        await toggleBtn2.click();
        await page.waitForTimeout(800);

        let hasDetailsFinal = await page.locator('details.chat-thoughts').count();
        let bodyFinal = (await page.locator('body').textContent()) || '';
        expect(hasDetailsFinal, 'details should be gone after re-hiding').toBe(0);
        expect(bodyFinal, 'thinking text should be hidden again')
            .not.toContain('Internal reasoning: the answer is 42');
    });
});
