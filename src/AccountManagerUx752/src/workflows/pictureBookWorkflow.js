/**
 * pictureBookWorkflow — REST client for PB2 workflow graph endpoints.
 * Phase 4 endpoints all require the olio.pb.book objectId (NOT the PB1 book group objectId).
 * Call getBookInfo(bookGroupObjectId) first to resolve the pb2BookObjectId.
 */
import { applicationPath } from '../core/config.js';
import { pollJob } from './sceneExtractor.js';

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
        estimate: p.estimate && typeof p.estimate === 'object' ? p.estimate : {},
        location: p.location && typeof p.location === 'object' ? p.location : {},
        inputs: p.inputs && typeof p.inputs === 'object' ? p.inputs : {}
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

// ── V3 locations library (P3-7) ───────────────────────────────────────
// Wire shapes are PbServiceFacade's locationDto / variantDto / locationNodeDto / proposalDto, serialized
// NON_EMPTY: every list below may be absent and every nullable scalar may be missing.

function normalizeUsedBy(list) {
    return (Array.isArray(list) ? list : []).map(function (u) {
        u = u || {};
        return Object.assign({}, u, {
            sceneObjectId: u.sceneObjectId != null ? u.sceneObjectId : null,
            sceneIndex: u.sceneIndex != null ? u.sceneIndex : null,
            variantObjectId: u.variantObjectId != null ? u.variantObjectId : null
        });
    });
}

export function normalizeLocationNode(n) {
    if (!n || typeof n !== 'object') return null;
    return Object.assign({}, n, {
        handle: n.handle != null ? n.handle : null,
        storedStatus: n.storedStatus != null ? n.storedStatus : null,
        pinned: !!n.pinned,
        lastError: n.lastError != null ? n.lastError : null,
        chain: (Array.isArray(n.chain) ? n.chain : []).map(normalizeArtifact)
    });
}

export function normalizeVariant(v) {
    v = v || {};
    return Object.assign({}, v, {
        name: v.name != null ? v.name : null,
        locationObjectId: v.locationObjectId != null ? v.locationObjectId : null,
        timeOfDay: v.timeOfDay != null ? v.timeOfDay : null,
        weather: v.weather != null ? v.weather : null,
        season: v.season != null ? v.season : null,
        viewpoint: v.viewpoint != null ? v.viewpoint : null,
        extra: v.extra != null ? v.extra : null,
        approvedRenderObjectId: v.approvedRenderObjectId != null ? v.approvedRenderObjectId : null,
        usedByScenes: normalizeUsedBy(v.usedByScenes),
        node: normalizeLocationNode(v.node)
    });
}

export function normalizeLocation(l) {
    l = l || {};
    return Object.assign({}, l, {
        name: l.name != null ? l.name : null,
        description: l.description != null ? l.description : null,
        kind: l.kind != null ? l.kind : null,
        aliases: Array.isArray(l.aliases) ? l.aliases : [],
        locked: !!l.locked,
        approvedRenderObjectId: l.approvedRenderObjectId != null ? l.approvedRenderObjectId : null,
        usedByScenes: normalizeUsedBy(l.usedByScenes),
        node: normalizeLocationNode(l.node),
        variants: (Array.isArray(l.variants) ? l.variants : []).map(normalizeVariant)
    });
}

export function normalizeProposal(p) {
    p = p || {};
    return Object.assign({}, p, {
        proposedName: p.proposedName != null ? p.proposedName : (p.name != null ? p.name : null),
        description: p.description != null ? p.description : null,
        kind: p.kind != null ? p.kind : null,
        aliases: Array.isArray(p.aliases) ? p.aliases : [],
        sceneObjectIds: Array.isArray(p.sceneObjectIds) ? p.sceneObjectIds : [],
        sameAs: p.sameAs != null ? p.sameAs : null,
        proposalStatus: p.proposalStatus != null ? String(p.proposalStatus).toUpperCase() : null,
        locationObjectId: p.locationObjectId != null ? p.locationObjectId : null,
        reason: p.reason != null ? p.reason : null
    });
}

export function normalizeLocationList(r) {
    r = r || {};
    return Object.assign({}, r, {
        locations: (Array.isArray(r.locations) ? r.locations : []).map(normalizeLocation)
    });
}

export function normalizeLocationPass(r) {
    r = r || {};
    return Object.assign({}, r, {
        scenesConsidered: r.scenesConsidered != null ? r.scenesConsidered : 0,
        scenesSkipped: r.scenesSkipped != null ? r.scenesSkipped : 0,
        proposed: r.proposed != null ? r.proposed : 0,
        hardFailure: !!r.hardFailure,
        proposals: (Array.isArray(r.proposals) ? r.proposals : []).map(normalizeProposal),
        warnings: Array.isArray(r.warnings) ? r.warnings : []
    });
}

/** GET /{book}/locations → {bookObjectId, worldObjectId, locations[]} */
export async function listLocations(pb2BookObjectId) {
    return normalizeLocationList(await wfFetch('/' + pb2BookObjectId + '/locations', { method: 'GET' }, 'listLocations failed'));
}

/** GET /{book}/location/{location} → locationDto */
export async function getLocation(pb2BookObjectId, locationObjectId) {
    return normalizeLocation(await wfFetch('/' + pb2BookObjectId + '/location/' + locationObjectId, { method: 'GET' }, 'getLocation failed'));
}

/** POST /{book}/locations {name, description?, kind?, aliases?} → locationDto */
export async function createLocation(pb2BookObjectId, body) {
    return normalizeLocation(await wfFetch('/' + pb2BookObjectId + '/locations',
        { method: 'POST', body: body || {} }, 'createLocation failed'));
}

/** PATCH /{book}/location/{location} — editable keys only: description, kind, aliases (name is not editable; a rename is a merge). */
export async function updateLocation(pb2BookObjectId, locationObjectId, body) {
    return normalizeLocation(await wfFetch('/' + pb2BookObjectId + '/location/' + locationObjectId,
        { method: 'PATCH', body: body || {} }, 'updateLocation failed'));
}

/** DELETE /{book}/location/{location} → {deleted, locationObjectId, steps[]} */
export async function deleteLocation(pb2BookObjectId, locationObjectId) {
    return wfFetch('/' + pb2BookObjectId + '/location/' + locationObjectId, { method: 'DELETE' }, 'deleteLocation failed');
}

/** POST /{book}/location/{location}/variants {name?, timeOfDay?, weather?, season?, viewpoint?, extra?} → variantDto */
export async function createLocationVariant(pb2BookObjectId, locationObjectId, body) {
    return normalizeVariant(await wfFetch('/' + pb2BookObjectId + '/location/' + locationObjectId + '/variants',
        { method: 'POST', body: body || {} }, 'createLocationVariant failed'));
}

/** POST /{book}/location/{location}/lock {locked} → locationDto */
export async function lockLocation(pb2BookObjectId, locationObjectId, locked) {
    return normalizeLocation(await wfFetch('/' + pb2BookObjectId + '/location/' + locationObjectId + '/lock',
        { method: 'POST', body: { locked: !!locked } }, 'lockLocation failed'));
}

/** POST /{book}/locations/merge {duplicateObjectId, survivorObjectId} → {merged, survivor} */
export async function mergeLocations(pb2BookObjectId, duplicateObjectId, survivorObjectId) {
    let r = await wfFetch('/' + pb2BookObjectId + '/locations/merge',
        { method: 'POST', body: { duplicateObjectId: duplicateObjectId, survivorObjectId: survivorObjectId } }, 'mergeLocations failed');
    r = r || {};
    return Object.assign({}, r, { survivor: r.survivor ? normalizeLocation(r.survivor) : null });
}

/**
 * POST /{book}/location/{location}/render[?variant=<oid>][&force=true]
 * → 202 {locationObjectId, variantObjectId, nodeObjectId, nodeMaterialized, jobId, runObjectId, runStatus, planned[], …}
 * (facade ensureLocationNode keys + PbRenderJobUtil.submit keys). 409 when the location is locked, the node is
 * pinned ("Node … is pinned. Unpin it first …") or a render is in flight; 429 at the per-principal job cap.
 */
export async function renderLocation(pb2BookObjectId, locationObjectId, variantObjectId, force) {
    let qs = [];
    if (variantObjectId) qs.push('variant=' + encodeURIComponent(variantObjectId));
    if (force) qs.push('force=true');
    let r = await wfFetch('/' + pb2BookObjectId + '/location/' + locationObjectId + '/render' + (qs.length ? '?' + qs.join('&') : ''),
        { method: 'POST' }, 'renderLocation failed');
    r = r || {};
    return Object.assign({}, r, {
        jobId: r.jobId != null ? r.jobId : null,
        runObjectId: r.runObjectId != null ? r.runObjectId : null,
        nodeObjectId: r.nodeObjectId != null ? r.nodeObjectId : null,
        variantObjectId: r.variantObjectId != null ? r.variantObjectId : null,
        planned: Array.isArray(r.planned) ? r.planned : []
    });
}

/** Pure: the location render route's 409 for a pinned node (distinct from the planner's "(N reused, M pinned)" shape). */
export function isPinnedNodeRefusal(err) {
    if (!err || err.status !== 409) return false;
    return /is pinned/i.test(String(err.message || '') + ' ' + String(err.body || ''));
}

/** The 202 submit body shared by the location pass and the compose job: {bookObjectId, kind, submitted, reused, jobId, jobStatus}. */
export function normalizeJobSubmit(r) {
    r = r || {};
    return Object.assign({}, r, {
        jobId: r.jobId != null ? r.jobId : null,
        jobStatus: r.jobStatus != null ? r.jobStatus : null,
        submitted: !!r.submitted,
        reused: !!r.reused,
        kind: r.kind || null
    });
}

/**
 * POST /{book}/locations/propose {chatConfigName?} → 202 submit DTO {bookObjectId, kind:"pb.proposeLocations",
 * submitted, reused, jobId, jobStatus}. The pass runs as a background job: collect the locationPassDto from
 * GET /rest/job/{jobId} (awaitLocationPass / locationPassFromJob). 409 while another job holds the book,
 * 429 at the principal's job cap, 404 for an unknown chatConfigName.
 */
export async function proposeLocations(pb2BookObjectId, chatConfigName) {
    let body = {};
    if (chatConfigName) body.chatConfigName = chatConfigName;
    return normalizeJobSubmit(await wfFetch('/' + pb2BookObjectId + '/locations/propose',
        { method: 'POST', body: body }, 'proposeLocations failed'));
}

/**
 * Pure: the terminal /rest/job payload of a pb.proposeLocations job → locationPassDto (+ cancelled, jobId).
 * Throws the job's `error` when the job itself failed, so callers toast it like a rejected submit.
 */
export function locationPassFromJob(job) {
    job = job || {};
    if (job.error) throw new Error(job.error);
    let result = job.result && typeof job.result === 'object' ? job.result : null;
    return Object.assign(normalizeLocationPass(result), {
        cancelled: !!(job.cancelled || (result && result.cancelled)),
        jobId: job.jobId != null ? job.jobId : null
    });
}

/** Poll a pb.proposeLocations job to its end (pollJob opts: intervalMs, onProgress, signal, deadlineMs) → locationPassDto. */
export async function awaitLocationPass(jobId, opts) {
    let job = await pollJob(jobId, opts);
    return Object.assign(locationPassFromJob(job), { jobId: jobId });
}

function normalizeComposeSubmit(r) {
    r = r || {};
    return Object.assign(normalizeJobSubmit(r), {
        quickStart: !!r.quickStart,
        force: !!r.force,
        sceneCount: r.sceneCount != null ? r.sceneCount : 0,
        scenesToCompose: r.scenesToCompose != null ? r.scenesToCompose : 0,
        scenesCurrent: r.scenesCurrent != null ? r.scenesCurrent : 0,
        pagesSkipped: r.pagesSkipped != null ? r.pagesSkipped : 0,
        reason: r.reason != null ? r.reason : null
    });
}

function composeBody(opts) {
    let o = opts || {};
    let body = {};
    if (o.quickStart) body.quickStart = true;
    if (o.force) body.force = true;
    if (o.chatConfigName) body.chatConfigName = o.chatConfigName;
    return body;
}

/**
 * POST /{book}/compose {quickStart?, force?, chatConfigName?} — only the keys that are set are sent.
 * 202 {…counts, submitted:true, reused, jobId, jobStatus, kind:"pb.compose"} when a job was queued;
 * 200 {…counts, submitted:false, reason} when nothing needs composing. 409 when the book is not on the V3
 * render path or another job holds it (isComposeJobRefusal), 429 at the job cap, 404 unknown chatConfigName,
 * 500 when quickStart has no SD backend. The job result is normalizeComposeJobResult(job.result).
 */
export async function composeBook(pb2BookObjectId, opts) {
    return normalizeComposeSubmit(await wfFetch('/' + pb2BookObjectId + '/compose',
        { method: 'POST', body: composeBody(opts) }, 'composeBook failed'));
}

export function normalizeComposeResult(r) {
    r = r || {};
    return Object.assign({}, r, {
        sceneObjectId: r.sceneObjectId != null ? r.sceneObjectId : null,
        sceneIndex: r.sceneIndex != null ? r.sceneIndex : null,
        written: !!r.written,
        cancelled: !!r.cancelled,
        skippedUserEdited: !!r.skippedUserEdited,
        migratedFallback: !!r.migratedFallback,
        hardFailure: !!r.hardFailure,
        failureReason: r.failureReason != null ? r.failureReason : null,
        source: r.source != null ? r.source : null,
        castTier: r.castTier != null ? r.castTier : null,
        locationHint: r.locationHint != null ? r.locationHint : null,
        elapsedMs: r.elapsedMs != null ? r.elapsedMs : 0,
        compositionObjectId: r.compositionObjectId != null ? r.compositionObjectId : null,
        composition: r.composition != null ? r.composition : null,
        warnings: Array.isArray(r.warnings) ? r.warnings : [],
        unresolvedNames: Array.isArray(r.unresolvedNames) ? r.unresolvedNames : [],
        unrecognizedPlacements: Array.isArray(r.unrecognizedPlacements) ? r.unrecognizedPlacements : [],
        actors: Array.isArray(r.actors) ? r.actors : [],
        elements: Array.isArray(r.elements) ? r.elements : []
    });
}

/**
 * POST /{book}/scene/{scene}/compose {force?, chatConfigName?} → 200 composeResult (synchronous, one scene).
 * 409 "A compose job is running for this book; …" while the book's compose job is live (isComposeJobRefusal),
 * 409 when the book is not on the V3 render path, 404 unknown chatConfigName.
 */
export async function composeScene(pb2BookObjectId, sceneObjectId, opts) {
    let o = opts || {};
    return normalizeComposeResult(await wfFetch('/' + pb2BookObjectId + '/scene/' + sceneObjectId + '/compose',
        { method: 'POST', body: composeBody({ force: o.force, chatConfigName: o.chatConfigName }) }, 'composeScene failed'));
}

function normalizeDraftRender(d) {
    if (!d || typeof d !== 'object') return null;
    return Object.assign({}, d, {
        artifactObjectId: d.artifactObjectId != null ? d.artifactObjectId : null,
        nodeObjectId: d.nodeObjectId != null ? d.nodeObjectId : null,
        nodeStatus: d.nodeStatus != null ? d.nodeStatus : null,
        runObjectId: d.runObjectId != null ? d.runObjectId : null,
        reviewStatus: d.reviewStatus != null ? d.reviewStatus : null,
        strategy: d.strategy != null ? d.strategy : null,
        stagesPlanned: d.stagesPlanned != null ? d.stagesPlanned : 0,
        stagesRendered: d.stagesRendered != null ? d.stagesRendered : 0,
        degradations: Array.isArray(d.degradations) ? d.degradations : [],
        autoApproved: !!d.autoApproved,
        autoApproveSkipped: !!d.autoApproveSkipped,
        configNotApplied: !!d.configNotApplied
    });
}

function normalizeComposeJobScene(s) {
    s = s || {};
    return Object.assign({}, s, {
        sceneObjectId: s.sceneObjectId != null ? s.sceneObjectId : null,
        sceneIndex: s.sceneIndex != null ? s.sceneIndex : null,
        title: s.title != null ? s.title : null,
        status: s.status != null ? s.status : null,
        compositionObjectId: s.compositionObjectId != null ? s.compositionObjectId : null,
        error: s.error != null ? s.error : null,
        source: s.source != null ? s.source : null,
        castTier: s.castTier != null ? s.castTier : null,
        actorCount: s.actorCount != null ? s.actorCount : 0,
        elementCount: s.elementCount != null ? s.elementCount : 0,
        warnings: Array.isArray(s.warnings) ? s.warnings : [],
        elapsedMs: s.elapsedMs != null ? s.elapsedMs : 0,
        draftRender: normalizeDraftRender(s.draftRender),
        draftRenderError: s.draftRenderError != null ? s.draftRenderError : null,
        draftRenderMs: s.draftRenderMs != null ? s.draftRenderMs : 0
    });
}

/** Pure: the terminal pb.compose job's `result` → every count 0, every list [], every flag boolean, locationPass normalized or null. */
export function normalizeComposeJobResult(r) {
    r = r || {};
    return Object.assign({}, r, {
        bookObjectId: r.bookObjectId != null ? r.bookObjectId : null,
        slug: r.slug != null ? r.slug : null,
        quickStart: !!r.quickStart,
        force: !!r.force,
        sceneCount: r.sceneCount != null ? r.sceneCount : 0,
        visited: r.visited != null ? r.visited : 0,
        composed: r.composed != null ? r.composed : 0,
        current: r.current != null ? r.current : 0,
        skippedUserEdited: r.skippedUserEdited != null ? r.skippedUserEdited : 0,
        migrated: r.migrated != null ? r.migrated : 0,
        hardFailures: r.hardFailures != null ? r.hardFailures : 0,
        pagesSkipped: r.pagesSkipped != null ? r.pagesSkipped : 0,
        quickStartRendered: r.quickStartRendered != null ? r.quickStartRendered : 0,
        quickStartFailed: r.quickStartFailed != null ? r.quickStartFailed : 0,
        durationMs: r.durationMs != null ? r.durationMs : 0,
        lastSceneIndex: r.lastSceneIndex != null ? r.lastSceneIndex : null,
        cancelled: !!r.cancelled,
        interrupted: !!r.interrupted,
        locationPassRan: !!r.locationPassRan,
        locationPass: r.locationPass && typeof r.locationPass === 'object' ? normalizeLocationPass(r.locationPass) : null,
        note: r.note != null ? r.note : null,
        scenes: (Array.isArray(r.scenes) ? r.scenes : []).map(normalizeComposeJobScene)
    });
}

/** Pure: a 409 from compose / composeScene / propose that means another job holds the book (not the "render path is v1" 409). */
export function isComposeJobRefusal(err) {
    if (!err || err.status !== 409) return false;
    return /compose job|job .* is still running|already running for this book/i.test(String(err.message || '') + ' ' + String(err.body || ''));
}

/** GET /{book}/locations/proposed[?status=proposed|accepted|rejected] → {proposals[]} */
export async function listLocationProposals(pb2BookObjectId, status) {
    let qs = status ? '?status=' + encodeURIComponent(String(status).toLowerCase()) : '';
    let r = await wfFetch('/' + pb2BookObjectId + '/locations/proposed' + qs, { method: 'GET' }, 'listLocationProposals failed');
    r = r || {};
    return Object.assign({}, r, { proposals: (Array.isArray(r.proposals) ? r.proposals : []).map(normalizeProposal) });
}

/** POST /{book}/locations/proposed/{proposal}/accept {overwrite?} → {proposal, locationObjectId, locationCreated, scenesRepointed, scenesSkipped, aliasesFolded, warnings} */
export async function acceptLocationProposal(pb2BookObjectId, proposalObjectId, overwrite) {
    let r = await wfFetch('/' + pb2BookObjectId + '/locations/proposed/' + proposalObjectId + '/accept',
        { method: 'POST', body: { overwrite: !!overwrite } }, 'acceptLocationProposal failed');
    r = r || {};
    return Object.assign({}, r, {
        proposal: r.proposal ? normalizeProposal(r.proposal) : null,
        locationObjectId: r.locationObjectId != null ? r.locationObjectId : null,
        locationCreated: !!r.locationCreated,
        scenesRepointed: r.scenesRepointed != null ? r.scenesRepointed : 0,
        scenesSkipped: r.scenesSkipped != null ? r.scenesSkipped : 0,
        aliasesFolded: r.aliasesFolded != null ? r.aliasesFolded : 0,
        warnings: Array.isArray(r.warnings) ? r.warnings : []
    });
}

/** POST /{book}/locations/proposed/{proposal}/reject {reason?} → proposalDto */
export async function rejectLocationProposal(pb2BookObjectId, proposalObjectId, reason) {
    let body = {};
    if (reason) body.reason = reason;
    return normalizeProposal(await wfFetch('/' + pb2BookObjectId + '/locations/proposed/' + proposalObjectId + '/reject',
        { method: 'POST', body: body }, 'rejectLocationProposal failed'));
}

/**
 * PUT /{book}/scene/{scene}/location {locationObjectId?, variantObjectId?} — only non-null keys are sent; `{}` clears.
 * → {sceneObjectId, locationObjectId, locationName, variantObjectId, variantName, updated, nodeStale, pinned, nodeStatus}.
 * A pinned scene node is not a 409 here: the scene is repointed and `pinned:true` says Render will skip it.
 */
export async function setSceneLocation(pb2BookObjectId, sceneObjectId, locationObjectId, variantObjectId) {
    let body = {};
    if (locationObjectId != null) body.locationObjectId = String(locationObjectId);
    if (variantObjectId != null) body.variantObjectId = String(variantObjectId);
    let r = await wfFetch('/' + pb2BookObjectId + '/scene/' + sceneObjectId + '/location',
        { method: 'PUT', body: body }, 'setSceneLocation failed');
    r = r || {};
    return Object.assign({}, r, {
        locationObjectId: r.locationObjectId != null ? r.locationObjectId : null,
        locationName: r.locationName != null ? r.locationName : null,
        variantObjectId: r.variantObjectId != null ? r.variantObjectId : null,
        variantName: r.variantName != null ? r.variantName : null,
        updated: !!r.updated,
        nodeStale: !!r.nodeStale,
        pinned: !!r.pinned,
        nodeStatus: r.nodeStatus != null ? r.nodeStatus : null
    });
}

// ── Run progress (pure; shared by the reader and the locations library) ──

/**
 * Stage list for the run progress panel. `planned` is the 202 body's planned[] (handles, in execution
 * order; objects with a handle are tolerated); `job` is the latest /rest/job payload (phase = the
 * handle being rendered, current = nodes finished, result.nodes[] at terminal) or null before the
 * first poll. States: pending | running | done | failed | skipped | unrun.
 */
export function deriveStageList(planned, job) {
    let handles = (Array.isArray(planned) ? planned : []).map(function (p) {
        if (typeof p === 'string') return p;
        return (p && (p.handle || p.nodeObjectId)) || '';
    }).filter(Boolean);
    let result = job && job.result && typeof job.result === 'object' ? job.result : null;
    let outcomes = {};
    (result && Array.isArray(result.nodes) ? result.nodes : []).forEach(function (n) {
        if (n && n.handle) outcomes[n.handle] = n;
    });
    let current = job && typeof job.current === 'number' ? job.current : 0;
    let phase = job && job.phase ? String(job.phase) : null;
    let terminal = !!(job && job.terminal);
    return handles.map(function (h, i) {
        let o = outcomes[h];
        if (o) {
            let st = String(o.status || '').toUpperCase();
            let state = 'done';
            if (st === 'FAILED') state = 'failed';
            else if (st.indexOf('SKIPPED') === 0) state = 'skipped';
            return {
                handle: h, state: state,
                error: o.error || null,
                artifactObjectId: o.artifactObjectId || null,
                autoApproved: !!o.autoApproved,
                reviewStatus: o.reviewStatus || null
            };
        }
        if (!job) return { handle: h, state: 'pending' };
        if (terminal) return { handle: h, state: 'unrun' };
        if (phase && h === phase) return { handle: h, state: 'running' };
        if (i < current) return { handle: h, state: 'done' };
        return { handle: h, state: 'pending' };
    });
}

/** One-line outcome of a terminal render job for the toast + progress panel. */
export function runSummaryText(job) {
    if (!job) return '';
    let r = job.result && typeof job.result === 'object' ? job.result : null;
    let st = String(job.status || '').toLowerCase();
    if (job.error && !r) return 'Render failed: ' + job.error;
    if (!r) return job.cancelled ? 'Render cancelled' : ('Render ' + (st || 'finished'));
    let runStatus = String(r.runStatus || '').toUpperCase();
    let parts = [];
    parts.push((r.executedNodeCount || 0) + ' rendered');
    if (r.failedNodeCount) parts.push(r.failedNodeCount + ' failed');
    if (r.skippedNodeCount) parts.push(r.skippedNodeCount + ' skipped');
    let head = runStatus === 'INTERRUPTED' ? 'Render interrupted' : runStatus === 'CANCELLED' ? 'Render cancelled' : runStatus === 'FAILED' ? 'Render failed' : 'Render complete';
    let text = head + ': ' + parts.join(', ');
    if (r.error) text += ' — ' + r.error;
    return text;
}
