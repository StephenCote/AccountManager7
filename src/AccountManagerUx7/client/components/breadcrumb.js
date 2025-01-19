(function(){
    const breadCrumb = {};
    let space;
    let vnode;
    let entityName = "sig";
    let app;
    let udef;
    let requesting = false;
    let forced = false;

 

    let crumbButtons;
    function modelBreadCrumb(){
        let oL = document.querySelector("#listBreadcrumb");
        let sPath = page.user.homeDirectory.path;
        let model = page.context();

        let objectId = m.route.param("objectId");

        let modType;
        let type = m.route.param("type") || "data.data";
        if(type){
            modType = am7model.getModel(type);
            type = modType.type || type;
        }

        let objType = type;
        if(m.route.get().match(/\/(list|new)\//gi)){
            objType = "auth.group";
        }

        if(modType && am7model.isGroup(modType) && objectId != null && objectId != "null" && objectId != undefined && objectId != "undefined"){
            let typeUdef = typeof model.contextObjects[objectId] == "undefined";
            if(typeUdef || model.contextObjects[objectId] == null){
                model.contextObjects[objectId] = null;
                am7client.get(objType, objectId, function(v){
                    model.contextObjects[objectId] = v;
                    m.redraw();
                });
            }
            else if(!typeUdef && model.contextObjects[objectId] != null){
                sPath = model.contextObjects[objectId][(objType.match(/^auth\.group/gi) ? "path" : "groupPath")];
            }
        }

        let crumbs = [];
        crumbButtons = [];
        if(!sPath || !sPath.length){
            console.warn("Unexpected path: " + sPath);
            return crumbs;
        }
        let aSp = sPath.split("/").slice(1);
        let bBack = [];


        aSp.forEach((p)=>{
            bBack.push("/" + p);

            let bJoi = bBack.join("");
            let id = bJoi.replace(/\//g,"zZz").replace(/\s/g,"yYy");
            let bid = id + "Button";
            let handler;
            let menuCls = "context-menu-container-slim";
            let btnCls = "multi-button";
            let disabled = false;
            if((bJoi.match(/^\/home$/gi) || bJoi.match(/^\/$/)) && !page.context().roles.admin){
                menuCls += " context-menu-container-disabled";
                btnCls = "multi-button-disabled";
                disabled = true;
            }
            else{
                handler = function(){page.navigateToPath(type, modType, bJoi).then((id)=>{ page.listByType(type, id); });}
            }


            if(!disabled) crumbButtons.push({id:id,bid:bid,path:bJoi,items:[]});
            crumbs.push(m("li","/"));
            crumbs.push(m("li",[
                m("div",{class : menuCls},[
                    m("button" + (disabled ? "[disabled = 'true']" : ""),{onclick : handler, class : "rounded-l " + btnCls}, p),
                    (!disabled ? m("button",{id : bid, class : "rounded-r " + btnCls}, m("span",{class : "material-symbols-outlined material-icons-cm"}, "expand_more")) : ""),
                    (!disabled ? m("div", {id : id, class : "transition transition-0 context-menu-48"}, [
                        page.navigable.contextMenuButton(id,"Loading","folder_off"),
                    ]) : "")
                ])

            ]));
            
        });
        return crumbs;
    }
  


    function buildBreadCrumb(){
        let type = m.route.param("type") || "data.data";
        let modType = am7model.getModel(type);

        return m("nav",{class: "breadcrumb-bar"},[
            m("div", {class: "breadcrumb-container"}, [
                m("nav",{class: "breadcrumb"}, [
                    m("ol", {id: "listBreadcrumb", class: "breadcrumb-list"},[
                        m("li",m("span", {class: "material-symbols-outlined material-icons-24"}, modType.icon)),
                        modelBreadCrumb()
                    ])
                ])
            ])
        ]);

    }

    function contextMenuItemHandler(query, object){
        let aP = query.items.filter((i)=>{
            if(object.path == i.path) return true;
        });

        let type = 'data';
        let modType = am7view.typeByPath(object.name) || "data.data";
        if(modType) type = modType;
        if(aP.length){
            page.listByType(type, aP[0].objectId);
        }
    }

    function configureContextMenus(){
        crumbButtons.forEach((v)=>{
            page.navigable.addContextMenu("#" + v.id,"#" + v.bid,{
                menu : v.id,
                action : "list",
                type : "auth.group",
                subType : "data",
                objectId : 0,
                path : v.path,
                handler : contextMenuItemHandler,
                icon : "folder"
            });
        });
    }
    function cleanupContextMenus(){
        crumbButtons.forEach((v)=>page.navigable.removeContextMenu("#" + v.id));
    }
    function setupDisplayState(){
        configureContextMenus();
    }
    breadCrumb.component = {
        oninit : function(x){

        },
        onupdate : function(){
            cleanupContextMenus();
            setupDisplayState();
        },
        oncreate : function (x) {
            setupDisplayState();

        },
        onremove : function(x){
            cleanupContextMenus();
        },

        view: function () {
            return m("div", { avoid : "1", class : ""},
                buildBreadCrumb()
            );
         }
    };

    page.components.breadCrumb = breadCrumb.component;
}());
