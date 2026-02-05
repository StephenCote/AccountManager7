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

    // Template/utility art types (card front, card back, background)
    const TEMPLATE_TYPES = {
        cardFront:  { color: "#795548", icon: "crop_portrait", label: "Card Front" },
        cardBack:   { color: "#546E7A", icon: "flip_to_back",  label: "Card Back" },
        background: { color: "#33691E", icon: "landscape",     label: "Background" }
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
        return [
            m("div", { class: "cg2-card-portrait" }, [
                card.portraitUrl
                    ? m("img", {
                        src: card.portraitUrl, class: "cg2-portrait-img",
                        style: { cursor: "pointer" },
                        onclick(e) { e.stopPropagation(); showImagePreview(card.portraitUrl); }
                    })
                    : m("span", { class: "material-symbols-outlined", style: { fontSize: "80px", color: "#B8860B" } }, "person"),
                card.race ? m("span", { class: "cg2-race-badge" }, card.race) : null
            ]),
            m("div", { class: "cg2-card-subtitle" }, [
                (card.gender ? card.gender.charAt(0).toUpperCase() + " " : "") + (card.race || "") + (card.age ? ", " + card.age : "") + (card.alignment ? " / " + card.alignment : ""),
                card.level ? m("span", { class: "cg2-level" }, "Lv " + card.level) : null
            ]),
            m("div", { class: "cg2-divider" }),
            m(StatBlock, { stats }),
            m("div", { style: { marginTop: "auto", textAlign: "center", fontSize: "10px", color: "#999", fontStyle: "italic" } }, "click to flip")
        ];
    }

    function renderCharacterBackBody(card, bgImage) {
        let stats = card.stats || {};
        let needs = card.needs || {};
        let equip = card.equipped || {};
        let skills = card.activeSkills || [];
        let backStyle = { borderColor: "#B8860B" };
        if (bgImage) {
            backStyle.backgroundImage = "url('" + bgImage + "')";
            backStyle.backgroundSize = "cover";
            backStyle.backgroundPosition = "center";
        }
        return m("div", { class: "cg2-card cg2-card-front cg2-char-back", style: backStyle }, [
            cornerIcon("character", "top-left"),
            cornerIcon("character", "top-right"),
            m("div", { class: "cg2-char-back-title" }, card.name || "Unknown"),
            m("div", { class: "cg2-card-body" }, [
                m(NeedBar, { label: "Health", abbrev: "HP", current: needs.hp || 20, max: 20, color: "#e53935" }),
                m(NeedBar, { label: "Energy", abbrev: "NRG", current: needs.energy || 14, max: stats.MAG || 14, color: "#1E88E5" }),
                m(NeedBar, { label: "Morale", abbrev: "MRL", current: needs.morale || 20, max: 20, color: "#43A047" }),
                m("div", { class: "cg2-divider" }),
                m("div", { class: "cg2-equip-row" }, [
                    m("span", { class: "cg2-section-label" }, "Skill Slots:"),
                    m("span", skills.map(s => m("span", { class: "cg2-slot" + (s ? " cg2-slot-filled" : "") }, s ? s.name : "\u2013")))
                ]),
                m("div", { class: "cg2-equip-row" }, [
                    m("span", { class: "cg2-section-label" }, "Equip:"),
                    m("span", ["head", "body", "handL", "handR", "feet", "ring", "back"].map(slot =>
                        m("span", {
                            class: "cg2-slot" + (equip[slot] ? " cg2-slot-filled" : ""),
                            title: slot
                        }, equip[slot] ? equip[slot].name : slot.charAt(0).toUpperCase())
                    ))
                ]),
                m("div", { style: { marginTop: "auto", textAlign: "center", fontSize: "10px", color: "#999", fontStyle: "italic" } }, "click to flip")
            ]),
            cornerIcon("character", "bottom-right")
        ]);
    }

    function renderApparelBody(card) {
        return [
            m("div", { class: "cg2-card-image-area" },
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
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
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
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
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
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
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#C62828" } }, "bolt")
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
                card.imageUrl
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#1565C0" } }, "chat_bubble")
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
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
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
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
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
                    ? m("img", { src: card.imageUrl, class: "cg2-card-img", style: { cursor: "pointer" }, onclick(e) { e.stopPropagation(); showImagePreview(card.imageUrl); } })
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
                let cardStyle = { borderColor: cfg.color };
                if (vnode.attrs.bgImage) {
                    cardStyle.backgroundImage = "linear-gradient(rgba(255,255,255,0.5),rgba(255,255,255,0.5)), url('" + vnode.attrs.bgImage + "')";
                    cardStyle.backgroundSize = "cover, cover";
                    cardStyle.backgroundPosition = "center, center";
                }
                return m("div", {
                    class: "cg2-card cg2-card-front cg2-card-" + type,
                    style: cardStyle
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
        }
    };

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
                // Merge theme defaults for backgroundPrompt/promptSuffix if not in JSON
                let td = THEME_DEFAULTS[resp.themeId];
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
        let td = THEME_DEFAULTS[id];
        if (td && id !== "high-fantasy") {
            activeTheme = {
                themeId: id,
                name: td.name,
                version: "1.0",
                artStyle: {
                    backgroundPrompt: td.backgroundPrompt,
                    promptSuffix: td.promptSuffix,
                    colorPalette: DEFAULT_THEME.artStyle.colorPalette
                },
                balance: DEFAULT_THEME.balance
            };
        } else {
            activeTheme = DEFAULT_THEME;
        }
        return activeTheme;
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
        return char.objectId || char.id || (char.entity && char.entity.objectId);
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
        if (!card.sourceId) return false;
        let fresh = await fetchCharPerson(card.sourceId);
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
        card.portraitUrl = getPortraitUrl(fresh, "256x256");
        if (card.portraitUrl) card.portraitUrl += "?t=" + Date.now();
        return true;
    }

    // ── Character Card Builder (single char → card data) ────────────
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
    let deckNameInput = "";     // user-editable deck name

    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

    // ── Load Characters ──────────────────────────────────────────────
    async function loadAvailableCharacters() {
        if (charsLoading) return;
        charsLoading = true;
        m.redraw();
        try {
            let allChars = [];
            let seenIds = new Set();

            // Load from Olio Population
            let popDir = await page.findObject("auth.group", "data", gridPath + "/Population");
            if (popDir) {
                let q = am7view.viewQuery("olio.charPerson");
                q.field("groupId", popDir.id);
                q.range(0, 50);
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
                    console.log("[CardGame v2] Loaded " + qr.results.length + " characters from Population");
                }
            } else {
                console.warn("[CardGame v2] Population directory not found");
            }

            // Load custom characters from ~/Characters
            try {
                let customDir = await page.findObject("auth.group", "data", "~/Characters");
                if (customDir) {
                    let cq = am7view.viewQuery("olio.charPerson");
                    cq.field("groupId", customDir.id);
                    cq.range(0, 50);
                    cq.entity.request.push("profile", "store", "statistics");
                    let cqr = await page.search(cq);
                    if (cqr && cqr.results && cqr.results.length) {
                        am7model.updateListModel(cqr.results);
                        let customCount = 0;
                        cqr.results.forEach(ch => {
                            let cid = getCharId(ch);
                            if (cid && !seenIds.has(cid)) {
                                seenIds.add(cid);
                                ch._customChar = true;
                                allChars.push(ch);
                                customCount++;
                            }
                        });
                        console.log("[CardGame v2] Loaded " + customCount + " custom characters from ~/Characters");
                    }
                }
            } catch (ce) {
                console.warn("[CardGame v2] Could not load ~/Characters:", ce);
            }

            // Resolve partially-loaded statistics for all characters
            for (let ch of allChars) {
                await resolveStatistics(ch);
            }

            availableCharacters = allChars;
            if (allChars.length === 0) {
                page.toast("info", "No characters found");
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
                        themeId: data.themeId || null,
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
            builtDeck = null;
            selectedChars = [];
            m.redraw();
            await viewDeck(safeName);
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
            cardFrontImageUrl = data.cardFrontImageUrl || null;
            cardBackImageUrl = data.cardBackImageUrl || null;
            flippedCards = {};
            artDir = null;
            // Load the deck's theme so prompts use the correct suffix
            if (data.themeId) {
                await loadThemeConfig(data.themeId);
            }
            screen = "deckView";
            m.redraw();
            // Auto-refresh character cards from source objects
            await refreshAllCharacters(data);
        } else {
            page.toast("error", "Failed to load deck");
        }
        m.redraw();
    }

    async function refreshAllCharacters(deck) {
        if (!deck || !deck.cards) return;
        let charCards = deck.cards.filter(c => c.type === "character" && c.sourceId);
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

    // ── Deck List Component ──────────────────────────────────────────
    function DeckList() {
        return {
            view() {
                return m("div", [
                    m("div", { class: "cg2-toolbar" }, [
                        m("span", { style: { fontWeight: 700, fontSize: "16px" } }, "Your Decks"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            onclick() { screen = "builder"; builderStep = 1; selectedChars = []; builtDeck = null; deckNameInput = ""; m.redraw(); }
                        }, [m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "4px" } }, "add"), "New Deck"]),
                        decksLoading ? m("span", { class: "cg2-loading" }, "Loading...") : null
                    ]),
                    savedDecks.length === 0 && !decksLoading
                        ? m("div", { class: "cg2-empty-state" }, "No decks yet. Create one to get started.")
                        : m("div", { class: "cg2-deck-grid" },
                            savedDecks.map(d => {
                                let themeName = d.themeId ? d.themeId.replace(/-/g, " ").replace(/\b\w/g, c => c.toUpperCase()) : null;
                                return m("div", { class: "cg2-deck-card" }, [
                                    m("div", { class: "cg2-deck-portrait" },
                                        d.portraitUrl
                                            ? m("img", { src: d.portraitUrl })
                                            : m("span", { class: "material-symbols-outlined", style: { fontSize: "48px", color: "#B8860B" } }, "playing_cards")
                                    ),
                                    m("div", { class: "cg2-deck-info" }, [
                                        m("div", { class: "cg2-deck-name" }, d.deckName),
                                        themeName ? m("div", { class: "cg2-deck-theme-badge" }, [
                                            m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", verticalAlign: "middle", marginRight: "3px" } }, "auto_fix_high"),
                                            themeName
                                        ]) : null,
                                        m("div", { class: "cg2-deck-meta" }, (d.characterNames || []).join(", ") + " \u2022 " + d.cardCount + " cards"),
                                        d.createdAt ? m("div", { class: "cg2-deck-meta" }, new Date(d.createdAt).toLocaleDateString()) : null
                                    ]),
                                    m("div", { class: "cg2-deck-actions" }, [
                                        m("button", { class: "cg2-btn", onclick: () => viewDeck(d.storageName) }, "View"),
                                        m("button", { class: "cg2-btn cg2-btn-danger", onclick: () => deleteDeck(d.storageName) }, "Delete")
                                    ])
                                ]);
                            }
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
                        themeOption("sci-fi", "Sci-Fi", "Far-future space setting with psionic powers and tech items", "rocket_launch"),
                        themeOption("post-apocalypse", "Post Apocalypse", "Harsh wasteland survival with scavenged gear and mutant threats", "destruction")
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
                            disabled: !selectedChars.length || applyingOutfits,
                            async onclick() {
                                if (!selectedChars.length || applyingOutfits) return;
                                builtDeck = assembleStarterDeck(selectedChars, activeTheme);
                                deckNameInput = builtDeck.deckName || "";
                                if (activeTheme && activeTheme.outfits) {
                                    await applyThemeOutfits(builtDeck, activeTheme, true);
                                }
                                builderStep = 3;
                                m.redraw();
                            }
                        }, applyingOutfits ? "Applying Outfits..." : "Next: Review Deck \u2192")
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
            isSelected ? m("div", { class: "cg2-char-badge" }, "\u2713") : null,
            ch._customChar ? m("div", { class: "cg2-char-source-badge" }, "Custom") : null
        ]);
    }

    // ── Builder Step 3: Review & Build ───────────────────────────────
    // ── Card Editor State (inline CRUD) ─────────────────────────────
    let editingCard = null;         // card object being edited (null = not editing)
    let editingCardIndex = -1;      // index in builtDeck.cards (-1 = new card)
    let addCardTypeOpen = false;    // dropdown open for "Add Card" type selector

    // Card type templates for new cards
    let CARD_TEMPLATES = {
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
        }
    };

    function startAddCard(templateKey) {
        editingCard = JSON.parse(JSON.stringify(CARD_TEMPLATES[templateKey]));
        editingCardIndex = -1;
        addCardTypeOpen = false;
        m.redraw();
    }

    function startEditCard(card, index) {
        editingCard = JSON.parse(JSON.stringify(card));
        editingCardIndex = index;
        m.redraw();
    }

    function saveEditingCard() {
        if (!editingCard || !builtDeck) return;
        // Build display name from fabric + name if fabric present
        if (editingCard.fabric && editingCard.name && !editingCard.name.startsWith(editingCard.fabric)) {
            editingCard._displayName = editingCard.fabric + " " + editingCard.name;
        }
        if (editingCardIndex >= 0) {
            builtDeck.cards[editingCardIndex] = editingCard;
        } else {
            builtDeck.cards.push(editingCard);
        }
        builtDeck.cardCount = builtDeck.cards.length;
        editingCard = null;
        editingCardIndex = -1;
        m.redraw();
    }

    function cancelEditingCard() {
        editingCard = null;
        editingCardIndex = -1;
        m.redraw();
    }

    function deleteCard(index) {
        if (!builtDeck) return;
        builtDeck.cards.splice(index, 1);
        builtDeck.cardCount = builtDeck.cards.length;
        m.redraw();
    }

    // Simple field editor for card properties
    function cardField(label, key, opts) {
        opts = opts || {};
        let val = editingCard[key];
        if (opts.type === "number") {
            return m("div", { class: "cg2-edit-field" }, [
                m("label", label),
                m("input", {
                    type: "number", value: val || 0,
                    min: opts.min || 0, max: opts.max || 999,
                    oninput(e) { editingCard[key] = parseFloat(e.target.value) || 0; }
                })
            ]);
        }
        if (opts.type === "select") {
            return m("div", { class: "cg2-edit-field" }, [
                m("label", label),
                m("select", {
                    value: val || "",
                    onchange(e) { editingCard[key] = e.target.value; }
                }, (opts.options || []).map(o => m("option", { value: o }, o)))
            ]);
        }
        // Default: text
        return m("div", { class: "cg2-edit-field" }, [
            m("label", label),
            m("input", {
                type: "text", value: val || "",
                placeholder: opts.placeholder || "",
                oninput(e) { editingCard[key] = e.target.value; }
            })
        ]);
    }

    // Render form fields based on card type
    function CardEditorFields() {
        return {
            view() {
                if (!editingCard) return null;
                let t = editingCard.type;
                let sub = editingCard.subtype;
                let fields = [
                    cardField("Name", "name", { placeholder: "Card name" })
                ];

                if (t === "action") {
                    fields.push(
                        cardField("Action Type", "actionType", { type: "select", options: ["Offensive", "Movement", "Discovery", "Social", "Recovery", "Utility", "Creation"] }),
                        cardField("Stack With", "stackWith", { placeholder: "e.g. Character + Weapon" }),
                        cardField("Roll", "roll", { placeholder: "e.g. 1d20 + STR" }),
                        cardField("On Hit", "onHit", { placeholder: "Effect on success" }),
                        cardField("Energy Cost", "energyCost", { type: "number", min: 0, max: 20 })
                    );
                } else if (t === "item" && sub === "weapon") {
                    fields.push(
                        cardField("Material", "fabric", { placeholder: "e.g. steel, iron" }),
                        cardField("Slot", "slot", { type: "select", options: ["Hand (1H)", "Hand (2H)"] }),
                        cardField("Rarity", "rarity", { type: "select", options: ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"] }),
                        cardField("ATK", "atk", { type: "number", min: 0, max: 20 }),
                        cardField("Range", "range", { type: "select", options: ["Melee", "Ranged", "Reach"] }),
                        cardField("Damage Type", "damageType", { type: "select", options: ["Slashing", "Piercing", "Bludgeoning", "Fire", "Ice", "Lightning", "Poison"] }),
                        cardField("Special", "special", { placeholder: "Special ability (optional)" }),
                        cardField("Durability", "durability", { type: "number", min: 1, max: 99 })
                    );
                } else if (t === "item" && sub === "consumable") {
                    fields.push(
                        cardField("Material", "fabric", { placeholder: "e.g. herbal, glass" }),
                        cardField("Rarity", "rarity", { type: "select", options: ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"] }),
                        cardField("Effect", "effect", { placeholder: "e.g. Restore 8 HP" })
                    );
                } else if (t === "apparel") {
                    fields.push(
                        cardField("Material", "fabric", { placeholder: "e.g. leather, wool" }),
                        cardField("Slot", "slot", { type: "select", options: ["Head", "Body", "Feet", "Back", "Ring", "Hand"] }),
                        cardField("Rarity", "rarity", { type: "select", options: ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"] }),
                        cardField("DEF", "def", { type: "number", min: 0, max: 20 }),
                        cardField("HP Bonus", "hpBonus", { type: "number", min: 0, max: 20 }),
                        cardField("Special", "special", { placeholder: "Special ability (optional)" }),
                        cardField("Durability", "durability", { type: "number", min: 1, max: 99 })
                    );
                } else if (t === "skill") {
                    fields.push(
                        cardField("Category", "category", { type: "select", options: ["Combat", "Defense", "Survival", "Social", "Crafting", "Discovery", "Magic"] }),
                        cardField("Modifier", "modifier", { placeholder: "e.g. +2 to Attack rolls" }),
                        cardField("Tier", "tier", { type: "select", options: ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"] })
                    );
                } else if (t === "magic") {
                    fields.push(
                        cardField("Effect Type", "effectType", { type: "select", options: ["Offensive", "Defensive", "Restorative", "Discovery", "Utility"] }),
                        cardField("Skill Type", "skillType", { type: "select", options: ["Imperial", "Undead", "Psionic", "Elemental", "Nature"] }),
                        cardField("Effect", "effect", { placeholder: "e.g. Deal 6 fire damage" }),
                        cardField("Stack With", "stackWith", { placeholder: "e.g. Character + Action" }),
                        cardField("Energy Cost", "energyCost", { type: "number", min: 0, max: 20 }),
                        cardField("Reusable", "reusable", { type: "select", options: ["true", "false"] })
                    );
                }

                return m("div", { class: "cg2-card-editor" }, [
                    m("div", { class: "cg2-card-editor-header" }, [
                        m("span", { style: { fontWeight: 700, fontSize: "14px" } },
                            editingCardIndex >= 0 ? "Edit Card" : "Add Card"),
                        m("span", {
                            class: "material-symbols-outlined",
                            style: { cursor: "pointer", fontSize: "18px", color: "#888" },
                            onclick: cancelEditingCard
                        }, "close")
                    ]),
                    m("div", { class: "cg2-card-editor-fields" }, fields),
                    m("div", { class: "cg2-card-editor-actions" }, [
                        m("button", { class: "cg2-btn", onclick: cancelEditingCard }, "Cancel"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: !editingCard.name || !editingCard.name.trim(),
                            onclick: saveEditingCard
                        }, editingCardIndex >= 0 ? "Update" : "Add to Deck")
                    ])
                ]);
            }
        };
    }

    function BuilderReviewStep() {
        return {
            view() {
                if (!builtDeck) return m("div", "No deck assembled");
                let cards = builtDeck.cards || [];
                let charCard = cards.find(c => c.type === "character");
                return m("div", [
                    m("div", { class: "cg2-section-title" }, "Step 3: Review & Build Deck"),
                    m("div", { class: "cg2-deck-name-row" }, [
                        m("label", { class: "cg2-deck-name-label" }, "Deck Name"),
                        m("input", {
                            class: "cg2-deck-name-input",
                            type: "text",
                            value: deckNameInput,
                            oninput(e) { deckNameInput = e.target.value; },
                            placeholder: "Enter a name for this deck"
                        })
                    ]),
                    m("div", { class: "cg2-review-summary" }, [
                        m("span", { class: "cg2-review-stat" }, "Theme: " + (builtDeck.themeId || "high-fantasy")),
                        m("span", { class: "cg2-review-stat" }, "Characters: " + (builtDeck.characterNames || []).join(", ")),
                        m("span", { class: "cg2-review-stat" }, "Cards: " + cards.length),
                        charCard ? m("span", { class: "cg2-review-stat" }, "HP: " + charCard.needs.hp + " | NRG: " + charCard.needs.energy + " | MRL: " + charCard.needs.morale) : null
                    ]),
                    // Add Card toolbar
                    m("div", { class: "cg2-toolbar", style: { justifyContent: "space-between" } }, [
                        m("div", { class: "cg2-section-title", style: { margin: 0 } }, "All Cards"),
                        m("div", { style: { position: "relative" } }, [
                            m("button", {
                                class: "cg2-btn cg2-btn-primary",
                                style: { fontSize: "11px" },
                                onclick() { addCardTypeOpen = !addCardTypeOpen; m.redraw(); }
                            }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "add"),
                                "Add Card"
                            ]),
                            addCardTypeOpen ? m("div", { class: "cg2-add-card-dropdown" },
                                [
                                    { key: "action", label: "Action", icon: "bolt", color: CARD_TYPES.action.color },
                                    { key: "item-weapon", label: "Weapon", icon: "swords", color: CARD_TYPES.item.color },
                                    { key: "item-consumable", label: "Consumable", icon: "science", color: CARD_TYPES.item.color },
                                    { key: "apparel", label: "Apparel", icon: "checkroom", color: CARD_TYPES.apparel.color },
                                    { key: "skill", label: "Skill", icon: "star", color: CARD_TYPES.skill.color },
                                    { key: "magic", label: "Magic Effect", icon: "auto_fix_high", color: CARD_TYPES.magic.color }
                                ].map(item =>
                                    m("div", {
                                        class: "cg2-add-card-option",
                                        onclick() { startAddCard(item.key); }
                                    }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", color: item.color, marginRight: "6px" } }, item.icon),
                                        item.label
                                    ])
                                )
                            ) : null
                        ])
                    ]),
                    // Card editor panel (shown when editing/adding)
                    editingCard ? m(CardEditorFields) : null,
                    // Card grid with edit/delete overlays
                    m("div", { class: "cg2-card-grid" },
                        cards.map((card, i) => {
                            let isCharacter = card.type === "character";
                            return m("div", { class: "cg2-card-art-wrapper", key: card.name + "-" + i }, [
                                m(CardFace, { card }),
                                !isCharacter ? m("div", { class: "cg2-card-edit-actions" }, [
                                    m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Edit card",
                                        onclick(e) { e.stopPropagation(); startEditCard(card, i); }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "edit")),
                                    m("button", {
                                        class: "cg2-card-action-btn cg2-card-action-btn-danger",
                                        title: "Remove card",
                                        onclick(e) { e.stopPropagation(); deleteCard(i); }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "delete"))
                                ]) : null
                            ]);
                        })
                    ),
                    m("div", { class: "cg2-builder-nav" }, [
                        m("button", { class: "cg2-btn", onclick: () => { builderStep = 2; m.redraw(); } }, "\u2190 Back"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: buildingDeck || !deckNameInput.trim(),
                            onclick() {
                                builtDeck.deckName = deckNameInput.trim();
                                saveDeck(builtDeck);
                            }
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

    // SD config template fetch delegated to am7sd common utility (sdConfig.js)

    // ── Prompt Builder ────────────────────────────────────────────────
    // Build SD prompts from card data + theme art style
    function styleLabel(style) {
        switch (style) {
            case "photograph": return "photograph";
            case "movie":      return "movie still";
            case "selfie":     return "selfie";
            case "anime":      return "anime illustration";
            case "portrait":   return "studio portrait";
            case "comic":      return "comic book illustration";
            case "digitalArt": return "digital artwork";
            case "fashion":    return "fashion photograph";
            case "vintage":    return "vintage photograph";
            case "art":        return "painting";
            default:           return "illustration";
        }
    }

    function buildCardPrompt(card, theme, style, customSuffix) {
        let suffix = customSuffix ||
            (theme && theme.artStyle && theme.artStyle.promptSuffix) ||
            "high fantasy, vibrant colors, detailed illustration";

        // Character cards — server builds its own prompt from person data
        if (card.type === "character") {
            let prompt = "Portrait of " + (card.name || "a warrior") + ", " +
                (card.race || "human").toLowerCase() + " " +
                (card.alignment || "").toLowerCase() +
                (card.age ? ", age " + card.age : "") +
                ", RPG character card art, facing viewer";
            return prompt + ", " + suffix;
        }

        // All other card types: emphasize type+name with double parentheses for SD weighting
        // Combine material + name for display (material is in fabric/material field, not in name)
        let sLabel = styleLabel(style);
        let baseName = card.name || card.type;
        let typeName = (card.fabric || card.material) ? (card.fabric || card.material) + " " + baseName : baseName;
        return "Close-up " + sLabel + " of ((" + card.type + " " + typeName + ")) in " + suffix;
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
    let backgroundPrompt = null;    // prompt used for the background (used as imageSetting for character cards)
    let backgroundGenerating = false;
    let flippedCards = {};          // { cardIndex: true } for character cards showing back
    let cardFrontImageUrl = null;   // background image for card faces
    let cardBackImageUrl = null;    // background image for card backs

    // Show image preview overlay
    let previewImageUrl = null;
    function showImagePreview(url) {
        if (!url) return;
        // Upgrade to larger thumbnail size for preview
        previewImageUrl = url.replace(/\/\d+x\d+/, "/512x512");
        m.redraw();
    }
    function closeImagePreview() {
        previewImageUrl = null;
        m.redraw();
    }
    function ImagePreviewOverlay() {
        return {
            view() {
                if (!previewImageUrl) return null;
                return m("div", {
                    style: {
                        position: "fixed", top: 0, left: 0, right: 0, bottom: 0,
                        background: "rgba(0,0,0,0.8)", zIndex: 9999,
                        display: "flex", alignItems: "center", justifyContent: "center",
                        cursor: "pointer"
                    },
                    onclick: closeImagePreview
                }, [
                    m("img", {
                        src: previewImageUrl,
                        style: { maxWidth: "90vw", maxHeight: "90vh", objectFit: "contain", borderRadius: "8px", boxShadow: "0 4px 24px rgba(0,0,0,0.5)" }
                    }),
                    m("span", {
                        class: "material-symbols-outlined",
                        style: { position: "absolute", top: "16px", right: "16px", color: "#fff", fontSize: "32px", cursor: "pointer" }
                    }, "close")
                ]);
            }
        };
    }

    // ── Gallery Picker (pick portrait from charPerson image gallery) ──
    let galleryPickerCard = null;    // card being picked for
    let galleryImages = [];
    let galleryLoading = false;

    async function openGalleryPicker(card) {
        if (!card || !card.sourceId) return;
        galleryLoading = true;
        galleryPickerCard = card;
        galleryImages = [];
        m.redraw();
        try {
            let char = await fetchCharPerson(card.sourceId);
            if (char && char.profile && char.profile.portrait && char.profile.portrait.groupId) {
                let q = am7client.newQuery("data.data");
                q.field("groupId", char.profile.portrait.groupId);
                q.entity.request.push("id", "objectId", "name", "groupId", "groupPath", "contentType");
                q.range(0, 100);
                q.sort("createdDate");
                q.order("descending");
                let qr = await page.search(q);
                if (qr && qr.results) {
                    galleryImages = qr.results.filter(r => r.contentType && r.contentType.match(/^image\//i));
                }
            }
            if (!galleryImages.length) {
                page.toast("warn", "No gallery images found for " + card.name);
                galleryPickerCard = null;
            }
        } catch (e) {
            console.error("[CardGame v2] Gallery load failed", e);
            page.toast("error", "Failed to load gallery");
            galleryPickerCard = null;
        }
        galleryLoading = false;
        m.redraw();
    }

    function closeGalleryPicker() {
        galleryPickerCard = null;
        galleryImages = [];
        m.redraw();
    }

    // ── Image Sequence Generation (apparel dress-up progression) ─────
    let sequenceCardId = null;   // sourceId of card currently sequencing
    let sequenceProgress = "";

    async function generateImageSequence(card) {
        if (!card || !card.sourceId || sequenceCardId) return;
        sequenceCardId = card.sourceId;
        sequenceProgress = "Loading character...";
        m.redraw();

        try {
            let char = await fetchCharPerson(card.sourceId);
            if (!char) throw new Error("Could not load character");

            // Find active (inuse) apparel from the store via members API
            let sto = char.store;
            if (!sto) throw new Error("Character has no store");

            am7client.clearCache("olio.store");
            let storeApparelList = await new Promise(res => {
                am7client.members("olio.store", sto.objectId, "olio.apparel", 0, 50, res);
            });
            if (!storeApparelList || !storeApparelList.length) {
                throw new Error("No apparel found in store");
            }

            // Find the inuse apparel (prefer first inuse, fallback to first)
            let activeApparel = storeApparelList.find(a => a.inuse) || storeApparelList[0];

            // Load full apparel details
            am7client.clearCache("olio.apparel");
            let aq = am7view.viewQuery("olio.apparel");
            aq.field("objectId", activeApparel.objectId);
            let aqr = await page.search(aq);
            if (!aqr || !aqr.results || !aqr.results.length) {
                throw new Error("Could not load apparel details");
            }
            let app = aqr.results[0];
            if (!app.wearables || !app.wearables.length) {
                throw new Error("No wearables found in apparel");
            }

            // Load full wearable details
            let wq = am7view.viewQuery("olio.wearable");
            wq.range(0, 20);
            let woids = app.wearables.map(w => w.objectId).join(",");
            wq.field("groupId", app.wearables[0].groupId);
            let wfld = wq.field("objectId", woids);
            wfld.comparator = "in";
            let wqr = await page.search(wq);
            if (!wqr || !wqr.results || !wqr.results.length) {
                throw new Error("Could not load wearable details");
            }
            let wears = wqr.results;

            // Get sorted unique wear levels
            let lvls = [...new Set(wears.sort((a, b) => {
                let aL = am7model.enums.wearLevelEnumType.findIndex(we => we == a.level.toUpperCase());
                let bL = am7model.enums.wearLevelEnumType.findIndex(we => we == b.level.toUpperCase());
                return aL < bL ? -1 : aL > bL ? 1 : 0;
            }).map(w => w.level.toUpperCase()))];

            if (!lvls.length) throw new Error("No wear levels found");

            // Dress down completely
            sequenceProgress = "Dressing down...";
            m.redraw();
            let dressedDown = true;
            while (dressedDown === true) {
                dressedDown = await am7olio.dressApparel(activeApparel, false);
            }

            let images = [];
            let totalImages = lvls.length;
            let baseSeed = -1;

            // Build base SD entity for character reimage
            let sdBase = await buildSdEntity(card, activeTheme);

            // Generate image at each wear level
            for (let i = 0; i < lvls.length; i++) {
                // Dress up one level
                await am7olio.dressApparel(activeApparel, true);

                sequenceProgress = "Generating image " + (i + 1) + " of " + totalImages + " (" + lvls[i] + ")...";
                m.redraw();

                let useSeed = (images.length === 0) ? baseSeed : (parseInt(baseSeed) + images.length);
                let sdEntity = Object.assign({}, sdBase);
                sdEntity.seed = useSeed;

                let x = await m.request({
                    method: "POST",
                    url: g_application_path + "/rest/olio/olio.charPerson/" + card.sourceId + "/reimage",
                    body: sdEntity,
                    withCredentials: true
                });

                if (!x) {
                    page.toast("error", "Failed to create image at level " + lvls[i]);
                    break;
                }

                // Extract seed from first image if random
                if (images.length === 0) {
                    let seedAttr = (x.attributes || []).filter(a => a.name == "seed");
                    if (seedAttr.length) baseSeed = seedAttr[0].value;
                }

                // Tag image with wear level
                let levelIndex = am7model.enums.wearLevelEnumType.findIndex(we => we == lvls[i]);
                await am7client.patchAttribute(x, "wearLevel", lvls[i] + "/" + levelIndex);

                images.push(x);
            }

            sequenceProgress = "";
            sequenceCardId = null;
            m.redraw();

            if (images.length > 0) {
                page.toast("success", "Created " + images.length + " images for " + card.name);
                // Refresh the card portrait to latest image
                await refreshCharacterCard(card);
                m.redraw();
                // Open the gallery picker so user can choose a portrait
                openGalleryPicker(card);
            } else {
                page.toast("warn", "No images were generated");
            }

        } catch (e) {
            console.error("[CardGame v2] Image sequence failed:", e);
            page.toast("error", "Image sequence failed: " + e.message);
            sequenceProgress = "";
            sequenceCardId = null;
            m.redraw();
        }
    }

    function GalleryPickerOverlay() {
        return {
            view() {
                if (!galleryPickerCard) return null;
                let orgPath = am7client.dotPath(am7client.currentOrganization);
                return m("div", {
                    style: {
                        position: "fixed", top: 0, left: 0, right: 0, bottom: 0,
                        background: "rgba(0,0,0,0.8)", zIndex: 9999,
                        display: "flex", alignItems: "center", justifyContent: "center",
                        cursor: "default"
                    },
                    onclick: closeGalleryPicker
                }, [
                    m("div", {
                        style: {
                            background: "#fff", borderRadius: "12px", padding: "16px",
                            maxWidth: "600px", maxHeight: "80vh", overflow: "auto",
                            boxShadow: "0 8px 32px rgba(0,0,0,0.4)"
                        },
                        onclick(e) { e.stopPropagation(); }
                    }, [
                        m("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "12px" } }, [
                            m("span", { style: { fontWeight: 700, fontSize: "14px" } }, "Pick Portrait: " + (galleryPickerCard.name || "Character")),
                            m("span", {
                                class: "material-symbols-outlined",
                                style: { cursor: "pointer", fontSize: "20px", color: "#888" },
                                onclick: closeGalleryPicker
                            }, "close")
                        ]),
                        galleryLoading
                            ? m("div", { style: { textAlign: "center", padding: "24px" } }, [
                                m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "24px" } }, "progress_activity"),
                                " Loading..."
                            ])
                            : m("div", { style: { display: "flex", flexWrap: "wrap", gap: "8px" } },
                                galleryImages.map(function (img) {
                                    let src = g_application_path + "/thumbnail/" + orgPath + "/data.data" + img.groupPath + "/" + img.name + "/96x96";
                                    let fullSrc = g_application_path + "/thumbnail/" + orgPath + "/data.data" + img.groupPath + "/" + img.name + "/256x256";
                                    let selected = galleryPickerCard.portraitUrl && galleryPickerCard.portraitUrl.indexOf("/" + img.name + "/") !== -1;
                                    return m("img", {
                                        src: src,
                                        width: 96, height: 96,
                                        style: {
                                            objectFit: "cover", borderRadius: "6px", cursor: "pointer",
                                            border: "3px solid " + (selected ? "#B8860B" : "transparent"),
                                            transition: "border-color 0.15s"
                                        },
                                        title: img.name,
                                        onclick: function () {
                                            galleryPickerCard.portraitUrl = fullSrc + "?t=" + Date.now();
                                            // Save deck with new portrait
                                            if (viewingDeck) {
                                                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                                                deckStorage.save(safeName, viewingDeck);
                                            }
                                            closeGalleryPicker();
                                            page.toast("success", "Portrait updated");
                                        }
                                    });
                                })
                            )
                    ])
                ]);
            }
        };
    }

    // ── SD Config Overrides ────────────────────────────────────────────
    // Each override tab is a proper olio.sd.config instance with model defaults,
    // rendered via the standard form system (am7model.prepareInstance + forms.sdConfigOverrides).
    // Card-type tabs inherit from _default — only fields the user explicitly
    // changed (different from _default) are applied as type-specific overrides.

    function newSdOverride() {
        return am7model.newPrimitive("olio.sd.config");
    }
    function defaultSdOverrides() {
        // Only _default is pre-populated; other tabs lazily inherit from _default
        // when first visited (see getSdOverrideInst)
        return { _default: newSdOverride() };
    }
    let sdOverrides = defaultSdOverrides();
    let sdConfigExpanded = false;
    let sdConfigTab = "_default";

    // Return only fields where the card-type override differs from _default.
    // This prevents card-type model defaults from overwriting _default settings.
    function getCardTypeDelta(key) {
        let ov = sdOverrides[key];
        let def = sdOverrides._default;
        if (!ov || !def || key === "_default") return ov;
        let delta = {};
        let hasChanges = false;
        for (let k in ov) {
            if (k === am7model.jsonModelKey) continue;
            if (ov[k] !== def[k]) {
                delta[k] = ov[k];
                hasChanges = true;
            }
        }
        return hasChanges ? delta : null;
    }

    // Prepared instances for form rendering — keyed by tab name
    let sdOverrideInsts = {};
    let sdOverrideViews = {};
    function getSdOverrideInst(key) {
        if (!sdOverrideInsts[key]) {
            let entity = sdOverrides[key];
            // For non-default tabs without saved data, start from current _default
            if (!entity && key !== "_default" && sdOverrides._default) {
                entity = JSON.parse(JSON.stringify(sdOverrides._default));
                entity[am7model.jsonModelKey] = "olio.sd.config";
                sdOverrides[key] = entity;
            }
            if (!entity || !entity[am7model.jsonModelKey]) {
                entity = am7model.prepareEntity(entity || {}, "olio.sd.config");
                sdOverrides[key] = entity;
            }
            sdOverrideInsts[key] = am7model.prepareInstance(entity, am7model.forms.sdConfigOverrides);
            sdOverrideViews[key] = page.views.object();
        }
        return sdOverrideInsts[key];
    }
    function resetSdOverrideInsts() {
        sdOverrideInsts = {};
        sdOverrideViews = {};
    }

    function currentDeckSafeName() {
        return viewingDeck && viewingDeck.deckName
            ? viewingDeck.deckName.replace(/[^a-zA-Z0-9_\-]/g, "_")
            : null;
    }

    async function ensureArtDir(themeId) {
        if (artDir) return artDir;
        let deckKey = currentDeckSafeName();
        let subdir = deckKey || (themeId || "high-fantasy");
        let path = ART_BASE_PATH + "/" + subdir;
        artDir = await page.makePath("auth.group", "DATA", path);
        return artDir;
    }

    // Generate a background image to use as img2img basis for card art
    async function generateBackground(theme) {
        let t = theme || activeTheme;
        let bgOv = sdOverrides.background;
        // Use the background override prompt if set, otherwise fall back to theme or default
        let bgPrompt = (bgOv && bgOv.description) || (t.artStyle && t.artStyle.backgroundPrompt) || "Fantasy landscape, epic panoramic view, vibrant colors";

        backgroundGenerating = true;
        m.redraw();

        try {
            let dir = await ensureArtDir(t.themeId);
            if (!dir) throw new Error("Could not create art directory");

            let entity = await am7sd.buildEntity({
                description: bgPrompt,
                bodyStyle: "landscape",
                seed: -1,
                groupPath: dir.path,
                imageName: "background-" + t.themeId + "-" + Date.now() + ".png"
            });
            am7sd.applyOverrides(entity, sdOverrides._default);
            am7sd.applyOverrides(entity, getCardTypeDelta("background"));
            am7sd.fillStyleDefaults(entity);

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
            backgroundPrompt = bgPrompt;
            backgroundThumbUrl = g_application_path + "/thumbnail/" +
                am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + (result.groupPath || dir.path) + "/" + result.name + "/256x256";

            // Save background reference on the deck
            if (viewingDeck) {
                viewingDeck.backgroundImageId = backgroundImageId;
                viewingDeck.backgroundPrompt = backgroundPrompt;
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

    // Build a SD config entity for a card, merging theme per-type overrides.
    // Uses am7sd to fetch the random template (with full style fields intact)
    // and apply overrides without destroying style-specific prompt composition.
    async function buildSdEntity(card, theme) {
        let entity = await am7sd.buildEntity();
        let t = theme || activeTheme;

        // Apply theme sdConfig defaults, then type-specific overrides
        let artCfg = t.artStyle && t.artStyle.sdConfig;
        if (artCfg) {
            if (artCfg["default"]) Object.assign(entity, artCfg["default"]);
            if (artCfg[card.type])  Object.assign(entity, artCfg[card.type]);
        }

        entity.seed = -1;

        // Apply user SD overrides first so style is final before building prompt
        am7sd.applyOverrides(entity, sdOverrides._default);
        am7sd.applyOverrides(entity, getCardTypeDelta(card.type));
        am7sd.fillStyleDefaults(entity);

        // Tab-specific or default prompt used as the "in ___" suffix
        let typeOv = sdOverrides[card.type];
        let defOv = sdOverrides._default;
        let customSuffix = (typeOv && typeOv.description) || (defOv && defOv.description) || null;
        entity.description = buildCardPrompt(card, t, entity.style, customSuffix);

        // For character cards, the server builds its own prompt from person data
        // and uses imageSetting for the scene/location.
        // Default to the theme's background prompt so characters match the setting.
        if (card.type === "character") {
            let setting = backgroundPrompt ||
                          (t.artStyle && t.artStyle.backgroundPrompt);
            if (setting) {
                entity.imageSetting = setting;
            }
        }

        // Attach background reference image if available
        if (backgroundImageId) {
            entity.referenceImageId = backgroundImageId;
            if (!entity.denoisingStrength || entity.denoisingStrength >= 1.0) {
                entity.denoisingStrength = 0.7;
            }
        }

        console.log("[CardGame v2] buildSdEntity:", card.type, card.name,
            "style:", entity.style, "imageSetting:", entity.imageSetting,
            "cfg:", entity.cfg, "denoise:", entity.denoisingStrength,
            "model:", entity.model, "ref:", entity.referenceImageId || "none",
            "w:", entity.width, "h:", entity.height);

        return entity;
    }

    // Generate art for a single card — character portraits use the reimage endpoint
    async function generateCardArt(card, theme) {
        // Character portraits: use the charPerson/reimage endpoint with SD config
        if (card.type === "character" && card.sourceId) {
            let sdEntity = await buildSdEntity(card, theme);
            // POST with sdConfig so the server can apply referenceImageId, model, style, etc.
            await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/charPerson/" + card.sourceId + "/reimage",
                body: sdEntity,
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

        console.log("[CardGame v2] generateArt response:", JSON.stringify({
            objectId: result && result.objectId, name: result && result.name,
            groupPath: result && result.groupPath, groupId: result && result.groupId
        }));

        if (!result || !result.objectId) throw new Error("generateArt returned no result");

        // Move result into art directory if server put it elsewhere
        if (result.groupId && dir.id && result.groupId !== dir.id) {
            await page.moveObject(result, dir);
            result.groupId = dir.id;
            result.groupPath = dir.path;
        }

        // Build thumbnail URL for card use — use dir.path as fallback
        let gp = result.groupPath || dir.path;
        let rname = result.name || sdEntity.imageName;
        let thumbUrl = g_application_path + "/thumbnail/" +
            am7client.dotPath(am7client.currentOrganization) +
            "/data.data" + gp + "/" + rname + "/256x256?t=" + Date.now();

        console.log("[CardGame v2] Card art URL:", thumbUrl);
        return { objectId: result.objectId, name: rname, groupPath: gp, thumbUrl: thumbUrl };
    }

    // Generate template art for card front/back backgrounds
    async function generateTemplateArt(side) {
        let theme = activeTheme;
        let configKey = side === "front" ? "cardFront" : "cardBack";
        let ov = sdOverrides[configKey];
        let defOv = sdOverrides._default;

        // Use tab-specific prompt, then _default prompt, then hardcoded fallback
        let prompt;
        if (ov && ov.description) {
            prompt = ov.description;
        } else if (defOv && defOv.description) {
            prompt = defOv.description;
        } else {
            let suffix = (theme.artStyle && theme.artStyle.promptSuffix) || "fantasy illustration";
            prompt = side === "front"
                ? "ornate card face design, decorative parchment border, golden filigree frame, card game template, " + suffix
                : "ornate card back design, intricate repeating pattern, card game template, " + suffix;
        }

        backgroundGenerating = true;
        m.redraw();
        try {
            let dir = await ensureArtDir(theme.themeId);
            if (!dir) throw new Error("Could not create art directory");
            let sdEntity = await am7sd.buildEntity({
                description: prompt,
                seed: -1,
                imageName: "card-" + side + "-" + (theme.themeId || "default") + "-" + Date.now() + ".png",
                groupPath: dir.path
            });
            am7sd.applyOverrides(sdEntity, sdOverrides._default);
            am7sd.applyOverrides(sdEntity, getCardTypeDelta(configKey));
            am7sd.fillStyleDefaults(sdEntity);
            if (backgroundImageId) {
                sdEntity.referenceImageId = backgroundImageId;
                if (!sdEntity.denoisingStrength || sdEntity.denoisingStrength >= 1.0) sdEntity.denoisingStrength = 0.7;
            }

            console.log("[CardGame v2] generateTemplateArt:", side, configKey, sdEntity);

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

            if (!result || !result.objectId) throw new Error("Template art returned no result");

            let gp = result.groupPath || dir.path;
            let rname = result.name || sdEntity.imageName;
            let thumbUrl = g_application_path + "/thumbnail/" +
                am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + gp + "/" + rname + "/512x768?t=" + Date.now();
            if (side === "front") {
                cardFrontImageUrl = thumbUrl;
                if (viewingDeck) viewingDeck.cardFrontImageUrl = thumbUrl;
            } else {
                cardBackImageUrl = thumbUrl;
                if (viewingDeck) viewingDeck.cardBackImageUrl = thumbUrl;
            }
            // Auto-save deck with new template URLs
            if (viewingDeck) {
                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                await deckStorage.save(safeName, viewingDeck);
            }
            page.toast("success", "Card " + side + " art generated");
        } catch (e) {
            console.error("[CardGame v2] Template art failed:", e);
            page.toast("error", "Card " + side + " art failed: " + (e.message || "Unknown error"));
        }
        backgroundGenerating = false;
        m.redraw();
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
                if (result.reimaged && deckCard.sourceId) {
                    // Re-fetch the charPerson to get the updated portrait
                    await refreshCharacterCard(deckCard);
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

    // ── Theme Apparel Swap ─────────────────────────────────────────────
    // Lookup colors from /Library/Colors, cache results
    let colorCache = {};
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
        q.field("name", key);
        q.range(0, 1);
        let qr = await page.search(q);
        if (qr && qr.results && qr.results.length) {
            colorCache[key] = { id: qr.results[0].id };
            return colorCache[key];
        }
        console.warn("[CardGame v2] Color not found:", key);
        return null;
    }

    let applyingOutfits = false;
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

        for (let card of charCards) {
            try {
                page.toast("info", "Outfitting " + card.name + "...", -1);
                let char = await fetchCharPerson(card.sourceId);
                if (!char) {
                    console.warn("[CardGame v2] Could not fetch char:", card.sourceId);
                    continue;
                }

                // Determine gender for outfit selection
                let gender = str(char.gender).toLowerCase();
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

                // 1. Load store to get its objectId (store IS a directory container)
                let storeObjId = char.store ? char.store.objectId : null;
                let sto = storeObjId ? await page.searchFirst("olio.store", undefined, undefined, storeObjId) : null;
                if (!sto) {
                    console.warn("[CardGame v2] No store for char:", card.name);
                    continue;
                }

                // 2. Deactivate current apparel — use members API for full list (no pagination limit)
                am7client.clearCache("olio.store");
                let storeApparelList = await new Promise(res => {
                    am7client.members("olio.store", storeObjId, "olio.apparel", 0, 50, res);
                });
                if (storeApparelList && storeApparelList.length) {
                    for (let apRef of storeApparelList) {
                        am7client.clearCache("olio.apparel");
                        let aq = am7view.viewQuery("olio.apparel");
                        aq.field("objectId", apRef.objectId);
                        let aqr = await page.search(aq);
                        if (aqr && aqr.results && aqr.results.length) {
                            let fullAp = aqr.results[0];
                            if (fullAp.inuse) {
                                if (fullAp.wearables && fullAp.wearables.length) {
                                    let wq = am7view.viewQuery("olio.wearable");
                                    wq.range(0, 20);
                                    let woids = fullAp.wearables.map(w => w.objectId).join(",");
                                    wq.field("groupId", fullAp.wearables[0].groupId);
                                    let wfld = wq.field("objectId", woids);
                                    wfld.comparator = "in";
                                    let wqr = await page.search(wq);
                                    if (wqr && wqr.results) {
                                        let patches = wqr.results
                                            .filter(w => w.inuse)
                                            .map(w => page.patchObject({ schema: "olio.wearable", id: w.id, inuse: false }));
                                        await Promise.all(patches);
                                    }
                                }
                                await page.patchObject({ schema: "olio.apparel", id: fullAp.id, inuse: false });
                            }
                        }
                    }
                }

                // 3. Create apparel FIRST in the store group (store likeInherits data.directory)
                let apparel = {
                    schema: "olio.apparel",
                    groupId: sto.objectId,
                    name: outfitDef.name,
                    type: outfitDef.type || "casual",
                    gender: outfitDef.gender || gender
                };
                let createdApparel = await page.createObject(apparel);
                if (!createdApparel) {
                    console.warn("[CardGame v2] Failed to create apparel for", card.name);
                    continue;
                }

                // 4. Create wearables in the apparel group (apparel likeInherits data.directory)
                let createdWearables = [];
                for (let wDef of outfitDef.wearables) {
                    let colorRef = await lookupColor(wDef.color);
                    let wearable = {
                        schema: "olio.wearable",
                        groupId: createdApparel.objectId,
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

                // 6. Link apparel to store via membership
                await page.member("olio.store", storeObjId, "olio.apparel", createdApparel.objectId, true);

                // 7. Patch inuse = true on all wearables and the apparel
                let inusePatches = createdWearables.map(cw =>
                    page.patchObject({ schema: "olio.wearable", id: cw.id, inuse: true })
                );
                inusePatches.push(
                    page.patchObject({ schema: "olio.apparel", id: createdApparel.id, inuse: true })
                );
                await Promise.all(inusePatches);

                // 8. Clear caches so subsequent reads reflect new state
                await am7client.clearCache("olio.wearable");
                await am7client.clearCache("olio.apparel");
                await am7client.clearCache("olio.store");

                // 9. Refresh character card data
                await refreshCharacterCard(card);
                processed++;
                console.log("[CardGame v2] Applied " + cat + " outfit to " + card.name);

            } catch (e) {
                console.error("[CardGame v2] Failed to outfit " + card.name, e);
                page.toast("error", "Failed to outfit " + card.name);
            }
        }

        applyingOutfits = false;
        if (processed > 0) {
            if (!skipSave) {
                let safeName = (deck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                await deckStorage.save(safeName, deck);
            }
            page.toast("success", "Applied outfits to " + processed + " character(s)");
        }
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
                    backgroundPrompt = viewingDeck.backgroundPrompt || null;
                    backgroundThumbUrl = viewingDeck.backgroundThumbUrl || null;
                } else {
                    backgroundImageId = null;
                    backgroundPrompt = null;
                    backgroundThumbUrl = null;
                }
                // Restore SD overrides from saved deck
                if (viewingDeck && viewingDeck.sdOverrides) {
                    let saved = viewingDeck.sdOverrides;
                    sdOverrides = { _default: newSdOverride() };
                    // Merge saved _default with model defaults
                    if (saved._default) Object.assign(sdOverrides._default, saved._default);
                    // Restore any explicitly-saved non-default tabs
                    Object.keys(saved).forEach(k => {
                        if (k !== "_default" && saved[k]) {
                            let copy = JSON.parse(JSON.stringify(saved[k]));
                            copy[am7model.jsonModelKey] = "olio.sd.config";
                            sdOverrides[k] = copy;
                        }
                    });
                } else {
                    sdOverrides = defaultSdOverrides();
                }
                resetSdOverrideInsts();
                sdConfigExpanded = false;
                sdConfigTab = "_default";
                flippedCards = {};
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
                        viewingDeck.themeId ? m("span", { class: "cg2-deck-theme-badge", style: { marginLeft: "8px" } }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", verticalAlign: "middle", marginRight: "3px" } }, "auto_fix_high"),
                            viewingDeck.themeId.replace(/-/g, " ").replace(/\b\w/g, c => c.toUpperCase())
                        ]) : null,
                        m("span", { style: { color: "#888", fontSize: "12px", marginLeft: "12px" } }, cards.length + " cards"),
                        m("button", {
                            class: "cg2-btn", style: { marginLeft: "auto", fontSize: "11px" },
                            disabled: busy,
                            title: "Re-fetch all character data from source objects",
                            async onclick() {
                                await refreshAllCharacters(viewingDeck);
                                page.toast("success", "Character data refreshed");
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "sync"),
                            "Refresh Data"
                        ]),
                        m("button", {
                            class: "cg2-btn", style: { fontSize: "11px", marginLeft: "4px" },
                            disabled: busy || applyingOutfits,
                            title: "Create theme-appropriate outfits and assign to characters",
                            async onclick() {
                                await applyThemeOutfits(viewingDeck, activeTheme);
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "checkroom"),
                            applyingOutfits ? "Outfitting..." : "Apply Theme Outfits"
                        ])
                    ]),
                    // SD Config panel (per-type)
                    m("div", { class: "cg2-sd-panel" }, [
                        m("div", {
                            class: "cg2-sd-panel-header",
                            onclick() { sdConfigExpanded = !sdConfigExpanded; m.redraw(); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", marginRight: "6px", transition: "transform 0.2s", transform: sdConfigExpanded ? "rotate(90deg)" : "" } }, "chevron_right"),
                            m("span", { style: { fontWeight: 600, fontSize: "13px" } }, "SD Config"),
                            (() => {
                                let active = Object.keys(sdOverrideInsts).filter(k => {
                                    return sdOverrideInsts[k] && sdOverrideInsts[k].changes && sdOverrideInsts[k].changes.length > 0;
                                });
                                return active.length
                                    ? m("span", { style: { fontSize: "10px", color: "#2E7D32", marginLeft: "8px" } }, active.length + " type" + (active.length > 1 ? "s" : "") + " modified")
                                    : null;
                            })()
                        ]),
                        sdConfigExpanded ? m("div", { class: "cg2-sd-panel-body" }, [
                            // Type tabs
                            m("div", { class: "cg2-sd-tabs" }, [
                                m("span", {
                                    class: "cg2-sd-tab" + (sdConfigTab === "_default" ? " cg2-sd-tab-active" : ""),
                                    onclick() { sdConfigTab = "_default"; m.redraw(); }
                                }, "Default"),
                                ...Object.keys(CARD_TYPES).map(t =>
                                    m("span", {
                                        class: "cg2-sd-tab" + (sdConfigTab === t ? " cg2-sd-tab-active" : ""),
                                        style: { borderBottomColor: sdConfigTab === t ? CARD_TYPES[t].color : "transparent" },
                                        onclick() { sdConfigTab = t; m.redraw(); }
                                    }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", marginRight: "2px", color: CARD_TYPES[t].color } }, CARD_TYPES[t].icon),
                                        CARD_TYPES[t].label
                                    ])
                                ),
                                m("span", { class: "cg2-sd-tab-sep" }),
                                ...Object.keys(TEMPLATE_TYPES).map(t =>
                                    m("span", {
                                        class: "cg2-sd-tab" + (sdConfigTab === t ? " cg2-sd-tab-active" : ""),
                                        style: { borderBottomColor: sdConfigTab === t ? TEMPLATE_TYPES[t].color : "transparent" },
                                        onclick() { sdConfigTab = t; m.redraw(); }
                                    }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", marginRight: "2px", color: TEMPLATE_TYPES[t].color } }, TEMPLATE_TYPES[t].icon),
                                        TEMPLATE_TYPES[t].label
                                    ])
                                )
                            ]),
                            // Fields rendered via standard form system
                            (() => {
                                let inst = getSdOverrideInst(sdConfigTab);
                                let ov = sdOverrideViews[sdConfigTab];
                                return m(ov.view, {
                                    freeForm: true,
                                    freeFormType: "olio.sd.config",
                                    freeFormEntity: inst.entity,
                                    freeFormInstance: inst
                                });
                            })(),
                            m("div", { style: { display: "flex", justifyContent: "space-between", marginTop: "6px" } }, [
                                m("button", {
                                    class: "cg2-btn", style: { fontSize: "11px" },
                                    onclick() {
                                        sdOverrides = defaultSdOverrides();
                                        resetSdOverrideInsts();
                                        sdConfigTab = "_default";
                                        m.redraw();
                                    }
                                }, "Reset All"),
                                m("div", { style: { display: "flex", gap: "6px" } }, [
                                    m("button", {
                                        class: "cg2-btn", style: { fontSize: "11px" },
                                        onclick() {
                                            if (sdConfigTab === "_default") {
                                                sdOverrides[sdConfigTab] = newSdOverride();
                                            } else {
                                                // Reset card type to a copy of _default
                                                let copy = JSON.parse(JSON.stringify(sdOverrides._default));
                                                copy[am7model.jsonModelKey] = "olio.sd.config";
                                                sdOverrides[sdConfigTab] = copy;
                                            }
                                            delete sdOverrideInsts[sdConfigTab];
                                            delete sdOverrideViews[sdConfigTab];
                                            m.redraw();
                                        }
                                    }, "Clear " + (sdConfigTab === "_default" ? "Default" : (CARD_TYPES[sdConfigTab] || TEMPLATE_TYPES[sdConfigTab] || {}).label || sdConfigTab)),
                                    m("button", {
                                        class: "cg2-btn cg2-btn-primary", style: { fontSize: "11px" },
                                        async onclick() {
                                            if (viewingDeck) {
                                                // Fill any undefined types from _default before saving
                                                let allKeys = Object.keys(CARD_TYPES).concat(Object.keys(TEMPLATE_TYPES));
                                                allKeys.forEach(k => {
                                                    if (!sdOverrides[k]) {
                                                        sdOverrides[k] = JSON.parse(JSON.stringify(sdOverrides._default));
                                                        sdOverrides[k][am7model.jsonModelKey] = "olio.sd.config";
                                                    }
                                                });
                                                viewingDeck.sdOverrides = JSON.parse(JSON.stringify(sdOverrides));
                                                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                                                await deckStorage.save(safeName, viewingDeck);
                                                page.toast("success", "SD config saved to deck");
                                            }
                                        }
                                    }, "Save to Deck")
                                ])
                            ]),
                            // Background Basis
                            m("hr", { style: { margin: "8px 0", border: "none", borderTop: "1px solid #ddd" } }),
                            m("div", { class: "cg2-bg-panel" }, [
                                m("div", { class: "cg2-bg-panel-left" }, [
                                    m("span", { style: { fontWeight: 700, fontSize: "13px" } }, "Background Basis"),
                                    backgroundImageId
                                        ? m("span", { style: { fontSize: "11px", color: "#2E7D32", marginLeft: "8px" } }, "Active")
                                        : m("span", { style: { fontSize: "11px", color: "#888", marginLeft: "8px" } }, "None"),
                                    backgroundThumbUrl ? m("img", {
                                        src: backgroundThumbUrl, class: "cg2-bg-thumb",
                                        style: { cursor: "pointer" },
                                        onclick() { showImagePreview(backgroundThumbUrl); }
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
                            ])
                        ]) : null
                    ]),
                    // Art toolbar
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: busy,
                            title: "Generate art only for cards that don't have images yet",
                            onclick() { queueDeckArt(viewingDeck); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "4px" } }, "auto_awesome"),
                            queueActive ? "Generating..." : "Generate Missing Art"
                        ]),
                        m("button", {
                            class: "cg2-btn cg2-btn-danger",
                            style: { marginLeft: "4px" },
                            disabled: busy,
                            title: "Clear all existing art and regenerate every card",
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
                    // Card grid — card front, card back, then all card faces
                    m("div", { class: "cg2-card-grid" }, [
                        // Card Front template
                        m("div", { class: "cg2-card-art-wrapper", key: "card-front" }, [
                            m("div", {
                                class: "cg2-card cg2-card-front cg2-card-front-bg",
                                style: cardFrontImageUrl ? { backgroundImage: "url('" + cardFrontImageUrl + "')", backgroundSize: "cover", backgroundPosition: "center" } : {},
                                onclick() { if (cardFrontImageUrl) showImagePreview(cardFrontImageUrl); }
                            }, [
                                m("div", { class: "cg2-back-pattern" }),
                                m("span", { class: "material-symbols-outlined cg2-back-icon" }, "style"),
                                m("div", { class: "cg2-back-label" }, "CARD FRONT")
                            ]),
                            m("div", { style: { textAlign: "center", fontSize: "10px", color: "#888", marginTop: "4px" } }, "Card Front"),
                            !busy ? m("div", { class: "cg2-card-actions" }, [
                                m("button", {
                                    class: "cg2-card-action-btn",
                                    title: "Generate card front background",
                                    onclick(e) { e.stopPropagation(); generateTemplateArt("front"); }
                                }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "auto_awesome"))
                            ]) : null
                        ]),
                        // Card Back template
                        m("div", { class: "cg2-card-art-wrapper", key: "card-back" }, [
                            m("div", {
                                class: "cg2-card cg2-card-back",
                                style: cardBackImageUrl
                                    ? { backgroundImage: "url('" + cardBackImageUrl + "')", backgroundSize: "cover", backgroundPosition: "center", borderColor: "#B8860B" }
                                    : { background: CARD_TYPES.item.color, borderColor: CARD_TYPES.item.color },
                                onclick() { if (cardBackImageUrl) showImagePreview(cardBackImageUrl); }
                            }, [
                                m("div", { class: "cg2-back-pattern" }),
                                m("span", { class: "material-symbols-outlined cg2-back-icon" }, CARD_TYPES.item.icon),
                                m("div", { class: "cg2-back-label" }, "CARD BACK")
                            ]),
                            m("div", { style: { textAlign: "center", fontSize: "10px", color: "#888", marginTop: "4px" } }, "Card Back"),
                            !busy ? m("div", { class: "cg2-card-actions" }, [
                                m("button", {
                                    class: "cg2-card-action-btn",
                                    title: "Generate card back background",
                                    onclick(e) { e.stopPropagation(); generateTemplateArt("back"); }
                                }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "auto_awesome"))
                            ]) : null
                        ]),
                        // Card faces
                        ...cards.map((card, i) => {
                            let artJob = artQueue.find(j => j.cardIndex === i);
                            let isGenerating = artJob && artJob.status === "processing";
                            let hasArt = card.imageUrl || (card.type === "character" && card.portraitUrl);
                            let isChar = card.type === "character";
                            let isFlipped = isChar && flippedCards[i];

                            let cardFront = m("div", {
                                class: isChar ? "cg2-card-flip-front" : ""
                            }, m(CardFace, { card, bgImage: cardFrontImageUrl }));

                            let cardBack = isChar ? m("div", { class: "cg2-card-flip-back" }, renderCharacterBackBody(card, cardBackImageUrl)) : null;

                            let cardContent;
                            if (isChar) {
                                cardContent = m("div", {
                                    class: "cg2-card-flipper" + (isFlipped ? " cg2-flipped" : ""),
                                    onclick() { flippedCards[i] = !flippedCards[i]; }
                                }, m("div", { class: "cg2-card-flipper-inner" }, [cardFront, cardBack]));
                            } else {
                                cardContent = cardFront;
                            }

                            return m("div", { class: "cg2-card-art-wrapper", key: card.name + "-" + i }, [
                                cardContent,
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
                                // Image preview on click (non-character cards with art)
                                !isChar && hasArt ? m("div", {
                                    class: "cg2-card-art-badge",
                                    style: { cursor: "pointer", right: "6px", left: "auto", top: "6px" },
                                    onclick() { showImagePreview(card.imageUrl); }
                                }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "zoom_in")) : null,
                                // Per-card action buttons
                                !busy ? m("div", { class: "cg2-card-actions" }, [
                                    // Reimage button
                                    m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Regenerate art\n" + buildCardPrompt(card, activeTheme),
                                        onclick(e) {
                                            e.stopPropagation();
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
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "auto_awesome")),
                                    // Gallery picker (character cards only)
                                    card.sourceId && card.type === "character" ? m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Pick portrait from gallery",
                                        onclick(e) {
                                            e.stopPropagation();
                                            openGalleryPicker(card);
                                        }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "photo_library")) : null,
                                    // Image sequence (character cards only) — dress-up progression
                                    card.sourceId && card.type === "character" ? (function() {
                                        let isThisCard = sequenceCardId === card.sourceId;
                                        return m("button", {
                                            class: "cg2-card-action-btn",
                                            title: isThisCard ? sequenceProgress : "Generate apparel image sequence",
                                            disabled: !!sequenceCardId,
                                            onclick(e) {
                                                e.stopPropagation();
                                                generateImageSequence(card);
                                            }
                                        }, isThisCard ? m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px" } }, "progress_activity")
                                            : m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "burst_mode"));
                                    })() : null,
                                    // Refresh data from source object (character cards only)
                                    card.sourceId && card.type === "character" ? m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Refresh card data from source object",
                                        async onclick(e) {
                                            e.stopPropagation();
                                            let ok = await refreshCharacterCard(card);
                                            if (ok) {
                                                page.toast("success", "Refreshed: " + card.name);
                                            } else {
                                                page.toast("warn", "Could not refresh " + card.name);
                                            }
                                            m.redraw();
                                        }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "sync")) : null,
                                    // Source object link (character cards)
                                    card.sourceId ? m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Open source object",
                                        onclick(e) {
                                            e.stopPropagation();
                                            window.open("#/view/olio.charPerson/" + card.sourceId, "_blank");
                                        }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "open_in_new")) : null,
                                    // Art object link (non-character cards with generated art)
                                    !card.sourceId && card.artObjectId ? m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Open generated art object",
                                        onclick(e) {
                                            e.stopPropagation();
                                            window.open("#/view/data.data/" + card.artObjectId, "_blank");
                                        }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "open_in_new")) : null
                                ]) : null
                            ]);
                        })
                    ]),
                    m(ImagePreviewOverlay),
                    m(GalleryPickerOverlay)
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
