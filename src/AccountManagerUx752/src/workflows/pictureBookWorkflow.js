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
 * P7-3: resolve WHATEVER id the wizard / the edit pencils / the PB1 viewer route were handed to the
 * olio.pb.book objectId the V3 reader route (/picture-book/v2/{oid}) needs, or null. The callers accept
 * either id the server's PB1 bridge accepts (resolveBookGroupEither), so three lookups, cheapest first:
 *   1. the id IS an olio.pb.book (list / series / reader pencils): GET /rest/model/olio.pb.book/{id};
 *   2. the id is a PB1 book group (viewer pencil, /picture-book/:groupOid): GET /{id}/pb2 → pb2BookObjectId;
 *   3. a shared chapter the by-id read cannot see for this caller but the /books DTO list still carries
 *      (the same fallback loadViewer uses for the name).
 * null means "no V3 record": a source data.data / data.note, a pure PB1 book, or an unknown id. Every
 * miss is a 404 or an empty list; a failed step falls through to the next, never throws.
 */
export async function resolvePb2BookObjectId(id) {
    if (!id || id === 'undefined') return null;
    try {
        let resp = await fetch(applicationPath + '/rest/model/olio.pb.book/' + encodeURIComponent(id), { credentials: 'include' });
        if (resp.ok) {
            let rec = null;
            try { rec = await resp.json(); } catch (_) { rec = null; }
            return (rec && rec.objectId) || id;
        }
    } catch (_) { /* fall through to the group bridge */ }
    try {
        let info = await getBookInfo(id);
        if (info && info.pb2BookObjectId) return info.pb2BookObjectId;
    } catch (_) { /* fall through to the list */ }
    try {
        let all = await listPb2Books();
        let known = (Array.isArray(all) ? all : []).find(function (b) { return b && b.objectId === id; });
        if (known) return known.objectId;
    } catch (_) { /* no V3 record reachable */ }
    return null;
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

/**
 * One artifactSummary with the NON_EMPTY omissions filled. §23 variants (P6): `attempt` is the run
 * objectId the artifact was rendered in (null for a Test-mode / pre-Phase-6 row), `variantIndex` its
 * 0..N-1 slot in that attempt, `seed` the per-variant seed (seed0 + variantIndex); `archivedAt` and
 * `bytesDeleted` are emitted by the server only when the artifact is ARCHIVED (the column is never NULL,
 * so the value is trusted only behind the status — PbServiceFacade.artifactSummary).
 * The artifact `seed` column is a `long` with DDL default 0, so TEXT / renderPlan / pre-Phase-6 rows
 * arrive as `seed: 0` (Jackson NON_EMPTY keeps numeric 0): a seed is "known" only when > 0, the same
 * falsy-seed convention the legacy result card uses (pictureBook.js `if (result.seed && …)`).
 */
export function normalizeArtifact(a) {
    a = a || {};
    return Object.assign({}, a, {
        reviewStatus: a.reviewStatus != null ? a.reviewStatus : null,
        selected: !!a.selected,
        dataObjectId: a.dataObjectId != null ? a.dataObjectId : null,
        nodeObjectId: a.nodeObjectId != null ? a.nodeObjectId : null,
        revision: a.revision != null ? Number(a.revision) : null,
        attempt: (a.attempt != null && String(a.attempt).length) ? String(a.attempt) : null,
        variantIndex: a.variantIndex != null && isFinite(Number(a.variantIndex)) ? Number(a.variantIndex) : 0,
        seed: a.seed != null && isFinite(Number(a.seed)) && Number(a.seed) > 0 ? Number(a.seed) : null,
        byteLength: a.byteLength != null && isFinite(Number(a.byteLength)) ? Number(a.byteLength) : 0,
        archivedAt: a.archivedAt != null ? String(a.archivedAt) : null,
        bytesDeleted: !!a.bytesDeleted
    });
}

/** Case-insensitive: is this artifact ARCHIVED (the only status the strip hides by default)? */
export function isArchivedArtifact(a) {
    return !!a && String(a.reviewStatus || '').toUpperCase() === 'ARCHIVED';
}

/**
 * Pure: group one role's chain into attempts for the history strip. The server lists a chain ascending
 * by revision with every attempt's variants interleaved only by revision order; the strip wants
 *   [{ attempt, label, variants: [artifact…] (ascending variantIndex), maxRevision, archivedCount }, …]
 * newest attempt FIRST (by its highest revision). An artifact without an `attempt` (Test mode, or a row
 * written before Phase 6) was one render on its own, so it forms its own single-variant group — folding
 * every untracked row into one bucket would misreport separate renders as variants of each other.
 * `archivedCount` is the number of ARCHIVED artifacts in the whole list; `active` the non-archived ones.
 * Returns { attempts, archivedCount, active } ; a null/malformed list → empty.
 */
export function groupArtifactsByAttempt(list) {
    let out = { attempts: [], archivedCount: 0, active: 0 };
    if (!Array.isArray(list) || !list.length) return out;
    let byKey = {};
    let order = [];
    list.forEach(function (raw) {
        if (!raw) return;
        let a = normalizeArtifact(raw);
        if (isArchivedArtifact(a)) out.archivedCount++; else out.active++;
        let key = a.attempt != null ? 'a:' + a.attempt : 'r:' + (a.objectId || ('rev' + a.revision));
        if (!byKey[key]) {
            byKey[key] = { attempt: a.attempt, variants: [], maxRevision: -Infinity, archivedCount: 0 };
            order.push(key);
        }
        let g = byKey[key];
        g.variants.push(a);
        if (a.revision != null && a.revision > g.maxRevision) g.maxRevision = a.revision;
        if (isArchivedArtifact(a)) g.archivedCount++;
    });
    out.attempts = order.map(function (k) {
        let g = byKey[k];
        g.variants.sort(function (x, y) {
            if (x.variantIndex !== y.variantIndex) return x.variantIndex - y.variantIndex;
            return (x.revision || 0) - (y.revision || 0);
        });
        if (!isFinite(g.maxRevision)) g.maxRevision = null;
        g.label = g.attempt != null
            ? 'Attempt ' + g.attempt.slice(0, 8) + (g.variants.length > 1 ? ' · ' + g.variants.length + ' variants' : '')
            : 'Rev ' + (g.maxRevision != null ? g.maxRevision : '?');
        return g;
    });
    // Newest first: highest revision in the group; ties keep the server's order.
    out.attempts.sort(function (x, y) { return (y.maxRevision || 0) - (x.maxRevision || 0); });
    return out;
}

/** Pure: 0 → '0 B', 1536 → '1.5 KB', … (base 1024, one decimal above bytes). Non-numbers → '0 B'. */
export function formatBytes(n) {
    n = Number(n);
    if (!isFinite(n) || n <= 0) return '0 B';
    let units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return (i === 0 ? String(Math.round(n)) : n.toFixed(1)) + ' ' + units[i];
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
 * Pure: does a render-submit failure mean "the target is pinned"? Two 409 shapes qualify: the facade's
 * own refusal of a pinned scene node, "Node scene-3-image is pinned. Unpin it first - ..." (the same text
 * regenerate / ChapBook page render give; since P7-5 the per-scene render gives it too, so a pinned
 * scene whose character drifted is refused instead of re-rendering only the portrait), and the planner's
 * "Nothing to render: every node of scene 3 is current (0 reused, 1 pinned)" with a non-zero pinned
 * count (render-all, or a pinned terminal with nothing else to do). An in-flight-node 409 or a 429 does not.
 */
export function isPinnedRefusal(err) {
    if (!err || err.status !== 409) return false;
    let text = String(err.message || '') + ' ' + String(err.body || '');
    if (/\bis pinned\b/i.test(text)) return true;
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

/**
 * P7-4: import a pre-V3 (PB1) book into V3 — POST /migrate-v1 {groupObjectId}. PB1 records are never
 * modified; a new olio.pb.book is created from the PB1 meta: the scenes' text and rendered-image
 * references, one SCENE node per scene (DONE_UNVERIFIED) and every PB1 landscape as an unapproved
 * landscape artifact (design §28 step 3). Portraits, composite renders and bindings are not carried.
 * Returns {bookObjectId, slug, scenesImported, scenesFailed, landscapesImported, locationPass, warnings}.
 * Non-2xx throws via throwNon2xx: 404 = no PB1 meta for the group, 409 = a V3 book with that slug
 * already exists (delete or rename it first), 400 = unparseable meta.
 */
export async function migrateV1Book(groupObjectId) {
    return wfFetch('/migrate-v1', { method: 'POST', body: { groupObjectId: groupObjectId } }, 'migrateV1Book failed');
}

/**
 * Pure: normalize a /migrate-v1 result for display — counts as numbers, warnings as a string array
 * (the server serializes NON_EMPTY, so an empty list and a zero count both vanish from the wire).
 */
export function normalizeMigration(r) {
    r = (r && typeof r === 'object') ? r : {};
    return {
        bookObjectId: r.bookObjectId || null,
        slug: r.slug || null,
        scenesImported: Number(r.scenesImported) || 0,
        scenesFailed: Number(r.scenesFailed) || 0,
        landscapesImported: Number(r.landscapesImported) || 0,
        warnings: (Array.isArray(r.warnings) ? r.warnings : []).map(function (w) { return String(w); })
    };
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

// ─────────────────────────── variant retention (V3 §23, P6-3b) ───────────────────────────
// Sweep shape (PbRetentionUtil.Sweep.toMap): { scope:'book'|'org', bookObjectId, slug, checkedAt | appliedAt,
//   keepAttempts, deleteBytesAfterDays, nodes, items:[{ objectId, name, nodeObjectId, nodeHandle, role, attempt,
//   variantIndex, revision, reviewStatus, selected, hasBytes, byteLength, archivedAt, action
//   (KEEP|ARCHIVE|DELETE_BYTES|NONE), reason, bytesDueAt, outcome?, outcomeReason?, bytesFreed? }],
//   total, counts:{keep, archive, deleteBytes, none}, bytesReclaimable,
//   apply adds: archived, bytesDeleted, detached, bytesFreed, refused, denied, failed ; warnings:[] }
// Item outcomes: archived | bytesDeleted | detached | kept | refused | denied | failed. `detached` = the
// artifact's data.data row is shared with another artifact (a seeded LOCATION render, a migrated PB1
// image), so only this artifact's reference was cleared; the row stays until its last referrer is swept.
// GET = dry run (query params); POST …/apply = act. K/D absent → server defaults (PbRetentionSelector);
// out-of-range/non-integer → 400 ("(400) …" from wfFetch). The org-wide routes exist (admin-only) but the
// canvas only drives the book-scoped pair.

export const RETENTION_ACTIONS = ['KEEP', 'ARCHIVE', 'DELETE_BYTES', 'NONE'];

/** Pure: a sweep with every NON_EMPTY omission filled, so views never test for a missing array/count. */
export function normalizeRetention(s) {
    s = s || {};
    let counts = s.counts || {};
    let n = function (v) { return v != null && isFinite(Number(v)) ? Number(v) : 0; };
    return Object.assign({}, s, {
        scope: s.scope || 'book',
        bookObjectId: s.bookObjectId != null ? s.bookObjectId : null,
        slug: s.slug != null ? s.slug : null,
        checkedAt: s.checkedAt != null ? s.checkedAt : null,
        appliedAt: s.appliedAt != null ? s.appliedAt : null,
        applied: s.appliedAt != null,
        keepAttempts: n(s.keepAttempts),
        deleteBytesAfterDays: n(s.deleteBytesAfterDays),
        nodes: n(s.nodes),
        total: n(s.total),
        counts: { keep: n(counts.keep), archive: n(counts.archive), deleteBytes: n(counts.deleteBytes), none: n(counts.none) },
        bytesReclaimable: n(s.bytesReclaimable),
        archived: n(s.archived),
        bytesDeleted: n(s.bytesDeleted),
        detached: n(s.detached),
        bytesFreed: n(s.bytesFreed),
        refused: n(s.refused),
        denied: n(s.denied),
        failed: n(s.failed),
        items: (Array.isArray(s.items) ? s.items : []).map(function (it) {
            it = it || {};
            return Object.assign({}, it, {
                action: String(it.action || 'NONE').toUpperCase(),
                outcome: it.outcome != null ? String(it.outcome) : null,
                outcomeReason: it.outcomeReason != null ? it.outcomeReason : null,
                reason: it.reason != null ? it.reason : null,
                selected: !!it.selected,
                hasBytes: !!it.hasBytes,
                byteLength: n(it.byteLength),
                bytesFreed: n(it.bytesFreed),
                variantIndex: n(it.variantIndex),
                revision: it.revision != null ? n(it.revision) : null,
                attempt: it.attempt != null ? String(it.attempt) : null,
                nodeHandle: it.nodeHandle != null ? it.nodeHandle : null,
                reviewStatus: it.reviewStatus != null ? String(it.reviewStatus).toUpperCase() : null
            });
        }),
        warnings: Array.isArray(s.warnings) ? s.warnings : []
    });
}

/** Pure: the items a dry run would actually change (ARCHIVE or DELETE_BYTES). */
export function retentionActionable(sweep) {
    let s = normalizeRetention(sweep);
    return s.items.filter(function (it) { return it.action === 'ARCHIVE' || it.action === 'DELETE_BYTES'; });
}

function retentionQuery(opts) {
    opts = opts || {};
    let q = [];
    if (opts.keepAttempts != null && opts.keepAttempts !== '') q.push('keepAttempts=' + encodeURIComponent(opts.keepAttempts));
    if (opts.deleteBytesAfterDays != null && opts.deleteBytesAfterDays !== '') q.push('deleteBytesAfterDays=' + encodeURIComponent(opts.deleteBytesAfterDays));
    return q.length ? '?' + q.join('&') : '';
}

function retentionBody(opts) {
    opts = opts || {};
    let b = {};
    if (opts.keepAttempts != null && opts.keepAttempts !== '') b.keepAttempts = Number(opts.keepAttempts);
    if (opts.deleteBytesAfterDays != null && opts.deleteBytesAfterDays !== '') b.deleteBytesAfterDays = Number(opts.deleteBytesAfterDays);
    return b;
}

/** Dry run: GET /{book}/retention[?keepAttempts=&deleteBytesAfterDays=]. opts values are passed as given
 *  (the server validates and 400s), absent = server defaults. Returns a normalized sweep. */
export async function scanRetention(pb2BookObjectId, opts) {
    return normalizeRetention(await wfFetch('/' + pb2BookObjectId + '/retention' + retentionQuery(opts),
        {}, 'scanRetention failed'));
}

/** Apply: POST /{book}/retention/apply with { keepAttempts?, deleteBytesAfterDays? }. The server re-scans and
 *  acts on THAT set (not the client's last dry run), so per-item `outcome` is the truth, not the earlier `action`. */
export async function applyRetention(pb2BookObjectId, opts) {
    return normalizeRetention(await wfFetch('/' + pb2BookObjectId + '/retention/apply',
        { method: 'POST', body: retentionBody(opts) }, 'applyRetention failed'));
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
        migratedFirst: !!r.migratedFirst,
        hardFailure: !!r.hardFailure,
        failureReason: r.failureReason != null ? r.failureReason : null,
        nodeStale: !!r.nodeStale,
        nodeStatus: r.nodeStatus != null ? r.nodeStatus : null,
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
        nodeStale: !!s.nodeStale,
        draftRender: normalizeDraftRender(s.draftRender),
        draftRenderError: s.draftRenderError != null ? s.draftRenderError : null,
        draftRenderMs: s.draftRenderMs != null ? s.draftRenderMs : 0
    });
}

/** Pure: the job's up-front composition-migration pass (`result.migration`) → every count 0, lists [], or null when the pass did not run. */
export function normalizeCompositionMigration(mg) {
    if (!mg || typeof mg !== 'object') return null;
    return Object.assign({}, mg, {
        scanned: mg.scanned != null ? mg.scanned : 0,
        created: mg.created != null ? mg.created : 0,
        skippedExisting: mg.skippedExisting != null ? mg.skippedExisting : 0,
        skippedUserEdited: mg.skippedUserEdited != null ? mg.skippedUserEdited : 0,
        failed: mg.failed != null ? mg.failed : 0,
        failedSceneObjectIds: Array.isArray(mg.failedSceneObjectIds) ? mg.failedSceneObjectIds : [],
        tagsPinned: mg.tagsPinned != null ? mg.tagsPinned : 0,
        tagsSkippedPinned: mg.tagsSkippedPinned != null ? mg.tagsSkippedPinned : 0,
        tagsAmbiguous: mg.tagsAmbiguous != null ? mg.tagsAmbiguous : 0,
        tagsAffected: mg.tagsAffected != null ? mg.tagsAffected : 0,
        tagsStaled: mg.tagsStaled != null ? mg.tagsStaled : 0,
        warnings: Array.isArray(mg.warnings) ? mg.warnings : [],
        error: mg.error != null ? mg.error : null
    });
}

/** Pure: the terminal pb.compose job's `result` → every count 0, every list [], every flag boolean, locationPass/migration normalized or null. */
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
        staled: r.staled != null ? r.staled : 0,
        migration: normalizeCompositionMigration(r.migration),
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

// ── Composition board + wardrobe (V3 §6, §8, §26, §27; P4-9 S1) ──────────
// The board routes (PbServiceFacade.getSceneComposition / patchSceneComposition / add|patch|deleteActor /
// add|patch|deleteElement, P4-8) answer with Include.NON_EMPTY bodies: absent lists, false booleans and
// null strings vanish from the wire. Every normalizer below restores them once so the board never tests
// for a missing array. The enum vocabularies are the server's (PbShotTypeEnumType, …) minus UNKNOWN; the
// composition models have no client modelDef entries today, so these lists are the operative ones.

export const COMPOSITION_ENUMS = {
    shotType: ['close_up', 'medium', 'full_body', 'wide', 'establishing'],
    viewpoint: ['eye_level', 'high', 'low', 'over_shoulder', 'birds_eye'],
    role: ['hero', 'supporting', 'named_background', 'extra', 'crowd'],
    facing: ['left', 'right', 'toward', 'away', 'profile'],
    referencePolicy: ['auto', 'require_reference', 'text_only'],
    elementType: ['prop', 'furniture', 'animal', 'vehicle', 'structure', 'nature', 'other'],
    importance: ['low', 'medium', 'high']
};

/**
 * Pure: the option list for one composition enum. `limit` is a model-registry `limit` array when the
 * client has a modelDef for the model (none today - am7model.getModel('olio.pb.composition') is null),
 * else the fallback vocabulary. Lower-cased, UNKNOWN hidden, order kept.
 */
export function enumOptions(limit, fallback) {
    let src = Array.isArray(limit) && limit.length ? limit : (Array.isArray(fallback) ? fallback : []);
    let out = [];
    src.forEach(function (v) {
        let s = String(v == null ? '' : v).trim().toLowerCase();
        if (!s || s === 'unknown' || out.indexOf(s) >= 0) return;
        out.push(s);
    });
    return out;
}

/** Pure: enum values compare case-insensitively (lowercase on the wire, UPPERCASE in Java); null/''/UNKNOWN are one value. */
export function sameEnum(a, b) {
    let x = String(a == null ? '' : a).trim().toLowerCase();
    let y = String(b == null ? '' : b).trim().toLowerCase();
    if (x === 'unknown') x = '';
    if (y === 'unknown') y = '';
    return x === y;
}

const ENUM_KEYS = { shotType: 1, viewpoint: 1, role: 1, facing: 1, referencePolicy: 1, elementType: 1, importance: 1 };
const NUMERIC_KEYS = { x: 1, y: 1, w: 1, h: 1, depth: 1, priority: 1, count: 1 };
// int on the model (sceneActorModel/sceneElementModel); the server's intField truncates silently, so round here.
const INT_KEYS = { depth: 1, priority: 1, count: 1 };

function normalizeEditValue(key, v) {
    if (ENUM_KEYS[key]) {
        let s = String(v == null ? '' : v).trim().toLowerCase();
        return (s === 'unknown' ? '' : s);
    }
    if (NUMERIC_KEYS[key]) {
        if (v === '' || v == null) return null;
        let n = Number(v);
        if (isNaN(n)) return null;
        return INT_KEYS[key] ? Math.round(n) : n;
    }
    if (typeof v === 'boolean') return v;
    if (v == null) return '';
    return String(v);
}

/**
 * Pure: the row-level PATCH body for one Save - only the `keys` whose edited value differs from the
 * persisted row. Enums compare case-insensitively; a blanked enum on a row that had a value is sent as
 * 'unknown' (every composition enum's no-value member; a bare '' answers 400), an unchanged UNKNOWN stays
 * untouched; numbers are sent as numbers (an emptied numeric field is skipped, never sent as null; int
 * fields are rounded); strings are sent as typed, so clearing a text field sends ''. Returns {} when
 * nothing changed.
 */
export function boardDelta(orig, edited, keys) {
    let o = orig || {};
    let e = edited || {};
    let delta = {};
    (Array.isArray(keys) ? keys : []).forEach(function (k) {
        if (!(k in e)) return;
        let before = normalizeEditValue(k, o[k]);
        let after = normalizeEditValue(k, e[k]);
        if (ENUM_KEYS[k]) {
            if (sameEnum(before, after)) return;
            delta[k] = after === '' ? 'unknown' : after;
            return;
        }
        if (NUMERIC_KEYS[k]) {
            if (after === null || before === after) return;
            delta[k] = after;
            return;
        }
        if (before === after) return;
        delta[k] = after;
    });
    return delta;
}

export function normalizeBoardComposition(c) {
    c = c || {};
    return Object.assign({}, c, {
        objectId: c.objectId != null ? c.objectId : null,
        name: c.name != null ? c.name : null,
        source: c.source != null ? String(c.source).toLowerCase() : null,
        purpose: c.purpose != null ? String(c.purpose).toLowerCase() : null,
        shotType: c.shotType != null ? String(c.shotType).toLowerCase() : null,
        viewpoint: c.viewpoint != null ? String(c.viewpoint).toLowerCase() : null,
        cameraText: c.cameraText != null ? c.cameraText : '',
        mood: c.mood != null ? c.mood : '',
        settingText: c.settingText != null ? c.settingText : '',
        action: c.action != null ? c.action : '',
        props: c.props != null ? c.props : '',
        promptOverride: c.promptOverride != null ? c.promptOverride : '',
        configOverride: c.configOverride != null ? c.configOverride : '',
        locationObjectId: c.locationObjectId != null ? c.locationObjectId : null,
        variantObjectId: c.variantObjectId != null ? c.variantObjectId : null,
        focalActorObjectId: c.focalActorObjectId != null ? c.focalActorObjectId : null,
        userEdited: !!c.userEdited,
        promptLocked: !!c.promptLocked,
        revision: c.revision != null ? c.revision : 0
    });
}

export function normalizeActorRow(a) {
    a = a || {};
    return Object.assign({}, a, {
        objectId: a.objectId != null ? a.objectId : null,
        name: a.name != null ? a.name : null,
        characterObjectId: a.characterObjectId != null ? a.characterObjectId : null,
        characterName: a.characterName != null ? a.characterName : null,
        apparelObjectId: a.apparelObjectId != null ? a.apparelObjectId : null,
        role: a.role != null ? String(a.role).toLowerCase() : null,
        referencePolicy: a.referencePolicy != null ? String(a.referencePolicy).toLowerCase() : null,
        facing: a.facing != null ? String(a.facing).toLowerCase() : null,
        priority: a.priority != null ? a.priority : 0,
        pose: a.pose != null ? a.pose : '',
        expression: a.expression != null ? a.expression : '',
        action: a.action != null ? a.action : '',
        description: a.description != null ? a.description : '',
        x: a.x != null ? a.x : 0,
        y: a.y != null ? a.y : 0,
        w: a.w != null ? a.w : 0,
        h: a.h != null ? a.h : 0,
        depth: a.depth != null ? a.depth : 0,
        count: a.count != null ? a.count : 1
    });
}

export function normalizeElementRow(e) {
    e = e || {};
    return Object.assign({}, e, {
        objectId: e.objectId != null ? e.objectId : null,
        elementType: e.elementType != null ? String(e.elementType).toLowerCase() : null,
        importance: e.importance != null ? String(e.importance).toLowerCase() : null,
        description: e.description != null ? e.description : '',
        itemObjectId: e.itemObjectId != null ? e.itemObjectId : null,
        x: e.x != null ? e.x : 0,
        y: e.y != null ? e.y : 0,
        w: e.w != null ? e.w : 0,
        h: e.h != null ? e.h : 0,
        depth: e.depth != null ? e.depth : 0
    });
}

/** Pure: GET /{book}/scene/{scene}/composition body → {bookObjectId, sceneObjectId, compositionObjectId, composition, actors[], elements[]}. */
export function normalizeBoard(r) {
    r = r || {};
    return Object.assign({}, r, {
        bookObjectId: r.bookObjectId != null ? r.bookObjectId : null,
        sceneObjectId: r.sceneObjectId != null ? r.sceneObjectId : null,
        compositionObjectId: r.compositionObjectId != null ? r.compositionObjectId : null,
        composition: normalizeBoardComposition(r.composition),
        actors: (Array.isArray(r.actors) ? r.actors : []).map(normalizeActorRow),
        elements: (Array.isArray(r.elements) ? r.elements : []).map(normalizeElementRow)
    });
}

/** Pure: an edit route's body → every flag boolean, `actor`/`element` rows normalized when present. */
export function normalizeEditResult(r) {
    r = r || {};
    return Object.assign({}, r, {
        bookObjectId: r.bookObjectId != null ? r.bookObjectId : null,
        sceneObjectId: r.sceneObjectId != null ? r.sceneObjectId : null,
        compositionObjectId: r.compositionObjectId != null ? r.compositionObjectId : null,
        revision: r.revision != null ? r.revision : 0,
        nodeStale: !!r.nodeStale,
        nodeStatus: r.nodeStatus != null ? r.nodeStatus : null,
        updated: !!r.updated,
        created: !!r.created,
        deleted: !!r.deleted,
        focalActorCleared: !!r.focalActorCleared,
        actorObjectId: r.actorObjectId != null ? r.actorObjectId : null,
        elementObjectId: r.elementObjectId != null ? r.elementObjectId : null,
        actor: r.actor && typeof r.actor === 'object' ? normalizeActorRow(r.actor) : null,
        element: r.element && typeof r.element === 'object' ? normalizeElementRow(r.element) : null
    });
}

/** Pure: a board-route 404 that means "no composition yet" (compose it / set the page prompt), not an unknown scene. */
export function isNoCompositionYet(err) {
    if (!err || err.status !== 404) return false;
    return /has no composition/i.test(String(err.message || '') + ' ' + String(err.body || ''));
}

/**
 * GET /{book}/scene/{scene}/composition → normalizeBoard. 404 when the scene has no composition yet
 * (isNoCompositionYet - the board shows an empty state with Compose) or the scene is not of the book.
 */
export async function getSceneComposition(pb2BookObjectId, sceneObjectId) {
    return normalizeBoard(await wfFetch('/' + pb2BookObjectId + '/scene/' + sceneObjectId + '/composition',
        { method: 'GET' }, 'getSceneComposition failed'));
}

/**
 * PATCH /{book}/scene/{scene}/composition {settingText?, action?, mood?, shotType?, viewpoint?, cameraText?, props?,
 * configOverride?, promptOverride?, promptLocked?, focalActorObjectId?} - a row-level delta (boardDelta). 400 for a
 * refused field (book, scene, purpose, source, revision, userEdited, name, location, locationVariant, focalActor),
 * a blank enum, a non-sparse configOverride or a focalActorObjectId that is not this composition's actor.
 * → editResult {…, updated:true, revision, nodeStale, nodeStatus}.
 */
export async function patchComposition(pb2BookObjectId, sceneObjectId, delta) {
    return normalizeEditResult(await wfFetch('/' + pb2BookObjectId + '/scene/' + sceneObjectId + '/composition',
        { method: 'PATCH', body: delta || {} }, 'patchComposition failed'));
}

/**
 * POST /{book}/composition/{comp}/actor {characterObjectId | description, role?, priority?, pose?, expression?, action?,
 * facing?, x?, y?, w?, h?, depth?, count?, referencePolicy?} → editResult + {actorObjectId, actor, created:true}.
 * 409 on a ChapBook page ("Actors are not enabled on ChapBook pages"), 404 character not of the book.
 */
export async function addActor(pb2BookObjectId, compositionObjectId, body) {
    return normalizeEditResult(await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/actor',
        { method: 'POST', body: body || {} }, 'addActor failed'));
}

/** PATCH /{book}/composition/{comp}/actor/{actor} {role?, priority?, pose?, expression?, action?, facing?, x?, y?, w?, h?, depth?, description?, count?, referencePolicy?} → editResult + {actorObjectId, actor, updated:true}. x/y/w/h outside 0..1 is a 400. */
export async function patchActor(pb2BookObjectId, compositionObjectId, actorObjectId, delta) {
    return normalizeEditResult(await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/actor/' + actorObjectId,
        { method: 'PATCH', body: delta || {} }, 'patchActor failed'));
}

/** DELETE /{book}/composition/{comp}/actor/{actor} → editResult + {actorObjectId, deleted:true, focalActorCleared}. */
export async function deleteActor(pb2BookObjectId, compositionObjectId, actorObjectId) {
    return normalizeEditResult(await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/actor/' + actorObjectId,
        { method: 'DELETE' }, 'deleteActor failed'));
}

/** POST /{book}/composition/{comp}/element {elementType, description, x?, y?, w?, h?, depth?, importance?} → editResult + {elementObjectId, element, created:true}. Allowed on every purpose. */
export async function addElement(pb2BookObjectId, compositionObjectId, body) {
    return normalizeEditResult(await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/element',
        { method: 'POST', body: body || {} }, 'addElement failed'));
}

/** PATCH /{book}/composition/{comp}/element/{el} {elementType?, description?, x?, y?, w?, h?, depth?, importance?} → editResult + {elementObjectId, element, updated:true}. */
export async function patchElement(pb2BookObjectId, compositionObjectId, elementObjectId, delta) {
    return normalizeEditResult(await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/element/' + elementObjectId,
        { method: 'PATCH', body: delta || {} }, 'patchElement failed'));
}

/** DELETE /{book}/composition/{comp}/element/{el} → editResult + {elementObjectId, deleted:true}. */
export async function deleteElement(pb2BookObjectId, compositionObjectId, elementObjectId) {
    return normalizeEditResult(await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/element/' + elementObjectId,
        { method: 'DELETE' }, 'deleteElement failed'));
}

/**
 * POST /{book}/composition/{comp}/element/{el}/promote → {elementObjectId, promoted, created, reused, itemObjectId, itemName,
 * elementText, staled, warnings[]}. 409 when the composition's scene has no location or the location has no POI store.
 */
export async function promoteElement(pb2BookObjectId, compositionObjectId, elementObjectId) {
    let r = await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/element/' + elementObjectId + '/promote',
        { method: 'POST' }, 'promoteElement failed');
    r = r || {};
    return Object.assign({}, r, {
        elementObjectId: r.elementObjectId != null ? r.elementObjectId : null,
        promoted: !!r.promoted,
        created: !!r.created,
        reused: !!r.reused,
        itemObjectId: r.itemObjectId != null ? r.itemObjectId : null,
        itemName: r.itemName != null ? r.itemName : null,
        elementText: r.elementText != null ? r.elementText : null,
        staled: r.staled != null ? r.staled : 0,
        warnings: Array.isArray(r.warnings) ? r.warnings : []
    });
}

/**
 * PUT /{book}/composition/{comp}/actor/{actor}/apparel {apparelObjectId} pins; `{}` (apparelObjectId null) unpins.
 * 400 when the apparel is not in the actor's character's wardrobe. → {actorObjectId, apparelObjectId, pinned, staled,
 * affectedSceneIndexes[]}. A pin does not mark the composition user-edited.
 */
export async function pinActorApparel(pb2BookObjectId, compositionObjectId, actorObjectId, apparelObjectId) {
    let body = {};
    if (apparelObjectId != null && String(apparelObjectId).trim()) body.apparelObjectId = String(apparelObjectId).trim();
    let r = await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/actor/' + actorObjectId + '/apparel',
        { method: 'PUT', body: body }, 'pinActorApparel failed');
    r = r || {};
    return Object.assign({}, r, {
        actorObjectId: r.actorObjectId != null ? r.actorObjectId : null,
        apparelObjectId: r.apparelObjectId != null ? r.apparelObjectId : null,
        pinned: !!r.pinned,
        staled: r.staled != null ? r.staled : 0,
        affectedSceneIndexes: Array.isArray(r.affectedSceneIndexes) ? r.affectedSceneIndexes : []
    });
}

export function normalizeResolvedOutfit(o) {
    o = o || {};
    return Object.assign({}, o, {
        actorObjectId: o.actorObjectId != null ? o.actorObjectId : null,
        actorName: o.actorName != null ? o.actorName : null,
        characterObjectId: o.characterObjectId != null ? o.characterObjectId : null,
        apparelObjectId: o.apparelObjectId != null ? o.apparelObjectId : null,
        apparelName: o.apparelName != null ? o.apparelName : null,
        apparelDescription: o.apparelDescription != null ? o.apparelDescription : null,
        source: o.source != null ? String(o.source).toLowerCase() : 'none',
        fromSceneIndex: o.fromSceneIndex != null ? o.fromSceneIndex : 0,
        label: o.label != null ? o.label : '',
        warning: o.warning != null ? o.warning : null
    });
}

/** GET /{book}/composition/{comp}/outfits → {compositionObjectId, sceneObjectId, actors[resolvedOutfit]} (source: pinned|carried|default|none). */
export async function getCompositionOutfits(pb2BookObjectId, compositionObjectId) {
    let r = await wfFetch('/' + pb2BookObjectId + '/composition/' + compositionObjectId + '/outfits',
        { method: 'GET' }, 'getCompositionOutfits failed');
    r = r || {};
    return Object.assign({}, r, {
        compositionObjectId: r.compositionObjectId != null ? r.compositionObjectId : null,
        sceneObjectId: r.sceneObjectId != null ? r.sceneObjectId : null,
        actors: (Array.isArray(r.actors) ? r.actors : []).map(normalizeResolvedOutfit)
    });
}

/**
 * Pure: the outfit chip text for one actor row from the resolved outfits. The server's `label` is the
 * board's text ("(pinned)", "(from scene N)", "(default)", "(none)"); the apparel name precedes it when
 * one resolved. Absent resolution → "(none)".
 */
export function outfitChipText(outfit) {
    if (!outfit) return '(none)';
    let name = outfit.apparelName ? String(outfit.apparelName) : '';
    let label = outfit.label ? String(outfit.label) : '';
    if (!label) {
        let src = String(outfit.source || 'none').toLowerCase();
        if (src === 'pinned') label = '(pinned)';
        else if (src === 'carried') label = '(from scene ' + (outfit.fromSceneIndex != null ? outfit.fromSceneIndex : '?') + ')';
        else if (src === 'default') label = '(default)';
        else label = '(none)';
    }
    return (name ? name + ' ' : '') + label;
}

export function normalizeWardrobeEntry(e) {
    e = e || {};
    return Object.assign({}, e, {
        objectId: e.objectId != null ? e.objectId : null,
        name: e.name != null ? e.name : null,
        description: e.description != null ? e.description : '',
        isDefault: !!e.isDefault,
        sceneIndex: e.sceneIndex != null ? e.sceneIndex : null,
        wearables: Array.isArray(e.wearables) ? e.wearables : [],
        gallery: Array.isArray(e.gallery) ? e.gallery : [],
        referenceObjectId: e.referenceObjectId != null ? e.referenceObjectId : null
    });
}

/** Pure: GET wardrobe body → {characterObjectId, characterName, storeObjectId, defaultApparelObjectId, entries[], entryCount, warnings[]}. */
export function normalizeWardrobe(r) {
    r = r || {};
    let entries = (Array.isArray(r.entries) ? r.entries : []).map(normalizeWardrobeEntry);
    return Object.assign({}, r, {
        bookObjectId: r.bookObjectId != null ? r.bookObjectId : null,
        characterObjectId: r.characterObjectId != null ? r.characterObjectId : null,
        characterName: r.characterName != null ? r.characterName : null,
        storeObjectId: r.storeObjectId != null ? r.storeObjectId : null,
        defaultApparelObjectId: r.defaultApparelObjectId != null ? r.defaultApparelObjectId : null,
        entries: entries,
        entryCount: r.entryCount != null ? r.entryCount : entries.length,
        warnings: Array.isArray(r.warnings) ? r.warnings : []
    });
}

/** GET /{book}/character/{char}/wardrobe → normalizeWardrobe. 404 when the character is not of the book. */
export async function getWardrobe(pb2BookObjectId, characterObjectId) {
    return normalizeWardrobe(await wfFetch('/' + pb2BookObjectId + '/character/' + characterObjectId + '/wardrobe',
        { method: 'GET' }, 'getWardrobe failed'));
}

/**
 * POST /{book}/character/{char}/wardrobe {description, chatConfigName?} → 201 {apparelObjectId, name, description, reused:false,
 * items[], unmatched[], wearables, colorsResolved, elapsedMs}; 200 reused:true when the description is already in the wardrobe.
 * 400 blank description, 409 wardrobe full / no world context, 502 the LLM produced nothing usable.
 */
export async function addWardrobeEntry(pb2BookObjectId, characterObjectId, description, chatConfigName) {
    let body = { description: description };
    if (chatConfigName) body.chatConfigName = chatConfigName;
    let r = await wfFetch('/' + pb2BookObjectId + '/character/' + characterObjectId + '/wardrobe',
        { method: 'POST', body: body }, 'addWardrobeEntry failed');
    r = r || {};
    return Object.assign({}, r, {
        apparelObjectId: r.apparelObjectId != null ? r.apparelObjectId : null,
        name: r.name != null ? r.name : null,
        description: r.description != null ? r.description : '',
        reused: !!r.reused,
        items: Array.isArray(r.items) ? r.items : [],
        unmatched: Array.isArray(r.unmatched) ? r.unmatched : [],
        wearables: r.wearables != null ? r.wearables : 0,
        colorsResolved: r.colorsResolved != null ? r.colorsResolved : 0,
        elapsedMs: r.elapsedMs != null ? r.elapsedMs : 0
    });
}

/** PUT /{book}/character/{char}/wardrobe/{apparel}/default → {apparelObjectId, previousApparelObjectIds[], changed, scenesScanned, staled, warnings[]}. */
export async function setDefaultApparel(pb2BookObjectId, characterObjectId, apparelObjectId) {
    let r = await wfFetch('/' + pb2BookObjectId + '/character/' + characterObjectId + '/wardrobe/' + apparelObjectId + '/default',
        { method: 'PUT' }, 'setDefaultApparel failed');
    r = r || {};
    return Object.assign({}, r, {
        apparelObjectId: r.apparelObjectId != null ? r.apparelObjectId : null,
        previousApparelObjectIds: Array.isArray(r.previousApparelObjectIds) ? r.previousApparelObjectIds : [],
        changed: !!r.changed,
        scenesScanned: r.scenesScanned != null ? r.scenesScanned : 0,
        staled: r.staled != null ? r.staled : 0,
        warnings: Array.isArray(r.warnings) ? r.warnings : []
    });
}

/**
 * POST /{book}/character/{char}/wardrobe/{apparel}/reference → 202 job submit {jobId, jobStatus, kind, reused}; poll
 * GET /rest/job/{jobId}. The render lands in apparel.gallery (re-read the wardrobe). 409 while a job runs for the
 * apparel, 500 no SD backend. There is no upload route: a reference is rendered, never uploaded.
 */
export async function renderApparelReference(pb2BookObjectId, characterObjectId, apparelObjectId) {
    return normalizeJobSubmit(await wfFetch('/' + pb2BookObjectId + '/character/' + characterObjectId + '/wardrobe/' + apparelObjectId + '/reference',
        { method: 'POST' }, 'renderApparelReference failed'));
}

/** POST /{book}/wardrobe/migrate-scene-tags → {scanned, pinned, skippedPinned, skippedNoActor, skippedNoScene, skippedNoComposition, ambiguous, warnings[]}. Idempotent. */
export async function migrateSceneTags(pb2BookObjectId) {
    let r = await wfFetch('/' + pb2BookObjectId + '/wardrobe/migrate-scene-tags', { method: 'POST' }, 'migrateSceneTags failed');
    r = r || {};
    return Object.assign({}, r, {
        scanned: r.scanned != null ? r.scanned : 0,
        pinned: r.pinned != null ? r.pinned : 0,
        skippedPinned: r.skippedPinned != null ? r.skippedPinned : 0,
        skippedNoActor: r.skippedNoActor != null ? r.skippedNoActor : 0,
        skippedNoScene: r.skippedNoScene != null ? r.skippedNoScene : 0,
        skippedNoComposition: r.skippedNoComposition != null ? r.skippedNoComposition : 0,
        ambiguous: r.ambiguous != null ? r.ambiguous : 0,
        warnings: Array.isArray(r.warnings) ? r.warnings : []
    });
}

/**
 * Pure: the degradation summary a scene card / the board shows from a normalized render plan (normalizePlan):
 * {count, codes[], truncatedDropped[]} where truncatedDropped is the message of every PROMPT_TRUNCATED entry
 * ("<group> dropped: a, b (N tokens over budget B)", CompilerSupport.recordDrop) taken verbatim from the plan -
 * the client composes no prompt and infers nothing about what was cut.
 */
export function degradationSummary(plan) {
    let list = plan && Array.isArray(plan.degradations) ? plan.degradations : [];
    let codes = [];
    let dropped = [];
    list.forEach(function (d) {
        if (!d) return;
        let code = String(d.code || '').toUpperCase();
        if (code && codes.indexOf(code) < 0) codes.push(code);
        if (code === 'PROMPT_TRUNCATED') dropped.push(String(d.message || ''));
    });
    return { count: list.length, codes: codes, truncatedDropped: dropped };
}
