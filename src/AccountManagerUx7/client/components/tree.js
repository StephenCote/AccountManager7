(function () {

    function newTreeComponent() {

        let treeMatrix = {};
        let autoExpand = false;
        let selectedNode;
        let origin;
        let originRole;
        let originPermission;
        let list;
        let pendingRefresh = false;
        let dnd;
        let olioMode = true;
        let checkOlio = false;
        let olio;

        function inlineType(type, fConfirm, fCancel) {
            treeMatrix[selectedNode].expanded = true;
            let entity = am7model.newPrimitive(type);
            if (type == "auth.group") {
                entity.path = treeMatrix[selectedNode].path;
            }
            if (type == "auth.role" || type == "auth.permission") {
                entity.path = treeMatrix[selectedNode].path + "/" + treeMatrix[selectedNode].name;
            }
            console.log(entity);
            let dlg = {
                label: "New " + type,
                entityType: type,
                size: 50,
                data: { entity },
                confirm: async function (data) {
                    if (fConfirm) await fConfirm(data);
                    page.endDialog();
                },
                cancel: async function (data) {
                    if (fCancel) await fCancel(data);
                    page.endDialog();
                }
            };
            page.setDialog(dlg);
        }
        async function inlineAdd(data) {
            let mtx = treeMatrix[selectedNode];
            let newGrp = data.entity;
            if (mtx.type == "auth.group") {
                newGrp.type = data.entity.type;
            }

            if (mtx.type == "auth.role" || mtx.type == "auth.permission" || mtx.type == "auth.group") {
                newGrp.path = treeMatrix[selectedNode].path + "/" + treeMatrix[selectedNode].name;
            }

            if (mtx.type.match(/^(auth\.role|auth\.permission)$/)) {
                newGrp.type = data.entity.type;
            }
            newGrp.parentId = mtx.nodeId;
            newGrp.name = data.entity.name;
            await page.createObject(newGrp);
            resetNode(selectedNode);
            am7client.clearCache(newGrp.model, true);
            m.redraw();
        }

        function addNew() {
            let mtx = treeMatrix[selectedNode];
            inlineType(mtx.listChildType, inlineAdd, async function (data) {
                //inlineType(mtx.type.toLowerCase(), inlineAdd, async function(data){
                console.log("Cancel");
            });
        }

        function deleteNode() {
            if (!selectedNode || selectedNode == origin.objectId) return;
            let node = treeMatrix[selectedNode];
            let pid;
            Object.keys(treeMatrix).forEach((t) => {
                let tmx = treeMatrix[t];
                if (!pid && t != selectedNode && tmx.nodeId == node.parentId) {
                    pid = tmx.id;
                }
            });
            console.log("PID", pid);
            page.confirm("Delete " + node.type + " " + node.name + "?", async function () {
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
            Object.keys(treeMatrix).forEach((i) => {
                resetNode(i, true);
            });
            am7client.clearCache("auth.group", true);
            m.redraw();
        }
        function getTreeView() {
            let tree = [];

            let buttons = [];
            buttons.push(page.iconButton("button", "add", "", addNew));
            buttons.push(page.iconButton("button", "refresh", "", refreshTree));
            buttons.push(page.iconButton("button" + (selectedNode && selectedNode != origin.objectId ? "" : " inactive"), "delete_outline", "", deleteNode));
            let buttonBar = m("div", { class: "result-nav-outer" }, [
                m("div", { class: "result-nav-inner" }, [
                    m("div", { class: "result-nav tab-container" },
                        buttons
                    ),
                    m("nav", { class: "result-nav" }, [
                        //page.pagination().pageButtons()
                    ])
                ])
            ]);

            if (origin) {
                if (!selectedNode) selectedNode = origin.objectId;
                tree.push(getTreeViewNode(origin));
                if (olioMode) {
                    if (!olio && !checkOlio) {
                        checkOlio = true;
                        page.findObject("auth.group", "DATA", "/Olio/Universes").then((g) => {
                            11
                            if (!g) {
                                return;
                            }
                            let q = am7view.viewQuery(am7model.newInstance("auth.group"));
                            q.field("objectId", g.objectId);
                            page.search(q).then((g2) => {

                                if (g2 && g2.results) {
                                    olio = g2.results[0];
                                    m.redraw();
                                }
                            })
                        });
                    }
                    else if (olio) {
                        tree.push(getTreeViewNode(olio));
                    }
                }
                else {
                    console.log("Not olio mode");
                }
            }

            return m("div", { class: "list-results-container" }, [
                m("div", { class: "list-results" }, [
                    buttonBar,
                    m("div", { class: "results-overflow" }, tree)
                ])
            ]);
        }

        function getMatrix(node) {
            let bGroup = (node.model === "auth.group");
            let nodeName = node.name;
            if (!treeMatrix[node.objectId]) {
                let fList = "list";
                let fListType = "data.data";
                let ico = "folder";
                let builtIn = false;

                /// Auto-Type, Lock from dragging out
                let pathType;
                if (bGroup) {
                    if (node.type.match(/^(account|person)$/gi)) {
                        /// fListType = node.type;
                        fListType = "identity." + node.type;
                        let modType = am7model.getModel(fListType.toLowerCase());
                        ico = modType.icon;
                    }
                    else if (node.type.match(/^(bucket)$/gi)) {
                        ico = "collections_bookmark";
                        if (node.name.match(/^favorite/i)) {
                            ico = "favorite";
                        }
                    }
                    else {
                        let path = node.path;
                        let idx = node.path.indexOf(origin.path);
                        if (idx == 0) {
                            path = path.slice(idx + origin.path.length + 1);
                        }
                        if (path.indexOf("/") > -1) {
                            let np = node.path;
                            np = np.replace(/\/Gallery$/, "/Gallery");
                            let pathType = am7view.typeByPath(np) || "data.data";
                            if (pathType) {
                                fListType = pathType;
                                let modType = am7model.getModel(pathType);
                                if (modType) {
                                    if (modType.icon) {
                                        ico = modType.icon;
                                    }
                                    if (modType.limit && modType.limit.length) {
                                        fListType = modType.limit[0];
                                    }
                                    else if (modType.type) {
                                        fListType = modType.type;
                                    }
                                }
                                builtIn = true;
                            }
                            else if (node.path == page.user.homeDirectory.path) {
                                ico = "gite";
                            }
                        }
                    }
                }
                else if (node.model.match(/^(auth\.role|auth\.permission)$/gi)) {
                    let modType = am7model.getModel(node.model.toLowerCase());
                    if (modType && modType.icon) {
                        ico = modType.icon;
                    }
                    fListType = node.model;
                    fList = "listInParent";
                    if (node.objectId == originPermission.objectId || node.objectId == originRole.objectId) {
                        nodeName = (node.model == "auth.role" ? "Roles" : "Permissions");
                        builtIn = true;
                    }

                }
                treeMatrix[node.objectId] = {
                    id: node.objectId,
                    name: nodeName,
                    type: node.model,
                    objectType: node.type,
                    path: node.path,
                    groupId: node.groupId,
                    parentId: node.parentId,
                    expanded: false,
                    origin: (node.objectId === origin.objectId),
                    baseIco: ico,
                    builtIn,
                    ico,
                    listEntityType: fListType,
                    listChildType: node.model,
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

        function expandNode(node) {
            let mtx = treeMatrix[node.objectId];
            mtx.expanded = !mtx.expanded;
            m.redraw();
        }
        function selectNode(node) {
            if (autoExpand) {
                expandNode(node);
            }
            selectedNode = node.objectId;
            if (list) {
                list.pagination().new();
            }
            m.route.set("/nav");
        }

        function getNestedTreeView(node) {
            return m("div", { class: "hier-item-nest" }, getTreeViewNode(node));
        }

        function getTreeViewNode(node) {

            if (!node) return "";

            /// Depending on how it's queried, the model property will be pruned from complex objects since the schema definition includes the model type, so that the same
            /// The downside is, if looking at a child node without the parent model reference, you don't know what the child type is supposed to be
            ///
            if (!node.model) {
                node.model = 'auth.group';
            }
            if (!node.model.match(/^(auth\.role|auth\.permission|auth\.group)$/gi)) {
                console.warn("Only roles, groups, and permissions are supported for hierarchical navigation");
                return "";
            }
            let bExpand = false;
            let mtx = getMatrix(node);
            if (mtx.origin || mtx.expanded) bExpand = true;

            let label = mtx.name;
            let nest = [];
            if (bExpand) {
                if (!mtx.children) {
                    if (!pendingRefresh) {
                        pendingRefresh = true;
                        am7client[mtx.flist](mtx.listChildType, mtx.id, null, 0, 1000, function (v) {
                            mtx.children = v.filter((o) => { return !o.name.match(/^\./); });
                            if (node.objectId == origin.objectId) {
                                page.parentType("auth.role").then((rb) => {
                                    page.parentType("auth.permission").then((pb) => {
                                        mtx.children.push(rb);
                                        mtx.children.push(pb);
                                        originRole = rb;
                                        originPermission = pb;

                                        mtx.children = mtx.children.sort(function (aa, bb) {
                                            let n1 = aa.name;
                                            if (aa.objectId == rb.objectId) n1 = "Roles";
                                            else if (aa.objectId == pb.objectId) n1 = "Permissions";
                                            let n2 = bb.name;
                                            if (bb.objectId == rb.objectId) n2 = "Roles";
                                            else if (bb.objectId == pb.objectId) n2 = "Permissions";
                                            return (
                                                n1 < n2
                                                    ? -1
                                                    : 1
                                            );
                                        });
                                        pendingRefresh = false;
                                        m.redraw();
                                    });
                                });
                            }
                            else {
                                pendingRefresh = false;
                                m.redraw();
                            }


                        });
                    }
                }
                else {
                    mtx.children.forEach((c) => {
                        nest.push(getNestedTreeView(c));
                    });
                }
            }
            let altCls = "";
            let altActive = "";
            if ((node.objectType && node.objectType.match(/^(bucket|account|person)$/gi)) || (originPermission && node.objectId == originPermission.objectId) || (originRole && node.objectId == originRole.objectId)) {
                altCls = " material-symbols-outlined-orange";
                altActive = "-orange";

            }
            let icoCls = "flare hier-item-element material-icons-24 material-symbols-outlined" + altCls;
            if (node.objectId == selectedNode) {
                icoCls += " filled active" + altActive;
            }

            let fHandler = function () {
                selectNode(node);
            }
            let attr = "";
            if (!mtx.builtIn) {
                attr = "[draggable]";
            }
            let itemProps = {
                class: "hier-item",
                ondblclick: function () { expandNode(node); },
                onclick: fHandler,
            };

            let dcls = dnd.doDragDecorate(node);
            if (dcls) itemProps.class += " " + dcls;

            itemProps.ondragover = function (e) { dnd.doDragOver(e, node); };
            itemProps.ondragend = function (e) { dnd.doDragEnd(e, node); };
            itemProps.ondragstart = function (e) { dnd.doDragStart(e, node); };
            itemProps.ondrop = function (e) { dnd.doDrop(e, node); };

            return m("div", { class: "hier-item-container" }, [
                m("div" + attr, itemProps, [
                    m("span", { class: icoCls }, mtx.ico),
                    m("span", { class: "hier-item-element" }, label)
                ]),
                nest
            ])
        }

        function resetNode(objectId, skipList) {
            if (treeMatrix[objectId]) {
                let mtx = treeMatrix[objectId];
                delete mtx.children;
                /*
                if(mtx.parentId){
                    let pid;
                    Object.keys(treeMatrix).forEach((t)=>{
                        if(!pid && t.objectId != objectId && t.nodeId == mtx.parentId){
                            pid = t.id;
                        }
                    });
                    if(pid) resetNode(pid, skipList);
                }
                */
                if (!skipList) list.pagination().new();
            }
        }

        let tree = {
            resetNode,
            selectedNode: function () {
                if (!selectedNode) return;
                return treeMatrix[selectedNode];
            },
            oninit: function (vnode) {
                dnd = page.components.dnd;
                list = vnode.attrs.list;
                origin = vnode.attrs.origin || page.user.homeDirectory;
            },
            oncreate: function (x) {

            },
            onremove: function (x) {

            },

            view: function () {
                return getTreeView()
            }
        };
        return tree;
    }
    page.components.tree = newTreeComponent;
}());
