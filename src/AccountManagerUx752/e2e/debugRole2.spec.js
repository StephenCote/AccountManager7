import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }

test('debug role membership', async ({ request }) => {
    let testInfo = await ensureSharedTestUser(request);
    console.log('USER', JSON.stringify(testInfo.user));

    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    await ctx.post(REST + '/login', { data: { schema: 'auth.credential', organizationPath: '/Development', name: 'admin', credential: b64('password'), type: 'hashed_password' } });
    let resp = await ctx.post(REST + '/model/search', {
        data: { schema: 'io.query', type: 'auth.role', fields: [{name:'name', comparator:'equals', value:'RoleReaders'}], request: ['id','objectId','name','type'], recordCount:1 }
    });
    let role = await resp.json();
    let roleObjectId = role.results && role.results[0] && role.results[0].objectId;
    console.log('roleObjectId', roleObjectId);

    let memResp = await ctx.get(REST + '/authorization/auth.role/' + roleObjectId + '/system.user/0/100');
    console.log('MEMBERS', memResp.status(), await memResp.text());

    await ctx.dispose();
});
