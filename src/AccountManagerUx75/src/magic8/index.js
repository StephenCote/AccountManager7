/**
 * Magic8 Module — Entry point and public API (ESM)
 * Consolidated immersive experience with biometric theming, audio, and AI generation.
 *
 * All subsystem modules are imported by Magic8App.js internally.
 * This file exposes the public launch API and the route component.
 */
import m from 'mithril';

let Magic8App = null;

/**
 * Lazily load the Magic8App module.
 * @returns {Promise<Object>} The Magic8App component
 */
async function ensureLoaded() {
    if (Magic8App) return Magic8App;
    let mod = await import('./Magic8App.js');
    Magic8App = mod.Magic8App || mod.default;
    return Magic8App;
}

/**
 * Launch Magic8 from chat context.
 * @param {Object} chatContext - Chat context (chatConfigId, chatHistory, systemCharacter, userCharacter, instanceId)
 */
function launchFromChat(chatContext) {
    sessionStorage.setItem('magic8Context', JSON.stringify(chatContext));
    let routeParams = { context: 'chat' };
    if (chatContext && chatContext.instanceId) {
        routeParams.sessionId = chatContext.instanceId;
    }
    m.route.set('/magic8', routeParams);
}

/**
 * Launch Magic8 with a saved session config.
 * @param {string} sessionConfigId - Object ID of saved config
 */
function launchWithConfig(sessionConfigId) {
    m.route.set('/magic8', { sessionConfigId: sessionConfigId });
}

/**
 * Launch Magic8 with fresh configuration.
 */
function launch() {
    m.route.set('/magic8');
}

/**
 * Check for return context from Magic8 (for chat.js to use).
 * @returns {Object|null} Return context or null
 */
function getReturnContext() {
    let json = sessionStorage.getItem('magic8Return');
    if (json) {
        sessionStorage.removeItem('magic8Return');
        try { return JSON.parse(json); } catch (e) { return null; }
    }
    return null;
}

const VERSION = '1.0.0';

export {
    ensureLoaded,
    launchFromChat,
    launchWithConfig,
    launch,
    getReturnContext,
    VERSION
};

export default {
    ensureLoaded,
    launchFromChat,
    launchWithConfig,
    launch,
    getReturnContext,
    VERSION
};
