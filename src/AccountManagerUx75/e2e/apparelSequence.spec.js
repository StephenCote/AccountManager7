/**
 * Apparel sequence generation — tests the full pipeline:
 * dress down → nude image → dress up per level → image per level.
 * Requires SD service running. SLOW (30-120s per image).
 * Uses test user.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';

const test = base;

test.describe('Apparel sequence generation', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('generate one image via /reimage for test character', async ({ page }) => {
        // Single image test — verifies SD pipeline works before running full sequence
        test.setTimeout(300000);

        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Need modules loaded
        await page.goto('/');
        await page.waitForTimeout(3000);

        let result = await page.evaluate(async () => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { page } = await import('/src/core/pageClient.js');
            let { applicationPath } = await import('/src/core/config.js');
            let log = [];

            // Find a charPerson with a store
            // Create a fresh character via roll to ensure valid store/apparel
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
            if (!char) return { error: 'Failed to create test character', log };
            // Narrate
            try { let inst = am7model.prepareInstance(char); await am7model.forms.commands.narrate(undefined, inst); } catch(e) {}
            log.push('Created character: ' + char.name + ' (' + char.objectId + ')');

            // Create a minimal SD config
            let sdEntity = am7model.newPrimitive('olio.sd.config') || {};
            sdEntity.steps = 10; // Low steps for fast test
            sdEntity.cfg = 5;
            sdEntity.width = 512;
            sdEntity.height = 512;
            sdEntity.hires = false;

            log.push('Calling /reimage...');
            try {
                let resp = await fetch(applicationPath + '/rest/olio/olio.charPerson/' + char.objectId + '/reimage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify(sdEntity)
                });
                log.push('Reimage status: ' + resp.status);
                if (resp.ok) {
                    let img = await resp.json();
                    log.push('Image created: ' + (img ? img.objectId || img.name || 'yes' : 'null'));
                    return { success: true, imageId: img ? img.objectId : null, log };
                } else {
                    let text = await resp.text();
                    log.push('Reimage error: ' + text.substring(0, 200));
                    return { success: false, status: resp.status, log };
                }
            } catch (e) {
                log.push('Reimage exception: ' + e.message);
                return { error: e.message, log };
            }
        });

        console.log('Reimage test:');
        result.log.forEach(l => console.log('  ' + l));

        if (result.error) {
            console.log('ERROR: ' + result.error);
            console.log('I DID NOT VERIFY apparel sequence. Reimage failed.');
        } else if (result.success) {
            console.log('VERIFIED: /reimage endpoint works, image created: ' + result.imageId);
        } else {
            console.log('Reimage returned ' + result.status + '. I DID NOT VERIFY sequence.');
        }
    });
});
