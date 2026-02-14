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
            favorites : {},
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

    let uidStub = "xxxxxxxx-xxxx-9xxx-yxxx-xxxxxxxxxxxx";
    let luidStub = "xxxx-xxxx";

    let toast = [];
    let toastConfig = {
        info: {
            ico: "info",
            style: "text-gray-500 bg-white dark:bg-black dark:text-gray-300",
            icoStyle: "text-blue-500 dark:text-blue-300",
            timeout: 5000
        },
        success: {
            ico: "check",
            style: "text-gray-500 bg-green-200 dark:bg-green-800 dark:text-gray-300",
            icoStyle: "text-black dark:text-white",
            timeout: 5000
        },
        warn: {
            ico: "warning",
            style: "bg-yellow-200 text-black dark:text-white dark:bg-yellow-800",
            icoStyle: "text-black dark:text-white",
            timeout: 10000
        },
        error: {
            ico: "stop",
            style: "bg-red-200 text-black dark:text-white dark:bg-red-700",
            icoStyle: "text-black dark:text-white",
            timeout: 15000
        }
    };

    let page = {

        profile,
        imageView,
        imageGallery,
        clearToast,
        reconnect,
        toast: addToast,
        toasts: () => toast,
        loadToast,
        clearServerCache,
        cleanup,
        clearCache : clearPageCache,
        components: {},
        user : null,
        application : undef,
        navigable : undef,
        contentRouter : contentRouter,
        authenticated : () => (page.user != null),
        getRawUrl : getRawUrl,
        home : goHome,
        nav: goNav,
        getTag,
        chat: goChat,
        member: promiseMember,
        parentType: promiseParentType,
        moveObject: promiseMoveObject,
        reparentObject: promiseReparentObject,
        countObjects : promiseCountObjects,
        deleteObject: promiseDeleteObject,
        listObjects : promiseListObjects,
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
        checkFavorites,
        isFavorite,
        favorite,
        favorites,
        systemLibrary,
        blobUrl : toBlobUrl,
        sessionName: function() {
            let a = ["turtle", "bunny", "kitty", "puppy", "duckling", "pony", "fishy", "birdie"];
            let b = ["fluffy", "cute", "ornery", "obnoxious", "scratchy", "licky", "cuddly", "mangy"];
            let c = ["little", "tiny", "enormous", "big", "skinny", "lumpy"];
      
            return b[parseInt(Math.random() * b.length)] + " " + c[parseInt(Math.random() * c.length)] + " " + a[parseInt(Math.random() * a.length)];
          },
        searchFirst,
        chatAvatar,
        normalizePath,
        chatStream: undefined,
        audioStream: undefined,
        gameStream: undefined,
        imageTags: (id) => {
            return am7client.getImageTags(id);
        },
        applyImageTags: async (id) => {
            let tags = await am7client.applyImageTags(id);
            await am7client.clearCache("data.data");
            await am7client.clearCache("data.tag");
            return tags;
        },
        testMode: (new URLSearchParams(window.location.search)).get("testMode") === "true",
        productionMode: (new URLSearchParams(window.location.search)).get("productionMode") === "true"

    };


    function normalizePath(path, grp){
      let opath;
      let cnt = grp || page.user.homeDirectory;
      if(cnt && cnt.path){
        if(path.startsWith("./")){
          opath = cnt.path + path.substring(1);
        }
        else if(path.startsWith("../")){
          let p = cnt.path.split("/");
          p.pop();
          opath = p.join("/") + path.substring(2);
        }
        else if(path.startsWith("/") || path.startsWith("~")){
          opath = path;
        }
        else{
          opath = cnt.path + "/" + path;
        }
      }
      return opath;
    }

    async function chatAvatar(){
        await am7model.forms.commands.character("Basi Lisk", undefined, 42, "M", "/media/logo_512.png");
    }
    function isFavorite(obj){
        let ctx = contextModel;
        let type = obj[am7model.jsonModelKey];
        return (ctx.favorites[type] != undefined && ctx.favorites[type].filter((o)=>o.objectId == obj.objectId).length)
    }
    async function favorite(obj){
        let set = !isFavorite(obj);
        let ctx = contextModel;
        //ctx.favorites[obj[am7model.jsonModelKey]] = undefined;
        let fav = await favorites();
        let b = await promiseMember(fav[am7model.jsonModelKey], fav.objectId, obj[am7model.jsonModelKey], obj.objectId, set);
        if(!b){
            addToast("warn", "Failed to set favorite status");
        }
        page.context().favorites[obj[am7model.jsonModelKey]] = undefined;
        await am7client.clearCache();
        return b;
    }

    async function checkFavorites(itype){
        let type = itype || m.route.param("type");
        let ctx = contextModel;
        if(type && ctx.favorites[type] == undefined){
          ctx.favorites[type] = [];
          let fav = await favorites();
          am7client.members(fav[am7model.jsonModelKey], fav.objectId, type, 0, 100, function (v) {
            ctx.favorites[type] = v;
          });
        }
      }

    async function getTag(name, type){
        let grp = await page.findObject("auth.group", "data", "~/Tags");
        if(grp == null){
            return;
        }
        let q = am7view.viewQuery(am7model.newInstance("data.tag"));
        q.field("groupId", grp.id);
        q.field("name", name);
        if(type){
            q.field("type", type);
        }
        let qr = await page.search(q);
        let tag;
        if(qr && qr.results && qr.results.length){
            tag = qr.results[0];
        }
        return tag;
    }

    async function systemLibrary(model){
        let grp;
        if(am7model.system.library[model]){
            grp = await page.findObject("auth.group", "data", am7model.system.library[model]);
        }
        return grp;
    }

    async function favorites(){
        if(!page.user){
            return;
        }
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
                // Phase 13e item 17: Reset reconnect state on successful connection (OI-75)
                wsReconnectAttempts = 0;
                wsReconnectDelay = 1000;
                res(event);
            };

            webSocket.onmessage = function(event) {
                if(event.data){
                    routeMessage(JSON.parse(event.data));
                }
            };

            // Phase 13e item 17: Exponential backoff reconnection (OI-75)
            webSocket.onclose = function(event) {
                console.warn("[WebSocket] Closed (code: " + event.code + ")");
                if (wsReconnectAttempts < wsMaxReconnectAttempts) {
                    wsReconnectAttempts++;
                    console.log("[WebSocket] Reconnecting (attempt " + wsReconnectAttempts + ") in " + wsReconnectDelay + "ms...");
                    setTimeout(function(){
                        reconnect(am7client.currentOrganization, page.token, 0);
                    }, wsReconnectDelay);
                    wsReconnectDelay = Math.min(wsReconnectDelay * 2, wsMaxReconnectDelay);
                } else {
                    addToast("error", "Connection lost. Please reload the page.", 0);
                }
            };
        });
      }

      // Phase 13e item 17: WebSocket reconnect state (OI-75)
      let wsReconnectDelay = 1000;
      let wsMaxReconnectDelay = 30000;
      let wsMaxReconnectAttempts = 10;
      let wsReconnectAttempts = 0;

      let maxReconnect = 60;
      async function reconnect(sOrg, sTok, iIter){
        addToast("warn", "Reconnecting ...", 5000);
        let i = 0;
        let iter = (iIter || 0) + 1;
        let org = sOrg || am7client.currentOrganization;
        let tok = sTok || page.token;
        if(!org || !tok){
            clearToast();
            addToast("error", "Unable to reconnect", 5000);
            return;
        }

        /// Test that the server is available
        try{
            i = await m.request({method: "GET", url: g_application_path + "/rest/login/time"});
        }
        catch(e){
            
        }
        
        if(iter >= maxReconnect){
            addToast("error", "Failed to reconnect", 5000);
            return;
        }
        if(i > 0){
            await am7client.loginWithPassword(sOrg, "${jwt}", sTok, function(v){
                clearToast();
                if(v != null){
                    addToast("success", "Reconnected", 5000);
                    page.router.refresh();
                }
                else{
                    addToast("error", "Failed to reconnect", 5000);
                }
            });
        }
        else{
            setTimeout(function(){
                reconnect(sOrg, sTok, iter);
            }, 5000);
        }
      }

      function routeMessage(msg){
        if(msg.token){
            page.token = msg.token;
        }
        if(msg.chirps){
            let c1 = msg.chirps[0] || "";
            if(c1.match(/(chatStart|chatComplete|chatUpdate|chatError)/)){
                if(!page.chatStream){
                    console.error("Chat stream isn't available");
                    return;
                }
                page.chatStream["on" + c1.toLowerCase()](msg.chirps[1], msg.chirps[2], msg.chirps[3]);
            }
            else if(c1.match(/(audioUpdate|audioSTTUpdate|audioError)/)){
                if(!page.audioStream){
                    console.error("Audio stream isn't available");
                    return;
                }
                page.audioStream["on" + c1.toLowerCase()](msg.chirps[1], msg.chirps[2]);
            }
            else if(c1 === "chainEvent"){
                let eventType = msg.chirps[1];
                let eventData = null;
                try { eventData = JSON.parse(msg.chirps[2]); } catch(e) { eventData = msg.chirps[2]; }
                if(page.chainStream){
                    page.chainStream["on" + eventType](eventData);
                }
            }
            else if(c1 === "policyEvent"){
                // Phase 10 (OI-40): Route policy violation events to LLMConnector
                if(window.LLMConnector){
                    let evtData = null;
                    try { evtData = JSON.parse(msg.chirps[2]); } catch(e) { evtData = msg.chirps[2]; }
                    LLMConnector.handlePolicyEvent({ type: msg.chirps[1], data: evtData });
                }
            }
            else if(c1 === "memoryEvent"){
                // Phase 13f item 24: Route memory events to MemoryPanel (OI-71, OI-72)
                let evtData = null;
                try { evtData = JSON.parse(msg.chirps[2]); } catch(e) { evtData = msg.chirps[2]; }
                if (window.MemoryPanel) {
                    MemoryPanel.refresh();
                }
                if (window.LLMConnector && LLMConnector.handleMemoryEvent) {
                    LLMConnector.handleMemoryEvent({ type: msg.chirps[1], data: evtData });
                }
            }
            else if(c1.match(/^game\.action\./)){
                // Game action messages (start, progress, complete, error, interrupt, cancel)
                if(page.gameStream || window.gameStream){
                    let gs = page.gameStream || window.gameStream;
                    gs.routeActionMessage(c1, ...msg.chirps.slice(1));
                }
            }
            else if(c1.match(/^game\./)){
                // Game push messages (situation, state, threat, npc, interaction, time, event)
                if(page.gameStream || window.gameStream){
                    let gs = page.gameStream || window.gameStream;
                    gs.routePushMessage(c1, ...msg.chirps.slice(1));
                }
            }
            else{
                //console.warn("Unhandled message type: " + c1);
            }
            /*
            msg.chirps.forEach((s) => {
                console.log(s);
                page.toast("info", s, 5000);
            });
            */
        }
      }
  
      async function wssSend(name, message, recipient, schema) {
        try{
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
                modelType: schema,
                messageId: page.uid(),
                name : name,
                // userId: (page?.user?.objectId),
                recipientId,
                recipientType: (recipientId != null ? "USER" : "UNKNOWN")

            };
            let dat = JSON.stringify({schema: "message.socketMessage", token: page?.token, message: msg});
            webSocket.send(dat);
        }
        catch(e){
            console.error(e);
        }
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
        page.toast("info", "Cleaning up ...", -1);
        await am7client.cleanup();
        await am7client.clearCache();       
        page.clearToast();
        page.toast("success", "Cleaned up!");
    }
    function clearPageCache(){
        
        am7client.clearCache(false, true);

        contextModel = newContextModel();

        page.token = null;
        page.user = null;
        page.application = null;
        // Phase 13e item 20 (OI-78): Resolved from identity.person under /Persons
        page.userProfilePath = null;

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
        page.components.dialog.setDialog({
            label: "Viewer",
            entityType: "imageView",
            size: 75,
            data: {
                entity: {schema:"imageView", image:entity}
            },
            cancel: function(data){
                if(fCancel) fCancel(data);
                page.components.dialog.cancelDialog();
            }
        });
    }

    async function imageGallery(images, charInst, fCancel){
        let galleryImages = images || [];
        let characterInstance = charInst;

        console.log("imageGallery called with images:", images?.length, "charInst:", charInst);

        // If charInst is provided, try to load all images from the appropriate source
        if(characterInstance && characterInstance.entity){
            let entity = characterInstance.entity;
            let entitySchema = entity[am7model.jsonModelKey] || entity.schema;

            // Check if this is an apparel entity (use gallery field directly)
            if(entitySchema === "olio.apparel"){
                let gallery = entity.gallery || [];
                if(gallery.length > 0){
                    // Put any new images (passed in) at the beginning, avoiding duplicates
                    let existingIds = new Set(gallery.map(r => r.id));
                    let newImages = galleryImages.filter(img => !existingIds.has(img.id));
                    galleryImages = [...newImages, ...gallery];
                }
                // If we have images passed in, use those even if gallery is empty
            }
            // Otherwise, handle charPerson entities (use profile.portrait)
            else {
                let profile = entity.profile;
                let portrait = profile ? profile.portrait : null;
                let profileObjectId = profile ? profile.objectId : null;

                // If profile exists but portrait isn't loaded, or profile is just a reference,
                // query for the profile with portrait attribute
                if(profileObjectId && (!portrait || !portrait.groupId)){
                    let pq = am7view.viewQuery("identity.profile");
                    pq.field("objectId", profileObjectId);
                    pq.entity.request.push("portrait");
                    let pqr = await page.search(pq);
                    if(pqr && pqr.results && pqr.results.length){
                        portrait = pqr.results[0].portrait;
                        // Update the entity's profile portrait reference
                        if(!characterInstance.entity.profile){
                            characterInstance.entity.profile = pqr.results[0];
                        } else {
                            characterInstance.entity.profile.portrait = portrait;
                        }
                        if(!portrait){
                            console.warn("imageGallery: Profile query returned but no portrait found");
                        }
                    } else {
                        console.warn("imageGallery: Profile query returned no results for objectId: " + profileObjectId);
                    }
                } else if(!profileObjectId){
                    console.warn("imageGallery: No profile objectId available", profile);
                }

                if(portrait && portrait.groupId){
                    // Query for all images in the same group as the portrait
                    let q = am7client.newQuery("data.data")
                    q.field("groupId", portrait.groupId);
                    q.entity.request.push("id", "objectId", "name", "groupId", "groupPath", "contentType", "attributes", "tags");

                    q.range(0, 200);
                    q.sort("createdDate");
                    q.order("descending");
                    let qr = await page.search(q);
                    if(qr && qr.results && qr.results.length){
                        // Put any new images (passed in) at the beginning, avoiding duplicates
                        let existingIds = new Set(qr.results.map(r => r.id));
                        let newImages = galleryImages.filter(img => !existingIds.has(img.id));
                        galleryImages = [...newImages, ...qr.results];
                    } else {
                        console.warn("imageGallery: No images found in group: " + portrait.groupId);
                    }
                } else if(portrait && !portrait.groupId){
                    console.warn("imageGallery: Portrait exists but no groupId", portrait);
                } else if(!portrait){
                    console.warn("imageGallery: No portrait available after all attempts");
                }
            }
        } else {
            console.warn("imageGallery: No characterInstance or entity provided", characterInstance);
        }

        console.log("imageGallery: Final galleryImages count:", galleryImages.length, "images:", galleryImages);

        page.components.dialog.setDialog({
            label: "Gallery (" + galleryImages.length + " images)",
            entityType: "imageGallery",
            size: 75,
            data: {
                entity: {schema:"imageGallery", images: galleryImages, currentIndex: 0, characterInstance: characterInstance}
            },
            cancel: function(data){
                if(fCancel) fCancel(data);
                page.components.dialog.cancelDialog();
            }
        });
    }
   

    async function profile(){
        let obj = await profileEntity();
        let objj = {schema: "userProfile"};
        if(obj.dataBytesStore && obj.dataBytesStore.length){
            objj = JSON.parse(uwm.base64Decode(obj.dataBytesStore));
            if(!objj[am7model.jsonModelKey]) objj[am7model.jsonModelKey] = "userProfile";
        }
        page.components.dialog.setDialog({
            label: "Profile",
            entityType: "userProfile",
            size: 75,
            data: {
                object: obj,
                entity: objj
            },
            confirm: updateProfile,
            cancel: page.components.dialog.cancelDialog,
        });
    }
    
    // Phase 13e item 20 (OI-78): Profile update stores via v1-profile attributes
    // as a compatibility bridge. The topMenu now also checks page.userProfilePath
    // which is resolved from the user's identity.person record under /Persons.
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

        page.components.dialog.endDialog();
        m.redraw();
    }


    
    function removeToast(id){
        toast = toast.filter((t)=>{
            return t.id != id;
        });
    }
    function burnToast(nt){
        let o = document.querySelector("#toast-box-" + nt.id);
        if(o){
            o.className = "transition transition-0 toast-box " + toastConfig[nt.type].style;
        }
        window.setTimeout(()=>{
            nt.visible = false;
            removeToast(nt.id);
            m.redraw();
        }, 300);
    }
    
    function clearToast(){
        toast = [];
        m.redraw();
    }

    function addToast(type, msg, timeout){
        let nt = {
            id: page.luid(),
            type,
            message: msg,
            visible: false,
            expiry: timeout || 5000
        };
        toast.push(nt);
        window.setTimeout(()=>{
            nt.visible = true;
            let o = document.querySelector("#toast-box-" + nt.id);
            if(o){
                o.className = "transition transition-100 toast-box " + toastConfig[nt.type].style;
            }
        }, 10);
        if(nt.expiry >= 0){
            window.setTimeout(()=>{
            burnToast(nt);
            }, nt.expiry);
        }
        m.redraw();
        return nt.id;
    }

    /// dark:text-gray-500 dark:hover:text-white dark:bg-gray-800 dark:hover:bg-gray-700
    function loadToast(){
        // transition-full transition-0 transition-100
        let items = toast.map((t)=>{
            let cfg = toastConfig[t.type];
            let closeBtn = m("button", {onclick: ()=>{ burnToast(t);}, type: "button", class: "ms-auto -mx-1.5 -my-1.5  rounded-lg focus:ring-2 focus:ring-gray-300 p-1.5 hover:bg-gray-100 dark:bg-gray-900 dark:text-gray-200 inline-flex items-center justify-center h-8 w-8 "}, 
                m("span", {class: "material-symbols-outlined"}, "close")
            );
            return m("div", {id: "toast-box-" + t.id, class: "transition transition-" + (t.visible ? "100":"0") + " toast-box " + cfg.style}, [
                m("div", {class: cfg.icoStyle + " ico-box"}, [
                    m("span", {class: "material-symbols-outlined"}, cfg.ico)
                ]),
                m("div", {class: "toast-text"}, t.message),
                closeBtn
            ]);
        });
        return (items.length == 0 ? "" : m("div", {class: "toast-container"}, [
            items
        ]));

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

    async function promiseCreate(object){
        return new Promise((res, rej)=>{
            am7client.create(object[am7model.jsonModelKey], object, function(v){
                res(v);
            });
        });
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
        if(name){
            q.field("name", name);
        }
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

    async function searchFirst(model, groupId, name, objectId){
        let q = am7view.viewQuery(am7model.newInstance(model));
        if(groupId){
            q.field("groupId", groupId);
        }
        if(name){
            q.field("name", name);
        }
        if(objectId){
            q.field("objectId", objectId);
        }
        let qr = await page.search(q);
        let obj;
        if(qr && qr.results && qr.results.length){
            obj = qr.results[0];
        }
        return obj;
    }
    

    let test = typeof module;

    if (test !== "undefined") {
        module.exports = page;
    } else {
        window.page = page;
    }
}());
