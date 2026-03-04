// Styles
import './styles/main.css';
import './styles/pageStyle.css';

// Core modules
import { am7model } from './core/model.js';
import { am7view } from './core/view.js';
import { am7client } from './core/am7client.js';
import { page } from './core/pageClient.js';
import { Dialog } from './components/dialogCore.js';

// Wire late-bound cross-module references
// model.js needs am7view for prepareInstance()/newPrimitive()
// view.js needs page/am7client for viewQuery()/showField()
am7model._view = am7view;
am7model._page = page;
am7model._client = am7client;

// Expose Dialog for dev console testing
if (typeof window !== 'undefined') {
    window.Dialog = Dialog;
}

// Start the application
import { init } from './router.js';
init();
