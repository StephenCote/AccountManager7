/**
 * Feature flag slice — D2 (one manifest), D3 (applyFeatures), D4 (one visibility predicate),
 * D5 (feature-tagged categories) and §3.6 (route prefixes / disabled-route feedback).
 * See ../../../aiDocs/UxFeatureFlagDesign.md §3 and §4a.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { resolveFeatureProfile } from '../core/featureProfile.js';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

import {
    features,
    profiles,
    profileNameFor,
    isEnabled,
    initFeatures,
    applyFeatures,
    getMenuItems,
    isMenuItemVisible,
    visibleCategories,
    featureForPath,
    disabledFeatureForPath,
    getEnabledFeatures,
    setManifest,
    getManifestErrors
} from '../features.js';
import localManifest from '../features.manifest.json';
import { am7model } from '../core/modelDef.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const srcDir = resolve(__dirname, '..');
const repoSrc = resolve(__dirname, '..', '..', '..');

// ── D2: one manifest ────────────────────────────────────────────────────

describe('D2 — one manifest', () => {

    it('src/features.manifest.json is byte-for-byte the Objects7 resource', () => {
        let mirror = readFileSync(resolve(srcDir, 'features.manifest.json'));
        let source = readFileSync(resolve(repoSrc,
            'AccountManagerObjects7', 'src', 'main', 'resources', 'features', 'uxFeatureManifest.json'));
        // A hand-authored second copy is the drift bug D2 exists to kill: the mirror must be a copy.
        expect(mirror.equals(source)).toBe(true);
    });

    it('every client wiring id has a manifest entry (no silent skips)', () => {
        setManifest(localManifest);
        expect(getManifestErrors()).toEqual([]);
    });

    it('manifest data is merged over the wiring for every feature', () => {
        setManifest(localManifest);
        expect(localManifest.length).toBe(Object.keys(features).length);
        for (let entry of localManifest) {
            let f = features[entry.id];
            expect(f, entry.id + ' must have client wiring').toBeDefined();
            expect(f.label).toBe(entry.label);
            expect(f.description).toBe(entry.description);
            expect(f.required).toBe(entry.required);
            expect(f.deps).toEqual(entry.deps);
        }
    });

    it('wiring is preserved through the merge', () => {
        setManifest(localManifest);
        expect(typeof features.chat.routes).toBe('function');
        expect(features.core.routes).toBe(null);
        expect(Array.isArray(features.chat.menuItems)).toBe(true);
        expect(Array.isArray(features.chat.routePrefixes)).toBe(true);
    });

    it('server manifest data wins over the local mirror', () => {
        let server = localManifest.map(function (e) { return Object.assign({}, e); });
        server.find(function (e) { return e.id === 'chat'; }).label = 'Server Chat Label';
        let errs = setManifest(server);
        expect(errs).toEqual([]);
        expect(features.chat.label).toBe('Server Chat Label');
        setManifest(localManifest);
        expect(features.chat.label).toBe('LLM Chat');
    });

    it('a wiring id missing from the manifest is a hard error, not a silent skip', () => {
        let spy = vi.spyOn(console, 'error').mockImplementation(function () {});
        // Reproduces the historical `media` drift: server list omits an id the client wires up.
        let server = localManifest.filter(function (e) { return e.id !== 'media'; });
        let errs = setManifest(server);
        expect(errs.length).toBe(1);
        expect(errs[0]).toContain('media');
        expect(getManifestErrors().length).toBe(1);
        // The feature is NOT dropped — silent skipping is what let `media` rot unnoticed.
        expect(features.media).toBeDefined();
        expect(spy).toHaveBeenCalled();
        spy.mockRestore();
        setManifest(localManifest);
        expect(getManifestErrors()).toEqual([]);
    });

    it('profiles stay client-only and are not in the manifest', () => {
        for (let entry of localManifest) {
            expect(entry.profile).toBeUndefined();
        }
        expect(Object.keys(profiles)).toEqual(
            ['minimal', 'standard', 'full', 'gaming', 'enterprise', 'compliance']);
    });

    it('featureConfig.js renders profiles from features.js instead of hardcoding them', () => {
        let src = readFileSync(resolve(srcDir, 'features', 'featureConfig.js'), 'utf-8');
        expect(src).toContain('Object.entries(profiles)');
        // the six hardcoded lists that had already drifted ("Standard" was ["core","chat"])
        expect(src).not.toContain('profileButton("Standard"');
        expect(src).not.toContain('profileButton("Compliance"');
    });

    it('profileNameFor recovers the profile name the server no longer persists', () => {
        expect(profileNameFor(profiles.compliance)).toBe('compliance');
        expect(profileNameFor(['iso42001', 'core', 'chat', 'accessRequests', 'featureConfig']))
            .toBe('compliance');
        expect(profileNameFor(profiles.minimal)).toBe('minimal');
        expect(profileNameFor(profiles.full)).toBe('full');
        expect(profileNameFor(['core', 'games'])).toBe('custom');
        expect(profileNameFor([])).toBe('custom');
    });
});

// ── D3: applyFeatures ───────────────────────────────────────────────────

describe('D3 — applyFeatures applies without a reload', () => {

    beforeEach(() => {
        initFeatures('full');
    });

    it('resets the enabled set and re-mounts via the passed-in refresh callback', async () => {
        let refresh = vi.fn();
        let redraw = vi.fn();
        let result = await applyFeatures(['core'], {
            currentRoute: function () { return '/main'; },
            redirect: vi.fn(),
            refresh,
            redraw
        });
        expect(getEnabledFeatures()).toEqual(['core']);
        expect(isEnabled('cardGame')).toBe(false);
        expect(refresh).toHaveBeenCalledTimes(1);
        expect(redraw).toHaveBeenCalledTimes(1);
        expect(result.redirected).toBe(false);
    });

    it('redirects to /main when the current route belongs to a just-disabled feature', async () => {
        let redirect = vi.fn();
        let refresh = vi.fn();
        let result = await applyFeatures(['core'], {
            currentRoute: function () { return '/cardGame'; },
            redirect,
            refresh
        });
        expect(result.redirected).toBe(true);
        expect(redirect).toHaveBeenCalledWith('/main');
        // BEFORE re-mounting — otherwise m.route(document.body, "/main", allRoutes) keeps the
        // existing hash and the following m.route.set strands the user on a routeless path.
        expect(redirect.mock.invocationCallOrder[0]).toBeLessThan(refresh.mock.invocationCallOrder[0]);
    });

    it('does not redirect when the current route survives', async () => {
        let redirect = vi.fn();
        let result = await applyFeatures(['core', 'chat'], {
            currentRoute: function () { return '/chat' },
            redirect,
            refresh: vi.fn()
        });
        expect(result.redirected).toBe(false);
        expect(redirect).not.toHaveBeenCalled();
    });

    it('works with no callbacks at all', async () => {
        await applyFeatures(['core']);
        expect(getEnabledFeatures()).toEqual(['core']);
    });

    it('featureConfig.js no longer tells the user to reload', () => {
        let src = readFileSync(resolve(srcDir, 'features', 'featureConfig.js'), 'utf-8');
        expect(src).not.toContain('Reload the page to apply changes');
        expect(src).toContain('applyFeatures(');
        expect(src).not.toMatch(/\binitFeatures\(/);
    });
});

// ── D4: one visibility predicate ────────────────────────────────────────

describe('D4 — isMenuItemVisible', () => {

    const plain = { icon: 'x', label: 'Plain', route: '/x', section: 'top' };
    const adminItem = { icon: 'x', label: 'Admin', route: '/x', section: 'aside', adminOnly: true };
    const devItem = { icon: 'x', label: 'Dev', route: '/x', section: 'top', devOnly: true };
    const roleItem = { icon: 'x', label: 'Iso', route: '/x', section: 'aside', roles: ['iso42001Any'] };

    it('untagged items are always visible', () => {
        expect(isMenuItemVisible(plain, { roles: {}, devMode: false })).toBe(true);
        expect(isMenuItemVisible(plain, {})).toBe(true);
    });

    it('adminOnly requires roles.admin', () => {
        expect(isMenuItemVisible(adminItem, { roles: {}, devMode: true })).toBe(false);
        expect(isMenuItemVisible(adminItem, { roles: { admin: true } })).toBe(true);
    });

    it('devOnly requires devMode', () => {
        expect(isMenuItemVisible(devItem, { roles: {}, devMode: false })).toBe(false);
        expect(isMenuItemVisible(devItem, { roles: {}, devMode: true })).toBe(true);
        // the asideMenu regression: page.devMode was never defined, so devOnly items were
        // permanently hidden — an undefined ctx must behave as "not dev", never as "dev"
        expect(isMenuItemVisible(devItem, {})).toBe(false);
    });

    it('roles requires at least one listed role', () => {
        expect(isMenuItemVisible(roleItem, { roles: {} })).toBe(false);
        expect(isMenuItemVisible(roleItem, { roles: { iso42001Any: true } })).toBe(true);
        expect(isMenuItemVisible(roleItem, { roles: { admin: true } })).toBe(false);
    });

    it('testHarness is hidden when devMode is false (the topMenu regression)', () => {
        initFeatures('full');
        let items = getMenuItems('top');
        let harness = items.find(function (mi) { return mi.label === 'Tests'; });
        expect(harness).toBeDefined();
        expect(harness.devOnly).toBe(true);
        expect(isMenuItemVisible(harness, { roles: { admin: true }, devMode: false })).toBe(false);
        expect(isMenuItemVisible(harness, { roles: {}, devMode: true })).toBe(true);
    });

    it('the five ISO aside items are gated on iso42001Any', () => {
        let iso = features.iso42001.menuItems;
        expect(iso.length).toBe(5);
        for (let mi of iso) {
            expect(mi.roles).toEqual(['iso42001Any']);
            expect(isMenuItemVisible(mi, { roles: { user: true } })).toBe(false);
            expect(isMenuItemVisible(mi, { roles: { iso42001Any: true } })).toBe(true);
        }
    });

    it('both menus use the shared predicate', () => {
        let top = readFileSync(resolve(srcDir, 'components', 'topMenu.js'), 'utf-8');
        let aside = readFileSync(resolve(srcDir, 'components', 'asideMenu.js'), 'utf-8');
        expect(top).toContain('isMenuItemVisible(mi, menuCtx)');
        expect(aside).toContain('isMenuItemVisible(mi, menuCtx)');
        // productionMode is no longer a proxy for "not dev"
        expect(top).not.toContain('mi.devOnly && page.productionMode');
    });

    it('pageClient defines devMode from import.meta.env.DEV', () => {
        let src = readFileSync(resolve(srcDir, 'core', 'pageClient.js'), 'utf-8');
        expect(src).toContain('devMode:');
        expect(src).toContain('import.meta.env.DEV');
    });
});

// ── D5: feature-tagged categories ───────────────────────────────────────

describe('D5 — visibleCategories', () => {

    it('modelDef tags olio -> cardGame and ai -> chat, and nothing else', () => {
        let tagged = {};
        for (let c of am7model.categories) {
            if (c.feature) tagged[c.name] = c.feature;
        }
        expect(tagged).toEqual({ olio: 'cardGame', ai: 'chat' });
    });

    it('minimal profile shows only the four core categories', () => {
        initFeatures('minimal');
        let names = visibleCategories(am7model.categories).map(function (c) { return c.name; });
        expect(names).toEqual(['identity', 'asset', 'process', 'policy']);
        expect(names).not.toContain('olio');
        expect(names).not.toContain('ai');
    });

    it('full profile shows all six categories', () => {
        initFeatures('full');
        let names = visibleCategories(am7model.categories).map(function (c) { return c.name; });
        expect(names.length).toBe(am7model.categories.length);
        expect(names).toContain('olio');
        expect(names).toContain('ai');
    });

    it('chat alone reveals ai but not olio', () => {
        initFeatures(['core', 'chat']);
        let names = visibleCategories(am7model.categories).map(function (c) { return c.name; });
        expect(names).toContain('ai');
        expect(names).not.toContain('olio');
    });

    it('tolerates a missing categories array', () => {
        expect(visibleCategories(null)).toEqual([]);
    });

    it('is applied at exactly the three category consumers', () => {
        let panelSrc = readFileSync(resolve(srcDir, 'components', 'panel.js'), 'utf-8');
        let asideSrc = readFileSync(resolve(srcDir, 'components', 'asideMenu.js'), 'utf-8');
        let modelSrc = readFileSync(resolve(srcDir, 'core', 'model.js'), 'utf-8');
        expect((panelSrc.match(/visibleCategories\(am7model\.categories\)/g) || []).length).toBe(2);
        expect((asideSrc.match(/visibleCategories\(am7model\.categories\)/g) || []).length).toBe(1);
        // core/model.js must NOT import features.js — getPrototype only walks identity/asset, both
        // untagged, so filtering there has no effect and would only create an import cycle.
        expect(modelSrc).not.toContain('features.js');
    });
});

// ── §3.6: route prefixes + disabled-route feedback ──────────────────────

/**
 * Pull the lazy import path out of a feature's `routes` factory, so this test verifies the REAL
 * wiring rather than a second hand-maintained id->file map. Matches both the authored form
 * (`() => import('./features/chat.js')`) and Vitest's SSR-transformed form
 * (`() => __vite_ssr_dynamic_import__("/src/features/chat.js")`).
 */
function wiringSourcePath(id) {
    let f = features[id];
    if (!f || typeof f.routes !== 'function') return null;
    let m = String(f.routes).match(/features\/([^"')]+)/);
    return m ? resolve(srcDir, 'features', m[1]) : null;
}

/** Extract the top-level keys of the exported `routes` object literal from a feature module. */
function extractRouteKeys(source) {
    let match = /(?:export\s+)?const\s+routes\s*=\s*\{/.exec(source);
    if (!match) return null;
    let i = source.indexOf('{', match.index);
    let depth = 0;
    let keys = [];
    let quote = null;
    let buf = '';
    for (; i < source.length; i++) {
        let ch = source[i];
        if (quote) {
            if (ch === '\\') { i++; continue; }
            if (ch === quote) {
                if (depth === 1) buf = source.slice(start + 1, i);
                quote = null;
            }
            continue;
        }
        if (ch === '"' || ch === "'" || ch === '`') { quote = ch; var start = i; continue; }
        if (ch === '{') { depth++; continue; }
        if (ch === '}') {
            depth--;
            if (depth === 0) break;
            continue;
        }
        if (ch === ':' && depth === 1 && buf && buf[0] === '/') {
            keys.push(buf);
            buf = '';
        }
    }
    return keys;
}

describe('§3.6 — routePrefixes are self-verifying', () => {

    it('every route key a feature module exports starts with a declared prefix', () => {
        let checked = 0;
        for (let id of Object.keys(features)) {
            let path = wiringSourcePath(id);
            if (!path) {
                expect(features[id].routePrefixes,
                    id + ' has no routes factory so it must declare no prefixes').toEqual([]);
                continue;
            }
            let src = readFileSync(path, 'utf-8');
            let keys = extractRouteKeys(src);
            expect(keys, id + ': could not parse a routes object out of ' + path).not.toBe(null);
            let prefixes = features[id].routePrefixes;
            if (!keys.length) {
                // media registers lazy components only (src/features/media.js:48 — `const routes = {}`)
                expect(prefixes, id + ' exports no routes so it must declare no prefixes').toEqual([]);
                continue;
            }
            expect(prefixes.length, id + ' exports routes so it must declare prefixes').toBeGreaterThan(0);
            for (let key of keys) {
                let owned = prefixes.some(function (p) { return key === p || key.indexOf(p + '/') === 0; });
                expect(owned, id + ' route "' + key + '" is not covered by ' + JSON.stringify(prefixes)).toBe(true);
                checked++;
            }
        }
        // guard against a broken extractor passing vacuously
        expect(checked).toBeGreaterThan(20);
    });

    it('extracts the route keys it claims to (extractor sanity check)', () => {
        expect(extractRouteKeys(readFileSync(wiringSourcePath('chat'), 'utf-8'))).toEqual(['/chat']);
        expect(extractRouteKeys(readFileSync(wiringSourcePath('games'), 'utf-8')))
            .toEqual(['/game', '/game/:gameId']);
        expect(extractRouteKeys(readFileSync(wiringSourcePath('media'), 'utf-8'))).toEqual([]);
        expect(extractRouteKeys(readFileSync(wiringSourcePath('iso42001'), 'utf-8')).length).toBe(12);
        expect(extractRouteKeys(readFileSync(wiringSourcePath('pictureBook'), 'utf-8')))
            .toEqual(['/picture-book', '/picture-book/v2/:pb2BookObjectId', '/picture-book/:bookObjectId']);
    });

    it('no declared prefix collides with a core route', () => {
        let coreRoutes = ['/sig', '/setup', '/main', '/list', '/plist', '/view', '/new', '/nav', '/explorer'];
        for (let id of Object.keys(features)) {
            for (let p of features[id].routePrefixes) {
                expect(coreRoutes, id + ' prefix ' + p + ' collides with a core route').not.toContain(p);
            }
        }
    });
});

describe('§3.6 — featureForPath / disabledFeatureForPath', () => {

    it('maps paths to their owning feature', () => {
        expect(featureForPath('/chat')).toBe('chat');
        expect(featureForPath('/cardGame')).toBe('cardGame');
        expect(featureForPath('/game')).toBe('games');
        expect(featureForPath('/game/tetris')).toBe('games');
        expect(featureForPath('/test')).toBe('testHarness');
        expect(featureForPath('/magic8')).toBe('biometrics');
        expect(featureForPath('/schema')).toBe('schema');
        expect(featureForPath('/webauthn')).toBe('webauthn');
        expect(featureForPath('/accessRequests')).toBe('accessRequests');
        expect(featureForPath('/admin/features')).toBe('featureConfig');
        expect(featureForPath('/picture-book/abc')).toBe('pictureBook');
        expect(featureForPath('/compliance')).toBe('iso42001');
        expect(featureForPath('/iso42001/cert/view/abc')).toBe('iso42001');
    });

    it('does not match on a bare string prefix', () => {
        expect(featureForPath('/gameshow')).toBe(null);
        expect(featureForPath('/chatter')).toBe(null);
        expect(featureForPath('/cardGame')).toBe('cardGame'); // and not games via "/game"
    });

    it('returns null for core and unknown paths', () => {
        expect(featureForPath('/main')).toBe(null);
        expect(featureForPath('/list/data.data')).toBe(null);
        expect(featureForPath('/nonsense')).toBe(null);
        expect(featureForPath('')).toBe(null);
        expect(featureForPath(null)).toBe(null);
    });

    it('ignores a query string', () => {
        expect(featureForPath('/chat?x=1')).toBe('chat');
    });

    it('disabledFeatureForPath only fires for known-but-disabled features', () => {
        initFeatures('minimal');
        expect(disabledFeatureForPath('/cardGame')).toBe('cardGame');
        expect(disabledFeatureForPath('/main')).toBe(null);
        expect(disabledFeatureForPath('/nonsense')).toBe(null);
        initFeatures('full');
        expect(disabledFeatureForPath('/cardGame')).toBe(null);
    });

    it('router registers a variadic catch-all that SKIPs paths it does not own', () => {
        // behaviour is proven against the real Mithril router in disabledFeatureRoute.test.js;
        // this only pins the wiring into router.js
        let route = readFileSync(resolve(srcDir, 'core', 'featureRoute.js'), 'utf-8');
        expect(route).toContain('"/:featurePath..."');
        expect(route).toContain('m.route.SKIP');
        expect(route).toContain('This feature is not enabled.');
        let src = readFileSync(resolve(srcDir, 'router.js'), 'utf-8');
        expect(src).toContain('allRoutes[disabledFeatureRouteKey] = disabledFeatureRoute');
        expect(src).toContain('createDisabledFeatureRoute(');
    });
});

// ── §3.7: profile precedence ────────────────────────────────────────────

describe('§3.7 — profile precedence', () => {

    // These exercise resolveFeatureProfile() for real. They replace three earlier assertions that
    // read router.js as TEXT and checked substring ORDER — those would have stayed green if the
    // devMode guard wrapped the wrong block or configFailed were never set, i.e. they were fake
    // tests by .claude/rules/llm-conduct.md. The logic was extracted to core/featureProfile.js
    // precisely so it could be executed: router.js cannot be imported by a unit test.

    const USER = { name: 'someone' };
    const ok = (features) => async () => ({ features });

    it('precedence: ?features= wins over server config, in dev', async () => {
        let called = false;
        let r = await resolveFeatureProfile({
            devMode: true, search: '?features=gaming', user: USER,
            getFeatureConfig: async () => { called = true; return { features: ['core'] }; },
            buildProfile: 'full'
        });
        expect(r.profile).toBe('gaming');
        expect(r.configFailed).toBe(false);
        expect(called).toBe(false); // short-circuits — the server is not even consulted
    });

    it('the ?features= override is INERT when devMode is false', async () => {
        let r = await resolveFeatureProfile({
            devMode: false, search: '?features=gaming', user: USER,
            getFeatureConfig: ok(['core', 'chat']), buildProfile: 'full'
        });
        expect(r.profile).toEqual(['core', 'chat']); // server wins, not the URL
    });

    it('precedence: server config beats the build define', async () => {
        let r = await resolveFeatureProfile({
            devMode: false, search: '', user: USER,
            getFeatureConfig: ok(['core', 'iso42001']), buildProfile: 'full'
        });
        expect(r.profile).toEqual(['core', 'iso42001']);
    });

    it('precedence: build define beats the default, and the default is standard', async () => {
        let unauth = { devMode: false, search: '', user: null, getFeatureConfig: ok(['core']) };
        expect((await resolveFeatureProfile({ ...unauth, buildProfile: 'gaming' })).profile).toBe('gaming');
        expect((await resolveFeatureProfile({ ...unauth, buildProfile: null })).profile).toBe('standard');
    });

    it('the server is not consulted at all when unauthenticated', async () => {
        let called = false;
        let r = await resolveFeatureProfile({
            devMode: false, search: '', user: null,
            getFeatureConfig: async () => { called = true; return { features: ['core'] }; },
            buildProfile: null
        });
        expect(called).toBe(false);
        expect(r.profile).toBe('standard');
        expect(r.configFailed).toBe(false);
    });

    // ── the failure branch the §3.7 implementation note exists to protect ──

    it('a THROWN config request fails open to full and flags a notice', async () => {
        let r = await resolveFeatureProfile({
            devMode: false, search: '', user: USER,
            getFeatureConfig: async () => { throw new Error('boom'); }, buildProfile: null
        });
        expect(r.profile).toBe('full');       // fail OPEN, not to 'standard'
        expect(r.configFailed).toBe(true);    // caller must surface a visible notice
    });

    it('a non-2xx (undefined body) fails open to full — am7client swallows the error', async () => {
        let r = await resolveFeatureProfile({
            devMode: false, search: '', user: USER,
            getFeatureConfig: async () => undefined, buildProfile: null
        });
        expect(r.profile).toBe('full');
        expect(r.configFailed).toBe(true);
    });

    it('a body whose features is missing or not an array counts as failure', async () => {
        for (let bad of [{}, { features: null }, { features: 'core,chat' }, { features: { 0: 'core' } }]) {
            let r = await resolveFeatureProfile({
                devMode: false, search: '', user: USER,
                getFeatureConfig: async () => bad, buildProfile: null
            });
            expect(r.profile).toBe('full');
            expect(r.configFailed).toBe(true);
        }
    });

    it('an EMPTY array is a real answer, not a failure — [] is truthy in JS', async () => {
        // the pre-change bug: `if (serverConfig.features)` accepted [] as valid AND a failure as
        // falsy. Array.isArray is the discriminator.
        let r = await resolveFeatureProfile({
            devMode: false, search: '', user: USER,
            getFeatureConfig: ok([]), buildProfile: null
        });
        expect(r.profile).toEqual([]);
        expect(r.configFailed).toBe(false);
    });

    it('["core"] is a legal small set, NOT a failure — minimal stays reachable', async () => {
        let r = await resolveFeatureProfile({
            devMode: false, search: '', user: USER,
            getFeatureConfig: ok(['core']), buildProfile: null
        });
        expect(r.profile).toEqual(['core']);
        expect(r.configFailed).toBe(false);   // must not fail open, or minimal is unreachable
        initFeatures(r.profile);
        expect(getEnabledFeatures()).toEqual(['core']);
        expect(profileNameFor(['core'])).toBe('minimal');
    });

});
