// Styles
import './styles/main.css';
import './styles/pageStyle.css';

// Core modules
import { am7model } from './core/model.js';
import { am7view } from './core/view.js';
import { am7client } from './core/am7client.js';
import { page } from './core/pageClient.js';
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

// PDF viewer component
import { pdfViewerComponent } from './components/pdfViewer.js';
page.components.pdf = pdfViewerComponent;

// Tree component
import { newTreeComponent } from './components/tree.js';
page.components.tree = newTreeComponent;

// Navigator view (registers itself on page.views.navigator)
import './views/navigator.js';

// SD config utility
import { am7sd } from './components/sdConfig.js';
page.components.sdConfig = am7sd;

// Olio character dress system
import { am7olio } from './components/olio.js';
page.components.olio = am7olio;
am7model._olio = am7olio;

// Audio system
import { audio } from './components/audio.js';
import { audioComponents } from './components/audioComponents.js';
page.components.audio = audio;
page.components.audioComponents = audioComponents;

// Wire late-bound references for formDef.js
am7model._sd = am7sd;

// Designer (rich text / code editor)
import { designer } from './components/designer.js';
page.components.designer = designer.component;

// Emoji picker
import { emoji } from './components/emoji.js';
page.components.emoji = emoji;

// Tab component
import { newTabComponent } from './components/tab.js';
page.components.tab = newTabComponent;

// Expose Dialog for dev console testing
if (typeof window !== 'undefined') {
    window.Dialog = Dialog;
}

// Start the application
import { init } from './router.js';
init();
