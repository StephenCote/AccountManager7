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

        let qual = wear.qualities ? wear.qualities[0] : null;
        let opac = qual?.opacity || 0.0;
        let shin = qual?.shininess || 0.0;
        let smoo = qual?.smoothness || 0.0;
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
        let activeAp = appl.find(a => a.inuse) || appl[0];
        let bdress = await dressApparel(activeAp, dressUp);
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
            am7model.updateListModel(aqr.results);
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

        // Restore schema on nested wearables array to prevent schema loss
        if(wear && wear.length) {
            am7model.updateListModel(wear);
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
        am7model.updateListModel(qr.results);
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
            return false;
        }

        console.log("Max level is " + maxLevelName + " at index " + maxLevelIdx + " of " + lvls.length);
        if(dressUp && maxLevelIdx >= lvls.length){
            page.toast("info", "Already dressed up to max level");
            return false; // Can't dress up anymore
        }
        else if(!dressUp && maxLevelIdx < 0){
            page.toast("info", "Already dressed down to min level");
            return false; // Can't dress down anymore
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
            // No changes but may still be able to continue dressing
            return true;
        }
        
        console.log("Patching " + patch.length + " items to level " + newLevel);
        let aP = [];
        patch.forEach((p) => {
            aP.push(page.patchObject(p));
        });

        // Execute all patches FIRST, then clear cache to prevent race condition
        // where cache is cleared before patches complete, leaving orphaned mutations
        await Promise.all(aP);

        // Now clear cache after patches have successfully completed
        await am7client.clearCache("olio.wearable");
        await am7client.clearCache("olio.apparel");
        await am7client.clearCache("olio.store");

        // Sync the local wears array with freshly patched state
        // This ensures the nested references match what was persisted
        patch.forEach((p) => {
            let w = wears.find(w => w.id === p.id);
            if(w) {
                w.inuse = p.inuse;
            }
        });

        return true;
    }

    async function setNarDescription(inst, cinst){
        am7client.clearCache("olio.narrative");
        let nar = await page.searchFirst("olio.narrative", undefined, undefined, inst.api.narrative().objectId);
        let pro = (inst.api.gender() == "male" ? "He" : "She");
        cinst.api.description(inst.api.firstName() + " is a " + nar.physicalDescription + " " + pro + " is " + nar.outfitDescription + ".");
    }

    // ==========================================
    // Outfit Builder
    // ==========================================

    // Tech tier definitions (Kardashev-to-tier mapping)
    const TECH_TIERS = [
        { value: 0, label: "Primitive", icon: "nature", desc: "Leaves, hide, raw fur" },
        { value: 1, label: "Crafted", icon: "carpenter", desc: "Leather, linen, hemp" },
        { value: 2, label: "Artisan", icon: "handyman", desc: "Cotton, wool, silk" },
        { value: 3, label: "Industrial", icon: "precision_manufacturing", desc: "Polyester, nylon, denim" },
        { value: 4, label: "Advanced", icon: "rocket_launch", desc: "Gore-Tex, carbon fiber" }
    ];

    // Climate types
    const CLIMATE_TYPES = [
        { value: "TROPICAL", label: "Tropical", icon: "wb_sunny", desc: "Hot, humid - minimal coverage" },
        { value: "ARID", label: "Arid", icon: "terrain", desc: "Hot, dry - full coverage, light fabrics" },
        { value: "TEMPERATE", label: "Temperate", icon: "park", desc: "Moderate - versatile layering" },
        { value: "COLD", label: "Cold", icon: "ac_unit", desc: "Cold - heavy insulation" },
        { value: "ARCTIC", label: "Arctic", icon: "severe_cold", desc: "Extreme - maximum insulation" }
    ];

    // Outfit presets
    const OUTFIT_PRESETS = [
        { value: "survival", label: "Survival", icon: "camping" },
        { value: "casual", label: "Casual", icon: "weekend" },
        { value: "formal", label: "Formal", icon: "checkroom" },
        { value: "combat", label: "Combat", icon: "shield" },
        { value: "travel", label: "Travel", icon: "hiking" }
    ];

    // Body location slots for the piece editor
    const BODY_LOCATIONS = [
        { slot: "head", label: "Head", icon: "face" },
        { slot: "torso", label: "Torso", icon: "accessibility" },
        { slot: "legs", label: "Legs", icon: "straighten" },
        { slot: "feet", label: "Feet", icon: "do_not_step" },
        { slot: "hands", label: "Hands", icon: "back_hand" },
        { slot: "accessories", label: "Accessories", icon: "diamond" }
    ];

    // Outfit builder state
    let outfitBuilderState = {
        characterId: null,
        techTier: 2,
        climate: "TEMPERATE",
        preset: null,
        isLoading: false,
        currentApparel: null,
        mannequinImages: [],
        gender: "female",          // For mannequin base selection
        selectedWearLevel: "SUIT", // Current wear level to display
        mannequinBasePath: null    // Path to base mannequin image
    };

    // Wear levels for mannequin display
    const WEAR_LEVELS = [
        { value: "NONE", label: "Nude", icon: "person" },
        { value: "BASE", label: "Base", icon: "checkroom" },
        { value: "SUIT", label: "Suit", icon: "dry_cleaning" },
        { value: "OVER", label: "Over", icon: "layers" },
        { value: "OUTER", label: "Outer", icon: "ac_unit" }
    ];

    // Generate outfit via REST API
    async function generateOutfit(characterId, techTier, climate, style) {
        outfitBuilderState.isLoading = true;
        m.redraw();

        try {
            let body = { characterId: characterId };
            if (techTier !== undefined) body.techTier = techTier;
            if (climate) body.climate = climate;
            if (style) body.style = style;

            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/outfit/generate",
                body: body,
                withCredentials: true
            });

            if (resp) {
                outfitBuilderState.currentApparel = resp;
                page.toast("success", "Outfit generated successfully");
                await am7client.clearCache("olio.apparel");
                await am7client.clearCache("olio.wearable");
            }
            return resp;
        } catch (e) {
            console.error("Failed to generate outfit", e);
            page.toast("error", "Failed to generate outfit: " + e.message);
            return null;
        } finally {
            outfitBuilderState.isLoading = false;
            m.redraw();
        }
    }

    // Generate mannequin images for an apparel record
    async function generateMannequinImages(apparelId, hires, style, seed) {
        outfitBuilderState.isLoading = true;
        m.redraw();

        try {
            let body = {
                apparelId: apparelId,
                hires: hires || false,
                style: style || "fashion",
                seed: seed || 0
            };

            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/outfit/mannequin",
                body: body,
                withCredentials: true
            });

            if (resp && Array.isArray(resp)) {
                outfitBuilderState.mannequinImages = resp;
                page.toast("success", "Generated " + resp.length + " mannequin images");
            }
            return resp;
        } catch (e) {
            console.error("Failed to generate mannequin images", e);
            page.toast("error", "Failed to generate images: " + e.message);
            return [];
        } finally {
            outfitBuilderState.isLoading = false;
            m.redraw();
        }
    }

    // Build outfit from explicit pieces
    async function buildFromPieces(characterId, pieces, primaryColor, pattern) {
        outfitBuilderState.isLoading = true;
        m.redraw();

        try {
            let body = {
                characterId: characterId,
                pieces: pieces,
                primaryColor: primaryColor || null,
                pattern: pattern || "solid"
            };

            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/outfit/pieces",
                body: body,
                withCredentials: true
            });

            if (resp) {
                outfitBuilderState.currentApparel = resp;
                page.toast("success", "Custom outfit created");
                await am7client.clearCache("olio.apparel");
            }
            return resp;
        } catch (e) {
            console.error("Failed to build outfit from pieces", e);
            page.toast("error", "Failed to create outfit: " + e.message);
            return null;
        } finally {
            outfitBuilderState.isLoading = false;
            m.redraw();
        }
    }

    // Get mannequin base image URL
    function getMannequinBaseUrl(gender, size) {
        let modelName = (gender === "male") ? "maleModel" : "femaleModel";
        // Use the olio random mannequin endpoint or a static path
        return g_application_path + "/rest/olio/mannequin/" + modelName + "/" + (size || "512x768");
    }

    // Interactive Mannequin Component
    let MannequinViewer = {
        view: function(vnode) {
            let gender = vnode.attrs.gender || outfitBuilderState.gender;
            let apparel = vnode.attrs.apparel || outfitBuilderState.currentApparel;
            let wearLevel = vnode.attrs.wearLevel || outfitBuilderState.selectedWearLevel;
            let images = outfitBuilderState.mannequinImages || [];

            // Find the image for the current wear level
            let levelImage = images.find(function(img) {
                return img.wearLevel && img.wearLevel.startsWith(wearLevel);
            });

            let baseUrl = getMannequinBaseUrl(gender, "512x768");
            let overlayUrl = null;

            if (levelImage) {
                overlayUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
                    "/data.data" + levelImage.groupPath + "/" + levelImage.name + "/512x768";
            }

            return m("div", { class: "mv-container" }, [
                // Mannequin display area
                m("div", { class: "mv-viewport" }, [
                    // Base mannequin silhouette
                    m("div", { class: "mv-base" }, [
                        m("img", {
                            src: baseUrl,
                            alt: "Mannequin",
                            class: "mv-base-img",
                            onerror: function(e) {
                                // Fallback to placeholder if image not found
                                e.target.style.display = 'none';
                            }
                        }),
                        // Fallback silhouette
                        m("div", { class: "mv-silhouette" }, [
                            m("span", { class: "material-symbols-outlined mv-silhouette-icon" },
                                gender === "male" ? "man" : "woman")
                        ])
                    ]),

                    // Overlay with generated outfit image
                    overlayUrl ? m("div", { class: "mv-overlay" }, [
                        m("img", {
                            src: overlayUrl,
                            alt: wearLevel + " outfit",
                            class: "mv-overlay-img"
                        })
                    ]) : null,

                    // Loading indicator
                    outfitBuilderState.isLoading ? m("div", { class: "mv-loading" }, [
                        m("span", { class: "material-symbols-outlined animate-spin" }, "sync")
                    ]) : null
                ]),

                // Wear level tabs
                m("div", { class: "mv-levels" }, WEAR_LEVELS.map(function(level) {
                    let isSelected = wearLevel === level.value;
                    let hasImage = images.some(function(img) {
                        return img.wearLevel && img.wearLevel.startsWith(level.value);
                    });
                    return m("button", {
                        class: "mv-level-btn" + (isSelected ? " mv-level-selected" : "") +
                               (hasImage ? " mv-level-available" : ""),
                        onclick: function() {
                            outfitBuilderState.selectedWearLevel = level.value;
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, level.icon),
                        m("span", { class: "mv-level-label" }, level.label)
                    ]);
                })),

                // Gender toggle
                m("div", { class: "mv-gender-toggle" }, [
                    m("button", {
                        class: "mv-gender-btn" + (gender === "female" ? " mv-gender-selected" : ""),
                        onclick: function() {
                            outfitBuilderState.gender = "female";
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, "woman")
                    ]),
                    m("button", {
                        class: "mv-gender-btn" + (gender === "male" ? " mv-gender-selected" : ""),
                        onclick: function() {
                            outfitBuilderState.gender = "male";
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, "man")
                    ])
                ])
            ]);
        }
    };

    // Outfit Builder UI Component
    let OutfitBuilderPanel = {
        oninit: function(vnode) {
            if (vnode.attrs.characterId) {
                outfitBuilderState.characterId = vnode.attrs.characterId;
            }
            if (vnode.attrs.gender) {
                outfitBuilderState.gender = vnode.attrs.gender;
            }
        },
        view: function(vnode) {
            let characterId = vnode.attrs.characterId || outfitBuilderState.characterId;

            return m("div", { class: "ob-panel" }, [
                // Header
                m("div", { class: "ob-header" }, [
                    m("span", { class: "material-symbols-outlined" }, "checkroom"),
                    " Outfit Builder"
                ]),

                // Loading indicator
                outfitBuilderState.isLoading ? m("div", { class: "ob-loading" }, [
                    m("span", { class: "material-symbols-outlined animate-spin" }, "sync"),
                    " Generating..."
                ]) : null,

                // Tech Tier Selection
                m("div", { class: "ob-section" }, [
                    m("div", { class: "ob-section-label" }, "Tech Level"),
                    m("div", { class: "ob-tier-grid" }, TECH_TIERS.map(function(tier) {
                        let isSelected = outfitBuilderState.techTier === tier.value;
                        return m("button", {
                            class: "ob-tier-btn" + (isSelected ? " ob-tier-selected" : ""),
                            title: tier.desc,
                            onclick: function() { outfitBuilderState.techTier = tier.value; }
                        }, [
                            m("span", { class: "material-symbols-outlined" }, tier.icon),
                            m("span", { class: "ob-tier-label" }, tier.label)
                        ]);
                    }))
                ]),

                // Climate Selection
                m("div", { class: "ob-section" }, [
                    m("div", { class: "ob-section-label" }, "Climate"),
                    m("div", { class: "ob-climate-grid" }, CLIMATE_TYPES.map(function(climate) {
                        let isSelected = outfitBuilderState.climate === climate.value;
                        return m("button", {
                            class: "ob-climate-btn" + (isSelected ? " ob-climate-selected" : ""),
                            title: climate.desc,
                            onclick: function() { outfitBuilderState.climate = climate.value; }
                        }, [
                            m("span", { class: "material-symbols-outlined" }, climate.icon),
                            m("span", { class: "ob-climate-label" }, climate.label)
                        ]);
                    }))
                ]),

                // Presets
                m("div", { class: "ob-section" }, [
                    m("div", { class: "ob-section-label" }, "Quick Presets"),
                    m("div", { class: "ob-preset-grid" }, OUTFIT_PRESETS.map(function(preset) {
                        return m("button", {
                            class: "ob-preset-btn",
                            onclick: async function() {
                                await generateOutfit(characterId, outfitBuilderState.techTier, outfitBuilderState.climate, preset.value);
                                if (vnode.attrs.onGenerate) {
                                    vnode.attrs.onGenerate(outfitBuilderState.currentApparel);
                                }
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined" }, preset.icon),
                            " " + preset.label
                        ]);
                    }))
                ]),

                // Generate Button
                m("div", { class: "ob-section ob-actions" }, [
                    m("button", {
                        class: "ob-generate-btn",
                        disabled: !characterId || outfitBuilderState.isLoading,
                        onclick: async function() {
                            await generateOutfit(characterId, outfitBuilderState.techTier, outfitBuilderState.climate);
                            if (vnode.attrs.onGenerate) {
                                vnode.attrs.onGenerate(outfitBuilderState.currentApparel);
                            }
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, "auto_awesome"),
                        " Generate Outfit"
                    ])
                ]),

                // Interactive Mannequin Viewer
                m("div", { class: "ob-section" }, [
                    m("div", { class: "ob-section-label" }, "Mannequin Preview"),
                    m(MannequinViewer, {
                        gender: outfitBuilderState.gender,
                        apparel: outfitBuilderState.currentApparel,
                        wearLevel: outfitBuilderState.selectedWearLevel
                    })
                ]),

                // Current Apparel Preview (if available)
                outfitBuilderState.currentApparel ? m("div", { class: "ob-section ob-preview" }, [
                    m("div", { class: "ob-section-label" }, "Current Outfit"),
                    m("div", { class: "ob-apparel-name" }, outfitBuilderState.currentApparel.name || "Unnamed"),
                    m("div", { class: "ob-apparel-desc" }, outfitBuilderState.currentApparel.description || ""),

                    // Mannequin image button
                    m("button", {
                        class: "ob-mannequin-btn",
                        onclick: async function() {
                            await generateMannequinImages(outfitBuilderState.currentApparel.objectId);
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, "photo_camera"),
                        " Generate Outfit Images"
                    ]),

                    // Mannequin gallery (per-level images)
                    outfitBuilderState.mannequinImages.length > 0 ? m("div", { class: "ob-mannequin-gallery" },
                        outfitBuilderState.mannequinImages.map(function(img) {
                            let imgUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
                                "/data.data" + img.groupPath + "/" + img.name + "/256x256";
                            return m("div", {
                                class: "ob-mannequin-thumb" + (img.wearLevel && img.wearLevel.startsWith(outfitBuilderState.selectedWearLevel) ? " ob-thumb-selected" : ""),
                                onclick: function() {
                                    if (img.wearLevel) {
                                        outfitBuilderState.selectedWearLevel = img.wearLevel.split("/")[0];
                                    }
                                }
                            }, [
                                m("img", { src: imgUrl, alt: img.wearLevel || "Level" }),
                                m("div", { class: "ob-level-label" }, img.wearLevel ? img.wearLevel.split("/")[0] : "")
                            ]);
                        })
                    ) : null
                ]) : null
            ]);
        }
    };

    // Piece Editor Component (for detailed outfit customization)
    let PieceEditorPanel = {
        view: function(vnode) {
            let apparel = vnode.attrs.apparel;
            let wearables = apparel && apparel.wearables ? apparel.wearables : [];

            // Group wearables by body location
            let byLocation = {};
            BODY_LOCATIONS.forEach(function(loc) {
                byLocation[loc.slot] = [];
            });
            wearables.forEach(function(w) {
                let locs = w.location || [];
                locs.forEach(function(l) {
                    let slot = l.toLowerCase();
                    if (slot.includes("head") || slot.includes("face") || slot.includes("ear")) {
                        byLocation.head.push(w);
                    } else if (slot.includes("torso") || slot.includes("chest") || slot.includes("back") || slot.includes("shoulder")) {
                        byLocation.torso.push(w);
                    } else if (slot.includes("leg") || slot.includes("hip") || slot.includes("thigh")) {
                        byLocation.legs.push(w);
                    } else if (slot.includes("foot") || slot.includes("ankle")) {
                        byLocation.feet.push(w);
                    } else if (slot.includes("hand") || slot.includes("wrist") || slot.includes("finger")) {
                        byLocation.hands.push(w);
                    } else {
                        byLocation.accessories.push(w);
                    }
                });
            });

            return m("div", { class: "pe-panel" }, [
                m("div", { class: "pe-header" }, [
                    m("span", { class: "material-symbols-outlined" }, "edit"),
                    " Piece Editor"
                ]),

                m("div", { class: "pe-slots" }, BODY_LOCATIONS.map(function(loc) {
                    let pieces = byLocation[loc.slot] || [];
                    return m("div", { class: "pe-slot" }, [
                        m("div", { class: "pe-slot-header" }, [
                            m("span", { class: "material-symbols-outlined" }, loc.icon),
                            " " + loc.label
                        ]),
                        m("div", { class: "pe-slot-items" }, pieces.length > 0 ?
                            pieces.map(function(p) {
                                return m("div", {
                                    class: "pe-item" + (p.inuse ? " pe-item-worn" : "")
                                }, [
                                    m("span", { class: "pe-item-name" }, p.name),
                                    p.color ? m("span", {
                                        class: "pe-item-color",
                                        style: "background-color: " + (p.color.hex || "#888")
                                    }) : null
                                ]);
                            }) :
                            m("div", { class: "pe-slot-empty" }, "Empty")
                        )
                    ]);
                }))
            ]);
        }
    };

    let am7olio = {
        dressUp,
        dressDown,
        dressCharacter,
        dressApparel,
        setNarDescription,
        // Outfit builder exports
        TECH_TIERS,
        CLIMATE_TYPES,
        OUTFIT_PRESETS,
        BODY_LOCATIONS,
        WEAR_LEVELS,
        outfitBuilderState,
        generateOutfit,
        generateMannequinImages,
        buildFromPieces,
        getMannequinBaseUrl,
        OutfitBuilderPanel,
        MannequinViewer,
        PieceEditorPanel
    };

    if (typeof module != "undefined") {
        module.am7olio = am7olio;
    } else {
        window.am7olio = am7olio;
    }
}());