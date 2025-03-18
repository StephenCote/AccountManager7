(function(){

    let dialogUp = false;
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
        showProgress
    }
    page.components.dialog = dialog;
}())