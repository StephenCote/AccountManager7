/**
 * Delete all pictureBook prompt templates, call init, verify they come back.
 * Tests in /Development org.
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }
async function safeJson(resp) {
    if (!resp.ok()) { console.log('HTTP', resp.status(), await resp.text().catch(() => '')); return null; }
    try { return JSON.parse(await resp.text()); } catch { return null; }
}

const PB_NAMES = [
    'pictureBook.extract-scenes',
    'pictureBook.extract-chunk',
    'pictureBook.extract-character',
    'pictureBook.scene-blurb',
    'pictureBook.landscape-prompt',
    'pictureBook.scene-image-prompt'
];

test('Delete pictureBook templates then re-init', async () => {
    let ctx = await newCtx();
    try {
        await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: 'admin', credential: b64('password'), type: 'hashed_password' }
        });

        // Step 1: Find and delete all pictureBook templates
        console.log('--- STEP 1: Delete all pictureBook templates ---');
        for (let name of PB_NAMES) {
            let resp = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.llm.promptTemplate',
                    fields: [{ name: 'name', comparator: 'equals', value: name }],
                    request: ['id', 'objectId', 'name'],
                    recordCount: 1
                }
            });
            let sr = await safeJson(resp);
            if (sr && sr.results && sr.results.length > 0) {
                let oid = sr.results[0].objectId;
                let delResp = await ctx.delete(REST + '/model/olio.llm.promptTemplate/' + oid);
                console.log('Deleted', name, ':', delResp.ok() ? 'OK' : 'FAIL ' + delResp.status());
            } else {
                console.log(name, ': not found (already deleted or never created)');
            }
        }

        // Step 2: Verify they're gone
        console.log('\n--- STEP 2: Verify deleted ---');
        let allResp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.llm.promptTemplate',
                fields: [],
                request: ['id', 'objectId', 'name'],
                recordCount: 50
            }
        });
        let allSr = await safeJson(allResp);
        let remaining = (allSr && allSr.results) ? allSr.results : [];
        console.log('Remaining templates:', remaining.length);
        remaining.forEach(t => console.log('  -', t.name));
        expect(remaining.length).toBe(3); // only original 3

        // Step 3: Call init
        console.log('\n--- STEP 3: Call prompt init ---');
        let initResp = await ctx.post(REST + '/chat/library/prompt/init');
        console.log('Init status:', initResp.status());
        let initBody = await safeJson(initResp);
        console.log('Init body:', JSON.stringify(initBody));

        // Step 4: Verify they're back
        console.log('\n--- STEP 4: Verify reloaded ---');
        let afterResp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.llm.promptTemplate',
                fields: [],
                request: ['id', 'objectId', 'name', 'description'],
                recordCount: 50
            }
        });
        let afterSr = await safeJson(afterResp);
        let afterItems = (afterSr && afterSr.results) ? afterSr.results : [];
        console.log('Templates after re-init:', afterItems.length);
        afterItems.forEach(t => console.log('  -', t.name, '|', (t.description || '').substring(0, 60)));

        // Assert all 9 exist
        for (let name of PB_NAMES) {
            let found = afterItems.find(t => t.name === name);
            expect(found, 'Expected "' + name + '" to be recreated by init').toBeTruthy();
        }
        expect(afterItems.length).toBe(9);

        await ctx.get(REST + '/logout');
    } finally {
        await ctx.dispose();
    }
});
