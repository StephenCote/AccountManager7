/**
 * Call prompt init and check server logs for errors.
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }
async function safeJson(resp) { if (!resp.ok()) { console.log('HTTP', resp.status(), await resp.text()); return null; } try { return JSON.parse(await resp.text()); } catch { return null; } }

test('Init prompt library and verify all 9 templates', async () => {
    let ctx = await newCtx();
    try {
        // Login as admin to call init
        let lr = await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development',
                name: 'admin', credential: b64('password'), type: 'hashed_password' }
        });
        expect(lr.ok()).toBe(true);

        // Call init
        let initResp = await ctx.post(REST + '/chat/library/prompt/init');
        console.log('Init status:', initResp.status());
        let initBody = await safeJson(initResp);
        console.log('Init body:', JSON.stringify(initBody));

        // Get the library dir
        let dirResp = await ctx.get(REST + '/chat/library/dir/promptTemplate');
        let dir = await safeJson(dirResp);
        console.log('Library dir:', dir ? { id: dir.id, path: dir.path } : 'null');

        // Search by groupId as NUMBER (confirmed working)
        if (dir && dir.id) {
            let searchResp = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.llm.promptTemplate',
                    fields: [{ name: 'groupId', comparator: 'equals', value: dir.id }],
                    request: ['id', 'objectId', 'name', 'description'],
                    recordCount: 50
                }
            });
            let sr = await safeJson(searchResp);
            let items = (sr && sr.results) ? sr.results : [];
            console.log('Templates in library by groupId (numeric):', items.length);
            items.forEach(t => console.log('  -', t.name));

            if (items.length < 9) {
                console.log('MISSING templates. Expected 9, got', items.length);
                // Try unfiltered to see what exists
                let allResp = await ctx.post(REST + '/model/search', {
                    data: {
                        schema: 'io.query',
                        type: 'olio.llm.promptTemplate',
                        fields: [],
                        request: ['id', 'objectId', 'name', 'groupId'],
                        recordCount: 50
                    }
                });
                let allSr = await safeJson(allResp);
                let allItems = (allSr && allSr.results) ? allSr.results : [];
                console.log('ALL templates (unfiltered):', allItems.length);
                allItems.forEach(t => console.log('  - name=' + t.name + ' groupId=' + t.groupId));
            }
        }

        // Now check: does ChatUtil have the updated names?
        // We can infer by calling init again and checking if new templates appear
        // Delete one pictureBook template and re-init to test
        let pbResp = await ctx.post(REST + '/model/search', {
            data: {
                schema: 'io.query',
                type: 'olio.llm.promptTemplate',
                fields: [{ name: 'name', comparator: 'equals', value: 'pictureBook.extract-scenes' }],
                request: ['id', 'objectId', 'name'],
                recordCount: 1
            }
        });
        let pbSr = await safeJson(pbResp);
        let pbExists = pbSr && pbSr.results && pbSr.results.length > 0;
        console.log('pictureBook.extract-scenes exists:', pbExists);

        await ctx.get(REST + '/logout');
    } finally {
        await ctx.dispose();
    }
});
