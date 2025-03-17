(function(){
    const mainPage = {};
    let vnode;
    let nodeMain;

    mainPage.view = {
        oninit : function(x){

        },
        oncreate : function (x) {
            nodeMain = document.querySelector("div.content-main");
        },
        onremove : function(x){
            //if(app) app.destroy();
        },

        view: function () {
            //console.log("Render main");
            vnode = [m("div",{class : "content-outer"},[
                m(page.components.navigation),
                m("div",{class : "content-main"},[
                    page.contentRouter()
                ])
            ]), page.loadDialog(), page.loadToast()];
            return vnode;
        }
    };

    page.views.main = mainPage.view;
}());
