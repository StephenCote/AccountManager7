/**
 * Admin panel routes — wired from the CORE feature (features.js), not from a toggleable feature id, so
 * every organization's administrators get it regardless of the org's stored feature set. Same shape as
 * ../olioAdmin/routes.js: a single exported `routes` map, each entry wrapped in layout(pageLayout(...)).
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
