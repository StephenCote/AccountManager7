(function(){
    const tab = {};
    let space;
    let vnode;
    let entityName = "sig";
    let app;

   



    tab.component = {
        oninit : function(x){
            model = x.attrs.model;
        },
        oncreate : function (x) {

        },
        onremove : function(x){

        },

        view: function () {
            return m("div",{
                    class : "panel-container"
                },
                m("div", { class : "panel-grid"},
                    modelPanel()
                )
            );
         }
    };

    page.components.tab = tab.component;
}());
