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
        /*
        "/community/:communityId" : page.views.community,
        "/community/:communityId/:projectId" : page.views.community,
        "/community/:communityId/list/:type" : page.views.list().view,
        "/community/:communityId/:projectId/list/:type" : page.views.list().view,
        "/community/:communityId/:projectId/list/:type/:objectId" : page.views.list().view,
        */
        "/game" : page.views.game,
        "/game/:name" : page.views.game
    };
    
    

    function init(){
        refreshApplication();
    }
    function refreshApplication() {
        ///page.wss.connect();
        am7client.principal().then((usr) => {
            let rt = window.location.hash.slice(window.location.hash.indexOf("/"));
            page.application = undefined;
            page.user = usr;
            
            if(usr == null){
                page.wss.close();
                // (bint ? m.route(document.body, rt, routes) : m.route.set(rt));
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

        /*

        am7client.application().then((app)=>{
            page.application = app;
            page.user = app ? app.user : null;

            if(!app || app == null){
                console.log("Not authenticated: route to login");
                page.wss.close();
                rt = "/sig";
                m.route.set(rt);
            }
            else{
                setContextRoles(app);
                if(!rt.length || rt == "/sig") rt = "/main";
                page.wss.connect();
            }
            m.route(document.body, rt, routes);
        });
        */
    };

    init();

    /*
    function router(){
        let curRout = m.route.get();
        if(curRout != "/sig" && !page.authenticated()){
            console.log("Force to /sig");
            return m.route.set("/sig");
        }
        let context = m.route.param("context");
        console.log("Route to " + context);
        let view;
        switch(context){
            case "list": view = page.views.list; break;
            case "community": view = page.views.community; break;
            case "new": view = page.views.object; break;
            default: view = page.views.main; break;
        }
        return view;
    }
    */
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

    /*
    function sessionProcessor(type, data){
        page.clearCache();
        //am7community.setCommunityMode(false);
        //toggleCommunity
        switch(type){
            case "onsessionrefresh":
                page.user = data;
                if(!data) page.wss.close();
                else page.wss.connect();
                
                if(!data || !data == null){
                    page.router("/sig");
                }
                else{
                    am7client.application().then((app)=>{
                        page.application = app;
                        setContextRoles(app);
                        page.router("/main");
                    });
                }
                break;
        }
    }
    */

}());
