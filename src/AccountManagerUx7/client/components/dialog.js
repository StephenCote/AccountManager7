(function(){

    let dialogUp = false;
    let dialogCfg;

    async function chatInto(ref, inst, aCCfg){
        let sessName = page.sessionName();
        let remoteEnt = {
          schema: "chatSettings",
          chat: "Object Chat",
          prompt: "Object Prompt",
          session: sessName,
          sessions: sessName
        };
        let wset = [];
        if(aCCfg){
            let aC = aCCfg.filter(c => c.name == inst.api.chat());
            if(aC.length && aC[0].userCharacter && aC[0].systemCharacter){
                let grp = await page.findObject("auth.group", "data", "~/Tags");
                let q = am7view.viewQuery(am7model.newInstance("data.tag"));
                q.field("groupId", grp.id);
                let q2 = q.field(null, null);
                q2.comparator = "group_or";
                q2.fields = [
                    {name: "name", comparator: "equals", value: aC[0].userCharacter.name},
                    {name: "name", comparator: "equals", value: aC[0].systemCharacter.name}
                ];
        
                let qr = await page.search(q);
                if(qr && qr.results){
                    wset = qr.results;
                }
            }
        }
        if(ref){
            wset.push(ref);
        }
  
        let w = window.open("/", "_blank");
        w.onload = function(){
          w.remoteEntity = remoteEnt;
          w.page.components.dnd.workingSet.push(...wset);
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
                                freeFormEntity: data.entity,
                                freeFormInstance: data.inst
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
        showProgress,
        chatInto
    }
    page.components.dialog = dialog;
}())