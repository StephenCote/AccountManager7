(function () {
    async function dressUp(object, inst, name){
        if(inst.model.name == "olio.charPerson"){
            await dressCharacter(inst, true);
        }
        else if(inst.model.name == "olio.apparel"){
            await dressApparel(inst.entity, true);
        }
    }
    async function dressDown(object, inst, name){
        if(inst.model.name == "olio.charPerson"){
            await dressCharacter(inst, false);
        }
        else if(inst.model.name == "olio.apparel"){
            await dressApparel(inst.entity, false);
        }
    }

    async function setApparelDescription(app, wears){
        let wdesc = wears.filter((w) => w.inuse).map((w) => describeWearable(w)).join(", ");
        app.description = "Worn apparel includes " + wdesc + ".";
        if(wdesc.length == 0){
            app.description = "No apparel worn.";
        }
        console.log("Setting description to " + app.description);
        await page.patchObject({schema: 'olio.apparel', id: app.id, description: app.description}); 
        m.redraw();
    }

    function describeWearable(wear){

        let qual = wear?.qualities[0];
        let opac = qual.opacity || 0.0;
        let shin = qual.shininess || 0.0;
        let smoo = qual.smoothness || 0.0;
        let col = wear?.color?.name.toLowerCase() || "";        
       
        if(col){
            col = col.replaceAll(/([\^\(\)]*)/g, "");
        }
        let fab = wear?.fabric?.toLowerCase() || "";
        let pat = wear?.pattern?.name.toLowerCase() || "";
        let loc = wear?.location[0]?.toLowerCase() || ""
        let name = wear?.name;
        if(name.indexOf("pierc") > -1) {
            name = loc + " piercing";
            pat = "";
        }
        let opacs = "";
        if(opac > 0.0 && opac <= 0.25) {
            opacs = " see-through";
        }
        let shins = "";
        if(shin >= 0.7) {
            shins = " shiny";
        }
    
        return (shins + opacs + " " + col + " " + pat + " " + fab + " " + name).replaceAll("  ", " ").trim();
    
    }

    async function dressCharacter(inst, dressUp){
        let sto = await page.searchFirst("olio.store", undefined, undefined, inst.api.store().objectId);
        let appl = sto.apparel;
        if(!appl || !appl.length){
            page.toast("error", "No apparel found in store " + sto.name);
            return;
        }
        let bdress = await dressApparel(appl[0], dressUp);
        if(bdress){
            await am7model.forms.commands.narrate(undefined, inst);
        }
    }
    async function dressApparel(vapp, dressUp){
        am7client.clearCache("olio.apparel");
        let aq = am7view.viewQuery("olio.apparel");
        aq.field("objectId", vapp.objectId);
        let aqr = await page.search(aq);
        let app;
        if(aqr && aqr.results && aqr.results.length){
            app = aqr.results[0];
        }
        else{
            page.toast("error", "No apparel found for " + vapp.objectId);
            return;
        }

        if(!app){
            page.toast("error", "No apparel found for " + vapp.objectId);
            return;
        }

        let wear = app.wearables;
        if(!wear || !wear.length){ 
            page.toast("error", "No wearables found in apparel " + app.name);
        }

        let q = am7view.viewQuery("olio.wearable");
        q.range(0, 20);
        let oids = wear.map((a) => { return a.objectId; }).join(",");
        q.field("groupId", wear[0].groupId);
        let fld = q.field("objectId", oids);
        fld.comparator = "in";

        let qr = await page.search(q);
        if(!qr || !qr.results || !qr.results.length){
            page.toast("error", "No wearables found in apparel " + app.name);
            return;
        }
        let wears = qr.results;

        
        let minLevel = 3;
        let availMin = am7model.enums.wearLevelEnumType.findIndex(we => we == "UNKNOWN");
        let maxLevel = minLevel;
        let lvls = [...new Set(wears.sort((a, b) => {
            let aL = am7model.enums.wearLevelEnumType.findIndex(we => we == a.level.toUpperCase());
            let bL = am7model.enums.wearLevelEnumType.findIndex(we => we == b.level.toUpperCase());
            return aL < bL ? -1 : aL > bL ? 1 : 0;
        }).map((w) => w.level.toUpperCase()))];
        
        wears.forEach((w) => {
            if(w.level){
                let lvl = am7model.enums.wearLevelEnumType.findIndex(we => we == w.level.toUpperCase());
                if(w.inuse){
                    maxLevel = Math.max(maxLevel, lvl);
                }
                
                else{
                    availMin = Math.min(availMin, lvl);
                }
            }
        });

        let ulevel = (dressUp ? availMin : maxLevel);
        let maxLevelName = am7model.enums.wearLevelEnumType[ulevel];
        let maxLevelIdx = lvls.findIndex((l) => l == maxLevelName);

        if(!lvls.includes(maxLevelName) || maxLevelIdx == -1){
            page.toast("info", "No wearables found in apparel " + app.name + " for level " + maxLevelName);
            return;
        }

        console.log("Max level is " + maxLevelName + " at index " + maxLevelIdx + " of " + lvls.length);
        if(dressUp && maxLevelIdx >= lvls.length){
            page.toast("info", "Already dressed up to max level");
            return;
        }
        else if(!dressUp && maxLevelIdx < 0){
            page.toast("info", "Already dressed down to min level");
            return;
        }

        let newLevel = am7model.enums.wearLevelEnumType.findIndex(we => we == lvls[maxLevelIdx + (dressUp ? 0 : -1)]);
        let patch = [];
        wears.forEach((w) => {
            if(w.level){
                let lvl = am7model.enums.wearLevelEnumType.findIndex(we => we == w.level.toUpperCase());
                if(lvl > newLevel && w.inuse){
                    w.inuse = false;
                    patch.push({schema: 'olio.wearable', id: w.id, inuse: false});
                }
                else if(lvl <= newLevel && !w.inuse){
                    w.inuse = true;
                    patch.push({schema: 'olio.wearable', id: w.id, inuse: true});
                }
            }
        });

        if(patch.length || !app.description){
            await setApparelDescription(app, wears);
            vapp.description = app.description;
        }

        if(!patch.length){
            page.toast("info", "No changes to make to " + app.name + " for level " + newLevel);
            return;
        }
        
        console.log("Patching " + patch.length + " items to level " + newLevel);
        let aP = [];
        patch.forEach((p) => {
            aP.push(page.patchObject(p));
        });
        am7client.clearCache("olio.wearable");
        am7client.clearCache("olio.apparel");
        am7client.clearCache("olio.store");
        am7client.clearCache("olio.charPerson");
        await Promise.all(aP);
        return true;
    }

    async function setNarDescription(inst, cinst){
        am7client.clearCache("olio.narrative");
        let nar = await page.searchFirst("olio.narrative", undefined, undefined, inst.api.narrative().objectId);
        let pro = (inst.api.gender() == "male" ? "He" : "She");
        cinst.api.description(inst.api.firstName() + " is a " + nar.physicalDescription + " " + pro + " is " + nar.outfitDescription + ".");
    }

    let am7olio = {
        dressUp,
        dressDown,
        dressCharacter,
        dressApparel,
        setNarDescription
    };

    if (typeof module != "undefined") {
        module.am7olio = am7olio;
    } else {
        window.am7olio = am7olio;
    }
}());