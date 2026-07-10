import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';

/**
 * PageIndex workflow — mirrors vectorize.js's Dialog -> REST -> toast shape, but the
 * build endpoint takes no chunk-type args (PageIndex picks header-vs-LLM-TOC itself), so
 * the dialog is a plain confirmation (Dialog.confirm) rather than an options form.
 * REST contract: GET /rest/pageIndex/build/{type}/{objectId} -> boolean.
 */

/** Raw build call (no dialog) — reused by the confirm-driven workflow below and by
 *  pageIndexTree.js's empty-state "Build Index" affordance / rebuild flow. */
async function buildPageIndex(type, objectId) {
    return await m.request({
        method: 'GET',
        url: am7client.base() + '/pageIndex/build/' + type + '/' + objectId,
        withCredentials: true
    });
}

/** Raw delete call (no dialog) — used for the "Rebuild index" (delete then build) flow. */
async function deletePageIndex(type, objectId) {
    return await m.request({
        method: 'DELETE',
        url: am7client.base() + '/pageIndex/' + type + '/' + objectId,
        withCredentials: true
    });
}

/**
 * Build-index workflow — opens a confirmation dialog, then calls /pageIndex/build.
 * Bound as objectPage.pageIndex (mirrors objectPage.vectorize) and also callable directly
 * (entity, inst) from pageIndexTree.js's empty-state affordance.
 * @returns {Promise<boolean>} whether the build succeeded (false if cancelled or failed).
 */
async function pageIndex(entity, inst) {
    let confirmed = await Dialog.confirm({
        title: 'Build PageIndex',
        message: 'Build a hierarchical PageIndex (structural outline + summaries) over this document\'s content? This calls the LLM for summarization and may take a while for larger documents.',
        confirmLabel: 'Build',
        confirmIcon: 'account_tree'
    });
    if (!confirmed) return false;

    page.toast('info', 'Building PageIndex — extracting structure and summarizing...', -1);
    try {
        let x = await buildPageIndex(inst.model.name, inst.api.objectId());
        page.clearToast();
        if (x) {
            page.toast('success', 'PageIndex build complete');
        } else {
            page.toast('error', 'PageIndex build failed — no content extracted');
        }
        return !!x;
    } catch (e) {
        page.clearToast();
        page.toast('error', 'PageIndex build failed: ' + (e.message || e));
        return false;
    }
}

export { pageIndex, buildPageIndex, deletePageIndex };
export default pageIndex;
