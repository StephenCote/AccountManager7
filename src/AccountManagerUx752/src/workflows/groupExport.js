import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';

/**
 * Gallery/Group export workflow (KI-17) — mirrors pageIndex.js's Dialog -> REST -> toast shape.
 * REST contract (GroupExportService, Service7):
 *   POST /rest/groupExport/{type}/{groupObjectId}  -> build/rebuild, returns the data.groupExport container JSON
 *   GET  /rest/groupExport/{type}/{groupObjectId}  -> status; 404 if none built yet
 *   GET  /rest/groupExport/{type}/{groupObjectId}/download -> the ZIP itself (Content-Disposition: attachment)
 */

/** Raw status check (no dialog) — returns the data.groupExport container, or null if none exists yet
 *  (404 is the expected "not built" response, not an error). */
async function checkGroupExport(type, groupObjectId) {
    try {
        return await m.request({
            method: 'GET',
            url: am7client.base() + '/groupExport/' + type + '/' + groupObjectId,
            withCredentials: true
        });
    } catch (e) {
        return null;
    }
}

/** Raw build/rebuild call (no dialog) — reused by the confirm-driven workflow below. */
async function buildGroupExport(type, groupObjectId) {
    return await m.request({
        method: 'POST',
        url: am7client.base() + '/groupExport/' + type + '/' + groupObjectId,
        withCredentials: true
    });
}

/**
 * Build-export workflow — opens a confirmation dialog, then calls POST /groupExport, toasts the result.
 * @returns {Promise<object|null>} the data.groupExport container on success, null if cancelled or failed.
 */
async function exportGroup(type, groupObjectId, groupName) {
    let confirmed = await Dialog.confirm({
        title: 'Export Group',
        message: 'Export ' + (groupName ? '"' + groupName + '"' : 'this group') + '\'s contents to a ZIP file? '
            + 'Any existing export for this group will be rebuilt.',
        confirmLabel: 'Export',
        confirmIcon: 'archive'
    });
    if (!confirmed) return null;

    page.toast('info', 'Exporting group contents...', -1);
    try {
        let container = await buildGroupExport(type, groupObjectId);
        page.clearToast();
        if (container && container.objectId) {
            let itemCount = container.itemCount || 0;
            page.toast('success', 'Export complete — ' + itemCount + ' item' + (itemCount === 1 ? '' : 's'));
            return container;
        }
        page.toast('error', 'Export failed — no exportable content found');
        return null;
    } catch (e) {
        page.clearToast();
        page.toast('error', 'Export failed: ' + (e.message || e));
        return null;
    }
}

/** Trigger a browser download of the finished archive. The server sets Content-Disposition: attachment,
 *  so a same-origin navigation (proxied cookie auth) is sufficient — no client-side blob assembly needed. */
function downloadGroupExport(type, groupObjectId) {
    let url = am7client.base() + '/groupExport/' + type + '/' + groupObjectId + '/download';
    window.open(url, '_blank');
}

export { exportGroup, buildGroupExport, checkGroupExport, downloadGroupExport };
export default exportGroup;
