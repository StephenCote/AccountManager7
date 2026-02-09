/**
 * CardGame Constants — All game configuration, type definitions, and balance data
 * Extracted from the monolithic cardGame-v2.js into a standalone module.
 *
 * Exposes: window.CardGame.Constants
 */
(function() {

    window.CardGame = window.CardGame || {};

    // ── Card Type Configuration ──────────────────────────────────────
    const CARD_TYPES = {
        character:  { color: "#B8860B", bg: "#FDF5E6", icon: "shield",        label: "Character" },
        apparel:    { color: "#808080", bg: "#F5F5F5", icon: "checkroom",      label: "Apparel" },
        item:       { color: "#2E7D32", bg: "#F1F8E9", icon: "swords",         label: "Item" },
        action:     { color: "#C62828", bg: "#FFF3F0", icon: "bolt",           label: "Action" },
        talk:       { color: "#1565C0", bg: "#E3F2FD", icon: "chat_bubble",    label: "Talk" },
        encounter:  { color: "#6A1B9A", bg: "#F3E5F5", icon: "blur_on",       label: "Encounter" },
        scenario:   { color: "#1B5E20", bg: "#E8F5E9", icon: "explore",       label: "Scenario" },
        loot:       { color: "#F57F17", bg: "#FFFDE7", icon: "inventory_2",    label: "Loot" },
        skill:      { color: "#E65100", bg: "#FFF3E0", icon: "star",           label: "Skill" },
        magic:      { color: "#00695C", bg: "#E0F2F1", icon: "auto_fix_high",  label: "Magic Effect" }
    };

    // ── Template/Utility Art Types ───────────────────────────────────
    const TEMPLATE_TYPES = {
        cardFront:  { color: "#795548", icon: "crop_portrait",    label: "Card Front" },
        cardBack:   { color: "#546E7A", icon: "flip_to_back",     label: "Card Back" },
        background: { color: "#33691E", icon: "landscape",        label: "Background" },
        tabletop:   { color: "#5D4037", icon: "table_restaurant", label: "Tabletop" }
    };

    // ── Sub-Icons for Item Subtypes ──────────────────────────────────
    const ITEM_SUBTYPE_ICONS = {
        weapon:     "swords",
        consumable: "science"
    };

    // ── Card Render Configuration (Phase 7.0 consolidation) ─────────
    // Defines fields to render for each card type
    const CARD_RENDER_CONFIG = {
        apparel: {
            placeholderIcon: "checkroom",
            headerField: { field: "slot", default: "Body", icon: "back_hand", showRarity: true },
            details: [
                { type: "stats", fields: ["def", "hpBonus"], labels: { def: "DEF +", hpBonus: "HP +" } },
                { field: "special", icon: "auto_awesome" },
                { field: "flavor", type: "flavor" }
            ],
            footer: [{ field: "durability", icon: "build", suffix: " Dur", default: "\u221E" }]
        },
        weapon: {
            placeholderIcon: "swords",
            headerField: { field: "slot", default: "Hand (1H)", icon: "back_hand", showRarity: true },
            details: [
                { type: "stats", fields: ["atk", "range"], labels: { atk: "ATK +" }, defaults: { range: "Melee" }, atkClass: "cg2-mod-atk" },
                { field: "damageType", icon: "flash_on", default: "Slashing" },
                { field: "requires", icon: "key", type: "requires" },
                { field: "special", icon: "auto_awesome" }
            ],
            footer: [{ field: "durability", icon: "build", suffix: " Dur", default: "\u221E" }]
        },
        consumable: {
            placeholderIcon: "science",
            headerField: { label: "Consumable", icon: "science", showRarity: true },
            details: [
                { field: "effect", icon: "auto_awesome" },
                { type: "warning", text: "USE IT OR LOSE IT" }
            ],
            footer: []
        },
        action: {
            placeholderIcon: "bolt",
            placeholderColor: "#C62828",
            details: [
                { field: "actionType", icon: "category", default: "Offensive" },
                { field: "stackWith", icon: "layers" },
                { field: "roll", icon: "casino", class: "cg2-roll" },
                { field: "onHit", icon: "gps_fixed" }
            ],
            footer: [{ field: "energyCost", icon: "bolt", suffix: " Energy", default: 0, class: "cg2-energy-cost" }]
        },
        talk: {
            placeholderIcon: "chat_bubble",
            placeholderColor: "#1565C0",
            details: [
                { label: "Social", icon: "category" },
                { label: "LLM chat + CHA mod", icon: "computer" },
                { label: "1d20 + CHA vs target", icon: "casino" },
                { label: "Required for communication", icon: "block", class: "cg2-card-warning" }
            ],
            footer: [{ field: "energyCost", icon: "bolt", suffix: " Energy", default: 5, class: "cg2-energy-cost" }]
        },
        encounter: {
            placeholderIcon: "blur_on",
            placeholderColor: "#6A1B9A",
            headerField: { field: "subtype", default: "Threat", icon: "warning", showRarity: true },
            details: [
                { field: "difficulty", icon: "signal_cellular_alt", prefix: "DC " },
                { type: "stats", fields: ["atk", "def", "hp"], labels: { atk: "ATK +", def: "DEF +", hp: "HP " }, atkClass: "cg2-mod-atk" },
                { field: "behavior", icon: "psychology_alt" },
                { field: "loot", icon: "inventory_2", type: "array" }
            ],
            footer: []
        },
        scenario: {
            placeholderIcon: "explore",
            placeholderColor: "#1B5E20",
            headerField: { field: "effect", default: "Event", icon: "explore", formatValue: function(v) { return v === "threat" ? "Threat" : "Boon"; } },
            details: [
                { field: "description", icon: "info" },
                { field: "bonus", icon: "auto_awesome", type: "scenarioBonus" },
                { field: "threatBonus", icon: "warning", prefix: "Threat DC +", type: "threatOnly" }
            ],
            footer: []
        },
        loot: {
            placeholderIcon: "inventory_2",
            placeholderColor: "#F57F17",
            headerField: { field: "source", default: "Spoils", icon: "inventory_2", showRarity: true },
            details: [
                { field: "effect", icon: "auto_awesome" },
                { field: "flavor", type: "flavor" }
            ],
            footer: []
        },
        skill: {
            placeholderIcon: "star",
            placeholderColor: "#E65100",
            details: [
                { field: "category", icon: "psychology", default: "Combat" },
                { field: "modifier", icon: "tune" },
                { field: "requires", icon: "key", type: "requires" },
                { field: "tier", icon: "military_tech" }
            ],
            footer: [{ label: "Unlimited", icon: "all_inclusive", class: "cg2-uses" }]
        },
        magic: {
            placeholderIcon: "auto_fix_high",
            placeholderColor: "#00695C",
            details: [
                { field: "effectType", icon: "category", default: "Offensive" },
                { field: "skillType", icon: "psychology", default: "Imperial" },
                { field: "requires", icon: "key", type: "requires" },
                { field: "effect", icon: "auto_awesome" },
                { field: "stackWith", icon: "layers" }
            ],
            footer: [
                { field: "energyCost", icon: "bolt", suffix: " Energy", default: 0, class: "cg2-energy-cost" },
                { field: "reusable", icon: "sync", altIcon: "local_fire_department", trueLabel: "Reusable", falseLabel: "Consumable" }
            ]
        }
    };

    // ── Game Phases ──────────────────────────────────────────────────
    const GAME_PHASES = {
        INITIATIVE: "initiative",
        EQUIP: "equip",
        THREAT_RESPONSE: "threat_response",  // Phase for responding to beginning/end threats
        DRAW_PLACEMENT: "draw_placement",
        RESOLUTION: "resolution",
        CLEANUP: "cleanup",
        END_THREAT: "end_threat"  // Phase for responding to end-of-round threats
    };

    // ── Status Effects ───────────────────────────────────────────────
    const STATUS_EFFECTS = {
        STUNNED: {
            id: "stunned",
            name: "Stunned",
            icon: "star",
            color: "#FFC107",
            description: "Skip next action",
            duration: 1,
            durationType: "turns"
        },
        POISONED: {
            id: "poisoned",
            name: "Poisoned",
            icon: "skull",
            color: "#8BC34A",
            description: "-2 HP at turn start",
            duration: 3,
            durationType: "turns",
            onTurnStart: (actor) => {
                actor.hp = Math.max(0, actor.hp - 2);
                return { message: "Poison deals 2 damage", damage: 2 };
            }
        },
        SHIELDED: {
            id: "shielded",
            name: "Shielded",
            icon: "shield",
            color: "#2196F3",
            description: "+3 DEF",
            duration: 1,
            durationType: "untilHit",
            defBonus: 3
        },
        WEAKENED: {
            id: "weakened",
            name: "Weakened",
            icon: "trending_down",
            color: "#9C27B0",
            description: "-2 to all rolls",
            duration: 2,
            durationType: "turns",
            rollPenalty: -2
        },
        ENRAGED: {
            id: "enraged",
            name: "Enraged",
            icon: "local_fire_department",
            color: "#F44336",
            description: "+3 ATK, -2 DEF",
            duration: 2,
            durationType: "turns",
            atkBonus: 3,
            defPenalty: -2
        },
        BURNING: {
            id: "burning",
            name: "Burning",
            icon: "whatshot",
            color: "#FF5722",
            description: "-3 HP at turn start",
            duration: 2,
            durationType: "turns",
            onTurnStart: (actor) => {
                actor.hp = Math.max(0, actor.hp - 3);
                return { message: "Burn deals 3 damage", damage: 3 };
            }
        },
        BLEEDING: {
            id: "bleeding",
            name: "Bleeding",
            icon: "water_drop",
            color: "#E53935",
            description: "-1 HP at turn start",
            duration: 4,
            durationType: "turns",
            onTurnStart: (actor) => {
                actor.hp = Math.max(0, actor.hp - 1);
                return { message: "Bleed deals 1 damage", damage: 1 };
            }
        },
        REGENERATING: {
            id: "regenerating",
            name: "Regenerating",
            icon: "spa",
            color: "#4CAF50",
            description: "+2 HP at turn start",
            duration: 3,
            durationType: "turns",
            onTurnStart: (actor) => {
                actor.hp = Math.min(actor.maxHp, actor.hp + 2);
                return { message: "Regen restores 2 HP", damage: 0 };
            }
        },
        FORTIFIED: {
            id: "fortified",
            name: "Fortified",
            icon: "fort",
            color: "#1565C0",
            description: "+2 DEF, +1 ATK",
            duration: 2,
            durationType: "turns",
            defBonus: 2,
            atkBonus: 1
        },
        INSPIRED: {
            id: "inspired",
            name: "Inspired",
            icon: "auto_awesome",
            color: "#FFB300",
            description: "+2 to all rolls",
            duration: 2,
            durationType: "turns",
            rollBonus: 2
        }
    };

    // ── Combat Outcomes ──────────────────────────────────────────────
    const COMBAT_OUTCOMES = {
        CRITICAL_HIT:    { label: "Critical Hit!", damageMultiplier: 2, effect: "Nat 20! Double damage + reward card", isCriticalHit: true },
        DEVASTATING:     { label: "Devastating!", damageMultiplier: 1.5, effect: "massive damage" },
        STRONG_HIT:      { label: "Strong Hit", damageMultiplier: 1, effect: "full damage" },
        GLANCING_HIT:    { label: "Glancing Hit", damageMultiplier: 0.5, effect: "half damage" },
        CLASH:           { label: "Clash!", damageMultiplier: 0, effect: "both take 1 damage", bothTakeDamage: 1 },
        DEFLECT:         { label: "Deflected", damageMultiplier: 0, effect: "no damage" },
        PARRY:           { label: "Parried!", damageMultiplier: 0, effect: "defender may counter", allowCounter: true },
        CRITICAL_PARRY:  { label: "Perfect Parry!", damageMultiplier: -0.25, effect: "Nat 20! Counter attack + reward card", isCriticalParry: true, allowCounter: true },
        CRITICAL_MISS:   { label: "Critical Miss!", damageMultiplier: -0.5, effect: "Nat 1! Attacker takes damage + stunned", isCriticalCounter: true }
    };

    // ── Card Templates (for new card creation in deck builder) ───────
    const CARD_TEMPLATES = {
        action: {
            type: "action", name: "", actionType: "Offensive",
            stackWith: "", roll: "", onHit: "", energyCost: 0
        },
        "item-weapon": {
            type: "item", subtype: "weapon", name: "", fabric: "",
            slot: "Hand (1H)", rarity: "COMMON", atk: 3, range: "Melee",
            damageType: "Slashing", requires: {}, special: null, durability: 8
        },
        "item-consumable": {
            type: "item", subtype: "consumable", name: "", fabric: "",
            rarity: "COMMON", effect: ""
        },
        apparel: {
            type: "apparel", name: "", fabric: "",
            slot: "Body", rarity: "COMMON", def: 1, hpBonus: 0,
            durability: 5, special: null
        },
        skill: {
            type: "skill", name: "", category: "Combat",
            modifier: "", requires: {}, tier: "COMMON"
        },
        magic: {
            type: "magic", name: "", effectType: "Offensive",
            skillType: "Imperial", requires: {},
            effect: "", stackWith: "", energyCost: 4, reusable: true
        },
        talk: {
            type: "talk", name: "Talk", energyCost: 5
        },
        encounter: {
            type: "encounter", name: "", subtype: "Threat",
            difficulty: 10, atk: 3, def: 3, hp: 10,
            behavior: "", loot: [], rarity: "COMMON"
        },
        scenario: {
            type: "scenario", name: "", effect: "neutral",
            description: "", bonus: null, threatBonus: 0, icon: "explore"
        },
        loot: {
            type: "loot", name: "", rarity: "COMMON",
            source: "Spoils", effect: "", flavor: ""
        }
    };

    // ── Theme Defaults ───────────────────────────────────────────────
    const THEME_DEFAULTS = {
        "high-fantasy": {
            name: "High Fantasy",
            backgroundPrompt: "Vast fantasy kingdom with ancient stone castles, enchanted forests, magical aurora in the sky, crystalline mountains, epic fantasy landscape",
            promptSuffix: "high fantasy, vibrant colors, detailed illustration"
        },
        "dark-medieval": {
            name: "Dark Medieval",
            backgroundPrompt: "Grim medieval fortress on a windswept moor, dark stone walls, overcast stormy skies, torchlit battlements, desolate and foreboding landscape",
            promptSuffix: "dark medieval, muted earth tones, gritty realism"
        },
        "sci-fi": {
            name: "Sci-Fi",
            backgroundPrompt: "Futuristic space station orbiting a gas giant, nebula backdrop, holographic displays, chrome corridors, sweeping sci-fi vista",
            promptSuffix: "science fiction, cinematic lighting, sleek futuristic design"
        },
        "post-apocalypse": {
            name: "Post Apocalypse",
            backgroundPrompt: "Ruined cityscape overgrown with vegetation, crumbling skyscrapers, dusty wasteland, abandoned vehicles, harsh survival outpost",
            promptSuffix: "post-apocalyptic, desaturated palette, gritty detail"
        },
        "steampunk": {
            name: "Steampunk",
            backgroundPrompt: "Vast steampunk cityscape, towering brass smokestacks, intricate clockwork buildings, airships floating between copper-domed towers, gas-lamp lit cobblestone streets, Victorian industrial architecture, warm amber and sepia tones",
            promptSuffix: "steampunk, Victorian industrial, brass and copper, clockwork gears, detailed mechanical illustration"
        }
    };

    // ── Default Theme Configuration ──────────────────────────────────
    const DEFAULT_THEME = {
        themeId: "high-fantasy",
        name: "High Fantasy",
        version: "1.0",
        artStyle: {
            backgroundPrompt: THEME_DEFAULTS["high-fantasy"].backgroundPrompt,
            promptSuffix: THEME_DEFAULTS["high-fantasy"].promptSuffix,
            colorPalette: {
                character: "#B8860B", apparel: "#808080", item: "#2E7D32",
                action: "#C62828", talk: "#1565C0", encounter: "#6A1B9A",
                scenario: "#1B5E20", loot: "#F57F17",
                skill: "#E65100", magic: "#00695C"
            }
        },
        balance: {
            hpMax: 20, moraleMax: 20,
            apFormula: "floor(END / 5) + 1",
            initiativeFormula: "1d20 + AGI"
        }
    };

    // ── Action Definitions (defaults — overridden by JSON when loaded) ──
    // Canonical source: media/cardGame/action-definitions.json
    // Hardcoded fallback ensures the game works even if JSON fails to load.
    let ACTION_DEFINITIONS = {
        "Attack":      { icon: "swords",          type: "Offensive",  energyCost: 0, roll: "1d20 + STR + ATK vs DEF",        stackWith: "Weapon + Skill + Magic", desc: "Melee or ranged attack" },
        "Guard":       { icon: "shield",          type: "Defensive",  energyCost: 0, roll: null,                              stackWith: "Skill",                  desc: "Auto: +3 DEF this round", exclusive: false },
        "Flee":        { icon: "directions_run",  type: "Movement",   energyCost: 0, roll: "1d20 + AGI vs difficulty",        stackWith: "Skill",                  desc: "Attempt to escape" },
        "Rest":        { icon: "hotel",           type: "Recovery",   energyCost: 0, roll: null,                              stackWith: "None",                   desc: "Restore +2 HP, +3 Energy", exclusive: true },
        "Use Item":    { icon: "science",         type: "Utility",    energyCost: 0, roll: null,                              stackWith: "Consumable item",        desc: "Apply consumable effect" },
        "Investigate": { icon: "search",          type: "Discovery",  energyCost: 0, roll: "1d20 + INT vs hidden threshold",  stackWith: "Skill",                  desc: "Search for hidden items/info" },
        "Trade":       { icon: "storefront",      type: "Social",     energyCost: 0, roll: null,                              stackWith: "Item(s) to offer",       desc: "CHA determines price" },
        "Craft":       { icon: "construction",    type: "Creation",   energyCost: 2, roll: "1d20 + INT vs recipe difficulty", stackWith: "Materials + Skill",       desc: "Create a new item" },
        "Steal":       { icon: "back_hand",       type: "Thievery",   energyCost: 0, roll: "1d20 + AGI vs target DEF",        stackWith: "Skill",                  desc: "Take item from target" },
        "Talk":        { icon: "chat_bubble",     type: "Social",     energyCost: 5, roll: "1d20 + CHA vs CHA",              stackWith: "Skill",                  desc: "Negotiate or persuade" },
        "Feint":       { icon: "swap_horiz",      type: "Tactical",   energyCost: 0, roll: "1d20 + AGI vs INT",              stackWith: "Skill",                  desc: "Deceive for advantage next action" },
        "Channel":     { icon: "auto_fix_high",   type: "Magic",      energyCost: 3, roll: "1d20 + MAG",                     stackWith: "Magic Effect + Skill",   desc: "Cast a spell" }
    };

    let COMMON_ACTIONS = ["Attack", "Channel", "Flee", "Rest", "Use Item", "Talk"];
    let ACTION_ART_PROMPTS = {};

    /**
     * Load action definitions from external JSON file.
     * Updates ACTION_DEFINITIONS, COMMON_ACTIONS, and ACTION_ART_PROMPTS
     * on the exported Constants object. Falls back to hardcoded defaults.
     */
    async function loadActionDefinitions() {
        try {
            let data = await m.request({ method: "GET", url: "media/cardGame/action-definitions.json" });
            if (data && data.actions) {
                ACTION_DEFINITIONS = data.actions;
                window.CardGame.Constants.ACTION_DEFINITIONS = ACTION_DEFINITIONS;
                console.log("[CardGame] Loaded", Object.keys(ACTION_DEFINITIONS).length, "action definitions from JSON");
            }
            if (data && data.commonActions) {
                COMMON_ACTIONS = data.commonActions;
                window.CardGame.Constants.COMMON_ACTIONS = COMMON_ACTIONS;
            }
            // Build art prompts from loaded definitions
            ACTION_ART_PROMPTS = {};
            for (let [name, def] of Object.entries(ACTION_DEFINITIONS)) {
                if (def.artPrompt) ACTION_ART_PROMPTS[name] = def.artPrompt;
            }
            window.CardGame.Constants.ACTION_ART_PROMPTS = ACTION_ART_PROMPTS;
        } catch (e) {
            console.warn("[CardGame] Could not load action-definitions.json, using defaults");
        }
    }

    // ── Stat Descriptions ────────────────────────────────────────────
    const STAT_DESCRIPTIONS = {
        STR: "Strength \u2014 melee damage",
        AGI: "Agility \u2014 initiative rolls",
        END: "Endurance \u2014 action points",
        INT: "Intelligence \u2014 skill effects",
        MAG: "Magic \u2014 energy pool & spells",
        CHA: "Charisma \u2014 talk & morale"
    };

    // ── Expose on CardGame namespace ─────────────────────────────────
    window.CardGame.Constants = {
        CARD_TYPES,
        TEMPLATE_TYPES,
        ITEM_SUBTYPE_ICONS,
        CARD_RENDER_CONFIG,
        GAME_PHASES,
        STATUS_EFFECTS,
        COMBAT_OUTCOMES,
        CARD_TEMPLATES,
        THEME_DEFAULTS,
        DEFAULT_THEME,
        ACTION_DEFINITIONS,
        COMMON_ACTIONS,
        ACTION_ART_PROMPTS,
        STAT_DESCRIPTIONS,
        loadActionDefinitions
    };

    console.log('[CardGame] Constants loaded (' + Object.keys(window.CardGame.Constants).length + ' exports)');

}());
