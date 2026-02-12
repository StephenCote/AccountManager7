const esbuild = require('esbuild');
const fs = require('fs');
const path = require('path');

// JS files to bundle (in order of dependency)
const jsFiles = [
    // Pre-module scripts (these define globals)
    'client/am7client.js',
    'common/base64.js',

    // Model layer
    'model/modelDef.js',
    'model/model.js',

    // Common utilities
    'common/ntc.js',

    // Core client
    'client/codemirror.jslint.js',
    'client/bbscript.js',
    'client/pageClient.js',

    // Base components
    'client/components/dialog.js',
    'client/components/dnd.js',
    'client/components/pdf.js',
    'client/components/olio.js',
    'client/formDef.js',
    'client/view.js',
    'client/viewExtensions.js',
    'client/decorator.js',
    'client/chat.js',

    // Shared Test Framework (Phase 6)
    'client/test/testFramework.js',
    'client/test/testRegistry.js',
    'client/test/llm/llmTestSuite.js',

    // CardGame v2 — Modular (order matches index.js loading order)
    'client/view/cardGame/constants/gameConstants.js',
    'client/view/cardGame/state/storage.js',
    'client/view/cardGame/rendering/cardComponents.js',
    'client/view/cardGame/rendering/cardFace.js',
    'client/view/cardGame/rendering/overlays.js',
    'client/view/cardGame/services/characters.js',
    'client/view/cardGame/services/themes.js',
    'client/view/cardGame/services/artPipeline.js',
    'client/view/cardGame/engine/effects.js',
    'client/view/cardGame/engine/combat.js',
    'client/view/cardGame/engine/encounters.js',
    'client/view/cardGame/engine/actions.js',
    'client/view/cardGame/state/gameState.js',
    'client/view/cardGame/ai/llmBase.js',
    'client/view/cardGame/ai/director.js',
    'client/view/cardGame/ai/narrator.js',
    'client/view/cardGame/ai/chatManager.js',
    'client/view/cardGame/ai/voice.js',
    'client/view/cardGame/ui/deckList.js',
    'client/view/cardGame/ui/builder.js',
    'client/view/cardGame/ui/deckView.js',
    'client/view/cardGame/ui/phaseUI.js',
    'client/view/cardGame/ui/threatUI.js',
    'client/view/cardGame/ui/chatUI.js',
    'client/view/cardGame/ui/gameOverUI.js',
    'client/view/cardGame/ui/gameView.js',
    'client/view/cardGame/test/testMode.js',
    'client/view/cardGame/CardGameApp.js',
    'client/view/cardGame/index.js',

    // UI components
    'client/components/advGrid.js',
    'client/components/tetris.js',
    'client/components/wordGame.js',
    'client/components/breadcrumb.js',
    'client/components/asideMenu.js',
    'client/components/topMenu.js',
    'client/components/panel.js',
    'client/components/objectViewDef.js',
    'client/components/objectViewRenderers.js',
    'client/components/object_v2.js',
    'client/components/form.js',
    'client/components/navigation.js',
    'client/components/pagination.js',
    'client/components/designer.js',
    'client/components/tree.js',
    'client/components/picker.js',
    'client/components/formFieldRenderers.js',
    'client/components/membership.js',
    'client/components/tableEntry.js',
    'client/components/emoji.js',
    'client/components/games.js',
    'client/components/audio.js',
    'client/components/audioComponents.js',
    'client/components/camera.js',

    // Views
    'client/view/main.js',
    'client/view/sig.js',
    'client/view/saur.js',
    'client/view/list.js',
    'client/view/object.js',
    'client/view/navigator.js',
    'client/view/chat.js',
    'client/view/game.js',
    'client/view/hyp.js',

    // Magic8 module (order matches index.js loading order)
    'client/view/magic8/utils/FullscreenManager.js',
    'client/view/magic8/utils/DynamicImageGallery.js',
    'client/view/magic8/audio/AudioEngine.js',
    'client/view/magic8/audio/VoiceSequenceManager.js',
    'client/view/magic8/state/BiometricThemer.js',
    'client/view/magic8/video/SessionRecorder.js',
    'client/view/magic8/text/TextSequenceManager.js',
    'client/view/magic8/generation/ImageGenerationManager.js',
    'client/view/magic8/ai/SessionDirector.js',
    'client/view/magic8/components/HypnoCanvas.js',
    'client/view/magic8/components/AudioVisualizerOverlay.js',
    'client/view/magic8/components/HypnoticTextDisplay.js',
    'client/view/magic8/components/ControlPanel.js',
    'client/view/magic8/components/BiometricOverlay.js',
    'client/view/magic8/components/SessionConfigEditor.js',
    'client/view/magic8/Magic8App.js',
    'client/view/magic8/index.js',

    // Test view (Phase 6)
    'client/view/testView.js',

    // Router (must be last)
    'client/applicationRouter.js'
];

// CSS files to bundle
const cssFiles = [
    'dist/basiTail.css',
    'node_modules/file-icon-vectors/dist/file-icon-vectors.min.css',
    'node_modules/material-icons/iconfont/material-icons.css',
    'node_modules/material-symbols/index.css',
    'node_modules/pdfjs-dist/web/pdf_viewer.css',
    'node_modules/codemirror/lib/codemirror.css',
    'node_modules/codemirror/theme/neat.css',
    'node_modules/codemirror/addon/lint/lint.css',
    'styles/pageStyle.css',
    'styles/cardGame-v2.css'
];

async function build() {
    const startTime = Date.now();
    console.log('Building production bundle...\n');

    // Ensure dist directory exists
    if (!fs.existsSync('dist')) {
        fs.mkdirSync('dist');
    }

    // Concatenate and minify JS files
    console.log('Bundling JavaScript...');
    let jsContent = '';

    for (const file of jsFiles) {
        const filePath = path.join(__dirname, file);
        if (fs.existsSync(filePath)) {
            const content = fs.readFileSync(filePath, 'utf8');
            // Remove ES module syntax since we're concatenating
            let processed = content
                .replace(/^\s*export\s+\{[^}]*\};\s*$/gm, '')
                .replace(/^\s*export\s+default\s+/gm, '')
                .replace(/^\s*import\s+.*from\s+['"][^'"]+['"];\s*$/gm, '')
                .replace(/^\s*import\s+['"][^'"]+['"];\s*$/gm, '');
            // Add semicolon before each file to prevent IIFE chaining issues
            // e.g., (function(){})() (function(){})() fails without ; between them
            jsContent += `\n;/* === ${file} === */\n${processed}\n`;
        } else {
            console.warn(`  Warning: ${file} not found`);
        }
    }

    // Write concatenated JS to temp file, then minify with esbuild
    const tempJsPath = path.join(__dirname, 'dist', 'app.temp.js');
    fs.writeFileSync(tempJsPath, jsContent);

    await esbuild.build({
        entryPoints: [tempJsPath],
        outfile: 'dist/app.bundle.min.js',
        bundle: false,
        minify: true,
        sourcemap: true,
        target: ['es2020'],
        charset: 'utf8'
    });

    // Clean up temp file
    fs.unlinkSync(tempJsPath);

    const jsSize = fs.statSync('dist/app.bundle.min.js').size;
    console.log(`  Created dist/app.bundle.min.js (${(jsSize / 1024).toFixed(2)} KB)`);

    // Concatenate and minify CSS files
    console.log('\nBundling CSS...');
    let cssContent = '';

    for (const file of cssFiles) {
        const filePath = path.join(__dirname, file);
        if (fs.existsSync(filePath)) {
            let content = fs.readFileSync(filePath, 'utf8');

            // Rewrite relative URLs to absolute paths based on original file location
            // e.g., url("./material-icons.woff2") in node_modules/material-icons/iconfont/
            // becomes url("/node_modules/material-icons/iconfont/material-icons.woff2")
            const fileDir = path.dirname(file);
            content = content.replace(/url\(["']?\.\/([^"')]+)["']?\)/g, (match, relativePath) => {
                const absolutePath = '/' + fileDir.replace(/\\/g, '/') + '/' + relativePath;
                return `url("${absolutePath}")`;
            });

            cssContent += `\n/* === ${file} === */\n${content}\n`;
        } else {
            console.warn(`  Warning: ${file} not found`);
        }
    }

    // Write concatenated CSS to temp file, then minify with esbuild
    const tempCssPath = path.join(__dirname, 'dist', 'app.temp.css');
    fs.writeFileSync(tempCssPath, cssContent);

    await esbuild.build({
        entryPoints: [tempCssPath],
        outfile: 'dist/app.bundle.min.css',
        bundle: false,
        minify: true,
        sourcemap: true,
        loader: { '.css': 'css' }
    });

    // Clean up temp file
    fs.unlinkSync(tempCssPath);

    const cssSize = fs.statSync('dist/app.bundle.min.css').size;
    console.log(`  Created dist/app.bundle.min.css (${(cssSize / 1024).toFixed(2)} KB)`);

    const elapsed = Date.now() - startTime;
    console.log(`\nBuild completed in ${elapsed}ms`);
    console.log('\nProduction files:');
    console.log('  - dist/app.bundle.min.js');
    console.log('  - dist/app.bundle.min.css');
    console.log('\nUse index.prod.html to load the bundled version.');
}

build().catch((err) => {
    console.error('Build failed:', err);
    process.exit(1);
});
