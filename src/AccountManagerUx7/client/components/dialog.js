(function(){

    /// TODO: Persist chatRequest.
    /// NOTE: The underlying AI request should also be persisted to make it easier to access and edit without having to deserialize the data.data byteStore
    ///

    let dialogUp = false;
    let dialogCfg;

    async function chatInto(ref, inst, aCCfg){
        let sessName = page.sessionName();

        let pa = (await loadPromptList()).filter(c => c.name.match(/^Object/gi));
        let ca = (await loadChatList()).filter(c => c.name.match(/^Object/gi));
        let cname = "Analyze " + (ref && ref[am7model.jsonModelKey] ? ref[am7model.jsonModelKey].toUpperCase() + " " : "") + (ref?.name ? ref.name : inst.api.name());
        let remoteEnt = {
          schema: "olio.llm.chatRequest",          
          chatConfig: ca.length ? ca[0] : undefined,
          promptConfig: pa.length ? pa[0] : undefined,
          name:cname
          //sessions: sessName
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
        
        if(inst && aCCfg){
            let aC = aCCfg.filter(c => c.name == inst.api.chatConfig()?.name);
            if(aC.length && aC[0].userCharacter && aC[0].systemCharacter){
                aCPs.push(aC[0].userCharacter.name);
                aCPs.push(aC[0].systemCharacter.name);
            }
        }
        else if(ref && ref[am7model.jsonModelKey] == "olio.charPerson" || ref[am7model.jsonModelKey] == "identity.person"){
            aCPs.push(ref.name);
        }
        
        if(inst && inst.api.session && inst.api.session() != null && inst.api.session()[am7model.jsonModelKey]){
            let sq = am7view.viewQuery(inst.api.session()[am7model.jsonModelKey]);
            sq.entity.request = ["id", "objectId", "groupId", "groupPath", "organizationId", "organizationPath"];
            sq.field("id", inst.api.session().id);
            let sess = await page.search(sq);
            if(sess && sess.results && sess.results.length){
                let x = await m.request({ method: 'GET', url: am7client.base() + "/vector/vectorize/" + inst.api.session()[am7model.jsonModelKey] + "/" + sess.results[0].objectId + "/WORD/500", withCredentials: true });
                if(x){
                    wset.push(sess.results[0]);
                }
                else{
                    page.toast("error", "Session vectorization failed: " + inst.api.session().id);
                }
            }
            else{
                page.toast("error", "Session not found: " + inst.api.session().id);
            }
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
                wset.push(...qr.results);
            }
        }
        
        if(ref){
            wset.push(ref);
        }
  
        let w = window.open("/", "_blank");
        w.remoteEntity = remoteEnt;
        w.onload = function(){
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

    function updateSessionName(inst, acfg, pcfg, bRename){
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

        inst.api.setting(occfg.setting)

        let name;
        if(inst.api.name) name = inst.api.name();

        if(bRename || !name || !name.length){
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
        }
        inst.api.name(name);

    }
    
    async function chatSettings(cinst, fHandler){
        let entity = cinst ? cinst.entity : am7model.newPrimitive("olio.llm.chatRequest");
        let inst = am7model.prepareInstance(entity, am7model.forms.newChatRequest);
        let acfg = await loadChatList();
        if(acfg && acfg.length && !entity.chatConfig) entity.chatConfig = acfg[0];
        let pcfg = await loadPromptList();
        if(pcfg && pcfg.length && !entity.promptConfig) entity.promptConfig = pcfg[0];

        inst.action("chatConfig", function(){updateSessionName(inst, acfg, pcfg, true);});
        inst.action("promptConfig", function(){updateSessionName(inst, acfg, pcfg, true);});
        updateSessionName(inst, acfg, pcfg);

        let cfg = {
            label: "Chat Settings",
            entityType: "olio.llm.chatRequest",
            size: 75,
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

    let lastReimage;

    async function loadSDConfig(name){
        let grp = await page.makePath("auth.group", "DATA", "~/Data/.preferences");
        if(!grp) return null;
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("groupId", grp.id);
            q.field("name", name);
            let qr = await page.search(q);
            if(qr && qr.results && qr.results.length){
                let obj = qr.results[0];
                if(obj.dataBytesStore && obj.dataBytesStore.length){
                    return JSON.parse(uwm.base64Decode(obj.dataBytesStore));
                }
            }
        } catch(e){
            console.warn("Failed to load SD config: " + name, e);
        }
        return null;
    }

    async function saveSDConfig(name, config){
        let grp = await page.makePath("auth.group", "DATA", "~/Data/.preferences");
        if(!grp) return false;

        // Create a clean copy without transient fields
        let saveConfig = {};
        let excludeFields = ["narration", "id", "objectId", "ownerId"];
        for(let k in config){
            if(!excludeFields.includes(k)){
                saveConfig[k] = config[k];
            }
        }

        let obj;
        try {
            obj = await page.searchByName("data.data", grp.objectId, name);
        } catch(e){
            // Object doesn't exist yet
        }

        if(!obj){
            obj = am7model.newPrimitive("data.data");
            obj.name = name;
            obj.mimeType = "application/json";
            obj.groupId = grp.id;
            obj.groupPath = grp.path;
            obj.dataBytesStore = uwm.base64Encode(JSON.stringify(saveConfig));
            await page.createObject(obj);
        } else {
            let patch = {id: obj.id, compressionType: "none", dataBytesStore: uwm.base64Encode(JSON.stringify(saveConfig))};
            patch[am7model.jsonModelKey] = "data.data";
            //console.log(patch, saveConfig);
            await page.patchObject(patch);
        }
        return true;
    }

    function applySDConfig(cinst, config){
        if(!config) return;
        let excludeFields = ["narration", "id", "objectId", "groupId", "organizationId", "ownerId", "groupPath", "organizationPath", "seed"];
        for(let k in config){
            if(!excludeFields.includes(k) && cinst.api[k]){
                cinst.api[k](config[k]);
            }
        }
    }

    // Social sharing context mapping to wear levels
    // Maps wear level names (from wearLevelEnumType) to social sharing tags
    // Levels: NONE, INTERNAL, UNDER, ON, BASE, ACCENT, SUIT, GARNITURE, ACCESSORY, OVER, OUTER, FULL_BODY, ENCLOSURE, UNKNOWN
    // UNDER/ON = tattoos/implants (under skin, on skin), BASE = skin contact clothing
    const socialSharingMap = {
        "NONE": "nude",      // No clothing - nude
        "INTERNAL": "nude",  // Internal items (implants)
        "UNDER": "nude",     // Under skin (implants)
        "ON": "nude",        // On skin (tattoos)
        "BASE": "intimate",           // Skin contact clothing (underwear)
        "ACCENT": "public",           // Everything above BASE is public
        "SUIT": "public",
        "GARNITURE": "public",
        "ACCESSORY": "public",
        "OVER": "public",
        "OUTER": "public",
        "FULL_BODY": "public",
        "ENCLOSURE": "public",
        "UNKNOWN": null               // No automatic tag for unknown
    };

    // All social sharing tags that can be toggled in the gallery
    // These are the subjective tags users can manually apply
    const socialSharingTags = [
        "inappropriate",
        "intimate",
        "sexy",
        "private",
        "casual",
        "public",
        "professional"
    ];

    // Get or create a social sharing tag
    async function getOrCreateSharingTag(tagName, objectType){
        // First try to get existing tag
        let tag = await page.getTag(tagName, objectType);
        if(tag){
            return tag;
        }

        // Create the tag if it doesn't exist
        let grp = await page.findObject("auth.group", "data", "~/Tags");
        if(!grp){
            console.warn("Tags group not found");
            return null;
        }

        let newTag = am7model.newPrimitive("data.tag");
        newTag.name = tagName;
        newTag.type = objectType;
        newTag.groupId = grp.id;
        newTag.groupPath = grp.path;
        newTag.description = "Social sharing context: " + tagName;

        tag = await page.createObject(newTag);
        return tag;
    }

    // Apply social sharing tag to an image based on wear level
    async function applySharingTag(image, wearLevelName){
        let sharingContext = socialSharingMap[wearLevelName];
        if(!sharingContext){
            // No automatic tag for this level (e.g., UNKNOWN)
            return;
        }

        let tag = await getOrCreateSharingTag(sharingContext, "data.data");
        if(!tag){
            console.warn("Failed to get/create sharing tag: " + sharingContext);
            return;
        }

        // Make the image a member of the tag
        await page.member("data.tag", tag.objectId, "data.data", image.objectId, true);
    }

    // Toggle a sharing tag on an image
    async function toggleSharingTag(image, tagName, enable){
        let tag = await getOrCreateSharingTag(tagName, "data.data");
        if(!tag){
            console.warn("Failed to get/create sharing tag: " + tagName);
            return false;
        }

        // Add or remove membership
        await page.member("data.tag", tag.objectId, "data.data", image.objectId, enable);
        return true;
    }

    // Expose for use in gallery
    if(typeof window !== "undefined"){
        window.am7sharingTags = {
            tags: socialSharingTags,
            toggle: toggleSharingTag,
            getOrCreate: getOrCreateSharingTag
        };
    }

    async function getCurrentWearLevel(inst){
        // Get character's store and apparel
        let sto = await page.searchFirst("olio.store", undefined, undefined, inst.api.store().objectId);
        if(!sto || !sto.apparel || !sto.apparel.length){
            return null;
        }

        // Load the apparel and wearables
        am7client.clearCache("olio.apparel");
        let aq = am7view.viewQuery("olio.apparel");
        aq.field("objectId", sto.apparel[0].objectId);
        let aqr = await page.search(aq);
        let app;
        if(aqr && aqr.results && aqr.results.length){
            app = aqr.results[0];
        }
        if(!app || !app.wearables || !app.wearables.length){
            return null;
        }

        // Load full wearable details
        let q = am7view.viewQuery("olio.wearable");
        q.range(0, 20);
        let oids = app.wearables.map((a) => a.objectId).join(",");
        q.field("groupId", app.wearables[0].groupId);
        let fld = q.field("objectId", oids);
        fld.comparator = "in";
        let qr = await page.search(q);
        if(!qr || !qr.results || !qr.results.length){
            return null;
        }
        let wears = qr.results;

        // Find the current max level that is in use
        let maxLevel = -1;
        let maxLevelName = "NUDE";
        wears.forEach((w) => {
            if(w.level && w.inuse){
                let lvl = am7model.enums.wearLevelEnumType.findIndex(we => we == w.level.toUpperCase());
                if(lvl > maxLevel){
                    maxLevel = lvl;
                    maxLevelName = w.level.toUpperCase();
                }
            }
        });

        // Get level index (position in enum)
        let levelIndex = maxLevel >= 0 ? maxLevel : 0;
        return {level: maxLevelName, index: levelIndex};
    }

    async function reimage(object, inst) {

        //let entity = am7model.newPrimitive("olio.sd.config");
        let entity = await m.request({ method: 'GET', url: am7client.base() + "/olio/randomImageConfig", withCredentials: true });
        let cinst = (lastReimage || am7model.prepareInstance(entity, am7model.forms.sdConfig));

        function tempApplyDefaults(){
            // TODO - Add to form
            cinst.api.steps(40);
            cinst.api.refinerSteps(40);
            cinst.api.cfg(5);
            cinst.api.refinerCfg(5);
            cinst.api.model("sdXL_v10VAEFix.safetensors");
            cinst.api.refinerModel("juggernautXL_ragnarokBy.safetensors");
        }

        tempApplyDefaults();

        // Try to load character-specific config
        let charConfigName = inst.api.name() + "-SD.json";
        let charConfig = await loadSDConfig(charConfigName);
        if(charConfig){
            applySDConfig(cinst, charConfig);
        }

        lastReimage = cinst;
        await am7olio.setNarDescription(inst, cinst);

        // Apply preferred seed from character attributes (overrides saved config)
        let cseed = (inst.entity?.attributes || []).filter(a => a.name == "preferredSeed");
        if(cseed.length){
            cinst.api.seed(cseed[0].value);
        }
        let cfg = {
            label: "Reimage " + inst.api.name(),
            entityType: "olio.sd.config",
            size: 75,
            data: {entity:cinst.entity, inst: cinst},
            confirm: async function (data) {
                //console.log(data);
                let count = cinst.api.imageCount ? (parseInt(cinst.api.imageCount()) || 1) : 1;
                if(count < 1) count = 1;

                let baseSeed = cinst.api.seed();
                let images = [];
                let wearLevel = await getCurrentWearLevel(inst);

                for(let i = 0; i < count; i++){
                    page.toast("info", "Creating image " + (i + 1) + " of " + count + "...", -1);

                    // Increment seed for subsequent images
                    let useSeed = (i === 0) ? baseSeed : (parseInt(baseSeed) + i);
                    let imgEntity = Object.assign({}, cinst.entity);
                    imgEntity.seed = useSeed;

                    let x = await m.request({ method: 'POST', url: am7client.base() + "/olio/" + inst.model.name + "/" + inst.api.objectId() + "/reimage", body: imgEntity, withCredentials: true });

                    if(!x){
                        page.toast("error", "Failed to create image " + (i + 1));
                        break;
                    }

                    // Tag image with wear level and apply sharing tag
                    if(wearLevel){
                        await am7client.patchAttribute(x, "wearLevel", wearLevel.level + "/" + wearLevel.index);
                        await applySharingTag(x, wearLevel.level);
                    }

                    // Apply image tags (caption/description)
                    await page.applyImageTags(x.objectId);

                    images.push(x);

                    // For first image, extract seed and update portrait
                    if(i === 0){
                        inst.entity.profile.portrait = x;

                        let od = {id: inst.entity.profile.id, portrait: {id: x.id}};
                        od[am7model.jsonModelKey] = "identity.profile";
                        await page.patchObject(od);

                        // Extract seed from response image (important for random seeds)
                        let seed = x.attributes.filter(a => a.name == "seed");
                        if(seed.length){
                            await am7client.patchAttribute(inst.entity, "preferredSeed", seed[0].value);
                        }
                    }
                }

                page.clearToast();

                if(images.length > 0){
                    page.toast("success", "Created " + images.length + " image(s)");

                    // Save SD config for this character
                    let charConfigName = inst.api.name() + "-SD.json";
                    let saveEntity = Object.assign({}, cinst.entity);
                    saveEntity.shared = false; // Always save character config with shared=false
                    await saveSDConfig(charConfigName, saveEntity);

                    // If shared was checked, also save to shared config
                    if(cinst.api.shared && cinst.api.shared()){
                        await saveSDConfig("sharedSD.json", cinst.entity);
                        cinst.api.shared(false); // Reset checkbox after save
                    }

                    // Clear the context cache for this object and its portrait
                    page.clearContextObject(inst.api.objectId());
                    images.forEach(img => {
                        if(img.objectId) page.clearContextObject(img.objectId);
                    });

                    // Force Mithril redraw
                    m.redraw();
                }
                else{
                    page.toast("error", "Reimage failed");
                }

                endDialog();

                // Show images - single or gallery
                if(images.length === 1){
                    page.imageView(images[0]);
                } else if(images.length > 1){
                    page.imageGallery(images, inst);
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
        am7model.forms.sdConfig.fields.randomSeed.field.command = async function(){
            cinst.api.seed(-1);
        };
        am7model.forms.sdConfig.fields.createApparelSequence.field.command = async function(){
            // Get character's store and apparel
            let sto = await page.searchFirst("olio.store", undefined, undefined, inst.api.store().objectId);
            let appl = sto.apparel;
            if(!appl || !appl.length){
                page.toast("error", "No apparel found in store " + sto.name);
                return;
            }

            // Load the apparel and wearables
            am7client.clearCache("olio.apparel");
            let aq = am7view.viewQuery("olio.apparel");
            aq.field("objectId", appl[0].objectId);
            let aqr = await page.search(aq);
            let app;
            if(aqr && aqr.results && aqr.results.length){
                app = aqr.results[0];
            }
            if(!app || !app.wearables || !app.wearables.length){
                page.toast("error", "No wearables found in apparel");
                return;
            }

            // Load full wearable details
            let q = am7view.viewQuery("olio.wearable");
            q.range(0, 20);
            let oids = app.wearables.map((a) => a.objectId).join(",");
            q.field("groupId", app.wearables[0].groupId);
            let fld = q.field("objectId", oids);
            fld.comparator = "in";
            let qr = await page.search(q);
            if(!qr || !qr.results || !qr.results.length){
                page.toast("error", "No wearables found");
                return;
            }
            let wears = qr.results;

            // Get sorted unique wear levels present in wearables
            let lvls = [...new Set(wears.sort((a, b) => {
                let aL = am7model.enums.wearLevelEnumType.findIndex(we => we == a.level.toUpperCase());
                let bL = am7model.enums.wearLevelEnumType.findIndex(we => we == b.level.toUpperCase());
                return aL < bL ? -1 : aL > bL ? 1 : 0;
            }).map((w) => w.level.toUpperCase()))];

            if(!lvls.length){
                page.toast("error", "No wear levels found");
                return;
            }

            // Dress down completely (all wearables off) first
            page.toast("info", "Dressing down completely...", -1);
            let dressedDown = true;
            while(dressedDown == true){
                dressedDown = await am7olio.dressApparel(appl[0], false);
            }

            // Store original seed
            let baseSeed = cinst.api.seed();
            let images = [];

            // Check if lowest level is not NUDE - if so, create a nude image first
            let lowestLevel = lvls[0];
            let nudeIdx = am7model.enums.wearLevelEnumType.findIndex(we => we == "NUDE");
            let lowestIdx = am7model.enums.wearLevelEnumType.findIndex(we => we == lowestLevel);
            let includeNude = (lowestIdx > nudeIdx);

            // Iterate through each level from lowest to highest
            page.toast("info", "Creating apparel sequence...", -1);
            let totalImages = lvls.length + (includeNude ? 1 : 0);

            // Create nude image first if needed
            if(includeNude){
                await am7olio.setNarDescription(inst, cinst);
                page.toast("info", "Creating image 1 of " + totalImages + " (level: NUDE)...", -1);
                let imgEntity = Object.assign({}, cinst.entity);
                imgEntity.seed = baseSeed;
                let x = await m.request({ method: 'POST', url: am7client.base() + "/olio/" + inst.model.name + "/" + inst.api.objectId() + "/reimage", body: imgEntity, withCredentials: true });
                if(x){
                    // Extract seed if it was random (-1)
                    let seed = x.attributes.filter(a => a.name == "seed");
                    if(seed.length){
                        baseSeed = seed[0].value;
                        await am7client.patchAttribute(inst.entity, "preferredSeed", baseSeed);
                    }
                    await am7client.patchAttribute(x, "wearLevel", "NUDE/" + nudeIdx);
                    await applySharingTag(x, "NUDE");
                    // Apply image tags (caption/description)
                    await page.applyImageTags(x.objectId);
                    images.push(x);
                }
            }

            for(let i = 0; i < lvls.length; i++){
                // Dress up to this level first (we start fully undressed)
                await am7olio.dressApparel(appl[0], true);

                // Update narration description for current outfit
                await am7olio.setNarDescription(inst, cinst);

                // Set seed (base seed + image count for 2nd+ images)
                let useSeed = (images.length === 0) ? baseSeed : (parseInt(baseSeed) + images.length);
                cinst.api.seed(useSeed);

                // Create image
                page.toast("info", "Creating image " + (images.length + 1) + " of " + totalImages + " (level: " + lvls[i] + ")...", -1);
                let imgEntity = Object.assign({}, cinst.entity);
                imgEntity.seed = useSeed;
                let x = await m.request({ method: 'POST', url: am7client.base() + "/olio/" + inst.model.name + "/" + inst.api.objectId() + "/reimage", body: imgEntity, withCredentials: true });

                if(!x){
                    page.toast("error", "Failed to create image at level " + lvls[i]);
                    break;
                }

                // For first image (if no nude), extract seed if it was random (-1)
                if(images.length === 0){
                    let seed = x.attributes.filter(a => a.name == "seed");
                    if(seed.length){
                        baseSeed = seed[0].value;
                        await am7client.patchAttribute(inst.entity, "preferredSeed", baseSeed);
                    }
                }

                // Tag image with wear level and apply sharing tag
                let levelIndex = am7model.enums.wearLevelEnumType.findIndex(we => we == lvls[i]);
                await am7client.patchAttribute(x, "wearLevel", lvls[i] + "/" + levelIndex);
                await applySharingTag(x, lvls[i]);

                // Apply image tags (caption/description)
                await page.applyImageTags(x.objectId);

                images.push(x);
            }

            // Restore seed to extracted value (important if it was random)
            cinst.api.seed(baseSeed);

            // Save SD config for this character
            if(images.length > 0){
                let charConfigName = inst.api.name() + "-SD.json";
                let saveEntity = Object.assign({}, cinst.entity);
                saveEntity.shared = false;
                await saveSDConfig(charConfigName, saveEntity);

                // If shared was checked, also save to shared config
                if(cinst.api.shared && cinst.api.shared()){
                    await saveSDConfig("sharedSD.json", cinst.entity);
                    cinst.api.shared(false);
                }
            }
            else{
                page.toast("warn", "Nothing  to save");
            }

            page.clearToast();
            page.toast("success", "Created " + images.length + " images in apparel sequence");
            m.redraw();

            // Show images in gallery
            if(images.length === 1){
                page.imageView(images[0]);
            } else if(images.length > 1){
                page.imageGallery(images, inst);
            }
        };
        am7model.forms.sdConfig.fields.loadShared.field.command = async function(){
            let sharedConfig = await loadSDConfig("sharedSD.json");
            if(sharedConfig){
                let seed = cinst.api.seed(); // Preserve current seed
                applySDConfig(cinst, sharedConfig);
                cinst.api.seed(seed);
                cinst.api.shared(false); // Reset shared checkbox after loading
                m.redraw();
                page.toast("success", "Loaded shared SD config");
            } else {
                page.toast("warn", "No shared SD config found");
            }
        };
        am7model.forms.sdConfig.fields.randomConfig.field.command = async function(){
            let ncfg = await m.request({ method: 'GET', url: am7client.base() + "/olio/randomImageConfig", withCredentials: true });
            // Do style first since that drives the display
            cinst.api.style(ncfg.style);
            m.redraw();
            let seed = cinst.api.seed();
            for(let k in ncfg){
                if(k != "id" && k != "objectId" && k != "style"){
                    if(cinst.api[k]) cinst.api[k](ncfg[k]);
                }
            }
            cinst.api.seed(seed);
            tempApplyDefaults();
            setTimeout(m.redraw, 10);
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