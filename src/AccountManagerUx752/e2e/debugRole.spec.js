import { test, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, addUserToRole } from './helpers/api.js';

test('debug role list nav', async ({ page, request }) => {
    let testInfo = await ensureSharedTestUser(request);
    await addUserToRole(request, testInfo.user.objectId, 'RoleReaders');
    page.on('console', msg => console.log('[console]', msg.type(), msg.text()));
    page.on('pageerror', err => console.log('[pageerror]', err.message));
    await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    await page.goto('/#!/list/auth.role?startRecord=0&recordCount=200');
    await page.waitForTimeout(3000);
    console.log('HASH:', await page.evaluate(() => window.location.hash));
    let html = await page.locator('.result-nav-outer, .tabular-results-table, .list-empty').first().textContent().catch(()=>'NONE');
    console.log('CONTENT:', html);
    await page.screenshot({ path: 'e2e/screenshots/debugRole.png', fullPage: true });
});
