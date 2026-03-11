// Styles
import 'material-icons/iconfont/material-icons.css';
import 'material-symbols/outlined.css';
import './styles/main.css';
import './styles/pageStyle.css';

// Core modules
import { am7model } from './core/model.js';
import { am7view } from './core/view.js';
import { am7client } from './core/am7client.js';
import { page } from './core/pageClient.js';
import './core/formDef.js'; // Side-effect: registers am7model.forms.*
import { Dialog } from './components/dialogCore.js';
import { ObjectPicker } from './components/picker.js';
import { dnd } from './components/dnd.js';
import { formFieldRenderers } from './components/formFieldRenderers.js';
import { tableEntry } from './components/tableEntry.js';
import { tableListEditor } from './components/tableListEditor.js';
import { membership } from './components/membership.js';

// Wire late-bound cross-module references
// model.js needs am7view for prepareInstance()/newPrimitive()
// view.js needs page/am7client for viewQuery()/showField()
am7model._view = am7view;
am7model._page = page;
am7model._client = am7client;
page.components.picker = ObjectPicker;
page.components.dnd = dnd;
page.components.formFieldRenderers = formFieldRenderers;
page.components.tableEntry = tableEntry;
page.components.tableListEditor = tableListEditor;
page.components.membership = membership;

// Load view extensions (custom renderer support, selectObjectRenderer, prepareObjectView)
import './core/viewExtensions.js';

// Load view model definitions (portrait, image, video, audio, text, pdf, note, message, memory)
import './components/objectViewDef.js';

// Load object view renderers (portrait, image, video, audio, text, pdf, markdown, message, memory)
import './components/objectViewRenderers.js';

// Tree component
import { newTreeComponent } from './components/tree.js';
page.components.tree = newTreeComponent;

// Navigator view (registers itself on page.views.navigator)
import './views/navigator.js';

// --- Lazy-loaded optional modules (split into separate chunks) ---
// These use defineProperty so the module is only imported when first accessed.

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
    import('./components/pdfViewer.js').then(m => m.pdfViewerComponent));

// SD config — only needed for SD model forms
lazyComponent(page.components, 'sdConfig', () =>
    import('./components/sdConfig.js').then(m => { am7model._sd = m.am7sd; return m.am7sd; }));

// Olio character dress system — only needed for apparel/character views
lazyComponent(page.components, 'olio', () =>
    import('./components/olio.js').then(m => { am7model._olio = m.am7olio; return m.am7olio; }));

// Audio system — only needed for audio playback views
lazyComponent(page.components, 'audio', () =>
    import('./components/audio.js').then(m => m.audio));
lazyComponent(page.components, 'audioComponents', () =>
    import('./components/audioComponents.js').then(m => m.audioComponents));

// Designer (rich text / code editor) — only needed for note/text editing
lazyComponent(page.components, 'designer', () =>
    import('./components/designer.js').then(m => m.designer.component));

// Emoji picker — only needed for chat/note editing
lazyComponent(page.components, 'emoji', () =>
    import('./components/emoji.js').then(m => m.emoji));

// Tab component
import { newTabComponent } from './components/tab.js';
page.components.tab = newTabComponent;

// Context menu
import { contextMenu } from './components/contextMenu.js';
page.components.contextMenu = contextMenu;

// Game stream (WebSocket game action streaming)
import { gameStream } from './core/gameStream.js';
page.components.gameStream = gameStream;

// Form viewer component (carousel-based form rendering)
import { form } from './components/form.js';
page.components.form = form.component;

// Object v2 (lightweight object view using am7view + custom renderers)
import { object_v2 } from './components/object_v2.js';
page.components.object_v2 = object_v2.component;

// Expose internals for dev console and E2E testing
if (typeof window !== 'undefined') {
    window.Dialog = Dialog;
    window.__am7page = page;
    window.__am7model = am7model;
}

// Start the application
import { init } from './router.js';
init();
