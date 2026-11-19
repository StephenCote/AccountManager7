/**
 * System Admin feature routes — follows the Ux752 feature pattern: a single exported `routes` map,
 * each entry wrapped in layout(pageLayout(...)), lazy-loaded + tree-shaken when the feature is disabled.
 * Mirrors ../olioAdmin/routes.js.
 */
import { layout, pageLayout } from '../../router.js';
import { systemAdminView } from './systemAdminView.js';

function wrap(viewObj) {
    return {
        oninit: function () { if (viewObj.oninit) viewObj.oninit(); },
        view: function () { return layout(pageLayout(viewObj.view())); }
    };
}

export const routes = {
    '/admin/system': wrap(systemAdminView)
};

export default routes;
