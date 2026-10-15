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

/**
 * Classify a 404 body from GET /{id}/workflow. The facade returns two DIFFERENT 404s that the
 * canvas must render differently (PbServiceFacade.requireBook / requireWorkflow):
 *   - {"error":"Book not found"}  → the id is not an olio.pb.book at all (a PB1 group oid, or
 *     nothing)                                                          → 'not-found'
 *   - {"error":"This book has no workflow yet - generate a scene first"} → the id IS a PB2 book
 *     whose graph has not been created yet (the graph is written at first render) → 'no-workflow'
 * Pure; exported for Vitest. Unknown/empty bodies fall back to 'not-found' (the pre-existing
 * behaviour), so a body-format change on the server can only lose the nicer state, never crash.
 */
export function classifyWorkflowNotFound(bodyText) {
    let msg = '';
    if (bodyText && typeof bodyText === 'object') {
        msg = bodyText.error || bodyText.message || '';
    } else if (typeof bodyText === 'string' && bodyText.length) {
        try {
            let j = JSON.parse(bodyText);
            msg = (j && (j.error || j.message)) || '';
        } catch (_) {
            msg = bodyText;
        }
    }
    msg = String(msg).toLowerCase();
    if (msg.indexOf('no workflow') >= 0) return 'no-workflow';
    return 'not-found';
}

/**
 * Full workflow graph: {bookObjectId, slug, bookName, nodeCount, nodes[], edges[]}
 * Returns null when the id is not a PB2 book (404 "Book not found"), and {noWorkflow:true} when
 * the id IS a PB2 book that has no workflow row yet (404 "no workflow yet") — see
 * classifyWorkflowNotFound. Throws on any other non-2xx.
 */
export async function workflowView(pb2BookObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/workflow', { credentials: 'include' });
    if (resp.status === 404) {
        let body = '';
        try { body = await resp.text(); } catch (_) {}
        if (classifyWorkflowNotFound(body) === 'no-workflow') {
            return { noWorkflow: true, bookObjectId: pb2BookObjectId };
        }
        return null;
    }
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

/**
 * One non-2xx pattern for every V3 helper (the testNode pattern): the thrown Error's message is
 * '(' + status + ') ' + body, where body is the server's {error} message when the body is that JSON
 * shape, else the raw text. `status` and the raw `body` ride on the Error so callers can branch on a
 * 409 (pinned / in-flight) or 429 without re-parsing the message.
 */
async function throwNon2xx(resp, fallback) {
    let raw = '';
    try { raw = await resp.text(); } catch (_) {}
    let msg = raw;
    if (raw) {
        try {
            let j = JSON.parse(raw);
            if (j && typeof j === 'object') {
                if (typeof j.error === 'string') msg = j.error;
                else if (typeof j.message === 'string') msg = j.message;
            }
        } catch (_) {}
    }
    let err = new Error('(' + resp.status + ') ' + (msg || fallback));
    err.status = resp.status;
    err.body = raw;
    throw err;
}

async function wfFetch(path, opts, fallback) {
    let o = Object.assign({ credentials: 'include' }, opts || {});
    if (o.body !== undefined && typeof o.body !== 'string') {
        o.headers = Object.assign({ 'Content-Type': 'application/json' }, o.headers || {});
        o.body = JSON.stringify(o.body);
    }
    let resp = await fetch(wfBase() + path, o);
    if (!resp.ok) await throwNon2xx(resp, fallback);
    if (resp.status === 204) return null;
    return resp.json();
}

/** Mark a node (and its downstream) STALE so it will be re-run on next generation. */
export async function regenerateNode(pb2BookObjectId, nodeObjectId) {
    return wfFetch('/' + pb2BookObjectId + '/node/' + nodeObjectId + '/regenerate',
        { method: 'POST' }, 'regenerateNode failed');
}

// ── V3 render / run / review helpers (P1-10 S1) ───────────────────────
// The server serializes with Include.NON_EMPTY, so absent lists and nulls vanish from the wire.
// Every helper below normalizes those once here so views never test for a missing array.

function normalizeSubmit(r) {
    r = r || {};
    return Object.assign({}, r, {
        jobId: r.jobId != null ? r.jobId : null,
        runObjectId: r.runObjectId != null ? r.runObjectId : null,
        runStatus: r.runStatus != null ? r.runStatus : null,
        planned: Array.isArray(r.planned) ? r.planned : [],
        plannedNodeCount: r.plannedNodeCount != null ? r.plannedNodeCount : 0,
        reusedNodeCount: r.reusedNodeCount != null ? r.reusedNodeCount : 0,
        pinnedNodeCount: r.pinnedNodeCount != null ? r.pinnedNodeCount : 0,
        scenesWithoutNode: Array.isArray(r.scenesWithoutNode) ? r.scenesWithoutNode : []
    });
}

export function normalizeRun(r) {
    r = r || {};
    return Object.assign({}, r, {
        runObjectId: r.runObjectId != null ? r.runObjectId : null,
        runStatus: r.runStatus != null ? r.runStatus : null,
        jobId: r.jobId != null ? r.jobId : null,
        error: r.error != null ? r.error : null,
        resumable: !!r.resumable,
        inFlightInThisProcess: !!r.inFlightInThisProcess,
        requestedNodeIds: Array.isArray(r.requestedNodeIds) ? r.requestedNodeIds : [],
        executedNodeCount: r.executedNodeCount != null ? r.executedNodeCount : 0,
        failedNodeCount: r.failedNodeCount != null ? r.failedNodeCount : 0,
        nodes: Array.isArray(r.nodes) ? r.nodes : []
    });
}

function normalizeArtifact(a) {
    a = a || {};
    return Object.assign({}, a, {
        reviewStatus: a.reviewStatus != null ? a.reviewStatus : null,
        selected: !!a.selected,
        dataObjectId: a.dataObjectId != null ? a.dataObjectId : null,
        nodeObjectId: a.nodeObjectId != null ? a.nodeObjectId : null
    });
}

export function normalizeReviewQueue(r) {
    r = r || {};
    return Object.assign({}, r, {
        filter: r.filter != null ? r.filter : 'all',
        autoApprove: !!r.autoApprove,
        artifactCount: r.artifactCount != null ? r.artifactCount : 0,
        pendingCount: r.pendingCount != null ? r.pendingCount : 0,
        nodes: (Array.isArray(r.nodes) ? r.nodes : []).map(function (n) {
            n = n || {};
            return Object.assign({}, n, {
                pinned: !!n.pinned,
                sceneIndex: n.sceneIndex != null ? n.sceneIndex : null,
                artifacts: (Array.isArray(n.artifacts) ? n.artifacts : []).map(normalizeArtifact)
            });
        })
    });
}

export function normalizeAffected(r) {
    r = r || {};
    return Object.assign({}, r, {
        boundNodes: r.boundNodes != null ? r.boundNodes : 0,
        drifted: !!r.drifted,
        markPersisted: !!r.markPersisted,
        driftedHandles: Array.isArray(r.driftedHandles) ? r.driftedHandles : [],
        affectedSceneIndexes: Array.isArray(r.affectedSceneIndexes) ? r.affectedSceneIndexes : [],
        staleSceneIndexes: Array.isArray(r.staleSceneIndexes) ? r.staleSceneIndexes : []
    });
}

/**
 * POST /{book}/scene/{scene}/render[?force=true] → 202 {sceneObjectId, sceneIndex, jobId, runObjectId, planned[], ...}
 * opts.force re-rolls the scene's node even when it is current (an explicit per-scene re-render); without it
 * a current node is reused and the planner answers 409 "(1 reused, 0 pinned)". A pinned node is a 409 either
 * way — force never overrides a pin (isPinnedRefusal → the unpin confirmation).
 */
export async function renderScene(pb2BookObjectId, sceneObjectId, opts) {
    let q = (opts && opts.force) ? '?force=true' : '';
    return normalizeSubmit(await wfFetch('/' + pb2BookObjectId + '/scene/' + sceneObjectId + '/render' + q,
        { method: 'POST' }, 'renderScene failed'));
}

/** POST /{book}/render-all → 202 {sceneCount, scenesWithoutNode[], jobId, runObjectId, planned[], ...} */
export async function renderAll(pb2BookObjectId) {
    return normalizeSubmit(await wfFetch('/' + pb2BookObjectId + '/render-all',
        { method: 'POST' }, 'renderAll failed'));
}

/** GET /{book}/run/{run} → runDto (runStatus, resumable, jobId when the job is still retained, nodes[]) */
export async function getRun(pb2BookObjectId, runObjectId) {
    return normalizeRun(await wfFetch('/' + pb2BookObjectId + '/run/' + runObjectId, {}, 'getRun failed'));
}

/** POST /{book}/run/{run}/resume → 202 on an INTERRUPTED run; 409 otherwise (body says why). */
export async function resumeRun(pb2BookObjectId, runObjectId) {
    return normalizeSubmit(await wfFetch('/' + pb2BookObjectId + '/run/' + runObjectId + '/resume',
        { method: 'POST' }, 'resumeRun failed'));
}

/** POST /{book}/run/{run}/cancel → {runObjectId, runStatus, cancelRequested} */
export async function cancelRun(pb2BookObjectId, runObjectId) {
    return wfFetch('/' + pb2BookObjectId + '/run/' + runObjectId + '/cancel', { method: 'POST' }, 'cancelRun failed');
}

/** PUT /{book}/artifact/{artifact}/select — make this revision the node's current artifact. */
export async function selectArtifact(pb2BookObjectId, artifactObjectId) {
    return normalizeArtifact(await wfFetch('/' + pb2BookObjectId + '/artifact/' + artifactObjectId + '/select',
        { method: 'PUT' }, 'selectArtifact failed'));
}

/** PUT /{book}/artifact/{artifact}/approve */
export async function approveArtifact(pb2BookObjectId, artifactObjectId) {
    return normalizeArtifact(await wfFetch('/' + pb2BookObjectId + '/artifact/' + artifactObjectId + '/approve',
        { method: 'PUT' }, 'approveArtifact failed'));
}

/** PUT /{book}/artifact/{artifact}/reject */
export async function rejectArtifact(pb2BookObjectId, artifactObjectId) {
    return normalizeArtifact(await wfFetch('/' + pb2BookObjectId + '/artifact/' + artifactObjectId + '/reject',
        { method: 'PUT' }, 'rejectArtifact failed'));
}

/** GET /{book}/review-queue?filter=all|auto-approved → {autoApprove, artifactCount, pendingCount, nodes[]} */
export async function getReviewQueue(pb2BookObjectId, filter) {
    let f = filter === 'auto-approved' ? 'auto-approved' : 'all';
    return normalizeReviewQueue(await wfFetch('/' + pb2BookObjectId + '/review-queue?filter=' + f, {}, 'getReviewQueue failed'));
}

/** GET /{book}/settings/auto-approve → {bookObjectId, autoApprove} */
export async function getAutoApprove(pb2BookObjectId) {
    let r = await wfFetch('/' + pb2BookObjectId + '/settings/auto-approve', {}, 'getAutoApprove failed');
    return Object.assign({}, r || {}, { autoApprove: !!(r && r.autoApprove) });
}

/** PUT /{book}/settings/auto-approve body {autoApprove: bool} (a JSON boolean; anything else is a 400). */
export async function setAutoApprove(pb2BookObjectId, autoApprove) {
    let r = await wfFetch('/' + pb2BookObjectId + '/settings/auto-approve',
        { method: 'PUT', body: { autoApprove: !!autoApprove } }, 'setAutoApprove failed');
    return Object.assign({}, r || {}, { autoApprove: !!(r && r.autoApprove) });
}

/**
 * GET /{book}/affected-by/{refModel}/{refObjectId} → {boundNodes, drifted, driftedHandles[],
 * affectedSceneIndexes[], staleSceneIndexes[], markPersisted}. A read that may persist STALE as the
 * caller (markPersisted reports whether it did) — call it once per open, never per row or per poll.
 */
export async function getAffectedBy(pb2BookObjectId, refModel, refObjectId) {
    return normalizeAffected(await wfFetch('/' + pb2BookObjectId + '/affected-by/'
        + encodeURIComponent(refModel) + '/' + encodeURIComponent(refObjectId), {}, 'getAffectedBy failed'));
}

/** GET /{book}/stale → [nodeSummary] (same route as listStale, with the uniform error pattern). */
export async function getStale(pb2BookObjectId) {
    let r = await wfFetch('/' + pb2BookObjectId + '/stale', {}, 'getStale failed');
    return Array.isArray(r) ? r : [];
}

/** POST /{book}/node/{node}/pin {pinned:false} — only ever called after the user's explicit confirmation. */
export async function unpinNode(pb2BookObjectId, nodeObjectId) {
    return wfFetch('/' + pb2BookObjectId + '/node/' + nodeObjectId + '/pin',
        { method: 'POST', body: { pinned: false } }, 'unpinNode failed');
}

/**
 * Pure: does a render-submit failure mean "every target is pinned"? The planner's 409 reads
 * "Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)". Only that shape with a
 * non-zero pinned count qualifies — an in-flight-node 409 or a 429 does not.
 */
export function isPinnedRefusal(err) {
    if (!err || err.status !== 409) return false;
    let text = String(err.message || '') + ' ' + String(err.body || '');
    let mt = /\((\d+) reused, (\d+) pinned\)/.exec(text);
    return !!(mt && parseInt(mt[2], 10) > 0);
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

// ─────────────────────────────── health check + self-heal ───────────────────────────────
// Report shape (PbHealthUtil): { scope:'book'|'org', bookObjectId?, slug?, checkedAt,
//   findings:[{code, severity:'ERROR'|'WARN'|'INFO', message, healable, refs}],
//   summary:{errors, warnings, infos, healable}, healed:[{code, refs, action}], skipped:[{code, refs, reason}] }
// GETs are read-only on the server (nothing created, nothing acted on as admin); only the POST heal
// routes change anything, and only for the codes the caller passes (absent = every healable finding).

export const HEALTH_STALE_GRAPH = 'STALE_GRAPH';
export const HEALTH_TEMPLATE_DRIFT = 'PROMPT_TEMPLATE_DRIFT';

/** Read-only health report for one PB2 book. 404 (book gone) → null. */
export async function bookHealth(pb2BookObjectId) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/health', { credentials: 'include' });
    if (resp.status === 404) return null;
    if (!resp.ok) throw new Error('bookHealth failed: ' + resp.status);
    return resp.json();
}

/** Repair one PB2 book. Body: { codes?: [] } (prompt templates are org-level; see healOrg). 403 when not entitled. */
export async function healBook(pb2BookObjectId, body) {
    let resp = await fetch(wfBase() + '/' + pb2BookObjectId + '/health/heal', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body || {})
    });
    if (!resp.ok) throw new Error('healBook failed: ' + resp.status);
    return resp.json();
}

/** Read-only health report across every book the caller can see, plus org-level checks. */
export async function orgHealth() {
    let resp = await fetch(wfBase() + '/health', { credentials: 'include' });
    if (!resp.ok) throw new Error('orgHealth failed: ' + resp.status);
    return resp.json();
}

/** Repair across the caller's books. Body: { codes?: [], overwriteTemplates?: false }. */
export async function healOrg(body) {
    let resp = await fetch(wfBase() + '/health/heal', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body || {})
    });
    if (!resp.ok) throw new Error('healOrg failed: ' + resp.status);
    return resp.json();
}

/**
 * Pure summary of a health report for rendering decisions.
 * Returns { errors, warnings, healable, hasStaleGraph, hasDrift }; a null/malformed report → all zero/false.
 */
export function summarizeHealth(report) {
    let out = { errors: 0, warnings: 0, healable: 0, hasStaleGraph: false, hasDrift: false };
    if (!report || typeof report !== 'object') return out;
    let findings = Array.isArray(report.findings) ? report.findings : [];
    let summary = report.summary && typeof report.summary === 'object' ? report.summary : null;
    if (summary) {
        out.errors = Number(summary.errors) || 0;
        out.warnings = Number(summary.warnings) || 0;
        out.healable = Number(summary.healable) || 0;
    } else {
        findings.forEach(f => {
            if (!f) return;
            let sev = String(f.severity || '').toUpperCase();
            if (sev === 'ERROR') out.errors++;
            else if (sev === 'WARN') out.warnings++;
            if (f.healable) out.healable++;
        });
    }
    out.hasStaleGraph = findings.some(f => f && f.code === HEALTH_STALE_GRAPH);
    out.hasDrift = findings.some(f => f && f.code === HEALTH_TEMPLATE_DRIFT);
    return out;
}

// ─────────────────────────────── orphan cleanup ───────────────────────────────
// scan shape (PbOrphanUtil): { scope:'own'|'org', checkedAt, categories:[{code, count, items:[{code, model, id,
//   objectId, name, path, slug, reason}]}], total, warnings }
// purge shape: { scope, purgedAt, codes, results:[item + outcome, reason, count], deleted, denied, failed
//   (integer counts), cleanupOrphansRan, warnings }
// The org-wide routes are separate paths because JAX-RS role-gates per path, not per query-param value;
// the server also re-checks AccountAdministrators membership itself.

function orphanBase(scope) {
    return wfBase() + (scope === 'org' ? '/orphans/org' : '/orphans');
}

/** Dry run. scope 'own' (default) = the caller's own leftovers; 'org' = whole organization (admins only). */
export async function listOrphans(scope) {
    let resp = await fetch(orphanBase(scope), { credentials: 'include' });
    if (!resp.ok) throw new Error('listOrphans failed: ' + resp.status);
    return resp.json();
}

/** Apply. Re-scans server-side and acts on that set. Body: { codes?: [] } (absent = every category). */
export async function purgeOrphans(scope, body) {
    let resp = await fetch(orphanBase(scope) + '/purge', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body || {})
    });
    if (!resp.ok) throw new Error('purgeOrphans failed: ' + resp.status);
    return resp.json();
}

/** Pure: total orphan count for a scan (server `total`, else summed category counts); null/malformed → 0. */
export function countOrphans(scan) {
    if (!scan || typeof scan !== 'object') return 0;
    if (typeof scan.total === 'number') return scan.total;
    if (!Array.isArray(scan.categories)) return 0;
    return scan.categories.reduce((n, c) => n + (Number(c && c.count) || 0), 0);
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

// ── V3 style, plan and illustration-model routes (P2-9) ──────────────────────

/** Style field keys PUT /{book}/style accepts (PbServiceFacade.putStyle); anything else is a 400. */
export const STYLE_FIELD_KEYS = ['sdConfig', 'description', 'exclusions', 'palette', 'taskModels', 'pageFormat', 'customWidth', 'customHeight', 'qualityPreset', 'photoreal', 'parent'];
export const STYLE_PAGE_FORMATS = ['PORTRAIT_PAGE', 'LANDSCAPE_PAGE', 'SQUARE', 'CUSTOM'];
export const STYLE_QUALITY_PRESETS = ['DRAFT', 'STANDARD', 'FINAL'];
export const STYLE_TASKS = ['portrait', 'location', 'sceneDirect', 'inpaint', 'harmonize', 'upscale'];

/** GET /{book}/style → the style DTO (own + effective + chain). */
export function getStyle(pb2BookObjectId) {
    return wfFetch('/' + pb2BookObjectId + '/style', { method: 'GET' }, 'Could not load the book style');
}

/** PUT /{book}/style with a SPARSE body (only the keys being changed; JSON null clears a field). */
export function putStyle(pb2BookObjectId, body) {
    return wfFetch('/' + pb2BookObjectId + '/style', { method: 'PUT', body: body || {} }, 'Could not save the book style');
}

/** POST /{book}/style/lock {locked} — 404 when the book has no style of its own yet. */
export function lockStyle(pb2BookObjectId, locked) {
    return wfFetch('/' + pb2BookObjectId + '/style/lock', { method: 'POST', body: { locked: !!locked } }, 'Could not change the style lock');
}

/** POST /{book}/scene/{scene}/plan {preset?, promptOverride?, raw?, model?} → the illustration plan (not persisted). */
export function planScene(pb2BookObjectId, sceneObjectId, body) {
    return wfFetch('/' + pb2BookObjectId + '/scene/' + sceneObjectId + '/plan', { method: 'POST', body: body || {} }, 'Could not plan the scene');
}

/** POST /{book}/scene/{scene}/render-plan — synchronous: plan, verify the model, render. No body. */
export function renderPlanScene(pb2BookObjectId, sceneObjectId) {
    return wfFetch('/' + pb2BookObjectId + '/scene/' + sceneObjectId + '/render-plan', { method: 'POST' }, 'Could not render the scene');
}

/** GET /{book}/artifact/{oid} → one artifact record (a renderPlan artifact carries the plan JSON in artifactText). */
export function getArtifact(pb2BookObjectId, artifactObjectId) {
    return wfFetch('/' + pb2BookObjectId + '/artifact/' + artifactObjectId, { method: 'GET' }, 'Could not load the artifact');
}

/**
 * GET /rest/olio/illustration/models?task=… → {task, inventory{fetchedAt,error,count}, deploymentDefault{model,flux2Model},
 * resolution{reason,degraded,…}, models?}. Not under the picture-book base, so it has its own fetch.
 */
export async function listModelsForTask(task) {
    let resp = await fetch(applicationPath + '/rest/olio/illustration/models?task=' + encodeURIComponent(task || ''), { credentials: 'include' });
    if (!resp.ok) await throwNon2xx(resp, 'Could not list the installed models');
    if (resp.status === 204) return null;
    return resp.json();
}

/**
 * Normalize the /style DTO so every consumer can index without null checks. `own` stays null when
 * the book has no style of its own (fresh book: effective carries only the two defaults). Pure.
 */
export function normalizeStyle(dto) {
    let d = dto && typeof dto === 'object' ? dto : {};
    let eff = d.effective && typeof d.effective === 'object' ? d.effective : {};
    let own = d.own && typeof d.own === 'object' ? d.own : null;
    return {
        bookObjectId: d.bookObjectId || null,
        own: own ? Object.assign({}, own, { fields: own.fields && typeof own.fields === 'object' ? own.fields : {} }) : null,
        effective: {
            fields: eff.fields && typeof eff.fields === 'object' ? eff.fields : {},
            taskModels: eff.taskModels && typeof eff.taskModels === 'object' ? eff.taskModels : {},
            taskModelSources: eff.taskModelSources && typeof eff.taskModelSources === 'object' ? eff.taskModelSources : {},
            sources: eff.sources && typeof eff.sources === 'object' ? eff.sources : {},
            sdConfigName: eff.sdConfigName || null,
            qualityPreset: eff.qualityPreset ? String(eff.qualityPreset).toUpperCase() : null,
            pageFormat: eff.pageFormat ? String(eff.pageFormat).toUpperCase() : null
        },
        chain: Array.isArray(d.chain) ? d.chain : [],
        lockedBy: d.lockedBy || null,
        truncatedAt: d.truncatedAt != null ? d.truncatedAt : null,
        truncatedModel: d.truncatedModel || null,
        cycleDetected: !!d.cycleDetected,
        complete: d.complete !== false
    };
}

/** Normalize a plan body (from /plan or a renderPlan artifact's artifactText) so arrays/objects are always present. Pure. */
export function normalizePlan(plan) {
    let p = plan && typeof plan === 'object' ? plan : {};
    let strategy = p.strategy && typeof p.strategy === 'object' ? p.strategy : {};
    return Object.assign({}, p, {
        canvas: p.canvas && typeof p.canvas === 'object' ? p.canvas : {},
        preset: p.preset && typeof p.preset === 'object' ? p.preset : {},
        style: p.style && typeof p.style === 'object' ? p.style : {},
        config: p.config && typeof p.config === 'object' ? p.config : {},
        strategy: Object.assign({}, strategy, { stages: Array.isArray(strategy.stages) ? strategy.stages : [] }),
        models: p.models && typeof p.models === 'object' ? p.models : {},
        compiled: p.compiled && typeof p.compiled === 'object' ? p.compiled : {},
        degradations: Array.isArray(p.degradations) ? p.degradations : [],
        configNotApplied: Array.isArray(p.configNotApplied) ? p.configNotApplied : [],
        estimate: p.estimate && typeof p.estimate === 'object' ? p.estimate : {}
    });
}

/** Parse a renderPlan artifact's artifactText into a normalized plan; null when it is not plan JSON. Pure. */
export function planFromArtifact(artifact) {
    if (!artifact || typeof artifact.artifactText !== 'string' || !artifact.artifactText.trim()) return null;
    let parsed;
    try { parsed = JSON.parse(artifact.artifactText); } catch (_) { return null; }
    if (!parsed || typeof parsed !== 'object') return null;
    return normalizePlan(parsed);
}

/** The newest artifact of a chain = highest revision (the server lists chains ascending; do not trust order). Pure. */
export function latestArtifact(chain) {
    if (!Array.isArray(chain) || !chain.length) return null;
    return chain.reduce(function (best, a) {
        if (!a) return best;
        if (!best) return a;
        return (Number(a.revision) || 0) > (Number(best.revision) || 0) ? a : best;
    }, null);
}

/**
 * The newest renderPlan artifact of a node, read in full: {artifact, plan} or null when the node has
 * no renderPlan chain. `artifacts` is the nodeView `artifacts` map ({role: [summary…]}); the text is
 * fetched through GET /{book}/artifact/{oid} (summaries never carry artifactText).
 */
export async function loadNodePlan(pb2BookObjectId, artifacts) {
    let chain = artifacts && artifacts.renderPlan;
    let latest = latestArtifact(chain);
    if (!latest || !latest.objectId) return null;
    let artifact = await getArtifact(pb2BookObjectId, latest.objectId);
    return { artifact: artifact, plan: planFromArtifact(artifact) };
}
