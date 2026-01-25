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
        "nude",
        "selfie",
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

    // ==========================================
    // Image Token System (shared by cardGame.js and chat.js)
    // ==========================================

    const chatImageTags = ["selfie", "nude", "intimate", "sexy", "private", "casual", "public", "professional"];

    const tagToWearLevel = {
        "nude": "NONE",
        "intimate": "BASE",
        "sexy": "BASE",
        "private": "ACCENT",
        "casual": "SUIT",
        "public": "SUIT",
        "professional": "SUIT",
        "selfie": null
    };

    // Cache for resolved image URLs
    let resolvedImageCache = {};

    function parseImageTokens(content) {
        if (!content) return [];
        let tokens = [];
        let regex = /\$\{image\.([^}]+)\}/g;
        let match;
        while ((match = regex.exec(content)) !== null) {
            let inner = match[1];
            let parts = inner.split(".");
            let id = null;
            let tagStr;
            if (parts.length > 1 && parts[0].indexOf("-") > -1) {
                id = parts[0];
                tagStr = parts.slice(1).join(".");
            } else {
                tagStr = inner;
            }
            let tags = tagStr.split(",").map(t => t.trim()).filter(t => t.length > 0);
            tokens.push({
                match: match[0],
                id: id,
                tags: tags,
                start: match.index,
                end: match.index + match[0].length
            });
        }
        return tokens;
    }

    function getImageThumbnailUrl(image, size) {
        if (!image || !image.groupPath || !image.name) return null;
        return g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
               "/data.data" + image.groupPath + "/" + image.name + "/" + (size || "256x256");
    }

    async function findImageForTags(character, tags) {
        if (!character || !tags || !tags.length) return null;

        let charName = character.name || (character.firstName + " " + character.lastName);
        let nameTag = await page.getTag(charName, "data.data");
        if (!nameTag) return null;

        let charImages = await am7client.members("data.tag", nameTag.objectId, "data.data", 0, 100);
        if (!charImages || !charImages.length) return null;

        let candidateIds = new Set(charImages.map(img => img.objectId));

        for (let tagName of tags) {
            if (tagName === "selfie") continue;
            let tag = await page.getTag(tagName, "data.data");
            if (!tag) {
                candidateIds.clear();
                break;
            }
            let tagMembers = await am7client.members("data.tag", tag.objectId, "data.data", 0, 100);
            if (!tagMembers || !tagMembers.length) {
                candidateIds.clear();
                break;
            }
            let memberIds = new Set(tagMembers.map(img => img.objectId));
            for (let cid of candidateIds) {
                if (!memberIds.has(cid)) candidateIds.delete(cid);
            }
        }

        if (candidateIds.size === 0) return null;
        let ids = Array.from(candidateIds);
        let picked = ids[Math.floor(Math.random() * ids.length)];
        return charImages.find(img => img.objectId === picked) || null;
    }

    // When true, opens the reimage dialog before generating so user can review/edit config
    let debugReimage = false;

    async function generateImageForTags(character, tags) {
        if (!character) return null;

        page.toast("info", "Generating image for " + (character.name || "character") + "...", -1);

        try {
            let targetLevel = "SUIT";
            for (let tag of tags) {
                if (tagToWearLevel[tag] && tagToWearLevel[tag] !== null) {
                    let lvl = tagToWearLevel[tag];
                    let levelOrder = ["NONE", "BASE", "ACCENT", "SUIT"];
                    if (levelOrder.indexOf(lvl) < levelOrder.indexOf(targetLevel)) {
                        targetLevel = lvl;
                    }
                }
            }

            // Load SD config
            let entity = await m.request({
                method: 'GET',
                url: am7client.base() + "/olio/randomImageConfig",
                withCredentials: true
            });

            // Set style to selfie if tags include "selfie"
            if (tags.indexOf("selfie") >= 0) {
                entity.style = "selfie";
            }

            let charType = character[am7model.jsonModelKey] || "olio.charPerson";
            let charName = character.name || (character.firstName + " " + character.lastName);

            // Save current portrait so we can restore it after reimage
            let charRecord = await page.searchFirst(charType, undefined, undefined, character.objectId);
            let originalPortraitId = charRecord && charRecord.profile && charRecord.profile.portrait ? charRecord.profile.portrait.id : null;
            let profileId = charRecord && charRecord.profile ? charRecord.profile.id : null;

            // Dress character to target wear level
            let originalWearStates = [];
            if (charRecord && charRecord.store) {
                let sto = await page.searchFirst("olio.store", undefined, undefined, charRecord.store.objectId);
                if (sto && sto.apparel && sto.apparel.length) {
                    am7client.clearCache("olio.apparel");
                    let aq = am7view.viewQuery("olio.apparel");
                    aq.field("objectId", sto.apparel[0].objectId);
                    let aqr = await page.search(aq);
                    if (aqr && aqr.results && aqr.results.length) {
                        let app = aqr.results[0];
                        if (app.wearables && app.wearables.length) {
                            let q = am7view.viewQuery("olio.wearable");
                            q.range(0, 20);
                            let oids = app.wearables.map(a => a.objectId).join(",");
                            q.field("groupId", app.wearables[0].groupId);
                            let fld = q.field("objectId", oids);
                            fld.comparator = "in";
                            let qr = await page.search(q);
                            if (qr && qr.results && qr.results.length) {
                                let wears = qr.results;
                                let targetIdx = am7model.enums.wearLevelEnumType.indexOf(targetLevel);
                                let patches = [];
                                wears.forEach(function(w) {
                                    if (w.level) {
                                        originalWearStates.push({id: w.id, inuse: w.inuse});
                                        let lvl = am7model.enums.wearLevelEnumType.indexOf(w.level.toUpperCase());
                                        let shouldWear = (targetLevel !== "NONE" && lvl <= targetIdx);
                                        if (w.inuse !== shouldWear) {
                                            patches.push({schema: 'olio.wearable', id: w.id, inuse: shouldWear});
                                        }
                                    }
                                });
                                if (patches.length) {
                                    await am7client.clearCache();
                                    await Promise.all(patches.map(function(p) { return page.patchObject(p); }));
                                }
                            }
                        }
                    }
                }
            }

            // If debug mode, open the reimage dialog and wait for user confirmation
            if (debugReimage) {
                page.clearToast();
                let cinst = am7model.prepareInstance(entity, am7model.forms.sdConfig);

                // Try to load character-specific SD config
                let charConfigName = charName + "-SD.json";
                let charConfig = await loadSDConfig(charConfigName);
                if (charConfig) {
                    applySDConfig(cinst, charConfig);
                }

                // Wait for user to confirm or cancel the dialog
                entity = await new Promise(function(resolve, reject) {
                    let cfg = {
                        label: "Chat Image: " + charName + " [" + tags.join(", ") + "]",
                        entityType: "olio.sd.config",
                        size: 75,
                        data: {entity: cinst.entity, inst: cinst},
                        confirm: async function() {
                            endDialog();
                            resolve(cinst.entity);
                        },
                        cancel: async function() {
                            endDialog();
                            resolve(null);
                        }
                    };
                    setDialog(cfg);
                });

                if (!entity) {
                    page.clearToast();
                    return null; // User cancelled
                }
                page.toast("info", "Generating image...", -1);
            }

            let image = await m.request({
                method: 'POST',
                url: am7client.base() + "/olio/" + charType + "/" + character.objectId + "/reimage",
                body: entity,
                withCredentials: true
            });

            if (!image) {
                page.toast("error", "Failed to generate image");
                return null;
            }

            // Tag with sharing context
            let sharingTag = targetLevel === "NONE" ? "nude" :
                            targetLevel === "BASE" ? "intimate" : "public";
            let stag = await getOrCreateSharingTag(sharingTag, "data.data");
            if (stag) {
                await page.member("data.tag", stag.objectId, "data.data", image.objectId, true);
            }

            for (let tagName of tags) {
                if (tagName !== sharingTag) {
                    let t = await getOrCreateSharingTag(tagName, "data.data");
                    if (t) {
                        await page.member("data.tag", t.objectId, "data.data", image.objectId, true);
                    }
                }
            }

            let nameTag = await getOrCreateSharingTag(charName, "data.data");
            if (nameTag) {
                await page.member("data.tag", nameTag.objectId, "data.data", image.objectId, true);
            }

            await page.applyImageTags(image.objectId);

            // Restore original portrait (reimage sets it to the new image)
            if (profileId && originalPortraitId) {
                let od = {id: profileId, portrait: {id: originalPortraitId}};
                od[am7model.jsonModelKey] = "identity.profile";
                await page.patchObject(od);
            }

            // Restore original wear states
            if (originalWearStates.length) {
                let restorePatches = originalWearStates.map(function(s) {
                    return {schema: 'olio.wearable', id: s.id, inuse: s.inuse};
                });
                await am7client.clearCache();
                await Promise.all(restorePatches.map(function(p) { return page.patchObject(p); }));
            }

            page.clearToast();
            page.toast("success", "Image generated");
            return image;
        } catch (e) {
            console.error("generateImageForTags error:", e);
            page.clearToast();
            page.toast("error", "Image generation failed");
            return null;
        }
    }

    async function resolveImageToken(token, character) {
        if (token.id) {
            if (resolvedImageCache[token.id]) {
                return resolvedImageCache[token.id];
            }
            let charName = character.name || (character.firstName + " " + character.lastName);
            let nameTag = await page.getTag(charName, "data.data");
            if (nameTag) {
                let members = await am7client.members("data.tag", nameTag.objectId, "data.data", 0, 100);
                if (members) {
                    let img = members.find(mi => mi.objectId === token.id);
                    if (img) {
                        let url = getImageThumbnailUrl(img, "256x256");
                        resolvedImageCache[token.id] = { image: img, url: url };
                        return { image: img, url: url };
                    }
                }
            }
            return null;
        }

        let image = await findImageForTags(character, token.tags);
        if (!image) {
            image = await generateImageForTags(character, token.tags);
        }
        if (image) {
            let url = getImageThumbnailUrl(image, "256x256");
            resolvedImageCache[image.objectId] = { image: image, url: url };
            return { image: image, url: url };
        }
        return null;
    }

    // Expose image token functions globally
    if(typeof window !== "undefined"){
        window.am7imageTokens = {
            tags: chatImageTags,
            tagToWearLevel: tagToWearLevel,
            cache: resolvedImageCache,
            parse: parseImageTokens,
            thumbnailUrl: getImageThumbnailUrl,
            findForTags: findImageForTags,
            generateForTags: generateImageForTags,
            resolve: resolveImageToken,
            get debug() { return debugReimage; },
            set debug(v) { debugReimage = v; }
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

        let isCharPerson = inst.model.name === "olio.charPerson";

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

        // Use consistent photograph style as default (can be overridden by saved config)
        cinst.entity.style = "photograph";

        // Try to load character-specific config
        let charConfigName = inst.api.name() + "-SD.json";
        let charConfig = await loadSDConfig(charConfigName);
        if(charConfig){
            applySDConfig(cinst, charConfig);
        }

        lastReimage = cinst;

        if(isCharPerson) {
            await am7olio.setNarDescription(inst, cinst);
        } else {
            // For data.data, set reference image for img2img
            if(inst.api.objectId()) {
                cinst.entity.referenceImageId = inst.api.objectId();
            }
        }

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
                let wearLevel = isCharPerson ? await getCurrentWearLevel(inst) : null;

                for(let i = 0; i < count; i++){
                    page.toast("info", "Creating image " + (i + 1) + " of " + count + "...", -1);

                    // Increment seed for subsequent images
                    let useSeed = baseSeed;//(i === 0) ? baseSeed : (parseInt(baseSeed) + i);
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

                    if(isCharPerson) {
                        // Apply character name tag (model type, for character queries)
                        let nameTag = await getOrCreateSharingTag(inst.api.name(), inst.model.name);
                        if(nameTag){
                            await page.member("data.tag", nameTag.objectId, "data.data", x.objectId, true);
                        }
                        // Apply character name tag (data.data type, for image discovery)
                        let imageNameTag = await getOrCreateSharingTag(inst.api.name(), "data.data");
                        if(imageNameTag){
                            await page.member("data.tag", imageNameTag.objectId, "data.data", x.objectId, true);
                        }
                    }

                    // Apply style-specific tags
                    if(cinst.entity.style === "selfie") {
                        let selfieTag = await getOrCreateSharingTag("selfie", "data.data");
                        if(selfieTag){
                            await page.member("data.tag", selfieTag.objectId, "data.data", x.objectId, true);
                        }
                    }

                    images.push(x);

                    // For first image, extract seed and update portrait
                    if(i === 0){
                        if(isCharPerson) {
                            inst.entity.profile.portrait = x;

                            let od = {id: inst.entity.profile.id, portrait: {id: x.id}};
                            od[am7model.jsonModelKey] = "identity.profile";
                            await page.patchObject(od);
                        }

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
            if(!isCharPerson) return;
            await am7olio.dressCharacter(inst, false);
            await am7olio.setNarDescription(inst, cinst);
        };

        am7model.forms.sdConfig.fields.dressUp.field.command = async function(){
            if(!isCharPerson) return;
            await am7olio.dressCharacter(inst, true);
            await am7olio.setNarDescription(inst, cinst);
        };
        am7model.forms.sdConfig.fields.randomSeed.field.command = async function(){
            cinst.api.seed(-1);
        };
        am7model.forms.sdConfig.fields.selectReference.field.command = async function(){
            let galleryImages = [];
            if(isCharPerson) {
                let profile = inst.entity.profile;
                let portrait = profile ? profile.portrait : null;
                let profileObjectId = profile ? profile.objectId : null;
                if(profileObjectId && (!portrait || !portrait.groupId)){
                    let pq = am7view.viewQuery("identity.profile");
                    pq.field("objectId", profileObjectId);
                    pq.entity.request.push("portrait");
                    let pqr = await page.search(pq);
                    if(pqr && pqr.results && pqr.results.length){
                        portrait = pqr.results[0].portrait;
                    }
                }
                if(portrait && portrait.groupId){
                    let q = am7client.newQuery("data.data");
                    q.field("groupId", portrait.groupId);
                    q.entity.request.push("id", "objectId", "name", "groupId", "groupPath", "contentType");
                    q.range(0, 100);
                    q.sort("createdDate");
                    q.order("descending");
                    let qr = await page.search(q);
                    if(qr && qr.results) {
                        galleryImages = qr.results.filter(r => r.contentType && r.contentType.match(/^image\//i));
                    }
                }
            } else if(inst.entity.groupId) {
                let q = am7client.newQuery("data.data");
                q.field("groupId", inst.entity.groupId);
                q.entity.request.push("id", "objectId", "name", "groupId", "groupPath", "contentType");
                q.range(0, 100);
                q.sort("createdDate");
                q.order("descending");
                let qr = await page.search(q);
                if(qr && qr.results) {
                    galleryImages = qr.results.filter(r => r.contentType && r.contentType.match(/^image\//i));
                }
            }
            if(!galleryImages.length){
                page.toast("warn", "No gallery images found");
                return;
            }
            let savedCfg = dialogCfg;
            let orgPath = am7client.dotPath(am7client.currentOrganization);
            let pickerView = {
                view: function(){
                    return m("div", {style: "padding: 12px; display: flex; flex-wrap: wrap; gap: 8px; max-height: 400px; overflow-y: auto;"},
                        galleryImages.map(function(img){
                            let src = g_application_path + "/thumbnail/" + orgPath + "/data.data" + img.groupPath + "/" + img.name + "/96x96";
                            let selected = cinst.entity.referenceImageId === img.objectId;
                            return m("img", {
                                src: src,
                                width: 96, height: 96,
                                style: "object-fit: cover; border-radius: 4px; cursor: pointer; border: 3px solid " + (selected ? "#2196F3" : "transparent") + ";",
                                title: img.name,
                                onclick: function(){
                                    cinst.entity.referenceImageId = img.objectId;
                                    if(!cinst.entity.denoisingStrength || cinst.entity.denoisingStrength === 0.75) {
                                        cinst.entity.denoisingStrength = 0.6;
                                    }
                                    setDialog(savedCfg);
                                    m.redraw();
                                }
                            });
                        })
                    );
                }
            };
            setDialog({
                label: "Select Reference Image",
                entityType: "imagePicker",
                size: 75,
                data: {entity: {}, view: pickerView},
                cancel: async function(){
                    setDialog(savedCfg);
                }
            });
        };
        am7model.forms.sdConfig.fields.createApparelSequence.field.command = async function(){
            if(!isCharPerson) return;
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
                    // Apply character name tag (model type, for character queries)
                    let nameTag = await getOrCreateSharingTag(inst.api.name(), inst.model.name);
                    if(nameTag){
                        await page.member("data.tag", nameTag.objectId, "data.data", x.objectId, true);
                    }
                    // Apply character name tag (data.data type, for image discovery)
                    let imageNameTag = await getOrCreateSharingTag(inst.api.name(), "data.data");
                    if(imageNameTag){
                        await page.member("data.tag", imageNameTag.objectId, "data.data", x.objectId, true);
                    }
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

                // Apply character name tag (model type, for character queries)
                let nameTag = await getOrCreateSharingTag(inst.api.name(), inst.model.name);
                if(nameTag){
                    await page.member("data.tag", nameTag.objectId, "data.data", x.objectId, true);
                }
                // Apply character name tag (data.data type, for image discovery)
                let imageNameTag = await getOrCreateSharingTag(inst.api.name(), "data.data");
                if(imageNameTag){
                    await page.member("data.tag", imageNameTag.objectId, "data.data", x.objectId, true);
                }

                // Apply style-specific tags (e.g., selfie)
                if(cinst.entity.style === "selfie") {
                    let selfieTag = await getOrCreateSharingTag("selfie", "data.data");
                    if(selfieTag){
                        await page.member("data.tag", selfieTag.objectId, "data.data", x.objectId, true);
                    }
                }

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

    async function reimageApparel(object, inst) {
        if (!inst || inst.model.name !== "olio.apparel") {
            page.toast("error", "Not an apparel record");
            return;
        }

        let entity = await m.request({ method: 'GET', url: am7client.base() + "/olio/randomImageConfig", withCredentials: true });
        let cinst = am7model.prepareInstance(entity, am7model.forms.sdMannequinConfig);

        function tempApplyDefaults(){
            cinst.api.steps(40);
            cinst.api.refinerSteps(40);
            cinst.api.model("sdXL_v10VAEFix.safetensors");
            cinst.api.refinerModel("juggernautXL_ragnarokBy.safetensors");
            // Use consistent photograph style for apparel
            cinst.entity.style = "photograph";
        }

        tempApplyDefaults();

        // Try to load shared apparel config
        let sharedConfig = await loadSDConfig("sharedApparelSD.json");
        if(sharedConfig){
            applySDConfig(cinst, sharedConfig);
        }

        // Wire up the random seed button
        am7model.forms.sdMannequinConfig.fields.randomSeed.field.command = async function(){
            cinst.api.seed(-1);
        };

        // Wire up the load shared button
        am7model.forms.sdMannequinConfig.fields.loadShared.field.command = async function(){
            let config = await loadSDConfig("sharedApparelSD.json");
            if(config){
                applySDConfig(cinst, config);
                page.toast("success", "Loaded shared apparel config");
                m.redraw();
            } else {
                page.toast("warn", "No shared apparel config found");
            }
        };

        let cfg = {
            label: "Mannequin Images: " + inst.api.name(),
            entityType: "olio.sd.config",
            size: 50,
            data: {entity: cinst.entity, inst: cinst},
            confirm: async function (data) {
                let baseSeed = cinst.api.seed();
                let hires = cinst.api.hires ? cinst.api.hires() : false;

                page.toast("info", "Creating mannequin images...", -1);

                let imgEntity = Object.assign({}, cinst.entity);
                imgEntity.seed = baseSeed;
                imgEntity.hires = hires;

                let images = await m.request({
                    method: 'POST',
                    url: am7client.base() + "/olio/apparel/" + inst.api.objectId() + "/reimage",
                    body: imgEntity,
                    withCredentials: true
                });

                console.log("reimageApparel: Server returned images:", images);

                page.clearToast();

                if(images && images.length > 0){
                    page.toast("success", "Created " + images.length + " mannequin image(s)");

                    // Clear the context cache for this object
                    page.clearContextObject(inst.api.objectId());
                    images.forEach(img => {
                        if(img.objectId) page.clearContextObject(img.objectId);
                    });

                    // Apply style-specific tags (e.g., selfie)
                    if(cinst.entity.style === "selfie") {
                        let selfieTag = await getOrCreateSharingTag("selfie", "data.data");
                        if(selfieTag){
                            for(let img of images){
                                await page.member("data.tag", selfieTag.objectId, "data.data", img.objectId, true);
                            }
                        }
                    }

                    // Save shared config if checkbox is checked
                    if(cinst.api.shared && cinst.api.shared()){
                        await saveSDConfig("sharedApparelSD.json", cinst.entity);
                        cinst.api.shared(false);
                    }

                    // Force Mithril redraw
                    m.redraw();

                }
                else{
                    page.toast("error", "Mannequin imaging failed");
                }

                // Close config dialog first, then show images
                endDialog();

                // Show images - these functions open their own dialogs
                if(images && images.length > 0){
                    console.log("reimageApparel: About to call imageGallery, inst:", inst, "inst.entity:", inst?.entity);
                    if(images.length === 1){
                        page.imageView(images[0]);
                    } else if(images.length > 1){
                        page.imageGallery(images, inst);
                    }
                }
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

    function batchProgress(label, items, processItem, onComplete){
        let state = {
            started: false,
            running: false,
            aborted: false,
            current: 0,
            total: items.length,
            currentName: "",
            successCount: 0,
            errorCount: 0
        };

        async function startBatch(){
            state.started = true;
            state.running = true;
            state.aborted = false;
            m.redraw();
            for(let i = 0; i < items.length; i++){
                if(state.aborted) break;
                state.current = i + 1;
                state.currentName = items[i].name || ("Item " + (i + 1));
                m.redraw();
                try {
                    let result = await processItem(items[i], i);
                    if(result) state.successCount++;
                } catch(e){
                    console.error("Batch item error:", e);
                    state.errorCount++;
                }
            }
            state.running = false;
            m.redraw();
            if(onComplete) onComplete(state);
        }

        function stopBatch(){
            state.aborted = true;
        }

        let progressView = {
            view: function(){
                let pct = state.total > 0 ? Math.round((state.current / state.total) * 100) : 0;
                return m("div", {style: "padding: 16px;"}, [
                    m("div", {style: "margin-bottom: 12px; color: #666;"}, label + " - " + state.total + " items"),
                    m("div", {style: "margin-bottom: 8px; height: 24px; background: #e0e0e0; border-radius: 12px; overflow: hidden;"}, [
                        m("div", {style: "height: 100%; width:" + pct + "%; background: linear-gradient(90deg, #4a90d9, #67b26f); transition: width 0.3s; border-radius: 12px; display:flex; align-items:center; justify-content:center; color:#fff; font-size:0.75em; font-weight:bold;"}, state.started ? pct + "%" : "")
                    ]),
                    m("div", {style: "margin-bottom: 12px; font-size: 0.9em; color: #555; min-height: 1.4em;"},
                        state.running ? (state.current + " of " + state.total + ": " + state.currentName) :
                        (state.started ? ("Done - " + state.successCount + " succeeded" + (state.errorCount > 0 ? ", " + state.errorCount + " errors" : "") + (state.aborted ? " (aborted)" : "")) : "Ready to start")
                    ),
                    m("div", {style: "display: flex; gap: 8px;"}, [
                        !state.started ? m("button", {class: "page-dialog-button", onclick: startBatch}, [
                            m("span", {class: "material-symbols-outlined md-18 mr-2"}, "play_arrow"),
                            "Start"
                        ]) : "",
                        state.running ? m("button", {class: "page-dialog-button", onclick: stopBatch}, [
                            m("span", {class: "material-symbols-outlined md-18 mr-2"}, "stop"),
                            "Stop"
                        ]) : "",
                        (!state.running) ? m("button", {class: "page-dialog-button", onclick: function(){ endDialog(); }}, [
                            m("span", {class: "material-symbols-outlined md-18 mr-2"}, "close"),
                            "Close"
                        ]) : ""
                    ])
                ]);
            }
        };

        let cfg = {
            label: label,
            entityType: "batchProgress",
            size: 50,
            data: {entity: {}, view: progressView},
            cancel: async function(data){
                if(state.running){
                    state.aborted = true;
                } else {
                    endDialog();
                }
            }
        };
        setDialog(cfg);
    }

    async function memberCloud(modelType, containerId){
        let stats = await am7client.membershipStats(modelType, "any", containerId, 0);
        if(!stats || !stats.length){
            page.toast("info", "No membership data found");
            return;
        }

        let cloudMode = true;
        let selectedStat = null;
        let memberList = [];
        let previewSrc = null;

        let minCount = Math.min(...stats.map(s => s.count));
        let maxCount = Math.max(...stats.map(s => s.count));

        function fontSize(count){
            if(maxCount === minCount) return 1.5;
            return 0.8 + ((count - minCount) / (maxCount - minCount)) * 2.2;
        }

        async function selectStat(stat){
            selectedStat = stat;
            cloudMode = false;
            memberList = [];
            m.redraw();
            let mems = await am7client.members(modelType, stat.objectId, stat.type || "any", 0, 100);
            if(mems && mems.length) am7model.updateListModel(mems);
            memberList = mems || [];
            m.redraw();
        }

        function backToCloud(){
            cloudMode = true;
            selectedStat = null;
            memberList = [];
            m.redraw();
        }

        function cloudColor(count){
            let ratio = (maxCount === minCount) ? 0.5 : (count - minCount) / (maxCount - minCount);
            let hue = 210 - (ratio * 160);
            let sat = 55 + (ratio * 25);
            let lit = 92 - (ratio * 20);
            return "hsl(" + hue + "," + sat + "%," + lit + "%)";
        }

        function cloudTextColor(count){
            let ratio = (maxCount === minCount) ? 0.5 : (count - minCount) / (maxCount - minCount);
            let hue = 210 - (ratio * 160);
            let lit = 35 - (ratio * 10);
            return "hsl(" + hue + ",60%," + lit + "%)";
        }

        let cloudView = {
            view: function(){
                if(cloudMode){
                    return m("div", {style: "padding: 20px; text-align: center; line-height: 1.4;"},
                        stats.map(function(stat){
                            let size = fontSize(stat.count);
                            let bg = cloudColor(stat.count);
                            let fg = cloudTextColor(stat.count);
                            return m("span", {
                                style: "font-size:" + size + "em; cursor:pointer; margin: 5px 6px; display:inline-block;" +
                                    "background:" + bg + "; color:" + fg + ";" +
                                    "padding: 4px 12px; border-radius: 20px;" +
                                    "border: 1px solid " + fg + "20;" +
                                    "transition: transform 0.15s, box-shadow 0.15s;" +
                                    "box-shadow: 0 1px 3px rgba(0,0,0,0.08);",
                                title: stat.name + " (" + stat.count + ")",
                                onmouseenter: function(e){ e.target.style.transform = "scale(1.08)"; e.target.style.boxShadow = "0 3px 8px rgba(0,0,0,0.15)"; },
                                onmouseleave: function(e){ e.target.style.transform = ""; e.target.style.boxShadow = "0 1px 3px rgba(0,0,0,0.08)"; },
                                onclick: function(){ selectStat(stat); }
                            }, [stat.name, m("sup", {style: "margin-left:3px; font-size:0.65em; opacity:0.7;"}, stat.count)]);
                        })
                    );
                }
                // Detail view
                return m("div", {style: "padding: 16px; position: relative;"}, [
                    previewSrc ? m("div", {
                        style: "position: absolute; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.85); z-index: 10; display: flex; align-items: center; justify-content: center; cursor: pointer;",
                        onclick: function(){ previewSrc = null; m.redraw(); }
                    }, [
                        m("img", {src: previewSrc, style: "max-width: 90%; max-height: 90%; object-fit: contain; border-radius: 4px; box-shadow: 0 4px 24px rgba(0,0,0,0.5);"})
                    ]) : "",
                    m("div", {style: "margin-bottom: 12px;"}, [
                        m("button", {class: "page-dialog-button", onclick: backToCloud}, [
                            m("span", {class: "material-symbols-outlined md-18 mr-2"}, "arrow_back"),
                            "Back"
                        ])
                    ]),
                    m("div", {style: "margin-bottom: 12px;"}, [
                        m("h4", {style: "margin: 0 0 4px 0;"}, [
                            m("a", {href: "#!/view/" + modelType + "/" + selectedStat.objectId, target: "_blank"}, selectedStat.name)
                        ]),
                        m("span", {style: "color: #666;"}, selectedStat.count + " member" + (selectedStat.count != 1 ? "s" : ""))
                    ]),
                    m("div", {class: "cloud-members"},
                        memberList.length == 0 ? m("em", "Loading members...") :
                        m("ul", {style: "list-style: none; padding: 0; margin: 0;"},
                            memberList.map(function(mem){
                                let memType = mem[am7model.jsonModelKey] || "unknown";
                                let memName = mem.name || mem.objectId;
                                let thumb = "";
                                let orgPath = am7client.dotPath(am7client.currentOrganization);
                                let imgSrc = null;
                                if(memType == "olio.charPerson" && mem.profile && mem.profile.portrait && mem.profile.portrait.contentType){
                                    let pp = mem.profile.portrait;
                                    imgSrc = g_application_path + "/thumbnail/" + orgPath + "/data.data" + pp.groupPath + "/" + pp.name;
                                    thumb = m("img", {height: 48, width: 48, style: "border-radius: 50%; object-fit: cover; margin-right: 8px; cursor: pointer;", src: imgSrc + "/48x48", onclick: function(e){ e.preventDefault(); previewSrc = imgSrc + "/512x512"; m.redraw(); }});
                                } else if(memType == "olio.charPerson"){
                                    thumb = m("span", {class: "material-symbols-outlined", style: "font-size: 48px; color: #999; margin-right: 8px;"}, "person");
                                } else if(memType == "data.data" && mem.contentType && mem.contentType.match(/^image/)){
                                    imgSrc = g_application_path + "/thumbnail/" + orgPath + "/data.data" + mem.groupPath + "/" + mem.name;
                                    thumb = m("img", {height: 48, width: 48, style: "object-fit: cover; border-radius: 4px; margin-right: 8px; cursor: pointer;", src: imgSrc + "/48x48", onclick: function(e){ e.preventDefault(); previewSrc = imgSrc + "/512x512"; m.redraw(); }});
                                }
                                return m("li", {style: "padding: 4px 0; border-bottom: 1px solid #eee; display: flex; align-items: center;"}, [
                                    thumb,
                                    m("div", [
                                        m("a", {href: "#!/view/" + memType + "/" + mem.objectId, target: "_blank"}, memName),
                                        m("span", {style: "color: #999; margin-left: 8px; font-size: 0.85em;"}, memType)
                                    ])
                                ]);
                            })
                        )
                    )
                ]);
            }
        };

        let cfg = {
            label: "Membership Cloud",
            entityType: modelType,
            size: 75,
            data: {entity: {}, view: cloudView},
            cancel: async function(data){
                endDialog();
            }
        };
        setDialog(cfg);
    }

    // Show the outfit builder panel for a character
    async function showOutfitBuilder(object, inst) {
        if (!inst) {
            page.toast("error", "No instance provided");
            return;
        }

        let characterId = null;
        let gender = "female";
        let currentApparel = null;

        // Check if this is a charPerson (character) or apparel
        let modelName = inst.model ? inst.model.name : null;

        if (modelName === "olio.charPerson") {
            // Invoked from character - the entity IS the character
            characterId = inst.entity.objectId || inst.entity.id;
            gender = inst.entity.gender || "female";
            // Get the character's current apparel if any
            if (inst.entity.store && inst.entity.store.apparel && inst.entity.store.apparel.length > 0) {
                currentApparel = inst.entity.store.apparel[0];
            }
        } else if (modelName === "olio.apparel") {
            // Invoked from apparel - try to get the associated character
            if (inst.entity && inst.entity.designer) {
                characterId = inst.entity.designer.objectId || inst.entity.designer.id;
                gender = inst.entity.designer.gender || "female";
            }
            currentApparel = inst.entity;
        }

        // Set up outfit builder state
        if (window.am7olio) {
            am7olio.outfitBuilderState.characterId = characterId;
            am7olio.outfitBuilderState.gender = gender;
            am7olio.outfitBuilderState.currentApparel = currentApparel;
        }

        // Create the dialog with the outfit builder
        let cfg = {
            title: "Outfit Builder" + (inst.entity.name ? " - " + inst.entity.name : ""),
            icon: "checkroom",
            label: "Generate context-aware outfit for character",
            size: 80,
            data: { entity: inst.entity, characterId: characterId },
            view: function() {
                if (!window.am7olio || !am7olio.OutfitBuilderPanel) {
                    return m("div", { class: "p-4 text-red-500" }, "Outfit builder component not loaded");
                }
                let builderApparel = am7olio.outfitBuilderState.currentApparel;
                return m("div", { class: "flex gap-4 p-4" }, [
                    // Outfit Builder Panel
                    m("div", { class: "flex-1" }, [
                        m(am7olio.OutfitBuilderPanel, {
                            characterId: characterId,
                            gender: gender,
                            onGenerate: async function(apparel) {
                                if (apparel) {
                                    // Update the state with new apparel
                                    am7olio.outfitBuilderState.currentApparel = apparel;
                                    m.redraw();
                                }
                            }
                        })
                    ]),
                    // Piece Editor (if apparel exists)
                    builderApparel && builderApparel.wearables ? m("div", { class: "flex-1" }, [
                        m(am7olio.PieceEditorPanel, {
                            apparel: builderApparel
                        })
                    ]) : null
                ]);
            },
            submit: async function(data) {
                endDialog();
                // Refresh the character view to show updated apparel
                page.clearContextObject(inst.api.objectId());
                m.redraw();
            },
            cancel: async function(data) {
                endDialog();
            }
        };
        setDialog(cfg);
    }

    // Start the card game with a specific character
    // Only works if the character is part of a realm's population
    async function startGameWithCharacter(object, inst) {
        if (!inst || !inst.entity) {
            page.toast("error", "No character selected");
            return;
        }

        let characterId = inst.entity.objectId || inst.entity.id;
        let characterName = inst.entity.name || "Unknown";

        // Check if character is part of a realm's population by checking groupPath
        // Characters in the world should be in a path like ~/World Building/...
        let groupPath = inst.entity.groupPath || "";
        let isInWorld = groupPath.includes("World Building") || groupPath.includes("Worlds") || groupPath.includes("Population");

        if (!isInWorld) {
            // Show adopt dialog instead
            let cfg = {
                title: "Character Not in World",
                icon: "warning",
                label: "This character is not part of the Olio world. Would you like to adopt them into the world first?",
                size: 40,
                buttons: [
                    { label: "Adopt Character", value: "adopt" },
                    { label: "Cancel", value: "cancel" }
                ],
                submit: async function(data) {
                    endDialog();
                    if (data.value === "adopt") {
                        await adoptCharacter(object, inst);
                    }
                },
                cancel: async function() {
                    endDialog();
                }
            };
            setDialog(cfg);
            return;
        }

        // Navigate to the card game with this character
        page.toast("info", "Starting game with " + characterName);

        // Store the selected character for the game
        if (window.am7cardGame) {
            am7cardGame.setSelectedCharacter(characterId);
        } else {
            // Store in session for pickup by card game
            sessionStorage.setItem("olio_selected_character", characterId);
        }

        // Navigate to the card game view
        m.route.set("/app/game/cardGame", { character: characterId });
    }

    // Adopt a character into the Olio worldspace
    // Moves to proper group and adds to realm population
    async function adoptCharacter(object, inst) {
        if (!inst || !inst.entity) {
            page.toast("error", "No character selected");
            return;
        }

        let character = inst.entity;
        let characterName = character.name || "Unknown";

        // Show realm selection dialog
        let cfg = {
            title: "Adopt Character to World",
            icon: "person_add",
            label: "Adopt '" + characterName + "' into the Olio world",
            size: 50,
            view: function() {
                return m("div", { class: "p-4" }, [
                    m("p", { class: "mb-4" },
                        "This will move the character to the world's population and make them available for game interactions."),
                    m("div", { class: "mb-4" }, [
                        m("label", { class: "block mb-2 font-medium" }, "Target Realm:"),
                        m("select", {
                            id: "adoptRealmSelect",
                            class: "w-full p-2 border rounded"
                        }, [
                            m("option", { value: "default" }, "Default Realm")
                        ])
                    ]),
                    m("div", { class: "text-sm text-gray-600" }, [
                        m("p", "Current location: " + (character.groupPath || "Unknown")),
                        m("p", "Character will be added to the realm's population group.")
                    ])
                ]);
            },
            submit: async function(data) {
                endDialog();
                page.toast("info", "Adopting " + characterName + "...");

                try {
                    // Call the adopt endpoint
                    let result = await m.request({
                        method: "POST",
                        url: am7client.base() + "/rest/game/adopt/" + (character.objectId || character.id),
                        withCredentials: true,
                        body: {}
                    });

                    if (result && result.adopted) {
                        page.toast("success", characterName + " has been adopted into the world!");
                        // Refresh the character view
                        page.clearContextObject(inst.api.objectId());
                        m.redraw();
                    } else {
                        page.toast("error", result.error || "Failed to adopt character");
                    }
                } catch (e) {
                    console.error("Adopt failed:", e);
                    page.toast("error", "Failed to adopt character: " + (e.message || "Unknown error"));
                }
            },
            cancel: async function() {
                endDialog();
            }
        };
        setDialog(cfg);
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
        reimageApparel,
        showOutfitBuilder,
        startGameWithCharacter,
        adoptCharacter,
        chatSettings,
        showProgress,
        chatInto,
        memberCloud,
        batchProgress
    }
    page.components.dialog = dialog;
}())