/**
 * CardGame Designer — Layout Renderer
 * Renders cards from layout configs using the zone-based model.
 * Replaces all existing card rendering (CardFace, renderCharacterBody, etc.)
 * when a layout config is available.
 *
 * Depends on:
 *   - window.CardGame.Constants (CARD_TYPES, CARD_SIZES)
 *   - window.CardGame.Designer.LayoutConfig (resolveTemplate, deriveGameStats, getLayout)
 *   - window.CardGame.Rendering (showImagePreview — for image click)
 *
 * Exposes: window.CardGame.Designer.LayoutRenderer
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Designer = window.CardGame.Designer || {};

    function C() { return window.CardGame.Constants; }
    function LC() { return window.CardGame.Designer.LayoutConfig; }
    function R() { return window.CardGame.Rendering || {}; }

    // ── Resolve template in an element's content ────────────────────
    function resolveContent(element, card, typeCfg) {
        return LC().resolveTemplate(element.content || "", card, typeCfg);
    }

    function resolveIcon(element, card, typeCfg) {
        return LC().resolveTemplate(element.icon || "", card, typeCfg);
    }

    // ── Get field value from card, with default fallback ────────────
    function getFieldValue(card, fieldName, defaultValue) {
        if (!fieldName || !card) return defaultValue != null ? defaultValue : null;
        // Handle nested paths like "stats.STR"
        let parts = fieldName.split(".");
        let val = card;
        for (let p of parts) {
            if (val == null) return defaultValue != null ? defaultValue : null;
            val = val[p];
        }
        return val != null ? val : (defaultValue != null ? defaultValue : null);
    }

    // ── Build inline style from element style config ────────────────
    function buildStyle(element, card, typeCfg, scale) {
        let s = element.style || {};
        let result = {};
        scale = scale || 1;

        if (s.fontSize) result.fontSize = (s.fontSize * scale) + "px";
        if (s.fontWeight) result.fontWeight = s.fontWeight;
        if (s.color) result.color = LC().resolveTemplate(s.color, card, typeCfg);
        if (s.background) result.background = LC().resolveTemplate(s.background, card, typeCfg);
        if (s.textAlign) result.textAlign = s.textAlign;
        if (s.textShadow) result.textShadow = s.textShadow;
        if (s.padding) result.padding = s.padding;
        if (s.fontStyle) result.fontStyle = s.fontStyle;
        if (s.objectFit) result.objectFit = s.objectFit;
        if (s.borderRadius != null) result.borderRadius = s.borderRadius + "px";

        return result;
    }

    // ── Element Renderers ───────────────────────────────────────────

    function renderStackBorder(el, card, typeCfg, scale, position) {
        let style = buildStyle(el, card, typeCfg, scale);
        let icon = resolveIcon(el, card, typeCfg);
        let content = resolveContent(el, card, typeCfg);

        if (position === "right") {
            // Vertical right border
            return m("div", {
                class: "cg2-stack-right",
                style: Object.assign({ background: style.background || typeCfg.color, color: "#fff" }, style)
            }, [
                icon ? m("span", { class: "material-symbols-outlined cg2-stack-right-icon" }, icon) : null
            ]);
        }
        // Top border (default)
        let maxLen = scale < 0.7 ? 10 : 18;
        let displayName = content.length > maxLen ? content.substring(0, maxLen - 1) + "\u2026" : content;
        return m("div", {
            class: "cg2-stack-top",
            style: Object.assign({ background: style.background || typeCfg.color, color: "#fff" }, style)
        }, [
            icon ? m("span", { class: "material-symbols-outlined cg2-stack-top-icon" }, icon) : null,
            m("span", { class: "cg2-stack-top-name" }, displayName)
        ]);
    }

    function renderImage(el, card, typeCfg, scale, opts) {
        let imageUrl = LC().resolveTemplate(el.source || "{{imageUrl}}", card, typeCfg);
        let style = buildStyle(el, card, typeCfg, scale);
        let noPreview = opts?.noPreview;

        if (imageUrl) {
            return m("div", { class: "cg2-card-image-area" },
                m("img", {
                    src: imageUrl,
                    class: "cg2-card-img",
                    style: Object.assign(noPreview ? {} : { cursor: "pointer" }, style),
                    onclick: noPreview ? null : function(e) {
                        e.stopPropagation();
                        if (R().showImagePreview) R().showImagePreview(imageUrl);
                    },
                    onerror: function(e) { e.target.style.display = "none"; }
                })
            );
        }
        // Placeholder
        let phIcon = el.placeholderIcon || typeCfg.icon;
        let phColor = LC().resolveTemplate(el.placeholderColor || "{{typeColor}}", card, typeCfg);
        return m("div", { class: "cg2-card-image-area" },
            m("span", {
                class: "material-symbols-outlined cg2-placeholder-icon",
                style: { color: phColor }
            }, phIcon)
        );
    }

    function renderLabel(el, card, typeCfg, scale) {
        let content = resolveContent(el, card, typeCfg);
        if (!content && !el.icon) return null;
        let style = buildStyle(el, card, typeCfg, scale);
        let icon = resolveIcon(el, card, typeCfg);

        if (icon) {
            return m("div", { class: "cg2-card-detail cg2-icon-detail" + (el.cssClass ? " " + el.cssClass : ""), style: style }, [
                m("span", { class: "material-symbols-outlined cg2-detail-icon" }, icon),
                m("span", content)
            ]);
        }
        return m("div", { class: "cg2-card-name", style: style }, content);
    }

    function renderStatRow(el, card, typeCfg, scale) {
        let fields = el.fields || [];
        let labels = el.labels || {};
        let prefix = el.prefix || {};
        let style = buildStyle(el, card, typeCfg, scale);

        // For character cards, derive game stats
        let gameStats = LC().deriveGameStats(card);
        let statValues = {};
        for (let f of fields) {
            if (gameStats[f] != null) {
                statValues[f] = gameStats[f];
            } else {
                statValues[f] = getFieldValue(card, f, 0);
            }
        }

        if (el.compact) {
            // Compact stat badges for hand tray
            return m("div", { class: "cg2-compact-stats", style: style },
                fields.map(function(f) {
                    let val = (prefix[f] || "") + statValues[f];
                    return m("span", { class: "cg2-compact-stat" }, val + " " + (labels[f] || f.toUpperCase()));
                })
            );
        }

        return m("div", { class: "cg2-game-stats", style: style },
            fields.map(function(f) {
                let val = (prefix[f] || "") + statValues[f];
                return m("div", { class: "cg2-game-stat" }, [
                    m("span", { class: "cg2-game-stat-val" }, String(val)),
                    m("span", { class: "cg2-game-stat-label" }, labels[f] || f.toUpperCase())
                ]);
            })
        );
    }

    function renderNeedBar(el, card, typeCfg, scale) {
        let needs = card?.needs || {};
        let stats = card?.stats || {};
        let current = needs[el.field] != null ? needs[el.field] : 20;

        // Resolve max from maxField or maxDefault
        let max = el.maxDefault || 20;
        if (el.maxField) {
            if (el.maxField.startsWith("stat.")) {
                let statKey = el.maxField.substring(5);
                max = stats[statKey] || el.maxDefault || 20;
            } else {
                max = getFieldValue(card, el.maxField, el.maxDefault || 20);
            }
        }

        let pct = Math.max(0, Math.min(100, (current / max) * 100));
        let style = buildStyle(el, card, typeCfg, scale);

        return m("div", { class: "cg2-need-row", style: style }, [
            m("span", { class: "cg2-need-label" }, el.abbrev || el.label),
            m("div", { class: "cg2-need-track" }, [
                m("div", { class: "cg2-need-fill", style: { width: pct + "%", background: el.color } })
            ]),
            m("span", { class: "cg2-need-value" }, current + "/" + max)
        ]);
    }

    function renderIconDetail(el, card, typeCfg, scale) {
        let content = resolveContent(el, card, typeCfg);
        let icon = resolveIcon(el, card, typeCfg) || el.icon;

        // Handle boolean field (reusable)
        if (el.fieldType === "boolean") {
            let val = getFieldValue(card, el.fieldName, false);
            content = val ? (el.trueLabel || "Yes") : (el.falseLabel || "No");
            icon = val ? el.icon : (el.altIcon || el.icon);
        }

        // Handle requires field (object → string)
        if (el.fieldType === "requires") {
            let req = getFieldValue(card, el.fieldName, null);
            if (req && typeof req === "object" && Object.keys(req).length) {
                content = Object.entries(req).map(function(pair) { return pair[0] + " " + pair[1]; }).join(", ");
            } else {
                return null;
            }
        }

        // Handle array field
        if (el.fieldType === "array") {
            let arr = getFieldValue(card, el.fieldName, null);
            if (arr && Array.isArray(arr) && arr.length) {
                content = arr.join(", ");
            } else {
                return null;
            }
        }

        // Skip empty fields
        if (!content && el.fieldName) {
            let val = getFieldValue(card, el.fieldName, null);
            if (val == null) return null;
            content = String(val);
        }

        if (!content) return null;

        let style = buildStyle(el, card, typeCfg, scale);

        // Rarity stars
        if (el.showRarity && card.rarity) {
            let rarityN = ({ COMMON: 1, UNCOMMON: 2, RARE: 3, EPIC: 4, LEGENDARY: 5 })[card.rarity] || 1;
            return m("div", { class: "cg2-card-detail cg2-icon-detail" + (el.cssClass ? " " + el.cssClass : ""), style: style }, [
                icon ? m("span", { class: "material-symbols-outlined cg2-detail-icon" }, icon) : null,
                m("span", content),
                m("span", { class: "cg2-rarity", style: { marginLeft: "auto" } },
                    Array.from({ length: 5 }, function(_, i) {
                        return m("span", { class: "cg2-rarity-star" + (i < rarityN ? " cg2-rarity-star-active" : "") }, "\u2605");
                    })
                )
            ]);
        }

        return m("div", { class: "cg2-card-detail cg2-icon-detail" + (el.cssClass ? " " + el.cssClass : ""), style: style }, [
            icon ? m("span", { class: "material-symbols-outlined cg2-detail-icon" }, icon) : null,
            m("span", content)
        ]);
    }

    function renderCornerIcon(el, card, typeCfg, scale) {
        let type = card?.type || "item";
        let cfg = C().CARD_TYPES[type] || C().CARD_TYPES.item;
        let icon = cfg.icon;
        let style = {
            position: "absolute", color: cfg.color, lineHeight: "1"
        };
        if (el.position === "top-left") {
            style.top = "calc(var(--card-stack-top) + 4px)";
            style.left = "8px";
        }
        if (el.position === "top-right") {
            style.top = "calc(var(--card-stack-top) + 4px)";
            style.right = "calc(var(--card-stack-right) + 6px)";
        }
        if (el.position === "bottom-right") {
            style.bottom = "6px";
            style.right = "calc(var(--card-stack-right) + 6px)";
            style.transform = "rotate(180deg)";
        }
        return m("span", { class: "material-symbols-outlined cg2-corner-icon", style: style }, icon);
    }

    function renderDivider(el, card, typeCfg, scale) {
        return m("div", { class: "cg2-divider" });
    }

    function renderRarity(el, card, typeCfg, scale) {
        if (!card.rarity) return null;
        let n = ({ COMMON: 1, UNCOMMON: 2, RARE: 3, EPIC: 4, LEGENDARY: 5 })[card.rarity] || 1;
        return m("div", { class: "cg2-card-detail", style: buildStyle(el, card, typeCfg, scale) },
            Array.from({ length: 5 }, function(_, i) {
                return m("span", { class: "cg2-rarity-star" + (i < n ? " cg2-rarity-star-active" : "") }, "\u2605");
            })
        );
    }

    function renderEmoji(el, card, typeCfg, scale) {
        let style = buildStyle(el, card, typeCfg, scale);
        return m("span", { style: style }, el.content || "");
    }

    function renderCustom(el, card, typeCfg, scale) {
        let content = resolveContent(el, card, typeCfg);
        let style = buildStyle(el, card, typeCfg, scale);
        return m("div", { class: "cg2-card-detail", style: style }, content);
    }

    // ── Element renderer dispatch ───────────────────────────────────
    let elementRenderers = {
        stackBorder: renderStackBorder,
        image: renderImage,
        label: renderLabel,
        statRow: renderStatRow,
        needBar: renderNeedBar,
        iconDetail: renderIconDetail,
        cornerIcon: renderCornerIcon,
        divider: renderDivider,
        rarity: renderRarity,
        emoji: renderEmoji,
        custom: renderCustom
    };

    function renderElement(el, card, typeCfg, scale, opts) {
        if (!el || !el.visible) return null;
        let renderer = elementRenderers[el.type];
        if (!renderer) return m("div", { class: "cg2-card-detail" }, "[Unknown: " + el.type + "]");
        return renderer(el, card, typeCfg, scale, opts);
    }

    // ── Zone renderer ───────────────────────────────────────────────
    function renderZone(zoneName, zone, card, typeCfg, scale, opts) {
        if (!zone || zone.height <= 0) return null;
        let children = [];
        for (let el of zone.elements) {
            let rendered = renderElement(el, card, typeCfg, scale, opts);
            if (rendered) children.push(rendered);
        }
        if (children.length === 0 && zoneName !== "image") return null;

        let zoneStyle = {};
        if (zone.height > 0) {
            zoneStyle.flex = "0 0 " + zone.height + "%";
        }
        // Image zone has special overflow handling
        if (zoneName === "image") {
            zoneStyle.overflow = "hidden";
        }

        return m("div", {
            class: "cg2-layout-zone cg2-layout-zone-" + zoneName,
            style: zoneStyle
        }, children);
    }

    // ── LayoutCardFace Component ────────────────────────────────────
    // The main component that replaces CardFace when layout configs are available.
    function LayoutCardFace() {
        return {
            view: function(vnode) {
                let card = vnode.attrs.card;
                if (!card || !card.type) return m("div.cg2-card.cg2-card-empty", "No card");

                let type = card.type;
                let typeCfg = C().CARD_TYPES[type] || C().CARD_TYPES.item;
                let deck = vnode.attrs.deck;
                let sizeKey = vnode.attrs.sizeKey || "poker"; // default print size
                let compact = vnode.attrs.compact;
                let full = vnode.attrs.full;
                let bgImage = vnode.attrs.bgImage;
                let noPreview = vnode.attrs.noPreview;
                let designMode = vnode.attrs.designMode; // show zone outlines

                // Determine which layout context to use
                let layoutSizeKey = sizeKey;
                if (compact) layoutSizeKey = "_compact";
                else if (vnode.attrs.mini) layoutSizeKey = "_mini";

                // Get layout config
                let layout = vnode.attrs.layoutConfig || LC().getLayout(deck, type, layoutSizeKey);

                // Scale factor (for font sizes relative to card pixel size)
                let scale = 1;
                if (compact) scale = 0.75;
                if (vnode.attrs.mini) scale = 0.5;
                if (full) scale = 1.4;

                let opts = { noPreview: noPreview };

                // Build card style
                let cardStyle = { borderColor: typeCfg.color };
                if (bgImage) {
                    cardStyle.backgroundImage = "linear-gradient(rgba(255,255,255,0.5),rgba(255,255,255,0.5)), url('" + bgImage + "')";
                    cardStyle.backgroundSize = "cover, cover";
                    cardStyle.backgroundPosition = "center, center";
                }

                let sizeClass = compact ? " cg2-card-compact" : (full ? " cg2-card-full" : "");
                if (vnode.attrs.mini) sizeClass = " cg2-card-mini";
                let cardClass = "cg2-card cg2-card-front cg2-card-" + type + sizeClass;
                if (designMode) cardClass += " cg2-design-mode";

                // Render zones
                let zoneOrder = C().LAYOUT_ZONES;
                let zones = [];
                for (let zn of zoneOrder) {
                    let zone = layout.zones[zn];
                    let rendered = renderZone(zn, zone, card, typeCfg, scale, opts);
                    if (rendered) zones.push(rendered);
                }

                // Render overlays (corner icons, right border)
                let overlays = [];
                if (layout.overlays) {
                    for (let ol of layout.overlays) {
                        if (!ol.visible) continue;
                        if (ol.type === "stackBorder" && ol.position === "right") {
                            overlays.push(renderStackBorder(ol, card, typeCfg, scale, "right"));
                        } else if (ol.type === "cornerIcon") {
                            overlays.push(renderCornerIcon(ol, card, typeCfg, scale));
                        } else {
                            overlays.push(renderElement(ol, card, typeCfg, scale, opts));
                        }
                    }
                }

                // Incomplete badge
                let incomplete = isCardIncomplete(card);

                return m("div", { class: cardClass, style: cardStyle }, [
                    // Overlays render first (absolutely positioned)
                    ...overlays,
                    // Incomplete indicator
                    incomplete ? m("div", {
                        class: "cg2-incomplete-badge",
                        title: "Card effect incomplete - needs definition"
                    }, "*") : null,
                    // Zone content
                    m("div", { class: "cg2-card-body cg2-layout-body" }, zones)
                ]);
            }
        };
    }

    // ── Card completeness check (same logic as cardFace.js) ─────────
    function isCardIncomplete(card) {
        if (!card) return true;
        let type = card.type;
        if (type === "character") return !card.stats;
        if (type === "action") return !card.onHit && !card.roll && !card.effect;
        if (type === "skill") return !card.modifier && !card.effect;
        if (type === "magic") return !card.effect;
        if (type === "talk") return !card.effect && !card.speechType;
        if (type === "item") return !card.effect && !card.atk && !card.def;
        if (type === "apparel") return !card.def && !card.effect;
        if (type === "encounter") return !card.behavior && !card.atk;
        if (type === "scenario") return !card.description;
        if (type === "loot") return !card.name;
        return false;
    }

    // ── Export LayoutCardFace for offscreen render (export pipeline) ─
    // Renders a card into a detached DOM element at exact pixel dimensions
    function renderCardToContainer(card, layout, containerEl, pxWidth, pxHeight, bgImage) {
        let type = card.type;
        let typeCfg = C().CARD_TYPES[type] || C().CARD_TYPES.item;

        // Set container dimensions
        containerEl.style.width = pxWidth + "px";
        containerEl.style.height = pxHeight + "px";
        containerEl.style.position = "absolute";
        containerEl.style.left = "-9999px";
        containerEl.style.top = "0";
        containerEl.style.overflow = "hidden";

        // Scale factor for print (base 180px → target width)
        let scale = pxWidth / 180;

        // Mount Mithril into the container
        m.render(containerEl, m(LayoutCardFace, {
            card: card,
            layoutConfig: layout,
            bgImage: bgImage,
            noPreview: true
        }));

        // Apply print-size CSS variables
        containerEl.style.setProperty("--card-width", pxWidth + "px");
        containerEl.style.setProperty("--card-height", pxHeight + "px");
        containerEl.style.setProperty("--card-stack-top", Math.round(22 * scale) + "px");
        containerEl.style.setProperty("--card-stack-right", Math.round(18 * scale) + "px");
        containerEl.style.setProperty("font-size", Math.round(10 * scale) + "px");
    }

    // ── Expose ──────────────────────────────────────────────────────
    window.CardGame.Designer.LayoutRenderer = {
        LayoutCardFace: LayoutCardFace,
        renderElement: renderElement,
        renderZone: renderZone,
        renderCardToContainer: renderCardToContainer,
        isCardIncomplete: isCardIncomplete
    };

    console.log("[CardGame] Designer.LayoutRenderer loaded");
}());
