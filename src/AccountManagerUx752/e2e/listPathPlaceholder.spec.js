/**
 * Verify list view filter input shows group NAME not PATH.
 * Uses shared test user (NEVER admin).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const REST = 'https://localhost:8899/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: 'https://localhost:8899', ignoreHTTPSErrors: true }); }
async function safeJson(resp) { if (!resp.ok()) return null; try { return JSON.parse(await resp.text()); } catch { return null; } }

test.describe('List Path Placeholder', () => {
    let shared;
    let notesDirOid;

    test.beforeAll(async () => {
        shared = await ensureSharedTestUser(null);

        // Ensure test user has a Notes directory
        let ctx = await newCtx();
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
        });
        let notesDir = await safeJson(await ctx.get(REST + '/path/make/auth.group/data/' +
            'B64-' + b64('~/Notes').replace(/=/g, '%3D')));
        if (notesDir) notesDirOid = notesDir.objectId;
        await ctx.get(REST + '/logout');
        await ctx.dispose();
    });

    test('Filter input placeholder shows group name, not path', async ({ page, request }) => {
        expect(notesDirOid).toBeTruthy();

        // Login as shared test user
        await apiLogin(request, { user: shared.testUserName, password: shared.testPassword });

        // Navigate to notes list
        await page.goto('#!/list/data.note/' + notesDirOid);
        await page.waitForTimeout(2500);
        await page.screenshot({ path: 'e2e/screenshots/list-path-01-notes.png' });

        // Check the filter input placeholder
        let filterInput = page.locator('#listFilter');
        await expect(filterInput).toBeVisible({ timeout: 5000 });
        let placeholder = await filterInput.getAttribute('placeholder');
        console.log('Notes dir filter placeholder:', JSON.stringify(placeholder));

        // Must NOT start with / (that would be a path)
        expect(placeholder || '').not.toMatch(/^\//);
        expect(placeholder || '').not.toContain('/');
        console.log('PASS: placeholder is "' + placeholder + '"');

        await apiLogout(request);
    });
});
