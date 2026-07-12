import { test, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, addUserToRole } from './helpers/api.js';

test('debug userRoles', async ({ page, request }) => {
    let testInfo = await ensureSharedTestUser(request);
    await addUserToRole(request, testInfo.user.objectId, 'RoleReaders');
    await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    await page.waitForTimeout(1000);
    let roles = await page.evaluate(() => window.am7dbg.roles());
    console.log('roles', JSON.stringify(roles));
    let app = await page.evaluate(() => window.am7dbg.page ? window.am7dbg.page.application : 'NOPAGE');
    console.log('application', JSON.stringify(app));
});
