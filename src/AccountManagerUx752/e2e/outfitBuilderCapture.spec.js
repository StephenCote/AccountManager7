/**
 * E2E test: open the outfit builder on a real character and capture every
 * /model/search request body so we can identify the malformed query that
 * triggers the recurring `bigint = character varying` PSQL error.
 *
 * Uses the shared test user. No assertions about the bug yet — this run
 * is diagnostic.
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(s) { return Buffer.from(s).toString('base64'); }

async function asUser(user, password, fn) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let resp = await ctx.post(REST + '/login', {
            data: {
                schema: 'auth.credential',
                organizationPath: '/Development',
                name: user,
                credential: b64(password),
                type: 'hashed_password'
            }
        });
        if (!resp.ok()) throw new Error('login failed');
        return await fn(ctx);
    } finally {
        await ctx.get(REST + '/logout').catch(() => {});
        await ctx.dispose();
    }
}

async function findAnyCharacter(user, password) {
    return await asUser(user, password, async (ctx) => {
        let resp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.charPerson',
                fields: [],
                request: ['objectId', 'name'],
                recordCount: 1
            }
        });
        if (!resp.ok()) return null;
        let body = await resp.json();
        return body && body.results && body.results[0];
    });
}

test.describe('Outfit Builder — capture failing queries', () => {
    let testInfo = {};
    let target = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        target = await findAnyCharacter(testInfo.testUserName, testInfo.testPassword);
        test.skip(!target, 'no character available');
    });

    test('open outfit builder, log all model/search request bodies', async ({ page }) => {
        test.setTimeout(120000);

        /// Capture every POST/PATCH/PUT request body so we can find the
        /// one that sends a String for a Long field.
        let posts = [];
        page.on('request', async (req) => {
            let url = req.url();
            let method = req.method();
            if (url.includes('/AccountManagerService7/rest/') && (method === 'POST' || method === 'PATCH' || method === 'PUT')) {
                let body = req.postData() || '';
                posts.push({ method, url, body });
            }
        });
        let bad = [];
        let outfitResponses = [];
        page.on('response', async (resp) => {
            if (resp.status() >= 400) {
                bad.push(resp.status() + ' ' + resp.url());
            }
            if (resp.url().includes('/game/outfit/generate')) {
                try {
                    let txt = await resp.text();
                    outfitResponses.push({ status: resp.status(), len: txt.length, preview: txt.substring(0, 300) });
                } catch(e) { /* ignore */ }
            }
        });
        let consoleErrors = [];
        page.on('pageerror', (err) => { consoleErrors.push(err.message); });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Open the character editor
        await page.goto('/#!/view/olio.charPerson/' + target.objectId);
        await page.waitForTimeout(3000);

        // Diagnose: list all buttons on the page to find the right selector
        let buttonInfo = await page.evaluate(() => {
            let btns = Array.from(document.querySelectorAll('button'));
            return btns.map(b => ({
                title: b.title || '',
                text: (b.textContent || '').trim().substring(0, 30),
                icons: Array.from(b.querySelectorAll('.material-symbols-outlined, .material-icons-outlined'))
                    .map(s => s.textContent.trim()).filter(Boolean).join(',')
            })).filter(x => x.icons || x.title || x.text);
        });
        console.log('\n=== Buttons on page (' + buttonInfo.length + ') ===');
        buttonInfo.forEach((b, i) => {
            console.log('  [' + i + '] title="' + b.title + '" text="' + b.text + '" icons="' + b.icons + '"');
        });

        // Try multiple selectors for the Outfit Builder
        let outfitBtn = page.locator('button[title="Outfit Builder"], button:has(span.material-symbols-outlined:text-is("checkroom"))').first();
        let hasBtn = await outfitBtn.isVisible({ timeout: 10000 }).catch(() => false);
        console.log('\nOutfit button visible: ' + hasBtn);

        if (hasBtn) {
            await outfitBtn.click();
            await page.waitForTimeout(2000);
            await screenshot(page, 'outfit-builder-open');

            // Try clicking Generate Outfit
            let genBtn = page.locator('button:has-text("Generate Outfit")').first();
            let hasGen = await genBtn.isVisible({ timeout: 3000 }).catch(() => false);
            console.log('Generate Outfit button visible: ' + hasGen);
            if (hasGen) {
                await genBtn.click();
                await page.waitForTimeout(8000);  // wait for backend
            }
        }

        await screenshot(page, 'outfit-builder-state');

        // ── Diagnostics ──
        console.log('\n=== All POST/PATCH/PUT request bodies (' + posts.length + ') ===');
        posts.forEach((p, i) => {
            let shortUrl = p.url.replace('https://localhost:8899/AccountManagerService7/rest', '');
            console.log('  [' + i + '] ' + p.method + ' ' + shortUrl);
            // For /model/search bodies, parse and show field types
            if (shortUrl.includes('/model/search')) {
                try {
                    let parsed = JSON.parse(p.body);
                    let fields = (parsed.fields || []).map(f =>
                        f.name + '=' + JSON.stringify(f.value) + '(' + typeof f.value + ')'
                    ).join(', ');
                    console.log('      type=' + parsed.type + ' fields=[' + fields + ']');
                } catch(e) {
                    console.log('      (unparseable)');
                }
            } else {
                // For other POSTs, just dump the first ~500 chars
                console.log('      body=' + p.body.substring(0, 500));
            }
        });
        console.log('=== Failed responses (' + bad.length + ') ===');
        bad.forEach(b => console.log('  ' + b));
        console.log('=== Outfit generate responses (' + outfitResponses.length + ') ===');
        outfitResponses.forEach(r => console.log('  status=' + r.status + ' len=' + r.len + ' preview=' + r.preview));
        console.log('=== Page errors (' + consoleErrors.length + ') ===');
        consoleErrors.forEach(e => console.log('  ' + e.substring(0, 200)));

        // This test always passes — it's diagnostic. We just want the log.
        expect(posts.length).toBeGreaterThanOrEqual(0);
    });
});
