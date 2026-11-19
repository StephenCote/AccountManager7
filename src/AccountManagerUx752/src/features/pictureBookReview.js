/**
 * pictureBookReview — PB2 V3 review queue.
 *
 * Route:
 *   /picture-book/:bookObjectId/review   (bookObjectId = olio.pb.book objectId)
 *
 * One read per filter change: GET /{book}/review-queue?filter=all|auto-approved
 * (PbServiceFacade.listReviewQueue). Use this / Approve / Reject are PUTs on the artifact route and
 * the queue is refetched after each, so the view only ever shows server state. The whole route is
 * V3-only on the server (409 under v1) - the reader shows the link only under v3, and a direct
 * navigation under v1 surfaces the 409 body here.
 *
 * Edit › Review (aiDocs/PictureBookUxConsistencyDesign.md §5.8): `reviewSection` is the bookWorkspace
 * SectionAdapter (PictureBook and ChapBook — any olio.pb.book objectId). Master/detail: the queue's nodes on the
 * left with unreviewed / QA-warning / stale-approved badges, the selected node's artifact rows and its render
 * plan (expanded, no toggle) on the right. `loadReviewCount(bookObjectId)` fills the rail count without opening
 * the section. Use this / Approve / Reject stay immediate commands (§6.2-6); this section holds no drafts.
 * The old /picture-book/:id/review route still renders the single-column list (with its back arrow).
 *
 * Not linked (§4.7): the queue's node summary carries no scene / character / location objectId
 * (PbServiceFacade.listReviewQueue sends nodeObjectId, handle, nodeType, nodeStatus, sceneIndex, pinned), so the
 * detail names the scene by index only.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { layout, pageLayout } from '../router.js';
import { resolveImageUrl } from '../workflows/sceneExtractor.js';
import {
    getReviewQueue, selectArtifact, approveArtifact, rejectArtifact, nodeView, loadNodePlan,
    normalizeQa, qaBadge, qaFindings, qaReportsForNewestAttempt
} from '../workflows/pictureBookWorkflow.js';
import { PbPlanInspector } from '../components/PbPlanInspector.js';
import { pageHeader, chip, metaText } from '../components/pageHeader.js';
import { BTN, BTN_DANGER } from '../components/buttons.js';
import { segmented, stateBlock, masterDetail } from '../components/bookUi.js';
import { guardDirty } from '../components/dirtyGuard.js';
import { editLink, ensureSelection } from '../workflows/bookWorkspaceNav.js';

let bookObjectId = null;
let queue = null;
let loading = false;
let error = null;
let filter = 'all';
let busyArtifact = null;
let imageUrls = {};
let loadSeq = 0;
let nodePlans = {};   // nodeObjectId → {open, loading, plan, artifact, qa, error} (V3 P2-9 per-node plan expander; qa = P8-5 newest-attempt reports)
let qaOpen = {};      // artifactObjectId → true while its inline visual-QA report is expanded (§17 / P8-5)
let selectedNode = null;   // Edit › Review: the route's :item (node objectId), null = first
let lastSelIndex = -1;     // re-seat index when the selected node leaves the queue (ensureSelection)
let reviewCounts = {};     // bookObjectId → { count, pending } from the 'all' queue (rail status)
let countLoads = {};       // bookObjectId → in-flight loadReviewCount promise

/** Per-node "Plan" expander: nodeView → newest renderPlan artifact → GET /artifact → inspector. Lazy, once per node. */
export async function toggleNodePlan(nodeObjectId) {
    let st = nodePlans[nodeObjectId];
    if (st && st.open) { st.open = false; m.redraw(); return; }
    return openNodePlan(nodeObjectId);
}

/** Open (and lazily load, once) a node's render plan — the Edit › Review detail shows it expanded. */
export async function openNodePlan(nodeObjectId) {
    let st = nodePlans[nodeObjectId] || (nodePlans[nodeObjectId] = { open: false, loading: false, plan: null, artifact: null, qa: null, error: null });
    st.open = true;
    if (st.plan || st.loading) { m.redraw(); return; }
    let bookId = bookObjectId;
    st.loading = true;
    st.error = null;
    m.redraw();
    try {
        let nv = await nodeView(bookId, nodeObjectId);
        if (bookId !== bookObjectId) return;
        let r = await loadNodePlan(bookId, nv && nv.artifacts);
        if (bookId !== bookObjectId) return;
        if (!r) throw new Error('This node has no render-plan artifact');
        if (!r.plan) throw new Error('The render-plan artifact ' + (r.artifact && r.artifact.objectId) + ' does not carry plan JSON');
        st.plan = r.plan;
        st.artifact = r.artifact;
        st.qa = qaReportsForNewestAttempt(nv && nv.artifacts);
    } catch (e) {
        if (bookId !== bookObjectId) return;
        st.error = (e && e.message) || 'Could not load the plan';
    }
    st.loading = false;
    m.redraw();
}

// ── Pure derivations (exported for Vitest) ─────────────────────────────

/** The server lists an artifact chain ascending (newest LAST); the queue shows newest first. Copies. */
export function orderNewestFirst(artifacts) {
    return (Array.isArray(artifacts) ? artifacts : []).slice().reverse();
}

export function reviewStatusOf(a) {
    return String((a && a.reviewStatus) || 'GENERATED').toUpperCase();
}

/** APPROVED with approvedBy == "AUTO" (OlioFieldNames.PB_APPROVED_BY_AUTO) is the server's auto-approve marker. */
export function isAutoApproved(a) {
    return reviewStatusOf(a) === 'APPROVED' && String((a && a.approvedBy) || '').toUpperCase() === 'AUTO';
}

/**
 * Which of Use this / Approve / Reject apply to one artifact. "Use this" is selection (the node's
 * current revision), Approve/Reject are review verdicts; a pinned node's selection is locked, and a
 * verdict already given is not offered again.
 */
export function artifactActions(a, nodePinned) {
    let rs = reviewStatusOf(a);
    let selected = !!(a && a.selected);
    return {
        canUse: !selected && !nodePinned && rs !== 'ARCHIVED',
        canApprove: rs !== 'APPROVED' && rs !== 'ARCHIVED',
        canReject: rs !== 'REJECTED' && rs !== 'ARCHIVED'
    };
}

export function queueSummary(q) {
    q = q || {};
    let nodes = Array.isArray(q.nodes) ? q.nodes : [];
    return {
        nodes: nodes.length,
        artifacts: q.artifactCount != null ? q.artifactCount : nodes.reduce(function (n, x) { return n + ((x && x.artifacts) ? x.artifacts.length : 0); }, 0),
        pending: q.pendingCount != null ? q.pendingCount : 0,
        autoApprove: !!q.autoApprove,
        filter: q.filter || 'all'
    };
}

export function nodeLabel(n) {
    if (!n) return '';
    if (n.sceneIndex != null) return 'Scene ' + n.sceneIndex + (n.handle ? ' · ' + n.handle : '');
    return n.handle || n.nodeObjectId || '';
}

/**
 * The Edit › Review list badges for one queue node, derived only from the queue payload:
 *   unreviewed     — artifacts still GENERATED / IN_REVIEW (the server's pendingCount rule), with the count
 *   QA warning     — any artifact whose newest visual-QA verdict is WARN or FAIL (red when any FAIL)
 *   stale-approved — the node is STALE but its current (selected) artifact is APPROVED: the approved image no
 *                    longer matches its inputs
 * Pure; returns [{key, text, tone}] in that order.
 */
export function nodeBadges(n) {
    let out = [];
    let arts = (n && Array.isArray(n.artifacts)) ? n.artifacts : [];
    let pending = arts.filter(function (a) { let rs = reviewStatusOf(a); return rs === 'GENERATED' || rs === 'IN_REVIEW'; }).length;
    if (pending) out.push({ key: 'unreviewed', text: pending + ' unreviewed', tone: 'amber' });
    let worst = null;
    arts.forEach(function (a) {
        let qa = normalizeQa(a && a.qa);
        if (!qa) return;
        if (qa.result === 'FAIL') worst = 'FAIL';
        else if (qa.result === 'WARN' && worst !== 'FAIL') worst = 'WARN';
    });
    if (worst) out.push({ key: 'qa', text: 'QA warning', tone: worst === 'FAIL' ? 'red' : 'amber' });
    let current = arts.filter(function (a) { return a && a.selected; })[0];
    if (String((n && n.nodeStatus) || '').toUpperCase() === 'STALE' && current && reviewStatusOf(current) === 'APPROVED') {
        out.push({ key: 'stale-approved', text: 'stale · approved', tone: 'purple' });
    }
    return out;
}

export function __reviewStateForTest() {
    return { bookObjectId, queue, loading, error, filter, busyArtifact, nodePlans, qaOpen, selectedNode, reviewCounts };
}
export function __setReviewStateForTest(patch) {
    patch = patch || {};
    if ('bookObjectId' in patch) bookObjectId = patch.bookObjectId;
    if ('queue' in patch) queue = patch.queue;
    if ('loading' in patch) loading = !!patch.loading;
    if ('error' in patch) error = patch.error;
    if ('filter' in patch) filter = patch.filter;
    if ('busyArtifact' in patch) busyArtifact = patch.busyArtifact;
    if ('nodePlans' in patch) nodePlans = patch.nodePlans || {};
    if ('qaOpen' in patch) qaOpen = patch.qaOpen || {};
    if ('selectedNode' in patch) { selectedNode = patch.selectedNode; lastSelIndex = -1; }
    if ('reviewCounts' in patch) { reviewCounts = patch.reviewCounts || {}; countLoads = {}; }
}

/** The §17 verdict badge beside the status pill: click toggles the artifact's inline report. Null for an unjudged image. */
export function toggleQaReport(artifactObjectId) {
    if (!artifactObjectId) return;
    if (qaOpen[artifactObjectId]) delete qaOpen[artifactObjectId]; else qaOpen[artifactObjectId] = true;
    m.redraw();
}

// ── Data ───────────────────────────────────────────────────────────────

export async function loadQueue() {
    if (!bookObjectId) return;
    let seq = ++loadSeq;
    loading = true;
    error = null;
    m.redraw();
    try {
        let q = await getReviewQueue(bookObjectId, filter);
        if (seq !== loadSeq) return;
        queue = q;
        if (filter === 'all') recordCount(bookObjectId, q);
        resolveThumbs(q);
    } catch (e) {
        if (seq !== loadSeq) return;
        queue = null;
        error = (e && e.message) || 'Failed to load the review queue';
    }
    loading = false;
    m.redraw();
}

function resolveThumbs(q) {
    (q && q.nodes ? q.nodes : []).forEach(function (n) {
        (n.artifacts || []).forEach(function (a) {
            let id = a && a.dataObjectId;
            if (!id || imageUrls[id] !== undefined) return;
            imageUrls[id] = null;
            resolveImageUrl(id).then(function (u) { imageUrls[id] = u || null; m.redraw(); });
        });
    });
}

async function act(label, fn, artifactObjectId) {
    if (busyArtifact) return;
    busyArtifact = artifactObjectId;
    m.redraw();
    try {
        await fn(bookObjectId, artifactObjectId);
        page.toast('success', label);
    } catch (e) {
        page.toast('error', (e && e.message) || (label + ' failed'));
    }
    busyArtifact = null;
    let book = bookObjectId;
    await loadQueue();
    // the 'all' load already refreshed the rail count; under another filter fetch it separately
    if (filter !== 'all') await loadReviewCount(book, { refresh: true });
}

function recordCount(book, q) {
    if (!book || !q) return;
    let s = queueSummary(q);
    reviewCounts[book] = { count: s.nodes, pending: s.pending };
}

/**
 * The rail count for Edit › Review without opening the section: one GET /{book}/review-queue?filter=all,
 * cached per book (concurrent callers share the request). `{refresh:true}` refetches. Resolves to the number
 * of queue nodes, or null when the queue could not be read (e.g. the v1 409) — a rail status never throws.
 */
export async function loadReviewCount(book, opts) {
    if (!book) return null;
    let refresh = !!(opts && opts.refresh);
    if (!refresh && reviewCounts[book]) return reviewCounts[book].count;
    if (countLoads[book]) return countLoads[book];
    let p = (async function () {
        try {
            let q = await getReviewQueue(book, 'all');
            recordCount(book, q);
            return reviewCounts[book].count;
        } catch (e) {
            return reviewCounts[book] ? reviewCounts[book].count : null;
        } finally {
            delete countLoads[book];
            m.redraw();
        }
    })();
    countLoads[book] = p;
    return p;
}

/** Switch the module to another book (the old route's oninit logic, shared with the section adapter). */
function enterBook(id) {
    if (id && id !== bookObjectId) {
        bookObjectId = id;
        queue = null;
        error = null;
        filter = 'all';
        imageUrls = {};
        nodePlans = {};
        qaOpen = {};
        selectedNode = null;
        lastSelIndex = -1;
    }
}

function setFilter(f) {
    f = f === 'auto-approved' ? 'auto-approved' : 'all';
    if (f === filter) return;
    filter = f;
    loadQueue();
}

// ── View ───────────────────────────────────────────────────────────────

function statusClass(rs) {
    switch (rs) {
        case 'APPROVED': return 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-200';
        case 'REJECTED': return 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200';
        case 'ARCHIVED': return 'bg-gray-200 text-gray-600 dark:bg-gray-700 dark:text-gray-300';
        case 'IN_REVIEW': return 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-200';
        default: return 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-200';
    }
}

/** The inline visual-QA report under a judged artifact row: provider/model, note, one line per check. */
function renderQaReport(a, qa) {
    let who = [qa.provider, qa.model].filter(Boolean).join(' / ');
    return m('div', { 'data-testid': 'pb2-review-qa-report', 'data-artifact-id': a.objectId, 'data-result': qa.result, class: 'mt-1 p-1.5 rounded bg-gray-50 dark:bg-gray-800 text-xs' }, [
        m('div', { class: 'text-gray-500' }, [
            who || 'visual QA',
            qa.durationMs ? ' · ' + (Math.round(qa.durationMs / 100) / 10) + ' s' : '',
            qa.imageArtifactObjectId && qa.imageArtifactObjectId !== a.objectId ? ' · for ' + qa.imageArtifactObjectId : ''
        ].join('')),
        qa.note ? m('div', { class: 'text-gray-700 dark:text-gray-300 mt-0.5 break-words' }, qa.note) : null,
        qa.checks.length
            ? m('ul', { class: 'mt-1 space-y-0.5' }, qa.checks.map(function (c) {
                let cb = qaBadge({ result: c.result });
                return m('li', { 'data-testid': 'pb2-review-qa-check', 'data-kind': c.kind, 'data-result': c.result, class: 'flex gap-2 items-start' }, [
                    m('span', { class: 'shrink-0 w-4 text-center', style: 'color:' + cb.color, title: cb.label }, cb.glyph),
                    m('span', { class: 'font-medium shrink-0' }, c.kind + (c.actorId ? ' ' + c.actorId : '')),
                    m('span', { class: 'text-gray-600 dark:text-gray-300 break-words' },
                        (c.expected != null || c.observed != null ? 'expected ' + (c.expected != null ? c.expected : '—') + ' → observed ' + (c.observed != null ? c.observed : '—') : '')
                        + (c.detail ? (c.expected != null || c.observed != null ? ' — ' : '') + c.detail : ''))
                ]);
            }))
            : m('div', { class: 'text-gray-400 italic mt-0.5' }, 'No checks recorded.')
    ]);
}

function renderArtifact(n, a) {
    let rs = reviewStatusOf(a);
    let acts = artifactActions(a, !!n.pinned);
    let busy = busyArtifact === a.objectId;
    let url = a.dataObjectId ? imageUrls[a.dataObjectId] : null;
    let qa = normalizeQa(a.qa);
    let badge = qaBadge(qa);
    return m('div', {
        key: a.objectId,
        'data-testid': 'pb2-review-artifact',
        'data-artifact-id': a.objectId,
        'data-review-status': rs,
        'data-selected': a.selected ? '1' : '0',
        'data-qa-result': qa ? qa.result : undefined,
        class: 'flex items-center gap-3 p-2 rounded border dark:border-gray-700 ' + (a.selected ? 'border-purple-400 bg-purple-50/40 dark:bg-purple-900/10' : '')
    }, [
        url
            ? m('img', { src: url, class: 'w-16 h-16 object-cover rounded flex-shrink-0', loading: 'lazy' })
            : m('div', { class: 'w-16 h-16 rounded bg-gray-100 dark:bg-gray-800 flex items-center justify-center flex-shrink-0' },
                m('span', { class: 'material-symbols-outlined text-gray-400' }, 'image')),
        m('div', { class: 'flex-1 min-w-0 text-xs' }, [
            m('div', { class: 'flex items-center gap-2' }, [
                m('span', { class: 'font-mono' }, 'rev ' + (a.revision != null ? a.revision : '?')),
                m('span', { class: 'px-1.5 py-0.5 rounded ' + statusClass(rs) }, rs),
                a.selected ? m('span', { 'data-testid': 'pb2-review-selected', class: 'px-1.5 py-0.5 rounded bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200' }, 'current') : null,
                isAutoApproved(a) ? m('span', { 'data-testid': 'pb2-review-auto-mark', class: 'text-gray-500' }, 'auto-approved') : null,
                badge ? m('button', {
                    'data-testid': 'pb2-review-qa',
                    'data-qa-result': badge.result,
                    'data-open': qaOpen[a.objectId] ? '1' : '0',
                    class: 'px-1.5 py-0.5 rounded-full font-semibold cursor-pointer border-0',
                    style: 'color:' + badge.color + ';background:' + badge.bg,
                    title: [badge.label + (qa.provider ? ' (' + qa.provider + ')' : '')].concat(qaFindings(qa)).join('\n'),
                    onclick: function (e) { e.stopPropagation(); toggleQaReport(a.objectId); }
                }, badge.glyph + ' ' + badge.result) : null
            ]),
            m('div', { class: 'text-gray-500 mt-1 truncate' }, [
                a.backend ? a.backend + ' · ' : '',
                a.imageWidth && a.imageHeight ? a.imageWidth + '×' + a.imageHeight + ' · ' : '',
                a.seed != null ? 'seed ' + a.seed : ''
            ].join('')),
            rs === 'APPROVED' && a.approvedBy ? m('div', { class: 'text-gray-500 mt-0.5' }, 'approved by ' + a.approvedBy + (a.approvedAt ? ' · ' + a.approvedAt : '')) : null,
            qa && qaOpen[a.objectId] ? renderQaReport(a, qa) : null
        ]),
        m('div', { class: 'flex flex-col gap-1' }, [
            m('button', {
                'data-testid': 'pb2-review-use', class: BTN, disabled: busy || !acts.canUse,
                title: n.pinned ? 'This node is pinned; unpin it on the canvas to change its image' : 'Make this revision the page image',
                onclick: function () { act('Revision ' + a.revision + ' is now current', selectArtifact, a.objectId); }
            }, 'Use this'),
            m('button', {
                'data-testid': 'pb2-review-approve', class: BTN, disabled: busy || !acts.canApprove,
                onclick: function () { act('Approved', approveArtifact, a.objectId); }
            }, 'Approve'),
            m('button', {
                'data-testid': 'pb2-review-reject', class: BTN_DANGER, disabled: busy || !acts.canReject,
                onclick: function () { act('Rejected', rejectArtifact, a.objectId); }
            }, 'Reject')
        ])
    ]);
}

function renderNode(n) {
    let chain = orderNewestFirst(n.artifacts);
    let ps = nodePlans[n.nodeObjectId];
    return m('div', {
        key: n.nodeObjectId,
        'data-testid': 'pb2-review-node',
        'data-node-id': n.nodeObjectId,
        class: 'mb-4 p-3 rounded border dark:border-gray-700 bg-white dark:bg-gray-900'
    }, [
        m('div', { class: 'flex items-center gap-2 mb-2 text-sm' }, [
            m('span', { class: 'font-semibold' }, nodeLabel(n)),
            m('span', { class: 'text-xs text-gray-500' }, String(n.nodeStatus || '').toUpperCase()),
            n.pinned ? m('span', { class: 'text-xs px-1.5 py-0.5 rounded bg-gray-200 dark:bg-gray-700' }, 'pinned') : null,
            m('span', { class: 'flex-1' }),
            m('span', { class: 'text-xs text-gray-500' }, chain.length + ' revision' + (chain.length !== 1 ? 's' : '')),
            m('button', {
                'data-testid': 'pb2-review-plan-toggle',
                class: BTN,
                disabled: !!(ps && ps.loading),
                title: 'Show the render plan this node was generated from',
                onclick: function () { toggleNodePlan(n.nodeObjectId); }
            }, ps && ps.loading ? 'Loading plan…' : (ps && ps.open ? 'Hide plan' : 'Plan'))
        ]),
        ps && ps.open && ps.error ? m('div', { 'data-testid': 'pb2-review-plan-error', class: 'mb-2 text-xs text-red-600 dark:text-red-400 break-words' }, ps.error) : null,
        ps && ps.open && ps.plan ? m('div', { 'data-testid': 'pb2-review-plan', class: 'mb-3 p-2 rounded border dark:border-gray-700 max-h-96 overflow-y-auto' }, [
            ps.artifact ? m('div', { class: 'text-[10px] text-gray-500 mb-1' }, 'rev ' + (ps.artifact.revision != null ? ps.artifact.revision : '?') + ' · ' + (ps.artifact.objectId || '')) : null,
            m(PbPlanInspector, { plan: ps.plan, qa: ps.qa })
        ]) : null,
        chain.length
            ? m('div', { class: 'space-y-2' }, chain.map(function (a) { return renderArtifact(n, a); }))
            : m('div', { class: 'text-xs text-gray-500 italic' }, 'No artifacts yet.')
    ]);
}

/** The All / Auto-approved filter, one shared segmented control (§4.6); the option testids are the old pills'. */
function renderFilter() {
    return segmented({
        testid: 'pb2-review-filter',
        value: filter,
        onchange: setFilter,
        options: [
            { value: 'all', label: 'All', testid: 'pb2-review-filter-all' },
            { value: 'auto-approved', label: 'Auto-approved', testid: 'pb2-review-filter-auto' }
        ]
    });
}

/**
 * The header options shared by the old route and the Edit › Review toolbar. With a workspace `ctx`, the
 * Auto-approve chip links to Edit › Book (where the switch lives) through the dirty guard.
 */
function headerOpts(ctx) {
    let s = queueSummary(queue);
    let chipAttrs = { 'data-testid': 'pb2-review-auto-approve', 'data-on': s.autoApprove ? '1' : '0' };
    if (ctx && ctx.bookObjectId) {
        let target = editLink(ctx.kind, ctx.bookObjectId, { type: 'book' });
        let go = function () { return guardDirty(function () { m.route.set(target); }); };
        Object.assign(chipAttrs, {
            role: 'link', tabindex: 0, 'data-href': target, title: 'Change auto-approve in Edit › Book', class: 'cursor-pointer hover:underline',
            onclick: go,
            onkeydown: function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } }
        });
    }
    return {
        title: 'Review queue',
        meta: [
            metaText(queue ? (s.pending + ' pending · ' + s.artifacts + ' artifact' + (s.artifacts !== 1 ? 's' : '') + ' · ' + s.nodes + ' node' + (s.nodes !== 1 ? 's' : '')) : '', { 'data-testid': 'pb2-review-counts' }),
            queue ? chip('Auto-approve: ' + (s.autoApprove ? 'ON' : 'OFF'), s.autoApprove ? 'green' : 'gray', chipAttrs) : null
        ],
        actions: [renderFilter()],
        refresh: { testid: 'pb2-review-refresh', disabled: loading, onclick: function () { loadQueue(); } }
    };
}

/** Old route header (/picture-book/:id/review): the shared options plus its back arrow. */
export function renderReviewHeader() {
    return pageHeader(Object.assign(headerOpts(null), {
        back: { testid: 'pb2-review-back', title: 'Back to book', onclick: function () { m.route.set('/picture-book/v2/' + bookObjectId); } }
    }));
}

function renderState() {
    let nodes = queue && Array.isArray(queue.nodes) ? queue.nodes : [];
    return stateBlock({
        loading: loading && !queue,
        error: error,
        empty: !nodes.length,
        loadingText: 'Loading review queue...',
        emptyText: filter === 'auto-approved' ? 'No auto-approved artifacts.' : 'Nothing to review.',
        testids: { loading: 'pb2-review-loading', error: 'pb2-review-error', empty: 'pb2-review-empty' }
    });
}

/** Old route body: every node, one column, plan behind its toggle. */
export function renderReviewBody() {
    let st = renderState();
    if (st) return st;
    return m('div', { class: 'max-w-3xl mx-auto w-full' }, queue.nodes.map(renderNode));
}

/** The node the detail shows: the route's :item if still queued, else the same index, else the first (§4.4). */
function effectiveSelection(nodes) {
    let ids = nodes.map(function (n) { return n.nodeObjectId; });
    let sel = ensureSelection(ids, selectedNode, lastSelIndex);
    lastSelIndex = sel != null ? ids.indexOf(sel) : -1;
    return sel;
}

/** Edit › Review detail: the node's header, its artifact rows, then its render plan shown expanded (no toggle). */
function renderNodeDetail(n) {
    let chain = orderNewestFirst(n.artifacts);
    if (!nodePlans[n.nodeObjectId]) openNodePlan(n.nodeObjectId);
    let ps = nodePlans[n.nodeObjectId];
    return m('div', { 'data-testid': 'pb2-review-node', 'data-node-id': n.nodeObjectId }, [
        m('div', { class: 'flex items-center gap-2 mb-3 text-sm' }, [
            m('span', { class: 'font-semibold', 'data-testid': 'pb2-review-node-label' }, nodeLabel(n)),
            m('span', { class: 'text-xs text-gray-500' }, String(n.nodeStatus || '').toUpperCase()),
            n.pinned ? m('span', { class: 'text-xs px-1.5 py-0.5 rounded bg-gray-200 dark:bg-gray-700' }, 'pinned') : null,
            m('span', { class: 'flex-1' }),
            m('span', { class: 'text-xs text-gray-500' }, chain.length + ' revision' + (chain.length !== 1 ? 's' : ''))
        ]),
        chain.length
            ? m('div', { class: 'space-y-2 mb-4' }, chain.map(function (a) { return renderArtifact(n, a); }))
            : m('div', { class: 'text-xs text-gray-500 italic mb-4' }, 'No artifacts yet.'),
        m('div', { class: 'text-xs font-medium text-gray-600 dark:text-gray-400 mb-1' }, 'Render plan'),
        ps && ps.loading ? m('div', { 'data-testid': 'pb2-review-plan-loading', class: 'text-xs text-gray-500' }, 'Loading plan…') : null,
        ps && !ps.loading && ps.error ? m('div', { 'data-testid': 'pb2-review-plan-error', class: 'text-xs text-red-600 dark:text-red-400 break-words' }, ps.error) : null,
        ps && ps.plan ? m('div', { 'data-testid': 'pb2-review-plan', class: 'p-2 rounded border dark:border-gray-700' }, [
            ps.artifact ? m('div', { class: 'text-[10px] text-gray-500 mb-1' }, 'rev ' + (ps.artifact.revision != null ? ps.artifact.revision : '?') + ' · ' + (ps.artifact.objectId || '')) : null,
            m(PbPlanInspector, { plan: ps.plan, qa: ps.qa })
        ]) : null
    ]);
}

/** Edit › Review body: master/detail over the queue's nodes (§5.8). */
export function renderReviewSectionBody(ctx) {
    let st = renderState();
    if (st) return st;
    let nodes = queue.nodes;
    let sel = effectiveSelection(nodes);
    return masterDetail({
        testid: 'pb2-review-md',
        listTestid: 'pb2-review-list',
        rowTestid: 'pb2-review-row',
        detailTestid: 'pb2-review-detail',
        items: nodes,
        idOf: function (n) { return n && n.nodeObjectId; },
        selectedId: sel,
        onSelect: function (id) {
            if (ctx && ctx.select) return ctx.select(id);
            selectedNode = id;
            m.redraw();
        },
        rowView: function (n) {
            let newest = orderNewestFirst(n.artifacts)[0];
            let chainLen = (n.artifacts || []).length;
            return {
                title: nodeLabel(n),
                subtitle: String(n.nodeStatus || '').toUpperCase() + ' · ' + chainLen + ' revision' + (chainLen !== 1 ? 's' : '') + (n.pinned ? ' · pinned' : ''),
                thumb: newest && newest.dataObjectId ? (imageUrls[newest.dataObjectId] || null) : null,
                badges: nodeBadges(n).map(function (b) { return { text: b.text, tone: b.tone, attrs: { 'data-badge': b.key } }; })
            };
        },
        detailView: renderNodeDetail,
        emptyText: 'Nothing to review.'
    });
}

/**
 * Edit › Review — the bookWorkspace SectionAdapter (components/bookWorkspace.js header). Works for any
 * olio.pb.book objectId; ctx.kind ('pb' | 'cb') only shapes the Edit › Book link. No dirtyKeys: every action
 * here is an immediate command (§6.2-6).
 */
export const reviewSection = {
    init: function (ctx) {
        enterBook(ctx.bookObjectId);
        selectedNode = ctx.item || null;
        lastSelIndex = -1;
        if (bookObjectId) loadQueue();
    },
    select: function (ctx) { selectedNode = ctx.item || null; },
    toolbar: function (ctx) { return headerOpts(ctx); },
    body: function (ctx) { return renderReviewSectionBody(ctx); },
    status: function (ctx) {
        let c = ctx && reviewCounts[ctx.bookObjectId];
        if (!c) return null;
        // The rail counts what is WAITING for a decision (pending), not every queue node: a book whose renders are
        // all approved still has nodes in the 'all' queue, and counting those would make Review "incomplete"
        // forever and capture D6's next-incomplete-section landing (design §4.3).
        return { kind: 'count', count: c.pending, title: c.pending + ' waiting for review · ' + c.count + ' node' + (c.count !== 1 ? 's' : '') + ' in the queue' };
    }
};

const reviewView = {
    oninit: function (vnode) {
        enterBook(vnode.attrs.bookObjectId);
        if (bookObjectId) loadQueue();
    },
    view: function () {
        return m('div', { 'data-testid': 'pb2-review', class: 'p-4 flex flex-col h-full' }, [
            renderReviewHeader(),
            m('div', { class: 'flex-1 overflow-y-auto' }, renderReviewBody())
        ]);
    }
};

export const routes = {
    '/picture-book/:bookObjectId/review': {
        oninit: function (vnode) { reviewView.oninit(vnode); },
        onupdate: function (vnode) {
            let id = vnode.attrs.bookObjectId;
            if (id && id !== bookObjectId) reviewView.oninit(vnode);
        },
        view: function () { return layout(pageLayout(m(reviewView))); }
    }
};
