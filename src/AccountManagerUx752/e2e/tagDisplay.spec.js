/**
 * Tag display E2E tests — verifies tags show on data.data object view
 * Uses ensureSharedTestUser, creates test data via API, navigates to object view.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('Tag display on data.data object view', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('tags display on data.data object form', async ({ page }) => {
        test.setTimeout(120000);

        // Create test data via client API: a data.data object, two tags, and membership
        let setup = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { page: pg } = await import('/src/core/pageClient.js');
            let { am7model } = await import('/src/core/model.js');

            let ts = Date.now().toString(36);

            // Ensure ~/Data directory
            let dataDir = await pg.makePath('auth.group', 'data', '~/Data');
            if (!dataDir) return { error: 'Failed to create ~/Data' };

            // Ensure ~/Tags directory
            let tagDir = await pg.makePath('auth.group', 'data', '~/Tags');
            if (!tagDir) return { error: 'Failed to create ~/Tags' };

            // Create a test data.data object
            let dataObj = am7model.newPrimitive('data.data');
            dataObj.name = 'TagTest-' + ts;
            dataObj.groupId = dataDir.id;
            dataObj.groupPath = dataDir.path;
            dataObj.contentType = 'text/plain';
            dataObj.description = 'Test object for tag display';
            let created = await new Promise(r => am7client.create('data.data', dataObj, r));
            if (!created || !created.objectId) return { error: 'Failed to create data.data' };

            // Create two test tags
            let tag1 = am7model.newPrimitive('data.tag');
            tag1.name = 'TestTag1-' + ts;
            tag1.type = 'data.data';
            tag1.groupId = tagDir.id;
            tag1.groupPath = tagDir.path;
            let t1 = await new Promise(r => am7client.create('data.tag', tag1, r));

            let tag2 = am7model.newPrimitive('data.tag');
            tag2.name = 'TestTag2-' + ts;
            tag2.type = 'data.data';
            tag2.groupId = tagDir.id;
            tag2.groupPath = tagDir.path;
            let t2 = await new Promise(r => am7client.create('data.tag', tag2, r));

            if (!t1 || !t1.objectId || !t2 || !t2.objectId) return { error: 'Failed to create tags' };

            // Add data.data as member of both tags (participation-based tagging)
            let m1 = await new Promise(r => am7client.member('data.tag', t1.objectId, null, 'data.data', created.objectId, true, r));
            let m2 = await new Promise(r => am7client.member('data.tag', t2.objectId, null, 'data.data', created.objectId, true, r));

            return {
                dataObjectId: created.objectId,
                tag1Name: t1.name,
                tag2Name: t2.name,
                member1: m1,
                member2: m2,
                ts: ts
            };
        });

        console.log('Setup result:', JSON.stringify(setup));
        expect(setup.error).toBeUndefined();
        expect(setup.dataObjectId).toBeTruthy();

        // Navigate to the data.data object view
        await page.goto('/#!/view/data.data/' + setup.dataObjectId);
        await page.waitForFunction(
            () => window.location.hash.includes('/view/data.data/'),
            { timeout: 15000 }
        );

        // Wait for form to load
        let formField = page.locator('input, select, textarea, label');
        await expect(formField.first()).toBeVisible({ timeout: 30000 });
        await page.waitForTimeout(1000);

        // Look for the "Tags" tab (sub-form tab)
        let tagsTab = page.locator('button', { hasText: 'Tags' });
        let hasTagsTab = await tagsTab.first().isVisible({ timeout: 5000 }).catch(() => false);

        if (hasTagsTab) {
            // Click the Tags tab
            await tagsTab.first().click();
            await page.waitForTimeout(2000);

            // Wait for tags to load asynchronously (participation query)
            let tag1Locator = page.locator('div', { hasText: setup.tag1Name }).first();
            await expect(tag1Locator).toBeVisible({ timeout: 15000 });

            let tag2Locator = page.locator('div', { hasText: setup.tag2Name }).first();
            await expect(tag2Locator).toBeVisible({ timeout: 5000 });

            // Verify item count shows 2
            let itemCount = page.locator('text=2 items');
            await expect(itemCount).toBeVisible({ timeout: 5000 });

            await screenshot(page, 'tag-display-tags-visible');
        } else {
            // No tags tab — check all visible tabs
            let allTabs = await page.locator('button').filter({ hasText: /Tags|Attributes/ }).allTextContents();
            console.log('Available tabs:', allTabs);

            await screenshot(page, 'tag-display-no-tags-tab');
            // Fail with diagnostic info
            expect(hasTagsTab).toBe(true);
        }
    });
});
