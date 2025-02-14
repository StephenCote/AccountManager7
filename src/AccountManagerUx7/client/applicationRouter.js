(function(){

    let routes = {
        
        "/sig": page.views.sig,
        "/saur": page.views.saur,
        "/main" : page.views.main,
        "/nav" : page.views.navigator().view,
        "/chat" : page.views.chat().view,
        "/new/:objectId" : page.views.object().view,
        "/new/:type/:objectId" : page.views.object().view,
        "/pnew/:type/:objectId" : page.views.object().view,
        "/view/:type/:objectId" : page.views.object().view,
        "/list/:type" : page.views.list().view,
        "/list/:type/:objectId" : page.views.list().view,
        "/plist/:type/:objectId" : page.views.list().view,
        "/game" : page.views.game,
        "/game/:name" : page.views.game
    };
    
    

    function init(){
        window.addEventListener("beforeunload", takeDown);
        refreshApplication();
    }
    
    function takeDown(){
        console.log("Unload");
        if(page.wss){
            page.wss.close();
        }
    }

    function refreshApplication() {
        
        am7client.principal().then((usr) => {
            let rt = window.location.hash.slice(window.location.hash.indexOf("/"));
            page.application = undefined;
            page.user = usr;
            
            if(usr == null){
                page.wss.close();
                rt = "/sig";
                m.route.set(rt);
                m.route(document.body, rt, routes);
                
            }
            else{
                am7client.currentOrganization = usr.organizationPath;
                am7client.application().then((app) => {
                    page.application = app;
                    if(app && app != null){
                        setContextRoles(app);
                        if(!rt.length || rt == "/sig") rt = "/main";
                        page.wss.connect();
                    }
                    else{
                        rt = "/sig";
                    }
                    m.route.set(rt);
                    m.route(document.body, rt, routes);
                });
            }
            
           
        });

        page.router = {
            refresh: refreshApplication,
            routes,
            route: function(path, node){
                m.route.set(path);
            }
        };
    };

    init();

    function hasRole(roles, role){
        return (roles.filter(r => r.name.toLowerCase() === role.toLowerCase()).length > 0);
    }
    function setContextRoles(app){
        let ctxRoles = page.context().roles;
        let roles = (app && app.userRoles ? app.userRoles : []);

        ctxRoles.user = hasRole(roles, 'accountusers');
        ctxRoles.api = hasRole(roles, 'apiusers');
        ctxRoles.userReader = hasRole(roles, 'accountusersreader');
        ctxRoles.roleReader = hasRole(roles, 'rolereaders');
        ctxRoles.permissionReader = hasRole(roles, 'permissionreaders');
        ctxRoles.author = hasRole(roles, 'articleauthors');
        ctxRoles.systemAdmin = hasRole(roles, 'systemadministrators');
        ctxRoles.accountAdmin = hasRole(roles, 'accountadministrators');
        ctxRoles.dataAdmin = hasRole(roles, 'dataadministrators');
        ctxRoles.objectAdmin = hasRole(roles, 'objectadministrators');
        ctxRoles.scriptExecutor = hasRole(roles, 'scriptexecutors');
        ctxRoles.admin = (ctxRoles.systemAdmin || ctxRoles.accountAdmin);
    }

}());
