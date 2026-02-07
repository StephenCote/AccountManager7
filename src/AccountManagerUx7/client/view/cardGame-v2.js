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

    // Template/utility art types (card front, card back, background, tabletop)
    const TEMPLATE_TYPES = {
        cardFront:  { color: "#795548", icon: "crop_portrait",    label: "Card Front" },
        cardBack:   { color: "#546E7A", icon: "flip_to_back",     label: "Card Back" },
        background: { color: "#33691E", icon: "landscape",        label: "Background" },
        tabletop:   { color: "#5D4037", icon: "table_restaurant", label: "Tabletop" }
    };

    // Sub-icons for item subtypes
    const ITEM_SUBTYPE_ICONS = {
        weapon:     "swords",
        consumable: "science"
    };

    // ── Card Render Configuration (Phase 7.0 consolidation) ─────────
    // Defines fields to render for each card type - replaces 8 separate render*Body functions
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

    // Get icon for card type (for stack display)
    function cardTypeIcon(type) {
        let cfg = CARD_TYPES[type] || CARD_TYPES.item;
        return cfg.icon;
    }

    // ── Card Face Renderers (per type) ───────────────────────────────

    function renderCharacterBody(card, options) {
        let stats = card.stats || {};
        let noPreview = options?.noPreview;
        return [
            m("div", { class: "cg2-card-image-area" },
                card.portraitUrl
                    ? m("img", {
                        src: card.portraitUrl, class: "cg2-card-img",
                        style: noPreview ? {} : { cursor: "pointer" },
                        onclick: noPreview ? null : function(e) { e.stopPropagation(); showImagePreview(card.portraitUrl); }
                    })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#B8860B" } }, "person")
            ),
            m("div", { class: "cg2-card-details" }, [
                m("div", { class: "cg2-card-detail cg2-icon-detail" }, [
                    m("span", { class: "material-symbols-outlined cg2-detail-icon" }, "badge"),
                    m("span", (card.gender ? card.gender.charAt(0).toUpperCase() + " " : "") + (card.race || "")),
                    card.age ? m("span", { style: { marginLeft: "auto", opacity: 0.7 } }, card.age + " yrs") : null
                ]),
                card.alignment ? iconDetail("balance", card.alignment.replace(/_/g, " ")) : null,
                m(StatBlock, { stats })
            ]),
            m("div", { class: "cg2-card-footer", style: { borderTop: "none" } }, [
                m("span", { style: { fontSize: "9px", color: "#999", fontStyle: "italic" } }, "click to flip")
            ])
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

    // Helper: render a detail row with icon instead of text prefix
    // Icons: stack=layers, roll=casino, hit=gps_fixed, effect=auto_awesome, type=category,
    //        require=key, tier=military_tech, cost=bolt, uses=all_inclusive, skill=psychology,
    //        difficulty=signal_cellular_alt, behavior=psychology_alt, loot=inventory_2, modifier=tune
    function iconDetail(icon, text, extraClass) {
        if (!text) return null;
        return m("div", { class: "cg2-card-detail cg2-icon-detail" + (extraClass ? " " + extraClass : "") }, [
            m("span", { class: "material-symbols-outlined cg2-detail-icon" }, icon),
            m("span", text)
        ]);
    }

    // ── Unified Card Body Renderer (Phase 7.0) ──────────────────────
    // Uses CARD_RENDER_CONFIG to render card body based on type
    function renderCardBody(card, type, options) {
        let config = CARD_RENDER_CONFIG[type];
        if (!config) return [m("div", "Unknown type: " + type)];

        let imageUrl = card.imageUrl || card.portraitUrl;
        let placeholderColor = config.placeholderColor || CARD_TYPES[type]?.color;
        let noPreview = options?.noPreview;

        // Image area
        let imageArea = m("div", { class: "cg2-card-image-area" },
            imageUrl
                ? m("img", {
                    src: imageUrl,
                    class: "cg2-card-img",
                    style: noPreview ? {} : { cursor: "pointer" },
                    onclick: noPreview ? null : function(e) { e.stopPropagation(); showImagePreview(imageUrl); }
                })
                : m("span", {
                    class: "material-symbols-outlined cg2-placeholder-icon",
                    style: placeholderColor ? { color: placeholderColor } : {}
                }, config.placeholderIcon)
        );

        // Details section
        let details = [];

        // Header field (first row with slot/type + rarity)
        if (config.headerField) {
            let hf = config.headerField;
            let value = hf.label || card[hf.field] || hf.default;
            details.push(m("div", { class: "cg2-card-detail cg2-icon-detail" }, [
                m("span", { class: "material-symbols-outlined cg2-detail-icon" }, hf.icon),
                m("span", value),
                hf.showRarity ? m("span", { class: "cg2-rarity", style: { marginLeft: "auto" } }, rarityStars(card.rarity)) : null
            ]));
        }

        // Render each detail field
        if (config.details) {
            for (let d of config.details) {
                if (d.type === "stats") {
                    // Stats line (multiple stats in one row)
                    let statElements = d.fields
                        .filter(f => card[f] != null)
                        .map(f => m("span", {
                            class: "cg2-mod" + (f === "atk" && d.atkClass ? " " + d.atkClass : "")
                        }, (d.labels?.[f] || "") + card[f]));
                    // Add defaults for fields that need them
                    if (d.defaults) {
                        for (let [k, v] of Object.entries(d.defaults)) {
                            if (!card[k] && !statElements.find(el => el.text?.includes(k))) {
                                statElements.push(m("span", { class: "cg2-mod" }, v));
                            }
                        }
                    }
                    if (statElements.length) {
                        details.push(m("div", { class: "cg2-card-stats-line" }, statElements));
                    }
                } else if (d.type === "requires" && card.requires && Object.keys(card.requires).length) {
                    details.push(iconDetail(d.icon, Object.entries(card.requires).map(([k, v]) => k + " " + v).join(", ")));
                } else if (d.type === "array" && card[d.field]?.length) {
                    details.push(iconDetail(d.icon, card[d.field].join(", ")));
                } else if (d.type === "flavor" && card[d.field]) {
                    details.push(m("div", { class: "cg2-flavor" }, "\u201C" + card[d.field] + "\u201D"));
                } else if (d.type === "warning") {
                    details.push(m("div", { class: "cg2-use-or-lose" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", marginRight: "4px" } }, "warning"),
                        d.text
                    ]));
                } else if (d.label) {
                    // Static label
                    details.push(iconDetail(d.icon, d.label, d.class));
                } else if (d.field) {
                    // Dynamic field
                    let value = card[d.field];
                    if (value == null && d.default != null) value = d.default;
                    if (value != null) {
                        let text = (d.prefix || "") + value + (d.suffix || "");
                        details.push(iconDetail(d.icon, text, d.class));
                    }
                }
            }
        }

        // Footer section
        let footer = null;
        if (config.footer && config.footer.length) {
            let footerItems = [];
            for (let f of config.footer) {
                if (f.label) {
                    footerItems.push(iconDetail(f.icon, f.label, f.class));
                } else if (f.field === "reusable") {
                    // Boolean toggle for reusable/consumable
                    let isReusable = card.reusable;
                    footerItems.push(iconDetail(isReusable ? f.icon : f.altIcon, isReusable ? f.trueLabel : f.falseLabel));
                } else {
                    let value = card[f.field];
                    if (value == null && f.default != null) value = f.default;
                    if (value != null) {
                        let text = (f.prefix || "") + value + (f.suffix || "");
                        footerItems.push(iconDetail(f.icon, text, f.class));
                    }
                }
            }
            if (footerItems.length) {
                footer = m("div", { class: "cg2-card-footer" }, footerItems);
            }
        }

        return [imageArea, m("div", { class: "cg2-card-details" }, details), footer];
    }

    // ── Render compact stats for action bar display ───────────────────
    function renderCompactStats(card, type) {
        if (!card) return null;
        let stats = [];

        switch (type) {
            case "action":
                if (card.roll) stats.push(m("span", { class: "cg2-compact-stat", title: "Roll" }, [
                    m("span", { class: "material-symbols-outlined" }, "casino"), card.roll
                ]));
                if (card.onHit) stats.push(m("span", { class: "cg2-compact-stat cg2-compact-effect", title: "On Hit" }, card.onHit));
                break;
            case "skill":
                if (card.modifier) stats.push(m("span", { class: "cg2-compact-stat", title: "Modifier" }, [
                    m("span", { class: "material-symbols-outlined" }, "tune"), card.modifier
                ]));
                if (card.skillType) stats.push(m("span", { class: "cg2-compact-stat" }, card.skillType));
                break;
            case "magic":
                if (card.effect) stats.push(m("span", { class: "cg2-compact-stat cg2-compact-effect" }, card.effect));
                if (card.manaCost) stats.push(m("span", { class: "cg2-compact-stat", title: "Mana" }, [
                    m("span", { class: "material-symbols-outlined" }, "water_drop"), card.manaCost
                ]));
                break;
            case "item":
                if (card.subtype === "weapon" && card.atk) {
                    stats.push(m("span", { class: "cg2-compact-stat", title: "Attack" }, [
                        m("span", { class: "material-symbols-outlined" }, "swords"), " " + card.atk
                    ]));
                }
                if (card.subtype === "armor" && card.def) {
                    stats.push(m("span", { class: "cg2-compact-stat", title: "Defense" }, [
                        m("span", { class: "material-symbols-outlined" }, "shield"), " " + card.def
                    ]));
                }
                if (card.effect) stats.push(m("span", { class: "cg2-compact-stat cg2-compact-effect" }, card.effect));
                break;
            case "talk":
                stats.push(m("span", { class: "cg2-compact-stat" }, [
                    m("span", { class: "material-symbols-outlined" }, "chat_bubble"), " CHA"
                ]));
                break;
            case "encounter":
                if (card.atk) stats.push(m("span", { class: "cg2-compact-stat" }, [
                    m("span", { class: "material-symbols-outlined" }, "swords"), " " + card.atk
                ]));
                if (card.def) stats.push(m("span", { class: "cg2-compact-stat" }, [
                    m("span", { class: "material-symbols-outlined" }, "shield"), " " + card.def
                ]));
                break;
            case "character":
                let s = card.stats || {};
                if (s.STR) stats.push(m("span", { class: "cg2-compact-stat" }, "STR " + s.STR));
                if (s.AGI) stats.push(m("span", { class: "cg2-compact-stat" }, "AGI " + s.AGI));
                if (s.END) stats.push(m("span", { class: "cg2-compact-stat" }, "END " + s.END));
                break;
        }

        return stats.length > 0 ? stats : null;
    }

    // ── Check if card is incomplete (missing clear effect) ────────────
    function isCardIncomplete(card) {
        if (!card) return true;
        let type = card.type;
        // Character cards are complete if they have stats
        if (type === "character") return !card.stats;
        // Action/skill/magic/talk need effect or clear mechanics
        if (type === "action") return !card.onHit && !card.roll && !card.effect;
        if (type === "skill") return !card.modifier && !card.effect;
        if (type === "magic") return !card.effect;
        if (type === "talk") return !card.effect && !card.speechType;
        // Items need effect or stats
        if (type === "item") return !card.effect && !card.atk && !card.def;
        // Apparel needs def or effect
        if (type === "apparel") return !card.def && !card.effect;
        // Encounters need behavior or stats
        if (type === "encounter") return !card.behavior && !card.atk;
        return false;
    }

    // ── CardFace Component ───────────────────────────────────────────
    function CardFace() {
        return {
            view(vnode) {
                let card = vnode.attrs.card;
                if (!card || !card.type) return m("div.cg2-card.cg2-card-empty", "No card");
                let type = card.type;
                let cfg = CARD_TYPES[type] || CARD_TYPES.item;
                let compact = vnode.attrs.compact;
                let full = vnode.attrs.full;
                let incomplete = isCardIncomplete(card);

                // Determine render type: character is special (has StatBlock),
                // items use subtype, others use unified renderer
                let renderType = type;
                if (type === "item") {
                    renderType = card.subtype === "consumable" ? "consumable" : "weapon";
                }

                // Options for render functions (e.g., noPreview disables image click)
                let renderOpts = { noPreview: vnode.attrs.noPreview };

                // Use unified renderCardBody for types with config, special handling for character
                let bodyFn = type === "character"
                    ? () => renderCharacterBody(card, renderOpts)
                    : (CARD_RENDER_CONFIG[renderType]
                        ? () => renderCardBody(card, renderType, renderOpts)
                        : () => [m("div", "Unknown type: " + type)]);
                let cardStyle = { borderColor: cfg.color };
                if (vnode.attrs.bgImage) {
                    cardStyle.backgroundImage = "linear-gradient(rgba(255,255,255,0.5),rgba(255,255,255,0.5)), url('" + vnode.attrs.bgImage + "')";
                    cardStyle.backgroundSize = "cover, cover";
                    cardStyle.backgroundPosition = "center, center";
                }
                let sizeClass = compact ? " cg2-card-compact" : (full ? " cg2-card-full" : "");
                let cardClass = "cg2-card cg2-card-front cg2-card-" + type + sizeClass;

                // For compact cards, render a minimal body with image and key stats
                let compactBody = null;
                if (compact) {
                    compactBody = m("div", { class: "cg2-card-body cg2-card-body-compact" }, [
                        // Show image if available
                        card.imageUrl ? m("div", { class: "cg2-card-image-area cg2-compact-image" },
                            m("img", { src: card.imageUrl, class: "cg2-card-img" })
                        ) : null,
                        // Show condensed stats based on type
                        m("div", { class: "cg2-compact-stats" }, renderCompactStats(card, type))
                    ]);
                }

                return m("div", {
                    class: cardClass,
                    style: cardStyle
                }, [
                    cornerIcon(type, "top-left"),
                    cornerIcon(type, "top-right"),
                    // Incomplete indicator (yellow asterisk)
                    incomplete ? m("div", {
                        class: "cg2-incomplete-badge",
                        title: "Card effect incomplete - needs definition"
                    }, "*") : null,
                    m("div", { class: "cg2-card-name", style: { color: cfg.color } }, card.name || "Unnamed"),
                    compact ? compactBody : m("div", { class: "cg2-card-body" }, bodyFn(card)),
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

    // ── D20 Dice Component (inline SVG with embedded number) ─────────
    function D20Dice() {
        return {
            view(vnode) {
                let { value, rolling, winner } = vnode.attrs;
                let displayVal = value || "?";

                // SVG paths from d20-1435790057.svg
                return m("div", {
                    class: "cg2-d20-wrap" + (rolling ? " cg2-d20-rolling" : " cg2-d20-final") + (winner ? " cg2-d20-winner" : "")
                }, [
                    m("svg", {
                        viewBox: "0 0 360 360",
                        class: "cg2-d20-svg",
                        xmlns: "http://www.w3.org/2000/svg"
                    }, [
                        // Main blue faces
                        m("g", { fill: "#3372A0" }, [
                            m("polygon", { points: "178.3,13.9 38.6,94.5 178.3,105.8" }),
                            m("polygon", { points: "34.8,104.6 34.8,263.4 93.4,252.3" }),
                            m("polygon", { points: "177.2,109.1 35.7,97.7 96,249.7" }),
                            m("polygon", { points: "261.3,251.9 180,111 98.7,251.9" }),
                            m("polygon", { points: "181.7,13.9 181.7,105.8 321.4,94.5" }),
                            m("polygon", { points: "264,249.7 324.3,97.7 182.8,109.1" }),
                            m("polygon", { points: "188,343.7 322.1,266.2 264.9,255.4" }),
                            m("polygon", { points: "325.2,263.4 325.2,104.6 266.6,252.3" }),
                            m("polygon", { points: "99.5,255.2 180,347.7 260.5,255.2" }),
                            m("polygon", { points: "37.9,266.2 172,343.7 95.1,255.4" })
                        ]),
                        // White highlights
                        m("g", { fill: "#FFFFFF", opacity: "0.25" }, [
                            m("polygon", { points: "178.3,13.9 38.6,94.5 68.1,96.9" }),
                            m("polygon", { points: "34.8,104.6 34.8,263.4 43.3,261.8" }),
                            m("polygon", { points: "108.5,228.1 35.7,97.7 96,249.7" }),
                            m("polygon", { points: "192.9,133.4 180,111 98.7,251.9 116.9,251.9" }),
                            m("polygon", { points: "181.7,13.9 181.7,105.8 209.3,103.6" }),
                            m("polygon", { points: "195.5,131.1 324.3,97.7 182.8,109.1" }),
                            m("polygon", { points: "188,343.7 282.5,258.7 264.9,255.4" }),
                            m("polygon", { points: "282.3,255.2 325.2,104.6 266.6,252.3" }),
                            m("polygon", { points: "99.5,255.2 180,347.7 118,255.4" }),
                            m("polygon", { points: "37.9,266.2 103.7,265.3 95.1,255.4" })
                        ]),
                        // Dark outline
                        m("path", { fill: "#303030", d: "M328.4,99.6c0-3.1-1.7-6-4.4-7.6L183.9,11.3c-2.4-1.4-5.4-1.4-7.9,0L35.6,92.9c-2.5,1.5-4.1,4.2-4.1,7.1l-0.1,161.8c0,2.8,1.5,5.4,3.9,6.8L176.1,350c2.4,1.4,5.3,1.4,7.7,0l140.8-81.3c2.4-1.4,3.9-4,3.9-6.8L328.4,99.6z M267.1,250.9l55.8-140.4c0.5-1.2,2.3-0.9,2.3,0.4v150.9c0,0.8-0.7,1.3-1.4,1.2L268,252.5C267.3,252.4,266.8,251.6,267.1,250.9z M102.2,255.2h155.7c1,0,1.6,1.2,0.9,2l-77.8,89.4c-0.5,0.6-1.4,0.6-1.8,0l-77.8-89.4C100.6,256.5,101.1,255.2,102.2,255.2z M181.1,112.9L260.2,250c0.5,0.8-0.1,1.8-1.1,1.8H100.8c-0.9,0-1.5-1-1.1-1.8l79.2-137.2C179.4,112.1,180.6,112.1,181.1,112.9z M184.7,109l137.7-11.1c0.9-0.1,1.6,0.8,1.2,1.7l-58.7,147.8c-0.4,0.9-1.7,1-2.2,0.2l-78.9-136.7C183.3,110,183.8,109,184.7,109z M95.1,247.3L36.4,99.5c-0.3-0.8,0.3-1.7,1.2-1.7L175.3,109c0.9,0.1,1.4,1,1,1.8L97.3,247.5C96.8,248.4,95.5,248.3,95.1,247.3z M181.7,104.5V16c0-0.9,1-1.5,1.8-1.1L318,92.6c1,0.6,0.7,2.2-0.5,2.3L183,105.7C182.3,105.8,181.7,105.2,181.7,104.5z M178.3,16v88.5c0,0.7-0.6,1.3-1.3,1.2L42.5,94.8c-1.2-0.1-1.5-1.7-0.5-2.3l134.5-77.6C177.3,14.5,178.3,15.1,178.3,16z M92,252.5l-55.8,10.6c-0.7,0.1-1.4-0.4-1.4-1.2V111c0-1.3,1.9-1.7,2.3-0.4l55.8,140.4C93.2,251.6,92.7,252.4,92,252.5z M41.1,265.6l53.3-10.1c0.4-0.1,0.9,0.1,1.1,0.4l71.7,82.3c0.9,1-0.3,2.5-1.5,1.9l-125-72.2C39.7,267.3,40,265.8,41.1,265.6z M265.6,255.5l53.3,10.1c1.1,0.2,1.4,1.7,0.4,2.2l-125,72.2c-1.2,0.7-2.4-0.8-1.5-1.9l71.7-82.3C264.7,255.6,265.1,255.4,265.6,255.5z" }),
                        // Number text - centered on the front face
                        m("text", {
                            x: "180",
                            y: "200",
                            "text-anchor": "middle",
                            "dominant-baseline": "middle",
                            fill: "#FFFFFF",
                            "font-size": displayVal.toString().length > 1 ? "70" : "80",
                            "font-weight": "900",
                            "font-family": "Arial, sans-serif",
                            style: "text-shadow: 0 2px 4px rgba(0,0,0,0.5)"
                        }, displayVal)
                    ])
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

    // ── data.data CRUD Helpers ────────────────────────────────────────
    // Shared utilities for all storage objects (deck, game saves, campaign)
    const DECK_BASE_PATH = "~/CardGame";

    function encodeJson(data) {
        return btoa(unescape(encodeURIComponent(JSON.stringify(data))));
    }
    function decodeJson(encoded) {
        return JSON.parse(decodeURIComponent(escape(atob(encoded))));
    }

    // Upsert a named data.data record in a group (create-or-update pattern)
    async function upsertDataRecord(groupPath, fileName, data) {
        let grp = await page.makePath("auth.group", "DATA", groupPath);
        if (!grp) { console.error("[CardGame v2] Could not create group:", groupPath); return null; }
        let encoded = encodeJson(data);

        let q = am7view.viewQuery("data.data");
        q.field("groupId", grp.id);
        q.field("name", fileName);
        let qr = await page.search(q);

        if (qr?.results?.length) {
            let existing = qr.results[0];
            existing.dataBytesStore = encoded;
            existing.compressionType = "none";
            return await page.patchObject(existing);
        }
        let obj = am7model.newPrimitive("data.data");
        obj.name = fileName;
        obj.contentType = "application/json";
        obj.compressionType = "none";
        obj.groupId = grp.id;
        obj.groupPath = grp.path;
        obj.dataBytesStore = encoded;
        return await page.createObject(obj);
    }

    // Load a named data.data record from a group
    async function loadDataRecord(groupPath, fileName, createGroup) {
        let grp = createGroup
            ? await page.makePath("auth.group", "DATA", groupPath)
            : await page.findObject("auth.group", "DATA", groupPath);
        if (!grp) return null;
        let q = am7view.viewQuery("data.data");
        q.field("groupId", grp.id);
        q.field("name", fileName);
        let qr = await page.search(q);
        if (qr?.results?.length && qr.results[0].dataBytesStore) {
            return decodeJson(qr.results[0].dataBytesStore);
        }
        return null;
    }

    // Query all data.data records in a group
    async function listDataRecords(groupPath) {
        let grp = await page.findObject("auth.group", "DATA", groupPath);
        if (!grp) return [];
        let q = am7view.viewQuery("data.data");
        q.field("groupId", grp.id);
        let qr = await page.search(q);
        return qr?.results || [];
    }

    // ── Deck Storage ──────────────────────────────────────────────────
    const deckStorage = {
        async save(deckName, data) {
            try {
                let saved = await upsertDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", data);
                console.log("[CardGame v2] Deck saved:", deckName);
                return saved;
            } catch (e) {
                console.error("[CardGame v2] Failed to save deck:", deckName, e);
                return null;
            }
        },

        async load(deckName) {
            try {
                return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", true);
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
                return (qr?.results || []).map(g => g.name);
            } catch (e) {
                console.error("[CardGame v2] Failed to list decks:", e);
                return [];
            }
        },

        async remove(deckName) {
            try {
                let grp = await page.findObject("auth.group", "DATA", DECK_BASE_PATH + "/" + deckName);
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

    // ── Game Save Storage ─────────────────────────────────────────────
    // Auto-saves game state to ~/CardGame/{deckName}/saves/ with rolling backups

    function serializeGameState(state) {
        let s = JSON.parse(JSON.stringify(state));
        // Strip non-serializable / transient fields
        delete s.turnTimer;
        delete s.narrationText;
        delete s.narrationTime;
        return s;
    }

    function deserializeGameState(saveData) {
        let state = saveData.gameState;
        state.turnTimer = null;
        state.narrationText = null;
        state.narrationTime = null;
        state.isPaused = false;
        return state;
    }

    const gameStorage = {
        _savesPath(deckName) { return DECK_BASE_PATH + "/" + deckName + "/saves"; },

        async save(deckName, state) {
            try {
                let saveData = {
                    version: 1,
                    timestamp: new Date().toISOString(),
                    deckName,
                    roundNumber: state.round,
                    phase: state.phase,
                    gameState: serializeGameState(state)
                };
                let groupPath = this._savesPath(deckName);
                let grp = await page.makePath("auth.group", "DATA", groupPath);
                if (!grp) return null;

                let obj = am7model.newPrimitive("data.data");
                obj.name = "save-" + Date.now() + ".json";
                obj.contentType = "application/json";
                obj.compressionType = "none";
                obj.groupId = grp.id;
                obj.groupPath = grp.path;
                obj.dataBytesStore = encodeJson(saveData);
                let saved = await page.createObject(obj);

                // Keep only last 3 saves
                await this.cleanupOldSaves(deckName, 3);
                return saved;
            } catch (e) {
                console.error("[CardGame v2] Failed to save game:", deckName, e);
                return null;
            }
        },

        async load(deckName) {
            try {
                let saves = await this.list(deckName);
                if (saves.length === 0) return null;
                let latest = saves[0];
                if (latest.dataBytesStore) {
                    return decodeJson(latest.dataBytesStore);
                }
                return null;
            } catch (e) {
                console.error("[CardGame v2] Failed to load game save:", deckName, e);
                return null;
            }
        },

        async list(deckName) {
            try {
                let records = await listDataRecords(this._savesPath(deckName));
                return records
                    .filter(r => r.name?.startsWith("save-"))
                    .sort((a, b) => b.name.localeCompare(a.name));
            } catch (e) {
                console.error("[CardGame v2] Failed to list saves:", deckName, e);
                return [];
            }
        },

        async deleteAll(deckName) {
            try {
                let grp = await page.findObject("auth.group", "DATA", this._savesPath(deckName));
                if (grp) await page.deleteObject("auth.group", grp.objectId);
            } catch (e) {
                console.warn("[CardGame v2] Failed to delete saves:", deckName, e);
            }
        },

        async cleanupOldSaves(deckName, keepCount) {
            try {
                let saves = await this.list(deckName);
                for (let i = keepCount; i < saves.length; i++) {
                    await page.deleteObject("data.data", saves[i].objectId);
                }
            } catch (e) {
                console.warn("[CardGame v2] Save cleanup failed:", e);
            }
        }
    };

    // ── Campaign Storage ──────────────────────────────────────────────
    // Persists character progression across games at ~/CardGame/{deckName}/campaign.json

    function createCampaignData(characterCard) {
        return {
            version: 1,
            characterId: characterCard.sourceId || characterCard._tempId || null,
            characterName: characterCard.name || "Unknown",
            level: 1,
            xp: 0,
            totalGamesPlayed: 0,
            wins: 0,
            losses: 0,
            statGains: {},
            pendingLevelUps: 0
        };
    }

    const campaignStorage = {
        async save(deckName, campaignData) {
            try {
                let saved = await upsertDataRecord(DECK_BASE_PATH + "/" + deckName, "campaign.json", campaignData);
                console.log("[CardGame v2] Campaign saved:", deckName, "Level", campaignData.level, "XP", campaignData.xp);
                return saved;
            } catch (e) {
                console.error("[CardGame v2] Failed to save campaign:", deckName, e);
                return null;
            }
        },

        async load(deckName) {
            try {
                return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "campaign.json", false);
            } catch (e) {
                console.error("[CardGame v2] Failed to load campaign:", deckName, e);
                return null;
            }
        }
    };

    // ── Campaign XP & Level System ────────────────────────────────────
    function calculateXP(state, isVictory) {
        let xp = 0;
        xp += state.round * 10;                    // 10 XP per round played
        if (isVictory) xp += 50;                    // Victory bonus
        xp += Math.max(0, state.player.hp) * 2;    // HP bonus (surviving health)
        return xp;
    }

    async function saveCampaignProgress(state, isVictory, xpGain) {
        let deckName = state.deckName;
        let campaign = await campaignStorage.load(deckName);
        if (!campaign) {
            campaign = createCampaignData(state.player.character);
        }
        campaign.totalGamesPlayed++;
        if (isVictory) campaign.wins++;
        else campaign.losses++;
        campaign.xp += xpGain;

        // Check for level up (every 100 XP, max level 10)
        let newLevel = Math.min(10, Math.floor(campaign.xp / 100) + 1);
        if (newLevel > campaign.level) {
            campaign.pendingLevelUps = (campaign.pendingLevelUps || 0) + (newLevel - campaign.level);
            campaign.level = newLevel;
        }

        await campaignStorage.save(deckName, campaign);
        return campaign;
    }

    // Module-level campaign state (loaded at game start, used in sidebar/game-over)
    let activeCampaign = null;
    let levelUpState = null; // { campaign, statsSelected: [], onComplete: fn }

    // Apply campaign stat gains to a game state's player character
    function applyCampaignBonuses(state, campaign) {
        if (!campaign?.statGains || !state?.player?.character?.stats) return;
        let stats = state.player.character.stats;
        for (let [stat, gain] of Object.entries(campaign.statGains)) {
            if (typeof stats[stat] === "number") {
                stats[stat] += gain;
            }
        }
        // Recalculate derived values from updated stats
        let end = stats.END ?? stats.end ?? 12;
        state.player.ap = Math.max(2, Math.floor(end / 5) + 1);
        state.player.maxAp = state.player.ap;
        let mag = stats.MAG ?? stats.mag ?? 12;
        state.player.energy = mag;
        state.player.maxEnergy = mag;
        console.log("[CardGame v2] Campaign bonuses applied:", JSON.stringify(campaign.statGains),
            "→ AP:", state.player.ap, "Energy:", state.player.energy);
    }

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
            sourceId: char.objectId || null,  // Only real objectId, not _tempId
            _sourceChar: char._tempId ? char : null  // Keep full object for temp chars
        };
    }

    // ── Per-Character Equipment Cards ─────────────────────────────────
    function assembleCharEquipment(char) {
        let stats = mapStats(char.statistics);
        Object.keys(stats).forEach(k => { stats[k] = clampStat(stats[k]); });
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

    // ── Phase 2: View State ──────────────────────────────────────────
    let screen = "deckList";    // "deckList" | "builder" | "deckView" | "game" | "test" | "themeEditor"
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
                    // Check for saved games and campaign data
                    let savesList = await gameStorage.list(name);
                    let campaign = await campaignStorage.load(name);
                    decks.push({
                        deckName: data.deckName || name,
                        themeId: data.themeId || null,
                        characterNames: data.characterNames || (data.characterName ? [data.characterName] : ["Unknown"]),
                        portraitUrl: data.portraitUrl || null,
                        createdAt: data.createdAt || null,
                        cardCount: data.cardCount || (data.cards ? data.cards.length : 0),
                        storageName: name,
                        // Include art URLs for deck list display
                        cardBackImageUrl: data.cardBackImageUrl || null,
                        cardFrontImageUrl: data.cardFrontImageUrl || null,
                        backgroundThumbUrl: data.backgroundThumbUrl || null,
                        // Save/campaign metadata
                        hasSave: savesList.length > 0,
                        lastSaveRound: savesList.length > 0 ? null : null, // populated below
                        campaign: campaign || null
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

        // Persist any generated characters that haven't been saved yet
        if (selectedChars && selectedChars.length > 0) {
            const unpersisted = selectedChars.filter(ch => ch._tempId && !ch.objectId);
            if (unpersisted.length > 0) {
                page.toast("info", "Saving characters...");
                const persisted = await persistGeneratedCharacters(unpersisted, safeName);
                // Update selectedChars with persisted versions
                for (const p of persisted) {
                    const idx = selectedChars.findIndex(ch => ch._tempId && ch.name === p.name);
                    if (idx >= 0) {
                        selectedChars[idx] = p;
                    }
                }
                // Update deck's character card references with real sourceId
                if (deck.cards) {
                    for (const card of deck.cards) {
                        if (card.type === "character" && card._sourceChar && card._sourceChar._tempId) {
                            const match = persisted.find(p => p.name === card.name);
                            if (match && match.objectId) {
                                card.sourceId = match.objectId;
                                delete card._sourceChar;  // No longer needed
                                console.log('[CardGame] Updated card sourceId for:', card.name, match.objectId);
                            }
                        }
                    }
                }
                // Update playerCharacter reference
                if (deck.playerCharacter && deck.playerCharacter._sourceChar) {
                    const match = persisted.find(p => p.name === deck.playerCharacter.name);
                    if (match && match.objectId) {
                        deck.playerCharacter.sourceId = match.objectId;
                        delete deck.playerCharacter._sourceChar;
                    }
                }
            }
        }

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

    // ── Character Generation (Phase 7.1) ─────────────────────────────
    let characterTemplates = null;
    let generatingCharacters = false;

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
    async function generateCharactersFromTemplates(themeId, count = 8) {
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

    // Convert charPerson to card format for deck
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

    // ── Play Deck (start game directly) ───────────────────────────────
    async function playDeck(storageName) {
        let data = await deckStorage.load(storageName);
        if (data) {
            viewingDeck = data;
            // Load the deck's theme
            if (data.themeId) {
                await loadThemeConfig(data.themeId);
            }
            // Clear old saves when starting a new game
            await gameStorage.deleteAll(storageName);
            // Reset game state and character selection for fresh start
            gameState = null;
            gameCharSelection = null;
            activeCampaign = null;
            screen = "game";
            m.redraw();
        } else {
            page.toast("error", "Failed to load deck");
        }
        m.redraw();
    }

    async function resumeGame(storageName) {
        let saveData = await gameStorage.load(storageName);
        if (!saveData) {
            page.toast("error", "No save found");
            return;
        }
        let data = await deckStorage.load(storageName);
        if (!data) {
            page.toast("error", "Failed to load deck for resume");
            return;
        }
        viewingDeck = data;
        if (data.themeId) {
            await loadThemeConfig(data.themeId);
        }
        gameState = deserializeGameState(saveData);
        gameCharSelection = null;
        activeCampaign = await campaignStorage.load(storageName);
        screen = "game";
        // Re-initialize LLM components for resumed game
        initializeLLMComponents(gameState, viewingDeck);
        m.redraw();
    }

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
                        m("span", { style: { flex: 1 } }),
                        m("button", { class: "cg2-btn cg2-btn-sm", onclick() { loadThemeList(); screen = "themeEditor"; m.redraw(); } }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "2px" } }, "palette"),
                            " Themes"
                        ]),
                        m("button", { class: "cg2-btn cg2-btn-sm", style: { marginLeft: "4px" }, onclick() { testDeck = null; testDeckName = null; screen = "test"; m.redraw(); } }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "2px" } }, "science"),
                            " Test"
                        ]),
                        decksLoading ? m("span", { class: "cg2-loading", style: { marginLeft: "4px" } }, "Loading...") : null
                    ]),
                    savedDecks.length === 0 && !decksLoading
                        ? m("div", { class: "cg2-empty-state" }, "No decks yet. Create one to get started.")
                        : m("div", { class: "cg2-deck-grid cg2-deck-grid-cards" },
                            savedDecks.map(d => {
                                // Use card back image if available
                                let cardBackUrl = d.cardBackImageUrl;

                                return m("div", { class: "cg2-deck-item" }, [
                                    // Card back display (hand card size)
                                    m("div", { class: "cg2-deck-card-back" }, [
                                        cardBackUrl
                                            ? m("div", {
                                                class: "cg2-deck-back-image",
                                                style: {
                                                    backgroundImage: "url('" + cardBackUrl + "')",
                                                    backgroundSize: "cover",
                                                    backgroundPosition: "center"
                                                }
                                            })
                                            : m("div", { class: "cg2-deck-back-default" }, [
                                                m("div", { class: "cg2-back-pattern" }),
                                                m("span", { class: "material-symbols-outlined cg2-back-icon" }, "playing_cards"),
                                                m("div", { class: "cg2-back-label" }, d.themeId || "Deck")
                                            ])
                                    ]),
                                    // Deck name + campaign info
                                    m("div", { class: "cg2-deck-name" }, d.deckName),
                                    d.campaign ? m("div", { class: "cg2-deck-campaign-info" }, [
                                        m("span", { class: "cg2-campaign-level" }, "Lv." + d.campaign.level),
                                        m("span", { class: "cg2-campaign-xp" }, d.campaign.xp + " XP"),
                                        m("span", { class: "cg2-campaign-record" }, d.campaign.wins + "W/" + d.campaign.losses + "L")
                                    ]) : null,
                                    // Action buttons
                                    m("div", { class: "cg2-deck-actions" }, [
                                        d.hasSave ? m("button", {
                                            class: "cg2-btn cg2-btn-accent",
                                            onclick: () => resumeGame(d.storageName),
                                            title: "Resume saved game"
                                        }, [
                                            m("span", { class: "material-symbols-outlined" }, "restore"),
                                            " Resume"
                                        ]) : null,
                                        m("button", {
                                            class: "cg2-btn cg2-btn-primary",
                                            onclick: () => playDeck(d.storageName),
                                            title: d.hasSave ? "Start a new game (deletes save)" : "Start a game with this deck"
                                        }, [
                                            m("span", { class: "material-symbols-outlined" }, "play_arrow"),
                                            d.hasSave ? " New Game" : " Play"
                                        ]),
                                        m("button", { class: "cg2-btn", onclick: () => viewDeck(d.storageName) }, "View"),
                                        m("button", { class: "cg2-btn cg2-btn-danger", onclick: () => deleteDeck(d.storageName) }, "Delete")
                                    ])
                                ]);
                            })
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
                    // Generate Characters panel (Phase 7.1)
                    m("div", { class: "cg2-gen-panel", style: { marginBottom: "16px", padding: "12px", background: "#f9f9f9", borderRadius: "8px", border: "1px solid #e0e0e0" } }, [
                        m("div", { style: { fontWeight: 600, marginBottom: "8px" } }, "Quick Start: Generate Balanced Characters"),
                        m("p", { style: { fontSize: "12px", color: "#666", margin: "0 0 12px 0" } },
                            "Creates 8 characters with balanced stats from templates. Each character has a unique name and appearance, but stats are balanced for fair gameplay."),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: generatingCharacters,
                            style: { display: "flex", alignItems: "center", gap: "8px" },
                            async onclick() {
                                if (generatingCharacters) return;
                                const themeId = activeTheme?.themeId || "high-fantasy";
                                const generated = await generateCharactersFromTemplates(themeId, 8);
                                if (generated.length > 0) {
                                    // Add generated characters to available list and auto-select them
                                    for (const charPerson of generated) {
                                        availableCharacters.push(charPerson);
                                        if (selectedChars.length < 8) {
                                            selectedChars.push(charPerson);
                                        }
                                    }
                                }
                                m.redraw();
                            }
                        }, [
                            generatingCharacters
                                ? m("span", { class: "material-symbols-outlined cg2-spin" }, "progress_activity")
                                : m("span", { class: "material-symbols-outlined" }, "auto_awesome"),
                            generatingCharacters ? "Generating..." : "Generate 8 Characters"
                        ])
                    ]),
                    charsLoading
                        ? m("div", { class: "cg2-loading" }, "Loading characters...")
                        : m("div", { class: "cg2-char-grid" }, [
                            ...availableCharacters.map(ch => charPickerCard(ch)),
                            m("div", {
                                class: "cg2-char-card cg2-char-card-new",
                                disabled: generatingCharacters,
                                async onclick() {
                                    if (generatingCharacters) return;
                                    const themeId = activeTheme?.themeId || "high-fantasy";
                                    const generated = await generateCharactersFromTemplates(themeId, 1);
                                    if (generated.length > 0) {
                                        availableCharacters.push(generated[0]);
                                        if (selectedChars.length < 8) {
                                            selectedChars.push(generated[0]);
                                        }
                                    }
                                    m.redraw();
                                }
                            }, [
                                generatingCharacters
                                    ? m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "48px", color: "#888" } }, "progress_activity")
                                    : m("span", { class: "material-symbols-outlined", style: { fontSize: "48px", color: "#888" } }, "add_circle"),
                                m("div", { style: { fontWeight: 600, marginTop: "8px" } }, "Add One"),
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
                            disabled: !selectedChars.length || applyingOutfits || buildingDeck,
                            async onclick() {
                                if (!selectedChars.length || applyingOutfits || buildingDeck) return;

                                // Persist any generated characters first (need real IDs for outfits)
                                const unpersisted = selectedChars.filter(ch => ch._tempId && !ch.objectId);
                                if (unpersisted.length > 0) {
                                    // Need a deck name to persist characters
                                    let tempDeckName = deckNameInput.trim() || "Deck_" + Date.now();
                                    buildingDeck = true;
                                    m.redraw();
                                    page.toast("info", "Saving characters...");
                                    const persisted = await persistGeneratedCharacters(unpersisted, tempDeckName);
                                    // Update selectedChars with persisted versions (have objectId now)
                                    for (const p of persisted) {
                                        const idx = selectedChars.findIndex(ch => ch._tempId && ch.name === p.name);
                                        if (idx >= 0) {
                                            selectedChars[idx] = p;
                                        }
                                    }
                                    deckNameInput = tempDeckName;
                                    buildingDeck = false;
                                }

                                builtDeck = assembleStarterDeck(selectedChars, activeTheme);
                                builtDeck.deckName = deckNameInput || builtDeck.deckName;
                                if (activeTheme && activeTheme.outfits) {
                                    await applyThemeOutfits(builtDeck, activeTheme, true);
                                }
                                builderStep = 3;
                                m.redraw();
                            }
                        }, buildingDeck ? "Saving Characters..." : (applyingOutfits ? "Applying Outfits..." : "Next: Review Deck \u2192"))
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
                ch.age ? ", " + ch.age : "",
                ch.trades && ch.trades[0] ? " - " + ch.trades[0] : ""
            ]),
            m("div", { class: "cg2-char-stats-mini" }, [
                ["STR", "AGI", "END"].map(s => m("span", { key: s }, s + ":" + clampStat(stats[s]))),
                m("br"),
                ["INT", "MAG", "CHA"].map(s => m("span", { key: s }, s + ":" + clampStat(stats[s])))
            ]),
            isSelected ? m("div", { class: "cg2-char-badge" }, "\u2713") : null,
            ch._customChar ? m("div", { class: "cg2-char-source-badge" }, "Custom") : null,
            ch._tempId ? m("div", { class: "cg2-char-source-badge", style: { background: "#4a7c59" } }, "Generated") : null
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
                                m(CardFace, { card, full: true }),
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

    // Descriptive prompt snippets for action cards by name
    const ACTION_PROMPTS = {
        "Attack":      "a warrior mid-strike with weapon raised",
        "Strike":      "a warrior mid-strike with weapon raised",
        "Flee":        "a person running away from danger, motion blur",
        "Investigate": "a person with lantern searching for clues in shadows",
        "Trade":       "two merchants exchanging goods at a market stall",
        "Rest":        "a weary traveler resting by a warm campfire",
        "Use Item":    "hands holding a glowing magical item",
        "Craft":       "hands crafting an item at a workbench with tools",
        "Guard":       "a warrior in defensive stance with shield raised",
        "Feint":       "a cunning fighter making a deceptive move"
    };

    // Descriptive prompt snippets for talk cards by name
    const TALK_PROMPTS = {
        "Talk":     "two people in animated conversation",
        "Taunt":    "a confident figure taunting an opponent with a smirk",
        "Persuade": "a charming speaker convincing a skeptical listener"
    };

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

        let sLabel = styleLabel(style);
        let baseName = card.name || card.type;
        let typeName = (card.fabric || card.material) ? (card.fabric || card.material) + " " + baseName : baseName;

        // Action cards - use descriptive scene prompts
        if (card.type === "action") {
            let scene = ACTION_PROMPTS[card.name] || "a dramatic action scene depicting " + baseName.toLowerCase();
            return "Close-up " + sLabel + " of ((" + scene + ")), " + suffix;
        }

        // Talk cards - use conversation/speech prompts
        if (card.type === "talk") {
            let scene = TALK_PROMPTS[card.name] || "two people engaged in " + baseName.toLowerCase();
            return "Close-up " + sLabel + " of ((" + scene + ")), " + suffix;
        }

        // Skill cards - mystical glyph/ability representation
        if (card.type === "skill") {
            return "Close-up " + sLabel + " of ((mystical glowing glyph representing " + baseName.toLowerCase() + " ability)), ornate magical symbol, " + suffix;
        }

        // Item cards - show the item itself
        if (card.type === "item") {
            if (card.subtype === "weapon") {
                return "Close-up " + sLabel + " of ((" + typeName + ")), detailed weapon, " + suffix;
            } else if (card.subtype === "armor") {
                return "Close-up " + sLabel + " of ((" + typeName + ")), detailed armor piece, " + suffix;
            } else if (card.subtype === "consumable") {
                return "Close-up " + sLabel + " of ((" + typeName + ")), magical potion or item on a table, " + suffix;
            } else if (card.subtype === "material") {
                return "Close-up " + sLabel + " of ((raw " + typeName + ")), crafting material, " + suffix;
            }
            return "Close-up " + sLabel + " of ((" + typeName + ")), detailed item, " + suffix;
        }

        // Default fallback for any other card type
        return "Close-up " + sLabel + " of ((" + typeName + ")), " + suffix;
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
    let tabletopImageId = null;     // objectId of tabletop surface image
    let tabletopThumbUrl = null;    // thumbnail URL of tabletop
    let tabletopGenerating = false;
    let flippedCards = {};          // { cardIndex: true } for character cards showing back
    let cardFrontImageUrl = null;   // background image for card faces
    let cardBackImageUrl = null;    // background image for card backs
    let cardFrontGenerating = false; // status for card front template generation
    let cardBackGenerating = false;  // status for card back template generation

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

    // ── Card Preview Overlay ──────────────────────────────────────────
    let previewCard = null;
    let previewFlipped = false;
    function showCardPreview(card) {
        if (!card) return;
        previewCard = card;
        previewFlipped = false;  // Always start showing front
        m.redraw();
    }
    function closeCardPreview() {
        previewCard = null;
        previewFlipped = false;
        m.redraw();
    }
    function CardPreviewOverlay() {
        return {
            view() {
                if (!previewCard) return null;
                let cardFrontBg = viewingDeck && viewingDeck.cardFrontImageUrl
                    ? viewingDeck.cardFrontImageUrl : null;
                let cardBackBg = viewingDeck && viewingDeck.cardBackImageUrl
                    ? viewingDeck.cardBackImageUrl : null;

                // Build back side content - card art or generic back
                let backContent;
                if (previewCard.imageUrl || previewCard.portraitUrl) {
                    let artUrl = (previewCard.imageUrl || previewCard.portraitUrl).replace(/\/\d+x\d+/, "/512x512");
                    backContent = m("div", {
                        class: "cg2-card cg2-card-full cg2-preview-back",
                        style: {
                            backgroundImage: cardBackBg ? "url('" + cardBackBg + "')" : "none",
                            backgroundSize: "cover",
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "center"
                        }
                    }, [
                        m("img", {
                            src: artUrl,
                            class: "cg2-preview-back-art"
                        })
                    ]);
                } else {
                    // No art - show card back design
                    backContent = m("div", {
                        class: "cg2-card cg2-card-full cg2-preview-back",
                        style: {
                            backgroundImage: cardBackBg ? "url('" + cardBackBg + "')" : "none",
                            backgroundSize: "cover",
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "center"
                        }
                    }, [
                        m("div", { class: "cg2-card-back-design" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:64px;opacity:0.5" }, "style")
                        ])
                    ]);
                }

                return m("div", {
                    class: "cg2-card-preview-overlay",
                    onclick: closeCardPreview
                }, [
                    m("div", {
                        class: "cg2-card-preview-container cg2-preview-flipper" + (previewFlipped ? " cg2-flipped" : ""),
                        onclick(e) {
                            e.stopPropagation();
                            previewFlipped = !previewFlipped;
                        }
                    }, [
                        // Front face
                        m("div", { class: "cg2-preview-face cg2-preview-front" }, [
                            m(CardFace, { card: previewCard, bgImage: cardFrontBg, full: true })
                        ]),
                        // Back face
                        m("div", { class: "cg2-preview-face cg2-preview-back-face" }, [
                            backContent
                        ])
                    ]),
                    m("div", { class: "cg2-preview-hint" }, "Click card to flip"),
                    m("span", {
                        class: "material-symbols-outlined cg2-preview-close",
                        onclick: closeCardPreview
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
        if (!card) return;
        if (!card.sourceId && card._sourceChar) {
            page.toast("info", "Save deck first to access gallery for generated characters");
            return;
        }
        if (!card.sourceId) return;
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
        if (!card || sequenceCardId) return;
        if (!card.sourceId && card._sourceChar) {
            page.toast("info", "Save deck first to generate images for generated characters");
            return;
        }
        if (!card.sourceId) return;
        sequenceCardId = card.sourceId;
        sequenceProgress = "Loading character...";
        page.toast("info", "Image Sequence: Loading character...", -1);
        m.redraw();

        try {
            // Load character to get store reference
            am7client.clearCache("olio.charPerson");
            let char = await page.searchFirst("olio.charPerson", undefined, undefined, card.sourceId);
            if (!char || !char.store || !char.store.objectId) throw new Error("Could not load character store");

            // Use getFull on store to get apparel with proper inuse flags
            await am7client.clearCache("olio.store");
            let sto = await am7client.getFull("olio.store", char.store.objectId);
            if (!sto || !sto.apparel || !sto.apparel.length) {
                throw new Error("No apparel found in store");
            }

            let activeApparel = sto.apparel.find(a => a.inuse) || sto.apparel[0];

            // Use getFull on apparel to get wearables with proper inuse flags
            await am7client.clearCache("olio.apparel");
            let fullApparel = await am7client.getFull("olio.apparel", activeApparel.objectId);
            if (!fullApparel || !fullApparel.wearables || !fullApparel.wearables.length) {
                throw new Error("No wearables found in apparel");
            }
            let wears = fullApparel.wearables;

            // Get sorted unique wear levels
            let lvls = [...new Set(wears.sort((a, b) => {
                let aL = am7model.enums.wearLevelEnumType.findIndex(we => we == a.level.toUpperCase());
                let bL = am7model.enums.wearLevelEnumType.findIndex(we => we == b.level.toUpperCase());
                return aL < bL ? -1 : aL > bL ? 1 : 0;
            }).map(w => w.level.toUpperCase()))];

            if (!lvls.length) throw new Error("No wear levels found");

            // Dress down completely
            sequenceProgress = "Dressing down...";
            page.toast("info", "Image Sequence: Dressing down...", -1);
            m.redraw();
            let dressedDown = true;
            while (dressedDown === true) {
                dressedDown = await am7olio.dressApparel(activeApparel, false);
            }

            // Update narrative to reflect fully undressed state
            am7client.clearCache("olio.charPerson");
            await m.request({ method: 'GET', url: am7client.base() + "/olio/olio.charPerson/" + card.sourceId + "/narrate", withCredentials: true });

            let images = [];
            let totalImages = lvls.length + 1; // +1 for undressed
            let baseSeed = -1;

            // Build base SD entity for character reimage
            let sdBase = await buildSdEntity(card, activeTheme);

            // Generate first image fully undressed (NONE level)
            sequenceProgress = "Generating image 1 of " + totalImages + " (NONE)...";
            page.toast("info", "Image Sequence: Generating 1 of " + totalImages + " (NONE)...", -1);
            m.redraw();

            let sdEntity = Object.assign({}, sdBase);
            sdEntity.seed = baseSeed;

            let x = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/olio.charPerson/" + card.sourceId + "/reimage",
                body: sdEntity,
                withCredentials: true
            });

            if (x) {
                let seedAttr = (x.attributes || []).filter(a => a.name == "seed");
                if (seedAttr.length) baseSeed = seedAttr[0].value;
                await am7client.patchAttribute(x, "wearLevel", "NONE/0");
                images.push(x);
            }

            // Generate image at each wear level (dressing up progressively)
            for (let i = 0; i < lvls.length; i++) {
                await am7olio.dressApparel(activeApparel, true);

                // Update narrative so reimage reflects the new clothing state
                am7client.clearCache("olio.charPerson");
                await m.request({ method: 'GET', url: am7client.base() + "/olio/olio.charPerson/" + card.sourceId + "/narrate", withCredentials: true });

                sequenceProgress = "Generating image " + (images.length + 1) + " of " + totalImages + " (" + lvls[i] + ")...";
                page.toast("info", "Image Sequence: Generating " + (images.length + 1) + " of " + totalImages + " (" + lvls[i] + ")...", -1);
                m.redraw();

                let useSeed = (parseInt(baseSeed) + images.length);
                sdEntity = Object.assign({}, sdBase);
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

                // Tag image with wear level
                let levelIndex = am7model.enums.wearLevelEnumType.findIndex(we => we == lvls[i]);
                await am7client.patchAttribute(x, "wearLevel", lvls[i] + "/" + levelIndex);

                images.push(x);
            }

            sequenceProgress = "";
            sequenceCardId = null;
            m.redraw();

            page.clearToast();  // Clear indefinite progress toast
            if (images.length > 0) {
                page.toast("success", "Created " + images.length + " images for " + card.name);

                // Update card portrait to the LAST generated image (fully dressed)
                let lastImage = images[images.length - 1];
                if (lastImage && lastImage.groupPath && lastImage.name) {
                    let orgPath = am7client.dotPath(am7client.currentOrganization);
                    card.portraitUrl = g_application_path + "/thumbnail/" + orgPath +
                        "/data.data" + lastImage.groupPath + "/" + lastImage.name + "/256x256?t=" + Date.now();
                    console.log("[CardGame v2] Updated card portrait to last sequence image:", card.portraitUrl);

                    // Save the deck with updated portrait
                    if (viewingDeck) {
                        let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                        await deckStorage.save(safeName, viewingDeck);
                    }
                }

                m.redraw();
                // Open the gallery picker so user can choose a different portrait if desired
                openGalleryPicker(card);
            } else {
                page.toast("warn", "No images were generated");
            }

        } catch (e) {
            console.error("[CardGame v2] Image sequence failed:", e);
            page.clearToast();  // Clear indefinite progress toast
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

    // ── Per-deck Game Config (LLM, Voice) ────────────────────────────
    let gameConfigExpanded = false;
    let voiceProfiles = [];       // Cached identity.voice records
    let voiceProfilesLoaded = false;

    async function loadVoiceProfiles() {
        if (voiceProfilesLoaded) return voiceProfiles;
        try {
            let q = am7view.viewQuery(am7model.newInstance("identity.voice"));
            let qr = await page.search(q);
            voiceProfiles = qr?.results || [];
            voiceProfilesLoaded = true;
            console.log("[CardGame v2] Voice profiles loaded:", voiceProfiles.length);
            m.redraw();
        } catch (e) {
            console.warn("[CardGame v2] Failed to load voice profiles:", e);
            voiceProfiles = [];
        }
        return voiceProfiles;
    }

    function getDeckGameConfig(deck) {
        if (!deck) return {};
        if (!deck.gameConfig) deck.gameConfig = {};
        return deck.gameConfig;
    }

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

    // Generate a tabletop surface image for the game play area
    async function generateTabletop(theme) {
        let t = theme || activeTheme;
        let ttOv = sdOverrides.tabletop;
        // Use tabletop override prompt if set, otherwise use theme setting or default
        let ttPrompt = (ttOv && ttOv.description) ||
            (t.artStyle && t.artStyle.tabletopPrompt) ||
            "Wooden table surface with subtle texture, warm lighting, top-down view, game table";

        tabletopGenerating = true;
        m.redraw();

        try {
            let dir = await ensureArtDir(t.themeId);
            if (!dir) throw new Error("Could not create art directory");

            let entity = await am7sd.buildEntity({
                description: ttPrompt,
                bodyStyle: "landscape",
                seed: -1,
                groupPath: dir.path,
                imageName: "tabletop-" + t.themeId + "-" + Date.now() + ".png"
            });
            am7sd.applyOverrides(entity, sdOverrides._default);
            am7sd.applyOverrides(entity, getCardTypeDelta("tabletop"));
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

            if (!result || !result.objectId) throw new Error("Tabletop generation returned no result");

            tabletopImageId = result.objectId;
            tabletopThumbUrl = g_application_path + "/thumbnail/" +
                am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + (result.groupPath || dir.path) + "/" + result.name + "/256x256";

            // Save tabletop reference on the deck
            if (viewingDeck) {
                viewingDeck.tabletopImageId = tabletopImageId;
                viewingDeck.tabletopThumbUrl = tabletopThumbUrl;
                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                await deckStorage.save(safeName, viewingDeck);
            }

            page.toast("success", "Tabletop generated");
            console.log("[CardGame v2] Tabletop generated:", tabletopImageId);
        } catch (e) {
            console.error("[CardGame v2] Tabletop generation failed:", e);
            page.toast("error", "Tabletop failed: " + (e.message || "Unknown error"));
        }

        tabletopGenerating = false;
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
            let result = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/olio.charPerson/" + card.sourceId + "/reimage",
                body: sdEntity,
                withCredentials: true
            });
            // Build URL from result image using groupPath + name (required by thumbnail servlet)
            let thumbUrl = null;
            console.log("[CardGame v2] Reimage result:", JSON.stringify({
                objectId: result?.objectId, groupPath: result?.groupPath, name: result?.name
            }));
            if (result && result.groupPath && result.name) {
                let orgPath = am7client.dotPath(am7client.currentOrganization);
                thumbUrl = g_application_path + "/thumbnail/" + orgPath +
                    "/data.data" + result.groupPath + "/" + result.name + "/256x256?t=" + Date.now();
                console.log("[CardGame v2] Built thumbUrl:", thumbUrl);
            } else {
                console.warn("[CardGame v2] Reimage result missing groupPath or name, cannot build URL");
            }
            return { reimaged: true, sourceId: card.sourceId, thumbUrl: thumbUrl, objectId: result?.objectId, groupPath: result?.groupPath, name: result?.name };
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

        // Set generating status for this side
        if (side === "front") cardFrontGenerating = true;
        else cardBackGenerating = true;
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

        // Clear generating status
        if (side === "front") cardFrontGenerating = false;
        else cardBackGenerating = false;
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
                    // For character cards, set portraitUrl; for others, set imageUrl
                    if (result.reimaged) {
                        deckCard.portraitUrl = result.thumbUrl;
                        deckCard.artObjectId = result.objectId;
                    } else {
                        deckCard.imageUrl = result.thumbUrl;
                        deckCard.artObjectId = result.objectId;
                    }
                } else if (result.reimaged && deckCard.sourceId) {
                    // Fallback: re-fetch the charPerson to get the updated portrait
                    await refreshCharacterCard(deckCard);
                }

                // Propagate art to duplicate cards in deck
                if (next.duplicateIndices && next.duplicateIndices.length > 0 && result.thumbUrl) {
                    next.duplicateIndices.forEach(dupeIdx => {
                        let dupeCard = viewingDeck.cards[dupeIdx];
                        if (dupeCard) {
                            if (result.reimaged) {
                                dupeCard.portraitUrl = result.thumbUrl;
                            } else {
                                dupeCard.imageUrl = result.thumbUrl;
                            }
                            dupeCard.artObjectId = result.objectId;
                        }
                    });
                    console.log("[CardGame v2] Shared art with " + next.duplicateIndices.length + " duplicate(s) of " + deckCard.name);
                }

                // Propagate art to cards in active game state (hand, drawPile, discardPile)
                if (gameState && result.thumbUrl) {
                    propagateArtToGameState(deckCard.name, deckCard.type, result.thumbUrl, result.reimaged);
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

    // Propagate generated art to cards in the active game state
    function propagateArtToGameState(cardName, cardType, thumbUrl, isPortrait) {
        if (!gameState) return;

        let updated = 0;
        const updateCard = (card) => {
            if (card && card.name === cardName && card.type === cardType) {
                if (isPortrait) {
                    card.portraitUrl = thumbUrl;
                } else {
                    card.imageUrl = thumbUrl;
                }
                updated++;
            }
        };

        // Update player cards
        if (gameState.player) {
            (gameState.player.hand || []).forEach(updateCard);
            (gameState.player.drawPile || []).forEach(updateCard);
            (gameState.player.discardPile || []).forEach(updateCard);
            (gameState.player.cardStack || []).forEach(updateCard);
        }

        // Update opponent cards
        if (gameState.opponent) {
            (gameState.opponent.hand || []).forEach(updateCard);
            (gameState.opponent.drawPile || []).forEach(updateCard);
            (gameState.opponent.discardPile || []).forEach(updateCard);
            (gameState.opponent.cardStack || []).forEach(updateCard);
        }

        // Update action bar cards
        if (gameState.actionBar?.positions) {
            gameState.actionBar.positions.forEach(pos => {
                if (pos.stack?.coreCard) updateCard(pos.stack.coreCard);
                (pos.stack?.modifiers || []).forEach(updateCard);
            });
        }

        // Update pot
        (gameState.pot || []).forEach(updateCard);

        if (updated > 0) {
            console.log("[CardGame v2] Propagated art to", updated, "card(s) in game state:", cardName);
        }
    }

    // Build a unique signature for a card to detect duplicates
    function cardSignature(card) {
        if (card.type === "character") {
            // Characters are unique by sourceId or _sourceChar._tempId
            return "char:" + (card.sourceId || (card._sourceChar && card._sourceChar._tempId) || card.name);
        }
        // Items/powers: type + name + material/fabric
        return (card.type || "") + ":" + (card.name || "") + ":" + (card.fabric || card.material || "");
    }

    // Queue art generation for all cards in a deck (including front/back templates)
    async function queueDeckArt(deck) {
        if (!deck || !deck.cards) return;
        artQueue = [];
        artCompleted = 0;
        artTotal = 0;
        artPaused = false;
        artDir = null;

        // First, generate missing templates, background, and tabletop
        let needFront = !deck.cardFrontImageUrl && !cardFrontImageUrl;
        let needBack = !deck.cardBackImageUrl && !cardBackImageUrl;
        let needBg = !deck.backgroundImageId && !backgroundImageId;
        let needTabletop = !deck.tabletopImageId && !tabletopImageId;
        let preGenCount = (needFront ? 1 : 0) + (needBack ? 1 : 0) + (needBg ? 1 : 0) + (needTabletop ? 1 : 0);

        if (preGenCount > 0) {
            console.log("[CardGame v2] Generating " + preGenCount + " missing template/environment images first...");
            if (needFront) {
                artTotal++; m.redraw();
                console.log("[CardGame v2] Generating card front template...");
                await generateTemplateArt("front");
                artCompleted++; m.redraw();
            }
            if (needBack) {
                artTotal++; m.redraw();
                console.log("[CardGame v2] Generating card back template...");
                await generateTemplateArt("back");
                artCompleted++; m.redraw();
            }
            if (needBg) {
                artTotal++; m.redraw();
                console.log("[CardGame v2] Generating background...");
                await generateBackground(activeTheme);
                artCompleted++; m.redraw();
            }
            if (needTabletop) {
                artTotal++; m.redraw();
                console.log("[CardGame v2] Generating tabletop...");
                await generateTabletop(activeTheme);
                artCompleted++; m.redraw();
            }
        }

        // Track unique cards and their duplicates
        let uniqueCards = {};  // signature -> { cardIndex, duplicateIndices: [] }

        deck.cards.forEach((card, i) => {
            // Skip cards that already have art
            if (card.type === "character" && card.portraitUrl) return;
            if (card.type !== "character" && card.imageUrl) return;

            let sig = cardSignature(card);
            if (uniqueCards[sig]) {
                // This is a duplicate - will share art with the first one
                uniqueCards[sig].duplicateIndices.push(i);
            } else {
                // First occurrence - queue for generation
                uniqueCards[sig] = { cardIndex: i, duplicateIndices: [] };
                artQueue.push({ card: card, cardIndex: i, status: "pending", result: null, error: null, duplicateIndices: [] });
                artTotal++;
            }
        });

        // Attach duplicate indices to queued items
        artQueue.forEach(job => {
            let sig = cardSignature(job.card);
            if (uniqueCards[sig]) {
                job.duplicateIndices = uniqueCards[sig].duplicateIndices;
            }
        });

        let dupeCount = Object.values(uniqueCards).reduce((sum, u) => sum + u.duplicateIndices.length, 0);
        if (dupeCount > 0) {
            console.log("[CardGame v2] Found " + dupeCount + " duplicate cards that will share art");
        }

        if (artTotal === 0) {
            page.toast("info", "All cards already have art");
            return;
        }

        console.log("[CardGame v2] Queued " + artTotal + " unique cards for art generation");
        processArtQueue();
    }

    async function saveArtProgress() {
        if (viewingDeck && artCompleted > 0) {
            let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
            await deckStorage.save(safeName, viewingDeck);
            console.log("[CardGame v2] Deck saved with " + artCompleted + " completed art images");
        }
    }

    async function pauseArtQueue() {
        artPaused = true;
        await saveArtProgress();
        page.toast("info", "Art generation paused. Progress saved (" + artCompleted + " of " + artTotal + ")");
        m.redraw();
    }

    function resumeArtQueue() {
        artPaused = false;
        m.redraw();
        processArtQueue();
    }

    async function cancelArtQueue() {
        artQueue.forEach(j => { if (j.status === "pending") j.status = "cancelled"; });
        artPaused = false;
        artProcessing = false;
        await saveArtProgress();
        if (artCompleted > 0) {
            page.toast("info", "Art generation cancelled. Progress saved (" + artCompleted + " of " + artTotal + ")");
        }
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
                await refreshCharacterCard(card);
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
                // Show progress for any active generation (queue, background, tabletop, sequence)
                let pending = artQueue.filter(j => j.status === "pending").length;
                let processing = artQueue.filter(j => j.status === "processing").length;
                let failed = artQueue.filter(j => j.status === "failed").length;
                let done = artQueue.filter(j => j.status === "complete").length;
                let queueActive = pending > 0 || processing > 0;
                let envActive = backgroundGenerating || tabletopGenerating || cardFrontGenerating || cardBackGenerating;
                let seqActive = !!sequenceCardId;
                let anyActive = queueActive || envActive || seqActive;

                // Total includes pre-gen (artCompleted tracks those) + queued cards
                let totalDone = artCompleted + done;
                let pct = artTotal > 0 ? Math.min(100, Math.round((totalDone / artTotal) * 100)) : 0;

                // Nothing to show if no work has been started
                if (artTotal === 0 && !envActive && !seqActive) return null;

                // Build current status message
                let currentMsg = null;
                if (backgroundGenerating) currentMsg = "Generating background...";
                else if (tabletopGenerating) currentMsg = "Generating tabletop...";
                else if (cardFrontGenerating) currentMsg = "Generating card front template...";
                else if (cardBackGenerating) currentMsg = "Generating card back template...";
                else if (seqActive) currentMsg = sequenceProgress || "Generating image sequence...";
                else if (processing > 0) {
                    let current = artQueue.find(j => j.status === "processing");
                    currentMsg = current ? (current.card.name || "Card") : "Processing...";
                }

                let statusText = anyActive ? "Generating Art..." : (failed > 0 ? "Generation Complete (with errors)" : "Generation Complete");

                return m("div", { class: "cg2-art-progress" }, [
                    m("div", { class: "cg2-art-progress-header" }, [
                        m("span", { style: { fontWeight: 700, fontSize: "13px" } }, statusText),
                        m("span", { style: { fontSize: "12px", color: "#888" } },
                            totalDone + " / " + artTotal + (failed > 0 ? " (" + failed + " failed)" : ""))
                    ]),
                    m("div", { class: "cg2-art-progress-bar" }, [
                        m("div", { class: "cg2-art-progress-fill" + (anyActive ? " cg2-art-progress-fill-active" : ""),
                            style: { width: pct + "%" } })
                    ]),
                    currentMsg ? m("div", { class: "cg2-art-progress-current" }, [
                        m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px" } }, "progress_activity"),
                        " " + currentMsg
                    ]) : null,
                    queueActive ? m("div", { class: "cg2-art-progress-actions" }, [
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

    // ══════════════════════════════════════════════════════════════════
    // ══ PHASE 4: Game State & Round Skeleton ═══════════════════════════
    // ══════════════════════════════════════════════════════════════════

    // ── Game Phases ───────────────────────────────────────────────────
    const GAME_PHASES = {
        INITIATIVE: "initiative",
        EQUIP: "equip",
        THREAT_RESPONSE: "threat_response",  // Phase for responding to beginning/end threats
        DRAW_PLACEMENT: "draw_placement",
        RESOLUTION: "resolution",
        CLEANUP: "cleanup",
        END_THREAT: "end_threat"  // Phase for responding to end-of-round threats
    };

    // ── Status Effects ────────────────────────────────────────────────
    const STATUS_EFFECTS = {
        STUNNED: {
            id: "stunned",
            name: "Stunned",
            icon: "star",
            color: "#FFC107",
            description: "Skip next action",
            duration: 1,
            durationType: "turns"  // "turns", "untilHit", "endOfRound"
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

    // Apply a status effect to an actor
    function applyStatusEffect(actor, effectId, sourceName) {
        if (!actor.statusEffects) actor.statusEffects = [];
        let effectDef = STATUS_EFFECTS[effectId.toUpperCase()];
        if (!effectDef) {
            console.warn("[CardGame v2] Unknown status effect:", effectId);
            return false;
        }
        // Check if already has this effect (refresh duration instead of stacking)
        let existing = actor.statusEffects.find(e => e.id === effectDef.id);
        if (existing) {
            existing.turnsRemaining = effectDef.duration;
            console.log("[CardGame v2] Refreshed", effectDef.name, "on", actor.character?.name || "actor");
            return true;
        }
        // Add new effect
        actor.statusEffects.push({
            id: effectDef.id,
            name: effectDef.name,
            icon: effectDef.icon,
            color: effectDef.color,
            turnsRemaining: effectDef.duration,
            durationType: effectDef.durationType,
            source: sourceName || "Unknown"
        });
        console.log("[CardGame v2] Applied", effectDef.name, "to", actor.character?.name || "actor");
        return true;
    }

    // Remove a status effect from an actor
    function removeStatusEffect(actor, effectId) {
        if (!actor.statusEffects) return;
        let idx = actor.statusEffects.findIndex(e => e.id === effectId);
        if (idx >= 0) {
            let removed = actor.statusEffects.splice(idx, 1)[0];
            console.log("[CardGame v2] Removed", removed.name, "from", actor.character?.name || "actor");
        }
    }

    // Check if actor has a specific status effect
    function hasStatusEffect(actor, effectId) {
        if (!actor.statusEffects) return false;
        return actor.statusEffects.some(e => e.id === effectId);
    }

    // Get total status effect modifiers for an actor
    function getStatusModifiers(actor) {
        let mods = { atk: 0, def: 0, roll: 0 };
        if (!actor.statusEffects) return mods;
        actor.statusEffects.forEach(effect => {
            let def = STATUS_EFFECTS[effect.id.toUpperCase()];
            if (def) {
                if (def.atkBonus) mods.atk += def.atkBonus;
                if (def.defBonus) mods.def += def.defBonus;
                if (def.defPenalty) mods.def += def.defPenalty;
                if (def.rollPenalty) mods.roll += def.rollPenalty;
                if (def.rollBonus) mods.roll += def.rollBonus;
            }
        });
        return mods;
    }

    // Process status effects at turn start (for effects like poison)
    function processStatusEffectsTurnStart(actor) {
        if (!actor.statusEffects) return [];
        let messages = [];
        actor.statusEffects.forEach(effect => {
            let def = STATUS_EFFECTS[effect.id.toUpperCase()];
            if (def && def.onTurnStart) {
                let result = def.onTurnStart(actor);
                if (result && result.message) {
                    messages.push({ effect: effect.name, message: result.message });
                }
            }
        });
        return messages;
    }

    // Tick down status effect durations (call at end of turn/round)
    function tickStatusEffects(actor) {
        if (!actor.statusEffects) return;
        actor.statusEffects = actor.statusEffects.filter(effect => {
            if (effect.durationType === "turns") {
                effect.turnsRemaining--;
                if (effect.turnsRemaining <= 0) {
                    console.log("[CardGame v2]", effect.name, "expired on", actor.character?.name || "actor");
                    return false;
                }
            }
            return true;
        });
    }

    // Remove "until hit" effects when actor is hit
    function onActorHit(actor) {
        if (!actor.statusEffects) return;
        actor.statusEffects = actor.statusEffects.filter(effect => {
            if (effect.durationType === "untilHit") {
                console.log("[CardGame v2]", effect.name, "removed (hit) from", actor.character?.name || "actor");
                return false;
            }
            return true;
        });
    }

    // ── LLM Connectivity ──────────────────────────────────────────────
    let llmStatus = { checked: false, available: false, error: null };

    async function checkLlmConnectivity() {
        llmStatus = { checked: false, available: false, error: null };
        m.redraw();
        try {
            // Test LLM connectivity by fetching chat prompt endpoint
            let response = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/chat/prompt",
                withCredentials: true
            });
            llmStatus = { checked: true, available: true, error: null };
            console.log("[CardGame v2] LLM connectivity check passed");
        } catch (err) {
            llmStatus = { checked: true, available: false, error: err.message || "LLM service unavailable" };
            console.warn("[CardGame v2] LLM connectivity check failed:", err);
        }
        m.redraw();
        return llmStatus.available;
    }

    // ── Game State Model ──────────────────────────────────────────────
    let gameState = null;  // null when not in game
    let gameCharSelection = null;  // { characters: [], selected: null } when picking character

    // ── Initiative Animation State ───────────────────────────────────────
    let initAnimState = {
        countdown: 3,           // Countdown from 3
        rolling: false,         // Whether dice are animating
        rollComplete: false,    // Whether final roll is shown
        playerDiceFace: 1,      // Current displayed face during animation
        opponentDiceFace: 1,
        animInterval: null      // Animation interval ID
    };

    function resetInitAnimState() {
        if (initAnimState.animInterval) clearInterval(initAnimState.animInterval);
        initAnimState = {
            countdown: 3,
            rolling: false,
            rollComplete: false,
            playerDiceFace: 1,
            opponentDiceFace: 1,
            animInterval: null
        };
    }

    function startInitiativeAnimation() {
        resetInitAnimState();

        // Start countdown
        let countdownInterval = setInterval(() => {
            initAnimState.countdown--;
            m.redraw();

            if (initAnimState.countdown <= 0) {
                clearInterval(countdownInterval);
                // Start dice rolling animation
                initAnimState.rolling = true;
                initAnimState.animInterval = setInterval(() => {
                    initAnimState.playerDiceFace = Math.floor(Math.random() * 20) + 1;
                    initAnimState.opponentDiceFace = Math.floor(Math.random() * 20) + 1;
                    m.redraw();
                }, 80);

                // After 2 seconds of rolling, show final result
                setTimeout(() => {
                    clearInterval(initAnimState.animInterval);
                    initAnimState.rolling = false;
                    initAnimState.rollComplete = true;
                    runInitiativePhase();  // Actually compute the roll
                    m.redraw();
                }, 2000);
            }
        }, 1000);
    }

    function createGameState(deck, selectedCharacter) {
        // Use selected character or find first one
        let cards = deck.cards || [];
        let allCharacters = cards.filter(c => c.type === "character");
        let playerCharCard = selectedCharacter || allCharacters[0];

        if (!playerCharCard) {
            console.error("[CardGame v2] No character card found in deck");
            return null;
        }

        // Pick an opponent from remaining characters, or create a generic one
        let otherChars = allCharacters.filter(c => c !== playerCharCard);
        let opponentCharCard = otherChars.length > 0
            ? otherChars[Math.floor(Math.random() * otherChars.length)]
            : null;

        // Calculate initial AP from END: floor(END/5) + 1, minimum 2 AP
        // Check for both uppercase and lowercase stat names, with proper numeric validation
        let playerStats = playerCharCard.stats || {};
        let rawPlayerEnd = playerStats.END ?? playerStats.end ?? playerStats.endurance;
        let playerEnd = (typeof rawPlayerEnd === "number" && rawPlayerEnd > 0) ? rawPlayerEnd : 12;  // Default 12 = 3 AP
        let playerAp = Math.max(2, Math.floor(playerEnd / 5) + 1);  // Minimum 2 AP for playability

        // Calculate initial energy from MAG
        let rawPlayerMag = playerStats.MAG ?? playerStats.mag ?? playerStats.magic;
        let playerMag = (typeof rawPlayerMag === "number" && rawPlayerMag > 0) ? rawPlayerMag : 12;

        console.log("[CardGame v2] Player character:", playerCharCard.name, "raw stats:", JSON.stringify(playerStats));
        console.log("[CardGame v2] Player END:", playerEnd, "(raw:", rawPlayerEnd, ") → AP:", playerAp, "| MAG:", playerMag);

        // Opponent stats - use actual opponent character if available
        let opponentStats = opponentCharCard
            ? Object.assign({}, opponentCharCard.stats || {})
            : Object.assign({}, playerStats);
        let rawOppEnd = opponentStats.END ?? opponentStats.end ?? opponentStats.endurance;
        let opponentEnd = (typeof rawOppEnd === "number" && rawOppEnd > 0) ? rawOppEnd : 12;
        let opponentAp = Math.max(2, Math.floor(opponentEnd / 5) + 1);  // Minimum 2 AP
        let rawOppMag = opponentStats.MAG ?? opponentStats.mag ?? opponentStats.magic;
        let opponentMag = (typeof rawOppMag === "number" && rawOppMag > 0) ? rawOppMag : 12;

        console.log("[CardGame v2] Opponent END:", opponentEnd, "(raw:", rawOppEnd, ") → AP:", opponentAp, "| MAG:", opponentMag);

        // Separate cards by type (cards already defined above)
        let actionCards = cards.filter(c => c.type === "action");
        let talkCards = cards.filter(c => c.type === "talk");
        let itemCards = shuffle(cards.filter(c => c.type === "item"));
        let apparelCards = shuffle(cards.filter(c => c.type === "apparel"));
        let skillCards = cards.filter(c => c.type === "skill");
        let magicCards = cards.filter(c => c.type === "magic");
        let encounterCards = cards.filter(c => c.type === "encounter");

        // Split items and apparel for player vs opponent (so they get different equipment)
        let itemMid = Math.ceil(itemCards.length / 2);
        let apparelMid = Math.ceil(apparelCards.length / 2);
        let playerItems = itemCards.slice(0, itemMid);
        let opponentItems = itemCards.slice(itemMid);
        let playerApparel = apparelCards.slice(0, apparelMid);
        let opponentApparel = apparelCards.slice(apparelMid);

        // Create playable card pool (actions, skills, magic, talk)
        let playableCards = [...actionCards, ...talkCards, ...skillCards, ...magicCards];

        console.log("[CardGame v2] Card counts - action:", actionCards.length,
            "talk:", talkCards.length, "skill:", skillCards.length,
            "magic:", magicCards.length, "playable total:", playableCards.length);

        // If deck has no playable cards, create basic cards
        if (playableCards.length === 0) {
            console.log("[CardGame v2] No playable cards in deck, using default starter cards");
            playableCards = [
                { type: "action", name: "Attack", actionType: "Offensive", energyCost: 0 },
                { type: "action", name: "Attack", actionType: "Offensive", energyCost: 0 },
                { type: "action", name: "Guard", actionType: "Defensive", energyCost: 0 },
                { type: "action", name: "Guard", actionType: "Defensive", energyCost: 0 },
                { type: "action", name: "Rest", actionType: "Recovery", energyCost: 0 },
                { type: "action", name: "Rest", actionType: "Recovery", energyCost: 0 },
                { type: "action", name: "Feint", actionType: "Utility", energyCost: 0 },
                { type: "talk", name: "Taunt", speechType: "Provoke", energyCost: 0 },
                { type: "talk", name: "Persuade", speechType: "Charm", energyCost: 0 }
            ];
        }

        // Create draw piles for both sides (shuffle and split)
        let shuffledPool = shuffle([...playableCards, ...playableCards]); // Double the pool
        let midPoint = Math.floor(shuffledPool.length / 2);
        let playerDrawPile = shuffledPool.slice(0, midPoint);
        let opponentDrawPile = shuffledPool.slice(midPoint);

        // Deal initial hands (5 cards each)
        let initialHandSize = 5;
        let playerHand = playerDrawPile.splice(0, initialHandSize);
        let opponentHand = opponentDrawPile.splice(0, initialHandSize);

        console.log("[CardGame v2] Initial hands dealt - player:", playerHand.length, "opponent:", opponentHand.length);
        console.log("[CardGame v2] Player hand:", playerHand.map(c => c.name));
        console.log("[CardGame v2] Draw piles - player:", playerDrawPile.length, "opponent:", opponentDrawPile.length);

        let state = {
            // Meta
            deckName: deck.deckName,
            themeId: deck.themeId,
            startedAt: Date.now(),

            // Round tracking
            round: 1,
            phase: GAME_PHASES.INITIATIVE,

            // Initiative
            initiative: {
                playerRoll: null,      // { raw, modifier, total }
                opponentRoll: null,
                winner: null,          // "player" | "opponent"
                playerPositions: [],   // [1,3,5,...] or [2,4,6,...]
                opponentPositions: []
            },

            // Action Bar
            actionBar: {
                totalPositions: 0,
                positions: [],  // [{ owner, stack, resolved }]
                resolveIndex: -1  // current resolution position (-1 = not started)
            },

            // Player state
            player: {
                character: playerCharCard,
                hp: 20,
                maxHp: 20,
                energy: playerMag,
                maxEnergy: playerMag,
                morale: 20,
                maxMorale: 20,
                ap: playerAp,
                maxAp: playerAp,
                apUsed: 0,
                hand: playerHand,
                drawPile: playerDrawPile,
                discardPile: [],
                // Card stack: modifier cards placed on character (apparel, items, buffs)
                cardStack: dealInitialStack(playerApparel, playerItems),
                roundPoints: 0,
                statusEffects: [],  // Active status effects (stunned, poisoned, etc.)
                typesPlayedThisRound: {}  // Track action types played this round for hoarding prevention
            },

            // Opponent state (AI) - use actual character from deck if available
            opponent: {
                name: opponentCharCard ? (opponentCharCard.name || "Opponent") : "Challenger",
                character: opponentCharCard || {
                    type: "character",
                    name: "Challenger",
                    race: "UNKNOWN",
                    alignment: "NEUTRAL",
                    level: 1,
                    stats: opponentStats,
                    needs: { hp: 20, energy: opponentMag, morale: 20 },
                    equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
                    activeSkills: [null, null, null, null],
                    portraitUrl: null
                },
                hp: 20,
                maxHp: 20,
                energy: opponentMag,
                maxEnergy: opponentMag,
                morale: 20,
                maxMorale: 20,
                ap: opponentAp,
                maxAp: opponentAp,
                apUsed: 0,
                hand: opponentHand,
                drawPile: opponentDrawPile,
                discardPile: [],
                // Card stack: modifier cards placed on character
                cardStack: dealInitialStack(opponentApparel, opponentItems),
                roundPoints: 0,
                statusEffects: [],  // Active status effects (stunned, poisoned, etc.)
                typesPlayedThisRound: {}  // Track action types played this round for hoarding prevention
            },

            // Encounter deck (shuffled)
            encounterDeck: shuffle([...encounterCards]),

            // Round pot
            pot: [],

            // Timing
            turnTimer: null,
            isPaused: false,

            // Turn tracking for draw/placement
            currentTurn: null,  // "player" | "opponent"
            turnActions: [],    // log of actions this placement phase

            // Beginning threats (from Nat 1 on initiative)
            beginningThreats: [],

            // Threat response state
            threatResponse: {
                active: false,
                type: null,        // "beginning" | "end"
                threats: [],       // Array of active threats
                responder: null,   // "player" | "opponent" - who gets to respond
                bonusAP: 0,        // AP for threat response (limited)
                defenseStack: null,// Stack placed for defense
                resolved: false
            },

            // Chat state (Phase 8)
            chat: {
                active: false,
                unlocked: false,   // True when Talk card is active (Silence Rule)
                messages: [],      // [{ role: "player"|"npc", text, timestamp }]
                npcName: null,
                inputText: "",
                pending: false,    // Waiting for LLM response
                talkCard: null,    // The Talk card that opened this chat
                talkPosition: null // The action bar position for resolution
            }
        };

        // Ensure each player has at least one Attack card in starting hand
        ensureOffensiveCard(state.player, "Player");
        ensureOffensiveCard(state.opponent, "Opponent");

        return state;
    }

    // Initialize LLM components (Director and Narrator) for a game
    let gameAnnouncerVoice = null;  // Separate voice instance for announcer

    async function initializeLLMComponents(state, deck) {
        const themeId = deck?.themeId || activeTheme?.themeId || "high-fantasy";
        const opponentChar = state?.opponent?.character;
        const gc = deck?.gameConfig || {};
        const narrationEnabled = gc.narrationEnabled !== false;       // Default: enabled
        const opponentVoiceEnabled = gc.opponentVoiceEnabled === true; // Default: disabled
        const opponentVoiceProfileId = gc.opponentVoiceProfileId || null;
        const announcerEnabled = gc.announcerEnabled === true;         // Default: disabled
        const announcerProfile = gc.announcerProfile || "arena-announcer";
        const announcerVoiceEnabled = gc.announcerVoiceEnabled === true;
        const announcerVoiceProfileId = gc.announcerVoiceProfileId || null;

        console.log("[CardGame v2] Game config: llm=" + narrationEnabled
            + " oppVoice=" + opponentVoiceEnabled + " (profile=" + (opponentVoiceProfileId || "default") + ")"
            + " announcer=" + announcerEnabled + " (style=" + announcerProfile + " voice=" + announcerVoiceEnabled + ")");

        // Initialize AI Director (optional - falls back to FIFO if unavailable)
        try {
            gameDirector = new CardGameDirector();
            const directorOk = await gameDirector.initialize(opponentChar, themeId);
            if (!directorOk) {
                console.log("[CardGame v2] LLM Director unavailable, using fallback AI");
                gameDirector = null;
            }
        } catch (err) {
            console.warn("[CardGame v2] Failed to initialize Director:", err);
            gameDirector = null;
        }

        // Initialize Announcer narrator (optional — separate from opponent)
        if (announcerEnabled && narrationEnabled) {
            try {
                gameNarrator = new CardGameNarrator();
                const narratorOk = await gameNarrator.initialize(announcerProfile, themeId);
                if (!narratorOk) {
                    console.log("[CardGame v2] Announcer LLM unavailable, using fallback narration");
                    gameNarrator = null;
                }
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize Announcer:", err);
                gameNarrator = null;
            }
        } else if (!announcerEnabled) {
            console.log("[CardGame v2] Announcer disabled by deck config");
            gameNarrator = null;
        } else {
            console.log("[CardGame v2] LLM disabled — announcer uses fallback text");
            gameNarrator = null;
        }

        // Always trigger game start narration (uses fallback if announcer unavailable)
        narrateGameStart();

        // Initialize Chat Manager for Talk cards / opponent dialogue (requires LLM enabled)
        if (narrationEnabled) {
            try {
                gameChatManager = new CardGameChatManager();
                const chatOk = await gameChatManager.initialize(opponentChar, themeId);
                if (!chatOk) {
                    console.log("[CardGame v2] LLM Chat Manager unavailable, Talk cards use fallback. Error:", gameChatManager?.lastError);
                    gameChatManager = null;
                } else {
                    console.log("[CardGame v2] Chat Manager initialized successfully");
                }
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize Chat Manager:", err);
                gameChatManager = null;
            }
        } else {
            gameChatManager = null;
        }

        // Initialize Opponent Voice (the opponent character's TTS voice)
        try {
            gameVoice = new CardGameVoice();
            await gameVoice.initialize({
                subtitlesOnly: !opponentVoiceEnabled,
                voiceProfileId: opponentVoiceProfileId,
                volume: 1.0
            });
        } catch (err) {
            console.warn("[CardGame v2] Failed to initialize opponent voice:", err);
            gameVoice = null;
        }

        // Initialize Announcer Voice (separate TTS for commentary)
        if (announcerEnabled && announcerVoiceEnabled) {
            try {
                gameAnnouncerVoice = new CardGameVoice();
                await gameAnnouncerVoice.initialize({
                    subtitlesOnly: false,
                    voiceProfileId: announcerVoiceProfileId,
                    volume: 1.0
                });
            } catch (err) {
                console.warn("[CardGame v2] Failed to initialize announcer voice:", err);
                gameAnnouncerVoice = null;
            }
        } else {
            gameAnnouncerVoice = null;
        }
    }

    // ── Unified Narration System ────────────────────────────────────────
    // Centralized narration with LLM and fallback support
    async function triggerNarration(trigger, extraContext = {}) {
        if (!gameState) return;
        console.log("[CardGame v2] triggerNarration called:", trigger);

        const playerName = gameState.player?.character?.name || "Player";
        const opponentName = gameState.opponent?.character?.name || "Opponent";
        const baseContext = { playerName, opponentName };

        // Build trigger-specific context
        let context, fallbackText;
        switch (trigger) {
            case "game_start":
                context = { ...baseContext, round: 1 };
                fallbackText = `The arena awaits! ${playerName} faces ${opponentName} in a battle of wits and steel. Let the cards decide your fate!`;
                break;

            case "game_end": {
                const isVictory = extraContext.winner === "player";
                const winnerName = isVictory ? playerName : opponentName;
                const loserName = isVictory ? opponentName : playerName;
                context = { winner: winnerName, loser: loserName, isPlayerVictory: isVictory, rounds: gameState.round };
                fallbackText = isVictory
                    ? `Victory! ${playerName} stands triumphant over ${opponentName} after ${gameState.round} rounds of fierce combat!`
                    : `Defeat... ${opponentName} has bested ${playerName}. The arena falls silent after ${gameState.round} rounds.`;
                break;
            }

            case "round_start":
                context = {
                    ...baseContext,
                    roundNumber: gameState.round,
                    playerHp: gameState.player.hp,
                    opponentHp: gameState.opponent.hp,
                    playerEnergy: gameState.player.energy,
                    opponentEnergy: gameState.opponent.energy
                };
                if (gameState.round <= 1) return; // Round 1 uses game_start
                const tension = (gameState.player.hp < 10 || gameState.opponent.hp < 10)
                    ? " The tension mounts as both combatants show signs of wear." : "";
                fallbackText = `Round ${gameState.round} begins!${tension}`;
                break;

            case "round_end": {
                const roundWinner = extraContext.roundWinner || "tie";
                const winName = roundWinner === "player" ? playerName : roundWinner === "opponent" ? opponentName : "Neither";
                context = {
                    ...baseContext,
                    roundNumber: gameState.round,
                    roundWinner: winName,
                    playerHp: gameState.player.hp,
                    opponentHp: gameState.opponent.hp
                };
                fallbackText = roundWinner === "tie"
                    ? `Round ${gameState.round} ends in a stalemate!`
                    : `${winName} takes Round ${gameState.round}!`;
                break;
            }

            case "resolution": {
                const attackerName = extraContext.attackerName || (extraContext.isPlayerAttack ? playerName : opponentName);
                const defenderName = extraContext.defenderName || (extraContext.isPlayerAttack ? opponentName : playerName);
                const outcome = extraContext.outcome || "Hit";
                const damage = extraContext.damage || 0;
                context = {
                    ...baseContext,
                    attackerName, defenderName, outcome, damage,
                    roundNumber: gameState.round
                };
                if (outcome === "CRIT") {
                    fallbackText = `Critical hit! ${attackerName} devastates ${defenderName} for ${damage} damage!`;
                } else if (outcome === "MISS" || damage <= 0) {
                    fallbackText = `${defenderName} deflects ${attackerName}'s assault!`;
                } else {
                    fallbackText = `${attackerName} strikes ${defenderName} for ${damage} damage!`;
                }
                break;
            }

            default:
                context = { ...baseContext, ...extraContext };
                fallbackText = null;
        }

        // Add Poker Face emotion context if available
        const emotionCtx = buildEmotionContext();
        if (emotionCtx) {
            context.playerEmotion = emotionCtx.emotion;
            context.playerEmotionDesc = emotionCtx.description;
        }

        // Try LLM narration
        let narrationText = null;
        if (gameNarrator?.initialized) {
            try {
                const narration = await gameNarrator.narrate(trigger, context);
                if (narration?.text) {
                    narrationText = narration.text;
                }
            } catch (e) {
                console.warn(`[CardGame v2] ${trigger} narration failed:`, e);
            }
        }

        // Use fallback if LLM failed
        const finalText = narrationText || fallbackText;
        if (!finalText) return;

        // Show subtitle
        showNarrationSubtitle(finalText);

        // Speak with announcer voice (narration/commentary uses announcer, not opponent)
        if (gameAnnouncerVoice?.enabled && !gameAnnouncerVoice.subtitlesOnly) {
            gameAnnouncerVoice.speak(finalText);
        }
    }

    // Convenience wrappers for backward compatibility
    function narrateGameStart() { return triggerNarration("game_start"); }
    function narrateGameEnd(winner) { return triggerNarration("game_end", { winner }); }
    function narrateRoundStart() { return triggerNarration("round_start"); }
    function narrateRoundEnd(roundWinner) { return triggerNarration("round_end", { roundWinner }); }

    // Show narrator text as a subtitle overlay
    function showNarrationSubtitle(text) {
        if (!text) return;
        console.log("[CardGame v2] showNarrationSubtitle:", text.substring(0, 80) + (text.length > 80 ? "..." : ""));
        // Store in game state for UI to display
        if (gameState) {
            gameState.narrationText = text;
            gameState.narrationTime = Date.now();
            m.redraw();

            // Auto-hide after 8 seconds (extended for readability)
            setTimeout(() => {
                if (gameState && gameState.narrationTime && Date.now() - gameState.narrationTime >= 7900) {
                    gameState.narrationText = null;
                    m.redraw();
                }
            }, 8000);
        }
    }

    // ── Dice Utilities (Phase 7.0) ──────────────────────────────────────
    // Centralized dice rolling for testability and consistency
    const DiceUtils = {
        // Roll a die with specified sides (default d20)
        roll(sides = 20) {
            return Math.floor(Math.random() * sides) + 1;
        },

        // Roll with a single modifier, returning breakdown
        rollWithMod(stat = 0, statName = "") {
            const raw = this.roll(20);
            const total = raw + stat;
            const breakdown = statName ? `${raw} + ${stat} ${statName} = ${total}` : `${raw} + ${stat} = ${total}`;
            return { raw, modifier: stat, total, breakdown, isCrit: raw === 20, isFumble: raw === 1 };
        },

        // Initiative roll (d20 + AGI)
        initiative(stats) {
            const raw = this.roll(20);
            const modifier = (stats && stats.AGI) || 0;
            return { raw, modifier, total: raw + modifier };
        }
    };

    // Backwards compatibility
    function rollD20() {
        return DiceUtils.roll(20);
    }

    function rollInitiative(stats) {
        return DiceUtils.initiative(stats);
    }

    // ── Combat Roll System (Phase 5) ─────────────────────────────────
    // Get total ATK bonus from actor's card stack (weapons)
    function getActorATK(actor) {
        let total = 0;
        (actor.cardStack || []).forEach(card => {
            if (card.atk) total += card.atk;
        });
        return total;
    }

    // Check if actor is dual-wielding (two one-handed weapons)
    function isDualWielding(actor) {
        let oneHandedWeapons = (actor.cardStack || []).filter(card =>
            card.type === "item" &&
            card.subtype === "weapon" &&
            card.slot &&
            card.slot.includes("1H")
        );
        return oneHandedWeapons.length >= 2;
    }

    // Get ATK values for each weapon (for dual wield separate rolls)
    function getWeaponATKs(actor) {
        let weapons = (actor.cardStack || []).filter(card =>
            card.type === "item" &&
            card.subtype === "weapon" &&
            card.atk
        );
        return weapons.map(w => ({ name: w.name, atk: w.atk || 0 }));
    }

    // Get total DEF bonus from actor's card stack (armor, apparel)
    function getActorDEF(actor) {
        let total = 0;
        (actor.cardStack || []).forEach(card => {
            if (card.def) total += card.def;
        });
        return total;
    }

    // Get skill modifier from stack modifiers
    // Parses skill card modifier strings like "+2 to Attack rolls"
    // ── Unified Effect Parser ─────────────────────────────────────────
    // Parses an effect string and returns all recognized mechanical effects.
    // Used by magic, consumable, and custom card evaluation.
    // Supported patterns:
    //   "Deal N damage"        → { damage: N }
    //   "Heal N" / "Restore N HP"  → { healHp: N }
    //   "Restore N Energy"     → { restoreEnergy: N }
    //   "Restore N Morale"     → { restoreMorale: N }
    //   "Drain N"              → { damage: N, healHp: N }  (damage + self-heal)
    //   "Draw N"               → { draw: N }
    //   "Burn" / "Ignite"      → { status: "burning", target: "enemy" }
    //   "Bleed"                → { status: "bleeding", target: "enemy" }
    //   "Stun"                 → { status: "stunned", target: "enemy" }
    //   "Poison"               → { status: "poisoned", target: "enemy" }
    //   "Shield" / "Protect"   → { status: "shielded", target: "self" }
    //   "Enrage" / "Fury"      → { status: "enraged", target: "self" }
    //   "Weaken"               → { status: "weakened", target: "enemy" }
    //   "Fortify" / "Bolster"  → { status: "fortified", target: "self" }
    //   "Inspire"              → { status: "inspired", target: "self" }
    //   "Regenerate" / "Regen" → { status: "regenerating", target: "self" }
    //   "Cure" / "Cleanse"     → { cure: true }  (removes negative status)
    function parseEffect(effectStr) {
        if (!effectStr) return {};
        let e = effectStr.toLowerCase();
        let result = {};

        // Numeric extractions
        let damageMatch = effectStr.match(/deal\s+(\d+)/i);
        if (damageMatch) result.damage = parseInt(damageMatch[1], 10);

        let drainMatch = effectStr.match(/drain\s+(\d+)/i);
        if (drainMatch) {
            let val = parseInt(drainMatch[1], 10);
            result.damage = (result.damage || 0) + val;
            result.healHp = (result.healHp || 0) + val;
        }

        let healMatch = effectStr.match(/heal\s+(\d+)|restore\s+(\d+)\s+hp/i);
        if (healMatch) result.healHp = (result.healHp || 0) + parseInt(healMatch[1] || healMatch[2], 10);

        let energyMatch = effectStr.match(/restore\s+(\d+)\s+energy/i);
        if (energyMatch) result.restoreEnergy = parseInt(energyMatch[1], 10);

        let moraleMatch = effectStr.match(/restore\s+(\d+)\s+morale/i);
        if (moraleMatch) result.restoreMorale = parseInt(moraleMatch[1], 10);

        let drawMatch = effectStr.match(/draw\s+(\d+)/i);
        if (drawMatch) result.draw = parseInt(drawMatch[1], 10);

        // Status effect keywords → { statusEffects: [{id, target}] }
        let statuses = [];
        if (e.includes("stun"))                                    statuses.push({ id: "stunned", target: "enemy" });
        if (e.includes("poison"))                                  statuses.push({ id: "poisoned", target: "enemy" });
        if (e.includes("burn") || e.includes("ignite"))            statuses.push({ id: "burning", target: "enemy" });
        if (e.includes("bleed"))                                   statuses.push({ id: "bleeding", target: "enemy" });
        if (e.includes("weaken"))                                  statuses.push({ id: "weakened", target: "enemy" });
        if (e.includes("shield") || e.includes("protect"))         statuses.push({ id: "shielded", target: "self" });
        if (e.includes("enrage") || e.includes("fury"))            statuses.push({ id: "enraged", target: "self" });
        if (e.includes("fortify") || e.includes("bolster"))        statuses.push({ id: "fortified", target: "self" });
        if (e.includes("inspire"))                                 statuses.push({ id: "inspired", target: "self" });
        if (e.includes("regenerat") || e.includes("regen"))        statuses.push({ id: "regenerating", target: "self" });
        if (statuses.length > 0) result.statusEffects = statuses;

        // Cure/cleanse
        if (e.includes("cure") || e.includes("cleanse") || e.includes("purify")) result.cure = true;

        return result;
    }

    // Apply parsed effects to owner/target actors
    function applyParsedEffects(parsed, owner, target, sourceName) {
        let log = [];
        if (parsed.damage && target) {
            let dmgResult = applyDamage(target, parsed.damage);
            log.push(sourceName + " deals " + dmgResult.finalDamage + " damage");
        }
        if (parsed.healHp && owner) {
            let before = owner.hp;
            owner.hp = Math.min(owner.maxHp, owner.hp + parsed.healHp);
            log.push(sourceName + " heals " + (owner.hp - before) + " HP");
        }
        if (parsed.restoreEnergy && owner) {
            owner.energy = Math.min(owner.maxEnergy, owner.energy + parsed.restoreEnergy);
            log.push(sourceName + " restores " + parsed.restoreEnergy + " Energy");
        }
        if (parsed.restoreMorale && owner) {
            owner.morale = Math.min(owner.maxMorale, owner.morale + parsed.restoreMorale);
            log.push(sourceName + " restores " + parsed.restoreMorale + " Morale");
        }
        if (parsed.draw && owner) {
            drawCardsForActor(owner, parsed.draw);
            log.push(sourceName + " draws " + parsed.draw + " card(s)");
        }
        if (parsed.statusEffects) {
            parsed.statusEffects.forEach(se => {
                let actor = se.target === "self" ? owner : target;
                if (actor) applyStatusEffect(actor, se.id, sourceName);
                log.push(sourceName + " applies " + se.id + " to " + (se.target === "self" ? "self" : "target"));
            });
        }
        if (parsed.cure && owner) {
            // Remove all negative status effects from owner
            let negatives = ["stunned", "poisoned", "burning", "bleeding", "weakened"];
            negatives.forEach(id => removeStatusEffect(owner, id));
            log.push(sourceName + " cures negative effects");
        }
        return log;
    }

    // Check if an effect string has any mechanically parseable content
    function isEffectParseable(effectStr) {
        let parsed = parseEffect(effectStr);
        return Object.keys(parsed).length > 0;
    }

    // Skill action keyword map: actionType → keywords that match
    const SKILL_ACTION_KEYWORDS = {
        attack:     ["attack", "combat", "melee", "strike", "offensive"],
        defense:    ["defense", "defend", "parry", "block", "defensive"],
        talk:       ["talk", "social", "charisma", "persuade", "diplomacy", "speech"],
        initiative: ["initiative", "speed", "first"],
        investigate:["investigate", "search", "discover", "perception"],
        flee:       ["flee", "escape", "evasion", "retreat"],
        craft:      ["craft", "create", "forge", "build"],
        magic:      ["magic", "spell", "cast", "arcane", "psionic"]
    };

    function getStackSkillMod(stack, actionType) {
        if (!stack || !stack.modifiers) return 0;
        let total = 0;
        let keywords = SKILL_ACTION_KEYWORDS[actionType] || [actionType];
        stack.modifiers.forEach(mod => {
            if (mod.type === "skill" && mod.modifier) {
                let match = mod.modifier.match(/\+(\d+)/);
                if (match) {
                    let modLower = mod.modifier.toLowerCase();
                    if (keywords.some(kw => modLower.includes(kw))) {
                        total += parseInt(match[1], 10);
                    }
                }
            }
        });
        return total;
    }

    // Roll attack: 1d20 + STR + weapon ATK + skill mods (from stack) + status effects
    function rollAttack(attacker, stack) {
        let raw = rollD20();
        let stats = attacker.character.stats || {};
        let strMod = stats.STR || 0;
        let atkBonus = getActorATK(attacker);
        let skillMod = getStackSkillMod(stack, "attack");

        // Add status effect modifiers
        let statusMods = getStatusModifiers(attacker);
        let statusAtkMod = statusMods.atk + statusMods.roll;

        let total = raw + strMod + atkBonus + skillMod + statusAtkMod;

        let breakdown = `${raw} + ${strMod} STR + ${atkBonus} ATK`;
        if (skillMod) breakdown += ` + ${skillMod} Skill`;
        if (statusAtkMod) breakdown += ` + ${statusAtkMod} Status`;
        breakdown += ` = ${total}`;

        return {
            raw,
            strMod,
            atkBonus,
            skillMod,
            statusMod: statusAtkMod,
            total,
            formula: "1d20 + STR + ATK" + (skillMod ? " + Skill" : "") + (statusAtkMod ? " + Status" : ""),
            breakdown
        };
    }

    // Roll defense: 1d20 + END + armor DEF + weapon parry (if applicable) + status effects
    function rollDefense(defender) {
        let raw = rollD20();
        let stats = defender.character.stats || {};
        let endMod = stats.END || 0;
        let defBonus = getActorDEF(defender);
        // Parry: check if defender has a weapon with parry property
        let parryBonus = 0;
        (defender.cardStack || []).forEach(card => {
            if (card.parry) parryBonus += card.parry;
        });

        // Add status effect modifiers
        let statusMods = getStatusModifiers(defender);
        let statusDefMod = statusMods.def + statusMods.roll;

        let total = raw + endMod + defBonus + parryBonus + statusDefMod;

        let breakdown = `${raw} + ${endMod} END + ${defBonus} DEF`;
        if (parryBonus) breakdown += ` + ${parryBonus} Parry`;
        if (statusDefMod) breakdown += ` + ${statusDefMod} Status`;
        breakdown += ` = ${total}`;

        return {
            raw,
            endMod,
            defBonus,
            parryBonus,
            statusMod: statusDefMod,
            total,
            formula: "1d20 + END + DEF" + (parryBonus ? " + Parry" : "") + (statusDefMod ? " + Status" : ""),
            breakdown
        };
    }

    // Outcome table (based on roll difference + natural 1/20)
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

    function getCombatOutcome(attackRoll, defenseRoll) {
        let diff = attackRoll.total - defenseRoll.total;
        let attackNat = attackRoll.raw;
        let defenseNat = defenseRoll.raw;

        // Natural 20 on attack = Critical Hit (always)
        if (attackNat === 20) return { ...COMBAT_OUTCOMES.CRITICAL_HIT, diff };

        // Natural 1 on attack = Critical Miss (always)
        if (attackNat === 1) return { ...COMBAT_OUTCOMES.CRITICAL_MISS, diff };

        // Natural 20 on defense with successful block = Critical Parry (with reward)
        if (defenseNat === 20 && diff <= 0) return { ...COMBAT_OUTCOMES.CRITICAL_PARRY, diff };

        // Diff-based outcomes (no criticals)
        if (diff >= 10) return { ...COMBAT_OUTCOMES.DEVASTATING, diff };
        if (diff >= 5)  return { ...COMBAT_OUTCOMES.STRONG_HIT, diff };
        if (diff >= 1)  return { ...COMBAT_OUTCOMES.GLANCING_HIT, diff };
        if (diff === 0) return { ...COMBAT_OUTCOMES.CLASH, diff };
        if (diff >= -4) return { ...COMBAT_OUTCOMES.DEFLECT, diff };
        return { ...COMBAT_OUTCOMES.PARRY, diff };
    }

    // Calculate damage: base = weapon ATK + STR, modified by outcome
    function calculateDamage(attacker, outcome) {
        let stats = attacker.character.stats || {};
        let strMod = stats.STR || 0;
        let weaponAtk = getActorATK(attacker);
        let baseDamage = weaponAtk + strMod;

        // Apply outcome multiplier
        let finalDamage = Math.floor(baseDamage * outcome.damageMultiplier);
        // Minimum 1 damage on any successful hit
        if (outcome.damageMultiplier > 0 && finalDamage < 1) finalDamage = 1;

        return {
            baseDamage,
            multiplier: outcome.damageMultiplier,
            finalDamage,
            breakdown: `(${weaponAtk} ATK + ${strMod} STR) × ${outcome.damageMultiplier} = ${finalDamage}`
        };
    }

    // Apply damage to a target actor
    function applyDamage(actor, damage) {
        let def = getActorDEF(actor);
        let reduced = Math.max(1, damage - def);  // Minimum 1 damage
        actor.hp = Math.max(0, actor.hp - reduced);

        // Remove "until hit" status effects (like shielded)
        onActorHit(actor);

        return {
            rawDamage: damage,
            armorReduction: def,
            finalDamage: reduced,
            newHp: actor.hp
        };
    }

    // Store current combat resolution for UI display
    let currentCombatResult = null;

    // Resolve combat between attacker and defender
    function resolveCombat(attackerActor, defenderActor, stack) {
        // Check for dual wield (two one-handed weapons = two attack rolls)
        let dualWield = isDualWielding(attackerActor);
        let weapons = dualWield ? getWeaponATKs(attackerActor) : null;

        let attackRoll = rollAttack(attackerActor, stack);
        let defenseRoll = rollDefense(defenderActor);
        let outcome = getCombatOutcome(attackRoll, defenseRoll);
        let damage = calculateDamage(attackerActor, outcome);
        let damageResult = null;
        let selfDamageResult = null;

        // Dual wield: second attack roll
        let secondAttack = null;
        if (dualWield && weapons && weapons.length >= 2) {
            // Second attack uses the second weapon's ATK
            let secondRoll = rollAttack(attackerActor, stack);
            let secondOutcome = getCombatOutcome(secondRoll, defenseRoll);
            let secondDamage = calculateDamage(attackerActor, secondOutcome);

            secondAttack = {
                weaponName: weapons[1].name,
                attackRoll: secondRoll,
                outcome: secondOutcome,
                damage: secondDamage,
                damageResult: null
            };

            // Apply second attack damage
            if (secondOutcome.damageMultiplier > 0) {
                secondAttack.damageResult = applyDamage(defenderActor, secondDamage.finalDamage);
            }

            // Tag first attack with weapon name
            if (weapons[0]) {
                attackRoll.weaponName = weapons[0].name;
            }

            console.log("[CardGame v2] DUAL WIELD: Second attack with", weapons[1].name, "- Roll:", secondRoll.total, "Outcome:", secondOutcome.label);
        }

        // Apply effects based on outcome (first/main attack)
        let criticalEffects = { itemDropped: null, attackerStunned: false, rewardCard: null };

        if (outcome.damageMultiplier > 0) {
            // Hit - apply damage to defender
            damageResult = applyDamage(defenderActor, damage.finalDamage);

            // Critical Hit: ALWAYS reward the attacker with an elevated item card
            if (outcome.isCriticalHit) {
                // Generate a special elevated item as reward
                let rewardCard = generateCriticalReward("attack", attackerActor);
                attackerActor.hand.push(rewardCard);
                criticalEffects.rewardCard = rewardCard;
                console.log("[CardGame v2] CRITICAL HIT! Reward card:", rewardCard.name);

                // ALSO 50% chance to make defender drop an item
                if (defenderActor.cardStack && defenderActor.cardStack.length > 0 && Math.random() < 0.5) {
                    let droppableItems = defenderActor.cardStack.filter(c =>
                        c.type === "item" || c.type === "apparel"
                    );
                    if (droppableItems.length > 0) {
                        let droppedItem = droppableItems[Math.floor(Math.random() * droppableItems.length)];
                        let idx = defenderActor.cardStack.indexOf(droppedItem);
                        if (idx >= 0) {
                            defenderActor.cardStack.splice(idx, 1);
                            gameState.pot.push(droppedItem);
                            criticalEffects.itemDropped = droppedItem;
                            console.log("[CardGame v2] CRITICAL HIT! Item also dropped:", droppedItem.name);
                        }
                    }
                }
            }
        } else if (outcome.damageMultiplier < 0) {
            // Critical counter - attacker takes damage
            selfDamageResult = applyDamage(attackerActor, damage.finalDamage * -1);

            // Critical Counter: stun the attacker (skip next action)
            if (outcome.isCriticalCounter) {
                applyStatusEffect(attackerActor, "stunned", "Critical Counter");
                criticalEffects.attackerStunned = true;
                console.log("[CardGame v2] CRITICAL COUNTER! Attacker stunned");
            }

            // Critical Parry: defender gets a reward card
            if (outcome.isCriticalParry) {
                let rewardCard = generateCriticalReward("defense", defenderActor);
                defenderActor.hand.push(rewardCard);
                criticalEffects.rewardCard = rewardCard;
                console.log("[CardGame v2] CRITICAL PARRY! Reward card:", rewardCard.name);
            }
        } else if (outcome.bothTakeDamage) {
            // Clash - both take fixed damage
            damageResult = applyDamage(defenderActor, outcome.bothTakeDamage);
            selfDamageResult = applyDamage(attackerActor, outcome.bothTakeDamage);
        }

        let result = {
            attackRoll,
            defenseRoll,
            outcome,
            damage,
            damageResult,
            selfDamageResult,
            attackerName: attackerActor.character.name || "Attacker",
            defenderName: defenderActor.character.name || "Defender",
            // Dual wield info
            dualWield: dualWield,
            secondAttack: secondAttack,
            // Critical effects
            criticalEffects: criticalEffects
        };

        console.log("[CardGame v2] Combat resolved:", result);
        currentCombatResult = result;
        return result;
    }

    // Check if game is over (either actor HP <= 0)
    function checkGameOver() {
        if (!gameState) return null;
        if (gameState.player.hp <= 0) return "opponent";
        if (gameState.opponent.hp <= 0) return "player";
        return null;
    }

    // ── Array Shuffle (Fisher-Yates) ──────────────────────────────────
    function shuffle(arr) {
        let a = arr.slice();
        for (let i = a.length - 1; i > 0; i--) {
            let j = Math.floor(Math.random() * (i + 1));
            [a[i], a[j]] = [a[j], a[i]];
        }
        return a;
    }

    // Deal initial modifier cards to character stack (weapon + armor + apparel)
    function dealInitialStack(apparelCards, itemCards) {
        let stack = [];

        // 1. Always add a weapon - find one from deck or create basic
        let weapons = itemCards.filter(i => i.subtype === "weapon");
        if (weapons.length > 0) {
            stack.push(shuffle([...weapons])[0]);
        } else {
            stack.push({
                type: "item", subtype: "weapon", name: "Basic Blade",
                slot: "Hand (1H)", rarity: "COMMON", atk: 2, range: "Melee",
                damageType: "Slashing", effect: "+2 ATK"
            });
        }

        // 2. Always add armor - find one from deck or create basic
        let armors = itemCards.filter(i => i.subtype === "armor");
        if (armors.length > 0) {
            stack.push(shuffle([...armors])[0]);
        } else {
            stack.push({
                type: "item", subtype: "armor", name: "Basic Armor",
                slot: "Body", rarity: "COMMON", def: 2,
                effect: "+2 DEF"
            });
        }

        // 3. Add 1 random apparel card if available, or basic garb
        if (apparelCards.length > 0) {
            let shuffledApparel = shuffle([...apparelCards]);
            stack.push(shuffledApparel[0]);
        } else {
            stack.push({ type: "apparel", name: "Basic Garb", slot: "Body", def: 1, effect: "+1 DEF" });
        }

        console.log("[CardGame v2] Initial card stack:", stack.map(c => c.name));
        return stack;
    }

    // ── Per-Round Threat System ──────────────────────────────────────
    const THREAT_CREATURES = [
        { name: "Wolf", atk: 2, def: 1, hp: 8, imageIcon: "pets" },
        { name: "Goblin Scout", atk: 3, def: 1, hp: 10, imageIcon: "face" },
        { name: "Giant Spider", atk: 2, def: 2, hp: 12, imageIcon: "bug_report" },
        { name: "Bandit", atk: 4, def: 2, hp: 15, imageIcon: "person_alert" },
        { name: "Dire Wolf", atk: 5, def: 2, hp: 18, imageIcon: "pets" },
        { name: "Orc Warrior", atk: 6, def: 3, hp: 20, imageIcon: "shield_person" }
    ];

    /**
     * Generate a reward card for critical success (nat 20)
     * Tries to pull a rare/epic card from deck, falls back to stat bonus
     * @param {string} type - "attack" or "defense"
     * @param {Object} actor - The actor receiving the reward
     * @returns {Object} Generated reward card
     */
    function generateCriticalReward(type, actor) {
        // Try to find a rare/epic card from the draw pile
        let rarityOrder = ["LEGENDARY", "EPIC", "RARE", "UNCOMMON"];
        let drawPile = actor.drawPile || [];

        // Look for items/apparel matching the type
        let typeMatch = type === "attack"
            ? c => c.type === "item" && (c.subtype === "weapon" || c.atk > 0)
            : c => (c.type === "item" || c.type === "apparel") && (c.subtype === "armor" || c.def > 0);

        for (let rarity of rarityOrder) {
            let candidates = drawPile.filter(c =>
                c.rarity === rarity && typeMatch(c)
            );
            if (candidates.length > 0) {
                let reward = { ...candidates[Math.floor(Math.random() * candidates.length)] };
                // Remove from draw pile
                let idx = drawPile.findIndex(c => c.name === reward.name);
                if (idx >= 0) drawPile.splice(idx, 1);

                reward.id = "crit-reward-" + Date.now();
                reward.isCriticalReward = true;
                console.log("[CardGame v2] Critical reward from deck:", reward.name);
                return reward;
            }
        }

        // Fallback: generate a simple stat bonus consumable
        let bonus = type === "attack"
            ? { type: "item", subtype: "consumable", name: "Critical Bonus", atk: 3, rarity: "RARE",
                effect: "+3 ATK this round" }
            : { type: "item", subtype: "consumable", name: "Critical Bonus", def: 3, rarity: "RARE",
                effect: "+3 DEF this round" };

        bonus.id = "crit-reward-" + Date.now();
        bonus.isCriticalReward = true;
        console.log("[CardGame v2] Critical reward (fallback):", bonus.name);
        return bonus;
    }

    /**
     * Create a threat encounter based on difficulty
     * @param {number} difficulty - Threat difficulty (affects stats and loot)
     * @returns {Object} Threat encounter object
     */
    function createThreatEncounter(difficulty) {
        // Pick a creature based on difficulty
        let creatureIdx = Math.min(Math.floor(difficulty / 2), THREAT_CREATURES.length - 1);
        let base = THREAT_CREATURES[creatureIdx];

        // Scale stats based on difficulty
        let scaleFactor = 1 + (difficulty - 4) * 0.1;
        let threat = {
            type: "encounter",
            subtype: "threat",
            name: base.name,
            difficulty: difficulty,
            atk: Math.round(base.atk * scaleFactor),
            def: Math.round(base.def * scaleFactor),
            hp: Math.round(base.hp * scaleFactor),
            maxHp: Math.round(base.hp * scaleFactor),
            imageIcon: base.imageIcon,
            isThreat: true
        };

        // Determine loot rarity based on difficulty
        if (difficulty <= 4) {
            threat.lootRarity = "COMMON";
            threat.lootCount = 1;
        } else if (difficulty <= 8) {
            threat.lootRarity = "UNCOMMON";
            threat.lootCount = 1;
        } else {
            threat.lootRarity = "RARE";
            threat.lootCount = 2;  // 1 rare + 1 common
        }

        return threat;
    }

    /**
     * Check for Nat 1 on initiative and create beginning threats
     * @returns {Array} Array of threat objects with target info
     */
    function checkNat1Threats() {
        if (!gameState || !gameState.initiative) return [];

        let threats = [];
        let difficulty = gameState.round + 2;  // Beginning threat difficulty = round + 2

        // Check player for Nat 1
        if (gameState.initiative.playerRoll && gameState.initiative.playerRoll.raw === 1) {
            let threat = createThreatEncounter(difficulty);
            threat.target = "player";  // Threat attacks the fumbler
            threats.push(threat);
            console.log("[CardGame v2] BEGINNING THREAT! Player rolled Nat 1 - spawning", threat.name);
        }

        // Check opponent for Nat 1
        if (gameState.initiative.opponentRoll && gameState.initiative.opponentRoll.raw === 1) {
            let threat = createThreatEncounter(difficulty);
            threat.target = "opponent";  // Threat attacks the fumbler
            threats.push(threat);
            console.log("[CardGame v2] BEGINNING THREAT! Opponent rolled Nat 1 - spawning", threat.name);
        }

        // Max 2 beginning threats (one per player Nat 1)
        return threats.slice(0, 2);
    }

    /**
     * Store beginning threats for THREAT_RESPONSE phase
     * (No longer inserts into action bar - combat handled in separate phase)
     */
    function insertBeginningThreats(threats) {
        if (!gameState || !threats || threats.length === 0) return;

        // Store threats for THREAT_RESPONSE phase
        gameState.beginningThreats = threats;

        console.log("[CardGame v2] Beginning threats queued for response phase:", threats.length, "threats");
    }

    // ── Scenario Cards (End Threats) ──────────────────────────────────
    const SCENARIO_CARDS = [
        { name: "Peaceful Respite", effect: "no_threat", description: "The area is calm. No threats emerge.", weight: 40 },
        { name: "Distant Howling", effect: "no_threat", description: "You hear something in the distance, but nothing approaches.", weight: 25 },
        { name: "Ambush!", effect: "threat", description: "Enemies emerge from hiding!", threatBonus: 0, weight: 15 },
        { name: "Elite Patrol", effect: "threat", description: "A stronger foe appears!", threatBonus: 2, weight: 10 },
        { name: "Wandering Monster", effect: "threat", description: "A creature stumbles upon you.", threatBonus: -1, weight: 8 },
        { name: "Boss Encounter", effect: "threat", description: "A fearsome opponent blocks your path!", threatBonus: 4, weight: 2 }
    ];

    /**
     * Draw a scenario card using weighted random selection
     * @returns {Object} Selected scenario card
     */
    function drawScenarioCard() {
        let totalWeight = SCENARIO_CARDS.reduce((sum, card) => sum + card.weight, 0);
        let roll = Math.random() * totalWeight;
        let cumulative = 0;

        for (let card of SCENARIO_CARDS) {
            cumulative += card.weight;
            if (roll <= cumulative) {
                return { ...card };
            }
        }
        return { ...SCENARIO_CARDS[0] };  // Fallback
    }

    /**
     * Check for end-of-round threat from scenario card
     * @returns {Object|null} End threat info or null if no threat
     */
    function checkEndThreat() {
        let scenario = drawScenarioCard();
        console.log("[CardGame v2] Scenario card drawn:", scenario.name);

        if (scenario.effect === "threat") {
            let difficulty = gameState.round + 3 + (scenario.threatBonus || 0);
            let threat = createThreatEncounter(difficulty);
            // End threats target the round loser (or random on tie)
            if (gameState.roundWinner === "tie") {
                threat.target = Math.random() < 0.5 ? "player" : "opponent";
            } else {
                threat.target = gameState.roundWinner === "player" ? "opponent" : "player";
            }
            return {
                scenario: scenario,
                threat: threat
            };
        }

        return { scenario: scenario, threat: null };
    }

    // ── Initiative Phase ──────────────────────────────────────────────
    function runInitiativePhase() {
        if (!gameState) return;

        let playerStats = gameState.player.character.stats || {};
        let opponentStats = gameState.opponent.character.stats || {};

        gameState.initiative.playerRoll = rollInitiative(playerStats);
        gameState.initiative.opponentRoll = rollInitiative(opponentStats);

        // Determine winner (re-roll ties handled here simply by random)
        let pTotal = gameState.initiative.playerRoll.total;
        let oTotal = gameState.initiative.opponentRoll.total;

        if (pTotal > oTotal) {
            gameState.initiative.winner = "player";
        } else if (oTotal > pTotal) {
            gameState.initiative.winner = "opponent";
        } else {
            // Tie-breaker: random
            gameState.initiative.winner = Math.random() < 0.5 ? "player" : "opponent";
        }

        // Calculate total positions - each player gets exactly their AP number of slots
        let playerAp = gameState.player.ap;
        let opponentAp = gameState.opponent.ap;
        let totalPositions = playerAp + opponentAp;

        // Assign positions: interleave winner and loser, but each gets exactly their AP count
        // Winner acts at positions 1, 3, 5... up to their AP count
        // Loser acts at positions 2, 4, 6... up to their AP count
        let winnerAp = gameState.initiative.winner === "player" ? playerAp : opponentAp;
        let loserAp = gameState.initiative.winner === "player" ? opponentAp : playerAp;

        let winnerPositions = [];
        let loserPositions = [];
        let pos = 1;
        let wCount = 0, lCount = 0;

        // Interleave: winner first, then loser, alternating until both filled
        while (wCount < winnerAp || lCount < loserAp) {
            if (wCount < winnerAp) {
                winnerPositions.push(pos++);
                wCount++;
            }
            if (lCount < loserAp) {
                loserPositions.push(pos++);
                lCount++;
            }
        }

        if (gameState.initiative.winner === "player") {
            gameState.initiative.playerPositions = winnerPositions;
            gameState.initiative.opponentPositions = loserPositions;
        } else {
            gameState.initiative.playerPositions = loserPositions;
            gameState.initiative.opponentPositions = winnerPositions;
        }

        // Build action bar positions
        gameState.actionBar.totalPositions = totalPositions;
        gameState.actionBar.positions = [];
        for (let i = 1; i <= totalPositions; i++) {
            let owner = gameState.initiative.playerPositions.includes(i) ? "player" : "opponent";
            gameState.actionBar.positions.push({
                index: i,
                owner: owner,
                stack: null,  // { coreCard, modifiers: [] }
                resolved: false
            });
        }

        // Check for Nat 1 (critical initiative failure) - triggers beginning threats
        let beginningThreats = checkNat1Threats();
        if (beginningThreats.length > 0) {
            insertBeginningThreats(beginningThreats);
        }

        console.log("[CardGame v2] Initiative:", gameState.initiative);
        m.redraw();
    }

    // ── Phase Transitions ─────────────────────────────────────────────
    function advancePhase() {
        if (!gameState) return;

        // Handle phase-specific transitions
        if (gameState.phase === GAME_PHASES.INITIATIVE) {
            // Check if there are beginning threats that need response
            if (gameState.beginningThreats && gameState.beginningThreats.length > 0) {
                enterThreatResponsePhase("beginning");
            } else {
                enterDrawPlacementPhase();
            }
        } else if (gameState.phase === GAME_PHASES.THREAT_RESPONSE) {
            // Threat response complete - resolve the threat combat, then continue to placement
            resolveThreatCombat();
        } else if (gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
            gameState.phase = GAME_PHASES.RESOLUTION;
            gameState.actionBar.resolveIndex = 0;
            console.log("[CardGame v2] Phase advanced to:", gameState.phase);
            // Auto-start resolution
            setTimeout(() => {
                if (gameState && gameState.phase === GAME_PHASES.RESOLUTION) {
                    advanceResolution();
                }
            }, 500);
        } else if (gameState.phase === GAME_PHASES.RESOLUTION) {
            gameState.phase = GAME_PHASES.CLEANUP;
            console.log("[CardGame v2] Phase advanced to:", gameState.phase);
        } else if (gameState.phase === GAME_PHASES.CLEANUP) {
            // Check for end-of-round threat before starting next round
            if (gameState.endThreatResult && gameState.endThreatResult.threat && !gameState.endThreatResult.responded) {
                enterEndThreatPhase();
            } else {
                startNextRound();
            }
        } else if (gameState.phase === GAME_PHASES.END_THREAT) {
            // End threat response complete - resolve it, then start next round
            resolveEndThreatCombat();
        }
        m.redraw();
    }

    function enterDrawPlacementPhase() {
        gameState.phase = GAME_PHASES.DRAW_PLACEMENT;
        gameState.currentTurn = gameState.initiative.winner;
        console.log("[CardGame v2] Phase advanced to:", gameState.phase, "- Current turn:", gameState.currentTurn);

        // If AI goes first, trigger AI placement
        if (gameState.currentTurn === "opponent") {
            setTimeout(() => {
                if (gameState && gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                    console.log("[CardGame v2] Triggering AI placement (goes first)");
                    aiPlaceCards();
                    m.redraw();
                }
            }, 500);
        }
    }

    function enterThreatResponsePhase(type) {
        console.log("[CardGame v2] Entering threat response phase for:", type, "threats");
        gameState.phase = GAME_PHASES.THREAT_RESPONSE;

        // Determine who responds - the fumbler(s) who triggered the threat
        let responders = [];
        gameState.beginningThreats.forEach(threat => {
            if (!responders.includes(threat.target)) {
                responders.push(threat.target);
            }
        });

        // Set up threat response state
        gameState.threatResponse = {
            active: true,
            type: type,
            threats: gameState.beginningThreats.slice(),
            responder: responders[0] || "player",  // First responder
            responderQueue: responders,
            bonusAP: 2,  // Limited AP for threat defense
            defenseStack: [],
            resolved: false
        };

        // Give responder bonus AP for defense
        let responder = gameState.threatResponse.responder === "player" ? gameState.player : gameState.opponent;
        responder.threatResponseAP = gameState.threatResponse.bonusAP;

        console.log("[CardGame v2] Threat response:", gameState.threatResponse.responder, "gets", gameState.threatResponse.bonusAP, "AP to defend");
    }

    function enterEndThreatPhase() {
        console.log("[CardGame v2] Entering end threat phase");
        gameState.phase = GAME_PHASES.END_THREAT;

        // Responder is the threat's target (the one being attacked)
        let threat = gameState.endThreatResult.threat;
        let responder = threat.target;

        gameState.threatResponse = {
            active: true,
            type: "end",
            threats: [threat],
            responder: responder,
            bonusAP: 2,  // Bonus AP for end threat response
            defenseStack: [],
            resolved: false
        };

        // Give responder (defender) bonus AP
        let actor = responder === "player" ? gameState.player : gameState.opponent;
        actor.threatResponseAP = gameState.threatResponse.bonusAP;

        console.log("[CardGame v2] End threat response:", responder, "must defend against", threat.name);
    }

    function resolveThreatCombat() {
        console.log("[CardGame v2] resolveThreatCombat called, phase:", gameState?.phase,
            "threatResponse:", !!gameState?.threatResponse,
            "threats:", gameState?.threatResponse?.threats?.length || 0);

        if (!gameState || !gameState.threatResponse) {
            console.warn("[CardGame v2] resolveThreatCombat: No threat response state");
            return;
        }

        let threats = gameState.threatResponse.threats;
        let defenseStack = gameState.threatResponse.defenseStack || [];

        if (!threats || threats.length === 0) {
            console.warn("[CardGame v2] resolveThreatCombat: No threats to resolve");
            // Still need to advance phase
            gameState.threatResponse.resolved = true;
            gameState.threatResponse.active = false;
            gameState.player.threatResponseAP = 0;
            gameState.opponent.threatResponseAP = 0;
            enterDrawPlacementPhase();
            return;
        }

        // Resolve each threat as combat
        threats.forEach(threat => {
            let targetActor = threat.target === "player" ? gameState.player : gameState.opponent;

            // Build threat attacker object
            let threatAttacker = {
                name: threat.name,
                character: {
                    name: threat.name,
                    stats: { STR: threat.atk, END: threat.def }
                },
                hp: threat.hp,
                maxHp: threat.maxHp,
                cardStack: [{ type: "item", subtype: "weapon", atk: threat.atk }]
            };

            // Check if defender has a defense stack
            let defenderStack = defenseStack.filter(card => card.target === threat.target);
            targetActor.cardStack = defenderStack;

            // Roll combat
            let attackRoll = rollAttack(threatAttacker, []);
            let defenseRoll = rollDefense(targetActor);
            let outcome = getCombatOutcome(attackRoll, defenseRoll);

            console.log("[CardGame v2] Threat combat:", threat.name, "vs", threat.target, "- Outcome:", outcome.label);

            if (outcome.damageMultiplier > 0) {
                // Threat hit
                let damage = calculateDamage(threatAttacker, outcome);
                let damageResult = applyDamage(targetActor, damage.finalDamage);
                console.log("[CardGame v2] Threat dealt", damage.finalDamage, "damage to", threat.target);

                // Store result for UI
                if (!gameState.threatResults) gameState.threatResults = [];
                gameState.threatResults.push({
                    threatName: threat.name,
                    target: threat.target,
                    outcome: "hit",
                    damage: damage.finalDamage,
                    attackRoll,
                    defenseRoll,
                    combatOutcome: outcome
                });
            } else {
                // Defender blocked/parried
                console.log("[CardGame v2]", threat.target, "defended against", threat.name);

                // Grant loot on successful defense
                let lootCard = {
                    type: "item",
                    subtype: "consumable",
                    name: "Threat Loot (" + threat.lootRarity + ")",
                    rarity: threat.lootRarity,
                    effect: threat.lootRarity === "RARE" ? "Restore 5 HP" : "Restore 3 HP",
                    flavor: "Spoils from defeating " + threat.name
                };
                targetActor.hand.push(lootCard);

                if (!gameState.threatResults) gameState.threatResults = [];
                gameState.threatResults.push({
                    threatName: threat.name,
                    target: threat.target,
                    outcome: "defeated",
                    loot: lootCard,
                    attackRoll,
                    defenseRoll,
                    combatOutcome: outcome
                });
            }
        });

        // Clear threat response and continue to placement
        gameState.threatResponse.resolved = true;
        gameState.threatResponse.active = false;

        // Clear threat response AP
        gameState.player.threatResponseAP = 0;
        gameState.opponent.threatResponseAP = 0;

        // Check for game over after threat combat
        let winner = checkGameOver();
        if (winner) {
            console.log("[CardGame v2] Game over from threat combat - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        // Continue to draw/placement phase
        enterDrawPlacementPhase();
    }

    function resolveEndThreatCombat() {
        if (!gameState || !gameState.endThreatResult || !gameState.endThreatResult.threat) {
            startNextRound();
            return;
        }

        let threat = gameState.endThreatResult.threat;
        let responder = gameState.threatResponse?.responder || gameState.roundWinner;
        let responderActor = responder === "player" ? gameState.player : gameState.opponent;
        let defenseStack = gameState.threatResponse?.defenseStack || [];

        // Build threat attacker object
        let threatAttacker = {
            name: threat.name,
            character: {
                name: threat.name,
                stats: { STR: threat.atk, END: threat.def }
            },
            hp: threat.hp,
            maxHp: threat.maxHp,
            cardStack: [{ type: "item", subtype: "weapon", atk: threat.atk }]
        };

        // Assign defense stack
        responderActor.cardStack = defenseStack;

        // Roll combat
        let attackRoll = rollAttack(threatAttacker, []);
        let defenseRoll = rollDefense(responderActor);
        let outcome = getCombatOutcome(attackRoll, defenseRoll);

        console.log("[CardGame v2] End threat combat:", threat.name, "vs", responder, "- Outcome:", outcome.label);

        if (outcome.damageMultiplier > 0) {
            // Threat hit
            let damage = calculateDamage(threatAttacker, outcome);
            applyDamage(responderActor, damage.finalDamage);
            gameState.endThreatResult.damageDealt = damage.finalDamage;
            gameState.endThreatResult.combatResult = { attackRoll, defenseRoll, outcome, damage };
            console.log("[CardGame v2] End threat dealt", damage.finalDamage, "damage to", responder);
        } else {
            // Defender won
            gameState.endThreatResult.damageDealt = 0;
            gameState.endThreatResult.combatResult = { attackRoll, defenseRoll, outcome, defended: true };

            // Grant loot
            let lootCard = {
                type: "item",
                subtype: "consumable",
                name: "End Threat Loot (" + threat.lootRarity + ")",
                rarity: threat.lootRarity,
                effect: "Restore 4 HP",
                flavor: "Spoils from defeating " + threat.name
            };
            responderActor.hand.push(lootCard);
            gameState.endThreatResult.loot = lootCard;
            console.log("[CardGame v2]", responder, "defeated end threat, earned loot");
        }

        gameState.endThreatResult.responded = true;

        // Clear threat response state
        if (gameState.threatResponse) {
            gameState.threatResponse.active = false;
            gameState.threatResponse.resolved = true;
        }

        // Clear threat response AP
        gameState.player.threatResponseAP = 0;
        gameState.opponent.threatResponseAP = 0;

        // Check for game over after end threat combat
        let winner = checkGameOver();
        if (winner) {
            console.log("[CardGame v2] Game over from end threat - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        // Return to cleanup phase to show the result before next round
        gameState.phase = GAME_PHASES.CLEANUP;
        console.log("[CardGame v2] End threat resolved, returning to cleanup to show result");
        m.redraw();
    }

    // Place a card in threat response defense stack
    function placeThreatDefenseCard(card) {
        if (!gameState || !gameState.threatResponse || !gameState.threatResponse.active) return false;

        let responder = gameState.threatResponse.responder;
        let actor = responder === "player" ? gameState.player : gameState.opponent;

        // Check AP
        let apCost = 1;
        if ((actor.threatResponseAP || 0) < apCost) {
            console.log("[CardGame v2] Not enough threat response AP");
            return false;
        }

        // Deduct AP and add to defense stack
        actor.threatResponseAP -= apCost;
        gameState.threatResponse.defenseStack.push({
            ...card,
            target: gameState.threatResponse.threats[0]?.target || responder
        });

        // Remove from hand
        let handIndex = actor.hand.indexOf(card);
        if (handIndex >= 0) {
            actor.hand.splice(handIndex, 1);
        }

        console.log("[CardGame v2] Placed threat defense card:", card.name);
        m.redraw();
        return true;
    }

    // Skip threat response and let combat resolve
    function skipThreatResponse() {
        if (!gameState || !gameState.threatResponse) return;

        console.log("[CardGame v2] Skipping threat response");

        if (gameState.phase === GAME_PHASES.THREAT_RESPONSE) {
            resolveThreatCombat();
        } else if (gameState.phase === GAME_PHASES.END_THREAT) {
            resolveEndThreatCombat();
        }
    }

    function startNextRound() {
        if (!gameState) return;

        // Check for game over BEFORE starting next round
        let winner = checkGameOver();
        if (winner) {
            console.log("[CardGame v2] Game over detected at round start - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        // Tick down status effect durations at end of round
        tickStatusEffects(gameState.player);
        tickStatusEffects(gameState.opponent);

        // Process turn-start effects (like poison damage)
        let playerEffects = processStatusEffectsTurnStart(gameState.player);
        let opponentEffects = processStatusEffectsTurnStart(gameState.opponent);
        if (playerEffects.length > 0 || opponentEffects.length > 0) {
            console.log("[CardGame v2] Status effects triggered:", { player: playerEffects, opponent: opponentEffects });
        }

        // Check again after status effects (poison could kill)
        winner = checkGameOver();
        if (winner) {
            console.log("[CardGame v2] Game over from status effects - winner:", winner);
            gameState.winner = winner;
            gameState.phase = "GAME_OVER";
            m.redraw();
            return;
        }

        gameState.round++;
        gameState.phase = GAME_PHASES.INITIATIVE;

        // Reset AP
        gameState.player.apUsed = 0;
        gameState.opponent.apUsed = 0;

        // Reset round points
        gameState.player.roundPoints = 0;
        gameState.opponent.roundPoints = 0;

        // Reset hoarding prevention tracking
        gameState.player.typesPlayedThisRound = {};
        gameState.opponent.typesPlayedThisRound = {};
        gameState.exhaustedThisRound = [];
        gameState.playerLethargy = [];
        gameState.opponentLethargy = [];

        // Clear action bar
        gameState.actionBar.positions = [];
        gameState.actionBar.resolveIndex = -1;

        // Clear beginning threats from previous round
        gameState.beginningThreats = [];

        // Clear pot (winner claimed it)
        gameState.pot = [];

        // Auto-draw at start of rounds 2+
        // Draw cards to refill hand (up to 5 cards, draw difference)
        let targetHandSize = 5;
        let playerDraw = Math.max(0, targetHandSize - gameState.player.hand.length);
        let opponentDraw = Math.max(0, targetHandSize - gameState.opponent.hand.length);

        if (playerDraw > 0) {
            console.log("[CardGame v2] Round", gameState.round, "- Player draws", playerDraw, "cards");
            drawCardsForActor(gameState.player, playerDraw);
        }
        if (opponentDraw > 0) {
            console.log("[CardGame v2] Round", gameState.round, "- Opponent draws", opponentDraw, "cards");
            drawCardsForActor(gameState.opponent, opponentDraw);
        }

        // Ensure each player has at least one Attack card
        ensureOffensiveCard(gameState.player, "Player");
        ensureOffensiveCard(gameState.opponent, "Opponent");

        // Mandatory ante: each player puts 1 random card into pot
        anteCard(gameState.player, "Player");
        anteCard(gameState.opponent, "Opponent");

        // Reset initiative animation state so it replays
        resetInitAnimState();

        // Clear previous initiative results
        gameState.initiative.playerRoll = null;
        gameState.initiative.opponentRoll = null;
        gameState.initiative.winner = null;

        console.log("[CardGame v2] Starting round", gameState.round, "- Pot has", gameState.pot.length, "cards");
        m.redraw();

        // Trigger round start narration (non-blocking)
        if (gameState.round > 1) {
            narrateRoundStart();
        }

        // Start initiative animation for round 2+ (oninit won't fire again)
        setTimeout(() => {
            if (gameState && gameState.phase === GAME_PHASES.INITIATIVE) {
                startInitiativeAnimation();
            }
        }, 100);
    }

    // ── Pot System ────────────────────────────────────────────────────
    // Ante: Each player puts a random card from hand into the pot
    function anteCard(actor, actorName) {
        if (!actor.hand || actor.hand.length === 0) {
            console.log("[CardGame v2]", actorName, "has no cards to ante");
            return null;
        }
        // Pick a random card from hand
        let idx = Math.floor(Math.random() * actor.hand.length);
        let card = actor.hand.splice(idx, 1)[0];
        gameState.pot.push(card);
        console.log("[CardGame v2]", actorName, "anted", card.name, "to pot");
        return card;
    }

    // Round winner claims all cards in the pot
    function claimPot(winner) {
        if (!gameState.pot || gameState.pot.length === 0) return;
        let winnerActor = winner === "player" ? gameState.player : gameState.opponent;
        gameState.pot.forEach(card => {
            winnerActor.discardPile.push(card);
        });
        console.log("[CardGame v2]", winner, "claimed pot with", gameState.pot.length, "cards");
        gameState.pot = [];
    }

    // Add a card to the pot (for mid-round drops)
    function addToPot(card, reason) {
        if (!card) return;
        gameState.pot.push(card);
        console.log("[CardGame v2] Added", card.name, "to pot (" + reason + ")");
    }

    // ── Hoarding Prevention ──────────────────────────────────────────────

    /**
     * Lethargy Check (at cleanup)
     * If actor holds 2+ copies of the same action type and played 0 this round,
     * keep 1 copy and return the rest to the encounter deck.
     * @returns {Array} Array of {actionType, stripped} objects
     */
    function checkLethargy(actor, actorName) {
        if (!actor.hand || actor.hand.length === 0) return [];

        let typesPlayed = actor.typesPlayedThisRound || {};
        let results = [];

        // Count action cards by name (only action type cards)
        let actionCounts = {};
        actor.hand.forEach(card => {
            if (card.type === "action") {
                let key = card.name;
                actionCounts[key] = (actionCounts[key] || 0) + 1;
            }
        });

        // Check each action type with 2+ copies
        Object.keys(actionCounts).forEach(actionType => {
            let count = actionCounts[actionType];
            let played = typesPlayed[actionType] || 0;

            // Lethargy: 2+ copies AND 0 played this round
            if (count >= 2 && played === 0) {
                let stripped = count - 1;  // Keep 1, strip the rest
                let removed = 0;

                // Remove extras from hand and add to encounter deck
                for (let i = actor.hand.length - 1; i >= 0 && removed < stripped; i--) {
                    let card = actor.hand[i];
                    if (card.type === "action" && card.name === actionType) {
                        actor.hand.splice(i, 1);
                        // Return to encounter deck
                        if (gameState.encounterDeck) {
                            gameState.encounterDeck.push(card);
                        }
                        removed++;
                    }
                }

                if (removed > 0) {
                    results.push({ actionType, stripped: removed });
                    console.log("[CardGame v2] LETHARGY:", actorName, "- stripped", removed, actionType, "card(s)");
                }
            }
        });

        // Shuffle encounter deck after adding cards
        if (results.length > 0 && gameState.encounterDeck) {
            gameState.encounterDeck = shuffle(gameState.encounterDeck);
        }

        return results;
    }

    /**
     * Exhausted Check (during resolution)
     * If actor played 2+ of a type this round, last one failed, and holds 2+ extras in hand,
     * keep 1 extra and return the rest to encounter deck.
     * @returns {Object|null} { actionType, stripped } or null if no trigger
     */
    function checkExhausted(actor, actorName, failedActionType) {
        if (!actor.hand || !failedActionType) return null;

        let typesPlayed = actor.typesPlayedThisRound || {};
        let playedCount = typesPlayed[failedActionType] || 0;

        // Must have played 2+ of this type
        if (playedCount < 2) return null;

        // Count how many of this type remain in hand
        let handCount = actor.hand.filter(c => c.type === "action" && c.name === failedActionType).length;

        // Exhausted: played 2+, failed, and hold 2+ extras in hand
        if (handCount >= 2) {
            let stripped = handCount - 1;  // Keep 1, strip the rest
            let removed = 0;

            for (let i = actor.hand.length - 1; i >= 0 && removed < stripped; i--) {
                let card = actor.hand[i];
                if (card.type === "action" && card.name === failedActionType) {
                    actor.hand.splice(i, 1);
                    if (gameState.encounterDeck) {
                        gameState.encounterDeck.push(card);
                    }
                    removed++;
                }
            }

            if (removed > 0) {
                // Shuffle encounter deck
                if (gameState.encounterDeck) {
                    gameState.encounterDeck = shuffle(gameState.encounterDeck);
                }
                console.log("[CardGame v2] EXHAUSTED:", actorName, "- stripped", removed, failedActionType, "card(s)");
                return { actionType: failedActionType, stripped: removed };
            }
        }

        return null;
    }

    // ── Draw Cards Helper ─────────────────────────────────────────────
    function drawCardsForActor(actor, count) {
        for (let i = 0; i < count; i++) {
            // If draw pile empty, shuffle discard into draw
            if (actor.drawPile.length === 0 && actor.discardPile.length > 0) {
                actor.drawPile = shuffle([...actor.discardPile]);
                actor.discardPile = [];
                console.log("[CardGame v2] Reshuffled discard pile into draw pile");
            }

            if (actor.drawPile.length > 0) {
                let card = actor.drawPile.shift();
                actor.hand.push(card);
                console.log("[CardGame v2] Drew card:", card.name);
            }
        }
    }

    /**
     * Ensure actor has at least one offensive card (Attack) in hand.
     * If not, find one from draw/discard pile or create a basic Attack.
     */
    function ensureOffensiveCard(actor, actorName) {
        // Check if hand already has an Attack card
        let hasAttack = actor.hand.some(c => c.type === "action" && c.name === "Attack");
        if (hasAttack) return;

        // Try to find Attack in draw pile
        let attackIdx = actor.drawPile.findIndex(c => c.type === "action" && c.name === "Attack");
        if (attackIdx >= 0) {
            let attackCard = actor.drawPile.splice(attackIdx, 1)[0];
            actor.hand.push(attackCard);
            console.log("[CardGame v2]", actorName, "guaranteed Attack card from draw pile");
            return;
        }

        // Try discard pile
        attackIdx = actor.discardPile.findIndex(c => c.type === "action" && c.name === "Attack");
        if (attackIdx >= 0) {
            let attackCard = actor.discardPile.splice(attackIdx, 1)[0];
            actor.hand.push(attackCard);
            console.log("[CardGame v2]", actorName, "guaranteed Attack card from discard pile");
            return;
        }

        // Last resort: create a basic Attack card
        let basicAttack = {
            type: "action",
            name: "Attack",
            effect: "Roll ATK vs DEF. Deal STR damage on hit.",
            rarity: "COMMON"
        };
        actor.hand.push(basicAttack);
        console.log("[CardGame v2]", actorName, "granted basic Attack card (none in deck)");
    }

    // ── Placement Actions ─────────────────────────────────────────────
    // Determine if a card type can be a core card (action that drives the stack)
    function isCoreCardType(cardType) {
        return cardType === "action" || cardType === "talk" || cardType === "magic";
    }

    // Determine if a card type can be a modifier (stacks on top of core)
    // Note: magic is NOT a modifier - it's a core action like attack/talk
    function isModifierCardType(cardType) {
        return cardType === "skill" || cardType === "item";
    }

    function placeCard(positionIndex, card, forceModifier = false) {
        if (!gameState) return false;
        if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return false;

        let pos = gameState.actionBar.positions.find(p => p.index === positionIndex);
        if (!pos) return false;

        // Check ownership
        let currentPlayer = gameState.currentTurn;
        if (pos.owner !== currentPlayer) {
            console.warn("[CardGame v2] Cannot place on opponent's position");
            return false;
        }

        let actor = currentPlayer === "player" ? gameState.player : gameState.opponent;

        // Determine if this card should be a modifier or core
        let isModifier = forceModifier;
        let hasExistingCore = pos.stack && pos.stack.coreCard;

        if (!forceModifier && hasExistingCore) {
            // Position already has a core card
            // Core action types (action, talk, magic) cannot be stacked - reject them
            if (card.type === "action" || card.type === "talk" || card.type === "magic") {
                console.warn("[CardGame v2] Cannot stack multiple action cards - position already has:", pos.stack.coreCard.name);
                page.toast("warn", "Position already has an action card");
                return false;
            } else if (isModifierCardType(card.type)) {
                // Modifiers (skill, item) can be added to existing stack
                isModifier = true;
            } else {
                console.warn("[CardGame v2] Position already has a core card, and", card.type, "cannot be a modifier");
                return false;
            }
        }

        // Double-check stack state after potential removal
        if (!isModifier && pos.stack && pos.stack.coreCard) {
            console.error("[CardGame v2] Stack still has core card after removal - blocking duplicate");
            return false;
        }

        if (isModifier) {
            // Add modifier to existing stack (no AP cost for modifiers)
            if (!pos.stack || !pos.stack.coreCard) {
                console.warn("[CardGame v2] No core card to modify - place an action first");
                return false;
            }
            pos.stack.modifiers.push(card);
            console.log("[CardGame v2] Added modifier", card.name, "to stack at position", positionIndex);
        } else {
            // Place core card (action/talk/magic)
            if (!isCoreCardType(card.type)) {
                // Skill dropped on empty slot - can't be core
                console.warn("[CardGame v2]", card.type, "cards need an action card first");
                return false;
            }

            // Check AP for core cards
            if (actor.apUsed >= actor.ap) {
                console.warn("[CardGame v2] No AP remaining");
                return false;
            }

            // Check energy cost
            if (card.energyCost && card.energyCost > actor.energy) {
                console.warn("[CardGame v2] Not enough energy for", card.name);
                return false;
            }

            pos.stack = { coreCard: card, modifiers: [] };
            actor.apUsed++;

            // Deduct energy if needed
            if (card.energyCost) {
                actor.energy -= card.energyCost;
            }

            // Track action type for hoarding prevention (Lethargy/Exhausted)
            let actionKey = card.name;  // Use card name as the action type key
            if (!actor.typesPlayedThisRound) actor.typesPlayedThisRound = {};
            actor.typesPlayedThisRound[actionKey] = (actor.typesPlayedThisRound[actionKey] || 0) + 1;

            console.log("[CardGame v2] Placed core card", card.name, "at position", positionIndex);
        }

        // Remove card from hand
        if (currentPlayer === "player") {
            let idx = gameState.player.hand.findIndex(c => c === card || (c.name === card.name && c.type === card.type));
            if (idx >= 0) gameState.player.hand.splice(idx, 1);
        }

        m.redraw();
        // Player manually ends turn - no auto-commit
        return true;
    }

    function removeCardFromPosition(positionIndex, skipRedraw = false) {
        if (!gameState) return false;
        if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return false;

        let pos = gameState.actionBar.positions.find(p => p.index === positionIndex);
        if (!pos || !pos.stack) return false;

        let currentPlayer = gameState.currentTurn;
        if (pos.owner !== currentPlayer) return false;

        let actor = currentPlayer === "player" ? gameState.player : gameState.opponent;

        // Return cards to hand and refund costs
        if (pos.stack.coreCard) {
            actor.hand.push(pos.stack.coreCard);
            actor.apUsed = Math.max(0, actor.apUsed - 1);

            // Refund energy cost
            if (pos.stack.coreCard.energyCost) {
                actor.energy = Math.min(actor.maxEnergy, actor.energy + pos.stack.coreCard.energyCost);
            }

            // Remove from types played tracking
            let actionKey = pos.stack.coreCard.name;
            if (actor.typesPlayedThisRound && actor.typesPlayedThisRound[actionKey]) {
                actor.typesPlayedThisRound[actionKey]--;
                if (actor.typesPlayedThisRound[actionKey] <= 0) {
                    delete actor.typesPlayedThisRound[actionKey];
                }
            }

            console.log("[CardGame v2] Removed", pos.stack.coreCard.name, "from position", positionIndex);
        }

        // Return modifiers to hand
        pos.stack.modifiers.forEach(mod => actor.hand.push(mod));

        pos.stack = null;
        if (!skipRedraw) m.redraw();
        return true;
    }

    function endTurn() {
        if (!gameState) return;
        if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;

        // Switch turn
        gameState.currentTurn = gameState.currentTurn === "player" ? "opponent" : "player";

        // Check if the current player has 0 AP - auto-skip their turn
        checkAutoEndTurn();
    }

    // Auto-end turn if current player has no AP remaining
    function checkAutoEndTurn() {
        if (!gameState) return;
        if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;

        let currentTurn = gameState.currentTurn;
        let current = currentTurn === "player" ? gameState.player : gameState.opponent;
        let positions = currentTurn === "player"
            ? gameState.initiative.playerPositions
            : gameState.initiative.opponentPositions;
        let apRemaining = current.ap - current.apUsed;

        console.log("[CardGame v2] checkAutoEndTurn:", currentTurn, "AP remaining:", apRemaining);

        // Check if current player can actually place any cards
        if (apRemaining > 0) {
            let coreCards = current.hand.filter(c => isCoreCardType(c.type));
            let playableCores = coreCards.filter(c => !c.energyCost || c.energyCost <= current.energy);
            let emptyPositions = positions.filter(posIdx => {
                let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
                return pos && !pos.stack;
            });

            // Auto-forfeit AP if can't place anything (no playable cores or no empty positions)
            if (playableCores.length === 0 || emptyPositions.length === 0) {
                console.log("[CardGame v2]", currentTurn, "can't place - playable cores:", playableCores.length,
                    "(total:", coreCards.length, ") empty:", emptyPositions.length, "energy:", current.energy);
                current.apUsed = current.ap;
                apRemaining = 0;
            }
        }

        if (apRemaining <= 0) {
            // No AP left, check if placement is complete or switch turns
            checkPlacementComplete();
            if (gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;  // Phase advanced

            // If still in placement, it means other player still has AP - switch to them
            let other = currentTurn === "player" ? gameState.opponent : gameState.player;
            if (other.apUsed < other.ap) {
                gameState.currentTurn = currentTurn === "player" ? "opponent" : "player";
                // Recursively check the new current player
                setTimeout(() => { checkAutoEndTurn(); m.redraw(); }, 300);
            }
        } else if (currentTurn === "opponent") {
            // Opponent has AP, let AI place cards
            setTimeout(() => {
                aiPlaceCards();
                m.redraw();
            }, 500);
        }
        m.redraw();
    }

    // ── CardGameLLM Base Class ────────────────────────────────────────
    // Shared LLM infrastructure for Director, Narrator, and Chat
    class CardGameLLM {
        constructor() {
            this.chatRequest = null;
            this.chatConfig = null;
            this.promptConfig = null;
            this.initialized = false;
            this.lastError = null;
        }

        // Find the ~/Chat directory
        static async findChatDir() {
            const chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
            if (!chatDir) {
                console.warn("[CardGameLLM] ~/Chat not found");
            }
            return chatDir;
        }

        // Get the "Open Chat" template config
        static async getOpenChatTemplate(chatDir) {
            const chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 0);
            const templateCfg = chatConfigs.find(c => c.name === "Open Chat");
            if (!templateCfg) {
                console.warn("[CardGameLLM] 'Open Chat' config not found");
                return null;
            }
            return am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);
        }

        // Ensure a prompt config exists (create or find) using am7chat.makePrompt
        static async ensurePromptConfig(chatDir, name, systemPrompt) {
            try {
                // Delegate to am7chat.makePrompt which correctly handles
                // the 'system' array field and create-or-find logic
                let cfg = await am7chat.makePrompt(name, [systemPrompt]);
                return cfg;
            } catch (err) {
                console.error("[CardGameLLM] ensurePromptConfig failed:", err);
                return null;
            }
        }

        // Ensure a chat config exists (find existing or create from template)
        static async ensureChatConfig(chatDir, template, name, temperature) {
            try {
                // Search for existing config using page API (same pattern as am7chat.makeChat)
                let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatConfig"));
                q.field("groupId", chatDir.id);
                q.field("name", name);
                q.cache(false);
                let qr = await page.search(q);

                if (qr?.results?.length > 0) {
                    return qr.results[0];
                }

                // Clone from template and create via page API
                let icfg = am7model.newInstance("olio.llm.chatConfig");
                icfg.api.groupId(chatDir.id);
                icfg.api.groupPath(chatDir.path);
                icfg.api.name(name);
                // Copy relevant fields from template
                if (template.model) icfg.api.model(template.model);
                if (template.serverUrl) icfg.api.serverUrl(template.serverUrl);
                if (template.serviceType) icfg.api.serviceType(template.serviceType);
                if (template.messageTrim) icfg.api.messageTrim(template.messageTrim);
                await page.createObject(icfg.entity);

                // Re-search to get the created object
                qr = await page.search(q);
                if (qr?.results?.length > 0) {
                    return qr.results[0];
                }
                return null;
            } catch (err) {
                console.error("[CardGameLLM] ensureChatConfig failed:", err);
                return null;
            }
        }

        // Extract text content from LLM response
        static extractContent(response) {
            if (!response) return "";
            if (response.messages?.length > 0) {
                const lastMsg = response.messages[response.messages.length - 1];
                return lastMsg.content || lastMsg.text || "";
            }
            return response.content || response.text || String(response);
        }

        // Clean JSON from LLM response (strip markdown, fix common issues)
        static cleanJsonResponse(content) {
            if (!content) return null;

            let cleaned = content
                .replace(/```json\s*/gi, "")
                .replace(/```\s*/g, "")
                .replace(/^[^{]*/, "")
                .replace(/[^}]*$/, "")
                .trim();

            if (!cleaned) return null;

            // Fix common JSON issues
            cleaned = cleaned
                .replace(/,(\s*[}\]])/g, "$1")
                .replace(/'/g, '"');

            return cleaned;
        }

        // Initialize LLM with standard pattern
        async initializeLLM(chatName, promptName, systemPrompt, temperature) {
            try {
                // Check if am7chat is available
                if (typeof am7chat === "undefined" || !am7chat) {
                    this.lastError = "am7chat not available";
                    console.warn("[CardGameLLM] am7chat not available - LLM features disabled");
                    return false;
                }

                const chatDir = await CardGameLLM.findChatDir();
                if (!chatDir) {
                    this.lastError = "~/Chat directory not found";
                    return false;
                }

                const template = await CardGameLLM.getOpenChatTemplate(chatDir);
                if (!template) {
                    this.lastError = "Open Chat template not found";
                    return false;
                }

                this.promptConfig = await CardGameLLM.ensurePromptConfig(chatDir, promptName, systemPrompt);
                if (!this.promptConfig) {
                    this.lastError = "Failed to create prompt config";
                    return false;
                }

                this.chatConfig = await CardGameLLM.ensureChatConfig(chatDir, template, chatName, temperature);
                if (!this.chatConfig) {
                    this.lastError = "Failed to create chat config";
                    return false;
                }

                this.chatRequest = await am7chat.getChatRequest(chatName, this.chatConfig, this.promptConfig);
                this.initialized = !!this.chatRequest;
                if (!this.initialized) {
                    this.lastError = "Failed to get chat request";
                }
                return this.initialized;
            } catch (err) {
                this.lastError = err.message || String(err);
                console.error("[CardGameLLM] initializeLLM failed:", err);
                return false;
            }
        }

        // Send a message and get response
        async chat(prompt) {
            if (!this.initialized || !this.chatRequest) return null;
            try {
                return await am7chat.chat(this.chatRequest, prompt);
            } catch (err) {
                this.lastError = err.message;
                throw err;
            }
        }
    }

    // ── CardGameDirector (Phase 7 LLM) ─────────────────────────────────
    // LLM-powered AI opponent decision making
    let gameDirector = null;

    class CardGameDirector extends CardGameLLM {
        constructor() {
            super();
            this.personality = null;
            this.lastDirective = null;
            this.consecutiveErrors = 0;
        }

        async initialize(opponentChar, themeId) {
            console.log("[CardGameDirector] Initializing for theme:", themeId);
            this.personality = this._extractPersonality(opponentChar);
            const systemPrompt = this._buildSystemPrompt(opponentChar, themeId);
            const ok = await this.initializeLLM(
                "CardGame AI Opponent",
                "CardGame AI Prompt",
                systemPrompt,
                0.4  // Low temperature for consistent decisions
            );
            if (ok) console.log("[CardGameDirector] Initialized successfully");
            return ok;
        }

        _extractPersonality(charPerson) {
            if (!charPerson) return "balanced";
            const alignment = charPerson.alignment || "";
            if (alignment.includes("EVIL") || alignment.includes("CHAOTIC")) return "aggressive";
            if (alignment.includes("LAWFUL") || alignment.includes("GOOD")) return "tactical";
            return "balanced";
        }

        _buildSystemPrompt(opponentChar, themeId) {
            const name = opponentChar?.name || "AI Opponent";
            const personality = this.personality || "balanced";

            return `You are ${name}, an AI opponent in a card game. Theme: ${themeId}.
Personality: ${personality}

You receive game state JSON and respond with placement decision JSON.
Goal: reduce player HP or morale to 0.

Response format:
{"stacks":[{"position":2,"coreCard":"Attack"}],"strategy":"brief"}

Rules:
- "position" must be from availablePositions array
- "coreCard" must match a card name in your hand exactly
- Optional: "modifiers" array of skill card names from hand
- Place up to your AP cards
- Consider energy costs

Reply with ONLY the JSON object, no markdown or text.`;
        }

        async requestPlacement(gameState) {
            if (!this.initialized || !this.chatRequest) {
                return this._fifoFallback(gameState);
            }

            const prompt = this._buildPlacementPrompt(gameState);

            try {
                const response = await am7chat.chat(this.chatRequest, prompt);
                const content = CardGameLLM.extractContent(response);
                const directive = this._parseDirective(content);

                if (directive && directive.stacks) {
                    this.lastDirective = directive;
                    this.consecutiveErrors = 0;
                    console.log("[CardGameDirector] LLM decision:", directive);
                    return directive;
                }

                // Retry once on parse failure
                console.warn("[CardGameDirector] Parse failed, retrying...");
                const retryResponse = await am7chat.chat(this.chatRequest, prompt + "\n\nIMPORTANT: Output ONLY valid JSON, no markdown.");
                const retryContent = CardGameLLM.extractContent(retryResponse);
                const retryDirective = this._parseDirective(retryContent);

                if (retryDirective && retryDirective.stacks) {
                    this.lastDirective = retryDirective;
                    return retryDirective;
                }

                page.toast("warn", "AI decision unclear, using default");
                return this._fifoFallback(gameState);

            } catch (err) {
                this.consecutiveErrors++;
                this.lastError = err.message;
                console.error("[CardGameDirector] Placement request failed:", err);

                if (this.consecutiveErrors === 1) {
                    page.toast("warn", "AI unavailable, using default placement");
                }

                return this._fifoFallback(gameState);
            }
        }

        _buildPlacementPrompt(gameState) {
            const opp = gameState.opponent;
            const player = gameState.player;

            return JSON.stringify({
                type: "placement",
                round: gameState.roundNumber,
                yourTurn: gameState.currentTurn === "opponent",
                ai: {
                    ap: opp.ap - opp.apUsed,
                    energy: opp.needs?.energy || 14,
                    hp: opp.needs?.hp || 20,
                    morale: opp.needs?.morale || 20,
                    hand: opp.hand.map(c => ({
                        name: c.name,
                        type: c.type,
                        energyCost: c.energyCost || 0,
                        atk: c.atk,
                        effect: c.effect || c.onHit
                    }))
                },
                player: {
                    hp: player.needs?.hp || 20,
                    energy: player.needs?.energy || 14,
                    morale: player.needs?.morale || 20
                },
                availablePositions: gameState.initiative.opponentPositions
            }, null, 0);
        }

        _parseDirective(content) {
            if (!content) return null;
            console.log("[CardGameDirector] Raw response:", content.substring(0, 200));

            const cleaned = CardGameLLM.cleanJsonResponse(content);
            if (!cleaned) {
                console.warn("[CardGameDirector] No JSON found in response");
                return null;
            }

            try {
                const parsed = JSON.parse(cleaned);
                if (parsed?.stacks && Array.isArray(parsed.stacks)) {
                    return parsed;
                }
                console.warn("[CardGameDirector] Invalid structure - missing stacks array");
                return null;
            } catch (e) {
                console.warn("[CardGameDirector] JSON parse failed:", e.message);
                return null;
            }
        }

        _fifoFallback(gameState) {
            // Reuse existing simple AI logic
            const opp = gameState.opponent;
            const positions = gameState.initiative.opponentPositions;
            const coreCards = opp.hand.filter(c => isCoreCardType(c.type));

            const stacks = [];
            let cardsUsed = [];

            for (const posIdx of positions) {
                if (stacks.length >= opp.ap - opp.apUsed) break;
                if (coreCards.length === 0) break;

                const playable = coreCards.find(c => c.name === "Attack") ||
                                coreCards.find(c => c.type === "action") ||
                                coreCards[0];

                if (playable && (!playable.energyCost || playable.energyCost <= opp.needs?.energy)) {
                    stacks.push({
                        position: posIdx,
                        coreCard: playable.name,
                        modifiers: [],
                        target: "player"
                    });
                    coreCards.splice(coreCards.indexOf(playable), 1);
                    cardsUsed.push(playable);
                }
            }

            return { stacks, fallback: true };
        }
    }

    // ── CardGameNarrator (Phase 7 LLM) ────────────────────────────────
    // LLM-powered narration at key game moments
    let gameNarrator = null;

    class CardGameNarrator extends CardGameLLM {
        static TRIGGER_POINTS = {
            GAME_START: "game_start",
            ROUND_START: "round_start",
            ENCOUNTER_REVEAL: "encounter_reveal",
            STACK_REVEAL: "stack_reveal",
            RESOLUTION: "resolution",
            ROUND_END: "round_end",
            GAME_END: "game_end"
        };

        static PROFILES = {
            "arena-announcer": {
                name: "Arena Announcer",
                personality: "Bombastic sports commentator. Over-the-top excitement, play-by-play analysis.",
                maxSentences: { game_start: 3, round_start: 2, resolution: 4, round_end: 1, game_end: 3 }
            },
            "dungeon-master": {
                name: "Dungeon Master",
                personality: "Classic tabletop DM. Atmospheric, descriptive, world-building.",
                maxSentences: { game_start: 3, round_start: 2, resolution: 4, round_end: 2, game_end: 3 }
            },
            "war-correspondent": {
                name: "War Correspondent",
                personality: "Gritty battlefield reporter. Terse, factual with emotional undertones.",
                maxSentences: { game_start: 2, round_start: 1, resolution: 3, round_end: 1, game_end: 2 }
            },
            "bard": {
                name: "Bard",
                personality: "Poetic narrator. Speaks in rhythm, references lore, foreshadows.",
                maxSentences: { game_start: 3, round_start: 2, resolution: 5, round_end: 2, game_end: 4 }
            }
        };

        constructor() {
            super();
            this.profile = "arena-announcer";
            this.lastNarration = null;
            this.enabled = true;
        }

        async initialize(profileId, themeId) {
            this.profile = profileId || "arena-announcer";
            const profileConfig = CardGameNarrator.PROFILES[this.profile];
            const systemPrompt = this._buildNarratorPrompt(profileConfig, themeId);
            const ok = await this.initializeLLM(
                "CardGame Narrator",
                "CardGame Narrator Prompt",
                systemPrompt,
                0.7  // Higher temperature for creative narration
            );
            if (ok) console.log("[CardGameNarrator] Initialized with profile:", this.profile);
            return ok;
        }

        _buildNarratorPrompt(profileConfig, themeId) {
            return `You are a ${profileConfig.name} narrating a card game battle. Theme: ${themeId}.

Personality: ${profileConfig.personality}

You will receive game events and must provide brief, engaging narration.
Keep responses concise - usually 1-3 sentences.
Match the tone to the event (dramatic for combat, tense for close calls).

For RESOLUTION events, also suggest a scene for image generation:
Add a line starting with "IMAGE:" describing a single dramatic moment.

Respond with plain text narration only. No JSON, no markdown.`;
        }

        async narrate(trigger, context) {
            if (!this.enabled || !this.initialized || !this.chatRequest) {
                return { text: null, imagePrompt: null };
            }

            try {
                const prompt = this._buildTriggerPrompt(trigger, context);
                const response = await this.chat(prompt);
                const content = CardGameLLM.extractContent(response);
                const parsed = this._parseNarration(content);
                this.lastNarration = parsed;
                return parsed;
            } catch (err) {
                console.error("[CardGameNarrator] Narration failed:", err);
                return { text: null, imagePrompt: null };
            }
        }

        _buildTriggerPrompt(trigger, context) {
            const profile = CardGameNarrator.PROFILES[this.profile];
            const maxSentences = profile?.maxSentences?.[trigger] || 3;

            let prompt = `EVENT: ${trigger.toUpperCase()}\n`;

            switch (trigger) {
                case "game_start":
                    prompt += `A new battle begins!\n`;
                    prompt += `${context.playerName} faces ${context.opponentName}\n`;
                    prompt += `Introduce both combatants dramatically.\n`;
                    break;

                case "game_end":
                    prompt += `The battle is over after ${context.rounds} rounds!\n`;
                    prompt += `Winner: ${context.winner}\n`;
                    prompt += `Defeated: ${context.loser}\n`;
                    prompt += context.isPlayerVictory
                        ? `Celebrate the player's triumph!\n`
                        : `Acknowledge the player's defeat with dignity.\n`;
                    break;

                case "round_start":
                    prompt += `Round ${context.roundNumber}\n`;
                    prompt += `Player HP: ${context.playerHp}/20, Opponent HP: ${context.opponentHp}/20\n`;
                    break;

                case "resolution":
                    prompt += `Player played: ${context.playerStack || "nothing"}\n`;
                    prompt += `Player roll: ${context.playerRoll?.raw || "?"} + mods = ${context.playerRoll?.total || "?"}\n`;
                    prompt += `Opponent played: ${context.opponentStack || "nothing"}\n`;
                    prompt += `Opponent roll: ${context.opponentRoll?.raw || "?"} + mods = ${context.opponentRoll?.total || "?"}\n`;
                    prompt += `Result: ${context.outcome} (${context.damage || 0} damage)\n`;
                    break;

                case "round_end":
                    prompt += `Round ${context.roundNumber} complete\n`;
                    prompt += `Player HP: ${context.playerHp}, Opponent HP: ${context.opponentHp}\n`;
                    break;
            }

            // Add Poker Face emotion hint if available
            if (context.playerEmotion && context.playerEmotionDesc) {
                prompt += `\nPOKER FACE: The player ${context.playerEmotionDesc}. Work this into your narration subtly.`;
            }

            prompt += `\nNarrate in ${maxSentences} sentences or less.`;
            if (trigger === "resolution") {
                prompt += `\nAlso add "IMAGE:" line for scene generation.`;
            }

            return prompt;
        }

        _parseNarration(content) {
            const lines = content.split("\n");
            let text = "";
            let imagePrompt = null;

            for (const line of lines) {
                if (line.trim().toUpperCase().startsWith("IMAGE:")) {
                    imagePrompt = line.replace(/^IMAGE:\s*/i, "").trim();
                } else if (line.trim()) {
                    text += (text ? " " : "") + line.trim();
                }
            }

            return { text, imagePrompt };
        }
    }

    // Apply AI decision from CardGameDirector to game state
    function applyAIDecision(decision) {
        if (!gameState || !decision || !decision.stacks) return;

        const opp = gameState.opponent;

        for (const stack of decision.stacks) {
            const pos = gameState.actionBar.positions.find(p => p.index === stack.position);
            if (!pos || pos.stack) continue;

            // Find the core card by name
            const coreCard = opp.hand.find(c => c.name === stack.coreCard);
            if (!coreCard) continue;

            // Check energy
            if (coreCard.energyCost && coreCard.energyCost > (opp.needs?.energy || 0)) continue;

            // Find modifier cards
            const modifiers = (stack.modifiers || [])
                .map(name => opp.hand.find(c => c.name === name))
                .filter(Boolean);

            // Place the stack
            pos.stack = { coreCard, modifiers };
            opp.apUsed++;

            if (coreCard.energyCost) {
                opp.needs.energy = (opp.needs.energy || 14) - coreCard.energyCost;
            }

            // Remove cards from hand
            opp.hand = opp.hand.filter(c => c !== coreCard && !modifiers.includes(c));

            console.log("[CardGame v2] AI placed:", coreCard.name, "at position", stack.position);
        }
    }

    // ── CardGameChatManager (Phase 8 LLM) ─────────────────────────────
    // LLM-powered NPC conversation for Talk cards
    let gameChatManager = null;

    class CardGameChatManager extends CardGameLLM {
        constructor() {
            super();
            this.npcName = null;
            this.npcPersonality = null;
            this.interactionHistory = [];
            this.currentConversation = [];
            this.chatActive = false;
        }

        async initialize(npcCharacter, themeId) {
            this.npcName = npcCharacter?.name || "NPC";
            this.npcPersonality = this._extractNpcPersonality(npcCharacter);
            const systemPrompt = this._buildChatPrompt(npcCharacter, themeId);

            const ok = await this.initializeLLM(
                "CardGame NPC Chat",
                "CardGame NPC Chat Prompt",
                systemPrompt,
                0.8  // Higher temperature for varied dialogue
            );
            if (ok) console.log("[CardGameChatManager] Initialized for NPC:", this.npcName);
            return ok;
        }

        _extractNpcPersonality(charPerson) {
            if (!charPerson) return { tone: "neutral", traits: [] };
            const traits = charPerson.personality?.split(",").map(t => t.trim()) || [];
            const alignment = charPerson.alignment || "NEUTRAL";
            let tone = "neutral";
            if (alignment.includes("EVIL")) tone = "hostile";
            else if (alignment.includes("GOOD")) tone = "friendly";
            else if (alignment.includes("CHAOTIC")) tone = "unpredictable";
            return { tone, traits, alignment };
        }

        _buildChatPrompt(npcCharacter, themeId) {
            const name = npcCharacter?.name || "Unknown";
            const desc = npcCharacter?.description || "";
            const personality = this.npcPersonality;

            return `You are ${name}, an NPC in a card game battle. Theme: ${themeId}.
${desc ? `Description: ${desc}` : ""}
Personality traits: ${personality.traits.join(", ") || "mysterious"}
Disposition: ${personality.tone}

You are being addressed by your opponent during combat via a Talk card.
Respond in character with 1-3 sentences. Stay true to your personality.
${personality.tone === "hostile" ? "You are hostile but can be persuaded." : ""}
${personality.tone === "friendly" ? "You are open to conversation." : ""}

If the player is trying to:
- Taunt: React according to your personality (angry, amused, dismissive)
- Persuade: Consider their words but stay in character
- Intimidate: Show fear or defiance based on your traits
- Negotiate: Be open or closed depending on alignment

Respond naturally in character. No game mechanics, just dialogue.`;
        }

        async startConversation() {
            this.chatActive = true;
            this.currentConversation = [];
            console.log("[CardGameChatManager] Conversation started with:", this.npcName);
            return true;
        }

        async sendMessage(playerMessage) {
            if (!this.initialized || !this.chatActive) {
                return { text: "...", error: true };
            }

            try {
                // Build context with recent conversation
                const contextMessages = this.currentConversation.slice(-4)
                    .map(m => `${m.role === "player" ? "Player" : this.npcName}: ${m.text}`)
                    .join("\n");

                const prompt = contextMessages
                    ? `Previous exchange:\n${contextMessages}\n\nPlayer: ${playerMessage}\n\n${this.npcName}:`
                    : `Player: ${playerMessage}\n\n${this.npcName}:`;

                const response = await this.chat(prompt);
                const npcReply = CardGameLLM.extractContent(response).trim();

                // Store in conversation history
                this.currentConversation.push({ role: "player", text: playerMessage });
                this.currentConversation.push({ role: "npc", text: npcReply });

                return { text: npcReply, speaker: this.npcName };
            } catch (err) {
                console.error("[CardGameChatManager] Message failed:", err);
                return { text: "...", error: true };
            }
        }

        async concludeConversation() {
            this.chatActive = false;

            // Create interaction record for future context
            const interaction = {
                timestamp: Date.now(),
                npcName: this.npcName,
                messages: [...this.currentConversation],
                outcome: this._evaluateOutcome()
            };

            this.interactionHistory.push(interaction);
            console.log("[CardGameChatManager] Conversation concluded. Outcome:", interaction.outcome);

            return interaction;
        }

        _evaluateOutcome() {
            // Simple heuristic based on conversation length and content
            const msgCount = this.currentConversation.length;
            if (msgCount === 0) return "silent";
            if (msgCount <= 2) return "brief";
            if (msgCount <= 4) return "moderate";
            return "extensive";
        }

        loadInteractionHistory(history) {
            this.interactionHistory = history || [];
        }

        getInteractionSummary() {
            return this.interactionHistory.map(i => ({
                npc: i.npcName,
                when: new Date(i.timestamp).toLocaleDateString(),
                outcome: i.outcome,
                preview: i.messages[0]?.text?.substring(0, 50) + "..."
            }));
        }
    }

    // ── CardGameVoice (Phase 8 Voice Synthesis) ───────────────────────
    // Voice synthesis for narrator using existing audio infrastructure
    let gameVoice = null;

    class CardGameVoice {
        constructor() {
            this.enabled = false;
            this.voiceProfileId = null;
            this.volume = 1.0;
            this.speaking = false;
            this.queue = [];
            this.subtitlesOnly = false;
        }

        async initialize(voiceConfig) {
            // Check if audio infrastructure exists
            if (typeof page?.components?.audio?.createAudioSource !== "function") {
                console.log("[CardGameVoice] Audio infrastructure not available, subtitles only");
                this.subtitlesOnly = true;
                this.enabled = true;
                return true;
            }

            this.voiceProfileId = voiceConfig?.voiceProfileId || null;
            this.volume = voiceConfig?.volume ?? 1.0;
            this.subtitlesOnly = voiceConfig?.subtitlesOnly ?? false;
            this.enabled = true;

            console.log("[CardGameVoice] Initialized. Profile:", this.voiceProfileId || "default");
            return true;
        }

        async speak(text, options = {}) {
            if (!this.enabled || !text) return;
            if (this.subtitlesOnly) {
                // Just show subtitles without audio
                showNarrationSubtitle(text);
                return;
            }

            // Queue if already speaking
            if (this.speaking) {
                this.queue.push({ text, options });
                return;
            }

            this.speaking = true;
            const name = "cardgame-voice-" + Date.now();

            try {
                // Use page.components.audio if available
                const audioComponent = page?.components?.audio;
                if (audioComponent?.createAudioSource) {
                    const source = await audioComponent.createAudioSource(name, this.voiceProfileId, text);
                    if (source) {
                        await this._playAudioSource(source);
                    }
                }
            } catch (err) {
                console.warn("[CardGameVoice] Speech synthesis failed:", err);
            } finally {
                this.speaking = false;
                // Process queue
                if (this.queue.length > 0) {
                    const next = this.queue.shift();
                    this.speak(next.text, next.options);
                }
            }
        }

        async _playAudioSource(source) {
            return new Promise((resolve) => {
                if (!source?.source || !source?.context) {
                    resolve();
                    return;
                }

                const sourceNode = source.context.createBufferSource();
                sourceNode.buffer = source.buffer;

                // Apply volume
                const gainNode = source.context.createGain();
                gainNode.gain.value = this.volume;

                sourceNode.connect(gainNode);
                gainNode.connect(source.context.destination);

                sourceNode.onended = () => resolve();
                sourceNode.start();
            });
        }

        setVolume(level) {
            this.volume = Math.max(0, Math.min(1, level));
        }

        stop() {
            this.queue = [];
            // Note: Stopping mid-playback would require tracking active source nodes
        }
    }

    // ── Poker Face Integration (Phase 8 Emotion Capture) ──────────────
    // Uses moodRing component for emotion detection
    function getPlayerEmotion() {
        if (!page?.components?.moodRing?.enabled()) return null;
        return page.components.moodRing.emotion();
    }

    function getPlayerMoodColor() {
        if (!page?.components?.moodRing?.enabled()) return null;
        return page.components.moodRing.moodColor();
    }

    // Build emotion context for narrator
    function buildEmotionContext() {
        const emotion = getPlayerEmotion();
        if (!emotion || emotion === "neutral") return null;

        const emotionDescriptions = {
            happy: "appears pleased",
            sad: "looks dejected",
            angry: "seems frustrated",
            fear: "appears nervous",
            surprise: "looks startled",
            disgust: "seems unimpressed"
        };

        return {
            emotion,
            description: emotionDescriptions[emotion] || null
        };
    }

    // ── Simple AI Card Placement ──────────────────────────────────────
    // Now with optional LLM integration via CardGameDirector
    async function aiPlaceCards() {
        if (!gameState) return;

        let opp = gameState.opponent;
        let positions = gameState.initiative.opponentPositions;

        console.log("[CardGame v2] AI placing cards. Hand:", opp.hand.length, "AP:", opp.ap - opp.apUsed);

        // Try LLM-based placement if director is available
        if (gameDirector && gameDirector.initialized) {
            try {
                const decision = await gameDirector.requestPlacement(gameState);
                if (decision && decision.stacks && !decision.fallback) {
                    applyAIDecision(decision);
                    checkPlacementComplete();
                    m.redraw();
                    return;
                }
            } catch (err) {
                console.warn("[CardGame v2] Director placement failed, using fallback:", err);
            }
        }

        // Fallback: Simple FIFO placement
        let coreCards = opp.hand.filter(c => isCoreCardType(c.type));
        let modifierCards = opp.hand.filter(c => c.type === "skill");

        // Phase 1: Place core cards (actions, talk, magic) on available positions
        for (let posIdx of positions) {
            if (opp.apUsed >= opp.ap) break;
            if (coreCards.length === 0) break;

            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            if (!pos || pos.stack) continue;

            // Find a playable core card (prefer Attack)
            let playable = coreCards.find(c => c.name === "Attack") ||
                          coreCards.find(c => c.type === "action") ||
                          coreCards[0];

            if (playable) {
                // Check energy cost
                if (playable.energyCost && playable.energyCost > opp.energy) {
                    coreCards = coreCards.filter(c => c !== playable);
                    continue;
                }

                pos.stack = { coreCard: playable, modifiers: [] };
                opp.apUsed++;

                if (playable.energyCost) {
                    opp.energy -= playable.energyCost;
                }

                // Remove from hand and tracking arrays
                let idx = opp.hand.indexOf(playable);
                if (idx >= 0) opp.hand.splice(idx, 1);
                coreCards = coreCards.filter(c => c !== playable);

                console.log("[CardGame v2] AI placed core:", playable.name, "at position", posIdx);
            }
        }

        // Phase 2: Add skill modifiers to placed stacks (no AP cost)
        for (let posIdx of positions) {
            if (modifierCards.length === 0) break;

            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            if (!pos || !pos.stack || !pos.stack.coreCard) continue;

            // Add one skill modifier to this stack
            let skill = modifierCards.shift();
            if (skill) {
                pos.stack.modifiers.push(skill);
                let idx = opp.hand.indexOf(skill);
                if (idx >= 0) opp.hand.splice(idx, 1);
                console.log("[CardGame v2] AI added modifier:", skill.name, "to position", posIdx);
            }
        }

        // If AI still has AP but can't place anything, try drawing first
        let remainingCores = opp.hand.filter(c => isCoreCardType(c.type));
        let playableCores = remainingCores.filter(c => !c.energyCost || c.energyCost <= opp.energy);
        let emptyPositions = positions.filter(posIdx => {
            let pos = gameState.actionBar.positions.find(p => p.index === posIdx);
            return pos && !pos.stack;
        });

        // If can't place but have AP and draw pile has cards, draw one
        if (opp.apUsed < opp.ap && playableCores.length === 0 && emptyPositions.length > 0 && opp.drawPile.length > 0) {
            console.log("[CardGame v2] AI drawing a card (no playable cores)");
            drawCardsForActor(opp, 1);
            // Check again for playable cores after draw
            remainingCores = opp.hand.filter(c => isCoreCardType(c.type));
            playableCores = remainingCores.filter(c => !c.energyCost || c.energyCost <= opp.energy);
        }

        // Forfeit if still can't place after potential draw
        if (opp.apUsed < opp.ap && (playableCores.length === 0 || emptyPositions.length === 0)) {
            console.log("[CardGame v2] AI forfeiting remaining AP - playable cores:", playableCores.length,
                "(total cores:", remainingCores.length, ") empty positions:", emptyPositions.length, "energy:", opp.energy);
            opp.apUsed = opp.ap;  // Mark as fully used
        }

        // AI placement done, switch back to player or end placement
        checkPlacementComplete();
    }

    function checkPlacementComplete() {
        if (!gameState) return;

        let playerDone = gameState.player.apUsed >= gameState.player.ap;
        let opponentDone = gameState.opponent.apUsed >= gameState.opponent.ap;

        console.log("[CardGame v2] checkPlacementComplete - Player done:", playerDone, "Opponent done:", opponentDone);

        if (playerDone && opponentDone) {
            console.log("[CardGame v2] Both done, advancing to resolution in 800ms");
            // Brief delay so player can see their last card placed
            setTimeout(() => {
                if (gameState && gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                    advancePhase(); // Move to resolution
                }
            }, 800);
        } else {
            gameState.currentTurn = playerDone ? "opponent" : "player";
            console.log("[CardGame v2] Turn switched to:", gameState.currentTurn);
            m.redraw();

            // If it's now the opponent's turn and they have AP, trigger AI
            if (gameState.currentTurn === "opponent" && !opponentDone) {
                setTimeout(() => {
                    if (gameState && gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                        console.log("[CardGame v2] Triggering AI placement continuation");
                        aiPlaceCards();
                        m.redraw();
                    }
                }, 500);
            }
        }
    }

    // ── Resolution Phase ──────────────────────────────────────────────
    let resolutionAnimating = false;
    let resolutionPhase = "idle";  // "idle" | "rolling" | "result" | "done"
    let resolutionDiceFaces = { attack: 1, defense: 1 };
    let resolutionDiceInterval = null;

    function advanceResolution() {
        if (!gameState) return;
        if (gameState.phase !== GAME_PHASES.RESOLUTION) return;
        if (resolutionAnimating) return;

        let bar = gameState.actionBar;
        if (bar.resolveIndex >= bar.positions.length) {
            // Resolution complete - check for game over
            let winner = checkGameOver();
            if (winner) {
                gameState.winner = winner;
                gameState.phase = "GAME_OVER";
                m.redraw();
                return;
            }
            advancePhase();
            return;
        }

        let pos = bar.positions[bar.resolveIndex];
        if (pos.resolved) {
            bar.resolveIndex++;
            advanceResolution();
            return;
        }

        // Check if this is a threat position (beginning threat from Nat 1)
        let isThreat = pos.isThreat && pos.threat;

        // Determine if this is a combat action
        let card = pos.stack?.coreCard;
        let isAttack = (card && card.name === "Attack") || isThreat;

        if (isAttack) {
            // Combat resolution with dice animation
            resolutionAnimating = true;
            resolutionPhase = "rolling";
            currentCombatResult = null;

            // Start dice animation
            resolutionDiceInterval = setInterval(() => {
                resolutionDiceFaces.attack = rollD20();
                resolutionDiceFaces.defense = rollD20();
                m.redraw();
            }, 80);

            m.redraw();

            // After 1.5 seconds, resolve the combat
            setTimeout(() => {
                clearInterval(resolutionDiceInterval);
                resolutionDiceInterval = null;

                // Determine attacker and defender
                let attacker, defender;
                if (isThreat) {
                    // Threat attacks the player who fumbled (rolled Nat 1)
                    attacker = {
                        name: pos.threat.name,
                        character: {
                            name: pos.threat.name,
                            stats: { STR: pos.threat.atk, END: pos.threat.def }
                        },
                        hp: pos.threat.hp,
                        maxHp: pos.threat.maxHp,
                        cardStack: [{ type: "item", subtype: "weapon", atk: pos.threat.atk }]
                    };
                    defender = pos.target === "player" ? gameState.player : gameState.opponent;
                } else {
                    attacker = pos.owner === "player" ? gameState.player : gameState.opponent;
                    defender = pos.owner === "player" ? gameState.opponent : gameState.player;
                }

                // Resolve combat (pass stack for skill modifiers)
                resolveCombat(attacker, defender, pos.stack);
                resolutionPhase = "result";

                // Trigger narrator for resolution (non-blocking, with fallback)
                if (currentCombatResult) {
                    triggerNarration("resolution", {
                        isPlayerAttack: pos.owner === "player",
                        attackerName: isThreat ? pos.threat?.name : (pos.owner === "player" ? gameState.player.character?.name : gameState.opponent.character?.name),
                        defenderName: isThreat ? (pos.target === "player" ? gameState.player.character?.name : gameState.opponent.character?.name) : (pos.owner === "player" ? gameState.opponent.character?.name : gameState.player.character?.name),
                        outcome: currentCombatResult.outcome?.label || "Hit",
                        damage: currentCombatResult.damageDealt || 0
                    });
                }

                m.redraw();

                // After showing result, mark as resolved
                setTimeout(() => {
                    pos.resolved = true;
                    pos.combatResult = currentCombatResult;
                    resolutionAnimating = false;
                    resolutionPhase = "done";
                    bar.resolveIndex++;

                    // Exhausted check: if attack failed (miss/counter), check for hoarding
                    if (currentCombatResult && currentCombatResult.outcome.damageMultiplier <= 0) {
                        let actionType = pos.stack?.coreCard?.name;
                        let ownerKey = pos.owner;
                        let ownerActor = ownerKey === "player" ? gameState.player : gameState.opponent;
                        let ownerName = ownerKey === "player" ? "Player" : "Opponent";

                        let exhaustedResult = checkExhausted(ownerActor, ownerName, actionType);
                        if (exhaustedResult) {
                            // Store for UI display
                            if (!gameState.exhaustedThisRound) gameState.exhaustedThisRound = [];
                            gameState.exhaustedThisRound.push({
                                owner: ownerKey,
                                actionType: exhaustedResult.actionType,
                                stripped: exhaustedResult.stripped
                            });
                            console.log("[CardGame v2] Exhausted triggered for", ownerName, "- stripped", exhaustedResult.stripped, exhaustedResult.actionType);
                        }
                    }

                    // Handle threat resolution outcome
                    if (isThreat && pos.threat) {
                        let targetActor = pos.target === "player" ? gameState.player : gameState.opponent;
                        let targetName = pos.target === "player" ? "Player" : "Opponent";

                        // Check if defender won (threat missed or was countered)
                        if (currentCombatResult && currentCombatResult.outcome.damageMultiplier <= 0) {
                            // Defender survived/won - grant loot
                            console.log("[CardGame v2]", targetName, "defeated the threat! Loot earned:", pos.threat.lootRarity);

                            // Create loot card based on threat rarity
                            let lootCard = {
                                type: "item",
                                subtype: "consumable",
                                name: "Threat Loot (" + pos.threat.lootRarity + ")",
                                rarity: pos.threat.lootRarity,
                                effect: pos.threat.lootRarity === "RARE" ? "Restore 5 HP" : "Restore 3 HP",
                                flavor: "Spoils from defeating " + pos.threat.name
                            };
                            targetActor.hand.push(lootCard);

                            // Store threat result for UI
                            if (!gameState.threatResults) gameState.threatResults = [];
                            gameState.threatResults.push({
                                threatName: pos.threat.name,
                                target: pos.target,
                                outcome: "defeated",
                                loot: lootCard.name
                            });
                        } else {
                            // Threat hit the defender
                            console.log("[CardGame v2]", targetName, "was hit by", pos.threat.name);

                            if (!gameState.threatResults) gameState.threatResults = [];
                            gameState.threatResults.push({
                                threatName: pos.threat.name,
                                target: pos.target,
                                outcome: "hit",
                                damage: currentCombatResult?.damageResult?.finalDamage || 0
                            });
                        }
                    }

                    // Move played cards to owner's discard pile (or return magic to hand)
                    if (pos.stack && pos.stack.coreCard && !isThreat) {
                        let owner = pos.owner === "player" ? gameState.player : gameState.opponent;
                        let coreCard = pos.stack.coreCard;

                        // Magic cards return to hand (reusable)
                        if (coreCard.type === "magic" && coreCard.reusable !== false) {
                            owner.hand.push(coreCard);
                            console.log("[CardGame v2] Magic card", coreCard.name, "returned to hand");
                        } else {
                            owner.discardPile.push(coreCard);
                        }

                        // Modifiers always go to discard
                        pos.stack.modifiers.forEach(mod => owner.discardPile.push(mod));
                    }

                    // Check for game over after each combat
                    let winner = checkGameOver();
                    if (winner) {
                        gameState.winner = winner;
                        gameState.phase = "GAME_OVER";
                        m.redraw();
                        return;
                    }

                    m.redraw();

                    // Auto-advance after 3 seconds total between actions
                    if (bar.resolveIndex < bar.positions.length) {
                        setTimeout(() => advanceResolution(), 1500);  // 1.5s after result shown
                    } else {
                        setTimeout(() => advancePhase(), 1000);
                    }
                }, 1500);  // Show result for 1.5 seconds (total ~3s per action)
            }, 1500);  // Roll dice for 1.5 seconds
        } else {
            // Non-combat action (Rest, Flee, etc.) - simple resolution
            resolutionAnimating = true;
            currentCombatResult = null;

            setTimeout(() => {
                pos.resolved = true;
                resolutionAnimating = false;
                bar.resolveIndex++;

                // Handle non-combat actions
                if (card) {
                    let owner = pos.owner === "player" ? gameState.player : gameState.opponent;
                    let target = pos.owner === "player" ? gameState.opponent : gameState.player;

                    // Rest action: restore HP and Energy
                    if (card.name === "Rest") {
                        let hpBefore = owner.hp;
                        let energyBefore = owner.energy;
                        let moraleBefore = owner.morale;
                        owner.hp = Math.min(owner.maxHp, owner.hp + 2);
                        owner.energy = Math.min(owner.maxEnergy, owner.energy + 3);
                        owner.morale = Math.min(owner.maxMorale, owner.morale + 2);
                        console.log("[CardGame v2]", pos.owner, "rested:",
                            "HP", hpBefore, "->", owner.hp, "(max:" + owner.maxHp + ")",
                            "| Energy", energyBefore, "->", owner.energy,
                            "| Morale", moraleBefore, "->", owner.morale);
                    } else {
                        console.log("[CardGame v2] Non-combat action card:", card.name, card.type);
                    }

                    // Guard/Defend action: gain shielded status
                    if (card.name === "Guard" || card.name === "Defend") {
                        applyStatusEffect(owner, "shielded", card.name);
                        console.log("[CardGame v2]", pos.owner, "is now shielded (+3 DEF until hit)");
                    }

                    // Flee action: AGI roll to escape (forfeit pot, end combat)
                    if (card.name === "Flee" || card.name === "Escape") {
                        let ownerAgi = owner.character.stats?.AGI || 10;
                        let fleeRoll = rollD20() + ownerAgi;
                        let fleeDC = 12;  // Base difficulty, could be modified by encounter

                        if (fleeRoll >= fleeDC) {
                            console.log("[CardGame v2]", pos.owner, "fled successfully! Roll:", fleeRoll, "vs DC", fleeDC);
                            // Forfeit pot to opponent
                            if (gameState.pot.length > 0) {
                                let other = pos.owner === "player" ? gameState.opponent : gameState.player;
                                gameState.pot.forEach(c => other.discardPile.push(c));
                                gameState.pot = [];
                                console.log("[CardGame v2] Pot forfeited to", pos.owner === "player" ? "opponent" : "player");
                            }
                            // End the round early (skip remaining actions)
                            bar.resolveIndex = bar.positions.length;
                        } else {
                            console.log("[CardGame v2]", pos.owner, "flee failed! Roll:", fleeRoll, "vs DC", fleeDC);
                            // Failed flee - lose morale
                            owner.morale = Math.max(0, owner.morale - 2);
                        }
                    }

                    // Investigate action: INT roll to reveal information
                    if (card.name === "Investigate" || card.name === "Search") {
                        let ownerInt = owner.character.stats?.INT || 10;
                        let investRoll = rollD20() + ownerInt;
                        let investDC = 10;

                        if (investRoll >= investDC) {
                            console.log("[CardGame v2]", pos.owner, "investigated successfully! Roll:", investRoll);
                            // Draw an extra card as reward
                            drawCardsForActor(owner, 1);
                        } else {
                            console.log("[CardGame v2]", pos.owner, "investigation found nothing. Roll:", investRoll);
                        }
                    }

                    // Use Item action: apply consumable effect
                    if (card.type === "item" && card.subtype === "consumable") {
                        // Use unified effect parser for consumable effects
                        let parsed = parseEffect(card.effect || "");
                        let log = applyParsedEffects(parsed, owner, target, card.name);
                        log.forEach(msg => console.log("[CardGame v2]", pos.owner, "used:", msg));
                        // Consumables are NOT returned to hand - they stay in discard
                    }

                    // Feint action: weaken opponent's next defense
                    if (card.name === "Feint") {
                        applyStatusEffect(target, "weakened", card.name);
                        console.log("[CardGame v2]", pos.owner, "feinted! Target is weakened (-2 all rolls)");
                    }

                    // Trade action: exchange items (vs AI: auto-decline unless items offered)
                    if (card.name === "Trade") {
                        let offeredItems = (pos.stack?.modifiers || []).filter(m => m.type === "item");
                        if (offeredItems.length > 0) {
                            // AI evaluates trade: 50% accept if items of equal or greater value offered
                            let acceptChance = 0.5;
                            if (Math.random() < acceptChance) {
                                // Accept: AI gives a random item from hand in exchange
                                let aiItems = target.hand.filter(c => c.type === "item");
                                if (aiItems.length > 0) {
                                    let giveItem = aiItems[Math.floor(Math.random() * aiItems.length)];
                                    target.hand = target.hand.filter(c => c !== giveItem);
                                    owner.hand.push(giveItem);
                                    // Give offered items to AI
                                    offeredItems.forEach(item => {
                                        owner.hand = owner.hand.filter(c => c !== item);
                                        target.hand.push(item);
                                    });
                                    console.log("[CardGame v2] Trade accepted!", owner.name, "gave", offeredItems.map(i => i.name).join(", "), "received", giveItem.name);
                                    pos.tradeResult = { accepted: true, gave: offeredItems, received: [giveItem] };
                                } else {
                                    console.log("[CardGame v2] Trade declined - AI has no items to trade");
                                    pos.tradeResult = { accepted: false, reason: "No items available" };
                                }
                            } else {
                                console.log("[CardGame v2] Trade declined by opponent");
                                pos.tradeResult = { accepted: false, reason: "Declined" };
                            }
                        } else {
                            console.log("[CardGame v2] Trade failed - no items offered");
                            pos.tradeResult = { accepted: false, reason: "No items offered" };
                        }
                    }

                    // Craft action: INT roll to create item from materials
                    if (card.name === "Craft") {
                        let ownerInt = owner.character.stats?.INT || 10;
                        let materials = (pos.stack?.modifiers || []).filter(m => m.type === "item" && m.subtype === "material");

                        if (materials.length >= 2) {
                            let craftRoll = rollD20() + ownerInt;
                            let craftDC = 12 + (materials.length * 2);  // Harder with more materials

                            if (craftRoll >= craftDC) {
                                // Success: create item based on materials
                                let craftedItem = {
                                    type: "item",
                                    subtype: "consumable",
                                    name: "Crafted " + materials[0].name + " Potion",
                                    effect: "Restore " + (5 + materials.length) + " HP",
                                    rarity: materials.length >= 3 ? "RARE" : "COMMON",
                                    flavor: "Crafted from " + materials.map(m => m.name).join(" + ")
                                };
                                owner.hand.push(craftedItem);
                                // Remove materials from hand
                                materials.forEach(mat => {
                                    owner.hand = owner.hand.filter(c => c !== mat);
                                });
                                console.log("[CardGame v2] Crafting success! Roll:", craftRoll, "vs DC", craftDC, "- Created:", craftedItem.name);
                                pos.craftResult = { success: true, roll: craftRoll, dc: craftDC, created: craftedItem };
                            } else {
                                console.log("[CardGame v2] Crafting failed. Roll:", craftRoll, "vs DC", craftDC);
                                // Lose one material on failure
                                if (materials.length > 0) {
                                    let lostMat = materials[0];
                                    owner.hand = owner.hand.filter(c => c !== lostMat);
                                    owner.discardPile.push(lostMat);
                                }
                                pos.craftResult = { success: false, roll: craftRoll, dc: craftDC };
                            }
                        } else {
                            console.log("[CardGame v2] Crafting failed - need at least 2 materials");
                            pos.craftResult = { success: false, reason: "Insufficient materials" };
                        }
                    }

                    // Talk action: Open chat UI for player, dice roll for AI
                    if (card.type === "talk") {
                        if (pos.owner === "player") {
                            // Player Talk: Open chat UI (Phase 8)
                            // Chat works with or without LLM - will use fallback responses if needed
                            console.log("[CardGame v2] Opening Talk chat. LLM available:", !!gameChatManager?.initialized);
                            openTalkChat(card, bar.resolveIndex);
                            // Resolution continues when chat ends
                            return;  // Don't auto-advance - chat will handle it
                        } else {
                            // Opponent Talk: Use dice roll
                            let ownerCha = owner.character.stats?.CHA || 10;
                            let targetCha = target.character.stats?.CHA || 10;
                            let skillMod = getStackSkillMod(pos.stack, "talk");

                            let ownerRoll = rollD20() + ownerCha + skillMod;
                            let targetRoll = rollD20() + targetCha;

                            if (ownerRoll > targetRoll) {
                                let moraleDmg = Math.max(1, Math.floor((ownerRoll - targetRoll) / 2));
                                target.morale = Math.max(0, target.morale - moraleDmg);
                                owner.morale = Math.min(owner.maxMorale, owner.morale + 1);
                                console.log("[CardGame v2]", pos.owner, "Talk success! Target morale -" + moraleDmg + ", own +1");
                            } else {
                                owner.morale = Math.max(0, owner.morale - 1);
                                console.log("[CardGame v2]", pos.owner, "Talk failed. Own morale -1");
                            }

                            if (target.morale <= 0) {
                                console.log("[CardGame v2] Target surrendered due to morale!");
                                gameState.winner = pos.owner;
                                gameState.phase = "GAME_OVER";
                                m.redraw();
                                return;
                            }
                        }
                    }

                    // Magic card resolution
                    if (card.type === "magic") {
                        let ownerStats = owner.character.stats || {};
                        let fizzled = false;

                        // Check stat requirements (fizzle if not met)
                        if (card.requires) {
                            for (let stat in card.requires) {
                                let required = card.requires[stat];
                                let actual = ownerStats[stat] || 0;
                                if (actual < required) {
                                    fizzled = true;
                                    console.log("[CardGame v2] Spell fizzled!", card.name, "requires", stat, required, "but actor has", actual);
                                    break;
                                }
                            }
                        }

                        if (!fizzled) {
                            // Use unified effect parser for all magic effects
                            let parsed = parseEffect(card.effect || "");
                            let log = applyParsedEffects(parsed, owner, target, card.name);
                            log.forEach(msg => console.log("[CardGame v2]", pos.owner, "cast:", msg));

                            // Check for defeat after damage
                            if (parsed.damage && target.hp <= 0) {
                                gameState.winner = pos.owner;
                                gameState.phase = "GAME_OVER";
                                m.redraw();
                                return;
                            }
                        }
                    }

                    // Move played cards to discard pile OR return magic to hand
                    if (card.type === "magic" && card.reusable !== false) {
                        // Magic cards return to hand (reusable)
                        owner.hand.push(card);
                        console.log("[CardGame v2] Magic card", card.name, "returned to hand");
                    } else {
                        owner.discardPile.push(card);
                    }

                    // Modifiers always go to discard
                    if (pos.stack.modifiers) {
                        pos.stack.modifiers.forEach(mod => owner.discardPile.push(mod));
                    }
                }

                m.redraw();

                // Auto-advance after ~3 seconds total
                if (bar.resolveIndex < bar.positions.length) {
                    setTimeout(() => advanceResolution(), 2000);
                } else {
                    setTimeout(() => advancePhase(), 1000);
                }
            }, 1000);  // Show action result for 1 second
        }

        m.redraw();
    }

    // ── Character Selection UI ────────────────────────────────────────
    // Calculate total stat score for a character
    function calcCharScore(char) {
        let stats = char.stats || {};
        return (stats.STR || 0) + (stats.AGI || 0) + (stats.END || 0) +
               (stats.INT || 0) + (stats.MAG || 0) + (stats.CHA || 0);
    }

    function CharacterSelectUI() {
        return {
            view() {
                if (!gameCharSelection) return null;
                let chars = gameCharSelection.characters || [];

                // Calculate best and worst characters by total stats
                let bestChar = null, worstChar = null;
                let bestScore = -Infinity, worstScore = Infinity;
                if (chars.length > 1) {
                    chars.forEach(c => {
                        let score = calcCharScore(c);
                        if (score > bestScore) { bestScore = score; bestChar = c; }
                        if (score < worstScore) { worstScore = score; worstChar = c; }
                    });
                    // Don't mark if all same score
                    if (bestScore === worstScore) { bestChar = null; worstChar = null; }
                }

                // Get card front background from deck if available
                let cardFrontBg = viewingDeck && viewingDeck.cardFrontImageUrl
                    ? viewingDeck.cardFrontImageUrl : null;

                // Get tabletop background from deck if available
                let tabletopBg = viewingDeck && viewingDeck.tabletopThumbUrl
                    ? viewingDeck.tabletopThumbUrl.replace(/\/\d+x\d+$/, "/1024x1024")
                    : null;

                let containerStyle = tabletopBg ? {
                    backgroundImage: "url('" + tabletopBg + "')",
                    backgroundSize: "cover",
                    backgroundPosition: "center"
                } : {};

                return m("div", { class: "cg2-game-container", style: containerStyle }, [
                    m("div", { class: "cg2-game-header" }, [
                        m("button", {
                            class: "cg2-btn",
                            onclick() {
                                gameCharSelection = null;
                                screen = "deckView";
                                m.redraw();
                            }
                        }, "\u2190 Back"),
                        m("span", { class: "cg2-game-title" }, "Select Your Character")
                    ]),

                    m("div", { class: "cg2-char-select-container" }, [
                        m("div", { class: "cg2-phase-panel" }, [
                            m("h2", "Choose Your Character"),
                            m("p", "Select which character you want to play (" + chars.length + " available). The other characters will be your opponents."),

                            // LLM Status Indicator
                            m("div", { class: "cg2-llm-status" }, [
                                !llmStatus.checked
                                    ? m("span", { class: "cg2-llm-checking" }, [
                                        m("span", { class: "material-symbols-outlined cg2-spin" }, "sync"),
                                        " Checking LLM connectivity..."
                                    ])
                                    : llmStatus.available
                                        ? m("span", { class: "cg2-llm-online" }, [
                                            m("span", { class: "material-symbols-outlined" }, "check_circle"),
                                            " LLM Online"
                                        ])
                                        : m("span", { class: "cg2-llm-offline" }, [
                                            m("span", { class: "material-symbols-outlined" }, "error"),
                                            " LLM Offline - AI opponent will use basic strategy"
                                        ])
                            ]),

                            m("p", { style: { marginBottom: "16px" } }, "Click a character to start"),

                            m("div", { class: "cg2-char-select-grid" },
                                chars.map(char => {
                                    let isBest = char === bestChar;
                                    let isWorst = char === worstChar;
                                    return m("div", {
                                        class: "cg2-char-select-wrapper",
                                        async onclick() {
                                            // Click to select and immediately start game
                                            gameCharSelection.selected = char;
                                            gameState = createGameState(viewingDeck, char);
                                            gameCharSelection = null;
                                            if (gameState) {
                                                // Load and apply campaign bonuses
                                                activeCampaign = await campaignStorage.load(viewingDeck.deckName || viewingDeck.storageName);
                                                if (activeCampaign) applyCampaignBonuses(gameState, activeCampaign);
                                                // Initialize LLM components in background (non-blocking)
                                                initializeLLMComponents(gameState, viewingDeck);
                                                // Animation will trigger runInitiativePhase() when complete
                                            }
                                            m.redraw();
                                        }
                                    }, [
                                        // Use CardFace for consistent card styling (card front bg, not portrait)
                                        // noPreview: true prevents image click from opening preview instead of selecting
                                        m(CardFace, { card: char, bgImage: cardFrontBg, noPreview: true }),
                                        // Best character indicator (gold check)
                                        isBest ? m("div", { class: "cg2-char-badge cg2-char-best", title: "Highest total stats" }, [
                                            m("span", { class: "material-symbols-outlined" }, "check_circle")
                                        ]) : null,
                                        // Worst character indicator (red X)
                                        isWorst ? m("div", { class: "cg2-char-badge cg2-char-worst", title: "Lowest total stats" }, [
                                            m("span", { class: "material-symbols-outlined" }, "cancel")
                                        ]) : null
                                    ]);
                                })
                            )
                        ])
                    ])
                ]);
            }
        };
    }

    // ── Game View Component ───────────────────────────────────────────
    function GameView() {
        return {
            oninit() {
                // Check LLM connectivity at game start
                if (!llmStatus.checked) {
                    checkLlmConnectivity();
                }

                if (!gameState && viewingDeck) {
                    // Always let player pick from all characters
                    let allChars = (viewingDeck.cards || []).filter(c => c.type === "character");
                    console.log("[CardGame v2] Game init - found " + allChars.length + " character cards:", allChars.map(c => c.name));

                    if (allChars.length > 0 && !gameCharSelection) {
                        // Show character selection for ALL characters
                        gameCharSelection = { characters: allChars, selected: null };
                    } else if (gameCharSelection?.selected) {
                        // Character selected - start game
                        gameState = createGameState(viewingDeck, gameCharSelection.selected);
                        gameCharSelection = null;
                        if (gameState) {
                            // Load and apply campaign bonuses (async, non-blocking)
                            campaignStorage.load(viewingDeck.deckName).then(c => {
                                activeCampaign = c;
                                if (c) applyCampaignBonuses(gameState, c);
                                m.redraw();
                            });
                            initializeLLMComponents(gameState, viewingDeck);
                            // Animation will trigger runInitiativePhase() when complete
                        }
                    } else if (allChars.length === 0) {
                        console.error("[CardGame v2] No characters in deck");
                    }
                }
            },
            view() {
                // Character selection screen
                if (gameCharSelection && !gameState) {
                    return m(CharacterSelectUI);
                }

                if (!gameState) {
                    return m("div", { class: "cg2-game-error" }, [
                        m("h2", "Game Error"),
                        m("p", "No game state available. Please load a deck first."),
                        m("button", {
                            class: "cg2-btn",
                            onclick() { screen = "deckList"; m.redraw(); }
                        }, "Back to Decks")
                    ]);
                }

                // Game Over is now rendered as an overlay, not a separate screen

                let isPlacement = gameState.phase === GAME_PHASES.DRAW_PLACEMENT;
                let isPlayerTurn = gameState.currentTurn === "player";
                let player = gameState.player;

                // Get tabletop background from deck if available
                let tabletopBg = viewingDeck && viewingDeck.tabletopThumbUrl
                    ? viewingDeck.tabletopThumbUrl.replace(/\/\d+x\d+$/, "/1024x1024")
                    : null;

                let containerStyle = tabletopBg ? {
                    backgroundImage: "url('" + tabletopBg + "')",
                    backgroundSize: "cover",
                    backgroundPosition: "center"
                } : {};

                return m("div", { class: "cg2-game-container", style: containerStyle }, [
                    // Header with inline placement controls
                    m("div", { class: "cg2-game-header" }, [
                        m("button", {
                            class: "cg2-btn cg2-btn-sm",
                            onclick() {
                                page.components.dialog.confirm("Exit game? Progress will be lost.", function(ok) {
                                    if (ok) {
                                        gameState = null;
                                        // Restore deck view image state from viewingDeck
                                        if (viewingDeck) {
                                            backgroundImageId = viewingDeck.backgroundImageId || null;
                                            backgroundPrompt = viewingDeck.backgroundPrompt || null;
                                            backgroundThumbUrl = viewingDeck.backgroundThumbUrl || null;
                                            tabletopImageId = viewingDeck.tabletopImageId || null;
                                            tabletopThumbUrl = viewingDeck.tabletopThumbUrl || null;
                                        }
                                        screen = "deckView";
                                        m.redraw();
                                    }
                                });
                            }
                        }, "\u2190 Exit"),
                        m("span", { class: "cg2-round-badge" }, "R" + gameState.round),

                        // Compact status in header
                        m("div", { class: "cg2-header-status" }, [
                            isPlacement ? m("span", { class: "cg2-header-ap" }, [
                                "AP: ", m("strong", (player.ap - player.apUsed) + "/" + player.ap)
                            ]) : null,
                            m("span", { class: "cg2-header-turn" + (isPlayerTurn && isPlacement ? " cg2-your-turn" : "") },
                                isPlacement ? (isPlayerTurn ? "Your turn" : "Opponent...") : ""
                            )
                        ]),

                        m("span", { class: "cg2-phase-badge" }, formatPhase(gameState.phase))
                    ]),

                    // Main game area
                    m("div", { class: "cg2-game-main" }, [
                        // Left sidebar: Player character
                        m("div", { class: "cg2-game-sidebar cg2-player-side" }, [
                            m(CharacterSidebar, { actor: gameState.player, label: "You" })
                        ]),

                        // Center: Phase UI + Action Bar
                        m("div", { class: "cg2-game-center" }, [
                            // Phase-specific content
                            m("div", { class: "cg2-phase-content" }, [
                                gameState.phase === GAME_PHASES.INITIATIVE ? m(InitiativePhaseUI) : null,
                                // Threat response phases
                                (gameState.phase === GAME_PHASES.THREAT_RESPONSE || gameState.phase === GAME_PHASES.END_THREAT)
                                    ? m(ThreatResponseUI)
                                    : null,
                                // Resolution overlay is now inside ActionBar
                                gameState.phase === GAME_PHASES.CLEANUP ? m(CleanupPhaseUI) : null,
                                // Action Bar (visible in placement and resolution)
                                (isPlacement || gameState.phase === GAME_PHASES.RESOLUTION)
                                    ? m(ActionBar)
                                    : null,

                                // Narrator text overlay
                                gameState.narrationText
                                    ? m("div", { class: "cg2-narration-overlay" }, [
                                        m("div", { class: "cg2-narration-text" }, [
                                            m("span", { class: "material-symbols-outlined cg2-narration-icon" }, "campaign"),
                                            gameState.narrationText
                                        ])
                                    ])
                                    : null,

                                // Talk card chat overlay (Phase 8)
                                gameState.chat?.active ? m(TalkChatUI) : null,

                                // Game Over overlay
                                gameState.winner ? m(GameOverUI) : null
                            ]),

                            // Action Panel (hidden during resolution to avoid duplicate UI)
                            gameState.phase !== GAME_PHASES.RESOLUTION ? m("div", { class: "cg2-action-panel" }, [
                                // Status message - dynamic based on phase and state
                                m("div", { class: "cg2-action-status" },
                                    gameState.phase === GAME_PHASES.INITIATIVE
                                        ? (initAnimState.rollComplete && gameState.initiative.winner
                                            ? [
                                                m("strong", gameState.initiative.winner === "player" ? "You win initiative! " : "Opponent wins initiative! "),
                                                m("span", gameState.initiative.winner === "player"
                                                    ? "You go first (odd positions: 1, 3, 5...)"
                                                    : "Opponent goes first. You get even positions (2, 4, 6...)")
                                            ]
                                            : "Rolling for initiative...")
                                        : (gameState.phase === GAME_PHASES.THREAT_RESPONSE || gameState.phase === GAME_PHASES.END_THREAT)
                                            ? (gameState.threatResponse?.responder === "player"
                                                ? "Threat incoming! Click cards to defend."
                                                : "Opponent is responding to threat...")
                                            : gameState.phase === GAME_PHASES.DRAW_PLACEMENT
                                                ? (isPlayerTurn ? "Place cards on the action bar" : "Opponent is placing...")
                                                : gameState.phase === GAME_PHASES.RESOLUTION
                                                    ? "" // Status shown in resolution overlay above
                                                    : gameState.phase === GAME_PHASES.CLEANUP
                                                        ? "Round complete!"
                                                        : ""
                                ),
                                // Primary action button(s)
                                m("div", { class: "cg2-action-buttons" }, [
                                    // Initiative phase: Continue button
                                    gameState.phase === GAME_PHASES.INITIATIVE && initAnimState.rollComplete
                                        ? m("button", {
                                            class: "cg2-btn cg2-btn-primary cg2-action-btn-lg",
                                            onclick() { advancePhase(); }
                                        }, "Continue")
                                        : null,
                                    // Placement phase: Draw and End Turn buttons
                                    isPlacement && isPlayerTurn ? [
                                        m("button", {
                                            class: "cg2-btn cg2-action-btn-lg",
                                            disabled: player.drawPile.length === 0 || player.apUsed >= player.ap,
                                            title: "Draw a card (costs 1 AP)",
                                            onclick() {
                                                if (player.apUsed < player.ap) {
                                                    drawCardsForActor(player, 1);
                                                    player.apUsed++;
                                                    m.redraw();
                                                }
                                            }
                                        }, [
                                            m("span", { class: "material-symbols-outlined" }, "add_card"),
                                            " Draw"
                                        ]),
                                        m("button", {
                                            class: "cg2-btn cg2-btn-primary cg2-action-btn-lg",
                                            onclick() {
                                                checkPlacementComplete();
                                                if (gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                                                    endTurn();
                                                }
                                            }
                                        }, player.apUsed > 0 ? "End Turn" : "Pass Turn")
                                    ] : null
                                ])
                            ]) : null
                        ]),

                        // Right sidebar: Opponent
                        m("div", { class: "cg2-game-sidebar cg2-opponent-side" }, [
                            m(CharacterSidebar, { actor: gameState.opponent, label: "Opponent", isOpponent: true })
                        ])
                    ]),

                    // Bottom: Hand Tray (visible in initiative, placement, and threat response phases)
                    (gameState.phase === GAME_PHASES.INITIATIVE ||
                     gameState.phase === GAME_PHASES.DRAW_PLACEMENT ||
                     gameState.phase === GAME_PHASES.THREAT_RESPONSE ||
                     gameState.phase === GAME_PHASES.END_THREAT)
                        ? m(HandTray)
                        : null
                ]);
            }
        };
    }

    function formatPhase(phase) {
        let labels = {
            [GAME_PHASES.INITIATIVE]: "Initiative",
            [GAME_PHASES.EQUIP]: "Equip",
            [GAME_PHASES.THREAT_RESPONSE]: "Threat!",
            [GAME_PHASES.DRAW_PLACEMENT]: "Placement",
            [GAME_PHASES.RESOLUTION]: "Resolution",
            [GAME_PHASES.CLEANUP]: "Cleanup",
            [GAME_PHASES.END_THREAT]: "End Threat!",
            "GAME_OVER": "Game Over"
        };
        return labels[phase] || phase;
    }

    // ── Fanned Card Stack Component ─────────────────────────────────────
    // Track which cards are flipped in the stack
    let stackFlipped = {};  // { "player-0": true, "opponent-1": false }

    function FannedCardStack() {
        return {
            view(vnode) {
                let { cards, stackId, label, maxShow } = vnode.attrs;
                maxShow = maxShow || 3;
                let cardList = cards || [];

                // Empty placeholder
                if (cardList.length === 0) {
                    return m("div", { class: "cg2-fanned-stack cg2-fanned-empty" }, [
                        m("div", { class: "cg2-stack-label" }, label || "Empty"),
                        m("div", { class: "cg2-stack-placeholder" }, [
                            m("span", { class: "material-symbols-outlined" }, "add_card")
                        ])
                    ]);
                }

                return m("div", { class: "cg2-fanned-stack" }, [
                    m("div", { class: "cg2-stack-label" }, (label || "Stack") + " (" + cardList.length + ")"),
                    m("div", { class: "cg2-fanned-cards" },
                        cardList.slice(0, maxShow).map((card, i) => {
                            let flipKey = stackId + "-" + i;
                            let isFlipped = stackFlipped[flipKey];
                            let fanAngle = (i - (Math.min(cardList.length, maxShow) - 1) / 2) * 8;
                            let fanOffset = i * 25;

                            return m("div", {
                                key: flipKey,
                                class: "cg2-fanned-card-wrap" + (isFlipped ? " cg2-flipped" : ""),
                                style: {
                                    zIndex: 10 + i,
                                    transform: "translateX(" + fanOffset + "px) rotate(" + fanAngle + "deg)"
                                },
                                onclick(e) {
                                    e.stopPropagation();
                                    // Double-click to flip
                                },
                                ondblclick(e) {
                                    e.stopPropagation();
                                    stackFlipped[flipKey] = !stackFlipped[flipKey];
                                    m.redraw();
                                },
                                title: card.name + (card.effect ? ": " + card.effect : "") + "\n(Double-click to flip)"
                            }, [
                                m("div", { class: "cg2-fanned-card-inner" }, [
                                    // Front face
                                    m("div", { class: "cg2-fanned-face cg2-fanned-front" },
                                        m(CardFace, { card, compact: true })
                                    ),
                                    // Back face
                                    m("div", { class: "cg2-fanned-face cg2-fanned-back" },
                                        m(CardBack, { type: card.type })
                                    )
                                ])
                            ]);
                        }),
                        // Show "+N more" badge if more cards
                        cardList.length > maxShow
                            ? m("div", { class: "cg2-fanned-more" }, "+" + (cardList.length - maxShow))
                            : null
                    )
                ]);
            }
        };
    }

    // ── Character Sidebar Component ───────────────────────────────────
    function CharacterSidebar() {
        return {
            view(vnode) {
                let { actor, label, isOpponent } = vnode.attrs;
                let char = actor.character || {};
                let stackId = isOpponent ? "opponent" : "player";

                // HP/Energy/Morale percentages
                let hpPct = Math.max(0, Math.min(100, (actor.hp / actor.maxHp) * 100));
                let energyPct = Math.max(0, Math.min(100, (actor.energy / actor.maxEnergy) * 100));

                return m("div", { class: "cg2-char-sidebar" + (isOpponent ? " cg2-opponent" : "") }, [
                    m("div", { class: "cg2-sidebar-label" }, label),

                    // Prominent game stats bar
                    m("div", { class: "cg2-sidebar-stats" }, [
                        m("div", { class: "cg2-sidebar-stat cg2-stat-hp" }, [
                            m("span", { class: "cg2-stat-icon" }, "\u2665"),
                            m("div", { class: "cg2-stat-bar" }, [
                                m("div", { class: "cg2-stat-fill cg2-hp-fill", style: { width: hpPct + "%" } })
                            ]),
                            m("span", { class: "cg2-stat-val" }, actor.hp + "/" + actor.maxHp)
                        ]),
                        m("div", { class: "cg2-sidebar-stat cg2-stat-energy" }, [
                            m("span", { class: "cg2-stat-icon" }, "\u26A1"),
                            m("div", { class: "cg2-stat-bar" }, [
                                m("div", { class: "cg2-stat-fill cg2-energy-fill", style: { width: energyPct + "%" } })
                            ]),
                            m("span", { class: "cg2-stat-val" }, actor.energy + "/" + actor.maxEnergy)
                        ])
                    ]),

                    // Character portrait (clickable for popup)
                    m("div", {
                        class: "cg2-sidebar-portrait",
                        onclick() { showCardPreview(char); },
                        title: "Click to view full card"
                    }, [
                        char.portraitUrl
                            ? m("img", { src: char.portraitUrl, class: "cg2-portrait-img-sidebar" })
                            : m("span", { class: "material-symbols-outlined", style: "font-size:48px;color:#B8860B" }, "person"),
                        m("div", { class: "cg2-portrait-name" }, char.name || "Unknown")
                    ]),

                    // Campaign level badge (player only)
                    !isOpponent && activeCampaign ? m("div", { class: "cg2-campaign-badge" }, [
                        m("span", { class: "cg2-campaign-level" }, "Lv." + activeCampaign.level),
                        m("span", { class: "cg2-campaign-xp" }, activeCampaign.xp + " XP"),
                        m("span", { class: "cg2-campaign-record" }, activeCampaign.wins + "W/" + activeCampaign.losses + "L")
                    ]) : null,

                    // AP indicator
                    m("div", { class: "cg2-ap-indicator" }, [
                        m("span", "AP: "),
                        m("span", { class: "cg2-ap-value" }, (actor.ap - actor.apUsed) + "/" + actor.ap)
                    ]),

                    // Compact counts row
                    m("div", { class: "cg2-hand-count" }, [
                        m("span", { title: "Cards in hand" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:12px;vertical-align:middle" }, "playing_cards"),
                            " " + (actor.hand ? actor.hand.length : 0)
                        ]),
                        m("span", { title: "Cards in deck" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:12px;vertical-align:middle" }, "layers"),
                            " " + (actor.drawPile ? actor.drawPile.length : 0)
                        ])
                    ]),

                    // Opponent hand visual (stack of card backs)
                    isOpponent && actor.hand && actor.hand.length > 0
                        ? m("div", { class: "cg2-opp-hand-visual" }, [
                            m("div", { class: "cg2-opp-hand-stack" },
                                // Show up to 5 card backs in a fanned stack
                                Array.from({ length: Math.min(actor.hand.length, 5) }).map((_, i) =>
                                    m("div", {
                                        key: i,
                                        class: "cg2-opp-card-back",
                                        style: {
                                            left: (i * 6) + "px",
                                            top: (i * 2) + "px",
                                            zIndex: i,
                                            transform: "rotate(" + ((i - 2) * 3) + "deg)"
                                        }
                                    })
                                )
                            )
                        ])
                        : null,

                    // Chat button (Silence Rule: locked unless Talk card active)
                    isOpponent ? m("button", {
                        class: "cg2-chat-btn" + (gameState?.chat?.unlocked ? " cg2-chat-unlocked" : " cg2-chat-locked"),
                        disabled: !gameState?.chat?.unlocked,
                        title: gameState?.chat?.unlocked
                            ? "Chat with " + (char.name || "opponent")
                            : "Play a Talk card to speak with your opponent",
                        onclick() {
                            if (gameState?.chat?.unlocked && !gameState?.chat?.active) {
                                // Re-open chat if unlocked but closed
                                gameState.chat.active = true;
                                m.redraw();
                            }
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined" }, "chat"),
                        gameState?.chat?.unlocked ? " Chat" : " Locked"
                    ]) : null,

                    // Status effects display
                    actor.statusEffects && actor.statusEffects.length > 0
                        ? m("div", { class: "cg2-status-effects" },
                            actor.statusEffects.map(effect =>
                                m("div", {
                                    key: effect.id,
                                    class: "cg2-status-effect",
                                    style: { borderColor: effect.color },
                                    title: effect.name + ": " + (STATUS_EFFECTS[effect.id.toUpperCase()]?.description || "") +
                                           (effect.durationType === "turns" ? " (" + effect.turnsRemaining + " turns)" : "")
                                }, [
                                    m("span", {
                                        class: "material-symbols-outlined cg2-status-icon",
                                        style: { color: effect.color }
                                    }, effect.icon),
                                    effect.durationType === "turns"
                                        ? m("span", { class: "cg2-status-turns" }, effect.turnsRemaining)
                                        : null
                                ])
                            )
                        )
                        : null,

                    // Fanned card stack (modifier cards on character)
                    m(FannedCardStack, {
                        cards: actor.cardStack,
                        stackId: stackId + "-mods",
                        label: "Equip",
                        maxShow: 3
                    })
                ]);
            }
        };
    }

    // ── Initiative Phase UI ───────────────────────────────────────────
    function InitiativePhaseUI() {
        let playerFlipped = false;
        let opponentFlipped = false;

        return {
            oninit() {
                // Start animation when component mounts (if not already rolling)
                if (!initAnimState.rolling && !initAnimState.rollComplete && initAnimState.countdown === 3) {
                    startInitiativeAnimation();
                }
            },
            view() {
                let init = gameState.initiative;
                let anim = initAnimState;
                let player = gameState.player;
                let opponent = gameState.opponent;

                // Cards flip when rolling starts or results are shown
                let showFlipped = anim.rolling || anim.rollComplete;

                // Get deck card backgrounds
                let cardFrontBg = viewingDeck && viewingDeck.cardFrontImageUrl ? viewingDeck.cardFrontImageUrl : null;
                let cardBackBg = viewingDeck && viewingDeck.cardBackImageUrl ? viewingDeck.cardBackImageUrl : null;

                // Helper to render initiative card
                function renderInitCard(who, character, roll, isWinner, diceFace, flipped) {
                    let isFlipped = showFlipped || flipped;
                    let isLoser = anim.rollComplete && !isWinner && init.winner;  // Has a winner but this isn't it

                    // Back side style with card back image
                    let backStyle = {};
                    if (cardBackBg) {
                        backStyle.backgroundImage = "url('" + cardBackBg + "')";
                        backStyle.backgroundSize = "cover";
                        backStyle.backgroundPosition = "center";
                    }

                    return m("div", {
                        class: "cg2-init-card-wrap" + (isFlipped ? " cg2-init-flipped" : "") +
                            (isWinner ? " cg2-init-winner-card" : "") +
                            (isLoser ? " cg2-init-loser-card" : ""),
                        onclick() {
                            // Allow manual flip after results
                            if (anim.rollComplete) {
                                if (who === "player") playerFlipped = !playerFlipped;
                                else opponentFlipped = !opponentFlipped;
                            }
                        }
                    }, [
                        m("div", { class: "cg2-init-card-inner" }, [
                            // Front: Character card with card front background
                            m("div", { class: "cg2-init-card-front" }, [
                                m(CardFace, { card: character, bgImage: cardFrontBg, compact: true })
                            ]),
                            // Back: Dice roll result with card back background
                            m("div", { class: "cg2-init-card-back", style: backStyle }, [
                                m("div", { class: "cg2-init-back-content" }, [
                                    m("div", { class: "cg2-init-back-label" }, who === "player" ? "You" : "Opponent"),
                                    m("div", { class: "cg2-dice-container" }, [
                                        m(D20Dice, {
                                            value: anim.rolling ? diceFace : (roll ? roll.raw : "?"),
                                            rolling: anim.rolling,
                                            winner: isWinner && anim.rollComplete
                                        })
                                    ]),
                                    roll && anim.rollComplete ? m("div", { class: "cg2-roll-breakdown" }, [
                                        m("span", roll.raw),
                                        m("span", " + "),
                                        m("span", { class: "cg2-mod" }, roll.modifier),
                                        m("span", " AGI = "),
                                        m("strong", roll.total)
                                    ]) : null,
                                    // Winner/Loser badge for alignment
                                    anim.rollComplete && init.winner
                                        ? (isWinner
                                            ? m("div", { class: "cg2-init-winner-badge" }, [
                                                m("span", { class: "material-symbols-outlined" }, "star"),
                                                " Winner!"
                                            ])
                                            : m("div", { class: "cg2-init-loser-badge" }, "\u2014"))  // em dash as placeholder
                                        : null
                                ])
                            ])
                        ])
                    ]);
                }

                // During countdown phase - show cards face up with countdown overlay
                if (anim.countdown > 0) {
                    return m("div", { class: "cg2-phase-panel cg2-initiative-panel" }, [
                        m("h2", "Initiative Phase"),
                        m("p", "Preparing to roll for turn order..."),
                        m("div", { class: "cg2-init-cards" }, [
                            renderInitCard("player", player.character, null, false, 1, false),
                            m("div", { class: "cg2-init-vs" }, "VS"),
                            renderInitCard("opponent", opponent.character, null, false, 1, false),
                            // Countdown overlay centered on cards
                            m("div", { class: "cg2-init-countdown" }, [
                                m("div", { class: "cg2-countdown-number" }, anim.countdown)
                            ])
                        ])
                    ]);
                }

                // During rolling or showing results
                return m("div", { class: "cg2-phase-panel cg2-initiative-panel" }, [
                    m("h2", "Initiative Phase"),

                    m("div", { class: "cg2-init-cards" }, [
                        renderInitCard("player", player.character, init.playerRoll, init.winner === "player", anim.playerDiceFace, playerFlipped),
                        m("div", { class: "cg2-init-vs" }, "VS"),
                        renderInitCard("opponent", opponent.character, init.opponentRoll, init.winner === "opponent", anim.opponentDiceFace, opponentFlipped)
                    ]),

                    // Nat 1 warning - beginning threat triggered (with roll details)
                    anim.rollComplete && gameState.beginningThreats && gameState.beginningThreats.length > 0
                        ? m("div", { class: "cg2-threat-warning" }, [
                            m("div", { class: "cg2-threat-warning-header" }, [
                                m("span", { class: "material-symbols-outlined" }, "warning"),
                                " CRITICAL FAILURE - THREAT TRIGGERED!"
                            ]),
                            // Show actual roll values for clarity
                            m("div", { class: "cg2-threat-rolls", style: { fontSize: "11px", color: "#c62828", marginBottom: "8px" } }, [
                                "Rolls: You (", init.playerRoll ? init.playerRoll.raw : "?", ") vs Opp (",
                                init.opponentRoll ? init.opponentRoll.raw : "?", ")"
                            ]),
                            gameState.beginningThreats.map((threat, i) =>
                                m("div", { class: "cg2-threat-warning-item" }, [
                                    m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle" }, threat.imageIcon || "pets"),
                                    " ", m("strong", threat.name),
                                    " (Diff ", threat.difficulty, ") attacks ",
                                    m("span", { class: threat.target === "player" ? "cg2-threat-target-you" : "cg2-threat-target-opp" },
                                        threat.target === "player" ? "YOU" : "Opponent"),
                                    " (rolled Nat 1)"
                                ])
                            )
                        ])
                        : null
                ]);
            }
        };
    }

    // ── Threat Response Phase UI ─────────────────────────────────────
    let threatAutoRespondScheduled = false;  // Guard against multiple auto-responds

    function ThreatResponseUI() {
        return {
            oninit() {
                // Reset auto-respond guard when component initializes
                threatAutoRespondScheduled = false;
            },
            view() {
                if (!gameState || !gameState.threatResponse || !gameState.threatResponse.active) {
                    return null;
                }

                let tr = gameState.threatResponse;
                let isEndThreat = tr.type === "end" || gameState.phase === GAME_PHASES.END_THREAT;
                let responder = tr.responder;
                let isPlayerResponder = responder === "player";
                let actor = isPlayerResponder ? gameState.player : gameState.opponent;
                let apRemaining = actor.threatResponseAP || 0;
                let threats = tr.threats || [];
                let defenseStack = tr.defenseStack || [];

                // For AI opponent, auto-respond after delay (only schedule once)
                if (!isPlayerResponder && !threatAutoRespondScheduled) {
                    threatAutoRespondScheduled = true;
                    console.log("[CardGame v2] Scheduling AI threat response");
                    setTimeout(() => {
                        if (gameState && gameState.threatResponse && gameState.threatResponse.active) {
                            // AI places a defensive card if available
                            let defenseCards = actor.hand.filter(c =>
                                c.type === "action" && (c.name === "Block" || c.name === "Dodge") ||
                                c.type === "item" && c.subtype === "armor"
                            );
                            if (defenseCards.length > 0 && actor.threatResponseAP > 0) {
                                placeThreatDefenseCard(defenseCards[0]);
                                m.redraw();
                            } else {
                                skipThreatResponse();
                            }
                        }
                        threatAutoRespondScheduled = false;  // Reset for next threat
                    }, 1500);  // Give player time to see threat
                }

                // Get the threat and defender info
                let threat = threats[0];
                let defenderActor = threat?.target === "player" ? gameState.player : gameState.opponent;
                let defenderName = threat?.target === "player" ? "You" : "Opponent";

                return m("div", { class: "cg2-phase-panel cg2-threat-response-panel" }, [
                    m("h2", isEndThreat ? "End-of-Round Threat!" : "Threat Incoming!"),
                    m("p", { class: "cg2-threat-explain" },
                        isEndThreat
                            ? (isPlayerResponder
                                ? "A threat emerges targeting you! Prepare to defend yourself!"
                                : "A threat emerges targeting the opponent! They must defend...")
                            : (isPlayerResponder
                                ? "Your fumble attracted danger! Prepare to defend yourself!"
                                : "Opponent's fumble attracted danger! They must defend...")
                    ),

                    // Two-card layout like initiative phase
                    m("div", { class: "cg2-init-cards cg2-threat-encounter" }, [
                        // Threat card (left)
                        threat ? m("div", { class: "cg2-init-card-wrap cg2-threat-card-wrap" }, [
                            m("div", { class: "cg2-threat-encounter-card" }, [
                                m("div", { class: "cg2-threat-encounter-icon" }, [
                                    m("span", { class: "material-symbols-outlined" }, threat.imageIcon || "pets")
                                ]),
                                m("div", { class: "cg2-threat-encounter-name" }, threat.name),
                                m("div", { class: "cg2-threat-encounter-stats" }, [
                                    m("div", { class: "cg2-threat-stat-row" }, [
                                        m("span", { class: "material-symbols-outlined" }, "swords"),
                                        m("span", "ATK " + threat.atk)
                                    ]),
                                    m("div", { class: "cg2-threat-stat-row" }, [
                                        m("span", { class: "material-symbols-outlined" }, "shield"),
                                        m("span", "DEF " + threat.def)
                                    ]),
                                    m("div", { class: "cg2-threat-stat-row" }, [
                                        m("span", { class: "material-symbols-outlined" }, "favorite"),
                                        m("span", "HP " + threat.hp)
                                    ])
                                ]),
                                m("div", { class: "cg2-threat-encounter-loot" }, [
                                    m("span", { class: "material-symbols-outlined" }, "inventory_2"),
                                    " ", threat.lootRarity, " Loot"
                                ])
                            ])
                        ]) : null,

                        // VS indicator
                        m("div", { class: "cg2-init-vs cg2-threat-vs" }, [
                            m("span", { class: "material-symbols-outlined" }, "swords"),
                            m("div", "VS")
                        ]),

                        // Defender card (right)
                        m("div", { class: "cg2-init-card-wrap cg2-defender-card-wrap" }, [
                            m("div", { class: "cg2-defender-card" + (isPlayerResponder ? " cg2-defender-you" : "") }, [
                                m("div", { class: "cg2-defender-label" }, defenderName),
                                m("div", { class: "cg2-defender-stats" }, [
                                    m("div", { class: "cg2-defender-stat" }, [
                                        m("span", { class: "material-symbols-outlined" }, "favorite"),
                                        " HP: ", defenderActor?.hp || 0
                                    ]),
                                    m("div", { class: "cg2-defender-stat" }, [
                                        m("span", { class: "material-symbols-outlined" }, "shield"),
                                        " END: ", defenderActor?.character?.stats?.END || 0
                                    ])
                                ]),

                                // Defense stack display
                                m("div", { class: "cg2-defender-stack-area" }, [
                                    m("div", { class: "cg2-defender-stack-label" }, "Defense Stack"),
                                    defenseStack.length > 0
                                        ? m("div", { class: "cg2-defender-stack-cards" },
                                            defenseStack.map(card =>
                                                m("div", { class: "cg2-defender-stack-card" }, card.name)
                                            )
                                        )
                                        : m("div", { class: "cg2-defender-stack-empty" }, "Empty - click cards below")
                                ]),

                                // AP indicator
                                isPlayerResponder ? m("div", { class: "cg2-defender-ap" }, [
                                    m("span", { class: "material-symbols-outlined" }, "bolt"),
                                    " ", apRemaining, " / ", tr.bonusAP, " AP"
                                ]) : null
                            ])
                        ])
                    ]),

                    // Action buttons
                    isPlayerResponder ? m("div", { class: "cg2-threat-actions" }, [
                        apRemaining > 0
                            ? m("button", {
                                class: "cg2-btn cg2-btn-secondary",
                                onclick: skipThreatResponse
                            }, "Skip Defense")
                            : null,
                        m("button", {
                            class: "cg2-btn cg2-btn-primary cg2-btn-threat",
                            onclick: () => {
                                console.log("[CardGame v2] Face Threat clicked, phase:", gameState.phase);
                                if (gameState.phase === GAME_PHASES.THREAT_RESPONSE) {
                                    resolveThreatCombat();
                                } else if (gameState.phase === GAME_PHASES.END_THREAT) {
                                    resolveEndThreatCombat();
                                } else {
                                    console.warn("[CardGame v2] Face Threat: unexpected phase", gameState.phase);
                                    // Try to resolve anyway
                                    if (gameState.threatResponse && gameState.threatResponse.active) {
                                        resolveThreatCombat();
                                    }
                                }
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: "vertical-align: middle; margin-right: 4px" }, "shield"),
                            apRemaining > 0 ? "Face Threat Now" : "Resolve Combat"
                        ])
                    ]) : m("div", { class: "cg2-threat-waiting" }, "Opponent is preparing defense...")
                ]);
            }
        };
    }

    // ── Equip Phase UI ────────────────────────────────────────────────
    function EquipPhaseUI() {
        return {
            view() {
                let player = gameState.player;
                let equipped = player.equipped;

                return m("div", { class: "cg2-phase-panel cg2-equip-panel" }, [
                    m("h2", "Equip Phase"),
                    m("p", "Adjust your equipment before combat. (Free action)"),

                    m("div", { class: "cg2-equip-slots" }, [
                        m(EquipSlot, { slot: "head", label: "Head", item: equipped.head }),
                        m(EquipSlot, { slot: "body", label: "Body", item: equipped.body }),
                        m(EquipSlot, { slot: "handL", label: "Left Hand", item: equipped.handL }),
                        m(EquipSlot, { slot: "handR", label: "Right Hand", item: equipped.handR }),
                        m(EquipSlot, { slot: "feet", label: "Feet", item: equipped.feet }),
                        m(EquipSlot, { slot: "ring", label: "Ring", item: equipped.ring }),
                        m(EquipSlot, { slot: "back", label: "Back", item: equipped.back })
                    ]),

                    m("button", {
                        class: "cg2-btn cg2-btn-primary",
                        style: { marginTop: "16px" },
                        onclick() { advancePhase(); }
                    }, "Continue to Placement Phase")
                ]);
            }
        };
    }

    function EquipSlot() {
        return {
            view(vnode) {
                let { slot, label, item } = vnode.attrs;
                return m("div", { class: "cg2-equip-slot" + (item ? " cg2-slot-filled" : "") }, [
                    m("span", { class: "cg2-slot-label" }, label),
                    item
                        ? m("span", { class: "cg2-slot-item" }, item.name)
                        : m("span", { class: "cg2-slot-empty" }, "Empty")
                ]);
            }
        };
    }

    // ── Resolution Phase UI ───────────────────────────────────────────
    function ResolutionPhaseUI() {
        return {
            view() {
                let bar = gameState.actionBar;
                let currentPos = bar.positions[bar.resolveIndex];
                let card = currentPos?.stack?.coreCard;
                let isAttack = (card && card.name === "Attack") || currentPos?.isThreat;
                let isThreat = currentPos?.isThreat;
                let isRolling = resolutionPhase === "rolling";
                let showResult = resolutionPhase === "result" || resolutionPhase === "done";
                let combat = currentCombatResult;

                // Generate descriptive action label
                let actionLabel = "";
                if (currentPos) {
                    let ownerName = currentPos.owner === "player" ? "Player" : "Opponent";
                    if (isThreat) {
                        actionLabel = (currentPos.threat?.name || "Threat") + " Attacks!";
                    } else if (card) {
                        let actionVerb = card.name === "Attack" ? "Attacks" :
                                        card.name === "Rest" ? "Rests" :
                                        card.name === "Flee" ? "Flees" :
                                        card.name === "Guard" ? "Guards" :
                                        card.name === "Investigate" ? "Investigates" :
                                        card.name === "Craft" ? "Crafts" :
                                        card.name === "Trade" ? "Trades" :
                                        card.type === "talk" ? "Talks" :
                                        card.type === "magic" ? "Casts " + card.name :
                                        "Acts";
                        actionLabel = ownerName + " " + actionVerb + "!";
                    }
                }

                // Return empty if not in resolution
                if (!currentPos) {
                    return m("div", { class: "cg2-resolution-complete" }, "All actions resolved!");
                }

                // Combat overlay (shows over action bar)
                return isAttack && (isRolling || showResult) ? m("div", { class: "cg2-combat-overlay" }, [
                    // Step indicator with descriptive label
                    m("div", { class: "cg2-action-step-label" }, [
                        m("span", { class: "cg2-step-number" }, "Step " + currentPos.index + "/" + bar.positions.length),
                        m("span", { class: "cg2-step-action" }, actionLabel)
                    ]),

                    // Content wrapper for stable dimensions
                    m("div", { class: "cg2-combat-content" }, [
                        // Attacker vs Defender header
                        m("div", { class: "cg2-combat-header" }, [
                            m("span", { class: "cg2-combatant cg2-attacker" },
                                combat ? combat.attackerName : (isThreat ? (currentPos.threat?.name || "Threat") : (currentPos.owner === "player" ? "You" : "Opponent"))),
                            m("span", { class: "cg2-combat-vs" }, "attacks"),
                            m("span", { class: "cg2-combatant cg2-defender" },
                                combat ? combat.defenderName : (isThreat ? (currentPos.target === "player" ? "You" : "Opponent") : (currentPos.owner === "player" ? "Opponent" : "You")))
                        ]),

                        // Dice display with D20 SVG
                        m("div", { class: "cg2-combat-dice-row" }, [
                            // Attack dice
                            m("div", { class: "cg2-combat-roll-card" }, [
                                m("div", { class: "cg2-roll-label" }, "Attack Roll"),
                                m(D20Dice, {
                                    value: isRolling ? resolutionDiceFaces.attack : (combat ? combat.attackRoll.raw : "?"),
                                    rolling: isRolling,
                                    winner: combat && showResult && combat.outcome.damageMultiplier > 0
                                }),
                                combat && showResult ? m("div", { class: "cg2-roll-breakdown" }, [
                                    m("span", combat.attackRoll.raw),
                                    m("span", " + "),
                                    m("span", { class: "cg2-mod" }, combat.attackRoll.strMod + " STR"),
                                    m("span", " + "),
                                    m("span", { class: "cg2-mod cg2-mod-atk" }, combat.attackRoll.atkBonus + " ATK"),
                                    combat.attackRoll.skillMod ? [
                                        m("span", " + "),
                                        m("span", { class: "cg2-mod cg2-mod-skill" }, combat.attackRoll.skillMod + " Skill")
                                    ] : null,
                                    combat.attackRoll.statusMod ? [
                                        m("span", " + "),
                                        m("span", { class: "cg2-mod cg2-mod-status" }, combat.attackRoll.statusMod + " Status")
                                    ] : null,
                                    m("span", " = "),
                                    m("strong", combat.attackRoll.total)
                                ]) : null
                            ]),

                            // VS divider
                            m("div", { class: "cg2-combat-vs-divider" }, "vs"),

                            // Defense dice
                            m("div", { class: "cg2-combat-roll-card" }, [
                                m("div", { class: "cg2-roll-label" }, "Defense Roll"),
                                m(D20Dice, {
                                    value: isRolling ? resolutionDiceFaces.defense : (combat ? combat.defenseRoll.raw : "?"),
                                    rolling: isRolling,
                                    winner: combat && showResult && combat.outcome.damageMultiplier <= 0
                                }),
                                combat && showResult ? m("div", { class: "cg2-roll-breakdown" }, [
                                    m("span", combat.defenseRoll.raw),
                                    m("span", " + "),
                                    m("span", { class: "cg2-mod" }, combat.defenseRoll.endMod + " END"),
                                    m("span", " + "),
                                    m("span", { class: "cg2-mod cg2-mod-def" }, combat.defenseRoll.defBonus + " DEF"),
                                    combat.defenseRoll.parryBonus ? [
                                        m("span", " + "),
                                        m("span", { class: "cg2-mod cg2-mod-parry" }, combat.defenseRoll.parryBonus + " Parry")
                                    ] : null,
                                    combat.defenseRoll.statusMod ? [
                                        m("span", " + "),
                                        m("span", { class: "cg2-mod cg2-mod-status" }, combat.defenseRoll.statusMod + " Status")
                                    ] : null,
                                    m("span", " = "),
                                    m("strong", combat.defenseRoll.total)
                                ]) : null
                            ])
                        ]),

                        // Outcome area - reserved space to prevent jumping
                        m("div", { class: "cg2-outcome-area" }, [
                            combat && showResult ? m("div", { class: "cg2-combat-outcome" }, [
                                m("div", {
                                    class: "cg2-outcome-label cg2-outcome-" +
                                        (combat.outcome.damageMultiplier > 0 ? "hit" :
                                         combat.outcome.damageMultiplier < 0 ? "counter" : "miss")
                                }, [
                                    m("span", { class: "cg2-outcome-text" }, combat.outcome.label),
                                    m("span", { class: "cg2-outcome-diff" },
                                        " (" + (combat.outcome.diff >= 0 ? "+" : "") + combat.outcome.diff + ")")
                                ]),
                                m("div", { class: "cg2-outcome-effect" }, combat.outcome.effect),

                                // Damage display
                                combat.damageResult ? m("div", { class: "cg2-damage-display" }, [
                                    m("span", { class: "cg2-damage-number cg2-damage-dealt" },
                                        "-" + combat.damageResult.finalDamage + " HP"),
                                    m("span", { class: "cg2-damage-target" },
                                        " to " + combat.defenderName)
                                ]) : null,

                                combat.selfDamageResult ? m("div", { class: "cg2-damage-display" }, [
                                    m("span", { class: "cg2-damage-number cg2-damage-self" },
                                        "-" + combat.selfDamageResult.finalDamage + " HP"),
                                    m("span", { class: "cg2-damage-target" },
                                        " to " + combat.attackerName + " (counter!)")
                                ]) : null,

                                // Critical Effects
                                combat.criticalEffects && (combat.criticalEffects.rewardCard || combat.criticalEffects.itemDropped || combat.criticalEffects.attackerStunned)
                                    ? m("div", { class: "cg2-critical-effects" }, [
                                        // Reward card earned
                                        combat.criticalEffects.rewardCard
                                            ? m("div", { class: "cg2-critical-effect cg2-reward-earned" }, [
                                                m("span", { class: "material-symbols-outlined" }, "auto_awesome"),
                                                " Reward: ", m("strong", combat.criticalEffects.rewardCard.name),
                                                m("span", { class: "cg2-reward-rarity cg2-rarity-" + (combat.criticalEffects.rewardCard.rarity || "common") },
                                                    " [" + (combat.criticalEffects.rewardCard.rarity || "common") + "]")
                                            ]) : null,
                                        // Item dropped from opponent
                                        combat.criticalEffects.itemDropped
                                            ? m("div", { class: "cg2-critical-effect cg2-item-dropped" }, [
                                                m("span", { class: "material-symbols-outlined" }, "backpack"),
                                                " Item dropped: ", m("strong", combat.criticalEffects.itemDropped.name)
                                            ]) : null,
                                        // Attacker stunned
                                        combat.criticalEffects.attackerStunned
                                            ? m("div", { class: "cg2-critical-effect cg2-attacker-stunned" }, [
                                                m("span", { class: "material-symbols-outlined" }, "flash_off"),
                                                " ", m("strong", combat.attackerName), " STUNNED!"
                                            ]) : null
                                    ]) : null
                            ]) : m("div", { class: "cg2-outcome-pending" }, [
                                m("span", { class: "material-symbols-outlined cg2-spin" }, "casino"),
                                " Rolling dice..."
                            ])
                        ])
                    ])

                ]) : m("div", { class: "cg2-non-combat-overlay" }, [
                    // Non-combat action display
                    m("div", { class: "cg2-action-step-label" }, [
                        m("span", { class: "cg2-step-number" }, "Step " + currentPos.index + "/" + bar.positions.length),
                        m("span", { class: "cg2-step-action" }, actionLabel)
                    ]),

                    // Non-combat content wrapper
                    m("div", { class: "cg2-combat-content" }, [
                        m("div", { class: "cg2-resolving-action" }, [
                            m("span", { class: "material-symbols-outlined cg2-spin" }, "sync"),
                            " Resolving ", card ? card.name : "action", "..."
                        ]),
                        m("div", { class: "cg2-resolution-progress" },
                            resolutionAnimating ? "Resolving..." : "Next action in 3s...")
                    ])
                ]);
            }
        };
    }

    // ── Cleanup Phase UI ──────────────────────────────────────────────
    function CleanupPhaseUI() {
        return {
            oninit() {
                // Determine round winner and apply HP recovery
                if (!gameState.cleanupApplied) {
                    let playerPts = gameState.player.roundPoints;
                    let oppPts = gameState.opponent.roundPoints;

                    if (playerPts > oppPts) {
                        gameState.roundWinner = "player";
                        gameState.player.hpRecovery = 5;
                        gameState.opponent.hpRecovery = 2;
                        gameState.player.energyRecovery = 3;
                        gameState.opponent.energyRecovery = 1;
                    } else if (oppPts > playerPts) {
                        gameState.roundWinner = "opponent";
                        gameState.player.hpRecovery = 2;
                        gameState.opponent.hpRecovery = 5;
                        gameState.player.energyRecovery = 1;
                        gameState.opponent.energyRecovery = 3;
                    } else {
                        // Tie - both get base recovery
                        gameState.roundWinner = "tie";
                        gameState.player.hpRecovery = 2;
                        gameState.opponent.hpRecovery = 2;
                        gameState.player.energyRecovery = 2;
                        gameState.opponent.energyRecovery = 2;
                    }

                    // Apply HP recovery (capped at maxHp)
                    gameState.player.hp = Math.min(gameState.player.maxHp, gameState.player.hp + gameState.player.hpRecovery);
                    gameState.opponent.hp = Math.min(gameState.opponent.maxHp, gameState.opponent.hp + gameState.opponent.hpRecovery);

                    // Apply Energy recovery (capped at maxEnergy)
                    gameState.player.energy = Math.min(gameState.player.maxEnergy, gameState.player.energy + gameState.player.energyRecovery);
                    gameState.opponent.energy = Math.min(gameState.opponent.maxEnergy, gameState.opponent.energy + gameState.opponent.energyRecovery);

                    // Winner claims the pot
                    if (gameState.roundWinner !== "tie") {
                        gameState.potClaimed = gameState.pot.length;
                        claimPot(gameState.roundWinner);
                    } else {
                        // On tie, pot carries over to next round
                        gameState.potClaimed = 0;
                        console.log("[CardGame v2] Tie - pot carries over:", gameState.pot.length, "cards");
                    }

                    // Lethargy check: strip hoarded action cards not played this round
                    gameState.playerLethargy = checkLethargy(gameState.player, "Player");
                    gameState.opponentLethargy = checkLethargy(gameState.opponent, "Opponent");

                    // End threat check: draw scenario card (damage applied in END_THREAT phase)
                    gameState.endThreatResult = checkEndThreat();
                    if (gameState.endThreatResult && gameState.endThreatResult.threat) {
                        // Mark as pending - will be handled in END_THREAT phase
                        gameState.endThreatResult.responded = false;
                        console.log("[CardGame v2] End threat pending:", gameState.endThreatResult.threat.name,
                            "- targets", gameState.endThreatResult.threat.target);
                    }

                    gameState.cleanupApplied = true;
                    console.log("[CardGame v2] Cleanup - Round winner:", gameState.roundWinner,
                        "| Player HP:", gameState.player.hp, "(+" + gameState.player.hpRecovery + ")",
                        "Energy:", gameState.player.energy, "(+" + gameState.player.energyRecovery + ")",
                        "| Opponent HP:", gameState.opponent.hp, "(+" + gameState.opponent.hpRecovery + ")",
                        "Energy:", gameState.opponent.energy, "(+" + gameState.opponent.energyRecovery + ")");

                    // Trigger round end narration (non-blocking)
                    narrateRoundEnd(gameState.roundWinner);

                    // Auto-save game state after each round
                    gameStorage.save(gameState.deckName, gameState).then(saved => {
                        if (saved) console.log("[CardGame v2] Auto-saved after round", gameState.round);
                    }).catch(e => console.warn("[CardGame v2] Auto-save failed:", e));
                }
            },
            view() {
                let winnerText = gameState.roundWinner === "player" ? "You win the round!" :
                                 gameState.roundWinner === "opponent" ? "Opponent wins the round!" :
                                 "Round is a tie!";

                return m("div", { class: "cg2-phase-panel cg2-cleanup-panel" }, [
                    m("h2", "Cleanup Phase"),
                    m("p", "Round " + gameState.round + " complete!"),

                    m("div", { class: "cg2-round-summary" }, [
                        m("div", { class: "cg2-round-winner " + (gameState.roundWinner === "player" ? "cg2-win" : gameState.roundWinner === "opponent" ? "cg2-lose" : "") }, winnerText),
                        m("div", { class: "cg2-round-points" }, [
                            m("span", "Your points: " + gameState.player.roundPoints),
                            m("span", " | "),
                            m("span", "Opponent: " + gameState.opponent.roundPoints)
                        ]),
                        m("div", { class: "cg2-round-recovery" }, [
                            m("div", { class: "cg2-recovery-row" }, [
                                m("span", { class: "cg2-recovery-label" }, "HP:"),
                                m("span", { class: gameState.roundWinner === "player" ? "cg2-winner" : "" },
                                    "You +" + gameState.player.hpRecovery),
                                m("span", " | "),
                                m("span", { class: gameState.roundWinner === "opponent" ? "cg2-winner" : "" },
                                    "Opp +" + gameState.opponent.hpRecovery)
                            ]),
                            m("div", { class: "cg2-recovery-row" }, [
                                m("span", { class: "cg2-recovery-label" }, "Energy:"),
                                m("span", { class: gameState.roundWinner === "player" ? "cg2-winner" : "" },
                                    "You +" + gameState.player.energyRecovery),
                                m("span", " | "),
                                m("span", { class: gameState.roundWinner === "opponent" ? "cg2-winner" : "" },
                                    "Opp +" + gameState.opponent.energyRecovery)
                            ])
                        ]),
                        // Pot claim info
                        gameState.potClaimed > 0 ? m("div", { class: "cg2-pot-claimed" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" }, "redeem"),
                            " ", gameState.roundWinner === "player" ? "You" : "Opponent",
                            " claimed ", gameState.potClaimed, " card", gameState.potClaimed > 1 ? "s" : "", " from the pot!"
                        ]) : null,
                        gameState.pot.length > 0 ? m("div", { class: "cg2-pot-carries" },
                            "Pot carries over: " + gameState.pot.length + " cards"
                        ) : null,

                        // Lethargy notifications
                        (gameState.playerLethargy && gameState.playerLethargy.length > 0) ||
                        (gameState.opponentLethargy && gameState.opponentLethargy.length > 0)
                            ? m("div", { class: "cg2-lethargy-notice" }, [
                                m("div", { class: "cg2-lethargy-title" }, [
                                    m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" }, "hotel"),
                                    " Lethargy"
                                ]),
                                gameState.playerLethargy && gameState.playerLethargy.length > 0
                                    ? m("div", { class: "cg2-lethargy-item cg2-lethargy-player" },
                                        "You lost " + gameState.playerLethargy.map(l => l.stripped + " " + l.actionType).join(", ") + " (hoarded, unplayed)")
                                    : null,
                                gameState.opponentLethargy && gameState.opponentLethargy.length > 0
                                    ? m("div", { class: "cg2-lethargy-item cg2-lethargy-opponent" },
                                        "Opponent lost " + gameState.opponentLethargy.map(l => l.stripped + " " + l.actionType).join(", ") + " (hoarded, unplayed)")
                                    : null
                            ])
                            : null,

                        // End threat (scenario card)
                        gameState.endThreatResult ? m("div", { class: "cg2-scenario-card" }, [
                            m("div", { class: "cg2-scenario-title" }, [
                                m("span", { class: "material-symbols-outlined", style: "font-size:14px;vertical-align:middle" },
                                    gameState.endThreatResult.threat ? "warning" : "eco"),
                                " Scenario: ", gameState.endThreatResult.scenario.name
                            ]),
                            m("div", { class: "cg2-scenario-desc" }, gameState.endThreatResult.scenario.description),
                            // Show pending threat (not yet resolved)
                            gameState.endThreatResult.threat && !gameState.endThreatResult.responded
                                ? m("div", { class: "cg2-end-threat cg2-end-threat-pending" }, [
                                    m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;color:#c62828" },
                                        gameState.endThreatResult.threat.imageIcon || "pets"),
                                    " ", m("strong", gameState.endThreatResult.threat.name),
                                    " approaches! ",
                                    m("span", { class: gameState.roundWinner === "player" ? "cg2-threat-target-you" : "cg2-threat-target-opp" },
                                        gameState.roundWinner === "player" || gameState.roundWinner === "tie" ? "You" : "Opponent"),
                                    " may prepare a defense."
                                ])
                                : null,
                            // Show resolved threat result (after END_THREAT phase)
                            gameState.endThreatResult.threat && gameState.endThreatResult.responded
                                ? m("div", { class: "cg2-end-threat" }, [
                                    m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;color:#c62828" },
                                        gameState.endThreatResult.threat.imageIcon || "pets"),
                                    " ", m("strong", gameState.endThreatResult.threat.name),
                                    gameState.endThreatResult.damageDealt > 0
                                        ? [" dealt ", m("span", { style: "color:#c62828;font-weight:bold" }, gameState.endThreatResult.damageDealt), " damage!"]
                                        : [" was ", m("span", { style: "color:#4CAF50;font-weight:bold" }, "defeated"), "!"]
                                ]) : null
                        ]) : null
                    ]),

                    // Button changes based on pending threat
                    gameState.endThreatResult && gameState.endThreatResult.threat && !gameState.endThreatResult.responded
                        ? m("button", {
                            class: "cg2-btn cg2-btn-primary cg2-btn-threat",
                            onclick() {
                                gameState.cleanupApplied = false;
                                advancePhase();  // Goes to END_THREAT phase
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: "vertical-align:middle;margin-right:4px" }, "shield"),
                            "Face the Threat"
                        ])
                        : m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            onclick() {
                                gameState.cleanupApplied = false;  // Reset for next round
                                advancePhase();  // Will call startNextRound since no pending threat
                            }
                        }, "Start Round " + (gameState.round + 1))
                ]);
            }
        };
    }

    // ── Talk Card Chat UI (Phase 8) ───────────────────────────────────
    function TalkChatUI() {
        let inputRef = null;

        async function sendMessage() {
            if (!gameState?.chat?.active || gameState.chat.pending) return;
            const text = gameState.chat.inputText?.trim();
            if (!text) return;

            // Add player message
            gameState.chat.messages.push({
                role: "player",
                text: text,
                timestamp: Date.now()
            });
            gameState.chat.inputText = "";
            gameState.chat.pending = true;
            m.redraw();

            // Get NPC response
            if (gameChatManager?.initialized) {
                try {
                    const response = await gameChatManager.sendMessage(text);
                    if (response && !response.error) {
                        gameState.chat.messages.push({
                            role: "npc",
                            text: response.text,
                            speaker: response.speaker,
                            timestamp: Date.now()
                        });
                        // Speak with opponent voice if available
                        if (gameVoice?.enabled && !gameVoice.subtitlesOnly && response.text) {
                            gameVoice.speak(response.text);
                        }
                    } else {
                        // LLM returned error
                        gameState.chat.messages.push({
                            role: "npc",
                            text: "*looks at you thoughtfully but says nothing*",
                            timestamp: Date.now()
                        });
                    }
                } catch (err) {
                    console.warn("[TalkChatUI] Chat failed:", err);
                    gameState.chat.messages.push({
                        role: "npc",
                        text: "*pauses, distracted*",
                        timestamp: Date.now()
                    });
                }
            } else {
                // Fallback: generate contextual responses based on NPC and player input
                const npcName = gameState.chat.npcName || "Opponent";
                const fallbackResponses = [
                    `*${npcName} considers your words carefully*`,
                    `*${npcName} narrows their eyes at you*`,
                    `"Hmm..." *${npcName} seems unimpressed*`,
                    `*${npcName} tilts their head, listening*`,
                    `"We shall see..." *${npcName} mutters*`
                ];
                const randomResponse = fallbackResponses[Math.floor(Math.random() * fallbackResponses.length)];
                gameState.chat.messages.push({
                    role: "npc",
                    text: randomResponse,
                    timestamp: Date.now()
                });
                console.log("[TalkChatUI] Using fallback response - LLM not initialized. Reason:", gameChatManager?.lastError || "gameChatManager is null");
            }

            gameState.chat.pending = false;
            m.redraw();

            // Auto-scroll to bottom
            setTimeout(() => {
                const msgArea = document.querySelector(".cg2-chat-messages");
                if (msgArea) msgArea.scrollTop = msgArea.scrollHeight;
            }, 50);
        }

        function endChat() {
            if (!gameState?.chat?.active) return;

            // Conclude conversation
            if (gameChatManager?.initialized) {
                gameChatManager.concludeConversation();
            }

            // Calculate morale effect based on conversation
            const msgCount = gameState.chat.messages.length;
            const playerMsgs = gameState.chat.messages.filter(m => m.role === "player").length;

            // Apply morale effects based on conversation quality
            if (msgCount >= 4 && playerMsgs >= 2) {
                // Good conversation: player gains morale, opponent loses some
                gameState.player.morale = Math.min(gameState.player.maxMorale, gameState.player.morale + 2);
                gameState.opponent.morale = Math.max(0, gameState.opponent.morale - 1);
                page.toast("info", "Productive conversation! +2 morale");
            } else if (msgCount >= 2) {
                // Brief exchange
                gameState.player.morale = Math.min(gameState.player.maxMorale, gameState.player.morale + 1);
                page.toast("info", "Brief exchange. +1 morale");
            }

            // Mark Talk card position as resolved
            if (gameState.chat.talkPosition !== null) {
                const pos = gameState.actionBar.positions[gameState.chat.talkPosition];
                if (pos) pos.resolved = true;
            }

            // Close chat (Silence Rule: lock chat again)
            gameState.chat.active = false;
            gameState.chat.unlocked = false;  // Silence Rule: chat locked until next Talk card
            gameState.chat.messages = [];
            gameState.chat.talkCard = null;
            gameState.chat.talkPosition = null;

            console.log("[CardGame v2] Chat ended (chat locked)");
            m.redraw();

            // Continue resolution after chat ends
            if (gameState.phase === GAME_PHASES.RESOLUTION) {
                setTimeout(() => advanceResolution(), 500);
            }
        }

        return {
            view() {
                if (!gameState?.chat?.active) return null;

                const npcName = gameState.chat.npcName || "Opponent";
                const npcPortrait = gameState.opponent?.character?.portraitUrl;

                return m("div", { class: "cg2-chat-overlay" }, [
                    m("div", { class: "cg2-chat-panel" }, [
                        // Header
                        m("div", { class: "cg2-chat-header" }, [
                            npcPortrait
                                ? m("img", { class: "cg2-chat-portrait", src: npcPortrait, alt: npcName })
                                : m("span", { class: "material-symbols-outlined cg2-chat-portrait-icon" }, "person"),
                            m("span", { class: "cg2-chat-npc-name" }, npcName),
                            m("button", {
                                class: "cg2-btn cg2-btn-sm",
                                onclick: endChat,
                                title: "End conversation"
                            }, [
                                m("span", { class: "material-symbols-outlined" }, "close")
                            ])
                        ]),

                        // Messages
                        m("div", { class: "cg2-chat-messages" },
                            gameState.chat.messages.length === 0
                                ? m("div", { class: "cg2-chat-hint" }, `Start a conversation with ${npcName}...`)
                                : gameState.chat.messages.map(msg =>
                                    m("div", {
                                        class: "cg2-chat-message cg2-chat-" + msg.role
                                    }, [
                                        m("span", { class: "cg2-chat-speaker" },
                                            msg.role === "player" ? "You" : (msg.speaker || npcName)),
                                        m("span", { class: "cg2-chat-text" }, msg.text)
                                    ])
                                )
                        ),

                        // Input
                        m("div", { class: "cg2-chat-input-area" }, [
                            m("input", {
                                type: "text",
                                class: "cg2-chat-input",
                                placeholder: "Type your message...",
                                value: gameState.chat.inputText || "",
                                disabled: gameState.chat.pending,
                                oninput: e => { gameState.chat.inputText = e.target.value; },
                                onkeydown: e => { if (e.key === "Enter") sendMessage(); },
                                oncreate: v => { inputRef = v.dom; v.dom.focus(); }
                            }),
                            m("button", {
                                class: "cg2-btn cg2-btn-primary cg2-chat-send",
                                onclick: sendMessage,
                                disabled: gameState.chat.pending || !gameState.chat.inputText?.trim()
                            }, gameState.chat.pending
                                ? m("span", { class: "material-symbols-outlined cg2-spin" }, "sync")
                                : m("span", { class: "material-symbols-outlined" }, "send")
                            )
                        ]),

                        // End conversation button
                        m("div", { class: "cg2-chat-actions" }, [
                            m("button", {
                                class: "cg2-btn cg2-btn-secondary",
                                onclick: endChat
                            }, "End Conversation")
                        ])
                    ])
                ]);
            }
        };
    }

    // Open chat for Talk card
    function openTalkChat(talkCard, positionIndex) {
        if (!gameState) return;

        const npcName = gameState.opponent?.character?.name || "Opponent";

        // Initialize chat state (Silence Rule: unlock chat)
        gameState.chat.active = true;
        gameState.chat.unlocked = true;  // Silence Rule: chat now available
        gameState.chat.messages = [];
        gameState.chat.npcName = npcName;
        gameState.chat.inputText = "";
        gameState.chat.pending = false;
        gameState.chat.talkCard = talkCard;
        gameState.chat.talkPosition = positionIndex;

        // Start conversation in chat manager
        if (gameChatManager?.initialized) {
            gameChatManager.startConversation();
        }

        console.log("[CardGame v2] Talk chat opened with:", npcName, "(chat unlocked)");
        m.redraw();
    }

    // ── Game Over UI ──────────────────────────────────────────────────
    function GameOverUI() {
        let narratedEnd = false;
        let campaignSaved = false;

        return {
            async oninit() {
                // Trigger end game narration once
                if (!narratedEnd && gameState && gameState.winner) {
                    narratedEnd = true;
                    narrateGameEnd(gameState.winner);
                }
                // Save campaign progress once
                if (!campaignSaved && gameState && gameState.winner) {
                    campaignSaved = true;
                    let isVictory = gameState.winner === "player";
                    let xpGain = calculateXP(gameState, isVictory);
                    gameState.xpGained = xpGain;
                    activeCampaign = await saveCampaignProgress(gameState, isVictory, xpGain);
                    // Delete game saves on game end
                    gameStorage.deleteAll(gameState.deckName);
                    m.redraw();
                }
            },
            view() {
                // Show level-up dialog if pending
                if (levelUpState) {
                    return m(LevelUpUI);
                }

                let isVictory = gameState.winner === "player";
                let player = gameState.player;
                let opponent = gameState.opponent;
                let rounds = gameState.round;

                // Overlay on top of current game view
                return m("div", { class: "cg2-game-over-overlay" }, [
                    m("div", { class: "cg2-game-over-panel" }, [
                        m("div", {
                            class: "cg2-game-over-title " + (isVictory ? "cg2-victory" : "cg2-defeat")
                        }, isVictory ? "Victory!" : "Defeat"),

                        m("div", { class: "cg2-game-over-subtitle" },
                            isVictory
                                ? opponent.character.name + " has been defeated!"
                                : "You have been defeated by " + opponent.character.name),

                        m("div", { class: "cg2-game-over-stats" }, [
                            m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value" }, rounds),
                                m("div", { class: "cg2-game-over-stat-label" }, "Rounds")
                            ]),
                            m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value" }, player.hp + "/" + player.maxHp),
                                m("div", { class: "cg2-game-over-stat-label" }, "Your HP")
                            ]),
                            m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value" }, opponent.hp + "/" + opponent.maxHp),
                                m("div", { class: "cg2-game-over-stat-label" }, "Opponent HP")
                            ]),
                            gameState.xpGained ? m("div", { class: "cg2-game-over-stat" }, [
                                m("div", { class: "cg2-game-over-stat-value cg2-xp-gain" }, "+" + gameState.xpGained),
                                m("div", { class: "cg2-game-over-stat-label" }, "XP Gained")
                            ]) : null
                        ]),

                        // Campaign progress bar
                        activeCampaign ? m("div", { class: "cg2-game-over-campaign" }, [
                            m("div", { class: "cg2-campaign-progress" }, [
                                m("span", "Level " + activeCampaign.level),
                                m("span", { class: "cg2-campaign-xp-bar" }, [
                                    m("span", { class: "cg2-campaign-xp-fill", style: { width: (activeCampaign.xp % 100) + "%" } }),
                                ]),
                                m("span", activeCampaign.xp + " XP"),
                            ]),
                            m("div", { class: "cg2-campaign-record-line" },
                                activeCampaign.wins + "W / " + activeCampaign.losses + "L (" + activeCampaign.totalGamesPlayed + " games)")
                        ]) : null,

                        m("div", { class: "cg2-game-over-actions" }, [
                            // Level up button (if pending)
                            activeCampaign?.pendingLevelUps > 0 ? m("button", {
                                class: "cg2-btn cg2-btn-accent",
                                onclick() {
                                    levelUpState = {
                                        campaign: activeCampaign,
                                        statsSelected: [],
                                        remaining: 2
                                    };
                                    m.redraw();
                                }
                            }, [
                                m("span", { class: "material-symbols-outlined" }, "upgrade"),
                                " Level Up! (+" + (activeCampaign.pendingLevelUps * 2) + " stats)"
                            ]) : null,
                            m("button", {
                                class: "cg2-btn cg2-btn-primary",
                                async onclick() {
                                    // Restart with same characters
                                    let deck = viewingDeck;
                                    let playerChar = player.character;
                                    gameState = createGameState(deck, playerChar);
                                    if (gameState) {
                                        if (activeCampaign) applyCampaignBonuses(gameState, activeCampaign);
                                        initializeLLMComponents(gameState, deck);
                                        resetInitAnimState();
                                        startInitiativeAnimation();
                                    }
                                    m.redraw();
                                }
                            }, "Play Again"),
                            m("button", {
                                class: "cg2-btn",
                                onclick() {
                                    gameState = null;
                                    activeCampaign = null;
                                    // Restore deck view image state from viewingDeck
                                    if (viewingDeck) {
                                        backgroundImageId = viewingDeck.backgroundImageId || null;
                                        backgroundPrompt = viewingDeck.backgroundPrompt || null;
                                        backgroundThumbUrl = viewingDeck.backgroundThumbUrl || null;
                                        tabletopImageId = viewingDeck.tabletopImageId || null;
                                        tabletopThumbUrl = viewingDeck.tabletopThumbUrl || null;
                                    }
                                    screen = "deckView";
                                    m.redraw();
                                }
                            }, "Back to Deck")
                        ])
                    ])
                ]);
            }
        };
    }

    // ── Level Up UI Component ───────────────────────────────────────────
    const LEVEL_UP_STATS = ["STR", "AGI", "END", "INT", "MAG", "CHA"];
    const STAT_DESCRIPTIONS = {
        STR: "Strength — melee damage",
        AGI: "Agility — initiative rolls",
        END: "Endurance — action points",
        INT: "Intelligence — skill effects",
        MAG: "Magic — energy pool & spells",
        CHA: "Charisma — talk & morale"
    };

    function LevelUpUI() {
        return {
            view() {
                if (!levelUpState) return null;
                let { campaign, statsSelected, remaining } = levelUpState;
                let playerStats = gameState?.player?.character?.stats || {};

                return m("div", { class: "cg2-levelup-overlay" }, [
                    m("div", { class: "cg2-levelup-panel" }, [
                        m("h2", { class: "cg2-levelup-title" }, "Level Up!"),
                        m("p", { class: "cg2-levelup-subtitle" },
                            "Level " + campaign.level + " reached! Choose " + remaining + " stat" + (remaining > 1 ? "s" : "") + " to increase."),

                        m("div", { class: "cg2-levelup-stats" },
                            LEVEL_UP_STATS.map(stat => {
                                let currentVal = playerStats[stat] || 0;
                                let gained = (campaign.statGains?.[stat] || 0);
                                let selectedCount = statsSelected.filter(s => s === stat).length;
                                let isSelected = selectedCount > 0;

                                return m("div", {
                                    class: "cg2-stat-pick" + (isSelected ? " selected" : ""),
                                    onclick() {
                                        if (remaining > 0) {
                                            statsSelected.push(stat);
                                            levelUpState.remaining--;
                                        } else if (isSelected) {
                                            // Deselect last instance
                                            let idx = statsSelected.lastIndexOf(stat);
                                            if (idx >= 0) {
                                                statsSelected.splice(idx, 1);
                                                levelUpState.remaining++;
                                            }
                                        }
                                        m.redraw();
                                    }
                                }, [
                                    m("div", { class: "cg2-stat-pick-name" }, stat),
                                    m("div", { class: "cg2-stat-pick-value" }, currentVal + (selectedCount > 0 ? " +" + selectedCount : "")),
                                    m("div", { class: "cg2-stat-pick-desc" }, STAT_DESCRIPTIONS[stat]),
                                    gained > 0 ? m("div", { class: "cg2-stat-pick-gained" }, "Campaign: +" + gained) : null
                                ]);
                            })
                        ),

                        m("div", { class: "cg2-levelup-actions" }, [
                            m("button", {
                                class: "cg2-btn cg2-btn-primary",
                                disabled: remaining > 0,
                                async onclick() {
                                    // Apply stat gains
                                    for (let stat of statsSelected) {
                                        campaign.statGains[stat] = (campaign.statGains[stat] || 0) + 1;
                                    }
                                    campaign.pendingLevelUps = Math.max(0, (campaign.pendingLevelUps || 1) - 1);

                                    // Save updated campaign
                                    await campaignStorage.save(gameState.deckName, campaign);
                                    activeCampaign = campaign;

                                    // Check if more level-ups pending
                                    if (campaign.pendingLevelUps > 0) {
                                        levelUpState = {
                                            campaign,
                                            statsSelected: [],
                                            remaining: 2
                                        };
                                    } else {
                                        levelUpState = null;
                                    }
                                    m.redraw();
                                }
                            }, remaining > 0 ? "Select " + remaining + " more" : "Confirm"),
                            m("button", {
                                class: "cg2-btn",
                                onclick() {
                                    // Reset selections
                                    levelUpState.statsSelected = [];
                                    levelUpState.remaining = 2;
                                    m.redraw();
                                }
                            }, "Reset")
                        ])
                    ])
                ]);
            }
        };
    }

    // ── Action Bar Component ──────────────────────────────────────────
    function ActionBar() {
        return {
            view() {
                let bar = gameState.actionBar;
                let isPlacement = gameState.phase === GAME_PHASES.DRAW_PLACEMENT;
                let isResolution = gameState.phase === GAME_PHASES.RESOLUTION;
                let cardFrontBg = viewingDeck?.cardFrontImageUrl || null;

                return m("div", { class: "cg2-action-bar" }, [
                    m("div", { class: "cg2-action-bar-label" }, "Action Bar"),
                    m("div", { class: "cg2-action-bar-track" },
                        bar.positions.map((pos, i) => {
                            let isActive = isResolution && bar.resolveIndex === i;
                            let isResolved = pos.resolved;
                            let isPlayerPos = pos.owner === "player";
                            let isThreatPos = pos.isThreat;
                            let playerOutOfAP = gameState.player.apUsed >= gameState.player.ap;
                            let canDrop = isPlacement && isPlayerPos && gameState.currentTurn === "player" && !isThreatPos;
                            // Mark empty player positions as locked if out of AP
                            let isLocked = isPlacement && isPlayerPos && !pos.stack && playerOutOfAP && !isThreatPos;

                            return m("div", {
                                key: pos.index,
                                class: "cg2-action-position" +
                                       (isThreatPos ? " cg2-threat-pos" : (isPlayerPos ? " cg2-player-pos" : " cg2-opponent-pos")) +
                                       (isActive ? " cg2-active" : "") +
                                       (isResolved ? " cg2-resolved" : "") +
                                       (isLocked ? " cg2-locked" : "") +
                                       (canDrop && !isLocked ? " cg2-droppable" : ""),
                                ondragover(e) {
                                    if (canDrop && !isLocked) e.preventDefault();
                                },
                                ondrop(e) {
                                    if (!canDrop || isLocked) return;
                                    e.preventDefault();
                                    let cardData = e.dataTransfer.getData("text/plain");
                                    try {
                                        let card = JSON.parse(cardData);
                                        placeCard(pos.index, card);
                                    } catch (err) {
                                        console.error("[CardGame v2] Drop error:", err);
                                    }
                                }
                            }, [
                                m("div", { class: "cg2-pos-number" }, isThreatPos ? "T" + pos.index : pos.index),
                                m("div", { class: "cg2-pos-owner" },
                                    isThreatPos
                                        ? [m("span", { class: "material-symbols-outlined", style: "font-size:12px;vertical-align:middle" }, pos.threat?.imageIcon || "warning"), " Threat"]
                                        : (isPlayerPos ? "You" : "Opp")),

                                // Threat display
                                isThreatPos && pos.threat
                                    ? m("div", { class: "cg2-pos-threat" }, [
                                        m("div", { class: "cg2-threat-name" }, pos.threat.name),
                                        m("div", { class: "cg2-threat-stats" }, [
                                            m("span", { title: "Attack" }, "ATK " + pos.threat.atk),
                                            m("span", { title: "Defense" }, "DEF " + pos.threat.def),
                                            m("span", { title: "Hit Points" }, "HP " + pos.threat.hp)
                                        ]),
                                        m("div", { class: "cg2-threat-target" }, [
                                            "→ ",
                                            m("span", { class: pos.target === "player" ? "cg2-threat-target-you" : "cg2-threat-target-opp" },
                                                pos.target === "player" ? "You" : "Opponent")
                                        ])
                                    ])
                                    : pos.stack && pos.stack.coreCard
                                        ? m("div", { class: "cg2-pos-stack" }, [
                                            // Core card as mini card (on top)
                                            m("div", {
                                                class: "cg2-pos-core-card",
                                                onclick(e) {
                                                    e.stopPropagation();
                                                    showCardPreview(pos.stack.coreCard);
                                                },
                                                title: pos.stack.coreCard.name + (pos.stack.modifiers.length > 0 ? " + " + pos.stack.modifiers.length + " modifier(s)" : "") + " (click to enlarge)"
                                            }, [
                                                m(CardFace, { card: pos.stack.coreCard, bgImage: cardFrontBg, compact: true }),
                                                // Stack count badge
                                                pos.stack.modifiers.length > 0
                                                    ? m("div", { class: "cg2-stack-count" }, "+" + pos.stack.modifiers.length)
                                                    : null,
                                                // Remove button (only during placement phase for player's cards)
                                                canDrop ? m("button", {
                                                    class: "cg2-pos-remove-btn",
                                                    onclick(e) {
                                                        e.stopPropagation();
                                                        removeCardFromPosition(pos.index);
                                                    },
                                                    title: "Remove card (return to hand)"
                                                }, m("span", { class: "material-symbols-outlined" }, "close")) : null
                                            ]),
                                            // Modifier list (shown below for clarity)
                                            pos.stack.modifiers.length > 0
                                                ? m("div", { class: "cg2-pos-modifiers-list" },
                                                    pos.stack.modifiers.map((mod, mi) =>
                                                        m("div", {
                                                            key: mi,
                                                            class: "cg2-pos-mod-item cg2-mod-" + mod.type,
                                                            onclick(e) {
                                                                e.stopPropagation();
                                                                showCardPreview(mod);
                                                            },
                                                            title: mod.name + " (click to enlarge)"
                                                        }, [
                                                            m("span", { class: "material-symbols-outlined", style: "font-size:10px" },
                                                                mod.type === "skill" ? "star" : mod.type === "magic" ? "auto_fix_high" : "category"),
                                                            " " + mod.name
                                                        ])
                                                    )
                                                )
                                                : null
                                        ])
                                        : m("div", { class: "cg2-pos-empty" + (isLocked ? " cg2-pos-locked" : "") },
                                            isLocked ? [m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "lock"), " No AP"]
                                                     : (canDrop ? "Drop here" : "\u2013")),

                                // Resolution marker
                                isActive ? m("div", { class: "cg2-resolve-marker" }, "\u25B6") : null
                            ]);
                        })
                    ),

                    // Combat/Resolution overlay (inside action bar)
                    isResolution ? m(ResolutionPhaseUI) : null
                ]);
            }
        };
    }

    // ── Hand Tray Component ───────────────────────────────────────────
    let handTrayFilter = "all";  // "all" | "action" | "skill" | "magic" | "item"

    function HandTray() {
        return {
            view() {
                let hand = gameState.player.hand || [];
                let filteredHand = handTrayFilter === "all"
                    ? hand
                    : hand.filter(c => c.type === handTrayFilter);

                // Get card front image from deck if available
                let cardFrontBg = viewingDeck && viewingDeck.cardFrontImageUrl
                    ? viewingDeck.cardFrontImageUrl : null;

                return m("div", { class: "cg2-hand-tray" }, [
                    // Header with label and filter tabs
                    m("div", { class: "cg2-hand-header" }, [
                        m("span", { class: "cg2-hand-label" }, [
                            m("span", { class: "material-symbols-outlined", style: "font-size:16px;vertical-align:middle;margin-right:4px" }, "playing_cards"),
                            "Your Hand (" + hand.length + ")"
                        ]),
                        m("div", { class: "cg2-hand-tabs" }, [
                            ["all", "action", "skill", "magic", "talk"].map(f =>
                                m("span", {
                                    class: "cg2-hand-tab" + (handTrayFilter === f ? " cg2-tab-active" : ""),
                                    onclick() { handTrayFilter = f; }
                                }, f.charAt(0).toUpperCase() + f.slice(1))
                            )
                        ])
                    ]),

                    // Cards - using compact card styling with click-to-preview
                    m("div", { class: "cg2-hand-cards" },
                        filteredHand.length > 0
                            ? filteredHand.map((card, i) => {
                                // Check if in threat response mode
                                let isThreatPhase = gameState.phase === GAME_PHASES.THREAT_RESPONSE ||
                                                    gameState.phase === GAME_PHASES.END_THREAT;
                                let isResponder = gameState.threatResponse?.responder === "player";
                                let hasAP = (gameState.player.threatResponseAP || 0) > 0;
                                let canPlaceDefense = isThreatPhase && isResponder && hasAP;

                                return m("div", {
                                    key: card.name + "-" + i,
                                    class: "cg2-hand-card-wrapper" + (canPlaceDefense ? " cg2-defense-eligible" : ""),
                                    draggable: !isThreatPhase,
                                    ondragstart(e) {
                                        if (isThreatPhase) {
                                            e.preventDefault();
                                            return;
                                        }
                                        e.dataTransfer.setData("text/plain", JSON.stringify(card));
                                        e.dataTransfer.effectAllowed = "move";
                                    },
                                    onclick(e) {
                                        e.stopPropagation();
                                        // In threat response phase, clicking adds to defense stack
                                        if (canPlaceDefense) {
                                            placeThreatDefenseCard(card);
                                            return;
                                        }
                                        showCardPreview(card);
                                    },
                                    title: canPlaceDefense ? "Click to add to defense" : "Click to enlarge, drag to place"
                                }, [
                                    m(CardFace, { card, bgImage: cardFrontBg, compact: true }),
                                    // Show art thumbnail if available
                                    card.imageUrl ? m("img", {
                                        src: card.imageUrl,
                                        class: "cg2-card-art-thumb"
                                    }) : null
                                ]);
                            })
                            : m("div", { class: "cg2-hand-empty" }, "No cards of this type")
                    ),
                    // Card Preview Overlay
                    m(CardPreviewOverlay)
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
                // Restore tabletop reference from saved deck
                if (viewingDeck && viewingDeck.tabletopImageId) {
                    tabletopImageId = viewingDeck.tabletopImageId;
                    tabletopThumbUrl = viewingDeck.tabletopThumbUrl || null;
                } else {
                    tabletopImageId = null;
                    tabletopThumbUrl = null;
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
                gameConfigExpanded = false;
                flippedCards = {};
            },
            view() {
                if (!viewingDeck) return m("div", "No deck loaded");
                let allCards = viewingDeck.cards || [];

                // Get unique cards by signature (name + type + subtype)
                let uniqueMap = {};
                allCards.forEach((card, i) => {
                    let sig = card.name + "|" + card.type + "|" + (card.subtype || "");
                    if (!uniqueMap[sig]) {
                        uniqueMap[sig] = { card, index: i, count: 1 };
                    } else {
                        uniqueMap[sig].count++;
                    }
                });
                let uniqueCards = Object.values(uniqueMap);

                let queueActive = artQueue.some(j => j.status === "pending" || j.status === "processing");
                let busy = queueActive || backgroundGenerating || tabletopGenerating || cardFrontGenerating || cardBackGenerating || !!sequenceCardId;
                return m("div", [
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", { class: "cg2-btn", onclick: () => { screen = "deckList"; viewingDeck = null; cancelArtQueue(); artTotal = 0; backgroundImageId = null; backgroundThumbUrl = null; m.redraw(); } }, "\u2190 Back to Decks"),
                        m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, viewingDeck.deckName || "Deck"),
                        viewingDeck.themeId ? m("span", { class: "cg2-deck-theme-badge", style: { marginLeft: "8px" } }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", verticalAlign: "middle", marginRight: "3px" } }, "auto_fix_high"),
                            viewingDeck.themeId.replace(/-/g, " ").replace(/\b\w/g, c => c.toUpperCase())
                        ]) : null,
                        m("span", { style: { color: "#888", fontSize: "12px", marginLeft: "12px" } }, uniqueCards.length + " unique / " + allCards.length + " total"),
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
                        ]),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            style: { fontSize: "11px", marginLeft: "8px" },
                            disabled: busy,
                            title: "Start a new game with this deck",
                            onclick() {
                                gameState = null; // Reset any existing game
                                gameCharSelection = null; // Reset character selection
                                screen = "game";
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "play_arrow"),
                            "Play Game"
                        ]),
                        m("button", {
                            class: "cg2-btn cg2-btn-sm",
                            style: { fontSize: "11px", marginLeft: "4px" },
                            disabled: busy,
                            title: "Run tests using this deck",
                            onclick() {
                                testDeck = viewingDeck;
                                testDeckName = viewingDeck?.deckName || viewingDeck?.storageName || null;
                                screen = "test";
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "science"),
                            "Test"
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
                            ])
                        ]) : null
                    ]),
                    // ── Game Config panel (LLM, Voice) ──
                    m("div", { class: "cg2-sd-panel" }, [
                        m("div", {
                            class: "cg2-sd-panel-header",
                            onclick() {
                                gameConfigExpanded = !gameConfigExpanded;
                                if (gameConfigExpanded && !voiceProfilesLoaded) loadVoiceProfiles();
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", marginRight: "6px", transition: "transform 0.2s", transform: gameConfigExpanded ? "rotate(90deg)" : "" } }, "chevron_right"),
                            m("span", { style: { fontWeight: 600, fontSize: "13px" } }, "Game Config"),
                            (() => {
                                let gc = getDeckGameConfig(viewingDeck);
                                let tags = [];
                                if (gc.narrationEnabled === false) tags.push("LLM off");
                                if (gc.opponentVoiceEnabled) tags.push("opponent voice");
                                if (gc.announcerEnabled) tags.push("announcer" + (gc.announcerVoiceEnabled ? " +voice" : ""));
                                return tags.length > 0 ? m("span", { style: { marginLeft: "8px", fontSize: "11px", color: "#888" } }, tags.join(" · ")) : null;
                            })()
                        ]),
                        gameConfigExpanded ? m("div", { style: { padding: "12px 16px" } }, (() => {
                            let gc = getDeckGameConfig(viewingDeck);
                            let voiceProfilePicker = (label, valueKey, icon) => m("div", { class: "cg2-config-row", style: { paddingLeft: "24px" } }, [
                                m("label", { class: "cg2-config-label" }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, icon || "mic"),
                                    label
                                ]),
                                m("div", { class: "cg2-config-control" }, [
                                    voiceProfiles.length > 0
                                        ? m("select", {
                                            class: "cg2-config-select",
                                            value: gc[valueKey] || "",
                                            onchange(e) { gc[valueKey] = e.target.value || null; m.redraw(); }
                                        }, [
                                            m("option", { value: "" }, "-- Default Voice (Piper) --"),
                                            ...voiceProfiles.map(vp =>
                                                m("option", { value: vp.objectId },
                                                    vp.name || ((vp.engine || "piper") + " \u2014 " + (vp.speaker || "default")))
                                            )
                                        ])
                                        : m("span", { style: { fontSize: "11px", color: "#999" } },
                                            voiceProfilesLoaded ? "No voice profiles found \u2014 default Piper voice will be used" : "Loading voice profiles...")
                                ])
                            ]);

                            return [
                                // ── LLM Narration toggle ──
                                m("div", { class: "cg2-config-row" }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "smart_toy"),
                                        "LLM Patter"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("button", {
                                            class: "cg2-btn cg2-btn-sm" + (gc.narrationEnabled !== false ? " cg2-btn-active" : ""),
                                            onclick() { gc.narrationEnabled = gc.narrationEnabled === false ? true : false; m.redraw(); }
                                        }, gc.narrationEnabled !== false ? "Enabled" : "Disabled"),
                                        m("span", { style: { fontSize: "10px", color: "#999", marginLeft: "8px" } },
                                            gc.narrationEnabled !== false ? "LLM generates dialogue and narration" : "Fallback text used instead of LLM")
                                    ])
                                ]),

                                // ── Section: Opponent Voice ──
                                m("div", { class: "cg2-config-section-label" }, "Opponent Voice"),
                                // Opponent voice enable/disable
                                m("div", { class: "cg2-config-row" }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "record_voice_over"),
                                        "Voice"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("button", {
                                            class: "cg2-btn cg2-btn-sm" + (gc.opponentVoiceEnabled ? " cg2-btn-active" : ""),
                                            onclick() { gc.opponentVoiceEnabled = !gc.opponentVoiceEnabled; m.redraw(); }
                                        }, gc.opponentVoiceEnabled ? "Enabled" : "Disabled"),
                                        m("span", { style: { fontSize: "10px", color: "#999", marginLeft: "8px" } },
                                            gc.opponentVoiceEnabled ? "Opponent speaks via TTS" : "Subtitles only")
                                    ])
                                ]),
                                // Opponent voice profile (shown when enabled)
                                gc.opponentVoiceEnabled ? voiceProfilePicker("Voice Profile", "opponentVoiceProfileId", "person") : null,

                                // ── Section: Announcer ──
                                m("div", { class: "cg2-config-section-label" }, "Announcer"),
                                // Announcer enable/disable
                                m("div", { class: "cg2-config-row" }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "campaign"),
                                        "Announcer"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("button", {
                                            class: "cg2-btn cg2-btn-sm" + (gc.announcerEnabled ? " cg2-btn-active" : ""),
                                            onclick() { gc.announcerEnabled = !gc.announcerEnabled; m.redraw(); }
                                        }, gc.announcerEnabled ? "Enabled" : "Disabled"),
                                        m("span", { style: { fontSize: "10px", color: "#999", marginLeft: "8px" } },
                                            gc.announcerEnabled ? "Play-by-play commentary during game" : "No announcer commentary")
                                    ])
                                ]),
                                // Announcer style (shown when enabled)
                                gc.announcerEnabled ? m("div", { class: "cg2-config-row", style: { paddingLeft: "24px" } }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "style"),
                                        "Style"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("select", {
                                            class: "cg2-config-select",
                                            value: gc.announcerProfile || "arena-announcer",
                                            onchange(e) { gc.announcerProfile = e.target.value; m.redraw(); }
                                        }, Object.entries(CardGameNarrator.PROFILES).map(([id, p]) =>
                                            m("option", { value: id }, p.name)
                                        ))
                                    ])
                                ]) : null,
                                // Announcer voice enable/disable (shown when announcer enabled)
                                gc.announcerEnabled ? m("div", { class: "cg2-config-row", style: { paddingLeft: "24px" } }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "volume_up"),
                                        "Voice"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("button", {
                                            class: "cg2-btn cg2-btn-sm" + (gc.announcerVoiceEnabled ? " cg2-btn-active" : ""),
                                            onclick() { gc.announcerVoiceEnabled = !gc.announcerVoiceEnabled; m.redraw(); }
                                        }, gc.announcerVoiceEnabled ? "Enabled" : "Disabled"),
                                        m("span", { style: { fontSize: "10px", color: "#999", marginLeft: "8px" } },
                                            gc.announcerVoiceEnabled ? "Announcer speaks via TTS" : "Text only")
                                    ])
                                ]) : null,
                                // Announcer voice profile (shown when announcer voice enabled)
                                gc.announcerEnabled && gc.announcerVoiceEnabled ? voiceProfilePicker("Voice Profile", "announcerVoiceProfileId", "mic") : null,

                                // Save button
                                m("div", { style: { marginTop: "12px", textAlign: "right" } }, [
                                    m("button", {
                                        class: "cg2-btn cg2-btn-primary",
                                        style: { fontSize: "11px" },
                                        async onclick() {
                                            viewingDeck.gameConfig = gc;
                                            let safeName = currentDeckSafeName();
                                            if (safeName) {
                                                await deckStorage.save(safeName, viewingDeck);
                                                page.toast("success", "Game config saved");
                                            }
                                        }
                                    }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "save"),
                                        "Save Config"
                                    ])
                                ])
                            ];
                        })()) : null
                    ]),
                    // Background Basis & Tabletop (side by side)
                    m("div", { class: "cg2-bg-row", style: { display: "flex", gap: "12px", margin: "8px 0", flexWrap: "wrap" } }, [
                        // Background Basis (for card art)
                        m("div", { class: "cg2-bg-panel", style: { flex: "1 1 200px" } }, [
                            m("div", { class: "cg2-bg-panel-left" }, [
                                m("span", { style: { fontWeight: 700, fontSize: "12px" } }, "Card Art Basis"),
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
                                    backgroundGenerating ? "..." : (backgroundImageId ? "Regen" : "Gen")
                                ]),
                                backgroundImageId ? m("button", {
                                    class: "cg2-btn",
                                    disabled: busy,
                                    onclick() { backgroundImageId = null; backgroundThumbUrl = null; m.redraw(); }
                                }, "\u00d7") : null
                            ])
                        ]),
                        // Tabletop Surface (for game play area)
                        m("div", { class: "cg2-bg-panel", style: { flex: "1 1 200px" } }, [
                            m("div", { class: "cg2-bg-panel-left" }, [
                                m("span", { style: { fontWeight: 700, fontSize: "12px" } }, "Tabletop"),
                                tabletopThumbUrl ? m("img", {
                                    src: tabletopThumbUrl, class: "cg2-bg-thumb",
                                    style: { cursor: "pointer" },
                                    onclick() { showImagePreview(tabletopThumbUrl); }
                                }) : null
                            ]),
                            m("div", { class: "cg2-bg-panel-right" }, [
                                m("button", {
                                    class: "cg2-btn",
                                    disabled: busy,
                                    onclick() { generateTabletop(activeTheme); }
                                }, [
                                    tabletopGenerating
                                        ? m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "progress_activity")
                                        : m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "table_restaurant"),
                                    tabletopGenerating ? "..." : (tabletopImageId ? "Regen" : "Gen")
                                ]),
                                tabletopImageId ? m("button", {
                                    class: "cg2-btn",
                                    disabled: busy,
                                    onclick() { tabletopImageId = null; tabletopThumbUrl = null; m.redraw(); }
                                }, "\u00d7") : null
                            ])
                        ])
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
                            title: "Clear all existing art and regenerate every card (including front/back)",
                            onclick() {
                                // Clear all card art
                                cards.forEach(c => { delete c.imageUrl; delete c.artObjectId; delete c.portraitUrl; });
                                // Clear front/back template art
                                cardFrontImageUrl = null;
                                cardBackImageUrl = null;
                                // Clear background and tabletop
                                backgroundImageId = null;
                                backgroundThumbUrl = null;
                                tabletopImageId = null;
                                tabletopThumbUrl = null;
                                if (viewingDeck) {
                                    delete viewingDeck.cardFrontImageUrl;
                                    delete viewingDeck.cardBackImageUrl;
                                    delete viewingDeck.backgroundImageId;
                                    delete viewingDeck.backgroundThumbUrl;
                                    delete viewingDeck.tabletopImageId;
                                    delete viewingDeck.tabletopThumbUrl;
                                }
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
                            }, cardFrontImageUrl ? null : [
                                // Only show placeholder content when no image
                                m("div", { class: "cg2-back-pattern" }),
                                m("span", { class: "material-symbols-outlined cg2-back-icon" }, "style"),
                                m("div", { class: "cg2-back-label" }, "CARD FRONT")
                            ]),
                            // Generation status overlay
                            cardFrontGenerating ? m("div", { class: "cg2-card-art-overlay" }, [
                                m("span", { class: "material-symbols-outlined cg2-spin" }, "progress_activity"),
                                m("div", "Generating...")
                            ]) : null,
                            !busy && !cardFrontGenerating ? m("div", { class: "cg2-card-actions" }, [
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
                            }, cardBackImageUrl ? null : [
                                // Only show placeholder content when no image
                                m("div", { class: "cg2-back-pattern" }),
                                m("span", { class: "material-symbols-outlined cg2-back-icon" }, CARD_TYPES.item.icon),
                                m("div", { class: "cg2-back-label" }, "CARD BACK")
                            ]),
                            // Generation status overlay
                            cardBackGenerating ? m("div", { class: "cg2-card-art-overlay" }, [
                                m("span", { class: "material-symbols-outlined cg2-spin" }, "progress_activity"),
                                m("div", "Generating...")
                            ]) : null,
                            !busy && !cardBackGenerating ? m("div", { class: "cg2-card-actions" }, [
                                m("button", {
                                    class: "cg2-card-action-btn",
                                    title: "Generate card back background",
                                    onclick(e) { e.stopPropagation(); generateTemplateArt("back"); }
                                }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "auto_awesome"))
                            ]) : null
                        ]),
                        // Card faces (unique cards only)
                        ...uniqueCards.map(({ card, index: i, count }) => {
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
                                // Card count badge (if duplicates exist)
                                count > 1 ? m("div", { class: "cg2-card-count-badge" }, "x" + count) : null,
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

    // ═══════════════════════════════════════════════════════════════════
    // Phase 9: Comprehensive Test Mode
    // ═══════════════════════════════════════════════════════════════════

    const TEST_CATEGORIES = {
        storage: { label: "Storage", icon: "database" },
        narration: { label: "Narration", icon: "campaign" },
        combat: { label: "Combat", icon: "swords" },
        cards: { label: "Card Eval", icon: "playing_cards" },
        campaign: { label: "Campaign", icon: "military_tech" },
        llm: { label: "LLM", icon: "smart_toy" },
        voice: { label: "Voice", icon: "record_voice_over" },
        playthrough: { label: "Playthrough", icon: "sports_esports" }
    };

    let testDeck = null;   // Deck selected for testing (set from DeckView)
    let testDeckName = null;

    let testState = {
        running: false,
        logs: [],       // [{ time, category, message, status: "info"|"pass"|"fail"|"warn" }]
        results: { pass: 0, fail: 0, warn: 0, skip: 0 },
        currentTest: null,
        completed: false,
        selectedCategories: Object.keys(TEST_CATEGORIES),
        autoPlaySpeed: 500  // ms between auto-play actions
    };

    function testLog(category, message, status = "info") {
        let entry = { time: new Date().toISOString().substring(11, 19), category, message, status };
        testState.logs.push(entry);
        if (status === "pass") testState.results.pass++;
        else if (status === "fail") testState.results.fail++;
        else if (status === "warn") testState.results.warn++;
        console.log(`[TestMode] [${category}] [${status}] ${message}`);
        m.redraw();
    }

    async function runTestSuite() {
        testState.running = true;
        testState.logs = [];
        testState.results = { pass: 0, fail: 0, warn: 0, skip: 0 };
        testState.completed = false;
        m.redraw();

        let cats = testState.selectedCategories;

        // Resolve the deck to use for testing
        // Prefer testDeck (set from DeckView), otherwise load first available deck
        let resolvedDeck = testDeck;
        let resolvedDeckName = testDeckName;
        if (!resolvedDeck) {
            let deckNames = await deckStorage.list();
            if (deckNames.length > 0) {
                resolvedDeckName = deckNames[0];
                resolvedDeck = await deckStorage.load(resolvedDeckName);
            }
        }
        if (resolvedDeck) {
            testLog("", "Using deck: " + (resolvedDeck.deckName || resolvedDeckName), "info");
            // Ensure the deck's theme is loaded so card pool analysis works
            if (resolvedDeck.themeId && (!activeTheme || activeTheme.themeId !== resolvedDeck.themeId)) {
                await loadThemeConfig(resolvedDeck.themeId);
                testLog("", "Loaded theme: " + (activeTheme?.name || resolvedDeck.themeId), "info");
            }
        } else {
            testLog("", "No deck available for testing — some tests will be skipped", "warn");
        }

        // ── Storage Tests ────────────────────
        if (cats.includes("storage")) {
            testState.currentTest = "Storage: CRUD operations";
            testLog("storage", "Testing storage CRUD...");

            try {
                // Test encodeJson/decodeJson
                let testData = { test: true, value: 42, nested: { arr: [1, 2, 3] } };
                let encoded = encodeJson(testData);
                let decoded = decodeJson(encoded);
                if (decoded.test === true && decoded.value === 42 && decoded.nested.arr.length === 3) {
                    testLog("storage", "encodeJson/decodeJson roundtrip", "pass");
                } else {
                    testLog("storage", "encodeJson/decodeJson roundtrip failed", "fail");
                }

                // Test deck list
                let deckNames = await deckStorage.list();
                testLog("storage", "deckStorage.list() returned " + deckNames.length + " decks", "pass");

                // Test gameStorage.list for each deck
                for (let name of deckNames.slice(0, 3)) {
                    let saves = await gameStorage.list(name);
                    testLog("storage", "gameStorage.list('" + name + "') = " + saves.length + " saves", "pass");
                }

                // Test campaignStorage for each deck
                for (let name of deckNames.slice(0, 3)) {
                    let campaign = await campaignStorage.load(name);
                    testLog("storage", "campaignStorage.load('" + name + "'): " + (campaign ? "Level " + campaign.level : "none"), "pass");
                }
            } catch (e) {
                testLog("storage", "Storage test error: " + e.message, "fail");
            }
        }

        // ── Narration Tests ──────────────────
        if (cats.includes("narration")) {
            testState.currentTest = "Narration: triggers and display";
            testLog("narration", "Testing narration system...");

            // Test showNarrationSubtitle
            let testText = "[TEST] Narration subtitle test at " + Date.now();
            try {
                // Create temporary gameState for testing
                let savedGs = gameState;
                gameState = { narrationText: null, narrationTime: null, player: { character: { name: "TestHero" } }, opponent: { character: { name: "TestVillain" } }, round: 1 };
                showNarrationSubtitle(testText);
                if (gameState.narrationText === testText) {
                    testLog("narration", "showNarrationSubtitle sets narrationText", "pass");
                } else {
                    testLog("narration", "showNarrationSubtitle did NOT set narrationText", "fail");
                }
                // Timer is async and can't be verified synchronously — log as info
                testLog("narration", "Auto-hide timer scheduled (8s, cannot verify synchronously)", "info");
                gameState.narrationText = null;
                gameState = savedGs;
            } catch (e) {
                testLog("narration", "Narration test error: " + e.message, "fail");
            }

            // Test triggerNarration fallback text
            try {
                let savedGs = gameState;
                let savedNarrator = gameNarrator;
                gameNarrator = null; // Force fallback path
                gameState = { narrationText: null, narrationTime: null, player: { character: { name: "Hero" }, hp: 15, energy: 10 }, opponent: { character: { name: "Villain" }, hp: 12, energy: 8 }, round: 3 };
                await triggerNarration("game_start");
                if (gameState.narrationText && gameState.narrationText.includes("Hero")) {
                    testLog("narration", "game_start fallback text includes player name", "pass");
                } else {
                    testLog("narration", "game_start fallback text missing", "fail");
                }
                gameState.narrationText = null;
                await triggerNarration("round_start");
                if (gameState.narrationText && gameState.narrationText.includes("Round 3")) {
                    testLog("narration", "round_start fallback for round 3", "pass");
                } else {
                    testLog("narration", "round_start fallback missing/wrong", gameState.round <= 1 ? "pass" : "fail");
                }
                gameState.narrationText = null;
                await triggerNarration("resolution", { isPlayerAttack: true, outcome: "CRIT", damage: 15 });
                if (gameState.narrationText && gameState.narrationText.includes("Critical")) {
                    testLog("narration", "resolution CRIT fallback text", "pass");
                } else {
                    testLog("narration", "resolution CRIT fallback missing", "fail");
                }
                gameState = savedGs;
                gameNarrator = savedNarrator;
            } catch (e) {
                testLog("narration", "Narration fallback test error: " + e.message, "fail");
            }
        }

        // ── Combat Tests ─────────────────────
        if (cats.includes("combat")) {
            testState.currentTest = "Combat: dice, damage, game over";
            testLog("combat", "Testing combat mechanics...");

            // Test rollD20
            let rolls = [];
            for (let i = 0; i < 100; i++) rolls.push(rollD20());
            let minRoll = Math.min(...rolls);
            let maxRoll = Math.max(...rolls);
            if (minRoll >= 1 && maxRoll <= 20) {
                testLog("combat", "rollD20: range [" + minRoll + "," + maxRoll + "] in 100 rolls", "pass");
            } else {
                testLog("combat", "rollD20: out of range [" + minRoll + "," + maxRoll + "]", "fail");
            }

            // Test checkGameOver
            let savedGs = gameState;
            gameState = { player: { hp: 5 }, opponent: { hp: 10 } };
            if (checkGameOver() === null) testLog("combat", "checkGameOver: no winner when both alive", "pass");
            else testLog("combat", "checkGameOver: false positive", "fail");

            gameState.player.hp = 0;
            if (checkGameOver() === "opponent") testLog("combat", "checkGameOver: opponent wins when player HP=0", "pass");
            else testLog("combat", "checkGameOver: wrong winner for HP=0", "fail");

            gameState.player.hp = 5;
            gameState.opponent.hp = -1;
            if (checkGameOver() === "player") testLog("combat", "checkGameOver: player wins when opponent HP<0", "pass");
            else testLog("combat", "checkGameOver: wrong winner for opponent HP<0", "fail");
            gameState = savedGs;

            // Test getActorATK / getActorDEF
            let testActor = { cardStack: [{ atk: 3 }, { def: 2 }, { atk: 1, def: 1 }] };
            let totalAtk = getActorATK(testActor);
            let totalDef = getActorDEF(testActor);
            if (totalAtk === 4) testLog("combat", "getActorATK: 3+1=" + totalAtk, "pass");
            else testLog("combat", "getActorATK: expected 4, got " + totalAtk, "fail");
            if (totalDef === 3) testLog("combat", "getActorDEF: 2+1=" + totalDef, "pass");
            else testLog("combat", "getActorDEF: expected 3, got " + totalDef, "fail");

            // Test getStackSkillMod
            let testStack = { modifiers: [
                { type: "skill", modifier: "+2 to Attack rolls" },
                { type: "skill", modifier: "+3 to defense" }
            ] };
            let atkMod = getStackSkillMod(testStack, "attack");
            let defMod = getStackSkillMod(testStack, "defense");
            if (atkMod === 2) testLog("combat", "getStackSkillMod(attack): +2", "pass");
            else testLog("combat", "getStackSkillMod(attack): expected 2, got " + atkMod, "fail");
            if (defMod === 3) testLog("combat", "getStackSkillMod(defense): +3", "pass");
            else testLog("combat", "getStackSkillMod(defense): expected 3, got " + defMod, "fail");
        }

        // ── Card Evaluation Tests ────────────
        if (cats.includes("cards")) {
            testState.currentTest = "Card Eval: unified effect parser";
            testLog("cards", "Testing unified parseEffect() engine...");

            // Test all supported effect patterns
            let testEffects = [
                { effect: "Deal 15 fire damage",            check: p => p.damage === 15 },
                { effect: "Restore 30 HP",                  check: p => p.healHp === 30 },
                { effect: "Restore 25 Energy",              check: p => p.restoreEnergy === 25 },
                { effect: "Restore 10 Morale",              check: p => p.restoreMorale === 10 },
                { effect: "Heal 8",                         check: p => p.healHp === 8 },
                { effect: "Drain 12 health",                check: p => p.damage === 12 && p.healHp === 12 },
                { effect: "Draw 2 cards",                   check: p => p.draw === 2 },
                { effect: "Stun target for 1 turn",         check: p => p.statusEffects?.some(s => s.id === "stunned") },
                { effect: "Poison enemy for 3 turns",       check: p => p.statusEffects?.some(s => s.id === "poisoned") },
                { effect: "Shield self",                    check: p => p.statusEffects?.some(s => s.id === "shielded" && s.target === "self") },
                { effect: "Enrage for bonus damage",        check: p => p.statusEffects?.some(s => s.id === "enraged") },
                { effect: "Burn the enemy with fire",       check: p => p.statusEffects?.some(s => s.id === "burning") },
                { effect: "Cause bleeding wounds",          check: p => p.statusEffects?.some(s => s.id === "bleeding") },
                { effect: "Weaken enemy defenses",          check: p => p.statusEffects?.some(s => s.id === "weakened") },
                { effect: "Fortify your defenses",          check: p => p.statusEffects?.some(s => s.id === "fortified") },
                { effect: "Inspire allies to fight",        check: p => p.statusEffects?.some(s => s.id === "inspired") },
                { effect: "Regenerate health over time",    check: p => p.statusEffects?.some(s => s.id === "regenerating") },
                { effect: "Cleanse all poisons",            check: p => p.cure === true },
                { effect: "Deal 20 and stun target",        check: p => p.damage === 20 && p.statusEffects?.some(s => s.id === "stunned") },
                { effect: "Restore 5 HP and 3 Energy",      check: p => p.healHp === 5 && p.restoreEnergy === 3 },
            ];

            for (let t of testEffects) {
                let parsed = parseEffect(t.effect);
                let ok = t.check(parsed);
                testLog("cards", "parseEffect('" + t.effect + "') → " + JSON.stringify(parsed), ok ? "pass" : "fail");
            }

            // Test isEffectParseable
            let parseableEffects = ["Deal 10 damage", "Restore 5 HP", "Stun target", "Drain 8"];
            let unparseableEffects = ["View opponent's next action", "+2 to Investigate rolls this round", "Attacks lowest HP character first"];
            for (let e of parseableEffects) {
                testLog("cards", "isParseable('" + e + "')", isEffectParseable(e) ? "pass" : "fail");
            }
            for (let e of unparseableEffects) {
                testLog("cards", "isNotParseable('" + e + "')", !isEffectParseable(e) ? "pass" : "fail");
            }

            // Test skill modifier parsing with expanded keywords
            let skillTests = [
                { mod: "+2 to Attack rolls", action: "attack", expect: 2 },
                { mod: "+3 to Defense", action: "defense", expect: 3 },
                { mod: "+1 to social interactions", action: "talk", expect: 1 },
                { mod: "+2 to melee strikes", action: "attack", expect: 2 },
                { mod: "+2 to Flee and initiative rolls", action: "flee", expect: 2 },
                { mod: "+1 to Investigate and Rest rolls", action: "investigate", expect: 1 },
                { mod: "+2 to spell casting", action: "magic", expect: 2 },
            ];
            for (let s of skillTests) {
                let stack = { modifiers: [{ type: "skill", modifier: s.mod }] };
                let result = getStackSkillMod(stack, s.action);
                testLog("cards", "SkillMod('" + s.mod + "', '" + s.action + "') = " + result, result === s.expect ? "pass" : "fail");
            }

            // Analyze active theme card pool for gaps
            if (activeTheme?.cardPool) {
                let pool = activeTheme.cardPool;
                let types = {};
                (pool || []).forEach(c => { types[c.type] = (types[c.type] || 0) + 1; });
                testLog("cards", "Theme '" + activeTheme.name + "' card pool: " + JSON.stringify(types), "info");

                // Check skills have valid modifier patterns
                for (let sk of (pool || []).filter(c => c.type === "skill")) {
                    let hasNumericMod = sk.modifier && sk.modifier.match(/\+(\d+)/);
                    testLog("cards", "Skill '" + sk.name + "': " + (sk.modifier || "none"), hasNumericMod ? "pass" : "warn");
                }
                // Check magic have parseable effects
                for (let mg of (pool || []).filter(c => c.type === "magic")) {
                    let parseable = isEffectParseable(mg.effect);
                    testLog("cards", "Magic '" + mg.name + "': " + (mg.effect || "").substring(0, 50), parseable ? "pass" : "warn");
                }
                // Check consumables have parseable effects
                for (let con of (pool || []).filter(c => c.type === "item" && c.subtype === "consumable")) {
                    let parseable = isEffectParseable(con.effect);
                    testLog("cards", "Consumable '" + con.name + "': " + (con.effect || "").substring(0, 50), parseable ? "pass" : "warn");
                }
            } else {
                testLog("cards", "No active theme card pool loaded", "warn");
            }
        }

        // ── Campaign Tests ───────────────────
        if (cats.includes("campaign")) {
            testState.currentTest = "Campaign: XP, levels, stat gains";
            testLog("campaign", "Testing campaign system...");

            // Test createCampaignData
            let testChar = { name: "TestHero", sourceId: "test-123" };
            let cd = createCampaignData(testChar);
            if (cd.level === 1 && cd.xp === 0 && cd.characterName === "TestHero") {
                testLog("campaign", "createCampaignData defaults correct", "pass");
            } else {
                testLog("campaign", "createCampaignData defaults wrong", "fail");
            }

            // Test calculateXP
            let testState1 = { round: 5, player: { hp: 15 } };
            let xpWin = calculateXP(testState1, true);
            let xpLose = calculateXP(testState1, false);
            testLog("campaign", "calculateXP(5 rounds, 15hp, win)=" + xpWin + " (expect 130)", xpWin === 130 ? "pass" : "fail");
            testLog("campaign", "calculateXP(5 rounds, 15hp, loss)=" + xpLose + " (expect 80)", xpLose === 80 ? "pass" : "fail");

            // Test level calculation
            let levels = [
                { xp: 0, expect: 1 }, { xp: 99, expect: 1 }, { xp: 100, expect: 2 },
                { xp: 250, expect: 3 }, { xp: 999, expect: 10 }, { xp: 1500, expect: 10 }
            ];
            for (let l of levels) {
                let level = Math.min(10, Math.floor(l.xp / 100) + 1);
                testLog("campaign", "XP " + l.xp + " → Level " + level + " (expect " + l.expect + ")", level === l.expect ? "pass" : "fail");
            }

            // Test applyCampaignBonuses
            let testGsState = {
                player: {
                    character: { stats: { STR: 10, AGI: 10, END: 15, INT: 10, MAG: 12, CHA: 10 } },
                    ap: 4, maxAp: 4, energy: 12, maxEnergy: 12
                }
            };
            let testCampaign = { statGains: { STR: 2, END: 5 } };
            applyCampaignBonuses(testGsState, testCampaign);
            if (testGsState.player.character.stats.STR === 12 && testGsState.player.character.stats.END === 20) {
                testLog("campaign", "applyCampaignBonuses: stats applied (STR 10→12, END 15→20)", "pass");
            } else {
                testLog("campaign", "applyCampaignBonuses: stats wrong", "fail");
            }
            // END=20 → AP = floor(20/5)+1 = 5
            if (testGsState.player.ap === 5) {
                testLog("campaign", "applyCampaignBonuses: AP recalculated (END 20 → AP 5)", "pass");
            } else {
                testLog("campaign", "applyCampaignBonuses: AP not recalculated (got " + testGsState.player.ap + ")", "fail");
            }
        }

        // ── Character Stats & Integration Tests ─
        if (cats.includes("cards")) {
            testState.currentTest = "Integration: character stats, effects, save/load";
            testLog("cards", "Testing character stats from deck...");

            // Use the resolved deck for character stat validation
            let charTestDecks = [];
            if (resolvedDeck?.cards) {
                charTestDecks.push({ name: resolvedDeck.deckName || resolvedDeckName, deck: resolvedDeck });
            } else {
                // Fallback: load available decks
                let deckNames2 = await deckStorage.list();
                for (let dn of deckNames2.slice(0, 3)) {
                    let d = await deckStorage.load(dn);
                    if (d?.cards) charTestDecks.push({ name: dn, deck: d });
                }
            }
            for (let { name: dn, deck } of charTestDecks) {
                let chars = (deck.cards || []).filter(c => c.type === "character");
                if (chars.length === 0) {
                    testLog("cards", "Deck '" + dn + "': no character cards", "warn");
                    continue;
                }
                for (let ch of chars) {
                    let stats = ch.stats || {};
                    let statNames = Object.keys(stats);
                    let hasCore = ["STR", "AGI", "END", "INT", "MAG", "CHA"].filter(s => stats[s] !== undefined);
                    testLog("cards", "Char '" + ch.name + "': " + hasCore.length + "/6 core stats (" + hasCore.join(",") + ")",
                        hasCore.length >= 4 ? "pass" : "warn");

                    // Validate stat ranges (should be 1-30 for reasonable gameplay)
                    let outOfRange = hasCore.filter(s => stats[s] < 1 || stats[s] > 30);
                    if (outOfRange.length > 0) {
                        testLog("cards", "Char '" + ch.name + "': stats out of range [1-30]: " + outOfRange.map(s => s + "=" + stats[s]).join(", "), "warn");
                    } else if (hasCore.length > 0) {
                        testLog("cards", "Char '" + ch.name + "': all stats in valid range", "pass");
                    }

                    // Test createGameState with this deck (stats should not persist/mutate)
                    let statsBefore = JSON.stringify(ch.stats);
                    let gs = createGameState(deck, ch);  // Use the deck from charTestDecks
                    let statsAfter = JSON.stringify(ch.stats);
                    if (gs) {
                        if (statsBefore === statsAfter) {
                            testLog("cards", "createGameState: '" + ch.name + "' stats not mutated", "pass");
                        } else {
                            testLog("cards", "createGameState: '" + ch.name + "' stats MUTATED (should not persist)", "fail");
                        }
                        // Validate derived values
                        let playerEnd = gs.player.character.stats?.END || 12;
                        let expectedAp = Math.max(2, Math.floor(playerEnd / 5) + 1);
                        if (gs.player.ap === expectedAp) {
                            testLog("cards", "AP derived correctly: END=" + playerEnd + " → AP=" + gs.player.ap, "pass");
                        } else {
                            testLog("cards", "AP mismatch: END=" + playerEnd + " → expected AP=" + expectedAp + " got " + gs.player.ap, "fail");
                        }
                        // Validate hand dealt (5 base + 1 Attack card added by design = 6)
                        if (gs.player.hand.length >= 5 && gs.player.hand.length <= 7) {
                            let hasAttack = gs.player.hand.some(c => c.name === "Attack" || c.type === "attack");
                            testLog("cards", "Initial hand: " + gs.player.hand.length + " cards" + (hasAttack ? " (includes Attack)" : ""), "pass");
                        } else {
                            testLog("cards", "Initial hand unexpected size: " + gs.player.hand.length + " (expected 5-7)", "fail");
                        }
                    } else {
                        testLog("cards", "createGameState returned null for '" + ch.name + "'", "fail");
                    }
                }
            }

            // ── Integration: applyParsedEffects ──
            testLog("cards", "Testing applyParsedEffects integration...");
            let testOwner = { hp: 20, maxHp: 25, energy: 10, maxEnergy: 15, morale: 15, maxMorale: 20, hand: [], drawPile: [{ name: "TestCard" }], discardPile: [], statusEffects: [] };
            let testTarget = { hp: 20, maxHp: 20, statusEffects: [] };

            // Test damage
            let dmgParsed = parseEffect("Deal 8 damage");
            applyParsedEffects(dmgParsed, testOwner, testTarget, "TestSpell");
            testLog("cards", "applyParsedEffects(Deal 8): target HP 20→" + testTarget.hp, testTarget.hp === 12 ? "pass" : "fail");

            // Test heal
            testOwner.hp = 15;
            let healParsed = parseEffect("Restore 7 HP");
            applyParsedEffects(healParsed, testOwner, testTarget, "HealSpell");
            testLog("cards", "applyParsedEffects(Restore 7 HP): owner HP 15→" + testOwner.hp, testOwner.hp === 22 ? "pass" : "fail");

            // Test heal capped at max
            testOwner.hp = 24;
            applyParsedEffects(parseEffect("Heal 10"), testOwner, testTarget, "BigHeal");
            testLog("cards", "applyParsedEffects(Heal 10): capped at maxHp " + testOwner.maxHp + " → " + testOwner.hp, testOwner.hp === 25 ? "pass" : "fail");

            // Test drain (damage + self-heal)
            testOwner.hp = 15;
            testTarget.hp = 20;
            applyParsedEffects(parseEffect("Drain 6"), testOwner, testTarget, "DrainSpell");
            testLog("cards", "applyParsedEffects(Drain 6): target HP " + testTarget.hp + ", owner HP " + testOwner.hp,
                testTarget.hp === 14 && testOwner.hp === 21 ? "pass" : "fail");

            // Test energy/morale restore
            testOwner.energy = 5;
            testOwner.morale = 10;
            applyParsedEffects(parseEffect("Restore 4 Energy"), testOwner, testTarget, "E");
            applyParsedEffects(parseEffect("Restore 3 Morale"), testOwner, testTarget, "M");
            testLog("cards", "Energy 5→" + testOwner.energy + ", Morale 10→" + testOwner.morale,
                testOwner.energy === 9 && testOwner.morale === 13 ? "pass" : "fail");

            // Test draw cards
            testOwner.hand = [];
            testOwner.drawPile = [{ name: "A" }, { name: "B" }, { name: "C" }];
            applyParsedEffects(parseEffect("Draw 2 cards"), testOwner, testTarget, "DrawSpell");
            testLog("cards", "applyParsedEffects(Draw 2): hand=" + testOwner.hand.length + " drawPile=" + testOwner.drawPile.length,
                testOwner.hand.length === 2 && testOwner.drawPile.length === 1 ? "pass" : "fail");

            // Test status effect application
            testTarget.statusEffects = [];
            applyParsedEffects(parseEffect("Stun target"), testOwner, testTarget, "StunCard");
            let hasStun = testTarget.statusEffects.some(e => e.id === "stunned");
            testLog("cards", "applyParsedEffects(Stun): target has stunned=" + hasStun, hasStun ? "pass" : "fail");

            testOwner.statusEffects = [];
            applyParsedEffects(parseEffect("Shield self"), testOwner, testTarget, "ShieldCard");
            let hasShield = testOwner.statusEffects.some(e => e.id === "shielded");
            testLog("cards", "applyParsedEffects(Shield): owner has shielded=" + hasShield, hasShield ? "pass" : "fail");

            // Test cure removes negatives
            testOwner.statusEffects = [];
            applyStatusEffect(testOwner, "poisoned", "test");
            applyStatusEffect(testOwner, "burning", "test");
            applyStatusEffect(testOwner, "shielded", "test");  // positive — should NOT be removed
            let beforeCure = testOwner.statusEffects.length;
            applyParsedEffects(parseEffect("Cleanse all"), testOwner, testTarget, "CureSpell");
            let afterCure = testOwner.statusEffects.length;
            let keptShield = testOwner.statusEffects.some(e => e.id === "shielded");
            testLog("cards", "Cure: " + beforeCure + " effects → " + afterCure + " (shield kept=" + keptShield + ")",
                afterCure === 1 && keptShield ? "pass" : "fail");

            // Test combo effect
            testTarget.hp = 20;
            testTarget.statusEffects = [];
            applyParsedEffects(parseEffect("Deal 10 and poison target"), testOwner, testTarget, "Combo");
            let comboOk = testTarget.hp === 10 && testTarget.statusEffects.some(e => e.id === "poisoned");
            testLog("cards", "Combo(Deal 10 + poison): HP=" + testTarget.hp + " poisoned=" + comboOk, comboOk ? "pass" : "fail");

            // ── Integration: status effect lifecycle ──
            testLog("cards", "Testing status effect lifecycle...");
            let lifecycleActor = { hp: 20, maxHp: 20, statusEffects: [] };
            applyStatusEffect(lifecycleActor, "poisoned", "TestPoison");
            if (lifecycleActor.statusEffects.length === 1 && lifecycleActor.statusEffects[0].turnsRemaining === 3) {
                testLog("cards", "Poisoned applied: duration=3", "pass");
            } else {
                testLog("cards", "Poisoned not applied correctly", "fail");
            }
            // Re-apply should refresh duration, not stack
            lifecycleActor.statusEffects[0].turnsRemaining = 1;
            applyStatusEffect(lifecycleActor, "poisoned", "RefreshPoison");
            if (lifecycleActor.statusEffects.length === 1 && lifecycleActor.statusEffects[0].turnsRemaining === 3) {
                testLog("cards", "Re-apply refreshes duration (1→3), no stack", "pass");
            } else {
                testLog("cards", "Re-apply stacking/duration wrong: count=" + lifecycleActor.statusEffects.length, "fail");
            }
            // Remove
            removeStatusEffect(lifecycleActor, "poisoned");
            testLog("cards", "removeStatusEffect: " + lifecycleActor.statusEffects.length + " remaining",
                lifecycleActor.statusEffects.length === 0 ? "pass" : "fail");

            // Status modifier calculation
            let modActor = { statusEffects: [] };
            applyStatusEffect(modActor, "enraged", "test");
            applyStatusEffect(modActor, "shielded", "test");
            let mods = getStatusModifiers(modActor);
            testLog("cards", "StatusMods(enraged+shielded): atk=" + mods.atk + " def=" + mods.def,
                mods.atk === 3 && mods.def === 1 ? "pass" : "fail");  // enraged +3 ATK -2 DEF, shielded +3 DEF = net +1 DEF
        }

        // ── Extended Save/Load Tests ─────────
        if (cats.includes("storage")) {
            testState.currentTest = "Storage: save/load field completeness";
            testLog("storage", "Testing save/load field preservation...");
            {
                let deck = resolvedDeck;
                let saveDeckName = resolvedDeckName || "test";
                if (deck) {
                    let gs = createGameState(deck);
                    if (gs) {
                        // Set up recognizable state
                        gs.round = 7;
                        gs.player.hp = 13;
                        gs.player.energy = 4;
                        gs.player.morale = 11;
                        gs.opponent.hp = 8;
                        let origHandLen = gs.player.hand.length;
                        let origRound = gs.round;
                        let origOppHp = gs.opponent.hp;
                        let origPhase = gs.phase;

                        let saved = await gameStorage.save(saveDeckName + "__test", gs);
                        if (saved) {
                            let loaded = await gameStorage.load(saveDeckName + "__test");
                            if (loaded?.gameState) {
                                let ls = loaded.gameState;
                                let checks = [
                                    ["round", ls.round === origRound],
                                    ["phase", ls.phase === origPhase],
                                    ["player.hp", ls.player?.hp === 13],
                                    ["player.energy", ls.player?.energy === 4],
                                    ["player.morale", ls.player?.morale === 11],
                                    ["player.hand.length", ls.player?.hand?.length === origHandLen],
                                    ["opponent.hp", ls.opponent?.hp === origOppHp],
                                    ["player.ap", ls.player?.ap === gs.player.ap],
                                    ["player.drawPile", Array.isArray(ls.player?.drawPile)],
                                    ["player.discardPile", Array.isArray(ls.player?.discardPile)],
                                    ["player.statusEffects", Array.isArray(ls.player?.statusEffects)],
                                ];
                                let allOk = true;
                                for (let [field, ok] of checks) {
                                    if (!ok) { testLog("storage", "Save/load field LOST: " + field, "fail"); allOk = false; }
                                }
                                if (allOk) testLog("storage", "Save/load: all " + checks.length + " fields preserved", "pass");
                            } else {
                                testLog("storage", "Save/load: loaded.gameState is null", "fail");
                            }
                            await gameStorage.deleteAll(saveDeckName + "__test");
                        } else {
                            testLog("storage", "Save/load: save returned null", "fail");
                        }
                    }
                }
            }
        }

        // ── LLM Tests ────────────────────────
        if (cats.includes("llm")) {
            testState.currentTest = "LLM: connectivity and chat";
            testLog("llm", "Testing LLM connectivity...");

            try {
                await checkLlmConnectivity();
                testLog("llm", "LLM status: available=" + llmStatus.available + ", checked=" + llmStatus.checked, llmStatus.available ? "pass" : "warn");

                if (llmStatus.available) {
                    testLog("llm", "LLM endpoint reachable", "pass");

                    // Test CardGameLLM base class
                    testLog("llm", "CardGameLLM.findChatDir()...");
                    try {
                        let chatDir = await CardGameLLM.findChatDir();
                        testLog("llm", "Chat directory: " + (chatDir ? chatDir.path || chatDir.name : "not found"), chatDir ? "pass" : "warn");
                    } catch (e) {
                        testLog("llm", "findChatDir error: " + e.message, "warn");
                    }

                    // Actual LLM chat test — send a prompt and verify response
                    testState.currentTest = "LLM: actual chat test";
                    testLog("llm", "Initializing test LLM instance...");
                    try {
                        let testLlm = new CardGameLLM();
                        let initOk = await testLlm.initializeLLM(
                            "CardGame Test Chat",
                            "CardGame Test Prompt",
                            "You are a helpful test assistant for a card game. Respond briefly.",
                            0.3
                        );
                        testLog("llm", "LLM init: " + (initOk ? "success" : "failed — " + (testLlm.lastError || "unknown")), initOk ? "pass" : "warn");

                        if (initOk) {
                            // Send a simple test prompt
                            testLog("llm", "Sending test prompt to LLM...");
                            let response = await testLlm.chat("Say exactly: TEST_OK");
                            let content = CardGameLLM.extractContent(response);
                            testLog("llm", "LLM raw response: " + JSON.stringify(response)?.substring(0, 200), "info");
                            testLog("llm", "LLM extracted content: " + (content || "(empty)").substring(0, 150), content ? "pass" : "fail");

                            // Test narrator initialization
                            testState.currentTest = "LLM: narrator test";
                            testLog("llm", "Testing CardGameNarrator...");
                            let testNarrator = new CardGameNarrator();
                            let themeId = resolvedDeck?.themeId || activeTheme?.themeId || "high-fantasy";
                            let narratorOk = await testNarrator.initialize("arena-announcer", themeId);
                            testLog("llm", "Narrator init (" + themeId + "): " + (narratorOk ? "success" : "failed — " + (testNarrator.lastError || "unknown")), narratorOk ? "pass" : "warn");

                            if (narratorOk) {
                                // Test game_start narration
                                testLog("llm", "Requesting game_start narration...");
                                let narration = await testNarrator.narrate("game_start", {
                                    playerName: "Test Hero",
                                    opponentName: "Test Villain"
                                });
                                testLog("llm", "Narration text: " + (narration?.text || "(empty)").substring(0, 200), narration?.text ? "pass" : "fail");
                                if (narration?.imagePrompt) {
                                    testLog("llm", "Narration IMAGE prompt: " + narration.imagePrompt.substring(0, 150), "info");
                                }

                                // Test resolution narration
                                testLog("llm", "Requesting resolution narration...");
                                let resNarration = await testNarrator.narrate("resolution", {
                                    playerStack: "Fireball (magic, Deal 15 fire damage)",
                                    playerRoll: { raw: 18, total: 25 },
                                    opponentStack: "Shield Block (defense)",
                                    opponentRoll: { raw: 8, total: 15 },
                                    isPlayerAttack: true,
                                    outcome: "HIT",
                                    damage: 10
                                });
                                testLog("llm", "Resolution narration: " + (resNarration?.text || "(empty)").substring(0, 200), resNarration?.text ? "pass" : "warn");
                            }

                            // Test JSON cleaning utility
                            let jsonTestInput = "```json\n{\"name\": \"Test\"}\n```";
                            let cleaned = CardGameLLM.cleanJsonResponse(jsonTestInput);
                            try {
                                let parsed = JSON.parse(cleaned);
                                testLog("llm", "cleanJsonResponse: parsed OK — " + JSON.stringify(parsed), "pass");
                            } catch (e) {
                                testLog("llm", "cleanJsonResponse: parse failed — " + cleaned, "fail");
                            }
                        }
                    } catch (e) {
                        testLog("llm", "LLM chat test error: " + e.message, "fail");
                    }
                } else {
                    testLog("llm", "LLM not available — remaining LLM tests skipped (defaults/fallbacks will be used at runtime)", "info");
                }
            } catch (e) {
                testLog("llm", "LLM connectivity error: " + e.message, "fail");
            }
        }

        // ── Voice Tests ──────────────────────
        if (cats.includes("voice")) {
            testState.currentTest = "Voice: synthesis pipeline";
            testLog("voice", "Testing voice system...");

            let hasAudio = typeof page?.components?.audio?.createAudioSource === "function";
            testLog("voice", "Audio infrastructure: " + (hasAudio ? "present" : "absent"), "info");

            // Test 1: subtitlesOnly mode (should work regardless of audio)
            let testVoice1 = new CardGameVoice();
            await testVoice1.initialize({ subtitlesOnly: true });
            if (testVoice1.subtitlesOnly && testVoice1.enabled) {
                testLog("voice", "subtitlesOnly init: enabled=" + testVoice1.enabled + " subtitlesOnly=" + testVoice1.subtitlesOnly, "pass");
            } else {
                testLog("voice", "subtitlesOnly init: unexpected state enabled=" + testVoice1.enabled + " subtitlesOnly=" + testVoice1.subtitlesOnly, "fail");
            }

            // Test 2: speak() in subtitlesOnly mode shows subtitle
            let savedGs = gameState;
            gameState = { narrationText: null, narrationTime: null };
            await testVoice1.speak("Voice test subtitle text");
            if (gameState.narrationText === "Voice test subtitle text") {
                testLog("voice", "speak() in subtitlesOnly mode → subtitle displayed", "pass");
            } else {
                testLog("voice", "speak() in subtitlesOnly mode → subtitle NOT displayed (narrationText=" + gameState.narrationText + ")", "fail");
            }
            gameState.narrationText = null;
            gameState = savedGs;

            // Test 3: Volume control
            testVoice1.setVolume(0.5);
            testLog("voice", "setVolume(0.5) → volume=" + testVoice1.volume, testVoice1.volume === 0.5 ? "pass" : "fail");
            testVoice1.setVolume(-1);
            testLog("voice", "setVolume(-1) → clamped to " + testVoice1.volume, testVoice1.volume === 0 ? "pass" : "fail");
            testVoice1.setVolume(5);
            testLog("voice", "setVolume(5) → clamped to " + testVoice1.volume, testVoice1.volume === 1 ? "pass" : "fail");

            // Test 4: Queue and stop
            testVoice1.queue = [{ text: "a" }, { text: "b" }];
            testVoice1.stop();
            testLog("voice", "stop() clears queue: " + testVoice1.queue.length + " items", testVoice1.queue.length === 0 ? "pass" : "fail");

            // Test 5: Full voice mode (if audio infrastructure available)
            if (hasAudio) {
                testState.currentTest = "Voice: audio synthesis";
                let testVoice2 = new CardGameVoice();
                await testVoice2.initialize({ subtitlesOnly: false, volume: 0.7 });
                testLog("voice", "Full voice init: subtitlesOnly=" + testVoice2.subtitlesOnly + " volume=" + testVoice2.volume, !testVoice2.subtitlesOnly ? "pass" : "warn");

                // Actually try to generate speech
                try {
                    testLog("voice", "Attempting TTS: 'Testing voice synthesis'...");
                    await testVoice2.speak("Testing voice synthesis");
                    testLog("voice", "TTS speak() completed without error", "pass");
                } catch (e) {
                    testLog("voice", "TTS speak() error: " + e.message, "fail");
                }
            } else {
                testLog("voice", "No audio infrastructure — full voice test skipped (subtitlesOnly fallback verified above)", "info");
            }
        }

        // ── Playthrough Tests ────────────────
        if (cats.includes("playthrough")) {
            testState.currentTest = "Playthrough: automated game";
            testLog("playthrough", "Running automated playthrough...");

            if (!resolvedDeck) {
                testLog("playthrough", "No deck available for playthrough", "warn");
            } else {
                let deckName = resolvedDeckName;
                let deck = resolvedDeck;
                {
                    testLog("playthrough", "Using deck: " + (deck.deckName || deckName) + " (" + (deck.cards || []).length + " cards)", "pass");

                    // Create game state
                    let gs = createGameState(deck);
                    if (!gs) {
                        testLog("playthrough", "createGameState failed (no characters?)", "fail");
                    } else {
                        testLog("playthrough", "Game created: " + gs.player.character.name + " vs " + gs.opponent.character.name, "pass");
                        testLog("playthrough", "Player: HP=" + gs.player.hp + " AP=" + gs.player.ap + " Hand=" + gs.player.hand.length, "info");
                        testLog("playthrough", "Opponent: HP=" + gs.opponent.hp + " AP=" + gs.opponent.ap + " Hand=" + gs.opponent.hand.length, "info");

                        // Simulate rounds
                        let maxRounds = 5;
                        for (let r = 0; r < maxRounds; r++) {
                            // Initiative
                            let pRoll = rollD20() + (gs.player.character.stats?.AGI || 10);
                            let oRoll = rollD20() + (gs.opponent.character.stats?.AGI || 10);
                            let initWinner = pRoll >= oRoll ? "player" : "opponent";
                            testLog("playthrough", "Round " + (r + 1) + " initiative: " + initWinner + " (P:" + pRoll + " vs O:" + oRoll + ")", "info");

                            // Simulate combat
                            let pAtk = rollD20() + (gs.player.character.stats?.STR || 10) + getActorATK(gs.player);
                            let oDef = rollD20() + (gs.opponent.character.stats?.END || 10) + getActorDEF(gs.opponent);
                            if (pAtk > oDef) {
                                let dmg = Math.max(1, Math.floor((pAtk - oDef) / 3));
                                gs.opponent.hp -= dmg;
                                testLog("playthrough", "Player attacks: " + pAtk + " vs " + oDef + " → " + dmg + " damage (opp HP: " + gs.opponent.hp + ")", "info");
                            } else {
                                testLog("playthrough", "Player attacks: " + pAtk + " vs " + oDef + " → miss", "info");
                            }

                            let oAtk = rollD20() + (gs.opponent.character.stats?.STR || 10) + getActorATK(gs.opponent);
                            let pDef = rollD20() + (gs.player.character.stats?.END || 10) + getActorDEF(gs.player);
                            if (oAtk > pDef) {
                                let dmg = Math.max(1, Math.floor((oAtk - pDef) / 3));
                                gs.player.hp -= dmg;
                                testLog("playthrough", "Opponent attacks: " + oAtk + " vs " + pDef + " → " + dmg + " damage (player HP: " + gs.player.hp + ")", "info");
                            } else {
                                testLog("playthrough", "Opponent attacks: " + oAtk + " vs " + pDef + " → miss", "info");
                            }

                            // Check game over
                            if (gs.player.hp <= 0) { testLog("playthrough", "Player defeated at round " + (r + 1), "info"); break; }
                            if (gs.opponent.hp <= 0) { testLog("playthrough", "Opponent defeated at round " + (r + 1), "info"); break; }
                        }

                        let winner = gs.player.hp <= 0 ? "opponent" : gs.opponent.hp <= 0 ? "player" : "draw";
                        testLog("playthrough", "Result: " + winner + " (P:" + gs.player.hp + " O:" + gs.opponent.hp + ")", winner !== "draw" ? "pass" : "info");

                        // Test save/load cycle (basic — comprehensive test in storage category)
                        let saved = await gameStorage.save(deckName + "__pt", gs);
                        if (saved) {
                            let loadedSave = await gameStorage.load(deckName + "__pt");
                            let roundtrip = loadedSave?.gameState?.player?.hp === gs.player.hp
                                && loadedSave?.gameState?.round === gs.round
                                && loadedSave?.gameState?.opponent?.hp === gs.opponent.hp;
                            testLog("playthrough", "Save/load roundtrip (hp+round+opp): " + (roundtrip ? "ok" : "mismatch"), roundtrip ? "pass" : "fail");
                            await gameStorage.deleteAll(deckName + "__pt");
                        } else {
                            testLog("playthrough", "Game state save failed", "fail");
                        }
                    }
                }
            }
        }

        testState.currentTest = null;
        testState.running = false;
        testState.completed = true;
        testLog("", "=== Test suite complete: " + testState.results.pass + " pass, " + testState.results.fail + " fail, " + testState.results.warn + " warn ===", testState.results.fail > 0 ? "fail" : "pass");
        m.redraw();
    }

    function TestModeUI() {
        return {
            view() {
                let statusColors = { info: "#777", pass: "#2E7D32", fail: "#C62828", warn: "#E65100" };
                let statusIcons = { info: "info", pass: "check_circle", fail: "error", warn: "warning" };

                return m("div", { class: "cg2-test-mode" }, [
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", { class: "cg2-btn", onclick() {
                            if (testDeck && viewingDeck) { screen = "deckView"; }
                            else { screen = "deckList"; }
                            m.redraw();
                        } }, "← Back"),
                        m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, "Test Mode"),
                        testDeckName ? m("span", { class: "cg2-deck-theme-badge", style: { marginLeft: "8px" } }, testDeckName) : null,
                        !testState.running ? m("button", {
                            class: "cg2-btn cg2-btn-primary", style: { marginLeft: "auto" },
                            onclick() { runTestSuite(); }
                        }, [m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "play_arrow"), "Run All Tests"]) : null,
                        testState.running ? m("span", { style: { marginLeft: "auto", color: "#B8860B", fontWeight: 600, fontSize: "13px" } }, [
                            m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "sync"),
                            testState.currentTest || "Running..."
                        ]) : null
                    ]),

                    // Category toggles
                    m("div", { class: "cg2-test-categories" },
                        Object.entries(TEST_CATEGORIES).map(([key, cat]) => {
                            let active = testState.selectedCategories.includes(key);
                            return m("label", { class: "cg2-test-cat" + (active ? " active" : ""), onclick() {
                                if (active) testState.selectedCategories = testState.selectedCategories.filter(c => c !== key);
                                else testState.selectedCategories.push(key);
                                m.redraw();
                            } }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, cat.icon),
                                " " + cat.label
                            ]);
                        })
                    ),

                    // Results summary
                    testState.completed ? m("div", { class: "cg2-test-summary" }, [
                        m("span", { class: "cg2-test-result-pass" }, testState.results.pass + " pass"),
                        m("span", { class: "cg2-test-result-fail" }, testState.results.fail + " fail"),
                        m("span", { class: "cg2-test-result-warn" }, testState.results.warn + " warn")
                    ]) : null,

                    // Debug console log
                    m("div", { class: "cg2-test-console" }, (() => {
                        let items = [];
                        let lastCat = null;
                        for (let entry of testState.logs) {
                            // Add section header when category changes
                            if (entry.category && entry.category !== lastCat) {
                                let catLabel = TEST_CATEGORIES[entry.category]?.label || entry.category;
                                items.push(m("div", { class: "cg2-test-section-header" }, catLabel));
                                lastCat = entry.category;
                            }
                            items.push(m("div", { class: "cg2-test-log-entry", "data-status": entry.status }, [
                                m("span", { class: "cg2-test-log-time" }, entry.time),
                                entry.category ? m("span", { class: "cg2-test-log-cat", "data-cat": entry.category }, entry.category) : null,
                                m("span", {
                                    class: "material-symbols-outlined",
                                    style: { fontSize: "13px", color: statusColors[entry.status] || "#666", verticalAlign: "middle", marginRight: "4px" }
                                }, statusIcons[entry.status] || "info"),
                                m("span", { style: { color: statusColors[entry.status] || "#333" } }, entry.message)
                            ]));
                        }
                        if (testState.logs.length === 0) {
                            items.push(m("div", { class: "cg2-test-empty" }, testDeck
                                ? "Click 'Run All Tests' to test deck: " + (testDeckName || "selected")
                                : "Select a deck first, or click 'Run All Tests' for generic tests."));
                        }
                        return items;
                    })())
                ]);
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 9: Theme Editor
    // ═══════════════════════════════════════════════════════════════════

    let themeEditorState = {
        themes: [],        // [{ name, themeId, isCustom, isBuiltin }]
        loading: false,
        editingTheme: null, // Full theme JSON being edited
        editingName: "",
        jsonText: "",
        jsonError: null,
        dirty: false
    };

    const THEME_STORAGE_PATH = DECK_BASE_PATH + "/themes";

    const themeStorage = {
        async save(themeId, themeData) {
            try {
                return await upsertDataRecord(THEME_STORAGE_PATH, themeId + ".json", themeData);
            } catch (e) {
                console.error("[CardGame v2] Failed to save theme:", themeId, e);
                return null;
            }
        },
        async load(themeId) {
            try {
                return await loadDataRecord(THEME_STORAGE_PATH, themeId + ".json", false);
            } catch (e) {
                return null;
            }
        },
        async list() {
            try {
                let records = await listDataRecords(THEME_STORAGE_PATH);
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

    async function loadThemeList() {
        themeEditorState.loading = true;
        m.redraw();
        let builtins = ["high-fantasy", "dark-medieval", "sci-fi", "post-apocalypse"];
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
                narratorProfile: "arena-announcer",
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
            narratorProfile: "arena-announcer",
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
                            m("button", { class: "cg2-btn", onclick() { themeEditorState.editingTheme = null; m.redraw(); } }, "← Back"),
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
                                    let parsed = parseEffect(themeEditorState.effectPreview);
                                    let parts = [];
                                    if (parsed.damage) parts.push("DMG:" + parsed.damage);
                                    if (parsed.healHp) parts.push("HEAL:" + parsed.healHp + " HP");
                                    if (parsed.restoreEnergy) parts.push("+" + parsed.restoreEnergy + " Energy");
                                    if (parsed.restoreMorale) parts.push("+" + parsed.restoreMorale + " Morale");
                                    if (parsed.draw) parts.push("DRAW:" + parsed.draw);
                                    if (parsed.cure) parts.push("CURE");
                                    if (parsed.statusEffects) parsed.statusEffects.forEach(s => parts.push(s.id.toUpperCase() + "→" + s.target));
                                    return parts.length > 0
                                        ? m("span", { style: { color: "#2E7D32" } }, "✓ Parsed: " + parts.join(", "))
                                        : m("span", { style: { color: "#c62828" } }, "✗ No mechanical effects detected");
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
  → modifier MUST contain +N and a keyword: attack, combat, melee, defense, parry, talk, social,
    flee, escape, initiative, investigate, search, craft, magic, spell, etc.

MAGIC:   { type:"magic", name, effect, energyCost, requires:{MAG:N}, rarity }
  → PARSEABLE EFFECTS (combine any):
    "Deal N damage" — direct damage to target
    "Drain N"       — damage target + heal self same amount
    "Heal N" / "Restore N HP"   — heal owner
    "Restore N Energy/Morale"   — restore resources
    "Draw N"        — draw N extra cards
    "Stun/Poison/Burn/Bleed/Weaken target" — debuff target
    "Shield/Enrage/Fortify/Inspire/Regenerate self" — buff self
    "Cure/Cleanse"  — remove negative statuses

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
                        m("button", { class: "cg2-btn", onclick() { screen = "deckList"; m.redraw(); } }, "← Back"),
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
            let isGameScreen = screen === "game";
            return m("div", { class: "content-outer" }, [
                fullMode ? "" : m(page.components.navigation),
                m("div", { class: "content-main" }, [
                    m("div", { class: "cg2-container" + (isGameScreen ? " cg2-game-mode" : "") }, [
                        // Header (hidden in game mode - game has its own header)
                        !isGameScreen ? m("div", { class: "cg2-toolbar" }, [
                            page.iconButton("button mr-4", fullMode ? "close_fullscreen" : "open_in_new", "", toggleFullMode),
                            m("span", { style: { fontWeight: 700, fontSize: "16px", marginRight: "16px" } }, "Card Game"),
                            screen === "deckList" ? [
                                m("button", { class: "cg2-btn cg2-btn-sm", onclick() { loadThemeList(); screen = "themeEditor"; m.redraw(); } }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "2px" } }, "palette"),
                                    " Themes"
                                ]),
                                m("button", { class: "cg2-btn cg2-btn-sm", style: { marginLeft: "4px" }, onclick() { screen = "test"; m.redraw(); } }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "2px" } }, "science"),
                                    " Test"
                                ])
                            ] : null,
                            m("span", { style: { marginLeft: "auto", fontSize: "11px", color: "#999" } },
                                "Theme: " + activeTheme.name
                            )
                        ]) : null,

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
                        screen === "deckView" ? m(DeckView) : null,
                        screen === "game" ? m(GameView) : null,
                        screen === "test" ? m(TestModeUI) : null,
                        screen === "themeEditor" ? m(ThemeEditorUI) : null
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
        artQueue: () => artQueue,
        // Phase 4
        GAME_PHASES,
        createGameState,
        gameState: () => gameState,
        rollD20,
        rollInitiative,
        advancePhase,
        placeCard,
        advanceResolution,
        // Phase 5 - Combat
        rollAttack,
        rollDefense,
        getCombatOutcome,
        calculateDamage,
        applyDamage,
        resolveCombat,
        checkGameOver,
        COMBAT_OUTCOMES,
        currentCombatResult: () => currentCombatResult,
        // Phase 9
        gameStorage,
        campaignStorage,
        themeStorage,
        testState: () => testState,
        runTestSuite,
        parseEffect,
        applyParsedEffects,
        isEffectParseable,
        SKILL_ACTION_KEYWORDS,
        STATUS_EFFECTS
    };

}());
