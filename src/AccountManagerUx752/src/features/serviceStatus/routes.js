/**
 * Service Status routes (feature id `serviceStatus`, aiDocs/ServiceStatusDesign.md §8). Same shape as
 * ../systemAdmin/routes.js: a single exported `routes` map, each entry wrapped in layout(pageLayout(...)).
 */
import { layout, pageLayout } from '../../router.js';
import { serviceStatusView } from './serviceStatusView.js';

function wrap(viewObj) {
    return {
        oninit: function () { if (viewObj.oninit) viewObj.oninit(); },
        view: function () { return layout(pageLayout(viewObj.view())); }
    };
}

export const routes = {
    '/admin/status': wrap(serviceStatusView)
};

export default routes;
