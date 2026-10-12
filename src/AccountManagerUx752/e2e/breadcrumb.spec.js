/**
 * Breadcrumb navigation tests — exercises breadcrumb against the live backend.
 * Uses a per-run test user (not admin).
 *
 * Markup (src/components/breadcrumb.js:104-146, 210-232): `nav.breadcrumb-bar ol.breadcrumb-list` holds one
 * `li` per path segment. Each navigable segment is a `div.context-menu-container-slim` with
 *   - `button#<id>Nav` (segment name, click navigates),
 *   - `button#<id>Button` holding `span.material-symbols-outlined` "expand_more" (breadcrumb.js:137), and
 *   - `div#<id>.context-menu-48` that `navigation.js` fills with the child folders as `button.context-menu-item`
 *     and flips to `.transition-full` when open (navigation.js:85-89, 110-133, 156-164).
 * where <id> = path with "/" -> "zZz" (breadcrumb.js:111-112). There never was a `.breadcrumb-expand-btn` class.
 * `/home` is rendered disabled for non-admin users (breadcrumb.js:117-121).
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';
import { setupTestUser, cleanupTestUser, ensurePath, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

// PLAYWRIGHT_BASE_URL is mandatory for every run; no local-dev fallback.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL;

const BAR = 'nav.breadcrumb-bar';
const SEGMENTS = BAR + ' ol.breadcrumb-list li:has(button[id$="Nav"])';

function crumbId(path) {
    return path.replace(/\//g, 'zZz').replace(/\s/g, 'yYy');
}

async function segmentTexts(page) {
    return page.locator(SEGMENTS + ' button[id$="Nav"]').allTextContents();
}

async function openList(page, type, dirObjectId) {
    await page.goto('/#!/list/' + type + '/' + dirObjectId);
    await page.waitForFunction(
        (oid) => window.location.hash.includes('/list/') && window.location.hash.includes(oid),
        dirObjectId, { timeout: 15000 }
    );
    await page.locator('.result-nav-outer').first().waitFor({ state: 'visible', timeout: 15000 });
    await expect(page.locator(BAR), 'breadcrumb bar renders on a list route').toBeVisible({ timeout: 15000 });
}

test.describe('Breadcrumb navigation', () => {
    let testInfo = {};
    let dataDir;      // ~/Data
    let childDir;     // ~/Data/<childName>
    let childName;

    test.beforeAll(async ({ request }) => {
        // Provisioning a fresh user (admin create + first login initialising the home tree) is slow on the
        // Docker stack; give it ample room — this is not a pass/fail timing.
        test.setTimeout(300000);
        const suffix = 'bc' + Date.now().toString(36);
        testInfo = await setupTestUser(request, { suffix, noteCount: 2 });
        if (!testInfo.user || !testInfo.user.objectId) throw new Error('setupTestUser did not create ' + testInfo.testUserName);

        // As the test user: make ~/Data and a known child folder so the Data segment's dropdown has content.
        childName = 'BcChild_' + suffix;
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            let lr = await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            if (!(lr.ok() || lr.status() === 204)) throw new Error('login as ' + testInfo.testUserName + ' failed: HTTP ' + lr.status());
            dataDir = await ensurePath(ctx, 'auth.group', 'data', '~/Data');
            childDir = await ensurePath(ctx, 'auth.group', 'data', '~/Data/' + childName);
            if (!dataDir || !dataDir.objectId || !childDir || !childDir.objectId) {
                throw new Error('could not create ~/Data/' + childName + ' for ' + testInfo.testUserName);
            }
            console.log('[breadcrumb] ~/Data = ' + dataDir.path + ' (' + dataDir.objectId + '), child = ' + childDir.path);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test.afterAll(async ({ request }) => {
        await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName });
    });

    test.beforeEach(async ({ page }) => {
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
    });

    test('breadcrumb renders with full path on group-type list', async ({ page }) => {
        await openList(page, 'data.data', dataDir.objectId);

        let segs = await segmentTexts(page);
        console.log('[breadcrumb] Route:', await page.evaluate(() => window.location.hash), 'segments:', JSON.stringify(segs));
        // Full path of the listed group: /home/<user>/Data (dataDir.path is what the server reports).
        let expected = dataDir.path.split('/').slice(1);
        expect(segs, 'breadcrumb must show every segment of ' + dataDir.path).toEqual(expected);
        expect(segs[segs.length - 1]).toBe('Data');

        // Non-admin: the /home segment is disabled, the rest are navigable multi-buttons with a dropdown.
        await expect(page.locator(BAR + ' button#' + crumbId('/home') + 'Nav')).toBeDisabled();
        await expect(page.locator(BAR + ' button#' + crumbId(dataDir.path) + 'Nav')).toBeEnabled();
        await expect(page.locator(BAR + ' button#' + crumbId(dataDir.path) + 'Button span.material-symbols-outlined')).toHaveText('expand_more');
        await screenshot(page, 'breadcrumb-group-list');
    });

    test('breadcrumb has multiple path segments', async ({ page }) => {
        await openList(page, 'data.data', childDir.objectId);

        let segs = await segmentTexts(page);
        console.log('[breadcrumb] Segments:', JSON.stringify(segs));
        await screenshot(page, 'breadcrumb-segments');

        // home > <user> > Data > <child>
        expect(segs.length).toBeGreaterThanOrEqual(4);
        expect(segs).toEqual(childDir.path.split('/').slice(1));
        expect(segs[segs.length - 1]).toBe(childName);

        // Clicking the parent "Data" segment navigates back up to ~/Data (breadcrumb.js:123-129 ->
        // page.navigateToPath -> page.listByType) and the crumbs must follow the new route.
        await page.locator(BAR + ' button#' + crumbId(dataDir.path) + 'Nav').click();
        await page.waitForURL(new RegExp('/list/data\\.data/' + dataDir.objectId), { timeout: 15000 });
        let lastSeg = null;
        await expect.poll(async () => { lastSeg = (await segmentTexts(page)).pop(); return lastSeg; }, {
            message: 'breadcrumb must follow segment-click navigation up to ' + dataDir.path,
            timeout: 15000
        }).toBe('Data').catch(async (e) => {
            // Diagnostic for the failure: has the group context for the new objectId been fetched?
            let ctxState = await page.evaluate((oid) => {
                let pg = window.am7page;
                let c = pg && pg.context ? pg.context().contextObjects[oid] : undefined;
                return c === 'pending' ? 'pending' : (c && (c.path || c.groupPath)) ? ('fetched:' + (c.path || c.groupPath)) : String(c);
            }, dataDir.objectId);
            console.log('[breadcrumb] after segment click: hash=' + await page.evaluate(() => window.location.hash)
                + ' lastSegment=' + lastSeg + ' contextObjects[' + dataDir.objectId + ']=' + ctxState);
            throw e;
        });
    });

    test('breadcrumb dropdown shows child folders', async ({ page }) => {
        let errors = [];
        page.on('pageerror', e => errors.push(e.message));
        await openList(page, 'data.data', dataDir.objectId);

        // expand_more button of the "Data" segment (breadcrumb.js:137)
        let dataId = crumbId(dataDir.path);
        let expandBtn = page.locator(BAR + ' button#' + dataId + 'Button:has(span:text-is("expand_more"))');
        await expect(expandBtn, 'Data segment must have an expand_more button').toBeVisible({ timeout: 5000 });
        console.log('[breadcrumb] Expand buttons found:', await page.locator(BAR + ' button[id$="Button"]:has(span:text-is("expand_more"))').count());

        // navigation.js:85-89 — click lists the segment's child groups into the menu div, then opens it.
        await expandBtn.click();
        let menu = page.locator(BAR + ' div#' + dataId + '.context-menu-48');
        await expect(menu, 'context menu must open').toHaveClass(/transition-full/, { timeout: 15000 });
        await expect(menu).toBeVisible();

        let items = await menu.locator('button.context-menu-item').allTextContents();
        console.log('[breadcrumb] Menu items:', JSON.stringify(items));
        let childItem = menu.locator('button.context-menu-item', { hasText: childName });
        await expect(childItem, 'dropdown must list the child folder ' + childName).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'breadcrumb-dropdown');

        // Selecting the child navigates into it (contextMenuItemHandler -> page.listByType).
        await childItem.click();
        await page.waitForURL(new RegExp('/list/[^/]+/' + childDir.objectId), { timeout: 15000 });
        await expect.poll(async () => (await segmentTexts(page)).pop(), { timeout: 15000 }).toBe(childName);
        // The Data segment is still part of the new path; its menu must be closed again (nav.menu toggled it).
        await expect(menu).not.toHaveClass(/transition-full/);
        expect(errors, 'no page errors:\n' + errors.join('\n')).toHaveLength(0);
    });
});
