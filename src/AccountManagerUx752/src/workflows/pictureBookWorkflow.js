/**
 * pictureBookWorkflow — REST client for PB2 workflow graph endpoints.
 * Phase 4 endpoints all require the olio.pb.book objectId (NOT the PB1 book group objectId).
 * Call getBookInfo(bookGroupObjectId) first to resolve the pb2BookObjectId.
 */
import { applicationPath } from '../core/config.js';

function wfBase() { return applicationPath + '/rest/olio/picture-book'; }

/**
 * Resolve a PB1 book group objectId → PB2 book info.
 * Returns {pb2BookObjectId, slug, bookName} or null (404 = no PB2 book yet).
 */
export async function getBookInfo(bookGroupObjectId) {
    let resp = await fetch(wfBase() + '/' + bookGroupObjectId + '/pb2', { credentials: 'include' });
    if (resp.status === 404) return null;
    if (!resp.ok) throw new Error('getBookInfo failed: ' + resp.status);
    return resp.json();
}

/** Full workflow graph: {bookObjectId, slug, bookName, nodeCount, nodes[], edges[]} */
export async function workflowView(pb2BookObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/workflow', { credentials: 'include' });
    if (!resp.ok) throw new Error('workflowView failed: ' + resp.status);
    return resp.json();
}

/** One node in detail: {nodeSummary, bindings[], artifacts{role:[artifactSummary]}} */
export async function nodeView(pb2BookObjectId, nodeObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/workflow/node/' + nodeObjectId,
        { credentials: 'include' });
    if (!resp.ok) throw new Error('nodeView failed: ' + resp.status);
    return resp.json();
}

/** Nodes whose recomputed status is STALE. Returns [nodeSummary, ...] */
export async function listStale(pb2BookObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/stale', { credentials: 'include' });
    if (!resp.ok) throw new Error('listStale failed: ' + resp.status);
    return resp.json();
}

/** Mark a node (and its downstream) STALE so it will be re-run on next generation. */
export async function regenerateNode(pb2BookObjectId, nodeObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/node/' + nodeObjectId + '/regenerate', {
        method: 'POST', credentials: 'include'
    });
    if (!resp.ok) throw new Error('regenerateNode failed: ' + resp.status);
    return resp.json();
}

/** Pin or unpin a node. Pinned nodes are not replaced by regeneration. */
export async function pinNode(pb2BookObjectId, nodeObjectId, pinned) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/node/' + nodeObjectId + '/pin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ pinned: !!pinned })
    });
    if (!resp.ok) throw new Error('pinNode failed: ' + resp.status);
    return resp.json();
}

/** Enrol users in the book (Writer or Admin tier). Body: {userNames:[], asAdmin?:false} */
export async function addMembers(pb2BookObjectId, userNames, asAdmin) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/members', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ userNames: userNames || [], asAdmin: !!asAdmin })
    });
    if (!resp.ok) throw new Error('addMembers failed: ' + resp.status);
    return resp.json();
}

/**
 * List all olio.pb.book records the current user can read.
 * Returns [{objectId, name, slug, bookStatus}, ...] sorted by name.
 */
export async function listPb2Books() {
    let resp = await fetch(wfBase() + '/books', { credentials: 'include' });
    if (!resp.ok) throw new Error('listPb2Books failed: ' + resp.status);
    return resp.json();
}

/**
 * Ordered scene pages for a PB2 book.
 * Returns [{objectId, sceneIndex, title, blurb, summary, dataObjectId}, ...].
 * dataObjectId is null when no composite artifact has been generated yet.
 */
export async function bookPages(pb2BookObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/pages', { credentials: 'include' });
    if (!resp.ok) throw new Error('bookPages failed: ' + resp.status);
    return resp.json();
}

/** Create the next chapter of a book. */
export async function createChapter(fromPb2BookObjectId, slug, title, copyRecordModel, copyRecordObjectIds) {
    let body = { fromBookObjectId: fromPb2BookObjectId, slug: slug };
    if (title) body.title = title;
    if (copyRecordModel) body.copyRecordModel = copyRecordModel;
    if (copyRecordObjectIds && copyRecordObjectIds.length) body.copyRecordObjectIds = copyRecordObjectIds;
    let resp = await fetch(wfBase() + '/chapter', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('createChapter failed: ' + resp.status);
    return resp.json();
}
