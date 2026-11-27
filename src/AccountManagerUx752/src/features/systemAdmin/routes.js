/**
 * Admin panel routes — wired from the CORE feature (features.js), not from a toggleable feature id, so
 * every organization's administrators get it regardless of the org's stored feature set. Same shape as
 * ../olioAdmin/routes.js: a single exported `routes` map, each entry wrapped in layout(pageLayout(...)).
 * Rendered inside the common Admin shell (../systemAdmin/adminTabs.js) as one tab of the Admin panel.
 */
import { layout, pageLayout } from '../../router.js';
import { systemAdminView } from './systemAdminView.js';
import { adminRoute } from './adminTabs.js';

export const routes = {
    '/admin/system': adminRoute('system', systemAdminView, layout, pageLayout)
};

export default routes;
