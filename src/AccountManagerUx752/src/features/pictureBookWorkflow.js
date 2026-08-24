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
    regenerateNode, pinNode, addMembers, createChapter, testNode
} from '../workflows/pictureBookWorkflow.js';

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

let memberDialog = false;
let memberNames = '';
let chapterDialog = false;
let chapterSlug = '';
let chapterTitle = '';
let recheckingStale = false;

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
    graphData = null;
    positions = {};
    selectedNodeId = null;
    nodeDetails = null;
    m.redraw();
    try {
        let info = await getBookInfo(groupOid);
        if (!info) {
            error = 'No PB2 workflow book found for this book. Generate some scenes to create one.';
            loading = false;
            m.redraw();
            return;
        }
        pb2BookObjectId = info.pb2BookObjectId;
        bookName = info.bookName || '';
        let gd = await workflowView(pb2BookObjectId);
        graphData = gd;
        positions = computePositions(gd.nodes || []);
    } catch (e) {
        error = 'Failed to load workflow graph: ' + (e.message || '');
    }
    loading = false;
    m.redraw();
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

async function doCreateChapter() {
    if (!chapterSlug.trim()) { page.toast('error', 'Slug is required'); return; }
    try {
        let result = await createChapter(pb2BookObjectId, chapterSlug.trim(), chapterTitle.trim() || null);
        page.toast('success', 'Chapter created: ' + result.slug);
        chapterDialog = false;
        chapterSlug = '';
        chapterTitle = '';
    } catch (e) {
        page.toast('error', 'Chapter failed: ' + e.message);
    }
    m.redraw();
}

// ── SVG edges ─────────────────────────────────────────────────────────

function renderEdges() {
    if (!graphData || !graphData.edges) return null;
    let paths = [];
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
        paths.push(m('path', {
            key: e.objectId || (src + '-' + dst),
            d: 'M' + x1 + ',' + y1 + ' C' + cx + ',' + y1 + ' ' + cx + ',' + y2 + ' ' + x2 + ',' + y2,
            fill: 'none',
            stroke: '#94a3b8',
            'stroke-width': '1.5',
            opacity: '0.6',
        }));
    }
    let sz = canvasSize();
    return m('svg', {
        style: 'position:absolute;top:0;left:0;pointer-events:none;',
        width: sz.w,
        height: sz.h,
    }, paths);
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
        onclick: function (e) { e.stopPropagation(); selectNode(n.objectId); }
    }, [
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

function renderChapterDialog() {
    if (!chapterDialog) return null;
    return m('div', {
        style: 'position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:500;display:flex;align-items:center;justify-content:center;',
        onclick: function () { chapterDialog = false; m.redraw(); }
    }, m('div', {
        style: 'background:#fff;border-radius:10px;padding:24px;width:360px;',
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
            style: 'width:100%;border:1px solid #e2e8f0;border-radius:6px;padding:8px;font-size:13px;box-sizing:border-box;',
            placeholder: 'Chapter Two',
            value: chapterTitle,
            oninput: function (e) { chapterTitle = e.target.value; },
        }),
        m('div', { style: 'display:flex;gap:8px;margin-top:12px;justify-content:flex-end;' }, [
            m('button', { style: 'border:1px solid #e2e8f0;border-radius:6px;padding:6px 14px;cursor:pointer;', onclick: function () { chapterDialog = false; m.redraw(); } }, 'Cancel'),
            m('button', { style: 'background:#3b82f6;color:#fff;border:none;border-radius:6px;padding:6px 14px;cursor:pointer;font-weight:600;', onclick: doCreateChapter }, 'Create'),
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

// ── Canvas interaction ────────────────────────────────────────────────

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
    if (!dragging) return;
    pan.x = panStart.x + (e.clientX - dragStart.x);
    pan.y = panStart.y + (e.clientY - dragStart.y);
    m.redraw();
}

function onCanvasMouseUp() {
    dragging = false;
}

function onCanvasWheel(e) {
    e.preventDefault();
    let delta = e.deltaY > 0 ? 0.9 : 1.1;
    zoom = Math.max(0.3, Math.min(3, zoom * delta));
    m.redraw();
}

// ── Main view ─────────────────────────────────────────────────────────

var pictureBookWorkflowView = {
    oninit: function (vnode) {
        bookGroupObjectId = vnode.attrs.bookObjectId;
        pb2BookObjectId = null;
        bookName = '';
        graphData = null;
        positions = {};
        selectedNodeId = null;
        nodeDetails = null;
        nodeDetailsLoading = false;
        pan = { x: 0, y: 0 };
        zoom = 1;
        dragging = false;
        pinLoading = {};
        regenLoading = {};
        testLoading = {};
        memberDialog = false;
        memberNames = '';
        chapterDialog = false;
        chapterSlug = '';
        chapterTitle = '';
        recheckingStale = false;
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
                // Recheck stale button
                pb2BookObjectId ? m('button', {
                    title: 'Recompute staleness from backend and reload graph',
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;',
                    onclick: doRecheckStale,
                    disabled: recheckingStale,
                }, recheckingStale ? '…' : '↻ Stale') : null,
                // Zoom controls
                m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:14px;',
                    onclick: function () { zoom = Math.max(0.3, zoom * 0.85); m.redraw(); }
                }, '−'),
                m('span', { style: 'font-size:12px;min-width:40px;text-align:center;' }, Math.round(zoom * 100) + '%'),
                m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:14px;',
                    onclick: function () { zoom = Math.min(3, zoom * 1.15); m.redraw(); }
                }, '+'),
                m('button', {
                    style: 'border:1px solid #e2e8f0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;',
                    onclick: function () { pan = { x: 0, y: 0 }; zoom = 1; m.redraw(); }
                }, 'Reset'),
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

            // Error state
            error ? m('div', { style: 'padding:32px;text-align:center;color:#dc2626;' }, error) : null,

            // Graph canvas
            !error ? m('div', {
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

            // Node detail panel (fixed overlay)
            renderNodeDetailPanel(),

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
