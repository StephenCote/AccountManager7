/**
 * CardGame Themes Service — Theme loading, configuration, outfit application, and theme editor
 * Extracted from the monolithic cardGame-v2.js into a standalone module.
 *
 * Dependencies:
 *   - CardGame.Constants (THEME_DEFAULTS, DEFAULT_THEME)
 *   - CardGame.Storage (deckStorage, upsertDataRecord, loadDataRecord, listDataRecords, DECK_BASE_PATH)
 *   - CardGame.Characters (str, refreshCharacterCard)  [loaded after this module]
 *
 * Exposes: window.CardGame.Themes
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};

    // ── State ──────────────────────────────────────────────────────────
    let activeTheme = CardGame.Constants.DEFAULT_THEME;
    let themeLoading = false;
    let colorCache = {};
    let applyingOutfits = false;

    // ── Theme Loading ──────────────────────────────────────────────────

    async function loadThemeConfig(themeId) {
        let id = themeId || "high-fantasy";
        try {
            let resp = await m.request({
                method: "GET",
                url: "/media/cardGame/" + id + ".json"
            });
            if (resp && resp.themeId) {
                // Merge theme defaults for backgroundPrompt/promptSuffix if not in JSON
                let td = CardGame.Constants.THEME_DEFAULTS[resp.themeId];
                if (td) {
                    if (!resp.artStyle) resp.artStyle = {};
                    if (!resp.artStyle.backgroundPrompt) resp.artStyle.backgroundPrompt = td.backgroundPrompt;
                    if (!resp.artStyle.promptSuffix) resp.artStyle.promptSuffix = td.promptSuffix;
                }
                activeTheme = resp;
                console.log("[CardGame v2] Theme loaded:", resp.themeId);
                return resp;
            }
        } catch (e) {
            console.warn("[CardGame v2] Theme config not found, using default. (" + id + ")", e);
        }
        // Build a theme from THEME_DEFAULTS if available, otherwise use DEFAULT_THEME
        let td = CardGame.Constants.THEME_DEFAULTS[id];
        if (td && id !== "high-fantasy") {
            activeTheme = {
                themeId: id,
                name: td.name,
                version: "1.0",
                artStyle: {
                    backgroundPrompt: td.backgroundPrompt,
                    promptSuffix: td.promptSuffix,
                    colorPalette: CardGame.Constants.DEFAULT_THEME.artStyle.colorPalette
                },
                balance: CardGame.Constants.DEFAULT_THEME.balance
            };
        } else {
            activeTheme = CardGame.Constants.DEFAULT_THEME;
        }
        return activeTheme;
    }

    // ── Color Lookup (for outfit system) ───────────────────────────────
    // Lookup colors from /Library/Colors, cache results

    async function lookupColor(colorName) {
        if (!colorName) return null;
        let key = colorName.charAt(0).toUpperCase() + colorName.slice(1).toLowerCase();
        if (colorCache[key]) return colorCache[key];
        let grp = await page.findObject("auth.group", "data", "/Library/Colors");
        if (!grp) {
            console.warn("[CardGame v2] /Library/Colors group not found");
            return null;
        }
        let q = am7view.viewQuery("data.color");
        q.field("groupId", grp.id);
        let qf = q.field("name", key);
        qf.comparator = "like";
        q.range(0, 1);
        let qr = await page.search(q);
        if (qr && qr.results && qr.results.length) {
            colorCache[key] = { id: qr.results[0].id };
            return colorCache[key];
        }
        console.warn("[CardGame v2] Color not found:", key);
        return null;
    }

    // ── Theme Outfit System ────────────────────────────────────────────
    // Applies theme-defined outfits to character cards in a deck

    async function applyThemeOutfits(deck, theme, skipSave) {
        if (!deck || !deck.cards || applyingOutfits) return;
        let thm = theme || activeTheme;
        if (!thm.outfits) {
            page.toast("warn", "Theme has no outfit definitions");
            return;
        }
        applyingOutfits = true;
        m.redraw();

        let charCards = deck.cards.filter(c => c.type === "character" && c.sourceId);
        if (!charCards.length) {
            page.toast("warn", "No character cards with source IDs");
            applyingOutfits = false;
            m.redraw();
            return;
        }

        let categories = ["scrappy", "functional", "fancy"];
        let processed = 0;

        // Find Apparel and Wearables in player-relative paths
        let apparelDir = await page.findObject("auth.group", "data", "~/Apparel");
        if (!apparelDir) {
            page.toast("error", "Apparel directory not found at ~/Apparel");
            applyingOutfits = false;
            m.redraw();
            return;
        }
        let wearableDir = await page.findObject("auth.group", "data", "~/Wearables");
        if (!wearableDir) {
            page.toast("error", "Wearables directory not found at ~/Wearables");
            applyingOutfits = false;
            m.redraw();
            return;
        }

        for (let card of charCards) {
            try {
                page.toast("info", "Outfitting " + card.name + "...", -1);

                // Load full character with all nested store/apparel/wearable data
                am7client.clearCache("olio.charPerson");
                am7client.clearCache("olio.apparel");
                am7client.clearCache("olio.wearable");
                am7client.clearCache("olio.store");
                let char = await am7client.getFull("olio.charPerson", card.sourceId);
                if (!char) {
                    console.warn("[CardGame v2] Could not fetch char:", card.sourceId);
                    continue;
                }

                // Determine gender for outfit selection
                let gender = CardGame.Characters.str(char.gender).toLowerCase();
                if (gender !== "male" && gender !== "female") gender = "male";
                let genderOutfits = thm.outfits[gender];
                if (!genderOutfits) {
                    console.warn("[CardGame v2] No outfits for gender:", gender);
                    continue;
                }

                // Pick random category
                let cat = categories[Math.floor(Math.random() * categories.length)];
                let outfitDef = genderOutfits[cat];
                if (!outfitDef) {
                    console.warn("[CardGame v2] No outfit for category:", cat);
                    continue;
                }

                let storeObjId = char.store ? char.store.objectId : null;
                if (!storeObjId) {
                    console.warn("[CardGame v2] No store for char:", card.name);
                    continue;
                }

                // 2. Deactivate ALL current apparel from the full store data
                let existingApparel = (char.store.apparel || []);
                for (let ap of existingApparel) {
                    // Deactivate wearables first
                    if (ap.wearables && ap.wearables.length) {
                        let wPatches = ap.wearables.filter(w => w.inuse).map(w =>
                            page.patchObject({ schema: "olio.wearable", id: w.id, inuse: false })
                        );
                        if (wPatches.length) await Promise.all(wPatches);
                    }
                    // Deactivate apparel
                    if (ap.inuse) {
                        await page.patchObject({ schema: "olio.apparel", id: ap.id, inuse: false });
                    }
                    // Remove old apparel from store membership so it won't interfere
                    await new Promise(res => {
                        am7client.member("olio.store", storeObjId, "apparel", "olio.apparel", ap.objectId, false, res);
                    });
                }

                // 2b. Clear existing items from store
                let storeItemList = await new Promise(res => {
                    am7client.members("olio.store", storeObjId, "olio.item", 0, 50, res);
                });
                if (storeItemList && storeItemList.length) {
                    for (let itemRef of storeItemList) {
                        await new Promise(res => {
                            am7client.member("olio.store", storeObjId, "items", "olio.item", itemRef.objectId, false, res);
                        });
                    }
                }

                // 2c. Clear existing inventory entries from store
                let storeInvList = await new Promise(res => {
                    am7client.members("olio.store", storeObjId, "olio.inventoryEntry", 0, 50, res);
                });
                if (storeInvList && storeInvList.length) {
                    for (let invRef of storeInvList) {
                        await new Promise(res => {
                            am7client.member("olio.store", storeObjId, "inventory", "olio.inventoryEntry", invRef.objectId, false, res);
                        });
                    }
                }

                // 3. Create apparel in the world-level Apparel directory
                let apparel = {
                    schema: "olio.apparel",
                    groupId: apparelDir.id,
                    name: outfitDef.name,
                    type: outfitDef.type || "casual",
                    gender: outfitDef.gender || gender
                };
                let createdApparel = await page.createObject(apparel);
                if (!createdApparel) {
                    console.warn("[CardGame v2] Failed to create apparel for", card.name);
                    continue;
                }

                // 4. Create wearables in the world-level Wearables directory
                let createdWearables = [];
                for (let wDef of outfitDef.wearables) {
                    let colorRef = await lookupColor(wDef.color);
                    let wearable = {
                        schema: "olio.wearable",
                        groupId: wearableDir.id,
                        name: wDef.name,
                        location: wDef.location,
                        fabric: wDef.fabric,
                        gender: wDef.gender || gender,
                        level: wDef.level || "SUIT"
                    };
                    if (colorRef) wearable.color = colorRef;
                    let created = await page.createObject(wearable);
                    if (created) createdWearables.push(created);
                }

                // 5. Link wearables to apparel via membership
                for (let cw of createdWearables) {
                    await page.member("olio.apparel", createdApparel.objectId, "olio.wearable", cw.objectId, true);
                }

                // 6. Link apparel to store via membership (field name: "apparel")
                await new Promise(res => {
                    am7client.member("olio.store", storeObjId, "apparel", "olio.apparel", createdApparel.objectId, true, res);
                });

                // 7. Patch inuse = true on all wearables and the apparel
                let inusePatches = createdWearables.map(cw =>
                    page.patchObject({ schema: "olio.wearable", id: cw.id, inuse: true })
                );
                inusePatches.push(
                    page.patchObject({ schema: "olio.apparel", id: createdApparel.id, inuse: true })
                );
                await Promise.all(inusePatches);

                // 8. Clear caches so subsequent reads reflect new state
                am7client.clearCache("olio.wearable");
                am7client.clearCache("olio.apparel");
                am7client.clearCache("olio.store");
                am7client.clearCache("olio.charPerson");

                // 9. Update narrative to reflect new outfit for imaging
                await m.request({ method: 'GET', url: am7client.base() + "/olio/olio.charPerson/" + card.sourceId + "/narrate", withCredentials: true });

                // 10. Refresh character card data
                await CardGame.Characters.refreshCharacterCard(card);
                processed++;
                console.log("[CardGame v2] Applied " + cat + " outfit to " + card.name);

            } catch (e) {
                console.error("[CardGame v2] Failed to outfit " + card.name, e);
                page.clearToast();  // Clear indefinite progress toast
                page.toast("error", "Failed to outfit " + card.name);
            }
        }

        applyingOutfits = false;
        page.clearToast();  // Clear indefinite progress toast
        if (processed > 0) {
            if (!skipSave) {
                let safeName = (deck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                await CardGame.Storage.deckStorage.save(safeName, deck);
            }
            page.toast("success", "Applied outfits to " + processed + " character(s)");
        }
        m.redraw();
    }

    // ── Theme Storage ──────────────────────────────────────────────────
    // Persists custom themes at ~/CardGame/themes/

    const THEME_STORAGE_PATH = CardGame.Storage.DECK_BASE_PATH + "/themes";

    const themeStorage = {
        async save(themeId, themeData) {
            try {
                return await CardGame.Storage.upsertDataRecord(THEME_STORAGE_PATH, themeId + ".json", themeData);
            } catch (e) {
                console.error("[CardGame v2] Failed to save theme:", themeId, e);
                return null;
            }
        },
        async load(themeId) {
            try {
                return await CardGame.Storage.loadDataRecord(THEME_STORAGE_PATH, themeId + ".json", false);
            } catch (e) {
                return null;
            }
        },
        async list() {
            try {
                let records = await CardGame.Storage.listDataRecords(THEME_STORAGE_PATH);
                return records.filter(r => r.name?.endsWith(".json")).map(r => r.name.replace(".json", ""));
            } catch (e) {
                return [];
            }
        },
        async remove(themeId) {
            try {
                let grp = await page.findObject("auth.group", "DATA", THEME_STORAGE_PATH);
                if (!grp) return;
                let q = am7view.viewQuery("data.data");
                q.field("groupId", grp.id);
                q.field("name", themeId + ".json");
                let qr = await page.search(q);
                if (qr?.results?.length) {
                    await page.deleteObject("data.data", qr.results[0].objectId);
                }
            } catch (e) {
                console.warn("[CardGame v2] Failed to remove theme:", themeId, e);
            }
        }
    };

    // ── Theme List Loading ─────────────────────────────────────────────

    let themeEditorState = {
        themes: [],        // [{ name, themeId, isCustom, isBuiltin }]
        loading: false,
        editingTheme: null, // Full theme JSON being edited
        editingName: "",
        jsonText: "",
        jsonError: null,
        dirty: false
    };

    async function loadThemeList() {
        themeEditorState.loading = true;
        m.redraw();
        let builtins = ["high-fantasy", "dark-medieval", "sci-fi", "post-apocalypse", "steampunk"];
        let custom = await themeStorage.list();
        themeEditorState.themes = [
            ...builtins.map(id => ({ themeId: id, name: id.replace(/-/g, " ").replace(/\b\w/g, c => c.toUpperCase()), isBuiltin: true, isCustom: false })),
            ...custom.filter(id => !builtins.includes(id)).map(id => ({ themeId: id, name: id.replace(/-/g, " ").replace(/\b\w/g, c => c.toUpperCase()), isBuiltin: false, isCustom: true }))
        ];
        themeEditorState.loading = false;
        m.redraw();
    }

    async function editTheme(themeId, isBuiltin) {
        let themeData;
        if (isBuiltin) {
            // Load from active theme config and card pool
            await loadThemeConfig(themeId);
            themeData = {
                themeId: themeId + "-custom",
                name: activeTheme.name + " (Custom)",
                description: activeTheme.description || "",
                sdSuffix: activeTheme.sdSuffix || "",
                narration: activeTheme.narration || { announcerProfile: "arena-announcer" },
                cardPool: activeTheme.cardPool || []
            };
        } else {
            themeData = await themeStorage.load(themeId);
            if (!themeData) themeData = { themeId, name: themeId, cardPool: [] };
        }
        themeEditorState.editingTheme = themeData;
        themeEditorState.editingName = themeData.name || themeId;
        themeEditorState.jsonText = JSON.stringify(themeData, null, 2);
        themeEditorState.jsonError = null;
        themeEditorState.dirty = false;
        m.redraw();
    }

    function createNewTheme() {
        let template = {
            themeId: "custom-" + Date.now(),
            name: "My Custom Theme",
            description: "A custom card game theme",
            sdSuffix: "fantasy art, detailed illustration",
            narration: { announcerProfile: "arena-announcer" },
            cardPool: [
                { type: "action", name: "Attack", subtype: "melee", effect: "Standard melee attack", rarity: "COMMON" },
                { type: "action", name: "Rest", effect: "Restore 2 HP, 3 Energy, 2 Morale", rarity: "COMMON" },
                { type: "talk", name: "Persuade", speechType: "Diplomacy", effect: "CHA vs CHA for morale damage", rarity: "COMMON" },
                { type: "skill", name: "Custom Skill", modifier: "+2 to Attack rolls", rarity: "UNCOMMON" },
                { type: "magic", name: "Custom Spell", effect: "Deal 10 damage", energyCost: 5, requires: { MAG: 8 }, rarity: "RARE" },
                { type: "item", subtype: "weapon", name: "Custom Weapon", atk: 3, range: "Melee", damageType: "Slashing", rarity: "COMMON" },
                { type: "item", subtype: "armor", name: "Custom Armor", def: 2, rarity: "COMMON" },
                { type: "item", subtype: "consumable", name: "Health Potion", effect: "Restore 15 HP", rarity: "UNCOMMON" },
                { type: "apparel", name: "Custom Garb", def: 1, slot: "Body", rarity: "COMMON" },
                { type: "encounter", name: "Custom Encounter", atk: 3, def: 2, hp: 15, behavior: "Attacks randomly", loot: "UNCOMMON", rarity: "UNCOMMON" }
            ]
        };
        themeEditorState.editingTheme = template;
        themeEditorState.editingName = template.name;
        themeEditorState.jsonText = JSON.stringify(template, null, 2);
        themeEditorState.jsonError = null;
        themeEditorState.dirty = true;
        m.redraw();
    }

    // ── Theme Editor UI Component ──────────────────────────────────────

    function ThemeEditorUI() {
        return {
            oninit() { loadThemeList(); },
            view() {
                // Editing view
                if (themeEditorState.editingTheme) {
                    let theme = themeEditorState.editingTheme;
                    let cardCount = 0;
                    try { cardCount = JSON.parse(themeEditorState.jsonText).cardPool?.length || 0; } catch (e) { /* parse error handled below */ }

                    return m("div", { class: "cg2-theme-editor" }, [
                        m("div", { class: "cg2-toolbar" }, [
                            m("button", { class: "cg2-btn", onclick() { themeEditorState.editingTheme = null; m.redraw(); } }, "\u2190 Back"),
                            m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, "Theme Editor"),
                            m("span", { style: { fontSize: "11px", color: "#888", marginLeft: "8px" } }, cardCount + " cards in pool"),
                            themeEditorState.dirty ? m("span", { style: { fontSize: "11px", color: "#E65100", marginLeft: "8px" } }, "(unsaved changes)") : null,
                            m("button", {
                                class: "cg2-btn cg2-btn-primary", style: { marginLeft: "auto" },
                                disabled: !!themeEditorState.jsonError,
                                async onclick() {
                                    try {
                                        let parsed = JSON.parse(themeEditorState.jsonText);
                                        parsed.name = themeEditorState.editingName;
                                        await themeStorage.save(parsed.themeId, parsed);
                                        themeEditorState.editingTheme = parsed;
                                        themeEditorState.dirty = false;
                                        page.toast("success", "Theme saved: " + parsed.name);
                                        loadThemeList();
                                    } catch (e) {
                                        page.toast("error", "Save failed: " + e.message);
                                    }
                                }
                            }, "Save Theme")
                        ]),

                        // Theme name input
                        m("div", { style: { padding: "8px 16px", display: "flex", gap: "12px", alignItems: "center" } }, [
                            m("label", { style: { fontWeight: 600, fontSize: "13px" } }, "Name:"),
                            m("input", {
                                class: "cg2-input", style: { flex: 1 },
                                value: themeEditorState.editingName,
                                oninput(e) { themeEditorState.editingName = e.target.value; themeEditorState.dirty = true; }
                            }),
                            m("label", { style: { fontWeight: 600, fontSize: "13px" } }, "ID:"),
                            m("span", { style: { fontSize: "12px", color: "#888" } }, theme.themeId)
                        ]),

                        // JSON error indicator
                        themeEditorState.jsonError ? m("div", { style: { padding: "4px 16px", color: "#c62828", fontSize: "12px" } }, themeEditorState.jsonError) : null,

                        // JSON editor
                        m("textarea", {
                            class: "cg2-json-editor",
                            value: themeEditorState.jsonText,
                            oninput(e) {
                                themeEditorState.jsonText = e.target.value;
                                themeEditorState.dirty = true;
                                try {
                                    JSON.parse(e.target.value);
                                    themeEditorState.jsonError = null;
                                } catch (err) {
                                    themeEditorState.jsonError = "JSON Error: " + err.message;
                                }
                            },
                            spellcheck: false
                        }),

                        // Card Effect Builder (interactive helper)
                        m("div", { class: "cg2-effect-builder" }, [
                            m("h3", { style: { margin: "0 0 8px", fontSize: "14px" } }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "4px" } }, "build"),
                                "Card Effect Builder"
                            ]),
                            m("p", { style: { fontSize: "11px", color: "#666", margin: "0 0 8px" } },
                                "Click effect tokens to build a parseable effect string. Copy into your card's effect field."),
                            m("div", { class: "cg2-effect-tokens" }, [
                                m("span", { class: "cg2-effect-section-label" }, "DAMAGE:"),
                                ...[5, 10, 15, 20, 25].map(n =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-damage", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + "Deal " + n + " damage. "; m.redraw(); } }, "Deal " + n)
                                ),
                                m("span", { class: "cg2-effect-section-label" }, "DRAIN:"),
                                ...[5, 10, 15].map(n =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-damage", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + "Drain " + n + " health. "; m.redraw(); } }, "Drain " + n)
                                ),
                                m("span", { class: "cg2-effect-section-label" }, "HEALING:"),
                                ...[5, 10, 15, 20].map(n =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-heal", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + "Restore " + n + " HP. "; m.redraw(); } }, "Heal " + n)
                                ),
                                ...[3, 5, 8].map(n =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-heal", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + "Restore " + n + " Energy. "; m.redraw(); } }, "Energy " + n)
                                ),
                                ...[3, 5, 10].map(n =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-heal", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + "Restore " + n + " Morale. "; m.redraw(); } }, "Morale " + n)
                                ),
                                m("span", { class: "cg2-effect-section-label" }, "STATUS (enemy):"),
                                ...["Stun", "Poison", "Burn", "Bleed", "Weaken"].map(s =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-debuff", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + s + " target. "; m.redraw(); } }, s)
                                ),
                                m("span", { class: "cg2-effect-section-label" }, "BUFF (self):"),
                                ...["Shield", "Enrage", "Fortify", "Inspire", "Regenerate"].map(s =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-buff", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + s + " self. "; m.redraw(); } }, s)
                                ),
                                m("span", { class: "cg2-effect-section-label" }, "UTILITY:"),
                                ...[1, 2, 3].map(n =>
                                    m("button", { class: "cg2-effect-token cg2-effect-token-util", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + "Draw " + n + " cards. "; m.redraw(); } }, "Draw " + n)
                                ),
                                m("button", { class: "cg2-effect-token cg2-effect-token-util", onclick() { themeEditorState.effectPreview = (themeEditorState.effectPreview || "") + "Cure all poisons. "; m.redraw(); } }, "Cure"),
                            ]),
                            // Preview with validation
                            themeEditorState.effectPreview ? m("div", { class: "cg2-effect-preview" }, [
                                m("div", { style: { display: "flex", alignItems: "center", gap: "8px", marginBottom: "4px" } }, [
                                    m("strong", { style: { fontSize: "11px" } }, "Preview:"),
                                    m("button", { class: "cg2-btn cg2-btn-sm", onclick() {
                                        if (navigator.clipboard) navigator.clipboard.writeText(themeEditorState.effectPreview.trim());
                                        else page.toast("info", themeEditorState.effectPreview.trim());
                                    } }, "Copy"),
                                    m("button", { class: "cg2-btn cg2-btn-sm", onclick() { themeEditorState.effectPreview = ""; m.redraw(); } }, "Clear")
                                ]),
                                m("code", { style: { fontSize: "12px", display: "block", padding: "6px 8px", background: "rgba(0,0,0,0.05)", borderRadius: "4px" } }, themeEditorState.effectPreview.trim()),
                                m("div", { style: { fontSize: "10px", marginTop: "4px" } }, (() => {
                                    let parsed = CardGame.Engine.parseEffect(themeEditorState.effectPreview);
                                    let parts = [];
                                    if (parsed.damage) parts.push("DMG:" + parsed.damage);
                                    if (parsed.healHp) parts.push("HEAL:" + parsed.healHp + " HP");
                                    if (parsed.restoreEnergy) parts.push("+" + parsed.restoreEnergy + " Energy");
                                    if (parsed.restoreMorale) parts.push("+" + parsed.restoreMorale + " Morale");
                                    if (parsed.draw) parts.push("DRAW:" + parsed.draw);
                                    if (parsed.cure) parts.push("CURE");
                                    if (parsed.statusEffects) parsed.statusEffects.forEach(s => parts.push(s.id.toUpperCase() + "\u2192" + s.target));
                                    return parts.length > 0
                                        ? m("span", { style: { color: "#2E7D32" } }, "\u2713 Parsed: " + parts.join(", "))
                                        : m("span", { style: { color: "#c62828" } }, "\u2717 No mechanical effects detected");
                                })())
                            ]) : null,
                            // Skill modifier builder
                            m("div", { style: { marginTop: "8px", borderTop: "1px solid rgba(0,0,0,0.08)", paddingTop: "8px" } }, [
                                m("strong", { style: { fontSize: "11px" } }, "Skill Modifier Templates:"),
                                m("div", { class: "cg2-effect-tokens", style: { marginTop: "4px" } }, [
                                    ...["Attack", "Defense", "Talk/Social", "Flee/Initiative", "Investigate", "Craft", "Magic/Spell"].map(act =>
                                        m("button", { class: "cg2-effect-token cg2-effect-token-skill", onclick() {
                                            themeEditorState.effectPreview = "+2 to " + act + " rolls";
                                            m.redraw();
                                        } }, "+2 " + act)
                                    )
                                ])
                            ])
                        ]),

                        // Card Pool Quick Reference
                        m("div", { class: "cg2-theme-reference" }, [
                            m("h3", "Card Pool Reference"),
                            m("p", { style: { fontSize: "11px", color: "#666" } }, "Each card in the cardPool array needs these fields:"),
                            m("pre", { style: { fontSize: "10px", background: "rgba(0,0,0,0.05)", padding: "8px", borderRadius: "6px", overflow: "auto", maxHeight: "200px" } },
`SKILL:   { type:"skill", name, modifier:"+N to Attack/Defense/Talk/Flee/Investigate/Craft/Magic", rarity }
  \u2192 modifier MUST contain +N and a keyword: attack, combat, melee, defense, parry, talk, social,
    flee, escape, initiative, investigate, search, craft, magic, spell, etc.

MAGIC:   { type:"magic", name, effect, energyCost, requires:{MAG:N}, rarity }
  \u2192 PARSEABLE EFFECTS (combine any):
    "Deal N damage" \u2014 direct damage to target
    "Drain N"       \u2014 damage target + heal self same amount
    "Heal N" / "Restore N HP"   \u2014 heal owner
    "Restore N Energy/Morale"   \u2014 restore resources
    "Draw N"        \u2014 draw N extra cards
    "Stun/Poison/Burn/Bleed/Weaken target" \u2014 debuff target
    "Shield/Enrage/Fortify/Inspire/Regenerate self" \u2014 buff self
    "Cure/Cleanse"  \u2014 remove negative statuses

ITEM:    Weapon:     { type:"item", subtype:"weapon", name, atk:N, range, damageType, rarity }
         Armor:      { type:"item", subtype:"armor", name, def:N, rarity }
         Consumable: { type:"item", subtype:"consumable", name, effect:(same as magic), rarity }
APPAREL: { type:"apparel", name, def:N, hpBonus:N, slot:"Body|Head|Feet", rarity }
ACTION:  { type:"action", name:"Attack|Rest|Guard|Flee", effect, rarity }
TALK:    { type:"talk", name, speechType, effect, rarity }
ENCOUNTER: { type:"encounter", name, atk:N, def:N, hp:N, behavior, loot:"RARITY", rarity }

RARITY:  "COMMON" | "UNCOMMON" | "RARE" | "EPIC" | "LEGENDARY"

STATUS EFFECTS: stunned(1t), poisoned(3t, -2HP/t), burning(2t, -3HP/t),
  bleeding(4t, -1HP/t), shielded(+3 DEF until hit), weakened(2t, -2 rolls),
  enraged(2t, +3ATK -2DEF), fortified(2t, +2DEF +1ATK),
  inspired(2t, +2 rolls), regenerating(3t, +2HP/t)`)
                        ])
                    ]);
                }

                // Theme list view
                return m("div", { class: "cg2-theme-editor" }, [
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", { class: "cg2-btn", onclick() { CardGame.Themes.navigateBack(); } }, "\u2190 Back"),
                        m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, "Theme Editor"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary", style: { marginLeft: "auto" },
                            onclick() { createNewTheme(); }
                        }, [m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "add"), "New Theme"]),
                        themeEditorState.loading ? m("span", { class: "cg2-loading" }, "Loading...") : null
                    ]),
                    m("div", { class: "cg2-theme-list" },
                        themeEditorState.themes.map(t =>
                            m("div", { class: "cg2-theme-list-item" }, [
                                m("div", { class: "cg2-theme-list-name" }, [
                                    t.name,
                                    t.isBuiltin ? m("span", { class: "cg2-theme-badge-builtin" }, "Built-in") : null,
                                    t.isCustom ? m("span", { class: "cg2-theme-badge-custom" }, "Custom") : null
                                ]),
                                m("div", { class: "cg2-theme-list-actions" }, [
                                    m("button", { class: "cg2-btn", onclick() { editTheme(t.themeId, t.isBuiltin); } },
                                        t.isBuiltin ? "Customize" : "Edit"),
                                    t.isCustom ? m("button", {
                                        class: "cg2-btn cg2-btn-danger",
                                        onclick() {
                                            page.components.dialog.confirm("Delete theme '" + t.name + "'?", async function(ok) {
                                                if (ok) {
                                                    await themeStorage.remove(t.themeId);
                                                    loadThemeList();
                                                }
                                            });
                                        }
                                    }, "Delete") : null
                                ])
                            ])
                        ),
                        themeEditorState.themes.length === 0 ? m("div", { class: "cg2-empty-state" }, "No themes found.") : null
                    )
                ]);
            }
        };
    }

    // ── Expose on CardGame namespace ─────────────────────────────────
    window.CardGame.Themes = {
        state: {
            get activeTheme() { return activeTheme; },
            set activeTheme(v) { activeTheme = v; },
            get themeLoading() { return themeLoading; },
            set themeLoading(v) { themeLoading = v; },
            get colorCache() { return colorCache; },
            get applyingOutfits() { return applyingOutfits; },
            set applyingOutfits(v) { applyingOutfits = v; }
        },
        loadThemeConfig,
        lookupColor,
        applyThemeOutfits,
        getActiveTheme: () => activeTheme,
        // Theme editor
        themeStorage,
        themeEditorState,
        loadThemeList,
        editTheme,
        createNewTheme,
        ThemeEditorUI,
        // Navigation callback — set by CardGameApp to wire screen changes
        navigateBack: function() { console.warn("[CardGame] navigateBack not wired"); }
    };

    console.log('[CardGame] Themes service loaded');

}());
