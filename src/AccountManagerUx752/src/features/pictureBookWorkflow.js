/**
 * PictureBook Workflow Graph — Phase 5a
 * Canvas-style DAG view of the olio.pb.* workflow graph for a book.
 *
 * Routes:
 *   /picture-book/:bookObjectId/workflow  — workflow graph (bookObjectId = PB1 book group objectId)
 *
 * On init the component calls GET /{bookGroupObjectId}/pb2 to resolve the olio.pb.book objectId
 * (pb2BookObjectId) used by all Phase 4 endpoints.
 *
 * Layout: nodes positioned on a grid keyed by (sceneIndex, nodeTypeRank). Pan via drag on the
 * canvas background; zoom via wheel. SVG overlay draws edges between source and consumer nodes.
 */
import m from 'mithril';
import { layout, pageLayout } from '../router.js';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';
import {
    getBookInfo, workflowView, nodeView, listStale,
    regenerateNode, pinNode, addMembers, createChapter, testNode,
    saveCanvas, addBinding, deleteBinding, detectBoundaries,
    listSeriesBooks
} from '../workflows/pictureBookWorkflow.js';
import { groupBooksBySeries } from '../workflows/pictureBookSeries.js';
import { ObjectPicker } from '../components/picker.js';

// Re-exported for callers/tests that import from the canvas module (the pure impl lives in the
// deps-free ../workflows/pictureBookSeries.js so it is testable without the mithril/router imports).
export { groupBooksBySeries };

// ── Constants ─────────────────────────────────────────────────────────

const CARD_W = 200;
const CARD_H = 110;
const COL_GAP = 240;
const ROW_GAP = 140;
const CANVAS_PAD = 40;

const STATUS_COLOR = {
    DONE: '#16a34a',
    DONE_UNVERIFIED: '#84cc16',
    STALE: '#d97706',
    PENDING: '#6b7280',
    READY: '#3b82f6',
    FAILED: '#dc2626',
    UNKNOWN: '#9ca3af',
};

const NODE_TYPE_RANK = {
    portrait: 0,
    landscape: 1,
    reference: 2,
    scene: 3,
    composite: 4,
    character: 5,
    book: 6,
};

function nodeRank(n) {
    let t = (n.nodeType || '').toLowerCase();
    for (let k of Object.keys(NODE_TYPE_RANK)) {
        if (t.indexOf(k) >= 0) return NODE_TYPE_RANK[k];
    }
    return 99;
}

function statusColor(status) {
    return STATUS_COLOR[(status || '').toUpperCase()] || STATUS_COLOR.UNKNOWN;
}

function imageUrl(dataObjectId) {
    return applicationPath + '/rest/resource/data.data/' + dataObjectId;
}

// ── Module-level state ────────────────────────────────────────────────

let bookGroupObjectId = null;
let pb2BookObjectId = null;
let bookName = '';
let graphData = null;     // workflowView response
let positions = {};       // nodeObjectId → {x, y}
let loading = false;
let error = null;
// Informational empty state (NOT an error). One of the three honest "no graph to draw" cases —
// see loadGraph. Shape: {icon, title, body}. Rendered neutrally (grey), distinct from `error` (red).
let emptyState = null;

let selectedNodeId = null;
let nodeDetails = null;
let nodeDetailsLoading = false;

let pinLoading = {};
let regenLoading = {};
let testLoading = {};

let pan = { x: 0, y: 0 };
let zoom = 1;
let dragging = false;
let dragStart = null;
let panStart = null;

// Node drag state (B8)
let draggingNodeId = null;
let dragStartClient = null;
let dragStartPos = null;

// Edge drag state (B8 — port-to-port binding creation)
let pendingEdgeSrc = null;

let memberDialog = false;
let memberNames = '';
let chapterDialog = false;
let chapterSlug = '';
let chapterTitle = '';
let recheckingStale = false;

// N3 — chapter-from-manuscript boundary review state.
// chapterSourceData: the selected data.data manuscript {objectId, name} (null = no source chosen).
// boundaryRows: editable [{startOffset, endOffset, title}] detected (or hand-added) ranges.
// selectedBoundaryIdx: which row becomes the chapter's sourceRange (-1 = none / whole document).
// detectingBoundaries / boundaryError: loading + error surface for the detect call.
let chapterSourceData = null;
let boundaryRows = [];
let selectedBoundaryIdx = -1;
let detectingBoundaries = false;
let boundaryError = null;
let creatingChapter = false;

// N4 — canvas view state. 'chapter' = the single-chapter workflow graph (zoom-to-single-chapter);
// 'series' = the whole-series overview across the chapters of THIS book's series (via listSeriesBooks,
// keyed on graphData.seriesObjectId — the series FK surfaced by workflowView).
let viewMode = 'chapter';
let seriesChapters = null;        // groupBooksBySeries() result once loaded (see loadSeriesChapters)
let seriesChaptersLoading = false;
let seriesChaptersError = null;
let seriesChaptersLoaded = false; // true once a load has been attempted for the current book (a
                                  // standalone book resolves to a null grouping, which must not retrigger
                                  // a reload every time the Series tab is re-selected).

function resetChapterDialogState() {
    chapterDialog = false;
    chapterSlug = '';
    chapterTitle = '';
    chapterSourceData = null;
    boundaryRows = [];
    selectedBoundaryIdx = -1;
    detectingBoundaries = false;
    boundaryError = null;
    creatingChapter = false;
}

// ── Layout calculation ────────────────────────────────────────────────

function computePositions(nodes) {
    if (!nodes || !nodes.length) return {};
    // Group by sceneIndex, then by nodeTypeRank within each column
    let cols = {};
    for (let n of nodes) {
        let col = n.sceneIndex != null ? n.sceneIndex : 0;
        if (!cols[col]) cols[col] = [];
        cols[col].push(n);
    }
    // Sort columns by sceneIndex
    let colKeys = Object.keys(cols).map(Number).sort((a, b) => a - b);
    // Within each column sort by type rank then ordinal
    for (let k of colKeys) {
        cols[k].sort((a, b) => {
            let ra = nodeRank(a), rb = nodeRank(b);
            if (ra !== rb) return ra - rb;
            return (a.ordinal || 0) - (b.ordinal || 0);
        });
    }
    let pos = {};
    colKeys.forEach(function (col, ci) {
        cols[col].forEach(function (n, ri) {
            pos[n.objectId] = {
                x: CANVAS_PAD + ci * COL_GAP,
                y: CANVAS_PAD + ri * ROW_GAP,
            };
        });
    });
    return pos;
}

function canvasSize() {
    if (!graphData || !graphData.nodes || !graphData.nodes.length) return { w: 600, h: 400 };
    let maxX = 0, maxY = 0;
    for (let n of graphData.nodes) {
        let p = positions[n.objectId];
        if (!p) continue;
        maxX = Math.max(maxX, p.x + CARD_W);
        maxY = Math.max(maxY, p.y + CARD_H);
    }
    return { w: maxX + CANVAS_PAD, h: maxY + CANVAS_PAD };
}

// ── Data loading ──────────────────────────────────────────────────────

async function loadGraph(groupOid) {
    loading = true;
    error = null;
    emptyState = null;
    graphData = null;
    positions = {};
    selectedNodeId = null;
    nodeDetails = null;
    m.redraw();
    try {
        // B7: Try direct PB2 path first — ChapBook / PB2 book objectIds ARE pb2BookObjectIds.
        // If workflowView succeeds the route ID is already the pb2BookObjectId.
        // If it returns null (404 for a PB1 group objectId), fall back to the bridge.
        let gd = null;
        // workflowView returns null on 404 (PB1 group objectId), throws on 401/403/500
        try {
            gd = await workflowView(groupOid);
        } catch (directErr) {
            // Real error (401/403/500) — surface it directly, do not fall back
            error = 'Failed to load workflow: ' + directErr.message;
            loading = false;
            m.redraw();
            return;
        }
        if (gd !== null) {
            // The route id was itself the pb2BookObjectId — direct hit.
            pb2BookObjectId = groupOid;
            bookName = (gd && gd.bookName) || '';
        } else {
            // null = 404: fall back to PB1 bridge: resolve group objectId → pb2BookObjectId
            let info = await getBookInfo(groupOid);
            if (!info) {
                // State (a): getBookInfo 404 — the book meta carries no pb2BookObjectId, so this
                // book was created before the workflow graph existed. There is nothing to record.
                emptyState = {
                    icon: 'history',
                    title: 'No workflow graph for this book',
                    body: 'This book predates the workflow graph — it was created before workflow '
                        + 'recording existed, so there is nothing to show here. Re-render its scenes '
                        + 'to create a graph.'
                };
                loading = false;
                m.redraw();
                return;
            }
            pb2BookObjectId = info.pb2BookObjectId;
            bookName = info.bookName || '';
            gd = await workflowView(pb2BookObjectId);
            if (gd === null) {
                // State (b) — the previously SILENT case: the book resolves to a PB2 book, but no
                // olio.pb.workflow row was ever written for it. Workflow recording was off when its
                // scenes were rendered, so the images exist without a graph behind them.
                emptyState = {
                    icon: 'sync_disabled',
                    title: 'Workflow was not recorded',
                    body: 'Workflow recording was off when these scenes were rendered, so no graph '
                        + 'was saved for them. Re-render a scene to start recording the graph.'
                };
                loading = false;
                m.redraw();
                return;
            }
        }
        graphData = gd;
        positions = computePositions((gd && gd.nodes) || []);
        if (!graphData.nodes || graphData.nodes.length === 0) {
            // State (c): a workflow row exists but holds zero nodes — this book genuinely has
            // never been rendered. (Toolbar still shows the book name / "0 nodes".)
            emptyState = {
                icon: 'draft',
                title: 'Nothing rendered yet',
                body: 'This book has a workflow but no scenes have been rendered yet. Render a '
                    + 'scene to populate the graph.'
            };
        }
    } catch (e) {
        error = 'Failed to load workflow graph: ' + (e.message || '');
    }
    loading = false;
    m.redraw();
}

// Neutral, informational empty-state panel (states a/b/c). Deliberately NOT the red `error` style —
// these are honest "nothing to draw" outcomes, not failures.
function renderEmptyState() {
    if (!emptyState) return null;
    return m('div', {
        style: 'flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;'
            + 'text-align:center;padding:48px 24px;color:#64748b;',
    }, [
        m('span', { class: 'material-symbols-outlined', style: 'font-size:56px;color:#cbd5e1;margin-bottom:12px;' },
            emptyState.icon || 'info'),
        m('div', { style: 'font-size:16px;font-weight:600;color:#334155;margin-bottom:8px;' }, emptyState.title),
        m('div', { style: 'font-size:13px;max-width:440px;line-height:1.6;' }, emptyState.body),
    ]);
}

async function selectNode(nodeOid) {
    if (selectedNodeId === nodeOid) {
        selectedNodeId = null;
        nodeDetails = null;
        m.redraw();
        return;
    }
    selectedNodeId = nodeOid;
    nodeDetails = null;
    nodeDetailsLoading = true;
    m.redraw();
    try {
        nodeDetails = await nodeView(pb2BookObjectId, nodeOid);
    } catch (e) {
        page.toast('error', 'Failed to load node detail');
    }
    nodeDetailsLoading = false;
    m.redraw();
}

async function doPin(n) {
    if (pinLoading[n.objectId]) return;
    pinLoading[n.objectId] = true;
    m.redraw();
    try {
        let newPinned = !n.pinned;
        let result = await pinNode(pb2BookObjectId, n.objectId, newPinned);
        n.pinned = result.pinned;
        page.toast('success', result.pinned ? 'Node pinned' : 'Node unpinned');
    } catch (e) {
        page.toast('error', 'Pin failed: ' + e.message);
    }
    delete pinLoading[n.objectId];
    m.redraw();
}

async function doRegen(n) {
    if (regenLoading[n.objectId] || n.pinned) return;
    regenLoading[n.objectId] = true;
    m.redraw();
    try {
        await regenerateNode(pb2BookObjectId, n.objectId);
        // Mark stale locally so the badge updates immediately (graph reload would confirm)
        n.status = 'STALE';
        page.toast('success', 'Node marked for regeneration');
        // Reload the full graph to reflect cascaded staleness
        await loadGraph(bookGroupObjectId);
    } catch (e) {
        page.toast('error', 'Regenerate failed: ' + e.message);
    }
    delete regenLoading[n.objectId];
    m.redraw();
}

async function doTest(n) {
    if (testLoading[n.objectId] || n.pinned) return;
    let nodeOid = n.objectId;
    let wasSelected = selectedNodeId === nodeOid;
    testLoading[nodeOid] = true;
    m.redraw();
    try {
        let result = await testNode(pb2BookObjectId, nodeOid);
        let revMsg = result.artifactRevision != null ? ' (rev ' + result.artifactRevision + ')' : '';
        let downMsg = result.downstreamMarked ? ' — ' + result.downstreamMarked + ' downstream marked stale' : '';
        page.toast('success', 'Executed' + revMsg + downMsg);
        await loadGraph(bookGroupObjectId);
        if (wasSelected) {
            // Re-select so the detail panel shows the new artifact
            await selectNode(nodeOid);
        }
    } catch (e) {
        page.toast('error', 'Test failed: ' + e.message);
    }
    delete testLoading[nodeOid];
    m.redraw();
}

async function doAddMembers() {
    let names = memberNames.split(/[\s,]+/).filter(Boolean);
    if (!names.length) return;
    try {
        let result = await addMembers(pb2BookObjectId, names, false);
        page.toast('success', 'Enrolled ' + result.enrolled + '/' + result.requested);
        memberDialog = false;
        memberNames = '';
    } catch (e) {
        page.toast('error', 'Share failed: ' + e.message);
    }
    m.redraw();
}

// N3 — let the user choose the source manuscript (data.data) this chapter is cut from.
function openChapterSourcePicker() {
    ObjectPicker.open({
        type: 'data.data',
        title: 'Select the source manuscript for this chapter',
        onSelect: function (item) {
            if (!item) return;
            chapterSourceData = { objectId: item.objectId, name: item.name || 'Untitled' };
            // Selecting a new manuscript invalidates any previously detected ranges.
            boundaryRows = [];
            selectedBoundaryIdx = -1;
            boundaryError = null;
            m.redraw();
        }
    });
}

// N3 — detect chapter-heading boundaries for the chosen manuscript, as editable rows.
async function doDetectBoundaries() {
    if (!chapterSourceData || detectingBoundaries) return;
    detectingBoundaries = true;
    boundaryError = null;
    m.redraw();
    try {
        let ranges = await detectBoundaries(chapterSourceData.objectId);
        boundaryRows = (ranges || []).map(function (r) {
            return {
                startOffset: r.startOffset != null ? r.startOffset : 0,
                endOffset: r.endOffset != null ? r.endOffset : 0,
                title: r.title != null ? r.title : '',
            };
        });
        // Default selection: first row that carries a real heading (front-matter has none), else the first.
        if (boundaryRows.length) {
            let firstTitled = boundaryRows.findIndex(function (r) { return r.title && r.title.trim().length; });
            selectedBoundaryIdx = firstTitled >= 0 ? firstTitled : 0;
        } else {
            selectedBoundaryIdx = -1;
            boundaryError = 'No chapter boundaries detected — the document has no usable text or no headings. '
                + 'Add a range manually, or leave unselected to cover the whole document.';
        }
    } catch (e) {
        boundaryError = 'Boundary detection failed: ' + (e.message || '');
        boundaryRows = [];
        selectedBoundaryIdx = -1;
    }
    detectingBoundaries = false;
    m.redraw();
}

// N3 — manual-override helpers for the editable boundary list.
function addBoundaryRow() {
    let last = boundaryRows.length ? boundaryRows[boundaryRows.length - 1] : null;
    let start = last ? (last.endOffset || 0) : 0;
    boundaryRows.push({ startOffset: start, endOffset: start, title: '' });
    selectedBoundaryIdx = boundaryRows.length - 1;
    m.redraw();
}

function removeBoundaryRow(idx) {
    boundaryRows.splice(idx, 1);
    if (selectedBoundaryIdx === idx) selectedBoundaryIdx = -1;
    else if (selectedBoundaryIdx > idx) selectedBoundaryIdx -= 1;
    m.redraw();
}

async function doCreateChapter() {
    if (!chapterSlug.trim()) { page.toast('error', 'Slug is required'); return; }
    if (creatingChapter) return;
    // If a boundary row is selected, validate its span before sending.
    let opts = {};
    if (chapterSourceData) opts.sourceDataObjectId = chapterSourceData.objectId;
    if (selectedBoundaryIdx >= 0 && boundaryRows[selectedBoundaryIdx]) {
        let r = boundaryRows[selectedBoundaryIdx];
        let start = Math.round(Number(r.startOffset));
        let end = Math.round(Number(r.endOffset));
        if (!(end > start)) {
            page.toast('error', 'Selected range is invalid: endOffset must be greater than startOffset');
            return;
        }
        opts.sourceRange = { startOffset: start, endOffset: end, title: (r.title || '').trim() || null };
    }
    creatingChapter = true;
    m.redraw();
    try {
        let result = await createChapter(pb2BookObjectId, chapterSlug.trim(), chapterTitle.trim() || null,
            null, null, opts);
        page.toast('success', 'Chapter created: ' + result.slug);
        resetChapterDialogState();
    } catch (e) {
        page.toast('error', 'Chapter failed: ' + e.message);
        creatingChapter = false;
    }
    m.redraw();
}

// ── SVG edges ─────────────────────────────────────────────────────────

function renderEdges() {
    if (!graphData || !graphData.edges) return null;
    let elements = [];
    for (let e of graphData.edges) {
        let src = e.sourceNodeObjectId;
        let dst = e.consumerObjectId;
        if (!src || !dst) continue;
        let sp = positions[src];
        let dp = positions[dst];
        if (!sp || !dp) continue;
        let x1 = sp.x + CARD_W, y1 = sp.y + CARD_H / 2;
        let x2 = dp.x, y2 = dp.y + CARD_H / 2;
        let cx = (x1 + x2) / 2;
        let mx = (x1 + x2) / 2;
        let my = (y1 + y2) / 2;
        let edgeKey = e.objectId || (src + '-' + dst);
        elements.push(m('path', {
            key: edgeKey,
            d: 'M' + x1 + ',' + y1 + ' C' + cx + ',' + y1 + ' ' + cx + ',' + y2 + ' ' + x2 + ',' + y2,
            fill: 'none',
            stroke: '#94a3b8',
            'stroke-width': '1.5',
            opacity: '0.6',
            style: 'pointer-events:none;',
        }));
        // Delete button at curve midpoint — only when binding has a known objectId
        if (e.objectId) {
            let bindingId = e.objectId;
            elements.push(m('g', {
                key: 'del-' + edgeKey,
                style: 'pointer-events:auto;cursor:pointer;',
                onclick: function () { doDeleteBinding(bindingId); },
            }, [
                m('circle', { cx: mx, cy: my, r: 7, fill: '#fff', stroke: '#ef4444', 'stroke-width': 1.5 }),
                m('text', {
                    x: mx, y: my + 4,
                    'text-anchor': 'middle',
                    'font-size': '10',
                    fill: '#ef4444',
                    style: 'user-select:none;',
                }, '×'),
            ]));
        }
    }
    let sz = canvasSize();
    return m('svg', {
        style: 'position:absolute;top:0;left:0;pointer-events:none;',
        width: sz.w,
        height: sz.h,
    }, elements);
}

// ── Node cards ────────────────────────────────────────────────────────

function renderArtifactThumb(artifacts) {
    if (!artifacts) return null;
    for (let role of Object.keys(artifacts)) {
        let list = artifacts[role];
        if (!list || !list.length) continue;
        let art = list.find(function (a) { return a.selected; }) || list[0];
        if (!art) continue;
        if (art.dataObjectId && art.mimeType && art.mimeType.startsWith('image/')) {
            return m('img', {
                src: imageUrl(art.dataObjectId),
                style: 'width:40px;height:40px;object-fit:cover;border-radius:4px;flex-shrink:0;',
                loading: 'lazy',
            });
        }
    }
    return null;
}

function renderNodeCard(n) {
    let p = positions[n.objectId];
    if (!p) return null;
    let status = (n.status || n.storedStatus || 'UNKNOWN').toUpperCase();
    let selected = selectedNodeId === n.objectId;
    let details = (selected && nodeDetails) ? nodeDetails : null;
    let thumb = details ? renderArtifactThumb(details.artifacts) : null;

    return m('div', {
        key: n.objectId,
        'data-node-id': n.objectId,
        style: [
            'position:absolute',
            'left:' + p.x + 'px',
            'top:' + p.y + 'px',
            'width:' + CARD_W + 'px',
            'background:var(--card-bg,#fff)',
            'border:2px solid ' + (selected ? '#3b82f6' : '#e2e8f0'),
            'border-radius:8px',
            'padding:8px',
            'box-shadow:0 1px 4px rgba(0,0,0,.1)',
            'cursor:pointer',
            'user-select:none',
            'box-sizing:border-box',
        ].join(';'),
        onclick: function (e) { e.stopPropagation(); selectNode(n.objectId); },
        onmousedown: function (e) { onNodeMouseDown(e, n.objectId); },
    }, [
        // Input port — left edge (drop target for incoming bindings)
        m('div', {
            'data-port-in': n.objectId,
            style: 'position:absolute;left:-6px;top:50%;transform:translateY(-50%);width:12px;height:12px;border-radius:50%;background:#34d399;cursor:crosshair;z-index:10;',
            title: 'Drop here to receive binding',
            onmouseup: function (e) { endEdgeDrag(e, n.objectId); },
        }),
        // Output port — right edge (drag source for outgoing bindings)
        m('div', {
            'data-port-out': n.objectId,
            style: 'position:absolute;right:-6px;top:50%;transform:translateY(-50%);width:12px;height:12px;border-radius:50%;background:#818cf8;cursor:crosshair;z-index:10;',
            title: 'Drag to create binding',
            onmousedown: function (e) { startEdgeDrag(e, n.objectId); },
        }),
        // Header: handle + status badge
        m('div', { style: 'display:flex;align-items:center;gap:6px;margin-bottom:6px;' }, [
            m('span', {
                style: [
                    'font-size:10px;font-weight:700;padding:2px 6px;border-radius:9999px;color:#fff;flex-shrink:0;',
                    'background:' + statusColor(status),
                ].join(''),
            }, status),
            m('span', { style: 'font-size:11px;color:#374151;font-weight:600;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;' },
                n.handle || n.nodeType || '?'),
        ]),

        // Thumb (when node selected and detail loaded)
        thumb ? m('div', { style: 'margin-bottom:6px;' }, thumb) : null,

        // Stale indicator
        status === 'STALE' ? m('div', { style: 'font-size:10px;color:#d97706;margin-bottom:4px;' }, '⚠ Stale — inputs changed') : null,

        // Actions row
        m('div', { style: 'display:flex;gap:4px;margin-top:4px;flex-wrap:wrap;align-items:center;' }, [
            // Pin toggle
            m('button', {
                title: n.pinned ? 'Unpin' : 'Pin',
                style: [
                    'border:none;background:none;cursor:pointer;padding:2px 4px;border-radius:4px;font-size:14px;',
                    n.pinned ? 'color:#f59e0b;' : 'color:#94a3b8;',
                ].join(''),
                onclick: function (e) { e.stopPropagation(); doPin(n); },
                disabled: !!pinLoading[n.objectId],
            }, pinLoading[n.objectId] ? '…' : (n.pinned ? '📌' : '📍')),

            // Test button — execute this node against the SD/LLM backend right now
            !n.pinned ? m('button', {
                title: 'Execute this node now',
                style: [
                    'border:1px solid #6366f1;border-radius:4px;padding:1px 6px;cursor:pointer;',
                    'font-size:11px;color:#6366f1;background:none;',
                    testLoading[n.objectId] ? 'opacity:.6;' : '',
                ].join(''),
                onclick: function (e) { e.stopPropagation(); doTest(n); },
                disabled: !!testLoading[n.objectId],
            }, testLoading[n.objectId] ? '…' : '▶ Test') : null,

            // Regenerate (mark stale) — for DONE and DONE_UNVERIFIED, not pinned
            (status === 'DONE' || status === 'DONE_UNVERIFIED') && !n.pinned ? m('button', {
                title: 'Mark stale for regeneration',
                style: 'border:none;background:none;cursor:pointer;padding:2px 4px;border-radius:4px;font-size:14px;color:#6b7280;',
                onclick: function (e) { e.stopPropagation(); doRegen(n); },
                disabled: !!regenLoading[n.objectId],
            }, regenLoading[n.objectId] ? '…' : '🔄') : null,

            // Scene index chip
            n.sceneIndex != null ? m('span', {
                style: 'font-size:10px;color:#64748b;padding:1px 5px;border-radius:9999px;background:#f1f5f9;margin-left:auto;',
            }, 'S' + n.sceneIndex) : null,
        ]),
    ]);
}

// ── Artifact history panel (inside node detail panel) ─────────────────

function renderArtifactHistory(role, list) {
    if (!list || !list.length) return null;
    return m('div', { style: 'margin-bottom:8px;' }, [
        m('div', { style: 'font-size:11px;font-weight:600;color:#374151;margin-bottom:4px;text-transform:capitalize;' }, role),
        m('div', { style: 'display:flex;gap:6px;flex-wrap:wrap;' },
            list.map(function (art) {
                return m('div', {
                    key: art.objectId,
                    style: [
                        'border:2px solid ' + (art.selected ? '#3b82f6' : '#e2e8f0'),
                        'border-radius:6px;overflow:hidden;',
                    ].join(''),
                    title: 'Rev ' + (art.revision || '?') + (art.selected ? ' (current)' : ''),
                }, art.dataObjectId && art.mimeType && art.mimeType.startsWith('image/')
                    ? m('img', {
                        src: imageUrl(art.dataObjectId),
                        style: 'width:60px;height:60px;object-fit:cover;display:block;',
                        loading: 'lazy',
                    })
                    : m('div', { style: 'width:60px;height:60px;display:flex;align-items:center;justify-content:center;font-size:10px;color:#64748b;' },
                        'r' + (art.revision || '?')));
            })
        ),
    ]);
}

function renderNodeDetailPanel() {
    if (!selectedNodeId) return null;
    let n = graphData && graphData.nodes && graphData.nodes.find(function (x) { return x.objectId === selectedNodeId; });
    if (!n) return null;

    return m('div', {
        style: [
            'position:fixed;right:16px;top:80px;width:280px;max-height:calc(100vh - 120px);',
            'background:var(--card-bg,#fff);border:1px solid #e2e8f0;border-radius:10px;',
            'box-shadow:0 4px 16px rgba(0,0,0,.12);overflow-y:auto;padding:16px;z-index:200;',
        ].join(''),
    }, [
        m('div', { style: 'display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;' }, [
            m('span', { style: 'font-weight:700;font-size:14px;color:#111;' }, n.handle || n.nodeType),
            m('button', {
                style: 'border:none;background:none;cursor:pointer;font-size:16px;color:#94a3b8;',
                onclick: function () { selectedNodeId = null; nodeDetails = null; m.redraw(); }
            }, '×'),
        ]),

        m('div', { style: 'margin-bottom:8px;' }, [
            m('span', {
                style: 'font-size:11px;font-weight:700;padding:2px 8px;border-radius:9999px;color:#fff;background:' + statusColor(n.status || n.storedStatus),
            }, (n.status || n.storedStatus || 'UNKNOWN').toUpperCase()),
            n.pinned ? m('span', { style: 'margin-left:6px;font-size:11px;color:#f59e0b;' }, '📌 Pinned') : null,
        ]),

        n.lastError ? m('div', { style: 'font-size:11px;color:#dc2626;background:#fef2f2;border-radius:4px;padding:6px;margin-bottom:8px;word-break:break-word;' },
            n.lastError) : null,

        nodeDetailsLoading ? m('div', { style: 'font-size:12px;color:#64748b;' }, 'Loading…') : null,

        nodeDetails && nodeDetails.artifacts
            ? m('div', { style: 'margin-top:8px;' }, [
                m('div', { style: 'font-size:12px;font-weight:600;color:#374151;margin-bottom:6px;' }, 'Artifact Revisions'),
                Object.keys(nodeDetails.artifacts).map(function (role) {
                    return renderArtifactHistory(role, nodeDetails.artifacts[role]);
                }),
            ])
            : null,

        nodeDetails && nodeDetails.bindings && nodeDetails.bindings.length
            ? m('div', { style: 'margin-top:8px;' }, [
                m('div', { style: 'font-size:12px;font-weight:600;color:#374151;margin-bottom:6px;' }, 'Bindings'),
                nodeDetails.bindings.map(function (b) {
                    return m('div', {
                        key: b.objectId,
                        style: 'font-size:11px;color:#374151;padding:3px 0;border-bottom:1px solid #f1f5f9;',
                    }, [
                        m('span', { style: 'font-weight:600;' }, b.role || '?'),
                        ' — ',
                        m('span', { style: 'color:' + (b.required ? '#dc2626' : '#6b7280') + ';' },
                            b.required ? 'required' : 'optional'),
                    ]);
                }),
            ])
            : null,
    ]);
}

// ── Dialogs ───────────────────────────────────────────────────────────

function renderMemberDialog() {
    if (!memberDialog) return null;
    return m('div', {
        style: 'position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:500;display:flex;align-items:center;justify-content:center;',
        onclick: function () { memberDialog = false; m.redraw(); }
    }, m('div', {
        style: 'background:#fff;border-radius:10px;padding:24px;width:360px;',
        onclick: function (e) { e.stopPropagation(); }
    }, [
        m('h3', { style: 'font-weight:700;font-size:16px;margin-bottom:12px;' }, 'Share Book'),
        m('p', { style: 'font-size:12px;color:#64748b;margin-bottom:8px;' }, 'Enter usernames (comma- or space-separated) to grant Writer access:'),
        m('textarea', {
            style: 'width:100%;border:1px solid #e2e8f0;border-radius:6px;padding:8px;font-size:13px;resize:none;box-sizing:border-box;',
            rows: 3,
            placeholder: 'user1, user2',
            value: memberNames,
            oninput: function (e) { memberNames = e.target.value; },
        }),
        m('div', { style: 'display:flex;gap:8px;margin-top:12px;justify-content:flex-end;' }, [
            m('button', { style: 'btn px-4 py-2 text-gray-600 border rounded;', onclick: function () { memberDialog = false; m.redraw(); } }, 'Cancel'),
            m('button', { class: 'btn px-4 py-2 bg-blue-600 text-white rounded', onclick: doAddMembers }, 'Share'),
        ]),
    ]));
}

// N3 — one editable boundary row (radio-select + title + start/end offsets + char length).
function renderBoundaryRow(r, idx) {
    let selected = selectedBoundaryIdx === idx;
    let len = (Number(r.endOffset) || 0) - (Number(r.startOffset) || 0);
    let isFrontMatter = !(r.title && r.title.trim().length);
    return m('div', {
        key: idx,
        'data-boundary-row': idx,
        style: [
            'display:flex;align-items:center;gap:6px;padding:6px;border-radius:6px;margin-bottom:4px;',
            'border:1px solid ' + (selected ? '#3b82f6' : '#e2e8f0'),
            selected ? 'background:#eff6ff;' : '',
        ].join(''),
    }, [
        m('input', {
            type: 'radio',
            name: 'pb-boundary-select',
            checked: selected,
            'data-boundary-select': idx,
            onchange: function () { selectedBoundaryIdx = idx; m.redraw(); },
            style: 'flex-shrink:0;cursor:pointer;',
        }),
        m('input', {
            type: 'text',
            'data-boundary-title': idx,
            value: r.title || '',
            placeholder: isFrontMatter ? '(front matter)' : 'Chapter title',
            oninput: function (e) { r.title = e.target.value; },
            style: 'flex:1;min-width:0;border:1px solid #e2e8f0;border-radius:4px;padding:4px 6px;font-size:12px;'
                + (isFrontMatter ? 'color:#94a3b8;font-style:italic;' : ''),
        }),
        m('input', {
            type: 'number',
            'data-boundary-start': idx,
            value: r.startOffset,
            title: 'startOffset (inclusive)',
            oninput: function (e) { r.startOffset = e.target.value === '' ? '' : parseInt(e.target.value, 10); },
            style: 'width:74px;border:1px solid #e2e8f0;border-radius:4px;padding:4px 6px;font-size:12px;',
        }),
        m('input', {
            type: 'number',
            'data-boundary-end': idx,
            value: r.endOffset,
            title: 'endOffset (exclusive)',
            oninput: function (e) { r.endOffset = e.target.value === '' ? '' : parseInt(e.target.value, 10); },
            style: 'width:74px;border:1px solid #e2e8f0;border-radius:4px;padding:4px 6px;font-size:12px;',
        }),
        m('span', {
            style: 'font-size:10px;color:#64748b;width:64px;text-align:right;flex-shrink:0;',
            title: 'character length',
        }, (len > 0 ? len.toLocaleString() : '0') + ' ch'),
        m('button', {
            title: 'Remove this range',
            'data-boundary-remove': idx,
            style: 'border:none;background:none;cursor:pointer;color:#ef4444;font-size:14px;flex-shrink:0;',
            onclick: function () { removeBoundaryRow(idx); },
        }, '×'),
    ]);
}

function renderChapterDialog() {
    if (!chapterDialog) return null;
    return m('div', {
        style: 'position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:500;display:flex;align-items:center;justify-content:center;',
        onclick: function () { resetChapterDialogState(); m.redraw(); }
    }, m('div', {
        style: 'background:#fff;border-radius:10px;padding:24px;width:560px;max-width:92vw;max-height:88vh;overflow-y:auto;',
        onclick: function (e) { e.stopPropagation(); }
    }, [
        m('h3', { style: 'font-weight:700;font-size:16px;margin-bottom:12px;' }, 'New Chapter'),
        m('label', { style: 'font-size:12px;font-weight:600;color:#374151;' }, 'Slug (URL-safe, unique)'),
        m('input', {
            style: 'width:100%;border:1px solid #e2e8f0;border-radius:6px;padding:8px;font-size:13px;box-sizing:border-box;margin-bottom:10px;',
            placeholder: 'my-chapter-2',
            value: chapterSlug,
            oninput: function (e) { chapterSlug = e.target.value; },
        }),
        m('label', { style: 'font-size:12px;font-weight:600;color:#374151;' }, 'Title (optional)'),
        m('input', {
            style: 'width:100%;border:1px solid #e2e8f0;border-radius:6px;padding:8px;font-size:13px;box-sizing:border-box;margin-bottom:14px;',
            placeholder: 'Chapter Two',
            value: chapterTitle,
            oninput: function (e) { chapterTitle = e.target.value; },
        }),

        // N3 — source manuscript + boundary review
        m('div', { style: 'border-top:1px solid #f1f5f9;padding-top:12px;' }, [
            m('label', { style: 'font-size:12px;font-weight:600;color:#374151;' }, 'Source manuscript (optional)'),
            m('div', { style: 'font-size:11px;color:#64748b;margin-bottom:6px;' },
                'Pick the document this chapter is cut from, then detect its chapter boundaries and select the range for this chapter.'),
            m('div', { style: 'display:flex;gap:8px;align-items:center;margin-bottom:10px;' }, [
                m('button', {
                    'data-pick-source': true,
                    style: 'border:1px solid #3b82f6;color:#3b82f6;background:none;border-radius:6px;padding:6px 12px;cursor:pointer;font-size:12px;',
                    onclick: openChapterSourcePicker,
                }, chapterSourceData ? '📄 Change manuscript' : '📄 Choose manuscript'),
                chapterSourceData ? m('span', {
                    style: 'font-size:12px;color:#334155;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;',
                    title: chapterSourceData.name,
                }, chapterSourceData.name) : null,
            ]),

            chapterSourceData ? m('div', { style: 'margin-bottom:10px;' }, [
                m('button', {
                    'data-detect-boundaries': true,
                    style: 'border:1px solid #a855f7;color:#7c3aed;background:none;border-radius:6px;padding:6px 12px;cursor:pointer;font-size:12px;'
                        + (detectingBoundaries ? 'opacity:.6;' : ''),
                    disabled: detectingBoundaries,
                    onclick: doDetectBoundaries,
                }, detectingBoundaries ? 'Detecting…' : '🔍 Detect chapter boundaries'),
            ]) : null,

            boundaryError ? m('div', {
                style: 'font-size:11px;color:#b45309;background:#fffbeb;border:1px solid #fde68a;border-radius:6px;padding:8px;margin-bottom:8px;',
            }, boundaryError) : null,

            (chapterSourceData && boundaryRows.length) ? m('div', { style: 'margin-bottom:8px;' }, [
                m('div', { style: 'display:flex;align-items:center;justify-content:space-between;margin-bottom:6px;' }, [
                    m('span', { style: 'font-size:12px;font-weight:600;color:#374151;' },
                        'Detected ranges — edit titles/offsets, select this chapter’s range'),
                ]),
                m('div', { 'data-boundary-list': true }, boundaryRows.map(function (r, i) { return renderBoundaryRow(r, i); })),
                m('button', {
                    'data-add-boundary': true,
                    style: 'border:1px dashed #cbd5e1;color:#64748b;background:none;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:11px;margin-top:4px;',
                    onclick: addBoundaryRow,
                }, '+ Add range'),
                selectedBoundaryIdx < 0 ? m('div', { style: 'font-size:11px;color:#64748b;margin-top:6px;' },
                    'No range selected — the chapter will cover the whole manuscript.') : null,
            ]) : null,
        ]),

        m('div', { style: 'display:flex;gap:8px;margin-top:16px;justify-content:flex-end;' }, [
            m('button', { style: 'border:1px solid #e2e8f0;border-radius:6px;padding:6px 14px;cursor:pointer;', onclick: function () { resetChapterDialogState(); m.redraw(); } }, 'Cancel'),
            m('button', {
                'data-create-chapter': true,
                style: 'background:#3b82f6;color:#fff;border:none;border-radius:6px;padding:6px 14px;cursor:pointer;font-weight:600;' + (creatingChapter ? 'opacity:.6;' : ''),
                disabled: creatingChapter,
                onclick: doCreateChapter,
            }, creatingChapter ? 'Creating…' : 'Create'),
        ]),
    ]));
}

// ── Stale recheck ─────────────────────────────────────────────────────

async function doRecheckStale() {
    if (!pb2BookObjectId || recheckingStale) return;
    recheckingStale = true;
    m.redraw();
    try {
        let staleList = await listStale(pb2BookObjectId);
        page.toast('info', (staleList.length || 0) + ' stale node(s) detected — reloading graph');
        await loadGraph(bookGroupObjectId);
    } catch (e) {
        page.toast('error', 'Stale recheck failed: ' + e.message);
    }
    recheckingStale = false;
    m.redraw();
}

// ── Binding management ────────────────────────────────────────────────

function startEdgeDrag(e, nodeId) {
    pendingEdgeSrc = nodeId;
    e.stopPropagation();
    e.preventDefault();
}

function endEdgeDrag(e, targetNodeId) {
    if (!pendingEdgeSrc || pendingEdgeSrc === targetNodeId) {
        pendingEdgeSrc = null;
        return;
    }
    let srcId = pendingEdgeSrc;
    pendingEdgeSrc = null;
    if (!pb2BookObjectId) return;
    addBinding(pb2BookObjectId, targetNodeId, 'source', srcId)
        .then(function () {
            page.toast('success', 'Binding created');
            return loadGraph(bookGroupObjectId);
        })
        .catch(function (err) {
            page.toast('error', 'Add binding failed: ' + err.message);
        });
}

async function doDeleteBinding(bindingObjectId) {
    if (!pb2BookObjectId) return;
    try {
        await deleteBinding(pb2BookObjectId, bindingObjectId);
        page.toast('success', 'Binding removed');
        await loadGraph(bookGroupObjectId);
    } catch (e) {
        page.toast('error', 'Delete binding failed: ' + e.message);
    }
    m.redraw();
}

// ── Canvas interaction ────────────────────────────────────────────────

function onNodeMouseDown(e, nodeId) {
    if (e.button !== 0) return;
    // Don't start a node drag when clicking a button inside the card
    if (e.target.closest && e.target.closest('button')) return;
    draggingNodeId = nodeId;
    dragStartClient = { x: e.clientX, y: e.clientY };
    dragStartPos = { ...(positions[nodeId] || { x: 0, y: 0 }) };
    e.stopPropagation();
}

function onCanvasMouseDown(e) {
    if (e.button !== 0) return;
    // Only start drag on background (not on a node card)
    if (e.target.closest && e.target.closest('[data-node-id]')) return;
    dragging = true;
    dragStart = { x: e.clientX, y: e.clientY };
    panStart = { x: pan.x, y: pan.y };
    e.preventDefault();
}

function onCanvasMouseMove(e) {
    if (draggingNodeId) {
        let dx = (e.clientX - dragStartClient.x) / zoom;
        let dy = (e.clientY - dragStartClient.y) / zoom;
        positions[draggingNodeId] = {
            x: dragStartPos.x + dx,
            y: dragStartPos.y + dy,
        };
        m.redraw();
        return;
    }
    if (!dragging) return;
    pan.x = panStart.x + (e.clientX - dragStart.x);
    pan.y = panStart.y + (e.clientY - dragStart.y);
    m.redraw();
}

function onCanvasMouseUp() {
    if (draggingNodeId) {
        let nodeId = draggingNodeId;
        let pos = positions[nodeId];
        draggingNodeId = null;
        dragStartClient = null;
        dragStartPos = null;
        if (pb2BookObjectId && pos) {
            saveCanvas(pb2BookObjectId, nodeId, pos).catch(function (err) {
                page.toast('error', 'Save position failed: ' + err.message);
            });
        }
        m.redraw();
        return;
    }
    dragging = false;
    pendingEdgeSrc = null;
}

function onCanvasWheel(e) {
    e.preventDefault();
    let delta = e.deltaY > 0 ? 0.9 : 1.1;
    zoom = Math.max(0.3, Math.min(3, zoom * delta));
    m.redraw();
}

// ── N4 — whole-series view ────────────────────────────────────────────

async function loadSeriesChapters() {
    if (seriesChaptersLoading) return;
    seriesChaptersLoading = true;
    seriesChaptersError = null;
    seriesChapters = null;
    m.redraw();
    try {
        // The current chapter's series is the FK workflowView now surfaces. A null seriesObjectId
        // means THIS book is standalone (not part of a series) — an honest empty state, not an error,
        // so leave seriesChapters null and let renderSeriesView show the standalone message.
        let seriesOid = graphData && graphData.seriesObjectId;
        if (seriesOid) {
            // listSeriesBooks returns THIS series' chapters visible to the caller (entitled non-owners
            // included). Group/order via the shared pure helper: one series in, one ordered group out.
            let books = await listSeriesBooks(seriesOid);
            seriesChapters = groupBooksBySeries(books || []);
        }
    } catch (e) {
        // 404 (series deleted) is distinct from an empty array (no entitlement / no chapters): the
        // former throws with .notFound, the latter resolves to [] and never reaches here.
        seriesChaptersError = (e && e.notFound)
            ? 'This series no longer exists — it may have been deleted.'
            : 'Failed to load series: ' + ((e && e.message) || '');
        seriesChapters = null;
    }
    seriesChaptersLoaded = true;
    seriesChaptersLoading = false;
    m.redraw();
}

function setViewMode(mode) {
    if (viewMode === mode) return;
    viewMode = mode;
    if (mode === 'series' && !seriesChaptersLoaded && !seriesChaptersLoading) {
        loadSeriesChapters();
    }
    m.redraw();
}

// One chapter tile in the whole-series overview. Clicking zooms into that chapter's workflow graph.
function renderSeriesChapterTile(b) {
    let isCurrent = b && (b.objectId === pb2BookObjectId);
    return m('div', {
        key: b.objectId,
        'data-series-chapter': b.objectId,
        style: [
            'border:2px solid ' + (isCurrent ? '#3b82f6' : '#e2e8f0'),
            'border-radius:8px;padding:12px;cursor:pointer;background:' + (isCurrent ? '#eff6ff' : '#fff') + ';',
            'width:200px;box-sizing:border-box;transition:border-color .1s;',
        ].join(''),
        onclick: function () {
            // Zoom to a single chapter: load that book's workflow graph in place.
            m.route.set('/picture-book/' + b.objectId + '/workflow');
        },
    }, [
        m('div', { style: 'display:flex;align-items:center;gap:6px;margin-bottom:6px;' }, [
            b.chapter != null ? m('span', {
                style: 'font-size:10px;font-weight:700;color:#fff;background:#6366f1;border-radius:9999px;padding:2px 7px;flex-shrink:0;',
            }, 'Ch ' + b.chapter) : null,
            m('span', { style: 'font-size:11px;font-weight:700;padding:2px 7px;border-radius:9999px;color:#fff;flex-shrink:0;background:' + statusColor(b.bookStatus) },
                (b.bookStatus || 'UNKNOWN').toUpperCase()),
        ]),
        m('div', { style: 'font-size:13px;font-weight:600;color:#111;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;', title: b.name || b.slug },
            b.name || b.slug || '(untitled)'),
        b.slug ? m('div', { style: 'font-size:11px;color:#64748b;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;', title: b.slug }, b.slug) : null,
        isCurrent ? m('div', { style: 'font-size:10px;color:#3b82f6;font-weight:600;margin-top:4px;' }, '● current') : null,
    ]);
}

// A group header label that never leaks the raw series objectId: groupBooksBySeries falls back to the
// series KEY (the objectId) when the DTO carries no series name — and our listSeriesBooks DTO does not
// provide one — so when the name is just the key, show the neutral "Series" label instead.
function seriesGroupLabel(g) {
    if (g && g.seriesName && g.seriesName !== g.seriesKey) return g.seriesName;
    return 'Series';
}

function renderSeriesView() {
    let standalone = !seriesChaptersLoading && !seriesChaptersError && !seriesChapters
        && !!(graphData && !graphData.seriesObjectId);
    let totalChapters = seriesChapters
        ? seriesChapters.groups.reduce(function (n, g) { return n + g.chapters.length; }, 0) : 0;
    return m('div', { 'data-series-view': true, style: 'flex:1;overflow-y:auto;padding:20px;background:#f8fafc;' }, [
        seriesChaptersLoading ? m('div', { style: 'text-align:center;color:#64748b;padding:40px;' }, 'Loading series…') : null,
        seriesChaptersError ? m('div', { 'data-series-error': true, style: 'text-align:center;color:#dc2626;padding:40px;' }, seriesChaptersError) : null,
        // Honest standalone state: THIS book is not part of a series (workflowView.seriesObjectId is null).
        standalone ? m('div', {
            'data-series-standalone': true,
            style: 'text-align:center;color:#64748b;padding:40px;line-height:1.6;',
        }, [
            m('div', { style: 'font-size:15px;font-weight:600;color:#334155;margin-bottom:6px;' }, 'Not part of a series'),
            m('div', { style: 'font-size:13px;' },
                'This book is standalone — it has no series, so there are no sibling chapters to show. '
                + 'Chapters created together as a series appear here.'),
        ]) : null,
        (!seriesChaptersLoading && !seriesChaptersError && seriesChapters) ? [
            totalChapters === 0
                // Series exists but nothing is visible to this caller (no entitlement, or empty series).
                ? m('div', { 'data-series-empty': true, style: 'text-align:center;color:#64748b;padding:40px;line-height:1.6;' }, [
                    m('div', { style: 'font-size:15px;font-weight:600;color:#334155;margin-bottom:6px;' }, 'No chapters to show'),
                    m('div', { style: 'font-size:13px;' },
                        'This series has no chapters you can access. Ask the series owner to add you as a writer.'),
                ])
                : seriesChapters.groups.map(function (g) {
                    return m('div', { key: g.seriesKey || 'series', 'data-series-group': g.seriesKey || 'series', style: 'margin-bottom:24px;' }, [
                        m('div', { style: 'font-size:14px;font-weight:700;color:#334155;margin-bottom:10px;' },
                            seriesGroupLabel(g) + ' — ' + g.chapters.length + ' chapter' + (g.chapters.length === 1 ? '' : 's')),
                        m('div', { style: 'display:flex;flex-wrap:wrap;gap:12px;' },
                            g.chapters.map(function (b) { return renderSeriesChapterTile(b); })),
                    ]);
                }),
        ] : null,
    ]);
}

// ── Main view ─────────────────────────────────────────────────────────

var pictureBookWorkflowView = {
    oninit: function (vnode) {
        // Only init on first call (route oninit, which carries route params) — skip when re-rendered
        // as m(pictureBookWorkflowView) with no attrs. Without this guard the child re-render calls
        // loadGraph(undefined) → 404 → empty-state (a), clobbering the real graph loaded by the route
        // oninit. Matches the sibling guard in pictureBook.js pictureBookView.oninit.
        if (!vnode.attrs.bookObjectId) return;
        bookGroupObjectId = vnode.attrs.bookObjectId;
        pb2BookObjectId = null;
        bookName = '';
        error = null;
        emptyState = null;
        graphData = null;
        positions = {};
        selectedNodeId = null;
        nodeDetails = null;
        nodeDetailsLoading = false;
        pan = { x: 0, y: 0 };
        zoom = 1;
        dragging = false;
        draggingNodeId = null;
        dragStartClient = null;
        dragStartPos = null;
        pendingEdgeSrc = null;
        pinLoading = {};
        regenLoading = {};
        testLoading = {};
        memberDialog = false;
        memberNames = '';
        resetChapterDialogState();
        recheckingStale = false;
        viewMode = 'chapter';
        seriesChapters = null;
        seriesChaptersLoading = false;
        seriesChaptersError = null;
        seriesChaptersLoaded = false;
        loadGraph(bookGroupObjectId);
    },
    view: function () {
        let sz = canvasSize();

        return m('div', { style: 'display:flex;flex-direction:column;height:100%;' }, [
            // Toolbar
            m('div', { style: 'display:flex;align-items:center;gap:8px;padding:8px 16px;border-bottom:1px solid #e2e8f0;flex-shrink:0;' }, [
                m('span', { class: 'material-symbols-outlined', style: 'color:#3b82f6;' }, 'account_tree'),
                m('span', { style: 'font-weight:700;font-size:15px;' }, bookName ? bookName + ' — Workflow' : 'Workflow Graph'),
                loading ? m('span', { style: 'font-size:12px;color:#64748b;margin-left:8px;' }, 'Loading…') : null,
                graphData ? m('span', { style: 'font-size:12px;color:#64748b;margin-left:8px;' },
                    (graphData.nodeCount || 0) + ' nodes') : null,
                // Stale count badge
                graphData && graphData.nodes ? (function () {
                    let staleCount = graphData.nodes.filter(function (n) {
                        return (n.status || n.storedStatus || '').toUpperCase() === 'STALE';
                    }).length;
                    return staleCount > 0
                        ? m('span', { style: 'font-size:11px;color:#d97706;font-weight:600;margin-left:4px;' },
                            staleCount + ' stale')
                        : null;
                })() : null,
                // Spacer
                m('div', { style: 'flex:1;' }),
                // N4 — view-mode toggle (single-chapter graph ⇄ whole-series overview).
                m('div', {
                    'data-view-mode-toggle': true,
                    style: 'display:inline-flex;border:1px solid #e2e8f0;border-radius:6px;overflow:hidden;margin-right:4px;',
                }, [
                    m('button', {
                        'data-view-mode': 'chapter',
                        title: 'Show this chapter’s workflow graph',
                        style: 'border:none;padding:4px 10px;cursor:pointer;font-size:12px;'
                            + (viewMode === 'chapter' ? 'background:#3b82f6;color:#fff;' : 'background:#fff;color:#334155;'),
                        onclick: function () { setViewMode('chapter'); },
                    }, 'Chapter'),
                    m('button', {
                        'data-view-mode': 'series',
                        title: 'Show all chapters in this series',
                        style: 'border:none;padding:4px 10px;cursor:pointer;font-size:12px;'
                            + (viewMode === 'series' ? 'background:#3b82f6;color:#fff;' : 'background:#fff;color:#334155;'),
                        onclick: function () { setViewMode('series'); },
                    }, 'Series'),
                ]),
                // Recheck stale button — only meaningful for the single-chapter graph.
                (viewMode === 'chapter' && pb2BookObjectId) ? m('button', {
                    title: 'Recompute staleness from backend and reload graph',
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;',
                    onclick: doRecheckStale,
                    disabled: recheckingStale,
                }, recheckingStale ? '…' : '↻ Stale') : null,
                // Zoom controls — graph-only.
                viewMode === 'chapter' ? m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:14px;',
                    onclick: function () { zoom = Math.max(0.3, zoom * 0.85); m.redraw(); }
                }, '−') : null,
                viewMode === 'chapter' ? m('span', { style: 'font-size:12px;min-width:40px;text-align:center;' }, Math.round(zoom * 100) + '%') : null,
                viewMode === 'chapter' ? m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:14px;',
                    onclick: function () { zoom = Math.min(3, zoom * 1.15); m.redraw(); }
                }, '+') : null,
                viewMode === 'chapter' ? m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;',
                    onclick: function () { pan = { x: 0, y: 0 }; zoom = 1; m.redraw(); }
                }, 'Reset') : null,
                // Share button
                pb2BookObjectId ? m('button', {
                    class: 'btn',
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;',
                    onclick: function () { memberDialog = true; m.redraw(); }
                }, '🔗 Share') : null,
                // Chapter button
                pb2BookObjectId ? m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;',
                    onclick: function () { chapterDialog = true; m.redraw(); }
                }, '📖 Chapter') : null,
                // Pages button — PB2 page reader
                pb2BookObjectId ? m('button', {
                    title: 'View scene pages',
                    style: 'border:1px solid #a855f7;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;color:#7c3aed;',
                    onclick: function () { m.route.set('/picture-book/v2/' + pb2BookObjectId); }
                }, '📖 Pages') : null,
                // Back button
                m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;',
                    onclick: function () { m.route.set('/picture-book/' + bookGroupObjectId); }
                }, '← Book'),
            ]),

            // N4 — whole-series overview (distinct view state from the single-chapter graph).
            viewMode === 'series' ? renderSeriesView() : null,

            // Error state (red) — a genuine failure (auth / server / network).
            (viewMode === 'chapter' && error) ? m('div', { style: 'padding:32px;text-align:center;color:#dc2626;' }, error) : null,

            // Empty state (grey) — one of the three honest "no graph to draw" cases (a/b/c).
            (viewMode === 'chapter' && !error && emptyState) ? renderEmptyState() : null,

            // Graph canvas (zoom-to-single-chapter)
            (viewMode === 'chapter' && !error && !emptyState) ? m('div', {
                style: 'flex:1;overflow:hidden;position:relative;background:#f8fafc;cursor:' + (dragging ? 'grabbing' : 'grab') + ';',
                onmousedown: onCanvasMouseDown,
                onmousemove: onCanvasMouseMove,
                onmouseup: onCanvasMouseUp,
                onmouseleave: onCanvasMouseUp,
                onwheel: onCanvasWheel,
            }, [
                m('div', {
                    style: 'position:absolute;transform-origin:0 0;transform:translate(' + pan.x + 'px,' + pan.y + 'px) scale(' + zoom + ');',
                }, [
                    // SVG edges
                    renderEdges(),
                    // Node cards
                    graphData && graphData.nodes
                        ? graphData.nodes.map(function (n) { return renderNodeCard(n); })
                        : null,
                    // Canvas size placeholder (keeps scroll area correct)
                    m('div', { style: 'width:' + sz.w + 'px;height:' + sz.h + 'px;pointer-events:none;' }),
                ]),
            ]) : null,

            // Node detail panel (fixed overlay) — chapter view only.
            viewMode === 'chapter' ? renderNodeDetailPanel() : null,

            // Dialogs
            renderMemberDialog(),
            renderChapterDialog(),
        ]);
    },
};

// ── Routes ────────────────────────────────────────────────────────────

export const routes = {
    '/picture-book/:bookObjectId/workflow': {
        oninit: function (vnode) { pictureBookWorkflowView.oninit(vnode); },
        view: function () { return layout(pageLayout(m(pictureBookWorkflowView))); }
    }
};
