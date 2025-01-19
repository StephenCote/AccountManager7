(function(){
    const signInPage = {};
    let space;
    let vnode;
    let entityName = "sig";
    let app;

    am7model.models.push({
        name: "login",
        icon: "login",
        label: "Login",
        fields: [
            {name: "organization", default: "/Public", type: "string"},
            {name: "userName", type: "string", "rules": ["$notEmpty"]},
            {name: "password", type: "string"},
            {name: "specifyOrganizationPath", type: "string"}
        ]
    });

    /// Option chain: ?.
    /// Null coalesce: ?? (eg: let x = null ?? prop)
    /// Promise.allSettled

    am7model.forms.login = {
        label : "Login",
        commands : {
            login : {
                label : 'Login',
                icon : 'login',
                action : 'login'
            }
        },
        fields: {
            organization: {
                layout: "full",
                format: "list",
                values : ["/Public", "/Development", "/System"]
            },
            userName: {
                layout: "full",
                label: "Name"
            },
            password: {
                layout: "full",
                label: "Password",
                type: "password"
            }
        }
    };
 
    let inst = am7model.newInstance("login", am7model.forms.login);
    inst.action("login", doLogin);

    function getCustomOrganization(){
        if(!document.querySelector("#selOrganizationList").selectedIndex) return document.querySelector("#specifyOrganizationPath").value;
        return document.querySelector("#selOrganizationList").value;
    };
    function doLogin(){
        uwm.login(inst.api.organization(), inst.api.userName(), inst.api.password(), 0, function(s, v){
            page.router.refresh();
        });
        inst.api.password("");
    }
    
    let organizations = ["Specify ...","/Public","/Development","/System"];

    function toggleSpecify(){
        let oSel = document.querySelector("#selOrganizationList");
        let i = oSel.selectedIndex;
        if(i == -1){
            i = 1;
        }
        document.querySelector("#specifyOrganizationPath").style.display = ((i == 0) ? "block" : "none");
    }

    function orgFieldDesigner(i, e, v){
        return m("div",[
            m("label",{
            class : "block",
            for : "organization"
            },[
            "Organization"
            ]),
            organizationField()
        ]);
    }

    function organizationField(){
        return [
            m("select", {id: "selOrganizationList", onchange: inst.handleChange("organization", toggleSpecify), class: "w-full page-select reactive-arrow"},
                organizations.map((o, i)=>m("option",{value:o,selected:(o==inst.api.organization())}, o)),
            ),
            m("input",{
                class : "hidden text-field-full",
                type : "text",
                id : "specifyOrganizationPath",
                onchange: inst.handleChange("organization"),
                placeholder : "/Custom"
            }) 
        ];
    }

    inst.designer("organization", orgFieldDesigner);
    inst.viewProperties("password", { onkeydown : function(e){ if (e.which == 13) doLogin(); }});

 
    
    signInPage.view = {
        oninit : function(x){

        },
        oncreate : function (x) {
            //document.querySelector("#selOrganizationList").selectedIndex = 1;
           /// toggleSpecify();
            // app = page.space(entityName, x, entity);

        },
        onremove : function(x){

        },

        view: function () {
            vnode = m("div",{
                class : "screen-center-gray"
            },[
                m("div",{
                    class : "box-shadow-white"
                },[
                    am7view.form(inst)
                ])
                
            ]);

            return vnode;
        }
    };

    page.views.sig = signInPage.view;
}());
