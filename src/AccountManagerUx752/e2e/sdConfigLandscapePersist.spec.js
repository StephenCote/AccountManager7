/**
 * KI-65 + KI-66 (live) — chat SD config persists SERVER-SIDE as an olio.sd.config record, and the
 * "Skip landscape" toggle (skipLandscape + flux2IncludeLandscapeRef) survives save -> fresh load.
 *
 * Drives the real chat/SceneGenerator.js module in the browser through its test seam
 * (__setChatConfigForTest / ensureSdConfig / persistConfig / __getSdConfigForTest), toggling the
 * checkbox via the real SdConfigPanel view's own onchange handler, exactly as the chat Scene dialog
 * does (config-driven mode). The stored record is then read back over REST with cache:false and the
 * module is reset and re-loaded for the same chat key to prove the values came from the server, not
 * from the in-memory singleton or localStorage (the KI-65 defect).
 *
 * Needs the Vite dev server (:8899) — `import('/src/...')` is not available from the Docker dist.
 * Uses ensureSharedTestUser() (never admin).
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser, apiLogin, apiLogout, deleteObject } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const EDITED_STEPS = 13;

async function readSdConfig(ctx, name) {
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'olio.sd.config',
            cache: false,
            request: ['id', 'objectId', 'name', 'skipLandscape', 'flux2IncludeLandscapeRef', 'steps', 'compositeMode', 'model'],
            fields: [{ name: 'name', comparator: 'EQUALS', value: name }]
        }
    });
    if (!resp.ok()) return null;
    let j = await resp.json();
    return (j && j.results && j.results.length) ? j.results[0] : null;
}

test.describe('Chat SD config — server-side persistence + Skip-landscape round trip (KI-65 / KI-66)', () => {
    let testInfo = {};
    const chatKey = 'e2e-ki65-' + Date.now().toString(36);
    const recordName = 'sdcfg-chat-' + chatKey;
    let recordObjectId = null;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test.afterAll(async () => {
        if (!recordObjectId) return;
        let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await apiLogin(ctx, { user: testInfo.testUserName, password: testInfo.testPassword });
            await deleteObject(ctx, 'olio.sd.config', recordObjectId);
            await apiLogout(ctx);
        } finally {
            await ctx.dispose();
        }
    });

    test('toggle Skip landscape + edit Steps, persist, reset module, reload from server: values persisted', async ({ page }) => {
        test.setTimeout(120000);
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Precondition: no record for this chat key yet (fresh key per run).
        expect(await readSdConfig(page.request, recordName), 'no pre-existing record').toBeNull();

        let r = await page.evaluate(async ({ chatKey, EDITED_STEPS }) => {
            const SG = await import('/src/chat/SceneGenerator.js');
            const { SdConfigPanel } = await import('/src/components/SdConfigPanel.js');

            function findByTag(vnode, tag) {
                let out = [];
                (function walk(n) {
                    if (Array.isArray(n)) { n.forEach(walk); return; }
                    if (!n) return;
                    if (n.tag === tag) out.push(n);
                    if (n.children) walk(n.children);
                })(vnode);
                return out;
            }
            function textOf(vnode) {
                let parts = [];
                (function walk(n) {
                    if (Array.isArray(n)) { n.forEach(walk); return; }
                    if (n == null || n === false) return;
                    if (typeof n === 'string' || typeof n === 'number') { parts.push(String(n)); return; }
                    if (n.children != null) walk(n.children);
                })(vnode);
                return parts;
            }
            function skipLandscapeCheckbox(tree) {
                let labels = findByTag(tree, 'label').filter(l => textOf(l).includes('Skip landscape'));
                if (labels.length !== 1) return null;
                let cb = findByTag(labels[0], 'input').filter(i => i.attrs.type === 'checkbox');
                return cb.length === 1 ? cb[0] : null;
            }
            function stepsRange(tree) {
                // SdConfigPanel.rangeInput passes label:key — the sr-only label text identifies the slider.
                let wrappers = findByTag(tree, 'div').filter(d => {
                    let labels = findByTag(d.children, 'label').filter(l => l.attrs && (l.attrs.className || l.attrs.class) === 'sr-only');
                    let ranges = findByTag(d.children, 'input').filter(i => i.attrs.type === 'range');
                    return labels.length === 1 && textOf(labels[0]).join('') === 'steps' && ranges.length === 1;
                });
                if (!wrappers.length) return null;
                return findByTag(wrappers[wrappers.length - 1].children, 'input').find(i => i.attrs.type === 'range');
            }

            let out = { log: [] };
            try {
                // 1. Build the per-chat config from the server template (no saved record yet).
                SG.__setChatConfigForTest(chatKey);
                SG.__setSaveSharedForTest(false);
                let inst = await SG.ensureSdConfig();
                let cfg = SG.__getSdConfigForTest();
                if (!inst || !cfg) { out.error = 'ensureSdConfig returned nothing'; return out; }
                out.recordName = SG.perChatConfigName();
                out.initial = { skipLandscape: cfg.skipLandscape, flux2IncludeLandscapeRef: cfg.flux2IncludeLandscapeRef, steps: cfg.steps, compositeMode: cfg.compositeMode };

                // 2. Toggle Skip landscape through the real panel view (config-driven, as chat renders it).
                let tree = SdConfigPanel.view({ attrs: { config: cfg } });
                let cb = skipLandscapeCheckbox(tree);
                if (!cb) { out.error = 'Skip landscape checkbox not rendered'; return out; }
                out.checkedBefore = cb.attrs.checked;
                cb.attrs.onchange({ target: { checked: true } });
                let steps = stepsRange(tree);
                if (!steps) { out.error = 'steps slider not rendered'; return out; }
                steps.attrs.oninput({ target: { value: String(EDITED_STEPS) } });
                out.afterToggle = { skipLandscape: cfg.skipLandscape, flux2IncludeLandscapeRef: cfg.flux2IncludeLandscapeRef, steps: cfg.steps };
                out.checkedAfter = skipLandscapeCheckbox(SdConfigPanel.view({ attrs: { config: cfg } })).attrs.checked;

                // 3. Persist server-side (per-chat record only; shared record untouched).
                out.persisted = await SG.persistConfig();

                // 4. Drop the module singleton and rebuild for the SAME chat key: must come back from the server.
                SG.__setChatConfigForTest(chatKey);
                if (SG.__getSdConfigForTest() !== null) { out.error = 'reset did not clear the singleton'; return out; }
                await SG.ensureSdConfig();
                let cfg2 = SG.__getSdConfigForTest();
                out.reloaded = { skipLandscape: cfg2.skipLandscape, flux2IncludeLandscapeRef: cfg2.flux2IncludeLandscapeRef, steps: cfg2.steps, model: cfg2.model };
                out.reloadedChecked = skipLandscapeCheckbox(SdConfigPanel.view({ attrs: { config: cfg2 } })).attrs.checked;
                // KI-65's legacy per-browser store was localStorage "am7.sdConfig"; nothing may write it now.
                out.legacyLocalStorage = window.localStorage.getItem('am7.sdConfig');
            } catch (e) {
                out.error = String(e && e.stack || e);
            } finally {
                SG.__resetSceneGeneratorForTest();
            }
            return out;
        }, { chatKey, EDITED_STEPS });

        console.log('[ki65] ' + JSON.stringify(r));
        expect(r.error, 'browser-side error').toBeUndefined();
        expect(r.recordName).toBe(recordName);

        // Template start state: not skipping (server default false), and the chat pin compositeMode=flux2.
        expect(r.initial.skipLandscape).toBe(false);
        expect(r.initial.compositeMode).toBe('flux2');
        expect(r.checkedBefore).toBe(false);

        // The panel's own handler wrote both fields, and the re-rendered checkbox reflects the entity.
        expect(r.afterToggle.skipLandscape).toBe(true);
        expect(r.afterToggle.flux2IncludeLandscapeRef).toBe(false);
        expect(r.afterToggle.steps).toBe(EDITED_STEPS);
        expect(r.checkedAfter).toBe(true);
        expect(r.persisted).toBe(true);

        // Stored on the server as a real olio.sd.config record (fresh read, cache:false) ...
        let stored = await readSdConfig(page.request, recordName);
        expect(stored, 'olio.sd.config record ' + recordName).toBeTruthy();
        recordObjectId = stored.objectId;
        expect(stored.skipLandscape).toBe(true);
        expect(stored.flux2IncludeLandscapeRef).toBe(false);
        expect(stored.steps).toBe(EDITED_STEPS);
        // (The client strips model/refinerModel before saving; the server then materializes the schema
        // default for the omitted column, so `stored.model` is the SERVER default, not what chat had.
        // The invariant that matters is below: the reloaded config keeps the template's node-valid model.)

        // ... and a fresh ensureSdConfig() for the same chat overlays those stored values on the template.
        expect(r.reloaded.skipLandscape).toBe(true);
        expect(r.reloaded.flux2IncludeLandscapeRef).toBe(false);
        expect(r.reloaded.steps).toBe(EDITED_STEPS);
        expect(r.reloadedChecked).toBe(true);
        // The template's node-valid model survives the overlay (the original "Invalid model value" bug).
        expect(typeof r.reloaded.model === 'string' && r.reloaded.model.length > 0, 'template model retained').toBe(true);
        // The legacy per-browser store is gone (KI-65).
        expect(r.legacyLocalStorage).toBeNull();
    });
});
