(function () {

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

    let am7chat = {
        makePrompt,
        makeChat
    };

    if (typeof module != "undefined") {
        module.am7chat = am7chat;
    } else {
        window.am7chat = am7chat;
    }
}());