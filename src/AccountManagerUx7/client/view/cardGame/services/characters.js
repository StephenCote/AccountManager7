/**
 * CardGame Characters Service — Character loading, stat mapping, card assembly,
 * generation, and persistence.
 *
 * Extracted from the monolithic cardGame-v2.js (lines ~1086-1721).
 * Handles all character-related operations for the card game deck builder.
 *
 * Exposes: window.CardGame.Characters
 *
 * Dependencies:
 *   - window.CardGame.Constants (DEFAULT_THEME)
 *   - window.CardGame.ctx (shared mutable context: activeTheme, deckNameInput)
 *   - AM7 globals: am7view, am7client, am7model, page, g_application_path, m
 */
(function() {

    window.CardGame = window.CardGame || {};

    // ── Mutable state (accessed by UI) ───────────────────────────────
    let availableCharacters = [];
    let charsLoading = false;
    let selectedChars = [];
    let characterTemplates = null;
    let generatingCharacters = false;

    // ── Helper: resolve activeTheme from shared context ──────────────
    function getActiveTheme() {
        return (CardGame.ctx && CardGame.ctx.activeTheme) || CardGame.Constants.DEFAULT_THEME;
    }

    // ── Helper: resolve deckNameInput from shared context ────────────
    function getDeckNameInput() {
        return (CardGame.ctx && CardGame.ctx.deckNameInput) || "";
    }

    // ── Stat Utilities ───────────────────────────────────────────────

    function statVal(v, fallback) { return (v != null && v !== undefined) ? v : fallback; }

    function mapStats(statistics) {
        if (!statistics) return { STR: 8, AGI: 8, END: 8, INT: 8, MAG: 8, CHA: 8 };
        // If statistics is only partially loaded (objectId ref without values), return defaults
        if (statistics.objectId && statistics.physicalStrength == null && statistics.agility == null) {
            return { STR: 8, AGI: 8, END: 8, INT: 8, MAG: 8, CHA: 8 };
        }
        return {
            STR: statVal(statistics.physicalStrength, 8),
            AGI: statVal(statistics.agility, 8),
            END: statVal(statistics.physicalEndurance, 8),
            INT: statVal(statistics.intelligence, 8),
            MAG: statVal(statistics.magic, 8),
            CHA: statVal(statistics.charisma, 8)
        };
    }

    // Resolve partially-loaded statistics (secondary query when only objectId is present)
    async function resolveStatistics(charObj) {
        if (!charObj || !charObj.statistics) return;
        let s = charObj.statistics;
        if (s.objectId && s.physicalStrength == null && s.agility == null) {
            try {
                let sq = am7view.viewQuery("olio.statistics");
                sq.field("objectId", s.objectId);
                let sqr = await page.search(sq);
                if (sqr && sqr.results && sqr.results.length > 0) {
                    charObj.statistics = sqr.results[0];
                    console.log("[CardGame v2] Resolved statistics for " + (charObj.name || charObj.objectId));
                }
            } catch (e) {
                console.warn("[CardGame v2] Failed to resolve statistics", e);
            }
        }
    }

    function clampStat(v) { return Math.max(1, Math.min(20, Math.round(v))); }

    // AM7 fields can be enums (objects), lists (arrays), or strings; safely coerce to string
    function str(v) {
        if (Array.isArray(v)) return v[0] || "";
        return typeof v === "string" ? v : (v && v.name ? v.name : String(v || ""));
    }

    // ── Portrait URL Helper ──────────────────────────────────────────

    function getPortraitUrl(char, size) {
        if (!char || !char.profile || !char.profile.portrait) return null;
        let pp = char.profile.portrait;
        if (!pp.groupPath || !pp.name) return null;
        return g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
            "/data.data" + pp.groupPath + "/" + pp.name + "/" + (size || "256x256");
    }

    // ── Character ID Helper ──────────────────────────────────────────

    function getCharId(char) {
        return char.objectId || char._tempId || char.id || (char.entity && char.entity.objectId);
    }

    // ── Fetch fresh charPerson by objectId (with profile + statistics) ──

    async function fetchCharPerson(objectId) {
        let q = am7view.viewQuery("olio.charPerson");
        q.field("objectId", objectId);
        q.range(0, 1);
        q.entity.request.push("profile", "store", "statistics");
        let qr = await page.search(q);
        if (qr && qr.results && qr.results.length) {
            am7model.updateListModel(qr.results);
            let char = qr.results[0];
            await resolveStatistics(char);
            return char;
        }
        return null;
    }

    // ── Refresh a character card from its source object ──────────────

    async function refreshCharacterCard(card) {
        let fresh;
        if (card._sourceChar) {
            // Use in-memory source for temp/generated characters
            fresh = card._sourceChar;
        } else if (card.sourceId) {
            am7client.clearCache("olio.charPerson");
            am7client.clearCache("data.data");
            fresh = await fetchCharPerson(card.sourceId);
        }
        if (!fresh) return false;
        let stats = mapStats(fresh.statistics);
        Object.keys(stats).forEach(k => { stats[k] = clampStat(stats[k]); });
        card.name = fresh.name || card.name;
        card.gender = str(fresh.gender) || card.gender || null;
        card.race = (str(fresh.race) || "HUMAN").toUpperCase();
        card.alignment = (str(fresh.alignment) || "NEUTRAL").replace(/_/g, " ");
        card.age = fresh.age || card.age;
        card.stats = stats;
        card.needs = { hp: 20, energy: stats.MAG, morale: 20 };
        // Preserve custom/generated portrait URLs (identified by artObjectId)
        let hasCustomPortrait = !!card.artObjectId;
        if (!hasCustomPortrait) {
            card.portraitUrl = getPortraitUrl(fresh, "256x256");
            if (card.portraitUrl) card.portraitUrl += "?t=" + Date.now();
        }
        return true;
    }

    // ── Character Card Builder (single char -> card data) ────────────

    function assembleCharacterCard(char) {
        let stats = mapStats(char.statistics);
        Object.keys(stats).forEach(k => { stats[k] = clampStat(stats[k]); });
        return {
            type: "character", name: char.name || "Unknown",
            gender: str(char.gender) || null,
            race: (str(char.race) || "HUMAN").toUpperCase(),
            alignment: (str(char.alignment) || "NEUTRAL").replace(/_/g, " "),
            age: char.age || null,
            level: 1,
            stats,
            needs: { hp: 20, energy: stats.MAG, morale: 20 },
            equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
            activeSkills: [null, null, null, null],
            portraitUrl: getPortraitUrl(char, "256x256"),
            sourceId: char.objectId || null,  // Only real objectId, not _tempId
            _sourceChar: char._tempId ? char : null  // Keep full object for temp chars
        };
    }

    // ── Per-Character Equipment Cards ─────────────────────────────────

    function assembleCharEquipment(char) {
        let stats = mapStats(char.statistics);
        Object.keys(stats).forEach(k => { stats[k] = clampStat(stats[k]); });
        let activeTheme = getActiveTheme();
        let cn = (activeTheme && activeTheme.cardNames) || {};

        let apparelCards = [];
        if (char.store && char.store.apparel) {
            let active = char.store.apparel.filter(a => a.wearables && a.wearables.some(w => w.inuse));
            active.slice(0, 3).forEach(ap => {
                let slots = [];
                (ap.wearables || []).filter(w => w.inuse).forEach(w => {
                    (w.location || []).forEach(loc => { if (!slots.includes(loc)) slots.push(loc); });
                });
                let slot = "Body";
                if (slots.includes("head")) slot = "Head";
                else if (slots.includes("foot") || slots.includes("feet")) slot = "Feet";
                else if (slots.includes("back")) slot = "Back";
                else if (slots.includes("finger")) slot = "Ring";
                apparelCards.push({
                    type: "apparel",
                    name: ap.name || ("Apparel " + (apparelCards.length + 1)),
                    slot, rarity: "COMMON", def: 1 + Math.floor(Math.random() * 3),
                    hpBonus: 0, durability: 5, special: null
                });
            });
        }
        if (apparelCards.length === 0) {
            apparelCards.push(
                { type: "apparel", name: cn.bodyArmor || "Leather Armor", slot: "Body", rarity: "COMMON", def: 2, hpBonus: 0, durability: 5, special: null },
                { type: "apparel", name: cn.boots || "Worn Boots", slot: "Feet", rarity: "COMMON", def: 1, hpBonus: 0, durability: 4, special: null }
            );
        }

        let mw = cn.meleeWeapon || {};
        let rw = cn.rangedWeapon || {};
        let weaponCard = {
            type: "item", subtype: "weapon", name: mw.name || "Short Sword",
            slot: "Hand (1H)", rarity: "COMMON", atk: 3, range: "Melee",
            damageType: mw.damageType || "Slashing", requires: { STR: 6 }, special: null, durability: 8
        };
        if (stats.AGI > stats.STR) {
            weaponCard = {
                type: "item", subtype: "weapon", name: rw.name || "Hunting Bow",
                slot: "Hand (2H)", rarity: "COMMON", atk: 3, range: "Ranged",
                damageType: rw.damageType || "Piercing", requires: { AGI: 6 }, special: null, durability: 8
            };
        }

        let ss = cn.strSkill || {};
        let as = cn.agiSkill || {};
        let ds = cn.defaultSkill || {};
        let skillCards = [];
        if (stats.STR >= 10) {
            skillCards.push({ type: "skill", name: ss.name || "Swordsmanship", category: ss.category || "Combat", modifier: ss.modifier || "+2 to Attack rolls with Slashing weapons", requires: { STR: 10 }, tier: "COMMON" });
        }
        if (stats.AGI >= 10) {
            skillCards.push({ type: "skill", name: as.name || "Quick Reflexes", category: as.category || "Defense", modifier: as.modifier || "+2 to Flee and initiative rolls", requires: { AGI: 10 }, tier: "COMMON" });
        }
        if (skillCards.length === 0) {
            skillCards.push({ type: "skill", name: ds.name || "Survival", category: ds.category || "Survival", modifier: ds.modifier || "+1 to Investigate and Rest rolls", requires: {}, tier: "COMMON" });
        }

        let hm = cn.healMagic || {};
        let im = cn.intMagic || {};
        let magicCards = [];
        if (stats.MAG >= 10) {
            magicCards.push({
                type: "magic", name: hm.name || "Healing Light", effectType: hm.effectType || "Restorative", skillType: hm.skillType || "Imperial",
                requires: { MAG: 10 }, effect: hm.effect || "Restore 6 HP to self or ally",
                stackWith: "Character + Action (Use Item) + " + (hm.skillType || "Imperial") + " skill", energyCost: 6, reusable: true
            });
        }
        if (stats.INT >= 12) {
            magicCards.push({
                type: "magic", name: im.name || "Mind Read", effectType: im.effectType || "Discovery", skillType: im.skillType || "Psionic",
                requires: { INT: 12 }, effect: im.effect || "View opponent's next planned action stack",
                stackWith: "Character + Action (Investigate)", energyCost: 4, reusable: true
            });
        }

        return { apparelCards, weaponCard, skillCards, magicCards };
    }

    // ── Shuffle Utility ──────────────────────────────────────────────

    function shuffle(arr) {
        let a = [...arr];
        for (let i = a.length - 1; i > 0; i--) {
            let j = Math.floor(Math.random() * (i + 1));
            [a[i], a[j]] = [a[j], a[i]];
        }
        return a;
    }

    // ── Starter Deck Assembly (multi-character) ───────────────────────

    function assembleStarterDeck(chars, theme) {
        if (!Array.isArray(chars)) chars = [chars];

        let allCards = [];
        let charNames = [];
        let activeTheme = getActiveTheme();

        // Per-character: character card + their equipment/skills/magic
        chars.forEach(char => {
            let characterCard = assembleCharacterCard(char);
            let equip = assembleCharEquipment(char);

            // Equip gear onto character card
            let bodyAp = equip.apparelCards.find(a => a.slot === "Body");
            if (bodyAp) characterCard.equipped.body = { name: bodyAp.name };
            let feetAp = equip.apparelCards.find(a => a.slot === "Feet");
            if (feetAp) characterCard.equipped.feet = { name: feetAp.name };
            characterCard.equipped.handR = { name: equip.weaponCard.name };
            if (equip.skillCards.length > 0) characterCard.activeSkills[0] = { name: equip.skillCards[0].name };

            allCards.push(characterCard, ...equip.apparelCards, equip.weaponCard, ...equip.skillCards, ...equip.magicCards);
            charNames.push(char.name || "Unknown");
        });

        // Shared deck cards (one set regardless of player count)
        let curTheme = theme || activeTheme;
        let themeConsumables = (curTheme && curTheme.starterDeck && curTheme.starterDeck.consumables) || [];
        let consumables = [];
        if (themeConsumables.length > 0) {
            themeConsumables.forEach(c => {
                for (let i = 0; i < (c.count || 1); i++) {
                    consumables.push({ type: "item", subtype: "consumable", name: c.name, rarity: "COMMON", effect: c.effect || "" });
                }
            });
        } else {
            consumables = [
                { type: "item", subtype: "consumable", name: "Ration", rarity: "COMMON", effect: "Restore 3 Energy" },
                { type: "item", subtype: "consumable", name: "Ration", rarity: "COMMON", effect: "Restore 3 Energy" },
                { type: "item", subtype: "consumable", name: "Health Potion", rarity: "COMMON", effect: "Restore 8 HP" },
                { type: "item", subtype: "consumable", name: "Bandage", rarity: "COMMON", effect: "Restore 4 HP" },
                { type: "item", subtype: "consumable", name: "Torch", rarity: "COMMON", effect: "+2 to Investigate rolls this round" }
            ];
        }
        let actionCards = [
            { type: "action", name: "Attack", actionType: "Offensive", stackWith: "Character + Weapon (required) + Skill (optional)", roll: "1d20 + STR + ATK vs target DEF", onHit: "Deal weapon ATK + STR as damage", energyCost: 0 },
            { type: "action", name: "Flee", actionType: "Movement", stackWith: "Character only", roll: "1d20 + AGI vs encounter difficulty", onHit: "Escape the encounter", energyCost: 0 },
            { type: "action", name: "Investigate", actionType: "Discovery", stackWith: "Character + Skill (optional)", roll: "1d20 + INT vs hidden threshold", onHit: "Reveal hidden items or information", energyCost: 0 },
            { type: "action", name: "Trade", actionType: "Social", stackWith: "Character + Item(s) to offer", roll: null, onHit: "CHA determines price modifier", energyCost: 0 },
            { type: "action", name: "Rest", actionType: "Recovery", stackWith: "Character only (no other actions)", roll: null, onHit: "Restore +2 HP, +3 Energy", energyCost: 0 },
            { type: "action", name: "Use Item", actionType: "Utility", stackWith: "Character + Consumable item", roll: null, onHit: "Apply item effect", energyCost: 0 },
            { type: "action", name: "Craft", actionType: "Creation", stackWith: "Character + Materials + Skill", roll: "1d20 + INT vs recipe difficulty", onHit: "Create new item", energyCost: 2 }
        ];
        let talkCard = { type: "talk", name: "Talk", energyCost: 5 };

        allCards.push(...consumables, ...actionCards, talkCard);

        let themeName = (theme || activeTheme).name || (theme || activeTheme).themeId || "high-fantasy";
        let deckName = "";
        return {
            themeId: (theme || activeTheme).themeId || "high-fantasy",
            deckName: deckName,
            characterNames: charNames,
            characterIds: chars.map(c => getCharId(c)),
            portraitUrl: getPortraitUrl(chars[0], "128x128"),
            createdAt: new Date().toISOString(),
            cardCount: allCards.length,
            cards: allCards
        };
    }

    // ── Load Characters ──────────────────────────────────────────────
    // Characters come from the deck's own Characters folder or are generated
    // No longer loads from Olio Population

    async function loadAvailableCharacters() {
        if (charsLoading) return;
        charsLoading = true;
        m.redraw();
        try {
            let allChars = [];
            let seenIds = new Set();
            let deckNameInput = getDeckNameInput();

            // Load from deck's Characters folder if we have a deck name
            if (deckNameInput) {
                try {
                    let deckCharDir = await page.findObject("auth.group", "data", "~/CardGame/" + deckNameInput + "/Characters");
                    if (deckCharDir) {
                        let q = am7view.viewQuery("olio.charPerson");
                        q.field("groupId", deckCharDir.id);
                        q.range(0, 8);  // Max 8 characters per deck
                        q.entity.request.push("profile", "store", "statistics");
                        let qr = await page.search(q);
                        if (qr && qr.results && qr.results.length) {
                            am7model.updateListModel(qr.results);
                            qr.results.forEach(ch => {
                                let cid = getCharId(ch);
                                if (cid && !seenIds.has(cid)) {
                                    seenIds.add(cid);
                                    allChars.push(ch);
                                }
                            });
                            console.log("[CardGame v2] Loaded " + qr.results.length + " characters from deck folder");
                        }
                    }
                } catch (de) {
                    console.warn("[CardGame v2] Could not load deck characters:", de);
                }
            }

            // Resolve partially-loaded statistics for all characters
            for (let ch of allChars) {
                await resolveStatistics(ch);
            }

            availableCharacters = allChars;
            if (allChars.length === 0) {
                console.log("[CardGame v2] No characters found - will need to generate");
            } else {
                console.log("[CardGame v2] Total available characters: " + allChars.length);
            }
        } catch (e) {
            console.error("[CardGame v2] Failed to load characters", e);
            page.toast("error", "Failed to load characters");
        }
        charsLoading = false;
        m.redraw();
    }

    // ── Character Generation ─────────────────────────────────────────

    async function loadCharacterTemplates() {
        if (characterTemplates) return characterTemplates;
        try {
            characterTemplates = await m.request({
                method: 'GET',
                url: 'media/cardGame/character-templates.json'
            });
            return characterTemplates;
        } catch (e) {
            console.error('[CardGame] Failed to load character templates:', e);
            return null;
        }
    }

    // Generate balanced characters from templates for a deck
    // Characters are kept in memory until deck is saved - no server persistence during build
    async function generateCharactersFromTemplates(themeId, count) {
        if (count === undefined) count = 8;
        const templates = await loadCharacterTemplates();
        if (!templates || !templates.templates) {
            page.toast('error', 'Character templates not available');
            return [];
        }

        generatingCharacters = true;
        m.redraw();

        // Shuffle and select unique templates
        const shuffled = [...templates.templates].sort(() => Math.random() - 0.5);
        const selectedTemplates = shuffled.slice(0, Math.min(count, shuffled.length));

        const characters = [];

        for (const template of selectedTemplates) {
            try {
                // Roll character from server - gets random name, gender, stats, apparel
                const gender = Math.random() > 0.5 ? 'male' : 'female';
                const rolled = await am7model.forms.commands.rollCharacter(undefined, undefined, gender);

                if (!rolled) {
                    console.warn('[CardGame] Failed to roll character for template:', template.id);
                    continue;
                }

                // Apply template modifications
                rolled.alignment = template.alignment;
                rolled.personality = template.personality || [];
                rolled.age = Math.floor(Math.random() * 38) + 18;  // Age 18-55

                // Get theme-specific trade
                const themeClass = template.themeVariants?.[themeId]?.trade || template.class;
                rolled.trades = [themeClass];

                // Add template reference for later use
                rolled._templateId = template.id;
                rolled._templateClass = template.class;

                // Generate a temporary ID for in-memory tracking
                rolled._tempId = "temp-" + template.id + "-" + Date.now() + "-" + Math.random().toString(36).slice(2, 11);

                characters.push(rolled);
                console.log('[CardGame] Generated character:', rolled.name, 'as', themeClass);
            } catch (e) {
                console.error('[CardGame] Error generating character from template:', template.id, e);
            }
        }

        page.toast('success', `Generated ${characters.length} characters`);
        generatingCharacters = false;
        m.redraw();
        return characters;
    }

    // Persist generated characters to server when saving deck
    async function persistGeneratedCharacters(characters, deckName) {
        if (!characters || !characters.length) return [];

        let charDir;
        try {
            charDir = await page.makePath("auth.group", "data", "~/CardGame/" + deckName + "/Characters");
            console.log('[CardGame] Characters folder:', charDir.path, 'id:', charDir.id);
        } catch (e) {
            console.error('[CardGame] Failed to create deck Characters folder:', e);
            return [];
        }

        const persisted = [];
        for (const char of characters) {
            // Skip if already persisted (has objectId)
            if (char.objectId) {
                persisted.push(char);
                continue;
            }

            try {
                // Check if character already exists
                let existing = await page.searchFirst("olio.charPerson", charDir.id, char.name);
                if (existing) {
                    console.log('[CardGame] Character already exists:', char.name);
                    persisted.push(existing);
                    continue;
                }

                // Prepare entity with proper schema
                let charN = am7model.prepareEntity(char, "olio.charPerson", true);

                // Prepare nested wearables and qualities
                if (charN.store && charN.store.apparel && charN.store.apparel[0] && charN.store.apparel[0].wearables) {
                    let w = charN.store.apparel[0].wearables;
                    for (let i = 0; i < w.length; i++) {
                        w[i] = am7model.prepareEntity(w[i], "olio.wearable", false);
                        if (w[i].qualities && w[i].qualities[0]) {
                            w[i].qualities[0] = am7model.prepareEntity(w[i].qualities[0], "olio.quality", false);
                        }
                    }
                }

                // Set the parent group to deck's Characters folder (both id and path required)
                charN.groupId = charDir.id;
                charN.groupPath = charDir.path;

                // Create the character
                let created = await page.createObject(charN);
                if (created) {
                    // Reload to get full object with objectId
                    let saved = await page.searchFirst("olio.charPerson", charDir.id, char.name);
                    if (saved) {
                        persisted.push(saved);
                        console.log('[CardGame] Persisted character:', saved.name);
                    }
                }
            } catch (e) {
                console.error('[CardGame] Error persisting character:', char.name, e);
            }
        }
        return persisted;
    }

    // ── Convert charPerson to card format ─────────────────────────────

    function charPersonToCard(charPerson) {
        const stats = charPerson.statistics || {};
        return {
            id: charPerson.objectId,
            objectId: charPerson.objectId,
            type: "character",
            name: charPerson.name || (charPerson.firstName + " " + charPerson.lastName),
            gender: charPerson.gender,
            race: charPerson.race,
            age: charPerson.age,
            alignment: charPerson.alignment,
            trade: charPerson.trades?.[0] || "",
            portraitUrl: charPerson.profile?.portrait?.groupPath && charPerson.profile?.portrait?.name ?
                g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + charPerson.profile.portrait.groupPath + "/" + charPerson.profile.portrait.name + "/256x256" : null,
            stats: {
                STR: stats.physicalStrength || 10,
                AGI: stats.agility || 10,
                END: stats.physicalEndurance || 10,
                INT: stats.intelligence || 10,
                MAG: stats.magic || stats.creativity || 10,
                CHA: stats.charisma || 10
            }
        };
    }

    // ── Refresh all character cards in a deck ─────────────────────────

    async function refreshAllCharacters(deck) {
        if (!deck || !deck.cards) return;
        let charCards = deck.cards.filter(c => c.type === "character" && (c.sourceId || c._sourceChar));
        let refreshed = 0;
        for (let card of charCards) {
            let ok = await refreshCharacterCard(card);
            if (ok) refreshed++;
        }
        if (refreshed > 0) {
            console.log("[CardGame v2] Refreshed " + refreshed + " character cards from source");
            m.redraw();
        }
    }

    // ── Expose on CardGame namespace ─────────────────────────────────

    window.CardGame.Characters = {
        // State (mutable, accessed by UI)
        state: {
            get availableCharacters() { return availableCharacters; },
            set availableCharacters(v) { availableCharacters = v; },
            get charsLoading() { return charsLoading; },
            set charsLoading(v) { charsLoading = v; },
            get selectedChars() { return selectedChars; },
            set selectedChars(v) { selectedChars = v; },
            get characterTemplates() { return characterTemplates; },
            set characterTemplates(v) { characterTemplates = v; },
            get generatingCharacters() { return generatingCharacters; },
            set generatingCharacters(v) { generatingCharacters = v; }
        },
        // Stat utilities
        statVal,
        mapStats,
        resolveStatistics,
        clampStat,
        str,
        // Portrait / ID helpers
        getPortraitUrl,
        getCharId,
        // Character CRUD
        fetchCharPerson,
        refreshCharacterCard,
        assembleCharacterCard,
        // Equipment assembly
        assembleCharEquipment,
        // Deck assembly
        assembleStarterDeck,
        // Character loading
        loadAvailableCharacters,
        // Character generation
        loadCharacterTemplates,
        generateCharactersFromTemplates,
        persistGeneratedCharacters,
        // Utility
        charPersonToCard,
        refreshAllCharacters,
        shuffle
    };

    console.log('[CardGame] Characters service loaded (' +
        Object.keys(window.CardGame.Characters).length + ' exports)');

}());
