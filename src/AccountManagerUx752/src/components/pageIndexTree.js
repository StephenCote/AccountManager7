/**
 * pageIndexTree.js — PageIndex tree viewer + ranked-query UI.
 *
 * Mirrors the existing vector/RAG feature's role at the Ux layer, substituting the PageIndex
 * REST contract (see aiDocs/PageIndexIntegrationPlan.md, Tier 3):
 *   GET  /pageIndex/tree/{type}/{objectId}              -> flat node array (ROOT/SECTION/CHUNK)
 *   POST /pageIndex/retrieve/{type}/{objectId}/{count}   -> ranked leaves (raw-text query body)
 *
 * Mounted as a custom form tab via formDef.js's `render(entity, inst, foreignData, objectPage)`
 * hook (data.data / data.note forms — gated by the model's `pageIndex: true` opt-in in
 * modelDef.js, mirroring how `vectorize: true` gates the vector feature).
 *
 * Gotchas honored here (per project rules):
 *  - Tree reconstruction matches each node's `parentId` (number) against another node's `id`
 *    (number) — NOT `objectId` (a UUID string).
 *  - `nodeType` is lowercase on the wire ("root"/"section"/"chunk") — compared case-insensitively.
 *  - The retrieve query is a raw-text POST body, not JSON — sent via the established
 *    `headers: {"Content-Type":"text/plain"}, serialize: v => v` pattern used elsewhere in
 *    this codebase (see src/test-harness/llmTestSuite.js's /memory/search call).
 */
import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { pageIndex as pageIndexWorkflow, buildPageIndex, deletePageIndex } from '../workflows/pageIndex.js';

/**
 * Reconstruct a nested ROOT -> SECTION -> CHUNK outline from the server's flat node list.
 * Pure function — exported for direct unit testing (the parentId/id matching is the trickiest
 * bit and deserves a fixture-driven test, per the Tier 3 verification plan).
 *
 * @param {Array} nodes flat node list from GET /pageIndex/tree/{type}/{objectId}
 * @returns {Array} root node(s), each carrying a `_children` array (recursively), sorted by
 *                  level then ordinal at every level. Orphan nodes (parentId set but the parent
 *                  isn't in the list) are surfaced as top-level roots rather than silently dropped.
 */
function buildPageIndexTree(nodes) {
    if (!Array.isArray(nodes) || nodes.length === 0) return [];

    let byId = {};
    nodes.forEach(function (n) {
        if (n && n.id != null) byId[n.id] = n;
        if (n) n._children = [];
    });

    let roots = [];
    nodes.forEach(function (n) {
        if (!n) return;
        let nodeType = (n.nodeType || '').toLowerCase();
        let hasParentRef = n.parentId != null && n.parentId !== 0;
        let parent = hasParentRef ? byId[n.parentId] : null;
        if (parent && parent !== n) {
            parent._children.push(n);
        } else if (nodeType === 'root' || !parent) {
            // Either an explicit ROOT, or an orphan (parentId set but parent missing from the
            // list, or no parentId at all) — surface rather than drop.
            roots.push(n);
        }
    });

    function sortRec(list) {
        list.sort(function (a, b) {
            let la = a.level || 0, lb = b.level || 0;
            if (la !== lb) return la - lb;
            return (a.ordinal || 0) - (b.ordinal || 0);
        });
        list.forEach(function (n) { if (n._children && n._children.length) sortRec(n._children); });
    }
    sortRec(roots);
    return roots;
}

function nodeKey(node) { return node.objectId || node.id; }

function PageIndexTree() {
    let entity, inst, type, objectId;
    let nodes = [];
    let tree = [];
    let loading = true;
    let loadError = null;
    let building = false;
    let expanded = {};

    let queryText = '';
    let queryCount = 5;
    let queryResults = [];
    let queryLoading = false;
    let queryError = null;

    function loadTree() {
        loading = true;
        loadError = null;
        m.request({
            method: 'GET',
            url: am7client.base() + '/pageIndex/tree/' + type + '/' + objectId,
            withCredentials: true
        }).then(function (res) {
            nodes = Array.isArray(res) ? res : [];
            tree = buildPageIndexTree(nodes);
            loading = false;
            m.redraw();
        }).catch(function (e) {
            loadError = (e && e.message) ? e.message : String(e);
            loading = false;
            m.redraw();
        });
    }

    function resetFor(vnode) {
        entity = vnode.attrs.entity;
        inst = vnode.attrs.inst;
        type = inst.model.name;
        objectId = inst.api.objectId();
        expanded = {};
        queryResults = [];
        queryError = null;
        loadTree();
    }

    async function doBuild() {
        building = true;
        m.redraw();
        try {
            await pageIndexWorkflow(entity, inst);
        } finally {
            building = false;
            loadTree();
        }
    }

    async function doRebuild() {
        building = true;
        m.redraw();
        try {
            await deletePageIndex(type, objectId);
            let ok = await buildPageIndex(type, objectId);
            page.toast(ok ? 'success' : 'error', ok ? 'PageIndex rebuilt' : 'Rebuild failed — no content extracted');
        } catch (e) {
            page.toast('error', 'Rebuild failed: ' + (e.message || e));
        } finally {
            building = false;
            loadTree();
        }
    }

    async function runQuery() {
        if (!queryText || !queryText.trim()) return;
        queryLoading = true;
        queryError = null;
        queryResults = [];
        m.redraw();
        try {
            let res = await m.request({
                method: 'POST',
                url: am7client.base() + '/pageIndex/retrieve/' + type + '/' + objectId + '/' + queryCount,
                headers: { 'Content-Type': 'text/plain' },
                body: queryText,
                withCredentials: true,
                serialize: function (v) { return v; }
            });
            queryResults = Array.isArray(res) ? res : [];
        } catch (e) {
            queryError = (e && e.message) ? e.message : String(e);
        }
        queryLoading = false;
        m.redraw();
    }

    function toggle(node) {
        let key = nodeKey(node);
        expanded[key] = !expanded[key];
    }

    function nodeIcon(nodeType) {
        if (nodeType === 'root') return 'account_tree';
        if (nodeType === 'section') return 'folder';
        return 'description';
    }

    function nodeRow(node, depth) {
        let key = nodeKey(node);
        let nodeType = (node.nodeType || '').toLowerCase();
        let hasChildren = node._children && node._children.length > 0;
        let isOpen = !!expanded[key];
        let canExpand = hasChildren || !!node.summary || !!node.content;

        let rows = [
            m('div', { class: 'flex items-start gap-1 py-1', style: { paddingLeft: (depth * 16) + 'px' } }, [
                canExpand ? m('button', {
                    class: 'text-gray-400 hover:text-gray-600 flex-shrink-0',
                    onclick: function () { toggle(node); m.redraw(); }
                }, m('span', { class: 'material-symbols-outlined', style: 'font-size: 16px;' }, isOpen ? 'expand_more' : 'chevron_right'))
                    : m('span', { class: 'inline-block flex-shrink-0', style: 'width: 16px;' }),
                m('span', { class: 'material-symbols-outlined text-gray-400 flex-shrink-0', style: 'font-size: 16px;' }, nodeIcon(nodeType)),
                m('div', { class: 'flex-1 min-w-0' }, [
                    m('div', { class: 'text-sm text-gray-800 dark:text-gray-200 truncate' }, node.title || ('(' + (nodeType || 'node') + ')')),
                    (isOpen && node.summary) ? m('div', { class: 'text-xs text-gray-500 dark:text-gray-400 mt-0.5' }, node.summary) : null,
                    (isOpen && nodeType === 'chunk' && node.content) ? m('div', {
                        class: 'text-xs text-gray-600 dark:text-gray-300 mt-1 whitespace-pre-wrap border-l-2 border-gray-200 dark:border-gray-700 pl-2'
                    }, node.content) : null
                ])
            ])
        ];
        if (hasChildren && isOpen) {
            node._children.forEach(function (c) { rows.push.apply(rows, nodeRow(c, depth + 1)); });
        }
        return rows;
    }

    function emptyStateView() {
        return m('div', { class: 'p-4 space-y-3' }, [
            m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, 'No PageIndex has been built for this document yet.'),
            m('button', {
                class: 'flex items-center gap-1 px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500 disabled:opacity-50',
                disabled: building,
                onclick: doBuild
            }, [
                m('span', { class: 'material-symbols-outlined', style: 'font-size: 16px;' }, 'account_tree'),
                building ? 'Building…' : 'Build Index'
            ])
        ]);
    }

    function queryView() {
        return m('div', { class: 'border-t border-gray-200 dark:border-gray-700 pt-3 mt-2' }, [
            m('label', { class: 'field-label' }, 'Query PageIndex'),
            m('div', { class: 'flex gap-2 mt-1' }, [
                m('input', {
                    class: 'text-field-compact flex-1',
                    type: 'text',
                    placeholder: 'Ask a question about this document...',
                    value: queryText,
                    oninput: function (e) { queryText = e.target.value; },
                    onkeydown: function (e) { if (e.key === 'Enter') runQuery(); }
                }),
                m('input', {
                    class: 'text-field-compact',
                    type: 'number',
                    style: 'width: 4.5rem;',
                    value: queryCount,
                    oninput: function (e) { queryCount = parseInt(e.target.value, 10) || 5; }
                }),
                m('button', {
                    class: 'flex items-center justify-center px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500 disabled:opacity-50',
                    disabled: queryLoading, onclick: runQuery
                }, queryLoading ? '…' : 'Search')
            ]),
            queryError ? m('div', { class: 'text-xs text-red-500 mt-1' }, queryError) : null,
            queryResults.length ? m('div', { class: 'mt-2 space-y-2' }, queryResults.map(function (r) {
                return m('div', { class: 'rounded border border-gray-200 dark:border-gray-700 p-2' }, [
                    m('div', { class: 'flex items-center justify-between gap-2' }, [
                        m('span', { class: 'text-sm font-medium text-gray-700 dark:text-gray-200 truncate' }, r.title || '(untitled)'),
                        m('span', { class: 'text-xs text-gray-400 flex-shrink-0' }, 'score: ' + (typeof r.score === 'number' ? r.score.toFixed(4) : r.score))
                    ]),
                    m('div', { class: 'text-xs text-gray-600 dark:text-gray-300 mt-1 whitespace-pre-wrap' }, r.content || '')
                ]);
            })) : null
        ]);
    }

    return {
        oninit: function (vnode) { resetFor(vnode); },
        onupdate: function (vnode) {
            // The object page can swap entity/inst without unmounting this tab (same tab-array
            // position) — reset if we're now looking at a different object.
            if (vnode.attrs.inst !== inst) resetFor(vnode);
        },
        view: function () {
            if (loading) return m('div', { class: 'p-4 text-sm text-gray-500' }, 'Loading PageIndex…');
            if (loadError) return m('div', { class: 'p-4 text-sm text-red-500' }, 'Failed to load PageIndex: ' + loadError);
            if (!tree.length) return emptyStateView();

            return m('div', { class: 'p-2 space-y-2' }, [
                m('div', { class: 'flex items-center gap-2' }, [
                    m('button', {
                        class: 'flex items-center gap-1 px-2 py-1 rounded text-xs border border-gray-300 dark:border-gray-600 text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-50',
                        disabled: building, onclick: doRebuild
                    }, [
                        m('span', { class: 'material-symbols-outlined', style: 'font-size: 14px;' }, 'refresh'),
                        building ? 'Rebuilding…' : 'Rebuild Index'
                    ]),
                    m('span', { class: 'text-xs text-gray-400' }, nodes.length + ' node' + (nodes.length === 1 ? '' : 's'))
                ]),
                m('div', { class: 'rounded border border-gray-200 dark:border-gray-700 max-h-80 overflow-y-auto p-1' },
                    tree.map(function (n) { return nodeRow(n, 0); })),
                queryView()
            ]);
        }
    };
}

export { buildPageIndexTree, PageIndexTree };
export default PageIndexTree;
