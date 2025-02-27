(function(){

    function newNavigatorControl(){
  
        const navigator = {};
        let fullMode = false;
        let showFooter = false;
        let pagination = page.pagination();
        let listView;
        let treeView;
        let altView;

        function getNavigatorTopMenuView(){
          /*
          return m("div", {class: "result-nav-outer"}, [
            m("div", {class: "result-nav-inner"}, [
              m("div", {class: "result-nav"}, [
                m("button", {class: "button"}, m("span", {class: "material-icons material-icons-24"}, "save"))
              ]),
              m("div", {class: "result-nav"}, [
                m("button", {class: "button"}, [m("span", {class: "material-icons material-icons-24"}, "tab"), " Meta"])
              ])
            ])
          ])
          */
         return [];
        }

        function getSplitContainerView(){
          let splitLeft = "";
          if(!fullMode) splitLeft = getSplitLeftContainerView();
          return m("div", {class: "results-fixed", onselectstart: function(e){ e.preventDefault();}},
            m("div", {class: "splitcontainer"}, [
              splitLeft,
              getSplitRightContainerView()
            ])
          );
        }

        function getSplitRightContainerView(){
          let cls = "splitrightcontainer";
          if(fullMode) cls = "splitfullcontainer";
          return m("div", {class: cls}, [
              getResultsView()     
          ]);
        }

        function getSplitLeftContainerView(){
          let tree = m(treeView, {
            origin,
            list:listView
          });
          return m("div", {
            class: "splitleftcontainer",
            ondragover: function(e){
              e.preventDefault();
            }
          }, [
            //m("div", {class: "splitoverflow"}, [
              tree
            //])
          ]);
        }
        function getResultsView(){
          let ret = "";
          let mtx = treeView.selectedNode();
          if(!mtx) return ret;
          if(altView){
            return m(altView.view.view, {
              fullMode,
              type: altView.type,
              objectId: altView.containerId,
              new: altView.new,
              parentNew: altView.parentNew,
              embeddedMode: true,
              embeddedController: navigator
            });
  
          }
          // console.log(mtx);
          return m(listView.view, {
            fullMode,
            type: mtx.listEntityType,
            objectId: mtx.id,
            startRecord: 0,
            recordCount: 0,
            embeddedMode: true,
            embeddedController: navigator
          });
        }

        function getNavigatorBottomMenuView(){
          if(!showFooter) return "";

          return m("div", {class: "result-nav-outer"}, 
            m("div", {class: "result-nav-inner"}, [
              m("div", {class: "result-nav"}, "..."),
              m("div", {class: "result-nav"}, "Bottom")
            ])
          );
        }
      
        function getNavigatorView(vnode){
            return m("div",{class : "content-outer"},[
              (fullMode ? "" : m(page.components.navigation, {hideBreadcrumb: true})),
              m("div",{class : "content-main"},
                m("div", {class: "list-results-container"},
                  m("div", {class: "list-results"}, [
                    getNavigatorTopMenuView(),
                    getSplitContainerView(),
                    getNavigatorBottomMenuView()
                  ])
                )
              )
            ]);
        }
        
        navigator.toggleFullMode = function(){
          fullMode = !fullMode;
          m.redraw();
        }

        navigator.cancelView = function(){
          altView = undefined;
          fullMode = false;
          m.redraw();
        };

        navigator.editItem = function(object){
          if(!object) return;
          altView = {
            fullMode,
            view: page.views.object(),
            type: object[am7model.jsonModelKey],
            containerId: object.objectId
          };
          m.redraw();
        };

        navigator.addNew = function(type, containerId, parentNew){
          altView = {
            fullMode,
            view: page.views.object(),
            type,
            containerId,
            parentNew,
            new: true
          };
          m.redraw();
        };
        navigator.view = {
            oninit : function(vnode){
                let ctx = page.user.homeDirectory;
                // if(am7community.getCommunityMode()) ctx = am7community.getCommunityProjectGroup();
                origin = vnode.attrs.origin || ctx;
                if(!listView) listView = page.views.list();
                if(!treeView) treeView = page.components.tree();
                //if(!objectView) objectView = page.views.object();
                //document.documentElement.addEventListener("keydown", navListKey);
            },
            oncreate : function (x) {
              // app = page.space(entityName, x, pagination.entity);
            },
            onupdate : function(x){

            },
            onremove : function(x){
              // document.documentElement.removeEventListener("keydown", navListKey);
              page.navigable.cleanupContextMenus();
              pagination.stop();
              // if(app) app.destroy();
            },
  
            view: function (vnode) {
              let v = getNavigatorView(vnode);
                return [v, page.loadDialog()];
            }
        };
        return navigator;
    }
      page.views.navigator = newNavigatorControl;
  }());
  