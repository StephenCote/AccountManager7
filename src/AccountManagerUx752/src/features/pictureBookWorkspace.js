/**
 * PictureBook route table (features.js `pictureBook` and `pictureBookWorkflow` both load this module):
 *
 *   /picture-book                          library                       (pictureBook.js)
 *   /picture-book/v2/:pb2BookObjectId      Read mode                     (pictureBook.js)
 *   /picture-book/:bookObjectId            PB1 legacy viewer             (pictureBook.js)
 *   /picture-book/:bookObjectId/edit[/:section[/:item]]   Edit mode      (pictureBookEdit.js)
 *
 * The pre-workspace sub-routes (/style, /locations, /review, /compose[/:scene], /cover[/:purpose], /workflow) redirect
 * into their Edit sections (aiDocs/PictureBookUxConsistencyDesign.md §3, D8) — bookmarks and old links keep working.
 * A ChapBook reaching a PictureBook route is sent to its own product (§8.2) by chapBookWorkspace / pictureBook.js.
 */
import m from 'mithril';
import { routes as pbRoutes } from './pictureBook.js';
import { pbWorkspace } from './pictureBookEdit.js';
import { legacyRedirect } from '../workflows/bookWorkspaceNav.js';

function redirect() {
    return {
        onmatch: function (args, requestedPath) {
            let to = legacyRedirect(requestedPath);
            if (to) m.route.set(to, null, { replace: true });
        },
        render: function () { return m('div', { class: 'p-8 text-sm text-gray-500' }, 'Opening…'); }
    };
}

const ws = pbWorkspace.routes('/picture-book/:bookObjectId/edit');

// A literal table (not Object.assign) so featureFlags.test.js's routePrefixes check can read every key this
// module adds; the reader / library / PB1 routes come in through the spread of pictureBook.js's own table.
export const routes = {
    ...pbRoutes,
    '/picture-book/:bookObjectId/style': redirect(),
    '/picture-book/:bookObjectId/locations': redirect(),
    '/picture-book/:bookObjectId/review': redirect(),
    '/picture-book/:bookObjectId/compose': redirect(),
    '/picture-book/:bookObjectId/compose/:sceneObjectId': redirect(),
    '/picture-book/:bookObjectId/cover': redirect(),
    '/picture-book/:bookObjectId/cover/:purpose': redirect(),
    '/picture-book/:bookObjectId/workflow': redirect(),
    '/picture-book/:bookObjectId/edit': ws['/picture-book/:bookObjectId/edit'],
    '/picture-book/:bookObjectId/edit/:section': ws['/picture-book/:bookObjectId/edit/:section'],
    '/picture-book/:bookObjectId/edit/:section/:item': ws['/picture-book/:bookObjectId/edit/:section/:item']
};

