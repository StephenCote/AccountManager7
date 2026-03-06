/**
 * olio.js — Character dress system + outfit builder (ESM)
 * Port of Ux7 client/components/olio.js
 *
 * Provides dressUp/dressDown, dressCharacter, dressApparel,
 * MannequinViewer, OutfitBuilderPanel, PieceEditorPanel components.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

// ── Dress Operations ────────────────────────────────────────────

async function dressUp(object, inst, name) {
    if (inst.model.name == "olio.charPerson") {
        await dressCharacter(inst, true);
    } else if (inst.model.name == "olio.apparel") {
        await dressApparel(inst.entity, true);
    }
}

async function dressDown(object, inst, name) {
    if (inst.model.name == "olio.charPerson") {
        await dressCharacter(inst, false);
    } else if (inst.model.name == "olio.apparel") {
        await dressApparel(inst.entity, false);
    }
}

async function setApparelDescription(app, wears) {
    let page = getPage();
    let wdesc = wears.filter(w => w.inuse).map(w => describeWearable(w)).join(", ");
    app.description = "Worn apparel includes " + wdesc + ".";
    if (wdesc.length == 0) {
        app.description = "No apparel worn.";
    }
    await page.patchObject({ schema: 'olio.apparel', id: app.id, description: app.description });
    m.redraw();
}

function describeWearable(wear) {
    let qual = wear.qualities ? wear.qualities[0] : null;
    let opac = qual?.opacity || 0.0;
    let shin = qual?.shininess || 0.0;
    let col = wear?.color?.name.toLowerCase() || "";

    if (col) {
        col = col.replaceAll(/([\^\(\)]*)/g, "");
    }
    let fab = wear?.fabric?.toLowerCase() || "";
    let pat = wear?.pattern?.name.toLowerCase() || "";
    let loc = wear?.location[0]?.toLowerCase() || "";
    let name = wear?.name;
    if (name.indexOf("pierc") > -1) {
        name = loc + " piercing";
        pat = "";
    }
    let opacs = "";
    if (opac > 0.0 && opac <= 0.25) {
        opacs = " see-through";
    }
    let shins = "";
    if (shin >= 0.7) {
        shins = " shiny";
    }

    return (shins + opacs + " " + col + " " + pat + " " + fab + " " + name).replaceAll("  ", " ").trim();
}

async function dressCharacter(inst, dressUp) {
    let am7client = getClient();
    let page = getPage();
    let storeRef = inst.api.store();
    if (!storeRef || !storeRef.objectId) {
        page.toast("error", "No store found for character");
        return;
    }
    await am7client.clearCache("olio.store");
    let sto = await am7client.getFull("olio.store", storeRef.objectId);
    if (!sto || !sto.apparel || !sto.apparel.length) {
        page.toast("error", "No apparel found in store");
        return;
    }

    let activeAp = sto.apparel.find(a => a.inuse) || sto.apparel[0];
    let bdress = await dressApparel(activeAp, dressUp);
    if (bdress) {
        if (am7model.forms && am7model.forms.commands && am7model.forms.commands.narrate) {
            await am7model.forms.commands.narrate(undefined, inst);
        }
    }
}

async function dressApparel(vapp, dressUpDir) {
    let am7client = getClient();
    let page = getPage();

    await am7client.clearCache("olio.apparel");
    let aq = am7view.viewQuery("olio.apparel");
    aq.field("objectId", vapp.objectId);
    let aqr = await page.search(aq);
    let app;
    if (aqr && aqr.results && aqr.results.length) {
        am7model.updateListModel(aqr.results);
        app = aqr.results[0];
    } else {
        page.toast("error", "No apparel found for " + vapp.objectId);
        return;
    }

    let wear = app.wearables;
    if (!wear || !wear.length) {
        page.toast("error", "No wearables found in apparel " + app.name);
        return;
    }

    if (wear && wear.length) {
        am7model.updateListModel(wear);
    }

    let q = am7view.viewQuery("olio.wearable");
    q.range(0, 20);
    let oids = wear.map(a => a.objectId).join(",");
    q.field("groupId", wear[0].groupId);
    let fld = q.field("objectId", oids);
    fld.comparator = "in";

    let qr = await page.search(q);
    if (!qr || !qr.results || !qr.results.length) {
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
    }).map(w => w.level.toUpperCase()))];

    wears.forEach(w => {
        if (w.level) {
            let lvl = am7model.enums.wearLevelEnumType.findIndex(we => we == w.level.toUpperCase());
            if (w.inuse) {
                maxLevel = Math.max(maxLevel, lvl);
            } else {
                availMin = Math.min(availMin, lvl);
            }
        }
    });

    let ulevel = (dressUpDir ? availMin : maxLevel);
    let maxLevelName = am7model.enums.wearLevelEnumType[ulevel];
    let maxLevelIdx = lvls.findIndex(l => l == maxLevelName);

    if (!lvls.includes(maxLevelName) || maxLevelIdx == -1) {
        page.toast("info", "No wearables found in apparel " + app.name + " for level " + maxLevelName);
        return false;
    }

    if (dressUpDir && maxLevelIdx >= lvls.length) {
        page.toast("info", "Already dressed up to max level");
        return false;
    } else if (!dressUpDir && maxLevelIdx < 0) {
        page.toast("info", "Already dressed down to min level");
        return false;
    }

    let newLevel = am7model.enums.wearLevelEnumType.findIndex(we => we == lvls[maxLevelIdx + (dressUpDir ? 0 : -1)]);
    let patch = [];
    wears.forEach(w => {
        if (w.level) {
            let lvl = am7model.enums.wearLevelEnumType.findIndex(we => we == w.level.toUpperCase());
            if (lvl > newLevel && w.inuse) {
                w.inuse = false;
                patch.push({ schema: 'olio.wearable', id: w.id, inuse: false });
            } else if (lvl <= newLevel && !w.inuse) {
                w.inuse = true;
                patch.push({ schema: 'olio.wearable', id: w.id, inuse: true });
            }
        }
    });

    if (patch.length || !app.description) {
        await setApparelDescription(app, wears);
        vapp.description = app.description;
    }

    if (!patch.length) {
        page.toast("info", "No changes to make to " + app.name + " for level " + newLevel);
        return true;
    }

    let aP = patch.map(p => page.patchObject(p));
    await Promise.all(aP);

    await am7client.clearCache("olio.wearable");
    await am7client.clearCache("olio.apparel");
    await am7client.clearCache("olio.store");

    patch.forEach(p => {
        let w = wears.find(w => w.id === p.id);
        if (w) w.inuse = p.inuse;
    });

    return true;
}

async function setNarDescription(inst, cinst) {
    let am7client = getClient();
    let page = getPage();
    let narRef = inst.api.narrative ? inst.api.narrative() : null;
    if (!narRef || !narRef.objectId) {
        cinst.api.description(inst.api.firstName() + " " + (inst.api.lastName ? inst.api.lastName() : ''));
        return;
    }
    await am7client.clearCache("olio.narrative");
    let nar = await page.searchFirst("olio.narrative", undefined, undefined, narRef.objectId);
    if (!nar) {
        cinst.api.description(inst.api.firstName() + " " + (inst.api.lastName ? inst.api.lastName() : ''));
        return;
    }
    let pro = (inst.api.gender() == "male" ? "He" : "She");
    cinst.api.description(inst.api.firstName() + " is a " + (nar.physicalDescription || '') + " " + pro + " is " + (nar.outfitDescription || '') + ".");
}

// ── Constants ────────────────────────────────────────────────────

const TECH_TIERS = [
    { value: 0, label: "Primitive", icon: "nature", desc: "Leaves, hide, raw fur" },
    { value: 1, label: "Crafted", icon: "carpenter", desc: "Leather, linen, hemp" },
    { value: 2, label: "Artisan", icon: "handyman", desc: "Cotton, wool, silk" },
    { value: 3, label: "Industrial", icon: "precision_manufacturing", desc: "Polyester, nylon, denim" },
    { value: 4, label: "Advanced", icon: "rocket_launch", desc: "Gore-Tex, carbon fiber" }
];

const CLIMATE_TYPES = [
    { value: "TROPICAL", label: "Tropical", icon: "wb_sunny", desc: "Hot, humid - minimal coverage" },
    { value: "ARID", label: "Arid", icon: "terrain", desc: "Hot, dry - full coverage, light fabrics" },
    { value: "TEMPERATE", label: "Temperate", icon: "park", desc: "Moderate - versatile layering" },
    { value: "COLD", label: "Cold", icon: "ac_unit", desc: "Cold - heavy insulation" },
    { value: "ARCTIC", label: "Arctic", icon: "severe_cold", desc: "Extreme - maximum insulation" }
];

const OUTFIT_PRESETS = [
    { value: "survival", label: "Survival", icon: "camping" },
    { value: "casual", label: "Casual", icon: "weekend" },
    { value: "formal", label: "Formal", icon: "checkroom" },
    { value: "combat", label: "Combat", icon: "shield" },
    { value: "travel", label: "Travel", icon: "hiking" }
];

const BODY_LOCATIONS = [
    { slot: "head", label: "Head", icon: "face" },
    { slot: "torso", label: "Torso", icon: "accessibility" },
    { slot: "legs", label: "Legs", icon: "straighten" },
    { slot: "feet", label: "Feet", icon: "do_not_step" },
    { slot: "hands", label: "Hands", icon: "back_hand" },
    { slot: "accessories", label: "Accessories", icon: "diamond" }
];

const WEAR_LEVELS = [
    { value: "NONE", label: "Nude", icon: "person" },
    { value: "BASE", label: "Base", icon: "checkroom" },
    { value: "SUIT", label: "Suit", icon: "dry_cleaning" },
    { value: "OVER", label: "Over", icon: "layers" },
    { value: "OUTER", label: "Outer", icon: "ac_unit" }
];

// ── Outfit Builder State ─────────────────────────────────────────

let outfitBuilderState = {
    characterId: null,
    techTier: 2,
    climate: "TEMPERATE",
    preset: null,
    isLoading: false,
    currentApparel: null,
    mannequinImages: [],
    gender: "female",
    selectedWearLevel: "SUIT",
    mannequinBasePath: null
};

// ── Outfit Generation ────────────────────────────────────────────

async function generateOutfit(characterId, techTier, climate, style) {
    let am7client = getClient();
    let page = getPage();
    outfitBuilderState.isLoading = true;
    m.redraw();

    try {
        let body = { characterId: characterId };
        if (techTier !== undefined) body.techTier = techTier;
        if (climate) body.climate = climate;
        if (style) body.style = style;

        let resp = await m.request({
            method: 'POST',
            url: am7client.base() + "/game/outfit/generate",
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

async function generateMannequinImages(apparelId, hires, style, seed) {
    let am7client = getClient();
    let page = getPage();
    outfitBuilderState.isLoading = true;
    m.redraw();

    try {
        let resp = await m.request({
            method: 'POST',
            url: am7client.base() + "/game/outfit/mannequin",
            body: { apparelId, hires: hires || false, style: style || "fashion", seed: seed || 0 },
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

async function buildFromPieces(characterId, pieces, primaryColor, pattern) {
    let am7client = getClient();
    let page = getPage();
    outfitBuilderState.isLoading = true;
    m.redraw();

    try {
        let resp = await m.request({
            method: 'POST',
            url: am7client.base() + "/game/outfit/pieces",
            body: { characterId, pieces, primaryColor: primaryColor || null, pattern: pattern || "solid" },
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

function getMannequinBaseUrl(gender, size) {
    let am7client = getClient();
    let modelName = (gender === "male") ? "maleModel" : "femaleModel";
    return am7client.base() + "/olio/mannequin/" + modelName + "/" + (size || "512x768");
}

// ── MannequinViewer Component ────────────────────────────────────

const MannequinViewer = {
    view: function(vnode) {
        let am7client = getClient();
        let gender = vnode.attrs.gender || outfitBuilderState.gender;
        let wearLevel = vnode.attrs.wearLevel || outfitBuilderState.selectedWearLevel;
        let images = outfitBuilderState.mannequinImages || [];

        let levelImage = images.find(img => img.wearLevel && img.wearLevel.startsWith(wearLevel));
        let baseUrl = getMannequinBaseUrl(gender, "512x768");
        let overlayUrl = null;

        if (levelImage) {
            overlayUrl = am7client.base().replace("/rest", "") + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + levelImage.groupPath + "/" + levelImage.name + "/512x768";
        }

        return m("div", { class: "relative w-full max-w-sm mx-auto" }, [
            m("div", { class: "relative aspect-[2/3] bg-gray-100 dark:bg-gray-800 rounded-lg overflow-hidden" }, [
                m("img", {
                    src: baseUrl, alt: "Mannequin",
                    class: "absolute inset-0 w-full h-full object-contain",
                    onerror: function(e) { e.target.style.display = 'none'; }
                }),
                m("div", { class: "absolute inset-0 flex items-center justify-center text-gray-300 dark:text-gray-600" },
                    m("span", { class: "material-symbols-outlined", style: "font-size:96px" }, gender === "male" ? "man" : "woman")
                ),
                overlayUrl ? m("img", {
                    src: overlayUrl, alt: wearLevel + " outfit",
                    class: "absolute inset-0 w-full h-full object-contain"
                }) : null,
                outfitBuilderState.isLoading ? m("div", { class: "absolute inset-0 flex items-center justify-center bg-black/30" },
                    m("span", { class: "material-symbols-outlined animate-spin text-white text-3xl" }, "sync")
                ) : null
            ]),
            m("div", { class: "flex justify-center gap-1 mt-2" }, WEAR_LEVELS.map(function(level) {
                let isSelected = wearLevel === level.value;
                let hasImage = images.some(img => img.wearLevel && img.wearLevel.startsWith(level.value));
                return m("button", {
                    class: "flex flex-col items-center px-2 py-1 rounded text-xs " +
                        (isSelected ? "bg-blue-500 text-white" : hasImage ? "bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300" : "text-gray-400"),
                    onclick: function() { outfitBuilderState.selectedWearLevel = level.value; }
                }, [
                    m("span", { class: "material-symbols-outlined text-sm" }, level.icon),
                    level.label
                ]);
            })),
            m("div", { class: "flex justify-center gap-2 mt-2" }, [
                m("button", {
                    class: "p-1 rounded " + (gender === "female" ? "bg-pink-100 dark:bg-pink-900" : ""),
                    onclick: function() { outfitBuilderState.gender = "female"; }
                }, m("span", { class: "material-symbols-outlined" }, "woman")),
                m("button", {
                    class: "p-1 rounded " + (gender === "male" ? "bg-blue-100 dark:bg-blue-900" : ""),
                    onclick: function() { outfitBuilderState.gender = "male"; }
                }, m("span", { class: "material-symbols-outlined" }, "man"))
            ])
        ]);
    }
};

// ── OutfitBuilderPanel Component ─────────────────────────────────

const OutfitBuilderPanel = {
    oninit: function(vnode) {
        if (vnode.attrs.characterId) outfitBuilderState.characterId = vnode.attrs.characterId;
        if (vnode.attrs.gender) outfitBuilderState.gender = vnode.attrs.gender;
    },
    view: function(vnode) {
        let am7client = getClient();
        let characterId = vnode.attrs.characterId || outfitBuilderState.characterId;

        return m("div", { class: "p-4 space-y-4" }, [
            m("div", { class: "flex items-center gap-2 text-lg font-medium" }, [
                m("span", { class: "material-symbols-outlined" }, "checkroom"),
                "Outfit Builder"
            ]),

            outfitBuilderState.isLoading ? m("div", { class: "flex items-center gap-2 text-blue-500" }, [
                m("span", { class: "material-symbols-outlined animate-spin" }, "sync"),
                "Generating..."
            ]) : null,

            // Tech Tier
            m("div", [
                m("div", { class: "text-sm font-medium text-gray-600 dark:text-gray-400 mb-1" }, "Tech Level"),
                m("div", { class: "flex flex-wrap gap-1" }, TECH_TIERS.map(function(tier) {
                    let sel = outfitBuilderState.techTier === tier.value;
                    return m("button", {
                        class: "flex items-center gap-1 px-2 py-1 rounded text-xs border " +
                            (sel ? "border-blue-500 bg-blue-50 dark:bg-blue-900 text-blue-700 dark:text-blue-300" : "border-gray-200 dark:border-gray-700"),
                        title: tier.desc,
                        onclick: function() { outfitBuilderState.techTier = tier.value; }
                    }, [m("span", { class: "material-symbols-outlined text-sm" }, tier.icon), tier.label]);
                }))
            ]),

            // Climate
            m("div", [
                m("div", { class: "text-sm font-medium text-gray-600 dark:text-gray-400 mb-1" }, "Climate"),
                m("div", { class: "flex flex-wrap gap-1" }, CLIMATE_TYPES.map(function(climate) {
                    let sel = outfitBuilderState.climate === climate.value;
                    return m("button", {
                        class: "flex items-center gap-1 px-2 py-1 rounded text-xs border " +
                            (sel ? "border-green-500 bg-green-50 dark:bg-green-900 text-green-700 dark:text-green-300" : "border-gray-200 dark:border-gray-700"),
                        title: climate.desc,
                        onclick: function() { outfitBuilderState.climate = climate.value; }
                    }, [m("span", { class: "material-symbols-outlined text-sm" }, climate.icon), climate.label]);
                }))
            ]),

            // Presets
            m("div", [
                m("div", { class: "text-sm font-medium text-gray-600 dark:text-gray-400 mb-1" }, "Quick Presets"),
                m("div", { class: "flex flex-wrap gap-1" }, OUTFIT_PRESETS.map(function(preset) {
                    return m("button", {
                        class: "flex items-center gap-1 px-3 py-1.5 rounded text-xs bg-gray-100 dark:bg-gray-800 hover:bg-gray-200 dark:hover:bg-gray-700",
                        onclick: async function() {
                            await generateOutfit(characterId, outfitBuilderState.techTier, outfitBuilderState.climate, preset.value);
                            if (vnode.attrs.onGenerate) vnode.attrs.onGenerate(outfitBuilderState.currentApparel);
                        }
                    }, [m("span", { class: "material-symbols-outlined text-sm" }, preset.icon), preset.label]);
                }))
            ]),

            // Generate button
            m("button", {
                class: "w-full flex items-center justify-center gap-2 px-4 py-2 rounded bg-blue-500 text-white hover:bg-blue-600 disabled:opacity-50",
                disabled: !characterId || outfitBuilderState.isLoading,
                onclick: async function() {
                    await generateOutfit(characterId, outfitBuilderState.techTier, outfitBuilderState.climate);
                    if (vnode.attrs.onGenerate) vnode.attrs.onGenerate(outfitBuilderState.currentApparel);
                }
            }, [m("span", { class: "material-symbols-outlined" }, "auto_awesome"), "Generate Outfit"]),

            // Mannequin
            m("div", [
                m("div", { class: "text-sm font-medium text-gray-600 dark:text-gray-400 mb-1" }, "Mannequin Preview"),
                m(MannequinViewer, { gender: outfitBuilderState.gender, wearLevel: outfitBuilderState.selectedWearLevel })
            ]),

            // Current apparel preview
            outfitBuilderState.currentApparel ? m("div", { class: "border rounded p-3 dark:border-gray-700" }, [
                m("div", { class: "text-sm font-medium" }, outfitBuilderState.currentApparel.name || "Unnamed"),
                m("div", { class: "text-xs text-gray-500 mt-1" }, outfitBuilderState.currentApparel.description || ""),
                m("button", {
                    class: "mt-2 flex items-center gap-1 px-3 py-1 rounded text-xs bg-purple-500 text-white hover:bg-purple-600",
                    onclick: async function() {
                        await generateMannequinImages(outfitBuilderState.currentApparel.objectId);
                    }
                }, [m("span", { class: "material-symbols-outlined text-sm" }, "photo_camera"), "Generate Outfit Images"]),
                outfitBuilderState.mannequinImages.length > 0 ? m("div", { class: "flex flex-wrap gap-2 mt-2" },
                    outfitBuilderState.mannequinImages.map(function(img) {
                        let imgUrl = am7client.base().replace("/rest", "") + "/thumbnail/" +
                            am7client.dotPath(am7client.currentOrganization) + "/data.data" + img.groupPath + "/" + img.name + "/256x256";
                        let sel = img.wearLevel && img.wearLevel.startsWith(outfitBuilderState.selectedWearLevel);
                        return m("div", {
                            class: "cursor-pointer rounded overflow-hidden border-2 " + (sel ? "border-blue-500" : "border-transparent"),
                            onclick: function() {
                                if (img.wearLevel) outfitBuilderState.selectedWearLevel = img.wearLevel.split("/")[0];
                            }
                        }, [
                            m("img", { src: imgUrl, alt: img.wearLevel || "Level", class: "w-16 h-16 object-cover" }),
                            m("div", { class: "text-center text-xs" }, img.wearLevel ? img.wearLevel.split("/")[0] : "")
                        ]);
                    })
                ) : null
            ]) : null
        ]);
    }
};

// ── PieceEditorPanel Component ───────────────────────────────────

const PieceEditorPanel = {
    view: function(vnode) {
        let apparel = vnode.attrs.apparel;
        let wearables = apparel && apparel.wearables ? apparel.wearables : [];

        let byLocation = {};
        BODY_LOCATIONS.forEach(loc => { byLocation[loc.slot] = []; });
        wearables.forEach(w => {
            let locs = w.location || [];
            locs.forEach(l => {
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

        return m("div", { class: "p-4 space-y-3" }, [
            m("div", { class: "flex items-center gap-2 text-lg font-medium" }, [
                m("span", { class: "material-symbols-outlined" }, "edit"),
                "Piece Editor"
            ]),
            m("div", { class: "space-y-2" }, BODY_LOCATIONS.map(function(loc) {
                let pieces = byLocation[loc.slot] || [];
                return m("div", { class: "border rounded p-2 dark:border-gray-700" }, [
                    m("div", { class: "flex items-center gap-1 text-sm font-medium text-gray-600 dark:text-gray-400" }, [
                        m("span", { class: "material-symbols-outlined text-sm" }, loc.icon),
                        loc.label
                    ]),
                    m("div", { class: "mt-1" }, pieces.length > 0 ?
                        pieces.map(p => m("div", {
                            class: "flex items-center gap-2 text-xs py-0.5 " + (p.inuse ? "text-gray-800 dark:text-gray-200" : "text-gray-400 line-through")
                        }, [
                            m("span", p.name),
                            p.color ? m("span", {
                                class: "w-3 h-3 rounded-full inline-block",
                                style: "background-color: " + (p.color.hex || "#888")
                            }) : null
                        ])) :
                        m("div", { class: "text-xs text-gray-400 italic" }, "Empty")
                    )
                ]);
            }))
        ]);
    }
};

// ── Public API ────────────────────────────────────────────────────

const am7olio = {
    dressUp,
    dressDown,
    dressCharacter,
    dressApparel,
    setNarDescription,
    describeWearable,
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

export { am7olio };
export default am7olio;
