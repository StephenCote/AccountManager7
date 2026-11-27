/**
 * Olio Admin feature routes (Deliverable B) — follows the Ux752 feature pattern: a single exported
 * `routes` map, each entry wrapped in layout(pageLayout(...)), lazy-loaded + tree-shaken when the
 * feature is disabled. Mirrors ../iso42001/routes.js.
 * Rendered inside the common Admin shell (../systemAdmin/adminTabs.js) as one tab of the Admin panel.
 */
import { layout, pageLayout } from '../../router.js';
import { olioAdminView } from './olioAdminView.js';
import { adminRoute } from '../systemAdmin/adminTabs.js';

export const routes = {
    '/admin/olio': adminRoute('olio', olioAdminView, layout, pageLayout)
};

export default routes;
