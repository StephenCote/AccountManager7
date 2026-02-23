// Card Game v2 — Rendering: Card Face Renderers
// Updated for v3.1: stacking borders, simplified stats, character back extended info, CardFlipContainer
(function() {
    "use strict";
    const C = window.CardGame.Constants;
    const R = window.CardGame.Rendering;

    // ── Helpers ──────────────────────────────────────────────────────

    function abbreviateName(name, maxLen) {
        if (!name || name.length <= maxLen) return name || "";
        return name.substring(0, maxLen - 1) + "\u2026";
    }

    /** Render the two stacking border elements (top + right) for a card type */
    function stackingBorders(cfg, cardName) {
        return [
            m("div", {
                class: "cg2-stack-top",
                style: { background: cfg.color, color: "#fff" }
            }, [
                m("span", { class: "material-symbols-outlined cg2-stack-top-icon" }, cfg.icon),
                m("span", { class: "cg2-stack-top-name" }, abbreviateName(cardName, 18))
            ]),
            m("div", {
                class: "cg2-stack-right",
                style: { background: cfg.color, color: "#fff" }
            }, [
                m("span", { class: "material-symbols-outlined cg2-stack-right-icon" }, cfg.icon)
            ])
        ];
    }

    // ── Card Face Renderers (per type) ───────────────────────────────

    function renderCharacterBody(card, options) {
        let stats = card.stats || {};
        let needs = card.needs || {};
        let noPreview = options?.noPreview;
        // Derive game-relevant values from raw stats
        let atk = stats.STR || stats.str || 0;
        let agi = stats.AGI || stats.agi || 0;
        let rawEnd = stats.END || stats.end || 0;
        let ap = Math.max(2, Math.floor(rawEnd / 5) + 1);
        let cha = stats.CHA || stats.cha || 0;
        return [
            m("div", { class: "cg2-card-image-area" },
                card.portraitUrl
                    ? m("img", {
                        src: card.portraitUrl, class: "cg2-card-img",
                        style: noPreview ? {} : { cursor: "pointer" },
                        onclick: noPreview ? null : function(e) { e.stopPropagation(); CardGame.Rendering.showImagePreview(card.portraitUrl); }
                    })
                    : m("span", { class: "material-symbols-outlined cg2-placeholder-icon", style: { color: "#B8860B" } }, "person")
            ),
            m("div", { class: "cg2-card-details" }, [
                // Race + Class + Level
                m("div", { class: "cg2-card-detail cg2-icon-detail" }, [
                    m("span", { class: "material-symbols-outlined cg2-detail-icon" }, "badge"),
                    m("span", [card.race || "", card._templateClass ? " " + card._templateClass : ""].join("")),
                    card.level ? m("span", { style: { marginLeft: "auto", opacity: 0.7 } }, "Lv." + card.level) : null
                ]),
                // 4 game stats in a single row
                m("div", { class: "cg2-game-stats" }, [
                    m("div", { class: "cg2-game-stat" }, [m("span", { class: "cg2-game-stat-val" }, "+" + atk), m("span", { class: "cg2-game-stat-label" }, "ATK")]),
                    m("div", { class: "cg2-game-stat" }, [m("span", { class: "cg2-game-stat-val" }, "+" + agi), m("span", { class: "cg2-game-stat-label" }, "INIT")]),
                    m("div", { class: "cg2-game-stat" }, [m("span", { class: "cg2-game-stat-val" }, String(ap)), m("span", { class: "cg2-game-stat-label" }, "AP")]),
                    m("div", { class: "cg2-game-stat" }, [m("span", { class: "cg2-game-stat-val" }, "+" + cha), m("span", { class: "cg2-game-stat-label" }, "CHA")])
                ]),
                // 3 need bars
                m(R.NeedBar, { label: "Health", abbrev: "HP", current: needs.hp != null ? needs.hp : 20, max: 20, color: "#e53935" }),
                m(R.NeedBar, { label: "Energy", abbrev: "NRG", current: needs.energy != null ? needs.energy : 14, max: stats.MAG || 14, color: "#1E88E5" }),
                m(R.NeedBar, { label: "Morale", abbrev: "MRL", current: needs.morale != null ? needs.morale : 20, max: 20, color: "#43A047" })
            ])
        ];
    }

    function renderCharacterBackBody(card, bgImage) {
        let equip = card.equipped || {};
        let skills = card.activeSkills || [];
        let backStyle = { borderColor: "#B8860B" };
        if (bgImage) {
            backStyle.backgroundImage = "url('" + bgImage + "')";
            backStyle.backgroundSize = "cover";
            backStyle.backgroundPosition = "center";
        }
        let cfg = C.CARD_TYPES.character;
        return m("div", { class: "cg2-card cg2-card-front cg2-char-back", style: backStyle }, [
            // Stacking borders on back too
            stackingBorders(cfg, card.name)[0],
            stackingBorders(cfg, card.name)[1],
            R.cornerIcon("character", "top-left"),
            R.cornerIcon("character", "top-right"),
            m("div", { class: "cg2-char-back-title" }, card.name || "Unknown"),
            m("div", { class: "cg2-card-body" }, [
                // Alignment
                card.alignment ? R.iconDetail("balance", card.alignment.replace(/_/g, " ")) : null,
                // Body info (height, weight, body type/shape)
                (card.height || card.bodyType || card.bodyShape) ? m("div", { class: "cg2-char-back-body-info" }, [
                    card.height ? (function() {
                        let feet = Math.floor(card.height);
                        let inches = Math.round((card.height - feet) * 100);
                        let htStr = feet + "'" + inches + '"';
                        let wtStr = card.weight ? Math.round(card.weight) + " lbs" : "";
                        return R.iconDetail("straighten", htStr + (wtStr ? ", " + wtStr : ""));
                    })() : null,
                    card.bodyType ? R.iconDetail("fitness_center", card.bodyType.toLowerCase()) : null,
                    card.bodyShape ? R.iconDetail("accessibility_new", card.bodyShape.replace(/_/g, " ").toLowerCase()) : null
                ]) : null,
                // Description
                card.description ? m("div", { class: "cg2-char-back-desc" }, card.description) : null,
                // Personality
                card.personality ? m("div", { class: "cg2-char-back-personality" }, [
                    m("span", { class: "material-symbols-outlined cg2-detail-icon" }, "psychology"),
                    m("span", { style: { fontStyle: "italic" } }, card.personality)
                ]) : null,
                m("div", { class: "cg2-divider" }),
                // Skill slots
                m("div", { class: "cg2-equip-row" }, [
                    m("span", { class: "cg2-section-label" }, "Skills:"),
                    m("span", skills.map(s => m("span", { class: "cg2-slot" + (s ? " cg2-slot-filled" : "") }, s ? s.name : "\u2013")))
                ]),
                // Equipment slots
                m("div", { class: "cg2-equip-row" }, [
                    m("span", { class: "cg2-section-label" }, "Equip:"),
                    m("span", ["head", "body", "handL", "handR", "feet", "ring", "back"].map(slot =>
                        m("span", {
                            class: "cg2-slot" + (equip[slot] ? " cg2-slot-filled" : ""),
                            title: slot
                        }, equip[slot] ? equip[slot].name : slot.charAt(0).toUpperCase())
                    ))
                ]),
                // XP / Record (campaign data)
                card.xp != null || card.wins != null ? m("div", { class: "cg2-char-back-record" }, [
                    card.xp != null ? m("span", "XP: " + card.xp + (card.xpMax ? "/" + card.xpMax : "")) : null,
                    card.wins != null ? m("span", "W:" + card.wins + " L:" + (card.losses || 0)) : null
                ]) : null
            ]),
            R.cornerIcon("character", "bottom-right")
        ]);
    }

    // ── Scenario Card Body Renderer ──────────────────────────────────
    function renderScenarioBody(card, options) {
        let noPreview = options?.noPreview;
        let imageUrl = card.imageUrl;
        let effectColor = card.effect === "threat" ? "#c62828" : "#43A047";
        let effectLabel = card.effect === "threat" ? "Threat" : "Boon";
        let effectIcon = card.effect === "threat" ? "warning" : "eco";

        return [
            m("div", { class: "cg2-card-image-area" },
                imageUrl
                    ? m("img", {
                        src: imageUrl, class: "cg2-card-img",
                        style: noPreview ? {} : { cursor: "pointer" },
                        onclick: noPreview ? null : function(e) { e.stopPropagation(); CardGame.Rendering.showImagePreview(imageUrl); },
                        onerror: function(e) { e.target.style.display = "none"; }
                    })
                    : m("span", {
                        class: "material-symbols-outlined cg2-placeholder-icon",
                        style: { color: card.cardColor || "#1B5E20" }
                    }, card.icon || "explore")
            ),
            m("div", { class: "cg2-card-details" }, [
                m("div", { class: "cg2-card-detail cg2-icon-detail" }, [
                    m("span", { class: "material-symbols-outlined cg2-detail-icon", style: { color: effectColor } }, effectIcon),
                    m("span", { style: { color: effectColor, fontWeight: 600 } }, effectLabel)
                ]),
                card.description ? R.iconDetail("info", card.description) : null,
                card.bonus === "heal" ? R.iconDetail("favorite", "+" + (card.bonusAmount || 3) + " HP to all") : null,
                card.bonus === "energy" ? R.iconDetail("bolt", "+" + (card.bonusAmount || 2) + " Energy to all") : null,
                card.bonus === "loot" ? R.iconDetail("inventory_2", "Discover loot!") : null,
                card.threatBonus != null && card.effect === "threat" ? R.iconDetail("warning", "Threat DC +" + card.threatBonus) : null
            ])
        ];
    }

    // ── Loot Card Body Renderer ───────────────────────────────────────
    function renderLootBody(card, options) {
        let noPreview = options?.noPreview;
        let imageUrl = card.imageUrl;

        return [
            m("div", { class: "cg2-card-image-area" },
                imageUrl
                    ? m("img", {
                        src: imageUrl, class: "cg2-card-img",
                        style: noPreview ? {} : { cursor: "pointer" },
                        onclick: noPreview ? null : function(e) { e.stopPropagation(); CardGame.Rendering.showImagePreview(imageUrl); },
                        onerror: function(e) { e.target.style.display = "none"; }
                    })
                    : m("span", {
                        class: "material-symbols-outlined cg2-placeholder-icon",
                        style: { color: "#F57F17" }
                    }, "inventory_2")
            ),
            m("div", { class: "cg2-card-details" }, [
                card.source ? R.iconDetail("pets", "From: " + card.source) : null,
                card.rarity ? m("div", { class: "cg2-card-detail cg2-icon-detail" }, [
                    m("span", { class: "material-symbols-outlined cg2-detail-icon" }, "star"),
                    m("span", R.rarityStars(card.rarity))
                ]) : null,
                card.effect ? R.iconDetail("auto_awesome", card.effect) : null
            ])
        ];
    }

    // ── Unified Card Body Renderer (Phase 7.0) ──────────────────────
    // Uses CARD_RENDER_CONFIG to render card body based on type
    function renderCardBody(card, type, options) {
        let config = C.CARD_RENDER_CONFIG[type];
        if (!config) return [m("div", "Unknown type: " + type)];

        let imageUrl = card.imageUrl || card.portraitUrl;
        let placeholderColor = config.placeholderColor || C.CARD_TYPES[type]?.color;
        let noPreview = options?.noPreview;

        // Image area
        let imageArea = m("div", { class: "cg2-card-image-area" },
            imageUrl
                ? m("img", {
                    src: imageUrl,
                    class: "cg2-card-img",
                    style: noPreview ? {} : { cursor: "pointer" },
                    onclick: noPreview ? null : function(e) { e.stopPropagation(); CardGame.Rendering.showImagePreview(imageUrl); },
                    onerror: function(e) { e.target.style.display = "none"; }
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
                hf.showRarity ? m("span", { class: "cg2-rarity", style: { marginLeft: "auto" } }, R.rarityStars(card.rarity)) : null
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
                } else if (d.type === "requires") {
                    // Render requires map if non-empty; skip empty {}
                    if (card.requires && Object.keys(card.requires).length) {
                        details.push(R.iconDetail(d.icon, Object.entries(card.requires).map(([k, v]) => k + " " + v).join(", ")));
                    }
                } else if (d.type === "array" && card[d.field]?.length) {
                    details.push(R.iconDetail(d.icon, card[d.field].join(", ")));
                } else if (d.type === "flavor" && card[d.field]) {
                    details.push(m("div", { class: "cg2-flavor" }, "\u201C" + card[d.field] + "\u201D"));
                } else if (d.type === "warning") {
                    details.push(m("div", { class: "cg2-use-or-lose" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", marginRight: "4px" } }, "warning"),
                        d.text
                    ]));
                } else if (d.label) {
                    // Static label
                    details.push(R.iconDetail(d.icon, d.label, d.class));
                } else if (d.field) {
                    // Dynamic field — skip objects/arrays to avoid [object Object]
                    let value = card[d.field];
                    if (value == null && d.default != null) value = d.default;
                    if (value != null && typeof value !== "object") {
                        let text = (d.prefix || "") + value + (d.suffix || "");
                        details.push(R.iconDetail(d.icon, text, d.class));
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
                    footerItems.push(R.iconDetail(f.icon, f.label, f.class));
                } else if (f.field === "reusable") {
                    // Boolean toggle for reusable/consumable
                    let isReusable = card.reusable;
                    footerItems.push(R.iconDetail(isReusable ? f.icon : f.altIcon, isReusable ? f.trueLabel : f.falseLabel));
                } else {
                    let value = card[f.field];
                    if (value == null && f.default != null) value = f.default;
                    if (value != null) {
                        let text = (f.prefix || "") + value + (f.suffix || "");
                        footerItems.push(R.iconDetail(f.icon, text, f.class));
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
            case "scenario":
                if (card.effect === "threat") {
                    stats.push(m("span", { class: "cg2-compact-stat", style: "color:#c62828" }, [
                        m("span", { class: "material-symbols-outlined" }, "warning"), " Threat"
                    ]));
                } else {
                    stats.push(m("span", { class: "cg2-compact-stat", style: "color:#43A047" }, [
                        m("span", { class: "material-symbols-outlined" }, "eco"), " Boon"
                    ]));
                }
                break;
            case "loot":
                if (card.rarity) stats.push(m("span", { class: "cg2-compact-stat" }, card.rarity));
                if (card.effect) stats.push(m("span", { class: "cg2-compact-stat cg2-compact-effect" }, card.effect));
                break;
            case "character":
                let s = card.stats || {};
                let cAtk = s.STR || s.str || 0;
                let cAgi = s.AGI || s.agi || 0;
                let cEnd = s.END || s.end || 0;
                let cCha = s.CHA || s.cha || 0;
                stats.push(m("span", { class: "cg2-compact-stat" }, "ATK +" + cAtk));
                stats.push(m("span", { class: "cg2-compact-stat" }, "INIT +" + cAgi));
                stats.push(m("span", { class: "cg2-compact-stat" }, "AP " + Math.max(2, Math.floor(cEnd / 5) + 1)));
                stats.push(m("span", { class: "cg2-compact-stat" }, "CHA +" + cCha));
                break;
        }

        return stats.length > 0 ? stats : null;
    }

    // ── Get short stat label for card (used as badge in hand tray) ────
    function getCardStatLabel(card) {
        if (!card) return null;
        switch (card.type) {
            case "item":
                if (card.subtype === "weapon" && card.atk) return "+" + card.atk + " ATK";
                if (card.subtype === "armor" && card.def) return "+" + card.def + " DEF";
                if (card.subtype === "consumable" && card.hp) return "+" + card.hp + " HP";
                if (card.effect) return card.effect.length > 12 ? card.effect.slice(0, 10) + ".." : card.effect;
                break;
            case "skill":
                if (card.modifier) return card.modifier.length > 12 ? card.modifier.slice(0, 10) + ".." : card.modifier;
                break;
            case "magic":
                if (card.energyCost) return card.energyCost + " NRG";
                break;
            case "action":
                if (card.energyCost) return card.energyCost + " NRG";
                break;
            case "apparel":
                if (card.def) return "+" + card.def + " DEF";
                if (card.hpBonus) return "+" + card.hpBonus + " HP";
                break;
            case "encounter":
                if (card.atk && card.def) return card.atk + "/" + card.def;
                if (card.atk) return "ATK " + card.atk;
                if (card.def) return "DEF " + card.def;
                break;
            case "loot":
                if (card.rarity) {
                    let r = card.rarity;
                    return r.charAt(0) + r.slice(1).toLowerCase();
                }
                break;
        }
        return null;
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
        // Scenario cards are complete if they have a description
        if (type === "scenario") return !card.description;
        // Loot cards are complete if they have a name
        if (type === "loot") return !card.name;
        return false;
    }

    // ── CardFace Component ───────────────────────────────────────────
    function CardFace() {
        return {
            view(vnode) {
                let card = vnode.attrs.card;
                if (!card || !card.type) return m("div.cg2-card.cg2-card-empty", "No card");
                let type = card.type;
                let cfg = C.CARD_TYPES[type] || C.CARD_TYPES.item;
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

                // Use unified renderCardBody for types with config, special handling for character/scenario/loot
                let bodyFn;
                if (type === "character") {
                    bodyFn = () => renderCharacterBody(card, renderOpts);
                } else if (type === "scenario") {
                    bodyFn = () => renderScenarioBody(card, renderOpts);
                } else if (type === "loot") {
                    bodyFn = () => renderLootBody(card, renderOpts);
                } else if (C.CARD_RENDER_CONFIG[renderType]) {
                    bodyFn = () => renderCardBody(card, renderType, renderOpts);
                } else {
                    bodyFn = () => [m("div", "Unknown type: " + type)];
                }
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
                    let imgUrl = card.imageUrl || card.portraitUrl;
                    let hasImage = !!imgUrl;
                    compactBody = m("div", { class: "cg2-card-body cg2-card-body-compact" }, [
                        // Show image if available, otherwise show type icon as placeholder
                        hasImage ? m("div", { class: "cg2-card-image-area cg2-compact-image" },
                            m("img", { src: imgUrl, class: "cg2-card-img" })
                        ) : m("div", { class: "cg2-compact-icon-placeholder" }, [
                            m("span", {
                                class: "material-symbols-outlined",
                                style: { fontSize: "32px", color: cfg.color, opacity: 0.6 }
                            }, card.icon || cfg.icon)
                        ]),
                        // Show condensed stats based on type
                        m("div", { class: "cg2-compact-stats" }, renderCompactStats(card, type))
                    ]);
                }

                return m("div", {
                    class: cardClass,
                    style: cardStyle
                }, [
                    // Stacking borders (v3.1 print-first)
                    stackingBorders(cfg, card.name)[0],
                    stackingBorders(cfg, card.name)[1],
                    R.cornerIcon(type, "top-left"),
                    R.cornerIcon(type, "top-right"),
                    // Incomplete indicator (yellow asterisk)
                    incomplete ? m("div", {
                        class: "cg2-incomplete-badge",
                        title: "Card effect incomplete - needs definition"
                    }, "*") : null,
                    m("div", { class: "cg2-card-name", style: { color: cfg.color } }, card.name || "Unnamed"),
                    compact ? compactBody : m("div", { class: "cg2-card-body" }, bodyFn(card)),
                    R.cornerIcon(type, "bottom-right")
                ]);
            }
        };
    }

    // ── CardBack Component ───────────────────────────────────────────
    function CardBack() {
        return {
            view(vnode) {
                let type = vnode.attrs.type || "item";
                let cfg = C.CARD_TYPES[type] || C.CARD_TYPES.item;
                return m("div", {
                    class: "cg2-card cg2-card-back",
                    style: { background: cfg.color, borderColor: cfg.color }
                }, [
                    // Stacking borders on back too (v3.1)
                    stackingBorders(cfg, cfg.label)[0],
                    stackingBorders(cfg, cfg.label)[1],
                    m("div", { class: "cg2-back-pattern" }),
                    m("span", { class: "material-symbols-outlined cg2-back-icon" }, cfg.icon),
                    m("div", { class: "cg2-back-label" }, cfg.label)
                ]);
            }
        };
    }

    // ── CardFlipContainer Component (v3.1) ──────────────────────────
    // Wraps character cards with inline flip. Non-character cards pass through to CardFace.
    // Supports external flip state via flipped/onFlip props, or manages its own state.
    function CardFlipContainer() {
        let localFlipped = false;
        return {
            view(vnode) {
                let card = vnode.attrs.card;
                let bgImage = vnode.attrs.bgImage;
                let bgImageBack = vnode.attrs.bgImageBack;
                let compact = vnode.attrs.compact;
                let full = vnode.attrs.full;
                let noFlip = vnode.attrs.noFlip;

                // Only character cards flip inline
                if (!card || card.type !== "character" || noFlip) {
                    return m(CardFace, vnode.attrs);
                }

                // Support external or internal flip state
                let externalFlipped = vnode.attrs.flipped;
                let onFlip = vnode.attrs.onFlip;
                let isFlipped = externalFlipped != null ? externalFlipped : localFlipped;

                let sizeClass = compact ? " cg2-card-compact" : (full ? " cg2-card-full" : "");

                return m("div", {
                    class: "cg2-card-flipper" + sizeClass + (isFlipped ? " cg2-flipped" : ""),
                    ondblclick(e) {
                        e.stopPropagation();
                        if (onFlip) {
                            onFlip(!isFlipped);
                        } else {
                            localFlipped = !localFlipped;
                        }
                    },
                    title: "Double-click to flip"
                }, m("div", { class: "cg2-card-flipper-inner" }, [
                    m("div", { class: "cg2-card-flip-front" },
                        m(CardFace, { card, bgImage, compact, full, noPreview: vnode.attrs.noPreview })
                    ),
                    m("div", { class: "cg2-card-flip-back" },
                        renderCharacterBackBody(card, bgImageBack)
                    )
                ]));
            }
        };
    }

    window.CardGame.Rendering = window.CardGame.Rendering || {};
    Object.assign(window.CardGame.Rendering, {
        renderCharacterBody, renderCharacterBackBody, renderCardBody,
        renderScenarioBody, renderLootBody,
        renderCompactStats, getCardStatLabel, isCardIncomplete,
        CardFace, CardBack, CardFlipContainer
    });
})();
