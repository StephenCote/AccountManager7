// Feature loader for AccountManagerUx752.
//
// This module holds ONLY the non-serializable half of the feature manifest — the per-id wiring:
//   routes:        () => import(...)   lazy chunk factory (kept as an ES module so Vite can
//                                      tree-shake / code-split the dead import() paths)
//   menuItems:     [...]              top/aside menu entries
//   routePrefixes: [...]              route path prefixes this feature owns (§3.6 catch-all)
//
// The DATA half (id, label, description, required, deps) lives in ./features.manifest.json, which is
// a byte-for-byte copy of AccountManagerObjects7/src/main/resources/features/uxFeatureManifest.json —
// the same file GET /rest/config/features/available serves verbatim. Do NOT hand-edit the copy; copy
// the Objects7 resource over it. Two hand-authored manifests is the drift bug D2 exists to kill
// (see ../../aiDocs/UxFeatureFlagDesign.md §3.2 / §4a D2 — `media` rotted out of the server list
// unnoticed exactly that way).
//
// Server manifest data, when fetched, wins over the local mirror: call setManifest(array) with the
// body of GET /rest/config/features/available.
//
// CRITICAL: this module must stay dependency-free apart from the manifest JSON. It is imported by
// router.js, topMenu.js, asideMenu.js, panel.js and featureConfig.js. A static
// `import { page } from './core/pageClient.js'` would create a real ESM cycle, because pageClient.js
// carries `formDef: am7model` and therefore imports core/model.js. Everything contextual (page.roles,
// page.devMode, m.route, am7model.categories, the re-mount callback) is passed in as an argument.

import localManifest from './features.manifest.json';

// --- Wiring (client-only; cannot be serialized) ---

const featureWiring = {
    core: {
        routes: null,
        menuItems: [],
        routePrefixes: []
    },
    media: {
        // media.js registers lazy components only — `const routes = {}` (src/features/media.js:48)
        routes: () => import('./features/media.js'),
        menuItems: [],
        routePrefixes: []
    },
    chat: {
        routes: () => import('./features/chat.js'),
        menuItems: [{ icon: 'chat', label: 'Chat', route: '/chat', section: 'top' }],
        routePrefixes: ['/chat']
    },
    cardGame: {
        routes: () => import('./features/cardGame.js'),
        menuItems: [{ icon: 'playing_cards', label: 'Card Game', route: '/cardGame', section: 'top' }],
        routePrefixes: ['/cardGame']
    },
    games: {
        routes: () => import('./features/games.js'),
        menuItems: [{ icon: 'sports_esports', label: 'Games', route: '/game', section: 'top' }],
        routePrefixes: ['/game']
    },
    testHarness: {
        routes: () => import('./features/testHarness.js'),
        menuItems: [{ icon: 'science', label: 'Tests', route: '/test', section: 'top', devOnly: true }],
        routePrefixes: ['/test']
    },
    iso42001: {
        routes: () => import('./features/iso42001/routes.js'),
        menuItems: [
            { icon: 'policy', label: 'Compliance', route: '/compliance', section: 'aside', roles: ['iso42001Any'] },
            { icon: 'campaign', label: 'ISO Campaigns', route: '/iso42001/campaigns', section: 'aside', roles: ['iso42001Any'] },
            { icon: 'science', label: 'ISO Test Runs', route: '/iso42001/run', section: 'aside', roles: ['iso42001Any'] },
            { icon: 'summarize', label: 'ISO Reports', route: '/iso42001/report', section: 'aside', roles: ['iso42001Any'] },
            { icon: 'verified', label: 'ISO Certifications', route: '/iso42001/cert', section: 'aside', roles: ['iso42001Any'] }
        ],
        routePrefixes: ['/compliance', '/iso42001']
    },
    biometrics: {
        routes: () => import('./features/biometrics.js'),
        menuItems: [{ icon: 'monitor_heart', label: 'Magic 8', route: '/magic8', section: 'top' }],
        routePrefixes: ['/magic8']
    },
    schema: {
        routes: () => import('./features/schema.js'),
        menuItems: [{ icon: 'schema', label: 'Schema', route: '/schema', section: 'aside', adminOnly: true }],
        routePrefixes: ['/schema']
    },
    webauthn: {
        routes: () => import('./features/webauthn.js'),
        menuItems: [{ icon: 'passkey', label: 'Passkeys', route: '/webauthn', section: 'aside' }],
        routePrefixes: ['/webauthn']
    },
    accessRequests: {
        routes: () => import('./features/accessRequests.js'),
        menuItems: [{ icon: 'switch_access_shortcut', label: 'Access Requests', route: '/accessRequests', section: 'aside' }],
        routePrefixes: ['/accessRequests']
    },
    featureConfig: {
        routes: () => import('./features/featureConfig.js'),
        menuItems: [{ icon: 'tune', label: 'Features', route: '/admin/features', section: 'aside', adminOnly: true }],
        routePrefixes: ['/admin/features']
    },
    pictureBook: {
        routes: () => import('./features/pictureBook.js'),
        menuItems: [{ icon: 'auto_stories', label: 'Picture Book', route: '/picture-book', section: 'aside' }],
        routePrefixes: ['/picture-book']
    },
    pictureBookWorkflow: {
        routes: () => import('./features/pictureBookWorkflow.js'),
        menuItems: [],
        routePrefixes: ['/picture-book']
    }
};

// --- Manifest merge (data over wiring) ---

const features = {};
let manifestErrors = [];
let activeManifest = localManifest;

/**
 * Merge the active manifest's data fields over the client wiring, in place on `features` so that
 * existing module-level references (profiles.full, tests, featureConfig.js) stay valid.
 * A wiring id with no manifest entry is a hard error (D2) recorded in manifestErrors and surfaced in
 * the UI — never a silent skip, which is how `media` rotted unnoticed.
 */
function mergeManifest() {
    manifestErrors = [];
    let byId = {};
    if (Array.isArray(activeManifest)) {
        for (let entry of activeManifest) {
            if (entry && entry.id) byId[entry.id] = entry;
        }
    } else {
        manifestErrors.push('Feature manifest is not an array — falling back to wiring defaults.');
    }
    for (let id of Object.keys(featureWiring)) {
        let w = featureWiring[id];
        let d = byId[id];
        if (!d) {
            manifestErrors.push('Feature "' + id + '" has client wiring but no manifest entry. '
                + 'src/features.manifest.json (and GET /rest/config/features/available) must list it — '
                + 'copy AccountManagerObjects7/src/main/resources/features/uxFeatureManifest.json.');
        }
        features[id] = {
            id,
            label: d ? d.label : id,
            description: d ? d.description : '',
            required: d ? !!d.required : false,
            deps: (d && Array.isArray(d.deps)) ? d.deps.slice() : [],
            routes: w.routes,
            menuItems: w.menuItems,
            routePrefixes: w.routePrefixes || []
        };
    }
    if (manifestErrors.length) {
        console.error('[features] manifest merge errors:\n - ' + manifestErrors.join('\n - '));
    }
}

mergeManifest();

/** Replace the manifest data source (e.g. the body of GET /rest/config/features/available). */
function setManifest(manifest) {
    activeManifest = manifest;
    mergeManifest();
    return manifestErrors.slice();
}

function getManifestErrors() {
    return manifestErrors.slice();
}

// --- Profiles ---
// CLIENT-ONLY. Profiles are not in the manifest and the server has no profile catalogue: it derives
// "full" vs "custom" from the saved id set. featureConfig.js imports these rather than keeping its
// own copies (they had already drifted — see §3.2).

const profiles = {
    minimal: ['core'],
    standard: ['core', 'media', 'chat'],
    full: Object.keys(features),
    gaming: ['core', 'media', 'chat', 'cardGame', 'games', 'biometrics'],
    enterprise: ['core', 'media', 'chat', 'iso42001', 'schema', 'webauthn', 'accessRequests', 'featureConfig'],
    // ISO 42001 appliance — minimal compliance-only surface (design §9.2). chat is required (the suite runs
    // LLM tests through LLMConnector); accessRequests + featureConfig support RBAC onboarding + admin toggling.
    compliance: ['core', 'chat', 'iso42001', 'accessRequests', 'featureConfig']
};

/**
 * Derive a display name for an enabled set by matching it against the client profile catalogue.
 * The server persists only "full"/"custom", so the profile the admin actually clicked has to be
 * recovered here or the UI reports "custom" immediately after saving e.g. "Compliance".
 */
function profileNameFor(list) {
    let want = new Set(list || []);
    for (let [name, ids] of Object.entries(profiles)) {
        let have = new Set(ids);
        if (have.size !== want.size) continue;
        let same = true;
        for (let id of want) {
            if (!have.has(id)) { same = false; break; }
        }
        if (same) return name;
    }
    return 'custom';
}

// --- Feature state ---

let enabledFeatures = new Set(['core']);
let loadedRoutes = {};

function isEnabled(featureId) {
    return enabledFeatures.has(featureId);
}

function enableFeature(featureId) {
    let f = features[featureId];
    if (!f) return false;
    for (let dep of f.deps) {
        enableFeature(dep);
    }
    enabledFeatures.add(featureId);
    return true;
}

function disableFeature(featureId) {
    let f = features[featureId];
    if (!f || f.required) return false;
    for (let [id, feat] of Object.entries(features)) {
        if (enabledFeatures.has(id) && feat.deps.includes(featureId)) {
            return false;
        }
    }
    enabledFeatures.delete(featureId);
    return true;
}

function initFeatures(profile, manifest) {
    if (manifest) setManifest(manifest);
    enabledFeatures = new Set(['core']);
    loadedRoutes = {};
    let featureList;
    if (typeof profile === 'string') {
        featureList = profiles[profile] || profiles.standard;
    } else if (Array.isArray(profile)) {
        featureList = profile;
    } else {
        featureList = profiles.standard;
    }
    for (let id of featureList) {
        enableFeature(id);
    }
}

async function loadFeatureRoutes() {
    let merged = {};
    for (let id of enabledFeatures) {
        let f = features[id];
        if (f && f.routes && !loadedRoutes[id]) {
            try {
                let mod = await f.routes();
                loadedRoutes[id] = mod.routes || {};
                Object.assign(merged, loadedRoutes[id]);
            } catch (e) {
                console.warn('[features] Failed to load routes for ' + id, e);
            }
        }
    }
    return merged;
}

/**
 * Apply a new enabled set at runtime — no page reload (D3).
 * Callbacks are passed in rather than imported, to keep this module dependency-free:
 *   opts.currentRoute  () => string   current route path (m.route.get)
 *   opts.redirect      (path) => void navigate (m.route.set)
 *   opts.refresh       () => void     re-mount the router (page.router.refresh === refreshApplication)
 *   opts.redraw        () => void     m.redraw
 * If the user is currently ON a route belonging to a feature that just became disabled, re-mounting
 * would strand them on a path with no handler (router.js relies on the m.route.set that follows
 * m.route(document.body, "/main", allRoutes) to keep the existing hash), so redirect FIRST.
 */
async function applyFeatures(list, opts) {
    let o = opts || {};
    initFeatures(list);
    await loadFeatureRoutes();
    let cur = (typeof o.currentRoute === 'function') ? o.currentRoute() : o.currentRoute;
    let stranded = !!(cur && disabledFeatureForPath(cur));
    if (stranded && typeof o.redirect === 'function') {
        o.redirect('/main');
    }
    if (typeof o.refresh === 'function') o.refresh();
    if (typeof o.redraw === 'function') o.redraw();
    return { enabled: getEnabledFeatures(), redirected: stranded };
}

// --- Route ownership (§3.6) ---

function pathMatchesPrefix(path, prefix) {
    if (!path || !prefix) return false;
    return path === prefix || path.indexOf(prefix + '/') === 0;
}

/**
 * The feature id owning `path`, or null. Longest declared prefix wins so that a nested prefix
 * (e.g. /iso42001 vs /iso42001/cert) resolves to the most specific owner.
 */
function featureForPath(path) {
    if (!path) return null;
    let clean = String(path).split('?')[0].split('#')[0];
    let bestId = null;
    let bestLen = -1;
    for (let [id, f] of Object.entries(features)) {
        for (let prefix of (f.routePrefixes || [])) {
            if (pathMatchesPrefix(clean, prefix) && prefix.length > bestLen) {
                bestId = id;
                bestLen = prefix.length;
            }
        }
    }
    return bestId;
}

/** The feature id owning `path` when that feature is KNOWN but currently disabled, else null. */
function disabledFeatureForPath(path) {
    let id = featureForPath(path);
    return (id && !isEnabled(id)) ? id : null;
}

// --- Menus ---

/**
 * The single menu visibility predicate (D4), used by BOTH topMenu.js and asideMenu.js.
 * ctx = { roles, devMode } — supplied by the caller (page.context().roles / page.devMode) because
 * this module must not import pageClient.js.
 */
function isMenuItemVisible(mi, ctx) {
    if (!mi) return false;
    let c = ctx || {};
    let roles = c.roles || {};
    if (mi.adminOnly && !roles.admin) return false;
    if (mi.devOnly && !c.devMode) return false;
    if (mi.roles && mi.roles.length && !mi.roles.some(function (r) { return !!roles[r]; })) return false;
    return true;
}

function getMenuItems(section) {
    let items = [];
    for (let id of enabledFeatures) {
        let f = features[id];
        if (!f) continue;
        for (let mi of f.menuItems) {
            if (mi.section === section) {
                items.push(mi);
            }
        }
    }
    return items;
}

// --- Categories (D5) ---

/**
 * Filter model categories by their owning feature. Untagged categories are core and always shown.
 * `categories` is passed in (am7model.categories) so this module stays import-free — core/model.js
 * must not depend on features.js.
 */
function visibleCategories(categories) {
    return (categories || []).filter(function (c) { return !c.feature || isEnabled(c.feature); });
}

function getEnabledFeatures() {
    return Array.from(enabledFeatures);
}

export {
    features,
    profiles,
    profileNameFor,
    isEnabled,
    enableFeature,
    disableFeature,
    initFeatures,
    applyFeatures,
    loadFeatureRoutes,
    getMenuItems,
    isMenuItemVisible,
    visibleCategories,
    featureForPath,
    disabledFeatureForPath,
    getEnabledFeatures,
    setManifest,
    getManifestErrors
};
