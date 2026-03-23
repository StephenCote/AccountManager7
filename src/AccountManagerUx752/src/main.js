// Styles
import 'material-icons/iconfont/material-icons.css';
import 'material-symbols/outlined.css';
import 'file-icon-vectors/dist/file-icon-vectors.min.css';
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

// Eagerly load olio (dress system) — formDef.js command buttons need am7model._olio synchronously
import { am7olio } from './components/olio.js';

// Wire late-bound cross-module references
// model.js needs am7view for prepareInstance()/newPrimitive()
// view.js needs page/am7client for viewQuery()/showField()
am7model._view = am7view;
am7model._page = page;
am7model._client = am7client;
am7model._olio = am7olio;
page.components.dialog = Dialog;
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
