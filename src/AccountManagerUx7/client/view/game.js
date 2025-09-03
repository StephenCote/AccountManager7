(function(){
    const game = {};
    let fullMode = false;
    let components = {};
    function toggleFullMode(){
        fullMode = !fullMode;
        m.redraw();
    }

    function getGameComponent(){
        let parm = m.route.param("name") || "tetris";
        if(!components[parm]){
            components[parm] = page.components[parm];
        }
        return components[parm];
    }

    function keyHandler(e){
        let component = getGameComponent();
        if(component && component.keyHandler) return component.keyHandler(e);
        return;
    }

    function doSave(){
        let component = getGameComponent();
        if(component.saveGame) component.saveGame();
    }

    function getGameView(){
        let comp = getGameComponent();
        return m("div",{class : "content-outer"},[
            (fullMode ? "" : m(page.components.navigation)),
            m("div",{class : "content-main"},[
                m("div",{class : "list-results-container"},[
                    m("div",{class : "list-results"},[
                    m("div",{class: "result-nav-outer"},[
                        m("div",{class: "result-nav-inner"},[
                            m("div",{class: "result-nav tab-container"},[
                                page.iconButton("button mr-4", (fullMode ? "close_fullscreen" : "open_in_new"), "", toggleFullMode),
                                page.iconButton("button" + (comp && comp.isChanged && comp.isChanged() ? " warn" : ""),"save", "", doSave),
                                page.iconButton("button","cancel", "", function(){ history.back(); }),
                                (comp && comp.commands ? comp.commands() : "")
                            ]),
                            m("div",{class: "result-nav tab-container"},
                               comp ? comp.scoreCard() : ""
                            )
                        ])
                    ]),
                    m(comp ? comp : "", {caller: game})
                ])
            ])
        ]),
         page.components.dialog.loadDialog(), page.loadToast()
        ]);
          
    }
    game.view = {
        oninit : function(x){
            document.addEventListener("keydown", keyHandler);
        },
        oncreate : function (x) {
          
          page.navigable.setupPendingContextMenus();
        },
        onupdate : function(x){

        },
        onunload : function(x){
            console.log("Unloading ...");
        },
        onremove : function(x){
            document.removeEventListener("keydown", keyHandler);
          page.navigable.cleanupContextMenus();
        },

        view: function (vnode) {
            if(!page.authenticated()) return m("");
            return getGameView();
        }
    };
    page.views.game = game.view;
}());
