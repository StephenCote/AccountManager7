// Card Game v2 — Phases 1-3: Card Rendering, Deck Builder, Image Generation
// Phase 1: Static card rendering for all 8 card types
// Phase 2: Deck builder with multi-character selection + starter deck assembly + save/load
// Phase 3: Image generation pipeline with SD art generation queue

(function () {

    // ── Card Type Configuration ──────────────────────────────────────
    const CARD_TYPES = {
        character:  { color: "#B8860B", bg: "#FDF5E6", icon: "shield",        label: "Character" },
        apparel:    { color: "#808080", bg: "#F5F5F5", icon: "checkroom",      label: "Apparel" },
        item:       { color: "#2E7D32", bg: "#F1F8E9", icon: "swords",         label: "Item" },
        action:     { color: "#C62828", bg: "#FFF3F0", icon: "bolt",           label: "Action" },
        talk:       { color: "#1565C0", bg: "#E3F2FD", icon: "chat_bubble",    label: "Talk" },
        encounter:  { color: "#6A1B9A", bg: "#F3E5F5", icon: "blur_on",       label: "Encounter" },
        skill:      { color: "#E65100", bg: "#FFF3E0", icon: "star",           label: "Skill" },
        magic:      { color: "#00695C", bg: "#E0F2F1", icon: "auto_fix_high",  label: "Magic Effect" }
    };

    // Sub-icons for item subtypes
    const ITEM_SUBTYPE_ICONS = {
        weapon:     "swords",
        consumable: "science"
    };

    // ── Rarity Display ───────────────────────────────────────────────
    function rarityStars(rarity) {
        let n = ({ COMMON: 1, UNCOMMON: 2, RARE: 3, EPIC: 4, LEGENDARY: 5 })[rarity] || 1;
        return Array.from({ length: 5 }, (_, i) =>
            m("span", { style: { color: i < n ? "#FFD700" : "#D0D0D0", fontSize: "14px" } }, "\u2605")
        );
    }

    // ── Need Bar Component ───────────────────────────────────────────
    function NeedBar() {
        return {
            view(vnode) {
                let { label, current, max, color, abbrev } = vnode.attrs;
                let pct = Math.max(0, Math.min(100, (current / max) * 100));
                return m("div", { class: "cg2-need-row" }, [
                    m("span", { class: "cg2-need-label" }, abbrev || label),
                    m("div", { class: "cg2-need-track" }, [
                        m("div", { class: "cg2-need-fill", style: { width: pct + "%", background: color } })
                    ]),
                    m("span", { class: "cg2-need-value" }, current + "/" + max)
                ]);
            }
        };
    }

    // ── Stat Block Component ─────────────────────────────────────────
    function StatBlock() {
        return {
            view(vnode) {
                let stats = vnode.attrs.stats;
                let order = [["STR", "AGI", "END"], ["INT", "MAG", "CHA"]];
                return m("div", { class: "cg2-stat-grid" },
                    order.map(row =>
                        m("div", { class: "cg2-stat-row" },
                            row.map(s =>
                                m("div", { class: "cg2-stat-cell" }, [
                                    m("span", { class: "cg2-stat-abbrev" }, s),
                                    m("span", { class: "cg2-stat-val" }, stats[s] || 0)
                                ])
                            )
                        )
                    )
                );
            }
        };
    }

    // ── Corner Icon Helper ───────────────────────────────────────────
    function cornerIcon(type, position) {
        let cfg = CARD_TYPES[type] || CARD_TYPES.item;
        let icon = cfg.icon;
        if (type === "item") icon = ITEM_SUBTYPE_ICONS.weapon; // default; overridden per card
        let style = {
            position: "absolute", fontSize: "18px", color: cfg.color, opacity: "0.7",
            lineHeight: "1"
        };
        if (position === "top-left")     { style.top = "6px"; style.left = "8px"; }
        if (position === "top-right")    { style.top = "6px"; style.right = "8px"; }
        if (position === "bottom-right") { style.bottom = "6px"; style.right = "8px"; style.transform = "rotate(180deg)"; }
        return m("span", { class: "material-symbols-outlined", style }, icon);
    }

    // ── Card Face Renderers (per type) ───────────────────────────────

    function renderCharacterBody(card) {
        let stats = card.stats || {};
        let needs = card.needs || {};
        let equip = card.equipped || {};
        let skills = card.activeSkills || [];
        return [
            m("div", { class: "cg2-card-portrait" }, [
                card.portraitUrl
                    ? m("img", { src: card.portraitUrl, class: "cg2-portrait-img" })
                    : m("span", { class: "material-symbols-outlined", style: { fontSize: "64px", color: "#B8860B" } }, "person"),
                card.race ? m("span", { class: "cg2-race-badge" }, card.race) : null
            ]),
            m("div", { class: "cg2-card-subtitle" }, [
                (card.race || "") + (card.age ? ", " + card.age : "") + (card.alignment ? " / " + card.alignment : ""),
                card.level ? m("span", { class: "cg2-level" }, "Lv " + card.level) : null
            ]),
            m("div", { class: "cg2-divider" }),
            m(StatBlock, { stats }),
            m("div", { class: "cg2-divider" }),
            m(NeedBar, { label: "Health", abbrev: "HP", current: needs.hp || 20, max: 20, color: "#e53935" }),
            m(NeedBar, { label: "Energy", abbrev: "NRG", current: needs.energy || 14, max: stats.MAG || 14, color: "#1E88E5" }),
            m(NeedBar, { label: "Morale", abbrev: "MRL", current: needs.morale || 20, max: 20, color: "#43A047" }),
            m("div", { class: "cg2-divider" }),
            m("div", { class: "cg2-equip-row" }, [
                m("span", { class: "cg2-section-label" }, "Skill Slots:"),
                m("span", skills.map((s, i) => m("span", { class: "cg2-slot" + (s ? " cg2-slot-filled" : "") }, s ? s.name : "\u2013")))
            ]),
            m("div", { class: "cg2-equip-row" }, [
                m("span", { class: "cg2-section-label" }, "Equip:"),
                m("span", ["head", "body", "handL", "handR", "feet", "ring", "back"].map(slot =>
                    m("span", {
                        class: "cg2-slot" + (equip[slot] ? " cg2-slot-filled" : ""),
                        title: slot
                    }, equip[slot] ? equip[slot].name : slot.charAt(0).toUpperCase())
                ))
            ])
        ];
    }

    function renderApparelBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img" })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon" }, "checkroom")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Slot: " + (card.slot || "Body")),
                m("span", { class: "cg2-rarity" }, rarityStars(card.rarity))
            ]),
            m("div", { class: "cg2-divider" }),
            m("div", { class: "cg2-card-stats-line" }, [
                card.def != null ? m("span", { class: "cg2-mod" }, "DEF +" + card.def) : null,
                card.hpBonus != null ? m("span", { class: "cg2-mod" }, "HP +" + card.hpBonus) : null
            ]),
            card.special ? m("div", { class: "cg2-card-special" }, "Special: " + card.special) : null,
            card.flavor ? m("div", { class: "cg2-flavor" }, "\u201C" + card.flavor + "\u201D") : null,
            m("div", { class: "cg2-card-footer" }, [
                m("span", { class: "cg2-durability" }, "Durability: " + (card.durability || "\u221E"))
            ])
        ];
    }

    function renderWeaponBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img" })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon" }, "swords")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Slot: " + (card.slot || "Hand (1H)")),
                m("span", { class: "cg2-rarity" }, rarityStars(card.rarity))
            ]),
            m("div", { class: "cg2-divider" }),
            m("div", { class: "cg2-card-stats-line" }, [
                m("span", { class: "cg2-mod cg2-mod-atk" }, "ATK +" + (card.atk || 0)),
                m("span", "Range: " + (card.range || "Melee"))
            ]),
            m("div", { class: "cg2-card-detail" }, "Type: " + (card.damageType || "Slashing")),
            card.requires ? m("div", { class: "cg2-card-detail" }, "Requires: " + Object.entries(card.requires).map(([k, v]) => k + " " + v).join(", ")) : null,
            card.special ? m("div", { class: "cg2-card-special" }, "Special: " + card.special) : null,
            m("div", { class: "cg2-card-footer" }, [
                m("span", { class: "cg2-durability" }, "Durability: " + (card.durability || "\u221E"))
            ])
        ];
    }

    function renderConsumableBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img" })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon" }, "science")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Consumable"),
                m("span", { class: "cg2-rarity" }, rarityStars(card.rarity))
            ]),
            m("div", { class: "cg2-divider" }),
            m("div", { class: "cg2-card-effect" }, "Effect: " + (card.effect || "")),
            m("div", { class: "cg2-use-or-lose" }, "USE IT OR LOSE IT"),
            m("div", { class: "cg2-use-or-lose-sub" }, "If played in a round, this card is consumed whether the effect was needed or not.")
        ];
    }

    function renderActionBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#C62828" } }, "bolt")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Action Type: " + (card.actionType || "Offensive"))
            ]),
            m("div", { class: "cg2-divider" }),
            card.stackWith ? m("div", { class: "cg2-card-detail" }, "Stack with: " + card.stackWith) : null,
            card.roll ? m("div", { class: "cg2-card-detail cg2-roll" }, "Roll: " + card.roll) : null,
            card.onHit ? m("div", { class: "cg2-card-detail" }, "On hit: " + card.onHit) : null,
            m("div", { class: "cg2-card-footer" }, [
                card.energyCost ? m("span", { class: "cg2-energy-cost" }, "Cost: " + card.energyCost + " Energy") : m("span", "Cost: 0")
            ])
        ];
    }

    function renderTalkBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#1565C0" } }, "chat_bubble")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Action Type: Social")
            ]),
            m("div", { class: "cg2-divider" }),
            m("div", { class: "cg2-card-detail" }, [
                m("strong", "ONLINE: "), "Opens LLM chat with target. Outcome determined by conversation quality + CHA modifier."
            ]),
            m("div", { class: "cg2-card-detail", style: { marginTop: "6px" } }, [
                m("strong", "RL: "), "Speak to the target player/GM. Roll: 1d20 + CHA vs target\u2019s 1d20 + CHA."
            ]),
            m("div", { class: "cg2-card-detail cg2-card-warning", style: { marginTop: "8px" } }, [
                m("strong", "WITHOUT THIS CARD: "), "No communication allowed."
            ]),
            m("div", { class: "cg2-card-footer" }, [
                m("span", { class: "cg2-energy-cost" }, "Cost: " + (card.energyCost || 5) + " Energy")
            ])
        ];
    }

    function renderEncounterBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img" })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#6A1B9A" } }, "blur_on")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Encounter: " + (card.subtype || "Threat")),
                m("span", { class: "cg2-rarity" }, rarityStars(card.rarity))
            ]),
            m("div", { class: "cg2-divider" }),
            card.difficulty != null ? m("div", { class: "cg2-card-detail" }, "Difficulty: " + card.difficulty) : null,
            m("div", { class: "cg2-card-stats-line" }, [
                card.atk != null ? m("span", { class: "cg2-mod cg2-mod-atk" }, "ATK +" + card.atk) : null,
                card.def != null ? m("span", { class: "cg2-mod" }, "DEF +" + card.def) : null,
                card.hp != null ? m("span", { class: "cg2-mod" }, "HP " + card.hp) : null
            ]),
            card.behavior ? m("div", { class: "cg2-card-detail" }, "Behavior: " + card.behavior) : null,
            card.loot && card.loot.length ? m("div", { class: "cg2-card-detail" }, [
                m("strong", "Loot: "),
                card.loot.join(", ")
            ]) : null
        ];
    }

    function renderSkillBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img" })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#E65100" } }, "star")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Skill: " + (card.category || "Combat"))
            ]),
            m("div", { class: "cg2-divider" }),
            m("div", { class: "cg2-card-effect" }, "Modifier: " + (card.modifier || "")),
            card.requires ? m("div", { class: "cg2-card-detail" }, "Requires: " + Object.entries(card.requires).map(([k, v]) => k + " " + v).join(", ")) : null,
            card.tier ? m("div", { class: "cg2-card-detail" }, "Tier: " + card.tier) : null,
            m("div", { class: "cg2-card-footer" }, [
                m("span", "Uses: \u221E")
            ])
        ];
    }

    function renderMagicBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img" })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#00695C" } }, "auto_fix_high")
            ),
            m("div", { class: "cg2-card-meta" }, [
                m("span", "Magic Effect: " + (card.effectType || "Offensive"))
            ]),
            m("div", { class: "cg2-divider" }),
            m("div", { class: "cg2-card-detail" }, "Skill Type: " + (card.skillType || "Imperial")),
            card.requires ? m("div", { class: "cg2-card-detail" }, "Requires: " + Object.entries(card.requires).map(([k, v]) => k + " " + v).join(", ")) : null,
            m("div", { class: "cg2-card-effect" }, "Effect: " + (card.effect || "")),
            card.stackWith ? m("div", { class: "cg2-card-detail" }, "Stack with: " + card.stackWith) : null,
            m("div", { class: "cg2-card-footer" }, [
                m("span", { class: "cg2-energy-cost" }, "Cost: " + (card.energyCost || 0) + " Energy"),
                m("span", card.reusable ? "Reusable" : "Consumable")
            ])
        ];
    }

    // ── CardFace Component ───────────────────────────────────────────
    function CardFace() {
        return {
            view(vnode) {
                let card = vnode.attrs.card;
                if (!card || !card.type) return m("div.cg2-card.cg2-card-empty", "No card");
                let type = card.type;
                let cfg = CARD_TYPES[type] || CARD_TYPES.item;
                let bodyFn;
                switch (type) {
                    case "character":  bodyFn = renderCharacterBody; break;
                    case "apparel":    bodyFn = renderApparelBody; break;
                    case "item":
                        bodyFn = card.subtype === "consumable" ? renderConsumableBody : renderWeaponBody;
                        break;
                    case "action":     bodyFn = renderActionBody; break;
                    case "talk":       bodyFn = renderTalkBody; break;
                    case "encounter":  bodyFn = renderEncounterBody; break;
                    case "skill":      bodyFn = renderSkillBody; break;
                    case "magic":      bodyFn = renderMagicBody; break;
                    default:           bodyFn = () => [m("div", "Unknown type: " + type)];
                }
                return m("div", {
                    class: "cg2-card cg2-card-front cg2-card-" + type,
                    style: { borderColor: cfg.color }
                }, [
                    cornerIcon(type, "top-left"),
                    cornerIcon(type, "top-right"),
                    m("div", { class: "cg2-card-name", style: { color: cfg.color } }, card.name || "Unnamed"),
                    m("div", { class: "cg2-card-body" }, bodyFn(card)),
                    cornerIcon(type, "bottom-right")
                ]);
            }
        };
    }

    // ── CardBack Component ───────────────────────────────────────────
    function CardBack() {
        return {
            view(vnode) {
                let type = vnode.attrs.type || "item";
                let cfg = CARD_TYPES[type] || CARD_TYPES.item;
                return m("div", {
                    class: "cg2-card cg2-card-back",
                    style: { background: cfg.color, borderColor: cfg.color }
                }, [
                    m("div", { class: "cg2-back-pattern" }),
                    m("span", { class: "material-symbols-outlined cg2-back-icon" }, cfg.icon),
                    m("div", { class: "cg2-back-label" }, cfg.label)
                ]);
            }
        };
    }

    // ── Hardcoded Test Cards ─────────────────────────────────────────
    const TEST_CARDS = [
        {
            type: "character", name: "Aldric the Bold",
            race: "HUMAN", alignment: "LAWFUL_GOOD", level: 3,
            stats: { STR: 14, AGI: 10, END: 12, INT: 8, MAG: 6, CHA: 11 },
            needs: { hp: 16, energy: 6, morale: 20 },
            equipped: { head: null, body: { name: "Chain Mail" }, handL: null, handR: { name: "Iron Sword" }, feet: { name: "Boots" }, ring: null, back: null },
            activeSkills: [{ name: "Swordsmanship" }, null, null, null]
        },
        {
            type: "apparel", name: "Chain Mail",
            slot: "Body", rarity: "UNCOMMON", def: 3, hpBonus: 0,
            special: null, durability: 5,
            flavor: "Forged in the deep fires of the mountain hold."
        },
        {
            type: "item", subtype: "weapon", name: "Iron Sword",
            slot: "Hand (1H)", rarity: "COMMON", atk: 4, range: "Melee",
            damageType: "Slashing", requires: { STR: 8 },
            special: "Cleave (on kill, deal 2 dmg to adjacent foe)", durability: 8
        },
        {
            type: "item", subtype: "consumable", name: "Health Potion",
            rarity: "COMMON", effect: "Restore 8 HP"
        },
        {
            type: "action", name: "Attack",
            actionType: "Offensive",
            stackWith: "Character + Weapon (required) + Skill (optional)",
            roll: "1d20 + STR + ATK bonus vs target DEF",
            onHit: "Deal weapon ATK + STR modifier as damage",
            energyCost: 0
        },
        {
            type: "talk", name: "Talk",
            energyCost: 5
        },
        {
            type: "encounter", name: "Wolf Pack",
            subtype: "Threat", rarity: "UNCOMMON",
            difficulty: 8, atk: 3, def: 2, hp: 25,
            behavior: "Attacks lowest HP character first.",
            loot: ["Wolf Pelt", "Raw Meat"]
        },
        {
            type: "skill", name: "Swordsmanship",
            category: "Combat",
            modifier: "+2 to Attack rolls when using a Slashing weapon",
            requires: { STR: 10 }, tier: "MEDIEVAL"
        },
        {
            type: "magic", name: "Fireball",
            effectType: "Offensive", skillType: "Imperial",
            requires: { MAG: 12 },
            effect: "Deal 15 fire damage to target. Ignores 2 DEF.",
            stackWith: "Character + Action (Attack) + Imperial skill card",
            energyCost: 8, reusable: true
        }
    ];

    // ── Theme Config Loader ──────────────────────────────────────────
    const DEFAULT_THEME = {
        themeId: "high-fantasy",
        name: "High Fantasy",
        version: "1.0",
        artStyle: {
            promptSuffix: "high fantasy, vibrant colors, detailed illustration",
            colorPalette: {
                character: "#B8860B", apparel: "#808080", item: "#2E7D32",
                action: "#C62828", talk: "#1565C0", encounter: "#6A1B9A",
                skill: "#E65100", magic: "#00695C"
            }
        },
        balance: {
            hpMax: 20, moraleMax: 20,
            apFormula: "floor(END / 5) + 1",
            initiativeFormula: "1d20 + AGI"
        }
    };

    let activeTheme = DEFAULT_THEME;

    async function loadThemeConfig(themeId) {
        let id = themeId || "high-fantasy";
        try {
            let resp = await m.request({
                method: "GET",
                url: "/media/cardGame/" + id + ".json"
            });
            if (resp && resp.themeId) {
                activeTheme = resp;
                console.log("[CardGame v2] Theme loaded:", resp.themeId);
                return resp;
            }
        } catch (e) {
            console.warn("[CardGame v2] Theme config not found, using default. (" + id + ")", e);
        }
        activeTheme = DEFAULT_THEME;
        return DEFAULT_THEME;
    }

    // ── data.data CRUD Wrapper ───────────────────────────────────────
    const DECK_BASE_PATH = "~/CardGame";

    const deckStorage = {
        async save(deckName, data) {
            try {
                let groupPath = DECK_BASE_PATH + "/" + deckName;
                let grp = await page.makePath("auth.group", "DATA", groupPath);
                if (!grp) { console.error("[CardGame v2] Could not create group:", groupPath); return null; }
                let encoded = btoa(unescape(encodeURIComponent(JSON.stringify(data))));

                // Search for existing deck.json in this group
                let q = am7view.viewQuery("data.data");
                q.field("groupId", grp.id);
                q.field("name", "deck.json");
                let qr = await page.search(q);

                let saved;
                if (qr && qr.results && qr.results.length) {
                    let existing = qr.results[0];
                    existing.dataBytesStore = encoded;
                    existing.compressionType = "none";
                    saved = await page.patchObject(existing);
                } else {
                    let obj = am7model.newPrimitive("data.data");
                    obj.name = "deck.json";
                    obj.contentType = "application/json";
                    obj.compressionType = "none";
                    obj.groupId = grp.id;
                    obj.groupPath = grp.path;
                    obj.dataBytesStore = encoded;
                    saved = await page.createObject(obj);
                }
                console.log("[CardGame v2] Deck saved:", deckName, saved);
                return saved;
            } catch (e) {
                console.error("[CardGame v2] Failed to save deck:", deckName, e);
                return null;
            }
        },

        async load(deckName) {
            try {
                let groupPath = DECK_BASE_PATH + "/" + deckName;
                let grp = await page.makePath("auth.group", "DATA", groupPath);
                if (!grp) return null;
                let q = am7view.viewQuery("data.data");
                q.field("groupId", grp.id);
                q.field("name", "deck.json");
                let qr = await page.search(q);
                if (qr && qr.results && qr.results.length) {
                    let obj = qr.results[0];
                    if (obj.dataBytesStore) {
                        return JSON.parse(decodeURIComponent(escape(atob(obj.dataBytesStore))));
                    }
                }
                return null;
            } catch (e) {
                console.error("[CardGame v2] Failed to load deck:", deckName, e);
                return null;
            }
        },

        async list() {
            try {
                let grp = await page.makePath("auth.group", "DATA", DECK_BASE_PATH);
                if (!grp) return [];
                let q = am7view.viewQuery("auth.group");
                q.field("parentId", grp.id);
                let qr = await page.search(q);
                return (qr && qr.results ? qr.results : []).map(g => g.name);
            } catch (e) {
                console.error("[CardGame v2] Failed to list decks:", e);
                return [];
            }
        },

        async remove(deckName) {
            try {
                let groupPath = DECK_BASE_PATH + "/" + deckName;
                let grp = await page.findObject("auth.group", "DATA", groupPath);
                if (!grp) return false;
                await page.deleteObject("auth.group", grp.objectId);
                console.log("[CardGame v2] Deck removed:", deckName);
                return true;
            } catch (e) {
                console.error("[CardGame v2] Failed to remove deck:", deckName, e);
                return false;
            }
        }
    };

    let themeLoading = false;

    // ── Phase 2: Stat Mapping ────────────────────────────────────────
    // Map Olio charPerson.statistics fields → card game 6-stat model
    function mapStats(statistics) {
        if (!statistics) return { STR: 8, AGI: 8, END: 8, INT: 8, MAG: 8, CHA: 8 };
        return {
            STR: statistics.physicalStrength || 8,
            AGI: statistics.agility || 8,
            END: statistics.physicalEndurance || 8,
            INT: statistics.intelligence || 8,
            MAG: statistics.magic || 8,
            CHA: statistics.charisma || 8
        };
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
        return char.objectId || char.id || (char.entity && char.entity.objectId);
    }

    // ── Character Card Builder (single char → card data) ────────────
    function assembleCharacterCard(char) {
        let stats = mapStats(char.statistics);
        Object.keys(stats).forEach(k => { stats[k] = clampStat(stats[k]); });
        return {
            type: "character", name: char.name || "Unknown",
            race: (str(char.race) || "HUMAN").toUpperCase(),
            alignment: (str(char.alignment) || "NEUTRAL").replace(/_/g, " "),
            age: char.age || null,
            level: 1,
            stats,
            needs: { hp: 20, energy: stats.MAG, morale: 20 },
            equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
            activeSkills: [null, null, null, null],
            portraitUrl: getPortraitUrl(char, "256x256"),
            sourceId: getCharId(char)
        };
    }

    // ── Per-Character Equipment Cards ─────────────────────────────────
    function assembleCharEquipment(char) {
        let stats = mapStats(char.statistics);
        Object.keys(stats).forEach(k => { stats[k] = clampStat(stats[k]); });

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
                { type: "apparel", name: "Leather Armor", slot: "Body", rarity: "COMMON", def: 2, hpBonus: 0, durability: 5, special: null },
                { type: "apparel", name: "Worn Boots", slot: "Feet", rarity: "COMMON", def: 1, hpBonus: 0, durability: 4, special: null }
            );
        }

        let weaponCard = {
            type: "item", subtype: "weapon", name: "Short Sword",
            slot: "Hand (1H)", rarity: "COMMON", atk: 3, range: "Melee",
            damageType: "Slashing", requires: { STR: 6 }, special: null, durability: 8
        };
        if (stats.AGI > stats.STR) {
            weaponCard = {
                type: "item", subtype: "weapon", name: "Hunting Bow",
                slot: "Hand (2H)", rarity: "COMMON", atk: 3, range: "Ranged",
                damageType: "Piercing", requires: { AGI: 6 }, special: null, durability: 8
            };
        }

        let skillCards = [];
        if (stats.STR >= 10) {
            skillCards.push({ type: "skill", name: "Swordsmanship", category: "Combat", modifier: "+2 to Attack rolls with Slashing weapons", requires: { STR: 10 }, tier: "COMMON" });
        }
        if (stats.AGI >= 10) {
            skillCards.push({ type: "skill", name: "Quick Reflexes", category: "Defense", modifier: "+2 to Flee and initiative rolls", requires: { AGI: 10 }, tier: "COMMON" });
        }
        if (skillCards.length === 0) {
            skillCards.push({ type: "skill", name: "Survival", category: "Survival", modifier: "+1 to Investigate and Rest rolls", requires: {}, tier: "COMMON" });
        }

        let magicCards = [];
        if (stats.MAG >= 10) {
            magicCards.push({
                type: "magic", name: "Healing Light", effectType: "Restorative", skillType: "Imperial",
                requires: { MAG: 10 }, effect: "Restore 6 HP to self or ally",
                stackWith: "Character + Action (Use Item) + Imperial skill", energyCost: 6, reusable: true
            });
        }
        if (stats.INT >= 12) {
            magicCards.push({
                type: "magic", name: "Mind Read", effectType: "Discovery", skillType: "Psionic",
                requires: { INT: 12 }, effect: "View opponent's next planned action stack",
                stackWith: "Character + Action (Investigate)", energyCost: 4, reusable: true
            });
        }

        return { apparelCards, weaponCard, skillCards, magicCards };
    }

    // ── Starter Deck Assembly (multi-character) ───────────────────────
    function assembleStarterDeck(chars, theme) {
        if (!Array.isArray(chars)) chars = [chars];

        let allCards = [];
        let charNames = [];

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
        let consumables = [
            { type: "item", subtype: "consumable", name: "Ration", rarity: "COMMON", effect: "Restore 3 Energy" },
            { type: "item", subtype: "consumable", name: "Ration", rarity: "COMMON", effect: "Restore 3 Energy" },
            { type: "item", subtype: "consumable", name: "Health Potion", rarity: "COMMON", effect: "Restore 8 HP" },
            { type: "item", subtype: "consumable", name: "Bandage", rarity: "COMMON", effect: "Restore 4 HP" },
            { type: "item", subtype: "consumable", name: "Torch", rarity: "COMMON", effect: "+2 to Investigate rolls this round" }
        ];
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

        let deckName = charNames.length === 1 ? charNames[0] : charNames.slice(0, 3).join(", ") + (charNames.length > 3 ? " +" + (charNames.length - 3) : "");
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

    // ── Phase 2: View State ──────────────────────────────────────────
    let screen = "deckList";    // "deckList" | "builder" | "deckView"
    let builderStep = 1;        // 1=theme, 2=character, 3=review/build
    let availableCharacters = [];
    let charsLoading = false;
    let selectedChars = [];     // multi-select, up to 8
    let builtDeck = null;       // preview before saving
    let savedDecks = [];        // list of { deckName, characterName, portraitUrl, createdAt, cardCount }
    let decksLoading = false;
    let viewingDeck = null;     // loaded deck for viewing
    let buildingDeck = false;

    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

    // ── Load Characters ──────────────────────────────────────────────
    async function loadAvailableCharacters() {
        if (charsLoading) return;
        charsLoading = true;
        m.redraw();
        try {
            let popDir = await page.findObject("auth.group", "data", gridPath + "/Population");
            if (!popDir) {
                page.toast("warn", "Population directory not found");
                charsLoading = false; m.redraw();
                return;
            }
            let q = am7view.viewQuery("olio.charPerson");
            q.field("groupId", popDir.id);
            q.range(0, 50);
            q.entity.request.push("store", "statistics");
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length) {
                am7model.updateListModel(qr.results);
                availableCharacters = qr.results;
                console.log("[CardGame v2] Loaded " + availableCharacters.length + " characters");
            } else {
                availableCharacters = [];
                page.toast("info", "No characters found in population");
            }
        } catch (e) {
            console.error("[CardGame v2] Failed to load characters", e);
            page.toast("error", "Failed to load characters");
        }
        charsLoading = false;
        m.redraw();
    }

    // ── Load Saved Decks ─────────────────────────────────────────────
    async function loadSavedDecks() {
        if (decksLoading) return;
        decksLoading = true;
        m.redraw();
        try {
            let names = await deckStorage.list();
            let decks = [];
            for (let name of names) {
                let data = await deckStorage.load(name);
                if (data) {
                    decks.push({
                        deckName: data.deckName || name,
                        characterNames: data.characterNames || (data.characterName ? [data.characterName] : ["Unknown"]),
                        portraitUrl: data.portraitUrl || null,
                        createdAt: data.createdAt || null,
                        cardCount: data.cardCount || (data.cards ? data.cards.length : 0),
                        storageName: name
                    });
                }
            }
            savedDecks = decks;
            console.log("[CardGame v2] Loaded " + savedDecks.length + " saved decks");
            if (savedDecks.length === 0 && screen === "deckList" && !buildingDeck) {
                screen = "builder";
                builderStep = 1;
                selectedChars = [];
                builtDeck = null;
            }
        } catch (e) {
            console.error("[CardGame v2] Failed to load decks", e);
        }
        decksLoading = false;
        m.redraw();
    }

    // ── Save Deck ────────────────────────────────────────────────────
    async function saveDeck(deck) {
        if (!deck) return;
        buildingDeck = true;
        m.redraw();
        let safeName = (deck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
        let result = await deckStorage.save(safeName, deck);
        buildingDeck = false;
        if (result) {
            page.toast("success", "Deck saved: " + deck.deckName);
            screen = "deckList";
            builtDeck = null;
            selectedChars = [];
            m.redraw();
            await loadSavedDecks();
        } else {
            page.toast("error", "Failed to save deck");
            m.redraw();
        }
    }

    // ── Delete Deck ──────────────────────────────────────────────────
    async function deleteDeck(storageName) {
        let ok = await deckStorage.remove(storageName);
        if (ok) {
            page.toast("success", "Deck deleted");
            await loadSavedDecks();
        } else {
            page.toast("error", "Failed to delete deck");
        }
        m.redraw();
    }

    // ── View Deck ────────────────────────────────────────────────────
    async function viewDeck(storageName) {
        let data = await deckStorage.load(storageName);
        if (data) {
            viewingDeck = data;
            screen = "deckView";
        } else {
            page.toast("error", "Failed to load deck");
        }
        m.redraw();
    }

    // ── Deck List Component ──────────────────────────────────────────
    function DeckList() {
        return {
            view() {
                return m("div", [
                    m("div", { class: "cg2-toolbar" }, [
                        m("span", { style: { fontWeight: 700, fontSize: "16px" } }, "Your Decks"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            onclick() { screen = "builder"; builderStep = 1; selectedChars = []; builtDeck = null; m.redraw(); }
                        }, [m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "4px" } }, "add"), "New Deck"]),
                        decksLoading ? m("span", { class: "cg2-loading" }, "Loading...") : null
                    ]),
                    savedDecks.length === 0 && !decksLoading
                        ? m("div", { class: "cg2-empty-state" }, "No decks yet. Create one to get started.")
                        : m("div", { class: "cg2-deck-grid" },
                            savedDecks.map(d =>
                                m("div", { class: "cg2-deck-card" }, [
                                    m("div", { class: "cg2-deck-portrait" },
                                        d.portraitUrl
                                            ? m("img", { src: d.portraitUrl })
                                            : m("span", { class: "material-symbols-outlined", style: { fontSize: "48px", color: "#B8860B" } }, "person")
                                    ),
                                    m("div", { class: "cg2-deck-info" }, [
                                        m("div", { class: "cg2-deck-name" }, d.deckName),
                                        m("div", { class: "cg2-deck-meta" }, (d.characterNames || []).join(", ") + " \u2022 " + d.cardCount + " cards"),
                                        d.createdAt ? m("div", { class: "cg2-deck-meta" }, new Date(d.createdAt).toLocaleDateString()) : null
                                    ]),
                                    m("div", { class: "cg2-deck-actions" }, [
                                        m("button", { class: "cg2-btn", onclick: () => viewDeck(d.storageName) }, "View"),
                                        m("button", { class: "cg2-btn cg2-btn-danger", onclick: () => deleteDeck(d.storageName) }, "Delete")
                                    ])
                                ])
                            )
                        )
                ]);
            }
        };
    }

    // ── Builder Step 1: Theme Select ─────────────────────────────────
    function BuilderThemeStep() {
        return {
            view() {
                return m("div", [
                    m("div", { class: "cg2-section-title" }, "Step 1: Select Theme"),
                    m("div", { class: "cg2-theme-grid" }, [
                        themeOption("high-fantasy", "High Fantasy", "Classic high fantasy with vibrant magic and epic adventures", "auto_fix_high"),
                        themeOption("dark-medieval", "Dark Medieval", "Gritty medieval setting with low magic and high mortality", "skull"),
                        themeOption("sci-fi", "Sci-Fi", "Far-future space setting with psionic powers and tech items", "rocket_launch")
                    ]),
                    m("div", { class: "cg2-builder-nav" }, [
                        m("button", { class: "cg2-btn", onclick: () => { screen = "deckList"; m.redraw(); } }, "Cancel"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            onclick() { builderStep = 2; loadAvailableCharacters(); m.redraw(); }
                        }, "Next: Select Character \u2192")
                    ])
                ]);
            }
        };
    }

    function themeOption(id, name, desc, icon) {
        let selected = activeTheme.themeId === id;
        return m("div", {
            class: "cg2-theme-card" + (selected ? " cg2-theme-selected" : ""),
            onclick() {
                loadThemeConfig(id);
                m.redraw();
            }
        }, [
            m("span", { class: "material-symbols-outlined", style: { fontSize: "32px", color: selected ? "#B8860B" : "#888" } }, icon),
            m("div", { class: "cg2-theme-name" }, name),
            m("div", { class: "cg2-theme-desc" }, desc),
            selected ? m("div", { class: "cg2-theme-badge" }, "\u2713 Selected") : null
        ]);
    }

    // ── Builder Step 2: Character Picker ─────────────────────────────
    function BuilderCharacterStep() {
        return {
            view() {
                return m("div", [
                    m("div", { class: "cg2-section-title" }, "Step 2: Select Character for Deck"),
                    charsLoading
                        ? m("div", { class: "cg2-loading" }, "Loading characters...")
                        : m("div", { class: "cg2-char-grid" }, [
                            ...availableCharacters.map(ch => charPickerCard(ch)),
                            m("div", {
                                class: "cg2-char-card cg2-char-card-new",
                                onclick() { page.toast("info", "Random character generation requires server endpoint"); }
                            }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "48px", color: "#888" } }, "add_circle"),
                                m("div", { style: { fontWeight: 600, marginTop: "8px" } }, "New Random"),
                                m("div", { style: { fontSize: "10px", color: "#999" } }, "Generate Character")
                            ])
                        ]),
                    selectedChars.length ? [
                        m("div", { class: "cg2-section-title", style: { marginTop: "16px" } },
                            "Selected: " + selectedChars.length + " of 8 characters"),
                        m("div", { class: "cg2-selected-chips" },
                            selectedChars.map(ch => {
                                let portrait = getPortraitUrl(ch, "128x128");
                                return m("div", { class: "cg2-selected-chip", key: getCharId(ch) }, [
                                    portrait
                                        ? m("img", { src: portrait })
                                        : m("span", { class: "cg2-chip-icon" }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "person")),
                                    m("span", ch.name || "Unnamed"),
                                    m("span", {
                                        class: "cg2-chip-remove material-symbols-outlined",
                                        onclick(e) {
                                            e.stopPropagation();
                                            selectedChars = selectedChars.filter(s => getCharId(s) !== getCharId(ch));
                                            m.redraw();
                                        }
                                    }, "close")
                                ]);
                            })
                        )
                    ] : null,
                    m("div", { class: "cg2-builder-nav" }, [
                        m("button", { class: "cg2-btn", onclick: () => { builderStep = 1; m.redraw(); } }, "\u2190 Back"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: !selectedChars.length,
                            onclick() {
                                if (!selectedChars.length) return;
                                builtDeck = assembleStarterDeck(selectedChars, activeTheme);
                                builderStep = 3;
                                m.redraw();
                            }
                        }, "Next: Review Deck \u2192")
                    ])
                ]);
            }
        };
    }

    function charPickerCard(ch) {
        let portrait = getPortraitUrl(ch, "128x128");
        let stats = mapStats(ch.statistics);
        let chId = getCharId(ch);
        let isSelected = selectedChars.some(s => getCharId(s) === chId);
        return m("div", {
            class: "cg2-char-card" + (isSelected ? " cg2-char-selected" : ""),
            onclick() {
                if (isSelected) {
                    selectedChars = selectedChars.filter(s => getCharId(s) !== chId);
                } else if (selectedChars.length < 8) {
                    selectedChars = [...selectedChars, ch];
                } else {
                    page.toast("warn", "Maximum 8 characters per deck");
                }
                m.redraw();
            }
        }, [
            m("div", { class: "cg2-char-portrait" },
                portrait
                    ? m("img", { src: portrait })
                    : m("span", { class: "material-symbols-outlined", style: { fontSize: "48px", color: "#B8860B" } }, "person")
            ),
            m("div", { class: "cg2-char-name" }, ch.name || "Unnamed"),
            m("div", { class: "cg2-char-meta" }, [
                str(ch.gender).charAt(0).toUpperCase(),
                " ",
                str(ch.race).replace(/_/g, " "),
                ch.age ? ", " + ch.age : ""
            ]),
            m("div", { class: "cg2-char-stats-mini" }, [
                ["STR", "AGI", "END"].map(s => m("span", { key: s }, s + ":" + clampStat(stats[s]))),
                m("br"),
                ["INT", "MAG", "CHA"].map(s => m("span", { key: s }, s + ":" + clampStat(stats[s])))
            ]),
            isSelected ? m("div", { class: "cg2-char-badge" }, "\u2713") : null
        ]);
    }

    // ── Builder Step 3: Review & Build ───────────────────────────────
    function BuilderReviewStep() {
        return {
            view() {
                if (!builtDeck) return m("div", "No deck assembled");
                let cards = builtDeck.cards || [];
                let charCard = cards.find(c => c.type === "character");
                return m("div", [
                    m("div", { class: "cg2-section-title" }, "Step 3: Review & Build Deck"),
                    m("div", { class: "cg2-review-summary" }, [
                        m("span", { class: "cg2-review-stat" }, "Theme: " + (builtDeck.themeId || "high-fantasy")),
                        m("span", { class: "cg2-review-stat" }, "Characters: " + (builtDeck.characterNames || []).join(", ")),
                        m("span", { class: "cg2-review-stat" }, "Cards: " + cards.length),
                        charCard ? m("span", { class: "cg2-review-stat" }, "HP: " + charCard.needs.hp + " | NRG: " + charCard.needs.energy + " | MRL: " + charCard.needs.morale) : null
                    ]),
                    m("div", { class: "cg2-section-title" }, "All Cards"),
                    m("div", { class: "cg2-card-grid" },
                        cards.map((card, i) => m(CardFace, { key: card.name + "-" + i, card }))
                    ),
                    m("div", { class: "cg2-builder-nav" }, [
                        m("button", { class: "cg2-btn", onclick: () => { builderStep = 2; m.redraw(); } }, "\u2190 Back"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: buildingDeck,
                            onclick: () => saveDeck(builtDeck)
                        }, buildingDeck ? "Saving..." : "Build & Save Deck")
                    ])
                ]);
            }
        };
    }

    // ── Deck View Screen ─────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════
    // Phase 3 — Image Generation Pipeline
    // ══════════════════════════════════════════════════════════════════

    const ART_BASE_PATH = "~/CardGame/Art";

    // ── SD Config Template Cache ──────────────────────────────────────
    let sdConfigTemplate = null;
    async function getSdConfigTemplate() {
        if (sdConfigTemplate) return sdConfigTemplate;
        try {
            sdConfigTemplate = await m.request({
                method: "GET",
                url: g_application_path + "/rest/olio/randomImageConfig",
                withCredentials: true
            });
            console.log("[CardGame v2] SD config template loaded");
        } catch (e) {
            console.warn("[CardGame v2] Could not fetch randomImageConfig:", e);
            sdConfigTemplate = null;
        }
        return sdConfigTemplate;
    }

    // ── Prompt Builder ────────────────────────────────────────────────
    // Build SD prompts from card data + theme art style
    function buildCardPrompt(card, theme) {
        let suffix = (theme && theme.artStyle && theme.artStyle.promptSuffix) || "high fantasy, vibrant colors, detailed illustration";
        let prompt = "";
        switch (card.type) {
            case "character":
                prompt = "Portrait of " + (card.name || "a warrior") + ", " +
                    (card.race || "human").toLowerCase() + " " +
                    (card.alignment || "").toLowerCase() +
                    (card.age ? ", age " + card.age : "") +
                    ", RPG character card art, facing viewer";
                break;
            case "apparel":
                prompt = (card.name || "Armor") + ", " +
                    (card.slot || "body").toLowerCase() + " armor piece, " +
                    "RPG equipment item, detailed render on neutral background, item card art";
                break;
            case "item":
                if (card.subtype === "weapon") {
                    prompt = (card.name || "Weapon") + ", " +
                        (card.damageType || "").toLowerCase() + " " +
                        (card.range || "melee").toLowerCase() + " weapon, " +
                        "RPG weapon item, detailed render on neutral background, item card art";
                } else {
                    prompt = (card.name || "Potion") + ", " +
                        "RPG consumable item, glowing, detailed render on neutral background, item card art";
                }
                break;
            case "action":
                prompt = (card.name || "Action") + " action, " +
                    (card.actionType || "combat").toLowerCase() + ", " +
                    "dynamic scene, RPG card illustration, dramatic lighting";
                break;
            case "talk":
                prompt = "Two figures in conversation, " +
                    "social interaction, RPG card illustration, warm lighting";
                break;
            case "encounter":
                prompt = (card.name || "Monster") + ", " +
                    (card.subtype || "threat").toLowerCase() + ", " +
                    "RPG encounter, dramatic atmosphere, full body";
                break;
            case "skill":
                prompt = (card.name || "Skill") + " ability, " +
                    (card.category || "combat").toLowerCase() + " technique, " +
                    "RPG skill icon, stylized energy effect, card art";
                break;
            case "magic":
                prompt = (card.name || "Spell") + " spell effect, " +
                    (card.skillType || "arcane").toLowerCase() + " magic, " +
                    "magical energy, RPG spell card illustration, mystical atmosphere";
                break;
            default:
                prompt = (card.name || "Card") + ", RPG card illustration";
        }
        return prompt + ", " + suffix;
    }

    // ── Image Generation Queue ────────────────────────────────────────
    let artQueue = [];          // { card, cardIndex, status, result, error }
    let artProcessing = false;
    let artCompleted = 0;
    let artTotal = 0;
    let artPaused = false;
    let artDir = null;          // cached ~/CardGame/Art/{themeId} group
    let backgroundImageId = null;   // objectId of generated background (used as img2img basis)
    let backgroundThumbUrl = null;  // thumbnail URL of background
    let backgroundGenerating = false;

    async function ensureArtDir(themeId) {
        if (artDir) return artDir;
        let path = ART_BASE_PATH + "/" + (themeId || "high-fantasy");
        artDir = await page.makePath("auth.group", "DATA", path);
        return artDir;
    }

    // Generate a background image to use as img2img basis for card art
    async function generateBackground(theme) {
        let t = theme || activeTheme;
        let bgPrompt = (t.artStyle && t.artStyle.backgroundPrompt) || "Fantasy landscape, epic panoramic view, vibrant colors";
        let bgConfig = (t.artStyle && t.artStyle.backgroundConfig) || {};

        backgroundGenerating = true;
        m.redraw();

        try {
            let dir = await ensureArtDir(t.themeId);
            if (!dir) throw new Error("Could not create art directory");

            let template = await getSdConfigTemplate();
            if (!template) throw new Error("No SD config template available");

            let entity = Object.assign({}, template);
            entity.description = bgPrompt;
            entity.style = "art";
            entity.steps = bgConfig.steps || 40;
            entity.cfg = bgConfig.cfg || 7;
            entity.width = bgConfig.width || 1024;
            entity.height = bgConfig.height || 1024;
            entity.hires = bgConfig.hires !== undefined ? bgConfig.hires : true;
            entity.seed = -1;
            entity.groupPath = dir.path;
            entity.imageName = "background-" + t.themeId + "-" + Date.now() + ".png";

            let result = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/generateArt",
                body: entity,
                withCredentials: true,
                extract: function(xhr) {
                    let body = null;
                    try { body = JSON.parse(xhr.responseText); } catch(e) {}
                    if (xhr.status !== 200) {
                        throw new Error((body && body.error) || "HTTP " + xhr.status);
                    }
                    return body;
                }
            });

            if (!result || !result.objectId) throw new Error("Background generation returned no result");

            backgroundImageId = result.objectId;
            backgroundThumbUrl = g_application_path + "/thumbnail/" +
                am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + (result.groupPath || dir.path) + "/" + result.name + "/256x256";

            // Save background reference on the deck
            if (viewingDeck) {
                viewingDeck.backgroundImageId = backgroundImageId;
                viewingDeck.backgroundThumbUrl = backgroundThumbUrl;
                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                await deckStorage.save(safeName, viewingDeck);
            }

            page.toast("success", "Background generated");
            console.log("[CardGame v2] Background generated:", backgroundImageId);
        } catch (e) {
            console.error("[CardGame v2] Background generation failed:", e);
            page.toast("error", "Background failed: " + (e.message || "Unknown error"));
        }

        backgroundGenerating = false;
        m.redraw();
    }

    // Build a SD config entity for a card, merging theme per-type overrides
    async function buildSdEntity(card, theme) {
        let template = await getSdConfigTemplate();
        if (!template) throw new Error("No SD config template available");
        let entity = Object.assign({}, template);

        // Apply theme sdConfig defaults, then type-specific overrides
        let artCfg = (theme || activeTheme).artStyle && (theme || activeTheme).artStyle.sdConfig;
        if (artCfg) {
            if (artCfg["default"]) Object.assign(entity, artCfg["default"]);
            if (artCfg[card.type])  Object.assign(entity, artCfg[card.type]);
        } else {
            // Fallback when theme has no sdConfig
            entity.style = "art";
            entity.steps = 30;
            entity.cfg = 7;
            entity.width = card.type === "character" ? 768 : 512;
            entity.height = card.type === "character" ? 1024 : 512;
        }

        entity.description = buildCardPrompt(card, theme);
        entity.seed = -1;

        // Attach background reference image if available
        if (backgroundImageId && card.type !== "character") {
            entity.referenceImageId = backgroundImageId;
            // denoisingStrength may already be set by theme per-type config
            if (!entity.denoisingStrength) entity.denoisingStrength = 0.7;
        }

        return entity;
    }

    // Generate art for a single card — character portraits use the quick endpoint
    async function generateCardArt(card, theme) {
        // Character portraits: use the existing charPerson/reimage endpoint
        if (card.type === "character" && card.sourceId) {
            await m.request({
                method: "GET",
                url: g_application_path + "/rest/olio/charPerson/" + card.sourceId + "/reimage/false",
                withCredentials: true
            });
            // The portrait is updated on the charPerson server-side;
            // return a marker so the card can be refreshed
            return { reimaged: true, sourceId: card.sourceId };
        }

        // All other card types: use the generateArt endpoint (txt2img from prompt)
        let dir = await ensureArtDir((theme || activeTheme).themeId);
        if (!dir) throw new Error("Could not create art directory");

        let sdEntity = await buildSdEntity(card, theme);
        // Add groupPath and imageName for the generateArt endpoint
        sdEntity.groupPath = dir.path;
        sdEntity.imageName = (card.name || "card").replace(/[^a-zA-Z0-9_\-]/g, "_") + "-" + Date.now() + ".png";

        let result = await m.request({
            method: "POST",
            url: g_application_path + "/rest/olio/generateArt",
            body: sdEntity,
            withCredentials: true,
            extract: function(xhr) {
                let body = null;
                try { body = JSON.parse(xhr.responseText); } catch(e) {}
                if (xhr.status !== 200) {
                    throw new Error((body && body.error) || "HTTP " + xhr.status);
                }
                return body;
            }
        });

        if (!result || !result.objectId) throw new Error("generateArt returned no result");

        // Move result into art directory if server put it elsewhere
        if (result.groupId !== dir.id) {
            await page.moveObject(result, dir);
            result.groupId = dir.id;
            result.groupPath = dir.path;
        }

        // Build thumbnail URL for card use
        let thumbUrl = g_application_path + "/thumbnail/" +
            am7client.dotPath(am7client.currentOrganization) +
            "/data.data" + result.groupPath + "/" + result.name + "/256x256";

        return { objectId: result.objectId, name: result.name, groupPath: result.groupPath, thumbUrl: thumbUrl };
    }

    // Process the art queue one item at a time
    async function processArtQueue() {
        if (artProcessing || artPaused) return;
        let next = artQueue.find(j => j.status === "pending");
        if (!next) {
            artProcessing = false;
            if (artCompleted > 0) {
                page.toast("success", "Art generation complete: " + artCompleted + " of " + artTotal + " images");
                // Save deck with updated image URLs
                if (viewingDeck) {
                    let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                    await deckStorage.save(safeName, viewingDeck);
                    console.log("[CardGame v2] Deck saved with updated art URLs");
                }
            }
            m.redraw();
            return;
        }

        artProcessing = true;
        next.status = "processing";
        m.redraw();

        try {
            let result = await generateCardArt(next.card, activeTheme);
            next.status = "complete";
            next.result = result;
            artCompleted++;

            // Update the card in the deck with the image URL
            if (viewingDeck && viewingDeck.cards && viewingDeck.cards[next.cardIndex]) {
                let deckCard = viewingDeck.cards[next.cardIndex];
                if (result.thumbUrl) {
                    deckCard.imageUrl = result.thumbUrl;
                    deckCard.artObjectId = result.objectId;
                }
                if (result.reimaged) {
                    // Refresh portraitUrl with cache-buster so the renderer picks up the new image
                    if (deckCard.portraitUrl) {
                        deckCard.portraitUrl = deckCard.portraitUrl.split("?")[0] + "?t=" + Date.now();
                    }
                }
            }
        } catch (e) {
            console.error("[CardGame v2] Art generation failed for:", next.card.name, e);
            next.status = "failed";
            next.error = e.message || "Unknown error";
        }

        artProcessing = false;
        m.redraw();

        // Process next in queue
        processArtQueue();
    }

    // Queue art generation for all cards in a deck
    function queueDeckArt(deck) {
        if (!deck || !deck.cards) return;
        artQueue = [];
        artCompleted = 0;
        artTotal = 0;
        artPaused = false;
        artDir = null;

        deck.cards.forEach((card, i) => {
            // Skip cards that already have art
            if (card.type === "character" && card.portraitUrl) return;
            if (card.type !== "character" && card.imageUrl) return;

            artQueue.push({ card: card, cardIndex: i, status: "pending", result: null, error: null });
            artTotal++;
        });

        if (artTotal === 0) {
            page.toast("info", "All cards already have art");
            return;
        }

        console.log("[CardGame v2] Queued " + artTotal + " cards for art generation");
        processArtQueue();
    }

    function pauseArtQueue() {
        artPaused = true;
        m.redraw();
    }

    function resumeArtQueue() {
        artPaused = false;
        m.redraw();
        processArtQueue();
    }

    function cancelArtQueue() {
        artQueue.forEach(j => { if (j.status === "pending") j.status = "cancelled"; });
        artPaused = false;
        artProcessing = false;
        m.redraw();
    }

    // ── Art Queue Progress Bar Component ──────────────────────────────
    function ArtQueueProgress() {
        return {
            view() {
                if (artTotal === 0) return null;
                let pending = artQueue.filter(j => j.status === "pending").length;
                let processing = artQueue.filter(j => j.status === "processing").length;
                let failed = artQueue.filter(j => j.status === "failed").length;
                let done = artQueue.filter(j => j.status === "complete").length;
                let pct = artTotal > 0 ? Math.round((done / artTotal) * 100) : 0;
                let current = artQueue.find(j => j.status === "processing");
                let active = pending > 0 || processing > 0;

                return m("div", { class: "cg2-art-progress" }, [
                    m("div", { class: "cg2-art-progress-header" }, [
                        m("span", { style: { fontWeight: 700, fontSize: "13px" } },
                            active ? "Generating Art..." : (failed > 0 ? "Generation Complete (with errors)" : "Generation Complete")),
                        m("span", { style: { fontSize: "12px", color: "#888" } },
                            done + " / " + artTotal + (failed > 0 ? " (" + failed + " failed)" : ""))
                    ]),
                    m("div", { class: "cg2-art-progress-bar" }, [
                        m("div", { class: "cg2-art-progress-fill", style: { width: pct + "%" } })
                    ]),
                    current ? m("div", { class: "cg2-art-progress-current" }, [
                        m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px" } }, "progress_activity"),
                        " " + (current.card.name || "Card")
                    ]) : null,
                    active ? m("div", { class: "cg2-art-progress-actions" }, [
                        artPaused
                            ? m("button", { class: "cg2-btn", onclick: resumeArtQueue }, "Resume")
                            : m("button", { class: "cg2-btn", onclick: pauseArtQueue }, "Pause"),
                        m("button", { class: "cg2-btn cg2-btn-danger", onclick: cancelArtQueue }, "Cancel")
                    ]) : null,
                    // Show failed items with retry
                    failed > 0 ? m("div", { class: "cg2-art-failures" },
                        artQueue.filter(j => j.status === "failed").map(j =>
                            m("div", { class: "cg2-art-fail-item" }, [
                                m("span", j.card.name + ": " + (j.error || "Failed")),
                                m("button", {
                                    class: "cg2-btn",
                                    style: { fontSize: "10px", padding: "1px 6px" },
                                    onclick() { j.status = "pending"; j.error = null; processArtQueue(); }
                                }, "Retry")
                            ])
                        )
                    ) : null
                ]);
            }
        };
    }

    // ── Deck View Screen (updated with art generation) ────────────────
    function DeckView() {
        return {
            oninit() {
                // Restore background reference from saved deck
                if (viewingDeck && viewingDeck.backgroundImageId) {
                    backgroundImageId = viewingDeck.backgroundImageId;
                    backgroundThumbUrl = viewingDeck.backgroundThumbUrl || null;
                } else {
                    backgroundImageId = null;
                    backgroundThumbUrl = null;
                }
            },
            view() {
                if (!viewingDeck) return m("div", "No deck loaded");
                let cards = viewingDeck.cards || [];
                let queueActive = artQueue.some(j => j.status === "pending" || j.status === "processing");
                let busy = queueActive || backgroundGenerating;
                return m("div", [
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", { class: "cg2-btn", onclick: () => { screen = "deckList"; viewingDeck = null; cancelArtQueue(); artTotal = 0; backgroundImageId = null; backgroundThumbUrl = null; m.redraw(); } }, "\u2190 Back to Decks"),
                        m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, viewingDeck.deckName || "Deck"),
                        m("span", { style: { color: "#888", fontSize: "12px", marginLeft: "12px" } }, cards.length + " cards")
                    ]),
                    // Background panel
                    m("div", { class: "cg2-bg-panel" }, [
                        m("div", { class: "cg2-bg-panel-left" }, [
                            m("span", { style: { fontWeight: 700, fontSize: "13px" } }, "Background Basis"),
                            backgroundImageId
                                ? m("span", { style: { fontSize: "11px", color: "#2E7D32", marginLeft: "8px" } }, "Active")
                                : m("span", { style: { fontSize: "11px", color: "#888", marginLeft: "8px" } }, "None"),
                            backgroundThumbUrl ? m("img", {
                                src: backgroundThumbUrl, class: "cg2-bg-thumb"
                            }) : null
                        ]),
                        m("div", { class: "cg2-bg-panel-right" }, [
                            m("button", {
                                class: "cg2-btn",
                                disabled: busy,
                                onclick() { generateBackground(activeTheme); }
                            }, [
                                backgroundGenerating
                                    ? m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "progress_activity")
                                    : m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "landscape"),
                                backgroundGenerating ? "Generating..." : (backgroundImageId ? "Regenerate BG" : "Generate BG")
                            ]),
                            backgroundImageId ? m("button", {
                                class: "cg2-btn",
                                disabled: busy,
                                onclick() { backgroundImageId = null; backgroundThumbUrl = null; m.redraw(); }
                            }, "Clear BG") : null
                        ])
                    ]),
                    // Art toolbar
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: busy,
                            onclick() { queueDeckArt(viewingDeck); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "4px" } }, "auto_awesome"),
                            queueActive ? "Generating..." : "Generate Art"
                        ]),
                        m("button", {
                            class: "cg2-btn",
                            style: { marginLeft: "4px" },
                            disabled: busy,
                            onclick() {
                                cards.forEach(c => { delete c.imageUrl; delete c.artObjectId; delete c.portraitUrl; });
                                queueDeckArt(viewingDeck);
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "4px" } }, "refresh"),
                            "Regen All"
                        ]),
                        backgroundImageId
                            ? m("span", { style: { marginLeft: "auto", fontSize: "11px", color: "#666" } }, "Cards will use background as img2img basis")
                            : null
                    ]),
                    // Art generation progress
                    m(ArtQueueProgress),
                    // Card grid
                    m("div", { class: "cg2-card-grid" },
                        cards.map((card, i) => {
                            let artJob = artQueue.find(j => j.cardIndex === i);
                            let isGenerating = artJob && artJob.status === "processing";
                            let hasArt = card.imageUrl || (card.type === "character" && card.portraitUrl);
                            return m("div", { class: "cg2-card-art-wrapper", key: card.name + "-" + i }, [
                                m(CardFace, { card }),
                                hasArt ? m("div", { class: "cg2-card-art-badge" }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "12px" } }, "image"),
                                    " Art"
                                ]) : null,
                                isGenerating ? m("div", { class: "cg2-card-art-overlay" }, [
                                    m("span", { class: "material-symbols-outlined cg2-spin" }, "progress_activity"),
                                    m("div", "Generating...")
                                ]) : null,
                                artJob && artJob.status === "failed" ? m("div", { class: "cg2-card-art-overlay cg2-art-overlay-fail" }, [
                                    m("span", { class: "material-symbols-outlined" }, "error"),
                                    m("div", { style: { fontSize: "10px" } }, artJob.error || "Failed")
                                ]) : null,
                                !busy ? m("button", {
                                    class: "cg2-card-reimage-btn cg2-card-reimage-btn-visible",
                                    title: buildCardPrompt(card, activeTheme),
                                    onclick() {
                                        delete card.imageUrl;
                                        delete card.artObjectId;
                                        if (card.type === "character") delete card.portraitUrl;
                                        artQueue = [{ card, cardIndex: i, status: "pending", result: null, error: null }];
                                        artCompleted = 0;
                                        artTotal = 1;
                                        artPaused = false;
                                        artDir = null;
                                        processArtQueue();
                                    }
                                }, m("span", { class: "material-symbols-outlined", style: { fontSize: "16px" } }, "auto_awesome")) : null
                            ]);
                        })
                    )
                ]);
            }
        };
    }

    // ── Main View ────────────────────────────────────────────────────
    let fullMode = false;
    function toggleFullMode() {
        fullMode = !fullMode;
        m.redraw();
    }

    let cardGame = {
        oninit() {
            if (!themeLoading) {
                themeLoading = true;
                loadThemeConfig("high-fantasy");
            }
            loadSavedDecks();
        },
        view() {
            if (!page.authenticated()) return m("");
            return m("div", { class: "content-outer" }, [
                fullMode ? "" : m(page.components.navigation),
                m("div", { class: "content-main" }, [
                    m("div", { class: "cg2-container" }, [
                        // Header
                        m("div", { class: "cg2-toolbar" }, [
                            page.iconButton("button mr-4", fullMode ? "close_fullscreen" : "open_in_new", "", toggleFullMode),
                            m("span", { style: { fontWeight: 700, fontSize: "16px", marginRight: "16px" } }, "Card Game"),
                            m("span", { style: { marginLeft: "auto", fontSize: "11px", color: "#999" } },
                                "Theme: " + activeTheme.name
                            )
                        ]),

                        // Screen router
                        screen === "deckList" ? m(DeckList) : null,
                        screen === "builder" ? [
                            // Builder step indicator
                            m("div", { class: "cg2-toolbar" }, [
                                [1, 2, 3].map(s =>
                                    m("span", {
                                        style: {
                                            padding: "3px 10px", borderRadius: "12px", fontSize: "12px", cursor: "pointer",
                                            background: builderStep === s ? "#B8860B" : "transparent",
                                            color: builderStep === s ? "#fff" : "#888",
                                            fontWeight: builderStep === s ? "700" : "400"
                                        },
                                        onclick: () => { if (s < builderStep) { builderStep = s; m.redraw(); } }
                                    }, s + ". " + ["Theme", "Character", "Review"][s - 1])
                                )
                            ]),
                            builderStep === 1 ? m(BuilderThemeStep) : null,
                            builderStep === 2 ? m(BuilderCharacterStep) : null,
                            builderStep === 3 ? m(BuilderReviewStep) : null
                        ] : null,
                        screen === "deckView" ? m(DeckView) : null
                    ])
                ]),
                page.components.dialog.loadDialog(),
                page.loadToast()
            ]);
        }
    };

    // ── Register View ────────────────────────────────────────────────
    page.views.cardGameV2 = cardGame;

    // ── Export for later phases ───────────────────────────────────────
    page.cardGameV2 = {
        CARD_TYPES,
        CardFace,
        CardBack,
        NeedBar,
        StatBlock,
        deckStorage,
        loadThemeConfig,
        activeTheme: () => activeTheme,
        assembleStarterDeck,
        mapStats,
        getPortraitUrl,
        TEST_CARDS,
        // Phase 3
        queueDeckArt,
        generateCardArt,
        buildCardPrompt,
        artQueue: () => artQueue
    };

}());
