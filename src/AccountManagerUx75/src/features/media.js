/**
 * media.js — Media Processing feature (ESM)
 * Registers lazy-loaded media components: PDF viewer, SD config, olio, audio, designer, emoji.
 * Lazy-loaded by features.js when 'media' feature is enabled.
 */
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';

function lazyComponent(obj, prop, loader) {
    let cached = undefined;
    Object.defineProperty(obj, prop, {
        get() {
            if (cached !== undefined) return cached;
            loader().then(v => { cached = v; });
            return null;
        },
        set(v) { cached = v; },
        configurable: true
    });
}

// PDF viewer — only needed for PDF object views
lazyComponent(page.components, 'pdf', () =>
    import('../components/pdfViewer.js').then(m => m.pdfViewerComponent));

// SD config — only needed for SD model forms
lazyComponent(page.components, 'sdConfig', () =>
    import('../components/sdConfig.js').then(m => { am7model._sd = m.am7sd; return m.am7sd; }));

// Olio character dress system — only needed for apparel/character views
lazyComponent(page.components, 'olio', () =>
    import('../components/olio.js').then(m => { am7model._olio = m.am7olio; return m.am7olio; }));

// Audio system — only needed for audio playback views
lazyComponent(page.components, 'audio', () =>
    import('../components/audio.js').then(m => m.audio));
lazyComponent(page.components, 'audioComponents', () =>
    import('../components/audioComponents.js').then(m => m.audioComponents));

// Designer (rich text / code editor) — only needed for note/text editing
lazyComponent(page.components, 'designer', () =>
    import('../components/designer.js').then(m => m.designer.component));

// Emoji picker — only needed for chat/note editing
lazyComponent(page.components, 'emoji', () =>
    import('../components/emoji.js').then(m => m.emoji));

const routes = {};

export { routes };
export default { routes };
