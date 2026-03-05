/**
 * tree.js — Hierarchical tree component for auth.group/role/permission (ESM)
 * Port of Ux7 client/components/tree.js
 *
 * Expand/collapse, selection, inline type creation, DnD tree reordering.
 * Used by navigator view for split-pane browsing.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

function newTreeComponent() {
    let treeMatrix = {};
    let autoExpand = false;
    let selectedNode;
    let origin;
    let originRole;
    let originPermission;
    let list;
    let pendingRefresh = false;
    let olioMode = true;
    let checkOlio = false;
    let olio;

    let am7client, page, dnd;

    // ── Inline creation ─────────────────────────────────────────────

    function inlineType(type, fConfirm, fCancel) {
        treeMatrix[selectedNode].expanded = true;
        let entity = am7model.newPrimitive(type);
        if (type === "auth.group") {
            entity.path = treeMatrix[selectedNode].path;
        }
        if (type === "auth.role" || type === "auth.permission") {
            entity.path = treeMatrix[selectedNode].path + "/" + treeMatrix[selectedNode].name;
        }

        page.components.dialog.open({
            title: "New " + type,
            content: function() {
                return m("div", { class: "p-4" }, [
                    m("label", { class: "block text-sm font-medium mb-1" }, "Name"),
                    m("input", {
                        class: "w-full px-3 py-2 border rounded text-sm",
                        type: "text",
                        value: entity.name || "",
                        oninput: function(e) { entity.name = e.target.value; }
                    })
                ]);
            },
            onConfirm: function() {
                if (fConfirm) fConfirm({ entity });
            }
        });
    }

    async function inlineAdd(data) {
        let mtx = treeMatrix[selectedNode];
        let newGrp = data.entity;
        if (mtx.type === "auth.group") {
            newGrp.type = data.entity.type;
        }
        if (mtx.type.match(/^(auth\.role|auth\.permission|auth\.group)$/gi)) {
            newGrp.path = treeMatrix[selectedNode].path + "/" + treeMatrix[selectedNode].name;
        }
        if (mtx.type.match(/^(auth\.role|auth\.permission)$/)) {
            newGrp.type = data.entity.type;
        }
        newGrp.parentId = mtx.nodeId;
        newGrp.name = data.entity.name;
        await page.createObject(newGrp);
        resetNode(selectedNode);
        am7client.clearCache(newGrp[am7model.jsonModelKey], true);
        m.redraw();
    }

    function addNew() {
        let mtx = treeMatrix[selectedNode];
        inlineType(mtx.listChildType, inlineAdd);
    }

    function deleteNode() {
        if (!selectedNode || selectedNode === origin.objectId) return;
        let node = treeMatrix[selectedNode];
        let pid;
        Object.keys(treeMatrix).forEach(function(t) {
            let tmx = treeMatrix[t];
            if (!pid && t !== selectedNode && tmx.nodeId === node.parentId) {
                pid = tmx.id;
            }
        });
        page.components.dialog.confirm("Delete " + node.type + " " + node.name + "?", async function() {
            await page.deleteObject(node.type, node.id);
            am7client.clearCache(node.type, true);
            delete treeMatrix[selectedNode];
            if (pid) {
                selectedNode = pid;
                resetNode(pid);
                am7client.clearCache("auth.group", true);
                m.redraw();
            }
        });
    }

    function refreshTree() {
        Object.keys(treeMatrix).forEach(function(i) {
            resetNode(i, true);
        });
        am7client.clearCache("auth.group", true);
        m.redraw();
    }

    // ── Matrix ──────────────────────────────────────────────────────

    function getMatrix(node) {
        let bGroup = (node[am7model.jsonModelKey] === "auth.group");
        let nodeName = node.name;

        if (!treeMatrix[node.objectId]) {
            let fList = "list";
            let fListType = "data.data";
            let ico = "folder";
            let builtIn = false;

            if (bGroup) {
                if (node.type && node.type.match(/^(account|person)$/gi)) {
                    fListType = "identity." + node.type;
                    let mt = am7model.getModel(fListType.toLowerCase());
                    if (mt && mt.icon) ico = mt.icon;
                }
                else if (node.type && node.type.match(/^(bucket)$/gi)) {
                    ico = "collections_bookmark";
                    if (node.name && node.name.match(/^favorite/i)) ico = "favorite";
                }
                else {
                    let path = node.path;
                    if (origin) {
                        let idx = node.path.indexOf(origin.path);
                        if (idx === 0) path = path.slice(idx + origin.path.length + 1);
                    }

                    let pathType;
                    if (path.indexOf("/") > -1) {
                        pathType = (am7view.typeByPath ? am7view.typeByPath(node.path) : null) || "data.data";
                    } else {
                        pathType = (am7view.typeByPath ? am7view.typeByPath(path) : null) || "data.data";
                    }
                    if (pathType) {
                        fListType = pathType;
                        let mt = am7model.getModel(pathType);
                        if (mt) {
                            if (mt.icon) ico = mt.icon;
                            if (mt.limit && mt.limit.length) fListType = mt.limit[0];
                            else if (mt.type) fListType = mt.type;
                        }
                    }
                    else if (page && page.user && page.user.homeDirectory && node.path === page.user.homeDirectory.path) {
                        ico = "gite";
                    }
                }
            }
            else if (node[am7model.jsonModelKey].match(/^(auth\.role|auth\.permission)$/gi)) {
                let mt = am7model.getModel(node[am7model.jsonModelKey].toLowerCase());
                if (mt && mt.icon) ico = mt.icon;
                fListType = node[am7model.jsonModelKey];
                fList = "list";
                if ((originPermission && node.objectId === originPermission.objectId) ||
                    (originRole && node.objectId === originRole.objectId)) {
                    nodeName = (node[am7model.jsonModelKey] === "auth.role" ? "Roles" : "Permissions");
                    builtIn = true;
                }
            }

            treeMatrix[node.objectId] = {
                id: node.objectId,
                name: nodeName,
                type: node[am7model.jsonModelKey],
                objectType: node.type,
                path: node.path,
                groupId: node.groupId,
                parentId: node.parentId,
                expanded: false,
                origin: (node.objectId === (origin ? origin.objectId : null)),
                baseIco: ico,
                builtIn,
                ico,
                listEntityType: fListType,
                listChildType: node[am7model.jsonModelKey],
                flist: fList,
                nodeId: node.id
            };
        }

        let mtx = treeMatrix[node.objectId];
        if (bGroup && !mtx.origin) {
            mtx.ico = (mtx.expanded && mtx.children && mtx.children.length ? "folder_open" : mtx.baseIco);
        }
        return mtx;
    }

    // ── Node operations ─────────────────────────────────────────────

    function expandNode(node) {
        let mtx = treeMatrix[node.objectId];
        mtx.expanded = !mtx.expanded;
        m.redraw();
    }

    function selectNode(node) {
        if (autoExpand) expandNode(node);
        selectedNode = node.objectId;
        if (list && list.pagination) list.pagination().new();
        m.redraw();
    }

    function resetNode(objectId, skipList) {
        if (treeMatrix[objectId]) {
            let mtx = treeMatrix[objectId];
            delete mtx.children;
            if (!skipList && list && list.pagination) list.pagination().new();
        }
    }

    // ── Rendering ───────────────────────────────────────────────────

    function getNestedTreeView(node) {
        return m("div", { class: "ml-4" }, getTreeViewNode(node));
    }

    function getTreeViewNode(node) {
        if (!node) return "";
        if (!node[am7model.jsonModelKey]) node[am7model.jsonModelKey] = 'auth.group';
        if (!node[am7model.jsonModelKey].match(/^(auth\.role|auth\.permission|auth\.group)$/gi)) return "";

        let mtx = getMatrix(node);
        let bExpand = mtx.origin || mtx.expanded;
        let nest = [];

        if (bExpand) {
            if (!mtx.children) {
                if (!pendingRefresh) {
                    pendingRefresh = true;
                    am7client[mtx.flist](mtx.listChildType, mtx.id, null, 0, 1000, function(v) {
                        if (!v) { pendingRefresh = false; m.redraw(); return; }
                        am7model.updateListModel(v);
                        mtx.children = v.filter(function(o) { return !o.name.match(/^\./); });

                        if (node.objectId === origin.objectId && page.parentType) {
                            page.parentType("auth.role").then(function(rb) {
                                page.parentType("auth.permission").then(function(pb) {
                                    mtx.children.push(rb);
                                    mtx.children.push(pb);
                                    originRole = rb;
                                    originPermission = pb;
                                    mtx.children.sort(function(a, b) {
                                        let n1 = (a.objectId === rb.objectId ? "Roles" : a.objectId === pb.objectId ? "Permissions" : a.name);
                                        let n2 = (b.objectId === rb.objectId ? "Roles" : b.objectId === pb.objectId ? "Permissions" : b.name);
                                        return n1 < n2 ? -1 : 1;
                                    });
                                    pendingRefresh = false;
                                    m.redraw();
                                });
                            });
                        } else {
                            pendingRefresh = false;
                            m.redraw();
                        }
                    });
                }
            } else {
                mtx.children.forEach(function(c) {
                    nest.push(getNestedTreeView(c));
                });
            }
        }

        let isSelected = (node.objectId === selectedNode);
        let icoClass = "material-symbols-outlined text-gray-500 dark:text-gray-400"
            + (isSelected ? " text-blue-600 dark:text-blue-400" : "");

        return m("div", { class: "select-none" }, [
            m("div", {
                class: "flex items-center gap-1 px-2 py-1 cursor-pointer rounded hover:bg-gray-100 dark:hover:bg-gray-800"
                    + (isSelected ? " bg-blue-50 dark:bg-blue-900/30" : ""),
                onclick: function() { selectNode(node); },
                ondblclick: function() { expandNode(node); }
            }, [
                m("span", { class: icoClass, style: "font-size:18px" }, mtx.ico),
                m("span", {
                    class: "text-sm truncate" + (isSelected ? " font-medium text-blue-700 dark:text-blue-300" : " text-gray-700 dark:text-gray-300")
                }, mtx.name)
            ]),
            nest
        ]);
    }

    function getTreeView() {
        let tree = [];
        let buttons = [
            page.iconButton("button", "add", "", addNew),
            page.iconButton("button", "refresh", "", refreshTree),
            page.iconButton("button" + (selectedNode && selectedNode !== (origin ? origin.objectId : null) ? "" : " inactive"), "delete_outline", "", deleteNode)
        ];

        let buttonBar = m("div", { class: "flex items-center gap-1 px-2 py-1 border-b border-gray-200 dark:border-gray-700" }, buttons);

        if (origin) {
            if (!selectedNode) selectedNode = origin.objectId;
            tree.push(getTreeViewNode(origin));

            // Olio mode: also show /Olio/Universes
            if (olioMode && !olio && !checkOlio) {
                checkOlio = true;
                page.findObject("auth.group", "DATA", "/Olio/Universes").then(function(g) {
                    if (!g) return;
                    let q = am7view.viewQuery(am7model.newInstance("auth.group"));
                    q.field("objectId", g.objectId);
                    page.search(q).then(function(g2) {
                        if (g2 && g2.results) { olio = g2.results[0]; m.redraw(); }
                    });
                });
            } else if (olioMode && olio) {
                tree.push(getTreeViewNode(olio));
            }
        }

        return m("div", { class: "flex flex-col h-full" }, [
            buttonBar,
            m("div", { class: "flex-1 overflow-y-auto p-1" }, tree)
        ]);
    }

    // ── Mithril component ───────────────────────────────────────────

    let tree = {
        resetNode,
        selectedNode: function() {
            if (!selectedNode) return;
            return treeMatrix[selectedNode];
        },
        oninit: function(vnode) {
            am7client = getClient();
            page = getPage();
            dnd = page.components.dnd;
            list = vnode.attrs.list;
            origin = vnode.attrs.origin || (page.user ? page.user.homeDirectory : null);
        },
        onremove: function() { },
        view: function() {
            return getTreeView();
        }
    };

    return tree;
}

export { newTreeComponent };
export default newTreeComponent;
