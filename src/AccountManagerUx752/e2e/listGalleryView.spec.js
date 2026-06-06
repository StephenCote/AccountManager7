/**
 * List-control gallery view — E2E test against live backend.
 *
 * Two regressions:
 *   1) List of olio.charPerson must load (sanity: pagination query must not
 *      break). Guards against future field-projection breakage.
 *   2) Toggle to gallery view (apps icon) must show character portrait
 *      thumbnails, not just placeholder icons.
 *
 * Test user: ensureSharedTestUser per CLAUDE.md (NEVER admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

let _testUser = {};

/// Find a Characters-style directory that has at least one charPerson row
/// for the test user. Returns { container: <auth.group record>, hasPortraitChars: bool }
/// or null if no chars exist anywhere for this user.
async function findCharContainer(testUserName, testPassword) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let loginResp = await apiLogin(ctx, { user: testUserName, password: testPassword });
        if (!loginResp.ok()) return null;

        // Find any charPerson row to discover its container.
        let cpResp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.charPerson',
                request: ['id', 'objectId', 'name', 'groupId', 'groupPath'],
                recordCount: 50
            }
        });
        if (!cpResp.ok()) { await apiLogout(ctx); return null; }
        let body = await cpResp.json();
        let chars = (body && body.results) || [];
        if (!chars.length) { await apiLogout(ctx); return null; }

        // Group chars by groupId so we can find a directory with the most rows
        let byGroup = {};
        chars.forEach(c => {
            if (c.groupId) {
                byGroup[c.groupId] = byGroup[c.groupId] || [];
                byGroup[c.groupId].push(c);
            }
        });
        let bestGroupId = null;
        let bestCount = 0;
        Object.keys(byGroup).forEach(g => {
            if (byGroup[g].length > bestCount) { bestCount = byGroup[g].length; bestGroupId = g; }
        });
        if (!bestGroupId) { await apiLogout(ctx); return null; }

        // Resolve the auth.group record so we have its objectId for routing.
        let gResp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'auth.group',
                fields: [{ name: 'id', comparator: 'equals', value: parseInt(bestGroupId, 10) }],
                request: ['id', 'objectId', 'name', 'path'],
                recordCount: 1
            }
        });
        if (!gResp.ok()) { await apiLogout(ctx); return null; }
        let gbody = await gResp.json();
        let container = (gbody && gbody.results && gbody.results[0]) || null;
        if (!container) { await apiLogout(ctx); return null; }

        // Walk the full chars list (not just one group's sample) looking for
        // ANY character with a populated profile.portrait. If found, use
        // ITS container so the gallery has at least one real portrait.
        let charsWithPortrait = [];
        for (let c of chars) {
            try {
                let fullResp = await ctx.get(REST + '/model/olio.charPerson/' + c.objectId + '/full');
                if (!fullResp.ok()) continue;
                let full = await fullResp.json();
                let p = full && full.profile && full.profile.portrait;
                if (p && p.contentType && p.contentType.match(/^image/) && p.name && p.groupPath) {
                    charsWithPortrait.push({ char: full, container: c.groupId });
                }
                if (charsWithPortrait.length >= 5) break;
            } catch (e) { /* ignore */ }
        }

        // Prefer a container that has portrait-equipped chars; fall back to the
        // largest group if none have portraits (test will skip gallery assertion).
        if (charsWithPortrait.length > 0) {
            let portraitGroupId = charsWithPortrait[0].container;
            // Re-resolve the container record for that groupId
            let g2Resp = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'auth.group',
                    fields: [{ name: 'id', comparator: 'equals', value: parseInt(portraitGroupId, 10) }],
                    request: ['id', 'objectId', 'name', 'path'],
                    recordCount: 1
                }
            });
            if (g2Resp.ok()) {
                let g2body = await g2Resp.json();
                let portraitContainer = (g2body && g2body.results && g2body.results[0]) || null;
                if (portraitContainer) container = portraitContainer;
            }
        }

        await apiLogout(ctx);
        return {
            container: container,
            withPortrait: charsWithPortrait.length,
            totalScanned: chars.length,
            totalInGroup: byGroup[container.id] ? byGroup[container.id].length : 0
        };
    } finally {
        await ctx.dispose();
    }
}

/// Tiny 1x1 transparent PNG — enough for the gallery to consider the
/// portrait an image (it has contentType + name + groupPath after the
/// data.data is created).
const ONE_PIXEL_PNG_BASE64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';

/// Ensure the test user has at least one charPerson with a populated
/// profile.portrait so the gallery test can assert on real thumbnail
/// rendering. Returns the container record + the test charPerson record.
async function ensurePortraitedCharForTest(testUserName, testPassword) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let loginResp = await apiLogin(ctx, { user: testUserName, password: testPassword });
        if (!loginResp.ok()) throw new Error('test user login failed');

        // 1. Find or create the Characters directory
        let path = '/home/' + testUserName + '/Characters';
        let b64Path = 'B64-' + Buffer.from(path).toString('base64').replace(/=/g, '%3D');
        let dirResp = await ctx.get(REST + '/path/make/auth.group/data/' + b64Path);
        if (!dirResp.ok()) throw new Error('failed to make Characters dir');
        let charDir = await dirResp.json();

        // 2. Create a tiny test charPerson
        let charName = 'GalleryTest-' + Date.now().toString(36);
        let createCharResp = await ctx.post(REST + '/model/', {
            data: {
                schema: 'olio.charPerson',
                name: charName,
                firstName: charName,
                lastName: 'Gallery',
                groupId: charDir.id,
                groupPath: charDir.path,
                gender: 'female',
                age: 30
            }
        });
        if (!createCharResp.ok()) throw new Error('failed to create charPerson');
        let createdChar = JSON.parse(await createCharResp.text());

        // 3. Fetch the full character to get its profile.id
        let fullResp = await ctx.get(REST + '/model/olio.charPerson/' + createdChar.objectId + '/full');
        if (!fullResp.ok()) throw new Error('failed to load created charPerson');
        let fullChar = await fullResp.json();
        let profile = fullChar && fullChar.profile;
        if (!profile || !profile.id) throw new Error('charPerson has no profile to attach portrait');

        // 4. Create a tiny test portrait data.data record under the Characters dir
        let portraitName = 'gallery-test-portrait-' + Date.now().toString(36) + '.png';
        let createImgResp = await ctx.post(REST + '/model/', {
            data: {
                schema: 'data.data',
                name: portraitName,
                groupId: charDir.id,
                groupPath: charDir.path,
                contentType: 'image/png',
                byteStore: ONE_PIXEL_PNG_BASE64
            }
        });
        if (!createImgResp.ok()) throw new Error('failed to create portrait data.data');
        let portraitRec = JSON.parse(await createImgResp.text());

        // 5. PATCH the profile to set portrait = portraitRec
        let patchResp = await ctx.fetch(REST + '/model', {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            data: {
                schema: 'identity.profile',
                id: profile.id,
                objectId: profile.objectId,
                portrait: { id: portraitRec.id, objectId: portraitRec.objectId, schema: 'data.data' }
            }
        });
        if (!patchResp.ok()) throw new Error('failed to PATCH profile.portrait');

        await apiLogout(ctx);
        return {
            container: charDir,
            charObjectId: createdChar.objectId,
            charName: charName,
            portraitOid: portraitRec.objectId
        };
    } finally {
        await ctx.dispose();
    }
}

async function deleteByObjectId(testUserName, testPassword, type, objectId) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        await apiLogin(ctx, { user: testUserName, password: testPassword });
        await ctx.fetch(REST + '/model/' + type + '/' + objectId, { method: 'DELETE' });
        await apiLogout(ctx);
    } catch (e) { /* best effort */ }
    finally { await ctx.dispose(); }
}

test.describe('List control — charPerson gallery view', () => {
    let charSetup = null;
    let createdFixture = null;

    test.beforeAll(async ({ request }) => {
        _testUser = await ensureSharedTestUser(request);
        try {
            createdFixture = await ensurePortraitedCharForTest(_testUser.testUserName, _testUser.testPassword);
            console.log('[gallery-view] created test fixture: char=' + createdFixture.charObjectId
                + ' in ' + createdFixture.container.path);

            // Verify the fixture actually has a portrait via /full
            let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
            try {
                await apiLogin(ctx, { user: _testUser.testUserName, password: _testUser.testPassword });
                let r = await ctx.get(REST + '/model/olio.charPerson/' + createdFixture.charObjectId + '/full');
                let body = r.ok() ? await r.json() : null;
                console.log('[gallery-view] fixture verify — profile=' + (body && body.profile ? 'YES' : 'NO')
                    + ' portrait=' + (body && body.profile && body.profile.portrait ? 'YES' : 'NO')
                    + ' contentType=' + (body && body.profile && body.profile.portrait && body.profile.portrait.contentType ? body.profile.portrait.contentType : 'n/a'));
                await apiLogout(ctx);
            } finally { await ctx.dispose(); }
        } catch (e) {
            console.warn('[gallery-view] failed to build portrait fixture: ' + e.message);
        }
        charSetup = await findCharContainer(_testUser.testUserName, _testUser.testPassword);
        console.log('[gallery-view] charSetup: withPortrait=' + (charSetup && charSetup.withPortrait)
            + ' totalScanned=' + (charSetup && charSetup.totalScanned)
            + ' container=' + (charSetup && charSetup.container && charSetup.container.path));
    });

    test.afterAll(async () => {
        if (createdFixture) {
            await deleteByObjectId(_testUser.testUserName, _testUser.testPassword,
                'olio.charPerson', createdFixture.charObjectId);
            await deleteByObjectId(_testUser.testUserName, _testUser.testPassword,
                'data.data', createdFixture.portraitOid);
        }
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: _testUser.testUserName, password: _testUser.testPassword });
    });

    test('charPerson list loads with non-zero rows (pagination query is not broken)', async ({ page }) => {
        test.setTimeout(45000);
        if (!charSetup) {
            test.skip(true, 'No charPerson rows exist for the test user — cannot exercise list pagination');
            return;
        }

        let containerOid = charSetup.container.objectId;
        await page.goto('/#!/list/olio.charPerson/' + containerOid);
        await page.waitForTimeout(2500);

        // Table view renders <tr> rows for each item (header row 0).
        let dataRow = page.locator('table tbody tr').first();
        await expect(dataRow).toBeVisible({ timeout: 15000 });

        await screenshot(page, 'list-charperson-table');

        let bodyText = await page.locator('body').innerText();
        expect(bodyText).not.toMatch(/no results found/i);
    });

    test('gallery view of charPerson list shows portrait thumbnails, not just placeholders', async ({ page }) => {
        test.setTimeout(90000);
        if (!charSetup) {
            test.skip(true, 'No charPerson rows exist for the test user — cannot exercise gallery view');
            return;
        }
        if (charSetup.withPortrait === 0) {
            test.skip(true, 'No charPersons in the test user\'s container have a portrait — cannot assert thumbnails');
            return;
        }

        let containerOid = charSetup.container.objectId;
        console.log('[gallery-view] using container ' + containerOid + ' with ~' + charSetup.totalInGroup
            + ' chars in group, ' + charSetup.withPortrait + ' portrait-equipped found in '
            + charSetup.totalScanned + ' scanned');

        await page.goto('/#!/list/olio.charPerson/' + containerOid);
        await page.waitForTimeout(2500);

        // Confirm the table loaded first.
        await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });

        // Click the gallery toggle (apps material symbol).
        let gridBtn = page.locator('button:has(span.material-symbols-outlined:text("apps"))').first();
        await expect(gridBtn).toBeVisible({ timeout: 10000 });
        await gridBtn.click();
        await page.waitForTimeout(3000);

        // Grid tiles must be present.
        let thumbs = page.locator('[data-grid-thumb]');
        await expect(thumbs.first()).toBeVisible({ timeout: 15000 });

        await screenshot(page, 'list-charperson-gallery');

        let totalTiles = await thumbs.count();
        let withImg = await page.locator('[data-grid-thumb] img').count();
        let firstSrc = await page.locator('[data-grid-thumb] img').first().getAttribute('src').catch(() => null);
        console.log('[gallery-view] tiles=' + totalTiles + ' withImg=' + withImg
            + ' firstSrc=' + (firstSrc ? firstSrc.substring(0, 80) : '(no img)'));

        // Primary assertion: at least one tile MUST render an <img>. The bug
        // we're fixing has withImg === 0 (only placeholder icons).
        expect(withImg).toBeGreaterThan(0);

        // First img must have a non-empty src.
        expect(firstSrc).toBeTruthy();
        expect(firstSrc.length).toBeGreaterThan(10);
    });
});
