/**
 * CardGame Designer — Layout Configuration
 * Data model, default layout generator, template variable resolver, save/load.
 *
 * Layout configs define how card elements are positioned within zones.
 * Each card type × card size combination gets its own layout config.
 * Screen display contexts (standard/compact/mini/full) also have layouts.
 *
 * Depends on: window.CardGame.Constants (CARD_TYPES, CARD_RENDER_CONFIG, CARD_SIZES, LAYOUT_ZONES)
 * Exposes: window.CardGame.Designer.LayoutConfig
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Designer = window.CardGame.Designer || {};

    function C() { return window.CardGame.Constants; }

    // ── Element ID counter ──────────────────────────────────────────
    let _nextId = 1;
    function nextElementId(prefix) {
        return (prefix || "el") + "_" + (_nextId++);
    }

    // ── Create a single element ─────────────────────────────────────
    function createElement(type, overrides) {
        let el = {
            id: nextElementId(type),
            type: type,
            visible: true,
            style: {},
            content: "",
            icon: ""
        };
        if (overrides) Object.assign(el, overrides);
        return el;
    }

    // ── Template variable resolver ──────────────────────────────────
    // Resolves {{varName}} in a string against a card object and type config
    function resolveTemplate(template, card, typeCfg) {
        if (!template || typeof template !== "string") return template || "";
        return template.replace(/\{\{(\w+(?:\.\w+)?)\}\}/g, function(match, key) {
            // Type config vars
            if (key === "typeColor") return typeCfg?.color || "#666";
            if (key === "typeBg") return typeCfg?.bg || "#fff";
            if (key === "typeIcon") return typeCfg?.icon || "help";
            if (key === "cardName") return card?.name || "Unnamed";
            if (key === "imageUrl") return card?.portraitUrl || card?.imageUrl || "";
            // Nested stat access: stat.STR, stat.AGI, etc.
            if (key.startsWith("stat.")) {
                let statKey = key.substring(5);
                return (card?.stats && card.stats[statKey]) || 0;
            }
            // Nested need access: need.hp, need.energy, need.morale
            if (key.startsWith("need.")) {
                let needKey = key.substring(5);
                return (card?.needs && card.needs[needKey]) || 0;
            }
            // Nested field access: field.slot, field.rarity, etc.
            if (key.startsWith("field.")) {
                let fieldKey = key.substring(6);
                let val = card?.[fieldKey];
                return val != null ? String(val) : "";
            }
            // Direct card properties
            if (card && card[key] != null) return String(card[key]);
            return "";
        });
    }

    // ── Compute derived game stats from raw character stats ─────────
    function deriveGameStats(card) {
        let stats = card?.stats || {};
        let atk = stats.STR || stats.str || 0;
        let agi = stats.AGI || stats.agi || 0;
        let rawEnd = stats.END || stats.end || 0;
        let ap = Math.max(2, Math.floor(rawEnd / 5) + 1);
        let cha = stats.CHA || stats.cha || 0;
        return { atk: atk, init: agi, ap: ap, cha: cha };
    }

    // ── Default layout generators per card type ─────────────────────
    // These produce layouts matching the current rendering exactly.

    function defaultCharacterLayout(sizeKey) {
        let sz = C().CARD_SIZES[sizeKey] || C().CARD_SIZES.poker;
        let isSmall = sz.px[0] < 600;  // mini and bridge-ish
        let isTiny = sz.px[0] < 550;   // mini only
        return {
            cardType: "character",
            cardSize: sizeKey,
            version: C().DEFAULT_LAYOUT_VERSION,
            zones: {
                header: {
                    height: 8,
                    elements: [
                        createElement("stackBorder", {
                            id: "stackTop", content: "{{cardName}}", icon: "{{typeIcon}}",
                            style: { fontSize: isTiny ? 8 : 10, fontWeight: 700, color: "#fff", background: "{{typeColor}}" }
                        }),
                        createElement("label", {
                            id: "cardName", content: "{{cardName}}",
                            style: { fontSize: isTiny ? 10 : 12, fontWeight: 700, color: "{{typeColor}}", textAlign: "center", textShadow: "0 0 4px #fff" }
                        })
                    ]
                },
                image: {
                    height: isTiny ? 40 : 45,
                    elements: [
                        createElement("image", {
                            id: "portrait", source: "{{imageUrl}}",
                            style: { objectFit: "cover" }
                        })
                    ]
                },
                stats: {
                    height: isTiny ? 25 : 22,
                    elements: [
                        createElement("iconDetail", {
                            id: "raceClass", icon: "badge",
                            content: "{{race}} {{_templateClass}} Lv.{{level}}",
                            style: { fontSize: isTiny ? 8 : 9 }
                        }),
                        createElement("statRow", {
                            id: "gameStats",
                            fields: ["atk", "init", "ap", "cha"],
                            labels: { atk: "ATK", init: "INIT", ap: "AP", cha: "CHA" },
                            prefix: { atk: "+", init: "+", cha: "+" },
                            style: { fontSize: isTiny ? 8 : 9, fontWeight: 600 }
                        }),
                        createElement("needBar", {
                            id: "hpBar", field: "hp", color: "#e53935", label: "HP", abbrev: "HP",
                            maxField: "hpMax", maxDefault: 20,
                            style: { fontSize: isTiny ? 7 : 8 },
                            visible: !isTiny
                        }),
                        createElement("needBar", {
                            id: "nrgBar", field: "energy", color: "#1E88E5", label: "Energy", abbrev: "NRG",
                            maxField: "stat.MAG", maxDefault: 14,
                            style: { fontSize: isTiny ? 7 : 8 },
                            visible: !isTiny
                        }),
                        createElement("needBar", {
                            id: "mrlBar", field: "morale", color: "#43A047", label: "Morale", abbrev: "MRL",
                            maxField: "moraleMax", maxDefault: 20,
                            style: { fontSize: isTiny ? 7 : 8 },
                            visible: !isTiny
                        })
                    ]
                },
                details: {
                    height: 0,
                    elements: []
                },
                footer: {
                    height: 5,
                    elements: []
                }
            },
            overlays: [
                createElement("cornerIcon", { id: "cornerTL", position: "top-left" }),
                createElement("cornerIcon", { id: "cornerTR", position: "top-right" }),
                createElement("cornerIcon", { id: "cornerBR", position: "bottom-right" }),
                createElement("stackBorder", { id: "stackRight", position: "right", style: { background: "{{typeColor}}" } })
            ]
        };
    }

    function defaultGenericLayout(cardType, configKey, sizeKey) {
        let config = C().CARD_RENDER_CONFIG;
        let renderCfg = config[configKey] || config[cardType] || config.action;
        let sz = C().CARD_SIZES[sizeKey] || C().CARD_SIZES.poker;
        let isSmall = sz.px[0] < 600;
        let isTiny = sz.px[0] < 550;

        // Build detail elements from CARD_RENDER_CONFIG
        let detailElements = [];

        // Header field (slot + rarity line)
        if (renderCfg.headerField) {
            let hf = renderCfg.headerField;
            let el = createElement("iconDetail", {
                id: "headerField",
                icon: hf.icon || "info",
                content: hf.label || ("{{field." + hf.field + "}}"),
                showRarity: hf.showRarity || false,
                style: { fontSize: isTiny ? 8 : 9 }
            });
            detailElements.push(el);
        }

        // Detail fields
        if (renderCfg.details) {
            for (let i = 0; i < renderCfg.details.length; i++) {
                let d = renderCfg.details[i];
                if (d.type === "stats") {
                    detailElements.push(createElement("statRow", {
                        id: "detailStats_" + i,
                        fields: d.fields,
                        labels: d.labels || {},
                        style: { fontSize: isTiny ? 8 : 9 }
                    }));
                } else if (d.type === "warning") {
                    detailElements.push(createElement("label", {
                        id: "warning_" + i,
                        content: d.text,
                        icon: "warning",
                        style: { fontSize: isTiny ? 7 : 8, color: "#C62828", fontWeight: 600 }
                    }));
                } else if (d.label) {
                    detailElements.push(createElement("iconDetail", {
                        id: "staticLabel_" + i,
                        icon: d.icon || "info",
                        content: d.label,
                        style: { fontSize: isTiny ? 8 : 9 },
                        cssClass: d.class || ""
                    }));
                } else if (d.field) {
                    detailElements.push(createElement("iconDetail", {
                        id: "field_" + d.field,
                        icon: d.icon || "info",
                        content: (d.prefix || "") + "{{field." + d.field + "}}" + (d.suffix || ""),
                        fieldName: d.field,
                        fieldType: d.type || "text",
                        style: { fontSize: isTiny ? 8 : 9 },
                        cssClass: d.class || ""
                    }));
                }
            }
        }

        // Footer elements
        let footerElements = [];
        if (renderCfg.footer) {
            for (let i = 0; i < renderCfg.footer.length; i++) {
                let f = renderCfg.footer[i];
                if (f.label) {
                    footerElements.push(createElement("iconDetail", {
                        id: "footer_" + i,
                        icon: f.icon || "info",
                        content: f.label,
                        style: { fontSize: isTiny ? 7 : 8 },
                        cssClass: f.class || ""
                    }));
                } else if (f.field === "reusable") {
                    footerElements.push(createElement("iconDetail", {
                        id: "footer_reusable",
                        icon: f.icon,
                        altIcon: f.altIcon,
                        content: "{{field.reusable}}",
                        trueLabel: f.trueLabel,
                        falseLabel: f.falseLabel,
                        fieldName: "reusable",
                        fieldType: "boolean",
                        style: { fontSize: isTiny ? 7 : 8 }
                    }));
                } else if (f.field) {
                    footerElements.push(createElement("iconDetail", {
                        id: "footer_" + f.field,
                        icon: f.icon || "info",
                        content: (f.prefix || "") + "{{field." + f.field + "}}" + (f.suffix || ""),
                        fieldName: f.field,
                        defaultValue: f.default,
                        style: { fontSize: isTiny ? 7 : 8 },
                        cssClass: f.class || ""
                    }));
                }
            }
        }

        return {
            cardType: cardType,
            cardSize: sizeKey,
            version: C().DEFAULT_LAYOUT_VERSION,
            zones: {
                header: {
                    height: 8,
                    elements: [
                        createElement("stackBorder", {
                            id: "stackTop", content: "{{cardName}}", icon: "{{typeIcon}}",
                            style: { fontSize: isTiny ? 8 : 10, fontWeight: 700, color: "#fff", background: "{{typeColor}}" }
                        }),
                        createElement("label", {
                            id: "cardName", content: "{{cardName}}",
                            style: { fontSize: isTiny ? 10 : 12, fontWeight: 700, color: "{{typeColor}}", textAlign: "center", textShadow: "0 0 4px #fff" }
                        })
                    ]
                },
                image: {
                    height: isTiny ? 35 : 42,
                    elements: [
                        createElement("image", {
                            id: "cardImage", source: "{{imageUrl}}",
                            style: { objectFit: "cover" },
                            placeholderIcon: renderCfg.placeholderIcon || "help",
                            placeholderColor: renderCfg.placeholderColor || "{{typeColor}}"
                        })
                    ]
                },
                stats: {
                    height: 0,
                    elements: []
                },
                details: {
                    height: isTiny ? 30 : 25,
                    elements: detailElements
                },
                footer: {
                    height: footerElements.length > 0 ? 5 : 0,
                    elements: footerElements
                }
            },
            overlays: [
                createElement("cornerIcon", { id: "cornerTL", position: "top-left" }),
                createElement("cornerIcon", { id: "cornerTR", position: "top-right" }),
                createElement("cornerIcon", { id: "cornerBR", position: "bottom-right" }),
                createElement("stackBorder", { id: "stackRight", position: "right", style: { background: "{{typeColor}}" } })
            ]
        };
    }

    // ── Compact layout (for hand tray) ──────────────────────────────
    function defaultCompactLayout(cardType) {
        let typeCfg = C().CARD_TYPES[cardType] || C().CARD_TYPES.item;
        let isChar = cardType === "character";

        let statsElements = [];
        if (isChar) {
            statsElements.push(createElement("statRow", {
                id: "compactStats", fields: ["atk", "init", "ap", "cha"],
                labels: { atk: "ATK", init: "INIT", ap: "AP", cha: "CHA" },
                prefix: { atk: "+", init: "+", cha: "+" },
                style: { fontSize: 7, fontWeight: 600 },
                compact: true
            }));
        } else {
            statsElements.push(createElement("label", {
                id: "compactStat", content: "{{compactStat}}",
                style: { fontSize: 7, fontWeight: 600, textAlign: "center" }
            }));
        }

        return {
            cardType: cardType,
            cardSize: "_compact",
            version: C().DEFAULT_LAYOUT_VERSION,
            zones: {
                header: {
                    height: 14,
                    elements: [
                        createElement("stackBorder", {
                            id: "stackTop", content: "{{cardName}}", icon: "{{typeIcon}}",
                            style: { fontSize: 7, fontWeight: 700, color: "#fff", background: "{{typeColor}}" }
                        })
                    ]
                },
                image: {
                    height: 46,
                    elements: [
                        createElement("image", {
                            id: "compactImage", source: "{{imageUrl}}",
                            style: { objectFit: "cover" }
                        })
                    ]
                },
                stats: {
                    height: 40,
                    elements: statsElements
                },
                details: { height: 0, elements: [] },
                footer: { height: 0, elements: [] }
            },
            overlays: [
                createElement("stackBorder", { id: "stackRight", position: "right", style: { background: "{{typeColor}}" } })
            ]
        };
    }

    // ── Mini layout (for action bar) ────────────────────────────────
    function defaultMiniLayout(cardType) {
        return {
            cardType: cardType,
            cardSize: "_mini",
            version: C().DEFAULT_LAYOUT_VERSION,
            zones: {
                header: {
                    height: 30,
                    elements: [
                        createElement("stackBorder", {
                            id: "stackTop", content: "{{cardName}}", icon: "{{typeIcon}}",
                            style: { fontSize: 6, fontWeight: 700, color: "#fff", background: "{{typeColor}}" }
                        })
                    ]
                },
                image: {
                    height: 70,
                    elements: [
                        createElement("image", {
                            id: "miniImage", source: "{{imageUrl}}",
                            style: { objectFit: "cover" }
                        })
                    ]
                },
                stats: { height: 0, elements: [] },
                details: { height: 0, elements: [] },
                footer: { height: 0, elements: [] }
            },
            overlays: []
        };
    }

    // ── Generate default layout for any type + size combination ─────
    function generateDefaultLayout(cardType, sizeKey) {
        // Screen display contexts
        if (sizeKey === "_compact") return defaultCompactLayout(cardType);
        if (sizeKey === "_mini") return defaultMiniLayout(cardType);

        // Character has its own generator
        if (cardType === "character") return defaultCharacterLayout(sizeKey);

        // Item subtypes map to their render config keys
        let configKey = cardType;
        if (cardType === "item") configKey = "weapon"; // default item subtype uses weapon render config

        // Scenario, loot have custom renderers in current code but can use generic with their render configs
        return defaultGenericLayout(cardType, configKey, sizeKey);
    }

    // ── Generate all default layouts for a deck ─────────────────────
    function generateAllDefaultLayouts() {
        let layouts = {};
        let types = Object.keys(C().CARD_TYPES);
        let sizes = Object.keys(C().CARD_SIZES);
        let screenSizes = ["_compact", "_mini"];

        for (let type of types) {
            // Print sizes
            for (let size of sizes) {
                let key = type + ":" + size;
                layouts[key] = generateDefaultLayout(type, size);
            }
            // Screen contexts
            for (let ss of screenSizes) {
                let key = type + ":" + ss;
                layouts[key] = generateDefaultLayout(type, ss);
            }
        }
        return layouts;
    }

    // ── Get layout for a card type + size, with fallback chain ──────
    function getLayout(deck, cardType, sizeKey) {
        let key = cardType + ":" + sizeKey;

        // 1. Check deck-specific layout configs
        if (deck?.layoutConfigs && deck.layoutConfigs[key]) {
            return deck.layoutConfigs[key];
        }

        // 2. Generate default
        return generateDefaultLayout(cardType, sizeKey);
    }

    // ── Save a layout config to a deck ──────────────────────────────
    function saveLayout(deck, layout) {
        if (!deck) return;
        if (!deck.layoutConfigs) deck.layoutConfigs = {};
        let key = layout.cardType + ":" + layout.cardSize;
        deck.layoutConfigs[key] = JSON.parse(JSON.stringify(layout));
    }

    // ── Deep clone a layout ─────────────────────────────────────────
    function cloneLayout(layout) {
        return JSON.parse(JSON.stringify(layout));
    }

    // ── Add element to a zone in a layout ───────────────────────────
    function addElement(layout, zoneName, elementType, overrides) {
        let zone = layout.zones[zoneName];
        if (!zone) return null;
        let el = createElement(elementType, overrides);
        zone.elements.push(el);
        return el;
    }

    // ── Remove element from a zone ──────────────────────────────────
    function removeElement(layout, zoneName, elementId) {
        let zone = layout.zones[zoneName];
        if (!zone) return false;
        let idx = zone.elements.findIndex(function(e) { return e.id === elementId; });
        if (idx === -1) return false;
        zone.elements.splice(idx, 1);
        return true;
    }

    // ── Move element within a zone (reorder) ────────────────────────
    function moveElement(layout, zoneName, elementId, newIndex) {
        let zone = layout.zones[zoneName];
        if (!zone) return false;
        let idx = zone.elements.findIndex(function(e) { return e.id === elementId; });
        if (idx === -1 || idx === newIndex) return false;
        let el = zone.elements.splice(idx, 1)[0];
        zone.elements.splice(newIndex, 0, el);
        return true;
    }

    // ── Find element by ID across all zones ─────────────────────────
    function findElement(layout, elementId) {
        for (let zoneName of C().LAYOUT_ZONES) {
            let zone = layout.zones[zoneName];
            if (!zone) continue;
            let el = zone.elements.find(function(e) { return e.id === elementId; });
            if (el) return { element: el, zone: zoneName };
        }
        // Check overlays
        if (layout.overlays) {
            let ol = layout.overlays.find(function(e) { return e.id === elementId; });
            if (ol) return { element: ol, zone: "_overlays" };
        }
        return null;
    }

    // ── Update element style ────────────────────────────────────────
    function updateElementStyle(layout, elementId, styleUpdates) {
        let found = findElement(layout, elementId);
        if (!found) return false;
        Object.assign(found.element.style, styleUpdates);
        return true;
    }

    // ── Update zone height ──────────────────────────────────────────
    function updateZoneHeight(layout, zoneName, newHeight) {
        let zone = layout.zones[zoneName];
        if (!zone) return false;
        zone.height = Math.max(0, Math.min(100, newHeight));
        return true;
    }

    // ── Expose ──────────────────────────────────────────────────────
    window.CardGame.Designer.LayoutConfig = {
        resolveTemplate: resolveTemplate,
        deriveGameStats: deriveGameStats,
        generateDefaultLayout: generateDefaultLayout,
        generateAllDefaultLayouts: generateAllDefaultLayouts,
        getLayout: getLayout,
        saveLayout: saveLayout,
        cloneLayout: cloneLayout,
        createElement: createElement,
        addElement: addElement,
        removeElement: removeElement,
        moveElement: moveElement,
        findElement: findElement,
        updateElementStyle: updateElementStyle,
        updateZoneHeight: updateZoneHeight
    };

    console.log("[CardGame] Designer.LayoutConfig loaded");
}());
