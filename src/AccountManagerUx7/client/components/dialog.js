(function(){

    /// TODO: Persist chatRequest.
    /// NOTE: The underlying AI request should also be persisted to make it easier to access and edit without having to deserialize the data.data byteStore
    ///

    let dialogUp = false;
    let dialogCfg;

    async function chatInto(ref, inst, aCCfg){
        let sessName = page.sessionName();
        let remoteEnt = {
          schema: "olio.llm.chatRequest",
          chat: "Object Chat",
          prompt: "Object Prompt",
          session: sessName,
          sessions: sessName
        };
        let wset = [];
        /*
        /// Since the summary tag is set to the summary notes and not the reference, adding the tag will limit the citations to only the summary notes
        let tag = await page.getTag("Summary");
        if(tag){
            wset.push(tag);
        }
        */
        let aCPs = [];
        if(aCCfg){
            let aC = aCCfg.filter(c => c.name == inst.api.chatConfig()?.name);
            if(aC.length && aC[0].userCharacter && aC[0].systemCharacter){
                aCPs.push(aC[0].userCharacter.name);
                aCPs.push(aC[0].systemCharacter.name);
            }
        }
        else if(ref && ref[am7model.jsonModelKey] == "olio.charPerson" || ref[am7model.jsonModelKey] == "identity.person"){
            aCPs.push(ref.name);
        }

        if(aCPs.length){
            let grp = await page.findObject("auth.group", "data", "~/Tags");
            let q = am7view.viewQuery(am7model.newInstance("data.tag"));
            q.field("groupId", grp.id);
            let q2 = q.field(null, null);
            q2.comparator = "group_or";
            q2.fields = aCPs.map((a) => {
                return {
                    name: "name",
                    comparator: "equals",
                    value: a
                }
            });
            /*
            [
                {name: "name", comparator: "equals", value: aC[0].userCharacter.name},
                {name: "name", comparator: "equals", value: aC[0].systemCharacter.name}
            ];
            */
    
            let qr = await page.search(q);
            if(qr && qr.results){
                wset = qr.results;
            }
        }
        
        if(ref){
            wset.push(ref);
        }
  
        let w = window.open("/", "_blank");
        w.onload = function(){
          w.remoteEntity = remoteEnt;
          w.page.components.dnd.workingSet.push(...wset);
          if(wset.length){
            w.page.components.topMenu.activeShuffle(wset[0]);
          }
          w.m.route.set("/chat");
        }
  
  
        
      }

    function endDialog(){
        dialogUp = false;
        m.redraw();
    }
    function setDialog(cfg){
        dialogCfg = cfg;
        dialogUp = true;
        m.redraw();
    }

    function showProgress(object, inst) {
        if(!object){
            return;
        }
        object.label = object.label || "Progress";
        let cfg = {
            label: object.label,
            entityType: "progress",
            size: 50,
            data: {entity:object, inst},
            cancel: async function (data) {
                endDialog();
            }
        };
        setDialog(cfg);
    }

    async function loadPromptList(){
        let dir = await page.findObject("auth.group", "DATA", "~/Chat");
        return await am7client.list("olio.llm.promptConfig", dir.objectId, null, 0, 0);
    }

    async function loadChatList() {
        let dir = await page.findObject("auth.group", "DATA", "~/Chat");
        return await am7client.list("olio.llm.chatConfig", dir.objectId, null, 0, 0);
      }

    function updateSessionName(inst, acfg, pcfg){
        let chat = inst.api.chatConfig();
        let prompt = inst.api.promptConfig();
        if(!chat || !prompt){
            console.warn("Invalid chat or prompt selection: " + chat + " / " + prompt);
            return;
        }
        let ycfg = pcfg.filter(a => a.name.toLowerCase() == prompt.name.toLowerCase());
        let vcfg = acfg.filter(a => a.name.toLowerCase() == chat.name.toLowerCase());
        if(!ycfg.length || !vcfg.length){
            console.warn("Invalid chat or prompt list: " + chat + " / " + prompt);
            return;
        }
        let occfg = vcfg[0];
        let opcfg = ycfg[0];

        let name;
        if(inst.api.name) name = inst.api.name();
        //if(!name || !name.length){
            if(page.components.dnd.workingSet.length){
                name = page.components.dnd.workingSet[0].name;
            }
            else if(occfg.systemCharacter && occfg.userCharacter){
                let sysName = occfg.systemCharacter.firstName || occfg.systemCharacter.name.substring(0,occfg.systemCharacter.name.indexOf(" "));
                let usrName = occfg.userCharacter.firstName || occfg.userCharacter.name.substring(0,occfg.userCharacter.name.indexOf(" "));
                name = sysName + " and " + usrName + " (" + occfg.rating.toUpperCase() + ")";
            }
            else {
                name = page.sessionName();
            }
        //}
        inst.api.name(name);

    }
    
    async function chatSettings(fHandler){
        let entity = am7model.newPrimitive("olio.llm.chatRequest");
        let inst = am7model.prepareInstance(entity, am7model.forms.newChatRequest);

        let acfg = await loadChatList();
        if(acfg && acfg.length && !entity.chatConfig) entity.chatConfig = acfg[0];
        //am7model.getModelField("chatSettings", "chat").limit  = acfg.map((c) => { return c.name; });
        //am7model.forms.newChatRequest.fields.chatConfig.field.limit = acfg.map((c) => { return c.name; });
        let pcfg = await loadPromptList();
        if(pcfg && pcfg.length && !entity.prompt) entity.promptConfig = pcfg[0];
        //am7model.getModelField("chatSettings", "prompt").limit  = pcfg.map((c) => { return c.name; });
       // am7model.forms.newChatSettings.fields.promptConfig.field.limit = pcfg.map((c) => { return c.name; });

        inst.action("chatConfig", function(){updateSessionName(inst, acfg, pcfg);});
        inst.action("promptConfig", function(){updateSessionName(inst, acfg, pcfg);});
        updateSessionName(inst, acfg, pcfg);

        let cfg = {
            label: "Chat Settings",
            entityType: "olio.llm.chatRequest",
            size: 50,
            data: {entity, inst},
            confirm: async function (data) {

                if(fHandler){
                    fHandler(entity);
                }

                endDialog();
            },
            cancel: async function (data) {
                endDialog();
            }
        };
        setDialog(cfg);
    }

    async function summarize(object, inst){
        let entity = am7model.newPrimitive("summarizeSettings");
        let acfg = await loadChatList();
        if(acfg && acfg.length) entity.chat = acfg[0].name;
        am7model.getModelField("summarizeSettings", "chat").limit  = acfg.map((c) => { return c.name; });

        let pcfg = await loadPromptList();
        if(pcfg && pcfg.length) entity.prompt = pcfg[0].name;
        am7model.getModelField("summarizeSettings", "prompt").limit  = pcfg.map((c) => { return c.name; });


        let cfg = {
            label: "Summarize Options",
            entityType: "summarizeSettings",
            size: 50,
            data: {entity},
            confirm: async function (data) {
                let ycfg = pcfg.filter(a => a.name.toLowerCase() == entity.prompt.toLowerCase());
                if(!ycfg.length){
                    page.toast("error", "Invalid prompt selection: " + entity.prompt);
                    return;
                }

                let vcfg = acfg.filter(a => a.name.toLowerCase() == entity.chat.toLowerCase());
                if(!vcfg.length){
                    page.toast("error", "Invalid chat selection: " + entity.chat);
                    return;
                }
                page.toast("info", "Summarizing ...", -1);
                let creq = am7model.newPrimitive("olio.llm.chatRequest");
                creq.chatConfig = {name:vcfg[0].name,id:vcfg[0].id};
                creq.promptConfig = {name: ycfg[0].name, id: ycfg[0].id};
                creq.data = [
                    JSON.stringify({schema:inst.model.name,objectId:inst.api.objectId()})
                ];
                let x = await m.request({ method: 'POST', url: am7client.base() + "/vector/summarize/" + entity.chunkType.toUpperCase() + "/" + entity.chunk, body: creq, withCredentials: true });
                page.clearToast();
                if (x && x != null) {
                    page.toast("success", "Summarization complete");
                }
                else{
                    page.toast("error", "Summarization failed");
                }
                endDialog();
            },
            cancel: async function (data) {
                endDialog();
            }
        };
        setDialog(cfg);

    }

    async function vectorize(object, inst) {
        let entity = am7model.newPrimitive("vectorOptions");
        let cfg = {
            label: "Vector Options",
            entityType: "vectorOptions",
            size: 50,
            data: {entity},
            confirm: async function (data) {
                page.toast("info", "Vectorizing ...");
                let x = await m.request({ method: 'GET', url: am7client.base() + "/vector/vectorize/" + inst.model.name + "/" + inst.api.objectId() + "/" + entity.chunkType.toUpperCase() + "/" + entity.chunk, withCredentials: true });
                if (x && x != null) {
                    page.toast("success", "Vectorization complete");
                }
                else{
                    page.toast("error", "Vectorization failed");
                }
                endDialog();
            },
            cancel: async function (data) {
                endDialog();
            }
        };
        setDialog(cfg);
    }

    async function reimage(object, inst) {

        //let entity = am7model.newPrimitive("olio.sd.config");
        let entity = await m.request({ method: 'GET', url: am7client.base() + "/olio/randomImageConfig", withCredentials: true });
        let cinst = am7model.prepareInstance(entity, am7model.forms.sdConfig);
        await am7olio.setNarDescription(inst, cinst);
        let cfg = {
            label: "Reimage " + inst.api.name(),
            entityType: "olio.sd.config",
            size: 75,
            data: {entity, inst: cinst},
            confirm: async function (data) {
                console.log(data);
                page.toast("info", "Reimaging ...", -1);
                let x = await m.request({ method: 'POST', url: am7client.base() + "/olio/" + inst.model.name + "/" + inst.api.objectId() + "/reimage", body:cinst.entity, withCredentials: true });
                page.clearToast();
                let pop = false;
                if (x && x != null) {
                    page.toast("success", "Reimage complete");
                    inst.entity.profile.portrait = x;
                    let od = {id: inst.entity.profile.id, portrait: {id: x.id}};
                    od[am7model.jsonModelKey] = "identity.profile";
                    page.patchObject(od);
                    pop = true;
        
                }
                else{
                    page.toast("error", "Reimage failed");
                }
                endDialog();
                if(pop){
                    page.imageView(x);
                }
                
            },
            cancel: async function (data) {
                endDialog();
            }
        };
        
        am7model.forms.sdConfig.fields.dressDown.field.command = async function(){
            await am7olio.dressCharacter(inst, false);
            await am7olio.setNarDescription(inst, cinst);
        };
        
        am7model.forms.sdConfig.fields.dressUp.field.command = async function(){
            await am7olio.dressCharacter(inst, true);
            await am7olio.setNarDescription(inst, cinst);
        };

        am7model.forms.sdConfig.fields.randomConfig.field.command = async function(){
            let ncfg = await m.request({ method: 'GET', url: am7client.base() + "/olio/randomImageConfig", withCredentials: true });
            // Do style first since that drives the display
            cinst.api.style(ncfg.style);
            for(let k in ncfg){
                if(k != "id" && k != "objectId"){
                    if(cinst.api[k]) cinst.api[k](ncfg[k]);
                }
            }

            m.redraw();
        };
        setDialog(cfg);
    }

    function loadDialog(){
        if(!dialogCfg || !dialogUp) return "";
        return createDialog(dialogCfg.size, dialogCfg.entityType, dialogCfg.data, dialogCfg.label, dialogCfg.confirm, dialogCfg.cancel);
    }

    function createDialog(size, entityType, data, title, fConf, fCanc){
        let sizeCls = "page-dialog-" + (size || "50");

        if(!data.view){
            data.view = page.views.object().view;
        }
        let mconf = "";
        if(fConf){
            mconf = m("button", {class: "page-dialog-button", onclick: async function(){ if(data.view) data.view.sync(); await fConf(data);}}, [
                m("span", {class: "material-symbols-outlined material-symbols-outlined-white md-18 mr-2"}, "check"),
                "Ok"
            ])
        }
        return [
            m("div", {class: "screen-glass screen-glass-open"}, [
                m("div", {class: "screen-glass-gray"})
            ]),
            m("div", {class: "page-dialog-container"}, [
                m("div", {class: "page-dialog " + sizeCls}, [
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
                                freeFormEntity: data.entity,
                                freeFormInstance: data.inst
                            }),
                            m("div",{class: "result-nav-outer"},[
                                m("div",{class: "result-nav-inner"},[
                                    m("nav",{class: "result-nav"},[
                                       mconf,
                                        m("button", {class: "page-dialog-button", onclick: async function(){ await fCanc(data);}},[
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
    
    function confirm(sMessage, fConfirm, fCancel){
        dialogCfg = {
            label: "Confirmation",
            entityType: "confirmation",
            size: 50,
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

    function cancelDialog(obj){
        dialogUp = false;
        m.redraw();
    }

    let dialog = {
        endDialog,
        setDialog,
        loadDialog,
        confirm,
        cancelDialog,
        vectorize,
        summarize,
        reimage,
        chatSettings,
        showProgress,
        chatInto
    }
    page.components.dialog = dialog;
}())