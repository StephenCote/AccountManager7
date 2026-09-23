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
    if (resp.status === 404) return null;
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
 * All chapter books of ONE series (N4 whole-series view), each with its series/chapter/world linkage.
 * Unlike listPb2Books() (owner-filtered), this returns a series' chapters to any ENTITLED caller (a
 * holder of the series Writer/Admin role) regardless of record owner — that is the whole point of the
 * whole-series canvas.
 *
 * Returns [{objectId, name, slug, bookStatus, chapter, seriesObjectId, worldObjectId}, ...], sorted by
 * chapter ascending server-side (the client re-sorts too). The DTO is plain JSON (no schema key), so no
 * list schema-restoration is needed.
 *
 * Distinguishes the two "nothing to show" cases the caller must render differently:
 *   - series does not exist → 404 → throws Error with .notFound === true
 *   - series exists but the caller is not entitled, or it has no chapters → [] (empty array, resolved)
 */
export async function listSeriesBooks(seriesObjectId) {
    let resp = await fetch(wfBase() + '/series/' + encodeURIComponent(seriesObjectId) + '/books',
        { credentials: 'include' });
    if (resp.status === 404) {
        let err = new Error('Series not found: ' + seriesObjectId);
        err.notFound = true;
        throw err;
    }
    if (!resp.ok) throw new Error('listSeriesBooks failed: ' + resp.status);
    return resp.json();
}

/**
 * Get-or-create the series for a slug, returning the two ids the N-series fan-out needs BEFORE it can
 * split a novel into one bounded per-chapter extraction each: { seriesObjectId, worldObjectId }. Every
 * chapter book created against this series shares worldObjectId (the ONE series world).
 *
 * POST, not GET, on purpose: this is get-or-create and the create branch performs privileged writes
 * (the series row + its shared world). Idempotent — calling it again with the same slug returns the
 * existing series, so the wizard can re-run a novel submission without minting duplicate series.
 * Body: { seriesSlug, title? }.
 */
export async function createSeries(seriesSlug, title) {
    let body = { seriesSlug: seriesSlug };
    if (title) body.title = title;
    let resp = await fetch(wfBase() + '/series', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('createSeries failed: ' + resp.status);
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

/**
 * Chapter-heading → character-offset boundary detection for a manuscript (data.data).
 * Read-only. Returns [{startOffset, endOffset, title}, ...] — the exact shape POST /chapter's
 * sourceRange accepts, so a chosen (or hand-edited) range can be handed straight back to createChapter.
 * A null title marks a leading front-matter / no-heading range; an empty array = no usable text.
 */
export async function detectBoundaries(sourceDataObjectId) {
    let resp = await fetch(wfBase() + '/chapter/detect-boundaries?sourceDataObjectId='
        + encodeURIComponent(sourceDataObjectId), { credentials: 'include' });
    if (!resp.ok) throw new Error('detectBoundaries failed: ' + resp.status);
    return resp.json();
}

/**
 * Create the next chapter of a book (or a series chapter).
 * opts (all optional): { seriesObjectId, chapter, sourceDataObjectId, sourceRange }
 *   - seriesObjectId: the olio.pb.series this chapter belongs to (shares the series' ONE world).
 *   - chapter: 1-based ordinal within the series (absent ⇒ facade uses series bookCount).
 *   - sourceDataObjectId: the data.data manuscript this chapter was cut from (N3 / Q8).
 *   - sourceRange: {startOffset, endOffset, title} — the chosen boundary span within sourceData.
 *     Sent as a plain nested object (NO inner schema key): the olio.pictureBookRequest model
 *     declares sourceRange with baseModel olio.pb.sourceRange, so the type is resolved from the
 *     top-level schema. Adding an inner "schema" here would defeat the server's ensureSchema()
 *     top-level injection.
 */
export async function createChapter(fromPb2BookObjectId, slug, title, copyRecordModel, copyRecordObjectIds, opts) {
    opts = opts || {};
    let body = { slug: slug };
    if (fromPb2BookObjectId) body.fromBookObjectId = fromPb2BookObjectId;
    if (title) body.title = title;
    if (copyRecordModel) body.copyRecordModel = copyRecordModel;
    if (copyRecordObjectIds && copyRecordObjectIds.length) body.copyRecordObjectIds = copyRecordObjectIds;
    if (opts.seriesObjectId) body.seriesObjectId = opts.seriesObjectId;
    if (opts.chapter != null) body.chapter = opts.chapter;
    if (opts.sourceDataObjectId) body.sourceDataObjectId = opts.sourceDataObjectId;
    if (opts.sourceRange) {
        let sr = opts.sourceRange;
        let range = {};
        if (sr.startOffset != null) range.startOffset = Math.round(Number(sr.startOffset));
        if (sr.endOffset != null) range.endOffset = Math.round(Number(sr.endOffset));
        if (sr.title != null && String(sr.title).trim().length) range.title = String(sr.title).trim();
        if (Object.keys(range).length) body.sourceRange = range;
    }
    let resp = await fetch(wfBase() + '/chapter', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('createChapter failed: ' + resp.status);
    return resp.json();
}

/**
 * Execute a single node synchronously via the SD backend.
 * Returns {nodeObjectId, handle, nodeStatus, artifactObjectId, artifactRevision, byteLength?, downstreamMarked, downstreamHandles}.
 * Throws with HTTP status embedded in the message on non-2xx (e.g. 501 = not yet implemented for that node type).
 */
export async function testNode(pb2BookObjectId, nodeObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/node/' + nodeObjectId + '/test', {
        method: 'POST', credentials: 'include'
    });
    if (!resp.ok) {
        let body = '';
        try { body = await resp.text(); } catch (_) {}
        throw new Error('(' + resp.status + ') ' + (body || 'testNode failed'));
    }
    return resp.json();
}

/** Save canvas position for a node. pos = {x, y, w, h} (all optional, rounded to int). */
export async function saveCanvas(pb2BookObjectId, nodeObjectId, pos = {}) {
    let body = {};
    if (pos.x != null) body.x = Math.round(pos.x);
    if (pos.y != null) body.y = Math.round(pos.y);
    if (pos.w != null) body.w = Math.round(pos.w);
    if (pos.h != null) body.h = Math.round(pos.h);
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/node/' + nodeObjectId + '/canvas', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('saveCanvas failed: ' + resp.status);
    return resp.json();
}

/** Add a binding: sourceNodeObjectId feeds into nodeObjectId with the given role. */
export async function addBinding(pb2BookObjectId, nodeObjectId, role, sourceNodeObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/node/' + nodeObjectId + '/bind', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ role, sourceNodeObjectId })
    });
    if (!resp.ok) throw new Error('addBinding failed: ' + resp.status);
    return resp.json();
}

/** Delete a binding record by its objectId. */
export async function deleteBinding(pb2BookObjectId, bindingObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/binding/' + bindingObjectId, {
        method: 'DELETE',
        credentials: 'include',
    });
    if (!resp.ok) throw new Error('deleteBinding failed: ' + resp.status);
    return resp.json();
}
