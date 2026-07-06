/**
 * CardGame Module - Entry point and public API
 * Modular card game with phases, combat, AI director, and deck building
 *
 * Script Loading Order (dependency order):
 *   1. constants/gameConstants.js
 *   2. state/storage.js
 *   3. rendering/cardComponents.js
 *   4. rendering/cardFace.js
 *   5. rendering/overlays.js
 *   6. services/characters.js
 *   7. services/themes.js
 *   8. services/artPipeline.js
 *   9. engine/effects.js
 *  10. engine/combat.js
 *  11. engine/encounters.js
 *  12. engine/actions.js
 *  13. state/gameState.js
 *  14. ai/llmBase.js
 *  15. ai/director.js
 *  16. ai/narrator.js
 *  17. ai/chatManager.js
 *  18. ai/voice.js
 *  19. ui/deckList.js
 *  20. ui/builder.js
 *  21. ui/deckView.js
 *  22. ui/phaseUI.js
 *  23. ui/threatUI.js
 *  24. ui/chatUI.js
 *  25. ui/gameOverUI.js
 *  26. ui/gameView.js
 *  27. test/testMode.js
 *  28. designer/layoutConfig.js
 *  29. designer/layoutRenderer.js
 *  30. designer/iconPicker.js
 *  31. designer/designerCanvas.js
 *  32. designer/exportPipeline.js
 *  33. designer/exportDialog.js
 *  34. CardGameApp.js
 *  35. index.js (this file)
 */
(function() {

    // Ensure CardGame namespace exists
    window.CardGame = window.CardGame || {};

    /**
     * Get the script paths for loading the CardGame module
     * @param {string} basePath - Base path to cardGame directory
     * @returns {Array<string>} Ordered script paths in dependency order
     */
    CardGame.getScriptPaths = function(basePath) {
        const base = basePath || 'client/view/cardGame';
        return [
            `${base}/constants/gameConstants.js`,
            `${base}/state/storage.js`,
            `${base}/rendering/cardComponents.js`,
            `${base}/rendering/cardFace.js`,
            `${base}/rendering/overlays.js`,
            `${base}/services/characters.js`,
            `${base}/services/themes.js`,
            `${base}/services/artPipeline.js`,
            `${base}/engine/effects.js`,
            `${base}/engine/combat.js`,
            `${base}/engine/encounters.js`,
            `${base}/engine/actions.js`,
            `${base}/state/gameState.js`,
            `${base}/ai/llmBase.js`,
            `${base}/ai/director.js`,
            `${base}/ai/narrator.js`,
            `${base}/ai/chatManager.js`,
            `${base}/ai/voice.js`,
            `${base}/ui/deckList.js`,
            `${base}/ui/builder.js`,
            `${base}/ui/deckView.js`,
            `${base}/ui/phaseUI.js`,
            `${base}/ui/threatUI.js`,
            `${base}/ui/chatUI.js`,
            `${base}/ui/gameOverUI.js`,
            `${base}/ui/gameView.js`,
            `${base}/test/testMode.js`,
            `${base}/designer/layoutConfig.js`,
            `${base}/designer/layoutRenderer.js`,
            `${base}/designer/iconPicker.js`,
            `${base}/designer/designerCanvas.js`,
            `${base}/designer/exportPipeline.js`,
            `${base}/designer/exportDialog.js`,
            `${base}/CardGameApp.js`,
            `${base}/index.js`
        ];
    };

    // Module info
    CardGame.VERSION = '2.0';
    CardGame.MODULE_NAME = 'cardGame';

    console.log('CardGame module loaded (v' + CardGame.VERSION + ')');

}());
