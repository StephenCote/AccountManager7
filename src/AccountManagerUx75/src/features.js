// Feature manifest and loader for AccountManagerUx75
// ES module (not runtime JSON) so Vite can tree-shake dead import() paths

const features = {
    core: {
        id: 'core',
        label: 'Core',
        description: 'Object management, navigation, forms, lists',
        required: true,
        deps: [],
        routes: null,
        menuItems: []
    },
    chat: {
        id: 'chat',
        label: 'LLM Chat',
        description: 'Chat interface and LLM integration',
        required: false,
        deps: ['core'],
        routes: () => import('./features/chat.js'),
        menuItems: [{ icon: 'chat', label: 'Chat', route: '/chat', section: 'top' }]
    },
    cardGame: {
        id: 'cardGame',
        label: 'Card Game',
        description: 'RPG card game with AI director',
        required: false,
        deps: ['core', 'chat'],
        routes: () => import('./features/cardGame.js'),
        menuItems: [{ icon: 'playing_cards', label: 'Card Game', route: '/cardGame', section: 'top' }]
    },
    games: {
        id: 'games',
        label: 'Mini Games',
        description: 'Tetris, Word Game',
        required: false,
        deps: ['core'],
        routes: () => import('./features/games.js'),
        menuItems: [{ icon: 'sports_esports', label: 'Games', route: '/game', section: 'top' }]
    },
    testHarness: {
        id: 'testHarness',
        label: 'Tests',
        description: 'Automated testing UI',
        required: false,
        deps: ['core'],
        routes: () => import('./features/testHarness.js'),
        menuItems: [{ icon: 'science', label: 'Tests', route: '/test', section: 'top', devOnly: true }]
    },
    iso42001: {
        id: 'iso42001',
        label: 'Compliance',
        description: 'ISO 42001 AI compliance evaluation and bias detection',
        required: false,
        deps: ['core', 'chat'],
        routes: () => import('./features/iso42001.js'),
        menuItems: [{ icon: 'policy', label: 'Compliance', route: '/compliance', section: 'aside' }]
    },
    biometrics: {
        id: 'biometrics',
        label: 'Biometrics',
        description: 'Camera, mood ring, biometric theming',
        required: false,
        deps: ['core'],
        routes: () => import('./features/biometrics.js'),
        menuItems: [{ icon: 'monitor_heart', label: 'Biometrics', route: '/hyp', section: 'top' }]
    }
};

const profiles = {
    minimal: ['core'],
    standard: ['core', 'chat'],
    full: Object.keys(features),
    gaming: ['core', 'chat', 'cardGame', 'games', 'biometrics'],
    enterprise: ['core', 'chat', 'iso42001']
};

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

function initFeatures(profile) {
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
        if (f.routes && !loadedRoutes[id]) {
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

function getMenuItems(section) {
    let items = [];
    for (let id of enabledFeatures) {
        let f = features[id];
        for (let mi of f.menuItems) {
            if (mi.section === section) {
                items.push(mi);
            }
        }
    }
    return items;
}

function getEnabledFeatures() {
    return Array.from(enabledFeatures);
}

export {
    features,
    profiles,
    isEnabled,
    enableFeature,
    disableFeature,
    initFeatures,
    loadFeatureRoutes,
    getMenuItems,
    getEnabledFeatures
};
