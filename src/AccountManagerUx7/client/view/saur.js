(function(){
    const authenticatedPage = {};
    let space;
    let vnode;
    let entityName = "sig";
    let app;
    let entity = {

    };
    authenticatedPage.view = {
        oninit : function(x){
            // console.log(x);
        },
        oncreate : function (x) {
            app = page.space(entityName, x, entity);
        },
        onremove : function(x){
            if(app) app.destroy();
        },

        view: function () {
            vnode = m("div",{
                class : "screen-center-gray"
                , "avoid" : 1
            },[
  
            ]);
            return vnode;
        }
    };

    page.views.saur = authenticatedPage.view;
}());
