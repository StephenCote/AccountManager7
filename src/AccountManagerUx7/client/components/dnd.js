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
            if ((sId && w.objectId == sId) || (!sId && w.model.match(/^(data\.tag|olio\.event|auth\.group|auth\.role)$/gi) && (!w.model.match(/^auth\.group$/gi) || w.type.match(/^(person|account|bucket)$/gi)))) {
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
                let actor = b2.model.match(/^identity\.account|identity\.person|olio\.charPerson|system\.user$/gi);
                if ((b.model == 'data.data' || b.model == 'olio.charPerson') && (b2.model == 'data.data' || b2.model == 'olio.charPerson') && b.model != b2.model) {
                    let p = b;
                    let d = b2;
                    if (p.model == 'data.data') {
                        p = b2;
                        d = b;
                    }

                }
                switch (b.model) {

                    case "olio.llm.promptConfig":
                    case "olio.llm.chatConfig":
                        if ((b2.model == "olio.llm.promptConfig" || b2.model == "olio.llm.chatConfig") && b2.model != b.model) {
                            let cfg = {};
                            cfg[b2.model] = b2;
                            cfg[b.model] = b;
                            page.context().contextObjects["chatConfig"] = cfg;
                            m.route.set("/chat");
                            break;
                        }
                    case "auth.role":
                        if (actor && b.type == b2.model) {
                            aP.push(page.member(b.model, b.objectId, b2.model, b2.objectId, true));
                            filt[b2.objectId] = true;
                        }
                        else {
                            console.warn("Skip actor type: " + b2.model + " for role " + b.type);
                        }
                        break;
                    case "auth.group":
                        if ((actor && b2.model == b.type) || (b.type == "bucket" && b2.model != "auth.group")) {
                            aP.push(page.member(b.model, b.objectId, b2.model, b2.objectId, true));
                            filt[b2.objectId] = true;
                        }
                        else {
                            console.warn("Skip actor type: " + b2.model + " for group " + b.type);
                        }
                        break;
                    case "data.tag":
                        if ((actor || b2.model.match(/^data\.data$/gi)) && b2.model == b.type) {
                            // aP.push(page.tag(b2, b, true));
                            aP.push(am7client.member(b2.model, b2.objectId, b.model, b.objectId, true));
                            filt[b2.objectId] = true;
                        }
                        else {
                            console.warn("Skip object type: " + b2.model + " for tag type " + b.type);
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
                        if (b2.model.match(/^identity\.contact$/gi)) {
                            b.contactInformation.contacts.push(b2);
                            bUp = true;
                        }
                        if (b2.model.match(/^identity\.address$/gi)) {
                            b.contactInformation.addresses.push(b2);
                            bUp = true;
                        }
                        if (bUp) {
                            aP.push(page.patchObject(b));
                        }

                        console.log("Blend " + b.model + " with " + b2.model);
                        break;
                    default:
                        console.warn("Unhandled", b.model);
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
            let modType = am7model.getModel(object.model);
            if (dnd.overTarget.model.match(/^auth\.group$/gi) || am7model.inherits(modType, "common.parent")) {
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
            let dragGroup = dnd.dragTarget.model.match(/^auth\.group$/gi);
            let dragParent = am7model.isParent(dnd.dragTarget.model);

            let dragModType = am7model.getModel(dnd.dragTarget.model);
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
                let overParent = am7model.isParent(dnd.overTarget.model);
                let dropGroup = dnd.overTarget.model.match(/^auth\.[group]$/gi);
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

    page.components.dnd = dnd;
}());
