/**
 * SD pipeline smoke: a client-built olio.sd.config (am7model.newPrimitive, no user model pick) must name a
 * checkpoint the stack's SD server actually has, and /reimage must then produce a real portrait.
 *
 * History: until 2026-10-10 this spec only logged "VERIFIED" / "I DID NOT VERIFY" and never asserted, so it
 * went green in the Phase 0 full gate while Swarm refused the request ("Invalid model value for param Model -
 * 'sdXL_v10VAEFix.safetensors'") and the endpoint answered 200 {} — a fake pass over a stale client default
 * (core/modelDef.js; see src/test/sdConfigModelParity.test.js). Now it checks the model against
 * GET /rest/olio/sdModels BEFORE generating, and fails on anything but a PNG portrait.
 *
 * Live SD on the stack's configured Swarm server (one 512x512 / 10-step image). Uses the shared test user.
 */
import { test, expect } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

test.describe('Apparel sequence generation', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('generate one image via /reimage for test character', async ({ page }) => {
        test.setTimeout(300000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // 1. Installed checkpoints, straight from the SD server the stack is configured against.
        const modelsResp = await page.request.get(REST + '/olio/sdModels');
        expect(modelsResp.ok(), 'GET /olio/sdModels HTTP ' + modelsResp.status()).toBe(true);
        const installed = await modelsResp.json();
        expect(Array.isArray(installed) && installed.length > 0, 'SD server lists at least one checkpoint').toBe(true);
        console.log('[sd-models] ' + installed.length + ' installed: ' + installed.join(', '));

        // 2. Fresh character + the config exactly as the client builds it without a user model pick.
        const built = await page.evaluate(async () => {
            let { am7model } = await import('/src/core/model.js');
            let { page } = await import('/src/core/pageClient.js');
            let ts = Date.now().toString(36);
            let roll = await am7model.forms.commands.rollCharacter(undefined, undefined, 'female');
            roll.firstName = 'Seqtest'; roll.lastName = 'Char' + ts; roll.name = roll.firstName + ' ' + roll.lastName;
            let cdir = await page.makePath('auth.group', 'data', '~/Characters');
            let charN = am7model.prepareEntity(roll, 'olio.charPerson', true);
            let wears = charN.store.apparel[0].wearables;
            for (let i = 0; i < wears.length; i++) {
                wears[i] = am7model.prepareEntity(wears[i], 'olio.wearable', false);
                if (wears[i].qualities && wears[i].qualities[0]) wears[i].qualities[0] = am7model.prepareEntity(wears[i].qualities[0], 'olio.quality', false);
            }
            await page.createObject(charN);
            let char = await page.searchFirst('olio.charPerson', cdir.id, roll.name);
            if (!char) return { error: 'Failed to create test character ' + roll.name };
            try { let inst = am7model.prepareInstance(char); await am7model.forms.commands.narrate(undefined, inst); } catch (e) { /* narration is optional for the render */ }

            let sdEntity = am7model.newPrimitive('olio.sd.config');
            sdEntity.steps = 10;
            sdEntity.cfg = 5;
            sdEntity.width = 512;
            sdEntity.height = 512;
            sdEntity.hires = false;
            return { charObjectId: char.objectId, charName: char.name, sdEntity };
        });
        expect(built.error, built.error).toBeUndefined();
        console.log('[character] ' + built.charName + ' (' + built.charObjectId + ')');

        // 3. The model the client is about to send must be one the server has — the check that was missing.
        const model = built.sdEntity.model;
        expect(typeof model === 'string' && model.length > 0, 'client config carries a model').toBe(true);
        expect(installed, 'client default model "' + model + '" must be installed on the SD server').toContain(model);
        if (built.sdEntity.refinerModel) {
            expect(installed, 'client refinerModel "' + built.sdEntity.refinerModel + '" must be installed').toContain(built.sdEntity.refinerModel);
        }
        console.log('[sd-config] model=' + model + ' refinerModel=' + built.sdEntity.refinerModel + ' steps=' + built.sdEntity.steps + ' cfg=' + built.sdEntity.cfg + ' ' + built.sdEntity.width + 'x' + built.sdEntity.height);

        // 4. Generate. A refused/empty generation comes back 200 {} — assert on the portrait, not the status.
        const started = Date.now();
        const resp = await page.request.post(REST + '/olio/olio.charPerson/' + built.charObjectId + '/reimage', {
            data: built.sdEntity,
            timeout: 280000
        });
        const durationMs = Date.now() - started;
        expect(resp.ok(), '/reimage HTTP ' + resp.status()).toBe(true);
        const img = await resp.json();
        console.log('[reimage] durationMs=' + durationMs + ' -> ' + JSON.stringify({ objectId: img && img.objectId, name: img && img.name, groupPath: img && img.groupPath }));
        expect(img && img.objectId, '/reimage must return the generated portrait record (got ' + JSON.stringify(img).slice(0, 200) + ')').toBeTruthy();
        expect(img.groupPath && img.name, 'portrait record carries groupPath + name').toBeTruthy();

        // 5. Pull the bytes back and prove it is a PNG with real dimensions.
        const org = (img.organizationPath || '/Development').replace(/^\//, '').replace(/\//g, '.');
        const mediaUrl = BASE_URL + '/AccountManagerService7/media/' + org + '/data.data' + img.groupPath + '/' + encodeURIComponent(img.name);
        const media = await page.request.get(mediaUrl);
        expect(media.ok(), 'GET media ' + mediaUrl + ' HTTP ' + media.status()).toBe(true);
        let raw = await media.body();
        if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
        expect(raw.subarray(0, 8).toString('hex'), 'portrait bytes are a PNG').toBe('89504e470d0a1a0a');
        const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
        expect(dims[0] > 0 && dims[1] > 0, 'PNG has dimensions').toBe(true);
        const pngPath = test.info().outputPath('apparelSequence-portrait.png');
        writeFileSync(pngPath, raw);
        await test.info().attach('apparelSequence-portrait.png', { path: pngPath, contentType: 'image/png' });
        console.log('[portrait] ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) -> ' + pngPath);
    });
});
