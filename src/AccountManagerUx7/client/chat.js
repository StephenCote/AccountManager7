(function () {

    async function deleteChat(s, force, fHandler) {
        async function chatHandler() {
            if (s.session) {
            let q = am7client.newQuery(s.sessionType);
            q.field("id", s.session.id);
            try {
                let qr = await page.search(q);
                if (qr && qr.results && qr.results.length > 0) {
                page.toast("info", "Deleting session data - " + qr.results[0].objectId);
                await page.deleteObject(s.sessionType, qr.results[0].objectId);
                }
                else {
                page.toast("error", "Failed to find session data to delete", 5000);
                console.error("Failed to delete session data", qr);
                }
            }
            catch (e) {
                page.toast("error", "Error deleting session data", 5000);
                console.error("Error deleting session data", e);
            }

            }
            else {
            console.warn("No session data found");
            }
            // console.log("Deleting request", s.objectId);
            await page.deleteObject(s[am7model.jsonModelKey], s.objectId);
            if (fHandler) fHandler(s);
        }
        if(!force){
            page.components.dialog.confirm("Delete chat " + s + "?", chatHandler);
        }
        else{
            chatHandler();
        }
    }


    async function getChatRequest(requestName, chatCfg, promptCfg) {
        let grp = await page.findObject("auth.group", "data", "~/ChatRequests");
        let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatRequest", am7model.forms.chatRequest));
        q.field("groupId", grp.id);
        q.field("name", requestName);
        q.cache(false);
        q.entity.request.push("session", "sessionType", "chatConfig", "promptConfig", "objectId");
        let req;
        let qr = await page.search(q);
        if (!qr || !qr.results || qr.results.length == 0) {
            let chatReq = {
                schema: "olio.llm.chatRequest",
                name: requestName,
                chatConfig: { objectId: chatCfg.objectId },
                promptConfig: { objectId: promptCfg.objectId },
                uid: page.uid()
            };

            req = await m.request({ method: 'POST', url: g_application_path + "/rest/chat/new", withCredentials: true, body: chatReq });
        }
        else if (qr && qr.results && qr.results.length > 0) {
            req = qr.results[0];
        }
        console.log(req);

        return req;

    }

    async function makeChat(sName, sModel, sServerUrl, sServiceType){
        let grp = await page.findObject("auth.group", "data", "~/Chat");
        let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatConfig"));
        q.field("groupId", grp.id);
        q.field("name", sName);
        q.cache(false);
        let cfg;
        let qr = await page.search(q);
        if(!qr || !qr.results || qr.results.length == 0){
            let icfg = am7model.newInstance("olio.llm.chatConfig");
            icfg.api.groupId(grp.id);
            icfg.api.groupPath(grp.path);
            icfg.api.name(sName);
            icfg.api.model(sModel);
            icfg.api.messageTrim(6);
            icfg.api.serverUrl(sServerUrl);
            icfg.api.serviceType(sServiceType);
            await page.createObject(icfg.entity);
            qr = await page.search(q);
        }
        if(qr?.results.length > 0){
            cfg = qr.results[0];
        }

        if(!cfg){
            page.toast("Error", "Could not create chat config");
        }

        return cfg;
    }

    async function makePrompt(sName, aSystem){
        let grp = await page.findObject("auth.group", "data", "~/Chat");
        let q = am7view.viewQuery(am7model.newInstance("olio.llm.promptConfig"));
        q.field("groupId", grp.id);
        q.field("name", sName);
        q.cache(false);
        let cfg;
        let qr = await page.search(q);
        if(!qr || !qr.results || qr.results.length == 0){
            let icfg = am7model.newInstance("olio.llm.promptConfig");
            icfg.api.groupId(grp.id);
            icfg.api.groupPath(grp.path);
            icfg.api.name(sName);
            icfg.entity.system = aSystem;
            await page.createObject(icfg.entity);
            qr = await page.search(q);
        }
        if(qr?.results.length > 0){
            cfg = qr.results[0];
        }

        if(!cfg){
            page.toast("Error", "Could not create prompt config");
        }

        return cfg;
    }

    async function chat(chat, msg) {
        if (!chat) {
            page.toast("error", "Chat request is not defined.");
            return;
        }
        if (!chat.chatConfig || !chat.promptConfig) {
            page.toast("error", "Chat request is missing the prompt or chat config.");
            return;
        }
        chat.message = msg;
        chat.uid = page.uid();
        //console.log(chat);
        return await m.request({ method: 'POST', url: g_application_path + "/rest/chat/text", withCredentials: true, body: chat });
    }

    let am7chat = {
        makePrompt,
        makeChat,
        getChatRequest,
        deleteChat,
        chat
    };

    if (typeof module != "undefined") {
        module.am7chat = am7chat;
    } else {
        window.am7chat = am7chat;
    }
}());