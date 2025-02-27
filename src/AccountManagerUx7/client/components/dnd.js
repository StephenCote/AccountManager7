(function () {

    const dnd = {
        dragTarget: undefined,
        overTarget: undefined,
        workingSet: []
    };
    dnd.doBlend = async function (sId) {
        let blended = [];
        let blender = [];
        dnd.workingSet.forEach((w) => {
            /// if an id is specified, then that is the item to apply to the rest of the set
            /// if an id isn't specified, then the blender list will be any tag, event, group, or role
            if ((sId && w.objectId == sId) || (!sId && w[am7model.jsonModelKey].match(/^(data\.tag|olio\.event|auth\.group|auth\.role)$/gi) && (!w[am7model.jsonModelKey].match(/^auth\.group$/gi) || w.type.match(/^(person|account|bucket)$/gi)))) {
                blender.push(w);
            }
            else {
                blended.push(w);
            }
        });
        let aP = [];
        let filt = {};
        blender.forEach((b) => {
            blended.forEach((b2) => {
                let actor = b2[am7model.jsonModelKey].match(/^identity\.account|identity\.person|olio\.charPerson|system\.user$/gi);
                if ((b[am7model.jsonModelKey] == 'data.data' || b[am7model.jsonModelKey] == 'olio.charPerson') && (b2[am7model.jsonModelKey] == 'data.data' || b2[am7model.jsonModelKey] == 'olio.charPerson') && b[am7model.jsonModelKey] != b2[am7model.jsonModelKey]) {
                    let p = b;
                    let d = b2;
                    if (p[am7model.jsonModelKey] == 'data.data') {
                        p = b2;
                        d = b;
                    }

                }
                switch (b[am7model.jsonModelKey]) {

                    case "olio.llm.promptConfig":
                    case "olio.llm.chatConfig":
                        if ((b2[am7model.jsonModelKey] == "olio.llm.promptConfig" || b2[am7model.jsonModelKey] == "olio.llm.chatConfig") && b2[am7model.jsonModelKey] != b[am7model.jsonModelKey]) {
                            let cfg = {};
                            cfg[b2[am7model.jsonModelKey]] = b2;
                            cfg[b[am7model.jsonModelKey]] = b;
                            page.context().contextObjects["chatConfig"] = cfg;
                            m.route.set("/chat");
                            break;
                        }
                    case "auth.role":
                        if (actor && b.type == b2[am7model.jsonModelKey]) {
                            aP.push(page.member(b[am7model.jsonModelKey], b.objectId, b2[am7model.jsonModelKey], b2.objectId, true));
                            filt[b2.objectId] = true;
                        }
                        else {
                            console.warn("Skip actor type: " + b2[am7model.jsonModelKey] + " for role " + b.type);
                        }
                        break;
                    case "auth.group":
                        if ((actor && b2[am7model.jsonModelKey] == b.type) || (b.type == "bucket" && b2[am7model.jsonModelKey] != "auth.group")) {
                            aP.push(page.member(b[am7model.jsonModelKey], b.objectId, b2[am7model.jsonModelKey], b2.objectId, true));
                            filt[b2.objectId] = true;
                        }
                        else {
                            console.warn("Skip actor type: " + b2[am7model.jsonModelKey] + " for group " + b.type);
                        }
                        break;
                    case "data.tag":
                        if ((actor || b2[am7model.jsonModelKey].match(/^data\.data$/gi)) && b2[am7model.jsonModelKey] == b.type) {
                            // aP.push(page.tag(b2, b, true));
                            aP.push(page.member(b2[am7model.jsonModelKey], b2.objectId, b[am7model.jsonModelKey], b.objectId, true));
                            filt[b2.objectId] = true;
                        }
                        else {
                            console.warn("Skip object type: " + b2[am7model.jsonModelKey] + " for tag type " + b.type);
                            console.warn(blender);
                            console.warn(b);
                        }
                        break;
                    /// Need to populate these in order to obtain contactInformation
                    case "system.user":
                    case "identity.account":
                    case "olio.charPerson":
                    case "identity.person":
                        let bUp = false;
                        console.log(b, b2);
                        if (b2[am7model.jsonModelKey].match(/^identity\.contact$/gi)) {
                            b.contactInformation.contacts.push(b2);
                            bUp = true;
                        }
                        if (b2[am7model.jsonModelKey].match(/^identity\.address$/gi)) {
                            b.contactInformation.addresses.push(b2);
                            bUp = true;
                        }
                        if (bUp) {
                            aP.push(page.patchObject(b));
                        }

                        console.log("Blend " + b[am7model.jsonModelKey] + " with " + b2[am7model.jsonModelKey]);
                        break;
                    default:
                        console.warn("Unhandled", b[am7model.jsonModelKey]);
                        break;

                }
            });


        });
        await Promise.all(aP);
        dnd.workingSet = dnd.workingSet.filter((o) => !filt[o.objectId]);

        console.log("Blended", blender);

    };
    dnd.doDragDecorate = function (object) {
        let cls;
        if (!object || !object.objectId) return cls;
        if (dnd.dragTarget && dnd.dragTarget.objectId == object.objectId) {
            cls = "hover:bg-orange-200";
        }
        else if (dnd.overTarget && dnd.overTarget.objectId == object.objectId && dnd.dragTarget.objectId != object.objectId) {
            let modType = am7model.getModel(object[am7model.jsonModelKey]);
            if (dnd.overTarget[am7model.jsonModelKey].match(/^auth\.group$/gi) || am7model.inherits(modType, "common.parent")) {
                cls = "hover:bg-green-200 bg-green-200";
            }
        }
        return cls;
    }

    dnd.doDragStart = function (e, obj) {
        //console.log("Drag start", obj.name);
        e.dataTransfer.setData('text', obj.objectId);
        dnd.dragTarget = obj;
        //m.redraw();
    }
    dnd.doDragOver = function (e, obj) {
        if (obj && dnd.dragTarget) {

            if (typeof obj === "string") {
                dnd.overTarget = obj;
            }
            if (obj.objectId != dnd.dragTarget.objectId) {
                dnd.overTarget = obj;
            }
        }
    }
    dnd.doDragEnd = function (e, obj) {
        dnd.dragTarget = undefined;
        dnd.overTarget = undefined;
    }
    dnd.doDrop = async function (e, obj) {
        let ret = false;
        if (obj && dnd.dragTarget) {
            let dragGroup = dnd.dragTarget[am7model.jsonModelKey].match(/^auth\.group$/gi);
            let dragParent = am7model.isParent(dnd.dragTarget[am7model.jsonModelKey]);

            let dragModType = am7model.getModel(dnd.dragTarget[am7model.jsonModelKey]);
            if (typeof obj === "string") {
                if (obj === "set") {
                    let aL = dnd.workingSet.filter((o) => { if (o.objectId == dnd.dragTarget.objectId) return true; });
                    if (aL.length) {
                        console.warn("Already in set");
                    }
                    else {
                        dnd.workingSet.push(dnd.dragTarget);
                        ret = true;
                    }
                }
            }
            else if (dnd.overTarget) {
                let overParent = am7model.isParent(dnd.overTarget[am7model.jsonModelKey]);
                let dropGroup = dnd.overTarget[am7model.jsonModelKey].match(/^auth\.[group]$/gi);
                console.log(dnd.dragTarget, dnd.overTarget, dragParent, overParent);
                if ((dragGroup && dropGroup) || (dragParent && overParent)) {
                    ret = await page.reparentObject(dnd.dragTarget, dnd.overTarget);
                }
                else if (dropGroup && am7model.isGroup(dragModType)) {
                    ret = await page.moveObject(dnd.dragTarget, dnd.overTarget);
                }
                else {
                    console.warn("Don't know what to do: Drop: " + dnd.dragTarget.name + " on " + dnd.overTarget.name);
                }
            }
        }
        return ret;

    }

    let totalUpCount = 0;
    let currentUpCount = 0;
    let maxUpload = 10;
    dnd.uploadFiles = async function(inst, files) {
        let entity = inst?.entity;
        if(!entity){
            return;
        }
        totalUpCount = files.length;
        currentUpCount = 0;
        let aP = [];
        for (var i = 0; i < files.length; i++) {
            var formData = new FormData();
            formData.append("organizationPath", am7client.currentOrganization);
            formData.append("groupPath", entity.groupPath);
            //formData.append("groupId", oGroup.id);
            let fname = files[i].name;
            formData.append("name", files[i].name);
            formData.append("dataFile", files[i]);
            aP.push(new Promise((res, rej)=>{
                var xhr = new XMLHttpRequest();
                xhr.withCredentials = true;
                console.log("Posting: " + g_application_path + '/mediaForm');
                xhr.open('POST', g_application_path + '/mediaForm');
                xhr.onload = function(v){
                    res(fname);
                };
                xhr.upload.onprogress = function(event){
                    ///ctl.updateWaitingCount();
                };
                xhr.send(formData);
            }));
            if(i > 0 && (i % maxUpload) == 0){
                await Promise.all(aP);
                aP = [];
            }

        }
        return new Promise((res2, rej2)=>{
            Promise.all(aP).then((aD)=>{
                if(aD.length == 1){
                    page.findObject("auth.group", "data", entity.groupPath).then((oG)=>{
                        if(!oG){
                            console.warn("Failed to lookup group", oG);
                        }
                        else{
                            page.openObjectByName("data.data", oG.objectId, aD[0]).then((oD)=>{
                                if(!oD){
                                    console.warn("Failed to lookup data");
                                }
                                else{
                                    let uri = "/view/data.data/" + oD.objectId;
                                    m.route.set(uri, {key: oD.objectId});
                                    res2();
                                }
                            });
                        }
                    });
                }
                else{
                    res2();
                }
            });
        });
    }

    function handleDragLeave(evt) {
        evt.preventDefault();
    }
    function handleDragEnter(evt) {
        evt.preventDefault();
    }
    function handleDragOver(evt) {
        evt.preventDefault();
    }
    function handleDrop(inst, evt) {
        evt.preventDefault();
        dnd.uploadFiles(inst, evt.dataTransfer.files);
    }

    dnd.props = function(inst){
        return {
            ondrop: function(e){ handleDrop(inst, e); },
            ondragover: handleDragOver,
            ondragenter: handleDragEnter,
            ondragleave: handleDragLeave
        };
    }

    page.components.dnd = dnd;
}());
