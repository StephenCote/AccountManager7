/**
 * Olio Admin feature routes (Deliverable B) — follows the Ux752 feature pattern: a single exported
 * `routes` map, each entry wrapped in layout(pageLayout(...)), lazy-loaded + tree-shaken when the
 * feature is disabled. Mirrors ../iso42001/routes.js.
 */
import { layout, pageLayout } from '../../router.js';
import { olioAdminView } from './olioAdminView.js';

function wrap(viewObj) {
    return {
        oninit: function () { if (viewObj.oninit) viewObj.oninit(); },
        view: function () { return layout(pageLayout(viewObj.view())); }
    };
}

export const routes = {
    '/admin/olio': wrap(olioAdminView)
};

export default routes;
