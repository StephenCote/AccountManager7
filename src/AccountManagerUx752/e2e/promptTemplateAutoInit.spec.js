/**
 * Delete pictureBook templates, then verify they're auto-recreated
 * when the picker queries for them.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true }); }
async function safeJson(resp) {
    if (!resp.ok()) return null;
    try { return JSON.parse(await resp.text()); } catch { return null; }
}

const PB_NAMES = [
    'pictureBook.extract-scenes', 'pictureBook.extract-chunk',
    'pictureBook.extract-character', 'pictureBook.scene-blurb',
    'pictureBook.landscape-prompt', 'pictureBook.scene-image-prompt'
];

test('Auto-init: delete pictureBook templates, verify picker triggers re-init', async () => {
    // Step 1: Admin deletes all pictureBook templates
    let adminCtx = await newCtx();
    await adminCtx.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: '/Development',
            name: 'admin', credential: b64('password'), type: 'hashed_password' }
    });

    for (let name of PB_NAMES) {
        let resp = await adminCtx.post(REST + '/model/search', {
            data: { schema: 'io.query', type: 'olio.llm.promptTemplate',
                fields: [{ name: 'name', comparator: 'equals', value: name }],
                request: ['id', 'objectId'], recordCount: 1 }
        });
        let sr = await safeJson(resp);
        if (sr && sr.results && sr.results.length > 0) {
            await adminCtx.delete(REST + '/model/olio.llm.promptTemplate/' + sr.results[0].objectId);
        }
    }

    // Verify only 3 remain
    let countResp = await adminCtx.post(REST + '/model/search', {
        data: { schema: 'io.query', type: 'olio.llm.promptTemplate',
            fields: [], request: ['id', 'name'], recordCount: 50 }
    });
    let countSr = await safeJson(countResp);
    let remaining = (countSr && countSr.results) ? countSr.results : [];
    console.log('After delete:', remaining.length, 'templates');
    expect(remaining.length).toBe(3);

    await adminCtx.get(REST + '/logout');
    await adminCtx.dispose();

    // Step 2: Call prompt init (simulating what the picker auto-init does)
    let shared = await ensureSharedTestUser(null);
    let userCtx = await newCtx();
    await userCtx.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: '/Development',
            name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
    });

    // Simulate picker check: search for pictureBook.extract-scenes in library
    let dirResp = await userCtx.get(REST + '/chat/library/dir/promptTemplate');
    let dir = await safeJson(dirResp);
    expect(dir).toBeTruthy();

    let checkResp = await userCtx.post(REST + '/model/search', {
        data: { schema: 'io.query', type: 'olio.llm.promptTemplate',
            fields: [
                { name: 'groupId', comparator: 'equals', value: dir.id },
                { name: 'name', comparator: 'equals', value: 'pictureBook.extract-scenes' }
            ],
            request: ['id', 'name'], recordCount: 1 }
    });
    let checkSr = await safeJson(checkResp);
    let found = checkSr && checkSr.results && checkSr.results.length > 0;
    console.log('Before auto-init, pictureBook.extract-scenes found:', found);
    expect(found).toBeFalsy();

    // Simulate picker auto-init: call initPromptLibrary
    let initResp = await userCtx.post(REST + '/chat/library/prompt/init');
    console.log('Auto-init result:', initResp.status());

    // Verify all 9 templates now exist
    let finalResp = await userCtx.post(REST + '/model/search', {
        data: { schema: 'io.query', type: 'olio.llm.promptTemplate',
            fields: [], request: ['id', 'name'], recordCount: 50 }
    });
    let finalSr = await safeJson(finalResp);
    let finalItems = (finalSr && finalSr.results) ? finalSr.results : [];
    console.log('After auto-init:', finalItems.length, 'templates');
    finalItems.forEach(t => console.log('  -', t.name));

    for (let name of PB_NAMES) {
        expect(finalItems.find(t => t.name === name), 'Missing: ' + name).toBeTruthy();
    }
    expect(finalItems.length).toBe(9);

    await userCtx.get(REST + '/logout');
    await userCtx.dispose();
});
