/**
 * SD config persistence test — verifies save/load cycle via API
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

test.describe('SD config save/load cycle', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('sdConfig saveConfig then loadConfig round-trips correctly', async ({ page }) => {
        test.setTimeout(60000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        let result = await page.evaluate(async () => {
            let { am7sd } = await import('/src/components/sdConfig.js');
            let { am7model } = await import('/src/core/model.js');

            let ts = Date.now().toString(36);
            let configName = 'E2E-SDTest-' + ts + '.json';

            // Create an SD config instance
            let entity = am7model.newPrimitive('olio.sd.config');
            let cinst = am7model.prepareInstance(entity);

            // Set values via API (goes through decorators)
            cinst.api.cfg(7);
            cinst.api.steps(25);
            cinst.api.denoisingStrength(60); // decorator: 60/100 = 0.6 in entity
            cinst.entity.bodyStyle = 'portrait';
            cinst.entity.style = 'photograph';

            let entityBeforeSave = {
                cfg: cinst.entity.cfg,
                steps: cinst.entity.steps,
                denoisingStrength: cinst.entity.denoisingStrength,
                bodyStyle: cinst.entity.bodyStyle,
                style: cinst.entity.style
            };

            // Save
            let saveEntity = Object.assign({}, cinst.entity);
            saveEntity.shared = false;
            let saved = await am7sd.saveConfig(configName, saveEntity);

            // Clear cache before loading
            let { am7client } = await import('/src/core/am7client.js');
            am7client.clearCache('data.data');

            // Load into a fresh instance
            let entity2 = am7model.newPrimitive('olio.sd.config');
            let cinst2 = am7model.prepareInstance(entity2);

            let loaded = await am7sd.loadConfig(configName);
            if (loaded) am7sd.applyConfig(cinst2, loaded);

            let entityAfterLoad = {
                cfg: cinst2.entity.cfg,
                steps: cinst2.entity.steps,
                denoisingStrength: cinst2.entity.denoisingStrength,
                bodyStyle: cinst2.entity.bodyStyle,
                style: cinst2.entity.style
            };

            // Read via API (through decorators) — this is what the form displays
            let apiAfterLoad = {
                cfg: cinst2.api.cfg(),
                steps: cinst2.api.steps(),
                denoisingStrength: cinst2.api.denoisingStrength ? cinst2.api.denoisingStrength() : 'N/A',
                bodyStyle: cinst2.entity.bodyStyle,
                style: cinst2.entity.style
            };

            return {
                saved,
                loadedRaw: loaded ? {
                    cfg: loaded.cfg,
                    steps: loaded.steps,
                    denoisingStrength: loaded.denoisingStrength,
                    bodyStyle: loaded.bodyStyle,
                    style: loaded.style
                } : null,
                entityBeforeSave,
                entityAfterLoad,
                apiAfterLoad,
                configName
            };
        });

        console.log('SD config persistence:', JSON.stringify(result, null, 2));

        expect(result.saved).toBe(true);
        expect(result.loadedRaw).not.toBeNull();

        // Entity values should match
        expect(result.entityAfterLoad.cfg).toBe(result.entityBeforeSave.cfg);
        expect(result.entityAfterLoad.steps).toBe(result.entityBeforeSave.steps);
        expect(result.entityAfterLoad.denoisingStrength).toBe(result.entityBeforeSave.denoisingStrength);
        expect(result.entityAfterLoad.bodyStyle).toBe(result.entityBeforeSave.bodyStyle);
        expect(result.entityAfterLoad.style).toBe(result.entityBeforeSave.style);

        // API values should be display-ready
        expect(result.apiAfterLoad.cfg).toBe(7);
        expect(result.apiAfterLoad.steps).toBe(25);
        // denoisingStrength stored as-is (no range decorator without form), numberDecorator returns parseFloat
        expect(parseFloat(result.apiAfterLoad.denoisingStrength)).toBe(60);
    });
});
