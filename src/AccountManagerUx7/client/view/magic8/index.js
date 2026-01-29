/**
 * Magic8 Module - Entry point and public API
 * Consolidated immersive experience with biometric theming, audio, and AI generation
 *
 * Script Loading Order:
 *   1. utils/FullscreenManager.js
 *   2. utils/DynamicImageGallery.js
 *   3. audio/AudioEngine.js
 *   4. state/BiometricThemer.js
 *   5. video/SessionRecorder.js
 *   6. text/TextSequenceManager.js
 *   7. generation/ImageGenerationManager.js
 *   8. components/HypnoCanvas.js
 *   9. components/HypnoticTextDisplay.js
 *  10. components/ControlPanel.js
 *  11. components/SessionConfigEditor.js
 *  12. Magic8App.js
 *  13. index.js (this file)
 */
(function() {

    // Ensure Magic8 namespace exists
    window.Magic8 = window.Magic8 || {};

    /**
     * Launch Magic8 from chat context
     * @param {Object} chatContext - Chat context to pass to Magic8
     * @param {Object} chatContext.chatConfigId - Chat config object ID
     * @param {Object} chatContext.chatHistory - Recent chat messages
     * @param {Object} chatContext.systemCharacter - System character info
     * @param {Object} chatContext.userCharacter - User character info
     * @param {Object} chatContext.instanceId - Chat instance ID
     */
    Magic8.launchFromChat = function(chatContext) {
        sessionStorage.setItem('magic8Context', JSON.stringify(chatContext));
        m.route.set('/magic8');
    };

    /**
     * Launch Magic8 with a saved session config
     * @param {string} sessionConfigId - Object ID of saved config
     */
    Magic8.launchWithConfig = function(sessionConfigId) {
        m.route.set('/magic8', { sessionConfigId: sessionConfigId });
    };

    /**
     * Launch Magic8 with fresh configuration
     */
    Magic8.launch = function() {
        m.route.set('/magic8');
    };

    /**
     * Check for return context from Magic8 (for chat.js to use)
     * @returns {Object|null} Return context or null
     */
    Magic8.getReturnContext = function() {
        const json = sessionStorage.getItem('magic8Return');
        if (json) {
            sessionStorage.removeItem('magic8Return');
            try {
                return JSON.parse(json);
            } catch (e) {
                return null;
            }
        }
        return null;
    };

    /**
     * Get the script paths for loading Magic8 module
     * @param {string} basePath - Base path to magic8 directory
     * @returns {Array<string>} Ordered script paths
     */
    Magic8.getScriptPaths = function(basePath) {
        const base = basePath || 'view/magic8';
        return [
            `${base}/utils/FullscreenManager.js`,
            `${base}/utils/DynamicImageGallery.js`,
            `${base}/audio/AudioEngine.js`,
            `${base}/state/BiometricThemer.js`,
            `${base}/video/SessionRecorder.js`,
            `${base}/text/TextSequenceManager.js`,
            `${base}/generation/ImageGenerationManager.js`,
            `${base}/components/HypnoCanvas.js`,
            `${base}/components/HypnoticTextDisplay.js`,
            `${base}/components/ControlPanel.js`,
            `${base}/components/SessionConfigEditor.js`,
            `${base}/Magic8App.js`,
            `${base}/index.js`
        ];
    };

    // Module info
    Magic8.VERSION = '1.0.0';
    Magic8.MODULE_NAME = 'Magic8';

    console.log('Magic8 module loaded (v' + Magic8.VERSION + ')');

}());
