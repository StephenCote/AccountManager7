/**
 * ChapBook route table (features.js `chapBook` loads this module) — aiDocs/PictureBookUxConsistencyDesign.md §3, §8:
 *
 *   /chap-book                                        library: the poem queue + Create, the book list   (chapBook.js)
 *   /chap-book/read/:bookObjectId                     Read mode                                         (chapBook.js)
 *   /chap-book/edit/:bookObjectId[/:section[/:item]]  Edit mode — the shared bookWorkspace, kind 'cb'
 *   /chap-book/review/:bookObjectId                   legacy: redirects (replace) to /chap-book/edit/:bookObjectId/pages
 *
 * Edit's sections (workflows/bookWorkspaceNav.js CB_SECTIONS): Book · Style · Locations · Pages · Cover & Art · Review ·
 * Render. Book / Style / Pages / Render are chapBook.js's adapters over its edit state; Locations and Review are the
 * PictureBook adapters (ctx.kind = 'cb' tells them which product); Cover & Art is pictureBookCompose's coverSection
 * when that module exports one.
 */
import m from 'mithril';
import { createBookWorkspace } from '../components/bookWorkspace.js';
import { legacyRedirect } from '../workflows/bookWorkspaceNav.js';
import {
    routes as cbRoutes,
    cbBookSection, cbStyleSection, cbPagesSection, cbRenderSection,
    cbWorkspaceHeader, cbWorkspaceEnter, cbStatusesReady
} from './chapBook.js';
import * as locationsMod from './pictureBookLocations.js';
import * as reviewMod from './pictureBookReview.js';
import * as composeMod from './pictureBookCompose.js';
import * as workflowMod from './pictureBookWorkflow.js';

function adapter(mod, name) { return mod && mod[name] ? mod[name] : null; }

/** The CB section adapters; shared ones are registered only when their module provides them. */
export function cbAdapters() {
    let a = {
        book: cbBookSection,
        style: cbStyleSection,
        pages: cbPagesSection,
        render: cbRenderSection
    };
    let lo = adapter(locationsMod, 'locationsSection');
    let co = adapter(composeMod, 'coverSection');
    let rv = adapter(reviewMod, 'reviewSection');
    let cv = adapter(workflowMod, 'canvasSection');
    if (cv) a.canvas = cv;
    if (lo) a.locations = lo;
    if (co) a.cover = co;
    if (rv) a.review = rv;
    return a;
}

export const cbWorkspace = createBookWorkspace({
    kind: 'cb',
    adapters: cbAdapters(),
    onEnter: function (ctx) {
        cbWorkspaceEnter(ctx);
        let lr = adapter(reviewMod, 'loadReviewCount');
        if (lr) Promise.resolve(lr(ctx.bookObjectId)).catch(function () {});
    },
    statusesReady: cbStatusesReady,
    header: cbWorkspaceHeader
});

/** /chap-book/review/:id → /chap-book/edit/:id/pages (replace), the D8 mapping in bookWorkspaceNav.legacyRedirect. */
function redirect() {
    return {
        onmatch: function (args, requestedPath) {
            let to = legacyRedirect(requestedPath);
            if (to) m.route.set(to, null, { replace: true });
        },
        render: function () { return m('div', { class: 'p-8 text-sm text-gray-500' }, 'Opening…'); }
    };
}

const ws = cbWorkspace.routes('/chap-book/edit/:bookObjectId');

// A literal table (not Object.assign / a spread) so featureFlags.test.js's routePrefixes check can read every key.
export const routes = {
    '/chap-book': cbRoutes['/chap-book'],
    '/chap-book/read/:bookObjectId': cbRoutes['/chap-book/read/:bookObjectId'],
    '/chap-book/review/:bookObjectId': redirect(),
    '/chap-book/edit/:bookObjectId': ws['/chap-book/edit/:bookObjectId'],
    '/chap-book/edit/:bookObjectId/:section': ws['/chap-book/edit/:bookObjectId/:section'],
    '/chap-book/edit/:bookObjectId/:section/:item': ws['/chap-book/edit/:bookObjectId/:section/:item']
};

export default routes;
