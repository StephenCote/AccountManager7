/**
 * Prompt Template Library — diagnose and verify pictureBook templates in system library.
 * Uses shared test user (NEVER admin for assertions).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

function b64(str) { return Buffer.from(str).toString('base64'); }

async function newCtx() {
    return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

async function safeJson(resp) {
    if (!resp.ok()) return null;
    try {
        let text = await resp.text();
        if (!text || text.startsWith('<!') || text.startsWith('<html')) return null;
        return JSON.parse(text);
    } catch { return null; }
}

const EXPECTED_TEMPLATES = [
    'pictureBook.extract-scenes',
    'pictureBook.extract-chunk',
    'pictureBook.extract-character',
    'pictureBook.scene-blurb',
    'pictureBook.landscape-prompt',
    'pictureBook.scene-image-prompt'
];

test.describe('Prompt Template Library', () => {

    test('A. Check library status and init if needed', async () => {
        // Admin calls init (only admin should bootstrap library)
        let adminCtx = await newCtx();
        try {
            await adminCtx.post(REST + '/login', {
                data: { schema: 'auth.credential', organizationPath: '/Development',
                    name: 'admin', credential: b64('password'), type: 'hashed_password' }
            });

            // Check current status
            let statusResp = await adminCtx.get(REST + '/chat/library/status');
            let status = await safeJson(statusResp);
            console.log('Library status:', JSON.stringify(status));

            // Call prompt init
            let initResp = await adminCtx.post(REST + '/chat/library/prompt/init');
            let initResult = await safeJson(initResp);
            console.log('Prompt init result:', JSON.stringify(initResult));
            expect(initResp.ok()).toBe(true);

            await adminCtx.get(REST + '/logout');
        } finally {
            await adminCtx.dispose();
        }
    });

    test('B. Search for pictureBook templates as test user', async () => {
        let shared = await ensureSharedTestUser(null);

        let ctx = await newCtx();
        try {
            await ctx.post(REST + '/login', {
                data: { schema: 'auth.credential', organizationPath: '/Development',
                    name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
            });

            // Search for all prompt templates (no group filter — library is shared)
            let resp = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.llm.promptTemplate',
                    fields: [],
                    request: ['id', 'objectId', 'name', 'description', 'groupPath'],
                    recordCount: 50
                }
            });
            let result = await safeJson(resp);
            let templates = (result && result.results) ? result.results : [];
            console.log('All prompt templates found:', templates.length);
            templates.forEach(t => console.log('  -', t.name, '|', t.groupPath || '(no path)'));

            // Check for the pictureBook templates specifically
            let pbTemplates = templates.filter(t => t.name && t.name.startsWith('pictureBook.'));
            console.log('PictureBook templates found:', pbTemplates.length);

            for (let expected of EXPECTED_TEMPLATES) {
                let found = templates.find(t => t.name === expected);
                if (!found) console.log('MISSING:', expected);
                else console.log('FOUND:', expected);
            }

            // Also search by name pattern for each
            for (let name of EXPECTED_TEMPLATES) {
                let nameResp = await ctx.post(REST + '/model/search', {
                    data: {
                        schema: 'io.query',
                        type: 'olio.llm.promptTemplate',
                        fields: [{ name: 'name', comparator: 'equals', value: name }],
                        request: ['id', 'objectId', 'name', 'description', 'groupPath'],
                        recordCount: 1
                    }
                });
                let nameResult = await safeJson(nameResp);
                let found = (nameResult && nameResult.results && nameResult.results.length > 0);
                console.log('Search by name "' + name + '":', found ? 'FOUND' : 'NOT FOUND');
            }

            await ctx.get(REST + '/logout');
        } finally {
            await ctx.dispose();
        }
    });

    test('C. Check library directory exists and has content', async () => {
        let shared = await ensureSharedTestUser(null);

        let ctx = await newCtx();
        try {
            await ctx.post(REST + '/login', {
                data: { schema: 'auth.credential', organizationPath: '/Development',
                    name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
            });

            // Try to get the library directory for prompt templates
            let dirResp = await ctx.get(REST + '/chat/library/dir/promptTemplate');
            let dir = await safeJson(dirResp);
            console.log('Library dir response status:', dirResp.status());
            console.log('Library dir:', dir ? JSON.stringify({ name: dir.name, path: dir.path, id: dir.id, objectId: dir.objectId }) : 'null');

            if (dir && dir.id) {
                // List contents of the directory
                let listResp = await ctx.post(REST + '/model/search', {
                    data: {
                        schema: 'io.query',
                        type: 'olio.llm.promptTemplate',
                        fields: [{ name: 'groupId', comparator: 'equals', value: String(dir.id) }],
                        request: ['id', 'objectId', 'name', 'description'],
                        recordCount: 50
                    }
                });
                let listResult = await safeJson(listResp);
                let items = (listResult && listResult.results) ? listResult.results : [];
                console.log('Templates in library dir (groupId=' + dir.id + '):', items.length);
                items.forEach(t => console.log('  -', t.name));
            }

            // Also check if the PromptTemplates group exists at all
            let findResp = await ctx.get(REST + '/path/find/auth.group/data/' +
                'B64-' + b64('/Library/PromptTemplates').replace(/=/g, '%3D'));
            let findDir = await safeJson(findResp);
            console.log('Direct path lookup /Library/PromptTemplates:', findDir ?
                JSON.stringify({ name: findDir.name, id: findDir.id }) : 'NOT FOUND');

            await ctx.get(REST + '/logout');
        } finally {
            await ctx.dispose();
        }
    });

    test('D. Verify all 6 pictureBook templates exist', async () => {
        let shared = await ensureSharedTestUser(null);

        let ctx = await newCtx();
        try {
            await ctx.post(REST + '/login', {
                data: { schema: 'auth.credential', organizationPath: '/Development',
                    name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
            });

            for (let name of EXPECTED_TEMPLATES) {
                let resp = await ctx.post(REST + '/model/search', {
                    data: {
                        schema: 'io.query',
                        type: 'olio.llm.promptTemplate',
                        fields: [{ name: 'name', comparator: 'equals', value: name }],
                        request: ['id', 'objectId', 'name', 'description'],
                        recordCount: 1
                    }
                });
                let result = await safeJson(resp);
                let found = result && result.results && result.results.length > 0;
                expect(found, 'Expected prompt template "' + name + '" to exist in library').toBe(true);
            }

            await ctx.get(REST + '/logout');
        } finally {
            await ctx.dispose();
        }
    });
});
