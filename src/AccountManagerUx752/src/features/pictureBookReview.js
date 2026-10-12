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
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { layout, pageLayout } from '../router.js';
import { resolveImageUrl } from '../workflows/sceneExtractor.js';
import { getReviewQueue, selectArtifact, approveArtifact, rejectArtifact } from '../workflows/pictureBookWorkflow.js';

let bookObjectId = null;
let queue = null;
let loading = false;
let error = null;
let filter = 'all';
let busyArtifact = null;
let imageUrls = {};
let loadSeq = 0;

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

export function __reviewStateForTest() {
    return { bookObjectId, queue, loading, error, filter, busyArtifact };
}
export function __setReviewStateForTest(patch) {
    patch = patch || {};
    if ('bookObjectId' in patch) bookObjectId = patch.bookObjectId;
    if ('queue' in patch) queue = patch.queue;
    if ('loading' in patch) loading = !!patch.loading;
    if ('error' in patch) error = patch.error;
    if ('filter' in patch) filter = patch.filter;
    if ('busyArtifact' in patch) busyArtifact = patch.busyArtifact;
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
    await loadQueue();
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

function renderArtifact(n, a) {
    let rs = reviewStatusOf(a);
    let acts = artifactActions(a, !!n.pinned);
    let busy = busyArtifact === a.objectId;
    let url = a.dataObjectId ? imageUrls[a.dataObjectId] : null;
    let btn = 'px-2 py-0.5 text-xs rounded border dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed';
    return m('div', {
        key: a.objectId,
        'data-testid': 'pb2-review-artifact',
        'data-artifact-id': a.objectId,
        'data-review-status': rs,
        'data-selected': a.selected ? '1' : '0',
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
                isAutoApproved(a) ? m('span', { 'data-testid': 'pb2-review-auto-mark', class: 'text-gray-500' }, 'auto-approved') : null
            ]),
            m('div', { class: 'text-gray-500 mt-1 truncate' }, [
                a.backend ? a.backend + ' · ' : '',
                a.imageWidth && a.imageHeight ? a.imageWidth + '×' + a.imageHeight + ' · ' : '',
                a.seed != null ? 'seed ' + a.seed : ''
            ].join('')),
            rs === 'APPROVED' && a.approvedBy ? m('div', { class: 'text-gray-500 mt-0.5' }, 'approved by ' + a.approvedBy + (a.approvedAt ? ' · ' + a.approvedAt : '')) : null
        ]),
        m('div', { class: 'flex flex-col gap-1' }, [
            m('button', {
                'data-testid': 'pb2-review-use', class: btn, disabled: busy || !acts.canUse,
                title: n.pinned ? 'This node is pinned; unpin it on the canvas to change its image' : 'Make this revision the page image',
                onclick: function () { act('Revision ' + a.revision + ' is now current', selectArtifact, a.objectId); }
            }, 'Use this'),
            m('button', {
                'data-testid': 'pb2-review-approve', class: btn, disabled: busy || !acts.canApprove,
                onclick: function () { act('Approved', approveArtifact, a.objectId); }
            }, 'Approve'),
            m('button', {
                'data-testid': 'pb2-review-reject', class: btn + ' text-red-600', disabled: busy || !acts.canReject,
                onclick: function () { act('Rejected', rejectArtifact, a.objectId); }
            }, 'Reject')
        ])
    ]);
}

function renderNode(n) {
    let chain = orderNewestFirst(n.artifacts);
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
            m('span', { class: 'text-xs text-gray-500' }, chain.length + ' revision' + (chain.length !== 1 ? 's' : ''))
        ]),
        chain.length
            ? m('div', { class: 'space-y-2' }, chain.map(function (a) { return renderArtifact(n, a); }))
            : m('div', { class: 'text-xs text-gray-500 italic' }, 'No artifacts yet.')
    ]);
}

export function renderReviewHeader() {
    let s = queueSummary(queue);
    let tab = function (f, label, tid) {
        let active = filter === f;
        return m('button', {
            'data-testid': tid,
            'data-active': active ? '1' : '0',
            class: 'px-3 py-1 text-xs rounded-full border dark:border-gray-600 ' + (active ? 'bg-purple-600 text-white border-purple-600' : 'hover:bg-gray-100 dark:hover:bg-gray-700'),
            onclick: function () { setFilter(f); }
        }, label);
    };
    return m('div', { class: 'flex items-center gap-3 mb-4 flex-wrap' }, [
        m('button', {
            'data-testid': 'pb2-review-back',
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Back to book',
            onclick: function () { m.route.set('/picture-book/v2/' + bookObjectId); }
        }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
        m('span', { class: 'font-semibold text-sm' }, 'Review queue'),
        m('span', { 'data-testid': 'pb2-review-counts', class: 'text-xs text-gray-500' },
            queue ? (s.pending + ' pending · ' + s.artifacts + ' artifact' + (s.artifacts !== 1 ? 's' : '') + ' · ' + s.nodes + ' node' + (s.nodes !== 1 ? 's' : '')) : ''),
        queue ? m('span', {
            'data-testid': 'pb2-review-auto-approve',
            'data-on': s.autoApprove ? '1' : '0',
            class: 'text-xs px-2 py-0.5 rounded-full ' + (s.autoApprove ? 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-200' : 'bg-gray-200 text-gray-600 dark:bg-gray-700 dark:text-gray-300')
        }, 'Auto-approve: ' + (s.autoApprove ? 'ON' : 'OFF')) : null,
        m('span', { class: 'flex-1' }),
        tab('all', 'All', 'pb2-review-filter-all'),
        tab('auto-approved', 'Auto-approved', 'pb2-review-filter-auto'),
        m('button', {
            'data-testid': 'pb2-review-refresh',
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            title: 'Refresh', disabled: loading,
            onclick: function () { loadQueue(); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'refresh'))
    ]);
}

export function renderReviewBody() {
    if (loading && !queue) return m('div', { class: 'text-sm text-gray-500 text-center py-12' }, 'Loading review queue...');
    if (error) return m('div', { 'data-testid': 'pb2-review-error', class: 'text-red-500 text-sm text-center py-12' }, error);
    let nodes = queue && Array.isArray(queue.nodes) ? queue.nodes : [];
    if (!nodes.length) {
        return m('div', { 'data-testid': 'pb2-review-empty', class: 'text-sm text-gray-500 text-center py-12' },
            filter === 'auto-approved' ? 'No auto-approved artifacts.' : 'Nothing to review.');
    }
    return m('div', { class: 'max-w-3xl mx-auto w-full' }, nodes.map(renderNode));
}

const reviewView = {
    oninit: function (vnode) {
        let id = vnode.attrs.bookObjectId;
        if (id && id !== bookObjectId) {
            bookObjectId = id;
            queue = null;
            error = null;
            filter = 'all';
            imageUrls = {};
        }
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
