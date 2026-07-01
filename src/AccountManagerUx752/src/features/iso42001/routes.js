/**
 * ISO 42001 feature routes (design §9.3 / §9A.3, adapted to the Ux752 feature pattern: a single exported
 * `routes` map, each entry wrapped in layout(pageLayout(...)), lazy-loaded + tree-shaken when the feature is
 * disabled). Dashboard stays on /compliance (the existing menu target) and is also aliased to /iso42001.
 */
import { layout, pageLayout } from '../../router.js';
import { dashboardView } from './dashboard.js';
import { campaignsView } from './campaignsView.js';
import { testRunnerView } from './testRunner.js';
import { resultsView } from './resultsBrowser.js';
import { reportView } from './reportViewer.js';
import { certificationView } from './certificationView.js';

function wrap(viewObj) {
    return {
        oninit: function () { if (viewObj.oninit) viewObj.oninit(); },
        view: function () { return layout(pageLayout(viewObj.view())); }
    };
}

export const routes = {
    '/compliance': wrap(dashboardView),
    '/iso42001': wrap(dashboardView),
    '/iso42001/campaigns': wrap(campaignsView),
    '/iso42001/campaigns/:configId': wrap(campaignsView),
    '/iso42001/run': wrap(testRunnerView),
    '/iso42001/results/:runId': wrap(resultsView),
    '/iso42001/results/:runId/:resultId': wrap(resultsView),
    '/iso42001/report': wrap(reportView),
    '/iso42001/report/:reportId': wrap(reportView),
    '/iso42001/cert': wrap(certificationView),
    '/iso42001/cert/request/:requestId': wrap(certificationView),
    '/iso42001/cert/view/:certId': wrap(certificationView)
};

export default routes;
