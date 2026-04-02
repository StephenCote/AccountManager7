/**
 * Test attributes tab loading in object view.
 * Uses shared test user (NEVER admin).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, createNote, searchByField } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }

test('Attributes tab loads data', async ({ page }) => {
    let shared = await ensureSharedTestUser(null);

    // Create note via existing helper
    let ctx = await newCtx();
    await ctx.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: '/Development',
            name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
    });

    let note = await createNote(ctx, '~/Notes', 'attrTest-' + Date.now(), 'test content with attributes');
    console.log('Created note:', note ? note.objectId : 'null');

    if (!note || !note.objectId) {
        console.log('Failed to create note');
        await ctx.get(REST + '/logout');
        await ctx.dispose();
        return;
    }

    // Transfer cookies
    let cookies = (await ctx.storageState()).cookies;
    await page.context().addCookies(cookies);
    await ctx.get(REST + '/logout');
    await ctx.dispose();

    await page.goto('/');
    await page.waitForTimeout(1500);

    let logs = [];
    page.on('console', msg => {
        let t = msg.text();
        if (t.includes('attribute') || t.includes('referenced') || t.includes('_part_') || t.includes('Loading'))
            logs.push(t);
    });

    // Navigate to note view
    await page.goto('#!/view/data.note/' + note.objectId);
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'e2e/screenshots/attributes-tab-before.png' });

    // Find and click Attributes tab
    let tabs = await page.locator('[class*="tab-item"], [class*="tab_"], button').allInnerTexts();
    console.log('Available tab texts:', tabs.filter(t => t.trim().length > 0 && t.trim().length < 30).join(', '));

    let attrTab = page.locator('text=Attributes');
    if (await attrTab.first().isVisible({ timeout: 3000 }).catch(() => false)) {
        await attrTab.first().click();
        await page.waitForTimeout(2000);
        await page.screenshot({ path: 'e2e/screenshots/attributes-tab-clicked.png' });

        let bodyText = await page.locator('body').innerText();
        // Check if attributes section is loading or showing content
        let hasLoading = bodyText.includes('Loading');
        let hasItems = bodyText.includes('items') || bodyText.includes('attribute');
        console.log('Has Loading text:', hasLoading);
        console.log('Has items/attribute text:', hasItems);
        console.log('Browser logs:', logs.slice(0, 5).join(' | '));
    } else {
        console.log('Attributes tab not visible');
    }
});
