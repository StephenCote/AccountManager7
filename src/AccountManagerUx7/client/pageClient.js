(function(){

    let undef;

    /// Note: These only apply to a handful of system roles within an organization.
    ///
    function getContextRoles(){
        return {
            api : false,
            userReader : false,
            roleReader : false,
            permissionReader : false,
            author : false,
            user : false,
            systemAdmin : false,
            accountAdmin : false,
            objectAdmin : false,
            dataAdmin : false
        }
    }

    function newContextModel(){
        return {
            streams : {},
            contextObjects : {},
            entitlements : {},
            tags : {},
            controls: {},
            requests: {},
            contextViews : {},
            roles : getContextRoles(),
            notifications: [],
            messages: [],
            pendingEntity : undef
        };
    }

    function clearContextObject(id){
        let ctx = page.context();
        delete ctx.streams[id];
        delete ctx.contextObjects[id];
        delete ctx.entitlements[id];
        delete ctx.tags[id];
        delete ctx.controls[id];
        delete ctx.requests[id];
        delete ctx.contextViews[id];
    }

    let contextModel = newContextModel();

    let dialogUp = false;
    let uidStub = "xxxxxxxx-xxxx-9xxx-yxxx-xxxxxxxxxxxx";
    let luidStub = "xxxx-xxxx";

    let page = {
        profile,
        confirm,
        imageView,
        loadDialog,
        setDialog,
        endDialog,
        createDialog : createDialog,
        clearServerCache,
        cleanup,
        clearCache : clearPageCache,
        components: {},
        patchSet,
        user : null,
        application : undef,
        navigable : undef,
        contentRouter : contentRouter,
        authenticated : () => (page.user != null),
        getRawUrl : getRawUrl,
        home : goHome,
        nav: goNav,
        chat: goChat,
        member: promiseMember,
        parentType: promiseParentType,
        moveObject: promiseMoveObject,
        reparentObject: promiseReparentObject,
        countObjects : promiseCountObjects,
        deleteObject: promiseDeleteObject,
        listObjects : promiseListObjects,
        //listObjectsInParent : promiseListObjectsInParent,
        findObject : promiseFindObject,
        makePath: promiseMakePath,
        stream : promiseStream,
        openObject : promiseOpenObject,
        search: promiseSearch,
        searchByName : promiseSearchObjectByName,
        count: promiseCount,
        openObjectByName : promiseOpenObjectByName,
        createObject: promiseCreate,
        patchObject: promisePatch,
        uriStem : getURIStem,
        uri : getURI,
        iconButton : iconButton,
        context : function(){ return contextModel;},
        clearContextObject : clearContextObject,
        listByType,
        navigateToPath : navigateToPathId,
        logout : async function(){
            clearPageCache();
            await uwm.logout();
            m.route.set("/sig");
        },
        removeDuplicates : removeDuplicates,
        tags : getTags,
        controls: getControls,
        requests: getRequests,
        entitlements : getEntitlements,
        resetCredential,
        wss: {
            connect: wssConnect,
            close: wssClose,
            send: wssSend
        },
        messages,
        cleanupUserRemains,
        uid : function(){
            return uid(uidStub);
        },
        luid : function(){
            return uid(luidStub);
        },
        views: {},
        setInnerXHTML,
        favorites,
        systemLibrary,
        blobUrl : toBlobUrl
    };

    async function systemLibrary(model){
        let grp;
        if(am7model.system.library[model]){
            grp = await page.findObject("auth.group", "data", am7model.system.library[model]);
        }
        return grp;
    }

    async function favorites(){
        let origin = page.user.homeDirectory;

        let grp = await page.openObjectByName("auth.group", origin.objectId, "Favorites");
        if(!grp){
            grp = am7model.newPrimitive("auth.group");
            grp.type = "bucket";
            grp.parentId = origin.id;
            grp.name = "Favorites";
            await page.createObject(grp);
            am7client.clearCache("auth.group", true);
            grp = await page.openObjectByName("auth.group", origin.objectId, "Favorites");
        }
        return grp;
    }

    function uid(temp){
        return temp.replace(/[xy]/g, function (c) {
            const r = Math.random() * 16 | 0, 
                v = c == 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }

    async function cleanupUserRemains(name){

        let g = await promiseFindObject("auth.group", "data", "/home/" + name);
        if(g){
            console.log("Cleaning up home directory");
            await promiseDeleteObject("auth.group", g.objectId);
        }
        let r = await promiseFindObject("auth.role", "user", "/home/" + name);
        if(r){
            console.log("Cleaning up home role");
            await promiseDeleteObject("auth.role", r.objectId);
        }
        let p = await promiseFindObject("auth.permission", "user", "/home/" + name);
        if(p){
            console.log("Cleaning up home permission");
            await promiseDeleteObject("auth.permission", p.objectId);
        }
        let pg = await promiseFindObject("auth.group", "data", "/Persons")
        if(pg){
            let up = await promiseOpenObjectByName("identity.person", pg.objectId, name);
            if(up){
                console.log("Cleaning up person object");
                await promiseDeleteObject("identity.person", up.objectId);
            }
        }
    }

    async function messages(){
        contextModel.notifications = [];
        //let listPathBase = (am7community.getCommunityMode() ? getRawUrl() : "") + "/list/message";
        let listPathBase = getRawUrl() + "/list/message";
        let dir = await page.makePath("auth.group", "data", "~/.messages");
        m.route.set(listPathBase + "/" + dir.objectId);
    }

    let webSocket;
    function wssClose(){
        if(!webSocket || webSocket.readyState === WebSocket.CLOSED){
            // console.log("Connection already closed");
            return;
        }
        // console.log("Closing WebSocket");
        webSocket.close();
        webSocket = undefined;
    }

    function wssConnect() {
        if (webSocket !== undefined && webSocket.readyState !== WebSocket.CLOSED) {
            console.warn("Connection is already open");
            return;
        }
        /*
        if(!page.user){
            console.warn("Connection requires authentication");
            return;
        }
        */
        return new Promise((res, rej) => {
            /// /" + (page?.user?.objectId)
            let wsUrl = g_application_path.replace(/^http[s]*/, "wss") + "/wss";
            webSocket = new WebSocket(wsUrl);
    
            webSocket.onopen = function(event) {
                res(event);
            };
    
            webSocket.onmessage = function(event) {
                //console.log("WebSocket message received: " + event.data);
                if(event.data){
                    routeMessage(JSON.parse(event.data));
                }
                //m.redraw();
            };
    
            webSocket.onclose = function(event) {
                console.log("WebSocket Closed ... TODO: Add reconnect");
                
            };
        });
      }

      function routeMessage(msg){
        if(msg.token){
            page.token = msg.token;
        }
        if(msg.chirps){
            msg.chirps.forEach((s) => {
                // console.log("Chirp", s);
            });
        }
      }
  
      async function wssSend(name, message, recipient) {
        let recipientId = null;
        if(typeof recipient == "number") recipientId = recipient;
        else if(typeof recipient == "string"){
            let obj = await promiseOpenObjectByName("USER", null, recipient);
            if(obj){
                recipientId = obj.id;
            }
        }
        if(recipient && !recipientId){
            console.warn("Invalid recipientId (" + recipientId + ") for recipient " + recipient);
            return;
        }
        let msg = {
            data : uwm.base64Encode(message),
            name : name,
            // userId: (page?.user?.objectId),
            recipientId,
            recipientType: (recipientId != null ? "USER" : "UNKNOWN")

        };
        return webSocket.send(JSON.stringify({schema: "socketMessage", token: page?.token, message: msg}));
      }
 

    async function resetCredential(obj, newCredential, currentCredential){

        if(!obj){
            obj = page.user;
        }
        let authReq = {
			schema: "authenticationRequest",
			credential: uwm.base64Encode(newCredential),
			credentialType: "hashed_password",
            checkCredential: (currentCredential ? uwm.base64Encode(currentCredential) : null),
            checkCredentialType: (currentCredential ? "hashed_password" : null)
		};
        let newCred = await new Promise((res, rej)=>{
            am7client.newPrimaryCredential(obj[am7model.jsonModelKey], obj.objectId, authReq, function(v){
                res(v);
            });
        });

        return newCred;
    }

    function getEntitlements(type, id){
        return new Promise((res, rej) => {
            if(contextModel.entitlements[id]) res(contextModel.entitlements[id]);
            else am7client.entitlements(type, id, function(v){
                contextModel.entitlements[id] = v || [];
                res(v);
            });
        });
    }
    function getRequests(type, id, startRecord, recordCount){
        let sr = startRecord || 0;
        let rc = recordCount || 25;
        return new Promise((res, rej) => {
            if(contextModel.requests[id]) res(contextModel.requests[id]);
            else am7client.listRequests(type, id, sr, rc, function(v){
                contextModel.requests[id] = v || [];
                res(v);
            });
        });
    }
    function getControls(type, id, startRecord, recordCount){
        let sr = startRecord || 0;
        let rc = recordCount || 25;
        return new Promise((res, rej) => {
            if(contextModel.controls[id]) res(contextModel.controls[id]);
            else am7client.listControls(type, id, sr, rc, function(v){
                contextModel.controls[id] = v || [];
                res(v);
            });
        });
    }

    function getTags(type, id){
        return new Promise((res, rej) => {
            if(contextModel.tags[id]) res(contextModel.tags[id]);
            else am7client.findTags(type, id, function(v){
                contextModel.tags[id] = v || [];
                res(v);
            });
        });
    }
    function clearServerCache(){
        return new Promise((res, rej)=>{
            am7client.clearCache(null, false, function(){
                res(true);
            });
        });
    }
    async function cleanup(){
        await am7client.cleanup();
        await am7client.clearCache();
    }
    function clearPageCache(){
        
        am7client.clearCache(false, true);

        contextModel = newContextModel();

        page.token = null;
        page.user = null;
        page.application = null;

    }

    function navigateToPathId(type, modType, path, handler){
        console.log(type, modType, path);
        return new Promise((res, rej)=>{
            if(am7model.isGroup(modType)){
                am7client.find("auth.group","UNKNOWN", path,function(v){
                    if(v != null){
                        res(v.objectId);
                    }
                    else res(null);
                });
            }
            else res(null);
        });
    }

    let dialogCfg;
    function endDialog(){
        dialogUp = false;
        m.redraw();
    }
    function setDialog(cfg){
        dialogCfg = cfg;
        dialogUp = true;
        m.redraw();
    }
    async function profileEntity(){
        let grp = await promiseMakePath("auth.group", "DATA", am7view.path("data.data"));
        let obj;
        try{
            obj = await promiseSearchObjectByName("data.data", grp.id, ".profile");
        } catch(e){
            console.error(e);
        }
        if(!obj){
            obj = am7model.newPrimitive("data.data");
            obj.name = ".profile";
            obj.mimeType = "application/json";
            obj.dataBytesStore = uwm.base64Encode(JSON.stringify({schema: "userProfile"}));
            let bUp = await promiseCreate(obj);
            obj = await promiseSearchObjectByName("data.data", grp.objectId, ".profile");
        }
        return obj;
    }
    function imageView(entity, fConfirm, fCancel){
        dialogCfg = {
            label: "Viewer",
            entityType: "imageView",
            size: 75,
            data: {
                entity: {schema:"imageView", image:entity}
            },
            cancel: function(data){
                if(fCancel) fCancel(data);
                dialogUp = false;
                m.redraw();
            }
        };
        dialogUp = true;
        m.redraw();
    }
    function confirm(sMessage, fConfirm, fCancel){
        dialogCfg = {
            label: "Confirmation",
            entityType: "confirmation",
            size: 75,
            data: {
                entity: {textData: sMessage}
            },
            confirm: function(data){
                if(fConfirm) fConfirm(data);
                dialogUp = false;
                m.redraw();
            },
            cancel: function(data){
                if(fCancel) fCancel(data);
                dialogUp = false;
                m.redraw();
            }
        };
        dialogUp = true;
        m.redraw();
    }

    async function profile(){
        let obj = await profileEntity();
        let objj = {schema: "userProfile"};
        if(obj.dataBytesStore && obj.dataBytesStore.length){
            console.log(uwm.base64Decode(obj.dataBytesStore));
            objj = JSON.parse(uwm.base64Decode(obj.dataBytesStore));
            if(!objj[am7model.jsonModelKey]) objj[am7model.jsonModelKey] = "userProfile";
        }
        dialogCfg = {
            label: "Profile",
            entityType: "userProfile",
            size: 75,
            data: {
                object: obj,
                entity: objj
            },
            confirm: updateProfile,
            cancel: cancelDialog,
        };
        dialogUp = true;
        m.redraw();
    }
    
    /// TODO: Replace this by having system.user inherit identity.profile
    /// Need to fix how referenced objects are created
    /// In fact, the whole reference approach should probably just be removed in favor of participations
    /// The new version of the back end only auto-creates when the parent object is created, and won't try to auto create 
    async function updateProfile(obj){

        let a1 = am7client.getAttribute(page.user, "v1-profile");
        let a2 = am7client.getAttribute(page.user, "v1-profile-path");

        obj.entity.profilePortraitPath = undefined;
        if(obj.entity.profilePortrait){
            let port = await promiseOpenObject("data", obj.entity.profilePortrait);
            obj.entity.profilePortraitPath = am7client.dotPath(port.organizationPath) + "/data.data" + port.groupPath + "/" + port.name;
        }

        if(obj.entity.profilePortraitPath){
            await am7client.patchAttribute(page.user, "v1-profile-path",obj.entity.profilePortraitPath);
        }
        if(obj.entity.profileId){
            await am7client.patchAttribute(page.user, "v1-profile", obj.entity.profileId);
        }

        obj.object.dataBytesStore = uwm.base64Encode(JSON.stringify(obj.entity));
        let bUp = await promisePatch(obj.object);

        dialogUp = false;
        m.redraw();
    }

    function cancelDialog(obj){
        dialogUp = false;
        m.redraw();
    }

    function loadDialog(){
        if(!dialogCfg || !dialogUp) return "";
        return createDialog(dialogCfg.size, dialogCfg.entityType, dialogCfg.data, dialogCfg.label, dialogCfg.confirm, dialogCfg.cancel);
    }

    function createDialog(size, entityType, data, title, fConf, fCanc){
        let sizeCls = "dialog-" + (size || "50");
        if(!data.view){
            data.view = page.views.object().view;
        }
        let mconf = "";
        if(fConf){
            mconf = m("button", {class: "dialog-button", onclick: async function(){ if(data.view) data.view.sync(); await fConf(data);}}, [
                m("span", {class: "material-symbols-outlined material-symbols-outlined-white md-18 mr-2"}, "check"),
                "Ok"
            ])
        }
        return [
            m("div", {class: "screen-glass screen-glass-open"}, [
                m("div", {class: "screen-glass-gray"})
            ]),
            m("div", {class: "dialog-container"}, [
                m("div", {class: "dialog " + sizeCls}, [
                    m("div", {class: "list-results-container"}, [
                        m("div", {class: "list-results"}, [
                            m("div",{class: "result-nav-outer"},[
                                m("div",{class: "result-nav-inner"},[
                                        m("nav",{class: "result-nav"},[
                                        m("h3", {class: "box-title"}, title)
                                    ])
                                ])
                            ]),
                            m(data.view, {
                                freeForm: true,
                                freeFormType: entityType,
                                freeFormEntity: data.entity
                            }),
                            m("div",{class: "result-nav-outer"},[
                                m("div",{class: "result-nav-inner"},[
                                    m("nav",{class: "result-nav"},[
                                       mconf,
                                        m("button", {class: "dialog-button", onclick: async function(){ await fCanc(data);}},[
                                            m("span", {class: "material-symbols-outlined material-symbols-outlined-white md-18 mr-2"}, "cancel"),
                                            "Cancel"
                                        ])
                                    ])
                                ])
                            ]),
                        ])
                    ])
                ])
            ])
        ];
    }

    function listByType(type, objectId, bParent){
        //let viewPath = (am7community.getCommunityMode() ? getRawUrl() : "") + "/" + (bParent ? "p" : "") + "list/" + type + "/" + objectId;
        /// getRawUrl() + 
        let viewPath = "/" + (bParent ? "p" : "") + "list/" + type + "/" + objectId;
        m.route.set(viewPath);
    }

    function iconButton(sClass, sIco, sLabel, fHandler){
        return m("button",{onclick : fHandler, class: sClass}, (sIco ? m("span",{class: "material-symbols-outlined material-icons-24"},sIco) : ""), (sLabel || ""));
    }
    function getURI(){
        let url = page.getRawUrl();
        let params = [];
        if(page.pagination.paginating){
            let pages = page.pagination.pages();
            if(pages != null && pages.currentPage > 0){
                if(pages.filter != null) params.push("filter=" + encodeURI(pages.filter));
                params.push("startRecord=" + ((pages.currentPage - 1)*pages.recordCount));
                params.push("recordCount=" + pages.recordCount);
            }
        }
        return url + (params.length ? "?" + params.join("&") : "");
    }
    function getURIStem(){
        let stem = [];
        let openItems = [];
        Object.keys(contextModel.contextViews).forEach((k)=>{
          openItems.push("{" + contextModel.contextViews[k].mode + "." + contextModel.contextViews[k].type + "." + k + "}");
        });
        if(openItems.length) stem.push("open=" + openItems.join(","));
        return stem.join("&");
    }

    function patchSet(object){
        let set = {
            patchType: object[am7model.jsonModelKey],
            patches: [],
            identityField: "OBJECTID",
            identity: object.objectId

        };
        return {
            set: function(){
                return set;
            },
            patch: function(col, val){
                let useVal = val;
                if(val instanceof Date) useVal = val.getTime();
                else if(typeof val == "number") useVal = val + "";
                else if(typeof val == "boolean" || typeof val == "object") useVal = val.toString();
                set.patches.push({valueField: col, value: useVal});
            }
        };
    }

    async function promiseCreate(object){
        return new Promise((res, rej)=>{
            am7client.create(object[am7model.jsonModelKey], object, function(v){
                res(v);
            });
        })
    }
    async function promiseMakePath(type, subType, path){
        return new Promise((res, rej)=>{
            am7client.make(type, subType, path,function(v){
                if(v != null){
                    res(v);
                }
                else res(null);
            });
        });
    }
    async function promiseFindObject(type, subType, path){
        return new Promise((res, rej)=>{
            am7client.find(type, subType, path,function(v){
                res(v);
            });
        });
    }
    /*
    async function promiseListObjectsInParent(sType, id, start, length){
        return new Promise((res, rej)=>{
            am7client.listInParent(sType, id, start, length, function(v){
                res(v);
            });
        });
    }
    */
    async function promiseReparentObject(child, newParent){
        let modType = am7model.getModel(child[am7model.jsonModelKey]);
        let modType2 = am7model.getModel(newParent[am7model.jsonModelKey]);
        if(!am7model.inherits(modType2, "common.parent")){
            console.warn("Not a parentable object");
            return false;
        }

        child.parentId = newParent.id;

        let b = await promisePatch(child);
        am7client.clearCache(child[am7model.jsonModelKey]);
        return b;
    }
    /*
    async function promiseMember(object, actor, enabled){
        return new Promise((res, rej)=>{
            am7client.member(object[am7model.jsonModelKey], object.objectId, null, actor[am7model.jsonModelKey], actor.objectId, enabled, function(v){
                res(v);
            })

        });
    }
    */

    async function promiseParentType(sType){
        return new Promise((res, rej) => {
            am7client.user(sType, "USER", function(v){
                uwm.getDefaultParentForType(sType, v).then((parObj) =>{
                    if(parObj != null){
                        res(parObj);
                    }
                    else{
                        rej();
                    }
                });
            });
        });
    }
    async function promiseMoveObject(object, group){
        let modType = am7model.getModel(object[am7model.jsonModelKey]);
        let bUp = false;
        if(object[am7model.jsonModelKey].match(/^auth\.group$/gi)){
          console.warn("Use reparent to move groups");
          return;
        }
        if(object[am7model.jsonModelKey].match(/^data\.data$/gi)){
          delete object.dataBytesStore;
        }
        if(am7model.isGroup(modType)){
          object.groupId = group.id;
          object.groupPath = group.path;
          bUp = await promisePatch(object);
        }
        return bUp;
      }

      async function promiseMember(sType, sId, sActorType, sActorId, bEnable){
        return new Promise((res, rej)=>{
            am7client.member(sType, sId, null, sActorType, sActorId, bEnable, function(v){
                res(v);
            });
        });
    }

    async function promiseDeleteObject(sType, id){
        return new Promise((res, rej)=>{
            am7client.delete(sType, id, function(v){
                res(v);
            });
        });
    }
    async function promiseListObjects(sType, id, sFields, start, length){
            return am7client.list(sType, id, sFields, start, length);
    }

    async function promiseCountObjects(sType, id){
            return am7client.count(sType, id);
    }

    async function promiseSearchObjectByName(type, id, name){
        
        if(typeof id == "string"){
            let q = am7view.viewQuery(am7model.newInstance("auth.group"));
            q.field("objectId", id);
            let qr = await promiseSearch(q);
            if(qr && qr.results){
                id = qr.results[0].id;
            }
        }
        if(typeof id == "string"){
            console.error("Expected a numeric groupId");
            return null;
        }
        let q = am7view.viewQuery(am7model.newInstance(type));
        q.field("groupId", id);
        return new Promise( (res, rej) => {
            page.search(q).then( (g2) => {
                let og;
                if(g2 && g2.results){
                    og = g2.results[0];
                }
                res(og);
            })
        });
    }

    async function promiseOpenObjectByName(type, id, name, bParent){
        return new Promise ((res, rej) => {
            let o = null;
            am7client.getByName(type, id, name, function(o){
                res (o);
            }).catch( () => {
                res(null);
            });
        });
    }

    async function promiseStream(id, start, length){
        if(typeof start == "undefined"){
            start = 0;
        }
        if(typeof length == "undefined"){
            length == 0;
        }
        if(typeof id == "object"){
            if(!length) length = id.size;
            id = id.objectId;
        }
        return (contextModel.streams[id] ? Promise.resolve(contextModel.streams[id]) : new Promise((res, rej)=>{
            am7client.stream(id, start, length, function(v){
                contextModel.streams[id] = v;
                res(v);
            });
        }));
    }

    async function promiseSearch(query){
        return am7client.search(query);
    }
    async function promiseCount(query){
        return am7client.search(query, undefined, true);
    }
    async function promisePatch(object){
        return new Promise((res, rej)=>{
            am7client.patch(object[am7model.jsonModelKey], object, function(v){
                res(v);
            });
        });
    }
    async function promiseOpenObject(type, id, mode){
        return (contextModel.contextObjects[id] ? Promise.resolve(contextModel.contextObjects[id]) : new Promise((res, rej)=>{
            let alt = (typeof id == "string" && id.match(/^am:/));
            am7client[alt ? "getByUrn" : "get"](type, id, function(v){
                contextModel.contextObjects[id] = v;
                contextModel.contextViews[id] = {type: type, mode:mode};
                res(v);
            });
        }));
    }
    function removeDuplicates(array, key){
        return array.filter((obj, index, self) => index === self.findIndex((obj2) => (obj2.objectId === obj.objectId)));

    }
    function filterObject(object, keyMatch){
        return Object.keys(object).filter((key) => { 
            return key.includes(keyMatch) == false;
        }).reduce(( prev, key) => {
            return Object.assign(prev, { [key]: object[key] });
        }, {});
    }

     function goNav(){
        m.route.set("/nav");
    }
    
    function goChat(){
        m.route.set("/chat");
    }

    function goHome(){
        let url = "/main";
       m.route.set(url);
    }
    function getRawUrl(){
        let url = m.route.get();
        let idx = url.indexOf("?");
        if(idx > -1) url = url.substring(0, idx);
        return url;
    }
    function contentRouter(){
        let curRout = m.route.get();
        if(curRout != "/sig" && !page.authenticated()){
            console.log("Force to /sig");
            return m.route.set("/sig");
        }
        else{
            return m(page.components.panel);
        }
    }
   
    /// given a data: url, exchange it for a blob url which caches the data to the client to be referenced by the blob id
    ///
    function toBlobUrl(dataURI){
        /// https://stackoverflow.com/questions/9388412/data-uri-to-object-url-with-createobjecturl-in-chrome-ff
        var mime = dataURI.split(',')[0].split(':')[1].split(';')[0];
        /// TODO: Replace with Buffer.from(dataURI.split(',')[1],"base64"); 
        var binary = window.atob(dataURI.split(',')[1]);
        var a = [];
        for (var i = 0; i < binary.length; i++) {
            a.push(binary.charCodeAt(i));
        }
        return URL.createObjectURL(new Blob([new Uint8Array(a)], {type: mime}));
    }

    function isAttributeSet (o, n, b) {
        if (o == null || (typeof o != "object") || o.nodeType != 1) return 0;
        var s = o.getAttribute(n);
        if (typeof s != "string" || s.length == 0) return 0;
        return (b ? s : 1);
    }


    function setInnerXHTML(t, s, p, d, z, c, h, ch) {

        var y, e, a, l, x, n, v, r = 0, b, f;


        if (!d) d = document;

        b = (d == document ? 1 : 0);

        if (!p)
            HemiEngine.xml.removeChildren(t);

        y = (s && typeof s == "object") ? s.nodeType : (typeof s == "string") ? 33 : -1;

        try {

            switch (y) {
                case 1:
                    if (h) {
                        e = s.cloneNode(false);
                    }
                    else {
                        f = s.nodeName;
                        if (typeof ch == "function") f = ch(y, f);
                        if (!f) return 0;
                        e = d.createElement(f);
                        a = s.attributes;
                        l = a.length;
                        for (x = 0; x < l; x++) {
                            n = a[x].nodeName;
                            v = a[x].nodeValue;
                            if (typeof ch == "function") {
                                n = ch(2, n);
                                v = ch(2, v);
                            }



                            if (b && n == "style") {
                                e.style.cssText = v;
                            }

                            else if (b && n == "id") {
                                e.id = v;
                            }



                            else if (b && n == "class") {
                                e.className = v;
                            }


                            else if (b && n.match(/^on/i)) {
                                eval("e." + n + "=function(){" + v + "}");
                            }
                            else {
                                e.setAttribute(n, v);
                            }
                        }
                    }
                    if (!z && s.hasChildNodes()) {
                        a = s.childNodes;
                        l = a.length;
                        for (x = 0; x < l; x++)
                           page.setInnerXHTML(e, a[x], 1, d, z, c, h, ch);

                    }

                    if (b && s.nodeName.match(/input/i) && isAttributeSet(s,"checked")) {
                        e.checked = true;
                    }
                    t.appendChild(e);
                    r = e;
                    break;
                case 3:
                    e = s.nodeValue;
                    if (typeof ch == DATATYPES.TYPE_FUNCTION) e = ch(y, e);
                    if (e) {
                        e = e.replace(/\s+/g, " ");
                        t.appendChild(d.createTextNode(e));
                        r = e;
                    }
                    break;

                case 4:
                    e = s.nodeValue;
                    if (typeof ch == DATATYPES.TYPE_FUNCTION) e = ch(y, e);
                    t.appendChild(d.createCDATASection(e));
                    break;
                case 8:

                    break;
                case 33:
                    e = s;
                    if (typeof ch == DATATYPES.TYPE_FUNCTION) e = ch(y, e);
                    if (e) {
                        if (!c) {
                            e = e.replace(/^\s*/, "");
                            e = e.replace(/\s*$/, "");
                            e = e.replace(/\s+/g, " ");
                        }
                        t.appendChild(d.createTextNode(e));
                        r = e;
                    }
                    break;
                default:

                    break;
            }

        }
        catch (e) {
            console.error(e);
        }

        return r;
    }

    let test = typeof module;

    if (test !== "undefined") {
        module.exports = page;
    } else {
        window.page = page;
    }
}());
