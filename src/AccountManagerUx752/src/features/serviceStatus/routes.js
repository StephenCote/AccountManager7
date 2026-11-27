/**
 * Service Status routes (feature id `serviceStatus`, aiDocs/ServiceStatusDesign.md §8). Same shape as
 * ../systemAdmin/routes.js: a single exported `routes` map, each entry wrapped in layout(pageLayout(...)).
 * Rendered inside the common Admin shell (../systemAdmin/adminTabs.js) as one tab of the Admin panel.
 */
import { layout, pageLayout } from '../../router.js';
import { serviceStatusView } from './serviceStatusView.js';
import { adminRoute } from '../systemAdmin/adminTabs.js';

export const routes = {
    '/admin/status': adminRoute('status', serviceStatusView, layout, pageLayout)
};

export default routes;
