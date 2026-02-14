/**
 * Magic8 Module - Entry point and public API
 * Consolidated immersive experience with biometric theming, audio, and AI generation
 *
 * Script Loading Order:
 *   1. utils/FullscreenManager.js
 *   2. utils/DynamicImageGallery.js
 *   3. audio/AudioEngine.js
 *   4. audio/VoiceSequenceManager.js
 *   5. state/BiometricThemer.js
 *   6. video/SessionRecorder.js
 *   7. text/TextSequenceManager.js
 *   8. generation/ImageGenerationManager.js
 *   9. ai/SessionDirector.js
 *  10. components/HypnoCanvas.js
 *  11. components/AudioVisualizerOverlay.js
 *  12. components/HypnoticTextDisplay.js
 *  13. components/ControlPanel.js
 *  14. components/MoodEmojiDisplay.js
 *  15. components/MoodRingButton.js
 *  16. components/BiometricOverlay.js
 *  17. components/SessionConfigEditor.js
 *  18. Magic8App.js
 *  19. index.js (this file)
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
        // Phase 10c: Pass sessionId as route param for server-side context lookup.
        // sessionStorage kept as fallback for complex objects (history, characters).
        sessionStorage.setItem('magic8Context', JSON.stringify(chatContext));
        let routeParams = { context: 'chat' };
        if (chatContext && chatContext.instanceId) {
            routeParams.sessionId = chatContext.instanceId;
        }
        m.route.set('/magic8', routeParams);
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
            `${base}/audio/VoiceSequenceManager.js`,
            `${base}/state/BiometricThemer.js`,
            `${base}/video/SessionRecorder.js`,
            `${base}/text/TextSequenceManager.js`,
            `${base}/generation/ImageGenerationManager.js`,
            `${base}/ai/SessionDirector.js`,
            `${base}/components/HypnoCanvas.js`,
            `${base}/components/AudioVisualizerOverlay.js`,
            `${base}/components/HypnoticTextDisplay.js`,
            `${base}/components/ControlPanel.js`,
            `${base}/components/MoodEmojiDisplay.js`,
            `${base}/components/MoodRingButton.js`,
            `${base}/components/BiometricOverlay.js`,
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
